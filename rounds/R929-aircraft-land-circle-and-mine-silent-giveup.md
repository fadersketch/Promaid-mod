# 实测九百二十九 固定翼"彻底落不下来"与挖矿"挖一半卡死"：回九百二十七 的降落链路 + 圈心=主人（不在圈内先飞进圈）+ 挖矿静默弃置路径全部留痕

> 跑道：**B 功能抢修**——开发者实机跑完第三轮报两件，其中①是**上一轮改坏了的**（九百二十八 把跑道圆心从"载具自己"改成"主人脚下"之后，降落从"落得太远"退化成"根本落不下来"）。
> 开发者指令（原话）：「1.现在女仆直接没有办法降落了。其实你上一版的降落逻辑就可以，只要把叛逆圈改成以主人为圆心的圈而非以女仆为圆心即可。若女仆不在圈内，则优先进入圈内。2.女仆还是会站在原地发呆不动。挖一半就卡那儿了。」
> 上一轮：`rounds/R928-aircraft-landing-owner-anchor-and-work-walk-stall.md`（九百二十八 把圆心改成主人 + 走路"导航在走、人没动"强制重发）。

## 排班

- **本轮主项**：两件都是"上一轮改完之后的残留/回退"，一次改完、一次部署。功能面（数值/矿表/建筑/对话/战斗）一律不动；版本号仍 **1.3.2**。
- 开发者给的 ① 口径已经写得很死，本轮**不再自己设计降落逻辑**，只做两件小事：**（a）把九百二十八 引入的"撞车"去掉**（让降落链路回到九百二十七 那条能落下去的版本）；（b）**圆心取主人**（这是九百二十八 已经做对的部分，保留）＋**不在圈内先飞进圈**（本轮新加的那一步）。判据：能指着日志说明"为什么九百二十八 之后反而落不下来"，并且改完后**每条判据都能在日志里看见**。
- ② 与九百二十八 同一条要求：**这一轮之后挖矿的每一次"放弃/卡住"都必须在日志里留下一条**，不能再有"静默站住 25 秒、一条诊断都没有"。
- 步骤与完成判据：
  1. ① 先回答"为什么九百二十八 之后她**完全**落不下来"（判据：指出**具体常量**与**具体几何**，不是"可能不稳定"）；
  2. ② 先回答"挖矿 25 秒静默"期间她**到底是不是在跑**（判据：用同段日志里**其它行为的节拍**证明"服务器在 tick、女仆在 tick"，把"引擎没跑"排除掉）；
  3. 改 Forge 树 → 4. 镜像 Neo 树（同一份模板 + 名字映射，逐锚点断言）；判据：两树 `javac` `EXITCODE=0`；
  5. 护栏 → 打 jar → 部署 4 实例；判据：全护栏 `FAIL 0`（`check_comment_style` 必须 `PASS`）；4 目标 `match=True`。
- 谁验收：开发者（本轮只部署、不跑回归；`## 验收` 记 `未验收`，并给出逐条实机复现步骤与日志关键字）。

## 需求 / 来源

- 现象①「现在女仆直接没有办法降落了。其实你上一版的降落逻辑就可以，只要把叛逆圈改成以主人为圆心的圈而非以女仆为圆心即可。若女仆不在圈内，则优先进入圈内。」
  ——九百二十八 改的是**圆心**（`ownerLandAnchor`，把 `aircraftLandPlan` 的候选框圆心从载具自己换到主人脚下），这个方向是对的、开发者本轮也确认保留；坏掉的是**同一次改动里连带的一个常量**。见 `## 定位与改法 ①`。
- 现象②「女仆还是会站在原地发呆不动。挖一半就卡那儿了。」
  ——九百二十七 删掉了"发呆看门狗"、九百二十八 补上了"导航在走、人没动 → 强制重发"，两者在本轮实机日志里**都没触发**（见下），可"卡死"照旧。本轮先**取证**（她卡住时到底还活着没有），再按取证结果改：**把每一条"放弃"路径都变成一条日志**，并给"连着三棵/三块都够不着"加一条"换框"出口。见 `## 定位与改法 ②`。

