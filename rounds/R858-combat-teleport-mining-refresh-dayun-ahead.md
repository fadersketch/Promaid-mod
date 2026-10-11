# 实测八百五十八 · 战斗中被回传主人 / 挖矿伐木 5 秒重评估 / 大运前瞻加码（两树）+ 版本升 1.3.2

> 流程见 `WORKFLOW.md`；本轮是 **A 修 bug**（粉丝反馈三条）+ **版本纪律**（开发者声明**升 1.3.2**）。
> 反馈第 4 条（雪地雪球→跳劈误伤主人）**按开发者指示本轮不做**（现有 `FriendlyFireGuard` 三层防护的核查结论见 §评估·④）。

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：**A 修 bug**。三条都是"既有行为做错了 / 没做到位"。
- **开工前读过**：`ARCHITECTURE-SWITCHES.md`（自保 / 挖矿 / 伐木 / 大运几节）、`BUG_DEBT.md` / `TECH_DEBT.md` /
  `ARCHITECTURE-DEBT.md`、`rounds/RELEASES.tsv`、`WORKFLOW.md` §5.5（升号清单）、上一轮 `rounds/R857-*.md`。
  结论：三条**都不需要新配置项**——改的是既有判据本身（省掉 i18n / 配置面板 / DIY 预设三处联动）。
- **工作队列**（逐步，做完就勾；每步写"完成判据"）：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 定位三条的代码入口（回传 = `SelfPreservationBehavior.teleportHomeOnExit` + `MaidTeleportPreserveMixin`；日志 = `MaidResyncCommand.scheduleAttributeResend`；挖矿/伐木 = `MaidMineBehavior` / `MaidWoodBehavior` 的目标锁定；大运 = `DaYunBreaker.sweep`） | 每条指着具体方法/行说清"为什么现在会这样" | ☑ |
| 1 | ① 战斗回传门禁：新增"是否正在交战"判定（有活的非友方目标）；`teleportHomeOnExit` 在交战中直接返回不传；`scheduleAttributeResend` 的诊断日志按女仆限频（30 秒/条） | 交战中不再被自保归位传送拽回主人；属性补包日志不再逐条刷屏 | ☐ |
| 2 | ② 挖矿/伐木每 5 秒重评估：锁定目标期间每 100 tick 走一次**廉价**的缓存重挑（`findOre`/`findWood` 内部有缓存就只校验、不重建、不停顿），换了更优目标才切；挖掘进行中（`destroyProgress>0`）不打扰 | 锁定期间每 5 秒重新评估一次；切目标不造成"停一下重扫"（无全量重建、无停顿） | ☐ |
| 3 | ③ 大运前瞻加码：前瞻距离 `速度×系数` 上限 3 → 8 格、系数 3 → 4，加**最小前瞻 2.5 格**；速度被撞没时改沿**视线方向**清（贴墙也能顶出来）；前瞻段提到中心段**之前**（不被中心段吃光预算） | 鞘翅高速平飞/俯冲时前方墙提前消失，不再"撞上才清" | ☐ |
| 4 | 两树镜像 + 编译 | `gen_compile*.py` + `compile_*.bat` → **EXITCODE=0**，0 条 error | ☑ |
| 5 | 版本升 1.3.2（§5.5 清单） | 三份 toml / 两个 jar 名 / `README` 顶部两行一致；`check_workflow.py` 版本项无新增 FAIL | ☑ |
| 6 | 打 jar（版本纪律 §5）+ 护栏 + jar 内自检 | `build_promaid*.py` 自检 OK；`patched/` 出 1.3.2 两包；护栏无新增 FAIL | ☑ |
| 7 | 写轮次记录 + 交开发者验收 | 本文件填完；把"怎么试 / 看哪几个日志关键词"交给开发者 | ☐ |
| 8 | 部署 + 回归 | **只在开发者说「部署」之后**（`WORKFLOW.md` §4 步骤 8~10 硬闸） | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法：① 让女仆在战斗（有活怪）中被打到自保线再看是否还会被"传送回身边"，
  日志搜 `属性补包`（应明显变少）；② 挖矿/伐木时看是否每约 5 秒会"换一块更近的矿"而不是死磕一块；
  ③ 戴大运鞘翅高速平飞/俯冲撞墙，看是否还会顿一下。日志搜 `self preserve` / `mine behavior` / `大运`。

