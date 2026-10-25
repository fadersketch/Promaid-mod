# R1069 降落接地前保留前向速度

## 排班

- **跑道**：A 修 bug（降落像“垂直慢慢掉下来”，没有前向速度）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 找“垂直下沉”的来源 | 进近段按 `before/120` 把水平速度收到接近 0 | ★ |
| 2 | 撤掉距离限速 | 进近全程保持着陆速度 | ★ |
| 3 | 接地段速度提高 | 从巡航 55% → 90%，贴地后由 onGround 刹停 | ★ |
| 4 | 两树编译、14 护栏、四套测试、4 实例部署 | 全部 PASS / match=True | ★ |
| 5 | 交开发者验收 | 降落是带前向速度擦地，不是原地垂直下沉 | ☐ |

## 需求 / 来源

- 开发者反馈：「真正的降落不是这样的，应该会有一个向前的速度的。而现在这样更像是垂直慢慢掉下来。」

## 原因

R1068 之前为“轻轻接地”加了一条距离限速：

```text
限速 = 巡航速度 × clamp(距接地点 / 120, 0, 1)
```

它从 120 格就开始减速：60 格时只剩一半、30 格时只剩四分之一、12 格时接近 0。
最后一段水平速度几乎为零，只剩 `aircraftHoldAltitude` 的竖直下沉——看起来就是“垂直慢慢掉下来”。
参考 maid-frontline 的进近一直保持着陆速度（`targetSpeed ≈ 1.55～1.65`），只有接地/超速时
才用减速输入，贴地后才真正收速。

## 改法（两树 `MaidMountCompat`）

1. 进近段去掉距离限速，`landSpeedCap = AIRCRAFT_CRUISE_SPEED`（全程保持着陆速度）。
2. 接地段限速从 `巡航 × 0.55` 提到 `巡航 × 0.90`；贴地后仍由 `onGround` 分支
   `aircraftGovernSpeedHard(0, DECEL)` 逐渐刹停。
3. 删除不再使用的 `AIRCRAFT_LAND_SPEED_FADE_D` 常量。
4. R1068 的姿态限幅（抬 5°/低 7°、坡度 ±10°、怠速）保持不变。

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
  - Forge `patched/promaid-1.3.3-forge-1.20.1.jar` 18,104,611 字节，
    SHA256 `A216938E0A5BF15E65E50E8B50013D83F105BA681179EAE039327515C51569B3`
  - Neo `patched/promaid-1.3.3-neoforge-1.21.1.jar` 18,192,141 字节，
    SHA256 `6125FBCFD87F2BDC72608E6E27A5E9280D9B83F9F8BE2EE36CE280B94E91ECCD`

## 验收

- 状态：`未验收`。验收人 = 开发者；验法：
  1. 点 home 模式看最后 100 格：应保持明显的前向速度，沿下滑道斜着接近跑道。
  2. 日志搜 `固定翼降落·进近`：`限速=` 应始终等于巡航速度，不再随距离变小。
  3. `接地段`：`水平速度` 应接近巡航速度的 90%，贴地后才开始下降。
  4. 接地后应逐渐停住，不应继续滑行很远（onGround 分支仍限速到 0）。

## 边界（没动什么）

- 不改进场航线、下滑道斜率 0.12、选址与复飞阈值。
- 不改 R1068 的俯仰/滚转限幅与怠速。
- 不改 TNT / 末地水晶 / 重生锚。

## 台账

- 未新增债。反射计数保持基线（`getMethod` Forge 205 / Neo 206）。
