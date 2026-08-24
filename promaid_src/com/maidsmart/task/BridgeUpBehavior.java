package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 搭路行为（v1.1.0，core 优先级 245——低于自保 250、高于落地水 240）。
 *
 * 主人在女仆上方一定距离内（默认 ≥2 格且总距离 <7 格 = 传送判定距离）时，
 * 女仆朝主人脚下走过去并逐格搭高靠近（借鉴 Zombie Invade 100 Days 的僵尸
 * MobBuildUpGoal：朝目标方向逐块垫高——女仆版用真实背包方块 + 物理上升，
 * 不像僵尸那样 setPos 瞬移）。默认关闭（bridge.enabled）。
 *
 * 触发条件（全部满足）：
 * - 开关开启；主人存在、活着、同维度
 * - 女仆非 home 模式（在家模式 = 守家不出门，不搭路追主人）
 * - 任务空闲（v1.1.0 实测十五：挖矿/伐木已锁定目标、烹饪/酿造站桩中、建造
 *   坐下中都不追——手上的活没干完不撂挑子；详见 isTaskOccupied）
 * - 主人高于女仆 ≥ bridge.minDy 格；欧氏距离 < bridge.maxDist 格
 * - 周围 bridge.threatDist 格内无敌对生物；女仆非自保状态
 * - 背包有可放置方块（BlockItem、非下落方块）
 *
 * 执行：置 bridging 标记（禁 TLM 瞬移回主人——MaidTeleportPreserveMixin）→
 * 水平导航到主人正下方 → 每步冷却在脚下垫方块（真实消耗背包方块）→
 * 距主人 ≤2.5 格停止（跟随接管）。搭的方块登记自清理（默认 10 秒变掉落物）。
 * 中止：威胁出现 / 方块耗尽 / 主人离开范围或换维度 / 主人不再高于女仆。
 */
public class BridgeUpBehavior extends Behavior<EntityMaid> {
    /** bridging 标记（persistentData——MaidTeleportPreserveMixin 拦传送用） */
    public static final String BRIDGING_TAG = "maid_smart_bridging";

    /** v1.1.0 实测四十二：换 PlacedBlockTracker——绑定搭建女仆（到期强制进她背包，
     *  不再 8 格附近查找）+ 魂符收回暂停计时。 */
    static final PlacedBlockTracker PLACED_TRACKER = new PlacedBlockTracker(
            () -> MaidSmartConfig.BRIDGE_PLACED_LIFETIME.get() * 20L);

    private static void track(ServerLevel level, BlockPos pos, Block block, EntityMaid maid) {
        PLACED_TRACKER.track(level, pos, block, maid);
    }

    /** 到期搭方块销毁（ProMaidExtension 每 tick 调）
     *  v1.1.0 实测四十二：改走 PlacedBlockTracker（绑定搭建者/魂符暂停） */
    public static void expirePlaced(net.minecraft.server.MinecraftServer server, long gameTime) {
        PLACED_TRACKER.expirePlaced(server, gameTime,
                pos -> anyMaidStanding(server, pos));
    }

    /** 任意维度的存活女仆站在该位置（跨维度判定；实测四十二） */
    private static boolean anyMaidStanding(net.minecraft.server.MinecraftServer server, BlockPos pos) {
        for (ServerLevel lvl : server.m_129785_()) {
            if (PlacedBlockTracker.anyMaidStanding(lvl, pos, m -> true)) {
                return true;
            }
        }
        return false;
    }

    /** 服务器停止清场（残留方块立即回收）
     *  v1.1.0 实测四十二：改走 PlacedBlockTracker.clearAll */
    public static void clearAll(net.minecraft.server.MinecraftServer server) {
        PLACED_TRACKER.clearAll(server);
    }

    /**
     * 该搭方块上是否正有女仆站着（脚下格或所在格）。
     * v1.1.0 审查：不再要求 bridging 标记——行为中止（威胁出现/卡死放弃）的瞬间标记
     * 就清了，旧判定会把她脚下的塔立刻回收，人从半空掉进威胁堆里；现在只要还站着
     * 就延后回收，走开才清。
     * v1.1.0 实测十七：改 public（supportsBridgerPublic）——自保的战斗方块回收
     * （60 秒到期）复用同一个"女仆站上面延后清理"保护。
     */
    public static boolean supportsBridgerPublic(ServerLevel level, BlockPos pos) {
        return supportsBridger(level, pos);
    }

