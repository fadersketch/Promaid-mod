# R1028 1.21.1 长矛空袭点火窗口 / 方块描边寿命 / 长矛挥臂闸（1.3.3 同号重打）

> **后续：R1029 已回退本轮的进场曲线前移（`AIM_LEVEL_DIST`）；描边寿命与挥臂闸保留。**

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修复 / 体验补齐（1.21.1 专属；1.20.1 等开发者验收批准后再镜像）。
- **开工前读过**：`rounds/R1027-neo-spear-altitude-ground-pillar-retreat.md`、
  `SpearAirRaidBehavior.steerAttack`、`PlacedMarkNetworking`、`MaidSpearSwingMixin`、
  `SpearCoreCompat`、`SpearAttackBehavior.suppressesMelee`；spearcore 的
  `SpearItem.onUseTick` 反编译（二参 `swing` 只在命中循环里调用）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 打不到怪定位 | 曲线在离目标 0 格才收到矛线高度，而点火带（±0.4 格、≥2 格距离）在 1~2 格处才成立，两窗口错开 | ☑ |
| 1 | 点火窗口 | 曲线改为提前 `AIM_LEVEL_DIST = 6` 格收到矛线高度，留出平飞点火段 | ☑ |
| 2 | 描边寿命定位 | `OUTLINE_TICKS = 60`（3 秒）先于方块回收寿命到期 | ☑ |
| 3 | 描边跟随方块 | 去掉 TTL，描边一直保留到 `noteRemove`（回收/拆除/替换） | ☑ |
| 4 | 挥臂定位 | spearcore 在命中循环里调二参 `swing`，TLM 平A调一参；旧版两个重载一起吞 | ☑ |
| 5 | 只放行伤害那一下 | 一参始终吞；二参只在 `getTicksUsingItem` 落在伤害窗口（end-2..end）时放行 | ☑ |
| 6 | 目标读取补缝 | `suppressesMelee` 改用 `currentTarget`（brain 回退），避免 TLM 重写目标时漏掉挥臂闸 | ☑ |
| 7 | 编译与护栏 | Neo 编译 `EXITCODE=0`；14 护栏 `FAIL 0 / WARN 0`；架构行号与 `normify-promaid/` 重新生成 | ☑ |
| 8 | 专服回归 | 测试服临时放入 `spearcore-1.21.1-neo-3.1.0`：`test_server` 与 `test_maid_load` 均 PASS，测试后移除 | ☑ |
| 9 | 部署与验收 | 只部署 1.21.1 实例；Forge 源码树与两个 Forge 实例一律不动 | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

- 开发者原话：
  「1.新的长矛空袭飞行高度上去了，但打不到怪了
  2.女仆自保搭建的方块不是有黄色光标吗，但我发现光标会比搭的方块先消失
  3.女仆攻击模式手持长矛冲锋时还是会不停的挥臂，我要的是造成伤害的那一下挥臂，其他时候无动作」

## 评估

1. **打不到怪**：R1027 把爬升抬到 10 格后，进场曲线仍然沿用 WineFox 的
   `goalY = passY + slope*d²/(d+8)`——它在 `d→0` 才收到矛线高度。点火条件同时要求
   `|dy| ≤ 0.4` 与 `distH ≥ MIN_FIRE_DIST(2)`，而 `dy ≤ 0.4` 只在 1~2 格内成立，
   两个窗口正好错开，所以她一路俯冲却一发都点不着。修法是把曲线整体前移
   `AIM_LEVEL_DIST = 6` 格：`dGoal = max(0, distH - 6)`，在离目标 6 格时就压到矛线高度，
   之后 6 格平飞进点火窗口。贴地保护保证这段平飞不会接地。
