# 实测八百七十二 · TD-14 续烧：「>=20 档」热点族 + Diag 降噪

## 需求 / 来源

R871 立起吞异常棘轮并烧了第一片（骑乘/载具探针族）。开发者：「继续」→ 按上一轮排好的顺序继续烧，同时**修掉我上一轮留下的一个噪声隐患**。

约定不变：不跑回归、不写 changelog / release 说明、版本号不动（仍 **1.3.2**）、两树逐字镜像、临时脚本用完即删、**不 commit / 不 push**（WORKFLOW §12）。

## 评估

- **跑道**：A（工程收敛，非功能轮）——不改控制流、不改玩家可见行为（只多一条日志）。
- **上一轮的隐患（本轮先修）**：`Diag.swallow` 一律写 WARN。但这一档里大量是**反射/类加载探测"可选模组没装"**（`ClassNotFoundException` / `NoClassDefFoundError` / `NoSuchMethod*` / `NoSuchField*`）——那属**预期内**失败，全报 WARN 会在烧完之后刷一屏无意义警告。→ 给 `Diag` 加**两档**：探测不在场 → DEBUG；其余 → WARN 附栈（与 `WORKFLOW` §2.4 的"未知 ≠ 不存在"同源）。
- **本片选取口径**：按文件计的**未留痕吞异常数 `>= 20`**（最肥一档）。共 **11 个文件**，src 442 / neo 439 处；都是**每 tick 热点**（刷怪笼火把 / 扫帚驾驶 / 飞行战斗 / 自保 / 飞行跟随 / 空战 / 飞行控制器 / 区块常驻 / 女仆救援）。
- **量**：两树未留痕吞异常 **1469 / 1477 → 1027 / 1038**（新基线）。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 侦察 | 拉全量分布、定"最肥一档"阈值 | 本文 §评估 | ☑ |
| 2 | 设计 | 定 Diag 两档判据（探测不在场 → DEBUG）与选取口径（>=20） | 本文 §改法 | ☑ |
| 3 | 实现 | 两树 `Diag.java` 加 `isProbeAbsence` 两档（逐字节相同） | 2 文件 | ☑ |
| 4 | 实现 | 烧 >=20 档 11 个文件（保留 catch、只插留痕；两树） | 22 文件 | ☑ |
| 5 | 护栏 | `check_catch_swallow.py` 基线 1469/1477 → **1027/1038** | 1 文件 | ☑ |
| 6 | 验证 | 两树编译、打包自检、五护栏、基线回落核对 | 构建输出 | ☑ |
| 7 | 留痕 | `RELEASES.tsv` 第三十四次 + 本文 §验证 + `TECH_DEBT` TD-14 状态更新 | 两处 | ☑ |
| 8 | 验收 | 交开发者：下一档口径（建议继续 `>=10` 档：38 文件 / 776 处） | 待开闸 | ☐ |

## 改法

1. **`Diag` 降噪**（两树逐字节相同）：`swallow` 里先 `SEEN.add(site)` 去重，再分档——
   `isProbeAbsence(t)`（`ClassNotFoundException` / `NoClassDefFoundError` / `NoSuchMethodException` / `NoSuchFieldException`）
   → `LOGGER.debug`；否则 `LOGGER.warn(..., t)`。新增一个私有 `isProbeAbsence`（只用 `java.lang` 里的类型，无需 import）。
2. **烧 >=20 档**：`SILENT` 正则整体替换（`catch (Throwable|Exception ignored) {` → 体首插 `com.maidsmart.diag.Diag.swallow("<类名>", ignored);`，
   带负向前瞻跳过已留痕的）。**catch 子句保留**，语义不变。11 个文件：`MaidSpawnerTorchBehavior` / `MaidBroomDrive` /
   `MaidFlightCombatBehavior` / `SelfPreservationBehavior` / `PromaidConfigScreen` / `MaidFlightKit` / `MaidAidOwnerBehavior` /
   `MaidFreeFlightController` / `MaidChunkLoadManager` / `MaidFlightFollowBehavior` / `MaidAirCombat`。
3. **基线**：`check_catch_swallow.py` 的 `BASELINE` 下调到 1027 / 1038（棘轮只减不增）。

### 不做

- **不一轮清完**（余 1027/1038 处）；下一档 `>=10`（38 文件 / 776 处）留待后续。
- **不动 `catch (Throwable t)`（490 处）**：未必静默，指标不含。
- **不动反射本身**（TD-15）；本轮只加留痕 + 降噪。
- 不新增 / 不改任何玩家可见开关与文案。

## 触及文件

**代码**（两树各一份）：`com/maidsmart/diag/Diag.java` + 上面 11 个文件。
**护栏 / 文档**：`tools/verify/check_catch_swallow.py`、`TECH_DEBT.md`、`rounds/RELEASES.tsv`、`rounds/R872-*.md`（新）。

## 验证

| 项 | 结果 |
|---|---|
| 两树编译 | `javac EXITCODE=0` |
| 护栏（棘轮） | `check_catch_swallow` FAIL 0 / WARN 0 —— 未留痕吞异常 src **1027** / neo **1038**（= 新基线；本片 -442/-439） |
| 护栏（既有） | `check_preset` FAIL 0 / WARN 2；`check_workflow` FAIL 0 / WARN 3；`check_comment_style` 931 文件 / **2262 处**（不变）；`check_panel_coverage` FAIL 0 / WARN 0 |
| 打包自检 | 两树 `MISSING: none`、`lang json: OK`；Forge **17,583,673 B**（`5eae0247e9a1…e30beb`）、Neo **17,622,422 B**（`db3e9c300f57…f1244d`）；桌面快照刷新一致 |
| 镜像 | `Diag.java` 两树 `fc /b` 逐字节相同；插入文本逐字相同（标签 = 类简单名） |
| 部署 | **未部署**（开发者闸门） |
| 回归 | **未跑**（开发者闸门） |
| commit / push | **未做**（WORKFLOW §12） |

**反证**：删掉任一已留痕站点的 `Diag.swallow(...)` → 计数 > 基线 → FAIL；把 `isProbeAbsence` 去掉（回到一律 WARN）→ 编译通过但"可选模组没装"会以 WARN 出现（本轮刻意避免的噪声）。

## 验收

**未验收**（下一档口径待开发者定）。

## 边界

- **不改控制流 / 游戏行为**：仍只是"空体 + 一条留痕"，catch 仍在、仍继续。
- 日志量：每站点只记一次；且**探测不在场**这一类降为 DEBUG（默认不输出），避免刷屏。
- 不动 TD-15、不动 `catch (Throwable t)`、不动文案 / 开关 / 版本号。

## 台账

- TD-14 状态保持**部分**，进度推进：已烧 412 + 442/439 处，余 1027/1038。
- 下一档候选：`>=10` 档（38 文件，src 776 / neo 772）——`GunCompat`(19) / `IndexStoneService`(17) / `MaidFlightRangedTask`(17/16) /
  `MaidTridentSpinBehavior`(15) / `MaidCookBehavior`(14) / `BlueprintFileIo`(13) / `MaidBroomBehavior`(13) / `MaidRiptideBoost`(13) /
  `MaidSpellCompat`(13) / `AutoCombatPools`(11/13) / `BombItems`(12) / `MaidSupportPolicy`(12) / `MaidBroomKit`(12) /
  `MaidSelfPropelledWings`(12) / `ProMaidExtension`(12) … 等。
