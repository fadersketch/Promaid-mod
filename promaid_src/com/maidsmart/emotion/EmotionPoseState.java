package com.maidsmart.emotion;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 情绪姿势服务端状态（v1.1.0 实测二百八十六）——摸头/抱抱触发时记录
 * 「女仆 → 姿势类型 + 结束 tick」，客户端通过 EmotionPosePacket 同步后
 * 在 TLM HardcodedAnimationManger 的最终骨骼覆盖层里播放姿势
 * （机制移植自 HeartPact：mixin playMaidAnimation/playGeckoMaidAnimation
 * 的 RETURN，覆写 head/arm 骨骼角度，非动画文件）。
 *
 * 游戏时间自动过期（摸头 40t/抱抱 80t），无需手动清理。
 */
public final class EmotionPoseState {
    public static final byte TYPE_PAT = 0;
    public static final byte TYPE_HUG = 1;

    public static final int PAT_DURATION_TICKS = 40;
    public static final int HUG_DURATION_TICKS = 80;

    /** 女仆 UUID → [type, expireAtGameTick] */
    private static final Map<UUID, long[]> POSES = new ConcurrentHashMap<>();

    private EmotionPoseState() {
    }

    public static void start(EntityMaid maid, byte type) {
        int duration = type == TYPE_HUG ? HUG_DURATION_TICKS : PAT_DURATION_TICKS;
        POSES.put(maid.m_20148_(), new long[]{type, maid.m_9236_().m_46467_() + duration});
    }

    /** 当前是否在指定姿势中（自动清理过期项） */
    public static boolean isActive(EntityMaid maid, byte type) {
        long[] pose = POSES.get(maid.m_20148_());
        if (pose == null) {
            return false;
        }
        if (maid.m_9236_().m_46467_() > pose[1]) {
            POSES.remove(maid.m_20148_());
            return false;
        }
        return pose[0] == type;
    }

    /** 审计：女仆卸载时清理（AiMemoryManager 遗忘链路） */
    public static void forget(UUID maidUuid) {
        POSES.remove(maidUuid);
    }
}
