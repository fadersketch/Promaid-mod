package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.3.7 实测六百六十七【武装拴绳】：她的拴绳枪手挂在【下方】（默认 1.8 格），不是骑在头顶。
 *
 * 【为什么注入这里】原版把乘客定位在载具身上的唯一入口就是载具的
 * {@code positionRider(Entity, Entity.MoveFunction)}（1.21.1 官方名；javap 实证方法体 =
 * {@code getY() + 乘客挂点} 后回调 {@code MoveFunction.accept}）。
 * TLM 的 {@code EntityMaid} 没有覆写它（javap 全表核对过），所以注入 EntityMaid 拦到 HEAD 即可。
 *
 * 【语义】只有"拴绳挂载的那位玩家"（GunnerTetherManager.isGunner：服务端 LINKS /
 * 客户端 SYNCED_PAIRS）才会被改定位，其余乘客（TLM 自己的可骑等）走原版头顶逻辑；
 * 有玩家在驾驶（TLM 扫帚那套）也完全不碰。回调仍是原版同款 MoveFunction
 * （Entity::setPos），只是 Y 从"脚踩在她身上"换成"悬在她脚底下方"。
 *
 * 【服务端/客户端都生效】positionRider 两侧都会调：服务端定权威位置，
 * 客户端同款算式保证本地预览一致（挂载对由 S2C 包同步，见 GunnerTetherManager）。
 */
@Mixin(EntityMaid.class)
public abstract class EntityMaidGunnerMixin {

    @Inject(method = "positionRider", at = @At("HEAD"), cancellable = true)
    private void maidsmart$hangGunnerBelow(Entity passenger, Entity.MoveFunction fn, CallbackInfo ci) {
        try {
            if (!(passenger instanceof Player)) {
                return;
            }
            if (!com.maidsmart.combat.GunnerTetherManager.isGunner((EntityMaid) (Object) this, passenger)) {
                return;
            }
            double hang = com.maidsmart.combat.GunnerTetherManager.hangOffset();
            // 玩家脚底 = 女仆脚底 − 悬挂距离；水平贴她的中心（跟原版同款，不前后偏移）。
            // 注意 mixin 类不继承 EntityMaid，直接 this.getX() 编译不过——走强转
            EntityMaid self = (EntityMaid) (Object) this;
            fn.accept(passenger, self.getX(), self.getY() - hang, self.getZ());
            ci.cancel();
        } catch (Throwable ignored) {
        }
    }
}
