# R989 三项：接管走位（语义修正与改名） / 接管钓鱼 / 接管农场（面板与常用跳转）

## 排班

1. ① **「接敌才接管走位」语义修正并改名「接管走位」**：从「战斗与自保」页移到**「单兵战术」页第一条**；
   开 = 现状口径（她接敌 / 本模组驱动器在开她才接管走位）；**关 = 战斗状态下本模组完全不再干涉她的走位与移动**
   （战术走位 / 跳劈 / 追怪导航全停；喂目标、原地补刀、投弹与举盾照常；自保 / 空袭 / 扫帚 / 自由飞等自家驱动原样）。
2. ② **新增「接管钓鱼」总闸**（`misc.takeOverFishing`，默认开）：新开「钓鱼」页（排「农场与宰杀」后），
   总闸为该页第一条；原挂农场页的「钓鱼自动坐垫」开关随迁进这一页；关 = 钓鱼完全按 TLM 原版。
3. ③ **新增「接管农场」总闸**（`misc.takeOverFarm`，默认开）：「农场与宰杀」页第一条；关 = 农场完全按 TLM 原版。
4. ④ **三颗总闸进「常用」跳转**：常用页新增「单兵战术 / 钓鱼 / 农场与宰杀」三颗「前往 →」（含新小标题）。
5. ⑤ 手册与译文同步（工作链接、面板导航结构句、农场杂项首条 bullet、i18n 键值对）→ 两树镜像 → 编译 →
   14 护栏 FAIL 0 → 双 jar → 部署 4 实例 → 专服回归三件 → 文档台账 → 清临时。

## 需求 / 来源

- ⑦ 用户原话：「接敌才接管走位这个开关你好像理解错了。我的意思是要设置一个开关，控制是否接管走位，
  （默认开，）关闭状态下 Promaid 不再干预女仆在战斗状态下的走位和移动逻辑。这个开关放在战术的
  配置页作为第一个开关。开始时则在接敌时接管。其他被魔改的模式（如钓鱼和农场，我们自己创建的新内容不管。）
  也要有这样的开关且放在对应配置页的第一个。然后这些开关也放在常用的跳转里面。」
- 口径明细（同日拍板）：关闭时战斗链路只停「走位与移动」相关的接管；喂目标与原地补刀保留；
  举盾（`isTacticsEnabled` 口径）不受影响；自保 / 空袭 / 扫帚 / 自由飞属自家内容不动；
  「我们自己创建的新内容」不设接管总闸。

## 改法

### ① 接管走位（两树 `MaidSmartConfig` / `MaidWorkTags` / `MaidCombatTacticsBehavior` / `NeutralThreatDriver` / `CombatWorkRange` / 面板 / 手册）

- `combat.engageChain` → `combat.takeOverMovement`：定义名 / 译文键（`config.promaid.combat.takeOverMovement`）/
  「接管走位」文案 / 面板行 / 注释全部同步；面板行从「战斗与自保」页删除，移进「单兵战术」页**第一条**。
- 闸点四处（关闭 = 全部退场，默认全开时行为与此前逐项一致）：
  - `MaidCombatTacticsBehavior.isActive` 顶部总闸（`MaidMoveSuppressMixin` / `MaidNonCombatSpeedCap` /
    `CombatWorkRange.chainDriverActive` / `m_6114_` 等消费点随闸）；
  - `MaidWorkTags.isChainDrive` 战斗腿（挖矿 / 伐木腿不动）；
  - `NeutralThreatDriver.navigateTo`（喂目标与原地补刀仍在，只有导航停）；
  - `CombatWorkRange.radius`：关闭时扩圈只认 `chainDriverActive`（自家驱动仍在时扩圈照旧，TLM 自身走位不吃扩圈）。
- 配置注释按新语义重写（「接管走位（默认开）：…」）。

### ② 接管钓鱼（两树 `MaidSmartConfig` / `FishingChairService` / 面板）

- 新定义 `misc.takeOverFishing`（默认开）。`FishingChairService` 四处闸（总闸开 → 老行为）：
  `tickKeepSeatWalkAll` / `shouldSuppressNoSit` / `tryAutoChair` / 坐垫巡检 `sweep`。
- 面板：新增 `Section.FISHING("钓鱼", Group.WORK)`（排 FARM 之后）；`fishingRows()` 第一条 = 总闸；
  原 `farmRows()` 里的「钓鱼自动坐垫」`cvAuto` 随迁；行译文键复用。

### ③ 接管农场（两树 `MaidSmartConfig` / `FarmSweepMixin` / `FarmTillDriver` / `FarmSweepCache` / `FarmlandGuard` / 面板）

- 新定义 `misc.takeOverFarm`（默认开）。闸点（既有 base-null 等前置判据不动，只在入口加总闸）：
  `FarmSweepMixin` 扫荡顶部 / `FarmTillDriver` 骨粉与开垦两处 / `FarmSweepCache.isTillable` /
  `FarmlandGuard`（master AND 子开关，「不踩农田」随之）。
- 面板：「农场与宰杀」页第一条 = 总闸；`MISC_SLAUGHTER_RADIUS` 等其余行顺序不变。

### ④ 常用跳转 + 手册 / 译文（两树 `PromaidConfigScreen` / Guide / lang / i18n）

- 常用页：战斗段「单兵战术」`BtnRow` 改挂 TACTICS；新增小标题「—— 工作：钓鱼与农场 ——」+ 说明行 +
  「钓鱼」/「农场与宰杀」两颗 `BtnRow`。
- 手册三处：功能总览 ⓪ 工作行加「钓鱼」链接（`CFG_LINK_RULES` 加 `{"钓鱼", "WORK:FISHING"}`）；
  `GuideChaptersSystem` 结构句补钓鱼页；`GuideChaptersWork` 农场杂项章首条 bullet 写明三颗「接管」总闸
  （各自页第一条、随 DIY 关）。
