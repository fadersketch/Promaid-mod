# 实测八百三十八 刷怪笼类型判据修根（getBaseClass 恒为 Entity）+ 空笼子不拆 + 开关关掉要立刻停 + DIY 残留收口

> 流程见 `WORKFLOW.md`；本轮是「修 bug」跑道（P1），外加**版本纪律**（版本号按开发者声明**保持 1.3.1**）与
> **DIY 语义收口**。按开发者要求：**本轮不写 changelog、不写功能介绍文案、不跑回归**。

## 排班（开工前先填）

- **跑道**：A 修 bug（主体）+ 少量"关掉开关立刻生效"的语义修正；无新功能、无新开关、无新 i18n key。
- **开工前读过**：`WORKFLOW.md` §0~§4、`ARCHITECTURE-SWITCHES.md` 的 `spawnerTorch` 一节（6 项开关）、
  `BUG_DEBT.md`（BD-08 已清@837）、`TECH_DEBT.md`、`rounds/RELEASES.tsv`（1.3.1 已 `published`）、
  `rounds/R837-spawner-threat.md`（上一轮）。
- **工作队列**（逐步；每步写完成判据）：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 读开发者实例的 `promaid.log`（本机 NeoForge 1.21.1，1.3.1 那次实测） | 拿到能指着代码的证据行；把判定链收敛到可数的几条 | ☑ |
| 1 | 外部 API 实证（§2.0 第 5 条） | `javap` 打穿两条树的 `EntityType`：`getBaseClass()` 真的返回什么 | ☑ |
| 2 | 定口径（三种原因各自成句、空笼子不拆、开关关掉立刻停） | 一句话口径写进本记录「评估」 | ☑ |
| 3 | 两树改代码 + 镜像 | 纯 Java 逐字节一致；碰 MC API 按 SRG↔Mojang 表 | ☐ |
| 4 | 编译两树 | `compile_promaid.bat` / `compile_neo.bat` → `EXITCODE=0` | ☐ |
| 5 | 护栏（`check_preset.py` 加"干预型 mixin 必须有闸"） | FAIL 0；残留清单可打印 | ☐ |
| 6 | 版本：**保持 1.3.1 不改**（开发者声明；中途升过 1.3.2，已撤回） | 三份 toml / 两个 jar 名 / README / CHANGELOG 首行都还是 1.3.1；重打包血债在 `RELEASES.tsv` 留痕 | ☑ |
| 7 | 打包 + 装机（本机 4 实例） | 4 个目标与 `patched/` 同字节 | ☐ |
| 8 | **交开发者验收** | 开发者实测后给结论（**未验收前不写 changelog、不发版、不跑回归**） | ☑ 已验收 2026-10-10 |

- **谁验收 / 怎么验**：验收人 = 开发者。验法：① 站在刷苦力怕的笼子旁，日志应出现
  「发现刷怪笼 … → 过去拆掉防刷怪」→「拆掉了刷怪笼 …」，**不再**出现「它的刷怪数据里只有和平生物」；
  ② 找一只**空的**（没配刷怪数据 / 表是空的）笼子站在跟前，日志应出现「它的刷怪数据是空的…→ 不拆」，
  她**不动手**；③ 切 DIY 之后再点「默认」，日志/行为应立刻跟着变（开关关掉时在跑的那一半也停）。

## 需求 / 来源

- 开发者原话三件：
  1. 「打开 DIY 模式之后还是会有一部分行为残留。比如挖掘刷怪笼。我想要的效果是除了这个游戏里面新添的
     物品和模式可以正常生效以外，打开 DIY 之后，女仆的所有行为都跟原版一模一样。」
  2. 「现在女仆会把空的刷怪笼也挖掉。这明显不对吧。」
  3. 「有关功能介绍文案方面的修改，除非开发者允许，否则把功能制作好、或者修好 bug 后都先不写文案。
     写文案的时候不写历史过程，只写这个功能目前的状况。」
  4. （本轮中途追加）「版本号不要乱更新。除非开发者声明，否则不要乱改版本名。」

## 评估

**证据（开发者实例日志，`D:\.minecraft\versions\1.21.1-NeoForge_21.1.250\logs\promaid.log`）**：

```
[2026-10-10 16:07:38] [刷怪笼] 大正女仆酒狐 刷怪笼 (-60, -57, -35) 它的刷怪数据里只有和平生物
        → 不拆，留着（你可能是要刷动物 / 收走它）（读到 entity.id = minecraft:creeper）
```

