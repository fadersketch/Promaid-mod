package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 【实测六百八十八】"本来要走路过去的活"交给仿创造飞行——直连寻路注入点。
 *
 * ── 为什么挂在 {@code moveTo} 上 ──
 * 她的移动有三条来源：①大脑的 {@code WALK_TARGET}（由 {@code MoveToTargetSink} 消费，最终走
 * {@code PathNavigation.moveTo(Path, double)}）；②**直连寻路**（挖矿 / 伐木 / 农活这些驱动每 tick
 * 自己调 {@code moveTo(x, y, z, speed)}，即本注入点的四参重载）；③别处直接写速度。
 * "本来要走过去的活"主要落在 ② 上（见 {@code MaidMineBehavior:1408} 那条注释：挖矿早已改成不再写
 * WALK_TARGET、改直连寻路），所以这里只掐 ② 这一个重载——**判据窄、影响面清楚**：
 * {@code MoveToTargetSink} 走的是 {@code moveTo(Path, double)}，一个字都不碰。
 *
 * ── 与 {@code SpawnerTorchNavGuardMixin} 的关系 ──
 * 那个注入点（实测六百六十四）是"刷怪笼插火把优先"的走位所有权闸，它挡的是**别的驱动**、放行
 * 自己那一发；本注入点是"她要去的地方太远 / 要上下 → 改飞"。两者都在 HEAD 上、都返回 false，
 * 同时命中时结果一致（那一发地面寻路不生效），不冲突。
 *
 * ── 为什么不在这里做「寻路失败就飞」──
 * HEAD 上拿不到"这条路通不通"（要么先跑一次 A*——那正是我们在省的开销，要么就放弃这个信号）。
 * 所以我们只按"距离 / 高差"判（{@code MaidFreeFlightController.takeOverWalk}），
 * 且**目标格底下落不下去就完全不接管**（沟上、虚空上、水面上方照旧交给她自己想办法）。
 *
 * ── 描述符为什么写全 ──
 * {@code PathNavigation.moveTo} 有四个重载，只写方法名会让 Mixin 目标歧义（加载期报错），
 * 所以连参数表一起写死：{@code (DDDD)Z}。
 */
@Mixin(PathNavigation.class)
public abstract class FreeFlightWalkGuardMixin {

    @Shadow
    @Final
    protected Mob mob;

    @Inject(method = "moveTo(DDDD)Z", at = @At("HEAD"), cancellable = true)
    private void maidsmart$flyInsteadOfWalk(double x, double y, double z, double speed,
                                            CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!(this.mob instanceof EntityMaid maid)) {
                return;
            }
            if (com.maidsmart.flight.MaidFreeFlightController.takeOverWalk(maid, x, y, z)) {
                cir.setReturnValue(false); // 这一发地面寻路不生效：她改用飞的（不走路、也不搭路）
            }
        } catch (Throwable ignored) {
        }
    }
}
