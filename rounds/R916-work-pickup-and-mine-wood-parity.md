# 实测九百一十六 干活时也捡掉落物 + 挖矿套用伐木（治「站桩发呆」）

> 跑道：**A 修 bug**——两条 P1（`DEBT.md` **BD-20** 干活期间拾取任务的走位目标被关掉；
> **BD-21** 挖矿接近链缺少伐木的"绝不停下"兜底 → 站桩发呆），外加 R915 的两处收尾
> （"到位就停"加可见性条件、向下逼近加滞回）。本轮**不是功能轮**（`WORKFLOW.md` §8「P0/P1
> 未清期间不得开纯功能轮」照旧成立——BD-04 仍挂着未清），且本轮清掉两条 P1，配额口径满足。

## 排班

- 开发者指令（一次给全）：「最新版本挖矿功能好像又不是很好用了。我感觉现在砍树的逻辑挺好的，
  可以套用一下。同时女仆很喜欢站在原地发呆。在执行砍树和伐木的时候。女仆不会去捡掉落物，也就是
  说捡掉落物的target也会被关掉。」（另：「补一下」——即上一条消息里提过的向下逼近滞回，本轮一并做掉。）
- 拆成三件事：
  - **①** 干活时不去捡掉落物——拾取任务的「去捡目标」被关掉。
  - **②** 挖矿不如伐木好用 / 站桩发呆——把伐木那套「绝不停下」的机制套到挖矿。
  - **③** 收尾：R915「到位就停」可能误停隔沟/隔墙的近目标；向下逼近缺滞回。

## 定位

### ① 拾取：两层闸把「去捡东西」关掉了
- **闸一（任务不启动）**：`MaidPickupPriorityMixin.checkExtraStartConditions` 在
  `MaidMineBehavior.isMining(maid) || MaidWoodBehavior.isWooding(maid)` 时 `setReturnValue(false)`
  ——拾取任务（TLM 原生 **CORE 优先级 10** 行为）压根不启动。旧开关 `misc.pickupPriority`
  （「挖矿中禁止拾取」）**默认开**，所以默认就是"干活不捡"。
- **闸二（启动了也走不到）**：TLM 拾取任务的移动方式是 `BehaviorUtils.setWalkAndLookTargetMemories`
  写 **WALK_TARGET**、由 `MoveToTargetSink` 消费（`javap` 实证 `MaidPickupEntitiesTask.start`——
  没有直连 `moveTo`、没有 `setSpeed`）。而本模组 R913 的链路走位独占（`MaidMoveSuppressMixin`）
  在 `isChainDrive` 期间**每 tick 清 WALK_TARGET + 取消 MoveToTargetSink** → 拾取目标写了也被抹掉。
- 结论：要让干活时捡东西，**两道闸都要开**——任务得能启动（开关默认关 + 去掉干活期的否决由开关决定），
  且它写的那一发 WALK_TARGET 得被放行。

### ② 挖矿 vs 伐木：四处兜底挖矿没有
逐处对照（`MaidWoodBehavior` 好用的那套 → `MaidMineBehavior` 缺的）：
1. **无 `firstOreAbove` 抬头兜底**：伐木有 `firstWoodAbove`（tick 无目标分支 + `approachWood` 搭高失败
   分支都用）；挖矿没有 → 扫描框/排除表给不出目标、或搭高失败时，不换抬头目标 → 对着头顶的矿发呆。
2. **`findStandNearOre` 的兜底是「悬空空气格」**：15 个候选都无立足点时，伐木**故意删掉了**"目标正上方
   最近空气"兜底（那是**导航去不了的悬空格** → 寻路必失败 → 站坑发呆）并 `return null`；挖矿却保留它，
   末尾还无条件 `return t`（矿本身也在墙里/悬空）→ 同样走不到。
3. **`walkToOreBase` 找不到立足点就不设移动目标**：伐木的 `walkToWoodBase` 对 null 直接换成 `t`
   （**永远给个移动目标**）；挖矿是 `return`（不设目标）→ 站着等。
4. **`pillarUpStep` 严格 isAir 判头顶**：伐木用 `suffocates`(isSuffocating) + **排除目标木材所在格**
   （"正上方就是目标"时也能搭高）；挖矿严格 isAir → 目标矿在正上方时**搭高永远失败** → 原地等到超时。

### ③ 收尾
- R915 的「到位就停」死区 `dy==0 && hDist<=close` 只按距离判——目标隔着沟/墙时也会停 → 新增
  `hasClearSight` 条件（看得见才停，看不见继续搭桥/走位）。
- `descendStep` 成功后的 `startStepOn(…,-1)` 没加滞回（桥段 R915 加了）→ 向下逼近时仍每拍覆写目标。

## 修法 / 落刀清单（两树镜像）

