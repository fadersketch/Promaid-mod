# R984 · 建造 HUD 修复 · 附赠蓝图 80→82 · 危险方块搬栏 · 「其余开关」空页

## 排班

1. 开发者五条：①1.20.1 冷却 / 建造 HUD「好像突然渲染不出来了」；②随包蓝图再加两张（「唯一」+「Misty Memories」），
   让下载即得 83 张；③危险方块设置不该在挖矿里，要在「战斗与自保」单开一栏；④「其余开关（自动）」只剩一项、没用，
   要不要改名/去掉；⑤联机下谁能用手册改数值、模组怎么处理（**提问，只需答复**）。
2. 先查①（拿开发者自己的客户端日志）：建造 HUD 的根因是**广播打包时抛 CCE 被吞**（不是渲染事件）；
   冷却 HUD 反而在日志里有 `first draw at y=4`，是好的——顺手把它「开界面就隐藏」的口径讲清楚。
3. ②核清数量口径（80/81/83 三个数各自的来历），把两张图按玩家文件原样打进两树。
4. ③定位「看着像在挖矿里」的真正来源 = **矿表子页的第三个页签「危险方块」**，不只是行挂错了小类。
5. ④把那一项登记为已收录、空页自动消失；⑤按 `ConfigSyncNetworking` 与保存路径书面答复。
6. 两树镜像 → 编译 → 14 护栏 → 打包 → 部署 → 回归 → 文档台账 → 清临时。

## 需求 / 来源

- ①「我发现1.20.1版本冷却和建造的hud似乎突然渲染不出来了。」
- ②「原本是81张你把模组下载下来就有的蓝图，在此基础上再加上两张……一下子就会获得83张附赠的蓝图。」
- ③「危险方块的设置为什么要放在挖矿里面？最好是在战斗与自保里面单开一栏」
- ④「其余开关（自动）这一单里面只剩下一个选项了，没什么用了，世界的唯一一个选项好像还是跟联机有关的吧？你看看还要不要改名字之类的。」
- ⑤「在联机模式下，是否所有玩家都可以用手册修改数值和开开关？模组内是怎么处理的？」

## 改法

### ① 建造 HUD（真因 + 事件）

**真因（开发者日志原文）**：

```
[Server thread/WARN] [promaid/]: [promaid] 已吞掉异常 BuildHudTracker @TRANSFORMER/promaid@1.3.2/...playerInvolved(BuildHudTracker.java:182)
java.lang.ClassCastException: class [Ljava.lang.String; cannot be cast to class java.lang.String
  at com.maidsmart.build.BuildHudTracker.playerInvolved(BuildHudTracker.java:182)
  at com.maidsmart.build.BuildHudTracker.sendToInvolved(BuildHudTracker.java:138)
  at com.maidsmart.build.BuildHudTracker.broadcast(BuildHudTracker.java:101)
  at com.maidsmart.ProMaidExtension.onServerTick(ProMaidExtension.java:312)
```

- `sendToInvolved` 打包判定材料时写的是 `new Object[]{e, info[1], info[2], info[3]}`——**槽位 0 放了 `String[]` 快照条目**，
  而 `playerInvolved` 里 `(String) info[0]` 当作 planId 用（实测九百六十八 加的「创建者本人也算有关」要按 planId 取计划）。
- CCE 被 `Diag.swallow("BuildHudTracker", …)` 吞掉 → **每秒每人都抛一次、每人都只收到空快照** → 左上角什么都不画。
- 修法（两树）：条目挪到 `info[4]`（`new Object[]{info[0], info[1], info[2], info[3], e}`），
  取快照改读 `info[4]`（`instanceof String[] snapshot` 守卫），planId 一侧加 `instanceof String planId` 守卫。
- 另（forge 专属）：`BuildHudRenderer` 的事件从 `RenderGuiOverlayEvent.Post` 换成 `RenderGuiEvent.Post`
  ——冷却 HUD 当初就是栽在这个事件上（类注释里写着「回调从未进入」），1.20.1 的 `ForgeGui.render` 末尾每帧必发
  `RenderGuiEvent.Post`，少一个会默默失效的环节；neo 本就是这一个。
