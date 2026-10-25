# R1036 1.21.1 长矛冲锋不抢起飞、撤销三叉戟分流、骑马长矛持续移动（1.3.3 同号重打）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修复（1.21.1 专属；1.20.1 等开发者验收批准后再镜像）。
- **开工前读过**：`rounds/R1034`（0.5 秒冲锋入口）、`rounds/R1035`（三叉戟分流）、
  `MaidFlightCombatBehavior` 的起飞窗口 `JUMP_LEFT/LAUNCH_LEFT` 与 `tickSpearCharge`、
  `SpearAttackBehavior` 五个相位的移动调用、`MaidRideKit.feedNavigation/stopNavigation`、
  `RideBindManager.driveLandMount`。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 复现与定位 | 日志确认冲锋在低空/起飞窗口内起手；骑马时只有乘客导航、坐骑导航没人喂 | ☑ |
| 1 | 冲锋让起飞 | 起手要求离地 ≥2.5 格，且起跳/点火窗口都结束 | ☑ |
| 2 | 冲锋方向限角 | 向下分量最多 -0.35（约 20°），不再按平上升矢量 | ☑ |
| 3 | 撤销三叉戟分流 | 删除 `Mode`/`State.mode`/群怪突刺分支与 `dashRange()` | ☑ |
| 4 | 骑乘移动 | 每个相位同时写女仆导航与坐骑导航；停止时两侧都停 | ☑ |
| 5 | 编译与护栏 | Neo 编译 `EXITCODE=0`；14 护栏 `FAIL 0 / WARN 0` | ☑ |
| 6 | 专服回归 | 临时挂载 `spearcore-1.21.1-neo-3.1.0`：`test_server` 与 `test_maid_load` 均 PASS，测试后移除 | ☑ |
| 7 | 部署与验收 | 只部署 1.21.1 实例；Forge 源码树与两个 Forge 实例一律不动 | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

- 开发者原话：
  「1.长矛的冲锋状态似乎会影响起飞，导致女仆在原地要卡好几次才能飞
  2.删掉三叉戟的分支战术
  3.女仆在骑马拿长矛时有时会打一下就在原地不动了。详细情况见刚运行的游戏」

## 评估

- **#1 冲锋抢起飞**：R1034 的 0.5 秒冲锋在通用链路「收翅猛击前的接敌距离」起手，
  但那一拍可能仍在起跳/点火窗口内、脚底离方块很近。冲锋会把速度方向改成指向目标，
  其中包含明显的向下分量，等于把刚点着的上升矢量按平；下一拍又贴地，再次点火——
  实机表现就是「原地卡好几次才飞起来」。
  - 起手闸：`!JUMP_LEFT.containsKey(id) && !LAUNCH_LEFT.containsKey(id)`——起跳与点火窗口都结束；
  - 净空闸：向下射线 8 格，脚底到最近方块 ≥ `SPEAR_CHARGE_MIN_CLEARANCE = 2.5` 格；
  - 方向闸：`spearChargeDirection` 把向下分量钳到 -0.35（约 20°），不再陡角扎地。
- **#2 三叉戟分流**：R1035 把 `SpearAirRaidBehavior` 扩成双武器 `Mode`，群怪时给三叉戟
  套长矛进场并在 4 格处释放旋转突刺。开发者判定这不是想要的分支战术，本轮整体回退：
  - 删除 `Mode`、`State.mode`、`isTridentChain/modeOf/label`；
  - `isConfigured` 回到「开关 + 飞行任务 + 主手长矛」；
  - `start/tick/stop` 恢复无条件长矛蓄力、滑翔、路径补判与「空袭·长矛」日志；
  - `tickApproach` 删除三叉戟突刺分支，只保留长矛水平贯穿点火；
  - `MaidTridentSpinBehavior.dashRange()` 访问器随之删除。
  - 通用空袭里 `smashHit` 的 `tryStartDash(airborne=true)` **保持原样**——那是三叉戟原本的
    单目标收翅俯冲攻击，不属于本轮的分支战术。
