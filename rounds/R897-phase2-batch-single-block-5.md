# 实测八百九十七 phase② 注释去历史：单块文件批量瘦身（第 5 批 · 两树）

> 跑道：纯注释（不改行为、不改配置 / i18n / 版本）。承接 `R896`，继续 `DEBT.md` §3 六阶段「② 注释去历史」。

## 需求 / 来源
- 开发者「继续」：按 `DEBT.md` §3 六阶段清理顺序推进，本轮续 **阶段② 注释去历史**。

## 改法
- 14 个「1~2 块」mixin 文件（两树合计 27 块）改写到 `DEV_GUIDE.md` §1 上限（类 ≤15 行 / 成员 ≤8 行）。
- **保留**：类职责与硬约束（危险方块三格判定与起点格不拦、飞行俯角为什么不走 TLM 扩展点、跟随每 tick 重断言的注入点与处理器签名血债、registerAnimationState 的 priority=1 取舍、扫帚/女仆半透明为何注入基类与描述符必须 LivingEntity、金色描边为何改 getTeamColor、Goety 推进为何要清 WALK_TARGET、拾取价值重排、玩家伤害五种模式、旋转突进的空实现补刀、跳劈不横扫的乘客豁免、移动抑制四种标记三种强度）。
- **删除**：`v x 实测NNN：旧版…这一版…` 的变更史；出处压成一句。

## 触及文件（两树）
`MaidConfigGoetyMixin`(Forge) `MaidDangerPathMixin` `MaidFlightDiveTiltMixin` `MaidFollowOwnerTickMixin`
`MaidFreeFlightAnimMixin`(Forge) `MaidGhostBroomMixin` `MaidGhostTetherMixin` `MaidGlowGoldMixin`
`MaidGoetyMoveSuppressMixin` `MaidPickupPriorityMixin` `MaidPlayerDamageMixin` `MaidSpinAttackTouchMixin`
`MaidSweepMixin`（成员块）`MaidMoveSuppressMixin`（类头 + 成员）。
- 无新配置项 / 新 i18n key / 新 mixin；无 Java 逻辑改动。

## 验证
- 全局 `check_comment_style`：**1951 → 1924**（−27）。
- 终态 `{'LONG-BLOCK': 1924}`，无 `QUOTE-SPEECH` / `BANNED` 自引入。
- 两树 `compile_promaid.bat` / `compile_neo.bat` → **EXITCODE=0**。
- 未部署、未跑专服回归。

## 过程修正（镜像性）
- 本轮起批量脚本改成**按树分别出稿**（`f`/`n`/`b` 三种归属）：涉及 SRG↔Mojmap 方法名与"1.20.1 差异"段落的类头，Forge / Neo 各写一份；仅 `MaidFlightDiveTiltMixin` 误用共享稿把 SRG 名带进 Neo，已即时改正（Neo 侧改为"本树官方名 {@code setupRotations} / forge 树 SRG {@code m_7523_}"）。

## 验收
- 状态：`未验收`。

## 边界（没动什么）
- **只动注释**；未动其它文件的 LONG-BLOCK（`MaidSweepMixin` 的类头块、`MaidContainerRemoteOpenMixin`、`MaidGeckoSpinDedupMixin`、`MaidMeleeRiptideMixin`、`MaidModelPackSortMixin`、`MaidShieldTimingMixin`、`MaidSwimGlideMixin` 等大体量块留下一批）；未碰 `MaidSmartConfig` 的 `.comment(...)`。
- `MaidConfigGoetyMixin` / `MaidFreeFlightAnimMixin` 仅 Forge 有该类注释块（Neo 侧本就合规）。
- **未 commit / push**（按 `WORKFLOW.md` §12）。

## 台账
- 属「② 注释去历史」推进；进度以 `check_comment_style` 计数为准（1951 → 1924）。
