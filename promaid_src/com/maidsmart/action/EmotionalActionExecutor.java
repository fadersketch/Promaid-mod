package com.maidsmart.action;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.network.NetworkHandler;
import com.github.tartaricacid.touhoulittlemaid.network.message.SpawnParticleMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.behavior.PositionTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashSet;
import java.util.Set;

/**
 * 情绪价值动作执行器（v1.2）：主动对话触发时，配合说话执行游戏内动作。
 *
 * 动作库（全部服务端、无瞬移、无凭空物品）：
 * - walkToOwner   ：走到主人身边（复用寻路，距离上限 32 格）
 * - lookAtOwner   ：看向主人
 * - heartParticles：向附近玩家播放心形粒子（主模组官方粒子消息）
 * - giveFoodToOwner：主人饥饿（<15）且背包有熟食时递上一个
 */
public final class EmotionalActionExecutor {
    /** 递食白名单（v1.5.206 扩展为原版【全量安全有益食物】）：
     *  - 熟食/煲汤/蔬果全收录——喂食按饱和度恢复量自动选最优
     *    （nutrition × saturationModifier × 2.0：金胡萝卜 14.4 最高、熟牛排/猪排 12.8 次之）；
     *  - 金苹果/附魔金苹果【不在内】——走即时增益道具路径（MaidAidOwnerBehavior.useGoldenApple）；
     *  - 危险/负面【不喂】——腐肉/蜘蛛眼/毒马铃薯/河豚/紫颂果（瞬移）/可疑炖菜（效果随机），
     *    以及一切生食（raw_*，生鸡肉有中毒概率）；
     *  - 牛奶桶/蜂蜜瓶不在内——走"负面效果解除"药物路径（v1.5.252g10：蜂蜜
     *    只在中毒时喝，不当普通食物消耗）。 */
    public static final Set<ItemStack> FOODS = new HashSet<>();

    static {
        // 高饱熟食
        addFood("minecraft:cooked_beef");      // 8 营养 / 12.8 饱和
        addFood("minecraft:cooked_porkchop");  // 8 / 12.8
        addFood("minecraft:cooked_mutton");    // 6 / 9.6
        addFood("minecraft:cooked_salmon");    // 6 / 9.6
        addFood("minecraft:cooked_chicken");   // 6 / 7.2
        addFood("minecraft:cooked_rabbit");    // 5 / 6
        addFood("minecraft:cooked_cod");       // 5 / 6
        // 煲汤/派（碗装/高营养）
        addFood("minecraft:rabbit_stew");      // 10 / 12
        addFood("minecraft:beetroot_soup");    // 6 / 7.2
        addFood("minecraft:mushroom_stew");    // 6 / 7.2
        addFood("minecraft:pumpkin_pie");      // 8 / 4.8
        // 蔬果/主食（含最高饱和的金胡萝卜）
        addFood("minecraft:golden_carrot");    // 6 / 14.4（饱和最高）
        addFood("minecraft:baked_potato");     // 5 / 6
        addFood("minecraft:bread");            // 5 / 6
        addFood("minecraft:carrot");           // 3 / 3.6
        addFood("minecraft:apple");            // 4 / 2.4
        addFood("minecraft:melon_slice");      // 2 / 1.2
        addFood("minecraft:sweet_berries");    // 2 / 0.4
        addFood("minecraft:glow_berries");     // 2 / 0.4
        addFood("minecraft:cookie");           // 2 / 0.4
        addFood("minecraft:dried_kelp");       // 1 / 0.6
        // v1.5.252g10：蜂蜜瓶已移出食物清单——它既是食物也是药物，按药物对待
        // （只在中毒时喝：drinkHoneyForPoison / 投喂负面解除），不当普通食物
        // 消耗（否则主人低饱食时会被当食物喂掉，真正中毒时反而没得喝）
    }

    private static void addFood(String id) {
        ItemStack stack = new ItemStack(ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(id)));
        if (!stack.m_41619_()) {
            FOODS.add(stack);
        }
    }

    private EmotionalActionExecutor() {
    }

    public static void walkToOwner(EntityMaid maid, ServerPlayer owner) {
        if (maid.m_20270_(owner) > 32.0f) {
            return;
        }
        BlockPos pos = owner.m_20183_();
        maid.m_6274_().m_21879_(MemoryModuleType.f_26370_,
                new WalkTarget(new BlockPosTracker(pos), 1.0f, 2));
    }

    public static void lookAtOwner(EntityMaid maid, ServerPlayer owner) {
        maid.m_6274_().m_21879_(MemoryModuleType.f_26371_,
                new EntityTracker(owner, true));
    }

    public static void heartParticles(EntityMaid maid) {
        NetworkHandler.sendToNearby(maid, new SpawnParticleMessage(maid.m_19879_(), SpawnParticleMessage.Type.HEART));
    }

    /** 主人饥饿时递上一个熟食（v1.5.201：按"饱和度恢复量"选最优——
     *  nutrition × saturationModifier × 2.0，不再按背包槽位顺序；主人背包满自动退回） */
    public static boolean giveFoodToOwner(EntityMaid maid, ServerPlayer owner) {
        if (owner.m_36324_().m_38702_() >= 15) {
            return false;
        }
        IItemHandler maidInv = maid.getMaidInv();
        int bestSlot = -1;
        double bestSat = -1.0;
        for (int i = 0; i < maidInv.getSlots(); i++) {
            ItemStack stack = maidInv.getStackInSlot(i);
            if (stack.m_41619_()) {
                continue;
            }
            boolean isFood = false;
            for (ItemStack food : FOODS) {
                if (food.m_41720_() == stack.m_41720_()) {
                    isFood = true;
                    break;
                }
            }
            if (!isFood) {
                continue;
            }
            double sat = foodSaturation(stack, owner);
            if (sat > bestSat) {
                bestSat = sat;
                bestSlot = i;
            }
        }
        if (bestSlot < 0) {
            return false;
        }
        ItemStack toGive = maidInv.extractItem(bestSlot, 1, false);
        ItemStack remain = ItemHandlerHelper.insertItemStacked(
                new net.minecraftforge.items.wrapper.InvWrapper(owner.m_150109_()), toGive, false);
        if (!remain.m_41619_()) {
            ItemHandlerHelper.insertItemStacked(maidInv, remain, false);
            return false;
        }
        return true;
    }

    /** v1.5.201：食物饱和度恢复量（MC 公式：nutrition × saturationModifier × 2.0）——
     *  喂食优先级依据。m_41720_=getItem、m_41473_=Item.getFoodProperties()（无参，
     *  1.20.1 唯一存在的版本；TLM 字节码里调用的两参 getFoodProperties(ItemStack,
     *  LivingEntity) 在 1.20.1 不存在——运行时必静默失败，TLM 喂食失效的隐藏原因）、
     *  m_38744_=getNutrition、m_38745_=getSaturationModifier（javap/常量池实证） */
    public static double foodSaturation(ItemStack stack, net.minecraft.world.entity.LivingEntity entity) {
        try {
            net.minecraft.world.food.FoodProperties food = stack.m_41720_().m_41473_();
            if (food == null) {
                return 0.0;
            }
            return food.m_38744_() * food.m_38745_() * 2.0;
        } catch (Exception e) {
            return 0.0;
        }
    }
}
