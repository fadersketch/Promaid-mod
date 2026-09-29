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
    private static final Map<UUID, Long> DENY_LOG = new HashMap<>();

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
            EntityMaid chairRider = chairRiderOf(target);
            if (chairRider != null) {
                deny(player, chairRider, ownable(player, chairRider)
                        ? "我已经在这条龙背上啦～（潜行右击可以让我下来）"
                        : "这条龙的背上已经有女仆了～");
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
        mark(maid);
        mark(mount);
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
            if (!isEnabled() || LINKS.isEmpty()) {
                return;
            }
            Entity e = event.getEntity();
            if (e == null || e.level().isClientSide()) {
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
        Entity mount = link == null ? null : link.mount.get();
        // 【实测七百二十五·点1】自动解除同样要还原坐姿（与 releaseMaidImpl 同口径）。
        MaidMountCompat.setSitting(maid, link != null && link.prevSitting);
        // 【实测七百二十四】自动解除这条以前**不还原**重力/龙的行动档（只有 releaseMaidImpl 才还原）
        // → 她永久无重力飘着、龙永久卡跟随档。这里补齐，与 releaseMaidImpl 同口径。
        if (link != null && link.chair) {
            MaidMountCompat.setGravity(maid, true);
            MaidMountCompat.restoreDragonCommand(mount, link.dragonCommand);
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
            // 【实测七百二十六·点3】飞行载具（卓越前线的直升机 / 固定翼）**有攻击目标时改走
            // 扫帚模式同款空战**：先爬到敌上 airAltCfg() 格（默认 15），再绕着敌人盘旋射击。
            // 玩家原话「应该要套用扫帚模式运动代码和逻辑……至少离敌人要高出15格左右吧」。
            // 这一档**排在最前**：有敌人时不再去看"她的走位记忆/主人跟随点"（旧版正是那两者
            // 让它贴地追主人、在低空乱窜）。无目标 / 关掉开关 / 非飞行载具 → 原样落回下面的链路。
            if (MaidAirCombat.enabled() && MaidMountCompat.isFlyingVehicle(mount)) {
                LivingEntity foe = targetOf(maid);
                if (foe != null && foe.isAlive() && foe.level() == maid.level()) {
                    Vec3 air = MaidAirCombat.combatTarget(maid, foe);
                    if (air != null) {
                        MaidRideKit.feedNavigation(mount, air, mod, maid);
                        // 朝向交给驾驶层：飞行档按"目标方位 vs 机头"写鼠标 X 通道（driveFlight 里）
                        return;
                    }
                } else {
                    MaidAirCombat.clear(maid); // 没目标 → 这一场遭遇作废，下一场重新爬
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
            com.maidsmart.tool.StateTables.cap("骑乘.DENY_LOG", DENY_LOG);
            com.maidsmart.tool.StateTables.cap("骑乘.ENGAGE_AT", ENGAGE_AT); // 实测七百二十六·点6
            com.maidsmart.tool.StateTables.cap("骑乘.AMMO_FEED_AT", AMMO_FEED_AT); // 实测七百二十六·点6
        } catch (Throwable ignored) {
        }
    }
}
