# R1017 1.21.1 近战空袭兼容「长矛核心」（1.3.3 同号重打）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修复 / 兼容补齐（1.21.1 专属；不改空袭框架、不新增玩法系统，属于「主手长矛本该被识别」
  的兼容缺口；1.20.1 等开发者验收批准后再镜像）。
- **开工前读过**：`rounds/R1016-air-raid-mace.md`、`MaidFlightCombatBehavior.java`（两树）、
  `MaidFlightKit.java`、`MaidFlightCombatTask.java`、`MaidToolAutoEquip.java`、
  `MaidSmartConfig.java`、`PromaidConfigScreen.java`、`GuideChaptersFlight.java`、
  `AiMemoryManager.java`、`ProMaidExtension.java`、`AirRaidTool.java`、`MaidChunkLoadManager.java`；
  以及 `spearcore-1.21.1-neo-3.1.0.jar` 的 `SpearItem` / `BaseSpearItem` / `SpearStats` /
  `LivingEntitySpearMixin` / `SpearCondition` 字节码（javap 反编译核对）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 长矛识别 | `SpearCoreCompat.isSpear` 认注册名 `spearcore:*_spear`、标签 `spearcore:spear`、类名兜底；未安装时恒 false | ☑ |
| 1 | 蓄力起手 | 阶段二（最高点）主手长矛时 `startUsingItem(MAIN_HAND)`，播放长矛自己的 `use` 音效 | ☑ |
| 2 | 冲锋/取消普攻 | 保持滑翔 + 朝敌俯冲；整段不走 `smashHit` / 范围强制命中 / 普通近战；长矛 `onUseTick` 自己结算伤害 | ☑ |
| 3 | 命中与脱离 | 目标 `lastHurtByMob` 上升沿判定命中；命中后保持 10 tick（0.5 秒）→ 收矛 → 原空袭链路点火起飞 | ☑ |
| 4 | 装备与清理 | `isMeleeWeapon` 认长矛；`forget` / `pauseRound` / `clearAll` / 停行为 / 传送清场都收矛并清静态表 | ☑ |
| 5 | 配置与手册 | 新增 `airRaid.spearRecoverTicks`（10）与 `airRaid.spearMaxTicks`（100）+ 面板行 + 中英译文；手册补长矛条目 | ☑ |
| 6 | 编译与护栏 | Neo 编译 `EXITCODE=0`；14 护栏 `FAIL 0 / WARN 0`；面板覆盖与开关清单同步 | ☑ |
| 7 | 专服回归 | 测试服挂载 `spearcore-3.1.0`：`test_server` 与 `test_maid_load` 均 PASS | ☑ |
| 8 | 部署 Neo | 只部署 1.21.1 实例；Forge 两个实例与 pack1201 一律不动 | ☑ |
| 9 | 开发者实机验收 | 见下方「验收」 | ☐ |
| 10 | 批准后镜像 Forge | 仅在开发者明确批准后执行；本轮不做 | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

- 开发者原话（本轮）：
  「在PVP玩法中，长矛配鞘翅也是一种很热门的玩法。但1.21.1和1.20.1没有长矛。而我最近发现了一个模组，
  叫做长矛核心可以让玩家体验到高版本的长矛。那我们这边能否进行兼容呢？……近战空袭状态下，主手武器
  为长矛时，女仆在飞到最高处后面朝敌人立即进入长矛的冲锋攻击状态（会播放一个音效），随后俯冲，
  接近敌人之后原本的通用的近战武器的攻击取消……将头转向敌人攻击。击中后默认0.5秒后取消冲锋状态。
  然后再次点火起飞。……你先做1.21.1版本。等我确认批准了，你再镜像到1.20.1。」
- 目标玩法：烟花起飞保持高度 → 最高点举矛蓄力 → 朝敌俯冲/平飞（用鞘翅速度）→ 长矛自己扎中 →
  命中后立刻拉起、保持 0.5 秒再收矛 → 再次点火；一击脱离，不悬停缠斗。

## 评估

1. `spearcore` 的 `SpearItem.onUseTick(Level, LivingEntity, ItemStack, int)` 对**任意 LivingEntity** 生效，
   不只对玩家；它在服务端每 tick 自己算蓄力阶段、按视线/相对速度扫实体、结算伤害/击退/音效，
   并在 `getDamageEndTick()` 后自己 `stopUsingItem()`。所以女仆侧只需要
   `maid.startUsingItem(MAIN_HAND)`，**不复制伤害公式**。
2. 玩家右键走的是 `SpearItem.use()`（只接受 Player）。女仆绕过 `use()` 后，使用音效要由本模组补播；
   直接按注册名取 `spearcore:item.spear.use`（木矛取 `item.spear_wood.use`），不需要反射。
3. 命中判定用原版 `LivingEntity.getLastHurtByMob()` + `getLastHurtByMobTimestamp()`：
   长矛的伤害源 `SpearDamageTypes.spear(level, attacker)` 把女仆设为 causing entity，扎中后
   `lastHurtByMob` 指向她；普通近战已被 `suppressVanillaMelee` 压住，所以这个上升沿只可能是长矛。
