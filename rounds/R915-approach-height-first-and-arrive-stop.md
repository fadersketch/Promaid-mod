# 实测九百一十五 挖矿/伐木接近逻辑：严格高度优先 + 到位就停（治「迈克尔杰克逊步」）

> 跑道：**A 修 bug**（P1：接近目标时"往身后垫方块、两点间反复横跳"，且开发者要求的"先对齐高度、
> 再水平靠近"没生效，见 `DEBT.md` **BD-19**）。本轮**不是功能轮**（`WORKFLOW.md` §8「P0/P1 未清
> 期间不得开纯功能轮」照旧成立——BD-04 仍挂着未清），且本轮已清掉一条 P1（BD-19），配额口径满足。

## 排班

- 开发者指令（一次给全）：「必须抢修。我发现女仆有的时候会走迈克尔杰克逊步。就是自己明明在往前走，
  但是却喜欢往自己后方搭一个方块，随后就在这两个位置上反复横跳。可以看一下具体的游戏日志。而且我原本
  说的先把高度上保持一致，然后在水平方向上保持一致，这个功能没有生效。」
- 拆成两件事（同一处代码的两个症状）：
  - **①** 「往身后垫块 + 两点横跳」——接近链路把一个方块垫到了**她身后**，然后被推回去，来回震荡。
  - **②** 「高度优先、再水平靠近」没生效——旧的斜向垫台阶把**垂直与水平混着逼近**。

## 定位（BD-19）

- **代码在跑哪一段**：`task/MaidWoodBehavior.approachWood` 与 `task/MaidMineBehavior.approachOre`
  （每 tick 重算，垫一块就重新评估）。旧顺序：
  1. `if (dy >= 2 && hDist <= 2.5)` → 原地搭高（`pillarUpStep`）；
  2. `if (dy >= 1 && hDist > 2.5 && hDist <= 8.0)` → 斜向垫台阶（`slopeStep`）——**水平与垂直一起逼近**；
  3. `if (dy <= -1)` → 逐级下降（`descendStep`）；
  4. 否则 → 前方脚下悬空先搭桥（`bridgeToWood`/`bridgeToOre`），否则走过去。
- **横跳的机制**：`startStepOn` 用方向单位量 `(hx/hDist, hz/hDist)` 把落点投到她**前方一格**
  （`floor(x + hx/hDist)`）。中距离（2.5~8 格）时 `slopeStep` 会先垫一块，`BlockWalkOn` 再推她过去；
  可她的水平投影一旦**越过目标轴附近**（`hx` 或 `hz` 变号），单位量**翻号** → 下一拍算出的落点落在
  **她身后**，`BlockWalkOn` 把她往后推、`startStepOn` 又覆写目标 → 在前后两点间反复横跳。
  旧版**没有水平死区**，也没有"垫块推送进行中就别再改目标"的滞回，所以每一拍都在自我推翻。
- **"高度优先"为什么没生效**：`slopeStep` 存在的那一条正是"斜着同时逼近"——与"先把高度弄一致、
  再水平靠近"直接冲突；同时第一档 `dy >= 2 && hDist <= 2.5` 说明她在**近但不够高**时（`dy>=1` 且
  `hDist<=2.5`）根本不会原地搭高，反而落到第 2 / 第 4 档去水平走位。
- **日志佐证**：游戏日志里没有 wood/mine 链路的专用 tag（最新一份 Neo 包是飞行相关居多），所以本轮
  定位**以源码为准**（上面对 `approachOre`/`approachWood`/`setWalkTarget`/`startStepOn`/`BlockWalkOn`
  逐处读过）。

## 修法 / 落刀清单

两树、两行为，共 **4 个方法**（`MaidWoodBehavior.approachWood` ×2、`MaidMineBehavior.approachOre` ×2），
逐处同样的四刀：

