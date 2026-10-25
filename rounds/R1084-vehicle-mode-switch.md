# R1084 骑乘载具时不再自动换战斗模式 / 还原链不再翻回玩家手选的模式

## 排班

- **跑道**：C 功能（两树镜像；Forge 1.20.1 / NeoForge 1.21.1）。
- **开工前读过**：`rounds/R1083`；`AutoCombatTargeting.tryEngageMaid`、
  `AutoCombatPools.retuneCombatTactics` / `buildPools` / `hasWeaponForTask`、
  `AutoCombatSwitch`（还原扫描）、`RideBindManager.tryEngageRider`、
  `MaidMountCompat.tickAttack`、`MaidWorkTags.isCombatTask`；
  实机日志 `D:\.minecraft\versions\1.21.1-NeoForge_21.1.250\logs\promaid.log`（UTF-8）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 定位「枪械模式被换成攻击」 | 日志证明是自动系统改 `getTask()`，非 TLM UI | ☑ |
| 1 | 骑乘载具时战中不换战术 | `retuneCombatTactics` 在 `isRidingVehicle` 时直接返回 | ☑ |
| 2 | 骑乘载具时参战入口不改任务 | 战斗任务 → 保持；非战斗任务 → 不自动参战 | ☑ |
| 3 | 还原链只还原本系统指派的模式 | 当前任务 ≠ `ASSIGNED_TAG` 时不 `setTask`，只清簿记 | ☑ |
| 4 | 编译与护栏 | 两树编译 `EXITCODE=0`；14 护栏 `FAIL 0` | ☑ |
| 5 | 重打双 jar | Forge / Neo 包均重新生成 | ☑ |
| 6 | 部署与专服回归 | 4 实例 `match=True`；四套测试 PASS | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

玩家原话：「女仆坐在载具上似乎会随意切换模式，刚刚我想把她切换成枪械模式，但她老是
自作主张换成攻击」。

## 评估

实机日志（NeoForge 1.21.1，`promaid.log`）证据链：

```
19:51:05 [战斗] 大正女仆酒狐 参战：touhou_little_maid:idle -> touhou_little_maid:gun_attack
19:52:06 [战斗] ... 僵局逃逸阀触发：威胁仍在 8 格内但已 60 秒无伤害往来 → 强制走还原
19:52:15 [战斗] ... 战斗还原：touhou_little_maid:attack -> touhou_little_maid:idle（威胁消失 10 秒）
19:52:15 [战斗] ... 参战：touhou_little_maid:idle -> touhou_little_maid:gun_attack
```

参战时选的确实是枪械模式，但还原日志里的来源已是 `attack`——中间的
`gun_attack -> attack` 没有任何日志，因为 `retuneCombatTactics` 不写运行日志。
三条根因：

1. **战中按距离换战术不看骑乘**：`retuneCombatTactics` 在威胁进入
   `MELEE_RANGE = 5` 格时把远程任务换成近战池任务（枪 → attack）。载具的索敌/开火
   本来由 `RideBindManager` + `MaidMountCompat.tickAttack` 独立完成，任务只是玩家
   选的"手持武器模式"；监守者一贴近，玩家刚选的枪械模式就被换掉。
2. **参战入口会重选**：`tryEngageMaid` 在"当前战斗任务 + `hasWeaponForTask` 为假"
   时继续 `pickCombatTask`，枪不在背包/被拿走时可能落回 attack。
3. **还原链把玩家手选当自己的安排**：`stillOnCombat` 旧口径是"当前是任意攻击任务
   就算本系统战斗"，威胁一解除就把玩家在战斗中亲手换的模式还原回战斗前任务
   （日志里的 `attack -> idle`）。`RideBindManager.tryEngageRider` 随后又触发参战，
   形成"换模式 → 还原 → 再参战"的循环，玩家看到的就是"她老是自作主张换成攻击"。

修法：

- `AutoCombatPools.isRidingVehicle(maid)`：`getVehicle()`（Forge `m_20202_()`）
  且 `MaidMountCompat.kindOf == Kind.VEHICLE`。
