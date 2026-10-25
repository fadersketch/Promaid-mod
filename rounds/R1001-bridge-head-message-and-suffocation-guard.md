# 实测一千〇八 搭路误报"头顶被挡" + 头格窒息保护失效

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）
- **跑道**：A 修 bug —— 两条都是实机行为与文案问题，日志/现场可对。
- **开工前读过**：`ARCHITECTURE-SWITCHES.md`（无相关开关）、`DEBT.md`（无等价条目）、`rounds/RELEASES.tsv`（当前 1.3.3 第 140 次重打）、`R999`/`R1000` 记录 —— 结论：与本轮前的 R999 描边、#40 魂符都无关，是搭高/防窒息链路自身。
- **工作队列**（逐步，做完就勾；每步写"完成判据"）：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 定位：拿实机日志对现场 | `latest.log` 12:44:42 `bridge-up stop ... reason=head-blocked`；`promaid.log` 12:03:39 `[窒息] ... in_wall ... 播报` 但无脱出动作 | ☑ |
| 1 | 根因 + 同类点 | 报错文案由"20 秒没垫块"硬编码而来（不看现场）；`antiSuffocate` 只看身体中心；`buildUp`/`placeStep` 会垫 pos+1（自己的上半身/头格） | ☑ |
| 2 | 两树改 + 编译 | `EXITCODE=0`（逐行核对批处理输出的 EXITCODE，不只看进程退出码） | ☑ |
| 3 | 打包装机 | 4 个目标与 `patched/` 同字节（match=True） | ☑ |
| 4 | 交开发者验收 | 开发者实测后给出结论 | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者；验法 = ① 在"超频单平台"复测搭高：若头顶其实有空间，气泡应是中性的"搭不上去，我先下来……"，`promaid.log` 有 `[promaid/搭路] 20 秒没垫出方块：头顶仍有空间（脚下=… 头上1=… 头上2=…）`；② 人为把方块压进她头格（活塞/放块）：应看到她横移一格脱出，`promaid.log` 出现 `[promaid/窒息] … 已横移一格脱出`。

## 需求 / 来源
- 来源：开发者实机反馈（2026-10-17 12:44 与 12:03 两个时段，女仆「大正女仆酒狐」）。
- 一句话需求：搭路 20 秒没进展时不要一律说"头顶被挡住"（现场头顶有空间）；头格被方块压住时要真的触发窒息保护，而不是只播报不动手。

## 评估
- **跑道**：A（P1：误报影响判断；头格窒息会持续掉血）。
- 证据：
  - `latest.log`：`12:44:22 bridge-up start ... owner-dy=5` → `12:44:42 bridge-up stop ... reason=head-blocked` → 同一秒气泡原文。
  - `promaid.log`：`12:03:39 [窒息] … 累计吃到 4.0 点窒息伤害（in_wall）→ 播报`；同一时段没有横移/顶出日志。
  - 代码：`canContinue` 的 20 秒超时直接 `addTextChatBubble("搭不上去了（头顶被挡住）…")`；`stopReason` 直接返回 `"head-blocked"`，都不看方块。
  - 代码：`SelfPreservationBehavior.antiSuffocate` / `MaidWorkBehavior.antiSuffocate` / `BridgeUpBehavior.antiSuffocate` 三份都只判 `bodyInSolid`（身体中心 50% 高度）；`headInSolid`（90% 高度）只用于"要不要进自保会话"。
  - 代码：`SelfPreservationBehavior.buildUp` 与 `BridgeUpBehavior.placeStep` 在"脚下格不是空气"时改垫 `pos+1`——那是她的上半身/头格；顶起稍有滞后就是自己把自己闷住（挖矿/伐木在实测九百五十八已验证过这个坑并改成只垫脚下格）。
- 根因三问：
  - **复现**：① 头顶有空间但 20 秒没垫出块（走位/等导航/脚下是不可覆盖的红石/雪层）→ 文案硬说头顶被挡；② 方块进入头格（自己垫 pos+1 / 活塞 / 旁人放块）→ 身体中心仍在空气 → 不动。
  - **根因**：① 把"没进展"当成"头顶被挡"，文案与现场脱钩；② 防窒息只认半身，且搭高的放置格选择会往自己身上放。
  - **同类点**：三份 `antiSuffocate` 副本全部只判半身；两处搭高（自保 buildUp、搭路 placeStep）都有 pos+1 分支；挖矿/伐木已修正（实测九百五十八），本轮对齐。

