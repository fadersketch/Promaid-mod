# R1063 一键传送 AC-130H 后的飞行状态重对齐

## 排班

- **跑道**：A 修 bug（外部一键传送把载具整体搬走后，女仆的飞行链路仍按旧坐标运行）。
- **开工前读过**：`RideBindManager.tick/drive`、`MaidAirCombat`（盘旋/通场/爬升状态）、
  `MaidMountCompat`（降落跑道、助跑带、地面调头、瞄准缓存）、`FlightTargeting`、
  `MaidShellHoming`，以及 `2026-10-20 21:00～21:05` 的 `latest.log`。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 确认「一键传送」走的是哪条路 | 不是本模组传送：外部直接改载具坐标，链路表与飞行状态都不知情 | ★ |
| 2 | 找出瞬移后仍指向旧位置的缓存 | 盘旋旋向/通场航线/降落跑道/助跑带/地面符号/锁定/在飞炮弹 | ★ |
| 3 | 每 tick 检测「一 tick 位移 > 16 格」 | 正常固定翼每拍 ≤ 2.6 格，16 格只可能是瞬移 | ★ |
| 4 | 瞬移后一次清空上述状态 | 下一拍全部按新位置重新采样 | ★ |
| 5 | 两树编译、14 护栏、四套测试、4 实例部署 | 全部 PASS / match=True | ★ |
| 6 | 交开发者验收 | 一键传送 AC-130H 后不再掉头飞回旧战场、不再按旧跑道起降 | ☐ |

## 需求 / 来源

- 开发者反馈：「一旦玩家使用一键传送把 AC-130H 传送过来，女仆的飞行链路就会彻底混乱。」

## 评估

「一键传送」不是本模组的排班表传送（那条会先解绑、只传人），而是外部功能直接把
**载具实体**的坐标搬到新位置（战术地图 / 召回道具 / 命令）。女仆作为乘客会被原版
`teleportTo` 一并摆到新位置，但本模组所有**按坐标记的飞行状态**都留在旧位置：

| 状态 | 存了什么 | 瞬移后的表现 |
|---|---|---|
| `MaidAirCombat.GUNSHIP_ORBIT_SIGN` | 侧射炮艇的固定盘旋方向 | 从错误的一侧切入，先掉头飞一大圈 |
| `MaidAirCombat.FIXED_WING_RUN` | REJOIN / ATTACK / EGRESS 三个世界坐标 | 前射机先飞回旧通场航线 |
| `MaidMountCompat.AIRCRAFT_LAND_PLAN` / `_SPOT_LEGACY` | 接地点、跑道方向、下滑道 | 按旧坐标降落（最长缓存 6000 拍） |
| `MaidMountCompat.RUNWAY_CACHE` | 起飞助跑方向与落差 | 在新地形按旧跑道方向滑跑 |
| `MaidMountCompat.GROUND_*` / `AVOID_AT` | 地面调头符号、卡住计数、避障结论 | 地面载具朝错误方向倒车 |
| `MaidMountCompat.NOSE_AIM` / `LAST_DESIRED` / `AIM_STALL` | 机头瞄准点与未对准计数 | 炮口先指向旧目标 |
| `FlightTargeting.LOCKED` + brain `ATTACK_TARGET` | 旧位置的敌人 | 瞬移后仍掉头去打旧战场（576 格维持圈内） |
| `MaidShellHoming.TRACKED` | 旧位置打出去的炮弹 | 追不到新位置，还在未加载区块里冻着被反复纠向 |

日志证据（21:00:32 / 21:00:38）：排班表「集合」一次位移 994 格、另一次 341 格；
21:04:15 重新绑定后，飞机先连续 4 次「固定翼降落完成·旧式：已接地 → 停住不动（电量见底）」，
随后才从地面滑跑起飞，并在接敌/跟随两档之间来回切换。

## 改法

两树 `RideBindManager`：

