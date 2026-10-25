# R1077 固定翼俯冲攻击 + 地面载具接敌/死目标收尾

## 排班

- **跑道**：A 改行为（固定翼攻击方式）+ B 修 bug（坦克不绕圈 / 目标死亡后鬼畜）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 存档反馈与定位 | 本文件（反馈原文 + 根因） | ★ |
| 2 | 固定翼改俯冲 | ATTACK 胡萝卜挂敌正上方、低空、减速；拉升后再来 | ★ |
| 3 | 开火连续性 | ATTACK 段内持续开火，不再只打水平通场 | ★ |
| 4 | 坦克不绕圈 | 地面载具的主人接敌绳 50 → 192 | ★ |
| 5 | 死目标后鬼畜 | `targetOf` 退回实体层时也要求 `isAlive` | ★ |
| 6 | 两树编译、14 护栏、四套测试、4 实例部署 | 全部 PASS / match=True | ★ |
| 7 | 交开发者验收 | 俯冲能打中、坦克会绕、杀完不乱抖 | ☐ |

## 反馈原文（1.3.2 老版本粉丝）

> 固定翼其实应该类似鞘翅的枪或重锤的攻击模式，先飞远然后垂直对准目标一边减速一边射击，
> 接近射击一定时间或接近目标一定距离拉升然后重复以上操作，像是尤猪 a10 就是，
> 盘旋打不着，但是盘点敌人存在就一直开火，能打到的只有那枚追踪导弹。
> 坦克也是，距离敌人一定距离就在那不走了，很少绕圈，我坐在副驾驶她才开始绕，
> 但目标死亡后好像因为什么指令导致疯狂鬼畜。

拆成三条可执行需求：

1. **固定翼**：飞远 → 回到目标正上方 → 减速俯冲射击 → 到距离/时间就拉升 → 重复；
   敌人存在期间保持开火，不要再走"横向通场"。
2. **坦克/地面载具**：接敌后应该保持绕圈，不能停在某个距离不动。
3. **目标死亡后**：不能出现原地/左右疯狂抖动。

## 根因

### 1. 固定翼"盘旋打不着"

- `MaidAirCombat.fixedWingAttackTarget` 的 ATTACK 段返回的是
  `pos + run × 120`——**水平**前推的胡萝卜。飞机在目标侧面/前方平飞，炮弹虽然由
  `directFireAt` 按弹道解算直射，但远距离 + 地形/提前量误差仍然容易落空；
  制导弹有末端制导，所以只有它看起来能命中。
- 用户要的是**从目标正上方俯冲**：几何上距离短、视线不被地形挡，机炮才有意义。

### 2. 坦克停在原地 / 只有玩家在副驾才绕

- `RideBindManager.drive` 的地面载具分支有一道"主人不在场就收手"的闸：

  ```java
  if (gfoe != null && maid.distanceTo(owner) > FlightTargeting.RANGE) {  // 50 格
      gfoe = null;
  }
  ```

- 主人在 50 格外（在基地/在后面跟着）时，目标被清空 → 落到"跟着主人"档，
  车就停在原地/往主人开；玩家一坐进副驾驶，主人与她的距离≈0 → 闸通过 → 开始绕圈。
  这与反馈完全吻合。

### 3. 目标死亡后疯狂鬼畜

- `RideBindManager.targetOf` 的兜底：

  ```java
  try { return maid.getTarget(); } catch (...) { return null; }
  ```

  **没有 `isAlive` 判断**。brain 里的 `ATTACK_TARGET` 死亡后，第一段判 `!isAlive`
  落到兜底，`maid.getTarget()` 仍可能返回那只死实体 → `fighting = true`：
  - `modeMove` 保持为真，继续把她的**旧战斗走位点**喂给车；
  - `tryEngageRider` 因 `!fighting` 不成立而不再重新索敌；
  - 同时 `FlightTargeting.resolve` 已经返回 null、`MaidAirCombat.clear` 清掉了绕圈状态。
  两边一拉一扯，车就抖/鬼畜。

## 改法（两树）

