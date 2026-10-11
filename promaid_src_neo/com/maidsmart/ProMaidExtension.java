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
    /** v1.3.7 实测六百六十七：武装拴绳挂载校验（2 tick 一次；无挂载对时零开销） */
    private int tetherTimer = 0;
    /** 实测八百六十六：穿戴装备托管核对节流计数（每 5 tick 一次；托管表空时零开销） */
    private int escrowTimer = 0;

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
        // 实测六百七十三：仿创造飞行（事件驱动：core 行为被实例化了却从不被咨询，见控制器类注释）
        NeoForge.EVENT_BUS.register(new com.maidsmart.flight.MaidFreeFlightHandler());
        // 实测八百一十四：干活时把移速压回 0 好感档（挂 MaidTickEvent，每 tick 自纠正一次）
        NeoForge.EVENT_BUS.register(new com.maidsmart.task.MaidWorkSpeedHandler());
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
            // instanceof 过滤——类查询漏扫（同 FarmTillDriver，真因见 MaidRoster）
            // v1.2.0（2026-09-18）【Sable 兼容】：原来的"有限值全世界 AABB"改为
            // level.getAllEntities()——超大 AABB 会被 Sable 直接拒绝（Aborting entity get
            // → 返回空列表 + 每次附完整堆栈刷日志），getAllEntities() 不传 AABB，
            // 同时绕开桶 bug 与 ±∞ 经 blockToSection 溢出两个老坑。
            for (net.minecraft.world.entity.Entity e : com.maidsmart.tool.MaidRoster.inAsEntities(level)) {
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
                // v1.2.2 实测六百〇八：飞行跟随的表同理（也是 core 行为，被强杀时走不到 stop()），
                // 而且她还可能正穿着我们替她换上的鞘翅 —— 收不到 stop 就永远还不回去了。
                com.maidsmart.combat.MaidFlightFollowBehavior.forget(maid.getUUID());
                // v1.2.4 实测六百四十（六百四十一 起口径不变）：激流推进剂窗口同理（只在飞行链路的
                // 每 tick 里被驱动，强杀时没人摘得掉它，留着就是表里一条陈旧 UUID）。
                com.maidsmart.combat.MaidRiptideBoost.forget(maid.getUUID());
                com.maidsmart.combat.MaidFlightKit.setGliding(maid, false);
            }
        }
        // 全清兜底：static 表不能跨会话残留（世界已重新加载，此刻没有任何女仆在飞）
        com.maidsmart.combat.MaidFlightCombatBehavior.clearAll();
        com.maidsmart.combat.MaidTridentSpinBehavior.clearAll();
        com.maidsmart.combat.MaidFlightFollowBehavior.clearAll(); // v1.2.2 实测六百〇八
        com.maidsmart.combat.MaidRiptideBoost.clearAll();         // v1.2.4 实测六百四十
        com.maidsmart.combat.MaidMaceSmashBehavior.clearForcedClutch();
    }

    /**
     * 实测八百二十八【女仆死亡 → 垫脚方块立即落地】：旧版女仆一死，她名下在
     * {@code PlacedBlockTracker} 里登记的垫脚方块会因"绑定女仆不在线"永久暂停计时
     * ——。
     * 这里在死亡事件当场把她的全部垫脚方块销毁并落地成掉落物（不塞背包：人已死）。
     * 覆盖挖矿/伐木/搭路/战斗搭高四张表（reclaimAllFor 遍历全部实例）。
     */
    @net.neoforged.bus.api.SubscribeEvent
    public void onMaidDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (!(event.getEntity() instanceof EntityMaid maid)) {
            return;
        }
        try {
            if (maid.level() instanceof net.minecraft.server.level.ServerLevel
                    && maid.level().getServer() != null) {
                com.maidsmart.task.PlacedBlockTracker.reclaimAllFor(
                        maid.level().getServer(), maid.getUUID());
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ProMaidExtension", ignored);
        }
        // 实测八百三十三：她 NBT 里若还压着"拆笼借镐时摘下的那件原副手物品"，人一死就落地
        // （先清 NBT 再掉落——复活若从 NBT 重建，不会多出一份）
        try {
            com.maidsmart.combat.MaidSpawnerTorchBehavior.dropSavedOffhand(maid);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ProMaidExtension", ignored);
        }
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
        // 实测六百九十九：登记表会话复位（内存清空、落盘保留——下次启动再灌回来）
        com.maidsmart.follow.MaidChunkLoadManager.resetSeenSession();
        // 实测七百四十二·点3：末制导后门的两张表（在飞的炮弹 / 待认领）跨会话不能留
        com.maidsmart.combat.MaidShellHoming.clearAll();
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
            // 实测六百九十【玩家崩溃报告·肇因就在下面这一层 for】：这里必须走"先攒快照
            // 再遍历"——老版直接 for (Entity en : lvl.getAllEntities())，而 getAllEntities()
            // 是原版实体表的活视图（Int2ObjectLinkedOpenHashMap + Guava
            // unmodifiableIterable，javap 实证）。循环体里的 followIfCrossDimension 会对
            // 女仆做跨维度 teleportTo = 从本维度 byId 里删实体，同一个迭代器接着读
            // link[槽位] 就越界：玩家那局报的就是 Index 877 / length 513
            // →「Exception in server tick loop」整合服务端崩回桌面。详见 EntitySnapshot。
            // 实测八百三十九：快照来源从"整张实体表"换成 MaidRoster（只攒女仆、按 20 tick 复用）
            // ——同样安全，且零女仆时不再白拷全表（见 MaidRoster 类注释的采样报告）。
            if (com.maidsmart.config.MaidSmartConfig.MISC_DIMENSION_FOLLOW.get()) {
                for (com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid fm
                        : com.maidsmart.tool.MaidRoster.all(server)) {
                    com.maidsmart.follow.MaidChunkLoadManager.followIfCrossDimension(fm);
                }
            }
        }
        // v1.1.0 实测七十：一键集合"未加载区块召回"队列推进（空队列零开销）
        com.maidsmart.follow.MaidChunkLoadManager.tickPending(server);
        // 实测七百四十二·点3：末制导后门——把女仆从载具打出去的炮弹逐拍纠向她的目标。
        // 内部两张表都空时零开销（绝大多数世界从没有过载具开火）。
        com.maidsmart.combat.MaidShellHoming.tick(server);
        // 实测六百九十八：持票"即时跟人"——她飞着换区块/被复活落到别的区块后，票当场
        // 跟过去（旧版要等下面那条 5 秒扫描，中间那段她就是"没票区块里的女仆"：
        // 服务端不 tick（客户端看她在空中定住）+ 区块一卸载原版就发删包（幽灵建模）。
        // 空表直接返回，平时零成本。
        com.maidsmart.follow.MaidChunkLoadManager.followTickets(server);
        // v1.2.0 实测五百五十六：入世界自动补包队列（空队列零开销）
        com.maidsmart.command.MaidResyncCommand.tickAutoResync(server);
        // 实测八百六十六：穿戴装备托管——每 5 拍核一次"离身的装备落定了没"（空表零开销）
        if (++this.escrowTimer >= 5) {
            this.escrowTimer = 0;
            tickEquipEscrow(server);
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
            // 【联机·服务端】旧版在这里对每个维度取 EntitySnapshot（全量活视图）再 instanceof
            // 过滤女仆 = 15 次/秒 × 全部维度 × 全部实体。改为只遍历"正在走向钓鱼坐垫"的那几只
            // （候选集 = FishingChairService.SEAT_TARGET 本身），没人走坐垫时零开销。
            com.maidsmart.fishing.FishingChairService.tickKeepSeatWalkAll(server);
        }
        // v1.3.7 实测六百六十七：武装拴绳——挂载状态校验/自动解除/存档恢复（2 tick 一次）
        if (++this.tetherTimer >= 2) {
            this.tetherTimer = 0;
            com.maidsmart.combat.GunnerTetherManager.tick(server);
            // v1.3.0(beta)【骑乘指挥棒】：坐骑链路校验/驱动/恢复（与拴绳同频，2 tick 一次）
            com.maidsmart.combat.RideBindManager.tick(server);
        }
        // v1.5.393 实测八百零四：饰品「大运」玩家侧——戴着它的玩家鞘翅滑翔时，逐 tick
        // 开路（5×5×5）。女仆侧不在这里（走她自己饰品栏的 onTick，见 DaYunBauble）。
        // 空跑代价 = 一次 List 遍历 + 每个玩家一次 isFallFlying，无人在飞时几乎为零。
        // v1.5.394 实测八百零五：**不再在这里判总开关**——BGM 与破坏的两条开关无关
        //（"戴着 + 滑翔"就该响），闸门移到 DaYunPlayerHook.tick 内部（它自己判 mayBreak）。
        com.maidsmart.accessory.DaYunPlayerHook.tick(server);
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
        // v1.2.2 实测五百八十四（issue #13）：登录补一次指标石状态同步——客户端那份镜像
        // 只在 S2C 到达时更新，而旧版全项目没有任何"进入世界时同步"的入口（syncTo 只在
        // 右击方块/右击女仆/建造收尾三处），于是"服务端会话已清空、客户端还以为锁着"
        // 会一直持续到下次 syncTo 为止 = 假死锁。
        com.maidsmart.build.IndexStoneNetworking.syncTo(player);
    }

    /** v1.2.2 实测五百八十四（issue #13）：换维度同样补一次——跨维度后锁定格不在当前
     *  维度，客户端的红框/幽灵格会指向一个本维度不存在的坐标（看着像"锁在虚空上"）。 */
    @net.neoforged.bus.api.SubscribeEvent
    public void onPlayerChangedDimension(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            com.maidsmart.build.IndexStoneNetworking.syncTo(player);
        }
    }
    /**
     * v1.5.49：注册指令 —— /maid_smart summon_builders &lt;数量&gt;（批量召唤建造女仆）
     */
    @net.neoforged.bus.api.SubscribeEvent
    public void onRegisterCommands(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        com.maidsmart.command.MaidArmyCommand.register(event.getDispatcher());
        // v1.2.0 实测五百五十五：客户端重同步（修"服务端活着、客户端连实体都没有"）
        com.maidsmart.command.MaidResyncCommand.register(event.getDispatcher());
        // 实测六百七十三：仿创造飞行手动触发（/maid_smart freeflight_goto / freeflight_follow /
        // freeflight_enemy：坐标档与两个专用服务器验收入口）
        com.maidsmart.command.MaidFreeFlightGotoCommand.register(event.getDispatcher());
        // 实测七百一十八：诡厄巫法（Goety）位移聚晶兼容——第三种飞行「外部持续推进」
        // （来源：社区 PR #26，本模组按自身架构收编其中的 Goety 部分、剔掉与本事无关的
        //  BeyondDimensions 半截；命令入口 /maid_smart goety_* 与只读探针 goety_probe）
        com.maidsmart.command.MaidGoetyFlyCommand.register(event.getDispatcher());
        com.maidsmart.command.MaidGoetyProbeCommand.register(event.getDispatcher());
        // 实测 G-16：超越维度（BeyondDimensions）存储联动——产物回收 / 溢出入库 / 自动补货 / 缓存冲刷
        // （默认关：见 misc.bdStorage；命令入口 /maid_smart bd_*）
        com.maidsmart.command.MaidBdProbeCommand.register(event.getDispatcher());
        com.maidsmart.command.MaidBdRuleCommand.register(event.getDispatcher());
        // v1.3.8 巡逻航迹：/maid_smart broom_goto <x y z> [女仆]（照 freeflight_goto 口径）
        //   + broom_patrol status|off [女仆]（排查/解除）
        com.maidsmart.patrol.PatrolCommand.register(event.getDispatcher());
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
            // 实测六百九十七：带上实体网络 id——它与"入世界/重同步"那几行的 id 对起来，
            // 就能一眼看出"离场的是刚复活进来的那一只，还是旧的那一只"（幽灵状态的正主）。
            com.maidsmart.tool.PromaidLog.log("离场", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 离开世界（id " + maid.getId() + "，reason=" + maid.getRemovalReason()
                    + "，维度=" + maid.level().dimension().location() + "）");
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ProMaidExtension", ignored);
        }
        // v1.2.0 实测五百五十七：**离场也登记一次补包**。
        // 【为什么】实测现场：她飞在离主人上千格处（-1184, 115, 96）时客户端实体丢失，
        // 手动 resync 一次即恢复——服务端始终在追踪，是客户端被"通知删除"后没人补回。
        // 触发者就是上面这条离场链路（法术模组对**没带锚核**的女仆"只删不补"）。
        // 若她随后又被加回同一个 level，"重新入世界"那一枪未必打得到（实测兜底后
        // 出现频率下降了，但没归零）；离场这一枪把窗口两头都盖住：
        // 队列按 UUID 在全部维度里找她，只有"她还活着 + 主人同维度"时才真的补包。
        com.maidsmart.command.MaidResyncCommand.scheduleAutoResync(maid);
        // 实测六百九十八【幽灵建模的第二道门】：**她活着**却离场 = 这不是死亡，是区块被拆
        // （reason=null 那一档：区块掉出 entity-ticking / 被卸载）。原版这一刻会给客户端
        // 发一次删包（ChunkMap.removeEntity），而"新实体不补包"（实测五百九十六）又拦着
        // 我们按 id 分流去补 —— 结果就是"服务端还活着、客户端永远没有她"。
        // 这里对**存活**离场直接登记一次强制补包（三枪，绕开 596 的 id 分流）；队列自己会
        // 顺延到"她又能被找到 + 主人同维度"那一拍再打，所以早登记不算浪费。
        if (maid.isAlive()) {
            com.maidsmart.command.MaidResyncCommand.scheduleForcedResync(maid);
        }
    }

    /** 实测五百九十六：装备离开限频表（女仆 UUID → 上次记录毫秒） */
    private static final java.util.Map<java.util.UUID, Long> EQUIP_WATCH_CD =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 【实测八百三十三】女仆装备槽被清空时：①留痕（哪个槽、丢什么、背包快照、调用栈）；
     *  ②**把那件东西接回来**——此刻它只剩本事件这一份引用（原版 tick 里补发的清空事件，
     *  随后只把"当前空值"写回她身上并广播），不接就永远拿不回来（玩家实测丢过盾/剑/靴/帽子）。
     *  【只救真丢的，五道闸缺一不救】服务端且她活着；不在她任何容器里（背包/隐藏/任务/饰品，
     *  按同物品同组件且数量够判——**不能**用对象同一性，TLM 那条"副手→背包"是 copy 进去的）；
     *  不在主人背包里；调用栈里没有玩家/菜单/网络帧；不是消耗品（可食用/药水/桶/烟花）或
     *  耐久见底的那件。救回：原本副手且副手空 → 原位；否则背包；背包也满 → 落地。任何路径都不删。 */
    @net.neoforged.bus.api.SubscribeEvent
    public void onMaidEquipmentLeave(
            net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent event) {
        if (!com.maidsmart.config.MaidSmartConfig.MISC_EQUIP_RESCUE.get()) {
            return; // v1.3.2 实测八百五十九：关 = 回原版（装备被清就是没了）
        }
        try {
            if (!(event.getEntity()
                    instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid)
                    || maid.level().isClientSide()) {
                return;
            }
            net.minecraft.world.item.ItemStack from = event.getFrom();
            if (from.isEmpty() || !event.getTo().isEmpty()) {
                return; // 只处理"槽位被清空"
            }
            StackTraceElement[] stack = new Throwable().getStackTrace();
            // ---- ① 留痕（限频 1.5 秒：同一只女仆只记一条）----
            long now = System.currentTimeMillis();
            Long last = EQUIP_WATCH_CD.get(maid.getUUID());
            if (last == null || now - last >= 1500L) {
                EQUIP_WATCH_CD.put(maid.getUUID(), now);
                StringBuilder inv = new StringBuilder();
                for (int i = 0; i < maid.getMaidInv().getSlots(); i++) {
                    net.minecraft.world.item.ItemStack st = maid.getMaidInv().getStackInSlot(i);
                    if (!st.isEmpty()) {
                        inv.append(i).append(':').append(st.getCount()).append('x')
                                .append(st.getItem()).append(' ');
                    }
                }
                StringBuilder frames = new StringBuilder();
                for (int i = 0; i < stack.length && i < 8; i++) {
                    frames.append("\n    at ").append(stack[i]);
                }
                com.maidsmart.tool.PromaidLog.log("装备离开", com.maidsmart.tool.PromaidLog.nameOf(maid)
                        + " 槽=" + event.getSlot() + " 失去 " + from.getCount() + "x" + from.getItem()
                        + " | 她背包[" + inv + "] 头=" + short0(maid.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD))
                        + " 主手=" + short0(maid.getMainHandItem())
                        + " | 调用栈：" + frames);
            }
            // ---- ② 托管（实测八百六十六：把旧"当拍救回"改成"登记后盯着落定"）----
            // 旧版当拍判定：那件此刻在玩家手里（光标/背包）就不救（对，否则复制）。可玩家从光标
            // 放进背包那一下万一真丢了，槽位清空事件只在"有→空"补发一次、不会再来第二枪，就再也
            // 没人看它 —— 实测那一局 58 次 [装备离开] / 0 次 [装备救回] 即此。新口径：离身即打标记，
            // 之后每 5 拍定位一次，只有确认落到最终位置才销；光标上=还在搬→续期；到点仍找不到→放回。
            if (!maid.isAlive() || !rescuable(from)) {
                return;
            }
            registerEscrow(maid, event.getSlot(), from);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ProMaidExtension", ignored);
        }
    }

    private static String short0(net.minecraft.world.item.ItemStack st) {
        return st.isEmpty() ? "空" : (st.getCount() + "x" + st.getItem());
    }

    /** 实测八百六十六：穿戴装备托管表（女仆 UUID → 已离身、还没确认落定的装备清单） */
    private static final java.util.Map<java.util.UUID, java.util.List<Escrow>> EQUIP_ESCROW =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 托管观察窗（毫秒）：装备离身后这么久仍四处查无 → 放回她身上；光标上会不断续期 */
    private static final long ESCROW_MS = 1500L;

    /** 光标上最多续期多久（毫秒）——防玩家一直攥着导致托管表不收敛 */
    private static final long ESCROW_MAX = 30000L;

    private static final int LOC_NONE = 0, LOC_MAID = 1, LOC_PLAYER = 2, LOC_CURSOR = 3, LOC_GROUND = 4;

    /** 一件「已离开她身上、还没确认落到最终位置」的装备（want 是离身那一刻的副本） */
    private static final class Escrow {
        final net.minecraft.world.entity.EquipmentSlot slot;
        final net.minecraft.world.item.ItemStack want;
        final long started;
        long deadline;

        Escrow(net.minecraft.world.entity.EquipmentSlot slot,
               net.minecraft.world.item.ItemStack want, long now) {
            this.slot = slot;
            this.want = want;
            this.started = now;
            this.deadline = now + ESCROW_MS;
        }
    }

    /** 登记一件离身的装备（同件已有未销标记则不重复登记——防止到点放回两次 = 复制） */
    private static void registerEscrow(EntityMaid maid, net.minecraft.world.entity.EquipmentSlot slot,
                                       net.minecraft.world.item.ItemStack from) {
        try {
            java.util.List<Escrow> list = EQUIP_ESCROW.computeIfAbsent(maid.getUUID(),
                    k -> new java.util.ArrayList<>());
            for (Escrow e : list) {
                if (sameIgnoringDurability(from, e.want)) {
                    return;
                }
            }
            list.add(new Escrow(slot, from.copy(), System.currentTimeMillis()));
            com.maidsmart.tool.PromaidLog.log("装备托管", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 槽=" + slot + " 她拿走了 " + from.getCount() + "x" + from.getItem()
                    + "，盯着它落定");
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ProMaidExtension", ignored);
        }
    }

    /** 每 5 拍核一遍托管表：落定即销；光标上续期；到点四处查无就放回（任何路径都不删） */
    private static void tickEquipEscrow(net.minecraft.server.MinecraftServer server) {
        if (EQUIP_ESCROW.isEmpty()) {
            return;
        }
        if (!com.maidsmart.tool.MaidRoster.any(server)) {
            EQUIP_ESCROW.clear();
            return;
        }
        long now = System.currentTimeMillis();
        java.util.List<EntityMaid> maids = com.maidsmart.tool.MaidRoster.all(server);
        java.util.Iterator<java.util.Map.Entry<java.util.UUID, java.util.List<Escrow>>> it =
                EQUIP_ESCROW.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<java.util.UUID, java.util.List<Escrow>> ent = it.next();
            EntityMaid maid = null;
            for (EntityMaid m : maids) {
                if (m.getUUID().equals(ent.getKey())) {
                    maid = m;
                    break;
                }
            }
            if (maid == null) {
                it.remove(); // 她不在了（被收/被删/死了）：无从放回，标记作废
                continue;
            }
            java.util.Iterator<Escrow> li = ent.getValue().iterator();
            while (li.hasNext()) {
                Escrow es = li.next();
                int loc = locateEquip(server, maid, es.want);
                if (loc == LOC_MAID || loc == LOC_PLAYER || loc == LOC_GROUND) {
                    li.remove();
                    com.maidsmart.tool.PromaidLog.log("装备落定", com.maidsmart.tool.PromaidLog.nameOf(maid)
                            + " 槽=" + es.slot + " 的 " + es.want.getCount() + "x" + es.want.getItem()
                            + " 已确认落定（" + locName(loc) + "）");
                    continue;
                }
                if (loc == LOC_CURSOR) {
                    if (now - es.started < ESCROW_MAX) {
                        es.deadline = now + ESCROW_MS; // 玩家正搬着：续期，标记不许清（也不碰光标）
                        continue;
                    }
                    li.remove(); // 攥太久：放过（东西在他光标上，没丢）
                    continue;
                }
                if (now < es.deadline) {
                    continue; // 还没到点：继续观察（可能是搬运中的空档）
                }
                li.remove();
                net.minecraft.world.item.ItemStack left = putBack(maid, es.want, es.slot);
                if (!left.isEmpty()) {
                    maid.spawnAtLocation(left, 0.5f); // 副手占了、背包也满 → 落地（绝不删）
                }
                com.maidsmart.tool.PromaidLog.log("装备救回", com.maidsmart.tool.PromaidLog.nameOf(maid)
                        + " 槽=" + es.slot + " 找回 " + es.want.getCount() + "x" + es.want.getItem()
                        + "（离身 1.5 秒四处查无，已放回她身上）");
            }
            if (ent.getValue().isEmpty()) {
                it.remove();
            }
        }
    }

    private static String locName(int loc) {
        return loc == LOC_MAID ? "她身上" : (loc == LOC_PLAYER ? "玩家侧" : "地上");
    }

    /** 那件此刻在哪儿：她容器/她装备槽 → 任意玩家界面或背包 → 任意玩家光标 → 附近地面；查无 = 真丢 */
    private static int locateEquip(net.minecraft.server.MinecraftServer server,
                                   EntityMaid maid, net.minecraft.world.item.ItemStack want) {
        try {
            if (inMaidContainers(maid, want)) {
                return LOC_MAID;
            }
            for (net.minecraft.world.entity.EquipmentSlot s : net.minecraft.world.entity.EquipmentSlot.values()) {
                if (matchOne(maid.getItemBySlot(s), want)) {
                    return LOC_MAID; // 她又穿回去了（头/胸/腿/脚/主副手任意一槽）
                }
            }
            // 跨维度算上：主人在别的维度把东西拿走了也要认（否则到点会放回一件 = 复制）
            for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
                for (net.minecraft.server.level.ServerPlayer p : level.players()) {
                    if (matchOne(p.containerMenu.getCarried(), want)) {
                        return LOC_CURSOR; // 拿起未放下：还在搬
                    }
                    for (net.minecraft.world.item.ItemStack st : p.containerMenu.getItems()) {
                        if (matchOne(st, want)) {
                            return LOC_PLAYER; // 玩家背包 / 当前界面里的箱子 / 女仆界面里的她的背包格
                        }
                    }
                }
            }
            if (maid.level() instanceof net.minecraft.server.level.ServerLevel ml) {
                net.minecraft.world.phys.AABB box = maid.getBoundingBox().inflate(24.0);
                for (net.minecraft.world.entity.item.ItemEntity it
                        : ml.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box)) {
                    if (matchOne(it.getItem(), want)) {
                        return LOC_GROUND;
                    }
                }
                for (EntityMaid other : ml.getEntitiesOfClass(EntityMaid.class, box)) {
                    if (other != maid && inMaidContainers(other, want)) {
                        return LOC_MAID; // 被别的女仆捡走也算落定
                    }
                }
            }
            for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
                for (net.minecraft.server.level.ServerPlayer p : level.players()) {
                    for (net.minecraft.world.entity.item.ItemEntity it : level.getEntitiesOfClass(
                            net.minecraft.world.entity.item.ItemEntity.class, p.getBoundingBox().inflate(16.0))) {
                        if (matchOne(it.getItem(), want)) {
                            return LOC_GROUND; // 她落地在他脚边也算落定（关界面时原版会把手上的丢出来）
                        }
                    }
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ProMaidExtension", ignored);
        }
        return LOC_NONE;
    }

    /** 单件比对："同一件、数量够"（与 {@link #hasSame} 同口径，给光标、背包格与地面物用） */
    private static boolean matchOne(net.minecraft.world.item.ItemStack s,
                                    net.minecraft.world.item.ItemStack want) {
        return s != null && !s.isEmpty() && s.getCount() >= want.getCount()
                && net.minecraft.world.item.ItemStack.isSameItemSameComponents(s, want);
    }

    /** 这件东西能不能救：按设计该消失的（吃掉的最后一件、用坏的那件）不救，救回=凭空多一件 */
    private static boolean rescuable(net.minecraft.world.item.ItemStack st) {
        try {
            net.minecraft.world.item.Item it = st.getItem();
            // 1.21.1 没有 Item.isEdible：食物是数据组件（DataComponents.FOOD）
            if (st.has(net.minecraft.core.component.DataComponents.FOOD)) {
                return false; // 可食用
            }
            net.minecraft.resources.ResourceLocation id =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it);
            if (id != null) {
                String s = id.toString();
                if (s.contains("potion") || s.contains("firework") || s.contains("bucket")) {
                    return false; // 药水 / 烟花 / 桶：都是一次性消耗品
                }
            }
            if (st.isDamageableItem() && st.getDamageValue() >= st.getMaxDamage() - 1) {
                return false; // 耐久见底 / 已用坏（例：挡到 0 的盾）
            }
            return true;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ProMaidExtension", ignored);
            return false;
        }
    }

    /** 她身上（背包 / 隐藏格 / 任务格 / 饰品 / **两只手**）还有没有"同一件、数量够"的东西 */
    private static boolean inMaidContainers(EntityMaid maid, net.minecraft.world.item.ItemStack want) {
        try {
            return hasSame(maid.getMaidInv(), want)
                    || hasSame(maid.getHideInv(), want)
                    || hasSame(maid.getTaskInv(), want)
                    || hasSame(maid.getMaidBauble(), want)
                    // 实测八百四十三：还要查她的两只手 —— 本模组自己的"主手⇄副手换手"（如 MaidPlanting）
                    // 会把副手原物挪到主手再腾空副手，"挪到另一只手"不是丢，否则一样会被救回成复制。
                    || hasSame(maid.getHandsInvWrapper(), want);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ProMaidExtension", ignored);
            return false;
        }
    }

    private static boolean hasSame(net.neoforged.neoforge.items.IItemHandler inv,
                                   net.minecraft.world.item.ItemStack want) {
        if (inv == null) {
            return false;
        }
        try {
            for (int i = 0; i < inv.getSlots(); i++) {
                net.minecraft.world.item.ItemStack s = inv.getStackInSlot(i);
                if (s.isEmpty() || s.getCount() < want.getCount() || s.getItem() != want.getItem()) {
                    continue; // 空 / 数量不够 / 连物品都不是同一个 → 直接跳过（省掉后面的拷贝）
                }
                if (sameIgnoringDurability(s, want)) {
                    return true;
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ProMaidExtension", ignored);
        }
        return false;
    }

    /**
     * 【实测八百四十三】"是不是同一件" = 同物品 + 数量够 + **除耐久外**完全相同。
     * <p>不能拿 {@code isSameItemSameComponents} 原样比：可损坏物品的耐久是数据组件 {@code minecraft:damage}，它要求两件一模一样；
     * 而事件里的 {@code from} 是原版**上一 tick 记下的副本** —— 她刚用这把镐拆掉刷怪笼（同一 tick 扣了 1 点耐久）再还镐，
     * 背包里那件的耐久就比它新 1 → 判成"她身上没有" → 镐又被救回副手 = 凭空多一把（实测八百四十一 之后仍复现的那条刷物品）。
     * 耐久是易变量，不该参与"是不是同一件"；附魔等其它组件照旧要比，"另一把不同附魔的镐"仍不算同一件。
     * 耐久附魔这次没扣耐久（差 0）时走上面那条老路，行为不变。
     */
    private static boolean sameIgnoringDurability(net.minecraft.world.item.ItemStack s,
                                                   net.minecraft.world.item.ItemStack want) {
        try {
            if (s.getDamageValue() == want.getDamageValue()) {
                return net.minecraft.world.item.ItemStack.isSameItemSameComponents(s, want);
            }
            if (!s.isDamageableItem() || !want.isDamageableItem()) {
                return false; // 不可损坏却"耐久"不同 → 不是同一件
            }
            net.minecraft.world.item.ItemStack a = s.copy();
            net.minecraft.world.item.ItemStack b = want.copy();
            a.setDamageValue(0); // 只把耐久对齐，别的组件不动
            b.setDamageValue(0);
            return net.minecraft.world.item.ItemStack.isSameItemSameComponents(a, b);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ProMaidExtension", ignored);
            return false;
        }
    }

    /** 放回：原本是副手且副手空着 → 原位；否则背包。返回没能放进去的剩余（空栈 = 已安置好） */
    private static net.minecraft.world.item.ItemStack putBack(
            EntityMaid maid, net.minecraft.world.item.ItemStack lost,
            net.minecraft.world.entity.EquipmentSlot slot) {
        try {
            net.neoforged.neoforge.items.IItemHandlerModifiable hands =
                    (net.neoforged.neoforge.items.IItemHandlerModifiable) maid.getHandsInvWrapper();
            if (slot == net.minecraft.world.entity.EquipmentSlot.OFFHAND
                    && hands.getStackInSlot(1).isEmpty()) {
                hands.setStackInSlot(1, lost);
                return net.minecraft.world.item.ItemStack.EMPTY;
            }
            return net.neoforged.neoforge.items.ItemHandlerHelper.insertItemStacked(
                    maid.getAvailableBackpackInv(), lost, false);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ProMaidExtension", ignored);
            return lost;
        }
    }

    @net.neoforged.bus.api.SubscribeEvent
    public void onMaidJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()
                || !(event.getEntity() instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid)) {
            return; // 只服务端处理女仆
        }
        // v1.2.0 实测五百五十六：入世界 → 延迟给主人补一次实体包（治"客户端实体丢失"）
        com.maidsmart.command.MaidResyncCommand.scheduleAutoResync(maid);
        // 实测五百七十六：入世界第一拍法术数据可能还没就绪 —— 清掉位移法术的书单缓存，
        // 下一拍重新问一次（配合 notifyNotReady 的 0.5 秒静默，治"刚放出来就报缺件"）
        com.maidsmart.combat.MaidFlightKit.forgetClimbSpellCache(maid.getUUID());
        try {
            if (com.maidsmart.schedule.ScheduleData.isOn(maid)
                    && com.maidsmart.config.MaidSmartConfig.MISC_SCHEDULE_ENABLED.get()
                    && maid.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                com.maidsmart.schedule.ScheduleManager.clearAppliedForJoin(maid);
                com.maidsmart.schedule.ScheduleManager.applyNow(maid, sl);
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ProMaidExtension", ignored);
        }
        // 【实测六百七十五】武装拴绳：女仆重新入世界（魂符放出的**最后一步** / 区块重载 / 跨维度）
        //  —— 他还挂在她身上就把链路当场重建，否则把残留的金色描边标记与拴绳键清掉。
        //
        //
        //  （事件链的最末）而不是魂符自己的流程里，正是因为"最后一步最安全"。
        com.maidsmart.combat.GunnerTetherManager.onMaidJoin(maid);
    }

    /**
     * v1.5.47：经验球归属（借鉴 maidmining）——8 格内有玩家 → 让原版（玩家优先）；
     * 否则 24 格内最近挖矿女仆直接吸收经验并取消生成（治"女仆挖矿 + 玩家同区双倍经验"）。
     */
    @net.neoforged.bus.api.SubscribeEvent
    public void onOrbJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (!com.maidsmart.config.MaidSmartConfig.MISC_ORB_JOIN.get()) {
            return; // v1.3.2 实测八百五十九：关 = 回原版（经验球不会被她自动吸收）
        }
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof net.minecraft.world.entity.ExperienceOrb orb)) {
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

    /**
     * v1.2.2 实测六百一十八【压缩盒界面：鼠标上那一叠的两道兜底】。
     *
     * 界面改成箱子式取放之后，玩家可能「把一叠东西挂在鼠标上」就出事：死掉、
     * 被传送、客户端崩了、直接退游戏。这一叠的真身在服务端（见
     * {@code CompressionBoxService.CARRIES}，客户端自己记账等于送物品），所以必须有
     * 不依赖客户端配合的归还点：
     * <ul>
     *   <li><b>每 tick 对一次账</b>：那只手里已经不是压缩盒（换手/被拿走/丢出去/死了
     *       背包被清）→ 把手上的东西放回背包，装不下掉在脚边；</li>
     *   <li><b>掉线</b>：同上（掉线那一刻背包还在，能塞回去）。</li>
     * </ul>
     * 两处都只做一件事：{@code returnCarry}。**任何路径都不许把它弄丢**——
     * 界面那条路（ESC / 关界面发的 CARRY_DROP）只是快一步，这两道才是保底。
     *
     * 【1.21 那边的写法不同】NeoForge 的 tick 事件是 {@code PlayerTickEvent.Post}
     * （1.20.1 那棵是 {@code TickEvent.PlayerTickEvent} + Phase.END，同一个时机）。
     */
    @net.neoforged.bus.api.SubscribeEvent
    public void onPlayerTickBoxCarry(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
            com.maidsmart.box.CompressionBoxService.tickPlayer(sp);
        }
    }

    /** 见 {@link #onPlayerTickBoxCarry}：掉线也要把鼠标上那一叠还回去 */
    @net.neoforged.bus.api.SubscribeEvent
    public void onPlayerLoggedOutBoxCarry(
            net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
            com.maidsmart.box.CompressionBoxService.returnCarry(sp);
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
        // v1.2.2 实测五百七十八【指挥三件套 + 自检】：让模型真正能"换模式 / 起飞 / 定工位 /
        // 查能不能干"。此前 LLM 只能打怪（smart_attack 顺手切攻击任务），别的模式够不到：
        //   smart_switch_task —— 切换任意任务（TLM 原生 + 本模组，支持中文别名，认不出就回可用列表）
        //   smart_air_raid    —— 起飞空袭（近战/远程 + 锁目标 + 缺件回报）/ 停止空袭落地
        //   smart_work_area   —— 设工作区并驻守 / 解除驻守跟随（写的就是中键标记那份 SchedulePos）
        //   smart_readiness   —— 只读自检（空袭三件套、弹药、驻守、排班、锚点、血量）
        register.register(new com.maidsmart.tool.SwitchTaskTool());
        register.register(new com.maidsmart.tool.AirRaidTool());
        register.register(new com.maidsmart.tool.WorkAreaTool());
        register.register(new com.maidsmart.tool.ReadinessTool());
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
    // v1.3.0：扫帚模式（骑 TLM 的扫帚实体飞起来 + 远程武器开火 + 平时悬停跟随主人）——
    // 同样不响应自主切换（AutoCombatPools 按 UID 显式排除），只有玩家手动指定 / 指令切换才进
    manager.add(new com.maidsmart.combat.MaidBroomTask());
    }

    /**
     * v1.5.393 实测八百零四：把「大运」登记成女仆饰品。
     *
     * <p>她饰品栏里放了「大运」→ TLM 的 {@code EntityMaid.tick()} 每 tick 调
     * {@code IMaidBauble.onTick} → {@link com.maidsmart.accessory.DaYunBauble} 判「是否空袭模式 + 是否
     * 真在滑翔」后开路。**只登记，不碰任何原版饰品逻辑**——没放过这件饰品的女仆零影响。
     */
    @Override
    public void bindMaidBauble(com.github.tartaricacid.touhoulittlemaid.item.bauble
            .BaubleManager manager) {
        manager.bind(ProMaidMod.DA_YUN, new com.maidsmart.accessory.DaYunBauble());
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
                            + "ToolAutoEquip=200 AidOwner=190 Torch=185 Shield=180 "
                            + "FlightFollow=246");
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
                        // v1.2.2 实测六百〇八【飞行跟随】：主人飞远了、她包里有鞘翅+烟花、
                        // 中间没方块挡着 → 背上鞘翅追过去（作者口径"跟空袭起飞一样，target=主人"）。
                        // 卡在 246——**刚好压过搭路（245）**：这就是需求里"在判定使用搭路时"的那个点，
                        // 条件不满足本行为压根不启动，搭路一字不动。默认关（flightFollow.enabled）。
                        Pair.of(246, new com.maidsmart.combat.MaidFlightFollowBehavior()),
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
                        // v1.3.3【防刷怪：发现刷怪笼就插火把】——比被动插火把（185）高一位：
                        // 落在这一格上（她发现刷怪笼会先过去把它按哑，再回去
                        // 干自己的活）。比自动装备（200）低、比战斗/自保那一群更低——打架和逃命
                        // 永远排在插火把前面。开关 combat.spawnerTorch.enable，半径同板块可配。
                        Pair.of(186, new com.maidsmart.combat.MaidSpawnerTorchBehavior()),
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
