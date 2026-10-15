# R961 建造完工「整体重放」+ 压缩盒弹药取不出来（差账核对误判）

## 排班
- **跑道**：B 新功能（开发者指令）内含一条 A 修复——一句话判定依据：① 给建造完工加一道默认开、可在面板关掉的新动作（终态整片重放），是「改变既有默认行为」的新能力；② 压缩盒里的 TNT / 重生锚 / 末地水晶取不出来是「玩家能感知的行为是错的（吞东西）」，按 A 修复走（根因三问见 §评估）。
- **开工前置**：读过 `WORKFLOW.md` §0~§4、`ARCHITECTURE-SWITCHES.md` 建造一节、`DEBT.md`、`rounds/RELEASES.tsv` 1.3.2 两行（第九十七次 R960）、`rounds/R960-*.md`，以及三处现状源码（建造完工分支 / 压缩盒取物口径 / 战斗未接敌闸门）。
- **工作队列**（逐步，做完就勾；每步写"完成判据"）：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 定口径：后门"清什么、放什么、什么时候" | 清 = 蓝图自己的格子（含流体源、无粒子无音效）；放 = 按计划原顺序整片重放、不扣料；跑点 = 缺口扫描之后、开入口之前 | ☑ |
| 1 | 定口径：压缩盒取弹为什么失效 | 根因 = 视图封顶 64 与 `extractHonest` 的"扣了要核账"对不上（差 0 判没取到） | ☑ |
| 2 | 改 Forge 树 | 【诚实取物窗口】+ `snapFinish` 两趟重放 + 开关 + 面板行 | ☑ |
| 3 | 镜像 Neo 树 | 同一函数逐字镜像（Mojang 名映射） | ☑ |
| 4 | 两树编译 | `javac EXITCODE=0` ×2 | ☑ |
| 5 | 护栏 | 14 支全 `FAIL 0`（两处生成物重生成） | ☑ |
| 6 | 打包装机 | 4 个目标与 `patched/` 同字节（`match=True`） | ☑ |
| 7 | 回归 | 四件全 PASS | ☑ |
| 8 | 交开发者验收 | 开发者实测后给出结论 | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者；验法 = ① 建一台机器（如轰炸机 / 天机炮）看完工一瞬间是否"静默整片重放"（无粒子无音效、机器终态正常）＋在「建造」页关掉「完工整体重放」确认回到不重放；② 往压缩盒里塞 TNT / 重生锚 / 末地水晶 / 萤石，看她扔 TNT、放重生锚、放水晶是否恢复；③ 顺手看一次"战斗状态未接敌"时跟随是否被覆盖（见 §评估 ③，本轮无代码改动）。

## 需求 / 来源
1. 开发者：建造可以走个后门——「在建造完成的瞬间，将女仆建造的所有东西（包括流体）全部清除（无粒子和音效），然后同帧/下一帧将蓝图本身替换上去。这样就不会因为建造顺序问题导致机器失效了！」
2. 开发者：新版本把 TNT / 重生锚 / 末地水晶放进压缩盒后，她不会扔 TNT / 不会放重生锚 / 不会放末地水晶了，只在空袭状态会扔 TNT。
3. 开发者问：女仆处于战斗状态但未接敌时，相关跟随和传送应该未被覆盖吧？（**问句，不改代码**——答复见 §评估 ③。）

