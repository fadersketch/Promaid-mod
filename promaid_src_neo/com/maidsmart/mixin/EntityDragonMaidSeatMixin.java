package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.3.0(beta) 实测七百二十一【冰火传说的龙：女仆落座"两个漏斗都要拦"】（1.21.1 版）。
 *
 * <p>完整根因、为什么两个漏斗、为什么落座前要过 {@code freeSeatY}、以及"客户端靠同步的
 * 主人 UUID 判定"这套口径，全在 1.20.1 树同名类的类注释里（逐条对应）。本树只把 SRG 名换成
 * 官方名：一参漏斗 {@code positionRider(Entity)}、两参漏斗 {@code positionRider(Entity,
 * MoveFunction)}（原版 1.21.1 的 {@code teleportPassengers} 直接调两参那个，反编译实证）。
 *
 * <p>描述符必须写全：1.21.1 的 {@code positionRider} 有两个重载（1 参 / 2 参），只写名字会歧义。
 *
 * <p>【实测七百二十】1.21.1 社区版的龙叫 {@code DragonBaseEntity}（1.20.1 是
 * {@code EntityDragonBase}），但 {@code getRiderPosition()} / {@code getDragonStage()} 等成员名
 * 四份 jar 逐字相同——所以 {@link com.maidsmart.combat.MaidMountCompat} 那条反射链两树共用。
 */
@Mixin(Entity.class)
public abstract class EntityDragonMaidSeatMixin {

    /** 每 tick 的那条漏斗（一参，{@code final}）。 */
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
            double y = com.maidsmart.combat.MaidMountCompat.freeSeatY(passenger,
                    seat.x, seat.y + (double) passenger.getBbHeight(), seat.z);
            passenger.setPos(seat.x, y, seat.z);
            ci.cancel();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【实测七百二十一】传送那条路直接调**两参**漏斗（{@code Entity.teleportPassengers}，
     * 反编译实证），720 没拦它 → 女仆被当猎物摆到嘴边 / 55 刻后被咬被甩。补上同一档。
     */
    @Inject(method = "positionRider(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity$MoveFunction;)V",
            at = @At("HEAD"), cancellable = true)
    private void maidsmart$seatMaidOnDragonArg(Entity passenger, Entity.MoveFunction fn, CallbackInfo ci) {
        try {
            Entity vehicle = (Entity) (Object) this;
            if (!com.maidsmart.combat.MaidMountCompat.shouldSeatMaidOnDragon(vehicle, passenger)) {
                return;
            }
            net.minecraft.world.phys.Vec3 seat = com.maidsmart.combat.MaidMountCompat.riderSeat(vehicle);
            if (seat == null) {
                return;
            }
            double y = com.maidsmart.combat.MaidMountCompat.freeSeatY(passenger,
                    seat.x, seat.y + (double) passenger.getBbHeight(), seat.z);
            fn.accept(passenger, seat.x, y, seat.z);
            ci.cancel();
        } catch (Throwable ignored) {
        }
    }
}
