# R1024 1.21.1 长矛满蓄力不衰减 + 空袭上升段完全沿用通用链路（1.3.3 同号重打）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修复 / 兼容补齐（1.21.1 专属；1.20.1 等开发者验收批准后再镜像）。
- **开工前读过**：`rounds/R1023-neo-spear-takeoff-reuse.md`、`SpearAttackBehavior.java`、
  `SpearAirRaidBehavior.java`、`MaidFlightCombatBehavior.java`、`SpearCoreCompat.java`；
  以及实机日志 10:37–10:40 与 spearcore `SpearItem.onUseTick` / `computeDamageUseDuration`
  字节码。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 实机日志定位 | 确认 10:40:01–07 反复出现「贴脸先拉开 → 进入冲锋（13.9 格）」= 行为在 12 格边界反复 stop/start；确认 spearcore 到 `damageEndTick` 自己 `stopUsingItem`，再举矛进度从 0 重爬 | ☑ |
| 1 | 冲锋不再自解除 | `canStillUse` 所有相位统一 24 格脱战；目标读取 `getTarget()` 为空时回退 brain `ATTACK_TARGET`，避免索敌抖动导致行为重启 | ☑ |
| 2 | 满蓄力不衰减 | 新增 `holdMaxCharge`：每 tick 把 `useItemRemaining` 钉在 spearcore 伤害窗口最后一 tick（进度恒 1.0，也不会触发它自己的到点收手）；新增 `LivingEntityUseItemAccessor` | ☑ |
| 3 | 空袭上升段 | CLIMB 不再按「高于目标 3 格」切段；改为消耗通用起飞链的 `LAUNCH_LEFT`，推力烧完且竖直速度落回 0 才转浅角接近 | ☑ |
| 4 | 起飞朝向 | 与通用空袭同一条判据：地面目标背离 + 抬头，目标高出 10 格以上才朝目标爬；只把抬头角换成 35° | ☑ |
| 5 | 冲锋续满 | 攻击/空袭链路每 tick 调 `holdMaxCharge`；再点火成功后也立刻钉满 | ☑ |
| 6 | 编译与护栏 | Neo 编译 `EXITCODE=0`；14 护栏 `FAIL 0 / WARN 0`；`check_reflection` 显式上调 204→205 并留痕 | ☑ |
| 7 | 专服回归 | 测试服临时放入 `spearcore-1.21.1-neo-3.1.0`：`test_server` 与 `test_maid_load` 均 PASS（同时验证新 accessor 注入），测试后移除 | ☑ |
| 8 | 部署与验收 | 只部署 1.21.1 实例；Forge 源码树与两个 Forge 实例一律不动；待开发者实机验收 | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

- 开发者原话（本轮）：
  「1.I攻击模式使用长矛问题还是没能完全解决，女仆在发现目标后会开启3冲锋，但似乎过了一会儿冲锋又自己解除了，
  导导致女仆一直在反复冲锋和拉开距离 2.空袭状态飞升时间不要以高度为判定，保持通用的上升判定，下一链路依旧是h等到
  上升矢量消去以后才判定。无论何时，女仆只要开启冲锋就是满冲锋无衰减的」
- 追加澄清：
  「也就是说，现有链路是女仆点火起飞上升了3格后就扭头，但实际上这样拉不开距离。实际上应该改成跟通用链路一样的
  等给予的耗尽了再下俯冲。也就是说，从起飞上升这一个阶段，除了上升角度不同以外，跟其他的空袭起飞没有任何区别。」

## 评估

1. **冲锋自己解除**：`canStillUse` 在 CHARGING 只允许 12 格，而开局拉开/命中后撤/目标被击退都会
   把距离推到 12 格外 → 行为 `stop`，下一 tick 距离回到 10 内又 `start` → 重新进 OPENING 再拉开。
   实机日志 10:40:01–07 的连续「贴脸先拉开（8.5/9.6/9.7）→ 进入冲锋（13.9/17.3）」就是这条死循环。
   修法：所有相位统一 24 格脱战；目标读取增加 brain `ATTACK_TARGET` 回退，避免 TLM 索敌刷新那一拍
   把行为闪停。
2. **满冲锋衰减**：spearcore `onUseTick` 在 `useTicks >= damageEndTick` 时自己 `stopUsingItem()`；
   我们下一 tick `claimCharge` 虽然能重新举矛，但 `getSpearChargeProgress()` 从 0 重爬，
   实机就是「冲锋过一会儿自己解除」。修法：新增 `holdMaxCharge`，每 tick 把 `useItemRemaining`
   钉在「`computeDamageUseDuration() - 1`」对应的剩余时长，进度恒 1.0，伤害窗口也一直有效。
