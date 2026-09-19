package com.maidsmart;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.GameContextRegister;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ToolRegister;
import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.entity.data.TaskDataRegister;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.maidsmart.build.BuildPlan;
import com.maidsmart.build.MaidBuildTask;
import com.maidsmart.build.SmartBuildListTool;
import com.maidsmart.build.SmartBuildTool;
import com.maidsmart.combat.SelfPreservationBehavior;
import com.maidsmart.dialogue.AutonomousTaskManager;
import com.maidsmart.dialogue.ProactiveDialogueManager;
import com.maidsmart.dialogue.WorkStatusReporter;
import com.maidsmart.memory.AiMemoryContext;
import com.maidsmart.memory.AiMemoryManager;
import com.maidsmart.memory.QueryMemoryTool;
import com.maidsmart.protect.MasterDeathTeleportHandler;
import com.maidsmart.task.MaidBrewTask;
import com.maidsmart.task.MaidCookTask;
import com.maidsmart.task.MaidSlaughterTask;import com.maidsmart.task.MaidMineTask;
import com.maidsmart.task.MaidWoodTask;
import com.maidsmart.tool.SmartGiveItemTool;
import com.maidsmart.tool.SmartMoveToTool;
import com.maidsmart.tool.SmartPickupTool;
import com.maidsmart.tool.SmartReportTool;
import com.github.tartaricacid.touhoulittlemaid.api.entity.ai.IExtraMaidBrain;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.ExtraMaidBrainManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.datafixers.util.Pair;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.neoforged.neoforge.common.NeoForge;

import java.util.List;

/**
 * 女仆 AI 增强扩展（Promaid v1.0.0，原 MaidSmartExtension）。
 * 通过官方 @LittleMaidExtension 机制被主模组自动发现（ModList 扫描）：
 * - P1：4 个新 AI 工具（移动/给物品/拾取/汇报）+ 建造工具
 * - P2：挖矿 / 整理箱子 任务
 * - P3：主动对话契机（关心/夜晚/好感/击杀/沉默）
 * - P4：烹饪 / 酿造 任务 + 自主决策 + 长期记忆
 * - P5：建造系统（Promaid 手册/工头/全局进度）+ 自保战斗 + 主人死亡传送
 *
 * 本扩展不注册任何第三方关系/存储联动类，可独立运行。
 */
@LittleMaidExtension
public class ProMaidExtension implements ILittleMaid {

    /** v1.5.53：活跃建造女仆数统计节流计数（每 40 tick 刷新一次） */
    private int maidCountTimer = 0;
    /** v1.5.103：挖矿静态 per-maid 数据清理节流计数（每 600 tick = 30 秒一次） */
    private int purgeTimer = 0;
    /** v1.5.142：跨维度跟随扫描节流计数（每 100 tick = 5 秒一次） */
    private int dimFollowTimer = 0;
    /** v1.5.252j：建造 HUD 广播节流计数（每 20 tick = 1 秒一次） */
    private int hudTimer = 0;
    /** v1.5.275：钓鱼女仆走位高频维持节流计数（每 3 tick 一次） */
    private int seatWalkTimer = 0;
    /** v1.5.332：幼儿女儿武器禁持轮询节流计数（每 20 tick = 1 秒一次） */
    private int weaponGuardTimer = 0;

