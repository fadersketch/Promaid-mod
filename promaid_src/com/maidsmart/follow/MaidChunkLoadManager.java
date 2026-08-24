package com.maidsmart.follow;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v1.1.0 实测四十四：女仆区块强制加载（"约等于玩家"）。
 *
 * 背景（用户反馈两条）：
 * ① 跨维度跟随不是真传送——旧 MaidDimensionFollow 用
 *   setRemoved(CHANGED_DIMENSION)+setPos+addFreshEntity 手动搬家：实体不重新
 *   注册到新维度的实体存储（PersistentEntitySectionManager），客户端看不到
 *   真正的跨维度流程（无传送特效/可能与 changeDimension 的 Forge 事件链
 *   冲突），部分联动（魂符、追踪表）会把它当"已移除"。
 * ② 主人走远后女仆所在区块卸载 → 跨维度跟随扫描（遍历已加载实体）根本
 *   找不到她 → 永远不传送。
 *
 * 方案：每 5 秒对所有【有主且主人不在此维度】的女仆所在区块挂
 * "unknown" TicketType 强制加载票（2 级 = 实体 ticking，与玩家同等待遇），
 * 实体离开（传送走/收回魂符/死亡）自动撤票。区块保持加载 ⇒
 * - 跨维度跟随扫描每轮都能找到她（问题②根治）；
 * - 传送改用原版 Entity.teleportTo(m_264318_)，走完整跨维度流程（问题①根治）。
 *
 * 票生命周期：以女仆 UUID 为 key 维护 当前持有的票，每轮刷新时对比——
 * 女仆跨区块 → 撤旧票挂新票；女仆消失/同维度了 → 撤票。服务器停止清空。
 */
public final class MaidChunkLoadManager {
    private MaidChunkLoadManager() {
    }

    /** 自定义票（永不过期；名字带上 modid 便于 /forge tickets 排查） */
    private static final TicketType<Unit> MAID_TICKET =
            TicketType.m_9462_("promaid_maid", (a, b) -> 0);

    /** 加载等级：2 = 实体正常 ticking（玩家同级；4 只是区块加载不 tick 实体） */
    private static final int TICKET_LEVEL = 2;

    /** 当前持有的票：maidUuid → (dimension, chunkX, chunkZ) */
    private static final Map<UUID, TicketKey> ACTIVE_TICKETS = new ConcurrentHashMap<>();

    private record TicketKey(ResourceKey<net.minecraft.world.level.Level> dim, long chunk) {
    }

    /** v1.1.0 实测七十：最后出现位置登记——一键集合对"未加载区块里的女仆"的
     *  唯一线索（卸载后实体不在任何列表里，只能靠最后见到的位置去强载区块）。
     *  每 5 秒覆盖写，天然最新；召回失败时丢弃该条（多半已被魂符收回/死亡）。 */
    private record LastSeen(ResourceKey<net.minecraft.world.level.Level> dim, BlockPos pos,
                            UUID ownerId, long seenAt) {
    }

    private static final Map<UUID, LastSeen> LAST_SEEN = new ConcurrentHashMap<>();

    /** v1.1.0 实测七十：待召回队列（uuid → 状态）——持有强载票直到实体出现/超时 */
    private record PendingSummon(ResourceKey<net.minecraft.world.level.Level> dim,
                                 net.minecraft.world.level.ChunkPos chunk,
                                 net.minecraft.server.level.ServerPlayer owner, long expireGameTime) {
    }

    private static final Map<UUID, PendingSummon> PENDING_SUMMON = new ConcurrentHashMap<>();

