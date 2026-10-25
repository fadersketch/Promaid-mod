# R1073 降落每拍强制下压 1 格直至接地

## 排班

- **跑道**：A 修 bug（进近段高度压不下来 / 到 65 格又复飞）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 读日志确认卡点 | `进近` 竖直速度长期 0.00～-0.07，到 `距接地点=83` 后重新进场 | ★ |
| 2 | 补 captured | 进入对齐下滑段即记已捕获，65 格不再触发复飞 | ★ |
| 3 | 强制下压 | 非接地的每一拍 `move(SELF, (0,-1,0))` | ★ |
| 4 | 两树编译、14 护栏、四套测试、4 实例部署 | 全部 PASS / match=True | ★ |
| 5 | 交开发者验收 | 高度每拍下降，直至接地 | ☐ |

## 需求 / 来源

- 开发者口径：「高度压不下来，现在保持运动逻辑不变，每tick强制压1竖直高度直至接地」。

## 日志实证

- `进近` 段从 `离地=24.72` 开始，竖直速度先是 `0.00`，后来只有 `-0.02 ~ -0.07`；
- 到 `距接地点=83格 / 离地=10.90格` 后突然重新 `进场航线`——因为此前从未
  `captured`，`before < 65` 触发了 R1071 的入口窗口复飞；
- 根因是俯仰/升力压不过 0.8 巡航速度带来的升力（`aircraftHoldAltitude` 的下沉率
  被引擎每拍重算的升力抵消），单靠姿态通道压不下去。

## 改法（两树 `MaidMountCompat`）

- 新增 `aircraftForceDescent(mount)`：`move(MoverType.SELF, (0, -1, 0))`，
  每拍直接下压 1 格；**水平位移、航向、速度、姿态逻辑一概不动**。
- 在 `aircraftHomeLanding` 的外层 `try` 加 `finally`：只要还没接地，每拍都调用一次
  `aircraftForceDescent`；接地后由原有 `onGround` 分支停住。
- 进入对齐下滑段时补写 `la.captured = true`：已经从入口/下滑窗口进场后，不再因为
  `before < 65` 触发复飞。
- 旧式降落链路（开关关掉时）不受影响：`realisticLanding()` 的早退发生在 `try` 之前，
  `finally` 不会执行。

## 触及文件

- `promaid_src/com/maidsmart/combat/MaidMountCompat.java`
- `promaid_src_neo/...` 同名文件镜像
- 无新配置项、无新 i18n、无新 mixin、无新类

## 验证

- Forge `compile_promaid`：`EXITCODE=0`；Neo `compile_neo`：`EXITCODE=0`。
- 14 护栏全部 `FAIL 0`。
- `test_server 1201` / `test_server neoforge1211`：PASS。
- `test_maid_load 1201` / `test_maid_load neoforge1211`：PASS。
- `deploy_all.py`：4 个实例全部 `match=True`。
- 产物：
  - Forge `patched/promaid-1.3.3-forge-1.20.1.jar` 18,105,197 字节，
    SHA256 `9466F5B24ED1160AF399763A859E083E53FDEEE7AE6C70F12B55A0C237B1FEAB`
  - Neo `patched/promaid-1.3.3-neoforge-1.21.1.jar` 18,192,628 字节，
    SHA256 `D69656B4D985CEADA4B076A41053F25C1729D69A344B7570A5F047A1A6ED31C1`

## 验收

- 状态：`未验收`。验收人 = 开发者；验法：
  1. 重启后点 home 模式，日志 `进近` 的 `离地` 应每拍明显下降（约 1 格/拍），
     不再长期停在 24 格。
  2. 到 65 格内不再重新出现 `进场航线`。
  3. 触地后进入 `固定翼降落·完成` 并停住。

## 边界 / 已知取舍

- 这是**强制位移兜底**，不再等俯仰通道把高度压下来；下降会比自然下滑道陡。
- 水平运动仍按原进场航线/速度环执行，没有改航向、速度、选址、入口参数。
- 如果之后要"强制下降但仍在接地点落地"，需要把强制段限制在
  `before <= AIRCRAFT_LAND_TOUCH_D` 或 `aboveRunway <= FLARE_ALT`，本轮按开发者
  口径全降落段执行。

## 台账

- 未新增债。反射计数保持基线（`getMethod` Forge 205 / Neo 206）。
