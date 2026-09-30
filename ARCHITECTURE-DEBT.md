# 技术债与屎山清单 / Tech Debt & Code Rot

> 全部数字取自 `05103ae`（NeoForge 1.21.1 树，`promaid_src_neo`），由脚本实测，不是估计。
> 复现：`python tools/arch/debt_report.py`

---

## 0. 先看结论：三条必须动刀的

| # | 问题 | 规模 | 为什么必须动 |
|---|---|---|---|
| **1** | **两棵手工镜像源码树** | 390 个同路径文件，其中 **224 个行数已经不一致** | 每个功能写两遍；两树已开始分叉，再拖下去无法再手工同步 |
| **2** | **`PromaidConfigScreen.java` 单类 6424 行** | 含 543 行 `render()`、468 行 `clickCookGrid()` | 加任何一个设置项都要改这个巨型类；是全部 UI 改动的阻塞点 |
| **3** | **补丁坟场**：`SelfPreservationBehavior` 4696 行 / `MaidMountCompat` 4746 行 | 两文件共 **327 处** `实测NNN` 历史标记 | 逻辑按「第几轮实测修的」堆叠，读不懂、不敢改、改一处崩三处 |

---

## 1. 超大文件排行（>1200 行，NeoForge 树）

| 行数 | 文件 | 性质 |
|---|---|---|
| **6424** | `config/PromaidConfigScreen.java` | 手写立即模式 UI 巨型类 |
| **4746** | `combat/MaidMountCompat.java` | 兼容模块吞掉了整个功能 |
| **4696** | `combat/SelfPreservationBehavior.java` | 补丁坟场（164 处实测标记） |
| **3493** | `combat/MaidFlightCombatBehavior.java` | 空袭主循环（222 处实测标记） |
| **3172** | `config/MaidSmartConfig.java` | 524 个设置项 + 1850 行 static 初始化块 |
| 2951 | `task/MaidWoodBehavior.java` | 伐木（自述是挖矿的完整克隆） |
| 2845 | `task/MaidMineBehavior.java` | 挖矿 |
| 2571 | `combat/MaidBroomDrive.java` | 扫帚飞行 |
| 2270 | `combat/RideBindManager.java` | 骑乘绑定语义 |
| 2139 | `build/BlueprintBookScreen.java` | 6 视图蓝图界面 |
| 2090 | `follow/MaidChunkLoadManager.java` | 区块票 + 跨维跟随 + 队列 + 可见性 |
| 1951 | `build/MaidBuildBehavior.java` | 建造执行 |
| 1946 | `combat/GunnerTetherManager.java` | 武装拴绳（与 RideBindManager 职责重叠） |
| 1859 | `combat/MaidAidOwnerBehavior.java` | 主人辅助 |
| 1748 | `combat/MaidFlightKit.java` | 空袭套件 |

> 这 15 个文件合计约 **45,000 行**，占 NeoForge 树 394 个文件的绝大部分体量。

---

## 2. 补丁坟场（按历史标记密度排序）

计数方式：正则 `实测[零一二三四五六七八九十百〇0-9]+` + `v1.\d+.\d+` 的出现次数。

| 合计 | 实测标记 | 版本标记 | 文件 |
|---|---|---|---|
| 814 | 397 | 417 | `config/MaidSmartConfig.java` |
| 572 | 247 | 325 | `config/PromaidConfigScreen.java` |
| 545 | 164 | 381 | `combat/SelfPreservationBehavior.java` |
| 412 | 98 | 314 | `task/MaidWoodBehavior.java` |
| 381 | 82 | 299 | `task/MaidMineBehavior.java` |
| 339 | 222 | 117 | `combat/MaidFlightCombatBehavior.java` |
| 275 | 29 | 246 | `build/MaidBuildBehavior.java` |
| 242 | 95 | 147 | `combat/MaidAidOwnerBehavior.java` |
| 221 | 147 | 74 | `follow/MaidChunkLoadManager.java` |
| 202 | 16 | 186 | `build/BlueprintBookScreen.java` |
| 168 | 159 | 9 | `combat/MaidMountCompat.java` |
| 130 | 116 | 14 | `combat/MaidBroomDrive.java` |

**这意味着什么**：注释里保留的是「上一版哪里错了、这一版怎么改的」，而不是「这段代码现在做什么」。
对维护者来说，要读懂一段逻辑必须先读它的历史 —— 这正是 `MaidMountCompat` 从 500 行长到 4746 行的方式。

