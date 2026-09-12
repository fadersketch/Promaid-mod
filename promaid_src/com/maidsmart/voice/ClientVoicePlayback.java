package com.maidsmart.voice;

import com.github.tartaricacid.touhoulittlemaid.client.sound.data.MaidSoundInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.sound.PlaySoundEvent;

/**
 * v1.1.0 实测四百二十：内置日语语音包的客户端播放标记 + 「播放期间暂压 TLM 原生语音包」。
 *
 * 用户需求原话：「播放此语音时会暂时卡掉 tlm 原来的语音包，播放完成后解除，
 * 有比原生语音包更高的优先级。」
 *
 * 实现：
 * - 播放：客户端 {@code PromaidVoiceSoundInstance.playFromPacket} 在播放前调用
 *   {@link #beginSuppress(long)} 开启压制窗口（截止时间戳，按音频字节估算时长）。
 * - 压制：监听 PlaySoundEvent；窗口内把 TLM 原生语音包实例 setSound(null)。
 *   1.20.1 的原生语音包只有 MaidSoundInstance（女仆语音/音效）；1.21.1 另有
 *   MaidSoundInstanceAtPos（定点音效），用类名字符串判定以免 1.20.1 编译不过。
 *   TTS 本体 MaidAISoundInstance 不压（那是要放的声音）。
 * - 解除：窗口到期自动失效。测试音（isTestSound）始终放行（语音包界面试听不受影响）。
 *
 * 仅客户端（专服不加载，避免触碰客户端类型）。
 */
@OnlyIn(Dist.CLIENT)
public final class ClientVoicePlayback {
    /** 压制截止时间（System.currentTimeMillis）；过去 = 不压制 */
    private static volatile long suppressUntilMs = 0L;
    private static volatile long lastLogMs = 0L;

    private ClientVoicePlayback() {
    }

    /** 标记"内置语音开始播放"：按音频字节估算时长并开启/顺延压制窗口 */
    public static void beginSuppress(long durationMs) {
        long until = System.currentTimeMillis() + Math.max(500L, durationMs);
        if (until > suppressUntilMs) {
            suppressUntilMs = until; // 连续两条内置语音 → 压制顺延，不缩短
        }
    }

    /** 当前是否处于压制窗口内 */
    public static boolean isSuppressing() {
        return System.currentTimeMillis() < suppressUntilMs;
    }

    /** 按 ogg 字节数估算播放时长（毫秒）——保守取值，宁多压不重叠 */
    public static long estimateDurationMs(int bytes) {
        if (bytes <= 0) {
            return 500L;
        }
        // vorbis q4 32kHz 单声道实测约 56 kbit/s ≈ 7000 字节/秒
        long ms = (long) ((bytes / 7000.0) * 1000.0);
        return Math.max(500L, Math.min(20000L, ms)) + 500L; // 额外 0.5 秒收尾余量
    }

    /**
     * 压制 TLM 原生语音包：内置语音播放窗口内，原生女仆语音/音效一律不发声。
     */
    public static void onPlaySound(PlaySoundEvent event) {
        if (!com.maidsmart.config.MaidSmartConfig.TTS_JAR_PACK_MUTE_NATIVE.get()) {
            return;
        }
        if (!isSuppressing()) {
            return;
        }
        SoundInstance s = event.getSound();
        if (s == null) {
            return;
        }
        if (s instanceof MaidSoundInstance msi) {
            if (msi.isTestSound()) {
                return; // 试听不受压制
            }
            event.setSound(null);
            throttleLog("MaidSoundInstance");
            return;
        }
        // 1.21.1 才有 MaidSoundInstanceAtPos（1.20.1 无此类）——按类名判定，跨版本安全
        String cn = s.getClass().getName();
        if (cn.endsWith("MaidSoundInstanceAtPos")) {
            event.setSound(null);
            throttleLog("MaidSoundInstanceAtPos");
        }
    }

    private static void throttleLog(String kind) {
        long now = System.currentTimeMillis();
        if (now - lastLogMs > 5000L) {
            lastLogMs = now;
            com.mojang.logging.LogUtils.getLogger().info(
                    "promaid 内置语音压制原生语音包: {}（窗口剩余 {}ms）",
                    kind, Math.max(0L, suppressUntilMs - now));
        }
    }

    /** 客户端 tick 兜底：未进世界时清窗口，避免残留压制 */
    public static void onClientTick() {
        Minecraft mc = Minecraft.m_91087_();
        if (mc == null || mc.f_91073_ == null) {
            suppressUntilMs = 0L;
        }
    }
}