## 需求 / 来源

开发者转述的粉丝反馈（本轮 **1 / 2 / 3** 条；第 4 条开发者指示不做）：

1. **战斗中被回传主人、日志刷屏**（1.3.0 / Forge 1.20.1）："女仆战斗过程中距离稍微拉远就会回传主人，频率很高，
   日志中出现大量【属性补包】女仆 传送后补属性表"。
2. **挖矿/伐木检测间隔长 + 战斗跟随距离尴尬**："检测 cd 有点长，锁定一个目标只要不离开太远就会一直尝试过去采掘
   除非等到重新检测，可以适当缩减一点检测周围矿物的时间间隔，比如五秒一轮……战斗跟随距离太近导致玩家没法拉扯，
   跟随范围太远又导致追怪跟不上。"
3. **大运撞墙还是顿**："大运的破坏判定位感觉可以根据移速往前移动几格，不断撞墙看上去有点呆；**最大的问题是玩家
   鞘翅飞行速度太快仍可能撞墙，需要有更强的措施。**"（后半句是开发者补充的最高优先项）
4. （**不做**）雪地：女仆朝主人扔雪球后无缝衔接暴击跳劈，把主人打死多次。

## 评估

**跑道 A（修 bug）。三条各自独立。**

### ① 战斗中被回传主人 + 属性补包刷屏（P1）
- **日志来源**：`MaidResyncCommand.scheduleAttributeResend`——**每成功传送一次女仆就打一条**"属性补包 … 传送后补属性表"
  （`MaidResyncCommand.java` 内 `PENDING_ATTR` + 日志）。所以"日志刷屏"= 传送次数多，症状在传送不在日志。
- **能"稍微拉远就回传"的传送有两处，两处都会打这条日志**：
  - **A（主嫌，最匹配"稍微 + 频率高"）**：`SelfPreservationBehavior.teleportHomeOnExit`（退出自保分支调用，
    传 `ignoreCooldown=true`）。判据是 **"不在主人 5 格内就传送"**，且 **绕过 5 秒 / 30 秒双冷却**，
    **不看有没有活目标**（代码注释原文："只要不在主人 5 格内就传送……无论主人身边是否有怪"）。
    战斗里血量在 30%↔70% 之间一上一下，每次退出自保都会立刻把她传送回主人身边 → 高频、每次都补属性表。
  - **B（次嫌）**：TLM 原版 `MaidFollowOwnerTask` 离主人过远（≈ `followRange`）时 `teleportToOwner`；
    `MaidTeleportPreserveMixin` 只在自保 / 建造 / 搭路 / 坐骑 / 滑翔 / 扫帚等状态拦它，**普通战斗不拦**。
- **根因三问**：为什么现在会这样？——因为退出归位传送是**刻意**设计（实测三百七十八："恢复即支援，交火中也撤出战场
  回到主人身边"），当时没考虑"战斗里血量频繁上下会反复触发"；为什么没早发现？——它只在自己回血资源充裕
  （血能回上来）的女仆身上才频繁，弱女仆一旦进自保就出不来了，所以老玩家/弱装配没暴露；不做会怎样？——
  女仆一整场战斗被反复拽回主人身边（且打断她正在打的跳劈，见反馈第 2 条后半），玩家没法拉扯。
- **同类点**：`SelfPreservationBehavior.teleportHome`（保命兜底那条）走的是同一套落点/冷却判定，但它**不绕冷却**
  （`ignoreCooldown=false`）且只在危急（岩浆 / 血<15% / 弹尽粮绝）触发，不在本轮改动范围。

