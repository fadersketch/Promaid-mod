# R999 女仆放置方块金色描边 + 潜行右键强制回收 + 指标石「真·最高优先级 / 执行到底 / 不乱动」

> 上一轮：`rounds/R998-wood-leaf-work-gate-mining-idle-chain-workarea-clamp.md`（伐木清叶双闸复核、挖矿静默链路玩家介入即止、
> 四任务 home 工作区钳制，1.3.3 同号重打，**未发布**）。
> 开发者原话（三条）：「女仆搭建的方块（除了建造模式和指标石）加一层金色的描边。同时玩家可以 shift+右键直接
> 强制将这个方块转换为掉落物。几个小时前我在刚刚测试游戏的时候，指标石好像出现了一点问题。女仆突然搭了一半
> 就不搭了，包里面明明还有材料。而且女仆会在搭建的过程中乱动。不应该是在开始执行任务时和建造一样移动机制吗？
> 我想要的效果是只要开始执行就执行到底。他会先放下手上的一切先去完成这个任务，完成以后状态回归原位。也就是说
> 指标石具有真正的最高优先级。」

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 新功能（①金色描边 + ②shift 右键强制回收）＋ B 抢修（③指标石：半途停 = 三条放弃/劫持路径；
  乱动 = 移动抑制白名单缺口）+ C 语义定型（③的「真·最高优先级」= 任务钉死 + 移动钉死 + 完成后还原）。
- **开工前读过**：
  - `rounds/RELEASES.tsv`：1.3.3 两条 `workspace`（R998，**第一百三十四次**）→ 本轮**第一百三十五次同号重打**
    （前一次字节作废）。
  - `ARCHITECTURE-SWITCHES.md`：新增 1 条开关 `misc.placedGoldMark`（默认开；进 DIY KEEP 表，
    关 = 无描边 + 右键不接管完全回原版）。
  - `DEBT.md`：无未清 P0/P1 与本轮相关（BD-04 专项审计与本轮无关）。
  - 上一轮记录 R998：1.3.3 未发布、本轮顺延 R999。
- **工作队列**（逐步，做完就勾；每步写"完成判据"）：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 定位（描边对象来源 / 右键口径 / 指标石三条放弃路径 / 乱动根因） | 每条结论指着 `file:line` 或 javap 字节码（见「评估」） | ☑ |
| 1 | 两树改 + 编译 | 新增 3 类（网络层 / 客户端渲染 / 右键交互）+ 11 文件编辑；两树 `javac` `EXITCODE=0` | ☑ |
| 2 | 14 护栏 | `FAIL 0 / WARN 0`；`panel_coverage.txt` / `ARCHITECTURE-SWITCHES.md` / `normify-promaid` 按真源重生成 | ☑ |
| 3 | 同号重打 1.3.3 + 装 4 实例 | 双 jar；`match=True`；`RELEASES.tsv` 两条新行（void 旧字节） | ☑ |
| 4 | 专服回归三件 | `test_server.py 1201` / `test_server.py neoforge1211` / `test_maid_load.py neoforge1211` 全 PASS | ☑ |
| 5 | 交开发者验收 | 实机复看三条（清单见下） | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者。
  1. **金色描边**：她搭路/垫脚/垫塔/挖矿垫块之后，那些方块套一层金色线框（搭路跟着走能一路看到）；
     蓝图的方块**没有**描边、指标石临时搭建的方块**也没有**。面板「系统与杂项 → 女仆放置方块的金色描边」
     关掉 = 描边与右键一并回原版。
  2. **shift + 潜行右键（最低优先级）**：描边方块上潜行右键 = 立刻变掉落物（粒子 + 音效，掉在原地）——
     但手里拿着方块、主手物品自带右键动作（食物 / 桶 / 打火石 / 锄锹斧 / 剪刀 / 钓竿 / 船 / 矿车 /
     刷怪蛋 / 末影珍珠 / 雪球 / 蛋 / 火焰弹 / 末地水晶…）、或方块自己可交互（箱子熔炉等方块实体 +
     门 / 按钮 / 工作台等家族）时**一律让位**（那些交互照常生效）；对着她没搭过的方块右键也不接管。
  3. **指标石执行到底**：开始搭建后——背包有料就一路搭完（中途缺料会等她/提醒补料，补上立刻续建，
     不再 3 秒烂尾）；你站在她面前挡格/离开区块回来看，她**不放弃**（日志最多一条"20 秒无进展"留痕）；
     旁边来怪她**不跑去打架**；你手动把她切成别的任务，约 2 秒内被**抓回**临时搭建（promaid.log 有
     `任务被外部改走 → 抓回临时建造`）；搭建全程她站桩不乱走（与建造任务同款）。
  4. **完成后还原**：搭完 → 播报「搭好了，共 N 个方块」→ 她的任务/home/作息回到**开始前那个**，
     金色描边不残留（指标石方块本来就不描边）、锁定框与幽灵格清空。
  5. 回归对照：其余玩法（挖矿/伐木/建造/战斗）手感与上一版一字不差。

