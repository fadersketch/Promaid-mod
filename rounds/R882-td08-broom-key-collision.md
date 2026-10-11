# 实测八百八十二 · TD-08：巡逻总开关撞键拆开 + 一次性迁移

## 需求 / 来源

TD-08：`COMBAT_BROOM_ENABLE` 与 `COMBAT_PATROL_ENABLE` **撞同一个 TOML 路径 `broom.enable`**（后定义者覆盖前者，配置面板与 TOML 可能不同步）。`check_preset` 一直报 `WARN 配置项路径重复定义：broom.enable`。开发者：「剩下的你逐一修复。我们必须要根除 issue37 和 38 中的问题」。

约定不变：不跑回归、不写 changelog / release 说明、版本号不动（仍 **1.3.2**）、两树镜像、临时脚本用完即删、**不 commit / 不 push**（WORKFLOW §12）。

## 评估

`COMBAT_PATROL_ENABLE` 的 `.translation()` key 是 `config.promaid.patrol.enable`，但 `define()` 的**键**写的是 `enable`，而它又在 `push("broom")` 段里 → TOML 路径 `broom.enable`，与扫帚总开关 `COMBAT_BROOM_ENABLE`（同段、同 `define("enable")`）**完全一样**。Forge/NeoForge 对同路径的两个 `ConfigValue` 是**同一个底层值**：面板上两个控件其实绑在一个值上，改一个另一个跟着变；后定义的注释还会盖掉前一个。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 排查 | 确认撞键的成因与影响面（同段同 key；值共享） | 结论 | ☑ |
| 2 | 实现 | 巡逻总开关 `define("enable")` → `define("patrolEnable")`（两树） | 2 文件 | ☑ |
| 3 | 迁移 | 新增一次性标记 `broom.patrolEnableMigrated`；老档把撞键值原样搬到巡逻侧 | 2×（配置 + 迁移代码） | ☑ |
| 4 | 配套 | `MaidConfigPreset.SKIP` +2 树；两树 lang +1 键；`check_preset` 基线 173/177 → 174/178 | 6 文件 | ☑ |
| 5 | 验证 | 两树编译、打包、五护栏、`check_preset` 撞键 WARN 归零 | 构建输出 | ☑ |
| 6 | 验收 | 交开发者：迁移口径是否认可 | 待开闸 | ☐ |

## 改法

1. `MaidSmartConfig`（两树）：`COMBAT_PATROL_ENABLE` 的 `.define("enable", true)` → `.define("patrolEnable", true)` → 路径变 `broom.patrolEnable`，与扫帚 `broom.enable` 不再撞。
2. 新增配置项 `PATROL_ENABLE_MIGRATED`（`broom.patrolEnableMigrated`，默认 false）+ `.comment()`/`.translation("config.promaid.patrol.enableMigrated")`。
3. `MaidConfigPreset.SKIP`（两树）：加 `"broom.patrolEnableMigrated"`（迁移标记不进 DIY 判定）。
4. `ProMaidMod.runConfigMigration()`（两树）：标记未置时，读 `COMBAT_BROOM_ENABLE`（= 撞键里那个共享值）→ 写进 `COMBAT_PATROL_ENABLE`（新路径）→ 置标记；**读失败不置标记**（下次启动再迁）。
5. 两树 lang `zh_cn`/`en_us` 各 +1 键 `config.promaid.patrol.enableMigrated`。

### 不做

- 不把整块巡逻配置搬到独立的 `push("patrol")` 段（那要改 `broom.clearance`/`broom.maxRadius` 两条已发布路径，且没有撞键问题）——**只拆真正撞的那一条**；
- 不改任何默认值 / 行为 / 版本号。

## 触及文件

**代码**（两树各一份）：`config/MaidSmartConfig.java`、`config/MaidConfigPreset.java`、`ProMaidMod.java`。
**文案**：两树 `assets/maid_smart/lang/{zh_cn,en_us}.json`。
**工具 / 文档**：`tools/verify/check_preset.py`（基线）、`ARCHITECTURE-SWITCHES.md`（重生成）、`TECH_DEBT.md`、`rounds/RELEASES.tsv`、`rounds/R882-*.md`（新）。

## 验证

| 项 | 结果 |
|---|---|
| 两树编译 | `javac EXITCODE=0` |
| 撞键消除 | `check_preset` **WARN 2 → 0**（"配置项路径重复定义：broom.enable" 消失） |
| 暴露的隐藏项 | 拆开后 patrol 总开关独立计数 → **DIY 关闭条数 173/177 → 174/178**（说明撞键时它被算漏了）；基线同步更新 |
| 迁移可读 | 老档 `broom.enable` 值原样搬进 `broom.patrolEnable`；读失败时不动标记 |
| 既有护栏 | `check_workflow` FAIL 0 / WARN 3；`check_catch_swallow` 0/0；`check_i18n_keys` 0/0；`check_comment_style` 931 / 2260；`check_panel_coverage` 0/0 |
| 打包自检 | 两树 `MISSING: none`、`lang json: OK`；Forge **17,604,249 B**（`a26b1fafb8ef…67bd10`）、Neo **17,643,358 B**（`cd02d98a0796…883f2c`） |
| 部署 / 回归 | **未部署、未回归**（开发者闸门） |
| commit / push | **未做**（WORKFLOW §12） |

**反证**：把 `en_us.json` 里 `patrol.enableMigrated` 那个 key 删掉 → `check_i18n_keys` 缺数 182 → 183 > 基线 → FAIL；补回 → PASS。撞键同理：把 `patrolEnable` 改回 `enable` → `check_preset` 又报 `broom.enable` 重复。

## 验收

**未验收**（迁移口径与是否接受一次性迁移标记，待开发者定）。

## 边界

- 行为上：默认两边都开，拆键后互不影响（原来也"看起来都开"）；唯一变化是**玩家若把那个共享开关关掉**，迁移会把它同时写进巡逻侧，保持与从前一致。
- 改的是已发布配置的键名，故必须靠迁移标记兜底（本片已做）。
- 仍未做（本轮之后）：TD-15 剩余（ModList 在场判断全铺）、TD-07（184 条译文）、TD-03③、TD-12①、TD-13②。

## 台账

- **TD-08 收口**：路径撞键消除 + 一次性迁移；`check_preset` 撞键 WARN 归零；DIY 基线 173/177 → 174/178。
