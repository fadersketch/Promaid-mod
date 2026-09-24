package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
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
 * v1.3.3「防刷怪：发现刷怪笼就插火把」——玩家建议原文：
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
 * 那边还多一条"会发红石信号"）。这就是玩家说的"有和火把一样功效的东西一样可以接受判定"。
 *
 * ── 与 {@code MaidTorchPlacerBehavior}（被动插火把）的关系 ──
 * 那一个是"跟在主人身边、主人脚下黑了就补一根"，判据是"手上拿着火把"这一小张清单，
 * 目的是**人身边的照明**；本类判的是"能不能真的把刷怪笼按死"，判据是**亮度数字**，
 * 目的是**断刷怪**。两件事不同，所以清单各有一份、互不包含；但优先级顺序一致
 * （普通火把 > 灵魂火把 > 别的灯），并且把那一份的三件（普通火把/灵魂火把/tacz 火把）
 * 全含在内——玩家说"手上有火把"时她要用的就是那三件。
 *
 * ── Home 工作区（河童的罗盘）里一律不碰 ──
 * 见 {@link #inHomeWorkArea}：圈里可能是玩家**故意留着**的刷怪塔/刷怪笼陷阱。这条判据
 * 读的是 TLM 的活动范围圆心与半径（{@code getRestrictCenter/getRestrictRadius}），
 * 与扫帚模式那把夹取（{@link MaidBroomKit#clampToHome}）读的是同一对字段——口径一处。
 *
 * ── 顺手说明"为什么它排在那么高的优先级"──
 * 玩家原话里的"优先"，落在这里 = core 行为优先级 186（见 {@code ProMaidExtension}），
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

    /**
     * 认作"灯"的注册名清单（优先级从上到下）。**真正生效的判据是发光 ≥ {@link #LIGHT_ENOUGH}**，
     * 这张表只是"允许动用的范围"：与被动插火把那份一样，宁可写死注册名也不写死物品类型——
     * 模组物品拿不到类，注册名是唯一稳的抓手；末尾另有"名字以 torch/lantern 结尾"的兜底。
     */
    private static final String[] LIGHT_IDS = {
            "minecraft:torch",              // 普通火把 14——玩家原话里的那件，最优先
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

    /** 女仆 → 她的目标刷怪笼与计时（WeakHashMap：女仆卸载即自然回收） */
    private static final Map<EntityMaid, Aim> AIM =
            Collections.synchronizedMap(new WeakHashMap<>());

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
    }

    /* ==================== 生命周期 ==================== */

    /**
     * 能不能开跑：总开关开着 + 她还活着 + **不是乘客**（骑扫帚/坐椅子时她不走路，也就走不到
     * 刷怪笼跟前）+ 没坐着 + 脑子还能动（TLM 的 canBrainMoving，工作态/骑乘态为 false）
     * + 手上没有攻击目标（先打完再插火把）+ 不在自保逃跑里。
     *
     * <p>【为什么不在这里判"有没有火把"】那要翻一遍背包，而 canUse 是**每 tick** 调的；
     * 这里只做零成本的闸门，"有没有灯"留给 {@link #m_6725_} 在扫描那一步（4 秒一次）判。
     */
    @Override
    protected boolean m_6114_(ServerLevel level, EntityMaid maid) {
        if (!enabled()) {
            return false;
        }
        if (maid == null || !maid.m_6084_() || maid.m_20159_()) {
            return false;
        }
        try {
            if (maid.isMaidInSittingPose() || !maid.canBrainMoving()) {
                return false;
            }
            if (maid.m_5448_() != null) {
                return false; // 有攻击目标：先打完
            }
            if (maid.getPersistentData().m_128471_(
                    com.maidsmart.combat.SelfPreservationBehavior.PRESERVE_TAG)) {
                return false; // 自保逃跑中：别去插火把
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** 与被动插火把同款：父类默认 canStillUse = false（试一次就停），必须重写为 true。 */
    @Override
    protected boolean m_6737_(ServerLevel level, EntityMaid maid, long gameTime) {
        return true;
    }

    @Override
    protected void m_6725_(ServerLevel level, EntityMaid maid, long gameTime) {
        if (maid == null || !maid.m_6084_()) {
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
        // ① 手上已经有目标 → 继续推进它（走过去 / 放下 / 判定不用放了）
        if (aim.spawner != null) {
            if (!stillNeedsTorch(level, aim.spawner)) {
                clearTarget(aim);
            } else if (!walkAndPlace(level, maid, aim, gameTime)) {
                // 放弃 / 插完了：记冷却，清目标
                aim.coolPos = aim.spawner;
                aim.coolUntil = gameTime + RETRY_COOLDOWN;
                clearTarget(aim);
            }
            return;
        }
        // ② 没有目标 → 按间隔扫一次
        if (gameTime - aim.lastScan < SCAN_GAP) {
            return;
        }
        aim.lastScan = gameTime;
        if (!hasLightItem(maid)) {
            return; // 手上一件灯都没有：整条链路不启动（玩家原话是"如果手上有火把"）
        }
        BlockPos found = scan(level, maid);
        if (found == null) {
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
                        + "）→ 不插火把（玩家可能故意留着它刷怪）");
            }
            aim.coolPos = found;
            aim.coolUntil = gameTime + RETRY_COOLDOWN;
            return;
        }
        aim.spawner = found;
        aim.since = gameTime;
        com.maidsmart.tool.PromaidLog.log("刷怪笼", com.maidsmart.tool.PromaidLog.nameOf(maid)
                + " 发现刷怪笼 " + pos(found) + " → 过去插火把防刷怪（手上/背包里有灯）");
    }

    private static void clearTarget(Aim aim) {
        aim.spawner = null;
        aim.since = 0L;
    }

    /* ==================== 三件事：判断 / 走过去 / 放下 ==================== */

    /**
     * 这只刷怪笼还需要插火把吗？
     * <p>
     * 两条都要成立：①**刷怪笼还在**（被挖了就作废）；②它身边 {@link #candidates} 那九个位置里
     * **还没有够亮的东西**。第二条同时就是"去重"——不需要记"我插过哪些刷怪笼"：火把在那儿，
     * 她就认为是处理过的；火把被人拆了（或者被水冲了），她下一轮扫描自然会再插一次。
     */
    private static boolean stillNeedsTorch(ServerLevel level, BlockPos spawner) {
        try {
            if (!isSpawnerBlock(level.m_8055_(spawner))) {
                return false;
            }
            for (BlockPos c : candidates(spawner)) {
                if (level.m_8055_(c).m_60791_() >= LIGHT_ENOUGH) {
                    return false; // 已经有够亮的东西守着它了
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
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
            try {
                net.minecraft.world.entity.ai.behavior.BehaviorUtils.m_22617_(
                        maid, spawner, WALK_SPEED, 2);
            } catch (Throwable ignored) {
            }
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
        // 【快照必须在扣料之前取】consumeOne 之后这一格就缩了，再读就是空的（名字会变成"空气"）。
        // 被动插火把那一边在实测五百九十七吃过一次同样的亏，这里照那份写法先拷贝。
        ItemStack show = src.m_41777_();
        String showName = src.m_41786_().getString();
        BlockState place = block.m_49966_();
        for (BlockPos c : candidates(spawner)) {
            if (!level.m_8055_(c).m_60795_()) {
                continue; // 不是空气
            }
            if (!place.m_60710_(level, c)) {
                continue; // 站不住（原版放置规则：火把要支撑面、方块要能替换…）
            }
            level.m_7731_(c, place, 3);
            consumeOne(maid, src, slot);
            maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
            if (slot >= 0) {
                com.maidsmart.combat.BombPose.showGated(maid, show);
            }
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

    /**
     * 刷怪笼周围**按优先级**的候选放置位（9 个，全在它紧挨着的格子里——光照衰减最小）：
     * <pre>
     *   ① 它自己顶上（火把立在刷怪笼上，玩家原话"在刷怪笼上插火把"最字面的那一处）
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
    private static BlockPos scan(ServerLevel level, EntityMaid maid) {
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
        } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 这个刷怪笼在不在她的【在家模式 / 工作区】圈里（河童的罗盘标记的那一片）——**在里面就整条
     * 链路不执行**（玩家原话）。读的是 TLM 的那对字段（圆心 + 半径），与扫帚模式的夹取同源。
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
        } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
        }
        return ItemStack.f_41583_;
    }

    /**
     * 背包里找一件灯，返回槽位（-1 = 没有）。两趟：**先普通火把/灵魂火把**（玩家原话里那两件，
     * 也是"插火把"最不心疼的），再退而求其次用别的灯（萤石、海晶灯…）。
     * 与被动插火把同款：先请 TLM 从精妙背包/旅行者背包里搬一件进来。
     */
    private static int findLight(EntityMaid maid) {
        try {
            com.maidsmart.tool.MaidExtraContainer.pull(maid,
                    s -> !s.m_41619_() && isLightItem(s), 1);
        } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
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
     *       玩家说"有和火把一样功效的东西一样可以接受判定"，"功效"就是这一条数字。</li>
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
        } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 消耗一件。slots >= 0 走背包（extractItem，与工程其它消耗点同款：直接缩 getStackInSlot
     * 返回的栈在 handler 返回副本时扣不掉），否则走主/副手。
     */
    private static void consumeOne(EntityMaid maid, ItemStack src, int slot) {
        try {
            if (slot >= 0) {
                maid.getAvailableBackpackInv().extractItem(slot, 1, false);
                return;
            }
            ((net.minecraftforge.items.IItemHandlerModifiable) maid.getHandsInvWrapper())
                    .extractItem(isLightItem(maid.m_21205_()) ? 0 : 1, 1, false);
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 配置 / 小工具 ==================== */

    private static boolean enabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_SPAWNER_TORCH_ENABLE.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static double radiusCfg() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_SPAWNER_TORCH_RADIUS.get();
        } catch (Throwable ignored) {
            return 12.0;
        }
    }

    /** "(x, y, z)"——日志与气泡里到处要用，收一处 */
    private static String pos(BlockPos p) {
        return "(" + p.m_123341_() + ", " + p.m_123342_() + ", " + p.m_123343_() + ")";
    }
}
