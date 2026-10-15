# 实测九百二十八 固定翼"降下来了、可离主人太远"与挖矿"锁了目标还是站着不动"：跑道圆心改成主人 + 走路"导航在走、人没动"强制重发

> 跑道：**B 功能抢修**——开发者实机跑完第二轮报两件。
> 开发者指令（原话）：「可以降落了，但是降落地点离主人太远了。挖矿方面，明明已经写的代码让女仆抓住目标不放，不达目的不罢休。但是为什么刚刚游戏测试的时候，他还是会站在原地不动呢？」
> 上一轮：`rounds/R927-aircraft-landing-fixes-and-mine-hold-on.md`（九百二十七 把降落链路改成收敛的、把挖矿的发呆/看门狗整段删掉）。

## 排班

- **本轮主项**：两件都是"上一轮改完之后的残留"，一次改完、一次部署。功能面（数值/矿表/建筑/对话/战斗）一律不动；版本号仍 **1.3.2**。
- 步骤与完成判据（每件都先「定位到具体代码 → 改 → 编译护栏打包装机」）：
  1. 降落先回答"上一轮明明降下来了，为什么落点离主人那么远"，判据：能指出**具体表达式**（哪张表、以谁为圆心、钉多久）并说明它为何与"离主人近"无关。
  2. 挖矿先回答"看门狗都删了，为什么她还能站着不动"，判据：能指出**具体那几行**（不是"可能是引擎卡"），并且**这一轮之后这类停顿必须能在日志里看见**（不能再有"静默站住"）。
  3. 改 Forge 树 → 4. 镜像 Neo 树（同一份模板 + 名字映射，逐锚点断言）；判据：两树 `javac` `EXITCODE=0`。
  5. 护栏 → 打 jar → 部署 4 实例；判据：全护栏 `FAIL 0`（`check_comment_style` 必须 `PASS`）；4 目标 `match=True`。
- 谁验收：开发者（本轮只部署、不跑回归；`## 验收` 记 `未验收`，并给出逐条实机复现步骤与日志关键字）。

## 需求 / 来源

- 现象①「可以降落了，但是降落地点离主人太远了」——九百二十七 把降落链路改成收敛之后，**降落本身成立了**（实机 `10:36:56 进近` → `10:37:06 已接地`），剩下的是**落在哪儿**：跑道的候选点框是**以载具自己为圆心**扫半径 48 格、挑中哪条就**钉住 5 分钟**（`AIRCRAFT_LAND_PLAN_KEEP_TICKS = 6000`）。也就是说"她什么时候进 home 档，就在那附近找条平地带落"——主人在地面跑、她在天上飞，两者一散开就是几十上百格。这不是"降落姿态"的问题，是**圆心选错了**。
- 现象②「明明已经写的代码让女仆抓住目标不放……为什么刚刚游戏测试的时候，他还是会站在原地不动呢」——九百二十七 删掉的是"发呆看门狗"与"每 5 秒重挑"，那两件**确实**删干净了（本轮实机会话里 `mine stuck-reset` / `mine relocate` / `mine box empty but home-restricted` **一条都没有**，说明既没触发超时弃置、也没触发框空迁移），可"站着不动"照旧出现。**根因是我们自己写的那道防抖闸**：`MaidWorkBehavior.setWalkTarget` 里"目标没变（±1 格容差）**且导航还在走**（`!isDone()`）就不重设"——它的前提是"导航在走 = 她确实在挪窝"。一旦导航**握着一条执行不了的路径**（目标格在墙里、路径第一段是死路、她卡在洞里……），`isDone()` 仍是 `false`，这道闸就**永远不放行**，而本模组是**直接 `moveTo`**、绕开了 TLM `MoveToTargetSink` 自己的恢复通道 —— 于是她**带着一个活目标、无限期站着**，而且**一条日志都不打**。这就是"锁了目标还不走"的真身：不是没锁住，是**锁住了却发不出（也不重发）移动指令**。

## 定位与改法

### ① 固定翼降落：跑道圆心改认主人（挑不出再退回她自己）

**定位**（`MaidMountCompat.aircraftLandPlan`，两树）：

- 候选点框的圆心取自**载具自己**：`int cx = floor(mount.getX()); int cz = floor(mount.getZ());`，往外扫 `AIRCRAFT_LAND_RADIUS = 48` 格（`STEP = 4`），按 `3×3 落差 ×4 + 到圆心的距离` 排序，再对靠前的 `AIRCRAFT_LAND_TRY = 3` 个试八向条带。
- 选中后写进 `AIRCRAFT_LAND_PLAN`（缓存 6000 拍 = 5 分钟，漂出 `PLAN_KEEP_D = 200` 格才作废）——**这一场降落就落这条**。
- 于是"离主人多远"**从头到尾没有进过任何判据**：她进 home 档时离主人 100 格，就落在离主人 100 格附近。

