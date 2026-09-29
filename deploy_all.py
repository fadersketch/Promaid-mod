# -*- coding: utf-8 -*-
"""Deploy the fixed jars to all local instances.

1.21.1 NeoForge 21.1.250  : patched/promaid-1.3.0-neoforge-1.21.1.jar
1.20.1 Forge 47.4.21      : patched/promaid-1.3.0-forge-1.20.1.jar   (user's main modpack)
1.20.1 server pack1201    : patched/promaid-1.3.0-forge-1.20.1.jar
Old jars are backed up under patched/backup_old/ first.

实测七百二十一（v1.3.0 beta，同名覆盖，未发版）：冰火传说龙骑位二期 + 骑乘指挥棒独占右击补闸
（两树镜像）——玩家原话 ①「现在女仆大概可以骑上龙了，但是会在背上反复横跳，甚至直接陷到地里面
窒息。而且龙的建模会被直接卡掉。」②「右击问题还是没有解决，拿着骑乘棒右击卓越前线的载具会直接
坐上去，而不是绑定。」
① **三个根因**：(a) 上一版只拦"每 tick 那条一参漏斗"，而原版**传送**直接调**两参**那个
   （`Entity.m_276804_` = teleportPassengers → `m_19956_(e, Entity::m_6027_)`，javap 实证）——
   每次传送（含"连人带坐骑送回主人身边"）都绕过上一版、把女仆又送进龙的"猎物在嘴里"分支；
   现在两个漏斗都拦。(b) 上一版"isFlying 为假就置真"会跟龙自己的悬停/落地判定**每 tick 互翻**，
   而鞍位在"悬停/飞行"与"站在地上"两档之间差约 4 格（阶段 5）＝横跳；现在只在它还站在地上时
   帮它离地，之后交给它自己那套飞行物理。(c) 龙在密闭空间里时鞍位**落在方块里**，女仆没有把
   方块挤开的体格 → 每 tick 吃 inWall 窒息（实机日志实证一串 `类型=inWall 位置=(…,-32,…)`）→
   自保 teleport 把她 unRide 走；现在落座前 `freeSeatY` 把鞍位往上抬到第一格她放得下的位置
   （最多 6 格）。(d) 自保传送对"被棍子绑上的骑乘女仆"让位（与 718 #31 坐/蹲、656 骑扫帚同口径）。
② **独占档补在登乘那一道**：上一版拦的 `PlayerInteractEvent` 只覆盖原版右击链路，而载具上车在
   更下面一层——任何 `startRiding` 都发 `EntityMountEvent`（javap 实证）。现在独占档挂那里：
   手里拿指挥棒的玩家一律不通过任何方式登乘（只拦玩家本人；女仆自己坐上去与武装拴绳不受影响）。
   日志搜「不登乘」。

实测七百一十一（v1.3.0 beta，同名覆盖，未发版）：烫伤脱困 + 鞘翅外观（两树镜像）——
玩家原话 ①「扫帚和空袭模式下有关岩浆的寻路还是不太聪明。好像是跟之前那个窒息的问题同一个问题，
用同样的方法解决就行。同时再加一个如果被烫到了立刻传送到最近的空气方块。」②「目前鞘翅的渲染
只在空袭模式下会被渲染出来。而且渲染出来的全都是原版鞘翅，能不能调用那个鞘翅自己的外观呢？
同时在所有模式下渲染。」
① **烫伤脱困**：与窒息同一个根——地面的危险方块处理（险境脱离）写明了豁免「乘客」
   （isMaidInSittingPose() || isPassenger()），而扫帚模式的女仆永远是她那把扫帚的乘客、
   空袭/飞行跟随又整天在空中，于是这三个模式里危险方块一条逃生都没有（只剩 MaidFlightHazardGuard
   的预测式 detour/antiSink：只在她还没进去时绕开）。新增 MaidHeatEscape：她**真的**泡进岩浆
   （isInLava）或被点着（isOnFire）时**本 tick 内立刻传送**到最近的空气格。判据用原版那一个
   （与窒息那档用 isInWall 同源）；泡水不算、烫不疼的不算（抗火/火焰保护饰品，收口成
   SelfPreservationBehavior.fireImmune）。岩浆每秒 4 点、她 20 血 → 等不起转向脱困，所以直接
   传（清摔落/清速度）；骑扫帚时**连人带扫帚一起搬**（MaidChunkLoadManager.relocateBroomRiderToCell，
   复用牵引绳的四步 broomRiderTo）。落点 = 最近、她放得下的空气格（自身/脚下非危险），
   一圈找不到就退一步只要能容下她；1 秒冷却。三个飞行行为 tick 最前面调用，true 则就此打住。
   开关 combat.heatEscape（默认开）。日志搜「烫伤脱困」。
② **鞘翅外观**：四个图层文件（forge/neo 的 Bedrock + Gecko）原先闸门是 isFlightVisual
   （飞行任务 或 正在滑翔）→ 放宽为「胸甲槽穿着可渲染的鞘翅」：站着/走路/跟随/空闲时背上也有
   一对**折叠**的翅膀，飞起来才张开（与玩家同款）。贴图不再写死原版 elytra.png，改 MaidWingSkins
   按物品 id 查表：伊卡洛斯之翼羽毛系/纸翼/魔法翼/贤者之石翼 + 空域系 6 件（滑翔另有 _reversed
   反向贴图，照抄）、神秘遗物+ 壮丽/混沌鞘翅，逐条反编译核实；认不出型号退回原版（不会画错）。
   开关 combat.wingRender（默认开）。与「自推鞘翅」互相独立（能不能飞 vs 长什么样）。

实测七百一十（v1.3.0 beta，同名覆盖，未发版）：**自推鞘翅——不靠烟花也能飞的那一类模组鞘翅**
（伊卡洛斯之翼 / 神秘遗物+，两树镜像）——玩家原话「加完模组之后有两种鞘翅不需要烟花也可以起飞。
我觉得需要做相关的兼容，如果装配了这些物品，相当于同时满足了烟花以及推进物品的要求。然后同时
也需要考虑一下他们的运动逻辑是怎么样的，看看怎么安排在女仆身上。之后其他的类似物品看有没有
一个通解通法。」
① 反编译摸清两家：伊卡洛斯之翼的**空域系**（`SynapseWings`：ikaros/nymph/astraea/chaos/
   hiyori/melan_wings）滑着就自推（`PlayerTickEvent`，`v += (look·d + (look·i − v)·t)·c`，
   **不用按键**）；羽毛系/纸翼/魔法翼只是普通 `ElytraItem`（无自推，不列）。神秘遗物+（1.21.1）
   的 `majestic_elytra`/`chaos_elytra` 继承 `BaseElytraItem`（`canElytraFly` 恒 true → 女仆可用），
   **按住跳跃键**自推（`v = v×0.48 + look×0.64` / `v = v×0.5 + look×k`）。1.20.1 的神秘遗物
   （EnigmaticLegacy / enigmaticaddons）把 `canElytraFly` 写死 `instanceof Player`，女仆连滑翔
   都做不到 → 那边用不了（不做 hack）；代码照镜像、表里留 id 备用。
② 关键障碍：三家自推**全都只推玩家**（挂在 `PlayerTickEvent` / `ServerPlayer` / 客户端跳跃键上），
   女仆收不到 → 只"认物品"她会展开滑翔后一路往下沉。所以真正的活是**由本模组替她施加推力**。
③ 通解通法：三家公式是**同一个形状** `v ← v×gain + look×add`（原版挂载烟花也是这一条）→
   一张表（物品 id → gain/add）+ 一个收敛推力窗口（`ignite/tick/clear`，40 tick 到期交还滑翔）
   + 两条接入链（空袭 / 飞行跟随各一处）。**逐条照抄各自数值**（巡航 0.70~2.20 格/tick 各不相同，
   特性即其自身），认不出型号走兜底 `0.5/0.5`；以后同类鞘翅只填 `combat.selfWingsItems` 一行 id、
   不改代码。全局倍数 `combat.selfWingsScale`（默认 1.0）。
④ 接线（两树镜像）：`hasFlightPropellant` 加第五条腿 + `equip()` 就绪判据放宽；`canLaunch` /
   `canJumpToLaunch` 对该条腿放行（**不吃烟花冷却**——它不消耗物资，那张表只该闸住要烧东西的腿）；
   `tryLaunch` 末尾加该分支（排序仍是 扇子 → 烟花 → 法术 → 激流 → 自推鞘翅，前四样手感一字不变）；
   两条飞行链每 tick `tick()` 推、收手时 `clear()`。**不消耗、不额外扣耐久**（只按原版滑翔
   每 20 tick 扣 1）：前四条腿是"借它的动作"，这一条只借它的**物理**。面板三行 + 四份 lang + 两树手册。
   日志搜「空袭·自推鞘翅」；缺件诊断那一行同时印出"自推鞘翅认没认出来、型号/巡航多少"。

实测七百〇九（v1.3.0 beta，同名覆盖，未发版）：扫帚模式两修（两树镜像）——
玩家原话 ①「扫把模式的窒息判定还是有问题。仍然会导致女仆窒息。被方块挡住的只能是扫帚。
而女仆在扫帚上似乎又没有碰撞箱，导致会陷进去窒息。」②「扫帚的启动链路还是比较落后，
目前也只判了远程武器，没有判弹药。应该改成跟远程空袭一样的起飞判定。」
① **窒息（真·被闷住）**：实测六百九十一 那套脱困的判据是"连续 12 tick 位移 < 0.06 格"，而
   玩家报的这一档是**扫帚贴墙"滑"**——每 tick 都在动，`still` 永远攒不满，脱困一次都进不去。
   真正被闷住的是**她**（乘客，座位在扫帚朝向后方 0.5 格，原版 positionRider 直接摆位、
   不做碰撞解算），扫帚有碰撞箱停在墙外、她被按进墙的边角扣 in_wall——正是"被挡住的只能是扫帚"。
   修：脱困前先问原版 `isInWall()`（**恰好**就是"这一 tick 原版要不要扣她窒息伤害"那道判据），
   为真**立刻**退到能容下她的空气格；选格规则同时为保命让位——不再要求"更靠近目标"
   （从方块里退出来本身就是正确方向）、不再封"目标高度 +3 格"（墙角外侧常更高）、
   一趟选不出来就**放开"5 秒内刚去过"的防抖再来一趟**；仍取最近的合格格。新日志「人正卡在方块里」。
② **起飞判定对齐远程空袭**：进起飞相位的判据原先是 `canStayMounted`（扫帚 + 远程武器，
   **刻意不含弹药**——那是 实测六百九十二 为"弹匣打空别摔她下来"定的口径），于是"有武器没弹药"
   的她照样腾空一次；而远程空袭（`MaidFlightKit.isModeActive`）**起飞前就要求三件齐 + 弹药**
   （实测四百九十五）。修两处：`armed`（= 远程武器 + 弹药）闸在"去找扫帚"之前（不武装就不出门找）；
   起飞相位改判 `isModeActive`（判假 → `hoverInPlace` + 报缺件，**不抬那 1 格**）；
   闸放在 `takeoffTarget` **之前**（否则那几拍会被相位的"没长高=顶头"判据当成结束相位，闸等于没关）。
   与 六百九十二 不打架：那条管"要不要下鞍"，本批只管"要不要起飞"；且弹药走 canFeed||canReload
   （实测六百九十四/六百九十五），"弹匣空但背包有对得上的弹"算齐备。

实测七百〇八（v1.3.0 beta，同名覆盖，未发版）：**修"同区块邻居撤票，把她的区块加载一起撤掉" +
同区块共享票不重复挂载**（两树镜像）——玩家原话「排班表……如果重生之后的女仆离玩家太远，
那么玩家无法通过排班表将他召回过来，排班表上也不会显示他的名字……远程打开女仆的背包」。
① 先核实：上次（实测六百九十九）那套修复源码与**已安装 jar 里都在**（两树构建产物与实际
   部署 jar SHA1 完全一致）——名单补「⚑ 未加载」、单点查询回"最后出现位置·未加载"、
   RemoteMaidGui 强制配对（距离上限已取消）、复活先同步加载重生点区块再 addFreshEntity。
② 真 bug：原版 `addRegionTicket` 对"同类型+同等级"的票会**去重**（`Ticket.compareTo` 只比
   等级/类型/比较器，本模组 `MAID_TICKET` 比较器恒为 0）⇒ 两只女仆同区块只有**一张**票；
   而旧代码撤票是**按女仆逐个撤** ⇒ 撤掉 A = 撤掉共用的那张 ⇒ **B 的区块当场卸载** ⇒
   下一轮 5 秒扫描 B 不在实体表 ⇒ B 也判"不要票" ⇒ 她从此无人加载（排班表看不见、召不回）。
   触发条件是"同区块邻居先走一步"而非距离，所以症状"时好时坏、找不到规律"。
③ 修法：票的挂/撤统一过一层**按区块的引用计数**（`CHUNK_WANTERS` + `acquireTicket`/
   `releaseTicket`）——**只有某区块最后一位持有者释放时才真正 `removeRegionTicket`**；
   持有者前缀区分（`m:` 会话票 / `s:` 强载票，共用票网格必须分开计数）；票龄护栏改"按区块记"；
   `releaseAll`（关服/关开关）仍整盘清场。全部挂撤票点（会话票、换区块、换维度、待召回强载票、
   复活补票）一律改走这一层，删除已被取代的 `removeTicket`/`TICKET_BORN`。
④ 顺带完成玩家要的优化：**覆盖重叠不再重复挂载**——引用计数的 key 就是 `(维度,区块)`，
   多只女仆覆盖同一区块自动合并成一张票，开销是覆盖范围的**并集**而非"每只女仆各开一片"。

实测七百〇七（v1.3.0 beta，同名覆盖，未发版）：起飞链路"一定要成功"——不再原地蹦着飞不起来（两树镜像）——
玩家原话「偶尔女仆会出现在地上跳了好几次却起飞不了的情况，被敌人打中了以后反而可以起飞了……
最终还是要求女仆一旦执行这个起飞的链路，一定要成功执行，而不是在原地浪费」。
根因：起跳（jumpForLaunch / 第 0 步补跳）只写了 `setDeltaMovement(x, 0.42, z)`，而**同一 tick 的 brain 阶段**
里 TLM MaidMoveControl（想游泳时直施速度）/ 本模组战术与自保 / MoveToTargetSink 的寻路都会再重写位移 ⇒
0.42 被摊平，她原地蹦却离不了地（updateFallFlying"落地即清滑翔位"是硬条件）⇒ 烟花永远点不着；
而**挨打那一下不属于女仆方的移动意图**、不会被覆盖，反而把她顶离了地——这正是"被打中后反而能起飞"。
修：新增 `kickOffGround`——清 WALK_TARGET + 停导航 + 重给 0.42 + 置 hasImpulse（服务端重发位置包），
`jumpForLaunch` 与补跳共用；新增「起飞受阻」留痕（正常一次都不该打出来）。

实测七百〇六（v1.3.0 beta，同名覆盖，未发版）：近战空袭"拉开距离"把空袭打废的 bug + 远程空袭高度硬地板（两树镜像）——
① 玩家原话「这一个版本的近战空袭基本上就失效了，可能是上一次出现的离敌人最小距离在近战空袭中也发力了。
  导致女仆基本上碰不到敌人」——**判断完全正确**，根因就在 704 新加的那一段。实机日志实证（1.21.1 `latest.log`）：
  `近战空袭/拉开距离：水平 X 格 < minStandoff 6.00` 25 行（一场 166 秒）、距离在 0.78~5.99 格之间来回，
  而同一场 `猛击` **0 行**——俯冲一次都没发生。两个 bug：
  (a) **两把尺子**：拉开判据用**水平**距离、俯冲闸门用 **3D** 距离（`dist <= smashRange()`）。恒有 `3D ≥ 水平`
  ⇒ `3D ≤ 3.5` 必然推出 `水平 ≤ 3.5 < 6` ⇒ **每次想俯冲都先被"拉开"拦下 return**，俯冲几何上不可能发生。
  修：两条判据统一成 **3D 距离**（`maid.distanceTo / m_20270_`）。
  (b) **预算自复位**：`pulled` 计满 40 后下一 tick 归零 ⇒ "拉开 40 tick、放开 1 tick、再拉开 40 tick"，
  6 格 → 收翅线 3.5 格那段进场路永远走不完。修：**一轮只拉开一次**——"够远"或"预算用完"即置位
  `STANDOFF_DONE`，本轮余下全部放行俯冲；`endSmash` 摘标记，下一轮重新评估。另：`WAIT_LAUNCH` 期间不拉开。
  俯冲的瞄准/命中判定（sweepWithin / smashHit）**一个字未改**。新日志「放弃拉开」。
② 远程空袭「飞着飞着只剩一点高度」（玩家原话「至少那个比敌人高上 8 格不能有太大的偏差」）：旧版只有软回正
  （高度误差→俯仰）+ 掉高补推（5 秒冷却），叠加后高度持续锯齿、偶尔探到 8 格以下。新增
  `airRaid.minAboveHeight`（**离敌最低高度**，默认 8.0，0~64，0 = 关）——**硬地板**：低于它就把盘旋俯仰
  **直接钉成最大抬头**（不走增益——增益在死区附近给的角太小、救不回）+ 把"掉高补推"的**触发门槛提到本值**。
  与「期望盘旋高度 10」配成"目标带 10、地板 8"。新日志「高度地板」（节流 5 秒）。
③ 面板「③ 盘旋与补高」加一行「离敌最低高度（格）」+ 改写「离敌最近距离」说明（标注 706 修正）；
   两份 lang JSON 各 +1 键；两树 guide 补「离敌最低高度」一段、改写「空袭也拉开距离」；
   四处 changelog + 本说明。**没动**俯冲判定本身、没动远程盘旋半径的 minStandoff 硬下限、
   没动「期望盘旋高度 10」的默认值。

实测七百〇五（v1.3.0 beta，同名覆盖，未发版）：接敌机动不再「一场定一种」+ 环绕每 8 秒随机掉头（两树镜像）——
① 玩家反馈「女仆似乎扫帚模式还是只会环绕」。翻 2026-09-28 实机日志（5502 行）：调用链是通的
   （`接敌机动` 8 行、五种机动里蛇形/高悠悠都真抽到过），但「一直在环绕」的观感是真的，两个原因叠一起：
   (a) 那一仗从 00:00:28 打到 00:03:11（**163 秒**），而机动**一场只抽一次**（`begin` 幂等）⇒
   抽到环绕就环绕 163 秒——旧设计假设"一场遭遇十几秒"，实战里一个 boss 能打两分钟以上；
   (b) 环绕权重最高（0.34）且另外四种几何幅度偏小（蛇形仅 ±15% 半径）。
   修：`CombatManeuver` 新增段上限 `SEGMENT_MAX_TICKS`（400 tick = 20 秒）——同一场遭遇内每满 20 秒
   重抽一次、且**保证与上一段不同**（`pick(id, salt, avoid)`）；权重改成 环绕 0.24 / 蛇形 0.24 /
   高悠悠 0.18 / 脱离再进 0.17 / 8 字横切 0.17（和为 1）；蛇形幅度 ±15% → ±22%；波形 ticks 换段归零
   ⇒ 每个机动都是完整一段。新读数 segment/entering/segmentTicks；日志改「本场遭遇第 N 段·换打法」。
② 环绕旋向每 8 秒随机掉头（玩家原话「不要一直顺时针或者逆时针……差不多 8 秒钟一个周期吧。随机选择
   继续顺时针或者逆时针」）：`CombatOrbit` 新增 `flipSign`——每 `DIR_FLIP_TICKS`（160 tick = 8 秒）
   **对半概率**决定继续原方向还是翻过来，乘在原有 `direction`（UUID 派生）上；掉头只改角速度符号、
   **位置连续**（不是瞬移），不需要插值。扫帚 + 远程空袭两条链路共用，真翻那一刻各写一行「反向环绕」；
   `forget`/`clearAll` 一并清 DIR 表。
③ 手册（两树 GuideChaptersFlight）补「每 20 秒换一种」+「旋向每 8 秒随机掉头」两段与新权重；
   面板「接敌机动」新增一行「环绕 · 方向会掉头」，`COMBAT_MANEUVER_ENABLE` 注释同步。
**没动**「战斗结束判定」本身（日志证明收尾链条已在生效，真正病因是"一场太长而一场只抽一次"，
已由段上限解决）；**没动**空袭拉开距离（那是 704 的 airRaid.minStandoff + faceAwayFlat，日志已见在打）。

实测七百〇四（v1.3.0 beta，同名覆盖，未发版）：四条（两树镜像）——
① 接敌机动「每场遭遇抽一种」**原先根本没生效**（实测日志实证：整场 21554 行里 `接敌机动` 只出现
   1 次）：`CombatManeuver.begin` 幂等、而"遭遇收尾"（`MaidBroomDrive.clearClimb` /
   `MaidFlightCombatBehavior.endFlightSafely`）**不清它的状态**，只有换任务/下线才清 → 一次抽签
   用一整天（第一场抽到环绕就一直环绕）；且扫帚那条用 `mountLog`（5 秒节流）写日志，
   与同 tick 的「随机环绕」抢名额、`接敌机动` 那行被静默丢掉。修：两处收尾各加
   `CombatOrbit.forget` + `CombatManeuver.forget`；扫帚改成直写日志。
② 岩浆判定按「她的身位」算：新增 `DangerBlocks.cellDangerousBoxed`（水平 ±ceil(宽/2) 格，
   只加宽不加高）+ `boxRadius`；`MaidFlightHazardGuard` 的 dangerousAt/detour/up/pathBlocked/
   antiSink 全带上碰撞箱半径。顺带修两个真 bug：`SelfPreservationBehavior.avoidLavaMovement`
   原先探**眼高**那格（岩浆贴地、眼高恒空气 → 避让对"走进浅岩浆"完全失效），改探脚底那一层；
   `MaidBroomDrive.parkIdle`（待命降回地面）原先不认岩浆（只问"是不是空气"）→ 落点是危险格就
   原地悬停。飞行跟随那一支补 `antiSink`（原先只有航段绕开、没有下沉抬平）。
③ 空袭「拉开距离」：新增 `AIR_RAID_MIN_STANDOFF`（airRaid.minStandoff，默认 6.0，0=关）——
   远程盘旋半径硬下限 + 近战"先背离平飞拉开再俯冲"（新增 `faceAwayFlat`，俯冲的瞄准与命中判定
   一个字不改）。
④ 等价交换（ProjectE）适配结论：**走「资格物品表」那一路即可**（`misc.freeFlightItems =
   ["projecte:gem_boots","projecte:arcana_ring"]`）——javap 实证 ProjectE 的飞行是
   `ServerPlayer` 绑定 capability（`IFlightProvider.canProvideFlight(ItemStack, ServerPlayer)`），
   女仆天然不适用；SWRG 本版本 `canProvideFlight` 恒 false，别填。

实测七百〇三（v1.3.0 beta，同名覆盖，未发版）：接敌机动（五种飞行方式）+ 空袭空中防叠罗汉（两树镜像）——
玩家原话「全是保持盘旋状态的，战斗方式过于单一……接敌之后从多种飞行方式里选一个执行，而不全是统一
绕圈……基础比敌人高多少格这一点还是要的」。新增 com.maidsmart.combat.CombatManeuver（两树字节一致，
纯逻辑无 MC 类型）：五种机动 环绕/蛇形/高悠悠/脱离再进/8 字横切，**每只女仆每场遭遇各抽一种**
（UUID + 遭遇序数派生，稳定可复现，下一场换一种，同场各抽各的）。它只输出乘数/加数（半径倍率
[0.82,1.15]、角速度倍率 [0.75,1.15]、高度增量、旋向翻转），几何骨架与硬上界一个字不改
（半径仍夹进 [近端, 最远距离]）；高度**只加不减**（heightAdd 返回 0~amp），所以「基础比敌人高多少格」
在任何机动下都成立。第二条：空袭（近战+远程）**原本完全没有**反叠罗汉（全树 grep 零命中，扫帚那边
六百九十一 就有）——远程盘旋半径加 UUID 派生的稳定偏置（±combat.airSeparationRadius，默认 2）、
近战爬升方位加 ±12° 稳定偏置（俯冲瞄准一个字不改）。新配置段 [maneuver]（面板「战斗与自保 → 接敌机动」
单独成板）：maneuver.enable（默认开）/ maneuver.yoyoAmp（默认 4，0~16）/ airSeparation（默认开）/
airSeparationRadius（默认 2.0，0~8）；两树语言文件各 +5 键。日志搜「接敌机动」看每只抽到哪一种。

实测七百〇二（v1.3.0 beta，同名覆盖，未发版）：仿创造飞行移植到 1.20.1（精简版）——
把 1.21.1 树的「仿创造飞行」按 PR #24 跟帖里定下的边界搬到 Forge 树（只有 forge 树变，
neo 树本轮只跟着换 guide changelog）。资格探测**只留两路**（物品表 + 效果表）：重力归零
启发式砍掉（javap 实证 1.20.1 的 Attributes 里没有 GRAVITY 字段）、数据组件两路 @组件 /
@组件~文本 砍掉（1.20.5+ 才有）。动画注册一行没改（register 两版签名逐字相同，实证）。
搬过来的：状态机 / 一阶飞行力学 / 朝向 QoL / 智能待命 / 战斗档 / 赶路档 / 状态表寿命回收 /
遗留无重力交还。1.20.1 侧按本树现有范式补的：SimpleChannel 网络层、配置面板那一行开关、
快捷键（默认不绑定）、/maid_smart freeflight_goto|_follow|_enemy（仅 OP）、搭路整段让位 +
MaidMoveSuppressMixin 的 isControlling 档。SRG 名全部 javap 实证（几处反直觉：isAir =
m_60795_、getCollisionShape 在 BlockStateBase 是 m_60742_ 且要传 CollisionContext、
Vec3 分量字段 f_82479_/f_82480_/f_82481_、setDeltaMovement(DDD) = m_20334_）。

实测七百零一（v1.3.0 beta，同名覆盖，未发版）：三件事（两树镜像）——
① 复活女仆「身体停在原地、扫帚照飞」的根因是**客户端那只女仆没有载具**：本模组的补包方法
   resyncTo 只补了"她载着谁"（getPassengers 非空 → SetPassengersPacket(她)），**漏了
   "她骑着谁"**。原版 ServerEntity.sendPairingData 两个方向都发（if (!getPassengers().isEmpty())
   ... ; if (isPassenger()) SetPassengersPacket(getVehicle())）；而原版对乘客不发它自己的位置包
   （sendChanges 的 isPassenger 那一支只用载具坐标），所以客户端把她钉在 AddEntity 那一拍的
   落点上不动、服务端的扫帚照飞。只有复活过的女仆会中招：入世界补包对"新实体"刻意放过
   （实测五百九十六），复活正是"同 UUID、新 id"；只剩 697 那三枪，而载具侧的 lastPassengers
   早已记着"她在车上"、不会重发。修法：resyncTo 补发 SetPassengersPacket(maid.getVehicle())。
② 扫帚接敌随机机动二期：新增配置 combat.broom.minStandoff（默认 6，1~32，面板「离敌最近距离」）
   —— 盘旋半径近端改成 max(盘旋距离 × 0.75, 本值)，不再跟着「离敌最远距离」一起塌；
   CombatOrbit 新增 speedScale（绕圈速度 0.6~1.5 倍、每 3 秒重掷，与半径的 4 秒节拍错开）；
   接敌档与同伴最小间距 2 → 4 格（推力封顶 1.5 → 2.5）。
③ 粉丝日志答疑（FML/Forge/Flywheel 三行是正常版本行、不是报错；本模组是 Java 版、手机装不了）。

实测七百（v1.3.0 beta，同名覆盖，未发版）：仿创造飞行·跟随时速度被拽的源头抑制补档（neo 树）——
PR #24 复核中贡献者指出他的「飞行接管」档没随合入进 main。反编译 1.21.1 定论缺口真实：
原版 MoveToTargetSink.start() 走 moveTo(Path,double)（路径由 createPath 造），两条都不在
FreeFlightWalkGuardMixin（moveTo(DDDD)Z）射程里；飞行速度写在 MaidTickEvent（brain 之前），
而跟随在同一个 tick 的 brain 阶段重写 WALK_TARGET → 造路径 → MaidMoveControl 在我们写速度
之后执行 ⇒ 拖速度。修法：MaidMoveSuppressMixin 同一注入点加一档 isControlling（飞行/软着陆）
→ 清 WALK_TARGET + 停导航 + 取消；落地待命不拦。

实测六百九十九（v1.3.0 beta，同名覆盖，未发版）：排班表全量显示 + 一键召回真正可用——
① 根因（原版字节码实证）：往未加载区块 addFreshEntity 的实体不在实体表里（区块可见性
   默认 HIDDEN 就不 startTracking），而 697 的 5 秒持票对账扫的恰恰是实体表——她还没
   "现身"就把刚挂的票撤掉 → 区块放弃加载 → 她【永远】进不了实体表：排班表不列她、
   按 UUID 找不到她、一键集合只能对死亡点白挂 15 秒票然后报「没能等到」。死亡→复活
   的 60 秒窗口里她更不是实体。
② 修法：复活前先把重生点区块同步加载到 FULL（addFreshEntity 当场入表）；TICKET_BORN
   票龄护栏（刚挂 <100 tick 的票对账不撤）；LAST_SEEN 登记表升格 SavedData（重启后
   远处女仆仍有迹可循，顺带记名字）；排班表补「✚ 复活中 / ⚑ 未加载」两类行；单独
   召回对死者报复活倒计时、对未加载者当场强载召回；一键集合照实报 reviving 数；
   死亡瞬间登记作废（forgetMaid）。

实测六百九十八（v1.3.0 beta，同名覆盖，未发版）：三件事——
① 建造女仆不再被打死：日志实证（2026-09-27 latest.log）森近霖之助两次出事都是同一形状——
   「主动参战」把他从 maid_smart:build 切成 touhou_little_maid:attack 的那一拍，建造护盾
   （实测二百七十三）按「当前任务是不是建造」判定当场失效，2 秒后被下界合金巨兽打死。
   修法两处互不依赖：建造女仆默认不参战（战斗模式分类表点名 maid_smart:build=近战/远程
   可恢复旧行为）+ 护盾跟「建造女仆」走（参战会话中战前任务=建造也算建造中，整场不掉）。
② 幽灵建模的真正机制（原版字节码实证）：EntityLeaveLevelEvent = onTrackingEnd，区块视线
   档位降到 HIDDEN 时逐实体发（此刻 setRemoved 未执行 → reason=null）；副作用
   ChunkMap.removeEntity 给所有看过她的玩家发删包。三道门：持票即时跟人（followTickets
   每 tick 对账，旧版 5 秒）、复活落点当场补票（ensureTicketNow）、可见性自愈看门狗
   （ChunkMap.tick TAIL 上查"主人客户端认不认得她"，认不得当场重建，96 格内才管）。
③ 扫帚待命落地：⑥.③"没目标也没在跟"那一档从 hoverInPlace 改成降回地面待命
   （脚下探地 24 格，找不到地照旧悬停绝不往下扎；主人飞在天上也不落地）。
   新配置键 combat.broom.idleLand（默认开）。

实测六百九十五（v1.3.0 beta，同名覆盖，未发版）：卓越前线的「有弹药」这一条也交给它自己——
实测六百九十四 只交了 TACZ 那一半（当时卓越前线本机未装、无法 javap 实证，仍走口径不敏感的
宽松扫描）。现在两台实例的 SBW 0.8.9.1 都装上了，GunCompat.canReload 对卓越前线枪原样调用
GunData.shouldStartReloading(Entity)——与 TLM 自己的卓越前线换弹链路（SWarfareCompatInner.
doGunReload）用的是同一个方法：只数这把枪自己认的那一类弹药（口径必须对得上）、创造弹药箱
读作无限；能量武器（二次灾变 / 超级星星炮）照旧免检。两树镜像。顺带把描述「激活条件」的
几处文案（任务说明中英 + 手册两树）从「TACZ 自己判」统一成「枪械 mod 自己判（TACZ /
卓越前线各问各的）」。

实测六百九十七（v1.3.0 beta，同名覆盖，未发版）：自动复活之后自己补三枪实体包，治
「复活后建模被卡掉、变成幽灵状态」。日志实证：每一次自动复活都是"新实体入世界 →
（实测五百九十六）补包跳过 → 法术模组把刚加进来那只的离场当离场处理 → 客户端实体被删"，
而 596 又规定"新实体不补包"，于是没人把她补回来。现在 MaidAutoResurrect 在
addFreshEntity 成功后登记 MaidResyncCommand.scheduleForcedResync：+2 秒 / +5 秒 / +10 秒
共三枪 resyncTo（删 + 生成 + 全量数据 + 属性 + 装备 + 饰品），绕开 596 的 id 分流；
她界面开着（guiOpening）那一枪顺延。「离场」与「自动复活」两行日志都带上实体网络 id。

实测六百九十六（v1.3.0 beta，同名覆盖，未发版）：飞行危险环境避让——扫帚 / 空袭 /
飞行跟随的**飞行途中**也把 misc.dangerBlocks 那张表（岩浆 / 火 / 岩浆块…）当"不可靠近"。
新增 com.maidsmart.combat.MaidFlightHazardGuard：detour（航段会穿进危险格就按
±35/±70/±110 度侧向绕开、带 3 秒粘滞，全堵死就抬升 2 格爬过去）接进
MaidBroomDrive.steerTo 与 MaidFlightFollowBehavior.faceToward；antiSink（滑翔下沉到
危险格上方时把竖直分量抬平）接进 MaidFlightCombatBehavior.tickRangedAir 末尾。
攻击动作（收翅俯冲 / 俯冲助推）不避让。新配置键 combat.flightDangerAvoid（默认开）+
面板行 + 中英 lang。

实测六百九十四（v1.3.0 beta，同名覆盖，未发版）：模式门禁的「有弹药」这一条改成问 TACZ 自己——
远程空袭 / 扫帚共用的那道弹药门（MaidFlightKit.hasAmmoForWeapon）里，「这一拍打得响」那半
早已交给 TACZ（实测六百六十六 的 canFeed），但「换得上弹」那半还是口径不敏感的宽松扫描
（任意 tacz:ammo 都算）——玩家指出「像冲锋枪跟狙击枪并不是只要有远程武器和弹药就行」。
现在 GunCompat.canReload 对 TACZ 枪原样调用 AbstractGunItem.canReload(shooter, gun)：
弹药型号必须对得上这把枪、弹药箱照认、坏枪与满匣顺带否掉；它是纯只读查询（javap 实证），
每 tick 调用无副作用。反射不可用时回退旧宽松口径。SBW 当时无对应公开查询、仍走宽松扫描
（实测六百九十五 起已交给它自己，见上一条）。

实测六百九十三（v1.3.0 beta，同名覆盖，未发版）：锁敌后的「随机环绕路径」——
盘旋半径不再固定，改成每只女仆在 [基础盘旋距离 × 0.75, 「离敌最远距离」] 里各自缓动
（每 4 秒重掷一次、每 tick 只挪一小步，是「飘」不是「抽搐」），旋向按 UUID 对半分
（一半顺时针一半逆时针）；新增两个数值键 combat.broom.orbitMax（默认 10）/
airRaid.orbitMax（默认 12）= 玩家点名要的「锁敌之后离敌的最远距离」，它同时是随机区间的
顶点与硬牵引界（越界径向修正加倍往回带）。扫帚与远程空袭两条环绕链路同时改（新增
com.maidsmart.combat.CombatOrbit，纯数学、两树逐字节相同）；高度一个字没动。

实测六百九十二（v1.3.0 beta，同名覆盖，未发版）：三条反馈三修——
① 守家（home）时两条牵引绳（空袭/扫帚）的参照点与落点从「主人」换成「她的工作区圈心」
   （主人走多远都不再把她拽走；「飞太远回不来」照旧兜住，拉回家）；
② 弹药不再算下鞍理由（弹匣打空不再把她从扫帚上摔下来；「喂得上弹」拆成
   「这一拍打得响」或「背包里有对得上的弹药」两档）+ 缺武器要连续 2 秒才下鞍；
③ 守家巡逻高度只降不升（找不到地面返回 NaN + 巡逻高度记忆）+ 脱困候选格不许爬过目标高度 +3 格。

v1.3.0 实测六百九十一（扫帚三修：① 空中寻路不再「钻一格窒息」——脱困选格改按她
（乘客，座位在扫帚朝向后方半格）0.6×1.5 的碰撞箱逐格判 + 一格死洞不选 + 刚去过的格子
5 秒内不再进，一条都不合格就原地不动；② 多女仆不再叠罗汉——绕圈相位按 UUID 错开 +
steerTo 里"载着女仆的扫帚"邻近互斥；③ 守家盘旋不再贴地——新增配置 combat.broom.homeAlt
（默认离地 8 格）+ safeY 兜住天花板，followPoint 一并过 safeY）：
v1.3.0 实测六百九十（玩家崩溃报告：跨维度跟随扫描边遍历原版实体表边跨维传送女仆 = 
fastutil 迭代器槽位越界 → 「Exception in server tick loop」整合服务端崩回桌面；
新增 com.maidsmart.tool.EntitySnapshot，两树各 33 处「遍历全部实体」改成先取快照）：
v1.3.0 实测六百八十九（面板排版三修：武装拴绳那条 1000+ 字介绍拆成 4 条短行【旧版窄窗口下比一页
还高，把第 1 页挤成只剩板块标题、正文压住翻页/保存按钮】+ 注释绘制加下界兜底 + markdown 星号不再
原样画在界面上 + 顺带把「实测六百八十六」标题被顶到第一行的历史遗留归位）：
v1.3.0 实测六百八十八（撤掉空袭爬升封顶与拴绳上飘 0.10 封顶 + 「本来要走过去的活」也交给
仿创造飞行 = PR #24 设计问题的答复 + 在主人身边干活时不再做跟随起飞）：
v1.3.0 实测六百八十七（仿创造飞行并入 neo 树 = PR #24 本地修复版 + 有飞行能力禁用搭路
+ 「丢锁敌后拴绳当操纵杆」+ 绑定 HUD 两行 + 服务端一轮审计）：
patched/promaid-1.3.0-neoforge-1.21.1.jar 与 patched/promaid-1.3.0-forge-1.20.1.jar 同名覆盖，
前面那条 686 说明照旧适用。

v1.3.0 实测六百五十四（扫帚模式）+ 实测六百五十三（烧制卡死）+ v1.3.1 实测六百五十五（扫帚实测六条）
+ v1.3.2 实测六百五十六（烧制清单网格乱列 / 骑扫帚反复被拉回 / 移动照搬原版 / 起飞 1 格与接敌升 8 格 / 跟随同款）
+ v1.3.3 实测六百五十八（排班表列表行改显示状态 / 女仆配置"打开即关"预检 / 扫帚原地打转与爬升高度 /
  找扫帚优先 / 防刷怪插火把）
+ v1.3.4 实测六百五十九（找扫帚改成先去骑世界里放着的扫帚实体 / 女仆配置「真正的远程开界面」）：
+ v1.3.5 实测六百六十（女仆配置改「强制同步」：区块加载解决不了客户端同步，新增 ChunkMap$TrackedEntity 强制配对 + 每 tick 同步泵，距离上限取消）：
+ v1.3.6 实测六百六十一（扫帚模式收尾：牵引绳「连人带扫帚」传送、守家时绕工作范围盘旋、独占配置板块、骑乘限制只留主手武器、烹饪清单面板收成一个勾）：
+ v1.3.7 实测六百六十二（修死机：RemoteTrackBridge 这个「鸭子接口」躺在 mixin 包里又没登记，玩家一进世界服务端就崩 IllegalClassLoadError——搬到 com.maidsmart.schedule）：
+ v1.3.8 实测六百六十三（借自别人改过的 TLM 1.5.3：援护主人的仇人 + 目标跑远就撒手，新配置「援护半径」combat.assistRadius 默认 16）：
+ v1.3.0(beta) 实测六百六十四（玩家七条反馈：扫帚+home 不受排班传送 / 扫帚撞墙先飘到最近空气格 / 扫帚跟随起手收手距离与卡墙脱困三条新档 / 守家圈心兜底+诊断日志 / 刷怪笼插火把的走位所有权 / 烧制清单写清食物在同一格；版本号按玩家要求回到 v1.3.0 beta 口径）：
+ v1.3.0(beta) 实测六百六十六（枪械"打几发就哑火"双成因修复 + 面板越界值红字/钳位/跳转前保存）：
+ v1.3.0(beta) 实测六百六十七（粉丝点单：新道具「武装拴绳」——右击飞行女仆把自己挂到她下方，武装直升机二号位，再右击解除；合成拴绳+铁锭×2；面板扫帚模式板块末尾三行）：
+ v1.3.0(beta) 实测六百六十八【紧急修复】（667 的武装拴绳让游戏开不起来：mixin 的 @Inject 只匹配目标类【自己声明】的方法，
  而 positionRider 只在原版 Entity 声明、EntityMaid 只是继承 → 启动期 InvalidInjectionException 崩在 Bootstrap。
  改成 @Mixin(Entity.class) + instanceof EntityMaid 守卫；两树名字口径写死并带完整描述符；新增 _mixchk.py 全量注入点审计闸门）：
+ v1.3.0(beta) 实测六百六十九（武装拴绳五条实测反馈：门槛改两档【扫帚需空中/空袭随时】+ 下方没空间不把人按进地面；
  右击优先绑定【目标是扫帚→取背上女仆】；绑定后不再跟随盘旋、改成离地 3 格定点悬停【扫帚与空袭共用一处口径】；
  有语音的系统气泡 青§b→粉§d；贴图换原版拴绳重上色为白；9 条拴绳气泡进语音包 manifest 124→133）：
+ v1.3.0(beta) 实测六百七十（魂符提示语纠正：生命值过低 → 受到致命伤害；末地水晶底座回收修"飞远了收不回来"：条目不再先删后做、区块没加载就留着下一 tick 再试【不再同步强制加载/抛异常丢条目】、按 UUID 重解析回收对象、回收表单列 1024 且溢出会说话、三条"这一发作废"的路也把底座还她）：
实测六百七十二：武装拴绳三期的两个 jar（扫帚换座 / 拴绳不再定高度 / 上扫帚去抖）。
+ v1.3.0(beta) 实测六百七十一（武装拴绳二期：扫帚模式下右击绑定修可靠性【已经骑在扫帚上就放行 + 认人扫全部乘客 + 拒绝原因写日志】；悬挂定位改"挑目标 + 滑变"修玩家上下横跳；tetherHoldPos 兜底不再自相对【找不到地面就保持她自己现在的高度】修女仆无限爬升；悬挂距离默认 1.8→2.6 且可调 0.5~6.0；空袭绑定不再悬空起程【不再写任何高度】；绳子不再自己断【落地不再自动放人，只留入水】；玩家挂着期间同款免摔 fall+flyIntoWall）：
+ v1.3.0(beta) 实测六百七十三（武装拴绳四期：坐扫帚上右击可立刻切回绑定【换座态准星前方没实体 → 事件是 RightClickItem，补那个入口 + 只认自己扫帚上的女仆】；绑定玩家第一人称下女仆模型半透明【原版幽灵档 itemEntityTranslucentCull + 顶点 alpha，只对他生效，面板可调 0.0~1.0、1.0 关掉】；空袭模式先「牵绳」不挂人、她起飞才挂到二号位、落地满 2 秒放回牵绳）：
  * 实测六百七十四（武装拴绳四期）：半透明默认 0.35→0.1 且扫帚本体也透明；同步包带相位（修空袭档「坐到她头上」）；牵绳档金色描边标记；5 条新台词补语音
+ v1.3.0(beta) 实测六百七十五（武装拴绳五期：绑第二只自动松开第一只【换绑】；魂符收放残留的金色描边在「入世界最后一步」清掉；配置面板改革【武装拴绳独立成板块 + 首条功能详解 + 面板顺序按飞行三件套重排 + 首页路标 + 中英翻译键】+ 手册新增一整章；扫帚跟随起手/收手默认 25/5→6/3【不加鞘翅那条视线阻挡，理由入注释】；扫帚档额外下沉 0.3 消除与扫帚建模的重叠；绳子从一根 LINES 直线换成原版拴绳同款丝带（24 段三角带 + 编织明暗 + 两端光照插值）、配色改白）：
+ v1.3.0(beta) 实测六百七十六（武装拴绳六期：绳子隔远不再消失【自己的 8 格闸门放宽到 64 格】；"直上直下"时原版丝带宽度公式是 0/0 → 近垂直改用三片 0/60/120 围绳轴的带子 + 两端按帧插值 + 新增一层拖曳弯曲【她加速时绳子被拖在后面】，并修正原版两顶点竖直错开顺序；悬挂档右击不再靠准星射线【她就在你头顶，射线打不到 → 补"我骑着的就是她"这条认人规则】，修"绑定态右击坐回扫帚"时灵时不灵；1.21.1 新增二号位重锤猛击【玩家骑乘时 fallDistance 恒 0 砸不出猛击 → 按跟着她俯冲的下降格数替他记一份，攻击瞬间写回，新混入 PlayerMaceRideFallMixin + 新配置键 + 面板行】）：
+ v1.3.0(beta) 实测六百七十七（两套都齐说一句「我已经准备好了」【空袭三件套齐 + 已骑扫帚持远程武器 → 一步气泡 + 语音 ready_dual.ogg，说一次就闩住、扫帚条件解除/空袭条件断开满 2 秒/退出扫帚任务三处才刷新 CD；空袭那一半新写任务无关判据 airAssaultReady——扫帚模式的任务不是飞行任务，用 isModeActive 会误判成"缺武器"】；拴绳的「拉扯」做成真的【六百七十六 那层只是画面弯曲：现在服务端每 tick 按原版拴绳那套分档给力——> 6 格照抄原版 copySign(d²×0.4,d) 冲量、2~6 格补"朝主人的速度上限"代替原版每 tick 的 A* 寻路、> 10 格原版会撒手我们不撒手；挂在 MaidTickEvent 上（她自己的 tick 之前，两版 javap 实证）而不是每 2 tick 的校验；只对牵绳档使劲，悬挂档由骑乘定位刚性控制；新配置键 + 面板行 + 中英翻译键】；绳子弯曲改成"相对速度 × 松弛度"——绷紧时是直线，只有绳松才弯）：
+ v1.3.0(beta) 实测六百七十八（武装拴绳七期 + 扫帚数值：换座不再把她摔下去【玩家原话"不应该把女仆的骑乘状态也解除掉。这样子可能会导致失控"——旧版换座是"先把所有人请下扫帚再逐个放回去"，她真的被下过一次鞍、第二下失败就把她留在空中（她只有 20 血）；现在改用原版 addPassenger 自带的"玩家插队"规则（玩家是 Player 且第一乘客不是 Player → List.add(0, …) 插进驾驶位），她的骑乘关系一个字节都不动；javap 实证 1.20.1 Entity.m_20348_ 与 1.21.1 Entity.addPassenger 逐条同形，且 getPassengers() 返回 ImmutableList 所以只能靠原版那条规则换座】；扫帚接敌爬升高度 8 → 10【玩家原话"考虑到现在加入了这个模式，那么女仆需要飞的再高一点，默认应该是10格的高度"——写死的常量 CLIMB_BLOCKS 删掉，改成配置键 combat.broom.climb（默认 10，2~32）+ 面板行 + 中英翻译键；这一个数字同时决定"这一场遭遇的盘旋高度"】；左上角一行蓝色「绑定中」【玩家原话"最好是在左上角用蓝色字体显示一下玩家现在处于绑定状态。（渲染机制同冷却计时）"——真的复用冷却 HUD 那条链路：同一个包 → CooldownHudRenderer，位置/字体/自动清空/开界面隐藏全套白拿；只是字改成蓝色 §9、永远排最上一行；文案带档位"牵绳 / 二号位"；数据源只有 LINKS 一处；关掉冷却计时不影响它——那个"整段 return"的闸挪进新的 soulScanWanted()，冷却那三段各自判】）：
+ v1.3.0(beta) 实测六百七十九（骑扫帚链路收窄 + 配置默认值同步：**只有「武装拴绳在用」才接管扫帚驱动**【玩家原话"最好不要整体改骑扫帚的链路，防止其他mod对骑扫帚进行改动导致冲突。而是对物品进行判定（即只有玩家手持武装拴绳才走此链路，不拿则走原版）"——本模组改动 TLM 扫帚飞行的唯一地方 EntityBroomMaidTravelMixin 是在 EntityBroom.travel 的 HEAD 直接 cancel 掉原版、整条接管，别的 mod 的改动会被整体绕开；现在新增唯一判据 GunnerTetherManager.leashChainActive(maid)：主人手上握着武装拴绳（主手/副手）**或**她正被拴着（二号位里玩家拿的是枪或重锤，"不拿绳子"是常态，只认字面会把二号位摔下来）——不满足就一个字不改、travel 走原版、并清掉没人消费的推进意图，日志搜「扫帚让位」；注意原版「没有玩家驾驶的扫帚」是自由落体，收绳子前先落地】；配置默认值按玩家 1.21.1 实例同步 12 项【mine.breakBudget 22→6、soulSpellCooldownSeconds 60→1、waterLandingScan 2→3、playerDamageMode 4→2、autoResurrectDelaySeconds 60→10、aidThirstThreshold 15→20、combatWorkRange 15→32、scheduleAvailabilityCheck 开→关、flightFollow.enabled 关→开、firework/elytra/trident 三条省料开关 开→关；只改声明默认值、老 toml 一个字节都不动；面板与手册里写死旧默认的文案一并改】；两条调研结论入库【空袭施法：通用那半确实复用万法皆通 touhou_little_maid_spell 的公开 API，位移法术那半是自己写的反射直连 Iron's Spells（万法皆通没有公开的指定法术 API）；空袭开枪：接的原版 TLM 枪械通道 GunCommonUtil.performGunAttack → TACZ IGunOperator.shoot，promaid 只写视线/冷却/弹药三道门，没有任何碰枪械的 mixin】）：
+ v1.3.0(beta) 实测六百八十（搭方块禁用名单：面板「移动与行为 → 搭路 → 搭方块禁用名单」——全方块网格点一下切「红框✖禁止 / 绿框✔允许」，默认规则「只许原版天然方块」（NaturalBlocks 表 113 项），合成品与全部模组方块默认全禁、面板里点绿即可放开；判定单一口径 MaidBuildBlockFilter.isBlacklistedBuildBlock，覆盖自保搭高/挖矿/伐木/搭路；新键 buildOnlyNatural / buildBlacklist / buildWhitelist；饰品栏（Curios）里的鞘翅也算空袭三件套——全反射软兼容 CuriosElytraCompat，起飞时取出来穿到胸甲槽，耐久照原版扣（等效消耗）；hasElytra/equip/takeElytra/诊断四处接入）：
+ v1.3.0(beta) 实测六百八十一（把 679「按玩家实例同步默认值」整体撤回：12 项声明默认值恢复真默认【穿透预算 22 / 收符冷却 60 / 落地水下探 2 / 玩家伤害模式 4=仅一点 / 复活延迟 60 / 投喂口渴度 15 /战斗扩圈 15 / 排班可用性 开 / 飞行跟随 关 / 消耗烟花·鞘翅·三叉戟 全开】，并删掉 breakBudget 22→6 与 scheduleAvailabilityCheck 无条件→false 两条打架的强制迁移；玩家三个实例的 toml 一并改回默认【两个客户端走管理员通道 + 各留 .p681bak】，另加一次性兜底迁移 DEFAULT_REPAIR_MIGRATED【只搬还停在 679 值上的键】：
+ v1.3.0(beta) 实测六百八十二（扫帚模式失效修复：679 那道「武装拴绳没在用就让位」的闸整段撤掉——原版对"女仆单骑"只有自由落体，不拿绳子时她必然"坐着扫帚掉在地上动也不动"；接敌爬升默认 10→15 + 一次性迁移 broomClimbMigrated + 三个实例 toml 直改；武装拴绳：她换模式（空袭⇄扫帚）时自动解除一次（新去抖 KIND_TICKS/1 秒，日志「自动解除(切模式)」），并顺手修掉落水自动解除那支在迭代器里改 LINKS 的隐患；远程空袭三条：枪械冷却递减搬进 tickGunState（不再被射程/视线门冻住）、起飞段也开火、新增 airRaid.rangedFireRange 有效开火距离（默认 24，0=不限）；绑定态 HUD 多一行「右击她：解除；她骑扫帚时改为主动骑乘」）：
+ v1.3.0(beta) 实测六百八十三（YSM 模型的女仆重新有激流旋转特效：把 548 顺手写下的「LayerMaidSpinAttackGecko 只在 Gecko 渲染器下绘制」那条守卫撤掉——那句限制的理由（挂点依赖 Gecko 骨骼表）只对鞘翅图层成立，激流层一根骨骼都不用，而反编译证实 YSM 的女仆渲染器调用图层的位置与 TLM GeoReplacedEntityRenderer 同构，scale(-1,-1,1) 的换算照样成立；鞘翅那条守卫一字未动。顺带查清"万法皆通的女仆用烈焰冲锋时的模型"其实不是万法皆通的：烈焰冲锋=ISS burning_dash（只置 SpinAttackType.FIRE），火色激流是 ISS mixin 原版 SpinAttackEffectLayer 换贴图，万法皆通只负责把 ISS 的 cast 动画名映射成模型里的 iss:xxx 动画，而那套动画来自 YSM 内置default 包的 iss.animation.json。README/PUBLISH_GUIDE 那句同步改成只留鞘翅）：
+ v1.3.0(beta) 实测六百八十四（三件：① 扫帚盘旋「只比敌人高三格」的两个来源都在 MaidBroomDrive.combatClimbTarget —— 换目标就重爬（相位 key=敌人 UUID，而 TLM 每 tick 重挑 ATTACK_TARGET，两个敌人轮流被选中时高度在 15 格 / 3.66 格之间来回切）+ 被顶住就再也不试（旧版一场遭遇只爬一次，那个 3.66 是终值「以后一直保持」）→ 现在本场只爬一次、换目标只改 key，被顶住的每 5 秒（退避到 60 秒封顶）且头顶探针说"现在是空气"时重试爬升；STALL_TICKS 4→10；「头顶被顶住」那行现在写出头顶三格是什么方块（分清真天花板 vs 驱动故障）。② 悬挂玩家重锤：RIDE_SLOW_MIN 1.0→0.2 格/2tick（扫帚竖直上限 0.30 格/tick，旧门槛下永远被判慢降、累计钳在 1.0 → 挂在扫帚下一锤猛击都砸不出来），并给玩家那一侧补 [重锤门] 留痕（旧日志只有女仆那一侧的判定，1295 行全是"不触发"）；consumeRideFall 不再无条件写 RIDE_LAST_Y。③ 鞘翅：非 Gecko 渲染器（YSM 等）改走固定身体偏移（肩线 +1.86 格，480 那批统计值）不再整层跳过；模组鞘翅走通用判据 ItemStack.canElytraFly（不是护甲才叠翅膀，是护甲的（鞘翅胸甲）自带外观不叠，写一行「鞘翅渲染」日志）。手册两树 + README + PUBLISH_GUIDE 同步）：
+ v1.3.0(beta) 实测六百八十五（扫帚锁敌：她与两种空袭改用同一个索敌器 FlightTargeting——发现 50 格球 / 锁定后 128 格内维持 / 维持不再查视线，不再按 TLM 援护半径 16-8 格与 4 格高的排班盒丢目标；MaidBroomBehavior.aimTarget 先问索敌器、问不到才退回 TLM 链，stop 时 FlightTargeting.forget；「援护·跑远就松手」对扫帚空中让位（主人就挂在下面，前提不成立）；索敌探针（日志搜 空袭索敌）对扫帚也生效；两树手册扫帚节新增「锁敌」一段）
+ v1.3.0(beta) 实测六百八十六（扫帚盘旋高度：默认 15→12 + 一次性迁移 broomClimb12Migrated（只搬恰好 15 的档）；「只升不降」——顶头判据加一条「整段净涨 < 0.5 格」、STALL_TICKS 10→20、COMBAT_ALT 只升不降、重试 100→40 tick / 封顶 1200→600（平地也只比敌人高一点点这个症状的第三处根因）；二号位重锤收窄成**只对空袭那一档生效**（扫帚档不记账、不写 fallDistance、也不打「重锤门 玩家=」那一行）；两树手册同步）
  先试【直接复制】（不需要 UAC）；只有直接复制被拒（权限不够）时
才退回原来的提权 PowerShell 通道。旧版是无条件提权 → 会弹 UAC 卡住等人点。
"""
import os
import shutil
import subprocess
import sys
import tempfile

