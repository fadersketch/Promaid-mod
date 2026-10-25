# 实测一千〇一十 1.20.1 潜行判定 SRG 误用（m_6040_ → m_6144_）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）
- **跑道**：A 修 bug —— 1.20.1 Forge 树专属；1.21.1 侧同一功能正常。
- **开工前读过**：`R999`（描边 + 强制回收的完整历史：双手空闸已按反馈移除、最低优先级让位是后来加的）、`R1002`（当前 1.3.3 第 142 次重打）、`ARCHITECTURE-SWITCHES.md`（`misc.placedGoldMark` 默认开）、`DEBT.md`（无等价条目）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 复现/静态定位 | 1.20.1 树 `PlacedMarkInteractHandler` 的潜行闸 `player.m_6040_()` 对玩家恒 false | ☑ |
| 1 | javap 核对 SRG | `LivingEntity.m_6040_` = 亡灵判定（`MobType.UNDEAD`）；`Entity.m_6144_` = 读共享标志位 1 = 潜行 | ☑ |
| 2 | 同类点排查 | 全树 5 处 `m_6040_` 都是"潜行"语义，全部改正 | ☑ |
| 3 | 编译 + 护栏 | `EXITCODE=0`；14 护栏 `FAIL 0 / WARN 0` | ☑ |
| 4 | 重打 + 装机 + 回归 | 双 jar；4 实例 `match=True`；专服回归三件 PASS | ☑ |
| 5 | 交开发者验收 | 1.20.1 实机：潜行右键描边方块 → 强制回收；指标石潜行解锁 / 骑乘潜行下坐骑同批复验 | ☐ |

## 需求 / 来源
- 开发者原话：「现在只要潜行 + 右键描边方块就强制回收，不再要求双手空」；随后反馈：「这个功能在1.20.1未能实现」。
- 一句话需求：1.20.1 上"潜行 + 右键描边方块"要能触发强制回收；1.21.1 已有同样行为。

## 评估
- **直接根因**：`promaid_src`（Forge 1.20.1）的 `PlacedMarkInteractHandler` 用 `player.m_6040_()` 判潜行。javap 实证 `m_6040_` 是 `LivingEntity` 的**亡灵/反转治疗判定**（`getMobType() == MobType.UNDEAD`，`f_21641_`）——对 `ServerPlayer` 恒 false，所以每一发右键都在潜行闸处 `return`，描边登记、让位判定、回收链路一次都没跑到。
- **正确 SRG**：`Entity.m_6144_()` 读共享标志位 1（`m_20291_(1)`），就是 `isShiftKeyDown()`（潜行）。`m_6047_()` 是 `isCrouching()`（Pose.CROUCHING），也能用，但本批统一用 `m_6144_` 与 1.21.1 的 `isShiftKeyDown()` 语义对齐。
- **为什么 1.21.1 正常**：Neo 树全部走 Mojmap 的 `player.isShiftKeyDown()`，没有这个映射问题。
- **同类点（一并修）**：全树 `m_6040_` 共 5 处，注释/上下文都明确是"潜行"：
  1. `task/PlacedMarkInteractHandler`（本轮报告的主功能）；
  2. `build/IndexStonePreviewClient`（指标石"潜行 + 右键 = 无条件解锁"）；
  3. `combat/RideBindManager`（骑乘指挥棒"潜行 + 右击 = 下坐骑/解除"）；
  4. `combat/SelfPreservationBehavior`（坐姿/蹲下/乘客 → 自保不归位）；
  5. `mixin/MaidTeleportPreserveMixin`（坐垫/骑乘/蹲下 → TLM 原版传送不拉走）。
  这 4 条在 1.20.1 上也一直是失效的（同类误用），本轮一起闭环。
- **不改的部分**：`someoneElseActs` 的"最低优先级"让位（手里拿方块 / 主手物品自带右键动作 / 方块自己可交互 → 让位）保持——这是 R999 追加二里开发者后来明确要的口径。"不再要求双手空"指的是不再有"双手都空"这道独立闸；不是"拿着方块也抢回收"。

## 改法
- 5 处 `m_6040_()` → `m_6144_()`（Forge 树）；两处注释里的 `m_6040_` 同步改成 `m_6144_`。Neo 树零改动。
- 无新配置项 / 无新 mixin / 无网络协议变化。

## 触及文件
- `promaid_src/com/maidsmart/task/PlacedMarkInteractHandler.java`
- `promaid_src/com/maidsmart/build/IndexStonePreviewClient.java`
- `promaid_src/com/maidsmart/combat/RideBindManager.java`
- `promaid_src/com/maidsmart/combat/SelfPreservationBehavior.java`
- `promaid_src/com/maidsmart/mixin/MaidTeleportPreserveMixin.java`

## 验证
- Forge `compile_promaid.bat`：`EXITCODE=0`。
- 14 护栏：`FAIL 0 / WARN 0`（`check_mixin_targets` 仍 267 注入点）。
- 双 jar（1.3.3 第 143 次同号重打）：
  - forge `211d21a2…8acc`（17939376 字节）
  - neoforge `207d08ae…7cd2`（17987001 字节，本次未改 Neo 代码，字节与上一版相同）
- `deploy_all.py` 4 个目标 `match=True`。
- 专服回归：`test_server.py 1201` PASS、`test_server.py neoforge1211` PASS、`test_maid_load.py neoforge1211` PASS。
- **反证**：`m_6040_` 的字节码读 `MobType.f_21641_`（UNDEAD），与"潜行标志位 1"毫无关系；`m_6144_` 的字节码正是 `m_20291_(1)`（共享标志位 1 = 潜行）。旧代码对玩家恒 false，所以 1.20.1 上不可能触发——与开发者"未能实现"的现场完全一致。
- 未做：未在 1.20.1 客户端实机点方块（等开发者验收）；未改 1.21.1 行为。

## 验收（谁说了算：开发者 —— `WORKFLOW.md` §4.1）
- 状态：`未验收`。
- 未验收之前：不写 changelog、不写 release 说明、不发平台。

## 边界（没动什么）
- 只修潜行判定映射；描边渲染、网络同步、回收产物、让位规则、指标石/骑乘/传送的业务语义一字未改。
- 1.21.1 树零改动；Neo jar 与上一版字节相同。
- 没有改 `someoneElseActs`：手里拿着方块 / 主手物品有右键动作 / 方块自己可交互时仍然让位（R999 追加二口径）。

## 台账
- 清掉的债：无既有 `BD-` 条目；本轮是 1.20.1 专属映射误用的首次闭环。
- 新增的债：无。
- 配额：A 类修 bug，不触发功能轮配额。
