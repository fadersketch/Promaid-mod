# R1023 1.21.1 长矛空袭起飞复用通用链路 + 平地冲锋命中归因（1.3.3 同号重打）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修复 / 兼容补齐（1.21.1 专属；1.20.1 等开发者验收批准后再镜像）。
- **开工前读过**：`rounds/R1022-neo-spear-dedicated-air-raid.md`、`SpearAirRaidBehavior.java`、
  `SpearAttackBehavior.java`、`MaidFlightCombatBehavior.java`、`MaidFlightKit.java`、
  `SpearCoreCompat.java`、`FlightTargeting` 相关实现；以及实机日志 10:11:28–10:11:37 与
  spearcore `SpearItem` / `SpearDamageTypes` 字节码。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 实机日志定位 | 10:11 整轮只有「进入独立链路 / 爬升结束 / 空袭免摔 / 退出」，**从未出现「水平贯穿点火」**；确认独立链路自己实现的地面起跳与距离门槛失败，且落地后仍占着链路不交回 | ☑ |
| 1 | 起飞复用 | 独立链路只在「通用空袭真正点火（`LAUNCH_LEFT>0`）+ 已离地 + 正在滑翔」后启动；地面起跳、距离 ≤20 格门槛、烟花/羽扇/激流/自推鞘翅与冷却全部调通用链路的同一份代码 | ☑ |
| 2 | 再点火复用 | `BOOST` 结束后 `RELAUNCH` 直接调 `MaidFlightCombatBehavior.tryLaunchMelee`；冷却/燃料不足等 60 tick 仍不行就退出交回通用链路 | ☑ |
| 3 | 落地交回 | `canStillUse` 在 `onGround()` 时返回 false；`stop` 清通用链路的剩余起飞/猛击状态（`pauseRound`），通用链路立即走地面近战或重新起飞，不再原地空转 | ☑ |
| 4 | 平地命中归因 | `tookDamage` 删除「目标血量下降」兜底，只认 `lastHurtByMob == 女仆` 的受伤归因；第三方伤害不再触发 `AFTER_HIT` → 提前后撤 | ☑ |
| 5 | 实机可核对 | 平地新增「举矛起手 / 贴脸先拉开 / 进入冲锋 / 长矛命中 / 后撤拉开」日志；空袭新增「接管控空 / 水平贯穿点火 / 再次点火 / 交回通用」日志 | ☑ |
| 6 | 编译与护栏 | Neo 编译 `EXITCODE=0`；14 护栏 `FAIL 0 / WARN 0`；架构行号与产物同步 | ☑ |
| 7 | 专服回归 | 测试服临时放入 `spearcore-1.21.1-neo-3.1.0`：`test_server` 与 `test_maid_load` 均 PASS，测试后移除 | ☑ |
| 8 | 部署与验收 | 只部署 1.21.1 实例；Forge 源码树与两个 Forge 实例一律不动；待开发者实机验收 | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

- 开发者原话（本轮）：
  「攻击模式，女仆拿着长矛冲锋的时候总是还没冲到就回头跑。刚刚运行的游戏里面，女仆起飞就没成功过，
  并且收回之后直接卡死在原地不动了。起飞你就不能套用空袭的起飞链路吗？直接照搬代码呀。」

## 评估

1. **空袭起飞失败**：R1022 的独立链路自己写了一套 `TAKEOFF`：不看通用链路的 20 格起飞距离、不看
   通用烟花冷却与燃料判定，起跳窗只有 3 tick；目标在 49 格时也从原地直接点火。实机日志 10:11 一整轮
   没有一条「水平贯穿点火」，说明她根本没进入正常的爬升/接近节奏，反复落地、退出、重进。
2. **收回后卡死**：独立链路落地后仍然是 active，通用空袭在 `tick` 顶部整段让位；而独立链路的
   `TAKEOFF` 又起不来，于是地面既没人走位也没人近战。`stop` 之后通用链路的 `LAUNCH_LEFT` 仍可能
   剩几十 tick，通用链路会继续空转「阶段一」而不进入地面分支，观感就是站在原地不动。
3. **修法（直接照搬代码）**：独立链路不再实现任何起跳/点火：
   - 启动门槛 = `!onGround()` + `isGliding()` + `MaidFlightCombatBehavior.isLaunchActive()`（通用链路已
     真正点火）；地面起跳、距离门槛、烟花/羽扇/激流/自推鞘翅、冷却全部由通用链路负责。
   - 空中段仍由独立链路负责：35° 朝目标爬升 → 浅角俯冲 → 高度差 ±2 格补水平烟花 → 平飞缓冲。
   - `BOOST` 结束后的再点火直接调通用链路的 `tryLaunchMelee`（就是把 `tryLaunch` 的 `ranged=false`
     分支原样开放给包内）；冷却/燃料不足等 60 tick 就退出。
   - 落地即 `canStillUse=false`；`stop` 调 `pauseRound` 清掉通用链路剩余的 `LAUNCH_LEFT`/`JUMP_LEFT`/
     `SMASH` 等状态，通用链路下一 tick 就能走地面近战或重新起飞。
