# R1033 1.21.1 长矛飞行路径碰撞补判（撤销范围补刀）（1.3.3 同号重打）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修复（1.21.1 专属；1.20.1 等开发者验收批准后再镜像）。
- **开工前读过**：`rounds/R1032`、`SpearAirRaidBehavior` 相位与 `tookSpearHit`、
  spearcore 的 `onUseTick` 命中扫描（沿视线实体射线，不覆盖侧向擦碰）、
  `MaidFlightCombatBehavior.hitOne`。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 撤销范围补刀 | 删除 `forceHitAround` 与命中拍的周身扫描；恢复单目标口径 | ☑ |
| 1 | 路径碰撞补判 | 用上一 tick 与本 tick 碰撞箱组成扫掠盒，擦过/撞上的敌对目标补一次武器结算 | ☑ |
| 2 | 防重复 | 本趟飞行每个实体最多补一次；本 tick 已被她打过的目标跳过 | ☑ |
| 3 | 安全边界 | 只认 `IAttackTask.canAttack`、跳过女仆/友方；碰撞不需要视线（身体确实经过了） | ☑ |
| 4 | 与主链路衔接 | 撞到主目标时进入既有命中保持；每趟再点火时清空补判记录 | ☑ |
| 5 | 编译与护栏 | Neo 编译 `EXITCODE=0`；14 护栏 `FAIL 0 / WARN 0` | ☑ |
| 6 | 专服回归 | 测试服临时放入 `spearcore-1.21.1-neo-3.1.0`：`test_server` 与 `test_maid_load` 均 PASS，测试后移除 | ☑ |
| 7 | 部署与验收 | 只部署 1.21.1 实例；Forge 源码树与两个 Forge 实例一律不动 | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

- 开发者原话：
  「不是这个问题，有的时候女仆在飞行路径中途撞到怪物却不会造成伤害。我要补的是这个，
  不是范围补刀，那样子就不是长矛了。」

## 评估

- spearcore 的矛伤扫描是**沿视线**的实体射线/判定框：贴身侧向擦过、机体从怪物身上穿过去
  但视线没对准时，不会结算。R1032 的周身范围补刀能补上，但会连带打到没碰到的怪，
  观感变成 AoE，不符合长矛。
- 本轮改为**路径碰撞补判**：每 tick 取上一 tick 位置与本 tick 碰撞箱，组成扫掠 AABB
  （外扩 0.35 格），只有机体轨迹真的经过的实体才会被补判。一次只打一个实体，
  仍然是长矛武器结算（附魔 + 空中暴击 + 击退）。
- 每趟飞行维护一个 `pathHits` 集合，同一实体只补一次；next relaunch 成功后清空。
- 本 tick 已经被她的长矛打过的目标（`lastHurtByMob == 她 && hurtTime > 0`）直接跳过，
  避免同一记伤害补两次。
- 撞到主目标时调用既有 `beginHitHold`，保持"命中 → 水平 → 矢量耗尽 → 再点火"的节奏；
  同时推进 `lastHurtStamp`，避免下一 tick 重复进入保持。

## 改动文件

- `promaid_src_neo/com/maidsmart/combat/SpearAirRaidBehavior.java`（997 行）：
  新增 `detectPathHit`、`PATH_HIT_INFLATE`、每趟 `pathHits`；`beginHitHold` 推进受伤基线。
- `promaid_src_neo/com/maidsmart/combat/MaidFlightCombatBehavior.java`（3420 行）：
  删除 `forceHitAround`，新增单目标 `spearPathHit`。
- `tools/arch/arch_modules.py` 与 `normify-promaid/`：行号同步。
- R1032 的范围补刀已撤销，文件留档。

## 验证

- Neo 编译：`EXITCODE=0`。
- 全部护栏：`FAIL 0 / WARN 0`；`check_mixin_targets` 校验 277 个注入点。
- 专服（测试服 mods 里临时放 `spearcore-1.21.1-neo-3.1.0.jar`）：
  `test_server.py neoforge1211` PASS；`test_maid_load.py neoforge1211` PASS；测试后已移除。
- Neo jar：18,138,333 字节，SHA256
  `DD560800700F94D44B2AE23FBAE2887CD257CC055190A50D338FF6009AF43FAE`。
- 已部署 1.21.1 实例，源包/目标包字节数一致（18,138,333）；目录内只有一个 promaid 包：
  `promaid-1.3.3-neoforge-1.21.1.jar`。Forge 源码树与两个 Forge 实例本轮零改动。

## 边界

- 仅 1.21.1 NeoForge；1.20.1 的镜像**等开发者验收批准后再做**。
- 补判伤害是一次长矛武器结算（攻击力 + 附魔 + 空中暴击），不是 spearcore 的相对速度公式；
  主目标被 spearcore 正常命中时仍走原公式。
- 只有机体轨迹真的经过（扫掠盒 + 0.35 格外扩）才会触发，不会再出现周身 AoE。
- 同一趟飞行同一实体最多一次；重新点火后进入新的一趟。
- 版本号仍为 1.3.3（同号重打）。

## 验收

1. **路径碰撞**：让女仆从怪物身上/身边擦过，即使视线没完全对准，也应看到那一下掉血/击退；
   日志出现「长矛碰撞命中（路径）」。
2. **不是 AoE**：没有经过的怪不应掉血；主人/女仆/友方宠物/中立动物不受影响。
3. **节奏**：撞到主目标后应正常进入命中保持、矢量耗尽后再点火，不应重复结算或卡在保持。
4. 正常矛线命中时行为与之前一致，不应出现同一目标一下掉两次血。
