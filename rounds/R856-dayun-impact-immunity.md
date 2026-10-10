# 实测八百五十六 「大运」新增：佩戴者免疫鞘翅飞行冲撞伤害（两树）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：**加功能**（`WORKFLOW.md` §1 表第一行：改行为 → 两树镜像 + 手册/i18n/面板/配置注释/lang 全链路同步）。
- **开工前读过**：`WORKFLOW.md` §0~§4（跑道表、§3.1③ 新开关要登记、§6 文案纪律：可见文案禁 `实测NNN`）·
  `ARCHITECTURE-SWITCHES.md`（配置清单；本轮要重生成）· `TECH_DEBT.md`（TD-11 DIY 语义：行为开关默认开、
  DIY 自动关；TD-03 文案口径）· `BUG_DEBT.md`（无相关项）· `rounds/RELEASES.tsv`
  （1.3.1 现字节 = 第十八次同号重打，forge `5bc98ccf…bc750e` / neo `6afc03b2…f70a81`，已部署@2026-10-10）·
  上一轮 `rounds/R855-visible-copy-slimming.md`（面板注释 ≤100 字新规）·
  `DEV_GUIDE.md` §1 注释口径 / §5 i18n 联动 / §6 护栏 ·
  现成实现（**本轮全部复用，不另造**）：
  - `combat/MaidFlightWallGuard.java`——已经管女仆的 `fly_into_wall`（按 `getMsgId()` 判，跨版本稳定）；
  - `accessory/DaYunPlayerHook.hasDaYun(ServerPlayer)`——玩家侧"戴没戴"（Curios 全反射）；
  - `accessory/DaYunWallPhase.wearsDaYun(EntityMaid)`——女仆侧"戴没戴"（TLM 饰品栏扫描）。
- **需求**（开发者原话）：
  > 给大运加个功能，让佩戴者免疫鞘翅飞行时的冲撞伤害
  即：**凡佩戴「大运」者（玩家 / 女仆），在鞘翅滑翔中免疫原版撞墙伤害（`fly_into_wall`）**。
- **口径（转写成可判定规则）**：
  1. **只认「佩戴者 + 在滑翔」**：判据 = `isFallFlying()` 且（玩家走 `hasDaYun` / 女仆走 `wearsDaYun`）。
     不要求女仆处于空袭任务——"佩戴者"就是佩戴者本身（与既有那条"女仆在飞任务才免撞"是**两道独立闸**）。
  2. **只免疫冲撞（`fly_into_wall`）**，不碰 `fall`（摔落有自己的开关 `combat.flightNoFallDamage`）、
     不碰其它任何伤害类型。
  3. **默认开**，并**新增一条配置开关**（`accessory.daYunNoWallDamage`）——按 §3.1③ 登记；响应 DIY：
     与「总开关 / 玩家侧」同档**随 DIY 关**（DIY 不动 `KEEP` 表，护栏会自动把它算进"DIY 关闭 N 项"）。
  4. **只认服务端**：客户端也取消会造成"本地不掉血、服务端照扣"的错位。
  5. 可见文案（配置注释 / 面板行 / 手册 / 物品说明）一律**不带轮次号**、面板注释 **≤100 字**（R855 新规）。
- **工作队列**（逐步，做完就勾；每步写"完成判据"）：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 侦察 | 大运 8 个类 / 现有 `fly_into_wall` 闸 / 佩戴者判据 / 手册章节 / 物品说明 / lang 四处全部看清（见本文件 §口径） | ☑ |
| 1 | 排班 | 本文件写完（就是本节） | ☑ |
| 2 | 配置项 | `DAYUN_NO_WALL_DAMAGE`（`accessory.daYunNoWallDamage`，默认 true）+ comment + translation；两树同名字段 | ☑ |
| 3 | 判定落地 | `MaidFlightWallGuard` 加一条前置闸 + 私有助手（玩家 Curios / 女仆 TLM 饰品栏），**不动**既有女仆飞行链路 | ☑ |
| 4 | 面板 | `daYunRows()` 末尾加 `SectionRow` + `BoolRow`（注释 60 字 ≤100） | ☑ |
| 5 | DIY | `MaidConfigPreset` 的 `KEEP` 注释更新；`check_preset` 基线 150/154 → 151/155 | ☑ |
| 6 | 手册 + 物品说明 | `daYunGuide()` 新增「四、佩戴者免疫撞墙伤害」并把原四/五顺延为五/六；`DaYunItem` 加第 5 行 tooltip | ☑ |
| 7 | i18n + lang | 两树 `en_us.json` 净 +7 键（新增 5 条 + 手册 2 条）并改名 2 条（含英文 value）；两树四份 lang 加 `desc5` | ☑ |
| 8 | 验证 | 见 §验证（编译 / 护栏 / 两树一致性 / jar 自查 全过） | ☑ |
| 9 | 打包 | 同号第 19 次：`MISSING: none` + jar 自查 + `RELEASES.tsv` 留痕（旧字节作废） | ☑ |
| 10 | 交付 | 开发者开闸「部署+回归」后：`deploy_all.py` 4 目标全 `match=True` + 专服回归两加载器各两件全 PASS | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者；验法 = 进游戏戴上「大运」（玩家 Curios / 女仆 TLM 饰品栏）→
  鞘翅起飞 → 故意撞墙：**不掉血、不被打断**；摘掉饰品再撞 → 照原版掉血。
  配置面板「移动与行为 → 饰品「大运」 → 佩戴者的保护」能看到并切换新开关；关掉后撞墙照旧掉血。