4. 蓄力期间**不调** `tickDiveBoost`：那套加速里的羽扇会 `startUsingItem` 顶掉长矛蓄力，法术/激流
   也会抢使用状态；长矛冲锋只靠鞘翅俯冲把高度换成速度，正好符合需求。
5. 普通近战空袭的 `smashRange` / `smashHit` / 范围强制命中在长矛分支整段让位，避免双重伤害；
   收翅猛击那套仍对重锤与普通近战武器原样工作。
6. `spearcore` 是可选模组：本兼容只用原版 API + 注册名/标签，未安装时所有判据恒 false，
   空袭行为与旧版一字不差。

## 改动文件

- `promaid_src_neo/com/maidsmart/combat/SpearCoreCompat.java`（新增）：长矛识别、起手/收矛、
  使用音效、主手蓄力判定。
- `promaid_src_neo/com/maidsmart/combat/MaidFlightCombatBehavior.java`：
  `SPEAR_ROUND` / `SPEAR_CHARGE_TICKS` / `SPEAR_HURT_STAMP` / `SPEAR_RECOVER_UNTIL`；
  阶段二长矛分支、`tickSpearAir` / `finishSpearCharge`、`isEngaged` 豁免、`endFlightSafely` 收矛、
  `forget` / `pauseRound` / `clearAll` 清理、两个配置访问器。
- `promaid_src_neo/com/maidsmart/task/MaidToolAutoEquip.java`：`isMeleeWeapon` 认长矛。
- `promaid_src_neo/com/maidsmart/memory/AiMemoryManager.java`、
  `ProMaidExtension.java`、`tool/AirRaidTool.java`、`follow/MaidChunkLoadManager.java`：
  女仆离开/启动清理/停止空袭/强制传送前收矛。
- `promaid_src_neo/com/maidsmart/combat/MaidFlightCombatTask.java`：激活条件与行为说明改为
  「近战武器（重锤/长矛/任意近战）」。
- `promaid_src_neo/com/maidsmart/config/MaidSmartConfig.java`：`AIR_RAID_SPEAR_RECOVER_TICKS` /
  `AIR_RAID_SPEAR_MAX_TICKS`。
- `promaid_src_neo/com/maidsmart/config/PromaidConfigScreen.java`：② 区新增两行，
  分区标题改为「收翅俯冲 / 长矛冲锋」。
- `promaid_src_neo/assets/maid_smart/lang/{zh_cn,en_us}.json`：两个配置键译文。
- `promaid_src_neo/com/maidsmart/guide/GuideChaptersFlight.java`：近战空袭链路补长矛条目；
  `promaid_src_neo/assets/promaid/i18n/en_us.json`：对应英文。
- `promaid_src_neo/assets/promaid/panel_coverage.txt`、`ARCHITECTURE-SWITCHES.md`：重新生成。

## 验证

- Neo 编译：`EXITCODE=0`。
- 14 护栏：`FAIL 0 / WARN 0`；Neo 面板覆盖 601 个字段；`check_guide_i18n` 缺译 0；
  `check_reflection` Neo 维持基线 204（本轮无新增探针式反射）。
- Neo jar：18,095,371 字节，SHA256 `3724ab9a687f0377…b6e040e3`。
- 专服（测试服 mods 里已放 `spearcore-1.21.1-neo-3.1.0.jar`）：
  `test_server.py neoforge1211` PASS；`test_maid_load.py neoforge1211` PASS（女仆大脑类正常加载）。
- 已部署 1.21.1 实例（`match=True`）；未重打/未部署 Forge 侧 jar；Forge 源码树本轮零改动。

## 边界

- 仅 1.21.1 NeoForge；1.20.1 的镜像**等开发者验收批准后**再做。
- 仅在「近战空袭 + 主手长矛」时接管；远程空袭、地面近战、重锤/普通近战武器均不受影响。
- 只认 `spearcore` 命名空间/标签/类名；别的模组的“长矛”不会误触发。
- 长矛伤害仍由 `spearcore` 按它自己的速度/相对速度条件决定；本模组只负责起手、朝向、俯冲、
  收矛时机与起飞衔接，不额外加伤害。
- 未安装 `spearcore` 时，`SpearCoreCompat.isSpear` 恒 false，行为与 R1016 完全一致。
- 蓄力期间不施法、不用俯冲段烟花/羽扇加速（避免顶掉使用状态）；起飞/爬升段的烟花照旧。
- 版本号仍为 1.3.3（同号重打），不升号。

## 验收

- 装 `spearcore` 3.1.0（1.21.1 Neo）后，给女仆鞘翅 + 任意长矛 + 烟花火箭，切「近战空袭」。
- 观察：起飞爬升到最高点后出现长矛使用音效并平举蓄力；接近敌人时**没有**普通近战挥砍/收翅猛击；
  长矛本体扎中后伤害按长矛模组结算；命中后约 0.5 秒收矛，随后点火拉起，进入下一轮。
- 打空/贴地/超时会收矛并切回滑翔/点火，不会带着长矛蓄力摔死。
- 配置面板「战斗与自保 → 空袭数值 → ② 收翅俯冲 / 长矛冲锋」可见两个新行。
- 把长矛换成重锤或普通剑：行为回到 R1016 的重锤猛击/暴击链路。
- 卸载 `spearcore`：空袭行为与 R1016 一致，无报错。