    /** 每 100 tick（5 秒）由 ProMaidExtension.onServerTick 调用 */
    public static void tick(MinecraftServer server) {
        // v1.1.0 实测七十：登记全部在场有主女仆的最后出现位置（不受下方开关限制
        // ——这是"一键集合召回未加载区块女仆"的唯一线索）
        for (ServerLevel lvl : server.m_129785_()) {
            for (Entity e : lvl.m_8583_()) {
                if (!(e instanceof EntityMaid maid) || !maid.m_6084_()) {
                    continue;
                }
                LivingEntity ow = maid.m_269323_();
                if (ow != null) {
                    LAST_SEEN.put(maid.m_20148_(), new LastSeen(lvl.m_46472_(),
                            maid.m_20183_().m_7949_(), ow.m_20148_(), lvl.m_46467_()));
                }
            }
        }
        if (!com.maidsmart.config.MaidSmartConfig.MISC_MAID_CHUNK_LOAD.get()) {
            releaseAll(server);
            return;
        }
        // 1. 扫描所有维度已加载女仆，找需要挂票的（有主、主人在其他维度）
        Map<UUID, TicketKey> wanted = new java.util.HashMap<>();
        for (ServerLevel level : server.m_129785_()) {
            for (Entity e : level.m_8583_()) {
                if (!(e instanceof EntityMaid maid) || !maid.m_6084_()) {
                    continue;
                }
                try {
                    LivingEntity owner = maid.m_269323_();
                    if (owner == null) {
                        continue;
                    }
                    // 只给"与主人不同维度"的女仆挂票——同维度时区块由玩家自然加载，
                    // 不额外占内存（跟随在身边的挖矿/伐木女仆不吃票）
                    if (maid.m_9236_() == owner.m_9236_()) {
                        continue;
                    }
                    long chunk = new ChunkPos(maid.m_20183_()).m_45588_();
                    wanted.put(maid.m_20148_(),
                            new TicketKey(level.m_46472_(), chunk));
                } catch (Exception ignored) {
                }
            }
        }
        // 2. 对比持票表：不再需要的撤票 / 位置变了的换票
        for (Map.Entry<UUID, TicketKey> e : ACTIVE_TICKETS.entrySet()) {
            UUID id = e.getKey();
            TicketKey cur = e.getValue();
            TicketKey want = wanted.get(id);
            if (want != null && want.equals(cur)) {
                continue; // 票不变
            }
            removeTicket(server, id, cur);
        }
        // 3. 挂新票（m_8387_ = addRegionTicket，withRadius=false 版本在 Forge 专有）
        for (Map.Entry<UUID, TicketKey> e : wanted.entrySet()) {
            TicketKey want = e.getValue();
            TicketKey cur = ACTIVE_TICKETS.get(e.getKey());
            if (want.equals(cur)) {
                continue;
            }
            ServerLevel level = server.m_129880_(want.dim());
            if (level == null) {
                continue;
            }
            ServerChunkCache cache = level.m_7726_();
            cache.m_8387_(MAID_TICKET, new ChunkPos(want.chunk()), TICKET_LEVEL, Unit.INSTANCE);
            ACTIVE_TICKETS.put(e.getKey(), want);
        }
    }

    private static void removeTicket(MinecraftServer server, UUID id, TicketKey key) {
        ServerLevel level = server.m_129880_(key.dim());
        if (level != null) {
            try {
                level.m_7726_().m_8438_(MAID_TICKET,
                        new ChunkPos(key.chunk()), TICKET_LEVEL, Unit.INSTANCE);
            } catch (Exception ignored) {
            }
        }
        ACTIVE_TICKETS.remove(id);
    }

    /** 服务器停止/开关关闭：撤掉全部票（ProMaidExtension ServerStoppingEvent 调用） */
    public static void releaseAll(MinecraftServer server) {
        // v1.1.0 实测七十：待召回队列一并清场
        for (PendingSummon p : PENDING_SUMMON.values()) {
            ServerLevel lvl = server.m_129880_(p.dim());
            if (lvl != null) {
                try {
                    lvl.m_7726_().m_8438_(MAID_TICKET, p.chunk(), TICKET_LEVEL, Unit.INSTANCE);
                } catch (Exception ignored) {
                }
            }
        }
        PENDING_SUMMON.clear();
        for (Map.Entry<UUID, TicketKey> e : ACTIVE_TICKETS.entrySet()) {
            TicketKey key = e.getValue();
            ServerLevel level = server.m_129880_(key.dim());
            if (level != null) {
                try {
                    level.m_7726_().m_8438_(MAID_TICKET,
                            new ChunkPos(key.chunk()), TICKET_LEVEL, Unit.INSTANCE);
                } catch (Exception ignored) {
                }
            }
        }
        ACTIVE_TICKETS.clear();
    }

