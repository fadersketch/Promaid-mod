# 专用服务器兼容：根因、修复与回归工具（v1.1.0）

## 一、反馈服崩根因（1.20.1 Forge 47.4.x）

崩溃报告：`promaid` 条目为 ERROR，`Failed to create mod instance. ModID: promaid`：

```
java.lang.RuntimeException: Attempted to load class net/minecraft/client/gui/screens/Screen
    for invalid dist DEDICATED_SERVER
```

原因：`ProMaidMod` 构造器里内联注册客户端配置界面，lambda 被编译成主类内的合成方法
`lambda$new$2(Minecraft, Screen) -> Screen`。服务端 FML 反射 @Mod 主类时解析方法描述符，
连带加载 `Screen` → Forge `RuntimeDistCleaner` 拒绝 → 整个 mod 加载失败、服务器启动中止。

**结论：客户端类型只要出现在“服务端会加载的类的成员签名/合成方法描述符”里，即使运行时
有 `if (dist.isClient())` 保护也会炸。**

### 修复模式（两棵树均已应用）

带客户端类型签名的 lambda 一律移入独立客户端类，主类只在 `dist.isClient()` 分支里静态调用：

- `promaid_src_neo/com/maidsmart/client/PromaidClientSetup.java`（NeoForge：`IConfigScreenFactory`）
- `promaid_src/com/maidsmart/client/PromaidClientSetup.java`（Forge：`ConfigScreenHandler.ConfigScreenFactory`）
- `ProMaidMod` 构造器只保留：
  `if (FMLEnvironment.dist.isClient()) PromaidClientSetup.registerConfigScreen(...);`

服务端该分支不执行 → 客户端类永不被加载。

## 二、全盘审查工具（可重复执行）

| 脚本 | 检查内容 |
| --- | --- |
| `audit_dist.py` | 解析 class 文件，找出**成员描述符**里含客户端类型（`net/minecraft/client/`、`neoforge/client/`、blaze3d、lwjgl）的类；另标注 @EventBusSubscriber |
| `audit_clinit.py` | 扫描所有 `<clinit>` 字节码，确认静态初始化不会在服务端触发客户端类加载 |
| `audit_mixin_targets.py` | 解析全部 mixin 注解的 `method=` / `target=`，按 import 解析 `@Mixin(X.class)`，沿继承链校验成员存在性（外部可选模组用 `@Pseudo` / `require=0` 豁免） |
| `audit_mixin_callsites.py` | 对 `@Redirect/@ModifyArg/@ModifyConstant`，在目标方法的真实字节码里查找该成员调用是否真的存在（“Scanned 0 target(s)”类错误） |
| `audit_subscriber_params.py` | 校验所有 `@SubscribeEvent` 方法参数类型是否**抽象事件类**（NeoForge 拒绝注册抽象事件监听器） |
| `audit_bus_match.py` | 校验 `@EventBusSubscriber` 的 bus（MOD/GAME）与事件类型是否匹配（不匹配 = 静默不触发） |

## 三、专用服务器回归测试

```bash
python test_server.py 1201            # Forge 1.20.1 专用服（Java 17）
python test_server.py neoforge1211    # NeoForge 1.21.1 专用服（Java 21）
```

- 测试服目录：`C:/Users/Sketch/mc_server_test/{1201,neoforge1211}`（安装脚本用官方 installer）
- 判定：日志出现 `Done (` = PASS；否则打印 promaid 相关失败行 + 尾部日志
- 每次自动把 `patched/` 里的最新 jar 复制进测试服 mods

## 四、本轮实测发现并修复的问题

1. **dist 崩溃（1.20.1 + 1.21.1）**：主类客户端 lambda → 移入 `PromaidClientSetup`。
   A/B 实证：旧 jar 复现反馈服原样崩溃，修复版 `Done (1.633s)!`。
2. **`MaidSweepMixin`（1.21.1）**：`@Redirect` 目标残留 SRG 名 `m_44821_`（1.21.1 已无
   `EnchantmentHelper.getSweepingDamageRatio`，横扫倍率改读属性
   `Attributes.SWEEPING_DAMAGE_RATIO`）→ Redirector 必注入失败、启动崩溃。
   改为重定向 `AttributeMap.getValue(Holder)D` 并钳到 ≥0.5。