3. **上升段被高度截断**：旧 CLIMB 只要「高于目标 3 格」就转接近（日志 10:37:55 一条 tick 内
   「接管控空 → 爬升结束 3.9」），通用链路点着的那 30 tick 推力还没烧完就被摊到水平；而且旧版
   朝目标爬，没有通用链路「背离 + 抬头」那一段拉开距离。修法：CLIMB 朝向上与通用链路完全同一条
   判据（地面目标背离 + 抬头，目标高出 10 格以上才朝目标），只把抬头角换成 35°；时长上自己消耗
   通用链路的 `LAUNCH_LEFT`，等推力烧完且竖直速度 ≤0.05 才转浅角接近，并留 +40 tick 安全上限。

## 改动文件

- `promaid_src_neo/com/maidsmart/combat/SpearAttackBehavior.java`（408 行）：所有相位统一 24 格
  脱战；`currentTarget` 回退 brain `ATTACK_TARGET`；每 tick / 起手 / 进入冲锋 / 续矛都调
  `holdMaxCharge`。
- `promaid_src_neo/com/maidsmart/combat/SpearCoreCompat.java`（249 行）：新增 `holdMaxCharge` 与
  `computeDamageUseDuration` 反射缓存；把使用进度钉在伤害窗口末尾。
- `promaid_src_neo/com/maidsmart/mixin/LivingEntityUseItemAccessor.java`（新增，16 行）：
  暴露原版 `useItemRemaining` 写口。
- `promaid_src_neo/mixins.promaid.json`：登记新 accessor。
- `promaid_src_neo/com/maidsmart/combat/MaidFlightCombatBehavior.java`（3401 行）：新增
  `consumeLaunchTick` / `faceLaunchDirectionAt`；`faceAwayAndUp` 增加自定义抬头角重载。
- `promaid_src_neo/com/maidsmart/combat/SpearAirRaidBehavior.java`（446 行）：CLIMB 改用通用
  起飞朝向与推力时长，矢量消去后才接近；起手/每 tick/再点火都钉满蓄力。
- `tools/verify/check_reflection.py`：Neo `getMethod(` 基线 204→205（可选依赖探针，留痕）。
- `tools/arch/arch_modules.py` 与 `normify-promaid/`：新 accessor 登记、行号同步、产物重新生成。
- `rounds/RELEASES.tsv`、发布文档与桌面文本：追加本轮说明。

## 验证

- Neo 编译：`EXITCODE=0`。
- 14 护栏：`FAIL 0 / WARN 0`；`check_mixin_targets` 校验 142 个注入点；`check_arch` 一致。
- 专服（测试服 mods 里临时放 `spearcore-1.21.1-neo-3.1.0.jar`）：
  `test_server.py neoforge1211` PASS（新 accessor 注入成功）；`test_maid_load.py neoforge1211`
  PASS；测试后已移除测试 jar。
- Neo jar：18,125,360 字节，SHA256
  `16A4FEA9D436EE14AD749EB2D170026B7DD247AA94467534E79994793F68A833`。
- 已部署 1.21.1 实例，源包/目标包 SHA256 一致；Forge 源码树与两个 Forge 实例本轮零改动。

## 边界

- 仅 1.21.1 NeoForge；1.20.1 的镜像**等开发者验收批准后**再做。
- `holdMaxCharge` 只在「主手长矛 + 正使用长矛」时生效；未安装 spearcore 时反射找不到方法，
  直接跳过，行为与之前一致。
- 满蓄力钉的是 spearcore 的**伤害窗口末尾**，不改变它按相对速度结算伤害的公式；伤害/击退/
  疲劳仍由它自己管。
- 空袭 CLIMB 的 +40 tick 只是防呆上限：正常情况在通用推力烧完、竖直速度落回 0 时就切段。
- 地面冲锋仍保留「造成伤害 → 同向 1 秒 → 后撤 → 再冲」的既定战术；本轮去掉的是 12 格边界
  造成的**意外** stop/start 与进度重置。
- 版本号仍为 1.3.3（同号重打）。

## 验收

- 攻击模式：给女仆长矛、切攻击模式、把怪放在 9 格内。观察她先举矛拉开一次，之后进入冲锋；
  日志应只有一条「贴脸先拉开」，不应再出现每 1–2 秒重复的「贴脸先拉开 → 进入冲锋」。
  冲锋期间长矛进度应始终是满的：不应再看到蓄力动画/状态周期性掉回 0。
  命中后仍是「同向 1 秒 → 后撤拉开 → 再冲」。
- 空袭：给女仆鞘翅 + 长矛 + 烟花，切近战空袭。点火后应看到「接管控空」→ 持续爬升约
  通用起飞段时长（默认 30 tick）→「上升矢量消去」→ 浅角接近；起飞方向与通用空袭一致
  （地面目标背离 + 抬头），只有角度是 35°。不应再出现「接管控空」下一 tick 就「爬升结束 3.9」。
- 满蓄力：空袭全程与地面冲锋的蓄力进度应保持满值；不应出现周期性收矛再举矛。
- 卸载 `spearcore`：所有新增判据恒 false，行为与之前一致，无报错。
