# R1018 1.21.1 攻击模式兼容「长矛核心」（1.3.3 同号重打）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修复 / 兼容补齐（1.21.1 专属；不改战斗任务框架、不新增玩法系统，属于「主手长矛
  不该再平A」的兼容缺口；1.20.1 等开发者验收批准后再镜像）。
- **开工前读过**：`rounds/R1017-neo-spear-air-raid.md`、`MaidMeleeRiptideMixin.java`、
  `MaidCombatTacticsBehavior.java`、`MaidMoveSuppressMixin.java`、
  `MaidToolAutoEquip.java`、`MaidSmartConfig.java`、`PromaidConfigScreen.java`、
  `ProMaidExtension.java`、`AiMemoryManager.java`、`AirRaidTool.java`、`MaidChunkLoadManager.java`；
  以及 TLM 1.5.3 Neo 的 `MaidMeleeAttack.lambda$create$0`、`TaskAttack` 字节码与
  `spearcore-1.21.1-neo-3.1.0.jar` 的 `SpearItem.onUseTick` / `BaseSpearItem` /
  `SpearStats` / `SpearCondition` / `SpearUseGoal` 字节码（javap 反编译核对）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 触发口径 | 攻击任务（`touhou_little_maid:attack`）+ 主手长矛 + 开关开；目标进 10 格起手 | ☑ |
| 1 | 持续蓄力 | 每 tick 保持 `startUsingItem(MAIN_HAND)`；提前被掐断（<20 tick）立即续上 | ☑ |
| 2 | 取消平A | `MaidMeleeRiptideMixin` 同时吞掉 `swing(MAIN_HAND)` 与 `doHurtTarget`，伤害只由长矛结算 | ☑ |
| 3 | 状态循环 | 长矛自己收手 → 反向拉开 10(+2 骑乘) 格、至少 10 tick → 再举矛；目标离开 12 格/死亡收矛 | ☑ |
| 4 | 高版本僵尸口径 | 相对速度伤害、疲劳后失去击落坐骑、再久失去击退、品质越高进入越快，全部由 spearcore `onUseTick` 结算；骑乘追击速度 ×2 | ☑ |
| 5 | 走位让位 | 战术走位 `isActive=false`、举盾 `shouldUseShield=false`、`MoveToTargetSink` 源头取消；直连导航由本行为独占 | ☑ |
| 6 | 配置与文案 | 新增 `combat.spearCharge`（默认开）+ 面板行 + 中英译文；面板覆盖/开关清单重新生成 | ☑ |
| 7 | 清理 | `stop` 收矛清状态；离开世界/启动清理/停止空袭/强制传送都 `forget`；启动 `clearAll` | ☑ |
| 8 | 编译与护栏 | Neo 编译 `EXITCODE=0`；14 护栏 `FAIL 0 / WARN 0` | ☑ |
| 9 | 专服回归 | 测试服挂载 `spearcore-3.1.0`：`test_server` 与 `test_maid_load` 均 PASS，注册日志确认 `SpearCharge=236` | ☑ |
| 10 | 部署 Neo | 只部署 1.21.1 实例；Forge 两个实例与 pack1201 一律不动 | ☑ |
| 11 | 开发者实机验收 | 见下方「验收」 | ☐ |
| 12 | 批准后镜像 Forge | 仅在开发者明确批准后执行；本轮不做 | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

- 开发者原话（本轮）：
  「攻击模式下使用长矛同理，不会去平A，而是将原来每次平A的触发改为持续的冲锋状态，断开了就续。
  依旧是接敌就续，不接敌自动取消。可以套用高版本僵尸使用长矛的逻辑与机制：……当僵尸与目标的距离
  进入10格以内时，它就会自动举起长矛，进入冲锋准备状态。……伤害严格取决于你和它之间的“相对速度”。
  ……疲劳状态：持矛冲锋过久会进入此状态，此时无法再把你从坐骑上击落。……脱离状态：继续冲锋会进入
  此状态，击退效果会消失，但伤害不变。……当相对速度不够或冲锋结束后，僵尸会主动尝试拉开距离，
  然后再掉头发起下一次冲锋，形成循环。骑乘强化：如果僵尸骑在僵尸马等快速载具上，冲锋速度会额外加倍。」
