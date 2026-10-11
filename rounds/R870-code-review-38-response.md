# 实测八百七十 · 代码评审 #38 落地：混入插件 + 构建说明 + 债务登记 + 回应草稿

## 需求 / 来源

开发者给出 GitHub issue #38（`fadersketch/Promaid-mod`，「代码方面的一些建议」，作者 gly091020，open）并说「全做」。

「全做」= 做满我上一轮给出的三档：

- **(a)** 拟一份对 #38 的公开回复草稿；
- **(b)** 把 issue 里可执行的点落成 `TECH_DEBT` 条目并排下一轮；
- **(c)** 先做最划算的两项：给 mixin 加 `IMixinConfigPlugin`、补构建说明。

约定不变：不跑回归、不写 changelog / release 说明、版本号不动（仍 **1.3.2**）、两树逐字镜像、临时脚本用完即删、**不 commit / 不 push**（WORKFLOW §12）。

## 评估（先核事实，不靠 issue 的措辞）

- **构建**（第 1 条）：仓库根**无** `build.gradle` / `gradle.properties`；构建链是自定义的 `tools/build/*.py` + `*.bat`，且 `compile_addon.txt` 的 classpath 是本机绝对路径 `D:\.minecraft\libraries\...` → **外部 clone 构建不了。属实。**
- **混入**（第 6 条）：两树各一份 `mixins.promaid.json`、**96 / 95** 个混入类、**无** `IMixinConfigPlugin`。**属实。** 另查了全部 `@Mixin` 目标：绝大多数是 TLM / 原版 / GeckoLib，只有 `MaidTombstoneClickMixin` 指向可选模组的 `EntityTombstone`；而 Mixin 对"目标缺失"本就降级跳过。→ plugin 的收益主要在**"可选择性关闭以二分定位冲突"**，按这个口径实现（**不**做一个恒返回 true 的空壳）。
- **吞异常**（第 4 条）：两树合计 `catch (Throwable ignored)` **3150** + `catch (Exception ignored)` **620**（927 个 java 文件）。**属实，且比 issue 说的"几百个"严重。**
- **反射**（第 5 条）：两树 `getMethod(` **419** / `Class.forName` **174**，而"模组在场判断" `ModList.get()` 只有 **11**。**部分属实**（一半已是正确范式）。
- **分支 / i18n / 手册选型**（第 2 / 3 条 + 结尾「从头开始写」）：属**设计取舍**，与现架构（同仓两树镜像 + 自带手册/配置）冲突 → 记立场，不改。
- **跑道**：**A（工程收敛轮）**——不启新玩法、不动玩家可见行为；无未清 P0/P1 债占用（BD-12 / BD-13 均已清）。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 侦察 | 核 issue 各条事实：构建 / 混入目标 / 吞异常 / 反射计数 | 本文 §评估 | ☑ |
| 2 | 设计 | 定 plugin 口径（选择性关闭，不做空壳）+ 文档/债/草稿范围 | 本文 §改法 | ☑ |
| 3 | 实现 | 两树 `com/maidsmart/PromaidMixinPlugin.java`（逐字节相同）+ 两树 `mixins.promaid.json` 登记 `plugin` | 5 文件 | ☑ |
| 4 | 文档 | `BUILD.md`（现状 / 前置 / 两树命令 / 镜像约定 / 护栏 / 混入开关 / 已知限制→TD-13） | 1 文件 | ☑ |
| 5 | 台账 | `TECH_DEBT.md` +TD-13（构建）/TD-14（吞异常）/TD-15（反射）/TD-16（混入插件，已清）/TD-17（选型立场） | 1 文件 | ☑ |
| 6 | 文档 | `ISSUE38-回应草稿.md`（逐条，第一人称，未发布） | 1 文件 | ☑ |
| 7 | 验证 | 两树编译、打包自检、jar 内查 plugin、四护栏 | 构建输出 | ☑ |
| 8 | 留痕 | `rounds/RELEASES.tsv` 第三十二次同号重打 + 本文 §验证 | 两处 | ☑ |
| 9 | 验收 | 交开发者：issue 回复是否照发、是否采纳 TD-13/14/15 的排期 | 待开闸 | ☐ |

## 改法

1. **新类** `com/maidsmart/PromaidMixinPlugin.java`（两树逐字节相同）：`implements IMixinConfigPlugin`；
   `-Dpromaid.mixin.disabled=all` 关全部混入，或 `=简单名,…` 只关指定的；命中在启动日志打一行 `[promaid]`。
   **默认（不设参数）全部生效、行为与原样一字不差。** 只用 JDK + Mixin 本体（`IMixinConfigPlugin` + `asm-tree`，
   两者都在编译 classpath 上：`mixin-0.8.7` / `asm-tree`），不碰 MC 与本模组配置系统——插件在配置加载**之前**运行；
   日志走标准输出（此时 slf4j 未必就绪）。