- `retuneCombatTactics`：骑乘载具时在入口直接返回，不评估近/远程互换。
- `tryEngageMaid`：骑乘载具时 `return MaidWorkTags.isCombatTask(maid) ? 2 : 0`——
  玩家选了什么就保持什么，不再自动选任务；载具火力照常由载具链路输出。
- 还原扫描：`stillOnCombat` 收紧为"当前任务 UID == `ASSIGNED_TAG`"。`retuneCombatTactics`
  每次换战术都会同步 `ASSIGNED_TAG`，所以仍能还原本系统换过的战术；而玩家/第三方在
  战斗中换的模式与 ASSIGNED 不符 → 按"玩家接管"处理，只 `restorePrevMode` +
  `clearMarkers`，绝不 `setTask` 翻回去。飞行作战的 manual-only 例外继续保留。

## 改动文件

- `promaid_src/com/maidsmart/combat/AutoCombatPools.java`：新增 `isRidingVehicle`；
  `retuneCombatTactics` 骑乘闸。
- `promaid_src_neo/com/maidsmart/combat/AutoCombatPools.java`：同上（Neo 版
  `maid.getVehicle()`）。
- `promaid_src/com/maidsmart/combat/AutoCombatTargeting.java`：`tryEngageMaid`
  骑乘时只保持玩家手选任务。
- `promaid_src_neo/com/maidsmart/combat/AutoCombatTargeting.java`：同上。
- `promaid_src/com/maidsmart/combat/AutoCombatSwitch.java`：还原判定收紧到
  `ASSIGNED_TAG`。
- `promaid_src_neo/com/maidsmart/combat/AutoCombatSwitch.java`：同上；原先只针对
  飞行作战的"玩家接管"特判合并进通用判定。

## 验证

- Neo 编译：`EXITCODE=0`；Forge 编译：`EXITCODE=0`。
- 全部 14 个护栏：`FAIL 0 / WARN 0`（`check_comment_style` 首次报新注释 9 行超限，
  压到 8 行内后 PASS）。
- Neo jar：18,194,401 字节，SHA256
  `4CA899D40C2C39631D729A1CDB1C9BBA4277D0A07E3097AFD2D763AF3B201EBA`。
- Forge jar：18,133,950 字节，SHA256
  `A339A85040596500B264FB8FBBE303A8C68A9E0718773B189CA6087841353ACD`。
- 已部署 4 实例，源包/目标包字节数一致（`match=True`）：1.21.1 Neo、1.20.1 Forge、
  龙之冒险整合包、pack1201 专服。部署时游戏已退出，守卫放行；未杀进程、未绕过守卫。
- 专服回归：`test_server.py neoforge1211` PASS、`test_maid_load.py neoforge1211` PASS、
  `test_server.py 1201` PASS、`test_maid_load.py 1201` PASS。

## 边界

- 骑乘闸只认卓越前线载具（`Kind.VEHICLE`）；扫帚/普通坐骑/飞行载具仍走原链路。
- 骑乘期间"不自动参战"只影响任务选择：载具的索敌、开火、装弹、走位不受影响；
  下机后恢复原有自动参战行为。
- 玩家手选的战斗任务不再被还原链翻回战斗前任务；自动系统自己换过的战术仍会
  （`ASSIGNED_TAG` 同步）按安全期还原，原行为不变。
- 若玩家在战斗中先让自动系统选了模式、之后什么都没改，威胁消失后仍会按原逻辑
  还原——这不是"玩家接管"。
- 未新增配置项；不想让载具自动开火仍走既有载具/战斗配置。

## 验收

1. 骑上载具，手动把女仆切成枪械模式（`touhou_little_maid:gun_attack`）。
2. 让敌人（如监守者）贴近到 5 格内并持续战斗 1 分钟以上。
3. 预期：任务始终保持 `gun_attack`，日志不再出现无来源的 `gun_attack -> attack`；
   载具的机枪/炮照常按距离开火。
4. 敌人清掉 10 秒后：女仆不会被打回"跟随/待命"，仍保持玩家选的枪械模式。
5. 下机后：自动参战恢复，原有"参战 → 还原"链路不变。
