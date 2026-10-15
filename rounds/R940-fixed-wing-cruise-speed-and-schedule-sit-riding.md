# 实测九百四十 固定翼坠机（巡航速度真钳回设计值 · 地形前瞻加长）＋ 排班表远程坐下骑乘豁免补全

> 跑道：**开发者反馈两条**（原话见下）。上一轮：`rounds/R939-heat-immunity-exemption-consolidation.md`。
> 本轮依据：开发者本次实测反馈（NeoForge_21.1.250，跑的是九百三十九 的 jar）+ 实机日志 + 代码定位。

## 排班

- 两件一次收口；功能面（数值/矿表/建筑/对话/战斗判据）不动；版本号仍 **1.3.2**。
- 步骤：改 Forge → 镜像 Neo（逐锚点断言唯一，命中数 ≠ 1 即中止）→ 两树 `javac EXITCODE=0` → 全护栏 `FAIL 0` → 打 jar → 部署 4 实例 `match=True`。
- 谁验收：开发者（本轮**已部署**、不跑回归；`## 验收` 记 `未验收`）。

## 需求 / 来源

开发者原话：「1.新版本的排班表加了一个让女仆远程坐下的功能。如果女仆处于骑乘状态，这个功能不应该生效，我不知道这个你做了没？2.刚刚运行游戏的时候，你的抬高高度根本就没生效。女仆开着飞机还是坠机了」

## 定位

### ① 排班表「远程坐下」+ 骑乘 —— 已经做了，本轮只补一处口径
- 功能在 **实测八百二十八** 实现：`ScheduleBookScreen` 快捷页「让她站起 / 让她坐下」按钮 → `MaidSitTogglePacket`。
- 骑乘豁免**两侧都有**：客户端按钮 `canSitBtn = … && !this.maidRiding`（骑乘时置灰显示「■ 骑乘中」），服务端 `handle` 里 `if (maid.isPassenger()) { 回消息拒绝; return; }`。
- 缺口：判据只看原版 `isPassenger`。**龙 / 卓越前线载具那种"悬空鞍位"她不是原版乘客**（由 `RideBindManager` 每拍摆位并强制坐姿），此时按钮可点、服务端也放行——她确实在骑乘，只是原版判据认不出。本轮补上。

### ② 固定翼坠机 —— 避险抬升其实触发了，但速度是设计值的 3.5 倍，来不及
实机日志（`D:\.minecraft\versions\1.21.1-NeoForge_21.1.250\logs\latest.log`）：

```
16:20:40  固定翼滑跑 … 水平速度=0.00/1.20 距目标=23格 高差=5
16:20:45  固定翼巡航档 跟随档(比主人高3格) 固定翼加高30格 地形净空(抬到地面上44格)
          鼠标X=-12 滚转=-45 俯仰=-3 推力=3.00 水平速度=2.79 竖直速度=0.30 距目标=142格 高差=-21→37
16:20:46  载具避险 … 贴地/前方有方块 → 期望高度抬升 6.00 格越过
16:20:47  [maidhurt] … 类型=air_crash 伤害=114514.0 位置=(-1634.7,155.2,-916.2) y=155
16:20:47  [CHAT] 大正女仆酒狐坠机了
```

读出来的三件事：

1. **避险抬升是生效的**（日志里连抬了 3.00 / 6.00 格）——但 `vehicleAvoidLift` 的作用只是"把期望高度抬一截"，它救不了"速度太快"。
2. **巡航速度 2.79 格/拍**（≈56 格/秒），而设计值 `AIRCRAFT_CRUISE_SPEED` = **0.80**。`推力=3.00`（冲刺位满推）——软速度环压不住它。
3. `竖直速度=0.30` 已经顶格（`AIRCRAFT_VY_AVOID_MAX`），`高差=-21→37`：地形净空线要求她再爬 37 格，可她 1.6 秒后就撞了。

