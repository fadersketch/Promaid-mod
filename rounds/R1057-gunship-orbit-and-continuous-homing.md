# R1057 炮艇盘旋轨迹稳定 + 炮弹持续追踪

## 排班（开工前先填，硬门槛 —— `WORKFLOW.md` §1）

- **跑道**：A 修 bug —— 两树镜像（1.20.1 Forge + 1.21.1 NeoForge）。
- **开工前读过**：`rounds/R1054` 固定翼接敌几何；`rounds/R1056` 末制导；
  `MaidAirCombat.gunshipOrbitTarget` / `aircraftCruise` / `aircraftBank`；
  `MaidShellHoming.steerOne`；Neo 实例 `latest.log`（2026-10-20 12:31~12:37，AC-130H 实战段）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 炮艇盘旋点改固定角度 | 旋向按遭遇锁定；前视角按速度反推，半径收敛 84 格 | ★ |
| 2 | 炮艇滚转改比例控制 | 坡度 ∝ 航向误差，不再 45°/0 开关；其他固定翼不动 | ★ |
| 3 | 末制导取消 3 秒时限 | 普通弹 60 → 600 拍（≈30 秒）；提前量上限 40 → 120 拍 | ★ |
| 4 | 两树编译与护栏 | 两树 `EXITCODE=0`；14 护栏 `FAIL 0` | ★ |
| 5 | 双端回归 | `test_server` / `test_maid_load` 两套 PASS | ★ |
| 6 | 打包装机 | 双 jar；4 个目标 `match=True` | ★ |
| 7 | 交开发者验收 | 盘旋不再剪刀形；300+ 格外炮弹仍持续拐向目标 | ☐ |

## 需求 / 来源

- 开发者反馈：“接敌时女仆的飞行轨迹发生了诡异现象，而且离得远了以后子弹也做不到追踪效果（建议改为持续追踪）。”
- 本轮只修这两件事：炮艇（AC-130H 这类侧射固定翼）的盘旋姿态，以及普通炮弹的追踪时限；
  不改其他载具、不改前射固定翼的通场几何、不改导弹的 0.2 秒锁定。

## 评估

- **轨迹证据**（Neo 实例 12:31~12:37，AC-130H）：
  - `滚转=45 → -45 → 45` 每 5 秒档翻一次，`鼠标X=+12 → -12` 同步翻；
  - `高差=+64 → -59 → +41` 上下荡，`俯仰` 多次顶到 `-25°`（最大抬头）再回 `-2°`；
  - 同一段 `距目标=108 格` 基本稳定，说明不是追不上目标，而是**姿态控制在来回救**。
- **根因一（盘旋点）**：旧 `gunshipOrbitTarget` 每拍用“当前位置 + 当前切向 × 60 格”重算胡萝卜。
  半径一有误差（或目标切换），切向整根翻过去，目标点跳到机身另一侧 → 滚转瞬间从 +45 甩到 -45。
- **根因二（滚转）**：`aircraftBank` 是开关式——航向误差 >25° 就顶满 45°，否则回正。
  84 格圈在 0.8~1.3 格/拍只需约 15~25° 坡度；长期 45° 把升力竖直分量砍掉近三成，
  掉高后只能靠 25° 抬头硬顶，形成“掉高—拉头—再掉高”的锯齿。
- **根因三（追踪）**：`MaidShellHoming.MAX_TICKS_PER_SHELL=60`（≈3 秒）。
  384 格内远距离弹的飞行时间常超过 3 秒，半路就撒手；同时 `steerOne` 的提前量上限只有 40 拍，
  300+ 格外瞄准点落在目标身后。

## 改法

### 1. 炮艇盘旋点（两树 `MaidAirCombat`）

- 新增 `GUNSHIP_ORBIT_SIGN`（按女仆记旋向）：首次按“径向 × 速度”的符号定，速度太小用 UUID 稳定选边；
  **同一场遭遇内不翻转**，换目标也保持。
- 胡萝卜改成“目标为圆心、半径 84 格、当前方位沿固定旋向再推一个固定**角度**”的圆上点。
- 前视角由 `gunshipLeadDeg(speed)` 反推：
  - 引擎转向 `ω(度/拍) = 0.9 × (0.3 + 3.2×|roll|/90)`，绕半径 R 需 `ω = v/R`；
  - 纯追踪稳态偏角 = 前视角的一半，滚转 = 增益 × 偏角 ⇒ 前视角 = 2×所需坡度/增益；
  - 速度 0.8~1.3 时前视角自动落在约 14°~32°，圈都收敛在 84 格。
