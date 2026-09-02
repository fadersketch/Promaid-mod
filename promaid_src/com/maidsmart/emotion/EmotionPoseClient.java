package com.maidsmart.emotion;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 情绪姿势客户端状态（v1.1.0 实测二百八十六）——按墙钟记录每只女仆的
 * 姿势开始/结束时刻，EmotionPoseMixin 在 TLM 动画最终骨骼覆盖层里按
 * 进度（淡入→保持→淡出）播放摸头/抱抱姿势。
 *
 * 服务端只发一次同步包（开始时刻），客户端自行到期清理——丢包最坏结果
 * 是本次姿势不播放，无状态残留。
 */
public final class EmotionPoseClient {
    public static final byte TYPE_PAT = 0;
    public static final byte TYPE_HUG = 1;

    public static final long PAT_DURATION_MS = 2_000L;
    public static final long HUG_DURATION_MS = 4_000L;
    private static final long FADE_IN_MS = 300L;
    private static final long FADE_OUT_MS = 500L;

    /** 女仆 UUID → 姿势开始墙钟毫秒 */
    private static final Map<UUID, Long> PET_START = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> HUG_START = new ConcurrentHashMap<>();

    private EmotionPoseClient() {
    }

    /** S2C 包到达：记录姿势开始时刻 */
    public static void onPose(int entityId, byte type) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.m_91087_();
        if (mc.f_91074_ == null
                || !(mc.f_91074_.m_9236_() instanceof net.minecraft.client.multiplayer.ClientLevel level)) {
            return;
        }
        net.minecraft.world.entity.Entity e = level.m_6815_(entityId);
        if (!(e instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid)) {
            return;
        }
        UUID uuid = maid.m_20148_();
        long now = System.currentTimeMillis();
        if (type == TYPE_HUG) {
            HUG_START.put(uuid, now);
            PET_START.remove(uuid); // 抱抱优先，避免两姿势叠加打架
        } else {
            PET_START.put(uuid, now);
        }
    }

    /** 姿势进度 0~1（淡入淡出插值；未激活返回 0）。 */
    public static float petProgress(UUID maidUuid) {
        return progress(PET_START, maidUuid, PAT_DURATION_MS);
    }

    public static float hugProgress(UUID maidUuid) {
        return progress(HUG_START, maidUuid, HUG_DURATION_MS);
    }

    private static float progress(Map<UUID, Long> map, UUID uuid, long duration) {
        Long start = map.get(uuid);
        if (start == null) {
            return 0.0f;
        }
        long elapsed = System.currentTimeMillis() - start;
        if (elapsed >= duration) {
            map.remove(uuid);
            return 0.0f;
        }
        long remain = duration - elapsed;
        float in = Math.min(1.0f, elapsed / (float) FADE_IN_MS);
        float out = Math.min(1.0f, remain / (float) FADE_OUT_MS);
        return Math.min(in, out);
    }
}
