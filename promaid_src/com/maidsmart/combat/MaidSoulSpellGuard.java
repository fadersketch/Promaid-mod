package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 实测四百零二【低血量自动回魂符】——参考 maid_survival-1.9.5 的 MaidSoulSpellGuard。
 * 实测四百零三【触发口径收紧】：仅【致死伤害且无保命物品】时收符——低血量不再
 * 触发（否则自保的喝药/搭高/珍珠全成小丑）；有绀珠之药/不死图腾等保命物品时
 * 让保命物品生效，不抢收。
 *
 * 机制：
 * - 女仆受到【致死伤害】（伤害 ≥ 当前血量）且【没有保命物品】（绀珠之药/不死
 *   图腾，SelfPreservationBehavior.hasDeathSaveItem 同口径）→ 尝试收进主人背包
 *   的空魂符（TLM SMART_SLAB_EMPTY），成功则取消伤害；
 * - 触发条件：女仆存活/未移除/未骑乘 + 冷却未到 + 主人是同维度 ServerPlayer 且
 *   在半径内（默认 24 格）+ 主人背包（主手/副手/物品栏）有空魂符；
 * - 动作：新建 SMART_SLAB_HAS_MAID 魂符 → ItemSmartSlab.storeMaidData 存女仆数据 →
 *   魂符打自动标记（冷却时间戳 + 释放血量比）→ 放入主人背包空槽 → 主人收到提示 +
 *   再生药水效果（时长=冷却）+ 升级音效 → 女仆实体从世界移除（m_146870_ discard）；
 * - 冷却：成功收符后女仆 persistentData 写冷却时间戳（默认 180 秒），期间不再触发；
 * - 防收放循环：魂符右键释放（MaidAndItemTransformEvent.ToMaid）时把冷却写回女仆
 *   ForgeData，并清掉魂符上的自动标记——释放后冷却期内不会立刻又被收回去。
 *
 * 与自保的关系：自保是"活着保命"（喝药/搭高/珍珠），本功能是"保不住（要死了）
 * 才收符"的兜底——低血量交给自保，只有必死一击且没有保命物品才收。玩家手动用
 * 魂符收/放女仆不受影响（只认自动标记的魂符）。
 */
public final class MaidSoulSpellGuard {

    private static final String AUTO_SAVED_TAG = "maid_hp_low_protect_auto_saved";
    private static final String COOLDOWN_UNTIL_TAG = "maid_hp_low_protect_cooldown_until";
    private static final String RELEASE_HEALTH_RATIO_TAG = "maid_hp_low_protect_release_health_ratio";
    private static final String MAID_INFO_TAG = "MaidInfo";
    private static final String FORGE_DATA_TAG = "ForgeData";

    private static final net.minecraft.network.chat.Component SUCCESS_MESSAGE =
            net.minecraft.network.chat.Component.m_237113_("你的女仆生命值过低，已回到魂符中。");

    private MaidSoulSpellGuard() {
    }

    /** 致死伤害保护：伤害 ≥ 当前血量 且 无保命物品 → 尝试收魂符（成功则取消伤害） */
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onMaidDamage(net.minecraftforge.event.entity.living.LivingDamageEvent event) {
        if (!com.maidsmart.config.MaidSmartConfig.SOUL_SPELL_ENABLE.get()
                || !com.maidsmart.config.MaidSmartConfig.SOUL_SPELL_LETHAL_GUARD.get()) {
            return;
        }
        if (!(event.getEntity() instanceof EntityMaid maid)) {
            return;
        }
        if (event.getAmount() < maid.m_21223_()) {
            return; // 非致死
        }
        // 实测四百零三：有保命物品（绀珠之药/不死图腾）→ 让保命物品生效，不抢收
        if (SelfPreservationBehavior.hasDeathSaveItem(maid)) {
            return;
        }
        if (tryReturnToSoulSpell(maid)) {
            event.setCanceled(true);
            event.setAmount(0.0f);
        }
    }

    /** 魂符释放（ToMaid）→ 把冷却写回女仆数据 + 清自动标记，防收放循环。
     *  实测四百零四【冷却起算点修正】：旧版把魂符里存的 until（=收符时刻+180s）
     *  原样写回女仆——释放时剩余冷却 ≈ 175 秒，放下来作战第二次致死必然还在
     *  冷却内 → "第二次正常死亡不收符"的根因。改为【从释放时刻重新起算】：
     *  释放后给足冷却窗口防"放出即死→又收又放"抖振，过了窗口正常作战再死可再收。 */
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onSoulSpellToMaid(
            com.github.tartaricacid.touhoulittlemaid.api.event.MaidAndItemTransformEvent.ToMaid event) {
        if (event.getMaid().m_9236_().f_46443_) {
            return;
        }
        ItemStack item = event.getItem();
        net.minecraft.nbt.CompoundTag tag = item.m_41783_();
        if (tag == null || !tag.m_128471_(AUTO_SAVED_TAG)) {
            return; // 只处理本功能自动收的魂符
        }
        net.minecraft.nbt.CompoundTag data = event.getData();
        long until = event.getMaid().m_9236_().m_46467_()
                + com.maidsmart.config.MaidSmartConfig.SOUL_SPELL_COOLDOWN_SECONDS.get() * 20L;
        writeCooldownToMaidData(data, until);
        tag.m_128473_(AUTO_SAVED_TAG);
        tag.m_128473_(RELEASE_HEALTH_RATIO_TAG);
    }

