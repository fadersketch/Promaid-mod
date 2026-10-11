# 实测八百八十五 · TD-03 收口：可见文案里的「开发过程」清零（英文口吻 + 轮次号 + 中文裸轮次号）

## 需求 / 来源

TD-03：「可见文案里的开发过程——配置注释/面板/手册里写 `实测NNN` 与『某轮修了什么』」。台账把剩余工作记成 **③ 英文 value 里属配置注释/女仆台词/自测串的 `we/our/us`（约 150 处，非手册面）**。开发者：「剩下的你逐一修复。我们必须要根除 issue37 和 38 中的问题」。

约定不变：不跑回归、不写 changelog / release 说明、版本号不动（仍 **1.3.2**）、两树镜像、临时脚本用完即删、**不 commit / 不 push**（WORKFLOW §12）。

## 评估（比台账写的范围更大，一并清）

动手前先量化，发现三件事：

1. **英文口吻**：`assets/promaid/i18n/en_us.json` 里含 `\b(we|our|us)\b` 的 value 不是"约 150"，是 **30 条/树**（另加 `ours` 2 条）——台账的估算偏高，逐条改得完。
2. **英文轮次号**：同一张表里还有一批**英文独有**的自测轮次号（中文 key 已清、英文 value 没跟）——`Test/Tested/test NNN`、`【Tested 673】`、`(tested 580)`、`tested at 692`、`playtest 672`、`measured at 691`、`since 614`、`the 726 version`、`tested in v1.2.2 on 608 / 611 / ...`，以及裸轮次号 641/642/679/726/601/676/814/170 等，共 **77 处/树**。这与 ③ 同类（都是"玩家看到开发日志"），一并对付。
3. **中文裸轮次号**：中文可见字面量（= i18n 表的 key，源码里也有一份字面量）里还有 **15 处/树** 残留——面板长描述与压缩盒自测串里的 `六百二十` / `六百四十一` / `六百一十四 起` / `七百二十六 那一版` / `814`，以及迁移回执里的 `679`。①② 那两轮只搜了「实测」二字，中英混排的裸轮次号漏了。另发现手册「开发者寄语」里有一句**纯自我表扬**（"整个项目的测试次数已经超过 300 次"）。

结论：③ 的英文口吻是最小面；真正要"根除 issue #37 第 3 条"还得把 ② 的英文漏网（轮次号）与中文裸轮次号一起清掉，并按 ① 的口径**留一条棘轮护栏**防复发。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 量化 | 逐表量出英文口吻 / 英文轮次号 / 中文裸轮次号三类残留的**准确条数与形状** | 分表清单（本轮） | ☑ |
| 2 | 实现-A | 两树 `promaid/i18n/en_us.json` 英文 value：删 `we/our/us/ours`（女仆台词/玩家引语改无人称），删自测轮次号（保留其解释文字） | 2 文件 | ☑ |
| 3 | 实现-B | 两树 `promaid/i18n/en_us.json` 英文 value：其余轮次号写法（`tested at`/`playtest`/`measured at`/`since`/`v1.2.2 on 608..`/裸号）逐条改写 | 2 文件 | ☑ |
| 4 | 实现-C | 删手册「寄语」那句纯开发自夸（源码 + key 同删）；中文可见字面量 15 处/树去号（**源码字面量 + i18n 键同步改名**） | 两树 guide/config/box + 2 文件 | ☑ |
| 5 | 护栏 | 新增 `tools/verify/check_dev_voice.py`（`test/实测` 轮次号 + `we/our/us` 双判据，两树两表基线 0/0），接进两 `build_promaid*.py`；三份文档护栏表由 5 增至 6 | 1 脚本 + 5 文档 | ☑ |
| 6 | 验证 | 两树编译、打包、六护栏（新护栏 0/0）、jar 内无残留、JSON round-trip 逐字节 | 构建输出 | ☑ |
| 7 | 留痕 | `RELEASES.tsv` 第四十五次 + 本文 + `TECH_DEBT` TD-03 收口 | 两处 | ☑ |
| 8 | 验收 | 交开发者：英文重写措辞与中文去号是否认可 | 待开闸 | ☐ |

## 改法

### A. 英文 value（两树，共 239 处改写操作）

- **口吻**（85 处操作）：`(620, the one the user asked about)`→去号；`We've arrived`→`Arrived — mind the landing`；`Sit tight, here we go`→`Setting off`；`our rope`→`this mod's rope`；`both we and YSM`→`both this mod and YSM`；`Master, what's our goal?`→`what's the goal?`；记忆日记示例问句 `what did we do last month?`→`what happened last month?` … 一律"作用 + 取值"或无人称。
- **轮次号**（77 处/树）：`【Tested 677】`→删前缀；`【Test 686: default 15 → 12】`→`【default 15 → 12】`；`(tested 580)`→`（去括号词）`；`— tested at 578: do not cast`→`— do not cast`；`(off by default, tested in v1.2.2 on 608 / 611 / ...)`→`(off by default)`；`since 614, spells are validated`→`spells are validated`；`641 copied the player's hit`→`copying the player's hit`；`the 726 version`→`the old version`；`(actual test on 170 saves:`→`(as seen on real saves:` …（**只删轮次号，保留其后的解释文字**——那部分中文 key 里本就有）。
- 手法：用 `json` 载入 → 对 value 做**精确子串替换**（每条 old 都断言能命中，否则整批中止不落盘）→ 写回时**保留文件末尾那一大段空白填充**（`raw.startswith(dumps)` 校验 + 原样拼接后缀，逐字节 round-trip）。

