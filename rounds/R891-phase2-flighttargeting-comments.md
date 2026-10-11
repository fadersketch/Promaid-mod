# 实测八百九十一 phase② 注释去历史：combat/FlightTargeting 长注释瘦身（两树）

> 跑道：纯注释（不改行为、不改配置 / i18n / 版本）。承接 `R890`，按 `DEBT.md` §3 六阶段「② 注释去历史」逐文件推进，最坏优先取下一个自成体系文件。

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）
- **跑道**：只改注释（注释块瘦身，无源码逻辑 / 配置 / i18n 改动），只落 dev 面。
- **开工前读过**：`DEBT.md` §3（②「注释去历史」）、`WORKFLOW.md`（★红线 / §1 / §4 / §12）、上一轮 `R890`（已验证"逐块改写 + 两树镜像"工序）。结论：沿用 R890 工序，取两树 LONG-BLOCK 计数靠前的 `combat/FlightTargeting`（Forge 11 / Neo 11 块）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 选定下一文件（最坏优先、自成体系） | 锁定 `combat/FlightTargeting.java`（11+11 块） | ✅ |
| 1 | 逐块改写到上限（保留约束 + 一行出处） | 该文件 LONG-BLOCK 归零 | ✅ |
| 2 | 两树编译 | `EXITCODE=0` | ✅ |
| 3 | 交开发者验收 | 开发者看可读性后给结论 | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者；验法 = 打开该文件看注释是否读得懂、约束有无丢；机器侧看 `check_comment_style` 计数下降。

## 需求 / 来源
- 开发者「就按顺序」：按 `DEBT.md` §3 六阶段清理顺序推进，本轮续 **阶段② 注释去历史**。
- 一句话需求：把过长注释里「上一版错在哪、这一版怎么改」的变更史删掉，只留当前职责与硬约束。

## 评估
- **跑道**：纯注释轮（不满足 A「行为是错的」、也不满足 B「新增能力」）。
- 承接 `R890`（全局 LONG-BLOCK 2260 → 2229）。本轮选 `combat/FlightTargeting`：空袭/扫帚/载具共用的索敌器，类头 98 行（Forge）/ 91 行（Neo），另有 10 处成员块超限，是全库第二档的注释体量。

## 改法
- 11 块逐块改写到 `DEV_GUIDE.md` §1 上限：类头 ≤15 行、方法 / 字段 ≤8 行。
- **保留**：`EntityMaid.searchDimension()` 按排班活动分流（非 WORK 走 idle、垂直硬编码 4 格）、`Sensor.isEntityAttackable` 内含 `range(16.0)` 的反编译实证、50（发现）/ 128（维持）拆分理由、固定翼"圆心换主人 + 度量换水平"两处改动、"纯服务端"约束、状态表清理入口。
- **删除**：`实测NNN：上一版…这一版…` 的变更史与开发心路，出处压成尾行一句 / 并入 `{@link}` 指向（`HOLD_RANGE` / `AIRCRAFT_RANGE` 各自承担细节）。
- 两树共用同一份新注释（类头与成员块在两树逐字一致，Mojmap 差异只落在代码行），按**声明行**定位其上方注释块整体替换（脚本对每个目标断言：标记行唯一命中、上方确为 `/**` 或 `//` 块头），杜绝误伤代码。

## 触及文件
- `promaid_src/com/maidsmart/combat/FlightTargeting.java`（LONG-BLOCK 11 → 0）
- `promaid_src_neo/com/maidsmart/combat/FlightTargeting.java`（LONG-BLOCK 11 → 0）
- 无新配置项 / 新 i18n key / 新 mixin；无 Java 逻辑改动。

## 验证
- `python tools/verify/check_comment_style.py --file FlightTargeting` → **PASS（0 处）**。
- 全局 `check_comment_style`：**2229 → 2207**（−22）。
- 两树 `compile_promaid.bat` / `compile_neo.bat` → **EXITCODE=0**。
- 未部署、未跑专服回归（纯注释，不涉运行时）。

## 验收
- 状态：`未验收`（等开发者看注释可读性与约束完整性）。

## 边界（没动什么）
- **只动注释**，一行代码 / 字符串 / 配置未改。
- 未动其它文件的 LONG-BLOCK（其余 2207 处留后续轮次，按最坏优先）。
- 未碰 `MaidSmartConfig` 的 `.comment(...)`（那是 i18n key，改动必须同步 `en_us.json`，须另开专项轮）。
- **未 commit / push**（按 `WORKFLOW.md` §12；等开发者指示）。

## 台账
- 本轮属 `DEBT.md` §3 六阶段「② 注释去历史」的推进，不新增 / 不清 BD·TD 条目；进度以 `check_comment_style` 计数为准（2229 → 2207）。
