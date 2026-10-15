# 实测九百四十五 压缩盒差异化：盒内弹药 → 一次投/放多个（TNT / 末地水晶）

> 跑道：承接 `rounds/R944-panel-common-first.md`。来源：开发者对「压缩盒差异化」的定案
> （此前 R942/R943 记录里一直挂着「压缩盒暂时不管」，本轮解除并落地）。

## 排班

- 单功能轮：压缩盒的「独特优势区间」——把爆炸物存进盒子 → 女仆一次投/放多个（`BOX_BURST=5`）。
- 步骤：读轰炸子系统（BombItems / BombThrow / BombPlacement / MaidBombing / BombExplosion）→
  改 Forge → 镜像 Neo（逐锚点断言唯一；SRG↔Mojang 名按树替换）→ 两树 `javac EXITCODE=0` →
  全护栏 `FAIL 0` → 打 jar → 部署 4 实例 `match=True` → 台账/轮次记录 → 清临时脚本。
- 谁验收：开发者（本轮**已部署**、不跑回归；`## 验收` 记 `未验收`）。

## 需求 / 来源

开发者原话：

> 「压缩盒你的差异化我想我怎么做了。这个物品本身是为了鼓励玩家去放末影水晶、TNT、重生锚这些东西的。
> 那就这样子，如果把炸药什么东西存进这个里面，那么女仆可以一次性投/放出多个。原本只扔一个，
> 在这里可以一次性扔5个。本来是放置一次末影水晶，从这里面可以一次性放5个，
> 这样子就达到了压缩爆炸的效果。」

要点：①触发条件 = 爆炸物**存在压缩盒里**；②效果 = 一次投/放的件数从 1 提到最多 5；
③目的 = 「压缩爆炸」（多个爆炸物同一时刻起爆）。

## 定位与改法（本轮落地项）

- **压缩盒已能供弹（前置条件，本轮未改）**：`MaidCompressionBoxMixin` 把 `getMaidInv` /
  `getAvailableInv` / `getAvailableBackpackInv` 三条视图尾部接上盒子段，所以轰炸链路
  （`BombItems.takeOne*` 走 `getHandsInvWrapper` + `getAvailableBackpackInv`）本来就拿得到盒子里的东西。
  本轮只需在**件数**上做文章。
- **`BombItems` 新增（+ 两个入口）**：
  - `BOX_BURST = 5`（代码常量，非配置项——按开发者「越简约越好」的口径，不为此再加一个 toml 旋钮）。
  - `boxAmmoCount(maid, match)`：走 `getMaidInv()` 的盒子段
    （`CompressionBoxMaidInv.boxRange()`，视图封顶 64——对「够不够 5 个」足够）数真实存量，无盒子/未开延伸 → 0。
  - `boxBurst(maid, match)`：盒内 ≥2 件 → `min(BOX_BURST, 存量)`，否则 1。
  - `boxBurstTnt` / `boxBurstCrystal`：TNT / 末地水晶两个入口。
- **TNT 投掷（`BombThrow`）**：`throwCount(maid)` 改为「低血爆发数」与「盒子供弹数」**取大者**。
  投掷循环本来就是 `for (i < want)`（实测六百〇四起的低血连投），所以件数一变就自动多投——
  每发仍是「取一件 TNT + 用一次打火工具（打火石扣 1 耐久 / 烈焰弹整件消耗）+ 抛射 + 追踪」。
- **末地水晶放置（`BombPlacement.stepPayload`）**：新增一段——第一枚水晶按原逻辑生成后，
  若 `boxBurstCrystal(maid) > 1`，在**同一底座**上按「十字 + 斜角」偏移再生成 (n−1) 枚水晶，
  逐枚登记为一个独立 `Bomb`（各自起爆 → 真正的「同时 5 炸」）。
  - **关键防漏**：多出来的这几枚 `Bomb` 的 `placedPos/placedBlock` 传 **null**——
    底座只由第一枚那一炸 `settleBase` 回收一次（该处对 null 列表直接 return），
    否则同一块黑曜石会被 `scheduleReclaim` 还 N 次 = 白送底座。
  - 水晶件数在**取第一枚之前**先算好（`crystalBurst`）——若先取再算，第一枚从盒子出库会把计数减 1，多出的枚数算漏一枚。
  - 安全性：原版 `EndCrystal` **实体**无「底下必须是黑曜石」的 tick 校验（那条只约束用物品放置），
    我们的起爆是自定义爆炸（`detonate` 里 `DISCARDED` 掉实体，不走原版那一炸），所以悬空相邻生成不会自爆、不会重复炸。
