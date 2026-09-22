package com.maidsmart.build;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.tool.MaidBuildBlockFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;

/**
 * 指标石临时建造行为（v1.2.0）——随隐藏任务 maid_smart:index_build 注册。
 *
 * 契约逐条对应：
 * - 站桩 + 瞬移到工地旁 + 挥臂 + 放置音效 → 与建造任务表现一致（复用
 *   MaidBuildBehavior.teleportToWorkSite + PlacedBlockTracker.placeSound）；
 * - 材料无限制：先女仆背包（含手部栏），不够从主人背包拉一组；选材规则复用
 *   搭路（MaidBuildBlockFilter.takeBuildBlock：数量最多者优先、必须有碰撞、
 *   排除下落方块/TNT/危险方块）；
 * - 填充顺序：会话 cells 已按"离女仆起点近→远"排序，逐格弹出 → 自然从自己
 *   所在格向锁定格推进；
 * - 全部填完 → IndexStoneService.onMaidFinished（播报 + 还原原任务）。
 *
 * 健壮性：会话丢失（玩家退出/重登）或长时间无法推进 → 自愈释放女仆并还原任务，
 * 绝不把她永久卡在临时任务上（对齐项目 SelfPreservationBehavior 的自愈约定）。
 */
public class IndexStoneBuildBehavior extends Behavior<EntityMaid> {

    private static final int PLACE_INTERVAL = 2;
    /** 缺料判失败的宽限（tick，默认 60=3 秒）——连续这么久拿不到任何材料就烂尾，
     *  留一点缓冲防"刚消费完一叠、主人背包正在补"这类瞬时缺料误判 */
    private static final int NO_MATERIAL_GRACE = 60;
    /** 无进展 tick 上限（20 秒）→ 视为会话丢失/彻底卡死，自愈收尾 */
    private static final int STALL_LIMIT = 400;

    private int cooldown = 0;
    private int stall = 0;
    private int noMaterial = 0;
    private int lastSize = -1;
    /** 是否已排过收尾（finish 走 server.execute 延后一 tick，期间本 tick 会重复进入，
     *  加锁防重复排程/重复播报） */
    private boolean finishing = false;
    /** 是否已排过自愈（同上：selfHeal 也会 setTask → refreshBrain，必须延后且只排一次） */
    private boolean healing = false;