**改法**（两树，同一份逻辑）：

- **圆心优先取主人脚下**：新增 `ownerLandAnchor(maid, sl)`（主人存在、且与她在同一维度 → 返回 `{x, y, z}`，否则 `null`）。`aircraftLandPlan` 的 `cx/cz` 改成 `tryOwner ? ownerAnchor[..] : mount.…`，`tryOwner = ownerAnchor != null && !AIRCRAFT_LAND_SELF_CENTER.contains(id)`。签名相应加一个 `Entity maid` 形参（调用点 `aircraftHomeLanding` 那句改成 `aircraftLandPlan(mount, maid, landNow)`）。
- **主人那一侧挑不出跑道就退回她自己**（不能因为"主人在屋里/山里/跨维度"就把降落卡死）：主人侧一条合格条带都没有 → 把这只载具记进新表 `AIRCRAFT_LAND_SELF_CENTER`（`Set<UUID>`），**下一次规划**以她自己为圆心（老口径，一个字节不变）；主人侧成功落成一次就清掉标记（`endAircraftLanding` / `resetAircraftLanding` / 复飞作废处共三处一并清）。
- **落点留痕**：定跑道成功时打一行 `模组坐骑 … 固定翼降落·定跑道 接地点=(x,y,z) 方向=…度 距主人=N格`（没主人时写 `（没主人：以她自己为圆心）`）——下一轮"还是太远"可以直接看这个 N，不用再猜。

### ② 挖矿/伐木：走路"导航在走、人没动"→ 强制停导航重发 + 留痕

**定位**（`MaidWorkBehavior.setWalkTarget`，两树）：

```java
if (this.lastWalkTarget != null
        && Math.abs(stand.getX() - this.lastWalkTarget.getX()) <= 1
        && Math.abs(stand.getZ() - this.lastWalkTarget.getZ()) <= 1
        && stand.getY() == this.lastWalkTarget.getY()
        && !maid.getNavigation().isDone()) {
    return;                       // 目标未变（±1 容差内）且导航还在走：不重设
}
```

这道闸本身是为"一走一停"加的（目标没变就不重发，免得每次重发都重跑一次 `createPath`），**但它把"导航在走"当成了"她在挪窝"**。两条独立的证据链指向它：

1. **代码面**：`isDone()` 只说明"路径队列里还有内容"，不说明"这一步迈得出去"。而本模组走的是**直接 `moveTo`**（九百二十七 的注释里写明：不写 `WALK_TARGET`、`MoveToTargetSink` 永不启动），所以 TLM 原版那套"路径走不动 → 超时清理 → 重新寻路"的恢复通道**根本没接上**。两条一叠 = 静默死锁。
2. **实机面**：本轮会话（NeoForge_21.1.250，`10:31:49–10:37:34`）里挖矿链路 `10:33:55–10:34:39` 一路productive（`mine chain burst` 连续），随后**到玩家退出（`10:35:07`）之间一行动作都没有**；全程 `mine behavior start` 只有 2 条、`mine stuck-reset` / `mine relocate` / `mine box empty but home-restricted` **0 条**。也就是说：**行为没停、框没空、也没被判超时**，她就是"带着目标站着"。其余解释（扫描太慢 / 没镐 / 框空）都被这组数据排除了：全量扫描是**分帧**的（`MINE_SCAN_BUDGET = 4096`/拍，框内 88837 列 ≈ 22 拍 ≈ 1.1 秒，且这 1.1 秒里 `MINE_SCANS` 有记录、不是静默）。

**改法**（两树，矿/木共用这一处，改动只在基类）：

- **把"在走"换成"在挪窝"**：`setWalkTarget` 开头先采一次**脚下格**（`BlockPos walkFeet = maid.blockPosition()`；变了就把 `lastWalkPos` / `walkStillSince` 推到当前 `gameTime`）。那道闸多一个前置：`walkStill`（**整整 `WALK_STILL_TICKS = 20` 拍 = 1 秒脚下格没动**）为 `false` 时照旧 `return`（防抖原意完整保留）；为 `true` 时**停掉导航**（`getNavigation().stop()`）再往下走一次 `moveTo` —— 这一次才会真正重跑 `createPath`，把它从"握着一条走不动的路径"里拽出来。
- **留痕**：新增 `reportWalkStuck(maid, stand, now)`（5 秒/条限频）打一行
  `work walk stuck: maid=… target=… stand=… feet=… dist=… path=… -> stop+retarget`。
  这条日志本身就是本轮的"验收凭据"：**以后再出现"站着不动"，日志里必然有它**（要么有它 = 命中这条死锁且已经在自愈，要么没有它 = 另有原因，可以直接排除本项）。
