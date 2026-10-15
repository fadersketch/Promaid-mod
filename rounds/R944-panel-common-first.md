# 实测九百四十四 面板「常用优先 / 简约」四项落实（一键预设 / 开关优先 / 常用跳转页 / 高级项折叠）

> 跑道：承接 `rounds/R943-panel-followup.md`。来源：开发者转述的粉丝反馈 + 自己圈定的四个方向。
> 开发者指令原话：「3-1-2-4 全部落实」。

## 排班

- 本轮四个方向**一次性交付**（3 一键预设 → 1 开关优先 → 2 常用跳转页 → 4 高级项折叠），
  两树镜像、无新增配置项 / i18n 键。
- 步骤：读面板结构（Group / Section / RowDef / sectionButtons 分页 / render）→ 改 Forge →
  镜像 Neo（逐锚点断言唯一，Neo 名差异另供）→ 两树 `javac EXITCODE=0` → 全护栏 `FAIL 0` →
  打 jar → 部署 4 实例 `match=True` → 台账/轮次记录 → 清临时脚本。
- 谁验收：开发者（本轮**已部署**、不跑回归；`## 验收` 记 `未验收`）。

## 需求 / 来源

粉丝反馈（开发者转述）：

> 「就个人经验来说这种 mod 其实越简约越好，目前虽然 UI 已经比较好懂了，但是各种奇奇怪怪的工具饰品和
> 一个套一个的配置项，只有常年混迹社区的资深玩家能搞得定，一般玩家想用这个 mod 的话体验大概就像
> 第一次打开钢铁雄心4那种感觉（配置项的数量基本上减不了，否则玩家就搞不了 DIY 了。但可以把一些常用的放前面）」
>
> 回复：「确实是个好主意，尤其开关类的和索敌跟随范围这些其实我觉得都可以往前放放」

我（助手）据此量了面板规模并给四个方向，开发者回「3-1-2-4 全部落实」。四个方向原文：

1. **方向三**：把已有的「预设模式」（R835 的 DIY / 出厂默认一键切换）提到明面——那是"新手别管这五百项"的正解。
2. **方向一**：页内提升行序——把各页「总开关」提到最前（重点是首行是数值的页）。
3. **方向二**：新增置顶「常用」大类，放总开关 + 少数真正会调的索敌/跟随范围（跳转按钮版）。
4. **方向四**：每页加「显示高级项」折叠开关，默认只露开关 + 少数范围。

## 定位与改法（本轮落地项）

- **新增 `Group.COMMON("§e常用")`（枚举置顶 → 首页第一个按钮）+ `Section.COMMON_PAGE("常用与一键预设")`**；
  `sectionButtons` 的 `switch (this.section)` 加 `case COMMON_PAGE -> this.commonRows();`；
  `groupHint` 加 `case COMMON`（否则 `switch` 表达式不穷尽编译不过）。
- **`commonRows()`（三个内容块）**：
  - ① 一键预设：`SectionRow` + 只读 `InfoRow("当前模式", DIY/默认)` +
    两个 `BtnRow`（切到出厂默认 / 切到 DIY），`btnText` 随 `presetArmed` 变成「§c再点一次确认」。
  - ② 常用开关：20 项「总开关 / 启用」跳转按钮。
  - ③ 常用索敌 / 跟随范围：19 项常调数值跳转按钮。
- **`jumpTo(sectionName, label)`**：遍历 `Section.values()` 找同名，置 `section / inGroup=false / inHome=false /
  pageIndex=0 / mineTable=false / woodTable=false / focusLabel=label` 后重建——纯跳转不复制控件，
  借既有的「手册链接定位」机制翻页并高亮目标行。
- **`presetStep(want)`**：两步确认。第一次点只 `presetArmed = want` + 重建（按钮文案变「再点一次确认」）；
  第二次点才 `MaidConfigPreset.applyDiy()` / `applyFactoryDefault()`，聊天回执改动条数，再重建面板
  （`BoolRow.value()` 在建行时读 `.get()`，所以重建即刷新为新模式的值）。
- **方向四：`showAdvanced`（`private static boolean`，默认 false，会话级、不进 toml、不新增配置项）**：
  - `isAdvancedRow(RowDef)`：`NumRow` 且标签不在 `COMMON_NUM` → 高级；`TextRow` → 高级；其余（开关 / 循环 /
    按钮 / 小节标题 / 只读行）不算高级。
  - `COMMON_NUM`：17 个「常用范围」标签（响应/援护/投掷索敌/有效开火/起手/收手/扫帚·盘旋/扫帚·跟随起手/
    骑乘·跟随停下/武装拴绳·悬挂/散步/排班活动/挖掘/砍伐/检索/传送安全判定/主人收符）——折叠时仍显示。
  - 在 `sectionButtons` 的 `switch` 之后、分页之前：统计并（当折叠生效时）移除高级行，然后
    `rows.add(0, new BoolRow("显示高级项", showAdvanced, v -> {…重建…}, 说明))`——**每页第一行**即此开关。
  - **折叠让位于深链**：`advCollapse = !showAdvanced && this.focusLabel == null`——手册 `openAt` 深链
    定位到某个数值行时本次不折叠，否则会被藏掉「跳了个寂寞」；定位用完后 `focusLabel = null`，
    后续翻页回到精简口径。
- **方向一**：每页首行由上面的折叠开关承担「开关优先」；另把两处被数值压在后面的开关抬到页首——
  `voiceRows` 的「系统消息朗读」提到「音量倍率」之前、`farmRows` 的「产出任务增强」提到「宰杀数量阈值」之前
  （连同各自的 `//` 说明注释一起移动）。

