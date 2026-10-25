# R1042 1.21.1 长矛增伤 / 鞘翅隐形烟花 / 群怪 100 格判定（1.3.3 同号重打）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修复（1.21.1 NeoForge 专属；1.20.1 无长矛空袭链路，暂不镜像）。
- **开工前读过**：`rounds/R1039`、`rounds/R1041`、spearcore `SpearItem.onUseTick` 的
  反编译口径（相对速度 × `getDamageMultiplier`）、`MaidSelfPropelledWings`、
  `MaidFlightCombatBehavior.tryLaunch/launchFirework`、`SpearAirRaidBehavior.countNearbyThreats`。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 定位 | ① 通常链路的强制命中绕过 spearcore，吃不到"移动越快伤害越高"；② 自推鞘翅走型号专属公式、轨迹与普通鞘翅不一致；③ 群怪判定半径 16 格且受活动范围限制 | ☑ |
| 1 | 长矛增伤 | `hitOne` 对长矛补 `floor(沿视线相对速度 × getDamageMultiplier())`，与 spearcore 同口径 | ☑ |
| 2 | 鞘翅后门 | 自推鞘翅改走普通鞘翅 + 原版烟花递推（`v←v×0.5+视线×0.85`），不消耗烟花；穿它时优先于烟花 | ☑ |
| 3 | 粒子 | 不再生成烟花实体：普通鞘翅无烟花粒子；壮丽/混沌改发 `DRAGON_BREATH` | ☑ |
| 4 | 群怪判定 | 以女仆为圆心半径 100 格（球）计数，去掉活动范围限制，5 tick 缓存 | ☑ |
| 5 | 编译与护栏 | Neo 编译 `EXITCODE=0`；14 护栏 `FAIL 0 / WARN 0` | ☑ |
| 6 | 专服回归 | 临时挂载 spearcore：`test_server` / `test_maid_load` 均 PASS，测试后移除 | ☑ |
| 7 | 部署与验收 | 只部署 1.21.1 实例；Forge 树与两个 Forge 实例不动 | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

- 开发者原话：
  ①「通常链路似乎无法触发增伤。」
  ②「做一个额外的后门：像壮丽鞘翅这种又满足飞行又满足鞘翅的。我们在飞行的时候不对它做额外兼容，
  自动把它的飞行调成跟普通鞘翅一样，同时不消耗烟花。也就是说当女仆穿壮丽鞘翅的时候，它的飞行轨迹
  跟正常鞘翅一模一样，只是不需要消耗烟花（效果都在，只是会隐去烟花的粒子效果，换成壮丽鞘翅特有的
  末影粒子。其他鞘翅也是类似的，隐去烟花粒子）」
  ③「对于三个敌人，这个判定判的有点太窄了。实际测试中，只要敌人排的稍微散一点就会被判定为只有一两个
  敌人。应该是以自身为圆心半径100格以内进行判定。」

## 评估

- **增伤**：spearcore 的蓄力伤害是 `基础 + floor(沿视线相对速度 × getDamageMultiplier())`，
  "移动越快伤害越高"。本模组的路径/落地强制命中走 `hitOne` 的通用结算，绕过了它，于是
  通常链路（≤3）看到的伤害没有这一份。本轮在 `hitOne` 里对长矛补同一口径的加成。
- **鞘翅**：`MaidSelfPropelledWings` 原先按每件鞘翅的型号公式推（壮丽 0.48/0.64 等），
  轨迹与普通鞘翅不同；且只在拿不出烟花时才启用。本轮改为：
  - 新增 `MaidVirtualFirework`：不再生成 `FireworkRocketEntity`，由代码每 tick 直接施加原版
    挂载烟花的递推 `v←v×0.5+视线×0.85`（40 tick，俯冲段按配置倍数放大）；
  - 自推鞘翅优先于烟花（穿它就不烧烟花），推力走上面同一套普通鞘翅递推；
  - 所有真实烟花（起飞/爬升/俯冲/盘旋/长矛贯穿/飞行跟随）也改走隐形烟花，粒子随之消失；
  - 粒子：壮丽 / 混沌鞘翅按模组自己的 `addParticle` 口径发 `ParticleTypes.DRAGON_BREATH`，
    其他鞘翅不发光。
