# Promaid 开发规范（DEV_GUIDE）

本文件是本仓库的**成文标准**：注释怎么写、更新内容怎么写、一个功能/bug 从接手到交付要走哪些步骤、
每一步的硬门槛是什么。凡与本文件冲突的旧习惯，一律以本文件为准。

适用范围：`promaid_src`（Forge 1.20.1，SRG 名）与 `promaid_src_neo`（NeoForge 1.21.1，Mojang 名）两棵树，
以及随包发布的文案（lang / i18n / 指南）。

---

## 1. 注释标准

### 1.1 注释只写两件事

1. **现在做什么**（这段代码的职责、输入输出、被谁调用）；
2. **硬约束**（前置条件、单位、取值范围、为什么是这个魔数、两树差异、失效时的表现）。

**不写**变更史（旧版错在哪、这版怎么改的）——那是轮次记录（`rounds/RNNN-*.md`）的职责。
`DEBT.md` §3（机器结构快照）已记录这条取舍。

### 1.2 出处压成一行

历史溯源保留，但**最多一行**，紧贴被说明的成员：

```java
// 实测八百三十：空笼子 / 只刷和平生物的笼子不拆
private static boolean spawnsHostile(ServerLevel level, BlockPos spawner) {
```

一个成员最多一处出处；不要在同一条链路的每个方法上都抄一遍。

### 1.3 硬禁令

注释里**不得出现**：

| 禁止 | 原因 |
|---|---|
| `玩家原话` / `玩家反馈` / `粉丝原话` / `玩家建议原文` | 与代码无关，观感差，且把需求描述混进了实现 |
| `「…」` 引语块（引用玩家/聊天原文） | 同上；需求要写就写成第三人称陈述句 |
| `issue #NN` 以外的问题追踪闲聊 | 需要时只留 `// issue #19` 一行 |

需求本身要留痕，就转写成事实陈述：

```java
// ✗ 玩家原话：「女仆拿着雪球也会主动参战打怪，手特别欠。」
// ✓ 参战前必须过"真能打"判据（有攻击力的武器）；雪球不算武器，否则空手也会被喂目标。
```

### 1.4 长度与形式

- 类头 `/** … */`：**≤ 15 行**，写职责 + 关键约束 + 出处；
- 方法注释：**≤ 8 行**；
- 行内 `//`：**≤ 3 行**，只写这条语句本身讲不清的约束；
- 超过上述长度说明内容属于文档 → 写进 `ARCHITECTURE.md`（机制）或 `机制详解.md` / 游戏内手册（玩法）。
- 方法名/字段名能说清的，不要用注释复述一遍。

### 1.5 允许且鼓励的注释

- 单位与量纲：`// 格、tick（20 tick = 1 秒）`；
- 魔数理由：`// 门限 8：原版怪物生成要求亮度 ≤ 7`；
- 反直觉实现的警告：`// 必须在这之后调用：Brain 会先问 canStillUse，写在那里会让 tick() 根本不执行`；
- 两树差异：`// Forge: m_20275_() ；Neo: distanceToSqr()`；
- 性能/线程约束：`// 只在服务端主线程调用`。

---

## 2. 更新内容标准（**没有 changelog 了**）

**【实测八百五十六】仓库内 changelog 已撤掉**：`CHANGELOG.md` 与 `store/version_changelog.md` 已删除，
GitHub 也不再推送更新日志（更早的实测八百四十四 已撤掉游戏内「更新日志」章与两树
`guide/changelog{,_en}.txt`）。**以后的版本更新内容写在桌面 `C:\Users\Sketch\Desktop\Promaid文本\` 里**，
release 说明从那里取素材（见 `WORKFLOW.md` §6.1）。

**两种读者、两份格式，不要混用。**

### 2.1 轮次记录（dev 面：`rounds/RNNN-*.md`）

给开发/追溯看：过程、根因、证据、边界都写在轮次记录里（模板 `rounds/TEMPLATE.md`）；
其中「改法」一节建议压成要点式、**每条 ≤ 6 个要点**：

```
  · 症状：AI 设计 / 自建图纸一律"存不成蓝图"，聊天里喷 JSON
  · 根因：parseJson 把"现场 JSON"的策略上限（±12 / y≤8 / 白名单）当成所有 JSON 蓝图的门槛
  · 改法：parseJson 只管结构 + 物理上限（512 / 禁方块）；策略上限回到 validate() / looseValidate()
  · 回报：lastJsonError 带真实原因；smart_build 改为提示改用 smart_design
  · 涉及：BlueprintCatalog / BlueprintLib / SmartBuildTool / SmartDesignTool / MaidSmartConfig
