# 实测八百七十九 · TD-09 / TD-02：配置注释补洞 + 雷同核查

## 需求 / 来源

承接 R837（当时 `check_comment_style` 净减 2）。TD-09：「配置注释里有删字留下的洞」（早期去引号批次删掉引语后没接上，留下孤立的「。」与半句话）；TD-02：「配置注释照抄：挖矿/伐木两套是复制出来的，有的字都没改」。开发者：「继续，一直做到所有任务结束」。

约定不变：不跑回归、不写 changelog / release 说明、版本号不动（仍 **1.3.2**）、两树镜像、临时脚本用完即删、**不 commit / 不 push**（WORKFLOW §12）。

## 评估（先扫后改）

**TD-09**：扫两树 `MaidSmartConfig` 全部注释，找两类形状——①整行只有标点（`。`/`，`/`；`）；②行尾是 `：`/`:` 而其下一注释行以标点开头。命中 **4 处**（两树各一份，同一批）：`COMBAT_RIDE_AIR_ALT` 上方一处孤立 `* 。`；`COMBAT_SPAWNER_TORCH_PRIORITY` 上方 `——` 后空注释体；`COMBAT_SPAWNER_BREAK` 上方 `插火把压不住：` 换行后 `* 。默认开…`；`COMBAT_SPAWNER_BREAK_ACTIVE_ONLY` 上方 `——` 后两行空 `*`。

**TD-02**：扫 `MINE_*` / `WOOD_*` 同后缀注释对（28 对，按相似度排序）。结论：**大段均已各自改写**（扫描预算 / 看门狗 / 目标超时 / 速度系数 / 硬挡路 / 发呆看门狗 …），无"照抄未改"的误导；仅 **4 对完全同名**，且都是**两套共用的机制**——`PILLAR_GUARD`（搭方块防掉落）、`RELOCATE_THROTTLE`（重定位节流）、`PILLAR_COOLDOWN`（搭方块冷却）、`JUNK_CHECK_INTERVAL`（废石清理检查间隔）。这些机制在挖矿与伐木里**就是同一件事**，同名文案是正确的，不改。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 扫描 | 两树配置注释：找删字空洞 + MINE/WOOD 雷同对 | 扫描报告（本轮） | ☑ |
| 2 | 实现 | 4 处空洞按上下文补回作用说明（两树） | 2 文件 | ☑ |
| 3 | 核查 | TD-02：判定 4 对同名注释是否误抄（结论：共用机制，保留） | 结论入台账 | ☑ |
| 4 | 验证 | 两树编译、打包、五护栏、注释存量下降、开关清单重生成 | 构建输出 | ☑ |
| 5 | 留痕 | `RELEASES.tsv` 第四十次 + 本文 + `TECH_DEBT` TD-09 收口 / TD-02 已确认 | 两处 | ☑ |
| 6 | 验收 | 交开发者：TD-09/TD-02 是否判"已清 / 已确认" | 待开闸 | ☐ |

## 改法

只动**配置项的 javadoc 注释**，按上下文补一句（内容取自同一字段的 `.comment()` 完整说明，逐字可信）：

1. `COMBAT_RIDE_AIR_ALT`：删掉孤立的 `* 。` 一行。
2. `COMBAT_SPAWNER_TORCH_PRIORITY`：把空的 `——` / `* */` 补成"发现刷怪笼先放下手上的活去拆它：这段时间她的走位独占（取消 WALK_TARGET、掐掉挖矿/伐木直连寻路）"。
3. `COMBAT_SPAWNER_BREAK`：把 `插火把压不住：` 换行后的孤立 `。` 删掉（句子接回"默认开 = 发现就拆"）。
4. `COMBAT_SPAWNER_BREAK_ACTIVE_ONLY`：把 `——` 后的空段补成"关掉 = 不看它有没有在刷怪、也不看笼子刷的是什么；回到旧的「见到就拆」"。

### 不做

- **不改任何 `.comment()` 玩家可见文案 / 开关 / 默认值**（只动源码里的 javadoc，`toml` 与面板输出不变）；
- 不改 TD-02 那 4 对同名注释（共用机制，同名正确）。

## 触及文件

**代码**（两树各一份）：`com/maidsmart/config/MaidSmartConfig.java`（仅 javadoc 注释）。
**文档 / 台账**：`ARCHITECTURE-SWITCHES.md`（重生成）、`TECH_DEBT.md`、`rounds/RELEASES.tsv`、`rounds/R879-*.md`（新）。

## 验证

| 项 | 结果 |
|---|---|
| 空洞扫描 | 两树 `TD-09` 命中数 **4 → 0** |
| 两树编译 | `javac EXITCODE=0`（两树各一次） |
| 护栏 | `check_catch_swallow` FAIL 0 / WARN 0；`check_preset` FAIL 0 / WARN 2；`check_workflow` FAIL 0 / WARN 3；`check_comment_style` **2262 → 2260 处**（净减 2）；`check_panel_coverage` FAIL 0 / WARN 0 |
| 开关清单 | `gen_switch_inventory.py --write` 重生成后 `check_workflow` 判"配置清单一致" |
| 打包自检 | 两树 `MISSING: none`、`lang json: OK`；Forge **17,603,852 B**（`cf6fe8e4…a85878`）、Neo **17,642,994 B**（`299d77e3…52d6c0`） |
| 镜像 | javadoc 差异仅 `ForgeConfigSpec`↔`ModConfigSpec`（`ForgeConfigSpec` 名）；文本逐字相同 |
| 部署 / 回归 | **未部署、未回归**（开发者闸门） |
| commit / push | **未做**（WORKFLOW §12） |

**反证（TD-02 无残留）**：对 28 对同后缀注释做相似度排序，若无"挖矿"词出现在 `WOOD_*` 文案里（或反之），即无交叉误抄——实测 `WOOD_*` 仅 `WOOD_BREAK_BUDGET`(`与挖矿共享障碍物名单`)、`WOOD_MOVE_SPEED`(`0.3 = 挖矿同款基础的一半`) 两处提到"矿"，且都是**有意对比**，非照抄。

## 验收

**未验收**（TD-09 是否判"已清"、TD-02 是否判"已确认"待开发者定）。

## 边界

- **不改行为 / 不改玩家可见文案**：仅源码 javadoc；`.comment()` 与 lang / 面板输出不变。
- 补的回句取自同字段 `.comment()` 的现成文本，不新造事实。
- 仍未做：TD-13 ②（自包含 Gradle）；TD-04 / TD-07 / TD-01（需开发者判断或较大工程）。

## 台账

- **TD-09 收口**（4 处空洞补齐，注释存量 2262→2260）；**TD-02 已确认**（28 对同后缀注释无残留误抄，4 对同名系共用机制，无需改）。
- 剩余未清项（均需开发者判断或较大工程）：**TD-01**（配置耦合）、**TD-04**（功能冗余裁定，需开发者）、**TD-07**（i18n 漂移）、**TD-13 ②**（Gradle）。已确认保留：TD-05、TD-17。
