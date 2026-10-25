# R994 两件：1.20.1「壮丽鞘翅不认」实证答复（不是识别 bug）/ 搭方块选材禁「两格方块」硬闸

> 上一轮：`rounds/R993-elytra-swap-scaffolding-refund.md`（三件修复，1.3.3 首包，**未发布**）。
> 开发者原话（三条）：
> 1. 「1.20.1，刚刚我在测试的时候发现如果女仆在饰品栏安装了壮丽鞘翅，但是女仆仍然不承认空战的装备已经齐了。当然，在背包内安装这个鞘翅，他也是不承认的。这是怎么回事？1.21.1呢？」
> 2. 「女仆搭方块的时候默认把床这类2格方块放入黑名单，否则会出bug」
> 3. 「1.3.3还没发布，所以版本号不变」
> 第 1 条是**问询**（要结论与证据链，不是要"改到认"）；第 2 条是修复；第 3 条定口径：**同号重打 1.3.3**。

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：① 问询/实证（javap 取证后给结论，**不改识别代码**——红线「口径同源」+「不猜外部数据」；
  见「改法①-被否方案」）；② A 修 bug（现状错、要改对；无新玩法/新开关）。
- **开工前读过**：
  - `rounds/RELEASES.tsv`：1.3.2 两条 `published`（冻结）；1.3.3 两条 `workspace`（R993 首包，未发布）
    → 本轮按开发者口径**同号重打**（**第一百三十次同号重打**，旧字节作废）。
  - `ARCHITECTURE-SWITCHES.md`：无"两格方块/搭块选材"相关开关；本轮**不新增开关**（修错，不是改语义）。
  - `DEBT.md`：BD-26/27/28（R993）已清；无本条的既有登记（见「台账」）。
  - 上一轮记录 R993：确认 1.3.3 未发布、本轮顺延 R994。
- **工作队列**（逐步，做完就勾；每步写"完成判据"）：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | ① 取证（javap 三个神秘遗物 jar） | 1.20.1 两件与 1.21.1 两件的 `canElytraFly` 各自字节码指认（是 player 闸还是恒真） | ☑ |
| 1 | ① 口径核对（我们认不认的判据链） | `isElytraLike`/`hasElytra`/`elytraDiagnostic`/`notifyNotReady` 四处口径一致、日志能自证 | ☑ |
| 2 | ② 定位（选材链 + 复现形状） | 四消费方全走 `isUsableBuildBlock`；床/门/高植物全过静态口径（实锤） | ☑ |
| 3 | ② 两树改 + 文案/译文同步 | 硬闸 + 类注 + 配置注释 + `en_us` 键值 + NaturalBlocks 注，两树镜像 | ☑ |
| 4 | 编译 + 14 护栏 | 两树 `javac` `EXITCODE=0`；护栏 `FAIL 0 / WARN 0` | ☑ |
| 5 | 同号重打 1.3.3 + 装 4 实例 | 双 jar；`match=True`；`RELEASES.tsv` 两条新行（void 旧字节） | ☑ |
| 6 | 交开发者验收 | ①按下方三条场景复看；②床/门实机试搭 | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者。
  1. **①-1.20.1（预期：维持"不认"）**：饰品栏或背包放壮丽鞘翅 → 开空袭。she 仍报缺鞘翅是**设计如此**
     （该物品对女仆 `canElytraFly=false`，穿上也滑不起来）。看日志一行自证：
     `[空袭装备] … 缺鞘翅（鞘翅判定: … canElytraFly=false ← 它对玩家返回 true、对女仆 false：这个物品的滑翔是写死给玩家的，女仆用不了 …）`。
  2. **①-1.21.1（预期：认）**：1.21.1 装 `enigmaticlegacyplus`，饰品栏/背包放壮丽鞘翅 → 开空袭；
     `BaseElytraItem.canElytraFly` 恒 true → 应认。日志关键词：`[空袭装备] … 胸甲槽换装：enigmaticlegacyplus:majestic_elytra`。
  3. **②**：背包里只留床/门/高植物 → 把她叫到够不着的高处（触发搭路）/开自保搭高：
     应报"没有能搭的方块"（`bridge no-block diag` 里 `minecraft:bed/…=REJECT`），**不再**放半张床然后被原版打掉。

