# R1076 固定翼巡航速度环真正生效（绕圈半径减半）+ 灾变 Boss 距离上限诊断

## 排班

- **跑道**：A 修 bug（固定翼绕主人/绕敌人圈太大、通场火力仍有断开）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 反编译 `aircraftEngine` 核推力 | 每拍沿视线推力 ≈0.18~0.24，大于速度环步长 0.15 | ★ |
| 2 | 前射机改用严格步长 | 实际巡航速度 2.0 → 0.8~1.0，半径 67 → 27~34 | ★ |
| 3 | egress 再收紧 | 100 → 60 格，脱离静默约 5 秒 → 2~3 秒 | ★ |
| 4 | 灾变 Boss 特殊代码 | `range_cap=18`（>27 格零伤害）+ 伤害桶/DPS 上限 | ★ |
| 5 | 两树编译、14 护栏、四套测试、4 实例部署 | 全部 PASS / match=True | ★ |
| 6 | 交开发者验收 | 圈明显变小、火力断开变短 | ☐ |

## 需求 / 来源

- 开发者口径：「中途还是会有一些断开。并且平时对于主人的绕圈护卫半径太大了。
  对着敌人绕圈也是。」「绕圈半径太大」。
- 另问：「灾变的下界合金巨兽是不是有什么特殊代码？飞机上面的炮弹无法对其造成伤害。」

## 根因（反编译 SWB `VehicleEngineUtils.aircraftEngine`）

每拍空中给速度的项有两处：

```
dm += view * 0.04 * dotView          // 固定前推
dm += view * 0.047 * power * speedRate   // 推力（power 常驻 3.0）
```

合计约 **0.18~0.24 格/拍**。而巡航硬速度环 `aircraftGovernSpeedHard` 每拍只允许减
`AIRCRAFT_CRUISE_SPEED_STEP = 0.15`——**比引擎给的还少**，于是每拍减掉的又被加回来，
速度长期停在引擎自己的平衡点 ~2.0 格/拍（实机日志 `水平速度=1.41~2.10`）。
转弯角速度由 `rotSpeed = 0.3 + 3.2×|roll|/90` 决定，满滚转 1.71°/拍 = 0.0298 弧度/拍，
所以真实转弯半径 `r ≈ 2.0 / 0.0298 ≈ 67 格`，不是注释里按 0.80 算的 27 格。
主人护卫圈、敌我通场圈、掉头半径全是这一个数。

## 改法（两树）

### 1. `MaidMountCompat`（巡航速度环）

新增 `AIRCRAFT_CRUISE_SPEED_STEP_STRICT = 0.40`：必须大于引擎每拍最大推力（0.24）。
`aircraftCruise` 里按机型选步长：

```java
double speedStep = isGunship(mount)
        ? AIRCRAFT_CRUISE_SPEED_STEP          // 炮艇保持 0.15（84 格盘旋不靠压速度）
        : AIRCRAFT_CRUISE_SPEED_STEP_STRICT;  // 前射机 0.40
aircraftGovernSpeedHard(mount, AIRCRAFT_CRUISE_SPEED, speedStep);
```

收敛后速度稳定在 0.8~1.0，转弯半径约 27~34 格——主人护卫圈与敌人通场圈同时减半，
9 格/秒级的速度也把撞地伤害从 `18×(2.0−0.2)² ≈ 58` 点降到个位数。

### 2. `MaidAirCombat`（通场脱离段）

`RUN_EGRESS` 100 → 60：速度降下来后 100 格外飞是多余余量，脱离静默从约 5 秒
缩到约 2~3 秒；`RUN_LINE 150 / RUN_ENTRY 190 / RUN_MIN 45` 不变。炮艇路径不经过这些常量。

## 灾变「下界合金巨兽」的特殊代码（诊断，未改对方代码）

反编译 `com.github.L_Ender.cataclysm.entity.InternalAnimationMonster.IABossMonsters.IABoss_monster`
与 `Animation_Monsters.calculateRange`：

