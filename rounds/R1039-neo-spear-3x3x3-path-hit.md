# R1039 1.21.1 长矛空袭路径及周边 3×3×3 强制判定（1.3.3 同号重打）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修复（1.21.1 NeoForge 专属；1.20.1 无长矛空袭独立链路，不镜像）。
- **开工前读过**：`rounds/R1033`（路径碰撞补判）、`SpearAirRaidBehavior.detectPathHit`、
  `MaidFlightCombatBehavior.spearChargeSweep`、`spearPathHit/hitOne` 的强制命中口径。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 定位 | 确认补判盒只外扩 0.35 格，高速掠过时差半格就完全不算命中 | ☑ |
| 1 | 空袭路径判定 | 独立链路扫掠盒外扩 1.0 格（路径 ±1，3×3×3） | ☑ |
| 2 | 冲锋路径判定 | 0.5 秒冲锋扫掠盒同样外扩 1.0 格 | ☑ |
| 3 | 去重 | 每趟飞行每个目标仍只补一次，本拍已挨打的目标跳过 | ☑ |
| 4 | 编译与护栏 | Neo 编译 `EXITCODE=0`；14 护栏 `FAIL 0 / WARN 0` | ☑ |
| 5 | 专服回归 | 临时挂载 spearcore：`test_server` / `test_maid_load` 均 PASS，测试后移除 | ☑ |
| 6 | 部署与验收 | 只部署 1.21.1 实例；Forge 树与两个 Forge 实例不动 | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

- 开发者原话：
  「还是打不中，不过处理方法也简单，走后门就行了。空袭状态下把女仆路径及周边3×3×3范围内
  都算作判定范围。这样子就能打中了」

## 评估

- R1033 的路径补判只把扫掠盒外扩 0.35 格；R1037 修好进场速度后，女仆仍可能以
  1 格/tick 左右掠过，矛线差半格就完全不算命中，实机就是"看着从怪身上过去但没伤害"。
- 本轮按开发者要求走判定后门：把扫掠盒外扩改为 **1.0 格**，即机体路径及周边
  **3×3×3** 范围都算判定范围。
- 改动点：
  - `SpearAirRaidBehavior.PATH_HIT_INFLATE`：0.35 → 1.0（独立长矛空袭全相位）；
  - `MaidFlightCombatBehavior.SPEAR_CHARGE_INFLATE`：0.35 → 1.0（≤3 目标的 0.5 秒冲锋）。
- 结算仍走 `spearPathHit → hitOne`：附魔、暴击/重锤加成、强制破无敌帧、击退、特效，
  与正常矛伤同一套；每趟飞行每个目标只补一次（`pathHits` / `hits`），本拍已被她打过的目标跳过。
- 不是新增范围伤害：3×3×3 只是把"路径擦过"的容差放大，命中来源仍是她这一趟飞行轨迹。

## 改动文件

- `promaid_src_neo/com/maidsmart/combat/SpearAirRaidBehavior.java`（1106 行）：
  `PATH_HIT_INFLATE = 1.0`，注释改为路径 ±1 格。
- `promaid_src_neo/com/maidsmart/combat/MaidFlightCombatBehavior.java`（3672 行）：
  `SPEAR_CHARGE_INFLATE = 1.0`，注释同步。
- `tools/arch/arch_modules.py` 与 `normify-promaid/`：行号与描述同步。

## 验证

- Neo 编译：`EXITCODE=0`。
- 全部护栏：`FAIL 0 / WARN 0`；`check_mixin_targets` 校验 277 个注入点。
- 专服（临时挂载 `spearcore-1.21.1-neo-3.1.0.jar`）：
  `test_server.py neoforge1211` PASS；`test_maid_load.py neoforge1211` PASS；测试后已移除。
- Neo jar：18,144,617 字节，SHA256
  `CE13622EBD5CE1F2AFEC3A34622D66B103151BEA90C0B52E27AC939407AAB09C`。
- 已部署 1.21.1 实例，源包/目标包字节数一致（18,144,617）。Forge 源码树与两个 Forge 实例本轮零改动。

## 边界

- 仅 1.21.1 NeoForge；1.20.1 Forge 树没有长矛空袭独立链路与 0.5 秒冲锋，本轮不涉及。
- 3×3×3 是**判定容差**，不是范围攻击：只有这一趟飞行路径附近的合法敌对目标才会被补判，
  且每趟每目标最多一次。
- 合法目标口径不变：`IAttackTask.canAttack` + `FriendlyFireGuard` + 存活/同维度；不追加视线判定
  （后门本来就是给"差半格"兜底；贴墙目标也在容差内）。
- 版本号仍为 1.3.3（同号重打）。

## 验收

1. **空袭掠过**：持长矛在 >3 怪群里空袭，机体从怪身边 1 格内掠过时应稳定出现「长矛碰撞命中（路径）」，
   不再需要精确穿过碰撞箱。
2. **0.5 秒冲锋**：≤3 目标时冲锋从怪身边擦过，应出现「冲锋命中（路径）」。
3. **去重**：同一趟飞行对同一个怪只补一次；绕回来再次进场才会再打。
4. **不误伤**：友方/女仆/活动范围外的目标仍不会被补判。
