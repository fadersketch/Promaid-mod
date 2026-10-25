# R1019 1.21.1 鞘翅长矛独立贯穿链路（1.3.3 同号重打）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修复 / 兼容补齐（1.21.1 专属；R1017 的长矛空袭只认当前主目标、第一次命中就收矛，
  与长矛“一次冲锋沿视线扎穿多个目标”的既有语义不符，属于兼容实现缺口；本轮把这套长矛战斗循环
  拆成独立链路重做，不改普通空袭、重锤、远程空袭与地面近战的既有语义。1.20.1 等开发者验收批准后再镜像）。
- **开工前读过**：`rounds/R1017-neo-spear-air-raid.md`、`rounds/R1018-neo-spear-attack-mode.md`、
  `MaidFlightCombatBehavior.java`、`MaidFlightKit.java`、`MaidFlightCombatTask.java`、
  `SpearCoreCompat.java`、`MaidSmartConfig.java`、`PromaidConfigScreen.java`、
  `GuideChaptersFlight.java`；以及 `spearcore-1.21.1-neo-3.1.0.jar` 的 `SpearItem` /
  `SpearCollision` / `SpearStats` / `SpearCondition` 字节码，和 Jerotes 1.20.1 jar 的
  `JerotesPlayerEntity` / `JerotesSpearUseGoal` / `ItemToolBaseSpearBase` 字节码。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 长矛贯穿取证 | 确认 `SpearItem.onUseTick` 的 `getHitEntitiesAlong` 返回**整条视线上的全部实体**、逐个结算，且按 `contactCooldownTicks` 对同一目标去重；不因第一个命中而停手 | ☑ |
| 1 | 独立链路 | 新增 `SpearAirChain`：编队取长轴 → 队首外对准 → 平举贯穿 → 穿出队尾拉起 → 转回再突袭；不再朝单个目标垂直俯冲 | ☑ |
| 2 | 多目标统计 | 按每个敌人的 `lastHurtByMob` 时间戳上升沿统计本轮贯穿命中数，主目标死亡不影响队伍里其它目标 | ☑ |
| 3 | 速度与高度 | 对准/冲锋期间按固定间隔点挂载烟花维持鞘翅速度；起冲前把高度压到目标眼高附近，避免从头顶垂直扎下 | ☑ |
| 4 | 安全收尾 | 落地、目标丢失、缺件、换任务、停止空袭、强制传送全部收矛并清编队轴；传送豁免把贯穿链也算进“本轮正在打” | ☑ |
| 5 | 配置与手册 | 新增 `spearPassRange` / `spearGroupRadius` / `spearBoostInterval` 三条；`spearRecoverTicks` / `spearMaxTicks` 语义改为「拉起脱离」「单次最长」；面板五行、中英译文、手册条目同步 | ☑ |
| 6 | 编译与护栏 | Neo 编译 `EXITCODE=0`；14 护栏 `FAIL 0 / WARN 0`；面板覆盖与开关清单同步 | ☑ |
| 7 | 专服回归 | 测试服挂载 `spearcore-3.1.0`：`test_server` 与 `test_maid_load` 均 PASS | ☑ |
| 8 | 部署 Neo | 只部署 1.21.1 实例；Forge 两个实例与 pack1201 一律不动 | ☑ |
| 9 | 开发者实机验收 | 见下方「验收」 | ☐ |
| 10 | 批准后镜像 Forge | 仅在开发者明确批准后执行；本轮不做 | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

- 开发者原话（本轮）：
  「鞘翅长矛的伤害可以正常触发了，但没能发挥它的全部价值，因为矛是可以一次冲锋贯穿多个敌人的，
  现有的空袭更偏向从上向下扎一个人。或许需要重新设计一套单独的链路，而不是在原本的通用基础上添改
  参考一下我下载的jerotes的仓库中假玩家使用长矛+鞘翅+烟花是怎么弄的」
- 目标玩法：烟花维持高度与速度 → 按敌人编队的长轴在队首外对准 → 平举长矛沿轴线平飞穿过整队 →
  一次冲锋扎穿沿途尽可能多的目标 → 穿出队尾后收矛、抬头拉起 → 转回来再次突袭。

## 评估

1. `spearcore` 的 `SpearItem.onUseTick` 每 tick 调 `getHitEntitiesAlong(attacker, spear,
   hitboxMargin, predicate)`；该方法用“视线起点到 `effectiveMaxRange + 速度`”的一条线构造 AABB，
   把范围内所有实体逐个做命中检测后**全部返回**，再由 `onUseTick` 逐个 `stabAttack`。它不会在
   第一个命中处停手，并对同一实体按 `contactCooldownTicks`（木矛 10 tick）去重。
2. 旧 R1017 链路的问题不在长矛，而在空袭状态机：`tickSpearAir` 只朝**当前主目标**俯冲，并且用
   主目标的 `lastHurtByMobTimestamp` 上升沿判定“命中”，第一次命中就进入保持段、收矛、结束本轮。
   于是长矛明明能扫一条线，女仆却只扎一个人。
3. Jerotes 假玩家（`JerotesPlayerEntity.m_8107_` 字节码）的做法是：滑翔标志位 + 每 10 tick 检查、
   按 20 tick 冷却从主/副手点挂载烟花，视线始终跟着目标；长矛本身则由 `ItemToolBaseSpearBase`
   的 `getHitEntitiesAlong` 沿视线扫多个实体。可借鉴的是**持续滑翔 + 烟花推进 + 视线对准**，
   不需要复制它的实体/网络实现。
