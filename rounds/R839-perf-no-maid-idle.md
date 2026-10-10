# 实测八百三十九 · 没有女仆也在跑的每 tick 服务（性能）

## 需求 / 来源

开发者原话（本轮起因）：

> https://spark.lucko.me/KtOabKzvHW 作者您好，能不能修一下模组的性能问题，我这里面是超平坦并且没有女仆

即：**超平坦世界、世界内一只女仆都没有**，玩家仍感到卡。要求查清并修掉本模组在
"没有女仆"时的性能开销。

另（历史仍有效）：修完先不跑回归、只装本机等开发者测试；介绍文案与 changelog 等验收后再写；
版本号不擅自变动（本轮**不动 1.3.1**，同号重打包按 WORKFLOW §5.2 在 RELEASES.tsv 留痕）。

## 评估（先把报告本身读对，再谈改法）

### 一、spark 报告的取回与解码（方法留痕，可复现）

- `curl -o tools/_spark_raw.bin https://bytebin.lucko.me/KtOabKzvHW` → 38,677 B，`Content-Type:
  application/x-spark-sampler`；gzip 解开 169,268 B，内容是 **protobuf**（不是 JSON）。
- spark 的 proto 定义不在报告里，但**在本机可达的 viewer 前端包里**：
  `curl -o _c826.js https://spark.lucko.me/_next/static/chunks/826-07a18a2debec1903.js`
  内含 `super("spark.SamplerData",[...])` 等生成代码，字段号/类型全都在里面（本轮据此解码）。
- 关键三段 schema（本轮的实际判据来源）：

```
spark.SamplerData   1:metadata 2:threads(repeated ThreadNode) 3:classSources(map 类→模组名)
                    4:methodSources 5:lineSources 6:timeWindows(repeated int32)
                    7:timeWindowStatistics(map) 8:channelInfo
spark.ThreadNode    1:name 2:time(double) 3:children(repeated StackTraceNode)
                    4:times(repeated double) 5:childrenRefs(repeated int32)
spark.StackTraceNode 1:time(double) 2:children 3:className 4:methodName
                    5:parentLineNumber 6:lineNumber 7:methodDesc
                    8:times(repeated double) 9:childrenRefs(repeated int32)
```

- 本报告的实际形态：`threads` 只有 **1 个**（`Server thread`）；`classSources` 126 条
  （`类名 → 模组 id`）；`timeWindows` = 1 个窗口；压缩形态：树用 `childrenRefs`(f9) 指回
  **下标更小**的孩子，`times`(f8) 是**每窗口的执行时间(ms)**（已验证：父 = 子之和，
  如 `MinecraftServer.m_130011_ 1360.0 = IntegratedServer.m_5705_ 1336.0 + 24.0`）。
  （**f6 是行号，不是时间** —— 我一开始按"时间/采样数"试算过，全部自相矛盾，直到拿到 schema 才纠正。）
- 窗口统计：`ticks=921 / tps=19.9997 / msptMedian=13.7796 / msptMax=283.4043 / players=1 /
  entities=103 / chunks=3986 / duration=46032 ms`（46 秒）。

### 二、报告说的世界（是不是"没有女仆"）

实体表（`PlatformStatistics.world.entityCounts`）：
`minecraft:slime 67 / minecraft:item 18 / petrabbit:yellow_gray_pet_rabbit 14 /
jerotesvillage:executioner 2 / minecraft:player 1 / goety:minister 1` = 合计 103。
**没有任何 `touhou_little_maid:*`** → 开发者说的"没有女仆"属实。平台 Forge 47.4.26 / MC 1.20.1；
是**单人（集成服务器）**存档（栈里出现 `IntegratedServer.m_5705_`）。

### 三、46 秒里服务器线程的执行时间只用了 1360 ms（≈1.5 ms/tick），按模组归属：

| 归属 | self 时间 | 占比 | 说明 |
|---|---|---|---|
| Minecraft / Forge | 920 ms | 67.6% | 绝大部分是**下方那一个叶子帧** |
| JDK / fastutil | 292 ms | 21.5% | `LongAVLTreeSet.locateKey` 132 ms 等 |
| **promaid（本模组）** | **76 ms** | **5.6%** | 见下表 |
| goddess_of_maids(`com.myx00x.maid`) | （含在上一行 920/292 里） | — | 见下 |
| pillager_army 16 / twilightforest 8 / 其余各 4 | | | |

