# R1125 十条实测反馈（采集 / 索敌 / 飞过去）

## 排班

- **跑道**：C 功能修复（承接玩家最新一轮十条反馈；无版本号变更，1.3.3 同号重打）。
- **开工前读过**：`MaidSmartConfig`（mine / wood / misc 段）、`MaidPickupPriorityMixin`、
  `MaidToolAutoEquip`、`MaidWorkBehavior`（stallWatchdog / antiWaterStuck）、
  `MaidMineBehavior` / `MaidWoodBehavior`（tick 入口）、`FlightTargeting`、
  `RideBindManager`（飞行载具分支）、`MaidBroomBehavior` / `MaidBroomDrive`、
  `EntityBroomMaidTravelMixin`、`MaidFlightFollowBehavior`、
  `PatrolNetworking.flyBlockedReason`、`ProMaidMod.runConfigMigration`。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 挖矿/伐木自动收集默认开 | 两树 define 默认 true，老 toml 一次性迁移 | ☑ |
| 2 | 伐木工作期间禁止拾取 + 配置项 | `wood.noPickup`（默认开）接入拾取 mixin | ☑ |
| 3 | 飞行载具/扫帚优先主人的目标 | `FlightTargeting` 主人仇人优先；锁上后按 HOLD_RANGE 维持 | ☑ |
| 4 | 斧子识别含副手 | 找树判定与 `ensureAnyAxe` 都认副手，并换回主手 | ☑ |
| 5 | 挖矿/伐木 60 秒硬超时 | `mine.hardTimeout` / `wood.hardTimeout`（默认 60 秒，0=关） | ☑ |
| 6 | 水流两格横跳脱困 | `antiWaterStuck` 改 3 格锚点容差，计时不再被重置 | ☑ |
| 7 | 飞过去期间不被锁敌打断 | travel 时索敌器让位；载具/扫帚战斗分支让位 | ☑ |
| 8 | 玩家+女仆同乘扫帚可飞过去 | 玩家驾驶扫帚时由女仆接管驱动 | ☑ |
| 9 | 玩家按移动键取消飞过去 | 读服务端同步的 WASD 输入，取消目标并交还方向盘 | ☑ |
| 10 | 鞘翅飞过去竖直振荡卡住 | 水平 2 秒无位移 → 跃出/抬高；泡水先挪干燥处 | ☑ |
| 11 | 两树编译/14 护栏/双 jar/部署/四测试/轮次文档 | 全绿 | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者，见文末「验收」。

## 需求 / 来源

本轮一次性处理玩家列出的十条反馈：

1. 伐木和挖矿的自动收集为默认开。
2. 伐木也要有挖矿同款「工作期间禁止拾取掉落物」，并给配置项。
3. 女仆开载具/扫帚飞行时，优先锁主人正在攻击、或正在攻击主人的目标；不转火（除非对方离开锁敌范围）。
4. 斧子识别要看副手；玩家手动给她的斧不能被报成「没有斧头」。
5. 挖矿/伐木加 60 秒硬阀（可配置）：同一目标连续 60 秒没解决，哪怕一直在动也放弃，效果与语音同「这块我够不到」。
6. 被水流推回、在两个方块间反复横跳时，原有的「水卡住 → 传送干燥处」保护不触发。
7. 飞过去期间不应被锁敌/战斗打断（现在会飞着飞着去打地上的怪）。
8. 玩家和女仆一起骑扫帚时不能飞过去；目前只有武装拴绳 + 扫帚模式能执行。
9. 原版 TLM 玩家+女仆同乘扫帚也要能飞过去；玩家按移动键则取消该任务。
10. 鞘翅带主人飞过去时概率卡死：12:03 日志中竖直上下动、水平速度 0.00。

## 评估

- 日志 `12:02:23` 起的「飞过去」：水平速度持续 `0.00`，竖直在 `+0.33 / -0.18 / -0.15`
  间振荡，距离每秒只掉约 0.2 格，而隐形烟花仍在每 1.5 秒补一枚。这与
  `travelInWater` 顶掉滑翔分支、或贴地反复起跳的形态一致：推进力被水/地形吃掉，
  原有「无进展」判据只对自动跟随生效，对显式 travel 不生效。
