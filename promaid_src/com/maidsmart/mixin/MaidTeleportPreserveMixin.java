package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 自保传送豁免第二道防线（v1.5.17）。
 *
 * 第一道（FollowPreserveMixin）拦 MaidFollowOwnerTask.checkExtraStartConditions——
 * 但那只在"跟随行为尚未启动"时生效；若跟随行为已处于 RUNNING 状态
 * （比如自保触发前正在跟随），或跟随逻辑走到了 start 里的 teleportToOwner，
 * 仍需直接拦截传送动作本身。
 *
 * 这里 mixin EntityMaid.teleportToOwner（开发名目标）：自保标记存在且血量
 * 未恢复 70% 时直接返回 false——无论从哪个路径调用都不再瞬移回主人身边。
 */
@Mixin(EntityMaid.class)
public abstract class MaidTeleportPreserveMixin {
    private static final String PRESERVE_TAG = "maid_smart_preserving";

    @Inject(method = "teleportToOwner", at = @At("HEAD"), cancellable = true)
    private void maidSmartPreserveTeleport(LivingEntity owner, CallbackInfoReturnable<Boolean> cir) {
        EntityMaid maid = (EntityMaid) (Object) this;
        // v1.5.138：建造女仆（任务 = maid_smart:build）任何时刻禁止 TLM 瞬移回主人身边。
        // 根治"下达建造后女仆被传送走"：v1.5.121 的 home 模式只在建造行为 doStart 后
        // 才强制开启，切任务→行为启动前的窗口期 FollowOwner（CORE 3）若在跑且
        // 距离主人 > 7 格 → teleportToOwner 把女仆瞬移回主人身边（主人没动、女仆被传）。
        // 这里从 TLM 源头一网打尽：建造女仆永不瞬移（站桩定位由建造行为自己负责）。
        try {
            // v1.5.252q：m_135815_ = getPath（javap 实证）——旧版写 "maid_smart:build"
            // 恒为 false，建造女仆瞬移拦截从未生效，此处修正
            if (maid.getTask() != null && "build".equals(maid.getTask().getUid().m_135815_())) {
                cir.setReturnValue(false);
                return;
            }
        } catch (Exception ignored) {
        }
        // v1.5.92：原"防窒息 20 秒传送冷却"抑制分支已移除——建仆不被瞬移回施工区
        // 由建造行为强制 home 模式从 TLM 源头保证（见 MaidBuildBehavior），不再需要
        // 后置的 teleportToOwner 拦截。此 mixin 只保留自保传送豁免。
        if (maid.getPersistentData().m_128471_(PRESERVE_TAG)
                && maid.m_21223_() / Math.max(1.0f, maid.m_21233_()) < 0.70f) {
            cir.setReturnValue(false);
        }
    }
}
