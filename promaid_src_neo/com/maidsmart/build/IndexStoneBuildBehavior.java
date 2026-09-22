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

import java.util.Map;

/**
 * 指标石临时建造行为（v1.2.0，1.21.1 NeoForge 版）——随隐藏任务 maid_smart:index_build 注册。
 *
 * 契约逐条对应：
 * - 站桩 + 瞬移到工地旁 + 挥臂 + 放置音效 → 与建造任务表现一致（复用
 *   MaidBuildBehavior.teleportToWorkSite + PlacedBlockTracker.placeSound）；
 * - 材料无限制：先女仆背包（含手部栏），不够从主人背包拉一组；选材复用搭路
 *   （MaidBuildBlockFilter.takeBuildBlock：数量最多者优先、必须有碰撞）；
 * - 填充顺序：会话 cells 已按"离女仆起点近→远"排序，逐格弹出；
 * - 全部填完 → IndexStoneService.onMaidFinished（播报 + 还原原任务）。
 *
 * 健壮性：会话丢失（玩家退出/重登）或长时间无法推进 → 自愈释放并还原任务。
 */
public class IndexStoneBuildBehavior extends Behavior<EntityMaid> {

    private static final int PLACE_INTERVAL = 2;
    /** 缺料判失败的宽限（tick，默认 60=3 秒）——防"刚消费完一叠、主人正在补"误判 */
    private static final int NO_MATERIAL_GRACE = 60;
    /** 无进展 tick 上限（20 秒）→ 卡死收尾 */
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
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        return IndexStoneService.isEnabled() && IndexStoneService.isIndexBuilding(maid);
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        this.cooldown = 0;
        this.stall = 0;
        this.noMaterial = 0;
        this.lastSize = -1;
        this.finishing = false;
        this.healing = false;
        holdStill(maid);
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        holdStill(maid);
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        maid.getNavigation().stop();

        IndexStoneService.Session s = IndexStoneService.sessionForMaid(maid);
        // 【完成判定必须在最前】——最后一格放完后 cells 变空，若不先判"她的会话还在
        // 且格子已清空"就是**成功完成**，就会被下面那条 selfHealStale 分支吃掉：
        // selfHealStale 出于"魂符放回来"的安全考虑【绝不触碰玩家会话】，
        // 于是绑定/锁定框/任务标记全都清不掉（"建造完成后不自动清绑定与幽灵方块"的根因）。
        if (s != null && s.maidId != null && s.maidId.equals(maid.getUUID()) && s.cells.isEmpty()) {
            if (!this.finishing) {
                this.finishing = true; // 只排一次收尾
                finish(level, maid);   // 全部填完 = 成功
            }
            return;
        }
        if (s == null || s.cells.isEmpty()) {
            // 会话不存在 / 不属于她 → 残留标记自愈：只还原任务，不播报"搭好了"。
            // 【必须延后一 tick】selfHealStale 内部会 setTask → TLM refreshBrain 重建
            // 整个 Brain；在本行为正在 tick 的过程中重建 = 行为列表被换掉（与 finish
            // 同源风险，见其注释）→ 反馈的"放出来建模卡住"。这里与 finish 同款延后。
            if (!this.healing) {
                this.healing = true;
                heal(level, maid);
            }
            return;
        }
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
            if (!level.isLoaded(target)) {
                // 区块未加载：不丢弃，本轮退避等加载就绪（stall 兜底）
                return;
            }
            // 实测五百三十六：该格被主人碰撞箱占着——与"区块未加载"同款退避：
            // 不丢格、不耗材料，主人让开后下一轮自然回来补（remove(0) 会永久留洞）
            if (com.maidsmart.tool.MaidPlaceGuard.blockedAtOwner(maid, target)) {
                return;
            }
            if (!level.getBlockState(target).isAir()) {
                s.cells.remove(0); // 已被占用 → 该格无需填
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
            // v1.2.0：缺材料 = 失败（留 3 秒宽限防瞬时缺料误判）
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

        MaidBuildBehavior.teleportToWorkSite(level, maid, target);
        if (place(level, maid, target, material)) {
            s.cells.remove(0);
            stall = 0;
        }
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        return IndexStoneService.isEnabled() && IndexStoneService.isIndexBuilding(maid);
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        com.maidsmart.task.MaidWorkTags.setStill(maid, false);
        maid.setHomeModeEnable(false);
    }

