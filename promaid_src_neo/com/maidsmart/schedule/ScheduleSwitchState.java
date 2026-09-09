package com.maidsmart.schedule;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 反向切换抑制状态（v1.1.0 实测一百三十三，借鉴 TLM-Sincerely 的 MaidSwitchState）。
 *
 * 目标：拦截短窗口内的 A→B→A→B 快速横跳。排班的时间段切换本来就相隔约 2000 tick
 * （一个班次槽位），正常交替（A 段→B 段→A 段）不会被判为"振荡"；真正要拦的是
 * 战斗还原/可用性重试压任务导致的秒级来回覆盖。
 *
 * 状态按女仆 UUID 存内存（对象极小，女仆数量天然受限），不落盘——重启即重置，
 * 反向抑制只关心"短窗口内反复"，跨越重启没有意义。
 */
public final class ScheduleSwitchState {
    private static final Map<UUID, State> STATES = new HashMap<>();

    private ScheduleSwitchState() {
    }

    /**
     * 判断这一次 from→to 是否应被抑制。
     *
     * @param maidUuid  宿主女仆
     * @param from      当前任务 UID（未被识别时为 "null" 字符串）
     * @param to        目标任务 UID
     * @param tick      当前游戏时间 tick
     * @param window    反向窗口（tick）：上一次切换与本次切换间隔 <= window 才算"来回"
     * @param threshold 达到多少次反向才压制（与 TLM-Sincerely 同语义）
     * @param cooldown  压制后的冷却时长（tick）
     */
    public static boolean shouldSuppressReverseSwitch(UUID maidUuid, String from, String to, long tick,
                                                      int window, int threshold, int cooldown) {
        State s = STATES.get(maidUuid);
        if (s == null) {
            return false;
        }
        if (cooldownActive(s, tick)) {
            return true;
        }
        boolean reverse = isReverse(s, from, to, tick, window);
        if (!reverse || s.reverseCount + 1 < threshold) {
            return false;
        }
        s.reverseCooldownEndTick = tick + cooldown;
        s.reverseCount = 0;
        return true;
    }

    /** 记录一次已发生的切换（from→to），维护反向计数的状态 */
    public static void recordSwitch(UUID maidUuid, String from, String to, long tick, int window) {
        State s = STATES.computeIfAbsent(maidUuid, unused -> new State());
        guardSize();
        boolean reverse = isReverse(s, from, to, tick, window);
        s.reverseCount = reverse ? s.reverseCount + 1 : 0;
        s.lastFrom = from;
        s.lastTo = to;
        s.lastSwitchTick = tick;
    }

    /** 是否仍在反向冷却期内；冷却到期自动复位（false 且清计数） */
    private static boolean cooldownActive(State s, long tick) {
        if (s.reverseCooldownEndTick == Long.MIN_VALUE) {
            return false;
        }
        if (tick >= s.reverseCooldownEndTick) {
            s.reverseCooldownEndTick = Long.MIN_VALUE;
            s.reverseCount = 0;
            return false;
        }
        return true;
    }

    private static boolean isReverse(State s, String from, String to, long tick, int window) {
        return s.lastFrom != null && s.lastTo != null
                && from.equals(s.lastTo) && to.equals(s.lastFrom)
                && s.lastSwitchTick != Long.MIN_VALUE
                && tick >= s.lastSwitchTick
                && (tick - s.lastSwitchTick) <= window;
    }

    /** 女仆卸载/被移除时清理（防内存里残留无关状态；不调也只是占几个字节） */
    public static void clearMaid(UUID maidUuid) {
        STATES.remove(maidUuid);
    }

    /** v1.1.0 实测一百七十六（移植 TLM-Sincerely MaidSwitchState.canSwitchNormally）：
     *  距上次切换不足 minHoldTicks → 禁止切换（绝对最短持有期）。与反向抑制互补：
     *  反向抑制拦 A→B→A 横跳，本方法拦"任何切换太快"（段边界秒切/战斗还原压任务
     *  导致的连切——最短持有保证每次切换落地后有一个稳定窗口）。minHoldTicks<=0 =
     *  关闭。 */
    public static boolean canSwitchNormally(UUID maidUuid, long currentTick, int minHoldTicks) {
        if (minHoldTicks <= 0) {
            return true;
        }
        State s = STATES.get(maidUuid);
        return s == null || s.lastSwitchTick == Long.MIN_VALUE
                || currentTick - s.lastSwitchTick >= minHoldTicks;
    }

    /** 防御：会话内女仆 UUID 数量有限，正常到不了；极端情况兜底清空防无限膨胀 */
    private static void guardSize() {
        if (STATES.size() > 4096) {
            STATES.clear();
        }
    }

    private static final class State {
        String lastFrom;
        String lastTo;
        long lastSwitchTick = Long.MIN_VALUE;
        int reverseCount;
        long reverseCooldownEndTick = Long.MIN_VALUE;
    }
}