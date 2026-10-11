# 实测九百〇三 架构测绘工具 + 开发流程规范化 + ④⑤⑥ 实施蓝本

> 跑道：工具 / 文档（不改玩法逻辑）。承接 `R902`；对应开发者「随后逐步推进 4，5，6，规范化开发流程」。

## 排班
- ① 配置文案去叙事（`R902`，已完成）；② 本轮：新增只读架构测绘工具 + 补 `WORKFLOW.md` 提交规范 + `DEBT.md` §6.1 实施蓝本；③ ④⑤⑥ 因**需运行时/实机验收**，本轮只做测绘，等开发者开闸。

## 需求 / 来源
- 开发者：配置面板文案去开发叙事已完成；「随后逐步推进 4，5，6，规范化开发流程」。
- 长期目标（仓库清理 + normify 架构拆分）经核实**已于 2026-10-11 执行完毕**（见下）。

## 做了什么
- **新增 `tools/verify/check_tree_mirror.py`（只读审计，不设门槛）**：度量两树同路径 `.java` 的镜像保真度——逐字节相同 / 有差异（再分行数一致 / 行数不同），并按行数差排序列出 Top 漂移文件。首跑结果：同路径 459 个，逐字节相同 **47**、有差异 412（行数一致 160 / 行数不同 252），最大 Δ `combat/GunnerTetherManager` 127。**不误报**：两树合法的映射名引用（如 `{@code m_269323_}` vs Neo 侧描述）本就导致差异，故只报告不判定。
- **`WORKFLOW.md` §6 存量行**：把可见文案的叙事基线接到 `check_dev_voice.py`（①英文 value / ②`.comment` 正文 `实测NNN` / ③中文键与标签 / **④ R902 起新增的版本戳·旧版·引语·实证·工单**），明确「基线 0、只减不增」。
- **`WORKFLOW.md` 新增 §6.2「提交规范（commit）」**：Conventional Commits 摘要 + 五条禁止（内部轮次号 / 个人临时文件 / 部署流水账 / 依赖上下文的细节 / 微提交）+「未开发者要求不主动 commit/push」，细则指向记忆库 `commit-conventions.md`。
- **`DEBT.md` §6.1「④⑤⑥ 实施蓝本」**：写入本轮测绘出的机械执行清单（见下）。

## 长期目标核实（有据）
- 仓库历史清理：`_cleanup_backup/` 内含 `promaid-all-refs-20261011.bundle`（全 ref 备份）、`rewrite-audit-20261011.tsv`（新→旧提交映射）、`remote-origin-after-20261011.txt`、`worktree-status-before-20261011.txt` 等；记忆库 `MEMORY.md` 记「796 → 738 提交、每个 ref 的 tree 逐字节不变」。
- normify 架构拆分：`normify-promaid/` 含 `tree.json` / `modules/` / `renders/` / `api-index.json` / `sidecar.json`，生成器在 `tools/arch/`（`build_arch.py` 等）。
- 结论：**「清理历史垃圾 + 按 normify 思路拆分架构并存本地」这一长期目标已达成**，本轮不重做。

## ④⑤⑥ 蓝本要点（详见 `DEBT.md` §6.1）
- ④：`MaidMineBehavior`(2952) 与 `MaidWoodBehavior`(2921) 经测绘确认为**系统性克隆**；参数化点与可上提成员清单已列。门槛：中风险 + 需回环测试 → **未动刀**。
- ⑤：`PromaidConfigScreen`(7659) 按 Section 拆独立面板类；门槛：高风险 + 需实机 UI 验收 → **未动刀**。
- ⑥：第一步审计工具已落地；第二步共享源目录（`src_shared/` + `build.gradle` srcDir + `gen_compile*.py`）需真 gradle 构建验证 → **未动刀**。

## 验证
- `check_tree_mirror.py` 运行通过（只读报告，无 FAIL 语义）。
- `check_dev_voice.py` **FAIL 0**（含新增 ④ 叙事项 0/0）。
- 全护栏 `check_comment_style` PASS、其余 `check_*` **FAIL 0**；两树 `compile_*` **EXITCODE=0**。
- 未部署、未跑专服回归（本轮为工具/文档，不涉玩法）。

## 验收
- 状态：`未验收`（工具与文档可自读；④⑤⑥ 因需运行时/实机验证，停在测绘）。

## 边界（没动什么）
- 未改任何源码玩法逻辑；未动 `patched/`、`store/`、`_tmp_pub/`。
- ④⑤⑥ **未动刀**——理由：三者都要求运行时/实机/真构建验证，而仓库规矩是「回归只在开发者开闸（『部署』）时跑」，不在无人验收下盲改。
- 未 commit / push。

## 台账
- 配置文案去叙事完成（`R902`）；架构测绘工具 + 流程规范化完成（本轮）。
- 待办：④ 抽基类（需回环测试）、⑤ 拆面板（需实机 UI 验收）、⑥ 共享源目录（需 gradle 验证）——蓝本已就绪，等开发者开闸。
