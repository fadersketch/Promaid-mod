# 债务台账 / Debt Ledger

> **本文件是唯一的债务台账**：行为 bug 债（BD-*）与结构债（TD-*）都在这里，另附自动证据快照。
> 原三份台账（`BUG_DEBT.md` / `TECH_DEBT.md` / `ARCHITECTURE-DEBT.md`）已合并进本文件，不再单独维护。
>
> **级别口径**：**P0** 崩溃 / 存档或物品丢失 / 专服崩；**P1** 功能明确失效；**P2** 观感·手感·性能；**P3** 文案。
> **状态取值**：`未清` / `已清@实测NNN` / `已确认保留（理由）` / `部分`。
> **配额（硬，见 `WORKFLOW.md` §8）**：一个 FEATURE 轮必须同时清一条 P1+ 债或记一条新债写明理由；
> **P0/P1 未清期间不得开纯功能轮**。
>
> 复现本文件 §3 的证据数字：`python tools/arch/debt_report.py`。
> 每条债必须写「影响」与「建议做法」，否则下一轮无从下手。

---

## 0. 按模块索引（改哪个模块，先看这里）

> 「风险」取该模块下最高级别；「收益」= 清掉这个模块的债能换来什么。

| 模块 | 债 | 风险 | 收益（清掉的回报） |
|---|---|---|---|
| **装备 / 物品托管** | BD-03 · BD-10 · BD-11 · BD-12 · TD-11 | P0 | 不再丢装备、不再刷物品（4 条恶性已清，靠「穿戴托管」兜底） |
| **刷怪笼 / 拆除** | BD-08 · BD-09 | P1 | 三态判据 + 递归读键，不再漏拆/误拆 |
| **挖矿 / 伐木 / 农场（生产链路）** | BD-04 · BD-13 · TD-02 | P1 | 生产可用、目标不抖、注释不误导（BD-04 未清：专项审计） |
| **跟随 / 召唤 / 传送** | BD-02 · TD-10 | P1 | 召她真的到、开关翻转立即生效 |
| **战斗 / 骑乘 / 蹲下语义** | BD-01 · BD-05 | P0 | 右击不再闪退；「蹲下=炮塔」尚缺官方口径（BD-05 未清） |
| **性能 / 周期任务 / 日志** | BD-06 · BD-07 · TD-12 | P2 | 进世界不卡、日志不刷屏、扫描有上限 |
| **配置系统** | TD-01 · TD-02 · TD-08 · TD-09 · TD-10 · TD-11 | P2 | 同一行为只有一个总闸、注释不洞、DIY 真回原版 |
| **文案 / i18n** | TD-03 · TD-07 | P3 | 玩家看到的只有「作用 + 取值」，英文界面不回落中文 |
| **代码质量（吞异常 / 反射）** | TD-14 · TD-15 · TD-18 | P2 | 出 bug 有日志、反射收敛成在场判断 |
| **mixin** | TD-16 | P2 | 冲突可二分关闭，不必改源码重编 |
| **构建 / 发布** | TD-06 · TD-13 | P1 | 外部可复现构建；版本号不乱（同号覆盖已禁） |
| **架构（两树镜像 / 选型）** | TD-05 · TD-17 | P1 | 已确认保留；长期才动（成本极高，收益最大） |
| **功能取舍（重复性）** | TD-04 | P2 | 与生态重复的功能逐项裁决（保留/合并/默认关/删除） |


---

## 1. 行为 bug 债（BD-*）— 按模块分组

**装备**

| ID | 级别 | 症状 | 证据 | 状态 |
|---|---|---|---|---|
| BD-03 | P1 | 女仆主/副手物品放回背包时凭空消失（丢过盾牌、剑、下界合金靴） | 原版 `LivingEquipmentChangeEvent.getFrom()` 是最后一份引用；旧钩子纯日志不改状态 | 已清@实测八百三十三 |
| BD-10 | P1 | **玩家从女仆界面取走副手物会被"救回"塞回来 = 刷物品**：`onMaidEquipmentLeave` 的第一道闸 `isPlayerDriven` 看调用栈里的容器/网络帧，但 `LivingEquipmentChangeEvent` 是 `LivingEntity.detectEquipmentUpdates()` 在**实体每 tick** 里补发的（javap 实证，两树一致），栈里永远没有这些帧 → 该判据**恒为 false**（形同没写）。拆笼是从她背包借镐到副手（背包那格腾空），于是玩家取走时 `inMaidContainers` 也 false → 塞回副手 → 取一次多一把，可反复刷 | 开发者原话「女仆在挖掘刷怪笼结束的时候…从（她）物品栏中把副手上的镐子给拿走。那么女仆的副手上又会凭空刷新一个一模一样的镐子，这是一个恶性bug，会导致玩家刷物品」 | 已清@实测八百四十一（第二道闸改为 `heldByAnyPlayer`：遍历该维度全部玩家，查界面光标 `containerMenu.getCarried()` 与背包任一格是否持有该件——一次界面取走只可能落到这两处；真丢失仍照旧救回） |
| BD-11 | P1 | **拆完刷怪笼「副手镐没还回去、背包还多一把」的复制**（与 BD-10 同症状、**不同成因**，且**不需要玩家参与**）：还镐 `restoreOffhand` 腾空副手 → 下一 tick 原版补发 `LivingEquipmentChangeEvent(from=那把镐, to=空)`（她原本副手为空，躲不过"to 非空即返回"）；救回的第一道闸 `inMaidContainers → hasSame` 用 `canItemStacksStack`，**要求耐久也一模一样**，而 `from` 是上一 tick 的旧副本、耐久比背包那件**差 1**（同一 tick 里 `hurtAndBreak` 刚扣掉 1 点）→ 判成"她身上没有" → 救回 → 副手 +1、背包那把也在。同类缺口：`inMaidContainers` 没查她的两只手（`MaidPlanting` 的"主手⇄副手换手"也会被当成丢） | 开发者原话「女仆在使用镐子挖刷怪笼的时候完以后，副手上的没有减少，背包里面又多出来一把」；`promaid.log` 2026-10-10 18:08:56 `[刷怪笼] 拆掉了刷怪笼 (-70, -57, 49)` 后紧跟 `[装备离开] …槽=OFFHAND 失去 1xminecraft:netherite_pickaxe | 她背包[… 24:1xminecraft:netherite_pickaxe …]` + `[装备救回] …找回 1xminecraft:netherite_pickaxe`（17:13 / 17:16 / 18:08 反复出现；同形状有时救有时不救 = 耐久附魔这次扣没扣耐久） | 已清@实测八百四十三（`hasSame` 的"是不是同一件"改成「同物品 + 数量够 + **去掉耐久后**完全相同」；`inMaidContainers` 增查两只手） |
| BD-12 | **P0** | **把女仆身上穿戴的装备拖进玩家物品栏时装备直接消失**（4 件护甲全中，之后换新装备重做又复现不了＝偶发）：旧「装备救回」是**当拍快照**判定——玩家把装备拿起时它在光标上，`heldByAnyPlayer=true` → 当拍不救（对，否则复制）；可玩家随后把它放进背包那一下万一真丢了，`LivingEquipmentChangeEvent` 只在"有→空"补发一次、不会再来第二枪 → 这段空窗**没有任何后续检查**会看它 → 永久消失 | 开发者原话「将女仆身上穿戴的钻石装备用鼠标移动到玩家的物品栏时，物品直接消失…身上的4件装备都是如此，全部丢失」；`promaid.log` 那一局 58 条 `[装备离开]` / **0 条 `[装备救回]`**（本模组对女仆装备只读：`MaidResyncCommand` 补包全是从她身上读），丢失发生在槽位空掉之后 | 已清@实测八百六十六（「穿戴装备托管」：装备离身即打标记，每 5 拍定位一次，只有在最终位置——她的容器/装备槽、任意玩家背包或当前界面、附近掉落物、别的女仆——确认过才销；只要还在玩家光标上就**续期不清**；到点 1.5 秒四处查无才放回她身上、绝不删。沿用 `misc.equipRescue` 总闸，不新增开关；删死代码 `heldByAnyPlayer`/`inPlayerPossession`/`inOwnerInventory`） |

