# 实测八百七十一 · TD-14 第一步：吞异常棘轮护栏 + 骑乘/载具探针一族留痕

## 需求 / 来源

上一轮（R870）把 issue #38 的可执行项记成 `TECH_DEBT` TD-13..TD-17，并写明「下一轮候选：① TD-14 吞异常棘轮 + 热点 log-once」。开发者：「继续」。

本轮 = **TD-14 第一步**：先把闸立起来（只减不增），再把最肥、最热的一族（骑乘 / 载具 / 探针兼容）烧成"留痕"。

约定不变：不跑回归、不写 changelog / release 说明、版本号不动（仍 **1.3.2**）、两树逐字镜像、临时脚本用完即删、**不 commit / 不 push**（WORKFLOW §12）。

## 评估

- **跑道**：A（工程收敛，非功能轮）——不改控制流、不改玩家可见行为（只多一条日志）。
- **量（实测）**：两树"未留痕的静默吞异常"（`catch (Throwable|Exception ignored) {` 且体首非 `Diag.swallow`）= src **1881** / neo **1889**，分布在 279 / 283 个文件。
- **分布（最肥）**：`combat/MaidMountCompat` **264**（第三方载具通解，反射探针密集）→ `RideBindManager` 89/88 → `combat/MaidBroomDrive` 50 → `MaidFlightCombatBehavior` 47/50 → `MaidSpawnerTorchBehavior` 49/50 → `SelfPreservationBehavior` 47 → `PromaidConfigScreen` 46/45 …。
  最大头是**骑乘 / 载具 / 探针兼容**这一族，且都在每 tick（驾驶/坐骑/巡逻）路径上——正是我 TD-14 里写的"热点路径（外部模组探针）"。
- **为什么要有"棘轮"**：3770 处不可能一轮改完；先把"存量只减不增"钉死，后续每轮烧一族，才不会再涨回去。
- **改写口径的坑（本轮踩到并修正）**：把 `catch (...) ignored` 改成留痕，**catch 子句仍在**（只是体里多一句），所以"数 catch 子句"这个指标**不会下降**；正确的指标是"**未留痕**的静默吞异常"。同时指标必须**剔除注释**——我自己 `Diag` 的 javadoc 里写了示例 `catch (Throwable ignored)`，一度被算成 1 处（这也是"字符串/注释别骗护栏"的实例）。
- **可复用**：项目各类各自声明 `LOGGER`（`LoggerFactory`），无统一出口——本轮新增一个极小的 `Diag`。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 侦察 | 量分布（per-file）、确认 `ignored` catch 的 `{` 都在同行、查现有日志范式 | 本文 §评估 | ☑ |
| 2 | 设计 | 定指标（未留痕 + 剔注释）与改写口径（保留 catch、体首加 `Diag.swallow`） | 本文 §改法 | ☑ |
| 3 | 实现 | 两树 `com/maidsmart/diag/Diag.java`（逐字节相同） | 2 文件 | ☑ |
| 4 | 实现 | 改写骑乘/载具探针一族（4 文件 × 2 树，保留控制流） | 8 文件 | ☑ |
| 5 | 护栏 | `tools/verify/check_catch_swallow.py`（棘轮）+ 接进两 `build_promaid*.py` + `WORKFLOW` §11 / `DEV_GUIDE` §6 表 | 5 文件 | ☑ |
| 6 | 验证 | 两树编译、打包自检、五护栏、基线回落核对 | 构建输出 | ☑ |
| 7 | 留痕 | `RELEASES.tsv` 第三十三次 + 本文 §验证 + `TECH_DEBT` TD-14 状态推进 | 两处 | ☑ |
| 8 | 验收 | 交开发者：棘轮口径是否认可、下一族烧哪包（建议 `MaidBroomDrive` / `MaidFlightCombatBehavior` / `SelfPreservationBehavior`） | 待开闸 | ☐ |

## 改法

1. **新类** `com/maidsmart/diag/Diag.java`（两树逐字节相同）：`swallow(String tag, Throwable t)`——
   站点标识 = `tag + " @" + 抛出点栈帧`，同一站点只记一次（不刷屏），写一条 WARN（含抛出点与异常本身）。
   只用 slf4j，不碰 MC。**没有**用 `catch` 吞任何东西（读系统属性/取栈帧都不抛），避免自己变成被批评的那种代码。
2. **改写口径**：`catch (Throwable|Exception ignored) {` → 体首插入 `com.maidsmart.diag.Diag.swallow("<类名>", ignored);`。
   **catch 子句保留**（语义不变，只是"吞掉"变"吞掉并留一条痕"）；用全限定名，不需要 import。
