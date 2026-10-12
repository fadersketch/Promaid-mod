# 实测九百〇八 架构测绘数据上保鲜门禁（让 normify 真正进入工作流）

> 跑道：工具 / 文档（不改玩法逻辑）。承接 `R903`（架构测绘工具）；对应开发者「修一下，让 normify 真正投入实际使用」。

## 排班
- ① 给结构数据加"只许来自真源"的门禁；② 接进打包链路（`--warn`）；③ 归位到 `WORKFLOW.md`（分工表 / 链路 / 门禁表 / DoD）+ `DEBT.md`；④ 自测门禁的失败路径（改产物 / 多出文件 / 基线下降都要能 FAIL）；⑤ 重跑生成与全护栏，验 `FAIL 0`。
- 不做：不改任何源码玩法逻辑；不打包、不部署、不跑专服回归（本轮不涉玩法）。

## 需求 / 来源
- 开发者：「修一下，让 normify 真正投入实际使用」。
- 现状（`R903` 遗留）：`tools/arch/` 的测绘工具链齐了，但**产物没有任何保鲜机制**——`normify-promaid/` 可以被手改、`ARCHITECTURE.md` 的统计数字靠手写，于是"图与代码各说各话"没人会发现。本轮把它变成**会被门禁顶出来**的东西。

## 做了什么
1. **`tools/arch/gen_arch.py` 加 `--out <dir>`**：可把结构数据生成到别处（默认仍是 `normify-promaid/`），供门禁"重新生成 → 逐字节比对"，不再只能覆盖式产出。
2. **新增 `tools/verify/check_arch.py`**（照 §11 棘轮惯例：`--warn` 可跑，`FAIL` 才 `exit 1`；另有 `--measure` 打印覆盖明细）：
   - **① 一致（FAIL）**：把真源重新生成到临时目录，与 `normify-promaid/` 逐字节比对（只抹平 revision 哈希——它是"最后一次改动源码树的提交"，随提交自然变），并反向检查产物里有没有**生成器根本不会写的文件**。
   - **② 覆盖棘轮（FAIL/WARN）**：两棵树里不被任何模块 `source` 覆盖的 `.java` 数量钉在基线上（`promaid_src_neo` 181 / `promaid_src` 0），**只减不增**；超基线会把未登记文件逐个打出来。
   - **③ 陈旧（WARN）**：产物冻结的 revision 落后于源码树最后一次提交。
   - **④ 文档漂移（WARN）**：`ARCHITECTURE.md` 的「修订 / 规模」与 `tree.json` 不一致。
3. **接进打包链路**：`tools/build/build_promaid.py` 与 `build_promaid_neo.py` 的 `--warn` 护栏表各加一行 `tools/verify/check_arch.py`。
4. **流程归位**：`WORKFLOW.md` §0 分工表新增 `ARCHITECTURE.md` + `normify-promaid/` 一行（写明产物手改无效、真源是 `arch_modules.py`）；§4 新增「结构数据保鲜（第 7 步的分支）」；§11 门禁表新增 `check_arch.py` 一行；§12 DoD 新增一条勾选项。
5. **台账**：`DEBT.md` 新增 **TD-19**（结构数据没有保鲜机制）+ §1 索引行。

## 自测（门禁必须真的能失败）
- 手改产物 + 塞入文件：`echo bogus >> normify-promaid/outline.md` 且新建 `normify-promaid/_junk.md` → `FAIL 2`（"内容不一致" + "多出文件"），随后复原。
- 覆盖基线临时下调（181 → 180）→ `FAIL`：`190 > 180`，并把未登记的 `accessory/DaYun*.java` 等逐个列出。
- 复盘中确认：`git checkout -- normify-promaid` 会把**整个产物目录**回退到提交态的旧数据（R907 前那份 112 模块的树，因为本轮重生成尚未 commit）——跑一次 `build_arch.py` 即恢复。**这正是本门禁存在的理由**：产物必须能被真源一键重建。

## 验证
- `python tools/arch/build_arch.py`：L1/L2 **0 error / 72 warning，121 模块 / 73 边**；写 144 文件；`render_arch.py` 出 `normify.html`（161,888 字符）；`finalize_arch.py` 冻结 **145 文件 / 0 stale**。
- `python tools/verify/check_arch.py`：**FAIL 0 / WARN 0** —— 144 个产物文件逐字节等于真源重生成；未覆盖 `.java` neo **181**（= 基线）/ forge **0**（= 基线）；产物 revision = 源码树最后提交；`ARCHITECTURE.md`「121 模块 / 61 API / 73 箭头 / 深度 3」与 `tree.json` 一致。
- 全护栏：`check_comment_style` PASS；`check_preset` / `check_catch_swallow` / `check_i18n_keys` / `check_dev_voice` / `check_comment_dup` / `check_reflection` 均 **FAIL 0 / WARN 0**；`check_workflow` **FAIL 0 / WARN 3**（3 条都是既有的发布登记 WARN，与本轮无关）。
- 未编译、未打包、未部署、未跑专服回归：本轮只改工具 / 流程 / 文档，不涉玩法，按仓库规矩「部署」才打包部署。

## 验收
- 状态：`未验收`（工具与文档可自读；门禁的失败路径已自测，见上）。

## 边界（没动什么）
- 未改任何源码玩法逻辑；未动 `patched/`、`store/`、`_tmp_pub/`。
- 未打包 / 未部署 / 未跑回归（等开发者说「部署」）。
- **未 commit / push**（`normify-promaid/` 的重生成目前是工作区未提交状态）。
- 临时脚本已删（`_*` 只在仓库根，用完即删）。

## 台账
- `DEBT.md` 新增 **TD-19**：结构数据没有保鲜机制 —— 已挂棘轮 `check_arch.py`（一致 / 覆盖 / 陈旧 / 文档漂移），标 `已清@实测九百〇八`；残留 181 个 neo 次要文件按基线钉住。
- 待办：两 `build_promaid*.py` 里 `check_arch.py` 仍以 `--warn` 跑（与其它护栏一致）；存量清零后可去掉 `--warn` 改为**阻断出包**。
