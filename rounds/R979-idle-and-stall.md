# R979 · 空闲接管真接管 · 矿表网格两态 · 卡死自愈

## 排班

1. 开发者回报三件事：①无目标静默 10 秒没生效（她只跟主人两秒就原地走一步停一步）；
   ②「目标矿物」网格同时出现“打叉的未选中”和“打勾的已选中”（点右上角小叉应回到未选中状态，
   自动识别口径要与面板一致）；③ 00:35 女仆原地停着不动，不报错、无恢复链路，周围环境正常。
2. 用开发者 NeoForge 实例的日志把 ③ 定位到「挡路块走不到→改走接近链」这条死循环出口（它绕开了唯一的卡死检测），
   并确认 ①② 的代码根因。
3. 三件一起改：`idleWander` 改「到点即换点、点距 ≥ 2.5 格、别人在驱动就让位」；
   网格删掉红框红叉第三态（状态挂悬停信息栏）；挖矿/伐木 tick 头部加静默自愈看门狗
   （160 拍不动 = 放弃当前目标 + 拉进 30 秒「暂时够不到」+ 清走位 + 播报 + 留痕，
   连着 3 次丢弃整片缓存重扫）。
4. 编译 / 护栏 / 打包 / 部署 / 回归 / 文档台账。

## 需求 / 来源

- ①「我用的那种实现方式你还是没有做到。我要求的是当女仆播出的周围已经没有矿物和树木的语音的时候。
  空闲的运作逻辑暂时接管 10 秒钟。但是实际上并没有。女仆顶多像空闲状态一样，跟随个
  主人两秒钟左右，然后就站在原地，开始走一步停一步了。」
- ②「你给我整的什么东西啊？一张图里面。有未选中的打叉的和打勾的，这成何体统？
  我要求的是当你点击绿色框右上角的叉叉的时候。直接回到跟没选中的状态一样。然后挖矿的
  自动识别走的面板识别路线也跟这个面板是一模一样的。也就是说在这个面板里面，背景是未选中
  的状态，那么就不选中它，而不是单独像这个样子。」
- ③「0：35时，女仆在原地一直停着不动，也不报错，也没有恢复链路。周围的环境很正常。
  帮我排查一下。」

## 改法

### ① 10 秒静默没接管 —— `idleWander` 钉死在同一个点

旧实现每 40 拍取一个闲逛点写进 `WALK_TARGET`，取点半径可能只有 3、落点可能离她不到 1 格，
**到达后也不换点**——于是：

- 点太近 → 一步就到，到了就“完成”了，TLM 不再驱动她 → 站住；40 拍后再取一个同样近的点
  → 再走一步 → 又站住。玩家看到的就是“走一步停一步”。
- 她还**无条件**往 `WALK_TARGET` 里写点：主人正在跟随带路、或 TLM 自己的行为正在驱动她时，
  这个点会**抢**内存里的走路目标，把跟随打断——所以“只跟主人两秒就不跟了”。

改成（`MaidWorkBehavior.idleWander`）：

- **上一轮的点还没走到就继续走**（`wanderSpot` 距她 > 1.5 格且未超 4 秒），
  不换点、不重设，避免每拍重设把 TLM 拖成顿挫；
- **到达即到期**：走到了（或超 4 秒）才允许取下一个点，节流 10 拍；
- **新点必须离她 ≥ 2.5 格**（连抽 4 次都不合格就本轮不逛），且在 `WorkAreaClamp` 允许圈内；
- **别人在驱动就让位**：`wanderSpot == null` 且内存里已有 `WALK_TARGET` 时直接 return，
  绝不抢主人/任务正在用的走路目标。

### ② 网格第三态 —— 删除红框红叉，状态改挂悬停

`PromaidConfigScreen` 的矿物网格里，被“叉掉”（表里写 `!id`）的方块有独立第三态（红框 + 红 ✖），
和“没加进去”（纯空白）在同一张图里共存 → 玩家看到的就是“一张图里又有打叉的又有打勾的”。改成：

- 网格**只画两态**：已加入 = 绿框 + ✓ + 右上角小叉；其余（没加过 / 移出过）**一律纯空白**，视觉上完全一致；
- 右上角小叉的点击语义不变，仍是 `removeOre` → `setOreBlacklist(true)` → 表里写 `!id`；
- `!id` 的**数据语义不变**：`MaidMineBehavior.loadCustomOres` 照样把它收进 `ORE_EXCLUDED`，
  `isTargetOre()` 判据不变 → “自动识别走的面板识别路线”与面板一致（面板上空白 = 不选中它，
  她就真不挖它，标签自动识别也不会把它认回来）；
- 想区分“移出过”和“没加过”：悬停到该方块，右侧信息栏第三行会在「被移出」时显示红字
  `已移出（自动识别也不算）`；
- 标题与两句底部说明改写：小叉 = 移出；✓ = 已加入；空白 = 未加入。

### ③ 00:35 卡死 —— 挡路分支绕开了唯一的卡死检测

从开发者 NeoForge 实例 `logs/latest.log` / `logs/promaid.log` 复现（会话 00:28:13~00:36:05）：

- 00:33:19~00:35:58 之间，同一只女仆（`af629870-085f-4238-9163-b85497e024b3`）反复打同一行出口：
  `work exit: maid=… reason=mine 挡路块走不到→改走接近链 detail=blocker=BlockPos{x=-1551,y=97,z=-771} …`（8 次）；