1. 新增 `LAST_MOUNT_POS`（载具 UUID → 上一 tick 坐标）与 `MOUNT_TELEPORT_JUMP = 16.0`。
2. `tick()` 在链路有效性检查之后、任何驱动之前调 `checkMountTeleport()`：
   一 tick 内位移超过 16 格即判定为瞬移（正常固定翼上限 2.6 格/拍）。
3. 瞬移时依次：
   - `MaidAirCombat.clear(maid)` —— 清盘旋旋向、通场航线、爬升窗、战斗绕圈状态；
   - `FlightTargeting.forgetAndErase(maid)` —— 清锁定并擦掉 brain 的 `ATTACK_TARGET` /
     `LOOK_TARGET`，下一拍按新位置重新索敌；
   - `MaidShellHoming.forgetMaid(maid)` —— 丢掉她名下旧位置的在飞/待认领炮弹；
   - `MaidMountCompat.resetAfterTeleport(mount)` —— 清降落跑道、助跑带、地面调头、
     悬停机头、刹车脉冲、瞄准缓存、爬升窗；
   - `clearRiderNav(maid)` + `MaidRideKit.stopNavigation(mount)` —— 清她脑里的旧走位路径，
     地面载具不会照着旧路径往回开；
   - 写一行 `模组坐骑` 日志：「检测到载具瞬移 N 格（一键传送/召回）→ … 按新位置重新起手」。
4. 链路正常解除时同步移除 `LAST_MOUNT_POS` 条目，表不会泄漏。

只清缓存：链路、电量、姿态、速度、动量都不动（瞬移该保留的动量归引擎管）。

## 触及文件

- `promaid_src/com/maidsmart/combat/RideBindManager.java`
- `promaid_src/com/maidsmart/combat/MaidMountCompat.java`
- `promaid_src/com/maidsmart/combat/FlightTargeting.java`
- `promaid_src/com/maidsmart/combat/MaidShellHoming.java`
- `promaid_src_neo/...` 同名文件的镜像
- 无新配置项、无新 i18n、无新 mixin、无新 Java 类。

## 验证

- Forge `compile_promaid`：`EXITCODE=0`；Neo `compile_neo`：`EXITCODE=0`。
- 14 护栏全部 `FAIL 0`。
- `test_server 1201` / `test_server neoforge1211`：PASS。
- `test_maid_load 1201` / `test_maid_load neoforge1211`：PASS。
- `deploy_all.py`：4 个实例全部 `match=True`。
- 产物：
  - Forge `patched/promaid-1.3.3-forge-1.20.1.jar` 18,097,810 字节，
    SHA256 `94F4EFDD528D6B78D6349F673A2FDAE73078A65515301C6E01CF36D7553FEFEE`
  - Neo `patched/promaid-1.3.3-neoforge-1.21.1.jar` 18,185,935 字节，
    SHA256 `7CFED0283E36969F61318724F880BDDC410E763AB12036E56A1F1FC403789AD7`

## 验收

- 状态：`未验收`。验收人 = 开发者；验法：
  1. 让女仆驾驶 AC-130H 在空中接敌，使用一键传送（战术地图/召回道具）把飞机搬到远处。
  2. 日志应出现 `检测到载具瞬移 N 格（一键传送/召回）→ 清空盘旋、航线、降落/助跑缓存…`。
  3. 飞机应在下一拍朝**新位置附近的敌人**重新起手，不再掉头飞回旧战场；
     不应再出现「固定翼降落完成·旧式…电量见底」反复刷屏后按旧跑道滑跑。
  4. 若瞬移距离小于 16 格，不会触发重对齐（这是刻意的：小位移由正常控制环自行收敛）。

## 边界（没动什么）

- 不改任何传送功能本身（排班表、TLM、卓越前线、其他模组）。
- 不改飞行控制的增益、盘旋半径、开火距离与炮弹追踪手感。
- 不改普通坐骑的「连人带坐骑一起搬」语义。
- 不改特殊载具「自动传送一律不生效，只有手动排班表传人」的既有口径。

## 台账

- 本轮不清债；未新增债。反射计数保持基线（`getMethod` Forge 205 / Neo 206）。
