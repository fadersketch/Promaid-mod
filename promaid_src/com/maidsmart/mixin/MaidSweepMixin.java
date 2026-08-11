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
 *    单体伤害（用户要求，玩家移动攻击也不触发横扫）。
 * 注：canSweep 仍要求主手剑（ToolActions.SWORD_SWEEP）——斧/三叉戟不横扫，与玩家一致。
 */
@Mixin(EntityMaid.class)
public abstract class MaidSweepMixin {

    @Redirect(method = "doSweepHurt",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/item/enchantment/EnchantmentHelper;m_44821_(Lnet/minecraft/world/entity/LivingEntity;)F"))
    private float maidsmart$sweepAlways(LivingEntity entity) {
        // 无横扫之刃附魔 → 0（原版不扫）→ 强制给 0.5（横扫之刃 I 倍率），保证每次平A横扫
        return Math.max(0.5f, EnchantmentHelper.m_44821_(entity));
    }

    /**
     * 跳劈不横扫——空中攻击（跳跃/下落中）保持单体暴击（用户要求，玩家移动攻击
     * 也不触发横扫）。v1.5.174：落地后的"禁横扫平A"窗口已删除（用户：平A没意义
     * ——跳劈空档由 TLM 正常攻击穿插横扫补伤害，不再有单独的平A状态）。
     */
    @Inject(method = "doSweepHurt", at = @At("HEAD"), cancellable = true)
    private void maidsmart$noSweepInAir(Entity target, CallbackInfo ci) {
        // m_20096_ = onGround（v1.5.137 实证 m_20162_ 是 isSneaking 不可用）
        if (!((EntityMaid) (Object) this).m_20096_()) {
            ci.cancel();
        }
    }
}