    /**
     * v1.5.142：女仆跟随主人跨维度传送（实测四十四重做传送本体）。
     *
     * 每 5 秒扫描一次全服女仆——跟随模式（非在家模式、未坐下、未骑乘、存活）
     * 且与主人不在同一维度 → teleportTo(m_264318_) 原版跨维度传送
     * （替代旧版 setRemoved+addFreshEntity 手动搬家：不走 Forge 维度事件链、
     * 实体不重新注册，属于"假传送"）。
     * 落点取主人身边第一个"脚下实心、站立格空气"的位置（向下最多 16 格）；
     * 找不到可站格（主人在高空/虚空飞行）→ 本次不传，等主人落地后再跟。
     *
     * 坐着的女仆不拉（建造模式强制坐下 = 玩家明确想让她留在原地，见
     * MaidBuildBehavior.tickBuildSit）；在家模式 = 不跟随，同样不拉。
     */
    public static void followIfCrossDimension(EntityMaid maid) {
        try {
            if (maid.m_213877_() || maid.m_21224_()) {
                return; // 已移除/死亡
            }
            if (maid.m_20159_()) {
                return; // 骑乘中（乘客跨维度跟随由载具负责，不单独拉）
            }
            if (maid.isMaidInSittingPose()) {
                return; // 坐着的女仆不拉（建造强制坐下 = 玩家要她留在原地）
            }
            if (maid.isHomeModeEnable()) {
                return; // 在家模式 = 不跟随
            }
            LivingEntity owner = maid.m_269323_();
            // v1.1.0 实测七十八（bug：主人下界死亡后看家女仆被传到下界基岩层上）——
            // 主人死亡期间实体仍在原位置（血量 0 但未移除），跟随链路照常触发，
            // 女仆被传到死亡点附近；死亡点在高位时向下找站立格，下界直接落在基岩
            // 顶层上面。主人不在存活状态一律不追
            if (owner == null || owner.m_21224_() || !owner.m_6084_()) {
                return;
            }
            if (maid.m_9236_() == owner.m_9236_()) {
                return; // 同一维度（f_19853_ 是 private，用 m_9236_() 取 Level）
            }
            if (!(owner.m_9236_() instanceof ServerLevel newLevel)
                    || !(maid.m_9236_() instanceof ServerLevel oldLevel)) {
                return;
            }
            BlockPos stand = findStand(newLevel,
                    new BlockPos((int) Math.floor(owner.m_20185_()),
                            (int) Math.floor(owner.m_20186_()),
                            (int) Math.floor(owner.m_20189_())));
            if (stand == null) {
                return; // 主人身边 16 格内无可站立点（高空飞行/虚空）→ 等落地再跟
            }
            // 实测四十四：原版跨维度传送（m_264318_ = teleportTo）——内部走完整的
            // changeDimension 流程（Forge 事件链 + 实体重新注册 + 客户端维度同步），
            // 是"真传送"；旧版手动 setRemoved+addFreshEntity 会被 PersistentEntitySectionManager
            // 当成"已移除实体"处理，属于假传送
            maid.m_264318_(newLevel, stand.m_123341_() + 0.5, stand.m_123342_(),
                    stand.m_123343_() + 0.5, java.util.Collections.emptySet(),
                    owner.m_146908_(), owner.m_146909_());
            // 传送后清理：摔落距离归零 + 停止旧导航 + 清残留速度
            maid.f_19789_ = 0.0f;
            maid.m_21573_().m_26569_();
            maid.m_20256_(net.minecraft.world.phys.Vec3.f_82478_);
            // 末影人传送音效（提示玩家女仆跟过来了）
            newLevel.m_5594_(null, stand, net.minecraft.sounds.SoundEvents.f_11852_,
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.0f);
        } catch (Exception ignored) {
        }
    }

