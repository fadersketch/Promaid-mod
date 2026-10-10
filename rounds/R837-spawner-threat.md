# 实测八百三十七 拆刷怪笼判定修根 + 版本升 1.3.1 + 工作流改写成可执行流程（范围：修 bug + 文档）

> 流程见 `WORKFLOW.md`；本轮的 `## 排班` 是成文后的第一个实例（硬门禁第一步）。

## 排班（开工前先填）

- **跑道**：一条轮里两条跑道都有 —— 主体是 **A 修 bug**（拆刷怪笼判定错，P1 级"功能性失效"）；
  附带 **文档/流程**（工作流改写成可执行流程）与 **版本纪律**（升 1.3.1）。
- **开工前读过**：`ARCHITECTURE-SWITCHES.md`（`spawnerTorch` 6 项 → 主题「防刷怪」）、`BUG_DEBT.md`（BD-04 未清 P1）、
  `TECH_DEBT.md`（TD-06 版本号不一致、TD-07 i18n 漂移）、`rounds/RELEASES.tsv`（1.3.0 已按冻结线登记）、
  `rounds/R836-workflow.md`。结论：这是**新债**（BD-08 拟），台账里没有；版本不一致（TD-06）本轮正好一并清掉。
- **工作队列**（逐步；每步写完成判据）：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 读玩家实例日志 + 代码，定位「她为什么不动手」 | 拿到能指向代码的那一行留痕，且能把判定链列全 | ☑ |
| 1 | 找根因（含同类点） | 根因指着证据；`catch (Throwable` 全清单逐条处置 | ☑ |
| 2 | 两树改 + 编译 | `compile_promaid.bat` / `compile_neo.bat` → `EXITCODE=0` | ☑ |
| 3 | 版本升 1.3.1（§5.5 清单走完） | 三份 toml / 两个 jar 名 / README / CHANGELOG 首行一致；护栏版本项零告警 | ☑ |
| 4 | 工作流改写成可执行流程 + 新硬规则进护栏 | `WORKFLOW.md` 有 §2.0/§2.5/§4.1/§5.5/§6.1；`check_workflow.py` 查 `## 排班` / `## 验收` / changelog 验收门 | ☑ |
| 5 | 打包 + 护栏 | `MISSING: none`；四个护栏无**新增**违规 | ☑ |
| 6 | 装机（本机 4 实例） | 4 个目标与 `patched/` 同字节 | ☑ |
| 7 | **交开发者验收** | 开发者实测后给出结论（**未验收前不写 changelog、不发版**） | ☑ 已验收 2026-10-10 |

- **谁验收 / 怎么验**：验收人 = 开发者。验法：站在会刷怪（已确认刷苦力怕那类）的笼子附近，
  看她是否走过去用镐拆；日志搜 `刷怪笼`，预期出现
  「发现刷怪笼 … → 过去拆掉防刷怪」→「拆掉了刷怪笼 …」，**不再**出现「现在不算威胁（空笼子 / 只刷牛羊猪…）」，
  或（若确实不该拆）出现**各自成句**的那一句 + 句尾「读到 entity.id = […]」。
  另验手册首页第 6 个按钮「⚑ 调试 / 预设模式」（835 那批的验收也一并落在这里）。

## 需求 / 来源

- 开发者原话两件：①「先进行升级」②「当时刷怪笼里面是刷的苦力怕，但是女仆仍然没有选择去挖」。
- 加上前面几轮的流程要求：工作流必须是**可执行流程**（怎么定位、怎么修）、验收前不写 changelog、
  发布说明按"本地↔远程 changelog 差集"以开发者口吻写、每次开发前必须定工作队伍（排班）。

## 评估

**A 跑道（P1，功能性失效）** —— 证据：

- `promaid.log`（NeoForge 实例，2026-10-10 15:36：00 / 15:36:34）：
  `[刷怪笼] 大正女仆酒狐 刷怪笼 (-60, -57, -35) 现在不算威胁（空笼子 / 只刷牛羊猪这类和平生物 / 没在出货…）→ 不拆，留着`
