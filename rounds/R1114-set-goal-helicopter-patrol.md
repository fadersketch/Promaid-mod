# R1114 /set goal 指令 + 直升机按航迹飞行

## 排班

- **跑道**：C 功能（玩家侧指令入口 + 飞行载具航迹巡逻），无新配置项。
- **开工前读过**：`PatrolNetworking.findEligibleMaid` / `MaidTravelTarget`、`PatrolBook.addTarget`、
  `PatrolChartData.readBook/writeBook`、`PatrolFlight.effective/nextPoint/speedOf`、
  `MaidBroomBehavior` ⑥（航迹优先于拴绳悬停）、`RideBindManager.drive` 的 home 闸与飞行载具分支、
  `MaidMountCompat.isFlyingVehicle/isAircraft`、`MaidAirCombat.hoverAt`、`MaidArmyCommand` 的
  Brigadier 写法与 `/maid_smart` 的 OP 门禁。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | `/set goal <x> <y> <z> [女仆]` | 普通玩家可用，不必 OP | ☑ |
| 1 | 指令写航图目标点 | 手上有航图 → 追加红色目标点并回推界面 | ☑ |
| 2 | 指令直接下飞行目标 | 四种载人状态（或点名自己的女仆）→ 立刻起飞 | ☑ |
| 3 | 航迹对飞行载具生效 | 扫帚任务与"她驾驶直升机"同权 | ☑ |
| 4 | 直升机 home 闸 + 航迹驱动 | 守家不再停住她；无敌人时按航迹点飞 | ☑ |
| 5 | 编译/护栏/打包/部署/测试/轮次文档 | 两树 OK；14 护栏全绿；四套 PASS；4 实例 match=True | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

"1.玩家也可以用指令/set goal +坐标为自己设计一个目标点
2.让乘坐直升机的女仆也可以按轨道飞行"

## 评估

1. **为什么不挂 `/maid_smart`**：那条根命令是 OP 专属（`requires hasPermission(2)`），而这一条是
   普通玩家给自己用的。所以新开一个根命令 `/set goal`；Brigadier 对同名 literal 是合并语义，
   不会覆盖别的模组已有的 `/set` 子命令。
2. **"设计一个目标点"的两层含义都做了**：
   - 手上有巡逻航图 → 把坐标追加成红色目标点（与中键标记落进同一本书、同一套 NBT，自动「目标N」）；
   - 当前处于"女仆载着你"的四种状态之一 → 直接下 `MaidTravelTarget`，走航图「飞过去」同一条链路。
   两者互不依赖：没航图也能飞；没有可执行女仆也能先把点存进书里。
3. **普通玩家的边界**：不带 `[女仆]` 时只认 `findEligibleMaid`（同乘扫帚 / 女仆驾驶直升机 / 武装拴绳
   挂在空袭或扫帚女仆下）；带 `[女仆]` 时必须是她自己的、活着的、同维度的女仆，否则拒绝。
4. **直升机航迹的生效判据**：与扫帚共用 `PatrolFlight.effective`——总开关 + home（工作范围有效）+
   绑了一条闭环且同维度的航迹。新增的只是"她正在驾驶飞行载具"也可以作为第二条入口；
   固定翼排除（它不能悬停、转弯半径大，航迹点追踪语义不成立）。
5. **守家闸冲突**：`RideBindManager` 原先只要 home 就把飞行载具按 `holdHere` 停住，航迹永远到不了。
   现在"驾驶飞行载具 + 航迹有效"这一种情况越过 home 闸，进入正常飞行档；接敌分支仍排在航迹之前，
   所以有敌人时照旧先打、打完再回航迹。
6. **高度口径**：航迹点的 y 就是飞行高度（与扫帚一致），不再叠加 `followAlt`；「飞过去」目标点
   仍沿用上一轮的 `hoverAt`（+跟随高差），两条指令语义分开。

## 改动

### 指令（两树）

- 新增 `command/PatrolGoalCommand.java`，注册根命令 `/set goal <x> <y> <z> [女仆]`：
  - 根节点 `requires(getEntity() instanceof ServerPlayer)`，无 OP 要求；
  - 坐标用 `DoubleArgumentType`，可选 `[maid]` 用实体选择器（可补全）；
  - 手上有航图 → `PatrolBook.addTarget` + `writeBook` + `PatrolNetworking.sendBook`；
  - 目标点已满（32）→ 只飞、不记，并提示先删；
  - 有可执行女仆 → `MaidTravelTarget.set(maid, pos)` 并回「✈ 某某 开始飞往 …」；
  - 点名女仆时校验 owner / 存活 / 同维度；非女仆实体直接拒绝。
