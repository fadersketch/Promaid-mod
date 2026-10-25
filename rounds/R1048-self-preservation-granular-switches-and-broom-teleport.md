# R1048 自保开关拆细 + 各功能总开关 + 骑扫帚时任何传送都带上扫帚（两树同步）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修 bug / 调行为（1.21.1 NeoForge + 1.20.1 Forge 同步；自保改开关与门控，
  轰炸按炸弹种类拆闸，对话/AI 加总闸并明确作用域，扫帚只改传送搬运）。
- **开工前读过**：`rounds/R1047`、两树 `MaidSmartConfig` / `SelfPreservationBehavior` /
  `PromaidConfigScreen` / `MaidBroomKit` / `MaidChunkLoadManager` / `EntityMoveToMarkMixin`、
  `ARCHITECTURE-SWITCHES.md`、最近的扫帚与自保实机日志。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 自保拆闸 | 环境自保 / 低血自保两总闸 + 12 个分动作开关 | ☑ |
| 1 | 火焰惊慌与火焰自救分离 | 火焰惊慌默认关；关掉时只保留灭火器/水桶/珍珠的瞬时自救，不导航、不停任务、不进会话 | ☑ |
| 2 | 低血动作逐个门控 | 喝药、垫高、逃跑、传送各自受开关控制；总闸关时行为不启动 | ☑ |
| 3 | 低血默认值按验收调正 | 总闸默认开；喝药/进食默认开；垫高、逃跑/周旋、传送默认关 | ☑ |
| 4 | 轰炸按炸弹种类拆闸 | 总开关 + 末地水晶 / 重生锚 / 床 / TNT 四个子开关；删除旧「空袭/地面」说法 | ☑ |
| 5 | 运行期门控 | 轰炸两个 tick 入口与各链路就绪判定、对话三个 tick 入口、AI 工具执行闸 | ☑ |
| 6 | 任务模式不加总闸 | 建造 / 挖矿 / 伐木 / 烧制 / 酿造由玩家选不选这个任务决定，不设总开关 | ☑ |
| 7 | 面板 / 译文 / 预设 / 覆盖清单 | 面板新增轰炸/对话/AI 总闸与四个炸弹子项、4 份 lang、`panel_coverage.txt`、`ARCHITECTURE-SWITCHES.md` 同步 | ☑ |
| 8 | 扫帚传送统一拦截 | `Entity.teleportTo(ServerLevel, …)` 的 HEAD 接住骑扫帚的女仆，四步搬运 | ☑ |
| 9 | 两树编译 | Forge / Neo 均 `EXITCODE=0` | ☑ |
| 10 | 护栏与专服回归 | 14 护栏 `FAIL 0 / WARN 0`；`test_server` / `test_maid_load` 两套均 PASS | ☑ |
| 11 | 部署与登记 | 4 个实例逐处 `match=True`；RELEASES 更新正式 1.3.3 两行 | ☑ |

## 需求 / 来源

- 开发者原话（自保拆分）：「自保功能的开关不够细，分为低血自保和环境自保，而且不同行为需要分别有开关，
  而不是只有一个管所有。低血自保功能改为默认关。火焰惊慌默认关（有保护，但女仆行为不变，被烫了以后
  会有传送/放水自救，但女仆行为不变）」
- 开发者原话（默认值修正 + 其他功能组）：「低血自保默认开，喝药/进食默认开。垫高、逃跑/周旋、传送默认关。
  promid 的其他功能项也要像这样，分的足够细，而不是笼统一个开关（要有一个总开关，以及各分项子开关）」
- 开发者原话（轰炸）：「轰炸那一栏应该是一个总开关，子开关：tnt，末地水晶，重生锚，床分别启用。
  不存在空袭/地面。那是旧旧版本的企错误说法。」
- 开发者原话（任务模式）：「建造，挖矿，伐木这些本mod自己做的模式不需要总开关，因为它们是任务，
  使不使用只取决于玩家是否选择这个任务模式。设置这个总开关意义不明」
- 开发者原话（AI 作用域与静默）：「AI那一栏的总开关只影响我们自己的AI功能，不影响TLM自己的/其他mod的。
  同时如果关闭了AI功能，就不要再在聊天栏发送AI功能已关闭的提示了，每隔一会儿就蹦一条很烦」
- 开发者原话（扫帚）：「女仆处于扫把模式骑在扫把上时，女仆任何传送时也带上她的扫把」
- 开发者原话（版本）：「版本还是1.3.3，因为之前发的是beta版，正式版未发，故正式版本号还是1.3.3」

## 自保：两层总闸 + 分动作开关

