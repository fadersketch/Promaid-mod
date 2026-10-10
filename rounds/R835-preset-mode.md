# 实测八百三十五 手册「调试 / 预设模式」：DIY / 默认一键切（两树）

> 追记：本轮在 `WORKFLOW.md` 立规之前就做完了，按新模板补记，作为第一条样板。

## 需求 / 来源
- 来源：issue #37 第 1 条（定制化——玩家得适应用法，而不是用法适配玩家）之后用户提出的方案：
  手册首页加调试入口，一键把本模组"介入 TLM 女仆行为"的改动全部静默（DIY），另一档恢复出厂推荐（默认）。

## 评估
- **跑道**：B 新功能（新增了两档配置与一个页面，且新增了 3 个开关）。
- 前置四问：
  1. **重复性**：无同类实现；DIY 本身是"让位"而不是"多加一层"。
  2. **语义**：**不改既有语义**——3 个新开关默认 true（与改动前一字不差），DIY 只是把既有开关批量关掉；
     另有一处反语义项（`combat.fleeWithSaveItem`）在 DIY 下显式置 true 以回到 TLM 原版惊慌。
  3. **耦合**：把 5 处"此前没有开关的介入"补齐——新增 3 个可见开关（`misc.pickupValueReorder` /
     `tlmTeleportExempt` / `misc.scheduleRangeFloor`）接进 4 个 mixin；另 2 处由既有 `combat.*` 总闸覆盖。
     **没有**用 `isDiy()` 硬闸，理由：硬闸会让 DIY 玩家无法单独恢复某一项。
  4. **性能**：预设页那句"会改动 N 项"从每帧计算改为进页算一次（`planDiy` 要读 500+ 配置项）。
- 先查：`ARCHITECTURE-SWITCHES.md` 当时还不存在（本轮后新增）；`BUG_DEBT` BD-05（蹲下迎战）与本轮同源，
  本轮给的是"回退口径"，不是"蹲下=炮塔"的口径，故 BD-05 仍未清。

## 改法
- 新增 `MaidConfigPreset`：反射枚举 `MaidSmartConfig` 全部 `static ConfigValue`（以后加配置项自动纳入），
  KEEP / SKIP / FORCE_ON / VALUES / RESET_KEEP 五张路径分类表；`applyDiy()` / `applyFactoryDefault()`
  写值后走 `persistConfigQuietly()` + 刷新矿表/木材表/烧制表缓存。
- 手册首页第 6 个入口「调试 / 预设模式」（行高按窗口自适应）+ 新页（两按钮各带二次确认、显示当前模式与改动条数）；
  配置面板 `misc` 组加 4 行（3 个新开关 + 只读的当前预设模式）。
- 新增护栏 `tools/verify/check_preset.py`（分类表与配置项一致性 + mixin 闸是否真引用到）。

## 触及文件
- 两树：`MaidSmartConfig` / `MaidConfigPreset`（新）/ `PromaidConfigScreen` / `BlueprintBookScreen` /
  4 个 mixin（`MaidPickupPriorityMixin` / `ScheduleRangeMixin` / `MaidTeleportPreserveMixin` / `FollowPreserveMixin`）。
- 新配置项 4 个；新 i18n key 两树各 32 条；lang 4 份各 +4 键。

## 验证
- 两树 `javac EXITCODE=0`；jar 自检 `MISSING: none`、lang json OK（entries 1080 / 1083）。
- 临时自测（loader 无关的反射 harness，已删）：枚举 575 / 581 项；`planDiy` 147 / 151；
  `applyDiy` 实改 148 / 152 = plan + 模式标记；DIY 后再算 plan = 0（幂等）；切回默认逐项与 `getDefault()` 相等、mismatch 0。
- 护栏：`check_preset` FAIL 0（WARN 2 = 既有的 `broom.enable` 撞键）；`check_changelog` 100,148 与
  `check_comment_style` 2,260 均无新增；i18n 32 条 key 与源码逐字节核对 0 失败。
- **未做**：未部署 4 个目标、未跑专服回归（等用户说"部署"）。

## 边界（没动什么）
- 不动 issue #37 的其它点（配置耦合、版本号、冗余功能、周期检测开销）。
- 不动已发布产物 / `store/`（除 changelog）/ `_tmp_pub/`；不 commit / push。
- DIY 的固有边界（已写进页面说明）：我们的挖矿/伐木/建造任务仍在任务表里（TLM 无同类原生任务可退回），
  但任务内加强项全关；AI/语音/手册等纯附加功能 DIY 不动。

## 台账
- 新增债：`TD-07`（i18n 表漂移，本轮核对时发现约 62 条存量）。
- 未清债（本轮未动）：`BD-04` / `BD-05` / `BD-06` / `TD-01` / `TD-02` / `TD-03`。
- 配额：本轮为 FEATURE 轮，未清 P1 债 → **不满足** `WORKFLOW.md` §8 配额（立规后首次违规记录在案；
  下轮起按新规执行：先清 `BD-04` 或同轮带上）。
