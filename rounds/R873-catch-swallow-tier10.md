# 实测八百七十三 · TD-14 续烧：「10..19 档」

## 需求 / 来源

承接 R871（棘轮 + 骑乘/载具族）、R872（≥20 档 11 文件 + Diag 降噪），开发者：「继续」→ 继续烧下一档。

约定不变：不跑回归、不写 changelog / release 说明、版本号不动（仍 **1.3.2**）、两树逐字镜像、临时脚本用完即删、**不 commit / 不 push**（WORKFLOW §12）。

## 评估

- **跑道**：A（工程收敛，非功能轮）——不改控制流、不改玩家可见行为。
- **本片口径**：按文件计的未留痕吞异常数落在 **[10, 19]** 的文件（`>=20` 档已在 R872 烧完）。共 **27 个文件**，src +334 / neo +333 处。
  代表：`GunCompat`(19)、`IndexStoneService`(17)、`MaidFlightRangedTask`(17/16)、`MaidTridentSpinBehavior`(15)、`MaidCookBehavior`(14)、
  `BlueprintFileIo`(13)、`MaidBroomBehavior`(13)、`MaidRiptideBoost`(13)、`MaidSpellCompat`(13)、`MaidBombing`(11)、`MaidPlanting`(11)、
  `MaidBuildBehavior`(11)、`AutoCombatPools`(11/13)、`BombItems`(12)、`MaidSupportPolicy`(12)、`MaidBroomKit`(12)、
  `MaidSelfPropelledWings`(12)、`ProMaidExtension`(12)、`MaidWoodBehavior`(12/11)、`MaidSpellCastCompat`(11)、
  `MaidExtraContainer`(11)、`PatrolNetworking`(11)、`FlightTargeting`(10)、`MaidAutoResurrect`(10)、`MaidGoetyCompat`(10)、
  `MaidFreeFlightKit`(10/11)、`MaidMineBehavior`(11/9)。
- **量**：两树未留痕吞异常 **1027 / 1038 → 693 / 705**（新基线）。累计已烧 **1188/树**（1881/1889 → 693/705）。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 侦察 | 定 [10,19] 档文件集（>20 已烧） | 本文 §评估 | ☑ |
| 2 | 实现 | 烧 27 个文件（保留 catch、只插留痕；两树） | 54 文件实例 | ☑ |
| 3 | 护栏 | `check_catch_swallow.py` 基线 1027/1038 → **693/705** | 1 文件 | ☑ |
| 4 | 验证 | 两树编译、打包、五护栏 | 构建输出 | ☑ |
| 5 | 留痕 | `RELEASES.tsv` 第三十五次 + 本文 + `TECH_DEBT` 更新 | 两处 | ☑ |
| 6 | 验收 | 交开发者：余量（<10 档，src 693 / neo 705）是否一次清完 | 待开闸 | ☐ |

## 改法

同 R871 / R872 的口径，**无新增类、无 Diag 改动**：`catch (Throwable|Exception ignored) {` → 体首插
`com.maidsmart.diag.Diag.swallow("<类名>", ignored);`（负向前瞻跳过已留痕的）；`catch` 子句保留。
基线下调到 693 / 705。

### 不做

- **不烧 <10 档**（余 693/705，多为 1~9 处的小文件）——留待下一轮，由棘轮冻住。
- 不动 `catch (Throwable t)`（490 处）、不动反射本身（TD-15）、不动文案 / 开关 / 版本号。

## 触及文件

**代码**（两树各一份）：上列 27 个文件。
**护栏 / 文档**：`tools/verify/check_catch_swallow.py`、`TECH_DEBT.md`、`rounds/RELEASES.tsv`、`rounds/R873-*.md`（新）。

## 验证

| 项 | 结果 |
|---|---|
| 两树编译 | `javac EXITCODE=0` |
| 护栏（棘轮） | `check_catch_swallow` FAIL 0 / WARN 0 —— 未留痕吞异常 src **693** / neo **705**（= 新基线；本片 -334/-333） |
| 护栏（既有） | `check_preset` FAIL 0 / WARN 2；`check_workflow` FAIL 0 / WARN 3；`check_comment_style` 931 文件 / **2262 处**（不变）；`check_panel_coverage` FAIL 0 / WARN 0 |
| 打包自检 | 两树 `MISSING: none`、`lang json: OK`；Forge **17,586,043 B**（`50efd71b5dc1…a69c44`）、Neo **17,624,783 B**（`5a651520edf7…846d80`）；桌面快照刷新一致 |
| 镜像 | 插入文本逐字相同（标签 = 类简单名）；两树计数按各自实际处数（如 `MaidMineBehavior` src 11 / neo 9） |
| 部署 | **未部署**（开发者闸门） |
| 回归 | **未跑**（开发者闸门） |
| commit / push | **未做**（WORKFLOW §12） |

**反证**：删掉任一已留痕站点的 `Diag.swallow(...)` → 计数 > 基线 → FAIL。

## 验收

**未验收**（余量口径待开发者定）。

## 边界

- **不改控制流 / 游戏行为**：仍只是"空体 + 一条留痕"。
- 日志量：每站点只记一次；探测不在场这一类仍降为 DEBUG。
- 不动 TD-15、不动 `catch (Throwable t)`、不动文案 / 开关 / 版本号。

## 台账

- TD-14 状态保持**部分**：已烧 412 → +442/439 → +334/333 = **1188/1184** 处（src/neo），余 **693/705**。
- 余量均为 <10 档（含 1~9 处的小文件，共 241 / 245 个文件）。下一轮可一次清完（~700/树），届时棘轮基线归零。
