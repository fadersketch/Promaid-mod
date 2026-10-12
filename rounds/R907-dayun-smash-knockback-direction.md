# 实测九百〇七 「大运」撞击的击飞方向改为顺着佩戴者的运动方向

> 跑道：**A（BUGFIX）**——修正实测九百〇六 新增玩法里「击飞固定向上」与需求不符。
> 需求原话：「给予怪物的速度冲量不应该固定向上，而应该是顺着飞行者运动的方向。」

## 排班
- **① 定位（BUGFIX 三问，开工前已答）**
  - **现象**：撞击命中后目标被**固定向上**抛起——无论佩戴者是平飞、俯冲还是爬升，竖直那一记都朝正上方。
  - **根因**：实测九百〇六 把「击飞」拆成两段——水平走原版 `knockback`（方向 = 使用者→目标，贴脸时退化成运动方向），竖直走 `push(0, lift, 0)`，**方向写死在 +Y**、强度 = 击飞系数 × 35%（上限 1.2）。方向感因此与飞行姿态脱钩。
  - **同类点清单**：全模组对命中目标施加"方向写死的冲量"只此一处（`DaYunSmash#strike` 的 `push(0.0D, …)`），无同类需一并改。
- **② 修法**：把「击飞」还成**一个**冲量，方向 = 佩戴者这一刻的**归一化运动矢量** `u = (vx, vy, vz) / |v|`（**含上下**）。
  水平分量仍走原版 `knockback`（保留"先把目标原有水平速度折半 + 落地小幅弹起"的击退手感），强度取 `knock × hypot(u.x, u.z)`、方向取 `u` 的水平投影；竖直分量用 `push(0, knock × u.y, 0)` 补。
  两段合起来的矢量模长恰为 `knock`——即"一个强度 `knock`、方向 `u` 的冲量"。平飞（`u.y≈0`）不抬升、俯冲往下砸、爬升往上抛。
- **③ 落刀清单（每树一份）**：`DaYunSmash#strike` 换实现（形参加 `vy`）+ 删不再用的 `LIFT_RATIO`/`MAX_LIFT` + 更新类头与方法 javadoc + 调用点传 `vy`；`MaidSmartConfig` 里 `daYunSmashKnockback` 的 `.comment` 改口径；两树 `assets/promaid/i18n/en_us.json` 对应两条映射**改名 + 改英文**（i18n 的 key 就是中文原文，文本变了 key 跟着变）；手册该章「数值」那段改口径（两树源码 + 英文映射）。
- **④ 完成判据**：两树 `javac` **EXITCODE=0**；全套护栏 **FAIL 0**；新文件两树仍只差映射名；打 jar 出包自检 OK；`RELEASES.tsv` 记「第四十九次同号重打」。
- **⑤ 谁验收**：开发者实机——**三种姿态各撞一次**：平飞撞（目标应顺着飞出去、不再上抛）、俯冲撞（往下砸）、爬升撞（往上抛）。
- **⑥ 卡点**：本轮**没有说「部署」**，所以只走到 `WORKFLOW.md` §4 第 7 步（打 jar + 轮次记录），**不部署、不回归**，等开发者开闸。

## 需求/来源
开发者本轮指令（原话）：「给予怪物的速度冲量不应该固定向上，而应该是顺着飞行者运动的方向。」

## 评估
- 属于实测九百〇六 新增玩法的**方向语义修正**；功能本体（破坏范围口径、伤害公式、友方过滤、开关与默认值）一律不动。
- 影响面 = 一个方法 + 一处配置注释 + 手册一段 + i18n 两条（中英）。**不改任何配置项的 key / 默认值 / 开关数量**，故 `check_preset` 的 DIY 基线维持 175/179 不变。
- 保留原版 `knockback` 而非改成纯 `push`：纯 `push` 会保留目标原有速度、少了"被打飞"的折半与落地弹起，手感不像击退；分解成「水平走原版击退 + 竖直补 push」既贴着运动方向，又不丢原版手感。

