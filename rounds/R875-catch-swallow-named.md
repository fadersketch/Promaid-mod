# 实测八百七十五 · TD-18：具名静默吞异常清尾（棘轮第二把尺）

## 需求 / 来源

承接 R871~R874（TD-14 只数 `catch (... ignored)`，已归零），把 TD-18 点名的**另一族**也收进棘轮：具名捕获（`catch (Throwable t)` / `catch (Exception e)`）里体是 `return <值>;` / 纯注释 / 空的——即 WORKFLOW §2.4 反模式「把异常翻译成正常值」，名字不是 `ignored` 也照样「日志一片空白」。开发者：「继续，一直做到所有任务结束」。

约定不变：不跑回归、不写 changelog / release 说明、版本号不动（仍 **1.3.2**）、两树逐字镜像、临时脚本用完即删、**不 commit / 不 push**（WORKFLOW §12）。

## 评估

- **跑道**：A（工程收敛，非功能轮）——不改控制流、不改玩家可见行为。
- **判据**：具名捕获体**紧跟** `{` 之后就是下面三者之一 → 记为静默：
  ① `return <表达式>;`；② 纯 `//…` 注释；③ 空体 `{}`。（体里有 log / 有其它语句的**不算**——可能真在处理。）
- **实测存量**：具名捕获两树 **2236 / 2248** 处；其中空体 0，裸 `return` 199 / 204，纯注释 3 / 2（早先粗测）。本轮按「紧跟 `{` 即判」的口径重扫并烧：**src 240 处 / 95 文件**、**neo 243 处 / 96 文件**（合计 **483 处 / 191 文件实例**）。差额来自口径放宽——上一版正则要求体正好以 `}` 收尾，本版只要求**首句**是 `return`/注释（体里后续语句照旧保留、控制流不变）。
- **结果**：两树「未留痕吞异常」（A 族 + B 族合并计数）维持 **0 / 0**；B 族单独 **240 / 243 → 0 / 0**。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 护栏 | `check_catch_swallow.py` 加 `SILENT_NAMED`（具名静默）并进同一基线 | 1 文件 | ☑ |
| 2 | 实现 | 具名静默站点点首插 `Diag.swallow("<类名>", <变量>);`（两树） | 191 文件实例 · 483 处 | ☑ |
| 3 | 验证 | 两树编译、打包、五护栏、B 族计数归零核对 | 构建输出 | ☑ |
| 4 | 留痕 | `RELEASES.tsv` 第三十七次 + 本文 + `TECH_DEBT` TD-18 收口 | 两处 | ☑ |
| 5 | 验收 | 交开发者：TD-18 是否判"已清"、下一步转 TD-15 / TD-13 / TD-10 | 待开闸 | ☐ |

## 改法

1. **护栏**（`tools/verify/check_catch_swallow.py`）：新增
   ```python
   SILENT_NAMED = re.compile(
       r'catch\s*\(\s*(?:Throwable|Exception)\s+(?!ignored\b)\w+\s*\)\s*\{\s*'
       r'(?:return\s+[^;{}]*;|//[^\n]*|\})')
   ```
   与原有 `SILENT`（`ignored` 族）**合并计数**，基线仍 **0 / 0**；`--dump` 打印合并后的每文件计数。
2. **源码**（两树逐字镜像，标签 = 类简单名、变量 = 原捕获变量名）：把上述命中站点的 catch 体首插一句
   `com.maidsmart.diag.Diag.swallow("<类名>", <变量>);`，**`catch` 子句、原有 `return` / 后续语句全部保留**。

### 不做

- 不动体里**已有** log / 处理的具名捕获（不是静默，不算债）。
- 不动反射本身（TD-15）、不动文案 / 开关 / 版本号。

## 触及文件

**代码**（两树各一份）：**191 个文件实例 · 483 处**（`combat/*`、`task/*`、`build/*`、`client/*`、`compat/*` 等；最肥的 `MaidBdCompat` +16/+15、`GunnerTetherManager` +23/+26、`MaidToolAutoEquip` +13/+15、`WorkAreaClamp` +10、`MaidChunkLoadManager` +11、`AutoCombatTargeting` +7、`MaidFlightHazardGuard` +6）。
**护栏 / 文档**：`tools/verify/check_catch_swallow.py`、`TECH_DEBT.md`、`rounds/RELEASES.tsv`、`rounds/R875-*.md`（新）。

## 验证

| 项 | 结果 |
|---|---|
| 两树编译 | `javac EXITCODE=0` |
| 护栏（棘轮） | `check_catch_swallow` FAIL 0 / WARN 0 —— 未留痕吞异常 src **0** / neo **0**（其中具名静默 **0 / 0**；本片 -240/-243） |
| 一致性实证 | 具名 `Diag.swallow("…", <非 ignored 变量>)` 计数 src **240** / neo **243** = 本片烧掉的具名静默数（1:1 无漏无多）；`ignored` 族仍 0 增 |
| 护栏（既有） | `check_preset` FAIL 0 / WARN 2；`check_workflow` FAIL 0 / WARN 3；`check_comment_style` 931 文件 / **2262 处**（不变）；`check_panel_coverage` FAIL 0 / WARN 0 |
| 打包自检 | 两树 `MISSING: none`、`lang json: OK`；Forge **17,603,138 B**（`5cd469c6f508…c8ae30`）、Neo **17,642,293 B**（`edfa1f0272ed…f5769b`）；桌面快照刷新一致 |
| 镜像 | 插入文本逐字相同（标签 = 类简单名、变量 = 原捕获变量名） |
| 部署 | **未部署**（开发者闸门） |
| 回归 | **未跑**（开发者闸门） |
| commit / push | **未做**（WORKFLOW §12） |

**反证**：在任一文件写 `try { … } catch (Throwable t) { return false; }` → `SILENT_NAMED` 计 1 > 基线 0 → `check_catch_swallow` FAIL；把它改成 `catch (Throwable t) { com.maidsmart.diag.Diag.swallow("X", t); return false; }` → 首句是留痕、不再命中 → 计数仍 0、PASS。

## 验收

**未验收**（TD-18 是否判"已清"、下一步方向待开发者定）。

## 边界

- **不改控制流 / 游戏行为**：全程只是"体首先插一条留痕"，`catch` 仍在、`return` 与后续语句照旧执行。
- 口径边界：只判**首句**；体里**有** log 或其它语句的具名捕获不在内（可能真在处理，留待需要时逐处看）。
- 日志量：每站点只记一次；探测不在场（可选模组没装）降为 DEBUG，不刷屏。
- 仍未动：反射本身（TD-15）、构建可复现（TD-13）、仅 `canUse` 检开关的 19 个行为（TD-10）、文案 / 开关 / 版本号。

## 台账

- **TD-18 收口**：具名且静默（首句 `return` / 纯注释 / 空）两树清零；棘轮第二把尺并入基线 = 此后新代码不许再出现"具名却静默"。剩余具名捕获（体里有 log / 其它语句）**不判为债**，需要时再按站点逐个评估。
- 下一步候选：**TD-13**（构建路径可配置化）、**TD-15**（反射收敛）、**TD-10**（开关仅在 `canUse` 生效的 19 个行为）。
