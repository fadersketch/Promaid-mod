package com.maidsmart.schedule;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraft.server.level.ServerLevel;

/**
 * 排班切换引擎（v1.1.0 实测一百七十六，镜像 TLM-Sincerely TaskSwitchDecisionEngine：
 * 全 mod 排班 setTask/setSchedule 的【单一切换出口】）。ScheduleManager.applyNow 保留
 * 全部前置门（总开关/home 锚定/宽限/自保/战斗让位/空表/休息/去抖/重试/外部改动尊重），
 * 真正"切"的动作统一走这里——门顺序（借鉴 TLM-Sincerely evaluateNormalSwitch 的
 * 分层决策）：
 *   已在目标任务 → 跳过（免无意义 refreshBrain）
 *   → 可用性门（isAvailable 完整检测 / isEnabled 硬闸，配置可切）
 *   → 最短持有（MaidSwitchState.canSwitchNormally——防段边界秒切/战斗还原压任务连切）
 *   → 反向抑制（ScheduleSwitchState——A→B→A 横跳冷却）
 *   → 兼容门（ScheduleCompatService——BLOCKED 任务不自动切）
 *   → runInternal setSchedule/setTask → 读回校验 → recordSwitch
 */
public final class ScheduleSwitchEngine {

    private ScheduleSwitchEngine() {
    }

    /** 一次段应用的结果。success=false 时 message 给原因；warning 为"切了但可能不干活"提示 */
    public static final class Result {
        public final boolean success;
        public final boolean soft;
        public final String message;
        public final String warning;

        private Result(boolean success, boolean soft, String message, String warning) {
            this.success = success;
            this.soft = soft;
            this.message = message;
            this.warning = warning;
        }

        static Result ok() {
            return new Result(true, false, null, null);
        }

        static Result okWithWarning(String warning) {
            return new Result(true, false, null, warning);
        }

        static Result soft(String message) {
            return new Result(false, true, message, null);
        }

        static Result fail(String message) {
            return new Result(false, false, message, null);
        }
    }

