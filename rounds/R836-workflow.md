# 实测八百三十六 开发工作流成文 + 三道新护栏（仓库级）

## 排班（**追记@实测八百三十七**）
- 本条写在"开工排班"成文之前，按 `check_workflow.py` 的 `ACCEPT_BASELINE=836` 属历史基线；
  这里补记当时的队列，免得护栏报警时看不出它做过什么：
  ① 读 issue #37 全文 + 盘点现有流程文档与版本号现状 → ② 写 `WORKFLOW.md`（两条跑道 + 共用链路 + 门禁）→
  ③ 建 `BUG_DEBT.md` / `TECH_DEBT.md` → ④ 建 `rounds/`（模板 + `RELEASES.tsv`）→
  ⑤ 写 `check_workflow.py` + `gen_switch_inventory.py` 并用反证验一遍 → ⑥ 接进两个 build 脚本 + 联动 `DEV_GUIDE` → ⑦ 清理临时脚本。

## 验收（**追记@实测八百三十七**）
- 状态：**未验收**。当时的定位是"无运行时改动、player 面不动"，所以没走"装机等验收"这一步；
  但它的产物（护栏与流程）**本轮已被实际使用**（八百三十七 就是按新流程走的第一轮）。
- 未验收期间守则（本轮起生效）：不进 `CHANGELOG.md` 条目部分、不发版。

## 需求 / 来源
- 来源：issue #37 全文（7 条批评）+ 用户要求：**开发要有一套完整、合规的工作流，分「修 bug」与「开发新功能」
  两条跑道，且必须避免 #37 提到的问题**。
- 不是玩家能感知的行为改动 → 走链路但**只落 dev 面**（`WORKFLOW.md` §6 末段），player 面 6 处不动。

## 评估
- **跑道**：两者都不是（流程/文档/护栏）。判据：本条不改变任何运行时行为。
- 先把 #37 逐条映射成"可强制的门禁"，再决定哪些能机器化：
  1 定制化 → §3.1② 语义（改既有语义必须给开关 + 回退口径）；
  2 新功能压旧 bug / 注释照抄 → §8 配额 + §2.3③ 同类点排查；
  3 开发过程写进 mod / 配置耦合 → §6 文案纪律 + §3.1③ 配置清单；
  4 同号覆盖 → §5 版本纪律 + `rounds/RELEASES.tsv` + 守卫；
  5 功能冗余 → §3.1① 重复性评估；
  6 周期检测过多 → §7 性能纪律（频率/范围/开销/开关四个数）；
  7 扫帚右击闪退 → §2 P0 口径（已在实测八百三十四修完）。
- 先查台账：`BD-01~03`、`BD-07`… 已清/已记；本轮**新增** `TD-06`（版本号四处不一致，守卫暴露）、
  `TD-07`（i18n 漂移，实测八百三十五发现）、`TD-08`（`broom.enable` 撞键，`check_preset` WARN）。

## 改法
- 新增 `WORKFLOW.md`：两条跑道 + 共用 10 步链路（含命令与硬门槛）+ 版本纪律 + 文案纪律 + 性能纪律 +
  债务配额 + 轮次记录 + **#37 逐条对照表** + 护栏映射 + Definition of Done。
- 新增台账 `BUG_DEBT.md` / `TECH_DEBT.md`（从 #37 与实测证据起头，每条写证据与建议做法）。
- 新增 `rounds/`：`TEMPLATE.md`（记录模板）、`R835-preset-mode.md`（立规前那轮按模板追记，
  含"配额不满足"的如实记录）、本文件、`RELEASES.tsv`（发布哈希登记，含 1.2.4 的历史差异说明）。
- 新增 `tools/verify/gen_switch_inventory.py` → 生成 `ARCHITECTURE-SWITCHES.md`（按节 + **按主题**聚合，
  主题索引直接回答 #37 第 3 条"搭路到底有几个开关"：实测 16 项）；末尾统计可见文案里的 `实测NNN` 存量
  （Forge 123 / Neo 129）作为 TD-03 的进度表。