- 补两条**一次性诊断日志**（`build hud: first render event` / `first draw at y=`），下次一眼能分辨
  「事件没到」还是「没数据」。

**冷却 HUD 的结论**：它没坏。开发者这次会话的日志里：

```
cooldown hud: first render event
cooldown hud: first snapshot received (1 entries)
cooldown hud: first draw at y=4
cooldown hud: SKIP[screen] entries=1 staleMs=203 screen=true f3=false font=true y=-1 h=-1
cooldown hud: SKIP[stale] entries=1 staleMs=3013 ...
```

——正常绘制过；之后 `SKIP[screen]` 是**设计如此**：打开任何界面（含手册）或按 F3 时两个 HUD 都隐藏
（避免与菜单/调试文字重叠）。建造 HUD 失去数据后 `bottomY()` 回 4，冷却 HUD 就贴顶画。

### ② 附赠蓝图（80 → 82，三个数各自的来历）

| 数 | 是什么 | 组成 |
|---|---|---|
| **80** | 本模组**真正随包发出**的蓝图数 | `assets/maid_smart/bundled_blueprints/` 80 个 `.litematic`（3.4 MB/树），名单 = `BlueprintFileIo.BUNDLED_BLUEPRINT_FILES`（与目录逐项一致） |
| **81** | 开发者 1.20.1 配置目录里看到的个数 | 80 + `甘蔗牧场（已失效）.schem`（早已标记失效、**已从包与名单里移除**，只是没从旧配置目录里删） |
| **83** | 开发者 1.21.1 配置目录里的个数 | 80 + `甘蔗牧场（已失效）.schem` + 本轮要加的两张 |

- 本轮把玩家 1.21.1 实例里的 `唯一.litematic`（15,380 B）与 `《Mistry Memory》.litematic`（24,137 B）
  **原样**复制进两树 `bundled_blueprints/`，并把文件名追加进 `BUNDLED_BLUEPRINT_FILES`（名单与目录各 82、逐项核对一致）。
- 首启解压逻辑不变：`ensureBundledBlueprints` 只在目标不存在时复制（不覆盖玩家自己改过的同名文件）。
- **包后总数是 82，不是 83**——差的正是那张失效的「甘蔗牧场」；没有把它加回去（它本来就是坏的）。
  文件名按玩家机器上的实际名（`《Mistry Memory》`），未按需求原文的「Misty Memories」改名。

### ③ 危险方块搬栏（含「看着像挖矿」的真源）

- 真源不止「行挂错小类」：`mineTableButtons` 的矿表页签是三格的
  `{"目标矿物", "障碍物", "危险方块"}`——**从「生产与工作 → 挖矿」的名单入口进去就能看到「危险方块」页签**，
  于是感受上它就在挖矿里。
- 改动（两树 `PromaidConfigScreen`）：
  1. 枚举 `SAFETY("女仆安全", Group.SURVIVAL)` → **`DANGER("危险方块", Group.COMBAT)`**；`case SAFETY → case DANGER`；
     `safetyRows()` → `dangerRows()`（含 寻路危险方块避让 / 险境脱离 / 危险方块名单 三行，文案一字未改）。
  2. 矿表页签去掉第三格：`woodTable ? {"木材","障碍物"} : (mineTableMode==2 ? {} : {"目标矿物","障碍物"})`
     ——危险方块子页**不画页签**，标题改「危险方块名单——点击方块图标加入/移出（再点取消）」。
  3. `mineRows` 的名单入口加 `mineTableMode = 0`（mode 2 现在只属危险方块子页；旧版不复位会带着 mode 2 进矿表）。
  4. 「受困救援」随板块拆分并入 `escapeRows()`（**生存与复活 → 传送与逃生**）——它本身就是一次「传送脱困」，
     也避免「女仆安全」被拆成只剩一行的空页。