## 需求 / 来源

- 开发者实机三条（原文见上）。①②是新功能（描边 + 强制回收）；③是实机 bug 报告 + 语义要求
  （执行到底 / 最高优先级 / 完成后状态回归）。

## 评估

### ① 金色描边 + ② 强制回收（新功能，口径与范围）

- **描边对象从哪来**："哪些方块是她搭的"是纯服务端知识——四个搭方块方各自持有一份 `PlacedBlockTracker` 实例
  （挖矿 `MaidMineBehavior` / 伐木 `MaidWoodBehavior` / 搭路 `BridgeUpBehavior` / 自保
  `SelfPreservationBehavior`，全部自动进 `ALL_INSTANCES`）。**蓝图建造与指标石临时搭建不走追踪表**
  → "除了建造模式和指标石"是**结构性**保证，不靠运行时黑名单。
- **同步**（新类 `PlacedMarkNetworking`）：登记/移除逐格累积（`noteAdd`/`noteRemove` 挂在
  `PlacedBlockTracker.track` / `untrackAny` / `destroyAndReclaim`），满 512 格或每 5 tick 合并一包发给
  **该维度全部玩家**（Forge `PacketDistributor.DIMENSION`；Neo `sendToPlayersInDimension`，javap 实证）；
  进服/换维度补全量快照（1024 格一包，第一包 full 清底）。服务器停止清待发表。
- **渲染**（新类 `PlacedMarkClient`）：纯客户端，位置表按打包坐标存；`AFTER_TRANSLUCENT_BLOCKS` 阶段用
  LINES 管线画金色线框（只画棱线不画填充——口径就是"描边"）；相机相对坐标与指标石预览/BombMark 同款
  （`camera = -camPos` + `camera.add(世界坐标)`）；96 格内、单帧上限 4096 格，帧开销有界；退出世界自清。
  开关 `misc.placedGoldMark`（默认开）；面板「系统与杂项」一行；中英译文键 ×4 lang 文件。
- **强制回收**（新类 `PlacedMarkInteractHandler`，服务端 `RightClickBlock`）：**潜行 + 该格在追踪表登记**
  （`trackedBlockId != null`）两闸全过即接管。初版另含「双手都空」第三闸；**验收反馈后已移除**
  （"只需要潜行加右击就行了"，见「追加」）。接管动作：`untrackAny`（含描边移除通知）→ 方块比对
  （被玩家换过只清登记不动方块）→ `reclaimDrops` → 破坏粒子音效 → 置空气 → 掉落物**掉在原地**
  （不塞她背包，谁都能捡）。
- **取消语义差**（javap 实证）：NeoForge 的 `RightClickBlock.setCanceled(true)` 只把
  `useBlock/useItem` 置 FALSE，`cancellationResult` 仍是默认 `PASS`；Forge 版取消会连结果一起置 FAIL
  → Neo 侧显式补 `setCancellationResult(InteractionResult.FAIL)` 对齐（两者字节码逐条核过）。

### ③-A 「搭建过程中乱动」的根因（移动抑制从未生效）

- 指标石行为（`IndexStoneBuildBehavior`）一直在 `setStill(true)`（站桩标记），但移动抑制的**总闸**
  `MaidWorkTags.isStill()` = 标记在 **且** `isWorkTask(当前任务)` 为真（防残留冻结的双保险）——
  而 `isWorkTask` 的白名单只有 `build / cook / brew`，**没有 `index_build`** → `isStill` 对临时搭建恒
  false → `MaidMoveSuppressMixin`（源头取消 MoveToTargetSink）与 `MaidStationaryMixin`（移动层清水平
  位移）**从未对她生效**。这就是"乱动"的根因：她行为里打的标记被自己的防残留闸否掉了。
