# 实测八百八十 · TD-12②：更正"类查询漏扫 = 类桶未预建"的错误归因

## 需求 / 来源

TD-12 第 ② 条：「三处注释把 `ClassInstanceMultiMap 桶 bug` 当成了类查询漏扫的原因」——实测八百三十九 已用 javap 证伪（`m_13533_`/`getAllOfClass` = `byClass.computeIfAbsent(...)`，类桶**按需补建**；真因是同段写明的**无限 AABB 溢出**）。开发者：「继续，一直做到所有任务结束」。

约定不变：不跑回归、不写 changelog / release 说明、版本号不动（仍 **1.3.2**）、两树镜像、临时脚本用完即删、**不 commit / 不 push**（WORKFLOW §12）。

## 评估

`MaidRoster` 的注释里已写明更正结论（"类桶按需补建，不存在没预建就跳过"）。但**错误归因文本扩散到了 11 个文件**（不止"三处"）：一处详解 + 两处两行详解，其余是 `（同 FarmTillDriver）` 的引用式短注释（`ProMaidExtension` / `BlueprintCatalog` / `PetImmunityGuard` / `DangerEscapeHandler` / `MaidDangerMalusHandler` / `NeutralThreatDriver` / `HomeWorkMovementDriver` / `MaidSlaughterBehavior`）。引用式的源头就是 `FarmTillDriver`，需连源头一起改。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 扫描 | 找出全部"把类桶未预建当漏扫原因"的注释（含 `同 FarmTillDriver` 引用） | 清单（本轮） | ☑ |
| 2 | 实现 | 更正为"真因无限 AABB 溢出，类桶按需补建"（两树；结论不变） | 11 文件 × 2 树 | ☑ |
| 3 | 验证 | 两树编译、打包、五护栏、错误归因残句归零 | 构建输出 | ☑ |
| 4 | 留痕 | `RELEASES.tsv` 第四十一次 + 本文 + `TECH_DEBT` TD-12 记"② 已做" | 两处 | ☑ |
| 5 | 验收 | 交开发者：TD-12 ①②③ 是否分批判清 | 待开闸 | ☐ |

## 改法

**仅改源码注释**（不进 jar 的语义，字节变化只因 javac `LineNumberTable` 随行号移动）：

- `FarmTillDriver`（两树各一处详解）：删掉"未预建类桶 → 空桶 → 跳过 section"的机理段，改为
  「【归因更正·实测八百三十九】…类桶按需补建，不存在"没预建就跳过"；真因是无限 AABB 溢出」；保留"结论不变：走 Entity.class + 有限范围 + instanceof 过滤"。
- `AutoCombatSwitch` / `HomePatrolHandler` / `ScheduleManager`（两树各一处两行详解）：第二句由
  "未预建 EntityMaid 桶的 section 被整段跳过，…扫不到该 section" 改为 "光靠类查询会漏扫该 section，…"。
- 引用式短注释（8 处）：`ClassInstanceMultiMap 桶 bug（同 FarmTillDriver）` → `类查询漏扫（同 FarmTillDriver，真因见 MaidRoster）`；
  `…桶 bug 与 ±∞ 溢出均已绕开` → `类查询漏扫与 ±∞ 溢出均已绕开`；`…桶 bug 已绕开` → `类查询漏扫真因已绕开`。

### 不做

- 不改 `MaidRoster` 的注释（它本来就是**正确**的那一份，是更正的依据）；
- 不改任何代码逻辑（`Entity.class + instanceof` 的写法与阈值原样保留）；
- TD-12 ①（非每 tick `EntitySnapshot` 审计）与 ③（Neo 独有 `MaidShellHoming` 留痕）本轮不做（另记）。

## 触及文件

**代码**（两树各一份，共 22 个文件实例）：`ProMaidExtension`、`build/FarmTillDriver`、`build/BlueprintCatalog`、`combat/AutoCombatSwitch`、`combat/PetImmunityGuard`、`combat/NeutralThreatDriver`、`follow/HomePatrolHandler`、`follow/HomeWorkMovementDriver`、`protect/DangerEscapeHandler`、`protect/MaidDangerMalusHandler`、`schedule/ScheduleManager`、`task/MaidSlaughterBehavior`（按树各自命中）。
**文档 / 台账**：`TECH_DEBT.md`、`rounds/RELEASES.tsv`、`rounds/R880-*.md`（新）。

## 验证

| 项 | 结果 |
|---|---|
| 两树编译 | `javac EXITCODE=0`（两树各一次） |
| 残句归零 | 两树搜 `未预建 EntityMaid 桶` / `ClassInstanceMultiMap 桶 bug`（注释）= **0** |
| 护栏 | `check_catch_swallow` FAIL 0 / WARN 0；`check_preset` FAIL 0 / WARN 2；`check_workflow` FAIL 0 / WARN 3；`check_comment_style` 931 文件 / **2260 处**（不变）；`check_panel_coverage` FAIL 0 / WARN 0 |
| 打包自检 | 两树 `MISSING: none`、`lang json: OK`；Forge **17,603,854 B**（`e501cbd29e43…b88a73`）、Neo **17,642,994 B**（`bd0b2c1a9c41…cb7b14`） |
| 镜像 | 文本逐字相同；`FarmTillDriver` 详解里方法名按树（Forge `m_13533_` / Neo `getAllOfClass`） |
| 部署 / 回归 | **未部署、未回归**（开发者闸门） |
| commit / push | **未做**（WORKFLOW §12） |

**反证**：改前 `MaidRoster` 注释就写着"AutoCombatSwitch 里那条同名注释归因有误"——本轮正是把那条"有误"的注释（及其 10 处引用）改对，两处说法自此一致。

## 验收

**未验收**（TD-12 的 ①②③ 分批判定待开发者定）。

## 边界

- **不改行为**：只改注释文字；`EntitySnapshot.of` 的调用方式、扫描范围、判定门槛一律不动。
- 字节变化说明：javac 默认带 `LineNumberTable`，注释增删导致后续代码行号位移 → class 字节变化（**不是**逻辑变化）。
- 仍未做：TD-12 ①（非每 tick `EntitySnapshot` 审计）、③（`MaidShellHoming` 留痕）。

## 台账

- **TD-12 部分**：② 已做（11 文件错误归因更正）；① / ③ 未做。
- 下一步候选：**TD-07**（i18n 漂移补齐）、**TD-03③**（`en_us.json` 里 `we/our/us`）、**TD-01**（配置耦合，较大）。
