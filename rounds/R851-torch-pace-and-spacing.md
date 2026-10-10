# 实测八百五十一 插火把：加间隔（0.5 秒 → 2 秒）+ 别挨着已有火把再插（真实光源盘点）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）
- **跑道**：A 修 bug（实测八百四十七 刚交付、未验收即被实机打回两条）。
- **开工前读过**：`R847`（同一功能的上一版：旧 `envBlockLight` 的上界掩码正是本轮真凶的出处）· `MaidHeldLight` 类注释（「此亮度不影响插火把」原始需求）· `MaidSpawnerTorchBehavior`（防刷怪链路的去重走 `isOwnLightBlock` + 方块自身发光值，不吃这套判据）· `ARCHITECTURE-SWITCHES.md`（`combat.torchPlacerEnable` / `combat.torchDarkThreshold` 已有，本轮不加新项）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 侦察：定位"连插"的冷却值 / "挨着火把插"的判据；两树 grep 调用点 | 根因指着代码行；`envBlockLight` 调用点全清单（仅两棵树各一把 placer） | ☑ |
| 1 | 根因 + 同类点处置 | 根因② = 上界掩码；同类点 = 防刷怪链路（不吃这套，写明不动） | ☑ |
| 2 | 两树改 + 编译 | `EXITCODE=0` | ☑ |
| 3 | 护栏 + 打包 + 桌面快照 + `RELEASES.tsv` 留痕 | 四护栏回基线 | ☑ |
| 4 | 交开发者验收 | 开发者实测"有间隔了、不挨着插了" | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者；实机看她插火把的节奏与落点；日志搜「插火把」看 `noSpot` 那行会不会写明"已被真实光源照到 N 个"。

## 需求 / 来源
- 开发者本轮实机反馈两条：①"插火把的速度太快了，一点间隔都没有"；②"会在火把旁边插火把，太过浪费了"。（承接实测八百四十七 的被动插火把功能。）

## 评估
- **跑道**：A。级别 P1（材料浪费 + 观感；功能本体可用）。
- **根因三问**：
  - 复现（对着代码可推、与实机现象一致）：她举着光源时，主人 5×5 里被她自己脚底那颗隐藏光块照到的格子，世界读数恰好等于"她自己那一份" → 旧 `envBlockLight` 直接按 0 计（"环境无光"）→ 25 格几乎全判"暗" → 每 0.5 秒按扫描顺序（左上→右下）插一根，下一根必然紧挨上一根；她刚插下的**真火把**也被同一套掩码抹掉，于是永远"这里还是暗的"。
  - 根因①（无间隔）：`torchCooldown = 10`（0.5 秒）；夜晚/地下 25 格里总有够不到阈值的格子 → 连插。
  - 根因②（挨着火把插）：`MaidHeldLight.envBlockLight` 的 `raw <= hers → 返回 0`——把"她自己的光"与"真实光源"混在同一个上界里，凡是"离她比离真实光源近"的格子一律按 0。
  - 同类点：`envBlockLight` 全树只有两把 placer 调用；防刷怪链路（`MaidSpawnerTorchBehavior`）用方块自身发光值 + `isOwnLightBlock`，**不受影响、本轮不动**。
- 先查 `ARCHITECTURE-SWITCHES.md`：开关与阈值已有；间隔不做成新配置项（开发者没要；要可调再开一轮）。

## 改法
- `MaidHeldLight`（两树）：删掉旧 `envBlockLight`（上界掩码 = 真凶），换三件只读工具：
  ① `ownLightUpperBound(maid, level, p)`——她自己的隐藏光块在某格的光度上界（0 = 没在发光）；
  ② `record RealSource(pos, emission).covers(p, need)`——"这颗真实光源在曼哈顿口径下还能给某格留下 ≥ need 的光"（忽略遮挡的近似，偏保守：宁可少插也不浪费）；
  ③ `realSourcesAround(maid, level, centre, reach)`——盘点周围真实发光方块（**跳过她自己那颗隐藏光块**；玩家火把、炉子、萤石、别的女仆的光块都算）。