- 修法：`isWorkTask` 白名单补 `maid_smart:index_build`（与建造任务同款移动机制——正是开发者要的
  "和建造一样"）；另在常驻 core 行为里每 tick 维护移动钉死（见下），双保险。

### ③-B 「搭一半就不搭了」的根因（三条放弃/劫持路径）

- 逐条实锤（旧 `IndexStoneBuildBehavior`）：
  1. `STALL_LIMIT=400`（20 秒没有进展，含"等区块加载/等主人让格"的合法等待）→ `failSession` **烂尾**；
  2. `NO_MATERIAL_GRACE=60`（3 秒找不到材料）→ `failSession`——"刚消费完一叠、主人正在往背包补"
     的窗口直接判死（与开发者"包里面明明还有材料"吻合：女仆背包空了、主人背包/交付还在路上）；
  3. `AutoCombatTargeting` 只豁免了蓝图建造（`BlueprintBuildExecutor.isBuildingTask`），**没有豁免
     index_build** → 附近刷怪 → 她"自动参战"被切走 → 建造链路停摆。
- 修法（全部删掉放弃语义）：
  - STALL 分支改**只留痕一条日志**（"继续等待…不再烂尾"），不 return 不失败；
  - 缺料分支改**节流重试**：首拍 + 每 40 次尝试全量重取一次（含从主人背包拉），提示每 100 次提醒一次
    （新文案"没有可以搭建的方块了…主人给我一些方块吧（搭完才会走）"），**没有放弃路径**——材料一补上
    立刻续建；
  - `AutoCombatTargeting` 加 `isIndexBuilding(maid)` 豁免（"先放下手上的一切"把这次搭建做完）。
- **真·最高优先级**（新 `IndexStoneService.tickPriority`，挂常驻 core 行为 `MaidToolAutoEquipBehavior`
  ——任何 activity 都跑，活动空档也兜住）：① 移动钉死（`setStill(true)` + home 让位 + 清 WALK_TARGET +
  停导航 + 清水平速度，与建造任务同款）；② 任务钉死——任务被任何来源（排班/玩家/第三方）改走 →
  抓回 `index_build`（40 tick 限频防拉锯；`server.execute` 延后避免行为 tick 中重建 Brain；
  `ScheduleSwitchGuard.runInternal` 是项目既有唯一 setTask 入口）。完成后走既有 `releaseMaid`：
  还原开始前的任务/home/作息，播报"搭好了"。玩家解锁/收魂/死亡三条既有取消入口原样保留。
  `PIN_LAST` 表在 releaseMaid / clearMaidTags 两处清理，防残留。

## 改动（触及文件）

- 新增（两树镜像，共 3 类 × 2 树）：`PlacedMarkNetworking`（S2C 位置表增量/快照）、
  `client/PlacedMarkClient`（金色线框渲染）、`PlacedMarkInteractHandler`（潜行右键强制回收；
  初版含「双手都空」闸，按验收反馈移除——见「追加」）。
- 编辑（两树各 11 个 .java）：
  - `PlacedBlockTracker`：登记/移除/销毁三处挂同步钩子 + 新增 `snapshot()`（跨实例去重，进服/换维度全量用）；
  - `MaidWorkTags`：`isWorkTask` 白名单补 `index_build`（乱动根因）；
  - `MaidToolAutoEquipBehavior`：core 行为里挂 `IndexStoneService.tickPriority`；
  - `IndexStoneService`：新增 `PIN_LAST` + `tickPriority`（移动钉死 + 任务抓回）+ 两处清理；
  - `IndexStoneBuildBehavior`：删两条放弃路径（STALL 留痕、缺料节流重试）+ 新气泡文案 + 类注更新；
  - `AutoCombatTargeting`：指标石参战豁免；
  - `MaidSmartConfig`：`MISC_PLACED_GOLD_MARK` 声明 + 定义（comment + translation）；
  - `PromaidConfigScreen`：面板「系统与杂项」新行；
  - `ProMaidMod`：网络层注册（Forge）/ 交互 handler 注册（Neo 自动注册网络层）；
  - `PromaidClientSetup`：`PlacedMarkClient.ensureRegistered()`；
  - `ProMaidExtension`：登录/换维度补描边全量、服务器停止清待发表。
