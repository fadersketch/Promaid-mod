package com.maidsmart.client;

import com.maidsmart.accessory.DaYunMusic;
import com.maidsmart.accessory.DaYunSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v1.5.394 实测八百零五：客户端**自己播放**所有「大运」BGM，并按「听者」决定哪一条听得见。
 *
 * <p>照搬 hatmod 的 {@code com.hatmod.client.HatMusicPlayer}（同一套规则、同一套心跳）：
 * <ul>
 *   <li><b>自己的 BGM 绝对优先</b>：自己正在放，就只听得见自己那条；</li>
 *   <li>自己没在放时，<b>只留最后响起的那一条</b>（后手覆盖）。</li>
 * </ul>
 * 实现上不是"停掉其余的"，而是**把其余的音量压到 0 继续无声播放**——轮到它时直接放开音量
 * 就能接着响，不用从头重播（原理见 {@link DaYunMusicInstance}）。
 *
 * <p><b>「服务端说到停才停」</b>：{@link #TRACKS} 记的是**「应该响着的 BGM」**，不是
 * 「此刻恰好活着的声道」。声音实例被掐掉（曲终、音效引擎重载、声道被别的音效挤掉、
 * 暂停期间的心跳误判……）时不把这一条丢掉，而是**原地重建再接着放**；只有服务端明确发来
 * 「停了」、或者佩戴者实体已经不在客户端世界里，才真正移除。
 *
 * <p>【为什么必须由客户端自己播】原版的声音包只带"某个位置在响某个音效"，不带
 * "这条音乐属于哪个实体"，服务端广播下来客户端根本分不清哪条是自己的（详见
 * {@code DaYunMusic} 类注释）。
 *
 * <p>本类两树各一份：Neo 树用 Mojmap 名（本文件），Forge 树用 SRG 名。
 */
public final class DaYunMusicPlayer {

    /** 心跳多久没跳就认为"这条声道已经没了"（刻）。留宽一点，避免把暂停/卡顿误判成死亡。 */
    private static final int HEARTBEAT_GRACE = 20;

    /** 重建之后的冷却（刻）：期间不再重试，免得播放失败时每刻都在建实例。 */
    private static final int RESTART_COOLDOWN = 20;

    /** 佩戴者实体 id → 它那条 BGM。{@link LinkedHashMap} 的插入顺序 = 响起顺序。 */
    private static final Map<Integer, Track> TRACKS = new LinkedHashMap<>();

    /** 客户端刻计数，给 {@link DaYunMusicInstance} 记心跳用。 */
    private static int ticks;

    /** 一条 BGM 的全部信息：跟着谁、放哪首、以及此刻正在响的那个声音实例。 */
    private static final class Track {
        private final Entity wearer;
        private final int song;
        private DaYunMusicInstance sound;
        private int retryAt;

        private Track(Entity wearer, int song) {
            this.wearer = wearer;
            this.song = song;
        }
    }

    private DaYunMusicPlayer() {
    }

    static int ticks() {
        return ticks;
    }

    /** 服务端通知：某个佩戴者的 BGM 开始 / 停止了。 */
    public static void handle(int wearerId, int track, boolean playing) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return;
        }
        Track t = TRACKS.get(wearerId);
        if (!playing) {
            if (t != null) {
                stop(minecraft, t);
                TRACKS.remove(wearerId);
            }
            applyVolumes(minecraft);
            return;
        }

        Entity wearer = minecraft.level.getEntity(wearerId);
        if (wearer == null) {
            // 客户端世界里没有这个实体（离得远没同步过来）。这时本来也听不见，直接不播。
            return;
        }
        // 同一轮循环重开会再发一次开始：先把上一条换掉，免得两条叠在一起
        if (t != null) {
            stop(minecraft, t);
        }
        t = new Track(wearer, track);
        TRACKS.put(wearerId, t);
        start(minecraft, t);
    }

    /** 建一条声道并起播（该被压住的那条靠 {@code canStartSilent} 也能正常建）。 */
    private static void start(Minecraft minecraft, Track t) {
        t.sound = new DaYunMusicInstance(DaYunSounds.forTrack(t.song), t.wearer);
        t.retryAt = ticks + RESTART_COOLDOWN;
        // 先定音量再起播，免得"一出生就该被压住"的那条响出一声再被压下去
        applyVolumes(minecraft);
        minecraft.getSoundManager().play(t.sound);
    }

    private static void stop(Minecraft minecraft, Track t) {
        if (t.sound != null) {
            minecraft.getSoundManager().stop(t.sound);
            t.sound = null;
        }
    }

    /** 客户端每刻调用（由 {@code PromaidClientSetup} 的 ClientTickEvent 转进来）。 */
    public static void onClientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || TRACKS.isEmpty()) {
            return;
        }
        // 暂停（Esc / 失焦 / 单人暂停）时**不能**推进心跳、也不能判定死亡：
        // 原版在暂停时不 tick 声音引擎（Minecraft.tick -> SoundManager.tick(paused) ->
        // SoundEngine 直接跳过 tickNonPaused），所以声音实例的 tick() 不会跑、心跳停在原地；
        // 而 ClientTickEvent 暂停时照常触发。两边一叠加，暂停几刻之后所有 BGM 都会被
        // 误判成"心跳停了"——这就是 hatmod 最早那版「玩着玩着 BGM 突然没了」的来源。
        // 暂停期间原样保留，恢复后声音引擎接着 tick，心跳自然跟上。
        if (minecraft.isPaused()) {
            return;
        }
        ticks++;

        Iterator<Map.Entry<Integer, Track>> iterator = TRACKS.entrySet().iterator();
        while (iterator.hasNext()) {
            Track t = iterator.next().getValue();
            // 佩戴者已经不在这个世界了（死了 / 卸载 / 换维度）：服务端不会再发停止包，
            // 这里自己收掉。这是**唯一**会因为"声道死了"而移除名单的情况。
            if (t.wearer.isRemoved()) {
                stop(minecraft, t);
                iterator.remove();
                continue;
            }
            if (isAlive(t) || ticks < t.retryAt) {
                continue;
            }
            // 服务端还说要响，但这声道已经没了（曲终 / 音效引擎重载 / 被别的音效挤掉）：
            // 原地重建，接着放。宁可重来一遍，也不要静默下去。
            stop(minecraft, t);
            start(minecraft, t);
        }
        applyVolumes(minecraft);
    }

    /**
     * 这条 BGM 现在是不是真的在响。
     *
     * <p>判据是「引擎还在每刻 tick 我」——{@link DaYunMusicInstance#tick()} 每刻留一次心跳，
     * 心跳一旦停了就说明这条声道已经不在引擎手里了（不管是因为播完了还是被踢了）。
     */
    private static boolean isAlive(Track t) {
        return t.sound != null && !t.sound.isStopped()
                && ticks - t.sound.lastHeartbeat() <= HEARTBEAT_GRACE;
    }

    /** 每刻重算「该听见谁」，其余一律压到 0。 */
    private static void applyVolumes(Minecraft minecraft) {
        if (TRACKS.isEmpty()) {
            return;
        }
        Track winner = null;
        if (minecraft.player != null) {
            // 自己的那一条只要还在名单里就永远是赢家——哪怕它的声道此刻正在重建。
            Track own = TRACKS.get(minecraft.player.getId());
            winner = own != null ? own : newestInRange(minecraft);
        }
        for (Track t : TRACKS.values()) {
            if (t.sound != null) {
                t.sound.setMuted(t != winner);
            }
        }
    }

    /**
     * 听得见的那些里**最后响起**的一条（{@code LinkedHashMap} 迭代顺序 = 响起顺序，
     * 所以不停覆盖就行）；一条都听不见就返回 null。
     *
     * <p>要卡距离是因为服务端是"开始那一刻"按 48 格筛选的：之后佩戴者可能走远，
     * 那一条已经听不见了，不该再压着近处那条不让响。
     */
    private static Track newestInRange(Minecraft minecraft) {
        if (minecraft.player == null) {
            return null;
        }
        double radiusSq = DaYunMusic.HEAR_RADIUS * DaYunMusic.HEAR_RADIUS;
        Track newest = null;
        for (Track t : TRACKS.values()) {
            double dx = t.wearer.getX() - minecraft.player.getX();
            double dy = t.wearer.getY() - minecraft.player.getY();
            double dz = t.wearer.getZ() - minecraft.player.getZ();
            if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                newest = t;
            }
        }
        return newest;
    }

    /** 退出世界时清空（避免跨世界残留）。 */
    public static void clear() {
        TRACKS.clear();
    }
}