## 需求 / 来源

- 开发者实机反馈三条（上文原文）；① 为 1.20.1 实例测试现场、② 为搭方块链路反馈、③ 为版本口径。

## 评估

- **① 结论：不是识别 bug——1.20.1 这两件「神秘遗物」鞘翅对女仆（非玩家）根本不能滑翔，而我们的识别口径
  与**原版滑翔闸门同源**，所以就应当"不认"；1.21.1 的 `enigmaticlegacyplus` 侧恒真，应认。**
  - **javap 实证**（`javap -p -c`，只挂 mod jar）：
    - 1.20.1 `EnigmaticLegacy-2.30.1.jar` → `EnigmaticElytra.canElytraFly` 第一条有效指令就是
      `instanceof Player`（`elytraFlightTick` 同）；中/英译名：`item.enigmaticlegacy.enigmatic_elytra = 壮丽鞘翅`。
    - 1.20.1 `enigmaticaddons-1.2.6.jar` → `ChaosElytra.canElytraFly` = `Player && isTheWorthyOne(ply) && ElytraItem.isFlyEnabled(this)`（混沌之傲，同病）。
    - 1.21.1 `enigmaticlegacyplus-1.21.1-1.1.2.jar` → `BaseElytraItem.canElytraFly` = `iconst_1; ireturn`
      （**无条件 true**）；`MajesticElytra`/`ChaosElytra` 均继承它。
  - **我们的判据链**（两树同构）：`MaidFlightKit.isElytraLike(stack, entity)` = 原版鞘翅（`isUsableElytra`）
    **或** `stack.canElytraFly(entity)`——刻意与 `LivingEntity.updateFallFlying`（原版滑翔唯一闸门）**同一个判据**：
    "我们认不认" ⇔ "她能不能真滑翔"。1.20.1 那两件对女仆恒 false → 不认是正确行为（认了反而会出现
    "把她抬上天、原版每 tick 清滑翔位"的半坏状态）。
  - **为什么饰品栏/背包都不认**：`hasElytra` 链（胸甲 → 双手/借物 → Curios 饰品栏 → 背包）四处都过
    `isElytraLike`，所以"不认"与放哪儿无关。`CuriosElytraCompat.describe` 在诊断行里单列饰品栏，日志自证。
  - **问"会不会是我们对饰品栏/背包漏扫"**：否——R992（1.3.2 已发布）已把"饰品栏只认一条件"的**真 bug**
    （烟花条件漏饰品栏）修掉；本轮现场是**另一个**问题（物品本身 player 闸），不是扫描面。
  - **为什么不做"特殊适配/强行放行"**：红线「不猜外部数据（javap 实证）」+「口径同源」；把 `isElytraLike`
    放宽成"名字里有 elytra 就认"会让空袭在不该起飞的装备上激活、并生产"起飞即坠落"的新现场。外部模组
    的 player 闸只能由外部模组解（或服主换 1.21.1 那套恒真实现）。
- **② 根因**：搭方块（自保搭高 / 挖矿 / 伐木 / 搭路 / 索引石）取材的唯一闸口是
  `MaidBuildBlockFilter.isUsableBuildBlock(Block, Level, BlockPos)`，四个消费方**全部**以 `(…, null, null)` 调用
  （BridgeUpBehavior：965/996/1020、MaidWorkBehavior、SelfPreservationBehavior、IndexStoneBuildBehavior）→
  走**静态口径**：只拦 下落方块 / TNT / 伤害表 / dangerBlocks / 玩家名单 / REPLACEABLE / **默认碰撞形状为空**。
  床（`BedBlock`）/ 门（`DoorBlock`）/ 高植物（`DoublePlantBlock` 系）的默认碰撞箱都**非空** → 全部通过 →
  被当垫脚石选中；单格放置只放下"下半/一半"，下一次邻居更新 `updateShape` 发现另一半不在了 → 这一半也被
  原版打掉 = "垫了个寂寞"+白扣一块（与 R993 脚手架那类"放上就消失"同形状，但这是**选材侧**的锅）。
  - **复现**：背包只留床（或门/高植物）→ 触发搭路/自保搭高 → 旧版选它、放半张、被打掉、再放、再掉……
  - 与玩家名单（`bridge.buildWhitelist`）的关系：名单是"允许垫脚"的例外表，但**放不住的东西放进允许表也放不住**——
    故本条与 沙/砾石 同档：**硬闸，名单不越权**。

