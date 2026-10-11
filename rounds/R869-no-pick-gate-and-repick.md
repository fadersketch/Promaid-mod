# 实测八百六十九 · 挖矿对齐参考流程图：①没镐不启动 ②锁定期间持续重挑（不重置超时）

## 需求 / 来源

开发者指令（承接 R868 汇报里我给出的补齐建议）：

> 就按你说的做。

即落实我给出的前两档（第三档「并行填坑 / 三模式」属新增功能，单开一轮、另议）：
1. **①「没镐不启动」闸**：对齐参考 mod 官方流程图入口节点「身上有镐子吗？无 → 任务开启不起来（提示背包需备齐镐来）」。
2. **②「持续检测」式重挑**：对齐流程图「持续检测」节点，把 R868 删掉的周期性重挑加回来，但带「**不重置锁定超时**」的修正（治抖动）。

约定不变：不跑回归、不写 changelog / release 说明、版本号不动（仍 **1.3.2**）、两树逐字镜像、临时脚本用完即删、**不 commit / 不 push**（WORKFLOW §12）。

## 评估

- **跑道**：A 修 bug（P2，行为：入口闸缺失 + 锁定期缺"持续检测"）。**非功能轮**（§8 配额不触发）。
- **现状（两树都核过）**：
  - 入口：`MaidMineBehavior.checkExtraStartConditions`（Forge `m_6114_`）**常驻为真**——只要 TLM 任务是 `maid_smart:mine` 就放行，**没有"身上有镐子吗"这道闸**（R868 前后一致）。没镐时她照常跑，走到矿前才由 `recordSkippedOre` 播报"挖不动"。
  - 持续检测：R868 **删掉了** R858 的"锁定期间每 5 秒重挑"（`MINE_REPICK_INTERVAL` / `repickCooldown` 一并删），改成"目标只在①失效②超时③不可达 时释放"。这治好了抖动，但也丢了流程图里的「持续检测」——她锁上一块后中途不看别的矿。
- **可复用**：
  - 镐判定：`MaidToolAutoEquip` 已有 `isPickaxe(ItemStack)`（私有）与 `canHarvestWithHandOrBackpack`（手+背包+精妙背包口径）——新增一个只看"有没有镐"的公开探针即可。
  - 重挑：R858 原实现仍在 `git show 3c6b7b7` 的 diff 里（`-` 行），照搬并把 `TARGET_SINCE.put(...)` 那一行去掉即可。
  - 限频：复用 `MINE_SKIP_REPORT_INTERVAL`（默认 600 tick = 30 秒）与既有 `*_REPORT_SINCE` 表范式。
- **开关纪律（§3.2 / DIY 硬要求）**：两处都是"改女仆行为"，**各给一个独立布尔开关**（默认开、随 DIY 关、可单独重开）：`mine.requirePick` / `mine.repick`。查 `ARCHITECTURE-SWITCHES.md` / `BUG_DEBT.md`：挖矿任务族目前没有等价总闸，本轮各自新增。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 侦察 | 确认入口无常驻外闸、R868 删掉了 R858 重挑、可复用的镐探针与限频范式 | 本文 §评估 | ☑ |
| 2 | 设计 | 定两开关与判据：`mine.requirePick`（有镐才启动 + 限频气泡）、`mine.repick`（重挑但**不重置** `TARGET_SINCE`） | 本文 §改法 | ☑ |
| 3 | 实现 | 两树 `MaidSmartConfig`：+2 声明 +2 定义（mine 组） | 两树 | ☑ |
| 4 | 实现 | 两树 `MaidToolAutoEquip`：+`hasAnyPickaxe(EntityMaid)` 公开探针 | 两树 | ☑ |
| 5 | 实现 | 两树 `MaidMineBehavior`：入口闸 + `hintNoPickaxe` + 限频表；重挑常量/字段/块 | 两树 | ☑ |
| 6 | 实现 | 两树 lang：+2 配置翻译 key（`config.promaid.mine.{requirePick,repick}`，中英） | 4 文件 | ☑ |
| 7 | 护栏 | `check_preset`：`GATES_ELSEWHERE` +2；`BASELINE_FLIP` 171/175 → 173/177；重生成 `ARCHITECTURE-SWITCHES.md` | 2 文件 | ☑ |
| 8 | 验证 | 两树编译、四护栏问题数不增、打包自检、镜像/符号比对 | 构建输出 | ☑ |
| 9 | 留痕 | `rounds/RELEASES.tsv` 第三十一次同号重打留痕 + 本文 §验证 | 两处 | ☑ |
| 10 | 验收 | 交开发者实测（没镐她不启动+提示；有镐锁定期发现更优矿会换，但不再抖动） | 待开闸 | ☐ |

## 改法

1. **新开关 ①** `mine.requirePick`（字段 `MINE_REQUIRE_PICK`，mine 组，`BooleanValue`，默认 `true`）：
   「没镐不启动（默认开）：主手与背包（含精妙/旅行者背包）里一把镐都没有时，挖矿任务不启动，
   她只气泡提示先备一把镐子；关闭 = 照常启动、走到矿前才报挖不动。」
