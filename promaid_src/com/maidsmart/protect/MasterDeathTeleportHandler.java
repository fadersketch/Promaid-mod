package com.maidsmart.protect;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.UUID;

/**
 * 主人死亡强制传送（v1.5.25c）：主人死亡瞬间，所有属于该主人的女仆
 * 【无条件、立即】传送回主人身边。
 *
 * v1.5.112：传送目标 = 玩家【重生点】（床/重生锚），不再是死亡位置——
 * 旧版传死亡地点：女仆赶去守尸体没意义（主人复活在别处）；重生锚可能在
 * 其他维度（下界床/末地重生锚）→ 目标维度一起解析，女仆跨维度传送。
 * 无重生点/维度异常 → 退主世界出生点。
 *
 * v1.5.231【死亡传送失效修复】：旧版用 level.m_143280_（getEntities）只能扫到
 * 【已加载区块】里的女仆——女仆在远处未加载区块时根本找不到，传送静默失效
 * （实测 04:47:19 苦力怕炸死，女仆在远处 → 无播报无传送 → "其他女仆全部消失"）。
 * 现在维护【女仆追踪表】（EntityJoinLevelEvent 注册 / EntityLeaveWorldEvent 标记
 * 卸载 / ServerTick 每 5 秒刷新已加载女仆位置）：
 * - 已加载女仆 → 直接传送；
 * - 未加载女仆 → 【强制加载其所在区块】→ 传送 → 解除强制加载。
 * 与自保的 teleportHome 不同——那是"威胁消失后、检查周围无怪才传"的安全传送；
 * 本处理器【不受战斗判定影响】：不走冷却、不检查威胁/距离/范围、不依赖 LLM 开关。
 * 主人死亡是最高优先级事件，女仆必须立刻赶到重生点。
 *
 * v1.1.0 实测八十三【保持原位三态豁免】：home 看家钉死 / 坐姿停放 / 骑乘中的
 * 女仆不参与死亡传送与复活保险拉取（实测七十八确立的"看家的女仆不响应任何
 * 传送"语义此前漏盖了本处理器——粉丝复测 home 女仆仍被拽走）。战斗类女仆
 * （跟随/护卫）照常无条件赶往重生点。
 */
public class MasterDeathTeleportHandler {

    /** 女仆追踪表（maidUuid → 最后已知状态）——死亡传送对未加载女仆也能定位 */
    private record MaidTrack(UUID ownerUuid, net.minecraft.resources.ResourceKey<Level> dim,
                             double x, double y, double z, boolean loaded) {
    }

    private static final java.util.Map<UUID, MaidTrack> MAID_TRACK =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 位置刷新节流（每 100 tick = 5 秒更新一次已加载女仆坐标） */
    private int trackTick = 0;

