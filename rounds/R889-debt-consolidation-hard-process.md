# 实测八百八十九 债务台账合并与硬性开发流程成文（仓库级 / 无源码改动）

> 跑道：只改文档 / 护栏（不动 `promaid_src*` 源码）。起因：开发者要求「把三份债务文档合并成一份，
> 按模块 + 风险 + 收益重排」，并「结合现有开发文件成立一套硬性流程，且必须植入上下文记忆」。

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）
- **跑道**：只改文档 / 护栏（无 Java 源码改动、无新配置项、无 i18n key 变化），故不走 A/B 的行为门禁，只落 dev 面。
- **开工前读过**：`WORKFLOW.md` §0–§4 / §8 / §12、`DEV_GUIDE.md`、三份债务文档（`BUG_DEBT.md` / `TECH_DEBT.md` /
  `ARCHITECTURE-DEBT.md`）、`tools/verify/check_workflow.py`、`tools/arch/debt_report.py`、线上 issue #1–#39
  （`curl -k api.github.com/.../issues`）。结论：三份台账职责重叠；现行 `WORKFLOW.md` 已是流程骨架，缺一份
  「不可越线」红线清单与覆盖全部 issue 的教训库；记忆库里只有提交规范，没有开发流程。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 抓线上 issue #1–#39，归纳事故主题 | 得到"再犯即事故"的硬要求清单 | ✅ |
| 1 | 合并三份债务文档为 `DEBT.md`（按模块 + 风险 + 收益） | 行内容逐字保留、可被 `check_workflow` 校验 | ✅ |
| 2 | 全量更新引用（护栏 / 生成器 / 部署 / 文档） | 非 `rounds/` 引用零残留旧名 | ✅ |
| 3 | `WORKFLOW.md` 增「不可越线」红线 + `§10.1 issue 教训库` | 红线 10 条、教训表覆盖 #1–#39 | ✅ |
| 4 | 把硬性流程植入上下文记忆库 | `MEMORY.md` 首节 + `dev-process.md` | ✅ |
| 5 | 跑护栏 / 校验引用 / 提交推送 | `check_workflow` FAIL 0；无源码改动故不必编译 | ✅ |

- **谁验收 / 怎么验**：验收人 = 开发者；验法 = 打开 `DEBT.md` 看合并与索引是否符合预期；确认流程与记忆条款。

## 需求 / 来源
- 开发者指令：① 「把整个项目的脉络拆分、拆掉无用的屎山、架构拆分的成果存本地」；② 「把三份债务文档合并成一份，
  按模块 + 风险 + 收益重排」；③ 「结合现有开发文件成立一套硬性流程，注意规避 issue，必须植入上下文记忆、不因上下文变长而忽略」。

## 评估
- **跑道**：文档 / 护栏轮（不满足 A 的"行为是错的"、也不满足 B 的"新增能力"，故只落 dev 面）。
- 先查过 `ARCHITECTURE-SWITCHES.md`（无等价"台账"项）与三份台账（本轮处理的是台账结构本身）。
- issue 全量抓取结论：#37 / #38 是"开发方式"总批评（已映射 TD-01..TD-18）；其余 36 条是玩家实测事故，
  归纳为若干主题（专服安全 / 物品守恒 / 版本号 / 改语义 / 性能 / 兼容 / 构建 / 修了未发布…）。

## 改法
- **债务台账合并**：新建 `DEBT.md`（唯一台账）= §0 按模块索引（模块 / 债 / 风险 / 收益）+ §1 行为债（BD-*，
  按模块分组）+ §2 结构债（TD-*，按模块分组）+ §3 屎山机器快照（原 `ARCHITECTURE-DEBT.md` 正文，标题降一级）
  + §4 已确认不修。行内容**逐字搬运**（用脚本按 ID 移动，避免手抄错）。删除 `BUG_DEBT.md` / `TECH_DEBT.md` / `ARCHITECTURE-DEBT.md`。
- **引用全量改指**：`check_workflow.py`（台账读取 + §8 配额改为读 `DEBT.md` 的 BD-*/TD-* 行）、
  `gen_switch_inventory.py`（生成文本里的 TD-08 出处）、`sync_desktop_snapshot.py`（桌面快照 DOCS 列表）、
  `WORKFLOW.md` §1/§8/§11、`DEV_GUIDE.md` §1.1/§2.4/§7、`ARCHITECTURE.md` §6，以及 4 个护栏脚本注释。
  `rounds/*` 为历史记录，**不改**（保留当时口径）。
- **硬性流程成文**：`WORKFLOW.md` 顶部新增 **★ 不可越线（硬红线 · 每次开发必读）** 10 条；§10 后新增
  **§10.1 issue 教训库（#1–#39）** 主题表。
- **记忆植入**：记忆库 `MEMORY.md` 首节改为 **「〇、硬性开发流程（HARD PROCESS）·最高优先级」**
  （开工前必读顺序 + 10 条红线 + 11 步链路）；新增 `dev-process.md`（可携带的完整摘要 + issue 教训 + DoD + 自检）。

## 触及文件
- 新增：`DEBT.md`、`rounds/R889-debt-consolidation-hard-process.md`。
- 删除：`BUG_DEBT.md`、`TECH_DEBT.md`、`ARCHITECTURE-DEBT.md`。
- 修改：`WORKFLOW.md`、`DEV_GUIDE.md`、`ARCHITECTURE.md`、`ARCHITECTURE-SWITCHES.md`（重生成）、
  `tools/verify/{check_workflow,check_catch_swallow,check_comment_dup,check_i18n_keys,check_reflection,gen_switch_inventory}.py`、
  `tools/deploy/sync_desktop_snapshot.py`。
- 记忆库（仓库外）：`MEMORY.md`、`dev-process.md`。
- 无 Java / 无 i18n key / 无新配置项 / 无 mixin 变化。

## 验证
- `python tools/verify/check_workflow.py` → **FAIL 0**（台账 `DEBT.md：合规行 31 / 不合规 0`；3 条 WARN 为既有的版本/归档提醒）。
- 引用核查：`git grep -n -e ARCHITECTURE-DEBT -e BUG_DEBT -e TECH_DEBT -- . ":(exclude)rounds"` → 仅剩
  「已并入」的说明性提及（`WORKFLOW.md` §0/§8 与 `check_workflow.py` 的提示串）；生成物 `ARCHITECTURE-SWITCHES.md` 已重生成。
- **无源码改动 → 不需要编译**（javac 不变）；未部署、未跑专服回归（不涉及运行时代码）。

## 验收
- 状态：`未验收`（等开发者确认合并结构、红线与记忆条款）。

## 边界（没动什么）
- 不动任何 `promaid_src` / `promaid_src_neo` 源码、配置项、i18n、版本号（仍 1.3.2）。
- 不改 `rounds/*` 历史记录（旧文档名作为当时的称呼保留）。
- 未清债条目内容一律照搬，不借机改判级别/状态。

## 台账
- 本轮是"台账结构"轮：不新增业务债；`DEBT.md` 条目内容逐字沿用原三份（BD-01..13 / TD-01..18）。
- 原 `BUG_DEBT.md` §「已确认不修」并入 `DEBT.md` §4。
