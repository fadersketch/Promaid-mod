# R1081 五项战斗修复（1.3.3 同号重打）

## 排班

- **跑道**：C 功能（两树镜像；第 1 项鞘翅长矛为 1.21.1 NeoForge 专属，1.20.1 无此链路）。
- **开工前读过**：`rounds/R1080`、`R1079`、`R1078`；`SpearAirRaidBehavior`、`MaidVirtualFirework`、
  `FlightFireworkPose`、`FlightTargeting`、`MaidMountCompat`、`RideBindManager`、`MaidAidOwnerBehavior`、
  `MaidExtraContainer`、`MaidSupportPolicy`、`PotionCompat`；实机日志
  `D:\.minecraft\versions\1.21.1-NeoForge_21.1.250\logs\latest.log`（GBK）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 幻翼音效 | 鞘翅长矛全程不再播放 `PHANTOM_SWOOP` | ☑ |
| 1 | 烟花粒子 | 起飞点火一束 + 推力窗每 tick 尾迹；壮丽/混沌仍为龙息 | ☑ |
| 2 | 前几次俯冲 | 低空起飞先爬到目标上方 8 格，再对到正上方 3 格内才俯冲 | ☑ |
| 3 | 地面载具开炮 | 绕圈档与炮塔档共用 `FlightTargeting` 的锁定目标 | ☑ |
| 4 | 机枪命中 | 直瞄改用真实炮口当发射点，AI 直瞄散布钳到 0.02 | ☑ |
| 5 | 精妙背包装填 | `feedVehicleAmmo` 先从额外容器拉取匹配弹药（含创造弹药盒） | ☑ |
| 6 | 药水等级 | 按 amplifier 排序，并按效果 id 识别模组高阶药水 | ☑ |
| 7 | 编译与护栏 | 两树编译 `EXITCODE=0`；14 护栏 `FAIL 0` | ☑ |
| 8 | 测试与部署 | 四套专服测试 PASS；双 jar；4 实例 `match=True` | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

开发者原话（五条）：

1. 女仆使用鞘翅长矛攻击时会发出幻翼的声音，疑似误搬音效；起飞和突进时缺少烟花粒子；
   最初几次运动打不中怪且动作很怪，之后恢复正常。
2. 离主人远接敌时女仆会开坦克，但只会绕动不会开炮。
3. 女仆驾驶「弓箭手自行火炮」（FH-77BW）时上面的机枪打不中。
4. 没做到和精妙背包的兼容：精妙背包里有东西女仆不认。例如把创造弹药盒放进女仆的背包，
   女仆不会拿它装填载具。理论上本 mod 里所有扫背包的功能都需要再扫一遍精妙背包。
5. 援护只丢瞬间治疗 I，不丢瞬间治疗 II；支援亡灵只用伤害 I，不用瞬间伤害 III。

## 评估

- **幻翼音效**：`enterSwoop` 里直接抄了原版幻翼的 `PHANTOM_SWOOP` 播放，属于误搬；整段删除。
  冲锋前的烟花加速仍保留 `FIREWORK_ROCKET_LAUNCH`，这是真实消耗烟花该有的声音。
- **烟花粒子**：`MaidVirtualFirework.particles` 旧版对常规鞘翅直接 `return`，只有壮丽/混沌有龙息；
  现改为点火 `FIREWORK` 一束（12 粒），推力窗每 tick 在机身后方 3 粒尾迹。壮丽/混沌维持龙息。
- **前几次俯冲打不中 / 动作怪**：根因是地面起飞后 10 tick 仍在低空，旧版从环绕半径直接斜着扎向
  目标；日志里多次出现「幻翼态触地」并伴随 27~37 格的浅角俯冲。现加两道闸：
  ① 高度未到目标上方 8 格时只爬高、不开始倒计时（100 tick 兜底，避免无限绕圈）；
  ② 俯冲前先飞到目标正上方（水平差 ≤3 格、高度 +12，60 tick 兜底），再垂直扎下。
- **坦克只绕不开炮**：`RideBindManager` 的地面绕圈用 `FlightTargeting.resolve` 拿目标，
  而 `tickAttack` 只读 brain；TLM 会在同一拍擦掉 `ATTACK_TARGET`，于是出现「绕圈档有目标、
  炮塔目标=无」。现在地面载具在 `tickAttack` 里也先问一次 `FlightTargeting.resolve`，
  固定翼仍以索敌器为唯一权威（没目标就是没目标）。
- **FH-77BW 机枪打不中**：实机日志里炮口 `(21.38,-56.97,-441.42)`、目标 `(25,-57.1,-437.3)`，
  期望方向约 `(0.66,-0.02,0.75)`，实际解算却是 `(0.205,-0.028,0.978)`（偏约 30°）。
  原因是 SWB 机枪 `ShootPos.Directions=["Passenger"]`，无乘客时 `getShootVec` 不是炮管指向，
  再按引擎公式反投影会把发射点横向挪出几格；近距离几格就是几十度。直瞄路径改为直接用真实
  炮口当发射点，并把 AI 直瞄的散布从武器自带的 `Spread:0.25` 钳到 `0.02`。引擎自己的
  `turretAutoAimFromUuid` 口径不变，诊断日志仍按旧口径对比。
- **精妙背包**：`getAvailableBackpackInv` 只覆盖她自己那格，精妙/旅行者背包在 TLM 的
  「额外容器」体系里。`feedVehicleAmmo` 在搬弹前先 `MaidExtraContainer.pull`，谓词就是
  现有的 `stackIsWantedAmmo`（已含散装弹、盒装弹、通用弹药盒、创造弹药盒）。没有额外容器时
  `pull` 零开销，行为与旧版一致。
