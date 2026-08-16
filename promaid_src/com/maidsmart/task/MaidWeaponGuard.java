package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraftforge.items.IItemHandlerModifiable;

/**
 * v1.5.332：幼儿女儿武器禁持——婴儿/幼年阶段的女儿
 * （RelationshipMemoryAdapter.isTooSmall = isChild 且阶段为 INFANT/JUVENILE）
 * 手上出现武器时，把武器从手上【删除】并【原地丢一个完全一样的】到地上。
 *
 * 背景（用户："小女仆手上拿武器还是有问题……如果在他手上放武器，会将这个
 * 武器进行删除，然后再往地上丢一个，完全一样的"）：幼儿女儿拿武器既不合
 * 设定也可能引发战斗行为问题。主手/副手（getHandsInvWrapper：Forge
 * EntityHandsInvWrapper，slot 0 = 主手、slot 1 = 副手）出现武器即移除，
 * 由 ProMaidExtension.onServerTick 每 1 秒轮询（覆盖玩家喂给/自动装备/
 * 任务换装等一切入手机制）。
 *
 * 范围：仅 女儿 + INFANT/JUVENILE（幼儿）——成年女儿/普通女仆不受影响；
 * 工具（镐/斧砍树等挖掘工具）不在武器判定内，不受影响。
 */
public final class MaidWeaponGuard {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private MaidWeaponGuard() {
    }

    /** 1 秒轮询全部已加载女仆（女仆数量少，开销可忽略） */
    public static void tick(MinecraftServer server) {
        try {
            for (ServerLevel level : server.m_129785_()) {
                for (EntityMaid maid : level.m_143280_(
                        net.minecraft.world.level.entity.EntityTypeTest.m_156916_(EntityMaid.class),
                        m -> true)) {
                    if (!maid.m_6084_()
                            || !com.maidsmart.memory.RelationshipMemoryAdapter.isTooSmall(maid)) {
                        continue;
                    }
                    stripWeapon(maid, 0); // 主手
                    stripWeapon(maid, 1); // 副手
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 手部 slot 上是武器 → 从手上删除 + 原地丢一个完全一样的 */
    private static void stripWeapon(EntityMaid maid, int slot) {
        try {
            IItemHandlerModifiable hands = (IItemHandlerModifiable) maid.getHandsInvWrapper();
            ItemStack stack = hands.getStackInSlot(slot);
            if (stack.m_41619_() || !isWeapon(stack)) {
                return;
            }
            ItemStack drop = stack.m_41777_(); // 先留副本（setStackInSlot 清的是槽位，不影响原栈对象）
            hands.setStackInSlot(slot, ItemStack.f_41583_); // 从手上删除
            if (maid.m_9236_() instanceof ServerLevel level) {
                ItemEntity entity = new ItemEntity(level, maid.m_20185_(),
                        maid.m_20186_() + 0.5, maid.m_20189_(), drop);
                level.m_7967_(entity); // 原地丢一个完全一样的
                LOGGER.info("weapon guard: 幼儿女儿 {} 手上的 {} 已移除并丢地",
                        maid.m_5446_() != null ? maid.m_5446_().getString() : maid.m_20148_().toString(),
                        drop.m_41786_().getString());
            }
        } catch (Exception ignored) {
        }
    }

    /** 武器判定：剑/斧/弓/弩/三叉戟（纯战斗类；镐等挖掘工具不受影响） */
    private static boolean isWeapon(ItemStack stack) {
        Item item = stack.m_41720_();
        return item instanceof SwordItem || item instanceof AxeItem
                || item instanceof BowItem || item instanceof CrossbowItem
                || item instanceof TridentItem;
    }
}
