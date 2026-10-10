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
 *
 * ── 报告
 * 「经过远距离传送（同维度内）后，文本里说女仆传送到身边了，但看不见……怪物会被攻击、
 * 也会有搭路方块在身边放置，唯一的问题是看不见女仆，无法对其进行右键点击操作。」（MingDeee）
 *
 * ── 机制（照抄上游开源项目 2415564388/maidsync 的现场实测，MIT） ──
 * <ol>
 *   <li>大跳变（传送）会让服务端在 {@code ServerEntity.sendChanges()} 里发
 *       {@code ClientboundTeleportEntityPacket}；客户端收到后走
 *       {@code Entity.lerpTo(目标, 3 步)} **逐 tick 插值**，公式
 *       {@code d0 = getX() + (lerpX - getX()) / lerpSteps}——第一步的落点<b>恰好是起终点的 1/3</b>。</li>
 *   <li>若那个 1/3 落点在客户端**没加载的区块**里，实体就脱离 {@code ClientLevel.tickingEntities}
 *       ——{@code Entity.tick()} 永不执行，插值**永久冻结在 1/3**。</li>
 *   <li>此时她所在 section 未编译 → 渲染循环直接跳过她 → **模型与碰撞箱一起消失**（玩家看到的"幽灵"）。</li>
 *   <li>而服务端**不会自愈**：{@code positionCodec} 基线已对齐到真实位置、{@code delta ≈ 0}，
 *       于是判定"客户端知道她在哪"、再也不发位置包。</li>
 * </ol>
 * 这正好解释了 #36 的全部现象：她能打怪、能搭路（服务端一切正常），只是客户端画不出她，
 * 右键也点不到（客户端实体还冻在 1/3 处的那个未加载区块里）。
 *
 * ── 上游的修法（本项目照搬） ──
 * <ol>
 *   <li><b>拦传送包</b>：在 {@code ServerEntity.sendChanges()} 入口检测"位置基线大跳变"，
 *       用 {@code removePairing + addPairing} 代替传送包——客户端把实体**整个重建**、坐标一次到位，
 *       完全不经过插值。这就是 {@code /maid_smart resync} 之所以有效的原因，这里把它自动化。</li>
 *   <li><b>延迟补包</b>（真正的修复手段）：跳变当帧重建是**无效的**——那一刻她的服务端位置
 *       可能还是旧的，生成包会把客户端实体放到一个不 tick 的区块里。必须等传送真正生效之后
 *       再补一次「删+生成+数据+装备」，生成包才会带**玩家身边的新坐标**（那个区块客户端是加载的）。</li>
 * </ol>
 *
 * ── 与上游的两处差异（本项目已有设施，不必照抄） ──
 * <ul>
 *   <li>上游收件人判定要**反射**读 {@code ChunkMap.entityMap / seenBy}（1.21.1 没有公开访问器）。
 *       本项目已有编译期核对过的桥 {@link RemoteTrackBridge}（由
 *       {@code ChunkMapTrackRemoteMixin} 实现在 {@code ChunkMap$TrackedEntity} 上），
 *       且 {@code ChunkMapRemotePumpMixin} 已经拿到了 {@code entityMap}——这里直接借它，
 *       一行反射都不写（反射失败是"运行时静默 no-op"，正是本项目最不想要的失败模式）。</li>
 *   <li>上游自己抄了一份「删+生成+数据+装备+乘客」的四包序列；本项目
 *       {@link MaidResyncCommand#resyncToViewer} 早就是同一套（口径与 {@code ServerEntity.sendPairingData}
 *       逐包对齐，含乘客两个方向与 TLM 饰品全量同步）。这里复用它，**不抄第三份**。</li>
 * </ul>
 *
 * <p>常量全部照抄上游默认：重建阈值 64 格、延迟 20 tick（1 秒）、冷却 40 tick（2 秒）。
 * 冷却挡的是"判定连续成立 → 每 tick 重建一次"那种风暴；跳过时**刻意不 cancel**
 * （cancel 会吞掉本 tick 的旋转包与脏数据同步，只有确实要重建时才划算）。
 */
public final class MaidTeleportRebuild {

    /** 位置基线跳变超过这个幅度（格）= 一次传送。上游默认 64：正常高速移动约 1.5 格/tick，留足余量。 */
    public static final double REBUILD_DISTANCE = 64.0D;

    /** 延迟补包的延迟（tick）：等传送真正生效后再重建，生成包才会带玩家身边的新坐标。 */
    public static final int DEFERRED_DELAY_TICKS = 20;

    /** 同一只女仆两次重建之间的最小间隔（tick）：挡"每 tick 重建一次"的风暴。 */
    public static final int COOLDOWN_TICKS = 40;

    /** 传送嗅探点（{@code Entity.moveTo} 大跳变）打下的标记，key = 实体网络 id。 */
    private static final Set<Integer> MARKED = ConcurrentHashMap.newKeySet();

    /** 延迟补包队列：女仆 UUID → 到期 gameTime。 */
    private static final java.util.Map<UUID, Long> DEFERRED = new ConcurrentHashMap<>();

    /** 冷却表：女仆 UUID → 上次重建的 gameTime。 */
    private static final java.util.Map<UUID, Long> LAST_REBUILD = new ConcurrentHashMap<>();

    private MaidTeleportRebuild() {
    }

    // ─────────────────────────── 标记 / 冷却（mixin 调用） ───────────────────────────

    /** {@code Entity.moveTo} 大跳变时打标记（"她被真正传送过"的凭证）。 */
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
        Long last = LAST_REBUILD.get(maid.getUUID());
        return last != null && now - last < COOLDOWN_TICKS;
    }

    public static void markRebuilt(EntityMaid maid, long now) {
        if (LAST_REBUILD.size() > 4096) {
            LAST_REBUILD.clear();
        }
        LAST_REBUILD.put(maid.getUUID(), now);
    }

    // ─────────────────────────── 延迟补包队列 ───────────────────────────

    /** 由 {@code ServerEntityRebuildMixin} 在检测到大跳变时登记；由 pump 到点执行。 */
    public static void schedule(EntityMaid maid) {
        try {
            long now = maid.level().getGameTime();
            Long existing = DEFERRED.get(maid.getUUID());
            if (existing != null && existing > now) {
                return; // 已经排了一个更晚的，别插队
            }
            if (DEFERRED.size() > 256) {
                DEFERRED.clear();
            }
            DEFERRED.put(maid.getUUID(), now + DEFERRED_DELAY_TICKS);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 延迟补包的执行口（由 {@code ChunkMapRemotePumpMixin} 在 {@code ChunkMap.tick} 尾巴上每 tick
     * 调一次——那个注入点本来就在扫 {@code entityMap}，这里顺手把到期的补包做掉，空队列零成本）。
     *
     * <p>收件人必须**正在追踪她**（{@code seenBy} 里有他）。补包走的是裸包序列，
     * 服务端不会因此登记追踪关系：给一个没在追踪她的客户端补包，只会在他的客户端上凭空造出一个
     * 服务端再也不管的幽灵实体。判定直接借 {@code entityMap} 里的 {@link RemoteTrackBridge}
     * （编译期核对过），不写反射。
     */
    public static void pump(ServerLevel level, Int2ObjectMap<Object> entityMap) {
        if (DEFERRED.isEmpty()) {
            return;
        }
        try {
            long now = level.getGameTime();
            java.util.Iterator<java.util.Map.Entry<UUID, Long>> it = DEFERRED.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<UUID, Long> e = it.next();
                if (now < e.getValue()) {
                    continue;
                }
                it.remove();
                Entity en = level.getEntity(e.getKey());
                if (!(en instanceof EntityMaid maid) || !maid.isAlive() || maid.isRemoved()) {
                    continue;
                }
                // 她的界面开着时不重建（删+生成会让 TLM 容器槽位抓着一只已删实体）——与既有多条链路同口径
                if (maid.guiOpening) {
                    schedule(maid);
                    continue;
                }
                Object tracked = entityMap.get(maid.getId());
                if (!(tracked instanceof RemoteTrackBridge bridge)) {
                    continue; // 她不在追踪表里：没有任何人追踪她，谁都别发
                }
                int sent = 0;
                for (ServerPlayer player : level.players()) {
                    if (player.level() != level || player.isRemoved()) {
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
     * 立即重建（{@code sendChanges} 那一枪）该不该配这个玩家。
     *
     * <p>用的是原版 {@code TrackedEntity.updatePlayer} 的同一口径**减去** 1.21.1 多出来的那道
     * {@code isChunkTracked} 门——那道门正是本 bug 的成因之一（传送落点区块客户端还没收到，
     * 原版因此把她从 {@code seenBy} 拆掉、发 {@code RemoveEntities}），所以这里必须绕开它。
     */
    public static boolean shouldPairTo(Entity entity, ServerPlayer player) {
        try {
            if (player == null || player.isRemoved() || player.level() != entity.level()) {
                return false;
            }
            if (player == entity) {
                return false;
            }
            int view = player.level().getServer() == null ? 10
                    : player.level().getServer().getPlayerList().getViewDistance();
            int range = Math.min(view * 16, entity.getType().clientTrackingRange() * 16);
            if (player.distanceToSqr(entity) > (double) range * (double) range) {
                return false;
            }
            return entity.broadcastToPlayer(player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 上游的 Sable 物理子关卡豁免（软兼容，反射全程 try/catch，**读不到就当"不在子关卡"**——
     * 宁可偶尔多重建一次，也不能因为反射失败让修复整个失效）。
     *
     * <p>为什么需要：女仆不在 Sable 的 {@code retain_in_sub_level} 标签里，站在子关卡上时
     * Sable 会在每 tick 的碰撞解算里把她从 plot 坐标系**踢**回世界坐标系——那是一次
     * {@code Entity.moveTo}，位移是两套坐标系的间距（几百到上千格），正好落在本类的阈值判定上。
     * 不豁免就会每 tick 重建一次，表现为女仆持续抖动抽搐。
     */
    public static boolean inSubLevel(Entity entity) {
        try {
            if (!net.neoforged.fml.ModList.get().isLoaded("sable")) {
                return false;
            }
            Class<?> sableCls = Class.forName("dev.ryanhcode.sable.Sable");
            Object helper = sableCls.getField("HELPER").get(null);
            if (helper == null) {
                return false;
            }
            java.lang.reflect.Method getContaining = helper.getClass().getMethod("getContaining",
                    net.minecraft.world.level.Level.class, net.minecraft.core.Position.class);
            Object sub = getContaining.invoke(helper, entity.level(), entity.position());
            return sub != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 供 {@code EntityMoveToMarkMixin} 算位移（避免在 mixin 里再写一遍世界坐标相减）。 */
    public static double movedDistance(Entity entity, double x, double y, double z) {
        try {
            Vec3 p = entity.position();
            return Math.sqrt(p.distanceToSqr(x, y, z));
        } catch (Throwable ignored) {
            return 0.0D;
        }
    }
}