    private static void holdStill(EntityMaid maid) {
        com.maidsmart.task.MaidWorkTags.setStill(maid, true);
        maid.setHomeModeEnable(true);
    }

    /**
     * 收尾：**必须延到本 tick 之后**再调 onMaidFinished——它内部会 setTask
     * （TLM setTask 会 refreshBrain 重建整个 Brain），在本行为正在 tick 的过程中
     * 重建 Brain = 行为列表被换掉，属于未定义行为（易崩溃/行为卡死）。
     * 用 server.execute 把收尾排到当前 tick 之后执行。
     */
    private static void finish(ServerLevel level, EntityMaid maid) {
        try {
            level.getServer().execute(() -> IndexStoneService.onMaidFinished(maid));
        } catch (Throwable t) {
            IndexStoneService.onMaidFinished(maid); // 服务器已停等极端情况 → 直接兜底
        }
    }

    /** 自愈收尾（同 {@link #finish}：延到本 tick 之后再用 setTask 重建 Brain） */
    private static void heal(ServerLevel level, EntityMaid maid) {
        try {
            level.getServer().execute(() -> IndexStoneService.selfHealStale(maid));
        } catch (Throwable t) {
            IndexStoneService.selfHealStale(maid);
        }
    }

    /** 选材：女仆背包（含手部栏，搭路规则）→ 不够从主人背包拉一组 → 创造主人无限 */
    private static Block resolveMaterial(ServerLevel level, EntityMaid maid, BlockPos target) {
        // v1.2.4【issue #19】：取材一律跳过"被动作表现借走的副手"（详见过滤器五参重载）
        boolean borrowed = com.maidsmart.combat.BombPose.offhandBorrowed(maid);
        Item it = MaidBuildBlockFilter.takeBuildBlock(
                maid.getAvailableBackpackInv(), maid.getHandsInvWrapper(), level, target, borrowed);
        if (it == null) {
            Player owner = maid.getOwner() instanceof Player p ? p : null;
            if (pullFromOwner(owner, maid)) {
                it = MaidBuildBlockFilter.takeBuildBlock(
                        maid.getAvailableBackpackInv(), maid.getHandsInvWrapper(), level, target, borrowed);
            }
            if (it == null && owner != null && BlueprintLib.isCreative(owner)) {
                Block b = pickOwnerBlock(owner);
                if (b != null) {
                    return b;
                }
            }
        }
        if (it instanceof BlockItem bi) {
            return bi.getBlock();
        }
        if (it != null) {
            ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it);
            Block b = id == null ? null
                    : net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);
            if (b != null) {
                return b;
            }
        }
        return null;
    }

    /** 从主人背包把"数量最多的可用垫脚方块"拉一组（64）给女仆 */
    private static boolean pullFromOwner(Player owner, EntityMaid maid) {
        if (owner == null || BlueprintLib.isCreative(owner)) {
            return false;
        }
        String bestId = null;
        int bestN = 0;
        Map<String, Integer> counts = new java.util.HashMap<>();
        net.minecraft.world.entity.player.Inventory inv = owner.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty() || !(st.getItem() instanceof BlockItem)) {
                continue;
            }
            if (!MaidBuildBlockFilter.isUsableBuildStack(st, null, null)) {
                continue;
            }
            ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem());
            if (id == null) {
                continue;
            }
            counts.merge(id.toString(), st.getCount(), Integer::sum);
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

    /** 主人背包里数量最多的可用方块（创造模式取用，不消耗） */
    private static Block pickOwnerBlock(Player owner) {
        Block best = null;
        int bestN = 0;
        Map<Block, Integer> counts = new java.util.HashMap<>();
        net.minecraft.world.entity.player.Inventory inv = owner.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty() || !(st.getItem() instanceof BlockItem bi)) {
                continue;
            }
            if (!MaidBuildBlockFilter.isUsableBuildStack(st, null, null)) {
                continue;
            }
            counts.merge(bi.getBlock(), st.getCount(), Integer::sum);
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
     * 放置一块（目标格必须空气）；悬空失败时补下方同材料再重放。
     *
     * v1.2.0 实测四百八十二：核查确认本方法的顺序**本来就是"搭一个扣一个"**——
     * 目标格的材料由调用方 `resolveMaterial` 先取（内部 `takeBuildBlock` 先扣 1），
     * 下方支撑也走 `takeOneFor`（内部先 `extractItem`）**再** setBlock。
     * 即"补支撑送方块"的口子不在这里（原判断有误，此处更正）。
     */
    private static boolean place(ServerLevel level, EntityMaid maid, BlockPos target, Block block) {
        try {
            if (!level.getBlockState(target).isAir()) {
                return true; // 已被占 → 视为完成
            }
            BlockState st = block.defaultBlockState();
            level.setBlock(target, st, 3);
            if (level.getBlockState(target).isAir()) {
                BlockPos below = target.below();
                // 先扣料（takeOneFor），再放支撑 —— 顺序 = 搭一个扣一个
                if (level.isLoaded(below) && level.getBlockState(below).isAir()
                        && takeOneFor(maid, below, block)) {
                    // v1.2.4【补支撑那一格也要过压人闸门】——调用方只判了 target，
                    // 而 below 是**另一格**：主人（或别的女仆）站在 below 的下一格时，
                    // 他的头部格正好是 below → 补支撑等于往他脑袋里塞方块。
                    // 其余三家（挖矿/伐木/搭路的 fill/support）与蓝图建造（supPos/below）
                    // 都是逐格判的，只有这条漏了，补上同一口径。
                    if (com.maidsmart.tool.MaidPlaceGuard.blockedAtOwner(maid, below)) {
                        return false;
                    }
                    level.setBlock(below, st, 3);
                    level.setBlock(target, st, 3);
                }
                if (level.getBlockState(target).isAir()) {
                    return false;
                }
            }
            maid.swing(InteractionHand.MAIN_HAND, true);
            com.maidsmart.task.PlacedBlockTracker.placeSound(level, target, block);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 为补支撑再取一块【指定的】同种材料（女仆背包/手部 → 主人背包） */
    private static boolean takeOneFor(EntityMaid maid, BlockPos pos, Block block) {
        Item item = block.asItem();
        net.neoforged.neoforge.items.IItemHandler inv = maid.getAvailableBackpackInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (!st.isEmpty() && st.getItem() == item) {
                ItemStack taken = inv.extractItem(i, 1, false);
                if (!taken.isEmpty()) {
                    return true;
                }
            }
        }
        net.neoforged.neoforge.items.IItemHandler hands = maid.getHandsInvWrapper();
        // v1.2.4【issue #19 同族】补支撑这一格同样不许从"被动作表现借走的副手"取料：
        // 那件是展示件复制品，取它 = 白放一块（本链虽不回收，但放出来的方块照样是真的）。
        boolean borrowed = com.maidsmart.combat.BombPose.offhandBorrowed(maid);
        for (int i = 0; i < Math.min(2, hands.getSlots()); i++) {
            if (borrowed && i == MaidBuildBlockFilter.OFFHAND_HAND_SLOT) {
                continue;
            }
            ItemStack st = hands.getStackInSlot(i);
            if (!st.isEmpty() && st.getItem() == item) {
                ItemStack taken = hands.extractItem(i, 1, false);
                if (!taken.isEmpty()) {
                    return true;
                }
            }
        }
        Player owner = maid.getOwner() instanceof Player p ? p : null;
        if (owner != null && BlueprintLib.isCreative(owner)) {
            return true;
        }
        if (owner != null) {
            ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
            if (id != null) {
                BlueprintLib.deliverToMaid(owner, maid, Map.of(id.toString(), 1));
                for (int i = 0; i < inv.getSlots(); i++) {
                    ItemStack st = inv.getStackInSlot(i);
                    if (!st.isEmpty() && st.getItem() == item) {
                        ItemStack taken = inv.extractItem(i, 1, false);
                        if (!taken.isEmpty()) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}