1. **拾取开关默认反转为"干活时照常捡"**：`config/MaidSmartConfig` 的 `MISC_PICKUP_PRIORITY`
   `.define("pickupPriority", true)` → **`false`**（注释补「默认关 = 干活时也去捡掉落物」）。
   该开关仍在（打开 = 旧版"干活时拾取任务让位"）；只是默认不再介入。
2. **拾取驱动戳（`task/MaidWorkTags`）**：新增 `PICKUP_DRIVE_UNTIL`（`Map<Integer,Long>`，
   `ConcurrentHashMap`）+ `markPickupDrive` / `clearPickupDrive` / `isPickupDrive`——拾取任务开始驱动
   走位时打戳（30 秒安全窗口，超 1024 条兜底清空），过期/目标消化即失效。
3. **打戳（`mixin/MaidPickupPriorityMixin`）**：新增 `@Inject` 到 `MaidPickupEntitiesTask` 的 start
   （forge `m_6735_` / neo `start(...)`）HEAD → `MaidWorkTags.markPickupDrive(maid, gameTime)`；
   类文档改为「默认关 + 由链路独占闸按 `isPickupDrive` 放行」。
4. **放行窄门（`mixin/MaidMoveSuppressMixin`）**：链路独占分支里，若 `isPickupDrive` 且 WALK_TARGET
   在（说明是拾取任务刚写的那一发）→ `return`（让 `MoveToTargetSink` 照常寻路）；若戳在但 WALK_TARGET
   没了（目标已消化）→ `clearPickupDrive` 后照旧清 + 取消。**其它 WALK_TARGET 一律不执行**（独占不受影响）。
5. **挖矿套用伐木（`task/MaidMineBehavior`）：**
   - 新增 `firstOreAbove`（镜像 `firstWoodAbove`；用 `isOre` + `!isMiningPlaced` + `!blockedOres.contains`）。
   - tick `targetPos==null` 分支：先抬头兜底（`firstOreAbove` + `RECENT_DISCARD` 30 秒跳过）→ 命中就
     `targetPos=above` + 登记 `TARGET_SINCE` + `MINING.add` 并 return；没命中才走原来的
     `MINING.remove` / 播报 / `slideAnchor`。
   - `approachOre` 的 `dy>=1` 分支：`pillarUpStep` 失败后 → `firstOreAbove` 换目标；若 already-above
     则水平偏开 >1 格时**沿柱向下找第一个可站格**并 `setWalkTarget`（找不到就 `walkToOreBase`）。
   - `findStandNearOre`：删「矿正上方最近空气」兜底与末尾 `return t` → 改 **`return null`**。
   - `walkToOreBase`：`findStandNearOre` 为 null 时 **`stand = t`**（直接朝矿坐标走）。
   - `pillarUpStep` 防窒息：严格 isAir → `suffocates`(isSuffocating) + **排除 `targetPos`**；新增
     `suffocates` 私有方法（镜像伐木）。
6. **收尾**：4 处 `approach` 的「到位就停」条件加 `&& this.hasClearSight(level, maid, t)`；4 处
   `descendStep` 成功后的 `startStepOn(…,-1)` 包 `if (!BlockWalkOn.isActive(maid))`。

## 验证

- 两树编译：`javac @compile_promaid.txt`（464 源）与 `javac @compile_neo.txt`（467 源）**零错误**。
- 全套护栏：`check_comment_style` **PASS**；`check_arch` / `check_catch_swallow` / `check_comment_dup` /
  `check_dev_voice` / `check_i18n_keys` / `check_panel_coverage` / `check_reflection` **FAIL 0 / WARN 0**；
  `check_preset` 基线由 175/179 调为 **174/178**（`misc.pickupPriority` 默认翻 false → 列入 `OFF_ACK`）
  后 **FAIL 0 / WARN 0**；`check_workflow` **FAIL 0 / WARN 3**（历史遗留：PUBLISH_GUIDE 版本、
  1.2.4 归档哈希、13 个更早 jar 未登记）。`ARCHITECTURE-SWITCHES.md` 按真源重生成（**1374 行**）。
- 打 jar：两树 `verify_jar_classes` **OK**（mixin 包登记 Forge **97** / Neo **96** 个类全部登记；
  **mixin 注入签名无歧义/错名**——本轮的 `m_6735_` / `start(...)` 新注入点经此校验；out 与 jar 的
  class 集合双向一致）；lang json **OK**；构建末尾全套护栏 **FAIL 0**。
- 未跑实机回归（`WORKFLOW.md` §4 第 8 步后一律先停）。打 jar 与部署见下「部署」小节。

### 部署（2026-10-12，开发者常态指令：「以后做完功能都是先部署，但后续的工程不要做，等我指挥」）