- 新增 `tools/verify/check_workflow.py`：版本纪律（FAIL：两树不同号）+ **同名不同内容**（FAIL，= #37 第 4 条）
  + 发布件不在归档 / 四处版本一致性 / 当前版本未登记 / 轮次记录缺失或缺点 / 台账行格式 / **§8 配额**
  （功能轮遇未清 P0/P1 → WARN）+ 配置清单过期（WARN）。
- 两个 `build_promaid*.py` 的护栏调用列表加入 `check_workflow.py`（`--warn`，与另两个一致）。

## 触及文件
- 新增：`WORKFLOW.md`、`BUG_DEBT.md`、`TECH_DEBT.md`、`ARCHITECTURE-SWITCHES.md`（生成物）、
  `rounds/{TEMPLATE.md,R835-preset-mode.md,R836-workflow.md,RELEASES.tsv}`、
  `tools/verify/{check_workflow.py,gen_switch_inventory.py}`。
- 改动：`DEV_GUIDE.md`（§3 指向本流程、§6 护栏表、§7 工具地图、§8 清单、§9/§10 记账）、
  `CHANGELOG.md` + `store/version_changelog.md`（dev 面本轮条目）、两个 `build_promaid*.py`。
- 无新配置项、无新 i18n key、player 面不动。

## 验证
- `check_workflow.py` 终态 **FAIL 0 / WARN 4**；四条 WARN 都是可执行待办而不是噪声：
  ① CHANGELOG 首行 v1.3.1 ≠ `mods.toml` 1.3.0（发布前对齐，`TD-06`）；
  ② `PUBLISH_GUIDE` 记的 1.2.4 与 `mods.toml` 不一致（同上）；
  ③ 发布件 `promaid-1.2.4.jar` 不在归档里（1.2.5 起改名，无法复核）；
  ④ 1.1.0~1.2.5 共 13 个旧 jar 未登记（归档用）。
- `gen_switch_inventory.py` 解析出的规模与 `check_preset.py` 一致（Forge 577 / Neo 581 项，24 节），
  主题索引与 `--grep` 两条路都可用；生成物与重新生成的结果一致（不过期）。
- 另两个护栏计数无新增：`check_changelog` 100,148；`check_comment_style` 927 文件·2,260 处。
- 两个 build 脚本照常出包（护栏以 `--warn` 接入，不阻断）。
- **反证（做了，并且它先抓出了我自己的错）**：往 `RELEASES.tsv` 插一条 `published` 行（文件名用
  `patched/` 里真实存在的 jar、哈希故意写错）→ `check_workflow` 退出码 1 且报「同名不同内容」→ 跑完还原。
  **第一版反证没能触发 FAIL**：当时我是按 (版本, 加载器) 去找文件比对，于是"改名后的归档文件"也会参与
  比对、又被一条 archived 行豁免掉——等于把真正的危险（**同名文件、内容不同**）糊掉了。已改为**只对同名
  文件比对哈希**：同名不符 = FAIL；发布件不在归档 = WARN。同时把 1.2.4(neoforge) 那条真实存在的历史事故
  如实登记为 `published-overwritten`（同名文件确实被后来的重打覆盖过），新发布禁用该状态。
- **未做**：未部署、未跑专服回归（等用户说"部署"）。

## 边界（没动什么）
- **没有升版本号**：`mods.toml` / jar 文件名仍是 1.3.0（改动它们等于改已交付产物的命名与平台版本，
  按 `WORKFLOW.md` §5 应由发布动作决定）——守卫会一直告警直到发布前对齐（`TD-06`）。
- **没有清行为债**：`BD-04`（挖矿/伐木专项审计）、`BD-05`（蹲下迎战口径）、`BD-06`（周期检测开销）、
  `TD-01`（配置耦合）等待后续轮次。
- 不动 issue #37 的其它点（功能冗余裁决等）；不动已发布产物 / `store/`（除 changelog）/ `_tmp_pub/`；不 commit / push。

## 台账
- 新增：`TD-06`、`TD-07`、`TD-08`（均为记录，未清）；`RELEASES.tsv` 记下 1.2.4(neoforge) 的
  `published-overwritten` 历史事故（同名文件被覆盖，正是 #37 第 4 条）。
- 配额：本轮不是功能轮，不受 §8 配额约束；立规后第一个受约束的轮次从下一次开始。
