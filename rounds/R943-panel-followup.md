# 实测九百四十三 手册配置面板续修（移动与跟随归位 / toml 余量归位 / 危险方块表勾选子页 / 挖矿伐木注释重写）

> 跑道：承接 `rounds/R942-panel-restructure.md`。开发者原话：「塞入移动与行为 / 压缩盒暂时不管，剩下的你来」。
> 本轮依据：开发者上批点名里的剩余项 + R942「## 边界」里如实记录的待办。

## 排班

- 本轮为 R942 面板批次的**收尾增量**（part A/B/C），**压缩盒差异化按开发者指令暂缓**。
- 步骤：改 Forge → 镜像 Neo（逐锚点断言唯一，命中数 ≠ 1 即中止；Neo 渲染/规格名差异另写 Neo 脚本）→
  两树 `javac EXITCODE=0` → 全护栏 `FAIL 0` → 打 jar → 部署 4 实例 `match=True` → 台账/轮次记录 → 清临时脚本。
- 谁验收：开发者（本轮**已部署**、不跑回归；`## 验收` 记 `未验收`）。

## 需求 / 来源

开发者原话（本轮）：

- 「塞入**移动与行为**（= 把 R942 暂放进「系统与杂项」的「移动与跟随」放回「移动与行为」大类）；
  **压缩盒暂时不管**；**剩下的你来**」。
- 剩余项（R942 边界里列出的待办）：危险方块表→勾选子页；挖矿/伐木陈旧配置重写；toml「其余开关（自动）」全量归位。

## 定位与改法（本轮落地项）

- **part A-1「移动与跟随」归位**：`Section.FOLLOW` 的 Group 由 `SYSTEM` 改回 `MOVE`；
  `groupHint(MOVE)` 文案补上「移动与跟随」。
- **part A-2 toml 余量全量归位**：新增通用行生成器
  `cvAuto(ForgeConfigSpec.ConfigValue<?>)`（Neo `ModConfigSpec.ConfigValue<?>`）——
  按 `BooleanValue / IntValue / DoubleValue / List / String` 分派生成 `BoolRow / NumRow / TextRow / InfoRow`，
  标签走 `Component.m_237110_("config.promaid."+path)`（Neo `Component.translatable`）中文译文，
  tip 记「config 文件里同名键：<path>」。把原「其余开关（自动）」页里 **63 项** toml 余量按 Section
  追加到各自页面末尾：airRaid(1) / build(6) / autoCombat(3) / selfPreserve(3) / playerDamage(2) /
  tactics(5) / ride(2) / daYun(3) / dialogue(2) / memory(20) / mine(2) / cookBrew(1) / idle(2) /
  farm(3) / follow(3) / utility(3) / schedule(2)。`EXTRA` 自动页因此清空（仅剩自动补全入口本身的说明）。
- **part B 危险方块表→勾选子页**：`MISC_DANGER_BLOCKS` 由裸注册名 `TextRow` 改为与挖矿同款的勾选子页——
  `mineTableMode` 扩到 `2`（`modeNames = {"目标矿物","障碍物","危险方块"}`）；
  挖矿页「危险方块表」原 `TextRow` 换成 `BtnRow("危险方块名单", "管理 →（共 N）", …)`，
  点击进入 `mineTable=true; mineTableMode=2`；`isInList / toggleCreative / removeMinable / MinableList.rebuild`
  增加 mode-2 分支，读写 `MISC_DANGER_BLOCKS`（**完整注册名** `namespace:path`）；
  子页标题「危险方块——点击方块图标加入/移出（再点取消）」、命中框色 `0x80CC4444`、底部提示按模式区分；
  mode-2 不显示「优先级输入框 / 锁定黄框 / 右上角 ×」（那是矿物的价值编辑）。
- **part C 挖矿/伐木陈旧文案重写**（用户可见 `comment` 串）：
  - 修正「软方块不耗耐久」误标「默认关」——`softNoDurability` 实为**默认开**（矿/木同）；
    去掉 `v1.5.138` 版本号，改为「（默认开）：只挖泥土/沙子这类软方块开路时不扣耐久，挖本体照常扣」。
  - 挖矿「智能挖矿」描述补「够不着的矿她会记下并跳过、换下一处，不再原地卡死」（对齐 R941 停滞弃置）。
  - 伐木「透视感知（隔墙找木材）」随挖矿一并改名 **「智能伐木」**，描述改为「（默认开）：女仆会自己感知并尝试伐木……」。
  - 伐木「自动收集」补默认标注「（默认关）」；挖矿「挖矿中禁止拾取」补「（默认关）」并说明语义。
- **part C 附带清理**：删掉 R943 part A 追加行尾的轮次标记 `// 实测九百四十三 归位`（开发者口径：
  注释不出现版本号/轮次；同时消除 `check_comment_style` 因连续 20 行尾注释触发的超长注释块告警）。

## 触及文件

