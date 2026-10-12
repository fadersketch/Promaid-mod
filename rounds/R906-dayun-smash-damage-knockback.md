# 实测九百〇六 「大运」撞击：破坏范围内的非友方单位按使用者速度结算伤害与击飞

> 跑道：**FEATURE**（新增玩家可见玩法）。需求原文：「大运破坏范围内的非友方单位会根据使用者的速度结算伤害以及击飞距离。」

## 排班
- **① 测绘（已完成）**：接点 = `accessory/DaYunBreaker`（破坏引擎，含「中心立方体 + 前瞻采样」两段范围）、`DaYunBauble`（女仆侧 tick，仅空袭）、`DaYunPlayerHook`（玩家侧 tick，仅滑翔）。友方判据 = `combat/FriendlyFireGuard#isFriendly`（女仆口径）。伤害/击退写法照 `combat/MaidFlightCombatBehavior#hitOne`、`combat/MaidCombatTacticsBehavior`。两树映射名已核对（见下「两树映射表」）。
- **② 修法设计**：新增 `accessory/DaYunSmash`，把「破坏范围」的**采样中心**从 `DaYunBreaker` 抽成公开方法（破坏与撞击共用同一份范围口径，杜绝漂移），对范围内 `LivingEntity` 结算「伤害 = 速度 × 系数（封顶）」+「击飞 = 速度 × 系数（封顶，含上挑）」。友方过滤自己实现（**不依赖** `COMBAT_FRIENDLY_FIRE_GUARD`，且覆盖玩家侧「自己的宠物/女仆」）。
- **③ 落刀清单（每树一份）**：新增 `DaYunSmash.java`；`DaYunBreaker` 抽 `coveredCenters()` 并复用于破坏；`DaYunBauble`/`DaYunPlayerHook` 各加一处 `DaYunSmash.impact(...)`；`MaidSmartConfig` 加 3 项（总开关 + 伤害系数 + 击飞系数）；两 `lang` 各补 6 键（含自动面板页用的 `config.promaid.accessory.daYun*` 短名）；`assets/promaid/i18n/en_us.json` 补 3 条注释英译；手册「饰品「大运」」章加一段。`tools/verify/check_preset.py` 基线 174/178 → **175/179**；`ARCHITECTURE-SWITCHES.md` 重生成。
- **④ 完成判据**：两树 `javac` EXITCODE=0；全套护栏 FAIL 0（`check_i18n_keys` 0/0、`check_preset` 0、`check_comment_style` PASS）；forge↔neo 新文件逐行仅映射名差异。
- **⑤ 谁验收**：开发者实机（玩家戴 Curios / 女仆空袭）——飞起来撞怪，看是否按速度掉血+被击飞，主人/友方/自己的宠物不受伤。
- **⑥ 卡点**：无（开发者已明确「做完后部署+回归」，第 8~10 步一并开闸）。

## 两树映射表（本功能用到的全部差异）
| 语义 | Forge（SRG） | Neo（Mojmap） |
|---|---|---|
| 取速度矢量 | `m_20184_()` | `getDeltaMovement()` |
| 矢量分量 | `m_7096_()/m_7098_()/m_7094_()` | `x()/y()/z()` |
| 在滑翔 | `m_21255_()` | `isFallFlying()` |
| 存活 | `m_6084_()` | `isAlive()` |
| 所在格 | `m_20183_()` | `blockPosition()` |
| 实体坐标 | `m_20185_()/m_20186_()/m_20189_()` | `getX()/getY()/getZ()` |
| UUID | `m_20148_()` | `getUUID()` |
| 伤害指示器 | `m_269291_()` | `damageSources()` |
| 生物攻击源 | `.m_269333_(LivingEntity)` | `.mobAttack(LivingEntity)` |
| 玩家攻击源 | `.m_269075_(Player)` | `.playerAttack(Player)` |
| 受伤 | `m_6469_(源, 量)` | `hurt(源, 量)` |
| 原版击退 | `m_147240_(强度,x,z)` | `knockback(强度,x,z)` |
| 叠加动量 | `m_5997_(x,y,z)` | `push(x,y,z)` |
| 范围内取实体 | `m_45976_(Class, AABB)` | `getEntitiesOfClass(Class, AABB)` |
| 主人 | `m_269323_()` | `getOwner()` |
| 同队/同盟 | `m_7307_(e)` | `isAlliedTo(e)` |
| 已驯服 | `m_21824_()` | `isTame()` |
| 宠物主人 UUID | `m_21805_()` | `getOwnerUUID()` |