- **#3 骑马站住**：实机日志里她已是乘客，`SpearAttackBehavior` 每 tick 调
  `maid.getNavigation().moveTo(...)`，但乘客导航不会驱动坐骑；`RideBindManager` 只在
  `target` 或 `ownNavigationTarget` 存在时才喂坐骑。冲锋段一旦没有把目标点显式交给坐骑，
  坐骑就停在原地（日志只有 `[模组坐骑·目标]`，没有位移）。
  - 新增 `moveTo(maid, point, speed)`：先写女仆导航；若是乘客，再对坐骑调用
    `MaidRideKit.feedNavigation(mount, point, MaidRideKit.speedModifierFor(mount, maid), maid)`。
  - OPENING / APPROACH / CHARGING / AFTER_HIT / RETREAT 五个相位全部改走该入口。
  - `stop()` 除了停女仆导航，也调用 `MaidRideKit.stopNavigation(mount)`。

## 改动文件

- `promaid_src_neo/com/maidsmart/combat/MaidFlightCombatBehavior.java`（3671 行）：
  新增 `SPEAR_CHARGE_MIN_CLEARANCE` 与 `spearChargeClear`；冲锋入口增加起飞窗口与净空判定；
  `spearChargeDirection` 限制向下分量。
- `promaid_src_neo/com/maidsmart/combat/SpearAirRaidBehavior.java`（1067 行）：
  删除 R1035 的全部三叉戟分支，恢复长矛专用链路。
- `promaid_src_neo/com/maidsmart/combat/MaidTridentSpinBehavior.java`（586 行）：
  删除 `dashRange()` 只读访问器。
- `promaid_src_neo/com/maidsmart/combat/SpearAttackBehavior.java`（469 行）：
  新增坐骑导航复用入口，五个相位与 `stop()` 同步改。
- `tools/arch/arch_modules.py` 与 `normify-promaid/`：行号与描述同步（空袭模块描述改回长矛专用）。

## 验证

- Neo 编译：`EXITCODE=0`。
- 全部护栏：`FAIL 0 / WARN 0`；`check_mixin_targets` 校验 277 个注入点。
- 专服（测试服 mods 里临时放 `spearcore-1.21.1-neo-3.1.0.jar`）：
  `test_server.py neoforge1211` PASS；`test_maid_load.py neoforge1211` PASS；测试后已移除。
- Neo jar：18,143,237 字节，SHA256
  `1DD5495F6EF61D9F5C19C0F815F66076D5B3BF8117BCBA70702B119E729C1DCA`。
- 已部署 1.21.1 实例，源包/目标包字节数一致（18,143,237）。Forge 源码树与两个 Forge 实例本轮零改动。

## 边界

- 仅 1.21.1 NeoForge；1.20.1 的镜像**等开发者验收批准后再做**。
- 冲锋让起飞只影响「通用空袭 ≤3 目标时的那一记 0.5 秒冲锋」；>3 的独立长矛链路不变。
- 净空闸取脚底向下 8 格；在洞穴/室内低顶环境下同样会推迟冲锋，这是刻意的保守口径。
- 三叉戟只撤销「群怪分流」；单目标收翅俯冲的旋转突刺、激流推进与冷却完全不变。
- 骑马移动改为同时喂两侧导航；步行/飞行时仍只走女仆自己的导航，行为不变。
- 版本号仍为 1.3.3（同号重打）。

## 验收

1. **起飞**：平地上让她持矛打单目标/少量怪，起飞阶段不应再出现「冲锋起手」日志；
   应一次点火离地，不再原地反复起跳。日志里「冲锋起手」只应在已离地 ≥2.5 格后出现。
2. **冲锋角**：对地面目标冲锋时应接近水平，不再明显向地面扎。
3. **三叉戟回归**：主手激流三叉戟、附近 >3 个怪时，不再出现「接管控空（三叉戟…）」
   或「激流旋转突刺」的独立链路日志；应走通用空袭，只在收翅俯冲那一下旋转突刺。
4. **骑马长矛**：骑马持矛攻击时，冲锋、后撤、再接近都应持续移动；不应打一下就在原地站住。
   停止攻击后坐骑也应停下，不会自己继续走。
5. **回归**：持长矛、附近 >3 个怪时仍走独立链路（「接管控空（群怪 N 个…）」），
   ≤3 时仍走通用空袭 + 0.5 秒冲锋。
