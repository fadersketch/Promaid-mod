# R1126 六条实测反馈（空袭大运 / 拴绳提示 / 同乘扫帚 / 直升机巡航 / 透明化）

## 排班

- **跑道**：C 功能修复（承接玩家最新一轮六条反馈；无版本号变更，1.3.3 同号重打）。
- **开工前读过**：`DaYunBauble` / `DaYunBreaker` / `DaYunSmash`、`MaidFlightKit` /
  `MaidFlightCombatBehavior`、`GunnerTetherManager`、`EntityBroomMaidTravelMixin`、
  `MaidBroomBehavior` / `MaidBroomDrive` / `MaidBroomKit`、`MaidTravelTarget`、
  `MaidMountCompat.flightBrake`（三档调用点）、`MaidGhostRender`。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 空袭状态下大运饰品失效 | 空袭轮内（含收翅猛击/激流）仍在空且未落地就继续开路/撞击 | ☑ |
| 2 | 守家女仆绑不上改为系统消息 | 改用 `denyTo`，只发玩家 `sendSystemMessage`，不再走气泡 | ☑ |
| 3 | 原版玩家+女仆同乘扫帚可飞过去 | 覆写 `getControllingPassenger`，服务端真正进入 `travel` 并被驱动 | ☑ |
| 4 | 直升机沿航路/目标点飞行不再周期性停顿 | 巡航段跳过 0.5 秒急停脉冲；到位硬刹/接敌脉冲保留 | ☑ |
| 5 | 玩家坐上运行中的扫帚不立刻接管 | 女仆继续驾驶；按 WASD 过 10 tick 防抖后才交出方向盘 | ☑ |
| 6 | 正常骑上扫帚也触发女仆/扫帚半透明 | 渲染判据不再要求武装拴绳或主人 UUID | ☑ |
| 7 | 两树编译/14 护栏/双 jar/部署/四测试/轮次文档 | 全绿 | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者，见文末「验收」。

## 需求 / 来源

玩家本轮六条反馈：

1. 空袭状态下女仆大运饰品失效。
2. 「守家女仆绑不上」这一条应为系统消息，而不是女仆说出来的话。
3. 玩家和女仆一起坐在扫帚上（原版 TLM 骑扫帚方式）仍无法触发飞过去：系统消息提示已允许，
   但玩家仍在自主操纵，扫帚没有自己飞行。
4. 直升机沿航路/向目标点飞行时总是飞一点就突然停顿一下，观感不好。
5. 玩家坐在女仆正在运行的扫帚上时不应立刻接管移动；只有按 WASD 才接管。
   在按下之前女仆按原路线/运动逻辑继续飞。原版骑扫帚不变。
6. 玩家通过正常方式骑上扫帚时，女仆和扫帚的透明化不生效；目前只有武装拴绳才触发半透明。

## 评估

- **大运**：`isAirRaidTask` 旧版只认任务 UID（`flight_combat` / `flight_ranged`）。空袭是
  一轮轮状态机，轮内任务会被自动战斗/恢复链短暂改写；且收翅猛击/激流相位会主动
  `setGliding(false)`，`DaYunBreaker` / `DaYunSmash` 原来的 `isFallFlying()` 闸随即整段失效。
- **拴绳提示**：守家拒绝调的是 `deny()`（女仆气泡），语义上是「任务不能绑」的系统规则，
  不该由女仆说出口。
- **同乘扫帚（关键）**：1.21.1 的 `LivingEntity.travelRidden` 只在
  `isControlledByLocalInstance()` 为真时调 `travel`；服务端上玩家驾驶时该值为假，
  于是服务端一个字节都不动——旧 mixin 挂在 `travel` 上，服务端根本不进，位移只发生在客户端。
  另外 `Entity.addPassenger` 会把 Player 插到第 0 位，正常 API 换不出 `[Maid, Player]`。
- **直升机停顿**：`flightBrake` 的 0.5 秒周期脉冲把水平速度乘 0.25，原本是给悬停防漂移的；
  巡航段每 10 拍触发一次，表现为「飞一点停一下」。
- **扫帚接管**：旧逻辑只要玩家是控制乘客就整段让位。玩家一坐下就抢走方向盘，
  女仆正在跑的航路/巡逻立即中断。
- **透明化**：`isGhostTarget` / `isGhostBroom` 原先只认「武装拴绳链路」或主人 UUID；
  客户端 `getOwner()` 在部分同步时序下为 null，正常骑乘永远进不了半透明。

## 改动

### 大运（两树）

- `DaYunBauble.isAirRaidTask`：任务 UID 之外并入
  `MaidFlightCombatBehavior.isEngaged`（起跳/爬升/猛击/等待再点火/激流旋转/长矛空袭）。
- 新增 `airRaidAirborne = !onGround() && MaidFlightKit.isFlightAirborne(maid)`；
  为真时调用新入口 `DaYunBreaker.sweepAheadAirRaid(...)` /
  `DaYunSmash.impactAirRaid(...)`，滑翔位为假也继续开路与撞击。
- `DaYunBreaker.sweep` / `DaYunSmash.impact` 增加 `allowNonGliding` 私有参数；
  原公开入口仍传 false，玩家侧行为一字不改。

### 拴绳提示（两树）

- `GunnerTetherManager` 守家分支由 `deny(maid, msg)` 改为
  `denyTo(player, maid, msg)`：写日志后用玩家系统消息通道发送
  （Forge `m_213846_` / Neo `sendSystemMessage`），带 1 秒去重。