## 改法
`strike(user, target, damage, knock, vx, vy, vz)`：
1. `speed = |(vx,vy,vz)|`；`speed < 1e-4` → 只吃伤害、不击飞（这一刻没有运动方向）。
2. `u = (vx,vy,vz)/speed`；`horizontal = hypot(u.x, u.z)`。
3. `horizontal > 1e-4` → `knockback(knock × horizontal, u.x/horizontal, u.z/horizontal)`。
4. `|u.y| > 1e-4` → `push(0, knock × u.y, 0)`。

删除常量 `LIFT_RATIO` / `MAX_LIFT`（不再有"固定上挑"）。

## 触及文件
- `promaid_src|_neo/com/maidsmart/accessory/DaYunSmash.java`
- `promaid_src|_neo/com/maidsmart/config/MaidSmartConfig.java`（`daYunSmashKnockback` 的 `.comment`）
- `promaid_src|_neo/assets/promaid/i18n/en_us.json`（2 条映射：配置注释 + 手册「数值」段）
- `promaid_src|_neo/com/maidsmart/guide/GuideChaptersCombat.java`（该章「数值」段）
- `rounds/R907-dayun-smash-knockback-direction.md`、`rounds/RELEASES.tsv`、`ARCHITECTURE-SWITCHES.md`（若含注释文本则重生成）

## 验证
- **编译**：两树 `javac` **EXITCODE=0**。
- **护栏**：全套 **FAIL 0** —— `check_comment_style` PASS、`check_comment_dup` 0/0、`check_dev_voice` 0/0、`check_i18n_keys` 0/0、`check_preset` 0/0（基线未变：**不改开关数**）、`check_panel_coverage` 0/0、`check_reflection` 0/0、`check_workflow` FAIL 0、`check_catch_swallow` 0/0。
- **镜像**：`DaYunSmash.java` 两树逐行对比——**仅 22 行**不同，全是 SRG↔Mojmap 映射名（`isAlive` / `isFallFlying` / `motion.x` / `getX` / `getEntitiesOfClass` / `getUUID` / `hurt` / `knockback` / `push` / `getOwner` / `isTame` / `getOwnerUUID` / `isAlliedTo`）；改后的方向算法两树**一字不差**。
- **打 jar**：`build_promaid.py` / `build_promaid_neo.py` 出包（forge 17581072 B / neo 17619128 B），`lang json: OK`、`MISSING: none`；`ARCHITECTURE-SWITCHES.md` 已重生成。
- **反证（方向确实随姿态变）**：`strike` 里 `uy = vy / speed` —— 平飞时 `vy ≈ 0` → **不进竖直分支（不再上抛）**；俯冲 `vy < 0` → `push(0, 负数, 0)`（往下砸）；爬升 `vy > 0` → 往上抛。水平分量方向 `(ux, uz)/horizontal` 即运动方向的水平投影（不再是"使用者 → 目标"）；两段合起来矢量模长恒为 `knock`。
- **未部署 / 未回归**（开发者本轮未开「部署」闸）：`patched/` 已换成本轮字节（forge `56c5b863…eeb4bb` / neo `ddf430c3…a6f703`），本机 4 实例仍是实测九百〇六 那一版。

## 验收
- 状态：**`未验收`**（等开发者实机）。
- 怎么试：**三种姿态各撞一次**——平飞撞向怪物（目标应顺着飞出去、**不再上抛**）、俯冲撞（目标往下砸）、爬升撞（目标往上抛）。
- 日志：`logs/promaid.log` 搜 `DaYunSmash`（只在异常时留痕）。

## 边界（没动什么）
- 不动实测九百〇六 的其余部分：破坏范围口径（`DaYunBreaker#coveredCenters`）、伤害公式与两个系数、友方判据、开关与默认值。
- 不改版本号（仍 **1.3.2**）、不写版本更新内容 / release 说明（§6.1：未验收前一个字都不写）、不动 `store/` 与 `_tmp_pub/`。
- 本轮**不部署、不回归**（开发者未开闸）：打出的 jar 只进 `patched/`，本机 4 实例仍是实测九百〇六 那一版的字节。
- 本轮**未清** P0/P1 债（BD-04 仍 `未清`）——这是开发者裁定的优先级，`check_workflow` §8 配额的那条 WARN 仍在，如实留痕。

## 台账
- 无新增债、无清债（纯方向修正，不涉及新增开关与债务）。