- `MaidTorchPlacerBehavior`（两树）：
  ① `torchCooldown` 10 → **40 tick（2 秒）**；
  ② 新增 `SCAN_GAP = 10`：每 0.5 秒才排一次扫描（旧版每 tick 扫 25 格；现在扫描里多一次盘点，必须限频）；
  ③ 扫描重写：`raw`（世界方块光）与 `own`（她的上界）分开取——`sky ≥ 阈值` 或 `raw ≥ 阈值`（读数可信时）直接跳过；读数可能全是她自己的光的格子进 `masked`，等真实光源盘点定夺（被真实光源覆盖 → 不插；否则按暗格候选、天光估亮度）；目标照旧取"最暗的可放格"；
  ④ 诊断文案补一档：`周围有 N 个暗格：放不了 M 个、已被真实光源照到 K 个 → 这一轮不插`。
- 被否掉的方案：a) 只加大冷却（治不了"挨着火把插"，还把"她自己的光不该挡住插火把"这条需求推翻）；b) 让她的隐藏光块整体不计入世界（破坏"她拿着火把照亮"这个功能本身）；c) 逐格爆破式搜光源（每格数百次方块查询）——改成一轮一次盘点。

## 触及文件
- `promaid_src{, _neo}/com/maidsmart/tool/MaidHeldLight.java`（`envBlockLight` → `ownLightUpperBound` + `RealSource` + `realSourcesAround`；类注释同步）
- `promaid_src{, _neo}/com/maidsmart/combat/MaidTorchPlacerBehavior.java`（冷却 40、扫描节奏闸、扫描重写、诊断文案、类注释）
- 新配置项 0 / 新 i18n key 0 / 新 mixin 0（留痕串是日志，不算 UI 文案）。

## 验证
- 两树 `javac EXITCODE=0`。
- 四护栏回基线：`check_comment_style` 929 文件 / **2258 处**（`{'LONG-BLOCK': 2258}`，QUOTE-SPEECH 0）· `check_preset` FAIL 0 / WARN 2 · `check_workflow` FAIL 0 / WARN 2 · `check_changelog` **1261 处**（均与开轮前一致，不增）。
- 打包 = 同号**第 14 次**重打包（与 R849 同一份 jar）：`patched/promaid-1.3.1-forge-1.20.1.jar` = `a5a0a83e…d0a55d`、`patched/promaid-1.3.1-neoforge-1.21.1.jar` = `4ac01070…8ecfbe`；jar 自检（mixin 96 个类全部登记、注入签名 OK、out 760 class 全在 jar）、`lang json: OK`、`MISSING: none`；桌面快照 `Promaid_本体当前` 已刷新。
- **反证**：本轮改动为静态推演（对着代码逐格算光照：她自己贡献 = 上界、真实光源走盘点）；未做实机对照（等开发者）。
- 未做的验证：**未部署**（等开发者说「部署」）· **未跑专服回归** · 未写 changelog（等验收，`WORKFLOW.md` §6.1）。

## 验收（谁说了算：开发者 —— `WORKFLOW.md` §4.1）
- 状态：`未验收`。等开发者实机看：插一根后有间隔；不再挨着已有火把/她自己刚插的那根重复插。

## 边界（没动什么）
- 不动防刷怪链路；不动 `TORCH_IDS` 与阈值语义；不加配置项；不动"包里优先、不搬动槽位、放下 + 扣 1 + 挥手一次"这套（实测八百四十七 已定）。
- 已知近似：`RealSource.covers` 用曼哈顿距离、不看遮挡——隔着墙也算"照到"（偏保守）。写进注释，不进 changelog。

## 台账
- 清掉：无编号（实测八百四十七 的 P1 回归，不重复记账）。
- 新增债 0。
