# R1070 接地前恒速，接地后才减速

## 排班

- **跑道**：A 修 bug（降落拉平段仍在提前降速）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 定位拉平段的降速来源 | 拉平段只给 90% 巡航 + 怠速，仍会提前掉速 | ★ |
| 2 | 拉平段改为恒速 | 前进+冲刺位、推力下限、硬限速 = 巡航 | ★ |
| 3 | 接地后才减速 | onGround 分支仍 `aircraftGovernSpeedHard(0, DECEL)` | ★ |
| 4 | 两树编译、14 护栏、四套测试、4 实例部署 | 全部 PASS / match=True | ★ |
| 5 | 交开发者验收 | 全程匀速进场，触地后才收速 | ☐ |

## 需求 / 来源

- 开发者反馈：「降落过程中速度不变，只有在接地以后才开始减速。」

## 改法（两树 `MaidMountCompat`）

拉平段（`aircraftLandFinal` 的接地段）原来：

- `mProcessInput(0)`：不给前进/冲刺位；
- `mSetPower(怠速 0.08)`；
- `aircraftGovernSpeedHard(巡航 × 0.90)`。

现在改为：

- `mProcessInput(0x004 | 0x100)`：前进 + 冲刺位保持；
- `aircraftPowerAtLeast(AIRCRAFT_MIN_CRUISE_POWER)`：推力下限保持；
- `aircraftGovernSpeed(巡航)` + `aircraftGovernSpeedHard(巡航)`：速度不提前下降；
- 高度仍由 `aircraftHoldAltitudeLanding` 压到 `restY`（下沉率 ≤0.10），姿态限幅不变。

贴地后由 `aircraftHomeLanding` 的 `onGround` 分支：

- 油门归零；
- `aircraftGovernSpeedHard(0.0, AIRCRAFT_SPEED_DECEL)` 每拍 0.04 逐渐刹停；
- 清 crash 标记、清降落缓存。

进近段本来就已经是恒速（R1069 去掉了距离限速），本轮只补齐拉平段。

## 触及文件

- `promaid_src/com/maidsmart/combat/MaidMountCompat.java`
- `promaid_src_neo/...` 同名文件的镜像
- 无新配置项、无新 i18n、无新 mixin、无新类。

## 验证

- Forge `compile_promaid`：`EXITCODE=0`；Neo `compile_neo`：`EXITCODE=0`。
- 14 护栏全部 `FAIL 0`。
- `test_server 1201` / `test_server neoforge1211`：PASS。
- `test_maid_load 1201` / `test_maid_load neoforge1211`：PASS。
- `deploy_all.py`：4 个实例全部 `match=True`。
- 产物：
  - Forge `patched/promaid-1.3.3-forge-1.20.1.jar` 18,104,579 字节，
    SHA256 `ED120A704E0644E739BCECDF78B5F2F059DCCB2080096CDBB5B82A5F093ECFC1`
  - Neo `patched/promaid-1.3.3-neoforge-1.21.1.jar` 18,192,112 字节，
    SHA256 `9F7C32B88027A254B1574B0BE8846E02E8B2F6D071FC6E501EA9BA94E750B40A`

## 验收

- 状态：`未验收`。验收人 = 开发者；验法：
  1. 点 home 模式看进近到拉平：日志里 `进近` 与 `接地段` 的 `水平速度` 应基本一致。
  2. 触地后 `水平速度` 才开始逐拍下降，约 1 秒内停住。
  3. 目视：接地前是带速度平飞擦地，不是悬停或垂直下沉。
  4. 接地段 `离地` 应逐步降到 0～2 格后进入 onGround 分支。

## 边界（没动什么）

- 不进近航线/下滑道/选址/复飞阈值。
- 不改俯仰 5°/7°、坡度 ±10° 的姿态限幅。
- 不改 TNT / 末地水晶 / 重生锚。

## 台账

- 未新增债。反射计数保持基线（`getMethod` Forge 205 / Neo 206）。
