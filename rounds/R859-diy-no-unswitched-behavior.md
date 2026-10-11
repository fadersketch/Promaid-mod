# 实测八百五十九 · DIY 私货清零：所有改行为的介入都落成开关（默认开 · 随 DIY 关）

## 需求 / 来源

开发者指令（本轮起因）：

> 我感觉diy模式仍然存在有私货没有办法调整的问题。现在我的要求是所有promaid做出的对女仆行为的
> 修改项目必须要有一个开关。然后随着DIY模式一起关掉。也可以自行打开。

承接实测八百四十（那一轮补了 2 处「无配置项的介入」+ 把 7 项行为类从 `KEEP` 移出）。但那一轮
§边界 写的是"KEEP 里剩下的都是纯附加/自家功能护栏"——本轮全量复核后发现**这句不成立**：还有一批
**直接改女仆行为、却完全没有配置开关**的介入点（既没进 `MaidSmartConfig`，也没问 `MaidConfigPreset`），
DIY 切过去之后它们照旧生效 → 正是开发者感到的"私货没法调"。

仍有效的约定：不跑回归、不写 changelog / 玩家可见文案、版本号不动（本轮仍 **1.3.2**，同号重打按
WORKFLOW §5.2 留痕）、两树纯 Java/字符串逐字节镜像、临时脚本用完即删。

## 评估（审计口径）

**审计面**：两树镜像，只查 `promaid_src`。三个来源：
1. `com/maidsmart/mixin/` 全部 96 个 mixin（逐个读、判类别、查是否引用配置/预设）；
2. 非 mixin 的行为面：`@Mod.EventBusSubscriber`、`@SubscribeEvent`、core 行为注册、`ProMaidExtension`
   的常驻轮询；
3. `MaidConfigPreset.KEEP` 表——逐个判"DIY 开着时它是否还改行为"。

**分类判据**（沿用 840）：改**女仆可见行为**（AI/移动/战斗/伤害/传送/拾取/装备/任务）→ 必须有开关、
默认开、不进 `KEEP`（= 随 DIY 关）；纯客户端渲染 / i18n / accessor-invoker / 只在自家功能里生效的
护栏 → 不算私货。

**结论**：17 处改行为的介入**无任何开关**（12 个 mixin + 5 个非 mixin 常驻）；另有 2 项 `KEEP` 里的
行为类仍随 DIY 生效（`teleportExemptSitting`、`aitools.craft`）——一并处理。

### 无开关清单（本轮要补的 17 项）

