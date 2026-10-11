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
 * 平A横扫修复（"每次平A都触发横扫之刃，和玩家同款"）。
 *
 * 背景（反编译实证）：TLM 的 {@code EntityMaid.doHurtTarget} 在攻击成功后调私有 {@code doSweepHurt}——
 * 横扫公式与玩家完全一致（伤害 = 1 + ratio×攻击力），但【触发条件要求 ratio > 0】——无"横扫之刃"附魔时
 * ratio=0 → 从不横扫！这就是"女仆迂回跟原版差距大"的根因。
 *
 * 修复：① {@code @Redirect} 横扫倍率读取——无附魔时返回 0.5（横扫之刃 I 的 50% 攻击力倍率），每次平A都横扫
 * （有附魔按附魔倍率更高）；② {@code @Inject} {@code doSweepHurt} HEAD——空中（跳劈）不横扫，保持单体暴击。
 * 注：{@code canSweep} 仍要求主手剑（{@code ItemAbilities.SWORD_SWEEP}）——斧/三叉戟不横扫，与玩家一致。
 * 【1.21.1 移植】横扫倍率来源从 {@code EnchantmentHelper.getSweepingDamageRatio} 改为属性
 * {@code Attributes.SWEEPING_DAMAGE_RATIO}（{@code EnchantmentHelper} 在 1.21.1 已无该方法）；旧 SRG 目标
 * {@code m_44821_} 在 1.21.1 找不到 → Redirector 必注入失败 → 启动崩溃。
 */
@Mixin(EntityMaid.class)
public abstract class MaidSweepMixin {

    /** doSweepHurt 内唯一一次 AttributeMap.getValue 调用就是读横扫倍率（javap 实证） */
    @Redirect(method = "doSweepHurt",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/attributes/AttributeMap;getValue(Lnet/minecraft/core/Holder;)D"))
    private double maidsmart$sweepAlways(AttributeMap attributes, Holder<Attribute> attribute) {
        if (!com.maidsmart.tool.MaidScope.owned((EntityMaid) (Object) this)) {
            return attributes.getValue(attribute); // v1.2.2 实测六百：无主女仆走原版横扫倍率
        }
        if (!com.maidsmart.config.MaidSmartConfig.COMBAT_SWEEP_ALWAYS.get()) {
            // 实测八百四十：本处原来没有配置项、只能硬问 isDiy；现在是真开关（默认开）。
            // 关掉 = 回 TLM 原版；DIY 模式由预设把这条开关置 false，所以 DIY 下同样不介入。
            return attributes.getValue(attribute);
        }
        double value = attributes.getValue(attribute);
        if (attribute == Attributes.SWEEPING_DAMAGE_RATIO) {
            // 无横扫之刃附魔 → 0（原版不扫）→ 强制给 0.5（横扫之刃 I 倍率），保证每次平A横扫
            return Math.max(0.5, value);
        }
        return value;
    }

    /**
     * 跳劈不横扫——空中攻击（跳跃/下落中）保持单体暴击（玩家移动攻击也不触发横扫）。判据：仍在空中时，若她
     * ① 是真乘客（原版坐骑 / 卓越前线载具 / 扫帚 / 船 / 矿车），或 ② 挂在冰火传说龙的**悬空鞍位**上
     * （{@link com.maidsmart.combat.RideBindManager#isDragonChairRider}——龙那条她不是乘客），一律**不取消**、
     * 照常横扫（坐骑每拍在动、她的 Y 跟着漂，{@code onGround()} 会长假 false，旧判据把她骑乘时的每一次横扫都
     * 掐掉了）；只有"自己跳起来（跳劈）/从坎上掉下来"这种真·空中才取消。安全：TLM 的 {@code doSweepHurt} 对每个受害者还要过 canAttack / canAttackType。
     */
    @Inject(method = "doSweepHurt", at = @At("HEAD"), cancellable = true)
    private void maidsmart$noSweepInAir(Entity target, CallbackInfo ci) {
        EntityMaid self = (EntityMaid) (Object) this;
        if (!com.maidsmart.tool.MaidScope.owned(self)) {
            return; // v1.2.2 实测六百：无主女仆不干预
        }
        if (!com.maidsmart.config.MaidSmartConfig.COMBAT_SWEEP_ALWAYS.get()) {
            return; // 实测八百四十：整条"平A横扫"关掉时，这一档也回原版（空中照扫）；DIY 同此
        }
        if (self.onGround()) {
            return; // 在地面 → 照常横扫
        }
        // ① 骑乘中（真乘客）——她不"跳劈"，只是被坐骑抬离了地面
        if (self.isPassenger()) {
            return;
        }
        // ② 冰火传说龙的悬空鞍位（她不是乘客，走的是 723 的降级方案）
        try {
            if (com.maidsmart.combat.RideBindManager.isDragonChairRider(self)) {
                return;
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSweepMixin", ignored);
        }
        ci.cancel(); // 真·空中（跳劈/坠落）→ 保持原口径
    }
}
