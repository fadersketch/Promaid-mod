# 实测八百九十三 phase② 注释去历史：单块文件批量瘦身（21 文件 · 两树）

> 跑道：纯注释（不改行为、不改配置 / i18n / 版本）。承接 `R892`，按 `DEBT.md` §3 六阶段「② 注释去历史」推进。本轮改走**批量清小文件**策略（全库 600+ 文件里大量只有 1 块 LONG-BLOCK），单轮多产。

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）
- **跑道**：只改注释（注释块瘦身，无源码逻辑 / 配置 / i18n 改动），只落 dev 面。
- **开工前读过**：`DEBT.md` §3（②）、`WORKFLOW.md`（★红线 / §1 / §4 / §12）、上一轮 `R892`。结论：先统计全库**逐文件块数分布**，本轮取 20 个"1 块"文件批量处理（两树合计 41 块）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 全库逐文件 LONG-BLOCK 计数（升序） | 拿到分布，选出 20 个单块文件 | ✅ |
| 1 | 逐块改写到上限（保留约束 + 一行出处） | 各文件 LONG-BLOCK 归零 | ✅ |
| 2 | 两树编译 | `EXITCODE=0` | ✅ |
| 3 | 交开发者验收 | 开发者看可读性后给结论 | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者；验法 = 打开这些文件看注释是否读得懂、约束有无丢；机器侧看 `check_comment_style` 计数下降。

## 需求 / 来源
- 开发者「继续」：按 `DEBT.md` §3 六阶段清理顺序推进，本轮续 **阶段② 注释去历史**。

## 改法
- **策略变更**：大文件（`MaidMountCompat` 等）单轮成本高，改为先清**大量单块文件**——同等预算下产出的块数更多、单块风险更低。
- 全部改写到 `DEV_GUIDE.md` §1 上限：类头 ≤15 行、成员 ≤8 行（= `/**` + 6 行正文 + `*/`）。
- **保留**：类职责、`javap`/反编译实证的硬约束（API 事实表、SRG↔Mojmap 差异、`TaskData.writeSyncData` 崩服、`isVisuallySwimming` 触发 swim 动画、BM25 公式、`doSlash` 补拔刀斩等）、兜底值须跟默认值走等工程约定。
- **删除**：`v x 实测NNN：旧版…这一版…` 的变更史与开发心路。
- 工序：标记 = 注释块内一行的子串；**双向**吃满连续注释行后整体替换；脚本内**断言新块 ≤ 上限**（杜绝手数误差）；短/重复标记自动挑"唯一命中"。

## 触及文件（每文件两树各 1~2 块）
- `FirstJoinGift` `accessory/DaYunItem` `action/ItemUses` `affect/AffectManager` `bd/MaidBdCompat`
  `box/CompressionBoxFilter` `brew/BrewRecipeResolver` `build/BlueprintStepMath` `build/BuildTntGuard`
  `client/FlightDiveTilt` `combat/BombExplosion` `combat/FriendlyFireGuard` `combat/MaidFireGuard`
  `combat/MaidHeatEscape` `combat/SlashBladeCompat` `flight/MaidFreeFlightFlags`
  `follow/MaidVisibilityGuard` `guide/GuideIcons` `memory/AiMemoryBm25`（两树）+ Neo 侧
  `combat/MaidBroomRecall`、`box/CompressionBoxFilter` 的两个成员块。
- 无新配置项 / 新 i18n key / 新 mixin；无 Java 逻辑改动。

## 验证
- 全局 `check_comment_style`：**2103 → 2063**（−40）。
- 两树 `compile_promaid.bat` / `compile_neo.bat` → **EXITCODE=0**。
- 未部署、未跑专服回归（纯注释，不涉运行时）。

## 验收
- 状态：`未验收`。

## 边界（没动什么）
- **只动注释**，一行代码 / 字符串 / 配置未改。
- 未动其它文件的 LONG-BLOCK（其余 2063 处留后续轮次）。
- 未碰 `MaidSmartConfig` 的 `.comment(...)`（i18n key，须另开专项轮）。
- 两树个别处存在既有差异：`combat/MaidBroomRecall` / `box/CompressionBoxFilter` 有 Forge 侧 ≤8 行、Neo 侧超限的成员块，本轮只修了 Neo 侧超限的那一处（镜像性本就因分叉不严——见 `R892` 备注）。
- **未 commit / push**（按 `WORKFLOW.md` §12；等开发者指示）。

## 台账
- 本轮属 `DEBT.md` §3 六阶段「② 注释去历史」的推进，不新增 / 不清 BD·TD 条目；进度以 `check_comment_style` 计数为准（2103 → 2063）。