3. **`ScheduleRangeMixin`（1.21.1）**：目标包名仍是 Forge 的 `net.minecraftforge`（NeoForge
   为 `net.neoforged.neoforge`）→ 扫描 0 条指令、注入失败。改为 NeoForge 包名（方法仍是
   `restrictTo`，三处配置读取就在其中，javap 实证）。
4. **24 个文件的 tick 监听器（1.21.1）**：`@SubscribeEvent` 参数用抽象类
   `ServerTickEvent`/`ClientTickEvent` + instanceof 守卫——NeoForge 事件总线**拒绝注册**
   （`Cannot register listeners for abstract class`）。统一改为 `.Post` 子类并删除守卫。
   修复脚本：`fix_tickevent_params.py`。

## 五、已知边界

- 4 个客户端 mixin（MaidTtsVolumeMixin / MaidConfigMemoryMixin / MaidDebugPanelMixin /
  EmotionPoseMixin）已静态校验目标存在，但未实机跑客户端（专用服不加载它们）。
- 外部可选模组 mixin（callresponse 的 `@Pseudo`、maidmarriage 的 `require=0`）在宿主缺失时
  静默跳过，日志有 `Error loading class` WARN，属预期。
- 发布给服务器玩家时建议 bump 版本号（如 1.1.1），便于与旧 jar 区分。

## 六、联机「连接已丢失」：NeoForge 网络包方向注册错误（1.21.1，2026-09-09 实测）

**症状**：1.21.1 服务器启动正常，客户端能连上但立刻被踢（"连接已丢失"）。

**日志证据**（服务端 latest.log）：
```
Failed encoding custom payload maid_smart:region_sync:
java.lang.ClassCastException: com.maidsmart.build.BlueprintBookNetworking$RegionSyncPacket
    cannot be cast to net.minecraft.network.protocol.common.custom.DiscardedPayload
Khragg lost connection: Disconnected
```

**根因**：NeoForge 的 `PayloadRegistrar` 要求显式声明方向（`playToServer` / `playToClient`），
而 1.20.1 Forge 的 `SimpleChannel.registerMessage` 默认双向——移植时把
`BlueprintBookNetworking` 全部 25 个包一律写成 `playToServer`，其中 9 个实际是
服务器→客户端（S2C）；`ScheduleNetworking` 的 `MaidTaskResyncPacket` 同样写反。
服务器发这些包时客户端未在对应方向注册，编码阶段抛异常并踢人。

**修复**（`fix_net_directions.py` + schedule 手工改）：
S2C 改 `playToClient`：OpenBlueprintBookPacket / ProgressUpdatePacket / BuildHudPacket /
MemoryViewResponsePacket / MaidDebugResponsePacket / MemoryStateSyncPacket /
LlmStateSyncPacket / ProjectionDataPacket / RegionSyncPacket / MaidTaskResyncPacket。

**判定口径**：handle() 体内用 `Minecraft.getInstance()` / `Screen` / `pushClientState` /
`BlueprintAreaPreview.set*` / `BuildHudRenderer` = 客户端侧 → `playToClient`；
强转 `(ServerPlayer) ctx.player()` 并写服务端状态 = `playToServer`。

**审计工具**：`audit_net_directions.py`（注册方向 vs handle 体特征，两棵树都能跑）。
当前结果：neo 树 43 个注册全部方向正确；1.20.1 树 SimpleChannel 默认双向，无此问题。

**注意**：`playToClient` 的包 handle 里有客户端类**不影响专用服务器安全**——
handle 只在客户端执行，服务端只做编码；dist 审计（audit_dist.py）针对的是
「成员签名/静态初始化」这类必然加载路径，网络 handler 不在其列。

## 七、整合包服务端（PCL 1.20.1-Forge_47.4.21）

- 独立服务器目录：`C:\Users\Sketch\mc_server_test\pack1201`（Forge 47.4.21 服务端安装 + 整合包 mods/config）
- 主整合包 95 个模组中 **33 个纯客户端模组**必须剔除（否则 `Module org.lwjgl not found`
  等启动失败），保留 62 个：扫描/剔除脚本 `scan_clientonly_mods.py` / `tmp_strip_client.py`
- 实测：`Done (15.093s)!` + 60 秒延迟崩溃盯守无异常；promaid 驱动全部存活
- 一键启动：`C:\Users\Sketch\mc_server_test\开服-主整合包.bat`（-Xmx6G）
- **.bat 必须存成 GBK + CRLF**——UTF-8 编码的中文 .bat 会整段乱码不可执行（曾踩坑）