## 定位与改法

### ① 固定翼降落：九百二十八 的"钉住窗口"装不下九百二十七 的"进场几何"

**取证**（NeoForge_21.1.250 实机会话 `10:57–11:00`，`logs/latest.log` 第 1261–1389 行）：

```
10:58:00.969 固定翼降落·定跑道 接地点=(420,-60,-57) 方向=90度 距主人=0格
10:58:02.369 固定翼降落·盘旋（home 模式）绕圈等：高度拉向 -36.00 格 当前高度=-23.14格 竖直速度=-0.30 水平速度=2.72
10:58:07.465 固定翼降落·进场（home 模式）还没对准 → 飞出去重新对正 距接地点=92格 横向=13格 航向误差=170度 离地=23.55格 竖直速度=0.04
10:58:09.467 固定翼降落·定跑道 接地点=(423,-60,-56) 方向=225度 距主人=0格
10:58:09.568 固定翼降落·定跑道 接地点=(423,-60,-56) 方向=225度 距主人=0格
10:58:09.669 固定翼降落·定跑道 接地点=(423,-60,-56) 方向=225度 距主人=0格
…（同一条，每 ≈100 毫秒一条，方向块 225度 → 10:58:10.466 起 135度 → 10:58:12.267 起 45度 → 10:58:24.163 起 315度 …）
10:58:12.464 固定翼降落·进场（home 模式）还没对准 → 飞出去重新对正 距接地点=220格 横向=48格 航向误差=33度 离地=21.75格 竖直速度=0.18
```

**定位**（两树，`MaidMountCompat`）：

- `AIRCRAFT_LAND_PLAN_KEEP_D`（九百二十八 的值 = **200** 格）是"跑道规划被钉住后，允许她被带走多远；超过就作废重规划"。
- 九百二十七 的**进场几何**是：没对准就**沿跑道轴线飞出去重建距离**，重入瞄准点钉在「接地点外 `AIRCRAFT_LAND_OUT_D(90) + AIRCRAFT_LAND_OUT_LEG(90) = 180` 格」的中线点上。
- 两条一叠：她**沿着那条 180 格的腿往外飞**的时候，到"被钉住的接地点"的距离**本来就要逼近 180+**（转向还有外扩，日志实测 **220** 格）。而 900 格的窗口只有 **200** ⇒ **她一往外飞就踩线** ⇒ `aircraftLandPlan` 每次调用都判定"漂太远"⇒ **作废重建**；重建时方向又按**她当时的机头**重挑（`AIRCRAFT_LAND_ALIGN_DEG = 45` 顺机头优先）⇒ 她一边飞一边转，方向就跟着换（日志里 225→135→45→315 那四块）。
- 九百二十八 那次改动还**同时**把"定跑道"这一行从节流的 `logDrive`（5 秒/只）换成了不节流的 `PromaidLog.log`，所以这个"每拍重建"在日志里就成了**每 100 毫秒一条的刷屏**——这既是现象本身，也是本轮取证能一眼看到它的原因。
- **另外**：规划失败（`aircraftLandPlan` 返回 `null`）的那条路**不写缓存**，于是失败时每拍都要重扫一遍 48 格方框（625 列 × 每列 9 次高度图 ≈ 5600 次/拍），没有任何退避。

**改法**（两树，同一份逻辑；**链路回到九百二十七，只动"窗口"和"退避"**）：

- **把钉住窗口改成"装得下那条几何"**：`AIRCRAFT_LAND_PLAN_KEEP_D` 由硬编码 `200` 改成**派生值**
  `AIRCRAFT_LAND_OUT_D + AIRCRAFT_LAND_OUT_LEG + AIRCRAFT_LAND_RADIUS + 40.0` = **268** 格（90 + 90 + 48 + 40），
  让"钉住整场"这条口径真正成立（`AIRCRAFT_LAND_PLAN_KEEP_TICKS = 6000` 拍 = 5 分钟不变）。注释里把上面这条推导写清（"窗口必须装得下进场段"，以及"旧值 200 → 每拍重建 → 永远落不下来"）。**这是本轮让降落恢复的关键一条**；除此之外 `aircraftLandPlan` / `aircraftLandFinal` / `holdAircraftPattern` / 条带选址 / 下滑道 / 接地段**一个字节没动**。
