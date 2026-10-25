# R1021 1.21.1 长矛四修：全程蓄力 / 开局拉开 / 空袭再点火 / 去挥臂（1.3.3 同号重打）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修复 / 兼容补齐（1.21.1 专属；1.20.1 等开发者验收批准后再镜像）。
- **开工前读过**：`rounds/R1020-neo-spear-chain-pivot.md`、`SpearAttackBehavior.java`、
  `MaidFlightCombatBehavior.java`、`SpearCoreCompat.java`、`MaidFlightRecall.java`、
  `MealTaskGatingMixin.java`；以及 TLM `MaidHealSelfTask` / `MaidHomeMealTask` /
  `MaidWorkMealTask`、`DefaultMaidHealSelfMeal`、spearcore `SpearItem` / `LivingEntity` 的
  javap 字节码与实机日志。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 实机日志定位 | 确认空袭每 1.5 秒重复「水平突袭点火」= BOOST 20 + RECOVER 10；确认进食/自愈餐占用 `useItem`；确认 spearcore 在 `onUseTick` 里调 `swing(hand,true)` | ☑ |
| 1 | 全程蓄力 | 新增 `SpearCoreCompat.claimCharge`：战斗链路先放下非长矛使用状态再举矛；攻击/空袭链路全部改走它 | ☑ |
| 2 | 餐食让位 | 工作餐/自愈餐/居家餐在长矛战斗期间 `checkExtraStartConditions` 返回 false，避免进食顶掉蓄力与重复回血 | ☑ |
| 3 | 开局拉开 | 攻击模式 `start()` 时距离 <10 格先进 `OPENING`：举矛退到 10 格外，再回 `APPROACH` 冲锋 | ☑ |
| 4 | 空袭再点火 | 新增 `SPEAR_RELAUNCH` 硬闸：突袭收尾后只有 `tryLaunch` 真正成功（`markLaunched`）或落地/收轮才允许开下一轮；不再依赖会被爬升相位清掉的 `WAIT_LAUNCH` | ☑ |
| 5 | 牵引绳豁免 | 本轮空中攻击进行中（`SPEAR_PASS` / 起飞 / 等待再点火）跳过 100 格空袭牵引绳，避免突袭途中被传回主人身边 | ☑ |
| 6 | 去挥臂 | 新增 `MaidSpearSwingMixin`：蓄力期间同时拦 `LivingEntity.swing(hand)` 与 `swing(hand,true)`，只吞动画/客户端事件 | ☑ |
| 7 | 编译与护栏 | Neo 编译 `EXITCODE=0`；14 护栏 `FAIL 0 / WARN 0`；架构生成物同步 | ☑ |
| 8 | 专服回归 | 测试服挂载 `spearcore-3.1.0`：`test_server` 与 `test_maid_load` 均 PASS，测试后移除测试 jar | ☑ |
| 9 | 部署与验收 | 只部署 1.21.1 实例；Forge 两个实例与 pack1201 一律不动；待开发者实机验收 | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

- 开发者原话（本轮）：
  「我要2攻击模式下矛也全程蓄力，同时如果初始就与敌人足距离<10，则先拉开距离。空袭状态珍个链路故障，
  女仆难以起飞，广试了五六次反成功一次且不会依冲。女仆使用冲锋时就不要再播放挥万万臂动作了」

## 评估

1. **攻击模式全程蓄力**：TLM 的 `DefaultMaidHealSelfMeal.onMaidEat` 会调用
   `maid.startUsingItem(hand)`（javap 实证），自愈餐/工作餐/居家餐都会占用 `useItem`；长矛
   `startCharge` 在 `isUsingItem` 为真且不是长矛时直接返回 false，于是“吃饭期间不起冲”。
   本轮新增 `claimCharge`：主手是矛时先 `stopUsingItem()` 放下非长矛使用，再立刻起手；攻击模式
   与空袭链路所有续矛点都改走它。另加餐食闸，避免任务每 tick 重新开始进食。
2. **开局贴脸先拉开**：`SpearAttackBehavior.start()` 时若距离 <10 格，进入新增 `OPENING` 相位：
   保持举矛，用 `LandRandomPos.getPosAway` 找背离点退到 10 格外（最少 10 tick、最多 60 tick），
   随后回 `APPROACH`/`CHARGING`。后撤方向与既有“边打边退”共用 `retreatPos`。
3. **空袭链路重复点火**：实机日志 09:04:38–09:04:46 连续 6 次「水平突袭点火」间隔均 1.5 秒，
   正好等于 `spearBoostInterval=20` + `spearRecoverTicks=10`。突袭收尾后 `tryLaunch` 没点着，
   但 `tickClimbToAltitude` 每 tick 无条件 `WAIT_LAUNCH.remove(id)`（且 `pauseRound` 也会清），
   于是 `tickSpearPass` 误以为“可以开新一轮”，重新进入 DIVE 并再次点火；原链路从未真正起飞。
   修法：新增 `SPEAR_RELAUNCH` 硬闸，收尾时置位；只有 `tryLaunch` 的任意一条起飞腿成功
   （烟花/羽扇/激流/自推鞘翅，统一走 `markLaunched`）、落地、入水、切武器、换任务或收轮才清除。
   `tickSpearPass` 在该标记存在时只负责“等点火”，绝不创建新突袭；行为短暂停/重启也不清它，
   避免目标抖动把刚收尾的突袭重新点着。100 格牵引绳在 `isEngaged`（`SPEAR_PASS` / `WAIT_LAUNCH` /
   `LAUNCH_LEFT` 等）为真时让位，不会再从突袭途中把她拽回。
