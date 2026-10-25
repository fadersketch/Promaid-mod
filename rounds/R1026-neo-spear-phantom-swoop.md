# R1026 1.21.1 长矛空袭 WineFox 式进场几何 + 128 格维持（1.3.3 同号重打）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修复 / 体验补齐（1.21.1 专属；1.20.1 等开发者验收批准后再镜像）。
- **开工前读过**：`rounds/R1025-neo-spear-hit-level-parallel.md`、`SpearAirRaidBehavior.java`、
  `MaidFlightCombatBehavior.java`（`FlightTargeting.HOLD_RANGE`）、实机日志 11:36–11:39；
  1.21.1 反编译的 `Phantom$PhantomMoveControl`、`LivingEntity.travel` 滑翔分支；
  以及 OrangeMiracle/WineFox-Skyfarer 的 `docs/FLIGHT_CONTROL_OVERVIEW.md`、
  `docs/VANILLA_PHYSICS.md`、`SweepingAttackPlanner`、`AttackLanePlanner`、`DiveProfile`、
  `HeadingController`、`GlidePlanner.feedbackPitch`、`FlightPrediction.Aim`、`SpearHitGeometry`。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 第一次实测定位 | 独立链路「接管控空」后 1–5 秒必出现「目标丢失或超出 50 格」；增伤与水平段都没有机会结算 | ☑ |
| 1 | 维持半径 | `TARGET_RANGE` 50→128，与通用空袭 `FlightTargeting.HOLD_RANGE` 同口径；退出日志同步 | ☑ |
| 2 | 第二次实测定位 | 只抄了幻翼的转向限速、没抄它的速度/几何：俯冲被 20° 上限卡住，`dampVertical` 又在 ±0.8 内清零下降速度 → 从目标上方平飞 | ☑ |
| 3 | 对照 WineFox | 确认关键不是"写速度"，而是：① 爬升高度必须受"接敌前能否降下来"约束；② 进场用固定斜率曲线，接敌处切线收平；③ 俯仰用高度/竖直速度反馈；④ 起飞后不应背离目标飞 | ☑ |
| 4 | 爬升方向 | 前 4 tick 保留通用起飞朝向；之后直接朝目标上方爬，消除空中 180° 回头 | ☑ |
| 5 | 爬升高度封顶 | `maxRiseForDistance` 按 `DiveProfile.minimumDistance` 反解；超过可俯冲高度立即转平飞/接近，剩余推力转水平 | ☑ |
| 6 | 曲线俯冲 | 进场时锁定一次 `diveSlope = rise*(d+8)/d²`；每 tick 用 `goalY = passY + slope*d²/(d+8)`、`goalVy = -h*slope*d*(d+16)/(d+8)²` 跟踪，接敌处自然收平 | ☑ |
| 7 | 俯仰反馈 | 照 `GlidePlanner.feedbackPitch` 用高度误差 + 竖直速度误差反算俯仰；不可行时先保持高度把距离飞出来 | ☑ |
| 8 | 瞄准与点火 | 目标提前量最多 12 tick；偏航 12/8/5°/tick；点火要求高度带 0.4、`|vy|≤0.2`、`spearRayHits` 视线穿过目标箱 | ☑ |
| 9 | 编译与护栏 | Neo 编译 `EXITCODE=0`；全部护栏 `FAIL 0 / WARN 0`；架构行号与 `normify-promaid/` 重新生成 | ☑ |
| 10 | 专服回归 | 测试服临时放入 `spearcore-1.21.1-neo-3.1.0`：`test_server` 与 `test_maid_load` 均 PASS，测试后移除 | ☑ |
| 11 | 部署与验收 | 只部署 1.21.1 实例，源包/目标包字节数一致；Forge 源码树与两个 Forge 实例一律不动 | ☑ |
| 12 | 魂符/死亡重放卡死 | 离场（DISCARDED/KILLED）与入世界时清空长矛/空袭/飞行静态状态、目标与导航，重放后能正常移动 | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

- 开发者原话（第一次）：
  「空袭状态下还是不太行。仍然没有触发增伤。而且也没有做到水平方向调整。我觉得飞行轨迹不要做成直来直去的那种。
  俯冲可以搞成类似于幻翼的那种飞行轨迹。你先去搜索一下相关代码。」