- **药水等级**：旧 `potionStrength` 只看注册名里有没有 `strong_`/`long_`，模组的高阶药水
  （例如 `instant_health` II、`instant_damage` III）没有这些前缀，就和 I 级同分，背包里先扫到
  哪瓶用哪瓶。现在按 `MobEffectInstance.getAmplifier()` 排序（每级 +10），同等级再看持续时间
  （最多 +9），喷溅整体 +100。`isPotionOf` 在注册名不匹配时按效果 id 再认一次，
  HEAL / REGEN / FIRE_RESIST / WATER_BREATHING / HARMING / BUFF 六类都走这条兜底。

## 改动文件

- `promaid_src_neo/com/maidsmart/combat/SpearAirRaidBehavior.java`：
  删除 `PHANTOM_SWOOP` 播放；新增 `aligning` / `alignTicks` 状态与 `MIN_SWEEP_CLIMB=8`、
  `CLIMB_TIMEOUT_TICKS=100`、`SWEEP_ALIGN_DIST=3`、`SWEEP_ALIGN_HEIGHT=12`、
  `SWEEP_ALIGN_MAX_TICKS=60`；`tickCircle` 先过爬高闸与水平对准闸，再 `enterSwoop`。
- `promaid_src_neo/com/maidsmart/combat/MaidVirtualFirework.java`：
  `ignite` 增加点火粒子束；`particles` 对常规鞘翅恢复每 tick 尾迹，壮丽/混沌仍为龙息。
- `promaid_src_neo/com/maidsmart/combat/MaidMountCompat.java`：
  地面载具 `tickAttack` 接 `FlightTargeting.resolve`；`firingSolution` 增加 `fromMuzzle`
  重载；直瞄用真实炮口与 `AI_DIRECT_FIRE_MAX_SPREAD=0.02`；`feedVehicleAmmo` 先
  `MaidExtraContainer.pull`；`availableInv` 注释同步。
- `promaid_src/com/maidsmart/combat/MaidMountCompat.java`：与 Neo 同口径镜像（SRG 映射）。
- `promaid_src_neo/com/maidsmart/combat/MaidAidOwnerBehavior.java`：`potionStrength` 改为
  amplifier 评分；新增 `effectMatches`；`isPotionOf` 增加六类效果兜底。
- `promaid_src/com/maidsmart/combat/MaidAidOwnerBehavior.java`：与 Neo 同口径镜像
  （`m_19564_` / `m_19557_` / `ForgeRegistries.MOB_EFFECTS`）。

## 验证

- Neo 编译：`EXITCODE=0`；Forge 编译：`EXITCODE=0`。
- 全部 14 个护栏：`FAIL 0 / WARN 0`。
- 专服回归（NeoForge 1.21.1）：`test_server.py neoforge1211` PASS；
  `test_maid_load.py neoforge1211` PASS。
- 专服回归（Forge 1.20.1）：`test_server.py 1201` PASS；`test_maid_load.py 1201` PASS。
- Neo jar：18,192,795 字节，SHA256
  `FD2A550D8446FD6A89F4449BAA0F4E7530954BE723D1485739B2BC859C48C814`。
- Forge jar：18,109,005 字节，SHA256
  `F4BA0F50A0558027A2ED4C86C6D0985888CB87439C64A7890C63F5A5BCF2166E`。
- 已部署 4 实例，源包/目标包字节数一致（`match=True`）：1.21.1 Neo、1.20.1 Forge、
  龙之冒险整合包、pack1201 专服。

## 边界

- 第 1 项仅 1.21.1 NeoForge：1.20.1 没有 `SpearCoreCompat` / 长矛空袭链路，不镜像；
  Forge jar 只包含第 2~5 项。
- 烟花粒子只对常规鞘翅恢复；壮丽/混沌仍发龙息（沿用 R1080 的视觉口径）。
- 直瞄散布钳位 `0.02` 对所有走直瞄后门的武器生效（不只机枪）；普通引擎开火路径不受影响。
- 精妙背包/旅行者背包要生效，仍需 TLM 的额外容器条件：Curios 在场、女仆饰品开启、
  背包装在饰品栏。没有额外容器时 `pull` 直接返回，零开销。
- 药水评分只影响「多瓶同类药水选哪瓶」，不改变投掷/饮用的触发条件；同等级、同持续时间的
  两瓶仍按背包顺序取第一瓶。
- 本轮补的是载具装填这条实证缺口；其余直接扫她自己背包的位点未逐一改动（多为写回、压缩盒/
  蓝图/指令等非取物路径，或已有 TLM 额外容器链路）。如需全量覆盖，按功能逐条验收后再补。

## 验收

1. **鞘翅长矛音效**：全程不再听到幻翼俯冲声；冲锋前若消耗烟花，仍有烟花发射声。
2. **鞘翅长矛粒子**：起飞点火时有一束烟花粒子，推进期间机身后方有持续尾迹；壮丽/混沌为龙息。
3. **前几次俯冲**：从地面起飞后先爬高，日志出现「先对到目标正上方再俯冲」，再出现
   「环绕结束 → 俯冲」；前几次不再扎地，命中率与后续一致。
4. **坦克**：主人站远处，女仆开坦克接敌时既绕圈也持续开炮；日志不再出现「绕圈档有目标、
   炮塔目标=无」。
5. **FH-77BW 机枪**：近距离（5~10 格）扫射能打中目标；日志里机枪的期望方向与实际方向
   夹角明显变小。
6. **精妙背包**：把创造弹药盒放进女仆饰品栏的精妙背包，再让她驾驶载具；日志出现
   「额外容器」取物记录，载具进入无限弹状态。
7. **援护药水**：背包同时有瞬间治疗 I 与 II 时优先丢 II；支援亡灵时同时有伤害 I 与
   瞬间伤害 III 时优先用 III。
