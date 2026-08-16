package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * v1.5.344：心契誓约（maidmarriage 2.3.0）Alt+J 对坐着的女仆无法进入交互面板——
 * 两个服务端校验链都有同样的 `if (maid.isMaidInSittingPose()) { 发 need_standing 消息; return; }`
 * 拒绝分支，本 mixin 对两处一起处理：
 *  1. 成人/妻子/恋人路径：MaidHugManager.handleInteractionToggle（v1.5.333 已修）
 *  2. 儿童/女儿路径：ChildInteractionManager.handleInteractionToggle（v1.5.344 补上——
 *     女儿走 ChildInteractionPayload → 这个管理器，之前只修了成人管理器，所以女儿
 *     坐着一按 Alt+J 依旧被 need_standing 拒之门外）
 *
 * 修复：HEAD 处先把【目标坐姿女仆】站起来（m_21837_(false) 清坐姿+指令位 +
 * m_20124_(STANDING)）——仅限：坐姿中、主人、未骑乘（骑乘由原版 riding_blocked
 * 继续拒绝，站起来无意义）。站起后原版 need_standing 检查自然通过 → 交互启动；
 * 后续 lockMaid 本身就会把女仆设为 STANDING 并锁定姿势/位置（m_20124_ +
 * m_21837_(false) + 锁定坐标），与我们的预站起一致，无副作用。
 *
 * 两个目标方法签名相同（public static void handleInteractionToggle(ServerPlayer, UUID)），
 * 用同一注入逻辑。
 *
 * @Pseudo + require=0：心契誓约可选模组，未装/版本变动 → 静默跳过。
 */
@Pseudo
@Mixin(targets = {
        "com.example.maidmarriage.compat.MaidHugManager",
        "com.example.maidmarriage.compat.ChildInteractionManager"
})
public abstract class MixinInteractionSittingAllow {

    @Inject(method = "handleInteractionToggle", at = @At("HEAD"), require = 0)
    private static void maidsmart$standSittingMaid(ServerPlayer player, UUID maidUuid, CallbackInfo ci) {
        try {
            if (!(player.m_9236_() instanceof ServerLevel sl)) {
                return;
            }
            Entity e = sl.m_8791_(maidUuid);
            if (e instanceof EntityMaid maid
                    && maid.m_21825_()              // isInSittingPose
                    && !maid.m_20159_()             // 未骑乘
                    && maid.m_21830_(player)) {     // 是该玩家的女仆（对齐原版 need_owner 判定）
                maid.m_21837_(false);               // 站起来（TLM override：坐姿+指令位一起清）
                maid.m_20124_(Pose.STANDING);
            }
        } catch (Exception ignored) {
        }
    }
}