- **顺带把"行为被停"也留痕**：`MaidMineBehavior.stop(...)`（Forge 侧 `m_6732_`）第一行加 `mine behavior stop: maid=… target=… progress=… feet=…`。这样"她不动"能一眼分成三类：**行为被停**（有 `stop`）、**死锁自愈**（有 `work walk stuck`）、**真在干活**（有挖掘行）。

## 触及文件

- `promaid_src` / `promaid_src_neo` × `com/maidsmart/combat/MaidMountCompat.java`（① `ownerLandAnchor` / 圆心取主人 / `AIRCRAFT_LAND_SELF_CENTER` / 三处清标记 / 定跑道留痕 / 调用点加参）
- `…/task/MaidWorkBehavior.java`（② `WALK_STILL_TICKS` + `lastWalkPos` / `walkStillSince` / `walkStuckLogAt` + `setWalkTarget` 的挪窝采样与强制重发 + `reportWalkStuck`）
- `…/task/MaidMineBehavior.java`（② `mine behavior stop` 留痕）
- `rounds/RELEASES.tsv`（两条 1.3.2 `workspace` 行 sha256 + 第六十七次同号重打）
- `rounds/R928-aircraft-landing-owner-anchor-and-work-walk-stall.md`（本文件）

## 验证

- **镜像自查（本轮抓到并修掉一次真问题）**：改法脚本是"同一份文字写两树 + 一张名字映射表"，而那张表里 `{X}/{Y}/{Z}` 映的是 **`Entity` 的**访问器（`m_20185_()` / `getX()`）。新代码里 `stand` 与 `lastWalkTarget` 是 **`BlockPos`**，Forge 侧应当是 `m_123341_()/m_123342_()/m_123343_()`（Neo 侧 `getX()/getY()/getZ()` 恰好同名，所以只有 Forge 会错）。**逐行读回生成的代码时发现**，写回前就修掉了（Forge 树 9 处：那道闸 6 处 + `reportWalkStuck` 里 3 处；Neo 树 0 处），并复查全文件 `stand.m_2018x_()` / `this.lastWalkTarget.m_2018x_()` 残留 = **0**。
- **两树编译**：`compile_promaid.bat` → `EXITCODE=0`（86 个警告全是既有的 `ResourceLocation` 过时提示，与本轮无关）；`compile_neo.bat` → `EXITCODE=0`（只有 `deprecation` / `removal` / `unchecked` 三条注记）。
- **全护栏**：`check_comment_style` **PASS**（931 文件 / 0 处；本轮新写的 `aircraftLandPlan` javadoc 第一版 **12 行 > 8** 被它拦下（`LONG-BLOCK`），压到 **8 行**后通过）；`check_arch` / `check_catch_swallow` / `check_comment_dup` / `check_dev_voice` / `check_i18n_keys`（两树 0/0，config 键 621/627）/ `check_panel_coverage` / `check_preset`（DIY 关闭 **172/176**，与九百二十七 的基线一致——本轮不增删开关）/ `check_reflection` **FAIL 0 / WARN 0**；`check_workflow` **FAIL 0 / WARN 3**（历史遗留：PUBLISH_GUIDE 版本、1.2.4 归档哈希、13 个更早 jar 未登记——与本轮无关）。`check_tree_mirror`（只读、不作闸门）照旧。
- **两个 jar**：forge **17581679** 字节（sha256 `787C5567…65B309`）；neoforge **17618085** 字节（`F5F2205B…95B156`）。
- **装机**：`deploy_all.py` → 4 个本机实例全部 **`match=True`**（1.21.1-NeoForge_21.1.250 / 1.20.1-Forge_47.4.10 / 龙之冒险：新征程v2.4a / mc_server_test pack1201）；旧包备份在 `patched/backup_old/`。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 `workspace` 行 sha256 更新为新字节，并追加「第六十七次同号重打」；九百二十七 的旧字节 `913B3AE6…DCC16D`（forge）/ `C7F9E53B…A2A222`（neo）作废。
- **未跑实机回归**（先停在这，等开发者指挥）。

## 验收