2. **描边先消失**：`PlacedMarkNetworking.OUTLINE_TICKS = 60`，描边 3 秒后由 `tick`
   主动撤框；战斗方块本身由 `PlacedBlockTracker` 按 `combatPlacedLifetime` 保留更久。
   改为 `OUTLINE_ALIVE = Long.MAX_VALUE`，描边只随 `noteRemove` 撤销（回收 / 女仆拆除 /
   玩家替换 / 会话清场），与方块寿命一致。
3. **不停挥臂**：spearcore 的 `onUseTick` 在命中循环里调用
   `swing(hand, true)`（二参），TLM 的 `MaidMeleeAttack` 调用 `swing(hand)`（一参）。
   旧版两个重载一起吞，伤害那一下也被吞掉；而 TLM 的平A/使用动作在目标记忆闪空的拍
   会从缝里漏出来。现在一参始终吞；二参只在 `getTicksUsingItem()` 落在
   `[end-2, end]`（spearcore 的伤害窗口）时放行。`suppressesMelee` 同时改用
   `currentTarget`（brain 回退），与 tick 的目标读取同口径。

## 改动文件

- `promaid_src_neo/com/maidsmart/combat/SpearAirRaidBehavior.java`（890 行）：
  `AIM_LEVEL_DIST`，`dEff/dGoal` 前移的进场曲线。
- `promaid_src_neo/com/maidsmart/task/PlacedMarkNetworking.java`（297 行）：
  描边 TTL → 跟随方块寿命。
- `promaid_src_neo/com/maidsmart/combat/SpearCoreCompat.java`（249 行）：
  新增 `isDamageSwingFrame`。
- `promaid_src_neo/com/maidsmart/mixin/MaidSpearSwingMixin.java`（50 行）：
  一参全吞、二参只放行伤害窗口。
- `promaid_src_neo/com/maidsmart/combat/SpearAttackBehavior.java`（431 行）：
  `suppressesMelee` 目标读取补 brain 回退。
- `tools/arch/arch_modules.py` 与 `normify-promaid/`：行号同步。

## 验证

- Neo 编译：`EXITCODE=0`。
- 全部护栏：`FAIL 0 / WARN 0`；`check_arch` 一致；`check_mixin_targets` 校验 275 个注入点。
- 专服（测试服 mods 里临时放 `spearcore-1.21.1-neo-3.1.0.jar`）：
  `test_server.py neoforge1211` PASS；`test_maid_load.py neoforge1211` PASS；测试后已移除。
- Neo jar：18,130,582 字节，SHA256
  `921B7B02E7F5D997AB669CB4639DB12C2422E58B9DA933F5DAA6B3D2C080DCF8`。
- 已部署 1.21.1 实例，源包/目标包字节数一致（18,130,582）；目录内只有一个 promaid 包：
  `promaid-1.3.3-neoforge-1.21.1.jar`。Forge 源码树与两个 Forge 实例本轮零改动。

## 边界

- 仅 1.21.1 NeoForge；1.20.1 的镜像**等开发者验收批准后**再做。
- 点火窗口前移 6 格是固定值，不随 `passRange` 配置变化；若玩家把点火距离调到 <6，
  仍会在压平后点火。
- 描边现在一直保留到方块回收；如果玩家希望"看一眼就淡出"，需要另加配置项。
- 伤害挥臂依赖 spearcore 的伤害窗口反射（`computeDamageUseDuration`）；未装 spearcore
  时判据恒 false，行为不变。
- 版本号仍为 1.3.3（同号重打）。

## 验收

1. **空袭命中**：近战空袭在 10 格限飞高度下，日志应重新出现「水平贯穿点火」，
   并且目标身上能看到长矛伤害；不应再出现一路俯冲却零点火。
2. **描边**：自保垫高后，金色描边应一直保留到方块被回收/拆除；不应 3 秒就消失。
3. **挥臂**：攻击模式持矛冲锋时，蓄力/追击/后撤段手臂应保持不动；只在长矛实际
   造成伤害的那一下出现一次挥臂。
4. 卸载 `spearcore`：新增判据恒 false，行为与之前一致，无报错。
