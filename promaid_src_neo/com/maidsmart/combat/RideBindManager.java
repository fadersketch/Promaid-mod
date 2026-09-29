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

    /**
     * 【实测七百二十三】悬空鞍位（龙）容许的最大偏离（格）：她离龙超过这个距离
     * （被别的模组拉走 / 被传送走 / 龙换维度）就解除链路——别让她在远处凭空飘着。
     * 取 8：龙自己的跟随档 {@code DragonAIEscortGoal.canContinueToUse} 里 15 格以内
     * 都还跟着走，正常不会甩开；一旦真的被拽走（几十格）立刻认出来。
     */
    private static final double CHAIR_MAX_GAP = 8.0;

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

        Link(EntityMaid m, Entity mount, ServerPlayer owner) {
            this(m, mount, owner, false, -1);
        }

        Link(EntityMaid m, Entity mount, ServerPlayer owner, boolean chair, int dragonCommand) {
            this.maid = new java.lang.ref.WeakReference<>(m);
            this.mount = new java.lang.ref.WeakReference<>(mount);
            this.owner = new java.lang.ref.WeakReference<>(owner);
            this.chair = chair;
            this.dragonCommand = dragonCommand;
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
        event.setCanceled(true);
        if (recognized) {
            player.swing(hand);
            handle((ServerPlayer) player, target);
        }
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
                event.setCanceled(true);
            }
        } catch (Throwable ignored) {
        }
    }

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
        // 【实测七百一十六·点1】独占：与武装拴绳同款——一位玩家同时只带一条骑乘链路。
        // 玩家原话："如果绑定了一个女仆再绑定另一个，那么第1个会解绑，坐骑同理。"
        releaseOtherLinks(player, maid);
        releaseMountLinks(mount, maid);
        if (LINKS.containsKey(maid.getUUID())) {
            releaseMaid(maid, false, "换绑");
        }
        // 【实测七百二十三】冰火传说的龙走**降级方案**：不 startRiding（她不是乘客），
        // 改成"每 tick 由我们把她摆到龙的玩家鞍位上 + 无重力 + 龙置跟随档"。
        // 完整口径见 MaidMountCompat 那一节的类注释（为什么真骑这条路走不通）。
        boolean chair = MaidMountCompat.isDragon(mount);
        int prevCmd = chair ? MaidMountCompat.dragonCommand(mount) : -1;
        if (chair) {
            // ① 摆位 + 无重力（她不是乘客，原版没有东西托着她）
            MaidMountCompat.setGravity(maid, false);
            if (!MaidMountCompat.seatOnDragon(mount, maid)) {
                MaidMountCompat.setGravity(maid, true); // 摆不上就还原，别让她飘着
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
        LINKS.put(maid.getUUID(), new Link(maid, mount, player, chair, prevCmd));
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
        Entity mount = link == null ? null : link.mount.get();
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
                // 【实测七百二十三】悬空鞍位（龙）：她**不是乘客**，判据换成"由我们每拍摆位"。
                if (link.chair) {
                    double gap = maid.position().distanceTo(mount.position());
                    if (gap > CHAIR_MAX_GAP) {
                        deferred.add(maid);
                        continue;
                    }
                    MaidMountCompat.setGravity(maid, false); // 保底：被翻回去时纠回来
                    MaidMountCompat.seatOnDragon(mount, maid);
                    // 龙自己那套飞行物理照常跑（我们只把它钉在跟随档）；攻击也不由我们触发。
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
            // v1.3.0(beta) 实测七百一十九【模组坐骑通解通法】：每拍把她的攻击目标交给坐骑——
            // 卓越前线载具走内置 Mob-乘客自动开火、冰火传说龙走吐息、**其余任何 Mob 坐骑**
            // 走通用兜底（无条件把她的 target 写下去，让它自己那套目标 AI 用它自己的攻击方式打）。
            // 实测七百一十九·点4 起这一档不再只对"模组坐骑"开——原版兽同样走一遍（它们没有
            // 目标 AI 时写了也无副作用），这样"别的模组的可骑乘战斗生物"零适配即可服从。
            MaidMountCompat.tickAttack(mount, maid);
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
            MaidRideKit.feedNavigation(mount, target, mod, maid);
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
