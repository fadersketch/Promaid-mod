# R993 三件：飞行跟随换装守恒（不复制胸甲）/ 蓝图脚手架支撑闸门 / 建造放置未成退料

> 上一轮：`rounds/R992-throw-tnt-minecart-landing-switch-tag-lines.md`（TNT 矿车 / 拟真降落 / 矿表标签行，已发布 1.3.2）。
> 开发者原话（实机反馈，两条）：
> 1. 「鞘翅飞行跟随 会复制胸甲，还会吞物品，详情见刚运行的游戏」
> 2. 「蓝图有脚手架的情况下 脚手架下面的方块没有放 先放脚手架 但是放不上 然后一直放」
> 本轮把「吞物品」拆成两条可证实的路径（还装复制 / 建造放置未成吞料），共三件修复。

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修 bug（三件都是"现状错、要改对"；没有新玩法/新开关）。
- **开工前读过**：
  - `rounds/RELEASES.tsv`：v1.3.2 两条 `published`（哈希冻结，2026-10-16）→ 本轮有内容变更，**按 §5.5 升号 1.3.3**（绝不"同号覆盖"）。
  - `ARCHITECTURE-SWITCHES.md`：无"还装/脚手架支撑"相关等价开关；本轮**不新增开关**（修错，不是改语义）。
  - `BUG_DEBT.md` / `TECH_DEBT.md`：无这三条的既有登记（见「台账」）。
  - 上一轮记录 R992：确认 1.3.2 已发布、本轮顺延 R993。
- **工作队列**（逐步，做完就勾；每步写"完成判据"）：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 定位（日志/存档/javap 取证） | 三条根因各指着证据（日志行 / 存档 NBT / 字节码）说 | ☑ |
| 1 | 根因 + 同类点 grep | `m_8061_(CHEST` 全树 3 处逐一处置；`supportDirection` 调用点核对；`consumeBlock` 消费链核对 | ☑ |
| 2 | 两树改 + 编译 | 两树 `javac` `EXITCODE=0` | ☑ |
| 3 | 14 护栏 + jar + 升号 1.3.3 | 护栏 `FAIL 0 / WARN 0`；双 jar；§5.5 清单全勾 | ☑ |
| 4 | 打包装机（4 实例） | 4 个目标与 `patched/` 同字节（`match=True`） | ☑ |
| 5 | 交开发者验收 | 开发者实测两条反馈场景后给出结论 | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法：
  1. **①复制胸甲**：给女仆穿/背包里放一件普通胸甲 + 一件鞘翅，开飞行跟随飞一趟再落地；落地后**数胸甲**——应始终 1 件（旧版每趟 +1）。
     日志关键词：`[飞行跟随] … 已落地，把胸甲还回去` / `收翅回背包（本趟没穿胸甲）` / `原胸甲已不在她身上，不补件`。
  2. **②脚手架**：下达含脚手架的蓝图（如 `房屋__中世纪庄园` / `红石__全树种树场`），观察脚手架位置——应**等下方块建好再放**、放上后不再消失。
     日志关键词：`build place-fail: minecraft:scaffolding@…`（应只在"补支撑"场景出现，不再有"目标现=minecraft:air"的反复失败）。
  3. **③退料**：找个会反复延后的位置（缺料蓝图），失败重试期间**背包材料数不减少**。
     日志关键词：`[归还] … 建造放置未成退回`。

## 需求 / 来源

- 开发者实机反馈两条（上文原文）；「详情见刚运行的游戏」= 2026-10-16 08:25–08:29 NEO 实例会话（`promaid.log` 17507–17595 行）+ 已落盘存档 `saves/新的世界`（level.dat 10-16 08:29）。

## 评估

- **跑道 A**；级别：① P0（物品守恒：复制+可能吞）；② P0（建造缺格 + 无痕 + 无限重试）；③ P1（材料静默损耗，②的前置）。
- **根因三问（逐条指着证据说）**：

### ① 飞行跟随换装 —— 复制胸甲 / 结算空转

