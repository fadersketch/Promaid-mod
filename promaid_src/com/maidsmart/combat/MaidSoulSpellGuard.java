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
    /** 实测四百三十三：魂符上记录的女仆名——HUD 在女仆还在符里时也能显示"回魂符 · 名字" */
    private static final String AUTO_SAVED_NAME_TAG = "maid_hp_low_protect_auto_name";
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
        // 实测四百三十九 双保险：若 TLM 在事件之后才把 data 应用到实体，直接写实体也能生效
        event.getMaid().getPersistentData().m_128356_(COOLDOWN_UNTIL_TAG, until);
        tag.m_128473_(AUTO_SAVED_TAG);
        tag.m_128473_(RELEASE_HEALTH_RATIO_TAG);
    }

    /** 实测四百四十一【收符期间冷却条消失修复】：任何"女仆 → 魂符"转换都把女仆当前
     *  仍在计时的回魂符冷却与名字盖到魂符物品上。
     *
     *  旧版的魂符冷却标记只由自动收符路径写入；玩家【手动】用空魂符收女仆时，魂符是
     *  TLM 自己 new 的、不带任何标记 → HUD 扫背包查不到 → 冷却条消失（而冷却其实还
     *  记在女仆数据里继续计时，用户："虽然实际上还在计时，但那样子观感不太好"）。
     *  TLM 的 storeMaidData 会发 MaidAndItemTransformEvent.ToItem（反编译实证），在事件
     *  里补标记即可同时覆盖手动/自动两条收符路径；女仆没在冷却时不标记。 */
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onSoulSpellToItem(
            com.github.tartaricacid.touhoulittlemaid.api.event.MaidAndItemTransformEvent.ToItem event) {
        try {
            EntityMaid maid = event.getMaid();
            if (maid == null || maid.m_9236_().f_46443_) {
                return;
            }
            long until = maid.getPersistentData().m_128454_(COOLDOWN_UNTIL_TAG);
            if (until <= maid.m_9236_().m_46467_()) {
                return; // 女仆没在冷却 → 不标记
            }
            ItemStack item = event.getItem();
            // ToItem 也被相机/胶卷等"存女仆物品"共用——只认魂符，避免在别的物品上留标记
            if (!item.m_150930_(com.github.tartaricacid.touhoulittlemaid.init.InitItems.SMART_SLAB_HAS_MAID.get())) {
                return;
            }
            net.minecraft.nbt.CompoundTag tag = item.m_41784_();
            tag.m_128356_(COOLDOWN_UNTIL_TAG, until);
            tag.m_128359_(AUTO_SAVED_NAME_TAG, com.maidsmart.tool.PromaidLog.nameOf(maid));
        } catch (Throwable ignored) {
        }
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
            long until = level.m_46467_()
                    + com.maidsmart.config.MaidSmartConfig.SOUL_SPELL_COOLDOWN_SECONDS.get() * 20L;
            // 实测四百四十一：先把冷却写进女仆数据再 storeMaidData——这样 ToItem 钩子
            // 与魂符里的女仆 NBT 都拿得到本次冷却，收符期间 HUD 才有条目可显示
            maid.getPersistentData().m_128356_(COOLDOWN_UNTIL_TAG, until);
            ItemStack slab = new ItemStack(
                    com.github.tartaricacid.touhoulittlemaid.init.InitItems.SMART_SLAB_HAS_MAID.get());
            com.github.tartaricacid.touhoulittlemaid.item.ItemSmartSlab.storeMaidData(slab, maid);
            markAutoSaved(slab, until);
            // 实测四百三十三：把女仆名写进魂符本身——HUD 在"女仆还在符里"的冷却窗口也能显示
            slab.m_41784_().m_128359_(AUTO_SAVED_NAME_TAG, com.maidsmart.tool.PromaidLog.nameOf(maid));
            slot.set(player, slab);
            // v1.1.0 实测四百一十二：提示语带上当前 CD 值——玩家不知道冷却机制的
            // 常反馈"第二次就死了不收"，文案点明（0 = 无冷却，措辞区分）
            int cdSec = com.maidsmart.config.MaidSmartConfig.SOUL_SPELL_COOLDOWN_SECONDS.get();
            player.m_5661_(cdSec > 0
                    ? net.minecraft.network.chat.Component.m_237113_(
                    "你的女仆生命值过低，已回到魂符中。（存在CD，CD为 " + cdSec + " 秒）")
                    : SUCCESS_MESSAGE, false);
            player.m_7292_(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.f_19590_,
                    com.maidsmart.config.MaidSmartConfig.SOUL_SPELL_COOLDOWN_SECONDS.get() * 20, 0));
            level.m_5594_(null, maid.m_20183_(), net.minecraft.sounds.SoundEvents.f_11778_,
                    net.minecraft.sounds.SoundSource.NEUTRAL, 0.65f, 1.0f);
            maid.m_146870_(); // 从世界移除（收进魂符）
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isOnCooldown(EntityMaid maid, ServerLevel level) {
        return maid.getPersistentData().m_128454_(COOLDOWN_UNTIL_TAG) > level.m_46467_();
    }

    // ================== HUD 查询（实测四百二十一） ==================

    /**
     * 实测四百二十一【冷却可视化】：HUD 用——该女仆的回魂符冷却是否未到。
     * 返回 {剩余秒, 总秒}；未在冷却返回 null。
     *
     * 冷却写入口径与本类一致：收符时写进女仆 persistentData（m_128356_），
     * 释放时（ToMaid）从释放刻重新起算后经 TLM load 回写到 persistentData——
     * 所以只需扫存活女仆的 persistentData 即可覆盖"放出后处于冷却窗口"这一
     * 玩家最需要看到的状态（在符里时本就安全，不显示）。
     */
    public static long[] hudCooldownSeconds(EntityMaid maid, long nowTick) {
        if (maid == null || !com.maidsmart.config.MaidSmartConfig.MISC_COOLDOWN_HUD.get()) {
            return null;
        }
        long until = maid.getPersistentData().m_128454_(COOLDOWN_UNTIL_TAG);
        if (until <= nowTick) {
            return null;
        }
        long totalSec = Math.max(1L,
                com.maidsmart.config.MaidSmartConfig.SOUL_SPELL_COOLDOWN_SECONDS.get());
        long remainSec = (until - nowTick + 19L) / 20L;
        if (remainSec > totalSec) {
            remainSec = totalSec; // 重启后 tickCount 归零的极端情形：显示不超过配置值
        }
        return new long[]{remainSec, totalSec};
    }

    // ================== HUD 查询 · 手上的魂符（实测四百三十三） ==================

    /**
     * HUD 用：这枚【自动魂符】自身的冷却到期 tick（0 = 不是自动魂符 / 无冷却）。
     * 覆盖"女仆已被收进符里、实体不存在"这段窗口——旧实现只扫存活女仆的
     * persistentData，收符瞬间就没有任何条目可显示。
     *
     * 实测四百四十一：不再要求 AUTO_SAVED_TAG——手动收符的魂符也由 ToItem 钩子盖了
     * 冷却戳，只要带 COOLDOWN_UNTIL_TAG 就该显示。
     */
    public static long charmCooldownUntil(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return 0L;
        }
        net.minecraft.nbt.CompoundTag tag = stack.m_41783_();
        if (tag == null || !tag.m_128471_(COOLDOWN_UNTIL_TAG)) {
            return 0L;
        }
        return tag.m_128454_(COOLDOWN_UNTIL_TAG);
    }

    /** HUD 用：魂符上记录的女仆名（没有则 null） */
    public static String charmMaidName(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return null;
        }
        net.minecraft.nbt.CompoundTag tag = stack.m_41783_();
        if (tag == null || !tag.m_128471_(AUTO_SAVED_NAME_TAG)) {
            return null;
        }
        String s = tag.m_128461_(AUTO_SAVED_NAME_TAG);
        return s == null || s.isBlank() ? null : s;
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
            return SoulSpellSlot.mainHand(inv.f_35977_);
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

        static SoulSpellSlot mainHand(int selected) {
            return new SoulSpellSlot(0, selected);
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
                // 实测四百四十一：主手 = items[selected]——旧版恒写 items[0]，玩家选中的
                // 不是 0 号槽时会把 0 号槽的东西直接顶掉（物品丢失）而手里的空符还在
                inv.f_35974_.set(this.index, stack);
            } else if (this.type == 1) {
                inv.f_35976_.set(0, stack); // 副手 = offhand[0]（旧版误写 armor 顶掉靴子）
            } else {
                inv.f_35974_.set(this.index, stack);
            }
        }
    }
}
