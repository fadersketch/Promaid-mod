# 实测八百六十五 · 面板补全：所有 toml 项都要在手册里有对应开关

## 需求 / 来源

开发者指令：

> 你这样太麻烦了。所有 toml 肯定是要在手册里面有对应的开关的。

即：`config/promaid-common.toml`（两加载器各自的配置文本）里的**每一项**，都应该能在手册的配置面板（`PromaidConfigScreen`）里找到对应控件并直接改——不该逼玩家去手改 toml。

## 实测缺口（本轮开工前已核）

以「面板源码里出现过 `MaidSmartConfig.<字段>`」为「已收录」口径，逐树比对全部声明字段（含泛型类型的列表/字符串项）：

| 树 | 声明字段 | 面板已引用 | **未收录** | 其中 `*_MIGRATED` 一次性迁移标记 |
|---|---|---|---|---|
| Forge | 601 | 531 | 70 | 9 |
| Neo | 607 | 540 | 67 | 9 |

未收录的真实项（扣掉 9 个迁移标记）为 **61 / 58 项**——含 R859 那批行为开关（`COMBAT_FRIENDLY_FIRE_GUARD` … `DIALOGUE_PROMPT_SITTING_WORK`）、R862/R863 两个（`COMBAT_RIDE_PURSUE_TARGET` / `COMBAT_RIDE_STARTUP_SECONDS`）、以及 `MEMORY_*` / `*_STUCK_WATCHDOG` / `BUILD_CHEST_FETCH_*` / `COMBAT_AUTO_SWITCH_*` / `MISC_BREW_AUTO` 等一批数值与开关。

## 口径

- 「手册里有对应开关」= 面板里**有控件、能改、能存**（不是只读展示）。
- 已收录的 531/540 项**不重复**出现在新板块——避免同一项两处出现。
- 9 个 `*_MIGRATED` 一次性迁移标记**不是**用户开关，列出（跳过，不进面板）。
- 新板块 = 一个**小类页**「其余开关（自动）」挂在 `系统与杂项` 大类下；页内按 toml 分节（`misc` / `memory` / `combat` …）加小标题分组，逐项给控件：
  - bool → 开/关切换；int/double → 数值输入（沿用 `setInt`/`setDouble`）；string → 文本输入；string-list → 逗号/顿号分隔文本输入（沿用 `setStringList`）。
  - 标签取该配置的翻译键 `config.promaid.<节>.<键>`（缺键的 15 项补齐两树 `lang` 中英文）。
- **零漂移**：新板块由**反射**枚举 `MaidSmartConfig` 的全部 `static ConfigValue` 字段生成，不写死清单；「哪些已收录」由生成物 `assets/promaid/panel_coverage.txt`（脚本扫面板源码得出）提供，护栏保证其不陈旧。以后新加配置项 → 自动落入本页，无需改面板。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 判定 | 量出未收录集合（逐树、含类型），定「自动补全板块」口径 | 本文 §实测缺口/§口径 | ☑ |
| 2 | 生成 | `tools/build/gen_panel_coverage.py`：扫两树 `PromaidConfigScreen.java` 的 `MaidSmartConfig.<字段>` → 写 `assets/promaid/panel_coverage.txt` | 2 资源 | ☑ |
| 3 | 护栏 | `tools/verify/check_panel_coverage.py`：重生成比对（陈旧即 FAIL）+ 报未收录计数 | 1 脚本 | ☑ |
| 4 | 实现 | 两树面板：+`Section.EXTRA("其余开关（自动）")`、dispatch case、`extraRows()`（反射枚举 + 读覆盖清单 + 按类型出控件 + 按节分组） | 2 文件 | ☑ |
| 5 | 实现 | 两树 `lang`：给缺键的未收录项补 `config.promaid.<节>.<键>`（中英） | 4 文件 | ☑ |
| 6 | 验证 | 两树编译、四护栏问题数不增、打包自检；面板源码/资源镜像比对 | 构建输出 | ☑ |
| 7 | 留痕 | `rounds/RELEASES.tsv` 两条 1.3.2 行换哈希 + R865 留痕 | 2 行 | ☑ |
| 8 | 清理 | 删临时脚本；提交推送（**不**部署、**不**跑回归、**不**写 changelog） | — | ☑ |
| 9 | 验收 | 交开发者实测（手册 → 系统与杂项 → 其余开关：能看到并改所有 toml 项） | 待开闸 | ☐ |