    /**
     * v1.1.0 实测七十：一键集合入口（SummonPacket 调用）。
     * ① 扫描全部维度的在场女仆：坐着/骑乘/在家模式（含排班自动 home）的保持
     * 原位——实测七十八起 home 女仆恢复"不响应传送"，想召回先解除她的排班/
     * 在家模式；已在身边的不折腾；其余走 summonMaidTo 真传送（跨维度通用）。
     * ② 不在场（未加载区块）的女仆：查 LAST_SEEN 最后出现位置，挂强载票 +
     * 进待召回队列，由 tickPending 每 tick 推进——实体一出现就自动传回并回报。
     */
    public static SummonReport summonAll(net.minecraft.server.level.ServerPlayer player) {
        int summoned = 0;
        int kept = 0;
        int failStand = 0;
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        java.util.UUID pid = player.m_20148_();
        for (ServerLevel lvl : player.m_9236_().m_7654_().m_129785_()) {
            for (Entity e : lvl.m_8583_()) {
                if (!(e instanceof EntityMaid md) || !md.m_6084_() || !md.m_21830_(player)) {
                    continue;
                }
                seen.add(md.m_20148_());
                // v1.1.0 实测七十八：home（在家）模式恢复豁免——看家的不该被一键
                // 集合拽走（排班自动 home 的同理：想召回先关排班）
                if (md.isMaidInSittingPose() || md.m_20159_() || md.isHomeModeEnable()) {
                    kept++;
                    continue;
                }
                if (lvl == player.m_9236_() && md.m_20238_(player.m_20182_()) < 25.0) {
                    continue; // 已在身边 5 格内
                }
                if (summonMaidTo(md, player)) {
                    summoned++;
                } else {
                    failStand++;
                }
            }
        }
        // 未加载区块里的：按最后出现位置挂强载票 + 进待召回队列
        int pending = 0;
        MinecraftServer server = player.m_9236_().m_7654_();
        long now = player.m_9236_().m_46467_();
        for (Map.Entry<UUID, LastSeen> en : LAST_SEEN.entrySet()) {
            LastSeen ls = en.getValue();
            if (!ls.ownerId().equals(pid) || seen.contains(en.getKey())) {
                continue;
            }
            if (PENDING_SUMMON.containsKey(en.getKey())) {
                pending++; // 已在队列（重复点集合不叠票）
                continue;
            }
            ServerLevel lvl = server.m_129880_(ls.dim());
            if (lvl == null) {
                continue;
            }
            net.minecraft.world.level.ChunkPos cp =
                    new net.minecraft.world.level.ChunkPos(ls.pos());
            lvl.m_7726_().m_8387_(MAID_TICKET, cp, TICKET_LEVEL, Unit.INSTANCE);
            PENDING_SUMMON.put(en.getKey(),
                    new PendingSummon(ls.dim(), cp, player, now + 300L));
            pending++;
        }
        return new SummonReport(summoned, kept, failStand, pending);
    }

    /** 集合结果汇总（聊天栏播报用） */
    public record SummonReport(int summoned, int kept, int failStand, int pending) {
    }

