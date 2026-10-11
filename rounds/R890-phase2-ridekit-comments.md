# 实测八百九十 phase② 注释去历史（起）：combat/MaidRideKit 长注释瘦身（两树）

> 跑道：纯注释（不改行为、不改配置 / i18n / 版本）。起因：开发者「就按顺序」——按 `DEBT.md` §3 六阶段推进，本轮开 **② 注释去历史**。

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）
- **跑道**：只改注释（注释块瘦身，无源码逻辑 / 配置 / i18n 改动），只落 dev 面。
- **开工前读过**：`DEBT.md` §3（屎山快照②「注释去历史」与该阶段定义）、`WORKFLOW.md`（★红线 / §1 / §4 / §12）、`ARCHITECTURE-SWITCHES.md`（与本轮无关）、上一轮 `R889`。结论：阶段② = 把过长注释里的「变更史 / 为什么」压掉，代码只留「现在做什么 + 硬约束 + 一行出处」，上限见 `DEV_GUIDE.md` §1.4（类 ≤15 行 / 方法·字段 ≤8 行 / 行内 ≤3 行）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 盘点两树 LONG-BLOCK（`check_comment_style`） | 得到逐文件计数（2260 处 / 604 文件） | ✅ |
| 1 | 取一个自成体系的文件做样板 | 选定 `combat/MaidRideKit.java`（Forge 17 / Neo 14 块） | ✅ |
| 2 | 逐块改写到上限（保留约束 + 一行出处） | 该文件 LONG-BLOCK 归零 | ✅ |
| 3 | 两树编译 | `EXITCODE=0` | ✅ |
| 4 | 交开发者验收 | 开发者看可读性后给结论 | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者；验法 = 打开该文件看注释是否读得懂、约束有无丢；机器侧看 `check_comment_style` 计数下降。

## 需求 / 来源
- 开发者「就按顺序」：按 `DEBT.md` §3 的六阶段清理顺序推进，本轮为**阶段② 注释去历史**。
- 一句话需求：把过长注释里「上一版错在哪、这一版怎么改」的变更史删掉，只留当前职责与硬约束。

## 评估
- **跑道**：纯注释轮（不满足 A「行为是错的」、也不满足 B「新增能力」）。
- 现状：`check_comment_style` 两树 **LONG-BLOCK 2260 处 / 604 文件**；最坏 `combat/MaidMountCompat`（106+105）、`MaidFlightCombatBehavior`（53+51）、`RideBindManager`（43+42）……
- 本轮取一个**自成体系、两树结构接近**的文件 `combat/MaidRideKit`（骑乘能力探测工具类）做样板，验证"逐块改写 + 两树镜像"的工序。

## 改法
- 逐块改写到 `DEV_GUIDE.md` §1 上限：类头 ≤15 行、方法 / 字段 ≤8 行。
- **保留**：`javap` / 反编译实证的硬约束（如 `startRiding(mount,true)` 跳过两道门、`getControllingPassenger()` 对女仆恒 null）、取值 / 单位、两树差异、`@param/@return`；**删除**：`实测NNN：上一版…这一版…` 的变更史与开发心路，出处压成尾行一句。
- 顺手合并了该文件里「11 行长注释 + 紧随 1 行 javadoc」的叠放（javadoc 只认后者）。
- 两树各按**行号区间**替换（脚本对每个区间断言：首行 `/**`、末行 `*/`），杜绝误伤代码。

## 触及文件
- `promaid_src/com/maidsmart/combat/MaidRideKit.java`（LONG-BLOCK 17 → 0）
- `promaid_src_neo/com/maidsmart/combat/MaidRideKit.java`（LONG-BLOCK 14 → 0）
- 无新配置项 / 新 i18n key / 新 mixin；无 Java 逻辑改动。

## 验证
- `python tools/verify/check_comment_style.py --warn --file MaidRideKit` → **PASS（0 处）**。
- 全局 `check_comment_style`：**2260 → 2229**（−31）。
- 两树 `compile_promaid.bat` / `compile_neo.bat` → **EXITCODE=0**。
- 未部署、未跑专服回归（纯注释，不涉运行时）。

## 验收
- 状态：`未验收`（等开发者看注释可读性与约束完整性）。

## 边界（没动什么）
- **只动注释**，一行代码 / 字符串 / 配置未改。
- 未动其它文件的 LONG-BLOCK（其余 2229 处留后续轮次，按最坏优先）。
- 未碰 `MaidSmartConfig` 的 `.comment(...)`（那是 i18n key，改动必须同步 `en_us.json`，须另开专项轮）。
- **未 commit / push**（按 `WORKFLOW.md` §12；等开发者指示）。

## 台账
- 本轮属 `DEBT.md` §3 六阶段「② 注释去历史」的推进，不新增 / 不清 BD·TD 条目；进度以 `check_comment_style` 计数为准（2260 → 2229，样板工序已跑通）。