## 改法

### ① 不改代码（问询结论 + 证据链，见「评估①」）

- 交付物 = 结论与证据链（本文档）+ 运行日志既有自证行（`elytraDiagnostic` 的 `← 它对玩家返回 true、对女仆 false…` 注释，
  15 秒节流一条，`[空袭装备]` 频道）。
- **被否方案**：（a）`isElytraLike` 放宽/按物品名特判——破坏"口径同源"（红线），生产不能飞的"假齐备"；
  （b）给这两件物品做显式兼容白名单并**替她施加推力**——那是"给外部模组的 player 闸开后门"，且 1.20.1 侧
  连 `elytraFlightTick` 也被 player 闸拦（滑翔位每 tick 被清），替推也飞不成稳定态（R992 的自推鞘翅只对
  "允许任何实体滑翔"的实现成立）；（c）改日志/提示新增中文气泡——`check_bubble_i18n` 要求每条气泡中文在
  `en_us` 表有译文，且现有诊断行已能自证，无必要。

### ② 两格方块硬闸（`MaidBuildBlockFilter`）

- `isUsableBuildBlock`：在 下落方块/TNT 判定**之后**插一段**类判定硬闸**：
  `BedBlock || DoorBlock || DoublePlantBlock → return false`（两树同文）。
- 位置理由：与 沙/砾石/TNT 同段（**先于**玩家名单链）＝"结构上放不住"这一档最靠前；名单（放宽）不给它开后门。
- 覆盖面（javap 实证继承链，见「验证」）：`BedBlock` ⊇ 全部床（1.20.1 十四色 + 1.21.1 十六色 + 模组床）；`DoorBlock` ⊇ 木门/铁门/模组门；`DoublePlantBlock` ⊇ 高草/大蕨/双高花（向日葵系走 `TallFlowerBlock`）/高海草/小垂滴叶——**含** 1.21.1 的矮枯草丛系。
- **不做**：不改 `takeBuildBlock` 的"数量最多优先"选取逻辑（口径只有一处，闸放在判定里）；不把 `TrapDoorBlock`
  等 1 格方块牵连进来（它们是 1 格、放得住）；不动蓝图链路（蓝图由 `BlueprintPlacement` 的两格方阵/床位
  专门逻辑处理，**合法**，不受本闸影响）。

### 文案 / 译文同步（同一句插入式，两树）

- `MaidSmartConfig.BRIDGE_BUILD_ONLY_NATURAL` 注释：`…、仙人掌/岩浆块这类伤害方块、床/门/高植物这类两格方块本来就一直不许搭…`。
- `assets/promaid/i18n/en_us.json`：该注释整串键同步 + 英文值补
  `…, and two-block blocks like beds/doors/tall plants have always been disallowed for bridging…`。
- `MaidBuildBlockFilter` 类头判定列表与 `NaturalBlocks` 设计注：同句补「非床/门/高植物（两格方块…）」。
- **不动**：`en_us.json` 里那条 `§e参数与名单§r…`（面板整段帮助的历史键，Java 无源文＝死键；内容不假，留着）。

## 触及文件

- 两树各 4 个：`tool/MaidBuildBlockFilter.java`、`config/MaidSmartConfig.java`、`tool/NaturalBlocks.java`、
  `assets/promaid/i18n/en_us.json`。
- 新配置项 0；新 i18n key 0（键值**替换**同一条）；新 mixin 0；新类 0。

## 验证

