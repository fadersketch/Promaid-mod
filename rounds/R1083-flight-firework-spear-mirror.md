# R1083 飞行烟花单特效 / 鞘翅长矛直瞄 / 空推自愈 / 重进后载具 AI / home 载具 / 1.20.1 长矛镜像

## 排班

- **跑道**：C 功能（两树镜像；鞘翅长矛链路本轮从 1.21.1 NeoForge 镜像到 1.20.1 Forge）。
- **开工前读过**：`rounds/R1080`、`R1082`；`MaidVirtualFirework`、`SpearAirRaidBehavior`、
  `MaidFlightCombatBehavior`（起飞/俯冲/加速/命中）、`MaidFlightFollowBehavior`、
  `RideBindManager`（`restore` / `HOUSEKEEP_DIV`）、`MaidMountCompat`、`FlightTargeting`；
  实机日志 `D:\.minecraft\versions\1.21.1-NeoForge_21.1.250\logs\latest.log`（GBK）与
  `logs/promaid.log`（UTF-8）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 飞行特效只留一套 | 点火拍只有一条尾迹；球形 FIREWORK 爆发删除 | ☑ |
| 1 | 鞘翅长矛俯冲直瞄 | 每 tick 刷新目标当前位置；无 shrink/4°/0.2 缓动 | ☑ |
| 2 | 惯性只在命中后开放 | 擦墙/受击/超时退回环绕，不再直接进 POST | ☑ |
| 3 | 取消长矛速度衰减 | `nextSpeed` 恒返回入态速度；两个历史配置键保留但不再读取 | ☑ |
| 4 | 提高冲撞频率 / 缩短轮次 | 首次俯冲 10→6 tick；环绕锚点 20~40→16~32 格；俯冲 6→8 秒上限 | ☑ |
| 5 | 飞行中途原地放烟花 | 连续 10 拍位移 <0.05 格 → 收窗 + 3 秒冷却；调用方不再白烧烟花 | ☑ |
| 6 | 重进后固定翼 AI 丢失 | 乘客关系缺失时按 UUID 重新骑乘；主人未就绪时保留 tag 下轮重试 | ☑ |
| 7 | home 载具停火 / FH-77BW 主炮 | 索敌/开火/装弹在 home 闸之前；玩家占主炮位时 AI 不抢瞄准 | ☑ |
| 8 | 1.20.1 长矛镜像 | 新 `SpearAirRaidBehavior` / `SpearCoreCompat` / `MaidVirtualFirework` 编译通过 | ☑ |
| 9 | 编译与护栏 | 两树编译 `EXITCODE=0`；14 护栏 `FAIL 0` | ☑ |
| 10 | 测试与部署 | 四套专服测试 PASS；双 jar；4 实例 `match=True` | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

1. 女仆飞行时理论上应该只有一个烟花特效，实际看起来像放出了两个。
2. 鞘翅长矛俯冲时要时刻盯紧目标；击中以后才开放惯性；对单命中率低；取消长矛速度衰减；
   增加冲撞频率；减少每一轮的时间。
3. 实际测试有时女仆会在飞行中途卡住，一直在原地放烟花却飞不走。
4. 女仆正在乘坐固定翼飞机时退出游戏，重进后固定翼 AI 丢失：只会一直向前飞，home 模式与
   周边敌人都不改变行为。
5. home 模式下女仆载具还是会停止攻击，且弓箭手（FH-77BW）的炮打不出来。
6. 1.20.1 也有长矛核心，镜像 1.21.1 的鞘翅长矛链路。

## 评估

- **双烟花特效**：旧版点火拍既发 10 粒球形 FIREWORK，又每 tick 发 3 粒尾迹，视觉上是两束。
  现在 `ignite` 只发一次尾迹（数量多一点），`tick` 期间保持同一束；壮丽/混沌鞘翅仍改用
  龙息粒子。起飞链的烟花改为"隐形烟花"后不再生成 `FireworkRocketEntity`，也就没有实体自带的
  第二套粒子与发射音。
- **俯冲命中率**：原幻翼移动的 `shrink = 1 - |dy*0.7|/distH` 在"目标几乎在正下方"时变负，
  水平方向整个翻转；再叠加 4°/tick 转向与 0.2 速度缓动，近距离目标会滑出准星。现在俯冲相位
  改用 `steerSwoop`：机头/视线每 tick 钉在目标当前身体中心，速度以 0.5 权重拉过去，无 shrink。