### A. 固定翼俯冲攻击（`MaidAirCombat`）

- ATTACK 段的胡萝卜从"水平前推 120 格"改成**目标正上方、低空**：

  ```java
  return new Vec3(center.x, center.y + fightAltCfg() - DIVE_DEPTH, center.z);
  ```

  `DIVE_DEPTH = 45`，实际高度还会被 `driveFlight` 的 `aircraftAltBonus` 与地形净空线
  修正，最终稳定在目标上方约 30～44 格——足够形成俯冲角。
- 触底/接近：`horizontalDist(pos, center) < RUN_MIN`（45 → 18）或俯冲时间超限 →
  EGRESS：向 `pos + run × RUN_EGRESS`（80 格）拉升，目标高度再抬 `DIVE_PULL_UP`（35 格）。
- EGRESS 到位或超时 → REJOIN → 再次进入 ATTACK，形成"俯冲—拉升—再来"的循环。
- ATTACK 段内 `fixedWingFireAllowed` 仍为真，机炮/火箭按各自 RPM 持续开火。
- 新增 `aircraftDiveActive(mount)`：ATTACK 段内把巡航速度目标从 0.80 压到
  `AIRCRAFT_DIVE_SPEED = 0.60`，实现"一边减速一边射击"；拉升段恢复 0.80。

### B. 地面载具接敌绳（`RideBindManager` + `MaidAirCombat`）

- 新增 `MaidAirCombat.GROUND_ENGAGE_LEASH = 192.0`，地面分支改用它：

  ```java
  if (gfoe != null && maid.distanceTo(owner) > MaidAirCombat.GROUND_ENGAGE_LEASH) {
      gfoe = null;
  }
  ```

  主人在 192 格内就允许坦克继续接敌；玩家在不在副驾驶不再决定"绕不绕"。

### C. 死目标收尾（`RideBindManager.targetOf`）

```java
LivingEntity t = maid.getTarget();
return t != null && t.isAlive() ? t : null;
```

死目标不再算 `fighting`，`modeMove` 落回"跟着主人"，`tryEngageRider` 也能重新索敌，
两边不再对拉。

## 触及文件

- `promaid_src/com/maidsmart/combat/MaidAirCombat.java`（俯冲几何 / 减速标记 / 地面接敌绳）
- `promaid_src_neo/...` 同名镜像
- `promaid_src/com/maidsmart/combat/MaidMountCompat.java`（俯冲段速度目标）
- `promaid_src_neo/...` 同名镜像
- `promaid_src/com/maidsmart/combat/RideBindManager.java`（接敌绳 / targetOf）
- `promaid_src_neo/...` 同名镜像
- 无新配置项、无新 i18n、无新 mixin、无新类

## 验证

- 两树编译 `EXITCODE=0`。
- 14 护栏 `FAIL 0`。
- `test_server 1201` / `test_server neoforge1211`：PASS。
- `test_maid_load 1201` / `test_maid_load neoforge1211`：PASS。
- `deploy_all.py`：4 个实例 `match=True`。
- 产物与哈希见本轮 `RELEASES.tsv`。

## 验收

- 固定翼：日志 `固定翼攻击航线 → ATTACK` 后，`距目标` 应从上方接近并降到 20 格内；
  炮弹落点应在目标附近，不再只有导弹能中；`水平速度` 在 ATTACK 段应降到 ~0.6。
- 坦克：主人不在副驾驶、但在 192 格内时，车应保持绕圈而不是停住。
- 杀完目标：车应回到跟随/重新索敌，不再高频左右抖。

## 边界 / 已知取舍

- 固定翼俯冲高度受地形净空线（地面上 44 格）限制，超平坦上最终约 +44，
  不是贴脸俯冲；这是为了避免撞地。
- 192 格接敌绳是折中：再大等于"满地图追"，再小又回到"主人在旁边才敢打"。
- 死目标修复只影响"是否还算在战斗"，不改她的索敌半径与优先级。

## 台账

- 未新增债。反射计数保持基线（`getMethod` Forge 205 / Neo 206）。
