package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.schedule.ScheduleData;
import com.maidsmart.schedule.ScheduleSwitchGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.1.0 实测一百三十六：排班中的女仆【禁止手动改任务】——源头拦截。
 *
 * 背景（用户："理论上排班中的女仆玩家不应该可以手动修改她的任务的"）：排班书
 * 自己的快捷设置/批量应用早已拦截，但 TLM 原版 GUI、蓝图书切任务、LLM 指令
 * （smart_attack / smart_build）都是【直接调 EntityMaid.setTask】，绕过拦截——
 * 手动改动与调度重试互相顶，表现为"排班卡死"。
 *
 * 判定：排班开启（ScheduleData.isOn）且非【自动系统】的 setTask 调用
 * （ScheduleSwitchGuard 内部标记——排班调度器与主动战斗系统的 setTask 都用
 * runInternal 包住）→ 直接拒绝。玩家手动改任务在源头被掐掉，调度/战斗的正常
 * 切换、以及未开排班时的照常改动都不受影响。
 *
 * 反馈：拒绝时给女仆一个气泡说明（addTextChatBubble 受 ChatBubbleLimitMixin
 * 5 秒限频，连续点击不会刷屏），并落一条排班日志。
 */
@Mixin(EntityMaid.class)
public abstract class MaidTaskScheduleGuardMixin {
    @Inject(method = "setTask", at = @At("HEAD"), cancellable = true)
    private void maidsmart$blockManualTaskWhileScheduled(IMaidTask target, CallbackInfo ci) {
        try {
            EntityMaid maid = (EntityMaid) (Object) this;
            if (!com.maidsmart.schedule.ScheduleData.isOn(maid)) {
                return; // 未开排班：照常放行
            }
            if (com.maidsmart.schedule.ScheduleSwitchGuard.isInternalSetTask()) {
                return; // 排班/战斗等自动系统的 setTask：放行
            }
            String uid = target == null ? "null"
                    : (target.getUid() == null ? "?" : target.getUid().toString());
            com.maidsmart.tool.PromaidLog.log("排班",
                    com.maidsmart.tool.PromaidLog.nameOf(maid)
                            + " 手动修改任务被拦截（排班开启，任务由日程表管理）：目标 " + uid);
            try {
                maid.getChatBubbleManager().addTextChatBubble(
                        "排班中呢，任务由日程表管理~想手动改任务，先关闭我的排班吧");
            } catch (Throwable ignored) {
            }
            ci.cancel();
        } catch (Throwable ignored) {
        }
    }
}