## 评估
- **① 现状**：完工分支现在的顺序是——缺口扫描 → 开入口 → 机器矿车 / 非机器红石唤醒 → 批激活水 → TNT 点火结算 → 清理掉落物 → 清计划。建造期整段静默（flag 2），机器段活放（flag 3）并按红石拓扑分层（结构→机构→活动件→传感→动力源→TNT）。活放的机器**必然**在建造过程中自我反应：活塞推东西、观察者误脉冲、水流冲散还没砌好的槽、补支撑塞在蓝图没有的格子里——机器可能因此"建好但不对"。本轮口径：完工时把蓝图占的每一格**静默清空**（flag 2：无粒子、无音效、不触发邻居更新；流体源一起清），再**按计划原顺序整片重放**（机器仍活放 flag 3、液体与 TNT 静默 + TNT 护栏）。终态严格等于蓝图本身。
  - **放在哪一步**：重放会重新铺墙，所以必须在 `carveEntrance`（自动开入口）**之前**——否则刚开的门洞会被补齐；也必须在缺口扫描之后（缺口补建仍走原来的补料口径），在 `activateWater` / `settleTntIgnition` / 矿车启动之前（机器活放完再点水、再结算 TNT、再放矿车）。
  - **重放不扣料**：材料在建造时已经付过一次，清掉的正是她刚放的那些；重放是"复原"不是"再建一遍"。
  - **只动蓝图自己的格子**：不碰区块外的地形与玩家建筑；空气步骤在第一趟就是清空本身。
  - **代价上限**：两趟 setBlock + 逐条 SNBT 解析都挤在完工这一个 tick 里，55 万步蓝图会卡好几秒——`SNAP_MAX_STEPS = 20000`，超限自动跳过并落日志（保持"不重放"）。
  - **可回退**：新开关 `build.snapFinish` 默认开，面板「建造」页可关；关掉 = 完工不重放。
- **② 根因（三问）**：**哪里错**——`CompressionBoxMaidInv.getStackInSlot` 对盒子格返回"压到 64"的**视图**（防一格 11 万发的大堆被整个塞进她的手 / 背包格），而 `MaidBuildBlockFilter.extractHonest` 用"扣前 / 扣后槽位总数差"核账；大堆扣 1 发，真实 11 万→10 万9999，视图 64→64，差值 0 ≠ 1 → 判"没取到"。**为什么现在才出**——六百一十六那批压缩盒接线（`getMaidInv` / `getAvailableBackpackInv` 注入）让战斗放置（`BombItems.takeFirstMatch`）也走盒子格，而这条链的核账口径是实测七百六十二才统一的；`viewCap` 与核账各自都对，撞在一起才错。**同类点清点**——所有走 `extractOneHonest` / `extractHonest` 的取物：战斗放置（TNT / 锚 / 水晶 / 床 / 萤石 / 黑曜石）、蓝图材料 `extractExact`、火把 / 搭路 / 自保搭高 / 索引石。全部同一个收口，一处修全链生效；损坏面还包含"被判没取到的那一发其实已经真扣了"（白白销毁）。
  - **修法**：给压缩盒加一个**诚实取物窗口**（静态开合、返回旧值，`try/finally` 还原）——窗口内置位时盒子格**如实报真实数量**；`extractHonest` 全程在窗口内跑。窗口外照旧只露 64（整堆仍不许流进她手里）。
  - **为什么不用别的修法**：改 `extractItem` 也去掉封顶会打开"整堆漏出去"的口子；把核账换成"抽出量非空就算成功"会把 issue #19 那类坏包装层的假扣当成功（净刷物品）；只给压缩盒特判会让"谁是真底账"散成两处口径。
- **③ 战斗未接敌时的跟随 / 传送**（问句的答复，证据在代码注释链上）：**跟随**——主动参战一开就把她的任务换成战斗任务 + 切 home 模式，TLM 的跟随任务与它的跟随瞬移**只在非 home 模式下运行**，所以战斗期间是"停用"而非"被覆盖"；威胁消失 20 秒后连同任务 / home / 作息一起还原（`AutoCombatSwitch` 还原链）。**自保归位传送**——它的闸是"有活的非友方目标就不传"（实测八百五十八），未接敌时确实不会拦；保命兜底那一档（泡岩浆 / 血 <15% / 弹尽粮绝）也照旧可用。**跨维度 / 同维远距拉回**——排班 / 在家 / 建造中不传（实测八百零三 / 八百四十七），其余情况照常。结论：未接敌时不额外压制跟随与传送——这是既有口径，本轮未改。
- **级别**：① **P2**（新能力，默认开但可关；最坏情况是完工多花一次重放）。② **P1**（吞东西 + 核心战斗功能失效）。
- **风险**：① 中低——重放只动蓝图格子、有步数上限与开关、顺序上避开开入口 / 点水 / TNT 结算；机器活放段沿用建造期同一套 flag 规则。② 低——只加一个静态窗口与 try/finally，窗口外行为逐字未改。

