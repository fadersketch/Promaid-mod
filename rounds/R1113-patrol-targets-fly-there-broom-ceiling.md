# R1113 巡逻航图目标点 + 「飞过去」 + 扫帚追主窒息

## 排班

- **跑道**：A 修 bug（扫帚追主卡方块窒息）+ C 功能（航图独立目标点与四状态「飞过去」）+ 问答（空瓶归属）。
- **开工前读过**：`PatrolBook` / `PatrolRoute` / `PatrolChartData` / `PatrolChartScreen` / `PatrolMarking` /
  `PatrolNetworking`（Forge SimpleChannel 与 Neo `CustomPacketPayload` 两套）/ `PatrolPreviewClient`、
  `MaidBroomDrive.patrolPoint` / `safeY` / `followPoint`、`MaidBroomBehavior` ⑥ 平时三档、
  `MaidFlightFollowBehavior.aimOf` / canUse / canContinue / `maidsmart$tetherHold`、
  `RideBindManager.drive` 飞行载具分支、`GunnerTetherManager.modeKind` / `hangingPlayer`、
  `MaidAidOwnerBehavior.giveDrinkablePotion` 与原版 `PotionItem.finishUsingItem`。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 目标点数据模型（两树） | `PatrolTarget` 入书 NBT，可增删改名、上限 32 | ☑ |
| 1 | 目标点 GUI 与红色预览 | 列表/管理/改名/删除/标记入口同款；世界里画红菱形 | ☑ |
| 2 | 中键标记目标点 | 服务端落盘，可连续打点，不碰轨道 | ☑ |
| 3 | 「飞过去」四状态识别 | 扫帚同乘 / 直升机女仆驾驶 / 空袭拴绳 / 扫帚拴绳 | ☑ |
| 4 | 目标注入三条飞行链路 | 扫帚、直升机、飞行跟随各自"跟随主人"入口改成目标点 | ☑ |
| 5 | 扫帚追主头顶方块窒息 | 竖向通道先量天花板，放不下就原地不动 | ☑ |
| 6 | 编译/护栏/打包/部署/测试/轮次文档 | 两树 OK；14 护栏全绿；四套 PASS；4 实例 match=True | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

1. "如果主人背包满了，那空瓶会在哪？"
2. "巡逻航图加入新功能，标目标点。在页面除了选择标记轨道外还选可单独标记目标点，标记方式和标轨道的点一致，
   标记点为红色。且管理标记点也和轨道有同款GUI。随后玩家和女仆一起在扫帚上时/玩家和自己的女仆坐在直升机且
   女仆主玩家副/玩家用武装拴绳拴在空袭女仆（且她具有飞行能力）上/玩家用武装拴绳把自己栓在扫帚女仆上时，
   玩家此时手持巡逻航图右击进入标记点的管理界面，会多出一个选项：飞过去（如果目标在其他维度，此选项变暗，
   变为只读，玩家无法点击），点击后玩家退出巡逻航图页面，女仆以目标点为目的地朝那飞行。飞行逻辑与各情况下
   女仆跟随主人逻辑无异。只是目标变为目标点且距离可能很远。"
3. "扫帚模式女仆骑扫帚如果要追在自己上方的主人，但头顶又有方块阻拦，那女仆会直接卡方块里窒息。"

## 评估

1. **目标点独立于轨道**：轨道是"闭环折线"，目标点是"单个坐标"。两者共用一本航图，但数据结构分开：
   `PatrolBook.targets()` 与 `routes()` 并列，`save/readInto` 各写一个 ListTag；`isEmpty()` 继续只认轨道
   （"有没有轨道可管理"），新增 `emptyAll()` 给预览/开屏用（"整本书是不是空的"）。这样旧存档、旧界面
   判定、旧命令语义都不受影响。
2. **标记复用同一条中键链路**：`PatrolMarking` 增加哨兵 id `#target`。开始标记时把 `#target` 当"轨道 id"
   发给服务端，服务端在 `StartMarkingPacket` / `MarkPatrolPointPacket` 最前面分支处理：只校验"航图在手上
   + 处于标记态"，不要求任何轨道存在。打点位置 = 玩家脚下眼睛位置（与轨道点同一处代码），维度取玩家当前维度。
3. **「飞过去」只存坐标**：新增 `MaidTravelTarget`（per-maid、TTL 30 分钟、到点 4 格自动清除、换维度作废）。
   三条位移链路只在各自"这一拍去哪"的入口问一句，其余（接敌、避障、燃料、高度、操控）一个字节不动：
   - 扫帚：`MaidBroomDrive.patrolPoint` ①（排在 `/broom_goto` 与航迹之前）；
   - 直升机/飞行载具：`RideBindManager.drive` 飞行载具无敌人分支，替代"机上有人 → 悬停"；
   - 空袭/扫帚拴绳的飞行跟随：`MaidFlightFollowBehavior.aimOf` ①（排在"拴绳原地悬停"之前）。
   这样"飞行逻辑与跟随主人无异，只是目的地变了"是字面成立，而不是另写一套飞行器。