    public ProMaidExtension() {
        NeoForge.EVENT_BUS.register(new ProactiveDialogueManager());
        // v1.5.191：聊天观察 + 回复反馈学习（真沉默计时 / 提问待答 / 负面反馈→error_mark）
        NeoForge.EVENT_BUS.register(new com.maidsmart.dialogue.ReplyFeedbackTracker());
        NeoForge.EVENT_BUS.register(new AutonomousTaskManager());
        // v1.5.95：感知变化检测（借鉴 maidsoulcore PerceptionEventDetector——快照对比
        // 检测敌对出现/主人受伤/主人注视/天气变化，纯规则气泡播报，零 LLM token）
        NeoForge.EVENT_BUS.register(new com.maidsmart.dialogue.PerceptionManager());
        // v1.5.95：PAD 情绪层事件钩子（主人互动/被打/静默恢复——独立数值）
        NeoForge.EVENT_BUS.register(new com.maidsmart.affect.AffectEventHooks());
        // v1.5.135：自保的"最近攻击者"记录（被攻击事件 → 5 秒威胁窗口，覆盖非 Monster 生物）
        NeoForge.EVENT_BUS.register(com.maidsmart.combat.SelfPreservationBehavior.class);
        // 实测四百零二：低血量自动回魂符（血量 ≤ 阈值 / 致死伤害 → 收进主人背包空魂符）
        NeoForge.EVENT_BUS.register(com.maidsmart.combat.MaidSoulSpellGuard.class);
        // 实测四百一十六：女仆自动复活（死亡→墓碑到期消失→主人重生点复活）
        NeoForge.EVENT_BUS.register(com.maidsmart.combat.MaidAutoResurrect.class);
        // v1.1.0：主动切换战斗模式（主人被敌对生物攻击 → 附近女仆切战斗保护，威胁消失还原）
        NeoForge.EVENT_BUS.register(new com.maidsmart.combat.AutoCombatSwitch());
        // v1.1.0 实测九十：险境脱离（已身处危险方块上的女仆自动挪到最近安全格+应急灭火）
        com.maidsmart.protect.DangerEscapeHandler.register();
        // v1.1.0 实测一百一十七：危险方块避让改原版寻路 malus 机制（DAMAGE_FIRE 等
        // 覆盖为 -1 不可通行——与 LAVA 同款原版语义，不依赖注入点，全移动链路生效）
        com.maidsmart.protect.MaidDangerMalusHandler.register();
        // v1.1.0 实测六十二：女仆着火不传主人（攻击路径取消 + 接触传火自动灭火）
        NeoForge.EVENT_BUS.register(new com.maidsmart.combat.MaidFireGuard());
        // v1.5.86：AI 记忆系统（取代旧 4 键 MaidMemoryManager；旧数据不迁移重新积累）
        NeoForge.EVENT_BUS.register(new AiMemoryManager());
        NeoForge.EVENT_BUS.register(new WorkStatusReporter());
        NeoForge.EVENT_BUS.register(new MasterDeathTeleportHandler());
        // v1.5.257：玩家水行为日志（latest.log 搜 "player water"——挖/放水定位钓鱼问题）
        NeoForge.EVENT_BUS.register(new com.maidsmart.fishing.PlayerWaterLog());
        // v1.1.0 实测一百一十三：Home 模式守卫巡逻（呆立根治——非干活 home 女仆
        // 每 4 秒在 home 锚点附近随机走位，不再原地呆站）
        com.maidsmart.follow.HomePatrolHandler.register();
        // v1.1.0 实测三百三十三：Home 工作移动独立驱动（home+农场/宰杀女仆直连
        // 导航巡逻——全盘推翻，不再依赖 TLM 大脑活动/站桩标记）
        com.maidsmart.follow.HomeWorkMovementDriver.ensureRegistered();
        // v1.1.0 实测一百二十五：蛋糕投喂（女仆吃完蛋糕 +10 好感；玩家蛋糕右击
        // 自己的女仆 = 立刻吃 + 消耗蛋糕 + 蓝色系统消息/气泡）
        NeoForge.EVENT_BUS.register(new com.maidsmart.task.MaidCakeEatHandler());
        // v1.1.0 实测二百三十三：随手种树任务级驱动注册（模块自监听 ServerTick，
        // 扫"任务=伐木"的女仆——触发=伐木模式，与行为运行窗口无关）
        com.maidsmart.task.MaidPlanting.ensureRegistered();
        // v1.1.0 实测二百三十四：手持光源发实光（隐藏光块跟随女仆）
        com.maidsmart.tool.MaidHeldLight.ensureRegistered();
        // v1.1.0 实测二百九十七：锄地独立驱动（不依赖 TARGET_POS——锄完一块
        // 泥土变耕地后扫描空转，start 不再触发，锄地再也不跑）
        com.maidsmart.build.FarmTillDriver.ensureRegistered();
        // v1.1.0 实测三百四十四：中立/魔改生物威胁驱动（行为化 getTarget 判定——
        // 发狂的狼/魔改被动生物 TLM 索敌链选不到目标，这里写目标+直接攻击+补触发参战）
        com.maidsmart.combat.NeutralThreatDriver.ensureRegistered();
        NeoForge.EVENT_BUS.register(this);
    }

