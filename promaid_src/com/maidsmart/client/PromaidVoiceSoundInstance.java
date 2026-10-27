package com.maidsmart.client;

import com.github.tartaricacid.touhoulittlemaid.client.sound.data.MaidAISoundInstance;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * v1.1.0 实测四百二十：内置日语语音包的客户端播放（自管声音实例）。
 *
 * 需求：「打包放进 .jar 里……触发了这些系统消息之后，会自动播放这个语音。」
 *
 * 直接继承 TLM 的 MaidAISoundInstance——它已实现 OGG/Opus/MP3 三种格式解码
 * （getStream 按字节头判定），复用即可，无需自写解码。
 *
 * 音量：TLM 构造把音量硬编码 1.0（再经 MaidTtsVolumeMixin 乘「TTS 音量倍率」）。
 * 本类在构造后把音量覆盖为「TTS 倍率 × 内置包倍率」，让玩家能单独调内置语音包音量
 * （要求"可以调整音量大小"）。1.20.1 音量字段 = AbstractSoundInstance.f_119573_。
 */
@OnlyIn(Dist.CLIENT)
public class PromaidVoiceSoundInstance extends MaidAISoundInstance {
    /** 当前实际播放音量（供客户端 tick 诊断/未来扩展用） */
    private final float appliedVolume;
    private final EntityMaid maid;
    /** 叫醒语音：服务端已经 stopSleeping，客户端姿态同步可能晚一拍，强制放行 */
    private final boolean forceWhileSleeping;

    public PromaidVoiceSoundInstance(EntityMaid maid, byte[] data, float volume) {
        this(maid, data, volume, false);
    }

    public PromaidVoiceSoundInstance(EntityMaid maid, byte[] data, float volume,
                                     boolean forceWhileSleeping) {
        super(maid, data);
        this.maid = maid;
        this.forceWhileSleeping = forceWhileSleeping;
        this.appliedVolume = volume;
        try {
            // 直接覆盖 protected 音量字段（不走 mixin；只影响本实例）
            this.f_119573_ = Math.max(0.0f, volume);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidVoiceSoundInstance", ignored);
        }
    }

    public float appliedVolume() {
        return this.appliedVolume;
    }

    /**
     * 【R1149】父类 {@code EntityBoundSoundInstance} 在实体 silent 时拒绝播放；
     * TLM 让女仆睡觉时会把实体设为 silent，刚叫醒的这一拍客户端可能还没解除。
     * 内置语音只认"真的睡在床上"（isSleeping）；叫醒语音由服务端先 stopSleeping，
     * 这里再强制放行，避免客户端姿态晚一拍把整条语音吞掉。
     */
    @Override
    public boolean m_7767_() {
        return this.maid == null || this.forceWhileSleeping || !this.maid.m_5803_();
    }

    /** 叫醒相关音频（wake_*.ogg）必须在"她仍躺在床上"这一拍也能播出 */
    private static boolean isWakeSound(String file) {
        return file != null && file.startsWith("wake_");
    }

    private static final java.util.List<Pending> PENDING = new java.util.ArrayList<>();

    private record Pending(int maidId, String file, long expireAt) {}

    /**
     * S2C 包入口：服务端下发了 {女仆实体id, jar 内文件名} → 本地取字节播放，
     * 并按音频时长开启「压制 TLM 原生语音包」窗口。
     */
    public static void playFromPacket(int maidId, String file) {
        playOrQueue(maidId, file, true);
    }

    private static void playOrQueue(int maidId, String file, boolean allowQueue) {
        try {
            if (!com.maidsmart.config.MaidSmartConfig.TTS_JAR_PACK_ENABLED.get()) {
                return;
            }
            Minecraft mc = Minecraft.m_91087_();
            if (mc == null || mc.f_91073_ == null) {
                if (allowQueue) {
                    queue(maidId, file);
                }
                return;
            }
            net.minecraft.world.entity.Entity e = mc.f_91073_.m_6815_(maidId);
            if (!(e instanceof EntityMaid maid)) {
                // 【R1149】叫醒/收放魂符会在同一秒内换实体，包可能比新实体先到；
                // 旧版直接丢弃 = 语音静默。这里排队重试 3 秒。
                if (allowQueue) {
                    queue(maidId, file);
                }
                return;
            }
            byte[] data = com.maidsmart.voice.JarVoicePack.bytesOfKey(file);
            if (data == null || data.length == 0) {
                com.maidsmart.tool.PromaidLog.log("内置语音", "jar 内缺少音频：" + file);
                return;
            }
            // 开启压制窗口（按字节估算时长）——必须在 play 之前，让首个原生事件就被挡
            if (com.maidsmart.config.MaidSmartConfig.TTS_JAR_PACK_MUTE_NATIVE.get()) {
                com.maidsmart.voice.ClientVoicePlayback.beginSuppress(
                        com.maidsmart.voice.ClientVoicePlayback.estimateDurationMs(data.length));
            }
            float vol = (float) (double) com.maidsmart.config.MaidSmartConfig.TTS_VOLUME_MULTIPLIER.get()
                    * (float) (double) com.maidsmart.config.MaidSmartConfig.TTS_JAR_PACK_VOLUME.get();
            mc.m_91106_().m_120367_(new PromaidVoiceSoundInstance(maid, data, vol, isWakeSound(file)));
            com.maidsmart.tool.PromaidLog.log("内置语音", "播放 " + file + " 音量=" + vol);
        } catch (Throwable e) {
            com.maidsmart.tool.PromaidLog.log("内置语音", "播放失败 " + file + "：" + e);
        }
    }

    private static void queue(int maidId, String file) {
        long now = System.currentTimeMillis();
        PENDING.removeIf(p -> p.expireAt() < now);
        if (PENDING.size() >= 8) {
            PENDING.remove(0);
        }
        PENDING.add(new Pending(maidId, file, now + 3000L));
        com.maidsmart.tool.PromaidLog.log("内置语音",
                "客户端还没找到女仆实体，排队重试：" + file + " (#" + maidId + ")");
    }

    /** 每客户端 tick 调一次：实体随后才同步到位时补播排队的语音 */
    public static void tickPending() {
        if (PENDING.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        PENDING.removeIf(p -> p.expireAt() < now);
        Minecraft mc = Minecraft.m_91087_();
        if (PENDING.isEmpty() || mc == null || mc.f_91073_ == null) {
            return;
        }
        java.util.Iterator<Pending> it = PENDING.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            if (mc.f_91073_.m_6815_(p.maidId()) instanceof EntityMaid) {
                it.remove();
                playOrQueue(p.maidId(), p.file(), false);
            }
        }
    }
}