- **惯性**：R1080 的擦墙/受击直接进 POST（水平惯性），玩家看到"没刺中就飘走"。现在
  擦墙/受击/超时都退回 CIRCLE 重新找角度；只有 `tookSpearHit` / `sweepHits` / 路径碰撞才进 POST。
- **轮次**：首次俯冲 10→6 tick、环绕锚点压到 16~32 格、俯冲爬高门槛 8→6 格、超时 100→80 tick
  （配置默认值仍为 100，代码上限取配置值）；速度全程保持进入幻翼态那一拍的值，不再越冲越慢。
- **原地空推**：`MaidVirtualFirework` 每 tick 记录位移；窗口开着却连续 10 拍几乎不动（<0.05 格）
  时收掉推力窗并给 60 tick 冷却。冷却期内 `ignite` 返回 false，起飞/俯冲加速/飞行跟随三处
  调用方都先问 `isStalled`，不再"烧一枚烟花却推不动"。
- **重进后固定翼**：`RideBindManager.restore` 旧版在乘客关系尚未恢复、或主人尚未加载时直接
  丢掉 `TAG_RIDE_MOUNT`，AI 绑定随之消失。现在：① 乘客关系缺失时按 tag 里的 UUID 找回载具并
  `startRiding`；② 主人还不在线时**保留** tag，下一次 `HOUSEKEEP_DIV` 再试。
- **home 载具**：`RideBindManager.drive` 的 home 分支仍在，但 `markTick` / `tickAttack` /
  `feedAmmoThrottled` 已提到 home 闸之前——home 只停走位，炮塔/机枪/装弹照常。地面载具索敌
  仍受 home 圈约束。FH-77BW 在玩家坐 1 号主炮位时只停车，并跳过 AI 的 `applyVehicleAiTargets`
  与 `aimBallistic`，避免 AI 每 tick 把玩家正在瞄的炮口拽回去。
- **1.20.1 镜像**：Forge 树新增 `SpearAirRaidBehavior`（SRG 版，状态机与 1.21.1 同构）、
  `SpearCoreCompat`（`computeDamageUseDuration` / `getDamageMultiplier` 反射探针；满蓄力直写
  `f_20936_`）、`MaidVirtualFirework`；通用空袭补上 `LAUNCH_START` / `launchAge` /
  `requestRelaunch` / `markFlightFireworkUsed` / `spearPathHit`，并在长矛链路接管时整体让位。
  1.20.1 的 `startUsingItem` 是 `m_6672_`（`m_6674_` 是 swing，只挥手臂不举矛，首版镜像踩到过，
  编译通过但不会蓄力；已按字节码改正）。配置新增 `combat.spearAirRaid` 与 6 个
  `airRaid.spear*`（两个衰减键保留为历史键）。

## 改动文件

- `promaid_src_neo/com/maidsmart/combat/MaidVirtualFirework.java`：单尾迹；停滞检测与冷却。
- `promaid_src_neo/com/maidsmart/combat/SpearAirRaidBehavior.java`：`steerSwoop`、`abortDive`、
  常量收紧、`nextSpeed` 恒速。
- `promaid_src_neo/com/maidsmart/combat/MaidFlightCombatBehavior.java`：起飞拍先摆朝向/滑翔位
  再点火；烟花改隐形；停滞门。
- `promaid_src_neo/com/maidsmart/combat/MaidFlightFollowBehavior.java`：隐形烟花 + 停滞门 +
  收推力窗。
- `promaid_src_neo/com/maidsmart/combat/RideBindManager.java`：`restore` 重骑/保留 tag；
  home 之前跑攻击。
- `promaid_src_neo/com/maidsmart/combat/MaidMountCompat.java`：玩家占 FH 主炮位时跳过 AI 瞄准。
- `promaid_src_neo/com/maidsmart/combat/FlightTargeting.java`：home 载具不受 home 圈限制。
- `promaid_src_neo/com/maidsmart/config/MaidSmartConfig.java`：两个衰减键默认 0 并标注历史键。
- `promaid_src/com/maidsmart/combat/SpearAirRaidBehavior.java`（新）：1.20.1 SRG 镜像。
- `promaid_src/com/maidsmart/combat/SpearCoreCompat.java`（新）：1.20.1 长矛兼容层。
- `promaid_src/com/maidsmart/combat/MaidVirtualFirework.java`（新）：1.20.1 隐形烟花。
- `promaid_src/com/maidsmart/combat/MaidFlightCombatBehavior.java`：起飞标记/让位/隐形烟花/
  长矛路径结算。