| # | 位置 | 改什么 | 拟开关 |
|---|---|---|---|
| 1 | `combat/FriendlyFireGuard.java` | 女仆对主人/同主友军的一切伤害三层取消 + 每 2 秒清仇恨 | `combat.friendlyFireGuard` |
| 2 | `combat/PetImmunityGuard.java` | 女仆对"玩家宠物"零伤害 + 去宠物仇恨 | `combat.petImmunity` |
| 3 | `combat/BuildShieldGuard.java` | 建造女仆无限抗性 V + 受击全免 + 不可被锁定 | `combat.buildShield` |
| 4 | `mixin/TaskAttackWeaponMixin.java` | 锄头从"武器"判定排除（改近战择物） | `combat.hoeNotWeapon` |
| 5 | `combat/FarmlandGuard.java` | 女仆经过/落地不再把农田踩成泥土 | `misc.farmlandGuard` |
| 6 | `task/MaidWeaponGuard.java` | 幼儿女儿手上的剑/斧/弓/弩/三叉戟被删并丢地（每 1 秒轮询） | `misc.weaponGuard` |
| 7 | `task/MaidToolAutoEquipBehavior.java` | core 行为每 tick 自动换主手工具/补副手盾/建造强制坐下 | `misc.toolAutoEquip` |
| 8 | `mixin/MaidBaubleTotemMixin.java` | 女仆自己从饰品栏/背包自动消耗不死图腾复活 | `misc.baubleTotem` |
| 9 | `ProMaidExtension.onOrbJoin` | 8 格无玩家时经验球归 24 格内最近挖矿女仆 | `misc.orbJoin` |
| 10 | `ProMaidExtension.onMaidEquipmentLeave` | 装备槽被清空时把物品"救回"（防丢） | `misc.equipRescue` |
| 11 | `follow/HomePatrolHandler.java` + `follow/HomeWorkMovementDriver.java` | home 模式女仆被强写随意走位（巡逻 / 宰杀工作移动） | `misc.homeMovement` |
| 12 | `mixin/MaidStationaryMixin.java` + `mixin/MaidMoveSuppressMixin.java`(静止分支) | 站桩/建造坐姿时从移动层锁死水平位移 | `misc.stationaryLock` |
| 13 | `fishing/FishingChairService.java`(sweep/tickKeepSeatWalkAll) | 钓鱼自动生成坐垫 + 维持坐垫走位 | `misc.fishingChair` |
| 14 | `mixin/RandomStrollGoalRiddenMixin.java` | 被本模组女仆驾驶的坐骑不许原版闲逛 | `misc.drivenNoStroll` |
| 15 | `mixin/ChunkFreezeMixin` + `GravityFreezeMixin` + `LeavesBlockMixin` + `MixinTntBuildGuard` | 建造区随机刻冻结 / 重力冻结 / 蓝图树叶不衰减 / TNT 不点火 | `build.areaProtection` |
| 16 | `mixin/BuildActivityForceMixin.java` | 建造任务期间强制把 activity 钉在 WORK（绕过作息） | `build.forceWorkActivity` |
| 17 | `mixin/PromaidPromptMixin.java` | LLM system 提示词无条件追加"坐姿工作"段 | `dialogue.promptSittingWork` |

### KEEP 表里仍随 DIY 生效的行为项（本轮移出）

| 路径 | 为什么算私货 |
|---|---|
| `teleportExemptSitting`（字段 `TELEPORT_EXEMPT_SITTING`） | 坐/蹲女仆豁免自保传送——改的是**移动行为**，不是"少管"；DIY 下必须回原版 |
| `aitools.craft`（字段 `TOOL_CRAFT`） | 让 LLM 直接指挥她用背包材料合成——与已随 DIY 关的 `aitools.place/switchTask/airRaid/workArea` 同性质，却留在了保留表里 |

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 侦察 | 读 96 mixin、非 mixin 行为面、`KEEP`/`SKIP`/护栏三表，逐项定"改不改行为" | 本文 §评估 | ☑ |
| 2 | 设计 | 定 17 条新开关的路径/字段/组/默认值；定 KEEP 移出项；定闸接点 | 本文 §改法 | ☑ |
| 3 | 实现 | 两树 `MaidSmartConfig`：+17 声明 +17 定义（分组末尾，带 comment/translation，默认 true） | 两树 | ☑ |
| 4 | 实现 | 两树 17 处代码闸接上新开关（mixin ×12、非 mixin ×5 处文件，共 20 处接点） | 两树 | ☑ |
| 5 | 实现 | 两树 `MaidConfigPreset.KEEP`：移出 `teleportExemptSitting` / `aitools.craft` | 两树 | ☑ |
| 6 | 实现 | 两树 `assets/maid_smart/lang/{en_us,zh_cn}.json`：+17 配置翻译 key（原生配置界面可见） | 4 文件 | ☑ |
| 7 | 护栏 | `check_preset.py`：`BASELINE_FLIP` 重算、`GATES`/新表 增接点、`--dump` 复核；重跑 `gen_switch_inventory.py` 刷 `ARCHITECTURE-SWITCHES.md` | 2 文件 | ☑ |
| 8 | 镜像 | Neo 树逐文件对齐（SRG↔Mojang；纯 Java/字符串逐字节一致） | 引用计数比对 | ☑ |
| 9 | 验证 | 两树 `javac EXITCODE=0`；四护栏问题数不增；`check_preset` FAIL 0；打包自检 | 构建输出 | ☑ |
| 10 | 留痕 | TD-11 更新（本轮再结清）+ 本文 §验证/§验收 | 两处 | ☑ |
| 11 | 清理 | 删临时脚本（`tools/_gateaudit.py` `tools/_mixaudit.py` `tools/_mix.txt` `tools/_r859*.py`） | — | ☑ |
| 12 | 验收 | 交开发者实测（切 DIY 后这 17 项全变关；面板/原生配置界面里能看到、能单独打开） | 待开闸 | ☑ |