2. **两树 `mixins.promaid.json`**：在 `"package"` 后加 `"plugin": "com.maidsmart.PromaidMixinPlugin"`。
   插件类放在 `com.maidsmart`（**不**在混入包 `com.maidsmart.mixin` 内），因此不进混入登记表、也不会被当成混入扫描。
3. **`BUILD.md`**（新）：讲清"为什么没有 gradle"、前置（JDK 17/21、`D:\.minecraft\libraries`、TLM jar）、
   两树三步构建、镜像约定、护栏清单、混入开关用法、已知限制（外部 clone 构建不了 → TD-13）。
4. **`TECH_DEBT.md`**：+TD-13 构建不可复现（部分：`BUILD.md` 已出）/ TD-14 吞异常存量（未清）/
   TD-15 反射未收敛（未清）/ TD-16 混入插件（已清@实测八百七十）/ TD-17 选型三条建议（已确认保留）。
5. **`ISSUE38-回应草稿.md`**（新）：逐条回应，认下第 1/2/4/5/6 条、说明第 3 条与结尾的取舍，列出已做与将做。

### 不做

- **不做 Gradle**：会动整个构建链且本机无法验证（依赖需联网拉取），本轮只出说明（`BUILD.md`）+ 记债 TD-13。
- **不做 TD-14 的棘轮与热点改造**：单开一轮（吞异常 3770 处，需逐类分优先级）。
- **不改任何现有 mixin 的注入逻辑**；不新增 / 不改任何玩家可见行为开关（插件默认行为 = 原样）。

## 触及文件

**代码**（两树各一份）：`com/maidsmart/PromaidMixinPlugin.java`（新）、`mixins.promaid.json`。
**文档 / 台账**：`BUILD.md`（新）、`TECH_DEBT.md`、`ISSUE38-回应草稿.md`（新）、`rounds/R870-code-review-38-response.md`（新）、`rounds/RELEASES.tsv`。

## 验证

| 项 | 结果 |
|---|---|
| 两树编译 | `javac EXITCODE=0`（含新 plugin 类） |
| 打包自检 | 两树 `MISSING: none`、`lang json: OK`；`verify_jar_classes: mixin 包登记 OK — 96 个类全部登记`（插件不在混入包内，不入表） |
| jar 内容实证 | 两个 jar 内均有 `com/maidsmart/PromaidMixinPlugin.class`；两个 jar 内的 `mixins.promaid.json` 均含 `"plugin": "com.maidsmart.PromaidMixinPlugin"` |
| 镜像 | `PromaidMixinPlugin.java` 两树 `fc /b` **逐字节相同**（只用 JDK + Mixin API，无 SRG/Mojang 名） |
| 护栏 | `check_preset` FAIL 0 / WARN 2；`check_workflow` FAIL 0 / WARN 3（三条旧 WARN）；`check_comment_style` **929 文件 / 2262 处**（文件 +2 = 新 plugin；违规数不变，无新增 LONG-BLOCK）；`check_panel_coverage` FAIL 0 / WARN 0 |
| 包字节 | Forge **17,579,719 B**（`b04d264bdca8…62d5c9`）、Neo **17,618,675 B**（`05028b81fca2…d73d0e`）；桌面 `Promaid_本体当前` 快照刷新一致 |
| 部署 | **未部署**（开发者闸门） |
| 回归 | **未跑**（开发者闸门） |
| commit / push | **未做**（WORKFLOW §12） |

## 验收

**未验收**（issue 回复是否照发、TD-13/14/15 的排期，待开发者定）。

## 边界

- plugin 只是"可控闸"：不设 JVM 参数时**全部混入照常生效**，对玩家、对现有行为**零影响**；它不改变任何混入的注入内容。
- 未触碰构建链、未动版本号、未动任何玩家可见文案 / 开关。
- `ISSUE38-回应草稿.md` 是**草稿**（文件头已标注），尚未发布到 GitHub，也未 commit。

## 台账

- 新增 TD-13 / TD-14 / TD-15 / TD-16 / TD-17（见 `TECH_DEBT.md`）。
- **下一轮候选（按可执行性）**：① TD-14 吞异常棘轮 + 热点 log-once（量最大、最匹配 issue 第 4 条）；② TD-15 反射→兼容类收敛；③ TD-13 的绝对路径可配置化（Gradle 更靠后）。