### 同乘扫帚驱动（两树）

- `MaidBroomDrive.maidShouldPilot(broom, maid)` 成为唯一驾驶权判据。
- `EntityBroomMaidTravelMixin` 新增 `getControllingPassenger` 的 HEAD 注入：
  该女仆飞时返回 null，原版随即走 `travel` 分支，服务端才真正执行我们写的位移。
- `travel` 注入补齐 `[Player, Maid]` 形态：
  - 有 `MaidTravelTarget`：非扫帚任务也由 mixin 调一次 `steerTo` 生成推力；
  - 扫帚任务：沿用 `MaidBroomDrive` 每拍写的推进意图；
  - 服务端 `noteTakeover + drive + cancel`，客户端让位等权威位置同步。

### 直升机巡航（两树）

- `flightBrake` 增加 `cruise` 参数：`!fighting && horiz > FLIGHT_ARRIVE` 时跳过周期急停脉冲；
  到位硬刹（≤3 格）与接敌盘旋脉冲保留。直升机/飞艇/汤姆6 三档调用点同步传入。

### 玩家坐上运行中的扫帚（两树）

- `maidShouldPilot` 顺序：飞过去目标 → 她飞（玩家按 WASD 立即取消目标）；
  扫帚任务且 `isModeActive` → 她飞；其它 → 玩家驾驶，原版一字不改。
- 玩家输入需连续存在且超过 `TAKEOVER_GRACE = 10 tick`（0.5 秒）才写入
  `PLAYER_TOOK_OVER` 锁；锁上后本趟都归玩家，松开也不抢回。
- `LAST_SEEN_AT` 记录上一拍见过玩家的时刻，断档超过 `RESEAT_GAP = 5 tick`
  视为重新坐下，清掉上一趟的接管锁（下坐再上坐没有回调可用）。
- 女仆仍负责开火与朝向；推进意图由 `MaidBroomBehavior` 正常写入。

### 透明化（两树）

- `MaidGhostRender.isGhostTarget`：玩家骑着她时，她骑着扫帚也算；
  玩家骑扫帚且女仆是同一把扫帚的乘客时直接算，不再要求 `isMyMaid`。
- `isGhostBroom`：玩家骑着她、她骑着扫帚时也半透明，不再要求拴绳。
  仅影响本地第一人称渲染，不改任何服务端状态。

## 验证

- 两树编译 `EXITCODE=0`。
- 14 项护栏 `FAIL 0 / WARN 0`（含 `check_workflow`）。
- 打包 + 部署 4 实例 `match=True`。
- 四套专服回归 PASS：`test_server 1201`、`test_server neoforge1211`、
  `test_maid_load 1201`、`test_maid_load neoforge1211`。
- Forge jar：18,197,019 字节，SHA256
  `3B304F92646A3A3E4C32D27FC7265A7C4CB313BAE47237F4E499D677454BA0E0`。
- Neo jar：18,258,157 字节，SHA256
  `EA097611BD778967E5E18938056F9B9F24E480F16674D16C2CA0E523AB3D167F`。
- **反证**：
  - `allowNonGliding` 只在「本轮空袭进行中 + 未落地」时为真：地面滑翔、扫帚、
    仿创造飞行、单纯跟随都仍被原闸挡住。
  - `getControllingPassenger` 只在 `maidShouldPilot` 为真时返回 null；
    玩家驾驶扫帚时原样返回 Player，TLM 自己的 `IBroomControl` 链路不受影响。
  - 玩家输入用服务端同步的 `xxa/zza`（1.21.1 `LocalPlayer` 在 `isPassenger()` 时
    每 tick 发 `ServerboundPlayerInputPacket`，字节码实证），不是客户端本地猜测。
  - 急停脉冲只跳过巡航段；`horiz <= 3` 与接敌时仍按原节奏刹，不会撞上目标点漂出去。

## 边界

- 大运的放宽仅限空袭轮内：本轮结束、目标消失或落地后立即恢复严格滑翔判据。
- 守家提示改为系统消息只此一条；其余拒绝（缺绳、非主人等）仍走女仆气泡。
- 同乘扫帚的「移动键」只认 WASD 的 x/z 输入；跳跃/潜行不触发接管。
- 玩家接管后本趟不再自动交还；下坐再上坐才重新由女仆驾驶。
- 半透明只是第一人称视觉处理，服务端实体状态、碰撞箱与索敌都不变。
- 版本号仍为 **1.3.3**，同号重打；发布素材等验收后再写。

## 验收

1. 女仆执行空袭（含收翅猛击、激流旋转）时戴着大运：沿途方块继续被清、撞到的怪继续吃伤害；
   落地或本轮结束后不再清。
2. 对守家女仆使用武装拴绳：聊天栏出现带女仆名的红色系统消息，不再出现女仆聊天气泡。
3. 玩家先坐上女仆正在飞的扫帚，再点「飞过去」：扫帚由女仆飞向目标，玩家不再自主操纵。
4. 直升机沿航路/目标点长距离飞行：不再每半秒顿一下；接近目标点仍会正常减速停住。
5. 女仆骑扫帚巡逻时玩家右击坐上：不按 WASD 时她照原路线继续飞；按住 WASD 约半秒后
   方向盘交给玩家；全程不按则一直由她飞。
6. 玩家正常骑上载着女仆的扫帚（非武装拴绳）：女仆与扫帚在视野里变为半透明。
