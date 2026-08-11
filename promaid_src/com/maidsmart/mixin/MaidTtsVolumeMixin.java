package com.maidsmart.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * v1.5.198：TTS 音量倍率——TLM 的 MaidAISoundInstance 构造时 super 音量参数
 * 硬编码 1.0f（玩家反馈"TTS 播放声音太小"）。在构造调用处把音量参数（index 2）
 * 按配置倍率放大：LLM 对话 TTS 与系统消息 TTS 都经该类播放，一并生效。
 * 客户端专用 mixin（mixins.promaid.json "client" 段，专服不应用）。
 */
@Mixin(com.github.tartaricacid.touhoulittlemaid.client.sound.data.MaidAISoundInstance.class)
public abstract class MaidTtsVolumeMixin {
    @ModifyArg(method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/sounds/EntityBoundSoundInstance;<init>(Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFLnet/minecraft/world/entity/Entity;J)V"),
            index = 2)
    // v1.5.215：必须 static——注入点在构造器 super() 调用处，super() 之前
    // 不能访问 this，非 static handler 导致 mixin 应用失败（日志 FATAL 刷屏 +
    // TTS 音量功能失效）。handler 只读配置，不碰实例字段，static 无副作用。
    private static float maidSmartTtsVolume(float volume) {
        return volume * com.maidsmart.config.MaidSmartConfig.TTS_VOLUME_MULTIPLIER.get().floatValue();
    }
}
