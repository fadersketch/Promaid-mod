package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.inventory.handler.BaubleItemHandler;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v1.5.142：饰品栏里的不死图腾也能触发复活。
 * v1.5.189：扩展——【共享不死图腾】主人致命伤时，女仆背包/饰品栏的不死图腾
 * 优先救主人（combat.totemShareEnable，默认开；特效同原版 levelEvent 35）。
 *
 * 原版 LivingEntity.m_21262_（checkTotemDeathProtection，SRG 名）只在【双手】里找
 * 不死图腾：生命 1 + 清除效果 + 再生/吸收/抗火 + 图腾粒子事件。自保机制会把图腾
 * 提前放进 TLM 饰品栏（BaubleItemHandler），这里补上"手里没有、饰品栏有"的判定，
 * 效果与原版逐条一致（字节码实证：m_21153_=setHealth、m_21219_=removeAllEffects、
 * MobEffects f_19605_=再生 / f_19617_=吸收 / f_19607_=抗火、m_7605_(Entity,byte)=
 * 图腾 levelEvent）。
 *
 * 手里已有图腾 → 直接放行让原版处理（避免双份消耗/双份效果）。
 */
@Mixin(LivingEntity.class)
public abstract class MaidBaubleTotemMixin {
    @Inject(method = "m_21262_", at = @At("HEAD"), cancellable = true)
    private void maidsmart$baubleTotem(DamageSource source, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof EntityMaid maid) {
            // 女仆自己：手里已有图腾 → 原版路径处理，不双份触发
            for (InteractionHand hand : InteractionHand.values()) {
                if (maid.m_21120_(hand).m_150930_(Items.f_42747_)) {
                    return;
                }
            }
            BaubleItemHandler bauble = maid.getMaidBauble();
            for (int i = 0; i < bauble.getSlots(); i++) {
                ItemStack stack = bauble.getStackInSlot(i);
                if (stack.m_41619_() || !stack.m_150930_(Items.f_42747_)) {
                    continue;
                }
                revive(maid, stack);
                // v1.5.277：图腾触发特效——原版同款 levelEvent 35（绿色粒子 +
                // 音效）。旧版女仆饰品栏触发只有复活和气泡、无特效（主人分支
                // 一直有播 35，女仆分支漏了——用户："女仆触发不死图腾没有明显特效"）
                maid.m_9236_().m_7605_(maid, (byte) 35);
                // v1.5.204：图腾必须真实消耗——旧版只 revive 不扣减 → 女仆无限
                // 复活（"不死图腾不消耗直接无敌"）。BaubleItemHandler 继承
                // ItemStackHandler，extractItem 直接扣存储
                consumeTotem(bauble, i);
                // v1.5.217：触发反馈——聊天气泡（主人身边可见）
                maid.getChatBubbleManager().addTextChatBubble("不死图腾救了我一命！");
                cir.setReturnValue(true);
                return;
            }
            // v1.5.286：背包里的图腾也触发（用户："一击秒杀时背包内不死图腾不会
            // 触发"）——旧版只认"手 + 饰品栏"：自保机制平时会把图腾提前放进饰品栏，
            // 但饰品栏满/未转移时背包里的图腾被白白浪费 → 秒杀直接死。致死伤害
            //（含秒杀）原版都会先走 checkTotemDeathProtection（m_21262_），这里补
            // 背包查找即全覆盖
            int invSlot = findTotemSlotInInv(maid);
            if (invSlot >= 0) {
                ItemStack invStack = maid.getMaidInv().getStackInSlot(invSlot);
                revive(maid, invStack);
                maid.m_9236_().m_7605_(maid, (byte) 35);
                consumeTotem(maid.getMaidInv(), invSlot);
                maid.getChatBubbleManager().addTextChatBubble("不死图腾救了我一命！");
                cir.setReturnValue(true);
            }
            // v1.1.0 实测六【女仆互相救】：她自己的图腾（手/饰品/背包）全用完仍是
            // 致命伤 → 找附近【同一主人的其他女仆】共享图腾救她（与救主人同机制：
            // 背包优先再饰品栏，真实消耗，特效+气泡）。找不到才真死。
            if (cir.getReturnValue() == null || !cir.getReturnValue()) {
                if (com.maidsmart.config.MaidSmartConfig.TOTEM_SHARE_ENABLE.get()
                        && maid.m_9236_() instanceof net.minecraft.server.level.ServerLevel sl) {
                    for (com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid m :
                            sl.m_45976_(com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid.class,
                                    maid.m_20191_().m_82400_(16.0))) {
                        if (m == maid || !m.m_6084_() || m.m_269323_() != maid.m_269323_()) {
                            continue; // 自己/死了/不是同主人的女仆都不算
                        }
                        int invSlot2 = findTotemSlotInInv(m);
                        int baubleSlot2 = -1;
                        ItemStack totem = invSlot2 >= 0
                                ? m.getMaidInv().getStackInSlot(invSlot2) : ItemStack.f_41583_;
                        if (totem.m_41619_()) {
                            baubleSlot2 = findTotemSlotInBauble(m);
                            totem = baubleSlot2 >= 0
                                    ? m.getMaidBauble().getStackInSlot(baubleSlot2) : ItemStack.f_41583_;
                        }
                        if (totem.m_41619_()) {
                            continue;
                        }
                        if (invSlot2 >= 0) {
                            consumeTotem(m.getMaidInv(), invSlot2);
                        } else {
                            consumeTotem(m.getMaidBauble(), baubleSlot2);
                        }
                        revive(maid, totem);
                        maid.m_9236_().m_7605_(maid, (byte) 35);
                        maid.getChatBubbleManager().addTextChatBubble("同伴的不死图腾救了我一命！");
                        m.getChatBubbleManager().addTextChatBubble("别倒下！我的不死图腾给你用！");
                        cir.setReturnValue(true);
                        return;
                    }
                }
            }
            return;
        }
        if (!(self instanceof net.minecraft.server.level.ServerPlayer owner)) {
            return;
        }
        // v1.5.189：主人自己手里有图腾 → 原版路径会触发图腾 → 原版处理（不抢）。
        for (InteractionHand hand : InteractionHand.values()) {
            if (owner.m_21120_(hand).m_150930_(Items.f_42747_)) {
                return;
            }
        }
        // v1.5.189：主人致命伤 → 女仆共享不死图腾（背包/饰品栏优先救主人）
        if (!com.maidsmart.config.MaidSmartConfig.TOTEM_SHARE_ENABLE.get()) {
            return;
        }
        if (!(owner.m_9236_() instanceof net.minecraft.server.level.ServerLevel sl)) {
            return;
        }
        for (com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid m :
                sl.m_45976_(com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid.class,
                        owner.m_20191_().m_82400_(32.0))) {
            if (!m.m_6084_() || m.m_269323_() != owner) {
                continue; // 必须是主人的女仆
            }
            // 背包优先（m_20158_ = getAllSlots 全部装备+背包），再饰品栏
            int invSlot = findTotemSlotInInv(m);
            int baubleSlot = -1;
            ItemStack totem = invSlot >= 0 ? m.getMaidInv().getStackInSlot(invSlot) : ItemStack.f_41583_;
            if (totem.m_41619_()) {
                baubleSlot = findTotemSlotInBauble(m);
                totem = baubleSlot >= 0 ? m.getMaidBauble().getStackInSlot(baubleSlot) : ItemStack.f_41583_;
            }
            if (totem.m_41619_()) {
                continue;
            }
            // v1.5.204：真实消耗——旧版对 getStackInSlot 返回的栈 m_41764_(1)：
            // 封装 handler 若返回副本则扣不掉 → 主人无限复活。extractItem 直接
            // 操作存储，必定扣减
            if (invSlot >= 0) {
                consumeTotem(m.getMaidInv(), invSlot);
            } else {
                consumeTotem(m.getMaidBauble(), baubleSlot);
            }
            revive(owner, totem);
            owner.m_9236_().m_7605_(owner, (byte) 35);
            // v1.5.217：共享图腾触发反馈——主人系统消息 + 女仆聊天气泡
            owner.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                    "\u00a7a[maid_smart] 女仆的不死图腾救了你一命！"));
            m.getChatBubbleManager().addTextChatBubble("主人挺住！我的不死图腾给你用！");
            cir.setReturnValue(true);
            return;
        }
    }

    /** v1.5.204：从槽位真实扣掉 1 个图腾（ItemStackHandler.extractItem 直接操作
     *  存储；返回空 = 提取被拒 → 对存储内实例 shrink 兜底再清空槽位）。
     *  v1.5.205：兜底用 m_41774_（= shrink）——旧代码用 m_41764_ 是 setCount(1)，
     *  count=1 的图腾永远扣不掉（"图腾不消耗"的另一半根因） */
    private static void consumeTotem(net.minecraftforge.items.IItemHandler handler, int slot) {
        try {
            if (!handler.extractItem(slot, 1, false).m_41619_()) {
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            ItemStack live = handler.getStackInSlot(slot);
            if (!live.m_41619_()) {
                live.m_41774_(1);
            }
            if (live.m_41619_() && handler instanceof net.minecraftforge.items.ItemStackHandler ish) {
                ish.setStackInSlot(slot, ItemStack.f_41583_);
            }
        } catch (Exception ignored) {
        }
    }

    /** 原版复活流程逐条对齐：生命 1 + 清效果 + 再生/吸收/抗火 */
    private static void revive(LivingEntity entity, ItemStack totem) {
        entity.m_21153_(1.0f); // setHealth
        entity.m_21219_(); // removeAllEffects
        entity.m_7292_(new MobEffectInstance(MobEffects.f_19605_, 900, 1)); // 再生 II 45 秒
        entity.m_7292_(new MobEffectInstance(MobEffects.f_19617_, 100, 1)); // 吸收 II 5 秒
        entity.m_7292_(new MobEffectInstance(MobEffects.f_19607_, 800, 0)); // 抗火 40 秒
    }

    /** v1.5.204：女仆背包（getMaidInv）里第一个不死图腾的槽位；无则 -1 */
    private static int findTotemSlotInInv(EntityMaid maid) {
        try {
            net.minecraftforge.items.IItemHandler inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (!stack.m_41619_() && stack.m_150930_(Items.f_42747_)) {
                    return i;
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** v1.5.204：女仆饰品栏（BaubleItemHandler）里第一个不死图腾的槽位；无则 -1 */
    private static int findTotemSlotInBauble(EntityMaid maid) {
        try {
            BaubleItemHandler bauble = maid.getMaidBauble();
            for (int i = 0; i < bauble.getSlots(); i++) {
                ItemStack stack = bauble.getStackInSlot(i);
                if (!stack.m_41619_() && stack.m_150930_(Items.f_42747_)) {
                    return i;
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }
}
