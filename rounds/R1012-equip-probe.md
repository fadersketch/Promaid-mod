# 实测一千〇一十二 装备消失诊断针（临时排查件 · 1.3.3 第 145 次同号重打）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）
- **跑道**：A 修 bug 的**定位阶段**。不猜根因、不对装备链路再做"看起来合理"的改动；先在服务端埋可判读的探针，由开发者实机复现，用日志定案。
- **开工前读过**：`R1004`（其中装备"误判补发"结论已被开发者当场否决：东西是真消失，补发不是误判）、`rounds/RELEASES.tsv`（第 144 次）、TLM 1.5.3 的 `MaidMainContainer` / `AbstractMaidContainer` / `EntityMaid`、Forge `SlotItemHandler` / `EntityEquipmentInvWrapper`、原版 `AbstractContainerMenu.doClick`、Forge `LivingEntity` 补丁（`LivingEquipmentChangeEvent` 的触发点）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 逐条排除已怀疑路径 | 原版点击链路 / Forge 槽位包装器 / MouseTweaks / 品质装备 / Curios / IPN / 遗物 / Tetra 附属 / 女仆厨房 / 大堆叠 均无直接删除点 | ☑ |
| 1 | 两树加净变化探针 | `EquipProbe` 逐 tick 对比光标/界面格/她的六槽/背包/饰品/玩家 41 格；`[装备离开]` 附带"界面开没开、光标拿着什么" | ☑ |
| 2 | 开发者第一轮实机 | 复现结果：**每次传送之后**才丢；槽位清空时光标为空、任何格子都没接住；`locateEquip` 连地面物品实体都扫过，仍"四处查无" | ☑ |
| 3 | 加点击包探针 + 写槽追踪 | `ContainerClickProbeMixin` 记录客户端每一次容器点击包；`EquipClearTraceMixin` 在槽位真正写空前抓调用栈 | ☑ |
| 4 | 编译 + 14 护栏 | 两树 `EXITCODE=0`；`FAIL 0`（`check_arch` 基线临时 +2 并留痕） | ☑ |
| 5 | 重打 + 装机 + 回归 | 双 jar；4 实例 `match=True`；专服三件回归 PASS | ☑ |
| 6 | 开发者第二轮实机 | 结论：**拾取点击完全正常**（服务端光标拿到装备），但**下次点击前光标已被清空**，且无任何格子/地面物品接住；丢件只在传送后出现 | ☑ |
| 7 | 加光标清理探针 | `CarriedClearTraceMixin` 抓 `setCarried` 调用栈；`EquipProbe` 增记光标对象身份（区分"换成空"与"原地清零"） | ☑ |
| 8 | 开发者第三轮实机 | 传送后取一件装备；日志出现 `[光标清理]` 或 `[光标对象]` | ☑ |
| 9 | 第三轮定案 | `[光标清理]` 0 条、`[光标对象]` 全为“同一对象原地清零” → 排除 `setCarried`，锁定 `setCount/shrink` | ☑ |
| 10 | 加 `setCount` 调用栈探针 | `ItemStackZeroTraceMixin` 只抓“目标数量 ≤ 0 且对象是光标上那一件”，打印 28 层栈与线程名 | ☑ |
| 11 | 第四轮实机 | 同一复现步骤，按 `[清零追踪]` 栈定位调用方 | ☑ |
| 12 | 定案 + 真修复 | 根因=单机本地连接不序列化 + 装备包不复制 → 客户端预判清零服务端共享对象；两树在 `ClientboundSetEquipmentPacket` 构造器逐枚复制 | ☑ |
| 12b | 修复启动崩溃并重打 | `@ModifyVariable` 在构造器 `super()` 前必须 `static`；改静态后 javap 核对、专服启动无 `Mixin apply failed`、三回归 PASS | ☑ |
| 13 | 第五轮实机验收 | 传送后反复取放装备，不再消失；装备成功落进目标格 | ☑ |
| 14 | 拆除探针 | 删除五个临时探针，`check_arch` 基线回 181，重打/回归 | ☑ |

## 需求 / 来源
- 开发者原话：「你先加一个探测针在游戏里面，标记当前女仆穿的装备和主副手，然后我在游戏里面实际测试，你来验收。」
- 开发者第二轮反馈：「游戏运行完成，发现是每次进行传送以后会发生这个问题。」
- 背景：开发者已明确纠正上一轮结论——从女仆装备槽/主副手拖到玩家物品栏、**放下那一下物品真的消失**，补发不是误判，要求定位真正原因。

