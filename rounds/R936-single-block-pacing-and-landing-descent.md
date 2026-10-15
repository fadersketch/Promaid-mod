# 实测九百三十六 挖矿「一个方块里来回走」＋ 固定翼/汤姆6 降落「只降不抬 · 别压进土里」

> 跑道：**开发者点名三件**。开发者原话：「你不在测试的时候不再站着不动了，但是在一个方块里面来回走动。反正是一种另类的卡住。载具方面，降落期间还是会受到某些保护机制的压制，导致本来要降下去却又抬升起来，浪费了时间。而且最终降落的时候，飞机没有完全停在地面上，更像是被嵌到了地里面去。」
> 上一轮：`rounds/R935-aircraft-land-nose-down-and-mine-blocker-fallback.md`（九百三十五 降落「先下压 · 缩短」＋挖矿「挡路块走不到 → 改走接近链」）。
> 本轮依据的实机日志 = 开发者本次测试（NeoForge_21.1.250 `latest.log`，14:15:42~14:22:03，**跑的就是九百三十五 的 jar**）。

## 排班

- **本轮主项**：①挖矿「不再站着不动，改成一个方块里来回走」——用日志钉死是哪两条动作在来回翻，堵掉；②降落「被某种保护机制顶回去、本来要降又抬起来」——用日志钉死是哪两处在把她往上抬；③「最终落地嵌进地里」——钉死是收尾高度只认规划值。功能面（数值/矿表/建筑/对话/战斗）一律不动；版本号仍 **1.3.2**。
- 步骤：改 Forge → 镜像 Neo（同一份模板 + 名字映射，逐锚点断言唯一，命中数 ≠ 1 即中止）→ 两树 `javac EXITCODE=0` → 全护栏 `FAIL 0` → 打 jar → 部署 4 实例 `match=True`。
- 谁验收：开发者（本轮**已部署**、不跑回归；`## 验收` 记 `未验收`，并给逐条看日志的方法）。

## 需求 / 来源

三条都是**上一轮（九百三十五）改动引出的新症状**，实机日志逐条对上了：

- **现象①「在一个方块里面来回走动」**：`latest.log` 13:57–14:19 段里，同一秒级节奏反复出现**成对**的两行——
  `mine 朝挡路块推进(够不着矿) blocker=(-1417,23,-654) target=(-1418,23,-654)` 与
  `mine 挡路块走不到→改走接近链 blocker=(-1417,23,-654) target=(-1418,23,-654)`，
  夹杂 `walk 沿用同一目标(导航在走) stand=(-1417,22,-654)` / `work walk stuck … path=false`。
  两处动作只差一格目标（挡路块脚边 ↔ 矿边），来回设目标 → 她就在紧挨着的一格之间来回挪。这是"另类的卡住"。
- **现象②「降落期间被保护机制顶回去、本来要降又抬起来」**：`latest.log` 14:19:51–14:21:20 两架次实测——
  - TOM6 **进圈**：14:19:51→14:19:58 高度 `-37.61 → -33.89 → -29.99 → -26.09 → -22.16 → -18.58 → -16.45 → -15.87`，**12 秒里爬了 21.7 格**（距主人 174→197 还在往外飞）。目标 = 主人 + `PATTERN_ALT`(24) ⇒ 明明来降落却先被抬到主人上方 24 格。
  - 固定翼 **进场**：14:21:15→14:21:20 离地 `9.62 → 7.37`（沉到最低）随后 `9.81 → 12.58 → 14.90 → 15.52`（**又升回去**，此后钉在 ≈15.6）。下沉到底后因为"飞出去重建距离"使 `before` 变大、下滑道目标随之抬高，把她从 7.4 又抬回 15.6。
- **现象③「最终落地嵌进地里」**：14:21:41 接地段 `离地=3.05`，14:21:42 `完成`——收尾高度只认**规划里的 tdY**（= -60），她若漂到条带里别处落点、脚下真实地面比 tdY 高几格（条带允许 ≤ `AIRCRAFT_LAND_STRIP_RELIEF`=5 的落差），就照 tdY 把机腹收进土里。

## 定位与改法

### ① 挖矿：改正 `walkToBlockFace` 的"支撑"取格 + 让"走不到"记住挡路块（治来回横跳）

**定位**（两树，`MaidMineBehavior`）：

