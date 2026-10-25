# R1031 1.21.1 回退二次点火抬高度（保留全程滑翔）（1.3.3 同号重打）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修复（1.21.1 专属；1.20.1 等开发者验收批准后再镜像）。
- **开工前读过**：`rounds/R1030`、`SpearAirRaidBehavior.tickRelaunch` / `tickRecover` /
  `tickBoost`、`MaidFlightCombatBehavior.tryLaunchMelee`、玩家实机反馈。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 命中率回退 | 删除 RELAUNCH 的"目标上方 4 格 → 抬头 21°"高度保持，恢复平飞等待 | ☑ |
| 1 | 全程不解除飞行 | RELAUNCH 保留在 `isSkimPhase`；每 tick 维持滑翔、平飞、贴地托举、擦地救起 | ☑ |
| 2 | 二次点火时机 | 冲刺（BOOST，默认 20 tick = 1 秒）→ RECOVER → RELAUNCH 调通用起飞链，全在空中完成 | ☑ |
| 3 | 编译与护栏 | Neo 编译 `EXITCODE=0`；14 护栏 `FAIL 0 / WARN 0` | ☑ |
| 4 | 专服回归 | 测试服临时放入 `spearcore-1.21.1-neo-3.1.0`：`test_server` 与 `test_maid_load` 均 PASS，测试后移除 | ☑ |
| 5 | 部署与验收 | 只部署 1.21.1 实例；Forge 源码树与两个 Forge 实例一律不动 | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

- 开发者原话：
  「拦住了！
  但是目前在引入二次点火不再先落地后女仆命中率大大降低了。先回退到原来，我想要的效果就是女仆在执行了冲刺后1s，
  不解除飞行，开始二次起飞。也就是说整个链路下来，女仆是从来没有解除过飞行状态的。」

## 评估

- R1030 在 `tickRelaunch` 里按"低于目标上方 4 格就抬头 21°"维持高度。这个额外爬升会改变
  下一轮进场的起始高度：爬升段再叠加 `maxRiseForDistance` 的封顶后，进场曲线与点火窗口
  容易错位，实机表现就是命中率明显下降。本轮把这段抬头/高度保持整段删掉。
- 保留 R1030 中真正需要的部分：
  - `RELAUNCH` 仍在 `isSkimPhase` 里，`canStillUse` / `tick` 都不会因为 `onGround` 退出链路；
  - 等待段每 tick `MaidFlightKit.setGliding(true)`（`tick` 顶层已做）、`levelOut` 平飞、
    `groundSkimLift` 限制下降、擦地时 `recoverFromGround` 救起；
  - `tryLaunchMelee` 每 tick 尝试，冷却/燃料就绪即在空中二次点火。
- 时间口径：冲刺那一记烟花后 `BOOST` 默认 20 tick（1 秒），随后 `RECOVER` 等矢量收平，
  再进 `RELAUNCH` 点火。整段没有落地、没有退出独立链路。

## 改动文件

- `promaid_src_neo/com/maidsmart/combat/SpearAirRaidBehavior.java`（913 行）：
  删除 `RELAUNCH_HOLD_RISE` 与抬头分支；`tickRelaunch` 恢复平飞 + 贴地托举 + 擦地救起。
- `tools/arch/arch_modules.py` 与 `normify-promaid/`：行号同步。
- R1030 的描边硬绑定与手臂动作拦截不变。

## 验证

- Neo 编译：`EXITCODE=0`。
- 全部护栏：`FAIL 0 / WARN 0`；`check_mixin_targets` 校验 277 个注入点。
- 专服（测试服 mods 里临时放 `spearcore-1.21.1-neo-3.1.0.jar`）：
  `test_server.py neoforge1211` PASS；`test_maid_load.py neoforge1211` PASS；测试后已移除。
- Neo jar：18,137,174 字节，SHA256
  `E5A819B4A2C0A15E9C2BF0DFAF98CBBFB35BC54D60A881A97CF9C0B13FEF8A1C`。
- 已部署 1.21.1 实例，源包/目标包字节数一致（18,137,174）；目录内只有一个 promaid 包：
  `promaid-1.3.3-neoforge-1.21.1.jar`。Forge 源码树与两个 Forge 实例本轮零改动。

## 边界

- 仅 1.21.1 NeoForge；1.20.1 的镜像**等开发者验收批准后**再做。
- 等待段不再主动爬高，因此低空冲刺后仍可能贴近地面滑翔；贴地托举保证不落地、不退出链路。
- 若烟花冷却/燃料都不足超过 40 tick，仍会交回通用链路（这是原有兜底，不是"落地解除"）。
- 版本号仍为 1.3.3（同号重打）。

## 验收

1. **命中率**：连续两三轮空袭，第二轮起的长矛命中应恢复到 R1029 的水平；
   不应再出现为拉高度而改变进场、导致点不着火。
2. **全程滑翔**：冲刺后约 1 秒开始第二次点火，整段不应出现落地/解除滑翔；
   低空时会贴着方块平飞，随后在空中点火。
3. **手臂**：上一轮的拦截保持有效，蓄力/追击/后撤无动作，只有命中那一下摆臂。