## 第一轮探针的结论（18:17–18:18 实机）
- 不传送时：整套"拿起 → 光标 → 放进玩家背包 → 托管确认落定"链路完全正常（`[装备探针]` 逐 tick 可见光标与目标格同步变化）。
- 传送之后（`位移基线 97 格` + 当帧/延迟重建客户端实体）：打开界面时六件装备都在；随后 `HEAD/CHEST/LEGS/FEET/MAINHAND/OFFHAND` 逐个被清空，每一条都是 **`光标=空`**，且菜单所有格、玩家 41 格、她的背包/饰品、地面 16–24 格内的物品实体全都没有接住。1.5 秒后托管把副本放回她的背包。
- 关键否证：`[装备离开]` 的调用栈只有 `LivingEntity.tick → collectEquipmentChanges → 事件`。Forge 的 `LivingEquipmentChangeEvent` 是在 **tick 的差异检测**里补发的，不是写槽现场；所以这条栈回答不了"谁清空的"，必须再抓 `setItemSlot` 的调用栈与客户端点击包。

## 第二轮实机结论（18:48–18:49，传送前后对照）
- **传送前**（18:48:51–18:49:05）：六件装备逐件从她身上拿起、再装回她身上，全部正常。`[点击探针]` 的"后"与下一次"前"都能看到光标持有物品，`[装备探针]` 能看到目标槽位落定。
- **传送后**（18:49:32 传送 180 格，18:49:34 开界面）：每一件的固定节奏变成——
  1. `[清槽追踪]` + `[点击探针]`：PICKUP 点击正常，`handleContainerClick → clicked → doClick → Slot.remove → SlotItemHandler.remove → EntityEquipmentInvWrapper.extractItem → EntityMaid.setItemSlot`，服务端光标 = 该装备；`[装备离开]` 也确认 `光标=该装备`。
  2. **下一次点击的 HEAD：服务端光标已经是空**（`前(光标=空)`），客户端包里的 `改动={玩家格=该装备}` 却仍以为自己在放下；服务端该玩家格是 `guide_book`，于是这次点击什么也没做。
  3. `[装备探针]` 在两次点击之间只落一行 `光标 该装备 → 空`，没有任何槽位/背包/地面接手。
- **结论**：拾取链路没有问题，物品是在**两次点击之间**被清掉的；清掉时既没有点击包、也没有任何容器接收。下一步必须抓 `setCarried` 的调用方。

## 第三轮实机结论（19:08，传送前后对照）
- **传送前**（19:08:29–38）：六件装备逐件取下、再放回她身上，全部成功。注意 `[光标对象]` 在每次**成功放置**时同样打印“同一对象被原地清零（count=0）”——正常的“放下”本来就是 `carried.split()` 原地清零，所以这条现象本身不能区分成功与丢件；真正的判据是“下一次点击 HEAD 时服务端光标已空且没有任何槽位/地面接手”。
- **传送后**（19:08:45 起）：拾取点击正常，`[清槽追踪]` 栈完整（`handleContainerClick → clicked → doClick → Slot.remove → SlotItemHandler.remove → EntityEquipmentInvWrapper.extractItem → EntityMaid.setItemSlot`），服务端光标=该装备。
- 约 0.8 秒后的放置点击：服务端 HEAD `前(光标=空)` 已经是空，客户端包仍预测 `改动={玩家格=该装备}`；服务端该格是 `guide_book`，这一下什么也没做。
- `[光标清理]` **0 条**；`[光标对象]` 8 条**全部**是“同一对象被原地清零（count=0）”。→ **不是 `setCarried(EMPTY)`**，是有人对服务端光标上的 ItemStack 直接 `setCount/shrink`。
- 清零窗口（45.345 拾取 tick 结束 → 46.117 放置包到达）内：**没有任何其他模组的日志**、没有任何点击包、没有槽位/背包/地面接手。→ 清零发生在服务端主线程的某个 tick 处理器里，且它持有这枚 ItemStack 的引用。
- 下一步：把 `ItemStack.m_41764_`（javap 实证 = `setCount`）挂上调用栈探针；`shrink / split / ContainerHelper.remove / clearOrCountMatchingItems` 最终都汇到它。