- `walkToBlockFace` 把站位的**支撑**查错了格：
  ```java
  BlockPos stand = new BlockPos(b.x, b.y - 1, b.z);   // 她要站的那一格
  BlockState below = level.getBlockState(stand);        // ← 错：stand 本身就该是空气
  if (below.isAir() || !below.isRedstoneConductor(level, stand)) { walkToOreBase(...); return; }
  ```
  `stand` 本该是空气 ⇒ 「是空气 → 退回 `walkToOreBase`」这条闸**几乎必中**；于是挡路块分支实际上一直走的是 `walkToOreBase`。而九百三十五 新加的兜底（`walkToBlockFace` 后导航给不出路径 → `approachOre`）会把目标改到**矿边**，两者**每拍互相覆盖** → 一个方块里来回走。
- **改法**：判据更正为「站位是空气 ∧ 脚下那一格实心」；并给"挡路块走不到"加**记忆**（`unreachableBlocker` + `MINE_BLOCKER_UNREACHABLE_TICKS`=40 拍）：一旦这条挡路块走不到，40 拍内不再试它，直接交给 `approachOre` 接近链（挖 / 垫 / 降都在那条链上，是真动作）——两条动作不再来回翻。

### ② 降落：进圈"只降不抬" + 进场/进近"不高过当前高度"

**定位**（两树，`MaidMountCompat`）：

- **进圈**（`aircraftFlyToCircle`）：目标 = `主人 y + AIRCRAFT_LAND_PATTERN_ALT`，她比这低就得先爬上去。
- **进场 / 进近**（`aircraftLandFinal`）：`targetY = max(下滑道, 净空线)`——下滑道 `tdY + before × GLIDE_SLOPE`，飞出去重建距离时 `before` 变大 ⇒ 目标抬高 ⇒ 把她抬回去追下滑道。

**改法**：

- 进圈目标改成 `min(当前高度, 主人 + PATTERN_ALT)`：比它高才降过去，比它低就**保持**（只降不抬）；地形净空同时从巡航那 45 格（`aircraftSafeTargetY` → `aircraftTerrainFloor` = 地面 + 1 + CLEAR14 + ALT_BONUS30）换成**降落档**的 `aircraftLandFloor`（地面 + `AIRCRAFT_LAND_MIN_ALT`=2）。
- 进场 / 进近的 `targetY` 再夹一道 `Math.min(下滑道, 当前高度)`：**不为了追下滑道往回爬**（低于下滑道就保持）；净空线仍可抬升 = 防撞不变。

### ③ 落地：收尾高度取"脚下真实地面净空线"

**改法**（两树，`aircraftLandFinal` 接地段）：收尾目标由 `tdY + AIRCRAFT_LAND_TOUCH_ALT` 改为
`restY = max(tdY + TOUCH_ALT, aircraftLandFloor)`（脚下滑行道净空线兜底）——既不悬空也不入土；过站后仍朝 `restY` 收最后一截但不再往下钻。另外 `aircraftHomeLanding` 的**完成**分支（已接地）每拍把**向下**的竖直速度清零：引擎贴地时不管这个分量，机腹会继续往地里渗。

- **没动**：降落链路的条带选址 / 判据、`OUT_D`、`CORRIDOR` / `MAX_BEARING`、八向顺机头选跑道、`PATTERN_ALT`（仍作"是否太高"判据与进圈**上限**）、`PLAN_KEEP_TICKS`、九百三十四 的硬限速、九百三十五 的陡下滑 / 快下沉 / 短外飞腿；挖矿的 `approachOre`、`pillarUpStep`、`digUpCap`、`walkToOreBase`、`findStandNearOre`、`MINE_WEDGE_TICKS`、`blockedOres` 全都不变；**没有新增开关**。

## 触及文件

- `promaid_src` / `promaid_src_neo` × `com/maidsmart/combat/MaidMountCompat.java`（① 进圈 `aircraftFlyToCircle`；② 进场 / 进近 `targetY`；③ 接地段 + 完成段）
- `…/task/MaidMineBehavior.java`（`walkToBlockFace` 支撑取格 + 挡路块"走不到"记忆窗口 + 两个新字段/常量）
- `rounds/RELEASES.tsv`（两条 1.3.2 `workspace` 行 sha256 + 第七十五次同号重打）
- `rounds/R936-single-block-pacing-and-landing-descent.md`（本文件）

## 验证