- `clear()` 一并清 `GUNSHIP_ORBIT_SIGN`。

### 2. 炮艇滚转（两树 `MaidMountCompat`）

- 新增 `AIRCRAFT_BANK_PER_DEG = 1.2`（包内可见，供 `MaidAirCombat` 反推前视角）。
- `aircraftBank` 增加重载：`proportional=true` 时 `滚转 = 1.2 × 航向误差`，限幅 ±45°，
  误差 <2° 回正；只有 `isGunship(mount)` 走这条。
- 前射固定翼仍走原来的开关式（`AIRCRAFT_BANK_MAX`/`AIRCRAFT_BANK_RATE` 未改）。

### 3. 持续追踪（两树 `MaidShellHoming`）

- `MAX_TICKS_PER_SHELL` 60 → 600（≈30 秒）。这不是新的“追踪上限”，而是防绕圈兜底；
  384 格内任何弹的飞行时间都远小于它，实际放手仍是命中/落地/被删/目标死亡。
- 提前量 `Math.min(remain, 40)` → `Math.min(remain, 120)`：远距离剩余飞行时间超过 40 拍时，
  旧上限会把瞄准点留在目标身后。
- 制导弹 `MISSILE_LOCK_TICKS=4`（0.2 秒）不变。

## 触及文件

- `promaid_src/com/maidsmart/combat/MaidAirCombat.java`
- `promaid_src/com/maidsmart/combat/MaidMountCompat.java`
- `promaid_src/com/maidsmart/combat/MaidShellHoming.java`
- `promaid_src_neo/com/maidsmart/combat/MaidAirCombat.java`
- `promaid_src_neo/com/maidsmart/combat/MaidMountCompat.java`
- `promaid_src_neo/com/maidsmart/combat/MaidShellHoming.java`
- 无新配置项、无新 i18n、无新 mixin、无新反射探针。

## 验证

- Forge `compile_promaid`：`EXITCODE=0`；Neo `compile_neo`：`EXITCODE=0`。
- 14 护栏全部 `FAIL 0`。
- `test_server 1201`：PASS；`test_server neoforge1211`：PASS。
- `test_maid_load 1201`：PASS；`test_maid_load neoforge1211`：PASS。
- `deploy_all.py`：4 个实例全部 `match=True`。
- 产物：
  - Forge `patched/promaid-1.3.3-forge-1.20.1.jar` 18,091,342 字节，
    SHA256 `4CD21318EE1B4889EDBF5F12B305F04F952CF5B09EEDC357BE8186CBDDCFB5CA`
  - Neo `patched/promaid-1.3.3-neoforge-1.21.1.jar` 18,178,678 字节，
    SHA256 `7C19FBAA57F60F00C9F7CAD2656AFBD32ABBBB4AFC15786B012224B28FA9C3BF`

## 验收

- 状态：`未验收`。验收人 = 开发者；验法：
  1. AC-130H 接敌盘旋：日志搜「固定翼巡航档」，`滚转` 应在 ±15~25° 内平稳变化，
     不再 5 秒一次 `+45 → -45`；`高差` 应收敛在目标上方约 165 格附近，不再 ±60 上下荡。
  2. 300+ 格对移动目标开火：日志搜「模组坐骑·制导」，应持续看到 `纠向 … 距离=3xx格` 多拍，
     而不是只出现一次就消失。
  3. 近距/静止目标：命中正常，不出现绕圈弹。
  4. 其他载具与前射固定翼：坦克/直升机/地面载具行为不变；A-10/J-16 等通场滚转仍按原档。

## 边界（没动什么）

- 不改前射固定翼的 REJOIN→ATTACK→EGRESS 几何与开关式滚转。
- 不改导弹的 0.2 秒锁定、不改弹速/重力/散布/RPM/炮塔转速。
- 不改 Forge 侧“只接管固定翼”的末制导范围；Neo 仍保持原有全载具范围。
- 不改盘旋半径 84 格、目标上方 165 格这两个几何数字。

## 台账

- 本轮不清债；未新增债。反射计数保持基线（`getMethod` Forge 205 / Neo 206）。
