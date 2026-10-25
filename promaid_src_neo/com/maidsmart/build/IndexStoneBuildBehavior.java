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
 * - 材料来源：她自己的双手/背包 + 全部附属容器（精妙背包/旅行者背包）+ 主人背包；
 *   启动前先扫这三处（一件都没有就拒绝绑定），选材走建造黑名单（MaidBuildBlockFilter）；
 * - 填充顺序：会话 cells 已按"离女仆起点近→远"排序，逐格弹出；
 * - 全部填完 → IndexStoneService.onMaidFinished（播报 + 还原原任务）。
 *
 * 健壮性：会话丢失（{@code s == null}）→ 自愈还原任务；**无进展 / 缺料一律不烂尾**
 * （实测一千〇六）：等区块 / 等主人让格 / 等材料，直到搭完或玩家解锁主动取消。
 */
public class IndexStoneBuildBehavior extends Behavior<EntityMaid> {

    private static final int PLACE_INTERVAL = 2;
    /** 缺料期间的重试节流（次）：首拍取材失败后，每这么多次尝试才再全量重试一次
     *  （含从主人背包拉料），防高频拉取；材料一补上立刻续建 */
    private static final int NO_MATERIAL_RETRY = 40;
    /** 缺料提示节流（次）：每隔这么多次尝试提醒一次主人补料（没有放弃路径） */
    private static final int NO_MATERIAL_REMIND = 100;
    /** 无进展留痕阈值（tick，20 秒）：只记一条日志、**不再放弃**（实测一千〇六） */
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
        // 进度推进检测（无进展太久 → 只留痕，不再烂尾：等区块/等主人让格/等材料）
        if (s.cells.size() != lastSize) {
            lastSize = s.cells.size();
            stall = 0;
        } else if (++stall == STALL_LIMIT) {
            com.maidsmart.tool.PromaidLog.log("指标石",
                    "临时建造 20 秒无进展——继续等待（等区块/等主人让格/等材料；"
                    + "不再烂尾，玩家解锁可主动取消）");
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

        Block material = null;
        // 实测一千〇六【执行到底】：缺料期间取材节流重试（首拍 + 每 NO_MATERIAL_RETRY 次），
        // 材料一补上立刻续建；旧版"3 秒找不到材料就烂尾"整条删除（明明有材料也误杀）
        if (this.noMaterial == 0 || this.noMaterial % NO_MATERIAL_RETRY == 0) {
            material = resolveMaterial(level, maid, target);
        }
        if (material == null) {
            this.noMaterial++;
            if (this.noMaterial == 1 || this.noMaterial % NO_MATERIAL_REMIND == 0) {
                maid.getChatBubbleManager().addTextChatBubble(
                        "没有可以搭建的方块了…主人给我一些方块吧（搭完才会走）");
            }
            return;
        }
        this.noMaterial = 0;

        // 与建造一致：先瞬移到工地旁（同建造的瞬移节流），再放置 + 挥臂 + 音效
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

    /**
     * 启动前"有没有料"总探针：主人背包 → 她的双手/背包 → 全部附属容器（精妙背包/旅行者背包）。
     * 判定走 {@link MaidBuildBlockFilter#isUsableBuildStack}（含「搭方块禁用名单」），与取料同源；
     * 只读（额外容器取出 1 件立刻放回）。一件都没有 → 调用方拒绝绑定。
     *
     * @param level 碰撞形状判定用；取料传目标格，启动探针传女仆脚下（null = 静态判定）
     * @return 有无可用方块；创造模式主人视为无限材料
     */
    public static boolean hasAnyBuildMaterial(EntityMaid maid, Player owner,
                                              net.minecraft.world.level.Level level, BlockPos pos) {
        try {
            if (owner != null && BlueprintLib.isCreative(owner)) {
                return true;
            }
            if (maid == null) {
                return false;
            }
            if (hasUsable(maid.getHandsInvWrapper(), level, pos)) {
                return true;
            }
            if (hasUsable(maid.getAvailableBackpackInv(), level, pos)) {
                return true;
            }
            if (com.maidsmart.tool.MaidExtraContainer.contains(maid,
                    s -> MaidBuildBlockFilter.isUsableBuildStack(s, level, pos))) {
                return true;
            }
            return owner != null && hasUsableOwner(owner, level, pos);
        } catch (Throwable t) {
            com.maidsmart.diag.Diag.swallow("IndexStoneBuildBehavior", t);
            return true; // 探针自身出错时放行：不因一次异常把指标石整条掐死
        }
    }

    private static boolean hasUsable(net.neoforged.neoforge.items.IItemHandler inv,
                                     net.minecraft.world.level.Level level, BlockPos pos) {
        for (int i = 0; i < inv.getSlots(); i++) {
            if (MaidBuildBlockFilter.isUsableBuildStack(inv.getStackInSlot(i), level, pos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUsableOwner(Player owner,
                                          net.minecraft.world.level.Level level, BlockPos pos) {
        net.minecraft.world.entity.player.Inventory inv = owner.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (MaidBuildBlockFilter.isUsableBuildStack(inv.getItem(i), level, pos)) {
                return true;
            }
        }
        return false;
    }

    /** 从她的附属容器（精妙背包/旅行者背包）搬一组可用方块进背包；成功返回 true。 */
    private static boolean pullFromExtraContainers(EntityMaid maid) {
        return com.maidsmart.tool.MaidExtraContainer.pull(maid,
                s -> MaidBuildBlockFilter.isUsableBuildStack(s, null, null), 64);
    }

    /** 选材：她双手/背包 → 附属容器 → 不够从主人背包拉一组 → 创造主人无限 */
    private static Block resolveMaterial(ServerLevel level, EntityMaid maid, BlockPos target) {
        // v1.2.4【issue #19】：取材一律跳过"被动作表现借走的副手"（详见过滤器五参重载）
        boolean borrowed = com.maidsmart.combat.BombPose.offhandBorrowed(maid);
        Item it = MaidBuildBlockFilter.takeBuildBlock(
                maid.getAvailableBackpackInv(), maid.getHandsInvWrapper(), level, target, borrowed);
        // 【R1100】她自己的背包没有 → 先问"附属容器"（精妙背包/旅行者背包）。TLM 把这层容器
        // 挂在她的饰品栏里，和背包一样算"她身上的料"；旧版只问主人，装了背包却不用里面的方块。
        if (it == null && pullFromExtraContainers(maid)) {
            it = MaidBuildBlockFilter.takeBuildBlock(
                    maid.getAvailableBackpackInv(), maid.getHandsInvWrapper(), level, target, borrowed);
        }
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
                // 先判"这一格放不放得下"，再扣料 —— 顺序 = 判一个、搭一个、扣一个。
                // **另一格**：主人（或别的女仆）站在 below 的下一格时，他的头部格正好是 below
                // → 补支撑等于往他脑袋里塞方块。其余三家（挖矿/伐木/搭路的 fill/support）与
                // 蓝图建造（supPos/below）都是逐格判的，只有这条漏了，补上同一口径。
                // 材料已经扣掉、方块却没放 = 白丢一件（闸门本意是"别往人身上放"，
                // 不该附带走失）。两句一交换即可，语义与其它三家一致。
                if (level.isLoaded(below) && level.getBlockState(below).isAir()
                        && !com.maidsmart.tool.MaidPlaceGuard.blockedAtOwner(maid, below)
                        && takeOneFor(maid, below, block)) {
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
             com.maidsmart.diag.Diag.swallow("IndexStoneBuildBehavior", t); return false;
        }
    }

    /** 为补支撑再取一块【指定的】同种材料（女仆背包/手部 → 主人背包） */
    private static boolean takeOneFor(EntityMaid maid, BlockPos pos, Block block) {
        Item item = block.asItem();
        // 真取物：先从额外容器（精妙背包/旅行者背包）搬 1 件到背包，下面的扫描才拿得到。
        com.maidsmart.tool.MaidExtraContainer.pull(maid,
                s -> !s.isEmpty() && s.getItem() == item, 1);
        net.neoforged.neoforge.items.IItemHandler inv = maid.getAvailableBackpackInv();
        // 【实测七百六十二·issue #19 三修】三处取材全部改走 MaidBuildBlockFilter.extractOneHonest：
        // 原来只看 extractItem 返回值非空，坏包装层"返回副本却不真扣"时等于白放一块
        // （本链虽不回收，但放出来的方块是真的）。没真扣 → 继续试下一槽。
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (!st.isEmpty() && st.getItem() == item
                    && MaidBuildBlockFilter.extractOneHonest(inv, i)) {
                return true;
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
            if (!st.isEmpty() && st.getItem() == item
                    && MaidBuildBlockFilter.extractOneHonest(hands, i)) {
                return true;
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
                    if (!st.isEmpty() && st.getItem() == item
                            && MaidBuildBlockFilter.extractOneHonest(inv, i)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
