# 实测八百七十八 · TD-10：总开关也在 canStillUse 里问一遍

## 需求 / 来源

承接 R838（当时只修了被点名的"拆刷怪笼"一处 `MaidSpawnerTorchBehavior`）。剩余 19 处待核。开发者：「继续，一直做到所有任务结束」。

TD-10 的症状：Brain 对**正在跑**的行为每 tick 只问 `canStillUse`（不问 `canUse`），所以"总开关只在 `canUse` 里问"的行为，开关翻转后**正在跑的那一半会自己跑完**——玩家观感是"按了开关没生效"。

约定不变：不跑回归、不写 changelog / release 说明、版本号不动（仍 **1.3.2**）、两树镜像、临时脚本用完即删、**不 commit / 不 push**（WORKFLOW §12）。

## 评估（先量后改）

全域扫描两树：共 **22** 个行为类覆写了 `canStillUse`/`m_6737_`。逐类判定得到：

| 形状 | 数量 | 处置 |
|---|---|---|
| `canUse` 读总开关、`canStillUse` **不读**（真候选） | **7** | **本轮补齐** |
| `canUse` 读、`canStillUse` 也有意返回 `false`（一次性，导航接管） | 2 | 不改（`MaidBedInteropBehavior` / `MaidStrollBehavior`） |
| `canUse` 读、`canStillUse` 也读（对称） | 5 | 已满足（`IndexStoneBuildBehavior`/`MaidCombatTacticsBehavior`/`MaidFlightFollowBehavior`/`MaidSpawnerTorchBehavior`/`BridgeUpBehavior`） |
| 开关不在 `canUse` 而由任务 uid / 运行态判定 | 7 | 不改（`MaidBuildBehavior`/`MaidBroomBehavior`/`MaidTridentSpinBehavior`/`MaidBrewBehavior`/`MaidCookBehavior`/`MaidSlaughterBehavior`/`MaidWoodBehavior`） |
| `MaidMineBehavior`：`canUse` 读的是 `MINE_REQUIRE_PICK`（**启动期子选项**，非任务总开关） | 1 | 不改（放进 `canStillUse` 会变成"开启该选项即打断在跑的挖矿"，语义不对） |

**真候选 7 个**（`canUse` 读总开关、`canStillUse` 未读）：

| # | 类 | 总开关 | `canStillUse` 原状 |
|---|---|---|---|
| 1 | `combat/MaidAidOwnerBehavior` | `AID_OWNER_ENABLE` | 字面 `return true;` |
| 2 | `combat/MaidShieldShareBehavior` | `SHIELD_SHARE_ENABLE` | 字面 `return true;` |
| 3 | `combat/MaidTorchPlacerBehavior` | `TORCH_PLACER_ENABLE` | 字面 `return true;` |
| 4 | `combat/SelfPreservationBehavior` | `COMBAT_SELF_PRESERVE` | 字面 `return true;` |
| 5 | `combat/WaterClutchBehavior` | `COMBAT_WATER_CLUTCH` \|\| `COMBAT_SNOW_CLUTCH` | 字面 `return true;` |
| 6 | `combat/MaidFlightCombatBehavior` | `COMBAT_FLIGHT_MODE` | 只判 睡着/骑乘 + 有目标 |
| 7 | `build/BuildContainerFetchBehavior` | `BUILD_FETCH_FROM_CHESTS` | 只判 目标未失效 |

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 审计 | 枚举全部覆写 `canStillUse` 的行为，逐类判"总开关是否只在 canUse 里问" | 审计表（本轮） | ☑ |
| 2 | 实现 | 7 个真候选的 `canStillUse` 补一行总开关判定（两树；≤3 行/处） | 7 类 × 2 树 | ☑ |
| 3 | 验证 | 两树编译、打包、五护栏、注释存量不增 | 构建输出 | ☑ |
| 4 | 留痕 | `RELEASES.tsv` 第三十九次 + 本文 + `TECH_DEBT` TD-10 收口 | 两处 | ☑ |
| 5 | 验收 | 交开发者：TD-10 是否判"已清" | 待开闸 | ☐ |

## 改法

对 7 个真候选，`canStillUse` 开头补一行（Forge `m_6737_`、Neo `canStillUse`；Forge 用 SRG 名、Neo 用 Mojmap 名）：

