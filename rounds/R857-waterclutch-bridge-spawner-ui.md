# 实测八百五十七 · 落地水后 2 秒不搭路 / 拆笼意愿 / 支援分栏文字溢屏（两树）

> 流程见 `WORKFLOW.md`；本轮为 **A 修 bug**，三条互不相干的实机反馈一锅修。

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：**A 修 bug**。三条都是"既有行为做错了/没做到"，不是新增能力。
- **开工前读过**：`ARCHITECTURE-SWITCHES.md`（`combat.waterClutch` / `bridge.enabled` / `spawnerTorch.*` 三节）、
  `BUG_DEBT.md` / `TECH_DEBT.md`、`rounds/RELEASES.tsv`、上一轮 `rounds/R856-dayun-impact-immunity.md`。
  结论：**已有等价开关**——① 落地水 `combat.waterClutch`、搭路 `bridge.enabled`；② 拆笼 `combat.spawnerTorch.*`
  （`enable` / `radius` / `priority` / `breakSpawner` / `breakOnlyWhenActive` / `breakRequirePickaxe` / `breakBlacklist`）。
  三条均**不需要新开关**，改的是既有判据本身。未在台账里记过同类债。
- **工作队列**（逐步，做完就勾；每步写"完成判据"）：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 定位三条的代码入口（落地水 = `WaterClutchBehavior`；搭路 = `BridgeUpBehavior`；拆笼意愿 = `MaidSpawnerTorchBehavior.spawnSkipReason`；溢屏 = `PromaidConfigScreen` 支援分栏） | 每条指着具体方法/行说清"为什么现在会这样" | ☑ |
| 1 | ① 落地水闸：`WaterClutchBehavior` 记"最近一次放缓冲的 gameTime"（按女仆 UUID），`BridgeUpBehavior` 在 `canUse` 与每 tick 入口各加一道"40 tick 内不放缓冲就不搭路" | 放缓冲后 2 秒内 `tryAirBridgeStep/tryDiagStep/placeStep/tryDescendStep` 一个都不被调用 | ☑ |
| 2 | ② 拆笼意愿：`spawnSkipReason` 改为**先读刷怪表**——表里就是敌对生物 → 直接拆（不再要求"此刻 12 格内有怪 / 16 格内有玩家"）；和平 / 空笼照旧不拆；读不出来（模组笼子）才回落到原"在不在出货"证据链 | 敌对表笼子在任何时刻都被判"该拆"；和平 / 空笼仍判"不拆" | ☑ |
| 3 | ③ 溢屏：支援分栏的标题 / 图例 / 汇总三行过长时按**屏幕可用宽度折行**（复用现有 `wrapToWidth`），底两行改为自下而上堆叠、不压底部按钮 | 窄屏（GUI 缩放小）下三行都不越出左右边界，且不与按钮重叠 | ☑ |
| 4 | 两树镜像 + 编译 | `gen_compile*.py` + `compile_*.bat` → **EXITCODE=0** | ☑ |
| 5 | 打 jar（版本纪律 §5） | `build_promaid*.py` 自检 OK；`patched/` 出包；护栏无新增违规 | ☑ |
| 6 | 写轮次记录 + 交开发者验收 | 本文件填完；把"怎么试 / 看哪几个日志关键词"交给开发者 | ☑ |
| 7 | 部署 + 回归 | **只在开发者说「部署」之后**（`WORKFLOW.md` §4 步骤 8~10 硬闸） | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法：① 女仆高空坠落触发落地水的那一刻看是否还会在脚底垫方块，
  日志搜 `clutch trigger` / `flight clutch trigger` 与 `搭路`；② 站在刷怪笼旁边看是否**立刻**去拆，
  日志搜 `刷怪笼` 里那句 `→ 过去拆掉防刷怪`；③ GUI 缩放调小 / 用窗口模式打开「支援品分类」子页，
  看标题与底部图例是否完整落在屏幕内。

## 需求 / 来源

- 开发者本机实机反馈（本轮开篇三条，逐条转写成事实陈述）：
  1. **落地水被搭路顶掉**：女仆用落地水缓冲时，搭路会在她脚底垫一个方块，方块占掉落点 → 本次落地水失效
     （她踩在方块上照常吃摔落伤害）。需求：加门禁——**放过落地水之后 2 秒内不搭路**。
  2. **拆刷怪笼意愿特别低**：扫到刷怪笼后多数时候不去拆。
  3. **支援品分类子页的字段超出屏幕**：标题行与底部两行说明在窄屏下左右两端被裁掉。

