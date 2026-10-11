# 实测八百七十四 · TD-14 清尾：静默吞异常归零、棘轮基线归零

## 需求 / 来源

承接 R871（棘轮 + 骑乘/载具族）、R872（≥20 档 + Diag 降噪）、R873（10..19 档），开发者：「继续」→ 清尾。

约定不变：不跑回归、不写 changelog / release 说明、版本号不动（仍 **1.3.2**）、两树逐字镜像、临时脚本用完即删、**不 commit / 不 push**（WORKFLOW §12）。

## 评估

- **跑道**：A（工程收敛，非功能轮）——不改控制流、不改玩家可见行为。
- **本片**：把剩余 **>0 档全烧**（上一轮后剩下的都是 1~9 处的小文件）——**487 个文件实例，1398 处**（src 693 / neo 705）。
- **结果**：两树"未留痕吞异常" **693 / 705 → 0 / 0**。累计四轮共烧 **2579 / 2582** 处（1881/1889 → 0/0）。
- **一致性实证**：两树 `Diag.swallow(` 出现数 = **1881 / 1889**，与"改前的静默吞异常数"**逐字相等**——说明每个原有站点恰好被插了一句留痕，没有漏、没有多。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 实现 | 全量烧 >0 档（保留 catch、只插留痕；两树） | 487 文件实例 | ☑ |
| 2 | 护栏 | `check_catch_swallow.py` 基线 693/705 → **0/0**（此后新代码不许再裸吞） | 1 文件 | ☑ |
| 3 | 验证 | 两树编译、打包、五护栏、`Diag.swallow` 计数核对 | 构建输出 | ☑ |
| 4 | 留痕 | `RELEASES.tsv` 第三十六次 + 本文 + `TECH_DEBT` TD-14 收口 + 新增 TD-18 | 两处 | ☑ |
| 5 | 验收 | 交开发者：TD-14 是否判"已清"、下一步转 TD-15 / TD-13 | 待开闸 | ☐ |

## 改法

同前三轮口径，**无新增类、无 Diag 改动**：`catch (Throwable|Exception ignored) {` → 体首插
`com.maidsmart.diag.Diag.swallow("<类名>", ignored);`；`catch` 子句保留。基线下调到 **0 / 0**。

### 不做

- **不动 `catch (Throwable t)` / `catch (Exception e)`**（有具名变量，未必静默，可能已有处理）——另记 **TD-18**，需逐个看"体里到底有没有处理"。
- 不动反射本身（TD-15）、不动文案 / 开关 / 版本号。

## 触及文件

**代码**（两树各一份）：**487 个文件实例**（含前四轮之外的所有剩余吞异常站点；其中 `combat/*`、`task/*`、`build/*`、`client/*` 等）。
**护栏 / 文档**：`tools/verify/check_catch_swallow.py`、`TECH_DEBT.md`、`rounds/RELEASES.tsv`、`rounds/R874-*.md`（新）。

## 验证

| 项 | 结果 |
|---|---|
| 两树编译 | `javac EXITCODE=0` |
| 护栏（棘轮） | `check_catch_swallow` FAIL 0 / WARN 0 —— 未留痕吞异常 src **0** / neo **0**（= 基线；本片 -693/-705） |
| 一致性实证 | `Diag.swallow(` 计数 src **1881** / neo **1889** = 改前静默吞异常数（1:1 无漏无多）；`catch (Throwable t)` 243/247、`catch (Exception e)` 100/100（未动，另记 TD-18） |
| 护栏（既有） | `check_preset` FAIL 0 / WARN 2；`check_workflow` FAIL 0 / WARN 3；`check_comment_style` 931 文件 / **2262 处**（不变）；`check_panel_coverage` FAIL 0 / WARN 0 |
| 打包自检 | 两树 `MISSING: none`、`lang json: OK`；Forge **17,601,390 B**（`d0a695d9ee7e…8673e1`）、Neo **17,640,500 B**（`f77b15891251…fa1d93`）；桌面快照刷新一致 |
| 镜像 | 插入文本逐字相同（标签 = 类简单名） |
| 部署 | **未部署**（开发者闸门） |
| 回归 | **未跑**（开发者闸门） |
| commit / push | **未做**（WORKFLOW §12） |

**反证**：在任一文件写一句裸的 `} catch (Throwable ignored) {` → 计数 1 > 基线 0 → `check_catch_swallow` FAIL（棘轮生效）；把它改成 `catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("X", ignored);` → 负向前瞻认作已留痕 → 计数仍 0、PASS。

## 验收

**未验收**（TD-14 是否判"已清"、下一步方向待开发者定）。

## 边界

- **不改控制流 / 游戏行为**：全程只是"空体 + 一条留痕"，`catch` 仍在、仍继续。
- 日志量：每站点只记一次；探测不在场（可选模组没装）降为 DEBUG，不刷屏。
- 仍未动：`catch (Throwable t)` / `catch (Exception e)`（TD-18）、反射本身（TD-15）、文案 / 开关 / 版本号。

## 台账

- **TD-14 收口**：静默吞异常（`catch (... ignored)`）两树清零，棘轮基线归零 = 此后新代码不许再裸吞。③"读不到 = 正常值改三态"属更深改动，随 TD-15/专项另议。
- 新增 **TD-18**：非 `ignored` 的具名泛捕获（`catch (Throwable t)` 两树 490、`catch (Exception e)` 200）里可能仍有"吞掉并返回某正常值"（WORKFLOW §2.4 反模式）——需逐处看体里有没有处理，再决定是否推广 `Diag`。