- `MaidAidOwnerBehavior`：`return AID_OWNER_ENABLE.get();`（原来是字面 `true`——**保持**"必须重写以免 1.20.1 默认 false 立刻停"的设计，只是把常量换成开关；开关默认开 → 行为逐字不变）。
- `MaidShieldShareBehavior` / `MaidTorchPlacerBehavior`：同上（`SHIELD_SHARE_ENABLE` / `TORCH_PLACER_ENABLE`）。
- `SelfPreservationBehavior`：`return COMBAT_SELF_PRESERVE.get();`（保留原"常驻检查器/会话由 tick 内部维护"注释）。
- `WaterClutchBehavior`：`return COMBAT_WATER_CLUTCH.get() || COMBAT_SNOW_CLUTCH.get();`。
- `MaidFlightCombatBehavior`：在原有 `睡觉/骑乘 → false` 之后、`currentTarget != null` 之前，插 `if (!COMBAT_FLIGHT_MODE.get()) return false;`。
- `BuildContainerFetchBehavior`：在原有判据之前，插 `if (!BUILD_FETCH_FROM_CHESTS.get()) return false;`。

### 不做

- 不改那 5 个已对称的、7 个由任务 uid/运行态判定的、2 个有意 `false` 一次性的；
- 不动 `MaidMineBehavior`（`MINE_REQUIRE_PICK` 是启动期子选项，不是任务总开关）；
- 不改任何玩家可见文案 / 开关默认值 / 版本号。

## 触及文件

**代码**（两树各一份，共 14 个文件）：`combat/MaidAidOwnerBehavior.java`、`combat/MaidShieldShareBehavior.java`、`combat/MaidTorchPlacerBehavior.java`、`combat/SelfPreservationBehavior.java`、`combat/WaterClutchBehavior.java`、`combat/MaidFlightCombatBehavior.java`、`build/BuildContainerFetchBehavior.java`。
**文档 / 台账**：`TECH_DEBT.md`、`rounds/RELEASES.tsv`、`rounds/R878-*.md`（新）。

## 验证

| 项 | 结果 |
|---|---|
| 两树编译 | `javac EXITCODE=0`（两树各一次） |
| 逻辑不变量 | 7 个开关**默认值均为开**（`check_preset` 的 DIY 关闭项数不变：Forge 173、Neo 177）→ 开关开着时 `canStillUse` 返回值与改前逐个等价 |
| 护栏 | `check_catch_swallow` FAIL 0 / WARN 0；`check_preset` FAIL 0 / WARN 2；`check_workflow` FAIL 0 / WARN 3；`check_comment_style` 931 文件 / **2262 处**（不变）；`check_panel_coverage` FAIL 0 / WARN 0 |
| 打包自检 | 两树 `MISSING: none`、`lang json: OK`；Forge **17,603,850 B**（`84a86a90…caaf7e`）、Neo **17,642,994 B**（`1e8e9fd5…b43bf6`） |
| 镜像 | 差异仅 SRG↔Mojmap 方法名（`m_6737_`↔`canStillUse`、`m_7702_`↔`getBlockEntity` 等） |
| 部署 / 回归 | **未部署、未回归**（开发者闸门） |
| commit / push | **未做**（WORKFLOW §12） |

**反证（开关即刻生效）**：把 `COMBAT_FLIGHT_MODE` 在配置里关掉（女仆正在空战）→ 改前她要等当前目标消失才停；改后下一 tick `canStillUse` 返回 false → 行为立即 stop（`m_6732_`/`stop` 收枪落地）。

## 验收

**未验收**（TD-10 是否判"已清"待开发者定）。

## 边界

- **默认行为不改**：7 个开关默认开，开着时判定值与改前一一对应（字面 `true` → `开关.get()` = true）。
- 只动"停止条件"，不动 `canUse`/tick 逻辑；每处 ≤3 行。
- 仍未做：TD-13 ②（Gradle）、TD-09/TD-02（配置注释空洞 / 雷同）。

## 台账

- **TD-10 收口**：22 个覆写里 7 个真候选补齐，其余 15 个为已对称 / 有意一次性 / 任务 uid 判定，不改。
- 下一步候选：**TD-09 / TD-02**（两树 `MaidSmartConfig` 的注释空洞与雷同注释）。
