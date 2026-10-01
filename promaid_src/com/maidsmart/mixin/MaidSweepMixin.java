package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.5.141：平A横扫修复（"每次平A都触发横扫之刃，和玩家同款"）。
 *
 * 背景（反编译实证）：TLM 的 EntityMaid.m_7327_（doHurtTarget）在攻击成功后调用
 * 私有 doSweepHurt——横扫公式与玩家完全一致（伤害 = 1 + ratio×攻击力，
 * 横扫之刃 ratio = 1 - 1/(等级+1)），但【触发条件要求 ratio > 0】——
 * 无"横扫之刃"附魔时 ratio=0 → 从不横扫！这就是"女仆迂回跟原版差距大"的根因。
 *
 * 修复：
 * 1. @Redirect EnchantmentHelper.m_44821_（getSweepingDamageRatio）——无附魔时
 *    返回 0.5（横扫之刃 I 的 50% 攻击力倍率）：每次平A都横扫（有附魔按附魔倍率更高）；
 * 2. @Inject doSweepHurt HEAD——空中（跳跃/下落中 = 跳劈）不横扫：跳劈暴击保持
 *    单体伤害（要求，玩家移动攻击也不触发横扫）。
 * 注：canSweep 仍要求主手剑（ToolActions.SWORD_SWEEP）——斧/三叉戟不横扫，与玩家一致。
 */
@Mixin(EntityMaid.class)
public abstract class MaidSweepMixin {

    @Redirect(method = "doSweepHurt",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/item/enchantment/EnchantmentHelper;m_44821_(Lnet/minecraft/world/entity/LivingEntity;)F"))
    private float maidsmart$sweepAlways(LivingEntity entity) {
        if (!com.maidsmart.tool.MaidScope.owned(entity)) {
            return EnchantmentHelper.m_44821_(entity); // v1.2.2 实测六百：无主女仆走原版横扫公式
        }
        // 无横扫之刃附魔 → 0（原版不扫）→ 强制给 0.5（横扫之刃 I 倍率），保证每次平A横扫
        return Math.max(0.5f, EnchantmentHelper.m_44821_(entity));
    }

    /**
     * 跳劈不横扫——空中攻击（跳跃/下落中）保持单体暴击（要求，玩家移动攻击
     * 也不触发横扫）。v1.5.174：落地后的"禁横扫平A"窗口已删除（反馈：平A没意义
     * ——跳劈空档由 TLM 正常攻击穿插横扫补伤害，不再有单独的平A状态）。
     *
     * <p>【v1.3.0(beta) 实测七百五十九·点1：骑乘时也不取消（玩家反馈"骑在坐骑上
     * 没办法触发横扫之刃"）】。
     *
     * <p><b>根因</b>：这一档原来是"只看 {@code !onGround()} 就取消"，而**坐在任何坐骑上
     * 她都被抬离地面**（坐骑每拍在动、她的 Y 跟着坐骑漂），于是 {@code onGround()} 长期为
     * false —— 她骑上坐骑之后**每一次平A的横扫都被这一句掐掉**，看起来就是"骑乘时永远
     * 不触发横扫之刃"。玩家自己骑着坐骑挥剑是照常横扫的（原版对骑乘没有任何这一档），
     * 所以这条判据本身就是错的。
     *
     * <p><b>修法（判据收窄到"真的只有跳劈那一种空中"）</b>：仍在空中时，若她在
     * ① 是真乘客（{@code m_20159_}：原版坐骑 / 卓越前线载具 / 扫帚 / 船 / 矿车），或
     * ② 挂在冰火传说龙的**悬空鞍位**上（{@link com.maidsmart.combat.RideBindManager#isDragonChairRider}
     * ——龙那条她**不是乘客**，见 723 降级方案），一律**不取消**，照常横扫。
     * 只有"她自己跳起来（跳劈）/从坎上掉下来"这种真·空中才保持取消，与 v1.5.174 的口径一字不差。
     *
     * <p>安全性：TLM 的 {@code doSweepHurt} 伤害循环里对**每个**受害者都还要过
     * {@code m_6779_}（canAttack）与 {@code m_6549_}（canAttackType），所以放松这一档
     * 不可能打到友军/坐骑本身（坐骑是 {@code m_7307_} isAlliedTo 与 canAttack 双重挡住的）。
     */
    @Inject(method = "doSweepHurt", at = @At("HEAD"), cancellable = true)
    private void maidsmart$noSweepInAir(Entity target, CallbackInfo ci) {
        EntityMaid self = (EntityMaid) (Object) this;
        if (!com.maidsmart.tool.MaidScope.owned(self)) {
            return; // v1.2.2 实测六百：无主女仆不干预
        }
        // m_20096_ = onGround（v1.5.137 实证 m_20162_ 是 isSneaking 不可用）
        if (self.m_20096_()) {
            return; // 在地面 → 照常横扫
        }
        // ① 骑乘中（真乘客）——她不"跳劈"，只是被坐骑抬离了地面
        if (self.m_20159_()) {
            return;
        }
        // ② 冰火传说龙的悬空鞍位（她不是乘客，走的是 723 的降级方案）
        try {
            if (com.maidsmart.combat.RideBindManager.isDragonChairRider(self)) {
                return;
            }
        } catch (Throwable ignored) {
        }
        ci.cancel(); // 真·空中（跳劈/坠落）→ 保持原口径
    }
}
