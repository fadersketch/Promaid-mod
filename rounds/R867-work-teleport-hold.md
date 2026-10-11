# 实测八百六十七 · 伐木/挖矿「发现目标后暂时禁止 TLM 远距离传送」

## 需求 / 来源

开发者指令（同一轮里的第一条）：

> 伐木加一个额外条件（等女仆发现目标的时候，暂时禁止 TLM 自带的远距离传送）。

约定不变：不跑回归、不写 changelog / release 说明、版本号不动（仍 **1.3.2**）、两树逐字镜像、临时脚本用完即删。

## 评估

- **跑道**：A 修 bug（P2，行为干扰：干到一半被拽走）。
- 现状（**两树都核过**）：`MaidWoodBehavior` / `MaidMineBehavior` 全文**没有**任何 `teleport` 调用；
  拦 TLM 传送的 `mixin/MaidTeleportPreserveMixin.java`（注入 `EntityMaid.teleportToOwner`）现有豁免分支只有
  建造 / 搭路 tag / 坐下·骑乘·蹲下 / 滑翔 / 扫帚腾空 / 特殊坐骑 / 自保标记 —— **没有"挖矿中/伐木中"这一支**。
  于是地下挖矿（>66 格远离主人）或走远伐木时，TLM `MaidFollowOwnerTask` 一超线就把她瞬移回主人身边，
  手上这一轮工作作废（挖矿还额外丢目标/断连锁，这也是"挖矿比伐木更差"的一个外部放大因素）。
- 判据可复用：两树都已有 `MaidWoodBehavior.isWooding(EntityMaid)` / `MaidMineBehavior.isMining(EntityMaid)`
  （`Set<UUID>`，"工作态"标志，找矿/伐木时登记、无目标/停任务时移除；本模组别处已用它，如
  `MaidPickupPriorityMixin`）。mixin 里拿得到 `EntityMaid`，可直接问。
- 查 `ARCHITECTURE-SWITCHES.md` / `BUG_DEBT.md`：`TLM_TELEPORT_EXEMPT`（默认开、随 DIY 关）是这一族豁免的**总闸**；
  本轮在它下面**新增一条独立开关**（每次改女仆行为都要有自己的闸，DIY 关、可单独重开）。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 侦察 | 确认伐木/挖矿无传送防护、`MaidTeleportPreserveMixin` 现有分支清单、可复用的 `isWooding/isMining` | 本文 §评估 | ☑ |
| 2 | 设计 | 定开关与判据：`misc.workTeleportHold`（默认开、随 DIY 关）；判据 = 总闸开 + 任务是挖矿/伐木 + `isMining/isWooding` | 本文 §改法 | ☑ |
| 3 | 实现 | 两树 `MaidSmartConfig`：+1 声明 +1 定义（misc 组） | 两树 | ☑ |
| 4 | 实现 | 两树 `MaidTeleportPreserveMixin`：新增豁免分支（引用该开关） | 两树 | ☑ |
| 5 | 实现 | 两树 lang：+1 配置翻译 key（`config.promaid.misc.workTeleportHold`，中英） | 4 文件 | ☑ |
| 6 | 护栏 | `check_preset`：`GATES` +1；`BASELINE_FLIP` 170/174 → 171/175；重生成 `ARCHITECTURE-SWITCHES.md` | 2 文件 | ☑ |
| 7 | 验证 | 两树编译、四护栏问题数不增、打包自检、镜像/符号比对 | 构建输出 | ☑ |
| 8 | 留痕 | `rounds/RELEASES.tsv` 同号重打留痕 + 本文 §验证 | 两处 | ☑ |
| 9 | 验收 | 交开发者实测（走远伐木/挖矿不再被瞬移回主人；关掉即回 TLM 原版） | 待开闸 | ☐ |

## 改法

1. **新开关** `misc.workTeleportHold`（字段 `MISC_WORK_TELEPORT_HOLD`，misc 组，`BooleanValue`，默认 `true`）：
   「干活时不被传送召回（默认开）：女仆在**挖矿/伐木**且已盯上目标（工作态）期间，TLM 自带的
   "离主人太远就瞬移回主人身边"暂不生效——避免她走远找矿/伐木时被拽走、手上这一轮作废。
   **关闭 = 完全回 TLM 原版**（照常被传送）。总闸仍是「传送重建/豁免」，本项只是其中一条。」