sys.stdout.reconfigure(encoding='utf-8')
BASE = os.path.dirname(os.path.abspath(__file__))

JOBS = [
    (os.path.join(BASE, 'patched', 'promaid-1.3.0-neoforge-1.21.1.jar'),
     r'D:\.minecraft\versions\1.21.1-NeoForge_21.1.250\mods\promaid-1.3.0-neoforge-1.21.1.jar'),
    (os.path.join(BASE, 'patched', 'promaid-1.3.0-forge-1.20.1.jar'),
     r'D:\.minecraft\versions\1.20.1-Forge_47.4.21\mods\promaid-1.3.0-forge-1.20.1.jar'),
    (os.path.join(BASE, 'patched', 'promaid-1.3.0-forge-1.20.1.jar'),
     r'C:\Users\Sketch\mc_server_test\pack1201\mods\promaid-1.3.0-forge-1.20.1.jar'),
]

# refuse while any java/javaw runs (game or server open)
probe = subprocess.run(['powershell', '-NoProfile', '-Command',
                        "(Get-Process -ErrorAction SilentlyContinue | "
                        "Where-Object { $_.ProcessName -match '^(java|javaw)$' }).Count"],
                       capture_output=True, text=True)
try:
    running = int((probe.stdout or '0').strip() or '0')
