package com.maidsmart.accessory;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * <p>【为什么服务端不自己播】照搬 hatmod（{@code com.hatmod.HatMusic}）的结论：
 * 原版 {@code level.playSound(...)} 走的是声音包，客户端拿到的只是"某个位置在响某个音效"、
 * **不带"这条音乐属于哪个实体"**——两个佩戴者同曲时，停其中一个会把另一条一起掐掉，
 * 也做不到"按听者计算谁该听见"。
 * <p>所以这里只做一件事：把「谁的 BGM 开始了 / 停了」发给听得见的人
 * （她自己 + {@link #HEAR_RADIUS} 格内的玩家），客户端据此为每个佩戴者建一条**自己的**
 * 声音实例（{@code DaYunMusicPlayer}）。
 * <p>【调用方是"每 tick 问一次"】{@link DaYunBauble#onTick} / {@link DaYunPlayerHook#tick}
 * 每刻都会无条件调 {@link #play}/{@link #stop}（不飞就调 stop）。为了不把网络刷爆，
 * 本类自己记 {@link #ACTIVE} 做**边沿触发**：只有"没在放 → 开始放"和"在放 → 停"才真的
 * 发包，状态没变时一个包都不发。
 * <p>【v1.5.396 实测八百零九：边沿触发加"宽限期"】
 * 收翅猛击那一段 {@code MaidFlightCombatBehavior} 会主动清掉滑翔位（不收翅吃不到猛击判定）， 打完再起飞时才置回去。旧版把这个"闪一下"直接当成"停了"，客户端于是拆掉旧声音、建一条 **从头放**的新实例——听感就是每打一下重头开始。现在停止要走 {@link #STOP_GRACE_TICKS} 的宽限期：期间任何一次 {@link #play} 都把它撤回，音乐从起播到曲终只有一条实例。 <p>【为什么记实体引用而不是只记 id】实体 id 会被回收（女仆死亡/卸载后新实体顶上同一个 id）。 只比 id 的话，新实体恰好被分配了旧 id 又选了同一首时，会因为"和记录里的一样"而被跳过、 一声都听不见。所以 {@link Entry} 同时持有实体引用：引用不同 = 换了人，一律重发。 <p>【范围与音量】{@link #HEAR_RADIUS} = 200 格（实测八百一十），音量 1.0：客户端实际响度 = {@code clamp(volume × 音量滑块, 0..1)}，可听范围 = {@code max(volume,1) × 衰减距离} ——所以"能听多远"由 {@code sounds.json} 的 {@code attenuation_distance = 200} 决定， 音量只管响度。两条必须成对改，见 {@link #MUSIC_VOLUME}。 <p>本类两树各一份：Neo 树用 Mojmap 名（本文件），Forge 树用 SRG 名。
 */
public final class DaYunMusic {

    /**
     * 听得见音乐的范围（同时也是"通知谁"的范围）。
     * <p>【v1.5.395 实测八百零八】
     * （{@code FlightTargeting.RANGE} = 50 格）。
     * <p><b>【v1.5.397 实测八百一十：改为固定 200 格】</b>
     * ——他在地面上、女仆飞远一点就听不见了。现在直接写死 200 格，**不再引用别的类**
     * （免得以后改锁敌半径顺手把音乐半径也改了）。
     */
    public static final double HEAR_RADIUS = 200.0D;

    /**
     * 播放音量。
     * <p>【为什么是 1.0，而不是照旧 HEAR_RADIUS / 16】音量在这里**不再承担扩展听觉范围**
     * 的职责。原版 {@code SoundEngine} 里可听半径 = {@code max(volume, 1) × attenuation_distance}
     * （该类的 {@code $$5}，反编译实证），所以想靠加大音量够到 200 格就得写 12.5——那会让
     * {@code clamp(volume × 音量滑块, 0..1)} 恒等于 1，**玩家自己的音乐滑块彻底失效**
     * （拖到 0 也关不掉）。现在范围交给 {@code sounds.json} 的 {@code attenuation_distance = 200}，音量回到 1.0：滑块照常生效，声压也不压限幅 （原先 3.125 其实已经撞在硬限幅上了）。
     */
    public static final float MUSIC_VOLUME = 1.0F;

    /** 状态表攒到这个数才做一次惰性清理（见 {@link #pruneDead}）。 */
    private static final int PRUNE_THRESHOLD = 256;

    /**
     * 【v1.5.396 实测八百零九】"滑翔位闪了一下"不算停：宽限期（刻）。
     * <p>
     * <p>旧行为把"这一 tick 没在滑翔"当成立刻停止，于是客户端收到"停 → 播"，
     * 拆掉旧声音实例、新建一条从头放——听感就是"每打一下就重头开始"。
     * 现在 {@link #stop} 只**记下"打算停"的时刻**，宽限期内若又收到 {@link #play}，
     * 就当作什么都没发生（客户端那条声音一直没断，自然接着播）。 <p>【为什么是 60 刻】要盖过"攻击动作把滑翔位按下去"的整段窗口，而不是某一拍： 收翅猛击最长 {@code airRaid.smashMaxTicks} = 20 刻，接着"等待再起飞"里放一次烟花 最多还要等 {@code airRaid.fireworkCooldown} = 30 刻，再加起跳离地那几拍——30+20+3 ≈ 53 刻。 60 留了点余量，又远小于一首曲子（分钟级），所以她真要落地/摘饰品停下时， 音乐仍会在 3 秒内真的停掉，不会"拖着不关"。
     */
    public static final int STOP_GRACE_TICKS = 60;

    /** 当前"应该响着"的佩戴者：实体 id → 实体引用 + 曲目。用于边沿触发（见类注释）。 */
    private static final Map<Integer, Entry> ACTIVE = new HashMap<>();

    /** 一条"正在响"的记录。 */
    private static final class Entry {
        private final Entity wearer;
        private final int song;
        /** 收到"打算停"的时刻（毫秒 epoch）；0 = 没打算停。见 {@link #STOP_GRACE_TICKS}。 */
        private long stopRequestedAt;

        private Entry(Entity wearer, int song) {
            this.wearer = wearer;
            this.song = song;
        }
    }

    private DaYunMusic() {
    }

    /**
     * 某个佩戴者的 BGM 开始了（同一个人同一首重复调用不发包）。
     *
     * <p>v1.5.396 实测八百零九：若这条记录正处在"打算停"的宽限期内，这里**撤回停止请求**
     * ——音乐从头到尾只有一条声音实例，不会被拆掉重播（见 {@link #STOP_GRACE_TICKS}）。
     */
    public static void play(ServerLevel level, Entity wearer, int track) {
        if (wearer == null || track <= 0) {
            return;
        }
        int id = wearer.getId();
        Entry current = ACTIVE.get(id);
        if (current != null && current.wearer == wearer && current.song == track) {
            // 就是这个人、就是这一首：已在放（或"刚打算停但还没到点"）→ 撤回停止请求，
            // 客户端那条声音一直没断，自然接着播。这里一个包都不发。
            current.stopRequestedAt = 0L;
            return;
        }
        ACTIVE.put(id, new Entry(wearer, track));
        pruneDead();
        notify(level, wearer, track, true);
    }

    /**
     * 停止（滑翔结束 / 摘掉饰品 / 实体卸载）。没在放时什么都不做。
     *
     * <p>v1.5.396 实测八百零九：**不再一到就停**，而是先记下"打算停"的时刻，并给
     * {@link #STOP_GRACE_TICKS} 刻的宽限。调用方每刻都会问一次（不飞就调本方法），
     * 所以宽限期到点后由**下一次调用**把"停了"发出去并摘掉记录——期间任何一次
     * {@link #play} 都能把它撤回（攻击动作按下滑翔位那几下就属于这种情况）。
     */
    public static void stop(ServerLevel level, Entity wearer) {
        if (wearer == null) {
            return;
        }
        int id = wearer.getId();
        Entry current = ACTIVE.get(id);
        if (current == null || current.wearer != wearer) {
            return; // 本来就没在放（或这条记录已经是别人的了）
        }
        long now = System.currentTimeMillis();
        if (current.stopRequestedAt == 0L) {
            current.stopRequestedAt = now;  // 第一次说"要停"：进宽限期
            return;
        }
        if (now - current.stopRequestedAt < STOP_GRACE_TICKS * 50L) {
            return;                          // 还在宽限期内：再等等，别拆这条声音
        }
        ACTIVE.remove(id);
        notify(level, wearer, 0, false);
    }

    /**
     * 把「这个佩戴者的 BGM 开始/停止了」告诉听得见的人：她自己 + 200 格内的玩家。
     *
     * <p>范围判断与 hatmod 一致（{@code HatMusic.notify}）。
     */
    private static void notify(ServerLevel level, Entity wearer, int track, boolean playing) {
        if (level == null || wearer == null) {
            return;
        }
        double radiusSq = HEAR_RADIUS * HEAR_RADIUS;
        int wearerId = wearer.getId();
        for (ServerPlayer player : level.players()) {
            if (player == wearer || player.distanceToSqr(wearer) <= radiusSq) {
                DaYunMusicNetworking.sendMusic(player, wearerId, track, playing);
            }
        }
    }

    /**
     * 惰性清理：佩戴者已经不在世界里的记录（死亡卸载、换维度、退出游戏留下的）。
     *
     * <p>为什么不每刻扫全服实体——那是每 tick 一次全服遍历，代价白白付给一个几乎永不发生的
     * 情况。这里只在表长到 {@link #PRUNE_THRESHOLD} 之后才顺手扫一遍，正常玩法下永不到达；
     * 而真到那个量级时，扫一次也正是该做的事。判据是实体自己的 {@code isRemoved()}，
     * 不需要遍历世界。
     */
    private static void pruneDead() {
        if (ACTIVE.size() < PRUNE_THRESHOLD) {
            return;
        }
        Iterator<Map.Entry<Integer, Entry>> iterator = ACTIVE.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().wearer.isRemoved()) {
                iterator.remove();
            }
        }
    }

    /** 服务器关闭 / 换存档时清空。 */
    public static void clear() {
        ACTIVE.clear();
    }

    /**
     * 这个佩戴者现在有没有一条"该响着"的记录（含"打算停、还在宽限期内"那一段）。
     *
     * <p>【给玩家侧省反射用】玩家侧要判断"戴着没有"得走 Curios 反射；而它每刻都想知道
     * "要不要顺手把这条停掉"。先问这一句：既没在响、又没在滑翔的玩家直接跳过，
     * 省掉每刻每人一次的反射扫描（见 {@code DaYunPlayerHook#tick}）。
     */
    public static boolean isActive(Entity wearer) {
        if (wearer == null) {
            return false;
        }
        Entry current = ACTIVE.get(wearer.getId());
        return current != null && current.wearer == wearer;
    }
}