## 改法

1. **17 条新配置项**（全部布尔、默认 `true`、不进 `KEEP` → 反射预设自动在 DIY 置 false）：
   - combat 组：`friendlyFireGuard` / `petImmunity` / `buildShield` / `hoeNotWeapon`
   - misc 组：`farmlandGuard` / `weaponGuard` / `toolAutoEquip` / `baubleTotem` / `orbJoin` /
     `equipRescue` / `homeMovement` / `stationaryLock` / `fishingChair` / `drivenNoStroll`
   - build 组：`areaProtection` / `forceWorkActivity`
   - dialogue 组：`promptSittingWork`
   每条带 `.comment(...)`（中文一句"开=什么/关=回原版"，不写历史）+ `.translation("config.promaid.<组>.<key>")`。
2. **接闸**（关掉 = 完全回 TLM/原版）：
   - `FriendlyFireGuard.isFriendly` 顶部一行总闸（三层防护与仇恨清除全部经由它）；`PetImmunityGuard.isPetMarked`
     顶部一行（本类唯一消费者）；`BuildShieldGuard` 三机制各加开关（效应维护要能在关掉时**撤掉**已给的效果——
     `tickShield` 的 `building` 与开关取 `&&`，原有 else 分支负责移除）。
   - 其余各处均为"方法/注入点顶部早退"或"条件加 `&& 开关`"。
3. **KEEP 表**：删除 `teleportExemptSitting`、`aitools.craft` 两个字符串；表注释同步（这两类现在随 DIY 关，
   代价 = 坐姿女仆被自保传送拉走 / LLM 不能用合成工具）。
4. **护栏**：`BASELINE_FLIP` = 151 + 17 + 2 = **170**（Forge）/ 155 + 17 + 2 = **174**（Neo）；
   `GATES` 增 mixin 型接点，新增 `GATES_ELSEWHERE`（字段 → 非 mixin 文件相对路径）覆盖其余接点；
   `OFF_ACK` 不变（无新增默认 false 项）。
5. 临时脚本跑完即删；不动版本号。

### 不做

- **不**给"只在自家功能里生效、DIY 关了功能就随之失效"的护栏再单独补开关（`build.noForceHome` /
  `bridge.buildOnlyNatural` / `bombing.*` / `misc.noPlaceInAir` / `misc.noPlaceOnOwner` / `accessory.daYun*` /
  `compat.puppetBlacklist`）——它们不是私货，DIY 下无功能可护。
- **不**给纯附加子系统（记忆/感知/情绪/对话/语音/手册/压缩盒/HUD）补开关——它们**本来就各有开关**
  （玩家可单独关），且不属"对女仆行为的修改"。
- **不**给扫帚任务分支（`SchedulePosTickMixin` / `SchedulePullbackMixin` / `EntityBroomMaidTravelMixin`
  的 `isBroomTask` 分支）补开关——扫帚模式本身有总开关，关了就没有扫帚任务，分支天然失效。
- 客户端渲染 / i18n / accessor-invoker 一律不算行为，不补。

## 触及文件