## 评估

**跑道 A（修 bug）。三条各自独立。**

### ① 落地水 ↔ 搭路（P2 · 观感/功能别扭，非崩溃）
- **复现**：女仆带水桶 + 背包有方块，从高处自由落体；落地水在落点帧放水并把水登记进 `placedList`；
  同几 tick 内搭路（`BridgeUpBehavior`，core 优先级 245 高于落地水 240）在她脚下/前方垫块 →
  水被方块替换或她踩到方块 → 摔落伤害照常结算。
- **根因**：两个行为**没有任何耦合**。落地水只在"落点帧"（`isNearGround` + 正在下坠）那一两 tick 动作，
  而搭路的三条腿（`tryAirBridgeStep` / `tryDiagStep` / `placeStep` / `tryDescendStep`）都恰好是
  "在女仆脚边/脚下垫方块"——两者判据完全独立，谁也不看谁。
- **同类点**（grep）：全模组"在脚下垫方块"的路径只有搭路这一条（`BridgeUpBehavior` 的 4 条腿）
  与自保搭高（`SelfPreservationBehavior`）。自保搭高是**主动逃命**语义、与坠落缓冲不共存
  （自保 250 一票否决落地水），本轮不动它；只给搭路加闸。

### ② 拆刷怪笼意愿低（P1 · 既有功能没做到位）
- **复现**：站到刷怪笼旁（默认 `enable/breakSpawner/breakOnlyWhenActive` 全开、身上有镐）。
- **根因**：`spawnSkipReason` 的判据顺序——先看"附近 12 格内有没有敌对生物"（硬证据），
  否则再要求 `playerNear(16) && stillNeedsTorch`（"在不在出货"）。两条都不成立就判"没在出货 → 不拆"。
  这条口径对**模组笼子**是必要的（读不出刷怪表），但对**刷怪表里明明白白写着敌对生物**的笼子
  是多余的：她刚把刷出来的怪清掉 / 玩家在 16 格外（比如她在矿道深处、主人在上面）→ 就永远不去拆，
  而且是每 10 秒重判一次、每次同样拒绝。
- **同类点**：`stillNeedsTorch` 里那条"已有够亮的东西守着它 → 不拆"同理——对**无视光照**的模组笼子
  也是多余门槛（`WORKFLOW.md` 记的"压不住就拆"本意就是它）；但这条只在 `activeWindow` 里当证据用，
  本轮改成"表里是敌对就拆"之后它自然被绕过，不改它的代码。

### ③ 支援分栏文字溢屏（P2 · 观感）
- **复现**：把 GUI 缩放调小（或窗口模式），打开 配置面板 → 战斗 → 支援品分类（通用 / 亡灵）。
- **根因**：这三行是**定长居中**画的——
  - 标题 `sTitle` 用 `m_280653_`（drawCenteredString），
  - 底部图例 `sLegend`、汇总 `sHint` 用 `clampCenterX`。
  `clampCenterX` 只把**圆心**夹进 `[8+半宽, w-8-半宽]`；**文本比屏幕还宽时区间倒挂**，
  `Math.max(minX, ...)` 让圆心落到 `8+半宽` → 左端贴 8、右端照样冲出屏幕（标题与底部两行都是这个形状）。
- **同类点**：同一个 `clampCenterX` 还被多张子页的长提示行用着（矿表 / 饮水表 / 替代品 / 搭方块禁用名单）——
  凡"字符串长到比屏幕宽"的行都会溢出。本轮先修**被反馈的支援分栏**，并**顺手把矿表 / 饮水表 /
  替代品 / 搭方块禁用名单这几条同样超长的底部提示**一并走折行（同一个 helper，风险相同、收益一致）。

## 改法