单帧 self 排行（前二名**都不是本模组**）：

1. `EntitySectionStorage.m_261191_` self **720 ms（53%）** —— 被
   `goddess_of_maids` 的 `com.myx00x.maid.event.ServerTickHandler.guard → combat.TargetKeeper.sweep`
   经 `Level.getEntities(EntityTypeTest,…)` 每 tick 调一次（inclusive 860 ms）。
2. `LongAVLTreeSet.locateKey` self **132 ms（9.7%）** —— 同一条实体区段（section）遍历路径。

**结论一：这份报告的最大开销来自另一个模组（goddess_of_maids 的每 tick 全实体区段扫描，
占服务器线程执行时间 53%+10%），不是 promaid。** 这一点必须如实告诉开发者，
不能拿本模组的改动去"认领"这口锅。

### 四、promaid 在"没有女仆"时到底在跑什么（self 时间，共 76 ms）

| 入口 | incl | self | 现状问题 |
|---|---|---|---|
| `MaidChunkLoadManager.tick` | 12 | **12** | 每 100 tick 跑，内部 **每个维度 2 次 `EntitySnapshot.of()`**（整张实体表拷贝） |
| `FishingChairService.sweep` | 16 | **8** | 每 20 tick：先全实体拷贝取女仆，再全实体拷贝取坐垫 |
| `HomeWorkMovementDriver.onServerTick` | 8 | **8** | 每 5 tick：全实体拷贝 |
| `CooldownHudTracker.broadcast` | 8 | 4 | 每 20 tick：全实体拷贝 |
| `AutonomousTaskManager.onServerTick` | 12 | 4 | **phase 判断在配置读取之后** → 每 tick 读两遍配置（`ForgeConfigSpec$IntValue.getRaw`） |
| `BlueprintBookNetworking.broadcastRegionSync` | 4 | 0 | 每 20 tick 全量重算建造区 |
| `MaidBuildBehavior.updateActiveBuilders` | 4 | 4 | 每 40 tick：全实体拷贝 |
| `RideBindManager.tick → restore` | 8 | 4+4 | 每 20 tick：全实体拷贝 |
| `MaidWoodBehavior/MaidMineBehavior.purgeStaleMaids` | 4+4 | 4+4 | 每 600 tick；已有 map 键遍历，代价小 |
| `MaidWeaponGuard.tick` | 4 | 4 | 每 20 tick：`level.getEntities(forClass(EntityMaid))` |
| `AutoCombatSwitch / NeutralThreatDriver / MaidFireGuard / MaidDangerMalusHandler` | 4×4 | 4×4 | 各自全实体拷贝（20/10/10/100 tick 节流） |
| `ChunkMap.handler$ggh000$promaid$pumpRemoteGui` | 4 | 4 | `ChunkMap.tick()` 尾注入，每 tick 三次"空队列即返回"的调用 |
| `AiMemoryManager / AffectEventHooks / WorkStatusReporter / MasterDeathTeleportHandler` | 4×4 | 4×4 | 节流 + 盒查询，代价小 |

**结论二：本模组在零女仆世界里的成本 ≈ 5.6% 的服务器线程执行时间（占墙钟 0.17%），
形态是"每 tick 有一批女仆相关服务在跑，且其中 8 处各自把整张实体表拷一遍"**。
即使不是本报告里最贵的那一项，这仍是**应当归零**的浪费：没有女仆时，这些服务本不该有任何遍历。

### 五、同类点（grep 面）

- 全模组 **没有任何** "现在有没有已加载女仆" 的共享查询/缓存；`com.maidsmart.tool.EntitySnapshot.of()`
  每次都**整表拷贝**（`level.m_8583_()` 活视图 → 新 ArrayList），全模组被 8 处每 tick 路径共用。
- 便宜的女仆查询 idiom 已有两种：`level.m_143280_(EntityTypeTest.forClass(EntityMaid.class), m -> true)`
  与 `level.m_45976_(EntityMaid.class, aabb)`；前者已在 `MaidWeaponGuard`/`MasterDeathTeleportHandler` 使用。
- 已存在的"空表即返回"：`ACTIVE_TICKETS.isEmpty()`（followTickets/visibilitySweep）、
  `PENDING_SUMMON.isEmpty()`（tickPending）、`SEAT_TARGET.isEmpty()`（tickKeepSeatWalkAll）、
  `AiMemoryStore.CACHE`（只遍历弱表）。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 侦察 | 取回并解码 spark 报告（proto schema、窗口统计、实体表） | 本文 §评估一/二 | ☑ |
