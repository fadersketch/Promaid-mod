# 实测八百九十四 phase② 注释去历史：单块文件批量瘦身（第 2 批 · 两树）

> 跑道：纯注释（不改行为、不改配置 / i18n / 版本）。承接 `R893`，继续 `DEBT.md` §3 六阶段「② 注释去历史」，仍是**批量清单块文件**。

## 需求 / 来源
- 开发者「继续」：按 `DEBT.md` §3 六阶段清理顺序推进，本轮续 **阶段② 注释去历史**。

## 改法
- 20 个「1 块」文件（两树合计 ~40 块，其中 `DaYunSounds`/`BuildContainerSource` 的 Neo 侧本就 ≤ 上限、无块）逐块改写到 `DEV_GUIDE.md` §1 上限（类 ≤15 行 / 成员 ≤8 行）。
- **保留**：类职责与硬约束（hatmod BGM 的 200 格衰减口径、超越维度四个网络包与平台差异、溢出入库"探测必走模拟分支"的复制教训、`keepAtLeast` 三条安全规矩、`parseJson` 只做结构+物理兜底、zip/世界存档导入、`storeMaidData 先快照再 post` 的魂符坑、指标石锁定两条路径、BombMark 渲染管线等）。
- **删除**：`v x 实测NNN：旧版…这一版…` 的变更史；出处压成一句。

## 触及文件（两树）
`accessory/DaYunSounds` `bd/MaidBdNetworking` `bd/MaidBdOverflow` `bd/MaidBdRestock` `bd/MaidBdRules`
`box/CompressionBoxNetworking` `build/BlueprintCatalog` `build/BlueprintFileIo` `build/BlueprintStructureCodec`
`build/BuildContainerFetchBehavior` `build/BuildContainerSource` `build/BuildHudTracker` `build/FarmSweepCache`
`build/FarmlandMarkStore` `build/IndexStoneInterruptHandler` `build/IndexStoneItem` `build/SmartBuildTool`
`build/SmartDesignTool` `client/BombMarkClient` `client/DaYunMusicInstance`。
- 无新配置项 / 新 i18n key / 新 mixin；无 Java 逻辑改动。

## 验证
- 全局 `check_comment_style`：**2063 → 2026**（−37）。
- 过程中自查出并修正 1 处自引入的 `QUOTE-SPEECH`（`SmartDesignTool` 里写了「玩家说」→ 改为「玩家输入」）；终态 `{'LONG-BLOCK': 2026}`，无 QUOTE-SPEECH。
- 两树 `compile_promaid.bat` / `compile_neo.bat` → **EXITCODE=0**。
- 未部署、未跑专服回归。

## 验收
- 状态：`未验收`。

## 边界（没动什么）
- **只动注释**；未动其它文件的 LONG-BLOCK；未碰 `MaidSmartConfig` 的 `.comment(...)`。
- `DaYunSounds` Neo 侧无该类注释块（本就合规），本轮只改了 Forge 侧——镜像性受既有分叉影响（见 `R892` 备注）。
- **未 commit / push**（按 `WORKFLOW.md` §12）。

## 台账
- 属「② 注释去历史」推进；进度以 `check_comment_style` 计数为准（2063 → 2026）。