- **打 jar**：`tools/build/build_promaid.py` → `patched/promaid-1.3.2-forge-1.20.1.jar`
  （**17580043** 字节，sha256 `4eb2a8ce…cb5e92`）；`build_promaid_neo.py` →
  `patched/promaid-1.3.2-neoforge-1.21.1.jar`（**17618058** 字节，sha256 `7fb2c157…1932ab`）。
- **装机**：`tools/deploy/deploy_all.py` → 4 个本机实例全部 **`match=True`**：1.21.1-NeoForge_21.1.250 /
  1.20.1-Forge_47.4.10 / 龙之冒险：新征程v2.4a / mc_server_test pack1201（后两个实例需提权，脚本走 UAC
  后 rc=0）。旧包已备份到 `patched/backup_old/`（17616672 / 17578630，即 R915 第五十四次字节）。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 `workspace` 行的 sha256 列更新为新字节，并追加
  「第五十五次同号重打」记录，旧字节 `01ad2e55…8f475b`（forge）/ `ed19d58b…a333e6`（neo）作废。
- **到此为止**：**未跑回归、未验收、未 commit、未 push**（开发者明确"后续的工程不要做"）。

## 验收

- 状态：`未验收`（已部署@2026-10-12：本机 4 实例与 `patched/` 逐字节一致；未 commit、未 push）。
  - 复现步骤 1（**干活时捡掉落物**）：砍一棵树 / 挖一处矿 → 地上的掉落物（原木/树苗/苹果、矿石/矿屑）
    她应当**走过去捡起来**（不再只是站桩）；`misc.pickupPriority` 打开后则回到旧版"干活时不让位"。
  - 复现步骤 2（**挖矿不再发呆**）：挖一处**嵌在岩壁里 / 上方一格**的矿 → 她应当抬头把正上方的矿
    当目标逐节啃、或走到矿柱底最近可站格（不再对着走不到的悬空格站着）；正上方就是矿时也**能搭高**
    （不再因 isAir 检查永远失败）。
  - 复现步骤 3（**"到位就停"不再误停**）：目标与女仆同层、水平很近但**隔着沟/墙** → 她应当继续搭桥/
    走位，而不是停住不动。
  - 复现步骤 4（**向下逼近**）：目标在下方多层 → 逐级下降时不来回抽动（`descendStep` 加了滞回）。
  - 判定：步骤 1 会去捡、步骤 2 不站桩、步骤 3 不会误停、步骤 4 无抖动 = 合格；异常请把
    `logs/promaid.log` 相关几行贴回。
- 回归确认（不该被本轮误伤）：链路走位独占仍生效（跟随不把她写回主人/不每 tick 清寻路；home 圈仍是
  上限）——拾取窄门只在 `isPickupDrive` 窗口内放行，其它 WALK_TARGET 照旧一律不执行。

## 边界（没动什么）

- **独占原则不变**：`MaidMoveSuppressMixin` 的链路独占只在"拾取任务刚写了去捡目标"这一发上开窄门
  （30 秒窗口 + 目标消化即清），其它任何 WALK_TARGET（跟随/闲逛/远程走位…）依旧不执行；`MaidFollowOwnerTickMixin`
  的整段取消未动。
- **挖矿的自有扩展保留**：`ABANDONED` 排除表、`mine.repick` 重挑、`MINE_REQUIRE_PICK` 没镐不启动、
  走路卡死 40 tick/3 次重寻路检测、障碍连锁、连锁耐久、`hardResetStuck` 丢死目标——**一律未动**（只补兜底）。
- **伐木侧**：只做了③的两处收尾（`hasClearSight` 条件、`descendStep` 滞回），主体逻辑未动。
- 未增删行为级**总开关**；`misc.pickupPriority` 仅改默认值与归类（`check_preset` 基线 175/179 → 174/178）；
  未改版本号（仍 **1.3.2**）。
- 结构数据按真源重生成（`ARCHITECTURE-SWITCHES.md`），模块与产物哈希一致 → `check_arch` FAIL 0；
  `tools/arch/arch_modules.py` 的行号表未动（`check_arch` 只校验产物逐字节一致、不校验行号）。
- 临时脚本（`_lg.py` / `_rel2.py` / `_f_err.txt` / `_n_err.txt`）用后即删。

## 台账

- `DEBT.md`：新增 **BD-20**（P1，已清@实测九百一十六：干活时拾取走位目标被关掉）与 **BD-21**
  （P1，已清@实测九百一十六：挖矿接近链缺伐木兜底 → 站桩发呆）；§0 索引「挖矿 / 伐木 / 农场」行加入
  `BD-20 · BD-21` 并补收益措辞。
- 待办（下一轮或开发者指令）：实机验收"干活捡掉落物 / 挖矿不站桩 / 到位不误停"；验收/部署后按
  `WORKFLOW.md` §4 第 10 步开闸再跑回归；`git commit` 后重跑 `python tools/arch/build_arch.py`。
