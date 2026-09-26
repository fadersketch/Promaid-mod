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

## 八、实测六百八十七：服务端一轮全量审计（对编译产物）

需求方原话：「这个模组有关服务端的优化真的太少了，导致在服务端和服务器这个模组会出现各种各样的问题……
等你把上面的功能做好之后，再想想服务端方面有什么隐患，并修复一下。」所以这一轮把"服务端会不会出事"
拆成四条可复查的判据，对**编译产物**（不是源码）逐类跑：

| 检查 | 判据 | 本轮结果 |
| --- | --- | --- |
| **dist（最重要）** | 类的**成员描述符**（字段/方法，含 lambda 合成方法）里有没有客户端类型（`net/minecraft/client/`、`com/mojang/blaze3d/`、`org/lwjgl/`、`neoforge/client/`、`forge/client/`） | 两树 567 / 583 个类，**命中 0** |
| **`<clinit>`** | 静态初始化器所在的类有没有引用客户端类型（一加载就带出来） | 含 `<clinit>` 的类 200 个，**引用客户端类型的 0 个** |
| **事件订阅** | `@SubscribeEvent` 的参数类型是不是具体事件类（NeoForge 拒绝注册抽象事件类） | 参数可疑 **0** |
| **网络方向** | 新包的 `playToServer` / `playToClient` 与 handle 体的实际行为是否一致 | 仿创造飞行两个包，**方向正确** |

为什么第一条是重点：这就是 1.1.0 真正炸过专用服务器的那一类——**客户端类型只要出现在"服务端会加载的类"
的成员签名/合成方法描述符里，`RuntimeDistCleaner` 就拒绝，`dist.isClient()` 也救不了**（见本文档第一节）。
判据必须看**描述符**而不是"字符串里有没有 `client`"：`com/maidsmart/config/PromaidConfigScreen` 这种
天然客户端专属的类引用客户端类型是本职，它不进服务端加载路径就行。

复现方式（本机辅助件，`_*.py` 在 .gitignore 内、不入库）：`_srv687b.py`（dist，解析 class 文件的
常量池 + 字段/方法描述符）、`_srv687.py`（dist/clinit/订阅三合一的粗筛）、`_srv687c.py`
（只增不减的静态集合 + tick 路径里的全图扫描）。改完记得重跑：判据用的是**编译产物**，源码级 grep 不算数。

### 本轮的修复

1. **`com.maidsmart.tool.StateTables`（两树新增）**：给"只增不减"的 UUID 表上一道护栏——超过 2048 条就
   整表清空并记一行日志。挂上护栏的表：武装拴绳 10 张（`PULL_LOGGED` / `GROUND_TICKS` / `MODE_TICKS` /
   `DISMOUNT_GRACE` / `KIND_TICKS` / `DENY_LOG` / `RIDE_FALL` / `RIDE_LAST_Y` / `SEAT_NOW` / `SEAT_SIDE`，
   后两张在 1.20.1 树没有）、扫帚驱动 3 张（`DISMOUNT_LOGGED` / `TAKEOFF_AT` / `MOUNT_LOGGED`）、
   排班气泡 `NEXT_ALLOWED`、散步检查 `RUNS`、烹饪 `NO_FEED_DUMP_SINCE`、钓鱼 3 张。
   **为什么敢整表清空**：这些表里没有一张存"行为必须一致"的状态（限频时间戳清掉 = 下次多打一条日志，
   退避计时清掉 = 重新起算，几秒宽限清掉 = 少一次豁免）。**必须精确的 `LINKS` 没走这条路**——它有自己的
   `detach` / `onMaidJoin` 链路。为什么不用"按 UUID 存活逐个删"：那要拿每个键去 `level.getEntity(uuid)`
   查一遍（跨维度还要遍历所有 level），而收益只是"保留限频状态"，不划算。
2. **仿创造飞行那套状态表的寿命**：`MaidFreeFlightController` 的十一张表加了 5 分钟清扫（`level().getGameTime()`
   单调时钟，不用 `maid.tickCount`——它随实体重登归零，跨实体比大小会算错）；`release()` 补清原先漏掉的
   4 张表；主人轨迹只在"她真的可能飞"时才记（总开关默认关，旧写法等于给全服女仆每 tick 各记一份坐标）。