- 同一实例 10-09 23:20~23:21 有 16 条「拆掉了刷怪笼」→ 链路本身能跑，是**判定**把她拦住了。
- 根因三问：
  1. **复现**：笼子写着刷 `minecraft:creeper`（敌对），她站在跟前、身上有镐（排除"没镐"那条闸）仍不拆。
  2. **根因（两个，都在 `MaidSpawnerTorchBehavior` 里）**：
     ① `spawnsHostile` 只读 `SpawnData` / `SpawnPotentials` 两个**固定键**；整合包把刷怪表放在自己的键下
     （如 `custom_spawn_rules.spawn_potentials`）→ 一个键都不命中 → 判成"只刷和平生物" → 不拆；
     ② `catch (Throwable ignored) { return false; }` 把**"读不到"与"只刷和平生物"合并成同一个 `false`**
     —— `isHostileEntity` 解析不出来也返回 `false`。**未知 ≠ 和平**。
     还有一个放大器：三种互斥原因被合并成**一句**留痕，所以玩家与开发从日志上看不出是哪一种。
  3. **同类点**：全文件 grep `catch (Throwable`（"返回值即判据"的处所）逐条核对 —— 本次直接修的是
     `spawnsHostile`（→ `spawnThreat`，返回三态 + 留痕）与 `isHostileEntity`（→ `classifyEntity`，返回 `Boolean` 三态）；
     其余（`hostileNear` / `playerNear` / `stillNeedsTorch` / `canReachSpawner` / `isSpawnerBlock`）**保留原返回值**，
     理由：它们的方向是"保守"（误判为 false 只是少拆一只，不会误拆），且判定链已由三态兜住；
     `scan()` 那段"隔墙静默跳过"改为限频留痕（不再让玩家看到"附近没扫到"却其实扫到了）。
- 先查过清单/台账：`spawnerTorch` 6 项开关都在；本问题是**判定逻辑错**，不是缺开关 → 不新增开关（§3.2：改既有语义才要开关）。

## 改法

1. **判定改三态**：新增 `enum SpawnThreat { HOSTILE, BENIGN, EMPTY, UNKNOWN }`，
   `spawnThreat()` 取代 `spawnsHostile()`：**不再按固定键名读**——先读三个已知键
   （`SpawnData` / `SpawnPotentials` / `custom_spawn_rules.spawn_potentials`、`spawners`），
   再兜底扫"根下任何名字带 spawn 的键"递归找 `entity.id`（最多 5 层；`addId` 过滤掉笼子自己的
   `minecraft:mob_spawner`）。判定：有敌对 → HOSTILE；全是可解析的非敌对 → BENIGN；
   一个实体 id 都读不到且连"带 spawn 的键"都没有 → EMPTY（真空笼子）；**其余（含抛异常）→ UNKNOWN**。
2. **决策**：`spawnSkipReason()` 取代 `spawningNow()`，返回 `null`（该拆）或**一整句中文**——
   ① 旁边就有敌对生物 → 拆；② 不在激活窗口 → "现在没在出货…"；③ 看刷怪表：敌对 → 拆 /
   和平 → "只刷和平生物" / 空 → "刷怪数据是空的"；**UNKNOWN → 拆**（宁可多拆不可漏拆，回到旧口径）。
   句尾统一附 `spawnEvidence()`（最多 6 条 `entity.id`，读不到就说读不到）。
3. **不再静默**：异常路径 `noteThreatFail()` 限频（30 秒）写一行日志；"隔墙被跳过"改成限频报
   「扫到 N 个刷怪笼，但都与她之间隔着方块」。
4. **配置注释同步**（两树 `MaidSmartConfig`）：`breakOnlyWhenActive` 的注释里"读不到 = 回落①②"
   与那句合并留痕的【日志】说明，改成新口径（不再宣称"空笼子/和平生物/没在出货"合成一句）。
5. **版本升 1.3.1**（§5.5 清单 11 处），顺带修掉 `promaid_src_neo/META-INF/mods.toml` 停在 1.2.4 的旧漂移。
6. **工作流可执行化**：`WORKFLOW.md` 新增 §2.0（定位五步）/ §2.5（定位→修复完整链路，以本轮为例）/
   §2.4 反模式四条 / §4.1 验收门 / §5.5 升版本清单 / §6.1 changelog 与 release 说明纪律；
   链路扩成 11 步（含"排班"与"等验收"）；`rounds/TEMPLATE.md` 加 `## 排班`、`## 验收`；
   `check_workflow.py` 新增三项检查。
7. **顺手清理**：`promaid_src/assets/promaid/i18n/_tmp_swap.txt` 是 i18n 换表工具留下的 1 字节残渣，
   **它被打进了 Forge jar**（Neo 侧没有）——删除源文件。

## 触及文件

- 代码（两树各 1 个）：`promaid_src|promaid_src_neo/com/maidsmart/combat/MaidSpawnerTorchBehavior.java`
  （删 `spawningNow` / `isVanillaSpawner` / `spawnsHostile` / `isHostileEntity`；加 `SpawnThreat` /
  `spawnThreat` / `spawnSkipReason` / `collectSpawnIds` / `addFromList` / `collectAnyIds` / `addId` /
  `hasSpawnKey` / `spawnEvidence` / `classifyEntity` / `noteThreatFail` + 静态字段 `lastThreatFailLog`；
  `scan()` 加 `int[] occludedOut`）。
- 注释（两树各 1 个）：`config/MaidSmartConfig.java`。
- 版本（11 处）：3 份 `mods.toml` / 2 个 build 脚本 / `deploy_all.py`（头注释 + JOBS）/
  `sync_desktop_snapshot.py` / 2 个 test 脚本 / `README.md`（CHANGELOG 首行本就 v1.3.1）。
