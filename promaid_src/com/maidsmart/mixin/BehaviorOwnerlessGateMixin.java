package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v1.2.2 实测六百【无主女仆全面降级】的**总闸**：本模组注册的 AI 行为对无主女仆一律不许起手。
 *
 * ── 需求原文 ──
 * "某些整合包，对于无主的女仆会有一些额外的特殊效果。我们这个 mod 不能够阻碍他们。
 * 所以我需要做一个很大的降级处理。对于无主女仆，promid 所做的一切改动全都不生效，
 * 保持为原版的状态，promid 完全不干预。"
 *
 * ── 为什么卡在 {@code Behavior} 上 ──
 * 本模组所有行为（自保 / 落地水 / 战术 / 自动装备 / 贴身辅助 / 搭路 / 散步 / 施工 / 采矿 /
 * 砍树 / 酿造 / 烹饪 / 站泡气泡 / 床铺互通…）都是 {@code com.maidsmart.*} 里
 * {@code extends Behavior<EntityMaid>} 的类，注册进 TLM 的 core / work / rest 三张行为表。
 * 与其在二十多个行为里各加一行判据（漏一个就漏一条链路），不如卡在**基类**：
 *
 * <ul>
 *   <li>{@code m_22554_}（{@code tryStart}）：**final** —— 任何行为的起手都要过这一关，
 *       子类怎么覆写 {@code checkExtraStartConditions} 都绕不开。无主 → 直接返回 false，
 *       等于"这个行为不存在"；</li>
 *   <li>{@code m_6737_}（{@code canContinueToUse}）：正在跑的行为每 tick 的续跑判定，
 *       锦上添花（若某个行为自己覆写了它，本注入不生效——但那种行为一开始就没起手过）。</li>
 * </ul>
 *
 * ── 为什么是"包名"而不是接口标记 ──
 * 判据{@code 类名以 com.maidsmart. 开头}（{@link com.maidsmart.tool.MaidScope#promaidBehavior}，
 * 按类缓存的 {@code ClassValue}）。用接口标记要给二十多个行为各改一次声明，漏一个就漏一条；
 * 包名判据零改动、且对本模组以后新加的行为**自动生效**。代价只是"行为必须住在自己的包里"
 * ——这本来就是本工程的约定。
 *
 * ── 开销 ──
 * 这段注入跑在**每个生物 / 每个行为 / 每 tick** 的 tryStart 上：先是一次 {@code ClassValue}
 * 查表（绝大多数行为不是本模组的 → 立刻 return），再是 {@code instanceof EntityMaid}。
 * 两次都是纳秒级，且不分配对象。
 */
@Mixin(net.minecraft.world.entity.ai.behavior.Behavior.class)
public abstract class BehaviorOwnerlessGateMixin {

    /** 起手闸：无主女仆 → 本模组的任何行为都不许 start */
    @Inject(method = "m_22554_", at = @At("HEAD"), cancellable = true)
    private void maidsmart$gateOwnerlessStart(ServerLevel level, LivingEntity entity, long gameTime,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (!com.maidsmart.tool.MaidScope.promaidBehavior(this)) {
            return;
        }
        if (!(entity instanceof EntityMaid maid) || com.maidsmart.tool.MaidScope.owned(maid)) {
            return;
        }
        com.maidsmart.tool.MaidScope.noteDowngrade(maid);
        cir.setReturnValue(false);
    }

    /** 续跑闸：跑到一半（认领被撤掉等）也要停 */
    @Inject(method = "m_6737_", at = @At("HEAD"), cancellable = true)
    private void maidsmart$gateOwnerlessContinue(ServerLevel level, LivingEntity entity, long gameTime,
                                                 CallbackInfoReturnable<Boolean> cir) {
        if (!com.maidsmart.tool.MaidScope.promaidBehavior(this)) {
            return;
        }
        if (!(entity instanceof EntityMaid maid) || com.maidsmart.tool.MaidScope.owned(maid)) {
            return;
        }
        cir.setReturnValue(false);
    }
}