- 该女仆 00:34:49 → 00:35:33 之间有 44 秒**一行日志都没有**；`promaid.log` 里 00:34:02 有一条
  `[窒息] … 累计吃到 4.0 点窒息伤害（in_wall，门槛 4.0）→ 播报`；00:35:31 一条
  `[离场] … (id 1265，reason=DISCARDED)`，紧接着 00:35:33 「新实体（收放魂符/重新召唤，id 1265→2876）」
  ——正好对上“她原地停着不动，只能把她收放一次”。
- 代码根因（`MaidMineBehavior` 挡路块分支）：挡路块可开启但超出 `reachSq()` 时，代码记下
  `unreachableBlocker` 后就 `approachOre(...)` + `traceExit("mine 挡路块走不到→改走接近链")` **直接 return**；
  而这句 return 之后的**整段卡死检测（`MINE_WEDGE_TICKS` / `RECENT_DISCARD` / `slideAnchor`）永远轮不到执行**。
  同时 `setChainDrive(maid, this.targetPos != null)` 一直是 true，TLM 的走路逻辑被压着不放，于是“不动、不报错、
  没有恢复链路”三件事同时成立（`WorkStatusReporter` 在 `isMining` 为真时也不给气泡）。

改成（`MaidWorkBehavior` 新增静默看门狗，挖矿/伐木 tick 头部调用）：

- `STALL_GIVEUP_TICKS = 160`（8 秒）：目标没变 ∧ 脚下格没动 ∧ `destroyProgress` 没推进，
  三者同时成立 160 拍 → 判定卡死；
- 处置：把该方块写进 `RECENT_DISCARD`（30 秒“暂时够不到”）、`onStallGiveUp`（子类清缓存的
  `ORE_CACHE`/`WOOD_CACHE` + `slideAnchor`）、清 `targetPos`/`destroyProgress`/`chainQueue`/`chainBlock`/各冷却与 `lastWalkTarget`、
  `navigation.stop()`、`eraseMemory(WALK_TARGET)`、`setChainDrive(false)`——**恢复链路**；再
  `notifyNoTarget("这块我够不到，先换个地方找找～")`（有气泡就不静默）并
  `LOGGER.info("work stall self-heal: …")`；
- 她一旦挪窝就清零计数；连续 3 次卡死 → `onStallStreak`（丢弃整片扫描缓存、重新全量扫）；
- 挖矿/伐木各自 `stallWatchdogSuppressed` 钩子（`MINE_SCANS`/`WOOD_SCANS` 未扫描时不判决）。

## 触及文件

- 两树：`com/maidsmart/task/MaidWorkBehavior.java`（`idleWander` 重写 + `wanderSpot`/`wanderAt` +
  `stallWatchdog` 及三个钩子）、`MaidMineBehavior.java` / `MaidWoodBehavior.java`（tick 头闸 + 三处钩子重载）、
  `com/maidsmart/config/PromaidConfigScreen.java`（网格两态 + 悬停“已移出” + 标题/说明改写）。
- 文档：`rounds/R979-idle-and-stall.md`、`rounds/RELEASES.tsv`、`DEV_GUIDE.md`、
  `release_body.md` / `release_body_neo.md` / `RELEASE_NOTES_v1.3.2.md` / `store/release_notes_1.3.2.md`、`机制详解.md`。

## 验证

- 两树 `javac EXITCODE=0`。
- 14 护栏全 `FAIL 0`。
- 包体：forge **17,774,987** 字节 `88432506…534dbf`；neo **17,822,832** 字节 `a6be513b…e51ee0`。
- 部署：`deploy_all.py` 4 个目标全 `match=True`（`elevated rc: 0`）。
- 回归：`test_server.py` / `test_maid_load.py` 两加载器各两件，四件全 `verdict: PASS`。

## 验收

- 挖空/砍空后 10 秒静默里她**一直在走**（走到一个点就换下一个，圈内且离原处 2.5 格以上），
  不再原地一步一停；主人这时带她走，她跟着走、不抢路。
- 「目标矿物」页里只有“已加入（绿框✓）”和“未加入（空白）”两种样子；点右上角小叉立刻回到空白，
  且她依然不会挖这块（自动识别也不算）。
- 她被够不到的目标钉住超过 8 秒时，会自己放弃、吰一声、去别处找；日志有 `work stall self-heal`。

## 边界

- 静默看门狗只在**挖矿/伐木 tick 内部**生效（不碰其他任务）；`destroyProgress` 有推进（正在挖）时永远不判卡死。
- `STALL_GIVEUP_TICKS = 160` 是固定值：`WALK_STILL_TICKS`(20) 的 8 倍，确保“导航在走但人不动”
  那条既有通道先说话。
- 移出（`!id`）在配置文件里仍是可见的一行；列表子页里那一行照旧显示「恢复」按钮，
  点它 = 解除移出。
- 网格不画第三态是**玩家点名**的口径；状态改挂悬停，不新增配置项。

## 台账

- `rounds/RELEASES.tsv` 两条 1.3.2 行 **第一百一十五次**同号重打，旧字节
  `3e85f305…24188c`（forge）/ `190cb2c9…6251de`（neo）（第一百一十四次 R978）作废。
- `DEV_GUIDE.md` 发布区间 → 八百五十九 ~ 九百七十九。
- 四份发布文案在 R978 条目之后追加本轮三条。
- `机制详解.md`：`MaidMineBehavior` / `MaidWoodBehavior` 两条各补「空闲接管修正 + 卡死自愈看门狗」。