- **给规划失败加退避**：新增 `AIRCRAFT_LAND_RETRY`（`Map<UUID,Long>`）与 `AIRCRAFT_LAND_RETRY_TICKS = 20L`（1 秒）。规划失败时记 `now + 20`，下次调用若还没到期**直接返回 `null`**（不重扫那 625 列）；成功一次、以及三个清缓存点（`endAircraftLanding` / `resetAircraftLanding` / 复飞作废处，与九百二十八 的 `AIRCRAFT_LAND_SELF_CENTER` 同一处）一并清掉。
- **把刷屏改回节流**：那一行 `固定翼降落·定跑道 …`
  （现在含 ` 距主人=N格`，九百二十八 加的）从 `PromaidLog.log` 换成 `logDrive(mount, …)`（**5 秒/只**），与降落链路其它几行同口径。
- **新增"不在圈内先飞进圈"（开发者本次点名要的那一步）**：`aircraftHomeLanding` 开头、在**还没有活规划**时先问一句
  `aircraftOutsideLandCircle(mount, maid)`（她到**圈心 = 主人脚下**的水平距离 > `AIRCRAFT_LAND_RADIUS = 48` 格）；
  超了就调 `aircraftFlyToCircle(mount, maid, why)` 并**直接 `return true`**——
  目标点 = **主人的水平位置**、高度按进场高度 `AIRCRAFT_LAND_PATTERN_ALT` 压（地形净空线由 `aircraftSafeTargetY` 兜底），执行器就是巡航那一套 `aircraftCruise`（航向 + 滚转 + 高度环 + 速度环），留痕一行
  `固定翼降落·进圈（…）还没进跑道圈 → 先朝主人飞 距主人=N格 高度=…格 竖直速度=… 水平速度=…`。
  **为什么"不交回调用方"（不 `return false`）**：home 档里那句话落到的是"飞行载具悬停"（`MaidAirCombat.holdHere`）或"直接停住"（`MaidRideKit.stopNavigation`），**都不带她去主人那边**——九百二十六 的"飞走、一去不复返"就是当年把固定翼交回巡航档的结果（`RideBindManager:1728-1745`）。
  **只在她还没有活规划时才拦**（`AIRCRAFT_LAND_PLAN.get(uuid) == null`）——正在进场 / 正在盘旋的**不会被这一句劫走**，避免"进圈这一步"和"进场那一步"互相打架。

### ② 挖矿/伐木："卡死 25 秒、零诊断"——先把"她是不是在跑"钉死，再把每条放弃路径都留痕

**取证**（同一次会话，`logs/latest.log` 第 1776–1795 行）：

