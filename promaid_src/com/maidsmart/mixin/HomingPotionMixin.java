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
 * v1.5.231b：投掷药水【追踪弹】——女仆投给主人的治疗/再生/抗火药水（persistentData
 * 打了 "maid_smart_homing" = 目标 UUID 的）每 tick 修正速度方向锁定目标飞行，
 * 直至击中（vanilla 的 tick 会在移动前扫描路径上的实体 → 修正后必中）。
 * 实测旧版抛物线投掷命中率极低（"喷溅治疗药水基本没喷中过"）——抛物线受
 * 主人移动/弹道物理影响，干脆追踪。
 * v1.5.239：mixin 目标从 ThrownPotion 改为 ThrowableProjectile——ThrownPotion
 * 未声明 m_8119_（tick 继承自父类），Sponge @Inject 只匹配目标类自己声明的方法，
 * 直接注入 ThrownPotion 会 "could not find any targets matching" 启动崩溃；
 * ThrowableProjectile 声明了 m_8119_，handler 内用 instanceof 过滤只追踪药水。
 */
@Mixin(net.minecraft.world.entity.projectile.ThrowableProjectile.class)
public abstract class HomingPotionMixin {

    private static final String HOMING_TAG = "maid_smart_homing";

    @Inject(method = "m_8119_", at = @At("HEAD"))
    private void maidsmart$homingTick(CallbackInfo ci) {
        try {
            if (!((Object) this instanceof ThrownPotion)) {
                return; // 只追踪药水（雪球/鸡蛋/珍珠等不处理）
            }
            ThrownPotion self = (ThrownPotion) (Object) this;
            // 只服务端修正（客户端实体是渲染副本，改了也没用）
            if (self.m_9236_().m_5776_()) {
                return;
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
                pd.m_128473_(HOMING_TAG); // 目标没了 → 恢复普通抛物线
                return;
            }
            // 每 tick 修正：速度方向 = 目标当前位置 - 投掷物位置，速度恒定
            Vec3 dir = target.m_20182_().m_82546_(self.m_20182_()); // subtract
            double len = dir.m_82553_(); // length
            if (len < 0.01) {
                return;
            }
            self.m_20256_(dir.m_82490_(1.6 / len)); // scale：速度 1.6/格每 tick
            // 距离很近 → 直接把投掷物挪到目标身边，保证本 tick 溅射命中
            if (len < 1.2) {
                self.m_6034_(target.m_20185_(), target.m_20227_(0.3), target.m_20189_());
            }
        } catch (Exception ignored) {
        }
    }
}
