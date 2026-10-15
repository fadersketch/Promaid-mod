# 配置项清单 / Switch Inventory（自动生成，勿手改）

> 生成：`python tools/verify/gen_switch_inventory.py --write`　校验是否过期：`check_workflow.py`（会与重新生成的结果比对）
> 用途（`WORKFLOW.md` §3.1③）：**动手改一个行为之前**，先用 §2 主题索引看这个行为有几处开关、谁管谁、优先级如何；新加开关必须进这份清单。

## 1. 规模

| 树 | 配置项 | 布尔 | 节数 |
|---|---|---|---|
| promaid_src（Forge 1.20.1） | 591 | 261 | 24 |
| promaid_src_neo（NeoForge 1.21.1） | 595 | 265 | 24 |

同一路径被定义两次的（TOML 后者覆盖前者，见 `DEBT.md` TD-08）：（无）
Neo 树比 Forge 树多出的配置项（14 个）：`airRaid.smashMinFall`、`combat.maceCooldown`、`combat.maceSmash`、`combat.maceTriggerRange`、`combat.maceWindCharge`、`misc.freeFlight`、`misc.freeFlightGravity`、`misc.freeFlightIdle`、`misc.freeFlightIdleSeconds`、`misc.freeFlightNearDist`、`misc.freeFlightTravel`、`misc.freeFlightTravelDist`、`misc.glideElytraAnimation`、`tether.maceSmash`

## 2. 主题索引（按行为聚合——改行为先看这里）