- 状态：**未验收**（已部署：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
  - ① **落点认主人**：让她开固定翼（Tom F6F / AC-130H / 汤姆6 那一类）在**离主人几十格以上**的地方进 home 档。**判据**：日志出现 `固定翼降落·定跑道 接地点=(…) 方向=…度 距主人=N格`，这个 **N 应当是个不大的数**（主人站在空地上时应当在十几~三十几格，也就是"绕一圈就能回来"的量级）；随后照旧 `进近` → `接地段 离地=…` → `固定翼降落完成：已接地 → 停住不动`。若主人当时在屋里/山里（主人那一侧挑不出跑带），会先打 `（没主人：以她自己为圆心）` 那种一行，然后以她自己为圆心落——**这是设计内的退回口径，不是失败**，此时落点远近仍取决于她当时在哪。
  - ② **站着不动**：给她挖矿/伐木任务，锁上一块矿/一棵树。**判据**：一旦出现停滞，日志里应当出现
    `work walk stuck: maid=… target=… stand=… feet=… dist=… path=true -> stop+retarget`
    ——**出现即命中本轮的修复**（她在同一格上站满 1 秒 → 停导航重发，随后应当看到挖掘/走路行接上）；若她**又不动、而这条日志一条都没有**，那说明是另一条路径（请把当时前后 20 行贴回来，尤其是 `mine behavior stop:` 有没有出现——有它就是"行为被停"，没有它就是"另有原因"）。注意这道闸**只在 1 秒没挪窝时才多打一次重发**，正常走路**不会**出现这条日志（防抖原意未变）。
  - 判定：逐条按上面现象观察即可。若某条仍复现，请贴 `logs/promaid.log` / `logs/latest.log` 里对应该条的几行（含时间戳）。

## 边界（没动什么）

- **没动**：条带选址与条带判据（`stripRelief` / `AIRCRAFT_LAND_STRIP_*`）、候选点排序与 `AIRCRAFT_LAND_TRY`、八向两遍顺机头挑选（`AIRCRAFT_LAND_ALIGN_DEG`，九百二十七）、进场段瞄准点与固定外点（`OUT_D + OUT_LEG`）、下滑道与接地段高度闸（R924）、盘旋兜底（`holdAircraftPattern`，九百二十七）、跑道钉住时长与漂移作废（`PLAN_KEEP_TICKS 6000` / `PLAN_KEEP_D 200`）；挖矿的锚点迁移（`slideAnchor`，R926）、2 秒"走不到就换目标"闸（`MINE_WEDGE_TICKS` / `WOOD_WEDGE_TICKS`，九百二十七）、`MINE_REQUIRE_PICK` / `MINE_HARD_BLOCK_REPORT` / `RECENT_DISCARD` / 目标超时 / `TARGET_SINCE`、`findOre` 与分帧扫描预算全都不变。
- **口径变更（有意，非回归）**：① 跑道圆心的**第一优先**由"载具自己"改成"主人脚下"，"主人那一侧挑不出条带"时**退回**原来的"她自己为圆心"（并且**只退一层**，用 `AIRCRAFT_LAND_SELF_CENTER` 记住本轮已退，主人侧成功落成即清）；② `setWalkTarget` 的防抖闸多一个前提——**脚下格 1 秒没动**时不再"跳过重设"，而是**停导航 + 重发**（防抖在"她确实在挪窝"时一字未变）；③ 新增两条**诊断**（`固定翼降落·定跑道` / `work walk stuck`）与一条行为停止留痕（`mine behavior stop`）。
- **没新增开关**；DIY 关闭条数基线仍 **172/176**；版本号仍 **1.3.2**（`WORKFLOW.md` §5「验收前不升号」）。新增数值常量只有 `WALK_STILL_TICKS = 20`（1 秒，模块内常量，未经实机标定——若实机看下来"卡一下就重发"太频繁，把它调大即可；调小则更积极）。
- **新增两张模块内表**：`AIRCRAFT_LAND_SELF_CENTER`（`Set<UUID>`，与 `AIRCRAFT_LAND_PLAN` 同在"这一场降落"的生命周期里，三处清缓存一并清）。
- 临时脚本（`tools/_r928_*.py`、`tools/_r928_edit.py`、`_r928_*.txt`、`_r928_*.log`）**已删**；仓库根目录既有的 `_*` 文件与预置目录未动。

## 台账

- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第六十七次同号重打。
- `DEBT.md`：不新增条目——① 是"把圆心选对"、② 是"把静默死锁改成自愈 + 留痕"，都不引入新的未验证功能面。
