# 实测八百五十五 游戏内文案瘦身：删净「实测N」轮次号 + 面板注释压到 100 字内（两树）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：**只改文案**（`WORKFLOW.md` §1 表第三行：改文案/文档 → 走共用链路，只落 dev 面）。
  判定依据：不动任何行为、开关、默认值、配置格式；只改**玩家可见字符串**与 `i18n` 表。
- **开工前读过**：`WORKFLOW.md` §0~§4（含 §6 文案纪律：可见文案禁 `实测NNN`，存量只减不增）·
  `ARCHITECTURE-SWITCHES.md`（1355 行，末尾存量表 `实测` 命中 60 行、全部来自配置注释）·
  `TECH_DEBT.md`（**TD-03 本次要清的面**：①配置注释 ②面板/工具提示；TD-07 i18n 漂移=顺带；
  TD-09 配置注释的"删字洞"=本轮不动）· `BUG_DEBT.md`（无相关项）· `rounds/RELEASES.tsv`
  （1.3.1 现字节 = 第十七次同号重打，forge `879de27a…830f98` / neo `3d01c069…5b909d`）·
  上一轮 `rounds/R854-air-raid-master-switch.md`（空袭总开关挪页，未验收）·
  `DEV_GUIDE.md` §1 注释口径 / §5 i18n 联动（**配置注释也是 i18n key**）/ §6.1 引语口径 / §10 存量表 ·
  `i18n/PromaidI18n.java`（整串精确匹配 → 剥 §x → `compose` 贪心最长段匹配；显示出口三处 mixin）·
  `PromaidConfigScreen.java` 的 `rowHeight`/`drawComment`（**面板注释怎么画**：10px/行、行高 = 注释行数、
  超出页面下界会截断成"……（余下见手册）"——这就是"注释太长把整页撑满"的机制）。
  结论：不是已记的 bug 债；本轮 = 清 TD-03 的①（配置注释）与②（面板），并按开发者新口径给面板注释立"≤100 字"。
- **口径**（开发者指令转写成可判定规则）：
  1. **游戏内可见文案 = 零「实测」**。范围： `MaidSmartConfig` 的 builder comment（配置文件注释 + i18n key）·
     `PromaidConfigScreen` 的面板行注释 / 分区标题（游戏内直接画出来）· `ProMaidMod` 的两条玩家/日志可见消息 ·
     `META-INF/mods.toml` 描述（模组列表里显示）· 模型 json 的 credit（随包，顺手）。
     **判据**：两树 `jar` 内**任何条目**的字节里搜不到 UTF-8 的「实测」；源码可见字面量里 0 处。
     （源码注释里的 `实测NNN` 出处行**不动**——`DEV_GUIDE.md` §1.2 明确允许"出处最多一行"，且不进 jar。）
  2. **游戏内注释 ≤ 100 字**：`PromaidConfigScreen` 的**面板行注释**（含 `InfoRow`/`TextRow`/`BtnRow`/`CycleRow`/
     `NumRow`/`BoolRow`）逻辑字符数 >100 的一律重写；重写后统一为"作用 + 取值 + 关掉会怎样"、
     当前口径（不写旧版/轮次/需求原话）、不用 `**`（面板按原样画，星号会直接显示），并同步英文 value。
     配置**文件**注释（`MaidSmartConfig`）不压长度（留着完整工程口径；`ARCHITECTURE-SWITCHES.md` 的"作用"列就是它），
     只按口径 1 删「实测」。**边界先写明**：若开发者要连配置文件注释也压到 100 字，那是下一轮的事（会丢技术证据）。

