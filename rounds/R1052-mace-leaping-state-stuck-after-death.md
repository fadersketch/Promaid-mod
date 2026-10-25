# R1052 重锤跃起状态在死亡/复活后残留（女仆再也不使用风弹）

## 排班（开工前先填，硬门槛 —— `WORKFLOW.md` §1）

- **跑道**：A 修 bug —— 1.21.1 NeoForge + 1.20.1 Forge 同规。
- **开工前读过**：`rounds/R1051`；`MaidMaceSmashBehavior`（两树）；`ProMaidExtension`
  的死亡/离场/入世事件；客户端 `logs/promaid.log` 与 `promaid.log.old`。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 从日志定位 | 找到“最后一次重锤门/重锤”与之后完全静默的分界点 | ★ |
| 2 | 根因 | 静态 `LEAPING` 在死亡时未清，`checkExtraStartConditions` 第一道门永久 false | ★ |
| 3 | 两树改 | Neo 补 `forget/clearAll`；两树死亡/离场/入世接线 | ★ |
| 4 | 编译与护栏 | 两树 `EXITCODE=0`；14 护栏 `FAIL 0` | ★ |
| 5 | 专服回归 | `test_server` / `test_maid_load` 两套 PASS | ★ |
| 6 | 打包装机 | 4 个目标 `match=True` | ★ |
| 7 | 交开发者验收 | 跃起中击杀→复活→再给重锤+风弹，能重新触发猛击 | ☐ |

## 需求 / 来源

- 开发者反馈：“发现一个bug，女仆突然不会使用风弹了”。
- 客户端日志证据（`D:\.minecraft\versions\1.21.1-NeoForge_21.1.250\logs`）：
  - `promaid.log.old` 23:37:05 / 23:37:13 各一次 `[重锤] ... 借风弹起跳猛击`；
  - 23:37:13 那次起跳后立刻 `[装备离开] MAINHAND 失去 1xminecraft:mace`（女仆死亡，
    装备被清空）；
  - 此后整场游戏（含新日志 `promaid.log` 到 23:58）**没有任何 `[重锤门]` 或 `[重锤]`**，
    而 23:55:47 她重新拿到重锤、背包里还有 64 枚风弹。
  - 旧日志死亡前有 83 条 `[重锤门]` 诊断，死亡后为 0。

## 评估

- **级别**：P1——不崩游戏，但重锤+风弹猛击这个战斗能力在死亡/复活后永久失效，
  直到重启游戏（静态表随 JVM 清空）才恢复；玩家看到的是“突然不会用了”。
- **根因**：`MaidMaceSmashBehavior` 用静态 `LEAPING`（按 UUID）标记“正在跃起”。
  `checkExtraStartConditions` 第一道门就是 `LEAPING.contains(uuid) → return false`，
  而且这里**刻意不打诊断**。女仆在空中/跃起中死亡时，行为走不到 `stop()`，
  UUID 留在 `LEAPING`；TLM 复活后 UUID 不变，于是后续每一 tick 都在第一道门静默返回。
  现有统一清理点只清了 `FORCED_CLUTCH`（`AiMemoryManager` 的离场清理与启动兜底），
  没清 `LEAPING/AIR_TICKS/NEXT_ALLOWED`。
- **同类点**：飞行作战、长矛冲锋等静态表早已有 `forget` 并在离场/入世接线；
  重锤这张表是漏网。Forge 树已定义 `forget/clearAll`，但同样没接到转移事件上。

## 改法

- Neo `MaidMaceSmashBehavior`：新增 `forget(UUID)`（清 `LEAPING / AIR_TICKS /
  NEXT_ALLOWED / FORCED_CLUTCH / LAST_GATE_LOG`）与 `clearAll()`；删除只清
  `FORCED_CLUTCH` 的旧方法。
- Neo `ProMaidExtension`：
  - `onMaidDeath` 清一次；
  - `clearCombatStateOnTransition`（离场/入世共用）清一次；
  - 服务端启动兜底从 `clearForcedClutch()` 改为 `clearAll()`。
- Neo `AiMemoryManager` 的离场清理：`forgetForcedClutch` → `forget`。
- Forge `ProMaidExtension`：`onMaidDeath` / `onMaidJoin` / `onMaidLeaveLevel` 三处
  接线 `MaidMaceSmashBehavior.forget(uuid)`（类里本来就有 `forget/clearAll`）。
- 不做“每 tick 扫全表”的兜底：转移事件已覆盖死亡、收魂、区块卸载、重新入世；
  真出现 brain 重建残留时再按 `LAST_GATE_LOG` 式的时间戳自愈补。

## 触及文件

- `promaid_src_neo/com/maidsmart/combat/MaidMaceSmashBehavior.java`
- `promaid_src_neo/com/maidsmart/ProMaidExtension.java`
- `promaid_src_neo/com/maidsmart/memory/AiMemoryManager.java`
- `promaid_src/com/maidsmart/ProMaidExtension.java`
- Forge `MaidMaceSmashBehavior` 已有 `forget/clearAll`，本轮不改。
- 无新配置项、无新 i18n、无新 mixin。

## 验证

- Forge `compile_promaid`：`EXITCODE=0`；Neo `compile_neo`：`EXITCODE=0`。
- 14 护栏全部 `FAIL 0`（`check_arch` / `check_mixin_targets` 等均无新增问题）。
- `test_server 1201`：PASS；`test_server neoforge1211`：PASS。
- `test_maid_load 1201`：PASS；`test_maid_load neoforge1211`：PASS（专服 summon 女仆成功）。
- **反证**：修复前日志里死亡后的 `[重锤门]` 为 0；修复后死亡/离场/入世三条路径都会
  清掉 `LEAPING`。本轮未在实机复现“跃起中击杀→复活”的完整时序（需要定点操作），
  最终以开发者在客户端复验为准。
- 产物：
  - Forge `patched/promaid-1.3.3-forge-1.20.1.jar` 18,081,949 字节，
    SHA256 `1F2D4507884A409524638F0FE331A8D9584D36A8296E66BF50A48EF6A69C66B4`
  - Neo `patched/promaid-1.3.3-neoforge-1.21.1.jar` 18,174,706 字节，
    SHA256 `CFA7B2F65645B6933687643CD7D7D8CE6A88F70CA696948232418B2AC9C8D2E3`
- `deploy_all.py`：4 个实例全部 `match=True`；部署前确认无 java 进程。

## 验收

- 状态：`未验收`。验收人 = 开发者；验法：
  1. 让女仆持重锤 + 风弹起跳（`logs/promaid.log` 应出现 `[重锤] ... 借风弹起跳猛击`）；
  2. 在她跃起/滞空时击杀；
  3. 复活后重新给她重锤 + 风弹，贴近敌人；
  4. 确认 `[重锤]` 再次出现、风弹数量减少；若仍不触发，看 `[重锤门]` 是否恢复了诊断输出。

## 边界（没动什么）

- 不动重锤的伤害结算、起跳数值、落地缓冲、风弹消耗数量。
- 不动自动装备选择逻辑；如果主手被枪/剑占着，重锤猛击本来就不会触发（设计如此）。
- 不动飞行/长矛的静态清理（它们已有自己的 `forget` 接线）。
- 未处理“brain 重建（非死亡/离场）时跃起状态残留”的极端路径；本轮以转移事件覆盖为主。

## 台账

- 本轮不清债；未新增债。