2. **新开关 ②** `mine.repick`（字段 `MINE_REPICK`，mine 组，`BooleanValue`，默认 `true`）：
   「锁定期间持续重挑（默认开）：锁定一块矿期间每 5 秒从缓存里廉价重挑一次，发现更优的矿就切过去；
   切换不重置「够不着」超时，避免在分数接近的矿之间反复抖动。关闭 = 锁上就一路挖到底、中途不换目标。」
3. **代码（`MaidMineBehavior`）**：
   - 入口闸（`checkExtraStartConditions` / `m_6114_`）：先判任务，再判
     `MINE_REQUIRE_PICK.get() && !MaidToolAutoEquip.hasAnyPickaxe(maid)` → `hintNoPickaxe(...)`（限频）+ `return false`。
     给了镐下一 tick 自动开工（TLM 每 tick 重询 `shouldExecute`）。
   - 重挑块（`dTick` 里、`targetPos` 扫描块之后）：`MINE_REPICK.get() && targetPos != null && destroyProgress <= 0 && --repickCooldown <= 0`
     → `repicked = findOre(...)`；`repicked != current` 才切目标 + `ensureForTarget`。**不写 `TARGET_SINCE`**。
   - 新增私有常量 `MINE_REPICK_INTERVAL = 100`、字段 `repickCooldown`、限频表 `NO_PICK_REPORT_SINCE`（并入 `forget` / `purgeStaleMaids`）。
4. **`MaidToolAutoEquip`**：新增 `public static boolean hasAnyPickaxe(EntityMaid)`——手 + 背包 + 精妙/旅行者背包里是否有任意一把镐（只看类型，不看能不能挖某种矿）。
5. 护栏：`GATES_ELSEWHERE` +2（`MINE_REQUIRE_PICK` / `MINE_REPICK` → `com/maidsmart/task/MaidMineBehavior.java`）；
   `BASELINE_FLIP` +2（默认开、不在 KEEP → DIY 会关）；`ARCHITECTURE-SWITCHES.md` 重生成；版本号不动、不写 changelog。

### 不做

- 不做「并行填坑 / 垫石」与「三模式（声波/锚点/漂移）」——属新增功能、会动现有架构，单开一轮另议。
- 不改「找得到路吗 → 失败目标待机」（Yield）——我们仍是"弃目标走人"，与流程图的有意差异，沿用 R868 决定。
- 不改判据里的"够不着超时"阈值（仍用 R868 的 `targetTimeoutTicks`）。

## 触及文件

**改动**（两树各一份）：`com/maidsmart/config/MaidSmartConfig.java`、`com/maidsmart/task/MaidToolAutoEquip.java`、
`com/maidsmart/task/MaidMineBehavior.java`、`assets/maid_smart/lang/{zh_cn,en_us}.json`。
**工具**：`tools/verify/check_preset.py`、`ARCHITECTURE-SWITCHES.md`（生成物）。

## 验证

| 项 | 结果 |
|---|---|
| 两树编译 | `javac EXITCODE=0`（Forge 88 警告 / Neo 同档，均为存量 deprecation） |
| 护栏 | `check_preset` FAIL 0 / WARN 2（配置项 602/606、布尔 266/270、DIY 关闭 **173/177**＝新基线；WARN 2 系既有 `broom.enable` 重路径；`GATES_ELSEWHERE` 已接上 `MINE_REQUIRE_PICK` / `MINE_REPICK`）；`check_workflow` FAIL 0 / WARN 3（三条旧 WARN）；`check_comment_style` 927 文件 / **2262 处**（与基线同数，无新增）；`check_panel_coverage` FAIL 0 / WARN 0（自动页未收录 64/61，R867+R869 各计） |
| 打包自检 | 两树 `MISSING: none`、`lang json: OK`；Forge 17,578,035 B（`7a551f3a528e…bc1f63`）、Neo 17,616,990 B（`df4cb4b6512b…0fdbf5`）；桌面 `Promaid_本体当前` 快照刷新一致 |
| 镜像 | 新符号两树计数逐项相等：`MINE_REQUIRE_PICK` 3/3、`MINE_REPICK` 7/7、`hasAnyPickaxe` 2/2、`hintNoPickaxe` 2/2、`MINE_REPICK_INTERVAL` 4/4、`NO_PICK_REPORT_SINCE` 6/6、`repickCooldown` 3/3、`config.promaid.mine.{requirePick,repick}` 各 3/3（源码 + 两 lang 键）；两树差异仅 SRG↔Mojang 名（`m_8055_`↔`getBlockState`、`m_46467_`↔`getGameTime`、`m_19879_`↔`getId`、`m_6114_`↔`checkExtraStartConditions`） |
| 部署 | **未部署**（开发者闸门） |
| 回归 | **未跑**（开发者闸门） |
| commit / push | **未做**（WORKFLOW §12） |

## 验收

**未验收**。

## 边界

- ①只在"任务 = 挖矿"时生效；没镐时她不启动挖矿、不影响其它任务。
- ②只在"已锁定目标且未在挖掘（`destroyProgress<=0`）"时重挑；挖掘进行中不打扰。
- 两项都随 DIY 关；关掉即 = R868 之后的行为（照常启动 / 锁上不换）。

## 台账

- 无新增结构债。两个新开关为布尔、默认开 → DIY 自动关；已进 `check_preset` 的 `GATES_ELSEWHERE` 与基线。
