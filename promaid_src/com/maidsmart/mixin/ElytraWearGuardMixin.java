package com.maidsmart.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v1.2.2 实测六百〇八：飞行跟随的"不消耗鞘翅耐久"开关。
 *
 * ── 拦在哪 ──
 * 原版滑翔扣耐久发生在 {@code LivingEntity.updateFallFlying()} 里那一句
 * {@code stack.elytraFlightTick(this, this.fallFlyTicks)}（字节码实证：
 * forge-1.20.1-47.4.21 `LivingEntity.m_21323_` 偏移 57 调用
 * `ItemStack.elytraFlightTick:(Lnet/minecraft/world/entity/LivingEntity;I)Z`，
 * 结果若为假就把滑翔位清掉）。而 {@code ElytraItem.elytraFlightTick} 自己就是
 * "每 20 tick 扣 1 点耐久"的地方：
 * <pre>
 *   if (!level.isClientSide &amp;&amp; (flightTicks + 1) % 20 == 0) {
 *       stack.hurtAndBreak(1, entity, e -&gt; e.broadcastBreakEvent(CHEST));
 *   }
 *   return true;
 * </pre>
 * forge 偏移 39 是 `ItemStack.m_41622_(1, entity, consumer)`，neo 同位置是
 * `ItemStack.hurtAndBreak(1, entity, EquipmentSlot.CHEST)`。
 *
 * ── 为什么在 HEAD 直接返回 true（而不是取消掉）──
 * 这个方法返回**布尔 = "这一 tick 的滑翔还成立吗"**：上面那个调用点拿到 true 才会
 * 保留滑翔位。所以"免耐久"不能 `ci.cancel()`（那会落到默认返回值 false → 她当场
 * 掉出滑翔、20 血自由落体），必须 `setReturnValue(true)`：跳过扣耐久那一段，
 * 但语义仍然是"这一 tick 滑翔照旧成立"。也就是说本 mixin 只吃掉 hurtAndBreak，
 * 一点飞行手感都不改。
 *
 * ── 边界（写进手册）──
 * 只认原版 {@code ElytraItem} 及其**调用 super 的**子类；模组那些"自带滑翔的护甲"
 * 走自己的钩子，这里拦不到。判据全部由
 * {@link com.maidsmart.combat.MaidFlightFollowBehavior#shouldSkipElytraWear(Object)}
 * 给（她是女仆 + 她正在飞行跟随 + "不消耗鞘翅耐久"关着），任何异常一律返回 false
 * ——最坏情况照原版扣耐久，绝不会误伤别的实体。
 *
 * 【两树同一份】{@code elytraFlightTick} 是加载器新增的方法名（forge 与 neo 都未混淆，
 * javap 实证），所以本文件在 SRG 树与 Mojmap 树里逐字相同。
 */
@Mixin(ElytraItem.class)
public abstract class ElytraWearGuardMixin {

    @Inject(method = "elytraFlightTick", at = @At("HEAD"), cancellable = true)
    private void maidsmart$skipWearWhileFollowing(ItemStack stack, LivingEntity entity, int flightTicks,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (com.maidsmart.combat.MaidFlightFollowBehavior.shouldSkipElytraWear(entity)) {
            cir.setReturnValue(true); // 照旧"滑翔成立"，只是不扣这一点耐久
        }
    }
}