4. **远距离要能起手**：飞行跟随原来的"与目标之间没有方块阻挡"是给 25 格内追主人用的；点一个几百格外的
   目标点必然被地形挡视线。所以**只在有航图目标时**跳过视线判定（威胁、缺鞘翅、缺燃料等闸门照旧）；
   玩家显式下的命令不该被一次 raycast 拦死。
5. **跨维度**：客户端用包里的 `playerDim` 与目标维度比对，不同则「飞过去」按钮 `active=false`（变暗只读）；
   服务端 `FlyToTargetPacket` 再校验一次（防改包），不满足直接回红字。
6. **谁能执行**：`findEligibleMaid` 只认四种"她载着玩家"的状态——同乘扫帚（两种座位）、同乘飞行载具且
   女仆在 0 号驾驶位、武装拴绳挂在空袭女仆下、挂在扫帚女仆下（`modeKind` = flight/broom/mount）。
   其它情况界面里不出现「飞过去」。
7. **窒息根因**：`safeY` 旧版只看"目标那一列"从想要高度往下找两格空气；主人飞在屋顶上时，目标点
   （主人 +2 格）本身就在屋顶**上方**的空气里，于是"找到"了却要穿过天花板才能到达——而她是被
   `positionRider` 直接摆到座位点的乘客，穿不过去就嵌在方块里扣窒息。现在先量两条竖直通道
   （她自己这一列、目标这一列）各自第一块天花板，取较低者作上限；一格都放不下就返回 `fromY`
   （原地悬停），不再硬塞。
8. **空瓶归属（问答）**：R1112 起主人链调 `finishUsingItem(taken, owner.level(), owner)`。原版
   `PotionItem` 对 `Player` 目标的收尾是 `player.getInventory().add(new ItemStack(GLASS_BOTTLE))`；
   背包满时 `Inventory.add` 返回 false 并走 `player.drop(...)`，所以**空瓶掉在主人脚边**，不会凭空消失。
   创造模式下原栈返回给女仆背包（等于不消耗），也是原版语义。

## 改动

### 数据模型（两树）

- 新增 `patrol/PatrolTarget.java`：`id / name / dimension / x,y,z`，`MAX_TARGETS = 32`，
  `pos() / copy() / save() / load()`；Forge 用 SRG NBT 与 `Vec3.f_82479_` 组，Neo 用 Mojmap。
- `patrol/PatrolBook.java`：新增 `targets` 列表与 `targets() / hasTargets() / emptyAll() / targetById() /
  addTarget(Vec3, dimension) / removeTarget() / freeTargetName()`；自动命名「目标N」；`copy/save/readInto`
  同步读写 `targets`。`isEmpty()` 保持"只认轨道"。
- 新增 `patrol/MaidTravelTarget.java`：per-maid 临时目的地，`set/clear/active/aim(maid[,current]) /
  positionOf`；TTL 36000 tick、到点 4 格、换维度作废；骑着扫帚/载具时用载具位置判到点。
- `patrol/PatrolMarking.java`：新增哨兵 `TARGET_MODE = "#target"`。

### 航图界面（两树）

- `PatrolChartScreen` 新增 `VIEW_TARGETS` / `VIEW_TARGET_MANAGE`、`viewingTargetId`、`canFlyToTarget`、
  `playerDim`、`targetPage`、`renamingTarget`。
- 列表页顶部新增「目标点管理 (N)」（放在创建按钮左侧，避免小分辨率下越界）。
- 目标点列表：管理 / 改名 / 删除 / 分页，与轨道列表同款；顶部「＋ 标记目标点」。
- 目标点管理：返回 / 改名 / 标记新目标点 / 删除；底部「✈ 飞过去」——
  - 只有 `canFlyToTarget`（四种状态之一）时才出现；
  - 目标在其他维度时按钮变暗且点击只回提示；
  - 点击后发 `FlyToTargetPacket` 并关闭界面。
- `PatrolPreviewClient`：整本书判空改用 `emptyAll()`；新增红色目标点循环（当前维度才画），
  标签「§c目标点N 名字」+ 坐标。
- `PromaidClientSetup`（Neo）补传 `canFlyToTarget / playerDim` 两个参数。

### 网络（两树）

- `OpenPatrolPacket` 增加 `canFlyToTarget`、`playerDim`（编码/解码同步）。
- 新增 C2S `FlyToTargetPacket(offHand, targetId)`：校验航图在手上、目标点仍在书里、维度一致、
  `findEligibleMaid` 非空，然后 `MaidTravelTarget.set(maid, t.pos())` 并回报坐标。
- `BookOp` 新增 `tdelete` / `trename`（删除/改名目标点，数据仍只由服务端从物品读→改→写）。
- `StartMarkingPacket` / `MarkPatrolPointPacket` 增加 `#target` 最前分支。
- `findEligibleMaid` / `dimensionOf` 新增；`sendBookOnly` 随开屏回推 `canFlyToTarget` 与玩家维度。
- 标记结束消息按模式区分：目标点提示去「目标点管理」/「飞过去」，轨道仍提示「连接」闭环。

### 三条位移链路（两树）

