package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * v1.3.0(beta)【骑乘指挥棒·原版生物骑乘】——把"已上鞍的坐骑"与"女仆"绑在一起，让她骑着它跟主人走。
 *
 * <p>需求原文："先做原版生物乘坐坐骑吧。直接套僵尸的骑乘代码，速度上是坐骑的最大速度与女仆的
 * 最大速度之间取最大值。乘上坐骑的女仆作为骑乘状态，我们也不做额外的豁免，同时加一个shift加
 * 右击可以把女仆从坐骑上弄下来（跟坐在扫帚上一样）。关键就在于骑乘状态下，女仆的移动AI该怎么搞，
 * 我觉得大概就是套我们这边已有的移动链路。引入一个新物品，骑乘指挥棒，可以将可骑乘坐骑与女仆
 * 绑定起来，被绑定以后会出现光标。（类似于前段时间的武装拴绳）不管是先绑女仆还是先绑可骑乘坐骑
 * 都没问题。绑定之后，女仆就会坐到那个坐骑上。"
 *
 * ── 【绑定：两向都行，靠"谁被选中"】──
 * 玩家手持骑乘指挥棒：
 * <ul>
 *   <li>右击一只**已上鞍**的坐骑 → 它成为"待配对"（金色描边 = 玩家说的"光标"）；</li>
 *   <li>右击自己的女仆 → 她也成为"待配对"；</li>
 *   <li>两边都有了 → 当场 {@code maid.startRiding(mount, true)}（**套僵尸骑鸡那一记**）+ 写链路表。</li>
 * </ul>
 * 所以"先绑女仆还是先绑坐骑"都对——选中任一边，再选另一边即配对。选中状态是**服务端按玩家存的**
 * 瞬时状态（{@link #PENDING}），不是物品 NBT：同一根棍子反复用、走多远都不丢。
 *
 * ── 【下坐骑：潜行 + 右击】──
 * 潜行时右击（女仆或坐骑均可）→ 解除链路 + {@code maid.stopRiding()}，她落到坐骑旁边。
 * 口径与"潜行+中键工位标记"同款（潜行 = 明确的"我要改设置"手势），也与玩家说的
 * "跟坐在扫帚上一样"一致。
 *
 * ── 【移动 AI：套我们已有的移动链路】──
 * 每 tick 读**她本来要去的地方**（脑里的 WALK_TARGET，跟随/走位/任务全部写在这里），把那个点
 * 转达给**坐骑自己的 PathNavigation**（{@link MaidRideKit#feedNavigation}）——这就是玩家说的
 * "套我们这边已有的移动链路"：目的地的计算完全复用现有系统，我们只把"她走"换成"坐骑走"。
 * 找不到她的走位目标时退回"跟主人走"（离主人超过 {@code ride.followDist} 就喂主人的位置）。
 * 坐骑自己的寻路负责上下坡/绕障/跳跃/原版动画；它自己的随机闲逛由
 * {@link com.maidsmart.mixin.RandomStrollGoalRiddenMixin} 按"被我们驾驶就别闲逛"掐掉。
 *
 * ── 【安全网】──
 * 她/坐骑/主人任一没了、跨维度、她自己下鞍（原版潜跳/被别的模组拽下去）→ 下一拍自动解除并清标记；
 * 服务端重启后由 {@link #tick} 的恢复扫描按 persistentData 重建（骑乘关系本身由原版存档恢复）。
 * 女仆的战斗/自保/落地水等链路**一律不改**（玩家原话"我们也不做额外的豁免"）。
 */
@Mod.EventBusSubscriber(modid = "promaid")
public final class RideBindManager {

    /** persistentData：她骑的是哪只坐骑（UUID 字符串）——跨存档重建/自愈用 */
    public static final String TAG_RIDE_MOUNT = MaidRideKit.TAG_RIDE_MOUNT;
    /** persistentData：这只坐骑驮的是哪位女仆（UUID 字符串）——坐骑侧留痕，双向可查 */
    public static final String TAG_RIDE_MAID = "maid_smart_ride_maid";

    /** 每 2 tick 校验一次（与武装拴绳同频） */
    private static final int TICK_DIV = 2;
    /** 拒绝/提示节流（ms） */
    private static final long DENY_INTERVAL_MS = 3000L;
    /** "离主人这么近就不喂目标"的地面余量（格）：防坐骑顶着主人打转 */
    private static final double STOP_SLACK = 1.5;

    /** 服务端链路表：女仆 UUID → 链路（弱引用，女仆/坐骑/主人没了自动失效） */
    private static final Map<UUID, Link> LINKS = new HashMap<>();

    /** 待配对：玩家 UUID → 选中的实体（女仆或坐骑）。只活服务端、只在这位玩家手里有效 */
    private static final Map<UUID, java.lang.ref.WeakReference<Entity>> PENDING = new HashMap<>();

    /** 提示节流 */
    private static final Map<UUID, Long> DENY_LOG = new HashMap<>();

    private static int tickTimer = 0;

    private static final class Link {
        final java.lang.ref.WeakReference<EntityMaid> maid;
        final java.lang.ref.WeakReference<Entity> mount;
        final java.lang.ref.WeakReference<ServerPlayer> owner;

        Link(EntityMaid m, Entity mount, ServerPlayer owner) {
            this.maid = new java.lang.ref.WeakReference<>(m);
            this.mount = new java.lang.ref.WeakReference<>(mount);
            this.owner = new java.lang.ref.WeakReference<>(owner);
        }
    }

    private RideBindManager() {
    }

    private static boolean isEnabled() {
        return MaidRideKit.enabled();
    }

    /* ==================== 事件入口 ==================== */

    /**
     * 手持骑乘指挥棒右击实体（女仆 / 坐骑）——骨架同 {@code GunnerTetherManager.onInteract}：
     * EntityInteract 比 TLM 的 mobInteract 先到，cancel 掉就不会误开女仆 GUI。
     */
    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!isEnabled()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        InteractionHand hand = event.getHand();
        ItemStack stack = player.m_21120_(hand);
        if (!(stack.m_41720_() instanceof RideBatonItem)) {
            return;
        }
        Entity target = event.getTarget();
        if (target == null) {
            return;
        }
        boolean maid = target instanceof EntityMaid;
        boolean mount = !maid && MaidRideKit.isRideableMount(target, null);
        if (!maid && !mount && !(target instanceof Mob)) {
            return; // 既不是女仆也不是生物：不接（对着别的实体挥棍子没有任何效果）
        }
        event.setCanceled(true);
        player.m_6674_(hand);
        handle(player, target);
    }

    /* ==================== 选中 / 配对 / 解除 ==================== */

    private static void handle(ServerPlayer player, Entity target) {
        try {
            // ① 潜行 + 右击 = 下坐骑 / 解除（"跟坐在扫帚上一样"）
            if (player.m_6040_()) {
                dismountByClick(player, target);
                return;
            }
            // ② 目标是女仆
            if (target instanceof EntityMaid m) {
                if (!ownable(player, m)) {
                    deny(player, m, "她不是我的女仆～");
                    return;
                }
                // 她已经骑着某只坐骑 → 再选她 = 直接下坐骑（比潜行更顺手的第二入口）
                if (MaidRideKit.isRidingMount(m)) {
                    releaseMaid(m, false, "再选一次");
                    return;
                }
                // 已经有一只坐骑"待配对" → 当场配对
                Entity mount = takePendingMount(player);
                if (mount != null) {
                    bind(player, m, mount);
                    return;
                }
                if (com.maidsmart.combat.MaidBroomKit.isRidingBroom(m)
                        || com.maidsmart.combat.MaidBroomKit.isBroomTask(m)) {
                    deny(player, m, "她正在骑扫帚，先让她收好扫帚～");
                    return;
                }
                setPending(player, m);
                bubble(m, "好呀，再指一只上了鞍的坐骑给我～");
                com.maidsmart.tool.PromaidLog.log("骑乘指挥棒", "选中女仆："
                        + com.maidsmart.tool.PromaidLog.nameOf(m) + "（玩家=" + name(player) + "，等坐骑）");
                return;
            }
            // ③ 目标是坐骑
            EntityMaid pendingMaid = takePendingMaid(player);
            String why = MaidRideKit.denyReason(target, pendingMaid);
            if (why != null) {
                deny(player, null, why);
                return;
            }
            if (pendingMaid != null) {
                bind(player, pendingMaid, target);
                return;
            }
            // 这只坐骑上是不是已经驮着**我的**女仆 → 再选一次 = 解除
            EntityMaid rider = MaidRideKit.riderOf(target);
            if (rider != null && ownable(player, rider)) {
                releaseMaid(rider, false, "再选一次");
                return;
            }
            setPending(player, target);
            player.m_213846_(Component.m_237113_("\u00a7a已选中坐骑：\u00a7f"
                    + MaidRideKit.describe(target) + "\u00a7a → 再右击自己的女仆即可配对"));
            com.maidsmart.tool.PromaidLog.log("骑乘指挥棒", "选中坐骑："
                    + MaidRideKit.describe(target) + "（玩家=" + name(player) + "，等女仆）");
        } catch (Throwable ignored) {
        }
    }

    /** 潜行右击：把这只坐骑上（或这只）女仆弄下来 */
    private static void dismountByClick(ServerPlayer player, Entity target) {
        EntityMaid m = null;
        if (target instanceof EntityMaid mm) {
            m = mm;
        } else {
            m = MaidRideKit.riderOf(target);
        }
        if (m == null) {
            player.m_213846_(Component.m_237113_("\u00a77这只坐骑背上没有我的女仆"));
            return;
        }
        if (!ownable(player, m)) {
            deny(player, m, "她不是我的女仆～");
            return;
        }
        releaseMaid(m, false, "潜行下鞍");
    }

    /** 配对：套僵尸骑鸡那一记 —— {@code startRiding(force)} + 写表 + 打标记（玩家说的"光标"） */
    private static void bind(ServerPlayer player, EntityMaid maid, Entity mount) {
        if (!ownable(player, maid)) {
            deny(player, maid, "她不是我的女仆～");
            return;
        }
        String why = MaidRideKit.denyReason(mount, maid);
        if (why != null) {
            deny(player, maid, why);
            return;
        }
        if (LINKS.containsKey(maid.m_20148_())) {
            releaseMaid(maid, false, "换绑");
        }
        // force = true：跳过原版 canRide / canAddPassenger（javap 实证 m_7998_ 的 iload_2 ifne 直接跳门）
        boolean ok = false;
        try {
            ok = maid.m_7998_(mount, true);
        } catch (Throwable ignored) {
        }
        if (!ok) {
            deny(player, maid, "没能坐上去……再试一次？");
            return;
        }
        LINKS.put(maid.m_20148_(), new Link(maid, mount, player));
        try {
            maid.getPersistentData().m_128359_(TAG_RIDE_MOUNT, maid.m_20148_() + "|"
                    + com.maidsmart.tool.PromaidLog.nameOf(maid) + "|" + mount.m_20148_());
        } catch (Throwable ignored) {
        }
        mark(maid);
        mark(mount);
        bubble(maid, "坐稳啦，我们出发～");
        com.maidsmart.tool.PromaidLog.log("骑乘指挥棒", "绑定：主人=" + name(player)
                + " 女仆=" + com.maidsmart.tool.PromaidLog.nameOf(maid)
                + " 坐骑=" + MaidRideKit.describe(mount)
                + "（速度倍率=" + MaidRideKit.fmt(MaidRideKit.speedModifierFor(mount, maid))
                + "，跟随距离=" + MaidRideKit.fmt(MaidRideKit.followDist()) + " 格）");
    }

    /** 解除：清表 + 下鞍 + 撤标记 */
    static void releaseMaid(EntityMaid maid, boolean natural, String why) {
        if (maid == null) {
            return;
        }
        Link link = LINKS.remove(maid.m_20148_());
        Entity mount = link == null ? null : link.mount.get();
        unmark(maid);
        unmark(mount);
        try {
            maid.getPersistentData().m_128473_(TAG_RIDE_MOUNT);
        } catch (Throwable ignored) {
        }
        if (mount != null) {
            try {
                mount.getPersistentData().m_128473_(TAG_RIDE_MAID);
            } catch (Throwable ignored) {
            }
            MaidRideKit.stopNavigation(mount);
        }
        try {
            if (maid.m_20202_() != null) {
                maid.m_8127_();
            }
        } catch (Throwable ignored) {
        }
        if (!natural) {
            bubble(maid, "好，我自己走～");
        }
        com.maidsmart.tool.PromaidLog.log("骑乘指挥棒", "解除(" + why + ")：女仆="
                + com.maidsmart.tool.PromaidLog.nameOf(maid)
                + (mount == null ? "" : " 坐骑=" + MaidRideKit.describe(mount)));
    }

    /* ==================== 待配对状态 ==================== */

    private static void setPending(ServerPlayer player, Entity e) {
        PENDING.put(player.m_20148_(), new java.lang.ref.WeakReference<>(e));
    }

    private static Entity takePending(ServerPlayer player, boolean wantMaid) {
        java.lang.ref.WeakReference<Entity> ref = PENDING.remove(player.m_20148_());
        Entity e = ref == null ? null : ref.get();
        if (e == null || !e.m_6084_() || e.m_9236_() != player.m_9236_()) {
            return null;
        }
        if (wantMaid != (e instanceof EntityMaid)) {
            PENDING.put(player.m_20148_(), new java.lang.ref.WeakReference<>(e)); // 不是想要的那类：放回去
            return null;
        }
        return e;
    }

    private static Entity takePendingMount(ServerPlayer player) {
        return takePending(player, false);
    }

    private static EntityMaid takePendingMaid(ServerPlayer player) {
        Entity e = takePending(player, true);
        return e instanceof EntityMaid m ? m : null;
    }

    /** 玩家下线/换维度 → 清掉他的选中状态（那只是瞬时 UI 状态） */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // 只在极低频做清理：这里借 PlayerTick 但每 200 tick 才跑一次
        if ((event.player.m_9236_().m_46467_() + event.player.m_19879_()) % 200 != 0) {
            return;
        }
        if (!(event.player instanceof ServerPlayer sp)) {
            return;
        }
        java.lang.ref.WeakReference<Entity> ref = PENDING.get(sp.m_20148_());
        if (ref != null) {
            Entity e = ref.get();
            if (e == null || !e.m_6084_() || e.m_9236_() != sp.m_9236_()) {
                PENDING.remove(sp.m_20148_());
            }
        }
    }

    /** 玩家退服：清他的瞬时状态（链路本身按女仆清理） */
    public static void forgetPlayer(UUID playerId) {
        PENDING.remove(playerId);
        DENY_LOG.remove(playerId);
    }

    /** 女仆卸载/被移出世界：清她的链路（与 GunnerTetherManager.forgetMaid 同款） */
    public static void forgetMaid(UUID maidId) {
        Link link = LINKS.remove(maidId);
        if (link != null) {
            unmark(link.maid.get());
            unmark(link.mount.get());
        }
    }

    /* ==================== 每 2 tick：驱动 + 校验 + 恢复 ==================== */

    public static void tick(MinecraftServer server) {
        if (!isEnabled()) {
            return;
        }
        if (++tickTimer < TICK_DIV) {
            return;
        }
        tickTimer = 0;
        capTables();
        try {
            Iterator<Map.Entry<UUID, Link>> it = LINKS.entrySet().iterator();
            java.util.List<EntityMaid> deferred = new java.util.ArrayList<>();
            while (it.hasNext()) {
                Map.Entry<UUID, Link> e = it.next();
                Link link = e.getValue();
                EntityMaid maid = link.maid.get();
                Entity mount = link.mount.get();
                ServerPlayer owner = link.owner.get();
                if (maid == null || !maid.m_6084_() || mount == null || !mount.m_6084_()) {
                    deferred.add(maid);
                    continue;
                }
                if (maid.m_9236_() != mount.m_9236_()) {
                    deferred.add(maid);
                    continue;
                }
                // 她不在它背上了（被别的模组拽下去 / 自己潜跳下鞍 / 原版把她踢下来）→ 解除
                if (maid.m_20202_() != mount) {
                    deferred.add(maid);
                    continue;
                }
                if (owner == null || !owner.m_6084_() || owner.m_9236_() != maid.m_9236_()) {
                    // 主人没了/换维度：停下载具，但**不解除**（主人回来接着走）
                    MaidRideKit.stopNavigation(mount);
                    continue;
                }
                drive(maid, mount, owner);
            }
            for (EntityMaid m : deferred) {
                if (m == null) {
                    continue;
                }
                releaseMaidQuiet(m);
            }
            // 恢复扫描：她 persistentData 里有坐骑标记、但链路表没她（服务端重启/区块重载）→ 重建
            restore(server);
        } catch (Throwable ignored) {
        }
    }

    /** 循环内不能改 map —— 攒下来再解（与 GunnerTetherManager 实测六百八十二 同款教训） */
    private static void releaseMaidQuiet(EntityMaid maid) {
        Link link = LINKS.remove(maid.m_20148_());
        Entity mount = link == null ? null : link.mount.get();
        unmark(maid);
        unmark(mount);
        try {
            maid.getPersistentData().m_128473_(TAG_RIDE_MOUNT);
        } catch (Throwable ignored) {
        }
        if (mount != null) {
            try {
                mount.getPersistentData().m_128473_(TAG_RIDE_MAID);
            } catch (Throwable ignored) {
            }
            MaidRideKit.stopNavigation(mount);
        }
    }

    /**
     * 驱动：把"她本来要去的地方"转达给坐骑自己的寻路。
     *
     * <p>目的地来源（按优先级）：
     * <ol>
     *   <li>她脑里的 {@code WALK_TARGET}——跟随/走位/任务写的都是这里，直接复用（"套已有链路"）；</li>
     *   <li>退不到就用主人当前的位置（离主人超过 followDist 才喂）。</li>
     * </ol>
     * 两者都够近就 {@code stop()}——让坐骑原地站住，别顶着自己人打转。
     */
    private static void drive(EntityMaid maid, Entity mount, ServerPlayer owner) {
        try {
            double mod = MaidRideKit.speedModifierFor(mount, maid);
            Vec3 target = null;
            try {
                var wt = maid.m_6274_().m_21952_(net.minecraft.world.entity.ai.memory.MemoryModuleType.f_26370_);
                if (wt.isPresent()) {
                    Vec3 t = wt.get().m_26420_().m_7024_();
                    if (t != null) {
                        target = t;
                    }
                }
            } catch (Throwable ignored) {
            }
            if (target == null) {
                double d = horizontalDist(mount, owner);
                if (d > MaidRideKit.followDist()) {
                    target = owner.m_20182_();
                }
            }
            if (target == null) {
                MaidRideKit.stopNavigation(mount);
                return;
            }
            // 目标已经够近（跟随档的余量）→ 站住；走位目标则交给它自己判断
            if (horizontalDist(mount, target) <= STOP_SLACK) {
                MaidRideKit.stopNavigation(mount);
                return;
            }
            MaidRideKit.feedNavigation(mount, target, mod);
        } catch (Throwable ignored) {
        }
    }

    /** 恢复：她 persistentData 有标记、链路表却没有 → 重建（服务端重启/区块重载后） */
    private static void restore(MinecraftServer server) {
        for (ServerLevel level : server.m_129785_()) {
            for (Entity e : com.maidsmart.tool.EntitySnapshot.of(level)) {
                if (!(e instanceof EntityMaid maid)) {
                    continue;
                }
                if (LINKS.containsKey(maid.m_20148_())) {
                    continue;
                }
                String tag;
                try {
                    tag = maid.getPersistentData().m_128461_(TAG_RIDE_MOUNT);
                } catch (Throwable ignored) {
                    continue;
                }
                if (tag == null || tag.isEmpty()) {
                    continue;
                }
                Entity mount = maid.m_20202_();
                if (mount == null || mount instanceof EntityMaid) {
                    try {
                        maid.getPersistentData().m_128473_(TAG_RIDE_MOUNT);
                    } catch (Throwable ignored) {
                    }
                    continue;
                }
                LivingEntity owner = maid.m_269323_();
                if (owner instanceof ServerPlayer sp) {
                    LINKS.put(maid.m_20148_(), new Link(maid, mount, sp));
                    mark(maid);
                    mark(mount);
                    com.maidsmart.tool.PromaidLog.log("骑乘指挥棒", "恢复：女仆="
                            + com.maidsmart.tool.PromaidLog.nameOf(maid)
                            + " 仍骑在 " + MaidRideKit.describe(mount) + " 上 → 重建链路");
                } else {
                    try {
                        maid.getPersistentData().m_128473_(TAG_RIDE_MOUNT);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    /* ==================== 标记（玩家说的"光标"）/ 提示 ==================== */

    /**
     * 打标记：沿用武装拴绳那套**原版发光标记**（同光灵箭的渲染，客户端 {@code MaidGlowGoldMixin}
     * 把描边改成金色）——玩家原话"被绑定以后会出现光标"。女仆与坐骑一起打，一眼看出是哪一对。
     */
    private static void mark(Entity e) {
        if (e == null) {
            return;
        }
        try {
            e.m_146915_(true);
        } catch (Throwable ignored) {
        }
    }

    /** 撤标记（{@code setGlowingTag(false)} 内部会重新求值，真中了光灵箭不会被误清） */
    private static void unmark(Entity e) {
        if (e == null) {
            return;
        }
        try {
            e.m_146915_(false);
        } catch (Throwable ignored) {
        }
    }

    private static void bubble(EntityMaid maid, String msg) {
        try {
            maid.getChatBubbleManager().addTextChatBubble(msg);
        } catch (Throwable ignored) {
        }
    }

    private static void deny(ServerPlayer player, EntityMaid maid, String msg) {
        long now = System.currentTimeMillis();
        Long last = DENY_LOG.get(player.m_20148_());
        if (last != null && now - last < DENY_INTERVAL_MS) {
            return;
        }
        DENY_LOG.put(player.m_20148_(), now);
        try {
            if (maid != null) {
                bubble(maid, msg);
            } else {
                player.m_213846_(Component.m_237113_("\u00a7c" + msg));
            }
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 小工具 ==================== */

    private static boolean ownable(ServerPlayer player, EntityMaid maid) {
        try {
            LivingEntity owner = maid.m_269323_();
            return owner != null && owner.m_20148_().equals(player.m_20148_());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static double horizontalDist(Entity a, Entity b) {
        try {
            double dx = a.m_20185_() - b.m_20185_();
            double dz = a.m_20189_() - b.m_20189_();
            return Math.sqrt(dx * dx + dz * dz);
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    private static double horizontalDist(Entity a, Vec3 b) {
        try {
            double dx = a.m_20185_() - b.f_82479_;
            double dz = a.m_20189_() - b.f_82481_;
            return Math.sqrt(dx * dx + dz * dz);
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    private static String name(ServerPlayer p) {
        try {
            return p.m_5446_() != null ? p.m_5446_().getString() : p.m_20148_().toString();
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static void capTables() {
        try {
            com.maidsmart.tool.StateTables.cap("骑乘.LINKS", LINKS);
            com.maidsmart.tool.StateTables.cap("骑乘.PENDING", PENDING);
            com.maidsmart.tool.StateTables.cap("骑乘.DENY_LOG", DENY_LOG);
        } catch (Throwable ignored) {
        }
    }
}
