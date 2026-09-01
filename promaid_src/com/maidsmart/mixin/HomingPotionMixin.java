package com.maidsmart.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.1.0 实测二百四十六（用户："药水的飞行逻辑过于鬼畜了。改为和玩家一样向目标方向
 * 以抛物线方式抛出一个药水，并强制在半秒之后消失并强制给予玩家效果（不管先前是否命中。
 * 因为有一个小bug，当离得太近的时候，女仆有可能会扔歪，导致药水提前消失）"）：
 * 纯抛物线 + 半秒强制生效。
 * 旧版（二百四十四）保留每 tick 追踪修正——目标移动时药水来回甩动"鬼畜"；近距时
 * 追踪贴脸逻辑还可能把药水提前触发。新版：
 * ① 不再修正方向——投掷初速（m_6686_，玩家同款 0.5 速度）就是抛物线，重力自然下落；
 * ② 出生时记录 tick（maid_smart_born），存活超过 10 tick（半秒）→ 强制给目标施加
 *    药水效果（PotionUtils.getMobEffects 逐个 addEffect，拷贝实例防共享），然后
 *    discard 药水——不管飞没飞到、扔没扔歪，效果必达；
 * ③ 目标消失/死亡 → 直接 discard（不飞了，也不浪费效果）。
 * 半秒内自然命中（onHit）→ 原版已施加效果并移除实体，tick 不再运行，不会重复。
 */
@Mixin(net.minecraft.world.entity.projectile.ThrowableProjectile.class)
public abstract class HomingPotionMixin {

    private static final String HOMING_TAG = "maid_smart_homing";
    private static final String BORN_TAG = "maid_smart_born";
    /** 飞行时间上限（tick）：半秒 = 10 tick */
    private static final long TIMEOUT_TICKS = 10L;

    @Inject(method = "m_8119_", at = @At("HEAD"))
    private void maidsmart$homingTick(CallbackInfo ci) {
        try {
            if (!((Object) this instanceof ThrownPotion)) {
                return; // 只处理药水（雪球/鸡蛋/珍珠等不干预）
            }
            ThrownPotion self = (ThrownPotion) (Object) this;
            if (self.m_9236_().m_5776_()) {
                return; // 只服务端处理（客户端实体是渲染副本）
            }
            CompoundTag pd = self.getPersistentData();
            if (!pd.m_128425_(HOMING_TAG, 8)) { // contains TAG_STRING
                return;
            }
            String targetUuid = pd.m_128461_(HOMING_TAG);
            Entity target;
            try {
                target = ((net.minecraft.server.level.ServerLevel) self.m_9236_())
                        .m_8791_(java.util.UUID.fromString(targetUuid));
            } catch (IllegalArgumentException e) {
                self.m_146870_(); // UUID 损坏 → 清除
                return;
            }
            if (target == null || !target.m_6084_()) {
                self.m_146870_(); // 目标没了 → 直接清除（不飞了）
                return;
            }
            // 出生 tick 记录（首次 tick 只记录，不判超时）
            long born = 0L;
            if (pd.m_128425_(BORN_TAG, 8)) {
                try {
                    born = Long.parseLong(pd.m_128461_(BORN_TAG));
                } catch (NumberFormatException ignored) {
                }
            }
            if (born == 0L) {
                pd.m_128359_(BORN_TAG, String.valueOf(self.m_9236_().m_46467_()));
                return;
            }
            // 半秒飞行上限：超时 → 强制给目标施加药水效果，然后清除药水
            //（不管飞没飞到、扔没扔歪——近距扔歪也不会提前消失，效果必达）
            if (self.m_9236_().m_46467_() - born >= TIMEOUT_TICKS) {
                ItemStack stack = self.m_7846_(); // getItem
                if (!stack.m_41619_() && target instanceof LivingEntity living) {
                    for (net.minecraft.world.effect.MobEffectInstance e :
                            net.minecraft.world.item.alchemy.PotionUtils.m_43571_(stack)) {
                        living.m_7292_(new net.minecraft.world.effect.MobEffectInstance(e));
                    }
                }
                self.m_146870_();
            }
            // 半秒内：纯抛物线飞行，不干预（v1.1.0 实测二百四十六：不再追踪修正）
        } catch (Exception ignored) {
        }
    }
}
