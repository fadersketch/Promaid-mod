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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * v1.3.0(beta)【骑乘指挥棒·原版生物骑乘】（1.21.1 版）——把"已上鞍的坐骑"与"女仆"绑在一起，
 * 让她骑着它跟主人走。
 *
 * <p>需求原文与完整口径见 1.20.1 树同名类的类注释（逐条对应：套僵尸骑乘、两向绑定、
 * 金色"光标"标记、潜行右击下坐骑、移动 AI 套已有链路、速度取最大值）。本树只是把 SRG 名换成
 * 官方名、把 Forge 事件换成 NeoForge 事件，行为完全一致。
 *
 * ── 【绑定：两向都行】── 手持骑乘指挥棒右击坐骑 → 选中它（金色描边）；右击自己的女仆 → 选中她；
 * 两边都有 → 当场 {@code maid.startRiding(mount, true)}（**套僵尸骑鸡那一记**）+ 写链路表。
 *
 * ── 【下坐骑】── 潜行 + 右击（女仆或坐骑均可）→ 解除 + {@code stopRiding()}。
 *
 * ── 【移动 AI】── 每 2 tick 读她脑里的 {@code WALK_TARGET}（跟随/走位/任务都写这里），把那个点
 * 转达给**坐骑自己的 PathNavigation**——目的地的计算完全复用现有系统，我们只把"她走"换成
 * "坐骑走"，上下坡/绕障/跳跃/原版动画由坐骑自己的寻路处理。
 */
@EventBusSubscriber(modid = "promaid")
public final class RideBindManager {

    /** persistentData：她骑的是哪只坐骑（UUID）——跨存档重建用 */
    public static final String TAG_RIDE_MOUNT = MaidRideKit.TAG_RIDE_MOUNT;
    /** persistentData：这只坐骑驮的是哪位女仆——坐骑侧留痕 */
    public static final String TAG_RIDE_MAID = "maid_smart_ride_maid";
    /** 【实测七百一十六·点3】persistentData：这只实体正被棍子"待配对"选中（金色"光标"） */
    public static final String TAG_PENDING_MARK = "maid_smart_ride_pending";

    private static final int TICK_DIV = 2;
    private static final long DENY_INTERVAL_MS = 3000L;
    private static final double STOP_SLACK = 1.5;

