# 实测九百二十一 工作圈钳制不再把"人在圈外"也钳死：旁边就是煤矿石却不挖的真正原因

> 跑道：**B 功能抢修**——`WorkAreaClamp.allowsWork` 在"非 home + 玩家用罗盘画过圈"时把**所有**干活选点钳进那个圈；玩家把圈留在老家、人跟着主人跑出几十上百格后，圈内外一块矿都选不出来 → 挖矿扫描永远空 → 她站着报「附近没有值得挖的矿石」，**旁边就是煤矿石也不挖**。改法：她本人**水平**已在圈外时不再钳制（在哪就在哪干活），圈内口径一字不变。
> 开发者指令：「你搞错了吧。当时他旁边明明是煤矿石，但他却站在那边不动了。」（上一轮 R920 定位错了：那轮补的是"模组矿石不在名单里"，与本例无关，另记。）
> **已被取代（实测九百二十二）**：本行那条"她本人水平已在圈外才放行"的启发式，已由 `rounds/R922-work-clamp-home-only.md` 整段换成**按模式分**（home 才管圈、跟随一律放行）——开发者指出根子在"home 与跟随没分开"。本行的存档实证与"她为什么站着不动"的链条仍然成立，只是修法不够正。
> 上一轮：`rounds/R920-mine-ore-tag-auto.md`（矿石标签自动识别——真改动、但**不是**本例的病因）。

## 排班

- **本轮唯一主项**：让"人被带到圈外"时干活选点不再被工作圈钳死。驾驶 / 战斗 / 建筑 / 矿表 / 数值一律不动。
- 步骤与完成判据：
  1. **把病因钉死在存档实证上**（不是猜）：从女仆实体 NBT 读出 `player_circle` / `home` / 三锚点 / 她的坐标 / `scheduleActivityRange`，代入 `allowsWork` 算一遍。判据：能算出"框内每一格都被否掉"。
  2. **解释现场的两个反常**：为什么她**能**挖到头顶正上方的煤、却对旁边的煤无反应。判据：指出唯一不查这道闸的路径。
  3. **改 Forge 树** → 4. **镜像 Neo 树**。判据：两树 `javac` `EXITCODE=0`。
  5. **护栏 → 打 jar → 部署 4 实例**。判据：全护栏 `FAIL 0`；4 个目标全部 `match=True`。
- 谁验收：开发者（本轮只部署、不跑回归；`## 验收` 记 `未验收`）。

## 需求 / 来源

- 开发者反馈（第一次给的病因判断被否）：「他旁边明明是煤矿石，但他却站在那边不动了」——矿是**原版煤矿石**（在矿表里），所以病因不在矿表。
- 同族历史：`rounds/R916` / `R917`（抬头兜底、立足点、穿透预算放行可开路方块、去永久排除表、看门狗保留目标）都修过；`R917` 的现场里"同点稍后又能挖到煤"那块煤在她**头顶 5 格**（y=78 对 y=73），当时已是个可疑信号，本轮才追到底。

## 定位：存档实证（`新的世界 (1)` → `entities/r.-3.-2.mca` 女仆 NBT）

用自写的 NBT 解析器读出那只女仆（`id=touhou_little_maid:maid`，`Pos=[-1473.64,79.0,-708.50]`，`MaidTask=maid_smart:mine`）：

| 字段 | 值 | 含义 |
| --- | --- | --- |
| `NeoForgeData.maid_smart_player_circle` | **1** | 玩家用罗盘画过圈（`PlayerCircle.PLAYER_TAG`） |
| `NeoForgeData.maid_smart_auto_anchor` | 0 | 不是自动锚定 |
| `MaidIsHome` | **0** | 非 home = 跟随模式 |
| `MaidSchedulePos` | Work=Idle=Sleep=**(-1547,112,-742)**，`Configured=1` | 圈心（最近的锚点） |
| 她本人 | (-1473,79,-708) | 距圈心**水平 ~74 格**（三维 ~87 格） |
| 配置 `misc.compassPriority` | true | 罗盘优先开 |
| 配置 `misc.scheduleActivityRange` | **12** | 非 home 时的圈半径 |
| `NeoForgeData.maid_smart_chain_drive` | 0 | 没有目标（链路没在驱动她） |

