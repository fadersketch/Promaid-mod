# R1060 风弹猛击频率 + 叠放水晶伤害 + 轰炸等重锤命中

## 排班（开工前先填，硬门槛 —— `WORKFLOW.md` §1）

- **跑道**：A 修 bug —— 两树镜像（1.20.1 Forge + 1.21.1 NeoForge）。
- **开工前读过**：`MaidMaceSmashBehavior`、`MaidCombatTacticsBehavior.meleeTick`、
  `MaidBombing`、`BombPlacement`、`BombExplosion`、`BombTntTick`、
  1.20.1 `LivingEntity.hurt` 字节码（`f_19802_` = 受击无敌帧）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 重锤档走位不再把她推出 2 格 | 贴脸绕圈 1.6 格，攻击后不后撤、贴脸不拉开 | ★ |
| 2 | 叠放/并排连炸每枚都结算 | 同 tick 五枚水晶 = 五份伤害，不再只吃第一枚 | ★ |
| 3 | 轰炸等重锤猛击真的命中 | 起跳清标记、命中写标记；未命中不放置、不投 TNT | ★ |
| 4 | 炸弹没炸完不开始下一跳 | 相位 / 待起爆 / 追踪 TNT 存在时猛击等待 | ★ |
| 5 | 两树编译与护栏 | 两树 `EXITCODE=0`；14 护栏 `FAIL 0` | ★ |
| 6 | 双端回归与装机 | 四套测试 PASS；4 个目标 `match=True` | ★ |
| 7 | 上一局日志复盘：五枚水晶为何只像炸了一枚 | 找到“施放者死亡 → 余下炸弹作废”与自伤路径 | ★ |
| 8 | 起爆伤害探针 | 下一局每炸一行，写出威力与目标掉血 | ★ |
| 9 | 交开发者验收 | 风弹频率上升；五枚水晶伤害显著；炸弹在命中后出现 | ☐ |

## 需求 / 来源

- 开发者反馈 1：“你理解错了，我的意思是他使用风弹的频率太低了。基本上一直是在用平A。”
- 开发者反馈 2：“末地水晶我记得伤害很高的来着，为什么女仆用的五个叠放伤害却很低，
  甚至有时打不到呢？”
- 开发者反馈 3：“重锤+风弹时使用tnt/水晶等的链路先停，防止重锤还没打到怪，怪先被炸飞了，
  砸了个空。” 随后补充：“重锤+风弹时，tnt等链路最好留在重锤真的击中人以后。”

## 评估

- **风弹频率**：R1059 把猛击触发距离收到 2 格后，单兵战术仍在按原来的近战节奏走位：
  攻击完成后撤到 3.5 格、贴进 2 格主动退到 3 格、平时按 2.2 格绕圈。三套动作全部落在
  2 格猛击距离之外，于是 `MaidMaceSmashBehavior` 几乎永远拿不到起跳条件，只剩重锤平A。
- **叠放水晶**：压缩盒一次放 5 枚，5 枚在同一 tick 起爆。原版 `LivingEntity.hurt` 在
  `invulnerableTime > 10` 时直接吞掉“伤害 ≤ 上一记”的命中；5 炸同威力同位置，第二枚起
  全是 0 伤害，玩家看到的就是“五枚只吃一枚”。如果猛击刚打中，目标还在无敌帧里，
  连第一枚也会被吞——这就是“有时完全打不到”。
- **上一局日志复盘**（`1.21.1-NeoForge_21.1.250/logs/latest.log`）：
  - `13:01:18.045` 压缩盒供弹，末地水晶一次放 5 枚（同一底座，一起起爆）；
    `13:01:18.048` 就位，10 tick 后起爆（威力 6.0）。
  - `13:01:18.552` 女仆在爆心 `(-113.8,-59.5,-388.7)` 吃到 `33.2` 伤害；随后
    `13:01:18.978` 出现“复活后补包”。
  - `13:01:19.503` 又投掷 `TNT ×5`（引信 40 tick），`13:01:19.849` 再吃 `47.6`，
    `13:01:20.821` 离场（`KILLED`）。
  - 旧版 `MaidBombing` 的待起爆循环把 `!maid.isAlive()` 和空引用一起判：她死了，
    **剩下四枚水晶与五枚 TNT 全被 `settleBase + dropEntity` 丢掉**，目标只吃到第一枚。
  - 同一局里她自己的伤害日志没有普通 `explosion` 条目，只有 `generic`；而无主女仆
    会被 `MaidScope.owned` 放行，旧版 `FriendlyFireGuard` 的 `isFriendly(maid, maid)`
    根本保护不到她——第一炸把她带走，是“五枚只像一枚”的直接原因。
