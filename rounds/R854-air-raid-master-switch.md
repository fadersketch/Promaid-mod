# 实测八百五十四 · 空袭总开关改名挪页（两树）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修 bug —— 玩家反馈「开启 DIY 之后空袭彻底无法启动，配置里按"空袭"找不到相关开关」，
  属**已交付功能的可用性缺陷**（DIY 是我们上一版交出去的模式），不是加新功能。
- **开工前读过**：`config/MaidConfigPreset.java`（DIY 到底关了哪些项：`combat.flightMode` 不在
  `KEEP` 里）· `config/PromaidConfigScreen.java` 的 `autoCombatRows()`（原位置，第 4483 行
  `BoolRow("飞行作战", …)`）与 `airRaidRows()`（「空袭数值」页，首行是 `SectionRow`）·
  扫帚页对照（`SectionRow("—— 扫帚模式（TLM 任务：maid_smart:broom）——")` 紧跟
  `BoolRow("扫帚模式·总开关", …)`）· `combat/MaidFlightCombatBehavior.java` 的 `canUse`
  （**`COMBAT_FLIGHT_MODE` 的唯一消费者**：`if (!…COMBAT_FLIGHT_MODE.get()) return false;`）·
  `combat/MaidFlightCombatTask.java` / `MaidFlightRangedTask.java`（两个空袭任务都 new 同一个行为）·
  两树手册「空袭与飞行作战」章「六、几个实用开关」与「怎么找某个开关」· `i18n/PromaidI18n.java`
  （整串精确匹配 + 去掉 `§x` 码再 trim 匹配）· `build/BlueprintBookScreen.java` 的 DIY 二次确认文案 ·
  `tools/verify/check_comment_style.py`（LONG-BLOCK：类头 > 15 行、成员 > 8 行；基线 2258，不许增加）·
  `rounds/RELEASES.tsv`（现字节 = 第十六次同号重打）。
  结论：不是已记的债；`ARCHITECTURE-SWITCHES.md` 里没有「空袭总开关」这类等价命名，只有
  `combat.flightMode`（注释首句是「飞行作战模式」）。
- **工作队列**（逐步，做完就勾；每步写"完成判据"）：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 侦察 | 定位「空袭总开关」= `combat.flightMode`，列出它被 DIY 关掉后的失效链与"按空袭找不到"的根因 | ☑ |
| 1 | 实现 | 两树面板：总开关从「主动参战」页挪到「空袭数值」页第一条并改名「空袭·总开关（飞行作战）」+ 加扫帚同款分区标题 | ☑ |
| 2 | 文案 | 配置注释 + 手册两章 + 内建配置页标签 + DIY 确认弹窗 + 两树 i18n（键与值）+ 开关清单重生成 | ☑ |
| 3 | 编译 | 两树 `javac EXITCODE=0` | ☑ |
| 4 | 护栏 | `check_comment_style` 不高于 2258 / `check_preset` FAIL 0 / `check_workflow` FAIL 0 / `check_changelog` 不变 | ☑ |
| 5 | 打包 | 同号第 17 次：`MISSING: none` + jar 自检 + `RELEASES.tsv` 留痕 | ☑ |
| 6 | 交付 | 交开发者进游戏实测（**不部署、不回归**，等开发者说「部署」） | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者；验法 = 手册首页点「DIY / 默认模式」切到 DIY，
  再去「模组详细配置 → 战斗与自保 → 空袭数值」，第一条应当就是「空袭·总开关（飞行作战）」；
  打开它 → 给女仆配齐三件套、右键切「近战空袭」→ 她应当照常起飞。

## 需求 / 来源

开发者当场反馈（原话）：

> 我发现开启DIY之后会直接导致空袭彻底无法启动。配置项目里面有没有相关开关，这样子对于DIY的玩家很不友好。扫帚模式好歹还有个开机开关呢。

## 评估

- **级别**：P2（可用性缺陷：功能能用，但 DIY 之后玩家按名字找不到把它开回来的那条开关）。
- **开关是存在的**：近战/远程两个空袭任务共用同一个 `MaidFlightCombatBehavior`，它的
  `canUse` 第一句就是 `if (!COMBAT_FLIGHT_MODE.get()) return false;` —— 也就是说
  `combat.flightMode` **确实就是**两个空袭任务的总闸（唯一消费者）。DIY 模式会把它一起关掉
  （它不在 `MaidConfigPreset.KEEP` 里），关掉后女仆能被切到空袭任务、但飞行行为不启动，
  观感就是"空袭彻底起不来"。
