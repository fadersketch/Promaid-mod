# 实测八百九十五 phase② 注释去历史：单块文件批量瘦身（第 3 批 · 两树）

> 跑道：纯注释（不改行为、不改配置 / i18n / 版本）。承接 `R894`，继续 `DEBT.md` §3 六阶段「② 注释去历史」，仍是**批量清单块文件**。

## 需求 / 来源
- 开发者「继续」：按 `DEBT.md` §3 六阶段清理顺序推进，本轮续 **阶段② 注释去历史**。

## 改法
- 20 个「1 块」文件（两树合计 ~40 块；`MaidBdProbeCommand` 的 Neo 侧本就 ≤ 上限、无块）逐块改写到 `DEV_GUIDE.md` §1 上限（类 ≤15 行 / 成员 ≤8 行）。
- **保留**：类职责与硬约束（半透明渲染必须换 RenderType 且只对第一人称生效、激流旋转特效是 RenderType 图层而非粒子、SpinLift 枢轴抬升几何、`BombPlacement.removePlaced` 的区块加载 hazard、`BuildShieldGuard` 的 `COMBAT_ACTIVE_TAG`/`PREV_TASK_TAG` 护盾口径、战斗模式分类表"改什么不改什么"、战斗感知自检三条判据与不留痕、`CombatTaskCompat` 的 `isWeapon` 契约与切入预检、`MaidBroomTask` 的 `IRangedAttackTask` 静默 no-op 证据、空袭牵引绳三链路让位与 home 闸、`message_id` 免疫口径与大运双闸、仆从坐骑伤害转移的环境伤害豁免、`MaidBdRuleCommand` 的"绝不能自造未注册 ArgumentType"坑等）。
- **删除**：`v x 实测NNN：旧版…这一版…` 的变更史；出处压成一句。

## 触及文件（两树）
`MaidGhostRender` `MaidSpinAttackEffect` `SpinLift` `BombPlacement` `BuildShieldGuard` `CombatModeTable`
`CombatSenseCheck` `CombatTaskCompat` `GunnerTetherNetworking` `MaidBroomTask` `MaidFlightRecall`
`MaidFlightWallGuard` `ServantMountDamageTransfer` `MaidBdProbeCommand` `MaidBdRuleCommand`
`MaidGoetyFlyCommand` `MaidModeCompat` `MaidItemNames` `ApiQuotaManager` `PerceptionManager`。
- 无新配置项 / 新 i18n key / 新 mixin；无 Java 逻辑改动。
- `MaidBdRuleCommand` 两树块型不同（Forge 是类头块、Neo 是 `test` 成员块），按各自块型各改一处。

## 验证
- 全局 `check_comment_style`：**2026 → 1987**（−39）。
- 终态 `{'LONG-BLOCK': 1987}`，无 `QUOTE-SPEECH` / `BANNED` 自引入。
- 两树 `compile_promaid.bat` / `compile_neo.bat` → **EXITCODE=0**。
- 未部署、未跑专服回归。

## 验收
- 状态：`未验收`。

## 边界（没动什么）
- **只动注释**；未动其它文件的 LONG-BLOCK；未碰 `MaidSmartConfig` 的 `.comment(...)`。
- `MaidBdProbeCommand` Neo 侧无该类注释块（本就合规），本轮只改了 Forge 侧——镜像性受既有分叉影响（见 `R892` 备注）。
- **未 commit / push**（按 `WORKFLOW.md` §12）。

## 台账
- 属「② 注释去历史」推进；进度以 `check_comment_style` 计数为准（2026 → 1987）。
