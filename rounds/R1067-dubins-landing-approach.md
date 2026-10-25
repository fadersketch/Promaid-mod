# R1067 移植 maid-frontline 的 Dubins 进场航线

## 排班

- **跑道**：A 修 bug / 借鉴（固定翼降落不够好）。
- **参考物**：开发者提供的 `maid-frontline-1.20.1-0.1.3.jar`（CFR 反编译）。
- **开工前读过**：参考模组的 `LandingPlan` / `LandingSurvey` / `RunwayApproach` /
  `DubinsPath` / `FlightControl.landing`；本模组 `aircraftHomeLanding` /
  `aircraftLandFinal` / `aircraftLandPlan`。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 反编译参考模组，定位降落主链 | RunwayApproach + Stage.RETURN/APPROACH/LAND | ★ |
| 2 | 移植 Dubins 曲线（四种解取最短） | 纯数学，无新顶级类、无 Mod 依赖 | ★ |
| 3 | 移植入口航线规划与跟随 | 入口 = 接地点外 160～200 格、高 28 格 | ★ |
| 4 | 对接现有对准/下滑段 | 到入口后交回原链路，不重复接管 | ★ |
| 5 | 下滑道 0.18 → 0.12、速度收油距离独立 | 与参考的进近剖面一致 | ★ |
| 6 | 两树编译、14 护栏、四套测试、4 实例部署 | 全部 PASS / match=True | ★ |
| 7 | 交开发者验收 | 降落不再“绕大圈/切不进走廊/压过头” | ☐ |

## 需求 / 来源

- 开发者反馈：「现在就是降落做的不好。套用这个的降落」，并附
  `maid-frontline-1.20.1-0.1.3.jar`。

## 参考实现的降落主链

1. **选址**：`LandingPlan.find` 以主人为圆心按 24 格环向外找，8 个朝向逐条试；
   `support()` 沿整条跑道与横向多列检查地面起伏、悬空、水/岩浆；`LandingSurvey`
   给跑道与进场走廊加区块票据，先把地形加载出来再判断。
2. **进场**：`RunwayApproach.follow` 用 **Dubins 曲线**从当前位姿规划一条到跑道入口
   （接地点外 `max(160, min(200, turnRadius*1.5))` 格、高 28 格）的路径；富余高度先
   绕 0～3 圈；前 10 个航点要求所在区块已加载；沿线高度线性下降，并按地形净空抬高。
3. **分段**：RETURN（走 Dubins）→ APPROACH（对准后压下滑道）→ LAND（`along < 55`
   切接地/拉平）；下滑道目标 = `接地点 + max(0.3, 沿跑道距离 × 0.12)`；偏航/横向/
   过站任一超限 → 复飞重新进近。
4. **姿态**：进近段滚转限 ±10°、目标速度约 1.55～1.65，接地段收油、压平。

## 本模组原来的问题

`aircraftLandFinal` 没对准时是“朝跑道轴线前方一点做纯追踪”，没有考虑固定翼的最小
转弯半径。实机里她经常从侧后方切场：要么绕着跑道转大圈、要么一直压不进 14 格走廊，
最后只能等两分钟拉黑换跑道。参考实现的 Dubins 路径正好解决这一段：它先算出一条
**符合转弯半径、末端航向与跑道一致**的曲线，再沿曲线飞。

## 改法（两树 `MaidMountCompat`）

1. 新增嵌套 `LandApproach` 状态（跑道键、规划时间、重试、失败数、航点表）与
   `AIRCRAFT_LAND_APPROACH` 表；`resetAircraftLanding` / `endAircraftLanding` /
   `aircraftLandReject` 一并清。
2. 新增 `planLandingApproach` / `dubinsPoints` / `dubinsAppend` / `dubinsPositive`：
   四种 Dubins 解（LSL/RSR/LSR/RSL）取最短，前 10 个航点所在区块未加载就换一条；
   高度从当前高度线性降到入口高度，逐点按 `地面 + 地形净空 + 4` 抬高；富余高度先绕圈。
3. 新增 `aircraftFollowApproach`：沿航点前视飞行（前视量随速度），到入口 25 格内且
   航向差 < 20°、高度差 < 12 格 → 清空航线，交回原来的对准/下滑段；同一条跑道 90 秒
   进不了入口 → 拉黑换一条。
4. `aircraftLandFinal` 的“没对准”分支先尝试进场航线；规划失败/等待时退回旧口径，
   保持原来的兜底能力。
5. 进近剖面贴近参考：下滑道斜率 0.18 → **0.12**，参考距离 90 → **220** 格；速度收油
   单独用 120 格，避免入口处就掉速掉舵效。

## 触及文件

- `promaid_src/com/maidsmart/combat/MaidMountCompat.java`
- `promaid_src_neo/...` 同名文件的镜像
- 无新配置项、无新 i18n、无新 mixin、无新顶级 Java 类（Dubins 是嵌套方法）。

## 验证

- Forge `compile_promaid`：`EXITCODE=0`；Neo `compile_neo`：`EXITCODE=0`。
- 14 护栏全部 `FAIL 0`。
- `test_server 1201` / `test_server neoforge1211`：PASS。
- `test_maid_load 1201` / `test_maid_load neoforge1211`：PASS。
- `deploy_all.py`：4 个实例全部 `match=True`。
- 产物：
  - Forge `patched/promaid-1.3.3-forge-1.20.1.jar` 18,104,444 字节，
    SHA256 `795512042F7B4DB21AD408DAD366864C19B8F916EBB071785B82D0D126C03FD5`
  - Neo `patched/promaid-1.3.3-neoforge-1.21.1.jar` 18,191,997 字节，
    SHA256 `9505E991BC70C0F8A0E5E1260300D5D08209EE6D2BDDA4223302BCAD408BE981`

## 验收

- 状态：`未验收`。验收人 = 开发者；验法：
  1. 让女仆驾驶固定翼，在离主人 200～600 格、不同朝向的位置点 home 模式降落。
  2. 日志搜 `固定翼降落·进场航线`：应出现 `入口=(x, 高度, z) 航点数=… 转弯半径=…格`，
     随后是 `进近` / `接地段`，不应再反复出现 `换跑道`。
  3. 目视：飞机应先绕到跑道入口、对正后再压下滑道，不再从侧面斜切或绕着跑道兜圈。
  4. 落点应仍在主人附近（选址逻辑未改，只改了进场方式）。

## 边界（没动什么）

- 跑道选址仍是本模组原口径（主人优先、48 格圆、八向条带），没有照搬参考的 576 格环扫。
- 没有移植参考的区块票据（进了本模组会引入新的区块加载语义）；改为“前 10 个航点
  已加载才用这条路径”，未加载就等/换解。
- 不改直升机/飞艇降落、不改起飞、不改战斗盘旋与炮弹追踪。
- 不改 TNT / 末地水晶 / 重生锚。

## 台账

- 未新增债。反射计数保持基线（`getMethod` Forge 205 / Neo 206）。
- 参考模组仅用于阅读，不引入任何依赖、不复制其类到构建产物。
