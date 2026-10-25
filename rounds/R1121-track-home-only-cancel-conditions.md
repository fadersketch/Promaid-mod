# R1121 航迹仅限 home / 直升机航点续飞 / 飞过去取消条件

## 排班

- **跑道**：C 功能修复（承接 R1120 验收反馈；无新开关、无版本号变更）。
- **开工前读过**：R1120 轮次文档、玩家 NeoForge 实例 `logs/latest.log`（04:07 直升机沿轨段）、
  `PatrolFlight`（effective / explain / nextPoint / 前视距离）、`PatrolCurve.Path.aim`、
  `RideBindManager.drive` 飞行分支、`MaidMountCompat`（`FLIGHT_ARRIVE = 3.0` /
  `shouldHardBrake`）、`MaidTravelTarget`、`GunnerTetherManager.tick`、
  `ProMaidExtension.onMaidLeaveLevel`。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 复现「只飞到一个点就停」 | 前视 2.5 格 < 驾驶层到位死区 3.0 格 → 每拍判「到了」不给油 | ☑ |
| 1 | 前视下限 | 驾驶飞行载具时前视 ≥ 6 格（出死区，又不切大弯） | ☑ |
| 2 | 航迹只对 home | effective / explain / routeBound 统一要求 home；跟随模式走常链路 | ☑ |
| 3 | 飞过去取消 | 收放魂符 / 死亡 / 换工作模式 / 跟随⇄home 切换都会清目标并记日志 | ☑ |
| 4 | 守家解绳 | 绑定后被调成 home → 立即 `解除(转为守家)` | ☑ |
| 5 | 两树编译/14 护栏/双 jar/部署/四测试/轮次文档 | 全绿 | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者，见文末「验收」。

## 需求 / 来源

> 「顺带一提，收放魂符也可以结束飞过去这个状态。或者让女仆的工作模式/跟随与home切换发生改变都会
> 终止飞过去的任务。武装栓绳栓住的女仆若被调为home，也会立即解开栓绳。最新一轮的测试有进步，
> 女仆开始尝试沿着轨道飞行了。但是它只飞到了一个点，然后就停在点上不动了。然后这个时候把女仆
> 从home模式关掉女仆也仍然不选择跟随。我的想法是这样的。所谓的轨道只会对home模式的女仆产生影响，
> 跟随模式下这个轨道完全没用。走常链络。口径跟巡逻航图下扫帚模式的判定类似。」

## 评估

### ① 只飞到一个点就停：胡萝卜落在驾驶死区里

```
MaidMountCompat.FLIGHT_ARRIVE = 3.0        // 驾驶层：距目标 ≤3 格 → 松油/硬刹
PatrolFlight.LOOKAHEAD_BASE   = 2.5        // 纯追踪：静止时目标只领先 2.5 格
```

- 她一旦停在某个航迹点上，下一拍 `nextPoint` 的胡萝卜仍在 2.5 格外，正好落在
  `FLIGHT_ARRIVE` 的 3 格死区里 → 驾驶层判「已到位」→ 不给油 → 永远停在那一点。
- 扫帚没有这个问题：驾驶层不是一个 3 格松油死区。所以这是飞车载具专属的错位。

### ② home 关掉后仍不跟随

- `PatrolFlight.effective` 在 R1118 对「驾驶飞行载具」放行了 home 检查；`RideBindManager`
  的 `routeBound` 又不看 home，只要绑了航迹就悬停等补完。于是关掉 home 后：
  航迹仍生效、或绑着未闭环航迹时强制悬停——两条都不会回落到跟随主人。
- 玩家的口径是：航迹只对 home 女仆生效，跟随模式完全不用这条轨，走常链路。

### ③ 取消条件

- 「飞过去」目标只按 TTL / 到点 / 跨维度清理；魂符收回、换工作模式、home 切换都没有入口去清。
  她再被放出来或切回跟随后，会继续朝旧目标飞。

## 改动

### ① 飞车载具前视（两树 `PatrolFlight`）

- 新增 `VEHICLE_MIN_LOOKAHEAD = 6.0`；`onFlyingVehicle(maid)` 时前视取
  `max(前视, 6.0)`，且这一步放在「偏离航迹收短前视」之后，保证无论哪条分支都大于 3 格。
- 效果：胡萝卜始终在驾驶死区外，直升机会沿着航迹一段一段往前追，而不是停在第一个点上。