- 台账/编排产物：`panel_coverage.txt`（两树重生成）、`ARCHITECTURE-SWITCHES.md`（1367 行）、
  `tools/arch/arch_modules.py`（`promaid.work.placed-block` 模块 source 补 `PlacedMarkClient`，
  placed-block 行号 437→460）+ `normify-promaid/` 143 文件重生成。
- **触及文件：两树 × 14 个 .java（3 新增 + 11 编辑）+ 4 个语言/预设文件 + 编排产物。**

## 验证

- 命令 + 结果：
  - 两树 `javac` **EXITCODE=0**。
  - 14 护栏**全 FAIL 0 / WARN 0**（`check_comment_style` PASS；`check_preset` DIY 关闭 108/111 不变
    ——新开关默认开 + 进 KEEP；`check_panel_coverage` 未收录 1 = 既有自动页；`check_arch` 覆盖维持
    181/0 基线，产物与真源逐字节一致）。
  - 双 jar（**同号重打**）：`patched/promaid-1.3.3-forge-1.20.1.jar` **17932097** 字节，sha256
    `03a1551bc52012d25d5a229d6438f1e63b6f1c9111230606698597057562bf0f`；`patched/promaid-1.3.3-
    neoforge-1.21.1.jar` **17979599** 字节，sha256 `b050c8347e29bf6f799db56880b3ed738a78bc19068e47
    44e48401de6d9e4cb4`。构建内 mixin 注入点审计 + lang json 校验 PASS（`build_promaid*.py` 内建硬闸）；
    桌面「Promaid_本体当前」快照同步（复制 51 / 12，删除 0）。
  - **部署 4 实例：全部 `match=True`**；**专服回归三件全 PASS**（`test_server.py 1201` verdict PASS、
    `Done (3.259s)` / `test_server.py neoforge1211` verdict PASS、`Done (1.137s)` /
    `test_maid_load.py neoforge1211` verdict PASS）。
- **反证**（修 bug / 新功能轮必填）：
  - ①描边范围"除蓝图/指标石"是**结构性**的：两处都不走 `PlacedBlockTracker`（蓝图放置走
    `BlueprintBuildExecutor` 自己的账本；指标石走 `IndexStoneBuildBehavior.place`）——不在追踪表 =
    永远没有描边点，不存在"靠一个 if 挡错"的可能。
  - ②右键不抢的证明面：三闸（潜行/双手空/已登记）任一不满足即原样放行；对照版本里"握物潜行右键"
    高频用于放置方块——双手都空这一条把它整个排除在外。
  - ③"半途停"的三条旧路径在旧版**必然命中**（不是偶发）：等区块/等主人让格每次超过 20 秒必然
    `failSession`；消费完一叠材料 3 秒内没补上必然 `failSession`；旁边刷怪必然被参战切走——修复后
    三条全部改为非破坏语义（等待/重试/豁免），仅存"玩家解锁/收魂/死亡"三条既有取消入口。
  - ④"乱动"的根因在字节码层可证伪：`isStill` = `getBoolean(WORK_STILL_TAG) && isWorkTask(maid)`，
    白名单缺 `index_build` → 恒 false（javap 逐一核对 TLM `Behavior` 与两个 mixin 的挂点）——补上后
    isStill 对她为真，防残留逻辑不受影响（任务切走自动回落 false）。
  - ⑤Neo 取消语义差已用 javap 逐条实证（`setCanceled` 只置两个 TriState、`cancellationResult` 默认
    PASS 留在构造器字节码里）→ 显式 FAIL 对齐 Forge，服务端把这一下判为"已处理但不通过"。

## 追加（验收反馈 · 实测一千〇六续）

> 开发者原话：「我必须要双手都空太严格了。只需要潜行加右击就行了。」

- **改动**：`PlacedMarkInteractHandler`（两树）删除「双手都空」闸——`潜行 + 已登记` 即接管（挥臂、
  判定、回收链路与初版逐字节相同，只少一个 return 分支）；类注 / 配置注释 / 面板行描述同步去掉
  "空手（双手）"字样（`MaidSmartConfig` comment + `PromaidConfigScreen` 行 × 两树，共 6 处编辑）。
- **口径变化**：握物潜行右键描边方块也会被接管（原设计怕抢"放置/使用"，按开发者要求放开）；
  "不是她搭的方块"「非潜行」两条闸不变。