2. **代码**（`MaidTeleportPreserveMixin`）：在总闸 `TLM_TELEPORT_EXEMPT` 已开的前提下，新增一支：
   ```java
   if (com.maidsmart.config.MaidSmartConfig.MISC_WORK_TELEPORT_HOLD.get()
           && (com.maidsmart.task.MaidMineBehavior.isMining(maid)
               || com.maidsmart.task.MaidWoodBehavior.isWooding(maid))) {
       cir.setReturnValue(false);
       return;
   }
   ```
   位置放在"建造/搭路"那两之后、坐垫/骑乘/蹲下之前（都是"停放不拉"的同族豁免）。
3. 护栏 `GATES` +1（要求 mixin 引用到这条开关）；`BASELINE_FLIP` 同步 +1（默认开、不在 KEEP → DIY 会关它）；
   `ARCHITECTURE-SWITCHES.md` 重生成；版本号不动、不写 changelog。

### 不做

- 不给"采蜜/割草/种树"等其它任务加这一支（本模组没有这些的独立工作态标志；将来有需要再补）。
- 不改 TLM `MaidFollowOwnerTask` 的触发阈值，也不改本模组自己的远距召回（`MaidChunkLoadManager`）——
  只拦 TLM 那一条"自动瞬移回主人"。

## 触及文件

**改动**（两树各一份）：`com/maidsmart/config/MaidSmartConfig.java`、`com/maidsmart/mixin/MaidTeleportPreserveMixin.java`、
`assets/maid_smart/lang/{zh_cn,en_us}.json`。
**工具**：`tools/verify/check_preset.py`、`ARCHITECTURE-SWITCHES.md`（生成物）。

## 验证

| 项 | 结果 |
|---|---|
| 两树编译 | `javac EXITCODE=0`（Forge 88 警告，与上轮同档；Neo 同档） |
| 护栏 | `check_preset` FAIL 0 / WARN 2（配置项 600/604、DIY 关闭 **171/175**＝新基线；WARN 2 系既有 `broom.enable` 重路径；`GATES` 已接上 `MISC_WORK_TELEPORT_HOLD`）；`check_workflow` FAIL 0 / WARN 3（三条旧 WARN）；`check_comment_style` 927 文件 / **2262 处**（与基线同数）；`check_panel_coverage` FAIL 0 / WARN 0（新开关已进自动页 62/59） |
| 打包自检 | 两树 `MISSING: none`、`lang json: OK`；Forge 17,576,737 B（`b08d1e06fb10…494e45`）、Neo 17,615,659 B（`cb32889036c2…90e656`）；桌面 `Promaid_本体当前` 快照刷新一致 |
| 镜像 | `MISC_WORK_TELEPORT_HOLD` 两树各 3 处；`workTeleportHold` 两树各 4 处；`config.promaid.misc.workTeleportHold` 两树各 3 处（源码 + 两个 lang 键）；mixin 新增分支两树差异仅 SRG↔Mojang 名（`isShiftKeyDown()`↔`m_6040_()` 等既有差异） |
| 开关清单 | `ARCHITECTURE-SWITCHES.md` 重生成（1390 行） |
| 部署 | **未部署**（开发者闸门） |
| 回归 | **未跑**（开发者闸门） |

**反证**：关掉 `misc.workTeleportHold`（或直接关总闸 `TLM_TELEPORT_EXEMPT`）= 这一支不生效 → 回 TLM 原版：
她走远后仍会被 `teleportToOwner` 瞬移回主人身边（旧行为）。开发者实测时的可见证据：任务为挖矿/伐木、
离主人 >66 格、正在找矿/砍树时**不再**出现瞬移回位；切任务或停手后恢复原版。

## 版本与上传

- 版本保持 **1.3.2**；**不**部署、**不**跑回归、**不**发 release；同号重打按 WORKFLOW §5.2 留痕。

## 验收

**未验收**。

## 边界

- 只在"工作态"豁免；她一停手（无目标/切任务）就恢复 TLM 原版行为。
- 总闸 `TLM_TELEPORT_EXEMPT` 关掉时，本项一并失效（回完全原版）。
- 本模组自己的传送链路（跨维度跟随 / 一键集合 / 复活回位）不经 `teleportToOwner`，不受影响。

## 台账

- 无新增结构债。新开关为布尔、默认开 → DIY 自动关；已进 `check_preset` 的 `GATES` 与基线。
