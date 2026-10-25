# R1101 熔炉等价族 + 营火/灵魂营火烹饪（两个新开关，默认关）

## 排班

- **跑道**：C 功能（两树镜像）。
- **开工前读过**：`MaidCookBehavior`（两树全文）、`MaidSmartConfig`、`MaidExtraContainer`、
  `WorkAreaClamp`、`DangerBlocks`；反编译的 `CampfireBlockEntity`（1.20.1 与 1.21.1 两版）、
  `AbstractCookingRecipe`、`RecipeManager`；龙之冒险整合包 256 个模组的熔炉类族扫描
  （暮色森林 `CinderFurnaceBlockEntity extends FurnaceBlockEntity`、农夫乐事炉灶、
  更好的熔炉重铸版 `SmeltingBlockEntity` 自定义容器、ProjectE 黑暗物质炉）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 定"等价族"口径 | 方块挂 AbstractFurnaceBlock 族，或（开关开）实体挂 AbstractFurnaceBlockEntity 族 | ☑ |
| 1 | 新配置 misc.cookFurnaceFamily / misc.cookCampfire | 两树 + 4 语言文件 + OFF_ACK + 开关清单 | ☑ |
| 2 | 等价族接入 findFurnace/绑定校验/加工门 | 开关关 = 行为与旧版一字不差 | ☑ |
| 3 | 营火：找火→站旁边→摆食材→捡成品 | `CampfireBlockEntity.placeFood/getCookableRecipe` 同原版口径 | ☑ |
| 4 | 不站火格 | 走位目标只挑安全邻格；已在火格上先挪开 | ☑ |
| 5 | 编译/护栏/测试/部署 | 两树 OK；14 护栏 FAIL 0；四套 PASS；4 实例 match=True | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

1. "整合包内还有其他的熔炉（参考龙之冒险）。能否去兼容熔炉的等价族？作为一个新的配置开关。"
2. "而且能不能进入篝火和灵魂营火呢？烧肉（或者其他可以被营火烧制的菜）的时候，女仆会自己把
   肉摆上去，烧好了以后自动收回背包。而营火的上面是有伤害的。所以女仆不能站到篝火上。
   只能在营火的周围。"

## 评估

- **等价族怎么定**：扫了龙之冒险 256 个模组——模组炉分两类。一类**实体挂在原版熔炉族**
  （暮色森林灰烬熔炉 `CinderFurnaceBlockEntity extends FurnaceBlockEntity`，方块却只是
  `BaseEntityBlock`）：槽位语义与原版逐字一致（0 进料/1 燃料/2 成品），喂料零风险，但旧版
  `findFurnace` 只认 `AbstractFurnaceBlock` 方块类 → 看不见它们。另一类**完全自定义容器**
  （更好的熔炉重铸版 `SmeltingBlockEntity`：自己的 `FactoryItemHandler`、槽位由
  `FINPUT()/FUEL()/FOUTPUT()` 等方法动态决定、`canPlaceItem` 不区分槽位）：没有任何通用协议
  能安全地判槽位，**不纳入本开关**（见边界）。
- **营火**：原版 `CampfireBlockEntity` 四个食材位，`placeFood` 摆（消耗 1 件）、烤好由
  `cookTick` 把成品**弹出成掉落物**堆在火格上（`Containers.dropItemStack`）；灵魂营火同一套
  方块实体。所以"收回背包"= 捡掉落物；而火格本身对站在上面的实体有伤害 → 站位必须只挑邻格。

## 改动（两树镜像）

- **新配置**：`misc.cookFurnaceFamily`（熔炉等价族，默认关）、`misc.cookCampfire`（营火烹饪，
  默认关）；语言文件 4 份、`check_preset` OFF_ACK、`ARCHITECTURE-SWITCHES.md` 均已登记。
- **等价族**（`MaidCookBehavior`）：新判据 `isFurnaceFamilyBlock`——方块挂
  `AbstractFurnaceBlock` 族照旧；开关开时"方块不是熔炉类、实体却挂
  `AbstractFurnaceBlockEntity` 族"也算。接入三处：`findFurnace` 扫描、tick 的绑定校验、
  加工门（`processFurnace` 前的 instanceof 闸）；`furnaceMatchesInv` 的"食物可喂"判据换成
  `isFurnaceLikeBlock`。开关关 = 三处全部回到旧判据。
- **营火**（`MaidCookBehavior`）：新字段 `stationCampfire` 标记台型；`findStation` 在
  "营火开关开 + 身上有营火配方食材"时优先绑营火（共用炉子占用表防多女仆挤一台）；
  `campfireStandSpot` 只挑"脚下/头上不实、非营火、非危险方块、在工作圈内"的邻格（就近），
  绑定期间若她已在火格上先挪开；`processCampfire` 每轮 ① 捡火边 1.5 格内**营火配方的成品**
  掉落物（`isCampfireResult` 按配方成品比对，别人丢的东西不碰）② 从背包取 1 件
  `getCookableRecipe` 认可的食材 `placeFood` 摆上（满载即停，与原版右键同一道闸）。
  看门狗/占用释放/工作区校验对营火同样生效。

## 验证

- Neo 编译 `NEO_OK`；Forge 编译 `FORGE_OK`。
- 14 项护栏 `FAIL 0 / WARN 0`（tree_mirror 为只读报告不设门槛）。
- 专服回归：`test_server.py 1201` PASS、`test_maid_load.py 1201` PASS、
  `test_server.py neoforge1211` PASS、`test_maid_load.py neoforge1211` PASS。
- Neo jar：18,219,859 字节，SHA256
  `CB04D2BF1BC578CBAC6A1628C387FA1FDFDA5F58CF1D0DF502B70839DFC4DA6A`。
- Forge jar：18,158,546 字节，SHA256
  `ACDCB784AE0A4A1547A6ECCAFD9AF635FFFCF06226DE0091A72C2661C1130475`。
- 已部署 4 实例，`match=True`。

## 边界

- **更好的熔炉（重铸版）不在等价族内**：它的 `SmeltingBlockEntity` 是自定义容器体系
  （槽位动态、`canPlaceItem` 恒真），没有通用协议能安全判"哪个是进料口/燃料口/成品口"，
  盲塞会丢东西。要做需要按该模组单独适配（可作为下一轮）。
- 营火只认原版 `CampfireBlock` 族（含灵魂营火）；农夫乐事的炉灶（StoveBlock）是它自己的
  方块实体与配方，不在本轮。
- 两开关默认关：不改任何现有存档行为；开启后"有营火食材 + 工作区有点着的营火"时营火优先
  于熔炉（不耗燃料），没有营火回落熔炉链路。
- 营火食材判定走 `CAMPFIRE_COOKING` 真实配方 + `isSafeToFeed`（装备/附魔永不喂）；
  收成品只捡配方成品，火边其他掉落物不碰。

## 验收

1. **熔炉等价族**：开关开启后，暮色森林灰烬熔炉会被烧制女仆认领并正常喂料/收成品
   （旧版：附近明明有炉子却一直 furnace=null 闲逛）。
2. **营火**：开关开启 + 背包有生肉 + 附近点着营火 → 她走到火旁邻格站定，把肉一个个摆上
   （最多 4 个位），烤好的成品弹出后自动进她背包；全程不站火格、不掉血。
3. **灵魂营火**：同上。
4. **两开关关闭**：与 1.3.3 之前行为一字不差（回归重点）。
