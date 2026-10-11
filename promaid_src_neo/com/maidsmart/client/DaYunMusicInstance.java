package com.maidsmart.client;

import com.maidsmart.accessory.DaYunMusic;
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;

/**
 * v1.5.394 实测八百零五：「大运」BGM 的一条声音实例——跟着佩戴者走、按距离衰减，音量随时可调。
 *
 * <p>照搬 hatmod 的 {@code com.hatmod.client.HatMusicInstance}（逐行同构）：
 * <ul>
 *   <li>原版 {@link EntityBoundSoundInstance} 的音量在创建时就定死；这里继承它、只改写
 *       {@link #getVolume()}，于是"压低 / 放开"变成每刻可做的小事；</li>
 *   <li>之所以可行：音效引擎对**可 tick** 的声音每刻都会重新读一次音量并推给声道
 *       （{@code SoundEngine.tickNonPaused} 里 {@code calculateVolume} → {@code channel.setVolume}）。
 *       所以被压到 0 的那条并不是被停掉，而是**无声地继续播**；等轮到它时把音量放回去，
 *       它就从原来的位置接着响——这正是「自己的优先、其余只留最后一条」想要的效果（不用从头重播）。</li>
 * </ul>
 *
 * <p>本类两树各一份：Neo 树用 Mojmap 名（本文件），Forge 树用 SRG 名。
 */
public class DaYunMusicInstance extends EntityBoundSoundInstance {

    private final float baseVolume;
    private boolean muted;
    private int lastHeartbeat;

    public DaYunMusicInstance(SoundEvent sound, Entity wearer) {
        super(sound, net.minecraft.sounds.SoundSource.PLAYERS,
                DaYunMusic.MUSIC_VOLUME, 1.0F, wearer, wearer.getId());
        this.baseVolume = DaYunMusic.MUSIC_VOLUME;
    }

    void setMuted(boolean value) {
        this.muted = value;
    }

    int lastHeartbeat() {
        return this.lastHeartbeat;
    }

    /**
     * 允许"以 0 音量起播"。否则一条"一出生就该被压住"的 BGM（比如自己正在响、别人又开始了）会被引擎
     * 直接跳过、压根不建声道，之后想放开也没东西可放。
     * 【SRG 名】1.20.1 {@code SoundInstance} 里默认返回 {@code false} 的那个就是
     * {@code canStartSilent()} = {@code m_7784_}（另有一个默认 {@code true} 的是
     * {@code canPlaySound()} = {@code m_7767_}，别弄反）。
     */
    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public float getVolume() {
        if (this.muted) {
            return 0.0F;
        }
        // sound 要等引擎 resolve 之后才非空；没解析出来就先用基础音量兜着
        return this.sound != null ? super.getVolume() : this.baseVolume;
    }

    @Override
    public void tick() {
        super.tick();
        // 心跳：引擎每刻都会 tick 我，一旦停了就说明这条已经播完 / 实体没了
        this.lastHeartbeat = DaYunMusicPlayer.ticks();
    }
}