    /**
     * 应用一个日程段：工作模式（setSchedule）+ 任务（setTask，全套门）。
     * 纯时间判定：段任务 UID 就是目标，不做环境检测（TLM-Sincerely 的检测子系统
     * 不移植——用户："改成日程表里的以时间为判定"）。
     *
     * @return 成功（含"已在该任务上"）/ soft fail（没活/持有/反向/兼容——主动暂不切）/
     *         硬失败（UID 非法/任务不存在/守卫拒绝/异常——需重试）
     */
    public static Result applySegment(EntityMaid maid, ServerLevel level,
                                      ScheduleData.Segment seg, long nowTick) {
        String fail = null;
        boolean soft = false;
        String warning = null;
        try {
            // —— 工作模式（0=DAY 早班 / 1=NIGHT 晚班 / 2=ALL 全天）——
            // v1.1.0 实测一百三十八：setSchedule 也打内部标记（排班守卫 mixin 拦 TLM
            // GUI 手动切作息，但不许误伤排班自己设置工作时间）
            var modes = com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule.values();
            if (seg.mode() >= 0 && seg.mode() < modes.length) {
                com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule chosen =
                        modes[seg.mode()];
                ScheduleSwitchGuard.runInternal(maid.m_20148_(), null,
                        () -> maid.setSchedule(chosen));
            } else {
                fail = "段模式越界 mode=" + seg.mode();
            }
            // —— 任务 ——
            if (fail == null && seg.taskUid() != null && !seg.taskUid().isEmpty()) {
                // v1.1.0 实测十六（审查 P1-5）：非法 taskUid 防护——taskUid 来自客户端包
                // （SchedSavePacket/QuickApplyPacket），恶意包/损坏 NBT 的非法串会让 parse
                // 抛 ResourceLocationException 崩服务端。m_135830_ = isValidResourceLocation
                // 预检（parse 用同一套校验，不抛异常）+ try/catch 兜底。
                try {
                    if (!net.minecraft.resources.ResourceLocation.m_135830_(seg.taskUid())) {
                        fail = "段任务 UID 非法 '" + seg.taskUid() + "'（保留模式，跳过任务）";
                    } else {
                        var found = TaskManager.findTask(
                                net.minecraft.resources.ResourceLocation.parse(seg.taskUid()));
                        if (found.isPresent()) {
                            var target = found.get();
                            String fromUid = (maid.getTask() != null && maid.getTask().getUid() != null)
                                    ? maid.getTask().getUid().toString() : "null";
                            String toUid = target.getUid() == null ? "null" : target.getUid().toString();
                            if (fromUid.equals(toUid)) {
                                // 已经在该任务上：无需重设（避免无意义 refreshBrain 重建 AI）
                            } else if (com.maidsmart.config.MaidSmartConfig.MISC_SCHEDULE_AVAILABILITY_CHECK.get()
                                    ? !ScheduleTaskAvailability.isAvailable(maid, target)
                                    : !ScheduleTaskAvailability.isEnabled(maid, target)) {
                                // v1.1.0 实测一百三十三 ①：切换前可用性检测——没活不切
                                // v1.1.0 实测一百七十：默认只查 isEnable 硬闸（任务状态必须
                                // 跟着时间段落真实切换；软探测可在面板调回）
                                soft = true;
                                fail = "目标任务 '" + seg.taskUid() + "' 当前不可用（任务被禁用/无法切换，约 10 秒后重试）";
                            } else if (!ScheduleSwitchState.canSwitchNormally(maid.m_20148_(), nowTick,
                                    com.maidsmart.config.MaidSmartConfig.MISC_SCHEDULE_MIN_HOLD_TICKS.get())) {
                                // v1.1.0 实测一百七十六（移植 TLM-Sincerely canSwitchNormally）：
                                // 最短持有期——刚切过（不足 60 tick），段再变也不连切
                                soft = true;
                                fail = "最短持有期内（距上次切换不足 "
                                        + com.maidsmart.config.MaidSmartConfig.MISC_SCHEDULE_MIN_HOLD_TICKS.get()
                                        + " tick），暂不切换（段 " + seg.taskUid() + "）";
                            } else if (ScheduleSwitchState.shouldSuppressReverseSwitch(maid.m_20148_(),
                                    fromUid, toUid, nowTick,
                                    com.maidsmart.config.MaidSmartConfig.MISC_SCHEDULE_REVERSE_WINDOW_TICKS.get(),
                                    com.maidsmart.config.MaidSmartConfig.MISC_SCHEDULE_REVERSE_THRESHOLD.get(),
                                    com.maidsmart.config.MaidSmartConfig.MISC_SCHEDULE_REVERSE_COOLDOWN_TICKS.get())) {
                                // v1.1.0 实测一百三十三 ②：反向切换抑制——A→B→A 横跳冷却
                                soft = true;
                                fail = "反向切换抑制：'" + fromUid + "' ↔ '" + toUid + "' 短窗口内反复横跳";
                            } else if (!ScheduleCompatService.isAutoSwitchable(target)) {
                                // v1.1.0 实测一百七十六（移植 TLM-Sincerely AutoWorkCompatService）：
                                // 兼容门——BLOCKED 任务不自动切（会卡 AI/纯交互/已知问题）
                                soft = true;
                                fail = "任务 '" + seg.taskUid() + "' 被兼容分类禁止自动切换（BLOCKED）";
                            } else {
                                // v1.1.0 实测一百七十六：UNSUPPORTED 兼容提示——切但提醒
                                //（时间驱动语义：用户明确写进日程表就必须切）
                                if (ScheduleCompatService.classify(target)
                                        == ScheduleCompatService.Classification.UNSUPPORTED) {
                                    warning = "任务 '" + seg.taskUid()
                                            + "' 兼容分类 UNSUPPORTED——可能不会正常干活，可考虑换任务";
                                }
                                // v1.1.0 实测一百三十三 ③：内部 setTask 标记——排班的自动切换
                                // 与其它系统（战斗/蓝图/一键应用/LLM）的 setTask 区分开，防互相覆盖
                                ScheduleSwitchGuard.runInternal(maid.m_20148_(), target.getUid(),
                                        () -> maid.setTask(target));
                                // v1.1.0 实测一百二十九：应用后读回校验——TLM setTask 有守卫
                                // （睡眠/活动/不可换任务状态）时会静默拒绝，读回对比当场暴露
                                String cur = (maid.getTask() != null && maid.getTask().getUid() != null)
                                        ? maid.getTask().getUid().toString() : "null";
                                if (!seg.taskUid().equals(cur)) {
                                    fail = "【任务未生效】目标 '" + seg.taskUid() + "' 应用后任务仍 = "
                                            + cur + "（被 TLM 守卫/睡眠/活动拒绝？）";
                                } else {
                                    ScheduleSwitchState.recordSwitch(maid.m_20148_(), fromUid, toUid,
                                            nowTick,
                                            com.maidsmart.config.MaidSmartConfig.MISC_SCHEDULE_REVERSE_WINDOW_TICKS.get());
                                }
                            }
                        } else {
                            fail = "段任务 '" + seg.taskUid() + "' 不存在（模组任务未装/任务被删）";
                        }
                    }
                } catch (Exception e) {
                    fail = "段任务应用异常：" + e;
                }
            }
        } catch (Exception e) {
            fail = "段应用异常：" + e;
        }
        if (fail == null) {
            return warning == null ? Result.ok() : Result.okWithWarning(warning);
        }
        return soft ? Result.soft(fail) : Result.fail(fail);
    }
}