**建议**：把「为什么改」写进 changelog，把「现在做什么」留在代码里。这是可以纯做减法的一次清理。

---

## 3. 死代码 / 一次性残留（有明确自述）

| 文件 | 证据 | 判定 |
|---|---|---|
| `tool/MaidProbe.java` | 自述「临时探针…查清问题后整类删掉即可」 | **可直接删** |
| `combat/FlightTargetProbe.java` | 自述「一次性排查工具，验证完可删」 | **可直接删** |
| `soul/SoulBindingService.java` | 自述灵魂核心功能已移除，只剩路由兼容 | 待确认是否还有旧存档用户 |
| `guide/GuideScreen.java` | `VIEW_SETTINGS`/`VIEW_VOICE` 标注 `@Deprecated`「已不可达，保留仅为回滚方便」 | 可删 |
| `build/BuiltinHouses.java`（1385 行） | `BlueprintCatalog.BUILT_IN_NAMES` 空块 + 注释「内置预设已全部移除」 | **休眠代码**，只可硬编码 id 触达 |
| `schedule/ScheduleNetworking.java` | 拆包后留下空方法体与「已整条删除」注释 | 可清 |
| `fishing/PlayerWaterLog.java` | 纯诊断日志助手，住在主源码里 | 可移入 tools |
| `box/CompressionBoxCheck.java`（702 行） | FakePlayer 自测住在主源码里 | 可移到测试源集 |
| `task/MaidStrollCheck.java`（337 行） | 仅为一条速度投诉的调试命令服务 | 可移到 tools |

**"名字从未被别处引用"的类共 30 个**，其中 26 个是 Mixin（Mixin 由 `mixins.promaid.json` 按名注册，
不靠 Java 引用，**属于正常，不是死代码**）。真正需要复核的非 Mixin 类只有 4 个：

- `combat/FarmlandGuard.java`（24 行）
- `combat/PetImmunityGuard.java`（201 行，职责与 `FriendlyFireGuard` 重叠）
- `CreativeTabHandler.java`（48 行，经事件/注册表生效）
- `flight/MaidFreeFlightKeysClient.java` / `goety/MaidGoetyKeysClient.java`（客户端按键注册）

---

## 4. 重复逻辑（可合并项）

### 4.1 两树镜像（最大的重复）

- 同路径文件 **390 个**，其中 **224 个行数已经不一致**（Δ 最大 174 行）。
- 不一致不代表功能不同 —— 大多只是注释/换行差异；但 **Δ 越大的文件越危险**，
  因为你无法再从"对照另一棵树"推断当前树是否正确。

| Δ | NeoForge | Forge | 文件 |
|---|---|---|---|
| 174 | 1946 | 1772 | `combat/GunnerTetherManager.java` |
| 141 | 1402 | 1261 | `flight/MaidFreeFlightController.java` |
| 129 | 2270 | 2399 | `combat/RideBindManager.java` |
| 91 | 433 | 524 | `combat/MaidSelfPropelledWings.java` |
| 84 | 770 | 854 | `build/BlueprintBookNetworking.java` |

**根因**：没有共享源集的机制。两树用不同的映射名（SRG vs Mojmap），但这**不能**用"注解处理器/模板"简单解决。
**建议方向**（按成本从低到高）：
1. 先把两树中**逐字节相同**的文件识别出来，用脚本校验它们真的相同（回归护栏）；
2. 再把「纯逻辑、无映射依赖」的部分（如 `BlueprintStepMath`、`BlueprintLegacyIds`、`AiMemorySkipList`）
   抽成共享源目录，两树各自 include；
3. 最后才考虑统一映射层（成本最高，收益也最大）。

### 4.2 同树内的重复

| 重复 | 证据 |
|---|---|
| `MaidWoodBehavior` ≈ `MaidMineBehavior` | 自述「完整克隆挖矿」；两文件合计 **5796 行** |
| `GunnerTetherManager` ≈ `RideBindManager` | 都在做绑定链/座位偏移/换座/清理；合计 **4216 行** |
| `MaidFlightRecall` ≈ `MaidBroomRecall` | 同构的"半径 N 格找不到主人就回传" |
| `MaidSeatNetworking` ≈ `GunnerTetherNetworking` | 两份几乎同构的 S2C 单包骨架 |
| `BlueprintMaterials` 内 5+ 个近同构变体 | 缺口/玩家缺口/合并持有/计数/计数玩家 |
| `SchedulePacketsPlan` ≈ `SchedulePacketsMaid` | 共享同一段 21 行 import 头，编解码逐字复制 |
| `BlueprintBookBuildPackets`（919）+ `EntityPackets`（735） | ~25 个包类塞在两个 god-file，各自重复 `type()` 样板 |
| `PromaidConfigScreen` 内 5 组列表+条目对 | BuildBlack/Alt/Cook/Water/Food/Minable 各写一遍增删改重建 |
| 各兼容层 | `GunCompat`/`MaidSpellCompat`/`SlashBladeCompat`/`TwilightFanKit`/`CuriosElytraCompat` 各自重写「反射失败→兜底」 |