- `groupHint`「生产与工作」补「钓鱼」；DIY 说明行补三颗总闸随 DIY 关；i18n 中英对（zh 面板行 / en 面板行）。

### ⑤ 护栏基线

- `check_preset.py`：BASELINE_FLIP 106/109 → **108/111**（两树各 +2 新开关随 DIY 关）；
  GATES 补 `MISC_TAKE_OVER_FARM`（`FarmSweepMixin.java`）；GATES_ELSEWHERE 补 `COMBAT_TAKE_OVER_MOVEMENT`
  （`com/maidsmart/task/MaidWorkTags.java`）与 `MISC_TAKE_OVER_FISHING`（`com/maidsmart/fishing/FishingChairService.java`）。
- 重跑 `gen_panel_coverage.py --write` 与 `gen_switch_inventory.py --write`（`ARCHITECTURE-SWITCHES.md` 已含新键）。

## 验证

- ⑦ 落盘：`_p1.py` 一次性补丁（两树代码 + 面板 + 手册 + 译文，逐项 count==1 断言；
  两处锚点按 `farmRows` 列 0 收尾修正后 FAILS=0）；`ENGAGE_CHAIN` / `engageChain` 全树残留 0
  （Java + lang + toml 逐文件扫过）。
- 两树编译 `EXITCODE=0`；14 护栏 FAIL 0（`check_preset` 基线 108/111 留痕；
  重跑两个生成器后 `check_workflow` 一致；`check_guide_i18n` missing / shifted / round 全 0）。
- 构建：forge `promaid-1.3.2-forge-1.20.1.jar` 17,905,440 字节（sha256 `ad9d9c94…284425`）、
  neo `promaid-1.3.2-neoforge-1.21.1.jar` 17,953,172 字节（`b0be7428…4f49b7`）。
- 部署 4 实例 **match=True**（Neo 1.21.1 本体 / Forge 47.4.10 / 龙之冒险整合包 / `mc_server_test/pack1201`）。
- 专服回归三件 PASS：`test_server.py 1201`（Done 3.174s）、`test_server.py neoforge1211`（Done 1.264s）、
  `test_maid_load.py`（召唤 → TLM brain 类加载，无崩）。

## 验收

- ①「单兵战术」页第一条「接管走位」：开着 = 与从前一致——战斗任务下没敌人时行为完整交还 TLM 原版，
  接敌 / 自家驱动接手才接管走位，敌人清完立即复原；**关掉 = 战斗状态下本模组不再干涉她的走位与移动**
  （战术走位、跳劈、追怪导航都停；喂目标、原地补刀、投弹与举盾照常；自保 / 空袭 / 扫帚 / 自由飞原样）。
- ②「钓鱼」页第一条「接管钓鱼」：关掉 = 她钓鱼完全按 TLM 原版（不主动找水、不生成坐垫、坐垫走位维持停手）；
  重新打开，子开关按原勾选恢复。
- ③「农场与宰杀」页第一条「接管农场」：关掉 = 农场玩法完全按 TLM 原版（连锁收获 / 批量种植 / 顺手拾取 /
  锄地 / 骨粉催熟 / 不踩农田全停）；宰杀半径等其余开关不受影响。
- ④「常用」页可直接前往 单兵战术 / 钓鱼 / 农场与宰杀 三个页面，不再翻大类。
- ⑤ 手册：功能总览可跳钓鱼页；面板导航章结构含钓鱼页；农场杂项章写明三颗总闸口径；英文译文对齐。

## 边界

- ①「接管走位·关」只作用于**战斗走位独占**：挖矿 / 伐木的走位独占、扫帚 / 空袭 / 自由飞的驱动腿照旧；
  `CombatWorkRange.radius` 关闭时只随自家驱动扩圈，TLM 自身走位不受扩圈半径影响。
- ① 举盾走 `isTacticsEnabled` 口径，不受「接管走位」影响；敌袭 / 清敌语音不变；轰炸 / 投 TNT 不属于走位，照常。
- ②③ 总闸只关「本模组对该玩法的增强」；「我们自己创建的新内容」（扫帚 / 建造 / 战斗任务等）不在任何
  总闸名下，照常可用。两个总闸与「接管走位」一样**随 DIY 关闭**（DIY 说明行已写明）。
- ②③ 关闸时子开关**保留勾选状态**（不改写子项值），重新打开总闸即恢复原配置。
- 三个开关默认全开：不改动默认手感，老档升级后行为与升级前逐项一致。

## 台账

- `rounds/RELEASES.tsv` 追加两条 1.3.2 行（**第一百二十六次**同号重打，本行字节）：
  `ad9d9c94…284425`（forge）/ `b0be7428…4f49b7`（neo）；前一次 `c48e188d…34a1cbe1` / `4a7210a1…2b516299` 作废。
- `DEV_GUIDE.md` 发布区间 → 八百五十九 ~ 九百九十三。
- 四份发布文案 + 桌面「完整逐条」：新增「钓鱼与农场也各有『接管』总开关」小节；
  「战斗任务没有敌人时」小节的开关行改写为「单兵战术 → 接管走位」新语义；
  「行为变化」补三颗总闸与随 DIY 关。
- `机制详解.md`：追加「接管走位（总闸语义与四处闸点）」「接管钓鱼」「接管农场」三条补记（R989）。
- `tools/verify/check_preset.py`：DIY 关闭条数基线 106/109 → 108/111（留痕）。
- 临时脚本（`tools/_*.py` / `tools/_*_out.txt` / `tools/_*.log`）全部删除；仓库根 `_*` 保持 5 项。