    public IndexStoneBuildBehavior() {
        super(java.util.Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Override
    protected boolean m_6114_(ServerLevel level, EntityMaid maid) {
        return IndexStoneService.isEnabled() && IndexStoneService.isIndexBuilding(maid);
    }

    @Override
    protected void m_6735_(ServerLevel level, EntityMaid maid, long gameTime) {
        this.cooldown = 0;
        this.stall = 0;
        this.noMaterial = 0;
        this.lastSize = -1;
        this.finishing = false;
        this.healing = false;
        MaidWorkTagsSetStill(maid);
    }

    @Override
    protected void m_6725_(ServerLevel level, EntityMaid maid, long gameTime) {
        // 站桩（与建造一致：清移动目标 + 停导航 + 打站桩标记）
        MaidWorkTagsSetStill(maid);
        maid.m_6274_().m_21936_(MemoryModuleType.f_26370_);
        maid.m_21573_().m_26569_();

        IndexStoneService.Session s = IndexStoneService.sessionForMaid(maid);
        // 【完成判定必须在最前】——最后一格放完后 cells 变空，若不先判"她的会话还在
        // 且格子已清空"就是**成功完成**，就会被下面那条 selfHealStale 分支吃掉：
        // selfHealStale 出于"魂符放回来"的安全考虑【绝不触碰玩家会话】，
        // 于是绑定/锁定框/任务标记全都清不掉（"建造完成后不自动清绑定与幽灵方块"的根因）。
        if (s != null && s.maidId != null && s.maidId.equals(maid.m_20148_()) && s.cells.isEmpty()) {
            if (!this.finishing) {
                this.finishing = true; // 只排一次收尾（延后执行期间本 tick 会被重复进入）
                finish(level, maid);   // 全部填完 = 成功（播报 + 清绑定/幽灵/锁定 + 还原任务）
            }
            return;
        }
        if (s == null || s.cells.isEmpty()) {
            // 会话不存在 / 不属于她 → 残留标记自愈：只还原任务，【不】播报"搭好了"。
            // 【必须延后一 tick】selfHealStale 内部会 setTask → TLM refreshBrain 重建
            // 整个 Brain；在本行为正在 tick 的过程中重建 = 行为列表被换掉（与 finish
            // 同源风险，见其注释）→ 反馈的"放出来建模卡住"。这里与 finish 同款延后。
            if (!this.healing) {
                this.healing = true;
                heal(level, maid);
            }
            return;
        }
        // 进度推进检测（无进展太久 → 视为卡死，烂尾收尾）
        if (s.cells.size() != lastSize) {
            lastSize = s.cells.size();
            stall = 0;
        } else if (++stall > STALL_LIMIT) {
            IndexStoneService.failSession(maid, "长时间没有进展", true);
            return;
        }
        if (this.cooldown-- > 0) {
            return;
        }
        this.cooldown = PLACE_INTERVAL;

        BlockPos target = null;
        while (!s.cells.isEmpty()) {
            int[] c = s.cells.get(0);
            target = new BlockPos(c[0], c[1], c[2]);
            if (!level.m_46749_(target)) {
                // 区块未加载：不丢弃（丢弃 = 这格永远不补，蓝图留洞），本轮先退避，
                // 等区块加载后继续（stall 兜底：真的一直加载不了就整体收尾）
                return;
            }
            // 实测五百三十六：该格被主人碰撞箱占着——与"区块未加载"同款退避：
            // 不丢格、不耗材料，主人让开后下一轮自然回来补（remove(0) 会永久留洞）
            if (com.maidsmart.tool.MaidPlaceGuard.blockedAtOwner(maid, target)) {
                return;
            }
            if (!level.m_8055_(target).m_60795_()) {
                s.cells.remove(0); // 已被占用（别人放了/本来就是方块）→ 该格无需填
                continue;
            }
            break;
        }
        if (s.cells.isEmpty() || target == null) {
            // 全部填完（含"剩余格都已被别的方块占掉"）= 成功
            if (!this.finishing) {
                this.finishing = true;
                finish(level, maid);
            }
            return;
        }

        Block material = resolveMaterial(level, maid, target);
        if (material == null) {
            // v1.2.0：缺材料 = 失败（需求："搭建中途发现缺材料也视作失败，系统提示后
            // 清除幽灵方块+解绑"。留 3 秒宽限防瞬时缺料误判）
            if (++this.noMaterial >= NO_MATERIAL_GRACE) {
                IndexStoneService.failSession(maid, "缺少搭建材料", true);
                return;
            }
            if (this.noMaterial == 1 || this.noMaterial % 20 == 0) {
                maid.getChatBubbleManager().addTextChatBubble(
                        "没有可以搭建的方块了…再找不到材料我就先停啦");
            }
            return;
        }
        this.noMaterial = 0;

        // 与建造一致：先瞬移到工地旁（4 tick 限频），再放置 + 挥臂 + 音效
        MaidBuildBehavior.teleportToWorkSite(level, maid, target);
        if (place(level, maid, target, material)) {
            s.cells.remove(0);
            stall = 0;
        }
    }

    @Override
    protected boolean m_6737_(ServerLevel level, EntityMaid maid, long gameTime) {
        return IndexStoneService.isEnabled() && IndexStoneService.isIndexBuilding(maid);
    }

    @Override
    protected void m_6732_(ServerLevel level, EntityMaid maid, long gameTime) {
        com.maidsmart.task.MaidWorkTags.setStill(maid, false);
        maid.setHomeModeEnable(false);
    }

    private static void MaidWorkTagsSetStill(EntityMaid maid) {
        com.maidsmart.task.MaidWorkTags.setStill(maid, true);
        maid.setHomeModeEnable(true);
    }

    /**
     * 收尾：**必须延到本 tick 之后**再调 onMaidFinished——它内部会 setTask
     * （TLM setTask 会 refreshBrain 重建整个 Brain），在本行为正在 tick 的过程中
     * 重建 Brain = 行为列表被换掉，属于未定义行为（易崩溃/行为卡死）。
     * 用 server.execute 把收尾排到当前 tick 之后执行（项目既有约定：
     * SystemTTSManager / MaidChatLanguageMixin 同样用 m_18707_ 回主线程）。
     */
    private static void finish(ServerLevel level, EntityMaid maid) {
        try {
            level.m_7654_().m_18707_(() -> IndexStoneService.onMaidFinished(maid));
        } catch (Throwable t) {
            IndexStoneService.onMaidFinished(maid); // 服务器已停等极端情况 → 直接兜底
        }
    }

    /** 自愈收尾（同 {@link #finish}：延到本 tick 之后再用 setTask 重建 Brain） */
    private static void heal(ServerLevel level, EntityMaid maid) {
        try {
            level.m_7654_().m_18707_(() -> IndexStoneService.selfHealStale(maid));
        } catch (Throwable t) {
            IndexStoneService.selfHealStale(maid);
        }
    }

    /**
     * 选材：女仆背包（含手部栏，走搭路过滤器——数量最多者优先、必须有碰撞）→
     * 不够从主人背包拉一组 → 创造模式主人视为无限（不扣料）。
     * 返回要放置的方块；无任何材料返回 null。
     */
    private static Block resolveMaterial(ServerLevel level, EntityMaid maid, BlockPos target) {
        // v1.2.4【issue #19】：取材一律跳过"被动作表现借走的副手"（详见过滤器五参重载）
        boolean borrowed = com.maidsmart.combat.BombPose.offhandBorrowed(maid);
        Item it = MaidBuildBlockFilter.takeBuildBlock(
                maid.getAvailableBackpackInv(), maid.getHandsInvWrapper(), level, target, borrowed);
        if (it == null) {
            Player owner = maid.m_269323_() instanceof Player p ? p : null;
            if (pullFromOwner(owner, maid)) {
                it = MaidBuildBlockFilter.takeBuildBlock(
                        maid.getAvailableBackpackInv(), maid.getHandsInvWrapper(), level, target, borrowed);
            }
            if (it == null && owner != null && BlueprintLib.isCreative(owner)) {
                Block b = pickOwnerBlock(owner);
                if (b != null) {
                    return b; // 创造：不消耗，直接给方块
                }
            }
        }
        if (it instanceof BlockItem bi) {
            return bi.m_40614_();
        }
        if (it != null) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(it);
            Block b = id == null ? null : ForgeRegistries.BLOCKS.getValue(id);
            if (b != null) {
                return b;
            }
        }
        return null;
    }

    /** 从主人背包把"数量最多的可用垫脚方块"拉一组（64）给女仆；成功返回 true */
    private static boolean pullFromOwner(Player owner, EntityMaid maid) {
        if (owner == null || BlueprintLib.isCreative(owner)) {
            return false;
        }
        String bestId = null;
        int bestN = 0;
        Map<String, Integer> counts = new java.util.HashMap<>();
        net.minecraft.world.entity.player.Inventory inv = owner.m_150109_();
        for (int i = 0; i < inv.m_6643_(); i++) {
            ItemStack st = inv.m_8020_(i);
            if (st.m_41619_() || !(st.m_41720_() instanceof BlockItem)) {
                continue;
            }
            if (!MaidBuildBlockFilter.isUsableBuildStack(st, null, null)) {
                continue;
            }
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(st.m_41720_());
            if (id == null) {
                continue;
            }
            counts.merge(id.toString(), st.m_41613_(), Integer::sum);
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestN) {
                bestN = e.getValue();
                bestId = e.getKey();
            }
        }
        if (bestId == null) {
            return false;
        }
        BlueprintLib.deliverToMaid(owner, maid, Map.of(bestId, 64));
        return true;
    }