- 目标玩法：攻击模式不再按 TLM 的 20 tick 节奏戳一下，而是 10 格内举矛、朝目标持续冲锋；
  长矛的伤害/疲劳阶段全部走 spearcore；一轮走完后撤，再冲；目标不在了就收矛。

## 评估

1. TLM 攻击模式的近战就是 `MaidMeleeAttack.lambda$create$0` 里那两记
   `swing(MAIN_HAND)` + `doHurtTarget(target)`（javap 实证）。本轮把这两记在
   `MaidMeleeRiptideMixin` 里一起拦截：持长矛时 `swing` 不执行、`doHurtTarget` 直接返回 true。
   TLM 仍会照常写攻击冷却，但不会再有普通挥砍/伤害。
2. `spearcore` 的 `SpearItem.onUseTick(Level, LivingEntity, ItemStack, int)` 对任意 LivingEntity
   生效：它按 `getDamageEndTick()` 自动收手，按视线/相对速度结算伤害，按
   `getDismountEndTick()` / `getKnockbackEndTick()` 依次关闭击落坐骑/击退。女仆侧只保持
   `startUsingItem(MAIN_HAND)`，不复制任何数值。
3. 高版本僵尸的 `SpearUseGoal` 是“进攻击半径 → 举矛 → 蓄力完 → 反向拉开 → 再举矛”的状态机；
   这里用 core 行为实现同一循环。起蓄半径按开发者指定的 10 格（僵尸默认 4.5 格），
   后撤距离 10 格、骑乘 +2，后撤至少 10 tick、最多 60 tick。
4. “断开了就续”：长矛自己收手发生在 `elapsed >= getDamageEndTick()`（木矛约 315 tick），
   所以“上一 tick 的已蓄力 tick 数 < 20”只可能是被外来行为提前掐断；这种直接重新
   `startUsingItem`，不进入后撤。正常走完一轮才后撤。
5. 骑乘强化：`maid.isPassenger()` 时导航速度 ×2，后撤距离 +2 格。击落坐骑/击退的“更容易”
   由 spearcore 的条件与 `mobFactor=0.2` 自己处理。
6. 走位独占：`MaidMoveSuppressMixin` 在 `MoveToTargetSink` 入口取消并清 `WALK_TARGET`；
   `MaidCombatTacticsBehavior.isActive` 返回 false；`shouldUseShield` 返回 false（盾会抢
   同一个使用物品槽位，把长矛蓄力顶掉）。本行为优先级 236，高于战术 230、低于落地水 240。
7. `spearcore` 是可选模组：`SpearCoreCompat.isSpear` 未安装时恒 false，本轮所有新链路
   自动失效，攻击模式与旧版一致。

## 改动文件

- `promaid_src_neo/com/maidsmart/combat/SpearAttackBehavior.java`（新增）：
  攻击模式长矛持续冲锋状态机（APPROACH / CHARGING / RETREAT）、10 格接敌、
  提前断蓄续矛、后撤再冲、骑乘倍速、`isEnabled` / `takesOverMovement` / `suppressesMelee`、
  `forget` / `clearAll`。
- `promaid_src_neo/com/maidsmart/mixin/MaidMeleeRiptideMixin.java`：
  新增 `swing` 的 `@Redirect`；`doHurtTarget` 在长矛接管时直接返回 true。
- `promaid_src_neo/com/maidsmart/mixin/MaidMoveSuppressMixin.java`：
  长矛冲锋期间清 `WALK_TARGET` 并取消 `MoveToTargetSink`。
- `promaid_src_neo/com/maidsmart/combat/MaidCombatTacticsBehavior.java`：
  `isActive` / `shouldUseShield` 在长矛冲锋期间让位。
- `promaid_src_neo/com/maidsmart/mixin/MaidShieldTimingMixin.java`：
  战术总开关关着时也拦住举盾，避免盾顶掉长矛蓄力。
- `promaid_src_neo/com/maidsmart/ProMaidExtension.java`：
  core 行为登记 `Pair.of(236, new SpearAttackBehavior())`；启动清理与 `clearAll` 接入。
- `promaid_src_neo/com/maidsmart/memory/AiMemoryManager.java`、
  `follow/MaidChunkLoadManager.java`、`tool/AirRaidTool.java`：
  离开世界/强制传送/停止空袭时清长矛攻击状态。
