# R1032 1.21.1 长矛贯穿范围补刀（重锤同款后门）（1.3.3 同号重打）

> **后续：R1033 已撤销本轮的"范围补刀"——玩家要的是飞行路径碰撞补判，不是周身 AoE；本文件仅留档。**

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 功能补齐（1.21.1 专属；1.20.1 等开发者验收批准后再镜像）。
- **开工前读过**：`MaidFlightCombatBehavior.smashHit` / `hitOne` / `forcedHitRadius`、
  `SpearAirRaidBehavior.beginHitHold`、`SpearCoreCompat`、`MaidFlightCombatTask`（实现 IAttackTask）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 口径对齐 | 复用重锤后门的半径配置、视线门控、IAttackTask.canAttack、女仆免疫 | ☑ |
| 1 | 触发时机 | 长矛贯穿命中主目标的那一拍（`tookSpearHit` → `beginHitHold`）补一次范围结算 | ☑ |
| 2 | 单次结算 | 每个范围内其他合法敌对目标调用一次 `hitOne`（附魔 + 空中暴击 + 击退 + 特效） | ☑ |
| 3 | 日志 | 搜「长矛后门」可看到补刀数量与半径 | ☑ |
| 4 | 编译与护栏 | Neo 编译 `EXITCODE=0`；14 护栏 `FAIL 0 / WARN 0` | ☑ |
| 5 | 专服回归 | 测试服临时放入 `spearcore-1.21.1-neo-3.1.0`：`test_server` 与 `test_maid_load` 均 PASS，测试后移除 | ☑ |
| 6 | 部署与验收 | 只部署 1.21.1 实例；Forge 源码树与两个 Forge 实例一律不动 | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

- 开发者原话：
  「1.1.能不能给空袭长矛加上重锤的同款后门？就是女仆周身范围内的怪物都强制受到一次这样的伤害。」

## 评估

- 重锤后门在 `smashHit` 里：主目标打完后再扫 `maid.getBoundingBox().inflate(forcedHitRadius())`，
  对范围内其他合法敌对目标各 `hitOne` 一次。条件是 `IAttackTask.canAttack` + 真实视线 +
  `FriendlyFireGuard`，女仆一律免疫。
- 长矛贯穿的问题相同：spearcore 的穿透判定通常只够到主目标，贴身小怪会漏。本轮把同一段
  范围结算抽成 `forceHitAround`，在长矛命中主目标的那一拍调用。
- 伤害口径：主目标仍由 spearcore 按相对速度结算；范围内其他目标各吃一次 `hitOne`
  （武器攻击力 + 附魔 + 空中暴击 ×1.5 + 击退/粒子）。这是"重锤后门"意义上的强制命中，
  不是复制 spearcore 的贯穿伤害公式。
- 半径直接复用配置 `airRaid.forcedHitRadius`（默认 2.5 格），不新增配置项。

## 改动文件

- `promaid_src_neo/com/maidsmart/combat/MaidFlightCombatBehavior.java`（3450 行）：
  新增 `forceHitAround`（与 `smashHit` 范围补刀同口径）。
- `promaid_src_neo/com/maidsmart/combat/SpearAirRaidBehavior.java`（915 行）：
  `beginHitHold` 接 `ServerLevel` 并在命中拍调用范围补刀。
- `tools/arch/arch_modules.py` 与 `normify-promaid/`：行号同步。

## 验证

- Neo 编译：`EXITCODE=0`。
- 全部护栏：`FAIL 0 / WARN 0`；`check_mixin_targets` 校验 277 个注入点。
- 专服（测试服 mods 里临时放 `spearcore-1.21.1-neo-3.1.0.jar`）：
  `test_server.py neoforge1211` PASS；`test_maid_load.py neoforge1211` PASS；测试后已移除。
- Neo jar：18,137,549 字节，SHA256
  `AB01A6F683519DDE4388AC924A7E72310281C98BA6D990395C92B7A7EEF87B86`。
- 已部署 1.21.1 实例，源包/目标包字节数一致（18,137,549）；目录内只有一个 promaid 包：
  `promaid-1.3.3-neoforge-1.21.1.jar`。Forge 源码树与两个 Forge 实例本轮零改动。

## 边界

- 仅 1.21.1 NeoForge；1.20.1 的镜像**等开发者验收批准后**再做。
- 只在主目标被长矛真正命中时触发；空挥/穿出不触发（与重锤"打中才扫"一致）。
- 范围内目标吃的是普通武器结算，不是 spearcore 的相对速度增伤；主目标仍是原公式。
- 不额外扣武器耐久（与重锤后门一致，整次出手只扣 1 点，由 spearcore 自己处理）。
- 版本号仍为 1.3.3（同号重打）。

## 验收

1. **范围补刀**：空袭长矛命中主目标时，周围 2.5 格内的其他敌对怪物应同时掉血/被击退；
   日志出现「长矛后门：贯穿范围补刀 N 只」。
2. **不误伤**：主人、其他女仆、友方宠物、中立动物不应被补刀。
3. **隔墙不打**：墙后的怪物即使落在半径内也不应被打到（视线门控）。
4. 把 `airRaid.forcedHitRadius` 调到 0 或关闭空袭链路时，行为与之前一致。