```java
// Animation_Monsters.calculateRange(source)
return source.getEntity() != null ? distanceToSqr(source.getEntity()) : -1;

// IABoss_monster.hurt(source, amount)
if (isInvulnerableTo(source)) return false;
if (source.is(BYPASSES_INVULNERABILITY)) return super.hurt(source, amount);
amount = min(DamageCap(), amount);
distSqr = calculateRange(source);
limit = RangeLimit();          // = 配置 range_cap
if (distSqr >= (limit*1.5)^2) return false;        // 超 27 格：零伤害
if (distSqr > limit^2) amount *= 线性衰减;
// 再走 damageBucket（上限 DamageCap 25、每拍回 DpsCap/20 = 1）
```

当前 `cataclysm-common.toml`：

```toml
[mobs.netherite_monstrosity.cap_config]
    damage_cap = 25.0
    dps_cap = 20.0
    dps_limit_time = 20
    range_cap = 18.0
```

所以：**伤害来源的责任实体（开炮的女仆/载具）离巨兽 ≥27 格 → 每一发都返回 false，
完全零伤害**；18~27 格线性衰减；28 格内的命中还会被 25 点的伤害桶与 1 点/拍的回充
压成很小的数字。飞机在 45~200 格开火，正好整段落在零伤害区。

这不是本模组的 bug，是灾变为反远程逃课写的机制。可选项（都属服主配置，不改对方代码）：
1. 把 `range_cap` 调到大于交战距离（例如 120~200），飞机即可正常输出；
2. 保留原机制，接受飞机打不动这只 Boss（近战/地面单位才能伤它）；
3. 只在需要时用 `/gamerule` 或临时换武器，不必动模组。

本模组**未**加绕过（不 mixin 对方类、不改对方配置）。

## 触及文件

- `promaid_src/com/maidsmart/combat/MaidMountCompat.java`
- `promaid_src_neo/...` 同名文件镜像
- `promaid_src/com/maidsmart/combat/MaidAirCombat.java`（`RUN_EGRESS`）
- `promaid_src_neo/...` 同名文件镜像
- 无新配置项、无新 i18n、无新 mixin、无新类

## 验证

- Forge `compile_promaid`：`EXITCODE=0`；Neo `compile_neo`：`EXITCODE=0`。
- 14 护栏全部 `FAIL 0`。
- `test_server 1201` / `test_server neoforge1211`：PASS。
- `test_maid_load 1201` / `test_maid_load neoforge1211`：PASS。
- `deploy_all.py`：4 个实例全部 `match=True`。
- 产物：
  - Forge `patched/promaid-1.3.3-forge-1.20.1.jar` 18,105,249 字节，
    SHA256 `814E61EA5F623AC6A5D9DD5277F6FA86F834F3091DBCA97E2CBDBB24533D2EF6`
  - Neo `patched/promaid-1.3.3-neoforge-1.21.1.jar` 18,192,668 字节，
    SHA256 `0CEF5D58687B9080F01BE838CB97BBE2D7C934B8C7672594860C6D80675EDED7`

## 验收

- 状态：`未验收`。验收人 = 开发者；验法：
  1. 无敌人时看固定翼跟随：绕主人的圈直径应明显变小（约 60~70 格，旧版 130+）。
  2. 接敌通场：`EGRESS` 后不再出现 200+ 格的外飞；开火空档应只剩脱离段约 2~3 秒。
  3. 看 `固定翼巡航档` 的 `水平速度`：应长期在 0.8~1.0，不再是 1.4~2.1。
  4. AC-130H 回归：仍是 84 格盘旋，速度环步长未改。
  5. 巨兽：按现配置（`range_cap=18`）飞机打不出伤害是预期；要能打需由服主调大该项。

## 边界 / 已知取舍

- 巡航速度降到 0.8~1.0 后，爬升率/机动余量都变小；起飞与爬升段仍用高推力高速度，
  只在巡航/接敌段压速。
- 转弯半径的物理下限仍由 `rotSpeed` 决定（满滚转 1.71°/拍）；0.8 速度下约 27 格，
  不可能再小，除非直接改写引擎的偏航角。
- 巨兽机制未绕过：这是对方模组的设计，绕过会改变对方 Boss 战平衡，需服主自行决定。

## 台账

- 未新增债。反射计数保持基线（`getMethod` Forge 205 / Neo 206）。
