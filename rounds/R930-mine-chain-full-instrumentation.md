# 实测九百三十 挖矿链路全程插桩：每一拍走的是哪条出口都留一行日志（只加日志、不改控制流）

> 跑道：**诊断插桩**——开发者要求先把整条挖矿链路每条路径都监听起来，再实测一次。
> 开发者指令（原话）：「你先给整个挖矿链路每一条都加一个日志进行监听。我再进行一次测试。」
> 上一轮：`rounds/R929-aircraft-land-circle-and-mine-silent-giveup.md`（九百二十九 修固定翼降落 + 六条弃置路径留痕）。

## 排班

- **本轮主项**：**纯插桩**。挖矿 tick 的每一条出口、扫描的三个阶段、两条"只弹气泡不写日志"的播报、以及共用的走路驱动，各加一行限频日志。**不改任何控制流、不改任何判据、不改任何开关**；版本号仍 **1.3.2**。
- 判据：实机再现"她站着不动"时，日志里**必须**有一行 `work exit:` 说明她在哪条路上——不允许再出现"二十五秒零行"。
- 步骤：改 Forge → 镜像 Neo（同一份模板 + 名字映射，逐锚点断言唯一）→ 两树 `javac EXITCODE=0` → 全护栏 `FAIL 0` → 打 jar → 部署 4 实例 `match=True`。
- 谁验收：开发者（本轮只部署、不跑回归；`## 验收` 给逐条看日志的方法）。

## 需求 / 来源

上一轮的结论是：卡死时"日志一片空白"，而空白的原因是我们**自己没有任何输出**。九百二十九 补了六条弃置路径，但**符合实机证据的那几条路（"选中的矿挖不动就跳过" / "锁着目标走不到" / 微动但不动）依然不写日志**。所以本轮不再猜根因、先把**每一条出口都变成一行日志**，让下一次实测自己说出答案。

顺便在本轮**排除并更正**了一条我先前的错误怀疑（见 `## 定位与改法 0`）。

## 定位与改法

### 0. 先更正：手里的锤子**不是**卡死的原因（实证）

上一轮我怀疑"主手不是 PickaxeItem → 挖矿链路拒绝工作"。实测证伪：

- 灾变 jar（`[灾变] L_Ender's Cataclysm 1.21.1-3.33.jar`）里 `item.cataclysm.infernal_forge.desc2 = （仅供参考，这其实是一把镐子）`；
- `javap` 类头：`com.github.L_Ender.cataclysm.items.Infernal_forge extends net.minecraft.world.item.PickaxeItem`；
- 会话日志 10:59:14.360（第一条 `重锤门` 探针，**她还没开挖**）就已经是 `主手=cataclysm:infernal_forge`，紧接着 10:59:15.668–10:59:25.914 她一路正常产出。**她拿着这把锤子在挖矿**，工具链完全放行。

### 1. 插桩清单（只加日志）

新增一个共用帮手（`MaidWorkBehavior`）：限频表 `TRACE_AT`（女仆 id → 原因 → 上次打印 tick）+ `TRACE_INTERVAL = 100L`（5 秒/原因/只），一行

```
work exit: maid=<uuid> reason=<出口名> detail=<现场值>
```

**挖矿 tick（`MaidMineBehavior`，15 条出口 + 3 条播报）**：

