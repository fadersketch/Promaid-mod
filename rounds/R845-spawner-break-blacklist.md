# 实测八百四十五 · 「空刷怪笼等一律不拆」独立黑名单（两树）

> 流程见 `WORKFLOW.md`；本轮的 `## 排班` 是开工前置的硬门禁。

## 需求 / 来源

开发者原话（本轮起因）：

> 女仆还是会主动破坏空刷怪笼。现在我也不强求了。你给空刷怪笼列一个单独的黑名单吧，让它不会被女仆破坏。

转写成事实陈述：**空刷怪笼（以及只刷和平生物的笼子）必须绝对不被女仆破坏**——不再依赖"现在没在刷怪"
那套启发式判定（实测八百三十 / 八百三十b / 八百三十七），改用一个**独立的、写在配置里的黑名单**，
命中即永不拆除。

## 评估（先把"为什么现有的『空笼子不拆』没生效"坐实）

**跑道**：A 修 bug（现象 = 与既有承诺不符：配置注释与轮次记录都写着"空笼子不拆"，实机仍被拆）。

**证据（代码对照，两树同形）**：`MaidSpawnerTorchBehavior.spawnSkipReason`（Forge 树 L665）的判定顺序是

1. `hostileNear(…, 12 格)` → 命中就 `return null`（= **该拆**）；
2. 不在激活窗口 → 不拆；
3. 读 NBT 刷怪表：HOSTILE → 拆 / BENIGN → 不拆 / EMPTY → 不拆 / UNKNOWN → 拆。

即：**第 1 条压在第 3 条前面**。一只空笼子只要周围 12 格内有任何敌对生物在游荡（洞窟里几乎总有），
`hostileNear` 先命中 → 直接判"该拆"——**笼子自己是不是空壳根本没被读到**。这与开发者的实机现象
（"还是会主动破坏空刷怪笼"）完全吻合；也解释了为什么实测八百三十b 加了"空笼子不拆"却仍然被拆。

**同类点清单（一并处置）**：

| 点 | 现状 | 处置 |
|---|---|---|
| 空笼子（EMPTY）被 `hostileNear` 短路拆掉 | 本次报告的现象 | **进默认黑名单**（永不受其它判据影响） |
| 只刷和平生物的笼子（BENIGN）同样被 `hostileNear` 短路拆掉 | 与"只刷牛羊猪的不拆"承诺不符 | **一并进默认黑名单**（同一条缺口） |
| 读不出刷怪表（UNKNOWN，模组笼子） | 现在按"在出货"处理 = 拆 | **不进默认黑名单**（保持原口径：宁可多拆不可漏拆）；需要的人可在黑名单里写 `unreadable` |
| 挖矿开路 / 连锁采集 | `spawner` 不在 `OPEN_BREAKABLE`；只有玩家自己把 `spawner` 填进 `mine.extraBreakables` 才会挖 | 不动（那是玩家显式要求挖它）；写进 §边界 |
| 饰品「大运」飞行扫方块 | `accessory.daYunSkipContainers` 默认**开** → 带方块实体的刷怪笼本来就被跳过 | 不动（默认已安全）；写进 §边界 |
| 轰炸/爆炸破坏地形 | 默认"不破坏方块"；打开 = 玩家显式要原版爆炸 | 不动（显式 opt-in） |

**已有等价开关？** 没有。`ARCHITECTURE-SWITCHES.md` 里与刷怪笼相关的只有 `spawnerTorch` 那 6 个开
（enable / radius / priority / breakSpawner / breakRequirePickaxe / breakOnlyWhenActive），**没有**"永不拆某些笼子"的名单；
本轮的 `breakBlacklist` 是新的第七个，语义独立（与 `breakOnlyWhenActive` 正交：那个是启发式判"在不在刷怪"，
这个是硬保证"这些笼子碰都不碰"）。

