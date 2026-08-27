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
    /** v1.5.307：「缺吃的」播报限频（按女仆记，60 秒一次；服务端单线程访问） */
    private static final java.util.Map<java.util.UUID, Long> NO_FOOD_ANNOUNCE = new java.util.HashMap<>();

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
        // v1.5.288：蜂蜜瓶恢复为投喂食物（用户："蜂蜜瓶竟然不作为投喂食物"）——
        // 饱和 14.4 排在中等优先级：主人饿了会投喂、中毒时由负面解除分支优先
        // 直接喂（解中毒+饱食）。女仆自己背包的蜂蜜仍走 drinkHoneyForPoison
        //（女仆用 vs 主人投喂是两条独立链路，不冲突）
        addFood("minecraft:honey_bottle");     // 6 / 1.2（直接喂食时额外解中毒）
    }

    private static void addFood(String id) {
        // v1.5.284：getValue 判空——物品不存在时跳过，防 new ItemStack(null) 类加载即崩
        net.minecraft.world.item.Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(id));
        if (item == null) {
            return;
        }
        ItemStack stack = new ItemStack(item);
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

    /** 主人饥饿时直接喂上一个熟食（v1.5.201：按"饱和度恢复量"选最优——
     *  nutrition × saturationModifier × 2.0，不再按背包槽位顺序；
     *  v1.5.288：改为【直接喂食】——饱食度直接加到主人（原版 FoodData.eat 语义），
     *  不再塞背包/手上（用户："投喂食物应该跟本来就有的喂食功能一样是直接喂给
     *  主人饱食度，而不是塞在手上"）；
     *  v1.5.299：硬编码 15 改读配置阈值（旧版写死 15——主人把投喂阈值调到 20 时
     *  饱食度 15~19 直接被拦，用户："明明饱食度不满、阈值设了 20 却无法触发"）；
     *  手持食物（主手/副手）纳入选优——旧版只扫 maidInv 背包，肉排拿在手上时
     *  永远找不到（TLM 双手是独立 handsInvWrapper，getMaidInv 不含手部） */
    public static boolean giveFoodToOwner(EntityMaid maid, ServerPlayer owner) {
        // v1.5.290：诊断日志——"饿了不喂"定位用（latest.log 搜 "aid feed"）
        org.slf4j.Logger log = com.mojang.logging.LogUtils.getLogger();
        int foodLevel = owner.m_36324_().m_38702_();
        if (foodLevel >= com.maidsmart.config.MaidSmartConfig.AID_FOOD_THRESHOLD.get()) {
            return false;
        }
        IItemHandler maidInv = maid.getMaidInv();
        int bestSlot = -1;
        double bestSat = -1.0;
        // v1.5.299：手持食物参与选优（h=0 主手 m_21205_，h=1 副手 m_21206_；
        // 手部用 handSlot=-2/-3 表示（副手 -3 与"无手持"哨兵 -1 区分），选优后从手上 shrink）
        int handSlot = -1;
        ItemStack handItem = null;
        double handSat = -1.0;
        for (int h = 0; h < 2; h++) {
            ItemStack hs = h == 0 ? maid.m_21205_() : maid.m_21206_();
            if (hs.m_41619_()) {
                continue;
            }
            boolean isFood = false;
            for (ItemStack food : FOODS) {
                if (food.m_41720_() == hs.m_41720_()) {
                    isFood = true;
                    break;
                }
            }
            if (!isFood) {
                continue;
            }
            double sat = foodSaturation(hs, owner);
            if (sat > handSat) {
                handSat = sat;
                // v1.1.0 实测一百二十六：副手哨兵 -3（旧版 -1 与"无手持食物"哨兵同值
                // → 只有副手食物时被判成"没有"，误报"我背包里没有吃的了"）；handItem
                // 存【快照】而非活引用（同 tick 隐藏槽/换手会清空活引用 → 喂空气）
                handSlot = h == 0 ? -2 : -3;
                handItem = hs.m_41777_();
            }
        }
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
        if (bestSlot < 0 && handSlot == -1) {
            // v1.5.307：缺吃的播报（手册承诺的功能，此前从未实现——用户："没有食物
            // 的时候会播报缺吃的，但实际并不会播报"）+ 日志限频（旧版每 tick 刷屏：
            // 日志实证 08:03:44 起每 50ms 一条"背包与双手都无投喂食物"）
            long now = System.currentTimeMillis();
            Long last = NO_FOOD_ANNOUNCE.get(maid.m_20148_());
            if (last == null || now - last >= 60_000L) {
                NO_FOOD_ANNOUNCE.put(maid.m_20148_(), now);
                String maidName = maid.m_5446_() != null ? maid.m_5446_().getString() : "女仆";
                log.info("aid feed: maid={} owner={} foodLevel={} FOODS={} 背包与双手都无投喂食物（已播报，60 秒限频）",
                        maid.m_5446_() != null ? maid.m_5446_().getString() : maid.m_20148_(),
                        owner.m_5446_() != null ? owner.m_5446_().getString() : "?",
                        foodLevel, FOODS.size());
                maid.getChatBubbleManager().addTextChatBubble("主人，我背包里没有吃的了，给我备点食物吧～");
                owner.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                        "\u00a7e[maid_smart] " + maidName + "：我背包里没有吃的了，给我备点食物吧～"));
            }
            return false;
        }
        ItemStack toGive;
        if (handSlot != -1 && handSat >= bestSat) {
            // 手持食物最优（同饱食度优先用手上，不翻背包）——v1.1.0 实测一百二十六：
            // handItem 是扫描时刻的【快照】；消耗前核对当前手上仍是同种食物才 shrink
            //（防同 tick 手被隐藏槽/换手系统清空或替换）
            toGive = handItem;
            if (toGive.m_41619_()) {
                return false;
            }
            ItemStack liveHand = handSlot == -2 ? maid.m_21205_() : maid.m_21206_();
            if (liveHand.m_41619_() || !liveHand.m_150930_(toGive.m_41720_())) {
                return false;
            }
            liveHand.m_41774_(1);
        } else {
            toGive = maidInv.extractItem(bestSlot, 1, false);
        }
        if (!feedFoodDirect(maid, owner, toGive)) {
            ItemHandlerHelper.insertItemStacked(maidInv, toGive, false);
            return false;
        }
        log.info("aid feed: maid={} owner={} foodLevel={} 阈值={} 喂了 {}",
                maid.m_5446_() != null ? maid.m_5446_().getString() : maid.m_20148_(),
                owner.m_5446_() != null ? owner.m_5446_().getString() : "?",
                foodLevel, com.maidsmart.config.MaidSmartConfig.AID_FOOD_THRESHOLD.get(),
                toGive.m_41786_().getString());
        return true;
    }

    /** v1.5.288：直接喂食（原版喂食语义）——饱食度直接加到主人（FoodData.eat），
     *  特殊效果照原版食物属性生效：蜂蜜瓶额外解中毒 + 返还玻璃瓶；牛奶（无食物
     *  属性）由 MaidAidOwnerBehavior 负面解除分支单独处理（清全部效果 + 空桶）。
     *  喂食失败（无食物属性）返回 false，调用方退回物品。 */
    public static boolean feedFoodDirect(EntityMaid maid, ServerPlayer owner, ItemStack food) {
        try {
            net.minecraft.world.food.FoodProperties fp = food.m_41720_().m_41473_();
            if (fp == null) {
                return false;
            }
            // v1.5.292：喂食动作——与投药水/金苹果同款摆臂动画（m_6674_=swing，
            // 服务端调用自动广播给客户端显示），自动投喂/治疗食物/蜂蜜全走这里
            maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
            owner.m_36324_().m_38707_(fp.m_38744_(), fp.m_38745_()); // eat(nutrition, satMod)
            // v1.5.290：喂食音效（蜂蜜=喝、其他=吃）+ 系统提示喂了什么（m_41786_ = getHoverName）
            String foodName = food.m_41786_().getString();
            String sndId = food.m_41720_() == net.minecraft.world.item.Items.f_42787_
                    ? "minecraft:entity.generic.drink" : "minecraft:entity.generic.eat";
            net.minecraft.sounds.SoundEvent snd = net.minecraftforge.registries.ForgeRegistries.SOUND_EVENTS
                    .getValue(net.minecraft.resources.ResourceLocation.parse(sndId));
            if (snd != null) {
                owner.m_9236_().m_5594_(null, owner.m_20183_(), snd,
                        net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.0f);
            }
            owner.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                    "\u00a7a[maid_smart] 女仆喂你吃了 " + foodName));
            if (food.m_41720_() == net.minecraft.world.item.Items.f_42787_) { // honey_bottle
                owner.m_21195_(net.minecraft.world.effect.MobEffects.f_19614_); // 解中毒
                net.minecraft.world.item.Item bottle = net.minecraftforge.registries.ForgeRegistries.ITEMS
                        .getValue(net.minecraft.resources.ResourceLocation.parse("minecraft:glass_bottle"));
                if (bottle != null) {
                    ItemHandlerHelper.insertItemStacked(maid.getMaidInv(), new ItemStack(bottle), false);
                }
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
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
