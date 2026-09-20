package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.implement.SwitchWorkTaskTool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.maidsmart.compat.MaidModeCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v1.2.2 实测五百九十【自主切换的最后一道闸：TLM 自带的 switch_work_task】。
 *
 * 【为什么还要这一道】本模组自己的 LLM 工具 {@code smart_switch_task} 已经在
 * {@code SwitchTaskTool.onCall} 里过滤了黑名单；但 LLM 还有**TLM 原生**那条
 * 「换工作/换模式」工具 {@code switch_work_task}
 * （{@code ai.agent.tool.implement.SwitchWorkTaskTool}，javap 实证它有
 * {@code onCall(String, Result, LLMCallback)}，参数里的 {@code Result.id()} 就是任务 UID），
 * 而 {@code AutonomousTaskManager} 的自主决策正是驱动它——那条路完全不经过本模组代码。
 * 于是在工具调用入口按 UID 拦一下：黑名单模式（《傀儡装配》的「傀儡师」）一律拒绝，
 * 并把理由回给模型（它就会换别的任务，而不是反复重试）。
 *
 * 口径：命中即返回一条工具结果、整段取消（不切任务、不动任何状态）；
 * 玩家手动切换（TLM 面板 / 那个模组自己的入口）不受影响——届时本模组战术全体让位，
 * 见 {@link MaidModeCompat} 的类注释。
 *
 * 安全：{@code require = 0}——TLM 换版本改了这个方法描述符时**只让这条闸失效**，
 * 绝不因为注入点找不到而启动崩溃（同 ExplosionWindGuardMixin 的约定）。
 */
@Mixin(SwitchWorkTaskTool.class)
public abstract class PuppetModeLlmGuardMixin {

    @Inject(method = "onCall(Ljava/lang/String;Lcom/github/tartaricacid/touhoulittlemaid/ai/agent/tool/implement/SwitchWorkTaskTool$Result;Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/LLMCallback;)Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/LLMCallback;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void promaid$blockBlacklistedMode(String toolId, SwitchWorkTaskTool.Result result,
                                              LLMCallback callback, CallbackInfoReturnable<LLMCallback> cir) {
        try {
            if (result == null || callback == null || toolId == null) {
                return;
            }
            String uid = String.valueOf(result.id());
            if (!MaidModeCompat.isBlacklistedUid(uid)) {
                return;
            }
            cir.setReturnValue(callback.addToolResult("「" + uid + "」是第三方玩法模式（"
                    + MaidModeCompat.descriptionOfUid(uid)
                    + "），已列入黑名单、不参与自主切换——要她去这个模式请玩家手动切换。", toolId));
        } catch (Throwable ignored) {
        }
    }
}
