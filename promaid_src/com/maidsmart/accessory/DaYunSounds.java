package com.maidsmart.accessory;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * v1.5.394 实测八百零五：穿「大运」滑翔时播放的 BGM（两首，玩家在「系统与杂项」里选）。
 *
 * <p>【需求原文】「玩家和女仆穿饰品飞行时会播放 {@code C:\...\VID_135.m4a}，
 * 玩家可在系统与杂项中配置播放的 bgm，可选播放该目录中的 2 首，播放机制参考
 * {@code hatmod-源码与成品} 中的默认音乐播放配置，直接照搬即可。」
 *
 * <p>【照搬 hatmod 的哪几件事】参考实现 {@code com.hatmod.HatSounds / HatMusic /
 * client.HatMusicInstance / client.HatMusicPlayer}：
 * <ul>
 *   <li>音效本体是 jar 内 {@code assets/<ns>/sounds/*.ogg}，由 {@code sounds.json} 登记；</li>
 *   <li>{@link SoundEvent} 用 {@code createVariableRangeEvent} 建——这样它是"世界里的一个发声点"、
 *       随距离衰减，而不是全局背景音乐；</li>
 *   <li>{@code sounds.json} 里每首歌写 {@code "stream": false, "attenuation_distance": 200}，
 *       配合 {@link DaYunMusic#MUSIC_VOLUME} = 1.0 得到 200 格衰减半径（实测八百一十）——
 *       可听范围 = {@code max(音量, 1) × 衰减距离}，音量只管响度，范围全由这里决定；</li>
 *   <li>**服务端只负责通知**（谁的 BGM 开始/停了），**客户端自己播放**——
 *       原版声音包不带"这条音乐属于哪个实体"，服务端广播下来客户端分不清哪条是自己的。</li>
 * </ul>
 *
 * <p>【两首的区别】{@code VID_135.m4a} / {@code VID_136.m4a} 已转成
 * {@code bgm_da_yun_1.ogg} / {@code bgm_da_yun_2.ogg}（单声道 Vorbis q3，压缩包里各约 1.6~2.9 MB）。
 * 玩家在「系统与杂项 → 饰品「大运」」里用 {@code accessory.daYun.bgm} 选 1 或 2。
 */
public final class DaYunSounds {

    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, "maid_smart");

    /** 曲目 1 = **天真的橡皮DJ**（VID_135.m4a → bgm_da_yun_1.ogg）。 */
    public static final RegistryObject<SoundEvent> BGM_1 = register("bgm_da_yun_1");
    /** 曲目 2 = **Tom Tom**（VID_136.m4a → bgm_da_yun_2.ogg）。 */
    public static final RegistryObject<SoundEvent> BGM_2 = register("bgm_da_yun_2");

    private DaYunSounds() {
    }

    private static RegistryObject<SoundEvent> register(String name) {
        return SOUNDS.register(name, () -> SoundEvent.m_262824_(
                new ResourceLocation("maid_smart", name)));
    }

    /** 按玩家选的序号取音效（1 = 第一首、其它 = 第二首）。 */
    public static SoundEvent forTrack(int track) {
        return track == 1 ? BGM_1.get() : BGM_2.get();
    }

    /** 注册到 mod 事件总线（{@code ProMaidMod} 构造器里调）。 */
    public static void register(net.minecraftforge.eventbus.api.IEventBus bus) {
        SOUNDS.register(bus);
    }
}
