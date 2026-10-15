# R985 · 十条整改：搜索框 · 下架三张随包蓝图 · 等价替代跳过完工重放 · 红石重放掉落物 · 滑翔高度配置 · 「接触到区块」 · 指挥棒光标开关 · 常用页跳转 · 单小类直进 · 战斗滞留兜底

## 排班

1. 开发者十条（见「需求 / 来源」），全量落在 实测九百八十五。
2. ①②⑤⑥⑧⑨ 属「先做一半、这次补完」：逐条复核两树实际代码（搜索框边框写在 `m_94182_(true)` 上、
   随包名单 82 → 79、固定翼加高接配置、区块判定三轴各放宽一格、常用页三颗新 `BtnRow`、`soleSectionOf` 直进），
   把漏的、错的补齐。
3. ③④ 是建造完成链路的两个隐患（完工「整体重放」）：等价替代会被换回原材料、发射器 / 投掷器重放后吐一地掉落物。
4. ⑦⑩ 在战斗 / 骑乘两侧：指挥棒光标总闸（只闸「点亮」）与「战斗模式未接敌滞留」兜底。
5. 两树镜像 → 编译 → 14 护栏（`check_comment_style` 一度 FAIL 7，见「验证」）→ 打包 → 部署 → 回归 → 文档台账 → 清临时。

## 需求 / 来源

- ①「搜索名称怎么连个搜索框都没有」
- ②「删除默认建筑蓝图中的中世纪刷石机，圆形水族箱，水立方。」
- ③「我突然想到我的建造完成之后重放这个功能有一个隐患。如果玩家使用等价材料族替换了材料最终把东西建造了出来。那最后还是会被重新放置。导致整个建筑变了个样。需要做一个额外的处理措施：如果玩家使用了等价材料组进行替换，那么这个功能是不生效的。」
- ④「红石重放的时候会产生掉落物。」
- ⑤「卓越前线滑翔式载具的盘旋高度没有相关配置项目。」
- ⑥「建造功能之前有个设定是必须要处于区块内，才可以对区块进行调控。这个似乎有一些太过严苛了。有一些只有一格建筑高度。玩家怎么都没有办法完全进入。最好是改成：接触到这个区块」
- ⑦「骑乘指挥棒加一个是否渲染载具和女仆光标的功能。」
- ⑧「常用与一键预设里面怎么只有对压缩盒的跳转？其他的物品怎么没有跳转呢？」
- ⑨「一个大类里面就塞一个小类也太可怜了吧。那有设小类的必要吗？」
- ⑩「女仆在某一战斗模式但是未接敌和发现威胁的状态下出现了严重的问题。会直接卡在原地不动。不再跟随主人或进行移动。」

## 改法

### ① 蓝图目录搜索框：改回有边框

- `BlueprintBookScreen`：`searchBox.m_94182_(true)`（有边框）+ 文字色 `0xFFFFFF`。
- v1.5.376 的无边框只解决了「面板底色深、默认深色字看不清」，代价是**整框看起来不存在**——
  空值时只有一行「搜索建筑名称…」飘在标题下面，玩家找不到输入口。原版边框自带浅灰描边与深底，
  任何底色上都认得出是输入框；文字改白，两头都保住。

### ② 随包蓝图 82 → 79：下架三张

- 两树 `assets/maid_smart/bundled_blueprints/` 删 `红石__中世纪刷石机.litematic`、`水__圆形水族箱.litematic`、
  `水__水立方.litematic`，`BlueprintFileIo.BUNDLED_BLUEPRINT_FILES` 同步删三行（名单与目录各 **79**、逐项一致）。
- 已经在 `config/maid_smart/blueprints/` 落过副本的旧安装：`cleanupLegacyBuiltinFiles` 的 legacy 名单加上这三张，
  首启连副本一起删（模组自己解压的，不算玩家导入内容），并清空外部注册与材料 / 描述 / 尺寸缓存。

### ③ 等价替代 → 完工重放不生效（两道闸）

- 判据一（主）：`MaidBuildBehavior` 完工时把 `plan.progress.altUsed`（她缺料时用等价族替代的**逐步记录**，
  内存态）传进 `BlueprintLib.snapFinish(level, origin, plan, live, altSubstituted)`；非空即整段跳过重放。
- 判据二（兜）：普通建筑（`!live`）在动世界之前先扫一遍图纸格——格里放着的既不是图纸方块、也不是空气、
  又确实是 `BlueprintLib.isEquivalent` 等价族成员（且不是 `isBuiltEquivalent` 的泥土 / 草方块白名单）时，
  记一格替代并打印样例（最多 3 格）；命中即跳过。防「替代记录只在内存、重启后接着建」的那一批漏网。
