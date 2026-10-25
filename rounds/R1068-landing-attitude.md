# R1068 降落姿态对齐：俯仰 ±、滚转 ±10°、接地保速

## 排班

- **跑道**：A 修 bug / 借鉴（降落姿势不自然）。
- **参考物**：`maid-frontline-1.20.1-0.1.3.jar` 的 `FlightControl.plane` / `landing`。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 对比参考与本模组的姿态通道 | 参考进近/接地把 pitch 夹 -5°～+7°、bank ±10° | ★ |
| 2 | 俯仰：新增降落限幅版本 | 抬头 ≤5°、低头 ≤7°、鼠标通道 0.45 | ★ |
| 3 | 滚转：坡度 ∝ 误差、上限 10° | 不再“误差过 25° 就顶满 45°” | ★ |
| 4 | 接地段：怠速 + 保拉平速度 | 不再 6 格高度熄火、速度不一路收到 0 | ★ |
| 5 | 两树编译、14 护栏、四套测试、4 实例部署 | 全部 PASS / match=True | ★ |
| 6 | 交开发者验收 | 落地不再“压机头/急转/突然栽下去” | ☐ |

## 需求 / 来源

- 开发者反馈：「降落姿势感觉没他的那么自然」（对比 maid-frontline）。

## 参考实现的姿态口径

`FlightControl.plane` 里对 `Stage.APPROACH` / `Stage.LAND`：

| 通道 | 参考值 |
|---|---|
| 俯仰 | `pitch = clamp(pitch, -5, 7)`（负 = 抬头，即抬头 ≤5°、低头 ≤7°） |
| 滚转 | `bank = clamp(yawError * 0.7, -10, 10)`，坡度 ∝ 航向误差 |
| 速度 | APPROACH ≈1.65；LAND ≈1.55/lift；`down` 输入只在 LAND 或超速时踩 |
| 油门 | 着陆段不熄火，靠 down 输入与速度环减速 |

本模组原来的问题：

1. `aircraftHoldAltitude` 的俯仰限幅是巡航口径（抬头 30° / 低头 12°），进近与接地也照用。
   下滑道需要下降时它会把机头压到 12°，看起来像俯冲。
2. 进近段只用左右位（误差 >25° 时给满 45° 坡度），没有比例滚转，转向显得生硬。
3. 接地段在离地 6 格就把油门归零、水平速度一路收到 0：升力/舵效一起消失，最后一段
   姿态容易“栽下去”。

## 改法（两树 `MaidMountCompat`）

1. `aircraftHoldAltitude` 拆出带限幅参数的内部版本；正常飞行仍是 30°/12°，降落走
   `aircraftHoldAltitudeLanding`：抬头 5°、低头 7°、鼠标通道上限 0.45。
2. 新增 `aircraftBankLanding`：`bank = clamp(航向误差 × 0.7, ±10°)`，按
   `AIRCRAFT_BANK_RATE`（2°/拍）平滑靠近；进近、没对准的兜底段、进场航线三处都用它。
3. 接地段：油门从 0 改为怠速 `AIRCRAFT_LAND_IDLE_POWER`（0.08）；速度只收到
   `巡航 × 0.55`（不是 0），真正贴地后由 `onGround` 分支归零并停住。
4. 进场航线的目标高度仍带地形净空，只换了姿态控制律。

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
  - Forge `patched/promaid-1.3.3-forge-1.20.1.jar` 18,104,644 字节，
    SHA256 `F7DDD2A785D4367CE2A73877EEBE1B0F7F8B80FC71D6A07EECDC216FBC466791`
  - Neo `patched/promaid-1.3.3-neoforge-1.21.1.jar` 18,192,174 字节，
    SHA256 `ADFC771F3E815FEAF6ED3EF4B156BDC2DD37ADFFD72230FEBA1605374AC76736`

## 验收

- 状态：`未验收`。验收人 = 开发者；验法：
  1. 点 home 模式看进近：机头只做小幅向下修正（目测不超过 7°），转弯坡度不超过 10°。
  2. 拉平段：机头应轻微抬起/保持，不再出现“速度掉光后垂直栽下”。
  3. 贴地后应立即停住（onGround 分支仍归零油门、限速到 0）。
  4. 日志搜 `固定翼降落·接地段`：`水平速度` 应约为巡航的一半，而不是 0.00。

## 边界（没动什么）

- 不改进场航线几何、下滑道斜率（0.12）、选址与复飞阈值。
- 不改巡航空战、炮艇盘旋、其他载具的飞行姿态。
- 不改 TNT / 末地水晶 / 重生锚。

## 台账

- 未新增债。反射计数保持基线（`getMethod` Forge 205 / Neo 206）。
- 参考模组仅用于阅读，不引入依赖。
