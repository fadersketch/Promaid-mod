# R1080 鞘翅长矛·幻翼态（1.3.3 同号重打）

## 排班

- **跑道**：C 功能（1.21.1 NeoForge 专属；1.20.1 没有长矛空袭链路，不镜像）。
- **开工前读过**：`rounds/R1022`、`R1034`、`R1043`、`R1044`；`SpearAirRaidBehavior`、
  `MaidFlightCombatBehavior` 的起飞/滑翔/长矛补判、`MaidFlightKit`、`SpearCoreCompat`、
  `MaidVirtualFirework`、原版 `Phantom` / `PhantomMoveControl` / `PhantomSweepAttackGoal`。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 定位 | 旧长矛空袭链路 35° 爬升/浅角俯冲已停用；需求改为"先通用起飞，0.5 秒后完全套用幻翼" | ☑ |
| 1 | 起飞接管 | 观察通用链路的 `LAUNCH_START`，0.5 秒后接管；未接管前通用链路一字不动 | ☑ |
| 2 | 幻翼状态机 | 照搬 CIRCLE / SWOOP / 环绕锚点 / 俯冲目标 / 撞上判定 / 速度合成 | ☑ |
| 3 | 速度曲线 | 接管当拍写入实际速度；每 tick ×0.999；下限 50% | ☑ |
| 4 | 平A换冲锋 | 全程 spearcore 蓄力；俯冲撞上目标按长矛结算；有烟花时冲锋前再消耗 1 枚加速 | ☑ |
| 5 | 惯性平飞 | 命中后解除幻翼态、水平惯性 1 秒，撞到敌人仍结算；结束交回通用链路重新起飞 | ☑ |
| 6 | 耐久 | 全程保持原版滑翔位，鞘翅耐久由 `ElytraItem.elytraFlightTick` 每 20 tick 扣 1 点 | ☑ |
| 7 | 编译与护栏 | Neo 编译 `EXITCODE=0`；全部护栏 `FAIL 0 / WARN 0` | ☑ |
| 8 | 测试与部署 | 四套专服测试 PASS；双 jar；4 实例部署 `match=True` | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

- 开发者原话（要点）：鞘翅长矛像重锤一样单独出一个链路；锁敌范围与常规近战空袭一致。
  未接敌平地走路；接敌后先用通用近战空袭的起飞方式（消耗一次烟花/其他消耗物），
  **起飞 0.5 秒后完全套用幻翼的攻击方式和状态机**。进入该状态的那一拍写入"起飞后
  0.5 秒的速度值"，随后每 tick 衰减 0.1%、最多衰减 50%。
- 与幻翼唯一的区别：幻翼单次攻击后飞离；女仆把平A换成**长矛冲锋**（同一套伤害结算），
  存在烟花时在平A前再消耗 1 枚火箭加速。攻击完成后解除幻翼态，变为常规水平鞘翅飞行，
  顺着惯性保持冲锋 1 秒（撞到敌人仍造成伤害），随后解除飞行；若仍有敌人则回到链路开头循环。
- 兼容：壮丽鞘翅 / 孔雀羽扇这类可飞行物只作"能不能起飞"的启用条件，不改变链路；
  冲锋前的额外加速只有烟花才会触发。鞘翅耐久要像真实鞘翅一样结算。

## 评估

- **为什么单开**：通用近战空袭的空中段是"62° 背离爬升 → 拉开 → 收翅猛击"，与幻翼的
  环绕/俯冲几何完全不同。把长矛链路继续塞在通用链路里会互相改朝向、改速度；单开成
  `SpearAirRaidBehavior` 后，通用链路只负责**起飞**，空中动作整段让位。
- **为什么等 0.5 秒**：通用起飞链路（跳跃→烟花/羽扇/激流/自推鞘翅）需要十几 tick 才
  真正离地并获得速度。用 `LAUNCH_START` 记录点火时刻，0.5 秒后接管，既保留通用起飞
  的全部消耗与手感，又能拿到一个稳定的初速度。
- **为什么速度只减不增**：需求明确"写入 0.5 秒那个值，再衰减"。接管时直接清掉
  通用链路的 `LAUNCH_LEFT` / 隐形烟花 / 激流 / 自推鞘翅推力窗，避免它们和衰减曲线抢速度。
- **伤害口径**：主体仍由 spearcore 的 `onUseTick` 按视线相对速度结算；高速掠过时用
  `spearPathHit`（`hitOne` 已恢复长矛速度增伤）补一次路径/落地判定，避免"撞上了却没掉血"。
- **耐久**：全程 `MaidFlightKit.setGliding(true)`，胸甲槽鞘翅走原版
  `LivingEntity.updateFallFlying → ItemStack.elytraFlightTick`，每 20 tick 扣 1 点。

## 改动文件

- `promaid_src_neo/com/maidsmart/combat/SpearAirRaidBehavior.java`（重写，约 560 行）：
  WAIT_TAKEOFF / CIRCLE / SWOOP / POST 四相位；PhantomMoveControl 同式移动；环绕锚点
  20~40 格、半径 5~15 格；速度写入 + 每 tick 衰减 + 50% 下限；冲锋前烟花加速；
  命中后水平惯性 1 秒；结束交回通用链路重新起飞。
