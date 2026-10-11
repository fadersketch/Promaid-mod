# 实测八百九十八 phase② 注释去历史：大体量 mixin 类头瘦身（第 6 批 · 两树）

> 跑道：纯注释（不改行为、不改配置 / i18n / 版本）。承接 `R897`，继续 `DEBT.md` §3 六阶段「② 注释去历史」。

## 需求 / 来源
- 开发者「继续」：按 `DEBT.md` §3 六阶段清理顺序推进，本轮续 **阶段② 注释去历史**。

## 改法
- 5 个大体量 mixin 类头（两树合计 9 块）改写到 `DEV_GUIDE.md` §1 上限（类 ≤15 行）。
- **保留**：类职责与硬约束（平A横扫的 ratio>0 触发条件与 1.21.1 属性改造、远程开界面只放行距离且 `@At("RETURN")` 而非 `@Redirect` 的理由、激流换招"绝不吞掉任何一记"与静态回调血债、模型包排序丢包的字节码根因、时机举盾两次修正后的定稿口径）。
- **删除**：`v x 实测NNN：旧版…这一版…` 的变更史与逐条 `<li>` 复述；出处压成一句。

## 触及文件（两树）
`MaidSweepMixin`（类头）`MaidContainerRemoteOpenMixin` `MaidMeleeRiptideMixin` `MaidModelPackSortMixin` `MaidShieldTimingMixin`。
- 无新配置项 / 新 i18n key / 新 mixin；无 Java 逻辑改动。

## 验证
- 全局 `check_comment_style`：**1924 → 1915**（−9）。
- 终态 `{'LONG-BLOCK': 1915}`，无 `QUOTE-SPEECH` / `BANNED` 自引入。
- 两树 `compile_promaid.bat` / `compile_neo.bat` → **EXITCODE=0**。
- 未部署、未跑专服回归。

## 验收
- 状态：`未验收`。

## 边界（没动什么）
- **只动注释**；`MaidGeckoSpinDedupMixin`(57/56) 与 `MaidSwimGlideMixin`(52/58) 两块体量最大、含完整矩阵推导与多层判据，留下一轮单独处理。
- **未 commit / push**（按 `WORKFLOW.md` §12）。

## 台账
- 属「② 注释去历史」推进；进度以 `check_comment_style` 计数为准（1924 → 1915）。
