# 实测九百〇九 进度数字刷新（DEBT.md 快照 / ARCHITECTURE.md / WORKFLOW.md 门禁表）

> 跑道：文档（**不改任何源码**）。承接 `R908`；对应开发者「修复，使进度最新」。

## 排班
- ① 量出全部进度数字的当前值（树规模 / 超大文件排行 / marker 密度 / 两树镜像 / 死代码 / 配置项）；② 刷新 `DEBT.md` §3 机器快照（§0 / §1 / §2 / §3 / §4.1 / §4.2 / §4.3 / §5 / §6.1），并给快照加上「当前 = 哪一轮」的年份戳；③ 刷新 `ARCHITECTURE.md` §6 三条与两树文件数；④ 刷新 `WORKFLOW.md` §11 门禁表的「当前」列，补上漏掉的 3 个护栏行；⑤ 修根因：`tools/arch/debt_report.py` 改读工作区；⑥ 重跑脚本 + 全护栏，确认文档数字 = 脚本重算值。

## 需求 / 来源
- 开发者：「修复，使进度最新」。上一轮报告里点出的既有漂移是入口：`WORKFLOW.md` §11 的 `check_comment_style` 行还写着「931 文件 · 2,260 处（存量）」，而实跑是「929 文件，0 处问题 / PASS」。顺着查到快照类文档同样大面积过期。

## 做了什么（旧 → 新，全部实测九百〇九）

