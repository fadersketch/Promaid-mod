package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.PositionTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashSet;
import java.util.Set;

/**
 * 酿造行为：给酿造台补烈焰粉与材料，收取酿好的药水。
 * 酿造台槽位：0-2=药水瓶，3=材料，4=烈焰粉。
 * 平衡设计：
 * - 只管理燃料与材料（药水瓶由主人准备），每次处理 1 个动作
 * - 燃料/材料必须由女仆背包携带（白名单）；处理间隔 100 tick
 * - 三瓶药水齐且燃料材料耗尽时收取成品
 */
public class MaidBrewBehavior extends Behavior<EntityMaid> {
    /** 搜索范围（v1.5.9+：16 格，未找到目标时更积极地寻找周围的工作方块） */
    /** v1.5.88：读配置面板（misc.brewRadius / processCooldown） */
    private static int brewRadius() {
        return com.maidsmart.config.MaidSmartConfig.MISC_BREW_RADIUS.get();
    }

    private static int processCooldown() {
        return com.maidsmart.config.MaidSmartConfig.MISC_PROCESS_COOLDOWN.get();
    }


    /** v1.5.102：垂直搜索范围从配置面板读取（misc.verticalRange） */
    /** 处理间隔（v1.5.9：40 tick = 2 秒，更快） */

    /**
     * 酿造材料白名单（v1.5.25 收紧）：只含"正向"材料——净疣用于打底（粗药阶段被
     * extractFromMaidExcept 排除），其余是粗药→成品阶段能酿出正面效果的。
     * 删除了会酿出中毒(spider_eye)/虚弱(fermented_spider_eye)的负面材料，
     * 以及对粗药无效的 redstone/glowstone_dust 和喷溅化 gunpowder（全自动不碰）。
     */
    private static final Set<String> INGREDIENTS = new HashSet<>();

    static {
        addId("minecraft:nether_wart");
        addId("minecraft:glistering_melon_slice"); // 治疗
        addId("minecraft:golden_carrot");          // 夜视
        addId("minecraft:blaze_powder");           // 力量
        addId("minecraft:sugar");                  // 迅捷
        addId("minecraft:rabbit_foot");            // 跳跃
        addId("minecraft:magma_cream");            // 防火
        addId("minecraft:pufferfish");             // 水肺
        addId("minecraft:ghast_tear");             // 再生
        addId("minecraft:phantom_membrane");       // 缓降
    }

    private static void addId(String id) {
        INGREDIENTS.add(id);
    }

    private BlockPos standPos = null;
    private int cooldown = 0;
    /** 目标扫描节流：找不到酿造台时每 20 tick 才扫一次 */
    private int scanCooldown = 0;