4. **平地提前回头**：旧 `tookDamage` 把「目标血量下降」也算命中，别的怪/队友打掉目标血时，她还在
   10 格外的冲锋路上就被判定为「已命中」→ 同向 1 秒 → 后撤。spearcore 的矛伤伤害源把攻击者归因给
   女仆（`SpearDamageTypes.spear(level, attacker)` 字节码实证），因此只认 `lastHurtByMob == 女仆`
   即可准确区分她自己的命中；归因不是她时保持冲锋，下一 tick 继续判定。

## 改动文件

- `promaid_src_neo/com/maidsmart/combat/SpearAirRaidBehavior.java`（439 行）：去掉 TAKEOFF 相位与
  自建起跳/点火；新增 CLIMB / APPROACH / BOOST / RECOVER / RELAUNCH；启动/续飞复用通用链路；
  落地退出并 `pauseRound`；水平烟花共用通用空袭的冷却表。
- `promaid_src_neo/com/maidsmart/combat/MaidFlightCombatBehavior.java`（3365 行）：`tryLaunch` 拆出
  近战口径 `tryLaunchMelee`；开放 `isLaunchActive` / `canLaunch` / `canTakeOff` /
  `canJumpToLaunch` / `launchRange` / `jumpTicks`；`faceLaunchDirection` / `faceAwayAndUp` /
  `climbPitch` / `faceUpForward` 支持显式近战/远战口径；新增共用烟花冷却读写。
- `promaid_src_neo/com/maidsmart/combat/SpearAttackBehavior.java`（395 行）：命中判定只认女仆归因；
  新增起手/贴脸拉开/进入冲锋/命中/后撤日志。
- `tools/arch/arch_modules.py` 与 `normify-promaid/`：行号与描述同步，架构产物重新生成。
- `rounds/RELEASES.tsv`、`release_body.md`、`release_body_neo.md`、`RELEASE_NOTES_v1.3.3.md`、
  桌面《Promaid_v1.3.3_发布声明.md》与《v1.3.3 临时追加更新》追加本轮说明。

## 验证

- Neo 编译：`EXITCODE=0`。
- 14 护栏：`FAIL 0 / WARN 0`；`check_mixin_targets` 校验 142 个注入点；
  `check_arch` / `check_guide_i18n` / `check_i18n_keys` 全部 0。
- 专服（测试服 mods 里临时放 `spearcore-1.21.1-neo-3.1.0.jar`）：
  `test_server.py neoforge1211` PASS；`test_maid_load.py neoforge1211` PASS；测试后已移除测试 jar。
- Neo jar：18,123,643 字节，SHA256
  `5FF84EE5F49BE6E87287438E18314298741BD919D89D3558A00389D2610EC785`。
- 已部署 1.21.1 实例，源包/目标包 SHA256 一致；未重打/未部署 Forge 侧 jar；
  Forge 源码树本轮零改动。

## 边界

- 仅 1.21.1 NeoForge；1.20.1 的镜像**等开发者验收批准后**再做。
- 独立链路的启动门槛要求「通用链路已真正点火」：地面起跳失败时它会一直让通用链路处理，
  不会再出现「独立链路占着地面却不飞」。
- 空中安全阀（挡墙/超时）与再点火超时都直接退出交回通用链路；不会自己从空中再次起跳。
- 水平烟花与通用空袭共用冷却表，避免「刚起飞又立刻烧一枚」；羽扇/激流/自推鞘翅的优先级与冷却
  仍完全由通用链路决定。
- 平地命中判定只看原版受伤归因；若目标在同一 tick 内先被她打中、又被别的生物打中导致归因被覆盖，
  她下一 tick 会继续冲锋并再次命中，不会误后撤。
- 版本号仍为 1.3.3（同号重打）。

## 验收

- 空袭：给女仆鞘翅 + 长矛 + 烟花，切近战空袭。让她离目标 30~50 格：应看到通用空袭先走位到 20 格内、
  起跳、放烟花，随后日志出现「接管控空」并 35° 朝目标爬升；接近后出现「水平贯穿点火」并真正穿过目标。
  一轮结束后出现「再次点火（沿用通用空袭起飞链）」，不应再原地反复「进入/退出」。
  落地后若还没死，应立即走位/近战或重新起飞，不应站在原地不动。
- 平地攻击模式：把怪放在她 9 格内，观察先举矛拉开到 10 格左右再回头冲锋；日志应按
  「举矛起手/贴脸先拉开 → 进入冲锋 → 长矛命中 → 后撤拉开」顺序出现。第三方（其他怪/队友）
  打目标时不应提前出现「长矛命中」或「后撤拉开」。
- 卸载 `spearcore`：所有新增判据恒 false，行为与之前一致，无报错。
