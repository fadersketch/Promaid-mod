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
import com.maidsmart.task.MaidMineTask;
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
import net.minecraftforge.common.MinecraftForge;

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
 * v1.0.0 拆分：关系联动（心契誓约 × 爱憎分明）已迁往 Heartfelt-connection；
 * 本扩展不注册任何关系相关类，可独立运行。
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
        MinecraftForge.EVENT_BUS.register(new ProactiveDialogueManager());
        // v1.5.191：聊天观察 + 回复反馈学习（真沉默计时 / 提问待答 / 负面反馈→error_mark）
        MinecraftForge.EVENT_BUS.register(new com.maidsmart.dialogue.ReplyFeedbackTracker());
        MinecraftForge.EVENT_BUS.register(new AutonomousTaskManager());
        // v1.5.95：感知变化检测（借鉴 maidsoulcore PerceptionEventDetector——快照对比
        // 检测敌对出现/主人受伤/主人注视/天气变化，纯规则气泡播报，零 LLM token）
        MinecraftForge.EVENT_BUS.register(new com.maidsmart.dialogue.PerceptionManager());
        // v1.5.95：PAD 情绪层事件钩子（主人互动/被打/静默恢复——独立数值，兼容心契）
        MinecraftForge.EVENT_BUS.register(new com.maidsmart.affect.AffectEventHooks());
        // v1.5.98：关系记忆适配（软感知 maidmarriage 结婚/告白/父女 + Love Loathe 信任/恐惧）
        MinecraftForge.EVENT_BUS.register(new com.maidsmart.memory.RelationshipMemoryAdapter());
        // v1.5.135：自保的"最近攻击者"记录（被攻击事件 → 5 秒威胁窗口，覆盖非 Monster 生物）
        MinecraftForge.EVENT_BUS.register(com.maidsmart.combat.SelfPreservationBehavior.class);
        // v1.1.0：主动切换战斗模式（主人被敌对生物攻击 → 附近女仆切战斗保护，威胁消失还原）
        MinecraftForge.EVENT_BUS.register(new com.maidsmart.combat.AutoCombatSwitch());
        // v1.1.0 实测九十：险境脱离（已身处危险方块上的女仆自动挪到最近安全格+应急灭火）
        com.maidsmart.protect.DangerEscapeHandler.register();
        // v1.1.0 实测六十二：女仆着火不传主人（攻击路径取消 + 接触传火自动灭火）
        MinecraftForge.EVENT_BUS.register(new com.maidsmart.combat.MaidFireGuard());
        // v1.5.86：AI 记忆系统（取代旧 4 键 MaidMemoryManager；旧数据不迁移重新积累）
        MinecraftForge.EVENT_BUS.register(new AiMemoryManager());
        MinecraftForge.EVENT_BUS.register(new WorkStatusReporter());
        MinecraftForge.EVENT_BUS.register(new MasterDeathTeleportHandler());
        // v1.5.257：玩家水行为日志（latest.log 搜 "player water"——挖/放水定位钓鱼问题）
        MinecraftForge.EVENT_BUS.register(new com.maidsmart.fishing.PlayerWaterLog());
        MinecraftForge.EVENT_BUS.register(this);
    }

    /** 服务端启动：注入 server 引用（存档 schematics/ 蓝图目录扫描用） */
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onServerStarted(net.minecraftforge.event.server.ServerStartedEvent event) {
        com.maidsmart.build.BlueprintLib.setServer(event.getServer());
        // v1.5.88：应用配置面板的建造默认档位（build.speedTier / build.turbo）
        com.maidsmart.build.MaidBuildBehavior.applyConfigDefaults();
        // v1.1.0 实测二十九：搭路/挖矿/伐木 PLACED 表跨会话兜底清理——
        // ServerStopping 正常退出会清，但崩溃/任务管理器强杀进程时 clearAll
        // 不执行 → 内存表残留进新会话：①搭路 isAirborne 误判（残留位置命中
        // 脚下 → 空中距离上限被错误放宽）②回收失效（残留 tick 是旧会话的
        // gameTime，新会话 gameTime 更小 → lifetime 判定永不过期，方块
        // 永不回收——用户实测"搭路失效时方块回收也失效"的根因）。
        // 兜底：每次服务端启动时把残留表全清（此时世界刚加载，摧毁的
        // 最多是上个会话崩在地图上的几块垫脚方块——本来也该被回收）。
        com.maidsmart.task.MaidMineBehavior.clearAll(event.getServer());
        com.maidsmart.task.MaidWoodBehavior.clearAll(event.getServer());
        com.maidsmart.task.BridgeUpBehavior.clearAll(event.getServer());
        com.maidsmart.combat.SelfPreservationBehavior.clearCombatPlaced(event.getServer());
        // BRIDGING 标记（persistentData 存档持久化）同理：崩溃时没走到
        // doStop 清标记 → 重进存档女仆永远背着 true → 禁瞬移 + 视觉挂桥。
        // 启动时全量清除（任何女仆此刻都不可能在搭路——行为刚初始化）。
        for (net.minecraft.server.level.ServerLevel level : event.getServer().m_129785_()) {
            for (com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid
                    : level.m_45976_(com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid.class,
                    new net.minecraft.world.phys.AABB(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                            Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY))) {
                if (maid.getPersistentData().m_128471_(com.maidsmart.task.BridgeUpBehavior.BRIDGING_TAG)) {
                    maid.getPersistentData().m_128379_(com.maidsmart.task.BridgeUpBehavior.BRIDGING_TAG, false);
                }
            }
        }
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
        // v1.5.28：挖矿搭的方块 10 秒清场的最终兜底——内存追踪器随进程消失，
        // 立即销毁全部残留方块变掉落物（重进存档不会看到永不消失的搭方块）
        com.maidsmart.task.MaidMineBehavior.clearAll(event.getServer());
        com.maidsmart.task.MaidWoodBehavior.clearAll(event.getServer());
        com.maidsmart.task.BridgeUpBehavior.clearAll(event.getServer());
        com.maidsmart.combat.SelfPreservationBehavior.clearCombatPlaced(event.getServer());
        com.maidsmart.build.BuildPlan.clearAll();
        com.maidsmart.build.ChunkFreeze.clearAll();
        com.maidsmart.build.BlueprintLib.setServer(null);
        // v1.1.0 实测四十四：撤掉全部女仆区块强制加载票（防票残留锁区块）
        com.maidsmart.follow.MaidChunkLoadManager.releaseAll(event.getServer());
    }

    /**
     * v1.5.28：挖矿搭方块 10 秒清理的全局兜底——旧版清理只挂在挖矿行为
     * m_6725_ 上，挖完矿行为停止后不再运行 → 搭的方块永久残留。
     * 现在每 tick 由这里统一执行，与行为生命周期完全无关。
     */
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
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
        long gt = server.m_129785_().iterator().next().m_46467_();
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
        }
        // v1.1.0 实测七十：一键集合"未加载区块召回"队列推进（空队列零开销）
        com.maidsmart.follow.MaidChunkLoadManager.tickPending(server);
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
            // v1.5.252q：清扫自动生成的钓鱼坐垫（任务解除/脱离坐垫超 2 秒 → 删除）
            // v1.5.252r：逻辑在普通类 FishingChairService（mixin 类不可被普通代码直接引用）
            com.maidsmart.fishing.FishingChairService.sweep(server);
        }
        // v1.5.275：每 3 tick 高频维持钓鱼女仆走位（FindSit 12 tick 间隙 + 站立行为
        // 清 WALK_TARGET → 一步一停；3 tick 内补回 → 连续走）
        if (++this.seatWalkTimer >= 3) {
            this.seatWalkTimer = 0;
            try {
                net.minecraft.world.phys.AABB whole = new net.minecraft.world.phys.AABB(
                        Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                        Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
                for (net.minecraft.server.level.ServerLevel level : server.m_129785_()) {
                    for (com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid m :
                            level.m_45976_(com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid.class, whole)) {
                        if (m.m_6084_()) {
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
     * v1.5.13 生存优化：玩家首次进入世界时赠送 5 张蓝图卷轴
     * （每种内置蓝图各 1 张，只送一次，标记存在玩家持久 NBT 里）。
     */
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onPlayerLoggedIn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            return;
        }
        net.minecraft.nbt.CompoundTag data = player.getPersistentData();
        // v1.5.347:类型写错——写入是 Byte(1),contains 却查 99(NBT 无此类型)永远 false,
        // 导致每次进游戏都重发手册。改为 TAG_BYTE(1) 后"只送一次"标记才真正生效。
        if (data.m_128425_("maid_smart_blueprints_given", 1)) {
            return;
        }
        data.m_128344_("maid_smart_blueprints_given", (byte) 1);
        net.minecraft.world.item.ItemStack[] gifts = {
                new net.minecraft.world.item.ItemStack(ProMaidMod.BLUEPRINT_BOOK.get())
        };
        int given = 0;
        for (net.minecraft.world.item.ItemStack gift : gifts) {
            if (player.m_150109_().m_36054_(gift)) {
                given++;
            }
        }
        player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                "\u00a7a[maid_smart] \u300aPromaid \u624b\u518c\u300b\u5df2\u9001\u5230\u4f60\u80cc\u5305\uff01"
                        + "\u624b\u6301\u53f3\u952e\u6253\u5f00\u5168\u90e8\u56fe\u7eb8\u5217\u8868\uff08\u542b\u7f3a\u6750\u63d0\u793a\uff09\uff0c"
                        + "\u70b9\u51fb\u5373\u8ba9\u5973\u4ec6\u5efa\u9020\uff08\u5973\u4ec6\u9700\u5148\u5207\u5230\u201c\u5efa\u7b51\u201d\u4efb\u52a1\uff09\u3002"
                        + "\u62df\u91cd\u65b0\u83b7\u53d6\uff1a/give @p maid_smart:blueprint_book\u3002"));
    }

    /**
     * v1.5.49：注册指令 —— /maid_smart summon_builders &lt;数量&gt;（批量召唤建造女仆）
     */
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onRegisterCommands(net.minecraftforge.event.RegisterCommandsEvent event) {
        com.maidsmart.command.MaidArmyCommand.register(event.getDispatcher());
    }

    /**
     * v1.5.47：经验球归属（借鉴 maidmining）——8 格内有玩家 → 让原版（玩家优先）；
     * 否则 24 格内最近挖矿女仆直接吸收经验并取消生成（治"女仆挖矿 + 玩家同区双倍经验"）。
     */
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onOrbJoin(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {        if (event.getLevel().m_5776_() || !(event.getEntity() instanceof net.minecraft.world.entity.ExperienceOrb orb)) {
            return;
        }
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) event.getLevel();
        // 8 格内有玩家 → 原版行为（玩家优先吸收）
        net.minecraft.world.entity.player.Player near = level.m_45976_(
                        net.minecraft.world.entity.player.Player.class, orb.m_20191_().m_82400_(8.0))
                .stream().findFirst().orElse(null);
        if (near != null) {
            return;
        }
        // 24 格内最近挖矿女仆 → 直接吸收（TLM pickupXPOrb），取消生成
        com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid best = null;
        double bestDist = Double.MAX_VALUE;
        for (com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid m
                : level.m_45976_(com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid.class,
                orb.m_20191_().m_82400_(24.0))) {
            if (m.getTask() == null || !MaidMineTask.UID.equals(m.getTask().getUid())) {
                continue;
            }
            double d = m.m_20238_(orb.m_20182_());
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
        // v1.1.0：伐木任务（克隆挖矿架构——木材表/斧判定/树叶放行视线/连锁砍整棵树）
        manager.add(new MaidWoodTask());
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
                            + "CombatTactics=230 ToolAutoEquip=200 AidOwner=190 Torch=185 Shield=180");
                }
                // 自保：core 行为（任何 activity 都运行）。
                // 优先级 250 > TLM 全部行为（core 最高 99：ClearSleep；跟随=3；Panic/Await=1），
                // 保证自保压过战斗/任务/跟随/待命/睡觉/恐慌一切状态，且不会被任何行为抢占。
                // v1.5.25：落地水（优先级 240，低于自保 250）——被动技能，不进自保状态，
                // 有水桶+坠落时自动放水缓冲，与自保互不干扰。
                // （v1.5.26 的反击背叛女仆已随关系联动迁往 Heartfelt-connection）
                // v1.5.90：任务工具自动装备（攻击/弓/弩/三叉戟/挖矿）——canUse 里
                // 换完即停、不占行为槽；优先级低于自保/落地水、高于施工区避让
                return List.of(
                        Pair.of(250, new SelfPreservationBehavior()),
                        // v1.1.0：搭路（主人在上方时垫方块靠近，默认关）——低于自保、
                        // 高于落地水/战术：搭路条件本身排除威胁/自保，不与战斗抢移动
                        Pair.of(245, new com.maidsmart.task.BridgeUpBehavior()),
                        Pair.of(240, new com.maidsmart.combat.WaterClutchBehavior()),
                        // v1.5.134：单兵作战战术（PVP 式走位/拉扯/距离控制）——低于自保/落地水，
                        // 高于自动装备/施工区避让；Brain 1.20.1 无高优先级阻断，不影响 WORK 战斗行为
                        Pair.of(230, new com.maidsmart.combat.MaidCombatTacticsBehavior()),
                        Pair.of(200, new com.maidsmart.task.MaidToolAutoEquipBehavior()),
                        // v1.5.189：玩家贴身辅助（被动技能，非工作状态）——低于自动装备/
                        // 战斗/自保，高于施工区避让；自动喂食治疗主人 / 黑暗插火把 / 共享盾牌
                        Pair.of(190, new com.maidsmart.combat.MaidAidOwnerBehavior()),
                        Pair.of(185, new com.maidsmart.combat.MaidTorchPlacerBehavior()),
                        Pair.of(180, new com.maidsmart.combat.MaidShieldShareBehavior())
                        // v1.5.212：施工区避让已删除——自保 antiSuffocate 每 tick 防窒息
                        // 兜底后，"非建造女仆接近施工区会逃离"没有存在意义
                        //（原 Pair.of(150, new BuildAreaAvoidBehavior())）
                );
            }
        });
    }
}