### 4.3 配置迁移残渣

`ProMaidMod.runConfigMigration()` 约 320 行，是一条 `if (值 == 旧默认) 值 = 新默认` 的链，
外加一个 12 项「默认值修复」批次（专门用来撤销一次改错的默认值），
以及同一个键**连续两次迁移**（扫帚爬升 10 → 15 → 12，各带一个一次性布尔标记）。

这 22 个 `*_MIGRATED` 标记**被当成用户可见设置留在了配置里**。
**建议**：迁移代码本身可以留（旧存档要能用），但标记不该暴露给用户 —— 应移出 `ModConfigSpec`。

---

## 5. 结构隐患（不是屎山，但会持续咬人）

| 隐患 | 说明 |
|---|---|
| **`ProMaidExtension` 单点装配** | 779 行、几十条注册；任何新功能都要碰它 → 改动冲突高发 |
| **大量静态可变状态** | `SelfPreservationBehavior` 的 `LAST_ATTACKERS`/`SUFFOCATE_DAMAGE`/`COMBAT_PLACED`、`MaidBuildBehavior` 约 15 个静态 Map、`MaidFreeFlightController` 的 STATE/YAW/RETREAT …… 全靠手工 `forget`/`clearAll`，**漏一处就泄漏**（注释里多次记录过因此崩服） |
| **`BombPose` 被 11 个不相关子系统引用** | 隐藏的全局耦合点：改动它要审 11 个调用方 |
| **命令树无中央注册表** | 5 个类各自注册 `/maid_smart` 同一个字面量 |
| **`GuideContent.CFG_LINK_RULES` 靠中文标题字符串匹配** | 手册跳转与配置面板的**行标题**强耦合，改一个标题就断链，且无编译期保护 |
| **`BlueprintLib` 半成品拆分** | 73 个一行转发 + 14 个类带同一段用不上的 import；拆分只做了一半 |
| **`MaidChunkLoadManager`** | 票据/跨维跟随/召唤队列/可见性四件事仍在一个类里 |

---

## 6. 建议的清理顺序（按投入产出比）

| 阶段 | 动作 | 风险 | 收益 |
|---|---|---|---|
| **① 零风险** | 删自述「可删」的 `MaidProbe`、`FlightTargetProbe`；`GuideScreen` 死视图；`ScheduleNetworking` 空方法 | 极低（有编译校验） | 清理噪音 |
| **② 低风险** | 把注释里的「历史/为什么」搬进 changelog，代码只留「现在做什么」 | 低（纯注释） | 大幅降低阅读成本 |
| **③ 中风险** | `*_MIGRATED` 移出用户配置；诊断类（`PlayerWaterLog`、`CompressionBoxCheck`、`MaidStrollCheck`）移出主源码 | 中 | 配置表干净、主源码只留产品代码 |
| **④ 中风险** | 抽 `MaidWorkBehavior` 基类，让挖矿/伐木共用扫描-接近-连锁-回收 | 中（有编译+回环测试） | 砍掉约 2500 行重复 |
| **⑤ 高风险** | 拆 `PromaidConfigScreen`（按 Section 拆成独立面板类） | 高（需实机验收 UI） | 解除 UI 改动阻塞 |
| **⑥ 最高** | 两树共享源目录（先纯逻辑，后映射层） | 最高 | 一次改动只写一遍 |

> 本项目有一批**编号回归 harness**（`tools/test/test_*.py`，27 个）和 `verify_jar_classes.py`，
> 这是做 ④⑤⑥ 的底气 —— **动刀之前先确认对应编号的 harness 还能跑**。

---

## 7. 复现本报告

```
python tools/arch/debt_report.py        # 重新计算本文件里的全部数字
python tools/arch/gen_arch.py           # 结构数据（L1/L2 门禁）
python tools/arch/render_arch.py        # 交互式架构图
python tools/arch/finalize_arch.py      # 冻结哈希
```