- 开发者原话（第二次）：
  「在空中飞行时间过长，而且没真正学到幻翼，根本瞄不到人，每次最终水平方向都是在敌方的上方。
  你是不会套用幻翼的代码吗？」
- 开发者原话（第三次）：
  「也不要闭门造车了，你看看他这边是怎么搞的。github https://github.com/OrangeMiracle/WineFox-Skyfarer」
- 开发者原话（第四次）：
  「能否整个长矛链路都套用他的代码？只在消耗以及p冲锋时释放烟花采用我们的项目（使它更具有实战价值。）
  而且我发现在近战空袭收回魂符/死亡再重新放出的时候女仆会直接卡死在原地不动。」

## 评估

1. **第一次没增伤：链路提前退出**。日志每轮「接管控空」后 1–5 秒就出现
   「退出长矛空袭独立链路（交回通用空袭：目标丢失或超出 50 格）」。长矛链路硬编码 50 格，
   通用空袭是 128 格；退出时 `stop()` 停掉长矛蓄力，spearcore 的相对速度增伤没有机会结算。
   已改为 128 格。
2. **第二次仍然从头顶飞过：只抄了幻翼的"转向"，没有进场几何**。上一版每 tick 只限速 yaw/pitch，
   速度仍靠原版滑翔；同时俯冲角被 20° 上限卡住、`dampVertical` 在高度差 ±0.8 内清零下降速度。
   更根本的是：通用起飞对地面目标是"背离 + 抬头"，爬升吃满推力后可能高出目标 20+ 格；
   而鞘翅从 20 格高度降回目标至少需要 50+ 格水平距离，接敌时根本来不及下降。
3. **WineFox 的做法（源码核对）**：
   - `DiveProfile.minimumDistance(rise, speed, gravity) = max(12, speed*(sqrt(2*(rise-2)/g)+8))`：
     先算"这个高度需要多少水平距离才能降下来"，不满足就不进场。
   - `CLIMB` 只取"下一次攻击需要的高度/速度"，不持续抬高目标高度；`AttackPolicy.canEnter`
     还会拒绝剩余时间不够完成转向的入口。
   - `AttackLanePlanner` 的平地曲线：`slope = rise*(distance+8)/distance²`，
     `pathHeight(d) = targetY + slope*d²/(d+8)`，`pathVerticalSpeed` 在 d→0 时收平。
   - `GlidePlanner.feedbackPitch` 用 `(goal-y)` 和 `(vy-goalVy)` 反算俯仰，靠原版滑翔的
     拉升/下压转换跟踪曲线；`HeadingController` 只改朝向，从不直接旋转速度。
   - `FlightPrediction.Aim` 用眼睛位置 + 真实视线对目标碰撞箱做射线命中判定；
     近距离取最多 12 tick 的目标提前量。
4. **本轮实现**：
   - 起飞前 4 tick 保留通用朝向，随后朝目标上方爬——不再先背离再空中掉头。
   - 爬升段用 `maxRiseForDistance` 封顶：超过可俯冲高度立刻转平飞/接近，剩余推力变成水平速度。
   - 进场时只锁定一次 `diveSlope`，之后按剩余距离跟踪；每 tick 重算会让末段突然变陡。
   - 俯仰不再直指目标，而是 `feedbackPitch` 跟踪曲线：中段下压、接敌处收平。
   - 目标提前量最多 12 tick；偏航近距降到 5°/tick，避免机头在目标前方来回摆。
   - 点火前用 `spearRayHits` 检查真实视线是否穿过目标碰撞箱；高度带 0.4、`|vy|≤0.2`。
   - 删掉上一版的 `phantomSweep` / 直接写速度 / 横向 S 偏移；只保留低速时沿视线补一点冲量。
5. **魂符收回/死亡重放后卡死：静态状态按 UUID 泄漏**。`SpearAirRaidBehavior.ACTIVE/STATES`、
   `SpearAttackBehavior.STATES`、`MaidFlightCombatBehavior` 的若干表都以女仆 UUID 为键；
   魂符链路用 `maid.discard()` 收人，行为 `stop()` 不保证被调用，状态因此留在表里。
   重放后还是同一个 UUID，通用空袭/长矛行为看到"这只还在链路里"就继续让位，
   而她本人没有速度也没有目标 → 原地不动。修法：在 `EntityLeaveLevelEvent`
   （`DISCARDED`/`KILLED`）与 `EntityJoinLevelEvent` 两侧调用
   `clearCombatStateOnTransition`，一次性清空长矛/空袭飞行静态表，并清目标、
   `ATTACK_TARGET` 记忆、导航、滑翔、蓄力与爬升缓存；普通跨维度传送只做
   `pauseRound`，保留烟花冷却与目标记忆。