- **证据（日志）**：10-16 08:28:10/28/48 三条 `[飞行跟随] … 已落地，把胸甲还回去`；但全会话只有 08:27:08 一条 `[装备离开] 槽=CHEST 失去 majestic_elytra`（且 1.5 秒后 `[装备落定] … 已确认落定（她身上）`，即玩家操作后被托管网接住）。**收手时胸甲槽根本没被动过**——事件链里没有任何对应的"槽被清空"。
- **证据（存档 NBT）**：解析 `saves/新的世界/entities/r.-1.-1.mca`（nbtlib；女仆 `touhou_little_maid:maid`）：三份快照（不同区块的三次保存）里 `ArmorItems[2]` 都穿着 majestic_elytra（nBT 330→341），背包 36 格里**没有**胸甲副本——本轮会话恰好是"她本来就穿着可用鞘翅"档位，`wearElytra` 第 1076 行提前 return，`SWAPPED_CHEST` 没有登记 → `restoreChest` 的 `orig == null` 分支静默什么都不做，**但日志照样打"把胸甲还回去"**（实测空转）。
- **证据（代码 · 复制机制）**：`MaidFlightFollowBehavior.wearElytra`（1074–1091）：换下原胸甲时 `SWAPPED_CHEST.put(id, old.copy())` 存**副本**，同时 `MaidGiveBack.give(maid, old, …)` 把**真品**还给背包；`restoreChest`（1094–1113）：收手时把**副本** `maid.m_8061_(CHEST, orig)` 直接穿回胸甲槽。⇒ 一个"换下→收手"循环里，真品在背包、副本在胸甲槽，**物品净 +1**；`!isElytraLike(now)` 那一支（玩家中途换过）又把副本 `give` 进背包，同样 +1。真品只应换手、绝不该有第二件。
- **同类点 grep**：全树对胸甲槽的写入只有 3 处——`MaidFlightFollowBehavior:1084/1107`、`MaidFlightKit:980`（空袭 `equip`，换下即还包、无还原链、守恒，不动）。
- **复现**：给女仆穿普通胸甲 + 背包放鞘翅 → 开飞行跟随（起飞距离内）→ 飞一趟落地 → 数胸甲（旧版 2 件，飞 N 趟 N+1 件）。

### ② 蓝图脚手架 —— 放上就被原版打落 / 无痕 / 反复重放

- **javap 实证（1.21.1 `client-…-srg.jar`）**：`ScaffoldingBlock.canSurvive = getDistance(level,pos) < 7`；`getDistance`：下方是脚手架→取它的 distance，下方 `isFaceSturdy(UP)`→0，否则水平四邻脚手架 distance+1，都没有=7。`updateShape` 每次邻居更新都 `scheduleTick(pos, block, 1)` 自检；`tick` 里 distance==7 → 方块自己的 distance 也 7 就 `FallingBlockEntity.fall(...)`，否则 `destroyBlock(pos, true)`（**打落/掉碎**）。⇒ 悬空脚手架活不过"下一次邻居更新 +1 tick"。
- **证据（代码）**：`BlueprintPlacement.supportDirection`（94–210）的附着名单里**没有脚手架** → `doPlace` 的支撑闸完全不设防；`setBlock` 无条件放上；同一 tick 的"放置后检查"（1301–1305）看到的还是刚落下的脚手架 → **记账 = 已建成**。随后活建造 flag 3 邻居更新 / 完工 `activateWater` / 玩家动一下 → 原版打落 → 位置留洞、日志无痕。
- **证据（实机）**：10-14 17:33 轨道炮主炮（12554 格）竣工 `[1520] scanGaps: 缺口 23 个：…[scaffolding×2]…`，而 `[1529] snapFinish` 前账目显示"已放 12554"——缺的正是上述无痕档；同场 `build place-fail` 54 条（activator_rail=48 / powered_rail=6，`目标现=minecraft:air`）是"放上→被打落→延后重试"的同一形状。用户第 2 条的"先放脚手架 但是放不上 然后一直放" = 支撑未建时的"放上→打落→40t 重试"循环。
- **同类点**：全 4 实例 80 份蓝图扫描（nbtlib 解 litematic）——含脚手架 8 份，共 49 格：庄园 3 格（下=deepslate_tiles）、树场 27 格（下=玻璃/活塞/活板门/脚手链）、飞艇 3、天空之城 4、热气球 4、bilibili 2、别墅 5（脚手链）、酒馆 1——**全部是"下方/侧邻合法、但下方块后建就可能先放"的形状**，闸门必须对全部生效。

### ③ 建造放置未成吞料（②的前置）

- **证据（代码）**：主循环 594 行与延后重试 826 行都**先扣料后放置**（`BlueprintLib.consumeBlock` → `BlueprintMaterials.extractExact/extractOneHonest` 真扣背包）；`doPlace` 返回 false 的全部路径（延后 / 补支撑失败 / 放置后被打掉 …）与主循环 665、延后循环 908–983 的延后/跳过分支**都没有把料退回来**——扣到的那份实物在 `consumeBlock` 内部就被丢掉了 = **每次失败吞一件材料**。10-14 轨道炮场 `place-fail` 54 条（限频 10 秒/位，实际重试更多）即至少 54 件材料被吞；`cleanupDrops` 顺带把 2154 个掉落物收了。②的闸门会新增"延后"路径，**不先把退料补上就会把脚手架材料吃掉**，故③是②的前置。
- **注意（不算吞、保持现状）**：补支撑用的 `consumeExtraBlock` 那块料经 `setBlock` 落进世界（守恒）；只有"扣了却没进世界"的档要退。

