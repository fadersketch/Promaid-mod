# -*- coding: utf-8 -*-
"""Normify-style normalized fractal module tree for the Promaid mod repo.

Consumed by gen_arch.py.

Record shape (exactly 9 elements):
  (id, zh, en, desc_zh, desc_en, sources, tags, apis, deps)
    sources: [(repo-relative path, start line, end line or None)]
    apis:    [(protocol, signature, desc_zh, desc_en)]   -- leaves only
    deps:    [(kind, target_id, label_zh, label_en)]     -- kind in call|event|dataflow|reference

Arrow convention: mixin -> feature (a mixin patches a feature), so the graph stays acyclic.
"""

MODULES = [
# ===================================================================== L1 root
("promaid", "Promaid 模组总览", "Promaid Mod Overview",
 "车万女仆（Touhou Little Maid）的增强附属模组，把女仆从「会干活」推进到「会驾驶、会飞、会记、会说话」。"
 "本树描述 1.21.1 NeoForge 主线（promaid_src_neo，Mojang 映射）；1.20.1 Forge 侧另见 promaid-forge 树。",
 "An enhancement addon for Touhou Little Maid that pushes maids from 'does chores' to "
 "'drives, flies, remembers and talks'. This tree documents the 1.21.1 NeoForge mainline "
 "(promaid_src_neo, Mojang mappings); the 1.20.1 Forge side lives in the promaid-forge tree.",
 [("promaid_src_neo/com/maidsmart/ProMaidMod.java", 1, None)], [], [], []),

# ============================================================== 1. entry
("promaid.entry", "入口与装配", "Entry & Assembly",
 "模组启动入口、功能注册枢纽、首次加入礼物与创造栏注入。所有子系统都从这里被接上 TLM 的扩展点。",
 "Mod bootstrap, the registration hub, first-join gift and creative-tab injection. Every subsystem is "
 "wired into TLM's extension points from here.",
 [("promaid_src_neo/com/maidsmart/ProMaidExtension.java", 1, None),
  ("promaid_src_neo/com/maidsmart/ProMaidMod.java", 1, None)], [], [], []),

("promaid.entry.mod", "模组主类与初始化", "Mod Main & Bootstrap",
 "注解 @Mod 的入口：注册物品、物品/网络/TaskData 引导、配置 SPEC 装配，以及一段约 320 行的历史默认值"
 "迁移链（含 22 个 *_MIGRATED 一次性标记）。",
 "The @Mod entry: registers items, bootstraps item/network/task-data, assembles the config SPEC, and runs a "
 "~320-line chain of historical default-value migrations (including 22 one-shot *_MIGRATED flags).",
 [("promaid_src_neo/com/maidsmart/ProMaidMod.java", 1, 452)], ["entry"], [], []),

("promaid.entry.extension", "功能注册枢纽", "Feature Registration Hub",
 "实现 TLM 的 @LittleMaidExtension：注册 AI 工具、任务、TaskData、AI 上下文、core/rest 大脑行为，"
 "并驱动所有服务端每 tick 模块。是全模组唯一的装配中枢，也是最大的耦合点。",
 "Implements TLM's @LittleMaidExtension: registers AI tools, tasks, TaskData, AI contexts and core/rest "
 "brain behaviors, and drives every server-tick module. The single assembly hub of the whole mod, and its "
 "largest coupling point.",
 [("promaid_src_neo/com/maidsmart/ProMaidExtension.java", 593, 779)], ["entry", "hub"], [], []),

("promaid.entry.gift", "首次加入礼物", "First-Join Gift",
 "用原版进度（advancement）而非 NBT 标记判定首次进入，发一次见面礼。",
 "Awards a one-time gift on first join, keyed off a vanilla advancement rather than an NBT flag.",
 [("promaid_src_neo/com/maidsmart/FirstJoinGift.java", 1, 113)], ["entry"], [], []),

("promaid.entry.creative-tab", "创造栏注入", "Creative Tab Injection",
 "把本模组物品插进原版创造模式物品栏的分类里，而不是另开一栏。",
 "Injects this mod's items into the vanilla creative-tab categories instead of adding its own tab.",
 [("promaid_src_neo/com/maidsmart/CreativeTabHandler.java", 1, 48)], ["entry"], [], []),

# ============================================================== 2. mixin
("promaid.mixin", "注入层（Mixin）", "Mixin Layer",
 "85 个 Mixin（mixins.promaid.json 里 68 个 common 加 17 个 client），逐一核对无孤儿。这是本模组唯一能"
 "与 TLM/原版深耦合的合法通道，也是「不 fork TLM 也能改行为」的根基。",
 "85 mixins (68 common plus 17 client in mixins.promaid.json), verified 1:1 with no orphans. This layer is "
 "the mod's only legitimate channel for deep coupling into TLM/vanilla, and the reason it can change "
 "behavior without forking TLM.",
 [("promaid_src_neo/mixins.promaid.json", 1, None)], [], [], []),

("promaid.mixin.core-tlm-maid", "TLM 女仆本体注入", "TLM EntityMaid Core Patches",
 "直接给 EntityMaid 打补丁：站桩锁位、传送豁免、扫击伤害、游泳姿态、任务/日程守卫、玩家伤害策略、"
 "压缩盒背包延伸、床铺互通。",
 "Patches EntityMaid directly: stand-still locking, teleport exemption, sweep damage, swim pose, "
 "task/schedule guards, player-damage policy, compression-box inventory extension and bed interop.",
 [("promaid_src_neo/com/maidsmart/mixin/MaidStationaryMixin.java", 1, 44),
  ("promaid_src_neo/com/maidsmart/mixin/MaidTeleportPreserveMixin.java", 1, 110),
  ("promaid_src_neo/com/maidsmart/mixin/MaidCompressionBoxMixin.java", 1, 161)], ["mixin"], [],
 [("call", "promaid.work.tags", "站桩标记", "Work-still tags"),
  ("call", "promaid.core.safety", "无主降级", "Ownerless downgrade")]),

("promaid.mixin.tlm-tasks", "TLM 任务行为注入", "TLM Task Behavior Patches",
 "对 TLM 内置任务类做闸门/限频/奖励修正：吃饭、偷吃、恐慌、跟随、挤奶、剪毛、采蜜、钓鱼落座、盾牌、"
 "近战/激流、拾取优先级、收割/耕地、大脑节流。",
 "Gates, rate-limits and reward-tunes TLM's built-in task classes: meal, steal-edible, panic, follow, "
 "milking, shearing, honey, fishing-seat, shield, melee/riptide, pickup priority, harvest/till and brain "
 "throttling.",
 [("promaid_src_neo/com/maidsmart/mixin/MaidFollowOwnerTickMixin.java", 1, 88),
  ("promaid_src_neo/com/maidsmart/mixin/FarmSweepMixin.java", 1, 285),
  ("promaid_src_neo/com/maidsmart/mixin/NativeTaskSmoothMixin.java", 1, 132)], ["mixin"], [],
 [("call", "promaid.system.schedule", "日程守卫", "Schedule guard"),
  ("call", "promaid.work.tags", "站桩标记", "Work-still tags")]),

("promaid.mixin.vanilla-ai", "原版 AI 注入", "Vanilla AI Patches",
 "无主女仆总闸、走路抢方向盘抑制、原版呆滞刹车时长、空袭导航守卫、骑乘散步。",
 "Ownerless-maid gate, walk-steering suppression, native idle-brake durations, air-raid nav guards and "
 "riding stroll.",
 [("promaid_src_neo/com/maidsmart/mixin/BehaviorOwnerlessGateMixin.java", 1, 74),
  ("promaid_src_neo/com/maidsmart/mixin/MaidMoveSuppressMixin.java", 1, 89),
  ("promaid_src_neo/com/maidsmart/mixin/MoveToTargetSinkDurationMixin.java", 1, 29)], ["mixin"], [],
 [("call", "promaid.flight.free", "自由飞行接管", "Free-flight takeover"),
  ("call", "promaid.system.goety", "Goety 推进期让位", "Goety thrust concession")]),

("promaid.mixin.entity-physics", "实体与物理注入", "Entity & Physics Patches",
 "外力归因（风弹/法术推人）、速度矢量拦截、骑乘门禁、重力冻结、爆炸/火焰/树叶/区块冻结等原版世界行为修补。",
 "Force attribution (wind charge / spell knockback), delta-movement interception, mount gating, gravity "
 "freeze, plus explosion/fire/leaf/chunk-freeze world-behavior patches.",
 [("promaid_src_neo/com/maidsmart/mixin/EntitySetDeltaMovementMixin.java", 1, 41),
  ("promaid_src_neo/com/maidsmart/mixin/MaidBaubleTotemMixin.java", 1, 266),
  ("promaid_src_neo/com/maidsmart/mixin/ExplosionWindGuardMixin.java", 1, 89)], ["mixin"], [],
 [("call", "promaid.combat.guards", "友军风免", "Friendly wind guard"),
  ("call", "promaid.work.build-exec", "区块冻结", "Chunk freeze")]),

("promaid.mixin.render-client", "客户端渲染注入", "Client Render & Gecko Patches",
 "鞘翅层、旋转/俯冲倾角、幽灵渲染、扫帚/拴绳虚影、Gecko 动画去重、模型包排序、金色描边、自由飞行动画注册、"
 "情绪姿势覆盖。",
 "Elytra layer, spin/dive tilt, ghost rendering, broom/tether ghosting, Gecko animation dedup, model-pack "
 "sorting, gold outline, free-flight animation registration and emotion pose override.",
 [("promaid_src_neo/com/maidsmart/mixin/EmotionPoseMixin.java", 1, 282),
  ("promaid_src_neo/com/maidsmart/mixin/MaidModelPackSortMixin.java", 1, 91),
  ("promaid_src_neo/com/maidsmart/mixin/MaidGhostTetherMixin.java", 1, 60)], ["mixin", "client"], [],
 [("call", "promaid.social.emotion", "情绪姿势", "Emotion pose"),
  ("call", "promaid.combat.tether", "拴绳虚影", "Tether ghost")]),

("promaid.mixin.tlm-config-gui", "TLM 配置界面扩展", "TLM Config GUI Extensions",
 "在 TLM 右键配置界面注入「长期记忆」「自由飞行」「Goety」三个面板。",
 "Injects three panels — long-term memory, free flight and Goety — into TLM's right-click config GUI.",
 [("promaid_src_neo/com/maidsmart/mixin/MaidConfigMemoryMixin.java", 1, 323),
  ("promaid_src_neo/com/maidsmart/mixin/MaidConfigFreeFlightMixin.java", 1, 114),
  ("promaid_src_neo/com/maidsmart/mixin/MaidConfigGoetyMixin.java", 1, 106)], ["mixin"], [],
 [("call", "promaid.social.memory", "记忆面板", "Memory panel"),
  ("call", "promaid.flight.free", "飞行开关", "Flight toggle")]),

("promaid.mixin.network-chunk", "网络与区块追踪注入", "Network & Chunk-Tracking Patches",
 "远程开界面绕过 4 格距离、强制实体同步（治「客户端没有她」）、远程界面同步泵。靠改写 vanilla ChunkMap 的 "
 "pairing/seenBy 实现，脆弱且深度反射。",
 "Lets the GUI open remotely past the 4-block limit, forces entity resync (fixes 'she is not on my client') "
 "and pumps remote GUI sync — by rewriting vanilla ChunkMap pairing/seenBy. Fragile and deeply reflective.",
 [("promaid_src_neo/com/maidsmart/mixin/ChunkMapTrackRemoteMixin.java", 1, 168),
  ("promaid_src_neo/com/maidsmart/mixin/MaidContainerRemoteOpenMixin.java", 1, 84),
  ("promaid_src_neo/com/maidsmart/mixin/ChunkMapRemotePumpMixin.java", 1, 70)], ["mixin", "net"], [],
 [("call", "promaid.system.remote-gui", "远程界面", "Remote GUI"),
  ("call", "promaid.system.visibility", "可见性自愈", "Visibility self-heal")]),

("promaid.mixin.dialogue-llm", "对话与 LLM 注入", "Chat & LLM Patches",
 "语言/回调修复、LLM 调用闸门、system prompt 追加、傀儡模式让位、TTS 音量、气泡限流。",
 "Language/callback fixes, LLM call gating, system-prompt appending, puppet-mode concession, TTS volume and "
 "chat-bubble rate limiting.",
 [("promaid_src_neo/com/maidsmart/mixin/MaidChatLanguageMixin.java", 1, 162),
  ("promaid_src_neo/com/maidsmart/mixin/ChatBubbleLimitMixin.java", 1, 134),
  ("promaid_src_neo/com/maidsmart/mixin/MaidChatLlmGateMixin.java", 1, 43)], ["mixin"], [],
 [("call", "promaid.social.dialogue", "对话层", "Dialogue layer"),
  ("call", "promaid.core.prompt", "提示词追加", "Prompt appender")]),

("promaid.mixin.compat", "兼容类注入", "Compat Patches",
 "Goety 位移聚晶推进期屏蔽走路抢方向盘；骑乘/武装拴绳相关注入（玩家下方挂点、金色标记、重锤骑乘落地缓冲、"
 "扫帚实体 travel）。",
 "Suppresses walk-steering during Goety displacement-crystal thrust, plus ride/tether injections (hang point "
 "below the player, gold marker, mace-ride landing buffer, broom entity travel).",
 [("promaid_src_neo/com/maidsmart/mixin/EntityGunnerHangMixin.java", 1, 94),
  ("promaid_src_neo/com/maidsmart/mixin/EntityBroomMaidTravelMixin.java", 1, 168),
  ("promaid_src_neo/com/maidsmart/mixin/PlayerMaceRideFallMixin.java", 1, 124)], ["mixin", "compat"], [],
 [("call", "promaid.system.goety", "Goety", "Goety"),
  ("call", "promaid.combat.ride", "骑乘", "Ride")]),

# ============================================================== 3. combat
("promaid.combat", "战斗与载具", "Combat & Vehicles",
 "模组体量最大的一层（76 文件约 2.77 MB）：从主动参战、空袭、扫帚、轰炸，到骑乘指挥棒与第三方载具兼容。"
 "也是技术债最集中的区域。",
 "The largest layer (76 files, about 2.77 MB): proactive engagement, air raid, broom, bombing, the ride baton "
 "and third-party vehicle compat. Also where the tech debt concentrates.",
 [("promaid_src_neo/com/maidsmart/combat", 1, None)], [], [], []),

("promaid.combat.auto-switch", "主动参战", "Proactive Engagement",
 "主人被攻击或主人攻击敌对生物时，女仆自动从当前任务切到攻击任务参战，战后还原原任务；含威胁判定、"
 "任务池抽签、模组/原生任务让位、战术态重调。",
 "When the owner is hit — or hits a hostile — maids auto-switch from their current task to a combat task and "
 "restore it afterwards. Includes threat scoring, a task-pool lottery, mod/native task concession and tactics "
 "re-tuning.",
 [("promaid_src_neo/com/maidsmart/combat/AutoCombatSwitch.java", 1, 799),
  ("promaid_src_neo/com/maidsmart/combat/AutoCombatPools.java", 1, 676),
  ("promaid_src_neo/com/maidsmart/combat/AutoCombatTargeting.java", 1, 488)], ["combat"], [], []),

("promaid.combat.auto-switch.pools", "战斗任务池", "Combat Task Pools",
 "可被抽中的攻击任务集合与权重，决定她切过去用哪种打法。",
 "The set of combat tasks eligible for the lottery, with weights deciding which fighting style she switches "
 "into.",
 [("promaid_src_neo/com/maidsmart/combat/AutoCombatPools.java", 1, 676),
  ("promaid_src_neo/com/maidsmart/combat/CombatModeTable.java", 1, 280)], ["combat"], [],
 [("call", "promaid.entry.extension", "任务表", "Task table")]),

("promaid.combat.auto-switch.targeting", "参战威胁判定", "Engagement Threat Targeting",
 "威胁评分与目标筛选：谁算威胁、值不值得切任务、打完多久撤。",
 "Threat scoring and target filtering: who counts as a threat, whether it is worth switching, and when to "
 "disengage.",
 [("promaid_src_neo/com/maidsmart/combat/AutoCombatTargeting.java", 1, 488),
  ("promaid_src_neo/com/maidsmart/combat/CombatWorkRange.java", 1, 184)], ["combat"], [],
 [("call", "promaid.system.work-area", "工作范围", "Work range"),
  ("call", "promaid.combat.guards", "友军判定", "Friendly check")]),

("promaid.combat.auto-switch.neutral", "中立威胁驱动", "Neutral Threat Driver",
 "把发狂的狼、被模组魔改成中立生物的目标也纳入「威胁」。",
 "Also counts enraged wolves and mod-mutated neutral mobs as threats.",
 [("promaid_src_neo/com/maidsmart/combat/NeutralThreatDriver.java", 1, 590)], ["combat"], [],
 [("call", "promaid.combat.auto-switch", "参战开关", "Combat switch")]),

("promaid.combat.targeting", "飞行索敌与诊断", "Flight Targeting & Diagnostics",
 "空袭状态下强制以自身为圆心半径 50 格索敌（绕过 TLM 原版丢目标），并提供 "
 "/maid_smart combat check 自助判据检查。",
 "Forces self-centred 50-block targeting while airborne (bypassing TLM's target dropping), plus the "
 "/maid_smart combat check self-test.",
 [("promaid_src_neo/com/maidsmart/combat/FlightTargeting.java", 1, 462),
  ("promaid_src_neo/com/maidsmart/combat/CombatSenseCheck.java", 1, 329)], ["combat", "diagnostics"], [],
 [("call", "promaid.system.work-area", "工作范围", "Work range"),
  ("call", "promaid.core.command", "combat check", "combat check")]),

("promaid.combat.flight-raid", "空袭（近战/远程）", "Air Raid (Melee / Ranged)",
 "鞘翅加武器加飞行道具的模式：遇敌起跳滑翔、放烟花推进、爬升/环绕/收翅俯冲、近战一记或远程持续开火，循环。",
 "Elytra plus weapon plus flight item: on spotting an enemy she launches, boosts with fireworks, "
 "climbs/orbits/folds her wings to dive, lands one melee hit or keeps firing at range, then loops.",
 [("promaid_src_neo/com/maidsmart/combat/MaidFlightCombatBehavior.java", 1, 3493),
  ("promaid_src_neo/com/maidsmart/combat/MaidFlightKit.java", 1, 1748),
  ("promaid_src_neo/com/maidsmart/combat/MaidFlightRangedTask.java", 1, 707)], ["combat", "flight"], [],
 [("call", "promaid.combat.targeting", "索敌", "Targeting"),
  ("call", "promaid.combat.bombing", "轰炸", "Bombing"),
  ("call", "promaid.combat.maneuvers", "战术机动", "Maneuvers")]),

("promaid.combat.flight-raid.follow", "飞行跟随", "Elytra Follow-Owner",
 "主人自己飞走时，女仆背上鞘翅追上来跟随，而不是在地面垫方块。",
 "When the owner flies off, she puts on elytra and follows through the air instead of pillaring up on the "
 "ground.",
 [("promaid_src_neo/com/maidsmart/combat/MaidFlightFollowBehavior.java", 1, 1680)], ["combat", "flight"], [],
 [("call", "promaid.combat.flight-raid", "飞行套件", "Flight kit"),
  ("call", "promaid.combat.tether", "拴绳", "Tether")]),

("promaid.combat.flight-raid.recall", "空袭牵引绳", "Air-Raid Leash",
 "半径 N 格（默认 100）内找不到主人就立刻把她传送回你身边，空中也能传——防「飞太高把目标打死后自己回不来」。",
 "If she cannot find the owner within N blocks (100 by default) she is teleported straight back, mid-air if "
 "needed, so she never strands herself after a kill.",
 [("promaid_src_neo/com/maidsmart/combat/MaidFlightRecall.java", 1, 158)], ["combat"], [],
 [("call", "promaid.system.follow", "传送", "Teleport")]),

("promaid.combat.flight-raid.air-combat", "空中交战姿态", "Air Combat Posture",
 "载具/空中状态下的悬停、机头朝向与环绕接敌的统一姿态层。",
 "The shared posture layer for hovering, nose heading and orbiting engagement while mounted or airborne.",
 [("promaid_src_neo/com/maidsmart/combat/MaidAirCombat.java", 1, 611),
  ("promaid_src_neo/com/maidsmart/combat/CombatOrbit.java", 1, None),
  ("promaid_src_neo/com/maidsmart/combat/CombatManeuver.java", 1, None)], ["combat"], [], []),

("promaid.combat.broom", "扫帚模式", "Broom Mode",
 "取出 TLM 扫帚实体骑上去飞行、悬停、接敌爬升盘旋，开火链路与远程空袭完全同款；含牵引绳回传与套件校验。"
 "只有玩家手动指派才会进。",
 "She mounts a TLM broom entity, flies, hovers and orbits to engage — the firing chain is identical to ranged "
 "air raid. Includes leash recall and kit validation. Only entered when the player assigns it.",
 [("promaid_src_neo/com/maidsmart/combat/MaidBroomDrive.java", 1, 2571),
  ("promaid_src_neo/com/maidsmart/combat/MaidBroomBehavior.java", 1, 788),
  ("promaid_src_neo/com/maidsmart/combat/MaidBroomKit.java", 1, 370)], ["combat", "flight"], [],
 [("call", "promaid.combat.flight-raid", "开火链路", "Firing chain"),
  ("call", "promaid.combat.ride", "骑乘工具", "Ride kit")]),

("promaid.combat.bombing", "空袭轰炸", "Air-Raid Bombing",
 "打完那一记或远程开火之后，顺手放一枚炸弹：末地水晶（黑曜石底座）、重生锚（1 萤石）、床（仅在原版会炸的维度）、"
 "投掷 TNT。材料齐的几段在同一次攻击里一起放。",
 "After landing a hit — or after firing at range — she drops a bomb as a follow-up: end crystal (obsidian "
 "base), respawn anchor (1 glowstone), bed (only where vanilla beds explode) and thrown TNT. Every variant she "
 "has materials for fires in the same attack.",
 [("promaid_src_neo/com/maidsmart/combat/MaidBombing.java", 1, 1205),
  ("promaid_src_neo/com/maidsmart/combat/BombPlacement.java", 1, 454),
  ("promaid_src_neo/com/maidsmart/combat/BombThrow.java", 1, 409)], ["combat", "bomb"], [], []),

("promaid.combat.bombing.items", "炸弹选材与判据", "Bomb Material Selection",
 "按背包材料决定放哪一段：注册名里带 tnt 的、方块继承原版 TntBlock 的模组 TNT 都认；认出来的是哪一件就"
 "放它自己那一枚。",
 "Picks which bomb to place from her inventory: any modded TNT whose registry name contains 'tnt', or whose "
 "block extends vanilla TntBlock, is accepted; whichever item is recognised is the one placed.",
 [("promaid_src_neo/com/maidsmart/combat/BombItems.java", 1, 336),
  ("promaid_src_neo/com/maidsmart/combat/BombConfig.java", 1, 151)], ["combat"], [],
 [("call", "promaid.core.config", "配置", "Config")]),

("promaid.combat.bombing.blast", "爆炸与安全口径", "Blast & Safety Semantics",
 "「破坏方块」与「伤到主人/友军」默认都关；她自己不会被自己的炸弹炸到或推飞；黑曜石底座默认保留 10 秒再"
 "收进背包。",
 "'Break blocks' and 'hurt owner/allies' both default off; she cannot be caught or launched by her own bomb; "
 "the obsidian base is kept 10 seconds before being returned to her inventory.",
 [("promaid_src_neo/com/maidsmart/combat/BombExplosion.java", 1, 190),
  ("promaid_src_neo/com/maidsmart/combat/BombTntTick.java", 1, 193),
  ("promaid_src_neo/com/maidsmart/combat/MaidTntBlastGuard.java", 1, 299)], ["combat", "safety"], [],
 [("call", "promaid.combat.guards", "友军防护", "Friendly guard")]),

("promaid.combat.bombing.pose", "副手动作姿势（共用）", "Held-Pose Prop (shared)",
 "放置/充能/投掷/放烟花的瞬间，副手亮一下对应物品模型。被 11 个互不相关的子系统引用，是隐藏的全局耦合点。",
 "Flashes the matching item model in the offhand at the instant of placing/charging/throwing/boosting. "
 "Referenced by 11 unrelated subsystems — a hidden global coupling point.",
 [("promaid_src_neo/com/maidsmart/combat/BombPose.java", 1, 366),
  ("promaid_src_neo/com/maidsmart/combat/FlightFireworkPose.java", 1, 193)], ["presentation", "shared"], [], []),

("promaid.combat.pink-fire", "粉色火焰", "Pink Fire",
 "把床/重生锚/末地水晶爆炸产生的火渲染成粉色，并附带一层伤害豁免；保证粉色火会熄灭（不蔓延、自己灭、"
 "不点燃任何人）。",
 "Renders the fire left by bed/anchor/crystal blasts pink, with a damage-exemption layer, and guarantees it "
 "goes out — no spread, self-extinguishing, ignites nobody.",
 [("promaid_src_neo/com/maidsmart/combat/PinkFireBlock.java", 1, 436),
  ("promaid_src_neo/com/maidsmart/combat/PinkFireSweep.java", 1, 231)], ["combat", "render"], [], []),

("promaid.combat.tether", "武装拴绳（二号位）", "Armed Leash (Gunner Seat)",
 "把玩家挂在飞行女仆下方（武装直升机二号位），玩家可自由开火并随女仆飞行。粉丝点单道具。",
 "Hangs the player below a flying maid — the gunship door-gunner seat — so the player can fire freely while "
 "being flown around. A fan-requested item.",
 [("promaid_src_neo/com/maidsmart/combat/GunnerTetherManager.java", 1, 1946),
  ("promaid_src_neo/com/maidsmart/combat/GunnerTetherNetworking.java", 1, 113),
  ("promaid_src_neo/com/maidsmart/combat/CombatLeashItem.java", 1, 36)], ["combat", "item"], [], []),

("promaid.combat.ride", "骑乘指挥棒", "Ride Baton",
 "把「已上鞍的坐骑」（原版生物 / 卓越前线载具 / 冰火传说飞龙）与女仆绑定，让她骑着它跟主人走、可攻击可飞行。"
 "顺序固定：先右击坐骑、再右击女仆；左键换座。",
 "Binds an already-saddled mount (vanilla mob / Superb Warfare vehicle / Ice and Fire dragon) to a maid so she "
 "rides it following the owner, and can fight and fly. Order is fixed: right-click the mount first, then the "
 "maid; left-click swaps seats.",
 [("promaid_src_neo/com/maidsmart/combat/RideBindManager.java", 1, 2270),
  ("promaid_src_neo/com/maidsmart/combat/MaidRideKit.java", 1, 534),
  ("promaid_src_neo/com/maidsmart/combat/RideBatonItem.java", 1, 36)], ["combat", "item", "mount"], [],
 [("call", "promaid.combat.mount-compat", "载具兼容", "Mount compat")]),

("promaid.combat.mount-compat", "载具兼容层", "Mount Compatibility Layer",
 "3657 行的兼容模块，实际吞掉了一整个功能：原版坐骑驾驶、卓越前线载具（刹车/炮塔/弹道瞄准）、"
 "冰火传说龙的鞍位挂载。全反射，零硬依赖。",
 "A 3657-line compat module that has swallowed a whole feature: vanilla mount driving, Superb Warfare vehicles "
 "(braking/turret/ballistic aim) and Ice-and-Fire dragon seat mounting. Fully reflective, zero hard "
 "dependencies.",
 [("promaid_src_neo/com/maidsmart/combat/MaidMountCompat.java", 1, 4746),
  ("promaid_src_neo/com/maidsmart/combat/MaidShellHoming.java", 1, 357)], ["combat", "compat"], [],
 [("call", "promaid.combat.flight-raid.air-combat", "空中姿态", "Air posture")]),

("promaid.combat.tactics", "单兵作战战术", "Solo Combat Tactics",
 "核心行为优先级 230 的 PVP 式近战战术接管：举盾时机、位移压制等，替代旧战斗协同。",
 "A PVP-style melee tactics takeover at core-behavior priority 230: shield timing, positional pressure and so "
 "on, replacing the old combat coordination.",
 [("promaid_src_neo/com/maidsmart/combat/MaidCombatTacticsBehavior.java", 1, 952)], ["combat"], [],
 [("call", "promaid.combat.self-preservation", "自保", "Self-preservation"),
  ("call", "promaid.combat.guards", "友军", "Friendly")]),

("promaid.combat.self-preservation", "自保", "Self-Preservation",
 "任何状态下低血且有威胁时，停止手头行为转入自保：喝药/搭高/珍珠/逃跑/记录放置方块。3789 行，"
 "全模组最大的单文件。",
 "At any time, when low on health with a threat present she abandons her current behavior and enters "
 "self-preservation: drink, pillar up, pearl, flee, log placed blocks. 3789 lines — the mod's largest single "
 "file.",
 [("promaid_src_neo/com/maidsmart/combat/SelfPreservationBehavior.java", 1, 4696)], ["combat"], [],
 [("call", "promaid.work.placed-block", "方块回收", "Block reclaim"),
  ("call", "promaid.combat.ride", "骑乘", "Ride")]),

("promaid.combat.maneuvers", "特殊攻击链路", "Special Attack Chains",
 "重锤猛击（仿 vanilla_mob_remake 僵尸）、激流三叉戟旋转突进（推进剂改成拟真烟花）、落地水/落地雪"
 "被动生存技能。",
 "Mace smash (modelled on vanilla_mob_remake zombies), riptide-trident spin charge (propellant switched to "
 "realistic fireworks) and water/powder-snow landing clutch passives.",
 [("promaid_src_neo/com/maidsmart/combat/MaidTridentSpinBehavior.java", 1, 743),
  ("promaid_src_neo/com/maidsmart/combat/MaidRiptideBoost.java", 1, 558),
  ("promaid_src_neo/com/maidsmart/combat/MaidMaceSmashBehavior.java", 1, 482)], ["combat"], [], []),

("promaid.combat.resurrect", "死亡复活与回魂符", "Auto-Resurrect & Soul Spell",
 "女仆死亡 60 秒后墓碑自消并在主人出生点复活（60 秒冷却）；致死伤害且无保命物品时自动收魂符。"
 "含冷却 HUD 广播。",
 "60 seconds after death her tombstone vanishes and she revives at the owner's spawn (60s cooldown); a lethal "
 "hit with no life-saving item auto-consumes a soul charm. Includes cooldown HUD broadcast.",
 [("promaid_src_neo/com/maidsmart/combat/MaidAutoResurrect.java", 1, 591),
  ("promaid_src_neo/com/maidsmart/combat/MaidSoulSpellGuard.java", 1, 361),
  ("promaid_src_neo/com/maidsmart/combat/CooldownHudTracker.java", 1, 157)], ["combat", "lifecycle"], [], []),

("promaid.combat.guards", "友军与环境防护总闸", "Friendly-Fire & Environment Guards",
 "主人与同主女仆免伤、宠物免疫（含 AOE）、友军风免、建造护盾、女仆着火不传主人、模组 TNT 不破坏方块、"
 "不踩坏农田。多为单点守卫，各自 @EventBusSubscriber 自注册。",
 "Owner and same-owner maids take no damage; pet immunity (including AOE); ally wind immunity; build shield; "
 "maid fire does not spread to the owner; modded TNT does not break blocks; farmland is never trampled. Mostly "
 "single-point guards, each self-registering via @EventBusSubscriber.",
 [("promaid_src_neo/com/maidsmart/combat/FriendlyFireGuard.java", 1, 172),
  ("promaid_src_neo/com/maidsmart/combat/FriendlyWindGuard.java", 1, 270),
  ("promaid_src_neo/com/maidsmart/combat/PetImmunityGuard.java", 1, 207),
  ("promaid_src_neo/com/maidsmart/combat/MaidFireGuard.java", 1, 105)], ["combat", "safety"], [], []),

("promaid.combat.owner-support", "主人贴身辅助", "Owner-Support Passives",
 "按优先级链喂药/治疗/喂食主人；共享盾牌；主人周围黑暗格被动插火把；发现刷怪笼优先插火把。",
 "A priority chain that medicates, heals and feeds the owner; shares her shield; passively lights dark spots "
 "around the owner; and prioritises torching spawners.",
 [("promaid_src_neo/com/maidsmart/combat/MaidAidOwnerBehavior.java", 1, 1859),
  ("promaid_src_neo/com/maidsmart/combat/MaidSpawnerTorchBehavior.java", 1, 650),
  ("promaid_src_neo/com/maidsmart/combat/MaidShieldShareBehavior.java", 1, 146)], ["combat", "support"], [], []),

("promaid.combat.compat", "第三方战斗兼容", "Third-Party Combat Compat",
 "全反射、零硬依赖地适配枪械（TACZ / 卓越前线）、万法皆通法术附属、拔刀剑、暮色森林孔雀羽扇、Curios 鞘翅、"
 "自推鞘翅。各自重写一遍「反射失败到宽松兜底」模板。",
 "Adapts guns (TACZ / Superb Warfare), the spell addon, SlashBlade, the Twilight Forest peacock fan, Curios "
 "elytra and self-propelled wings — all by reflection with zero hard deps. Each re-implements the same "
 "'reflection failed, degrade gracefully' template.",
 [("promaid_src_neo/com/maidsmart/combat/GunCompat.java", 1, 716),
  ("promaid_src_neo/com/maidsmart/combat/MaidSpellCastCompat.java", 1, 727),
  ("promaid_src_neo/com/maidsmart/combat/CuriosElytraCompat.java", 1, 288)], ["compat"], [], []),

# ============================================================== 4. work
("promaid.work", "生产类任务", "Work Tasks",
 "女仆的「职业」：挖矿、伐木、烧制、酿造、宰杀、搭路，以及一整套蓝图建造系统。全部通过 TLM 的 IMaidTask "
 "注册，不自行挂钩。",
 "A maid's professions: mining, woodcutting, smelting, brewing, slaughter, bridging, plus a full "
 "blueprint-building system. All registered through TLM's IMaidTask rather than self-hooking.",
 [("promaid_src_neo/com/maidsmart/task", 1, None)], [], [], []),

("promaid.work.mine", "挖矿", "Mining",
 "按距离平方加深度惩罚减价值 扫最高价值矿，寻路过去逐块挖（原版破坏公式加工具等级门槛加女仆 1.2 倍加成），"
 "连锁挖整条矿脉；临时垫脚方块事后回收。2338 行。",
 "Scans for the highest-value ore by distance squared plus a depth penalty minus value, paths there and mines "
 "block by block (vanilla break formula, tool-tier gated, 1.2x maid bonus), chain-mining the vein; temporary "
 "scaffold is reclaimed later. 2338 lines.",
 [("promaid_src_neo/com/maidsmart/task/MaidMineBehavior.java", 1, 2845),
  ("promaid_src_neo/com/maidsmart/task/MaidMineTask.java", 1, 74)], ["work"], [],
 [("call", "promaid.work.placed-block", "方块回收", "Block reclaim"),
  ("call", "promaid.work.tool-equip", "自动换工具", "Auto tool")]),

("promaid.work.wood", "伐木", "Woodcutting",
 "与挖矿同构，表换成原木/竹子（logs 标签自动），树叶不挡视线，连锁砍整棵树。注释自述是「完整克隆挖矿」"
 "——2379 行乘 2 的重复。",
 "Isomorphic to mining with the table swapped to logs/bamboo (auto logs tag); leaves do not block line of "
 "sight and the whole tree is felled in a chain. Its own comments admit it is a 'complete clone of mining' — "
 "2379 lines of duplication.",
 [("promaid_src_neo/com/maidsmart/task/MaidWoodBehavior.java", 1, 2951),
  ("promaid_src_neo/com/maidsmart/task/MaidWoodTask.java", 1, 66)], ["work"], [],
 [("call", "promaid.work.mine", "同构来源", "Isomorphic origin"),
  ("call", "promaid.work.placed-block", "方块回收", "Block reclaim")]),

("promaid.work.cook", "烧制", "Smelting & Cooking",
 "按背包内容选炉型（食物选烟熏炉、矿石选高炉），把燃料与可烧物填进附近炉子并收产物；女仆在绑定期间坐着。",
 "Picks the furnace type from inventory contents (food to smoker, ore to blast furnace), fills nearby furnaces "
 "with fuel and smeltables, and collects output; she sits while bound.",
 [("promaid_src_neo/com/maidsmart/task/MaidCookBehavior.java", 1, 1311),
  ("promaid_src_neo/com/maidsmart/task/MaidCookTask.java", 1, 64)], ["work"], [], []),

("promaid.work.brew-task", "酿造任务", "Brewing Task",
 "给酿造台补烈焰粉与材料、收成品药水，100 tick 节奏。配方解析见 system.brew。",
 "Refills brewing stands with blaze powder and ingredients and collects finished potions, on a 100-tick "
 "cadence. Recipe resolution lives in system.brew.",
 [("promaid_src_neo/com/maidsmart/task/MaidBrewBehavior.java", 1, 1189),
  ("promaid_src_neo/com/maidsmart/task/MaidBrewTask.java", 1, 63)], ["work"], [],
 [("call", "promaid.system.brew", "配方", "Recipes")]),

("promaid.work.slaughter", "宰杀", "Livestock Slaughter",
 "按 EntityType 统计附近动物，超过阈值就追上去每 3 秒杀一只（SEEK/CHASE/STRIKE 状态机）。",
 "Counts nearby animals by EntityType; once a group exceeds the threshold she chases and kills one every 3 "
 "seconds (SEEK/CHASE/STRIKE state machine).",
 [("promaid_src_neo/com/maidsmart/task/MaidSlaughterBehavior.java", 1, 278),
  ("promaid_src_neo/com/maidsmart/task/MaidSlaughterTask.java", 1, 71)], ["work"], [], []),

("promaid.work.bridge", "搭路与垫高", "Bridge & Pillar",
 "核心行为优先级 245：从背包拿真方块朝主人方向跨沟/爬墙铺路，含危险方块与威胁排除、以及放置方块回收。"
 "三种模式：空中搭桥、斜向跨步、垂直垫柱。",
 "Core behavior at priority 245: places real blocks from her inventory to path toward the owner across gaps "
 "and up walls, excluding deadly blocks and threats, and reclaiming what she places. Three modes: air bridge, "
 "diagonal step and vertical pillar.",
 [("promaid_src_neo/com/maidsmart/task/BridgeUpBehavior.java", 1, 1300)], ["work"], [],
 [("call", "promaid.work.placed-block", "方块回收", "Block reclaim"),
  ("call", "promaid.core.safety", "选块过滤", "Block filter")]),

("promaid.work.tool-equip", "任务工具自动装备", "Auto Tool Equip",
 "核心行为优先级 200：按当前任务把背包里对口的工具/武器换到真正的主手；不占行为槽。同时也是副手姿势道具"
 "的还原入口。",
 "Core behavior at priority 200: swaps the right tool/weapon for the current task from her backpack into the "
 "real main hand without occupying a behavior slot. Also the restore entry point for held-pose props.",
 [("promaid_src_neo/com/maidsmart/task/MaidToolAutoEquip.java", 1, 934),
  ("promaid_src_neo/com/maidsmart/task/MaidToolAutoEquipBehavior.java", 1, 122)], ["work", "shared"], [], []),

("promaid.work.stroll", "空闲散步", "Idle Stroll",
 "核心行为优先级 50：给空闲女仆一个散步目标（TLM 原生散步概率约 8e-6/tick，基本不动）；遵守工作/战斗/"
 "禁足边界。MaidStrollCheck 仅为一条速度投诉的调试命令而存在。",
 "Core behavior at priority 50: gives idle maids a stroll target, since TLM's native stroll chance is about "
 "8e-6/tick (i.e. she never moves). Respects work/combat/restriction bounds. MaidStrollCheck exists purely to "
 "back a debug command for a speed complaint.",
 [("promaid_src_neo/com/maidsmart/task/MaidStrollBehavior.java", 1, 240),
  ("promaid_src_neo/com/maidsmart/task/MaidStrollCheck.java", 1, 413)], ["work", "idle"], [], []),

("promaid.work.planting", "随手种树", "Opportunistic Replanting",
 "独立 ServerTick 模块（不是大脑行为）：女仆在伐木任务时每 20 tick 捡附近树苗，在合法泥土上按树干净空判定"
 "种一棵。",
 "A standalone ServerTick module rather than a brain behavior: on the woodcut task she picks up nearby saplings "
 "every 20 ticks and plants one on valid dirt after a trunk-clearance check.",
 [("promaid_src_neo/com/maidsmart/task/MaidPlanting.java", 1, 755)], ["work"], [], []),

("promaid.work.placed-block", "搭方块统一回收", "Placed-Block Reclaim",
 "把每个放置的脚手架方块与其放置者 UUID 绑定；含寿命到期、回魂暂停、跨维度归还背包、崩溃后安全清理。"
 "四个主人共用：挖矿/伐木/搭路/自保。",
 "Binds every scaffold block to its placing maid's UUID with lifetime expiry, soul-spell pause, "
 "cross-dimension return to backpack and crash-safe startup cleanup. Four owners share it: mining, "
 "woodcutting, bridging and self-preservation.",
 [("promaid_src_neo/com/maidsmart/task/PlacedBlockTracker.java", 1, 437)], ["work", "shared"], [], []),

("promaid.work.tags", "站桩标记（跨切面）", "Work-Still Tagging (cross-cutting)",
 "persistentData 标记（WORK_STILL_TAG 与 BUILD_SIT_TAG）告诉 mixin 抑制移动目标下沉；被 17 处 mixin 引用。"
 "是本包最重要的集成契约。",
 "persistentData flags (WORK_STILL_TAG and BUILD_SIT_TAG) tell mixins to suppress MoveToTargetSink; "
 "referenced by 17 mixin sites. The single most important integration contract in the package.",
 [("promaid_src_neo/com/maidsmart/task/MaidWorkTags.java", 1, 195)], ["work", "contract"], [], []),

("promaid.work.build-plan", "建造计划数据层", "Build Plan Data Model",
 "计划格式是字符串表：首元素记录原点与蓝图名/id，其余记录相对坐标与方块。多区域 planId 到 PlanState，"
 "含原点/维度/朝向 quarter/光标/延迟重试表/已放置集；按维度持久化到 BuildArchive。BuildPlan 暴露约 40 个 "
 "public static，是典型上帝对象。",
 "The plan format is a string list: the first element records the origin plus blueprint name/id, the rest record "
 "relative coordinates and blocks. Multi-region planId to PlanState holds origin, dimension, rotation quarters, "
 "cursor, deferred-retry map and placed sets; persisted per dimension via BuildArchive. BuildPlan exposes about "
 "40 public statics — a textbook god object.",
 [("promaid_src_neo/com/maidsmart/build/BuildPlan.java", 1, 1045),
  ("promaid_src_neo/com/maidsmart/build/BuildArchive.java", 1, 176)], ["build"], [], []),

("promaid.work.build-exec", "建造执行", "Build Execution",
 "运行时核心：解析、重叠拒绝、障碍告警、已建扫描、材料预检、建或续计划（原点取玩家脚下、不需要女仆），"
 "以及女仆按光标每 N tick 放一块（四档速度）。含传送到位、缺料向主人取、TNT 点火守卫、区块重发、缝隙扫描、"
 "替代方块、TPS 自适应节奏。",
 "The runtime core: parse, reject overlaps, warn obstacles, scan prebuilt, precheck materials, create or resume "
 "the plan (origin at the player's feet, no maid required), plus a maid placing one block per N ticks (four "
 "speed tiers). Includes teleport-to-site, fetching missing material from the owner, TNT ignition guarding, "
 "chunk resend, gap scanning, alternative blocks and TPS-adaptive pacing.",
 [("promaid_src_neo/com/maidsmart/build/MaidBuildBehavior.java", 1, 1951),
  ("promaid_src_neo/com/maidsmart/build/BlueprintBuildExecutor.java", 1, 254),
  ("promaid_src_neo/com/maidsmart/build/MaidBuildTask.java", 1, 70)], ["build"], [],
 [("call", "promaid.work.build-plan", "计划", "Plan"),
  ("call", "promaid.work.blueprint-lib", "蓝图库", "Blueprint lib")]),

("promaid.work.blueprint-lib", "蓝图库门面", "Blueprint Facade",
 "570 行门面：静态访问器加 73 个一行转发到 14 个拆分实现类（v1.2.4 拆分）。拆分只做了一半——门面里仍混着"
 "真实逻辑，且 14 个类都带着同一段用不上的 import。",
 "A 570-line facade: static accessors plus 73 one-line delegations to 14 split implementation classes (split in "
 "v1.2.4). The split is half-done — real logic still sits in the facade, and all 14 classes carry the same "
 "unused import block.",
 [("promaid_src_neo/com/maidsmart/build/BlueprintLib.java", 1, 570)], ["build"], [], []),

("promaid.work.blueprint-lib.math", "蓝图几何与步骤", "Blueprint Geometry & Steps",
 "解析/旋转/居中/去重/裁剪/尺寸缓存；步骤是唯一权威内存形态，litematic 与 snbt 都先转成它。",
 "Parse/rotate/center/dedupe/trim/size caching. The step list is the single authoritative in-memory form — "
 "both litematic and SNBT are converted into it first.",
 [("promaid_src_neo/com/maidsmart/build/BlueprintStepMath.java", 1, 557),
  ("promaid_src_neo/com/maidsmart/build/BlueprintProjectionSampler.java", 1, 131)], ["build"], [], []),

("promaid.work.blueprint-lib.materials", "材料核算", "Material Accounting",
 "需求计数、缺口、玩家/女仆/合并持有、材料到物品的映射与转移（缺口计算、玩家缺口、合并持有等 5 个以上"
 "近同构变体）。",
 "Needs counting, shortfall, player/maid/combined holdings, material-to-item mapping and transfer — with five "
 "or more near-isomorphic variants.",
 [("promaid_src_neo/com/maidsmart/build/BlueprintMaterials.java", 1, 584)], ["build"], [], []),

("promaid.work.blueprint-lib.placement", "放置规则", "Placement Rules",
 "支撑方向、虚空重力、红石重算、已建判定、门缝补齐、障碍预检。门面之外零外部调用者。",
 "Support direction, void gravity, redstone recalculation, built-equivalence checks, door-gap filling and "
 "obstacle prechecks. Zero callers outside the facade.",
 [("promaid_src_neo/com/maidsmart/build/BlueprintPlacement.java", 1, 681)], ["build"], [], []),

("promaid.work.blueprint-lib.tables", "方块与命名表", "Block & Name Tables",
 "禁用/不可破坏/地形/合法地面/白名单/等价组等硬编码表，1.12 数字 id 映射，约 180 条硬编码中文名表。",
 "Hardcoded tables for forbidden/unbreakable/terrain/valid-ground/whitelist/equivalent groups, the 1.12 "
 "numeric-id mapping, and a ~180-entry hardcoded Chinese name table.",
 [("promaid_src_neo/com/maidsmart/build/BlueprintBlockData.java", 1, 284),
  ("promaid_src_neo/com/maidsmart/build/BlueprintLegacyIds.java", 1, 947),
  ("promaid_src_neo/com/maidsmart/build/BlueprintNames.java", 1, 303)], ["build", "data"], [], []),

("promaid.work.blueprint-lib.io", "蓝图文件 IO", "Blueprint File I/O",
 "扫描 config/maid_smart/blueprints 与 schematics 外部目录，7 种扩展名，zip 导入，内置蓝图解包与预热。"
 "内置程序化生成器自 v1.5.387 起已不再被目录枚举，只可硬编码 id 触达（休眠代码）。",
 "Scans the external config/maid_smart/blueprints and schematics directories, 7 extensions, zip import and "
 "bundled-blueprint unpacking with warmup. The procedural built-in generator has not been enumerated by the "
 "catalog since v1.5.387 and is reachable only by hardcoded id (dormant code).",
 [("promaid_src_neo/com/maidsmart/build/BlueprintFileIo.java", 1, 725),
  ("promaid_src_neo/com/maidsmart/build/BlueprintCatalog.java", 1, 427),
  ("promaid_src_neo/com/maidsmart/build/BuiltinHouses.java", 1, 1385)], ["build", "io"], [], []),

("promaid.work.blueprint-formats", "蓝图格式解析", "Blueprint Format Parsing",
 "litematic：遍历 Regions，解包位压缩 BlockStates（bits 取调色板大小的对数，最少 2），合并分区调色板，"
 "TileEntities 转方块实体 SNBT。schem（Sponge）与 schematic（MCEdit varint）同口径归一化。",
 "litematic: walks Regions, unpacks bit-packed BlockStates (bits = log2 palette, min 2), merges per-region "
 "palettes and maps TileEntities to block-entity SNBT. schem (Sponge) and schematic (MCEdit varint) normalise "
 "to the same shape.",
 [("promaid_src_neo/com/maidsmart/build/BlueprintStructureCodec.java", 1, 300),
  ("promaid_src_neo/com/maidsmart/build/BlueprintWorldExtract.java", 1, 397)], ["build"], [], []),

("promaid.work.blueprint-ui", "蓝图手册界面", "Blueprint Book UI",
 "客户端 Screen（无 Menu 容器），6 个视图：主页/建造目录/材料明细/女仆管理/女仆详情/区域女仆；暂停恢复与"
 "工头控制，每 2 秒轮询进度。2068 行单类。",
 "A client Screen with no Menu container and 6 views: home, build catalog, material detail, maid management, "
 "maid detail and region maids; pause/resume and foreman control, polling progress every 2 seconds. 2068 lines "
 "in a single class.",
 [("promaid_src_neo/com/maidsmart/build/BlueprintBookScreen.java", 1, 2139),
  ("promaid_src_neo/com/maidsmart/build/BlueprintBookItem.java", 1, 93)], ["build", "ui", "client"], [],
 [("call", "promaid.guide", "手册", "Guide"),
  ("call", "promaid.core.config-panel", "配置跳转", "Config jump")]),

("promaid.work.blueprint-net", "蓝图网络包", "Blueprint Networking",
 "目录 S2C、选蓝图 C2S、建造控制、进度、记忆/调试/语音/世界导入等约 25 个自定义包，塞在两个 god-file"
 "（919 加 735 行）里，各带一份重复的 type 样板。",
 "About 25 custom payloads — catalog S2C, blueprint select C2S, build control, progress, memory/debug/voice/"
 "world-import — crammed into two god files (919 plus 735 lines), each with duplicated type() boilerplate.",
 [("promaid_src_neo/com/maidsmart/build/BlueprintBookBuildPackets.java", 1, 984),
  ("promaid_src_neo/com/maidsmart/build/BlueprintBookEntityPackets.java", 1, 818),
  ("promaid_src_neo/com/maidsmart/build/BlueprintBookNetworking.java", 1, 770)], ["build", "net"], [], []),

("promaid.work.blueprint-ghost", "建造投影与区域框", "Ghost Projection & Region Boxes",
 "客户端只读渲染：每个计划一个红色固定区域框（名字加创建坐标）、计划原点的橙色幽灵方块、确认流程里跟随"
 "玩家的金色预览与青色幽灵。点云由服务端采样、壳过滤、缓存。",
 "Client-only rendering: a red fixed region box per plan (name plus creation coords), orange ghost blocks at "
 "the plan origin, and a gold player-following preview with cyan ghosts during the confirmation flow. The point "
 "cloud is sampled, shell-filtered and cached server-side.",
 [("promaid_src_neo/com/maidsmart/build/BlueprintAreaPreview.java", 1, 596),
  ("promaid_src_neo/com/maidsmart/build/BuildHudRenderer.java", 1, 228)], ["build", "render", "client"], [], []),

("promaid.work.index-stone", "指标石", "Index Stone",
 "物品加 512 格长射线锁块加女仆绑定；两者齐备时，女仆起点与锁定块之间的所有空气变成一次性临时桥，她靠"
 "传送逐格填。客户端与服务端共用同一套 IndexStonePlan 几何。",
 "An item plus a 512-block raycast block lock plus a maid binding: when both are set, every air block between "
 "her start point and the locked block becomes a one-shot temporary bridge she fills by teleporting. Client and "
 "server share the same IndexStonePlan geometry.",
 [("promaid_src_neo/com/maidsmart/build/IndexStoneService.java", 1, 726),
  ("promaid_src_neo/com/maidsmart/build/IndexStoneBuildBehavior.java", 1, 388),
  ("promaid_src_neo/com/maidsmart/build/IndexStonePreviewClient.java", 1, 409)], ["build", "item"], [],
 [("call", "promaid.work.build-exec", "放置逻辑", "Placement"),
  ("call", "promaid.work.placed-block", "回收", "Reclaim")]),

("promaid.work.farm", "锄地驱动", "Farmland Till Driver",
 "ServerTick 模块：若「曾是耕地」的标记存在且该方块已不再是耕地，女仆在 5 乘 5 内把它锄回来。标记持久化在 "
 "SavedData；耕作本体已回退原版 TLM。",
 "A ServerTick module: if a 'was farmland' mark exists and the block is no longer farmland, she tills it back "
 "within 5x5. Marks persist in SavedData; the farming action itself was reverted to vanilla TLM.",
 [("promaid_src_neo/com/maidsmart/build/FarmTillDriver.java", 1, 399),
  ("promaid_src_neo/com/maidsmart/build/FarmSweepCache.java", 1, 198),
  ("promaid_src_neo/com/maidsmart/build/FarmlandMarkStore.java", 1, 88)], ["work", "farm"], [], []),

("promaid.work.ai-tools", "建造 AI 工具", "AI Build Tools",
 "给 LLM 用的三件：smart_build（LLM 生成 JSON 蓝图，含白名单与上限，整建或部分）、smart_build_list"
 "（目录查询）、smart_design（子代理建筑师，存蓝图后走常规建造流）。",
 "Three tools for the LLM: smart_build (LLM-generated JSON blueprint with whitelist and limits, full or "
 "partial), smart_build_list (catalog query) and smart_design (sub-agent architect, saved blueprint, then the "
 "normal build flow).",
 [("promaid_src_neo/com/maidsmart/build/SmartBuildTool.java", 1, 407),
  ("promaid_src_neo/com/maidsmart/build/SmartDesignTool.java", 1, 333),
  ("promaid_src_neo/com/maidsmart/build/SmartBuildListTool.java", 1, 78)], ["build", "ai"], [],
 [("call", "promaid.work.blueprint-lib", "蓝图库", "Blueprint lib")]),

("promaid.work.fishing", "钓鱼自动坐垫", "Fishing Auto-Chair",
 "钓鱼任务找不到椅子/船时，扫 8 格内的空垫或水域岸边生成带标记的 TLM 坐垫并强制骑乘；每秒清理失效坐垫；"
 "被摧毁无掉落。",
 "When the fishing task finds no chair or boat, she scans 8 blocks for a free cushion or a shoreline spot and "
 "spawns a marked TLM cushion she is forced onto; stale cushions are swept each second and drop nothing when "
 "destroyed.",
 [("promaid_src_neo/com/maidsmart/fishing/FishingChairService.java", 1, 501),
  ("promaid_src_neo/com/maidsmart/fishing/PlayerWaterLog.java", 1, 73)], ["work", "compat"], [], []),

# ============================================================== 5. flight
("promaid.flight", "飞行", "Flight",
 "独立于空袭战斗的飞行能力层：仿创造模式自由飞行。空袭/扫帚的战斗飞行见 combat 层。",
 "The flight-capability layer independent of air-raid combat: creative-like free flight. Combat flight lives in "
 "the combat layer.",
 [("promaid_src_neo/com/maidsmart/flight", 1, None)], [], [], []),

("promaid.flight.free", "仿创造飞行", "Creative-Like Free Flight",
 "女仆携带特定物品/效果/零重力资格时获得悬停与平滑 3D 移动：关重力加每 tick 一阶速度（按距离算、永不累积），"
 "松开时软着陆，平滑偏航与后撤朝向，逐女仆开关（磁盘持久加 S2C 缓存）。刻意做成事件驱动而非大脑行为——"
 "注释记载 core behavior 实例化后其起始判据从不被调用。",
 "When she carries the right item/effect/zero-gravity qualification she gains hover and smooth 3D movement: "
 "gravity off plus a per-tick first-order velocity computed from distance and never accumulated, soft landing "
 "on release, smoothed yaw and retreat facing, and a per-maid toggle (disk-backed plus S2C cached). "
 "Deliberately event-driven rather than a brain behavior — the comments record that a core behavior instance "
 "never got its start-condition check called.",
 [("promaid_src_neo/com/maidsmart/flight/MaidFreeFlightController.java", 1, 1402),
  ("promaid_src_neo/com/maidsmart/flight/MaidFreeFlightKit.java", 1, 273),
  ("promaid_src_neo/com/maidsmart/flight/MaidFreeFlightFlags.java", 1, 167)], ["flight"], [], []),

("promaid.flight.free.handlers", "飞行事件桥与网络", "Flight Event Bridge & Networking",
 "女仆 tick 事件桥接、逐女仆开关包、客户端按键与动画状态。",
 "The maid-tick event bridge, per-maid toggle packets, client keybinds and animation state.",
 [("promaid_src_neo/com/maidsmart/flight/MaidFreeFlightHandler.java", 1, 25),
  ("promaid_src_neo/com/maidsmart/flight/MaidFreeFlightNetworking.java", 1, 153),
  ("promaid_src_neo/com/maidsmart/flight/MaidFreeFlightAnimState.java", 1, 59)], ["flight", "net"], [], []),

# ============================================================== 6. social
("promaid.social", "社交与心智", "Social & Mind",
 "记忆、人格、情绪、对话、语音五层构成的「她像个人」的那部分。移植自 maidsoulcore 与 Sphantosis。",
 "The 'she feels like a person' half: memory, persona, affect, dialogue and voice, ported from maidsoulcore "
 "and Sphantosis.",
 [("promaid_src_neo/com/maidsmart/memory", 1, None)], [], [], []),

("promaid.social.memory", "长期记忆", "Long-term Memory",
 "每女仆的长期记忆库：事件源注入加后台 LLM 提取加五表 jsonl 落盘（段落/实体/关系/片段/画像）加多级日记式"
 "索引加睡醒或登出自动归档；对话时只注入短投影。",
 "A per-maid long-term memory store: event-source injection, background LLM extraction, five jsonl tables "
 "(paragraphs/entities/relations/episodes/profiles), multi-level diary-style indexing and auto-archival on "
 "wake or logout — with only a short projection injected into chat.",
 [("promaid_src_neo/com/maidsmart/memory/AiMemoryStore.java", 1, 998),
  ("promaid_src_neo/com/maidsmart/memory/AiMemoryExtractor.java", 1, 636),
  ("promaid_src_neo/com/maidsmart/memory/AiMemoryArchiver.java", 1, 597)], ["memory", "ai"], [], []),

("promaid.social.memory.index", "记忆索引与检索", "Memory Index & Recall",
 "日记式索引、混合召回与 RRF 融合、跳过表（移植自 Sphantosis 的 cognitive/composite/skiplist_index.py）。",
 "Diary-style index, hybrid recall with RRF fusion, and a skip list (ported from Sphantosis's "
 "cognitive/composite/skiplist_index.py).",
 [("promaid_src_neo/com/maidsmart/memory/AiMemoryIndexStore.java", 1, 171),
  ("promaid_src_neo/com/maidsmart/memory/AiMemorySearch.java", 1, 221),
  ("promaid_src_neo/com/maidsmart/memory/AiMemorySkipList.java", 1, 126)], ["memory"], [], []),

("promaid.social.memory.tools", "记忆 LLM 工具", "Memory LLM Tools",
 "让 LLM 自己读写记忆的四件工具：查询记忆、查询索引、记住、工作笔记。",
 "Four tools letting the LLM read and write memory itself: query memory, query index, remember and working note.",
 [("promaid_src_neo/com/maidsmart/memory/RememberTool.java", 1, 145),
  ("promaid_src_neo/com/maidsmart/memory/QueryMemoryIndexTool.java", 1, 140),
  ("promaid_src_neo/com/maidsmart/memory/WorkingNoteTool.java", 1, 131)], ["memory", "ai"], [], []),

("promaid.social.persona", "角色包", "Persona Package",
 "每女仆的稳定人格种子（角色/特质/核心记忆三份文件），只读投影进提示词；聊天记忆永不改写人格；首启从 jar "
 "资源生成模板。",
 "A per-maid stable persona seed (persona/traits/core-memories files), projected read-only into the prompt. "
 "Chat memory never rewrites persona; templates are generated from jar resources on first launch.",
 [("promaid_src_neo/com/maidsmart/persona/PersonaPackage.java", 1, 344)], ["memory", "ai"], [], []),

("promaid.social.affect", "PAD 情绪层", "PAD Affect Layer",
 "独立第四套数值（愉悦/唤醒/支配 加 亲密度/冲突/思念 加 受伤债/修复债），事件驱动回落，"
 "作为情感上下文注入对话影响语气；落盘 affect.json。",
 "A fourth independent numeric layer (pleasure/arousal/dominance plus intimacy/conflict/longing and "
 "hurt-debt/repair-debt) that decays in response to events and is injected as the affect context to tint "
 "conversation; persisted to affect.json.",
 [("promaid_src_neo/com/maidsmart/affect/AffectManager.java", 1, 246),
  ("promaid_src_neo/com/maidsmart/affect/AffectEventHooks.java", 1, 89)], ["affect", "ai"], [], []),

("promaid.social.emotion", "亲昵互动与姿势", "Affection Interactions & Poses",
 "按键 G 摸头 / H 抱抱，给好感加成、心形粒子与姿势动画；服务端校验视线/距离/主人，冷却 8 秒与 30 秒。"
 "姿势靠 mixin 覆盖 TLM 最终骨骼层。",
 "Keys G (headpat) and H (hug) grant favour, heart particles and a pose animation; the server checks line of "
 "sight, distance and ownership, with 8s and 30s cooldowns. Poses are applied by overriding TLM's final bone "
 "layer via mixin.",
 [("promaid_src_neo/com/maidsmart/emotion/EmotionNetworking.java", 1, 231),
  ("promaid_src_neo/com/maidsmart/emotion/EmotionPoseState.java", 1, 53)], ["social", "client"], [], []),

("promaid.social.dialogue", "对话与自主决策", "Dialogue & Autonomy",
 "LLM 对话周边层：主动对话 7 阶段状态机、回复反馈学习、世界感知气泡、自主切换任务、跨轮工作清单、工作播报、"
 "世界探查工具。",
 "The layer around LLM chat: a 7-stage proactive dialogue state machine, reply-feedback learning, perception "
 "bubbles, autonomous task switching, a cross-turn work list, work reporting and world-probe tools.",
 [("promaid_src_neo/com/maidsmart/dialogue/ProactiveDialogueManager.java", 1, 605),
  ("promaid_src_neo/com/maidsmart/dialogue/ProactiveStage.java", 1, 53),
  ("promaid_src_neo/com/maidsmart/dialogue/AutonomousTaskManager.java", 1, 119)], ["dialogue", "ai"], [],
 [("call", "promaid.social.memory", "记忆", "Memory"),
  ("call", "promaid.social.affect", "情绪", "Affect")]),

("promaid.social.dialogue.perception", "世界感知与探查", "Perception & World Probe",
 "每秒快照 diff 出规则气泡；ASCII 空间网格/地形/建造点/方块/实体扫描（由 PatchouliAI 移植）。",
 "A per-second snapshot diff driving rule-based bubbles, plus ASCII spatial-grid/terrain/build-site/block/entity "
 "scanning (ported from PatchouliAI).",
 [("promaid_src_neo/com/maidsmart/dialogue/PerceptionManager.java", 1, 369),
  ("promaid_src_neo/com/maidsmart/dialogue/WorldProbe.java", 1, 597),
  ("promaid_src_neo/com/maidsmart/dialogue/PerceptionQueryTool.java", 1, 161)], ["dialogue", "ai"], [], []),

("promaid.social.dialogue.worklist", "工作清单与播报", "Work List & Reporting",
 "跨轮次的工作清单与工作状态播报工具，让 LLM 记得自己交代过什么。",
 "A cross-turn work list and a work-status reporting tool, so the LLM remembers what it asked for.",
 [("promaid_src_neo/com/maidsmart/dialogue/WorkListTool.java", 1, 288),
  ("promaid_src_neo/com/maidsmart/dialogue/WorkStatusReporter.java", 1, 175),
  ("promaid_src_neo/com/maidsmart/dialogue/MaidWorkList.java", 1, 122)], ["dialogue", "ai"], [], []),

("promaid.social.action", "情绪动作与物品交互", "Emotional Actions & Item Use",
 "主动对话触发时的游戏内动作（走到或看向主人、心形粒子、递食）；喂水软兼容 Thirst；按「剩余次数」区分的"
 "物品用途键。",
 "In-world actions triggered by proactive dialogue (walk to or look at the owner, heart particles, offer food); "
 "soft Thirst compat for water; and per-remaining-use item-purpose keys.",
 [("promaid_src_neo/com/maidsmart/action/EmotionalActionExecutor.java", 1, 300),
  ("promaid_src_neo/com/maidsmart/action/ThirstCompat.java", 1, 248),
  ("promaid_src_neo/com/maidsmart/action/ItemUses.java", 1, 186)], ["social", "compat"], [], []),

("promaid.social.voice", "语音与 TTS", "Voice & TTS",
 "所有系统气泡的朗读。四级来源优先级：jar 内置日语包、磁盘系统语音包、语音缓存、TLM TTS 合成落盘。"
 "173 个音频文件约 5.7 MB。",
 "Reads out every system bubble. Four-level source priority: the jar-bundled Japanese pack, the on-disk system "
 "voice pack, the voice cache, then TLM TTS synthesis written to disk. 173 audio files, about 5.7 MB.",
 [("promaid_src_neo/com/maidsmart/voice/SystemTTSManager.java", 1, 324),
  ("promaid_src_neo/com/maidsmart/voice/SystemVoicePack.java", 1, 201),
  ("promaid_src_neo/com/maidsmart/voice/JarVoicePack.java", 1, 142)], ["voice", "audio"], [], []),

("promaid.social.soul", "灵魂记忆路由（兼容残留）", "Soul Memory Routing (legacy residue)",
 "旧版绑定过灵魂核心的女仆，记忆继续读写全局 souls 目录；新女仆走世界目录。灵魂核心本体已在 v1.5.251e 移除，"
 "此处只剩路由兼容。",
 "Maids bound to the old soul core keep reading and writing the global souls directory; new maids use the world "
 "directory. The soul core itself was removed in v1.5.251e — only the routing shim remains.",
 [("promaid_src_neo/com/maidsmart/soul/SoulBindingService.java", 1, 68)], ["memory", "legacy"], [], []),

# ============================================================== 7. system
("promaid.system", "系统服务", "System Services",
 "维持世界与女仆之间那层「别丢、别卡、别漏」的底层服务：区块加载、跨维跟随、排班、保护、压缩盒、酿造、Goety。",
 "The plumbing that keeps maids from getting lost, stuck or leaked: chunk loading, cross-dimension follow, "
 "scheduling, protection, the compression box, brewing and Goety.",
 [("promaid_src_neo/com/maidsmart/follow", 1, None)], [], [], []),

("promaid.system.follow", "区块加载与跨维跟随", "Chunk Loading & Cross-Dimension Follow",
 "每 5 秒给每个在职女仆所在区块挂实体 tick 级别的票，按区块共享引用计数与票龄守卫；跨维度用原版传送跟随；"
 "推进未加载女仆的召唤/召回队列；为排班女仆持久化票据；并自愈「服务端活着、客户端幽灵」的可见性。1666 行，"
 "仍把票据/跟随/队列/可见性混在一起。",
 "Every 5 seconds keeps an entity-ticking ticket on each active maid's chunk, with per-chunk refcounted sharing "
 "and a ticket-age guard; follows across dimensions via vanilla teleport; advances the summon/recall queue for "
 "unloaded maids; persists tickets for scheduled maids; and self-heals the 'alive server-side, ghost "
 "client-side' state. 1666 lines, still mixing tickets, follow, queue and visibility in one class.",
 [("promaid_src_neo/com/maidsmart/follow/MaidChunkLoadManager.java", 1, 2090)], ["system"], [],
 [("call", "promaid.system.visibility", "可见性", "Visibility")]),

("promaid.system.work-area", "工作圈钳制", "Work-Area Clamp",
 "把 TLM 的工作圆判定统一给所有自定义行为的目标选择用，免得女仆在自定义任务里乱走。被 37 处调用。",
 "Unifies TLM's work-circle predicate for every custom behavior's target selection, so she does not wander "
 "during custom tasks. Called from 37 sites.",
 [("promaid_src_neo/com/maidsmart/follow/WorkAreaClamp.java", 1, 162)], ["system", "contract"], [], []),

("promaid.system.visibility", "可见性自愈", "Visibility Self-Heal",
 "每 tick 检查服务端活着的女仆是否真的被追踪与可见，出现客户端幽灵状态就重发实体生成包（根因是区块卸载时 "
 "ChunkMap 移除实体）。",
 "Per-tick check that a live server-side maid is actually tracked and visible, re-sending spawn packets whenever "
 "the client-side ghost state appears (root cause: ChunkMap removing the entity on chunk unload).",
 [("promaid_src_neo/com/maidsmart/follow/MaidVisibilityGuard.java", 1, 119)], ["system"], [], []),

("promaid.system.home", "Home 巡逻与工作驱动", "Home Patrol & Work Movement",
 "每 4 秒给非工作的 home 模式女仆一个家锚点附近的随机走动目标，让她不再呆立；另有每 5 tick 的直连导航驱动，"
 "只服务 home 模式与宰杀任务（农耕在 v1.2.4 明确移除），完全绕过 TLM 大脑活动。",
 "Every 4 seconds gives a non-working home-mode maid a random walk target near her home anchor so she stops "
 "standing still; a separate 5-tick direct-navigation driver serves only home mode and the slaughter task "
 "(farming was explicitly removed in v1.2.4), bypassing the TLM brain entirely.",
 [("promaid_src_neo/com/maidsmart/follow/HomePatrolHandler.java", 1, 116),
  ("promaid_src_neo/com/maidsmart/follow/HomeWorkMovementDriver.java", 1, 147)], ["system"], [], []),

("promaid.system.schedule", "排班表", "Schedule System",
 "排班表物品右键打开界面：选女仆、选班次（早班/晚班/全天）、6 个任务槽排一天；底层存时段段表，按游戏挂钟"
 "每分钟扫描自动切任务。启用排班时会强制开 home 模式并自动锚定家位置（治「呆立」）。",
 "The schedule book item opens a UI: pick maids, pick a shift (day/night/all), fill 6 task slots across a day. "
 "Underneath it stores segments and scans the world clock every minute to switch tasks. Enabling a schedule "
 "forces home mode on and auto-anchors the home position (fixes the 'stands frozen' bug).",
 [("promaid_src_neo/com/maidsmart/schedule/ScheduleManager.java", 1, 387),
  ("promaid_src_neo/com/maidsmart/schedule/ScheduleData.java", 1, 275),
  ("promaid_src_neo/com/maidsmart/schedule/ScheduleBookScreen.java", 1, 1167)], ["system", "ui"], [],
 [("call", "promaid.combat.auto-switch", "优先级让位", "Priority concession"),
  ("call", "promaid.combat.self-preservation", "优先级让位", "Priority concession")]),

("promaid.system.schedule.switch", "任务切换引擎", "Task Switch Engine",
 "唯一的任务/日程写入出口，带分层闸门与守卫；任务可用性判定做硬性开关检查。",
 "The single exit for writing task/schedule changes, with layered gates and guards; task availability applies "
 "the hard enable check.",
 [("promaid_src_neo/com/maidsmart/schedule/ScheduleSwitchEngine.java", 1, 176),
  ("promaid_src_neo/com/maidsmart/schedule/ScheduleTaskAvailability.java", 1, 296),
  ("promaid_src_neo/com/maidsmart/schedule/ScheduleSwitchGuard.java", 1, 73)], ["system"], [], []),

("promaid.system.schedule.net", "排班网络包", "Schedule Networking",
 "三条包文件：登记、计划数据、女仆操作。v1.2.4 从原网络类拆出，编解码逐字未动，三个文件共享同一段 21 行 "
 "import 头——典型的复制粘贴拆分。",
 "Three packet files: registration, plan data and maid operations. Split out of the original networking class in "
 "v1.2.4 with encode/decode copied verbatim — all three share the same 21-line import header, a textbook "
 "copy-paste split.",
 [("promaid_src_neo/com/maidsmart/schedule/SchedulePacketsPlan.java", 1, 640),
  ("promaid_src_neo/com/maidsmart/schedule/SchedulePacketsMaid.java", 1, 616),
  ("promaid_src_neo/com/maidsmart/schedule/ScheduleNetworking.java", 1, 274)], ["system", "net"], [], []),

("promaid.system.remote-gui", "远程女仆界面", "Remote Maid GUI",
 "靠改写原版区块追踪的配对与可见集合实现真远程开界面，深度反射、多版本重写，是本仓库最脆弱的一处。",
 "Implements genuinely remote GUI opening by rewriting vanilla chunk-tracking pairing and visibility sets — "
 "deeply reflective, rewritten across several versions, and the most fragile spot in the repo.",
 [("promaid_src_neo/com/maidsmart/schedule/RemoteMaidGui.java", 1, 252),
  ("promaid_src_neo/com/maidsmart/schedule/RemoteTrackBridge.java", 1, 54)], ["system", "net"], [], []),

("promaid.system.protect", "保护与险境", "Protection & Hazard",
 "主人死亡瞬间无条件立即传送所有女仆到其重生点（跨维度、强载未加载区块）；危险方块寻路避让；"
 "站在岩浆或火上的女仆每 0.5 秒巡检挪到安全格并灭火。",
 "The instant the owner dies, teleport every maid to their respawn point unconditionally (across dimensions, "
 "force-loading chunks); pathfinding avoidance for dangerous blocks; and a 0.5s sweep that moves maids standing "
 "in lava or fire to a safe cell and extinguishes them.",
 [("promaid_src_neo/com/maidsmart/protect/MasterDeathTeleportHandler.java", 1, 619),
  ("promaid_src_neo/com/maidsmart/protect/DangerEscapeHandler.java", 1, 195),
  ("promaid_src_neo/com/maidsmart/protect/MaidDangerMalusHandler.java", 1, 146)], ["system", "safety"], [],
 [("call", "promaid.system.follow", "强载区块", "Force-load")]),

("promaid.system.box", "压缩盒", "Compression Box",
 "5 格、每格最多 11 万多的收纳道具；右键箱子式界面鼠标取放；放进女仆背包即其背包延伸；禁入压缩盒与附魔类"
 "（不可堆叠带组件）防消失。压缩盒自测是住在主源码里的 702 行假玩家校验。",
 "A 5-slot container holding over a hundred thousand items per slot, with a chest-like click UI; placing it in a "
 "maid's backpack extends her inventory. Compression boxes and enchanted items are barred (unstackable with "
 "components) to prevent loss. The box self-test is a 702-line fake-player check living in main source.",
 [("promaid_src_neo/com/maidsmart/box/CompressionBoxService.java", 1, 544),
  ("promaid_src_neo/com/maidsmart/box/CompressionBoxData.java", 1, 339),
  ("promaid_src_neo/com/maidsmart/box/CompressionBoxMaidInv.java", 1, 315)], ["system", "item"], [], []),

("promaid.system.brew", "酿造配置与配方", "Brewing Config & Recipes",
 "女仆药剂手册物品右键女仆打开配置界面；两种模式——批量（有什么酿什么）与定向（按目标药水配方链精确下料，"
 "缺料等待）。配方解析靠反射读原版酿造表。",
 "The brew manual item opens a config GUI on right-click: batch mode (brew whatever materials allow) and "
 "directed mode (follow the exact recipe chain for a target potion, waiting on missing ingredients). Recipe "
 "resolution reads vanilla brewing mixes by reflection.",
 [("promaid_src_neo/com/maidsmart/brew/BrewRecipeResolver.java", 1, 291),
  ("promaid_src_neo/com/maidsmart/brew/BrewManualScreen.java", 1, 607),
  ("promaid_src_neo/com/maidsmart/brew/BrewConfig.java", 1, 97)], ["system", "ui"], [], []),

("promaid.system.goety", "诡厄巫法兼容", "Goety Soft Compat",
 "全程反射软兼容。把 Goety 的风系聚晶当第三种飞行（「喷气式」）：到达/跟随/战斗三种任务；自动模式默认关"
 "（主人拉开 16 格起飞、6 格落地带回 TLM 跟随）。两个包根都探。",
 "Fully reflective soft compat. Treats Goety's wind crystal as a third kind of flight ('jet'): arrive/follow/"
 "combat modes; auto mode is off by default (launches when the owner separates 16 blocks, lands at 6 and hands "
 "back to TLM follow). Probes two possible package roots.",
 [("promaid_src_neo/com/maidsmart/goety/MaidGoetyCompat.java", 1, 418),
  ("promaid_src_neo/com/maidsmart/goety/MaidGoetyFlight.java", 1, 561),
  ("promaid_src_neo/com/maidsmart/goety/MaidGoetyAuto.java", 1, 220)], ["system", "compat"], [], []),

# ============================================================== 8. core
("promaid.core", "核心与配置", "Core & Config",
 "配置定义与面板、管理指令、LLM 指挥工具、建构安全过滤器，以及若干薄壳（文件浏览器、工位标记、"
 "第三方模式兼容、提示词追加）。",
 "Config definition and panel, admin commands, LLM command tools, build-safety filters, plus a few thin shells "
 "(file picker, work-position marker, third-party mode compat, prompt appending).",
 [("promaid_src_neo/com/maidsmart/config", 1, None)], [], [], []),

("promaid.core.config", "全模组配置定义", "Mod Config Definition",
 "524 个设置项、22 个分类；字段声明块后跟一个约 1850 行的巨型 static 初始化块逐项重复声明。另有 22 个一次性"
 "迁移标记被当成用户可见设置留在配置里。",
 "524 settings in 22 categories. A field-declaration block is followed by a ~1850-line static initializer that "
 "re-declares every value. Another 22 one-shot migration flags live in user-visible config.",
 [("promaid_src_neo/com/maidsmart/config/MaidSmartConfig.java", 1, 3172)], ["core", "config"], [], []),

("promaid.core.config-panel", "配置面板 GUI", "Config Panel GUI",
 "不是控件对象树，而是手写的立即模式界面：约 12 个嵌套类型（含 7 个分组枚举与 35 个 Section）、约 180 个方法、"
 "59 处控件注册、32 个输入框。render 单方法 543 行，烹饪网格点击 468 行；5 组几乎一样的列表加条目对。"
 "全模组最大文件。",
 "Not a widget object graph but a hand-rolled immediate-mode screen: about 12 nested types (7 group enums, 35 "
 "sections), ~180 methods, 59 widget registrations and 32 text inputs. A single 543-line render and a 468-line "
 "cooking-grid click handler; five near-identical list-plus-entry pairs. The mod's largest file.",
 [("promaid_src_neo/com/maidsmart/config/PromaidConfigScreen.java", 1, 6424)], ["core", "ui", "client"], [], []),

("promaid.core.command", "管理指令", "Admin Commands",
 "命令树：批量召唤、记忆开关、投喂测试、飞行跟随验收、客户端重同步、Goety 飞行。五个类各自独立注册同一个"
 "字面量，没有中央命令注册表。",
 "The command tree: batch summon, memory toggle, feeding test, flight-follow acceptance, client resync and Goety "
 "flight. Five classes each independently register the same literal — there is no central command registry.",
 [("promaid_src_neo/com/maidsmart/command/MaidArmyCommand.java", 1, 663),
  ("promaid_src_neo/com/maidsmart/command/MaidResyncCommand.java", 1, 616),
  ("promaid_src_neo/com/maidsmart/command/MaidGoetyFlyCommand.java", 1, 232)], ["core", "command"], [], []),

("promaid.core.tools-ai", "LLM 指挥工具集", "LLM Command Tool Suite",
 "供 LLM 调用的工具：移动、拾取、汇报、攻击、切任务、空袭、工位、合成、放置、给物、主人背包、自检。"
 "经注册枢纽统一登记。",
 "The tools the LLM can call: move, pickup, report, attack, switch task, air raid, work post, craft, place, give "
 "item, owner inventory and readiness. All registered through the extension hub.",
 [("promaid_src_neo/com/maidsmart/tool/SwitchTaskTool.java", 1, 355),
  ("promaid_src_neo/com/maidsmart/tool/SmartCraftTool.java", 1, 224),
  ("promaid_src_neo/com/maidsmart/tool/AirRaidTool.java", 1, 214)], ["core", "ai"], [], []),

("promaid.core.safety", "建构安全与共享工具", "Build Safety & Shared Utilities",
 "女仆一切垫脚与搭桥选材的统一安全过滤、无主降级、悬空禁搭、危险方块表、天然方块黑白名单、实体快照遍历、"
 "静态状态表护栏、日志、物品归还、额外容器、手持光源、临时探针。",
 "The unified safety filter for every block a maid places: ownerless downgrade, no-building-while-airborne, "
 "dangerous-block tables, natural-block allow/deny lists, entity-snapshot iteration, static-state-table guards, "
 "logging, item give-back, extra containers, and a held light source.",
 [("promaid_src_neo/com/maidsmart/tool/MaidBuildBlockFilter.java", 1, 452),
  ("promaid_src_neo/com/maidsmart/tool/MaidPlaceGuard.java", 1, 268),
  ("promaid_src_neo/com/maidsmart/tool/MaidExtraContainer.java", 1, 352)], ["core", "shared"], [], []),

("promaid.core.gui", "游戏内文件浏览器", "In-Game File Picker",
 "无头安全的文件选择界面，替代 AWT 文件对话框，供蓝图手册使用。",
 "A headless-safe file-picker screen that replaces the AWT file dialog, used by the blueprint book.",
 [("promaid_src_neo/com/maidsmart/gui/FilePickScreen.java", 1, 247)], ["core", "ui", "client"], [], []),

("promaid.core.compat", "第三方模式兼容", "Third-Party Mode Compat",
 "把 Modular Golems 的傀儡师任务拉黑，并在傀儡模式下停用战术。目前黑名单里只有一条硬编码标识，"
 "其余是推测性通用性。",
 "Blacklists Modular Golems' puppet-master task and suspends tactics in puppet mode. The blacklist currently "
 "holds a single hardcoded id; the rest is speculative generality.",
 [("promaid_src_neo/com/maidsmart/compat/MaidModeCompat.java", 1, 112)], ["core", "compat"], [], []),

("promaid.core.marker", "工位标记", "Work-Position Marker",
 "潜行加中键标记女仆的工作锚点；会礼让 TLM 的罗盘。",
 "Sneak plus middle-click marks a maid's work anchor; yields to TLM's compass.",
 [("promaid_src_neo/com/maidsmart/marker/WorkPosMarkerClient.java", 1, 102)], ["core", "client"], [], []),

("promaid.core.prompt", "提示词追加", "Prompt Appender",
 "向系统提示词追加「工作姿态」一节，由注入类挂上。",
 "Appends a 'work posture' section to the system prompt, injected by a mixin.",
 [("promaid_src_neo/com/maidsmart/prompt/PromaidPromptAppender.java", 1, 26)], ["core", "ai"], [], []),

("promaid.client", "客户端界面", "Client Screens",
 "各功能自带的客户端界面集合（压缩盒等）。蓝图手册与配置面板的界面分别归各自模块。",
 "The client screens that ship with individual features (compression box and others). The blueprint book and "
 "config panel screens belong to their own modules.",
 [("promaid_src_neo/com/maidsmart/client/CompressionBoxScreen.java", 1, None)], ["client", "ui"], [], []),

("promaid.guide", "详细介绍手册", "In-Game Guide",
 "手册物品的「详细介绍」子界面：36 章（35 正文加更新日志），章节目录到正文按像素换行分页阅读；每章末尾"
 "自动附可点击「配置入口」跳转到配置面板对应行。手册只讲当前行为，更新日志另存。",
 "The guide item's 'in detail' sub-screen: 36 chapters (35 content plus the changelog), a chapter list into "
 "pixel-wrapped paged text. Each chapter ends with a clickable 'config entry' link jumping to the matching row "
 "of the config panel. The guide documents only current behavior; the changelog is kept separate.",
 [("promaid_src_neo/com/maidsmart/guide/GuideContent.java", 1, 207),
  ("promaid_src_neo/com/maidsmart/guide/GuideScreen.java", 1, 826),
  ("promaid_src_neo/com/maidsmart/guide/GuideChaptersFlight.java", 1, 1282)], ["guide", "ui"], [],
 [("call", "promaid.core.config-panel", "配置跳转", "Config jump")]),
]

# ============================================================== second tree
MODULES_FORGE = [
("promaid-forge", "Forge 1.20.1 镜像树", "Forge 1.20.1 Mirror Tree",
 "promaid_src 是与 1.21.1 主线逐行手工镜像的 1.20.1 Forge 源码树（SRG 名，如 m_20148_）。两树不共享任何"
 "构建产物，也不由同一份源码生成——每个功能都要改两遍。这是本仓库最大的结构性技术债。",
 "promaid_src is a line-by-line hand mirror of the 1.21.1 mainline for 1.20.1 Forge (SRG names such as "
 "m_20148_). The two trees share no build artifact and are not generated from one source — every feature must "
 "be written twice. This is the repository's largest structural debt.",
 [("promaid_src/com/maidsmart", 1, None)], [], [], []),
]