**刷怪笼**

| ID | 级别 | 症状 | 证据 | 状态 |
|---|---|---|---|---|
| BD-08 | P1 | **拆刷怪笼只认固定键名 + 把"读不到"当"和平"**：整合包把刷怪表放在自己的键下（如 `custom_spawn_rules.spawn_potentials`）→ 一个键都不命中 → 判成"只刷和平生物"→ 刷苦力怕的笼子**永不拆**；`isHostileEntity` 解析失败也返回 false（未知 ≠ 和平）。三种互斥原因还被合并成一句留痕，无从判断 | 开发者原话「当时刷怪笼里面是刷的苦力怕，但女仆仍然没有选择去挖」+ `promaid.log` 2026-10-10 15:36 `刷怪笼 (-60,-57,-35) 现在不算威胁（空笼子 / 只刷牛羊猪这类和平生物 / 没在出货…）→ 不拆，留着` | 已清@实测八百三十七（三态 `SpawnThreat` + 递归捞 `entity.id` + 读不出来按"在出货"处理 + 三种原因各自成句 + 隔墙跳过改为限频留痕） |
| BD-09 | P1 | **笼子刷什么生物判据恒为"和平"**：`classifyEntity` 用 `Monster.class.isAssignableFrom(type.getBaseClass())` 判敌对，而 `getBaseClass()` 在两条树（1.20.1-47.4.23 / 1.21.1 NeoForge）**都恒等于 `Entity.class`**（javap：`ldc class Entity; areturn`）→ 判定恒 false → 凡读得出注册名的笼子（苦力怕 / 僵尸 / 牛羊猪…）全判成"只有和平生物"。连带：`ids.isEmpty()` 分叉到 UNKNOWN 的处置是"拆" → **空壳笼子（有刷怪键、表是空的）被拆掉**；`canStillUse` 不问总开关 → **开关关掉/切 DIY 之后，在跑的那一半照跑完**（最多再拆 20 秒） | 开发者原话「打开 DIY 之后还是会有一部分行为残留。比如挖掘刷怪笼」「现在女仆会把空的刷怪笼也挖掉」；`promaid.log` 2026-10-10 16:07:38 同一条留痕写着「它的刷怪数据里只有和平生物」而句尾 `读到 entity.id = minecraft:creeper`；11 秒后才靠"旁边出现敌对生物"直接证据拆掉 | 已清@实测八百三十八（改按 `EntityType.getCategory()/m_20674_` 判 `MobCategory.MONSTER`，和平类别逐个列全；`ids.isEmpty()` 一律 EMPTY→不拆；`canStillUse` 加总开关与拆笼开关两道判据） |

**挖矿**

| ID | 级别 | 症状 | 证据 | 状态 |
|---|---|---|---|---|
| BD-13 | **P1** | **挖矿目标锁定能力差 / 容易站桩 / 看门狗无效**：①锁定期间每 5 秒重挑一次、换目标就重置超时，两块矿分数接近时 A→B→A 抖动，够不着的矿永远等不到弃置；②`reachBonus(-400)` 随女仆位置实时翻转"谁进伸手圈谁最优"，分数跳变 400；③价值权重（矿价值 300×权重 2＝600 减分）压过距离 → 远处高价矿压过眼前煤矿；④`findStandNearOre` 找不到立足点返回 null 时 `walkToOreBase` 不设移动目标 → 她站在原地看矿发呆；⑤`hardResetStuck` 刻意保留 `targetPos` 与超时时钟 → 卡死根因正是"锁了够不着的矿"，看门狗只清计数器、目标照旧 | 开发者原话「女仆挖矿很不好用…对矿物的锁定能力很差，开隔墙穿透也一样。而且很容易站在原地不动。有看门狗也没什么用」 | 已清@实测八百六十八（借鉴 maid_mining 1.4.1：删「每 5 秒重挑」；锁定超时按破坏速度 3× 放大 `targetTimeoutTicks`；新增 `ABANDONED` 本锚点排除表；排序改「到女仆距离²」并删 `reachBonus`；`findStandNearOre` 扩 ±2 外圈 + 兜底直接朝矿走（**永不返回 null**）；`hardResetStuck` 不再保留死目标。保留搭方块/调障碍/换镐三大卖点与连锁挖矿） |

**生产链路**

| ID | 级别 | 症状 | 证据 | 状态 |
|---|---|---|---|---|
| BD-04 | P1 | 早期生产链路（挖矿 / 伐木 / 农场）"很粗糙、也有 bug，而且没怎么维护"——从没做过一轮系统性审计 | issue #37 第 2 条点名；挖矿/伐木是照抄出来的两套（同名方法、同段文案） | 未清（建议：专项审计轮，先扫两套的差异面 + 逐项实机验一遍） |

**跟随/召唤**

| ID | 级别 | 症状 | 证据 | 状态 |
|---|---|---|---|---|
| BD-02 | P1 | 「一键集合 / 召她过来」报成功却没到位、或看不见她 | `teleportCoreTo` 无条件 return true；短距召唤不触发客户端重建 | 已清@实测八百三十三 |

**战斗**

| ID | 级别 | 症状 | 证据 | 状态 |
|---|---|---|---|---|
| BD-01 | P0 | 女仆骑扫帚时对她右击闪退，之后世界进不去（issue #37 第 7 条） | 崩溃栈 "Pose stack not empty"；两个半透明重绘入口 | 已清@实测八百三十四 |
| BD-05 | P2 | 女仆"蹲下迎战会主动起身"破坏"蹲下不移动"的既有语义；玩家只能靠关总闸绕过 | issue #37 第 1 条；现可用 `combat.*` 总闸或 DIY 模式绕过，但没有"蹲下=炮塔/站桩"的官方口径 | 未清（建议：给"蹲下时禁止主动接敌"一个独立开关 + 默认保持既有语义，见 `WORKFLOW.md` §3.1②） |