## 第四轮实机结论与根因（19:34，传送前后对照）
- **传送前**（19:34:08–18）：六件装备取放全部成功，`[清零追踪]` 全部是 **Server thread** 的正常放置栈（`doClick → Slot.setByPlayer → split`）。
- **传送后**（19:34:21 传送 146 格 → 19:34:22 客户端实体重建 → 19:34:24 开界面）：拾取正常；下一次放置点击时服务端 HEAD 光标已空；`[清零追踪]` 全部是 **Render thread** 的客户端放置预判栈（`MultiPlayerGameMode.handleInventoryMouseClick → doClick → split → setCount(0)`），服务端侧没有任何清零调用。
- **根因**（三层叠在一起）：
  1. 单机整合服的本地连接**不走网络序列化**（`Connection$2.initChannel` 只挂 `packet_handler`，没有 encoder/decoder；javap 实证）。
  2. `ClientboundSetEquipmentPacket(int, List<Pair<EquipmentSlot, ItemStack>>)` 的构造器**只把列表原样存下**，不复制 `ItemStack`（两树 javap 实证；对比 `ClientboundContainerSetSlotPacket` / `ClientboundContainerSetContentPacket` 构造器都复制）。
  3. 传送后的客户端实体重建会重发装备包，于是客户端女仆的装备格和服务端持有**同一枚 ItemStack**；客户端点击预判的 `carried.split()` 把它原地 `setCount(0)`——服务端光标（同一个对象）跟着变空，下一次放置点击自然什么也放不进去，物品真丢。
- 这也解释了为什么只有传送后出现：重建把装备对象重新“原样递”给客户端；传送前的装备格是两边各自的副本。
- **真修复**：`EquipmentPacketCopyMixin` 在 `ClientboundSetEquipmentPacket` 构造器入参处逐枚 `copy()`，切断跨端对象共享（两树镜像）。专服本来会在序列化时复制，多一次复制无行为影响。

## 启动崩溃与修复（20:00 开发者回报）
- 开发者启动上一版修复包时，在"点击世界进入"瞬间崩溃。crash report 的 Suspected Mod 指向 Yeetus Experimentus，但日志里的真实错误是
  `Mixin apply failed mixins.promaid.json:EquipmentPacketCopyMixin -> net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket: org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException @ModifyVariable handler before super() invocation must be static`。
- 原因：`@At("HEAD")` 在构造器里位于 `super()` 之前，Mixin 强制要求此处的 `@ModifyVariable` 处理器为 `static`；上一版写成了实例方法。
- 修复：两树的 `promaid$copyEquipmentStacks` 改为 `private static`。已用 `javap -p` 核对两个新 jar 内的类文件，处理器均为 `private static`；专服启动 + status ping 均到 `Done (` 且无 `Mixin apply failed` / `InvalidInjectionException`。
- 只改处理器修饰符，复制逻辑与根因结论不变。

