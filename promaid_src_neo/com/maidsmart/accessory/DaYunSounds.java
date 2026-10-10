package com.maidsmart.accessory;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * v1.5.394 实测八百零五：「大运」的两首 BGM 声音事件注册（NeoForge 1.21.1）。
 *
 * <p>照搬 hatmod 的 {@code HatSounds}：{@link SoundEvent#createVariableRangeEvent} +
 * 一条 {@code DeferredRegister}。声音文件在 {@code assets/maid_smart/sounds/bgm_da_yun_1.ogg}
 * / {@code bgm_da_yun_2.ogg}，由 {@code assets/maid_smart/sounds.json} 声明
 * （{@code stream:false, attenuation_distance:200}——与 {@link DaYunMusic#MUSIC_VOLUME} = 1.0
 * 配套得到 200 格衰减半径，见该类注释）。
 *
 * <p>本类两树各一份：Neo 树用 NeoForge 的 {@code DeferredRegister}（本文件），
 * Forge 树用 {@code DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, ...)}。
 */
public final class DaYunSounds {

    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(net.minecraft.core.registries.Registries.SOUND_EVENT, "maid_smart");

    /** 第一首 = **天真的橡皮DJ**（配置 {@code accessory.daYun.bgm = 1}）。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> BGM_1 =
            register("bgm_da_yun_1");

    /** 第二首 = **Tom Tom**（配置 {@code accessory.daYun.bgm = 2}）。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> BGM_2 =
            register("bgm_da_yun_2");

    private DaYunSounds() {
    }

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUNDS.register(name,
                () -> SoundEvent.createVariableRangeEvent(
                        ResourceLocation.fromNamespaceAndPath("maid_smart", name)));
    }

    /** 曲目序号 → 声音事件（{@code track} 非 1 一律当第 2 首）。 */
    public static SoundEvent forTrack(int track) {
        return track == 1 ? BGM_1.get() : BGM_2.get();
    }

    /** 由模组主类在构造期注册到 MOD 总线。 */
    public static void register(IEventBus modBus) {
        SOUNDS.register(modBus);
    }
}