- `promaid_src/com/maidsmart/combat/MaidFlightFollowBehavior.java`：隐形烟花接线。
- `promaid_src/com/maidsmart/combat/MaidCombatTacticsBehavior.java`：长矛链路接管时让位。
- `promaid_src/com/maidsmart/ProMaidExtension.java`：注册 237、死亡/入世/离场/重启清理。
- `promaid_src/com/maidsmart/config/MaidSmartConfig.java`：镜像 7 个配置项。
- 两树 lang（`zh_cn` / `en_us`）：7 个译文键。
- `tools/verify/check_reflection.py`、`check_preset.py`：基线随新增探针/开关上调并留痕。
- `ARCHITECTURE-SWITCHES.md`：重新生成。

## 验证

- Neo 编译：`EXITCODE=0`；Forge 编译：`EXITCODE=0`。
- 全部 14 个护栏：`FAIL 0 / WARN 0`。
- 专服回归（NeoForge 1.21.1）：`test_server.py neoforge1211` PASS；
  `test_maid_load.py neoforge1211` PASS。
- 专服回归（Forge 1.20.1）：`test_server.py 1201` PASS；`test_maid_load.py 1201` PASS。
- Neo jar：18,194,259 字节，SHA256
  `D5C657BAEADBD667E5FACE7BC1AB387E15492F37FDEAC38C845BB2B7960382E2`。
- Forge jar：18,133,725 字节，SHA256
  `A15999E406B51EBD20FC17B0ADB0EDA7C48E2C7B081A32417C33EAADF2D2DC06`。
- 已部署 4 实例，源包/目标包字节数一致（`match=True`）：1.21.1 Neo、1.20.1 Forge、
  龙之冒险整合包、pack1201 专服。

## 边界

- 隐形烟花只删表现（火箭实体/爆炸粒子），推力递推、时长（40 tick）与冷却口径不变；想关掉
  整套空袭仍走 `combat.flightMode`。
- 停滞检测只认"窗口开着却不动"，不拦正常的低速悬停/爬升；冷却 3 秒，代价是一枚烟花。
- 鞘翅长矛的直瞄只作用于 SWOOP；CIRCLE 仍沿用幻翼的 shrink 轨迹，盘旋手感不变。
- 取消衰减是硬改：两个衰减配置键仍在 toml 里（旧存档不报错），但代码不再读取。
- 1.20.1 的 `SpearCoreCompat` 满蓄力走反射直写 `useItemRemaining`（`f_20936_`）；未安装
  spearcore 时全部判据短路，不影响普通武器。
- home 载具"不停火"只覆盖卓越前线载具（`Kind.VEHICLE`）；其他坐骑仍按各自链路。
- FH-77BW 主炮位被玩家占用时 AI 完全不碰瞄准/开火；玩家离开 1 号座后恢复。
- `restore` 对主人尚未加载的情况是"本轮不绑定但保留 tag"，不会把载具从女仆身下拽走。

## 验收

1. **烟花特效**：近战/远程空袭起飞与补推时只看得到一束尾迹，不再出现"两枚烟花叠在一起"。
2. **长矛命中率**：俯冲段日志保持"距敌 X 格"逐渐收窄，目标在正下方时不再出现水平翻转绕飞；
   前几次俯冲即能刺中；擦墙/受击后回到环绕而不是直接飘走。
3. **冲撞频率**：一轮"环绕 → 俯冲 → 惯性"明显变短，单位时间冲撞次数上升；长距离俯冲末段
   仍保持速度。
4. **原地空推**：把女仆卡在墙边/树叶里飞行，日志出现"原地空推 10 拍 → 收掉推力窗"，
   3 秒内不再烧烟花；脱困后恢复正常飞行。
5. **重进固定翼**：乘坐固定翼退出再进，飞机仍按 home/敌人改变行为，不再一直向前飞。
6. **home 载具**：home 模式下坦克/火炮停在原地但炮塔与机枪持续开火；玩家坐 FH-77BW 1 号座
   时可以自行瞄准并打出主炮，AI 不抢炮。
7. **1.20.1 长矛**：Forge 1.20.1 装 spearcore 后，近战空袭主手持矛同样进入"环绕 → 俯冲 →
   长矛冲锋 → 惯性"循环，伤害按长矛自身结算。