- **工作队列**（逐步，做完就勾；每步写"完成判据"）：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 侦察 + 盘点 | 两树逐文件扫「实测」：可见字面量 3 个 java + 2 个资产 + 2 条 toml 描述；面板注释 >100 字 = 149(F)/153(N)；配置注释含实测 = 77(F)/81(N)；并写出 `_r855_inv.json` 编辑清单（含 span 与 i18n 覆盖） | ☑ |
| 1 | 排班 | 本文件写完（就是本节） | ☑ |
| 2 | 定稿新文本 | 按**整段拼接注释**口径重扫得 422 行需改（F 208 / N 214 个字面量 span）；此前 165 条里只有 310 行精确命中、4 行片段命中，**其余 108 行（含两树差异行）另写 112 条文案**（`_r855_plan.json`：`uncovered=0`、`maxlen=100`、无实测、无 `**`） | ☑ |
| 3 | 落盘 Forge | span 级替换（`_r855_apply2.py`：只动"最后一个纯字面量参数"的 span，`count==1` 断言）：`PromaidConfigScreen` 208 · `MaidSmartConfig` 77 · `ProMaidMod` 2；另有 `mods.toml` / `compression_box.json` 署名 / `MaidStrollCheck` 三处 `\uXXXX` 转义（`_r855_fix_rest.py`） | ☑ |
| 4 | 镜像 Neo | 同一字符串两树逐字节一致（1.21.1 专属行单独审：`PromaidConfigScreen` 214 · `MaidSmartConfig` 81 · `ProMaidMod` 2）；两树 javac `EXITCODE=0` | ☑ |
| 5 | i18n 两树 | 新 key 与源码逐字节一致；英文 value 更新；撤旧 key（F 47 / N 49）、建新 key（F 55 / N 57）；清陈旧（12/8+16/14）；补共用键 6/6 与漏键 4+3+2；`json.loads` 通过；**结尾变短只能原地写 + 尾部空格补齐**（文件被映射锁住，不截断） | ☑ |
| 6 | 护栏 | `check_comment_style` 2258 · `check_preset` 0/2 · `check_workflow` 0/2 · `check_changelog` 1261（全部回基线）；`gen_switch_inventory.py --write` 重生成、末尾存量表 = 0/0 | ☑ |
| 7 | 打包 | 同号**第 18 次**：`MISSING: none` + jar 自查 + **jar 内全条目搜不到「实测」** + `RELEASES.tsv` 留痕 | ☑ |
| 8 | 交付 | 开发者开闸「部署+回归」后：`deploy_all.py` 4 目标全 `match=True` + 专服回归两加载器各两件全 PASS（本包同时交付 R853 / R854 尚未落地的内容） | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者；验法 = 游戏内开手册 → 配置面板逐页翻：
  ① 任意页都看不到「实测N」；② 每条注释两行内（≤100 字）、不再出现 500+ 字撑满整页的行；
  ③ 英文语言下注释同样是英文。日志/配置文件方面：`config/promaid-*.toml` 的注释里也没有「实测」。

## 需求 / 来源

开发者指令（原话）：

> 删掉游戏内所有实测XXX的字样，同时游戏内注释超过100字的都需要进行删减。继续

台账对应：`TECH_DEBT.md` **TD-03**（可见文案里的开发过程）①②；口径在 `WORKFLOW.md` §6 已立（可见文案禁 `实测NNN`），
本轮把存量清干净，并新增"游戏内注释 ≤100 字"。

## 评估

- **跑道**：只改文案（无行为改动）——不动开关、不动默认值、不动存档/toml 结构；`javac` 只能证明没写坏，行为的
  "没变"由"只替换字符串字面量"这一事实保证（见 §验证 的字符级核对）。
- **为什么不能只删 tag 了事**：面板注释是**游戏内逐行画**的（`drawComment`，10px 一行），
  一条 553 字的注释 = 26 行 = 260px，单行就能把一页撑满、还会被下界截断成"……（余下见手册）"。
  所以口径 2 的"删减"= 把每一条压回"作用 + 取值 + 关掉会怎样"，细节留在配置文件注释与手册里。
- **口径落地时发现的关键一条**：面板注释在源码里常写成 `"a" + "b"` 多行拼接，`PromaidI18n` 的
  `compose()` 又是**贪心最长段**匹配——所以"注释是否 >100 字"必须按**拼接后的整段**量，不能按单个字面量量。
  按整段口径重扫后，需改行数从 149/153 涨到 422（含 108 行旧清单没收进来的）。
- **不做什么**：不改手册正文（R850 刚翻新、且本轮无实测残留）；不压配置文件注释的长度（工程证据；
  要压另开一轮）；不动源码注释的"出处一行"（`DEV_GUIDE.md` §1.2 允许）。

## 改法

