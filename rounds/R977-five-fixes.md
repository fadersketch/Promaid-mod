# R977 · 关系按钮能用 / 投喂重要度分档 / 有镐判定看背包 / 无目标静默闲逛 / 检索半径 48

## 排班

1. 调试页「启用关系」永远点不动——查清它什么时候可用，并把入口做出来。
2. 投喂蛋糕与 TLM「女仆感兴趣的食物」时抬高记忆重要度；顺带算清楚「同一件事发生太频繁」会不会撑爆面板。
3. 「没镐」判定改看背包（含副手），修掉误报。
4. 挖矿/伐木说出「这片区域没有目标了」之后，先静默闲逛 10 秒（可配置），到点自动恢复并重新检索。
5. 挖矿/伐木默认检索半径 24 → 48。
6. 编译 / 护栏 / 打包 / 部署 / 回归 / 文档台账。

## 需求 / 来源

- 「启用关系那个按钮我永远都打不开，我不知道在什么时候才可以启用这个开关。」
- 「给女仆喂食蛋糕 / 原版 tlm 中填的女仆感兴趣的食物的时候，应该拥有更高的记忆重要度。
  顺便问一下。如果某些事情发生得过于频繁，会怎么处理？比如投喂的次数很多。如果每一条都列在面板里面，
  那么面板会变得臃肿不堪。」
- 「女仆判定是否有镐的时候，应该是检索背包，而不是检索主副手。现在的判定有一些误判，导致会误报自己没有镐。」
- 「如果女仆在伐木或者挖矿的时候触发了『周围没有矿物 / 树木』的语音，则会恢复空闲模式的行走逻辑 10 秒钟
  （在此期间所有的工作链路暂时不生效，静默 10 秒，此时长作为可自助配置项目），然后再恢复回来重新执行一次检索。」
- 「默认的检索半径改为 48。」

## 改法

### 一、调试页「启用关系」三态（`BlueprintBookScreen`，两树）

根因：这颗按钮的可用条件是**选中的那一条是关系行**（`isRel`），但旧版的标签只看 `relOn = isRel && !inactive`——
没选中关系时算出来是 false，于是它照样写着「启用关系」，却是一颗永远灰着的按钮。玩家既看不出它要求先选中什么，
也不知道本页到底有没有关系（关系行来自 `store.relations()`，只有关系图里真有边时才存在）。

改成三态：

- 选中的是关系行 → 「停用关系」/「启用关系」（原逻辑不变）；
- 本页有关系但没选中 → 标签变「**跳到关系 ▸**」，点一下直接选中第一条关系并把它翻到可见页
  （新增 `selectFirstRelation()` + `debugMaxRows` 记住每页行数），并在第三行给回执；
- 一条关系都没有 → 标签「关系操作」，保持灰色，把原因写进第二行提示。

提示行（`debugContextHint()`）补两件事：未选中时显示「共 N 条 · 关系 M 条」；
选中记忆行时说明「关系操作」只对带关系标记的行生效，并给出 M（M>0 提示可用「跳到关系」，M=0 提示
多和她相处、提升好感等级 / 触发关系事件后才会出现）。

### 二、投喂重要度分档（`AiMemoryManager.onFeed`，两树）

- 她感兴趣的食物（TLM 标签 `touhou_little_maid:maid_tamed_item`——原版蛋糕与 kawaiidishes 等各模组蛋糕族，
  外加本模组自己的 `MaidCakeEatHandler.isCake`）→ salience **10**；
- `FoodProperties` 营养 ≥ 8 → **9**；
- 其余普通食物 → 原值 **7**。

分档只改重要度，**内容一字不变**（仍是「主人喂了我<物品名>」），所以去重口径不受影响。

**频繁发生的处理（回答「面板会不会臃肿」）**：不会。`AiMemoryStore.addParagraph` 以
`hashOf(content)` 为键做精确去重——同一种食物反复喂，写进去的是同一段内容、同一个 hash，命中
`mergeParagraph` 后只把重要度取 max、想起次数取 max、时间窗取并集（`AiMemoryStore.java:227 / 312`），
**永远不会多出一行**。真正会长行数的是「新内容」（换一种食物、换一个话题），而这类行本身是有信息量的。

### 三、有镐判定看背包（`WorkStatusReporter` + `MaidToolAutoEquip`，两树）

`WorkStatusReporter.idleReason` 的挖矿分支原来只判主手：`hand` 不是镐就报「我没有镐」——镐放在背包里、
或刚和骨粉换手的瞬间都会误报（挖矿入口闸 `MaidMineBehavior.m_6114_` 早就用的是 `hasAnyPickaxe`，两边口径不一致）。

现在改成：主手不是镐时，再问 `MaidToolAutoEquip.hasAnyPickaxe(maid)`（主手 → 背包 → 精妙背包），
有镐就不播报。顺带把 `hasAnyPickaxe` 与 `canHarvestWithHandOrBackpack` 补上**副手**探针——
「手上」本来就该含副手。

### 四、无目标静默闲逛（`MaidWorkBehavior` + 两个工作 tick，两树）

- 基类新增 `IDLE_PAUSE_UNTIL`（实体 ID → 恢复时刻）+ `startIdlePause()` / `inIdlePause()`；
- 挖矿 / 伐木在「全量扫描确认本框没有目标」那一支里，气泡 + 闲逛之后追加 `startIdlePause()`；
- 两个 tick 在 `antiWaterStuck` 之后插入闸门：处于窗口内就清目标、`setChainDrive(false)`、
  走空闲闲逛（`idleWander`）后直接 return——**整条工作链路暂停**（不扫描、不换镐、不播报），
  10 秒到点自动恢复，下一 tick 重新检索一次；