- 跳过时写 INFO 日志（用了多少格、样例 `原材料→实际`），并**直接返回 false**，清空 / 重放两趟都不跑——
  建筑保持玩家材料库实际给得起的样子（这正是需求：整栋别变样）。
- 机器 `live` 模式不扫世界（活塞 / 水在建造中途本就与图纸不同，会大面积误判），只认 `altUsed`。

### ④ 红石重放不再吐掉落物

- 真因：`normalizeMachineState` 剥掉了 `power|lit|extended|powered|locked|unstable|level`，**但不剥
  `triggered`**。图纸里 `triggered:"false"`（未触发）的发射器 / 投掷器被原样重放后，重放完成时的
  邻接更新把它当成「刚被触发」→ 调度一次发射 → 盒子里存的物品落一地（时点在 `cleanupDrops` 之后，
  捡不回来）。
- 修法：`BlueprintMachineFinish.safeDispenserState(blockId, stateSnbt)`——只对 `:dispenser` / `:dropper`，
  把 `triggered` 一律改写成 `"true"`（`replaceAll` 已有键；没有键则在 SNBT 尾部补 `,triggered:"true"`，
  空 compound 也处理好）。建造主循环 / 延迟循环（`MaidBuildBehavior` 两处）与完工重放（`snapFinish`）
  统一走它；`BlueprintLib` 加同名转发。`triggered=true` 就是原版机器「刚被玩家右键 / 通电过一次」的稳态，
  不再有新的发射调度。

### ⑤ 卓越前线滑翔式载具：盘旋高度进面板

- 新配置 `combat.ride.fixedWingAltBonus`（Double，默认 **30**，范围 0~80，面板行
  **「固定翼·额外加高（滑翔式载具）」**，挂在「战斗与自保 → 空袭数值 → ③ 盘旋与补高」）。
- `MaidMountCompat`：原写死常量 `AIRCRAFT_ALT_BONUS = 30.0` 保留作配置读不到时的兜底，
  新增 `aircraftAltBonus()` 读配置；巡航调用点两处（胡萝卜高度 = 参照物 + `followAlt`/`fightAlt` + 本项；
  地形净空线 `columnFloor` 用 `AIRCRAFT_TERRAIN_CLEAR + 本项`）一起改读方法。
  口径不变：**两个约束一起抬**（780 的教训——只抬胡萝卜高度，超平坦上真正管她的是净空线）。
- 4 份 lang 补 `config.promaid.ride.fixedWingAltBonus`（中 / 英）。

### ⑥ 区块调控：「完全在内」→「接触到区块」

- 服务端 `BlueprintBookNetworking.findPlayerPlan`：三轴各向外放一格（`x0-1 .. r[3]`、`r[1]-1 .. r[4]`、
  `z0-1 .. r[5]`）；客户端 `BlueprintBookScreen.playerPlanId` / `playerInPlanRegion` 同口径。
- 一格高的建筑玩家永远进不去体积内——现在贴着区块边界站着就算「在里面」，暂停 / 改速 / 取消 / 详情都可用。

### ⑦ 骑乘指挥棒：光标（金色描边）开关

- 新配置 `combat.ride.batonMark`（Boolean，默认 **true**，面板行「**骑乘指挥棒·光标（金色描边）**」，
  挂在「物品 → 骑乘指挥棒」，紧跟「独占右击」）。
- `RideBindManager`：`batonMarkOn()` 总闸**只闸「点亮」**——`mark`（绑定链路标记）与 `markPending`
  （待选光标 + `TAG_PENDING_MARK` 留痕）在写之前先查闸；`unmark` / `clearPendingMark` **永不查闸**
  （关闸后旧光标必须熄得掉；标记簿记与 `restore` 链一个不动）。
- `sweepStaleMarks` 的 5 秒清扫节拍里加：关闸时把已点亮的全部 `unmark` + `clearPendingMark` 清掉——
  开关一关，最多 5 秒全屏发光自行熄掉；重新打开立即恢复旧行为。
- 4 份 lang 补 `config.promaid.ride.batonMark`。

### ⑧ 常用页：四件道具全给「前往 →」

- `commonRows`：原只有「压缩盒 → 前往」，补三颗 `BtnRow`——**武装拴绳（二号位）**（→ `TETHER`）、
  **骑乘指挥棒**（→ `RIDE`）、**饰品「大运」**（→ `DA_YUN`）；顶部 `NoteRow` 文案同步改成「点『前往』进各自的配置页」。

### ⑨ 单小类大类不再多一层

- 新增 `soleSectionOf(Group)`：大类下「能进去的小类」只有一个时返回它（`Section.EXTRA` 在
  `extraItems()` 为空时不计数——那一栏本来就不列）；两个以上返回 null。