- 见 §排班 的口径 1/2。落盘用 `tools/_r855_apply2.py`：按清单里的 **span**（"最后一个纯字面量参数"的起止）
  整段替换成新字面量（多行 `"a" + "b"` 拼接压成一行——新串都是单行），逐条 `count==1` 断言，
  失败整体不写；i18n 表：撤旧 key、按新串建 key、value 换成新英文，`json.loads` 复验，
  写回时**不允许截断**（文件被映射锁住）→ 变长直接写、变短尾部补空格。
- 面板行的替换锚点只落在**注释参数**上：label / value / onChange / min / max 与参数个数都逐行核过
  （`bad_args=0`），保证"只动文案"这句话能被机械复核。
- **不做**：不改任何 `MaidSmartConfig.*` 的默认值/路径；不改 `lang/*.json`（配置项名称表，本轮无新配置项）；
  不改 `ARCHITECTURE.md` / `机制详解.md`（dev 面文档，非游戏内）。

## 过程备注（span 越界与还原，留痕用）

- 第一版 `_r855_apply.py` 的 span 取错：把"行首 → 注释末字面量"整段当成了替换范围，
  一次替换会连 label/value/onChange 一起吃掉。发现后**没有就地改**，而是：
  ① 用桌面快照 `Promaid_本体当前\source` 与编辑前的逐行锚点清单对账（314/314 面板 + 158/158 配置锚点全中），
  确认快照就是编辑前状态；② 从快照还原两树 3 个 java；③ 重扫补全 112 条新文案；
  ④ 换成只动"最后一个纯字面量参数"的 `_r855_apply2.py` 重落（`issues: 0`）。
- `ProMaidMod.java` 两条可见字面量在第一版落盘时被插进引号导致 javac 报错，已修好并补 2 条 i18n key；
  两树 javac 均 `EXITCODE=0`。
- 收尾修正若干（都在本轮内闭环）：被误删的 6 条"配置注释/面板行共用" i18n key 还原；
  3 条 `\uXXXX` 转义里的「实测」（`MaidStrollCheck` 的参考表/口诀/回执）；`compression_box.json` 署名里的
  `v1.2.2 实测六百一十七`；3 行面板文本里漏网的 `**`（大运三条，面板会照原样画星号）。

## 触及文件

- 两树 `com/maidsmart/config/MaidSmartConfig.java`（配置注释 77/81 条：删「实测」）
- 两树 `com/maidsmart/config/PromaidConfigScreen.java`（208/214 个字面量 span：删「实测」+ 压 ≤100 字 + 分区标题）
- 两树 `com/maidsmart/ProMaidMod.java`（2 条日志/迁移消息）
- 两树 `com/maidsmart/command/MaidStrollCheck.java`（3 处 `\uXXXX` 转义文案）
- 两树 `assets/promaid/i18n/en_us.json`（新 key + 新英文 value；旧 key 撤掉；共用键还原；漏键补齐）
- 两树 `META-INF/mods.toml`（描述里的「经实测」/裸轮次区间）、`assets/maid_smart/models/item/compression_box.json`（credit）
- `ARCHITECTURE-SWITCHES.md`（重生成）、`rounds/RELEASES.tsv`（第十八次字节）、本轮记录、`TECH_DEBT.md`（TD-03/TD-07 状态）

## 验证

- **编译**：两树 `javac EXITCODE=0`（Forge 88 条 deprecation 警告、0 错误；Neo 0 错误）。
- **面板口径逐条核（`_r855_verify2.py`，按整段拼接量）**：面板注释 >100 字 = **0**（F 583 行 / N 594 行里）；
  分区标题 >100 = **0**；可见字面量里「实测」= **0**、`**` = **0**；行参数完整 **bad_args = 0**。
- **字面量扫描（`_r855_sweep.py`，转义解码后按值搜）**：两树命中 **0**（修前各 5：`compression_box.json` 署名、
  `MaidStrollCheck` 三处转义、`mods.toml`）。
- **i18n（两树）**：本轮用到的文案全部有 key（**missing = 0**）；key 里「实测」= **0**；英文 value 里轮次引用 = **0**；
  `json.loads` 通过（F **6995** / N **6992** 条）。
- **jar 自查（打包后按字节）**：两个 jar 的**全条目**（含 class 常量池、json、toml）搜 UTF-8「实测」= **0**；
  无 `.new/.tmp/.bak` 类临时条目；`verify_jar_classes` OK（F 760 / N 764 个 class 全在，mixin 登记与注入签名 OK）。