代入 `WorkAreaClamp.allowsWork`：非 home → 看 `PlayerCircle.isPlayerCircle` = 开关 true 且 PLAYER_TAG=1 → **真**；圈心 = 三锚点里离她最近的 = (-1547,112,-742)；半径 = 12。于是对**框内每一格** `c.distSqr(pos) > 12²` → **全部返回 false** → `fullScanOres` / `pickFromCache` / 连锁队列三个入口都跳过每一块矿 → 扫描永远给不出目标。

**两个反常由此一并解释**：
- 她**能**挖到头顶正上方的煤 —— 唯一不查这道闸的入口是 `firstOreAbove`（脚下 ±2 格内、上方 1~10 格）。现场两处成功挖掘都符合：`mine autoCollect pos=(-1458,79,-702)`（她当时脚位约 y=73，矿在头顶 6 格）、上一轮记录的 `(-1468,78,-682)`（头顶 5 格）。
- 她没有目标时 `chain_drive=0` → 链路独占让位 → TLM 的**跟随**逻辑接管，把她带到主人身边（现场 `mine stuck-reset pos=(-1465,73,-693)`，正是跟随走位后的落点）→ 站住不动 → 看门狗 15 秒后判发呆 → `WorkStatusReporter` 报「附近没有值得挖的矿石」。

## 改法（两树镜像）

`WorkAreaClamp.allowsWork`：圈心/半径算出来之后、判定目标之前，**先看她本人**——

```java
int hx = 她脚位.x - 圈心.x;
int hz = 她脚位.z - 圈心.z;
if ((long) hx * hx + (long) hz * hz > (long) r * r) {
    return true;   // 她本人水平已在圈外 → 不钳制，在哪就在哪干活
}
return 圈心.distSqr(pos) <= r*r;   // 圈内口径一字不变
```

- **用水平距离**（不比 y）：圈心常在屋/塔上，往下挖几格就"y 差 > 半径"了；只用水平距离才不会把"在自己圈里往下挖"误判成出圈。
- **为什么这么改**：圈是"她的工作区"，她人在圈里就该守圈（原设计场景：圈画了、home 关了，防她跟着矿越挖越远）；可她**人已被带出圈**时（跟随主人远征 / 收放魂符重放）再钳就是把她钉死——这条闸变成"一块矿都选不出来"。出圈 = 主人明确带她换地方了，就地干活才是对的。
- 两树差异只在 SRG ↔ Mojang 名字（`m_20183_`/`m_123341_`/`m_123331_` ↔ `blockPosition()`/`getX()`/`distSqr()`）。

## 触及文件

- `promaid_src/com/maidsmart/follow/WorkAreaClamp.java`
- `promaid_src_neo/com/maidsmart/follow/WorkAreaClamp.java`
- `rounds/RELEASES.tsv`（两条 1.3.2 `workspace` 行 sha256 + 第六十次同号重打）
- `rounds/R920-mine-ore-tag-auto.md`（加一段更正指引：那轮的病因判断不适用于本例）
- `rounds/R921-work-circle-clamp-when-outside.md`（本文件）

## 验证

- **两树编译**：`compile_promaid.bat` → `EXITCODE=0`；`compile_neo.bat` → `EXITCODE=0`。
- **全护栏**：`check_comment_style` **PASS**（0 处）；`check_preset` / `check_catch_swallow` / `check_i18n_keys` / `check_dev_voice` / `check_comment_dup` / `check_reflection` / `check_arch` / `check_panel_coverage` **FAIL 0 / WARN 0**；`check_workflow` **FAIL 0 / WARN 3**（历史遗留：PUBLISH_GUIDE 版本、1.2.4 归档哈希、13 个更早 jar 未登记）。
- **两个 jar**：`verify_jar_classes` **OK**（mixin 包登记 OK、注入签名 OK；`out` ↔ `jar` class 双向一致）。
- **未跑实机回归**（先停在这，等开发者指挥）。

### 部署（2026-10-13）