    @SubscribeEvent
    public void onMaidJoin(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof EntityMaid maid)) {
            return;
        }
        try {
            var owner = maid.m_269323_();
            if (owner == null) {
                return;
            }
            MAID_TRACK.put(maid.m_20148_(), new MaidTrack(owner.m_20148_(),
                    maid.m_9236_().m_46472_(), maid.m_20185_(), maid.m_20186_(), maid.m_20189_(), true));
        } catch (Exception ignored) {
        }
    }

    @SubscribeEvent
    public void onMaidLeave(net.minecraftforge.event.entity.EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof EntityMaid maid)) {
            return;
        }
        // v1.5.audit:实体【永久移除】(死亡/解雇/discard)→ 彻底删除追踪条目
        // ——旧版只标记未加载,死亡女仆的条目永远残留,主人每次死亡都要
        // 白白强制加载一次其"遗照坐标"的区块。区块卸载(isRemoved=false)
        // 才走"保留最后坐标、标记未加载"路径。
        if (maid.m_213877_()) {
            MAID_TRACK.remove(maid.m_20148_());
            return;
        }
        MaidTrack t = MAID_TRACK.get(maid.m_20148_());
        if (t != null) {
            // 保留最后坐标，只标记未加载
            MAID_TRACK.put(maid.m_20148_(), new MaidTrack(t.ownerUuid(), t.dim(),
                    maid.m_20185_(), maid.m_20186_(), maid.m_20189_(), false));
        }
    }

    @SubscribeEvent
    public void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        if (++this.trackTick < 100) {
            return;
        }
        this.trackTick = 0;
        // 刷新已加载女仆的位置（只更新 loaded 项；卸载项保留旧坐标）
        // 直接遍历所有已加载女仆更新坐标
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.m_129785_()) {
            for (EntityMaid maid : level.m_143280_(
                    net.minecraft.world.level.entity.EntityTypeTest.m_156916_(EntityMaid.class), m -> true)) {
                MaidTrack t = MAID_TRACK.get(maid.m_20148_());
                if (t != null) {
                    MAID_TRACK.put(maid.m_20148_(), new MaidTrack(t.ownerUuid(),
                            level.m_46472_(), maid.m_20185_(), maid.m_20186_(), maid.m_20189_(), true));
                }
            }
        }
    }

    @SubscribeEvent
    public void onPlayerDeath(LivingDeathEvent event) {
        // v1.5.88：主人死亡传送开关（配置面板 combat.masterDeathTeleport）
        if (!com.maidsmart.config.MaidSmartConfig.COMBAT_MASTER_DEATH_TELEPORT.get()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // v1.5.329【图腾救活不传】:不死图腾触发时,某些环境(如 charmofundying
        // 不死图腾槽位/部分 mod 的顺序)会先触发 LivingDeathEvent 再图腾复活——
        // 死亡传送若立即执行,会把女仆传到【重生点】(主人实际没死、离坠落现场
        // 很远)→ "女仆凭空消失"(用户实测:触发手中图腾,聊天框"丈夫我赶紧赶到
        // 你身边",女仆消失)。改为登记后校验:【下一服务端 tick 末】玩家若已被
        // 图腾救活(活着)→ 跳过传送;真死(仍 dead/血量 0)→ 才执行。
        // v1.5.audit:旧实现登记后在【同一 tick 的 END】就校验——死亡发生在
        // tick T 的实体阶段,同 tick END 立刻查,modded 图腾若在 T+1 才复活
        // (先 post 事件、后复活)仍会误传。现在记录登记 tick,严格等 ≥1 tick
        // 后才校验执行(与 heartfelt 死亡调侃的真死核验同款时序)。
        PENDING_DEATHS.put(player.m_20148_(), ServerLifecycleHooks.getCurrentServer() != null
                ? ServerLifecycleHooks.getCurrentServer().m_129921_() : 0L);
    }

    /** v1.5.329:待校验的玩家死亡(uuid → 登记 tick)——下一 tick 末执行真传送 */
    private static final java.util.Map<UUID, Long> PENDING_DEATHS =
            new java.util.concurrent.ConcurrentHashMap<>();

    @SubscribeEvent
    public void onSettlePendingDeaths(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        if (PENDING_DEATHS.isEmpty()) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            PENDING_DEATHS.clear();
            return;
        }
        long now = server.m_129921_();
        for (java.util.Iterator<java.util.Map.Entry<UUID, Long>> it =
                PENDING_DEATHS.entrySet().iterator(); it.hasNext(); ) {
            java.util.Map.Entry<UUID, Long> entry = it.next();
            UUID ownerId = entry.getKey();
            if (now < entry.getValue() + 1L) {
                continue; // 登记未满 1 tick:留到下一轮再校验(真·下一 tick)
            }
            it.remove();
            ServerPlayer player = null;
            for (ServerPlayer sp : server.m_6846_().m_11314_()) {
                if (sp.m_20148_().equals(ownerId)) {
                    player = sp;
                    break;
                }
            }
            if (player == null) {
                continue; // 玩家已离线/已移除——不传送
            }
            // 图腾救活场景:玩家下一 tick 活着(hp > 0 且未 dead)→ 跳过传送,
            // 女仆留在身边;真死 → 执行死亡传送
            if (player.m_21224_() || player.m_21223_() <= 0.0f) {
                doDeathTeleport(server, player);
            }
        }
    }

    /** 实际执行死亡传送(目标 = 玩家重生点,全女仆传送) */
    private void doDeathTeleport(MinecraftServer server, ServerPlayer player) {
        UUID ownerId = player.m_20148_();
        // v1.5.112：解析传送目标 = 玩家重生点（床/重生锚）。m_219759_ = getRespawnPosition
        // （Optional<GlobalPos>，含维度）；无重生点/维度不存在 → 主世界出生点兜底。
        ServerLevel targetLevel = null;
        double tx = 0;
        double ty = 0;
        double tz = 0;
        java.util.Optional<net.minecraft.core.GlobalPos> respawn = player.m_219759_();
        if (respawn.isPresent()) {
            net.minecraft.core.GlobalPos gp = respawn.get();
            targetLevel = server.m_129880_(gp.m_122640_()); // getLevel(respawnDimension)
            net.minecraft.core.BlockPos pos = gp.m_122646_(); // pos()
            tx = pos.m_123341_() + 0.5;
            ty = pos.m_123342_();
            tz = pos.m_123343_() + 0.5;
        }
        if (targetLevel == null) {
            targetLevel = server.m_129880_(Level.f_46428_); // OVERWORLD
            net.minecraft.core.BlockPos spawn = targetLevel.m_220360_(); // getSharedSpawnPos
            tx = spawn.m_123341_() + 0.5;
            ty = spawn.m_123342_();
            tz = spawn.m_123343_() + 0.5;
        }
        final ServerLevel dest = targetLevel;
        final double fx = tx;
        final double fy = ty;
        final double fz = tz;
        // v1.5.227：安全落点——重生点是床/出生点【方块坐标】，女仆直接传过去会
        // 卡进方块/墙里（1.8 格高的身体被塞进 1 格空间 → 实体剔除/模型卡没，
        // 实测"传过去之后女仆消失了、只有一只真的在身边"）。在目标柱子上找
        // 脚下可站立 + 头顶有空间的位置；找不到退回原坐标（上方 +1）。
        final double[] safe = findSafeLanding(dest, fx, fy, fz);
        // v1.5.228：只让【离目标最近的】女仆播报"马上赶到你身边"——旧版每只女仆
        // 各喊一遍（实测 4 只女仆死亡瞬间 4 条消息刷屏）
        EntityMaid closest = null;
        double closestDist = Double.MAX_VALUE;
        // 遍历所有维度（m_129785_ = getAllLevels）已加载女仆
        for (ServerLevel level : server.m_129785_()) {
            java.util.List<? extends EntityMaid> maids = level.m_143280_(
                    net.minecraft.world.level.entity.EntityTypeTest.m_156916_(EntityMaid.class), m -> true);
            for (EntityMaid maid : maids) {
                if (!maid.m_21824_() || !maid.m_6084_()) {
                    continue;
                }
                UUID maidOwner = maid.m_269323_() != null ? maid.m_269323_().m_20148_() : null;
                if (maidOwner == null || !maidOwner.equals(ownerId)) {
                    continue;
                }
                // v1.1.0 实测八十三：保持原位三态（home/坐姿/骑乘）不传也不参与播报
                if (shouldStayPut(maid)) {
                    continue;
                }
                // 记录最近者（用【传送前】距离，避免全传后坐标相同分不出）
                double d = maid.m_20238_(player.m_20182_());
                if (d < closestDist) {
                    closestDist = d;
                    closest = maid;
                }
                teleportMaid(maid, level, dest, safe, player);
            }
        }
        // v1.5.231：追踪表里的【未加载】女仆（区块卸载/距离过远）——强制加载其
        // 所在区块后传送（旧版直接漏掉 → "其他女仆全部消失"）
        for (java.util.Map.Entry<UUID, MaidTrack> e : MAID_TRACK.entrySet()) {
            MaidTrack t = e.getValue();
            if (t == null || !t.ownerUuid().equals(ownerId) || t.loaded()) {
                continue; // 已加载的在上面处理过；只处理未加载的
            }
            ServerLevel maidLevel = server.m_129880_(t.dim());
            if (maidLevel == null) {
                continue;
            }
            try {
                int cx = net.minecraft.util.Mth.m_14107_(t.x()) >> 4;
                int cz = net.minecraft.util.Mth.m_14107_(t.z()) >> 4;
                maidLevel.m_6325_(cx, cz); // getChunk：同步加载区块（required 语义）
                EntityMaid maid = (EntityMaid) maidLevel.m_8791_(e.getKey()); // getEntity
                if (maid != null) {
                    // 更新追踪为已加载（位置以实体为准）
                    MAID_TRACK.put(e.getKey(), new MaidTrack(t.ownerUuid(), t.dim(),
                            maid.m_20185_(), maid.m_20186_(), maid.m_20189_(), true));
                    // v1.1.0 实测八十三：保持原位三态（home/坐姿/骑乘）不传
                    if (shouldStayPut(maid)) {
                        continue;
                    }
                    double d = maid.m_20238_(player.m_20182_());
                    if (d < closestDist) {
                        closestDist = d;
                        closest = maid;
                    }
                    teleportMaid(maid, maidLevel, dest, safe, player);
                } else {
                    // v1.5.audit:强制加载后实体仍不在=已死亡/被移除——清条目,
                    // 防止之后每次主人都死都白加载这个区块
                    MAID_TRACK.remove(e.getKey());
                }
            } catch (Exception ignored) {
            }
        }
        if (closest != null) {
            closest.getChatBubbleManager().addTextChatBubble("主人……我马上赶到你身边！");
        }
    }

    /**
     * v1.1.0 实测八十三：保持原位三态豁免（与一键集合/跨维度跟随的口径统一）——
     * home 看家钉死（看家的女仆不响应任何传送）、坐姿 = 玩家明确停放、骑乘中
     * 强拽会脱离载具状态。死亡传送与复活保险拉取均遵守。
     */
    private static boolean shouldStayPut(EntityMaid maid) {
        return maid.isHomeModeEnable() || maid.isMaidInSittingPose() || maid.m_20159_();
    }

    /** 传送单只女仆（同维度 m_6034_ / 跨维度 m_264318_ + 清摔落/速度） */
    private static void teleportMaid(EntityMaid maid, ServerLevel from, ServerLevel dest,
                                     double[] safe, ServerPlayer player) {
        if (from == dest) {
            maid.m_6034_(safe[0], safe[1], safe[2]);
        } else {
            maid.m_264318_(dest, safe[0], safe[1], safe[2], java.util.Collections.emptySet(),
                    player.m_146908_(), player.m_146909_()); // getYRot/getXRot
        }
        // v1.5.27：传送不重置 fallDistance——不清零的话主人若在悬空/高处，
        // 女仆传过去立即坠落会带着旧累计摔落距离 → 1 格落地也摔伤
        maid.f_19789_ = 0.0f;
        // v1.5.227：清速度——传送后残留的移动向量会让女仆继续飘/冲进地形
        maid.m_20256_(net.minecraft.world.phys.Vec3.f_82478_); // Vec3.ZERO
    }

    /**
     * v1.5.228：重生后保险传送——死亡传送万一漏传（异常/未加载等极端情况），
     * 主人重生时把仍在远处的女仆拉到身边（>64 格才拉，正常情况女仆已在重生点
     * 附近，不会误拉）。
     */
    @SubscribeEvent
    public void onPlayerRespawn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) {
        if (!com.maidsmart.config.MaidSmartConfig.COMBAT_MASTER_DEATH_TELEPORT.get()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.m_9236_() instanceof ServerLevel level)) {
            return;
        }
        UUID ownerId = player.m_20148_();
        for (EntityMaid maid : level.m_45976_(EntityMaid.class,
                player.m_20191_().m_82400_(256.0))) {
            if (!maid.m_21824_() || !maid.m_6084_()) {
                continue;
            }
            UUID maidOwner = maid.m_269323_() != null ? maid.m_269323_().m_20148_() : null;
            if (maidOwner == null || !maidOwner.equals(ownerId)) {
                continue;
            }
            // v1.1.0 实测八十三：保持原位三态（home/坐姿/骑乘）保险拉取同样豁免
            if (shouldStayPut(maid)) {
                continue;
            }
            if (maid.m_20238_(player.m_20182_()) <= 64.0) {
                continue; // 已在身边，不拉
            }
            double[] spot = findSafeLanding(level, player.m_20185_(), player.m_20186_(), player.m_20189_());
            maid.m_6034_(spot[0], spot[1], spot[2]);
            maid.f_19789_ = 0.0f;
            maid.m_20256_(net.minecraft.world.phys.Vec3.f_82478_);
        }
    }

    /**
     * v1.5.227：在 (tx, ty, tz) 所在柱子上找安全落点——从 ty 起向上扫描最多 24 格，
     * 找第一个【脚部 + 头部都是空气】的位置（女仆 1.8 格身高需要两格净空，否则
     * 卡进方块 → 实体剔除/模型卡没，"传过去女仆消失"的根因）。
     * v1.5.228：找到空气格后【继续向下走到地面】再落——旧版直接落在第一个空气格
     * 上，若重生点上方有树/高台，女仆落在树顶/高台再坠落（"落地水"刷屏 + 落点
     * 看起来不对）；现在贴地落，无坠落无位移。
     */
    private static double[] findSafeLanding(ServerLevel level, double tx, double ty, double tz) {
        int x = net.minecraft.util.Mth.m_14107_(tx); // floor
        int z = net.minecraft.util.Mth.m_14107_(tz);
        // v1.1.0 实测八十（粉丝 bug："主人下界死亡后女仆被传到基岩层上面"）：下界
        // y=127 是一整层基岩天花板——旧版从重生点向上扫第一处两格净空，若重生点
        // 柱子上方被实体方块一路封到天花板（封闭基地/埋在地下的重生锚），第一处
        // 露天空间就是基岩层上方；随后"向下贴地"又被基岩挡住 → 稳落基岩顶。
        // 修复：①下界搜索上限压到 y≤124（天花板之下全部排除，重生锚本身在屋顶
        // 上也照此办理）；②向上找不到时从重生点向【下】继续扫（地下空腔/基地
        // 内部）；③都无果才退回原坐标上方一格。
        boolean netherCeiling = "the_nether".equals(level.m_46472_().m_135782_().m_135815_());
        int minY = level.m_141928_() + 1; // getMinBuildHeight
        int limitUp = level.m_141937_() - 3; // getMaxBuildHeight
        if (netherCeiling) {
            limitUp = Math.min(limitUp, 124);
        }
        int startY = Math.max(minY, net.minecraft.util.Mth.m_14107_(ty));
        int airY = -1;
        for (int y = startY; y <= limitUp; y++) {
            if (level.m_8055_(new net.minecraft.core.BlockPos(x, y, z)).m_60795_()   // 脚部空气
                    && level.m_8055_(new net.minecraft.core.BlockPos(x, y + 1, z)).m_60795_()) { // 头部空气
                airY = y;
                break;
            }
        }
        if (airY < 0) {
            // 向上无果 → 从重生点向【下】扫（实测八十：地下空腔/基地内部兜底）
            for (int y = Math.min(startY - 1, limitUp); y >= minY; y--) {
                if (level.m_8055_(new net.minecraft.core.BlockPos(x, y, z)).m_60795_()
                        && level.m_8055_(new net.minecraft.core.BlockPos(x, y + 1, z)).m_60795_()) {
                    airY = y;
                    break;
                }
            }
        }
        if (airY < 0) {
            return new double[]{tx, startY + 1.0, tz}; // 兜底：密闭空间原坐标上方 1 格
        }
        // 从空气格向下走到地面（下方是空气就继续下移；贴地才落，不悬空不坠落）
        int groundY = airY;
        while (groundY > level.m_141928_() + 1
                && level.m_8055_(new net.minecraft.core.BlockPos(x, groundY - 1, z)).m_60795_()) {
            groundY--;
        }
        return new double[]{tx, groundY + 0.5, tz};
    }
}
