# 实测八百八十八 · 预防棘轮：把 #37 / #38 的三类问题钉成"只减不增"

## 需求 / 来源

开发者给出仓库 issues 链接，要求「必须想办法优化代码，尝试修复，避免同样的 37、38 中的代码问题再出现」。

**拉取实况**：本机无法联网读取线上 issue（`WebFetch` TLS 证书错、`gh` 未登录、直连 GitHub 超时），故以仓库内已有的 #37 / #38 归档为准：`ISSUE38-回应草稿.md`（#38 六条）、`TECH_DEBT.md` / `BUG_DEBT.md`（#37 / #38 各条已逐条映射成 TD-01…TD-18）。

**已清点（现状）**：#37/#38 的绝大多数条已收口并**已有护栏**——
| issue 条 | 对应债 | 现有护栏 |
|---|---|---|
| #38-1 构建不可复现 | TD-13 | `deps.lock` + `check_deps.py`（R887）、`BUILD.md` |
| #38-4 静默吞异常 | TD-14/TD-18 | `check_catch_swallow.py`（基线 0/0） |
| #38-6 混入不可控 | TD-16 | `PromaidMixinPlugin` + `verify_jar_classes.py` |
| #37-4 版本号不一致 | TD-06 | `check_workflow.py` |
| #37-3 可见文案开发过程（英文面） | TD-03 | `check_dev_voice.py`（R885） |
| #37-3 配置 key 漂移 | TD-07 | `check_i18n_keys.py`（基线 0/0） |
| 配置项 / 面板覆盖 / 预设 | TD-08/TD-11 等 | `check_preset.py`、`check_panel_coverage.py` |

**缺口（本轮补的正好是这三类）**：
1. **#37-2 配置注释照抄**（TD-02）——注释被复制出来但没改口径，靠人眼发现；**无护栏**。
2. **#37-3 可见文案开发过程（中文侧面）**（TD-03）——`check_dev_voice` 只扫了 `en_us` 的 **value**；而**中文可见字面量**（就是 `.comment("…")` 的正文与 `assets/promaid/i18n/en_us.json` 的 **key**）没扫，R885 是手工清的，同类痕迹可能再溜进来。
3. **#38-5 反射未收敛**（TD-15）——每 tick 路径已收敛（R877），但**总量无约束**：新写的探针式反射会静默堆积。

本轮 = 给这三类各钉一道**棘轮**（只减不增），让同类问题**在下一次构建就 FAIL**，而不是等下一次 review。

约定不变：不跑回归（本轮不改 jar）、不写 changelog / release 说明、版本号不动（仍 **1.3.2**）、临时脚本用完即删、**不 commit / 不 push**（WORKFLOW §12）。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 量化 | 量出三类缺口的**准确形状与存量**（重复注释 4 组、中文侧痕迹 0、反射计数 86/204/6/7 与 82/203/5/7） | 清单（本轮） | ☑ |
| 2 | 护栏-A | 新增 `tools/verify/check_comment_dup.py`（TD-02）：`.comment()` 正文归一化后分组建表，白名单 4 条共用机制，其余重复即 FAIL | 1 脚本 | ☑ |
| 3 | 护栏-B | 扩 `tools/verify/check_dev_voice.py`（TD-03）：加扫 `.comment()` 正文 + 两树 i18n / lang 的 **key**，判据 `实测` / `[四五六七八九]百` / `把\d+误当` | 1 脚本（改） | ☑ |
| 4 | 护栏-C | 新增 `tools/verify/check_reflection.py`（TD-15）：`Class.forName(` / `getMethod(` / `getDeclaredMethod(` / `getDeclaredField(` 计数棘轮，高于基线 FAIL、低于 WARN | 1 脚本 | ☑ |
| 5 | 接线 | 三道护栏以 `--warn` 接进两 `build_promaid*.py`；`WORKFLOW` §11 / `DEV_GUIDE` §6 / `BUILD` §5 护栏表 6 → 8；`TECH_DEBT` TD-02/TD-03/TD-15 状态补"已挂棘轮" | 5 文档 + 2 脚本 | ☑ |
| 6 | 验证 | 三道新/改护栏全绿；既有六护栏不退化；两树编译（确认无源码改动） | 运行输出 | ☑ |
| 7 | 留痕 | 本文 + `TECH_DEBT` 三行；`RELEASES.tsv` 不加（纯工具轮，jar 未变，同 R881/R887 例） | 两处 | ☑ |
| 8 | 验收 | 交开发者：是否接受"棘轮防复发"这一路线、反射基线是否合适 | 待开闸 | ☐ |

## 改法

### 护栏-A `check_comment_dup.py`（#37-2 / TD-02）
- 从两树全部 `.java` 剥注释后抓 `.comment("…")` 正文 → 去掉所有空白归一化 → 正文相同的分一组。
- **白名单**（TD-02 已裁定的 4 条**共用机制**，挖矿/伐木两套本就同口径）：`搭方块防掉落（潜行效果，速度不变）`、`重定位节流（tick，防边界抖动）`、`搭方块冷却（tick，垫脚下/搭路节奏）`、`废石清理检查间隔（tick）`。
- 归一化后 count>1 且**不在白名单** → FAIL（新出现一对"复制了没改口径"的注释）。基线 = 白名单内 4 组，超出即 FAIL。