```
10:59:14.349 mine behavior start: maid=af629870-…
10:59:16.104 mine chain filled (blocking): … block=minecraft:stone queued=16 limit=16
10:59:16.111 mine chain burst (blocking): … block=minecraft:stone broken=16
…（productive 一路到…）
10:59:25.470 mine chain empty: … block=superbwarfare:scheelite_ore (no adjacent ore)
10:59:25.909 mine autoCollect: … pos=BlockPos{x=-1417, y=31, z=-682} drops=1
10:59:25.909 mine chain filled (blocking): … block=minecraft:stone queued=16 limit=16
10:59:25.914 mine chain burst (blocking): … block=minecraft:stone broken=16     ← 最后一条动作
   ……（25.3 秒，挖矿链路一条日志都没有）……
10:59:29.468 [重锤门] 大正女仆酒狐 不触发: 主手不是重锤 | 主手=cataclysm:infernal_forge 风弹=0 目标=无 地面=true 冷却剩=0t
10:59:34.516 [重锤门] …（同样一行）
10:59:34.586 [插火把] 大正女仆酒狐 身上一根火把都没有（背包 / 精妙背包 / 主手 / 副手都问过了）→ 不插
10:59:35.088 [FriendlyFireGuard] friendly-fire hate-clear: maid=大正女仆酒狐
10:59:39.550 [重锤门] …
10:59:44.551 [重锤门] …
10:59:44.684 Saving and pausing game...
10:59:47.100 [FriendlyFireGuard] …
10:59:48.396 [刷怪笼] 大正女仆酒狐 附近没扫到刷怪笼（搜索半径 12 格、上下各 4 格）
10:59:49.598 [重锤门] …
10:59:51.190 离场 大正女仆酒狐 离开世界（id 25，reason=DISCARDED，维度=minecraft:overworld）
10:59:53.313 重同步 … 她是新实体（收放魂符/重新召唤，id 25→1337）
10:59:53.359 mine behavior start: maid=af629870-…          ← 新实体重新开始
   ……（再 10.8 秒静默）……
11:00:04.199 离场 …（id 1337，reason=DISCARDED，…）
11:00:14.767 重同步 … id 1337→1369
11:00:14.811 mine behavior start: maid=af629870-…
11:00:15.957 mine chain filled (blocking): … ← 又开始productive
```

**从这组数据能确定的**：

1. **服务器在 tick、女仆在 tick、她的其它行为都在跑**——静默期里 `重锤门`（5 秒一条）、`插火把`、`刷怪笼`、`FriendlyFireGuard` **一条不落**。所以"她卡住"**不是引擎没跑 / 区块没加载 / 实体被冻结**。
2. **挖矿链路本身**：`mine behavior start` **没有**再出现（说明**行为没被停、也没重启**）、`mine stuck-reset` / `mine relocate` / `mine box empty but home-restricted` **0 条**、九百二十八 新加的 `mine behavior stop:` 与 `work walk stuck:` **0 条**。⇒ 她**带着活目标、安安静静地**停在那儿，**没有任何一条诊断**。
3. **她最后是被"重新召唤"救回来的**：`离场 reason=DISCARDED` + `重同步 … id 25→1337→1369`（两个循环），每次重召后**先干几秒活、再静默**。⇒ "卡死"是**可复现的**、不是偶发。
4. 静默前最后一条相关诊断是 `mine chain empty … superbwarfare:scheelite_ore (no adjacent ore)`——一次"目标矿的连锁扩展没找到相邻矿"。

**定位**（`MaidMineBehavior` / `MaidWoodBehavior`，两树）：把"放弃当前目标"的每一条 `return` 逐条读了一遍，**它们全都是静默的**——六条：**锚点解析不出来**（`resolveAnchor` 给 `null`）、**超时够不着**（`TARGET_SINCE` 超时）、**视线被挡**、**硬挡路**（`MINE_HARD_BLOCK_REPORT` 那条）、**2 秒没挪窝**（`MINE_WEDGE_TICKS` / `WOOD_WEDGE_TICKS`，九百二十七 加的）。九百二十八 加的 `work walk stuck` 补的是**"导航还在走、人没动"**那一半；**"导航已经 `isDone()`（压根没有路）+ 人没动"**那一半（也就是上面第 2 条那种形状）**照旧静默**——这就是"卡住却一条日志都没有"的直接原因：她**确实在按设计弃置/等下一轮**，只是**我们没把这件事说出来**。

**改法**（两树，矿/木共用一个模板）：

- **每条放弃路径都留一条**：新增 `giveUpLog(EntityMaid, String reason, BlockPos, long)`（100 拍 = 5 秒/条限频），打一行
  `mine give-up: maid=… reason=… target=… dist=… feet=… progress=…`；
  在**六条**放弃路径上全部接上（`锚点解析不出来` / `超时够不着` / `视线被挡` / `硬挡路` / `2秒没挪窝`）。