## 改法

### ① 飞行跟随换装守恒（`MaidFlightFollowBehavior` + `MaidFlightKit`）

- `SWAPPED_CHEST` 的值语义从"待穿回的物品副本"改为**"认领钥匙"**（物品签名：物品 + 除耐久外 NBT + 数量；空栈 = 她本来没穿胸甲）。副本只用来"认出她背包里那一件"，**永不回流**。
- `wearElytra`：保留 `give(old)`（真品回背包，可见性与旧版一致）+ 存签名（一字未动的 `old.copy()`，语义换注释说明）。
- `restoreChest` 重写为**取回真品**：`MaidFlightKit.takeBackExact(maid, 签名, 数量)`（与 `takeFromBackpack` 同口径：先让额外容器把匹配物搬进来再整叠取；匹配用 `canItemStacksStack` + 耐久归零副本，与 `ProMaidExtension.sameIgnoringDurability` 同源）→ 取到就穿回胸甲槽；**取不到就不凭空补**（鞘翅收回背包，如实记）。
- 结算结果分档返回字符串，`m_6732_` / `settleOnGround` 按实际结果记日志：
  `把胸甲还回去`（真还了，原文案）/ `收翅回背包（本趟没穿胸甲）` / `原胸甲已不在她身上，不补件` / `胸甲槽已被换过，不覆盖` / `本趟没有换装`。
- **被否方案**：（a）托管真品在内存表、收手再穿——跨存档/中途退出就把真品弄丢（红线3 反着犯）；（b）托管真品进她的持久 NBT——飞行期间胸甲从背包"消失"，观感就是"被吞"，且新增一套跨版本 NBT 迁移面。都不如"真品始终在她背包里、收手取回"。

### ② 脚手架支撑闸门（`BlueprintPlacement` + `MaidBuildBehavior`）

- `supportDirection`：附着名单补 `ScaffoldingBlock`（它没有 facing/half/face 属性，自然落到 `return DOWN`）——**声明"它需要支撑、支撑方向按下方格算"**，让延后重试路径的 `waitPlanSupport` 分析（928–938）对脚手架也成立（旧版 sup=null → waitPlanSupport=false → 3 次后直接"悬空放不上"永久跳过 = 缺格）。
- `doPlace`：`placeState` 定稿后加**脚手架专用前置闸门**——直接调 `placeState.m_60796_(level, target)`（`canSurvive`，对任意坐标可先行调用，判的就是世界当前邻居，与 `getDistance` 同源）：
  - 能活（下方可支撑面 / 脚手架链 / 水平侧邻脚手架都在）→ 照常放，并把 `sup` 置 null（跳过通用"下方格"分支，避免给侧挂脚手架补一块多余石头）；
  - 不能活：下方格在蓝图计划内且非 force → `return false`（延后等下方块）；否则扣料补一块下方支撑（石头，见下）再放，仍不能活 → `logPlaceFail("补脚手架支撑后仍放不住")` 延后。
- `supportBlockFor`：脚手架补支撑归入"→ 石头"档（fallback 可能是玻璃/半砖这类非支撑面建材，补上去 `getDistance` 仍 7）。
- **不做**：不改 `buildPriority`（脚手架仍按结构 0 与 y 升序——"先放"不再有害：闸门保证放上去就能活；改优先级会打乱既有拟人化排序的验收基线）。

### ③ 放置未成退料（`BlueprintMaterials` + `MaidBuildBehavior` 两个循环）

- `BlueprintMaterials`：把 `consumeBlock` 的主体抽成 `consumeOne(maid, blockId) → Consumed{Item item; ItemStack taken;}`——`taken` = 真扣到的那一件**实物**（含 NBT；创造模式/水桶工具档 = 空栈，不扣实物）；`consumeBlock` 改为 `consumeOne(...).item` 委托（既有调用点一字不动）。
- `MaidBuildBehavior` 主循环（594/662）与延后循环（826/908）：`doPlace` 返回 false 时 `refund(maid, taken, "建造放置未成退回")`（`MaidGiveBack.give` → 塞回背包，塞不下掉脚下；taken 为空栈则跳过）后再延后/重排/跳过。
- `doPlace` 内 1373–1377 的"打落后清掉落物"保持（那份掉落物与退回的料是**同一件**的两个现场，只能留一个：留背包里那份 = 不重复）。
- **被否方案**：在 `doPlace` 各失败点内部退料（同一失败点被主循环/延后循环/补支撑多路复用，退回的料归属会乱）；只退"方块型"料、忽略 NBT（退回改名/带附魔的建材会丢 NBT）。

## 触及文件