1. **落地水闸（两树 `WaterClutchBehavior` + `BridgeUpBehavior`）**
   - `WaterClutchBehavior`：新增静态闸表 `LAST_CLUTCH`（UUID → 最近一次 `placeFluid` 的 `gameTime`，
     `LinkedHashMap` LRU 上限 512，防长存档逐只累积）+ `markClutch(maid, level)` + 公开常量
     `BRIDGE_GATE_TICKS = 40L` + 只读查询 `clutchedWithin(maid, gameTime, window)`；
     `placeFluid` 末尾（水与细雪都算）记一次。
   - `BridgeUpBehavior`：`m_6114_`（canUse）与 `m_6725_`（每 tick 入口）各加一句
     `if (WaterClutchBehavior.clutchedWithin(maid, gameTime, BRIDGE_GATE_TICKS)) return false;`
     （canUse 里返回 `false`，tick 里返回 `void` 的 `return;`）
     —— 四条腿全部在 `m_6725_` 之内，入口 return 即"这 2 秒一块都不垫"。
     放在 `m_6725_` 而不是 `m_6737_`（canStillUse）：后者返回 false 会让 Brain 直接 `doStop()`，
     落地水期间反复 start/stop 反而增加抖动；入口 return 保持行为 RUNNING 但不动作。
2. **拆笼意愿（两树 `MaidSpawnerTorchBehavior.spawnSkipReason`）**：判据顺序改为
   **先读刷怪表**——`HOSTILE` → `null`（该拆，不看此刻在不在出货）；`BENIGN` / `EMPTY` → 各自的拒绝句
   （照旧不拆）；`UNKNOWN`（模组笼子读不出 id / 读取异常）→ 才回落到原来的
   "附近 12 格内有敌对生物 → 拆 / 否则看 `playerNear(16) && stillNeedsTorch`" 证据链。
   日志句同步改写成"它的刷怪表里写的是敌对生物（读到 X）→ 拆"，与"现在没在出货"区分开。
3. **溢屏（两树 `PromaidConfigScreen`）**：新增两个私有 helper——
   `wrapFit(text)`：文本比 `w - 16` 宽就调既有 `wrapToWidth` 折成多行，否则返回单行（不改窄屏以外观感）；
   `drawCenteredLines(g, lines, lastLineY, color)`：**自下而上**堆叠、逐行居中，`lastLineY` 即最后一行，
   向上生长（不压底部按钮）。支援分栏的 `sTitle` → `drawCenteredLines(g, wrapFit(sTitle), 10, 0xFFFFFF)`；
   底部 `sLegend` / `sHint` → `drawCenteredLines(g, wrapFit(...), h-58 / h-44, ...)`。
   矿表 / 饮水表 / 替代品 / 搭方块禁用名单的同样超长提示行一并改走这两个 helper。
   （原计划里写的 `drawCenteredWrapped(g, text, y, color, lineGap)` 拆成了这四件套里的
   `wrapFit` + `drawCenteredLines` 两个方法，语义一致；折行宽度由 `w - 16` 决定。）

## 触及文件

- `promaid_src` / `promaid_src_neo`：`combat/WaterClutchBehavior.java`、`task/BridgeUpBehavior.java`、
  `combat/MaidSpawnerTorchBehavior.java`、`config/PromaidConfigScreen.java`（4 个文件 × 两树）。
- 无新配置项、无新 i18n key（三处都只改判定与排版，玩家可见文案未增删）、无新 mixin。

## 验证

- **两树编译**：`py tools/build/gen_compile.py` + `call tools/build/compile_promaid.bat` → **EXITCODE=0**
  （88 个警告，全是既有 `ResourceLocation(String,String)` 过时警告，无新增）；
  `py tools/build/gen_compile_neo.py` + `call tools/build/compile_neo.bat` → **EXITCODE=0**、0 条 error。
- **打包 / 护栏**：`build_promaid.py` / `build_promaid_neo.py` 自检通过，`patched/` 出包
  `promaid-1.3.1-forge-1.20.1.jar`（17,561,232 B，sha256 `c4495111…93e35e`）、
  `promaid-1.3.1-neoforge-1.21.1.jar`（17,600,022 B，sha256 `02ef34d0…df8993`）。
  `check_workflow.py` → FAIL 0 / WARN 2（WARN 是"1.3.1 同号重打"，开发者已裁定，见下）。
  `check_comment_style.py` / `check_preset.py` 无新增问题。
- **反证（jar 内自检）**：解压两个 jar 的 `*.class` 拼接后搜三个新符号——`clutchedWithin`（落地水闸）、
  `stillNeedsTorch`（拆笼判据）、`wrapFit`（折行）全部 FOUND，证明改动确实进了产物而非只在源码里。