```

- 轮次记录的标题与范围由 `rounds/TEMPLATE.md` 规定（如 `R831 蓝图解析器上限归位`，范围写「两树 / 仅 1.21.1 / 仅 1.20.1」）；
- 要点固定顺序：**症状 → 根因 → 改法 → 影响面/涉及文件**（没有的项省略）；
- 保留关键技术证据（反编译结论、门限值、字节偏移）——这是 dev 版存在的意义；
- 不写玩家原话；需要时把需求写成「需求：…」一句事实陈述。

### 2.2 版本更新内容（桌面 `Promaid文本\`）

- **一个版本一个文件**，文件名 / 正文一级标题 = 「版本号 + 这次更新了什么」，例如
  `v1.3.2 拆刷怪笼判定修正与零女仆性能.md`；
- 写法见该目录里的 `写作规则.md`。要点：如实写**目前的状态**，不写开发过程 / 轮次编号 / 谁提的意见；
- **记什么**（修 bug 的任务）：修的是**本版本尚未推送内容**的 bug → **不写**（它还没交付，不算"这一版修好了什么"）；
  修的是**老 bug 且已修完** → **写**。新功能 / 行为变化 / 优化按"玩家能否感知"判断。

### 2.3 发布说明（`release_body*.md` / `store/release_notes_*.md`）

用 `PUBLISH_GUIDE.md` §4.3 的模板：`一句话 / 新功能 / 修复 / 这一版的行为变化 / 完整逐条见 …`；
**素材来源是桌面 `Promaid文本\` 里对应版本的那份文件**（§2.2）。

### 2.4 同步责任

一次要交付的改动落到**两个面**：

| 面 | 文件 |
|---|---|
| 仓库内部开发记录（dev） | `rounds/RNNN-*.md` 轮次记录 + `DEBT.md` 台账 |
| 版本更新内容（发布素材） | 桌面 `Promaid文本\v<版本号> ….md`（按 §2.2 的记 / 不记口径） |

- 玩家能感知的改动才写进 `Promaid文本`；纯流程 / 护栏 / 内部重构只留 dev 面；
- `Promaid文本` 在仓库外，没有护栏能查（`check_changelog.py` 已随 changelog 一起删除），靠 §2.2 的口径自律。

---

## 3. 标准开发流程（9 步，每步有硬门槛）

> **完整流程与门禁见 `WORKFLOW.md`**：那里把工作分成「修 bug」与「开发新功能」两条跑道，每步的命令与硬门槛、版本纪律、文案纪律、性能纪律、债务配额、轮次记录都在那边；本节只保留骨架。

| # | 步骤 | 硬门槛 |
|---|---|---|
| 0 | **定位根因**：grep / 读代码；原版或模组行为不确定时用 `javap` / 反编译 / 实机日志拿出**实证** | 不能靠猜；结论要能指着证据说 |
| 1 | **定口径**：把需求转成可判定的行为；取舍写清（能自行判断的按默认拍板，不反复确认） | 影响玩家行为的开关默认值要明确 |
| 2 | **改 Forge 树** `promaid_src`（SRG 名） | — |
| 3 | **镜像 Neo 树** `promaid_src_neo`（Mojang 名） | 纯 Java/字符串文件逐字节一致；碰 MC API 的按 SRG↔Mojang 表改 |
| 4 | **编译两树** `tools/build/compile_{promaid,neo}.bat` | **EXITCODE=0** |
| 5 | **文案三件套**：lang（4 份）+ i18n（`en_us.json`）+ 指南章节 | i18n 的中文 key 与源码可见文案**逐字节一致** |
| 6 | **打 jar** `tools/build/build_{promaid,promaid_neo}.py` | mixin 登记 OK / 注入签名 OK / lang json OK / MISSING: none；跑完**自动**同步桌面快照（§7） |
| 7 | **部署** `tools/deploy/deploy_all.py` | 4 个目标 SHA1 一致 |
| 8 | **专服回归** `tools/test/test_server.py` + `test_maid_load.py` × 2 加载器 | 全部 PASS |
| 9 | **汇报** | 做了什么 / 为什么 / 边界（哪些**没动**）/ 验证结果 |

### 3.1 硬纪律

- **不 commit、不 push**，除非明确要求；
- 不改已发布产物、不动 `_tmp_pub/`；
- 临时脚本一律 `tools/_*.py`，**用完即删**；
- 一次性脚本也要能重跑（幂等）：先 `count == 1` 断言再替换，失败就整体不写。

---

## 4. 两树镜像规则

| 文件类型 | 规则 |
|---|---|
| 纯 Java 逻辑 / 纯字符串常量（不碰 MC 类型） | 两树**逐字节一致**（可直接复制） |
| 碰 MC API 的类 | 手工镜像：按 SRG↔Mojang 名替换（`m_9236_()`→`level()` 等），只改名字不改结构 |
| 资源文件（`assets/`） | 两树一致 |
| 配置项 / 面板 / 指南 | 两树一致，且与 i18n 表联动 |

镜像后必须**两边都编译**；只编一棵树等于没做。

---

## 5. i18n / lang / 指南联动规则（改动文案时必看）

1. **i18n 表**（`assets/promaid/i18n/en_us.json`）是 `{中文原文: English}` 的平表，key 就是源码里
   显示给人的那句中文。**改源码文案 = 必须同步改 key**（否则英文界面回落到中文）。
2. **lang 文件**（`assets/maid_smart/lang/{zh_cn,en_us}.json`）按配置键组织，与 i18n 表**并行维护**。
3. **指南章节**（`com/maidsmart/guide/GuideChapters*.java`）的段落文本同样是 i18n 的 key，
   必须与 key 逐字节一致（含 `\n` 转义与 `§` 的表示差异：源码用 `\u00a7`，i18n 里是真 `§`）。
4. 更新内容**不是** i18n key（版本更新内容写在桌面 `Promaid文本\`，两树源码里没有）。
5. 编码约定：lang / guide 的 JSON **带 BOM**（沿用现状）；`en_us.json`
   **不带 BOM、LF**；改完必须重新解析 JSON 验证（`json.loads` 通过）。
6. **配置注释也是 i18n key**（易踩）：`MaidSmartConfig` 里 `BUILDER.comment("…")` 的整串中文
   ——**含玩家原话与全部 `\n`**——就是 `i18n/en_us.json` 的 key，其 value 是英文译文
   （英文里也照译了 "Player's original words: …"）。所以改一条配置注释要同时改三处且**逐字节一致**：
   两树源码里的那个字符串常量、两树 `en_us.json` 的 key、它的英文 value。
   （`assets/maid_smart/lang/*.json` 是配置项**名称**表，键是配置键名，与这条无关。）

---

## 6. 自动化护栏

| 脚本 | 作用 | 何时跑 |
|---|---|---|
| `tools/verify/check_comment_style.py` | 扫描两树：注释禁引语/禁超长块；**字符串字面量（玩家可见文案）也不许有引述玩家字样** | 每次改代码后；已接进 `build_promaid*.py` |
| `tools/verify/check_preset.py` | 校验预设模式分类表：路径存在性 / 表间不重叠 / 布尔分类条数基线（Forge 141、Neo 145）/ 默认 false 白名单 / mixin 闸真的引用到配置项 / 两树表一致 | 改配置项或动预设时；已接进 `build_promaid*.py`（`--warn`） |
| `tools/verify/check_workflow.py` | 工作流门禁（`WORKFLOW.md`）：两树版本不一致 / 同号覆盖 / 四处版本一致性 / 轮次记录 / 台账格式 / §8 债配额 / 配置清单过期 | 每轮改完；已接进 `build_promaid*.py`（`--warn`） |
| `tools/verify/check_catch_swallow.py` | 吞异常棘轮：数注释之外的**未留痕**静默吞异常（裸 `catch (... ignored)` 或具名却体首只 `return`/注释/空，且不是 `Diag.swallow`），高于基线 FAIL、低于基线 WARN（只减不增） | 每次改代码后；已接进 `build_promaid*.py`（`--warn`）；见 TD-14 / TD-18 |
| `tools/verify/check_i18n_keys.py` | i18n 键漂移棘轮：源码里有 `.translation("k")`、lang 表里没有 `k` 的处数只减不增（防"英文界面回落中文/键名"） | 改配置项或加开关时；已接进 `build_promaid*.py`（`--warn`）；见 TD-07 |
| `tools/verify/check_dev_voice.py` | 开发口吻棘轮：可见文案（两树 `assets/promaid/i18n/en_us.json` 与 `assets/maid_smart/lang/en_us.json` 的 value）里禁 `test/实测` 轮次号与第一人称复数 `we/our/us/ours`，处数只减不增 | 改任意可见文案时；已接进 `build_promaid*.py`（`--warn`）；见 TD-03 |
| `tools/verify/check_comment_dup.py` | 重复注释棘轮：把两树源码里所有 `.comment("…")` 正文（去空白后）分组，同一段文字出现在多处即算重复；仅 4 段共用机制的固定文案在白名单，其余重复组只减不增 | 改配置项注释时；已接进 `build_promaid*.py`（`--warn`）；见 TD-02 |
| `tools/verify/check_reflection.py` | 探针式反射棘轮：数两树源码（去注释后）里的 `Class.forName(` / `getMethod(` / `getDeclaredMethod(` / `getDeclaredField(` 处数，高于基线 FAIL、低于基线 WARN（只减不增） | 每次改代码后；已接进 `build_promaid*.py`（`--warn`）；见 TD-15 |
| `tools/verify/check_mixin_targets.py` | mixin 注入点审计：拿目标类 + 父类链的**真实方法表**（`javap -s` 的 JVM 描述符）核对每个 `method="…"`——写全的必须存在、裸名字不许有"同名不同签名"（会命中协变桥接）、`@Inject` 处理方法参数必须 = 目标参数 + 回调；支持多 `@Mixin` 文件与跨行字符串拼接；单棵树「已校验注入点」不足 100 判空转 FAIL | 每次改 mixin / 换依赖版本后；**已接进 `build_promaid*.py` 作硬闸门（不过不出包）；见 R951** |
| `tools/verify/gen_switch_inventory.py` | 生成 `ARCHITECTURE-SWITCHES.md`（按节 + 按主题聚合配置项），改行为前先查、新开关必须进 | 改配置项后 |
| `tools/verify/verify_jar_classes.py` | 打包后自检（mixin 类登记 / class 齐全；注入签名判据已搬到 `check_mixin_targets.py`） | 打 jar 后（已有） |

这一批护栏**目前以 `--warn` 形式接进构建**（注释 / 吞异常 / 缺译文存量未清零，先告警不阻断）；
存量清零后把 `build_promaid*.py` 里那行的 `--warn` 去掉即改为**阻断出包**。
（`check_mixin_targets.py` 例外：它是**硬闸门**，在打包前先跑，不过直接拒包。）

### 6.1 「引语」到底禁什么

护栏只把**逐字玩家原话**判为违规：长引语（> 30 字）且内含第一人称，或行内出现
`玩家原话 / 粉丝原话 / 用户原话 / 玩家反馈 / 原话照录 / 原话：` 这类引述标记。

`「传送到我身边」` 这类 UI 串、`「大运」` 这类功能名、面板路径**不算违规**——
它们是标识符，不是玩家的话。这条口径由 `check_comment_style.py` 的 `is_speech_quote()` 实现。

护栏的意义：标准**不靠自觉**——违规要么让校验失败，要么让构建失败。

---

## 7. 目录与工具地图

```
promaid_src/            Forge 1.20.1 源码（SRG 名）
promaid_src_neo/        NeoForge 1.21.1 源码（Mojang 名）
  assets/maid_smart/lang/    配置项 lang（4 份）
  assets/promaid/i18n/       {中文: English} 平表
  assets/promaid/guide/      游戏内手册资源
tools/build/            生成 argfile / 编译 / 打包
tools/deploy/           部署到 4 个本地实例；`sync_desktop_snapshot.py` 同步桌面快照
tools/test/             专用服务器回归（启动 / 女仆大脑加载 / 单项行为）
tools/verify/           校验护栏（jar / 注释 / 预设 / 工作流 / 清单）
patched/                构建产物（jar）+ 部署备份
store/                  平台文案母本（gitignore，本地维护）
WORKFLOW.md             开发工作流与门禁（两条跑道 / 版本纪律 / 债务配额）
DEBT.md                唯一债务台账（手工 + 机器快照，每轮更新）
ARCHITECTURE-SWITCHES.md     配置项清单（自动生成）
rounds/                 每轮记录 + RELEASES.tsv 发布哈希登记
DEV_GUIDE.md / WORKFLOW.md / PUBLISH_GUIDE.md / GIT_SYNC.md   成文标准与流程
```

桌面快照 `C:\Users\Sketch\Desktop\Promaid_本体当前`（**固定名，不带日期**）由
`tools/deploy/sync_desktop_snapshot.py` 在**每次出包后自动刷新**（两个 `build_promaid*.py` 末尾调用，
以 `--quiet` 模式只打一行 `SNAPSHOT: …`）：`source/` = 工作树镜像（跳过一切 `_` 开头的临时物）、
`01_jar/` = 刚打出的两个 jar、`说明.txt` = 按当前 `RELEASE_NOTES_v1.3.2.md` 标题与 jar 哈希**重新生成**。
所以**不需要再手动刷**；要单独刷一遍就 `python tools/deploy/sync_desktop_snapshot.py`。
镜像范围内快照多出来的旧文件会被自动删掉（多退少补），但不动 `source/` 之外用户自己放的东西。

两个 `build_promaid*.py` 打包时把 jar 内每个条目的归档时间戳**固定成 `2020-01-01`**，于是
**同一份源码必出同一个 sha256**：`patched/`、快照 `01_jar/`、四个部署目标三处 hash 永远一致，
可以直接拿 hash 核对"是不是同一份"，重建也不会再漂移（否则每次打包时间进 jar 字节，hash 必变）。

---

## 8. 快速自查清单（提交前逐条打勾）

- [ ] 两棵树都改了、都编译 EXITCODE=0
- [ ] 注释：无引语、无变更史、出处 ≤ 1 行、长度达标
- [ ] 版本更新内容（玩家能感知的那部分）写进了桌面 `Promaid文本\` 该版本那份（按 §2.2 口径）
- [ ] 文案改动：i18n key 逐字节一致 + lang 同步 + JSON 能解析
- [ ] `check_comment_style.py` / `check_preset.py` / `check_workflow.py` 通过
- [ ] 打 jar 自检 OK；需要时部署 + 专服回归 PASS
- [ ] 临时脚本已删；没有 commit / push
- [ ] `rounds/RNNN-*.md` 已写（含 `## 验证` 与 `## 边界`）；台账已更新、§8 配额满足
- [ ] 版本号按 `WORKFLOW.md` §5（该升就升；交付过就冻结并在 `rounds/RELEASES.tsv` 登记）

---

## 9. 版本 ↔ 实测编号对照

`实测NNN` 是本仓库的**轮次编号**，与发布版本不是一回事（编号是工作区里的连续计数，
git tag 上并不存在）。发布版本区间由这几处实证锁定：

| 发布版本 | 实测区间 | 出处 |
|---|---|---|
| v1.3.2 | 八百五十九 ~ 九百九十九 | 本次发布，`RELEASE_NOTES_v1.3.2.md` |
| v1.3.1 | 七百九十五 ~ 八百五十六（+ 857/858 同号覆盖重传） | `RELEASE_NOTES_v1.3.1.md`、`store/release_notes_1.3.1.md` |
| v1.3.0 | 七百四十九 ~ 七百九十四（beta 749~763 + 正式 764~794） | `store/release_notes_1.3.0.md`（「相对首次 beta 上传：实测七百四十九 ~ 七百六十三」） |
| v1.2.5 及更早 | 旧版按版本号分组写（`## vX.Y.Z：…`），未用编号 | 本地留档 `_cleanup_backup/version_changelog_legacy_full.md`（实测八百五十六 起移出工作区） |

再往前（v1.2.4 及更早）历史日志本来就是**按版本号分组**写的（`## v1.2.4：…`），
编号只覆盖较近的一段。把这两套口径合一，是 player 版全量改写的前置工作。

## 10. 规范化进度（截至 2026-10-11）

已完成：

1. **v1.3.1 窗口**（41 轮）在 6 个面全部按新格式重写：dev 版 41 条要点式、player 版 49 条一行式（中英条目数一致）；窗口内违规 0。
2. **引语清洗**（全历史）：6 个面共删/改约 1900 行玩家原话与「…」引语块；两树注释清空 2251 行引语。
   - `check_changelog.py`：`BANNED` 0、`QUOTE-BLOCK` 0、`QUOTE-SPEECH` 0。
   - `check_comment_style.py`：`BANNED` 0、`QUOTE-BLOCK` 0、`QUOTE-SPEECH` 0。
3. **注释去引语安全闸**：255 个 java 文件，剥掉注释后**逐字节比对新旧源码完全一致**（证明只动了注释）；
   两树 `javac EXITCODE=0`。
4. **玩家可见文案去引语**（配置注释 / 面板 / 指南）：两树共改写 116 处字符串字面量，
   把 `玩家原话「X」` 一律改成中性的 `需求：X`（`后又追加「Y」`→`；需求补充：Y`；
   英文 `Player's original words: "X"` 等 11 种写法统一成 `Requirement: X`）。
   同步改了 `i18n/en_us.json` 的 **key（改名）+ 英文 value**（两树各 61 条），表内也清到零。
   验收：`json.loads` 通过、键数不变（6868）、字面量个数不变且每个改写都 == 预期变换（116/116 相符）、
   两树 `javac EXITCODE=0`。
   - 口径：只有**引述玩家**才算违规；`「大运」`/`「传送到我身边」` 这类名称与 UI 串照旧保留。
   - 有 5 条长注释在 i18n 表里**没有对应条目**（源码后来改了、表没跟上）——只改了源码，没动表。
5. **实测八百三十二 搭路/垫脚方块超距回收失效修复**（两树）：`PlacedBlockTracker.expirePlaced` 的到期分支改为
   「同维度内**不分远近**一律回收进绑定女仆背包；只有女仆死亡（`reclaimAllFor`）与跨维度这两件意外才就地落地」，
   并让 `bridge.reclaimToMaid` 真正生效（此前到期路径从未读它，828 还把它硬编码成了"超距即落地"）。
   配置注释与面板说明按新口径改写，两树 `i18n` key/英文同步（键数 6868 不变）；6 个 changelog 面加 832 一轮。
   验收：两树 `javac EXITCODE=0`；两 jar 重打（mixin / lang / `MISSING: none` 全过）；
   `check_changelog` 仍 100,148、`check_comment_style` 仍 2260（**无新增违规**）。
6. **桌面快照自动化 + 可复现打包**：`tools/deploy/sync_desktop_snapshot.py`（常驻）＋两个 `build_promaid*.py` 末尾挂钩，
   每次出包自动把 `Desktop\Promaid_本体当前`（**固定名**，已从 `_20261009` 改名）刷成与仓库一致；
   同时把 jar 内归档时间戳固定成 `2020-01-01`（同一份源码必出同一 sha256）；`说明.txt` 改为按
   本版标题（实测八百五十六 起读该版 `RELEASE_NOTES_vX.Y.Z.md`）+ jar 哈希**生成**（不再手写）。
   旧的 `tools/_refresh_snapshot.py` 已删。

7. **实测八百三十五 手册「调试 / 预设模式」（两树）**：新增 `MaidConfigPreset`（反射枚举全部 `static ConfigValue`
   ＋ KEEP/SKIP/FORCE_ON/VALUES/RESET_KEEP 分类表）、手册首页第 6 个入口、预设页（DIY / 默认，各带二次确认），
   并补齐 5 处"无开关的介入"（新增 3 个可见开关 `misc.pickupValueReorder` / `tlmTeleportExempt` /
   `misc.scheduleRangeFloor`，另 2 处由 `combat.fleeWithSaveItem` / `combat.selfPreserve` 覆盖）。
   新增护栏 `tools/verify/check_preset.py`（已接进两个 `build_promaid*.py`，`--warn`），首跑即抓出
   「FORCE_ON 路径写成 `misc.fleeWithSaveItem`」与「注释里的中文引号被当作分类表内容」两处真错。
   验收：两树 `javac EXITCODE=0`；自测（反射枚举 575/581 项、`planDiy` 147/151、DIY 后 plan 归零、
   默认往返 mismatch 0）全 PASS；`i18n` 两树各 +32 条 key（逐字节核对 0 失败）+ lang 4 份各 +4 键；
   两个护栏脚本计数不变（`check_changelog` 100,148 / `check_comment_style` 2260）。

8. **实测八百三十六 开发工作流成文（仓库级）**：issue #37 的七条批评逐条映射成门禁，写进 `WORKFLOW.md`（两条跑道 + 共用 10 步链路 + 版本·文案·性能纪律 + 债务配额 + 轮次记录 + DoD）；新增台账 `BUG_DEBT.md` / `TECH_DEBT.md` 与 `rounds/`（模板 + `RELEASES.tsv` 发布哈希登记）；新增护栏 `check_workflow.py`（版本纪律 / 同号覆盖 / 轮次记录 / 台账格式 / §8 配额 / 清单过期）与 `gen_switch_inventory.py` → `ARCHITECTURE-SWITCHES.md`（按节 + 按主题聚合，主题索引答"搭路有几个开关"= 16 项）。首跑标出现存问题：版本四处不一致（`mods.toml` 1.3.0 / CHANGELOG v1.3.1 / README v1.3.0 / PUBLISH_GUIDE v1.2.4）、1.2.4 归档副本与发布登记不同字节（按 `archived` 留痕）、实测八百三十五 功能轮遇未清 P1 债 `BD-04`（配额违规，如实记录）。无运行时改动、无新配置项、player 面不动、未升版本号。

9. **实测八百五十五 游戏内文案瘦身（两树）**：可见文案「实测NNN」清零 + 面板注释全部压到 ≤100 字。
   两树配置注释 77/81 条、面板注释 422 行（F 208 / N 214 个字面量 span——按"整段拼接注释"口径逐行核，
   旧清单只覆盖 314 行，其余 108 行另写 112 条）、命令回执 3 处（`\uXXXX` 转义）、`mods.toml` 描述与
   `compression_box.json` 署名；两树 `i18n` key/英文同步（撤 47/49、建 55/57、还原被误删的共用键 6/6、
   清陈旧 28/22、补漏键，净键数 6,995/6,992）。
   验收：两树 `javac EXITCODE=0`；面板 >100 字 = 0、可见「实测」/`**` = 0、行参数完整（`bad_args`=0）；
   **两个 jar 全条目按字节**搜「实测」= 0（含 class 常量池）；`verify_jar_classes` OK；四护栏回基线
   （`check_comment_style` 2,258 / `check_preset` 0-2 / `check_workflow` 0-2 / `check_changelog` 1,261）；
   `ARCHITECTURE-SWITCHES.md` 存量表 0/0。过程留痕：第一版落盘脚本替换 span 越界（会连行首参数一起吃掉），
   已从桌面快照逐行对账（314/314 面板 + 158/158 配置锚点）后还原、改成"只动最后一个纯字面量参数"重落。

10. **实测八百五十六 撤掉仓库 changelog + 「大运」佩戴者免疫撞墙（两树）**：
    ① 按开发者决定删除仓库 changelog：`CHANGELOG.md`（1.97 MB）与 `store/version_changelog*.md` 移除，
    原文留档 `_cleanup_backup/*.20261011-predelete`（仅本地）；GitHub 不再推送更新日志，版本更新内容
    改由桌面 `Promaid文本\` 记录，release 说明从那里取素材（`WORKFLOW.md` §6.1 / `DEV_GUIDE.md` §2）。
    同步改动：删护栏 `check_changelog.py`；`check_workflow.py` 去掉 CHANGELOG 依赖（轮次取 `rounds/` 最新 RNNN、
    版本位去掉「CHANGELOG 首行」、撤掉「进了 changelog 必须已验收」门禁）；`sync_desktop_snapshot.py`
    改按该版 `RELEASE_NOTES_vX.Y.Z.md` 标题生成 `说明.txt`；两个 `build_promaid*.py` 护栏列表去一项；
    `publish_modrinth.py` 的平台 changelog 比对改读 `release_body*.md`；文档同步（`DEV_GUIDE` §2/§6/§7/§8/§9、
    `WORKFLOW` §5/§6/§9/§11、`README`、`PUBLISH_GUIDE`、台账、`机制详解`、`arch_modules`）。
    ② 新功能「**大运**」佩戴者免疫撞墙（`daYunNoWallDamage`，默认开；只认「佩戴者（玩家 Curios / 女仆 TLM 饰品栏）
    + 正在鞘翅滑翔 + 原版 `flyIntoWall`」，纯服务端结算），新增面板行 / 指南第四章 / tooltip 第 5 行 / i18n 与 lang 键；
    `check_preset` 基线 151/155。验收：两树 `javac EXITCODE=0`；部署 4 目标与 `patched/` 逐字节一致；
    专服两加载器各两件回归全 PASS、0 条与 promaid 相关的 ERROR。**未验收**（等开发者实机撞墙验证）。

**剩余存量**（下一步分批做，护栏计数即进度表）：

| 项 | 数量 | 说明 |
|---|---|---|
| 注释块超长 | 2,258 处 | `LONG-BLOCK`：类头 > 15 行、方法注释 > 8 行，需要逐文件判断该删还是该搬到 `ARCHITECTURE.md`（八百三十七 净减 2：拆刷怪笼那两个方法注释压到 8 行内） |

> changelog 结构性改写这一项已随 changelog 一起消失（实测八百五十六 删除 `CHANGELOG.md` / `store/version_changelog*.md`）；
> 上文第 2/5/7/8/9 条里出现的 `check_changelog` 计数是**当时**的留痕，该护栏现已删除。

工具：一次性迁移脚本放在 `tools/_*.py`（`_slice/_assemble_v131/_apply_v131/_dequote{,_2,_3}/_comment_dequote`），
**属于在飞工具，全部做完后连同 `tools/_out_*.txt` 一起删除**；改写前的原文完整留档在 `_bak_style/`。