### ② 挖矿/伐木检测间隔长（P2 · 观感/效率）
- **现状**：`findOre`（伐木 `findWood`）**只在 `targetPos == null` 时才被调用**（挖完一块 / 目标失效 / 缓存轮空）。
  一旦锁定目标，她一路挖过去 / 走过去，**中途不再重新评估其它矿**——这正是"锁定一个目标……除非等到重新检测"。
  **注意**：以前是"每 5 秒强制全量重扫"，被实测一百四十二 **刻意删掉**（"到期重建只会造成'每 5 秒停一下重扫/
  换目标'"，等效收放魂符）——所以**不能走回"每 5 秒全量重扫"**。
- **根因三问**：为什么现在会这样？——为了消除"每 5 秒停一下"把周期重评估整个拿掉了，连带把"换更近的矿"也拿掉了；
  为什么没早发现？——单矿/近矿场景看不出；不做会怎样？——她守着远处一块矿慢慢挪，近处更好的矿视而不见。
- **同类点**：伐木 `MaidWoodBehavior` 与挖矿**同构**（`TARGET_SINCE` / `pickFromCache` / `WOOD_TARGET_TIMEOUT`），
  一并改。`mine.targetTimeout`（默认 300 tick）是"够不着的目标超时放弃"，改它会误伤"够得着但在走路"的矿
  （走路时 `destroyProgress=0`），**本轮不动它**。

### ③ 大运前瞻不够（P2 · 观感，开发者点名要"更强的措施"）
- **现状**：`DaYunBreaker.sweepAhead` 已经做了"按移速提前清"（实测八百一十/八百一十一），但：
  - 前瞻距离被 `min(速度 × 3.0, 3.0)` **卡在 3 格**——鞘翅带烟花/俯冲时速度能明显超 3 格/tick，
    "这一 tick 会走到哪"算得比实际近 → 墙还是撞上才清；
  - 速度被墙撞没的那一 tick，`speed ≈ 0` → 前瞻段**整段跳过**（reach=0），只能等中心段救，观感就是"贴墙愣一下"。
- **根因三问**：为什么现在会这样？——八百一十 那版按"鞘翅平飞 0.6~1.2 格/tick"定的系数与上限，没考虑带加速
  （烟花/俯冲）的高速；为什么没早发现？——女仆侧空袭是上下翻飞、玩家侧平飞赶路才明显，样本少；不做会怎样？——
  高速撞墙的顿挫依旧。
- **同类点**：`DaYunBreaker.canPhaseThrough`（穿墙锁敌）用同一套硬度判据，本轮不动它的判据。

### ④（不做，仅记录结论）雪地雪球 → 暴击跳劈误伤主人
- `FriendlyFireGuard`（三层：事件取消 / 注入点过滤 / 每 2 秒清仇恨）的类注释里写的根因**就是**这条反馈；
  跳劈结算是 `target.m_6469_(maid.m_269291_().m_269333_(maid), dmg)`（伤害源的造成者 = 女仆）→ 第①层必取消，
  且 `MaidCombatTacticsBehavior` 第 680 行先 `isFriendly` 直接不挥刀。**当前代码上这条打不出伤害。**
  若粉丝仍复现，最可能是"那只女仆不是他的"（`isFriendly` 对 `owned==false` 直接返回 false，护栏按设计不介入）
  或跑的是旧版本 → **需要粉丝的构建版本号 + 是否有 `friendly-fire hate-clear` 日志**，本轮不动。

## 改法

1. **战斗回传门禁（两树 `FriendlyFireGuard` + `SelfPreservationBehavior` + `MaidResyncCommand`）**
   - `FriendlyFireGuard` 新增 `public static boolean isEngaged(EntityMaid maid)`：她此刻有没有**活的、非友方的**攻击目标
     （查 `getTarget()` 与 `ATTACK_TARGET` 记忆两处；友方用既有 `isFriendly` 过滤）。复用同文件、不新建类。
   - `SelfPreservationBehavior.teleportHomeOnExit`：在主人存活 / 非看家 / 非停放 等既有护栏之后，
     加一句 `if (FriendlyFireGuard.isEngaged(maid)) return;`——**交战中不把她传送回主人身边**（交还给战术继续打，
     战斗结束/目标消失后自然走回；不影响"完全脱离战斗后归位"的原语义）。
   - `MaidResyncCommand.scheduleAttributeResend`：日志按女仆限频（`UUID → 上次打日志的 gameTime`，
     间隔 600 tick = 30 秒）。**只限日志，补属性表的行为一字不改**（issue #27 的诊断仍可查，只是不再逐条刷屏）。