3. **首片**（骑乘 / 载具 / 探针兼容，每树 +412 处）：`combat/MaidMountCompat`(264)、`combat/RideBindManager`(89/88)、
   `combat/MaidRideKit`(31)、`combat/GunnerTetherManager`(28/29)。
4. **新护栏** `tools/verify/check_catch_swallow.py`：正则数**未留痕**静默吞异常（先剔 `/* */` 与 `//` 注释）；
   `> 基线` FAIL、`< 基线` WARN（棘轮只减不增）、`--dump` 打印每文件前 25 名。基线 = 本轮烧完后的 **1469 / 1477**。
   接进两个 `build_promaid*.py`（`--warn`，与既有三护栏并列），并登记进 `WORKFLOW` §11 与 `DEV_GUIDE` §6 的护栏表。

### 不做

- **不一轮清完**（余 1469/1477 处，按包分批）；本轮只烧"最热 + 最肥"的一族，其余由棘轮冻住。
- **不改 `catch (Throwable t)`（490 处，非 `ignored`）**：那些未必是静默（可能已有处理），本轮指标不含它们，留给后续逐处看。
- **不动反射本身**（那是 TD-15）；本轮只给这族"吞异常"加留痕。
- 不新增 / 不改任何玩家可见开关与文案。

## 触及文件

**代码**（两树各一份）：`com/maidsmart/diag/Diag.java`（新）、`combat/MaidMountCompat.java`、`combat/RideBindManager.java`、
`combat/MaidRideKit.java`、`combat/GunnerTetherManager.java`。
**护栏 / 文档**：`tools/verify/check_catch_swallow.py`（新）、`tools/build/build_promaid.py`、`tools/build/build_promaid_neo.py`、
`WORKFLOW.md`、`DEV_GUIDE.md`、`TECH_DEBT.md`、`rounds/RELEASES.tsv`、`rounds/R871-*.md`（新）。

## 验证

| 项 | 结果 |
|---|---|
| 两树编译 | `javac EXITCODE=0`（含 8 个改写文件与 `Diag`） |
| 护栏（新增） | `check_catch_swallow` FAIL 0 / WARN 0 —— 未留痕吞异常 src **1469** / neo **1477**（= 新基线；改前 1881 / 1889，本族 -412/树） |
| 护栏（既有） | `check_preset` FAIL 0 / WARN 2；`check_workflow` FAIL 0 / WARN 3；`check_comment_style` **931 文件 / 2262 处**（文件 +2 = 两个 `Diag.java`；违规数不变）；`check_panel_coverage` FAIL 0 / WARN 0 |
| 打包自检 | 两树 `MISSING: none`、`lang json: OK`、`verify_jar_classes` 混入登记 OK；Forge **17,581,597 B**（`184d8763d57d…b4613a`）、Neo **17,620,492 B**（`6f60ff41d190…03f1a2`）；桌面 `Promaid_本体当前` 快照刷新一致 |
| 镜像 | `Diag.java` 两树 `fc /b` 逐字节相同；改写用的标签 = 类简单名（两树同串），插入文本逐字相同 |
| 部署 | **未部署**（开发者闸门） |
| 回归 | **未跑**（开发者闸门） |
| commit / push | **未做**（WORKFLOW §12） |

**反证**：故意在任意一个已留痕站点把 `Diag.swallow(...)` 那句删掉 → `check_catch_swallow` 计数 +1 且 > 基线 → FAIL（脚本 `> 基线` 分支）；把 `Diag.java` 的 javadoc 改回含 `catch (Throwable ignored)` 示例 → 因脚本先剔注释，计数**不**变（验证"注释不吃计数"）。

## 验收

**未验收**（棘轮口径与下一族选择待开发者定）。

## 边界

- **不改控制流 / 游戏行为**：只是把 `catch (...) ignored` 的空体变成"空体 + 一条留痕"，catch 仍在、仍继续。
- 日志量：每站点**只记一次**（静态 `Set` 去重，键含抛出点栈帧），不会每 tick 刷屏；`Set` 大小有界（≈ 触达的站点数）。
- 不动 TD-15（反射收敛）、不动 `catch (Throwable t)` 那 490 处、不动文案 / 开关 / 版本号。

## 台账

- TD-14 状态推进为**部分**（棘轮 + `Diag` + 首片已落；余 1469/1477 处待逐族烧）。
- 下一族候选（按量）：`combat/MaidBroomDrive`(50)、`combat/MaidFlightCombatBehavior`(47/50)、`combat/MaidSpawnerTorchBehavior`(49/50)、
  `combat/SelfPreservationBehavior`(47)、`config/PromaidConfigScreen`(46/45)、`flight/MaidFreeFlightController`(36)。
