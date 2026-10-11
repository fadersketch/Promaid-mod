# 实测八百九十二 phase② 注释去历史：combat/MaidFlightCombatBehavior 长注释瘦身（两树）

> 跑道：纯注释（不改行为、不改配置 / i18n / 版本）。承接 `R891`，按 `DEBT.md` §3 六阶段「② 注释去历史」逐文件推进。本轮处理**全库最大**的行为类注释体（Forge 53 / Neo 51 块）。

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）
- **跑道**：只改注释（注释块瘦身，无源码逻辑 / 配置 / i18n 改动），只落 dev 面。
- **开工前读过**：`DEBT.md` §3（②「注释去历史」）、`WORKFLOW.md`（★红线 / §1 / §4 / §12）、上一轮 `R891`。结论：沿用 R890/R891 工序；本文件块多且两树存在个别结构差（Neo 多 `forcedFallDistance` / `faceUpForward`，少 `separationRadius` / `hitOne` / `launchFirework` 的标记者），故按树分别给标记。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 逐块盘点两树（含每块的"声明行"锚点） | 拿到 53（Forge）/ 51（Neo）块清单与锚点 | ✅ |
| 1 | 逐块改写到上限（保留约束 + 一行出处） | 该文件 LONG-BLOCK 归零 | ✅ |
| 2 | 两树编译 | `EXITCODE=0` | ✅ |
| 3 | 交开发者验收 | 开发者看可读性后给结论 | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者；验法 = 打开该文件看注释是否读得懂、约束有无丢；机器侧看 `check_comment_style` 计数下降。

## 需求 / 来源
- 开发者「就按顺序」：按 `DEBT.md` §3 六阶段清理顺序推进，本轮续 **阶段② 注释去历史**。
- 一句话需求：把过长注释里「上一版错在哪、这一版怎么改」的变更史删掉，只留当前职责与硬约束。

## 评估
- **跑道**：纯注释轮（不满足 A「行为是错的」、也不满足 B「新增能力」）。
- 承接 `R890`（2260→2229）、`R891`（2229→2207）。本轮选 `combat/MaidFlightCombatBehavior`：空袭行为的完整链路实现，Forge 53 / Neo 51 块 LONG-BLOCK，是全库单文件最大的**散文**注释体（非字段列）。

## 改法
- 全部块改写到 `DEV_GUIDE.md` §1 上限：类头 ≤15 行、成员 ≤8 行（= `/**` + 6 行正文 + `*/`）。
- **保留**（硬约束）：起跳滑翔/暴击判据、`Sensor.isEntityAttackable` 内含 `range(16.0)`、`hasImpulse` 重发、`resetXRotOnTick()` 恒真导致朝向被抹、`GunData.tick` 是 SBW 状态机唯一推进者、`DiveBoostRocket` 的"抬不动点"推导、位移法术两类（提供高度/速度）的冲量公式、盘旋半径随机+硬上界、近身弹开"同时改速度+改朝向"。
- **删除**：`v1.x 实测NNN：旧版…这一版…` 的变更史与开发心路；出处压成尾行一句或 `{@link}` 指向。
- 工序要点（本轮新增，供后续沿用）：
  - 按**声明行**定位块；若短标记在文件里出现多次，自动挑"其上方注释游程 >8 行"的那一处（= 被护栏标记者）。
  - 注释块与声明行之间**可能夹空行**（护栏把空行当断点），故改用"**双向**扩展：从标记向上下吃满连续注释行"，避免漏删旧块造成重复。
  - 新块长度在脚本里**断言 ≤8**（`/**`+6+`*/`），杜绝再次出现"以为 8 行实为 9 行"的手数误差。

## 触及文件
- `promaid_src/com/maidsmart/combat/MaidFlightCombatBehavior.java`（LONG-BLOCK 53 → 0）
- `promaid_src_neo/com/maidsmart/combat/MaidFlightCombatBehavior.java`（LONG-BLOCK 51 → 0）
- 无新配置项 / 新 i18n key / 新 mixin；无 Java 逻辑改动。

## 验证
- `python tools/verify/check_comment_style.py --file MaidFlightCombatBehavior` → **PASS（0 处）**。
- 全局 `check_comment_style`：**2207 → 2103**（−104）。
- 两树 `compile_promaid.bat` / `compile_neo.bat` → **EXITCODE=0**。
- 未部署、未跑专服回归（纯注释，不涉运行时）。

## 验收
- 状态：`未验收`（等开发者看注释可读性与约束完整性）。

## 边界（没动什么）
- **只动注释**，一行代码 / 字符串 / 配置未改。
- 未动其它文件的 LONG-BLOCK（其余 2103 处留后续轮次，按最坏优先）。
- 未碰 `MaidSmartConfig` 的 `.comment(...)`（i18n key，须另开专项轮）。
- **未 commit / push**（按 `WORKFLOW.md` §12；等开发者指示）。

## 台账
- 本轮属 `DEBT.md` §3 六阶段「② 注释去历史」的推进，不新增 / 不清 BD·TD 条目；进度以 `check_comment_style` 计数为准（2207 → 2103）。
- **备注（供后续轮/开发者定夺）**：`combat/MaidMountCompat`（7674 行）的 LONG-BLOCK 多为"**带行尾注释的字段列**"（如 `@98` 的 93 行 = 一串 `private static Method mXxx;  // Xxx.method()`）——护栏把连续带注释的代码行也算作一个注释块。按 `DEV_GUIDE.md` §1.2「一个成员最多一处出处，不要在同一条链路的每个方法上都抄一遍」，这类逐字段行尾注释本就应与组头合并；因体量大、且涉及结构，留作单独一轮处理。