4. **蓄力挥臂**：spearcore `SpearItem.onUseTick` 自己调用
   `LivingEntity.swing(InteractionHand, boolean)`（javap 实证），TLM 平A调用一参重载；两个重载
   在 `EntityMaid` 蓄力期间都被 `MaidSpearSwingMixin` 取消。伤害与长矛音效不受影响。

## 改动文件

- `promaid_src_neo/com/maidsmart/combat/SpearCoreCompat.java`：新增 `claimCharge`、
  `isChargingForAnimation`、`shouldReserveUse`。
- `promaid_src_neo/com/maidsmart/combat/SpearAttackBehavior.java`：新增 `OPENING` 相位与
  `retreatPos`；`start()` 贴脸先拉开；续矛统一 `claimCharge`。
- `promaid_src_neo/com/maidsmart/combat/MaidFlightCombatBehavior.java`：新增 `SPEAR_RELAUNCH`
  硬闸与 `markLaunched`；`tryLaunch` 四条成功腿统一清闸；`tickSpearPass` 收尾只等真正点火；
  `tickClimbToAltitude` 对长矛保留 `WAIT_LAUNCH`；续矛改 `claimCharge`；行为短暂停不清闸，
  落地/入水/切武器/换任务才清。
- `promaid_src_neo/com/maidsmart/combat/MaidFlightRecall.java`：空中攻击进行中跳过 100 格牵引绳；
  一轮收尾后自动恢复。
- `promaid_src_neo/com/maidsmart/mixin/MaidSpearSwingMixin.java`（新增）：两个 `swing` 重载的
  蓄力动画闸。
- `promaid_src_neo/com/maidsmart/mixin/SpearHealMealGatingMixin.java`（新增）：自愈餐/居家餐让位。
- `promaid_src_neo/com/maidsmart/mixin/MealTaskGatingMixin.java`：工作餐在长矛战斗期间让位。
- `promaid_src_neo/mixins.promaid.json`：登记两个新 mixin。
- `tools/arch/arch_modules.py` 与 `normify-promaid/`：新 mixin 登记、架构产物重新生成。
- 发布文档：`release_body.md`、`release_body_neo.md`、`RELEASE_NOTES_v1.3.3.md`、
  桌面《Promaid_v1.3.3_发布声明.md》与《v1.3.3 临时追加更新》追加本轮稳定性补丁说明。

## 验证

- Neo 编译：`EXITCODE=0`。
- 14 护栏：`FAIL 0 / WARN 0`；`check_mixin_targets` 校验 142 个注入点；
  `check_arch` / `check_guide_i18n` / `check_i18n_keys` 全部 0。
- Neo jar：18,110,763 字节，SHA256
  `7fb314d47dbe3e129335ab7b94b51ea2279ee164cd22a57c0629b6fc6f9d8cc6`。
- 专服（测试服 mods 里临时放 `spearcore-1.21.1-neo-3.1.0.jar`）：
  `test_server.py neoforge1211` PASS；`test_maid_load.py neoforge1211` PASS
  （女仆大脑类、两个新 mixin 与长矛链路正常加载）。测试后已把测试 jar 移出测试服。
- 已部署 1.21.1 实例，源包/目标包 SHA256 一致；桌面快照同步；未重打/未部署 Forge 侧 jar；
  Forge 源码树本轮零改动。

## 边界

- 仅 1.21.1 NeoForge；1.20.1 的镜像**等开发者验收批准后**再做。
- `claimCharge` 只在攻击模式/空袭的长矛链路调用；其它玩法仍走 `startCharge`，不抢吃喝。
- 餐食闸只在“攻击或空袭任务 + 主手长矛 + 目标存活 + 开关打开”时生效，目标丢失后照常进食。
- `SPEAR_RELAUNCH` 只约束“本轮突袭收尾 → 下一次起飞”；落地、换武器、丢目标、切任务、
  收轮都会清掉，不影响后续正常起飞。无烟花/羽扇/激流/自推鞘翅时她会保持滑翔并最终落地，
  不会无限重开突袭。
- 100 格牵引绳只在 `isEngaged` 为真时让位；一轮收尾后恢复，不会让她永久失联。
- 挥臂闸只取消动画，不改伤害、不取消长矛使用音效。
- 版本号仍为 1.3.3（同号重打），不升号。

## 验收

- 攻击模式：给女仆长矛、切攻击模式、把怪放到她 9 格内。观察她**先举矛后退到 10 格左右**，
  再回头冲锋；中途不吃饭、不收矛、不挥臂。打中后仍按 R1020 的“同向 1 秒 → 后撤 → 再冲”。
- 空袭：给女仆鞘翅 + 长矛 + 烟花，切近战空袭。日志里每轮突袭应只有一条「水平突袭点火」，
  随后出现「放烟花起飞」并真正爬升；不应再出现连续 6 条 1.5 秒间隔的原地点火。交战期间
  即使她离主人超过 100 格，也不应出现「空袭牵引绳」把她拽回；目标死亡/切任务后才恢复。
- 蓄力动画：长矛蓄力期间手臂保持举矛姿势，不再反复播放挥臂；换普通剑/重锤后动画恢复。
- 卸载 `spearcore`：所有新增判据恒 false，行为与 R1020 一致，无报错。
