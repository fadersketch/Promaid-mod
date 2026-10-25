# R1118 飞过去真正起飞 / 任意任务可绑 / 直升机绑轨即巡逻 / 准星描边

## 排班

- **跑道**：C 功能修复（承接 R1117 验收反馈；无新开关、无版本号变更）。
- **开工前读过**：R1117 轮次文档、玩家 NeoForge 实例 `logs/promaid.log`（02:42:40–02:44:09）、
  `MaidFlightFollowBehavior`（canUse / `m_6737_` canContinue / `m_6732_` stop / `aimOf`）、
  `GunnerTetherManager` 的 `tryBind` 与 `onMaidTick`、`PatrolNetworking.findEligibleMaid` /
  `FlyToTargetPacket` / `missingFor`、`PatrolFlight.effective/nextPoint`、`RideBindManager.drive`
  的 home 闸与无敌人分支、`MaidAimGlowClient` + `MaidAimGlowMixin`、Forge/Neo
  `Entity.pick` 与 `ProjectileUtil` 的 javap 签名。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 复现「飞过去」能点但不起飞 | 日志证明起飞后**同一秒**结束；canContinue 仍在总开关/威胁/接敌处收手 | ☑ |
| 1 | 显式命令贯穿 canContinue | `!cfg()`、威胁、接敌三闸都带 `!travel`；结束行打印原因 | ☑ |
| 2 | 拴绳可绑任意任务模式 | 去掉"扫帚/空袭/骑载具"门禁；home 仍在绑定入口拒绝 | ☑ |
| 3 | home 女仆「飞过去」只读 | 资格判定排除 home；服务端不再给 home 女仆写 travel | ☑ |
| 4 | 直升机绑闭环航迹即沿轨 | 驾驶飞行载具时 `effective` 不再要求 home 标志位 | ☑ |
| 5 | 准星描边（傀儡装配同款光标） | 客户端改用实体射线，不再用只返回方块的 `Entity.pick` | ☑ |
| 6 | 两树编译/14 护栏/双 jar/部署/四测试/轮次文档 | 全绿 | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者，见文末「验收」。

## 需求 / 来源

> 「测试情景：1.把符合飞行条件的空袭女仆绑定，在航图里选飞过去，系统也提示了，但女仆就是不飞
> 2.让女仆坐上直升机起飞，开home，绑轨道，但女仆不按轨道飞，还是追着主人
> 3.之前提到的傀儡装配同款光标也未能渲染
> 更改方面，让女仆可以在任何任务模式下被绑定，不再局限于空袭模式。但home女仆还是绑不上」

## 评估

### ① 飞过去：canUse 放行了，canContinue 又把她收掉

- R1117 只改了 `canUse`（`if (!cfg() && !travel) return false;`）。行为真正每 tick 跑的是
  `canContinue`（`m_6737_` / `canStillUse`），它第一句仍是 **`if (!cfg()) return false;`**。
- 于是「飞行跟随」总开关关着、但玩家点了「飞过去」时：`canUse` 放行 → 打出起飞日志 →
  **下一拍 `canContinue` 因总开关为假 → 立刻 stop**，日志里就是同一秒的
  `起飞 → 结束（… 她已落地）`。
- 同一处还有两道自动跟随的软闸没有给显式命令让路：`threatNearby`（刷怪笼/怪堆旁必中）与
  `ownFlightBusy`（空袭链路抢到目标）。玩家在怪堆边点「飞过去」时，她连地面都离不开。
- 旧结束日志不带原因，"为什么刚起飞就结束"在日志里完全看不出来。

### ② 直升机绑轨仍追主人：`effective` 还要求 home 标志位

- `PatrolFlight.effective` 的判据是 "home 开 + 闭环航迹 + 同维度"。玩家实测那台直升机
  `isHomeModeEnable()` 读出来是 **false**（TLM 面板的 home 与存档标志在这一侧没同步上），
  于是 `patrolFlight == false` → 不穿过 home 闸、也不进航迹分支 → 无敌人时按"跟随主人"驱动。
