# 实测八百六十四 · 删掉「幼儿女儿武器禁持」老功能（MaidWeaponGuard）

## 需求 / 来源

开发者指令（附选中的配置项 `misc<TAB>weaponGuard<TAB>幼儿女儿也能拿武器（不再删+丢地）`）：

> 这个是老功能的残留，把它删掉。

约定不变：不跑回归、不写 changelog / release 说明、版本号不动（仍 **1.3.2**）、两树逐字镜像、临时脚本用完即删。

## 判定

`misc.weaponGuard`（字段 `MISC_WEAPON_GUARD`）不是"新开关"，而是给一个**老功能**——`task/MaidWeaponGuard`
（v1.5.332 引入的「婴儿/幼年女儿手上出现剑/斧/弓/弩/三叉戟 → 从手上删除 + 原地丢一个一样的」）——在 **R859**
补的开关。开发者判定该**功能本身**是过时残留：不再要这个"删武器再丢地"的介入，整个删掉（连带它的开关）。
删掉后幼儿女儿能正常拿武器（与关掉该开关时的行为一致），不再有每 1 秒轮询。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 判定 | 确认 `weaponGuard` = R859 给 `MaidWeaponGuard` 补的开关，老功能即该介入本身；删功能连带删开关 | 本文 §判定 | ☑ |
| 2 | 实现 | 两树 **删** `com/maidsmart/task/MaidWeaponGuard.java` | 2 文件 | ☑ |
| 3 | 实现 | 两树 `ProMaidExtension`：删字段 `weaponGuardTimer` + 20-tick 轮询块 | 2 文件 | ☑ |
| 4 | 实现 | 两树 `MaidSmartConfig`：删 `MISC_WEAPON_GUARD` 声明 + 定义（misc 组） | 2 文件 | ☑ |
| 5 | 实现 | 两树 lang：删 `config.promaid.misc.weaponGuard`（zh/en 共 4 行） | 4 文件 | ☑ |
| 6 | 实现 | 两树 i18n：删日志键 `weapon guard: 幼儿女儿 {} …已移除并丢地` | 2 文件 | ☑ |
| 7 | 护栏 | `check_preset.py`：`GATES_ELSEWHERE` 删该接点；`BASELINE_FLIP` 171/175 → **170/174**；重生成 `ARCHITECTURE-SWITCHES.md` | 2 文件 | ☑ |
| 8 | 实现 | `机制详解.md`：删 tick 节流句里的「幼儿武器禁持轮询（20 tick）」+ 该功能词条 | 1 文件 | ☑ |
| 9 | 验证 | 两树编译、四护栏问题数不增/基线更新、打包自检、镜像/符号比对（旧符号应归零） | 构建输出 | ☑ |
| 10 | 留痕 | `rounds/RELEASES.tsv` 两条 1.3.2 行：换哈希 + R864 留痕 | 2 行 | ☑ |
| 11 | 清理 | 删临时脚本；提交推送（**不**部署、**不**跑回归、**不**写 changelog） | — | ☑ |
| 12 | 验收 | 交开发者实测（幼儿女儿能正常持武器、无删+丢；配置界面不再有该开关） | 待开闸 | ☐ |

## 改法

- **删**（两树各一份）：`task/MaidWeaponGuard.java` 整个文件；`ProMaidExtension` 的 `weaponGuardTimer`
  字段与那段 `if (++weaponGuardTimer >= 20) { … MaidWeaponGuard.tick(server); }`；`MaidSmartConfig` 的
  `MISC_WEAPON_GUARD` 声明与 `.translation("config.promaid.misc.weaponGuard").define("weaponGuard", true)`；
  两树 `assets/maid_smart/lang/{zh_cn,en_us}.json` 里的 `config.promaid.misc.weaponGuard`；
  两树 `assets/promaid/i18n/en_us.json` 里的日志键；`机制详解.md` 里对应的两处提及。
- **护栏**：`check_preset.py` 撤 `GATES_ELSEWHERE['MISC_WEAPON_GUARD']`；`BASELINE_FLIP` = **170**（Forge）/
  **174**（Neo）（该布尔默认开、原计入 DIY 关闭数，删掉后少 1）；开关清单重生成后该项应消失。
- 行为后果：与"关掉该开关"完全一致——幼儿女儿可正常持武器，无删除/丢地。**无新增开关**（功能没了）。
- 不改版本号、不写 changelog、不动已发布产物。

### 不做

- 不保留"删/丢"逻辑的任何形态（不改成只删不丢、不改成拦战斗）。
- 不动 R839/R859 等历史轮次记录（历史如实，不追改）。

## 触及文件

**删**：`promaid_src|promaid_src_neo/com/maidsmart/task/MaidWeaponGuard.java`。
**改**：两树 `com/maidsmart/ProMaidExtension.java`、`com/maidsmart/config/MaidSmartConfig.java`、
`assets/maid_smart/lang/{zh_cn,en_us}.json`、`assets/promaid/i18n/en_us.json`；
`tools/verify/check_preset.py`、`ARCHITECTURE-SWITCHES.md`（生成物）、`机制详解.md`。

## 验证

| 项 | 结果 |
|---|---|
| 两树编译 | `javac EXITCODE=0` |
| 打包自检 | 两树 `MISSING: none`、`lang json: OK`；Forge 17,567,246 B（`3c33fced…022b51`）、Neo 17,606,190 B（`07e9247e…3d5a68`）；两个 jar 内 `MaidWeaponGuard.class` **不存在**、字节里无 `weaponGuard` |
| 护栏 | `check_preset` FAIL 0 / WARN 2（DIY 关闭条数 **170/174**，与更新后基线一致；WARN 2 系既有 `broom.enable` 重路径）；`check_workflow` FAIL 0 / WARN 3；`check_comment_style` 927 文件 / 2262（文件数因删类 −2，问题数不变） |
| 镜像/符号 | 两树源码 `.java`/`.json` 里 `MaidWeaponGuard` / `MISC_WEAPON_GUARD` / `weaponGuard` / `weapon guard` 残留 **0**；`ARCHITECTURE-SWITCHES.md` 1389 → 1388 行（该项消失） |
| 部署 | **未部署**（开发者闸门） |
| 回归 | **未跑**（开发者闸门） |

## 版本与上传

- 版本保持 **1.3.2**；**不**部署、**不**跑回归、**不**发 release；同号重打按 WORKFLOW §5.2 留痕。

## 验收

**未验收**。

## 边界

- 删除 = 永久回原版行为（幼儿可持武器），无开关可回；要恢复只能回滚代码。
- 历史轮次记录（R839 性能轮 / R859 开关轮）里对该功能的提及**不改**，如实保留。

## 台账

- TD-11 相关：该布尔从 DIY 关闭集合移除（170/174），护栏基线同步；开关清单减 1。