- **真正的毛病是"找不到"**：那一行在面板里的名字叫**「飞行作战」**（Neo 树「飞行作战（1.21.1）」），
  位置在「战斗与自保 → 主动参战」页、和"主动切换战斗模式 / 响应半径 / 援护半径"混在一起。
  面板里带"空袭"二字的行一共 9 条（防叠罗汉 / 半径偏置 / 免疫摔落 / 近身弹开 / 牵引绳 / 顺带施法 /
  施法间隔 / 施法距离 …），**没有一条是总开关**。对照扫帚：扫帚那一页的分区标题点名 TLM 任务，
  紧接着第一条就是「扫帚模式·总开关」——所以扫帚"好歹有个开机开关"。
- **DIY 是刻意关它的**（DIY = 不介入女仆行为），本轮的取向不是让 DIY 不关、而是把总开关**找得回来**：
  与扫帚同款（分区标题 + 第一条就是总开关），并把手册/注释/内建配置页/DIY 弹窗全部指向它。

## 改法

1. **两树 `PromaidConfigScreen`**：
   - 从 `autoCombatRows()` 删掉 `BoolRow("飞行作战"…)/( 「飞行作战（1.21.1）」… )`（原位留一行说明注释：它挪去哪了）；
   - 在 `airRaidRows()` **最前面**加一条与扫帚同款的 `SectionRow("—— 空袭（TLM 任务：
     maid_smart:flight_combat / maid_smart:flight_ranged）——")`，紧随其后是
     `BoolRow("空袭·总开关（飞行作战）", COMBAT_FLIGHT_MODE …)`，描述改成先讲"这是两个空袭任务的总闸 /
     DIY 会关掉它 / 想只开回空袭就打开这一条"，再接原有的三件套与飞行动作链路。
2. **两树配置注释**（`COMBAT_FLIGHT_MODE`）：首句改成「空袭·总开关（飞行作战）——…」，
   末尾补「关掉 = 近战空袭与远程空袭两个任务完全不工作…；DIY 模式会关掉它，想只把空袭开回来就打开这一条」。
   （Switch Inventory 的「作用」列取自注释首句，所以清单里也会显示成"空袭·总开关"。）
3. **两树手册**：飞行作战章「六、几个实用开关」第一条改成指向「战斗与自保 → 空袭数值」页第一条；
   同章末尾那段"总开关才在…"同步；系统章「怎么找某个开关」的空袭条目改成
   「总开关与各项数值都在「战斗与自保 → 空袭数值」（页首第一条就是「空袭·总开关（飞行作战）」）」。
4. **`GuideContent.CFG_LINK_RULES`**：空袭章的「配置入口」链接从 `COMBAT:AIR_RAID` 改成
   `COMBAT:AIR_RAID:空袭·总开关（飞行作战）`（跳过去会高亮那一行），`飞行作战` 那条也从
   `COMBAT:AUTO_COMBAT` 改指 `COMBAT:AIR_RAID`。
5. **DIY 二次确认弹窗**加一句：大模式都有自己的「总开关」，就在它自己板块的第一条——
   扫帚＝「移动与行为 → 扫帚模式」，空袭＝「战斗与自保 → 空袭数值」。
6. **内建配置页标签**（`assets/maid_smart/lang/{zh_cn,en_us}.json` 的 `config.promaid.combat.flightMode`）
   一并对齐成「空袭·总开关（飞行作战）」/「Air Raid · Master Switch (Flight Combat)」。
7. 不新增配置项、不改默认值、不改 TOML 键名（`combat.flightMode` 原样）——只改显示与文档。

## 触及文件

- 两树 `com/maidsmart/config/PromaidConfigScreen.java`（总开关挪页 + 改名 + 分区标题）
- 两树 `com/maidsmart/config/MaidSmartConfig.java`（`COMBAT_FLIGHT_MODE` 的 comment）
- 两树 `com/maidsmart/guide/GuideChaptersFlight.java`、`com/maidsmart/guide/GuideChaptersSystem.java`
- 两树 `com/maidsmart/guide/GuideContent.java`（`CFG_LINK_RULES` 两条）
- 两树 `com/maidsmart/build/BlueprintBookScreen.java`（DIY 确认文案）
- 两树 `assets/promaid/i18n/en_us.json`（1 个新标签、1 个新分区标题、2 条新面板描述、2 条新配置注释、
  1 条新手册段落、2 条改动手册段落、1 条改动的 DIY 段落 + 英文 value）
