# 实测九百一十三 女仆行动逻辑整改（最近优先 / 干活与接战期间原版走路逻辑整体让位 / 接战圈翻倍）

> 跑道：**A 修 bug**（P1：行动逻辑诡异 + 不优先锁定最近的，见 `DEBT.md` BD-17）——本轮的开轮理由与唯一主项。
> 本轮**不是纯功能轮**（`WORKFLOW.md` §8「P0/P1 未清期间不得开纯功能轮」照旧成立——BD-04 仍挂着未清），
> 且本轮已清掉一条 P1（BD-17），配额口径两侧都满足。

## 排班
- 开发者指令（一次给全）：「这个问题还是没有解决。女仆的行动逻辑很诡异，可以看看我刚刚运行的游戏。同时伐木和
  挖矿的时候不优先锁定最近的。老版本的优先级现在也可以废除掉了。…我觉得伐木/挖矿的链路应该是这个样子的：
  每隔一段时间搜寻范围内有没有树木（会受到除了树叶以外的视线阻挡），有的话就尝试过去，优先搭方块/拆方块把高度
  一致，然后是水平方向上靠近（也采用垫方块和挖掘的方式进行辅助）。到了可交互范围之后，执行挖掘。确认挖掘完成
  之后再重复上述步骤。如果没有树木，则回归空闲状态的走路逻辑。…进行整改：如果发现了目标，并且成功激活了链路。
  那么原版TLM所有走路逻辑被覆盖掉。切换成别的模式或者失去目标以后再回复。战斗方面，发现敌人之后原本的直接禁用，
  传送判定半径提升一倍」。
- 定位顺序：先看**实机日志**（NeoForge 2026-10-12 22:33–22:40 那一段）确认"诡异"的具体表现，再回源码核对
  链路形态与选目标评分，最后用 `javap`（`libs_forge/original_tlm.jar` / `libs_neo/*.jar`）钉死跟随行为的
  `start` 签名与它在"传送被拦下"之后还会做什么。

## 定位（BD-17）
- **日志实证**（`promaid.log`）：这一局伐木/挖矿链路都在跑（`wood behavior start` ×2、橡木连锁 ×3、树叶爆破 ×4；
  铜/煤/铁连发、`mine autoCollect` ×110），但同一时段还有：**工作期间的一次传送**（`[属性补包] 传送后补属性表`
  + `[重同步]`，22:39:10）、**实体三次被替换**（`[离场] reason=DISCARDED`，id 16→1977→2169→2267）、
  6 次坠落伤害（y 在 77↔89 之间弹）；另有 `wood chain empty … grass_block/dirt/stone (no adjacent wood)` 刷屏、
  `mine stuck-reset` ×2、聊天栏"我没有斧头"。结论：不是链路没启动，而是**多条驱动各自为政**（跟随 / 排班 /
  其它行为写的 `WALK_TARGET` / 传送重同步）把她从目标上拽走。
- **为什么扩圈（R911/R912）不够**：扩圈只把"太远就回去"的**判据**顶开，**跟随行为本身照跑**——它没覆写
  `canStillUse`（父类默认 false）⇒ 每 tick 都走一次 `start`；任何一次读数回落（切任务瞬间、主人贴近到阈值内）都会
  重新把她写回主人。加之 `MoveToTargetSink` 会消费**别的行为**写的 `WALK_TARGET`（闲逛/远程走位等）。
  开发者要的是"**覆盖掉**原版走路逻辑"，而不是"把它的判据调松"。
- **不优先锁定最近的**：两树各两处评分（`fullScanWoods` / `pickFromCache`、`fullScanOres` / `pickFromCache`）
  都是「距离² + 深度惩罚 × 深度 − 价值 × 价值权重」。木材侧价值 300 × 权重 2.0 = **减 600 分**，
  足够把圈内最远的树压过脚边那块；挖矿侧同理（钻石 500 × 2 = 减 1000）。这就是"不优先锁定最近的"根因。
- **链路形态**：开发者描述的"先竖后横、到可交互范围再挖、挖完重复"其实**已经是现状**——`approachWood` 的顺序
  就是 `pillarUpStep → slopeStep → descendStep → bridgeToWood/walkToWoodBase`（垂直优先），且 `hasClearRay`
  早已**跳过树叶**（树叶不挡视线）。所以本轮不需要重写链路，只补"最近优先 + 走路独占 + 无目标回空闲"三处。

## 修法 / 落刀清单
1. **链路走位独占标记**（`task/MaidWorkTags`，两树）：新增 `CHAIN_DRIVE_TAG` + `setChainDrive` / `isChainDrive`。
   判据 = 「挖矿/伐木行为手上锁着目标（行为每 tick 维护）」**或** 「正在接战（`CombatWorkRange.inCombat`）」，
   并加坐着 / 被骑 / 脑冻结 / 无任务四道防残留闸。
