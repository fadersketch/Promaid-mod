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
        } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
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
        return idOf(stack).toLowerCase(java.util.Locale.ROOT).contains("tnt");
    }

    static TntBlock tntBlockOf(ItemStack stack) {
        try {
            if (stack == null || stack.m_41619_() || !(stack.m_41720_() instanceof BlockItem bi)) {
                return null;
            }
            return bi.m_40614_() instanceof TntBlock tb ? tb : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean has(EntityMaid maid, String... ids) {
        if (maid == null) {
            return false;
        }
        try {
            IItemHandler hands = (IItemHandler) maid.getHandsInvWrapper();
            for (int i = 0; i < hands.getSlots(); i++) {
                if (matchAny(hands.getStackInSlot(i), ids)) {
                    return true;
                }
            }
            IItemHandler inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                if (matchAny(inv.getStackInSlot(i), ids)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static boolean hasBed(EntityMaid maid) {
        return maid != null && !takeFirstMatch(maid, BombItems::isBed, true).m_41619_();
    }

    static boolean hasTnt(EntityMaid maid) {
        return !takeFirstMatch(maid, BombItems::isTnt, true).m_41619_();
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
        try {
            IItemHandler hands = (IItemHandler) maid.getHandsInvWrapper();
            for (int i = 0; i < hands.getSlots(); i++) {
                ItemStack s = hands.getStackInSlot(i);
                if (match.test(s)) {
                    return dryRun ? s : hands.extractItem(i, 1, false);
                }
            }
            IItemHandler inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (match.test(s)) {
                    return dryRun ? s : inv.extractItem(i, 1, false);
                }
            }
        } catch (Throwable ignored) {
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
                } catch (Throwable ignored) {
                }
                if (!one.m_41619_()) {
                    ItemStack left = inv.insertItem(i, one, false); // 先放回原槽（那一格刚被我们腾出来）
                    if (!left.m_41619_()) {
                        giveBack(maid, left);
                    }
                }
                return display;
            }
        } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
        }
        return isStack(stack, ID_FIRE_CHARGE);
    }

    private static boolean isIgniter(ItemStack stack) {
        return isFlintLike(stack) || isChargeLike(stack);
    }

    static boolean hasIgniter(EntityMaid maid) {
        return !takeFirstMatch(maid, BombItems::isIgniter, true).m_41619_();
    }

    static ItemStack useIgniter(EntityMaid maid) {
        if (maid == null) {
            return ItemStack.f_41583_;
        }
        try {
            IItemHandler hands = (IItemHandler) maid.getHandsInvWrapper();
            IItemHandler inv = maid.getMaidInv();
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
        } catch (Throwable ignored) {
            return ItemStack.f_41583_;
        }
    }

    static SoundEvent sound(String id) {
        try {
            return ForgeRegistries.SOUND_EVENTS.getValue(new net.minecraft.resources.ResourceLocation(id));
        } catch (Throwable ignored) {
            return null;
        }
    }
}