- **重打第 136 次**：两树 `javac` EXITCODE=0 → 14 护栏 FAIL 0 / WARN 0 → 双 jar
  forge **17932000** 字节 `fe2531e5…772e` / neo **17979490** 字节 `00ca3320…0bb8`（前一次
  `03a1551b…bf0f` / `b050c834…4cb4` 作废）→ 部署 4 实例 `match=True` ×4 → 专服回归三件 PASS
  （Done 2.859s / 1.105s / maid load PASS）。



## 追加二（验收反馈 · 实测一千〇六续二）

> 开发者原话：「手里拿着方块不接管，总体而言，这个功能的优先级很低。如果收到的东西有什么其他的交互功能，都会让位给它。」

- **改动**：`PlacedMarkInteractHandler`（两树）在接管前加一道「最低优先级让位」判据（`someoneElseActs`）：
  ① 主手/副手拿着方块（`BlockItem`）→ 让位（放置优先）；② 主手物品自带右键动作 → 让位——可探测集按
  **超集方向**取：食物（1.20.1 `ItemStack.isEdible` / 1.21.1 `DataComponents.FOOD`）＋ 蓄力使用
  （1.20.1 `Item.getUseDuration(ItemStack)` / 1.21.1 `ItemStack.getUseDuration(player)`：弓 / 盾 / 弩 /
  三叉戟 / 望远镜 / 药水…）＋ 常见即时作用与放置物家族（桶 / 打火石 / 锄 / 锹 / 斧 / 剪刀 / 钓竿 /
  船 / 矿车 / 刷怪蛋 / 末影珍珠 / 雪球 / 蛋 / 火焰弹 / 末地水晶）；③ 被点方块自己有交互 → 让位
  （一切方块实体 `EntityBlock` ＋ 无方块实体的原版交互家族：门 / 活板门 / 栅栏门 / 按钮 / 拉杆 /
  音符盒 / 堆肥桶 / 蛋糕 / 重生锚 / 工作台系 / 切石机 / 织布机 / 砂轮 / 铁砧）。判定全部走在
  `setCanceled` 之前——让位 = 完全不取消，原版链路照走。
- **口径**：只有"这一下本来什么都不做"（既没拿方块、主手东西没有右键动作、方块也没交互）才回收；
  未知模组的物品/方块本类不猜——留在"可探测超集"之外（宁可少回收，不抢别人的交互）。
- **同步**：两树 handler 类注 + 配置注释 + 面板行描述再更新（4 处）。
- **重打第 137 次**：两树 `javac` EXITCODE=0 → 14 护栏 FAIL 0 / WARN 0 → 双 jar
  forge **17932986** 字节 `0b85e9bf…4418` / neo **17980592** 字节 `52ae2e91…f526`（前一次
  `fe2531e5…772e` / `00ca3320…0bb8` 作废）→ 部署 4 实例 `match=True` ×4 → 专服回归三件 PASS
  （Done 2.747s / 0.943s / maid load PASS）。

## 追加三（验收反馈 · 实测一千〇六续三）

> 开发者原话：「失败了，你的开通未能生效。金色描边和潜行右击都没有」

**先证版本身份（排除"没装上"）**：

- 实机跑的就是 137 版字节：产物 10-17 01:16/01:17 生成 → 01:27:52 部署完成 → 测试会话 01:29:11 开服
  （晚于部署），实机 jar 与 `patched` 产物 sha256 逐字节一致（`0b85e9bf…4418` / `52ae2e91…f526`），
  `promaid-common.toml` 里 `placedGoldMark = true`（新键在场 = 加载的确实是含本功能的那份，且开关在闸内）。
- 测试窗口（10-17 01:22–01:36 两个会话）`promaid.log` 全量 197 行：**没有任何"女仆搭方块 / 回收"类事件**
  （全是重锤门 / 刷怪笼 / 飞行跟随 / 装备托管 / 自愈诊断等别的链路）。也就是说：那两个会话里
  **根本没有出现过"该被描边"的方块**——上一会话的搭方块按既有机制在退出世界时就已回收
  （`ServerStopping → clearAll`），蓝图建造与指标石按需求天然不在内。