## 验证
- **编译**：两树 `javac` **EXITCODE=0**（`gen_compile*.py` 重生成 argfile，已含新文件 `DaYunSmash.java`；日志里除历史遗留的 `[removal]` 过时告警外无错）。
- **护栏**：全套 **FAIL 0** —— `check_comment_style` PASS、`check_comment_dup` 0/0、`check_dev_voice` 0/0、`check_i18n_keys` 0/0、`check_preset` 0/0（基线已上调 175/179）、`check_panel_coverage` 0/0、`check_reflection` 0/0、`check_workflow` FAIL 0（4 条 WARN 均为版本 / 归档 / 配额类，见「边界」）、`check_catch_swallow` 0/0、`check_tree_mirror` 报告完成（只读，不设门槛）。
- **镜像**：新文件逐行对比——两树**仅** SRG↔Mojmap 映射名不同（`isAlive` / `isFallFlying` / `motion.x` / `BlockPos#getX` / `getEntitiesOfClass` / `getUUID` / `damageSources().playerAttack|mobAttack` / `hurt` / `knockback` / `push` / `getOwner` / `isTame` / `getOwnerUUID` / `isAlliedTo`），注释逐字相同。
- **破坏行为不变**：`DaYunBreaker` 只把「前瞻采样点 + 自身那格」抽成 `coveredCenters`（`AHEAD_FACTOR`/`AHEAD_MAX`/`AHEAD_MIN` 一字未改、采样顺序不变）；`sweep` 改成遍历同一份列表，`if (cleared >= maxBlocksPerTick) break;` 等价于旧版的前置 guard。
- **部署@2026-10-12**：`deploy_all.py` 4 个目标全部 **`match=True`**（1.21.1-NeoForge_21.1.250 / 1.20.1-Forge_47.4.10 / 龙之冒险：新征程v2.4a / mc_server_test pack1201），旧包备份在 `patched/backup_old/`。两个 jar：forge `promaid-1.3.2-forge-1.20.1.jar` 17580949 B / `sha256 5aa143c4…9b70c6`；neoforge `promaid-1.3.2-neoforge-1.21.1.jar` 17618998 B / `sha256 9e907b94…d0c857`。
- **回归@2026-10-12**：`test_server` 1201 `verdict: PASS`（`Done (7.808s)`）、`test_maid_load` 1201 `verdict: PASS`；`test_server` neoforge1211 `verdict: PASS`（`Done (3.092s)`）、`test_maid_load` neoforge1211 `verdict: PASS`。**四件全 PASS**，日志里无一条与 promaid 相关的 ERROR。
  （neo 侧那批 `touhou_little_maid_spell:…` 战利品表 `Unknown registry key` 是第三方 mod 的数据包问题，与本模组无关；回归脚本按其固定口径也将其排除在判定外。）


## 验收
- 状态：**`未验收`**（等开发者实机）。
- 怎么试：玩家把「大运」放进 **Curios 饰品栏**（或女仆空袭时装 TLM 饰品栏），鞘翅**滑翔中撞向怪物** —— 按速度掉血 + 被击飞；
  速度越高（平飞约 0.6~1.2 格/tick，带烟花 / 俯冲更高）打得越狠、飞得越远；几乎悬停（< 0.4）不结算。
- 预期**不撞**：使用者自己 / 主人 / 同一个主人的女仆 / 使用者名下的宠物 / 同队同盟（这一条**不看** `combat.friendlyFireGuard` 总闸）。
- 怎么调：面板 「移动与行为 → 饰品「大运」 → 其余开关（自动）」，或直接改 config：`accessory.daYunSmash`（总开关）/ `daYunSmashDamage`（每格速度的伤害，默认 5.0）/ `daYunSmashKnockback`（每格速度的击飞，默认 2.0）。
- 日志：`logs/promaid.log` 搜 `DaYunSmash`（本模块只在异常时留痕，正常撞击不刷日志）。


## 边界（没动什么）
- 本轮**只加一个新玩法**，没改「开路」破坏的任何判据 / 数值 / 开关；`coveredCenters` 是**纯抽取**（逐字一致的范围口径，不同步就会让“破坏范围内”两边说的不是同一片地方）。
- 不改版本号（仍 **1.3.2**）、**不写版本更新内容 / release 说明**（§6.1：未验收前一个字都不写）、不动 `store/` 与 `_tmp_pub/`。
- **为什么这一轮先做功能**：开发者本轮明确点名要这个功能，并在**同一条指令里**给了「做完后部署 + 回归」的授权（按他的话走 §4 第 8–10 步）。
  本轮**未清** P0/P1 债（BD-04 仍 `未清`），故 `check_workflow` §8 配额有一条 WARN —— 这是**开发者裁定的优先级**，如实留痕；不擅自改版本号、也不替开发者开别的闸。
  其余 3 条 WARN（`PUBLISH_GUIDE.md` 停在 1.3.1 —— 它记的是“平台上现有什么”、`promaid-1.2.4.jar` 归档里已无同名无从核对、更早 13 个 jar 未登记）均为本仓库长期存在的归档 / 发布项，与本轮无关。
- 未新增债务；也未动「余额」内容（新开关的可见文案已按 §6 只写“这个开关做什么”，未提稀里糊涂的过程）。


## 台账
- 新增开关 1 项（`accessory.daYunSmash`，默认开、随 DIY 关）+ 2 项数值（`daYunSmashDamage` 5.0 / `daYunSmashKnockback` 2.0）；
  `check_preset` 基线 174/178 → **175/179**；`GATES_ELSEWHERE` 增 `DAYUN_SMASH → com/maidsmart/accessory/DaYunSmash.java`；`ARCHITECTURE-SWITCHES.md` 重生成。
- 两树 lang 各 +6 键（含自动面板页用的 `config.promaid.accessory.daYun*` 短名）、`assets/promaid/i18n/en_us.json` 各 +5 条（手册新一节 + 三条配置注释）。
- 未新增债务（DEBT.md 无动）。

