# 实测八百八十四 · TD-07 收口：配置 key 短名补齐（自动补全页不再显示裸 key）

## 需求 / 来源

TD-07：「i18n 表漂移——老轮次改了源码文案但没同步 key，英文界面回落中文」。开发者：「剩下的你逐一修复。我们必须要根除 issue37 和 38 中的问题」。

约定不变：不跑回归、不写 changelog / release 说明、版本号不动（仍 **1.3.2**）、两树镜像、临时脚本用完即删、**不 commit / 不 push**（WORKFLOW §12）。

## 评估（先查清"缺 key 到底伤到谁"）

追了一遍面板渲染链，结论关键：
- 配置面板主页（`PromaidConfigScreen` 各 `xxxRows()`）用的是**硬编码中文字面量**当标签、另一段硬编码中文当说明；英文靠 `GuiGraphicsI18nMixin` → `PromaidI18n.tr()` 查 `assets/promaid/i18n/en_us.json`（**按中文字面量作 key**）。**与 `maid_smart/lang` 无关**。
- 唯一用 `maid_smart/lang` 的是「其余开关（自动补全）」页：`Component.translatable("config.promaid." + path)`，**没有回退参数** → key 缺失时原样显示**裸 key**（如 `config.promaid.memory.decayDays`）。
- `.comment("…")` 文本只进生成的 TOML 文件，面板不读。

所以 TD-07 的"缺 key"这一块，实际症状是**自动补全页显示裸 key**（不是"回落中文"）；而"回落中文"对应的是 `assets/promaid/i18n/en_us.json` 那张表（属 TD-03 范畴）。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 追链 | 查清缺 key 的真实用户可见面（自动补全页裸 key） | 结论（本轮） | ☑ |
| 2 | 实现 | 两树各 **184** 条 `config.promaid.*` 短名补进 `zh_cn`/`en_us`（zh 取说明前段、en 逐条写） | 4 个 lang 文件 | ☑ |
| 3 | 护栏 | `check_i18n_keys` 基线 **184/182 → 0/0**（棘轮：此后新项必须带译文） | 1 文件 | ☑ |
| 4 | 验证 | 两树编译、打包、五护栏、en_us 无中文残留 | 构建输出 | ☑ |
| 5 | 留痕 | `RELEASES.tsv` 第四十四次 + 本文 + `TECH_DEBT` TD-07 收口 | 两处 | ☑ |
| 6 | 验收 | 交开发者：短名译法是否认可 | 待开闸 | ☐ |

## 改法

- 从 `MaidSmartConfig` 抓缺的 184 个 `.translation("config.promaid.*")`；zh 短名取同项 `.comment()` 的**前段**（冒号前/首括号前，如 `TNT 点火保护期（秒）`、`透视感知（隔墙找矿）`），en 逐条写英文（如 `TNT ignition grace period (seconds)`、`See-through-walls ore sense`）。
- 用 `json` 读写（**已验证 round-trip 逐字节一致**：`indent=2, ensure_ascii=False` 与原文件相同）→ 追加 184 条，其余键不动。
- `check_i18n_keys.py` 基线改 `0/0`。

### 不做

- **不动** `assets/promaid/i18n/en_us.json`（面板主页那张以中文字面量为 key 的表——属 TD-03/后续）；
- 不改 `.comment()` / 开关 / 行为 / 版本号。

## 触及文件

**文案**（两树各两份）：`promaid_src[|_neo]/assets/maid_smart/lang/{zh_cn,en_us}.json`（zh 502→686 / 508→692；en 506→688 / 512→694）。
**工具 / 台账**：`tools/verify/check_i18n_keys.py`、`TECH_DEBT.md`、`rounds/RELEASES.tsv`、`rounds/R884-*.md`（新）。

## 验证

| 项 | 结果 |
|---|---|
| 护栏 | `check_i18n_keys` FAIL 0 / WARN 0 —— 有 key 无译文两树 **0 / 0**（本片 -184/-182） |
| en 无中文 | 两树 `en_us.json` 含 CJK 的 value = **0** |
| JSON 合法 | 两树四份 lang `json.load` OK；打包自检 `lang json: OK` |
| 两树编译 | `javac EXITCODE=0` |
| 既有护栏 | `check_preset` 0/0；`check_workflow` 0/3；`check_catch_swallow` 0/0；`check_comment_style` 931/2260；`check_panel_coverage` 0/0 |
| 打包自检 | 两树 `MISSING: none`；Forge **17,610,897 B**（`be959a0d84e6…7e02b9`）、Neo **17,650,069 B**（`2db805d0…847353`） |
| 部署 / 回归 | **未部署、未回归**（开发者闸门） |
| commit / push | **未做**（WORKFLOW §12） |

**反证**：把任意一条新补的 key 从 lang 删掉 → 缺数 0 → 1 > 基线 → `check_i18n_keys` FAIL；补回 → PASS。自动补全页对应行也不再显示 `config.promaid.memory.decayDays` 这样的裸 key。

## 验收

**未验收**（短名译法/英文用词是否认可，待开发者定）。

## 边界

- **不改行为 / 不改面板主页**：只给自动补全页补短名键；主页标签仍走 `promaid/i18n` 那条链。
- 覆盖面：仅 `MaidSmartConfig` 的 `config.promaid.*` 一键一类；面板/手册其它 key 源由各自护栏与 TD-03 管。
- 仍未做：TD-03③（`promaid/i18n/en_us.json` 的开发口吻）、TD-12①、TD-13②。

## 台账

- **TD-07 收口**（本类）：184 条配置短名补齐，棘轮基线 0/0；已查清"缺 key"与"回落中文"分属两张表。
- 注：TD-03 的剩余部分（面板/手册那张中文字面量表的口吻）与 TD-07 相邻，后续可同批处理。