- 飞行载具与扫帚的接敌分支都排在 travel 之前；`FlightTargeting` 也未区分 travel，
  所以玩家点了目标点后，索敌器仍会把最近敌人写进 brain。
- 扫帚的 `EntityBroom.travel` 在「第一乘客是玩家」时整段让给 TLM；女仆行为也在
  `drivenByPlayer` 时只开火。于是玩家同乘时没有任何一方按 travel 目标驱动。
- 斧子判定只扫主手 + 背包，副手是盲区。

## 改动

### 采集与工作（两树）

- `MINE_AUTO_COLLECT` / `WOOD_AUTO_COLLECT` 默认值 false → true。
  新增一次性迁移标记 `AUTO_COLLECT_ON_MIGRATED`（放在 `promaid-internal.toml`）：
  老存档里仍是 false 的自动改成 true，标记落盘后玩家手动关掉不会再被打开。
  `persistConfigQuietly` 同时保存 `INTERNAL_SPEC`，保证标记真的持久化。
- 新配置 `wood.noPickup`（默认 true）：`MaidPickupPriorityMixin` 在伐木期间
  `checkExtraStartConditions` 直接返回 false；总开关 `misc.pickupPriority` 打开时
  挖矿与伐木一起让位。
- 新配置 `mine.hardTimeout` / `wood.hardTimeout`（秒，默认 60，0=关）：
  `MaidWorkBehavior.hardWatchdog` 只按「同一目标锁了多久」计时，不看有没有挪窝、
  也不看进度有没有变。到点走与 `stallWatchdog` 完全相同的弃目标收尾
  （进 30 秒短排、清目标/连锁、停导航、还走位权、气泡「这块我够不到，先换个地方找找～」），
  连弃 3 次仍进入换一片的逻辑。
- `antiWaterStuck`：脚下格完全相等 → 3 格水平 / 2 格垂直的锚点容差。
  水流把她推回两格之间来回横跳时，计时不再每拍重置。

### 工具识别（两树）

- `MaidToolAutoEquip.canHarvestWoodWithHandOrBackpack`：主手之后补副手。
- `ensureAnyAxe`：主手不是斧、副手是斧（且未快坏）→ 与主手互换后直接返回 true；
  不再对着副手那把斧播报「我没有斧头」。

### 索敌 / 飞过去（两树）

- `FlightTargeting.resolve`：
  - travel 期间整段让位：清本类锁定与 brain 的 `ATTACK_TARGET` / `LOOK_TARGET`，
    返回 null；
  - 新增 `ownerThreat`：主人 10 秒内正在攻击的目标优先，其次 10 秒内攻击过主人的目标；
    通过既有 `keepable`（存活 / 同维度 / 可攻击 / 活动范围 / HOLD_RANGE / 友军闸）后
    先锁它。锁上之后由原「维持锁定」分支继续用，旁边出现更近的怪不转火。
- `RideBindManager`：索敌器因 travel 返回 null 时，不再用 `liveTargetOf` 回落；
  飞行载具与地面载具两处回落都加 travel 闸。
- `MaidBroomBehavior`：本拍有 travel 目标时，先于「玩家驾驶」和「接敌」，
  只调用 `MaidBroomDrive.steerTo(target)`；缺远程武器也不再因此下鞍。
- `EntityBroomMaidTravelMixin`：
  - 第一乘客是玩家、第二乘客是女仆、且她有 travel 目标时，服务端接管扫帚驱动；
    任务不是 `maid_smart:broom` 时由 mixin 直接调一次 `steerTo` 生成推力；
  - 接管前读驾驶玩家的 `xxa` / `zza`（服务端经 `setPlayerInput` 同步）：
    一旦非零，`MaidTravelTarget.cancel(..., "玩家动了移动键，把扫帚交还玩家")` 并交还原版。
- `PatrolNetworking.flyBlockedReason`：玩家驾驶扫帚 + 女仆同乘时放行
  （不再要求她的任务必须是扫帚模式）。
- `MaidFlightFollowBehavior`：travel 期间新增水平停滞监护
  （`TRAVEL_STALL_TICKS = 40`）：
  - 泡在水里 → 先在 ±4 格、-2..+4 高度找干燥落脚点搬过去；
  - 水平 2 秒位移不足 0.5 格 → 朝目标强给一记水平速度（竖直至少 0.35），
    若正踩在地上再把头顶第一个「两格空气」位置让出来，打散贴地起跳循环；
  - 每次动作写一行 `[飞过去] … 水平 2 秒没有位移 … 强制朝目标跃出/抬高脱困`，
    泡水那条写「先挪到附近干燥落脚点再飞」。

