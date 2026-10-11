package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.task.MaidWorkTags;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * v1.3.3「防刷怪：发现刷怪笼就插火把」——
 * 「有玩家建议女仆在发现刷怪笼以后如果手上有火把会优先在刷怪笼上插火把（用来防止刷怪，
 *  当然可能涉及我的知识盲区，如果有和火把一样功效的东西，那么一样可以接受判定）。
 *  如果这一块区域被判定为 Home 模式工作区域（河童的罗盘标记的那一块）则不执行这个链路。」
 *
 * ── 为什么"光"就够（这一条是本类的立身之本，写清楚免得后人以为是玄学）──
 * 原版刷怪笼每次尝试生成都要过 {@code Monster.isDarkEnoughToSpawn}：目标位置亮度 ≤ 7 才放行
 * （字节码实证：{@code monsterSpawnLightTest} 是 0~7 的均匀分布）。刷怪笼的候选生成区是以自己
 * 为中心的 8×3×8 格，而光照衰减是每格 -1——**只要刷怪笼脚下/身边有一个亮度 14 的东西，
 * 整个 8×3×8 里最远的那一格也还有 10，全部在门限之上，刷怪笼直接哑掉**。
 * 所以本类不认"火把"这个名字，认的是那一件事：**放下去之后方块自身发光 ≥ {@link #LIGHT_ENOUGH}**
 * （火把 14 / 灵魂火把 10 / 萤石 15 / 海晶灯 15 … 都过；红石火把只有 7，留着它继续刷怪，
 * 所以清单里刻意没有它——与 {@code MaidTorchPlacerBehavior} 里"移出红石火把"同一个理由，
 * 那边还多一条"会发红石信号"）。这就是。
 *
 * ── 与 {@code MaidTorchPlacerBehavior}（被动插火把）的关系 ──
 * 那一个是"跟在主人身边、主人脚下黑了就补一根"，判据是"手上拿着火把"这一小张清单，
 * 目的是**人身边的照明**；本类判的是"能不能真的把刷怪笼按死"，判据是**亮度数字**，
 * 目的是**断刷怪**。两件事不同，所以清单各有一份、互不包含；但优先级顺序一致
 * （普通火把 > 灵魂火把 > 别的灯），并且把那一份的三件（普通火把/灵魂火把/tacz 火把）
 * 全含在内——时她要用的就是那三件。
 *
 * ── Home 工作区（河童的罗盘）里一律不碰 ──
 * 见 {@link #inHomeWorkArea}：圈里可能是玩家**故意留着**的刷怪塔/刷怪笼陷阱。这条判据
 * 读的是 TLM 的活动范围圆心与半径（{@code getRestrictCenter/getRestrictRadius}），
 * 与扫帚模式那把夹取（{@link MaidBroomKit#clampToHome}）读的是同一对字段——口径一处。
 *
 * ── 顺手说明"为什么它排在那么高的优先级"──
 * ，落在这里 = core 行为优先级 186（见 {@code ProMaidExtension}），
 * 比被动插火把（185）高、比自动装备（200）低：她发现刷怪笼会**先过去插火把**再回去干活。
 * 不想要这个"优先"就关掉 {@code combat.spawnerTorch.enable}，或者把优先级那一行改小。
 */
public class MaidSpawnerTorchBehavior extends Behavior<EntityMaid> {

    public MaidSpawnerTorchBehavior() {
        super(Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /* ==================== 数值 ==================== */

    /** 扫描间隔（tick）= 4 秒。刷怪笼不会自己跑，扫得勤只是白烧 CPU */
    private static final int SCAN_GAP = 80;
    /** 扫描的垂直半高（格）：地下刷怪笼基本就在她上下几格内，横向半径才需要可配 */
    private static final int SCAN_DY = 4;
    /** 动手距离（格）：走到这么近才放下（比玩家手长 4.5 略收，免得隔墙"隔空插火把"） */
    private static final double PLACE_RANGE = 4.0;
    /**
     * 点亮阈值（0~15）：**必须 ≥ 8**——原版敌对生物要求所在位置亮度 ≤ 7 才刷
     * （见类注释）。这一条就是本类判"这件东西跟火把一样管用"的唯一数字判据。
     */
    private static final int LIGHT_ENOUGH = 8;
    /** 单个刷怪笼的耐心（tick）= 20 秒：走不到（卡墙/够不着）就放弃，进冷却 */
    private static final long GIVE_UP_TICKS = 400;
    /** 放弃后的冷却（tick）= 60 秒：同一只刷怪笼别每 4 秒重来一次 */
    private static final long RETRY_COOLDOWN = 1200;
    /** 走过去的移动速度倍率（TLM 默认走路 = 1.0） */
    private static final float WALK_SPEED = 1.0F;
    /** "她没法插火把"这条日志的限频（tick）= 30 秒，防刷屏 */
    private static final long SKIP_LOG_GAP = 600;
    /** 实测八百三十七：读刷怪数据抛异常时的留痕限频（毫秒）= 30 秒——异常路径上再也不能一行都不写 */
    private static volatile long lastThreatFailLog = 0L;

    /* ---------- 实测八百三十/八百三十b：两个修正（隔墙不拆 / 没在刷不拆） ---------- */

    /** 「这只刷怪笼现在真的在刷怪吗」判据①：附近这个半径（格）内有敌对生物在游荡 → 它在刷 */
    private static final double THREAT_MOB_RADIUS = 12.0;
    /** 判据②：原版刷怪笼的激活半径（格）——16 格内没有玩家它根本不 tick（= 现在没在刷） */
    private static final double VANILLA_ACTIVATION_RANGE = 16.0;
    /** 「现在没在刷怪 → 这次不拆」之后重看的间隔（tick）= 10 秒：比 {@link #RETRY_COOLDOWN} 短，
     *  这样玩家一走近、或怪又刷出来，她能很快重判并动手 */
    private static final long INACTIVE_COOLDOWN = 200;
    /** 判据③（实测八百三十b）：**空笼子**（没设任何刷怪数据）一律不拆；同理只刷牛羊猪这类
     *  和平生物的笼子也不拆。见 {@link #spawnThreat}。 */

    /**
     * 认作"灯"的注册名清单（优先级从上到下）。**真正生效的判据是发光 ≥ {@link #LIGHT_ENOUGH}**，
     * 这张表只是"允许动用的范围"：与被动插火把那份一样，宁可写死注册名也不写死物品类型——
     * 模组物品拿不到类，注册名是唯一稳的抓手；末尾另有"名字以 torch/lantern 结尾"的兜底。
     */
    private static final String[] LIGHT_IDS = {
            "minecraft:torch",              // 普通火把 14——
            "minecraft:soul_torch",         // 灵魂火把 10
            "minecraft:lantern",            // 灯笼 15
            "minecraft:soul_lantern",       // 灵魂灯笼 10
            "minecraft:glowstone",          // 萤石 15
            "minecraft:shroomlight",        // 菌光体 15
            "minecraft:sea_lantern",        // 海晶灯 15
            "minecraft:ochre_froglight",    // 蛙明灯 15（三种颜色）
            "minecraft:verdant_froglight",
            "minecraft:pearlescent_froglight",
            "minecraft:jack_o_lantern",     // 南瓜灯 15
            "minecraft:end_rod",            // 末地烛 14
            "minecraft:campfire",           // 篝火 15（最次选：它还带"点火/烹饪"语义）
            "minecraft:soul_campfire",      // 灵魂篝火 10
    };

    /* ==================== 每只女仆的状态 ==================== */

    /** 拆笼借镐时从副手摘下的那件原物品，存在**她自己的持久 NBT**里。
     *  【为什么不是堆内存】跨维度传送/世界重载/复活都会换实体实例，而 {@code AIM} 是
     *  WeakHashMap（键=旧实例）随之回收；旧版只放 {@code Aim.savedOffhand} 里，于是这些
     *  事件让她副手那件彻底消失（玩家实测丢过盾牌、剑、靴子）。NBT 跟着实体走，换实例也不丢。 */
    private static final String SAVED_OFFHAND_TAG = "promaid_torch_saved_offhand";

    /** 女仆 → 她的目标刷怪笼与计时（WeakHashMap：女仆卸载即自然回收） */
    private static final Map<EntityMaid, Aim> AIM =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 把一件物品写进/清出她的持久 NBT（空栈 = 删键）。异常一律吞掉：这条链绝不能反过来咬人。 */
    private static void stashOffhand(EntityMaid maid, ItemStack stack) {
        if (maid == null) {
            return;
        }
        try {
            net.minecraft.nbt.CompoundTag pd = maid.getPersistentData();
            if (stack == null || stack.m_41619_()) {
                pd.m_128473_(SAVED_OFFHAND_TAG);
            } else {
                pd.m_128365_(SAVED_OFFHAND_TAG, stack.m_41739_(new net.minecraft.nbt.CompoundTag()));
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
        }
    }

    /** 读回她 NBT 里压着的那件原副手物品（没有 = 空栈） */
    private static ItemStack savedOffhandOf(EntityMaid maid) {
        if (maid == null) {
            return ItemStack.f_41583_;
        }
        try {
            net.minecraft.nbt.CompoundTag pd = maid.getPersistentData();
            if (!pd.m_128441_(SAVED_OFFHAND_TAG)) {
                return ItemStack.f_41583_;
            }
            return ItemStack.m_41712_(pd.m_128469_(SAVED_OFFHAND_TAG));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            return ItemStack.f_41583_;
        }
    }

    private static final class Aim {
        /** 正在处理的刷怪笼（null = 没有） */
        BlockPos spawner;
        /** 上次扫描的 gameTime */
        long lastScan;
        /** 这个刷怪笼是从什么时候开始处理的（超时放弃用） */
        long since;
        /** 放弃过的那个刷怪笼 + 冷却到期时间（同一只别每 4 秒重来） */
        BlockPos coolPos;
        long coolUntil;
        /** 上次记"工作区里不碰"的时间（限频用） */
        long skipLogged;
        /** 实测八百二十二：这一只当前走【拆除】模式（true）还是旧【插火把】模式（false）。
         *  发现那一刻就定死——模组刷怪笼无视光照，插火把没意义，故默认一律拆。 */
        boolean breaking;
        /** 实测八百二十二：拆除进度（0~1，累积到 1 就破）。只在【拆除】模式下有效 */
        float breakProgress;
        /** 实测八百二十二：上次已广播的裂缝阶段（-1 = 还没发过；同阶段不重发，省带宽） */
        int lastCrackStage = -1;
        /** 实测八百二十六：干活状态下镐【借在副手】——true = 副手里那把镐是本链路借来的 */
        boolean borrowedOffhand;
        /** 实测八百二十七：上一次"身上有没有能挖的镐"的探测结果与时刻（缓存 40 tick，
         *  canStillUse 每 tick 都问，而精妙背包探测要 extract/reinsert —— 别每 tick 折腾）。 */
        boolean digToolCache;
        long digToolCacheAt = Long.MIN_VALUE;
    }


    /* ==================== 走位所有权（v1.3.0(beta) 实测六百六十四） ==================== */

    /**
     * "这一发 moveTo 是我们自己发的"——{@code SpawnerTorchNavGuardMixin} 据此放行。
     *
     * <p>【为什么用这种窄门】让位闸要掐掉**别的驱动**发起的直连寻路，可它们与本类调的是
     * 同一个 {@code PathNavigation.moveTo}，只看参数分不出谁是谁。于是本类在自己那一发外面
     * 夹一层自标记：服务端单线程、调用是同步的，闸看到标记就让路。try/finally 保证异常时
     * 一定摘掉（否则那一 tick 之后她自己的寻路也会被自己掐掉）。
     */
    private static volatile boolean SELF_NAV = false;

    /** 闸的询问口：true = 现在这一发是插火把这条链自己发的 */
    public static boolean selfNav() {
        return SELF_NAV;
    }

    /** 直连导航（换掉当前路径，不走 MoveToTargetSink）——外面包一层自标记，让让位闸放行 */
    private static void navTo(EntityMaid maid, BlockPos pos, float speed) {
        try {
            SELF_NAV = true;
            maid.m_21573_().m_26519_(pos.m_123341_() + 0.5, pos.m_123342_() + 0.5,
                    pos.m_123343_() + 0.5, speed);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
        } finally {
            SELF_NAV = false;
        }
    }

    /**
     * 「她为什么没去插火把」的留痕（30 秒一条上限，与"工作区里不碰"共用同一个限频字段）。
     *
     * <p>【为什么需要】"附近没扫到刷怪笼""手上一件灯都没有"这两条此前是**静默 return**——
     * 玩家只能看到"她没去插"，分不清是没找到、没灯、还是被工作区挡住了（那一条本来就打日志）。
     */
    private static void noteIdle(Aim aim, EntityMaid maid, long gameTime, String why) {
        noteOnce(aim, maid, gameTime, why + "（搜索半径 " + (int) Math.round(radiusCfg())
                + " 格、上下各 " + SCAN_DY + " 格）");
    }

    /** 限频写一行（30 秒上限）——{@link #noteIdle} 与 {@link #noteBlocked} 共用这一枚计数位 */
    private static void noteOnce(Aim aim, EntityMaid maid, long gameTime, String msg) {
        try {
            if (gameTime - aim.skipLogged < SKIP_LOG_GAP) {
                return;
            }
            aim.skipLogged = gameTime;
            com.maidsmart.tool.PromaidLog.log("刷怪笼",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " " + msg);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
        }
    }

    /**
     * 实测八百二十七：**"她本该动却没动"的留痕**（与 {@link #noteIdle} 共用 30 秒限频与同一枚
     * 计数位，所以两者最多一共 30 秒一条，不会刷屏）。
     *
     * <p>【为什么非要有】这条链路过去有一整类**什么都没有发生**的状态：行为连 {@code tick()} 都
     * 没被调用（见 {@link #m_6737_} 的说明），玩家只能看到她不去拆，日志里一行都没有——只能靠
     * 猜。现在凡是被闸挡下/主动让位，都在这里写一行说清**是哪一闸、她当时什么状态**（末尾带
     * {@link #stateOf} 快照），下次再出问题一眼就能定位。
     */
    private static void noteBlocked(ServerLevel level, EntityMaid maid, String why) {
        try {
            if (maid == null || level == null) {
                return;
            }
            Aim aim;
            synchronized (AIM) {
                aim = AIM.get(maid);
                if (aim == null) {
                    aim = new Aim();
                    AIM.put(maid, aim);
                }
            }
            noteOnce(aim, maid, level.m_46467_(), "被挡：" + why + stateOf(maid));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
        }
    }

    /** 被挡留痕用的一行状态快照（坐/乘/睡/可动/目标/自保）——省得再来一轮"猜她当时怎么了" */
    private static String stateOf(EntityMaid maid) {
        try {
            return " [坐=" + maid.isMaidInSittingPose()
                    + " 乘=" + maid.m_20159_()
                    + " 睡=" + maid.m_5803_()
                    + " 可动=" + maid.canBrainMoving()
                    + " 目标=" + (maid.m_5448_() != null)
                    + " 自保=" + maid.getPersistentData().m_128471_(
                            com.maidsmart.combat.SelfPreservationBehavior.PRESERVE_TAG)
                    + " 任务=" + (com.maidsmart.task.MaidWorkTags.isAttackTask(maid) ? "战斗" : "非战斗")
                    + "]";
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            return "";
        }
    }

    /* ==================== 生命周期 ==================== */

    /**
     * 能不能开跑：总开关开着 + 她还活着 + **不是乘客**（骑扫帚/坐椅子时她不走路，也就走不到
     * 刷怪笼跟前）+ 没坐着 + 没睡着 + 手上没有攻击目标（先打完再插火把）+ 不在自保逃跑里。
     *
     * <p>【为什么不在这里判"有没有火把"】那要翻一遍背包，而 canUse 是**每 tick** 调的；
     * 这里只做零成本的闸门，"有没有灯/镐"留给 {@link #m_6725_} 在扫描那一步（4 秒一次）判。
     *
     * <p>【实测八百二十七·为什么不再问 {@code canBrainMoving()}】那是 TLM 的一把**粗闸**，等于
     * "没坐着 && 不是乘客 && 没睡着 && **没被拴绳**"。被拴绳这一条与本链路毫无关系——我们走过去
     * 用的是直连 {@code moveTo}，不经过 TLM 的跟随闸，牵着绳她照样能走——却会在玩家用
     * 【牵绳档 / 空袭挂载】时把整条链路**静默掐死**：日志里连一行都没有，只看到她"就是不去拆"。
     * 现在拆成上面四条精确判据，并且**每一条被挡都会留痕**（{@link #noteBlocked}），
     * 不再有"什么都没发生、也查不到原因"的黑箱。
     */
    @Override
    protected boolean m_6114_(ServerLevel level, EntityMaid maid) {
        if (!enabled()) {
            noteBlocked(level, maid, "总开关关着（spawnerTorch.enable=false）");
            return false;
        }
        if (maid == null) {
            return false;
        }
        if (!maid.m_6084_()) {
            noteBlocked(level, maid, "她已不在（死亡/未加载）");
            return false;
        }
        try {
            if (maid.m_20159_()) {
                noteBlocked(level, maid, "她是乘客（骑扫帚/坐骑/坐椅子）→ 她自己不会走路");
                return false;
            }
            if (maid.isMaidInSittingPose()) {
                noteBlocked(level, maid, "她坐着（sitting）→ 她自己不会走路");
                return false;
            }
            if (maid.m_5803_()) {
                noteBlocked(level, maid, "她睡在床上 → 醒了再说");
                return false;
            }
            // 实测八百二十二：只有【插火把】模式才"有目标先打完"。拆除模式刻意不看攻击目标——
            // 模组刷怪笼一刷怪她就被写目标，若这里让位就永远拆不完（
            //
            // NeutralThreatDriver 原地反击（见 AutoCombatTargeting 的拆>战斗闸）。
            if (maid.m_5448_() != null && !breakMode()) {
                noteBlocked(level, maid, "她手上有攻击目标（插火把模式：先打完再去）");
                return false; // 有攻击目标：先打完
            }
            if (maid.getPersistentData().m_128471_(
                    com.maidsmart.combat.SelfPreservationBehavior.PRESERVE_TAG)) {
                noteBlocked(level, maid, "她正在自保（自保 250 最高，一票否决）");
                return false; // 自保逃跑中：别去插火把/拆笼（自保 250 永远最高）
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
        }
        return true;
    }

    /**
     * 父类默认 canStillUse = false（试一次就停），必须重写为 true；这里**只保留两条硬失效**：
     * 「总开关被关掉」与「自保接管」（250 最高，一票否决），其余让位判断全部挪到 {@link #m_6725_} 里做。
     *
     * <p>【为什么总开关要在这里也问一遍】Brain 对**正在跑**的行为每 tick 只问 canStillUse（不问你
     * {@code m_6114_}）：只在 canUse 里问开关，等于"关掉开关之后，已经在跑的那一半照跑完"——
     * 拆笼最多还能再拆 20 秒（玩家看到的就是"开关关了/切了 DIY，她还在挖"）。
     * 停下来的收尾走 {@link #m_6732_}：借来的副手镐照旧还回去。
     *
     * <p>【实测八百二十七·为什么"战斗中没镐"不能写在这里（这是这次抓到的真凶）】反编译实证：
     * Brain 每 tick 对 RUNNING 行为调的是 {@code Behavior.m_22558_}，它**先问 canStillUse**——
     * 返回 false 就直奔 {@code doStop()}，连 {@code tick()} 都不会被调用。所以一旦把"战斗中没镐
     * →让位"写在 canStillUse 里，整条链路就会彻底静默：不扫描、不借镐、不打任何日志。
     * 玩家实测到的正是"她处于攻击状态时就是不换成镐去挖"，而日志里一行都没有（无从查起）。
     * 现在让位判断放在 tick 里（行为保持 RUNNING，让位时不动走位、不碰副手，但一定留一行说明）。
     */
    @Override
    protected boolean m_6737_(ServerLevel level, EntityMaid maid, long gameTime) {
        if (maid == null || !maid.m_6084_()) {
            return false;
        }
        if (!enabled()) {
            return false; // 总开关关着（含 DIY 一键关）→ 在跑的那一半也立刻停、副手镐照旧归还
        }
        Aim running = AIM.get(maid);
        if (running != null && running.breaking && !breakMode()) {
            return false; // 拆笼开关刚被关掉 → 这一只不再拆（下一轮扫描回到插火把链路）
        }
        if (maid.getPersistentData().m_128471_(
                com.maidsmart.combat.SelfPreservationBehavior.PRESERVE_TAG)) {
            return false; // 自保接管（唯一一票否决；其余让位见 m_6725_，且一定留痕）
        }
        return true;
    }

    /** v1.3.0(beta)：行为停下（换任务/被移除）时一定要把走位所有权交还 */
    @Override
    protected void m_6732_(ServerLevel level, EntityMaid maid, long gameTime) {
        // 实测八百二十七修订【为什么拆笼途中被切走不能清标记】：她拆到一半被 AutoCombatTargeting
        // 切任务时，TLM 的 setTask → refreshBrain → brain.stopAll 会调到这里。旧版无条件
        // restoreOffhand + 清两枚标记 → 下一秒新实例从 AIM（静态表，还在）接着拆，但**标记已丢**：
        // isBreakingSpawner 转 false → 不再借副手镐、AutoCombatTargeting 也不再拦切换 → 她在战斗里
        // 永远换不出镐、主手被战斗自动装备占着（的根因）。
        // 所以：**这一只还在拆且她还能动**（活着 / 不在自保 / 不坐着 / 不骑乘 / 未超时）→ 标记与
        // 副手那把镐**原样保留**，下一秒新实例接着拆（拆 > 战斗；）。
        // 其余情况（自保接管 / 死亡 / 坐骑 / 超时 / 正常拆完）照旧还镐 + 交还标记。
        Aim aim = AIM.get(maid);
        boolean keepPin = false;
        if (aim != null && aim.breaking && aim.spawner != null) {
            // 实测八百二十七：这里的"她还能不能继续拆"改用与 m_6114_ 同一套精确判据
            // （不再问 canBrainMoving——它把"被拴绳"也算成不可动，会把牵着绳拆到一半的她误停）
            keepPin = maid != null && maid.m_6084_() && !maid.m_20159_()
                    && !maid.isMaidInSittingPose() && !maid.m_5803_()
                    && maid.m_9236_().m_46467_() - aim.since <= GIVE_UP_TICKS
                    && !maid.getPersistentData().m_128471_(
                            com.maidsmart.combat.SelfPreservationBehavior.PRESERVE_TAG);
        }
        if (!keepPin) {
            // 实测八百二十六：行为停下（换任务/被移除/自保接管）→ 先把借到副手的镐还回去，
            // 再交还走位所有权——否则副手会被我们的镐一直占着（盾牌/食物回不来）。
            if (aim != null) {
                restoreOffhand(maid, aim);
            }
            MaidWorkTags.setSpawnerTorch(maid, false);
            MaidWorkTags.setSpawnerBreak(maid, false); // 实测八百二十二：拆除标记一并交还
        }
        super.m_6732_(level, maid, gameTime);
    }

    @Override
    protected void m_6725_(ServerLevel level, EntityMaid maid, long gameTime) {
        if (maid == null || !maid.m_6084_()) {
            return;
        }
        // 实测八百三十三：先补一道"借镐摘下的副手物品"兜底回收——跨维度传送/世界重载换来的是
        // 新实体实例（AIM 随旧实例回收），只有 NBT 还压着那件原物品；不补这一手它就永远回不来。
        // 没压着东西时只是一次 NBT contains，零开销。
        recoverSavedOffhand(maid);
        Aim aim;
        synchronized (AIM) {
            aim = AIM.get(maid);
            if (aim == null) {
                aim = new Aim();
                AIM.put(maid, aim);
            }
        }
        // 【实测八百二十七·战斗让位的正确写法】
        // 战斗任务、且还没开始拆时——两种让位：①插火把模式（战斗永远优先）；②拆除模式但身上
        // 没有能挖的镐（）。
        // **为什么写在这里而不是 canStillUse**：见 m_6737_ 的说明——写在 canStillUse 里会让
        // Brain 连 tick() 都不调，整条链路静默（不扫描、不借镐、不留痕），玩家实测就是
        // "她处于攻击状态时不会自己换成镐去挖，而且日志里什么都没有"。写在这里：行为保持
        // RUNNING、让位时**不动走位标记、不碰副手**（其余系统毫无影响），但一定留一行说明。
        // 只在"手上没有任何在推进的刷怪笼"（aim.spawner == null）时让位——已有目标的话
        // 交给下面的 ⓪/① 走原本的清目标/超时放弃流程，免得把在途目标晾在那儿没人收尾。
        if (aim.spawner == null && MaidWorkTags.isAttackTask(maid) && !isBreakingSpawner(maid)) {
            if (!breakMode()) {
                noteBlocked(level, maid, "战斗中 + 插火把模式 → 本链路让位（战斗永远优先）");
                return;
            }
            if (pickaxeMode() && !hasDigToolCached(maid, gameTime)) {
                noteBlocked(level, maid, "战斗中，手上/背包里没有能挖的镐 → 本链路让位，正常战斗链路照跑（需求：手头没有破坏的工具就算了）");
                return;
            }
        }
        // ⓪【v1.3.0(beta) 实测六百六十四】脑里出现攻击目标 → 立刻清目标 + 摘掉走位所有权标记
        //    （战斗永远优先；标记不清的话她会被"走去插火把"那面闸按住手脚）
        //    实测八百二十二：**拆除模式不看这一条**——模组刷怪笼一刷怪她就被写目标，让位就永远
        //    拆不完。拆除模式下她"钉住"：见 canUse / AutoCombatTargeting 拆>战斗闸 / 原地反击。
        if (maid.m_5448_() != null && !breakMode()) {
            if (aim.spawner != null) {
                MaidWorkTags.setSpawnerTorch(maid, false);
                clearTarget(maid, aim);
            }
            return;
        }
        // ① 手上已经有目标 → 继续推进它（走过去 / 放下 / 判定不用放了 / 拆）
        if (aim.spawner != null) {
            if (aim.breaking) {
                // 实测八百二十七修订【每 tick 重挂走位所有权】：她拆到一半时任何一次 setTask
                // （AutoCombatSwitch 的距离切换 idle↔attack↔gun_attack、威胁消失还原…）都会
                // refreshBrain → brain.stopAll → 调 stop()，把 SPAWNER_BREAK 标记清掉。旧版只在
                // "发现那一刻"挂标记一次，被清后就再也挂不回来 → isBreakingSpawner 转 false →
                // 不再借副手镐、AutoCombatTargeting 也不再把她钉住（）。
                // 这里每 tick 重挂一次：只要还在拆，走位所有权与"拆>战斗"就恒成立（
                // "所有链路都必须在刷怪笼被破坏掉之后才行"）。
                setSpawnerOwner(maid, true);
                // 拆除模式——只要她还有镐就继续拆；被攻击也钉住（见上）。
                // walkAndBreak 返回 false = 这一只处理完了（拆掉了 / 放弃 / 超时）→ 清目标记冷却
                if (!walkAndBreak(level, maid, aim, gameTime)) {
                    aim.coolPos = aim.spawner;
                    aim.coolUntil = gameTime + RETRY_COOLDOWN;
                    clearTarget(maid, aim);
                }
                return;
            }
            if (!stillNeedsTorch(level, maid, aim.spawner)) {
                clearTarget(maid, aim);
            } else if (!walkAndPlace(level, maid, aim, gameTime)) {
                // 放弃 / 插完了：记冷却，清目标
                aim.coolPos = aim.spawner;
                aim.coolUntil = gameTime + RETRY_COOLDOWN;
                clearTarget(maid, aim);
            }
            return;
        }
        // ② 没有目标 → 按间隔扫一次
        if (gameTime - aim.lastScan < SCAN_GAP) {
            return;
        }
        aim.lastScan = gameTime;
        boolean breaking = breakMode(); // 实测八百二十二：默认一律拆（模组刷怪笼无视光照，火把压不住）
        if (breaking) {
            // 拆除模式：身上/副手得有能挖的镐（默认要求；见 breakRequirePickaxe）。没有就先不去
            // 实测八百二十七：战斗中也走这一条——没镐就当场不启动，把副手/战斗全还给正常链路
            if (pickaxeMode() && !hasDigToolSomewhere(maid)) {
                noteIdle(aim, maid, gameTime, "手上/背包里没有能挖的镐 → 这条链路不启动（拆除需要镐；"
                        + "不带镐徒手要 25 秒，中途必被刷出来的怪打断）");
                return;
            }
        } else if (!hasLightItem(maid)) {
            noteIdle(aim, maid, gameTime, "手上/背包里一件能当灯的东西都没有 → 这条链路不启动");
            return; // 手上一件灯都没有：整条链路不启动（）
        }
        int[] occluded = new int[1];
        BlockPos found = scan(level, maid, occluded, aim, gameTime);
        if (found == null) {
            if (occluded[0] > 0) {
                // 实测八百三十七：以前这种情况只报"附近没扫到刷怪笼"（因为 scan 里对"隔着方块"的
                // 候选是静默跳过的）——玩家看到的就和"这儿压根没笼子"一模一样，无从判断。
                noteIdle(aim, maid, gameTime, "扫到 " + occluded[0] + " 个刷怪笼，但都与她之间隔着方块"
                        + "（隔着墙 / 挡在方块后面）→ 当没看见，不隔墙拆");
            } else {
                noteIdle(aim, maid, gameTime, "附近没扫到刷怪笼");
            }
            return;
        }
        if (found.equals(aim.coolPos) && gameTime < aim.coolUntil) {
            return; // 这一只刚放弃过，冷却期内不理它
        }
        if (inHomeWorkArea(maid, found)) {
            // 河童罗盘圈出来的家/工作区：**一律不碰**——那可能是玩家故意留的刷怪塔
            if (gameTime - aim.skipLogged > SKIP_LOG_GAP) {
                aim.skipLogged = gameTime;
                com.maidsmart.tool.PromaidLog.log("刷怪笼", com.maidsmart.tool.PromaidLog.nameOf(maid)
                        + " 刷怪笼在「在家模式/工作区」圈里（" + pos(found)
                        + "）→ 不插火把也不拆（玩家可能故意留着它刷怪）");
            }
            aim.coolPos = found;
            aim.coolUntil = gameTime + RETRY_COOLDOWN;
            return;
        }
        // 实测八百四十五【拆除黑名单：任何情况下都不拆】——压在本条之下所有判据之前。
        // 旧版把"有没有在刷怪"排在最前面（下面的 spawnSkipReason 第①条就是"旁边有敌对生物 → 拆"），
        // 于是洞窟里随便一只僵尸路过，空笼子就被当成"在出货"拆掉了——正是开发者报的那条现象。
        // 名单里的笼子（默认 空笼子 / 只刷和平生物的笼子）在扫描到的那一刻直接放过，连
        // 「只在真在刷怪时才拆」关掉也照样不拆；只对**拆除**生效，插火把模式不受影响（插火把不破坏它）。
        if (breaking) {
            String hit = breakBlacklistHit(level, found);
            if (hit != null) {
                if (gameTime - aim.skipLogged > SKIP_LOG_GAP) {
                    aim.skipLogged = gameTime;
                    com.maidsmart.tool.PromaidLog.log("刷怪笼", com.maidsmart.tool.PromaidLog.nameOf(maid)
                            + " 刷怪笼 " + pos(found) + " 在黑名单里（命中「" + hit + "」） → 永不拆，留着");
                }
                aim.coolPos = found;
                aim.coolUntil = gameTime + RETRY_COOLDOWN; // 名单里的笼子不必重看，等冷却过了再静默跳过
                return;
            }
        }
        // 实测八百三十 + 八百三十七【只在真在刷怪时才拆】——
        //
        // 刷怪笼一律不碰（留着给玩家收走 / 改造 / 当刷怪塔的材料），只有它**此刻确实在刷怪**
        // 才拆。判据见 spawnSkipReason：它返回一整句"不拆 + 为什么"，**三种原因各自成句**
        // （没在出货 / 只刷和平生物 / 刷怪数据是空的），句尾还附上"到底读到了什么"。
        // 关掉 spawnerTorch.breakOnlyWhenActive = 回到旧行为（见到就拆）。
        if (breaking && activeOnly()) {
            String why = spawnSkipReason(level, found);
            if (why != null) {
                if (gameTime - aim.skipLogged > SKIP_LOG_GAP) {
                    aim.skipLogged = gameTime;
                    com.maidsmart.tool.PromaidLog.log("刷怪笼", com.maidsmart.tool.PromaidLog.nameOf(maid)
                            + " 刷怪笼 " + pos(found) + " " + why);
                }
                // 用较短的间隔重看：玩家一走近、或怪又刷出来，她很快就能重判并动手
                aim.coolPos = found;
                aim.coolUntil = gameTime + INACTIVE_COOLDOWN;
                return;
            }
        }
        aim.spawner = found;
        aim.since = gameTime;
        aim.breaking = breaking;
        aim.breakProgress = 0.0f;
        aim.lastCrackStage = -1;
        setSpawnerOwner(maid, breaking); // v1.3.0(beta)：这段时间她独占走位
        com.maidsmart.tool.PromaidLog.log("刷怪笼", com.maidsmart.tool.PromaidLog.nameOf(maid)
                + " 发现刷怪笼 " + pos(found) + (breaking
                        ? " → 过去拆掉防刷怪（模组刷怪笼无视光照，插火把压不住）"
                        : " → 过去插火把防刷怪（手上/背包里有灯）"));
    }

    private static void clearTarget(EntityMaid maid, Aim aim) {
        restoreOffhand(maid, aim); // 实测八百二十六：把借到副手的镐还回背包、副手原物品复位
        aim.spawner = null;
        aim.since = 0L;
        aim.breaking = false;
        aim.breakProgress = 0.0f;
        aim.lastCrackStage = -1;
        MaidWorkTags.setSpawnerTorch(maid, false); // v1.3.0(beta)：走位所有权一并交还
        MaidWorkTags.setSpawnerBreak(maid, false); // 实测八百二十二：拆除标记一并摘掉
    }

    /** 实测八百二十二：写/清"正在拆刷怪笼"的走位所有权标记（插火把与拆除各一枚）。 */
    private static void setSpawnerOwner(EntityMaid maid, boolean breaking) {
        MaidWorkTags.setSpawnerTorch(maid, !breaking);
        MaidWorkTags.setSpawnerBreak(maid, breaking);
    }

    /**
     * 实测八百二十二：她此刻是不是正在"拆"某只刷怪笼（供 {@code AutoCombatTargeting} 的
     * 「拆 > 战斗」闸查询）。判据：①本行为给她挂了拆除标记（持久化，切维度/存档不丢）；
     * ②内存目标确在拆除模式且还没超时（{@link #GIVE_UP_TICKS}）。天然**有界**——拆除最多
     * 持续 20 秒，绝不会把女仆永久锁在"免战"状态。
     */
    public static boolean isBreakingSpawner(EntityMaid maid) {
        try {
            if (maid == null || !maid.m_6084_()) {
                return false;
            }
            if (!MaidWorkTags.isSpawnerBreak(maid)) {
                return false;
            }
            Aim aim = AIM.get(maid);
            return aim != null && aim.breaking && aim.spawner != null
                    && maid.m_9236_().m_46467_() - aim.since <= GIVE_UP_TICKS;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            return false;
        }
    }

    /* ==================== 三件事：判断 / 走过去 / 放下 ==================== */

    /**
     * 这只刷怪笼还需要插火把吗？两条都要成立：①**刷怪笼还在**；②它身边 {@link #candidates} 那九个
     * 位置里**还没有够亮的东西**（这就是"去重"：火把在那儿她就当处理过了；被拆了/被水冲了，下一轮
     * 扫描自然会再插）。实测八百四十七：她自己脚底那格光块（{@code MaidHeldLight}）不算数——她站在
     * 笼边时会落进候选位，旧版当成"已经有够亮的东西守着它了" → 这只笼永远轮不到插火把。
     * <p>{@code maid == null} 的版本不排除任何光源（{@link #spawnSkipReason} 判"这地方是不是被光照
     * 压住了"用它：她举着的火把**是真实光照**，压住笼子这件事本身成立，"现在没在出货"就该成立）。
     */
    private static boolean stillNeedsTorch(ServerLevel level, BlockPos spawner) {
        return stillNeedsTorch(level, null, spawner);
    }

    private static boolean stillNeedsTorch(ServerLevel level, EntityMaid maid, BlockPos spawner) {
        try {
            if (!isSpawnerBlock(level.m_8055_(spawner))) {
                return false;
            }
            for (BlockPos c : candidates(spawner)) {
                if (level.m_8055_(c).m_60791_() >= LIGHT_ENOUGH
                        && (maid == null
                        || !com.maidsmart.tool.MaidHeldLight.isOwnLightBlock(maid, level, c))) {
                    return false; // 已经有够亮的东西守着它了（她自己脚底那块不算）
                }
            }
            return true;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            return false;
        }
    }

    /**
     * 实测八百三十b：她与刷怪笼之间**有没有方块挡着**（隔墙不拆的唯一判据）。
     *
     * <p>从眼睛到刷怪笼中心打一发射线，只挡有碰撞箱的方块（COLLIDER）、不看流体：第一个命中的
     * 方块**就是这只刷怪笼**才算没挡着；命中别的方块（一堵墙、地板）或打空都算"挡着"。
     * 判不出来（异常）一律按"挡着"处理——宁可少拆一只，也不隔墙拆。
     */
    private static boolean canReachSpawner(ServerLevel level, EntityMaid maid, BlockPos spawner) {
        try {
            net.minecraft.world.phys.Vec3 from = maid.m_20299_(1.0F); // getEyePosition
            net.minecraft.world.phys.Vec3 to = new net.minecraft.world.phys.Vec3(
                    spawner.m_123341_() + 0.5, spawner.m_123342_() + 0.5, spawner.m_123343_() + 0.5);
            net.minecraft.world.level.ClipContext ctx = new net.minecraft.world.level.ClipContext(
                    from, to, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, maid);
            net.minecraft.world.phys.BlockHitResult hit = level.m_45547_(ctx); // Level.clip
            return hit.m_6662_() == net.minecraft.world.phys.HitResult.Type.BLOCK
                    && spawner.equals(hit.m_82425_()); // BlockHitResult.getBlockPos
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            return false;
        }
    }

    /**
     * 【实测八百四十五】命中拆除黑名单时返回命中的那一条（供留痕），否则 {@code null}。
     * <p>名单里三种特殊词：{@code empty}=空笼子（读得出方块实体、里面没配刷怪数据）、
     * {@code benign}=只刷和平生物、{@code unreadable}=读不出刷怪数据；其余按实体注册名匹配
     * （写 {@code minecraft:zombie} 或只写 {@code zombie} 都行）。读 NBT 是**懒加载**：
     * 名单里没出现 empty/benign/unreadable 时一次都不读。名单空 = 不生效。
     */
    private static String breakBlacklistHit(ServerLevel level, BlockPos spawner) {
        try {
            java.util.List<? extends String> list =
                    com.maidsmart.config.MaidSmartConfig.COMBAT_SPAWNER_BREAK_BLACKLIST.get();
            if (list == null || list.isEmpty()) {
                return null;
            }
            SpawnThreat threat = null;   // 懒算：只有名单里出现特殊词才读刷怪表
            java.util.Set<String> ids = null;
            for (String raw : list) {
                if (raw == null) {
                    continue;
                }
                String e = raw.trim().toLowerCase(java.util.Locale.ROOT);
                if (e.isEmpty()) {
                    continue;
                }
                if (e.equals("empty") || e.equals("benign") || e.equals("unreadable")) {
                    if (threat == null) {
                        threat = spawnThreat(level, spawner);
                    }
                    if ((e.equals("empty") && threat == SpawnThreat.EMPTY)
                            || (e.equals("benign") && threat == SpawnThreat.BENIGN)
                            || (e.equals("unreadable") && threat == SpawnThreat.UNKNOWN)) {
                        return raw;
                    }
                    continue;
                }
                if (ids == null) {
                    ids = new java.util.LinkedHashSet<>();
                    net.minecraft.world.level.block.entity.BlockEntity be = level.m_7702_(spawner);
                    if (be != null) {
                        net.minecraft.nbt.CompoundTag tag = be.m_187480_();
                        if (tag != null) {
                            collectSpawnIds(tag, ids);
                        }
                    }
                }
                for (String id : ids) {
                    String low = id.toLowerCase(java.util.Locale.ROOT);
                    if (low.equals(e) || low.endsWith(":" + e)) {
                        return raw;
                    }
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            // 读配置/读 NBT 出任何岔子都按"没命中"处理：黑名单失败不该让整条链路停摆
        }
        return null;
    }

    /**
     * 实测八百三十 + 八百三十七 + 八百五十七：{@code null} = 该拆；否则返回**一整句"不拆 + 为什么"**（直接进日志）。
     *
     * <p>【实测八百五十七·判据顺序改了：先读刷怪表】旧顺序把"此刻在不在出货"的旁证排在最前
     * （12 格内有敌对生物 / 16 格内有玩家），读表排在最后。结果是**刷怪表里明明白白写着敌对生物**
     * 的笼子也常常不拆：她刚把刷出来的怪清掉、或玩家在 16 格外（典型：她在矿道深处拆笼、主人在上面）
     * → 判"没在出货" → 每 10 秒重判一次、每次同样拒绝（开发者反馈"拆除意愿特别低"）。
     * 现在：**表里是敌对 → 就该拆**，只保留"光照已经把它压住且旁边也没有怪"这一种例外
     * （那是玩家特意打亮保存的笼子）；**读不出表**（模组笼子）才回落到原来的旁证链。
     *
     * <p>各原因**各自成句**（旧版合成一句「空笼子 / 只刷牛羊猪 / 没在出货」，玩家与开发都分不出）：
     * ① 刷怪表 = 敌对 且压不住 → 拆；② 表里只有和平生物 / 表是空的 → 不拆；
     * ③ 表读不出来（模组笼子）→ 旁证：旁边就有敌对生物 → 拆；否则看"玩家在不在 16 格内 + 光照压没压住"；
     * ④ 表里有 id 但一个都解析不出（模组笼子）→ 按在出货处理（宁可多拆不可漏拆）。句尾附"到底读到了什么"。
     */
    private static String spawnSkipReason(ServerLevel level, BlockPos spawner) {
        try {
            SpawnThreat t = spawnThreat(level, spawner);
            if (t == SpawnThreat.HOSTILE) {
                // 实测八百五十七：刷怪表里就是敌对生物 + 光照压不住（或旁边就在出货）→ 拆。
                // 不再要求"玩家在 16 格内"——她在矿道深处拆笼时主人多半在上面，旧口径等于永远不拆。
                if (stillNeedsTorch(level, spawner)
                        || hostileNear(level, spawner, THREAT_MOB_RADIUS)) {
                    return null;
                }
                return "它的刷怪表里是敌对生物，但它旁边那圈的光照已经够亮、把它压住了，"
                        + "附近 12 格内也没有敌对生物 → 不拆，留着（看起来是你特意打亮保存的笼子）"
                        + spawnEvidence(level, spawner);
            }
            if (t == SpawnThreat.BENIGN) {
                return "它的刷怪数据里只有和平生物 → 不拆，留着（你可能是要刷动物 / 收走它）"
                        + spawnEvidence(level, spawner);
            }
            if (t == SpawnThreat.EMPTY) {
                return "它的刷怪数据是空的（读得出方块实体，但里面一个实体都没配 → 永远刷不出东西）"
                        + " → 不拆，留着（你可能要收走它）" + spawnEvidence(level, spawner);
            }
            // UNKNOWN：有刷怪表但实体 id 一个都解析不出来（模组笼子）/ 读取异常 →
            // 才用"此刻在不在出货"的旁证兜底（旧口径）。
            if (hostileNear(level, spawner, THREAT_MOB_RADIUS)) {
                return null; // 直接证据：它旁边就有敌对生物 = 它正在出货 → 拆
            }
            boolean activeWindow = playerNear(level, spawner, VANILLA_ACTIVATION_RANGE)
                    && stillNeedsTorch(level, spawner);
            if (!activeWindow) {
                return "刷怪表读不出内容（模组笼子），且现在没在出货（附近 12 格内没有敌对生物；"
                        + "也没有玩家在 16 格内，或它旁边那圈的光照已经够亮、把它压住了） → 不拆，留着"
                        + "（你可能要收走它）" + spawnEvidence(level, spawner);
            }
            return null;
        } catch (Throwable t) {
            noteThreatFail(spawner, t);
            return null; // 判不出来时按"在刷"处理（保证不会因异常而漏拆）
        }
    }

    /** 实测八百三十七：笼子刷怪数据的三态（取代旧版"读不到 = 空笼子/和平生物"的二分）。 */
    private enum SpawnThreat {
        HOSTILE,  // 刷敌对生物（Monster 子类）→ 该拆
        BENIGN,   // 读得到实体 id 且全不是敌对（牛羊猪/村民…）→ 不拆
        EMPTY,    // 读得出方块实体、但里面一个实体 id 都没有（真空 / 空壳笼子）→ 不拆
        UNKNOWN   // 有刷怪表但实体 id 一个都解析不出来（或读取抛异常）→ 交回"在不在出货"证据
    }

    /**
     * 实测八百三十七（bug 修复）：这只笼子写在 NBT 里的刷怪表说明了什么。
     *
     * <p>【旧版的真 bug】旧 {@code spawnsHostile} 只读 {@code SpawnData} / {@code SpawnPotentials}
     * 两个固定键，且**把"读不到"与"只刷和平生物"都返回 false**：整合包把刷怪表放在自己的键下
     * （如 {@code custom_spawn_rules.spawn_potentials}）→ 一个都不读 → 刷苦力怕的笼子被判成和平生物、永不拆。
     * 现在**任何名字带 spawn 的键**都递归找 {@code entity.id}，读失败一律 UNKNOWN（不再当和平生物）。
     */
    private static SpawnThreat spawnThreat(ServerLevel level, BlockPos spawner) {
        try {
            net.minecraft.world.level.block.entity.BlockEntity be = level.m_7702_(spawner);
            if (be == null) {
                return SpawnThreat.UNKNOWN;
            }
            // 【实测八百三十b：别读 getUpdateTag】那是发给客户端的同步包、会把 SpawnPotentials
            // 摘掉（自然生成的洞穴笼子恰恰"只有 SpawnPotentials"），读它会把好笼子误判成空的。
            net.minecraft.nbt.CompoundTag tag = be.m_187480_(); // BlockEntity.saveWithoutMetadata
            if (tag == null) {
                return SpawnThreat.UNKNOWN;
            }
            java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
            collectSpawnIds(tag, ids);
            if (ids.isEmpty()) {
                // 读得出方块实体、却一个实体 id 都没有 = 真空 / 空壳笼子（没配 SpawnData、表是空的、
                // 只剩空壳键）→ 不拆：它就是块"没在刷东西"的方块，留着给你收走。
                // 【注意】"读不出来"是上面两条 return UNKNOWN，不在这里——那才是保守拆。
                return SpawnThreat.EMPTY;
            }
            int unresolved = 0;
            for (String id : ids) {
                Boolean hostile = classifyEntity(id);
                if (hostile == null) {
                    unresolved++;
                } else if (hostile.booleanValue()) {
                    return SpawnThreat.HOSTILE;
                }
            }
            return unresolved == 0 ? SpawnThreat.BENIGN : SpawnThreat.UNKNOWN;
        } catch (Throwable t) {
            noteThreatFail(spawner, t);
            return SpawnThreat.UNKNOWN;
        }
    }

    /** 收齐笼子里的候选实体 id：先读三个已知键，再兜底扫根下**任何名字带 spawn 的键**。 */
    private static void collectSpawnIds(net.minecraft.nbt.CompoundTag tag, java.util.Set<String> out) {
        addId(out, entityIdIn(tag.m_128469_("SpawnData")));
        addFromList(tag.m_128437_("SpawnPotentials", 10), out);
        net.minecraft.nbt.CompoundTag rules = tag.m_128469_("custom_spawn_rules");
        addFromList(rules.m_128437_("spawn_potentials", 10), out);
        addFromList(rules.m_128437_("spawners", 10), out);
        for (String k : tag.m_128431_()) { // getAllKeys
            if (k.toLowerCase(java.util.Locale.ROOT).contains("spawn")) {
                collectAnyIds(tag.m_128423_(k), out, 0); // get(String)
            }
        }
    }

    private static void addFromList(net.minecraft.nbt.ListTag list, java.util.Set<String> out) {
        if (list == null) {
            return;
        }
        for (net.minecraft.nbt.Tag e : list) {
            if (e instanceof net.minecraft.nbt.CompoundTag c) {
                addId(out, entityIdIn(c));
            }
        }
    }

    /** 递归找实体 id（最多 5 层）：复合键里的 {@code id} / {@code Name} 字符串都算候选。 */
    private static void collectAnyIds(net.minecraft.nbt.Tag t, java.util.Set<String> out, int depth) {
        if (t == null || depth > 5) {
            return;
        }
        if (t instanceof net.minecraft.nbt.CompoundTag c) {
            addId(out, c.m_128461_("id"));
            addId(out, c.m_128461_("Name"));
            for (String k : c.m_128431_()) {
                collectAnyIds(c.m_128423_(k), out, depth + 1);
            }
        } else if (t instanceof net.minecraft.nbt.ListTag l) {
            for (net.minecraft.nbt.Tag e : l) {
                collectAnyIds(e, out, depth + 1);
            }
        }
    }

    /** 只收"像注册名"的 id：含冒号、够长，且不是刷怪笼方块自己的 id（根上那个 id 会一路带进来）。 */
    private static void addId(java.util.Set<String> out, String id) {
        if (id == null || id.length() < 3 || !id.contains(":")) {
            return;
        }
        if (id.equals("minecraft:mob_spawner") || id.equals("minecraft:trial_spawner")) {
            return;
        }
        out.add(id);
    }

    /** NBT 里有没有"名字带 spawn"的键——区分"真空笼子"与"有刷怪数据但读不出 id"。 */
    private static boolean hasSpawnKey(net.minecraft.nbt.CompoundTag tag) {
        try {
            for (String k : tag.m_128431_()) {
                if (k.toLowerCase(java.util.Locale.ROOT).contains("spawn")) {
                    return true;
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
        }
        return false;
    }

    /** 把"这只笼子到底读到什么"附在留痕句尾（最多 6 条 id），用来一眼分辨"读不到"与"只读到和平生物"。 */
    private static String spawnEvidence(ServerLevel level, BlockPos spawner) {
        try {
            net.minecraft.world.level.block.entity.BlockEntity be = level.m_7702_(spawner);
            if (be == null) {
                return "（读不到方块实体）";
            }
            net.minecraft.nbt.CompoundTag tag = be.m_187480_();
            if (tag == null) {
                return "（方块实体 NBT 为空）";
            }
            java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
            collectSpawnIds(tag, ids);
            if (ids.isEmpty()) {
                return hasSpawnKey(tag) ? "（有刷怪键，但读不出 entity.id）" : "（NBT 里没有刷怪数据）";
            }
            StringBuilder sb = new StringBuilder("（读到 entity.id = ");
            int n = 0;
            for (String id : ids) {
                if (n++ > 0) {
                    sb.append(", ");
                }
                if (n > 6) {
                    sb.append("…");
                    break;
                }
                sb.append(id);
            }
            return sb.append("）").toString();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            return ""; // 纯诊断串：读不到就少写一段，不影响"拆不拆"的判定
        }
    }

    /**
     * 注册名 → 三态：TRUE=敌对 / FALSE=确定和平 / null=判不出来（**绝不当成和平生物**）。
     * <p>【不能读 getBaseClass()】javap 实证（两树都核过）：1.20.1-47.4.23 的 {@code m_142225_()} 与
     * 1.21.1 NeoForge 的 {@code getBaseClass()} 都只有 {@code ldc class Entity; areturn}，恒等于
     * {@code Entity.class} —— 拿它判 {@code Monster} 永远 false（留痕写着「只有和平生物」、句尾却
     * 读到 {@code entity.id = minecraft:creeper}）。改按 {@code EntityType.getCategory()} 判：
     * 原版把敌对生物一律登记在 {@code MobCategory.MONSTER}，动物 / 水生 / 蝙蝠 / 蝾螈各有和平类别。
     */
    private static Boolean classifyEntity(String id) {
        try {
            net.minecraft.world.entity.EntityType<?> type = net.minecraftforge.registries.ForgeRegistries
                    .ENTITY_TYPES.getValue(net.minecraft.resources.ResourceLocation.parse(id));
            if (type == null) {
                return null; // 没有这个注册名（模组没装 / id 写错）→ 未知
            }
            net.minecraft.world.entity.MobCategory cat = type.m_20674_(); // EntityType.getCategory
            if (cat == net.minecraft.world.entity.MobCategory.MONSTER) {
                return Boolean.TRUE;
            }
            if (cat == net.minecraft.world.entity.MobCategory.CREATURE
                    || cat == net.minecraft.world.entity.MobCategory.AMBIENT
                    || cat == net.minecraft.world.entity.MobCategory.AXOLOTLS
                    || cat == net.minecraft.world.entity.MobCategory.UNDERGROUND_WATER_CREATURE
                    || cat == net.minecraft.world.entity.MobCategory.WATER_CREATURE
                    || cat == net.minecraft.world.entity.MobCategory.WATER_AMBIENT) {
                return Boolean.FALSE; // 原版的和平类别，逐个列全（不靠"不是 MONSTER 就算和平"）
            }
            // 只剩 MISC：原版落在这里的只有村民 / 游商 / 铁傀儡 / 雪傀儡这类和平生物；
            // 模组实体也落进来时就说不准了 → 未知（交给"直接证据"与保守拆除）。
            return id.startsWith("minecraft:") ? Boolean.FALSE : null;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            return null; // 解析失败 = 未知，**不当成和平生物**
        }
    }

    /** 判定抛异常时留一行（30 秒上限）——旧版在这里静默 return false，等于"她不动、日志里什么都没有"。 */
    private static void noteThreatFail(BlockPos spawner, Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastThreatFailLog < 30000L) {
            return;
        }
        lastThreatFailLog = now;
        com.maidsmart.tool.PromaidLog.log("刷怪笼", "读 " + pos(spawner)
                + " 的刷怪数据时抛了异常 → 按「在出货」处理（宁可多拆不可漏拆）：" + t);
    }

    /**
     * 从刷怪数据 NBT 里取实体 id。原版这套 NBT 有**三层不同的壳**，这里逐层剥：
     * <ul>
     *   <li>{@code SpawnData} → {@code {entity:{id:"..."}}}；</li>
     *   <li>{@code SpawnPotentials} 的每个条目 → {@code {weight:1, data:{entity:{id:"..."}}}}（两层壳）；</li>
     *   <li>少数手写结构文件 → 直接 {@code {id:"..."}}。</li>
     * </ul>
     * 所以不能只看一层：循环里先在当前层找 {@code id} / {@code Name}，找不到再往下剥
     * {@code entity} 或 {@code data}（最多剥 4 层，防病态数据死循环）。找不到就返回 null
     * （= 空笼子 / 认不出来）。
     */
    private static String entityIdIn(net.minecraft.nbt.CompoundTag data) {
        try {
            if (data == null) {
                return null;
            }
            net.minecraft.nbt.CompoundTag holder = data;
            for (int depth = 0; depth < 4; depth++) {
                String id = holder.m_128461_("id");     // entity 的 id
                if (id == null || id.isEmpty()) {
                    id = holder.m_128461_("Name");      // 少数结构文件写法
                }
                if (id != null && !id.isEmpty()) {
                    return id;
                }
                if (holder.m_128425_("entity", 10)) {
                    holder = holder.m_128469_("entity");
                } else if (holder.m_128425_("data", 10)) {
                    holder = holder.m_128469_("data");
                } else {
                    return null;
                }
            }
            return null;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            return null;
        }
    }

    /** 刷怪笼周围半径 r 内有没有活着的敌对生物（{@code Monster}，与 SmartAttackTool 同判据）。 */
    private static boolean hostileNear(ServerLevel level, BlockPos spawner, double r) {
        try {
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                    spawner.m_123341_() + 0.5 - r, spawner.m_123342_() + 0.5 - r, spawner.m_123343_() + 0.5 - r,
                    spawner.m_123341_() + 0.5 + r, spawner.m_123342_() + 0.5 + r, spawner.m_123343_() + 0.5 + r);
            return !level.m_6443_(net.minecraft.world.entity.monster.Monster.class, box,
                    e -> e.m_6084_()).isEmpty();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            return false;
        }
    }

    /** 刷怪笼周围半径 r 内有没有**非旁观**的玩家（原版刷怪笼的激活条件）。 */
    private static boolean playerNear(ServerLevel level, BlockPos spawner, double r) {
        try {
            double r2 = r * r;
            for (net.minecraft.server.level.ServerPlayer p : level.m_6907_()) {
                if (!p.m_6084_() || p.m_5833_()) { // 死亡 / 旁观不算
                    continue;
                }
                if (p.m_20275_(spawner.m_123341_() + 0.5, spawner.m_123342_() + 0.5,
                        spawner.m_123343_() + 0.5) <= r2) {
                    return true;
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
        }
        return false;
    }

    /**
     * 推进"走过去并插上"。返回 false = 这一只处理完了（插上了 / 放不下 / 超时放弃），
     * 调用方据此清目标并记冷却；返回 true = 还在处理中（本 tick 就到此为止）。
     */
    private static boolean walkAndPlace(ServerLevel level, EntityMaid maid, Aim aim, long gameTime) {
        BlockPos spawner = aim.spawner;
        if (gameTime - aim.since > GIVE_UP_TICKS) {
            com.maidsmart.tool.PromaidLog.log("刷怪笼", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 刷怪笼 " + pos(spawner) + " 走了 " + (GIVE_UP_TICKS / 20)
                    + " 秒也没够到 → 先放下（过一会儿还会再试）");
            return false;
        }
        double d2 = maid.m_20275_(spawner.m_123341_() + 0.5,
                spawner.m_123342_() + 0.5, spawner.m_123343_() + 0.5);
        if (d2 > PLACE_RANGE * PLACE_RANGE) {
            // 还没到：每 tick 重写一次寻路目标（与其它行为同款；真正的移动由 TLM 导航执行）
            // 【v1.3.0(beta) 实测六百六十四：走位所有权】清 WALK_TARGET（那面闸会把
            //  MoveToTargetSink 一并取消）+ 直连导航；挖矿/伐木/农活驱动发起的寻路会被
            //  SpawnerTorchNavGuardMixin 掐掉，于是"走去插火把"这段路上谁都抢不走她的路。
            try {
                maid.m_6274_().m_21936_(net.minecraft.world.entity.ai.memory.MemoryModuleType.f_26370_);
            } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            }
            navTo(maid, spawner, WALK_SPEED);
            return true;
        }
        // 到了：按候选位顺序找一个"能站住"的位置把灯放下
        int slot = findLight(maid);
        ItemStack src = slot >= 0
                ? maid.getAvailableBackpackInv().getStackInSlot(slot)
                : handLight(maid);
        if (src.m_41619_()) {
            return false; // 灯用完了（最后一根在路上掉了/被别的行为借走了）
        }
        Block block = lightBlockOf(src);
        if (block == null) {
            return false;
        }
        // 【名字必须在扣料之前取】consumeOne 之后这一格就缩了，再读就是"空气"。
        // 被动插火把那一边在实测五百九十七吃过一次同样的亏，这里照那份写法先拷贝。
        // （实测八百四十七：只留这一份"给日志用的名字"——原来还给 BombPose 留了一份物品栈
        //   快照用来"副手举一下"，那记拟真表现已按开发者要求删除。）
        String showName = src.m_41786_().getString();
        BlockState place = block.m_49966_();
        for (BlockPos c : candidates(spawner)) {
            if (!level.m_8055_(c).m_60795_()) {
                continue; // 不是空气
            }
            if (!place.m_60710_(level, c)) {
                continue; // 站不住（原版放置规则：火把要支撑面、方块要能替换…）
            }
            if (!level.m_7731_(c, place, 3)) {
                continue; // 放不下（被占 / 已经是同一种）→ 换下一个候选位，一根都不扣
            }
            if (!consumeOne(maid, src, slot)) {
                // 【实测七百六十二·issue #19 三修】扣不到就把刚放下的那盏**拆回去**——
                // 绝不留"放了却没扣"的那一盏（坏包装层下就是净产出）。
                level.m_7471_(c, false);
                return false;
            }
            // 实测八百四十七：原来这里跟着一记 BombPose.showGated（副手举一下那盏灯）——
            // 开发者「不再要求原来的拟真行为」→ 删掉，只留上面这一次挥手 + 扣 1 盏。
            maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
            com.maidsmart.tool.PromaidLog.log("刷怪笼", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 在刷怪笼 " + pos(spawner) + " 的 " + pos(c) + " 放下了 1x "
                    + showName + "（发光 " + place.m_60791_() + " ≥ "
                    + LIGHT_ENOUGH + " → 8×3×8 生成区全在门限之上，它哑了）");
            return false;
        }
        com.maidsmart.tool.PromaidLog.log("刷怪笼", com.maidsmart.tool.PromaidLog.nameOf(maid)
                + " 刷怪笼 " + pos(spawner) + " 周围九个位置全放不了（头顶/侧面都被方块占了，"
                + "或者她脚下没有支撑面）→ 这一只先放过");
        return false;
    }

    /* ==================== 实测八百二十二：拆 ==================== */

    /**
     * 推进"走过去把它拆了"（默认行为；模组刷怪笼无视光照，插火把压不住）。返回 false = 这一只
     * 处理完了（拆掉了 / 放弃 / 超时 / 不可破坏），调用方据此清目标并记冷却；返回 true = 还在
     * 处理中（本 tick 到此为止）。
     *
     * <p>【被攻击也钉住】本方法**不查攻击目标**——她走到跟前就一镐一镐刨到底。这不是 bug：
     * 模组刷怪笼一刷怪她就会被写攻击目标，若这里让位就永远拆不完（
     *
     * 不受走位闸影响）；她自己的走位被 {@code SPAWNER_TORCH_TAG/SPAWNER_BREAK_TAG} 锁住，
     * 谁都不许把她从刷怪笼前拖走（见两个 mixin）。血量跌破自保线（250）时自保接管、本行为让位。
     *
     * <p>挖掘公式逐字对齐 {@code MaidMineBehavior}（不依赖 FakePlayer）：硬度取**正解**
     * {@code Block.getDestroySpeed}（=5.0）——**不是** Neo 树里那个把 {@code getShadeBrightness}
     * 当硬度的既有 bug；正确工具除数 30、否则 100；效率附魔加成 {@code lv²+1}；乘全局
     * {@code mine.speedFactor}；封顶 0.25/刻。镐在手约 4~15 刻拆完，徒手约 25 秒（故默认要求带镐）。
     */
    private static boolean walkAndBreak(ServerLevel level, EntityMaid maid, Aim aim, long gameTime) {
        BlockPos spawner = aim.spawner;
        if (!isSpawnerBlock(level.m_8055_(spawner))) {
            // 已经没了（她上一镐拆掉了 / 被别人拆了 / 被活塞推了）→ 处理完
            return false;
        }
        if (gameTime - aim.since > GIVE_UP_TICKS) {
            com.maidsmart.tool.PromaidLog.log("刷怪笼", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 刷怪笼 " + pos(spawner) + " 走了 " + (GIVE_UP_TICKS / 20)
                    + " 秒也没够到 → 先放下（过一会儿还会再试）");
            return false;
        }
        double d2 = maid.m_20275_(spawner.m_123341_() + 0.5,
                spawner.m_123342_() + 0.5, spawner.m_123343_() + 0.5);
        if (d2 > PLACE_RANGE * PLACE_RANGE) {
            // 还没到：清 WALK_TARGET + 直连导航（走位所有权与插火把同款）
            try {
                maid.m_6274_().m_21936_(net.minecraft.world.entity.ai.memory.MemoryModuleType.f_26370_);
            } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            }
            navTo(maid, spawner, WALK_SPEED);
            return true;
        }
        // 实测八百三十b【隔墙不拆·一句话判据】她与刷怪笼之间只要还挡着方块，就当**没看见**
        // 这一只（放下、进冷却）——不加计时、不绕路、不退让，就这一条。判据见 canReachSpawner。
        if (!canReachSpawner(level, maid, spawner)) {
            com.maidsmart.tool.PromaidLog.log("刷怪笼", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 刷怪笼 " + pos(spawner) + " 与她之间隔着方块（隔着墙 / 挡在方块后面）"
                    + " → 当没看见，不隔墙拆");
            return false;
        }
        // 到位了：手上得有一把能挖它的镐。
        // 实测八百二十六：**干活中**（非战斗工作）主手要留给任务本身（挖矿的镐 / 伐木的斧 / 农活的锄
        // 会被工作行为按自己的节奏抢回主手，旧版把镐放主手 = 刚换上就被抢走、进度永远推不动，
        // 于是"只有空闲时才拆得动"）→ 改用【副手】借一把镐，主手一字不动，拆除与干活并行。
        // 空闲 / 待命 / 战斗仍走主手（原有口径不变）。
        BlockState state = level.m_8055_(spawner);
        ItemStack tool;
        boolean offHand;
        if (offhandMode(maid)) {
            ItemStack off = maid.m_21206_();
            if (isPickaxeFor(off, state)) {
                tool = off;          // 副手已经是能挖的镐（上次借的还没还 / 玩家自己放的）
                offHand = true;
            } else {
                ItemStack main = maid.m_21205_();
                if (!aim.borrowedOffhand && isPickaxeFor(main, state)) {
                    tool = main;     // 主手本就是这只能挖的镐（如挖矿女仆）→ 直接用，不折腾
                    offHand = false;
                } else {
                    tool = borrowOffhand(maid, aim, state); // 从背包借一把到副手
                    offHand = !tool.m_41619_();
                    if (!offHand && pickaxeMode()) {
                        com.maidsmart.tool.PromaidLog.log("刷怪笼", com.maidsmart.tool.PromaidLog.nameOf(maid)
                                + " 走到刷怪笼 " + pos(spawner) + " 跟前，但身上没有能挖的镐 → 这一只先放过"
                                + "（拆除需要镐；关掉 spawnerTorch.breakRequirePickaxe 可让她徒手慢慢刨）");
                        return false;
                    }
                }
            }
        } else {
            // 不在干活也不在拆（空闲 / 跟随 / 无任务）：主手没有别的活抢，仍走主手。
            // 若之前借过副手镐（任务中途切走），先把副手还原。
            if (aim.borrowedOffhand) {
                restoreOffhand(maid, aim);
            }
            ItemStack main = maid.m_21205_();
            if (!isPickaxeFor(main, state)) {
                try {
                    com.maidsmart.task.MaidToolAutoEquip.ensureForTarget(maid, state); // 从背包装备一把镐
                } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
                }
                main = maid.m_21205_();
                if (!isPickaxeFor(main, state) && pickaxeMode()) {
                    com.maidsmart.tool.PromaidLog.log("刷怪笼", com.maidsmart.tool.PromaidLog.nameOf(maid)
                            + " 走到刷怪笼 " + pos(spawner) + " 跟前，但身上没有能挖的镐 → 这一只先放过"
                            + "（拆除需要镐；关掉 spawnerTorch.breakRequirePickaxe 可让她徒手慢慢刨）");
                    return false;
                }
            }
            tool = main;
            offHand = false;
        }
        final boolean swingOff = offHand;
        // 挖掘公式（与 MaidMineBehavior 同款）
        float hardness = state.m_60734_().m_7749_(state, level, spawner); // Block.getDestroySpeed
        if (hardness <= 0.0f || hardness == Float.MAX_VALUE) {
            com.maidsmart.tool.PromaidLog.log("刷怪笼", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 刷怪笼 " + pos(spawner) + " 硬度异常（不可破坏）→ 放过");
            return false;
        }
        boolean correctNow = isPickaxeFor(tool, state);
        float digSpeed = Math.max(1.0f, tool.m_41691_(state));
        try {
            net.minecraft.world.item.enchantment.Enchantment eff =
                    net.minecraftforge.registries.ForgeRegistries.ENCHANTMENTS
                            .getValue(ResourceLocation.parse("minecraft:efficiency"));
            if (eff != null && digSpeed > 1.0f) {
                int lv = net.minecraft.world.item.enchantment.EnchantmentHelper.m_44843_(eff, tool);
                if (lv > 0) {
                    digSpeed += lv * lv + 1;
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
        }
        float delta = digSpeed / hardness / (correctNow ? 30.0f : 100.0f)
                * (float) (double) com.maidsmart.config.MaidSmartConfig.MINE_SPEED_FACTOR.get();
        delta = Math.min(delta, 0.25f);
        // 每刻挥镐（挖掘动作）：借在副手就挥副手，主手一字不动（不打断她手上的活）
        maid.m_21011_(swingOff ? net.minecraft.world.InteractionHand.OFF_HAND
                : net.minecraft.world.InteractionHand.MAIN_HAND, true);
        aim.breakProgress += delta;
        int stage = Math.max(0, Math.min(9, (int) (aim.breakProgress * 10.0f)));
        if (stage != aim.lastCrackStage) {
            aim.lastCrackStage = stage;
            broadcastCrack(level, maid, spawner, stage);
        }
        if (aim.breakProgress < 1.0f) {
            return true; // 还在刨，下一 tick 继续
        }
        // —— 破了 ——
        broadcastCrack(level, maid, spawner, -1); // 清客户端裂缝
        level.m_46796_(2001, spawner, Block.m_49956_(state)); // 2001 = 方块破坏声 + 碎屑
        try {
            net.minecraft.world.level.block.entity.BlockEntity be = level.m_7702_(spawner);
            // 刷怪笼**没有掉落物**（空 loot table），这里只是把"破坏"走全：掉落表为空，
            // 但会自然掉经验球（spawnAfterBreak）——与玩家手拆完全一致。
            Block.m_49881_(state, level, spawner, be, maid, tool);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
        }
        level.m_7731_(spawner, net.minecraft.world.level.block.Blocks.f_50016_.m_49966_(), 3); // 置为空气
        if (!tool.m_41619_()) { // 用的那把镐耗 1 点耐久（镐碎了就把对应手槽清空）
            final ItemStack damaged = tool;
            final int slot = swingOff ? 1 : 0;
            damaged.m_41622_(1, maid, broken -> {
                maid.m_6674_(swingOff ? net.minecraft.world.InteractionHand.OFF_HAND
                        : net.minecraft.world.InteractionHand.MAIN_HAND);
                try {
                    ((net.minecraftforge.items.IItemHandlerModifiable) maid.getHandsInvWrapper())
                            .setStackInSlot(slot, ItemStack.f_41583_);
                } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
                }
            });
        }
        com.maidsmart.tool.PromaidLog.log("刷怪笼", com.maidsmart.tool.PromaidLog.nameOf(maid)
                + " 拆掉了刷怪笼 " + pos(spawner) + "（模组刷怪笼无视光照，插火把压不住 → 直接拆除）");
        return false;
    }

    /**
     * 实测八百二十七：她此刻是不是处于【副手锁定】——正在拆刷怪笼，且那把镐确实是本链路
     * 借到副手的（{@code aim.borrowedOffhand}）。供副手写入方让位查询：这段时间任何"换副手"
     * 的动作（战斗自动装备盾牌等）都不许动她的副手，直到刷怪笼被拆掉（{@link #isBreakingSpawner}
     * 转 false，最多 {@link #GIVE_UP_TICKS} = 20 秒）。
     *
     * <p>【为什么只锁"借来的"】若那把镐是玩家自己放的 / 主手本就有的（没有借副手），则副手
     * 根本没被动过，没理由拦住原系统（举盾/自动装备）。只有真正借了副手，才需要独占。
     */
    public static boolean isLockedOffhand(EntityMaid maid) {
        try {
            if (!isBreakingSpawner(maid)) {
                return false;
            }
            Aim aim = AIM.get(maid);
            return aim != null && aim.borrowedOffhand;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            return false;
        }
    }

    /** 广播挖掘裂缝包（与 MaidMineBehavior 同款；只在阶段变化时发，省带宽） */
    private static void broadcastCrack(ServerLevel level, EntityMaid maid, BlockPos pos, int stage) {
        try {
            net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket pkt =
                    new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                            maid.m_19879_(), pos, stage);
            for (net.minecraft.server.level.ServerPlayer p : level.m_6907_()) {
                if (p.m_20275_(pos.m_123341_() + 0.5, pos.m_123342_() + 0.5,
                        pos.m_123343_() + 0.5) < 1024.0) {
                    p.f_8906_.m_9829_(pkt);
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
        }
    }

    /**
     * 她身上/背包里有没有一把"能挖刷怪笼"的镐（判据同自动装备；用原版刷怪笼状态探）。
     * 实测八百二十七：扫描启动闸与"战斗中要不要继续挂着本链路"都查它——**有镐才拆，
     * 没镐就走正常链路**（
     */
    private static boolean hasDigToolSomewhere(EntityMaid maid) {
        try {
            return com.maidsmart.task.MaidToolAutoEquip.canHarvestWithHandOrBackpack(
                    maid, net.minecraft.world.level.block.Blocks.f_50085_.m_49966_());
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            return false;
        }
    }

    /**
     * 实测八百二十七：{@link #hasDigToolSomewhere} 的 40 tick 缓存版——{@code canStillUse} 每 tick
     * 都问一次，而精妙背包那条探测要 extract/reinsert；缓存一下免得每 tick 折腾背包。
     */
    private static boolean hasDigToolCached(EntityMaid maid, long gameTime) {
        try {
            Aim aim;
            synchronized (AIM) {
                aim = AIM.get(maid);
                if (aim == null) {
                    aim = new Aim();
                    AIM.put(maid, aim);
                }
            }
            // 实测八百二十七补【整数溢出：本次事故的根因，务必别改回去】digToolCacheAt 的初值是
            // Long.MIN_VALUE，而 gameTime - Long.MIN_VALUE 在补码里会溢出成一个大负数——永远
            // < 40，于是缓存**永不刷新**、digToolCache 恒为初值 false。后果不是"少算一次"，
            // 而是：战斗中（攻击任务）canStillUse 永远返回 false，而 Brain 对 RUNNING 行为
            // 先问 canStillUse，false 就直奔 doStop、连 tick() 都不调 → 整条链路彻底静默
            // （不扫描、不借副手镐、不写一行日志）。玩家实测正是："她处于攻击状态下永远不换成
            // 镐去挖"，且日志一片空白。同一坑本仓库已修过一次（MaidBuildBehavior v1.5.328
            // LAST_CHUNK_RESEND），口径照它：初值必须用 == 显式判，不能只靠减法。
            if (aim.digToolCacheAt == Long.MIN_VALUE || gameTime - aim.digToolCacheAt >= 40L) {
                aim.digToolCache = hasDigToolSomewhere(maid);
                aim.digToolCacheAt = gameTime;
            }
            return aim.digToolCache;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            return hasDigToolSomewhere(maid);
        }
    }

    /* ==================== 实测八百二十六：干活中【副手借镐】并行拆除 ==================== */

    /**
     * 这一 tick 是否该走【副手借镐】模式？判据 = 她正挂着【非战斗的干活任务】
     * （挖矿/伐木/农活/建造/烹饪…由 {@code MaidWorkTags.isNonCombatWork} 识别）
     * **或者正在拆刷怪笼**（实测八百二十七：战斗中也拆——副手借镐、主手继续打）。
     *
     * <p>【为什么必须换到副手】干活时那些任务的主手是它们干活要用的工具（挖矿的镐 / 伐木的斧 /
     * 农活的锄），工作行为会按自己的节奏把主手抢回去——旧版把拆除的镐放主手，等于"刚换上
     * 就被抢走"，进度永远推不动，玩家实测到的就是"只有空闲状态下才会去挖刷怪笼"。副手不属于
     * 任何工作任务的工具位（TLM 工作行为从不动副手），借一把镐放副手就能与干活并行、互不打扰。
     * 战斗同理：主手要留着挥武器/举弓，拆除的镐也必须让到副手，才能"边打边拆"。
     *
     * <p>空闲 / 待命 / 无任务不算干活：没有别的东西抢主手，仍走主手（原口径不变）。
     */
    private static boolean offhandMode(EntityMaid maid) {
        try {
            // 实测八百二十七：已经借了副手 → 钉住，不中途还（
            //
            if (isLockedOffhand(maid)) {
                return true;
            }
            if (com.maidsmart.task.MaidWorkTags.isNonCombatWork(maid)) {
                return true; // 干活：主手被工作工具占着 → 副手借镐（826 原口径）
            }
            // 实测八百二十七：战斗中拆笼、还没借到副手 → 也要借副手（主手留给武器）。
            // 空闲 / 跟随保持 826 口径（主手空闲，直接主手换镐，不折腾副手）。
            return com.maidsmart.task.MaidWorkTags.isAttackTask(maid) && isBreakingSpawner(maid);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            return false;
        }
    }

    /** 这把是不是"能挖这个刷怪笼"的镐（判据与自动装备同款；徒手不算——徒手走 correctNow=false 的慢速档） */
    private static boolean isPickaxeFor(ItemStack stack, BlockState state) {
        try {
            if (stack == null || stack.m_41619_()
                    || !(stack.m_41720_() instanceof net.minecraft.world.item.PickaxeItem)) {
                return false;
            }
            return stack.m_41720_() instanceof net.minecraft.world.item.DiggerItem dg && dg.m_8096_(state);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            return false;
        }
    }

    /**
     * 从背包借一把能挖这个刷怪笼的镐到【副手】。返回借到的那把镐（没借到 = 空栈，副手保持原样）。
     *
     * <p>【副手原物品怎么处理】首次借镐时把副手原物品**整件摘下**写进她自己的持久 NBT
     * （{@link #stashOffhand}；脱离槽位、不复制、不塞背包），槽位让给借来的镐；
     * {@link #restoreOffhand} 再原样放回。这样既不丢、也不会复制玩家那件。**放 NBT 而不是放
     * 堆内存里的 {@code Aim} 字段**，是因为跨维度传送/世界重载/死亡复活都会换实体实例，
     * 只有 NBT 跟着她走（见 {@link #SAVED_OFFHAND_TAG}）。与 {@code BombPose}/
     * {@code FlightFireworkPose} 的"借副手"同一形状。借用期间若副手被别的东西顶掉，
     * 顶掉的那件收进背包（不动 NBT 里那份），再借一把新的。
     */
    private static ItemStack borrowOffhand(EntityMaid maid, Aim aim, BlockState state) {
        try {
            net.minecraftforge.items.IItemHandlerModifiable hands =
                    (net.minecraftforge.items.IItemHandlerModifiable) maid.getHandsInvWrapper();
            net.minecraftforge.items.IItemHandlerModifiable inv = maid.getAvailableBackpackInv();
            if (aim.borrowedOffhand) {
                ItemStack off = hands.getStackInSlot(1);
                if (off.m_41619_() || isPickaxeFor(off, state)) {
                    return off; // 上一把还在（或被清空）→ 沿用，savedOffhand 不动
                }
                // 被别的东西顶掉了：把那件收回背包（**不是删掉**；savedOffhand 仍是玩家原物品）
                hands.setStackInSlot(1, ItemStack.f_41583_);
                ItemStack left = net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(inv, off, false);
                if (!left.m_41619_()) {
                    maid.m_5552_(left, 0.5f);
                }
            }
            com.maidsmart.tool.MaidExtraContainer.pull(maid, s -> isPickaxeFor(s, state), 1);
            int best = -1;
            long bestScore = Long.MIN_VALUE;
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (s.m_41619_() || !isPickaxeFor(s, state)) {
                    continue;
                }
                long score = s.m_41776_() - s.m_41773_(); // 剩余耐久（别借一把快碎的）
                if (score > bestScore) {
                    bestScore = score;
                    best = i;
                }
            }
            if (best < 0) {
                return ItemStack.f_41583_; // 背包里没有能挖的镐：副手保持现状（若刚取走顶掉物已收好）
            }
            ItemStack offOld = hands.getStackInSlot(1);
            if (!aim.borrowedOffhand) {
                if (savedOffhandOf(maid).m_41619_()) {
                    // 首次借：把副手原物品整件摘下、写进**她自己的 NBT**（换实体实例也不丢）
                    stashOffhand(maid, offOld);
                } else if (!offOld.m_41619_()) {
                    // NBT 里还压着上一次的（跨维度/重载后留下的残件）：先把她手上这件收回背包，
                    // 别让下面那句腾副手把它顶没了
                    ItemStack left = net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(inv, offOld, false);
                    if (!left.m_41619_()) {
                        maid.m_5552_(left, 0.5f);
                    }
                }
            }
            hands.setStackInSlot(1, ItemStack.f_41583_); // 腾副手（原物品已存进她的 NBT）
            ItemStack picked = inv.getStackInSlot(best);
            inv.setStackInSlot(best, ItemStack.f_41583_); // 背包那格腾空
            aim.borrowedOffhand = true;
            hands.setStackInSlot(1, picked); // 镐进副手
            return picked;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            return ItemStack.f_41583_;
        }
    }

    /**
     * 还镐：把借来的镐收回背包、副手原物品放回副手。拆除结束 / 行为停止 / 被切任务时务必调用
     * （见 {@link #clearTarget} 与 {@code m_6732_}），否则副手会被我们的镐一直占着。
     */
    private static void restoreOffhand(EntityMaid maid, Aim aim) {
        try {
            if (!aim.borrowedOffhand) {
                return;
            }
            aim.borrowedOffhand = false;
            net.minecraftforge.items.IItemHandlerModifiable hands =
                    (net.minecraftforge.items.IItemHandlerModifiable) maid.getHandsInvWrapper();
            net.minecraftforge.items.IItemHandlerModifiable inv = maid.getAvailableBackpackInv();
            ItemStack off = hands.getStackInSlot(1);
            hands.setStackInSlot(1, ItemStack.f_41583_); // 腾空副手（借来的镐脱离槽位）
            if (!off.m_41619_()) {
                ItemStack left = net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(inv, off, false);
                if (!left.m_41619_()) {
                    maid.m_5552_(left, 0.5f); // 背包满 → 原地丢还
                }
            }
            ItemStack saved = savedOffhandOf(maid);
            if (!saved.m_41619_()) {
                hands.setStackInSlot(1, saved); // 原物品（盾牌/食物/火把）原样回到副手
            }
            stashOffhand(maid, ItemStack.f_41583_); // 放好之后才清 NBT（半路抛异常就留着重试）
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
        }
    }

    /**
     * 兜底回收：她 NBT 里还压着"借镐时摘下的那件原副手物品"、而这会儿并没有在借 → 还给她。
     *
     * <p>【谁会走到这里】跨维度传送 / 世界重载 / 死亡复活都会**换实体实例**：新实例的
     * {@code AIM} 是空的（WeakHashMap 的键是旧实例），借镐那条链便永远不会再触发
     * {@link #restoreOffhand}；只有 NBT 跟着实体走，本方法就是把它接回来的那一手。每 tick 一问
     * （没压着东西时只是一次 NBT contains，零开销）。
     */
    public static void recoverSavedOffhand(EntityMaid maid) {
        if (maid == null || !maid.m_6084_()) {
            return;
        }
        ItemStack saved = savedOffhandOf(maid);
        if (saved.m_41619_()) {
            return;
        }
        Aim aim = AIM.get(maid);
        if (aim != null && aim.borrowedOffhand) {
            return; // 正借在手上 → 由 restoreOffhand 负责，别插手
        }
        try {
            net.minecraftforge.items.IItemHandlerModifiable hands =
                    (net.minecraftforge.items.IItemHandlerModifiable) maid.getHandsInvWrapper();
            net.minecraftforge.items.IItemHandler inv = maid.getAvailableBackpackInv();
            if (hands.getStackInSlot(1).m_41619_()) {
                hands.setStackInSlot(1, saved); // 副手空着 → 原样回副手
            } else {
                ItemStack left = net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(inv, saved, false);
                if (!left.m_41619_()) {
                    maid.m_5552_(left, 0.5f); // 背包也满 → 落地，绝不删
                }
            }
            stashOffhand(maid, ItemStack.f_41583_);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
        }
    }

    /** 女仆死亡 → 把 NBT 里压着的那件原副手物品掉在原地（人没了，东西不能跟着 NBT 一起消失） */
    public static void dropSavedOffhand(EntityMaid maid) {
        ItemStack saved = savedOffhandOf(maid);
        if (saved.m_41619_()) {
            return;
        }
        stashOffhand(maid, ItemStack.f_41583_); // 先清 NBT 再落地（复活若从 NBT 重建，不会多出一份）
        try {
            maid.m_5552_(saved, 0.5f);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
        }
    }

    /**
     * 刷怪笼周围**按优先级**的候选放置位（9 个，全在它紧挨着的格子里——光照衰减最小）：
     * <pre>
     *   ① 它自己顶上（火把立在刷怪笼上，最字面的那一处）
     *   ② 同一层的东南西北四邻（火把立在地板上）
     *   ③ 四邻的上一层（火把立在墙头上）
     * </pre>
     * 顺序即优先级；第一个"空气 + 能站住"的位置就是落点。九个都放不了才放弃。
     * （扫怪笼本身是"可燃尽"的？不——它不可替换，所以从不考虑替换它。）
     */
    private static List<BlockPos> candidates(BlockPos spawner) {
        List<BlockPos> list = new ArrayList<>(9);
        list.add(spawner.m_7494_());
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            list.add(spawner.m_7918_(d[0], 0, d[1]));
            list.add(spawner.m_7918_(d[0], 1, d[1]));
        }
        return list;
    }

    /* ==================== 扫描 ==================== */

    /** 她附近最近的刷怪笼（水平 ±半径、垂直 ±{@link #SCAN_DY}；没有则 null） */
    private static BlockPos scan(ServerLevel level, EntityMaid maid, int[] occludedOut) {
        return scan(level, maid, occludedOut, null, 0L);
    }

    /**
     * 实测八百五十七：扫描"最近的、看得见的、**且不在冷却里**的"刷怪笼。
     *
     * <p>【为什么加冷却过滤】旧版只挑最近的一只；一只"暂时不该拆"的笼子（空笼 / 和平笼 / 光照已压住 /
     * 在家圈里 / 冷却中）被判完后就占住了 {@code aim.coolPos}，而它**还是最近的** → 每次扫描
     * 都选到它、每次都被冷却挡回 → 半径内**别的笼子永远轮不到**。玩家看到的就是"她就在笼子边上，
     * 就是不去拆"。现在把冷却中的位置直接从候选里剔掉，选下一个最近的。
     */
    private static BlockPos scan(ServerLevel level, EntityMaid maid, int[] occludedOut,
                                 Aim aim, long gameTime) {
        try {
            int r = (int) Math.round(radiusCfg());
            BlockPos base = maid.m_20183_();
            BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = -SCAN_DY; dy <= SCAN_DY; dy++) {
                        BlockPos p = base.m_7918_(dx, dy, dz);
                        if (!isSpawnerBlock(level.m_8055_(p))) {
                            continue;
                        }
                        // 实测八百五十七：刚判过"这次不动它"的那只不再当选——否则它会一直
                        // 挡在"最近的"这个位置上，把半径内其它笼子全遮住（见方法注释）
                        if (aim != null && p.equals(aim.coolPos) && gameTime < aim.coolUntil) {
                            continue;
                        }
                        // 实测八百三十b【隔墙就视为没看见】：她和它之间挡着方块 → 这一只不当目标
                        // （挑最近的"看得见"的那只；一只都看不见 = 附近没扫到）
                        // 实测八百三十七：**数一下**被挡掉几只，让调用方把"隔着墙"与"压根没有"
                        // 分开报——以前静默跳过，两种情况在日志里长得一模一样。
                        if (!canReachSpawner(level, maid, p)) {
                            if (occludedOut != null) {
                                occludedOut[0]++;
                            }
                            continue;
                        }
                        double d = maid.m_20275_(p.m_123341_() + 0.5,
                                p.m_123342_() + 0.5, p.m_123343_() + 0.5);
                        if (d < bestD) {
                            bestD = d;
                            best = p;
                        }
                    }
                }
            }
            return best;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            return null;
        }
    }

    /**
     * 这是不是一个刷怪笼。
     * <p>
     * 【为什么两条判据】①原版主世界/要塞那种是 {@code SpawnerBlock}——**按类型认**，TLM
     * 或原版改了注册名也照样认（被动插火把认火把同理）；②1.21.1 的"试炼刷怪笼"
     * （trial_spawner）在 1.20.1 里**根本没有对应的类**，写死类名会让 forge 树编译不过，
     * 所以补一条"注册名以 spawner 结尾"的兜底——它同时把各种模组刷怪笼一起收进来。
     */
    private static boolean isSpawnerBlock(BlockState state) {
        try {
            if (state.m_60734_() instanceof net.minecraft.world.level.block.SpawnerBlock) {
                return true;
            }
            ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.m_60734_());
            if (key == null) {
                return false;
            }
            String id = key.toString();
            return id.endsWith("spawner");
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            return false;
        }
    }

    /**
     * 这个刷怪笼在不在她的【在家模式 / 工作区】圈里（河童的罗盘标记的那一片）——**在里面就整条
     * 链路不执行**（
     *
     * <p>【判不出来时按"在里面"处理】异常一律当成 True：宁可少插一根火把，也不要冒着拆掉
     * 玩家刷怪塔的风险。这个不对称是刻意的。
     */
    private static boolean inHomeWorkArea(EntityMaid maid, BlockPos pos) {
        try {
            if (!maid.m_21536_()) {
                return false; // 没开"在家模式/工作范围"
            }
            BlockPos c = com.maidsmart.follow.WorkAreaClamp.circleCenter(maid);
            if (c == null) {
                return false; // 从没定过圈心（BlockPos.ZERO）＝没有圈
            }
            double r = Math.max(1.0, maid.m_21535_());
            double dx = pos.m_123341_() + 0.5 - (c.m_123341_() + 0.5);
            double dz = pos.m_123343_() + 0.5 - (c.m_123343_() + 0.5);
            return dx * dx + dz * dz <= r * r;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            return true;
        }
    }

    /* ==================== 灯：找 / 认 / 用 ==================== */

    /** 她身上（主手/副手/背包/精妙背包）有没有一件能用的（见 isLightItem） */
    private static boolean hasLightItem(EntityMaid maid) {
        try {
            if (isLightItem(maid.m_21205_()) || isLightItem(maid.m_21206_())) {
                return true;
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
        }
        return findLight(maid) >= 0;
    }

    /** 手上拿着的那件灯（没有则空栈） */
    private static ItemStack handLight(EntityMaid maid) {
        try {
            if (isLightItem(maid.m_21205_())) {
                return maid.m_21205_();
            }
            if (isLightItem(maid.m_21206_())) {
                return maid.m_21206_();
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
        }
        return ItemStack.f_41583_;
    }

    /**
     * 背包里找一件灯，返回槽位（-1 = 没有）。两趟：**先普通火把/灵魂火把**（
     * 也是"插火把"最不心疼的），再退而求其次用别的灯（萤石、海晶灯…）。
     * 与被动插火把同款：先请 TLM 从精妙背包/旅行者背包里搬一件进来。
     */
    private static int findLight(EntityMaid maid) {
        try {
            com.maidsmart.tool.MaidExtraContainer.pull(maid,
                    s -> !s.m_41619_() && isLightItem(s), 1);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
        }
        try {
            net.minecraftforge.items.IItemHandler inv = maid.getAvailableBackpackInv();
            int first = -1;
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (s.m_41619_() || !isLightItem(s)) {
                    continue;
                }
                if (isPlainTorch(s)) {
                    return i;
                }
                if (first < 0) {
                    first = i;
                }
            }
            return first;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            return -1;
        }
    }

    private static boolean isPlainTorch(ItemStack stack) {
        try {
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
            if (key == null) {
                return false;
            }
            String id = key.toString();
            return "minecraft:torch".equals(id) || "minecraft:soul_torch".equals(id);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            return false;
        }
    }

    /**
     * 这件东西能不能当"和火把一样功效"的灯。三道门：
     * <ol>
     *   <li>得是方块物品（能放下）；</li>
     *   <li>注册名在 {@link #LIGHT_IDS} 里，**或者**以 {@code torch}/{@code lantern} 结尾
     *       （模组火把/灯笼的常见命名）；</li>
     *   <li><b>放下去真的够亮</b>：方块默认状态自身发光 ≥ {@link #LIGHT_ENOUGH}。
     *       这一条把红石火把（7）以及各种"看着像灯其实照不亮"的东西挡在外面——
     *       ，"功效"就是这一条数字。</li>
     * </ol>
     */
    private static boolean isLightItem(ItemStack stack) {
        try {
            if (stack == null || stack.m_41619_()
                    || !(stack.m_41720_() instanceof net.minecraft.world.item.BlockItem bi)) {
                return false;
            }
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
            if (key == null) {
                return false;
            }
            String id = key.toString();
            boolean listed = false;
            for (String t : LIGHT_IDS) {
                if (t.equals(id)) {
                    listed = true;
                    break;
                }
            }
            if (!listed && !id.endsWith("torch") && !id.endsWith("lantern")) {
                return false;
            }
            return bi.m_40614_().m_49966_().m_60791_() >= LIGHT_ENOUGH;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            return false;
        }
    }

    /** 这件灯对应的放置方块（非方块物品返回 null） */
    private static Block lightBlockOf(ItemStack stack) {
        try {
            if (stack == null || stack.m_41619_()
                    || !(stack.m_41720_() instanceof net.minecraft.world.item.BlockItem bi)) {
                return null;
            }
            return bi.m_40614_();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            return null;
        }
    }

    /**
     * 消耗一件。slots >= 0 走背包，否则走主/副手。**返回 true = 确认真的扣掉了**。
     *
     * <p>【实测七百六十二·issue #19 三修】原来只调 {@code extractItem}、完全不看结果——
     * 坏包装层"返回副本却不真扣"时灯照插、材料一件没少（与搭路/战斗放置同一形状）。
     * 现在改走 {@code MaidBuildBlockFilter.extractOneHonest}（扣完核对槽位总数真的减 1），
     * 调用方按返回值决定要不要把刚放下的那盏拆回来。
     */
    private static boolean consumeOne(EntityMaid maid, ItemStack src, int slot) {
        try {
            if (slot >= 0) {
                return com.maidsmart.tool.MaidBuildBlockFilter.extractOneHonest(
                        maid.getAvailableBackpackInv(), slot);
            }
            return com.maidsmart.tool.MaidBuildBlockFilter.extractOneHonest(
                    (net.minecraftforge.items.IItemHandlerModifiable) maid.getHandsInvWrapper(),
                    isLightItem(maid.m_21205_()) ? 0 : 1);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            return false;
        }
    }

    /* ==================== 配置 / 小工具 ==================== */

    private static boolean enabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_SPAWNER_TORCH_ENABLE.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            return true;
        }
    }

    private static double radiusCfg() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_SPAWNER_TORCH_RADIUS.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            return 12.0;
        }
    }

    /** 实测八百二十二：发现刷怪笼直接拆（默认开）。关掉 = 回到旧的"插火把"链路（火把降为可选） */
    private static boolean breakMode() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_SPAWNER_BREAK.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            return true;
        }
    }

    /** 实测八百二十二：拆除是否要求带镐（默认开）。只影响**速度**：镐约 4~15 刻，徒手约 25 秒 */
    private static boolean pickaxeMode() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_SPAWNER_BREAK_PICKAXE.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            return true;
        }
    }

    /** 实测八百三十：只在刷怪笼**真的在刷怪**时才拆（默认开）。关掉 = 回到旧的"见到就拆"。
     *  判据见 {@link #spawnSkipReason}。 */
    private static boolean activeOnly() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_SPAWNER_BREAK_ACTIVE_ONLY.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSpawnerTorchBehavior", ignored);
            return true;
        }
    }

    /** "(x, y, z)"——日志与气泡里到处要用，收一处 */
    private static String pos(BlockPos p) {
        return "(" + p.m_123341_() + ", " + p.m_123342_() + ", " + p.m_123343_() + ")";
    }
}