## 需求 / 来源

开发者指令（原话）：

> 给大运加个功能，让佩戴者免疫鞘翅飞行时的冲撞伤害

## 评估

- **为什么复用 `MaidFlightWallGuard` 而不是新开一个事件类**：`fly_into_wall` 已经在它手里，判定用
  `getMsgId()`（跨版本稳定，`data/minecraft/damage_type/fly_into_wall.json` 两个版本都是 `flyIntoWall`）；
  新开一个类会让"同一伤害类型有两个 cancel 点"，将来排查"到底谁拦的"要多看一处。
- **为什么与新开关独立于 `DAYUN_ENABLE`（总开关）**：总开关的语义是"清方块"那一件事（注释里写的就是它），
  BGM 也早已独立于总开关（`实测八百零五`）。免疫是**保命**档，跟"要不要铲自家屋顶"是两回事，
  所以只认它自己那条开关。
- **为什么必须只认服务端**：玩家血量以服务端为准；客户端取消会让本地显示与服务器真相不一致。
- **不做什么**：不改 `fall`（摔落）那条既有开关的语义；不改破坏引擎（`DaYunBreaker`）与 BGM；
  不动既有女仆飞行链路（`MaidFlightKit.isFlightTask` / 跟随 / 收手）的一行判定。

## 改法

见 §排班 的队列 2~7。要点：
- 两树镜像：纯 Java 部分（新开关字段之外的结构）逐字节一致；MC-API 处按 SRG↔Mojmap 替换
  （`m_19385_`/`getMsgId`、`m_21255_`/`isFallFlying`、`m_9236_().m_5776_()`/`level().isClientSide()`、
  `LivingAttackEvent`/`LivingIncomingDamageEvent`）。
- i18n 表：新增 key 用**源码里那串中文逐字节**做 key；改名（手册四→五、五→六）用"旧 key 换成新 key +
  英文 value 跟着改"，写回沿用"不截断、尾部空格补齐"的机制（文件被映射锁住）。

## 触及文件

- 两树 `com/maidsmart/config/MaidSmartConfig.java`（+1 字段 +1 builder 条目）
- 两树 `com/maidsmart/combat/MaidFlightWallGuard.java`（+1 前置闸 +2 私有助手，类注释补一段）
- 两树 `com/maidsmart/config/PromaidConfigScreen.java`（+1 分区 +1 开关行）
- 两树 `com/maidsmart/config/MaidConfigPreset.java`（`KEEP` 注释更新）
- 两树 `com/maidsmart/guide/GuideChaptersCombat.java`（手册章节 +1 段 + 顺延编号）
- 两树 `com/maidsmart/accessory/DaYunItem.java`（+1 行 tooltip）
- 两树 `assets/promaid/i18n/en_us.json`、两树 `assets/maid_smart/lang/{zh_cn,en_us}.json`
- `ARCHITECTURE-SWITCHES.md`（重生成）、`rounds/RELEASES.tsv`（第十九次字节）、本轮记录

## 验证

- **编译**：两树 `javac EXITCODE=0`（0 错误；Forge 侧仍是既有 deprecation 噪音）。
- **护栏回基线**：`check_comment_style` 929 文件 / 2258 处 · `check_preset` **FAIL 0 / WARN 2**
  （DIY 关闭条数基线按设计上移：150 → **151**、154 → **155**，`check_preset.py` 的 `BASELINE_FLIP` 已同步）·
  `check_workflow` FAIL 0 / WARN 2 · `check_changelog` 1261。`gen_switch_inventory.py --write` 重生成，
  清单里能看到 `daYunNoWallDamage`，末尾「带轮次编号的可见文案」仍 0/0。
