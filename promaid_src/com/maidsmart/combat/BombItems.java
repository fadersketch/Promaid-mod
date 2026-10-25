package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraftforge.items.IItemHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 轰炸用到的物品识别与取放（v1.2.4 从 MaidBombing 拆出）。
 * 
 * TNT/床/重生锚/末地水晶/打火石/火焰弹的 id 常量、缓存与判定，
 * 以及从女仆背包取一件、还一件、用掉打火工具。
 */
public final class BombItems {
    private BombItems() {
    }

    static final String ID_OBSIDIAN = "minecraft:obsidian";

    static final String ID_BEDROCK = "minecraft:bedrock";

    static final String ID_END_CRYSTAL = "minecraft:end_crystal";

    static final String ID_RESPAWN_ANCHOR = "minecraft:respawn_anchor";

    static final String ID_GLOWSTONE = "minecraft:glowstone";

    private static final String ID_TNT = "minecraft:tnt";

    /** TNT 矿车那一件（原版 id）：认到它时扔出去的是「它自己那一辆点着的矿车」——见 {@code BombThrow}。 */
    private static final String ID_TNT_MINECART = "minecraft:tnt_minecart";

    private static final String ID_FLINT_AND_STEEL = "minecraft:flint_and_steel";

    private static final String ID_FIRE_CHARGE = "minecraft:fire_charge";

    static final String ID_TNT_PRIMED_SOUND = "minecraft:entity.tnt.primed";

    private static final Map<String, Item> ITEM_CACHE = new HashMap<>();

