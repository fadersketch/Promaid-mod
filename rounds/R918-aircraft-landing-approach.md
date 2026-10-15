# 实测九百一十八 固定翼（滑翔式飞机）降落改进：条带选址 + 对准进近 + 下滑道 + 复飞

> 跑道：**A 修 bug**——P2（`DEBT.md` **BD-23**：固定翼降落"很难看、常常落不住"。根因是**注释与代码不符**：本区注释写的是"照一条下滑道（距离 × 0.30，最低 2 格）"，代码里的目标高度却是常数 `地面 + 2 格`）。
> 开发者指令：「这是另一个让女仆乘坐卓越前线载具的模组…我觉得它里面的滑翔式飞机的相关逻辑还是做的不错的，尤其是降落。…**降落这边别的不需要管。只需要让我们这边的滑翔式飞机可以更好的降落就可以了。别的不需要管。**」
> 参照物：`maid-frontline-1.20.1-0.1.3.jar`（反编译读其 `FlightControl` / `LandingPlan` / `RunwayApproach` 的思路；**该模组是 GPL-3.0，本模组是 MIT → 只借思路，代码全部自写**）。

## 排班

- **本轮唯一主项**：只改我们这边"滑翔式飞机"（固定翼 `AIRCRAFT` 这一档）的**降落**。直升机 / 飞艇 / 汤姆6 / 地面载具、巡航 / 接敌 / 起飞一律不动。
- 步骤与完成判据：
  1. **定位**：读 `MaidMountCompat` 的固定翼降落链（`aircraftHomeLanding` / `landingSpot` / `aircraftHoldAltitude` / `aircraftGovernSpeed` / `aircraftRunway`）。判据：写出下降段目标高度的算式，并指出注释与代码的差。
  2. **定口径**：降落 = 一条**能飞的进近**（条带选址 → 对准中线 → 下滑道 → 接地；不对准就复飞）；规划不出来**整条退回旧口径**；不新增开关。判据：口径写成一句话、落进注释。
  3. **改 Forge 树** → 4. **镜像 Neo 树**。判据：两树 `javac` `EXITCODE=0`。
  5. **护栏**。判据：全护栏 `FAIL 0`。
  6. **打 jar + 部署 4 实例**。判据：4 个目标全部 `match=True`。
- 谁验收：开发者（本轮只部署、不跑回归；`## 验收` 记 `未验收`）。

## 需求 / 来源

- 开发者给出另一个"让女仆乘坐卓越前线载具"的模组（`maid-frontline-1.20.1-0.1.3.jar`），点名其**滑翔式飞机的降落**做得好，要求只把我们这边的滑翔式飞机降落改好。
- 参照物的做法（反编译所得，仅供理解）：先按"落差 + 长度"选一条跑道、再按跑道方向进近（中线 + 侧偏修正）、按下滑道下降、超限复飞并黑名单该跑道。本模组旧版**只有**"朝最平的点压高度"，缺的正是这条进近链。

## 定位：旧降落的两处根因（两树同形）

1. **目标高度是常数**：`aircraftHomeLanding` 下降段写的是 `(groundY + AIRCRAFT_LAND_MIN_ALT) - mount.y`（`MIN_ALT = 2.0`），于是**不管离落点多远，目标高度都只有"地面 + 2 格"**——离得远时等于一路平飞过去、到了跟前才垂直往下扎。固定翼没有悬停能力，垂直下扎只有两个结局：擦地置 crash（SWB 会把乘客一次 114514 伤害打死，见本区顶部注释），或者速度掉到失速线以下掉下去。
2. **落点选址不查条带、也没有复飞**：`landingSpot` 只按 `relief3x3 × 4 + 距离` 选一个 3×3 最平的点，**不查条带长度 / 整条落差 / 水面**（湖面是平的，3×3 落差判据拦不住它），选错了也没有"复飞重选"这一步，只能一头扎下去。

## 改法（两树镜像）

**新增代码**（`promaid_src` 与 `promaid_src_neo` 各 +324 行，仅映射名不同）：