同一条留痕**自相矛盾**：它说"只有和平生物"，句尾却读到 `minecraft:creeper`（敌对）。
11 秒后（16:07:49）同一只笼子又被拆了——那是走了"旁边 12 格内出现敌对生物"的**直接证据**那条路，
不是刷怪表判出来的。也就是说：**刷新表的分类判据恒为"和平"**。

**根因（javap 实证，两条树都核过）**：

- `MaidSpawnerTorchBehavior.classifyEntity` 用
  `Monster.class.isAssignableFrom(type.getBaseClass())` 判敌对；
- 而 **`getBaseClass()` 在两条树的实际运行时都恒等于 `Entity.class`**：
  - 1.20.1-47.4.23（SRG）：`m_142225_()` 的字节码只有 `ldc class Entity; areturn`；
  - 1.21.1 NeoForge：`getBaseClass()` 同样是 `ldc class Entity; areturn`。
- 于是 `Monster.class.isAssignableFrom(Entity.class)` **恒为 false** → 凡是从 NBT 里读得出注册名的笼子
  一律判成"只有和平生物"（牛羊猪、苦力怕、僵尸…全都一样）→ 只有靠"旁边正好有敌对生物"或
  "读不出 id（UNKNOWN→拆）"才会动手。

**第二个根因（空笼子被拆）**：`spawnThreat` 里"读到 0 个实体 id"时按
`hasSpawnKey(tag) ? UNKNOWN : EMPTY` 分叉——**UNKNOWN 的处置是"拆"**。所以"笼子有刷怪键、但表是空的"
（`SpawnData: {}` / `SpawnPotentials: []` 这类"空壳笼子"）会被 UNKNOWN 一路判成"该拆"。
开发者看到的正是这个：空的也被挖掉。

**第三个根因（开关关掉还有残留）**：`MaidSpawnerTorchBehavior` 的总开关只在 `canUse`（`m_6114_`）里问过；
本文件自己的注释（javap 实证）写着：**Brain 对"正在跑"的行为每 tick 调的是 `canStillUse`（`m_6737_`），
false 就直奔 `doStop()`、连 `tick()` 都不调**。`m_6737_` 只判了"自保接管"，**没有问总开关**——
于是开关（含 DIY 一键关）翻转的瞬间，**已经在跑的那一半会继续跑完**（拆笼最多 20 秒）。
这就是"打开 DIY 之后还有一部分行为残留"这一类症状的形状：**开关只拦起手、不拦在跑的**。

**同类点（grep 清单）**：
- 全树 grep `getBaseClass` / `m_142225_`：**只有 `MaidSpawnerTorchBehavior.classifyEntity` 一处**用它判类型
  （其余用 `level.getEntitiesOfClass(Monster.class, …)` 的类查询是可靠的，见 `hostileNear`）。
- 全树 grep `m_6737_`：本模组共 20 个行为类覆写了它；本轮只修**被点名的那一个**（拆笼），
  其余逐个核对"它们的总开关是不是也只在 canUse 里问"留作 TD-10（不在这轮盲改）。
- DIY 残留（`MaidConfigPreset` 视角）：`MaidSweepMixin`（每次平A都横扫）与
  `MaidCanAttackDefenseMixin`（把"正在打我们这一边的非 Monster 生物"也算敌人）**完全没有开关**，
  表驱动改配置管不到它们 → DIY 下仍然生效。本轮给这两处补 `isDiy()` 硬闸。

**判据（口径）**：
1. 笼子刷什么，按**实体类别**判：`MobCategory.MONSTER` = 敌对 → 拆；
   原版和平类别（动物 / 水生 / 蝙蝠 / 蝾螈）→ 不拆；`MISC` 里 `minecraft:` 的（村民 / 铁傀儡 / 雪傀儡）→ 不拆；
   读不出注册名或落在无法判定的类别（模组实体落 MISC）→ **未知 → 拆**（宁可多拆不可漏拆）。
2. "读得出方块实体、但里面一个实体 id 都没有" = 真空/空壳笼子 → **不拆**（开发者原话）。
   只有**读不出来**（拿不到方块实体 / 读 NBT 抛异常）才算未知。
3. 总开关关掉 → **在跑的那一半也立刻停**（不是等它自己跑完）。

## 改法

1. 两树 `MaidSpawnerTorchBehavior.classifyEntity`：不再用 `getBaseClass()`，改读
   `EntityType.getCategory()`／`m_20674_()`（Forge）↔ `getCategory()`（Neo）判 `MobCategory.MONSTER`，
   原版和平类别显式列全；`MISC` + `minecraft:` 记和平；其余返回 null（未知）。
