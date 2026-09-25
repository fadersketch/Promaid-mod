package com.maidsmart.mixin;

import com.maidsmart.combat.GunnerTetherManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.MaceItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 【实测六百七十六】二号位重锤猛击：吊在女仆下方时，把"她这一段俯冲下落了多少格"写回
 * {@code player.fallDistance}，让玩家自己手里的重锤**也能吃到下落加成**。
 *
 * <p><b>玩家原话</b>："我刚刚在运行游戏的时候，让女仆进行了近战空袭，然后我手里面也拿了个重锤。
 * 那么我可以正常触发这个重锤的增伤等效果吗？我更希望玩家可以吃到这些效果。而不受坐下这个状态
 * 影响。"
 *
 * <p><b>为什么原来吃不到（反编译实证，1.21.1）</b>：重锤那条链路只有一次读
 * {@code fallDistance}——{@code Player.attack} 第 209 行
 * {@code $$1 += this.getWeaponItem().getAttackDamageBonus(target, $$1, source)}；
 * {@code MaceItem.getAttackDamageBonus} 里先 {@code canSmashAttack}（=
 * {@code fallDistance > 1.5f && !isFallFlying()}）再按 4f / 12+2(f-3) / 22+(f-8) 三段算；
 * 命中后 {@code MaceItem.hurtEnemy} 顺手放猛击音效、周围 3.5 格击退、并
 * {@code setIgnoreFallDamageFromCurrentImpulse(true)}（免那一下摔伤），
 * {@code postHurtEnemy} 再 {@code resetFallDistance()}。
 * 而 {@code fallDistance} **只在 {@code Entity.move} → {@code Entity.checkFallDamage} 里累加**
 * （{@code Entity.java} 656 / 1135 行），**乘客不走 move**：{@code Entity.rideTick} 先把速度清零、
 * 再由载具的 {@code positionRider} 直接 {@code setPos}（我们的
 * {@link EntityGunnerHangMixin} 也正是这么定位的）→ 吊在她下面的玩家 {@code fallDistance}
 * 恒为 0 → 打不出猛击。
 *
 * <p><b>所以做法是"只补那一个字段"</b>：不改原版任何判据，只在攻击那一刻把
 * {@link GunnerTetherManager#consumeRideFall}（他自己那一份"已下落格数"）写进
 * {@code fallDistance}，后面原版那整套自己就跑通了。写法与女仆那边
 * （{@code MaidFlightCombatBehavior} / {@code MaidMaceSmashBehavior} 强制写
 * {@code maid.fallDistance}）**同一个套路**，只是主体换成了玩家。
 *
 * <p><b>为什么收窄到"手里真是重锤"</b>：{@code fallDistance} 是个被很多东西读的公共字段
 * （暴击判定、摔伤、其它模组的加成…）。只在"这一锤本来就是重锤"时写，爆炸半径最小：
 * 原版猛击成功会自己 {@code resetFallDistance()}，不会留下"莫名摔伤"的后遗症。
 *
 * <p><b>两侧与时机</b>：{@code Player.attack} 客户端也会调（本地预测），这里只认服务端
 * （{@code isClientSide} 直接返回）——伤害、音效、击退本来就是服务端算的。
 * {@code HEAD} 注入保证"写在那一次读取之前"。
 *
 * <p>本模组手工编译、无 refmap，{@code method} 按**运行时名**逐字匹配；1.21.1 运行时是官方名，
 * 所以这里写 {@code attack}（1.20.1 那边没有重锤，本混入不进那棵树）。
 */
@Mixin(Player.class)
public abstract class PlayerMaceRideFallMixin {

    @Inject(method = "attack(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))
    private void maidsmart$rideMaceSmash(Entity target, CallbackInfo ci) {
        try {
            Player self = (Player) (Object) this;
            if (self.level().isClientSide()) {
                return;
            }
            if (!GunnerTetherManager.maceSmashWhileRiding()) {
                return;
            }
            if (!(self.getWeaponItem().getItem() instanceof MaceItem)) {
                return;
            }
            if (self.isFallFlying()) {
                return; // 与 canSmashAttack 的第二道门同口径
            }
            float ride = GunnerTetherManager.consumeRideFall(self);
            if (ride > GunnerTetherManager.rideSmashMin() && self.fallDistance < ride) {
                self.fallDistance = ride;
            }
        } catch (Throwable ignored) {
        }
    }
}
