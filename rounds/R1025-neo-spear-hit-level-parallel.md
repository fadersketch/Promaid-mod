# R1025 1.21.1 长矛空袭命中增伤/水平脱离 + 冲锋并行化（1.3.3 同号重打）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修复 / 体验补齐（1.21.1 专属；1.20.1 等开发者验收批准后再镜像）。
- **开工前读过**：`rounds/R1024-neo-spear-full-charge-ascent.md`、`SpearAirRaidBehavior.java`、
  `SpearAttackBehavior.java`、`SpearCoreCompat.java`、`MaidFlightCombatBehavior.java`、
  `MaidSpearSwingMixin.java`、`MealTaskGatingMixin.java`、`SpearHealMealGatingMixin.java`；
  以及实机日志 10:38 与 11:00–11:01、spearcore `SpearItem.onUseTick` / `SpearCondition` /
  `SpearStats` 字节码。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 实机日志定位 | 确认 11:00 每轮「接管控空」后约 1 秒就「退出长矛空袭独立链路」；确认旧链路在距敌 20 格、仍有俯冲余速时就点火，命中前没有水平段 | ☑ |
| 1 | 起飞缓冲 | 烟花/羽扇/激流/自推鞘翅点火后 12 tick 内不再因 `onGround()` 退出；缓冲期不消耗 `LAUNCH_LEFT`，离地后才正常计推力 | ☑ |
| 2 | 目标维持 | `FlightTargeting` 因活动范围/排班暂时返回 null 时回退 brain `ATTACK_TARGET`；只在目标死亡/超 50 格/友方时退出 | ☑ |
| 3 | 最终水平刺击 | 距敌 6 格或高度差 ≤0.8 时强制俯仰 0，并按比例压掉竖直速度；只有「高度差 ≤0.8 且实际竖直速度 ≤0.15」才点火，避免把残余俯冲矢量放大 | ☑ |
| 4 | 命中判断 | 只在目标 `lastHurtByMob == maid` 且时间戳变化时算命中；命中日志带 spearcore 同口径相对速度，便于核对增伤 | ☑ |
| 5 | 命中后水平 | 命中立即转 RECOVER：俯仰 0 + 竖直速度衰减；`|vy| ≤ 0.06` 且水平速度回落到滑翔常态（或 60 tick 上限）后才再次点火 | ☑ |
| 6 | 冲锋并行化 | `claimCharge` 不再 `stopUsingItem` 抢吃喝；地面链路在吃喝期间连朝向/寻路都不抢；删除长矛专用的两处餐食闸门 | ☑ |
| 7 | 挥臂动画 | 地面冲锋每 tick 置 `ATTACK_COOLING_DOWN`；`MaidSpearSwingMixin` 从「正在蓄力」放宽到「蓄力或长矛战斗链路仍在运行」 | ☑ |
| 8 | 编译与护栏 | Neo 编译 `EXITCODE=0`；全部护栏 `FAIL 0 / WARN 0`；`panel_coverage` 重新生成 | ☑ |
| 9 | 专服回归 | 测试服临时放入 `spearcore-1.21.1-neo-3.1.0`：`test_server` 与 `test_maid_load` 均 PASS，测试后移除 | ☑ |
| 10 | 部署与验收 | 只部署 1.21.1 实例；Forge 源码树与两个 Forge 实例一律不动；待开发者实机验收 | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

- 开发者原话（本轮）：
  「刚刚进入游戏实测，发现现有长矛空袭的2个问题：1.无法触发增伤2.俯冲时无法做到最终水平刺向敌人，或许可以加一条：
  击中敌人后强制将飞行方向改为水平，随后等矢量消耗完了才开启下一次飞升 同时让冲锋不影响吃东西等链路，让冲锋成为一个
  并行链路而非抢占。还是会触发挥臂动作」

## 评估

1. **增伤看不到，根因不是伤害公式**：R1024 实机日志 11:00:07「接管控空」→ 11:00:08
   「退出长矛空袭独立链路」。烟花推力要下一 tick 才把实体抬离地面，独立链路在缓冲期外仍按
   `onGround()` 立即退出，随后通用空袭接管并走收翅猛击/普通近战——长矛 `onUseTick` 的
   相对速度增伤根本没有机会结算。修法：点火后 12 tick 缓冲；缓冲期不烧 `LAUNCH_LEFT`；
   同时给目标解析加 brain 回退，避免索敌器因活动范围暂时返回 null 时半空自我解除。
2. **命中前没有水平段**：旧链路在 20 格、`|dy| ≤ 2` 就点火，此时机体仍有俯冲余速；
   烟花的推力方向取点火当拍视线，等于把残余下坠继续放大。修法：压平距离提前到 6 格，
   高度差 ≤0.8 或进入 6 格后强制俯仰 0，并用 `setDeltaMovement(y × 0.45)` 压掉竖直速度；
   点火条件增加「实际竖直速度 ≤0.15」。这样最后 6 格是真正的水平矛线。
3. **命中后必须强制水平**：新增 RECOVER 语义：命中归因成立的当 tick 立刻俯仰 0、衰减竖直速度；
   等 `|vy| ≤ 0.06` 且水平速度回落到滑翔常态（上限 60 tick）才转 RELAUNCH。穿出目标未命中
   也走同一段水平保持，避免带着俯冲矢量直接再点火。
4. **冲锋抢占吃喝**：旧 `claimCharge` 会 `stopUsingItem()` 把饭碗顶掉，且两处餐食 mixin
   在「有目标 + 主手矛」时直接禁止进食。修法：`claimCharge` 遇到非长矛使用动作直接返回
   false；地面链路在吃喝期间连朝向和寻路都不抢；删除 `SpearHealMealGatingMixin` 与工作餐里的
   长矛专用闸门。冲锋因此变成并行链路：吃饭优先占用 useItem，吃完下一 tick 自动续满。