**是不是已记的债？** 不是 `BUG_DEBT` / `TECH_DEBT` 里的条目；是开发者当场提的新需求（且明确"不强求启发式，
直接上黑名单"）。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 0 | 侦察 | 读 `spawnSkipReason` 的判定顺序，定位"空笼子仍被拆"的那一处短路 | 本文 §评估（含行号） | ☑ |
| 1 | 侦察 | 列全部"能动刷怪笼"的路径（拆/挖穿/大运/爆炸），逐条定处置 | 本文 §评估 同类点表 | ☑ |
| 2 | 定口径 | 黑名单的取值语义（`empty` / `benign` / `unreadable` / 具体实体 id）、默认值、与既有开关的关系 | 本文 §改法 | ☑ |
| 3 | 实现 | 新增配置项 `combat.spawnerTorch.breakBlacklist`（默认 `["empty","benign"]`）+ 注释 | 两树 `MaidSmartConfig.java` | ☑ |
| 4 | 实现 | 命中黑名单的笼子**在任何判据之前**直接"不拆"（含 `breakOnlyWhenActive` 关掉时也生效）+ 独立留痕句 | 两树 `MaidSpawnerTorchBehavior.java` | ☑ |
| 5 | 实现 | 面板加一行（分类页 `spawnerTorch` 段内），与既有 6 行并列 | 两树 `PromaidConfigScreen.java` | ☑ |
| 6 | 镜像 | Neo 树逐文件对齐（SRG↔Mojang；纯 Java/字符串逐字节一致） | 引用比对 | ☑ |
| 7 | 验证 | 两树 `javac EXITCODE=0`；护栏问题数**不增**；打包 `MISSING: none` | 构建输出 | ☑ |
| 8 | 交付 | 打包 + 桌面快照 + `RELEASES.tsv` 同号留痕；**部署/回归待开发者开闸** | 三处 | ☑ |
| 9 | 留痕 | 本文 §验证 / §验收 / §边界 / §台账 | 本文 | ☑ |
| 10 | 验收 | 交开发者实测（空笼子旁边放怪，看她拆不拆） | 等开发者 | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法：① 在她附近放一只**空刷怪笼** + 召几只僵尸（让 12 格内有敌对生物，
  即旧版必拆的条件）→ 她**不得**去拆；② 日志搜 `刷怪笼` 应出现"在黑名单里 → 永不拆"一类句子，
  **不**应出现"发现刷怪笼 … → 过去拆掉"；③ 换一只刷僵尸的笼子（非空）→ 应**照旧拆**（黑名单不能误伤）。

## 需求 / 来源之外

- 不动 `WORKFLOW.md` §6.1；本轮的可见文案增量只有**配置项注释**（面板本身不做 i18n，故**无新 i18n key**）。

## 改法（定稿）

**① 新配置项 `combat.spawnerTorch.breakBlacklist`**（两树 `MaidSmartConfig.java`，字段声明紧挨
`COMBAT_SPAWNER_BREAK_ACTIVE_ONLY` 之后）：

- Forge 树 L1086-1088 / Neo 树 L720-722：
  `public static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> COMBAT_SPAWNER_BREAK_BLACKLIST;`
  （Neo 树为 `ModConfigSpec.ConfigValue<...>`，其余逐字节相同）。
- 定义在 `COMBAT_SPAWNER_BREAK_ACTIVE_ONLY` 的 `define` 之后、`BUILDER.pop();` 之前
  （Forge L2876-2879 / Neo L2930-2933）：
  `.translation("config.promaid.spawnerTorch.breakBlacklist").defineList("breakBlacklist", java.util.List.of("empty", "benign"), o -> o instanceof String s && !s.isEmpty());`
  默认 `["empty","benign"]`；校验器只要求是**非空字符串**（词表由行为侧解释，写错了只当不命中，不会崩）。
- 注释里写清四种写法（`empty` / `benign` / `unreadable` / 一个实体注册名 `minecraft:zombie` 或 `zombie`）
  与"写空 = 名单不生效"。**面板本身不走 `PromaidI18n`**，且 `config.promaid.spawnerTorch.*` 这一族
  translation 键在 `en_us.json` 里**本来就不存在**（既有五项也没有），故本项**无需新增 i18n 键**。

**② 硬保证落在行为侧**（两树 `MaidSpawnerTorchBehavior.java`）：

- 命中点插在 `doTick` 里——紧接 `inHomeWorkArea(...)` 那一块之后、`if (breaking && activeOnly())` 那一支**之前**
  （Forge L538-555 / Neo L570-587）。于是**黑名单的优先级高于 `spawnSkipReason` 的第①条 `hostileNear`**——
  这正是开发者报的现象的根因（空笼子旁边有敌对生物游走时被当"在出货"拆掉）。
- 只在 `breaking`（= 拆除模式）时判：插火把模式不破坏方块，不受影响。
- 命中即 `aim.coolPos = found; aim.coolUntil = gameTime + RETRY_COOLDOWN; return;`，并写一条独立留痕
  `刷怪笼 <坐标> 在黑名单里（命中「<那一条>」） → 永不拆，留着`（`SKIP_LOG_GAP` 节流）。
- 新私有方法 `breakBlacklistHit(ServerLevel, BlockPos) -> String`（Forge L675-731 / Neo L707-763）：
  返回**命中的那一条原文**（供留痕），否则 `null`。名单里出现 `empty`/`benign`/`unreadable` 才**懒读** NBT
  （`spawnThreat(...)`，复用既有判定），否则一次都不读；其余词按实体注册名匹配（全等或 `:` + 名，
  大小写不敏感，`minecraft:zombie` 与 `zombie` 都行）。整段 `try/catch (Throwable)` 兜底——
  读配置/读 NBT 出岔子一律当"没命中"，绝不让黑名单把整条链路拖停。
