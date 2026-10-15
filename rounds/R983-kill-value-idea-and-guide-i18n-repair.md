# R983 · 「数值 / 优先级 / 默认 300」想法清场 · 手册英文表错位修复 · ④ 措辞复核

## 排班

1. 开发者两条：①问「默认300？现在这个数值还有用吗？」并要求**把这个想法的文本也删掉**；
   ②指出上一轮④（补给不再补坏工具）的说明**措辞含糊**，要求复查。
2. 先回答①的「还有没有用」：把那个整数在全模组的读取点摊开（结论见下），确认只剩「给够不到的矿排个最值钱的」
   一处，其余全是成员判定 → 判定为**表述残留**，按①删文本。
3. 清场范围：配置项 + 面板文案 + 手册正文 + 英文词典；做词典时发现手册矿/木章节**键值整段错位**并一并修好。
4. 两树镜像 → 编译 → 14 护栏 → 打包 → 部署 → 回归 → 文档台账。

## 需求 / 来源

- ①「默认300？现在这个数值还有用吗？把这个想法的文本也删掉。」
- ②「不再补要坏的道具这一点你措辞很含糊。你再给我检查一下。」

## 改法

### ①-a 那个整数还有没有用（先查再删）

- `oreValueOf` / `woodValueOf`（`MaidMineBehavior:54/70`、`MaidWoodBehavior:53/58/73`）只是
  `WOOD_VALUE.get(block) != null` 的**成员判定**——「在表里 = 是矿/木材」，整数本身不参与。
- 目标选择与评分的唯一判据是 `distSqToBlock`（她到方块的**距离平方**，最近优先）。
- 全模组**唯一读整数**的地方：`MaidMineBehavior.recordSkippedOre`（本次改动前 M:1834-1845）在
  「这块够不到，记一条」时用 `>` 挑出**数值最大的那一块**写进汇报。
- 结论：对玩家可见行为没有任何影响 → 面板不该再要求玩家「给它赋一个值」，手册也不该再讲数值。

### ①-b 文本清场（两树同步）

| 位置 | 旧 | 新 |
|---|---|---|
| `MaidSmartConfig` | `MINE_CREATIVE_DEFAULT_VALUE` / `WOOD_CREATIVE_DEFAULT_VALUE`（两个 toml 项 + comment + translation） | **删除**；面板改用本地面板常量 `TABLE_ENTRY_PLACEHOLDER_VALUE = 300`（`PromaidConfigScreen` 内，私有） |
| 矿表 / 木材表子页提示 | 输入优先级数值（留空=默认价值） | **（直接点「添加」即可）** |
| 悬停第三行 | §a优先级：300 / §a已在名单（数值 300） | **§a已在名单** |
| 锁定红字 | 已锁定（X），请为其赋予一个值（直接点添加/回车 = 300） | **已锁定（X），点「添加」或回车把它加进名单** |
| 面板 4 份 `lang/*.json` | `config.promaid.mine.creativeDefaultValue` / `…wood.creativeDefaultValue` | **删除**（forge zh 682→680、en 684→682；neo zh 688→686、en 690→688） |
| 面板残留注释 | 「点击添加的默认价值」/「显示在优先级输入框下方」/「= 用默认价值（留空快捷赋值）」 | 改成「行格式占位」/「方块网格下方」/「点添加或回车就把它加进名单」 |
| 手册 · 挖矿教程 | 「输优先级数值点添加…（输入框留空直接点添加/回车 = 用默认价值）」；「价值分越大越优先…价值也可在列表输入框改」；举例「输入 500 点添加」；「走最近性价比最高的矿」；「她会优先找钻石挖」 | **「点方块图标锁定该矿（黄框固定），再点添加或直接回车就加进名单」**；**「挖哪块一律按最近」**；举例「点添加/回车」；**「走离自己最近的矿」**；**「她会去挖钻石」** |
| 手册 · 伐木教程 | 「输数值点添加…（留空 = 用默认价值 300）」；「价值分越大越优先」；举例「直接回车（默认价值）」；「· 自动识别模组原木（标签）（默认开）…（价值 300）…」 | **「再点添加或直接回车就加进名单」**；**「砍哪棵一律按最近」**；举例「点添加/回车」；简介补「模组原木要自己在表里加——旧的标签自动识别已删」，**那条开关 bullet 整条删除** |

### ①-c 英文词典（`assets/promaid/i18n/en_us.json`，两树）