- **面板可发现性**：压缩盒小类页加一条只读 `InfoRow「弹药加成（压缩爆炸）」`，写清触发条件与消耗口径。

## 触及文件

- 两树 × `com/maidsmart/combat/BombItems.java`（盒子供弹助手）
- 两树 × `com/maidsmart/combat/BombThrow.java`（`throwCount` 取大者）
- 两树 × `com/maidsmart/combat/BombPlacement.java`（水晶多放 + 只回收一次）
- 两树 × `com/maidsmart/config/PromaidConfigScreen.java`（压缩盒页只读说明）
- `rounds/RELEASES.tsv`（第八十四次同号重打）+ `rounds/R945-compression-box-burst.md`（本文件）
- **未动**：`MaidSmartConfig`（**未新增配置项** → `panel_coverage` / `check_preset` 基线不变）、
  盒子存储/过滤/界面、重生锚与床的放置链路。

## 验证

- **两树编译**：`compile_promaid.bat` / `compile_neo.bat` → `EXITCODE=0`
  （Neo 侧 `isEmpty/getCount/getX/getY/getZ/setShowBottom/addFreshEntity` 与 Forge 的
  `m_41619_/m_41613_/m_123341_/m_123342_/m_123343_/m_31056_/m_7967_` 按树替换，逐锚点命中数 = 1）。
- **全护栏**：11 个全 `FAIL 0`（`check_comment_style` PASS、`check_panel_coverage` FAIL 0/WARN 0、
  `check_preset` 173/177 FAIL 0、`check_i18n_keys` FAIL 0、`check_arch`/`check_comment_dup`/`check_dev_voice`/
  `check_reflection`/`check_catch_swallow` FAIL 0；`check_workflow` FAIL 0 + 3 条历史 WARN；
  `check_tree_mirror` 只读报告）。
- **两个 jar**：forge **17595878** 字节（sha256 `71CFD2FE…226CD7`）；
  neoforge **17632129** 字节（`72F3870D…F21544`）。
- **装机**：`deploy_all.py` → 4 个本机实例全部 **`match=True`**。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 行更新 sha256 并列第八十四次同号重打；
  九百四十四 的旧字节 `CCA55E74…037CB2`（forge）/ `29C2738B…B164BD`（neo）作废。
- **未跑实机回归**（已部署，等开发者实测）——尤其「5 枚水晶同帧起爆的伤害/性能」「5 发 TNT 的
  点火消耗体感」需要实测确认。

## 验收

- 状态：**未验收**（已部署：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
- **判据**：
  - 盒子里放 ≥2 个 TNT（且开「女仆背包延伸」），女仆轰炸一次投出 5 发 TNT（低血爆发取大者）。
  - 盒子里放 ≥2 枚末地水晶，女仆一次在同一底座周围放出 5 枚水晶、同一时刻起爆（爆炸比原来大）。
  - 盒子空 / 没开背包延伸 → 一切回到原来的「一次 1 个」。
  - 起爆后底座黑曜石**只回收一块**（不是 5 块回背包）。
  - 压缩盒小类页能看到「弹药加成（压缩爆炸）」那条只读说明。

## 边界（没动什么）

- **没动**：盒子本身的存取/过滤/界面/每格上限；轰炸的威力（`kind.power`）、追踪、粉色火焰、回收口径；
  TNT 的点火消耗（多投时**每发**仍各消耗一次打火工具，这是原版口径）。
- **没新增配置项 / i18n 键**：`BOX_BURST` 是代码常量（开发者点名「5」；也贴合本轮「越简约越好」的主题）。
  若要在面板里可调，加一个 `int` 配置项即可，属下一轮。
- **已知取舍（如实记录 + 待开发者裁）**：
  1. **重生锚 / 床仍是单放**（开发者动机里提到重生锚，但只举了 TNT 与末地水晶两个例子）。
     多放重生锚需要「每枚各占一个带支撑的落点 + 各充一份萤石」，是另一套落点几何；
     按当前口径先不动，等开发者确认要不要补。
  2. 水晶多放采用「同一底座 + 邻近偏移」而非「每枚各铺一块黑曜石底座」——省料、且符合
     「从盒子里一次掏出 5 枚水晶」的观感；如果你要的是「5 块底座 + 5 枚」，改 `stepBlock` 落点即可。
  3. 触发阈值取「盒内 ≥2 件」（1 件时没有「多」可言，仍按 1）。

## 台账

- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第八十四次同号重打。
- `DEBT.md`：`TD-04`（压缩盒 vs 精妙背包功能重复未裁决）**部分缓解**——本轮给了压缩盒一个精妙背包
  没有的独特优势区间（盒子供弹的多投放），可作为该条债的处置依据；本轮不新增债条目。