    public MaidBrewBehavior() {
        // v1.5.124：无限运行时长（旧版默认 60 tick 上限导致行为每 3 秒重启）
        super(java.util.Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Override
    protected boolean m_6114_(ServerLevel level, EntityMaid maid) {
        // v1.5.24 站桩（参考建筑行为）：只要任务是酿造就启动，不再依赖附近有酿造台
        return isBrewTask(maid);
    }

    private static boolean isBrewTask(EntityMaid maid) {
        return maid.getTask() != null
                && ResourceLocation.parse("maid_smart:brew").equals(maid.getTask().getUid());
    }

    @Override
    protected void m_6735_(ServerLevel level, EntityMaid maid, long gameTime) {
        if (this.standPos == null) {
            this.standPos = this.findBrewingStand(level, maid);
        }
        this.cooldown = 0;
    }

    @Override
    protected void m_6725_(ServerLevel level, EntityMaid maid, long gameTime) {
        if (this.standPos == null) {
            // v1.5.24：找不到酿造台时站桩等待（不乱跑），节流扫描
            MaidWorkTags.setStill(maid, true);
            if (this.scanCooldown-- > 0) {
                return;
            }
            this.scanCooldown = 20;
            this.standPos = this.findBrewingStand(level, maid);
            if (this.standPos == null) {
                return;
            }
        }
        BlockState state = level.m_8055_(this.standPos);
        if (!(state.m_60734_() instanceof BrewingStandBlock)) {
            this.standPos = null;
            this.standUp(maid); // v1.5.252：酿造台没了恢复站立
            MaidWorkTags.setStill(maid, true); // 酿造台没了：继续站桩等扫描
            return;
        }
        double distSq = maid.m_20275_(this.standPos.m_123341_() + 0.5, this.standPos.m_123342_() + 0.5, this.standPos.m_123343_() + 0.5);
        if (distSq > 6.25) {
            // 还没到酿造台：解除站桩标记，允许 MoveToTargetSink 走过去
            MaidWorkTags.setStill(maid, false);
            this.standUp(maid);
            maid.m_6274_().m_21879_(MemoryModuleType.f_26370_,
                    new WalkTarget(new BlockPosTracker(this.standPos), 1.0f, 2));
            return;
        }
        // v1.5.17 站桩强化：绑定工作方块后每 tick 清移动目标 + 停止导航，
        // 冷却期间也清——防止漫游/跟随在冷却间隙重新设目标（完全站桩不动）
        MaidWorkTags.setStill(maid, true);
        maid.m_6274_().m_21936_(MemoryModuleType.f_26370_);
        maid.m_21573_().m_26569_();
        // v1.5.252：绑定完成 → 立刻坐下不动（类似建筑；每 tick 保持坐姿防状态机重置）
        if (!maid.isMaidInSittingPose()) {
            maid.m_20124_(net.minecraft.world.entity.Pose.SITTING);
        }
        if (this.cooldown-- > 0) {
            return;
        }
        this.cooldown = processCooldown();
        BlockEntity be = level.m_7702_(this.standPos);
        if (be instanceof BrewingStandBlockEntity) {
            this.processStand(maid, (Container) be);
        }
    }

    @Override
    protected boolean m_6737_(ServerLevel level, EntityMaid maid, long gameTime) {
        // v1.5.24 站桩：只要还在酿造任务就持续（酿造台在烧/暂时无事也不解除），任务切走才让位
        boolean still = isBrewTask(maid);
        if (!still) {
            MaidWorkTags.setStill(maid, false);
            this.standUp(maid);
        }
        return still;
    }

    @Override
    protected void m_6732_(ServerLevel level, EntityMaid maid, long gameTime) {
        // v1.5.24：行为真正停止时解除站桩标记（双保险）+ 恢复站立
        MaidWorkTags.setStill(maid, false);
        this.standUp(maid);
    }

    /** v1.5.252：恢复站立（若当前是坐姿） */
    private void standUp(EntityMaid maid) {
        try {
            if (maid.isMaidInSittingPose()) {
                maid.m_20124_(net.minecraft.world.entity.Pose.STANDING);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * v1.5.25 全自动酿药（重写）。
     * 旧版致命缺陷：金萝卜/烈焰粉/糖等材料【不能直接作用于水瓶】——必须先
     * 水瓶+下界疣 → awkward，再 awkward+材料 → 真药水（两阶段）。旧版不管
     * 基底直接塞槽3 → 酿造台永不反应 → 观感"乱塞材料不酿药"。
     *
     * 现在按【槽0 当前药水阶段】决定动作：
     * - 槽空 → 补水瓶（water）
     * - 水瓶（water）→ 槽3 只放下界疣（唯一能把水瓶变 awkward 的材料）
     * - 粗制药水（awkward）→ 槽3 放下界疣以外的任意材料（金萝卜/烈焰粉/糖…）
     * - 其他药水（平凡/浓稠/真药水）→ 收进女仆背包（平凡/浓稠是死路，收走腾位；
     *   真药水是成品）
     * 燃料槽 4 始终优先补烈焰粉；槽3 已有材料时不动（酿造台自动续酿消耗）。
     */
    private void processStand(EntityMaid maid, Container stand) {
        IItemHandler maidInv = maid.getMaidInv();

        // 1. 补燃料（槽 4）——始终优先，没燃料什么都不酿
        if (stand.m_8020_(4).m_41619_()) {
            ItemStack fuel = this.extractItemFromMaid(maidInv, "minecraft:blaze_powder", 1);
            if (!fuel.m_41619_()) {
                stand.m_6836_(4, fuel);
            }
        }
        // 2. 收成品：槽0-2 里是"最终状态"（water/awkward 之外的一切药水）→ 收走
        //    （平凡/浓稠是无效果死路也收，腾出位置重新酿）
        for (int i = 0; i <= 2; i++) {
            ItemStack s = stand.m_8020_(i);
            if (this.isDonePotion(s)) {
                ItemStack taken = stand.m_8016_(i);
                ItemStack left = ItemHandlerHelper.insertItemStacked(maidInv, taken, false);
                if (!left.m_41619_()) {
                    stand.m_6836_(i, left);
                }
            }
        }
        // 3. 按阶段补水瓶/补材料（以槽0 状态为准；槽3 空时才放材料，一次一轮）
        //    v1.5.252：手册"自动酿造"关闭时跳过——只维持（补燃料+收成品），
        //    由玩家/LLM 指令决定酿什么
        if (com.maidsmart.config.MaidSmartConfig.MISC_BREW_AUTO.get()) {
            for (int i = 0; i <= 2; i++) {
                ItemStack s = stand.m_8020_(i);
                if (s.m_41619_()) {
                    ItemStack bottle = this.extractWaterBottle(maidInv);
                    if (!bottle.m_41619_()) {
                        stand.m_6836_(i, bottle);
                    }
                    continue;
                }
                if (i == 0 && stand.m_8020_(3).m_41619_()) {
                    if (this.isPotion(s, "minecraft:water")) {
                        ItemStack wart = this.extractItemFromMaid(maidInv, "minecraft:nether_wart", 1);
                        if (!wart.m_41619_()) {
                            stand.m_6836_(3, wart);
                        }
                    } else if (this.isPotion(s, "minecraft:awkward")) {
                        // 粗药阶段：放下界疣以外的正向材料（红石/荧石对粗药无效、
                        // 负面材料已从 INGREDIENTS 移除）
                        ItemStack ingredient = this.extractFromMaidExcept(maidInv, INGREDIENTS, "minecraft:nether_wart", 1);
                        if (!ingredient.m_41619_()) {
                            stand.m_6836_(3, ingredient);
                        }
                    }
                }
            }
        }

        // v1.5.24：酿造中/无事可做时【不】放弃酿造台——持续站桩等待
        //（旧版 didSomething=false 就清 standPos → 酿造中开始漫游）
    }

    /** 是否"最终状态"药水（water/awkward 之外的一切：真药水/平凡/浓稠都收走） */
    private boolean isDonePotion(ItemStack s) {
        if (s.m_41619_() || !(s.m_41720_() instanceof net.minecraft.world.item.PotionItem)) {
            return false;
        }
        net.minecraft.world.item.alchemy.Potion p = net.minecraft.world.item.alchemy.PotionUtils.m_43579_(s);
        if (p == null) {
            return false;
        }
        net.minecraft.resources.ResourceLocation key = ForgeRegistries.POTIONS.getKey(p);
        if (key == null) {
            return false;
        }
        String k = key.toString();
        return !"minecraft:water".equals(k) && !"minecraft:awkward".equals(k);
    }

    /** 槽内药水是否为指定 Potion（按注册表 key 判定，可靠） */
    private boolean isPotion(ItemStack s, String potionId) {
        if (s.m_41619_() || !(s.m_41720_() instanceof net.minecraft.world.item.PotionItem)) {
            return false;
        }
        net.minecraft.world.item.alchemy.Potion p = net.minecraft.world.item.alchemy.PotionUtils.m_43579_(s);
        if (p == null) {
            return false;
        }
        net.minecraft.resources.ResourceLocation key = ForgeRegistries.POTIONS.getKey(p);
        return key != null && potionId.equals(key.toString());
    }

    /** 从白名单取材料，但排除指定 id（awkward 阶段不能再用下界疣） */
    private ItemStack extractFromMaidExcept(IItemHandler maidInv, Set<String> whitelistIds, String excludeId, int count) {
        for (int i = 0; i < maidInv.getSlots(); i++) {
            ItemStack stack = maidInv.getStackInSlot(i);
            if (stack.m_41619_()) {
                continue;
            }
            ResourceLocation stackId = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
            if (stackId != null && whitelistIds.contains(stackId.toString())
                    && !excludeId.equals(stackId.toString())) {
                return maidInv.extractItem(i, count, false);
            }
        }
        return ItemStack.f_41583_;
    }

    /** 从背包取 1 瓶水瓶（PotionItem 且药水为 water，动态 key 判定）；没有返回空 */
    private ItemStack extractWaterBottle(IItemHandler inv) {
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.m_41619_() || !(stack.m_41720_() instanceof net.minecraft.world.item.PotionItem)) {
                continue;
            }
            net.minecraft.world.item.alchemy.Potion potion = net.minecraft.world.item.alchemy.PotionUtils.m_43579_(stack);
            if (potion == null) {
                continue;
            }
            net.minecraft.resources.ResourceLocation key = ForgeRegistries.POTIONS.getKey(potion);
            if (key != null && "minecraft:water".equals(key.toString())) {
                return inv.extractItem(i, 1, false);
            }
        }
        return ItemStack.f_41583_;
    }

    private ItemStack extractFromMaid(IItemHandler maidInv, Set<String> whitelistIds, int count) {
        for (int i = 0; i < maidInv.getSlots(); i++) {
            ItemStack stack = maidInv.getStackInSlot(i);
            if (stack.m_41619_()) {
                continue;
            }
            ResourceLocation stackId = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
            if (stackId != null && whitelistIds.contains(stackId.toString())) {
                return maidInv.extractItem(i, count, false);
            }
        }
        return ItemStack.f_41583_;
    }

    private ItemStack extractItemFromMaid(IItemHandler maidInv, String itemId, int count) {
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(itemId));
        if (item == null) {
            return ItemStack.f_41583_;
        }
        for (int i = 0; i < maidInv.getSlots(); i++) {
            ItemStack stack = maidInv.getStackInSlot(i);
            if (!stack.m_41619_() && stack.m_41720_() == item) {
                return maidInv.extractItem(i, count, false);
            }
        }
        return ItemStack.f_41583_;
    }

    private BlockPos findBrewingStand(ServerLevel level, EntityMaid maid) {
        BlockPos pos = maid.m_20183_();
        for (int dy = -com.maidsmart.config.MaidSmartConfig.MISC_VERTICAL_RANGE.get();
             dy <= com.maidsmart.config.MaidSmartConfig.MISC_VERTICAL_RANGE.get(); dy++) {
            for (int dx = -brewRadius(); dx <= brewRadius(); dx++) {
                for (int dz = -brewRadius(); dz <= brewRadius(); dz++) {
                    BlockPos p = pos.m_7918_(dx, dy, dz);
                    if (level.m_8055_(p).m_60734_() instanceof BrewingStandBlock) {
                        return p;
                    }
                }
            }
        }
        return null;
    }
}
