package com.maidsmart.schedule;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v1.3.4 实测六百五十九「真正的远程开界面」：（玩家, 女仆）这把"这次是我们替她开的"登记处。
 *
 * ── 为什么要有这个东西 ──
 * TLM 的女仆容器**每 tick** 都要过一遍 {@code AbstractMaidContainer.stillValid}
 * （forge 树 = SRG {@code m_6875_}），其中一条是「玩家到她 ≤ 3 格」（1.21.1 是 4 格）。
 * 玩家在排班表里点「女仆配置」时人可能站在十几格外，于是服务端**下一 tick 就把界面关掉**
 * ——客户端看到的就是"点进去一下、立刻闪退"。玩家原话："我想要真正的远程开界面。"
 *
 * ── 这个登记处管的是"例外"，不是"规则" ──
 * 只有**从排班表那条路开出来的**界面才登记（{@link SchedulePacketsMaid.OpenMaidConfigPacket}），
 * 玩家自己右键女仆开的那种**不登记** → 距离照旧由 TLM 管（"走近才能开"的原版手感一点没变）。
 * 界面关掉时由 {@code AbstractMaidContainer.removed} 那条注入收回
 * （见 {@code MaidContainerRemoteOpenMixin}）。
 *
 * ── 为什么还要一条"别开太远"的边界（{@link #remoteRange}）──
 * TLM 的容器是**按实体的网络 id 在客户端找她**的（字节码实证：{@code AbstractMaidContainer}
 * 构造里 {@code level.getEntity(int)} + {@code checkcast EntityMaid}），而"玩家背包那一片槽位"
 * 只在 {@code maid != null} 时才建。客户端根本没同步到这个实体时，打开的是一个**残界面**，
 * 所以那种距离我们宁可不开（{@link SchedulePacketsMaid} 里会给一句明白话）。
 * 边界取的是**原版自己的实体同步距离**（{@code EntityType.getClientTrackingRange()}，
 * 单位=区块），而不是我们拍一个数——TLM 哪天改了同步距离，我们跟着改。
 *
 * 【线程】登记与查询都在服务端主线程（容器 tick / 包处理），但表用并发实现，免得
 * 集成服务器上"关界面的那一刻"与"tick 里的判据"撞在一起。
 */
public final class RemoteMaidGui {

    /** "玩家UUID|女仆UUID" → 最后一次登记或命中的毫秒时刻 */
    private static final Map<String, Long> SESSIONS = new ConcurrentHashMap<>();

    /** 会话的存活上限（毫秒）：界面一直开着就靠 {@link #isActive} 每次命中续期，不清也不会掉 */
    private static final long TTL_MS = 30L * 60L * 1000L;

    /** 同步距离上留的余量（格）：贴着边界开最危险（同步刚刚好差一格），退一点 */
    private static final double MARGIN = 8.0;

    /**
     * 远程开界面的距离上限（格）。
     * <p>
     * 【为什么封顶 48】原版实体同步距离是按**区块**算的（{@code getClientTrackingRange()} × 16），
     * 同时还会被**玩家的视距**卡一刀（{@code ChunkMap.TrackedEntity} 里那一处
     * {@code Math.min(…, viewDistance × 16)}，字节码实证）。玩家视距常见的低档是 4 区块 = 64 格，
     * 所以这里封 48：既比"3 格"宽出一个数量级（玩家要的就是这个），又稳稳在同步范围内。
     */
    private static final double MAX_RANGE = 48.0;

    /** 读不到数值时的兜底（格）：原版实体同步最保守的那一档也远大于它 */
    private static final double FALLBACK_RANGE = 24.0;

    private RemoteMaidGui() {
    }

    /** 登记："这个界面是我们替玩家开的" —— 之后 {@code stillValid} 里那条距离判据对它放行 */
    public static void enable(Player player, EntityMaid maid) {
        String k = key(player, maid);
        if (k == null) {
            return;
        }
        SESSIONS.put(k, System.currentTimeMillis());
        if (SESSIONS.size() > 64) {
            prune();
        }
    }

    /** 收回授权（界面关了 / 没开成） */
    public static void disable(Player player, EntityMaid maid) {
        String k = key(player, maid);
        if (k != null) {
            SESSIONS.remove(k);
        }
    }

    /**
     * 这一对（玩家, 女仆）此刻是不是"我们替他开的远程界面"。
     * 命中时顺手续期——所以界面开着就一直有效，关了（{@link #disable}）或半小时没人管才作废。
     */
    public static boolean isActive(Player player, EntityMaid maid) {
        String k = key(player, maid);
        if (k == null) {
            return false;
        }
        Long t = SESSIONS.get(k);
        if (t == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - t > TTL_MS) {
            SESSIONS.remove(k);
            return false;
        }
        SESSIONS.put(k, now);
        return true;
    }

    /**
     * 客户端"看得见她"的距离（格）＝ 原版给这个实体类型声明的同步距离（区块 × 16）－ 余量，
     * 再夹进 [8, {@link #MAX_RANGE}]。读不到（反射/映射变了、maid 为 null）就退回
     * {@link #FALLBACK_RANGE}——**宁可保守**：拦住了顶多让玩家走近两步，放过去则是开出一个残界面。
     */
    public static double remoteRange(EntityMaid maid) {
        try {
            if (maid == null) {
                return FALLBACK_RANGE;
            }
            int chunks = maid.m_6095_().m_20681_();
            double blocks = chunks * 16.0;
            return Math.max(8.0, Math.min(MAX_RANGE, blocks - MARGIN));
        } catch (Throwable ignored) {
            return FALLBACK_RANGE;
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
    private static void prune() {
        long now = System.currentTimeMillis();
        try {
            SESSIONS.entrySet().removeIf(e -> now - e.getValue() > TTL_MS);
        } catch (Throwable ignored) {
        }
    }
}
