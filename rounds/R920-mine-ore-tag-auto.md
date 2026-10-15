# 实测九百二十 挖矿补上矿石标签自动识别（套用伐木的 tagAuto）：模组矿石不再"检索不到"

> 跑道：**B 功能补齐**——把伐木侧早就有的「标签自动识别」（`wood.tagAuto`）原样搬到挖矿，新增 `mine.tagAuto`（默认开、随 DIY 关）：带矿石伞形标签（Forge `forge:ores` / NeoForge `c:ores`）的方块也算矿。**为什么这轮先做功能**：开发者实测反馈「哪怕旁边就是矿，她还是站在原地不动，似乎根本就检索不到那个矿」——这是同一族反馈的第三次；根因是矿表**只认显式名单**（内置/配置全是原版矿），模组矿石一律不可见，而伐木侧早有对应机制。本轮不新增任何"押着高危债"的行为改动（现存 P0/P1 债为 0）。
> 开发者指令：「你搞了个啥，刚刚运行了一下游戏。哪怕女巫旁边就是光，她还是站在那边原地不动，似乎根本就检索不到那个矿」
> **更正（实测九百二十一）**：上面这条反馈的**真因不是矿表**——开发者随后澄清"旁边明明是**煤矿石**"（原版矿、在矿表里），真因是 `WorkAreaClamp.allowsWork` 把"她本人已在玩家圈外"时的干活选点**全部**否掉（见 `rounds/R921-work-circle-clamp-when-outside.md`，有存档 NBT 实证）。本轮的 `mine.tagAuto` 仍然成立并保留——它补的是"模组矿石不在名单里"这个**另一个**真缺口，只是与本例无关。下面的定位推理因此只对本轮自己的目标成立。
> 上一轮：`rounds/R919-swb-blacklist-lift.md`（卓越前线剩余载具解禁）。R919 只动载具 / 手册 / i18n，与挖矿无关——本轮的现场证据也证实了这点（见下）。

## 排班

- **本轮唯一主项**：让挖矿能"看见"模组矿石。驾驶 / 战斗 / 建筑 / 农场等一律不动。
- 步骤与完成判据：
  1. **复核现场证据**（`logs/latest.log` / `logs/promaid.log`，2026-10-13 00:41–00:44 那次实机）。判据：能指出"她确实挖到过矿、也确实报过没有矿"，并把两种结论各自的代码闸找出来。
  2. **找出"伐木好使、挖矿不好使"的机制差**。判据：差是一处**机制**（名单制 vs 标签制），不是数值。
  3. **改 Forge 树** → 4. **镜像 Neo 树**。判据：两树 `javac` `EXITCODE=0`。
  5. **护栏 → 打 jar → 部署 4 实例**。判据：全护栏 `FAIL 0`；4 个目标全部 `match=True`。
- 谁验收：开发者（本轮只部署、不跑回归；`## 验收` 记 `未验收`）。

## 需求 / 来源

- 开发者反馈（第三次同族）：「哪怕……旁边就是矿，她还是站在原地不动，似乎根本就检索不到那个矿」。
- 前两次同族反馈（`rounds/R916` / `R917`）已按"套用伐木"修过：抬头兜底、立足点、搭高失败兜底、穿透预算放行可开路方块、去掉"本锚点永久排除表"、看门狗改成保留目标。**都修在"选到目标之后"这一段**，没碰"什么算矿"这一层。

## 定位：矿表是"名单制"，伐木是"名单 + 标签制"

现场证据（`logs/latest.log`，2026-10-13）：

1. `00:42:48.922 mine autoCollect: pos=(-1458,79,-702) drops=1` + `00:42:48.926 mine chain burst block=minecraft:coal_ore broken=2` —— **她挖到煤了**，说明整条挖掘链（手持 `cataclysm:infernal_forge`，它是 `PickaxeItem`，`javap` 实证）本身是通的。
2. `00:43:03.680 mine stuck-reset: maid=1029 idle=296t pos=(-1465,73,-693)` → `00:43:33 [CHAT] 附近没有值得挖的矿石`（这条文案出自 `WorkStatusReporter.idleReason` 的 MINE 分支：**导航空闲 + 手上有镐**时报"附近没有值得挖的矿石"）—— 挖到煤之后她再也选不出任何目标。
3. `logs/promaid.log` 同一时段 `[重锤门] 主手=cataclysm:infernal_forge`、`[插火把] 周围有 25 个暗格`、`[离场] … reason=DISCARDED` 反复（收放魂符）——她在暗处的矿洞里，环境正常。