### 护栏-B 扩 `check_dev_voice.py`（#37-3 / TD-03 中文侧面）
- 原有：两树 `assets/.../en_us.json` 与 `assets/maid_smart/lang/en_us.json` 的 **value**（英文口吻 + 轮次号）。
- 新增两个**中文可见面**：
  - 两树全部 `.java` 剥注释后的 `.comment("…")` 正文（进 TOML，玩家可见）；
  - 两树 `assets/promaid/i18n/en_us.json` 的 **key**（= 面板 / 手册中文可见字面量）与 `assets/maid_smart/lang/{zh_cn,en_us}.json` 的 key。
- 判据（这三条是**开发者标记**，不误伤正常中文）：`实测`、`[四五六七八九]百`（中文数字百位轮次号）、`把 *\d+ *误当`。**不用** `第N次`（`第一次`/`第二次` 是正常中文，实测有 5 处正当用法）。
- 基线仍全 0。

### 护栏-C `check_reflection.py`（#38-5 / TD-15）
- 剥注释后按树统计 `Class.forName(`、`getMethod(`、`getDeclaredMethod(`、`getDeclaredField(` 出现次数。
- 高于基线 → FAIL（**新写的探针式反射必须同时把别处收敛掉、或显式上调基线并留痕**）；低于基线 → WARN（可顺势下调，棘轮只往一个方向走）。
- 基线 = 本轮实测（见验证表）。

### 不做
- **不改源码 / 行为 / 开关 / 默认值**；本轮不重打 jar（纯工具轮）。
- **不动** TD-01（配置耦合）、TD-04（功能重复裁决）——开发者此前明确「暂时不管」。
- **不加** `第N次` 这类会误伤的判据；不给反射做"深层兼容框架重构"（那是工程骨架级改动，超出"防复发"）。

## 触及文件

**工具**：`tools/verify/check_comment_dup.py`（新）、`tools/verify/check_reflection.py`（新）、`tools/verify/check_dev_voice.py`（改）。
**接线 / 文档**：`tools/build/build_promaid.py`、`tools/build/build_promaid_neo.py`、`WORKFLOW.md` §11、`DEV_GUIDE.md` §6、`BUILD.md` §5、`TECH_DEBT.md`（TD-02/TD-03/TD-15）、`rounds/R888-*.md`（新）。

## 验证

| 项 | 结果 |
|---|---|
| `check_comment_dup` | 两树：重复组 4（全在白名单）→ **FAIL 0 / WARN 0**。**反证已实跑**：注入临时探针 `promaid_src/_tmp_probe.java`（把 `临时重复测试A` 写在第 2 处 → 重复组 5、非白名单 1）→ `FAIL 1`；删探针后复绿 |
| `check_dev_voice`（扩后） | 两树三面（英文 value + `.comment` 正文 + i18n/lang key）**全 0** → **FAIL 0 / WARN 0**。**反证已实跑**：同一探针含 `.comment("实测999：故意违反")` → `.comment` 正文面 `FAIL 1`；删探针后复绿 |
| `check_reflection` | 基线 Forge `{forName 86, getMethod 204, getDeclaredMethod 6, getDeclaredField 7}`、Neo `{82, 203, 5, 7}`（`--measure` 实测确认）→ **FAIL 0 / WARN 0**。**反证已实跑**：同一探针含 `Class.forName("x")` → Forge `87 > 86`，`FAIL 1`；删探针后复绿 |
| 既有护栏 | `check_i18n_keys` 0/0；`check_preset` 0/0；`check_workflow` 0/3；`check_catch_swallow` 0/0；`check_comment_style` 931/2260；`check_panel_coverage` 0/0 |
| 两树编译 | 本轮仅改工具/文档，源码未动 → 不重编、不重打；jar 仍为 R886 的 Forge `17,610,091 B` / Neo `17,649,277 B` |
| 部署 / 回归 | 本轮无 jar 变化，不触发（R885/R886 那两轮已部署@2026-10-11、已回归@2026-10-11） |
| commit / push | **未做**（WORKFLOW §12） |

## 验收

**未验收**（"棘轮防复发"路线是否认可、反射基线数值是否合适，待开发者定）。

## 边界

- **防复发 ≠ 已修**：#37/#38 的实质条目此前已逐条修完（本轮的验证表列出映射）；本轮只补"这三类以后再冒出来会被自动拦住"。
- **只拦新增**：棘轮对**存量**是"钉住当前值"，不新增即为通过；要真正下降仍需专门批次（如反射收敛）。
- **未动**：TD-01 / TD-04（开发者「暂时不管」）；线上 issue 因离线未读，若线上有**新**条目（非 37/38）不在本轮的映射内，需要时再单独开轮。
