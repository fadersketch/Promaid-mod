# R982 · 面板口径收尾 · 无目标切空闲（照自主战斗）· 补给不再补坏工具 · 岩浆语音

## 排班

1. 开发者四条反馈：①**岩浆挡路那句播报要补语音**；②**矿物 / 木材名单页的红字要改口径**——
   「现在已经没有优先级和判定值这个说法了」；③把「空闲模式的运作逻辑」搬进挖矿 / 伐木，
   这次**退而求其次照自主战斗切换来写**：无目标语音触发后 2 秒切空闲、10 秒再切回来，
   并问「伐木中切空闲 → 被怪打触发自主战斗 → 打完回到哪个模式」；④**补给别再补因耐久爆掉的工具**。
2. 先摸清三处既有实现：自主战斗切换（`AutoCombatSwitch` / `AutoCombatPools` / `AutoCombatTargeting`）、
   补给的实体（`MaidToolAutoEquip` 换装漏斗 + `MaidExtraContainer.pull`）、面板红字的出处（两树 `PromaidConfigScreen`）。
3. 两树镜像改代码 / 文案 → 编译 → 语音 → 打包 → 护栏 / 部署 / 回归 → 文档台账。

## 需求 / 来源

- ①「汇报去某条路上有岩浆的这句话，需要补上语音。」
- ②「把挖矿和障碍名单里面红字的文本改一下，因为现在已经没有优先级和判定值这个说法了。」
- ③「我发现你还是做不到，把空闲模式的运作逻辑搬到那两个模式里面。那么我就退而求其次吧。
  套用一下之前自主战斗切换的代码。如果女仆触发了这个区域内没有矿物/树木这样的语音后 2 秒，
  那它就像自主战斗切换一样，切换为空闲模式，10 秒钟然后再切换回来。整体的代码就跟自主战斗切换是一样的。」
- ③附带问题：「假设女仆处于伐木状态，周边的树都被砍完了。切换到了空闲，然后这个时候又被怪打了一下，
  触发了自主战斗。那么他把怪打完之后，他最终会回到什么模式下呢？」
- ④「补物品方面有一个 bug…女仆把快要用坏的道具用爆了。结果又补包又给他补了一个道具，
  导致一个坏掉的道具被无限补。…只要在补包的里面加一个不补因为耐久损坏而爆掉的工具。」

## 改法

### ① 岩浆挡路播报补语音

- 气泡原文（`MaidWorkBehavior.reportLavaPath`）：`去目标（坐标 x, y, z）的路上有岩浆，我先绕一下～`。
- 两树 `assets/promaid/voice/manifest.json` 加 `{"text":"的路上有岩浆","file":"lava_path_ahead.ogg","mode":"contains"}`
  （`contains` 避开坐标部分）；`gen_voice_pack.py` 归 **work 档**（REF_A、speed 1.03）+ JP 台词
  「目標までの道にマグマがあるの、ちょっと回り道するね～」。
- 合成 → 归一化（−9.00 dB → +7.50 dB，目标 −1.5 dB / libvorbis q4）→ `lava_path_ahead.ogg` 44,357 B → 装两树。
- 条目 178 → **179**（JP 180 行，`missing JP: none`）。

### ② 名单页红字改口径

| 位置 | 旧 | 新 |
|---|---|---|
| 数值输入框提示 | 输入优先级数值（留空=默认价值） | **输入数值（留空 = 用默认值加入）** |
| 悬停第三行 | §a优先级：300 | **§a已在名单（数值 300）** |
| 网格下方「移出」行 | …移出后**自动识别**也不会再把它认回来；加回来 = 点图标后**填价值**点「添加」 | **…移出后就不在名单里了，女仆不挖它；加回来 = 点图标后再点「添加」** |
| 锁定红字 | 已锁定（X），**请为其赋予一个值**（直接点添加/回车 = 300） | **已锁定（X），点「添加」或回车把它加进名单（默认 300）** |
| 黄字聊天 | …再**输入数值点添加** | **…再点「添加」把它加进名单** |
| 两处旧注释 | 「自动识别照旧不会把它认回来」「悬停三行…优先级」 | 改成「名单是唯一依据」「名单数值」 |