- **四护栏回基线**：`check_comment_style` 929 文件 / 2258 处 · `check_preset` FAIL 0 / WARN 2 ·
  `check_workflow` FAIL 0 / WARN 2 · `check_changelog` 1261 处。
- **打包**：同号**第 18 次**——F `5bc98ccf…bc750e`（17,556,943 字节）/ N `6afc03b2…f70a81`（17,595,601 字节）；
  `RELEASES.tsv` 已登记并把旧字节作废留痕（F `879de27a…830f98` / N `3d01c069…5b909d`）；桌面快照随包自动同步。
- **源码注释残留**：`ProMaidMod.java` 里仍有约 50 行 `// 实测NNN：…` 的出处行——按 `DEV_GUIDE.md` §1.2
  "出处最多一行"，且**不进 jar**（jar 自查 0 已证）。
- **部署（开发者开闸「部署+回归」后执行）**：`tools/deploy/deploy_all.py` 4 个目标全部 `match=True`——
  `1.21.1-NeoForge_21.1.250` 17,595,601 B、`1.20.1-Forge_47.4.10` / `龙之冒险：新征程v2.4a` /
  `mc_server_test\pack1201` 各 17,556,943 B；两个 Forge 客户端实例文件被占用，脚本按设计走提权通道（rc 0）。
  另按 sha256 逐个复核：4 个落地 jar 与 `patched/` **逐字节一致**（neo `6afc03b2…f70a81` /
  forge `5bc98ccf…bc750e`）；旧包逐个备份进 `patched/backup_old/`。
- **专服回归（跑的就是 `patched/` 里这一包）**：`test_server.py 1201` **PASS**（`Done (3.115s)`）·
  `test_maid_load.py 1201` **PASS**（真 summon 了一只女仆、`WorldProbe preloaded OK`、
  extra-core 注册 7 条）· `test_server.py neoforge1211` **PASS**（`Done (1.272s)`）·
  `test_maid_load.py neoforge1211` **PASS**。四件全过；四个日志里**与 promaid 相关的 ERROR 行 = 0**
  （剩下的 ERROR 都是别的模组存量：TLM Spell 的 loot table、`RuntimeDistCleaner` 的客户端类、refmap 警告）。

## 验收（谁说了算：开发者 —— `WORKFLOW.md` §4.1）

- 状态：`未验收`（已部署 + 已回归；等开发者进游戏翻配置面板 + 配置文件给结论）。
  本轮文案改动只有翻页面才看得出来，回归只证明"这一包的类装载 / mixin 注入没坏"。

## 边界（没动什么）

- 不改任何行为/开关/默认值/配置格式；只替换可见字符串字面量 + i18n 表。
- **不压配置文件注释的长度**（只删轮次号）——完整工程口径留着；要压另开一轮。
- 源码注释里的「出处一行」保留（`DEV_GUIDE.md` §1.2；不进 jar）。
- 历史文档（`CHANGELOG.md`、`store/version_changelog.md`、`rounds/*.md`）不属游戏内文案，未动
  （这也是 `check_changelog` 1261 与基线一致的原因）。
- **已部署@2026-10-10、已回归@2026-10-10**（开发者开闸「部署+回归」后执行，见 §验证）；**未写 changelog**
  （按 §6.1，等验收）。本包同时把第十六 / 十七次（R853 落地水落点帧、R854 空袭总开关挪页）尚未落地的内容一起装上。
- `TD-07` 的存量漂移本轮**未扩大处理**：抽查口径还剩 F cfg 37 / 面板片段 9、N cfg 38 / 面板片段 8
  （记忆系统那批注释、武装拴绳几个 InfoRow 片段等）；本轮只保证"动到的条目全部同步"。

## 台账

- 清 `TD-03` ①②（配置注释 + 面板/工具提示）：两树可见文案「实测」= 0、面板注释全部 ≤100 字；
  `TD-03` 只剩 ③（英文 value 里非手册面的 `we/our/us`）。
- `TD-07` 状态补注：本轮触到的 key 全部同步，剩余存量按抽查口径 F 37+9 / N 38+8，留给 TD-07 自己那一轮。
- 不新增债；`ARCHITECTURE-SWITCHES.md` 末尾存量表 0/0。
