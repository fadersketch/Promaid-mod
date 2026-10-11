# 实测九百〇一 phase③ 中风险清理：内部迁移标记移出用户配置 + 诊断类移出主源码

> 跑道：配置 schema 与源码布局（不改玩法逻辑）。承接 `R900`，按 `DEBT.md` §6 进入 **阶段③**。

## 排班
- —（连续跑批；③ 收口后进 ④，无独立排班项。）

## 需求 / 来源
- `DEBT.md`：阶段③「`*_MIGRATED` 移出用户配置；诊断类（`PlayerWaterLog`、`CompressionBoxCheck`、`MaidStrollCheck`）移出主源码」。
- 开发者「一次性把这些任务都干完」。

## 3a 内部迁移标记移出用户配置
- `MaidSmartConfig` 新增第二份 spec：`INTERNAL` builder + `public static final … INTERNAL_SPEC`；把 10 个 `*_MIGRATED`（build.speedMigrated / build.scheduleRangeMigrated / build.strollSpeedMigrated / build.defaultRepairMigrated / broom.climbMigrated / broom.climb12Migrated / broom.patrolEnableMigrated / broom.airAltMigrated / broom.orbitRadiusMigrated / misc.dangerCampfireMigrated）的定义从主 builder 改挂 `INTERNAL`。
- 两树 `ProMaidMod` 增注册第二份 COMMON 配置：`promaid-internal.toml`（Forge 走 `ModLoadingContext#registerConfig`，Neo 走 `container.registerConfig`）。
- **迁移代码与语义一字未改**（`ProMaidMod.runConfigMigration()` 仍读同一批字段名）；主 `promaid-common.toml` 从此不再出现这 10 项。
- `MaidConfigPreset` 的 `SKIP`（原本只装这批标记）随之清空，`check_preset` 不再报"路径不存在"。
- **如实说明（老档影响）**：老档 `promaid-common.toml` 里那 10 个键不再被读取 → 内部标记回到默认 `false` → 迁移会在下次启动**重跑一次**。这些迁移的判据一律是"值 == 旧默认才改"，所以：没自己调过档的玩家得到的目标值与原先一致（幂等）；少数把某项**故意留在旧默认值**上的玩家，该项会被按新默认搬一次（这正是那些迁移当初的意图：极速→关、档位→x1 等）。未做"从老 toml 回填标记"的兼容层。

## 3b 诊断类移出主源码
- 三个类从编译树移到仓库 `diag_src/<tree>/com/maidsmart/...`（不在 `gen_compile*.py` 的扫描范围，故不参与编译/打包）：`fishing/PlayerWaterLog`、`box/CompressionBoxCheck`、`task/MaidStrollCheck`。
- 移除它们在**生产代码**里的挂载：
  - `ProMaidExtension`：删掉 `EVENT_BUS.register(new ...PlayerWaterLog())` 一行（两树）；
  - `MaidArmyCommand`：删掉 `/maid_smart stroll {check|go|speed}` 与 `box check` 两组诊断子命令及其 4 个处理器方法（`box <女仆>` 的只读报告保留，它只用产品类 `CompressionBoxMaidInv`）。
- 残留的 javadoc `{@link MaidStrollCheck#go}` / `{@code CompressionBoxCheck}` 仅注释，编译不受影响。
- `compile_promaid.txt` / `compile_neo.txt` 已用 `gen_compile.py` / `gen_compile_neo.py` 重生成（461 / 464 sources）。

## 验证
- `check_comment_style` **0 问题 PASS**；`check_catch_swallow` / `check_comment_dup` / `check_dev_voice` / `check_i18n_keys` / `check_panel_coverage` / `check_preset` / `check_reflection` 全部 **FAIL 0**。
- 两树 `compile_promaid.bat` / `compile_neo.bat` → **EXITCODE=0**。
- 未部署、未跑专服回归。

## 验收
- 状态：`未验收`（配置 schema 变化需开发者实机确认老档迁移行为）。

## 边界（没动什么）
- 未改任何玩法逻辑与配置**值**；未动 `patched/`、`store/`、`_tmp_pub/`。
- `PlayerWaterLog` 的注册被移除属**有意**（它只是诊断日志）。
- 未 commit / push。

## 台账
- 阶段③ 完成（3a + 3b）。后续：④ 抽 `MaidWorkBehavior` 基类；⑤ 拆 `PromaidConfigScreen`（DEBT 标注需实机 UI 验收）；⑥ 两树共享源目录（最高风险，架构级）。