- 4 份 `en_us.json` 词条同步重挂：改键 `输入数值（留空 = 用默认值加入）` / `§a已在名单（数值 ` /
  `），点「添加」或回车把它加进名单（默认 `；把两条旧「（价值 …」碎片换成**整句标题**、两条旧图例尾句
  换成**整句图例**（并删掉两条悬空碎片）；新增「移出」整句词条；`✔ 一键全部加入` 的键补上漏掉的 `§b`
  （旧键少了颜色码 → 英文客户端永远命不中）。
- 顺手修 neo 独有的一处写坏：悬停「未添加」写成了 `"\\u00a77未添加"`（渲染出字面量 `\u00a77未添加`）。

### ③ 无目标 → 切空闲任务（照自主战斗切换那套）

- 新增在基类 `MaidWorkBehavior`（两树同构）：
  - 状态：`WORK_IDLE_MEM`（实体 ID → `[切换到期 tick, 还原 tick, 空闲时长]`）、`WORK_IDLE_PREV`（切走前的任务 UID）；
  - `armWorkIdleSwitch(maid, gameTime, sec)`：记下当前任务 UID，`gameTime + 40`（2 秒）到期；
    **排班开启不参与**（任务归排班管，与自主战斗同一口径）。它由 `startIdlePause(...)` 末尾调用
    ——挖矿 / 伐木两条「框空」分支本来就都走这里，一处装配两处生效；
  - `tickWorkIdleSwitch(maid, gameTime)`：到点 → `switchMaidTask(idle)`；再满 `sec * 20`（默认 200 拍 = 10 秒）
    → 切回 `WORK_IDLE_PREV` 里存的 UID。**真在战斗 / 排班接管时顺延 20 拍让位**，等它们收场再还原；
  - `switchMaidTask(...)`：照自主战斗那条路——`TaskManager.findTask(uid)` +
    `ScheduleSwitchGuard.runInternal(maid, uid, () -> maid.setTask(task))`，并回读确认。
- **驱动放在常驻 core 行为 `MaidToolAutoEquipBehavior` 里**（每 tick 一次）：她切到空闲任务后，
  挖矿 / 伐木行为本身已经不再 tick，只有「任何 activity 都跑」的地方才能把任务切回来。
- 静默时长仍取 `mine.noTargetPause`（0 = 整条关掉，回到 R977 之前的旧行为）。

### ④ 补给不再补「因耐久损毁而爆掉」的工具

- 记账：`MaidToolAutoEquip.recordDurabilityBreak(maid, stack)`——调用点在三处碎裂点（挖矿主手、
  挖矿连锁、伐木主手）**扣耐久之前**；方法自己判断「这一下会不会把它用爆」（`damage + 1 >= max`），
  爆了才按**物品注册名**记一条 `(她, 类型) → 时刻`，窗口 `600` 拍（30 秒），过期自动清。
- 拦补给：`brokeByDurability(maid, stack)` 进两条路——
  1. 换装候选循环（`equip` / `ensureForTarget` / `ensureAnyAxe` / `ensureAxeForTarget` /
     `ensureShieldForCombat` / `ensureHoeForFarm`）连同原有的「快坏不切」一起跳过；
  2. `MaidExtraContainer.pull(...)` 的**过滤器**也加上「不快坏 且 没刚爆过」，
     额外容器（精妙背包等）再也搬不进一件快坏 / 刚爆过的同类。
- 手上那把**照旧继续用**（她能干活就干，不新增"撂挑子"行为）——改的只是「不再源源不断递新的」。

**③ 附带问题的答案（从代码推）**：自主战斗参战瞬间用 `resolvePrevTaskUid(maid)` 快照
「她此刻的任务 UID」写进 `PREV_TASK_TAG`；她此刻正被我们切在 `touhou_little_maid:idle`（非排班），
所以战斗结束的还原目标是 **idle**——只按战斗那套走，她打完会**停在空闲**。
但本轮这套「切空闲」自己还记着 `WORK_IDLE_PREV`：战斗中我们顺延让位，战斗一结束（`isReallyCombatActive`
为假）就把它切回**伐木**。所以最终答案是：**回到伐木**。