**性能**

| ID | 级别 | 症状 | 证据 | 状态 |
|---|---|---|---|---|
| BD-06 | P2 | 加载后进世界变慢、内存占用高；大量周期检测（刷怪笼放火把、每几秒扫状态、4096 格扫描等） | issue #37 第 6 条；日志里可见多个周期任务；实测八百三十九拿到开发者 spark 报告（超平坦、零女仆存档：本模组 12 处每 tick 服务合计 76 ms = 服务器线程执行时间 5.6%，其中 8 处各拷一遍整张实体表） | **部分清偿@实测八百三十九**（新增 `MaidRoster` 名册按 20 tick 复用 + 12 处改走名册/加零女仆闸；零女仆时从约 14 次全量扫描/秒降到 1 次/秒）。剩余：进世界变慢的其它成因、以及非每 tick 路径的 `EntitySnapshot`（见 TD-12）；"逐个周期任务填频率/范围/最坏开销/开关四个数"仍未做全 |
| BD-07 | P2 | 周期功能日志刷屏，出问题时时间线不好对 | issue #37 第 6 条 + 作者回应第 6 条 | 未清（部分分类已有轮换与 4MB 上限；建议审计时一并给"每分类可关"） |

---

## 2. 结构债（TD-*）— 按模块分组

**配置**

| ID | 主题 | 证据 | 影响 | 建议做法 | 状态 |
|---|---|---|---|---|---|
| TD-01 | **配置耦合**：同一行为散在多处开关，优先级不明 | issue #37 第 3 条：搭路 9 条路径、砍树/战斗/跟随各有搭路 | 改一个行为要翻整本配置；牵一发动全身 | 用 `ARCHITECTURE-SWITCHES.md` 的主题索引按行为聚合，逐个行为并成"总闸 + 子项"并标注优先级；新开关按 `WORKFLOW.md` §3.1③ 登记 | 未清 |
| TD-02 | **配置注释照抄**：挖矿/伐木两套是复制出来的，有的字都没改 | issue #37 第 2 条 | 文案误导（说砍树其实是挖矿的口径） | 扫两树 `MaidSmartConfig` 的高相似注释对，逐对改写成各自口径（可在 `check_workflow.py` 加"雷同注释"告警） | 已确认（实测八百七十九核查）：扫 **28 对** `MINE_*` / `WOOD_*` 同后缀注释（含相似度排序）：大段（扫描预算 / 看门狗 / 目标超时 / 速度系数 / 硬挡路）**均已各自改写**，无误抄；仅 4 对完全相同 = **共用机制**（搭方块防掉落 / 重定位节流 / 搭方块冷却 / 废石清理），有意保留同文案，**无需改**。已挂棘轮@实测八百八十八：`tools/verify/check_comment_dup.py`（非白名单重复 `.comment` 组只减不增；当前 4 组全白名单，FAIL 0/WARN 0），已接进两 `build_promaid*.py` |
| TD-08 | **`broom.enable` TOML 路径重复定义**（`COMBAT_BROOM_ENABLE` 与 `COMBAT_PATROL_ENABLE` 撞键） | `check_preset.py` WARN（两树都有） | 后定义者覆盖前者，配置面板与 TOML 可能不同步 | 拆成两个路径或明确合并（会动已发布配置的键名，需一轮迁移） | 已清@实测八百八十二：巡逻总开关 `.define("patrolEnable")` → 路径 `broom.patrolEnable`（不再与扫帚 `broom.enable` 撞键）；新增一次性迁移标记 `broom.patrolEnableMigrated`（老档继承撞键值）；`check_preset` 撞键 WARN 归零，DIY 基线 173/177 → 174/178 |
| TD-09 | **配置注释里有删字留下的洞**：早期"去引号"批次把引语整段删掉后没接上，留下孤立的「。」与半句话 | 实测八百三十七 在 `MaidSmartConfig` 的 `spawnerTorch` 段看到：`…插火把压不住：` 换行后 `*  。默认开 = 发现就拆；` | 玩家读注释像被截断，怀疑配置失效 | 单独一轮扫两树 `MaidSmartConfig`：找"：\n *  。"这类形状，按上下文补回一句作用说明（同轮顺带 TD-02 的雷同注释） | 已清@实测八百七十九：扫两树 `MaidSmartConfig` 全部注释，找到 4 处删字空洞（1 处孤立 `。` 行 + `COMBAT_SPAWNER_TORCH_PRIORITY` 空注释 + `COMBAT_SPAWNER_BREAK` 孤立 `。` + `COMBAT_SPAWNER_BREAK_ACTIVE_ONLY` 空段，两树各一份），已按上下文补回作用说明；注释存量 2262→2260 |
| TD-10 | **"开关只拦起手、不拦在跑的"这一类形状**：本模组 20 个行为类覆写了 `m_6737_`/`canStillUse`，而 Brain 对**正在跑**的行为每 tick 只问它（不问 `canUse`）——只在 canUse 里问总开关的行为，开关翻转后在跑的那一半会自己跑完 | 实测八百三十八：`MaidSpawnerTorchBehavior.canStillUse` 原本只判自保接管，切 DIY 之后「拆笼最多还能再拆 20 秒」（开发者反馈的"残留"形状）；本轮只修了被点名的这一处 | 玩家按了开关却"看起来没生效"，只能等它自己停 | 逐个行为核一遍"总开关在 canStillUse 里也问了吗"，没有的补一行（每处 ≤3 行，不改语义） | 已清@实测八百七十八：遍审两树 **22** 个覆写 `canStillUse`/`m_6737_` 的行为，**7 个真候选**（`canUse` 读总开关、`canStillUse` 未读）已补齐__MaidAidOwnerBehavior__/__MaidShieldShareBehavior__/__MaidTorchPlacerBehavior__/__SelfPreservationBehavior__/__WaterClutchBehavior__/__MaidFlightCombatBehavior__/__BuildContainerFetchBehavior__（每处 ≤3 行）；其余 15 个 = 已对称 5 / 有意 `false` 一次性 2 / 任务 uid 判定 7 / `MaidMineBehavior` 的启动期子选项 1，**均不改**。7 个开关默认均为开→开着时行为逐字不变 |
| TD-11 | **DIY 的"全量语义"逐表裁决**：开发者口径是「除新添物品/模式外，打开 DIY 后女仆所有行为都跟原版一模一样」，`MaidConfigPreset.KEEP` 里一度留着若干**会改变行为**的项，表驱动因此关不掉它们 | 实测八百三十八：开发者反馈"打开 DIY 之后还是会有一部分行为残留"（先收口两处无配置项的 mixin）；实测八百四十：开发者指令「这类功能全部都加入配置开关、默认开启、响应 DIY 以后自动关闭」；实测八百五十九：开发者反馈「diy模式仍然存在有私货没有办法调整的问题……所有promaid做出的对女仆行为的修改项目必须要有一个开关，随 DIY 一起关、也可以自行打开」（全量复核出 17 处无开关的行为介入 + 2 项行为类 KEEP） | 主项已消失 | 已做（实测八百四十）：①两处无配置项的介入（`MaidSweepMixin` 平A横扫、`MaidCanAttackDefenseMixin` 护主扩类）落成真开关 `combat.sweepAlways` / `combat.attackThoseAttackingUs`（默认 true、DIY 自动关）；②`KEEP` 移出 7 项行为类（`misc.heldLightMaid`、`mine\|wood.stuckWatchdog`、`mine\|wood.pillarGuard`、`mine.rideNoPillar`、`wood.tagAuto`），DIY 下真回原版；③护栏同步：`check_preset.py` 的 `GATES` +2、`PRESET_GATES` 清空、基线 150/154。**实测八百五十九**：④17 处无开关的行为介入（`combat.friendlyFireGuard`/`petImmunity`/`buildShield`/`hoeNotWeapon`、`misc.farmlandGuard`/`weaponGuard`/`toolAutoEquip`/`baubleTotem`/`orbJoin`/`equipRescue`/`homeMovement`/`stationaryLock`/`fishingChair`/`drivenNoStroll`、`build.areaProtection`/`forceWorkActivity`、`dialogue.promptSittingWork`）落成真开关（默认 true、随 DIY 关）；⑤`KEEP` 再移出 `teleportExemptSitting`/`aitools.craft`；⑥护栏：`GATES` +7、新 `GATES_ELSEWHERE` +10、基线 170/174 | 已清@实测八百四十；**复清@实测八百五十九**（`KEEP` 现只剩纯附加子系统（各有开关）与"只在自家功能里生效"的护栏；`teleportExemptSitting`/`aitools.craft` 已移出随 DIY 关） |

