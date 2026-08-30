package com.maidsmart.follow;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 * 方案：每 5 秒对所有【有主且处于活动状态】的女仆所在区块挂
 * "unknown" TicketType 强制加载票（2 级 = 实体正常 ticking，与玩家同等待遇），
 * 实体离开（传送走/收回魂符/死亡）自动撤票。区块保持加载 ⇒
 * - 跨维度跟随扫描每轮都能找到她（问题②根治）；
 * - 传送改用原版 Entity.teleportTo(m_264318_)，走完整跨维度流程（问题①根治）。
 *
 * v1.1.0 实测八十八【持续加载】：票务范围从"仅异维度"扩大到全部有主活动女仆
 * （同维度跟随女仆落后主人超过模拟距离时区块会卸载、AI 冻结，TLM 的过远传送
 * 永远无法触发）。
 * v1.1.0 实测八十八b：home/坐姿/骑乘不豁免【加载】——三态只豁免传送；停放
 * 女仆的区块同样保持 ticking（周边农场/熔炉照常运转，随时可被找到）。
 *
 * 票生命周期：以女仆 UUID 为 key 维护 当前持有的票，每轮刷新时对比——
 * 女仆跨区块 → 撤旧票挂新票；女仆消失/转入停放态 → 撤票。服务器停止清空。
 *
 * v1.1.0 实测一百三十一：跨维度跟随 "home 不拦"——home 只拦同维度跟随 (TLM
 * MaidFollowOwnerTask 照旧)，跨维度（玩家过 portal/传到他维度）一直传。
 * 根源：实测七十给排班启用女仆自动 home，home 挡调 = 排班女仆不永远在
 * 下界/任何地方。语义：玩家要她守家，在 TLM GUI 主动点 home（SummonPacket
 * 一键召集保留 home 拦停）。
 */