    private static boolean supportsBridger(ServerLevel level, BlockPos pos) {
        return PlacedBlockTracker.anyMaidStanding(level, pos, m -> true);
    }

    /** 销毁一个追踪方块：v1.1.0 实测四十二后由 PlacedBlockTracker 内部处理，
     *  本方法保留给 isOwnPlaced 相关旧引用（实际已无调用者）。 */
    @SuppressWarnings("unused")
    private static void destroyMarked(ServerLevel level, BlockPos pos, String blockId) {
        var state = level.m_8055_(pos);
        if (state.m_60795_()) {
            return;
        }
        ResourceLocation cur = ForgeRegistries.BLOCKS.getKey(state.m_60734_());
        if (!blockId.isEmpty() && cur != null && !blockId.equals(cur.toString())) {
            return; // 玩家已替换，尊重改动
        }
        level.m_46796_(2001, pos, Block.m_49956_(state));
        if (MaidSmartConfig.BRIDGE_RECLAIM_TO_MAID.get()) {
            // 掉落物直接进附近女仆背包（最近者优先；满背包/找不到女仆才落地）
            java.util.List<ItemStack> drops = Block.m_49869_(state, level, pos, null);
            EntityMaid nearest = findNearestMaid(level, pos);
            boolean handed = false;
            if (nearest != null && !drops.isEmpty()) {
                try {
                    net.minecraftforge.items.wrapper.CombinedInvWrapper inv = nearest.getAvailableInv(true);
                    for (ItemStack stack : drops) {
                        if (stack.m_41619_()) {
                            continue;
                        }
                        ItemStack remain = net.minecraftforge.items.ItemHandlerHelper
                                .insertItemStacked(inv, stack, false);
                        if (!remain.m_41619_()) {
                            Block.m_49840_(level, pos, remain); // 背包满：落地（原版 popResource）
                        }
                    }
                    handed = true;
                } catch (Exception ignored) {
                }
            }
            if (!handed && !drops.isEmpty()) {
                for (ItemStack stack : drops) {
                    Block.m_49840_(level, pos, stack);
                }
            }
        } else {
            Block.m_49892_(state, level, pos, level.m_7702_(pos));
        }
        level.m_7731_(pos, Blocks.f_50016_.m_49966_(), 3);
    }

    /** 距该位置最近的女仆（回收目标；8 格内没有 → null）。
     *  v1.1.0 实测十：改 public（findNearestMaidPublic）——挖矿/伐木的搭方块
     *  回收（BRIDGE_RECLAIM_TO_MAID 升级为全局开关后）共用这一个查找。 */
    public static EntityMaid findNearestMaidPublic(ServerLevel level, BlockPos pos) {
        EntityMaid best = null;
        double bestSq = 64.0; // 8 格
        for (EntityMaid m : level.m_45976_(EntityMaid.class, new AABB(pos).m_82400_(8.0))) {
            if (!m.m_6084_()) {
                continue;
            }
            double dSq = m.m_20275_(pos.m_123341_() + 0.5, pos.m_123342_() + 0.5, pos.m_123343_() + 0.5);
            if (dSq < bestSq) {
                bestSq = dSq;
                best = m;
            }
        }
        return best;
    }

    @Deprecated
    private static EntityMaid findNearestMaid(ServerLevel level, BlockPos pos) {
        return findNearestMaidPublic(level, pos);
    }

    /**
     * v1.1.0 实测四：女仆是否处于"空中"状态——脚下悬空（含下落中）或站在
     * 自己 10 秒内垫的搭路方块上（PLACED 表里有记录）。
     * 空中 = 导航不可用 → 搭路距离上限放宽到 airMaxDist。
     */
    private static boolean isAirborne(ServerLevel level, EntityMaid maid) {
        BlockPos feet = maid.m_20183_();
        if (!maid.m_20096_()) {
            return true; // 悬空/下落中
        }
        BlockPos under = feet.m_7918_(0, -1, 0);
        if (PLACED_TRACKER.isPlaced(level, under)) {
            return true; // 站在自己垫的方块上（塔/桥——地面导航够不着主人）
        }
        return false;
    }

    /* ==================== 行为本体 ==================== */