- 两个调用点：①大类按钮点击——`only != null` 直接进那一页（不再画「选择小类」）；②页内「← 返回分类」——
  单小类时直接回大类列表（`inGroup=false` / `inHome=true`），不再回一个只有一行的假小类页。

### ⑩ 战斗模式未接敌时的滞留兜底

- 真因：原版攻击类任务**不跟随主人**。她在**本模组指派的战斗任务**（`ASSIGNED_TAG` == 当前任务 UID）上、
  附近无目标也无威胁时，既不跟随也不干活；而既有的还原扫描（`AutoCombatSwitch.onServerTick`）先要看
  `COMBAT_ACTIVE_TAG` 标记才处理她——标记被别的环节清掉（排班开启 / 傀儡模式退出 / 存档重读……）之后，
  再没有任何路径管她的任务，于是永久站桩。
- 修法：`onServerTick` 里加一道**不看标记**的看门狗（每 20 tick 随既有节拍跑一遍全维度女仆）：
  她在我们指派的攻击任务上 + 非排班 + 非自保 + `CombatWorkRange.inCombat` 为假 + `hasThreatNearby` 为假 +
  `nearestThreatDist > AutoCombatPools.TARGETING_RANGE`，条件持续 `STRANDED_COMBAT_TICKS = 200`（10 秒）
  → 补回 `COMBAT_ACTIVE_TAG`，交给下面那条久经修补的还原链（解析原任务 / 重应用排班 / 找不到落 idle，全程日志）。
  计时存 `STRANDED_COMBAT_SINCE`（实体 id → 首次判定时刻，ConcurrentHashMap，条件不成立即清、>4096 自清）；
  玩家自己切的战斗任务不在射程内（任务 UID 与指派记录不符即跳过）。

## 触及文件

- 两树：`build/BlueprintBookScreen.java`（①⑥ 判定 + ⑦无关）、`build/BlueprintBookNetworking.java`（⑥）、
  `build/BlueprintFileIo.java`（②）、`build/BlueprintLib.java`（③④ 转发）、`build/BlueprintMachineFinish.java`（③④）、
  `build/MaidBuildBehavior.java`（③ 调用点 + ④ 两处落地）、`combat/AutoCombatSwitch.java`（⑩）、
  `combat/RideBindManager.java`（⑦）、`combat/MaidMountCompat.java`（⑤）、`config/MaidSmartConfig.java`（⑤⑦）、
  `config/PromaidConfigScreen.java`（⑤⑦⑧⑨）、`assets/maid_smart/lang/{zh_cn,en_us}.json`（两树各 2 文件、
  各补 2 键：`config.promaid.ride.fixedWingAltBonus` 与 `config.promaid.ride.batonMark`）、
  `assets/maid_smart/bundled_blueprints/`（−3 文件）、
  `assets/promaid/panel_coverage.txt`（重生成）。
- 工具：`tools/build/gen_panel_coverage.py`、`tools/verify/gen_switch_inventory.py --write`（重跑）。
- 文档：`rounds/R985-*.md`、`rounds/RELEASES.tsv`、`DEV_GUIDE.md`、`机制详解.md`、
  `release_body.md` / `release_body_neo.md` / `RELEASE_NOTES_v1.3.2.md` / `store/release_notes_1.3.2.md`
  （+ 桌面 `Promaid文本\v1.3.2 骑乘修整与全部配置开关可见.md`）、`ARCHITECTURE-SWITCHES.md`。

## 验证

- 两树 `javac EXITCODE=0`（`_c12` 修掉 ⑤ 注释里的半角引号 → forge 通过；`_c11` 修掉 neo 误用 SRG `m_6084_`
  → neo 通过；随后 `_c13`~`_c16` 全是注释整形，再编译一次仍 0）。
- 14 护栏：`check_comment_style` 一度 **FAIL 7**（本轮新注释块超 8 行 + 两处「玩家反馈」违禁词），
  逐处压缩 / 改口径（`BlueprintBookNetworking.findPlayerPlan` 9→6 行、`AutoCombatSwitch` 看门狗注释 11→8 行
  且去掉违禁词、`MaidMountCompat.AIRCRAFT_ALT_BONUS` 10→6 行并修坏缩进、`RideBindManager` 待选光标 javadoc
  与 `batonMarkOn` javadoc 换位/去重）→ **PASS**；其余 13 项 `FAIL 0`（`check_tree_mirror` 只读不设门槛）。