2. **两条让位闸**：
   - `mixin/MaidMoveSuppressMixin`（两树）：在既有"插火把让位"分支后新增一条「链路让位」——清 `WALK_TARGET`
     （`MemoryModuleType.f_26370_` / `WALK_TARGET`）+ 取消 `MoveToTargetSink`。本模组链路的走位是**直连
     `moveTo`**（`MaidWorkBehavior.setWalkTarget`），不走 `WALK_TARGET`，所以拦 sink 不会挡她自己。
   - `mixin/MaidFollowOwnerTickMixin`（两树）：新增对跟随行为 `start`（SRG `m_6735_`）的 **HEAD 整段取消**。
     必须在 `start` 上砍：跟随的「把主人写回她的寻路目标」与「传送」两个分支都落在 `start` 里，而**传送被拦下后
     TLM 紧接着无条件 `resetNavigation()`**（停寻路 + 导航模式回 GROUND）——只在传送点拦截拦不住这一下。
3. **最近优先**（两树共 4 处评分点）：`fullScanWoods` / `pickFromCache`（`MaidWoodBehavior`）、
   `fullScanOres` / `pickFromCache`（`MaidMineBehavior`）的 `score` 一律改成 `distSqToBlock(maid, p)`；
   连带删掉只为打分存在的 `feetY` / `valueWeight` / `depthPenalty` 局部量与过时注释。
4. **废除旧优先级**（开发者："老版本的优先级现在也可以废除掉了"）：删除 `mine.valueWeight` /
   `mine.depthPenalty` / `wood.valueWeight` / `wood.depthPenalty` 四个配置项——字段声明 + `.defineInRange`
   定义（`config/MaidSmartConfig` 两树）、面板行（`config/PromaidConfigScreen` 两树）、中英译文键
   （`assets/maid_smart/lang/{zh_cn,en_us}.json` 两树）、手册文案（`guide/GuideChaptersWork` 的「选矿逻辑」
   与"价值权重"两处，两树）、`机制详解.md`。**矿/木材的 `OR E_VALUE`/`WOOD_VALUE` 表保留**——它仍用于
   "挖不动时的播报挑哪块报"，只是不再参与排序（注释已同步说明）。
5. **无目标 → 回归空闲走路**：`slideAnchor`（挖矿/伐木各一处）**不再朝空框设移动目标**（旧版注释写着
   "空框时也在赶路，不原地干等"）——框空 = 没活，就交给原版空闲/跟随走路；她走到哪，下一轮 `relocate`
   会把搜索框埋回她脚下。行为停转（`m_6732_`/`stop`）当拍清 `CHAIN_DRIVE_TAG`。
6. **接战圈翻倍**：`combat/CombatWorkRange.radius` 去掉 `!hasRestriction() → 原样` 那道 home 自限，
   读数改为 `max(常态 × 2, combatWorkRange)`（旧口径 `max(常态, 15)` 在常态 ≥15 时形同没扩）；
   类文档与 `radius()` 文档同步。**不新增配置项**（沿用 `combat.workRange`）。
7. **测绘/门禁同步**：`ARCHITECTURE-SWITCHES.md` 按生成器重刷（1375 行）；`panel_coverage.txt` 重生成
   （527 / 536 项）；`check_preset` 接点不变（无新增/删除开关，删掉的是数值项）。

## 验证
- 两树编译：`compile_promaid.bat` **EXITCODE=0（465 源）**、`compile_neo.bat` **EXITCODE=0（468 源）**。
- 全套护栏：`check_comment_style` **PASS**（首轮曾因 `MaidMineBehavior` 类头 16 行 > 15 报 LONG-BLOCK ×2，
  压缩类头一行后 PASS）；`check_arch` / `check_catch_swallow` / `check_comment_dup` / `check_dev_voice` /
  `check_i18n_keys` / `check_panel_coverage` / `check_preset` / `check_reflection` 全部 **FAIL 0 / WARN 0**；
  `check_tree_mirror` 报告完成（只读）。
- 打 jar：两树 `verify_jar_classes` **OK**（mixin 包登记 Forge **97** / Neo **96** 个类全部登记、无未登记
  「鸭子接口」；**mixin 注入签名无歧义/错名**；out 与 jar 的 class 集合双向一致），lang json **OK**；
  构建末尾全套护栏 **FAIL 0**（`_mixchk.py` 不在库内、构建按约定 WARN 跳过，非本轮引入）。
- 未跑实机回归（`WORKFLOW.md` §4 第 8 步后一律先停）。打 jar 与部署见下「部署」小节。

### 部署（2026-10-12，开发者常态指令：「以后做完功能都是先部署，但后续的工程不要做，等我指挥」）
- **打 jar**：`tools/build/build_promaid.py` → `patched/promaid-1.3.2-forge-1.20.1.jar`（**17583783** 字节，
  sha256 `d15364ac…e7920a`）；`build_promaid_neo.py` → `patched/promaid-1.3.2-neoforge-1.21.1.jar`
  （**17621856** 字节，sha256 `cbd690d8…9a8ffa`）。