- **面板**：422 行口径重扫，注释 >100 字 = **0**；新行 `免疫冲撞伤害` 的拼接注释 **60 字**，
  且 label / 分区标题 / 拼接注释**三条都是 i18n 表里的 key**（R855 建立的口径）。
- **i18n**：Forge **7002** / Neo **6999** 键（本轮净 +7：新增 5 条可见文案 + 手册新节 2 条，
  另把手册「四、怎么调」「五、常见问题」改名成「五」「六」，英文跟着改成 `§e5. …` / `§e6. FAQ§r`）；
  表内 key 含「实测」= **0**。
- **lang**：两树 × zh_cn / en_us 四份都加了 `item.maid_smart.da_yun.desc5`（物品说明第 5 行）。
- **两树一致性**：`MaidConfigPreset.java` / `GuideChaptersCombat.java` / `PromaidConfigScreen.java`
  本轮改动区**逐字节一致**；`MaidFlightWallGuard.java` 的新助手块在 SRG↔Mojmap 归一后**差异 0 行**
  （只差 `m_19385_`↔`getMsgId`、`m_21255_`↔`isFallFlying`、`m_9236_().m_5776_()`↔`level().isClientSide()`、
  `m_7755_().getString()`↔`getName().getString()` 与事件类型/方法名）。
- **jar 自查（打包后按字节）**：两个 jar `MISSING: none`、`verify_jar_classes` OK（760 / 764 个 class
  全在、mixin 登记与注入签名 OK）；**全条目搜「实测」= 0**、无 `.new/.tmp` 类临时条目；
  `MaidSmartConfig.class` 里能搜到 `daYunNoWallDamage`、`MaidFlightWallGuard.class` 里有 `flyIntoWall`；
  jar 内 i18n（7002 / 6999 键，新键齐）与四份 lang（`desc5` 齐）都在。
- **打包**：同号**第 19 次**——Forge `9fe83cde…21c8a5`（17,559,650 字节）/
  Neo `a39bb4f4…b3dd0e`（17,598,350 字节）；`RELEASES.tsv` 已登记并把第十八次字节
  （`5bc98ccf…bc750e` / `6afc03b2…f70a81`）作废留痕；桌面快照随包自动同步。

- **部署（开发者开闸「部署+回归」后执行）**：`tools/deploy/deploy_all.py` 4 个目标全部 `match=True`——
  `1.21.1-NeoForge_21.1.250` 17,598,350 B、`1.20.1-Forge_47.4.10` / `龙之冒险：新征程v2.4a` /
  `mc_server_test\pack1201` 各 17,559,650 B；旧包备份进 `patched/backup_old/`；
  另按 sha256 复核：4 个落地 jar 与 `patched/` **逐字节一致**（forge `9fe83cde…21c8a5` / neo `a39bb4f4…b3dd0e`）。
- **专服回归（跑的就是这一包）**：`test_server.py 1201` **PASS**（`Done (3.296s)`）·
  `test_maid_load.py 1201` **PASS** · `test_server.py neoforge1211` **PASS**（`Done (1.133s)`）·
  `test_maid_load.py neoforge1211` **PASS**；四个日志里与 promaid 相关的 ERROR / Exception = **0**。

## 验收（谁说了算：开发者 —— `WORKFLOW.md` §4.1）

- 状态：`未验收`（**已部署@2026-10-11、已回归@2026-10-11**；等开发者戴上饰品撞一次墙给结论）。

## 边界（没动什么）

- 不动 `fall`（摔落）那条既有开关的语义，也不动破坏引擎 `DaYunBreaker` 与 BGM；
  `DaYunWallPhase` / `DaYunPlayerHook` **本体一行未改**（只被新闸调用）。
- 既有女仆飞行链路（`MaidFlightKit.isFlightTask` / 跟随 / 收手）的判定**一行未改**——新闸是前置独立闸。
- 新开关**不进** `KEEP`（随 DIY 关，与「总开关 / 玩家侧」同档）；`check_preset` 基线已同步，非"漏关"。
- **changelog 一个字没写**（按 §6.1，等验收）。
- 已知边界（写进手册读者能看到的形状）：玩家侧需要装 Curios 才认得出「戴着」；免疫只覆盖
  `fly_into_wall`（撞墙），不含摔落伤害；只认服务端。

## 台账

- 不新增债；`TD-11`（DIY 逐表裁决）不受影响——新开关按既有口径随 DIY 关、`KEEP` 表未动。
- 未清债（本轮与债无关）：无。