    /** 主人背包里数量最多、可用于垫脚/搭建的方块（创造模式取用，不消耗） */
    private static Block pickOwnerBlock(Player owner) {
        Block best = null;
        int bestN = 0;
        Map<Block, Integer> counts = new java.util.HashMap<>();
        net.minecraft.world.entity.player.Inventory inv = owner.m_150109_();
        for (int i = 0; i < inv.m_6643_(); i++) {
            ItemStack st = inv.m_8020_(i);
            if (st.m_41619_() || !(st.m_41720_() instanceof BlockItem bi)) {
                continue;
            }
            if (!MaidBuildBlockFilter.isUsableBuildStack(st, null, null)) {
                continue;
            }
            counts.merge(bi.m_40614_(), st.m_41613_(), Integer::sum);
        }
        for (Map.Entry<Block, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestN) {
                bestN = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    /**
     * 放置一块（目标格必须是空气）。放置失败（悬空等）→ 先在其下方补一块同材料
     * 再重放；仍失败返回 false（下轮重试，stall 兜底自愈）。
     */
    /**
     * 放置一块（目标格必须空气）；悬空失败时补下方同材料再重放。
     *
     * v1.2.0 实测四百八十二：核查确认本方法的顺序**本来就是"搭一个扣一个"**——
     * 目标格的材料由调用方 `resolveMaterial` 先取（内部 `takeBuildBlock` 先扣 1），
     * 下方支撑也走 `takeOneFor`（内部先 `extractItem`）**再** setBlock。
     * 即"补支撑送方块"的口子不在这里（原判断有误，此处更正）。
     */
    private static boolean place(ServerLevel level, EntityMaid maid, BlockPos target, Block block) {
        try {
            if (!level.m_8055_(target).m_60795_()) {
                return true; // 已被占 → 视为完成
            }
            BlockState st = block.m_49966_();
            level.m_7731_(target, st, 3);
            if (level.m_8055_(target).m_60795_()) {
                // 补下方支撑（悬空方块会立刻掉）——支撑也消耗同材料（先扣后放）
                BlockPos below = target.m_7918_(0, -1, 0);
                if (level.m_46749_(below) && level.m_8055_(below).m_60795_()
                        && takeOneFor(maid, level, below, block)) {
                    // v1.2.4【补支撑那一格也要过压人闸门】——调用方只判了 target，
                    // 而 below 是**另一格**：主人（或别的女仆）站在 below 的下一格时，
                    // 他的头部格正好是 below → 补支撑等于往他脑袋里塞方块。
                    // 其余三家（挖矿/伐木/搭路的 fill/support）与蓝图建造（supPos/below）
                    // 都是逐格判的，只有这条漏了，补上同一口径。
                    if (com.maidsmart.tool.MaidPlaceGuard.blockedAtOwner(maid, below)) {
                        return false;
                    }
                    level.m_7731_(below, st, 3);
                    level.m_7731_(target, st, 3);
                }
                if (level.m_8055_(target).m_60795_()) {
                    return false;
                }
            }
            maid.m_21011_(InteractionHand.MAIN_HAND, true);
            com.maidsmart.task.PlacedBlockTracker.placeSound(level, target, block);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 为补支撑再取一块【指定的】同种材料（女仆背包 → 主人背包）；取不到返回 false */
    private static boolean takeOneFor(EntityMaid maid, ServerLevel level, BlockPos pos, Block block) {
        Item item = block.m_5456_();
        net.minecraftforge.items.IItemHandler inv = maid.getAvailableBackpackInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (!st.m_41619_() && st.m_41720_() == item) {
                ItemStack taken = inv.extractItem(i, 1, false);
                if (!taken.m_41619_()) {
                    return true;
                }
            }
        }
        net.minecraftforge.items.IItemHandler hands = maid.getHandsInvWrapper();
        // v1.2.4【issue #19 同族】补支撑这一格同样不许从"被动作表现借走的副手"取料：
        // 那件是展示件复制品，取它 = 白放一块（本链虽不回收，但放出来的方块照样是真的）。
        boolean borrowed = com.maidsmart.combat.BombPose.offhandBorrowed(maid);
        for (int i = 0; i < Math.min(2, hands.getSlots()); i++) {
            if (borrowed && i == MaidBuildBlockFilter.OFFHAND_HAND_SLOT) {
                continue;
            }
            ItemStack st = hands.getStackInSlot(i);
            if (!st.m_41619_() && st.m_41720_() == item) {
                ItemStack taken = hands.extractItem(i, 1, false);
                if (!taken.m_41619_()) {
                    return true;
                }
            }
        }
        Player owner = maid.m_269323_() instanceof Player p ? p : null;
        if (owner != null && BlueprintLib.isCreative(owner)) {
            return true;
        }
        if (owner != null) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id != null) {
                BlueprintLib.deliverToMaid(owner, maid, Map.of(id.toString(), 1));
                for (int i = 0; i < inv.getSlots(); i++) {
                    ItemStack st = inv.getStackInSlot(i);
                    if (!st.m_41619_() && st.m_41720_() == item) {
                        ItemStack taken = inv.extractItem(i, 1, false);
                        if (!taken.m_41619_()) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}
