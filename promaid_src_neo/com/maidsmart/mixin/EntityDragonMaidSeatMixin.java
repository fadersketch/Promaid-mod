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

    /**
     * 【实测七百二十二】坐骑自己把我摆错了 —— 每一拍在她自己 tick 的**末尾**再抢回鞍位。
     *
     * <p><b>为什么前两针还不够</b>：冰火传说的龙**覆写了**两参 {@code positionRider}，而覆写体是
     * 「先调 {@code super}（= 前两个注入点），**返回之后**再判一次乘客身份」——女仆不是它的
     * "控制乘客"（它只认主人）→ 走 {@code updatePreyInMouth(她)}：把她按到**嘴边/脚下**、
     * 把龙自己的动画切成 SHAKEPREY（撕咬前的甩动）→ 每拍甩来甩去（玩家看到的"反复横跳"）、
     * 落在方块里（窒息）、55 刻后**咬她一口（伤害×2）+ 把她甩下鞍**（实机日志：13:12:20 骑上、
     * 13:12:20 窒息、13:12:31 死亡+自动复活），而 SHAKEPREY 还会把整个模型带偏
     * （玩家报的"龙的建模只剩一块"）。{@code ci.cancel()} 只能取消**基类那一份**，
     * 取消不了覆写体在 {@code super} 返回之后写的东西。
     *
     * <p><b>所以这一针挂在「乘客自己的 rideTick 末尾」</b>：原版顺序是
     * {@code rideTick() { tick(); … vehicle.positionRider(this); }}——末尾这一刻，
     * 坐骑（= 龙）所有写位置的动作都已经做完，我们最后落笔，谁也没法再把她挪走。
     * 顺带把那条"猎物甩动"动画清掉（{@code MaidMountCompat.enforceDragonSeat} 内做），
     * 模型也就跟着回到正常姿态。
     */
    @Inject(method = "rideTick()V", at = @At("TAIL"))
    private void maidsmart$reSeatAfterVehicleMovedMe(CallbackInfo ci) {
        try {
            Entity self = (Entity) (Object) this;
            if (!(self instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid)) {
                return;
            }
            Entity vehicle = maid.getVehicle();
            if (!com.maidsmart.combat.MaidMountCompat.shouldSeatMaidOnDragon(vehicle, maid)) {
                return;
            }
            com.maidsmart.combat.MaidMountCompat.enforceDragonSeat(vehicle, maid);
        } catch (Throwable ignored) {
        }
    }
}
