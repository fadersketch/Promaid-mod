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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
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

    // 【实测七百二十四】旧的 CHAIR_MAX_GAP（偏离 8 格就解除悬空鞍位链路）已删除：
    // 距离不再是"断开"的理由——她本来就每拍被摆回鞍位；只有她/龙/主人真没了或跨维度才解除。
    // 详见 tick() 里那段注释。

    private static final Map<UUID, Link> LINKS = new HashMap<>();
    private static final Map<UUID, java.lang.ref.WeakReference<Entity>> PENDING = new HashMap<>();
    /**
     * 【实测七百二十九·点2】"待选光标"的**开始时刻**（{@code playerUUID → 毫秒}）。
     *
     * <p>为什么必须有它：{@link #markPending} 给目标打的是**原版发光标记**（写进 NBT，跨存档还在），
     * 而旧版 {@link #PENDING} 只有"再点一个/实体没了/玩家下线"三条清理路径——**没有超时**。
     * 于是"用棍子点了一下龙、然后走开"这种最常见的操作，会让那条龙**永久发光**，而且
     * 重启存档也还在（玩家原话：「如果一只龙被绑定了以后并且上了光标，那就再也没有办法解除他身上的
     * 光标了」）。这里记下时刻，{@link #tick} 每 2 tick 扫一遍超时的，到点就熄。
     */
    private static final Map<UUID, Long> PENDING_AT = new HashMap<>();
    private static final Map<UUID, Long> DENY_LOG = new HashMap<>();

    /**
     * 【实测七百二十七·点4】悬空鞍位（龙）配对的**客户端镜像**：女仆实体 id → 龙的实体 id。
     *
     * <p>客户端**没有链路表**（{@link #LINKS} 只活在服务端），所以"她挂在哪条龙上"这项知识
     * 必须由服务端 S2C 同步（{@link MaidSeatNetworking}）。见 {@link #onSeatSync}。
     */
    public static final Map<Integer, Integer> SYNCED_CHAIRS = new HashMap<>();

    private static int tickTimer = 0;

    private static final class Link {
        final java.lang.ref.WeakReference<EntityMaid> maid;
        final java.lang.ref.WeakReference<Entity> mount;
        final java.lang.ref.WeakReference<ServerPlayer> owner;
        /**
         * 【实测七百二十三】冰火传说龙专用：**降级方案**里她不是乘客，只是挂在鞍位上。
         * {@code true} = 这一对走的是"悬空鞍位"（不 {@code startRiding}、每拍由我们摆位）。
         */
        final boolean chair;
        /** 绑上之前龙的 {@code getCommand()}（解绑时还原；非龙 / 没读到 → -1）。 */
        final int dragonCommand;
        /**
         * 【实测七百二十五】绑上之前她的 TLM 坐姿标志——解绑时原样还原（玩家手动让她坐下 /
         * 原版"坐下"指令都不会被我们吃掉）。
         */
        final boolean prevSitting;

        Link(EntityMaid m, Entity mount, ServerPlayer owner, boolean prevSitting) {
            this(m, mount, owner, false, -1, prevSitting);
        }

        Link(EntityMaid m, Entity mount, ServerPlayer owner, boolean chair, int dragonCommand,
             boolean prevSitting) {
            this.maid = new java.lang.ref.WeakReference<>(m);
            this.mount = new java.lang.ref.WeakReference<>(mount);
            this.owner = new java.lang.ref.WeakReference<>(owner);
            this.chair = chair;
            this.dragonCommand = dragonCommand;
            this.prevSitting = prevSitting;
        }
    }

    private RideBindManager() {
    }

    private static boolean isEnabled() {
        return MaidRideKit.enabled();
    }

    /**
     * 【实测七百二十·点2】骑乘指挥棒是否**独占右击**（配置 {@code ride.batonExclusive}，默认开）。
     * 完整口径见 1.20.1 树同名类。
     */
    private static boolean batonExclusive() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_BATON_EXCLUSIVE.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /* ==================== 事件入口 ==================== */

    /**
     * 【实测七百二十·点2】目标先过一道 {@link MaidMountCompat#resolveMount}——冰火传说的龙是多部件
     * 实体，准星常常打中的是翅膀/尾巴/头那些部位实体，而部位会把右击转发给本体（反编译实证）。
     */
    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!isEnabled()) {
            return;
        }
        // 【实测七百二十三】判据从 ServerPlayer 放宽到 Player：这个事件**两侧都发**
        // （服务端 Player.interactOn；客户端 MultiPlayerGameMode → LocalPlayer.interactOn
        // 同一条链）。旧版只认 ServerPlayer，等于**客户端那一份从来没被拦**——而卓越前线的
        // VehicleEntity.interact 在**客户端本地**也跑，它一跑就会 setDriverAngle（把玩家转向
        // 车头）+ 把第一个非玩家乘客 stopRiding 踢掉。这正是玩家说的"右击完之后玩家的位置
        // 发生了改变。似乎是坐上去秒坐下来的结果"。现在两侧都拦：客户端这一下直接被吃掉。
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player player = event.getEntity();
        if (!(player instanceof ServerPlayer)) {
            // 客户端：只做"吞掉这一下"，**不执行业务逻辑**（绑定/解除只由服务端做一次，
            // 否则同一个右击会在两侧各绑一次）。认得出是棍子 + 认得的目标就取消事件。
            cancelClientBatonInteract(player, event);
            return;
        }
        InteractionHand hand = event.getHand();
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof RideBatonItem)) {
            return;
        }
        Entity raw = event.getTarget();
        if (raw == null) {
            return;
        }
        Entity target = MaidMountCompat.resolveMount(raw);
        boolean maid = target instanceof EntityMaid;
        boolean mount = !maid && MaidRideKit.isRideableMount(target, null);
        // v1.3.0(beta) 实测七百一十八·点4：家具（椅子/坐垫）与扫帚若不是 Mob，会在下面那句
        // "对着别的实体挥棍子没效果"里直接 return —— 玩家看不到任何提示。这里把它们收进来，
        // 让它们在 denyReason 里给出明确拒绝。
        boolean rejected = !maid && (MaidRideKit.isFurniture(target) || MaidRideKit.isBroom(target));
        boolean recognized = maid || mount || rejected || (target instanceof Mob);
        // 【实测七百二十·点2 独占档】手持骑乘棒时，这一下实体右击一律由棍子吃掉（认不出也吞）。
        if (!recognized && !batonExclusive()) {
            return;
        }
        // 【实测七百二十四】右击载具会转玩家视角：SWB 的 VehicleVecUtils.setDriverAngle 在
        // player.startRiding 之前就调用了，而我们随后拦下 startRiding 也拦不回已经转过的视角。
        // 所以这一下前后做"视角快照 / 还原"——只把**这一下右击**造成的转动抹掉，玩家自己转视角不受影响。
        float[] snap = ViewSnapshot.capture(player);
        event.setCanceled(true);
        if (recognized) {
            player.swing(hand);
            handle((ServerPlayer) player, target);
        }
        ViewSnapshot.restoreIfChanged(player, snap);
    }

    /**
     * 【实测七百二十三】客户端那一份的右击闸：把"手里拿着指挥棒 + 面对一个我们认得出的目标"
     * 的这一下**取消掉**，于是本地预测不会再进 {@code VehicleEntity.interact}（SWB 的
     * {@code player.startRiding} / {@code setDriverAngle} / 把女仆 {@code stopRiding} 都在里面）。
     *
     * <p>为什么客户端也要拦：绑定/解除本身只由服务端那份事件做（客户端做会双绑），
     * 但**客户端本地预测**是独立跑的——不拦它，玩家屏幕上就会出现"转了一下头 / 位置跳了一下"。
     * 这里只 cancel，不调 {@code handle}。
     */
    private static void cancelClientBatonInteract(Player player, PlayerInteractEvent.EntityInteract event) {
        try {
            if (!batonExclusive()) {
                return;
            }
            ItemStack stack = player.getItemInHand(event.getHand());
            if (!(stack.getItem() instanceof RideBatonItem)) {
                return;
            }
            Entity target = MaidMountCompat.resolveMount(event.getTarget());
            if (target == null) {
                return;
            }
            boolean recognized = target instanceof EntityMaid || target instanceof Mob
                    || MaidRideKit.isRideableMount(target, null)
                    || MaidMountCompat.kindOf(target) != null;
            if (recognized) {
                // 【实测七百二十四】客户端本地预测那一拍：SWB 的 VehicleEntity.interact 在**客户端**
                // 也跑，setDriverAngle 会当场把玩家转向车头。快照 + 钉住几拍把这一下抹掉。
                ViewSnapshot.pin(player, ViewSnapshot.capture(player));
                event.setCanceled(true);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【实测七百二十四】"右击载具不再转玩家视角"的兜底口径。
     *
     * <p>根因（反编译实证）：SWB {@code VehicleVecUtils.setDriverAngle(vehicle, player)} 是**全 jar
     * 唯一**改玩家 yRot/xRot/yHeadRot 的地方，而它在 {@code VehicleEntity.interact} 里
     * {@code player.startRiding} **之前**就被调用（:3172 / :3184）。我们虽然后面把 startRiding 拦了，
     * 但"转视角"这一下已经发生——玩家原话「每次拿骑乘棒右击一下载具，玩家的视角都会转一下」。
     *
     * <p>所以右击之后**短时间内每客户端 tick 复述一次快照**（只覆盖那几拍，过期自动失效），
     * 把 SWB 本地预测写进去的朝向抹平。玩家自己主动转视角不受影响。
     *
     * <p><b>客户端类型隔离</b>：本类两侧都会加载（NeoForge 专用服务器也加载），所以这里**不碰
     * {@code net.minecraft.client.*}**——每客户端 tick 的复述放在客户端专属类
     * {@code com.maidsmart.client.RideBatonViewClamp}（只在客户端注册，见 {@code ProMaidExtension}）。
     * 本类只提供纯原版字段的"记录/还原"。
     */
    private static final class ViewSnapshot {
        /** 玩家 UUID → [untilMillis, yRot, xRot, yHeadRot, yRotO, xRotO, yHeadRotO]（Float 位打包）。 */
        private static final Map<UUID, long[]> PINS = new HashMap<>();

        static float[] capture(Player p) {
            try {
                return new float[]{p.getYRot(), p.getXRot(), p.getYHeadRot(),
                        p.yRotO, p.xRotO, p.yHeadRotO};
            } catch (Throwable ignored) {
                return null;
            }
        }

        static void restoreIfChanged(Player p, float[] snap) {
            try {
                if (snap == null) {
                    return;
                }
                if (p.getYRot() != snap[0] || p.getXRot() != snap[1] || p.getYHeadRot() != snap[2]) {
                    p.setYRot(snap[0]);
                    p.setXRot(snap[1]);
                    p.setYHeadRot(snap[2]);
                    p.yRotO = snap[3];
                    p.xRotO = snap[4];
                    p.yHeadRotO = snap[5];
                }
            } catch (Throwable ignored) {
            }
        }

        /** 钉住：接下来 {@link #PIN_TICKS} 个客户端 tick 内，每拍把朝向复述回快照。 */
        static void pin(Player p, float[] snap) {
            try {
                if (p == null || snap == null) {
                    return;
                }
                long until = System.currentTimeMillis() + PIN_TICKS * 50L;
                PINS.put(p.getUUID(), new long[]{until,
                        Float.floatToIntBits(snap[0]), Float.floatToIntBits(snap[1]),
                        Float.floatToIntBits(snap[2]), Float.floatToIntBits(snap[3]),
                        Float.floatToIntBits(snap[4]), Float.floatToIntBits(snap[5])});
                if (PINS.size() > 64) {
                    PINS.clear();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 【实测七百二十四】给客户端专属类调的：若这位玩家的视角还被钉着，就复述回快照（返回 true = 钉着）。
     * 纯原版字段操作，服务端加载本类也安全。
     */
    public static boolean clampPinnedView(Player player) {
        try {
            if (player == null) {
                return false;
            }
            long[] v = ViewSnapshot.PINS.get(player.getUUID());
            if (v == null) {
                return false;
            }
            if (System.currentTimeMillis() > v[0]) {
                ViewSnapshot.PINS.remove(player.getUUID()); // 过期 → 失效
                return false;
            }
            float[] snap = {Float.intBitsToFloat((int) v[1]), Float.intBitsToFloat((int) v[2]),
                    Float.intBitsToFloat((int) v[3]), Float.intBitsToFloat((int) v[4]),
                    Float.intBitsToFloat((int) v[5]), Float.intBitsToFloat((int) v[6])};
            ViewSnapshot.restoreIfChanged(player, snap);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 右击之后"钉住视角"持续多少个客户端 tick（约 4 拍，足够盖住 SWB 本地预测那一拍）。 */
    private static final int PIN_TICKS = 4;

    /**
     * 【实测七百二十·点2】独占档的第二道闸：{@code EntityInteractSpecific}（= 原版 {@code interactAt}）。
     * 客户端先发 interactAt、未被消费才发 interact（反编译实证），所以两个入口都拦掉才算"不触发
     * 原本的右击效果"。这里**只 cancel、不调 handle**——两个入口都做事会重复执行一遍绑定/解除。
     */
    @SubscribeEvent
    public static void onInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!isEnabled() || !batonExclusive()) {
            return;
        }
        // 【实测七百二十三】与 onInteract 同款放宽到 Player：客户端那份不拦就还会走
        // SWB 的本地 interactAt 分支。这里纯当闸门（只 cancel）。
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player player = event.getEntity();
        ItemStack stack = player.getItemInHand(event.getHand());
        if (!(stack.getItem() instanceof RideBatonItem)) {
            return;
        }
        event.setCanceled(true);
    }

    /**
     * 【实测七百二十一·点2 补闸】独占档改在"登乘那一道"上落实——完整口径（玩家原话、
     * 720 那两道闸为什么拦不住卓越前线的载具、为什么 {@code EntityMountEvent} 是唯一收口、
     * 为什么只拦"玩家本人"）见 1.20.1 树同名方法的注释。
     *
     * <p>名字换成官方名；NeoForge 的 {@code EntityMountEvent} 实现 {@code ICancellableEvent}
     * （javap 实证），{@code setCanceled()} 同样让 {@code EventHooks.canMountEntity} 返回 false
     * （反编译实证：它读 {@code isCanceled()} 后先把玩家摆回原位再 return 0）。
     */
    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        try {
            if (!isEnabled() || !batonExclusive()) {
                return;
            }
            if (!event.isMounting()) {
                return;
            }
            if (!(event.getEntityMounting() instanceof Player player)) {
                return;
            }
            if (!holdsBaton(player)) {
                return;
            }
            event.setCanceled(true);
            PlayerMountLog.throttled(player);
        } catch (Throwable ignored) {
        }
    }

    /** 玩家（主手或副手）拿着骑乘指挥棒吗。 */
    private static boolean holdsBaton(Player player) {
        try {
            return player.getMainHandItem().getItem() instanceof RideBatonItem
                    || player.getOffhandItem().getItem() instanceof RideBatonItem;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 【实测七百二十二】独占档的**唯一收口**：{@code Entity.startRiding} 的最前面。
     *
     * <p>完整口径（为什么 720 的右击事件、721 的 {@code EntityMountEvent} 两层都不够——
     * 后者是"先同意上车再撤销"，那一刻玩家眼前已经坐上去过了；实机日志 16:07:10.572/573
     * 两行同时出现即证据）见 1.21.1 树 {@code EntityBatonMountGateMixin} 的类注释。
     *
     * <p>由 mixin 调用：返回 true = 这一下不许上车。
     */
    public static boolean denyMountForBatonHolder(Player player, Entity vehicle) {
        try {
            if (player == null || !isEnabled()) {
                return false;
            }
            // 【实测七百二十三】被"悬空鞍位"占着的龙：**谁都不许骑上去**（玩家原话
            // 「阻止一下右击骑龙的行为」）。这一条不看手里拿什么——降级方案里女仆就挂在
            // 玩家鞍位上，玩家再骑上去会跟她重叠、龙也会立刻把她当"非控制乘客"走猎物分支。
            if (chairRiderOf(vehicle) != null) {
                PlayerMountLog.throttled(player);
                return true;
            }
            if (!batonExclusive()) {
                return false;
            }
            if (!holdsBaton(player)) {
                return false;
            }
            // 【边界】武装拴绳那条链路是"玩家挂到女仆/扫帚上"，主副手同时拿着指挥棒与拴绳时
            // 不该被这一档误伤 —— 指挥棒独占的语义是"骑不上龙/车子"，不是"挂不上自己的女仆"。
            if (vehicle instanceof EntityMaid || MaidRideKit.isBroom(vehicle)) {
                return false;
            }
            PlayerMountLog.throttled(player);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 独占档拦住登乘的留痕（只记服务端；节流 3 秒一位玩家，免得刷屏）。 */
    private static final class PlayerMountLog {
        private static final Map<UUID, Long> AT = new HashMap<>();

        static void throttled(Player player) {
            try {
                if (!(player instanceof ServerPlayer sp)) {
                    return; // 客户端那一份不记（同一件事服务端会记一次）
                }
                long now = System.currentTimeMillis();
                UUID id = sp.getUUID();
                Long last = AT.get(id);
                if (last != null && now - last < DENY_INTERVAL_MS) {
                    return;
                }
                if (AT.size() > 512) {
                    AT.clear();
                }
                AT.put(id, now);
                com.maidsmart.tool.PromaidLog.log("骑乘指挥棒", name(sp)
                        + " 手里拿着指挥棒 → 不登乘（独占右击：这一下只归棍子）");
            } catch (Throwable ignored) {
            }
        }
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
                //
                // 【实测七百二十七·点2】判据再放宽到 {@link #isOurRider}：**悬空鞍位（冰火传说的龙）
                // 那条她不是乘客**，{@code MaidRideKit.isRideRider} 要求 {@code getVehicle() != null}
                // → 对龙恒为 false，于是"右击她"落到了下面那句「先用骑乘棒右击坐骑」，她根本下不来。
                // 玩家原话：「女仆如果坐到了龙上……除非把龙收起来，否则女仆是下不来的。这边建议将它
                // 改成跟载具一样的，直接拿着骑乘指挥棒右击就可以让他下来。」所以这里跟载具同口径。
                if (isOurRider(m)) {
                    releaseMaid(m, false, "再选一次");
                    return;
                }
                Entity mount = takePendingMount(player);
                if (mount != null) {
                    bind(player, m, mount);
                    return;
                }
                if (MaidBroomKit.isRidingBroom(m) || MaidBroomKit.isBroomTask(m)) {
                    deny(player, m, "我处于扫帚模式");
                    return;
                }
                // 【实测七百二十六·点5】绑定顺序**强制"先瞄载具、再瞄女仆"**。玩家原话：
                // 「如果先用骑乘棒绑定女仆再绑定载具是无效的。必须要先绑定载具再绑定女仆。
                // 也罢，咱们干脆就堵死了。描述以及代码上都要求必须要先瞄载具再瞄女仆。」
                // 旧版这里给女仆挂"待选"光标、等玩家再指坐骑（那条"先选女仆"的链路实机里
                // 不可靠）；现在直接拒绝并明说该怎么做——**不再给女仆挂待选**，于是
                // "女仆先"这条路彻底不存在，只剩"先坐骑"一条。
                deny(player, m, "先用骑乘棒右击坐骑，再来右击我～");
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
            // 【实测七百二十三】龙被"悬空鞍位"占着：玩家原话「此时玩家对龙进行右击会显示
            // 已经占用了。」——她不是乘客，按乘客找找不到她；这里按链路表 + 龙直接认出来。
            //
            // 【实测七百二十七·点2】改成与载具同口径：**自己的**女仆挂在上面 → 直接右击就让她
            // 下来（旧版只回一句"潜行右击可以让我下来"，而那条路实际走不通，玩家等于下不来）。
            // 不是自己的 → 才提示"已经有女仆了"。
            EntityMaid chairRider = chairRiderOf(target);
            if (chairRider != null) {
                if (ownable(player, chairRider)) {
                    releaseMaid(chairRider, false, "再选一次");
                } else {
                    deny(player, chairRider, "这条龙的背上已经有女仆了～");
                }
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
        // 【实测七百二十七·点2】目标是她自己、或她驮的坐骑、或她挂的龙（悬空鞍位）。
        // 龙那条她**不是乘客**，{@code riderOf} 找不到她 —— 必须补 {@link #chairRiderOf}。
        EntityMaid m = target instanceof EntityMaid mm ? mm : MaidRideKit.riderOf(target);
        if (m == null) {
            m = chairRiderOf(target);
        }
        if (m == null) {
            player.displayClientMessage(Component.literal("\u00a77这只坐骑背上没有我的女仆"), false);
            return;
        }
        if (!ownable(player, m)) {
            deny(player, m, "她不是我的女仆～");
            return;
        }
        // 实测七百一十八·点4 + 实测七百二十七·点2：只有"骑乘棒绑上去的"才由我们负责弄下来。
        // 判据用 {@link #isOurRider}（乘客档 + 悬空鞍位档，龙那条她也算）。
        if (!isOurRider(m)) {
            player.displayClientMessage(Component.literal(
                    "\u00a77她不是用骑乘指挥棒绑上去的，我不去动她"), false);
            return;
        }
        releaseMaid(m, false, "潜行下鞍");
    }

    /**
     * 【实测七百二十七·点2】她是不是"我们骑乘指挥棒绑上去的那位"——**两条路都要认**：
     * <ul>
     *   <li><b>乘客档</b>（原版兽 / 卓越前线载具）：{@link MaidRideKit#isRideRider}（它要求
     *       她确实是乘客，见那个方法的说明）；</li>
     *   <li><b>悬空鞍位档</b>（冰火传说的龙）：她**不是乘客**，判据只能看链路表里那条
     *       {@code chair} 标记（{@link #isDragonChairRider}）。</li>
     * </ul>
     *
     * <p>【为什么必须补第二条】玩家原话：「女仆如果坐到了龙上，虽然明面上系统消息写的是
     * shift+右击让女仆下来。但实际上没用，导致除非把龙收起来，否则女仆是下不来的。这边建议
     * 将它改成跟载具一样的，直接拿着骑乘指挥棒右击就可以让他下来。」——旧版这条链路每一处
     * 都只问 {@code isRideRider}（对龙恒 false），所以她一旦上了龙就下不来。
     */
    private static boolean isOurRider(EntityMaid maid) {
        try {
            return MaidRideKit.isRideRider(maid) || isDragonChairRider(maid);
        } catch (Throwable ignored) {
            return false;
        }
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
        // 【实测七百二十四·点3：绑定语义修正】旧版这里有一句
        //   {@code releaseOtherLinks(player, maid)}——"一位玩家名下只留一条骑乘链路"，于是
        //   玩家给甲+载具A 绑好之后，再去给乙+载具B 配对，**甲会被静默放掉**（实机日志实证：
        //   22:08:48 绑甲(TRACK) → 22:08:57 绑乙(同一辆 TRACK)，中间没有任何"换绑"日志）。
        //   玩家原话：「已经达成绑定乘坐载具A的甲女仆，并不会因为玩家去绑定乙女仆乘坐B载具而解绑。」
        //   所以整条"玩家级独占"删除——甲+载具A 完全不受乙+载具B 影响。
        //   这里只保留"她自己再绑一次 = 换绑"那条自解绑（下面的 containsKey 判断）。
        if (LINKS.containsKey(maid.getUUID())) {
            releaseMaid(maid, false, "换绑");
        }
        // 【实测七百二十四·点3】同一辆坐骑只服务一位女仆：车上已有**另一位**女仆 → 拒绝乙、
        // **绝不碰甲**（旧版是 releaseMountLinks 把甲拆掉）。龙走悬空鞍位（不是乘客），
        // 用 chairRiderOf；普通坐骑/载具走 riderOf。
        EntityMaid occupant = chairRiderOf(mount);
        if (occupant == null) {
            occupant = MaidRideKit.riderOf(mount);
        }
        if (occupant != null && occupant != maid) {
            deny(player, maid, ownable(player, occupant)
                    ? "这只坐骑已经有我的女仆了～"
                    : "这只坐骑已经有女仆了～");
            return;
        }
        // 【实测七百二十五·点1】绑上就是坐姿：她的**可见坐姿**只由 TLM 的坐姿标志
        // ({@code isMaidInSittingPose}) 决定（bedrock JS 与 gecko molang 两条渲染路径都问它，
        // 反编译实证），而骑载具/龙这条路没人替她置位 → 她一直用站立模型。
        // 记下绑前的值，解绑时原样还原。
        boolean prevSitting = MaidMountCompat.isSitting(maid);
        // 【实测七百二十三】冰火传说的龙走**降级方案**：不 startRiding（她不是乘客），
        // 改成"每 tick 由我们把她摆到龙的玩家鞍位上 + 无重力 + 龙置跟随档"。
        // 完整口径见 MaidMountCompat 那一节的类注释（为什么真骑这条路走不通）。
        boolean chair = MaidMountCompat.isDragon(mount);
        int prevCmd = chair ? MaidMountCompat.dragonCommand(mount) : -1;
        if (chair) {
            // ① 摆位 + 无重力（她不是乘客，原版没有东西托着她）
            MaidMountCompat.setGravity(maid, false);
            MaidMountCompat.setSitting(maid, true); // 点1：先坐姿，落点才按坐姿算
            if (!MaidMountCompat.seatOnDragon(mount, maid)) {
                MaidMountCompat.setGravity(maid, true); // 摆不上就还原，别让她飘着
                MaidMountCompat.setSitting(maid, prevSitting);
                deny(player, maid, "没能坐上它的鞍位……再试一次？");
                return;
            }
            // ② 龙的行动逻辑转为"跟随玩家"（原版语义：command=2 → DragonAIEscortGoal 跟主人）
            MaidMountCompat.setDragonFollow(mount);
        } else {
            boolean ok = false;
            try {
                ok = maid.startRiding(mount, true);
            } catch (Throwable ignored) {
            }
            if (!ok) {
                deny(player, maid, "没能坐上去……再试一次？");
                return;
            }
            MaidMountCompat.setSitting(maid, true); // 点1：骑上去就是坐姿
            // v1.3.0(beta) 实测七百一十九：卓越前线的引擎只认**座位 0**（getFirstPassenger）——
            // 女仆若被排到别的座位（驾驶位已被玩家占了）她能开火却开不动，这里把她挪回座位 0。
            try {
                if (!MaidMountCompat.ensureDriverSeat(mount, maid)) {
                    com.maidsmart.tool.PromaidLog.log("骑乘指挥棒", "座位修正失败："
                            + com.maidsmart.tool.PromaidLog.nameOf(maid) + " 不在驾驶位（下一拍重试）");
                }
            } catch (Throwable ignored) {
            }
        }
        LINKS.put(maid.getUUID(), new Link(maid, mount, player, chair, prevCmd, prevSitting));
        try {
            maid.getPersistentData().putString(TAG_RIDE_MOUNT, maid.getUUID() + "|"
                    + com.maidsmart.tool.PromaidLog.nameOf(maid) + "|" + mount.getUUID());
        } catch (Throwable ignored) {
        }
        // 【实测七百二十九·点2·必补】坐骑侧也要留痕——**旧版从来没有写过它**（全树只有 remove、
        // 没有 put）。后果不只是"留痕白写"：重启后 {@link #restore} 只认"她还在马/车上"这一条
        // （{@code maid.getVehicle() != null}），而**龙那条她不是乘客，getVehicle() 恒为 null**
        // → 那条龙**重建不出链路，也就永远没人去撤它的发光标记**（玩家原话：「如果一只龙被绑定了
        // 以后并且上了光标，那就再也没有办法解除他身上的光标了」——这是根因）。写上去之后，
        // {@link #sweepStaleMarks} 才有"这只坐骑曾经被我们标记过"这个凭据，才能在链路丢失时
        // 兜底熄灯；{@link #restore} 也能靠它把龙的链路一起重建出来（见那里）。
        try {
            mount.getPersistentData().putString(TAG_RIDE_MAID, mount.getUUID() + "|"
                    + com.maidsmart.tool.PromaidLog.nameOf(maid) + "|" + maid.getUUID());
        } catch (Throwable ignored) {
        }
        mark(maid);
        mark(mount);
        // 【实测七百二十七·点4】悬空鞍位（龙）那条：把"她挂在哪条龙上"同步给客户端，
        // 客户端才能按同一个算式与她同帧摆位（见 MaidSeatNetworking 与 onClientEntityTickPost）。
        if (chair) {
            syncSeat(maid, mount.getId());
        }
        bubble(maid, chair ? "我坐它背上啦，它跟着你走～" : "坐稳啦，我们出发～");
        com.maidsmart.tool.PromaidLog.log("骑乘指挥棒", "绑定：主人=" + name(player)
                + " 女仆=" + com.maidsmart.tool.PromaidLog.nameOf(maid)
                + " 坐骑=" + MaidRideKit.describe(mount)
                + (chair ? "（悬空鞍位降级方案：不真骑，龙置跟随档，传送只传她）" : "")
                + "（速度倍率=" + MaidRideKit.fmt(MaidRideKit.speedModifierFor(mount, maid))
                + "，跟随距离=" + MaidRideKit.fmt(MaidRideKit.followDist()) + " 格）");
    }

    /** 解除：清表 + 下鞍 + 撤标记（包内用） */
    static void releaseMaid(EntityMaid maid, boolean natural, String why) {
        releaseMaidImpl(maid, natural, why);
    }

    /**
     * 【实测七百二十三】给传送链路用的公开入口：跨维度跟随要先把"悬空鞍位"那条链路干净解除
     * （还原她的重力与龙的行动档），再单独传她一个人。与包内 {@code releaseMaid} 同一实现。
     */
    public static void releaseDragonChairForTravel(EntityMaid maid) {
        releaseMaidImpl(maid, false, "跨维度跟随");
    }

    private static void releaseMaidImpl(EntityMaid maid, boolean natural, String why) {
        if (maid == null) {
            return;
        }
        Link link = LINKS.remove(maid.getUUID());
        // 【实测七百二十六·点3】下鞍 = 空战这一场遭遇结束：清掉爬升相位/盘旋高度（与扫帚 clearClimb
        // 同口径）。不清的话她下次再骑上去会带着上一场的盘旋高度/方位角。
        MaidAirCombat.clear(maid);
        Entity mount = link == null ? null : link.mount.get();
        // 【实测七百二十五·点1】解绑还原坐姿（绑前是站姿就还原站姿）——玩家的原版"坐下"指令 /
        // 手动让她坐下，都不会被我们吃掉。
        MaidMountCompat.setSitting(maid, link != null && link.prevSitting);
        // 【实测七百二十三】悬空鞍位（龙）那条：还原重力 + 还原龙的行动档。
        // 她压根不是乘客，所以下面那些"下鞍"动作对她无意义。
        if (link != null && link.chair) {
            MaidMountCompat.setGravity(maid, true);
            MaidMountCompat.restoreDragonCommand(mount, link.dragonCommand);
            // 【实测七百二十七·点4】通知客户端解除镜像（否则客户端还在按旧配对摆位）
            syncSeat(maid, -1);
        }
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
        // 【实测七百二十九·点2】记下开始时刻——超时清理要用（见 PENDING_AT 的说明）。
        PENDING_AT.put(player.getUUID(), System.currentTimeMillis());
        markPending(e);
    }

    private static Entity takePending(ServerPlayer player, boolean wantMaid) {
        java.lang.ref.WeakReference<Entity> ref = PENDING.remove(player.getUUID());
        PENDING_AT.remove(player.getUUID());
        Entity e = ref == null ? null : ref.get();
        if (e == null || !e.isAlive() || e.level() != player.level()) {
            // 【实测七百二十九·点2】目标已经没了/换了维度：这一下右击等于白点，**必须把光标撤掉**。
            // 旧版这里直接 return null，那条实体身上的"待选发光"就永远留在它身上了（玩家原话：
            // 「再也没有办法解除他身上的光标了」——这是其中一条泄漏路径）。
            clearPendingMark(e);
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
        PENDING_AT.remove(playerId); // 实测七百二十九·点2：时刻表一并清，免得残留旧时间戳
        if (ref != null) {
            clearPendingMark(ref.get()); // 点3：下线时撤掉"待选光标"
        }
        DENY_LOG.remove(playerId);
    }

    public static void forgetMaid(UUID maidId) {
        Link link = LINKS.remove(maidId);
        if (link != null) {
            // 【实测七百二十九·点2】解绑必须连**坐骑侧的光标**一起撤（玩家原话：「在解除女仆
            // 乘坐在坐骑上的情况以后就顺便解除龙身上的光标」）。旧版这里只 unmark 了，
            // 看着对——但 unmark 走的是 {@code setGlowingTag(false)}，而那条龙如果**同时**还挂着
            // "待选光标"（先点龙、再点女仆配对成功时，takePending 已清待选，所以正常不重叠），
            // 或者存档里残留过一条别的链路留下的留痕，就会熄不干净。这里顺手把坐骑侧留痕也清掉，
            // 与 releaseMaidImpl 完全同口径；真正兜底的是 sweepStaleMarks。
            unmark(link.maid.get());
            unmark(link.mount.get());
            Entity mount = link.mount.get();
            if (mount != null) {
                try {
                    mount.getPersistentData().remove(TAG_RIDE_MAID);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 点4：她是不是"被棍子绑上坐骑"的骑乘女仆（连坐骑一起搬运的判据）——给传送链路问 */
    public static boolean isRideRider(EntityMaid maid) {
        return MaidRideKit.isRideRider(maid);
    }

    /**
     * 【实测七百二十三】她是不是"坐在龙的悬空鞍位上"（**不是乘客**，由本类每拍摆位）。
     * 传送链路据此分叉：**只传她不传龙**（玩家原话「玩家手动使用日程表进行传送那也仅仅是
     * 传送女仆不传送龙」）。与 {@link #isRideRider} 的区别正是"她是不是真乘客"。
     */
    public static boolean isDragonChairRider(EntityMaid maid) {
        try {
            if (maid == null) {
                return false;
            }
            Link link = LINKS.get(maid.getUUID());
            return link != null && link.chair;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * v1.3.0(beta) 实测七百二十六·点1【"特殊载具"= 卓越前线的载具 + 冰火传说的龙】。
     *
     * <h2>玩家原话</h2>
     * 「对于卓越前线以及龙这两个特殊的载具……不会触发大部分传送，只有玩家手动使用排班表
     *  进行的传送，可以将她们传送过来。不会连着载具一起传送。只会把人传送过来。并且一旦进行
     *  传送了，那么就会立刻进行一次清除标记和解绑。也就是说，相比于原版的坐骑女仆并不会去
     *  主动产生传送。所有的传送方面的行为必须由玩家来。」
     *
     * <p>所以这一档的语义与**普通原版坐骑**（马/猪/骆驼，{@link MaidRideKit#isRideRider}）**故意不同**：
     * <ul>
     *   <li><b>普通坐骑</b>：保留旧口径——自动/手动传送都"连人带坐骑一起搬"（玩家 716 点名要的）。</li>
     *   <li><b>特殊载具（本档）</b>：**一切自动传送一律不生效**（跨维跟随 / 同维远距拉回 /
     *       危险撤离 / 主人死亡归位 / TLM 原生 teleportToOwner 全部让位）；**只有玩家手动**的
     *       排班表传送（「传送到我身边」/「一键集合」）能把人传过来，且**只传人不传载具**，
     *       传送的同时**解除绑定、清掉光标标记**。</li>
     * </ul>
     *
     * <p>判据同时认"链路表里的 chair（龙）"与"她骑的是卓越前线载具（{@code kindOf == VEHICLE}）"，
     * 并对"服务端重启后链路表还没重建"的那一拍兜底（按 {@code TAG_RIDE_MOUNT} 痕迹 + 当前载具类型认）。
     */
    public static boolean isSpecialMountRider(EntityMaid maid) {
        try {
            if (maid == null) {
                return false;
            }
            Link link = LINKS.get(maid.getUUID());
            if (link != null && link.chair) {
                return true; // 冰火传说的龙（悬空鞍位）
            }
            // 卓越前线的载具：她一定是乘客（走 startRiding）；链路表未重建的那一拍按痕迹 + 类型认
            Entity v = maid.getVehicle();
            if (v != null && MaidMountCompat.kindOf(v) == MaidMountCompat.Kind.VEHICLE) {
                if (link != null || MaidRideKit.isBatonBound(maid)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 【实测七百二十六·点1】玩家**手动**排班表传送特殊载具上的女仆时调用：先干净解除绑定
     * （还原坐姿 / 重力 / 龙的行动档 + 撤掉金色标记 + 清 persistentData 痕迹），再让她被单独
     * 传送过去（载具留在原地）。玩家原话「不会连着载具一起传送。只会把人传送过来。并且一旦
     * 进行传送了，那么就会立刻进行一次清除标记和解绑。」
     *
     * <p>走的实现与 {@link #releaseMaidImpl} 同一支（{@code natural = true}：不播"我自己走"的
     * 气泡——她是被玩家召回的，不是自己下鞍）。她若还是乘客（载具那条），解除里会
     * {@code stopRiding()}；龙那条会还原重力与 {@code command}。
     */
    public static void detachForSpecialTeleport(EntityMaid maid) {
        releaseMaidImpl(maid, true, "手动传送（只传人，解除绑定）");
    }


    /** 这条坐骑背上"悬空鞍位"占着的那只女仆（给"右击龙提示已占用"用）；没有 → null。 */
    public static EntityMaid chairRiderOf(Entity mount) {
        try {
            if (mount == null) {
                return null;
            }
            for (Map.Entry<UUID, Link> e : LINKS.entrySet()) {
                Link link = e.getValue();
                if (!link.chair) {
                    continue;
                }
                Entity m = link.mount.get();
                if (m != null && m.getUUID().equals(mount.getUUID())) {
                    EntityMaid maid = link.maid.get();
                    if (maid != null && maid.isAlive()) {
                        return maid;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /* ==================== 每 2 tick：驱动 + 校验 + 恢复 ==================== */

    /**
     * 【实测七百二十四】悬空鞍位（龙）的**每 tick** 摆位。
     *
     * <p>为什么单开这一路：723 的摆位只挂在 {@link #tick} 里（每 2 tick 一次），她跟不上龙——
     * 龙每拍在动，她两拍才被纠正一次，玩家看到的就是"坐的位置时不时错一下"。而且她是**普通实体**，
     * 大脑/寻路每拍都可能把她往外挪。所以这里照抄 {@code GunnerTetherManager.onMaidTick} 的做法
     * （TLM 的 {@code MaidTickEvent}，每 tick、且在 {@code super.tick()} 之前），
     * 每拍：无重力 + 停导航/清速度（{@link MaidMountCompat#freezeOnSeat}）+ 摆回鞍位。
     * {@link #tick} 里那条 2 拍摆位保留作兜底（链路校验/自愈仍在那一档）。
     */
    @SubscribeEvent
    public static void onMaidTick(com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent event) {
        try {
            if (!isEnabled()) {
                return;
            }
            EntityMaid maid = event.getMaid();
            if (maid == null || maid.level().isClientSide()) {
                return; // 客户端那半不摆位（链路表只活在服务端）
            }
            Link link = LINKS.get(maid.getUUID());
            if (link == null || !link.chair) {
                return;
            }
            Entity mount = link.mount.get();
            if (mount == null || !mount.isAlive() || mount.level() != maid.level()) {
                return;
            }
            MaidMountCompat.setGravity(maid, false);
            MaidMountCompat.freezeOnSeat(maid);
            MaidMountCompat.seatOnDragon(mount, maid);
        } catch (Throwable ignored) {
        }
    }

    /**
     * v1.3.0(beta) 实测七百二十六·点2【龙每拍摆位补在**龙自己 tick 之后**——治"她换位置的速度
     * 赶不上龙飞行的速度"】。
     *
     * <h2>玩家原话</h2>
     * 「目前女仆跟龙之间还是有一点点小小的错位，就是女仆换位置的速度赶不上龙飞行的速度。」
     *
     * <h2>根因（服务端 tick 顺序，javap 实证）</h2>
     * {@code ServerLevel.tickNonPassenger(e)} 的顺序是
     * {@code setOldPosAndRot → fireEntityTickPre → e.tick() → fireEntityTickPost → 摆乘客}，
     * 而**同一 tick 内龙与女仆谁先 tick 由实体 ID 决定**。{@link #onMaidTick} 挂在
     * {@code MaidTickEvent}（在她**自己的** {@code tick()} 里），所以：
     * <ul>
     *   <li>龙**先** tick：她 tick 时拿到的是龙的新位置 → 摆位正确；</li>
     *   <li>她**先** tick：她按龙的**旧**位置摆好，随后这一 tick 龙才飞到新位置 →
     *       她整整落后龙一帧（每拍 0.5~1 格，飞起来就是"明显的错位"）。</li>
     * </ul>
     * 也就是说 {@link #onMaidTick} 是"在**她**的 tick 里摆"，而真正稳的做法是"在**龙的**
     * tick 里摆"——不管谁先谁后，{@code EntityTickEvent.Post} 对**两者**都发，谁后 tick 谁的
     * Post 就是这一帧的最后一次摆位，天然收敛到"她的位置 = 龙这一帧的最终鞍位"。
     *
     * <h2>为什么用 Post 而不是 Pre</h2>
     * Post 在 {@code e.tick()} **返回之后**发（javap 实证），龙的 {@code setPos} 已经落地——这正是
     * 我们要的那一帧的最终位置。Pre 会拿到上一帧的位置，等于没修。
     *
     * <h2>成本</h2>
     * {@code EntityTickEvent.Post} 对**每个实体每 tick** 都发，所以这里两道最便宜的守卫排在最前：
     * ① 她（{@code EntityMaid}）或 ② 龙（{@link MaidMountCompat#isDragon}）——其余实体一次
     * 方法调用就返回；真正的摆位只在"确实是悬空鞍位链路里的那两只之一"时才做。
     */
    @SubscribeEvent
    public static void onEntityTickPost(net.neoforged.neoforge.event.tick.EntityTickEvent.Post event) {
        try {
            Entity e = event.getEntity();
            if (e == null) {
                return;
            }
            // 【实测七百二十七·点4】客户端这一支：**悬空鞍位（龙）**那条她不是乘客、又没人摆位，
            // 位置只能靠原版限流位置包 + 客户端 lerp 插值 → 龙一飞就"位置严重改变和错乱"。
            // 这里用服务端同步过来的配对（{@link #SYNCED_CHAIRS}）在客户端把她按**同一个算式**
            // 摆到龙的当前（插值后）位置上，与服务端逐字同源、且与龙同帧。见 MaidSeatNetworking。
            if (e.level().isClientSide()) {
                onClientEntityTickPost(e);
                return;
            }
            if (!isEnabled() || LINKS.isEmpty()) {
                return;
            }
            // ① 她本人 tick 完 → 按龙的当前（可能刚更新）位置摆一次
            if (e instanceof EntityMaid maid) {
                Link link = LINKS.get(maid.getUUID());
                if (link == null || !link.chair) {
                    return;
                }
                Entity mount = link.mount.get();
                if (mount == null || !mount.isAlive() || mount.level() != maid.level()) {
                    return;
                }
                MaidMountCompat.setGravity(maid, false);
                MaidMountCompat.freezeOnSeat(maid);
                MaidMountCompat.seatOnDragon(mount, maid);
                return;
            }
            // ② 龙 tick 完 → 把挂在它鞍位上的她摆过来（这一帧的最后一次摆位）
            if (MaidMountCompat.isDragon(e)) {
                EntityMaid rider = chairRiderOf(e);
                if (rider == null || rider.level() != e.level()) {
                    return;
                }
                MaidMountCompat.setGravity(rider, false);
                MaidMountCompat.freezeOnSeat(rider);
                MaidMountCompat.seatOnDragon(e, rider);
            }
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 实测七百二十七·点4：悬空鞍位（龙）的客户端同行 ==================== */

    /**
     * 客户端那一半的摆位（{@link #onEntityTickPost} 在 {@code isClientSide()} 时转进来）。
     *
     * <p>【为什么必须有这一支】服务端的每拍摆位跑在服务端；客户端**没有** {@link #LINKS}
     * （那是服务端状态），所以旧版客户端的她 = "不是乘客 + 没人摆位" = 完全由原版位置包与
     * 客户端自身插值决定。而原版位置包**限流**（{@code ServerEntity} 每 2 tick 一次），她又
     * 每拍被服务端拽回鞍位——这种"每拍小位移 + 限流"的组合，客户端插值最容易出偏差；
     * 龙**是**每帧插值渲染的原版实体 → 两条插值曲线不同步 → 玩家看到的"一飞就错乱"。
     *
     * <p>现在客户端按服务端同步的配对（{@link #SYNCED_CHAIRS}），用**同一个**
     * {@link MaidMountCompat#seatOnDragon} 从"龙的当前渲染位置"重算鞍位——客户端不再依赖
     * 位置包，两者同帧。龙 / 她 tick 完各摆一次（谁后 tick 谁说了算，与服务端同口径）。
     */
    private static void onClientEntityTickPost(Entity e) {
        try {
            if (!isEnabled() || SYNCED_CHAIRS.isEmpty()) {
                return;
            }
            if (e instanceof EntityMaid maid) {
                Integer mountId = SYNCED_CHAIRS.get(maid.getId());
                if (mountId == null) {
                    return;
                }
                Entity mount = maid.level().getEntity(mountId);
                if (mount == null || !mount.isAlive() || mount.level() != maid.level()) {
                    return;
                }
                MaidMountCompat.setGravity(maid, false);
                MaidMountCompat.freezeOnSeat(maid);
                MaidMountCompat.seatOnDragon(mount, maid);
                return;
            }
            if (MaidMountCompat.isDragon(e)) {
                Integer maidId = null;
                for (Map.Entry<Integer, Integer> en : SYNCED_CHAIRS.entrySet()) {
                    if (en.getValue() != null && en.getValue() == e.getId()) {
                        maidId = en.getKey();
                        break;
                    }
                }
                if (maidId == null) {
                    return;
                }
                Entity rider = e.level().getEntity(maidId);
                if (!(rider instanceof EntityMaid maid) || maid.level() != e.level()) {
                    return;
                }
                MaidMountCompat.setGravity(maid, false);
                MaidMountCompat.freezeOnSeat(maid);
                MaidMountCompat.seatOnDragon(e, maid);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 服务端：把"她挂在哪条龙上"同步给客户端（{@link MaidSeatNetworking}）。{@code mountId < 0} = 解除。 */
    private static void syncSeat(EntityMaid maid, int mountId) {
        try {
            if (maid == null) {
                return;
            }
            MaidSeatNetworking.send(maid, mountId);
            // 留痕（每只女仆 5 秒一条上限）：排查"龙一飞就错乱"时先看这一行有没有写出来
            // ——有 = 配对已同步给客户端；日志搜「骑行同步」。
            logSeatSync(maid, mountId);
        } catch (Throwable ignored) {
        }
    }

    /** 配对同步的留痕节流表（与其它几条日志互不顶掉）。 */
    private static final Map<UUID, Long> SEAT_LOG_AT = new HashMap<>();

    private static void logSeatSync(EntityMaid maid, int mountId) {
        try {
            long now = System.currentTimeMillis();
            Long last = SEAT_LOG_AT.get(maid.getUUID());
            if (last != null && now - last < 5000L) {
                return;
            }
            if (SEAT_LOG_AT.size() > 256) {
                SEAT_LOG_AT.clear();
            }
            SEAT_LOG_AT.put(maid.getUUID(), now);
            com.maidsmart.tool.PromaidLog.log("骑行同步", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + (mountId < 0 ? " 悬空鞍位配对解除（客户端停止同行摆位）"
                            : " 悬空鞍位配对已同步给客户端（龙 id=" + mountId + "，客户端按同一算式同行）"));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 客户端收到配对同步：记下/清掉镜像表。由 {@link MaidSeatNetworking.SyncPacket} 调用
     * （两侧都会加载本类，所以这里只碰纯数据）。
     */
    public static void onSeatSync(int maidId, int mountId) {
        try {
            if (mountId < 0) {
                SYNCED_CHAIRS.remove(maidId);
            } else {
                SYNCED_CHAIRS.put(maidId, mountId);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 客户端退出世界清空（{@code PromaidClientSetup} 的登出钩子调）。 */
    public static void clearSyncedSeats() {
        try {
            SYNCED_CHAIRS.clear();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 晚进服 / 传过来的玩家开始追踪她时补发当前配对（同 {@code GunnerTetherManager.onStartTracking}）。
     */
    @SubscribeEvent
    public static void onStartTracking(
            net.neoforged.neoforge.event.entity.player.PlayerEvent.StartTracking event) {
        try {
            if (!(event.getTarget() instanceof EntityMaid maid)) {
                return;
            }
            if (!(event.getEntity() instanceof ServerPlayer watcher)) {
                return;
            }
            Link link = LINKS.get(maid.getUUID());
            Entity mount = link == null ? null : link.mount.get();
            if (link != null && link.chair && mount != null) {
                MaidSeatNetworking.sendTo(watcher, maid.getId(), mount.getId());
            } else {
                MaidSeatNetworking.sendTo(watcher, maid.getId(), -1);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void tick(MinecraftServer server) {
        if (!isEnabled()) {
            return;
        }
        if (++tickTimer < TICK_DIV) {
            return;
        }
        tickTimer = 0;
        capTables();
        // 【实测七百二十九·点2】光标（发光标记）的两条清理：待选超时 + 死标记兜底扫描。
        // 放在最前——它们与链路驱动无关，且越早熄灯玩家越早看得见。
        sweepPendingTimeout();
        sweepStaleMarks(server);
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
                // 【实测七百二十三 / 七百二十四】悬空鞍位（龙）：她**不是乘客**，判据换成"由我们每拍摆位"。
                // 723 的旧版这里 gap > CHAIR_MAX_GAP 就**解除链路**——但解除走的是 releaseMaidQuiet，
                // 它不还原重力/龙的行动档（她永久无重力、龙永久卡跟随档），而且是"时不时断开"的来源：
                // TLM 原生 teleportToOwner / 救援传送把她拽离鞍位几格就够触发。724 起：
                // ① 距离只是"该拉回来"的信号（seatOnDragon 本来就是把她 setPos 回鞍位），不再解除；
                // ② 只有她/龙/主人真没了或跨维度才解除（上面那两道 already）。
                if (link.chair) {
                    MaidMountCompat.setGravity(maid, false); // 保底：被翻回去时纠回来
                    MaidMountCompat.freezeOnSeat(maid);      // 停她的导航/速度，别让她自己走出鞍位
                    MaidMountCompat.seatOnDragon(mount, maid);
                    // 龙自己那套飞行物理照常跑（我们只把它钉在跟随档）；攻击也不由我们触发。
                    continue;
                }
                if (maid.getVehicle() != mount) {
                    deferred.add(maid);
                    continue;
                }
                // 【实测七百二十五·点1】每拍复述坐姿：TLM 的任务里有几处会 her 清回站姿
                // （MaidBedTask/MaidJoyTask 等），一掉就变回站姿模型、看着又"站着骑"。
                // 与龙那一档的 freezeOnSeat 同口径，只是她这条是真乘客、不需要停导航。
                MaidMountCompat.setSitting(maid, true);
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
        MaidAirCombat.clear(maid); // 实测七百二十六·点3：自动解除同样作废这一场遭遇
        // 【实测七百三十】飞行坐骑的索敌器锁定也要跟着撤（与扫帚 stop() 里那一句同口径）
        com.maidsmart.combat.FlightTargeting.forget(maid.getUUID());
        LEASH_LOGGED.remove(maid.getUUID());
        Entity mount = link == null ? null : link.mount.get();
        // 【实测七百二十五·点1】自动解除同样要还原坐姿（与 releaseMaidImpl 同口径）。
        MaidMountCompat.setSitting(maid, link != null && link.prevSitting);
        // 【实测七百二十四】自动解除这条以前**不还原**重力/龙的行动档（只有 releaseMaidImpl 才还原）
        // → 她永久无重力飘着、龙永久卡跟随档。这里补齐，与 releaseMaidImpl 同口径。
        if (link != null && link.chair) {
            MaidMountCompat.setGravity(maid, true);
            MaidMountCompat.restoreDragonCommand(mount, link.dragonCommand);
            // 【实测七百二十七·点4】自动解除（链路失效）同样要通知客户端撤掉镜像
            syncSeat(maid, -1);
        }
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
            // v1.3.0(beta) 实测七百一十九【模组坐骑通解通法】：每拍把她的攻击目标交给坐骑——
            // 卓越前线载具走内置 Mob-乘客自动开火、冰火传说龙走吐息、**其余任何 Mob 坐骑**
            // 走通用兜底（无条件把她的 target 写下去，让它自己那套目标 AI 用它自己的攻击方式打）。
            // 实测七百一十九·点4 起这一档不再只对"模组坐骑"开——原版兽同样走一遍（它们没有
            // 目标 AI 时写了也无副作用），这样"别的模组的可骑乘战斗生物"零适配即可服从。
            MaidMountCompat.tickAttack(mount, maid);
            // 【实测七百二十六·点6】女仆自己往载具装弹：她背包里若有**这车当前武器对得上的**
            // 子弹，搬进车自己的弹药容器（车的枪弹是从车容器取的，见 MaidMountCompat.feedVehicleAmmo）。
            // 节流 0.5 秒一次——弹药消耗远慢于此，每 2 拍扫一遍背包是浪费。
            if (MaidMountCompat.kindOf(mount) == MaidMountCompat.Kind.VEHICLE) {
                feedAmmoThrottled(mount, maid);
            }
            double mod = MaidRideKit.speedModifierFor(mount, maid);
            // 【实测七百二十七·点1 + 七百二十八·玩家补正】飞行载具（卓越前线的直升机 /
            // 固定翼）分**两档高度**：
            //   ① **有敌人** → 升到**敌人上方 fightAlt 格**（默认 15）、绕着敌人盘旋射击；
            //      高度进的是目标点 Y（由总距升降），不是俯仰 —— 所以敌人比她在下很多也不会
            //      把机头压向地面（726 栽的那个坑）。玩家原话：「遇到敌人还是要升高到比敌人
            //      高 15 格的位置的呀，同时缩小绕圈的半径」。
            //   ② **没有敌人** → **低空跟随**：主人离得够远就把目标点放在主人正上方、
            //      高度锁在**离地 airAlt 格**（默认 3，玩家原话「跟随的时候保持离地三格」）；
            //      主人就在旁边则原地悬停。
            // 两条都把结果交给 driveFlight——它是唯一能写总距/悬停开关的地方。飞艇/地面车不在
            // 这一档里。
            if (MaidAirCombat.enabled() && MaidMountCompat.isFlyingVehicle(mount)) {
                // 【实测七百三十】锁敌改用**本模组自己的索敌器**（与扫帚/空袭同一套：
                // 发现 50 格 / 维持 128 格、无视排班盒子）——玩家原话「骑上飞行载具后它的
                // 锁敌范围应该跟扫帚模式是一样的」。旧版读 brain 的 ATTACK_TARGET，
                // 而 TLM 那条链按 16/8 格援护半径丢目标，boss 一飞高/一掉下去就没了。
                LivingEntity foe = FlightTargeting.resolve(maid);
                if (foe == null) {
                    foe = targetOf(maid); // 索敌器这一拍没结果 → 退回 brain（不丢已有目标）
                }
                // 【实测七百三十·点2】"发现攻击范围内没有主人"→ 放弃当前敌人、转去追主人。
                // 玩家原话：「打完了，或者发现攻击范围内没有主人，则放弃攻击敌人，转而去追主人。」
                // 参照半径直接复用索敌器的**发现半径**（{@link FlightTargeting#RANGE} = 50）：
                // 主人离得比"她还愿意接敌的距离"还远，就说明这一场不在主人身边打——收手回去。
                if (foe != null && maid.distanceTo(owner) > FlightTargeting.RANGE) {
                    if (LEASH_LOGGED.add(maid.getUUID())) {
                        MaidMountCompat.logDrive(mount, "主人超出接敌半径（"
                                + (long) maid.distanceTo(owner) + "格 > " + (long) FlightTargeting.RANGE
                                + "格）→ 放弃敌人、转去追主人");
                    }
                    foe = null;
                } else if (foe != null) {
                    LEASH_LOGGED.remove(maid.getUUID());
                }
                Vec3 air;
                if (foe != null && foe.isAlive() && foe.level() == maid.level()) {
                    air = MaidAirCombat.combatTarget(maid, foe);
                } else {
                    MaidAirCombat.clear(maid); // 没目标 → 这一场遭遇作废，下一场重新起手
                    // 低空跟随：主人够远就飞到他正上方（高度仍锁离地 N 格）；够近就原地悬停。
                    if (horizontalDist(mount, owner) > MaidRideKit.followDist()) {
                        air = MaidAirCombat.hoverAt(maid, owner.getX(), owner.getZ());
                    } else {
                        air = MaidAirCombat.followTarget(maid);
                    }
                }
                if (air != null) {
                    MaidRideKit.feedNavigation(mount, air, mod, maid);
                    // 朝向交给驾驶层：飞行档按"目标方位 vs 机头"写鼠标 X 通道（driveFlight 里）
                    return;
                }
            }
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
            // 【实测七百二十四·点2】停车距离按**车体尺寸**：坦克这种大车若还用旧的固定 1.5 格，
            // 等于"顶到主人身上才停"——玩家原话「会直接把主人撞倒。应与主人拉开距离才对」。
            // 模组载具用 stopSlackFor(车体半宽)；并与"跟随距离"取大（别比主人自己设的还近）。
            double slack = stopSlackFor(mount);
            if (horizontalDist(mount, target) <= slack) {
                MaidRideKit.stopNavigation(mount);
                return;
            }
            MaidRideKit.feedNavigation(mount, target, mod, maid);
            // 【实测七百二十四·点5】骑载具时更主动接敌：她此刻没目标就补一次索敌评估
            // （像骑马远程一样主动找目标），目标一有，下一秒 tickAttack 就会把炮塔 AI 目标写下去。
            if (mount instanceof net.minecraft.world.entity.Entity
                    && MaidMountCompat.kindOf(mount) == MaidMountCompat.Kind.VEHICLE
                    && targetOf(maid) == null) {
                tryEngageRider(maid);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【实测七百二十四·点2】停车距离（格）：普通坐骑沿用 {@code STOP_SLACK=1.5}；
     * 卓越前线载具（坦克/装甲车这类大车）用 {@code max(1.5, 车体半宽 + 1)}，
     * 再与主人的"跟随停下距离"取大——这样大车永远停在车体之外，不会把主人撞倒。
     */
    private static double stopSlackFor(Entity mount) {
        try {
            if (MaidMountCompat.kindOf(mount) == MaidMountCompat.Kind.VEHICLE) {
                return Math.max(MaidMountCompat.stopSlackFor(mount), MaidRideKit.followDist());
            }
        } catch (Throwable ignored) {
        }
        return STOP_SLACK;
    }

    /** 【实测七百三十】"主人超出接敌半径 → 收手追主人"这条日志的每只女仆一次闩（回来了就拔）。 */
    private static final java.util.Set<UUID> LEASH_LOGGED = new java.util.HashSet<>();

    /** 【实测七百二十四·点5】节流地给"骑载具的女仆"补一次主动索敌（避免每 2 tick 都扫）。 */
    private static final Map<UUID, Long> ENGAGE_AT = new HashMap<>();

    /** 【实测七百二十六·点6】装弹节流表：车 UUID → 下次可搬的时间（毫秒）。 */
    private static final Map<UUID, Long> AMMO_FEED_AT = new HashMap<>();
    /** 装弹检查间隔（毫秒）：0.5 秒——弹药消耗远慢于此，扫太勤是纯浪费。 */
    private static final long AMMO_FEED_INTERVAL_MS = 500L;

    private static void feedAmmoThrottled(Entity mount, EntityMaid maid) {
        try {
            long now = System.currentTimeMillis();
            Long last = AMMO_FEED_AT.get(mount.getUUID());
            if (last != null && now - last < AMMO_FEED_INTERVAL_MS) {
                return;
            }
            if (AMMO_FEED_AT.size() > 256) {
                AMMO_FEED_AT.clear();
            }
            AMMO_FEED_AT.put(mount.getUUID(), now);
            MaidMountCompat.feedVehicleAmmo(mount, maid);
        } catch (Throwable ignored) {
        }
    }

    private static void tryEngageRider(EntityMaid maid) {
        try {
            long now = System.currentTimeMillis();
            Long last = ENGAGE_AT.get(maid.getUUID());
            if (last != null && now - last < 1000L) {
                return; // 1 秒最多一次（索敌本身有开销，且战斗链路会自己维持目标）
            }
            if (ENGAGE_AT.size() > 256) {
                ENGAGE_AT.clear();
            }
            ENGAGE_AT.put(maid.getUUID(), now);
            com.maidsmart.combat.AutoCombatSwitch.tryEngagePublic(maid);
        } catch (Throwable ignored) {
        }
    }

    /** 女仆当前的攻击目标（brain 的 ATTACK_TARGET 优先，退回实体层 target）。 */
    private static LivingEntity targetOf(EntityMaid maid) {
        try {
            java.util.Optional<LivingEntity> mem = maid.getBrain().getMemory(
                    net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET);
            if (mem != null && mem.isPresent()) {
                LivingEntity le = mem.get();
                if (le != null && le.isAlive()) {
                    return le;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            return maid.getTarget();
        } catch (Throwable ignored) {
            return null;
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
                    // 【实测七百二十五·点1】重载后重建链路同样保持坐姿（与 bind 同口径；
                    // 这里她是乘客，绑前值无从得知，就用"当前值"当还原目标，最保守）。
                    LINKS.put(maid.getUUID(), new Link(maid, mount, sp, MaidMountCompat.isSitting(maid)));
                    MaidMountCompat.setSitting(maid, true);
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
            com.maidsmart.tool.StateTables.cap("骑乘.PENDING_AT", PENDING_AT); // 实测七百二十九·点2
            com.maidsmart.tool.StateTables.cap("骑乘.DENY_LOG", DENY_LOG);
            com.maidsmart.tool.StateTables.cap("骑乘.ENGAGE_AT", ENGAGE_AT); // 实测七百二十六·点6
            com.maidsmart.tool.StateTables.cap("骑乘.AMMO_FEED_AT", AMMO_FEED_AT); // 实测七百二十六·点6
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 实测七百二十九·点2：光标（发光标记）不再泄漏 ==================== */

    /**
     * 【实测七百二十九·点2】"点了坐骑却没配对"的**待选光标**超时（毫秒）——到点自动熄。
     *
     * <p>取 {@code 30000}（30 秒）：正常玩法里"点坐骑 → 再点女仆"是紧接着的两下（实测日志里
     * 相隔 1~2 秒），30 秒足够宽容；而它挡住的是"点了一下就走开"这类残留，那种情况下光标
     * 在 30 秒后消失，跟玩家"这根棍子这一下已经不算数了"的直觉一致。
     *
     * <p>【为什么必须加这一条】原版发光标记是**写进 NBT 的**（{@code TAG_PENDING_MARK} 也在
     * persistentData 里），跨存档、跨重启都在——没有超时就等于"点错一次、那条龙永远发光"。
     * 玩家原话：「如果一只龙被绑定了以后并且上了光标，那就再也没有办法解除他身上的光标了。
     * 这边建议在解除女仆乘坐在坐骑上的情况以后就顺便解除龙身上的光标。」
     */
    private static final long PENDING_TTL_MS = 30000L;

    /**
     * 【实测七百二十九·点2】扫掉过期的"待选光标"。
     *
     * <p>每个 2 tick 由 {@link #tick} 调一次。它治的是三件事里的"点了一下就走开"；
     * 另外两条泄漏路径（目标消失/换维度、解绑后坐骑侧没撤干净）分别在
     * {@link #takePending} 与 {@link #sweepStaleMarks} 里堵。
     */
    private static void sweepPendingTimeout() {
        try {
            if (PENDING.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<UUID, Long>> it = PENDING_AT.entrySet().iterator();
            java.util.List<UUID> expired = new java.util.ArrayList<>();
            while (it.hasNext()) {
                Map.Entry<UUID, Long> en = it.next();
                if (now - en.getValue() >= PENDING_TTL_MS) {
                    expired.add(en.getKey());
                }
            }
            for (UUID id : expired) {
                PENDING_AT.remove(id);
                java.lang.ref.WeakReference<Entity> ref = PENDING.remove(id);
                Entity e = ref == null ? null : ref.get();
                if (e != null) {
                    clearPendingMark(e);
                    com.maidsmart.tool.PromaidLog.log("骑乘指挥棒", "待选光标超时（"
                            + (PENDING_TTL_MS / 1000) + " 秒没有配对）→ 已熄灯："
                            + MaidRideKit.describe(e));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【实测七百二十九·点2】兜底扫描：清掉"身上带着我们的标记痕迹、但已经没有归属"的光标。
     *
     * <p>为什么必须有这一道——原版发光标记会**写进 NBT 持久化**
     * （javap 实证：{@code Entity.saveWithoutId} 把 {@code hasGlowingTag} 写进 {@code "Glowing"}
     * 键、{@code Entity.load} 再读回来调 {@code setGlowingTag}），所以它**跨存档、跨重启都在**。
     * 而我们的清理只挂在"链路表里那条 Link"上：任何一次"进程被杀 / 区块卸载 / 重启后没能重建
     * 链路"都会留下一个**永久发光、且没有任何入口能解除**的实体——玩家原话：
     * 「如果一只龙被绑定了以后并且上了光标，那就再也没有办法解除他身上的光标了。这边建议在
     * 解除女仆乘坐在坐骑上的情况以后就顺便解除龙身上的光标。」
     *
     * <p>这一道不依赖链路表，只看我们**自己的两个留痕标记**（都是写在实体 persistentData 里的
     * 私有键，原版/别的模组绝不会写）：
     * <ol>
     *   <li>{@code TAG_PENDING_MARK}（"待选光标"，716 起就在写）：带它就说明"这是棍子点出来的光标"。
     *       只要此刻没有玩家把它当作待配对目标 → 它就是残留，熄灯。</li>
     *   <li>{@code TAG_RIDE_MAID}（"这只坐骑驮着谁"，729 起开始写）：带它但链路表里查无此车
     *       → 死标记，熄灯 + 清留痕。</li>
     * </ol>
     *
     * <p><b>绝不误伤</b>：判据是"带我们的私有键"，不是"在发光"——她自己中了光灵箭、别的模组
     * 让她发光，都不会带这两个键，一次都不会被碰。
     *
     * <p>节流 {@code MARK_SWEEP_MS} 一次（5 秒）；只在表里/待选里有东西时才扫，空转代价为零。
     */
    private static void sweepStaleMarks(MinecraftServer server) {
        try {
            if (LINKS.isEmpty() && PENDING.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastMarkSweepMs < MARK_SWEEP_MS) {
                return;
            }
            lastMarkSweepMs = now;
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity e : com.maidsmart.tool.EntitySnapshot.of(level)) {
                    if (e instanceof EntityMaid) {
                        continue; // 女仆那条由链路表自己的生命周期管（她不是"坐骑侧留痕"）
                    }
                    boolean ourTag = false;
                    boolean pendingTag = false;
                    try {
                        pendingTag = e.getPersistentData().getBoolean(TAG_PENDING_MARK);
                        ourTag = pendingTag
                                || !e.getPersistentData().getString(TAG_RIDE_MAID).isEmpty();
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (!ourTag) {
                        continue;
                    }
                    // ① 待选光标：还挂在某位玩家的待配对里吗？
                    if (pendingTag && !isPendingSomewhere(e)) {
                        clearPendingMark(e);
                        if (e.isCurrentlyGlowing()) {
                            unmark(e);
                            com.maidsmart.tool.PromaidLog.log("骑乘指挥棒", "残留「待选光标」→ 已熄灯："
                                    + MaidRideKit.describe(e) + "（没有玩家把它当作待配对目标了）");
                        }
                        continue;
                    }
                    // ② 坐骑侧留痕：链路表里还有归属吗？
                    if (mountHasLink(e)) {
                        continue;
                    }
                    try {
                        e.getPersistentData().remove(TAG_RIDE_MAID);
                    } catch (Throwable ignored) {
                    }
                    if (e.isCurrentlyGlowing()) {
                        unmark(e);
                        com.maidsmart.tool.PromaidLog.log("骑乘指挥棒", "坐骑侧残留光标 → 已熄灯："
                                + MaidRideKit.describe(e) + "（链路已不存在，属于存档里留下的死标记）");
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 还有没有哪位玩家把这只实体当作"待选坐骑"（供 {@link #sweepStaleMarks} 用）。 */
    private static boolean isPendingSomewhere(Entity e) {
        try {
            for (java.lang.ref.WeakReference<Entity> ref : PENDING.values()) {
                Entity p = ref == null ? null : ref.get();
                if (p != null && p.getUUID().equals(e.getUUID())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 链路表里还有没有一条 Link 指向这只坐骑（供 {@link #sweepStaleMarks} 用）。 */
    private static boolean mountHasLink(Entity mount) {
        try {
            if (mount == null) {
                return false;
            }
            for (Map.Entry<UUID, Link> en : LINKS.entrySet()) {
                Entity m = en.getValue().mount.get();
                if (m != null && m.getUUID().equals(mount.getUUID())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 死标记扫描的节流（毫秒）。 */
    private static final long MARK_SWEEP_MS = 5000L;
    private static long lastMarkSweepMs = 0L;
}