2. **挖矿 / 伐木每 5 秒重评估（两树 `MaidMineBehavior` / `MaidWoodBehavior`）**
   - 新增实例字段 `repickCooldown` + 常量 `MINE_REPICK_INTERVAL` / `WOOD_REPICK_INTERVAL` = 100（5 秒）。
   - 在 `m_6725_` 里"目标已解析完（`targetPos != null`）"之后、"弃置检查"之前插入：
     每 100 tick 且 `destroyProgress <= 0`（不在挖的当中）时，调一次 `findOre` / `findWood`；
     返回非空且与当前目标不同 → 切目标（更新 `targetPos`、`TARGET_SINCE`、按需换镐）。
     `findOre` 内部**有缓存就只走廉价校验（`pickFromCache`）、不重建、不停顿**，所以重评估本身零停顿；
     返回 null（分帧扫描进行中 / 框内无矿）时**保留当前目标**，不误清。
3. **大运前瞻加码（两树 `DaYunBreaker`）**
   - `AHEAD_FACTOR` 3.0 → **4.0**，`AHEAD_MAX` 3.0 → **8.0**，新增 `AHEAD_MIN = 2.5`（最小前瞻）。
   - 前瞻距离改为 `clamp(max(速度 × 系数, AHEAD_MIN), AHEAD_MIN, AHEAD_MAX)`——**速度被撞没时也至少前瞻 2.5 格**。
   - 方向：`speed` 有值用速度方向；`speed ≈ 0`（已贴墙）改用 `entity.getLookAngle()` 的**视线方向**把自己顶出来。
   - 顺序：把**前瞻段提到中心段之前**（旧版中心段先跑、可能吃光 `maxBlocksPerTick` 预算，高速时恰恰最需要前瞻）。

## 触及文件

- `promaid_src` / `promaid_src_neo`（4 个文件 × 两树）：
  `combat/FriendlyFireGuard.java`、`combat/SelfPreservationBehavior.java`、`command/MaidResyncCommand.java`、
  `task/MaidMineBehavior.java`、`task/MaidWoodBehavior.java`、`accessory/DaYunBreaker.java`（共 6 个文件 × 两树）。
- **无新配置项、无新 i18n key、无新 mixin**（三处都只改判定与排版内部逻辑，玩家可见文案未增删）。
- 版本升 1.3.2 的机械面（§5.5）：三份 `mods.toml` / 两个 `build_promaid*.py` 的 `JAR_OUT` /
  `deploy_all.py`（文件头 + `JOBS`）/ `sync_desktop_snapshot.py`（`JARS` + 说明）/ `test_server.py` + `test_maid_load.py` /
  `README.md` 顶部两行 / `rounds/RELEASES.tsv` 新增 1.3.2 `workspace` 两行。

## 验证

- **两树编译**：`gen_compile.py` + `compile_promaid.bat` → **EXITCODE=0**（463 源文件）；
  `gen_compile_neo.py` + `compile_neo.bat` → **EXITCODE=0**（466 源文件）、0 条 error。
- **护栏**：`check_comment_style.py` → 2262 处（**与本轮开工前基线 2262 持平，零新增**——中途两组注释块超长
  `MEMBER > 8` 已当场压回；`RESULT: FAIL` 是存量 2262 条的常态）；
  `check_preset.py` → FAIL 0 / WARN 2（存量：两树 `broom.enable` 路径重复定义）；
  `check_workflow.py` → **FAIL 0 / WARN 3**（全是存量：`PUBLISH_GUIDE` 记平台版本 1.2.4、1.2.4 归档缺口、
  13 个早期 jar 未登记）。**1.3.2 的"未登记"告警已随 `RELEASES.tsv` 两行落库消失。**