- 手册与词典跟进：`GuideChaptersBasics` ③ 行加 `[[@COMBAT:DANGER|危险方块]]`、④ 行去掉 `|女仆安全`；
  `GuideChaptersSystem` 的 ③/④ 小类清单、索引行（危险方块三项 → 战斗与自保）、术语「受困救援」与「危险方块避让」的
  路径；`GuideChaptersWork` 的「女仆安全与区块保载」尾句；`GuideContent` 的 `SURVIVAL:SAFETY` → `COMBAT:DANGER`。
  词典：**7 个新键补英文 / 6 个旧键改写 / 3 条死键删除**（`check_guide_i18n` missing 7 → 0）。
  `DangerBlocks` 类注释里的旧路径也改掉。

### ④ 「其余开关（自动）」空页

- 那一项是 `misc.serverForcedConfig`（多人下「配置以服务端为准」）——**它本来就在「系统与杂项 → 交互与杂项」有自己的一行**
  （`showForcedRow()` = 多人 ∧ 本机 OP 时才渲染），`extraRows` 之所以又列一次，是因为它在面板里**只用译文键引用、没写
  `MaidSmartConfig.MISC_SERVER_FORCED_CONFIG`**，所以没进 `panel_coverage.txt`。
- 改法：该行 tooltip 用 `MISC_SERVER_FORCED_CONFIG.getPath()` 写出 toml 键（顺带让该字段被收录）→ 反射清单**变空**；
  `extraRows` 拆出 `extraItems()`，`groupButtons()` 在该表为空时**不再列出 `Section.EXTRA`**；
  小类名与页内标题统一为「其余开关（自动补全）」。以后真有新 toml 项没收录，这一栏会自己回来。
- 命名取舍：保留「自动补全」的定位（它是兜底页，不是功能区），不改成别的名字；空即不显示。

### ⑤ 联机下的面板权限（书面答复，见本轮结论）

- 默认（`misc.serverForcedConfig` 关）：**人人都能改自己那份**——面板写的是**本机**的
  `config/promaid-common.toml`，对服务端/别人的游戏**没有影响**；非 OP 玩家的改动不落到服务端。
- 打开该开关（只有**专用服务器上的 OP** 能看到并切换这一行）：服务端把整份配置推给每个客户端（**只在内存生效、
  不写客户端文件**），**非 OP 只能看不能改**（控件置灰），**OP 的改动在关闭面板时做差量发回服务端、由服务端落盘并广播全服**。
- 其余 C2S（SetForced / SetValues / ApplyPreset）一律带 `hasPermissions(2)` 校验；单机 / 局域网只有自己没有服务端强制项。

## 触及文件

- 两树：`build/BuildHudTracker.java`、`build/BlueprintFileIo.java`（名单 +2）、`config/PromaidConfigScreen.java`
  （枚举 / 派发 / `dangerRows` / `escapeRows` / 页签 / 标题 / `extraItems` / `groupButtons` / tooltip）、
  `guide/GuideChaptersBasics.java`、`guide/GuideChaptersSystem.java`、`guide/GuideChaptersWork.java`、
  `guide/GuideContent.java`、`tool/DangerBlocks.java`（类注释）、`assets/promaid/i18n/en_us.json`（7 补 / 6 改 / 3 删）、
  `assets/promaid/panel_coverage.txt`（重生成）、`assets/maid_smart/bundled_blueprints/`（+2 文件）。
- forge 专属：`build/BuildHudRenderer.java`（事件 + 两条诊断日志）。
- 工具：`tools/build/gen_panel_coverage.py`、`tools/verify/gen_switch_inventory.py --write`（重跑）。
- 文档：`rounds/R984-*.md`、`rounds/RELEASES.tsv`、`DEV_GUIDE.md`、`机制详解.md`、
  `release_body.md` / `release_body_neo.md` / `RELEASE_NOTES_v1.3.2.md` / `store/release_notes_1.3.2.md`
  （+ 桌面 `Promaid文本\v1.3.2 骑乘修整与全部配置开关可见.md`）、`ARCHITECTURE-SWITCHES.md`。

