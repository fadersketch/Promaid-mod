# R1029 1.21.1 回退限飞高度（保留贴地保护）+ 长矛挥臂改压 DATA_ARM_RISE（1.3.3 同号重打）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修复（1.21.1 专属；1.20.1 等开发者验收批准后再镜像）。
- **开工前读过**：`rounds/R1027`、`rounds/R1028`、`SpearAirRaidBehavior`、
  `MaidSpearSwingMixin`、TLM 的 `MaidAttackStrafingTask` 反编译（`start()` 调
  `setSwingingArms(true)`，`stop()` 调 false）、`EntityMaid.DATA_ARM_RISE` 同步字段。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 高度回退 | 恢复 R1026 的 `maxRiseForDistance` 爬升封顶与进场可行性门；删除 10 格限飞与 `AIM_LEVEL_DIST` | ☑ |
| 1 | 保留贴地保护 | `GROUND_SKIM_*`、`isSkimPhase`、擦地救起、onGround 容错全部保留 | ☑ |
| 2 | 挥臂根因 | 不是 `swing()`：TLM 攻击任务用 `DATA_ARM_RISE` 让模型全程举臂，`swing` 闸管不到 | ☑ |
| 3 | 压掉举臂姿态 | 长矛地面冲锋/空袭每 tick `maid.setSwingingArms(false)`；命中那一下仍由 spearcore 二参 swing 放行 | ☑ |
| 4 | 编译与护栏 | Neo 编译 `EXITCODE=0`；14 护栏 `FAIL 0 / WARN 0`；架构行号与 `normify-promaid/` 重新生成 | ☑ |
| 5 | 专服回归 | 测试服临时放入 `spearcore-1.21.1-neo-3.1.0`：`test_server` 与 `test_maid_load` 均 PASS，测试后移除 | ☑ |
| 6 | 部署与验收 | 只部署 1.21.1 实例；Forge 源码树与两个 Forge 实例一律不动 | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

- 开发者原话：
  「还是回退吧，只保留俯冲贴地保护。
  冲锋时不停挥臂还是没修好」

## 评估

1. **高度实验回退**：R1027 把爬升改成 10 格硬上限、R1028 又把进场曲线前移 6 格去补点火窗口，
   实机仍然出现"高度上去了但打不到怪"。按开发者要求整段回退到 R1026 的低空进场：
   爬升重新由 `maxRiseForDistance` 按当前距离/水平速度封顶；进场重新要求
   `rise <= feasibleRise + 1` 才规划下降曲线。R1027 的俯冲贴地保护（限速/减半下压/强制平飞/
   擦地托举/onGround 容错）原样保留。
2. **挥臂根因**：TLM 的攻击任务（`MaidAttackStrafingTask` / `MaidAttackStrafingAnyItemTask`）
   在 `start()` 里调用 `EntityMaid.setSwingingArms(true)`，写的是同步字段 `DATA_ARM_RISE`；
   模型据此在整个任务期间播放举臂/挥臂姿态。R1025/R1028 拦的是 `LivingEntity.swing`，
   与这条链路无关，所以"不停挥臂"一直没修好。现在长矛蓄力期间每 tick 把
   `swingingArms` 压回 false；真正造成伤害的那一拍仍由 spearcore 的二参 `swing` 触发一次摆臂。

## 改动文件

- `promaid_src_neo/com/maidsmart/combat/SpearAirRaidBehavior.java`（906 行）：
  恢复 `maxRiseForDistance` 与进场可行性门；删除 10 格限飞与 `AIM_LEVEL_DIST`；
  保留全部贴地保护；tick 压掉 `swingingArms`。
- `promaid_src_neo/com/maidsmart/combat/SpearAttackBehavior.java`（439 行）：
  start/tick 压掉 `swingingArms`。
- `tools/arch/arch_modules.py` 与 `normify-promaid/`：行号同步。
- R1028 的点火窗口改动已回退；其描边寿命与挥臂闸（一参全吞、二参伤害窗口放行）保留。

## 验证

- Neo 编译：`EXITCODE=0`。
- 全部护栏：`FAIL 0 / WARN 0`；`check_arch` 一致；`check_mixin_targets` 校验 275 个注入点。
- 专服（测试服 mods 里临时放 `spearcore-1.21.1-neo-3.1.0.jar`）：
  `test_server.py neoforge1211` PASS；`test_maid_load.py neoforge1211` PASS；测试后已移除。
- Neo jar：18,130,796 字节，SHA256
  `B6E1610F8221CB53443081A9493C70D9F9CF32611A33C9B6A5928CFC28A83BC8`。
- 已部署 1.21.1 实例，源包/目标包字节数一致（18,130,796）；目录内只有一个 promaid 包：
  `promaid-1.3.3-neoforge-1.21.1.jar`。Forge 源码树与两个 Forge 实例本轮零改动。

## 边界

- 仅 1.21.1 NeoForge；1.20.1 的镜像**等开发者验收批准后**再做。
- 回退后飞行高度重新取决于接敌距离：近距离接敌仍可能只有 2~3 格高度，这是回退的代价。
- 贴地保护只避免"俯冲擦地中断飞行"，不改变低空进场几何。
- `setSwingingArms(false)` 是每 tick 覆盖；TLM 任务 start 的那一拍仍可能短暂置 true，
  但不会持续。
- 版本号仍为 1.3.3（同号重打）。

## 验收

1. **高度/命中**：近战空袭应恢复到 R1026 的进场观感（低空、能点火、能打出长矛伤害）。
2. **贴地保护**：平地俯冲不再因接地出现「退出长矛空袭独立链路」；擦地后能继续平飞点火。
3. **挥臂**：攻击模式持矛冲锋时，蓄力/追击/后撤全程手臂保持不举；只在长矛实际造成伤害
   的那一下出现一次摆臂。
4. 卸载 `spearcore`：新增判据恒 false，行为与之前一致，无报错。
