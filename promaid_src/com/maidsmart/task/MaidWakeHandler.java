package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 【R1109】叫醒睡着的女仆：手持本模组任意物品右击睡着的她 → 叫醒 + 一句抱怨气泡（走既有的
 * 气泡/TTS 通道）+ 60 秒内不再找床睡。魂符收回时（ToItem）清掉这条冷却——收放魂符不带着
 * "不想睡"的状态。
 *
 * <p>60 秒的断路由两处共同执行：TLM 自带的 {@code MaidBedTask}（女仆床）由
 * {@code MaidBedWakeBlockMixin} 拦，本模组的 {@code MaidBedInteropBehavior}（原版床）
 * 在 {@code checkExtraStartConditions} 里查同一份表。
 *
 * <p>【R1110】判定放宽到与"原版右击叫醒村民"同口径：不再要求准星精确点在睡着女仆那具
 * 缩小后的碰撞箱上——右击物品时按视线做一次带 1 格膨胀的实体射线，右击方块（例如她睡的
 * 那张床）时按方块周围 1.5 格找睡着的她。都是"鼠标大致对着她/她睡的床"就能叫醒。
 */
public class MaidWakeHandler {

    /** 女仆 UUID → 解禁 gameTime（60 秒 = 1200 tick） */
    private static final java.util.Map<java.util.UUID, Long> BED_BLOCK_UNTIL = new java.util.HashMap<>();
    private static final long BED_BLOCK_TICKS = 1200L;

    /** 抱怨台词：走 addTextChatBubble → 既有 TTS/语音包链路 */
    public static final String COMPLAINT = "唔…主人，人家还想再睡一会儿嘛～";