- 打 jar：`tools/build/build_promaid.py` → `patched/promaid-1.3.2-forge-1.20.1.jar`（**17582736** 字节，sha256 `F97A0806…EB5DAE`）；`build_promaid_neo.py` → `patched/promaid-1.3.2-neoforge-1.21.1.jar`（**17619126** 字节，sha256 `0ED7554B…6B286F`）。
- 装机：`tools/deploy/deploy_all.py` → 4 个本机实例全部 **`match=True`**（1.21.1-NeoForge_21.1.250 / 1.20.1-Forge_47.4.10 / 龙之冒险：新征程v2.4a / mc_server_test pack1201）；旧包已备份在 `patched/backup_old/`。
- 留痕：`rounds/RELEASES.tsv` 两条 1.3.2 `workspace` 行的 sha256 列更新为新字节，并追加「第六十次同号重打」；R920 旧字节 `AA24D86B…8DEDB5`（forge）/ `9313629B…D34ED3`（neo）作废。
- 到此为止：**未跑回归、未验收、未 commit、未 push**（开发者指令："做完功能先部署，部署完等他指挥"）。

## 验收

- 状态：**未验收**（已部署@2026-10-13：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
  - 复现步骤 1（**本例正解**）：保持"玩家用罗盘画过圈（罗盘优先开）+ 女仆非 home（跟随）"，把女仆带到**离圈几十格以外**的矿洞，站到一块煤/铁旁边 → 开挖矿任务 → 她应当**就地开挖**（日志出现 `mine autoCollect` / `mine chain burst block=…`），不再弹「附近没有值得挖的矿石」。
  - 复现步骤 2（**圈内仍守圈**）：把她放回圈内（水平距圈心 ≤ `misc.scheduleActivityRange`），圈内挖空后她应当**待在圈里等**，不追着圈外的矿越挖越远（原口径不变）。
  - 复现步骤 3（**头顶兜底照旧**）：圈外仍能像以前一样靠 `firstOreAbove` 挖头顶正上方的矿（现在侧面的也能选了）。
  - 判定：步骤 1 就地挖到旁边的煤 = 合格；若还是报「附近没有值得挖的矿石」，请贴该女仆的 NBT 两行（`maid_smart_player_circle` / `MaidIsHome` / `MaidSchedulePos`）与 `logs/latest.log` 里 `mine ` 开头的几行。
- **同一处闸的连带效果**（预期、一并受益）：伐木 / 宰杀 / 农活的选点也走 `allowsWork`，同样不再被"人在圈外"钳死。若发现它们开始跟着主人跑远，请回报——那说明"出圈即放行"这条口径需要再收一档（记入 `DEBT.md` 的判据见下）。

## 边界（没动什么）

- **没动**：圈**内**的钳制口径（一字节不变）；home 模式那条（仍是 TLM 自己的 `isWithinRestriction`）；`allows` / `allowsWander`（闲逛）两条；`misc.compassPriority` / `misc.scheduleActivityRange` / `misc.workStayInCircle` 的默认值与语义；矿表 / 挖掘 / 接近 / 连锁 / 看门狗 / 掉落回收全部既有口径；R920 的 `mine.tagAuto`（保留：它补的是"模组矿石不在名单里"这个**另一个**真缺口）。
- **不新增开关、不改默认值**；版本号仍 **1.3.2**（`WORKFLOW.md` §5 "验收前不升号"）。
- **诚实更正上一轮**：R920 的轮次文件把现场病因写成"矿表只认显式名单"，那是**错的**——本例的矿是原版煤矿石（在表里），真因是本轮的圈钳制。R920 的代码改动本身仍然成立（模组矿石确实看不见），已在 R920 文件顶部加更正指引。
- 临时脚本（`tools/_r921*.py` / `_r920*.py` / `_circ*.py` / `_mca.py` / `_nbt*.py` / `_needle*` / `_minewin*` / `_cat_inspect*` / `_tagscan*` / `_ore*` / `_rel*`）**已删**；仓库根目录既有的 `_*` 文件与 `_tmp_pub` 等未动。

## 台账

- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第六十次同号重打。
- `rounds/R920-mine-ore-tag-auto.md`：顶部加一段更正指引（病因判断不适用于本例），正文不改写以保留当时的推理轨迹。
- `DEBT.md`：本轮不新增条目；若验收时发现"出圈即放行"让工作女仆跟着主人满地图跑，再按 `## 验收` 的判据立新条目（届时首选收成"出圈后仍以她为中心留一个临时圈"，而不是回到把整框否掉的旧口径）。