- **轰炸时机**：`BombTntTick.tickCombatTnt` 只要目标合法就每 2 tick 试起手，不等重锤是否
  命中。起跳途中水晶/TNT 先炸，目标被掀飞，紧跟着的猛击砸空。

## 改法

### 1. 重锤 + 风弹档主动贴身（两树 `MaidCombatTacticsBehavior`）

- 新增 `MACE_ORBIT_RADIUS = 1.6`：主手重锤且风弹条件满足时，贴脸绕圈半径从配置的 2.2
  压到 1.6，给路径误差留 0.4 格余量，保证落在 2 格猛击距离内。
- 这一档**跳过攻击完成后的 14 tick 后撤**，也**跳过“贴进 2 格退到 3 格”的拉开**；
  否则每次平A都会把她重新推回猛击距离外。
- 重锤仍然禁用通用跳劈（起跳只由 `MaidMaceSmashBehavior` 负责）；没有风弹时
  `maceLeap` 为 false，走位恢复原样，退化为重锤平A。

### 2. 叠放 / 并排连炸每枚独立结算（两树 `BombExplosion` + `MaidBombing` + `BombPlacement`）

- `Bomb` 新增 `burst` 标记：压缩盒一次放多枚（同格叠放末地水晶 / 并排重生锚）整批置 true。
- 每次起爆前，如果这一发是末地水晶或 `burst`，先把爆心 `power × 2` 范围内活体的
  `invulnerableTime` 清零（跳过女仆本人与友军），再由原版 `explode` 结算。
  每一炸都会重新写入无敌帧，所以只作用于这一次连续爆发。
- 水晶**恒走**这条：即使目标刚被平A打中、还在无敌帧里，水晶也按自己的威力出伤。
- 威力、引信、爆炸模式、友军豁免、粒子与粉色火焰全部不变。

### 3. 轰炸链路等“这一记猛击真的命中”（两树）

- `MaidMaceSmashBehavior` 新增 `SMASH_HIT`：
  - 起跳那一 tick 清掉（新一跳开始，上一跳的命中不再作数）；
  - 落地 `smashHit` 真正 `hurt` 成功时写上。
- 新增 `bombingAllowed(maid)`：非重锤档恒 true；重锤+风弹档必须 `SMASH_HIT` 为真。
  空袭任务、骑乘、鞘翅、睡觉时猛击本来就不起跳，这些状态直接放行，不锁死轰炸。
- 闸门接入四处：`MaidBombing.tryStartMelee`、`MaidBombing.tick(Phase)`、
  `BombTntTick.tickRangedTnt`、`BombTntTick.tickCombatTnt` / `flushTnt`。
  起跳途中已经在飞的放置相位会当场回滚（底座原物还她）。
- 猛击命中增加无敌帧兜底：如果目标还在平A留下的无敌帧里，清一次再结算，
  保证“看起来砸中了”就是真命中，同时给轰炸链路放行。
- `MaidBombing.hasLiveBomb(maid)`：她名下还有放置相位 / 待起爆 / 追踪中的 TNT 时，
  `MaidMaceSmashBehavior` 暂不起跳——先让炸弹炸完，避免下一记猛击又被掀飞。
  没有炸弹时这条恒 false，不影响起跳频率。

### 4. 放炸弹的她死了，已放好的炸弹仍然要炸（两树 `MaidBombing`）

- 待起爆循环只判 `b.level == null || b.maid == null`，**不再判 `maid.isAlive()`**。
  女仆死亡只意味着没人指挥下一发，不等于这一发该作废；底座与炸弹实体照常结算。
- 这是日志复盘里最直接的一条：旧版第一炸把她带走后，余下四枚水晶与五枚 TNT
  全部走“半路作废”分支，目标当然只看到一份伤害。

### 5. 起爆伤害探针（两树 `BombExplosion`）

- 每次起爆前记录爆心 `power × 2` 内非友方活体的血量，起爆后逐个体比对，日志写一行：
  `末地水晶 起爆 @x,y,z 威力 6.0：生物名 40.0→0.0（-40.0）；…`