**链路七段静态审计（全过）**：①登记 `track → noteAdd`（搭路 / 挖矿 / 伐木 / 战斗四套追踪器、
被 `.track()` 全部调用点覆盖）；②合并 `Pending`（FLUSH_OPS 512 / FLUSH_TICKS 5）；③发包；
④载荷注册（Forge `PlacedMarkNetworking.register()`@`ProMaidMod:102`；Neo `@EventBusSubscriber` 自动注册）；
⑤客户端收表 + 注册口（`PromaidClientSetup.registerHudHooks → PlacedMarkClient.ensureRegistered`，与已实装
的 BombMark 粉标同一个注册口）；⑥渲染（AFTER_TRANSLUCENT_BLOCKS + LINES + TLM renderLine + 相机相对坐标，
与 `BombMarkClient` 逐行同款）；⑦右键接管（Neo 侧 javap 实证：补丁版 `ServerPlayerGameMode.useItemOn`
顶部**无条件**发 `onRightClickBlock`（空手也发）→ `isCanceled` 即 `return getCancellationResult()`；
`setCancellationResult` 是纯赋值无校验）。结论：**"日志里看不到"与"链路故障"在旧日志下不可区分**。

- **改动（纯日志插桩 + 一处分发口换用实测过的口径；行为零改动）**：
  - `PlacedMarkNetworking`（两树）：flush 每维度 2 秒一条「同步 增N 撤M → 维度」；`pushSnapshot`
    每条「全量补发 N 格（开关关）→ 玩家名」（0 格也记——证明进服 / 换维度补发确实执行了）。
  - `PlacedMarkClient`（两树）：收到全量 →「客户端收到全量：清底后 N 格」；首批增量 →「+N 格」；
    首帧与之后每 30 秒心跳 →「客户端在画描边：表内 N 格，视野内 M 格」。
  - `PlacedMarkInteractHandler`（两树）：命中描边表一律留痕（让位原因 / 「强制回收 X → 掉落 N 件」/
    只清登记：空气或已被换块）；空手潜行右键「未登记」格留一条「该格无描边登记（描边表 N 格）」
    （20 tick 节流；拿物品时的右键是常态、不记）。
  - 分发口：flush 从 `PacketDistributor.DIMENSION` 广播（全工程唯一一处该用法、无实测先例）换成逐玩家
    分发（Forge `PLAYER.with(...)` / Neo `sendToPlayer`，与 `ConfigSyncNetworking.broadcast` 同款；
    只发本维度玩家）。
- **给开发者的复看步骤**（30 秒出结论）：开「搭路」（或挖矿 / 伐木 / 自保）让她放几块方块 → 应见金色线框；
  空手潜行右键那格 → 立刻碎成掉落物。无论成败，`logs/promaid.log` 这次都会留下 `[描边]` 全链路痕迹
  （补发 → 同步 → 客户端收到 → 在画）——哪一段缺，病灶就在哪一段。
- **重打第 138 次**：两树 `javac` EXITCODE=0 → 14 护栏 FAIL 0 / WARN 0 → 双 jar
  forge **17935069** 字节 `1eff6ca0…1ef4` / neo **17982671** 字节 `80de37e3…d071`（前一次
  `0b85e9bf…4418` / `52ae2e91…f526` 作废）→ 部署 4 实例 `match=True` ×4 → 专服回归三件 PASS
  （Done 2.855s / 0.985s / maid load PASS）。

## 追加四（验收复测 · 实测一千〇六续四）

> 开发者：「我测了」——拿到复测日志后定位到真因：**负坐标解包错**（不是链路，是坐标算术）。

- **复测日志（10-17 11:58–12:04，NeoForge 实例）**：
  - `[11:58:13] 描边 全量补发 0 格 → Khragg` → `[11:58:14] 客户端收到全量：清底后 0 格`
    （登录补发 + S2C 载荷链路通）；
  - `[11:58:25] 同步 增1 撤0 → overworld` → `[11:58:25] 客户端收到首批增量：+1 格`
    （服务端登记 + 增量分发 + 客户端收表全通）；
  - 之后 11:58:29 增1撤1、11:59:17/21/25 三次撤1（两块战斗垫块按 60 秒寿命到期）——**全程没有
    「客户端在画描边」**，也没有任何潜行右键记录。
- **根因**：`PlacedMarkClient` 自写的坐标打包是 X 26 位 / Y 12 位 / Z 26 位；`unpackX` 用算术右移
  （负数正确），但 `unpackY` 只取 12 位无符号、`unpackZ` 只取 26 位无符号——负 Y 解成 4055+、
  负 Z 解成 67108823+。测试基地在负坐标（此前日志里她在 `(-742,-41,-41)`），客户端把包里的坐标
  一解包就飞到天边，每帧距离闸（96 格）全部 `continue`，`drawn` 恒 0 → 金框一个都不画，
  连「在画」心跳都不会出（与日志完全吻合）。
