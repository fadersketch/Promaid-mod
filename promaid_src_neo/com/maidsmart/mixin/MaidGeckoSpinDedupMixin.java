package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.GeoReplacedEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * v1.2.0 实测五百三十七【Gecko 模型女仆的激流旋转为什么转不起来】。
 *
 * ── 需求原文（症状）──
 * "激流我只能听到音效和看到伤害，但是没有像玩家那样的旋转特效和动作。"
 *
 * ── 根因：TLM 自己那对旋转把原版的那对**正好抵消**了 ──
 * 原版早在 `LivingEntityRenderer.setupRotations` 里就实现了激流姿态（不是 Player 专属，
 * 1.21.1 字节码在本方法 202-204 行）：
 * <pre>
 *   } else if (entity.isAutoSpinAttack()) {
 *       poseStack.mulPose(Axis.XP.rotationDegrees(-90.0f - entity.getXRot()));
 *       poseStack.mulPose(Axis.YP.rotationDegrees((entity.tickCount + partialTicks) * -75.0f));
 *   }
 * </pre>
 * 而 TLM 1.5.3 的 `GeoReplacedEntityRenderer.setupRotations` 在 `super`（= 上面这段）之后
 * **又施加了同轴反号的一对**（javap 实证：`invokespecial LivingEntityRenderer.setupRotations`
 * → `invokevirtual LivingEntity.isAutoSpinAttack` → 两次 `mulPose`，常量 `75.0f`）：
 * <pre>
 *   if (deathTime <= 0 && entity.isAutoSpinAttack()) {
 *       poseStack.mulPose(Axis.YP.rotationDegrees((entity.tickCount + partialTicks) * 75.0f));
 *       poseStack.mulPose(Axis.XP.rotationDegrees(90.0f + entity.getXRot()));
 *   }
 * </pre>
 * `mulPose` 是**右乘**，所以合成矩阵是
 * <pre>
 *   M = M0 · XP(a) · YP(b) · YP(-b) · XP(-a)      a = -90 - xRot, b = -75·(tick+partial)
 *     = M0 · XP(a) · XP(-a) = M0
 * </pre>
 * 中间那对同轴 YP 相邻、直接消成单位矩阵，剩下的一对同轴 XP 也消成单位矩阵 ——
 * **净旋转恒等于什么都不做**。所以 Gecko/YSM 模型（酒狐家族等，走的正是这个渲染器）
 * 的女仆突进时永远不转、也不俯卧；Bedrock 模型（`EntityMaidRenderer`，
 * TLM 内置的 `touhou_little_maid:*` 模型）不走这里，本来就有原版那一份，不受影响。
 *
 * ── 修法：只掐掉 TLM 那一对重复的，把原版那一份留下 ──
 * 把 TLM 那次标志位读取改成 `false`，它的 `if` 块整段不执行 → 只剩原版的
 * `XP(-90 - xRot) + YP(-75·tick)`，与玩家**完全同款**（不自己写角度、不重复叠加）。
 * 副作用面为零：该 `if` 块里除这两次 `mulPose` 没有任何其它语句，且标志位本就是"只有
 * 激流突进中才为真"，不突进的女仆一个字都不受影响。
 *
 * ── 为什么 require = 0（而不是项目默认的 1）──
 * 这里的注入是"**撤销别人的重复**"，不是"提供我们自己的行为"：万一 TLM 以后自己把这
 * 一对删掉（即它修好了自己的 bug），重定向不再命中，我们就自动不再干预，原版那一份
 * 照常工作 —— 这正是我们想要的结果。写 `require = 1` 会把"TLM 修好了"变成启动崩溃，
 * 反而有害。
 *
 * ── 覆盖范围（如实说明）──
 * 只覆盖走 `GeoReplacedEntityRenderer` 的模型（Gecko 模型，含 `GeckoEntityMaidRenderer`）。
 * YSM 模型由 YSM 自己的 `IGeoEntityRenderer` 渲染、不经这个类，TLM 只给它暴露
 * `is_riptide` molang 变量（`YSMBinding`），那条路的旋转姿态取决于 YSM 自己的渲染器，
 * 本 mixin 管不到也不该乱管。
 *
 * ── 与本文件并存的 {@link MaidFlightDiveTiltMixin} ──
 * 那一个也在 `setupRotations` 的 RETURN 处叠加飞行俯角，但它作用于飞行任务的前倾、与激流
 * 无关，两者互不干扰（本 mixin 改的是"TLM 的重复旋转跑不跑"，它做的是"再叠一层俯角"）。
 */
@Mixin(GeoReplacedEntityRenderer.class)
public abstract class MaidGeckoSpinDedupMixin {

    /**
     * 拦截 TLM 那次 {@code isAutoSpinAttack()} 读取，恒返回 false —— 让它那一对与
     * 原版反号的重复旋转整段跳过。
     */
    @Redirect(method = "setupRotations",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;isAutoSpinAttack()Z"),
            require = 0)
    private boolean promaid$dropDuplicateSpin(LivingEntity entity) {
        return false;
    }
}