    /** 核心：尝试把女仆收进主人背包的空魂符。成功返回 true。 */
    private static boolean tryReturnToSoulSpell(EntityMaid maid) {
        try {
            if (maid.m_9236_().f_46443_) {
                return false;
            }
            if (!(maid.m_9236_() instanceof ServerLevel level)) {
                return false;
            }
            if (!maid.m_6084_() || maid.m_213877_() || maid.m_20159_()) {
                return false; // 已死/已移除/骑乘中不收
            }
            if (isOnCooldown(maid, level)) {
                return false;
            }
            net.minecraft.world.entity.LivingEntity owner = maid.m_269323_();
            if (!(owner instanceof ServerPlayer player)) {
                return false; // 主人不在线/非玩家
            }
            if (player.m_9236_() != maid.m_9236_()) {
                return false; // 不同维度不收（魂符在主人背包，跨维放不进去）
            }
            double radius = com.maidsmart.config.MaidSmartConfig.SOUL_SPELL_OWNER_RADIUS.get();
            if (player.m_20280_(maid) > radius * radius) {
                return false; // 主人太远
            }
            SoulSpellSlot slot = findEmptySoulSpell(player);
            if (slot == null) {
                return false; // 主人背包没有空魂符
            }
            ItemStack slab = new ItemStack(
                    com.github.tartaricacid.touhoulittlemaid.init.InitItems.SMART_SLAB_HAS_MAID.get());
            com.github.tartaricacid.touhoulittlemaid.item.ItemSmartSlab.storeMaidData(slab, maid);
            long until = level.m_46467_()
                    + com.maidsmart.config.MaidSmartConfig.SOUL_SPELL_COOLDOWN_SECONDS.get() * 20L;
            markAutoSaved(slab, until);
            slot.set(player, slab);
            player.m_5661_(SUCCESS_MESSAGE, false);
            player.m_7292_(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.f_19590_,
                    com.maidsmart.config.MaidSmartConfig.SOUL_SPELL_COOLDOWN_SECONDS.get() * 20, 0));
            level.m_5594_(null, maid.m_20183_(), net.minecraft.sounds.SoundEvents.f_11778_,
                    net.minecraft.sounds.SoundSource.NEUTRAL, 0.65f, 1.0f);
            maid.getPersistentData().m_128356_(COOLDOWN_UNTIL_TAG, until);
            maid.m_146870_(); // 从世界移除（收进魂符）
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isOnCooldown(EntityMaid maid, ServerLevel level) {
        return maid.getPersistentData().m_128454_(COOLDOWN_UNTIL_TAG) > level.m_46467_();
    }

    /** 魂符打自动标记：冷却时间戳 + 释放血量比（对齐 maid_survival 结构） */
    private static void markAutoSaved(ItemStack slab, long until) {
        net.minecraft.nbt.CompoundTag tag = slab.m_41784_();
        tag.m_128379_(AUTO_SAVED_TAG, true);
        tag.m_128356_(COOLDOWN_UNTIL_TAG, until);
        tag.m_128350_(RELEASE_HEALTH_RATIO_TAG,
                (float) (double) com.maidsmart.config.MaidSmartConfig.SOUL_SPELL_RELEASE_RATIO.get());
        net.minecraft.nbt.CompoundTag maidInfo = slab.m_41698_(MAID_INFO_TAG);
        if (maidInfo != null) {
            writeCooldownToMaidData(maidInfo, until);
        }
    }

    /** 把冷却写进女仆数据（ForgeData 子标签——TLM 释放时读 persistentData 的路径） */
    private static void writeCooldownToMaidData(net.minecraft.nbt.CompoundTag data, long until) {
        net.minecraft.nbt.CompoundTag forge = data.m_128469_(FORGE_DATA_TAG);
        forge.m_128356_(COOLDOWN_UNTIL_TAG, until);
        data.m_128365_(FORGE_DATA_TAG, forge);
    }

    /** 找主人背包里的空魂符（主手 → 副手 → 物品栏） */
    private static SoulSpellSlot findEmptySoulSpell(ServerPlayer player) {
        net.minecraft.world.entity.player.Inventory inv = player.m_150109_();
        if (isEmptySoulSpell(player.m_21205_())) {
            return SoulSpellSlot.mainHand();
        }
        if (isEmptySoulSpell(player.m_21206_())) {
            return SoulSpellSlot.offhand();
        }
        for (int i = 0; i < inv.f_35974_.size(); i++) {
            if (isEmptySoulSpell(inv.f_35974_.get(i))) {
                return SoulSpellSlot.inventory(i);
            }
        }
        return null;
    }

    private static boolean isEmptySoulSpell(ItemStack stack) {
        return stack.m_150930_(com.github.tartaricacid.touhoulittlemaid.init.InitItems.SMART_SLAB_EMPTY.get());
    }

    /** 魂符槽位（主手/副手/物品栏下标） */
    private static final class SoulSpellSlot {
        private final int type; // 0=主手 1=副手 2=物品栏
        private final int index;

        private SoulSpellSlot(int type, int index) {
            this.type = type;
            this.index = index;
        }

        static SoulSpellSlot mainHand() {
            return new SoulSpellSlot(0, -1);
        }

        static SoulSpellSlot offhand() {
            return new SoulSpellSlot(1, -1);
        }

        static SoulSpellSlot inventory(int index) {
            return new SoulSpellSlot(2, index);
        }

        void set(ServerPlayer player, ItemStack stack) {
            net.minecraft.world.entity.player.Inventory inv = player.m_150109_();
            if (this.type == 0) {
                inv.f_35974_.set(0, stack); // 主手 = items[0]
            } else if (this.type == 1) {
                inv.f_35975_.set(0, stack); // 副手 = offhand[0]
            } else {
                inv.f_35974_.set(this.index, stack);
            }
        }
    }
}
