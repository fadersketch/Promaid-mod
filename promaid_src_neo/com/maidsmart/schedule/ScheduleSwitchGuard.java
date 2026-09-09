package com.maidsmart.schedule;

import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 内部 setTask 标记（v1.1.0 实测一百三十三，借鉴 TLM-Sincerely 的
 * AutoWorkInternalSetTaskGuard）。
 *
 * 排班调度器是【唯一】允许自动调用 {@code EntityMaid.setTask} 的组件。其它系统
 * （战斗主动切换、蓝图建造下达、一键/批量应用、LLM 工具等）各自独立调用 setTask，
 * 相互之间没有"这条任务的变更是谁写的"这一层可判定信息——一旦两条自动链路同刻
 * 触发，就会出现 A 系统刚切完、B 系统又切回去的互相覆盖。
 *
 * 本类用线程局部（ThreadLocal）在排班自己的 setTask 调用期间打上标记：将来任何
 * 需要区分"排班的自动切换"与"外部/玩家手动切换"的 Mixin、守卫或兼容层，都可以
 * 通过 {@link #isInternalSetTask()} / {@link #currentMaidUuid()} /
 * {@link #currentTargetTask()} 识别，不必再靠"当前任务是不是某个虚拟任务"这类
 * 脆弱的启发式推断。
 *
 * 用法：用 {@link #runInternal(UUID, ResourceLocation, Runnable)} 包住
 * {@code maid.setTask(...)}——即使调用抛异常，标记也会在 finally 里被清掉。
 */
public final class ScheduleSwitchGuard {
    /** 单线程当前"内部 setTask 调用上下文"——非 null 即"正在排班触发的 setTask 内" */
    private static final ThreadLocal<CallContext> CURRENT = new ThreadLocal<>();

    private ScheduleSwitchGuard() {
    }

    /** 当前线程是否正处在排班触发的 setTask 调用内 */
    public static boolean isInternalSetTask() {
        return CURRENT.get() != null;
    }

    /** 正在进行的内部 setTask 的宿主女仆 UUID（无则 null） */
    public static UUID currentMaidUuid() {
        CallContext ctx = CURRENT.get();
        return ctx == null ? null : ctx.maidUuid();
    }

    /** 正在进行的内部 setTask 的目标任务 UID（无则 null） */
    public static ResourceLocation currentTargetTask() {
        CallContext ctx = CURRENT.get();
        return ctx == null ? null : ctx.targetTask();
    }

    /** 带内部标记执行 action，finally 里清除标记（异常不外漏） */
    public static void runInternal(UUID maidUuid, ResourceLocation targetTask, Runnable action) {
        CURRENT.set(new CallContext(maidUuid, targetTask));
        try {
            action.run();
        } finally {
            CURRENT.remove();
        }
    }

    /**
     * 防御性清理：万一前面某次调用在 finally 之前异常逃逸（shouldn't happen，但
     * 线程池/重入场景要兜底），在服务端 tick 开头清掉陈旧标记，防止泄漏到同线程的
     * 其它 setTask 调用点上。清到泄漏时记一条日志。
     */
    public static void clearIfStale(String source) {
        if (CURRENT.get() != null) {
            com.maidsmart.tool.PromaidLog.log("排班", "[手动排查] 清理残留的内部 setTask 标记，来源=" + source);
            CURRENT.remove();
        }
    }

    private record CallContext(UUID maidUuid, ResourceLocation targetTask) {
    }
}