4. 因此本轮把“长矛战斗循环”从通用空袭里拆出来：起飞、爬升、落地退路仍共用
   `MaidFlightCombatBehavior`；一旦进入阶段二且主手是长矛，就交给 `SpearAirChain` 独占。
   伤害公式、击落/击退/疲劳条件一个字都不抄，仍由 `spearcore` 自己的 `onUseTick` 结算。

## 改动文件

- `promaid_src_neo/com/maidsmart/combat/SpearAirChain.java`（新增）：编队扫描、最长轴选择、
  进场线/队尾线、对准/冲锋/拉起三阶段、逐目标命中统计、烟花助推、日志与清理。
- `promaid_src_neo/com/maidsmart/combat/MaidFlightCombatBehavior.java`：阶段二长矛分支改调
  `SpearAirChain.tick`；删除旧 `tickSpearAir` / `finishSpearCharge` 与四个单目标状态表；
  落地、`forget` / `pauseRound` / `clearAll` / `isEngaged` 接入新链路；`applyRotation` 与
  `suppressVanillaMelee` 改为包内可见供新链路复用。
- `promaid_src_neo/com/maidsmart/combat/MaidFlightCombatTask.java`：类注释改为“长矛走独立贯穿链路”。
- `promaid_src_neo/com/maidsmart/config/MaidSmartConfig.java`：新增
  `AIR_RAID_SPEAR_PASS_RANGE` / `AIR_RAID_SPEAR_GROUP_RADIUS` / `AIR_RAID_SPEAR_BOOST_INTERVAL`；
  改写 `AIR_RAID_SPEAR_RECOVER_TICKS` / `AIR_RAID_SPEAR_MAX_TICKS` 的语义与注释。
- `promaid_src_neo/com/maidsmart/config/PromaidConfigScreen.java`：② 区改为五行长矛参数。
- `promaid_src_neo/assets/maid_smart/lang/{zh_cn,en_us}.json`：五条配置译文同步。
- `promaid_src_neo/com/maidsmart/guide/GuideChaptersFlight.java` 与
  `promaid_src_neo/assets/promaid/i18n/en_us.json`：手册条目改为“编队贯穿”。
- `promaid_src_neo/assets/promaid/panel_coverage.txt`、`ARCHITECTURE-SWITCHES.md`：重新生成。

## 验证

- Neo 编译：`EXITCODE=0`。
- 14 护栏：`FAIL 0 / WARN 0`；Neo 面板覆盖 605 个字段；`check_guide_i18n` 缺译 0；
  `check_reflection` Neo 维持基线 204（本轮无新增反射）。
- Neo jar：18,111,975 字节，SHA256 `961b94fd15a95817fe4d3d264a6c0107156021014c6da00cc7158d0bd398622e`。
- 专服（测试服 mods 里临时放 `spearcore-1.21.1-neo-3.1.0.jar`）：
  `test_server.py neoforge1211` PASS；`test_maid_load.py neoforge1211` PASS（女仆大脑类正常加载）。
  测试后已把 spearcore 测试 jar 移出测试服。
- 已部署 1.21.1 实例，源包/目标包 SHA256 一致；未重打/未部署 Forge 侧 jar；Forge 源码树本轮零改动。

## 边界

- 仅 1.21.1 NeoForge；1.20.1 的镜像**等开发者验收批准后**再做。
- 只在「近战空袭 + 主手长矛核心」时接管；远程空袭、地面近战、重锤/普通近战武器均不受影响。
- 贯穿路线按**冲锋开始那一刻**的敌人编队固定；队伍在冲锋途中大幅横向散开时可能漏掉个别目标，
  下一轮会重新取轴。不是“自动追踪每一个敌人”。
- 长矛伤害、击落坐骑、击退、疲劳阶段仍全部由 `spearcore` 按相对速度/持续时间结算；
  本模组只负责选线、对准、平飞、保持蓄力与拉起时机，不额外加伤害。
- 烟花助推消耗真实烟花（1 枚/次）；没有烟花但有自推鞘翅时照常飞，只是没有额外加速。
- 未安装 `spearcore` 时 `SpearCoreCompat.isSpear` 恒 false，空袭行为与 R1018 完全一致。
- 版本号仍为 1.3.3（同号重打），不升号。

## 验收

- 装 `spearcore` 3.1.0（1.21.1 Neo）后，给女仆鞘翅 + 任意长矛 + 烟花火箭，切「近战空袭」。
- 观察：她不再从目标正上方垂直扎一个人，而是先绕到编队一侧、在队首外平举长矛（有长矛使用音效），
  然后沿敌人排成的长轴平飞穿过；沿途多个敌人依次挨扎，命中第一个后**不收矛**，穿出队尾才收矛、
  抬头拉起，转回来再冲。
- 日志搜「空袭·长矛」：应出现「起冲：编队 N 个」「贯穿命中第 K 个」「贯穿结束：命中 N 个（穿出队尾）」。
- 打空/超时/贴地/落地都会收矛并切回滑翔或地面退路，不会带着长矛蓄力摔死。
- 配置面板「战斗与自保 → 空袭数值 → ② 收翅俯冲 / 长矛冲锋」可见五行新参数。
- 把长矛换成重锤或普通剑：行为回到 R1016 的重锤猛击/暴击链路。
- 卸载 `spearcore`：空袭行为与 R1018 一致，无报错。
