package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.3.0(beta) 实测七百二十【冰火传说的龙：女仆按玩家同款鞍位落座】（1.21.1 版）。
 *
 * <p>完整根因、为什么注入原版 {@code Entity} 的漏斗、以及"客户端靠同步的主人 UUID 判定"
 * 这套口径，全在 1.20.1 树同名类的类注释里（逐条对应）。本树只把 SRG 名换成官方名：
 * 漏斗从 1.20.1 树那个 SRG 名换成**一参的** {@code positionRider(Entity)}（一样是 {@code final}，
 * 龙覆写的是两参的那个 {@code positionRider(Entity, MoveFunction)}）。
 *
 * <p>描述符必须写全：1.21.1 的 {@code positionRider} 有两个重载（1 参 / 2 参），只写名字会歧义。
 *
 * <p>【实测七百二十】1.21.1 社区版的龙叫 {@code DragonBaseEntity}（1.20.1 是
 * {@code EntityDragonBase}），但 {@code getRiderPosition()} / {@code getDragonStage()} 等成员名
 * 四份 jar 逐字相同——所以 {@link com.maidsmart.combat.MaidMountCompat} 那条反射链两树共用。
 */
@Mixin(Entity.class)
public abstract class EntityDragonMaidSeatMixin {

    @Inject(method = "positionRider(Lnet/minecraft/world/entity/Entity;)V",
            at = @At("HEAD"), cancellable = true)
    private void maidsmart$seatMaidOnDragon(Entity passenger, CallbackInfo ci) {
        try {
            Entity vehicle = (Entity) (Object) this;
            if (!com.maidsmart.combat.MaidMountCompat.shouldSeatMaidOnDragon(vehicle, passenger)) {
                return; // 不是"有主女仆 + 冰火传说龙" → 原版一字不动
            }
            net.minecraft.world.phys.Vec3 seat = com.maidsmart.combat.MaidMountCompat.riderSeat(vehicle);
            if (seat == null) {
                return; // 鞍位拿不到 → 退回原版
            }
            passenger.setPos(seat.x, seat.y + (double) passenger.getBbHeight(), seat.z);
            ci.cancel();
        } catch (Throwable ignored) {
        }
    }
}