- **两树编译**：`compile_promaid.bat` → `EXITCODE=0`；`compile_neo.bat` → `EXITCODE=0`（16 处锚点全部唯一命中，`FAILS=0 / TOTAL=16`）。
- **镜像自查**：新增 / 改动的每一行逐条读回两树——Forge 侧一律 SRG（`m_20186_()` / `m_20148_()` / `m_8055_()` / `m_60795_()` / `m_60796_()` / `m_7495_()` / `m_7949_()` / `m_20184_()` / `m_20256_()`），Neo 侧一律 Mojang（`getY()` / `getUUID()` / `getBlockState()` / `isAir()` / `isRedstoneConductor()` / `below()` / `immutable()` / `getDeltaMovement()` / `setDeltaMovement()`）。
- **全护栏**：11 个全 `FAIL 0`（`check_comment_style` **PASS**；`check_preset` **FAIL 0 / WARN 0**、DIY 基线仍 **172/176**——本轮不增删开关；`check_workflow` 3 条历史 WARN）。`check_arch` 一致差 0。
- **两个 jar**：forge **17585135** 字节（sha256 `96AB4302…77BB94`）；neoforge **17621466** 字节（`A2125475…8FE0D5`）。
- **装机**：`deploy_all.py` → 4 个本机实例全部 **`match=True`**（1.21.1-NeoForge_21.1.250 / 1.20.1-Forge_47.4.10 / 龙之冒险：新征程v2.4a / mc_server_test pack1201）；旧包备份在 `patched/backup_old/`。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 `workspace` 行 sha256 更新为新字节，并追加「第七十五次同号重打」；九百三十五 的旧字节 `279EE195…86FE19`（forge）/ `ECF20BCA…B3EE6F7B`（neo）作废。
- **未跑实机回归**（已部署，等开发者实测）。

## 验收

- 状态：**未验收**（已部署：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
- ① **挖矿不再"一个方块里来回走"**：给她挖矿任务。**判据**：不该再看到「`mine 朝挡路块推进(够不着矿)`」与「`mine 挡路块走不到→改走接近链`」**每拍成对翻**；走不到那条挡路块后应稳定留在接近链上（接 `mine 挖掉一块` / `mine 朝挡路块搭高` / `mine 头顶压住挖开` 之类真动作）。若仍横跳，请把当时前后 20 行贴回来。
- ② **降落"只降不抬"**：让她开固定翼 / 汤姆6 进 home 档。**判据**：
  1. **进圈**（`固定翼降落·进圈`）`高度=` 应当**一路往下或持平**，不该再爬到「主人 + 24」；
  2. **进场**（`固定翼降落·进场`）`离地=` 不该在下沉之后又升回去（九百三十五 那次的 7.4 → 15.6 不该再现）；
  3. 正常对准的进近照旧单调下降到 `接地段` → `完成`。
- ③ **落地不嵌进地里**：落地后看飞机是"停在表面"而非"陷进去"。**判据**：`固定翼降落·接地段` 的 `离地=` 收尾值应贴近 1~3 格；落地后机身不再继续下沉。若仍明显陷土，请贴 `接地段`/`完成` 那几行回来。
- 判定：逐条按上面现象观察即可。

## 边界（没动什么）

- **没动**：降落链路条带选址 / 判据、`OUT_D`、`CORRIDOR`(14) / `MAX_BEARING`(40)、`ALIGN_DEG`、`PATTERN_ALT`(24，仍作"太高"判据与进圈上限)、`PLAN_KEEP_TICKS`(6000)、`FLARE_ALT` / `TOUCH_D` / `killHorizontal`、九百三十四 的 `AIRCRAFT_LAND_SPEED_STEP` / `aircraftGovernSpeedHard`、九百三十五 的 `GLIDE_SLOPE`(0.18) / `DESCENT_VY_MAX`(0.30) / `OUT_LEG`(60)；挖矿的 `approachOre`（含既有 `digUpCap`）、`pillarUpStep`、`walkToOreBase`、`findStandNearOre`、`blockedOres`、`MINE_WEDGE_TICKS`、`RECENT_DISCARD`。
- **口径变更（有意，非回归）**：① 进圈高度目标由「主人 + `PATTERN_ALT`」改为 `min(当前高度, 主人 + PATTERN_ALT)` + 降落档净空；进场 / 进近 `targetY` 加「不高过当前高度」夹；接地收尾取 `max(tdY + TOUCH_ALT, 脚下净空线)`；完成段清向下竖直速度。② 挖矿 `walkToBlockFace` 支撑取格更正 + 挡路块"走不到"记忆 40 拍。
- **没新增开关**；DIY 关闭条数基线仍 **172/176**；版本号仍 **1.3.2**（`WORKFLOW.md` §5「验收前不升号」）。新增常量：`MINE_BLOCKER_UNREACHABLE_TICKS`（mod 内部，不入配置）。

## 台账

- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第七十五次同号重打。
- `DEBT.md`：不新增条目——① 是"改正取错格的判据 + 给卡住加记忆"，②③ 是"按开发者口径把降落改成只降不抬、落地取真实地面"，都不引入新的未验证功能面。