    public static boolean isBedBlocked(EntityMaid maid) {
        try {
            if (maid == null) {
                return false;
            }
            Long until = BED_BLOCK_UNTIL.get(maid.m_20148_());
            if (until == null) {
                return false;
            }
            if (maid.m_9236_().m_46467_() >= until) {
                BED_BLOCK_UNTIL.remove(maid.m_20148_());
                return false;
            }
            return true;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidWakeHandler", ignored);
        }
        return false;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof EntityMaid maid)
                || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (maid.m_9236_().f_46443_ || !maid.m_21224_()) {
            return;
        }
        ItemStack stack = player.m_21120_(event.getHand());
        if (!isOurItem(stack)) {
            return;
        }
        if (!enabled()) {
            return;
        }
        event.setCanceled(true);
        player.m_6674_(event.getHand());
        wake(maid, player);
    }

    /** 【R1110】右击物品（准星没精确落在她身上）：视线带膨胀找睡着的女仆，命中就叫醒并吃掉这次使用 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onUseItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !isOurItem(player.m_21120_(event.getHand()))) {
            return;
        }
        if (!enabled()) {
            return;
        }
        EntityMaid maid = pickSleeping(player);
        if (maid != null) {
            event.setCanceled(true);
            player.m_6674_(event.getHand());
            wake(maid, player);
        }
    }

    /** 【R1110】右击方块（常见是她睡的那张床）：方块周围 1.5 格内有睡着的她就算叫醒 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onUseBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !isOurItem(player.m_21120_(event.getHand()))) {
            return;
        }
        if (!enabled()) {
            return;
        }
        EntityMaid maid = sleepingNear(player, event.getPos());
        if (maid != null) {
            event.setCanceled(true);
            player.m_6674_(event.getHand());
            wake(maid, player);
        }
    }

    /** 视线射线 + 1 格膨胀：与原版右击村民同一类判定（大体对着她就够），不穿墙 */
    private static EntityMaid pickSleeping(ServerPlayer player) {
        try {
            Vec3 eye = player.m_20299_(1.0F);
            Vec3 end = eye.m_82549_(player.m_20252_(1.0F).m_82490_(6.0));
            BlockHitResult block = player.m_9236_().m_45547_(new ClipContext(eye, end,
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            double wall = block.m_6662_() == HitResult.Type.MISS
                    ? 6.0 : eye.m_82554_(block.m_82450_());
            EntityHitResult hit = ProjectileUtil.m_37304_(player.m_9236_(), player,
                    eye, end, new AABB(eye, end).m_82400_(1.0),
                    e -> e instanceof EntityMaid m && m.m_21224_() && m.m_6084_());
            if (hit == null || eye.m_82554_(hit.m_82450_()) > wall + 0.5) {
                return null;
            }
            return (EntityMaid) hit.m_82443_();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidWakeHandler", ignored);
        }
        return null;
    }

    /** 被右击块（多半是床）周围 1.5 格内最近的睡着的女仆 */
    private static EntityMaid sleepingNear(ServerPlayer player, BlockPos pos) {
        try {
            EntityMaid best = null;
            double bestSq = 2.25;
            for (EntityMaid m : player.m_9236_().m_45976_(EntityMaid.class,
                    new AABB(pos).m_82400_(1.5))) {
                if (!m.m_21224_() || !m.m_6084_()) {
                    continue;
                }
                double sq = m.m_20275_(pos.m_123341_() + 0.5, pos.m_123342_() + 0.5, pos.m_123343_() + 0.5);
                if (sq <= bestSq) {
                    bestSq = sq;
                    best = m;
                }
            }
            return best;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidWakeHandler", ignored);
        }
        return null;
    }

    /** 【R1146】右击叫醒总开关（默认开；关 = 右键完全不接管） */
    private static boolean enabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.MISC_RIGHT_CLICK_WAKE.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidWakeHandler", ignored);
            return true;
        }
    }

    /** 【R1109】本模组物品 = 注册命名空间 maid_smart（武装拴绳/指标石/蓝图书……全部算） */
    private static boolean isOurItem(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return false;
        }
        net.minecraft.resources.ResourceLocation key =
                net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.m_41720_());
        return key != null && "maid_smart".equals(key.m_135827_());
    }

    private static void wake(EntityMaid maid, ServerPlayer player) {
        try {
            if (BED_BLOCK_UNTIL.size() > 256) {
                long now = maid.m_9236_().m_46467_();
                BED_BLOCK_UNTIL.entrySet().removeIf(e -> e.getValue() <= now);
            }
            maid.m_21253_(); // stopSleeping
            ((net.minecraft.world.entity.ai.Brain) maid.m_6274_())
                    .m_21936_(net.minecraft.world.entity.ai.memory.MemoryModuleType.f_26370_); // 别接着往床边走
            BED_BLOCK_UNTIL.put(maid.m_20148_(), maid.m_9236_().m_46467_() + BED_BLOCK_TICKS);
            // 【R1149】叫醒语音直接发给叫醒者：不依赖气泡 5 秒限频、不依赖 owner 在线。
            com.maidsmart.voice.SystemTTSManager.speakTo(maid, player, COMPLAINT);
            maid.getChatBubbleManager().addTextChatBubble(COMPLAINT);
            com.maidsmart.tool.PromaidLog.log("叫醒", "玩家=" + player.m_7755_().getString()
                    + " 叫醒了女仆=" + com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + "（60 秒内不再找床）");
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidWakeHandler", ignored);
        }
    }

    /** 魂符收回 → 冷却立刻作废（放出来不该还带着"不想睡"） */
    @SubscribeEvent
    public void onSoulSpellToItem(
            com.github.tartaricacid.touhoulittlemaid.api.event.MaidAndItemTransformEvent.ToItem event) {
        try {
            EntityMaid maid = event.getMaid();
            if (maid == null || maid.m_9236_().f_46443_) {
                return;
            }
            ItemStack item = event.getItem();
            boolean soulSlab = item != null && item.m_150930_(
                    com.github.tartaricacid.touhoulittlemaid.init.InitItems.SMART_SLAB_HAS_MAID.get());
            if (soulSlab) {
                BED_BLOCK_UNTIL.remove(maid.m_20148_());
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidWakeHandler", ignored);
        }
    }
}
