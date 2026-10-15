# 实测九百三十三 删掉随「弃置」一起失去读取方的配置项 `mine.targetTimeout`

> 跑道：**收尾**。开发者指令（原话）：「是的，配置项也要改。伐木暂时不动」。
> 上一轮：`rounds/R932-mine-remove-abandon-and-dig-up-cap.md`（九百三十二 删弃置 + 补「挖开头顶」）。

## 排班

- **本轮主项**：把九百三十二 删掉「弃置」后**失去读取方**的那条开关 `mine.targetTimeout` 从配置本体 / 配置面板 / 中英译文里一并清掉；
  `wood.targetTimeout`（伐木）**原样保留**。版本号仍 **1.3.2**。
- 步骤：改两树（配置 + 面板 + 两语言文件）→ 重生成 `panel_coverage.txt` 与 `ARCHITECTURE-SWITCHES.md` → 两树 `javac EXITCODE=0` → 全护栏 `FAIL 0` → 打 jar → 部署 4 实例 `match=True`。

## 需求 / 来源

九百三十二 删掉挖矿的「弃置」时，`targetTimeoutTicks()` 被整段删除，而它正是 `mine.targetTimeout` 唯一的读取方 ——
开关键留在 TOML / 面板 / 译文里成了死项（写入后无人读）。开发者确认「配置项也要改」，并明确**伐木那条不动**。

## 定位与改法

`mine.targetTimeout` 的全部落点（两树各一份，逐条删除）：

| 位置 | 处理 |
|---|---|
| `com/maidsmart/config/MaidSmartConfig.java` 字段声明 `MINE_TARGET_TIMEOUT` | 删行 |
| 同文件 `.comment("目标超时（tick，够不到矿超时放弃）").translation("config.promaid.mine.targetTimeout").defineInRange("targetTimeout", 300, 60, 1200)` | 删整块 |
| `com/maidsmart/config/PromaidConfigScreen.java` 面板「目标与节奏」下的 `NumRow`（目标超时（tick）） | 删两行 |
| `assets/maid_smart/lang/zh_cn.json` → `config.promaid.mine.targetTimeout` | 删键 |
| `assets/maid_smart/lang/en_us.json` → `config.promaid.mine.targetTimeout` | 删键 |
| `assets/promaid/panel_coverage.txt` | **重生成**（`python tools/build/gen_panel_coverage.py`） | 
| `ARCHITECTURE-SWITCHES.md` | **重生成**（`python tools/verify/gen_switch_inventory.py --write`） |

**保留**：`WOOD_TARGET_TIMEOUT` / `config.promaid.wood.targetTimeout` / 面板伐木那一行 / `MaidWoodBehavior` 的读取 —— 伐木本轮不动。

## 触及文件

- `promaid_src` / `promaid_src_neo` × `com/maidsmart/config/MaidSmartConfig.java`、`config/PromaidConfigScreen.java`、
  `assets/maid_smart/lang/{zh_cn,en_us}.json`
- `promaid_src` / `promaid_src_neo` × `assets/promaid/panel_coverage.txt`（重生成：525→524 / 534→533）
- `ARCHITECTURE-SWITCHES.md`（重生成：1364 行，`mine.targetTimeout` 消失、`wood.targetTimeout` 保留）
- `rounds/RELEASES.tsv`（两条 1.3.2 `workspace` 行 sha256 + 第七十二次同号重打）
- `rounds/R933-*.md`（本文件）

## 验证

- **残留自查**：两树全文搜 `MINE_TARGET_TIMEOUT|config.promaid.mine.targetTimeout` → **0 命中**；`targetTimeout` 只剩伐木侧（`WOOD_TARGET_TIMEOUT` / `wood.targetTimeout` / `MaidWoodBehavior:609`）。
- **两树编译**：`compile_promaid.bat` → `EXITCODE=0`；`compile_neo.bat` → `EXITCODE=0`。
- **全护栏**：11 个护栏全部 **FAIL 0**（`check_preset` DIY 基线仍 **172/176** —— 数值项本来就不计入 DIY 关闭条数；
  `check_panel_coverage` / `check_workflow` 的重生成比对均通过；`check_workflow` 仍 3 条历史 WARN）。
- **两个 jar**：forge **17584549** 字节（sha256 `AEFDFC0C…BF1FB1`）；neoforge **17620918** 字节（`5C1D8B68…AC6803`）。
- **装机**：`deploy_all.py` → 4 个本机实例全部 **`match=True`**；旧包备份在 `patched/backup_old/`。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 `workspace` 行 sha256 更新为新字节，并追加「第七十二次同号重打」；
  九百三十二 的旧字节 `FFE7DB26…28ACF7`（forge）/ `E3F5BF5E…598D31`（neo）作废。
- **未跑实机回归**。

## 验收

- 状态：**未验收**（已部署：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
- **怎么看**：打开配置面板「挖矿 → 目标与节奏」，应当**没有**「目标超时（tick）」这一行；
  TOML 里不应再有 `[mine]` 节下的 `targetTimeout`；伐木那一节（`[wood]`）的 `targetTimeout` **照旧在**。
  旧的 TOML 里残留的 `mine.targetTimeout` 会被 Forge/Neo 的配置解析忽略（键不再声明），无需迁移。

## 边界（没动什么）

- **只删挖矿那条**：`wood.targetTimeout` 与 `MaidWoodBehavior` 的读取**一字未动**（开发者明确「伐木暂时不动」）。
- **不新增任何开关**；DIY 关闭条数基线仍 **172/176**（数值项不计入该基线，R933 无需改 `check_preset`）。
- 只改配置 / 面板 / 译文与两份生成物（`panel_coverage.txt`、`ARCHITECTURE-SWITCHES.md`），**无任何玩法行为变化**。
- 版本号仍 **1.3.2**。
- 临时脚本与日志（`tools/_r933_*.py`、`_r933_*.log`）**已删**；仓库根目录既有的 `_*` 文件与预置目录未动。

## 台账

- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第七十二次同号重打。
- `DEBT.md`：不新增条目。
