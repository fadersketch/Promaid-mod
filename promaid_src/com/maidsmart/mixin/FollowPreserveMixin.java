package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidFollowOwnerTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 自保传送豁免（v1.5.2）。
 *
 * 背景：TLM 的 MaidFollowOwnerTask 在离主人过远时会 teleportToOwner 瞬移回主人
 * 身边。自保（逃跑）期间跟随行为仍会启动（vanilla Brain 每个优先级组都能启动
 * 一个行为），导致低血逃跑时被传送回主人身边"白白送死"。
 *
 * 做法：mixin 注入 checkExtraStartConditions（开发名目标——mixin 应用在 Forge
 * SRG 重映射之前，原版 TLM 类的方法名是开发名），自保标记存在且血量未恢复到
 * 70% 时直接返回 false，跟随不启动 → 不传送。自保结束标记清除后恢复原行为。
 *
 * v1.1.0 实测八十三：追加主人死亡闸——主人已死亡（尸体停在死亡地点等待重生）
 * 时跟随一律不启动：根绝 TLM 内置的离主人过远 teleportToOwner 把女仆瞬移到
 * 【死亡地点】的路径（粉丝实测"死亡后女仆在尸体旁而不是重生点"）。原版
 * ownerStateConditions 只查 isRemoved 不查存活，尸体期会放行。
 *
 * v1.1.0 实测八十三c【回退 home 门】：八十三曾在此一并拦截 home 女仆的跟随启动，
 * 结果 home 女仆原地站桩（用户实测）。字节码取证：TLM 对 home 女仆本就是
 * 「checkExtraStartConditions 放行 → start() 内部 maidStateConditions 全分支
 * 空转」，该任务每 tick 启动并占据其优先级组是大脑正常运转的一部分；从外部
 * 掐掉启动会让同组/后续优先级的行为接管出异常动力学。home 的传送防护由各
 * 传送点自行豁免（自保两处 / 死亡传送三态 / 集合与跨维跟随），此处不再重复。
 *
 * 注意：handler 方法体内的 MC 调用用 SRG 名（m_21223_ 等），重映射阶段不会被
 * 改动，运行时即为正确名字；mixin 注解里的目标方法名必须用开发名。
 */
@Mixin(MaidFollowOwnerTask.class)
public abstract class FollowPreserveMixin {
    private static final String PRESERVE_TAG = "maid_smart_preserving";

    @Inject(method = "checkExtraStartConditions", at = @At("HEAD"), cancellable = true)
    private void maidSmartPreserveFollow(ServerLevel level, EntityMaid maid, CallbackInfoReturnable<Boolean> cir) {
        // v1.1.0 实测八十三：主人死亡（尸体等待重生）→ 跟随不启动（瞬移同样不会发生）。
        // home 模式不在此拦——见上「回退 home 门」。
        net.minecraft.world.entity.LivingEntity owner = maid.m_269323_();
        if (owner == null || !owner.m_6084_()) {
            cir.setReturnValue(false);
            return;
        }
        if (maid.getPersistentData().m_128471_(PRESERVE_TAG)
                && maid.m_21223_() / Math.max(1.0f, maid.m_21233_()) < 0.70f) {
            cir.setReturnValue(false);
        }
    }
}
