# R968 两处修复：刚确定的建造区走出区块也看得见 + 完工整片重放的流体改按流体判

## 排班
- **跑道**：A 修错轮——① 开发者实测 bug（刚确定、未动工时走出区块就看不见那片区域）；② 开发者追问（「完工整体重放为什么不能一并重放水？」）。两项都是**既有行为的缺陷修复**：不新增用户开关、不改任何放置/材料判定、不改蓝图数据格式（只在存档里加一个**兼容**字段）。
- **开工前置**：读过 `WORKFLOW.md` §0~§4、`DEBT.md`、`rounds/RELEASES.tsv` 1.3.2 两行（第一百零三次 R967）、上一轮 `rounds/R967-build-fluid-freeze.md` 与 `rounds/R950-builder-fluids-last.md`（既有结论：液体块一落位就自己排上流体 tick；`activateWater` 在 `BuildPlan.clear` **之前**）；**javap 实证** 1.20.1 字节码：`Block.m_49966_` = `defaultBlockState()`、`BlockState`（实为 `BlockBehaviour$BlockStateBase`）的 `m_60819_` = `getFluidState()`、`FluidState.m_76170_` = `isEmpty()`——按"方块的流体状态"判流体所需的三个符号全部核实。
- **工作队列**（逐步，做完就勾）：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 定位①：区域为什么"人一走就没了" | 找到 `BlueprintBookNetworking.broadcastRegionSync` + `regionInvolves`：判据是「人在区域内 / 区域内的已加载女仆的绑定主人」，而绑定要等女仆被指派 → 未动工时走出即无行可发 | ☑ |
| 1 | 定位②：完工重放的水到底走了哪几步 | `snapFinish`（静默清 + 整片重放，流体静默铺源块）→ `activateWater`（flag 3 激活）；**只有 `minecraft:water` 被激活**，岩浆从未被覆盖（旧注释却写着"水/岩浆源"） | ☑ |
| 2 | 修①：让"确定它的人"一直看得见 | 计划持久化创建者（`PlanState.creatorUuid` → `SavedPlan` 第 11 字段，长度判读兼容旧档）；同步与建造 HUD 都总是把创建者那一行发给他 | ☑ |
| 3 | 修②：流体判定统一按流体 | 新增 `BlueprintStepMath.isFluid / isFluidBlock / isFluidStep`，把**全部**「名字里有没有 water/lava」的判据换成它（含 `moveFluidsLast` 那个按名字的字符串预筛） | ☑ |
| 4 | 两树镜像 | 上述改动两树逐处同步（SRG / 官方名各按本树） | ☑ |
| 5 | 编译 / 护栏 / 打包 / 部署 / 回归 | `EXITCODE=0` ×2；14 支 `FAIL 0`；`BUILT` 双 jar；4/4 `match=True`；四件回归全 `verdict: PASS` | ☑ |
| 6 | 台账 / 文案 / 收尾 | `RELEASES.tsv` 第一百零四次同号重打、`DEV_GUIDE.md` 区间到 九百六十八、5 份发布文案补两条并改一条 | ☑ |
| 7 | 交开发者验收 | 开发者实测后给出结论 | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者；验法 = ①用手册把区块**确定**下来、女仆**别指派/别开工**，自己走出这片区域：红色框与橙色虚影**仍在**（原先整片消失），走回来一样；②女仆开工后照旧；③只带**岩浆**的机器（或任何带流体的机器）建完：岩浆按图纸流动成形（原先不流）；④普通住宅的水池 / 喷泉照旧灌满、睡莲甘蔗不掉；⑤老存档（这份计划是旧版确定的）读取、续建一切正常。

## 需求 / 来源
- 开发者（bug）：「还有一个最近出现的bug，玩家使用手册将区块确定下来的时候，如果这个时候走出区块，则无法再看到区块。重新走进去以后可以。女仆开始搭建的时候也可以，就是在刚确定下来未动工并走出的时候无法看见。」
- 开发者（追问）：「完工整体重放为什么不能一并重放水？我要的大致流程：建造（女仆一个一个放方块/水，放置期间这些方块/流体你红等一切完全静止，活塞不会被激活，tnt也不会爆炸之类的，可以认为在这个时候这些方块/流体是静止的），全部放完后，统一重放，然后再宣布建造完成，删区块」
- 后续补充：「你最好是以流体来进行判定，而不是单单的水和岩浆。」