- 命令 + 结果：
  - 两树 `javac` **EXITCODE=0**（`gen_compile.py`+`compile_promaid.bat`：469 sources / `gen_compile_neo.py`+`compile_neo.bat`：471 sources）。
  - 14 护栏**全 FAIL 0 / WARN 0**（`check_comment_style` PASS；`RELEASES.tsv`/`DEBT.md` 登记后**二次全跑仍全绿**；`check_workflow`：mods.toml=1.3.3 ｜ jar 文件名=1.3.3 ｜ README=v1.3.3 ｜ PUBLISH_GUIDE=v1.3.3 对齐）。
  - 双 jar（**同号重打**）：`patched/promaid-1.3.3-forge-1.20.1.jar` **17915379** 字节，sha256 `8daa47ab…114970`；`patched/promaid-1.3.3-neoforge-1.21.1.jar` **17963032** 字节，sha256 `6c83b55b…f6bc3e`；桌面快照自动同步（forge=8daa47ab… / neo=6c83b55b…）。
  - 部署 4 实例 **match=True**（1.21.1-NeoForge_21.1.250 / 1.20.1-Forge_47.4.10 / 龙之冒险 v2.4a / server pack1201；旧"首包"字节自动备份进 `patched/backup_old/`）。
  - 专服回归三件 **PASS**（`test_server.py 1201`：`Done (3.104s)`；`test_server.py neoforge1211`：`Done (1.369s)`；`test_maid_load.py neoforge1211`：`verdict: PASS`；30s 守护窗口无崩）。
- **反证**（修 bug 必填）：
  - ②：旧代码判定里**没有任何两格类判定**（代码即反证）——床/门/高植物走静态口径时"默认碰撞形状非空"即放行；修复后第一个判定段（沙/砾石/TNT 之后）即拒。现场自证：触发"没料"播报时的 `bridge no-block diag` 里应出现 `"minecraft:bed=REJECT"`（旧版是 `=OK`）。
  - ①：字节码反证已列（`instanceof Player` / `Player && …` / `iconst_1`）——不是"我们漏扫饰品栏/背包"，而是"认了也不能飞"；若强行认，原版 `updateFallFlying` 每 tick 清滑翔位 → 只会在日志/气泡上造假象。
- 未做：实机跑一遍（装机后由开发者验，见「验收」）——专服回归三件已按惯例跑完 PASS。

## 验收（谁说了算：开发者 —— `WORKFLOW.md` §4.1）

- 状态：`未验收`（①按「排班」三条场景复看；②床/门实机试搭）。
- 未验收之前：不写 changelog、不写 release 说明、不发平台。

## 边界（没动什么）

- ①：识别口径（`isElytraLike`/`hasElytra`/渲染/换装）**一字未动**；不碰外部模组；不给任何物品开后门。
- ②：蓝图/建造链路的床位与两格逻辑未动；`TrapDoorBlock`/`CaveVines` 等 1 格可放置物未动；玩家名单机制未动。
- 版本号仍 **1.3.3**（未发布时段同号重打，旧字节在 `RELEASES.tsv` 记废）。

## 台账

- 清掉：无既有债号。② 属**新发现**，已登记 `DEBT.md` BD-29（搭方块选材漏拦两格方块，建造组），状态 `已清@实测一千〇一`；
  §0 模块索引「建造 / 蓝图」行已补 BD-29 与收项描述。
- 发布登记：`rounds/RELEASES.tsv` 追加 **1.3.3** 两条 `workspace`（forge `8daa47ab…114970` / neoforge `6c83b55b…f6bc3e`，
  2026-10-16，**第一百三十次同号重打**：本行现字节；前一次 `92bb0d5c…8d1cf4` / `31c93987…3f51be` 作废）；
  1.3.2 两条 `published` 保持冻结未动；1.3.3「首包」两条旧行保留在册（其字节已在重打行注明作废）。
- 升号：无（1.3.3 未发布 → 同号重打，§5.5 清单不动；README/PUBLISH_GUIDE 版本行维持 1.3.3 与护栏对齐）。
- 新增债：见上（BD-29 本轮即清）。