## 改动文件

- `promaid_src_neo/com/maidsmart/combat/SpearAirRaidBehavior.java`（790 行）：
  `TARGET_RANGE=128`；爬升方向与高度封顶；`steerAttack`、`feedbackPitch`、
  `maxRiseForDistance`、`diveSlope`、`spearRayHits`；命中/再点火等待 40 tick。
- `promaid_src_neo/com/maidsmart/ProMaidExtension.java`：新增
  `clearCombatStateOnTransition`，在 `onMaidLeaveLevel` / `onMaidJoin` 清静态状态。
- `tools/arch/arch_modules.py` 与 `normify-promaid/`：行号 790，描述改为
  「爬升封顶 + 固定斜率曲线俯冲 + 俯仰反馈」，产物重新生成。
- `rounds/RELEASES.tsv`、发布文档与桌面文本：追加本轮说明。

## 验证

- Neo 编译：`EXITCODE=0`。
- 全部护栏：`FAIL 0 / WARN 0`；`check_arch` 一致；`check_mixin_targets` 校验 275 个注入点。
- 专服（测试服 mods 里临时放 `spearcore-1.21.1-neo-3.1.0.jar`）：
  `test_server.py neoforge1211` PASS；`test_maid_load.py neoforge1211` PASS；测试后已移除。
- Neo jar：18,128,686 字节，SHA256
  `33F7E6F77EDA50430D99EFA10E21EA18BCA1C3D4CD51A05797ED1E881B3C3140`。
- 已部署 1.21.1 实例，源包/目标包字节数一致（18,128,686）；目录内只有一个 promaid 包：
  `promaid-1.3.3-neoforge-1.21.1.jar`。Forge 源码树与两个 Forge 实例本轮零改动。

## 边界

- 仅 1.21.1 NeoForge；1.20.1 的镜像**等开发者验收批准后**再做。
- 只改朝向、不直接写速度：原版滑翔负责把水平速度逐渐对到视线；低速时只补很小的沿视线冲量。
- 进场几何是 WineFox 完整异步预测器的简化版：没有逐 tick 全寿命轨迹认证，只用
  `DiveProfile` 的可下降距离 + 固定斜率曲线 + 俯仰反馈。极端地形/高速目标仍可能失败。
- 水平矛线对高度低于 0.8 格的小型生物仍是几何限制；需要俯冲或虚拟纵向扩展才能命中。
- 伤害/击退/疲劳仍由 spearcore 结算；本轮只保证接近段方向、高度和速度。
- 版本号仍为 1.3.3（同号重打）。

## 验收

- 空袭（主手长矛 + 鞘翅 + 烟花，近战空袭）：
  1. 点火后应看到「接管控空（…推力 N tick…）」，**不再**在 1 秒内出现
     「退出长矛空袭独立链路」；40–100 格的目标也应继续维持；
  2. 起飞后约 4 tick 应转向目标方向，不再先背离飞远再掉头；
  3. 爬升段应出现「达到可俯冲高度（…高于目标 X 格）→ 曲线俯冲」，X 不应再是 20+；
  4. 接近段应能看到平滑下压、接敌前收平，而不是从目标头顶直线掠过；
     最后应出现「水平贯穿点火」；
  5. 命中时应出现「长矛命中（相对速度 …）→ 强制水平，矢量耗尽后再点火」，
     相对速度应明显大于 0；单趟从点火到接敌的时间应明显短于上一版。
- 魂符/死亡重放（近战空袭过程中）：
  1. 空袭途中用魂符收回，再原地放出：女仆应正常跟随/移动，不再原地卡死；
  2. 空袭途中被击杀（触发魂符保存）后放出：同上；
  3. 日志里应能看到「离场」记录（reason=DISCARDED/KILLED），随后入世界；
     不应出现她仍被空袭行为接管、却没有速度的停留状态。
- 卸载 `spearcore`：所有新增判据恒 false，行为与之前一致，无报错。
