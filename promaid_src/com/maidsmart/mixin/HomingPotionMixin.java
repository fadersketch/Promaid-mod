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
 * v1.1.0 实测二百四十三（用户："药水追踪逻辑做的并不好。会导致药水乱飞。我觉得最好是
 * 抛物线方式飞向玩家/女仆，一秒后不管有没有投中，那么药水都被清除掉，然后强制给予目标
 * 效果"）：追踪弹 → 抛物线 + 超时兜底。
 * 旧版每 tick 修正速度方向锁定目标（HomingPotionMixin），目标移动时药水来回甩动乱飞。
 * 新版：
 * ① 不再修正方向——投掷初速（m_6686_）就是抛物线，重力自然下落，观感正常；
 * ② 出生时记录 tick（maid_smart_born，字符串存防 SRG 歧义），每 tick 检查：存活超过
 *    20 tick（1 秒）→ 强制给目标施加药水效果（PotionUtils.getMobEffects 逐个
 *    addEffect，拷贝实例防共享），然后 discard 药水——不管飞没飞到，效果必达；
 * ③ 目标消失/死亡 → 直接 discard（不飞了，也不浪费效果）。
 * 1 秒内自然命中（onHit）→ 原版已施加效果并移除实体，tick 不再运行，不会重复。
 */
@Mixin(net.minecraft.world.entity.projectile.ThrowableProjectile.class)
public abstract class HomingPotionMixin {

    private static final String HOMING_TAG = "maid_smart_homing";
    private static final String BORN_TAG = "maid_smart_born";
    /** 超时（tick）：1 秒 = 20 tick */
    private static final long TIMEOUT_TICKS = 20L;

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
            long born = 0L;
            if (pd.m_128425_(BORN_TAG, 8)) {
                try {
                    born = Long.parseLong(pd.m_128461_(BORN_TAG));
                } catch (NumberFormatException ignored) {
                }
            }
            if (born == 0L) {
                pd.m_128359_(BORN_TAG, String.valueOf(self.m_9236_().m_46467_()));
                return; // 出生 tick 记录完成，本 tick 不判超时
            }
            if (self.m_9236_().m_46467_() - born < TIMEOUT_TICKS) {
                return; // 1 秒内：纯抛物线飞行，不干预
            }
            // 超时：强制给目标施加药水效果，然后清除药水
            ItemStack stack = self.m_7846_(); // getItem
            if (!stack.m_41619_() && target instanceof LivingEntity living) {
                for (net.minecraft.world.effect.MobEffectInstance e :
                        net.minecraft.world.item.alchemy.PotionUtils.m_43571_(stack)) {
                    living.m_7292_(new net.minecraft.world.effect.MobEffectInstance(e));
                }
            }
            self.m_146870_();
        } catch (Exception ignored) {
        }
    }
}