3. **遗留无重力交还**：`Entity#noGravity` 随实体 NBT 存盘，而状态表只在内存里——她若在飞行中存档退出/
   被卸载，重载后没人认领她，旧写法会"带着 NoGravity 永远飘着"。现在起飞时在 `persistentData` 写一枚
   自己的标记，每 tick 对一次账（只管**我们**留下的无重力，不误伤空袭/扫帚设的那些）。

### 查出来但本轮**没动**的（如实记在这里）

- `getAllEntities()` 全图实体扫描有 3 处：`ProMaidExtension` 启动清 `BRIDGING` 标记（一次性）、
  跨维度跟随（每 5 秒）、钓鱼保持座位走位（**每 3 tick**）。前两处频率无所谓；第三处在"女仆多 + 实体多"
  的服务器上是实打实的开销（20 次/秒 × 全部实体），但改它要给它建"活跃钓鱼女仆"索引，改动面大、收益
  又没有服务器实测数据支撑，所以留在这里等实测。
- 其余"只增不减"的静态集合（配方/物品/文件名缓存、`WeakHashMap` 以实体为键的那几张）本身有界，
  不在本轮修复范围。

### 回归口径

专用服务器实测是本节的最终判据（`python test_server.py 1201`：日志出现 `Done (` = PASS）。
本轮已跑，结果写在实测六百八十七 的 changelog 里。

## 九、实测六百八十八：本批的服务端视角（新注入点落在全服热路径上）

这一批新加的那个注入点（`FreeFlightWalkGuardMixin`）目标是 `PathNavigation.moveTo(DDDD)Z`——
**它不只是女仆在走**：全服每一个 `Mob`（僵尸、村民、动物、第三方模组的怪）的直连寻路都从这个方法进。
所以这里按服务端的口径记三件事：

| 关注点 | 本批的做法 |
| --- | --- |
| 注入点第一句 | `if (!(this.mob instanceof EntityMaid maid)) return;`——非女仆**一次 instanceof 就返回**，不改返回值、不碰任何字段。与 实测六百六十四 的 `SpawnerTorchNavGuardMixin` 同一个形状（同一个注入点、同一道早退）。 |
| 会不会多算路径 | 相反：命中时**直接返回 false（不做这次 A*）**。她的直连寻路（挖矿 / 伐木 / 农活）改成飞，省掉的正是这条链最贵的那一步（`PathNavigation.createPath`）。 |
| 状态表寿命 | 新增 3 张（`TRAVEL` / `TRAVEL_FLIGHT` / `TRAVEL_LOGGED`）：`release()` / `forget()` / `purge()` 三处都清；`TRAVEL` 另有 20 tick 的新鲜期（请求一断就摘，不等清扫）。`TRAVEL_LOGGED` 是 5 秒限频（同一只最多一条日志）。 |

**顺带修掉的一处往复**（既是行为问题、也是服务端开销问题）：原来"她落到工位 → 主人一动她就起飞去追 →
挖矿驱动又发一发远距离寻路 → 我们再把她飞回工位"会在**站着干活的主人**周围一直摆。现在
"在非战斗工作任务上 + 主人水平 16 格内 + 高差 8 格内"时不做跟随起飞（`workingNearOwner`）；
主人真走远或上天照旧跟。

**回归与复核**：两树 `javac` 0 错误；`_mixchk.py` PASS=171 FAIL=0（新注入点由目标类自己声明）；
`_srv687b.py` 对这版编译产物重跑——forge 568 / neo 585 个类，成员描述符带客户端类型 **0 命中**；
两个专用服务器（Forge 47.4.23 / NeoForge 21.1.250）启动实测 PASS（`Done (3.097s)` / `Done (1.093s)`，
之后各盯 30 秒）。上一节"查出来但没动的"那三条（`getAllEntities()` 每 3 tick 等）**这一批照旧没动**。
