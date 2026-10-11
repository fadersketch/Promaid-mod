# 实测八百八十一 · TD-07（护栏）：i18n 键漂移棘轮

## 需求 / 来源

TD-07：「i18n 表漂移——老轮次改了源码文案但没同步 key，英文界面回落中文」。开发者：「继续，一直做到所有任务结束」。

本片做**可复现的那一半**：把"有 key 无译文"的存量量出来、钉上棘轮（只减不增），防增量；**整批 184 条译文**属内容任务，按节分批补（另记，见下）。

约定不变：不跑回归、不写 changelog / release 说明、版本号不动（仍 **1.3.2**）、两树镜像、临时脚本用完即删、**不 commit / 不 push**（WORKFLOW §12）。

## 评估（先量）

- 从 `MaidSmartConfig.java` 抓全部 `.translation("k")` 字面量（**先剔注释**——javadoc 里那句 `.translation("config.promaid.*")` 是示例，不剔会多算 1 条）。
- 逐个查 `assets/maid_smart/lang/{zh_cn,en_us}.json`：**两树均 zh 缺 184 / en 缺 182**（config 键 626 / 632）。
- 旁证：两树 `en_us.json` 里**含中文的 value = 0 条**（不存在"英文值回落中文"；缺的是**整条 key**，缺失时配置面板/界面拿不到译文）。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 审计 | 量出"有 key 无译文"存量（剔注释后） | 数字：两树 zh 184 / en 182 | ☑ |
| 2 | 护栏 | 新增 `check_i18n_keys.py`（棘轮：高于基线 FAIL） | 1 文件 | ☑ |
| 3 | 接线 | 接进两 `build_promaid*.py`；更新 `WORKFLOW.md` §11 / `DEV_GUIDE.md` §6 / `BUILD.md` §5 表 | 5 文件 | ☑ |
| 4 | 验证 | 两树编译、打包（护栏工具不进 jar → 字节不变）、护栏 PASS | 构建输出 | ☑ |
| 5 | 留痕 | 本文 + `TECH_DEBT` TD-07 记"护栏落地，内容待补" | 一处 | ☑ |
| 6 | 验收 | 交开发者：剩余 184 键是否按节分批补 | 待开闸 | ☐ |

## 改法

新增 `tools/verify/check_i18n_keys.py`：
- 抓 `MaidSmartConfig` 的 `.translation("k")`（剔注释）→ 查两树 `maid_smart/lang/{zh_cn,en_us}.json`；
- 基线 `{'promaid_src': {zh 184, en 182}, 'promaid_src_neo': {同}}`；**高于基线 → FAIL、低于 → WARN**（棘轮只减不增）；
- `--dump` 打出缺的 key 便于下一轮挑一节补。

接线：两个 `build_promaid*.py` 的护栏元组 +1；`WORKFLOW.md` §11 表、`DEV_GUIDE.md` §6 表（"四个护栏"→"五个护栏"）、`BUILD.md` §5 命令块各加一行。

### 不做

- **不整批补 184 条译文**：属内容/文案任务（zh 取同项 `.comment()` 原文、en 写英文），逐节做更稳；本片只防增量；
- 不改任何 `.comment()` / 开关 / 版本号。

## 触及文件

**工具 / 文档**：`tools/verify/check_i18n_keys.py`（新）、`tools/build/build_promaid.py`、`tools/build/build_promaid_neo.py`、`WORKFLOW.md`、`DEV_GUIDE.md`、`BUILD.md`、`TECH_DEBT.md`、`rounds/R881-*.md`（新）。
**产物**：无（护栏脚本不进 jar，两 jar 字节与 R880 一致）。

## 验证

| 项 | 结果 |
|---|---|
| 新护栏 | `check_i18n_keys` FAIL 0 / WARN 0（zh 184 / en 182 = 基线） |
| 两树编译 | `javac EXITCODE=0` |
| 打包自检 | 两树 `MISSING: none`；Forge **17,603,854 B**（`e501cbd29e43…b88a73`）、Neo **17,642,994 B**（`bd0b2c1a9c41…cb7b14`）——**与 R880 逐字节相同**（工具改动不进产物，故 RELEASES 不动） |
| 既有护栏 | `check_preset` FAIL 0 / WARN 2；`check_workflow` FAIL 0 / WARN 3；`check_catch_swallow` 0/0；`check_comment_style` 931 / 2260；`check_panel_coverage` 0/0 |
| 部署 / 回归 | **未部署、未回归**（开发者闸门） |
| commit / push | **未做**（WORKFLOW §12） |

**反证**：给某个 `.translation("config.promaid.demo")` 新键却不在 lang 里 → 缺数 184→185 > 基线 → `check_i18n_keys` FAIL（棘轮生效）；把该 key 补进两份 lang → 回到 184、PASS。

## 验收

**未验收**（剩余译文是否按节分批补、口径是否用本护栏的数字，待开发者定）。

## 边界

- **不改行为 / 不改产物**：只加护栏与文档；jar 字节不变。
- 度量口径：只数 `MaidSmartConfig.java` 的 `translation()` 字面量 vs `maid_smart/lang`；不含面板/手册的其它 key 源（那批由 `check_panel_coverage` 与手册 i18n 各自管）。
- 仍未做：整批 184 条译文补齐；TD-03③（`en_us.json` 里 `we/our/us` 口吻）；TD-01（配置耦合）。

## 台账

- **TD-07 部分**：棘轮护栏落地（防增量）；存量 184/182 按节分批补（内容任务）。
- 已清 / 已确认：TD-02、06、09、10、11、14、16、18；部分：TD-03、12、13、15；保留：TD-05、17；未清（需判断或较大工程）：TD-01、04、07、08。