- 三处既有注释顺带对齐：`COMBAT_SPAWNER_BREAK` 的"另有两处修正"改写成"**另有三道修正**"（把黑名单列为
  其中一道硬保证）；`COMBAT_SPAWNER_BREAK_ACTIVE_ONLY` 的说明补一段【空笼子 / 只刷和平生物另有独立硬保证】，
  点明"黑名单压在本条所有判据之前，本条关掉也照样生效"。

**③ 配置面板加一行**（两树 `PromaidConfigScreen.java`，`spawnerTorch` 段内、`ACTIVE_ONLY` 那一行之后）：
Forge L4588-4590 / Neo L4610-4612，`new TextRow("防刷怪·拆除黑名单（永不拆）", String.join(",", ...), s -> setStringList(...), "…")`，
提示文字写清四种写法、逗号分隔、"写空 = 这份名单不生效"、默认 `empty,benign`。

**④ 台账联动**：`ARCHITECTURE-SWITCHES.md` 重新生成（`python tools/verify/gen_switch_inventory.py --write`，1356 行），
否则 `check_workflow` 会报"配置清单已过期"。

## 触及文件

| 文件 | 改动 |
|---|---|
| `promaid_src/com/maidsmart/config/MaidSmartConfig.java` | +1 字段、+1 defineList、2 处既有注释 |
| `promaid_src_neo/com/maidsmart/config/MaidSmartConfig.java` | 同上（`ModConfigSpec`） |
| `promaid_src/com/maidsmart/combat/MaidSpawnerTorchBehavior.java` | +命中分支、+`breakBlacklistHit` |
| `promaid_src_neo/com/maidsmart/combat/MaidSpawnerTorchBehavior.java` | 同上（`m_7702_`/`m_187480_` ↔ `getBlockEntity`/`saveWithoutMetadata`） |
| `promaid_src{, _neo}/com/maidsmart/config/PromaidConfigScreen.java` | +1 面板行 |
| `ARCHITECTURE-SWITCHES.md` | 重新生成（含新开关） |
| `rounds/RELEASES.tsv` | 同号重打包留痕（见 §台账） |

## 验证

- **两树编译**：`tools/build/gen_compile.py` + `compile_promaid.bat` → `EXITCODE=0`；
  `gen_compile_neo.py` + `compile_neo.bat` → `EXITCODE=0`。
  （中途踩过一次 Java 字符串里放 ASCII `"` 的坑——已改用「」，两树重编均 0。）
- **护栏不增**：`check_comment_style` 929 文件 / 2258 处（与基线同）；`check_changelog` 1261；
  `check_preset` FAIL 0 / WARN 2；`check_workflow` FAIL 0 / WARN 2（重新生成清单前曾多出 1 条
  "配置清单已过期" WARN，已消除）。
- **打包**：`build_promaid.py` / `build_promaid_neo.py` → `lang json: OK`、`MISSING: none`，
  桌面快照 `Promaid_本体当前` 自动刷新。
- **没有部署、没有跑回归**——开发者没说"部署"，按 `WORKFLOW.md` §4 只做到第 7 步。

## 验收

**未验收** —— 等开发者实测。

## 边界

- **不动**：挖矿开路 / 连锁采集（`spawner` 不在 `OPEN_BREAKABLE`，要玩家自己填 `mine.extraBreakables` 才挖）、
  饰品「大运」飞行扫方块（`accessory.daYunSkipContainers` 默认开，带方块实体的笼子本就被跳过）、
  轰炸/爆炸拆方块（默认不破坏，打开是玩家显式要原版爆炸）。这三条都不是"女仆自主拆笼子"，与本轮无关。
- **`unreadable` 不进默认名单**：读不出刷怪表时仍按"在出货"处理（宁可多拆不可漏拆）——这是沿用既有口径，
  想连模组笼子一起留的人自己往名单里写 `unreadable`。
- **不新增 i18n 键**：配置面板不走 `PromaidI18n`（面板是中文专用），且 `config.promaid.spawnerTorch.*`
  这一族键在 `en_us.json` 里本来就没有。
- 临时脚本全部跑完即删，未留 `tools/_*`。

## 台账

- **`RELEASES.tsv`**：1.3.1 同号第 10 次重打包（R845）+ 第 11 次（R846 补口径），两行的 sha256 已改为
  当前字节（forge `7527bd74…d7d98b`、neoforge `709ced7b…a57beb`），旧字节逐条列为"作废"，
  备注写明"**尚未部署、未回归、未验收**"。版本号按开发者声明**不升**（仍 1.3.1）。
- **已验收的历史**：1.3.1 上一版（`861ffdcd…e2704b` / `e58a4ca1…c605d5`）是 2026-10-10 开发者开闸验收的那一份；
  本轮两件（R845/R846）都在其**之后**，故仍需重新验收。