- 绑定航迹本身就是玩家的显式命令；驾驶飞行载具时不该再被 home 标志位卡住。

### ③ 准星描边从未亮过：`Entity.pick` 只做方块裁剪

- 客户端每 tick 用 `cam.pick(512, partial, false)` 找"准星下的女仆"，再把结果强转
  `EntityHitResult`。反编译/签名确认：`Entity.pick` 内部只调 `level.clip(...)`，**返回值永远
  是 `BlockHitResult`**，不可能出现实体命中。
- 所以 `found` 恒为 null，`MaidAimGlowMixin` 的 `isCurrentlyGlowing` 覆写从来没有目标。

### ④ 拴绳被任务模式卡死

- `GunnerTetherManager.tryBind` 的最后一闸是
  `if (!onMount && !broomMode && !ridingBroom && !isFlightTask(maid)) deny("扫帚、空袭或骑着载具时再抓绳子吧～")`。
- 也就是只有空袭/扫帚/骑载具三种任务能绑；挖矿、战斗、烹饪等任务即使带着鞘翅+烟花也绑不上，
  航图自然也不会出现「飞过去」。home 女仆又因为这条门禁被一起挡住，但需求要的是
  "任意任务可绑、home 仍然不可绑"——这两件事此前挤在同一道闸里。

## 改动

### ① 飞过去（两树 `MaidFlightFollowBehavior`）

- `canContinue` 顶部先取 `travel`：`if (!cfg() && !travel) return false;`。
- `threatNearby`、`ownFlightBusy` 都加 `!travel`；超时判定同样不适用于显式命令。
- home 判据改为**无条件**中断（`maid.isHomeModeEnable()`），不再有 "home + 命令" 的例外。
- 新增 `STOP_WHY` 表：各收手分支写入可读原因，结束日志追加 `原因=…；`；`forget`/`clearAll` 清理。
- 起手/结束日志的坐标档措辞改为同时认 `MaidTravelTarget`（旧版只认 `/elytra_goto` 的 GOTO 表，
  航图「飞过去」也被写成"主人飞远了"）。

### ② 拴绳绑定（两树 `GunnerTetherManager`）

- 绑定入口新增 home 拒绝：`if (maid.isHomeModeEnable()) deny("守家的女仆不抓绳子～…")`，
  放在 `releaseOtherLinks` 之前（被拒时不许拆掉已有链路）。
- 牵绳档判据去掉 `isFlightTask`：`leash = !broomMode && !ridingBroom && mountOf(maid)==null
  && !flyingNow(maid)`。任意任务的落地女仆都能先绑成牵绳档。
- 删除"扫帚、空袭或骑着载具时再抓绳子吧～"这道任务门禁。
- `onMaidTick` 的绳子拉力在 `MaidTravelTarget` 有效时直接返回：显式飞行任务期间不与飞行链路
  每 tick 写入的速度打架。

### ③ 飞过去资格（两树 `PatrolNetworking`）

- `findEligibleMaid` 的扫帚/直升机/tether 三支全部排除 `isHomeModeEnable()`。
- tether 支去掉 `modeKind` 三选一：只要是她自己的、被这位玩家拴住的、非 home 的女仆就返回。
- `missingFor` 同步：驾驶飞行载具时不再提示"开启 home"。

### ④ 直升机航迹（两树 `PatrolFlight.effective`、`RideBindManager`）

- home 判据改成 `!maid.isHomeModeEnable() && !onFlyingVehicle(maid)`：驾驶飞行载具时
  绑定闭环航迹即生效；扫帚仍按原口径要求 home（避免未开 home 的普通扫帚突然离开主人）。
- 无敌人分支新增 5 秒限频的 `[航迹飞行]` 留痕：`home=… 本拍航迹点=true/false`。
  `本拍航迹点=false` 时会走原地悬停，验收能一眼区分"在沿轨"与"在跟随主人"。

### ⑤ 准星描边（两树 `MaidAimGlowClient`）

