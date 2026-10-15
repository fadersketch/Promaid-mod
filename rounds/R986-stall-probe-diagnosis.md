# R986 路 滞留探针：给「战斗模式却原地不动」装两发日志（诊断轮）

## 排班

1. 接 R985 之后的现象：**战斗模式下周围没有敌人，她却不跟随主人；切 DIY 立刻恢复正常**。
2. 排查结论（只读分析）：链路走位独占（`MaidWorkTags.isChainDrive` = 挖矿/伐木标记 **或** 接战判定）
   是唯一能整段掐掉「跟随 start」与「MoveToTargetSink」的闸门，而**接战判定的间接来源多**
   （ATTACK_TARGET / `getTarget` / 自保标记 / 最近 5 秒挨打或打人）——日志里看不出是哪条腿在咬，
   也看不出"咬住了但没人驱动"。DIY 之所以能治好：`MaidConfigPreset` 把非保留项统统关掉
   （含 `combat.*`、`misc.followTighten`），独立闸门与喂目标的两条来源一起没了。
3. 本轮**只加诊断**（按确认过的三步：先加探针 → 复现取日志 → 再改闸门）。玩家可见行为零改动。
4. 顺手修掉排查时被它反复干扰的一条**假日志**：`FriendlyFireGuard` 每 2 秒把"目标为空"也算成
   一次"仇恨清除"（`isFriendly(maid, null)` 恒 true），于是每个没目标的女仆每 10 秒记一行
   `friendly-fire hate-clear`——排查期它把真信号淹了。
5. 两树镜像 → 编译 → 14 护栏 FAIL 0 → 双 jar → 部署 4 实例 → 专服回归两件 → 文档台账 → 清临时。

## 需求 / 来源

- 「在 13:11 的那一局里，女仆是攻击模式，周围没有敌人的情况下，不像原版 TLM 那样跟随主人。
  开了 DIY 模式之后恢复正常，说明是本模组导致的改动。请调查本模组在女仆处于战斗模式下做了
  什么改动，为什么会出现这种情况。」
- 上一轮批准的三步走：「先加诊断、复现确认，再改闸门」。

## 改法

### 新类 `CombatStallProbe`（两树镜像，`com/maidsmart/combat/`）

- `note(maid, site)`：两个取消点各调一发。只在两种可疑情况下落一行（其余静默）——
  ① **独占标记残留**：`CHAIN_DRIVE_TAG` 还在，可她已不在挖矿/伐木（该由任务切换清掉却没清）；
  ② **算在接战却没有驱动**：`CombatWorkRange.inCombat` 为真，而战术 / 自保 / 我们喂的目标 /
  空袭 / 扫帚 / 自由飞**一个都没在开她**（= 闸门咬住却没人驱动，正是"原地不动"的机器视角）。
- `sweep(maid)`：另一路，由 `NeutralThreatDriver` 的 0.5 秒巡检代跑。战斗任务、离主人 > 16 格、
  闸门没咬、也没目标可打、且另有驱动器的状态全部排除（扫帚/空袭/自由飞/牵引绳/指挥棒绑定/
  插火把/守家/坐下/乘客/睡觉/脑冻结）+ DIY 模式排除 → 落一行 `no-gate`。
- 两者共用一份**只读快照**（`Snap`）与节流（同女仆同站点 5 秒一条，表满即清空）。
- 落盘走 `LogUtils` 直接进 `latest.log`（前缀 `[promaid/stall]`）——**不**走 `promaid.log`
  的 `misc.logEnabled` 开关，免得排查期被配置挡住。
- 日志字段：`why / site / maid / task / tag / mine / wood / combat / reason / tactics / preserve /
  fed / broom / flight / free / attack / canMove / home / seated / dist / mode`。
  其中 `reason` 由新加的 `CombatWorkRange.combatProbeDesc` 给出（在既有 `combatReason` 口径上
  补"是谁"：`ATTACK_TARGET:Zombie`、`最近挨打(1.2秒前):Wolf`…）。
- 新增 `NeutralThreatDriver.drivingFedTarget`：登记的目标**此刻还是她脑里那个活着的目标**吗
  （把"我们正在喂"与"登记表里留着旧目标"分开）。闸门修复到期时会直接用它，本轮先服务于探针。

### 接入点（各一处）

- `MaidFollowOwnerTickMixin.maidsmart$yieldWhileChainDriving`：`ci.cancel()` 之前 `note(maid, "follow-cut")`。
- `MaidMoveSuppressMixin` 链路走位独占分支：`eraseMemory(WALK_TARGET)` 之前 `note(maid, "walk-cut")`
  （拾取放行那条 `return` 不探——那条本来就不取消）。
- `NeutralThreatDriver.onServerTick` 女仆循环：`sweep(maid)`，放在 `drive(...)` 之前（它自己不抛）。

### `FriendlyFireGuard` 假日志修复