1. **严格高度优先**：`if (dy >= 2 && hDist <= 2.5)` → **`if (dy >= 1)`**——只要目标在她上方 1 格以上，
   **无条件原地搭高**（`pillarUpStep`，成功后 `stop()` 让 `pillarGuard` 钳位），水平远近都先不管。
   `dy <= -1` 逐级下降（`descendStep`）与 `dy == 0` 才做水平这两档保持原序。
2. **删除斜向 `slopeStep`**：删掉整个 `if (dy >= 1 && hDist > 2.5 && hDist <= 8.0) { … slopeStep … }`
   分支连同其前置连续注释，并删除 `slopeStep` 方法本体与它的 javadoc（两树两行为共 4 处）。
   `descendStep` 的 javadoc/注释里对 `slopeStep` 的引用同步改写成「普通垫块」。
3. **新增"到位就停"死区**：在 `dy` 算出之后插入
   ```java
   double close = Math.min(2.5, Math.max(1.0, Math.sqrt(reachSq()) - 0.5));
   if (dy == 0 && hDist <= close) {
       return;
   }
   ```
   ——高度已一致 + 水平进了可交互圈 → **不再走 / 不再垫**，当拍直接交给挖掘入口（这正是旧版缺的
   那道闸：没有它，她在目标轴附近一抖就翻号垫身后）。
4. **桥段加滞回**：`bridgeToWood`/`bridgeToOre` 成功后的 `startStepOn(...)` 包进
   `if (!BlockWalkOn.isActive(maid)) { … }`——**推送进行中不再覆写走位目标**（治同一批"每拍自我推翻"）。

`approachWood`/`approachOre` 的 javadoc 头改写为「①高度优先 ②高度一致后再水平靠近 ③到位就停」。

## 验证

- 两树编译：`javac @compile_promaid.txt`（**464 源**）与 `javac @compile_neo.txt`（**467 源**）
  均**零错误**（仅历史 deprecation/未检查告警；Forge 86 条、Neo 39 条，与改动无关）。
- 全套护栏：`check_comment_style` **PASS**；`check_arch` / `check_panel_coverage` / `check_catch_swallow` /
  `check_comment_dup` / `check_dev_voice` / `check_i18n_keys` / `check_preset` / `check_reflection`
  全部 **FAIL 0 / WARN 0**；`check_preset` 基线 Forge **175** / Neo **179** 不变（本轮不增删开关/数值项）；
  `check_workflow` **FAIL 0 / WARN 3**（3 条均为历史遗留：PUBLISH_GUIDE 版本 1.3.1 ≠ mods.toml 的 1.3.2、
  1.2.4 归档哈希不可核、13 个更早 jar 未登记）。
- 打 jar：两树 `verify_jar_classes` **OK**（mixin 包登记 Forge **97** / Neo **96** 个类全部登记、无未登记
  「鸭子接口」；mixin 注入签名无歧义/错名；out 与 jar 的 class 集合双向一致）；lang json **OK**；
  构建末尾全套护栏 **FAIL 0**。
- 未跑实机回归（`WORKFLOW.md` §4 第 8 步后一律先停）。打 jar 与部署见下「部署」小节。

### 部署（2026-10-12，开发者常态指令：「以后做完功能都是先部署，但后续的工程不要做，等我指挥」）

- **打 jar**：`tools/build/build_promaid.py` → `patched/promaid-1.3.2-forge-1.20.1.jar`
  （**17578630** 字节，sha256 `01ad2e55…8f475b`）；`build_promaid_neo.py` →
  `patched/promaid-1.3.2-neoforge-1.21.1.jar`（**17616672** 字节，sha256 `ed19d58b…a333e6`）。
