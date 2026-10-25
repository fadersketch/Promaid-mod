# R1071 降落：默认走参考链路 + 必须从跑道入口捕获

## 排班

- **跑道**：A 修 bug（降落水平速度被压没，且新链路从未生效）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 查实为什么 R1067–R1070 没效果 | 日志 `固定翼降落·下降·旧式` → 开关 `realisticLanding=false` | ★ |
| 2 | 开关保留，默认改为开 | 两树 `.define("realisticLanding", true)`，面板/注释同步 | ★ |
| 3 | 落点就在脚下时也先飞进场 | 未捕获时 `before < 65` 一律走进场航线 | ★ |
| 4 | 捕获前不得下降 | 复飞/进场回退段把目标高度夹在进场高度以上 | ★ |
| 5 | 两树编译、14 护栏、四套测试、4 实例部署 | 全部 PASS / match=True | ★ |
| 6 | 交开发者验收 | 日志出现 `进场航线`→`进近`，全程带水平速度 | ☐ |

## 需求 / 来源

- 开发者反馈：「你这也不对啊，水平方向的速度还是被压没了。实在不行你直接把对面 mod 的降落完全照搬过来吧。不做改动。」
- 开发者推测：「因为当时测试的地区是超平坦地形，女仆直接选择了她脚下的位置作为降落点。所以水平方向完全没有速度。」
- 追加口径：「不移除开关。」

## 根因

1. **新链路根本没跑**：`COMBAT_RIDE_REALISTIC_LANDING` 默认 `false`，实机日志全是
   `固定翼降落·下降·旧式` / `固定翼降落完成·旧式`。R1067–R1070 的 Dubins 进场、
   恒速进近、姿态限幅都写在拟真分支里，开关关着时一行都不会执行。
2. **即使打开也会垂直下沉**：原对准判据只看横向/航向/高度。落点就在飞机正下方时
   `before≈0`、`cross≈0`，当场判「已对准」，直接按下滑道朝脚下地面下降，没有
   前向航程。参考实现 `RunwayApproach` 要求 `along > 65` 才切进 APPROACH，飞机
   必须先从入口飞进来；这一条此前漏了。

## 改法（两树 `MaidMountCompat` + 配置/面板）

- **开关保留，默认改为开**：`realisticLanding` 的 `.define(..., true)`；面板与
  `.comment()` 的「默认关」改为「默认开」，并注明关掉仍是上一个版本的旧链路。
- **`LandApproach.captured`**：进场航线飞到入口（距接地点 160～200 格、高 28 格）
  且航向/高度都对上时置 `true`；重新规划、超时、换跑道、清缓存时置 `false`。
- **入口窗口**：未捕获时要求 `65 ≤ before ≤ 260` 才准进下滑段；否则一律走
  Dubins 进场航线到入口。落点在脚下（`before≈0`）或从跑道后方来时都会先飞出去。
- **捕获前不下降**：复飞/进场回退段的下滑道目标夹到
  `tdY + AIRCRAFT_LAND_PATTERN_ALT` 以上，别在接地点头顶往下扎。
- 进近/拉平/接地段仍按 R1069–R1070：全程巡航速度，贴地后才由 `onGround`
  分支逐拍刹停。旧链路代码一行未动，开关关掉仍可回到旧行为。

## 触及文件

- `promaid_src/com/maidsmart/combat/MaidMountCompat.java`
- `promaid_src_neo/...` 同名文件的镜像
- `promaid_src{,neo}/com/maidsmart/config/MaidSmartConfig.java`（默认值/注释）
- `promaid_src{,neo}/com/maidsmart/config/PromaidConfigScreen.java`（面板文案）
- 重新生成 `ARCHITECTURE-SWITCHES.md`
- 无新配置键、无新 i18n 键、无新 mixin、无新类

## 验证

- Forge `compile_promaid`：`EXITCODE=0`；Neo `compile_neo`：`EXITCODE=0`。
- 14 护栏全部 `FAIL 0`。
- `test_server 1201` / `test_server neoforge1211`：PASS。
- `test_maid_load 1201` / `test_maid_load neoforge1211`：PASS。
- `deploy_all.py`：4 个实例全部 `match=True`。
- 玩家三份 `config/promaid-common.toml` 的 `realisticLanding` 已置 `true`
  （1.20.1 Forge / 1.21.1 Neo / 龙之冒险）；测试服务器两份同样置 `true`。
  **需重启游戏/服务器读取新 jar 与新配置。**
- 产物：
  - Forge `patched/promaid-1.3.3-forge-1.20.1.jar` 18,104,724 字节，
    SHA256 `D4F3B8775DEAFEBCA1873E0112B3C30139F975C8EC481DD7C4C9DE1930D0B3B9`
  - Neo `patched/promaid-1.3.3-neoforge-1.21.1.jar` 18,192,212 字节，
    SHA256 `AA1AE1DAB7A5BB264885F2109F08D52E40877221FA2E7D05931347AF56336F19`

## 验收

- 状态：`未验收`。验收人 = 开发者；验法：
  1. 重启后点 home 模式，日志应出现 `固定翼降落·进场航线`、`固定翼降落·进近`，
     不应再出现 `旧式`。
  2. 超平坦地形、落点就在脚下时，飞机应先朝跑道入口飞出去，再掉头进场，
     而不是原地垂直下沉。
  3. `进近` 与 `接地段` 的 `水平速度` 都应接近巡航值（约 0.80），触地后才逐拍下降。
  4. 若关掉「拟真降落」开关，行为应回到上一个版本的旧链路（开关仍可用）。

## 边界（没动什么）

- 旧式降落整段、TNT / 末地水晶 / 重生锚、炮艇开火链路均未改动。
- 未改进场入口距离（160～200 格）、入口高度（28 格）、下滑角 0.12、姿态限幅。

## 台账

- 未新增债。反射计数保持基线（`getMethod` Forge 205 / Neo 206）。