### ② 航迹只对 home（两树 `PatrolFlight` + `RideBindManager`）

- `PatrolFlight.effective` / `explain`：`!maid.isHomeModeEnable()` 一律不生效（扫帚与飞行载具同权）。
- `RideBindManager`：`routeBound = maid.isHomeModeEnable() && boundRoute != null`。
  没开 home → 当作没绑，走常链路（飞过去 → 机上有人悬停 → 跟随主人）。
- 关掉 home 的那些拍：`patrolFlight=false`、`routeBound=false`，`[航迹飞行]` 会显示
  `说明=没开 home 分支=跟随主人`，与真实驱动一致。

### ③ 飞过去取消（两树 `MaidTravelTarget` + `ProMaidExtension`）

- `Req` 记下单时的 home 状态与任务 id；每次 `aim()` 比对：
  - home 状态变了 → `cancel(… "切到 home 模式" / "切到跟随模式")`；
  - 任务 id 变了 → `cancel(… "切换工作模式（旧 → 新）")`。
  放在 `aim()` 里是因为三条飞行链路每拍都会调用它，命令、面板、工具、排班、自动战斗等
  所有改法都能覆盖到，不需要在每个入口各写一遍。
- `ProMaidExtension.onMaidLeaveLevel`：移除原因 `DISCARDED`（魂符收回）/`KILLED`（死亡）
  → `cancel(… "魂符收回或死亡")`。区块卸载不清，重新加载后继续飞。
- 取消统一日志：`[飞过去] … 本次飞行取消（原因）`。

### ④ 守家解绳（两树 `GunnerTetherManager.tick`）

- 每 2 tick 的校验里，模式去抖之前先判 `maid.isHomeModeEnable()`：
  是 → `deferredDetach.put(maid, "转为守家")`，本轮循环结束后正常 `detach`
  （金色标记、绳子、相位包一并清）。

## 验证

- 两树编译 `EXITCODE=0`。
- 14 项护栏 `FAIL 0 / WARN 0`。
- 打包 + 部署 4 实例 `match=True`。
- 四套专服回归 PASS：`test_server 1201`、`test_server neoforge1211`、
  `test_maid_load 1201`、`test_maid_load neoforge1211`。
- Forge jar：18,189,440 字节，SHA256
  `E6D5F48719E2260B49AA24FD01426BE441B36C720FABFA188CBD501DE5ECA4ED`。
- Neo jar：18,250,647 字节，SHA256
  `A5701B220D49B51037B1D57E3A5D5B2A2D9278B803460330BB4444E91561E7C9`。
- **反证**：
  - 前视 6 > 3，`shouldHardBrake` / `parked` 都不可能在她静止时成立；她会持续得到新的航点。
  - `routeBound` 与 `effective` 都以 home 为前置，home 关闭后代码里不存在任何航迹/悬停分支可命中。
  - home/任务变化在 `aim()` 内直接清表，下一拍 `travel` 即为 null，行为不会继续认领这趟任务。

## 边界

- home 关掉后，身上那条航迹绑定仍然保留（数据不丢）；重新打开 home 就按原轨继续巡逻。
- 「飞过去」被取消后不会自动改回原任务，也不会把玩家从座位上放下——只清这一趟飞行目标。
- 魂符收回时取消；区块卸载不取消（重新加载继续飞），与"收放魂符结束任务"的语义区分开。
- 版本号仍为 **1.3.3**，同号重打；发布素材等验收后再写。

## 验收

1. **航点续飞**：home + 闭环航迹，直升机应在每个航点后继续朝下一个点飞，不再停在第一个点；
   `[模组坐骑]` 的 `距目标` 应周期性变化（被新航点拉开），而不是长期为 0。
2. **关闭 home 即恢复跟随**：飞行途中关掉 home，`[航迹飞行]` 应出现 `说明=没开 home`、
   `分支=跟随主人`（玩家不在机上时），机身转向主人；重新打开 home 后应回到 `分支=航迹`。
3. **飞过去取消**：飞行途中做任一操作——收魂符、换工作模式、切 home/跟随——
   `[飞过去]` 应出现 `本次飞行取消（原因）`，随后不再朝旧目标飞。
4. **守家解绳**：拴绳绑住女仆后把她调成 home，应在 2 tick 内看到
   `[武装拴绳] 解除(转为守家)`，金边标记与绳子一起消失。