- `PatrolNetworking.findEligibleMaid` 由 private 改为 public（指令与界面共用同一份"谁能执行"口径）。
- 两树 `ProMaidExtension.onRegisterCommands` 注册新命令。

### 航迹（两树）

- `PatrolFlight.onFlyingVehicle(maid)` 新增：`maid.getVehicle()` 是飞行载具、不是固定翼、且她是
  0 号驾驶位（`getFirstPassenger()` / `m_146895_()`）。
- `PatrolFlight.effective`：第二道闸从"必须是扫帚任务"放宽为"扫帚任务 **或** 正在驾驶飞行载具"；
  其余（总开关、home、闭环、同维度）不变。`speedOf` 本来就用 `maid.getVehicle()`，直升机的
  前视距离也按真实速度走。
- `RideBindManager.drive`：
  - home 闸前新增 `patrolFlight` 判定（空战开关 + 驾驶飞行载具 + 航迹有效），为真时越过 home 停驻；
  - 飞行载具"无敌人"分支的优先级改为：航图「飞过去」目标 → **航迹点** → 机上有人悬停 → 跟随主人；
  - 航迹点直接交给 `MaidRideKit.feedNavigation`（y 即目标高度），与扫帚同一套 `PatrolFlight`。

## 验证

- 两树编译 `EXITCODE=0`（Forge 494 源文件 / Neo 499 源文件）。
- 14 项护栏 `FAIL 0 / WARN 0`：
  - 新增 `PatrolGoalCommand` 使 Neo 未覆盖基线 183 → 184，已在 `check_arch.py` 留痕；
  - `check_comment_style: PASS`、`check_catch_swallow`、`check_workflow` 等全绿。
- 打包 + 部署 4 实例：`match=True`（Neo 18,223,169 / Forge 18,161,965 字节）。
- 四套专服回归 PASS（1201 / neoforge1211 × server / maid_load）；`/set` 根命令未与测试包内
  其它模组冲突（服务端正常启动）。
- Forge jar：18,161,965 字节，SHA256
  `BF8D204A834D7CBCD581EFBFC332CEA06C52CE808C50090DABB52C674E700900`。
- Neo jar：18,223,169 字节，SHA256
  `2EA7D8E19C3AA237965CE0FDC51B526D086190EDED7C345369810C0A43A8FA52`。

## 边界

- **指令只对玩家可用**：控制台/命令方块会回"只能由玩家执行"（目标维度与"自己的女仆"都需要执行者）。
- **`/set goal` 不要求 OP**，但也不接受"别人的女仆"；`[女仆]` 只做自己名下校验。
- **手上有航图才记点**：没有航图时只下一次性飞行目标，不生成红色标记。
- **点名女仆时不校验载人状态**：目标会挂着，等她进入扫帚/直升机/拴绳链路后才被消费。
- **航迹生效条件与扫帚一致**：总开关 + home（工作范围）+ 闭环航迹 + 同维度；关掉总开关后
  直升机回到原来的守家悬停。
- **只认 0 号驾驶位**：玩家自己开直升机时方向盘归玩家，航迹不会抢控制；固定翼不参与航迹飞行。
- **接敌优先**：飞行档先索敌，有敌人时按原空战逻辑打，没有敌人才按航迹飞。
- 版本号仍为 **1.3.3**，不升号。

## 验收

1. **/set goal 记点**：手持巡逻航图输入 `/set goal 100 64 200`——聊天栏出现「已记为目标点」；
   右键航图进「目标点管理」，列表里多一个「目标N」，世界里出现红色菱形；GUI 里可「飞过去」。
2. **/set goal 直接飞**：与女仆同乘扫帚（或女仆驾驶直升机、或武装拴绳挂着）时执行同一条命令——
   聊天栏出现「✈ … 开始飞往 (100, 64, 200)」，她立刻朝那里飞；到达 4 格内交回平时的跟随/悬停。
3. **点名女仆**：`/set goal 100 64 200 @e[type=touhou_little_maid,limit=1,sort=nearest]`
   只接受自己的女仆；点别人的女仆回「这只女仆不是你的」。
4. **无航图/无载人**：空手执行 → 提示要拿航图或进入四种载人状态，不会误飞。
5. **直升机航迹**：女仆驾驶直升机（玩家坐副驾）、开启 home 并绑定一条闭环航迹——飞机沿航迹点
   逐段飞；把 home 关掉或总开关关掉 → 回到原来的悬停/跟随行为。
6. **接敌优先**：航迹飞行途中附近出现敌人——飞机先按原空战逻辑接敌，敌人消失后回到航迹。
