package com.maidsmart.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v1.3.0(beta) 实测七百二十二【骑乘指挥棒·独占右击二期：在"登乘"这一个源头上一刀切】
 * （1.20.1 版）——完整根因（为什么 721 那层 {@code EntityMountEvent} 不够：它是
 * "先同意上车再撤销"，那一刻玩家眼前已经坐上去过了；实机日志 16:07:10.572/573
 * 两行同时出现就是证据）见 1.21.1 树同名类。
 *
 * <p>本树只换名字：{@code startRiding(Entity, boolean)} = {@code m_7998_(Entity, Z)}。
 * 注入头部直接返回 false，什么状态都还没改。
 */
@Mixin(Entity.class)
public abstract class EntityBatonMountGateMixin {

    @Inject(method = "m_7998_(Lnet/minecraft/world/entity/Entity;Z)Z",
            at = @At("HEAD"), cancellable = true)
    private void maidsmart$denyBatonHolderMount(Entity vehicle, boolean force,
                                                CallbackInfoReturnable<Boolean> cir) {
        try {
            Entity self = (Entity) (Object) this;
            if (!(self instanceof Player player)) {
                return; // 只拦"玩家本人"：女仆、别的生物一律放行
            }
            if (!com.maidsmart.combat.RideBindManager.denyMountForBatonHolder(player, vehicle)) {
                return;
            }
            cir.setReturnValue(false);
        } catch (Throwable ignored) {
        }
    }
}
