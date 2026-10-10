package com.maidsmart.follow;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.command.MaidResyncCommand;
import com.maidsmart.schedule.RemoteTrackBridge;
import com.maidsmart.tool.PromaidLog;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【实测七百九十九·issue #36 根因重定位】远距离传送后「女仆在服务端活着、客户端却没有她」的
 * 真正机制，**不是**追踪关系（{@code seenBy}）掉了，而是**客户端插值被冻住**。
 * （本文件是 forge / 1.20.1 树；neo 版见同名文件，官方名与 SRG 各写各的。）
 *
 * ── 报告
 * 「经过远距离传送（同维度内）后，文本里说女仆传送到身边了，但看不见……怪物会被攻击、
 * 也会有搭路方块在身边放置，唯一的问题是看不见女仆，无法对其进行右键点击操作。」（MingDeee）
 *
 * ── 机制（照抄上游开源项目 2415564388/maidsync 的现场实测，MIT） ──
 * <ol>
 *   <li>大跳变（传送）会让服务端在 {@code ServerEntity.m_8533_()}（= sendChanges）里发
 *       {@code ClientboundTeleportEntityPacket}；客户端收到后走
 *       {@code Entity.lerpTo(目标, 3 步)} **逐 tick 插值**，第一步的落点<b>恰好是起终点的 1/3</b>。</li>
 *   <li>若那个 1/3 落点在客户端**没加载的区块**里，实体就脱离 tick 列表——{@code Entity.tick()}
 *       永不执行，插值**永久冻结在 1/3**。</li>
 *   <li>此时她所在 section 未编译 → 渲染循环直接跳过她 → **模型与碰撞箱一起消失**。</li>
 *   <li>而服务端**不会自愈**：{@code positionCodec}（SRG {@code f_214995_}）基线已对齐到真实位置、
 *       {@code delta ≈ 0}，于是判定"客户端知道她在哪"、再也不发位置包。</li>
 * </ol>
 * 这正好解释了 #36 的全部现象：她能打怪、能搭路（服务端一切正常），只是客户端画不出她。
 *
 * <p>两条修法与全部常量说明见 neo 版同名类的注释（两棵树一字不差，只有 SRG 名字不同）。
 */
public final class MaidTeleportRebuild {

    /** 位置基线跳变超过这个幅度（格）= 一次传送。上游默认 64：正常高速移动约 1.5 格/tick，留足余量。 */
    public static final double REBUILD_DISTANCE = 64.0D;

    /** 延迟补包的延迟（tick）：等传送真正生效后再重建，生成包才会带玩家身边的新坐标。 */
    public static final int DEFERRED_DELAY_TICKS = 20;

    /** 同一只女仆两次重建之间的最小间隔（tick）：挡"每 tick 重建一次"的风暴。 */
    public static final int COOLDOWN_TICKS = 40;

    /** 传送嗅探点（{@code Entity.m_7678_} 大跳变）打下的标记，key = 实体网络 id。 */
    private static final Set<Integer> MARKED = ConcurrentHashMap.newKeySet();

    /** 延迟补包队列：女仆 UUID → 到期 gameTime。 */
    private static final java.util.Map<UUID, Long> DEFERRED = new ConcurrentHashMap<>();

    /** 冷却表：女仆 UUID → 上次重建的 gameTime。 */
    private static final java.util.Map<UUID, Long> LAST_REBUILD = new ConcurrentHashMap<>();

    private MaidTeleportRebuild() {
    }

    // ─────────────────────────── 标记 / 冷却（mixin 调用） ───────────────────────────

    /** {@code Entity.m_7678_} 大跳变时打标记（"她被真正传送过"的凭证）。 */
    public static void mark(int entityId) {
        if (MARKED.size() > 4096) {
            MARKED.clear();
        }
        MARKED.add(entityId);
    }

    /** 取走标记；返回 true = 这个实体被远距离挪动过。 */
    public static boolean consume(int entityId) {
        return MARKED.remove(entityId);
    }

    /** 把标记原样还回去（冷却跳过时用：它是凭证，冷却结束了还得靠它）。 */
    public static void restoreMark(int entityId) {
        mark(entityId);
    }

    public static boolean coolingDown(EntityMaid maid, long now) {
        Long last = LAST_REBUILD.get(maid.m_20148_());
        return last != null && now - last < COOLDOWN_TICKS;
    }

    public static void markRebuilt(EntityMaid maid, long now) {
        if (LAST_REBUILD.size() > 4096) {
            LAST_REBUILD.clear();
        }
        LAST_REBUILD.put(maid.m_20148_(), now);
    }

    // ─────────────────────────── 延迟补包队列 ───────────────────────────