## 症状 / 现场
- ① 手册确定区块 → 女仆还没动工 → 走出去：那片红色固定框与橙色虚影**整片消失**；走回来又有；一旦女仆开工（被指派）就恢复正常。
- ② 完工"统一重放"的流体处理名不副实：水有条专门的重放激活，岩浆没有——**只带岩浆的机器建好后岩浆不动**（旧注释与 facade 注释一直写着"批激活水/岩浆源"）。

## 根因
- ① **可见性判据是"谁在区域里"，而不是"谁确定了它"**：`BlueprintBookNetworking.broadcastRegionSync`（每秒一次，来自 `ProMaidExtension`）只对 `regionInvolves(...)` 为真的玩家发 `RegionSyncPacket`（客户端 `BlueprintAreaPreview` 就靠这些行画红框与虚影）；而 `regionInvolves` = 「玩家本人在区域内（含外扩）/ 玩家名下的**已加载女仆**在区域内」。刚确定、未动工时女仆**还没被指派**（绑定不存在）→ 玩家一走出区域，两条都不成立 → 一行都不发 → 客户端手里没有行 → 看不见。`BuildHudTracker.playerInvolved` 是同一套判据，所以走开后建造进度条也一起消失。
- ② **完工激活只认水**：`activateWater` 遍历计划步骤，`if (!"minecraft:water".equals(parts[3]) && !"minecraft:lava".equals(parts[3])) continue;` 之后的实现体里只对水生效（旧版只有 `"minecraft:water".equals(...)` 一个条件）；而岩浆在建造期那一拍早被冻结/消耗（R950/R967 结论：流体一落位就排 tick），完工没人给它重排 → 不流。

## 改法
1. **① 计划记下"创建者"**（两树）：
   - `BuildPlan.PlanState.creatorUuid`（新字段，空串 = 无）+ `setPlan(..., quarters, UUID creator)` 新签名（旧 4 参重载委托 `0, null`）；`replacePlanSteps` / `toSavedPlan` / restore 三处一并搬运。
   - `BuildArchive.SavedPlan.creatorUuid`：序列化追加第 11 个 `\u0001` 字段，反序列化 `if (f.length > 10)` 才读 → **旧档读不到即空值**（老存档照旧，新建/重确定的计划享受新行为）。
   - `BlueprintBuildExecutor` 调用点把玩家 UUID 传进去。
   - `BlueprintBookNetworking.regionInvolves` 与 `BuildHudTracker.playerInvolved` 都**最先把"我是创建者"算成立** → 创建者永远收到自己那片区域的行（进度条同理）。
2. **② 流体判定改按流体**（两树）：新增 `BlueprintStepMath.isFluid(Block)`（`!block.defaultBlockState().getFluidState().isEmpty()`）、`isFluidBlock(String)`（id → boolean，`ConcurrentHashMap` 缓存，热路径不反复查注册表）、包内 `isFluidStep(String[])`。把原先所有「名字里有没有 water/lava」的判据换成它：
   - `BlueprintMachineFinish.activateWater`（**完工重放统一激活**，含岩浆与模组流体）+ 其世界侧检查 `st.getFluidState().isEmpty()`；
   - `BlueprintMachineFinish.snapFinish` 的"流体静默放置" flag、`machinePhase` 的"流体排在动力源之前"、`normalizeMachineState` 的 `level` 归一；
   - `BlueprintStepMath.moveFluidsLast`（**连那个 `step.contains("water")||contains("lava")` 的字符串预筛一起删掉**——它按名字认流体，只带模组流体的蓝图会被漏掉；改为整趟 `isFluidStep`，代价与紧随其后的 `dedupeSteps` 同量级）；
   - `BlueprintPlacement.isFluidStepAt`（"设计意图的流体支撑"：村民机水闸活板门等）；
   - `BlueprintLib` 导入时 `keepFluids` 保留与"流体无物品形式"判定、`BlueprintWorldExtract` 取图时跳过流体格、`MaidBuildBehavior` 建造期静默放置的 flag；
   - `BlueprintMachineDetect` 剥离提示统计（新增"其他流体×N"，提示语改为"不会建流体"）。
3. **完工管线（写进 `MaidBuildBehavior` 完工注释）**：建造（放置期方块/流体静止：随机刻 / 重力 / 树叶 / TNT / **流体**冻结）→ 全部放完 → **静默清空 + 按蓝图整片重放**（`snapFinish`）→ **流体统一激活**（水 / 岩浆 / 模组流体，flag 3）→ TNT 点火结算 → 清掉落 → 删区块 → 宣布完工。

