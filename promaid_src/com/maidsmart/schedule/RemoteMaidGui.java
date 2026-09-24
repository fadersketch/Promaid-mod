package com.maidsmart.schedule;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v1.3.4 实测六百五十九 / v1.3.5 实测六百六十「真正的远程开界面」：
 * （玩家, 女仆）这把「这次是我们替她开的」登记处 + 每 tick 的同步泵。
 *
 * ── 为什么要有这个东西 ──
 * TLM 的女仆容器**每 tick** 都要过一遍 {@code AbstractMaidContainer.stillValid}
 * （forge 树 = SRG {@code m_6875_}），其中一条是「玩家到她 ≤ 3 格」（1.21.1 是 4 格）。
 * 玩家在排班表里点「女仆配置」时人可能站在几十上百格外，于是服务端**下一 tick 就把界面关掉**
 * ——客户端看到的就是"点进去一下、立刻闪退"。玩家原话：「我想要真正的远程开界面。」
 *
 * ── 这个登记处管的是「例外」，不是「规则」 ──
 * 只有**从排班表那条路开出来的**界面才登记（{@link SchedulePacketsMaid.OpenMaidConfigPacket}），
 * 玩家自己右键女仆开的那种**不登记** → 距离照旧由 TLM 管（「走近才能开」的原版手感一点没变）。
 * 界面关掉时由 {@code AbstractMaidContainer.removed} 那条注入收回
 * （见 {@code MaidContainerRemoteOpenMixin}）。
 *
 * ── v1.3.5：距离上限取消了，改成「把同步真做出来」 ──
 * 上一版（v1.3.4）留了一条"别开太远"的边界，理由写的是"客户端根本没同步到她，开出来是残界面"。
 * 那个顾虑本身是对的（字节码实证：{@code AbstractMaidContainer} 构造里按实体网络 id 找她，
 * 找不到就只建一半槽位），但**做法**错了：既然残界面的根因是"没同步"，那就去把同步做出来，
 * 而不是把玩家挡在门外。玩家问过一句：「不是有过一个女仆区块强制加载吗？难道解决不了这样的
 * 问题吗？」——区块票据解决不了（详见 {@code ChunkMapTrackRemoteMixin} 的注释：票据管
 * "她在服务端在不在"，同步门是另一条管线、另一套距离判据），但"强制同步"本身做得到：
 * {@code ChunkMapTrackRemoteMixin} 把这一对塞进原版 {@code seenBy} 并调原版
 * {@code addPairing}，客户端拿到的她与"走近时原版自己配上的"完全一致。
 * 于是**本类里再没有任何距离阈值**——半径这个概念整个不存在了。
 *
 * ── 泵（{@link #pump}）为什么在 ChunkMap.tick 里跑 ──
 * 见 {@code ChunkMapRemotePumpMixin}：① 只有混进 ChunkMap 的 mixin 碰得到 entityMap；
 * ② 「先配对、再开界面」必须在同一次 tick 内按序发这两条包（同一个方法里做完，
 * TCP 有序天然保证客户端先认识她、再打开她的界面）。登记本身只记一笔，真正的同步+打开在泵里做，
 * 所以玩家点下去到界面弹出来最多晚 1~2 tick（≤100 毫秒），肉眼无感。
 *
 * 【线程】登记/泵/查询都在服务端主线程（容器 tick、包处理、区块 tick），表用并发实现，
 * 免得集成服务器上"关界面的那一刻"与"tick 里的判据"撞在一起。
 */
public final class RemoteMaidGui {

    /** "玩家UUID|女仆UUID" → 这次远程会话 */
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    /** 会话存活上限（毫秒）：界面开着就一直被 {@link #isActive} 续期，不清也不会掉 */
    private static final long TTL_MS = 30L * 60L * 1000L;

    /**
     * 从"登记"到"同步落地"的上限（tick，40 = 2 秒）。
     * <p>正常情况下下一 tick 就同步上了并顺手把界面开出来；等这么久还没成，只有两种情况：
     * 她的区块没加载出来（她不在 entityMap 里），或者这一拍之内人没了。此时宁可回一句明白话，
     * 也不让玩家对着"点了没反应"发呆。
     */
    private static final long SYNC_TIMEOUT_TICKS = 40L;

    /** 一次远程会话 */
    private static final class Session {
        final UUID playerId;
        final UUID maidId;
        final ResourceKey<Level> dim;
        /** 最后一次"有人在管它"的墙上时间（登记、泵、容器 stillValid 都会刷新） */
        volatile long lastHit;
        /** 登记时的 gameTime：用来算"同步等了多久还没成" */
        final long requestedTick;
        /** 界面是否已经替玩家开出去了（开成功就只维持同步；开失败就把这条会话收掉） */
        volatile boolean opened;

        Session(UUID playerId, UUID maidId, ResourceKey<Level> dim, long lastHit, long requestedTick) {
            this.playerId = playerId;
            this.maidId = maidId;
            this.dim = dim;
            this.lastHit = lastHit;
            this.requestedTick = requestedTick;
        }
    }

    private RemoteMaidGui() {
    }

    /**
     * 登记：「这次界面是我们替玩家开的」——之后 {@code stillValid} 里那条距离判据对它放行，
     * 并且 {@link #pump} 会在她所在维度的下一 tick 把同步和界面都办掉。
     *
     * <p>登记本身不做任何判定（该不该开由 {@code SchedulePacketsMaid} 那边的
     * {@code openBlockedReason} 统一说了算，同一口径只有一处实现）。
     */
    public static void request(ServerPlayer player, EntityMaid maid) {
        String k = key(player, maid);
        if (k == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long tick = player.m_9236_().m_46467_();
        SESSIONS.put(k, new Session(player.m_20148_(), maid.m_20148_(),
                maid.m_9236_().m_46472_(), now, tick));
        if (SESSIONS.size() > 64) {
            prune(now);
        }
    }

    /** 收回授权（界面关了 / 没开成）——下一个 tick 原版就会按距离把她从客户端拆回原样 */
    public static void disable(Player player, EntityMaid maid) {
        String k = key(player, maid);
        if (k != null) {
            SESSIONS.remove(k);
        }
    }

    /**
     * 这一对（玩家, 女仆）此刻是不是「我们替他开的远程界面」。
     * 命中时顺手续期——所以界面开着就一直有效，关了（{@link #disable}）或半小时没人管才作废。
     *
     * <p>三个调用点，含义同一份：容器 {@code stillValid}（放行距离那一条）、
     * {@code ChunkMapTrackRemoteMixin}（决定要不要强制同步 / 拦住原版的拆人）、
     * 本类的 {@link #pump}。
     */
    public static boolean isActive(Player player, EntityMaid maid) {
        String k = key(player, maid);
        if (k == null) {
            return false;
        }
        Session s = SESSIONS.get(k);
        if (s == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - s.lastHit > TTL_MS) {
            SESSIONS.remove(k);
            return false;
        }
        s.lastHit = now;
        return true;
    }

    /**
     * 同步泵：每 tick（每维度一次，见 {@code ChunkMapRemotePumpMixin}）把该同步的女仆同步给玩家，
     * 同步落地了就替玩家把配置界面开出来。没登记过任何会话时**第一行就返回**（平时零开销）。
     *
     * <p>逐对做四件事：
     * <ol>
     *   <li>把玩家与女仆从 uuid 解回来（任何一边不在了 → 等超时后收场并给一句明白话）；</li>
     *   <li>{@code bridge.promaid$forcePair(player)}：这就是「强制同步」本身——原版的
     *       {@code seenBy} + {@code addPairing}，客户端由此真的拿到她（spawn + 元数据 + 属性）。
     *       已经同步着的不会重复发包（{@code add} 的返回值即"这次新加进去没有"）；</li>
     *   <li>同步成功后（且还没开过）→ {@code openMaidGui}。**顺序不能反**：同一个方法里
     *       先配对再开，两条包同一条连接按序到达，客户端先认识她、再打开她的界面；</li>
     *   <li>已经在开着的会话 → 只维持同步（每 tick 补一次，原版中途因为区块滑出视野之类
     *       把她拆掉也会在下一 tick 补回来）。</li>
     * </ol>
     *
     * @param level     本 tick 的那个维度（{@code ChunkMap.f_140133_}）
     * @param entityMap {@code ChunkMap} 的实体跟踪表（{@code f_140150_}，key = 实体网络 id）——
     *                  值 cast 成 {@link RemoteTrackBridge} 就是我们借出来的「强制配对」入口
     */
    public static void pump(ServerLevel level, Int2ObjectMap<?> entityMap) {
        if (SESSIONS.isEmpty() || level == null || entityMap == null) {
            return;
        }
        long wall = System.currentTimeMillis();
        long tick = level.m_46467_();
        Iterator<Session> it = SESSIONS.values().iterator();
        while (it.hasNext()) {
            Session s = it.next();
            if (!s.dim.equals(level.m_46472_())) {
                continue; // 她在别的维度：那边维度的 tick 会管；跨维度开界面在包里就被拒了
            }
            if (wall - s.lastHit > TTL_MS) {
                it.remove();
                continue;
            }
            ServerPlayer player = level.m_7654_().m_6846_().m_11259_(s.playerId);
            Entity found = level.m_8791_(s.maidId);
            boolean timedOut = tick - s.requestedTick > SYNC_TIMEOUT_TICKS;
            if (player == null || !(found instanceof EntityMaid maid) || !maid.m_6084_()) {
                if (timedOut) {
                    it.remove();
                    say(player, "§7没能打开她的配置界面——她已经不在了（被收回 / 死亡 / 离开了这一维度）");
                }
                continue;
            }
            s.lastHit = wall; // 泵也算「有人在管」，别让 TTL 在开出来之前把它收了
            Object tracked = entityMap.get(maid.m_19879_());
            if (!(tracked instanceof RemoteTrackBridge bridge) || !bridge.promaid$forcePair(player)) {
                // 同步没成（她的区块没加载出来 / 她已经不该被同步了）→ 等超时
                if (timedOut) {
                    it.remove();
                    say(player, "§7没能打开「" + nameOf(maid)
                            + "」的配置界面——她的区块没加载出来（同步不过去）。"
                            + "走近一点再点，或者用「召她过来」把她叫过来");
                }
                continue;
            }
            if (!s.opened) {
                s.opened = true;
                if (!maid.openMaidGui(player)) {
                    it.remove();
                    say(player, "§7没能打开「" + nameOf(maid) + "」的配置界面");
                }
            }
        }
    }

    private static String nameOf(EntityMaid maid) {
        try {
            return maid.m_5446_() == null ? "女仆" : maid.m_5446_().getString();
        } catch (Throwable ignored) {
            return "女仆";
        }
    }

    private static void say(ServerPlayer player, String msg) {
        if (player == null) {
            return;
        }
        try {
            player.m_213846_(net.minecraft.network.chat.Component.m_237113_(msg));
        } catch (Throwable ignored) {
        }
    }

    private static String key(Player player, EntityMaid maid) {
        if (player == null || maid == null) {
            return null;
        }
        try {
            return player.m_20148_() + "|" + maid.m_20148_();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 清掉过期条目（纯防泄漏；正常路径上每次关界面都会 {@link #disable}） */
    private static void prune(long now) {
        try {
            SESSIONS.values().removeIf(s -> now - s.lastHit > TTL_MS);
        } catch (Throwable ignored) {
        }
    }
}