- **装机**：`tools/deploy/deploy_all.py` → 4 个本机实例全部 **`match=True`**（与 `patched/` 逐字节一致）：
  1.21.1-NeoForge_21.1.250 / 1.20.1-Forge_47.4.10 / 龙之冒险：新征程v2.4a / mc_server_test pack1201
  （后两个实例需提权复制，脚本走 UAC 后 rc=0）。旧包已备份到 `patched/backup_old/`
  （17616951 / 17578945，即 R914 第五十三次字节）。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 `workspace` 行的 sha256 列更新为新字节，并追加
  「第五十四次同号重打」记录，旧字节 `e899f791…3a22a4`（forge）/ `9e09a027…7f6013`（neo）作废。
- **到此为止**：**未跑回归、未验收、未 commit、未 push**（开发者明确"后续的工程不要做"）。

## 验收

- 状态：`未验收`（已部署@2026-10-12：本机 4 实例与 `patched/` 逐字节一致；未 commit、未 push）。
  - 复现步骤 1（**不再垫身后块 / 不横跳**）：让女仆挖一个**斜上方**（高 1~3 格、水平 2~8 格）的矿
    或砍一棵略高的树 → 她应当**先原地/正下方把高度垫上来**，而不是朝斜后方垫块再走回去；
    不应再出现"在两点之间来回走"的观感。
  - 复现步骤 2（**高度优先真的生效**）：目标在她上方时，观察她的 y **先追平**目标、再在水平方向贴近；
    高度没对齐之前不应为了水平距离而左右绕。
  - 复现步骤 3（**到位就停、交给挖掘**）：高度一致且水平已进可达圈（约 `min(2.5, √reach − 0.5)` 格内）
    → 她**站定开挖**，不再"多垫一块 / 再走半步"。
  - 判定：步骤 1 无身后块无横跳、步骤 2 先竖后横、步骤 3 到位即开挖 = 合格；异常请把
    `logs/promaid.log` 相关几行贴回。
- 回归确认（不该被本轮误伤）：`descendStep`（向下逐级下降）、`bridgeToWood`/`bridgeToOre`（断崖/水搭桥）、
  连锁挖矿、换镐、搭方块回收等既有机制**照旧**。

## 边界（没动什么）

- **只改接近链路的排序与死区**：`pillarUpStep` / `descendStep` / `bridgeToWood` / `bridgeToOre` /
  `firstWoodAbove` / `walkToWoodBase` / `walkToOreBase` / `findStandNearOre` 的**内部逻辑一个字没动**；
  `setWalkTarget`（导航节流 ±1 容差 / >14 格取 12 格中点）与 `startStepOn`（落点算法）也未改。
- 未动挖矿/伐木的**选目标**（最近优先，R913 已定）、未动任务锁定 / 超时 / `ABANDONED` /
  看门狗（R868）、未动走位独占与扩圈（R913/R914）。
- 未增删任何**配置项 / 开关 / 默认值**（`MINE_REACH`/`WOOD_REACH` 仍默认 4.5；`check_preset` 基线不变）；
  未改版本号（仍 **1.3.2**）。
- `ARCHITECTURE-SWITCHES.md` / `panel_coverage.txt` / `normify-promaid/` 结构数据**不变**（本轮无新类 /
  新开关，模块与产物哈希一致 → `check_arch` FAIL 0）；`tools/arch/arch_modules.py` 各代表文件的
  `end_line` 数字未动（`check_arch` 只校验产物与真源逐字节一致、不校验行号）。
- 临时脚本（`_fix.py` / `_log.py` / `_rel.py` / `_neo_err.txt`）用后即删。

## 台账

- `DEBT.md`：新增 **BD-19**（P1，已清@实测九百一十五）并更新 §0 索引行（挖矿/伐木列加入 `BD-19`，
  收益改「接近不横跳（严格高度优先 + 到位就停）」）。
- 待办（下一轮或开发者指令）：实机验收「迈克尔杰克逊步」是否消失、高度优先是否可感；验收/部署后按
  `WORKFLOW.md` §4 第 10 步开闸再跑回归；`git commit` 后重跑一次 `python tools/arch/build_arch.py`
  （提交后修订哈希会前移，`check_arch` 会以 WARN 提示，属预期）。