- **打包 / 反证**：`build_promaid.py` / `build_promaid_neo.py` 自检 OK（`MISSING: none`、`verify_jar_classes` OK）；
  `patched/promaid-1.3.2-forge-1.20.1.jar`（17,562,091 B，sha256 `c3651a7a…2f4bec`、761 class）、
  `patched/promaid-1.3.2-neoforge-1.21.1.jar`（17,600,872 B，`6b97b5e8…ec1ce2`、765 class）。
  解压两个 jar 的 `*.class` 拼接后搜新符号——`isEngaged` / `repickCooldown` / `ATTR_LOG_SINCE` /
  `MINE_REPICK_INTERVAL` / `WOOD_REPICK_INTERVAL` **全部 FOUND**，证明改动确实进了产物。
- **镜像**：两树 6 个文件的增删行数逐对相等（`60/19`、`24/0`、`8/0`、`16/3`、`26/0`、`22/0`）。
- 未做的验证要写明：**未部署、未跑专服回归**（等开发者开闸「部署」）。

## 版本（开发者声明：升 1.3.2）

- 开发者本轮明确"准备 1.3.2 的更新"→ 按 `WORKFLOW.md` §5.5 走号：
  三份 toml（`version="1.3.2"`）、两个 `build_promaid*.py` 的 `JAR_OUT`、`deploy_all.py`（文件头 4 行 + `JOBS`
  共 12 处）、`sync_desktop_snapshot.py`（`JARS` + 说明那句版本，共 3 处）、两个 test 脚本（各 4 处）、
  `README.md` 顶部两行、`rounds/RELEASES.tsv` 新增 1.3.2 `workspace` 两行（hash 见 §验证）。
- **`PUBLISH_GUIDE.md` / `store/` 不动**（记的是"平台上现有什么"，只在真发布时改）。
- **`sync_desktop_snapshot.py` 里指向 `RELEASE_NOTES_v1.3.1.md` 的几处本轮未动**（该文件是 1.3.1 的历史文案，
  1.3.2 的 release 声明按 §6.1 要等验收后才写；届时一并把 tooling 里的 notes 引用改成 1.3.2）。本轮不跑部署/快照，
  所以不影响。
- 版本更新内容 / release 说明**验收前一个字都不写**（§6.1）；本轮只落轮次记录与台账。

## 验收（谁说了算：开发者 —— `WORKFLOW.md` §4.1）

- 状态：`未验收`。等开发者按"谁验收 / 怎么验"实测三条。
- 未验收之前：**不写版本更新内容、不写 release 说明、不发平台、不部署**（可以装进自己机器试）。

## 边界（没动什么）

- 不动 `teleportHome`（自保保命兜底传送）的判据与冷却——它不绕冷却、只在危急触发。
- 不动 `mine.targetTimeout` / `wood.targetTimeout`（够不着的目标超时放弃）——改会误伤"够得着但要走"的矿。
- 不动"每 5 秒全量重扫"（已刻意删除，会重新引入停顿）——本轮只加廉价缓存重挑。
- 不动大运的硬度判据 / 容器安全 / 掉落 / 粒子 / 范围 / 单 tick 上限。
- 不动第 4 条（雪地误伤）——按开发者指示本轮不做。
- 本轮**未部署、未跑专服回归**（等开发者开闸「部署」）。

## 台账

- 本轮不新增债、不清债（三条都是新反馈，未在台账里记过）。
- `rounds/RELEASES.tsv` 已加 **1.3.2 `workspace`** 两行（forge `c3651a7a…2f4bec` / neo `6b97b5e8…ec1ce2`）；
  1.3.1 两行 `published`（`c4495111…93e35e` / `02ef34d0…df8993`）**原样保留**（仍是玩家手上那一份）。
- 已披露未决项（承接 R857）：1.3.1 同号覆盖与 §5.2 冻结线冲突的流程口径仍待开发者定；
  另：`sync_desktop_snapshot.py` 里的 `RELEASE_NOTES_v1.3.1.md` 引用要在 1.3.2 文案写好后再改（见 §版本）。
