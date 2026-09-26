package com.maidsmart.follow;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.command.MaidResyncCommand;
import com.maidsmart.schedule.RemoteTrackBridge;
import com.maidsmart.tool.PromaidLog;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实测六百九十八【可见性自愈】——把"服务端还活着、客户端却没有她"这件事**每 tick 查一次、
 * 当场补回来**。（本文件是 forge / 1.20.1 树；neo 版见同名文件，差别只在 SRG 名字。）
 *
 * ── 玩家报的那句 ──
 * 「女仆在复活之后，建模直接被卡掉了，变成了幽灵状态。」（1.3.0 beta 实测六百九十七 那三枪
 * 补包没治好；本次整合包日志 2026-09-27 latest.log 又复现）
 *
 * ── 机制（这次是从原版字节码读出来的，不是猜） ──
 * <ol>
 *   <li>Forge 的 {@code EntityLeaveLevelEvent} 只在 {@code ServerLevel.EntityCallbacks
 *       .m_143334_}（onTrackingEnd）里发；而它由
 *       {@code PersistentEntitySectionManager.updateChunkStatus} 在**区块视线档位降到
 *       {@code HIDDEN}**（区块卸载/掉出 entity-ticking）时对区块里所有实体调一遍
 *       ——这条路上 {@code setRemoved} 还没被执行，所以离场那一行一直是
 *       {@code reason=null}（这就是日志里那些"reason=null"的来历，与死亡无关）。</li>
 *   <li>它的副作用是 {@code ChunkMap.removeEntity}：把 {@code TrackedEntity} 从实体表摘掉，
 *       并给**所有看过她的玩家**发一次 {@code ClientboundRemoveEntitiesPacket}
 *       —— 客户端当场把她删掉。</li>
 *   <li>区块重新 ticking 时原版会重新同步她，但"新实体不补包"（实测五百九十六）拦掉了
 *       我们自己的补包，而实测六百九十七 那三枪只覆盖复活后头 10 秒 —— 幽灵就是这么留下的。</li>
 * </ol>
 *
 * ── 这里做什么 ──
 * 挂在 {@code ChunkMap.m_140421_} 尾巴上（与远程界面同步泵同一个注入点）：
 * <ul>
 *   <li>她**在实体表里但主人没配对** = 典型幽灵 → 立刻重建一次（5 秒冷却）；</li>
 *   <li>她**根本不在实体表里** = 区块没在 entity-ticking → 立刻把票挪到她脚下。</li>
 * </ul>
 * 「主人不在 96 格内」一律不管：原版在 160 格内本来就该同步过她。
 */
public final class MaidVisibilityGuard {

    /** 只在这个距离内判定"原版本该已经同步过她"：女仆的客户端同步距离是 10 区块 = 160 格，
     *  常见低视距也有 8 区块 = 128 格；96 格留足余量。 */
    private static final double NEAR = 96.0;
    private static final double NEAR_SQR = NEAR * NEAR;
    /** 同一只女仆两次"重建"的最短间隔（tick）= 5 秒 */
    private static final int RESYNC_COOLDOWN = 100;
    /** 同一只女仆两次日志的最短间隔（tick）= 10 秒 */
    private static final int LOG_COOLDOWN = 200;

    private static final Map<UUID, Long> LAST_FIX = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_LOG = new ConcurrentHashMap<>();

    private MaidVisibilityGuard() {
    }

    /** 由 {@link MaidChunkLoadManager#visibilitySweep} 每 tick 对"本维度持票的每只女仆"调一次 */
    public static void check(ServerLevel level, Int2ObjectMap<Object> entityMap, EntityMaid maid) {
        try {
            if (maid == null || !maid.m_6084_() || maid.m_213877_()) {
                return;
            }
            if (!(maid.m_269323_() instanceof ServerPlayer owner) || owner.m_9236_() != maid.m_9236_()) {
                return; // 无主 / 主人不在这一维度：没有"该看得见她的人"，不插手
            }
            if (owner.m_20280_(maid) > NEAR_SQR) {
                return; // 太远：原版本来就不同步她（走近了原版自己会同步）
            }
            long now = level.m_46467_();
            UUID id = maid.m_20148_();
            Object tracked = entityMap.get(maid.m_19879_());
            if (tracked == null) {
                // 她的区块没在 entity-ticking：票立刻挪到她脚下（旧票每 5 秒才对账一次）
                MaidChunkLoadManager.ensureTicketNow(maid);
                log(now, id, maid, "她的区块没在 ticking → 已立刻补票（原版会自己把她同步过去）");
                return;
            }
            if (!(tracked instanceof RemoteTrackBridge bridge)) {
                return; // 混入没被我们的 mixin 实现过（理论上不会）：不猜，不插手
            }
            if (bridge.promaid$isPaired(owner)) {
                return; // 主人客户端认她：一切正常
            }
            Long last = LAST_FIX.get(id);
            if (last != null && now - last < RESYNC_COOLDOWN) {
                return;
            }
            if (!MaidResyncCommand.resyncToOwner(owner, maid)) {
                return; // 她的界面开着（重建会让 TLM 容器槽位抓着一只已删实体）→ 下一轮再试
            }
            LAST_FIX.put(id, now);
            log(now, id, maid, "主人客户端没有她 → 已当场重建（防「建模卡掉、幽灵状态」）");
        } catch (Throwable ignored) {
            // 自愈是兜底：这里出任何问题都不该影响区块 tick
        }
    }

    private static void log(long now, UUID id, EntityMaid maid, String text) {
        Long last = LAST_LOG.get(id);
        if (last != null && now - last < LOG_COOLDOWN) {
            return;
        }
        LAST_LOG.put(id, now);
        PromaidLog.log("重同步", PromaidLog.nameOf(maid) + " 可见性自愈：" + text);
    }
}