**根因（代码层面）**：`aircraftCruise` 的第 ④ 层用的是**软**速度环 `aircraftGovernSpeed(mount, AIRCRAFT_CRUISE_SPEED)` ——它每拍只允许削 `AIRCRAFT_SPEED_DECEL` = 0.04，靠直接改 `deltaMovement` 收敛。而同一份代码在 **实测九百三十四** 里已经实证过这句软环没用：

> 「巡航那个环"只压不拉"、每拍只削 0.04，压不住引擎的冲刺推力（实机日志降落全程 `水平速度=2.8~3.2`、`推力=3.00`），设计值 0.80 等于没有」

九百三十四 为此加了**硬**速度环 `aircraftGovernSpeedHard`，但**只接在降落链路上**（盘旋/进场/进近/接地），巡航档漏了。于是巡航全程 2.8~3.0，后果有两层：

- **转弯半径** `r ≈ v/0.0298`：v=0.80 → 27 格；v=2.79 → **94 格**（日志里 `滚转=-45 鼠标X=-12` 满舵在转也收不回来）。
- **撞地伤害** `18×(v×0.2)²`：v=0.80 → 6.5；v≈2.6 → 103。速度 2.8 时 `air_crash` 直接判满值秒杀。
- 顺带：**净空线的前瞻不够**。要抬 Δh 格，需要提前 `v·Δh/vy` 格 = `0.80·44/0.30 ≈ 118` 格，而当时上限 `AIRCRAFT_LOOKAHEAD_MAX` = 84 格（0.80×100 拍），只够爬 30 格。

## 改法

### ① 排班表「远程坐下」骑乘判据补全
- `SchedulePacketsMaid`（两树）：姿态回包 `MaidCoordPacket` 的 `riding` 字段与 `MaidSitTogglePacket.handle` 的拒绝分支，判据都由 `isPassenger()` 扩为 `isPassenger() || RideBindManager.isSpecialMountRider(maid)`——客户端按钮据此自动置灰，服务端据此拒绝，两侧同口径。

### ② 固定翼巡航：速度真钳回设计值 + 地形前瞻加长
- `MaidMountCompat.aircraftCruise` 第 ④ 层：`aircraftGovernSpeed(...)` → `aircraftGovernSpeedHard(mount, AIRCRAFT_CRUISE_SPEED, AIRCRAFT_CRUISE_SPEED_STEP)`；新增内部常量 `AIRCRAFT_CRUISE_SPEED_STEP = 0.15`（2.6 → 0.80 约 12 拍 = 0.6 秒收敛，比软环的 45 拍快得多、又不至于急刹）。
- 地形前瞻：`AIRCRAFT_LOOKAHEAD_TICKS` 100 → **160**、`AIRCRAFT_LOOKAHEAD_MAX` 84 → **140**（0.80×160 = 128 格 ≥ 118 格所需提前量，爬升余量 48 格 ≥ 44 格净空线）。
- 只动这两处：`AIRCRAFT_CRUISE_SPEED` 本身（0.80）与净空线高度（`TERRAIN_CLEAR` 14 + `ALT_BONUS` 30 = 地面 44 格）一个数字没改——本轮是**让既有设计值真正生效**，不是改设计。

## 触及文件

- 两树 × `com/maidsmart/combat/MaidMountCompat.java`（②，巡航硬速度环 + 新常量 + 前瞻两常量）
- 两树 × `com/maidsmart/schedule/SchedulePacketsMaid.java`（①，骑乘判据两处 + 注释）
- `rounds/RELEASES.tsv`（第七十九次同号重打）+ `rounds/R940-fixed-wing-cruise-speed-and-schedule-sit-riding.md`（本文件）

## 验证

