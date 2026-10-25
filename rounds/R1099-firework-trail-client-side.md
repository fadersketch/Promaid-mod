# R1099 烟花尾迹改由客户端在女仆渲染坐标上发

## 排班

- **跑道**：C 功能（两树镜像）。
- **开工前读过**：`rounds/R1097`、`R1098`；`MaidVirtualFirework`、`FlightFireworkPose`、
  `MaidCombatTacticsBehavior` / `MaidMountCompat`（客户端读写 `xo/yo/zo` 的既有先例）、
  `MaidSeatNetworking`（S2C 骨架）、`GunnerTetherClient`（`RenderLevelStageEvent` + `ensureRegistered`）；
  反编译的 `FireworkRocketEntity`（`tick` 的客户端粒子分支）、`Entity`（`xo/yo/zo` 与 `xOld/yOld/zOld`）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 定位"粒子比女仆先飞"的根因 | 服务端粒子用服务端坐标，客户端画她用插值坐标 | ☑ |
| 1 | 新增 S2C 窗口包（两树） | `FireworkTrailNetworking`：`maidId + ticks`，追踪她的客户端 | ☑ |
| 2 | 客户端在渲染坐标上发粒子 | `FireworkTrailClient`：`AFTER_ENTITIES` + 同一个 partialTick | ☑ |
| 3 | 服务端不再发粒子 | `MaidVirtualFirework` 删除 `sendParticles` 尾迹 | ☑ |
| 4 | 编译与护栏 | 两树编译 `EXITCODE=0`；14 护栏 `FAIL 0` | ☑ |
| 5 | 测试与部署 | 四套专服测试 PASS；双 jar；4 实例 `match=True` | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

1. "烟花粒子的出现仍比女仆飞行的位置要先一步……理论上粒子出现的位置应该是在女仆的身体部位。
   你可以自己对比一下老版本的渲染。老版本的重锤空袭就没这个问题。"

## 评估

R1097/R1098 已经把密度和"离地才发"对齐了原版，但**坐标链路**仍是服务端：`ServerLevel.sendParticles`
发的是服务端这一拍的她，而客户端画她用的是 `xo → x` 的逐帧插值（外加位置包限流与网络延迟）。
她滑翔时一拍一格多，两条坐标差的就是"粒子先飞出去、人还在后面"。

原版挂载烟花没有这一步：`FireworkRocketEntity.tick` 的粒子分支只在客户端执行，坐标取火箭自己的
（火箭每拍被摆到骑手身上），所以粒子一定长在人身上。本轮照这个口径把尾迹整体搬到客户端。

## 改动

- **新增** `com/maidsmart/combat/FireworkTrailNetworking`（两树）：S2C 单包 `maidId + ticks`
  （`ticks <= 0` = 收掉）。Neo 走 `CustomPacketPayload` + `PayloadRegistrar` + `sendToPlayersTrackingEntityAndSelf`；
  Forge 走 `SimpleChannel` + `PacketDistributor.TRACKING_ENTITY_AND_SELF`，在 `ProMaidMod` 构造期注册。
- **新增** `com/maidsmart/client/FireworkTrailClient`（两树）：记 `maidId → 结束 gameTime`；
  在 `RenderLevelStageEvent.Stage.AFTER_ENTITIES` 用**本帧的 partialTick** 取她的插值位置
  （`xo + (x - xo) * partial`，身体中部 `+ bbHeight * 0.5`），每游戏拍一粒：
  速度 `(gauss×0.05, -(y - yo)×0.5, gauss×0.05)`；壮丽/混沌鞘翅换 `DRAGON_BREATH`（×0.02）。
  同一拍只发一次；`isFallFlying()` 且 `!onGround()` 才发；退出世界清表。
- **改动** `MaidVirtualFirework`（两树）：删除服务端 `particles(...)` 与其 import；
  `ignite` 改为发窗口包；停滞收窗与 `clear()` 发 `ticks = 0` 收掉客户端尾迹。推力计算一字未动。
- **改动** `PromaidClientSetup`（两树）：`LoggingOut` 时顺带清空尾迹表。

## 验证

- Neo 编译：`NEO_OK`；Forge 编译：`FORGE_OK`。
- 14 项护栏：`FAIL 0 / WARN 0`（`check_arch` 已把两个新类登记进
  `promaid.combat.flight-raid` 并重跑 `build_arch.py`）。
- 专服回归：`test_server.py neoforge1211` PASS、`test_maid_load.py neoforge1211` PASS、
  `test_server.py 1201` PASS、`test_maid_load.py 1201` PASS。
- Neo jar：18,215,601 字节，SHA256
  `80F0CC7D31521AAB6A9C7510B62FF0490667D9989C4A1C0BCC87947DF21520E5`。
- Forge jar：18,154,204 字节，SHA256
  `22A53F1A18805ECAD5251786613FCF1248955E422C96194E708FFBABB1A7E546`。
- 已部署 4 实例，源包/目标包字节数一致（`match=True`）。

## 边界

- 尾迹只在"点火窗口内 + 正在滑翔 + 已离地"时出现；窗口到期、停滞收窗、`clear()` 都会收掉。
- 服务端仍是推力的唯一权威；客户端只画粒子，不参与任何力学或耗料判定。
- 服务端不再发任何烟花/龙息粒子，画面里不会出现两套坐标各画一条。
- 观察者必须正在追踪她（包只发给追踪者）；远处未加载的格子本来也看不到粒子。

## 验收

1. **重锤空袭起飞**：粒子从她**身体中部**冒出，跟着她走，不再出现在飞行方向的前方。
2. **长矛空袭**：同上；"起飞 + 冲锋前补一记"仍是同一条连续细流。
3. **远程空袭**：与改动前一致（本来就在空中补推）。
4. **落地/收翅**：尾迹立即停，不在原地留烟。