- `promaid_src_neo/com/maidsmart/config/MaidSmartConfig.java`：`COMBAT_SPEAR_CHARGE`
  （`combat.spearCharge`，默认 true）。
- `promaid_src_neo/com/maidsmart/config/PromaidConfigScreen.java`：
  激流三叉戟行下方新增「长矛核心·攻击模式冲锋」。
- `promaid_src_neo/assets/maid_smart/lang/{zh_cn,en_us}.json`：新配置键译文。
- `tools/verify/check_preset.py`：Neo DIY 关闭条数基线 111 → 112（Forge 未镜像，不动）。
- `promaid_src_neo/assets/promaid/panel_coverage.txt`、`ARCHITECTURE-SWITCHES.md`：重新生成。

## 验证

- Neo 编译：`EXITCODE=0`。
- 实机首轮触发时崩溃修复：`SpearAttackBehavior` 构造原写 `(0, Integer.MAX_VALUE)`，
  而 `Behavior.tryStart` 用 `nextInt(maxDuration + 1 - minDuration)` 取随机时长，
  `MAX_VALUE + 1` 溢出为负数 → `IllegalArgumentException: Bound must be positive`。已改为
  与其它常驻 core 行为一致的 `(Integer.MAX_VALUE, Integer.MAX_VALUE)`，复测通过。
- 14 护栏：`FAIL 0 / WARN 0`；Neo 面板覆盖 602 个字段；`check_preset` Neo 112 条；
  `check_reflection` Neo 维持 `Class.forName 83 / getMethod 204 / getDeclaredMethod 5 /
  getDeclaredField 8`（本轮无新增反射）。
- Neo jar：18,102,058 字节，SHA256 `2282cd3c5e454081…8be084f3`。
- 专服（测试服 mods 里临时放 `spearcore-1.21.1-neo-3.1.0.jar`，测完已移除）：
  `test_server.py neoforge1211` PASS；`test_maid_load.py neoforge1211` PASS
  （女仆大脑类正常加载，注册日志出现 `SpearCharge=236`，无 mixin apply 失败）。
- 已部署 1.21.1 实例（`match=True`）；未重打/未部署 Forge 侧 jar；Forge 源码树本轮零改动。

## 边界

- 仅 1.21.1 NeoForge；1.20.1 的镜像**等开发者验收批准后**再做。
- 仅在「攻击任务 + 主手长矛」时接管；远程攻击任务、空袭任务、重锤/剑/三叉戟均不受影响。
- 只认 `spearcore` 命名空间/标签/类名；别的模组的“长矛”不会误触发。
- 长矛伤害、疲劳/脱离阶段仍由 `spearcore` 按它自己的条件决定；本模组只负责举矛、朝向、
  追击、断蓄续矛、后撤与退出，不额外加伤害。
- 目标超过 12 格或死亡、主手换掉长矛、任务切走时收矛退出；重新进 10 格会再次起手。
- 蓄力期间不举盾（盾会顶掉使用物品槽位）；战术走位/跳劈让位，普通索敌与攻击任务本身不变。
- 未安装 `spearcore` 时行为与 R1017 完全一致。
- 版本号仍为 1.3.3（同号重打），不升号。

## 验收

- 装 `spearcore` 3.1.0（1.21.1 Neo）后，给女仆任意长矛，切「攻击模式」，旁边放一只怪。
- 观察：目标进 10 格左右出现长矛使用音效并平举；她**不再**按普通近战节奏戳刺，
  而是朝目标持续冲锋；长矛自己的相对速度伤害生效。
- 蓄力被外力打断（例如中途换手/被顶掉）后，只要目标还在 10 格内，她会立刻重新举矛。
- 一轮蓄力走完后她会反向拉开一段距离，再回头举矛冲锋；目标跑远（>12 格）或死亡则收矛。
- 骑僵尸马/快速载具时追击明显更快，后撤距离更远。
- 配置面板「战斗与自保 → 激流三叉戟旋转冲击」下方可见「长矛核心·攻击模式冲锋」；
  关闭后长矛回到普通近战挥砍。
- 卸载 `spearcore`：攻击模式与 R1017 一致，无报错。