## 改法
- **搭高放置格（两树）**：`buildUp` / `placeStep` 一律只垫脚下那一格；脚下格有不可覆盖的方块（非空气非流体，如红石粉/火把/雪层）时不垫（不拆红石、不往身上放），脚下是岩浆时不垫；头顶空间改用 `isSuffocating` 判定（水/火把/树叶等可穿过格子不再误拦）。
- **20 秒超时的文案（两树）**：新增 `headSpaceBlocked`，与 `placeStep` 同一判据。真被实心块占住 → "搭不上去了（头顶被挡住），我先下来……"、`stopReason=head-blocked`；否则 → 中性的"搭不上去，我先下来……"、`stopReason=stuck`。同时把脚下/头上 1/头上 2 三格的坐标与方块名、主人 dy 写进 `logs/promaid.log` 的 `[promaid/搭路]`，下次反馈可直接定位。
- **防窒息（两树）**：自保新增 `escapeSuffocation`（共享），自保 / 挖矿 / 伐木 / 搭路四条链路都调它：头格或身格任一命中实心块就出手；优先横移一格到能完整容纳碰撞箱、脚下有支撑且不是岩浆的相邻列；横移无路才退回旧的"顶到方块顶面"。脱出写 `[promaid/窒息] … 已横移一格脱出`（5 秒节流）。
- 被否方案：
  - *只在文案上删掉"头顶"* —— 放置格仍会往 pos+1 放，窒息根因留着。
  - *只把 antiSuffocate 改成 headInSolid* —— 头格被压时往上顶会顶进那个方块，必须横移。
  - *在 20 秒超时直接不播报* —— 玩家不知道她为什么停下；中性文案 + 诊断日志更有用。

## 触及文件
- `promaid_src{,_neo}/com/maidsmart/combat/SelfPreservationBehavior.java`（`escapeSuffocation` / `shiftOutOfSolid` / `suffocates` / `lavaAt` / buildUp）
- `promaid_src{,_neo}/com/maidsmart/task/BridgeUpBehavior.java`（placeStep / 文案 / headSpaceBlocked / describe / antiSuffocate）
- `promaid_src{,_neo}/com/maidsmart/task/MaidWorkBehavior.java`（antiSuffocate 委托共享实现）
- `promaid_src{,_neo}/assets/promaid/i18n/en_us.json`（新气泡「搭不上去，我先下来……」译文）
- 无新配置项 / 无新 mixin。

## 验证
- 两树编译：`compile_promaid.bat` / `compile_neo.bat` 输出 `EXITCODE=0`（批处理把 javac 退出码打印在最后一行；不能只看 `cmd /c` 的成功位）。
- 14 护栏：`FAIL 0`（`check_bubble_i18n` 首轮拦下新气泡缺译文，补词条后 PASS）。
- 打包：`patched/` 现字节
  - forge `3efcf653…1d0d`（17937511 字节）
  - neoforge `fd5667fe…ecb8`（17985120 字节）
- 装机：`deploy_all.py` 4 个目标 `match=True`。
- 专服回归：`test_server.py 1201` PASS、`test_server.py neoforge1211` PASS、`test_maid_load.py neoforge1211` PASS。
- **反证**：旧代码 `headInSolid` 完全不参与三份 `antiSuffocate`（逐行核对），头格被压时不可能有任何位移；旧 20 秒分支没有任何方块判定，必然对任何"没垫出块"都播报"头顶被挡住"（日志 12:44:42 与现场矛盾即实证）。实机反证待开发者按上面的验法复测。
- 未做的验证：未在开发者存档里复现活塞压头场景；未跑客户端图形界面。

## 验收（谁说了算：开发者 —— `WORKFLOW.md` §4.1）
- 状态：`未验收`。
- 未验收之前：不写 changelog、不写 release 说明、不发平台。

## 边界（没动什么）
- 没有改 TLM 自身的窒息伤害/气泡链路；`[窒息]` 播报门槛（4 点/5 秒窗口）与 20 秒放弃窗口都保持原值。
- 没有改挖矿/伐木的搭高放置逻辑（实测九百五十八 已正确），只把它们的 `antiSuffocate` 换成共享实现。
- 没有改战斗自保的搭高高度/传送/放水链路。
- 没有新增配置项；横移脱出是硬保护（只在头/身确实卡进实心块时触发）。
- "超频单平台"场景中若脚下是红石粉等不可覆盖方块，现在会走中性文案并**不垫块**（不拆红石）；这是刻意取舍。

## 台账
- 清掉的债：无对应 `BD-` 条目（实机首次报告即在本轮闭环）。
- 新增的债：无。
- 配额：A 类修 bug 轮，不触发功能轮配额。
