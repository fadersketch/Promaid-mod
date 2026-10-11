package com.maidsmart.accessory;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 穿「大运」滑翔时播放的 BGM（两首，玩家在「系统与杂项」里选）。
 *
 * 【照搬 hatmod 的几件事】音效本体是 jar 内 {@code assets/<ns>/sounds/*.ogg}，由 {@code sounds.json}
 * 登记；{@code SoundEvent} 用 {@code createVariableRangeEvent} 建——它是"世界里的发声点"、随距离
 * 衰减，不是全局背景音乐；{@code sounds.json} 每首写 {@code "stream": false,
 * "attenuation_distance": 200}，配合 {@code DaYunMusic.MUSIC_VOLUME}=1.0 得到 200 格可听半径
 * （可听范围 = {@code max(音量,1) × 衰减距离}，音量只管响度）；**服务端只负责通知**（谁的 BGM
 * 开始/停了），**客户端自己播放**——原版声音包不带"这条音乐属于哪个实体"，客户端分不清哪条是自己的。
 * 【两首】{@code VID_135/136.m4a} 已转成 {@code bgm_da_yun_1/2.ogg}（单声道 Vorbis q3），玩家在
 * 「系统与杂项 → 饰品「大运」」里用 {@code accessory.daYun.bgm} 选 1 或 2。
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