## 验证

- 两树编译 `EXITCODE=0`。
- 14 项护栏 `FAIL 0 / WARN 0`（含 `check_workflow`）。
- 打包 + 部署 4 实例 `match=True`。
- 四套专服回归 PASS：`test_server 1201`、`test_server neoforge1211`、
  `test_maid_load 1201`、`test_maid_load neoforge1211`。
- 迁移实测：Forge 测试服启动后 `mine.autoCollect` / `wood.autoCollect` 均为 true，
  `promaid-internal.toml` 写入 `autoCollectMigrated = true`，
  日志出现「配置迁移 / 自动收集默认开启」。
- Forge jar：18,195,390 字节，SHA256
  `3E00FBA19AAA31DED53D6578B9BE0F9BFEB3B95B2F6C92B68321A4ABB489680E`。
- Neo jar：18,256,533 字节，SHA256
  `D53B9411E1ECDF3DA29ED8B0AF19EFDFA44FC956E1B56B70609A9897FEAC4CB7`。
- **反证**：
  - 硬超时只在 `targetPos` 不变时累计，目标一换立即重置；不与 3 秒「原地不动」
    判据混用，仍在挖的目标不会被误判为停手。
  - ownerThreat 走 `LiveThreat`（含 5 秒正伤害台账与主人双向交手），
    不会因为主人很久以前打过一只中立生物就永久当成目标。
  - 扫帚接管只在「有 travel 目标 + 玩家没按移动键」时发生；取消目标的下一拍就回到
    TLM 玩家驾驶，不存在两边同时写速度。
  - travel 停滞监护只在 travel 生效；自动跟随仍走原 `PROGRESS_TIMEOUT` 收手。

## 边界

- 自动收集迁移只做一次：标记落盘后，玩家把 `autoCollect` 关掉会一直保持关闭。
- `wood.noPickup` 只管伐木；挖矿仍由 `misc.pickupPriority` 管。两者任一为真都会让
  对应工作的拾取任务不启动。
- 硬超时的「放弃」与手动掘进共享 `RECENT_DISCARD` 30 秒短排，因此放弃后不会立刻重选同一块。
- 同乘扫帚的「玩家移动键」只认 WASD 的 x/z 输入；跳跃/潜行不取消 travel。
- 鞘翅脱困是最后手段：只在水平 2 秒无位移时触发，正常巡航/到点减速不受影响。
- 玩家与女仆同乘扫帚时，服务端接管期间客户端不写位移（与服务端权威位置同步的既有口径一致）。
- 版本号仍为 **1.3.3**，同号重打；发布素材等验收后再写。

## 验收

1. 首次进游戏 / 启动服务器后：`config/promaid-common.toml` 的
   `[mine] autoCollect` 与 `[wood] autoCollect` 变为 true，日志有「自动收集默认开启」。
   手动关掉后再重启，仍保持 false。
2. 伐木时地上有掉落物：她不跳下树/离开目标去捡；把 `wood.noPickup` 关掉后恢复拾取。
3. 骑扫帚/飞行载具时主人在打某只怪：她优先打那只；旁边刷出更近的怪不转火，
   直到当前目标死亡或离开维持范围。
4. 玩家把斧子放在她副手：砍树不再播报「我没有斧头」，且会把副手斧换到主手使用。
5. 锁住一块矿/一棵树 60 秒仍未解决：放弃并播报「这块我够不到，先换个地方找找～」；
   把 `hardTimeout` 设为 0 后不再触发。
6. 水流把她推在两格之间来回横跳：约 2 秒后自动挪到附近干燥落脚点。
7. 点「飞过去」后路边有怪：她保持飞向目标点，不拐去接敌。
8. 玩家驾驶扫帚、女仆坐后座，点「飞过去」：扫帚由女仆接管飞向目标。
9. 接上一条，飞行中玩家按 W/A/S/D：目标立即取消，扫帚回到玩家驾驶。
10. 鞘翅飞过去途中水平速度长时间为 0：2 秒后强制跃出/抬高，日志出现脱困行；
    若泡在水里会先挪到干燥处。