public final class MaidChunkLoadManager {
    private static final org.slf4j.Logger LOGGER =
            com.mojang.logging.LogUtils.getLogger();

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
     *  每 5 秒覆盖写，天然最新；召回失败时丢弃该条（多半已被魂符收回/死亡）。
     *  v1.1.0 实测八十七c：快照 stayPut 三态豁免（home/坐姿/骑乘）——未加载区块里
     *  读不到 persistentData，没有这份快照就无法在集合时跳过她们，导致白挂强载票 +
     *  静默收队（用户视角="点了集合却石沉大海"）。 */
    private record LastSeen(ResourceKey<net.minecraft.world.level.Level> dim, BlockPos pos,
                            UUID ownerId, long seenAt, boolean stayPut) {
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
                    // v1.1.0 实测八十七c：同步快照三态豁免（home/坐姿/骑乘）
                    boolean stayPut = maid.isHomeModeEnable()
                            || maid.isMaidInSittingPose() || maid.m_20159_();
                    LAST_SEEN.put(maid.m_20148_(), new LastSeen(lvl.m_46472_(),
                            maid.m_20183_().m_7949_(), ow.m_20148_(), lvl.m_46467_(), stayPut));
                    // v1.1.0 实测七十九：受困救援——下界基岩顶层/虚空中的女仆自动传回
                    // 存活主人身边（跨维度通用；已在主人 8 格内不触发，防屋顶住户循环）
                    if (com.maidsmart.config.MaidSmartConfig.MISC_MAID_RESCUE.get()
                            && ow.m_6084_() && needsRescue(lvl, maid)
                            && maid.m_20238_(ow.m_20182_()) >= 64.0) {
                        double fromY = maid.m_20186_();
                        if (teleportCore(maid, ow)) {
                            // v1.1.0 实测一百四十四：日志带上维度最低建筑高度（min=）——
                            // 救援触发即"真虚空"的现场证据，映射再错一眼可见
                            LOGGER.info("maid rescue: id={} dim={} y={} min={}->owner side",
                                    maid.m_20148_(), lvl.m_46472_().m_135782_(), (int) fromY,
                                    lvl.m_141937_());
                        }
                    }
                }
            }
        }
        if (!com.maidsmart.config.MaidSmartConfig.MISC_MAID_CHUNK_LOAD.get()) {
            releaseAll(server);
            return;
        }
        // 1. 扫描所有维度已加载女仆，找需要挂票的。
        // v1.1.0 实测八十八【持续加载】：取消"仅异维度"限制——旧版只给跨维度女仆
        // 挂票，同维度跟随的女仆一旦落后主人超过模拟距离，所在区块卸载、AI 冻结，
        // TLM 的"离主人过远自动传送"永远无法触发（用户："无法再传送过来了；
        // 女仆所在区块应该持续加载，参考区块加载器"）。现在除三态豁免
        // （home/坐姿/骑乘 = 玩家明确停放，冻结无碍）外全部持续加载。
        Map<UUID, TicketKey> wanted = new java.util.HashMap<>();
        for (ServerLevel level : server.m_129785_()) {
            for (Entity e : level.m_8583_()) {
                if (!(e instanceof EntityMaid maid) || !maid.m_6084_()) {
                    continue;
                }
                try {
                    LivingEntity owner = maid.m_269323_();
                    if (owner == null) {
                        continue; // 无主野女仆不保载
                    }
                    // v1.1.0 实测八十八b：home/坐姿/骑乘不豁免【区块加载】——三态豁免的
                    // 是传送，不是加载。停放的女仆所在区块同样保持 ticking（她只是不走动，
                    // 但周边农场/熔炉照常运转，也随时可被找到），与用户确认的口径一致。
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
     * 每 5 秒扫描一次全服女仆——存活、未坐下、未骑乘、与主人不在同一维度 →
     * teleportTo(m_264318_) 原版跨维度传送
     * （替代旧版 setRemoved+addFreshEntity 手动搬家：不走 Forge 维度事件链、
     * 实体不重新注册，属于"假传送"）。
     * 落点取主人身边第一个"脚下实心、站立格空气"的位置（向下最多 16 格）；
     * 找不到可站格（主人在高空/虚空飞行）→ 本次不传，等主人落地后再跟。
     *
     * 坐着的女仆不拉（建造模式强制坐下 = 玩家明确想让她留在原地，见
     * MaidBuildBehavior.tickBuildSit）。
     * v1.1.0 实测一百八十五（用户："排班中的女仆和处于 home 模式的女仆仍然会
     * 响应跨维度传送"）：在家/排班模式【拦截】跨维度跟随——一百三十一"home 不拦"
     * 的旧口径反转：home = 守家，主人过门/换维度也不跟（与同维度拉回、一键集合
     * 的口径一致）；想召回先解除她的排班/在家模式（见 summonAll）。
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
            // v1.1.0 实测一百八十五：排班/在家模式 → 跨维度也不传（旧版漏判——
            // 一百三十一口径是"home 不拦跨维"；home 女仆被拉到主人新维度，
            // 守家/排班锚点全废）。本扫描每 5 秒跑全服，节流日志防刷屏。
            // v1.1.0 实测一百九十六【自保 vs 自动传送矛盾根治】（用户："自保逃跑禁用
            // 传送跟跨维度传送、跨区块传送，远距离传送会有矛盾吗？"）：PRESERVE 标记
            // 旧版只拦了 TLM 原生传送 / 跟随拉近（FollowPreserveMixin、MaidTeleport
            // PreserveMixin），我们自己的跨维度跟随与同维度拉回链路没读它——低血/逃跑
            // 中的女仆会被跨维或远距自动传送拉回主人身边（威胁点）送死 = 矛盾。
            // 自保中一律不被自动拉回；玩家【主动一键集合】不受影响（人工意图优先）。
            if (maid.getPersistentData().m_128471_(
                    com.maidsmart.combat.SelfPreservationBehavior.PRESERVE_TAG)) {
                return;
            }
            boolean scheduled = false;
            try {
                scheduled = com.maidsmart.schedule.ScheduleData.isOn(maid);
            } catch (Throwable ignored) {
            }
            if (maid.isHomeModeEnable() || scheduled) {
                throttledSkipLog(maid, "home-cross", com.maidsmart.tool.PromaidLog.nameOf(maid)
                        + " 排班/在家模式中，跨维度不传（想召回先解除排班/在家模式）");
                return;
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
            // v1.1.0 实测一百三十四：同一维度 → 远距拉回兜底（TLM 自带"过远自动
            // 传送"只对 非home+非工作+同维度 的跟随女仆触发，且 teleportToOwner 的
            // ±3 格随机试探可能静默失败；这里统一补一道可靠的同维度远距拉回）
            trySameDimPull(maid, owner);
            return;
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
                // v1.1.0 实测一百三十四：失败路径落日志（旧版静默 return——"为什么不
                // 传"完全不可见；主人在高空/虚空时先等落地，落地点出现后自动再试）
                throttledSkipLog(maid, "nostand", com.maidsmart.tool.PromaidLog.nameOf(maid)
                        + " 跨维度跟随：主人身边 16 格内无可站立点（高空/虚空）——等落地后再传");
                return;
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
            // v1.1.0 实测九十四：运行日志——跨维跟随事件落盘
            com.maidsmart.tool.PromaidLog.log("跨维", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 跟随主人跨维传送至 "
                    + stand.m_123341_() + "," + stand.m_123342_() + "," + stand.m_123343_());
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
            // v1.1.0 实测八十七c：快照为 home/坐/骑 → 不强载、不建队，计入保持原位
            //（旧版会白挂强载票把区块载进来才发现要豁免，然后静默收队）
            if (ls.stayPut()) {
                kept++;
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
                        // v1.1.0 实测八十七c：补播报——旧版静默收队，玩家以为集合失败
                        try {
                            String name = md.m_5446_() != null ? md.m_5446_().getString() : "女仆";
                            owner.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                                    "§7【集合】" + name + " 在家模式/坐姿中，保持原位（想召回先解除她的排班/在家模式）"));
                        } catch (Exception ignored) {
                        }
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

    /**
     * v1.1.0 实测七十九：受困判定；实测八十一按 1.20.1 维度真实特性修正——
     * 主世界 y∈[-64,319]、下界 y∈[0,255]（y=127 整层基岩天花板，站面 y≥128）、
     * 末地 y∈[0,255]。旧版虚空判定硬编码 y<-60 会误伤超平坦世界——默认超平坦
     * 的草方块顶面恰好就是 y=-60（站在地表的女仆被当成"掉出世界"反复救援）。
     * 现在虚空判定改用维度自身 getMinBuildHeight（主世界 -64 / 下界与末地 0），
     * 只有真正掉出该维度最低建筑高度才触发。下界基岩顶阈值维持 ≥126（顶层
     * 方块占 127、站面 128，与 findSafeLanding 的落点上限 124 留一格缓冲）。
     */
    private static boolean needsRescue(ServerLevel level, EntityMaid maid) {
        String dim = level.m_46472_().m_135782_().m_135815_();
        double y = maid.m_20186_();
        if ("the_nether".equals(dim) && y >= 126.0) {
            return true; // 下界基岩顶层上方滞留（顶层方块占 y=127）
        }
        // v1.1.0 实测一百四十四【排班女仆不断瞬移根治】：旧版误用 m_141928_ =
        // getHeight（主世界 384）当最低建筑高度——"y < 384"对所有站立女仆恒真，
        // 受困救援把每只距主人 >8 格的女仆（含守家/排班女仆）每 5 秒拽回主人身边
        // 一次（日志实证：排班锚点 (91,-60,139) 的女仆被当成"掉出世界"循环救援）。
        // 正确映射 m_141937_ = getMinBuildHeight（主世界 -64 / 下界与末地 0，
        // javap Level.getHeight 实证：未加载兜底返回 m_141937_ = getMinBuildHeight），
        // 只有真正掉出维度最低建筑高度以下才触发救援。
        return y < level.m_141937_(); // 掉出本维度最低建筑高度以下 = 真虚空
    }

    /**
     * 从主人所在格附近找"站立格空气 + 脚下实心不悬空"的位置。
     * v1.1.0 实测八十三：旧版只从主人脚下一路【向下】扫 16 格——下界桥面/
     * 熔岩海高架/悬崖地形正下方常无地面（悬空 100 格），直接判"无可站立点"
     * → 一键集合报"N 名因身边无可站立点未动"、跨维度跟随也卡住。现在：
     * ①原点柱向下 16（保留旧语义，落点贴主人脚下）；②再向上 12
     * （主人站在屋檐下/洞口时旁边有台面）；③水平外环半径 1~3 逐列扫描
     * （沿桥面/平台横走一格就能落脚）。
     */
    private static BlockPos findStand(ServerLevel level, BlockPos from) {
        BlockPos hit = scanColumn(level, from);
        if (hit != null) {
            return hit;
        }
        for (int r = 1; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    // 只扫外环（r=1 九宫格边圈 → r=2 → r=3，由近及远）
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    BlockPos h = scanColumn(level, from.m_7918_(dx, 0, dz));
                    if (h != null) {
                        return h;
                    }
                }
            }
        }
        // v1.1.0 实测一百四十：全失败落日志（60 秒限频）——脚格/下格/头顶方块 ID
        // 直接暴露是哪个判据误杀（下界的火/火把/台阶/窄道等）
        long now = level.m_46467_();
        if (LAST_STAND_FAIL_LOG == Long.MIN_VALUE || now - LAST_STAND_FAIL_LOG >= 1200L) {
            LAST_STAND_FAIL_LOG = now;
            com.maidsmart.tool.PromaidLog.log("跨维",
                    "findStand 全失败 @(" + from.m_123341_() + "," + from.m_123342_() + ","
                            + from.m_123343_() + ") 脚格=" + idAt(level, from)
                            + " 下格=" + idAt(level, from.m_7495_())
                            + " 头顶=" + idAt(level, from.m_7918_(0, 1, 0))
                            + "（加载=" + level.m_46749_(from) + "）");
        }
        return null;
    }

    /** v1.1.0 实测二百零五：安全落点公共入口——自保传回（SelfPreservationBehavior
     *  teleportHome/teleportHomeOnExit）复用同一判定：主人身边 16 格内（下 16/上 12 +
     *  水平环 r≤3）有可站立格才返回，否则 null（主人飞行/悬空/虚空边缘——不传，
     *  宁可她走路归队也不冒半空坠落摔死风险）。 */
    public static BlockPos findStandNear(ServerLevel level, BlockPos from) {
        return findStand(level, from);
    }

    /** 单柱扫描：从起始高度先向下最多 16 格、再向上最多 12 格，找可站立的格子。
     *  v1.1.0 实测一百四十（参考 tlm_beyond_space SafeTeleportService.canStandAt）：
     *  判定从"站立格 isAir + 脚下 isSolid 满方块"放宽为"站立格/头顶碰撞箱为空 +
     *  脚下有碰撞面 + 无流体"——旧判定在下界（脚下火/火把/台阶/栅栏/1 格窄道）几乎
     *  必挂，是"一传送到下界就提示无落脚点"的根因 */
    private static BlockPos scanColumn(ServerLevel level, BlockPos col) {
        BlockPos cur = col;
        for (int i = 0; i < 16; i++) {
            if (standableCell(level, cur)) {
                return cur;
            }
            cur = cur.m_7495_();
        }
        cur = col.m_7918_(0, 1, 0);
        for (int i = 0; i < 12; i++) {
            if (standableCell(level, cur)) {
                return cur;
            }
            cur = cur.m_7918_(0, 1, 0);
        }
        return null;
    }

    /**
     * v1.1.0 实测一百四十：站立格可靠判定（参考 tlm_beyond_space 的 canStandAt）——
     * ① 区块已加载（不触发加载）；② 脚下有碰撞面（不限满方块——台阶/栅栏可站）；
     * ③ 站立格与头顶碰撞箱为空（火/火把/草丛等无碰撞方块不挡）；④ 站立格与头顶无
     * 流体；⑤ 命中危险表（岩浆/火）不落；⑥ 目标格无存活实体占用（防传进玩家身体
     * 被碰撞挤走，与 DangerEscapeHandler 同口径）。
     */
    private static boolean standableCell(ServerLevel level, BlockPos c) {
        try {
            if (!level.m_46749_(c)) {
                return false;
            }
            BlockPos belowPos = c.m_7495_();
            if (level.m_8055_(belowPos).m_60742_(level, belowPos,
                    net.minecraft.world.phys.shapes.CollisionContext.m_82749_()).m_83281_()) {
                return false; // 脚下无碰撞面
            }
            if (!level.m_8055_(c).m_60742_(level, c,
                    net.minecraft.world.phys.shapes.CollisionContext.m_82749_()).m_83281_()) {
                return false; // 站立格有碰撞方块
            }
            BlockPos headPos = c.m_7918_(0, 1, 0);
            if (!level.m_8055_(headPos).m_60742_(level, headPos,
                    net.minecraft.world.phys.shapes.CollisionContext.m_82749_()).m_83281_()) {
                return false; // 头顶有碰撞方块
            }
            if (!level.m_8055_(c).m_60819_().m_76178_()
                    || !level.m_8055_(headPos).m_60819_().m_76178_()) {
                return false; // 站立格/头顶有流体
            }
            if (com.maidsmart.tool.DangerBlocks.cellDangerous(level,
                    c.m_123341_(), c.m_123342_(), c.m_123343_())) {
                return false; // 危险格不落（岩浆/火等）
            }
            net.minecraft.world.phys.AABB box =
                    new net.minecraft.world.phys.AABB(c).m_82400_(-0.05);
            if (!level.m_45976_(net.minecraft.world.entity.LivingEntity.class, box).isEmpty()) {
                return false; // 格被实体占用
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** findStand 全失败诊断日志限频（gameTime） */
    private static long LAST_STAND_FAIL_LOG = Long.MIN_VALUE;

    private static String idAt(ServerLevel level, BlockPos p) {
        try {
            net.minecraft.resources.ResourceLocation rl =
                    net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(level.m_8055_(p).m_60734_());
            return rl == null ? "?" : rl.toString();
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * v1.1.0 实测二百零八：日程表详情页「传送到我身边」——只召唤指定 UUID 这一只
     * 女仆（跨维度查找任意已加载世界）。豁免口径与一键集合一致：死亡/骑乘/坐着/
     * 在家模式（排班自动 home）保持原位——想强制召回先关闭她的排班；玩家主动操作
     * 不受"干活中不拉/搭路中不拉"等自动拉回限制（人工意图优先）。
     *
     * @return 0=不在已加载区块/不是主人的女仆；1=已传回；2=主人身边无可站立点；
     *         3=状态豁免（坐/骑/家/死亡）
     */
    public static int summonOne(ServerPlayer player, String uuid) {
        try {
            if (player == null || uuid == null || uuid.isEmpty()) {
                return 0;
            }
            java.util.UUID uid = java.util.UUID.fromString(uuid);
            EntityMaid maid = null;
            for (ServerLevel lvl : player.m_9236_().m_7654_().m_129785_()) {
                net.minecraft.world.entity.Entity e = lvl.m_8791_(uid);
                if (e instanceof EntityMaid m && m.m_6084_()) {
                    maid = m;
                    break;
                }
            }
            if (maid == null) {
                return 0;
            }
            if (!maid.m_21830_(player)) {
                return 0; // 非主人的女仆（安全兜底）
            }
            if (maid.m_213877_() || maid.m_21224_() || maid.m_20159_()
                    || maid.isMaidInSittingPose() || maid.isHomeModeEnable()) {
                return 3; // 状态豁免（坐/骑/家/死亡——与一键集合同口径）
            }
            return teleportCore(maid, player) ? 1 : 2;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * v1.1.0 实测六十：一键集合——把女仆传送到主人身边（跨维度/同维度通用）。
     * 排班表列表页「一键集合」按钮调用；复用 followIfCrossDimension 的真传送链路
     * （m_264318_ 原版 teleportTo + 摔落/导航/速度清理 + 末影人音效）。
     *
     * @return true = 传送成功；false = 无可站立点（主人高空/虚空）或女仆状态异常
     */
    public static boolean summonMaidTo(EntityMaid maid, LivingEntity owner) {
        // 咽喉点判定（实测七十八）：home（在家）模式不被任何传送打扰——守家钉死；
        // 主人非存活不传（防传到死亡点/基岩顶）
        if (maid.m_213877_() || maid.m_21224_() || maid.m_20159_()) {
            return false; // 已移除/死亡/骑乘中
        }
        if (maid.isHomeModeEnable() || !owner.m_6084_()) {
            return false;
        }
        return teleportCore(maid, owner);
    }

    /**
     * v1.1.0 实测七十九：传送本体（不含豁免判定）——救援路径复用。受困女仆即使是
     * home 模式也要能被捞回来（基岩顶不是家）；主人存活性由调用方保证。
     */
    private static boolean teleportCore(EntityMaid maid, LivingEntity owner) {
        try {
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

    /** v1.1.0 实测一百三十四：跳过/失败原因落日志限频（女仆|原因 → 上次记录 gameTime，
     *  60 秒一条防刷屏——只对"本该拉但没拉"的场景留痕，正常近距离全静默） */
    private static final Map<String, Long> SKIP_LOG_SINCE = new java.util.concurrent.ConcurrentHashMap<>();

    private static void throttledSkipLog(EntityMaid maid, String reason, String msg) {
        try {
            String key = maid.m_20148_() + "|" + reason;
            long now = maid.m_9236_().m_46467_();
            Long last = SKIP_LOG_SINCE.get(key);
            if (last != null && now - last < 1200L) {
                return;
            }
            if (SKIP_LOG_SINCE.size() > 4096) {
                SKIP_LOG_SINCE.clear();
            }
            SKIP_LOG_SINCE.put(key, now);
            com.maidsmart.tool.PromaidLog.log("跨维", msg);
        } catch (Exception ignored) {
        }
    }

    /**
     * v1.1.0 实测一百三十四：同维度远距拉回（跨区块传送的真正补丁）。
     *
     * 背景：跨区块（同维度距离过远）的自动传送此前【完全依赖 TLM 自带机制】——
     * MaidFollowOwnerTask 的 teleportToOwner 只对 非 home + 可脑动 + 主人同维度 的
     * 跟随女仆触发，且 10 次 ±3 随机试探可能全部落空（悬崖/窄道/主人飞行）而静默
     * 失败；排班自动 home 的女仆同维度更是永远不被 TLM 拉。这就是"修了五六次
     * 修不好"的实质：每次修的都是跨维度或区块保载，同维度远距拉回要么不存在、
     * 要么是 TLM 的随机静默失败。
     *
     * 本方法用与跨维度同款的可靠链路（findStand + teleportTo 真传送）补同维度兜底：
     * 距离超过阈值、非守家、非坐/骑、没在干重活（挖矿/伐木/建造未暂停/烹饪酿造站桩）
     * 就拉回主人身边。守家/干活中不拉，但会落日志说明原因（60 秒限频）——"为什么不
     * 传"从此可见。
     */
    private static void trySameDimPull(EntityMaid maid, LivingEntity owner) {
        try {
            if (!com.maidsmart.config.MaidSmartConfig.MISC_MAID_SAME_DIM_PULL.get()) {
                return;
            }
            int dist = com.maidsmart.config.MaidSmartConfig.MISC_MAID_SAME_DIM_DIST.get();
            double dSq = maid.m_20275_(owner.m_20185_(), owner.m_20186_(), owner.m_20189_());
            if (dSq < (double) dist * dist) {
                // v1.1.0 实测一百八十八（用户："传送机制不检测 Y 轴。女仆搭得太高不会
                // 自己传送下来"）：3D 距离未过线但【垂直高度差】超阈值 → 按 Y 轴拉回
                // 判定继续（水平贴身、竖直搭高 30 格时 3D 距离 900 < 48²，旧版永远不触发）
                double dyAbs = Math.abs(maid.m_20186_() - owner.m_20186_());
                if (dyAbs < com.maidsmart.config.MaidSmartConfig.MISC_MAID_SAME_DIM_VERTICAL.get()) {
                    return; // 水平不远、垂直也不远——走路/跟随正常处理，不打扰
                }
            }
            int blocks = (int) Math.sqrt(dSq);
            // 实测一百八十八：Y 轴分支标记（日志措辞区分——同一条链路，同一个安全落点判定）
            boolean yPull = dSq < (double) dist * dist;
            String name = com.maidsmart.tool.PromaidLog.nameOf(maid);
            // v1.1.0 实测一百九十六：自保中不拉回（与跨维跟随同口径——低血/逃跑中拉
            // 回主人身边=送死；PRESERVE 期间由自保行为自己决定去向）
            if (maid.getPersistentData().m_128471_(
                    com.maidsmart.combat.SelfPreservationBehavior.PRESERVE_TAG)) {
                throttledSkipLog(maid, "sam-dim-preserve", name
                        + " 自保中（低血/逃跑/垫高），不拉回——自保行为自己应变");
                return;
            }
            // 守家/干活中不拉，但落日志（限频）——这正是"她不回来"的可见原因
            if (maid.isHomeModeEnable()) {
                throttledSkipLog(maid, "sam-dim-home", name + " 同维度距离 " + blocks
                        + " 格但守家中，不拉（想召回先解除排班/在家模式）");
                return;
            }
            if (com.maidsmart.task.BridgeUpBehavior.isTaskOccupied(maid)) {
                throttledSkipLog(maid, "sam-dim-work", name + " 同维度距离 " + blocks
                        + " 格但干活中（挖矿/伐木/建造/站桩），不打断——任务结束或空闲后再拉");
                return;
            }
            // v1.1.0 实测二百零七：搭路中的女仆【水平拉回】不执行——她正踩在自己铺的
            // 半空桥上，拉走=抽掉她脚下的桥；但 实测二百一十六 用户反馈「高度差过大
            // 强制传送没生效」——根因正是这条闸门把一百八十八的 Y 轴拉回连带拦掉：
            // "搭太高"恰恰发生在她自己垫的高柱/桥上。Y 轴分支（yPull）是把她往主人
            // 旁边【已验证的安全落点】传下来（不是拉离），且二百零七②的近距刷新保证
            // 她脚下桥块不会立刻回收——所以 Y 轴拉回在搭路中【放行】。
            if (!yPull && maid.getPersistentData().m_128471_(
                    com.maidsmart.task.BridgeUpBehavior.BRIDGING_TAG)) {
                throttledSkipLog(maid, "sam-dim-bridging", name + " 正在搭路（半空结构上），"
                        + "不拉回——搭路自己会铺到主人脚边，拉走会抽掉她脚下的桥（Y 轴搭太高拉回不受此限）");
                return;
            }
            if (teleportCore(maid, owner)) {
                // 实测一百八十八：Y 轴成功路径留痕（找得到安全落点才传）
                com.maidsmart.tool.PromaidLog.log("跨维", name + (yPull
                        ? " Y 轴距离 " + (int) Math.abs(maid.m_20186_() - owner.m_20186_())
                        + " 格（搭太高），主人旁有安全落点 → 传送下来"
                        : " 同维度远距拉回至主人身边（原距 " + blocks + " 格）"));
            } else {
                // 实测一百八十八：Y 轴失败路径（无安全落点）明确"不传"，60 秒限频
                throttledSkipLog(maid, yPull ? "y-nostand" : "sam-dim-nostand", name + (yPull
                        ? " Y 轴距离 " + (int) Math.abs(maid.m_20186_() - owner.m_20186_())
                        + " 格需拉回，但主人身边 16 格内无安全落点——不传（有落点后再试）"
                        : " 同维度距离 " + blocks + " 格需拉回，但主人身边 16 格内无可站立点（高空/虚空）——等落地后再拉"));
            }
        } catch (Exception ignored) {
        }
    }
}
