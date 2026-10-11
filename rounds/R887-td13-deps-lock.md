# 实测八百八十七 · TD-13：依赖锁 —— 让外部贡献者能取到「逐哈希一致」的编译工具链

## 需求 / 来源

TD-13：「构建不可外部复现：没有 Gradle」——issue #38 第 1 条。台账剩下的那一半是「再做自包含 Gradle（ForgeGradle / NeoGradle + 官方仓库，TLM 走依赖坐标或 `libs/`）」。开发者：「剩下的你逐一修复。我们必须要根除 issue37 和 38 中的问题」。

约定不变：不跑回归、不写 changelog / release 说明、版本号不动（仍 **1.3.2**）、两树镜像、临时脚本用完即删、**不 commit / 不 push**（WORKFLOW §12）。

## 评估（为什么这一轮不做"真 Gradle"，而做等价可复现）

先探了环境与依赖现实：

1. **本机没有 Gradle，且外网不通**（`maven.minecraftforge.net` 握手超时）——ForgeGradle / NeoGradle 与 MC 制品都下不下来；写了 `build.gradle` 也**无法验证**，与本仓库「验证过才留痕」的纪律冲突。
2. **工具链里有不可从仓库取的第三方 jar**：车万女仆 TLM（`libs_forge/original_tlm.jar`、`libs_neo/touhoulittlemaid-*.jar`）与 libIPN——Gradle 方案也得靠 `libs/` 自备，**"完全自包含"本就不成立**。
3. **本仓库是"两树镜像 +自定义 javac 管道"**：套 Gradle 要另建两套 sourceSet、动的是工程骨架而非文案，属于开发者级的取舍（与 TD-17 的"不拆/不换/不重写"立场相关）。

所以本轮做**等价可复现**里最实的一步：**把"用哪个工具链"钉死并可离线核对**——外部贡献者据此能拿到与记录那次构建**逐哈希一致**的一套 jar。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 评估 | 探环境（Gradle / 网络）+ 判"真 Gradle 能不能做、能不能验" | 结论（本轮） | ☑ |
| 2 | 实现 | 实测生成 `tools/build/deps.lock`：两树每个编译 classpath jar 的「根相对路径 + sha256 + 字节数 + maven/local」 | 1 文件（98 行） | ☑ |
| 3 | 实现 | `tools/build/check_deps.py`：离线核对（不符 FAIL、缺失 WARN）+ `--plan` 打印各 maven 条目的仓库 URL | 1 脚本 | ☑ |
| 4 | 验证 | 本机跑 `check_deps.py`：forge 40 一致 + 1 可选缺失、neo 57 一致，FAIL 0 / WARN 0 | 运行输出 | ☑ |
| 5 | 留痕 | `BUILD.md` §一.2 新增「依赖锁」、§七 更新；`TECH_DEBT` TD-13 状态；本文 | 三处 | ☑ |
| 6 | 验收 | 交开发者：是否接受"锁 + 校验器"作为等价方案、真 Gradle 是否仍要做 | 待开闸 | ☐ |

## 改法

- `deps.lock` 的路径是**根相对**的（`LIB/…`＝`PROMAID_LIB` 或 `<MC_HOME>/libraries`、`MC/…`＝`PROMAID_MC_HOME`、`REPO/…`＝仓库根），因此不绑本机盘符；`kind` 列区分 `maven`（可取）与 `local`（TLM / libIPN，须自备）。锁由 `gen_compile*.py` 的 classpath 实测产出（含 sha256 与字节数）。
- `check_deps.py`：
  - 默认**离线核对**：逐条比对本机文件的 sha256；不符 → FAIL，缺失 → WARN（javac 容忍 classpath 缺项），锁里即 `MISSING` 的条目算"可选缺失"。
  - `--plan`：对 `maven` 条目按前缀给出仓库根 URL（Forge 官方 / NeoForge 官方 / Minecraft 库 / Maven Central），对 `local` 条目注明"需自备"。
- 校验器**有意不接进打包脚本**：它校验的是"环境"，换一套兼容工具链就该不符，当常驻护栏会误报；按需手跑。

### 不做

- **不写 unverifiable 的 `build.gradle` / `settings.gradle`**（本机离线验不了，放了只会误导）；**不动**源码 / 行为 / 开关 / 版本号；**不改** `compile_addon.txt`（其 classpath 已被 `gen_compile.py` 的 env 前缀替换覆盖）。

## 触及文件

**工具（新）**：`tools/build/deps.lock`、`tools/build/check_deps.py`。
**文档**：`BUILD.md`（§一.2 新、§七 更新）、`TECH_DEBT.md`（TD-13 状态）、`rounds/R887-*.md`（新）。
**未动**：源码两树（本轮不改 jar；故不重打、`RELEASES.tsv` 不加"同号重打"——与更早的纯工具轮 R881 同例）。

## 验证

| 项 | 结果 |
|---|---|
| 依赖锁离线核对 | `check_deps.py`：forge **40 一致 / 0 不符 / 0 缺失 / 1 可选缺失**（libIPN 本机没有）；neo **57 一致 / 0 不符 / 0 缺失**；**FAIL 0 / WARN 0** |
| 获取计划 | `--plan` 对 maven 条目给出仓库 URL；`local` 仅 3 条（`libs_forge/original_tlm.jar`、`libs_neo/touhoulittlemaid-*.jar`、`libIPN`）标注"需自备" |
| 既有护栏 | `check_dev_voice` 0/0；`check_i18n_keys` 0/0；`check_preset` 0/0；`check_workflow` 0/3；`check_catch_swallow` 0/0；`check_comment_style` 931/2260；`check_panel_coverage` 0/0 |
| jar 字节 | **本轮不改源码 → 不重打**；仍为 R886 的 Forge **17,610,091 B**（`501db5cf…e7a085`）/ Neo **17,649,277 B**（`555d87a5…26cd2c`） |
| 部署 / 回归 | **未部署、未回归**（开发者闸门；且本轮无 jar 变化） |
| commit / push | **未做**（WORKFLOW §12） |

**反证**：随便动一个 classpath jar（换成别的版本）→ `check_deps.py` 该条 SHA 不符 → FAIL；`--plan` 会把这条的仓库 URL 打出来，照它重下即恢复"一致"。

## 验收

**未验收**（是否接受"依赖锁 + 校验器"作为 TD-13 的等价可复现方案、以及真 Gradle 是否仍要做，待开发者定）。

## 边界

- **等价而非等价形式**：这解决的是"用哪个工具链"的复现与核对；**不解决**"自动下载/一键构建"（那需要网络与自包含 Gradle）。
- **第三方 jar 自备**：TLM / libIPN 不在任何公共仓库（TLM 1.5.3 为车万女仆官方发行），外部贡献者须自备同版本；`--plan` 会明确标出这几条。
- 仍未做：真自包含 Gradle（理由见 `BUILD.md` §七）。

## 台账

- **TD-13**：路径可配置（②，@八百七十六）已做；本轮补**依赖锁 + 离线校验器**（等价可复现）；真 Gradle 因需联网、第三方 jar 自备、且要动两树工程骨架，**有意未做并写明理由**。
- 本轮为纯工具 / 文档轮：不改 jar、不重打、`RELEASES.tsv` 不加同号重打记录（同 R881 例）。