| 出口 | reason |
|---|---|
| 扫描节流（等 5 拍） | `mine 扫描节流(等5拍)` |
| 分帧扫描进行中 | `mine 分帧扫描进行中(等扫描)` |
| 抬头兜底命中 | `mine 抬头兜底选中` |
| 框里只剩挖不动的矿 | `mine 只剩挖不动的矿` |
| 框空（全量/缓存轮都说话） | `mine 框空`（带 `full=`/`blocked=`） |
| 目标已不是矿/废石 | `mine 目标已不是矿/废石` |
| 主手挖不动（试着换镐） | `mine 主手挖不动(试着换镐)` |
| 朝挡路块推进 | `mine 朝挡路块推进(够不着矿)` |
| **够不着 → 每拍继续推进** | `mine 够不着-继续推进`（带 `dist`/`reach`/`stuck=n/40`） |
| **主手不是镐（原来的静默早退）** | `mine 主手不是镐(暂停挖掘)`（带主手物品 + "背包能不能挖"） |
| 硬度 ≤0 / 不可破坏 | `mine 硬度≤0/不可破坏` |
| 挖掘中（**健康心跳**） | `mine 挖掘中(健康)`（带 `progress`） |
| 挖掉一块 | `mine 挖掉一块` |
| **锁着目标但超时时钟缺失** | `mine 锁着目标但超时时钟缺失`（见下） |
| 全量扫描扫完 | `mine 全量扫描完成`（带 `found=`/`best=`） |
| 气泡「挖不动，先跳过」 | `mine skip-report: …`（**新增 LOGGER**） |
| 气泡「有硬方块挡住」 | `mine blocked-report: …`（**新增 LOGGER**） |
| 气泡「附近矿被挡住」 | `mine blocked-area: …`（**新增 LOGGER**） |

**共用的走路驱动（`MaidWorkBehavior.setWalkTarget`，2 条）**：`walk 沿用同一目标(导航在走)`、`walk 远距离接力(>14格)`。（这条同时被伐木复用。）

> 「锁着目标但超时时钟缺失」是**纯观察**行，不改行为：它验证上一轮我读代码时发现的那条结构性隐患——`purgeStaleMaids`（每 30 秒，`MaidMineBehavior:307`）按实体 ID 清掉 `TARGET_SINCE`，而 `targetPos` 是行为实例字段、**不在清理范围**里；一旦清掉，`if (since != null && …)` 的"够不着超时弃置"对**当前这个目标**就永久失效（`TARGET_SINCE` 只在选中新目标时才写回）。**本轮只把它记出来，不修**（本轮不加行为改动）。

## 触及文件

- `promaid_src` / `promaid_src_neo` × `com/maidsmart/task/MaidWorkBehavior.java`（`TRACE_AT` / `TRACE_INTERVAL` / `traceExit(...)` + 走路两行）
- `…/task/MaidMineBehavior.java`（挖矿 tick 15 条出口 + 3 条播报补 LOGGER）
- `rounds/RELEASES.tsv`（两条 1.3.2 `workspace` 行 sha256 + 第六十九次同号重打）
- `rounds/R930-mine-chain-full-instrumentation.md`（本文件）

## 验证

- **两树编译**：`compile_promaid.bat` → `EXITCODE=0`；`compile_neo.bat` → `EXITCODE=0`。
- **镜像自查**：插桩是"同一份模板写两树 + 名字映射"，逐锚点断言唯一；逐行读回生成的 `traceExit(...)`（forge 17 行 / neo 17 行，行号一一对应），确认 Forge 侧一律 `ForgeRegistries.BLOCKS/ITEMS.getKey(...state.m_60734_()/stack.m_41720_())`、Neo 侧一律 `BuiltInRegistries.BLOCK/ITEM.getKey(...state.getBlock()/stack.getItem())`，无交叉泄漏。
- **全护栏**：`check_comment_style` **PASS**；`check_catch_swallow` / `check_comment_dup` / `check_dev_voice` / `check_i18n_keys` / `check_panel_coverage` / `check_preset` / `check_reflection` / `check_arch` 全部 **FAIL 0 / WARN 0**（`check_preset` DIY 基线 **172/176** 不变——本轮不增删开关）；`check_workflow` **FAIL 0 / WARN 3**（历史遗留）。
- **两个 jar**：forge **17585329** 字节（sha256 `22B95699…681CB0`）；neoforge **17621641** 字节（`96719BE9…01D044`）。
- **装机**：`deploy_all.py` → 4 个本机实例全部 **`match=True`**（1.21.1-NeoForge_21.1.250 / 1.20.1-Forge_47.4.10 / 龙之冒险：新征程v2.4a / mc_server_test pack1201）；旧包备份在 `patched/backup_old/`。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 `workspace` 行 sha256 更新为新字节，并追加「第六十九次同号重打」；九百二十九 的旧字节 `3958995B…40A084`（forge）/ `14E87CE9…226960`（neo）作废。
- **未跑实机回归**。