- 发现两类问题（都是**英文客户端可见**的错）：
  1. **6 条当前正文没译文**：本次改写的 6 句话（矿表 step1 / 两张表 / 举例、木材表 简介 / step1 / 两张表）；
  2. **整段键值错位**：木材章节 9 条（step1 → 两张表 → 第二步 → 切伐木任务 → 常用开关 → 连锁砍伐 → 自动收集 → 透视感知）
     的**英文各自挂到了下一条中文上**——中文界面看不出来，英文界面整段错位；旧判据（长键 + 短值）查不到它。
- 处理：6 条补新译文（按要求去掉「价值/优先级/默认 300」口径，并把「最值钱」改成「最近」）；
  9 条按新中文重挂（`§eStep 2: Send her to chop§r`、`§eCommon toggles§r (Logging section):`、
  `· §eChain logging§r…`、`· §eAuto-collect§r…` 等回到各自那条）；删 **11 条陈旧条目**
  （矿/木的旧 `透视感知` 两条、旧 `选矿逻辑（价值分…）`、旧 `切伐木任务（性价比…）`、
  `自动识别模组原木` 4 条 ×2 树——这些中文已无任何代码产出）。
- 结果：`check_guide_i18n` 由 `missing 6` → **0**（shifted / round 仍 0）。

### ② ④ 的措辞复核（落到行号）

**R982 干的不是「不补快坏的工具」——那条判据早就有了**（`isNearlyBroken`，v1.5.168）。本轮**新增的是两件事**：

1. **记账**：`MaidToolAutoEquip.recordDurabilityBreak(maid, stack)`（forge M:621-640 / neo M:621-640）——
   `damage + 1 >= max`（**这一下就会把它用爆**）时，按**物品注册名**记一条
   `她(id) → 名字 → 当前 gameTime`；读取时顺手清掉超过 `BROKEN_SUPPLY_BLOCK_TICKS = 600`（30 秒）的旧账。
   调用点**只有 3 处，都在扣耐久之前**：`MaidMineBehavior:867`、`MaidMineBehavior:960`、`MaidWoodBehavior:823`。
2. **拦补给 / 换装**：`brokeByDurability(maid, stack)`（M:643-658，`now - t <= 600`）进两条路——
   - **换装候选循环**（`equip` M:206、`ensureForTarget` M:254、`ensureAxeForTarget` M:462、
     `ensureShieldForCombat` M:833）**跳过**刚爆过的那种；`ensureAnyAxe`（M:422-423）/
     `ensureHoeForFarm`（M:894-895）在行内 `|| brokeByDurability(...)` 直接排除；
   - **6 处 `MaidExtraContainer.pull(...)` 过滤器**（M:196-197、244-245、415-416、452-453、822-824、887-888）
     一律加上 `&& !isNearlyBroken(s) && !brokeByDurability(maid, s)`——**额外容器（精妙背包等）此前完全不看耐久**，
     这是本轮新封的口子。

**仍然会发生的事（写清楚，不是"全都不补了"）**：

- 手上那把快坏的工具**不会被拿走**：`ensureForTarget` 只在她手上那把「挖不动」或「快坏」时才尝试换
  （M:240 的放行条件 = 是镐 ∧ 能挖 ∧ 不快坏）；候选全被跳过（`bestSlot < 0`）时**不换**——
  手上那把能挖就继续挖（M:267 返回 true），挖不动才返回 false（调用方才去播报「需要更好的镐」）。
  所以「包里一堆快坏的」时，她会把手上的**一件件用爆**（这是刻意的：不能在现场撂挑子）。
- 她背包里**已经**躺着的快坏工具不会被清出去；BD 自动补货 `MaidBdRestock` 也**不看耐久**（整类只有
  `onMaidTick`/`tick`，没有 `isNearlyBroken` 判据）——所以「包里一堆快坏的」这局面照样能出现，
  只是**换装与 pull 漏斗不再把它挑到她手上**，「爆一件 → 补一件快坏的」这个循环断了。
- 记账按**物品注册名**且**只对她本人**：窗口内她拿不到同款（玩家这时新塞一把同款给她也一样等窗口过），
  30 秒后自动解禁——不写死任何黑名单。
- 记账只覆盖**挖矿 / 伐木这 3 处手工具扣耐久点**。战斗挥砍、远程射箭/弩、三叉戟、扇子、锄地
  （`MaidFlightRangedTask`、`MaidFlightCombatBehavior`、`MaidRiptideBoost`、`MaidTridentSpinBehavior`、
  `TwilightFanKit`、`FarmTillDriver:356` 等）**不在账上**——那些工具用爆后不会触发 30 秒窗口。