- **补上"导航没路 + 人没动"那一半**：`MaidWorkBehavior.setWalkTarget` 里九百二十八 的那道闸改成**两种情形都留痕**——原来是"`!isDone()` 且站住才 `stop + retarget + reportWalkStuck`"；现在拆出 `sameWalkTarget(...)`，**"目标没变 + 脚下没动"**（不论是"导航还在走"还是"导航已 `isDone()`"）都走同一条"停导航 + 重发 + `reportWalkStuck`"。
- **给"连着够不着"加一条出口（换框）**：矿/木各自新增 `wedgeStreak`（200 拍窗口内的连续弃置计数）与 `lastWedgeAt`。同一只女仆在 200 拍内**连续第 3 次**因为"2 秒没挪窝"弃置、且**这一窗内一块都没破坏**时，判定"**这个框里没一个能走到的**" →
  清掉框缓存（矿 `ORE_CACHE` + `MINE_SCANS`；木 `WOOD_CACHE` + `WOOD_SCANS`）、留一行
  `mine box unreachable (3 连弃置、零破坏) → fresh box: maid=… anchor=…`、再走**已有的** `slideAnchor(...)` 迁移一格（home 档不迁、只留痕，口径不变）。**成功破坏一块就把 `wedgeStreak` 清零**（`this.chainBlock = state.getBlock();` 之前插一句）。
- **顺带修一处"两树不同口径"的既有错**（Neo 独有、**非本轮引入**）：`promaid_src_neo` 的 `MaidMineBehavior`（2 处）与 `MaidWoodBehavior`（1 处）用 `state.getShadeBrightness(level, pos)`（**亮度**）当"硬度"喂进挖掘耗时公式；同树的 `MaidSpawnerTorchBehavior` 一直在用正解 `state.getDestroySpeed(level, pos)`（那行注释就写着「硬度取正解 getDestroySpeed，不是 getShadeBrightness」），Forge 侧也一直是正解（`state.m_60734_().m_7749_(state, level, pos)`）。本轮把 Neo 这三处改成 `getDestroySpeed`，两树同口径。

## 触及文件

- `promaid_src` / `promaid_src_neo` × `com/maidsmart/combat/MaidMountCompat.java`（① `PLAN_KEEP_D` 派生 = 268 / `AIRCRAFT_LAND_RETRY` 退避 / 定跑道行改 `logDrive` / `aircraftOutsideLandCircle` + `aircraftFlyToCircle` + `aircraftHomeLanding` 里"没活规划才拦"的那道闸）
- `…/task/MaidWorkBehavior.java`（② `setWalkTarget` 两种"站住"都 `stop + 重发`；`sameWalkTarget` 抽出）
- `…/task/MaidMineBehavior.java`（② `giveUpLog` + 六条接上 / `wedgeStreak` 换框 / Neo 硬度正解 2 处）
- `…/task/MaidWoodBehavior.java`（② 同款 `giveUpLog` / `wedgeStreak` 换框 / Neo 硬度正解 1 处）
- `rounds/RELEASES.tsv`（两条 1.3.2 `workspace` 行 sha256 + 第六十八次同号重打）
- `rounds/R929-aircraft-land-circle-and-mine-silent-giveup.md`（本文件）

## 验证

- **两树编译**：`compile_promaid.bat` → `EXITCODE=0`（86 个警告全是既有的 `ResourceLocation` 过时提示）；`compile_neo.bat` → `EXITCODE=0`。本轮编译**失败过两轮**，两个错都抓到并修掉：
  1. **`非法前向引用`**（`MaidMountCompat`：Forge 2839 / Neo 2489）——`AIRCRAFT_LAND_PLAN_KEEP_D` 的派生表达式引用了**声明在它后面**的 `OUT_D` / `OUT_LEG` / `RADIUS`。修法：把声明**整体移到** `AIRCRAFT_LAND_OUT_LEG` 之后（不是改成硬编码数字，保留"派生"这条可追溯性）。
  2. **`找不到符号 变量 maid`**（`MaidWoodBehavior`：Forge 696 / Neo 695）——`RECENT_DISCARD.computeIfAbsent(maid.maid.m_19879_() …)`，模板占位符本身已带 `maid.` 前缀又被外层补了一次。修成 `maid.m_19879_()`（Neo `maid.getId()`）。