- `promaid_src_neo/com/maidsmart/combat/MaidFlightCombatBehavior.java`：
  新增 `LAUNCH_START` / `launchAge` / `dropLaunch` / `requestRelaunch`；`markLaunched`
  记录点火时刻；`hitOne` 恢复长矛"移动越快伤害越高"的速度增伤（路径/落地补判）。
- `promaid_src_neo/com/maidsmart/ProMaidExtension.java`：注册 `Pair.of(237, ...)`。
- `promaid_src_neo/com/maidsmart/config/MaidSmartConfig.java`：重写长矛空袭说明；
  删除旧 35° 爬升/俯冲/编队半径三项；新增幻翼态速度衰减与最大衰减两项；
  `spearRecoverTicks` = 接管延迟、`spearBoostInterval` = 惯性平飞时长、
  `spearPassRange` = 冲锋前加速距离、`spearMaxTicks` = 单次俯冲上限。
- `promaid_src_neo/com/maidsmart/config/PromaidConfigScreen.java`：长矛页按新链路重写。
- `promaid_src_neo/assets/maid_smart/lang/{zh_cn,en_us}.json`：更新/补充对应译文键。
- `promaid_src_neo/assets/promaid/panel_coverage.txt` 与 `ARCHITECTURE-SWITCHES.md`：重新生成。
- `promaid_src_neo/com/maidsmart/combat/MaidFlightCombatTask.java`、`MaidFlightCombatBehavior`
  注释：同步新链路口径（旧水平突袭段标记为仅对照）。

## 验证

- Neo 编译：`EXITCODE=0`；Forge 编译：`EXITCODE=0`（Forge 树本轮无功能改动）。
- 全部护栏：`FAIL 0 / WARN 0`（mixin 注入点 283，下限 200）。
- 专服回归（NeoForge 1.21.1）：`test_server.py neoforge1211` PASS；
  `test_maid_load.py neoforge1211` PASS（女仆召唤即加载 core 行为表）。
- 专服回归（Forge 1.20.1）：`test_server.py 1201` PASS；`test_maid_load.py 1201` PASS。
- Neo jar：18,191,664 字节，SHA256
  `6A371749DD4182F49E945AB6F8F0B72610C070C9CBB3030F83546B5D8F9FFB9E`。
- Forge jar：18,108,580 字节，SHA256
  `8B2EE7BD3A1AD2CFE60FAA6926E468C16E6179346F2144C628CE3BF6A2657D3F`。
- 已部署 4 实例，源包/目标包字节数一致（`match=True`）：1.21.1 Neo、1.20.1 Forge、
  龙之冒险整合包、pack1201 专服。

## 边界

- 仅 1.21.1 NeoForge：1.20.1 树没有 `SpearCoreCompat` / 长矛空袭链路，本轮不镜像；
  Forge jar 只是同号重打，行为与 R1079 一致。
- 旧长矛空袭的 35° 爬升/俯冲/编队半径三项配置已删除；旧 toml 里残留的同名键会被
  配置系统忽略，不影响启动。
- 幻翼态的几何（锚点 20~40 格、环绕半径 5~15 格、首个 SWOOP 10 tick）按原版幻翼硬编码，
  不新增配置；可调的是接管延迟、速度衰减、最大衰减、单次俯冲上限、惯性平飞时长、
  冲锋前加速距离。
- 链路循环仍受燃料限制：起飞与冲锋前加速会消耗烟花/羽扇/激流/自推鞘翅；没有推进剂时
  交回通用链路后不会再起飞。
- 速度下限是进入时速度的 50%，不会失速；但实际速度还受原版鞘翅滑翔物理影响，可能略低于
  目标速度。
- 1 秒惯性结束时若仍在空中且没有敌人，链路结束但**不清滑翔位**（避免自由落体摔死），
  由通用链路按正常滑翔落地。

## 验收

1. **地面待机**：未接敌时与普通近战空袭完全一致，平地走路、不起飞。
2. **起飞接管**：接敌后在 20 格内按通用方式起飞（日志「放烟花起飞 / 挥羽扇起飞 /
   激流三叉戟起飞 / 自推鞘翅起飞」），约 0.5 秒后出现
   「起飞 0.5 秒 → 幻翼态（速度 X 格/tick…）」。
3. **幻翼几何**：日志「幻翼态：环绕结束 → 俯冲」，机体先绕目标上方转，再俯冲；
   俯冲撞上目标时出现「长矛命中 → 解除幻翼态，水平惯性冲锋 1.0 秒」。
4. **速度衰减**：接管速度写入后只减不增，最低不低于 50%；不会出现越飞越快。
5. **冲锋伤害**：命中时目标按 spearcore 的长矛伤害掉血；背包有烟花时日志出现
   「冲锋前补一记烟花加速（消耗 1 枚）」，没有烟花时不补。
6. **惯性段**：命中后 1 秒内水平飞行，撞到其他敌人仍掉血；1 秒后日志
   「惯性冲锋 1 秒结束 → 交回通用空袭重新起飞（链路循环）」。
7. **耐久**：连续飞行时鞘翅耐久每 20 tick 掉 1 点，用坏后会换下一件或落地。
8. **兼容**：只带孔雀羽扇/壮丽鞘翅也能起飞并走同一套幻翼链路；冲锋前不会额外消耗它们。
