package com.maidsmart.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.1.0 实测二百五十五（用户："动画偏移效果还是有点严重……那么药水动画的最终落点
 * 就给到时刻追踪目标位置"）：动画药水【落点时刻追踪】——女仆投药水的动画抛掷
 * （persistentData 带 "maid_smart_anim_target" = 目标 UUID）每 tick 用当前实际位置
 * 重算抛物线瞄准目标当前位置（速度 1.0，弹道公式解仰角）——既有弧线又跟目标移动，
 * 落点与目标几乎重合，消除偏移违和感。
 * 注意：这是【纯动画】追踪——动画药水不施加效果（效果由触发逻辑直接施加），
 * 落地时原版溅射的 addEffect 只是视觉合并，无害。
 * 距离 < 0.6 格（目标脚下）视为落点已到——保持当前速度自然落地（碎裂动画完整）。
 */
@Mixin(net.minecraft.world.entity.projectile.ThrowableProjectile.class)
public abstract class PotionAnimTrackMixin {

    private static final String ANIM_TARGET = "maid_smart_anim_target";
    private static final String ANIM_BORN = "maid_smart_anim_born";
    /** 追踪时长上限（tick）：v1.1.0 实测二百五十七（用户："滞留型药水怎么突然又没有了
     *  效果？药水云怎么没了？而且投掷动画也没了"）——旧版无上限每 tick 重算瞄准目标
     *  当前位置：目标移动时动画药水永远追着飞（距离恒 ≥0.6）永不落地 → 看不到落地
     *  碎裂动画、也看不到落地生成的药水云（动画药水是实际滞留药水复制，落地本应生成
     *  云）。加 1 秒上限：超过停止追踪、保持当前速度自然落地（碎裂/云动画完整）。
     *  v1.1.0 实测二百六十（用户要求半秒）：上限收窄为 10 tick。
     *  v1.1.0 实测二百六十四（用户："效果给到之后药水动画立刻落地吧，要不然太奇怪了"）：
     *  效果是触发即生效（瞬间给到），动画药水却还要飞半秒才落地——观感脱节。上限收窄
     *  为 1 tick：出生后立即停止追踪，配合 throwPotionAnimate 的 4.0 高速，动画药水
     *  2~3 tick 内到达目标落地碎裂，与效果施加几乎同步。 */
    private static final long TRACK_LIMIT_TICKS = 1L;

    @Inject(method = "m_8119_", at = @At("HEAD"))
    private void maidsmart$animTrackTick(CallbackInfo ci) {
        try {
            if (!((Object) this instanceof ThrownPotion)) {
                return;
            }
            ThrownPotion self = (ThrownPotion) (Object) this;
            if (self.m_9236_().m_5776_()) {
                return; // 只服务端处理
            }
            CompoundTag pd = self.getPersistentData();
            if (!pd.m_128425_(ANIM_TARGET, 8)) {
                return;
            }
            // 出生 tick 记录（首次 tick 只记录）
            long born = 0L;
            if (pd.m_128425_(ANIM_BORN, 8)) {
                try {
                    born = Long.parseLong(pd.m_128461_(ANIM_BORN));
                } catch (NumberFormatException ignored) {
                }
            }
            if (born == 0L) {
                pd.m_128359_(ANIM_BORN, String.valueOf(self.m_9236_().m_46467_()));
                return;
            }
            // 追踪超时 → 停止追踪，保持当前速度自然落地（碎裂/云动画完整）
            if (self.m_9236_().m_46467_() - born >= TRACK_LIMIT_TICKS) {
                pd.m_128473_(ANIM_TARGET);
                return;
            }
            String targetUuid = pd.m_128461_(ANIM_TARGET);
            Entity target;
            try {
                target = ((net.minecraft.server.level.ServerLevel) self.m_9236_())
                        .m_8791_(java.util.UUID.fromString(targetUuid));
            } catch (IllegalArgumentException e) {
                return;
            }
            if (target == null || !target.m_6084_()) {
                return; // 目标没了 → 保持当前速度自然落地
            }
            double dx = target.m_20185_() - self.m_20185_();
            double dz = target.m_20189_() - self.m_20189_();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 0.6) {
                return; // 已到目标脚下 → 自然落地（碎裂动画完整）
            }
            // 弹道公式解仰角（速度 1.0、重力 0.05 方块/tick²）——每 tick 重算，
            // 瞄准目标当前位置
            double v = 1.0;
            double g = 0.05;
            double h = target.m_20186_() - self.m_20186_();
            double A = 2 * v * v / (g * len);
            double B = 2 * v * v * h / (g * len * len) + 1;
            double disc = A * A - 4 * B;
            double u;
            if (disc >= 0) {
                u = (A - Math.sqrt(disc)) / 2;
                if (u < 0) {
                    u = (A + Math.sqrt(disc)) / 2;
                }
            } else {
                u = 0.25;
            }
            double vh = v / Math.sqrt(1 + u * u);
            double vy = v * u / Math.sqrt(1 + u * u);
            self.m_20256_(new Vec3(dx / len * vh, vy, dz / len * vh));
        } catch (Exception ignored) {
        }
    }
}
