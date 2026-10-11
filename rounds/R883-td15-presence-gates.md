# 实测八百八十三 · TD-15（收尾）：单一发行版探针补在场判断

## 需求 / 来源

TD-15：「按模组把探针式反射收敛成'兼容类 + `ModList.get()` 在场判断'，每 tick 路径优先」。R877 已做"每 tick 路径"那一半；本片收尾"在场判断"那一半。开发者：「剩下的你逐一修复。我们必须要根除 issue37 和 38 中的问题」。

约定不变：不跑回归、不写 changelog / release 说明、版本号不动（仍 **1.3.2**）、两树镜像、临时脚本用完即删、**不 commit / 不 push**（WORKFLOW §12）。

## 评估（关键约束：**多发行版不能单 id 判断**）

`ModList.get().isLoaded(id)` 只有在**目标模组 modid 唯一**时才安全。代码里有几处兼容类**故意**按"多个包根逐个探针"，正是因为同一功能存在**多发行版**、modid 未必相同：

- `MaidGoetyCompat`：官方版 `com.Polarice3.Goety` + 《万法皆通》`za.co.infernos.goety`——两发行版 **modid 未必相同**；单 id 判断会误伤其中一套。
- `MaidMountCompat` 的冰火传说：普通版 `com.github.alexthe666.iceandfire` + 社区版 `com.iafenvoy.iceandfire`——**但两个发行版 modid 都是 `iceandfire`**（源码注释已注明），故可以安全加闸。
- `CuriosElytraCompat`：为保**两树逐字同源**（不碰加载器专有类型）而用 `Class.forName` 当在场判断（已缓存一次）。

因此本片只给**单一发行版**的探针加 `ModList` 在场判断：

| 类 | 目标 | modid | 依据 |
|---|---|---|---|
| `combat/MaidMountCompat` `initSwb()` | 卓越前线 Superb Warfare | `MOD_SWB`=superbwarfare | 单一发行版 |
| `combat/MaidMountCompat` `initIaf()` / `initPart()` | 冰火传说（普通版/社区版） | `MOD_IAF`=iceandfire | 两发行版 modid 相同 |
| `bd/MaidBdCompat` `init()` | BeyondDimensions | `MOD_ID`=beyonddimensions | 两个 ROOTS 是同一模组的两种类路径 |
| `combat/MaidFlightCombatBehavior.taczOpApi()` | TaCZ | `tacz` | 与 `GunCompat` 同 id（已实证） |

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 评估 | 分清"单发行版"（可加闸）与"多发行版"（不可加闸） | 上表 | ☑ |
| 2 | 实现 | 单一发行版探针的 init 前加 `ModList.get().isLoaded(...)`（两树） | 4 类 × 2 树 | ☑ |
| 3 | 留痕 | 多发行版（Goety）与同源（Curios）两处**写注释说明为何不加闸** | 2 类 | ☑ |
| 4 | 验证 | 两树编译、打包、五护栏、镜像一致 | 构建输出 | ☑ |
| 5 | 记录 | `RELEASES.tsv` 第四十三次 + 本文 + `TECH_DEBT` TD-15 收口 | 两处 | ☑ |
| 6 | 验收 | 交开发者 | 待开闸 | ☐ |

## 改法

- `MaidMountCompat.initSwb/initIaf/initPart`（两树）：`xxxInited = true;` 之后、`try {` 之前插 `if (!ModList.get().isLoaded(MOD_SWB/MOD_IAF)) { return; }`。
- `MaidBdCompat.init()`：`inited = true;` 之后插 `if (!ModList.get().isLoaded(MOD_ID)) { return; }`。
- `MaidFlightCombatBehavior.taczOpApi()`：缓存未命中时先判 `isLoaded("tacz")`，不在场直接置空数组返回。
- `MaidGoetyCompat.init()`（两树）：加注释说明"有意不用 ModList（多发行版 modid 未必同）"。
- `CuriosElytraCompat.available()`：加注释说明"有意不用 ModList（为保两树逐字同源）"。

### 不做

- **不给多发行版探针加单 id 闸**（会误伤）；不改任何行为 / 开关 / 版本号。

## 触及文件

**代码**（两树各一份）：`combat/MaidMountCompat.java`、`bd/MaidBdCompat.java`、`combat/MaidFlightCombatBehavior.java`、`goety/MaidGoetyCompat.java`、`combat/CuriosElytraCompat.java`（两树逐字相同）。
**台账**：`TECH_DEBT.md`、`rounds/RELEASES.tsv`、`rounds/R883-*.md`（新）。

## 验证

| 项 | 结果 |
|---|---|
| 两树编译 | `javac EXITCODE=0` |
| 在场判断覆盖 | 现在带 `ModList.get().isLoaded` 的兼容类：`GunCompat`/`SlashBladeCompat`/`ThirstCompat`/`MaidSpellCompat`/`MaidSpellCastCompat`（原有）+ `MaidMountCompat`/`MaidBdCompat`/`MaidFlightCombatBehavior`（本轮） |
| 镜像 | `CuriosElytraCompat` 两树 `fc /b` 逐字节相同 |
| 护栏 | `check_preset` 0/0；`check_workflow` 0/3；`check_catch_swallow` 0/0；`check_i18n_keys` 0/0；`check_comment_style` 931/2260；`check_panel_coverage` 0/0 |
| 打包自检 | 两树 `MISSING: none`、`lang json: OK`；Forge **17,604,497 B**（`dd0eecd8a617…f75e46`）、Neo **17,643,606 B**（`62cefdf9c090…a99510`） |
| 部署 / 回归 | **未部署、未回归**（开发者闸门） |
| commit / push | **未做**（WORKFLOW §12） |

**反证**：若把 `MaidGoetyCompat` 也改成 `isLoaded("goety")`，则《万法皆通》那套（modid 不同）装了就再也连不上法杖兼容——这正是本片**不加**那一闸的原因，已在源码注释写明。

## 验收

**未验收**（多发行版保留口径待开发者确认）。

## 边界

- **不改行为**：目标不在场时，原来靠 Class.forName 抛异常 → 现在靠 isLoaded 直接返回，结果一致（都判"不可用"）；在场时照旧。
- 探测仍只解析一次（缓存/init 标记），非每 tick。
- 仍未做：TD-07（184 条译文）、TD-03③、TD-12①、TD-13②。

## 台账

- **TD-15 收口**：每 tick 路径（R877）+ 单一发行版在场判断（本片）都已做；多发行版/同源两处有意保留并写明理由。
