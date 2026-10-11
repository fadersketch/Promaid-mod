# 实测八百九十六 phase② 注释去历史：单块文件批量瘦身（第 4 批 · 两树）

> 跑道：纯注释（不改行为、不改配置 / i18n / 版本）。承接 `R895`，继续 `DEBT.md` §3 六阶段「② 注释去历史」。

## 需求 / 来源
- 开发者「继续」：按 `DEBT.md` §3 六阶段清理顺序推进，本轮续 **阶段② 注释去历史**。

## 改法
- 20 个「1 块」文件（两树合计 ~36 块）改写到 `DEV_GUIDE.md` §1 上限（类 ≤15 行 / 成员 ≤8 行）。
- **保留**：类职责与硬约束（大运 BGM 的"客户端自播 + 服务端说到停才停"、主动对话 7 阶段与配额口径、回复反馈学习三能力、滑翔鞘翅动画"模型没有该动画时恒 false"、仿创造飞行事件挂载与 per-maid 开关网络层、Home 巡逻呆立三个断点、记忆审计/事件策略/召回闸门/分词器四件套、粉色火焰"只在她那一炸的窗口内换"、外力归因与友军风免总闸、EntityTickEvent 的 1.20.1 替代、重力冻结、对话语言"提示词强制 + 输出二次检测"、配置界面一行开关的 TaskData 崩服坑与无 refmap 反射）。
- **删除**：`v x 实测NNN：旧版…这一版…` 的变更史；出处压成一句。

## 触及文件（两树）
`client/DaYunMusicPlayer` `dialogue/ProactiveDialogueManager` `dialogue/ProactiveStage` `dialogue/ReplyFeedbackTracker`
`flight/MaidFreeFlightAnimState` `flight/MaidFreeFlightHandler`(Forge) `flight/MaidFreeFlightNetworking`
`follow/HomePatrolHandler` `memory/AiMemoryAudit` `memory/AiMemoryEventPolicy` `memory/AiMemoryRecallGate`
`memory/AiMemoryTokens` `mixin/BaseFireBlockPinkMixin` `mixin/EntityMoveToMarkMixin` `mixin/EntitySetDeltaMovementMixin`
`mixin/EntityTickBlameMixin` `mixin/EntityTickPostMixin`(Forge) `mixin/GravityFreezeMixin` `mixin/MaidChatLanguageMixin`
`mixin/MaidConfigFreeFlightMixin`。
- 无新配置项 / 新 i18n key / 新 mixin；无 Java 逻辑改动。

## 验证
- 全局 `check_comment_style`：**1987 → 1951**（−36）。
- 终态 `{'LONG-BLOCK': 1951}`，无 `QUOTE-SPEECH` / `BANNED` 自引入。
- 两树 `compile_promaid.bat` / `compile_neo.bat` → **EXITCODE=0**。
- 未部署、未跑专服回归。

## 过程修正（镜像性）
- 批量脚本用了统一的「两树共写一份」文本，导致 5 处**树专属断言被写进 Neo 镜像**（SRG 方法名 `m_8119_`/`m_213897_`/`m_20256_`/`m_7678_`、`1.20.1 精简版` 标题、`{@link SimpleChannel}`）。已用一次性脚本把 Neo 侧那 5 处改回官方名 / NeoForge 写法（`CustomPayload`+`PayloadRegistrar`），Forge 侧不动。**教训**：涉及 SRG↔Mojmap 映射名与平台落法的类头注释，必须**按树分别出稿**，不能共用一份文本。

## 验收
- 状态：`未验收`。

## 边界（没动什么）
- **只动注释**；未动其它文件的 LONG-BLOCK；未碰 `MaidSmartConfig` 的 `.comment(...)`。
- `MaidFreeFlightHandler` / `EntityTickPostMixin` 仅 Forge 有该类注释块（Neo 侧本就合规或文件不存在）。
- **未 commit / push**（按 `WORKFLOW.md` §12）。

## 台账
- 属「② 注释去历史」推进；进度以 `check_comment_style` 计数为准（1987 → 1951）。
