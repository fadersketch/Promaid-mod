# R1096 飞行烟花双粒子合并 / 等待再起飞不重复点火

## 排班

- **跑道**：C 功能（两树镜像）。
- **开工前读过**：`rounds/R1081`、`R1083`；`MaidVirtualFirework`、`MaidFlightCombatBehavior`
  （`tick` 相位派发 / `tickDiveBoost` / `tryLaunch`）、`MaidFlightFollowBehavior`、
  `SpearAirRaidBehavior`；实机日志 `logs/promaid.log`（UTF-8，2026-10-23 20:53）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 复现并定位双粒子来源 | 日志里"俯冲加速"与"放烟花起飞"同秒成对；两处都在同一 tick 调 `ignite` | ☑ |
| 1 | 等待再起飞时不让俯冲加速抢点火 | `tickDiveBoost` 在 `WAIT_LAUNCH` 期间直接返回 | ☑ |
| 2 | 相邻拍点火只喷一束 | `ignite` 在 5 tick 内的第二记只续推力窗、不喷粒子 | ☑ |
| 3 | 两树镜像 | Forge `MaidVirtualFirework` / `MaidFlightCombatBehavior` 同构 | ☑ |
| 4 | 编译与护栏 | 两树编译 `EXITCODE=0`；14 护栏 `FAIL 0` | ☑ |
| 5 | 测试与部署 | 四套专服测试 PASS；双 jar；4 实例 `match=True` | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

1. 女仆释放烟花时会出现两次粒子效果，烟花粒子互相重叠；旧版本没有这个问题。

## 评估

**双粒子的真实来源不是粒子函数本身，而是同一拍点了两枚烟花。** 近战空袭的相位派发顺序是
`tickDiveBoost(...)`（俯冲段冲刺加速）在前、`if (WAIT_LAUNCH.contains(id) && tryLaunch(...))`
（猛击收尾后的"再次放烟花起飞"）在后，两者之间没有任何互斥。`tryLaunch` 会查
`FIREWORK_READY` 这张"这一口推进"的共享冷却表，但 `tickDiveBoost` 的烟花分支既不查也不写
——于是"等待再起飞"的那一拍，俯冲加速先烧一枚点一束，紧接着起飞再烧一枚点一束，两束 10 粒
`FIREWORK` 叠在同一坐标，发射音也叠成两声。

实机日志（`promaid.log`，2026-10-23）成对出现，正是这个形状：

```
20:53:40 [空袭·俯冲] 大正女仆酒狐 俯冲加速（烟花×1.4，距敌 37.5 格）
20:53:40 [飞行作战] 大正女仆酒狐 放烟花起飞
20:54:00 [空袭·俯冲] 大正女仆酒狐 俯冲加速（烟花×1.4，距敌 33.4 格）
20:54:00 [飞行作战] 大正女仆酒狐 放烟花起飞
```

旧版看不见这个问题，是因为旧版常规鞘翅根本没有烟花粒子（`particles` 只给壮丽/混沌鞘翅发龙息），
R1081/R1083 补上"点火一束 + 推进尾迹"之后，同一拍的两枚才显形。

## 改动

- `MaidFlightCombatBehavior.tickDiveBoost`（两树）：`WAIT_LAUNCH` 期间直接返回。那一拍她还没
  进入俯冲，推力会由紧随其后的 `tryLaunch` 给出；顺带省下被白烧的那一枚烟花。
- `MaidVirtualFirework.ignite`（两树）：新增 `MERGE_TICKS = 5`。上一记点火还不到 5 tick 时，
  第二记只刷新推力窗（保留已走的拍数与位移采样），不再喷第二束粒子。

## 验证

- Neo 编译：`NEO_COMPILE_OK`；Forge 编译：`FORGE_COMPILE_OK`。
- 14 项护栏：`FAIL 0 / WARN 0`（`check_comment_style` → `RESULT: PASS`）。
- 专服回归：`test_server.py neoforge1211` PASS、`test_maid_load.py neoforge1211` PASS、
  `test_server.py 1201` PASS、`test_maid_load.py 1201` PASS。
- Neo jar：18,210,447 字节，SHA256
  `0C84BF5B978A1ACFBBC46F7D1D66DA32A6A4CB922ADCB6420C8D525C819CFCF2`。
- Forge jar：18,149,183 字节，SHA256
  `4C462888975E4C9434D3FCF928D463D633A617DA530B189924C6CFFE39612FB4`。
- 已部署 4 实例，源包/目标包字节数一致（`match=True`）：1.21.1 Neo、1.20.1 Forge、
  龙之冒险整合包、pack1201 专服。

## 边界

- 合并窗口只吞"5 tick 内的第二束粒子"，推力窗与力度照旧（第二记的 scale 仍生效）。
- `WAIT_LAUNCH` 期间不点俯冲那一口，只影响"猛击收尾等重新起飞"这一小段；正常俯冲段的
  加速节奏（`diveBoostInterval` 默认 30 tick）不变。
- 长矛水平突袭那一枚（`launchSpearPassRocket`）本来就在 `tickSpearPass` 里提前 `return`，
  与通用俯冲加速不在同一 tick，不受本次改动影响。
- 飞行跟随（`MaidFlightFollowBehavior`）不经过这两条路径，补推节奏不变。

## 验收

1. **起飞/补推**：近战空袭一轮里只看到一束烟花粒子、只听到一声发射音；日志不再出现同一秒
   成对的"俯冲加速"与"放烟花起飞"。
2. **燃料**：等待再起飞的那一拍不再白烧第二枚烟花（背包消耗速度应明显回落）。
3. **飞行手感**：俯冲段的加速频率与力度不变，长距俯冲仍按 `diveBoostInterval` 补推。
4. **骑乘/长矛**：乘骑链路、长矛水平突袭的观感与耗料与 R1095 一致。
