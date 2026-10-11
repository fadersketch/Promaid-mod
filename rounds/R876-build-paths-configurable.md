# 实测八百七十六 · TD-13（上）：构建 classpath 绝对路径改可配置

## 需求 / 来源

承接 R870（TD-13 登记；`BUILD.md` 已出）。TD-13：「构建不可外部复现：没有 Gradle」——分两步，本片做**第①步路径可配置**（②自包含 Gradle 仍未做）。开发者：「继续，一直做到所有任务结束」。

约定不变：不跑回归、不写 changelog / release 说明、版本号不动（仍 **1.3.2**）、临时脚本用完即删、**不 commit / 不 push**（WORKFLOW §12）。

## 评估

- **跑道**：A（工程收敛，非功能轮）——**只改构建工具 `tools/build/*.py`，不碰任何 Java 源码**，因此不影响产物字节。
- **痛点**：`compile_addon.txt` 的 classpath 与 `gen_compile_neo.py` 里写死 `D:/.minecraft/...` 本机绝对路径（另有 workspace 绝对路径 `C:/Users/Sketch/.zcode/workspace/default/promaid-mod/...`）→ 别人 clone 下来编译不了。
- **做法**：生成脚本按环境变量替换前缀，默认值 = 本机原值 → **不设变量时输出逐字节不变**（已验证）。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 实现 | `gen_compile.py`：classpath 前缀按 `PROMAID_LIB` / `PROMAID_MC_HOME` + 仓库真实路径替换；缺失条目打一行 WARN | 1 文件 | ☑ |
| 2 | 实现 | `gen_compile_neo.py`：同法（`LIB` / `MC_HOME` / `VERSIONS`）；TLM 用 `<MC 根>\versions\...` | 1 文件 | ☑ |
| 3 | 文档 | `BUILD.md` 新增「一.1 换机器：改两个环境变量」+ 改写「七、为什么没有 gradle」 | 1 文件 | ☑ |
| 4 | 验证 | 两树重生成 argfile / 编译 / 打包；核对 jar 哈希与 R875 一致（工具改动不影响产物）；五护栏 | 构建输出 | ☑ |
| 5 | 留痕 | 本文 + `TECH_DEBT` TD-13 记为「路径可配置已做、Gradle 未做」 | 两处 | ☑ |
| 6 | 验收 | 交开发者：TD-13 是否算阶段性清；②是否要 Gradle | 待开闸 | ☐ |

## 改法

1. `tools/build/gen_compile.py`（读 `compile_addon.txt` 得到 classpath 后）：
   - `PROMAID_MC_HOME`（默认 `D:/.minecraft`）、`PROMAID_LIB`（默认 `<MC 根>/libraries`）；
   - 依次替换 `D:/.minecraft/libraries` → `PROMAID_LIB`、`D:/.minecraft/versions` → `<MC 根>/versions`、
     `C:/Users/Sketch/.zcode/workspace/default/promaid-mod` → 脚本实际所在仓库根（`BASE`，正斜杠）；
   - 若替换后与原来不同（或 `PROMAID_LIB` 被设过），打印一行 `classpath roots: ...`；
   - classpath 里不存在的条目打一行 `WARN classpath 有 N 项不存在 ...`（javac 忽略缺失项，不影响编译）。
2. `tools/build/gen_compile_neo.py`：同法，`LIB`/`MC_HOME`/`VERSIONS` 三个变量；TLM jar 与 libIPN 走
   `<MC 根>\versions\...\mods`。
3. `BUILD.md`：前置一节加「一.1」讲清两个环境变量与"不设则逐字节不变"；第七节改写成"路径已可配置、Gradle 未做"。

### 不做

- **不动 `compile_addon.txt`**（里面有 1 条历史死项 `…47.4.21/mods/libIPN….jar`，本机从来没有此版本目录；javac 忽略它，不影响编译。留作 TD-13 登记，不顺手改）。
- 不做 Gradle（②，另议）；不改任何 Java 源码 / 开关 / 版本号。

## 触及文件

**工具 / 文档**：`tools/build/gen_compile.py`、`tools/build/gen_compile_neo.py`、`BUILD.md`、`TECH_DEBT.md`、`rounds/R876-*.md`（新）。
**产物**：无（Java 源码未动）。

## 验证

| 项 | 结果 |
|---|---|
| 重生成 argfile | `gen_compile.py` 两个 argfile 464 / 0 sources（与改前一致）；`gen_compile_neo.py` 467 sources |
| 默认路径下输出不变 | 不设环境变量 → 无 `classpath roots:` 打印；`compile_promaid.txt` 内容与改前一致 |
| 两树编译 | `javac EXITCODE=0`（两树） |
| 打包自检 | 两树 `MISSING: none`、`lang json: OK`；Forge **17,603,138 B**（`5cd469c6f508…c8ae30`）、Neo **17,642,293 B**（`edfa1f0272ed…f5769b`）—— **与 R875 逐字节相同**（工具改动不进产物） |
| 护栏（五） | `check_catch_swallow` 0/0；`check_preset` FAIL 0/WARN 2；`check_workflow` FAIL 0/WARN 3；`check_comment_style` 931/2262；`check_panel_coverage` 0/0 |
| RELEASES | **不动**（产物字节未变，R875 那行仍准） |
| 部署 / 回归 | **未部署、未回归**（开发者闸门） |
| commit / push | **未做**（WORKFLOW §12） |

**反证（可移植性）**：`set PROMAID_LIB=X:\other\libraries` 后跑 `gen_compile.py`，输出应打印 `classpath roots: LIB=X:/other/libraries ...` 且 `compile_promaid.txt` 的 classpath 首项随之改变 —— 前缀确实可换（默认不设则不变）。

## 验收

**未验收**（TD-13 阶段划分与②是否做 Gradle 待开发者定）。

## 边界

- **不改行为 / 不改产物**：只动生成 argfile 的脚本；`javac` 结果与 jar 字节不变。
- 环境变量缺省 = 本机原值，故现有开发者工作流**零感知**（命令不变、输出不变）。
- 仍未做：自包含 Gradle（TD-13 ②）、反射收敛（TD-15）、仅 `canUse` 检开关的 19 个行为（TD-10）、文案（TD-09/TD-02）。

## 台账

- **TD-13 阶段①已做**（路径可配置）；②「自包含 Gradle（ForgeGradle / NeoGradle，依赖走仓库内相对路径 + 官方仓库）」**仍未做**，需较大工程与开发者决策。
- 下一步候选：**TD-15**（反射收敛）、**TD-10**（开关仅 `canUse` 生效的 19 个行为）。
