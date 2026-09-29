package com.maidsmart.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v1.3.0(beta) 实测七百二十二【骑乘指挥棒·独占右击二期：在"登乘"这一个源头上一刀切】
 * （1.21.1 版）。
 *
 * <h2>玩家原话</h2>
 * 「拿着骑乘棒右击卓越前线的载具会直接坐上去，而不是绑定。」
 *
 * <h2>为什么 720/721 那两道闸还不够（实机日志实证）</h2>
 * 2026-09-29 16:07 那一次测试里，日志同时打出两行：
 * <pre>
 *   16:07:10.572  [骑乘指挥棒] 绑定：主人=Khragg 女仆=圣女酒狐 坐骑=卓越前线载具(WHEELCHAIR)
 *   16:07:10.573  [骑乘指挥棒] Khragg 手里拿着指挥棒 → 不登乘（独占右击：这一下只归棍子）
 * </pre>
 * —— 绑定**成功了**、721 那道 {@code EntityMountEvent} 闸**也拦下了**，但玩家眼前仍然是
 * "自己坐上去了"（他自己坐上去以后又立刻下来）。原因在**拦的层次**：
 * {@code EntityMountEvent} 是在 {@code startRiding} **内部**发的，取消它等于"先同意上车、
 * 再把这件事撤销"——原版/NeoForge 那条撤销路径（{@code EventHooks.canMountEntity} 里
 * {@code setDeltaMovement(ZERO)} 之类的回摆）本身就是一次**可见的**上车+下车；
 * 而客户端本地那一份预测（卓越前线自己的 {@code ClientPacketListenerMixin} 会在收到
 * 挂载包时本地 {@code startRiding}）根本不看服务端的事件结果 → 玩家眼前那一下依然发生。
 *
 * <h2>修法：拦 {@code startRiding} 本身</h2>
 * {@code startRiding} 是**所有**登乘路径唯一的收口（vanilla 右击 / 各模组自己的上车 /
 * 别的模组调 API / 客户端本地预测），而且在 HEAD 处直接返回 {@code false}
 * **什么状态都还没改**——没有"上车再撤销"这一下，也就没有可见的坐上去。
 * 判据保持最窄的三条：**玩家本人 + 手里（主/副手）拿着骑乘指挥棒 + 独占档开着**。
 * 女仆自己坐上去（{@code maid.startRiding}）、武装拴绳那条链路（玩家拿的是拴绳不是指挥棒）
 * 一个字节都不受影响。
 *
 * <p>两侧同款：客户端也要拦（否则客户端预测会把你放上去，服务端再把你拉下来 = 抖）。
 */
@Mixin(Entity.class)
public abstract class EntityBatonMountGateMixin {

    @Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;Z)Z",
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
            cir.setReturnValue(false); // 与"这只坐骑不让你骑"同一条原版返回
        } catch (Throwable ignored) {
        }
    }
}