- 两树 × `com/maidsmart/config/PromaidConfigScreen.java`（Group/Section、`cvAuto` 生成器 + 63 处调用、
  `mineTableMode=2` 与危险方块子页读写、挖矿/伐木文案）
- `rounds/RELEASES.tsv`（第八十二次同号重打）+ `rounds/R943-panel-followup.md`（本文件）
- 注：`panel_coverage.txt` 未变（R943 未新增/删除 toml 键，只把已收录键的行搬了位置；
  `check_panel_coverage` 仍 **FAIL 0 / WARN 0**）。

## 验证

- **两树编译**：`compile_promaid.bat` / `compile_neo.bat` → `EXITCODE=0`
  （Neo 侧 `ModConfigSpec.ConfigValue` / `Component.translatable` / `.bounds(…).build()` / `rebuildWidgets()`
  等 Mojang 名与 Forge 的 SRG 名分树供给，逐锚点命中数 = 1）。
- **全护栏**：11 个全 `FAIL 0`：
  - `check_comment_style` **PASS**（清理轮次尾注释后，原 2 处 20 行超长注释块归零）；
  - `check_panel_coverage` **FAIL 0 / WARN 0**（Forge 声明 599 / 已收录 588 / 未收录 1 / 迁移跳过 10；
    Neo 605 / 594 / 1 / 10）；
  - `check_preset` **FAIL 0 / WARN 0**、DIY 基线 173/177；
  - `check_i18n_keys` **FAIL 0 / WARN 0**（zh_cn/en_us 各 620 / 626 config 键，无「有 key 无译文」）；
  - `check_workflow` **FAIL 0**、3 条历史 WARN（PUBLISH_GUIDE 版本号差异 + 两条更早 jar 归档未登记）；
  - `check_tree_mirror` 只读报告。
- **两个 jar**：forge **17591670** 字节（sha256 `563F272C…152BB7`）；
  neoforge **17627841** 字节（`0E293B6A…3464C7`）。
- **装机**：`deploy_all.py` → 4 个本机实例全部 **`match=True`**。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 行更新 sha256 并列第八十二次同号重打；
  九百四十二 的旧字节 `1A8487B4…9B9B29`（forge）/ `3C2B09F4…4E38F6A`（neo）作废。
- **未跑实机回归**（已部署，等开发者实测）。

## 验收

- 状态：**未验收**（已部署：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
- **判据**：
  - 「移动与行为」大类里能看到「移动与跟随」小类。
  - 打开任意配置小页，末尾能看到原来只在「其余开关（自动）」里出现的 toml 项（以中文标签呈现）；
    「其余开关（自动）」页自身不再堆一长串杂项。
  - 挖矿页「危险方块表」行变成「危险方块名单 · 管理 →（共 N）」按钮；点进去是创造面板网格，
    点方块图标即加入/移出危险表（完整注册名），命中方块有红框对勾，再点取消。
  - 挖矿页「软方块不耗耐久」默认**开**且描述不再写「默认关 / v1.5.138」；
    伐木页原「透视感知（隔墙找木材）」显示为「智能伐木」。

## 边界（没动什么）

- **没动**：压缩盒本体与其配置（开发者明确「暂时不管」）；配置项的 toml 段名/键名（老 toml 与
  翻译键 `config.promaid.<section>.<key>` 完全不受影响）；危险方块表的**判定语义**
  （寻路绕行 / 险境脱离 / 搭块选材三系统共用 `MISC_DANGER_BLOCKS`，只改填充 UI，不改读取）；
  挖矿/伐木的行为逻辑（part C 只重写用户可见注释文字，无逻辑改动）。
- **口径变更（有意，非回归）**：「软方块不耗耐久」注释由「默认关」改为「默认开」——
  这是**订正文案**去对齐既有默认值（`softNoDurability=true` 未变），非改行为。
- **没新增配置开关**；版本号仍 **1.3.2**。
- **已知取舍（如实记录 + 待开发者裁）**：
  1. 伐木「透视感知（隔墙找木材）」→「智能伐木」是我按挖矿「智能挖矿」口径**推断**的对称改名
     （开发者只明写了挖矿那条）；若只想改挖矿，回退一处标签即可。
  2. `cvAuto` 生成的 63 行沿用原「其余开关」页**同一套** `config.promaid.<path>` 译文标签
     （`assets/maid_smart/lang/…` 已有这些键，`check_i18n_keys` 无缺译文），故只是**搬了位置**、
     标签解析方式与之前逐字一致，无回归。
  3. 挖矿页里的「挖矿中禁止拾取」「拾取按价值重排」仍留在挖矿页（无独立「拾取」小类可归）；
     如你要单列，属下一批。

## 台账

- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第八十二次同号重打。
- `DEBT.md`：不新增条目——本轮为面板归类 / 文案 / UI 控件替换，不引入新的未验证功能面。
  （注：`DEBT.md` TD-04「压缩盒 vs 精妙背包功能重复未裁决」仍待开发者定向，见「压缩盒暂缓」。）