**机制差**（两树逐字一致）：

- 伐木：`MaidWoodBehavior.isWoodState` / `woodValueOf` = **名单内** ∪ `wood.tagAuto` 且带 `#minecraft:logs` / `#minecraft:bamboo_blocks` 标签。
- 挖矿：`MaidMineBehavior.isOre` = **只有** `ORE_VALUE`（`mine.oreValues`）这一张名单——内置与配置默认全是原版矿（19 条）。**没有对应的标签自动识别**。
- 排班侧同样不对称：`ScheduleTaskAvailability.hasWoodInRange` 认 `WOOD_TAG_AUTO` + 标签；`hasOreInRange` 只查 `MINE_ORE_VALUES` —— 附近只有模组矿时，排班软探测会判"没活可干"（女仆不切过去 / 站着）。
- 实测本机四个实例的全部模组：**形如矿石的方块 20 个，未进任何矿石标签的 0 个**（`#forge:ores` / `#c:ores` 全覆盖；1.21.1 实例里是卓越前线的银矿/方铅矿/白钨矿、神秘遗物+ 的以太矿石、诡厄巫法的玉矿石等）。**所以"她检索不到"的那块矿，只可能是名单里没有的模组矿。**

## 改法（两树镜像）

- **`MaidSmartConfig.java`**：新增 `MINE_TAG_AUTO`（`mine.tagAuto`，默认 `true`，`.translation("config.promaid.mine.tagAuto")`），紧挨 `mine.oreValues`。
- **`assets/maid_smart/lang/{zh_cn,en_us}.json`**：补 `config.promaid.mine.tagAuto` 译文（zh「模组矿石标签自动识别」/ en「Auto-detect tagged ores (modded)」）。
- **`MaidMineBehavior.java`**：新增两个标签键 `ORE_TAG_FORGE`（`forge:ores`）/ `ORE_TAG_COMMON`（`c:ores`，两棵树都查同一对——不存在的标签恒不命中，安全）与统一入口 `oreValueOf(BlockState)`：**名单值优先；`tagAuto` 开且带矿石标签 → 300（与内置矿同价）**。`isOre` 与另外五处读取（`recordSkippedOre` 跳过播报、`fullScanOres` 全量扫描、`pickFromCache` 缓存轮、`countBlocking` 挡路预算、`findBlockingBlock` 硬挡路）全部改走 `oreValueOf` —— 口径只有一处。
- **`ScheduleTaskAvailability.java`**：`hasOreInRange` 与伐木同款——`ores.isEmpty() && !tagAuto` 才放行不判；命中判据加 `tagAuto && (标签)`。
- **`tools/verify/check_preset.py`**：`BASELINE_FLIP` 174/178 → 175/179（新开关默认开、随 DIY 关，与 `wood.tagAuto` 同档）。
- **`ARCHITECTURE-SWITCHES.md`**：`gen_switch_inventory.py --write` 重生成（配置项变了）。

## 触及文件

- `promaid_src/com/maidsmart/config/MaidSmartConfig.java`
- `promaid_src/assets/maid_smart/lang/zh_cn.json` · `en_us.json`
- `promaid_src/com/maidsmart/task/MaidMineBehavior.java`
- `promaid_src/com/maidsmart/schedule/ScheduleTaskAvailability.java`
- 以上四件的 Neo 镜像（`promaid_src_neo/...`）
- `tools/verify/check_preset.py`（基线）· `ARCHITECTURE-SWITCHES.md`（重生成）
- `rounds/RELEASES.tsv`（两条 1.3.2 `workspace` 行 sha256 + 第五十九次同号重打）

## 验证

- **两树编译**：`compile_promaid.bat` → `EXITCODE=0`；`compile_neo.bat` → `EXITCODE=0`。
- **全护栏**：`check_comment_style` **PASS**（0 处）；`check_preset` / `check_catch_swallow` / `check_i18n_keys` / `check_dev_voice` / `check_comment_dup` / `check_reflection` / `check_arch` / `check_panel_coverage` **FAIL 0 / WARN 0**；`check_workflow` **FAIL 0 / WARN 4**（历史遗留：PUBLISH_GUIDE 版本、1.2.4 归档哈希、13 个更早 jar 未登记、`ARCHITECTURE-SWITCHES.md` 已按上面重生成）。
- **两个 jar**：`verify_jar_classes` **OK**（mixin 包登记 OK、注入签名 OK；`out` ↔ `jar` class 双向一致）。
- **未跑实机回归**（先停在这，等开发者指挥）。