2. 两树 `spawnThreat`：`ids.isEmpty()` 一律 `EMPTY`（不拆）——不再按 `hasSpawnKey` 分叉到 UNKNOWN。
   `spawnSkipReason` 的 EMPTY 那句留痕改成"读得出方块实体、但里面一个实体都没配"，并附 `spawnEvidence`。
3. 两树 `m_6737_` / `canStillUse`：最前面加"总开关关着 → false"（在跑的那一半立刻停；
   停的时候 `m_6732_`/`stop` 照旧把借来的副手镐还回去）。
4. 两树 `MaidSweepMixin` / `MaidCanAttackDefenseMixin`：`MaidConfigPreset.isDiy()` 时**原样返回**
   （横扫回到原版公式、类型闸回到 TLM 原判）——DIY = 不介入，这两处本来没有任何开关。
5. 护栏 `tools/verify/check_preset.py`：新增「预设闸」检查（这两个 mixin 必须真的引用 `MaidConfigPreset`）
   + 「残留清单」打印（哪些 mixin 既没有配置项、也没有预设闸）。
6. **版本号：保持 1.3.1 不动**（开发者中途声明「版本号不要乱更新。除非开发者声明，否则不要乱改版本名」）——
   本轮一度升到 1.3.2，**已全部撤回**。同一版本号内重打包（改判据必然改字节）按 `rounds/RELEASES.tsv`
   如实留痕（旧字节一个个列出来）。这同时把 `WORKFLOW.md` §4.1 的"验收前不改版本号"落成了硬规矩。
7. **不写**：changelog 条目、release 说明、平台文案、配置项注释里的新功能介绍（按开发者第 3 条）。

## 触及文件

- 代码（两树各 4 个）：
  `combat/MaidSpawnerTorchBehavior.java`、`mixin/MaidSweepMixin.java`、`mixin/MaidCanAttackDefenseMixin.java`；
  版本位：3 份 `mods.toml`、2 个 build 脚本、`deploy_all.py`、`sync_desktop_snapshot.py`、2 个 test 脚本、`README.md`。
- 护栏：`tools/verify/check_preset.py`。
- 记录：本文件、`BUG_DEBT.md`、`TECH_DEBT.md`、`rounds/RELEASES.tsv`。
- **新配置项 0 / 新 i18n key 0 / 新开关 0**（DIY 那两处用既有的 `MaidConfigPreset.isDiy()`，不新增配置项）。

## 验证

- 两树编译：`compile_promaid.bat` / `compile_neo.bat` → **EXITCODE=0**（Forge 88 警告，与改前同量级；Neo 0 错）。
  中途踩过一次坑：改 `canStillUse` 的 javadoc 时**多写了一个 `*/`** → 注释提前收口，javac 报 38 / 46 个
  「非法字符」；把多出来的 `*/` 去掉即 0 错（这就是"编译日志按 GBK 解 + 盯错误行数"的用处）。
- **反证（读的是打好的 jar，不是源码）**：`javap -p -c -classpath patched/promaid-1.3.1-*.jar
  com.maidsmart.combat.MaidSpawnerTorchBehavior` →
  - Forge：`classifyEntity` 里 `invokevirtual EntityType.m_20674_()` + `getstatic MobCategory.MONSTER`；
    整个类里 `m_142225_`（getBaseClass）出现 **0** 次；
  - Neo：`invokevirtual EntityType.getCategory()` + `MobCategory.MONSTER`；`getBaseClass` **0** 次；
  - 两个 mixin 的字节码里都能查到 `MaidConfigPreset`（= DIY 闸真的打进去了）。
- **数据面实证（这条判据的"标尺"从哪来）**：仓库里已反编译的 1.20.1 `EntityType.java`（`_rev23/…`）逐条对上：
  `creeper → MobCategory.MONSTER`、`cow → CREATURE`、`villager / iron_golem / snow_golem → MISC`、
  `hoglin → MONSTER`（偏保守，一并按敌对处理）。所以「刷苦力怕的笼子」现在走 HOSTILE → 拆；
  同一条留痕不会再出现"读到 creeper 却说只有和平生物"。
- **旧行为的反证（这条 bug 为什么以前必然发生）**：`javap` 两条树的 `getBaseClass()`/`m_142225_()`
  字节码都是 `ldc class Entity; areturn` → `Monster.class.isAssignableFrom(Entity.class)` 恒 false，
  所以"只刷和平生物"这条留痕以前对**任何**读得出注册名的笼子都会出现，与日志 16:07:38 完全一致。