**改动**（两树各一份）：`com/maidsmart/config/MaidSmartConfig.java`（+17 项）、
`com/maidsmart/config/MaidConfigPreset.java`（KEEP −2）、`assets/maid_smart/lang/{en_us,zh_cn}.json`（+17 key ×2）；
代码闸：`combat/FriendlyFireGuard.java`、`combat/PetImmunityGuard.java`、`combat/BuildShieldGuard.java`、
`combat/FarmlandGuard.java`、`task/MaidWeaponGuard.java`、`task/MaidToolAutoEquipBehavior.java`、
`fishing/FishingChairService.java`、`follow/HomePatrolHandler.java`、`follow/HomeWorkMovementDriver.java`、
`ProMaidExtension.java`，以及 mixin：`TaskAttackWeaponMixin`、`MaidBaubleTotemMixin`、`MaidStationaryMixin`、
`MaidMoveSuppressMixin`、`RandomStrollGoalRiddenMixin`、`ChunkFreezeMixin`、`GravityFreezeMixin`、
`LeavesBlockMixin`、`MixinTntBuildGuard`、`BuildActivityForceMixin`、`PromaidPromptMixin`。

**工具**：`tools/verify/check_preset.py`、`tools/verify/ARCHITECTURE-SWITCHES.md`（生成物）、`TECH_DEBT.md`（TD-11）。

## 验证

| 项 | 结果 |
|---|---|
| 两树编译 | `javac EXITCODE=0`（Forge / Neo 各 21 处代码闸 + 配置改动后重编） |
| `check_preset` | **FAIL 0 / WARN 2**（仅 `broom.enable` 重复定义，TD-08 旧账）；DIY 关闭条数 **Forge 170 / Neo 174**，与新基线一致；配置项 598 / 602（+17）；17 条新开关的接点全部通过 `GATES`（7）与 `GATES_ELSEWHERE`（10）检查 |
| `check_comment_style` | 929 文件 / **2262 处**（与基线同数，未增） |
| `check_workflow` | **FAIL 0 / WARN 3**（三条旧 WARN：PUBLISH_GUIDE 版本、1.2.4 归档、13 旧 jar 未登记）；`ARCHITECTURE-SWITCHES.md` 与重生成结果一致（1385 行） |
| 打包自检 | 两树 `MISSING: none`、`lang json: OK`；Forge 17,568,843 B（`12000e1a…d1c195`）、Neo 17,607,774 B（`31d8c1c1…daa9cf`） |
| 镜像 | 17 个新字段的**引用点分布图**逐文件比对：Forge 与 Neo 完全一致（field ref-map mirror: OK）；四份 lang 的 17 个 key 全部就位 |
| jar 内自检 | 两个 jar 的 `MaidSmartConfig.class` 含全部 17 字段；`FriendlyFireGuard.class`/`TaskAttackWeaponMixin.class` 均引用各自开关 |
| 部署 | **未部署**（开发者闸门） |
| 回归 | **未跑**（开发者闸门） |

## 版本与上传

- 版本保持 **1.3.2**（开发者已就 1.3.2 更新声明过；本轮为同一版本内的续做）。
- **不**部署、**不**跑回归、**不**发 release —— 等开发者开闸「部署」。
- 同号重打按 WORKFLOW §5.2 在 `rounds/RELEASES.tsv` 留痕。

## 验收

**未验收**。

## 边界

- DIY 的"回原版"本轮再逼近一步：改行为且原先无开关的介入点，本轮全部落成开关并随 DIY 关。
- 剩余 `KEEP` 项已逐类复核为"纯附加子系统（各有开关）"或"只在自家功能里生效的护栏"，不再改变"她做什么"。

## 台账

- **TD-11**（DIY 全量语义）—— 本轮**再次结清**：17 处无开关介入 → 有开关；2 项行为类 KEEP → 随 DIY 关。
  审计脚本（mixin 全量 + 非 mixin 行为面）结论已写入 §评估，供下次复核复用。