**文案/i18n**

| ID | 主题 | 证据 | 影响 | 建议做法 | 状态 |
|---|---|---|---|---|---|
| TD-03 | **可见文案里的开发过程**：配置注释/面板/手册里写 `实测NNN` 与"某轮修了什么" | issue #37 第 3 条；存量计数见 `ARCHITECTURE-SWITCHES.md` 末尾（现 0/0） | 玩家看的是配置介绍，不是开发日志 | 新文案一律不带（已在 `WORKFLOW.md` §6 立规）；存量分批改成"作用 + 取值 + 关掉会怎样" | 已清@实测八百八十五：**手册正文那一块已清@实测八百四十六**（两树 5 个章节正文解码后 `判据`/`闸门`/`原来` 均 0、`我们` 只剩 4 句玩家引语；`en_us.json` 里手册用得到的 762/765 条 key，英文 value 的开发口吻已同步改掉）；**① 配置项 comment 与 ② 面板/工具提示 已清@实测八百五十五**（两树配置注释 77/81 条删净轮次号、面板注释 422 行压到 ≤100 字并删净，jar 内**全条目字节**搜「实测」= 0）。**③ 已清@实测八百八十五**：英文 value 的开发口吻（`we/our/us/ours`，两树合计 85 处）与自测轮次号（`Test/Tested NNN`、`tested at`/`playtest`/`measured at`/`since`/裸轮次号 641/642/679/726/608..615 等，两树合计 239 处改写）全部改成「作用 + 取值」；**④ 已清@实测八百八十五**：中文可见字面量（面板描述 / 压缩盒自测串 / 迁移回执）里残留的裸轮次号 15 处/树——源码字面量与 i18n 键同步改名；另删手册「开发者寄语」一句纯开发过程的「测试次数超过 300 次」（源码 + 键同删）。新增棘轮护栏 `tools/verify/check_dev_voice.py`（`test/实测` 轮次号 + `we/our/us` 双判据，两树两表基线 0/0），已接进两 `build_promaid*.py`；`WORKFLOW` §11 / `DEV_GUIDE` §6 / `BUILD` §5 护栏表由 5 增至 6。注：源码注释里的"出处一行"（`// 实测NNN：…`）按 `DEV_GUIDE.md` §1.2 保留，不进 jar；扩容@实测八百八十八：同护栏增扫源码 `.comment` 正文与 i18n 键面（两树基线 0/0），护栏表 6→8 |
| TD-07 | **i18n 表漂移**：老轮次改了源码文案但没同步 key，英文界面回落中文 | 实测八百三十五发现：抽查出约 62 条可见中文在表里没有精确对应条目（如面板写"默认 10"、表里还是"默认 60"） | 英文客户端看到中文 | 单独一轮：按"源码字面量 → 表"双向核对，补/改 key 与英文 | 已清@实测八百八十四：查清缺的 key 仅影响“其余开关（自动补全）”面板页（缺 key → 显示裸 key），非“回落中文”；两树各 **184** 条 `config.promaid.*` 短名补齐（zh+en）→ `check_i18n_keys` **基线 0/0**（棘轮）。此外 `en_us.json` 含中文值 = 0。注：面板主页的中文字面量是另一张表 `assets/promaid/i18n/en_us.json`（按中文字面量作 key），属 TD-03 范畴 |

**代码质量**

| ID | 主题 | 证据 | 影响 | 建议做法 | 状态 |
|---|---|---|---|---|---|
| TD-14 | **静默吞异常存量（catch ignored）** | issue #38 第 4 条；两树合计 `catch (Throwable ignored)` **3150** 处、`catch (Exception ignored)` **620** 处（927 个 java 文件）；`WORKFLOW.md` §2.4 已把 `catch(Throwable){return 正常值}` 列为硬禁反模式，但只禁新增、存量未清 | 出 bug 时日志无痕（正是 #37 / #38 反复点名的"她不动、日志空"那一类）；异常被翻译成正常结论 | 单独一轮：①护栏加"计数基线，只减不增"棘轮；②热点路径（外部模组探针 / 渲染 / 网络包 / 存档迁移）改 log-once；③"读不到 = 正常值"的形状改三态（UNKNOWN） | 已清@实测八百七十四：静默吞异常（`catch (... ignored)`）两树清零（1881/1889 → **0/0**），棘轮护栏 `tools/verify/check_catch_swallow.py` 基线归零（此后新代码不许再裸吞，要吞就写 `Diag.swallow("类名", ignored)`）；四轮共烧 **2579/2582** 处，`Diag.swallow(` 计数 1881/1889 = 改前静默数（1:1 无漏无多）。③"读不到 = 正常值改三态"未做（随 TD-15 另议）；非 `ignored` 的具名泛捕获另记 TD-18 |
| TD-15 | **反射兼容未收敛** | issue #38 第 5 条；两树 `getMethod(` **419**、`Class.forName` **174**，而"模组在场判断" `ModList.get()` 只有 **11** 处 | 效率低、错误要手处理、无补全；与本模组 `FriendlyFireGuard` / `PetImmunityGuard` 那种"兼容类 + 在场判断"范式不统一 | 把探针式反射按模组收敛成"兼容类 + `ModList.get()` 在场判断"，每 tick 路径优先；与 TD-01（配置耦合）同批 | 已清@实测八百八十三：每 tick 路径那一半已做（R877）；剩下的“在场判断”已补到**单一发行版**的探针（`MaidMountCompat` SWB/IAF、`MaidBdCompat`、`MaidFlightCombatBehavior` TaCZ）；**多发行版**探针（`MaidGoetyCompat`）与为保两树同源的 `CuriosElytraCompat` **有意保留**（代码注释已写明，避免后人误改而误伤多发行版/破镜像）。已挂棘轮@实测八百八十八：`tools/verify/check_reflection.py`（`Class.forName`/`getMethod`/`getDeclaredMethod`/`getDeclaredField` 处数只减不增，基线 Forge 86/204/6/7、Neo 82/203/5/7，FAIL 0/WARN 0），已接进两 `build_promaid*.py` |
| TD-18 | **非 `ignored` 的具名泛捕获里可能仍静默**：`catch (Throwable t)` 两树 490 处、`catch (Exception e)` 200 处 | 实测八百七十四 清尾时实测计数（有具名变量 → 体未必空，可能已处理，也可能只 `return` 某正常值） | WORKFLOW §2.4 反模式"把异常翻译成正常值"可能仍藏在这里 | 同法先量一遍"体首既不是 `Diag.swallow`、也不是日志、也不是 `return void`"的近似集，再逐族清；或把 `Diag.swallow` 推广过去 | 已清@实测八百七十五：`check_catch_swallow.py` 加第二把尺 `SILENT_NAMED`（具名且体首是 `return <值>;` / 纯注释 / 空 → 判静默），与 `ignored` 族合并计基线；烧 **240/243** 处（191 文件实例）→ 具名静默 **0/0**，基线仍 **0/0**。余下具名捕获体里有 log / 其它语句者**不判为债** |