- **常量组**（`AIRCRAFT_LAND_GLIDE_SLOPE = 0.10` / `GLIDE_MAX_D = 90` / `LOOKAHEAD = 24`、`MAX = 80` / `ALIGN_D = 40`、`ALIGN_FRAC = 0.6` / `CORRIDOR = 14` / `MAX_BEARING = 40` / `WRONGWAY_DEG = 90` / `TOUCH_D = 12` / `SINK_CMD = 0.4` / `GOAROUND_LIFT = 16` / `STRIP_HALF = 24`、`HALF_W = 4`、`RELIEF = 5.0` / `TRY = 3` / `REJECT_TICKS = 1200`）+ 两张表（`AIRCRAFT_LAND_PLAN` 规划好的跑道、`AIRCRAFT_LAND_REJECT` 复飞拉黑）。
- `aircraftLandPlan`：先把 48 格方框里的候选点按"3×3 落差 ×4 + 距离"排序，再对靠前的 **3** 个候选点试**八向条带**（`stripRelief`，48×9 格、步长 2），要求整条落差 ≤ 5.0、**无悬空、无水面/岩浆**、且没被复飞拉黑的那条胜出；一条都没有 → `null`。高度图在未加载区块给的是"世界底面"那个数，这种列按"没数据"剔掉。
- `aircraftLandFinal`（每拍）：把她的位置投影到跑道坐标系（`s` = 沿跑道坐标、`cross` = 横向偏离）。
  - 滑翔道：`glideY = 接地点 + clamp(max(before,0),0,90) × 0.10`，再抬到 `aircraftLandFloor`（脚下 + 机头方向前方三列的地面 + 2 格）之上防钻地。
  - 瞄准点：**过站**（`s > 0`）或**机头朝反了**（|航向偏差| > 90°）→ 直接瞄接地点（先把方向拧回来）；否则追中线前方一点，前视距离 `clamp(|cross| × 1.5, 24, 80)`（固定翼纯追踪收敛的条件是"转弯半径 < 前视距离的一半"）。
  - 进近段：**保持巡航推力**（固定翼的升力 ∝ 速度，收了油门既没升力也没舵效）+ 速度环只压不拉；高度交给俯仰通道（`aircraftHoldAltitude`）。
  - 接地段（距接地点 ≤ 12 格）：压平机翼、怠速、`AIRCRAFT_LAND_FLARE_RETAIN` 把水平速度收到亚音速以下；已经进了接地区（`before ≤ 0`）就给一个恒定微下沉指令把最后一截收掉（不再"保持高度"，免得悬在 1.2 格上落不下去）。
  - 复飞：**近端**（距接地点 < 24 格）横向出 14 格走廊或航向偏差 > 40° → 把这条件（候选点 + 方向）拉黑 1200 拍、拉起 16 格、作废本拍规划；下一拍 `aircraftLandPlan` 自动改选别的方向 / 别的候选点（不会反复朝同一条死路扎）。
- `aircraftHomeLanding` 分流：算完 `fuelDead` 后先试跑道规划，成功即走上面这条链；**规划不出来整条退回旧的"最平点"口径**（`landingSpot` 那套，一个字节不变）。
- 缓存清理：`endAircraftLanding` / `resetAircraftLanding` 一并清 `AIRCRAFT_LAND_PLAN` / `AIRCRAFT_LAND_REJECT`。
- 顺手修掉旧注释里那句与代码不符的"照一条下滑道（距离 × 0.30，最低 2 格）"→ 改写为"退回口径：…把目标高度钉在'该列地面 + {@link #AIRCRAFT_LAND_MIN_ALT}'"。

## 触及文件

- `promaid_src/com/maidsmart/combat/MaidMountCompat.java`（+324 行）
- `promaid_src_neo/com/maidsmart/combat/MaidMountCompat.java`（+324 行，镜像）
- `rounds/RELEASES.tsv`（两条 1.3.2 `workspace` 行的 sha256 列 + 第五十七次同号重打记载）
- `DEBT.md`（新增 BD-23 + §0 索引行）

## 验证

- **两树编译**：`tools/build/gen_compile.py` + `compile_promaid.bat` → `EXITCODE=0`；`gen_compile_neo.py` + `compile_neo.bat` → `EXITCODE=0`（过程中修掉两处把 `getMinBuildHeight` / `getFluidState` 当字段写的笔误，见 `## 边界`）。
- **全护栏**：`check_comment_style` **PASS**（0 处）；`check_preset` / `check_catch_swallow` / `check_i18n_keys` / `check_dev_voice` / `check_comment_dup` / `check_reflection` / `check_arch` **FAIL 0 / WARN 0**；`check_workflow` **FAIL 0 / WARN 3**（历史遗留：PUBLISH_GUIDE 版本、1.2.4 归档哈希、13 个更早 jar 未登记）。
- **两个 jar**：`verify_jar_classes` **OK**（mixin 包登记 OK、注入签名 OK；`out` ↔ `jar` class 双向一致）。
- **未跑实机回归**（先停在这，等开发者指挥）。