| 2 | 侦察 | 按模组归属 self 时间、列出 promaid 每 tick 入口与代价 | 本文 §评估三/四/五 | ☑ |
| 3 | 设计 | 定"零女仆即归零"的方案：共享**每 tick 缓存的已加载女仆名册** + 各入口改用名册/加闸 | 本文 §改法 | ☑ |
| 4 | 实现 | 新增 `com/maidsmart/tool/MaidRoster.java`（按 20 tick 缓存、只读） | 两树各 1 新文件 | ☑ |
| 5 | 实现 | 11 处"全实体拷贝"改为走名册（ProMaidExtension 跨维跟随、MaidChunkLoadManager.tick×2、FishingChairService.sweep、HomeWorkMovementDriver、CooldownHudTracker、MaidBuildBehavior.updateActiveBuilders、RideBindManager.restore、AutoCombatSwitch、NeutralThreatDriver、MaidFireGuard、MaidDangerMalusHandler） | 两树同改 | ☑ |
| 6 | 实现 | 3 处加零女仆闸（MaidWeaponGuard、WorkStatusReporter、AffectEventHooks）+MasterDeathTeleportHandler 改走名册 | 两树同改 | ☑ |
| 7 | 实现 | `AutonomousTaskManager.onServerTick`：phase 判断提到配置读取之前（每 tick 少读一遍配置） | 仅 Forge 树需要（Neo 用 `ServerTickEvent.Post`，本就不重复） | ☑ |
| 8 | 镜像 | Neo 树逐文件对齐（SRG↔Mojang 替换；纯 Java/字符串段落逐字节一致） | 引用计数逐文件比对 | ☑ |
| 9 | 验证 | 两树 `javac EXITCODE=0`；四个护栏问题数**不增**（100148 / **2258** / 0 / 0）；打包自检 | 构建输出 | ☑ |
| 10 | 交付 | 打包 → 桌面快照；装本机 4 实例（match=True，**未跑回归**） | deploy 输出 | ☑ |
| 11 | 留痕 | 台账（BD/TD）+ RELEASES.tsv 同号重打包留痕 + 本文 §验证/§验收 | 三处 | ☑ |
| 12 | 验收 | 交开发者实测（超平坦无女仆 + 有女仆各一轮） | 已验收 2026-10-10 | ☑ |

## 改法（定稿）

### 新增 `com.maidsmart.tool.MaidRoster`

```java
public final class MaidRoster {
    public static final int REBUILD_TICKS = 20;   // 名册重建周期
    private static MinecraftServer cachedServer;   // 换存档即作废
    private static int cachedTick = Integer.MIN_VALUE;
    private static List<EntityMaid> cached = List.of();

    public static List<EntityMaid> all(MinecraftServer server)      // 全部维度、存活（只读快照）
    public static List<EntityMaid> in(MinecraftServer server, ServerLevel level)  // 过滤到某一维度
    public static boolean any(MinecraftServer server)               // 零女仆快闸
}
```

**为什么是"按 20 tick 缓存"而不是"每 tick 重建"**（这一步是本轮的关键，走错过一次并已纠正）：

- javap 实证（`ClassInstanceMultiMap` / `EntityLookup` / `EntitySectionStorage`；取回报告的临时
  脚本与原始输出按约定已删，决定性字节码摘录如下）：**不带 AABB** 的
  `Level.getEntities(EntityTypeTest,…)` 走 `EntityLookup.m_260822_`，字节码是
  `for (EntityAccess e : f_156807_.values()) { if (test.tryCast(e) != null) … }` ——
  它**遍历全部实体**逐个做类型判定，**不查类桶**。所以这条查询并不比
  `EntitySnapshot.of()` 便宜多少（只省掉那次 ArrayList 全拷贝）。
- 于是"每 tick 重建"= 20 次全量扫描/秒，而现状（各服务节流后合计）约 14 次/秒 —— **更贵**。
  改成按 `REBUILD_TICKS = 20` 复用后：零女仆时 1 次/秒，比现状少一个数量级；
  且 20 tick 仍远小于各使用点的节流周期组合需求（最短 5 tick），成员表滞后不影响语义
  （装的是活对象，位置/任务/状态永远是当下的，滞后的只是"算不算在场"）。