- 流程：`WORKFLOW.md`、`rounds/TEMPLATE.md`、`tools/verify/check_workflow.py`。
- 清理：删 `promaid_src/assets/promaid/i18n/_tmp_swap.txt`。
- **新配置项 0 / 新 i18n key 0 / 新 mixin 0**（判定修根不需要新开关；新日志串是诊断输出，
  与既有的「拆掉了刷怪笼」等一样不入 i18n 表——见边界）。

## 验证

- 两树编译：`compile_promaid.bat` / `compile_neo.bat` → **EXITCODE=0**（Forge 88 警告，与改前同量级）。
- 编译期实证：`javap -classpath client-1.20.1-…-srg.jar net.minecraft.nbt.CompoundTag` 确认
  `getAllKeys()` 的 SRG 名是 **`m_128431_`**（`m_128403_(String)` 是 `contains`）——第一版按 `m_128403_` 写，
  javac 报 3 个错，改对后 0 错（这一步就是 §2.0 第 5 条的实际用法）。
- **反证（本轮必做，且判据本身要能失败）**：数据形状断言 ——
  ① 只有和平生物的笼子（`SpawnData.entity.id=minecraft:cow`）→ 必须 BENIGN（不拆）；
  ② 空笼子（无任何 spawn 键）→ 必须 EMPTY（不拆）；
  ③ `custom_spawn_rules.spawn_potentials[0].data.entity.id=minecraft:creeper` → 必须 HOSTILE（拆），
     并且**旧代码（`spawnsHostile`）在这条输入上返回 false**——这就是"去掉修复能复现"的那一半。
  ④ 隔墙扫描：造一个"看得见 0 只、被挡 1 只"的场景 → 日志必须出现「扫到 1 个…隔着方块」而不是「附近没扫到刷怪笼」。
- 护栏：`check_workflow.py` FAIL 0（新增三项检查：`## 排班`、`## 验收`、changelog 验收门）；
  `check_preset.py` / `check_changelog.py` / `check_comment_style.py` 计数**不增**。
- 打包：`MISSING: none`；`patched/` 新增 `promaid-1.3.1-forge-1.20.1.jar` / `…-neoforge-1.21.1.jar`。
- **未做的验证**：**按开发者要求，本轮不跑专服/实机回归**——装机后停下等验收（§4.1）。

## 验收

- 状态：**已验收@2026-10-10**（开发者开闸「开始跑部署和回归，然后写 changelog 和 release 声明」
  —— 即认定 837 起这一批可用于发版；本轮专服回归两加载器各两件全过、本机 4 实例 `match=True`）。
- 验收看过的日志关键词：`刷怪笼` / `拆掉了刷怪笼` / `它的刷怪数据` / `读到 entity.id` / `扫到`。

## 边界（没动什么）

- **不动** `store/`（除 changelog 规则本身）、`_tmp_pub/`、已发布产物（1.2.4 / 1.3.0 的 jar 与登记原样保留）。
- **不动** 拆笼的其它闸：隔墙不拆（恒生效）、在家/工作区圈内不碰、`breakRequirePickaxe`、
  战斗让位、自保一票否决、坐/睡/乘客 —— 本轮只改"读刷怪表"这条链与留痕文案。
- **不新增开关**：本问题不是"缺开关"，是判定错（§3.2）。`breakOnlyWhenActive=false` 仍是"见到就拆"的退路。
- 新日志串**不新增 i18n key**：与既有诊断串（`拆掉了刷怪笼` 等从未入表）一致；配置注释里的口径已同步更新。
- 没有 commit / push；临时脚本（`tools/_*.py` / `_*.txt`）本轮跑完即删。

## 台账

- 新增 **BD-08（P1，已清@837）**：拆刷怪笼按固定键名读刷怪表 + 把"读不到"当"和平"→ 刷敌对生物的笼子永不拆。
  证据：2026-10-10 15:36 的 `现在不算威胁…` 留痕。修法：三态判定 + 递归捞 id + 读不出来按在出货处理。
- 清掉 **TD-06（版本号四处不一致）**：三份 `mods.toml`、两个 jar 名、`README.md`、CHANGELOG 首行已统一到 1.3.1
  （`PUBLISH_GUIDE` 的 1.2.4 是"平台上有什么"的记录，按 §5.5 属"只在真发布时改"，不算不一致）。
- 新增 **TD-09（低）**：`MaidSmartConfig` 里那段配置注释**本身有删字留下的洞**
  （如「…插火把压不住：\n *  。默认开 = …」是早期去引号批次的遗留），本轮只改必要口径，没做整段重写。
- 配额：本轮属修 bug（§8 只对 FEATURE 轮要求"清一条 P1 或记一条新债"），且已清 TD-06 / 新增 BD-08。
