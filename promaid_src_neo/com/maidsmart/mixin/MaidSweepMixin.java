package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.5.141：平A横扫修复（"每次平A都触发横扫之刃，和玩家同款"）。
 *
 * 背景（反编译实证）：TLM 的 EntityMaid.doHurtTarget（doHurtTarget）在攻击成功后调用
 * 私有 doSweepHurt——横扫公式与玩家完全一致（伤害 = 1 + ratio×攻击力），
 * 但【触发条件要求 ratio > 0】——无"横扫之刃"附魔时 ratio=0 → 从不横扫！
 * 这就是"女仆迂回跟原版差距大"的根因。
 *
 * 修复：
 * 1. @Redirect 横扫倍率读取——无附魔时返回 0.5（横扫之刃 I 的 50% 攻击力倍率）：
 *    每次平A都横扫（有附魔按附魔倍率更高）；
 * 2. @Inject doSweepHurt HEAD——空中（跳跃/下落中 = 跳劈）不横扫：跳劈暴击保持
 *    单体伤害（用户要求，玩家移动攻击也不触发横扫）。
 * 注：canSweep 仍要求主手剑（ItemAbilities.SWORD_SWEEP）——斧/三叉戟不横扫，与玩家一致。
 *
 * 【1.21.1 移植修正】横扫倍率的来源从 EnchantmentHelper.getSweepingDamageRatio(LivingEntity)F
 * 改为属性 Attributes.SWEEPING_DAMAGE_RATIO（原版 Player.attack 与 TLM 1.21.1 的
 * doSweepHurt 都走 getAttributes().getValue(Attributes.SWEEPING_DAMAGE_RATIO)，javap 实证；
 * EnchantmentHelper 在 1.21.1 已无该方法）。旧 SRG 目标 m_44821_ 在 1.21.1 找不到 →
 * Redirector 必注入失败 → 启动崩溃（NeoForge 专用服实测实证）。
 */
@Mixin(EntityMaid.class)
public abstract class MaidSweepMixin {

    /** doSweepHurt 内唯一一次 AttributeMap.getValue 调用就是读横扫倍率（javap 实证） */
    @Redirect(method = "doSweepHurt",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/attributes/AttributeMap;getValue(Lnet/minecraft/core/Holder;)D"))
    private double maidsmart$sweepAlways(AttributeMap attributes, Holder<Attribute> attribute) {
        double value = attributes.getValue(attribute);
        if (attribute == Attributes.SWEEPING_DAMAGE_RATIO) {
            // 无横扫之刃附魔 → 0（原版不扫）→ 强制给 0.5（横扫之刃 I 倍率），保证每次平A横扫
            return Math.max(0.5, value);
        }
        return value;
    }

    /**
     * 跳劈不横扫——空中攻击（跳跃/下落中）保持单体暴击（用户要求，玩家移动攻击
     * 也不触发横扫）。v1.5.174：落地后的"禁横扫平A"窗口已删除（用户：平A没意义
     * ——跳劈空档由 TLM 正常攻击穿插横扫补伤害，不再有单独的平A状态）。
     */
    @Inject(method = "doSweepHurt", at = @At("HEAD"), cancellable = true)
    private void maidsmart$noSweepInAir(Entity target, CallbackInfo ci) {
        if (!((EntityMaid) (Object) this).onGround()) {
            ci.cancel();
        }
    }
}