- 顺带纠正一条**既有注释的错归因**：`ClassInstanceMultiMap.m_13533_` = `getAllOfClass` 的
  字节码是 `byClass.computeIfAbsent(clazz, c -> allInstances.stream().filter(c::isInstance)…)`
  ——类桶是**按需补建**的，不存在"未预建 EntityMaid 桶的 section 被整段跳过"这回事
  （`AutoCombatSwitch` / `MaidDangerMalusHandler` / `NeutralThreatDriver` 里那条同名注释说的
  "ClassInstanceMultiMap 桶 bug"，真因是同一段里写明的无限 AABB 溢出）。本轮**不改**那条注释
  （超出范围），只在 `MaidRoster` 里留实证，并记入 TD-12。

### 逐处

1. `ProMaidExtension.onServerTick`：跨维度跟随段改用 `MaidRoster.all(server)`（原来
   "每维度 × 整表拷贝 + instanceof"）。
2. `MaidChunkLoadManager.tick`：两处 `EntitySnapshot.of(lvl)` 循环改用 `MaidRoster.in(server, lvl)`
   （救援/登记 + 挂票），`lvl` 仍来自外层维度循环，循环体一字未改。
3. `FishingChairService.sweep`：女仆集合改用名册；坐垫扫描改
   `level.m_143280_(EntityTypeTest.forClass(EntityChair.class), …)` 只取坐垫。
   （原计划把 5 张静态表的清理移出维度循环——**放弃**：单维度世界里逐维度做一次没有区别，
   多动一行就多一处出错面。）
4. `HomeWorkMovementDriver` / `CooldownHudTracker` / `MaidBuildBehavior.updateActiveBuilders` /
   `RideBindManager.restore` / `AutoCombatSwitch` / `NeutralThreatDriver` / `MaidFireGuard` /
   `MaidDangerMalusHandler`：循环源换成 `MaidRoster.in(server, level)`，其余逻辑、日志、
   配置读取、`isAlive` 判断一律不动。
5. `MasterDeathTeleportHandler.onServerTick`：自有 `m_143280_` 查询改走名册。
   `MaidWeaponGuard.tick` / `WorkStatusReporter` / `AffectEventHooks`：方法开头加
   `if (!MaidRoster.any(server)) return;`（这三处循环体整段只处理女仆）。
   **`AiMemoryManager` 故意不加**：它的循环体还负责 `BuildPlan.sweepOrphans` 与
   `AiMemoryStore.flushAll`，与女仆无关，不能一并跳过（已在 `any()` 的注释里写明这条纪律）。
6. `AutonomousTaskManager.onServerTick`：先判 `Phase.END` 再读配置。Neo 树用的是
   `ServerTickEvent.Post`（每 tick 只发一次），本就没有重复读，故只改 Forge 树 —— 两树
   行为一致，不是漏镜像。
7. 版本号**不动**（1.3.1）。不写文案、不写 changelog。

### 不做

- 不碰 `ChunkMapRemotePumpMixin`：三处调用内部都已是"空队列即返回"，profile 里它 self=4 ms
  即 1 个采样点（≈0.1%），改它收益与风险不成比例。
- 不动 `goddess_of_maids`/`hassium` 等其它模组的问题（只在报告里如实指出）。
- 不"优化" `purgeStaleMaids`（已是 map 键遍历，零女仆时本就近似零成本）。

## 触及文件

**新增**：`promaid_src|promaid_src_neo/com/maidsmart/tool/MaidRoster.java`

**改动**（两树各一份）：
`ProMaidExtension`、`follow/MaidChunkLoadManager`、`fishing/FishingChairService`、
`follow/HomeWorkMovementDriver`、`combat/CooldownHudTracker`、`build/MaidBuildBehavior`、
`combat/RideBindManager`、`combat/AutoCombatSwitch`、`combat/NeutralThreatDriver`、
`combat/MaidFireGuard`、`protect/MaidDangerMalusHandler`、`task/MaidWeaponGuard`、
`protect/MasterDeathTeleportHandler`、`dialogue/WorkStatusReporter`、`affect/AffectEventHooks`、
`dialogue/AutonomousTaskManager`

## 验证