## 触及文件

- 两树 × `com/maidsmart/config/PromaidConfigScreen.java`（Group/Section 枚举、`commonRows`/`jumpTo`/`presetStep`、
  `showAdvanced`/`COMMON_NUM`/`isAdvancedRow`/`presetArmed`、`sectionButtons` 插行与折叠、`groupHint`、两处行序）
- `rounds/RELEASES.tsv`（第八十三次同号重打）+ `rounds/R944-panel-common-first.md`（本文件）
- **未动**：`MaidSmartConfig`（不新增配置项 → `panel_coverage` / `check_preset` 基线均不变）、
  `MaidConfigPreset`（只调用既有 API）、lang 文件（新标签全为硬编码中文，与面板既有口径一致）。

## 验证

- **两树编译**：`compile_promaid.bat` / `compile_neo.bat` → `EXITCODE=0`
  （Neo 侧 `Component.literal` / `this.rebuildWidgets()` / `mc.player.displayClientMessage(…, false)` 与
  Forge 的 `Component.m_237113_` / `this.m_7856_()` / `mc.f_91074_.m_213846_(…)` 分树供给，逐锚点命中数 = 1）。
- **全护栏**：11 个全 `FAIL 0`：
  - `check_comment_style` **PASS**（新增注释块均 < 8 行连续 `//`）；
  - `check_comment_dup` **FAIL 0**——20/19 条跳转按钮的说明用运行时拼接（`"…" + s[0] + "…"`），
    源码里各只有一条字面量，不产生重复注释组；
  - `check_panel_coverage` **FAIL 0 / WARN 0**（未增删 toml 键）；`check_preset` **FAIL 0 / WARN 0**（173/177，无新配置项）；
  - `check_i18n_keys` **FAIL 0 / WARN 0**；`check_arch` **FAIL 0 / WARN 0**；`check_dev_voice` / `check_reflection` /
    `check_catch_swallow` 均 **FAIL 0**；`check_workflow` **FAIL 0** + 3 条历史 WARN；`check_tree_mirror` 只读报告。
- **两个 jar**：forge **17594614** 字节（sha256 `CCA55E74…037CB2`）；
  neoforge **17630883** 字节（`29C2738B…B164BD`）。
- **装机**：`deploy_all.py` → 4 个本机实例全部 **`match=True`**。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 行更新 sha256 并列第八十三次同号重打；
  九百四十三 的旧字节 `563F272C…152BB7`（forge）/ `0E293B6A…3464C7`（neo）作废。
- **未跑实机回归**（已部署，等开发者实测）。

## 验收

- 状态：**未验收**（已部署：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
- **判据**：
  - 首页第一个大类是「常用」；点进去第一页是「一键预设」：显示当前模式 + 两个按钮，点一下变
    「再点一次确认」，再点才切换（切换后其它板块的开关会随之整批变）。
  - 任意板块参数页第一行都是「显示高级项」开关；默认（关）时一页只看到开关 / 按钮 / 小节标题 +
    少量常用范围，数值微调不见；把它打开，数值与文本行全部回来。
  - 「常用」页下半部分列出各功能总开关与常用索敌/跟随范围，点「前往」跳到对应板块页并高亮那一行。
  - 语音页第一行是「系统消息朗读」（在「音量倍率」之前）；农场页第一行是「产出任务增强」
    （在「宰杀数量阈值」之前）——注意这两行开关本身不被折叠隐藏。
  - 手册里点到某个数值配置的深链仍能定位（定位时该页不折叠）。

## 边界（没动什么）

- **没动**：任何配置项的键名 / 段名 / 默认值（**未新增配置项**，故 `panel_coverage`、`check_preset` 基线不变，
  老 toml 完全不受影响）；`MaidConfigPreset` 的分类表与算法（只调用）；手册的既有预设页（保留，两处都能切）。
- **口径变更（有意）**：面板默认视图改为「精简」（高级项折叠）——这是本轮方向四的**目的本身**，
  不是回归；想看全量把每页首行开关打开即可，或切到 DIY/默认时按需打开。
- **会话级状态**：`showAdvanced` 是 `static`（同一游戏进程内切页/重开面板保持），**不落盘**、不进 toml；
  重启游戏回到默认（精简）。`presetArmed` 是实例级（换页即失效，防误触）。
- **已知取舍（如实记录 + 待开发者裁）**：
  1. 「常用」页自身有 40+ 个跳转按钮（约 5 页）——它是"跳转台"，本身也是一串按钮；若你觉得太长，
     可把范围那组再砍掉几条（改 `rg` 数组即可）。
  2. 「显示高级项」插在每页 **最顶**（在小节标题之上）——保证"一眼看到总闸"，代价是它在大标题前面；
     若想放到页尾，改 `rows.add(0, …)` 为 `rows.add(… )` 追加即可。
  3. 折叠只按「行类型 + 常用范围标签」判定，不改任何行定义；`COMMON_NUM` 是标签白名单
     （「检索半径」在挖矿/伐木两页同名，两页都会保留该行）。

## 台账

- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第八十三次同号重打。
- `DEBT.md`：不新增条目——本轮为面板信息架构 / 展示顺序调整，不引入新的未验证功能面，也不新增配置项。
  （注：`DEBT.md` TD-04「压缩盒 vs 精妙背包功能重复未裁决」仍待开发者定向。）