| 条目 | 旧 | 新 |
|---|---|---|
| `PromaidConfigScreen.java` | 6424 行（ARCHITECTURE 也是 6424） | **7661** 行；`render()` 543 → **645** 行、`clickCookGrid()` 468 → **27** 行 |
| `MaidMountCompat.java` | 4746 / 3657（两处不同） | **6509** 行 |
| `SelfPreservationBehavior.java` | 4696 / 3789（两处不同） | **4804** 行（`实测NNN` 178 处） |
| `MaidSmartConfig.java` | 3172 行 / 524 个设置项 | **3546** 行 / **601** 个配置项（`check_preset.py` 口径） |
| §1 超大文件排行（15 行） | 6424…1748 | 整表按新排行重写：7661 / 6509 / 4804 / 3546 / 2997 / 2863 / 2475 / 2329 / 2300 / 2257 / 2252 / 2081 / 1970 / 1870 / 1760 |
| §1 合计 | 「约 45,000 行」 | Top15 **47,674 行**；>1200 行共 **21** 个 = 55,602 行（Neo 树 466 个 `.java` / 156,789 行） |
| §2 补丁坟场（12 行） | MaidSmartConfig 814 … MaidBroomDrive 130 | 整表重算：759 / 552 / 546 / 308 / 300 / 264 / 251 / 226 / 226 / 211 / 188 / 182 |
| §3 死代码 | `PlayerWaterLog` / `CompressionBoxCheck`(702) / `MaidStrollCheck`(337)「可移出」 | **已做@实测九百〇一（阶段③）：三者在两树均已不存在**；`BuiltinHouses` 1385 → 1393 |
| §3「未被别处引用」 | 30 个（26 Mixin / 4 非 Mixin） | **36** 个（31 Mixin / **5** 非 Mixin：FarmlandGuard 33 行、PetImmunityGuard 203 行、CreativeTabHandler 52 行、MaidFreeFlightKeysClient 91 行、PromaidMixinPlugin）；`MaidGoetyKeysClient` 已被引用 → 移出名单 |
| §4.1 两树镜像 | 390 同路径 / 224 行数不一致 / Δ 最大 174 | **461 / 253 / Δ 最大 127**，Δ Top5 换新 |
| §4.2 重复逻辑 | 挖矿+伐木 5796；Gunner+Ride 4216；共享 import 21 行；Packets 919/735 | **4629**（+ 基类 685）；**4623**；**23** 行；**984/818** |
| §4.3 配置迁移残渣 | 22 个 `*_MIGRATED` | **10** 个（阶段③ 已清掉一批） |
| §5 隐患 | `ProMaidExtension` 779 行；`BombPose` 11 个引用；`BlueprintLib` 73 个一行转发 | **1088** 行；**19** 个别的文件引用；**80** 个一行转发（占 94 个方法） |
| §6.1 ④⑤⑥ 蓝本 | Mine(2952) / Wood(2921) / ConfigScreen(7659)；⑥ 镜像复测 460/47/413 | **2329 / 2300 / 7661**；**461 / 47 / 414**（行数一致 161、不同 253） |
| `ARCHITECTURE.md` §6 | `promaid_src` 781 文件 / neo 785，各含 462 / 465 个 `.java` | **782 / 786 文件，各含 463 / 466 个 `.java`**；三条最要紧的数字同上 |
| `WORKFLOW.md` §11 | `check_preset` FAIL 0/WARN 2；`check_comment_style`「931 文件 · 2,260 处」；`check_workflow`「见运行输出」 | FAIL 0/**WARN 0**（配置项 597/601、DIY 关闭 175/179）；**PASS（929 文件 · 0 处）**；**FAIL 0 / WARN 3**（均为发布登记/历史归档） |

**补上漏掉的门禁行**：`check_panel_coverage.py`（FAIL 0/WARN 0，Forge 609/531/68、Neo 615/540/65）、`check_tree_mirror.py`（只读审计：461/47/414）、`tools/build/check_deps.py`（FAIL 0/WARN 0，Forge 40 / Neo 57 一致），并注明这三者未接进出包链路、按需手动跑。

**根因修复（防再烂）**：`tools/arch/debt_report.py` 原先用 `git ls-files` 取文件集——本仓库成果长期停在未提交状态，于是**未跟踪的新源码在报告里等于不存在**（实测：改前报 `total classes: 465` / `mirrored paths: 460`，改后 466 / 461）。现改为 `os.walk` 读工作区，并在证据首行打出 `snapshot: promaid_src* revision …`，使「重跑脚本即可刷新」这句话成立、也让读者一眼看出快照的年份。

## 验证
- `python tools/arch/debt_report.py`：`snapshot: revision 8fbb6334b1c5`、`total classes: 466`、`mirrored paths: 461`、`with differing line counts: 253`、`7661 PromaidConfigScreen.java` —— 与写进文档的数字逐个一致。
- 全护栏：`check_arch` **FAIL 0 / WARN 0**；`check_workflow` **FAIL 0 / WARN 3**（与改前同 3 条，未新增；`DEBT.md 合规行 32 / 不合规 0`）；`check_comment_style` **PASS**；`check_preset` / `check_panel_coverage` / `check_catch_swallow` / `check_i18n_keys` / `check_dev_voice` / `check_comment_dup` / `check_reflection` 均 **FAIL 0 / WARN 0**；`check_tree_mirror` 报告完成；`check_deps` **FAIL 0 / WARN 0**。
- 未改任何源码、未编译、未打包、未部署、未跑回归（本轮纯文档 + 一个只读脚本的口径修正）。

## 验收
- 状态：`未验收`（数字可复算：跑 `tools/arch/debt_report.py`、`check_tree_mirror.py`、各 `check_*.py` 即可逐项核对）。

## 边界（没动什么）
- 未改 `promaid_src*` 任何一行源码；未动 `patched/`、`store/`、`_tmp_pub/`；未 commit / push。
- 只刷新**可实测的数字**；纯定性判断（如「5 组列表+条目对」「~25 个包类」「各兼容层各自重写反射兜底」）保持原文。
- 历史留痕行（`@实测NNN`、阶段①「GuideScreen 541→532」这类当时成果）按规矩不改写。
- 有一处旧说法**删掉而非改数**：§5「`SelfPreservationBehavior` 的 … `COMBAT_PLACED`」——该标识符现已找不到，行内改为只列已核实的字段（`LAST_ATTACKERS`/`SUFFOCATE_DAMAGE` 在 202/215 行）。

## 台账
- `DEBT.md` §3 快照标注「当前 = 实测九百〇九（revision `8fbb6334`）」；TD-19 维持「已清」（那是 `R908` 的门禁成果）。
- 无新增债；`R908`（门禁）与本轮（数字刷新）都等开发者验收。
