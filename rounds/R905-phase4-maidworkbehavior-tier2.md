# 实测九百〇五 ④ 抽 `MaidWorkBehavior` 基类（第二批：per-maid 状态表 + 纯工具）

> 跑道：重构 / 工具（不改玩法语义）。承接 `R904`；开发者「做成可回退，然后继续」——先提交为可回退检查点，再继续把 ④ 的安全子集扩到尽。

## 排班
- ① `R902` 配置文案去叙事（已完成）；② `R903` 架构测绘 + 流程规范化（已完成）；③ `R904` ④ 第一批 7 个成员落刀（已完成，`512524c7` 可回退）；④ **本轮**：④ 第二批 55 个成员落刀；⑤⑥ 仍等实机/真构建开闸。

## 需求 / 来源
- 开发者：「做成可回退，然后继续」。可回退 = 把 `R904` 的成果提交成检查点（已在 `R904` 轮内完成，`512524c7`）；继续 = 把 ④ 的安全子集扩到「两文件代码逐字相同」的全部成员。

## 做了什么
- **精确成员解析器**：去注释/字符串后按花括号配对切出顶层成员（校验：mine 138 / wood 141 个成员，0 处边界异常，末成员恰好落在类闭合括号前一行）。用「注释/空白归一」比对两文件成员，得**代码逐字相同**的候选 58 个。
- **依赖封堵**：对每个候选扫描其引用的顶层成员名，若引用了一个**仍留在子类**的同名成员（代码不同、故不能上提），则该候选不上提。命中 3 个真差异：`trackPlaced`/`clearAll`（→ `PLACED_TRACKER`）、`walkToStep`（→ `approachSpeed`）。
- **落刀 55 个成员 / 365 行**（两树一致，仅 SRG↔Mojmap 名字差异）：per-maid 静态状态表（`ANCHORS`/`OUT_SINCE`/`LAST_RELOCATE`/`SLIDE_SINCE`/`TARGET_SINCE`/`LAST_PROGRESS`/`WATCH_SAMPLE`/`PROGRESS_TAG`/`DANGER_BLOCK_IDS`…）、实例字段（`targetPos`/`destroyProgress`/`chainQueue`/`chainBlock`/`scanCooldown`/`repickCooldown`/`pillarCooldown`/`walkRetargetCooldown`/`lastWalkTarget`…）、纯工具（`markProgress`/`setWalkTarget`/`startStepOn`×2/`broadcastCrack`/`watchSample`/`anyMaidStanding`/`insertIntoMaidInventory`/`saveProgress`/`saveProgressNow`/`loadProgress`/`notifyNoBuildBlock`/`antiSuffocate`/`takeBuildBlock`/`isOpenStone`/`isBreakable`/`isDisabledBreakable`/`isBuiltInBreakable`/`isBuiltinBreakableBlock`/`requiredTool`/嵌套 `Blocker`）。
- 基类 import 取两子类 import 的**并集**（同树内必然合法），可见性统一 `private → protected`。
- **`DEBT.md` §6.1** ④ 段改写为两批落刀的累计数字与「不可上提」清单。

## 验证
- 两树 `compile_promaid/neo` **EXITCODE=0**。
- 全套护栏 `FAIL 0`（`check_comment_style` PASS、`check_catch_swallow`/`check_comment_dup`/`check_dev_voice`/`check_i18n_keys`/`check_panel_coverage`/`check_preset`/`check_reflection` 全 0、`check_workflow` FAIL 0 / WARN 3 为既有发布登记项）。
- **行为透明性证明**：脚本逐成员比对——基类每个成员正文（去可见性）== **两个**子类各自的原文；子类无同体副本残留（dups=0）；唯一不匹配项是基类自己的构造（设计如此）。
- **镜像保真**：forge↔neo 基类 diff 仅 `m_123341_()`↔`getX()`、`m_19879_()`↔`getId()`、`m_8096_()`↔`isCorrectToolForDrops(...)`、`ForgeRegistries`↔`BuiltInRegistries`、`net.minecraftforge.*`↔`net.neoforged.neoforge.*` 等映射/加载器差异（及两树既有换行/注释漂移），无逻辑差异。
- 未部署、未跑实机挖矿/伐木回归。

## 验收
- 状态：`未验收`（编译 + 护栏 + 「基类正文 == 子类原文」三者可自证；运行时行为需开发者「部署」开闸回环测试）。
- 累计（相对 `1aff5710`，每树）：`MaidMineBehavior` −613、`MaidWoodBehavior` −619、基类 +686 → **每树净去重约 546 行，两树合计约 1092 行**。

## 边界（没动什么）
- 未上提三个**真差异**成员（`reachSq`/`PLACED_TRACKER`/`approachSpeed`）及其连带引用者——它们要任务特异参数化（`MINE_*` vs `WOOD_*` 配置键），属下一步。
- 未合并剩余**驱动逻辑**（扫描-接近-连锁-回收的差异部分）：两树行数/写法均有差异，需逐方法人工核对语义 + 实机回归。
- 未动 ⑤（`PromaidConfigScreen`）、⑥ 第二步（共享源目录）；未动 `patched/`、`store/`、`_tmp_pub/`；未改版本号（仍 1.3.2）；未 push。
- 临时脚本（`_hoist2.py`/`_ver2.py`/`_tidy2.py` 等）用后即删。

## 台账
- ④ 可编译校验子集完成（两批 62 个成员，本轮）；④ 剩余驱动逻辑合并、⑤、⑥ 第二步待办（`DEBT.md` §6.1）。