    /**
     * v1.1.0 实测七十：每 tick 推进待召回队列（ProMaidExtension 每tick调用；
     * 空队列零开销）。区块加载完成后女仆实体出现 → 自动传回主人身边并聊天栏
     * 回报；300 tick（15 秒）还没等到（被收回/死亡/位置失效）→ 放弃、撤票、
     * 丢弃该位置记录。
     */
    public static void tickPending(MinecraftServer server) {
        if (PENDING_SUMMON.isEmpty()) {
            return;
        }
        java.util.Iterator<Map.Entry<UUID, PendingSummon>> it =
                PENDING_SUMMON.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PendingSummon> en = it.next();
            PendingSummon p = en.getValue();
            net.minecraft.server.level.ServerPlayer owner = p.owner();
            boolean ownerGone = owner == null || owner.m_21224_() || !owner.m_6084_()
                    || !(owner.m_9236_() instanceof ServerLevel);
            long now = ownerGone ? 0 : ((ServerLevel) owner.m_9236_()).m_46467_();
            if (ownerGone || now > p.expireGameTime()) {
                releasePendingTicket(server, p);
                if (!ownerGone && now > p.expireGameTime()) {
                    try {
                        owner.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                                "§7【集合】有一名女仆没能等到（可能已被魂符收回或不在了）"));
                    } catch (Exception ignored) {
                    }
                    LAST_SEEN.remove(en.getKey()); // 位置多半失效，别再拿它召回
                }
                it.remove();
                continue;
            }
            // 只扫目标维度（区块刚被我们强载，实体出现就在那里）
            ServerLevel lvl = server.m_129880_(p.dim());
            if (lvl == null) {
                releasePendingTicket(server, p);
                it.remove();
                continue;
            }
            for (Entity e : lvl.m_8583_()) {
                if (e instanceof EntityMaid md && en.getKey().equals(md.m_20148_())
                        && md.m_21830_(owner)) {
                    if (md.isHomeModeEnable() || md.isMaidInSittingPose() || md.m_20159_()) {
                        // v1.1.0 实测七十八：强载出来才发现是 home/坐着/骑乘 → 不拽，
                        // 撤票收队（强载票只为找到她，去留按同一套豁免判定）
                        releasePendingTicket(server, p);
                        it.remove();
                        break;
                    }
                    boolean ok = summonMaidTo(md, owner);
                    String name = md.m_5446_() != null ? md.m_5446_().getString() : "女仆";
                    try {
                        owner.m_213846_(net.minecraft.network.chat.Component.m_237113_(ok
                                ? "§a【集合】" + name + " 已从未加载的区块召回"
                                : "§c【集合】" + name + " 召回了但身边没有可站立点"));
                    } catch (Exception ignored) {
                    }
                    releasePendingTicket(server, p);
                    it.remove();
                    break;
                }
            }
        }
    }

    /** 撤掉待召回条目持有的强载票 */
    private static void releasePendingTicket(MinecraftServer server, PendingSummon p) {
        ServerLevel lvl = server.m_129880_(p.dim());
        if (lvl != null) {
            try {
                lvl.m_7726_().m_8438_(MAID_TICKET, p.chunk(), TICKET_LEVEL, Unit.INSTANCE);
            } catch (Exception ignored) {
            }
        }
    }

    /** 从主人所在格向下找第一个"站立格空气 + 脚下实心不悬空"的位置（最多 16 格） */
    private static BlockPos findStand(ServerLevel level, BlockPos from) {
        BlockPos cur = from;
        for (int i = 0; i < 16; i++) {
            BlockState st = level.m_8055_(cur);
            BlockPos belowPos = cur.m_7495_();
            BlockState below = level.m_8055_(belowPos);
            if (st.m_60795_() && !below.m_60795_() && !below.m_60815_()
                    && below.m_60796_(level, belowPos)) {
                return cur;
            }
            cur = belowPos;
        }
        return null;
    }

    /**
     * v1.1.0 实测六十：一键集合——把女仆传送到主人身边（跨维度/同维度通用）。
     * 排班表列表页「一键集合」按钮调用；复用 followIfCrossDimension 的真传送链路
     * （m_264318_ 原版 teleportTo + 摔落/导航/速度清理 + 末影人音效）。
     *
     * @return true = 传送成功；false = 无可站立点（主人高空/虚空）或女仆状态异常
     */
    public static boolean summonMaidTo(EntityMaid maid, LivingEntity owner) {
        try {
            if (maid.m_213877_() || maid.m_21224_() || maid.m_20159_()) {
                return false; // 已移除/死亡/骑乘中
            }
            // v1.1.0 实测七十八：home（在家）模式的女仆不被任何传送打扰——守家钉死；
            // 主人已死亡也不传送（防传到死亡点/基岩顶）。本方法是所有传送的咽喉点
            if (maid.isHomeModeEnable() || !owner.m_6084_()) {
                return false;
            }
            if (!(owner.m_9236_() instanceof ServerLevel dest)) {
                return false;
            }
            BlockPos stand = findStand(dest,
                    new BlockPos((int) Math.floor(owner.m_20185_()),
                            (int) Math.floor(owner.m_20186_()),
                            (int) Math.floor(owner.m_20189_())));
            if (stand == null) {
                return false; // 主人身边 16 格内无可站立点
            }
            maid.m_264318_(dest, stand.m_123341_() + 0.5, stand.m_123342_(),
                    stand.m_123343_() + 0.5, java.util.Collections.emptySet(),
                    owner.m_146908_(), owner.m_146909_());
            maid.f_19789_ = 0.0f;
            maid.m_21573_().m_26569_();
            maid.m_20256_(net.minecraft.world.phys.Vec3.f_82478_);
            dest.m_5594_(null, stand, net.minecraft.sounds.SoundEvents.f_11852_,
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.0f);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