### 部署（2026-10-13）

- 打 jar：`tools/build/build_promaid.py` → `patched/promaid-1.3.2-forge-1.20.1.jar`（**17582671** 字节，sha256 `AA24D86B…8DEDB5`）；`build_promaid_neo.py` → `patched/promaid-1.3.2-neoforge-1.21.1.jar`（**17619061** 字节，sha256 `9313629B…D34ED3`）。
- 装机：`tools/deploy/deploy_all.py` → 4 个本机实例全部 **`match=True`**（1.21.1-NeoForge_21.1.250 / 1.20.1-Forge_47.4.10 / 龙之冒险：新征程v2.4a / mc_server_test pack1201）；旧包已备份在 `patched/backup_old/`。
- 留痕：`rounds/RELEASES.tsv` 两条 1.3.2 `workspace` 行的 sha256 列更新为新字节，并追加「第五十九次同号重打」记载；R919 旧字节 `a37b300f…b88d5d`（forge）/ `765a7199…44f094`（neo）作废。
- 到此为止：**未跑回归、未验收、未 commit、未 push**（开发者指令："做完功能先部署，部署完等他指挥"）。

## 验收

- 状态：**未验收**（已部署@2026-10-13：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
  - 复现步骤 1（**模组矿能挖**）：站到一块模组矿石旁（1.21.1 实例可试卓越前线的**银矿** / **方铅矿**，或诡厄巫法的**玉矿石**；1.20.1 的「龙之冒险」包更多）→ 开挖矿任务 → 她应当**走过去开挖**，日志出现 `mine autoCollect` / `mine chain burst block=<那块的注册名>`，而不再弹「附近没有值得挖的矿石」。
  - 复现步骤 2（**原版矿照旧**）：原版煤/铁/钻石等仍照常选（名单没动，只是多认了标签）。
  - 复现步骤 3（**开关生效**）：把 `mine.tagAuto` 关掉（或切 DIY 预设）→ 只剩名单里那 19 条原版矿可选（回到旧行为）。
  - 判定：步骤 1 挖到模组矿 = 合格；若仍报"附近没有值得挖的矿石"，请贴现场那块矿的**注册名**（F3 看着方块按 F3+H 或手册里的方块名）与 `logs/latest.log` 里 `mine ` 开头的几行。
- **已知边界（不阻断验收）**：`tagAuto` 只认标签。若某模组的矿石**没有**打进 `#forge:ores` / `#c:ores`，仍需手动写进面板「可挖掘方块表」（`mine.oreValues`）。本轮实测本机四个实例的模组 **未打标签的矿石为 0 个**，所以这条对现有整合包不构成实际缺口。

## 边界（没动什么）

- **没动**：矿表本身（`mine.oreValues` 的默认 19 条、面板勾选语义一个字不变）；挖掘/接近/搭高/连锁/掉落回收/看门狗等全部既有口径；`mine.seekThroughWalls`、`mine.breakBudget` 等数值；伐木侧一个字节不变。
- **不新增行为开关以外的配置项**；版本号仍 **1.3.2**（`WORKFLOW.md` §5 "验收前不升号"）。
- **两树差异只在 SRG ↔ Mojang 名字**：标签键两棵树都用 `forge:ores` + `c:ores`（不存在的那个恒不命中），`oreValueOf` 的取块 / `is` 调用按树映射（`m_60734_`/`m_204336_` ↔ `getBlock()`/`is()`）。
- 临时脚本（`tools/_r920*.py` / `tools/_needle*` / `_minewin*` / `_cat_inspect*` / `_tagscan*` / `_orescan.py` / `_orecover.py` / `_relread.py` / `_rel_tail.txt`）**已删**；仓库根目录既有的 `_*` 文件与 `_tmp_pub` 等未动。

## 台账

- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第五十九次同号重打。
- `DEBT.md`：本轮不新增条目——BD-21 / BD-22（"套用伐木 / 站桩发呆"那两条）已在本轮之前清零；本轮补的正是它们没覆盖到的"什么算矿"这一层，无新增未清项。