## 改法
1. **诚实取物窗口**（两树 `box/CompressionBoxMaidInv.java`）：新静态 `honestView` + `setHonestView(boolean)`（返回旧值）；`getStackInSlot` 盒子格分支封顶取 `honestView ? Integer.MAX_VALUE : viewCap(s)`。`tool/MaidBuildBlockFilter.extractHonest` 整体包进 `setHonestView(true) ... finally setHonestView(旧值)`——早退的 `return false` 也走 finally。
2. **完工「后门」**（两树 `build/BlueprintMachineFinish.java`）：新 `snapFinish(level, origin, plan, live)`——开关关 / 参数空 / `plan.size() > SNAP_MAX_STEPS`（= 20000）直接返回 false；第一趟逐条按蓝图坐标清成空气（`isAir` 跳过、区块未加载跳过）；第二趟逐条重放——机器槽位归一化（`normalizeMachineState`）→ 状态解析（`parseStepState`）→ 与建造同口径的 flag（机器活放 3；液体 / TNT / 静默建筑 2；TNT 前后挂 `BuildTntGuard.setSuppress`）→ 方块实体数据还原（`snapApplyBe`）。日志一行：清了多少格 / 重放多少格。
3. **接线**（两树 `build/BlueprintLib.java` + `build/MaidBuildBehavior.java`）：`BlueprintLib.snapFinish` 一行委托；完工分支在开入口之前调用 `BlueprintLib.snapFinish(level, origin, plan, machineSmart)`。
4. **开关**（两树 `config/MaidSmartConfig.java`）：`build.snapFinish`（布尔，默认 true，`.translation("config.promaid.build.snapFinish")`）——声明处一行注解 + 定义处 `BUILDER.comment("完工整体重放…")`。
5. **面板**（两树 `config/PromaidConfigScreen.java`）：建造页 TNT 保护期那行之后加「完工整体重放」`BoolRow`（标签 / 提示同款口吻）。
6. **译文**（四份 lang）：`config.promaid.build.snapFinish` = 完工整体重放（zh）/ Snap-replace whole build on completion（en），插在 `tntIgnitionGrace` 之后。
7. **文案口吻**：新配置的 `.comment` 与面板提示里避开「旧行为」这类开发叙事（`check_dev_voice` 的 R902 口径），改写成「关 = 不重放」。

## 触及文件
- 两树各 7 个（共 14）：`box/CompressionBoxMaidInv.java`、`tool/MaidBuildBlockFilter.java`、`build/BlueprintMachineFinish.java`、`build/BlueprintLib.java`、`build/MaidBuildBehavior.java`、`config/MaidSmartConfig.java`、`config/PromaidConfigScreen.java`（其中配置 2 处、面板 1 处为行级小改）。
- 新配置项 1 个（`build.snapFinish`）/ 新 i18n key 1 条（四份 lang 同批）/ 新 mixin 0 个。
- 生成物：`assets/promaid/panel_coverage.txt`（重生成 588 / 594 项，+1 = 新开关）、`ARCHITECTURE-SWITCHES.md`（重生成 1365 行，+2 行 = 新开关一条）。
- 不动：`CompressionBoxData`（视图封顶本身的常量与口径）、`extractItem` 的封顶、`doPlace` 与全部建造放置路径、`activateWater` / `settleTntIgnition` 的相对顺序、`AutoCombatSwitch` / 自保归位 / 跟随链路（③ 是问句）。

