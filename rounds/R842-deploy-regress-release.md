# 实测八百四十二 · 部署 + 专服回归 + v1.3.1 文案结账（837 ~ 841）

## 需求 / 来源

开发者开闸（本轮起因，紧接实测八百四十一）：

> 开始跑部署和回归，然后写changelog和release声明

即：①把当前产物装到本机 4 实例；②跑专服回归（`test_server` + `test_maid_load` × 两加载器）；
③把实测 837 ~ 841 这五轮按 `DEV_GUIDE.md` §2 的**六个面**写进 changelog；④写 v1.3.1 的发布声明
（按 `WORKFLOW.md` §6.1：以远程上一次发布 v1.3.0 为基线取增量、开发者口吻）。

本轮为**流程/文档轮**（按 `WORKFLOW.md` §6：只落 dev 面，不占 player 面额度）。

## 评估

- 回归脚本两件：`tools/test/test_server.py`（起专服等 `Done (` + 30 秒观测）与
  `tools/test/test_maid_load.py`（起服 → 召唤女仆逼出 brain-task mixin 注入 → 查 mixin 失败行）。
  必须两加载器各跑一遍（1201 / neoforge1211）——`test_server` 看不到 brain mixin 的注入失败。
- 六个面（`DEV_GUIDE.md` §2.4）：`CHANGELOG.md`、`store/version_changelog.md`、
  两棵树的 `assets/promaid/guide/changelog.txt` 与 `changelog_en.txt`。其中 4 份 guide 文件
  在 jar 里 → 改完必须**重打包 + 重部署**（本轮会再同号重打一次，按 §5.2 在 `RELEASES.tsv` 留痕）。
- 版本号仍不动（1.3.1）。`check_workflow` 要求：进了 CHANGELOG 的轮次（>836）其轮次记录必须
  `已验收` → 837 ~ 841 五份记录的 §验收 要先翻成已验收（依据 = 开发者开闸 + 本轮回归通过）。
- release 声明落盘（§6.1 第 3 条）：`store/release_notes_1.3.1.md`（平台母本）+ 桌面成品一份；
  仓库内 `RELEASE_NOTES_v1.3.1.md` 定稿、`release_body*.md`（GitHub Release 正文）同步到 1.3.1。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 侦察 | 读回归脚本用法、六个面格式（DEV_GUIDE §2）、护栏规则（check_changelog / check_workflow）、release 文件现状 | 本文 §评估 | ☑ |
| 2 | 交付 | `deploy_all.py` 装本机 4 实例 | deploy 输出 | ☑ |
| 3 | 回归 | `test_server` ×2 + `test_maid_load` ×2（两加载器） | 回归日志 | ☑ |
| 4 | 验收 | 837 ~ 841 轮次记录 §验收 → 已验收（开发者开闸 + 回归通过） | 5 份记录 | ☑ |
| 5 | 文案 | dev 面：`CHANGELOG.md` / `store/version_changelog.md` 补 837 ~ 842 六条 + 首行范围与轮数 | 2 个文件 | ☑ |
| 6 | 文案 | player 面：4 份 guide changelog 补条目（zh 两树一致、en 两树一致、条目数 zh=en） | 4 个文件 | ☑ |
| 7 | 文案 | release 声明：`store/release_notes_1.3.1.md` + `RELEASE_NOTES_v1.3.1.md` 定稿 + `release_body*.md` + 桌面成品 | 4~5 个文件 | ☑ |
| 8 | 文案 | `PUBLISH_GUIDE.md` 版本号与 §4.3 对齐 1.3.1（清 §5 的 WARN） | 1 个文件 | ☑ |
| 9 | 验证 | 重打包两树 + 重部署（文案进 jar）+ 四护栏不增 + RELEASES.tsv 留痕 | 构建/护栏输出 | ☑ |

## 改法