5. **仍然挥臂**：TLM `MaidMeleeAttack` 会先 `swing` 再 `doHurtTarget`；只靠「正在蓄力」判断
   会漏掉吃喝结束、状态表仍在的那一拍。修法：地面链路每 tick 置 `ATTACK_COOLING_DOWN`
   按住 TLM 近战；`MaidSpearSwingMixin` 同时看「蓄力 / 攻击模式长矛链路 / 空袭长矛链路」。

## 改动文件

- `promaid_src_neo/com/maidsmart/combat/SpearAirRaidBehavior.java`（641 行）：起飞 12 tick
  缓冲；压平距离 6 格 + 竖直速度衰减；点火要求实际水平；命中转 RECOVER，等矢量耗尽再点火；
  穿出判定；目标解析回退 brain；命中日志带相对速度；退出日志带原因。
- `promaid_src_neo/com/maidsmart/combat/SpearAttackBehavior.java`（420 行）：每 tick 压制 TLM
  近战；吃喝期间不抢朝向/寻路；续矛改为等待原版使用动作结束。
- `promaid_src_neo/com/maidsmart/combat/SpearCoreCompat.java`（225 行）：`claimCharge` 不再
  抢非长矛 useItem；删除 `shouldReserveUse`。
- `promaid_src_neo/com/maidsmart/combat/MaidFlightCombatBehavior.java`（3406 行）：新增只读
  `launchTicksLeft` 供接管日志使用。
- `promaid_src_neo/com/maidsmart/mixin/MaidSpearSwingMixin.java`（47 行）：挥臂闸放宽到
  长矛战斗链路存续期间。
- `promaid_src_neo/com/maidsmart/mixin/MealTaskGatingMixin.java`（37 行）：删除长矛专用
  工作餐闸门，只保留 `misc.workUninterrupted`。
- `promaid_src_neo/com/maidsmart/mixin/SpearHealMealGatingMixin.java`（删除）：自愈餐/居家餐
  不再被长矛战斗挡住。
- `promaid_src_neo/mixins.promaid.json`：移除已删除的 mixin（common 82→81，另 20 client）。
- `tools/arch/arch_modules.py` 与 `normify-promaid/`：mixin 计数、行号与产物重新生成。
- `tools/deploy/deploy_neo.py`：版本号从陈旧的 1.3.0 同步到 1.3.3。
- `rounds/RELEASES.tsv`、发布文档与桌面文本：追加本轮说明。

## 验证

- Neo 编译：`EXITCODE=0`。
- 全部护栏：`FAIL 0 / WARN 0`；`check_mixin_targets` 校验 275 个注入点；`check_arch` 一致；
  `check_panel_coverage` 已重新生成（Neo 608 项）。
- 专服（测试服 mods 里临时放 `spearcore-1.21.1-neo-3.1.0.jar`）：
  `test_server.py neoforge1211` PASS；`test_maid_load.py neoforge1211` PASS；测试后已移除。
- Neo jar：18,126,436 字节，SHA256
  `A1C7F4682BD2F93595263D398D9EAC14515A9BD1C53E8B333011BE7DA192B7FC`。
- 已部署 1.21.1 实例，源包/目标包 SHA256 一致；Forge 源码树与两个 Forge 实例本轮零功能改动。
- 部署工具说明：`deploy_neo.py` 原先硬编码 1.3.0，误放了一个旧 jar；已删除该旧 jar 并把脚本
  同步到 1.3.3，最终目录只保留 `promaid-1.3.3-neoforge-1.21.1.jar`。

## 边界

- 仅 1.21.1 NeoForge；1.20.1 的镜像**等开发者验收批准后**再做。
- 冲锋仍然只在主手长矛 + 攻击/近战空袭任务下接管；未安装 spearcore 时所有判据恒 false。
- 吃喝期间长矛蓄力会暂时中断（useItem 只有一个槽位），吃完下一 tick 自动续满；这是刻意的
  「并行」语义，不是长矛被永久取消。
- 命中后的水平保持有 60 tick 上限：极端情况下矢量一直不消也会收尾再点火，避免贴地绕圈。
- 伤害/击退/疲劳仍由 spearcore 自己结算；本轮只保证「命中前是水平高速、命中后有水平保持」，
  不改它的伤害公式与相对速度阈值。
- `holdMaxCharge` 仍是每 tick 把使用进度钉在伤害窗口末尾；吃喝结束后续矛会立刻回到满蓄力。
- 版本号仍为 1.3.3（同号重打）。

## 验收

- 空袭（主手长矛 + 鞘翅 + 烟花，近战空袭）：
  1. 点火后应看到「接管控空（…推力 N tick…）」，且**不再**在 1 秒内出现「退出长矛空袭独立链路」；
  2. 爬升段应等到「上升矢量消去」；随后接近段最后 6 格应摆平，日志出现「水平贯穿点火」；
  3. 命中时应看到「长矛命中（相对速度 …）→ 强制水平，矢量耗尽后再点火」，相对速度应明显
     大于 0（满速时通常几十）；观察她命中后保持水平飞一段，再出现「水平矢量耗尽 → 再次点火」；
  4. 若没命中，应看到「穿出目标（未命中）→ 强制水平…」，同样水平保持后再点火。
- 地面攻击模式：
  1. 女仆吃东西/喝药时应能正常完成，不再被举矛打断；吃完后应自动续上满蓄力冲锋；
  2. 冲锋期间不应再出现挥臂动画；
  3. 仍保留「命中 → 同向 1 秒 → 后撤 → 再冲」的节奏。
- 卸载 `spearcore`：所有新增判据恒 false，行为与之前一致，无报错。