    /** 服务端启动：注入 server 引用（存档 schematics/ 蓝图目录扫描用） */
    @net.neoforged.bus.api.SubscribeEvent
    public void onServerStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        com.maidsmart.build.BlueprintLib.setServer(event.getServer());
        // v1.2.0：配置默认值迁移兜底——NeoForge 侧仅靠 ModConfigEvent 实测未生效，
        // 服务端启动时再跑一次（迁移幂等：只在"值==旧默认"时才改）。
        // v1.2.0 实测五百五十七：顺序调到 applyConfigDefaults **之前**——否则本会话
        // 的建造档位仍是迁移前的旧值（旧档第一次进游戏还会是极速），要再进一次才生效。
        // v1.2.2 实测五百六十一：迁移结果**只在真的改过值时**落盘一次。以前是在配置事件里
        // 无条件 save()，而写盘会触发 NeoForge 的文件监听器 → 再发 Reloading → 再写 →
        // 无限写盘风暴（实测 20 秒被重写 38 次），玩家侧表现就是「一点保存就卡死」和
        // 「下次进游戏卡在 mod 加载界面」。这里在配置回调之外，写一次是安全的。
        if (com.maidsmart.ProMaidMod.runConfigMigration()) {
            com.maidsmart.ProMaidMod.persistConfigQuietly();
        }
        // v1.5.88：应用配置面板的建造默认档位（build.speedTier / build.turbo）
        com.maidsmart.build.MaidBuildBehavior.applyConfigDefaults();
        // v1.1.0 实测二十九：搭路/挖矿/伐木 PLACED 表跨会话兜底清理——
        // ServerStopping 正常退出会清，但崩溃/任务管理器强杀进程时 clearAll
        // 不执行 → 内存表残留进新会话：①搭路 isAirborne 误判（残留位置命中
        // 脚下 → 空中距离上限被错误放宽）②回收失效（残留 tick 是旧会话的
        // gameTime，新会话 gameTime 更小 → lifetime 判定永不过期，方块
        // 永不回收——实测"搭路失效时方块回收也失效"的根因）。
        // 兜底：每次服务端启动时把残留表全清（此时世界刚加载，摧毁的
        // 最多是上个会话崩在地图上的几块垫脚方块——本来也该被回收）。
        com.maidsmart.task.MaidMineBehavior.clearAll(event.getServer());
        com.maidsmart.task.MaidWoodBehavior.clearAll(event.getServer());
        com.maidsmart.task.BridgeUpBehavior.clearAll(event.getServer());
        com.maidsmart.combat.SelfPreservationBehavior.clearCombatPlaced(event.getServer());
        // BRIDGING 标记（persistentData 存档持久化）同理：崩溃时没走到
        // doStop 清标记 → 重进存档女仆永远背着 true → 禁瞬移 + 视觉挂桥。
        // 启动时全量清除（任何女仆此刻都不可能在搭路——行为刚初始化）。
        for (net.minecraft.server.level.ServerLevel level : event.getServer().getAllLevels()) {
            // v1.1.0 实测三百三十：EntityMaid.class 全图扫描改用 Entity.class 全量 +
            // instanceof 过滤——ClassInstanceMultiMap 桶 bug（同 FarmTillDriver）
            // v1.2.0（2026-09-18）【Sable 兼容】：原来的"有限值全世界 AABB"改为
            // level.getAllEntities()——超大 AABB 会被 Sable 直接拒绝（Aborting entity get
            // → 返回空列表 + 每次附完整堆栈刷日志），getAllEntities() 不传 AABB，
            // 同时绕开桶 bug 与 ±∞ 经 blockToSection 溢出两个老坑。
            for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
                if (!(e instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid)) {
                    continue;
                }
                if (((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getBoolean(com.maidsmart.task.BridgeUpBehavior.BRIDGING_TAG)) {
                    ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().putBoolean(com.maidsmart.task.BridgeUpBehavior.BRIDGING_TAG, false);
                }
                // v1.2.0：飞行作战的滑翔/俯冲状态是 static 表——崩溃/强杀时行为没走
                // 到 stop()，残留会让该女仆永久"俯冲中"（再进不了飞行作战 + 空中禁
                // 传送/落地缓冲全线卡死）。启动时对该女仆清一次状态，并清掉滑翔标志位。
                com.maidsmart.combat.MaidFlightCombatBehavior.forget(maid.getUUID());
                // 实测五百四十四：激流突进的表同样会残留（本行为是 core 行为，女仆被强杀时
                // 也走不到 stop()），而且它现在还会让空袭让位 + 豁免自动传送——残留的代价比
                // 之前大得多，必须一起清。
                com.maidsmart.combat.MaidTridentSpinBehavior.forget(maid.getUUID());
                com.maidsmart.combat.MaidFlightKit.setGliding(maid, false);
            }
        }
        // 全清兜底：static 表不能跨会话残留（世界已重新加载，此刻没有任何女仆在飞）
        com.maidsmart.combat.MaidFlightCombatBehavior.clearAll();
        com.maidsmart.combat.MaidTridentSpinBehavior.clearAll();
        com.maidsmart.combat.MaidMaceSmashBehavior.clearForcedClutch();
    }

    @net.neoforged.bus.api.SubscribeEvent
    public void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        // v1.5.28：挖矿搭的方块 10 秒清场的最终兜底——内存追踪器随进程消失，
        // 立即销毁全部残留方块变掉落物（重进存档不会看到永不消失的搭方块）
        com.maidsmart.task.MaidMineBehavior.clearAll(event.getServer());
        com.maidsmart.task.MaidWoodBehavior.clearAll(event.getServer());
        com.maidsmart.task.BridgeUpBehavior.clearAll(event.getServer());
        com.maidsmart.combat.SelfPreservationBehavior.clearCombatPlaced(event.getServer());
        com.maidsmart.build.BuildPlan.clearAll();
        com.maidsmart.build.ChunkFreeze.clearAll();
        // v1.2.0：指标石会话清空（防跨存档残留锁定/绑定状态）
        com.maidsmart.build.IndexStoneService.clearAll();
        com.maidsmart.build.BlueprintLib.setServer(null);
        // v1.1.0 实测四十四：撤掉全部女仆区块强制加载票（防票残留锁区块）
        com.maidsmart.follow.MaidChunkLoadManager.releaseAll(event.getServer());
    }

    /**
     * v1.5.28：挖矿搭方块 10 秒清理的全局兜底——旧版清理只挂在挖矿行为
     * tick 上，挖完矿行为停止后不再运行 → 搭的方块永久残留。
     * 现在每 tick 由这里统一执行，与行为生命周期完全无关。
     */
    @net.neoforged.bus.api.SubscribeEvent
    public void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