- **群怪判定**：`GROUP_SCAN_RANGE` 16 → 100，AABB 后再按球半径收敛；去掉
  `isWithinRestriction`（它把散开的怪挡在活动范围外）；数量缓存 5 tick（100 格查询较重）。

## 改动文件

- `promaid_src_neo/com/maidsmart/combat/MaidVirtualFirework.java`（新增，159 行）。
- `promaid_src_neo/com/maidsmart/combat/MaidFlightCombatBehavior.java`（3710 行）：
  隐形烟花 tick、自推鞘翅优先且不消耗烟花、`launchFirework` 改直推、`hitOne` 长矛增伤、
  `forget/clearAll` 清理。
- `promaid_src_neo/com/maidsmart/combat/SpearCoreCompat.java`（304 行）：
  `speedDamageBonus` 与 `getDamageMultiplier` 反射缓存。
- `promaid_src_neo/com/maidsmart/combat/SpearAirRaidBehavior.java`（1171 行）：
  100 格球半径 + 缓存、隐形烟花 tick、贯穿那一记改隐形烟花。
- `promaid_src_neo/com/maidsmart/combat/MaidFlightFollowBehavior.java`（1369 行）：
  跟随补推改隐形烟花；自推鞘翅走普通鞘翅递推；进圈收掉隐形烟花窗口。
- `tools/arch/arch_modules.py`、`tools/verify/check_reflection.py`（反射基线 205 → 206）
  与 `normify-promaid/`：同步。

## 验证

- Neo 编译：`EXITCODE=0`。
- 全部护栏：`FAIL 0 / WARN 0`；`check_mixin_targets` 校验 277 个注入点。
- 专服（临时挂载 `spearcore-1.21.1-neo-3.1.0.jar`）：
  `test_server.py neoforge1211` PASS；`test_maid_load.py neoforge1211` PASS；测试后已移除。
- Neo jar：18,149,253 字节，SHA256
  `276EE19D3F6097A45BD89419621F30F54E6242C9ED27ED24CC17E2B4C801FDFD`。
- 已部署 1.21.1 实例，源包/目标包字节数一致（18,149,253）。Forge 源码树与两个 Forge 实例本轮零改动。

## 边界

- 仅 1.21.1 NeoForge；1.20.1 树没有长矛空袭链路与本轮鞘翅后门，暂不镜像。
- 隐形烟花保留原版**推力与时长**，只是不生成实体；烟花消耗规则不变（普通鞘翅照扣，
  穿自推鞘翅不扣）。因此"隐去粒子"不等于"不耗燃料"。
- 自推鞘翅的每件型号参数表仍保留在 `MaidSelfPropelledWings` 里（诊断/手册用），
  但飞行推力不再使用它——所有鞘翅统一普通鞘翅物理。
- 长矛增伤只补在 `hitOne`（路径/落地补判与重锤以外的武器结算）；spearcore 自己算的
  蓄力伤害仍由它自己结算，两处不重复叠加（路径补判会跳过本拍已挨打的目标）。
- 群怪判定是"路由用的数量"，不是攻击目标范围；实际索敌仍走原链路。
- 版本号仍为 1.3.3（同号重打）。

## 验收

1. **增伤**：≤3 敌人、持长矛空袭，落地/路径强制命中尸壳时伤害明显高于普通挥击；
   日志「落地强制命中」「长矛碰撞命中（路径）」仍在。
2. **鞘翅**：穿壮丽鞘翅、背包里放烟花，起飞后烟花数量不减；飞行轨迹与普通鞘翅一致
   （无型号专属加速），只见末影龙气息粒子，不见烟花粒子。
3. **普通鞘翅**：消耗烟花照旧，但空中不再出现烟花粒子/火箭实体。
4. **群怪判定**：把 4 个以上敌人分散到 20~80 格内，应稳定进入长矛独立链路
   （日志「接管控空（群怪 N 个）」），不再因分散被误判成 ≤3。