    /** 由 {@code ServerEntityRebuildMixin} 在检测到大跳变时登记；由 pump 到点执行。 */
    public static void schedule(EntityMaid maid) {
        try {
            long now = maid.m_9236_().m_46467_();
            Long existing = DEFERRED.get(maid.m_20148_());
            if (existing != null && existing > now) {
                return; // 已经排了一个更晚的，别插队
            }
            if (DEFERRED.size() > 256) {
                DEFERRED.clear();
            }
            DEFERRED.put(maid.m_20148_(), now + DEFERRED_DELAY_TICKS);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 延迟补包的执行口（由 {@code ChunkMapRemotePumpMixin} 在 {@code ChunkMap.m_140421_} 尾巴上每 tick
     * 调一次——那个注入点本来就在扫 {@code f_140150_}，这里顺手把到期的补包做掉，空队列零成本）。
     *
     * <p>收件人必须**正在追踪她**（{@code f_140475_} 里有他）。补包走的是裸包序列，
     * 服务端不会因此登记追踪关系：给一个没在追踪她的客户端补包，只会在他的客户端上凭空造出一个
     * 服务端再也不管的幽灵实体。判定直接借 {@code f_140150_} 里的 {@link RemoteTrackBridge}
     * （编译期核对过），不写反射。
     */
    public static void pump(ServerLevel level, Int2ObjectMap<Object> entityMap) {
        if (DEFERRED.isEmpty()) {
            return;
        }
        try {
            long now = level.m_46467_();
            java.util.Iterator<java.util.Map.Entry<UUID, Long>> it = DEFERRED.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<UUID, Long> e = it.next();
                if (now < e.getValue()) {
                    continue;
                }
                it.remove();
                Entity en = level.m_8791_(e.getKey());
                if (!(en instanceof EntityMaid maid) || !maid.m_6084_() || maid.m_213877_()) {
                    continue;
                }
                // 她的界面开着时不重建（删+生成会让 TLM 容器槽位抓着一只已删实体）——与既有多条链路同口径
                if (maid.guiOpening) {
                    schedule(maid);
                    continue;
                }
                Object tracked = entityMap.get(maid.m_19879_());
                if (!(tracked instanceof RemoteTrackBridge bridge)) {
                    continue; // 她不在追踪表里：没有任何人追踪她，谁都别发
                }
                int sent = 0;
                for (ServerPlayer player : level.m_6907_()) {
                    if (player.m_9236_() != level || player.m_213877_()) {
                        continue;
                    }
                    if (!bridge.promaid$isPaired(player)) {
                        continue; // 没在追踪她的客户端：补包只会造幽灵实体
                    }
                    try {
                        MaidResyncCommand.resyncToViewer(player, maid);
                        sent++;
                    } catch (Throwable ignored) {
                    }
                }
                if (sent > 0) {
                    PromaidLog.log("重同步", PromaidLog.nameOf(maid)
                            + " 传送后延迟重建客户端实体（删+生成，治「客户端插值冻在未加载区块 → 看不见她」）："
                            + sent + " 个客户端");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ─────────────────────────── 立即重建的收件人判定 ───────────────────────────

    /**
     * 立即重建（{@code m_8533_} 那一枪）该不该配这个玩家。
     *
     * <p>用的是原版 {@code TrackedEntity.m_140497_} 的同一口径（1.20.1 没有 1.21.1 那道
     * {@code isChunkTracked} 门，所以这里就是逐字照抄原版）。
     */
    public static boolean shouldPairTo(Entity entity, ServerPlayer player) {
        try {
            if (player == null || player.m_213877_() || player.m_9236_() != entity.m_9236_()) {
                return false;
            }
            if (player == entity) {
                return false;
            }
            net.minecraft.server.MinecraftServer srv = entity.m_9236_().m_7654_();
            int view = srv == null ? 10 : srv.m_6846_().m_11312_();
            int range = Math.min(view * 16, entity.m_6095_().m_20681_() * 16);
            if (player.m_20280_(entity) > (double) range * (double) range) {
                return false;
            }
            return entity.m_6459_(player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 上游的 Sable 物理子关卡豁免（软兼容，反射全程 try/catch，**读不到就当"不在子关卡"**——
     * 宁可偶尔多重建一次，也不能因为反射失败让修复整个失效）。理由见 neo 版同名注释。
     */
    public static boolean inSubLevel(Entity entity) {
        try {
            if (!net.minecraftforge.fml.ModList.get().isLoaded("sable")) {
                return false;
            }
            Class<?> sableCls = Class.forName("dev.ryanhcode.sable.Sable");
            Object helper = sableCls.getField("HELPER").get(null);
            if (helper == null) {
                return false;
            }
            java.lang.reflect.Method getContaining = helper.getClass().getMethod("getContaining",
                    net.minecraft.world.level.Level.class, net.minecraft.core.Position.class);
            Object sub = getContaining.invoke(helper, entity.m_9236_(), entity.m_20182_());
            return sub != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 供 {@code EntityMoveToMarkMixin} 算位移（避免在 mixin 里再写一遍世界坐标相减）。 */
    public static double movedDistance(Entity entity, double x, double y, double z) {
        try {
            Vec3 p = entity.m_20182_();
            return Math.sqrt(p.m_82531_(x, y, z));
        } catch (Throwable ignored) {
            return 0.0D;
        }
    }
}