### 部署（2026-10-13）

- 打 jar：`tools/build/build_promaid.py` → `patched/promaid-1.3.2-forge-1.20.1.jar`（**17583183** 字节，sha256 `b5821e00…f1020a`）；`build_promaid_neo.py` → `patched/promaid-1.3.2-neoforge-1.21.1.jar`（**17621220** 字节，sha256 `72bba604…f0a935`）。
- 装机：`tools/deploy/deploy_all.py` → 4 个本机实例全部 **`match=True`**（1.21.1-NeoForge_21.1.250 / 1.20.1-Forge_47.4.10 / 龙之冒险：新征程v2.4a / mc_server_test pack1201）；旧包已备份在 `patched/backup_old/`。
- 留痕：`rounds/RELEASES.tsv` 两条 1.3.2 `workspace` 行的 sha256 列更新为新字节，并追加「第五十七次同号重打」记载；旧字节 `d3a2d81a…445bc5`（forge）/ `09c9031f…5326b`（neo）作废。
- 到此为止：**未跑回归、未验收、未 commit、未 push**（开发者指令："做完功能先部署，部署完等他指挥"）。

## 验收

- 状态：**未验收**（已部署@2026-10-13：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
  - 复现步骤 1（**落得下来**）：给女仆一架固定翼（固定翼滑翔式飞机），切进 home 模式或让她电量见底 → 她应当**沿着一条下滑道**朝一块平整地（落差 ≤5、非水面）下降、**在接地前把速度收下来**，而不是"平飞过去再垂直扎地"。
  - 复现步骤 2（**选得出落点**）：把她放到**湖面 / 悬崖 / 陡坡**上方 → 她**不该**把水面当选落点（条带校验会否决），应当另寻一块平地。
  - 复现步骤 3（**没对准会重来**）：把她的机头一开始就对着跑道反方向 → 她应当先把方向拧回来再进近；近端还没对准时应当**拉起复飞一次**再重选方向，而不是硬扎下去。
  - 复现步骤 4（**旧口径仍在**）：在一片"全是水 / 全是悬崖"的地方（一条合格条带都选不出来）→ 行为应与旧版一字不差（退回 `landingSpot` 的"最平点"口径）。
  - 判定：步骤 1/2/3 出现新的进近行为、步骤 4 无变化 = 合格；异常请贴 `logs/promaid.log` / `latest.log` 里 `固定翼降落` 相关的几行。

## 边界（没动什么）

- 只动**固定翼（AIRCRAFT）的降落**这一条链：`aircraftHomeLanding` 的三处入口判据（home 模式 / 机上有人且周围无敌人 / 电量见底）、覆盖车型（`isAircraftEngine`）、以及**规划不出来时的旧"最平点"口径**（`landingSpot` + 常数"地面 + 2"那段）全部逐字保留。
- **没动**：直升机 / 飞艇 / 汤姆6 / 地面载具 / 轮椅 / 船；固定翼的巡航（航向 / 速度 / 高度环）、接敌、起飞（`aircraftRunway` 助跑带、起飞档、爬升窗口）；`RideBindManager` 的驱动分流；其它任何模块。
- **不新增开关、不改任何配置项 / 默认值**；版本号仍 **1.3.2**（`WORKFLOW.md` §5 "验收前不升号"）。
- 与参照模组的关系：其代码为 **GPL-3.0**、本模组为 **MIT** → **只借思路、不抄代码**（无一行来自反编译产物）。
- 过程留痕：两树各有一处笔误（把 `getMinBuildHeight` / `getFluidState` 当字段而非方法调用）在首轮编译时被 `javac` 拦下并当场修掉；临时脚本（`tools/_land918.py` / `_land918fix.py` / `_wf.py` / `_wf.txt` / `_sha.py` / `_r918docs.py`）**已删**。

## 台账

- `DEBT.md`：新增 **BD-23**（P2，已清@实测九百一十八：固定翼降落"很难看、常常落不住"——目标高度是常数 / 选址不查条带与水面 / 没有复飞），并更新 §0 索引行「战斗 / 骑乘 / **载具** / 蹲下语义」加入 BD-23。