    private static Item item(String id) {
        Item cached = ITEM_CACHE.get(id);
        if (cached != null) {
            return cached;
        }
        try {
            Item it = ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation(id));
            if (it != null) {
                ITEM_CACHE.put(id, it);
            }
            return it;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombItems", ignored);
            return null;
        }
    }

    private static boolean isStack(ItemStack stack, String id) {
        if (stack == null || stack.m_41619_()) {
            return false;
        }
        Item want = item(id);
        return want != null && stack.m_150930_(want);
    }

    private static boolean matchAny(ItemStack stack, String... ids) {
        for (String id : ids) {
            if (isStack(stack, id)) {
                return true;
            }
        }
        return false;
    }

    static String idOf(ItemStack stack) {
        try {
            net.minecraft.resources.ResourceLocation rl = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
            return rl == null ? "" : rl.toString();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombItems", ignored);
            return "";
        }
    }

    private static boolean isBed(ItemStack stack) {
        try {
            if (stack == null || stack.m_41619_()) {
                return false;
            }
            if (!(stack.m_41720_() instanceof BlockItem bi)) {
                return false;
            }
            return bi.m_40614_() instanceof BedBlock;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombItems", ignored);
            return false;
        }
    }

    private static boolean isTnt(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return false;
        }
        if (isStack(stack, ID_TNT)) {
            return true; // 原版那一件：注册表查询万一失手也照认
        }
        if (tntBlockOf(stack) != null) {
            return true; // v1.2.2 实测六百〇五：方块继承 TntBlock 的（模组 TNT 多半名字里没有 tnt）
        }
        // 名字带 tnt：原版 TNT 矿车（minecraft:tnt_minecart）与各模组 TNT 都从这一条进来——
        // 矿车那一类在投掷链里放的是「它自己那一辆矿车」，判据见 {@link #isTntMinecart}
        return idOf(stack).toLowerCase(java.util.Locale.ROOT).contains("tnt");
    }

    /**
     * 这一件是不是「TNT 矿车」那一类：它按 {@link #isTnt} 的老口径本来就算 TNT（名字里就带 tnt），
     * 单拎出来是因为**扔出去的东西不一样**——矿车要放的是它自己那一辆点着的矿车（见 {@code BombThrow}），
     * 而不是回落成一枚普通引信 TNT。模组矿车：是矿车物品（Item 继承 MinecartItem）且注册名带 tnt。
     */
    static boolean isTntMinecart(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return false;
        }
        if (isStack(stack, ID_TNT_MINECART)) {
            return true; // 原版那一件：注册表查询万一失手也照认（与 isTnt 同一口径）
        }
        try {
            if (stack.m_41720_() instanceof net.minecraft.world.item.MinecartItem) {
                return idOf(stack).toLowerCase(java.util.Locale.ROOT).contains("tnt");
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombItems", ignored);
        }
        return false;
    }

    static TntBlock tntBlockOf(ItemStack stack) {
        try {
            if (stack == null || stack.m_41619_() || !(stack.m_41720_() instanceof BlockItem bi)) {
                return null;
            }
            return bi.m_40614_() instanceof TntBlock tb ? tb : null;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombItems", ignored);
            return null;
        }
    }

    static boolean has(EntityMaid maid, String... ids) {
        if (maid == null) {
            return false;
        }
        return com.maidsmart.tool.MaidExtraContainer.any(maid, s -> matchAny(s, ids));
    }

    static boolean hasBed(EntityMaid maid) {
        return maid != null && com.maidsmart.tool.MaidExtraContainer.any(maid, BombItems::isBed);
    }

    static boolean hasTnt(EntityMaid maid) {
        return maid != null && com.maidsmart.tool.MaidExtraContainer.any(maid, BombItems::isTnt);
    }

    static ItemStack takeOne(EntityMaid maid, String... ids) {
        return takeFirst(maid, ids, false);
    }

    static ItemStack takeOneBed(EntityMaid maid) {
        return takeFirst(maid, null, false);
    }

    static ItemStack takeOneTnt(EntityMaid maid) {
        return takeFirstMatch(maid, BombItems::isTnt, false);
    }

    private static ItemStack takeFirst(EntityMaid maid, String[] ids, boolean dryRun) {
        return takeFirstMatch(maid, ids == null ? BombItems::isBed : s -> matchAny(s, ids), dryRun);
    }

    private static ItemStack takeFirstMatch(EntityMaid maid,
                                            java.util.function.Predicate<ItemStack> match, boolean dryRun) {
        if (maid == null) {
            return ItemStack.f_41583_;
        }
        // 真取物：先请 TLM 从额外容器（精妙背包/旅行者背包）搬一件进来，下面的
        // 手/背包扫描才拿得到。dryRun 是纯判定，不搬（判定侧用 MaidExtraContainer.any）。
        if (!dryRun) {
            com.maidsmart.tool.MaidExtraContainer.pull(maid, match, 1);
        }
        try {
            IItemHandler hands = (IItemHandler) maid.getHandsInvWrapper();
            // v1.2.4【issue #19 同族·战斗放置】动作表现借走副手那 10 tick 里，副手上是**展示件**
            // （复制品，见 BombPose.show）——把它当材料等于"免费放一块"。而这条链的底座是
            // **逐格回收真物品**的（BombPlacement.removePlaced → returnBlockItem，按方块 id 合成
            // 一件还她），一进一出就是净产出，与搭路刷物品同一形状。
            // 所以取材跳过副手；没被借走时照旧（玩家把方块挂她副手是正常用法）。
            boolean borrowed = BombPose.offhandBorrowed(maid);
            for (int i = 0; i < hands.getSlots(); i++) {
                if (borrowed && i == com.maidsmart.tool.MaidBuildBlockFilter.OFFHAND_HAND_SLOT) {
                    continue;
                }
                ItemStack s = hands.getStackInSlot(i);
                if (!match.test(s)) {
                    continue;
                }
                if (dryRun) {
                    return s;
                }
                // 【实测七百六十二·issue #19 三修：战斗放置也要"诚实取材"】
                // 第二轮的核对口径只接在 MaidBuildBlockFilter 一个收口上，这条链漏了：
                // 裸 extractItem 在某些包装层下"返回副本却不真扣"，而回收侧
                // BombPlacement.returnBlockItem 是按方块 id 造一件**真物品**还她 → 净 +1，
                // 且只在恰好选中那个坏槽位时发生（就是报告者那句"长期使用缓慢增加"）。
                // 没真扣 → 判为没取到，试下一个槽位（与搭路完全同一口径）。
                ItemStack taken = s.m_41777_();
                taken.m_41764_(1);
                if (com.maidsmart.tool.MaidBuildBlockFilter.extractOneHonest(hands, i)) {
                    return taken;
                }
            }
            IItemHandler inv = maid.getAvailableBackpackInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (!match.test(s)) {
                    continue;
                }
                if (dryRun) {
                    return s;
                }
                ItemStack taken = s.m_41777_();
                taken.m_41764_(1);
                if (com.maidsmart.tool.MaidBuildBlockFilter.extractOneHonest(inv, i)) {
                    return taken;
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombItems", ignored);
        }
        return ItemStack.f_41583_;
    }

    static void giveBack(EntityMaid maid, ItemStack stack) {
        com.maidsmart.tool.MaidGiveBack.give(maid, stack, "轰炸用不掉的材料");
    }

    private static ItemStack useIgniterIn(IItemHandler inv, EntityMaid maid,
                                         java.util.function.Predicate<ItemStack> match) {
        try {
            for (int i = 0; i < inv.getSlots(); i++) {
                if (!match.test(inv.getStackInSlot(i))) {
                    continue;
                }
                ItemStack one = inv.extractItem(i, 1, false);
                if (one.m_41619_()) {
                    continue;
                }
                if (one.m_41776_() <= 0) {
                    return one; // 消耗品（没有耐久）：整件消耗——取出来的这一件就是"刚用掉的那一件"
                }
                ItemStack display = one.m_41777_(); // 给动作亮的快照（与槽里那一件不共用对象）
                try {
                    // 道具（有耐久）：原版口径——点一次掉 1 点耐久（1.20.1：hurtAndBreak(1, 实体, 损坏回调)）
                    one.m_41622_(1, maid, m -> m.m_21166_(EquipmentSlot.MAINHAND));
                } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombItems", ignored);
                }
                if (!one.m_41619_()) {
                    ItemStack left = inv.insertItem(i, one, false); // 先放回原槽（那一格刚被我们腾出来）
                    if (!left.m_41619_()) {
                        giveBack(maid, left);
                    }
                }
                return display;
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombItems", ignored);
        }
        return ItemStack.f_41583_;
    }

    private static boolean isFlintLike(ItemStack stack) {
        try {
            if (stack == null || stack.m_41619_()) {
                return false;
            }
            if (stack.m_41720_() instanceof FlintAndSteelItem) {
                return true;
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombItems", ignored);
        }
        return isStack(stack, ID_FLINT_AND_STEEL);
    }

    private static boolean isChargeLike(ItemStack stack) {
        try {
            if (stack == null || stack.m_41619_()) {
                return false;
            }
            if (stack.m_41720_() instanceof FireChargeItem) {
                return true;
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombItems", ignored);
        }
        return isStack(stack, ID_FIRE_CHARGE);
    }

    private static boolean isIgniter(ItemStack stack) {
        return isFlintLike(stack) || isChargeLike(stack);
    }

    static boolean hasIgniter(EntityMaid maid) {
        return maid != null && com.maidsmart.tool.MaidExtraContainer.any(maid, BombItems::isIgniter);
    }

    static ItemStack useIgniter(EntityMaid maid) {
        if (maid == null) {
            return ItemStack.f_41583_;
        }
        try {
            IItemHandler hands = (IItemHandler) maid.getHandsInvWrapper();
            IItemHandler inv = maid.getAvailableBackpackInv();
            ItemStack used = useIgniterIn(hands, maid, BombItems::isFlintLike);
            if (used.m_41619_()) {
                used = useIgniterIn(inv, maid, BombItems::isFlintLike);
            }
            if (used.m_41619_()) {
                used = useIgniterIn(hands, maid, BombItems::isChargeLike);
            }
            if (used.m_41619_()) {
                used = useIgniterIn(inv, maid, BombItems::isChargeLike);
            }
            return used;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombItems", ignored);
            return ItemStack.f_41583_;
        }
    }

    // ===== 实测九百四十五【压缩盒差异化】：盒子里存了弹药 → 一次投/放多个 =====

    /** 压缩盒「弹药加成」的一次投 / 放上限（开发者口径：一次性扔 / 放 5 个，凑出"压缩爆炸"）。 */
    static final int BOX_BURST = 5;

    /**
     * 女仆背包里所有压缩盒内、匹配 {@code match} 的物品总数量（真实数量；没盒子 / 没开延伸 = 0）。
     * 走 {@code getMaidInv()} 的盒子段（{@code CompressionBoxMaidInv#boxRange()}，视图封顶 64——
     * 对"够不够 5 个"足够）。
     */
    static int boxAmmoCount(EntityMaid maid, java.util.function.Predicate<ItemStack> match) {
        if (maid == null) {
            return 0;
        }
        try {
            if (!(maid.getMaidInv() instanceof com.maidsmart.box.CompressionBoxMaidInv mv)) {
                return 0;
            }
            IItemHandler box = mv.boxRange();
            if (box == null) {
                return 0;
            }
            int n = 0;
            for (int i = 0; i < box.getSlots(); i++) {
                ItemStack s = box.getStackInSlot(i);
                if (!s.m_41619_() && match.test(s)) {
                    n += s.m_41613_();
                }
            }
            return n;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombItems", ignored);
            return 0;
        }
    }

    /** 一次投 / 放件数：盒子里存了 ≥2 件这类弹药 → 最多 {@link #BOX_BURST} 件（按存量取小），否则 1。 */
    static int boxBurst(EntityMaid maid, java.util.function.Predicate<ItemStack> match) {
        int inBox = boxAmmoCount(maid, match);
        return inBox >= 2 ? Math.min(BOX_BURST, inBox) : 1;
    }

    /** TNT 的盒子加成件数（轰炸投掷链路用）。 */
    static int boxBurstTnt(EntityMaid maid) {
        return boxBurst(maid, BombItems::isTnt);
    }

    /** 末地水晶的盒子加成件数（放置链路用）。 */
    static int boxBurstCrystal(EntityMaid maid) {
        return boxBurst(maid, s -> isStack(s, ID_END_CRYSTAL));
    }

    /** 重生锚的盒子加成件数（放置链路用）。 */
    static int boxBurstAnchor(EntityMaid maid) {
        return boxBurst(maid, s -> isStack(s, ID_RESPAWN_ANCHOR));
    }

    static SoundEvent sound(String id) {
        try {
            return ForgeRegistries.SOUND_EVENTS.getValue(new net.minecraft.resources.ResourceLocation(id));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombItems", ignored);
            return null;
        }
    }
}