## 探针口径（四版累计）
1. **`[装备探针]`**（已有，保留）：逐 tick 净变化，回答"东西最后落在哪个格子 / 是否真丢"。
2. **`[点击探针]`**（`ContainerClickProbeMixin`，仅 Forge 树）：在 `ServerGamePacketListenerImpl.handleContainerClick`（`m_5914_`）的 HEAD/RETURN 成对记录——点击类型、槽号、按键、容器/状态 id、`changedSlots`、包内光标、处理前/后的服务端光标与槽位。它回答"客户端到底发了什么、服务端执行成了什么"；出现 `★槽位清空且光标为空★` 即命中现场。
3. **`[清槽追踪]`**（`EquipClearTraceMixin`，两树）：在 `EntityMaid.setItemSlot` 的 HEAD，仅当"old 非空、new 为空"时打印 16 层调用栈，并同时读出"谁开着她的界面、光标上是什么"。它回答"服务端是哪一行代码把槽写空的"。
4. **`[光标清理]`**（`CarriedClearTraceMixin`，两树）：在 `AbstractContainerMenu.setCarried`（forge `m_142503_`）的 HEAD，仅当"女仆界面、old 非空、new 为空"时打印 20 层调用栈。正常"放下"会带 `handleContainerClick → doClick`；若不是点击链，栈顶就是真正清光标的地方。
5. **`[光标对象]`**（`EquipProbe` 增强）：持住上拍光标上那个 `ItemStack` 的引用，下一拍若光标为空，打印"同一对象被原地清零"或"菜单里的 carried 被换成了空/别的对象（旧对象现值=…）"——用于区分 `setCarried(EMPTY)` 与原地 `shrink(0)`。
6. **`[清零追踪]`**（`ItemStackZeroTraceMixin`，两树，第四版新增）：在 `ItemStack.m_41764_`（javap 实证 = `setCount`）的 HEAD，仅当“目标数量 ≤ 0、对象非空、且它正是某玩家光标上那一件”时打印 28 层调用栈与线程名。`EquipProbe` 每次光标变化时把对象引用拍进 `WATCHED` 数组，热路径只做身份比较，不持锁、不碍事。
- **SRG 实证（本轮用 javap 核对，上一版有三处写错）**：`m_41764_` = `setCount`、`m_41769_` = `grow`、`m_41774_` = `shrink`、`m_41620_` = `split`（不是 setCount）。`setCount` 是唯一直接写 `count` 字段的方法，`shrink/split` 都经它。
- 点击包字段的 SRG 对应（本轮用 javap 核对）：`m_133962_` = slotNum、`m_133963_` = buttonNum、`m_182741_` = stateId；探针现有的"槽/键/状态"标签写反了，只影响可读性，不影响结论（`改动={}` 与 `包光标` 是准确的）。
- 注意：`LivingEntity.setItemSlot`（forge `m_8061_`）是**抽象方法**，没有指令节点，Mixin 在 HEAD 取节点会拿到 null 直接崩服（本轮实测踩过，`Mixin apply failed … Unexpecteded NullPointerException`）。因此 `EquipClearTraceMixin` 挂在 `EntityMaid` 自己的覆写上——既精确，又覆盖所有虚拟调用。

## 改法
- 新增 `promaid_src/com/maidsmart/diag/EquipProbe.java`（Neo 同构）。
- `ProMaidExtension.onServerTick`（END）每 tick 调 `EquipProbe.tick(server)`。
- `onMaidEquipmentLeave` 的 `[装备离开]` 行追加 `openMenuState(maid)`。
- 新增 `promaid_src/com/maidsmart/mixin/ContainerClickProbeMixin.java`（仅 Forge；Neo 树不装，因为实机只在 1.20.1 复现）。
- 新增 `promaid_src|promaid_src_neo/com/maidsmart/mixin/EquipClearTraceMixin.java`（两树镜像）。
- 新增 `promaid_src|promaid_src_neo/com/maidsmart/mixin/CarriedClearTraceMixin.java`（两树镜像）。
- 新增 `promaid_src|promaid_src_neo/com/maidsmart/mixin/ItemStackZeroTraceMixin.java`（两树镜像；forge 挂 `m_41764_`，Neo 挂 `setCount`）。
- `EquipProbe` 增强：新增 `[光标对象]`，区分 carried 被换成 `EMPTY` 与同一对象被原地清零；新增 `WATCHED` 对象快照与 `isWatchedCarried`，供 `setCount` 探针做身份判定。
- **真修复**：新增 `promaid_src|promaid_src_neo/com/maidsmart/mixin/EquipmentPacketCopyMixin.java`，在 `ClientboundSetEquipmentPacket` 构造器入参处逐枚 `copy()`；这是**长期代码**，已登记进 `tools/arch/arch_modules.py` 的 `promaid.mixin.network-chunk` 模块，不是临时探针。
- 两个 `mixins.promaid.json` 登记新 mixin。
- `check_arch.py` 的 `BASELINE_UNCOVERED['promaid_src_neo']` 181 → 185，留痕：五个文件都是临时排查件，定案后随探针一起删除并把基线改回 181。

## 怎么验收（开发者实机步骤）
1. 启动 `龙之冒险：新征程 v2.4a`（本轮已装机，Forge jar 18,025,203 字节，sha256 `bbb4e6ed…d2b2`）。
2. 先让女仆传送一次（传送她或让主人带她跨距离），再打开她的物品栏/装备栏。
3. 把六件装备逐件从她的装备槽/主副手拖到玩家物品栏放下，再逐件放回她身上；反复两轮。
4. 重点看：每次放下都应立刻出现在目标格、光标清空且**不触发“补包到脚下”**；`[装备救回]` 不应再出现。
5. 关掉界面并退出游戏。若仍有异常，回传 `D:\.minecraft\versions\龙之冒险：新征程v2.4a\logs\promaid.log`；正常时按 `[清零追踪]` 应只看到 **Server thread** 的正常放置栈，不再出现 Render thread 的清零。