见排班；文案口径按 `DEV_GUIDE.md` §2（dev：症状→根因→改法→涉及，每条 ≤6 要点、≤20 行；
player：一行一条、≤80 字符、不出现类名与轮次号），release 声明按 §6.1 用开发者口吻、不含轮次号。

### 不做

- 不 commit / 不 push、不上传平台、不发 GitHub Release（除非开发者另说）。
- 不改 `guide/changelog` 里 1.3.0(beta) 时代的存量差异（两树那 18 处 `【仅 1.21.1】/【同步到 1.20.1】`
  标注是当年的历史事实，重写等于篡改历史；护栏里这条 parity 属既有存量）。

## 触及文件

`CHANGELOG.md`、`store/version_changelog.md`；`promaid_src|promaid_src_neo/assets/promaid/guide/changelog{,_en}.txt`；
`rounds/R837~R841-*.md`（§验收）；`RELEASE_NOTES_v1.3.1.md`、`store/release_notes_1.3.1.md`、
`release_body.md`、`release_body_neo.md`、`PUBLISH_GUIDE.md`、`rounds/RELEASES.tsv`。

## 验证

- 两树打包：`build_promaid.py` / `build_promaid_neo.py` → `MISSING: none`；
  `patched/promaid-1.3.1-forge-1.20.1.jar` 18,928,648 B（sha256 `b212b4b4…00cd14`）、
  `patched/promaid-1.3.1-neoforge-1.21.1.jar` 18,966,546 B（`42f2e9a6…e0be4e1`）；
  两 jar 内 mixin 登记 96 个类全登记、注入签名无歧义、class 数吻合；lang json OK；桌面快照刷新。
- 四护栏（**不增**，与基线一致）：`check_comment_style.py` **2258** 处 / 929 文件（该工具"有问题就
  打印 `RESULT: FAIL`"是它的输出风格，看的是数字——未变）；`check_preset.py` FAIL **0** / WARN 2；
  `check_changelog.py` **100148** 处（= 基线；新写的文案都压到了 200 / 80 字符以内）；
  `check_workflow.py` FAIL **0** / WARN 2（原来"PUBLISH_GUIDE 版本 1.2.4 ≠ 1.3.1"那条 WARN 已随版本
  对齐消掉；余下 2 条是 1.2.4 / 更早 jar 的归档 WARN，属既有存量）。
- 专服回归（`tools/test/`，跑 `patched/` 里这一包）：`test_server.py 1201` **PASS**、
  `test_maid_load.py 1201` **PASS**、`test_server.py neoforge1211` **PASS**、
  `test_maid_load.py neoforge1211` **PASS** —— 四件全过（`test_maid_load` 那次真的 summon 了一只女仆、
  逼出了 brain-task mixin 注入）。
- 装机：`deploy_all.py` 4 个目标全部 `match=True`（NeoForge 18,966,546；Forge 18,928,648 ×3）。
- 登记：`rounds/RELEASES.tsv` 两条 1.3.1 行改填新 sha256，旧字节（含 `7e9ef616…13780b` /
  `7fcd6235…16d93d`）列入作废清单；`check_workflow.py` 的"同名不同内容"FAIL 随之清零（§5.2）。

## 验收

**已验收@2026-10-10**（开发者开闸：「开始跑部署和回归，然后写 changelog 和 release 声明」；
本机 4 实例 `match=True`；专服回归两加载器各两件全过）。

## 边界

- 本轮无代码改动（不碰两树 `.java`），只有文档/资源/台账；因此回归结论对代码与上一轮产物一致。
- 未升版本号；同号重打包按 §5.2 在 `RELEASES.tsv` 登记新字节并列出作废旧字节。

## 台账

- 清 `TECH_DEBT`/`WORKFLOW` §5 的 PUBLISH_GUIDE 版本不一致（一处 WARN 待消）；
- 引用 `BUG_DEBT` BD-06/BD-08/BD-09/BD-10（837~841 所清各债）。