- `MaidBroomDrive.patrolPoint`：① 航图目标点 → ② `/broom_goto` → ③ 航迹。这样扫帚同乘、扫帚拴绳
  （`MaidBroomBehavior` ⑥ 在拴绳悬停之前调它）都自然生效。
- `RideBindManager.drive` 飞行载具无敌人分支：有航图目标时用 `MaidAirCombat.hoverAt(目标)` 替代
  `holdHere`/机上有人悬停；直升机女仆驾驶、玩家副驾适用于此。
- `MaidFlightFollowBehavior`：
  - `aimOf` 最前取 `MaidTravelTarget`（优先于拴绳原地悬停）；
  - `tick` 的拴绳早退只在没有航图目标时执行；
  - `canUse`：守家闸、距离闸在目标激活时放行；视线闸也放行；
  - `canContinue`：守家闸、60 秒 `MAX_TICKS`、收手半径都在目标激活时放行，飞到自己清掉为止。

### 扫帚追主窒息（两树）

- `MaidBroomDrive.safeY`：新增 `ceilingCap(level,bx,bz,bottom,top)`；同时量"她这一列"与"目标这一列"，
  取较低上限后再往下找两格空气；一格都放不下返回 `fromY`（旧版返回 `wantY`，即硬塞进方块）。
- `followPoint` / `homeOrbitPoint` 等调用点未变，仍然只问 `safeY` 一个口径。

## 验证

- 两树编译 `EXITCODE=0`（Forge 493 源文件 / Neo 498 源文件）。
- 14 项护栏 `FAIL 0 / WARN 0`：
  - `check_comment_style: PASS`（新增 3 处超长注释块已压到 8 行内）；
  - `check_arch`：新增两个同包文件 → 未覆盖基线 181 → 183，已在 `check_arch.py` 留痕；
  - `check_panel_coverage`、`check_workflow`、`check_i18n_keys`、`check_mixin_targets`（293 注入点）等全绿。
- 打包 + 部署 4 实例：`match=True`（Neo 18,219,004 / Forge 18,157,877 字节）。
- 四套专服回归 PASS（1201 / neoforge1211 × server / maid_load）。
- Forge jar：18,157,877 字节，SHA256
  `3CD677E18F448B76700E5237E0690DA76612513D556DACCCEF54FECBBA282439`。
- Neo jar：18,219,004 字节，SHA256
  `2A4D7CD252E80BD22EC4AEE346EA14CD25A46F14810826D80B2602250FA6BEDE`。

## 边界

- **目标点上限 32 个/本**，满了先删；轨道仍按原上限。
- **只存坐标**：点完「飞过去」后再删掉该目标点，已经起飞的那一趟仍会飞完；换维度、到达 4 格、
  30 分钟超时都会自动失效。
- **跨维度只读**：客户端变暗 + 服务端复核；跨维度不会自动传过去。
- **不新增飞行器**：三条链路复用各自的跟随/驾驶实现，接敌、避障、燃料、操控、落地保护都不变。
- **飞行跟随的视线闸**：只在航图目标激活时跳过；追主人仍是原来的 raycast 判定。
- **飞行跟随的防烧料闸**：「刚收手 3 秒内不起手」「上一趟判过追不上」这两条自动追主人用的闸门，
  在航图目标激活时也放行（玩家显式命令不该被拖住）；「无进展」仍会收手，但不会把这只女仆
  写进「追不上」封禁。
- **目标点不参与巡逻航迹**：不会自动沿目标点飞，只有玩家点「飞过去」这一趟。
- **空瓶**：主人物品栏满 → 空瓶掉在主人脚边（原版 `Inventory.add` 满则 `player.drop`）。
- 版本号仍为 **1.3.3**，不升号。

## 验收

1. **标记目标点**：手持航图右键 → 目标点管理 → 「＋ 标记目标点」→ 退出界面，中键连打三个点；
   世界里出现三个红色菱形（当前维度），再右键航图能在列表里看到「目标1/2/3」，可改名、删除。
2. **GUI 同款**：目标点列表/管理的按钮排布、分页、改名输入框与轨道页一致；主页面顶部能看到
   「目标点管理 (N)」。
3. **扫帚同乘**：与女仆同乘扫帚时右键航图 → 目标点管理 → 选一个目标 → 底部出现「✈ 飞过去」→
   点击后界面关闭，扫帚朝目标点飞；到达 4 格内自动回到平时的跟随/悬停。
4. **直升机**：女仆驾驶、玩家坐副驾时同样出现「飞过去」，飞机按原直升机飞行逻辑飞向目标；
   玩家自己坐驾驶位时不出现该按钮。
5. **武装拴绳**：分别把自己拴在空袭女仆、扫帚女仆下面，重复第 3 步；人跟着绳子被拖过去，
   目标点跨维度时按钮是暗的、点不动。
6. **扫帚窒息修复**：让女仆骑扫帚追飞到屋顶/天花板**上方**的主人（自己站在她正上方、头顶有方块）——
   她升到天花板下方就停住，不再穿进方块扣窒息；挪开天花板后能继续正常上升。
7. **空瓶**：主人背包塞满后让女仆喂药水，药效立即生效，空瓶掉在主人脚边。