- 若范围内没有任何目标掉血，写 `半径内无掉血目标`。下一局搜「起爆」即可判断：
  是“没炸到”，还是“炸到了但每枚被吞”，不用再靠血条目测。
- 探针只读血量、不加伤害、不改爆炸参数；异常照旧走 `Diag.swallow`。

### 6. 自己放的这一炸不伤她本人（两树 `FriendlyFireGuard`）

- 起爆期间已有 `selfImmuneBlast` 窗口；现在在伤害事件最上游直接判
  `BombExplosion.isSelfImmuneBlastFor(victim)` 并取消，不再依赖 `isFriendly(maid, maid)`。
- 原因：无主女仆在 `MaidScope.owned` 处被直接放行，旧版三层免伤对她全部失效。
- 只覆盖“本模组炸弹正在起爆”这一瞬间，其它来源的伤害一律不动。

## 触及文件

- `promaid_src/com/maidsmart/combat/MaidCombatTacticsBehavior.java`
- `promaid_src/com/maidsmart/combat/MaidMaceSmashBehavior.java`
- `promaid_src/com/maidsmart/combat/MaidBombing.java`
- `promaid_src/com/maidsmart/combat/BombPlacement.java`
- `promaid_src/com/maidsmart/combat/BombExplosion.java`
- `promaid_src/com/maidsmart/combat/BombTntTick.java`
- `promaid_src/com/maidsmart/combat/FriendlyFireGuard.java`
- `promaid_src_neo/...` 同名文件的镜像
- 无新配置项、无新 i18n、无新 mixin、无新反射探针、无新 Java 类。

## 验证

- Forge `compile_promaid`：`EXITCODE=0`；Neo `compile_neo`：`EXITCODE=0`。
- 14 护栏全部 `FAIL 0`（含 i18n / panel coverage / preset / workflow）。
- `test_server 1201`：PASS；`test_server neoforge1211`：PASS。
- `test_maid_load 1201`：PASS；`test_maid_load neoforge1211`：PASS。
- `deploy_all.py`：4 个实例全部 `match=True`。
- 产物：
  - Forge `patched/promaid-1.3.3-forge-1.20.1.jar` 18,093,790 字节，
    SHA256 `26DB8F6CDB67EDB00303E72DA67B5D565AD5901C5F53CA2AF83EC04B1161766B`
  - Neo `patched/promaid-1.3.3-neoforge-1.21.1.jar` 18,181,867 字节，
    SHA256 `63133EA65533EAAD0ABCCE1502E19F58130CF282E5D9C72646C8FC1F1F8304AC`

## 验收

- 状态：`未验收`。验收人 = 开发者；验法：
  1. 重锤 + 风弹打一只地面怪：她应主动贴到 1.6 格左右，日志搜「重锤」应频繁出现
     `借风弹起跳猛击`，而不是一直 `超出贴身距离（>2格，改用重锤平A）`。
  2. 压缩盒放 5 枚末地水晶：目标血条应一次掉一大截（约等于五份水晶爆炸），
     不应只掉一份；刚用平A打过目标后紧接着炸也应正常出伤。
     日志搜「起爆」应看到 5 行，每行都有目标掉血，或明确写 `半径内无掉血目标`。
  3. 重锤 + 风弹战斗：起跳过程中日志里不应出现 `放下 末地水晶` / `投掷 TNT`；
     只有落地砸中之后才会看到放置/投掷。炸弹没炸完之前她不会开始下一跳。
  4. 她若在引信期间死亡，已放好的炸弹仍应照常起爆，而不是只剩第一枚。
- 非重锤武器 / 空袭任务 / 骑乘状态：轰炸链路行为与旧版一致。

## 边界（没动什么）

- 不改重锤猛击的伤害公式、风弹消耗、冷却（60 tick）、起跳初速、落地缓冲。
- 不改水晶 / 重生锚 / 床 / TNT 的爆炸威力、引信、爆炸模式、友军豁免。
- 不改压缩盒的 5 件上限、盒子判定、放置落点三级逻辑。
- 不改非重锤档的走位、后撤、绕圈半径与轰炸节奏。

## 台账

- 本轮不清债；未新增债。反射计数保持基线（`getMethod` Forge 205 / Neo 206）。