## 验证

- 两树 `javac EXITCODE=0`（改完先编译）。
- 14 护栏全 `FAIL 0`：`check_comment_style` 一度 FAIL 1（新枚举注释里出现「玩家反馈」+ 引语，已改口径）；
  `check_guide_i18n` 7 缺译 → 0；`check_panel_coverage` 重生成后 0；`check_workflow` 重生成后 0。
- 包体：forge **17,900,278** 字节 `6891ad0e…360cb5`；neo **17,947,867** 字节 `8c1d4211…2f24d3`。
- 部署：`deploy_all.py` 4 个目标全 `match=True`（`elevated rc: 0`）。
- 回归：`test_server.py` / `test_maid_load.py` 两加载器各两件，四件全 `verdict: PASS`。
- 蓝图：两树 `BUNDLED_BLUEPRINT_FILES` 条数 == `bundled_blueprints/` 文件数 == **82**，集合相等。

## 验收

- 让女仆开工建造，左上角能看到建造进度（名称 / 已放 / 总数 / 跳过 / 速度 / ETA），建完自动消失；
  1.20.1 与 1.21.1 表现一致；打开手册或按 F3 时两个 HUD 都暂时隐藏（松手即回）。
- 干净安装后建造目录里能看到「唯一」与「《Mistry Memory》」，加上原来那 80 张，共 82 张。
- 配置面板「战斗与自保」里能看到「危险方块」一栏；「生产与工作 → 挖矿」的名单子页只剩「目标矿物 / 障碍物」；
  危险方块名单自成一页、标题写明「危险方块名单」；「受困救援」在「生存与复活 → 传送与逃生」。
- 「系统与杂项」下**看不到**「其余开关（自动）」这一栏（当前无未收录项）；小类名与页内标题在出现时都是「其余开关（自动补全）」。

## 边界

- 蓝图总数是 **82**（不是需求里写的 83）：差的「甘蔗牧场（已失效）.schem」是早已移除的失效项，未加回。
  玩家旧配置目录里的它不会被删（那是玩家目录，模组只负责首启解压）。
- 附赠蓝图文件名沿用玩家机器上的实际名（`《Mistry Memory》`），未按需求原文「Misty Memories」重命名。
- 「危险方块」新小类只管 寻路危险方块避让 / 险境脱离 / 危险方块名单 三项；**飞行侧**的「飞行危险环境避让」
  仍留在「战斗与自保」原有的数值板块里（它管的是飞行途中，不在本轮范围）。
- 冷却 / 建造 HUD 在**打开任何界面时都不画**是刻意行为（手册详情页已有进度显示，避免双份）；
  玩家若想要「开着界面也看得见」，那是另一个需求。
- 菜单小类顺序 = 枚举声明顺序：`DANGER` 声明在 `ESCAPE` 之后，所以在「战斗与自保」里排在末位。

## 台账

- `rounds/RELEASES.tsv` 两条 1.3.2 行 **第一百二十次** 同号重打；旧字节
  `f0e0e85a…bdc998`（forge）/ `fcb0ef8e…97cdb6`（neo）（第一百一十九次 R983）作废。
- `DEV_GUIDE.md` 发布区间 → 八百五十九 ~ 九百八十四。
- 四份发布文案 + 桌面「完整逐条」：新增「随包附赠蓝图 80 → 82」「危险方块设置搬到战斗与自保」两小节；
  关键修复追加「建造 HUD 又能看到了」「『其余开关（自动）』不再是一页只有一项」两条；
  并把「其余开关（自动）」页的旧提法改成「（自动补全）……真有内容时才出现」。
- `机制详解.md`：`BuildHudRenderer / BuildHudTracker` 一条补「快照只发给与该区块有关的人」+ 槽位约定 + 事件；
  `BlueprintLib` 一条补「随包附赠蓝图包（82 张）」与首启解压口径。
- 附：`check_guide_i18n` 仍抓不到**长度相当**的键值错位（R983 已记的盲区，本轮未扩大）。