except Exception:
    running = 0
if running > 0:
    print('DEPLOY REFUSED: %d 个 java/javaw 进程在运行（游戏或服务器）——先全部退出。' % running)
    sys.exit(5)

for src, dst in JOBS:
    if not os.path.isfile(src):
        print('FATAL: jar 不存在:', src)
        sys.exit(6)

# backup old jars
backup = os.path.join(BASE, 'patched', 'backup_old')
os.makedirs(backup, exist_ok=True)
for src, dst in JOBS:
    if os.path.exists(dst):
        tag = os.path.basename(os.path.dirname(os.path.dirname(dst)))  # instance dir name
        bdst = os.path.join(backup, tag + '__' + os.path.basename(dst))
        try:
            shutil.copyfile(dst, bdst)
            print('backup:', bdst, os.path.getsize(bdst))
        except Exception as e:
            print('backup failed (continuing):', e)


def drop_other_versions(dst):
    """同一 modId 的旧包留在 mods 目录会与新包冲突导致启动失败 → 只删本模组的旧包
    （保留本次要写的那一个文件名），不动别人的 jar。"""
    d, keep = os.path.dirname(dst), os.path.basename(dst)
    for f in os.listdir(d):
        if f.startswith('promaid-') and f.endswith('.jar') and f != keep:
            try:
                os.remove(os.path.join(d, f))
                print('  removed old jar:', os.path.join(d, f))
            except Exception as e:
                print('  remove failed:', f, e)