**性能**

| ID | 主题 | 证据 | 影响 | 建议做法 | 状态 |
|---|---|---|---|---|---|
| TD-12 | **"女仆相关服务各自扫实体表"的存量**：①`EntitySnapshot.of()` 仍是"整表拷贝"语义，全模组还有约 20 处**非每 tick** 路径（命令、界面、一次性扫描等）在用它；②三处注释把 `ClassInstanceMultiMap 桶 bug` 当成了类查询漏扫的原因（javap 证明类桶是 `computeIfAbsent` 按需补建，真因是同段写明的无限 AABB 溢出）；③Neo 树独有 `MaidShellHoming.tick`（每 tick，内部两张表空时零开销），1.20.1 树已整体删除该功能 | 实测八百三十九：javap 实证（`ClassInstanceMultiMap.m_13533_` = `byClass.computeIfAbsent(...)`；`EntityLookup.m_260822_` = 遍历全部实体 tryCast，不查类桶，故"每刻重建名册"比按 20 tick 复用更贵）；引用计数逐文件比对（`MaidShellHoming` 一边倒 0/1） | ①零女仆时仍有少量白扫；②注释误导下一个人（可能让人不敢用类查询或误判漏镜像）；③两树功能差异易被误判为漏镜像 | ①下轮审计非每 tick 的 `EntitySnapshot` 用法，能换名册的换；②与其它注释整理同轮改那三处；③保持本行留痕即可 | 已清@实测八百八十六：**① 已做**——全模组还剩 22/23 处 `EntitySnapshot.of`，逐处审计后 **20 处/树**（循环体只用到女仆）来源表达式换成新增的 `MaidRoster.inAsEntities(ServerLevel)`（只换来源、循环体一字不动）；**有意保留 2/3 处**真需要非女仆实体的站点（`RideBindManager` 骑乘留痕 / `FishingChairService` 自动坐垫 / Neo 独有 `MaidShellHoming` 制导弹）。**② 已做**@实测八百八十（错误归因更正为“无限 AABB 溢出，类桶按需补建”）。③ Neo 独有 `MaidShellHoming.tick`（每 tick、内部两张表空时零开销）**留痕即可、不改** |

**构建**

| ID | 主题 | 证据 | 影响 | 建议做法 | 状态 |
|---|---|---|---|---|---|
| TD-13 | **构建不可外部复现：没有 Gradle** | issue #38 第 1 条（clone 后没有 `build.gradle` / `gradle.properties`，无法构建）；实况：仓库只有自定义 `tools/build/*.py` + `*.bat`，且 `compile_addon.txt` 的 classpath 是 `D:\.minecraft\libraries\...` 本机绝对路径 | 外部贡献者无法本地构建 / 提 PR，协作门槛高；CI 也做不了 | ①先出 `BUILD.md` 把现有链讲清（本行已做）＋ 把两处绝对路径改成可配置（环境变量 / 相对路径）；②再做自包含 Gradle（ForgeGradle / NeoGradle + 官方仓库，TLM 走依赖坐标或 `libs/`） | 部分：`BUILD.md` 已出@实测八百七十；Gradle 与路径可配置已做@实测八百七十六：两个生成脚本把写死的 `D:/.minecraft/...` 前缀改成可配置（`PROMAID_MC_HOME` / `PROMAID_LIB` 环境变量），仓库自身位置自动重算；不设变量时输出逐字节不变；缺失条目打一行 WARN。脚本：`gen_compile.py` / `gen_compile_neo.py`、`BUILD.md` §一.1。**等价可复现方案已做@实测八百八十七**：新增 `tools/build/deps.lock`（把两树编译 classpath 每个 jar 钉成「根相对路径 + sha256 + 字节数 + maven/local」，实测生成 98 行）+ `tools/build/check_deps.py`（离线核对 / `--plan` 打印获取计划；本机核对 forge 40 一致 + 1 可选缺失、neo 57 一致，FAIL 0/WARN 0）；外部贡献者据此可取到逐哈希一致的工具链。自包含 Gradle **仍未做**：需联网下载 ForgeGradle/NeoGradle（本机离线、无法验证），且 TLM / libIPN 是第三方 jar 必须自备，套 Gradle 还要另建两树 sourceSet（动工程骨架）——已在 `BUILD.md` §七 写明理由 |

**发布**

| ID | 主题 | 证据 | 影响 | 建议做法 | 状态 |
|---|---|---|---|---|---|
| TD-06 | **版本号多处不一致** | 实测八百三十六：`mods.toml`=1.3.0 / `README`=v1.3.0 / `PUBLISH_GUIDE`=1.2.4 | 玩家分不清自己是不是最新（正是 #37 第 4 条） | 发布前按 `WORKFLOW.md` §5 一次对齐并登记 `rounds/RELEASES.tsv` | 已清@实测八百三十七（三份 `mods.toml` / 两个 jar 名 / `README` 统一 1.3.1；`PUBLISH_GUIDE`=1.2.4 是"平台上有什么"的记录，按 §5.5 不属不一致） |

**mixin**

| ID | 主题 | 证据 | 影响 | 建议做法 | 状态 |
|---|---|---|---|---|---|
| TD-16 | **混入不可控：95+ 个混入无插件** | issue #38 第 6 条；实况：两树各一份 `mixins.promaid.json`、96 / 95 个混入类，**无** `IMixinConfigPlugin` | 与别的模组混入冲突时只能改源码重编才能二分定位 | 加 `IMixinConfigPlugin`，提供按名字 / `all` 选择性关闭的口径 | 已清@实测八百七十（`com/maidsmart/PromaidMixinPlugin`，`-Dpromaid.mixin.disabled=all` 或逗号分隔的简单名；默认全部生效、行为与不加插件一致） |

