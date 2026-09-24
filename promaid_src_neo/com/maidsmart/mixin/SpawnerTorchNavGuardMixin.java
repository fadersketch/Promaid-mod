package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 直连寻路让位闸（v1.3.0(beta) 实测六百六十四）——刷怪笼插火把的「优先」落到这一处。
 *
 * ── 为什么需要它 ──
 * 1.20.1 的 Brain 里 CORE 与当前活动（WORK）是**两套并发的行为表、互不阻断**，而本模组自己的
 * 挖矿/伐木/农活驱动走的是**直连寻路** {@code PathNavigation.moveTo}（不走 WALK_TARGET，
 * 所以 {@code MaidMoveSuppressMixin} 那面闸拦不住）。她去插火把的路上，那几条驱动每 tick
 * 照旧把寻路目标换成"工位"——谁最后写谁赢，于是玩家看到的"优先"没了。
 *
 * ── 判据与放行 ──
 * 目标实体（{@code PathNavigation.mob}，SRG {@code f_26494_}）是本模组所有的女仆 +
 * {@link com.maidsmart.task.MaidWorkTags#isSpawnerTorchRun} 为真（= 正在走去插火把 + 没在打架 +
 * 没坐着/被骑 + 开关开着，见 {@code combat.spawnerTorch.priority}）→ 这一发寻路直接不生效
 * （{@code moveTo} 返回 false）。**她自己的那一发**由
 * {@code MaidSpawnerTorchBehavior.selfNav()} 的自标记放行（服务端单线程，同步调用，安全）。
 *
 * ── 影响面 ──
 * 只在这一段（有刷怪笼目标、正在走过去，最长 20 秒，之后进 60 秒冷却）生效；其余任何时刻
 * 本注入的第一条判据就返回 false，原版/本模组其它驱动的寻路一个字都不动。
 *
 * ── 描述符为什么写全 ──
 * {@code PathNavigation.moveTo} 有四个重载，只写方法名会让 Mixin 目标歧义（加载期报错），
 * 所以连参数表一起写死：{@code (DDDD)Z}。
 */
@Mixin(net.minecraft.world.entity.ai.navigation.PathNavigation.class)
public abstract class SpawnerTorchNavGuardMixin {

    @Shadow
    @Final
    protected Mob mob;

    @Inject(method = "moveTo(DDDD)Z", at = @At("HEAD"), cancellable = true)
    private void maidsmart$yieldToSpawnerTorch(double x, double y, double z, double speed,
                                              CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!(this.mob instanceof EntityMaid maid)) {
                return;
            }
            if (com.maidsmart.combat.MaidSpawnerTorchBehavior.selfNav()) {
                return; // 这一发是插火把这条链自己发的 → 放行
            }
            if (!com.maidsmart.task.MaidWorkTags.isSpawnerTorchRun(maid)) {
                return;
            }
            cir.setReturnValue(false); // 她正走去插火把：别的驱动这一发寻路不生效
        } catch (Throwable ignored) {
        }
    }
}