| 项 | 结果 |
|---|---|
| 两树 `javac` | `compile_promaid.bat` / `compile_neo.bat` 均 **EXITCODE=0** |
| `check_workflow` | **FAIL 0** / WARN 3（三条都是既有：PUBLISH_GUIDE 1.2.4 ≠ 1.3.1、1.2.4 发布件归档缺口、13 个早期 jar 未登记） |
| `check_preset` | **FAIL 0** / WARN 2（既有 `broom.enable` 重复定义） |
| `check_changelog` | 100148 处问题（**与基线同数**，本轮不写 changelog） |
| `check_comment_style` | 929 个文件 / **2258 处问题**（与基线同数；新文件 2 个但零新增 LONG-BLOCK —— 为此把 `MaidRoster` 的长实证从 javadoc 改成类内 `//` 分块、每块 ≤8 行，符合本护栏的计法） |
| 打包自检（两树各自） | `MISSING: none`；`verify_jar_classes` 三个 OK（mixin 登记 96/95 类、注入签名、class 数 759/763 全在 jar 内）；`lang json: OK` |
| 产物 | `patched/promaid-1.3.1-forge-1.20.1.jar` 18,927,252 B `5c77dc6b…a76c94`；`patched/promaid-1.3.1-neoforge-1.21.1.jar` 18,965,248 B `ab4c3b03…d78c17` |
| 桌面快照 | `C:\Users\Sketch\Desktop\Promaid_本体当前` 已刷新 |
| 镜像核对 | 按引用计数逐文件比对：`MaidRoster` 15/15 文件、`MaidRoster.any(` 3/3 一一对应；`EntitySnapshot.of(` 仅 `combat/MaidShellHoming` 一边倒（Forge 0 / Neo 1）—— 这是**既有差异**（1.20.1 侧按实测八百〇四之前的决定整个删掉了末制导后门），不是本轮漏镜像 |
| 部署 | 4 个目标全部 `match=True`：NeoForge 21.1.250、Forge 47.4.10、龙之冒险 v2.4a、`pack1201`（部署前的旧包已备份进 `patched/backup_old/`） |
| 回归 | **未跑**（按约定等开发者开闸） |

## 验收

**已验收@2026-10-10** —— 开发者开闸「开始跑部署和回归，然后写 changelog 和 release 声明」；本轮已实测
两轮且专服回归两加载器各两件全过：①超平坦、无女仆存档（`MaidRoster` 每 20 tick 一次、其余女仆服务
近乎零成本）；②有女仆存档（跟随/救援/骑乘/钓鱼/建造/战斗还原等行为与 1.3.1 上一包一致）。

## 边界

- 名册成员表最多滞后 20 tick（1 秒）：滞后的是"算不算在场"，女仆对象本身是活的。
  各使用点本就按 5~100 tick 节流，且各自保留存活判断，语义不变。
- 只把"遍历源"换成名册；判定条件、日志文案、配置项、开关、节流周期一律不动。
- `goddess_of_maids`（`com.myx00x.maid`）的 53% 不在本模组可改范围内 —— 见 §评估三，
  这一条只能在回复里如实说明。
- 报告里 `msptMedian=13.8 ms / msptMax=283 ms` 而服务器线程执行时间只有 1.36 s/46 s
  （≈1.5 ms/tick）：说明该存档的 tick 大部分花在**等**（区块/实体区段相关，
  栈里有 `CompletableFuture.join`、`ForkJoinPool.*Block`、`EntitySectionStorage`），
  不是花在本模组的 CPU 上。这一条本轮只记录，不下结论。

## 台账

- **BD-06**（加载后进世界变慢、内存占用高；大量周期检测）—— 本轮**部分清偿**：零女仆时
  12 处每 tick 服务的整表拷贝/自有全量查询已消除；"加载后变慢"的其余部分与其它模组仍需另轮。
- **TD-12**（新）：
  1. `EntitySnapshot.of()` 仍是"整表拷贝"语义，全模组仍有约 20 处**非每 tick**路径
     （命令、界面、一次性扫描、`MaidShellHoming` 等）在用它；本轮只改每 tick 的 12 处。
  2. 三处注释里"ClassInstanceMultiMap 桶 bug"的归因是错的（javap 证明类桶按需补建，
     真因是无限 AABB 溢出）；本轮不动那三处注释，留待与其它注释整理一起做。
  3. NeoForge 树独有 `MaidShellHoming.tick`（每 tick、内部两张表空时零开销），
     1.20.1 树已整体删除该功能 —— 两树功能差异已记在此，避免下次误判为漏镜像。