## 改法

1. 生成物 `assets/promaid/panel_coverage.txt`：每行一个「已被手工面板收录」的字段名（脚本从面板源码提取）。
2. 面板新增小类 `EXTRA`（Group.SYSTEM），`extraRows()`：
   - 反射 `MaidSmartConfig` 的 `static final *ConfigValue` 字段 → `getPath()` 得 `节.键`；
   - 读 `panel_coverage.txt`（classpath `/promaid/panel_coverage.txt`）得已收录字段名集合；
   - 逐字段：已收录 或 `*_MIGRATED` → 跳过；否则按类型出 `BoolRow` / `NumRow` / `TextRow`，标签用翻译键；
   - 按 `节` 分组，前置一段说明 + 每节 `SectionRow` 小标题。
3. 缺翻译键 15 项：两树 `assets/maid_smart/lang/{zh_cn,en_us}.json` 各补 15 条。
4. 护栏：`check_panel_coverage.py` 重生成资源比对 + 计数；资源里出现面板源码没有的字段（错项）即 FAIL。
5. 临时脚本用完即删；不升版本号、不写 changelog。

### 不做

- 不重排/不删已有手工行（531 项原样）。
- 不给 `*_MIGRATED` 出控件（它们不是用户开关）。

## 触及文件

**新增**：两树 `assets/promaid/panel_coverage.txt`（生成物）、`tools/build/gen_panel_coverage.py`、`tools/verify/check_panel_coverage.py`。
**改**：两树 `com/maidsmart/config/PromaidConfigScreen.java`、两树 `assets/maid_smart/lang/{zh_cn,en_us}.json`。

## 验证

| 项 | 结果 |
|---|---|
| 两树编译 | `javac EXITCODE=0` |
| 打包自检 | 两树 `MISSING: none`、`lang json: OK`；Forge 17,574,160 B（`263f3774…1ce2f4`）、Neo 17,613,051 B（`c18656d7…07d479`）；两个 jar 内均含 `assets/promaid/panel_coverage.txt` 与 `config/PromaidConfigScreen.class` |
| 护栏 | `check_preset` FAIL 0 / WARN 2（DIY 170/174）；`check_workflow` FAIL 0 / WARN 3；`check_comment_style` 927 文件 / **2262**（与基线同数，`extraRows` 的 javadoc 一度 9 行被判 MEMBER LONG-BLOCK +2，压回 7 行后归位）；**新护栏 `check_panel_coverage` FAIL 0**（Forge 声明 601 / 已收录 531 / 自动页列出 **61** / 迁移标记 9；Neo 607 / 540 / **58** / 9） |
| 镜像/符号 | 两树 `EXTRA(` / `void extraRows()` / `loadPanelCoverage` / `setListRaw` 计数一致；`panel_coverage.txt` 531 / 540 行 |
| 部署 | **已部署@2026-10-11**（开发者开闸「部署」；`deploy_all.py` 4 个目标全部 `match=True`） |
| 回归 | **已回归@2026-10-11**（`test_server` + `test_maid_load` × 1201 / neoforge1211，四件全 PASS；日志里无与 promaid 相关的 ERROR） |

## 版本与上传

- 版本保持 **1.3.2**；**已部署**（本机 4 实例，`match=True`）+ **已回归**（两加载器各两件全 PASS）；**未**上平台、**未**发 release；同号重打按 WORKFLOW §5.2 留痕。

## 验收

**已验收@2026-10-11**（开发者指示「部署」并写版本更新内容=验收）；版本更新内容已写入桌面
`Promaid文本\v1.3.2 骑乘修整与全部配置开关可见.md`（1.3.2 覆盖 R858~R865）。

## 边界

- 自动板块的控件不带范围校验（反射拿不到 `defineInRange` 的 min/max）——越界值会在下次加载时被原版 `correct()` 钳回。
- string-list 项（数据表类）在自动板块里以「逗号/顿号分隔文本」编辑，够用；精细编辑仍走各自的专用网格页。
- 覆盖清单是生成物，改动面板行后需重跑 `gen_panel_coverage.py`（护栏会提示陈旧）。

## 台账

- 新增一处生成物 + 一道护栏（与 `gen_switch_inventory`/`check_workflow` 同套路）。