- Forge：`Entity.pick` → `ProjectileUtil.m_37304_(level, player, eye, end, box, pred)`。
- Neo：`Entity.pick` → `ProjectileUtil.getEntityHitResult(level, player, eye, end, box, pred)`。
- 眼睛/视线向量仍取相机实体，命中过滤只认 `EntityMaid`。

### ⑥ 诊断（两树 `MaidTravelTarget`）

- `set` 落一行 `[飞过去] 接受目标 (x,y,z) 维度=…`；到点清除时落
  `[飞过去] 已到达（N 格）→ 清除目标`。用于区分"没下单"与"下单即到点"。

## 验证

- 两树编译 `EXITCODE=0`。
- 14 项护栏 `FAIL 0 / WARN 0`。
- 打包 + 部署 4 实例 `match=True`。
- 四套专服回归 PASS：`test_server 1201`、`test_server neoforge1211`、
  `test_maid_load 1201`、`test_maid_load neoforge1211`。
- Forge jar：18,185,481 字节，SHA256
  `AA91664BD200F18C6BC131454F7761DD2CBE76FF8ED2BBE0A1C4D62A63CA3FE1`。
- Neo jar：18,246,752 字节，SHA256
  `6D40570C6BB7DD4B0B7DD57A9589A0A08667333995524A6C8FAB1D6EB2982F06`。
- **反证**：
  - ① `canUse` 与 `canContinue` 现在用同一份 `travel` 判据；`threatNearby`/`ownFlightBusy`
    两处都带 `!travel`。结束日志新增 `原因=`，下一轮验收能直接读到是哪一闸。
  - ② `effective` 对 `onFlyingVehicle` 提前放行，`RideBindManager` 的 `patrolFlight`
    随之变真、穿过 home 闸，无敌人分支取 `PatrolFlight.nextPoint`。
  - ③ `Entity.pick` 签名只返回 `HitResult` 且内部只做 `level.clip`；新射线用
    `ProjectileUtil` 的实体路径（Forge `m_37304_` / Neo `getEntityHitResult` 均已 javap 核对）。
  - ④ 绑定入口的 home 拒绝位于 `releaseOtherLinks` 之前；牵绳档判据不再读任务。

## 边界

- 任意任务可绑只改变"能不能绑"；她能不能飞仍由鞘翅 + 推进剂（烟花/羽扇/位移法术/激流三叉戟/
  自推鞘翅）决定，缺料时飞行链路照旧在 `飞行跟随跳过` 里报原因。
- home 女仆在绑定入口与服务端资格判定两处都被拒；UI 里不出现「飞过去」，该页仍可查看目标点。
- 直升机免 home 只针对"驾驶飞行载具"；扫帚、固定翼的既有口径不变（固定翼仍走降落链路）。
- 准星描边仍是纯客户端、零网络；只改射线来源，不写实体发光标志位。
- 版本号仍为 **1.3.3**，同号重打；发布素材等验收后再写。

## 验收

1. **飞过去（跟随女仆）**：武装拴绳绑住地面上的空袭女仆（跟随模式），航图选远处目标点
   按「飞过去」：她应真的起飞并飞向该点；日志应出现 `[飞过去] 接受目标` 与
   `飞向指定坐标（N 格）`，不再出现同一秒的 `结束（… 原因=…）`。
2. **任意任务可绑**：把女仆切到挖矿/战斗/烹饪等非空袭任务，手持武装拴绳右击 → 应绑成牵绳档
   （气泡「先跟着我走…」），航图里出现「飞过去」。
3. **home 只读**：对 home 女仆右击拴绳 → 应回「守家的女仆不抓绳子」；航图里不出现「飞过去」。
4. **直升机航迹**：女仆驾驶直升机升空、绑定一条闭环航迹：应沿航迹飞，不再追主人；
   日志每 5 秒出现 `[航迹飞行] … 本拍航迹点=true`；把航迹解绑后回到"无航迹"的悬停/跟随口径。
5. **准星光标**：手持武装拴绳或指标石，准星指向女仆：应出现金色/发光描边（与傀儡装配的
   命令手杖同款），移开准星 0.5 秒内消失。