- **两树编译**：`compile_promaid.bat` / `compile_neo.bat` → `EXITCODE=0`（12 处锚点全部唯一命中，`FAILS=0 / TOTAL=12`）。
- **镜像自查**：本轮改动的行都是无混淆名文本（常量、方法调用、注释），Forge / Neo 两树逐字一致；`SchedulePacketsMaid` 的两处判据各自用本树的 `m_20159_()` / `isPassenger()`，追加的 `RideBindManager.isSpecialMountRider(maid)` 两树同名（该判据本身两树同签名）。
- **全护栏**：11 个全 `FAIL 0`（`check_comment_style` **PASS** 931 文件 / 0 处；`check_dev_voice` / `check_i18n_keys` / `check_panel_coverage` FAIL 0；`check_preset` **FAIL 0 / WARN 0**、DIY 基线仍 **172/176**——本轮不增删开关；`check_workflow` 3 条历史 WARN）。
- **两个 jar**：forge **17587589** 字节（sha256 `9EB8E32B…0DB179`）；neoforge **17623967** 字节（`49AB5091…EB7010`）。
- **装机**：`deploy_all.py` → 4 个本机实例全部 **`match=True`**。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 行更新 sha256 并列第七十九次同号重打；九百三十九 的旧字节 `344705EC…3FDE05`（forge）/ `B42CD6C2…E0DC9F`（neo）作废。
- **未跑实机回归**（已部署，等开发者实测）。

## 验收

- 状态：**未验收**（已部署：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
- ① **排班表远程坐下**：让她骑上船 / 坐骑 / 龙 / 卓越前线载具，打开排班表快捷页 → 站/坐按钮应**置灰显示「■ 骑乘中」**；即使伪造包，服务端应回「§7她正骑在坐骑/船上——先让她下来才能调整站/坐」且不改变她的坐姿。
- ② **固定翼巡航速度**：让她开固定翼（AC-130H / A-10 / J-16 / 汤姆6）跟随你飞 → 日志 `固定翼巡航档 … 水平速度=` 应稳定在 **0.8 附近**（不再是 2.8~3.0），转弯半径明显收小（约 27 格）。
- ③ **不再坠机**：朝有山 / 建筑的方向飞 → 她应提前（约 128 格）看到地形并把高度抬上去，越过之后回落；日志里 `air_crash` / 「坠机了」不再出现。
- 判定：日志搜 `固定翼巡航档` 看 `水平速度` 与 `地形净空`；再观察是否会撞地形。

## 边界（没动什么）

- **没动**：`AIRCRAFT_CRUISE_SPEED`（0.80）、`AIRCRAFT_TERRAIN_CLEAR`(14)/`AIRCRAFT_ALT_BONUS`(30)、`AIRCRAFT_VY_*`、俯仰/滚转/偏航各环、起飞与爬升窗口、降落链路（九百三十四~九百三十九 的 `aircraftGovernSpeedHard` 调用点一个没碰）；`vehicleAvoidLift`（九百三十八 的避险抬升本身：它照旧"脚下贴地就抬、前方有方块就抬"）；排班表其余功能。
- **口径变更（有意，非回归）**：① 排班表站/坐的"骑乘"判据含特殊坐骑鞍位；② 固定翼**巡航段**改用硬速度环（此前只有降落段是硬的）——巡航速度从实际 2.8~3.0 回到设计值 0.80；③ 地形净空线前瞻 84 → 140 格上限（100 → 160 拍）。
- **没新增配置开关**（新增常量 `AIRCRAFT_CRUISE_SPEED_STEP` 为 mod 内部）；DIY 关闭条数基线仍 **172/176**；版本号仍 **1.3.2**。
- **已知取舍（如实记录）**：② 把巡航压到 0.80 是**九百三十四 已经写明的设计值**（转弯半径 27 格、撞地伤害 6.5），本轮只是让它生效；代价是接敌/跟随时也按 0.80 飞（比 2.8 慢），若实测觉得"太慢追不上主人"，可把 `AIRCRAFT_CRUISE_SPEED` 单独调高（那会同时放大转弯半径与撞地伤害，需要再配前瞻距离）。

## 台账

- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第七十九次同号重打。
- `DEBT.md`：不新增条目——①是补一处判据口径，②是让既有设计值真正生效（九百三十四 已把结论写在注释里，本轮只是把硬环接上巡航档）。