原 `combat.selfPreserve` 一个开关管全部。现在保留这个 TOML 键作为**环境自保总闸**（旧存档兼容），
新增低血自保总闸与各动作开关：

| 面板行 | TOML 键 | 默认 | 作用 |
|---|---|---|---|
| 环境自保 | `combat.selfPreserve`（旧键名不变） | 开 | 岩浆/着火/溺水/窒息/伤害方块的总闸 |
| 环境·岩浆逃生 | `selfPreserveEnvLava` | 开 | 岩浆内走出/垫脚/水桶/珍珠/抗火链 |
| 环境·着火自救 | `selfPreserveEnvFireRescue` | 开 | 灭火器/水桶/珍珠的瞬时自救 |
| 环境·火焰惊慌 | `selfPreserveEnvFirePanic` | **关** | 着火后逃跑/导航/打断任务的完整惊慌链路 |
| 环境·溺水自救 | `selfPreserveEnvDrown` | 开 | 上浮/水肺/找空气格 |
| 环境·窒息脱困 | `selfPreserveEnvSuffocate` | 开 | 卡墙顶出/找空气格 |
| 环境·伤害方块脱离 | `selfPreserveEnvDamageCell` | 开 | 营火/岩浆块等接触伤害格 |
| 低血自保（默认开） | `selfPreserveLowHp` | **开** | 低血总闸；关时整条低血自保不启动 |
| 低血·喝药/进食 | `selfPreserveLowHpHeal` | **开** | 治疗药水/食物/金苹果阶梯 |
| 低血·垫高 | `selfPreserveLowHpPillar` | **关** | 搭高/塔顶/强制垫高 |
| 低血·逃跑/周旋 | `selfPreserveLowHpFlee` | **关** | 拉开距离/反击/卡住脱身 |
| 低血·传送 | `selfPreserveLowHpTeleport` | **关** | 珍珠/归位传送/退出传送 |

门控口径：

- `checkExtraStartConditions` / `canStillUse` 只在「环境总闸 或 低血总闸」至少一个开时返回 true。
- 低血会话只在总闸开、血量低于进入阈值、且至少一个低血动作开关开时进入；全关时不会白进会话、不会让位移动。
- 会话来源用 `sessionFromLowHp` 记录：低血会话的退出归位传送只受「低血·传送」控制，环境会话不受影响。
- `stop()` 清 `sessionActive` / `PRESERVE_TAG` / 垫高状态，避免关掉总闸后残留标记继续让位排班与战斗。
- **火焰惊慌关 ≠ 不保护**：`tickFireRescue` 每 tick 只做灭火器 → 水桶 → 末影珍珠三类瞬时动作，
  不写导航、不切任务、不进自保会话；火焰惊慌打开时才由原 `environmentalEscape` 的着火分支接管。
- 低血默认改为「总闸开、只留喝药/进食」：新档开局即有保命回血，但不会自动垫高、逃跑或传送。

## 轰炸：总开关 + 四个炸弹子开关

旧版把放置类炸弹笼统写成「战斗模式轰炸」，还按「空袭 / 地面」分类——这是旧旧版本留下的错误说法。
本轮改成一条总闸 + 四种炸弹各一条子开关，不再区分空袭/地面：

| 面板行 | TOML 键 | 默认 | 作用 |
|---|---|---|---|
| 轰炸总开关 | `bombing.enable` | 开 | 四条链路全部停手 |
| 末地水晶炸弹 | `bombing.endCrystal` | 开 | 黑曜石/基岩底座 + 末地水晶 |
| 重生锚炸弹 | `bombing.respawnAnchor` | 开 | 重生锚 + 萤石充能（维度闸照旧） |
| 床炸弹 | `bombing.bed` | 开 | 床（维度闸照旧） |
| TNT 投掷 | `bombing.tnt` | 开 | 朝最近敌人扔 TNT + 点火料 |

- 总闸关 = 四条都停；子开关的勾选值保留，重新打开总闸即恢复。
- 运行期按种类分别判：`readyKinds` 里水晶/重生锚/床各读自己的开关，TNT 读 `bombing.tnt`；
  `BombTntTick` 的两个入口只在「总闸开 + 至少一条放置类子开关开 + TNT 开关」时继续。
- 面板与 TOML 注释里的「空袭 / 地面 / 近战空袭 / 远程空袭」旧说法已全部删除；
  栏目名从「空袭轰炸」改为「轰炸」。

## 对话与 AI：作用域与静默

| 面板行 | TOML 键 | 默认 | 作用 |
|---|---|---|---|
| 对话与播报总开关 | `dialogue.enable` | 开 | 工作播报 / 自主决策 / 主动搭话三个 tick 入口 |
| Promaid AI 工具总开关 | `aitools.enable` | 开 | 只拦本模组自己登记的 21 个工具 |