**架构**

| ID | 主题 | 证据 | 影响 | 建议做法 | 状态 |
|---|---|---|---|---|---|
| TD-05 | **两树镜像重复**（最大的重复面） | `ARCHITECTURE-DEBT.md` §4.1 | 一次改动要写两遍，容易漏 | 长期可考虑单一源码 + 预处理生成另一树；短期靠 `check_preset.py` 那种"两树表逐项比对"护栏兜底 | 未清（已确认保留，暂不动架构） |
| TD-17 | **架构选型的三条外部建议（有意不改，记录理由）** | issue #38 第 2 / 3 条 + 结尾：①两加载器应拆成两条分支；②自写 i18n + 一堆 mixin 不如改用 Patchouli / Cloth Config；③"从头开始写" | 外部协作者的心智负担；但这些是设计取舍而非缺陷 | 记录立场即可：①**不拆**——现"同仓两树镜像 + 逐项护栏比对"能保证"一次改动同时验证两加载器一致"，拆分支就丢了它（见 TD-05）；②**不换**——Patchouli / Cloth 会引入硬依赖，与本模组"零硬依赖 + 自带手册 / 配置面板（含 DIY 预设）"的目标冲突；③**不重写**——模组已上线、有验收与发布登记，按 `WORKFLOW.md` 的债务配额逐步收敛（本台账与 `BUG_DEBT.md` 就是收敛通道） | 已确认保留（理由） |

**功能取舍**

| ID | 主题 | 证据 | 影响 | 建议做法 | 状态 |
|---|---|---|---|---|---|
| TD-04 | **功能重复性未经裁决**：落地水（vs 饰品）、压缩盒（vs 精妙背包）、中键设工作位置（vs 河童罗盘） | issue #37 第 5 条 | 与生态重复，玩家觉得冗余 | 逐项做 §3.1① 的四问裁决并留痕：保留（写明理由）/ 合并 / 默认关 / 删除 | 未清 |

---

## 3. 屎山快照（自动证据 · `python tools/arch/debt_report.py`）

> 本节是**机器实测**的结构快照（超大文件 / 补丁坟场 / 死代码 / 重复逻辑 / 隐患 / 清理顺序），
> 取自 NeoForge 树；数字会随源码变化，重跑脚本即可刷新。它不是逐轮台账，改动不必同步它。

### 0. 先看结论：三条必须动刀的

| # | 问题 | 规模 | 为什么必须动 |
|---|---|---|---|
| **1** | **两棵手工镜像源码树** | 390 个同路径文件，其中 **224 个行数已经不一致** | 每个功能写两遍；两树已开始分叉，再拖下去无法再手工同步 |
| **2** | **`PromaidConfigScreen.java` 单类 6424 行** | 含 543 行 `render()`、468 行 `clickCookGrid()` | 加任何一个设置项都要改这个巨型类；是全部 UI 改动的阻塞点 |
| **3** | **补丁坟场**：`SelfPreservationBehavior` 4696 行 / `MaidMountCompat` 4746 行 | 两文件共 **327 处** `实测NNN` 历史标记 | 逻辑按「第几轮实测修的」堆叠，读不懂、不敢改、改一处崩三处 |

---

### 1. 超大文件排行（>1200 行，NeoForge 树）

| 行数 | 文件 | 性质 |
|---|---|---|
| **6424** | `config/PromaidConfigScreen.java` | 手写立即模式 UI 巨型类 |
| **4746** | `combat/MaidMountCompat.java` | 兼容模块吞掉了整个功能 |
| **4696** | `combat/SelfPreservationBehavior.java` | 补丁坟场（164 处实测标记） |
| **3493** | `combat/MaidFlightCombatBehavior.java` | 空袭主循环（222 处实测标记） |
| **3172** | `config/MaidSmartConfig.java` | 524 个设置项 + 1850 行 static 初始化块 |
| 2951 | `task/MaidWoodBehavior.java` | 伐木（自述是挖矿的完整克隆） |
| 2845 | `task/MaidMineBehavior.java` | 挖矿 |
| 2571 | `combat/MaidBroomDrive.java` | 扫帚飞行 |
| 2270 | `combat/RideBindManager.java` | 骑乘绑定语义 |
| 2139 | `build/BlueprintBookScreen.java` | 6 视图蓝图界面 |
| 2090 | `follow/MaidChunkLoadManager.java` | 区块票 + 跨维跟随 + 队列 + 可见性 |
| 1951 | `build/MaidBuildBehavior.java` | 建造执行 |
| 1946 | `combat/GunnerTetherManager.java` | 武装拴绳（与 RideBindManager 职责重叠） |
| 1859 | `combat/MaidAidOwnerBehavior.java` | 主人辅助 |
| 1748 | `combat/MaidFlightKit.java` | 空袭套件 |

> 这 15 个文件合计约 **45,000 行**，占 NeoForge 树 394 个文件的绝大部分体量。

---

### 2. 补丁坟场（按历史标记密度排序）

计数方式：正则 `实测[零一二三四五六七八九十百〇0-9]+` + `v1.\d+.\d+` 的出现次数。

| 合计 | 实测标记 | 版本标记 | 文件 |
|---|---|---|---|
| 814 | 397 | 417 | `config/MaidSmartConfig.java` |
| 572 | 247 | 325 | `config/PromaidConfigScreen.java` |
| 545 | 164 | 381 | `combat/SelfPreservationBehavior.java` |
| 412 | 98 | 314 | `task/MaidWoodBehavior.java` |
| 381 | 82 | 299 | `task/MaidMineBehavior.java` |
| 339 | 222 | 117 | `combat/MaidFlightCombatBehavior.java` |
| 275 | 29 | 246 | `build/MaidBuildBehavior.java` |
| 242 | 95 | 147 | `combat/MaidAidOwnerBehavior.java` |
| 221 | 147 | 74 | `follow/MaidChunkLoadManager.java` |
| 202 | 16 | 186 | `build/BlueprintBookScreen.java` |
| 168 | 159 | 9 | `combat/MaidMountCompat.java` |
| 130 | 116 | 14 | `combat/MaidBroomDrive.java` |

**这意味着什么**：注释里保留的是「上一版哪里错了、这一版怎么改的」，而不是「这段代码现在做什么」。
对维护者来说，要读懂一段逻辑必须先读它的历史 —— 这正是 `MaidMountCompat` 从 500 行长到 4746 行的方式。

**建议**：把「为什么改」写进轮次记录（`rounds/RNNN-*.md`），把「现在做什么」留在代码里。这是可以纯做减法的一次清理。

---

### 3. 死代码 / 一次性残留（有明确自述）

