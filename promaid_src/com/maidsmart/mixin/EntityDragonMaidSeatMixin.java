package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.3.0(beta) 实测七百二十【冰火传说的龙：女仆按玩家同款鞍位落座，不再被当成"含在嘴里的猎物"】。
 *
 * <h2>玩家原话</h2>
 * 「女仆坐在龙身上的位置跟玩家不一样。而且龙会直接把女仆从背上甩下来。」——两条是同一个根因的两半。
 *
 * <h2>根因（反编译实证，普通版/社区版、1.20.1/1.21.1 四份一致）</h2>
 * 龙把乘客分两类，判据是 {@code getControllingPassenger()}：1.20.1 上**只认 Player**
 * （{@code EntityDragonBase.m_6688_()}：{@code passenger instanceof Player}），1.21.1 上只认
 * "主人"。女仆两边都不满足 → 恒为 null → 龙的 {@code m_19956_}／{@code positionRider} 走
 * **另一个分支** {@code updatePreyInMouth(passenger)}：把她摆在**嘴边**，并在
 * {@code animationTick > 55} 时咬她一口（{@code m_6469_}，伤害×2）再 {@code m_8127_()}
 * ——玩家说的"甩下来"就是这一下。位置在嘴边 + 55 tick 后被咬下来，两半同源。
 *
 * <h2>为什么注入原版 {@code Entity} 的漏斗，而不是龙自己的覆写</h2>
 * 乘客位置每 tick 由原版算一遍（1.20.1 {@code Entity.m_6083_ → m_7332_ → m_19956_}，
 * javap 实证）。其中 <b>{@code m_7332_} 是 {@code public final}</b>——龙**不可能覆写**它
 * （它只覆写了两参的 {@code m_19956_}）。在这个漏斗上 HEAD 拦下并 cancel，就**整段跳过**
 * 龙的覆写（含那条猎物分支），我们自己按鞍位把她摆好。
 *
 * <p>顺带解决了一个更硬的约束：冰火传说**是可选模组**，直接 {@code @Mixin} 它的类时，目标类
 * 不存在只会记一条 WARN 不崩（反编译 {@code MixinInfo.getTargetClass} 实证），但本项目的只读
 * 审计 {@code _mixchk.py} 会把"解析不到的目标类"记 UNRES 而拦打包——而那两家模组的 jar 不在
 * 编译 classpath 上。注入原版 {@code Entity} 既没有这个问题，又让审计能逐字证明注入点存在。
 *
 * <h2>"是不是我们绑的"在客户端怎么判</h2>
 * 服务端有链路表，但**乘客位置不随包同步**：客户端每 tick 用载具的权威位置自己算一遍乘客位置
 * （{@code ClientLevel.tickPassenger → m_6083_}，反编译实证），所以这条**必须客户端也生效**，
 * 否则她在你屏幕里仍是"含在嘴里"。客户端拿不到 persistentData（不过网），但**主人 UUID 是同步
 * 实体数据**（{@code TamableAnimal.DATA_OWNERUUID_ID}，javap 实证）——所以两树同口径，判据 =
 * **"乘客是【有主】女仆 + 载具是冰火传说的龙"**（见 {@link
 * com.maidsmart.combat.MaidMountCompat#shouldSeatMaidOnDragon} 里"为什么这等价于棍子绑的"）。
 *
 * <h2>名字口径</h2>
 * 手工编译、无 refmap，{@code method} 按运行时名逐字匹配：本文件是 1.20.1 树（SRG 名）。
 * 1.21.1 树见 promaid_src_neo 的同名文件（官方名，且漏斗换成一参的
 * {@code positionRider(Entity)}——那个也是 {@code final}）。
 */
@Mixin(Entity.class)
public abstract class EntityDragonMaidSeatMixin {

    /**
     * 在**原版**的 {@code m_7332_}（= {@code positionRider(Entity)} 的 SRG 名，{@code final}）
     * 头部：若乘客是本模组的有主女仆、且载具是冰火传说的龙，就按**玩家同款鞍位**落座并 cancel。
     *
     * <p>鞍位不重复实现：直接反射调龙自己的 {@code getRiderPosition()}（公开方法），
     * 它算出来的就是玩家坐的那个点（含俯仰补偿与飞行/行走抬高）。拿不到就**不 cancel**——
     * 退回原版（宁可不生效，也不把她摆到错的地方）。
     *
     * <p>别的载具（原版马/猪/别的模组）与别的乘客（玩家本人、别的生物、无主女仆）**一个字节
     * 都不动**：判据第一句就把它们放行。
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
            // 与龙自己给玩家算的一模一样：鞍位 + 乘客身高（见 EntityDragonBase.m_19956_ 的玩家分支）
            passenger.m_6034_(seat.f_82479_, seat.f_82480_ + (double) passenger.m_20206_(), seat.f_82481_);
            ci.cancel();
        } catch (Throwable ignored) {
        }
    }
}