- 对话总闸只停「工作状态播报 / 自主决策 / 主动搭话」，玩家主动发起的聊天不受影响。
- AI 工具在 `registerAITool` 时统一套 `GatedTool`；注册发生在初始化，所以开关在**每次调用**时读。
- **作用域**：`GatedTool` 只包本模组登记的工具，TLM 自带工具与其它模组的工具完全不受影响。
- **关闭后静默**：总开关关闭时不再回「AI 功能已关闭」之类工具结果；LLM 关闭拦截也移除了聊天栏系统提示，
  避免主动对话反复触发时刷屏。

## 任务模式不设总闸

建造 / 挖矿 / 伐木 / 烧制 / 酿造都是**任务模式**：用不用只取决于玩家是否选中这个任务，
再叠一条「总开关」语义重复。因此本轮不新增这五个总闸，行为恢复为只看任务类型。

## 扫帚：任何传送都连人带扫帚

根因：原版 `Entity.teleportTo(ServerLevel, …)` 第一件事是 `unRide()`。直接传她 = 她从扫帚上被踹下来，
扫帚留在原地；此前只有 `recallBroomRider` / `MaidHeatEscape` 等少数链路各自做了四步搬运，
排班表、原版 `/tp`、别的模组的传送仍会漏。

修法（两树同一口径）：

- **专属判据**：只有「当前任务 = `maid_smart:broom`」**且**「正骑着 TLM `EntityBroom`」才接管；
  别的任务里她就算坐在扫帚上，也按原版传送（扫帚留在原地）。
- `EntityMoveToMarkMixin` 在 `Entity.teleportTo(ServerLevel, double, double, double, Set, float, float)`
  的 `HEAD` 增加一个 `@Inject(cancellable = true)`；Neo 用官方名描述符，Forge 用 `m_264318_`。
- 命中 `EntityMaid` 时调 `MaidBroomKit.teleportWithBroom(...)`：
  1. 她正骑着 TLM `EntityBroom` → `stopRiding()`；
  2. 扫帚 `teleportTo(dest, …)`；
  3. 她 `teleportTo(dest, …)`（线程内 `TELEPORTING` 闸防止递归再次进入本拦截）；
  4. 按 UUID 在目标维度取回扫帚（跨维度是 `changeDimension` 造的新实例）→ 清速度/意图 → `startRiding(force)`。
- 没骑扫帚 → 返回 `null`，mix-in 不取消，原版传送一字不改。
- 已有的 `broomRiderTo` 四步搬运不受影响：它先 `stopRiding()`，内层 `teleportTo` 时已不是乘客，
  `teleportWithBroom` 直接放行。

覆盖链路：排班表「召她过来 / 一键集合 / 传送到我身边」、自保归位、烫伤脱困、原版 `/tp`、
其它模组直接调用 `Entity.teleportTo` 的传送。TLM 的 `teleportToOwner` 原本就有扫帚豁免，继续不拉。

## 改动文件

自保 / 总闸 / 面板：

- `promaid_src_neo/com/maidsmart/config/MaidSmartConfig.java`
- `promaid_src_neo/com/maidsmart/config/PromaidConfigScreen.java`
- `promaid_src_neo/com/maidsmart/combat/SelfPreservationBehavior.java`
- `promaid_src_neo/com/maidsmart/combat/BombConfig.java` / `BombTntTick.java` / `MaidBombing.java`
- `promaid_src_neo/com/maidsmart/dialogue/WorkStatusReporter.java` /
  `AutonomousTaskManager.java` / `ProactiveDialogueManager.java`
- `promaid_src_neo/com/maidsmart/mixin/MaidChatLlmGateMixin.java`（关闭时静默）
- `promaid_src_neo/com/maidsmart/tool/GatedTool.java`（新类）
- `promaid_src_neo/com/maidsmart/ProMaidExtension.java`（21 个工具套 `GatedTool`）
- `promaid_src_neo/assets/maid_smart/lang/zh_cn.json` / `en_us.json`
- `promaid_src/...`（Forge 树同名镜像，SRG 映射；`GatedTool` 同一份源码）
- `promaid_src/assets/promaid/panel_coverage.txt`、`promaid_src_neo/assets/promaid/panel_coverage.txt`
- `ARCHITECTURE-SWITCHES.md`、`normify-promaid/*`（`tools/arch/build_arch.py` 重跑）
- `tools/verify/check_preset.py`（撤销低血总闸白名单、加入三个默认关子项，DIY 基线 119 / 122）
- `tools/arch/arch_modules.py`（登记 `GatedTool`）