## 触及文件

- 两树：`task/MaidWorkBehavior.java`（`WORK_IDLE_*` / 装配 / 驱动 / `switchMaidTask`）、
  `task/MaidToolAutoEquipBehavior.java`（每 tick 驱动一行）、`task/MaidToolAutoEquip.java`
  （记账 + 拦截 + 6 处候选与 6 处 pull 过滤器）、`task/MaidMineBehavior.java`（三处碎裂点之二）、
  `task/MaidWoodBehavior.java`（碎裂点之三）、`config/PromaidConfigScreen.java`（红字 + 注释）、
  `assets/promaid/i18n/en_us.json`、`assets/promaid/voice/manifest.json`、
  `assets/promaid/voice/lava_path_ahead.ogg`（新增）。
- 工具：`tools/voice/gen_voice_pack.py`。
- 文档：`rounds/R982-*.md`、`rounds/RELEASES.tsv`、`DEV_GUIDE.md`、`机制详解.md`、
  `release_body.md` / `release_body_neo.md` / `RELEASE_NOTES_v1.3.2.md` / `store/release_notes_1.3.2.md`。

## 验证

- 两树 `javac EXITCODE=0`（改完先编译；forge 一次 SRG 名修正是唯一的返工）。
- 14 护栏全 `FAIL 0`（`check_tree_mirror` 报告制）。
- 包体：forge **17,864,806** 字节 `a6f58ec6…6d3460`；neo **17,912,578** 字节 `efc2eeec…fd6dfb`。
- 部署：`deploy_all.py` 4 个目标全 `match=True`（`elevated rc: 0`）。
- 回归：`test_server.py` / `test_maid_load.py` 两加载器各两件，四件全 `verdict: PASS`。
- 语音：`gen_voice_pack.py check` = `entries: 179` / `JP lines: 180` / `missing JP: none`；两树 ogg 一致。

## 验收

- 她绕开岩浆去目标时，那句提醒会被念出来。
- 面板上再也看不到「优先级 / 请为其赋予一个值 / 自动识别」；英文客户端对应几行也有英文。
- 挖空 / 砍空说完提醒 → **2 秒**后她切成待机、安静 10 秒 → 自己切回挖矿 / 伐木（面板把「无目标静默」填 0 则整条不生效）。
- 快坏工具用爆后，同一种在 30 秒内**不会再被补给她 / 换给她**，「无限补」停止。

## 边界

- **排班模式下不参与切换**：任务归排班管（与自主战斗「排班女仆不参战」同一口径），她在那 10 秒里仍是
  R977 那套「交回走位权 + 停止扫描」。
- 空闲时长 = `mine.noTargetPause`（默认 10 秒、0 = 关）；那个「气泡后 2 秒」是固定常量（40 拍）。
- 战斗 / 排班期间还原**顺延让位**，不抢它们的任务；战斗结束后最多一拍内把工作切回来。
- 「耐久损毁」记账按**物品注册名 + 30 秒窗口**：窗口内同种工具不会被补给 / 换装（玩家新塞一把同款也要等窗口过）；
  窗口后自然解除，不写死任何黑名单。
- 只拦「补给 / 换装」，不禁止她用手上那把快坏的继续干活（否则她会在现场撂挑子)。
- 岩浆那条语音是 `contains` 匹配（避开坐标），当前只有这一处气泡含「的路上有岩浆」。

## 台账

- `rounds/RELEASES.tsv` 两条 1.3.2 行 **第一百一十八次** 同号重打；旧字节
  `144a210a…999c3a`（forge）/ `952ef734…9910e0`（neo）（第一百一十七次 R981）作废。
- `DEV_GUIDE.md` 发布区间 → 八百五十九 ~ 九百八十二。
- 四份发布文案各追加本轮四条（切任务、耐久补给、面板口径、岩浆语音）。
- `机制详解.md`：挖矿 / 伐木两条任务 bullet 各补切换说明，`MaidToolAutoEquip` bullet 补补给拦截，§十九 语音补 179。
