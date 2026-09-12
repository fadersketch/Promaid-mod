package com.maidsmart.client;

import com.github.tartaricacid.touhoulittlemaid.client.sound.data.MaidAISoundInstance;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * v1.1.0 实测四百二十：内置日语语音包的客户端播放（自管声音实例）。
 *
 * 用户需求：「打包放进 .jar 里……触发了这些系统消息之后，会自动播放这个语音。」
 *
 * 直接继承 TLM 的 MaidAISoundInstance——它已实现 OGG/Opus/MP3 三种格式解码
 * （getStream 按字节头判定），复用即可，无需自写解码。
 *
 * 音量：TLM 构造把音量硬编码 1.0（再经 MaidTtsVolumeMixin 乘「TTS 音量倍率」）。
 * 本类在构造后把音量覆盖为「TTS 倍率 × 内置包倍率」，让用户能单独调内置语音包音量
 * （用户要求"可以调整音量大小"）。1.20.1 音量字段 = AbstractSoundInstance.f_119573_。
 */
@OnlyIn(Dist.CLIENT)
public class PromaidVoiceSoundInstance extends MaidAISoundInstance {
    /** 当前实际播放音量（供客户端 tick 诊断/未来扩展用） */
    private final float appliedVolume;

    public PromaidVoiceSoundInstance(EntityMaid maid, byte[] data, float volume) {
        super(maid, data);
        this.appliedVolume = volume;
        try {
            // 直接覆盖 protected 音量字段（不走 mixin；只影响本实例）
            this.f_119573_ = Math.max(0.0f, volume);
        } catch (Throwable ignored) {
        }
    }

    public float appliedVolume() {
        return this.appliedVolume;
    }

    /**
     * S2C 包入口：服务端下发了 {女仆实体id, jar 内文件名} → 本地取字节播放，
     * 并按音频时长开启「压制 TLM 原生语音包」窗口。
     */
    public static void playFromPacket(int maidId, String file) {
        try {
            if (!com.maidsmart.config.MaidSmartConfig.TTS_JAR_PACK_ENABLED.get()) {
                return;
            }
            Minecraft mc = Minecraft.m_91087_();
            if (mc == null || mc.f_91073_ == null) {
                return;
            }
            net.minecraft.world.entity.Entity e = mc.f_91073_.m_6815_(maidId);
            if (!(e instanceof EntityMaid maid)) {
                return;
            }
            byte[] data = com.maidsmart.voice.JarVoicePack.bytesOfKey(file);
            if (data == null || data.length == 0) {
                return;
            }
            // 开启压制窗口（按字节估算时长）——必须在 play 之前，让首个原生事件就被挡
            if (com.maidsmart.config.MaidSmartConfig.TTS_JAR_PACK_MUTE_NATIVE.get()) {
                com.maidsmart.voice.ClientVoicePlayback.beginSuppress(
                        com.maidsmart.voice.ClientVoicePlayback.estimateDurationMs(data.length));
            }
            float vol = (float) (double) com.maidsmart.config.MaidSmartConfig.TTS_VOLUME_MULTIPLIER.get()
                    * (float) (double) com.maidsmart.config.MaidSmartConfig.TTS_JAR_PACK_VOLUME.get();
            mc.m_91106_().m_120367_(new PromaidVoiceSoundInstance(maid, data, vol));
        } catch (Throwable ignored) {
        }
    }
}