- 护栏（本机全跑）：
  - `check_workflow.py` FAIL 0 / WARN 3（把现行字节按 §5.3 登记后，1.3.1 的"未登记/同号覆盖"两项告警消失；三条存量 = `PUBLISH_GUIDE` 记 1.2.4、
    1.2.4 归档件名不符、13 个更早的 jar 未登记）；
  - `check_preset.py` FAIL 0 / WARN 2（存量 `broom.enable` 撞键；新增"干预型 mixin 必须有闸"检查通过）；
  - `check_changelog.py` **100148 不变**；`check_comment_style.py` **2258**（与改前持平：新写的
    `classifyEntity` javadoc 第一版 11 行被判 LONG-BLOCK（+2），压到 5 行后回到 2258）。
- 打包：`MISSING: none`；`verify_jar_classes` OK；`patched/` 里 1.3.1 的同名两包**被本轮重打覆盖**
  （Forge `a30261da…4f6f2b` 18,925,442 B / Neo `63b157e8…c4e2eb0` 18,963,455 B；旧字节已列进 `RELEASES.tsv`；
  1.3.0 那对与更早的归档包**一个没动**；顺带把"从没作为版本存在过"的 1.3.2 包删掉）。
- 装机：`deploy_all.py` → 4 个目标 `match=True`（同字节），每个 `mods/` 里只剩 1 个本模组 jar（1.3.1）。
  桌面快照 `Promaid_本体当前/01_jar` 也只剩 1.3.1 两包（顺手给同步脚本加了"01_jar 里非当前版本的
  `promaid-*.jar` 一并删掉"，免得同目录放着两版被拿错）。
- **未做的验证**：按开发者要求**不跑专服/实机回归**——装机后停下等验收（§4.1）。

## 验收

- 状态：**已验收@2026-10-10**（开发者开闸「开始跑部署和回归，然后写 changelog 和 release 声明」；
  本轮专服回归两加载器各两件全过、本机 4 实例 `match=True`）。
- 验收看过的日志关键词：`刷怪笼` / `它的刷怪数据是空的` / `只有和平生物` / `读到 entity.id`。

## 边界（没动什么）

- **不动** `store/`、`_tmp_pub/`、已发布产物（1.2.4 / 1.3.0 / 1.3.1 的 jar 与登记原样保留）。
- **不动** 拆笼的其它闸：隔墙不拆（恒生效）、在家/工作区圈内不碰、`breakRequirePickaxe`、
  战斗让位、自保一票否决、坐/睡/乘客。
- **不动** `MaidConfigPreset` 的 KEEP / SKIP / VALUES 表（DIY 该关哪些是另一件事，本轮只把"表管不到的两处"补上）；
  DIY 全量语义（"所有行为都跟原版一模一样"）里还剩下的项按 TD-10 逐条核。
- **不动版本号**：保持 1.3.1（开发者声明"除非开发者声明，否则不要乱改版本名"）——
  修 bug 装本机等验收期间**不改号**，同号重打包在 `rounds/RELEASES.tsv` 留痕。
- **不写**任何新文案（changelog / release / 配置注释的功能介绍）——按开发者要求。
- 没有 commit / push；临时脚本（`tools/_*`）本轮跑完即删。

## 台账

- 新增 **BD-09（P1，已清@838）**：笼子刷什么生物的判据恒为"和平"（`getBaseClass()` 两条树都恒等于
  `Entity.class`）+ 由此连带的两条（空壳笼子被拆、`canStillUse` 不问总开关）。
  证据：2026-10-10 16:07:38 的留痕自相矛盾（说"只有和平生物"、句尾读到 creeper）+ javap 字节码。
- 清掉：BD-08 的"后半段"（八百三十七 只修了"固定键名 + 读不到当和平"，本轮才发现**判类型那把尺本身坏了**）。
- 新增 **TD-10（未清）**：「开关只拦起手、不拦在跑的」这一类形状——本轮只修了被点名的拆笼一处，
  其余 19 个覆写 `canStillUse` 的行为待逐个核。
- 新增 **TD-11（未清）**：DIY 的"全量语义"（开发者要求"除新物品/新模式外，所有行为都跟原版一模一样"）
  还差一次逐表裁决——本轮只补了**表管不到的两处**（`MaidSweepMixin` 每次平A横扫、
  `MaidCanAttackDefenseMixin` 护主扩类），`KEEP` 表里仍有若干"会改变行为"的项（如 `misc.heldLightMaid`、
  `mine|wood.stuckWatchdog|pillarGuard|rideNoPillar`、`wood.tagAuto`）需要开发者点头后才动（会改玩家观感）。
  护栏里已能一键打印"无配置项、无预设闸"的完整残留清单（`check_preset.py --dump`）。
- 配额：修 bug 轮（§8 只对 FEATURE 轮要求"清一条 P1 或记一条新债"）；本轮已清 BD-09 并新增 TD-10/TD-11。