## 验证
- 两树编译 `EXITCODE=0`。
- 14 护栏：`check_arch` / `check_catch_swallow` / `check_comment_style` / `check_mixin_targets` 等全部 `FAIL 0`；`check_workflow` `WARN 0`。`check_arch` 对 Neo 的未覆盖基线临时为 185（五个诊断文件），定案删除后改回 181。
- `check_mixin_targets`：Forge 已校验注入点 277、Neo 137，均高于下限。
- 专服回归：`test_server.py 1201` PASS、`test_server.py neoforge1211` PASS、`test_maid_load.py neoforge1211` PASS（含一次"抽象方法注入"崩溃的修复后复跑）。
- 部署：4 个实例 `match=True`；Forge jar `bbb4e6ed…d2b2`（18,025,203 字节），Neo jar `f7cce794…1d5e`（18,069,697 字节）。
- 架构产物：`tools/arch/build_arch.py` 三步全过（120 模块 / 76 边），`EquipmentPacketCopyMixin` 已归入 `promaid.mixin.network-chunk`。
- 以上编译、护栏、回归与部署数字均为 **static 修复后的重跑**（旧 `dbaa673e…ab15` / `630cfdc7…310e` 作废）。

## 验收
- 实机第三轮（19:08）已完成：排除 `setCarried`，锁定“同一对象被原地 `setCount(0)`”。
- 实机第四轮（19:34）已完成：Render thread 预判清零、服务端无清零 → 根因=单机本地连接不序列化 + 装备包不复制导致跨端对象共享。
- 实机第五轮（开发者回「修好了」）通过：传送后逐件取放两轮，装备正常落进目标格，不再消失；
  不再出现 `[装备救回]`，日志里不再出现 Render thread 的 `[清零追踪]`。
- 验收后已在 R1013 拆除五个临时探针（`EquipProbe` / `ContainerClickProbeMixin` / `EquipClearTraceMixin` /
  `CarriedClearTraceMixin` / `ItemStackZeroTraceMixin`），`check_arch` 基线回 181；`EquipmentPacketCopyMixin` 保留。
- 发布素材按开发者本轮指令在 R1013 补写；版本号仍 1.3.3。

## 边界
- 这些探针都是**只读观测**：不拦截、不取消、不修改任何包或槽位；`EquipProbe` 每 tick 只做一次 `instanceof`，`[光标对象]` 只持引用观察。
- `ContainerClickProbeMixin` 只对 `AbstractMaidContainer` 生效，非女仆界面不落日志。
- `EquipClearTraceMixin` / `CarriedClearTraceMixin` 只在"女仆、服务端、old 非空、new 为空"时落日志，正常换装不会刷屏。
- `ItemStackZeroTraceMixin` 只在“`setCount` 目标 ≤ 0、对象非空、且对象正是某玩家光标上那一件”时落日志；`WATCHED` 为空时只多两次布尔判断。它也**只读**，不改变任何数量。
- `EquipmentPacketCopyMixin` 是**行为修复**：装备包构造时每枚 `ItemStack` 复制一次。只在发装备同步包时发生（换装/配对/传送重建），不在每 tick 热路径；复制失败会退回原列表并留痕，不阻断同步。
- 实机验收通过后整类删除 `EquipProbe` / `ContainerClickProbeMixin` / `EquipClearTraceMixin` / `CarriedClearTraceMixin` / `ItemStackZeroTraceMixin`，并把 `BASELINE_UNCOVERED` 改回 181；`EquipmentPacketCopyMixin` 保留。本轮不提交远端。