## 验证
- **编译**：两树 `javac EXITCODE=0`。
- **护栏**：14 支全 `FAIL 0`（`check_comment_style` / `check_dev_voice` 各先红后绿：新注释块 11 行 > 8 已压到 8 行内；新 `.comment` 里的「旧行为」按 R902 口径改写）。`check_preset` 基线 105/108 不变（`build.` 前缀在 `DIY_KEEP_PREFIX` 里，新开关不参与 DIY 翻转计数）；`check_panel_coverage` 0/0；`check_workflow` **FAIL 0**，带一条 §8 配额 WARN——见下。
- **镜像**：两树 `snapFinish` / `setHonestView` / 新面板行 / 新注释逐字对应（仅 SRG ↔ Mojang 名不同）；`check_tree_mirror` 无新增差异项。
- **打包**：forge `17634315` / neo `17681332` 字节；部署 4/4 `match=True`；新 sha256 forge `74395ba0…5369b2` / neo `96a42f1c…e51dcf`（台账两行已改 sha 列并追加「第九十八次同号重打」说明）。
- **回归**：四件全 PASS（`test_server` / `test_maid_load` × 1201 / neoforge1211）。
- **反证**（改前 / 改后）：`extractHonest` 的核账判据逐字未改（只是跑在窗口里）；`getStackInSlot` 窗口外的封顶表达式逐字未改；`extractItem` / `getSlotLimit` / `isItemValid` 零改动；完工分支除新插一行调用外逐字未改（开入口 / 矿车 / 点水 / TNT 结算 / 清理的顺序原样）；新开关关闭时 `snapFinish` 第一句就返回 false（行为回到不重放）。
- **未做**：**没有实机开游戏验证**（本环境没有 GUI / 游戏回归手段）——重放的实际观感（无粒子无音效、机器终态）与压缩盒取弹的实机手感都留给开发者实测；改动能被证到的最深处是"两树编译 + 14 护栏 + 四件回归 + 既有口径零改动"。

## 验收（谁说了算：开发者 —— `WORKFLOW.md` §4.1）
- 状态：**未验收**。已部署@2026-10-14、已回归@2026-10-14（四件全 PASS）。
- 未验收之前：不写 changelog、不发平台（只**追加**到同一份未发布的 1.3.2 文案）。

## 边界（没动什么）
- **不动建造放置路径**：`doPlace` / 补支撑 / 缺料替代 / 延后轮换 / 配额逐字未改；重放是完工后的独立一趟，只走"清空 + 直放"。
- **不动开入口与矿车**：`carveEntrance` 仍在重放**之后**（门洞不会被补齐）；轰炸机矿车仍在重放之后启动（矿车落在新铺的探测铁轨上）。
- **不动点水与 TNT 结算**：`activateWater` / `settleTntIgnition` 的顺序与口径未改——重放的液体照旧静默铺源块，由它们统一激活 / 结算。
- **不动视图封顶**：压缩盒对外的 64 封顶（含"整堆不许漏出去"的保护）在窗口外逐字保留；窗口只服务核账那一瞬。
- **不动战斗 / 跟随 / 传送**：③ 是问句，本轮零改动（答复见 §评估）。
- **不动版本号**：仍 1.3.2。

## 台账
- 清掉的债：无（本轮是开发者点名的功能 + 一条现场发现的修复，不在既有债册里）。
- 新增的债：无。
- 配额：**功能轮带一条 WARN**——`check_workflow` §8 口径"最新轮次是功能轮时不得有未清的 P0/P1 债"，而 **BD-04(P1) 仍 `未清`**。为什么不在这轮清它：本轮两项都是**开发者当场点的**（① 新功能 + ② 他实测撞到的回归），按他「后续的工程不要做，等我指挥」的长期口径，不擅自把别的债塞进来。`FAIL 0` 照旧满足（该项只有 WARN）；同 R906 / R907 / R960 的处理，如实留痕。