- **镜像自查**：`aircraftOutsideLandCircle` / `aircraftFlyToCircle` / `giveUpLog` 三处逐行读回两树，确认 Forge 侧一律 SRG（`m_9236_()` / `m_20185_()` / `m_20189_()` / `m_20186_()` / `m_146908_()` / `m_20148_()` / `m_7949_()`），Neo 侧一律 Mojang（`level()` / `getX()` / `getZ()` / `getY()` / `getYRot()` / `getUUID()` / `immutable()`），无交叉泄漏。
- **全护栏**：`check_comment_style` **PASS**（931 文件 / 0 处）——`aircraftFlyToCircle` 的 javadoc 第一版 **9 行 > 8** 被它拦下（`LONG-BLOCK`），压缩到 **8 行**后通过；`check_arch` / `check_catch_swallow` / `check_comment_dup` / `check_dev_voice` / `check_i18n_keys`（两树 0/0，config 键 621/627）/ `check_panel_coverage` / `check_preset`（DIY 关闭 **172/176**，与九百二十八 基线一致——本轮不增删开关）/ `check_reflection` **FAIL 0 / WARN 0**；`check_workflow` **FAIL 0 / WARN 3**（历史遗留：PUBLISH_GUIDE 版本、1.2.4 归档哈希、13 个更早 jar 未登记——与本轮无关）。`check_tree_mirror`（只读、不作闸门）照旧。
- **两个 jar**：forge **17583428** 字节（sha256 `3958995B…40A084`）；neoforge **17619746** 字节（`14E87CE9…226960`）。
- **装机**：`deploy_all.py` → 4 个本机实例全部 **`match=True`**（1.21.1-NeoForge_21.1.250 / 1.20.1-Forge_47.4.10 / 龙之冒险：新征程v2.4a / mc_server_test pack1201）；旧包备份在 `patched/backup_old/`。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 `workspace` 行 sha256 更新为新字节，并追加「第六十八次同号重打」；九百二十八 的旧字节 `787C5567…65B309`（forge）/ `F5F2205B…95B156`（neo）作废。
- **未跑实机回归**（先停在这，等开发者指挥）。

## 验收

- 状态：**未验收**（已部署：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
  - ① **能不能落下来 + 落在谁身边**：让她开固定翼（Tom F6F / AC-130H / 汤姆6 那一类）在**离主人几十格以上**进 home 档。**判据**依次应当看到：
    1. `固定翼降落·进圈（home 模式）还没进跑道圈 → 先朝主人飞 距主人=N格 …`（**N 从大到小**——这是本轮新加的"不在圈内先飞进圈"）；
    2. 进圈后转 `固定翼降落·定跑道 接地点=(…) 方向=…度 距主人=N格`（这个 **N 应当是十几~三十几格**，"绕一圈就能回来"的量级）；
    3. 之后 `进场 … 距接地点=…格` → `下降段` → `接地段 离地=…` → `固定翼降落完成：已接地 → 停住不动`。
    **关键回归点**：`定跑道` 这一行**不该再刷屏**——同一条跑道、同一个方向最多每 **5 秒**一条（`logDrive` 节流）；若**方向每 1~2 秒就换一次**、或 `进场 距接地点` 长期在 **220 格以上**，说明本轮那条派生窗口还不够（请把那几行贴回来）。
  - ② **挖一半卡死**：给她挖矿任务。**判据**：一旦出现停滞，日志里**必然**出现下面之一（本轮之前是**一条都没有**）：
    - `mine give-up: maid=… reason=… target=… dist=… feet=… progress=…`——她把当前目标**弃了**（`reason` 会写明是"锚点解析不出来 / 超时够不着 / 视线被挡 / 硬挡路 / 2秒没挪窝"哪一条）；
    - `mine box unreachable (3 连弃置、零破坏) → fresh box: maid=… anchor=…`——**连着 3 次都够不着**，她已经**换框**了（随后应当看到 `mine box empty …` 那条迁移留痕或新框的 `mine chain filled`）；
    - `work walk stuck: maid=… … path=true/false -> stop+retarget`——"导航在走/没路"两种站住都在这里（九百二十八 只覆盖前者）。
    **若她仍不动、而这三条一条都没有**：说明是**第四种**路径（请把当时前后 20 行贴回来，尤其看有没有 `mine behavior stop:`——有它就是"行为被停"，没有它就是"另有原因"）。正常挖掘**不会**出现这些行（都是节流 5 秒/条、且只在异常路径上打）。
  - 判定：逐条按上面现象观察即可。若某条仍复现，请贴 `logs/promaid.log` / `logs/latest.log` 里对应该条的几行（含时间戳）。