## 触及文件
- `promaid_src/com/maidsmart/diag/EquipProbe.java`（新增）
- `promaid_src_neo/com/maidsmart/diag/EquipProbe.java`（新增）
- `promaid_src/com/maidsmart/mixin/ContainerClickProbeMixin.java`（新增，仅 Forge）
- `promaid_src/com/maidsmart/mixin/EquipClearTraceMixin.java`（新增）
- `promaid_src_neo/com/maidsmart/mixin/EquipClearTraceMixin.java`（新增）
- `promaid_src/com/maidsmart/mixin/CarriedClearTraceMixin.java`（新增）
- `promaid_src_neo/com/maidsmart/mixin/CarriedClearTraceMixin.java`（新增）
- `promaid_src/com/maidsmart/mixin/ItemStackZeroTraceMixin.java`（新增）
- `promaid_src_neo/com/maidsmart/mixin/ItemStackZeroTraceMixin.java`（新增）
- `promaid_src/com/maidsmart/mixin/EquipmentPacketCopyMixin.java`（**真修复，长期保留**）
- `promaid_src_neo/com/maidsmart/mixin/EquipmentPacketCopyMixin.java`（**真修复，长期保留**）
- `tools/arch/arch_modules.py`：`promaid.mixin.network-chunk` 登记 `EquipmentPacketCopyMixin`，重跑 `build_arch.py`
- `promaid_src/mixins.promaid.json` / `promaid_src_neo/mixins.promaid.json`：登记新 mixin
- `promaid_src/com/maidsmart/ProMaidExtension.java` / `promaid_src_neo/...`：tick 调用 + `openMenuState`
- `tools/verify/check_arch.py`：基线临时 +4（181 → 185，留痕；验收后随探针删除改回 181）

## 备注
- 本轮**不是**修复轮，不写发布素材、不动版本号、不提交远端。定案后另行开修复轮。
- 已做过的静态扫描（全 mods 目录，按常量池里的 SRG 名）：`setCarried`（`m_142503_`）、`Inventory.clearOrCountMatchingItems`（`m_36022_`）、`transferState`（`m_150414_`）。
  - `clearOrCountMatchingItems` 的调用方：品质装备的"重铸按钮"（谓词只匹配按钮本身）、生于混沌的护符、传奇怪物的苔藓锤；女仆厨房的 `WrappedMaidInventory` 是把它覆写成返回 0 的假背包。都不匹配护甲/剑/盾。
  - `transferState`：没有任何 mod 调用。
  - `setCarried` 的其余引用：JEI/TrashSlot/遗物/KubeJS 等都有明确的前置（删除键、垃圾桶界面、特定遗物、脚本 API）；Curios 的 `SPacketGrabbedItem` 是服务端→客户端同步；本模组只有 `CompressionBoxClickMixin`，且前置是"光标上正好是 1 个压缩盒"。
  - 原版服务端能清 carried 的只有 `doClick` 与 `Inventory.clearOrCountMatchingItems`；前者已由点击包排除，后者没有匹配装备的谓词。故仍需 `[光标清理]` 的实栈定案。
- 第三轮之后的补充静态复查（按正确的 SRG 名）：
  - `m_41764_` = `setCount`、`m_41769_` = `grow`、`m_41774_` = `shrink`、`m_41620_` = `split`。
  - 精妙背包 `RefillUpgradeWrapper`：`getCarried()` 只用于比较数量；两处 `setCount` 都落在 `copy()` 出来的临时件上，不是光标对象。
  - 精妙背包 `BackpackItem`：唯一的 `shrink` 在“放置背包方块成功”分支，且减的是放置用的 `ItemStack`；`getCarried()` 只在客户端 tooltip 里判空。
  - Curios：`CuriosEventHandler` 的 `shrink` 在“右键把手中饰品装上”分支；`LivingEquipmentChangeEvent` 处理器只读 from/to。
  - TLM `EntityMaid`：类内唯一 `shrink` 在 `tameMaid` 消耗驯服物品；`MaidMainContainer` 及其四个槽子类没有任何 `setCount/shrink`。
  - 静态层面已无“全 tick 清光标”的明显调用方 → 必须靠 `[清零追踪]` 的实栈。
- 已排除但未证伪的候选（供定案后回看）：TrashSlot（TLM 女仆界面默认 `isEnabled=false`）、物品实体清理（`ClearSchedule=false`）、女仆 AI 自动换装（只动主手/胸甲，与四件护甲同时丢不符）、客户端实体重建（第一轮已确认：每次传送后必现，正是当前最大嫌疑面）。
- 第一轮日志里缺失的几条 `[装备离开]` 可用 TLM 的 `quickMoveStack` 解释：Shift 取装备后 TLM 会把 `lastArmorItem / lastHandItem` 直接对齐成旧值，刻意压掉原版的差异检测与事件；但与本次"拖动"路径无关。
