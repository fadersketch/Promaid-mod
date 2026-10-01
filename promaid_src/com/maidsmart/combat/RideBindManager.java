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
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
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
    /** 【实测七百一十六·点3】persistentData：这只实体正被棍子"待配对"选中（金色"光标"）。
     *  与链路标记分开记——选中是瞬时的：选另一只 / 超时 / 绑定完成都要能干净撤掉，
     *  且只撤"我们打的那一下"（不误清光灵箭等别的发光来源）。 */
    public static final String TAG_PENDING_MARK = "maid_smart_ride_pending";

    /** 每 2 tick 校验一次（与武装拴绳同频） */
    private static final int TICK_DIV = 2;
    /** 拒绝/提示节流（ms） */
    private static final long DENY_INTERVAL_MS = 3000L;
    /** "离主人这么近就不喂目标"的地面余量（格）：防坐骑顶着主人打转 */
    private static final double STOP_SLACK = 1.5;

    // 【实测七百二十四】旧的 CHAIR_MAX_GAP（偏离 8 格就解除悬空鞍位链路）已删除：
    // 距离不再是"断开"的理由——她本来就每拍被摆回鞍位；只有她/龙/主人真没了或跨维度才解除。
    // 详见 tick() 里那段注释。

    /** 服务端链路表：女仆 UUID → 链路（弱引用，女仆/坐骑/主人没了自动失效） */
    private static final Map<UUID, Link> LINKS = new HashMap<>();

    /** 待配对：玩家 UUID → 选中的实体（女仆或坐骑）。只活服务端、只在这位玩家手里有效 */
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
     *
     * <p>玩家原话：「骑乘指挥棒在使用的时候不会触发原本的右击效果。只会触发骑乘棒自己的右击
     * 效果，也就是说你拿骑乘棒是骑不上龙或者车子的。」开着时，手持棍子的**任何**实体右击
     * 一律被棍子吃掉（含 {@code interactAt}）——登龙/上车都长在 {@code m_6096_}/{@code m_6071_}
     * 那条路上，吞掉就不会触发。关掉 = 只有"我们认得出的目标"才接管（与今天一字不差）。
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
     * 手持骑乘指挥棒右击实体（女仆 / 坐骑）——骨架同 {@code GunnerTetherManager.onInteract}：
     * EntityInteract 比 TLM 的 mobInteract 先到，cancel 掉就不会误开女仆 GUI。
     *
     * <p>【实测七百二十·点2】目标先过一道 {@link MaidMountCompat#resolveMount}：冰火传说的龙是
     * **多部件实体**，准星常常打中的是它的**翅膀/尾巴/头**那些部位实体，而部位会把右击**转发给
     * 本体**（{@code EntityMultipartPart.m_6096_} → {@code getParent().m_6096_}，反编译实证）。
     * 不解析的话我们看到的只是一块"部位"（既不是女仆也不是 Mob）→ 放行 → 转发到龙 → 玩家就骑上去了。
     */
    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!isEnabled()) {
            return;
        }
        // 【实测七百二十三】判据从 ServerPlayer 放宽到 Player：这个事件**两侧都发**
        // （服务端 Player.interactOn → CommonHooks/ForgeHooks；客户端 MultiPlayerGameMode
        // → LocalPlayer.interactOn 同一条链）。旧版只认 ServerPlayer，等于**客户端那一份
        // 从来没被拦**——而卓越前线的 VehicleEntity.interact 是在**客户端本地**也跑的
        // （`player.startRiding` 在 `level() instanceof ServerLevel` 之外还有本地分支），
        // 它一跑就会 setDriverAngle（把玩家转向车头）+ 把第一个非玩家乘客 stopRiding 踢掉。
        // 这正是玩家说的"右击完之后玩家的位置发生了改变。似乎是坐上去秒坐下来的结果"。
        // 现在两侧都拦：客户端这一下直接被吃掉，本地预测也不会跑 SWB 那段。
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
        ItemStack stack = player.m_21120_(hand);
        if (!(stack.m_41720_() instanceof RideBatonItem)) {
            // 【实测七百三十七·双人座·空手右击】玩家原话：「我考虑到卓越前线有一些载具是分为
            //  双人座的，能否考虑在女仆乘坐后主人右击的时候登上副驾驶座呢？」——空手右击自己
            //  女仆开的车时，卓越前线自己的 VehicleEntity.interact 走的是"第一乘客不是玩家 →
            //  把她踢下来、主人坐座位 0"那一支（反编译实证），正是"女仆被顶下车"的根因。
            //  所以这一档由我们接管：两侧都吞掉这一下（客户端那一份不吞，本地预测仍会把她踢下去），
            //  服务端把主人放进**副驾**（{@link #handlePassengerSeat}），女仆留在驾驶位。
            handlePassengerSeat(player, event);
            return;
        }
        Entity raw = event.getTarget();
        if (raw == null) {
            return;
        }
        Entity target = MaidMountCompat.resolveMount(raw);
        boolean maid = target instanceof EntityMaid;
        boolean mount = !maid && MaidRideKit.isRideableMount(target, null);
        // v1.3.0(beta) 实测七百一十八·点4：家具（椅子/坐垫）与扫帚**不是 Mob**，若不单独
        // 认出来就会在下面那句"对着别的实体挥棍子没效果"里直接 return —— 玩家看不到任何
        // 提示。这里把它们收进来，让它们在 denyReason 里给出明确拒绝（"这是家具，不是坐骑～"
        // / "扫帚有它自己的飞法"）。
        boolean rejected = !maid && (MaidRideKit.isFurniture(target) || MaidRideKit.isBroom(target));
        // 【实测七百四十七】不可驾名单里的载具（Ju-87/A-10/AC-130H/KV-16/汤姆6/迷你快艇）：
        // 它们**不是 Mob**、`isRideableMount` 又因为 denyReason 非空而返回 false，于是若不单独
        // 认出来，`recognized` 就是 false → 独占档下这一下右击被静默吞掉，玩家等不到任何解释
        // （正是"认人"这件事最需要说清楚的地方）。收进来走 denyReason 那条路给气泡。
        boolean banned = MaidMountCompat.isUnridable(target);
        /** 这一下棍子认得出是什么（女仆 / 能骑的 / 家具扫帚 / 不可驾载具 / 任何 Mob）——认得出才回话 */
        boolean recognized = maid || mount || rejected || banned || (target instanceof Mob);
        // 【实测七百二十·点2 独占档】玩家原话："加一个新设定，骑乘指挥棒在使用的时候不会触发
        // 原本的右击效果。只会触发骑乘棒自己的右击效果，也就是说你拿骑乘棒是骑不上龙或者车子的。"
        // 所以独占档开着时，只要手里拿的是骑乘棒，这一下实体右击**一律由棍子吃掉**——不管目标是
        // 什么，绝不再往下走 {@code m_6096_}/{@code m_6071_}（登龙/上车都在那里）。
        // 关掉独占 = 与今天一字不差（只有认得出的目标才接管）。
        if (!recognized && !batonExclusive()) {
            return; // 既不是女仆也不是生物：不接（对着别的实体挥棍子没有任何效果）
        }
        // 【实测七百二十四】右击载具会转玩家视角：SWB 的 VehicleVecUtils.setDriverAngle 在
        // player.m_7998_ 之前就调用了，而我们随后拦下 startRiding 也拦不回已经转过的视角。
        // 所以这一下前后做"视角快照 / 还原"——只把**这一下右击**造成的转动抹掉，玩家自己转视角不受影响。
        float[] snap = ViewSnapshot.capture(player);
        event.setCanceled(true);
        if (recognized) {
            player.m_6674_(hand);
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
     * 但**客户端本地预测**是独立跑的——不拦它，玩家屏幕上就会出现"转了一下头 / 位置跳了一下"
     * （玩家原话"右击完之后玩家的位置发生了改变"）。这里只 cancel，不调 {@code handle}。
     */
    private static void cancelClientBatonInteract(Player player, PlayerInteractEvent.EntityInteract event) {
        try {
            // 【实测七百三十七·双人座】空手右击"自己女仆开的车"同样要在客户端吞掉这一下：
            // 本地预测里 SWB 的 VehicleEntity.interact 会当场把女仆 stopRiding 踢下去
            // （服务端随后纠正也救不回玩家眼前这一下）。这一档与"拿不拿棍子"无关，放最前。
            if (cancelClientPassengerSeatInteract(player, event)) {
                return;
            }
            if (!batonExclusive()) {
                return;
            }
            ItemStack stack = player.m_21120_(event.getHand());
            if (!(stack.m_41720_() instanceof RideBatonItem)) {
                return;
            }
            Entity target = MaidMountCompat.resolveMount(event.getTarget());
            if (target == null) {
                return;
            }
            boolean recognized = target instanceof EntityMaid || target instanceof Mob
                    || MaidRideKit.isRideableMount(target, null)
                    || MaidMountCompat.kindOf(target) != null
                    || MaidMountCompat.isUnridable(target); // 实测七百四十七：不可驾载具也要吞这一下
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
     * 【实测七百三十七·双人座】空手右击的入口：对着"自己女仆开的卓越前线载具"右击 → 主人上副驾。
     *
     * <p>玩家原话：「我考虑到卓越前线有一些载具是分为双人座的，能否考虑在女仆乘坐后主人右击的
     * 时候登上副驾驶座呢？」——空手右击那条路原本走的是卓越前线自己的 {@code VehicleEntity.interact}，
     * 而它见"第一乘客不是玩家"就把女仆 {@code stopRiding} 踢下来、自己坐驾驶位（反编译实证）。
     * 这里把这一档接过来：认得出来（自己女仆开的车 + 有空副驾）就两侧都 cancel，服务端再把主人
     * 放进副驾；认不出就**一个字节都不动**（原版行为照旧）。
     */
    private static void handlePassengerSeat(Player player, PlayerInteractEvent.EntityInteract event) {
        try {
            if (!isEnabled()) {
                return;
            }
            Entity target = MaidMountCompat.resolveMount(event.getTarget());
            if (target == null || !allowsOwnerPassengerSeat(player, target)) {
                return; // 不是"自己女仆开的车"→ 不插手
            }
            event.setCanceled(true); // 两侧都吞：挡住 SWB 本地预测把她踢下车
            if (!(player instanceof ServerPlayer sp)) {
                return; // 客户端只 cancel，不执行业务
            }
            EntityMaid maid = MaidRideKit.riderOf(target);
            if (MaidMountCompat.boardOwnerAsPassenger(target, sp, maid)) {
                sp.m_213846_(Component.m_237113_("\u00a7a已坐上副驾驶～\u00a7f（"
                        + MaidRideKit.describe(target) + "\u00a7f 由她开）"));
                com.maidsmart.tool.PromaidLog.log("骑乘指挥棒", "主人上副驾(空手右击)："
                        + MaidRideKit.describe(target) + "（玩家=" + name(sp)
                        + (maid != null ? "，女仆=" + com.maidsmart.tool.PromaidLog.nameOf(maid)
                                          + " 仍在驾驶位" : "") + "）");
            } else {
                deny(sp, maid, "这辆车没有能坐的副驾～"); // maid 允许为 null（deny 会发系统消息）
            }        } catch (Throwable ignored) {
        }
    }

    /** 客户端的空手副驾闸：只 cancel（认得出"自己女仆开的车 + 有空副驾"时）。 */
    private static boolean cancelClientPassengerSeatInteract(Player player,
                                                             PlayerInteractEvent.EntityInteract event) {
        try {
            Entity target = MaidMountCompat.resolveMount(event.getTarget());
            if (target == null || !allowsOwnerPassengerSeat(player, target)) {
                return false;
            }
            ViewSnapshot.pin(player, ViewSnapshot.capture(player)); // 顺带抹掉 SWB 本地那一拍转视角
            event.setCanceled(true);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 【实测七百二十四】"右击载具不再转玩家视角"的兜底口径。
     *
     * <p>根因（反编译实证）：SWB {@code VehicleVecUtils.setDriverAngle(vehicle, player)} 是**全 jar
     * 唯一**改玩家 yRot/xRot/yHeadRot 的地方，而它在 {@code VehicleEntity.interact} 里
     * {@code player.m_7998_} **之前**就被调用（:3172 / :3184）。我们虽然后面把 startRiding 拦了，
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
                return new float[]{p.m_146908_(), p.m_146909_(), p.m_6080_(),
                        p.f_19859_, p.f_19860_, p.f_20886_};
            } catch (Throwable ignored) {
                return null;
            }
        }

        static void restoreIfChanged(Player p, float[] snap) {
            try {
                if (snap == null) {
                    return;
                }
                if (p.m_146908_() != snap[0] || p.m_146909_() != snap[1] || p.m_6080_() != snap[2]) {
                    p.m_146922_(snap[0]);
                    p.m_146926_(snap[1]);
                    p.m_5616_(snap[2]);
                    p.f_19859_ = snap[3];
                    p.f_19860_ = snap[4];
                    p.f_20886_ = snap[5];
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
                PINS.put(p.m_20148_(), new long[]{until,
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
            long[] v = ViewSnapshot.PINS.get(player.m_20148_());
            if (v == null) {
                return false;
            }
            if (System.currentTimeMillis() > v[0]) {
                ViewSnapshot.PINS.remove(player.m_20148_()); // 过期 → 失效
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
            return; // 关掉独占 = 与今天一字不差（今天不碰 interactAt）
        }
        // 【实测七百二十三】与 onInteract 同款放宽到 Player：这个事件同样两侧都发，
        // 客户端那份不拦就还会走 SWB 的本地 interactAt 分支。这里纯当闸门（只 cancel）。
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player player = event.getEntity();
        ItemStack stack = player.m_21120_(event.getHand());
        if (!(stack.m_41720_() instanceof RideBatonItem)) {
            return;
        }
        event.setCanceled(true);
    }

    /**
     * v1.3.0(beta) 实测七百二十一【点2 补闸：独占档改在"登乘那一道"上落实】。
     *
     * <h2>玩家原话（720 之后仍在）</h2>
     * 「右击问题还是没有解决，拿着骑乘棒右击卓越前线的载具会直接坐上去，而不是绑定。」
     *
     * <h2>720 那两道闸为什么拦不住它（字节码实证）</h2>
     * 720 拦的是 {@code PlayerInteractEvent.EntityInteract( Specific )}——它们由
     * {@code Player.m_36157_}（= {@code interactOn}，服务端）与
     * {@code MultiPlayerGameMode.m_105230_/m_105226_}（客户端本地）里各发一次，**只覆盖"原版那条
     * 右击链路"**。而"坐上载具"这件事在更下面一层：**任何** {@code startRiding} 都会走
     * {@code Entity.m_7998_} → {@code ForgeEventFactory.canMountEntity} /
     * {@code EventHooks.canMountEntity} → 发 {@code EntityMountEvent}（javap 实证：
     * {@code Entity.m_7998_} 偏移 49 就是这次调用）。卓越前线的载具/轮椅走的是它自己那条
     * 客户端本地链路（{@code WheelChairEntity.attractEntity} 每 tick 把附近的**非玩家**
     * 生物 {@code startRiding} 上来、玩家那条走 {@code VehicleEntity.interact} →
     * {@code player.startRiding}），**客户端本地那一下**根本不过我们那两个事件。
     *
     * <h2>所以补这一道（唯一收口）</h2>
     * 独占档开着时：**手里拿着骑乘指挥棒的玩家，不通过任何方式登乘**——{@code EntityMountEvent}
     * 是**所有**登乘路径（原版右击 / 模组自己的本地链路 / 别的模组调 {@code startRiding}）
     * 的共同必经点，而且客户端与服务端**各发一次**（{@code canMountEntity} 在两侧的
     * {@code m_7998_} 里都调），所以两边都拦得住。
     *
     * <p><b>只拦"玩家本人"</b>：女仆自己登乘（{@link #bind} 里的
     * {@code maid.m_7998_(mount, true)}）与武装拴绳那条链路（它压根不用 {@code startRiding}）
     * 一个字节都不受影响。关掉 {@code ride.batonExclusive} = 与今天一字不差。
     */
    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        try {
            if (!isEnabled() || !batonExclusive()) {
                return; // 关掉独占 = 与今天一字不差（不碰登乘）
            }
            if (!event.isMounting()) {
                return; // 下鞍不管
            }
            if (!(event.getEntityMounting() instanceof Player player)) {
                return; // 不是玩家（女仆自己坐上去 / 别的生物）→ 放行，这是我们要的绑定那条路
            }
            if (!holdsBaton(player)) {
                return; // 手里不是骑乘指挥棒 → 原版一字不动
            }
            // 【实测七百四十二·点1】换座自己那一下必须放行（否则"拿指挥棒"的人永远回不到车上）。
            if (remountPermitted(player, event.getEntityBeingMounted())) {
                return;
            }
            // 【实测七百三十八·指挥棒绝不登乘】手里拿着指挥棒 = 一律拦下（含副驾）。
            // 空手坐副驾那条路不受影响（handlePassengerSeat 里不查手里拿什么）。
            event.setCanceled(true);
            PlayerMountLog.throttled(player);
        } catch (Throwable ignored) {
        }
    }

    /** 玩家（主手或副手）拿着骑乘指挥棒吗。 */
    private static boolean holdsBaton(Player player) {
        try {
            return player.m_21205_().m_41720_() instanceof RideBatonItem
                    || player.m_21206_().m_41720_() instanceof RideBatonItem;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ==================== 实测七百四十二·点1：换座期间的"重新登乘放行条" ==================== */

    /**
     * 【实测七百四十二·点1】"左击换座"这一步要重上车，但玩家**手里正拿着指挥棒**——
     * 而我们自己那两道登乘闸（{@link #denyMountForBatonHolder} 拦 {@code startRiding} 本体 +
     * {@link #onMount} 拦 {@code EntityMountEvent}）就是**专门拦"拿指挥棒的人"**的。
     * 于是 741 那一版的 {@code player.m_7998_(mount, true)} 恒返回 false，
     * 每次都落在"换座失败：坐不回去了"。这正是玩家原话
     * 「从副驾驶换到主驾驶座这个操作一直没能实现。老是提示失败」的根因。
     *
     * <h2>放行条的口径（尽量窄）</h2>
     * 只在"这一次换座"这一个动作期间有效，且**必须同时匹配玩家与载具**：
     * <ul>
     *   <li>{@link #grantRemount} 紧贴 {@code startRiding} 之前开，
     *       {@link #revokeRemount} 用 {@code finally} 在同一拍关掉；</li>
     *   <li>键是**玩家 UUID → 载具 UUID**，换成别的车、或别的玩家长棍子都无效；</li>
     *   <li>只在服务端这张表里生效（客户端那份由 {@link #armClientRemount} 单独喂）。</li>
     * </ul>
     */
    private static final Map<UUID, UUID> REMOUNT_PERMIT = new HashMap<>();

    /** 服务端：开一张"这一下允许他重上车"的放行条（紧贴 startRiding 之前调）。 */
    public static void grantRemount(Player player, Entity vehicle) {
        try {
            if (player == null || vehicle == null) {
                return;
            }
            synchronized (REMOUNT_PERMIT) {
                if (REMOUNT_PERMIT.size() > 64) {
                    REMOUNT_PERMIT.clear();
                }
                REMOUNT_PERMIT.put(player.m_20148_(), vehicle.m_20148_());
            }
        } catch (Throwable ignored) {
        }
    }

    /** 服务端：关掉放行条（{@code finally} 里调，确保任何异常都不留条）。 */
    public static void revokeRemount(Player player) {
        try {
            if (player == null) {
                return;
            }
            synchronized (REMOUNT_PERMIT) {
                REMOUNT_PERMIT.remove(player.m_20148_());
            }
        } catch (Throwable ignored) {
        }
    }

    /** 客户端那一份：由服务端在换座前 S2C 下发（{@link MaidSeatNetworking#armRemount}）。 */
    private static volatile int CLIENT_REMOUNT_VEHICLE = Integer.MIN_VALUE;
    private static volatile long CLIENT_REMOUNT_UNTIL_MS = 0L;

    /** 客户端：这一小会儿允许"拿指挥棒的玩家"登上这辆车（由 S2C 包调）。 */
    public static void armClientRemount(int vehicleId) {
        CLIENT_REMOUNT_VEHICLE = vehicleId;
        CLIENT_REMOUNT_UNTIL_MS = System.currentTimeMillis() + CLIENT_REMOUNT_WINDOW_MS;
    }

    /** 客户端放行窗口（毫秒）。同 tick 就会用到，1 秒足够富余。 */
    private static final long CLIENT_REMOUNT_WINDOW_MS = 1000L;

    /** 这一次"上车"是不是我们换座自己触发的那一下（服务端表 / 客户端窗口，任一命中即放行）。 */
    private static boolean remountPermitted(Player player, Entity vehicle) {
        try {
            if (player == null || vehicle == null) {
                return false;
            }
            UUID want;
            synchronized (REMOUNT_PERMIT) {
                want = REMOUNT_PERMIT.get(player.m_20148_());
            }
            if (want != null && want.equals(vehicle.m_20148_())) {
                return true; // 服务端：我们刚开的条
            }
            if (CLIENT_REMOUNT_VEHICLE == vehicle.m_19879_()
                    && System.currentTimeMillis() < CLIENT_REMOUNT_UNTIL_MS) {
                return true; // 客户端：服务端刚发过"这一辆车放行"
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 【实测七百四十一·点1】给客户端专用类问的"手里拿着指挥棒吗"——**两边都能安全调**
     * （{@link #holdsBaton} 是 private，且客户端那一支需要它但拿不到）。
     */
    public static boolean holdsBatonClient(Player player) {
        return holdsBaton(player);
    }

    /**
     * 【实测七百四十一·点1】给客户端专用类问的"这是不是卓越前线的载具"——只走反射探测
     * （{@link MaidMountCompat#kindOf} 在两侧都安全，不碰客户端专属类型）。
     */
    public static boolean isModVehicleClient(Entity e) {
        try {
            return MaidMountCompat.kindOf(e) == MaidMountCompat.Kind.VEHICLE;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 【实测七百四十一·点1】左击换座的服务端那一半。玩家原话：「如果玩家处于副座，可以通过手持
     * 骑乘指挥棒进行左击，从而把自己交换到主座位。再左击一下再换回去。」
     *
     * <p>规则（服务端权威，客户端只发"我要换"）：
     * <ol>
     *   <li>手里得拿着骑乘指挥棒（左击这一下由客户端闸放进来的；服务端再核一遍，防伪造包）；</li>
     *   <li>目标载具得是**卓越前线载具**（{@link MaidMountCompat.Kind#VEHICLE}）——原版马/猪那种
     *       只有一个座位，没有"主副驾"可言；</li>
     *   <li>她（我们绑的那位女仆）得**还在这辆车上**，且是棍子绑的（{@link MaidRideKit#isRideRider}）。</li>
     * </ol>
     * 换座走 SWB 自己的 {@code changeSeat(Entity,int)}——见 {@link MaidMountCompat#swapOwnerSeat}。
     */
    public static void handleSwapSeatRequest(ServerPlayer player, int vehicleId) {
        try {
            if (player == null || !isEnabled() || !batonExclusive()) {
                return;
            }
            if (!holdsBaton(player)) {
                return; // 手里没拿指挥棒 → 不是这一档（也防伪造包）
            }
            Entity mount = player.m_9236_().m_6815_(vehicleId);
            if (mount == null || MaidMountCompat.kindOf(mount) != MaidMountCompat.Kind.VEHICLE) {
                return;
            }
            if (player.m_20202_() != mount) {
                return; // 他自己不在车上 → 无从换
            }
            EntityMaid maid = MaidRideKit.riderOf(mount);
            if (maid == null || !ownable(player, maid) || !MaidRideKit.isRideRider(maid)) {
                return; // 车上不是我们绑的女仆 → 不插手（纯玩家自己开的车，座位由他自己管）
            }
            // 【实测七百四十二·点1】先给**客户端**发一个"这一辆车，这一小会儿放行登乘"的包——
            // 服务端 startRiding 成功后客户端会跟着本地再上一次车，而客户端也装着那道
            // "拿指挥棒不许上车"的 mixin（见 armClientRemount 的注释）。
            MaidSeatNetworking.armRemount(player, mount.m_19879_());
            String msg = MaidMountCompat.swapOwnerSeat(mount, player, maid);
            if (msg != null) {
                player.m_5661_(Component.m_237113_(msg), true); // 动作栏一行，不刷聊天
            }
            com.maidsmart.tool.PromaidLog.log("骑乘指挥棒", "左击换座：主人=" + name(player)
                    + " 车=" + MaidRideKit.describe(mount) + "（女仆="
                    + com.maidsmart.tool.PromaidLog.nameOf(maid) + "）");
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【实测七百二十二】独占档的**唯一收口**：{@code Entity.m_7998_}（{@code startRiding}）的最前面。
     *
     * <h2>玩家原话（721 之后仍在）</h2>
     * 「右击问题还是没有解决，拿着骑乘棒右击卓越前线的载具会直接坐上去，而不是绑定。」
     *
     * <h2>为什么上面那道 {@code EntityMountEvent} 也不够（实机日志实证）</h2>
     * 2026-09-29 16:07 那次测试的日志里，这两行**同时**打出：
     * <pre>
     *   16:07:10.572  [骑乘指挥棒] 绑定：… 坐骑=卓越前线载具(WHEELCHAIR)
     *   16:07:10.573  [骑乘指挥棒] Khragg 手里拿着指挥棒 → 不登乘（独占右击：这一下只归棍子）
     * </pre>
     * 绑定成功、闸也拦下了，玩家眼前却仍然是"自己坐上去了"：{@code EntityMountEvent} 是在
     * {@code startRiding} **内部**发的，取消它 = "先同意上车、再撤销"。而卓越前线的载具是
     * **客户端本地**上车的（{@code VehicleEntity.interact} → {@code player.startRiding}，
     * 以及它自己那条"非玩家驾驶位一律清掉"的分支）——客户端那一份预测不看服务端的事件结果，
     * 所以"坐上去"这一下照样在你屏幕上发生。
     *
     * <h2>所以再往下一层：拦 {@code startRiding} 本身</h2>
     * {@code m_7998_} 是**所有**登乘路径唯一的收口，且在 HEAD 返回 false 时**什么状态都还没改**
     * ——没有"上车再撤销"，也就没有可见的坐上去。由 {@code EntityBatonMountGateMixin} 调用。
     * 判据保持最窄：**玩家本人 + 手里拿着指挥棒 + 独占档开着**；女仆自己坐上去、
     * 武装拴绳（玩家拿的不是指挥棒）一律放行。关掉 {@code ride.batonExclusive} = 一字不差。
     *
     * @return true = 这一下不许上车
     */
    public static boolean denyMountForBatonHolder(Player player, Entity vehicle) {
        try {
            if (player == null || !isEnabled()) {
                return false;
            }
            // 【实测七百二十三】被"悬空鞍位"占着的龙：**谁都不许骑上去**（玩家原话
            // 「阻止一下右击骑龙的行为」）。这一条不看手里拿什么——因为降级方案里女仆
            // 就挂在玩家鞍位上，玩家再骑上去就会跟她重叠、而且龙会立刻把她当"非控制乘客"
            // （原版 getControllingPassenger 只认主人）走猎物分支。放在棍子判据之前。
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
            // 【边界】武装拴绳是"玩家挂到女仆/扫帚上"——主副手同时拿着指挥棒与拴绳时不该被误伤：
            // 指挥棒独占的语义是"骑不上龙/车子"，不是"挂不上自己的女仆"。
            if (vehicle instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid
                    || MaidRideKit.isBroom(vehicle)) {
                return false;
            }
            // 【实测七百三十八·指挥棒绝不登乘】玩家原话：「我们的骑乘指挥棒有最高的优先级，
            // 如果拿骑乘指挥棒进行右击，那么就不会坐上副驾驶，只会有他的解绑功能，绝对不会乘坐。」
            // 所以这里**删掉了 737 那道"放行主人坐副驾"的豁口**——手里拿着指挥棒的玩家，一律
            // 拦下登乘（包括副驾）。想坐副驾请**空手**右击（那条路仍然通，见 handlePassengerSeat）。
            // 【实测七百四十二·点1】唯一例外：**换座自己那一下**（grantRemount 开的放行条）。
            if (remountPermitted(player, vehicle)) {
                return false;
            }
            PlayerMountLog.throttled(player);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 【实测七百三十七·双人座】这位玩家现在能不能"作为副驾"登上这辆车——**唯一**的放行口径，
     * 供两道登乘闸（{@code denyMountForBatonHolder} 与 {@code onMount}）共用。
     *
     * <p>判据取最窄的三条：① 这是卓越前线的载具（其余坐骑一字不动）；② 车上驮着**这位玩家
     * 自己的、我们用指挥棒绑的**女仆（{@link MaidRideKit#riderOf} + 归属 + {@link MaidRideKit#isRideRider}）；
     * ③ 还有**除她之外的**空座。三条都满足 = 玩家是来坐副驾的，不是来抢驾驶位的——女仆稳在
     * 0 号座（引擎唯一认的驾驶位），这条放行不会让她被顶下车。
     */
    static boolean allowsOwnerPassengerSeat(Player player, Entity vehicle) {
        try {
            if (player == null || vehicle == null) {
                return false;
            }
            if (MaidMountCompat.kindOf(vehicle) != MaidMountCompat.Kind.VEHICLE) {
                return false; // 只管卓越前线载具：原版兽/龙/扫帚不在此列
            }
            EntityMaid rider = MaidRideKit.riderOf(vehicle);
            if (rider == null || !MaidRideKit.isRideRider(rider)) {
                return false; // 车上不是我绑的女仆 → 照旧按独占档拦
            }
            // 归属：直接比 UUID（**客户端也要能用**——本方法两侧都调，不能强转 ServerPlayer）。
            // 客户端可能拿不到女仆的 owner，那就按"拦"处理（退回原独占档语义，不会误放行）。
            LivingEntity owner = rider.m_269323_();
            if (owner == null || !owner.m_20148_().equals(player.m_20148_())) {
                return false;
            }
            return MaidMountCompat.firstFreeSeatExcept(vehicle, rider) >= 0; // 还有副驾空座
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
                UUID id = sp.m_20148_();
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
                // 她已经骑着**我们用棍子绑的**坐骑 → 再选她 = 直接下坐骑（比潜行更顺手的第二入口）
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
                // 已经有一只坐骑"待配对" → 当场配对
                Entity mount = takePendingMount(player);
                if (mount != null) {
                    bind(player, m, mount);
                    return;
                }
                if (com.maidsmart.combat.MaidBroomKit.isRidingBroom(m)
                        || com.maidsmart.combat.MaidBroomKit.isBroomTask(m)) {
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
                // 【实测七百三十八·指挥棒绝不登乘】玩家原话：「我们的骑乘指挥棒有最高的优先级，
                // 如果拿骑乘指挥棒进行右击，那么就不会坐上副驾驶，只会有他的解绑功能，绝对不会乘坐。」
                // 所以这里**删掉了 737 那个"先试坐上副驾"的分支**——拿着指挥棒右击自己女仆开的车，
                // 只有一种结果：解绑（下面那句 releaseMaid）。想坐副驾请空手右击（那条路没变）。
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
        // 【实测七百二十七·点2】目标是她自己、或她驮的坐骑、或她挂的龙（悬空鞍位）。
        // 龙那条她**不是乘客**，{@code riderOf} 找不到她 —— 必须补 {@link #chairRiderOf}。
        EntityMaid m = target instanceof EntityMaid mm ? mm : MaidRideKit.riderOf(target);
        if (m == null) {
            m = chairRiderOf(target);
        }
        if (m == null) {
            player.m_5661_(Component.m_237113_("\u00a77这只坐骑背上没有我的女仆"), false);
            return;
        }
        if (!ownable(player, m)) {
            deny(player, m, "她不是我的女仆～");
            return;
        }
        // 实测七百一十八·点4 + 实测七百二十七·点2：只有"骑乘棒绑上去的"才由我们负责弄下来。
        // 判据用 {@link #isOurRider}（乘客档 + 悬空鞍位档，龙那条她也算）。
        if (!isOurRider(m)) {
            player.m_5661_(Component.m_237113_(
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
        if (LINKS.containsKey(maid.m_20148_())) {
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
            // force = true：跳过原版 canRide / canAddPassenger（javap 实证 m_7998_ 的 iload_2 ifne 直接跳门）
            boolean ok = false;
            try {
                // 【实测七百四十一·点4】她已经在这辆车上了？——玩家原话：「有的时候，女仆会自己
                // 坐到某个载具上，这个时候就会导致骑乘棒没有办法绑定女仆，女仆就会一直卡在车上不动。」
                //
                // 根因（javap 反编译原版 {@code Entity.startRiding(Entity,boolean)} 实证）：
                // 它**第一句**就是 {@code if (this.vehicle == vehicle) return false;}——她已经在这辆车上
                // 时恒返回 false。于是这里走"没能坐上去……再试一次？"分支、**不写链路表**；
                // 而她不写链路 = 我们的驱动不生效（"卡在车上不动"），并且 {@code isOurRider} 认不出她，
                // 连"右击让她下来"都做不到。她**自己坐上去**（SWB 的 VehicleEntity.interact 允许
                // Mob 上车；或原版别的路径）是很常见的场景。
                //
                // 修法：她已在这辆车上 → **直接采纳现状**当作绑定成功（不重坐），继续走下面那套
                // "坐姿 + 挪到驾驶位 + 写表 + 打标记"。若她坐在**别的**车上，则先把她请下来。
                if (maid.m_20202_() == mount) {
                    ok = true; // 已在车上 → 采纳
                    com.maidsmart.tool.PromaidLog.log("骑乘指挥棒", "她本来就在这辆车上 → 直接采纳绑定："
                            + com.maidsmart.tool.PromaidLog.nameOf(maid)
                            + " @ " + MaidRideKit.describe(mount));
                } else {
                    if (maid.m_20202_() != null) {
                        maid.m_8127_(); // 她坐在**别的**车上 → 先请下来
                    }
                    ok = maid.m_7998_(mount, true);
                }
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
        LINKS.put(maid.m_20148_(), new Link(maid, mount, player, chair, prevCmd, prevSitting));
        try {
            maid.getPersistentData().m_128359_(TAG_RIDE_MOUNT, maid.m_20148_() + "|"
                    + com.maidsmart.tool.PromaidLog.nameOf(maid) + "|" + mount.m_20148_());
        } catch (Throwable ignored) {
        }
        // 【实测七百二十九·点2·必补】坐骑侧也要留痕——**旧版从来没有写过它**（全树只有 remove、
        // 没有 put）。后果不只是"留痕白写"：重启后 {@link #restore} 只认"她还在马/车上"这一条
        // （{@code maid.m_20202_() != null}），而**龙那条她不是乘客，getVehicle() 恒为 null**
        // → 那条龙**重建不出链路，也就永远没人去撤它的发光标记**（玩家原话：「如果一只龙被绑定了
        // 以后并且上了光标，那就再也没有办法解除他身上的光标了」——这是根因）。写上去之后，
        // {@link #sweepStaleMarks} 才有"这只坐骑曾经被我们标记过"这个凭据，才能在链路丢失时
        // 兜底熄灯；{@link #restore} 也能靠它把龙的链路一起重建出来（见那里）。
        try {
            mount.getPersistentData().m_128359_(TAG_RIDE_MAID, mount.m_20148_() + "|"
                    + com.maidsmart.tool.PromaidLog.nameOf(maid) + "|" + maid.m_20148_());
        } catch (Throwable ignored) {
        }
        mark(maid);
        mark(mount);
        // 【实测七百二十七·点4】悬空鞍位（龙）那条：把"她挂在哪条龙上"同步给客户端，
        // 客户端才能按同一个算式与她同帧摆位（见 MaidSeatNetworking 与 onClientEntityTickPost）。
        if (chair) {
            syncSeat(maid, mount.m_19879_());
        }
        bubble(maid, chair ? "我坐它背上啦，它跟着你走～" : "坐稳啦，我们出发～");
        com.maidsmart.tool.PromaidLog.log("骑乘指挥棒", "绑定：主人=" + name(player)
                + " 女仆=" + com.maidsmart.tool.PromaidLog.nameOf(maid)
                + " 坐骑=" + MaidRideKit.describe(mount)
                + (chair ? "（悬空鞍位降级方案：不真骑，龙置跟随档，传送只传她）" : "")
                + "（速度倍率=" + MaidRideKit.fmt(MaidRideKit.speedModifierFor(mount, maid))
                + "，跟随距离=" + MaidRideKit.fmt(MaidRideKit.followDist()) + " 格）");
    }

    /** 解除：清表 + 下鞍 + 撤标记 */
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
        Link link = LINKS.remove(maid.m_20148_());
        // 【实测七百二十六·点3】下鞍 = 空战这一场遭遇结束：清掉爬升相位/盘旋高度（与扫帚 clearClimb
        // 同口径）。不清的话她下次再骑上去会带着上一场的盘旋高度/方位角。
        MaidAirCombat.clear(maid);
        Entity mount = link == null ? null : link.mount.get();
        // 【实测七百二十五·点1】解绑还原坐姿（绑前是站姿就还原站姿）——玩家的原版"坐下"指令 /
        // 手动让她坐下，都不会被我们吃掉。
        MaidMountCompat.setSitting(maid, link != null && link.prevSitting);
        // 【实测七百二十三】悬空鞍位（龙）那条：还原重力 + 还原龙的行动档。
        // 她压根不是乘客，所以下面那些"下鞍"动作对她无意义（原版 stopRiding 也会是空操作）。
        if (link != null && link.chair) {
            MaidMountCompat.setGravity(maid, true);
            MaidMountCompat.restoreDragonCommand(mount, link.dragonCommand);
            // 【实测七百二十七·点4】通知客户端解除镜像（否则客户端还在按旧配对摆位）
            syncSeat(maid, -1);
        }
        unmark(maid);
        unmark(mount);
        // 点3：解绑时顺手把"待选光标"痕迹也清掉（若还留着）
        try {
            maid.getPersistentData().m_128473_(TAG_PENDING_MARK);
        } catch (Throwable ignored) {
        }
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

    /**
     * 【实测七百一十六·点3】选中即亮"光标"——玩家原话："如果玩家用这根棒子选中了那个女仆/
     * 可骑乘坐骑，那就应该立刻显示光标，而不是坐上坐骑以后再显示。"
     *
     * <p>所以打标记的时机从"绑定成功"提前到"被选中"：选中的这一只立刻 {@code setGlowingTag(true)}
     * （同光灵箭那条渲染、金色描边），并写 {@link #TAG_PENDING_MARK} 留痕。取消选中/换选/
     * 配对完成/超时，都走 {@link #clearPendingMark} 撤掉——只撤我们自己打的那一下。
     */
    private static void setPending(ServerPlayer player, Entity e) {
        // 换选：先把上一只的"待选光标"撤掉，再给新的一只打上
        java.lang.ref.WeakReference<Entity> old = PENDING.get(player.m_20148_());
        if (old != null) {
            clearPendingMark(old.get());
        }
        PENDING.put(player.m_20148_(), new java.lang.ref.WeakReference<>(e));
        // 【实测七百二十九·点2】记下开始时刻——超时清理要用（见 PENDING_AT 的说明）。
        PENDING_AT.put(player.m_20148_(), System.currentTimeMillis());
        markPending(e);
    }

    private static Entity takePending(ServerPlayer player, boolean wantMaid) {
        java.lang.ref.WeakReference<Entity> ref = PENDING.remove(player.m_20148_());
        PENDING_AT.remove(player.m_20148_());
        Entity e = ref == null ? null : ref.get();
        if (e == null || !e.m_6084_() || e.m_9236_() != player.m_9236_()) {
            // 【实测七百二十九·点2】目标已经没了/换了维度：这一下右击等于白点，**必须把光标撤掉**。
            // 旧版这里直接 return null，那条实体身上的"待选发光"就永远留在它身上了（玩家原话：
            // 「再也没有办法解除他身上的光标了」——这是其中一条泄漏路径）。
            clearPendingMark(e);
            return null;
        }
        if (wantMaid != (e instanceof EntityMaid)) {
            PENDING.put(player.m_20148_(), new java.lang.ref.WeakReference<>(e)); // 不是想要的那类：放回去
            return null;
        }
        // 配对成功（这一只被取走）→ 撤掉它的"待选光标"（绑定后会由 mark() 打正式标记）
        clearPendingMark(e);
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
                clearPendingMark(e); // 点3：超时/消失也要把"待选光标"撤掉
                PENDING.remove(sp.m_20148_());
            }
        }
    }

    /** 玩家退服：清他的瞬时状态（链路本身按女仆清理） */
    public static void forgetPlayer(UUID playerId) {
        java.lang.ref.WeakReference<Entity> ref = PENDING.remove(playerId);
        PENDING_AT.remove(playerId); // 实测七百二十九·点2：时刻表一并清，免得残留旧时间戳
        if (ref != null) {
            clearPendingMark(ref.get()); // 点3：下线时把"待选光标"撤掉
        }
        DENY_LOG.remove(playerId);
    }

    /** 女仆卸载/被移出世界：清她的链路（与 GunnerTetherManager.forgetMaid 同款） */
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
                    mount.getPersistentData().m_128473_(TAG_RIDE_MAID);
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
     * 传送女仆不传送龙」）。与 {@link #isRideRider} 的区别正是"她是不是真乘客"——
     * 载具/兽那两条走 {@code startRiding}，龙这条不走。
     */
    public static boolean isDragonChairRider(EntityMaid maid) {
        try {
            if (maid == null) {
                return false;
            }
            Link link = LINKS.get(maid.m_20148_());
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
            Link link = LINKS.get(maid.m_20148_());
            if (link != null && link.chair) {
                return true; // 冰火传说的龙（悬空鞍位）
            }
            // 卓越前线的载具：她一定是乘客（走 startRiding）；链路表未重建的那一拍按痕迹 + 类型认
            Entity v = maid.m_20202_();
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

    /**
     * 【实测七百四十一·点4】她是不是"自己坐上了模组载具"——**不是**我们用指挥棒绑的，
     * 但她确实是卓越前线载具的乘客（{@code kindOf == VEHICLE}）。
     *
     * <h2>玩家原话</h2>
     * 「有的时候，女仆会自己坐到某个载具上，这个时候就会导致骑乘棒没有办法绑定女仆，
     *  女仆就会一直卡在车上不动。……我想的是在排班表里面的召她过来最好可以规避掉骑乘模式。
     *  直接把女仆自己召唤过来。」
     *
     * <h2>为什么这一档会"卡住"（两条链路都不认她）</h2>
     * <ul>
     *   <li>{@link #isSpecialMountRider} 要求 {@code link != null || isBatonBound}——她**没绑过**，
     *       所以 false；</li>
     *   <li>{@link MaidRideKit#isRideRider} 同样要求 {@code isBatonBound}——也是 false。</li>
     * </ul>
     * 于是排班表召唤落到 {@code summonOne} 最后那句 {@code maid.isPassenger()} 上 → 返回 3
     * （"状态豁免，保持原位"），玩家点了「传送到我身边」她却纹丝不动。这正是"一直卡在车上"。
     *
     * <h2>本档的口径</h2>
     * 与"特殊载具"（726）**故意不同**：那一档是她被棍子绑上去的（解绑要还原坐姿/标记），
     * 这一档她只是**恰好坐在上面**——所以召唤时**只需要让她下来 + 传人**，
     * 不去碰任何我们自己的标记（根本没有）。
     */
    public static boolean isSelfBoardedModMount(EntityMaid maid) {
        try {
            if (maid == null) {
                return false;
            }
            if (MaidRideKit.isBatonBound(maid) || LINKS.containsKey(maid.m_20148_())) {
                return false; // 是我们绑的 → 归 726 那一档管（要先干净解绑）
            }
            Entity v = maid.m_20202_();
            return v != null && MaidMountCompat.kindOf(v) == MaidMountCompat.Kind.VEHICLE;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 【实测七百四十一·点4】把她从"自己坐上去的模组载具"上请下来（只下鞍，不碰任何我们的标记）。
     * 供排班表的手动召唤在传送前调用。
     *
     * @return true = 现在她确实不在那辆车上了（本来就不在 / 已下来）
     */
    public static boolean dismountSelfBoarded(EntityMaid maid) {
        try {
            if (maid == null) {
                return true;
            }
            if (!isSelfBoardedModMount(maid)) {
                return true;
            }
            com.maidsmart.tool.PromaidLog.log("骑乘指挥棒", "排班表召唤：她本来自己坐在载具上（并非指挥棒绑定）"
                    + " → 先请她下来再单独传人（" + com.maidsmart.tool.PromaidLog.nameOf(maid) + "）");
            maid.m_8127_();
            return maid.m_20202_() == null;
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
                if (m != null && m.m_20148_().equals(mount.m_20148_())) {
                    EntityMaid maid = link.maid.get();
                    if (maid != null && maid.m_6084_()) {
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
            if (maid == null || maid.m_9236_().m_5776_()) {
                return; // 客户端那半不摆位（链路表只活在服务端）
            }
            Link link = LINKS.get(maid.m_20148_());
            if (link == null || !link.chair) {
                return;
            }
            Entity mount = link.mount.get();
            if (mount == null || !mount.m_6084_() || mount.m_9236_() != maid.m_9236_()) {
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
     * {@code setOldPosAndRot → fireEntityTickPre → e.m_8119_() → fireEntityTickPost → 摆乘客}，
     * 而**同一 tick 内龙与女仆谁先 tick 由实体 ID 决定**。{@link #onMaidTick} 挂在
     * {@code MaidTickEvent}（在她**自己的** {@code tick()} 里），所以：
     * <ul>
     *   <li>龙**先** tick：她 tick 时拿到的是龙的新位置 → 摆位正确；</li>
     *   <li>她**先** tick：她按龙的**旧**位置摆好，随后这一 tick 龙才飞到新位置 →
     *       她整整落后龙一帧（每拍 0.5~1 格，飞起来就是"明显的错位"）。</li>
     * </ul>
     * 也就是说 {@link #onMaidTick} 是"在**她**的 tick 里摆"，而真正稳的做法是"在**龙的**
     * tick 里摆"——不管谁先谁后，{@code TickEvent.EntityTickEvent} 对**两者**都发，谁后 tick 谁的
     * Post 就是这一帧的最后一次摆位，天然收敛到"她的位置 = 龙这一帧的最终鞍位"。
     *
     * <h2>为什么用 Post 而不是 Pre</h2>
     * Post 在 {@code e.m_8119_()} **返回之后**发（javap 实证），龙的 {@code setPos} 已经落地——这正是
     * 我们要的那一帧的最终位置。Pre 会拿到上一帧的位置，等于没修。
     *
     * <h2>成本</h2>
     * {@code TickEvent.EntityTickEvent} 对**每个实体每 tick** 都发，所以这里两道最便宜的守卫排在最前：
     * ① 她（{@code EntityMaid}）或 ② 龙（{@link MaidMountCompat#isDragon}）——其余实体一次
     * 方法调用就返回；真正的摆位只在"确实是悬空鞍位链路里的那两只之一"时才做。
     */
    public static void onEntityTickPost(Entity e) {
        try {
            if (e == null) {
                return;
            }
            // 【实测七百二十七·点4】客户端这一支：**悬空鞍位（龙）**那条她不是乘客、又没人摆位，
            // 位置只能靠原版限流位置包 + 客户端 lerp 插值 → 龙一飞就"位置严重改变和错乱"。
            // 这里用服务端同步过来的配对（{@link #SYNCED_CHAIRS}）在客户端把她按**同一个算式**
            // 摆到龙的当前（插值后）位置上，与服务端逐字同源、且与龙同帧。见 MaidSeatNetworking。
            if (e.m_9236_().m_5776_()) {
                onClientEntityTickPost(e);
                return;
            }
            if (!isEnabled() || LINKS.isEmpty()) {
                return;
            }
            // ① 她本人 tick 完 → 按龙的当前（可能刚更新）位置摆一次
            if (e instanceof EntityMaid maid) {
                Link link = LINKS.get(maid.m_20148_());
                if (link == null || !link.chair) {
                    return;
                }
                Entity mount = link.mount.get();
                if (mount == null || !mount.m_6084_() || mount.m_9236_() != maid.m_9236_()) {
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
                if (rider == null || rider.m_9236_() != e.m_9236_()) {
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
                Integer mountId = SYNCED_CHAIRS.get(maid.m_19879_());
                if (mountId == null) {
                    return;
                }
                Entity mount = maid.m_9236_().m_6815_(mountId);
                if (mount == null || !mount.m_6084_() || mount.m_9236_() != maid.m_9236_()) {
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
                    if (en.getValue() != null && en.getValue() == e.m_19879_()) {
                        maidId = en.getKey();
                        break;
                    }
                }
                if (maidId == null) {
                    return;
                }
                Entity rider = e.m_9236_().m_6815_(maidId);
                if (!(rider instanceof EntityMaid maid) || maid.m_9236_() != e.m_9236_()) {
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
            Long last = SEAT_LOG_AT.get(maid.m_20148_());
            if (last != null && now - last < 5000L) {
                return;
            }
            if (SEAT_LOG_AT.size() > 256) {
                SEAT_LOG_AT.clear();
            }
            SEAT_LOG_AT.put(maid.m_20148_(), now);
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
            net.minecraftforge.event.entity.player.PlayerEvent.StartTracking event) {
        try {
            if (!(event.getTarget() instanceof EntityMaid maid)) {
                return;
            }
            if (!(event.getEntity() instanceof ServerPlayer watcher)) {
                return;
            }
            Link link = LINKS.get(maid.m_20148_());
            Entity mount = link == null ? null : link.mount.get();
            if (link != null && link.chair && mount != null) {
                MaidSeatNetworking.sendTo(watcher, maid.m_19879_(), mount.m_19879_());
            } else {
                MaidSeatNetworking.sendTo(watcher, maid.m_19879_(), -1);
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
                if (maid == null || !maid.m_6084_() || mount == null || !mount.m_6084_()) {
                    deferred.add(maid);
                    continue;
                }
                if (maid.m_9236_() != mount.m_9236_()) {
                    deferred.add(maid);
                    continue;
                }
                // 【实测七百四十七】名单是在 747 才加的：升级前绑在不可驾载具上的链路要能自愈——
                // 每拍扫到时按"该解绑"处理（走 releaseMaidQuiet，重力/坐姿/龙的行动档都还原）。
                if (MaidMountCompat.isUnridable(mount)) {
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
                    // 主人不在（别的维度/离线）也不用收手——龙本来就不由我们驱动。
                    continue;
                }
                // 她不在它背上了（被别的模组拽下去 / 自己潜跳下鞍 / 原版把她踢下来）→ 解除
                if (maid.m_20202_() != mount) {
                    deferred.add(maid);
                    continue;
                }
                // 【实测七百二十五·点1】每拍复述坐姿：TLM 的任务里有几处会 her 清回站姿
                // （MaidBedTask/MaidJoyTask 等），一掉就变回站姿模型、看着又"站着骑"。
                // 与龙那一档的 freezeOnSeat 同口径，只是她这条是真乘客、不需要停导航。
                MaidMountCompat.setSitting(maid, true);
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
        MaidAirCombat.clear(maid); // 实测七百二十六·点3：自动解除同样作废这一场遭遇
        // 【实测七百三十】飞行坐骑的索敌器锁定也要跟着撤（与扫帚 stop() 里那一句同口径）
        com.maidsmart.combat.FlightTargeting.forget(maid.m_20148_());
        LEASH_LOGGED.remove(maid.m_20148_());
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
     * <p>目的地来源（按优先级，【实测七百一十六·点2】重排）：
     * <ol>
     *   <li><b>她自己的导航目标</b>（{@link MaidRideKit#ownNavigationTarget}）——这是"1:1 还原
     *       女仆原有走路逻辑"的那一档：我们的单兵战术（core 230）与 TLM 跟随（core 3）都照常
     *       在她自己的 {@code PathNavigation} 上写目的地，只是位移被她"是乘客"这一条吃掉。
     *       把这个点原样转达给坐骑，坐骑就按她原本的近战/远程走位跑（马这种只会跑的坐骑
     *       由此完全复现）。</li>
     *   <li>退而读脑里的 {@code WALK_TARGET}（任务/跟随行为写的走位记忆——她自己的导航
     *       没被写时用它兜底）；</li>
     *   <li>再退就用主人当前的位置（离主人超过 followDist 才喂）。</li>
     * </ol>
     * 目标够近就 {@code stop()}——让坐骑原地站住，别顶着自己人打转。
     */
    private static void drive(EntityMaid maid, Entity mount, ServerPlayer owner) {
        try {
            // 【实测七百四十一·点3】刷新"当前刻"：机头瞄准请求带几拍时效（见 MaidMountCompat.NOSE_AIM），
            // 由这里统一喂，免得本类去依赖任何一侧的 level 状态。
            MaidMountCompat.markTick(maid.m_9236_().m_46467_());
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
                if (foe != null && maid.m_20270_(owner) > FlightTargeting.RANGE) {
                    if (LEASH_LOGGED.add(maid.m_20148_())) {
                        MaidMountCompat.logDrive(mount, "主人超出接敌半径（"
                                + (long) maid.m_20270_(owner) + "格 > " + (long) FlightTargeting.RANGE
                                + "格）→ 放弃敌人、转去追主人");
                    }
                    foe = null;
                } else if (foe != null) {
                    LEASH_LOGGED.remove(maid.m_20148_());
                }
                Vec3 air;
                if (foe != null && foe.m_6084_() && foe.m_9236_() == maid.m_9236_()) {
                    air = MaidAirCombat.combatTarget(maid, foe);
                } else {
                    MaidAirCombat.clear(maid); // 没目标 → 这一场遭遇作废，下一场重新起手
                    // 【实测七百三十八·玩家坐副驾】玩家原话：「如果女仆乘坐的是直升机且玩家坐
                    // 副驾驶……导致女仆必须要一直往上飞。建议改为玩家乘坐以后就悬停。」
                    // 根因：跟随档的高度基准是**主人的 Y**，玩家一上机，owner.getY() == 机身自己的
                    // Y → 目标高度 = 她自己的高度 + followAlt，每拍 +N 的棘轮，永远往上飞。
                    // 所以"机上有玩家"这一档不追任何基准，直接悬停在她现在的位置（水平+竖直都保持）；
                    // 接敌那一支在上面，完全不受影响（玩家要的"锁敌的时候正常"）。
                    if (MaidMountCompat.hasPlayerAboard(mount)) {
                        // 【实测七百四十一·点2a】基准是**载具**（她自己是乘客，座位 Y 比机身
                        // 高约 1.5 格 → 用它当基准就是"永久缓慢爬升"，见 holdHere 的注释）。
                        air = MaidAirCombat.holdHere(maid, mount);
                        if (air != null) {
                            MaidRideKit.feedNavigation(mount, air, mod, maid);
                            return;
                        }
                    }
                    // 跟随：**高度基准永远是主人**（比主人高三格，七百三十三 玩家纠正口径）。
                    // 【实测七百三十四·棘轮修正】七百三十三 这一档"够近就原地悬停"错误地把基准
                    // 传成了**她自己的 Y**，于是目标 = 她现在的高度 + 3 ——她每贴到一次就被抬高
                    // 3 格、目标跟着水涨船高，形成**每拍 +3 的棘轮**，几秒就窜到主人上方十几二十格
                    // （玩家实机反馈）。两档的差别只应在**水平位置**：够远飞到他正上方，够近就守住
                    // 当前水平位置；**竖直基准一律是主人的 Y**，任何情况下都不拿她自己的高度当参照。
                    if (horizontalDist(mount, owner) > MaidRideKit.followDist()) {
                        air = MaidAirCombat.hoverAt(maid, owner.m_20185_(), owner.m_20189_(), owner.m_20186_());
                    } else {
                        air = MaidAirCombat.hoverAt(maid, maid.m_20185_(), maid.m_20189_(), owner.m_20186_());
                    }
                }
                if (air != null) {
                    MaidRideKit.feedNavigation(mount, air, mod, maid);
                    // 朝向交给驾驶层：飞行档按"目标方位 vs 机头"写鼠标 X 通道（driveFlight 里）
                    return;
                }
            }
            // 【实测七百三十八·地面载具接敌】玩家原话：「女仆在骑乘陆地载具的时候，走位的方向
            // 仍然是朝着主人方向。……主要是在面对敌人的时候，主人坐上车以后，车还是一动不动，
            // 就很难绷了。能不能在接敌后也采用直升机/扫帚那种绕圈的方式呢？撞墙以后自动反方向。」
            // 根因：接敌机动那一整套此前**只挂在飞行载具那一支**上，地面载具永远走"跟着主人走"
            // 的兜底——主人一上车，距离≈0 → 立刻判"到了" → 停车，敌人再近也不动。
            // 这里给地面载具补同一套绕圈（同一段 CombatOrbit 状态机、同一个"追逐式胡萝卜"），
            // 高度贴着敌人、撞墙/卡住自动反向（见 MaidAirCombat.groundOrbitTarget 与
            // MaidMountCompat.groundReverse）。
            if (MaidAirCombat.enabled() && MaidMountCompat.kindOf(mount) == MaidMountCompat.Kind.VEHICLE
                    && !MaidMountCompat.isFlyingVehicle(mount)) {
                LivingEntity gfoe = FlightTargeting.resolve(maid);
                if (gfoe == null) {
                    gfoe = targetOf(maid);
                }
                if (gfoe != null && maid.m_20280_(owner) > FlightTargeting.RANGE) {
                    gfoe = null; // 与飞行档同一道"主人不在场就收手"的闸（口径只有一处）
                }
                if (gfoe != null && gfoe.m_6084_() && gfoe.m_9236_() == maid.m_9236_()) {
                    Vec3 gair = MaidAirCombat.groundOrbitTarget(maid, mount, gfoe);
                    if (gair != null) {
                        // 【实测七百三十九·点3】绕圈走**专用驱动档**：不刹车、不因转向收油
                        // （见 MaidMountCompat.driveOrbit 的注释——停车带的车速前瞻会每拍命中
                        // "前方的胡萝卜"，用普通档就是"冲一下刹一下、最后撞上去"）。
                        // 反射拿不到（返回 false）就退回通用档，行为与 738 一致。
                        if (!MaidMountCompat.driveOrbit(mount, gair, mod, maid)) {
                            MaidRideKit.feedNavigation(mount, gair, mod, maid);
                        }
                        return;
                    }
                } else {
                    MaidAirCombat.clear(maid);
                }
            }
            // 【实测七百四十九·点1：默认档 = 跟着主人走，够近就停】
            // 玩家原话：「我发现最新版本女仆在骑乘马匹的时候，对于主人的寻路有问题。总是喜欢乱窜，
            // 明明这是一个旧版本解决的问题，但为什么在新版本复发了呢？要求是平时马头朝着主人移动
            // 靠近了就停止。其他时候就动用该模式下的运动逻辑。（因为骑的马仍然是陆地载具，跟女仆
            // 自己在地上走区别不大，所以直接采用女仆的运动逻辑。）」
            //
            // 根因：716 立的规矩是「① 她自己的导航目标最优先」，而**她自己的导航目标在"没有模式
            // 在指挥"时并不为空**——TLM 的跟随（MaidFollowOwnerTask）与她大脑里的 WALK_TARGET
            // 一直在写，于是坐骑永远在追一个"她自己的走位点"，而不是"主人"。她成了乘客、位移被
            // rideTick 吃掉，那些走位点又每拍变，坐骑就表现为**乱窜**。
            //
            // 新口径（就是玩家这句话的字面）：
            //   · **她在某个"模式"里**（任务不是 idle：挖矿/伐木/建造/战斗…）→ 那个模式有自己的
            //     运动逻辑，照旧把它转达给坐骑（下面 ①② 两条，716 的"1:1 还原走位"只在这一档生效）。
            //   · **没有模式**（idle / 纯跟随）→ 不读她的走位点，直接**朝主人去、够近就停**，
            //     停下时把马头转向主人（玩家要的"马头朝着主人…靠近了就停止"）。
            //
            // 【接敌也算"模式"】她此刻**有攻击目标**时同样走模式档——"骑马远程"的走位（绕圈/拉开）
            // 本来就在她自己的导航上（724 点5 的口径），归到"跟随主人"会把接敌走位整条抹掉。
            boolean fighting = targetOf(maid) != null;
            boolean modeMove = MaidRideKit.hasModeMovement(maid) || fighting;
            // 【实测七百二十四·点5 保留·实测七百四十九 前移】骑载具时更主动接敌：她此刻没目标就补一次
            // 索敌评估（像骑马远程一样主动找目标）。**必须在"默认档提前 return"之前跑**——
            // 旧位置在方法末尾，而 749 给默认档加了"够近就直接 return"，那之后这里就永远到不了，
            // 749 的"够近就停"会把 724 点5 的主动索敌整个吃掉（坦克停在你身边、再也不去找敌人）。
            if (mount instanceof net.minecraft.world.entity.Entity
                    && MaidMountCompat.kindOf(mount) == MaidMountCompat.Kind.VEHICLE
                    && !fighting) {
                tryEngageRider(maid);
            }
            // ① 模式下的走位意图（1:1 还原）——只在"确实有模式在指挥"时才认
            Vec3 target = modeMove ? MaidRideKit.ownNavigationTarget(maid) : null;
            // 【实测七百五十三·点3】① 也要过**同一道**"这是跟随、不是任务走位"的筛子。
            //
            // 玩家原话：「女仆在骑乘陆地载具的时候战斗会进行移动，但是平时不会跟随主人。」
            // ——战斗时 fighting=true，① 拿到的是接敌走位（照常工作）；**平时**她多半处在一个
            // 非空闲任务里（战斗任务的待机段 / 农田 / 挖矿），modeMove 于是为真，而 751 只在
            // **②**（brain 的 WALK_TARGET）上做了"指着主人那条不算任务"的剔除，**① 没做**。
            // 她**是乘客**、位移被 rideTick 吃掉，TLM 的跟随与 MoveToTargetSink 仍然每拍在她自己的
            // PathNavigation 上留着一条**永远走不完**的路（乘客走不动 → isDone() 恒 false →
            // getTargetPos() 恒非空），于是 ① 永远返回一个"其实是在跟主人/早已过时"的点，
            // 座骑就朝着它蹭过去、然后站住——**永远走不到下面 ④ 的"跟着主人"那一档**。
            // 判据与 ② 那条完全同源（同一句玩家要求、同一个"跟随 ≠ 任务"的口径），只是 ① 拿到的
            // 是**坐标**不是 tracker，所以按"是不是就落在主人身上"来认（见 navTargetIsOwner）。
            if (target != null && navTargetIsOwner(owner, target)) {
                target = null; // 那不是"这个模式要去的地方"，是跟随 → 落到 ④
            }
            // ② 她的走位记忆（任务/跟随写在这里）——同上，只属于"模式"那一档
            if (target == null && modeMove) {
                try {
                    var wt = maid.m_6274_().m_21952_(net.minecraft.world.entity.ai.memory.MemoryModuleType.f_26370_);
                    // 【实测七百五十一，七百五十二保留】模式档里**不认**"指着主人那一条"：
                    // TLM 的跟随（MaidFollowOwnerTask，core 3，任何活动都在跑）在她离主人
                    // 62~66 格时会 setWalkAndLookTargetMemories(主人)；那一格是"跟随"，不是
                    // "这个模式要去的地方"。剔除它之后本拍就没有"模式走位点"了，落到下面 ③
                    // 的**默认档：跟着主人**（七百五十一 那版把它当"原地待命"是错的，见 ③ 注释）。
                    if (wt.isPresent() && !walkTargetIsOwner(owner, wt.get())) {
                        Vec3 t = wt.get().m_26420_().m_7024_();
                        if (t != null) {
                            target = t;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            // ③ 【实测七百五十二更正】模式这一拍**没有**给出走位点（站桩活贴方块 / 农田暂时
            // 没活 / 接敌走位的间隙），或者她**本来就没有模式**（空闲）→ **默认档：跟着主人走**
            // （够远才喂，够近就停 + 转向主人）。
            //
            // 【为什么必须在这里更正】七百五十一 把"没有模式"和"有模式但这一拍没给走位点"
            // 混为一谈，让后者**原地待命**——玩家原话（七百五十二）：「我要的是女仆在空闲状态
            // 或者处于某种模式下（但是没有对应的任务目标）的时候是跟随主人。……但是你现在整的
            // 只有空闲状态下会跟随玩家，其他时候女仆就站在原地一动不动了」。口径就是这句的字面：
            // **只有"这个任务这一拍真有目标"才走模式自己的路**（上面 ①②），其余一律回到"跟着
            // 主人"这一档——不是"待命"。玩家又说这是"做模组载具兼容之前就已经修好"的行为，
            // 而兼容前（716）这一档的本体正是"都没有 → 跟主人走"。
            // ④ 默认档：跟着主人（够远才喂，够近就停 + 转向主人）
            if (target == null) {
                double d = horizontalDist(mount, owner);
                if (d > MaidRideKit.followDist()) {
                    target = owner.m_20182_();
                } else {
                    MaidRideKit.stopNavigation(mount);
                    faceOwner(mount, owner);
                    return;
                }
            }
            if (target == null) {
                MaidRideKit.stopNavigation(mount);
                return;
            }
            // 【实测七百四十九·点3】"别叠罗汉"：与扫帚**同一套标准**（MaidRideKit.separateAim）。
            // 目标点附近有别的"女仆正骑着的坐骑"时，把它朝远离同伴的方向推出去一点。
            target = separateFromPeers(maid, mount, target, modeMove, fighting);
            // 【实测七百二十四·点2】停车距离按**车体尺寸**：坦克这种大车若还用旧的固定 1.5 格，
            // 等于"顶到主人身上才停"——玩家原话「会直接把主人撞倒。应与主人拉开距离才对」。
            // 模组载具用 stopSlackFor(车体半宽)；并与"跟随距离"取大（别比主人自己设的还近）。
            double slack = stopSlackFor(mount);
            if (horizontalDist(mount, target) <= slack) {
                MaidRideKit.stopNavigation(mount);
                // 【实测七百四十九·点1】停下来的那一拍把马头转向主人——玩家要的"马头朝着主人"。
                faceOwner(mount, owner);
                return;
            }
            MaidRideKit.feedNavigation(mount, target, mod, maid);
            // 【实测七百二十四·点5】主动索敌已前移到本方法开头（见那里"749 前移"的说明）——
            // 749 给默认档加了"够近就 return"，留在这里会被那条提前返回吃掉。
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

    /**
     * v1.3.0(beta) 实测七百四十九【点2：收进魂符的**那一刻**就撤掉骑乘光标】。
     *
     * <h2>玩家原话</h2>
     * 「目前去除女仆和坐骑身上的光标必须是女仆骑乘到坐骑上，然后解绑一次才能去除光标，这样子
     *  明显太麻烦了，能不能把女仆收进魂符以后就立刻解除一次光标呢？」
     *
     * <h2>为什么"收符"这一下必须接</h2>
     * 光标的真身是**原版发光标记**（{@code setGlowingTag}）——它会**跟着 NBT 一起进魂符**
     * （javap 实证：{@code Entity.saveWithoutId}/{@code load} 读写 {@code "Glowing"} 键）。
     * 于是"收进符里 → 再放出来"这套最常见的操作，会让**她和那只坐骑**带着上一轮的光标回来，
     * 而链路表在收符那一刻就断了 → 没有任何入口再去熄灯，玩家只能"重新骑上去再解绑一次"。
     *
     * <h2>为什么挂在 ToItem（收进去）而不是"放出来"那一头</h2>
     * 放出来那头 729 已经有兜底（{@link #sweepStaleMarks} 每 5 秒扫一遍死标记）。但玩家要的是
     * **收符时立刻**干净——魂符收进去的东西会被存档/交易/搬运，等 5 秒甚至等一个存档周期都不合适；
     * 而且"符里的女仆"根本不在世界里，扫描扫不到她。所以在**收进去那一刻**就把该清的清掉。
     *
     * <h2>清什么、绝不动什么</h2>
     * <ul>
     *   <li><b>先解绑</b>（{@code releaseMaidQuiet}）：坐姿 / 重力 / 龙的行动档 / 链路表 / 留痕键
     *       全部按正常解绑口径还原——这是玩家说的"立刻解除一次"。</li>
     *   <li><b>再熄灯**双方**</b>：她和坐骑各自 {@code setGlowingTag(false)}。坐骑侧在解绑时
     *       已经 unmark 过，这里再补一次是幂等的兜底（龙那条链路表可能已经先断了）。</li>
     *   <li><b>只碰带我们留痕的那两个</b>：判据是 {@code TAG_RIDE_MOUNT} / {@code TAG_RIDE_MAID} /
     *       {@code TAG_PENDING_MARK} 这三个**我们的私有键**。她自己中了光灵箭（GLOWING 效果）时
     *       {@code setGlowingTag(false)} 内部会重新求值 {@code isCurrentlyGlowing()}，
     *       **不会**把与光灵箭无关的那一份清掉（javap 实证）。</li>
     * </ul>
     *
     * <p>【1.20.1 的事件 API】与 {@code MaidSoulSpellGuard.onSoulSpellToItem} 同款：
     * TLM 的 {@code storeMaidData} 会发 {@code MaidAndItemTransformEvent.ToItem}（反编译实证）。
     */
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onSoulCharmStore(
            com.github.tartaricacid.touhoulittlemaid.api.event.MaidAndItemTransformEvent.ToItem event) {
        try {
            if (!isEnabled()) {
                return;
            }
            EntityMaid maid = event.getMaid();
            if (maid == null || maid.m_9236_().m_5776_()) {
                return;
            }
            // 【先抓住坐骑】解绑会把链路表里那条删掉、也会把她从车上请下来，之后再找就晚了。
            // 两条来源都试：① 链路表（正常绑定）② 她此刻骑着的（存档残留 / 重启后没重建）。
            Entity mount = null;
            Link link = LINKS.get(maid.m_20148_());
            if (link != null) {
                mount = link.mount.get();
            }
            if (mount == null) {
                Entity v = maid.m_20202_();
                if (v != null && !(v instanceof EntityMaid)) {
                    mount = v;
                }
            }
            // ① 她身上有我们的链路 → 立刻解除一次（还原坐姿/重力/行动档 + 撤她自己的标记）。
            //    玩家原话要的就是这一下：「能不能把女仆收进魂符以后就立刻解除一次光标呢？」
            if (link != null) {
                releaseMaidQuiet(maid);
                com.maidsmart.tool.PromaidLog.log("骑乘指挥棒", "收进魂符：立刻解除一次骑乘链路（"
                        + com.maidsmart.tool.PromaidLog.nameOf(maid) + "）→ 双方光标已撤");
            }
            // ② 兜底：链路表里没有她、但她身上还带着留痕（存档残留 / 重启后没重建）→ 同样熄灯
            try {
                if (!maid.getPersistentData().m_128461_(TAG_RIDE_MOUNT).isEmpty()) {
                    maid.getPersistentData().m_128473_(TAG_RIDE_MOUNT);
                    unmark(maid);
                }
            } catch (Throwable ignored) {
            }
            // ③ "待选光标"（只点了她 / 只点了坐骑、还没配对就收符）也要撤
            clearPendingMark(maid);
            unmark(maid);
            // ④ **坐骑侧**的光标一起撤——玩家点名的就是"女仆**和坐骑**身上的光标"。
            //    只动我们标记过的那只（TAG_RIDE_MAID 是我们的私有键），别的模组/原版不会写它，
            //    所以"恰好路过的另一只发光坐骑"绝不会被误伤。
            if (mount != null) {
                try {
                    mount.getPersistentData().m_128473_(TAG_RIDE_MAID);
                } catch (Throwable ignored) {
                }
                unmark(mount);
                com.maidsmart.tool.PromaidLog.log("骑乘指挥棒", "收进魂符：坐骑侧光标已撤："
                        + MaidRideKit.describe(mount));
            }
            // ⑤ **收进去的那份 NBT 也要熄灯**——这一条是 749 漏掉的地方，也是玩家报的
            //    「收回魂符只清了坐骑的光标，女仆自己没有」的真正原因。
            //    TLM 的 {@code ItemSmartSlab.storeMaidData} 先
            //    {@code maid.saveWithoutId(tag)} **再** 发 ToItem 事件，而符里的女仆 NBT
            //    就是这个 event.getData()（反编译实证）。所以只对**世界里的活实体**
            //    setGlowingTag(false)，对"将来从符里放出来的那只"一个字节都不起作用——
            //    她自己中了光灵箭那份不在此列（只改 {@code "Glowing"} 这一个键，
            //    放出来时 load 会重新求值，见 {@code Entity.isCurrentlyGlowing}）。
            clearGlowInStoredData(event.getData());
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【实测七百五十一】把"收进符里那一份女仆 NBT"的发光标记清掉。
     *
     * <p>键名来自反编译实证：{@code Entity.saveWithoutId} 把 {@code hasGlowingTag} 写进
     * {@code "Glowing"}，{@code Entity.load} 再读回来调 {@code setGlowingTag}
     * （javap 两版一致）。只动这一个键、且只在它确实是 {@code true} 时改，
     * 其余 NBT 一个字节不碰。
     */
    private static void clearGlowInStoredData(net.minecraft.nbt.CompoundTag data) {
        try {
            if (data != null && data.m_128441_("Glowing")) {
                data.m_128379_("Glowing", false);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【实测七百四十九·点1】停在"够近"档时把**马头转向主人**——玩家原话「平时马头朝着主人移动靠近了就停止」。
     *
     * <p>为什么需要单独这一步：坐骑走的是它自己的 {@code PathNavigation} / {@code MoveControl}，
     * 「停」只是不再喂新目标点；速度归零之后它的朝向**停在最后一段位移的方向**上（侧对甚至背对主人）。
     * 这里在"已经停住"的那一拍补一次朝向设定，让它对着主人。
     *
     * <p>【只动朝向、不动位移】{@code setYRot}/{@code setYHeadRot} 写在停住之后——不产生位移，
     * 也不进 {@code MoveControl}，所以既不会把停住的车再推出去，也不会和寻路抢方向。
     * 【不打扰玩家自己开的车】只有"没有玩家在驾"时才转（玩家握着方向盘时他的朝向说了算）。
     *
     * <p>【1.20.1 的 SRG 名】{@code m_146922_ = setYRot}、{@code m_5618_ = setYHeadRot}、
     * {@code m_6688_ = getControllingPassenger}、{@code m_146908_ = getYRot}。
     */
    private static void faceOwner(Entity mount, ServerPlayer owner) {
        try {
            if (mount == null || owner == null) {
                return;
            }
            // 玩家在驾这台车 → 朝向由玩家决定，我们一个字不碰
            try {
                if (mount.m_6688_() != null) {
                    return;
                }
            } catch (Throwable ignored) {
            }
            double dx = owner.m_20185_() - mount.m_20185_();
            double dz = owner.m_20189_() - mount.m_20189_();
            if (dx * dx + dz * dz < 1.0E-4) {
                return; // 已经重合：没有可靠的"朝向主人"方向，保持原样
            }
            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx))) - 90.0f; // 与 feedNavigation 同一套 yaw 口径
            mount.m_146922_(yaw);
            mount.m_5618_(yaw);
            // 同步给她的身体（她是乘客，模型朝向跟着她自己的 yRot 走）——不转的话会"车头对着主人、
            // 人还侧着坐"。
            try {
                if (!mount.m_20197_().isEmpty()) {
                    net.minecraft.world.entity.Entity first = mount.m_20197_().get(0);
                    first.m_146922_(yaw);
                    first.m_5618_(yaw);
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【实测七百四十九·点3】坐骑侧的"别叠罗汉"：与扫帚**同一个标准**
     * （{@link MaidRideKit#separateAim}，连四个间距常量都是同一份）。
     *
     * <p>同伴 = **别的女仆正骑着的坐骑**。按玩家的口径（「不管是扫帚还是坐骑，都需要防叠罗汉」），
     * 空着的坐骑是"她正要去骑的目标"、玩家自己骑的那匹也不是她要叠的东西——与扫帚那边
     * {@code carriesMaid} 同一套取舍。
     *
     * <p>【只在"没有模式"时生效】有模式在指挥时（挖矿/建造/战斗走位），她的目标点是那个模式
     * 真正要去的地方，被互斥推偏等于让工作走位失真——所以那一档不推，交回模式自己。
     *
     * @param combat 【与扫帚同口径】这一拍算不算"接敌"：她此刻有攻击目标 → 用更大的一档间距。
     */
    private static Vec3 separateFromPeers(EntityMaid maid, Entity mount, Vec3 aim, boolean modeMove, boolean combat) {
        try {
            if (aim == null || mount == null || modeMove) {
                return aim;
            }
            if (!(mount.m_9236_() instanceof net.minecraft.server.level.ServerLevel level)) {
                return aim;
            }
            double sepR = MaidRideKit.SEP_R_COMBAT;
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                    aim.f_82479_ - sepR, aim.f_82480_ - sepR, aim.f_82481_ - sepR,
                    aim.f_82479_ + sepR, aim.f_82480_ + sepR, aim.f_82481_ + sepR);
            java.util.List<Vec3> peers = new java.util.ArrayList<>();
            for (Entity other : level.m_6443_(Entity.class, box,
                    e -> e != mount && e.m_6084_() && isMaidMount(e))) {
                peers.add(other.m_20182_());
            }
            return MaidRideKit.separateAim(aim, MaidRideKit.ridePhase(maid.m_20148_()), peers, combat);
        } catch (Throwable ignored) {
            return aim;
        }
    }

    /**
     * 【实测七百四十九·点3】"这只坐骑是女仆在骑着的"——{@link #separateFromPeers} 的障碍判据。
     * 与扫帚那边 {@code carriesMaid} 逐字同口径：普通坐骑看乘客，悬空鞍位（龙）看我们的链路表。
     */
    private static boolean isMaidMount(Entity e) {
        try {
            if (e instanceof EntityMaid) {
                return false; // 女仆自己不是坐骑
            }
            if (MaidRideKit.riderOf(e) != null) {
                return true; // 普通坐骑：背上有女仆
            }
            if (chairRiderOf(e) != null) {
                return true; // 悬空鞍位（龙）：链路表说这条龙驮着谁
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
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
            Long last = AMMO_FEED_AT.get(mount.m_20148_());
            if (last != null && now - last < AMMO_FEED_INTERVAL_MS) {
                return;
            }
            if (AMMO_FEED_AT.size() > 256) {
                AMMO_FEED_AT.clear();
            }
            AMMO_FEED_AT.put(mount.m_20148_(), now);
            MaidMountCompat.feedVehicleAmmo(mount, maid);
        } catch (Throwable ignored) {
        }
    }

    private static void tryEngageRider(EntityMaid maid) {
        try {
            long now = System.currentTimeMillis();
            Long last = ENGAGE_AT.get(maid.m_20148_());
            if (last != null && now - last < 1000L) {
                return; // 1 秒最多一次（索敌本身有开销，且战斗链路会自己维持目标）
            }
            if (ENGAGE_AT.size() > 256) {
                ENGAGE_AT.clear();
            }
            ENGAGE_AT.put(maid.m_20148_(), now);
            com.maidsmart.combat.AutoCombatSwitch.tryEngagePublic(maid);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【实测七百五十一】这条走位记忆是不是"指着主人"——TLM 跟随（{@code MaidFollowOwnerTask}）
     * 在她离主人过远时把主人本身写成 {@code WALK_TARGET}（{@code EntityTracker}，与
     * {@code BehaviorUtils.setWalkAndLookTargetMemories(maid, owner, …)} 同源，反编译实证）。
     *
     * <p>为什么要单独认这一条：那个目标是**跟随**写下的，不是"她这个模式要去的地方"。
     * 坐骑的模式档把它当任务走位转达出去，就变成"农田/挖矿/站桩任务中的坐骑照样朝主人走"
     * （玩家原话：「行动轨迹应该是女仆在此模式下原有的轨迹，而现在仍然是朝着主人」）。
     * 判据只认 {@code EntityTracker} 且实体 UUID == 主人——**方块型的走位点
     * （{@code BlockPosTracker}，TLM 农田/挖矿/我们的行为都用它）一律不算**，绝不误伤真正的任务走位。
     */
    private static boolean walkTargetIsOwner(LivingEntity owner,
                                             net.minecraft.world.entity.ai.memory.WalkTarget wt) {
        try {
            if (owner == null || wt == null) {
                return false;
            }
            net.minecraft.world.entity.ai.behavior.PositionTracker tracker = wt.m_26420_();
            if (tracker instanceof net.minecraft.world.entity.ai.behavior.EntityTracker et) {
                return et.m_147481_() != null && et.m_147481_().m_20148_().equals(owner.m_20148_());
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 【实测七百五十三·点3】① 那条走位点是不是"其实落在主人身上"——与
     * {@link #walkTargetIsOwner} **同一条口径**（跟随 ≠ 任务走位），只是那一头拿到的是
     * {@code WalkTarget}／{@code EntityTracker}（能直接问实体），
     * 这一头是 {@link MaidRideKit#ownNavigationTarget} 给的**坐标**，所以按"是不是就落在
     * 主人身上"来认：水平距离在 {@link #NAV_OWNER_EPS} 格以内即视为"跟随着主人"。
     *
     * <p>为什么①也必须过这道筛子：她**是乘客**、位移被 {@code rideTick} 吃掉，TLM 的跟随
     * （{@code MaidFollowOwnerTask}）与 {@code MoveToTargetSink} 会**每拍**在她自己的
     * {@code PathNavigation} 上留一条走不完的路（乘客走不动 → {@code isDone()} 恒 false →
     * {@code getTargetPos()} 恒非空）。751 只在②上剔了"指着主人那条"，① 就成了没堵上的后门：
     * 非空闲任务里（战斗任务的待机段等）座骑永远朝那个点蹭、走不到 ④ 的"跟着主人"。
     */
    private static boolean navTargetIsOwner(LivingEntity owner, Vec3 navTarget) {
        try {
            if (owner == null || navTarget == null) {
                return false;
            }
            double dx = navTarget.f_82479_ - owner.m_20185_();
            double dz = navTarget.f_82481_ - owner.m_20189_();
            return Math.sqrt(dx * dx + dz * dz) <= NAV_OWNER_EPS;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** "① 的走位点落在主人身上"的判定容差（格）：她自己那条导航的走位点本来就是**方块中心**
     *  （{@code ownNavigationTarget} 取 {@code +0.5}），主人站的那一格与它最多差半格多，
     *  所以给 1.5 格——够容下取整误差，又远小于任何真实任务点（农田/矿点都在数格开外）。 */
    private static final double NAV_OWNER_EPS = 1.5;

    /** 女仆当前的攻击目标（brain 的 ATTACK_TARGET 优先，退回实体层 target）。 */
    private static LivingEntity targetOf(EntityMaid maid) {
        try {
            java.util.Optional<LivingEntity> mem = maid.m_6274_().m_21952_(
                    net.minecraft.world.entity.ai.memory.MemoryModuleType.f_26372_);
            if (mem != null && mem.isPresent()) {
                LivingEntity le = mem.get();
                if (le != null && le.m_6084_()) {
                    return le;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            return maid.m_5448_();
        } catch (Throwable ignored) {
            return null;
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
                    // 【实测七百二十五·点1】重载后重建链路同样保持坐姿（与 bind 同口径；
                    // 这里她是乘客，绑前值无从得知，就用"当前值"当还原目标，最保守）。
                    LINKS.put(maid.m_20148_(), new Link(maid, mount, sp, MaidMountCompat.isSitting(maid)));
                    MaidMountCompat.setSitting(maid, true);
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

    /**
     * 【实测七百一十六·点3】"待选光标"：选中即亮（未绑定也亮）。与正式链路标记用同一个
     * 原版发光渲染（金色描边），只是用 {@link #TAG_PENDING_MARK} 单独留痕，方便"只撤我们
     * 打的那一下"。
     */
    private static void markPending(Entity e) {
        if (e == null) {
            return;
        }
        try {
            e.m_146915_(true);
            e.getPersistentData().m_128359_(TAG_PENDING_MARK, "1");
        } catch (Throwable ignored) {
        }
    }

    /** 撤掉"待选光标"：只在她身上没有正式链路标记时才熄灯（防把已绑定的标记一起撤掉） */
    private static void clearPendingMark(Entity e) {
        if (e == null) {
            return;
        }
        try {
            boolean pending = e.getPersistentData().m_128441_(TAG_PENDING_MARK);
            if (!pending) {
                return;
            }
            e.getPersistentData().m_128473_(TAG_PENDING_MARK);
            // 她若已绑定（正式标记仍在）就不熄灯；否则撤掉待选的光
            if (e instanceof EntityMaid m && LINKS.containsKey(m.m_20148_())) {
                return;
            }
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
            for (ServerLevel level : server.m_129785_()) {
                for (Entity e : com.maidsmart.tool.EntitySnapshot.of(level)) {
                    if (e instanceof EntityMaid) {
                        continue; // 女仆那条由链路表自己的生命周期管（她不是"坐骑侧留痕"）
                    }
                    boolean ourTag = false;
                    boolean pendingTag = false;
                    try {
                        pendingTag = e.getPersistentData().m_128471_(TAG_PENDING_MARK);
                        ourTag = pendingTag
                                || !e.getPersistentData().m_128461_(TAG_RIDE_MAID).isEmpty();
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (!ourTag) {
                        continue;
                    }
                    // ① 待选光标：还挂在某位玩家的待配对里吗？
                    if (pendingTag && !isPendingSomewhere(e)) {
                        clearPendingMark(e);
                        if (e.m_142038_()) {
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
                        e.getPersistentData().m_128473_(TAG_RIDE_MAID);
                    } catch (Throwable ignored) {
                    }
                    if (e.m_142038_()) {
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
                if (p != null && p.m_20148_().equals(e.m_20148_())) {
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
                if (m != null && m.m_20148_().equals(mount.m_20148_())) {
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
