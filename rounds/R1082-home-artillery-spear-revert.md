# R1082 守家持续攻击 / 弓箭手炮架 / 鞘翅长矛回调（1.3.3 同号重打）

## 排班

- **跑道**：C 功能（两树镜像；鞘翅长矛为 1.21.1 NeoForge 专属）。
- **开工前读过**：`rounds/R1080`、`R1081`；`RideBindManager`（home 闸、地面载具绕圈）、
  `MaidMountCompat`（`tickAttack`、`gunNameFor`、`stop`、`seatHasPlayer`）、`SpearAirRaidBehavior`、
  SWB `Fh77bwEntity` / `VehicleEntity` / `ArtilleryEntity` 字节码与 `fh_77bw` 动画/配置；
  实机日志 `D:\.minecraft\versions\1.21.1-NeoForge_21.1.250\logs\latest.log`（GBK）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 守家不停火 | home 闸之前先跑索敌/开火/装弹，home 只停走位 | ☑ |
| 1 | 鞘翅长矛命中率 | 撤回强制"飞到目标正上方"对准，只保留爬高闸 | ☑ |
| 2 | 弓箭手炮架 | 玩家坐主炮位时停车，SWB 停稳后自己放下驻锄 | ☑ |
| 3 | AI 不抢玩家炮 | `gunNameFor` 兜底跳过玩家座位 | ☑ |
| 4 | 编译与护栏 | 两树编译 `EXITCODE=0`；14 护栏 `FAIL 0` | ☑ |
| 5 | 测试与部署 | 四套专服测试 PASS；双 jar；4 实例 `match=True` | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

1. 女仆在载具处于 home 模式的时候，不要让载具停止攻击。
2. R1081 新加的鞘翅长矛命中率大幅下降。
3. 女仆驾驶弓箭手自行火炮（FH-77BW）时，自动把炮架放下来，让玩家可以自由控制是否使用大炮
   （载具自身火炮要求车停了才能用）。

## 评估

- **home 只停走位**：`RideBindManager.drive` 的 home 分支原本在 `tickAttack` / `feedAmmoThrottled`
  之前 `return`，所以守家时连炮塔/机枪都停了。现在把 `markTick` / `tickAttack` /
  `feedAmmoThrottled` 提到 home 闸之前；home 分支仍只做 `stopNavigation` + 朝主人，不再移动。
  地面载具的索敌本来就走 `FlightTargeting`，仍受 home 圈约束，不会追出守家范围。
- **鞘翅长矛命中率**：R1081 的"先飞到目标正上方"闸在 1.67 格/tick 的速度下受转向半径限制，
  实机日志从水平差 8.8 格开始，60 tick 超时后反而到了 26.5~29.5 格才俯冲，比 R1080 更远。
  本轮撤回 `tickAlign` / `SWEEP_ALIGN_*` / `aligning`，只保留"未到目标上方 8 格先爬高
  （100 tick 兜底）"的闸；俯冲起点回到环绕半径 5~15 格，命中率恢复 R1080 口径。
- **FH-77BW 主炮**：反编译实证 `TurretControllerIndex=1`，主炮 `Main` 在 1 号座；
  `canShoot` 要求射手就是 1 号座乘客、车体竖直、水平速度平方 ≤0.001。女仆在 0 号座，
  玩家坐 1 号座时主炮归玩家。现在只要 1 号座是玩家：
  ① `drive` 直接 `stop(mount)` 刹停并返回，不再绕圈；
  ② `gunNameFor` 的 ④ 兜底跳过玩家座位，AI 不会再从 0 号座把 1 号座的主炮"捞回来"替玩家扣扳机。
  停稳后 SWB 自己的 `Fh77bwEntity.baseTick` 会把尾部驻锄放下（水平速度平方 ≤0.004 →
  `HOE_UP=false`），也就是玩家看到的"炮架放下来"；我们不再额外每 tick 强写 `OUT`，
  避免和它自己的状态机每 tick 重算打架、把支撑腿抽得来回动。玩家不在 1 号座时仍走原来的
  绕圈接敌，机枪不受影响。

## 改动文件

- `promaid_src_neo/com/maidsmart/combat/RideBindManager.java`：
  攻击/装弹前移到 home 闸之前；新增 FH-77BW 主炮位分支（只停车，驻锄由 SWB 自己放下）。
- `promaid_src/com/maidsmart/combat/RideBindManager.java`：与 Neo 同口径镜像。
- `promaid_src_neo/com/maidsmart/combat/MaidMountCompat.java`：
  `seatHasPlayer` 公开；新增 `isFh77bw`（按类名认，无新增反射）；`gunNameFor` 兜底跳过玩家座位。
- `promaid_src/com/maidsmart/combat/MaidMountCompat.java`：与 Neo 同口径镜像。
- `promaid_src_neo/com/maidsmart/combat/SpearAirRaidBehavior.java`：删除 R1081 的强制对准状态与
  常量，保留爬高闸。

## 验证

- Neo 编译：`EXITCODE=0`；Forge 编译：`EXITCODE=0`（96 个既有警告）。
- 全部 14 个护栏：`FAIL 0 / WARN 0`。
- 专服回归（NeoForge 1.21.1）：`test_server.py neoforge1211` PASS；
  `test_maid_load.py neoforge1211` PASS。
- 专服回归（Forge 1.20.1）：`test_server.py 1201` PASS；`test_maid_load.py 1201` PASS。
- Neo jar：18,192,545 字节，SHA256
  `E4CB8B1654BADEFBE0004C3AFED9D718993184FDCE4FEC5A4C06F32FDE92324C`。
- Forge jar：18,109,159 字节，SHA256
  `7ECC9E187D17E8E4A248E6DE304D8A8E97AF6F9D29DD9B1B517719F5AC746AC0`。
- 已部署 4 实例，源包/目标包字节数一致（`match=True`）：1.21.1 Neo、1.20.1 Forge、
  龙之冒险整合包、pack1201 专服。

## 边界

- home 持续攻击只对载具生效；诡厄那类"无鞍可骑仆从"仍按原口径在 home 档交还给它的自身 AI，
  不强行写目标。
- 地面载具守家索敌仍受 home 圈限制；圈外的敌人不会追出去打。
- FH-77BW 的停车/放炮架只在 **1 号座（主炮位）是玩家** 时触发；玩家坐机枪位或没上车时，
  女仆仍按原绕圈接敌行驶。
- 炮架/驻锄动画完全交给 SWB 自己的 `baseTick`（停稳 → `HOE_UP=false`），我们只负责把车停稳；
  不依赖 SWB 的私有方法名，换版本也不会因反射失败。
- 鞘翅长矛回调仅 1.21.1 NeoForge；1.20.1 没有该链路。

## 验收

1. **守家坦克**：把女仆设为驻守（home），敌人进入 home 圈后，坦克停在原地但炮塔/机枪持续开火；
   不再出现"只有绕圈、不还击"。
2. **鞘翅长矛命中率**：接敌后日志重新出现"环绕结束 → 俯冲（距敌 20~30 格）"，不再有
   "先对到目标正上方（水平差 8.8 格）"后拖到 26+ 格才俯冲；前几次俯冲不再扎地。
3. **弓箭手炮架**：玩家坐进 FH-77BW 1 号座（主炮位）后，女仆把车刹停；停稳后 SWB 自动把
   尾部驻锄放下（炮架落下动画），玩家可以自行瞄准并开主炮。玩家离开 1 号座后，女仆恢复绕圈行驶。
4. **AI 不抢炮**：玩家在主炮位时，女仆不会自动替玩家开主炮；机枪仍可工作。