扫帚：

- `promaid_src_neo/com/maidsmart/combat/MaidBroomKit.java`
- `promaid_src_neo/com/maidsmart/mixin/EntityMoveToMarkMixin.java`
- `promaid_src/...`（Forge 树同名镜像，`m_264318_` / `m_8127_` / `m_7998_` 等 SRG 名）

版本 / 构建：

- `rounds/RELEASES.tsv`：原 `v1.3.3-beta` 两行保持 `archived`（原包在
  `patched/backup_old/v1.3.3-beta-*.jar`）；正式 1.3.3 两行更新为本轮哈希。
- 版本号仍为 **1.3.3**。

## 验证

- 两树编译：Forge / Neo 均 `EXITCODE=0`。
- 全部 14 个护栏：`FAIL 0 / WARN 0`（含 `check_workflow`、`check_mixin_targets`、`check_preset`、
  `check_panel_coverage`、`check_i18n_keys`、`check_arch`）。
- 专服：`test_server.py 1201` PASS、`test_maid_load.py 1201` PASS；
  `test_server.py neoforge1211` PASS、`test_maid_load.py neoforge1211` PASS。
- 包体：
  - Forge 18,067,177 字节，SHA256
    `28907EA8CC9AF23D37872BA84BC7ECFB7C4E96DCD6CC582E57D77907978F8ECA`；
  - Neo 18,159,923 字节，SHA256
    `57B4FEFC220336EC0A5B94CE589D49861F1AB2F662BA57B755FCBC0B6886F65A`。
- 已部署 4 个实例（1.21.1、1.20.1 测试、龙之冒险主整合包、server pack1201），逐处 `match=True`。

## 验收

1. 面板「自保」分组能看到 12 行：环境自保 + 6 个环境动作，低血自保 + 4 个低血动作。
2. 新存档 / 未改过配置时：低血自保为**开**、喝药/进食为开，垫高/逃跑/传送为**关**；
   低血时女仆会吃药或进食，但不会自动搭高、走位或传送。
3. 火焰惊慌为关时：她被点燃仍会立刻灭火器/放水/珍珠自救，但任务不中断、不逃跑、不搭高。
4. 打开低血自保后，四个低血动作可分别开关；关掉「低血·传送」时，低血会话结束不会自动归位。
5. 建造 / 挖矿 / 伐木 / 烧制 / 酿造页**没有**总开关；用不用只由玩家选择对应任务模式决定。
6. 轰炸总开关关掉后，四条链路都不再放炸弹或扔 TNT；下面「末地水晶 / 重生锚 / 床 / TNT」四个子开关可分别开关。
7. 面板与注释里不再出现「空袭/地面」的轰炸分类；栏目名是「轰炸」。
8. 对话与播报总开关关掉后，卡住播报、自主决策、主动搭话都停；玩家主动聊天仍正常回复。
9. Promaid AI 工具总开关关掉后，本模组工具不再执行，且**不会**在聊天栏反复出现「AI 功能已关闭」；
   TLM 自带 AI 与其它模组的工具不受影响。
10. 扫帚模式女仆骑着扫帚时，用排班表召回、一键集合、原版 `/tp` 或其它模组传送：
    她与扫帚一起到达，落地后仍骑在扫帚上，不出现"人到了、扫帚留在原地"。
11. 别的任务里她即使骑在扫帚上，传送仍按原版：只传人、扫帚留在原地（本机制是扫帚模式专属）。
12. 没骑扫帚的女仆传送行为与旧版完全一致。

## 边界

- 扫帚拦截只认 TLM 的 `EntityBroom`，且**只在扫帚模式任务 + 骑着扫帚时**生效；船/矿车/其它模组的载具、
  以及别的任务里骑扫帚，都仍走各自既有链路。
- 跨维度时按 UUID 取回 `changeDimension` 造出的新扫帚实例；若扫帚搬运失败，女仆仍会传送到目标点，
  不会把人留在原地，但可能出现"人到了、扫帚没跟上"的退化（与旧版一致，不新增更坏行为）。
- 低血总闸默认开，但垫高/逃跑/传送默认关——玩家想要完整阶梯时逐个打开即可。
- 各总闸只拦「女仆主动去做」的链路；玩家手动操作（手动下达任务、手动聊天、手动传送）不受影响。
- 任务是玩家主动选择的模式，建造/挖矿/伐木/烧制/酿造不设总闸；要停就直接切走任务。
- 版本号仍为 **1.3.3**（正式版未发布，beta 件已归档；不是 1.3.4）。
