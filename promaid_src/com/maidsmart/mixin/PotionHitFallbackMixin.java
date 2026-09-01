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
 * v1.1.0 实测二百四十八（用户："当我就在女仆附近的时候，女仆就一个劲的往地上扔。
 * 玩家也没吃到效果"）：落地兜底——药水命中（含落地碎裂）时，若 persistentData 带
 * homing 标签，强制给目标施加药水效果（原版落地溅射只覆盖 4 格内实体，目标可能
 * 不在溅射范围 → "扔了没效果"）。施加后移除标签防重复；原版 onHit 照常执行
 * （实体命中原版已施加，重复施加同效果只刷新时长，无害）。
 */
@Mixin(net.minecraft.world.entity.projectile.ThrownPotion.class)
public abstract class PotionHitFallbackMixin {

    private static final String HOMING_TAG = "maid_smart_homing";

    @Inject(method = "m_6532_", at = @At("HEAD"))
    private void maidsmart$hitFallback(net.minecraft.world.phys.HitResult result, CallbackInfo ci) {
        try {
            ThrownPotion self = (ThrownPotion) (Object) this;
            if (self.m_9236_().m_5776_()) {
                return; // 只服务端处理
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
                return;
            }
            if (target == null || !target.m_6084_()) {
                return;
            }
            // 命中（含落地）→ 强制给目标施加效果（原版溅射可能没覆盖到目标）
            ItemStack stack = self.m_7846_(); // getItem
            if (!stack.m_41619_() && target instanceof LivingEntity living) {
                for (net.minecraft.world.effect.MobEffectInstance e :
                        net.minecraft.world.item.alchemy.PotionUtils.m_43571_(stack)) {
                    living.m_7292_(new net.minecraft.world.effect.MobEffectInstance(e));
                }
            }
            pd.m_128473_(HOMING_TAG); // 防重复施加
        } catch (Exception ignored) {
        }
    }
}