- 三处清除判据补"先看非空"：`getTarget() != null && isFriendly(...)`、`getLastHurtByMob() != null && …`。
  空目标在伤害闸那边**必须**算友方（`isFriendly(maid, null) == true` 是给"上游伤害归因"用的口径），
  但在"清仇恨"这里等于"什么都没清也报 cleared"。

## 验证

- 两树编译 `EXITCODE=0`（Forge / Neo 各一遍，只有既有 deprecation 警告）。
- 14 护栏：`check_comment_style` **PASS**（940 文件 0 问题）、其余 **FAIL 0**
  （`check_tree_mirror` 为只读报告）。
- 构建：forge `promaid-1.3.2-forge-1.20.1.jar` 17,897,847 字节 / neo
  `promaid-1.3.2-neoforge-1.21.1.jar` 17,945,365 字节；`verify_jar_classes` 全绿（无多余 class）。
- 部署 4 实例 **match=True**：Neo 1.21.1 本体、Forge 47.4.10、龙之冒险整合包、`mc_server_test/pack1201`。
- 专服回归：`test_server.py 1201` PASS（Done 3.213s）、`test_server.py neoforge1211` PASS（Done 1.362s）、
  `test_maid_load.py` PASS（召唤 → 加载 TLM brain 任务类，无崩）。

## 验收

- 游戏内行为**一字未变**：所有开关、任务、走位、战斗判定全按原样跑；探针只读、只记日志
  （唯一写入是它自己的节流戳表，表满即清）。
- 正常游玩（干活 / 正常接战 / 正常跟随）时 `latest.log` 里**不应出现** `[promaid/stall]`。
- 复现条件（战斗模式 + 周围无敌人 + 她不跟）满足时，日志里会出现 `[promaid/stall]` 行，
  且 `why`/`site`/`reason`/`tactics`/`preserve`/`fed` 六个字段足以判出是哪条腿在咬；
  单条不会刷屏（同女仆同站点 5 秒一行）。
- 复现时不会再被 `friendly-fire hate-clear` 刷屏——那条只在**真的**清掉了攻击目标 /
  实体目标 / 复仇目标时才出现。
- 复现后关掉 DIY 再试一次：`mode=diy` 时两类探针都静默（DIY 属预期差异，不落盘）。

## 边界

- 本轮**不动闸门**：`isChainDrive` 的接战腿、挖矿/伐木标记的任务校验与清扫都留到拿到复现日志之后
  （避免在"哪条腿在咬"尚未确认时改出第二种滞留）。
- `sweep` 只看**战斗任务**且离主人 > 16 格：跟随任务 / 干活任务的女仆不在射程内（那些有各自的
  工作圈语义，不该按"没跟随"记一笔）。
- 探针日志**不受** `misc.logEnabled` 管（它直写 `latest.log`）；排查期这是刻意的，
  修复落地时连同探针一起收掉。
- `FriendlyFireGuard` 的修复只改"什么时候记日志"，清除动作本身的判断口径没变（空目标本来就清不动）。
- 只报实体类名（`Zombie` / `Wolf`）不报显示名：诊断够用，且不额外碰 Component / 注册表链。

## 台账

- `rounds/RELEASES.tsv` 两条 1.3.2 行 **第一百二十二次** 同号重打：`57a74255…8b2dc8`（forge）/
  `deacfec12…9d9233`（neo）；上一轮 `9eb9e7ac…0c4a1b` / `35f5159e…0b79b2` 作废（workspace 行）。
- `DEV_GUIDE.md` 发布区间 → 八百五十九 ~ 九百八十六。
- `tools/arch/arch_modules.py`：`promaid.combat.targeting` 加 `CombatStallProbe.java` 源 + 三条依赖边；
  `ARCHITECTURE.md` 规模行 73 → 76 箭头；`normify-promaid/` 重跑冻结（143 产物、0 差异、144 冻结文件）。
- `机制详解.md`：追加「链路走位独占的取消点 + 滞留探针」一条与 `FriendlyFireGuard` 假日志的注记。
- 发布文案**不动**：本轮无玩家可见改动（两条日志一增一减），不往 1.3.2 的发布副本里加条目。

## 后续（等复现日志）

- 有 `why=标记残留` → `CHAIN_DRIVE_TAG` 泄漏：给标记加任务类型校验 + 任务切换清扫。
- 有 `why=接战无驱动` → 接战腿只应在"本模组的驱动器真在开她"时独占（`tactics`/自保/喂目标/飞行）；
  其余情况把走位还给 TLM。
- 有 `why=无闸滞留` → 与闸门无关，按那一行的 `canMove/home/seated/task/dist` 继续查下游（导航 / 任务层）。
- 一行都没有 → 两处取消点没被执行，说明滞留不在这条链上，另开一枪（下一步会看
  `MaidFollowOwnerTask` 是否被别的闸挡在 start 之前）。