- **装机**：`tools/deploy/deploy_all.py` → 4 个本机实例全部 **`match=True`**（与 `patched/` 逐字节一致）：
  1.21.1-NeoForge_21.1.250 / 1.20.1-Forge_47.4.10 / 龙之冒险：新征程v2.4a / mc_server_test pack1201
  （后两个实例需提权复制，脚本走 UAC 后 rc=0）。旧包已备份到 `patched/backup_old/`
  （第五十一次的 17622658 / 17584584；本轮 neo 首次打包后因镜像对齐又重打过一次，
  两次都只在 `MaidFollowOwnerTickMixin` 的注入目标类型上改了一处）。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 `workspace` 行的 sha256 列更新为新字节，并追加
  「第五十二次同号重打」记录，旧字节 `08c8d209…7b2f77` / `ed86c3a8…ff54de` 作废。
- **到此为止**：**未跑回归、未验收、未 commit、未 push**（开发者明确"后续的工程不要做"）。

## 验收
- 状态：`未验收`（已部署@2026-10-12：本机 4 实例与 `patched/` 逐字节一致；未 commit、未 push）。
  - 复现步骤 1（**最近优先**，主项）：给伐木任务，在脚边和十几格外各放一棵树 → 她应先砍**脚边**那棵
    （旧版会被远处高价木/深惩罚打分拉走）。挖矿同理：脚边煤矿 vs 远处钻石矿 → 先挖**脚边**的。
  - 复现步骤 2（**走路独占**）：伐木/挖矿进行中（她已锁上目标）把主人走远、或让她穿过闲逛生物群 →
    她**不应**被拽回主人、也不应被 `WALK_TARGET` 改写而掉头（原版跟随/闲逛逻辑整体让位）。
  - 复现步骤 3（**接战翻倍**）：给她战斗任务（或让怪打她）→ 她追出去的距离应比原来**远一倍**
    （`combat.workRange` 仍有效，取 `max(常态 × 2, 该项)`）；脱离战斗后该回位回位。
  - 复现步骤 4（**无目标回空闲**）：把伐木/挖矿任务挂着但附近**没有**树/矿（比如清空一片草地）→
    她**不应**朝着"搜索框中心"自己赶路，而应回到空闲/跟随主人的走路状态。
  - 判定：步骤 1 先近后远、步骤 2 不再被拽走、步骤 4 不空跑 = 合格；异常请把 `logs/promaid.log`
    相关几行贴回。
- 面板/文案验收：挖矿与伐木面板里**不应**再出现「价值权重」「深度惩罚」两行；手册「选矿逻辑」应写
  "最近优先"。

## 边界（没动什么）
- 未重写挖矿/伐木链路（开发者描述的"先竖后横、到可交互范围再挖、挖完重复"本就是现状：
  `approachWood` 垂直优先、`hasClearRay` 早已跳过树叶）；未改工具/连锁/搭方块/换镐等既有机制。
- 未改 `MaidWorkRangeExpand` 与 R912 的扩圈读数（那是另一条腿，两者互补：扩圈顶开判据 + 独占覆盖行为）。
- 未新增/未改任何战斗数值与开关（接战翻倍复用 `combat.workRange`）；未改版本号（仍 1.3.2）。
- **`ARCHITECTURE-SWITCHES.md` / `panel_coverage.txt` 已按真源重生成**；`tools/arch/arch_modules.py` 里各
  代表文件的 `end_line` 数字**未动**——实测该表 233 条里已有 **168 条**与文件实际行数不符（历史漂移，
  `check_arch` 只校验产物与真源逐字节一致、不校验行号），本轮只改 6 个文件的行数就动它属于治标，
  留作测绘侧的专项（与本轮修复无关）。
- 已知口径（有意保留）：`isChainDrive` 判的是"行为手上锁着目标"而不是"刚启动扫描"——扫描/迁移期间（无目标）
  她归原版走路；这是开发者点名的"没有树木则回归空闲状态"。
- 临时脚本（`_g.py` / `_rel.py` / `_arch.py` / 先前遗留的 `_logscan.py`）用后即删。

## 台账
- `DEBT.md`：新增 **BD-17**（P1，已清@实测九百一十三，含日志 + 源码证据链）+ §0 索引行更新
  （BD-16 · BD-17 并列）。
- 待办（下一轮或开发者指令）：实机验收；验收/部署后按 `WORKFLOW.md` §4 第 10 步开闸再跑回归；
  `git commit` 后重跑一次 `python tools/arch/build_arch.py`（提交后修订哈希会前移，`check_arch` 会以 WARN 提示，属预期）。