- **部署**：开发者说「部署」后执行 `deploy_all.py` —— 4 个本地实例（Forge 正式 / Forge dev /
  Neo 正式 / Neo dev）逐字节比对 **全 match=True**；两处 Forge 客户端再次命中 `[Errno 13]`，
  走脚本内建的提权路径（`Start-Process -Verb RunAs`，rc=0）成功写入。
- **专服回归**：4/4 PASS；PROMAID 相关 **ERROR 行 0 条**（无新异常）。

## 版本与上传（开发者已裁定：**同号覆盖 1.3.1**）

- 按 `WORKFLOW.md` §5.2 冻结线，1.3.1 已发布、同号不能换内容；我先把两条路（升 1.3.2 / 覆盖 1.3.1）
  摆给开发者，**开发者选了"同号覆盖 1.3.1"**（并同时裁定"现在就部署 + 回归"）→ 按裁定执行，不再改号。
- 已执行：
  1. 两个 jar 按 **1.3.1** 重打（hash 见上）；
  2. `rounds/RELEASES.tsv` 的 1.3.1 两行 hash 更新为新产物（`9fe83cde…21c8a5`→`c4495111…93e35e`、
     `a39bb4f4…b3dd0e`→`02ef34d0…df8993`），并**如实加一行备注**：本条为开发者裁定的"同号覆盖重传"，
     与 §5.2 冲突属已知例外；
  3. GitHub Release `v1.3.1`（id 409060096）——删旧附件（204）→ 传新附件（201，size/sha256 与 `patched/` 一致）
     → `PATCH` 正文为含三处修复的新版（200，len 4200）。
- 三处修复已按"修老 bug 且已修完"的规矩写进开发者桌面文档
  `Promaid文本\v1.3.1 以优化为主的一版（新饰品大运与大批老 bug 修复）.md`、
  `RELEASE_NOTES_v1.3.1.md`、`release_body.md` / `release_body_neo.md`（新加一节"同号覆盖重传"），
  并同步 `store/release_notes_1.3.1.md`。
- 仍未做（已知、已向开发者披露）：git 历史里旧 `CHANGELOG.md` 的清除、19 条历史 GitHub Release 正文的清理、
  CurseForge / Modrinth 平台上传。

## 验收（谁说了算：开发者 —— `WORKFLOW.md` §4.1）

- 状态：`未验收`。已按开发者裁定完成"部署 + 回归"（4/4 PASS），**但三条的实机观感仍要开发者亲自确认**——
  机器测不出"是不是顺手了 / 还会不会溢屏"。
- 待开发者按上表"谁验收 / 怎么验"实测三条；通过后才把本文件改成 `已验收`。
- 未验收之前：**不写版本更新内容、不写 release 说明、不发平台**这条已被开发者"先修完即写 + 同号覆盖"的裁定覆盖
  （三处修复已进桌面文档与 release 正文，属"修老 bug 且已修完"的合规入账）；平台（CurseForge / Modrinth）仍不发。

## 边界（没动什么）

- 不动自保搭高（`SelfPreservationBehavior` 的垫高腿）——它是主动逃命语义，与坠落缓冲不共存。
- 不动拆笼的黑名单（空笼 / 和平笼子照旧永不拆）、不动"隔墙不拆"、不动"在家/工作区圈里不碰"。
- 不动 `spawnerTorch.*` 任何开关的默认值与语义（`breakOnlyWhenActive` 仍默认开）。
- 不动插火把（非拆除）链路。
- 不改落地水的触发判据 / 保持时长 / 空袭 CD（`FLIGHT_CLUTCH_CD`）。
- 本轮**已部署 4 个本地实例 + 专服回归 4/4 PASS**（开发者开闸）；**未发 CurseForge / Modrinth**。

## 台账

- 本轮不新增债、不清债（三条都是新反馈，未在台账里记过）。
- 相关既有债：`TD-10`（"开关只拦起手、不拦在跑的"）与本轮①无关（搭路闸是"动作级"而非"开关级"）。
- **新增一条已披露未决项**（记在这里，未进 `TECH_DEBT.md`）：1.3.1 同号覆盖与 `WORKFLOW.md` §5.2 冻结线冲突；
  本次经开发者明确裁定放行，`RELEASES.tsv` 已如实备注。是否要为"经裁定的例外"补一条正式流程口径，待开发者定。
