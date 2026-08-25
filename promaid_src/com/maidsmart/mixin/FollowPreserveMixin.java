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
 * v1.1.0 实测八十三：追加两道硬闸——①主人已死亡（尸体停在死亡地点等待重生）
 * 时跟随一律不启动：根绝 TLM 内置的离主人过远 teleportToOwner 把女仆瞬移到
 * 【死亡地点】的路径（粉丝实测"死亡后女仆在尸体旁而不是重生点"，与自保传送
 * 同源）；②home 看家钉死的女仆跟随不启动（与一键集合/死亡传送的保持原位口径
 * 统一）。复活后 owner 恢复存活，跟随自然恢复。
 *
 * 注意：handler 方法体内的 MC 调用用 SRG 名（m_21223_ 等），重映射阶段不会被
 * 改动，运行时即为正确名字；mixin 注解里的目标方法名必须用开发名。
 */
@Mixin(MaidFollowOwnerTask.class)
public abstract class FollowPreserveMixin {
    private static final String PRESERVE_TAG = "maid_smart_preserving";

    @Inject(method = "checkExtraStartConditions", at = @At("HEAD"), cancellable = true)
    private void maidSmartPreserveFollow(ServerLevel level, EntityMaid maid, CallbackInfoReturnable<Boolean> cir) {
        // v1.1.0 实测八十三：主人死亡 / home 看家 → 跟随不启动（瞬移同样不会发生）
        net.minecraft.world.entity.LivingEntity owner = maid.m_269323_();
        if (owner == null || !owner.m_6084_() || maid.isHomeModeEnable()) {
            cir.setReturnValue(false);
            return;
        }
        if (maid.getPersistentData().m_128471_(PRESERVE_TAG)
                && maid.m_21223_() / Math.max(1.0f, maid.m_21233_()) < 0.70f) {
            cir.setReturnValue(false);
        }
    }
}