- **修法**：`unpackY` / `unpackZ` 补符号扩展（`y >= 0x800 ? y - 0x1000 : y`；
  `z >= 0x2000000 ? z - 0x4000000 : z`），`unpackX` 不动（算术右移本就带符号）；用 python 模拟
  Java 位运算对 `(0,0,0)`、`(-742,-41,-41)`、`(100,320,-500)`、`(1234,64,5678)` 与 ±26/12 位
  边界极值做回环验证（旧式全错、新式全对）。
- **顺带**：「客户端收到首批增量」日志补上首格坐标 `@(x,y,z)`——下次一眼看出解包是否正确。
- **重打第 139 次**：两树 `javac` EXITCODE=0 → 14 护栏 FAIL 0 / WARN 0 → 双 jar
  forge **17935183** 字节 `c40fa88a…4370` / neo **17982779** 字节 `9cb00859…bfbf`（前一次
  `1eff6ca0…1ef4` / `80de37e3…d071` 作废）→ 部署 4 实例 `match=True` ×4 → 专服回归三件 PASS
  （Done 3.288s / 1.694s / maid load PASS）。

## 验收（谁说了算：开发者 —— `WORKFLOW.md` §4.1）

- 状态：`未验收`（等开发者按「排班」五条清单实机复看）。
- 未验收之前：不写 changelog、不写 release 说明、不发平台。

## 边界（没动什么）

- 描边只管"她搭的方块"（四追踪表）；蓝图建造与指标石**不在内**（结构性）；开关关 = 描边与右键回收
  一并回原版。
- 空袭轰炸的底座方块（黑曜石 / 基岩）走 `MaidBombing` 自己的回收表（起爆后约 10 秒收回她背包），
  不经 `PlacedBlockTracker` → 不在描边 / 右键范围（追加三审计顺带核实：全工程 `.track()` 调用点只有
  搭路 / 挖矿 / 伐木 / 战斗四处；该项未改动，列为边界）。
- 右键回收是**最低优先级**：潜行 + 已登记是入场券，但凡「拿着方块 / 主手东西有右键动作 / 方块自己
  有交互」任一成立就整条让位（判定集见「追加二」，按超集方向取——宁可少回收、不抢别人的交互）；
  方块被玩家换过 → 只清登记，绝不动玩家的方块。
- 指标石**没有新增放弃路径**：删掉了两条自动放弃（20 秒无进展 / 3 秒缺料），保留玩家解锁、收魂、
  女仆死亡三条既有取消；完工/取消都照旧还原任务/home/作息（`releaseMaid` 既有语义，一字未改）。
- 任务抓回限频 40 tick：与排班/第三方 mod 的 setTask 抢方向盘不超过"每 2 秒一次"，且只在她仍处于
  临时搭建标记下才动手；我们自己的收尾路径先清标记再还原，不会与抓回打架。
- 骑乘/坐下期间不受影响（`tickPriority` 只钉移动与任务，不改坐姿/骑乘；行为本身只在 WORK 活动评估）。
- 版本号仍 **1.3.3**（未发布时段同号重打，旧字节在 `RELEASES.tsv` 记废）。

## 台账

- 清掉：无既有债号（①②为新功能；③为本轮新报告）。
- 新增债：`DEBT.md` BD-34（指标石三条放弃/劫持路径：「搭一半就停」——已清@实测一千〇六）、
  BD-35（指标石「搭建中乱动」：`isWorkTask` 白名单缺 `index_build` 致移动抑制从未生效——已清@实测一千〇六）；
  §0 模块索引「建造 / 蓝图」行补 BD-34 · BD-35。
- 发布登记：`rounds/RELEASES.tsv` 追加 **1.3.3** 两条 `workspace`（**第一百三十九次同号重打**：本行现字节
  `c40fa88a…4370` / `9cb00859…bfbf`；前一次 `1eff6ca0…1ef4` / `80de37e3…d071` 作废；更早的
  `0b85e9bf…4418` / `52ae2e91…f526`、`fe2531e5…772e` / `00ca3320…0bb8`、
  `03a1551b…bf0f` / `b050c834…4cb4`、`e21fc36c…1403` / `5ed9f7d6…fd98` 依次作废）。
- 升号：无（1.3.3 未发布 → 同号重打）。