- `forget(maidEntityId)` 一并清这张表，防实体 ID 复用串台。

时长取新配置 `mine.noTargetPause`（默认 10 秒，范围 0~120，0 = 关闭本机制、回到旧行为）。
面板落点：「挖矿」页「检索半径」下面一行「无目标静默（秒）」。

### 五、默认检索半径 24 → 48（`MaidSmartConfig`，两树）

`mine.searchRadius` 与 `wood.searchRadius` 的默认值由 24 改 48（范围仍 8~64）。
注意：**只影响新装的配置**——已经生成过 `config/promaid-common.toml` 的存档里写着 24，会继续按 24 走，
想用新默认值就在面板里把它改成 48。

## 触及文件

- `promaid_src` / `promaid_src_neo`：`com/maidsmart/build/BlueprintBookScreen.java`、
  `com/maidsmart/memory/AiMemoryManager.java`、`com/maidsmart/dialogue/WorkStatusReporter.java`、
  `com/maidsmart/task/MaidToolAutoEquip.java`、`com/maidsmart/task/MaidWorkBehavior.java`、
  `com/maidsmart/task/MaidMineBehavior.java`、`com/maidsmart/task/MaidWoodBehavior.java`、
  `com/maidsmart/config/MaidSmartConfig.java`、`com/maidsmart/config/PromaidConfigScreen.java`、
  `assets/maid_smart/lang/{zh_cn,en_us}.json`。
- 生成物：`assets/promaid/panel_coverage.txt`（两树）、`ARCHITECTURE-SWITCHES.md`。
- 文档：`rounds/R977-five-fixes.md`、`rounds/RELEASES.tsv`、`DEV_GUIDE.md`、
  `release_body.md` / `release_body_neo.md` / `RELEASE_NOTES_v1.3.2.md` / `store/release_notes_1.3.2.md`、
  `机制详解.md`。

## 验证

- 两树 `javac EXITCODE=0`（首轮一处 Python 拼接把配置注释断在字符串中间，改成单行后过）。
- 14 护栏全 `FAIL 0`（`check_panel_coverage` 先报陈旧 → 重跑 `gen_panel_coverage.py`；
  `check_workflow` 报开关清单过期 → 重跑 `gen_switch_inventory.py --write`）。
- 包体：forge **17,772,985** 字节 `b584002f…df3d62`；neo **17,820,827** 字节 `f7793059…a40e19`。
- 部署：`deploy_all.py` 4 个目标全 `match=True`（`elevated rc: 0`）。
- 回归：`test_server.py` / `test_maid_load.py` 两加载器各两件，四件全 `verdict: PASS`。

## 验收

1. 调试页不选中任何行时，提示行写着「共 N 条 · 关系 M 条」；M>0 时动作行第 3 颗按钮是「跳到关系 ▸」，
   点一下自动选中第一条关系并翻到那一页，第三行出现回执。
2. 选中一条关系：按钮变「停用关系」，点后该行标【已停用】、按钮变「启用关系」，再点恢复。
3. 一条关系都没有时：按钮显示「关系操作」并保持灰，第二行提示关系条目要等与她的关系事件出现。
4. 喂蛋糕 / 模组蛋糕：记忆里那条「主人喂了我 cake」重要度 10；喂普通食物仍是 7；同一种反复喂只有一行。
5. 镐放背包里、手上空着时，女仆不再喊「我没有镐，没法挖矿」。
6. 挖空 / 砍空一片：气泡之后她安静逛约 10 秒（期间不再刷「附近没有…」），然后自动重新检索；
   面板「挖矿 → 无目标静默（秒）」填 0 后回到旧行为。
7. 新装存档的面板里，挖矿 / 伐木检索半径显示 48。

## 边界

- 静默窗口是**内存表**（不落盘）：服务器重启即清空，重启后按正常链路重新检索一次。
- 静默期间她被完全放开（清目标 + 还走位权），所以这 10 秒内**不会**继续挖/砍正在进行的方块——这是
  刻意的：这一段的前提本来就是「本框确认没有目标」。
- 面板改「无目标静默（秒）」即时生效（两个子类每 tick 读配置），但**已开始的窗口不重算**（下次触发才用新值）。
- 检索半径改默认值只对新配置生效；已有 toml 保留旧值 24。
- TLM 的 `maid_tamed_item` 标签是本体数据，模组蛋糕（kawaiidishes 等）只要装了就会一起进分档；
  没装时该标签里只剩 minecraft:cake，行为与 `MaidCakeEatHandler.isCake` 一致。

## 台账

- `rounds/RELEASES.tsv` 两条 1.3.2 行 **第一百一十三次**同号重打，旧字节
  `e5cfce35…cbe587`（forge）/ `df0c0812…48b60a21`（neo）（第一百一十二次 R976）作废。
- `DEV_GUIDE.md` 发布区间 → 八百五十九 ~ 九百七十七。
- 四份发布文案在 R976 条目之后追加本轮条目。
- `机制详解.md`：`BlueprintBookScreen` / `WorkStatusReporter` / `AiMemoryManager` /
  `MaidMineBehavior` / `MaidWoodBehavior` / `MaidToolAutoEquip` 六条补本轮说明。