### 搭路 / 垫脚方块　（16 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `mine.pillarCooldown` | num | 4 | 搭方块冷却（tick，垫脚下/搭路节奏） |
| `wood.pillarCooldown` | num | 4 | 搭方块冷却（tick，垫脚下/搭路节奏） |
| `bridge.enabled` | bool | true | 搭路（默认开） |
| `bridge.maxDist` | num | 32 | 搭路触发距离（格，默认 32） |
| `bridge.airMaxDist` | num | 50 | 空中搭桥触发距离（格，默认 50） |
| `bridge.minDy` | num | 5 | 搭路最小高差（格，默认 3） |
| `bridge.minRadius` | num | 4 | 搭路最小球面半径（格，默认 3） |
| `bridge.startHDist` | num | 8.0 | 平桥启动水平距离（格，默认 6） |
| `bridge.threatDist` | num | 8 | 搭路威胁半径（格，默认 8） |
| `bridge.stepCooldown` | num | 4 | 搭路节奏（tick/块，默认 4） |
| `bridge.placedLifetime` | num | 3 | 搭路方块清理时间（秒，默认 3） |
| `bridge.reclaimToMaid` | bool | true | 搭路方块回收进背包（默认开，全局开关——搭路/挖矿/伐木/战斗搭方块一切女仆搭的垫脚方块都适用） |
| `bridge.buildOnlyNatural` | bool | true | 搭方块只用原版天然方块（默认开） |
| `bridge.buildBlacklist` | list | java.util.List.of( | 搭方块禁用名单（完整注册名，逗号分隔；默认空） |
| `bridge.buildWhitelist` | list | java.util.List.of( | 搭方块放宽名单（完整注册名，逗号分隔；默认空） |
| `bridge.combatPlacedLifetime` | num | 10 | 战斗搭方块清理时间（秒，默认 10） |

### 拾取 / 掉落物　（9 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `accessory.daYunDropItems` | bool | true | 「大运」破坏后掉落（默认开） |
| `mine.pillarGuard` | bool | true | 搭方块防掉落（潜行效果，速度不变） |
| `mine.skipReportInterval` | num | 600 | 跳过矿/捡不到掉落播报间隔（tick，防刷屏） |
| `mine.autoCollect` | bool | false | 自动收集（挖掘掉落物直接进女仆背包，不进世界 |
| `wood.pillarGuard` | bool | true | 搭方块防掉落（潜行效果，速度不变） |
| `wood.autoCollect` | bool | false | 自动收集（砍伐掉落物直接进女仆背包，不进世界） |
| `misc.pickupPriority` | bool | false | 挖矿中禁止拾取（捡掉落物最低优先级 |
| `misc.pickupValueReorder` | bool | true | 拾取按价值重排（默认开） |
| `misc.autoCollect` | bool | false | 农场掉落物立即拾取（默认关） |

### 传送 / 召回　（15 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `memory.recallGate` | bool | true | 记忆召回闸门（移植自 MemoryRecallGate） |
| `memory.recallMinInterval` | num | 45 | 召回最小间隔（秒） |
| `memory.recallMinMessages` | num | 2 | 触发重召回所需的最少新增消息数 |
| `memory.recallCacheTtl` | num | 120 | 召回缓存有效期（秒） |
| `memory.graphEnable` | bool | true | 关系图多跳召回（移植自 MemoryGraphStore） |
| `memory.temporalEnable` | bool | true | 时间路召回（移植自 MinecraftTimeQuery） |
| `combat.masterDeathTeleport` | bool | true | 主人死亡强制传送（无视战斗/距离） |
| `combat.teleportCooldown` | num | 600 | 传送回家成功冷却（tick，默认 600 = 30 秒 |
| `combat.teleportSafeRadius` | num | 5.0 | 传送安全判定半径（格，主人身边此半径内无可见怪物才传送回主人，默认 5） |
| `broom.recallDistance` | num | 100 | 扫帚牵引绳（格，默认 100，0=关闭） |
| `teleportExemptSitting` | bool | true | 坐下的女仆不被自保传送拉走（默认开） |
| `misc.dimensionFollow` | bool | true | 跟随女仆跨维度传送（主人换维度后，跟随模式女仆自动传送到主人身边 |
| `misc.maidChunkLoad` | bool | true | 女仆区块强制加载（所有有主女仆所在区块持续保持实体 ticking，随时可传送/召回/救援 |
| `misc.maidTeleportRebuild` | bool | true | 传送后客户端重建（默认开） |
| `misc.workTeleportHold` | bool | true | 干活时不被召回（默认开） |

### 排班 / 作息　（14 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `aitools.readiness` | bool | true | smart_readiness 工具（只读自检——空袭三件套/弹药/驻守/排班/工作锚点/血量，让模型"先查后做"） |
| `combat.forceAllSchedule` | bool | true | 参战时把作息临时切「全天」（默认开） |
| `misc.scheduleBubbleEnabled` | bool | true | 排班贴身气泡（默认开） |
| `misc.scheduleBubbleDist` | num | 3.5 | 排班贴身气泡触发距离（格，默认 3.5） |
| `misc.scheduleEnabled` | bool | true | 排班表系统（默认开） |
| `misc.scheduleRestoreGrace` | num | 60 | 战斗还原后排班宽限（tick，默认 60=3 秒） |
| `misc.scheduleAvailabilityCheck` | bool | true | 排班切换前完整可用性检测（默认开） |
| `misc.scheduleReverseWindowTicks` | num | 200 | 排班反向切换窗口（tick，默认 200=10 秒） |
| `misc.scheduleReverseThreshold` | num | 2 | 排班反向切换阈值（默认 2） |
| `misc.scheduleReverseCooldownTicks` | num | 200 | 排班反向切换冷却（tick，默认 200=10 秒） |
| `misc.scheduleMinHoldTicks` | num | 60 | 排班最短持有期（tick，默认 60=3 秒，借鉴 TLM-Sincerely MINIMUM_TASK_HOLD_ |
| `misc.scheduleForceBrainRefresh` | bool | true | 排班切段后大脑自愈（默认开，借鉴 TLM-Sincerely FORCE_BRAIN_REFRESH_ON_STUC |
| `misc.scheduleActivityRange` | num | 12 | 排班活动半径（格，默认 12） |
| `misc.scheduleRangeFloor` | bool | true | 排班活动半径下限生效（默认开） |

### 战斗 / 自保　（135 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `combat.selfPreserve` | bool | true | 自保行为（低血逃跑/搭高/治疗） |
| `combat.enterRatio` | num | 0.3 | 自保触发血量（0-1） |
| `combat.exitRatio` | num | 0.7 | 自保绝对解除血量（0-1，默认 0.7 |
| `combat.threatDistance` | num | 12 | 威胁感知距离 |
| `combat.waterClutch` | bool | true | 落地水（有水桶+坠落自动放水缓冲） |
| `combat.waterFallDistance` | num | 4.0 | 落地水触发高度（格，默认 4） |
| `combat.waterBucketLava` | bool | true | 岩浆逃生放水（垫高后周围无水源且包里有水桶 → 在自己垫的方块上放水灭火，1 秒后收回 |
| `combat.masterDeathTeleport` | bool | true | 主人死亡强制传送（无视战斗/距离） |
| `combat.pearlCooldown` | num | 100 | 末影珍珠逃生冷却（tick，20=1 秒 |
| `combat.pearlRatio` | num | 0.3 | 末影珍珠逃生触发血量（0-1，低于此值且威胁贴身才扔） |
| `combat.pearlDist` | num | 8.0 | 末影珍珠逃生威胁距离（威胁小于此格数才扔珍珠） |
| `combat.safeReturnRatio` | num | 0.7 | 安全回归血量（0-1，默认 0.7 |
| `combat.closeDistance` | num | 4.0 | 贴身距离（格，低于此值判定被近身） |
| `combat.pillarMax` | num | 30 | 至多向上搭多少个方块（格） |
| `combat.pillarSafeHeight` | num | 5 | 搭高安全高度（格，默认 5） |
| `combat.pillarAboveEnemy` | num | 30 | 搭高时高于敌方的格数（默认 30） |
| `combat.healCooldown` | num | 40 | 治疗食物冷却（tick） |
| `combat.threatScan` | num | 5 | 威胁扫描间隔（tick） |
| `combat.fleeSpeed` | num | 1.0 | 走位速度倍率（自保小幅走位拉开身位的移动加成，1.0=正常、不额外加速） |
| `combat.fireProtectBauble` | bool | true | 火焰保护饰品识别（默认开） |
| `combat.drownProtectBauble` | bool | true | 溺水保护饰品识别（默认开） |
| `combat.fleeWithSaveItem` | bool | false | 保命物品下允许惊慌（默认关） |
| `combat.soulSpellEnable` | bool | true | 致死伤害自动回魂符（默认开） |
| `combat.soulSpellLethalGuard` | bool | true | 致死伤害保护（默认开） |
| `combat.soulSpellOwnerRadius` | num | 24.0 | 主人收符半径（格，默认 24） |
| `combat.soulSpellCooldownSeconds` | num | 60 | 收符冷却（秒，默认 60） |
| `combat.autoResurrectEnable` | bool | true | 女仆自动复活（默认开） |
| `combat.autoResurrectDelaySeconds` | num | 60 | 复活延迟（秒，默认 60） |
| `combat.autoResurrectHealthRatio` | num | 1.0 | 复活血量比（默认 1.0 = 满血） |
| `combat.autoResurrectTiming` | num | 0 | 复活时机（0 = 延迟秒后复活，用上面的「复活延迟（秒）」 |
| `combat.stuckWindow` | num | 20 | 卡住判定窗口（tick） |
| `combat.stuckThreshold` | num | 0.3 | 卡住位移阈值（格） |
| `combat.threatGoneExit` | num | 400 | 威胁消失退出时长（tick，400=20 秒 |
| `combat.teleportCooldown` | num | 600 | 传送回家成功冷却（tick，默认 600 = 30 秒 |
| `combat.teleportSafeRadius` | num | 5.0 | 传送安全判定半径（格，主人身边此半径内无可见怪物才传送回主人，默认 5） |
| `combat.potionCooldown` | num | 40 | 药水尝试间隔（tick） |
| `combat.alertCooldown` | num | 60 | 头顶警示粒子间隔（tick） |
| `combat.announceCooldown` | num | 200 | 策略播报间隔（tick，防刷屏） |
| `combat.waterHold` | num | 5 | 落地水保持时长（tick） |
| `combat.waterLandingScan` | num | 2 | 落地水下探格数（提前放水检测） |
| `combat.snowClutch` | bool | true | 落地雪（细雪桶版落地水，默认开） |
| `combat.snowFallDistance` | num | 4.0 | 落地雪触发高度（格，默认 4） |
| `combat.snowHold` | num | 5 | 落地雪保持时长（tick，默认 5） |
| `combat.snowLandingScan` | num | 2 | 落地雪下探格数（默认 2） |
| `combat.tactics` | bool | true | 单兵作战战术（绕圈走位/打退拉扯/距离控制/时机举盾——PVP 式战斗） |
| `combat.tacticsMelee` | bool | true | 近战战术（贴脸绕圈、打一刀退一步、跳劈接近） |
| `combat.tacticsRanged` | bool | true | 远程战术（保持理想射程、横移绕圈风筝） |
| `combat.tacticsShield` | bool | true | 时机举盾（攻击冷却间隙举盾格挡、攻防交替 |
| `combat.tacticsOrbitRadius` | num | 2.2 | 绕圈半径（格） |
| `combat.tacticsKiteRange` | num | 0.6 | 远程理想射程倍率（0.6 = 保持在最大射程 60% 的距离放风筝） |
| `combat.tacticsMeleeKite` | bool | true | 近战贴脸后退（敌人贴进 2 格内主动后退拉开距离，女仆手长 3 格仍能挥砍） |
| `combat.aidOwnerEnable` | bool | true | 自动投喂/治疗主人（被动 |
| `combat.aidMaidMutual` | bool | true | 女仆之间互相支援（默认开） |
| `combat.aidFriendlyUnits` | bool | true | 支援其他友方单位（默认开） |
| `combat.aidUndeadUnits` | bool | true | 支援亡灵单位（默认开） |
| `combat.aidSelfUse` | bool | true | 自用也走支援表（默认开） |
| `combat.aidFoodThreshold` | num | 12 | 投喂触发饱食度（4-20 |
| `combat.aidFoodBlacklist` | list | java.util.List.of(
                        "minecraft:rotten_flesh" | 投喂食物黑名单（完整注册名，逗号分隔；留空 = 只按"能吃"判定，所有食物可喂） |
| `combat.aidSupportCategory` | list | java.util.List.of( | 通用支援品分类表（条目 = 「键=分类」，逗号分隔；留空 = 所有支援品按效果自动推断） |
| `combat.aidSupportCategoryUndead` | list | java.util.List.of( | 亡灵支援品分类表（条目 = 「键=分类」，逗号分隔；留空 = 按效果自动推断） |
| `combat.aidThirstThreshold` | num | 15 | 投喂触发口渴度（4-20 |
| `combat.aidDrinkMinPurity` | num | 2 | 喂水最低水质（0-3，默认 2=可接受的） |
| `combat.aidDrinkWhitelist` | list | java.util.List.of(
                            "minecraft:potion" | 喂水白名单（完整注册名，逗号分隔） |
| `combat.aidHealthThreshold` | num | 0.3 | 治疗触发血量（0.1-1 |
| `combat.torchPlacerEnable` | bool | true | 被动插火把（主人周围黑暗自动插火把照明） |
| `combat.maidFireGuard` | bool | true | 女仆着火不传主人（默认开） |
| `combat.torchDarkThreshold` | num | 7 | 插火把亮度阈值（0-15 |
| `combat.shieldShareEnable` | bool | true | 共享盾牌（主人盾牌耐久低/空时，从自己背包取盾给主人——不动自己副手） |
| `combat.totemShareEnable` | bool | true | 共享不死图腾（主人致命伤时，女仆背包/饰品栏的不死图腾优先救主人，特效同原版） |
| `combat.playerDamageMode` | num | 4 | 玩家对女仆伤害模式（0=TLM原版压制÷5封顶2点、1=玩家伤害完全免疫、2=玩家伤害无限制、3=玩家伤害有上限（比 |
| `combat.playerDamageMaidCap` | num | 0.1 | 玩家伤害上限比例（0-1 |
| `combat.autoSwitch` | bool | true | 主动切换战斗模式（主人被敌对生物攻击时，附近女仆无论什么任务都立即切战斗保护主人 |
| `combat.flightMode` | bool | true | 空袭·总开关（飞行作战）——鞘翅 + 任意近战武器 + 烟花火箭 |
| `combat.flightNoWallDamage` | bool | true | 飞行作战免疫鞘翅撞击伤害（默认开） |
| `combat.flightNoFallDamage` | bool | true | 空袭免疫摔落伤害（默认开） |
| `combat.flightDangerAvoid` | bool | true | 飞行危险环境避让（默认开） |
| `combat.heatEscape` | bool | true | 烫伤脱困（默认开） |
| `combat.wingRender` | bool | true | 鞘翅外观（默认开） |
| `combat.wingYsmYield` | bool | true | 鞘翅外观·YSM 让位（默认开） |
| `combat.riptideDash` | bool | true | 激流三叉戟旋转冲击（默认开） |
| `combat.riptideFlightScale` | num | 1.0 | 激流三叉戟·推进剂力度倍数（默认 1.0 = 不打折，范围 0.1~2.0） |
| `combat.selfWings` | bool | true | 自推鞘翅（默认开） |
| `combat.selfWingsItems` | list | List.of("locusazzurro_icaruswings:ikaros_wings" | 自推鞘翅·资格物品表（默认见上） |
| `combat.selfWingsScale` | num | 1.0 | 自推鞘翅·推力倍数（默认 1.0，范围 0.2~3.0） |
| `combat.crossbowPlainFirework` | bool | false | 弩可用普通烟花当弹药（默认关） |
| `combat.flightRangedPush` | bool | true | 远程空袭近身弹开（默认开） |
| `combat.flightRecallDistance` | num | 100 | 空袭牵引绳（格，默认 100，0=关闭） |
| `combat.flightSpellCast` | bool | true | 空袭顺带施法（默认开，需装《车万女仆 |
| `combat.flightSpellCastInterval` | num | 20 | 空袭施法间隔（tick，默认 20 = 1 秒） |
| `combat.flightDashClimb` | bool | true | 空袭·位移法术【提供高度】（默认开，需装《车万女仆 |
| `combat.flightDashBoost` | bool | true | 空袭·位移法术【提供速度】（默认开，需装《车万女仆 |
| `combat.flightDashClimbSpells` | list | List.of("irons_spellbooks:ascension" | 【提供高度】的法术表（填完整法术 id） |
| `combat.flightDashBoostSpells` | list | List.of("irons_spellbooks:burning_dash" | 【提供速度】的法术表（填完整法术 id） |
| `combat.flightDashInterval` | num | 40 | 位移法术间隔（tick，默认 40 = 2 秒） |
| `combat.flightDashBoostRespectCooldown` | bool | true | 位移法术·【提供速度】尊重法术自身冷却（默认开） |
| `combat.flightSpellCastRange` | num | 24.0 | 空袭施法距离（格，默认 24） |
| `combat.friendlyWindImmune` | bool | true | 友军风免（默认开） |
| `combat.autoSwitchRadius` | num | 16 | 主动切战斗响应半径（格） |
| `combat.autoSwitchModWeight` | num | 2.0 | 模组武器权重（选战斗任务时的加权随机权重，默认 2.0） |
| `combat.autoSwitchVanillaWeight` | num | 1.0 | 原版武器权重（默认 1.0） |
| `combat.autoSwitchAllowModTasks` | bool | true | 模组任务参与自主切换（默认开） |
| `combat.taskModes` | list | java.util.List.of( | 战斗模式分类表（默认空 = 全部按内置规则参与） |
| `combat.vanillaYieldToMod` | bool | true | 模组任务优先让位（默认开） |
| `combat.spellGearIgnore` | list | java.util.List.of("slashblade" | 法术装备忽略表（默认 slashblade, youkaishomecoming） |
| `combat.prefMeleeWeight` | num | 3 | 近战偏好权重（默认 3） |
| `combat.prefRangedWeight` | num | 1 | 远程偏好权重（默认 1） |
| `combat.tacticHoldTicks` | num | 40 | 战中换战术最短持有（tick，默认 40=2 秒） |
| `combat.reverseWindowTicks` | num | 100 | 战中反向切换窗口（tick，默认 100=5 秒） |
| `combat.reverseCooldownTicks` | num | 200 | 战中反向切换冷却（tick，默认 200=10 秒） |
| `combat.unarmedSkip` | bool | true | 空手不参战（默认开） |
| `combat.autoSwitchRestore` | bool | true | 战斗结束自动还原（威胁消失一段时间后切回战斗前的原任务 |
| `combat.autoSwitchRestoreDelay` | num | 200 | 战斗结束还原延迟（tick，200=10 秒） |
| `combat.forceAllSchedule` | bool | true | 参战时把作息临时切「全天」（默认开） |
| `combat.autoSwitchRestoreThreatDist` | num | 8 | 还原判定威胁半径（格，默认 8） |
| `combat.autoSwitchStaleSeconds` | num | 60 | 战斗僵局逃逸（秒，默认 60） |
| `combat.autoSwitchThreatExpandSeconds` | num | 10 | 动态威胁圈（秒，默认 10） |
| `combat.combatWorkRange` | num | 15 | 战斗时临时扩圈（格，默认 15，0 = 关闭） |
| `combat.assistRadius` | num | 16 | 援护半径（格，默认 16，0 = 关闭；借自别人改过的 TLM 1.5.3） |
| `combat.takeOverMovement` | bool | true | 接管走位（默认开） |
| `combat.sweepAlways` | bool | true | 平A必横扫（默认开） |
| `combat.attackThoseAttackingUs` | bool | true | 反击类型判不出的攻击者（默认开） |
| `combat.friendlyFireGuard` | bool | true | 主人/友军免伤总闸（默认开） |
| `combat.petImmunity` | bool | true | 玩家宠物免伤（默认开） |
| `combat.buildShield` | bool | true | 建造护盾（默认开） |
| `combat.hoeNotWeapon` | bool | true | 锄头不算武器（默认开） |
| `broom.range` | num | 8.0 | 战斗盘旋的站立距离（格，默认 8） |
| `teleportExemptSitting` | bool | true | 坐下的女仆不被自保传送拉走（默认开） |
| `bombing.melee` | bool | true | 战斗模式轰炸（默认开） |
| `bombing.tnt` | bool | true | 战斗模式的 TNT 投掷（默认开） |
| `bridge.reclaimToMaid` | bool | true | 搭路方块回收进背包（默认开，全局开关——搭路/挖矿/伐木/战斗搭方块一切女仆搭的垫脚方块都适用） |
| `bridge.combatPlacedLifetime` | num | 10 | 战斗搭方块清理时间（秒，默认 10） |
| `misc.nonCombatSpeedCap` | bool | true | 非战斗模式下移速硬上限（默认开） |
| `misc.nonCombatMaxSpeed` | num | 0.56 | 非战斗移速上限（默认 0.56，范围 0.05~1.5） |
| `misc.scheduleRestoreGrace` | num | 60 | 战斗还原后排班宽限（tick，默认 60=3 秒） |
| `voice.systemEnabled` | bool | true | 系统消息朗读（感知/工作/自保等规则气泡也播放 TTS 语音） |

### 飞行 / 空袭　（93 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `accessory.daYunBgm` | num | 1 | 佩戴「大运」鞘翅飞行时播放的 BGM（默认 1，可选 1 或 2） |
| `aitools.switchTask` | bool | true | smart_switch_task 工具（切换任务/工作模式——TLM 原生 + 本模组任务全可切，支持空袭/挖矿/ |
| `aitools.airRaid` | bool | true | smart_air_raid 工具（起飞空袭/停止空袭——可指定近战或远程、锁定目标，并回报"缺不缺件"） |
| `aitools.readiness` | bool | true | smart_readiness 工具（只读自检——空袭三件套/弹药/驻守/排班/工作锚点/血量，让模型"先查后做"） |
| `combat.flightMode` | bool | true | 空袭·总开关（飞行作战）——鞘翅 + 任意近战武器 + 烟花火箭 |
| `combat.flightNoWallDamage` | bool | true | 飞行作战免疫鞘翅撞击伤害（默认开） |
| `combat.flightNoFallDamage` | bool | true | 空袭免疫摔落伤害（默认开） |
| `combat.flightDangerAvoid` | bool | true | 飞行危险环境避让（默认开） |
| `combat.wingRender` | bool | true | 鞘翅外观（默认开） |
| `combat.wingYsmYield` | bool | true | 鞘翅外观·YSM 让位（默认开） |
| `combat.selfWings` | bool | true | 自推鞘翅（默认开） |
| `combat.selfWingsItems` | list | List.of("locusazzurro_icaruswings:ikaros_wings" | 自推鞘翅·资格物品表（默认见上） |
| `combat.selfWingsScale` | num | 1.0 | 自推鞘翅·推力倍数（默认 1.0，范围 0.2~3.0） |
| `combat.flightRangedPush` | bool | true | 远程空袭近身弹开（默认开） |
| `combat.flightRecallDistance` | num | 100 | 空袭牵引绳（格，默认 100，0=关闭） |
| `combat.flightSpellCast` | bool | true | 空袭顺带施法（默认开，需装《车万女仆 |
| `combat.flightSpellCastInterval` | num | 20 | 空袭施法间隔（tick，默认 20 = 1 秒） |
| `combat.flightDashClimb` | bool | true | 空袭·位移法术【提供高度】（默认开，需装《车万女仆 |
| `combat.flightDashBoost` | bool | true | 空袭·位移法术【提供速度】（默认开，需装《车万女仆 |
| `combat.flightDashClimbSpells` | list | List.of("irons_spellbooks:ascension" | 【提供高度】的法术表（填完整法术 id） |
| `combat.flightDashBoostSpells` | list | List.of("irons_spellbooks:burning_dash" | 【提供速度】的法术表（填完整法术 id） |
| `combat.flightDashInterval` | num | 40 | 位移法术间隔（tick，默认 40 = 2 秒） |
| `combat.flightDashBoostRespectCooldown` | bool | true | 位移法术·【提供速度】尊重法术自身冷却（默认开） |
| `combat.flightSpellCastRange` | num | 24.0 | 空袭施法距离（格，默认 24） |
| `maneuver.airSeparation` | bool | true | 空袭·空中防叠罗汉（默认开） |
| `maneuver.airSeparationRadius` | num | 2.0 | 空袭·半径偏置强度（格，默认 2.0，0~8） |
| `tether.tetherLeashSteer` | bool | true | 二号位·悬挂时用武装拴绳操控方向（默认开，仅空袭档） |
| `ride.startupDelaySeconds` | num | 3.0 | 绑定飞行载具后的启动时间（秒，默认 3.0，0 = 关闭） |
| `ride.airCombat` | bool | true | 骑飞行载具时的悬停与低空盘旋（默认开） |
| `airRaid.launchTicksMelee` | num | 30 | 起飞段时长·近战（tick，默认 30 = 1.5 秒） |
| `airRaid.launchTicksRanged` | num | 20 | 起飞段时长·远程（tick，默认 20 = 1 秒） |
| `airRaid.launchClimbTanMelee` | num | 1.88 | 起飞仰角正切·近战（默认 1.88 ≈ 62°） |
| `airRaid.launchClimbTanRanged` | num | 1.0 | 起飞仰角正切·远程（默认 1.0 = 45°） |
| `airRaid.launchRange` | num | 20.0 | 地面重新起飞的最大水平距离（格，默认 20） |
| `airRaid.altitudeTolerance` | num | 10.0 | 占位高度容差（格，默认 10） |
| `airRaid.jumpTicks` | num | 3 | 起跳等待上限（tick，默认 3） |
| `airRaid.fireworkCooldown` | num | 30 | 烟花最小间隔（tick，默认 30 = 1.5 秒） |
| `airRaid.fanCooldown` | num | 20 | 羽扇最小间隔（tick，默认 20 = 1 秒，与原版 getUseDuration 一致） |
| `airRaid.smashRange` | num | 3.5 | 收翅俯冲触发距离（格，默认 3.5） |
| `airRaid.smashHitRange` | num | 4.0 | 猛击命中判定距离（格，默认 4.0） |
| `airRaid.forcedHitRadius` | num | 2.5 | 范围强制命中半径（格，默认 2.5） |
| `airRaid.smashMaxTicks` | num | 20 | 猛击段最长（tick，默认 20 = 1 秒） |
| `airRaid.meleeReach` | num | 3.0 | 地面近战触及距离（格，默认 3.0） |
| `airRaid.maxPitchUp` | num | 55.0 | 阶段二俯仰限幅·抬头（度，默认 55） |
| `airRaid.maxPitchDown` | num | 70.0 | 阶段二俯仰限幅·低头（度，默认 70） |
| `airRaid.orbitRadius` | num | 10.0 | 远程空袭的盘旋半径（格，默认 10） |
| `airRaid.orbitMax` | num | 12.0 | 锁敌之后离敌的最远距离（格，默认 12，2~64） |
| `airRaid.minStandoff` | num | 6.0 | 空袭·离敌最小距离（格，默认 6.0，0~32；0 = 关闭） |
| `airRaid.minAboveHeight` | num | 8.0 | 空袭·离敌最低高度（格，默认 8.0，0~64；0 = 关闭） |
| `airRaid.rangedHoldHeight` | num | 10.0 | 期望盘旋高度（目标上方格数，默认 10） |
| `airRaid.rangedHoldGain` | num | 5.0 | 高度修正增益（默认 5.0） |
| `airRaid.rangedHoldBias` | num | 1.0 | 高度修正偏置（格，默认 1.0） |
| `airRaid.rangedBoostDrop` | num | 0.5 | 掉高容差（格，默认 0.5） |
| `airRaid.rangedOrbitUpMax` | num | 45.0 | 盘旋抬头上限（度，默认 45） |
| `airRaid.rangedOrbitDownMax` | num | 35.0 | 盘旋低头上限（度，默认 35） |
| `airRaid.rangedBoostInterval` | num | 100 | 掉高补推间隔（tick，默认 100 = 5 秒） |
| `airRaid.rangedBoostAimTicks` | num | 10 | 补推抬头窗口（tick，默认 10 = 0.5 秒） |
| `airRaid.rangedBoostPitch` | num | -45.0 | 补推仰角（度，默认 -45 = 抬头 45°） |
| `airRaid.rangedShotCooldown` | num | 20 | 远程开火基础间隔（tick，默认 20 = 1 秒） |
| `airRaid.rangedAttackRange` | num | 24.0 | 远程射程（格，默认 24） |
| `airRaid.rangedFireRange` | num | 24.0 | 空袭·有效开火距离（格，默认 24，0 = 不限） |
| `airRaid.rangedPushRadius` | num | 3.0 | 弹开触发半径（格，默认 3） |
| `airRaid.rangedPushSpeed` | num | 0.55 | 弹开水平速度（默认 0.55） |
| `airRaid.rangedPushUp` | num | 0.25 | 弹开抬升速度（默认 0.25） |
| `airRaid.rangedPushTicks` | num | 30 | 弹开保持（tick，默认 30 = 1.5 秒） |
| `airRaid.dashBoostMinRange` | num | 6.0 | 【提供速度】位移法术的最小施放距离（格，默认 6） |
| `airRaid.dashBoostMaxRange` | num | 28.0 | 【提供速度】位移法术的最大施放距离（格，默认 28） |
| `airRaid.diveBoost` | bool | true | 俯冲段冲刺加速（默认开） |
| `airRaid.diveBoostInterval` | num | 30 | 俯冲段冲刺间隔（tick，默认 30 = 1.5 秒） |
| `airRaid.diveBoostMinRange` | num | 5.0 | 俯冲段冲刺·最近距离（格，默认 5） |
| `airRaid.diveBoostMaxRange` | num | 40.0 | 俯冲段冲刺·最远距离（格，默认 40） |
| `airRaid.diveBoostImpulse` | num | 0.55 | 俯冲段冲刺·一口补多少速度（格/tick，默认 0.55） |
| `airRaid.diveBoostFirework` | bool | true | 俯冲段冲刺·用烟花（默认开） |
| `airRaid.diveBoostFireworkScale` | num | 1.4 | 俯冲段冲刺·烟花力度倍数（默认 1.4） |
| `airRaid.diveBoostFan` | bool | false | 俯冲段冲刺·用羽扇（默认关） |
| `airRaid.diveBoostRiptide` | bool | true | 俯冲段冲刺·用激流三叉戟（默认开） |
| `airRaid.rangedBoostRiptide` | bool | true | 掉高补推·用激流三叉戟（默认开） |
| `bombing.tntTrack` | bool | true | TNT 追踪飞行（默认开） |
| `flightFollow.enabled` | bool | false | 飞行跟随（默认关） |
| `flightFollow.dist` | num | 25.0 | 飞行跟随·起手距离（格，默认 25） |
| `flightFollow.endDist` | num | 5.0 | 飞行跟随·收手距离（格，默认 5） |
| `flightFollow.firework` | bool | true | 飞行跟随·消耗烟花（默认开） |
| `flightFollow.elytra` | bool | true | 飞行跟随·消耗鞘翅耐久（默认开 = 照原版每 20 tick 扣 1 点） |
| `flightFollow.trident` | bool | true | 飞行跟随·消耗三叉戟耐久（默认开 = 照原版每次推进扣 1 点） |
| `voice.freeFlight` | bool | false | 仿创造飞行（默认关，1.20.1 精简版） |
| `voice.freeFlightItems` | list | List.of( | 仿创造飞行·资格物品表（默认空） |
| `voice.freeFlightEffects` | list | List.of( | 仿创造飞行·资格效果表（默认空） |
| `voice.freeFlightIdle` | bool | true | 仿创造飞行·智能待命（默认开） |
| `voice.freeFlightIdleSeconds` | num | 3 | 仿创造飞行·主人静止多久后落地待命（秒，默认 3） |
| `voice.freeFlightNearDist` | num | 2 | 仿创造飞行·落地待命的贴近距离（格，默认 2） |
| `voice.freeFlightTravel` | bool | true | 仿创造飞行·走路的活也交给飞（默认开） |
| `voice.freeFlightTravelDist` | num | 8.0 | 仿创造飞行·超过多远就改飞（格，默认 8） |
| `voice.glideElytraAnimation` | bool | false | 滑翔时改用模型自己的鞘翅动画（默认关） |

### 扫帚 / 载具　（21 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `broom.enable` | bool | true | 扫帚模式总开关（默认开） |
| `broom.range` | num | 8.0 | 战斗盘旋的站立距离（格，默认 8） |
| `broom.orbitMax` | num | 10.0 | 锁敌之后离敌的最远距离（格，默认 10，1~48） |
| `broom.minStandoff` | num | 6.0 | 锁敌之后离敌的**最小距离**（格，默认 6，1~32） |
| `broom.hover` | num | 2.0 | 悬停高度（格，默认 2，相对目标脚底） |
| `broom.climb` | num | 12.0 | 接敌爬升高度（格，默认 12，2~32） |
| `broom.follow` | bool | true | 平时（没有敌人时）跟随主人（默认开） |
| `broom.clampHome` | bool | true | 受「守家/工作区」活动范围约束 + 沿工作范围盘旋（默认开） |
| `broom.homeAlt` | num | 8.0 | 守家盘旋高度（格，离地，默认 8，1~32） |
| `broom.followStart` | num | 6.0 | 扫帚·跟随起手距离（格，默认 6，2~128） |
| `broom.followEnd` | num | 3.0 | 扫帚·跟随收手距离（格，默认 3，1~64） |
| `broom.unstick` | bool | true | 卡墙脱困（默认开） |
| `broom.idleLand` | bool | true | 扫帚待命落地（默认开） |
| `broom.recallDistance` | num | 100 | 扫帚牵引绳（格，默认 100，0=关闭） |
| `broom.patrolEnable` | bool | true | 巡逻航迹总开关（默认开） |
| `broom.clearance` | num | 1.5 | 新建航迹的默认净空半径（格，默认 1.5，0.5~6.0） |
| `broom.maxRadius` | num | 128 | 巡逻航迹最大水平半径（格，默认 128，16~512，0=不限制） |
| `tether.broomExtra` | num | 0.3 | 【扫帚档的额外下沉】（格，默认 0.3，0.0~2.0） |
| `ride.startupDelaySeconds` | num | 3.0 | 绑定飞行载具后的启动时间（秒，默认 3.0，0 = 关闭） |
| `ride.airCombat` | bool | true | 骑飞行载具时的悬停与低空盘旋（默认开） |
| `ride.ammoFeed` | bool | true | 骑载具时替她装弹（默认开） |

### 挖矿 / 伐木　（59 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `mine.oreValues` | list | DEFAULT_ORE_VALUES | 可挖掘方块表（自定义矿表） |
| `mine.extraBreakables` | list | List.of( | 额外可挖穿方块（障碍物名单，path 名如 oak_log——女仆挖矿遇到会挖穿而非当硬挡路报点弃置） |
| `mine.disabledBreakables` | list | List.of(
                        "oak_log" | 已取消挖穿的障碍物（排除名单，path 名如 spruce_log） |
| `mine.searchRadius` | num | 48 | 矿物检索半径（水平） |
| `mine.downRange` | num | 12 | 垂直向下搜索范围 |
| `mine.upRange` | num | 24 | 垂直向上搜索范围 |
| `mine.breakBudget` | num | 22 | 穿透预算（默认 22） |
| `mine.speedFactor` | num | 1.2 | 挖矿速度系数（1.0=玩家速度，1.2=快20%） |
| `mine.moveSpeed` | num | 0.6 | 发现矿物后的移动速度（0.6 = TLM 伐木任务同款移速（IFarmTask 同款 0.6f），走路观感自然） |
| `mine.junkKeep` | num | 32 | 废石保留量（每种超出销毁） |
| `mine.placedLifetime` | num | 5 | 搭方块自动清理时间（秒） |
| `mine.softNoDurability` | bool | true | 软方块（徒手可挖）开路不消耗镐耐久（默认开——与伐木一致） |
| `mine.pillarGuard` | bool | true | 搭方块防掉落（潜行效果，速度不变） |
| `mine.hardBlockReport` | bool | true | 硬挡路（箱子/机器等）报点弃置该矿 |
| `mine.seekThroughWalls` | bool | true | 智能挖矿（默认开） |
| `mine.anchorTimeout` | num | 200 | 锚点出框超时（tick，出框超过此时长重埋锚点） |
| `mine.relocateThrottle` | num | 20 | 重定位节流（tick，防边界抖动） |
| `mine.reach` | num | 4.5 | 挖掘/捡拾距离（格） |
| `mine.pillarCooldown` | num | 4 | 搭方块冷却（tick，垫脚下/搭路节奏） |
| `mine.rideNoPillar` | bool | true | 骑乘/坐下中禁止搭方块（默认开；对**所有**搭方块行为生效） |
| `mine.junkCheckInterval` | num | 100 | 废石清理检查间隔（tick） |
| `mine.scanBudget` | num | 4096 | 挖矿扫描预算（格/tick，默认 4096） |
| `mine.skipReportInterval` | num | 600 | 跳过矿/捡不到掉落播报间隔（tick，防刷屏） |
| `mine.noTargetPause` | num | 10 | 无目标静默闲逛时长（秒，挖矿与伐木共用） |
| `mine.chainMining` | bool | true | 连锁采集（默认开） |
| `mine.autoCollect` | bool | false | 自动收集（挖掘掉落物直接进女仆背包，不进世界 |
| `mine.chainLimit` | num | 16 | 连锁采集上限（块） |
| `mine.chainFullDurability` | bool | false | 连锁采集每块都消耗耐久（默认关） |
| `mine.requirePick` | bool | true | 没镐不启动（默认开） |
| `wood.woodValues` | list | List.of(
                                "minecraft:oak_log=300" | 可砍伐木材表 |
| `wood.searchRadius` | num | 48 | 木材检索半径（水平） |
| `wood.downRange` | num | 4 | 垂直向下搜索范围（格）——树在地表，默认只往下看 4 格 |
| `wood.upRange` | num | 24 | 垂直向上搜索范围（格）——树冠/巨型蘑菇很高，默认 24 |
| `wood.breakBudget` | num | 22 | 穿透预算（允许挖开多少层不可开路挡路方块）——与挖矿共享障碍物名单 |
| `wood.speedFactor` | num | 1.2 | 砍伐速度系数（1.0=玩家速度，1.2=快20%） |
| `wood.moveSpeed` | num | 0.3 | 接近木材速度倍率（0.6→0.3——伐木移速至少快一倍，观感像狂奔 |
| `wood.junkKeep` | num | 32 | 废石保留量——砍树途中挖穿泥土/石头产生的废石每种保留几组 |
| `wood.placedLifetime` | num | 5 | 搭方块清理时间（秒） |
| `wood.softNoDurability` | bool | true | 软方块（徒手可挖）开路不消耗斧耐久 |
| `wood.pillarGuard` | bool | true | 搭方块防掉落（潜行效果，速度不变） |
| `wood.hardBlockReport` | bool | true | 硬挡路（箱子/机器等）报点弃置该木材 |
| `wood.seekThroughWalls` | bool | true | 透视感知（隔墙找木材，默认开）——开启后女仆能发现视线被方块挡住的木材并挖通开路 |
| `wood.anchorTimeout` | num | 200 | 锚点出框超时（tick） |
| `wood.relocateThrottle` | num | 20 | 重定位节流（tick，防边界抖动） |
| `wood.reach` | num | 4.5 | 砍伐距离（格） |
| `wood.pillarCooldown` | num | 4 | 搭方块冷却（tick，垫脚下/搭路节奏） |
| `wood.plantSaplingEnabled` | bool | true | 随手种树（默认开） |
| `wood.plantSaplingCooldown` | num | 100 | 补种树苗冷却（tick，默认 100≈5 秒） |
| `wood.junkCheckInterval` | num | 100 | 废石清理检查间隔（tick） |
| `wood.scanBudget` | num | 4096 | 伐木扫描预算（格/tick，默认 4096） |
| `wood.skipReportInterval` | num | 600 | 跳过木材/被挡住播报间隔（tick，防刷屏） |
| `wood.chainMining` | bool | true | 连锁砍伐（同一棵树的相连木材一次砍完——树干天然相连，默认开启） |
| `wood.autoCollect` | bool | false | 自动收集（砍伐掉落物直接进女仆背包，不进世界） |
| `wood.chainLimit` | num | 16 | 连锁砍伐上限（块） |
| `wood.leavesClear` | bool | true | 树冠清理（默认开） |
| `aitools.switchTask` | bool | true | smart_switch_task 工具（切换任务/工作模式——TLM 原生 + 本模组任务全可切，支持空袭/挖矿/ |
| `bridge.reclaimToMaid` | bool | true | 搭路方块回收进背包（默认开，全局开关——搭路/挖矿/伐木/战斗搭方块一切女仆搭的垫脚方块都适用） |
| `misc.pickupPriority` | bool | false | 挖矿中禁止拾取（捡掉落物最低优先级 |
| `misc.orbJoin` | bool | true | 经验球归挖矿女仆（默认开） |

### 建造 / 蓝图　（37 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `build.speedTier` | str | "x1" | 建造速度档位 |
| `build.turbo` | bool | false | 极速模式（吃满服务器上限，性能风险） |
| `build.globalQuota` | num | 350 | 全局放置配额（每秒方块数上限，性能敏感） |
| `build.maxForceChunks` | num | 1024 | 建造区强制加载区块上限 |
| `build.maxBlocks` | num | 200 | LLM 蓝图最大方块数（上限放开到 50 万——构建链统一支持 50 万块级建筑） |
| `build.maxRange` | num | 12 | LLM 蓝图平面范围（±） |
| `build.maxHeight` | num | 32 | LLM 蓝图高度上限（默认由 8 提到 32——8 格高连一棵 20 格高的树都放不下，是「图纸设计出来后存不成蓝图 |
| `build.designMaxBlocks` | num | 500000 | AI 子 Agent 设计蓝图方块上限（默认与上限放开到 50 万——构建链统一支持 50 万块级建筑） |
| `build.structureMaxBlocks` | num | 1000000 | 结构文件蓝图方块上限（100万是服务器负担） |
| `build.maxMaids` | num | 30 | Promaid 手册女仆管理列表上限 |
| `build.originPlayer` | bool | true | 建造地点基准 |
| `build.indexStone` | bool | true | 指标石（默认开） |
| `build.noForceHome` | bool | true | 建造不强制 home（默认开） |
| `build.machineSmart` | bool | true | 红石机器专属搭建 |
| `build.keepFluids` | str | "auto" | 图纸流体保留（水/岩浆） |
| `build.looseMaterials` | str | "machine" | 缺料同类宽松 |
| `build.tntIgnitionGrace` | num | 120 | TNT 点火保护期（秒，默认 120） |
| `build.snapFinish` | bool | true | 完工整体重放（默认开） |
| `build.projection` | bool | true | 蓝图投影预览 |
| `build.fetchFromChests` | bool | true | 从箱子取材料（默认开） |
| `build.chestSearchMargin` | num | 4 | 取料扫描外扩（格，默认 4） |
| `build.chestFetchPerTake` | num | 8 | 每趟每格容器取多少（个，默认 8） |
| `build.chestFetchCooldown` | num | 60 | 取料冷却（tick，默认 60） |
| `build.altEnabled` | bool | true | 缺料自动替代开关 |
| `build.altSlabs` | list | List.of("minecraft:oak_slab" | 半格高替代品（台阶类方块缺料时按序使用，填完整注册名如 minecraft:oak_slab） |
| `build.altBlocks` | list | List.of("minecraft:oak_planks" | 一格高替代品（整方块缺料时按序使用，填完整注册名如 minecraft:stone_bricks） |
| `build.altTalls` | list | List.of("minecraft:oak_door" | 两格高替代品（门/双植物等缺料时按序使用，填完整注册名如 minecraft:oak_door） |
| `build.altWides` | list | List.of("minecraft:white_bed" | 横两格替代品（床等宽 2 格方块缺料时按序使用，填完整注册名如 minecraft:red_bed） |
| `build.altNoClips` | list | List.of("minecraft:torch" | 无碰撞替代品（花/火把/地毯等无碰撞箱方块缺料时按序使用，填完整注册名如 minecraft:oak_sapling |
| `build.stallInterval` | num | 20 | 卡住/放置节流（tick，建不动时重试间隔） |
| `build.lookahead` | num | 512 | 单轮扫描步数上限（建造计划每轮最多推进的步骤） |
| `build.deferredScanCap` | num | 256 | 延后步骤轮询上限（每轮检查的延后步骤数） |
| `build.structureMaxVolume` | num | 16777216 | 结构文件体积上限（宽×高×长，超限拒绝加载） |
| `build.areaProtection` | bool | true | 建造区保护（默认开） |
| `build.forceWorkActivity` | bool | true | 建造任务强制工作时段（默认开） |
| `aitools.workList` | bool | true | work_list 工具（query_todo/build_need——当前任务清单与建造材料缺口查询，杜绝'先生成 |
| `combat.buildShield` | bool | true | 建造护盾（默认开） |

### 防刷怪　（10 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `build.altNoClips` | list | List.of("minecraft:torch" | 无碰撞替代品（花/火把/地毯等无碰撞箱方块缺料时按序使用，填完整注册名如 minecraft:oak_sapling |
| `combat.torchPlacerEnable` | bool | true | 被动插火把（主人周围黑暗自动插火把照明） |
| `combat.torchDarkThreshold` | num | 7 | 插火把亮度阈值（0-15 |
| `spawnerTorch.enable` | bool | true | 总开关（默认开） |
| `spawnerTorch.radius` | num | 12.0 | 搜索半径（格，默认 12，4~32） |
| `spawnerTorch.priority` | bool | true | 优先去插（默认开） |
| `spawnerTorch.breakSpawner` | bool | true | 发现刷怪笼直接**拆掉**（默认开） |
| `spawnerTorch.breakRequirePickaxe` | bool | true | 拆除刷怪笼要求带镐（默认开） |
| `spawnerTorch.breakOnlyWhenActive` | bool | true | 只在刷怪笼**真的在刷怪**时才拆（默认开） |
| `spawnerTorch.breakBlacklist` | list | java.util.List.of("empty" | 拆除黑名单（永不拆的刷怪笼，默认 empty,benign） |

### 大运 / 饰品　（14 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `accessory.daYunEnable` | bool | true | 「大运」总开关（默认开） |
| `accessory.daYunPlayerEnable` | bool | true | 「大运」玩家侧（默认开） |
| `accessory.daYunMaxHardness` | num | 50.0d | 「大运」硬度上限（默认 50 = 黑曜石） |
| `accessory.daYunSkipContainers` | bool | true | 「大运」跳过容器（默认开） |
| `accessory.daYunDropItems` | bool | true | 「大运」破坏后掉落（默认开） |
| `accessory.daYunBreakParticles` | bool | true | 「大运」破坏粒子（默认开） |
| `accessory.daYunMaxPerTick` | num | 64 | 「大运」单 tick 破坏上限（默认 64） |
| `accessory.daYunRange` | num | 5 | 「大运」破坏范围（默认 5，范围 1~15） |
| `accessory.daYunBgm` | num | 1 | 佩戴「大运」鞘翅飞行时播放的 BGM（默认 1，可选 1 或 2） |
| `accessory.daYunWallPhase` | bool | false | 「大运」穿墙锁敌（默认关） |
| `accessory.daYunNoWallDamage` | bool | true | 「大运」免疫冲撞（默认开） |
| `accessory.daYunSmash` | bool | true | 「大运」撞击伤害（默认开） |
| `accessory.daYunSmashDamage` | num | 5.0d | 「大运」撞击伤害·每格速度（默认 5.0） |
| `accessory.daYunSmashKnockback` | num | 2.0d | 「大运」撞击击飞·每格速度（默认 2.0） |

### 压缩盒 / 背包　（12 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `mine.autoCollect` | bool | false | 自动收集（挖掘掉落物直接进女仆背包，不进世界 |
| `wood.autoCollect` | bool | false | 自动收集（砍伐掉落物直接进女仆背包，不进世界） |
| `aitools.craft` | bool | true | smart_craft 工具（按配方合成——从自己背包取材料，成品交给主人） |
| `aitools.place` | bool | true | smart_place 工具（从背包取出方块放到指定位置） |
| `aitools.ownerInventory` | bool | true | smart_owner_inventory 工具（查看主人背包里有什么——只读查询，不修改物品） |
| `combat.shieldShareEnable` | bool | true | 共享盾牌（主人盾牌耐久低/空时，从自己背包取盾给主人——不动自己副手） |
| `combat.totemShareEnable` | bool | true | 共享不死图腾（主人致命伤时，女仆背包/饰品栏的不死图腾优先救主人，特效同原版） |
| `bridge.reclaimToMaid` | bool | true | 搭路方块回收进背包（默认开，全局开关——搭路/挖矿/伐木/战斗搭方块一切女仆搭的垫脚方块都适用） |
| `compressionBox.maidExtension` | bool | true | 压缩盒·女仆背包延伸（默认开） |
| `compressionBox.maxStack` | num | 114514 | 压缩盒·每格上限（默认 114514 = 用户点名的那个数，范围 64~1000000） |
| `misc.backpackOverflow` | bool | true | 背包满时装进精妙背包（默认开） |
| `misc.baubleTotem` | bool | true | 饰品/背包不死图腾复活（默认开） |

### 记忆 / 对话 / 语音　（102 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `memory.enable` | bool | true | AI 记忆系统全局开关（per-maid 可覆盖） |
| `memory.extractThreshold` | num | 8 | 攒满多少条新对话触发一次 LLM 提取 |
| `memory.maxEntries` | num | 64 | 记忆段落上限（超出淘汰低重要度） |
| `memory.promptTopN` | num | 3 | 注入对话的相关记忆条数 |
| `memory.maxMessageChars` | num | 200 | 提取时每条对话消息最大字符数 |
| `memory.relationInject` | bool | true | 关系三元组注入对话（主人-喜欢-红茶） |
| `memory.conflictOverride` | bool | true | 冲突覆盖（新记忆高重要度覆盖旧记忆） |
| `memory.coreFold` | bool | true | 摘要折叠（核心记忆常驻+扩展按需） |
| `memory.workingNote` | bool | true | 工作笔记（跨对话任务状态注入） |
| `memory.openEvents` | bool | true | 未完成的事（默认开，移植自 Sphantosis） |
| `memory.scanInterval` | num | 20 | 记忆调度扫描间隔（秒） |
| `memory.projectionChars` | num | 600 | 注入对话的记忆投影字符上限 |
| `memory.extractTimeoutMin` | num | 5 | LLM 提取超时（分钟，超时允许重试） |
| `memory.rrfK` | num | 60.0 | 检索融合参数（RRF k，越大越平均） |
| `memory.decayDays` | num | 30 | 记忆衰减周期（天，未访问且重要度低删除） |
| `memory.decaySalience` | num | 3 | 衰减保留重要度（低于此值的非永久记忆可能被删） |
| `memory.lazySave` | bool | true | 防抖写盘（内存累积后按 scanInterval 批量落盘，减少磁盘 IO） |
| `memory.maintenanceMin` | num | 10 | 记忆维护周期（分钟，定期固化重要记忆、衰减陈旧记忆、降旧关系置信度） |
| `memory.relationDecayDays` | num | 60 | 关系置信度衰减周期（天，非永久关系 N 天未被强化则置信度×0.85，低到 0.15 变 inactive） |
| `memory.apiUrl` | str | "" | 记忆 API 地址（OpenAI 兼容 chat/completions 端点，留空 = 跟随 TLM） |
| `memory.apiKey` | str | "" | 记忆 API 密钥（留空 = 跟随 TLM |
| `memory.apiModel` | str | "" | 记忆 API 模型（留空 = 跟随 TLM 女仆当前模型） |
| `memory.indexEnable` | bool | true | 多级记忆索引（日/3日/周/月日记式摘要，跨边界与睡醒自动生成，移植自 Sphantosis） |
| `memory.indexOnSleep` | bool | true | 睡一觉自动处理（玩家睡醒后生成当日记忆日记 + 短期记忆整簇转长期） |
| `memory.indexOnLogout` | bool | true | 会话收尾归档（玩家登出=真人结束一天，收尾当日记忆 |
| `memory.indexMonthTopN` | num | 20 | 月级索引保留事件数（按重要度排序保留的最多事件数） |
| `memory.indexMaxEvents` | num | 40 | 单次索引事件上限（跨度内事件过多时按重要度裁剪再生成日记——控制摘要上下文长度） |
| `memory.shortTermDays` | num | 3 | 短期→长期转移阈值（游戏日，关联簇全部段落超过该年龄才整簇转移） |
| `memory.affectSnapshot` | bool | true | 情绪快照写入记忆（每条新记忆附带当时 PAD 情绪，供回看/分析 |
| `memory.persona` | bool | true | 人格种子注入（每女仆 persona.properties + traits.properties + core_m |
| `memory.carePoints` | bool | true | 每日关心点（每日回顾附上'下次该怎么对主人'的行动建议——从情绪残留/边界/风格推导，主动会话自动复用） |
| `memory.dualAgent` | bool | true | 双 agent 提取（摘要与事实/事件分两次独立 LLM 调用，更聚焦互不阻塞 |
| `memory.personaUnify` | bool | true | 人设统一（TLM 原版已有人设时，人格种子块降级为补充——只补人格参数/核心记忆，不再重复身份，冲突以 TLM 设定 |
| `memory.recallGate` | bool | true | 记忆召回闸门（移植自 MemoryRecallGate） |
| `memory.recallMinInterval` | num | 45 | 召回最小间隔（秒） |
| `memory.recallMinMessages` | num | 2 | 触发重召回所需的最少新增消息数 |
| `memory.recallCacheTtl` | num | 120 | 召回缓存有效期（秒） |
| `memory.graphEnable` | bool | true | 关系图多跳召回（移植自 MemoryGraphStore） |
| `memory.graphHops` | num | 2 | 关系图最大跳数（1=只含直接命中） |
| `memory.temporalEnable` | bool | true | 时间路召回（移植自 MinecraftTimeQuery） |
| `memory.profileItems` | bool | true | 结构化画像条目（移植自 UserProfile$ProfileItem） |
| `memory.snapshotEnable` | bool | true | 记忆快照（移植自 MemorySnapshotStore） |
| `memory.snapshotEveryN` | num | 3 | 每 N 次摘要刷新/生成一次记忆快照 |
| `memory.continuityEnable` | bool | true | 续写队列（移植自 ConversationContinuityStore） |
| `memory.eventPolicy` | bool | true | 世界事件策略（移植自 WorldEventMemoryPolicy） |
| `memory.auditEnable` | bool | true | 记忆审计（移植自 MemoryV3Store 审计元数据） |
| `memory.vectorEnable` | bool | false | 向量语义检索（移植自 MemoryVectorIndex/HttpEmbeddingClient） |
| `memory.embedUrl` | str | "" | embeddings 端点（OpenAI 兼容，如 https:  |
| `memory.embedKey` | str | "" | embeddings 密钥 |
| `memory.embedModel` | str | "" | embeddings 模型名（如 BAAI/bge-m3） |
| `memory.embedDim` | num | 1024 | 向量维度（0=不校验 |
| `memory.embedTimeout` | num | 5000 | embeddings 请求超时（毫秒） |
| `memory.embedBatch` | num | 16 | embeddings 单批文本数上限 |
| `affect.inject` | bool | true | 情绪注入对话上下文（ai_affect） |
| `aitools.remember` | bool | true | remember 工具（LLM 主动写记忆，"记住…"） |
| `aitools.workingNote` | bool | true | working_note 工具（跨对话任务笔记） |
| `dialogue.statusReporter` | bool | true | 工作状态播报（女仆卡住时气泡解释原因） |
| `dialogue.reportInterval` | num | 10 | 工作播报间隔（秒，默认 10） |
| `dialogue.reportRadius` | num | 32 | 工作播报扫描范围 |
| `dialogue.proactive` | bool | true | 主动对话（关心/夜晚/好感等主动开口） |
| `dialogue.proactiveCooldown` | num | 4 | 两次主动发言最小间隔（分钟） |
| `dialogue.proactiveDaily` | num | 12 | 主动对话日上限（次，控 token 成本 |
| `dialogue.proactiveKill` | bool | false | 击杀邀功对话（默认关） |
| `dialogue.autonomous` | bool | true | 自主决策（女仆自己换任务干活） |
| `dialogue.autonomousCooldown` | num | 10 | 自主决策冷却（分钟） |
| `dialogue.autonomousDaily` | num | 10 | 自主决策日上限（次） |
| `dialogue.apiDailyLimit` | num | 40 | 所有女仆每日主动 LLM 调用总量上限（token 成本 |
| `dialogue.reportCheck` | num | 20 | 工作播报检查间隔（tick） |
| `dialogue.proactiveScan` | num | 20 | 主动对话周期扫描间隔（秒） |
| `dialogue.proactiveLowHp` | num | 30 | 主动关心主人低血阈值（%） |
| `dialogue.proactiveEventCd` | num | 30 | 主动对话事件驱动冷却（秒，重伤/死亡等紧急事件） |
| `dialogue.autoScan` | num | 60 | 自主决策检查间隔（秒） |
| `dialogue.autoOwnerRange` | num | 16 | 自主决策触发主人范围 |
| `dialogue.autoDayStart` | num | 1000 | 自主决策工作开始时刻（游戏 tick） |
| `dialogue.autoDayEnd` | num | 13000 | 自主决策工作结束时刻（游戏 tick） |
| `dialogue.maxReplies` | num | 4 | 每轮主动会话最多发言次数（7 阶段不会一次性全喷——主人一次互动周期内最多主动发 N 次，之后进入空闲） |
| `dialogue.proactiveIdleMin` | num | 60 | 主动对话空闲重启（分钟，一轮跑完/被打断后主人 N 分钟没互动才重启新周期） |
| `dialogue.longSilenceMax` | num | 2 | 长沉默确认每日上限（次，"主人还在吗"这类确认一天最多几次，防烦人） |
| `dialogue.replyFeedback` | bool | true | 回复反馈学习（主人说'别说了/好烦'→ 记 error_mark 停止该话题 |
| `dialogue.topicBackoffMin` | num | 60 | 话题冷却（分钟，被主人否定的主动话题 N 分钟内不再提起） |
| `dialogue.outputLanguage` | str | "" | 对话输出语言（留空 = 强制中文输出 |
| `dialogue.langCheck` | bool | true | 对话输出语言检测（LLM 回复非设定语言时【内嵌翻译】成目标语言再显示） |
| `dialogue.promptSittingWork` | bool | true | 对话提示词·坐姿工作说明（默认开） |
| `misc.bubbleLimitMs` | num | 5000 | 对话气泡限频（毫秒，防刷屏） |
| `voice.volumeMultiplier` | num | 2.0 | TTS 语音播放音量倍率（与伤害/减伤无关！） |
| `voice.systemEnabled` | bool | true | 系统消息朗读（感知/工作/自保等规则气泡也播放 TTS 语音） |
| `voice.systemCooldownS` | num | 8 | 系统消息朗读冷却（秒，同一女仆两次朗读最小间隔） |
| `voice.voicePackEnabled` | bool | true | 系统语音包（config/maid_smart/system_voice/ 下 manifest.json 映射文本 |
| `voice.cacheMaxFiles` | num | 200 | TTS 语音缓存上限（config/maid_smart/voice_cache/，训练一次保存后复用 |
| `voice.jarPackEnabled` | bool | true | 内置日语语音包（默认开） |
| `voice.jarPackVolume` | num | 1.0 | 内置语音包音量倍率（默认 1.0，范围 0.1-20.0） |
| `voice.jarPackMinIntervalS` | num | 5 | 内置语音包最小间隔（秒，默认 5） |
| `voice.jarPackMuteNative` | bool | true | 播放时暂压原生语音包（默认开） |
| `voice.freeFlight` | bool | false | 仿创造飞行（默认关，1.20.1 精简版） |
| `voice.freeFlightItems` | list | List.of( | 仿创造飞行·资格物品表（默认空） |
| `voice.freeFlightEffects` | list | List.of( | 仿创造飞行·资格效果表（默认空） |
| `voice.freeFlightIdle` | bool | true | 仿创造飞行·智能待命（默认开） |
| `voice.freeFlightIdleSeconds` | num | 3 | 仿创造飞行·主人静止多久后落地待命（秒，默认 3） |
| `voice.freeFlightNearDist` | num | 2 | 仿创造飞行·落地待命的贴近距离（格，默认 2） |
| `voice.freeFlightTravel` | bool | true | 仿创造飞行·走路的活也交给飞（默认开） |
| `voice.freeFlightTravelDist` | num | 8.0 | 仿创造飞行·超过多远就改飞（格，默认 8） |
| `voice.glideElytraAnimation` | bool | false | 滑翔时改用模型自己的鞘翅动画（默认关） |

### 危险方块 / 避让　（4 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `combat.flightDangerAvoid` | bool | true | 飞行危险环境避让（默认开） |
| `misc.dangerAvoid` | bool | true | 寻路危险方块避让（默认开） |
| `misc.dangerBlocks` | list | List.of(
                        "minecraft:lava" | 危险方块表（完整注册名，一行一个 |
| `misc.dangerEscape` | bool | true | 险境脱离（默认开） |

### 预设 / 调试　（1 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `misc.presetMode` | str | "default" | 模式预设（手册首页「DIY / 默认模式」页切换） |

## 3. 按节分组（Forge 树全量）

### (root)　（2 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `teleportExemptSitting` | bool | true | 坐下的女仆不被自保传送拉走（默认开） |
| `tlmTeleportExempt` | bool | true | TLM 瞬移/跟随豁免（默认开） |

### accessory　（14 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `accessory.daYunEnable` | bool | true | 「大运」总开关（默认开） |
| `accessory.daYunPlayerEnable` | bool | true | 「大运」玩家侧（默认开） |
| `accessory.daYunMaxHardness` | num | 50.0d | 「大运」硬度上限（默认 50 = 黑曜石） |
| `accessory.daYunSkipContainers` | bool | true | 「大运」跳过容器（默认开） |
| `accessory.daYunDropItems` | bool | true | 「大运」破坏后掉落（默认开） |
| `accessory.daYunBreakParticles` | bool | true | 「大运」破坏粒子（默认开） |
| `accessory.daYunMaxPerTick` | num | 64 | 「大运」单 tick 破坏上限（默认 64） |
| `accessory.daYunRange` | num | 5 | 「大运」破坏范围（默认 5，范围 1~15） |
| `accessory.daYunBgm` | num | 1 | 佩戴「大运」鞘翅飞行时播放的 BGM（默认 1，可选 1 或 2） |
| `accessory.daYunWallPhase` | bool | false | 「大运」穿墙锁敌（默认关） |
| `accessory.daYunNoWallDamage` | bool | true | 「大运」免疫冲撞（默认开） |
| `accessory.daYunSmash` | bool | true | 「大运」撞击伤害（默认开） |
| `accessory.daYunSmashDamage` | num | 5.0d | 「大运」撞击伤害·每格速度（默认 5.0） |
| `accessory.daYunSmashKnockback` | num | 2.0d | 「大运」撞击击飞·每格速度（默认 2.0） |

### affect　（3 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `affect.enable` | bool | true | PAD 情绪层总开关（事件驱动+落盘） |
| `affect.inject` | bool | true | 情绪注入对话上下文（ai_affect） |
| `affect.recoverInterval` | num | 20 | 情绪静默恢复间隔（秒，无事件时情绪值缓慢回归） |

### airRaid　（48 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `airRaid.launchTicksMelee` | num | 30 | 起飞段时长·近战（tick，默认 30 = 1.5 秒） |
| `airRaid.launchTicksRanged` | num | 20 | 起飞段时长·远程（tick，默认 20 = 1 秒） |
| `airRaid.launchClimbTanMelee` | num | 1.88 | 起飞仰角正切·近战（默认 1.88 ≈ 62°） |
| `airRaid.launchClimbTanRanged` | num | 1.0 | 起飞仰角正切·远程（默认 1.0 = 45°） |
| `airRaid.launchRange` | num | 20.0 | 地面重新起飞的最大水平距离（格，默认 20） |
| `airRaid.altitudeTolerance` | num | 10.0 | 占位高度容差（格，默认 10） |
| `airRaid.jumpTicks` | num | 3 | 起跳等待上限（tick，默认 3） |
| `airRaid.fireworkCooldown` | num | 30 | 烟花最小间隔（tick，默认 30 = 1.5 秒） |
| `airRaid.fanCooldown` | num | 20 | 羽扇最小间隔（tick，默认 20 = 1 秒，与原版 getUseDuration 一致） |
| `airRaid.smashRange` | num | 3.5 | 收翅俯冲触发距离（格，默认 3.5） |
| `airRaid.smashHitRange` | num | 4.0 | 猛击命中判定距离（格，默认 4.0） |
| `airRaid.forcedHitRadius` | num | 2.5 | 范围强制命中半径（格，默认 2.5） |
| `airRaid.smashMaxTicks` | num | 20 | 猛击段最长（tick，默认 20 = 1 秒） |
| `airRaid.meleeReach` | num | 3.0 | 地面近战触及距离（格，默认 3.0） |
| `airRaid.maxPitchUp` | num | 55.0 | 阶段二俯仰限幅·抬头（度，默认 55） |
| `airRaid.maxPitchDown` | num | 70.0 | 阶段二俯仰限幅·低头（度，默认 70） |
| `airRaid.orbitRadius` | num | 10.0 | 远程空袭的盘旋半径（格，默认 10） |
| `airRaid.orbitMax` | num | 12.0 | 锁敌之后离敌的最远距离（格，默认 12，2~64） |
| `airRaid.minStandoff` | num | 6.0 | 空袭·离敌最小距离（格，默认 6.0，0~32；0 = 关闭） |
| `airRaid.minAboveHeight` | num | 8.0 | 空袭·离敌最低高度（格，默认 8.0，0~64；0 = 关闭） |
| `airRaid.rangedHoldHeight` | num | 10.0 | 期望盘旋高度（目标上方格数，默认 10） |
| `airRaid.rangedHoldGain` | num | 5.0 | 高度修正增益（默认 5.0） |
| `airRaid.rangedHoldBias` | num | 1.0 | 高度修正偏置（格，默认 1.0） |
| `airRaid.rangedBoostDrop` | num | 0.5 | 掉高容差（格，默认 0.5） |
| `airRaid.rangedOrbitUpMax` | num | 45.0 | 盘旋抬头上限（度，默认 45） |
| `airRaid.rangedOrbitDownMax` | num | 35.0 | 盘旋低头上限（度，默认 35） |
| `airRaid.rangedBoostInterval` | num | 100 | 掉高补推间隔（tick，默认 100 = 5 秒） |
| `airRaid.rangedBoostAimTicks` | num | 10 | 补推抬头窗口（tick，默认 10 = 0.5 秒） |
| `airRaid.rangedBoostPitch` | num | -45.0 | 补推仰角（度，默认 -45 = 抬头 45°） |
| `airRaid.rangedShotCooldown` | num | 20 | 远程开火基础间隔（tick，默认 20 = 1 秒） |
| `airRaid.rangedAttackRange` | num | 24.0 | 远程射程（格，默认 24） |
| `airRaid.rangedFireRange` | num | 24.0 | 空袭·有效开火距离（格，默认 24，0 = 不限） |
| `airRaid.rangedPushRadius` | num | 3.0 | 弹开触发半径（格，默认 3） |
| `airRaid.rangedPushSpeed` | num | 0.55 | 弹开水平速度（默认 0.55） |
| `airRaid.rangedPushUp` | num | 0.25 | 弹开抬升速度（默认 0.25） |
| `airRaid.rangedPushTicks` | num | 30 | 弹开保持（tick，默认 30 = 1.5 秒） |
| `airRaid.dashBoostMinRange` | num | 6.0 | 【提供速度】位移法术的最小施放距离（格，默认 6） |
| `airRaid.dashBoostMaxRange` | num | 28.0 | 【提供速度】位移法术的最大施放距离（格，默认 28） |
| `airRaid.diveBoost` | bool | true | 俯冲段冲刺加速（默认开） |
| `airRaid.diveBoostInterval` | num | 30 | 俯冲段冲刺间隔（tick，默认 30 = 1.5 秒） |
| `airRaid.diveBoostMinRange` | num | 5.0 | 俯冲段冲刺·最近距离（格，默认 5） |
| `airRaid.diveBoostMaxRange` | num | 40.0 | 俯冲段冲刺·最远距离（格，默认 40） |
| `airRaid.diveBoostImpulse` | num | 0.55 | 俯冲段冲刺·一口补多少速度（格/tick，默认 0.55） |
| `airRaid.diveBoostFirework` | bool | true | 俯冲段冲刺·用烟花（默认开） |
| `airRaid.diveBoostFireworkScale` | num | 1.4 | 俯冲段冲刺·烟花力度倍数（默认 1.4） |
| `airRaid.diveBoostFan` | bool | false | 俯冲段冲刺·用羽扇（默认关） |
| `airRaid.diveBoostRiptide` | bool | true | 俯冲段冲刺·用激流三叉戟（默认开） |
| `airRaid.rangedBoostRiptide` | bool | true | 掉高补推·用激流三叉戟（默认开） |

### aitools　（11 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `aitools.remember` | bool | true | remember 工具（LLM 主动写记忆，"记住…"） |
| `aitools.workingNote` | bool | true | working_note 工具（跨对话任务笔记） |
| `aitools.craft` | bool | true | smart_craft 工具（按配方合成——从自己背包取材料，成品交给主人） |
| `aitools.place` | bool | true | smart_place 工具（从背包取出方块放到指定位置） |
| `aitools.perception` | bool | true | perception_query 工具（look_around/terrain/build_site/inspect |
| `aitools.workList` | bool | true | work_list 工具（query_todo/build_need——当前任务清单与建造材料缺口查询，杜绝'先生成 |
| `aitools.ownerInventory` | bool | true | smart_owner_inventory 工具（查看主人背包里有什么——只读查询，不修改物品） |
| `aitools.switchTask` | bool | true | smart_switch_task 工具（切换任务/工作模式——TLM 原生 + 本模组任务全可切，支持空袭/挖矿/ |
| `aitools.airRaid` | bool | true | smart_air_raid 工具（起飞空袭/停止空袭——可指定近战或远程、锁定目标，并回报"缺不缺件"） |
| `aitools.workArea` | bool | true | smart_work_area 工具（把工作区设到此处并驻守 / 解除驻守恢复跟随 / 查看锚点——写的就是 TLM |
| `aitools.readiness` | bool | true | smart_readiness 工具（只读自检——空袭三件套/弹药/驻守/排班/工作锚点/血量，让模型"先查后做"） |

### bombing　（25 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `bombing.melee` | bool | true | 战斗模式轰炸（默认开） |
| `bombing.tnt` | bool | true | 战斗模式的 TNT 投掷（默认开） |
| `bombing.fuse` | num | 10 | 起爆延迟（tick，默认 10 = 0.5 秒） |
| `bombing.tntFuse` | num | 40 | 投掷 TNT 的引信（tick，默认 40 = 2 秒） |
| `bombing.tntInterval` | num | 200 | 投掷 TNT 的最短间隔（tick，默认 200 = 10 秒） |
| `bombing.tntSpeed` | num | 0.9 | 投掷初速（格/tick，默认 0.9） |
| `bombing.breakBlocks` | bool | false | 轰炸破坏方块（默认关） |
| `bombing.hurtFriendly` | bool | false | 轰炸伤到主人/友军（默认关） |
| `bombing.tntRange` | num | 12.0 | 投掷索敌半径（格，默认 12） |
| `bombing.tntBurstRatio` | num | 0.7 | 残血连投阈值（默认 0.7 = 七成血以下） |
| `bombing.tntBurstCount` | num | 3 | 连投最多几发（默认 3） |
| `bombing.reclaimSeconds` | num | 10 | 炸弹底座回收延迟（秒，默认 10，0 = 起爆即回收） |
| `bombing.placeGap` | num | 10 | 放置间隔（tick，默认 10 = 0.5 秒） |
| `bombing.dimensionGuard` | bool | true | 维度闸（默认开） |
| `bombing.anchorNeedsGlowstone` | bool | true | 重生锚需要萤石（默认开 = 原版口径） |
| `bombing.bombInterval` | num | 200 | 轰炸最短间隔（tick，默认 200 = 10 秒） |
| `bombing.pose` | bool | true | 副手动作表现（默认开） |
| `bombing.airPlace` | bool | true | 空中强制放置（默认开） |
| `bombing.airDrop` | bool | true | 空中悬空投弹（走后门，默认开） |
| `bombing.pinkMark` | bool | true | 女仆放置物的淡粉色标记（默认开，纯客户端） |
| `bombing.pinkFire` | bool | true | 爆炸火焰改粉色（默认开） |
| `bombing.fireRender` | bool | true | 粉色火焰渲染（默认开） |
| `bombing.fireProtect` | bool | true | 火焰伤害保护（默认开） |
| `bombing.tntTrack` | bool | true | TNT 追踪飞行（默认开） |
| `bombing.tntTrackTicks` | num | 20 | TNT 追踪时长（tick，默认 20 = 1 秒，0 = 不追踪） |

### bridge　（14 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `bridge.enabled` | bool | true | 搭路（默认开） |
| `bridge.maxDist` | num | 32 | 搭路触发距离（格，默认 32） |
| `bridge.airMaxDist` | num | 50 | 空中搭桥触发距离（格，默认 50） |
| `bridge.minDy` | num | 5 | 搭路最小高差（格，默认 3） |
| `bridge.minRadius` | num | 4 | 搭路最小球面半径（格，默认 3） |
| `bridge.startHDist` | num | 8.0 | 平桥启动水平距离（格，默认 6） |
| `bridge.threatDist` | num | 8 | 搭路威胁半径（格，默认 8） |
| `bridge.stepCooldown` | num | 4 | 搭路节奏（tick/块，默认 4） |
| `bridge.placedLifetime` | num | 3 | 搭路方块清理时间（秒，默认 3） |
| `bridge.reclaimToMaid` | bool | true | 搭路方块回收进背包（默认开，全局开关——搭路/挖矿/伐木/战斗搭方块一切女仆搭的垫脚方块都适用） |
| `bridge.buildOnlyNatural` | bool | true | 搭方块只用原版天然方块（默认开） |
| `bridge.buildBlacklist` | list | java.util.List.of( | 搭方块禁用名单（完整注册名，逗号分隔；默认空） |
| `bridge.buildWhitelist` | list | java.util.List.of( | 搭方块放宽名单（完整注册名，逗号分隔；默认空） |
| `bridge.combatPlacedLifetime` | num | 10 | 战斗搭方块清理时间（秒，默认 10） |

### broom　（17 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `broom.enable` | bool | true | 扫帚模式总开关（默认开） |
| `broom.range` | num | 8.0 | 战斗盘旋的站立距离（格，默认 8） |
| `broom.orbitMax` | num | 10.0 | 锁敌之后离敌的最远距离（格，默认 10，1~48） |
| `broom.minStandoff` | num | 6.0 | 锁敌之后离敌的**最小距离**（格，默认 6，1~32） |
| `broom.hover` | num | 2.0 | 悬停高度（格，默认 2，相对目标脚底） |
| `broom.climb` | num | 12.0 | 接敌爬升高度（格，默认 12，2~32） |
| `broom.follow` | bool | true | 平时（没有敌人时）跟随主人（默认开） |
| `broom.clampHome` | bool | true | 受「守家/工作区」活动范围约束 + 沿工作范围盘旋（默认开） |
| `broom.homeAlt` | num | 8.0 | 守家盘旋高度（格，离地，默认 8，1~32） |
| `broom.followStart` | num | 6.0 | 扫帚·跟随起手距离（格，默认 6，2~128） |
| `broom.followEnd` | num | 3.0 | 扫帚·跟随收手距离（格，默认 3，1~64） |
| `broom.unstick` | bool | true | 卡墙脱困（默认开） |
| `broom.idleLand` | bool | true | 扫帚待命落地（默认开） |
| `broom.recallDistance` | num | 100 | 扫帚牵引绳（格，默认 100，0=关闭） |
| `broom.patrolEnable` | bool | true | 巡逻航迹总开关（默认开） |
| `broom.clearance` | num | 1.5 | 新建航迹的默认净空半径（格，默认 1.5，0.5~6.0） |
| `broom.maxRadius` | num | 128 | 巡逻航迹最大水平半径（格，默认 128，16~512，0=不限制） |

### build　（35 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `build.speedTier` | str | "x1" | 建造速度档位 |
| `build.turbo` | bool | false | 极速模式（吃满服务器上限，性能风险） |
| `build.globalQuota` | num | 350 | 全局放置配额（每秒方块数上限，性能敏感） |
| `build.maxForceChunks` | num | 1024 | 建造区强制加载区块上限 |
| `build.maxBlocks` | num | 200 | LLM 蓝图最大方块数（上限放开到 50 万——构建链统一支持 50 万块级建筑） |
| `build.maxRange` | num | 12 | LLM 蓝图平面范围（±） |
| `build.maxHeight` | num | 32 | LLM 蓝图高度上限（默认由 8 提到 32——8 格高连一棵 20 格高的树都放不下，是「图纸设计出来后存不成蓝图 |
| `build.designMaxBlocks` | num | 500000 | AI 子 Agent 设计蓝图方块上限（默认与上限放开到 50 万——构建链统一支持 50 万块级建筑） |
| `build.structureMaxBlocks` | num | 1000000 | 结构文件蓝图方块上限（100万是服务器负担） |
| `build.maxMaids` | num | 30 | Promaid 手册女仆管理列表上限 |
| `build.originPlayer` | bool | true | 建造地点基准 |
| `build.indexStone` | bool | true | 指标石（默认开） |
| `build.noForceHome` | bool | true | 建造不强制 home（默认开） |
| `build.machineSmart` | bool | true | 红石机器专属搭建 |
| `build.keepFluids` | str | "auto" | 图纸流体保留（水/岩浆） |
| `build.looseMaterials` | str | "machine" | 缺料同类宽松 |
| `build.tntIgnitionGrace` | num | 120 | TNT 点火保护期（秒，默认 120） |
| `build.snapFinish` | bool | true | 完工整体重放（默认开） |
| `build.projection` | bool | true | 蓝图投影预览 |
| `build.fetchFromChests` | bool | true | 从箱子取材料（默认开） |
| `build.chestSearchMargin` | num | 4 | 取料扫描外扩（格，默认 4） |
| `build.chestFetchPerTake` | num | 8 | 每趟每格容器取多少（个，默认 8） |
| `build.chestFetchCooldown` | num | 60 | 取料冷却（tick，默认 60） |
| `build.altEnabled` | bool | true | 缺料自动替代开关 |
| `build.altSlabs` | list | List.of("minecraft:oak_slab" | 半格高替代品（台阶类方块缺料时按序使用，填完整注册名如 minecraft:oak_slab） |
| `build.altBlocks` | list | List.of("minecraft:oak_planks" | 一格高替代品（整方块缺料时按序使用，填完整注册名如 minecraft:stone_bricks） |
| `build.altTalls` | list | List.of("minecraft:oak_door" | 两格高替代品（门/双植物等缺料时按序使用，填完整注册名如 minecraft:oak_door） |
| `build.altWides` | list | List.of("minecraft:white_bed" | 横两格替代品（床等宽 2 格方块缺料时按序使用，填完整注册名如 minecraft:red_bed） |
| `build.altNoClips` | list | List.of("minecraft:torch" | 无碰撞替代品（花/火把/地毯等无碰撞箱方块缺料时按序使用，填完整注册名如 minecraft:oak_sapling |
| `build.stallInterval` | num | 20 | 卡住/放置节流（tick，建不动时重试间隔） |
| `build.lookahead` | num | 512 | 单轮扫描步数上限（建造计划每轮最多推进的步骤） |
| `build.deferredScanCap` | num | 256 | 延后步骤轮询上限（每轮检查的延后步骤数） |
| `build.structureMaxVolume` | num | 16777216 | 结构文件体积上限（宽×高×长，超限拒绝加载） |
| `build.areaProtection` | bool | true | 建造区保护（默认开） |
| `build.forceWorkActivity` | bool | true | 建造任务强制工作时段（默认开） |

### combat　（125 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `combat.selfPreserve` | bool | true | 自保行为（低血逃跑/搭高/治疗） |
| `combat.enterRatio` | num | 0.3 | 自保触发血量（0-1） |
| `combat.exitRatio` | num | 0.7 | 自保绝对解除血量（0-1，默认 0.7 |
| `combat.threatDistance` | num | 12 | 威胁感知距离 |
| `combat.waterClutch` | bool | true | 落地水（有水桶+坠落自动放水缓冲） |
| `combat.waterFallDistance` | num | 4.0 | 落地水触发高度（格，默认 4） |
| `combat.waterBucketLava` | bool | true | 岩浆逃生放水（垫高后周围无水源且包里有水桶 → 在自己垫的方块上放水灭火，1 秒后收回 |
| `combat.masterDeathTeleport` | bool | true | 主人死亡强制传送（无视战斗/距离） |
| `combat.pearlCooldown` | num | 100 | 末影珍珠逃生冷却（tick，20=1 秒 |
| `combat.pearlRatio` | num | 0.3 | 末影珍珠逃生触发血量（0-1，低于此值且威胁贴身才扔） |
| `combat.pearlDist` | num | 8.0 | 末影珍珠逃生威胁距离（威胁小于此格数才扔珍珠） |
| `combat.safeReturnRatio` | num | 0.7 | 安全回归血量（0-1，默认 0.7 |
| `combat.closeDistance` | num | 4.0 | 贴身距离（格，低于此值判定被近身） |
| `combat.pillarMax` | num | 30 | 至多向上搭多少个方块（格） |
| `combat.pillarSafeHeight` | num | 5 | 搭高安全高度（格，默认 5） |
| `combat.pillarAboveEnemy` | num | 30 | 搭高时高于敌方的格数（默认 30） |
| `combat.healCooldown` | num | 40 | 治疗食物冷却（tick） |
| `combat.threatScan` | num | 5 | 威胁扫描间隔（tick） |
| `combat.fleeSpeed` | num | 1.0 | 走位速度倍率（自保小幅走位拉开身位的移动加成，1.0=正常、不额外加速） |
| `combat.fireProtectBauble` | bool | true | 火焰保护饰品识别（默认开） |
| `combat.drownProtectBauble` | bool | true | 溺水保护饰品识别（默认开） |
| `combat.fleeWithSaveItem` | bool | false | 保命物品下允许惊慌（默认关） |
| `combat.soulSpellEnable` | bool | true | 致死伤害自动回魂符（默认开） |
| `combat.soulSpellLethalGuard` | bool | true | 致死伤害保护（默认开） |
| `combat.soulSpellOwnerRadius` | num | 24.0 | 主人收符半径（格，默认 24） |
| `combat.soulSpellCooldownSeconds` | num | 60 | 收符冷却（秒，默认 60） |
| `combat.autoResurrectEnable` | bool | true | 女仆自动复活（默认开） |
| `combat.autoResurrectDelaySeconds` | num | 60 | 复活延迟（秒，默认 60） |
| `combat.autoResurrectHealthRatio` | num | 1.0 | 复活血量比（默认 1.0 = 满血） |
| `combat.autoResurrectTiming` | num | 0 | 复活时机（0 = 延迟秒后复活，用上面的「复活延迟（秒）」 |
| `combat.stuckWindow` | num | 20 | 卡住判定窗口（tick） |
| `combat.stuckThreshold` | num | 0.3 | 卡住位移阈值（格） |
| `combat.threatGoneExit` | num | 400 | 威胁消失退出时长（tick，400=20 秒 |
| `combat.teleportCooldown` | num | 600 | 传送回家成功冷却（tick，默认 600 = 30 秒 |
| `combat.teleportSafeRadius` | num | 5.0 | 传送安全判定半径（格，主人身边此半径内无可见怪物才传送回主人，默认 5） |
| `combat.potionCooldown` | num | 40 | 药水尝试间隔（tick） |
| `combat.alertCooldown` | num | 60 | 头顶警示粒子间隔（tick） |
| `combat.announceCooldown` | num | 200 | 策略播报间隔（tick，防刷屏） |
| `combat.waterHold` | num | 5 | 落地水保持时长（tick） |
| `combat.waterLandingScan` | num | 2 | 落地水下探格数（提前放水检测） |
| `combat.snowClutch` | bool | true | 落地雪（细雪桶版落地水，默认开） |
| `combat.snowFallDistance` | num | 4.0 | 落地雪触发高度（格，默认 4） |
| `combat.snowHold` | num | 5 | 落地雪保持时长（tick，默认 5） |
| `combat.snowLandingScan` | num | 2 | 落地雪下探格数（默认 2） |
| `combat.tactics` | bool | true | 单兵作战战术（绕圈走位/打退拉扯/距离控制/时机举盾——PVP 式战斗） |
| `combat.tacticsMelee` | bool | true | 近战战术（贴脸绕圈、打一刀退一步、跳劈接近） |
| `combat.tacticsRanged` | bool | true | 远程战术（保持理想射程、横移绕圈风筝） |
| `combat.tacticsShield` | bool | true | 时机举盾（攻击冷却间隙举盾格挡、攻防交替 |
| `combat.tacticsOrbitRadius` | num | 2.2 | 绕圈半径（格） |
| `combat.tacticsKiteRange` | num | 0.6 | 远程理想射程倍率（0.6 = 保持在最大射程 60% 的距离放风筝） |
| `combat.tacticsMeleeKite` | bool | true | 近战贴脸后退（敌人贴进 2 格内主动后退拉开距离，女仆手长 3 格仍能挥砍） |
| `combat.aidOwnerEnable` | bool | true | 自动投喂/治疗主人（被动 |
| `combat.aidMaidMutual` | bool | true | 女仆之间互相支援（默认开） |
| `combat.aidFriendlyUnits` | bool | true | 支援其他友方单位（默认开） |
| `combat.aidUndeadUnits` | bool | true | 支援亡灵单位（默认开） |
| `combat.aidSelfUse` | bool | true | 自用也走支援表（默认开） |
| `combat.aidFoodThreshold` | num | 12 | 投喂触发饱食度（4-20 |
| `combat.aidFoodBlacklist` | list | java.util.List.of(
                        "minecraft:rotten_flesh" | 投喂食物黑名单（完整注册名，逗号分隔；留空 = 只按"能吃"判定，所有食物可喂） |
| `combat.aidSupportCategory` | list | java.util.List.of( | 通用支援品分类表（条目 = 「键=分类」，逗号分隔；留空 = 所有支援品按效果自动推断） |
| `combat.aidSupportCategoryUndead` | list | java.util.List.of( | 亡灵支援品分类表（条目 = 「键=分类」，逗号分隔；留空 = 按效果自动推断） |
| `combat.aidThirstThreshold` | num | 15 | 投喂触发口渴度（4-20 |
| `combat.aidDrinkMinPurity` | num | 2 | 喂水最低水质（0-3，默认 2=可接受的） |
| `combat.aidDrinkWhitelist` | list | java.util.List.of(
                            "minecraft:potion" | 喂水白名单（完整注册名，逗号分隔） |
| `combat.aidHealthThreshold` | num | 0.3 | 治疗触发血量（0.1-1 |
| `combat.torchPlacerEnable` | bool | true | 被动插火把（主人周围黑暗自动插火把照明） |
| `combat.maidFireGuard` | bool | true | 女仆着火不传主人（默认开） |
| `combat.torchDarkThreshold` | num | 7 | 插火把亮度阈值（0-15 |
| `combat.shieldShareEnable` | bool | true | 共享盾牌（主人盾牌耐久低/空时，从自己背包取盾给主人——不动自己副手） |
| `combat.totemShareEnable` | bool | true | 共享不死图腾（主人致命伤时，女仆背包/饰品栏的不死图腾优先救主人，特效同原版） |
| `combat.playerDamageMode` | num | 4 | 玩家对女仆伤害模式（0=TLM原版压制÷5封顶2点、1=玩家伤害完全免疫、2=玩家伤害无限制、3=玩家伤害有上限（比 |
| `combat.playerDamageMaidCap` | num | 0.1 | 玩家伤害上限比例（0-1 |
| `combat.autoSwitch` | bool | true | 主动切换战斗模式（主人被敌对生物攻击时，附近女仆无论什么任务都立即切战斗保护主人 |
| `combat.flightMode` | bool | true | 空袭·总开关（飞行作战）——鞘翅 + 任意近战武器 + 烟花火箭 |
| `combat.flightNoWallDamage` | bool | true | 飞行作战免疫鞘翅撞击伤害（默认开） |
| `combat.flightNoFallDamage` | bool | true | 空袭免疫摔落伤害（默认开） |
| `combat.flightDangerAvoid` | bool | true | 飞行危险环境避让（默认开） |
| `combat.heatEscape` | bool | true | 烫伤脱困（默认开） |
| `combat.wingRender` | bool | true | 鞘翅外观（默认开） |
| `combat.wingYsmYield` | bool | true | 鞘翅外观·YSM 让位（默认开） |
| `combat.riptideDash` | bool | true | 激流三叉戟旋转冲击（默认开） |
| `combat.riptideFlightScale` | num | 1.0 | 激流三叉戟·推进剂力度倍数（默认 1.0 = 不打折，范围 0.1~2.0） |
| `combat.selfWings` | bool | true | 自推鞘翅（默认开） |
| `combat.selfWingsItems` | list | List.of("locusazzurro_icaruswings:ikaros_wings" | 自推鞘翅·资格物品表（默认见上） |
| `combat.selfWingsScale` | num | 1.0 | 自推鞘翅·推力倍数（默认 1.0，范围 0.2~3.0） |
| `combat.crossbowPlainFirework` | bool | false | 弩可用普通烟花当弹药（默认关） |
| `combat.flightRangedPush` | bool | true | 远程空袭近身弹开（默认开） |
| `combat.flightRecallDistance` | num | 100 | 空袭牵引绳（格，默认 100，0=关闭） |
| `combat.flightSpellCast` | bool | true | 空袭顺带施法（默认开，需装《车万女仆 |
| `combat.flightSpellCastInterval` | num | 20 | 空袭施法间隔（tick，默认 20 = 1 秒） |
| `combat.flightDashClimb` | bool | true | 空袭·位移法术【提供高度】（默认开，需装《车万女仆 |
| `combat.flightDashBoost` | bool | true | 空袭·位移法术【提供速度】（默认开，需装《车万女仆 |
| `combat.flightDashClimbSpells` | list | List.of("irons_spellbooks:ascension" | 【提供高度】的法术表（填完整法术 id） |
| `combat.flightDashBoostSpells` | list | List.of("irons_spellbooks:burning_dash" | 【提供速度】的法术表（填完整法术 id） |
| `combat.flightDashInterval` | num | 40 | 位移法术间隔（tick，默认 40 = 2 秒） |
| `combat.flightDashBoostRespectCooldown` | bool | true | 位移法术·【提供速度】尊重法术自身冷却（默认开） |
| `combat.flightSpellCastRange` | num | 24.0 | 空袭施法距离（格，默认 24） |
| `combat.friendlyWindImmune` | bool | true | 友军风免（默认开） |
| `combat.autoSwitchRadius` | num | 16 | 主动切战斗响应半径（格） |
| `combat.autoSwitchModWeight` | num | 2.0 | 模组武器权重（选战斗任务时的加权随机权重，默认 2.0） |
| `combat.autoSwitchVanillaWeight` | num | 1.0 | 原版武器权重（默认 1.0） |
| `combat.autoSwitchAllowModTasks` | bool | true | 模组任务参与自主切换（默认开） |
| `combat.taskModes` | list | java.util.List.of( | 战斗模式分类表（默认空 = 全部按内置规则参与） |
| `combat.vanillaYieldToMod` | bool | true | 模组任务优先让位（默认开） |
| `combat.spellGearIgnore` | list | java.util.List.of("slashblade" | 法术装备忽略表（默认 slashblade, youkaishomecoming） |
| `combat.prefMeleeWeight` | num | 3 | 近战偏好权重（默认 3） |
| `combat.prefRangedWeight` | num | 1 | 远程偏好权重（默认 1） |
| `combat.tacticHoldTicks` | num | 40 | 战中换战术最短持有（tick，默认 40=2 秒） |
| `combat.reverseWindowTicks` | num | 100 | 战中反向切换窗口（tick，默认 100=5 秒） |
| `combat.reverseCooldownTicks` | num | 200 | 战中反向切换冷却（tick，默认 200=10 秒） |
| `combat.unarmedSkip` | bool | true | 空手不参战（默认开） |
| `combat.autoSwitchRestore` | bool | true | 战斗结束自动还原（威胁消失一段时间后切回战斗前的原任务 |
| `combat.autoSwitchRestoreDelay` | num | 200 | 战斗结束还原延迟（tick，200=10 秒） |
| `combat.forceAllSchedule` | bool | true | 参战时把作息临时切「全天」（默认开） |
| `combat.autoSwitchRestoreThreatDist` | num | 8 | 还原判定威胁半径（格，默认 8） |
| `combat.autoSwitchStaleSeconds` | num | 60 | 战斗僵局逃逸（秒，默认 60） |
| `combat.autoSwitchThreatExpandSeconds` | num | 10 | 动态威胁圈（秒，默认 10） |
| `combat.combatWorkRange` | num | 15 | 战斗时临时扩圈（格，默认 15，0 = 关闭） |
| `combat.assistRadius` | num | 16 | 援护半径（格，默认 16，0 = 关闭；借自别人改过的 TLM 1.5.3） |
| `combat.takeOverMovement` | bool | true | 接管走位（默认开） |
| `combat.sweepAlways` | bool | true | 平A必横扫（默认开） |
| `combat.attackThoseAttackingUs` | bool | true | 反击类型判不出的攻击者（默认开） |
| `combat.friendlyFireGuard` | bool | true | 主人/友军免伤总闸（默认开） |
| `combat.petImmunity` | bool | true | 玩家宠物免伤（默认开） |
| `combat.buildShield` | bool | true | 建造护盾（默认开） |
| `combat.hoeNotWeapon` | bool | true | 锄头不算武器（默认开） |

### compat　（1 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `compat.puppetBlacklist` | bool | true | 第三方玩法模式黑名单（默认开） |

### compressionBox　（2 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `compressionBox.maidExtension` | bool | true | 压缩盒·女仆背包延伸（默认开） |
| `compressionBox.maxStack` | num | 114514 | 压缩盒·每格上限（默认 114514 = 用户点名的那个数，范围 64~1000000） |

### dialogue　（27 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `dialogue.statusReporter` | bool | true | 工作状态播报（女仆卡住时气泡解释原因） |
| `dialogue.reportInterval` | num | 10 | 工作播报间隔（秒，默认 10） |
| `dialogue.reportRadius` | num | 32 | 工作播报扫描范围 |
| `dialogue.proactive` | bool | true | 主动对话（关心/夜晚/好感等主动开口） |
| `dialogue.proactiveCooldown` | num | 4 | 两次主动发言最小间隔（分钟） |
| `dialogue.proactiveDaily` | num | 12 | 主动对话日上限（次，控 token 成本 |
| `dialogue.proactiveKill` | bool | false | 击杀邀功对话（默认关） |
| `dialogue.autonomous` | bool | true | 自主决策（女仆自己换任务干活） |
| `dialogue.autonomousCooldown` | num | 10 | 自主决策冷却（分钟） |
| `dialogue.autonomousDaily` | num | 10 | 自主决策日上限（次） |
| `dialogue.apiDailyLimit` | num | 40 | 所有女仆每日主动 LLM 调用总量上限（token 成本 |
| `dialogue.reportCheck` | num | 20 | 工作播报检查间隔（tick） |
| `dialogue.proactiveScan` | num | 20 | 主动对话周期扫描间隔（秒） |
| `dialogue.proactiveLowHp` | num | 30 | 主动关心主人低血阈值（%） |
| `dialogue.proactiveEventCd` | num | 30 | 主动对话事件驱动冷却（秒，重伤/死亡等紧急事件） |
| `dialogue.autoScan` | num | 60 | 自主决策检查间隔（秒） |
| `dialogue.autoOwnerRange` | num | 16 | 自主决策触发主人范围 |
| `dialogue.autoDayStart` | num | 1000 | 自主决策工作开始时刻（游戏 tick） |
| `dialogue.autoDayEnd` | num | 13000 | 自主决策工作结束时刻（游戏 tick） |
| `dialogue.maxReplies` | num | 4 | 每轮主动会话最多发言次数（7 阶段不会一次性全喷——主人一次互动周期内最多主动发 N 次，之后进入空闲） |
| `dialogue.proactiveIdleMin` | num | 60 | 主动对话空闲重启（分钟，一轮跑完/被打断后主人 N 分钟没互动才重启新周期） |
| `dialogue.longSilenceMax` | num | 2 | 长沉默确认每日上限（次，"主人还在吗"这类确认一天最多几次，防烦人） |
| `dialogue.replyFeedback` | bool | true | 回复反馈学习（主人说'别说了/好烦'→ 记 error_mark 停止该话题 |
| `dialogue.topicBackoffMin` | num | 60 | 话题冷却（分钟，被主人否定的主动话题 N 分钟内不再提起） |
| `dialogue.outputLanguage` | str | "" | 对话输出语言（留空 = 强制中文输出 |
| `dialogue.langCheck` | bool | true | 对话输出语言检测（LLM 回复非设定语言时【内嵌翻译】成目标语言再显示） |
| `dialogue.promptSittingWork` | bool | true | 对话提示词·坐姿工作说明（默认开） |

### flightFollow　（6 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `flightFollow.enabled` | bool | false | 飞行跟随（默认关） |
| `flightFollow.dist` | num | 25.0 | 飞行跟随·起手距离（格，默认 25） |
| `flightFollow.endDist` | num | 5.0 | 飞行跟随·收手距离（格，默认 5） |
| `flightFollow.firework` | bool | true | 飞行跟随·消耗烟花（默认开） |
| `flightFollow.elytra` | bool | true | 飞行跟随·消耗鞘翅耐久（默认开 = 照原版每 20 tick 扣 1 点） |
| `flightFollow.trident` | bool | true | 飞行跟随·消耗三叉戟耐久（默认开 = 照原版每次推进扣 1 点） |

### maneuver　（4 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `maneuver.enable` | bool | true | 接敌机动·总开关（默认开） |
| `maneuver.yoyoAmp` | num | 4.0 | 高悠悠高度幅度（格，默认 4，0~16） |
| `maneuver.airSeparation` | bool | true | 空袭·空中防叠罗汉（默认开） |
| `maneuver.airSeparationRadius` | num | 2.0 | 空袭·半径偏置强度（格，默认 2.0，0~8） |

### memory　（53 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `memory.enable` | bool | true | AI 记忆系统全局开关（per-maid 可覆盖） |
| `memory.extractThreshold` | num | 8 | 攒满多少条新对话触发一次 LLM 提取 |
| `memory.maxEntries` | num | 64 | 记忆段落上限（超出淘汰低重要度） |
| `memory.promptTopN` | num | 3 | 注入对话的相关记忆条数 |
| `memory.maxMessageChars` | num | 200 | 提取时每条对话消息最大字符数 |
| `memory.relationInject` | bool | true | 关系三元组注入对话（主人-喜欢-红茶） |
| `memory.conflictOverride` | bool | true | 冲突覆盖（新记忆高重要度覆盖旧记忆） |
| `memory.coreFold` | bool | true | 摘要折叠（核心记忆常驻+扩展按需） |
| `memory.workingNote` | bool | true | 工作笔记（跨对话任务状态注入） |
| `memory.openEvents` | bool | true | 未完成的事（默认开，移植自 Sphantosis） |
| `memory.scanInterval` | num | 20 | 记忆调度扫描间隔（秒） |
| `memory.projectionChars` | num | 600 | 注入对话的记忆投影字符上限 |
| `memory.extractTimeoutMin` | num | 5 | LLM 提取超时（分钟，超时允许重试） |
| `memory.rrfK` | num | 60.0 | 检索融合参数（RRF k，越大越平均） |
| `memory.decayDays` | num | 30 | 记忆衰减周期（天，未访问且重要度低删除） |
| `memory.decaySalience` | num | 3 | 衰减保留重要度（低于此值的非永久记忆可能被删） |
| `memory.lazySave` | bool | true | 防抖写盘（内存累积后按 scanInterval 批量落盘，减少磁盘 IO） |
| `memory.maintenanceMin` | num | 10 | 记忆维护周期（分钟，定期固化重要记忆、衰减陈旧记忆、降旧关系置信度） |
| `memory.relationDecayDays` | num | 60 | 关系置信度衰减周期（天，非永久关系 N 天未被强化则置信度×0.85，低到 0.15 变 inactive） |
| `memory.apiUrl` | str | "" | 记忆 API 地址（OpenAI 兼容 chat/completions 端点，留空 = 跟随 TLM） |
| `memory.apiKey` | str | "" | 记忆 API 密钥（留空 = 跟随 TLM |
| `memory.apiModel` | str | "" | 记忆 API 模型（留空 = 跟随 TLM 女仆当前模型） |
| `memory.indexEnable` | bool | true | 多级记忆索引（日/3日/周/月日记式摘要，跨边界与睡醒自动生成，移植自 Sphantosis） |
| `memory.indexOnSleep` | bool | true | 睡一觉自动处理（玩家睡醒后生成当日记忆日记 + 短期记忆整簇转长期） |
| `memory.indexOnLogout` | bool | true | 会话收尾归档（玩家登出=真人结束一天，收尾当日记忆 |
| `memory.indexMonthTopN` | num | 20 | 月级索引保留事件数（按重要度排序保留的最多事件数） |
| `memory.indexMaxEvents` | num | 40 | 单次索引事件上限（跨度内事件过多时按重要度裁剪再生成日记——控制摘要上下文长度） |
| `memory.shortTermDays` | num | 3 | 短期→长期转移阈值（游戏日，关联簇全部段落超过该年龄才整簇转移） |
| `memory.affectSnapshot` | bool | true | 情绪快照写入记忆（每条新记忆附带当时 PAD 情绪，供回看/分析 |
| `memory.persona` | bool | true | 人格种子注入（每女仆 persona.properties + traits.properties + core_m |
| `memory.carePoints` | bool | true | 每日关心点（每日回顾附上'下次该怎么对主人'的行动建议——从情绪残留/边界/风格推导，主动会话自动复用） |
| `memory.dualAgent` | bool | true | 双 agent 提取（摘要与事实/事件分两次独立 LLM 调用，更聚焦互不阻塞 |
| `memory.personaUnify` | bool | true | 人设统一（TLM 原版已有人设时，人格种子块降级为补充——只补人格参数/核心记忆，不再重复身份，冲突以 TLM 设定 |
| `memory.recallGate` | bool | true | 记忆召回闸门（移植自 MemoryRecallGate） |
| `memory.recallMinInterval` | num | 45 | 召回最小间隔（秒） |
| `memory.recallMinMessages` | num | 2 | 触发重召回所需的最少新增消息数 |
| `memory.recallCacheTtl` | num | 120 | 召回缓存有效期（秒） |
| `memory.graphEnable` | bool | true | 关系图多跳召回（移植自 MemoryGraphStore） |
| `memory.graphHops` | num | 2 | 关系图最大跳数（1=只含直接命中） |
| `memory.temporalEnable` | bool | true | 时间路召回（移植自 MinecraftTimeQuery） |
| `memory.profileItems` | bool | true | 结构化画像条目（移植自 UserProfile$ProfileItem） |
| `memory.snapshotEnable` | bool | true | 记忆快照（移植自 MemorySnapshotStore） |
| `memory.snapshotEveryN` | num | 3 | 每 N 次摘要刷新/生成一次记忆快照 |
| `memory.continuityEnable` | bool | true | 续写队列（移植自 ConversationContinuityStore） |
| `memory.eventPolicy` | bool | true | 世界事件策略（移植自 WorldEventMemoryPolicy） |
| `memory.auditEnable` | bool | true | 记忆审计（移植自 MemoryV3Store 审计元数据） |
| `memory.vectorEnable` | bool | false | 向量语义检索（移植自 MemoryVectorIndex/HttpEmbeddingClient） |
| `memory.embedUrl` | str | "" | embeddings 端点（OpenAI 兼容，如 https:  |
| `memory.embedKey` | str | "" | embeddings 密钥 |
| `memory.embedModel` | str | "" | embeddings 模型名（如 BAAI/bge-m3） |
| `memory.embedDim` | num | 1024 | 向量维度（0=不校验 |
| `memory.embedTimeout` | num | 5000 | embeddings 请求超时（毫秒） |
| `memory.embedBatch` | num | 16 | embeddings 单批文本数上限 |

### mine　（29 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `mine.oreValues` | list | DEFAULT_ORE_VALUES | 可挖掘方块表（自定义矿表） |
| `mine.extraBreakables` | list | List.of( | 额外可挖穿方块（障碍物名单，path 名如 oak_log——女仆挖矿遇到会挖穿而非当硬挡路报点弃置） |
| `mine.disabledBreakables` | list | List.of(
                        "oak_log" | 已取消挖穿的障碍物（排除名单，path 名如 spruce_log） |
| `mine.searchRadius` | num | 48 | 矿物检索半径（水平） |
| `mine.downRange` | num | 12 | 垂直向下搜索范围 |
| `mine.upRange` | num | 24 | 垂直向上搜索范围 |
| `mine.breakBudget` | num | 22 | 穿透预算（默认 22） |
| `mine.speedFactor` | num | 1.2 | 挖矿速度系数（1.0=玩家速度，1.2=快20%） |
| `mine.moveSpeed` | num | 0.6 | 发现矿物后的移动速度（0.6 = TLM 伐木任务同款移速（IFarmTask 同款 0.6f），走路观感自然） |
| `mine.junkKeep` | num | 32 | 废石保留量（每种超出销毁） |
| `mine.placedLifetime` | num | 5 | 搭方块自动清理时间（秒） |
| `mine.softNoDurability` | bool | true | 软方块（徒手可挖）开路不消耗镐耐久（默认开——与伐木一致） |
| `mine.pillarGuard` | bool | true | 搭方块防掉落（潜行效果，速度不变） |
| `mine.hardBlockReport` | bool | true | 硬挡路（箱子/机器等）报点弃置该矿 |
| `mine.seekThroughWalls` | bool | true | 智能挖矿（默认开） |
| `mine.anchorTimeout` | num | 200 | 锚点出框超时（tick，出框超过此时长重埋锚点） |
| `mine.relocateThrottle` | num | 20 | 重定位节流（tick，防边界抖动） |
| `mine.reach` | num | 4.5 | 挖掘/捡拾距离（格） |
| `mine.pillarCooldown` | num | 4 | 搭方块冷却（tick，垫脚下/搭路节奏） |
| `mine.rideNoPillar` | bool | true | 骑乘/坐下中禁止搭方块（默认开；对**所有**搭方块行为生效） |
| `mine.junkCheckInterval` | num | 100 | 废石清理检查间隔（tick） |
| `mine.scanBudget` | num | 4096 | 挖矿扫描预算（格/tick，默认 4096） |
| `mine.skipReportInterval` | num | 600 | 跳过矿/捡不到掉落播报间隔（tick，防刷屏） |
| `mine.noTargetPause` | num | 10 | 无目标静默闲逛时长（秒，挖矿与伐木共用） |
| `mine.chainMining` | bool | true | 连锁采集（默认开） |
| `mine.autoCollect` | bool | false | 自动收集（挖掘掉落物直接进女仆背包，不进世界 |
| `mine.chainLimit` | num | 16 | 连锁采集上限（块） |
| `mine.chainFullDurability` | bool | false | 连锁采集每块都消耗耐久（默认关） |
| `mine.requirePick` | bool | true | 没镐不启动（默认开） |

### misc　（84 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `misc.cookRadius` | num | 16 | 烧制任务熔炉搜索范围 |
| `misc.brewRadius` | num | 16 | 酿造任务酿造台搜索范围 |
| `misc.processCooldown` | num | 40 | 烧制/酿造处理间隔（tick） |
| `misc.cookSmeltOres` | bool | true | 熔炉烧矿物（默认开） |
| `misc.cookSmeltAny` | bool | true | 烧任何可烧制物（默认开） |
| `misc.strollEnabled` | bool | true | 空闲散步（默认开） |
| `misc.strollInterval` | num | 200 | 散步间隔（tick，默认 200=10 秒，范围 20~1728000） |
| `misc.strollRadius` | num | 16 | 散步半径（格，默认 16，范围 4~128） |
| `misc.strollSpeed` | num | 0.4 | 散步速度倍率（默认 0.4；范围 0.05~2.5） |
| `misc.bedInterop` | bool | true | 床铺互通（默认开） |
| `misc.cooldownHud` | bool | true | 冷却可视化 HUD（默认开） |
| `misc.noPlaceInAir` | bool | true | 悬空禁搭方块（默认开） |
| `misc.noPlaceOnOwner` | bool | true | 不得搭在主人身上（默认开） |
| `misc.cakeEdible` | bool | true | 蛋糕可食用（默认开） |
| `misc.cookSmokerBlast` | bool | true | 兼容高炉/烟熏炉（默认开） |
| `misc.cookBurnWood` | bool | false | 烧木材（默认关） |
| `misc.cookSmeltAllow` | list | List.of( | 烧制清单·只烧这些（默认空 = 按自动判定） |
| `misc.cookSmeltDeny` | list | List.of( | 烧制清单·禁止烧制（默认空） |
| `misc.cookFuelAllow` | list | List.of( | 烧制清单·只用这些燃料（默认空 = 按燃烧时长自动挑） |
| `misc.cookFuelDeny` | list | List.of( | 烧制清单·禁用燃料（默认空） |
| `misc.slaughterCount` | num | 5 | 宰杀数量阈值（默认 5） |
| `misc.slaughterRadius` | num | 16 | 宰杀扫描半径（默认 16） |
| `misc.bubbleLimitMs` | num | 5000 | 对话气泡限频（毫秒，防刷屏） |
| `misc.scheduleBubbleEnabled` | bool | true | 排班贴身气泡（默认开） |
| `misc.scheduleBubbleDist` | num | 3.5 | 排班贴身气泡触发距离（格，默认 3.5） |
| `misc.pickupPriority` | bool | false | 挖矿中禁止拾取（捡掉落物最低优先级 |
| `misc.pickupValueReorder` | bool | true | 拾取按价值重排（默认开） |
| `misc.verticalRange` | num | 4 | 烹饪/酿造垂直搜索范围 |
| `misc.brewAuto` | bool | true | 酿造自动下料（true=自动两阶段酿药 / false=只维持 |
| `misc.nativeTaskSmooth` | bool | true | TLM 原生任务呆滞修复（行为无限时长/随机散步让位/走路少刹车/检查节流减半） |
| `misc.workUninterrupted` | bool | true | 干活不被打断（工作中跳过吃饭/偷吃/小伤恐慌/切班拽回） |
| `misc.produceTaskEnhance` | bool | true | 产出任务增强（农场连收连种 / 钓鱼主动找水域自带坐垫） |
| `misc.dimensionFollow` | bool | true | 跟随女仆跨维度传送（主人换维度后，跟随模式女仆自动传送到主人身边 |
| `misc.maidChunkLoad` | bool | true | 女仆区块强制加载（所有有主女仆所在区块持续保持实体 ticking，随时可传送/召回/救援 |
| `misc.maidSameDimPull` | bool | true | 同维度远距拉回（默认开） |
| `misc.maidSameDimDist` | num | 48 | 同维度拉回距离阈值（格，默认 48） |
| `misc.maidSameDimVertical` | num | 16 | Y 轴拉回门槛（格，默认 16） |
| `misc.maidTeleportRebuild` | bool | true | 传送后客户端重建（默认开） |
| `misc.followTighten` | bool | true | 跟随收紧（默认开，参考改版 TLM jar 设计） |
| `misc.workSpeedLevel0` | bool | true | 干活时移速压回 0 好感档（默认开） |
| `misc.workStayInCircle` | bool | true | 干活时不许出圈（默认开） |
| `misc.compassPriority` | bool | true | 罗盘圈优先（默认开） |
| `misc.nonCombatSpeedCap` | bool | true | 非战斗模式下移速硬上限（默认开） |
| `misc.nonCombatMaxSpeed` | num | 0.56 | 非战斗移速上限（默认 0.56，范围 0.05~1.5） |
| `misc.milkFeedWithBuff` | bool | true | 有增益也喂牛奶（默认开） |
| `misc.dangerAvoid` | bool | true | 寻路危险方块避让（默认开） |
| `misc.dangerBlocks` | list | List.of(
                        "minecraft:lava" | 危险方块表（完整注册名，一行一个 |
| `misc.dangerEscape` | bool | true | 险境脱离（默认开） |
| `misc.maidRescue` | bool | true | 受困救援（默认开） |
| `misc.logEnabled` | bool | true | 运行日志（默认开） |
| `misc.presetMode` | str | "default" | 模式预设（手册首页「DIY / 默认模式」页切换） |
| `misc.heldLightMaid` | bool | true | 手持光源发实光（默认开） |
| `misc.chainHarvest` | bool | true | 农场连锁收获（默认开 |
| `misc.autoCollect` | bool | false | 农场掉落物立即拾取（默认关） |
| `misc.backpackOverflow` | bool | true | 背包满时装进精妙背包（默认开） |
| `misc.bdStorage` | bool | false | 超越维度存储联动（默认关） |
| `misc.chainHarvestLimit` | num | 24 | 农场连锁收获上限（格） |
| `misc.batchPlant` | bool | true | 农场批量种植（种植时以当前格为中心蔓延，把相连农田里的空耕地一次全种上） |
| `misc.batchPlantLimit` | num | 24 | 农场批量种植上限（格） |
| `misc.maidBonemealSapling` | bool | true | 树苗骨粉催熟（默认开） |
| `misc.maidBonemealFarm` | bool | true | 农场作物骨粉催熟（默认开） |
| `misc.scheduleEnabled` | bool | true | 排班表系统（默认开） |
| `misc.scheduleRestoreGrace` | num | 60 | 战斗还原后排班宽限（tick，默认 60=3 秒） |
| `misc.scheduleAvailabilityCheck` | bool | true | 排班切换前完整可用性检测（默认开） |
| `misc.scheduleReverseWindowTicks` | num | 200 | 排班反向切换窗口（tick，默认 200=10 秒） |
| `misc.scheduleReverseThreshold` | num | 2 | 排班反向切换阈值（默认 2） |
| `misc.scheduleReverseCooldownTicks` | num | 200 | 排班反向切换冷却（tick，默认 200=10 秒） |
| `misc.scheduleMinHoldTicks` | num | 60 | 排班最短持有期（tick，默认 60=3 秒，借鉴 TLM-Sincerely MINIMUM_TASK_HOLD_ |
| `misc.scheduleForceBrainRefresh` | bool | true | 排班切段后大脑自愈（默认开，借鉴 TLM-Sincerely FORCE_BRAIN_REFRESH_ON_STUC |
| `misc.scheduleActivityRange` | num | 12 | 排班活动半径（格，默认 12） |
| `misc.scheduleRangeFloor` | bool | true | 排班活动半径下限生效（默认开） |
| `misc.farmlandGuard` | bool | true | 不踩坏农田（默认开） |
| `misc.toolAutoEquip` | bool | true | 任务工具自动装备（默认开） |
| `misc.baubleTotem` | bool | true | 饰品/背包不死图腾复活（默认开） |
| `misc.orbJoin` | bool | true | 经验球归挖矿女仆（默认开） |
| `misc.equipRescue` | bool | true | 装备救回（默认开） |
| `misc.workTeleportHold` | bool | true | 干活时不被召回（默认开） |
| `misc.homeMovement` | bool | true | home 模式走位（默认开） |
| `misc.stationaryLock` | bool | true | 站桩锁位（默认开） |
| `misc.fishingChair` | bool | true | 钓鱼自动坐垫（默认开） |
| `misc.takeOverFishing` | bool | true | 接管钓鱼（默认开） |
| `misc.takeOverFarm` | bool | true | 接管农场（默认开） |
| `misc.drivenNoStroll` | bool | true | 坐骑不闲逛（默认开） |
| `misc.serverForcedConfig` | bool | false | 多人服务器 |

### perception　（12 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `perception.enable` | bool | true | 感知变化检测总开关 |
| `perception.hostile` | bool | true | 敌对检测（出现/接近/消失） |
| `perception.owner` | bool | true | 主人检测（受伤/血量低/看向女仆） |
| `perception.weather` | bool | true | 天气变化检测 |
| `perception.scanInterval` | num | 20 | 快照扫描间隔（tick） |
| `perception.eventCooldown` | num | 30 | 同类事件播报限频（秒，非敌对事件用） |
| `perception.hostileShowCooldown` | num | 300 | 敌对感知显示限频（秒，默认 300 = 5 分钟一条） |
| `perception.ownerLowHealth` | num | 30 | 主人血量低阈值（%） |
| `perception.lookTicks` | num | 3 | 主人持续注视判定时长（秒） |
| `perception.lookEnterDeg` | num | 35.0 | 看向进入角度（度） |
| `perception.lookExitDeg` | num | 55.0 | 看向退出角度（度） |
| `perception.lookCooldown` | num | 1800 | 「主人一直在看着我…」触发间隔（秒，每只女仆各自计时 |

### ride　（21 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `ride.enable` | bool | true | 原版生物骑乘总开关（默认开） |
| `ride.followDist` | num | 5.0 | 跟随停下距离（格，默认 5.0，1.0~16.0） |
| `ride.speedScale` | num | 1.0 | 速度总倍率（默认 1.0，0.2~3.0） |
| `ride.pursueTarget` | bool | true | 骑马追击目标（默认开） |
| `ride.startupDelaySeconds` | num | 3.0 | 绑定飞行载具后的启动时间（秒，默认 3.0，0 = 关闭） |
| `ride.furnitureBlacklist` | list | java.util.List.of("touhou_little_maid:chair" | 家具类坐骑黑名单（女仆坐上去时，本模组所有骑乘改动一律不生效） |
| `ride.modMounts` | bool | true | 模组坐骑兼容（默认开） |
| `ride.modMountFire` | bool | true | 模组坐骑·代她开火（默认开） |
| `ride.noSaddlePets` | bool | true | 无鞍可骑仆从（默认开） |
| `ride.servantAuto` | bool | true | 模组仆从坐骑·单独区间（默认开） |
| `ride.servantTransfer` | bool | true | 模组仆从坐骑·伤害转移（默认开） |
| `ride.batonExclusive` | bool | true | 骑乘指挥棒·独占右击（默认开） |
| `ride.airCombat` | bool | true | 骑飞行载具时的悬停与低空盘旋（默认开） |
| `ride.airAlt` | num | 3.0 | **跟随**时的悬停高度（**离地**格数，默认 3，1~20） |
| `ride.fightAlt` | num | 15.0 | **接敌**时的爬升高度（比敌人高多少格，默认 15，3~40） |
| `ride.orbitRadius` | num | 4.0 | 盘旋半径（格，默认 4，2~24） |
| `ride.ammoFeed` | bool | true | 骑载具时替她装弹（默认开） |
| `ride.bombStandoff` | num | 20.0 | 投弹安全高度（格，默认 20，0~64） |
| `ride.fixedWingAltBonus` | num | 60.0 | 固定翼（滑翔式）额外加高（格，默认 60，0~80） |
| `ride.realisticLanding` | bool | false | 拟真降落（默认关） |
| `ride.batonMark` | bool | true | 骑乘指挥棒·光标（默认开） |

### spawnerTorch　（7 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `spawnerTorch.enable` | bool | true | 总开关（默认开） |
| `spawnerTorch.radius` | num | 12.0 | 搜索半径（格，默认 12，4~32） |
| `spawnerTorch.priority` | bool | true | 优先去插（默认开） |
| `spawnerTorch.breakSpawner` | bool | true | 发现刷怪笼直接**拆掉**（默认开） |
| `spawnerTorch.breakRequirePickaxe` | bool | true | 拆除刷怪笼要求带镐（默认开） |
| `spawnerTorch.breakOnlyWhenActive` | bool | true | 只在刷怪笼**真的在刷怪**时才拆（默认开） |
| `spawnerTorch.breakBlacklist` | list | java.util.List.of("empty" | 拆除黑名单（永不拆的刷怪笼，默认 empty,benign） |

### tether　（7 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `tether.enable` | bool | true | 武装拴绳总开关（默认开） |
| `tether.tetherLeashSteer` | bool | true | 二号位·悬挂时用武装拴绳操控方向（默认开，仅空袭档） |
| `tether.hang` | num | 2.6 | 悬挂距离（格，默认 2.6，0.5~6.0） |
| `tether.ghostAlpha` | num | 0.1 | 【第一人称下的透明度】（默认 0.1，0.0~1.0；**设 1.0 = 关掉这个功能**，照旧不透明） |
| `tether.glowMark` | bool | true | 【金色描边标记】（默认开） |
| `tether.broomExtra` | num | 0.3 | 【扫帚档的额外下沉】（格，默认 0.3，0.0~2.0） |
| `tether.pull` | bool | true | 【拉扯】绳子绷紧时像原版拴绳一样把女仆拽过来（默认开） |

### voice　（18 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `voice.volumeMultiplier` | num | 2.0 | TTS 语音播放音量倍率（与伤害/减伤无关！） |
| `voice.systemEnabled` | bool | true | 系统消息朗读（感知/工作/自保等规则气泡也播放 TTS 语音） |
| `voice.systemCooldownS` | num | 8 | 系统消息朗读冷却（秒，同一女仆两次朗读最小间隔） |
| `voice.voicePackEnabled` | bool | true | 系统语音包（config/maid_smart/system_voice/ 下 manifest.json 映射文本 |
| `voice.cacheMaxFiles` | num | 200 | TTS 语音缓存上限（config/maid_smart/voice_cache/，训练一次保存后复用 |
| `voice.jarPackEnabled` | bool | true | 内置日语语音包（默认开） |
| `voice.jarPackVolume` | num | 1.0 | 内置语音包音量倍率（默认 1.0，范围 0.1-20.0） |
| `voice.jarPackMinIntervalS` | num | 5 | 内置语音包最小间隔（秒，默认 5） |
| `voice.jarPackMuteNative` | bool | true | 播放时暂压原生语音包（默认开） |
| `voice.freeFlight` | bool | false | 仿创造飞行（默认关，1.20.1 精简版） |
| `voice.freeFlightItems` | list | List.of( | 仿创造飞行·资格物品表（默认空） |
| `voice.freeFlightEffects` | list | List.of( | 仿创造飞行·资格效果表（默认空） |
| `voice.freeFlightIdle` | bool | true | 仿创造飞行·智能待命（默认开） |
| `voice.freeFlightIdleSeconds` | num | 3 | 仿创造飞行·主人静止多久后落地待命（秒，默认 3） |
| `voice.freeFlightNearDist` | num | 2 | 仿创造飞行·落地待命的贴近距离（格，默认 2） |
| `voice.freeFlightTravel` | bool | true | 仿创造飞行·走路的活也交给飞（默认开） |
| `voice.freeFlightTravelDist` | num | 8.0 | 仿创造飞行·超过多远就改飞（格，默认 8） |
| `voice.glideElytraAnimation` | bool | false | 滑翔时改用模型自己的鞘翅动画（默认关） |

### wood　（26 项）

| 配置键 | 类型 | 默认 | 作用（注释首句） |
|---|---|---|---|
| `wood.woodValues` | list | List.of(
                                "minecraft:oak_log=300" | 可砍伐木材表 |
| `wood.searchRadius` | num | 48 | 木材检索半径（水平） |
| `wood.downRange` | num | 4 | 垂直向下搜索范围（格）——树在地表，默认只往下看 4 格 |
| `wood.upRange` | num | 24 | 垂直向上搜索范围（格）——树冠/巨型蘑菇很高，默认 24 |
| `wood.breakBudget` | num | 22 | 穿透预算（允许挖开多少层不可开路挡路方块）——与挖矿共享障碍物名单 |
| `wood.speedFactor` | num | 1.2 | 砍伐速度系数（1.0=玩家速度，1.2=快20%） |
| `wood.moveSpeed` | num | 0.3 | 接近木材速度倍率（0.6→0.3——伐木移速至少快一倍，观感像狂奔 |
| `wood.junkKeep` | num | 32 | 废石保留量——砍树途中挖穿泥土/石头产生的废石每种保留几组 |
| `wood.placedLifetime` | num | 5 | 搭方块清理时间（秒） |
| `wood.softNoDurability` | bool | true | 软方块（徒手可挖）开路不消耗斧耐久 |
| `wood.pillarGuard` | bool | true | 搭方块防掉落（潜行效果，速度不变） |
| `wood.hardBlockReport` | bool | true | 硬挡路（箱子/机器等）报点弃置该木材 |
| `wood.seekThroughWalls` | bool | true | 透视感知（隔墙找木材，默认开）——开启后女仆能发现视线被方块挡住的木材并挖通开路 |
| `wood.anchorTimeout` | num | 200 | 锚点出框超时（tick） |
| `wood.relocateThrottle` | num | 20 | 重定位节流（tick，防边界抖动） |
| `wood.reach` | num | 4.5 | 砍伐距离（格） |
| `wood.pillarCooldown` | num | 4 | 搭方块冷却（tick，垫脚下/搭路节奏） |
| `wood.plantSaplingEnabled` | bool | true | 随手种树（默认开） |
| `wood.plantSaplingCooldown` | num | 100 | 补种树苗冷却（tick，默认 100≈5 秒） |
| `wood.junkCheckInterval` | num | 100 | 废石清理检查间隔（tick） |
| `wood.scanBudget` | num | 4096 | 伐木扫描预算（格/tick，默认 4096） |
| `wood.skipReportInterval` | num | 600 | 跳过木材/被挡住播报间隔（tick，防刷屏） |
| `wood.chainMining` | bool | true | 连锁砍伐（同一棵树的相连木材一次砍完——树干天然相连，默认开启） |
| `wood.autoCollect` | bool | false | 自动收集（砍伐掉落物直接进女仆背包，不进世界） |
| `wood.chainLimit` | num | 16 | 连锁砍伐上限（块） |
| `wood.leavesClear` | bool | true | 树冠清理（默认开） |

## 4. 文案存量（`WORKFLOW.md` §6：只减不增）

| 树 | 可见文案里带轮次编号（`实测NNN`）的条数 |
|---|---|
| promaid_src | 0 |
| promaid_src_neo | 0 |

改动任何一条相关文案后，这个数只能变小或不变；新写的文案一律不带轮次编号。