## 边界（没动什么）

- **没动**：条带选址与条带判据（`stripRelief` / `AIRCRAFT_LAND_STRIP_*`）、候选点排序与 `AIRCRAFT_LAND_TRY`、八向两遍顺机头挑选（`AIRCRAFT_LAND_ALIGN_DEG`）、**进场段瞄准点与那条固定外点（`OUT_D + OUT_LEG`）本身**、下滑道与接地段高度闸、盘旋兜底 `holdAircraftPattern`、跑道钉住时长 `PLAN_KEEP_TICKS = 6000`；挖矿的锚点迁移 `slideAnchor`、2 秒"走不到就换目标"闸（`MINE_WEDGE_TICKS` / `WOOD_WEDGE_TICKS`）、`MINE_REQUIRE_PICK` / `MINE_HARD_BLOCK_REPORT` / `RECENT_DISCARD` / 目标超时 `TARGET_SINCE`、`findOre` 与分帧扫描预算。
- **口径变更（有意，非回归）**：
  1. `AIRCRAFT_LAND_PLAN_KEEP_D` **200 → 268**（派生：`OUT_D + OUT_LEG + RADIUS + 40`）——这是本轮让降落恢复的关键一条；
  2. 规划失败**新增 20 拍退避**（`AIRCRAFT_LAND_RETRY`），失败时不再每拍重扫 625 列；
  3. `定跑道` 那一行改回 **5 秒节流**（`logDrive`）；
  4. **新增"不在圈内先飞进圈"**（`aircraftOutsideLandCircle` → `aircraftFlyToCircle`），**只在她没有活规划时**才拦；
  5. `setWalkTarget` 的"站住"判据由"导航还在走"扩到**"导航在走 / 导航已 `isDone()` 两种"**；
  6. 新增 **`wedgeStreak` 3 连弃置换框**出口；
  7. Neo 树三处**硬度取正解** `getDestroySpeed`（与 Forge 同口径；属修既有错，**不是**改玩法数值）。
- **没新增开关**；DIY 关闭条数基线仍 **172/176**；版本号仍 **1.3.2**（`WORKFLOW.md` §5「验收前不升号」）。新增常量只有 `AIRCRAFT_LAND_RETRY_TICKS = 20L`、`wedgeStreak` 的 200 拍窗口与阈值 3（都是模块内常量，**未经实机标定**——"3 次"若实机看来太急/太钝，改阈值即可）。
- **新增两张模块内表**：`AIRCRAFT_LAND_RETRY`（`Map<UUID,Long>`，与 `AIRCRAFT_LAND_PLAN` 同一生命周期，四个清缓存点一并清）；挖/木各一个 `long wedgeStreak` / `lastWedgeAt` 实例字段。
- 临时脚本（`tools/_r929_*.py`、`_r929_*.txt`、`_r929_*.log`）**已删**；仓库根目录既有的 `_*` 文件与预置目录未动。

## 台账

- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第六十八次同号重打。
- `DEBT.md`：不新增条目——① 是"把九百二十八 引入的窗口撞车修回去 + 补开发者点名的那一步"，② 是"把静默弃置改成留痕 + 给够不着加一条换框出口"，都不引入新的未验证功能面。