- 判定阈值：`isNearlyBroken` = **剩余耐久 ≤ 总耐久 10%**（`BROKEN_SOON_RATIO = 0.1`，M:611/666-672；
  无耐久上限的物品一律不算）。

## 触及文件

- 两树：`config/MaidSmartConfig.java`（删两个 toml 项）、`config/PromaidConfigScreen.java`
  （面板文案 / 锁行 / 删除两个数值行 / `creativeDefaultValue()` / 占位常量落地 / 注释）、
  `guide/GuideChaptersWork.java`（矿/木两章正文）、`assets/promaid/i18n/en_us.json`（补 6 / 重挂 9 / 删 11）、
  `assets/maid_smart/lang/{zh_cn,en_us}.json`（各删 2 键）、`assets/promaid/panel_coverage.txt`（重生成）。
- 工具：`tools/build/gen_panel_coverage.py`（重跑）、`tools/verify/gen_switch_inventory.py --write`（重跑）。
- 文档：`rounds/R983-*.md`、`rounds/RELEASES.tsv`、`DEV_GUIDE.md`、`机制详解.md`、
  `release_body.md` / `release_body_neo.md` / `RELEASE_NOTES_v1.3.2.md` / `store/release_notes_1.3.2.md`、
  `ARCHITECTURE-SWITCHES.md`。

## 验证

- 两树 `javac EXITCODE=0`（改完先编译）。
- 14 护栏全 `FAIL 0`：`check_guide_i18n` 6 缺译 → 0；`check_panel_coverage` 重生成后 0；
  `check_workflow` 重生成 `ARCHITECTURE-SWITCHES.md`（1359 行）后 0。
- 包体：forge **17,861,900** 字节 `f0e0e85a…bdc998`；neo **17,909,614** 字节 `fcb0ef8e…97cdb6`。
- 部署：`deploy_all.py` 4 个目标全 `match=True`（`elevated rc: 0`）。
- 回归：`test_server.py` / `test_maid_load.py` 两加载器各两件，四件全 `verdict: PASS`。
- 词典：`en_us.json` 两树 `json.load` 通过（forge 7032 键 / neo 7042 键）。

## 验收

- 面板上再也找不到「优先级 / 默认价值 / 请为其赋予一个值」——矿表与木材表子页只剩「点图标锁定 → 点添加/回车」。
- 手册挖矿 / 伐木教程讲的是「点添加/回车就进名单」「挖哪块/砍哪棵一律按最近」，并写明模组原木要自己加。
- 英文客户端这两章译文逐条对得上（不再串行）。

## 边界

- 那个整数**没有被从存储格式里拿掉**：`id=数值` 的行写法保留（旧存档 / 手改 toml 仍可读），
  面板按占位值写入；`recordSkippedOre` 里「最值钱的那块」这条汇报口径也保留——它只影响那句汇报文案，
  与选择行为无关。
- ①-c 的词典修复只动**矿/木两章的手册条目**与本次改写涉及的键；其它章节的旧条目未逐一复核
  （`check_guide_i18n` 的 shifted 判据只抓「长键 + 短值」，长度相当的错位它抓不到——这是本次发现的口径盲区，
  已记在台账）。
- ④ 的窗口是**内存态**（`ConcurrentHashMap`），不落盘：服务器重启 / 她重登后窗口清空，重新计时。

## 台账

- `rounds/RELEASES.tsv` 两条 1.3.2 行 **第一百一十九次** 同号重打；旧字节
  `a6f58ec6…6d3460`（forge）/ `efc2eeec…fd6dfb`（neo）（第一百一十八次 R982）作废。
- `DEV_GUIDE.md` 发布区间 → 八百五十九 ~ 九百八十三。
- 四份发布文案：把上一轮那条「名单页红字改口径」的 bullet 改写成本轮口径
  （数值 = 行写法、面板不再要求赋值、先挖/先砍按最近）+ 新增一条「手册与英文译文对齐」。
- `机制详解.md`：挖矿 / 伐木两条 bullet 各补一句「表里 `id=数值` 的数值只是行写法占位」。
- 新增台账项（未修，只记）：`check_guide_i18n` 的「错位」判据抓不到**长度相当**的键值错位
  （本次木材章节就是这种；靠人工比对中文/英文发现的）。