net.minecraft.server.MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        // v1.5.52: 自适应建造速度 - 每 tick 测 TPS (20 tick 一次), 建筑行为据此自动调速
        com.maidsmart.build.MaidBuildBehavior.tickTpsMonitor();
        // v1.5.53: 活跃建造女仆数统计（40 tick ≈ 2 秒一次）——多女仆翻倍加速的基础
        if (++this.maidCountTimer >= 40) {
            this.maidCountTimer = 0;
            com.maidsmart.build.MaidBuildBehavior.updateActiveBuilders(server);
        }
        // v1.1.0 实测四十二：搭方块回收改 PlacedBlockTracker（绑定搭建女仆 +
        // 魂符收回暂停计时）——expirePlaced 内部自遍历全维度，不再按维度循环
        long gt = server.getAllLevels().iterator().next().getGameTime();
        com.maidsmart.task.MaidMineBehavior.expirePlaced(server, gt);
        com.maidsmart.task.MaidWoodBehavior.expirePlaced(server, gt);
        com.maidsmart.task.BridgeUpBehavior.expirePlaced(server, gt);
        // v1.1.0 实测十七：战斗搭方块（自保搭高/翻墙/搭桥/封头盖帽）60 秒到期清理
        com.maidsmart.combat.SelfPreservationBehavior.expireCombatPlaced(server, gt);
        // v1.5.103：每 30 秒清理挖矿静态 per-maid 数据（防长时运行内存泄漏）
        if (++this.purgeTimer >= 600) {
            this.purgeTimer = 0;
            com.maidsmart.task.MaidMineBehavior.purgeStaleMaids(server);
            com.maidsmart.task.MaidWoodBehavior.purgeStaleMaids(server);
        }
        // v1.5.142：每 5 秒扫描跟随女仆是否与主人跨维度 → 传送到主人身边
        // v1.1.0 实测四十四：并入 MaidChunkLoadManager——区块强制加载（真·随时
        // 可传送）+ 原版 teleportTo 跨维度跟随
        if (++this.dimFollowTimer >= 100) {
            this.dimFollowTimer = 0;
            com.maidsmart.follow.MaidChunkLoadManager.tick(server);
            // v1.1.0 实测九十二：接线跨维度跟随——MISC_DIMENSION_FOLLOW 开关此前是
            // 摆设（配置+面板都在，followIfCrossDimension 却从未被任何代码调用）。
            // 开关开启时每 5 秒扫描全服女仆，异维度跟随者自动传送到主人身边；
            // home/坐姿/骑乘/主人非存活/同维度等豁免在 followIfCrossDimension 内自判。
            if (com.maidsmart.config.MaidSmartConfig.MISC_DIMENSION_FOLLOW.get()) {
                for (net.minecraft.server.level.ServerLevel lvl : server.getAllLevels()) {
                    for (net.minecraft.world.entity.Entity en : lvl.getAllEntities()) {
                        if (en instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid fm) {
                            com.maidsmart.follow.MaidChunkLoadManager.followIfCrossDimension(fm);
                        }
                    }
                }
            }
        }
        // v1.1.0 实测七十：一键集合"未加载区块召回"队列推进（空队列零开销）
        com.maidsmart.follow.MaidChunkLoadManager.tickPending(server);
        // v1.2.0 实测五百五十六：入世界自动补包队列（空队列零开销）
        com.maidsmart.command.MaidResyncCommand.tickAutoResync(server);
        // v1.5.332：幼儿女儿武器禁持（1 秒轮询——婴儿/幼年女儿手上出现武器
        // → 移除并原地丢一个完全一样的到地上）
        if (++this.weaponGuardTimer >= 20) {
            this.weaponGuardTimer = 0;
            com.maidsmart.task.MaidWeaponGuard.tick(server);
        }
        // v1.5.252j：建造 HUD 广播（每秒一次）——客户端左上角显示速度/预计完成时间
        if (++this.hudTimer >= 20) {
            this.hudTimer = 0;
            com.maidsmart.build.BuildHudTracker.broadcast(server);
            // v1.1.0 实测九十五：建造区块行每秒同步——红色区块框+幽灵方块投影的
            // 持续驱动（此前只在开手册/创建计划时一次性下发，玩家关掉手册走到工地
            // 后客户端无数据，"建造此建筑里面没有显示投影"的根因）
            com.maidsmart.build.BlueprintBookNetworking.broadcastRegionSync(server);
            // v1.5.252q：清扫自动生成的钓鱼坐垫（任务解除/脱离坐垫超 2 秒 → 删除）
            // v1.5.252r：逻辑在普通类 FishingChairService（mixin 类不可被普通代码直接引用）
            com.maidsmart.fishing.FishingChairService.sweep(server);
            // v1.1.0 实测四百二十一：冷却可视化 HUD（复活倒计时 / 回魂符冷却每秒下发）
            com.maidsmart.combat.CooldownHudTracker.broadcast(server);
        }
        // v1.5.275：每 3 tick 高频维持钓鱼女仆走位（FindSit 12 tick 间隙 + 站立行为
        // 清 WALK_TARGET → 一步一停；3 tick 内补回 → 连续走）
        if (++this.seatWalkTimer >= 3) {
            this.seatWalkTimer = 0;
            try {
                // v1.2.0（2026-09-18）【Sable 兼容】：全世界 AABB 扫描改为 getAllEntities()
                //（超大 AABB 被 Sable 拒绝查询，见 FarmTillDriver 同款说明）
                for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
                    // v1.1.0 实测三百三十：EntityMaid.class 全图扫描改用 Entity.class 全量 +
                    // instanceof 过滤——ClassInstanceMultiMap 桶 bug（同 FarmTillDriver）
                    for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
                        if (e instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid m
                                && m.isAlive()) {
                            com.maidsmart.fishing.FishingChairService.tickKeepSeatWalk(m);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        // v1.5.140：建造传送机制已整体删除（suffocateCheck 救援传送同删）
    }

    /**
     * v1.5.13 生存优化：玩家首次进入世界时赠送手册与排班表。
     *
     * v1.2.0【改用原版进度系统，修"重复给予"】：旧版在这里用 persistentData 里的
     * 手搓标记判断"已发过"，但实机日志显示同一存档 10:55 登录发一次、10:59 又登录
     * 又发一次（背包里确实多出书）——手搓 NBT 标记拦不住跨会话重发。现改为参照
     * TLM《记忆中的幻想乡》的做法：用一个 minecraft:impossible 进度的完成状态当
     * "已领取"记录（由原版进度系统持久化），奖励走进度掉落表自动发放。
     * 具体见 com.maidsmart.FirstJoinGift（含老存档迁移：已拿过的人静默记账不重发）。
     */
    @net.neoforged.bus.api.SubscribeEvent
    public void onPlayerLoggedIn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            return;
        }
        com.maidsmart.FirstJoinGift.onLogin(player);
    }
    /**
     * v1.5.49：注册指令 —— /maid_smart summon_builders &lt;数量&gt;（批量召唤建造女仆）
     */
    @net.neoforged.bus.api.SubscribeEvent
    public void onRegisterCommands(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        com.maidsmart.command.MaidArmyCommand.register(event.getDispatcher());
        // v1.2.0 实测五百五十五：客户端重同步（修"服务端活着、客户端连实体都没有"）
        com.maidsmart.command.MaidResyncCommand.register(event.getDispatcher());
    }

    /**
     * v1.1.0 实测一百四十四：女仆重新入世界（魂符收放/区块重载/跨维度传送）→ 排班
     * 重放。根因：魂符收进再放出，persistentData（含"本段已应用"去抖键）随魂符保存，
     * 同段内放出后去抖命中 → 调度器跳过 = "魂符收放后排班没切换"。入世界即清去抖键
     * 并立即按当前时段应用一次（模式/任务/在家锚点全部重放——SchedulePos 锚点随
     * 实体 NBT 保存，不会在放出位置错误重锚）。未开排班/总开关关闭零成本。
     */
    /**
     * v1.2.0 实测五百五十六【离场诊断】：女仆离开世界时记一行（含移除原因）。
     *
     * 【为什么要记】法术模组的 `MaidSpellEventHandler.onEntityLeaveLevel` 会在"该释放
     * 区块加载"那类移除原因下，**发通知让客户端删掉她的实体**；而它自己的"客户端实体
     * 恢复"只对带锚核的女仆生效。没带锚核的女仆被删掉后若又被加回同一个 level，原版
     * 追踪表未必再发一次生成包 → 表现就是"服务端还活着、客户端永远没有她"（重启游戏
     * 才会回来）。这一行日志是排查该现象的第一现场：出现"客户端看不见她"时，先看这里
     * 有没有同一时刻的离场记录与移除原因。
     */
    @net.neoforged.bus.api.SubscribeEvent
    public void onMaidLeaveLevel(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()
                || !(event.getEntity() instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid)) {
            return;
        }
        try {
            com.maidsmart.tool.PromaidLog.log("离场", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 离开世界（reason=" + maid.getRemovalReason() + "，维度="
                    + maid.level().dimension().location() + "）——法术模组会据此通知客户端删掉她的实体");
        } catch (Throwable ignored) {
        }
        // v1.2.0 实测五百五十七：**离场也登记一次补包**。
        // 【为什么】实测现场：她飞在离主人上千格处（-1184, 115, 96）时客户端实体丢失，
        // 手动 resync 一次即恢复——服务端始终在追踪，是客户端被"通知删除"后没人补回。
        // 触发者就是上面这条离场链路（法术模组对**没带锚核**的女仆"只删不补"）。
        // 若她随后又被加回同一个 level，"重新入世界"那一枪未必打得到（实测兜底后
        // 出现频率下降了，但没归零）；离场这一枪把窗口两头都盖住：
        // 队列按 UUID 在全部维度里找她，只有"她还活着 + 主人同维度"时才真的补包。
        com.maidsmart.command.MaidResyncCommand.scheduleAutoResync(maid);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public void onMaidJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()
                || !(event.getEntity() instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid)) {
            return; // 只服务端处理女仆
        }
        // v1.2.0 实测五百五十六：入世界 → 延迟给主人补一次实体包（治"客户端实体丢失"）
        com.maidsmart.command.MaidResyncCommand.scheduleAutoResync(maid);
        try {
            if (com.maidsmart.schedule.ScheduleData.isOn(maid)
                    && com.maidsmart.config.MaidSmartConfig.MISC_SCHEDULE_ENABLED.get()
                    && maid.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                com.maidsmart.schedule.ScheduleManager.clearAppliedForJoin(maid);
                com.maidsmart.schedule.ScheduleManager.applyNow(maid, sl);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * v1.5.47：经验球归属（借鉴 maidmining）——8 格内有玩家 → 让原版（玩家优先）；
     * 否则 24 格内最近挖矿女仆直接吸收经验并取消生成（治"女仆挖矿 + 玩家同区双倍经验"）。
     */
    @net.neoforged.bus.api.SubscribeEvent
    public void onOrbJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof net.minecraft.world.entity.ExperienceOrb orb)) {
            return;
        }
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) event.getLevel();
        // 8 格内有玩家 → 原版行为（玩家优先吸收）
        net.minecraft.world.entity.player.Player near = level.getEntitiesOfClass(
                        net.minecraft.world.entity.player.Player.class, orb.getBoundingBox().inflate(8.0))
                .stream().findFirst().orElse(null);
        if (near != null) {
            return;
        }
        // 24 格内最近挖矿女仆 → 直接吸收（TLM pickupXPOrb），取消生成
        com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid best = null;
        double bestDist = Double.MAX_VALUE;
        for (com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid m
                : level.getEntitiesOfClass(com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid.class,
                orb.getBoundingBox().inflate(24.0))) {
            if (m.getTask() == null || !MaidMineTask.UID.equals(m.getTask().getUid())) {
                continue;
            }
            double d = m.position().distanceTo(orb.position());
            if (d < bestDist) {
                bestDist = d;
                best = m;
            }
        }
        if (best != null) {
            best.pickupXPOrb(orb);
            event.setCanceled(true);
        }
    }

    @Override
    public void registerAITool(ToolRegister register) {
        register.register(new SmartMoveToTool());
        register.register(new SmartGiveItemTool());
        register.register(new SmartPickupTool());
        register.register(new SmartReportTool());
        // v1.5.136：战斗指挥（"去打那只怪/攻击最近的怪/帮我打它"——切攻击任务+锁目标）
        register.register(new com.maidsmart.tool.SmartAttackTool());
        register.register(new SmartBuildTool());
        register.register(new SmartBuildListTool());
        // v1.5.94：AI 画蓝图（子 Agent 设计器）——玩家对话描述 → 子 LLM 设计 → 存手册 → 正常建造
        register.register(new com.maidsmart.build.SmartDesignTool());
        // v1.5.86：AI 记忆检索工具（LLM 对话中按需调用）
        register.register(new QueryMemoryTool());
        // v1.5.95：LLM 主动写记忆工具（"记住…"当场写，与被动提取互补）
        register.register(new com.maidsmart.memory.RememberTool());
        // v1.5.95：工作笔记工具（跨对话任务状态，临时可覆盖）
        register.register(new com.maidsmart.memory.WorkingNoteTool());
        // 多级记忆索引查询工具（移植自 Sphantosis query_memory_index：日/3日/周/月
        // 日记式摘要，先列跨度再查内容，前缀和式逐步缩小时间范围）
        register.register(new com.maidsmart.memory.QueryMemoryIndexTool());
        // v1.5.190：帮主人做事的"双手"工具——合成 / 放方块（让 AI 女仆有玩家能力）
        register.register(new com.maidsmart.tool.SmartCraftTool());
        register.register(new com.maidsmart.tool.SmartPlaceTool());
        // v1.5.196：感知查询工具（先查后做——look_around/terrain/build_site/inspect/scanblock/scanentity）
        register.register(new com.maidsmart.dialogue.PerceptionQueryTool());
        // v1.5.244：启动预热加载 WorldProbe——LLM 工具在异步线程首次访问该类时
        // ModuleClassLoader 偶发 ClassNotFoundException（实测"让女仆帮忙填坑"对话
        // 报 Async tool execution failed: NoClassDefFoundError: WorldProbe，jar 里
        // 类文件完整、URLClassLoader 可加载）；启动时在主线程强制加载一次，进入
        // 模块缓存后异步调用即可复用；若仍失败日志会暴露真实原因
        try {
            Class.forName("com.maidsmart.dialogue.WorldProbe");
            org.slf4j.LoggerFactory.getLogger(ProMaidExtension.class)
                    .info("WorldProbe preloaded OK");
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger(ProMaidExtension.class)
                    .warn("WorldProbe preload failed: {}", t.toString());
        }
        // v1.5.196：工作清单工具（query_todo/build_need——任务计划与材料缺口查询闭环）
        register.register(new com.maidsmart.dialogue.WorkListTool());
        // v1.5.287：查看主人物品栏工具（确认/获得主人背包里有什么——只读查询）
        register.register(new com.maidsmart.tool.OwnerInventoryTool());
    }

    @Override
    public void addMaidTask(TaskManager manager) {
        manager.add(new MaidMineTask());
        manager.add(new MaidCookTask());
        manager.add(new MaidBrewTask());
        manager.add(new MaidBuildTask());
        // v1.2.0：指标石一次性临时建造任务（隐藏任务——不出现在任务面板，
        // 只由 IndexStoneService 在玩家绑定女仆时临时指派，完成后还原原任务）
        manager.add(new com.maidsmart.build.IndexStoneBuildTask());
        // v1.1.0：伐木任务（克隆挖矿架构——木材表/斧判定/树叶放行视线/连锁砍整棵树）
        manager.add(new MaidWoodTask());
        // v1.1.0 实测三百一十一：宰杀任务（5×5 同种牲畜超阈值 → 每 3 秒随机宰杀一只）
        manager.add(new MaidSlaughterTask());
        // v1.2.0（1.21.1）：飞行作战——鞘翅 + 重锤 + 烟花三件齐备才激活的新作战模式，
        // 不响应自主切换（AutoCombatSwitch.buildPools 按 UID 显式排除）
        manager.add(new com.maidsmart.combat.MaidFlightCombatTask());
    // v1.2.0：飞行远战（空中盘旋如幻翼 + 每 5 秒补烟花 + 手持远程武器开火）
    manager.add(new com.maidsmart.combat.MaidFlightRangedTask());
    }

    @Override
    public void registerTaskData(TaskDataRegister register) {
        BuildPlan.KEY = register.register(BuildPlan.KEY_ID, BuildPlan.CODEC);
        // v1.5.101：AI 记忆 per-maid 开关改用 Forge persistentData（AiMemoryManager）——
        // 原 TaskData 注册（Codec.BOOL 编成 ByteTag）会被 TLM 同步（TaskDataRegister
        // writeSyncData）强转 CompoundTag 崩溃。不再注册。
    }

    @Override
    public void registerAIMaidContext(GameContextRegister register) {
        // v1.5.86：AI 记忆投影（相关记忆/今日回顾/主人画像；详细检索走 query_memory 工具）
        register.registerCategory("ai_memory", "AI long-term memory of the maid", true);
        register.registerContext("ai_memory", new AiMemoryContext());
        // v1.5.95：PAD 情绪层（愉悦/唤醒/支配 + 亲密/冲突/思念 + 修复债务）
        register.registerCategory("ai_affect", "Current emotional state of the maid", true);
        register.registerContext("ai_affect", new com.maidsmart.affect.AffectManager.AffectContext());
        // v1.5.196：工作清单（跨轮任务计划投影——LLM 知道自己"在做什么/下一步做什么"）
        register.registerCategory("ai_worklist", "Current task plan of the maid", true);
        register.registerContext("ai_worklist", new com.maidsmart.dialogue.WorkListContext());
    }

    @Override
    public void addExtraMaidBrain(ExtraMaidBrainManager manager) {
        manager.addExtraMaidBrain(new IExtraMaidBrain() {
            /** v1.5.227：getCoreBehaviors 消费诊断（只打第一条）——被调用 = TLM
             *  女仆 Brain 构建时确实取了我们注册的 core 行为列表 */
            private boolean coreLogged = false;

            @Override
            public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> getCoreBehaviors() {
                // v1.5.227 诊断：Brain 构建消费注册表（女仆生成/脑重建时调用）
                if (!this.coreLogged) {
                    this.coreLogged = true;
                    org.slf4j.Logger log = com.mojang.logging.LogUtils.getLogger();
                    log.info("extra-core registered: SelfPreservation=250 WaterClutch=240 "
                            + "MaceSmash=235 CombatTactics=230 "
                            + "ToolAutoEquip=200 AidOwner=190 Torch=185 Shield=180");
                }
                // 自保：core 行为（任何 activity 都运行）。
                // 优先级 250 > TLM 全部行为（core 最高 99：ClearSleep；跟随=3；Panic/Await=1），
                // 保证自保压过战斗/任务/跟随/待命/睡觉/恐慌一切状态，且不会被任何行为抢占。
                // v1.5.25：落地水（优先级 240，低于自保 250）——被动技能，不进自保状态，
                // 有水桶+坠落时自动放水缓冲，与自保互不干扰。
                // v1.5.90：任务工具自动装备（攻击/弓/弩/三叉戟/挖矿）——canUse 里
                // 换完即停、不占行为槽；优先级低于自保/落地水、高于施工区避让
                return List.of(
                        Pair.of(250, new SelfPreservationBehavior()),
                        // v1.1.0：搭路（主人在上方时垫方块靠近，默认关）——低于自保、
                        // 高于落地水/战术：搭路条件本身排除威胁/自保，不与战斗抢移动
                        Pair.of(245, new com.maidsmart.task.BridgeUpBehavior()),
                        Pair.of(240, new com.maidsmart.combat.WaterClutchBehavior()),
                        // v1.2.0 实测五百三十四：激流三叉戟旋转突进（攻击模式 / 近战空袭 + 主手激流三叉戟）——
                        // 低于落地水（240）、高于战术（230）：突进本身是一次"贴脸手段"，
                        // 命中判定由 MaidSpinAttackTouchMixin 补（原版 doAutoAttackOnTouch 对非玩家是空实现）
                        Pair.of(235, new com.maidsmart.combat.MaidTridentSpinBehavior()),
                        // v1.1.0（1.21.1 专属）：重锤猛击——持重锤贴近目标跳起下落猛砸
                        // （参考 vanilla_mob_remake 僵尸用重锤）；高于战术，跃起期间战术让位；
                        // 落地缓冲改由本行为在【猛击结算后】请求 WaterClutchBehavior 强放
                        // 一格水/雪（重锤专属落地水，实测四百四十二）
                        Pair.of(235, new com.maidsmart.combat.MaidMaceSmashBehavior()),
                        // v1.5.134：单兵作战战术（PVP 式走位/拉扯/距离控制）——低于自保/落地水，
                        // 高于自动装备/施工区避让；Brain 1.20.1 无高优先级阻断，不影响 WORK 战斗行为
                        Pair.of(230, new com.maidsmart.combat.MaidCombatTacticsBehavior()),
                        Pair.of(200, new com.maidsmart.task.MaidToolAutoEquipBehavior()),
                        // v1.5.189：玩家贴身辅助（被动技能，非工作状态）——低于自动装备/
                        // 战斗/自保，高于施工区避让；自动喂食治疗主人 / 黑暗插火把 / 共享盾牌
                        Pair.of(190, new com.maidsmart.combat.MaidAidOwnerBehavior()),
                        Pair.of(185, new com.maidsmart.combat.MaidTorchPlacerBehavior()),
                        Pair.of(180, new com.maidsmart.combat.MaidShieldShareBehavior()),
                        // v1.1.0 实测一百八十三：空闲散步（反馈："增加女仆散步的频率和速度"）
                        //——低于 TLM core 最高 99 与上面全部行为，只在真正空闲时生效
                        Pair.of(50, new com.maidsmart.task.MaidStrollBehavior()),
                        // v1.1.0 实测四百一十：排班贴身气泡（情绪价值彩蛋）——主人靠近
                        // 排班中的女仆时冒一句贴身对话（30 条池，30 秒/只 CD）；战斗/自保/
                        // 睡觉/坐骑中豁免，限频由 ChatBubbleLimitMixin 全局兜底
                        Pair.of(48, new com.maidsmart.task.ScheduleBubbleBehavior())
                        // v1.5.212：施工区避让已删除——自保 antiSuffocate 每 tick 防窒息
                        // 兜底后，"非建造女仆接近施工区会逃离"没有存在意义
                        //（原 Pair.of(150, new BuildAreaAvoidBehavior())）
                );
            }

            /**
             * 实测四百一十八：床铺互通·方向一（反馈："让女仆床和玩家床的代码互通。
             * 女仆和玩家可以互相使用对方的床"）。
             *
             * REST 活动注册（TLM 自带 MaidBedTask=5、随机散步=20）：优先级 6 排在
             * TLM 女仆床行为之后——女仆床优先，没有可用女仆床时才睡原版床。
             */
            @Override
            public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> getRestBehaviors() {
                return List.of(
                        Pair.of(6, new com.maidsmart.task.MaidBedInteropBehavior())
                );
            }
        });
    }
}