    private static final Map<UUID, Link> LINKS = new HashMap<>();
    private static final Map<UUID, java.lang.ref.WeakReference<Entity>> PENDING = new HashMap<>();
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

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!isEnabled()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        InteractionHand hand = event.getHand();
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof RideBatonItem)) {
            return;
        }
        Entity target = event.getTarget();
        if (target == null) {
            return;
        }
        boolean maid = target instanceof EntityMaid;
        boolean mount = !maid && MaidRideKit.isRideableMount(target, null);
        // v1.3.0(beta) 实测七百一十八·点4：家具（椅子/坐垫）与扫帚若不是 Mob，会在下面那句
        // "对着别的实体挥棍子没效果"里直接 return —— 玩家看不到任何提示。这里把它们收进来，
        // 让它们在 denyReason 里给出明确拒绝。
        boolean rejected = !maid && (MaidRideKit.isFurniture(target) || MaidRideKit.isBroom(target));
        if (!maid && !mount && !rejected && !(target instanceof Mob)) {
            return;
        }
        event.setCanceled(true);
        player.swing(hand);
        handle(player, target);
    }

    /* ==================== 选中 / 配对 / 解除 ==================== */

    private static void handle(ServerPlayer player, Entity target) {
        try {
            if (player.isCrouching()) {
                dismountByClick(player, target);
                return;
            }
            if (target instanceof EntityMaid m) {
                if (!ownable(player, m)) {
                    deny(player, m, "她不是我的女仆～");
                    return;
                }
                // v1.3.0(beta) 实测七百一十八·点4：判据从 isRidingMount 收紧到 isRideRider——
                // 她若只是原版/别的模组让她坐上去的（不是我们绑的），指挥棒不该把她拽下来。
                if (MaidRideKit.isRideRider(m)) {
                    releaseMaid(m, false, "再选一次");
                    return;
                }
                Entity mount = takePendingMount(player);
                if (mount != null) {
                    bind(player, m, mount);
                    return;
                }
                if (MaidBroomKit.isRidingBroom(m) || MaidBroomKit.isBroomTask(m)) {
                    deny(player, m, "她正在骑扫帚，先让她收好扫帚～");
                    return;
                }
                setPending(player, m);
                bubble(m, "好呀，再指一只上了鞍的坐骑给我～");
                com.maidsmart.tool.PromaidLog.log("骑乘指挥棒", "选中女仆："
                        + com.maidsmart.tool.PromaidLog.nameOf(m) + "（玩家=" + name(player) + "，等坐骑）");
                return;
            }
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
            // 这只坐骑上是不是已经驮着**我的**女仆、且是我们绑的 → 再选一次 = 解除
            EntityMaid rider = MaidRideKit.riderOf(target);
            if (rider != null && ownable(player, rider) && MaidRideKit.isRideRider(rider)) {
                releaseMaid(rider, false, "再选一次");
                return;
            }
            setPending(player, target);
            player.displayClientMessage(Component.literal("\u00a7a已选中坐骑：\u00a7f"
                    + MaidRideKit.describe(target) + "\u00a7a → 再右击自己的女仆即可配对"), false);
            com.maidsmart.tool.PromaidLog.log("骑乘指挥棒", "选中坐骑："
                    + MaidRideKit.describe(target) + "（玩家=" + name(player) + "，等女仆）");
        } catch (Throwable ignored) {
        }
    }

    private static void dismountByClick(ServerPlayer player, Entity target) {
        EntityMaid m = target instanceof EntityMaid mm ? mm : MaidRideKit.riderOf(target);
        if (m == null) {
            player.displayClientMessage(Component.literal("\u00a77这只坐骑背上没有我的女仆"), false);
            return;
        }
        if (!ownable(player, m)) {
            deny(player, m, "她不是我的女仆～");
            return;
        }
        // 实测七百一十八·点4：只有"骑乘棒绑上去的"才由我们负责弄下来。
        if (!MaidRideKit.isRideRider(m)) {
            player.displayClientMessage(Component.literal(
                    "\u00a77她不是用骑乘指挥棒绑上去的，我不去动她"), false);
            return;
        }
        releaseMaid(m, false, "潜行下鞍");
    }

    /** 配对：套僵尸骑鸡那一记 —— {@code startRiding(force)} + 写表 + 打标记 */
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
        // 【实测七百一十六·点1】独占：与武装拴绳同款——一位玩家同时只带一条骑乘链路。
        // 玩家原话："如果绑定了一个女仆再绑定另一个，那么第1个会解绑，坐骑同理。"
        releaseOtherLinks(player, maid);
        releaseMountLinks(mount, maid);
        if (LINKS.containsKey(maid.getUUID())) {
            releaseMaid(maid, false, "换绑");
        }
        boolean ok = false;
        try {
            ok = maid.startRiding(mount, true);
        } catch (Throwable ignored) {
        }
        if (!ok) {
            deny(player, maid, "没能坐上去……再试一次？");
            return;
        }
        LINKS.put(maid.getUUID(), new Link(maid, mount, player));
        try {
            maid.getPersistentData().putString(TAG_RIDE_MOUNT, maid.getUUID() + "|"
                    + com.maidsmart.tool.PromaidLog.nameOf(maid) + "|" + mount.getUUID());
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

    static void releaseMaid(EntityMaid maid, boolean natural, String why) {
        if (maid == null) {
            return;
        }
        Link link = LINKS.remove(maid.getUUID());
        Entity mount = link == null ? null : link.mount.get();
        unmark(maid);
        unmark(mount);
        try {
            maid.getPersistentData().remove(TAG_RIDE_MOUNT);
        } catch (Throwable ignored) {
        }
        if (mount != null) {
            try {
                mount.getPersistentData().remove(TAG_RIDE_MAID);
            } catch (Throwable ignored) {
            }
            MaidRideKit.stopNavigation(mount);
        }
        try {
            if (maid.getVehicle() != null) {
                maid.stopRiding();
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

    /**
     * 【实测七百一十六·点1】换绑 = 先松开这位玩家名下**别的**骑乘链路（口径照抄武装拴绳的
     * {@code GunnerTetherManager.releaseOtherLinks}：只认"链路里的主人 == 这位玩家"，
     * 先收集再解除——releaseMaid 会改 LINKS，边走边删会炸迭代器）。
     */
    private static void releaseOtherLinks(ServerPlayer player, EntityMaid except) {
        try {
            java.util.List<EntityMaid> others = new java.util.ArrayList<>();
            for (Map.Entry<UUID, Link> e : LINKS.entrySet()) {
                Link link = e.getValue();
                EntityMaid m = link.maid.get();
                if (m == null || m == except) {
                    continue;
                }
                ServerPlayer p = link.owner.get();
                if (p != null && p.getUUID().equals(player.getUUID())) {
                    others.add(m);
                }
            }
            for (EntityMaid m : others) {
                releaseMaid(m, false, "换绑");
                com.maidsmart.tool.PromaidLog.log("骑乘指挥棒", "换绑：先松开女仆="
                        + com.maidsmart.tool.PromaidLog.nameOf(m) + "（主人=" + name(player) + "）");
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【实测七百一十六·点1】"坐骑同理"：这只坐骑上若已驮着**另一位**女仆的链路，一并松开
     * ——一只坐骑同时只服务一位女仆（先收集再解除，理由同上）。
     */
    private static void releaseMountLinks(Entity mount, EntityMaid except) {
        try {
            java.util.List<EntityMaid> others = new java.util.ArrayList<>();
            for (Map.Entry<UUID, Link> e : LINKS.entrySet()) {
                Link link = e.getValue();
                EntityMaid m = link.maid.get();
                Entity mm = link.mount.get();
                if (m == null || m == except || mm == null) {
                    continue;
                }
                if (mm.getUUID().equals(mount.getUUID())) {
                    others.add(m);
                }
            }
            for (EntityMaid m : others) {
                releaseMaid(m, false, "坐骑换主");
                com.maidsmart.tool.PromaidLog.log("骑乘指挥棒", "坐骑换主：先松开女仆="
                        + com.maidsmart.tool.PromaidLog.nameOf(m) + "（这只坐骑改配另一位）");
            }
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 待配对状态 ==================== */

    /**
     * 【实测七百一十六·点3】选中即亮"光标"——玩家原话："如果玩家用这根棒子选中了那个女仆/
     * 可骑乘坐骑，那就应该立刻显示光标，而不是坐上坐骑以后再显示。"
     */
    private static void setPending(ServerPlayer player, Entity e) {
        java.lang.ref.WeakReference<Entity> old = PENDING.get(player.getUUID());
        if (old != null) {
            clearPendingMark(old.get());
        }
        PENDING.put(player.getUUID(), new java.lang.ref.WeakReference<>(e));
        markPending(e);
    }

    private static Entity takePending(ServerPlayer player, boolean wantMaid) {
        java.lang.ref.WeakReference<Entity> ref = PENDING.remove(player.getUUID());
        Entity e = ref == null ? null : ref.get();
        if (e == null || !e.isAlive() || e.level() != player.level()) {
            return null;
        }
        if (wantMaid != (e instanceof EntityMaid)) {
            PENDING.put(player.getUUID(), new java.lang.ref.WeakReference<>(e));
            return null;
        }
        clearPendingMark(e); // 点3：配对成功 → 撤掉"待选光标"（绑定后由 mark() 打正式标记）
        return e;
    }

    private static Entity takePendingMount(ServerPlayer player) {
        return takePending(player, false);
    }

    private static EntityMaid takePendingMaid(ServerPlayer player) {
        Entity e = takePending(player, true);
        return e instanceof EntityMaid m ? m : null;
    }

    /** 玩家每个 tick 都过一遍，但每 200 tick 才真做一次清理 */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) {
            return;
        }
        if ((sp.level().getGameTime() + sp.getId()) % 200 != 0) {
            return;
        }
        java.lang.ref.WeakReference<Entity> ref = PENDING.get(sp.getUUID());
        if (ref != null) {
            Entity e = ref.get();
            if (e == null || !e.isAlive() || e.level() != sp.level()) {
                clearPendingMark(e); // 点3：超时/消失也要撤掉"待选光标"
                PENDING.remove(sp.getUUID());
            }
        }
    }

    public static void forgetPlayer(UUID playerId) {
        java.lang.ref.WeakReference<Entity> ref = PENDING.remove(playerId);
        if (ref != null) {
            clearPendingMark(ref.get()); // 点3：下线时撤掉"待选光标"
        }
        DENY_LOG.remove(playerId);
    }

    public static void forgetMaid(UUID maidId) {
        Link link = LINKS.remove(maidId);
        if (link != null) {
            unmark(link.maid.get());
            unmark(link.mount.get());
        }
    }

    /** 点4：她是不是"被棍子绑上坐骑"的骑乘女仆（连坐骑一起搬运的判据）——给传送链路问 */
    public static boolean isRideRider(EntityMaid maid) {
        return MaidRideKit.isRideRider(maid);
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
                if (maid == null || !maid.isAlive() || mount == null || !mount.isAlive()) {
                    deferred.add(maid);
                    continue;
                }
                if (maid.level() != mount.level()) {
                    deferred.add(maid);
                    continue;
                }
                if (maid.getVehicle() != mount) {
                    deferred.add(maid);
                    continue;
                }
                if (owner == null || !owner.isAlive() || owner.level() != maid.level()) {
                    MaidRideKit.stopNavigation(mount);
                    continue;
                }
                drive(maid, mount, owner);
            }
            for (EntityMaid m : deferred) {
                if (m != null) {
                    releaseMaidQuiet(m);
                }
            }
            restore(server);
        } catch (Throwable ignored) {
        }
    }

    private static void releaseMaidQuiet(EntityMaid maid) {
        Link link = LINKS.remove(maid.getUUID());
        Entity mount = link == null ? null : link.mount.get();
        unmark(maid);
        unmark(mount);
        try {
            maid.getPersistentData().remove(TAG_RIDE_MOUNT);
        } catch (Throwable ignored) {
        }
        if (mount != null) {
            try {
                mount.getPersistentData().remove(TAG_RIDE_MAID);
            } catch (Throwable ignored) {
            }
            MaidRideKit.stopNavigation(mount);
        }
    }

    private static void drive(EntityMaid maid, Entity mount, ServerPlayer owner) {
        try {
            double mod = MaidRideKit.speedModifierFor(mount, maid);
            // ① 她自己的走路意图（1:1 还原走位）——最优先，与"两条腿"时同源
            Vec3 target = MaidRideKit.ownNavigationTarget(maid);
            // ② 她的走位记忆（任务/跟随写在这里）
            if (target == null) {
                try {
                    var wt = maid.getBrain().getMemory(
                            net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
                    if (wt.isPresent()) {
                        Vec3 t = wt.get().getTarget().currentPosition();
                        if (t != null) {
                            target = t;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            // ③ 都没有 → 跟主人走（够远才喂）
            if (target == null) {
                if (horizontalDist(mount, owner) > MaidRideKit.followDist()) {
                    target = owner.position();
                }
            }
            if (target == null) {
                MaidRideKit.stopNavigation(mount);
                return;
            }
            if (horizontalDist(mount, target) <= STOP_SLACK) {
                MaidRideKit.stopNavigation(mount);
                return;
            }
            MaidRideKit.feedNavigation(mount, target, mod);
        } catch (Throwable ignored) {
        }
    }

    private static void restore(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : com.maidsmart.tool.EntitySnapshot.of(level)) {
                if (!(e instanceof EntityMaid maid)) {
                    continue;
                }
                if (LINKS.containsKey(maid.getUUID())) {
                    continue;
                }
                String tag;
                try {
                    tag = maid.getPersistentData().getString(TAG_RIDE_MOUNT);
                } catch (Throwable ignored) {
                    continue;
                }
                if (tag == null || tag.isEmpty()) {
                    continue;
                }
                Entity mount = maid.getVehicle();
                if (mount == null || mount instanceof EntityMaid) {
                    try {
                        maid.getPersistentData().remove(TAG_RIDE_MOUNT);
                    } catch (Throwable ignored) {
                    }
                    continue;
                }
                LivingEntity owner = maid.getOwner();
                if (owner instanceof ServerPlayer sp) {
                    LINKS.put(maid.getUUID(), new Link(maid, mount, sp));
                    mark(maid);
                    mark(mount);
                    com.maidsmart.tool.PromaidLog.log("骑乘指挥棒", "恢复：女仆="
                            + com.maidsmart.tool.PromaidLog.nameOf(maid)
                            + " 仍骑在 " + MaidRideKit.describe(mount) + " 上 → 重建链路");
                } else {
                    try {
                        maid.getPersistentData().remove(TAG_RIDE_MOUNT);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    /* ==================== 标记 / 提示 ==================== */

    private static void mark(Entity e) {
        if (e == null) {
            return;
        }
        try {
            e.setGlowingTag(true);
        } catch (Throwable ignored) {
        }
    }

    private static void unmark(Entity e) {
        if (e == null) {
            return;
        }
        try {
            e.setGlowingTag(false);
        } catch (Throwable ignored) {
        }
    }

    /** 【实测七百一十六·点3】"待选光标"：选中即亮（未绑定也亮），单独留痕便于"只撤我们打的那一下" */
    private static void markPending(Entity e) {
        if (e == null) {
            return;
        }
        try {
            e.setGlowingTag(true);
            e.getPersistentData().putBoolean(TAG_PENDING_MARK, true);
        } catch (Throwable ignored) {
        }
    }

    /** 撤掉"待选光标"：只在她身上没有正式链路标记时才熄灯（防把已绑定的标记一起撤掉） */
    private static void clearPendingMark(Entity e) {
        if (e == null) {
            return;
        }
        try {
            boolean pending = e.getPersistentData().getBoolean(TAG_PENDING_MARK);
            if (!pending) {
                return;
            }
            e.getPersistentData().remove(TAG_PENDING_MARK);
            if (e instanceof EntityMaid m && LINKS.containsKey(m.getUUID())) {
                return;
            }
            e.setGlowingTag(false);
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
        Long last = DENY_LOG.get(player.getUUID());
        if (last != null && now - last < DENY_INTERVAL_MS) {
            return;
        }
        DENY_LOG.put(player.getUUID(), now);
        try {
            if (maid != null) {
                bubble(maid, msg);
            } else {
                player.displayClientMessage(Component.literal("\u00a7c" + msg), false);
            }
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 小工具 ==================== */

    private static boolean ownable(ServerPlayer player, EntityMaid maid) {
        try {
            LivingEntity owner = maid.getOwner();
            return owner != null && owner.getUUID().equals(player.getUUID());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static double horizontalDist(Entity a, Entity b) {
        try {
            double dx = a.getX() - b.getX();
            double dz = a.getZ() - b.getZ();
            return Math.sqrt(dx * dx + dz * dz);
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    private static double horizontalDist(Entity a, Vec3 b) {
        try {
            double dx = a.getX() - b.x;
            double dz = a.getZ() - b.z;
            return Math.sqrt(dx * dx + dz * dz);
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    private static String name(ServerPlayer p) {
        try {
            return p.getName() != null ? p.getName().getString() : p.getUUID().toString();
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