- 两树各 4 个：`combat/MaidFlightFollowBehavior.java`、`combat/MaidFlightKit.java`、`build/BlueprintPlacement.java`、`build/MaidBuildBehavior.java`、`build/BlueprintMaterials.java`（共 5 个文件 × 2 树）。
- 新配置项 0；新 i18n key 0；新 mixin 0；新类 0。

## 验证

- 命令 + 结果：
  - 两树 `javac` **EXITCODE=0**（`gen_compile.py`+`compile_promaid.bat` / `gen_compile_neo.py`+`compile_neo.bat`；含注释块压行后的终编译）。
  - 14 护栏**全 FAIL 0 / WARN 0**（`check_comment_style` 首跑抓到本轮新增的 10 处「成员注释块 > 8 行」，两树压到 ≤8 行后 PASS；`check_workflow` 在升号 + 补登记后 WARN 0；`check_tree_mirror` 只读报告不设门槛）。
  - 双 jar：`patched/promaid-1.3.3-forge-1.20.1.jar` **17915266** 字节，sha256 `92bb0d5c…8d1cf4`；`patched/promaid-1.3.3-neoforge-1.21.1.jar` **17962914** 字节，sha256 `31c93987…3f51be`。
  - 部署 4 实例 **match=True**（1.21.1-NeoForge_21.1.250 / 1.20.1-Forge_47.4.10 / 龙之冒险 v2.4a / server pack1201；旧 1.3.2 包自动清）。
  - 专服回归三件 **PASS**（`test_server.py 1201`、`test_server.py neoforge1211`、`test_maid_load.py neoforge1211`；`Done (…)` 均 ~3s、30s 守护窗口无崩、无 mixin 应用失败）。
- **反证**（修 bug 必填）：
  - ①：旧代码路径 `give(真品)+穿(副本)` 在数学上每循环必 +1（代码即反证）；本轮实测空转档（日志"还了"存档"没还"）证明旧版结算与日志不符。修复后：取回真品穿、取不到不补——净变化恒为 0。
  - ②：旧版 `supportDirection(脚手架)=null`、闸门不存在（代码即反证）；修复后脚手架先行 `canSurvive` 拦截，悬空不再落世界。
  - ③：旧版失败路径无退料调用（代码即反证）；修复后失败路径逐条退料。
- 未做：实机跑一遍（装机后由开发者验，见「验收」）——专服回归三件已按惯例跑完 PASS。

## 验收（谁说了算：开发者 —— `WORKFLOW.md` §4.1）

- 状态：`未验收`（等开发者按「排班」里的三条场景实机复看）。
- 未验收之前：不写 changelog、不写 release 说明、不发平台。

## 边界（没动什么）

- `MaidFlightKit.equip`（空袭/飞行任务的换装）：换下即还包、无还原链，本来就守恒——一字未动。
- `[装备托管]` 网（R866）：只加了一张"结算分档"的嘴，判定逻辑未动。
- 建造系统的排序（`buildPriority`/`centerSteps`）、`snapFinish` 完工重放、rail 类方块既有的"放上→被打落→延后重试"机制：未动（rail 的 54 条 fail 属于同一形状，本轮靠③退料止损，不改变其重试语义）。
- 补支撑"扣了料但 setBlock 没落进世界"（区块卸载竞态）的罕见档：未单独退料（记为观察点）。
- 0.14/0.15 存档里 08:27:08 那次"玩家把鞘翅从胸甲槽挪进背包"的操作方：托管网已按设计接住（落定 她身上），本轮不追责不改动。

## 台账

- 清掉：无既有债号；本轮三条属**新发现**，已登记 `DEBT.md`——BD-26（飞行跟随换装复制 / 空转，装备组）、BD-27（脚手架悬空被原版打落 / 死循环，新建「建造」组）、BD-28（放置未成吞料，建造组），状态均 `已清@实测一千`；§0 模块索引同步加行。
- 发布登记：`rounds/RELEASES.tsv` 追加 **1.3.3** 两条 `workspace`（forge `92bb0d5c…8d1cf4` / neoforge `31c93987…3f51be`，2026-10-16，备注 = 实测一千三项 + 全验证口径）；1.3.2 两条 `published` 保持冻结未动。
- 升号：1.3.2 已发布（GitHub Release v1.3.2 / 2026-10-16）→ 按 §5.5 升 **1.3.3**（三份 mods.toml、双构建脚本 `JAR_OUT`、`deploy_all.py` 头 + `JOBS`、`sync_desktop_snapshot.py` JARS + 说明句、两测试脚本各 2 条、`README.md` 顶部两行、`PUBLISH_GUIDE.md` 版本头 + 第 6 节那句；桌面快照随出包自动刷新）。
- 新增债：无。