### B. 手册 / 中文可见字面量（两树，15 键/树 + 删 1 句）

- **删句**：`GuideChaptersBasics.foreword()` 里 `"整个项目的测试次数已经超过 300 次，算得上一个不小的工作量。",` 整行删除（源码两树），并从 `en_us.json` 删掉对应 key。
- **去号**：`（六百二十，用户要的那条）`→`（用户要的那条）`、`六百四十一 照搬玩家那一记`→`原版照搬玩家那一记`、`——六百一十四 起挑法术前`→`——挑法术前`、`七百二十六 那一版`→`旧版`、`本批同时去掉了 679 那道`→`本批同时去掉了那道`、`移动抑制/814 同一条规矩`→`移动抑制 同一条规矩`、`默认值修复：把 679 误当默认…`→`把误当默认…` 等。
- 这些字面量**同时是 i18n 表的 key**：脚本对**源码全部 `.java`** 与 `en_us.json` 的 **key** 施加**同一组替换**，两边同步改名；改名后断言**无键冲突**、且新键集合里再无裸轮次号。

### C. 护栏

`check_dev_voice.py`：扫两树两表（`assets/promaid/i18n/en_us.json`、`assets/maid_smart/lang/en_us.json`）的 **value**，判据 = `实测` / `(?<![A-Za-z])(test|tested)\s*\d` 轮次号 **或** `\b(we|our|us|ours|ourselves)\b`；高于基线 FAIL、低于基线 WARN（棘轮只减不增），基线两树两表全 **0**。注意 `[[@WORK:BUILD:指标石|Marker Stone]]` 这类 wiki 链接**目标锚点**里的中文不是玩家可见字样，脚本只查可见正文，不误报。

### 不做

- **不动**行为 / 开关 / 默认值 / 版本号。
- 源码注释里的「出处一行」（`// 实测NNN：…`）按 `DEV_GUIDE.md` §1.2 **保留**（不进 jar，本轮未动）。

## 触及文件

**文案（两树）**：`assets/promaid/i18n/en_us.json`（值 119/120 处 + 键 15 处 + 删 1 键）。
**源码（两树）**：`guide/GuideChaptersBasics.java`（删 1 行）、`config/MaidSmartConfig.java`、`config/PromaidConfigScreen.java`、`box/CompressionBoxCheck.java`、`ProMaidMod.java`（字面量去号，与键同步）。
**工具 / 文档**：`tools/verify/check_dev_voice.py`（新）、`tools/build/build_promaid.py`、`tools/build/build_promaid_neo.py`、`WORKFLOW.md` §11、`DEV_GUIDE.md` §6、`BUILD.md` §5、`TECH_DEBT.md`、`rounds/RELEASES.tsv`、`rounds/R885-*.md`（新）。

## 验证

| 项 | 结果 |
|---|---|
| 新护栏 | `check_dev_voice` FAIL 0 / WARN 0 —— 两树两表残留 **0 / 0 / 0 / 0** |
| 英文口吻/轮次号 | `en_us.json` value 里 `we/our/us/ours`、`test/实测\s*\d`、裸轮次号（641/642/679/726/608../814…）= **0** |
| 中文裸轮次号 | 表 key 里 `[四五六七八九]百` / `679` / `814` = **0** |
| JSON 合法 / 保真 | 两树 `json.load` OK；写回**保留末尾空白填充**、逐字节 round-trip 校验通过 |
| jar 内无残留 | 打包后 class + i18n 内搜 `实测` / `测试次数已经超过` / `六百二十` / `六百四十一` = **0 / 0 / 0 / 0**（两树） |
| 两树编译 | `javac EXITCODE=0` |
| 既有护栏 | `check_preset` 0/0（174/178）；`check_i18n_keys` 0/0；`check_workflow` 0/3；`check_catch_swallow` 0/0；`check_comment_style` 931/2260；`check_panel_coverage` 0/0 |
| 打包自检 | 两树 `MISSING: none`；Forge **17,609,934 B**（`befe23fc…0dce07`）、Neo **17,649,112 B**（`64f21c9f…b988e8`） |
| 部署 / 回归 | **未部署、未回归**（开发者闸门） |
| commit / push | **未做**（WORKFLOW §12） |

**反证**：往任一 value 里加回 `tested 580` 或 `our wings` → `check_dev_voice` 该表残留 0 → 1 > 基线 → FAIL；去掉 → PASS。

## 验收

**未验收**（英文重写后的措辞、以及把中文面板描述里的轮次号删掉后的可读性，待开发者定）。

## 边界

- **仅文案 / 键名**：不改行为、开关、默认值、版本号（1.3.2）。
- **口径**：轮次号只删"编号"，其后的**解释文字一律保留**（那部分中文 key 里本就有、玩家需要）；女仆台词与玩家引语改成无人称，不改变语义。
- **未动的近邻**：`assets/maid_smart/lang/*` 的 value 本轮判据为 0，未改；源码注释的「出处一行」按 §1.2 保留。
- 仍未做：TD-12①（非每 tick 的 `EntitySnapshot` 审计）、TD-13②（可复现构建）。

## 台账

- **TD-03 收口**：③ 英文口吻 + 英文轮次号 + ④ 中文裸轮次号全部清零，并新增棘轮护栏 `check_dev_voice`（两树两表 0/0）。
- 注：①② 那两轮"删净实测"只搜了「实测」二字，中英混排的裸轮次号（六百二十 / 679 / 814 等）与英文 side 的轮次号直到本轮才清；护栏已补上这两类判据。