## 验收

- 状态：**未验收**（已部署：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
- **怎么看**：让她去挖矿，等"站着不动"再现，然后在 `logs/latest.log` 里搜 **`work exit:`**——这一轮之后**不可能再出现"卡住却一行都没有"**。
  - 判据逐条：
    1. 有 `work exit: … reason=mine 挖掘中(健康)` 且 `progress` 在涨 → 她**正常在挖**（这不是故障）。
    2. 反复出现 `reason=mine 够不着-继续推进` 且 `stuck=n/40` 的 `n` **从不增长**（或一直在 0/40 之间跳） → 她在**微动**（"脚下坐标"永远对不上，40 拍判据永远不成立）——这就是九百二十七/九百二十八 两版"卡死检测"都抓不到的那种卡法。
    3. 反复出现 `reason=mine 锁着目标但超时时钟缺失` → **确认**了上面那条隐患（30 秒清理把"够不着超时"关掉了）。
    4. 反复出现 `reason=mine 只剩挖不动的矿` 或 `mine skip-report:` → 她在**反复选中一块挖不动的矿**（`skip-report` 会直接给出矿名、要求什么工具、她手上是什么）。
    5. 只有 `mine 主手不是镐(暂停挖掘)` → 换镐链条没接上（会附带"背包能不能挖"）。
    6. 只有 `mine 框空`/`mine 全量扫描完成 found=0` → 框里**真的没有矿**（`full=false` 表示连全量都没跑）。
    7. 出现 `mine 分帧扫描进行中(等扫描)` **持续**不结束 → 扫描游标被反复作废（锚点每拍在变）。
  - 请把卡死那一刻前后 **20~30 行**（含时间戳，最好带 `work exit:`、`mine give-up`、`mine skip-report`、`mine chain` 一起）贴回来，我按 `reason` 直接定位到行号。
- 判定：本轮不修根因，只让故障自己说话。

## 边界（没动什么）

- **纯插桩**：所有插桩都是"打印一行"，**没有一处改变判据、返回值、目标、导航或任何计数**。唯一非打印的改动是 `traceExit` 内的**兜底表清空**（`TRACE_AT.size() > 512` 时清一次，防表无限涨），以及它挂在 `LOGGER` 上的 5 秒限频。
- **没修**（本轮只观察、不修）：`purgeStaleMaids` 清 `TARGET_SINCE` 而保留 `targetPos`（隐患 ①）；"卡住"判据要求脚下坐标逐拍完全一致（隐患 ②）；`reportSkippedOre` 系列原本只弹气泡。
- **没插桩**：伐木 tick（`MaidWoodBehavior`）自己的出口本轮**未加**（九百二十九 已给它加过 `giveUpLog` 六条）；它只从基类继承了那两行走路日志。挖矿扫描内部的每列处理（`pickFromCache`/`fullScanOres` 循环体）不加，避免刷屏——它们的**结果**已由"框空/全量扫描完成/只剩挖不动的矿/分帧扫描进行中"四条覆盖。
- **没新增开关**；DIY 关闭条数基线仍 **172/176**；版本号仍 **1.3.2**（`WORKFLOW.md` §5「验收前不升号」）。
- 临时脚本（`tools/_r930_*.py`、`_r930_*.log`、`_r930_*.txt`、`tools/_r929c_*.py`、`_r929c_*.txt`）**已删**；仓库根目录既有的 `_*` 文件与预置目录未动。

## 台账

- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第六十九次同号重打。
- `DEBT.md`：不新增条目——本轮是**诊断插桩**（可随时按需回退），不引入新的未验证功能面。
