package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.3.0(beta) 实测七百二十一【冰火传说的龙：女仆落座"两个漏斗都要拦"】。
 *
 * <h2>玩家原话（720 之后）</h2>
 * 「现在女仆大概可以骑上龙了，但是会在背上反复横跳，甚至直接陷到地里面窒息。而且龙的建模会被
 * 直接卡掉。」——位置还是不稳，而且会把人卡进方块。
 *
 * <h2>720 的漏在哪（反编译实证）</h2>
 * 720 只拦了**一参**漏斗（1.20.1 {@code Entity.m_7332_} / 1.21.1 {@code positionRider(Entity)}，
 * 两者都是 {@code final}）。但原版里还有**第二个**入口直接调**两参**的那个：
 * <pre>
 *   Entity.m_276804_()  (= teleportPassengers):
 *       this.m_20199_().forEach(p -> { for (Entity e : p.f_19823_) p.m_19956_(e, Entity::m_6027_); });
 * </pre>
 * 也就是说**每一次传送**（{@code m_6021_} / {@code m_264318_}，含本模组"连人带坐骑送回主人身边"
 * 那条链路）都会绕过一参漏斗、直接进龙覆写的两参 {@code m_19956_}：女仆的
 * {@code getControllingPassenger()} 为 null ⇒ 龙把她当**嘴里的猎物**摆到嘴边
 * （{@code updatePreyInMouth}），并在 55 刻后咬她一口 + {@code m_8127_} 把她甩下来——
 * 这就是"反复横跳 / 被甩"里从传送那条路来的那一半。
 *
 * <h2>这一版的修法</h2>
 * 两个漏斗**都**拦：一参（原版 final 漏斗，每 tick 走这里）与两参（传送那条路）。
 * 两参那一档用原版给的 {@code MoveFunction} 落座（传送时它是 {@code Entity::m_6027_} =
 * moveTo，正好保留传送语义），再 cancel。
 *
 * <h2>顺带：不许把她卡进方块</h2>
 * 鞍位由龙自己算（{@code getRiderPosition()}），但龙在**密闭空间**里（洞里 / 天花板很低的大厅）
 * 时那个点可能在方块里——女仆没有把方块挤开的体格，落在方块里就是每 tick 扣 {@code in_wall}
 * 窒息伤害（实机日志实证：绑定后立刻 {@code 类型=inWall 位置=(…,-32,…)}，随后自保把她
 * teleport 出去）。所以落座前过一道 {@code MaidMountCompat.freeSeatY}：从鞍位往上找第一格空气，
 * 最多抬 6 格（找不到就用原位，绝不因此不落座）。
 *
 * <h2>注入点为什么还是原版 {@code Entity}</h2>
 * 与 720 同一条理由（可选模组的目标类不在编译 classpath 上、审计会把 "解析不到" 记 UNRES）。
 * 详见 {@link com.maidsmart.combat.MaidMountCompat#shouldSeatMaidOnDragon} 的说明。
 *
 * <h2>名字口径</h2>
 * 手工编译、无 refmap，{@code method} 按运行时名逐字匹配：本文件是 1.20.1 树（SRG 名）。
 * 1.21.1 树见 promaid_src_neo 的同名文件（官方名，两参那个也叫 {@code positionRider}）。
 */
@Mixin(Entity.class)
public abstract class EntityDragonMaidSeatMixin {

    /**
     * 每 tick 的那条漏斗（{@code m_7332_} = {@code positionRider(Entity)} 的 SRG 名，{@code final}）。
     *
     * <p>鞍位不重复实现：反射调龙自己的 {@code getRiderPosition()}；拿不到就**不 cancel**
     * （退回原版——宁可不生效，也不把她摆到错的地方）。
     */
    @Inject(method = "m_7332_(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
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
            // 与龙自己给玩家算的一模一样：鞍位 + 乘客身高（见 EntityDragonBase.m_19956_ 的玩家分支），
            // 但先过一道 freeSeatY：鞍位在方块里时往上抬到第一格空气，别把她按进墙里窒息。
            double y = com.maidsmart.combat.MaidMountCompat.freeSeatY(passenger,
                    seat.f_82479_, seat.f_82480_ + (double) passenger.m_20206_(), seat.f_82481_);
            passenger.m_6034_(seat.f_82479_, y, seat.f_82481_);
            ci.cancel();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【实测七百二十一】传送那条路直接调**两参**漏斗（{@code Entity.m_276804_}，反编译实证），
     * 720 没拦它 → 女仆被当成猎物摆到嘴边 / 55 刻后被咬被甩。这里补上同一档。
     *
     * <p>落座走原版给的回调 {@code fn}（传送时是 {@code Entity::m_6027_} = moveTo），保留传送语义。
     */
    @Inject(method = "m_19956_(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity$MoveFunction;)V",
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
                    seat.f_82479_, seat.f_82480_ + (double) passenger.m_20206_(), seat.f_82481_);
            fn.m_20372_(passenger, seat.f_82479_, y, seat.f_82481_);
            ci.cancel();
        } catch (Throwable ignored) {
        }
    }
}