| 文件 | 证据 | 判定 |
|---|---|---|
| `tool/MaidProbe.java` | 自述「临时探针…查清问题后整类删掉即可」 | **已删**（零风险一档）|
| `combat/FlightTargetProbe.java` | 自述「一次性排查工具，验证完可删」 | **已删**（零风险一档）|
| `soul/SoulBindingService.java` | 自述灵魂核心功能已移除，只剩路由兼容 | 待确认是否还有旧存档用户 |
| `guide/GuideScreen.java` | `VIEW_SETTINGS`/`VIEW_VOICE` 标注 `@Deprecated`「已不可达，保留仅为回滚方便」 | **已删**（零风险一档）|
| `build/BuiltinHouses.java`（1385 行） | `BlueprintCatalog.BUILT_IN_NAMES` 空块 + 注释「内置预设已全部移除」 | **休眠代码**，只可硬编码 id 触达 |
| `schedule/ScheduleNetworking.java` | 拆包后留下 10 条空段注释 | **已清**（零风险一档；类本身仍在用，只删注释）|
| `fishing/PlayerWaterLog.java` | 纯诊断日志助手，住在主源码里 | 可移入 tools |
| `box/CompressionBoxCheck.java`（702 行） | FakePlayer 自测住在主源码里 | 可移到测试源集 |
| `task/MaidStrollCheck.java`（337 行） | 仅为一条速度投诉的调试命令服务 | 可移到 tools |

**"名字从未被别处引用"的类共 30 个**，其中 26 个是 Mixin（Mixin 由 `mixins.promaid.json` 按名注册，
不靠 Java 引用，**属于正常，不是死代码**）。真正需要复核的非 Mixin 类只有 4 个：

- `combat/FarmlandGuard.java`（24 行）
- `combat/PetImmunityGuard.java`（201 行，职责与 `FriendlyFireGuard` 重叠）
- `CreativeTabHandler.java`（48 行，经事件/注册表生效）
- `flight/MaidFreeFlightKeysClient.java` / `goety/MaidGoetyKeysClient.java`（客户端按键注册）

---

### 4. 重复逻辑（可合并项）

#### 4.1 两树镜像（最大的重复）

- 同路径文件 **390 个**，其中 **224 个行数已经不一致**（Δ 最大 174 行）。
- 不一致不代表功能不同 —— 大多只是注释/换行差异；但 **Δ 越大的文件越危险**，
  因为你无法再从"对照另一棵树"推断当前树是否正确。

| Δ | NeoForge | Forge | 文件 |
|---|---|---|---|
| 174 | 1946 | 1772 | `combat/GunnerTetherManager.java` |
| 141 | 1402 | 1261 | `flight/MaidFreeFlightController.java` |
| 129 | 2270 | 2399 | `combat/RideBindManager.java` |
| 91 | 433 | 524 | `combat/MaidSelfPropelledWings.java` |
| 84 | 770 | 854 | `build/BlueprintBookNetworking.java` |

**根因**：没有共享源集的机制。两树用不同的映射名（SRG vs Mojmap），但这**不能**用"注解处理器/模板"简单解决。
**建议方向**（按成本从低到高）：
1. 先把两树中**逐字节相同**的文件识别出来，用脚本校验它们真的相同（回归护栏）；
2. 再把「纯逻辑、无映射依赖」的部分（如 `BlueprintStepMath`、`BlueprintLegacyIds`、`AiMemorySkipList`）
   抽成共享源目录，两树各自 include；
3. 最后才考虑统一映射层（成本最高，收益也最大）。

#### 4.2 同树内的重复

| 重复 | 证据 |
|---|---|
| `MaidWoodBehavior` ≈ `MaidMineBehavior` | 自述「完整克隆挖矿」；两文件合计 **5796 行** |
| `GunnerTetherManager` ≈ `RideBindManager` | 都在做绑定链/座位偏移/换座/清理；合计 **4216 行** |
| `MaidFlightRecall` ≈ `MaidBroomRecall` | 同构的"半径 N 格找不到主人就回传" |
| `MaidSeatNetworking` ≈ `GunnerTetherNetworking` | 两份几乎同构的 S2C 单包骨架 |
| `BlueprintMaterials` 内 5+ 个近同构变体 | 缺口/玩家缺口/合并持有/计数/计数玩家 |
| `SchedulePacketsPlan` ≈ `SchedulePacketsMaid` | 共享同一段 21 行 import 头，编解码逐字复制 |
| `BlueprintBookBuildPackets`（919）+ `EntityPackets`（735） | ~25 个包类塞在两个 god-file，各自重复 `type()` 样板 |
| `PromaidConfigScreen` 内 5 组列表+条目对 | BuildBlack/Alt/Cook/Water/Food/Minable 各写一遍增删改重建 |
| 各兼容层 | `GunCompat`/`MaidSpellCompat`/`SlashBladeCompat`/`TwilightFanKit`/`CuriosElytraCompat` 各自重写「反射失败→兜底」 |

#### 4.3 配置迁移残渣

`ProMaidMod.runConfigMigration()` 约 320 行，是一条 `if (值 == 旧默认) 值 = 新默认` 的链，
外加一个 12 项「默认值修复」批次（专门用来撤销一次改错的默认值），
以及同一个键**连续两次迁移**（扫帚爬升 10 → 15 → 12，各带一个一次性布尔标记）。

这 22 个 `*_MIGRATED` 标记**被当成用户可见设置留在了配置里**。
**建议**：迁移代码本身可以留（旧存档要能用），但标记不该暴露给用户 —— 应移出 `ModConfigSpec`。

---

### 5. 结构隐患（不是屎山，但会持续咬人）

| 隐患 | 说明 |
|---|---|
| **`ProMaidExtension` 单点装配** | 779 行、几十条注册；任何新功能都要碰它 → 改动冲突高发 |
| **大量静态可变状态** | `SelfPreservationBehavior` 的 `LAST_ATTACKERS`/`SUFFOCATE_DAMAGE`/`COMBAT_PLACED`、`MaidBuildBehavior` 约 15 个静态 Map、`MaidFreeFlightController` 的 STATE/YAW/RETREAT …… 全靠手工 `forget`/`clearAll`，**漏一处就泄漏**（注释里多次记录过因此崩服） |
| **`BombPose` 被 11 个不相关子系统引用** | 隐藏的全局耦合点：改动它要审 11 个调用方 |
| **命令树无中央注册表** | 5 个类各自注册 `/maid_smart` 同一个字面量 |
| **`GuideContent.CFG_LINK_RULES` 靠中文标题字符串匹配** | 手册跳转与配置面板的**行标题**强耦合，改一个标题就断链，且无编译期保护 |
| **`BlueprintLib` 半成品拆分** | 73 个一行转发 + 14 个类带同一段用不上的 import；拆分只做了一半 |
| **`MaidChunkLoadManager`** | 票据/跨维跟随/召唤队列/可见性四件事仍在一个类里 |

---

### 6. 建议的清理顺序（按投入产出比）