    /** 垫块节奏冷却（tick 计数） */
    private int stepCooldown = 0;
    /** 搭块防掉落窗口（刚垫完 12 tick 内钳制在方块中心） */
    private int guardTicks = 0;
    /** 上次成功垫出方块的 gameTime（卡死检测：太久没垫出 = 头顶被挡/没料，放弃） */
    private long lastPlacedGameTime = 0;
    /** 材料耗尽播报限频 */
    private static final Map<Integer, Long> NO_BLOCK_SINCE = new HashMap<>();
    /** v1.1.0 实测十六（审查 P2-8）：canUse 节流——旧版每 tick 每
     *  女仆做 Monster AABB 扫描 + 全背包 BlockItem 过滤（含 VoxelShape 构造）。
     *  默认关闭缓解，但开启时多女仆场景明显。10 tick 节流足够响应。 */
    private static final Map<Integer, Integer> canUseThrottle = new HashMap<>();

    public BridgeUpBehavior() {
        super(Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Override
    protected boolean m_6114_(ServerLevel level, EntityMaid maid) {
        // v1.1.0 实测十六（审查 P2-8）：廉价检查先行 + 10 tick 节流——
        // 开关/home/自保/任务占用/距离/高度这些廉价判定不受节流（每 tick 都判），
        // 只有威胁扫描（Monster AABB）和背包过滤（VoxelShape）这两个重的受节流。
        if (!MaidSmartConfig.BRIDGE_ENABLED.get()) {
            return false;
        }
        LivingEntity owner = maid.m_269323_();
        if (owner == null || !owner.m_6084_() || owner.m_9236_() != level) {
            return false;
        }
        if (maid.getPersistentData().m_128471_(com.maidsmart.combat.SelfPreservationBehavior.PRESERVE_TAG)) {
            return false; // 自保优先
        }
        if (maid.isHomeModeEnable()) {
            return false;
        }
        if (isTaskOccupied(maid)) {
            return false;
        }
        int dy = owner.m_20183_().m_123342_() - maid.m_20183_().m_123342_();
        if (dy < MaidSmartConfig.BRIDGE_MIN_DY.get()) {
            return false;
        }
        boolean airborne = isAirborne(level, maid);
        int distLimit = airborne
                ? Math.max(MaidSmartConfig.BRIDGE_MAX_DIST.get(), MaidSmartConfig.BRIDGE_AIR_MAX_DIST.get())
                : MaidSmartConfig.BRIDGE_MAX_DIST.get();
        if (maid.m_20275_(owner.m_20185_(), owner.m_20186_(), owner.m_20189_())
                >= sq(distLimit)) {
            return false;
        }
        // v1.1.0 实测十六：重检查节流——威胁扫描 + 背包过滤每 10 tick 一次
        int eid = maid.m_19879_();
        Integer cd = canUseThrottle.get(eid);
        if (cd != null && cd > 0) {
            canUseThrottle.put(eid, cd - 1);
            return false;
        }
        canUseThrottle.put(eid, 10);
        if (hasThreatNearby(level, maid)) {
            return false;
        }
        return hasBuildBlock(maid);
    }

    @Override
    protected void m_6735_(ServerLevel level, EntityMaid maid, long gameTime) {
        maid.getPersistentData().m_128379_(BRIDGING_TAG, true);
        this.stepCooldown = 0;
        this.guardTicks = 0;
        this.lastPlacedGameTime = level.m_46467_();
        // v1.1.0 实测二十九：启动/中止日志（latest.log 搜 "bridge-up"）——
        // 间歇性失效排查用；中止原因在 doStop 记
        com.mojang.logging.LogUtils.getLogger().info(
                "bridge-up start: maid={} owner-dy={}",
                maid.m_5446_() != null ? maid.m_5446_().getString() : maid.m_20148_(),
                maid.m_269323_() != null
                        ? maid.m_269323_().m_20183_().m_123342_() - maid.m_20183_().m_123342_() : -999);
    }

    /** v1.1.0 实测二十九：中止原因（诊断日志用——doStop 时 canContinue 已
     *  复算过，这里重算一次拿结论；廉价判定开销可忽略） */
    private String stopReason(ServerLevel level, EntityMaid maid, long gameTime) {
        if (!MaidSmartConfig.BRIDGE_ENABLED.get()) {
            return "disabled";
        }
        LivingEntity owner = maid.m_269323_();
        if (owner == null || !owner.m_6084_() || owner.m_9236_() != level) {
            return "owner-gone";
        }
        double dSq = maid.m_20275_(owner.m_20185_(), owner.m_20186_(), owner.m_20189_());
        if (dSq <= 6.25) {
            return "reached";
        }
        int distLimit = Math.max(MaidSmartConfig.BRIDGE_MAX_DIST.get(),
                MaidSmartConfig.BRIDGE_AIR_MAX_DIST.get());
        if (dSq >= sq(distLimit + 2)) {
            return "owner-too-far";
        }
        if (hasThreatNearby(level, maid)) {
            return "threat";
        }
        if (maid.getPersistentData().m_128471_(com.maidsmart.combat.SelfPreservationBehavior.PRESERVE_TAG)) {
            return "self-preserve";
        }
        if (isTaskOccupied(maid)) {
            return "task-occupied";
        }
        int dyNow = owner.m_20183_().m_123342_() - maid.m_20183_().m_123342_();
        if (dyNow >= 1 && !hasBuildBlock(maid)) {
            return "no-block";
        }
        if (dyNow >= 1 && gameTime - this.lastPlacedGameTime > 400L) {
            return "head-blocked";
        }
        return "unknown";
    }

    @Override
    protected void m_6725_(ServerLevel level, EntityMaid maid, long gameTime) {
        // 每 tick 防窒息 + 防掉落钳制（照挖矿 pillarGuard/antiSuffocate）
        this.antiSuffocate(maid);
        if (this.guardTicks > 0) {
            this.guardTicks--;
            this.pillarGuard(level, maid);
        }
        LivingEntity owner = maid.m_269323_();
        if (owner == null || !owner.m_6084_()) {
            return; // doStop 兜底清标记
        }
        double distSq = maid.m_20275_(owner.m_20185_(), owner.m_20186_(), owner.m_20189_());
        int dy = owner.m_20183_().m_123342_() - maid.m_20183_().m_123342_();
        // 完成：贴到主人身边（跟随接管）
        if (distSq <= 6.25) {
            return; // canContinue 会结束行为
        }
        double hx = owner.m_20185_() - maid.m_20185_();
        double hz = owner.m_20189_() - maid.m_20189_();
        double hDist = Math.sqrt(hx * hx + hz * hz);
        // v1.1.0 实测三（用户："僵尸在空中仍能左右搭方块继续追，女仆只会傻站着"）：
        // 空中水平搭桥——参照 endofdays BlockBuildBridGeGoal 的做法：不依赖导航，
        // 只要朝主人方向前方一格脚下是空的，就直接在【前方脚下】垫方块铺桥，
        // 走过去再铺下一块（导航在半空永远返回失败 → 旧版只剩垂直叠柱/傻站）。
        // 与 tryDiagStep 的区别：那格只垫"下方一格"做台阶上楼；这里女仆已在
        // 主人高度附近（dy 已不足 minDy），铺的是平桥——空中横向逼近的主力。
        if (hDist > 1.2 && dy < MaidSmartConfig.BRIDGE_MIN_DY.get()) {
            if (this.tryAirBridgeStep(level, maid, hx, hz, hDist)) {
                return; // 铺了一块并走上去——本 tick 结束
            }
        }
        // v1.1.0 终审二（用户："不喜欢朝着主人的方向向前搭"）：水平还没对齐时不再
        // 死等导航走过去——导航绕路/被挡时她只会原地叠柱子够不着主人。脚下垫高
        // 的同时朝主人方向【前方一格脚下】也垫一块（斜上台阶，照伐木 slopeStep），
        // 垫完走上去——水平和垂直一起逼近，塔变成斜坡。
        // 前方格净空不足时退回纯垂直（等站位变化），不会把自己憋死。
        boolean diag = hDist > 1.2 && dy >= 1
                && this.tryDiagStep(level, maid, hx, hz, hDist);
        if (!diag && hDist > 1.2) {
            maid.m_21573_().m_26519_(owner.m_20185_(), maid.m_20186_(), owner.m_20189_(),
                    (float) (double) MaidSmartConfig.MINE_MOVE_SPEED.get());
        } else if (!diag) {
            maid.m_21573_().m_26573_(); // 站桩搭高（垂直列干净成型）
        }
        // 垂直接近：脚下垫方块（节奏冷却；diag 已垫过前方台阶时共用冷却）
        if (dy >= 1 && this.stepCooldown-- <= 0) {
            this.placeStep(level, maid);
            this.stepCooldown = MaidSmartConfig.BRIDGE_STEP_COOLDOWN.get();
        }
    }

    /**
     * v1.1.0 实测三：空中水平铺桥（朝主人方向前方一格脚下垫块并走上去）。
     * 适用：主人高度与女仆接近（dy < minDy，垂直搭高不触发/已到顶）但水平还远——
     * 前方脚下悬空时导航永远走不过去，直接铺桥走过去（参照 endofdays 僵尸
     * BlockBuildBridGeGoal：朝向一格的脚下是空就垫，不问导航）。
     * 返回 true = 铺了一块（本 tick 不再做别的动作）。
     */
    private boolean tryAirBridgeStep(ServerLevel level, EntityMaid maid, double hx, double hz, double hDist) {
        if (this.stepCooldown > 0) {
            return false; // 与垂直垫块共用节奏冷却
        }
        int y = maid.m_20183_().m_123342_();
        int tx = (int) Math.floor(maid.m_20185_() + hx / hDist);
        int tz = (int) Math.floor(maid.m_20189_() + hz / hDist);
        BlockPos ahead = new BlockPos(tx, y, tz);
        BlockPos fill = ahead.m_7918_(0, -1, 0);
        // 前方脚下不是空气（有方块/被占）→ 有路，交给导航
        if (!level.m_8055_(fill).m_60795_()) {
            return false;
        }
        // 前方两格净空（身体+头）防把自己憋进去
        if (!level.m_8055_(ahead).m_60795_()
                || !level.m_8055_(ahead.m_7918_(0, 1, 0)).m_60795_()) {
            return false;
        }
        Item item = takeBuildBlock(maid);
        if (item == null) {
            return false;
        }
        Block block = ForgeRegistries.BLOCKS.getValue(ForgeRegistries.ITEMS.getKey(item));
        if (block == null) {
            return false;
        }
        level.m_7731_(fill, block.m_49966_(), 3);
        track(level, fill, block, maid);
        // v1.1.0 实测三十七（用户："搭方块的时候也播放一下动作"）：摆臂动画 + 放置音效
        maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
        com.maidsmart.task.PlacedBlockTracker.placeSound(level, fill, block);
        this.guardTicks = 12;
        this.stepCooldown = MaidSmartConfig.BRIDGE_STEP_COOLDOWN.get();
        this.lastPlacedGameTime = level.m_46467_();
        // 走上刚铺的桥面（一格，直接移动目标；导航在半空没用）
        maid.m_21573_().m_26519_(tx + 0.5, y, tz + 0.5,
                (float) (double) MaidSmartConfig.MINE_MOVE_SPEED.get());
        return true;
    }

    /**
     * v1.1.0 终审二：朝主人方向前方一格的脚下垫台阶（斜上逼近）。
     * 冷却与垂直垫块共用（stepCooldown）；垫成功返回 true（本 tick 不再导航——
     * 导航目标会把女仆往回拉，刚垫的台阶踩不上去）。
     * v1.1.0 实测六十六（用户："搭方块的方向应该要更向着主人一些"）：旧版只在
     * 前方脚下【悬空】时才垫（fill 为空气）——地形实心时直接放弃退回原地直上，
     * 塔不朝主人长。改为两种都垫：悬空垫 fill（跨坑/铺桥台阶）；实心垫 ahead
     * 本格（在地形上再垫一级台阶踩上来）——垂直垫高从此始终朝主人方向斜着长。
     */
    private boolean tryDiagStep(ServerLevel level, EntityMaid maid, double hx, double hz, double hDist) {
        if (this.stepCooldown > 0) {
            return false; // 垂直垫块的节奏冷却还在——不抢拍
        }
        int y = maid.m_20183_().m_123342_();
        int tx = (int) Math.floor(maid.m_20185_() + hx / hDist);
        int tz = (int) Math.floor(maid.m_20189_() + hz / hDist);
        BlockPos ahead = new BlockPos(tx, y, tz);
        BlockPos fill = ahead.m_7918_(0, -1, 0);
        // 前方两格净空（台阶+头部）防把自己憋进去
        if (!level.m_8055_(ahead).m_60795_()
                || !level.m_8055_(ahead.m_7918_(0, 1, 0)).m_60795_()) {
            return false;
        }
        BlockPos place;
        if (level.m_8055_(fill).m_60795_()) {
            place = fill; // 悬空：垫脚下格成台阶（原逻辑）
        } else if (level.m_8055_(fill).m_60796_(level, fill)) {
            place = ahead; // 实心地形：在本格再垫一级台阶（踩上来高度+1）
        } else {
            return false; // 水/花草等非实心——垫了不可靠，交给导航绕
        }
        Item item = takeBuildBlock(maid);
        if (item == null) {
            return false;
        }
        Block block = ForgeRegistries.BLOCKS.getValue(ForgeRegistries.ITEMS.getKey(item));
        if (block == null) {
            return false;
        }
        level.m_7731_(place, block.m_49966_(), 3);
        track(level, place, block, maid);
        // v1.1.0 实测三十七：搭方块摆臂动画 + 放置音效
        maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
        com.maidsmart.task.PlacedBlockTracker.placeSound(level, place, block);
        this.guardTicks = 12;
        this.stepCooldown = MaidSmartConfig.BRIDGE_STEP_COOLDOWN.get();
        // 走上刚垫的台阶（不走导航长路——就一格，直接朝目标格设移动目标；
        // 实心地形垫的本格台阶比脚下一格高，移动目标也抬一格让她跳上来）
        double walkY = place.equals(ahead) ? y + 1 : y;
        maid.m_21573_().m_26519_(tx + 0.5, walkY, tz + 0.5,
                (float) (double) MaidSmartConfig.MINE_MOVE_SPEED.get());
        return true;
    }

    @Override
    protected boolean m_6737_(ServerLevel level, EntityMaid maid, long gameTime) {
        if (!MaidSmartConfig.BRIDGE_ENABLED.get()) {
            return false;
        }
        LivingEntity owner = maid.m_269323_();
        if (owner == null || !owner.m_6084_() || owner.m_9236_() != level) {
            return false;
        }
        if (maid.m_20275_(owner.m_20185_(), owner.m_20186_(), owner.m_20189_()) <= 6.25) {
            return false; // 已贴到主人（≤2.5 格）——完成，跟随接管
        }
        // v1.1.0 实测四：距离上限与 canUse 同口径——空中用 airMaxDist（+2 缓冲），
        // 否则远距空中铺桥刚启动就被 canContinue 掐掉
        int distLimit = Math.max(MaidSmartConfig.BRIDGE_MAX_DIST.get(),
                MaidSmartConfig.BRIDGE_AIR_MAX_DIST.get());
        if (maid.m_20275_(owner.m_20185_(), owner.m_20186_(), owner.m_20189_())
                >= sq(distLimit + 2)) {
            return false; // 主人走远了（超出阈值+2 缓冲）——放弃
        }
        if (hasThreatNearby(level, maid)) {
            return false; // 威胁出现——中止（战术/自保接管）
        }
        if (maid.getPersistentData().m_128471_(com.maidsmart.combat.SelfPreservationBehavior.PRESERVE_TAG)) {
            return false; // 自保触发——让位
        }
        // v1.1.0 实测十五：搭路途中任务重新占用了她（排班切班/玩家改派/矿刷新
        // 被重新锁定）→ 中止搭路让位（脚下方块 10 秒自回收，走开即清）
        if (isTaskOccupied(maid)) {
            return false;
        }
        // v1.1.0 终审：方块耗尽 → 立即中止（搭不了就是搭不了，说一声就撤，不等 20 秒；
        // 选材始终是"背包数量最多的可放置方块"，takeBuildBlock 不变）
        int dyNow = owner.m_20183_().m_123342_() - maid.m_20183_().m_123342_();
        if (dyNow >= 1 && !hasBuildBlock(maid)) {
            this.notifyNoBlock(maid);
            return false;
        }
        // 头顶被挡 → 20 秒垫不出任何方块再放弃（可能是站位问题，给她一点腾挪时间）
        if (dyNow >= 1 && gameTime - this.lastPlacedGameTime > 400L) {
            maid.getChatBubbleManager().addTextChatBubble(
                    "搭不上去了（头顶被挡住），我先下来……");
            return false;
        }
        return true;
    }

    @Override
    protected void m_6732_(ServerLevel level, EntityMaid maid, long gameTime) {
        com.mojang.logging.LogUtils.getLogger().info(
                "bridge-up stop: maid={} reason={}",
                maid.m_5446_() != null ? maid.m_5446_().getString() : maid.m_20148_(),
                this.stopReason(level, maid, gameTime));
        maid.getPersistentData().m_128379_(BRIDGING_TAG, false);
        NO_BLOCK_SINCE.remove(maid.m_19879_());
        canUseThrottle.remove(maid.m_19879_());
        super.m_6732_(level, maid, gameTime);
    }

    /* ==================== 搭块（照挖矿 pillarUpStep 精简） ==================== */

    /** 脚下垫一块把自己顶高：脚下悬空垫所在格；平地垫身体格。头顶 2 格必须空。 */
    private void placeStep(ServerLevel level, EntityMaid maid) {
        BlockPos pos = maid.m_20183_();
        BlockPos place = level.m_8055_(pos).m_60795_() ? pos : pos.m_7918_(0, 1, 0);
        if (!level.m_8055_(place).m_60795_()
                || !level.m_8055_(place.m_7918_(0, 1, 0)).m_60795_()
                || !level.m_8055_(place.m_7918_(0, 2, 0)).m_60795_()) {
            return; // 放置格/头顶空间不足——等站位变化再试
        }
        // 实际头顶（碰撞箱顶）检查：连续垫高时实体位移滞后，防把自己埋了
        double headY = maid.m_20191_().m_82374_(net.minecraft.core.Direction.Axis.Y);
        BlockPos headPos = new BlockPos((int) maid.m_20185_(), (int) (headY + 0.05), (int) maid.m_20189_());
        if (!level.m_8055_(headPos).m_60795_() || !level.m_8055_(headPos.m_7918_(0, 1, 0)).m_60795_()) {
            return;
        }
        Item item = takeBuildBlock(maid);
        if (item == null) {
            this.notifyNoBlock(maid);
            return;
        }
        Block block = ForgeRegistries.BLOCKS.getValue(ForgeRegistries.ITEMS.getKey(item));
        if (block == null) {
            return;
        }
        level.m_7731_(place, block.m_49966_(), 3);
        track(level, place, block, maid);
        // v1.1.0 实测三十七：搭方块摆臂动画 + 放置音效
        maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
        com.maidsmart.task.PlacedBlockTracker.placeSound(level, place, block);
        this.guardTicks = 12;
        this.lastPlacedGameTime = level.m_46467_();
    }

    /**
     * 背包里是否有可搭方块（只看不拿——canUse 探测专用，无副作用）。
     * v1.1.0 审查：旧版 canUse 直接调 takeBuildBlock，每轮启动尝试都会凭空
     * 消耗一枚方块（brain 每 tick 轮询 canUse，行为反复启停时背包悄悄漏方块）。
     * v1.1.0 实测七：统一走 MaidBuildBlockFilter——火把等无碰撞方块不再入选。
     */
    private static boolean hasBuildBlock(EntityMaid maid) {
        IItemHandler inv = maid.getMaidInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            if (com.maidsmart.tool.MaidBuildBlockFilter.isUsableBuildStack(
                    inv.getStackInSlot(i), null, null)) {
                return true;
            }
        }
        return false;
    }

    /** 背包数量最多的可搭方块（v1.1.0 实测七：统一走 MaidBuildBlockFilter 过滤） */
    private static Item takeBuildBlock(EntityMaid maid) {
        return com.maidsmart.tool.MaidBuildBlockFilter.takeBuildBlock(maid.getMaidInv(), null, null);
    }

    /** 材料耗尽播报（限频 30 秒） */
    private void notifyNoBlock(EntityMaid maid) {
        long now = maid.m_9236_().m_46467_();
        Long last = NO_BLOCK_SINCE.get(maid.m_19879_());
        if (last != null && now - last < 600L) {
            return;
        }
        NO_BLOCK_SINCE.put(maid.m_19879_(), now);
        maid.getChatBubbleManager().addTextChatBubble(
                "主人就在上面……可我背包里没有能搭的方块了（圆石/泥土等），够不着呀……");
    }

    /* ==================== 防窒息/防掉落（照挖矿精简版） ==================== */

    private void antiSuffocate(EntityMaid maid) {
        AABB box = maid.m_20191_();
        double cy = box.f_82289_ + (box.f_82292_ - box.f_82289_) * 0.5;
        BlockPos mid = new BlockPos((int) Math.floor(maid.m_20185_()),
                (int) Math.floor(cy), (int) Math.floor(maid.m_20189_()));
        var st = maid.m_9236_().m_8055_(mid);
        if (st.m_60795_() || !st.m_60796_(maid.m_9236_(), mid)) {
            return;
        }
        double top = mid.m_123342_() + 1.0;
        if (top > box.f_82289_ + 0.01) {
            maid.m_6034_(maid.m_20185_(), top + 0.02, maid.m_20189_());
        }
    }

    private void pillarGuard(ServerLevel level, EntityMaid maid) {
        BlockPos feet = maid.m_20183_();
        BlockPos under = feet.m_7918_(0, -1, 0);
        boolean onOwn = isOwnPlaced(level, under) || isOwnPlaced(level, feet);
        if (!onOwn) {
            return;
        }
        double halfW = maid.m_20205_() / 2.0;
        double limit = Math.max(0.05, 0.5 - halfW);
        double cx = under.m_123341_() + 0.5;
        double cz = under.m_123343_() + 0.5;
        double x = maid.m_20185_();
        double z = maid.m_20189_();
        double nx = Math.max(cx - limit, Math.min(cx + limit, x));
        double nz = Math.max(cz - limit, Math.min(cz + limit, z));
        if (nx != x || nz != z) {
            maid.m_6034_(nx, maid.m_20186_(), nz);
        }
    }

    private static boolean isOwnPlaced(ServerLevel level, BlockPos pos) {
        return PLACED_TRACKER.isPlaced(level, pos);
    }

    private static boolean hasThreatNearby(ServerLevel level, EntityMaid maid) {
        double r = MaidSmartConfig.BRIDGE_THREAT_DIST.get();
        for (Monster e : level.m_45976_(Monster.class, maid.m_20191_().m_82400_(r))) {
            if (e.m_6084_()) {
                return true;
            }
        }
        return false;
    }

    /**
     * v1.1.0 实测十五：女仆的任务是否正被【实质工作】占用（搭路追主人的门禁）。
     *
     * 用户口径："必须保证自己没有任务进程在占用——比如正准备挖矿时主人
     * 在别处打高，不应该追上去；可以处于任务状态，但该状态必须是空闲的。"
     *
     * 占用判定（promaid 自有四工作任务的"有活"信号，全部是行为维护的实时状态）：
     * - 挖矿：MINING 集合（找到矿才登记、框内无矿即摘除——空闲时已退出标记）
     * - 伐木：WOODING 集合（同上口径，找到树才登记）
     * - 建造：BINDING 建造计划在身（有蓝图待建 = 占用；v1.5.177 暂停时行为
     *   canUse 为 false、坐下标记也由行为维护——暂停中的女仆视为空闲可追）
     * - 站桩工作（烹饪/酿造）：WORK_STILL 标记（行为激活期才有，空闲即清）
     *
     * 不拦的情况：idle/跟随/战斗/喂食等 TLM 原生任务（搭路本来就为跟随服务，
     * 这些任务上搭路是正常画面）；挖矿/伐木任务但【无目标空闲】（挖完了
     * 在找下一个或守在原地——正是用户允许的"处于任务状态但空闲"）。
     * 判定全部 try/catch 兜底 false——任何一个信号表异常都不该让搭路失效。
     */
    private static boolean isTaskOccupied(EntityMaid maid) {
        try {
            if (com.maidsmart.task.MaidMineBehavior.isMining(maid)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (com.maidsmart.task.MaidWoodBehavior.isWooding(maid)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (com.maidsmart.build.BlueprintBuildExecutor.isBuildingTask(maid)
                    && !com.maidsmart.build.BuildPlan.isBoundPlanPaused(maid)
                    && !com.maidsmart.build.BuildPlan.isMaidPaused(maid)) {
                return true; // 建造任务且未暂停（暂停 = 玩家叫停，视为空闲可追）
            }
        } catch (Throwable ignored) {
        }
        return maid.getPersistentData().m_128471_(MaidWorkTags.WORK_STILL_TAG);
    }

    private static double sq(double v) {
        return v * v;
    }
}
