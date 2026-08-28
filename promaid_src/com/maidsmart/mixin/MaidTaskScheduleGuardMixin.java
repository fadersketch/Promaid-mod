package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.1.0 实测一百三十六/一百三十八：排班开启的女仆【禁止手动改任务与工作模式】——
 * 源头拦截。
 *
 * 背景（用户："理论上排班中的女仆玩家不应该可以手动修改她的任务的"、"排班名义上
 * 不允许玩家切换模式，但 TLM 本身能切，禁用调整变成无用"）：排班书自己的快捷设置/
 * 批量应用早已拦截，但 TLM 原版 GUI 是【直接调 EntityMaid.setTask / setSchedule】，
 * 绕过拦截——手动改动与调度重试互相顶（任务），或工作模式（DAY/NIGHT/ALL 作息）被
 * 玩家改走、段内去抖键命中后排班不纠正。
 *
 * 判定：排班开启（ScheduleData.isOn）且非【自动系统】的调用（ScheduleSwitchGuard
 * 内部标记——排班调度器与主动战斗系统的 setTask/setSchedule 都用 runInternal 包住）
 * → 直接拒绝。玩家手动改任务、改作息在源头被掐掉；调度/战斗的正常切换、未开排班
 * 时的照常调整都不受影响。
 *
 * 反馈：拒绝时给女仆气泡说明（addTextChatBubble 受 ChatBubbleLimitMixin 5 秒限频，
 * 连续点击不刷屏），并落一条排班日志。
 */
@Mixin(EntityMaid.class)
public abstract class MaidTaskScheduleGuardMixin {
    @Inject(method = "setTask", at = @At("HEAD"), cancellable = true)
    private void maidsmart$blockManualTaskWhileScheduled(IMaidTask target, CallbackInfo ci) {
        try {
            if (blockManual((EntityMaid) (Object) this)) {
                String uid = target == null ? "null"
                        : (target.getUid() == null ? "?" : target.getUid().toString());
                reject((EntityMaid) (Object) this, "任务", "目标 " + uid);
                ci.cancel();
            }
        } catch (Throwable ignored) {
        }
    }

    /** v1.1.0 实测一百三十八：工作模式（作息 DAY/NIGHT/ALL）同样上锁——TLM 原版 GUI
     *  切作息直接调 setSchedule，此前只守了 setTask，作息调整形同虚设 */
    @Inject(method = "setSchedule", at = @At("HEAD"), cancellable = true)
    private void maidsmart$blockManualScheduleWhileScheduled(MaidSchedule schedule, CallbackInfo ci) {
        try {
            if (blockManual((EntityMaid) (Object) this)) {
                reject((EntityMaid) (Object) this, "工作模式",
                        schedule == null ? "null" : schedule.toString());
                ci.cancel();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 排班开启 + 非自动系统调用 = 手动调整，应拦截 */
    private static boolean blockManual(EntityMaid maid) {
        if (!com.maidsmart.schedule.ScheduleData.isOn(maid)) {
            return false; // 未开排班：照常放行
        }
        return !com.maidsmart.schedule.ScheduleSwitchGuard.isInternalSetTask();
    }

    /** 拦截时：日志 + 女仆气泡（统一措辞覆盖任务与作息） */
    private static void reject(EntityMaid maid, String what, String detail) {
        com.maidsmart.tool.PromaidLog.log("排班",
                com.maidsmart.tool.PromaidLog.nameOf(maid)
                        + " 手动修改" + what + "被拦截（排班开启，由日程表管理）：" + detail);
        try {
            maid.getChatBubbleManager().addTextChatBubble(
                    "排班中呢，工作模式和任务都由日程表管理~想手动调整，先关闭我的排班吧");
        } catch (Throwable ignored) {
        }
    }
}