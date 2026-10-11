# 实测八百九十九 phase② 注释去历史：两块最大 mixin 类头（第 7 批 · 两树）

> 跑道：纯注释。承接 `R898`，继续 `DEBT.md` §3「② 注释去历史」。

## 改法
- `MaidGeckoSpinDedupMixin`（57/56 行）与 `MaidSwimGlideMixin`（52/58 行）类头改写到 ≤15 行（两树合计 4 块）。
- **保留硬约束**：Gecko 激流"TLM 那对同轴反号旋转把原版那份抵消成单位矩阵"的完整推理 + `require = 0` 的理由 + 覆盖范围（Gecko 走此渲染器、YSM 管不到）；滑翔游泳动画"TLM 覆写丢了 FALL_FLYING 分支 + updateSwimming 每 tick 清除"两条原因 + 不动 pose 的判定箱理由 + 判据三层同口径（`isFlightVisual && !onGround()`）与"滑翔位是闪的"根因。
- **删除**：`实测NNN` 时间线、逐条 `<li>` 复述、`<pre>` 反编译片段（要点已并入正文）。

## 触及文件（两树）
`MaidGeckoSpinDedupMixin` `MaidSwimGlideMixin`。

## 验证
- 全局 `check_comment_style`：**1915 → 1911**（−4）。
- 两树编译 **EXITCODE=0**；未部署、未跑专服回归。

## 验收
- 状态：`未验收`。

## 台账
- 属「② 注释去历史」推进（1915 → 1911）。本轮起本 session 累计 **2103 → 1911**（−192）。
- 未 commit / push。