# stage to temp (ASCII path) then copy
staged = []
for src, dst in JOBS:
    tmp = os.path.join(tempfile.gettempdir(), 'promaid_deploy_' + os.path.basename(dst))
    shutil.copyfile(src, tmp)
    staged.append((tmp, dst, os.path.getsize(src)))
    print('staged:', tmp, os.path.getsize(tmp))

need_elevation = []
for tmp, dst, size in staged:
    try:
        shutil.copyfile(tmp, dst)
        print('copied (no UAC):', dst)
    except Exception as e:
        print('direct copy failed, will try elevated:', dst, e)
        need_elevation.append((tmp, dst))

if need_elevation:
    inner_parts = ["Copy-Item -LiteralPath '%s' -Destination '%s' -Force" % (t, d)
                   for t, d in need_elevation]
    for t, d in need_elevation:
        inner_parts.append("Get-ChildItem -LiteralPath '%s' -Filter 'promaid-*.jar' "
                           "-ErrorAction SilentlyContinue | Where-Object { $_.Name -ne '%s' } "
                           "| Remove-Item -Force" % (os.path.dirname(d), os.path.basename(d)))
    inner = '; '.join(inner_parts)
    cmd = ['powershell', '-NoProfile', '-Command',
           "Start-Process powershell -Verb RunAs -Wait -ArgumentList '-NoProfile','-Command',"
           "'__INNER__'".replace('__INNER__', inner.replace("'", "''"))]
    r = subprocess.run(cmd, capture_output=True, text=True)
    print('elevated rc:', r.returncode, (r.stdout or '').strip()[:200], (r.stderr or '').strip()[:200])

for src, dst in JOBS:
    if os.path.isdir(os.path.dirname(dst)):
        drop_other_versions(dst)

ok = True
for tmp, dst, size in staged:
    if os.path.exists(dst):
        dsz = os.path.getsize(dst)
        match = dsz == size
        ok = ok and match
        print('deployed: %s  %d  match=%s' % (dst, dsz, match))
    else:
        ok = False
        print('MISSING after deploy:', dst)
    try:
        os.remove(tmp)
    except Exception:
        pass
sys.exit(0 if ok else 3)