## 触及文件
- 两树各：`build/BuildPlan.java`、`build/BuildArchive.java`、`build/BlueprintBuildExecutor.java`、`build/BlueprintBookNetworking.java`、`build/BuildHudTracker.java`（①）；`build/BlueprintStepMath.java`、`build/BlueprintPlacement.java`、`build/BlueprintMachineFinish.java`、`build/BlueprintLib.java`、`build/BlueprintWorldExtract.java`、`build/BlueprintMachineDetect.java`、`build/MaidBuildBehavior.java`（②）。
- 台账 / 文档：`rounds/RELEASES.tsv`（第一百零四次同号重打）、`rounds/R968-*.md`（本文件）、`DEV_GUIDE.md`（发布区间 → 九百六十八）、5 份发布文案（`release_body.md` / `release_body_neo.md` / `RELEASE_NOTES_v1.3.2.md` / 桌面 `Promaid文本\Promaid_v1.3.2_发布声明.md` / 桌面 `Promaid文本\v1.3.2 骑乘修整与全部配置开关可见.md`）。
- **未动**：建造顺序的相位编号与"流体最后放"的口径（R950 结论原样）、材料/缺料链、蓝图导入的状态归一、`build.areaProtection` 与 `build.snapFinish` 两个开关的语义、`BuildArchive` 已有的 10 个字段。

## 验证
- **两树编译**：`javac EXITCODE=0`（forge / neoforge 各一次；改到 12 个源文件，两树镜像）。
- **护栏 14 个**：全 `FAIL 0`（含 `check_arch` 与重新生成的 arch 产物一致、`check_mixin_targets` 267 个注入点、`check_comment_style`、`check_catch_swallow`）。
- **打包**：forge **17637204** 字节（sha256 `458d52f6…1bbcb3`）；neoforge **17684162** 字节（`f359ef5c…b7f790`）。
- **装机**：`deploy_all.py` → 4 个本机实例（1.20.1-Forge、龙之冒险、NeoForge 1.21.1、pack1201 专服）全部 **`match=True`**。
- **回归**：`tools/test/test_server.py 1201` / `neoforge1211` 与 `tools/test/test_maid_load.py 1201` / `neoforge1211` 四件全 `verdict: PASS`。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 行列**第一百零四次**同号重打；R967 旧字节 `af86cac0…4d0087`（forge）/ `4d4ed90e…d8a543`（neo）作废。

## 验收
- 状态：**未验收**（已部署：本机 4 实例与 `patched/` 逐字节一致；未 commit、未 push）。
- **判据**：见上文"谁验收 / 怎么验"五条；其中 ① 与 ③ 是本轮的两个直接修复点，② 是"不许回退"的回归点。

## 边界（没动什么 / 已知取舍）
- **创建者标记只服务"可见性"**：不改材料、不改建造顺序、不改任何放置判定；同时**只多发给创建者自己**，别人的可见性口径一字未变。
- **建造期"静止"这条老门禁管的是普通建筑**：普通建筑全程静默放置（flag 2，活塞不动、红石不激活）；**机器蓝图按设计仍是活放置**（flag 3，动力源最后落位，机器建好即运行）——所以"建造中活塞绝不被激活"对机器蓝图并不成立，终态由完工那一瞬间的**整片重放**保证（这正是重放存在的理由）。这一点在完工注释里写明。
- **流体激活发生在删区块之前**：`activateWater` 在 `BuildPlan.clear` 之前跑；此刻区域若仍在冻结中，那几拍会被 `FluidFreezeMixin` 暂缓到删区块之后照常发生（R967 既有结论，不受本轮影响）。
- **超大蓝图（> 2 万步）跳过重放**：这条既有边界不变；该路径下流体仍由 `activateWater` 单独兜底激活（本轮把它的覆盖从"只水"扩到"各类流体"，正是让这条兜底也名副其实）。
- **模组流体**：判据是"方块自身的流体状态"，因此模组流体与水/岩浆一视同仁；但它们**没有原版水桶/岩浆桶那套材料映射**，缺料链仍按各自动作（本轮不动材料链）。
- **老存档**：计划创建者是本轮新增的持久化字段，此前的计划读出来为空 → 其区域可见性退回旧口径（人/女仆在区域内才可见）。要享受新行为，重新确定一次即可。
- **`moveFluidsLast` 去掉字符串预筛**：每个蓝图多一趟 `parseStep`（与紧随其后的 `dedupeSteps` 同量级，且只在建计划/取投影时跑一次，`BlueprintProjectionSampler` 侧还有缓存）；换来"只带模组流体的蓝图也会把流体挪到最后"。

## 台账
- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第一百零四次同号重打。
- `DEBT.md`：不新增条目（两项都是既有行为的缺陷修复，不是新债）。
- `DEV_GUIDE.md`：发布区间 `八百五十九 ~ 九百六十八`。