- 包体：forge **17,892,182** 字节 `9eb9e7ac…0c4a1b`；neo **17,939,717** 字节 `35f5159e…0b79b2`。
- 部署：`deploy_all.py` 4 个目标全 `match=True`（`elevated rc: 0`）。
- 回归：`test_server.py` / `test_maid_load.py` 两加载器各两件，四件全 `verdict: PASS`。
- 蓝图：两树 `BUNDLED_BLUEPRINT_FILES` 条数 == `bundled_blueprints/` 文件数 == **79**，集合相等，三张下架文件零残留。

## 验收

- 蓝图目录：标题下方是一个**看得出边框**的输入框，点一下就能打字；输入即过滤（清空恢复全量）。
- 干净安装的建造目录里**看不到**中世纪刷石机 / 圆形水族箱 / 水立方；旧安装首启后这三张的残留副本也没了。
- 用等价族材料（圆石替石头这类）建完一栋，完工时**不会**被换回图纸原材料；没替代过的建筑照旧整体重放。
- 盖带发射器 / 投掷器的红石机器蓝图：完工后**不再有物品掉在机器旁**；机器照常工作（要发射就自己通电点火）。
- 「战斗与自保 → 空袭数值 → ③ 盘旋与补高」里有「固定翼·额外加高（滑翔式载具）」，默认 30；改大 / 改小 / 填 0
  后固定翼盘旋高度跟着变，超平坦上也真抬起来（净空线一起抬）。
- 贴着（含紧贴边界外一格）区块站着就能暂停 / 改速 / 取消 / 看详情；离远一格则不行。
- 「物品 → 骑乘指挥棒」里能关「光标（金色描边）」：关掉不再点亮；已经亮着的 5 秒内自动熄掉；
  绑定关系、`restore` 还原、排班一切照旧（只是不发光）。
- 「常用」页四件道具都有「前往 →」；「物品」大类下的「骑乘指挥棒」等小类仍是一进一层（多小类不受影响）。
- 某个大类真的只剩一个小类时，点大类直接进那一页，「← 返回分类」直接回大类列表。
- 女仆被指到战斗任务、附近没敌人也没威胁：10 秒后她会自己接回原任务 / 排班 / 待机，跟着主人走，不再原地站桩。

## 边界

- ③ 的跳过是**整段跳过**（不重放也不清空）——等价替代的那一版就该原样保留；机器 `live` 模式不做世界扫描
  （活塞 / 水会误判），只认 `altUsed`。
- ④ 只改「放置与重放时写什么状态」，不改红石逻辑本身：图纸里本来就带电的发射器重放后该怎么响还怎么响
  （`BuildTntGuard` / `recalcRedstone` 链路未动）。
- ⑤ 只作用在**固定翼（滑翔式）**这一档；直升机 / 扫帚 / 鞘翅的既有高度项（悬停高度 / 接敌高度 / 最小离地）
  一字未动。填 0 = 退回「只按悬停 / 接敌高度」。
- ⑦ 关掉只影响「点亮」：熄灯、标记簿记、`sweepStaleMarks` 的过期清扫、坐标链路全部照旧；
  已绑定的那一对再被选中也不会重新点亮（直到开关打开）。
- ⑩ 只补标记、不做切换决策——真正的切任务 / 回排班仍由既有还原链负责（本道只负责「让她重新进那条链」）；
  玩家**自己**切过去的战斗任务不在射程内。
- ② 的清理按文件名删：这三张是随包下架内容，旧安装里同名副本会被删；自己导入的同名图纸属极端情况，会被一并清掉。

## 台账

- `rounds/RELEASES.tsv` 两条 1.3.2 行 **第一百二十一次** 同号重打；旧字节
  `6891ad0e…360cb5`（forge）/ `8c1d4211…2f24d3`（neo）（第一百二十次 R984）作废。
- `DEV_GUIDE.md` 发布区间 → 八百五十九 ~ 九百八十五。
- 四份发布文案 + 桌面「完整逐条」：随包蓝图小节改写为「两张进来、三张下架」（80 → 79 净变化）；
  新增「滑翔载具盘旋高度可调」「指挥棒光标可关」两小节；关键修复追加「等价替代不再被换回」「重放不再吐掉落物」
  「战斗模式未接敌站桩」三条；「行为变化」补「随包下架三张（旧副本首启清除）」。
- `机制详解.md`：`BlueprintLib` 一条补 79 张与下架清理 + 完工重放两道闸；`MaidBuildBehavior` 一条补 `altUsed`
  判据与落地状态口径；末尾联动链补骑乘三条链路（RideBindManager → MaidMountCompat → AutoCombatSwitch 滞留兜底）。
- 附：`check_comment_style` 的长块阈值（成员 8 行）在本轮被连续踩到三次——新注释一律**先数行数再写**。