- 两树 `assets/maid_smart/lang/{zh_cn,en_us}.json`（`config.promaid.combat.flightMode`）
- `ARCHITECTURE-SWITCHES.md`（`gen_switch_inventory.py --write` 重新生成）、
  `rounds/RELEASES.tsv`（第十七次字节 + 作废旧字节）、本轮记录
- **未动**：`MaidConfigPreset`（DIY 的语义一个字没改）、`MaidFlightCombatBehavior` 等任何行为代码、
  任何开关默认值、`CHANGELOG.md` / `store/version_changelog.md`。

## 验证

- 两树 `javac` `EXITCODE=0`（Forge 463 源 / Neo 466 源；只有既有的 copyright/deprecation 警告）。
- 四护栏与基线一致：`check_comment_style` 929 文件 / **2258 处**（改前改后同数）·
  `check_preset` FAIL 0 / WARN 2 · `check_workflow` FAIL 0 / WARN 2（登记第十七次字节后）·
  `check_changelog` 1261 处（本轮未动 changelog，数字不变）。
- i18n 健壮性（脚本断言）：所有**新键都是从改过的 Java 里现抽**（不是手抄），断言它们逐一出现在表里
  且**各只出现一次**；旧键（飞行作战总开关那句、总开关才在…那两段、系统章那段、旧面板描述两条、
  旧配置注释两条、DIY 旧段落）逐一从表里消失；两树 `en_us.json` 与 4 个 lang json 写回后 `json` 解析全部通过。
- 两树一致性：同一段文案在两树解码后逐字节相同（手册「六、几个实用开关」第一条与系统章那段）；
  Neo 独有的措辞（「后四项」、`1.21.1`）保留，Forge 对应句仍写「以上四项」。
- 打包（同号**第十七次**）：`MISSING: none`、`lang json: OK`、`verify_jar_classes` OK
  （Forge 760 / Neo 764 class 全在、mixin 登记 96/95 无遗漏、注入签名无歧义）；
  forge `879de27a…830f98`（17,629,956 B）、neo `3d01c069…5b909d`（17,668,676 B）；桌面快照已刷新。
- jar 内自查：包内 `assets/promaid/i18n/en_us.json` 有 7 条新键、4 条旧键前缀已不在；
  `PromaidConfigScreen.class` 里有「空袭·总开关（飞行作战）」与新区分标题、`MaidSmartConfig.class` 里有新注释、
  三个手册/书 class 里都有新文案片段、`lang/zh_cn.json` 已改；**包内无 `.new/.orig/.tmp/.bak` 残留**。
- **打包前先清了一个坑**：第一次建包把 `assets/promaid/i18n/en_us.json.new`（我调试写入时
  `os.replace` 失败留下的临时文件）打进了 forge 包（18,419,241 B）。已删除源目录与 `staging_promaid`
  里的两份副本并**重新建包**，包体回到 17,629,956 B（该临时文件已确认不在包内）。
- **反证**：本轮没有行为代码改动，因此"能不能起飞"的判定不变——`MaidFlightCombatBehavior.canUse`
  仍然只认 `COMBAT_FLIGHT_MODE.get()`；本轮改的全是**显示与文档**。真正的观感验证要等开发者进游戏
  （DIY 模式下打开「空袭·总开关」→ 配齐三件套 → 看她是否照常起飞）。
- **未做的验证**：未部署、未跑专服回归、未进游戏实测（等开发者开闸「部署」）。
- 形态说明：`en_us.json` 是**被别的进程（ZCode 索引器）映射着**的文件——实测这一轮它是"能原地写、
  但不能 truncate 收缩、也不能改名"（`PermissionError` / WinError「用户映射区域」）。
  所以移走旧键、写入新键之后，我按**等长写回**（内容比原文件短就在末尾补空格；该文件尾部本来就有一大段
  尾随空格，JSON 允许），写回后再 `json` 解析校验。两树 `en_us.json` 因此各多了几百字节尾随空格。

## 验收（谁说了算：开发者 —— `WORKFLOW.md` §4.1）

- 状态：`未验收`（需开发者进游戏实测 DIY → 空袭）。

## 边界（没动什么）

- 不改 DIY 的语义（`MaidConfigPreset` 一个字没改：DIY 仍然会关掉空袭总开关，本轮只让它找得回来）。
- 不改 `combat.flightMode` 的 TOML 键、默认值、类型；不改任何行为代码；不加新配置项。
- 不动 `CHANGELOG.md` / `store/version_changelog.md`（§6.1 验收后才写）；不部署、不回归（等开发者开闸）。

## 台账

- 无新增债。这一轮清掉的是一笔**可用性债**（"DIY 后找不到把功能开回来的开关"），不单独建条目。