| 阶段 | 动作 | 风险 | 收益 |
|---|---|---|---|
| **① 零风险** ✅已完成 | 删自述「可删」的 `MaidProbe`、`FlightTargetProbe`；`GuideScreen` 死视图；`ScheduleNetworking` 空段注释 | 极低（有编译校验） | GuideScreen 541→532 / 531→522 行；两树 javac 0 error；两 jar mixin 自检 186 PASS |
| **② 低风险** | 把注释里的「历史/为什么」搬进轮次记录，代码只留「现在做什么」 | 低（纯注释） | 大幅降低阅读成本 |
| **③ 中风险** | `*_MIGRATED` 移出用户配置；诊断类（`PlayerWaterLog`、`CompressionBoxCheck`、`MaidStrollCheck`）移出主源码 | 中 | 配置表干净、主源码只留产品代码 |
| **④ 中风险** | 抽 `MaidWorkBehavior` 基类，让挖矿/伐木共用扫描-接近-连锁-回收 | 中（有编译+回环测试） | 砍掉约 2500 行重复 |
| **⑤ 高风险** | 拆 `PromaidConfigScreen`（按 Section 拆成独立面板类） | 高（需实机验收 UI） | 解除 UI 改动阻塞 |
| **⑥ 最高** | 两树共享源目录（先纯逻辑，后映射层） | 最高 | 一次改动只写一遍 |

> 编号回归 harness（原 27 个 `tools/test/test_*.py`）已随仓库大清理移除，只保留通用回归骨架
> `tools/test/test_server.py`（启动到 `Done (`）与 `test_maid_load.py`（女仆大脑装载），
> 配合 `verify_jar_classes.py` 作为 ④⑤⑥ 动刀前的编译 / 装载校验。

#### 6.1 ④⑤⑥ 实施蓝本（2026-10-12 测绘）

**④ 抽 `MaidWorkBehavior` 基类（挖矿/伐木）**
- 测绘确认：`task/MaidMineBehavior`(2952) 与 `task/MaidWoodBehavior`(2921) 是**系统性克隆**——公开/私有方法集一一对应，绝大多数只是 Ore↔Wood 词根替换。
- 参数化点（子类差异）：`isOre/isWood`(+`isWoodState`) 判据；`OreCache/WoodCache` + `OreScanState/WoodScanState` 内部类；`load/ensure/reload{Builtin}`；`findOre/findWood`、`pickFromCache`；伐木独有的 `clearTreeTop`/`burstNearbyLeaves`/`isSaplingItem`/`firstWoodAbove`/`walkTargetFor`；提示文案 `hintNoPickaxe/hintNoAxe`；`requiredTool`/`canHarvest` 的物品集；配置键前缀（`mining.*` vs `wood.*`）。
- 做法：抽 `abstract class MaidWorkBehavior extends Behavior<EntityMaid>`，把**无任务特异依赖**的成员上提 `protected`。**两树各做一份**（映射名不同，不能跨树共享源码）。
  - **已落刀的安全子集（2026-10-12）**：先只上提「两文件逐字相同」的静态数据表与纯工具，共 7 个成员 / 每文件约 199 行：`JUNK_STONES`、`OPEN_BREAKABLE`(+static 块)、`BLOCK_CN`(+static 块)、`distSqToBlock`、`blockCnName`、`isSoft`、`canHarvest`。基类构造 `super(Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE)` 与子类原写法逐字一致（无限时长），行为完全透明。两树 `compile_*` `EXITCODE=0`；forge↔neo 基类 diff 全部是 SRG↔Mojmap / 注册表 API 替换（`m_123341_()`↔`getX()`、`m_41619_()`↔`isEmpty()`、`m_8096_()`↔`isCorrectToolForDrops(...)`、`ForgeRegistries.BLOCKS`↔`BuiltInRegistries.BLOCK`），无一处逻辑差异。
  - **注意**：`reachSq` **不可上提**（挖矿读 `MINE_REACH`、伐木读 `WOOD_REACH`，同名不同源）。其余候选方法（`findBlockingBlock / takeBuildBlock / pillarUpStep / …`）因两树**行数/写法均有差异**（非逐字克隆，含任务特异分支）暂不上提——需逐方法人工核对语义后再动，属下一步。
- 门槛：DEBT 标注**中风险、需回环测试**。安全子集已满足 `compile_promaid/neo` `EXITCODE=0` + 全套护栏 `FAIL 0`；**完整 ~2500 行驱动逻辑合并仍停在测绘**，需开发者「部署」开闸做实机挖矿/伐木回归。

**⑤ 拆 `PromaidConfigScreen`（7659 行）**
- 现状：单类含 首页 / 大类页 / 参数页 + 5 组「列表 + 条目」对（BuildBlack/Alt/Cook/Water/Food/Minable 各写一遍增删改重建）+ `client/MaidConfigRowLayout` 辅助。
- 做法：按 Section 拆成独立面板类（每 Section 一个 `*Section`，共用基类/接口），`PromaidConfigScreen` 只做装配与分页。
- 门槛：DEBT 标注**高风险、需实机 UI 验收**——拆完必须实机点一遍每个 Section（行高 44 / 翻页 / 手册跳转 / 控件状态）。**本轮仅测绘，未动刀。**

**⑥ 两树共享源目录**
- 第一步（**本轮已做**）：`tools/verify/check_tree_mirror.py`（只读审计，不设门槛）。现状（④ 落刀后复测）：同路径 `.java` **460** 个，逐字节相同 **47** / 有差异 413（行数一致 160、行数不同 253——多为注释里的映射名引用，合法；行数差 Top 见脚本输出，最大 `GunnerTetherManager` Δ127）。
- 第二步（未做）：把**纯逻辑、无映射依赖**的文件移入共享源目录（如 `src_shared/`），两侧 `build.gradle` 的 `srcDir` 与 `tools/build/gen_compile*.py` 一并 include。候选先取「逐字节相同」那 47 个（先逐个确认真无映射依赖；已知 `memory/AiMemorySkipList`、`tool/NaturalBlocks`、`tool/StateTables` 相同，而 `build/BlueprintStepMath` 因注释映射名而 DIFF）。**需 gradle 构建验证，本轮未动。**

> ④ 的**安全子集**已落刀（编译 + 护栏可验，见上）；⑤⑥ 仍停在**测绘**：两者都要求实机 UI / 真构建验证，而仓库规矩是「回归只在开发者开闸（『部署』）时跑」——故不在无人验收的情形下盲改。蓝本给出下一步的机械执行清单。

---

### 7. 复现本报告

```
python tools/arch/debt_report.py        # 重新计算本文件里的全部数字
python tools/arch/gen_arch.py           # 结构数据（L1/L2 门禁）
python tools/arch/render_arch.py        # 交互式架构图
python tools/arch/finalize_arch.py      # 冻结哈希
```

---

## 4. 已确认不修（留痕，避免重复讨论）

| ID | 内容 | 理由 |
|---|---|---|
| — | 功能冗余类（落地水 / 压缩盒 / 中键设工作位置） | 属**功能取舍**不是 bug → 记在 `TECH_DEBT.md` TD-04 做重复性裁决 |
