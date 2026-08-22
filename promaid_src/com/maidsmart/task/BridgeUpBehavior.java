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

    /** 搭方块放置追踪：维度 → 位置 → 放置 tick（到期销毁变掉落物） */
    private record PlacedMark(long tick, String blockId) {
    }

    private static final Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
            Map<BlockPos, PlacedMark>> PLACED = new HashMap<>();

    private static void track(ServerLevel level, BlockPos pos, Block block) {
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
        PLACED.computeIfAbsent(level.m_46472_(), k -> new HashMap<>())
                .put(pos.m_7949_(), new PlacedMark(level.m_46467_(), key != null ? key.toString() : ""));
    }

    /** 到期搭方块销毁（ProMaidExtension 每 tick 调；女仆站上面延后一轮） */
    public static void expirePlaced(ServerLevel level, long gameTime) {
        Map<BlockPos, PlacedMark> marks = PLACED.get(level.m_46472_());
        if (marks == null || marks.isEmpty()) {
            return;
        }
        long lifetime = MaidSmartConfig.BRIDGE_PLACED_LIFETIME.get() * 20L;
        Iterator<Map.Entry<BlockPos, PlacedMark>> it = marks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, PlacedMark> e = it.next();
            if (gameTime - e.getValue().tick < lifetime) {
                continue;
            }
            BlockPos pos = e.getKey();
            if (supportsBridger(level, pos)) {
                continue; // 女仆站在上面 → 延后（走开即清）
            }
            it.remove();
            destroyMarked(level, pos, e.getValue());
        }
    }

    /** 服务器停止清场（残留方块立即回收） */
    public static void clearAll(net.minecraft.server.MinecraftServer server) {
        for (ServerLevel level : server.m_129785_()) {
            Map<BlockPos, PlacedMark> marks = PLACED.remove(level.m_46472_());
            if (marks == null) {
                continue;
            }
            for (Map.Entry<BlockPos, PlacedMark> e : marks.entrySet()) {
                destroyMarked(level, e.getKey(), e.getValue());
            }
        }
        PLACED.clear();
    }

    /** 该搭方块上是否正有搭路女仆站着（脚下格或所在格） */
    private static boolean supportsBridger(ServerLevel level, BlockPos pos) {
        for (EntityMaid m : level.m_45976_(EntityMaid.class, new AABB(pos).m_82400_(2.0))) {
            if (!m.m_6084_() || !m.getPersistentData().m_128471_(BRIDGING_TAG)) {
                continue;
            }
            BlockPos feet = m.m_20183_();
            if (feet.m_7949_().equals(pos) || feet.m_7918_(0, -1, 0).m_7949_().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    /** 销毁一个追踪方块：已被玩家换掉的不误破坏；掉落物走女仆拾取 */
    private static void destroyMarked(ServerLevel level, BlockPos pos, PlacedMark mark) {
        var state = level.m_8055_(pos);
        if (state.m_60795_()) {
            return;
        }
        ResourceLocation cur = ForgeRegistries.BLOCKS.getKey(state.m_60734_());
        if (!mark.blockId.isEmpty() && cur != null && !mark.blockId.equals(cur.toString())) {
            return; // 玩家已替换，尊重改动
        }
        level.m_46796_(2001, pos, Block.m_49956_(state));
        Block.m_49892_(state, level, pos, level.m_7702_(pos));
        level.m_7731_(pos, Blocks.f_50016_.m_49966_(), 3);
    }

    /* ==================== 行为本体 ==================== */

    /** 垫块节奏冷却（tick 计数） */
    private int stepCooldown = 0;
    /** 搭块防掉落窗口（刚垫完 12 tick 内钳制在方块中心） */
    private int guardTicks = 0;
    /** 材料耗尽播报限频 */
    private static final Map<Integer, Long> NO_BLOCK_SINCE = new HashMap<>();

    public BridgeUpBehavior() {
        super(Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Override
    protected boolean m_6114_(ServerLevel level, EntityMaid maid) {
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
        int dy = owner.m_20183_().m_123342_() - maid.m_20183_().m_123342_();
        if (dy < MaidSmartConfig.BRIDGE_MIN_DY.get()) {
            return false; // 主人不够高（平路/在下面——走路或跟随处理）
        }
        if (maid.m_20275_(owner.m_20185_(), owner.m_20186_(), owner.m_20189_())
                >= sq(MaidSmartConfig.BRIDGE_MAX_DIST.get())) {
            return false; // 超过传送判定距离——交给 TLM 瞬移/跟随
        }
        if (hasThreatNearby(level, maid)) {
            return false; // 有威胁不搭（塔会被拆/搭到一半挨打）
        }
        return takeBuildBlock(maid) != null; // 有料才启动
    }

    @Override
    protected void m_6735_(ServerLevel level, EntityMaid maid, long gameTime) {
        maid.getPersistentData().m_128379_(BRIDGING_TAG, true);
        this.stepCooldown = 0;
        this.guardTicks = 0;
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
        // 水平接近：导航到主人正下方（同柱不必精确，±1 容差由导航自己处理）
        double hx = owner.m_20185_() - maid.m_20185_();
        double hz = owner.m_20189_() - maid.m_20189_();
        double hDist = Math.sqrt(hx * hx + hz * hz);
        if (hDist > 1.2) {
            maid.m_21573_().m_26519_(owner.m_20185_(), maid.m_20186_(), owner.m_20189_(),
                    (float) (double) MaidSmartConfig.MINE_MOVE_SPEED.get());
        } else {
            maid.m_21573_().m_26573_(); // 站桩搭高（垂直列干净成型）
        }
        // 垂直接近：脚下垫方块（节奏冷却）
        if (dy >= 1 && this.stepCooldown-- <= 0) {
            this.placeStep(level, maid);
            this.stepCooldown = MaidSmartConfig.BRIDGE_STEP_COOLDOWN.get();
        }
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
        if (maid.m_20275_(owner.m_20185_(), owner.m_20186_(), owner.m_20189_())
                >= sq(MaidSmartConfig.BRIDGE_MAX_DIST.get() + 2)) {
            return false; // 主人走远了（超出传送判定+2 缓冲）——放弃
        }
        if (hasThreatNearby(level, maid)) {
            return false; // 威胁出现——中止（战术/自保接管）
        }
        if (maid.getPersistentData().m_128471_(com.maidsmart.combat.SelfPreservationBehavior.PRESERVE_TAG)) {
            return false; // 自保触发——让位
        }
        return true;
    }

    @Override
    protected void m_6732_(ServerLevel level, EntityMaid maid, long gameTime) {
        maid.getPersistentData().m_128379_(BRIDGING_TAG, false);
        NO_BLOCK_SINCE.remove(maid.m_19879_());
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
        track(level, place, block);
        this.guardTicks = 12;
    }

    /** 背包数量最多的可搭方块（BlockItem + 非下落；照挖矿 takeBuildBlock） */
    private static Item takeBuildBlock(EntityMaid maid) {
        IItemHandler inv = maid.getMaidInv();
        Map<Item, Integer> counts = new HashMap<>();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.m_41619_() || !(stack.m_41720_() instanceof BlockItem bi)) {
                continue;
            }
            Block block = bi.m_40614_();
            if (block == null || block instanceof FallingBlock) {
                continue; // 下落方块（沙/砾石）不用
            }
            counts.merge(stack.m_41720_(), stack.m_41613_(), Integer::sum);
        }
        Item best = null;
        int bestCount = 0;
        for (Map.Entry<Item, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        if (best == null) {
            return null;
        }
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.m_41619_() && stack.m_41720_() == best) {
                ItemStack taken = inv.extractItem(i, 1, false);
                if (!taken.m_41619_()) {
                    return best;
                }
            }
        }
        return null;
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
        Map<BlockPos, PlacedMark> marks = PLACED.get(level.m_46472_());
        return marks != null && marks.containsKey(pos.m_7949_());
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

    private static double sq(double v) {
        return v * v;
    }
}
