package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityBroom;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * v1.3.7 实测六百六十七【武装拴绳】——粉丝点单的「武装直升机二号位」（1.21.1 NeoForge 版）。
 *
 * 需求原文："众所周知酒狐会魔法，酒狐在空中飞的时候，是否可以让玩家挂在酒狐下方？让玩家能够
 * 与酒狐一同攻击，八宝粥行动那样武装直升机，玩家挂在2号位开火。这边要引入一个新道具，应该类似
 * 于一个不会扯断，而且长度只有一格的拴绳。可以将玩家和女仆锁在一起。使用方法跟拴绳一样，右击
 * 女仆就行。解除也是同样的方式。"
 *
 * ── 实现口径（为什么是"真乘客 + positionRider 定位"，而不是绳子拉力模拟）──
 * 把玩家 startRiding(force) 上女仆（她当载具），再在 {@link com.maidsmart.mixin.EntityGunnerHangMixin}
 * 里把乘客定位从"骑在头顶"改成"悬挂在下方 hang 格"（那个 mixin 注入的是 **Entity** 的
 * positionRider，不是 EntityMaid 的——理由见它的类注释：继承方法 @Inject 匹配不到，667 就是这么炸的）。选这个方案是因为：
 * <ul>
 *   <li>**位移零仿真**：乘客跟着载具走是原版机制（客户端插值、多人同步、俯冲/爬升/烟花加速
 *       全部自动正确），自己写"弹簧拉力"会跟玩家本地输入打架（橡皮筋 + 抖动）；</li>
 *   <li>**她照常飞行开火**：javap 实证 TLM {@code EntityMaid} 没有覆写
 *       {@code getControllingPassenger}——玩家乘客不会抢走驾驶权，她的大脑/远程空袭
 *       一切照旧，"一号位她开火、二号位你开火"就是这么来的；</li>
 *   <li>**骑乘关系零成本同步**：乘客关系由原版 SetPassengersPacket 同步，绳子渲染只要知道
 *       "谁是她的枪手"（S2C 包，见 GunnerTetherNetworking）；</li>
 *   <li>**"长度只有一格"**：默认悬挂距离 1.8 格（可调 0.5~4.0），绳子画在女仆腰部与玩家手之间。</li>
 * </ul>
 *
 * ── 【实测六百七十二：座位是"两半"的，且她不再替你定高度】──
 * 扫帚上的换座是**一对互逆**的动作，都在右击这一次里完成（不依赖 TLM 的事件泄漏）：
 * <ul>
 *   <li>玩家**骑在扫帚驾驶位**上右击她 → 下扫帚、挂到她身上（换到二号位）；</li>
 *   <li>**绑定态**右击她（她在扫帚上）→ 解除拴绳 + 玩家坐回扫帚**驾驶位**（她在第二乘客）。</li>
 * </ul>
 * 另外，拴着的时候她**不再被指定高度**：空袭（671）与扫帚（672）都改成"保持她自己当前高度 /
 * 原地悬停"——去哪个高度由她自己的战斗链路决定，拴绳只负责"绑住人"。玩家在地面时改为并肩站位，
 * 见 {@link #seatOffset}。
 *
 * ── 安全网（都做进 tick 校验）──
 * 她落地/入水超过 0.6 秒 → 自动把玩家放下（免得挂着拖地闷在水里）；玩家潜跳自行下鞍 /
 * 被别的模组拽下去 → 下一次校验自动解除；解除瞬间人在空中 → 5 秒摔伤豁免（不搞"刚松手就摔死"）；
 * 挂着时卡墙/挤墙伤全免（贴着树冠飞是常态）；她本人对主人的伤害由 {@link FriendlyFireGuard}
 * 三层总闸拦（挂载者必须是主人，天然被覆盖）。
 *
 * 【持久化】挂载标记写在女仆的 persistentData（{@code maid_smart_gunner}），存档重载后由
 * 每 30 秒一次的恢复扫描重建（骑乘关系本身由原版存档恢复）。
 *
 * 【事件】手持武装拴绳右击女仆 = 挂载/解除（与 IndexStoneInteractHandler 同款骨架：
 * EntityInteract 事件比 TLM 的 mobInteract 先到，cancel 掉就不会误开女仆 GUI）。
 */
@EventBusSubscriber(modid = "promaid")
public final class GunnerTetherManager {

    /** persistentData 里的挂载标记键 */
    public static final String TAG_GUNNER = "maid_smart_gunner";
    /** 她连续贴地/入水多少 tick 后自动放下（12 = 0.6 秒；碰一下地面不算） */
    private static final int GROUND_DISMOUNT_TICKS = 12;
    /** 解除后的摔伤豁免时长（tick） */
    private static final int DISMOUNT_GRACE_TICKS = 100;
    /** 拒绝提示节流（ms） */
    private static final long DENY_INTERVAL_MS = 4000L;

    /** 服务端权威挂载表：maid UUID → 挂载记录（弱引用：女仆/玩家没了自动失效） */
    private static final Map<UUID, Link> LINKS = new HashMap<>();
    /**
     * 挂载对（客户端视角）：maid entityId → rider entityId。只由 S2C 包
     * （{@link GunnerTetherNetworking} → {@link com.maidsmart.client.GunnerTetherClient}）写入，
     * mixin 定位与绳子渲染读。放本类（而不是客户端类）是为了让 mixin 这份公共代码
     * 不引用任何客户端类型（专用服务器安全铁律）。
     */
    public static final Map<Integer, Integer> SYNCED_PAIRS = new HashMap<>();
    /** 她连续贴地/入水计数：maid UUID → tick 数 */
    private static final Map<UUID, Integer> GROUND_TICKS = new HashMap<>();
    /** 解除后的摔伤豁免：player UUID → 到期 game tick */
    private static final Map<UUID, Long> DISMOUNT_GRACE = new HashMap<>();
    /** 拒绝提示节流：maid UUID → 上次提示 ms */
    private static final Map<UUID, Long> DENY_LOG = new HashMap<>();
    /** 恢复扫描计时（ProMaidExtension 每 2 tick 调一次 tick()，这里再分流） */
    private static int restoreTimer = 0;

    private static final class Link {
        final java.lang.ref.WeakReference<EntityMaid> maid;
        final java.lang.ref.WeakReference<ServerPlayer> player;

        Link(EntityMaid m, ServerPlayer p) {
            this.maid = new java.lang.ref.WeakReference<>(m);
            this.player = new java.lang.ref.WeakReference<>(p);
        }
    }

    private GunnerTetherManager() {
    }

    /* ==================== 开关 / 参数 ==================== */

    public static boolean isEnabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_TETHER_ENABLE.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 悬挂距离（格）——mixin 定位用；配置没挂上时退回默认 */
    public static double hangOffset() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_TETHER_HANG.get();
        } catch (Throwable t) {
            return 2.6; // 【实测六百七十二】与配置默认值对齐（671 把默认改成 2.6，这里忘了跟）
        }
    }

    /**
     * 【实测六百六十九】这只女仆此刻是否被武装拴绳绑着（服务端口径：LINKS 里有她）。
     * 用途：「绑定后别再追主人、改成离地悬停」——扫帚链路（{@code MaidBroomBehavior}）与空袭链路
     * （{@code MaidFlightFollowBehavior}）都用这一个判据，口径只有一处。
     */
    public static boolean isTethered(EntityMaid maid) {
        try {
            return maid != null && LINKS.containsKey(maid.getUUID());
        } catch (Throwable t) {
            return false;
        }
    }

    /* 【实测六百七十二：tetherHoldPos() / tetherHover() 整段删掉】——拴绳不再给女仆定高度。
     *  671 已经把空袭那一条改成"只保持她自己当前高度"（MaidFlightFollowBehavior.maidsmart$tetherHold），
     *  672 把扫帚那一条也改成同一个口径（MaidBroomBehavior ⑥.0 → MaidBroomDrive.hoverInPlace）。
     *  于是"离地 3 格悬停"这个**绝对高度目标**彻底没有消费方了。留着一个没人调、又带着一整套
     *  "往下扫地面"逻辑的方法，等于给下一个人准备好第二份高度口径（本项目"口径只有一处"的铁律），
     *  所以连 javadoc 一起删干净——"她该多高"这件事现在只有一个答案：她原来多高就多高。
     *
     *  （树上的证据：实测日志里"不断攀升"那一段，她的高度来自**反复上/下扫帚时每次重开的起飞相位**，
     *   以及这条绝对高度目标；672 两处都处理了，见 MaidBroomDrive.startTakeoff / broomlessLongEnough。） */

    /* ==================== 悬挂 / 并肩定位（实测六百七十一 → 六百七十二） ==================== */

    /**
     * 【为什么需要"滑变"】旧版（668/669）的定位是二值判定：下方那一格没空间就**这一拍不改定位**、
     * 退回原版"站在她身上"，下一拍地形变了又吊回 hang 格下面——两个位置之间每 tick 直跳。玩家原话：
     * "被绑定以后，玩家会在女仆的上下反复横跳"。根因就是**两个相差 2 格以上的位置之间没有插值**
     * （不是碰撞：javap 实证原版 {@code Entity.push} 对"载具与其自身乘客"本来就互推豁免）。
     *
     * <p>【实测六百七十二：多加一档"并肩"，并把"回到 0"这条死路堵掉】只把"跳到 0"改成"滑到 0"
     * 还不够——**0 就是玩家站在她脚底**，两个建模完全重叠。玩家原话："平时待命没有起飞的时候，
     * 直接跟女仆的建模完全重叠看起来真的太难绷了。应该要跟女仆拉开一定程度上的距离的。女仆在地上
     * 应该是跟原版拴绳一样的逻辑才对。" 所以现在分两档，用**下方有没有空间**当判据（不再看
     * {@code onGround}：几何自己就说明了状态，而且"贴着地面滑飞"也不会漏）：
     * <ul>
     *   <li><b>悬挂</b>（下方有空间 = 她在飞）：吊在她脚底下方 hang 格（老规矩）；</li>
     *   <li><b>并肩</b>（下方一点空间都没有 = 她站在地上 / 贴着地形）：沿"她 → 玩家上一拍位置"
     *       的方向**水平拉开**绳长，高度贴她脚底——两个建模并排站着、绳子看得见，就是被拴着伴走
     *       的样子（玩家要的"原版拴绳逻辑"）。方向取自玩家上一拍在哪、而不是她的朝向：她转身时
     *       玩家不会绕着她荡一圈，而且这是个收敛的不动点（她往前走 = 玩家自然跟在后头）。</li>
     * </ul>
     *
     * <p>两档之间**有粘滞**（{@link #SEAT_SIDE}）：进"并肩"要真的没空间，回"悬挂"要有满 0.5 格
     * 余量——中间那条带就是"别在 0 和 hang 之间来回跳"的迟滞，也就是实测反馈㊁"反复横跳"的解药。
     *
     * <p>两侧各存一份（mixin 服务端/客户端都会跑同一个算式）：键是**玩家 UUID**、值是"当前偏移
     * 向量"。包不参与同步——滑变只是观感，两侧各自算出来的差别在半格以内。
     */
    private static final Map<UUID, Vec3> SEAT_NOW = new HashMap<>();
    /** 「并肩」这一档的粘滞标记：键是玩家 UUID、值是"这一拍是不是并肩那一档" */
    private static final Map<UUID, Boolean> SEAT_SIDE = new HashMap<>();

    /** 每 tick 允许的位移：竖直**上升快、下降慢**（上升慢了就把玩家按进方块里；下降快了就是那个
     *  "上下横跳"）、水平统一 0.5 格——两档之间换档时走的就是这条水平限速，看着像绳子荡过去。 */
    private static final double SEAT_H_STEP = 0.5;
    private static final double SEAT_UP_STEP = 1.5;
    private static final double SEAT_DOWN_STEP = 0.35;
    /** 并肩时拉开的水平距离（格）：绳长就是"拉开多远"，但夹在 1.2~3.0 之间——比 1.2 近两个建模
     *  还会重叠，比 3.0 远就像根棍子把她拽着，都不像"被拴着伴走" */
    private static final double SIDE_MIN = 1.2;
    private static final double SIDE_MAX = 3.0;

    /** 悬挂点（玩家脚底那一格 + 头顶那一格）有没有空间——判不了（异常）时返回 true：宁可照旧吊着 */
    private static boolean roomFor(Entity passenger, double x, double y, double z) {
        try {
            net.minecraft.world.level.Level lvl = passenger.level();
            net.minecraft.core.BlockPos feet = new net.minecraft.core.BlockPos(
                    (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
            // 【670 的教训】区块没加载时**不要**去 getBlockState——它走的是 requireChunk 那条路
            //（会同步加载地形、或者直接抛异常）。isLoaded 是只读判据。
            if (!lvl.isLoaded(feet)) {
                return true;
            }
            return lvl.getBlockState(feet).isAir() && lvl.getBlockState(feet.above()).isAir();
        } catch (Throwable t) {
            return true;
        }
    }

    /** 这一拍最合适的偏移：有空间就用满 hang；没空间就沿她身体往上收（永不超过 0 = 她脚底那一层） */
    public static double hangTarget(EntityMaid maid, Entity passenger, double hang) {
        try {
            double h = Math.max(0.0, hang);
            for (double o = h; o > 0.0; o -= 0.5) {
                if (roomFor(passenger, maid.getX(), maid.getY() - o, maid.getZ())) {
                    return o; // 从最远往近处找：第一个有空的就是"这一拍能吊多远"
                }
            }
            return 0.0; // 贴着她脚底都没空间（钻进方块里飞）：用 0 兜底，绝不再退回原版头顶位（那会横跳）
        } catch (Throwable t) {
            return Math.max(0.0, hang);
        }
    }

    /**
     * 这一拍乘客相对**女仆脚底**该在的偏移（已经过滑变；mixin 直接加到她坐标上）。
     * 两档的规则与粘滞见上面那段说明，逐拍算出来的就是这两步：
     * <pre>
     *   ① 挑档：这一拍下方能吊多远（{@link #hangTarget}，0 = 一点空间都没有）
     *           → 没空间就"并肩"、有余量就回"悬挂"，中间 0~0.5 那条带保持上一拍的选择（迟滞）
     *   ② 滑变：把**当前**偏移朝这一拍的目标偏移挪一步（各分量限速）
     * </pre>
     */
    public static Vec3 seatOffset(EntityMaid maid, Entity passenger, double hang) {
        try {
            UUID id = passenger.getUUID();
            double h = Math.max(0.0, hang);
            boolean side = Boolean.TRUE.equals(SEAT_SIDE.get(id));
            double below = hangTarget(maid, passenger, h); // 这一拍下方能吊多远（0 = 一点空间都没有）
            if (!side && below <= 0.05) {
                side = true;                              // 进"并肩"：下方真的一点空间都没有
            } else if (side && below >= Math.min(h, 0.5)) {
                side = false;                             // 回"悬挂"：要有余量才回（迟滞的另一半）
            }
            SEAT_SIDE.put(id, Boolean.valueOf(side));
            Vec3 target = side ? sideTarget(maid, passenger, h) : new Vec3(0.0, -below, 0.0);
            Vec3 prev = SEAT_NOW.get(id);
            Vec3 cur = prev == null ? target : stepTo(prev, target);
            if (prev == null && SEAT_NOW.size() > 256) {
                SEAT_NOW.clear(); // 兜底：正常解绑会 forgetHang 清；人不多，清一次也无所谓
            }
            SEAT_NOW.put(id, cur);
            return cur;
        } catch (Throwable t) {
            return new Vec3(0.0, -Math.max(0.0, hang), 0.0);
        }
    }

    /**
     * 「并肩」那一档的目标偏移：沿"她 → 玩家上一拍位置"的方向水平拉开，高度贴她脚底。
     *
     * <p>方向为什么取"玩家上一拍在哪"而不是她的朝向：取朝向的话她一转身玩家就得绕着她荡半圈；
     * 取上一拍位置则是收敛的——她往前走，玩家自然被留在后头（就是被拴着伴走的样子）。
     * 只有"刚绑上那一拍玩家还在她身上"（长度≈0，没有方向可言）才退化成朝她**背后**拉开
     * （MC 偏航约定：面朝 {@code (-sin, cos)}，所以背后是 {@code (sin, -cos)}）。
     *
     * <p>距离取绳长、夹在 1.2~3.0；拉开的那一格也被 {@link #roomFor} 检一遍，堵住了就沿同一方向
     * 收到 0.6 / 0.35 倍（窄过道里仍然并排站着，而不是把玩家塞进墙里）。连贴身位都没空间
     * （1×1 竖井）才退回 0——那种地方本来也没法"拉开"。
     */
    private static Vec3 sideTarget(EntityMaid maid, Entity passenger, double hang) {
        double d = Math.max(SIDE_MIN, Math.min(hang, SIDE_MAX));
        double dx = passenger.getX() - maid.getX();
        double dz = passenger.getZ() - maid.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.05) {
            double r = Math.toRadians(maid.getYRot());
            dx = Math.sin(r);
            dz = -Math.cos(r);
        } else {
            dx /= len;
            dz /= len;
        }
        for (double k : new double[] { 1.0, 0.6, 0.35 }) {
            double o = d * k;
            if (roomFor(passenger, maid.getX() + dx * o, maid.getY(), maid.getZ() + dz * o)) {
                return new Vec3(dx * o, 0.0, dz * o);
            }
        }
        return Vec3.ZERO; // 连贴身位都没空间（1×1 竖井）：只能重叠，交给滑变慢慢收
    }

    /** 把当前偏移朝目标滑一步（各分量限速，见上面那三个常量） */
    private static Vec3 stepTo(Vec3 cur, Vec3 target) {
        return new Vec3(
                cur.x + clampStep(target.x - cur.x, SEAT_H_STEP),
                cur.y + clampStep(target.y - cur.y,
                        target.y > cur.y ? SEAT_UP_STEP : SEAT_DOWN_STEP),
                cur.z + clampStep(target.z - cur.z, SEAT_H_STEP));
    }

    /** 每 tick 最多挪这么多格（正负对称）：给滑变限速用 */
    private static double clampStep(double d, double max) {
        return d > max ? max : (d < -max ? -max : d);
    }

    /** 解绑时把滑变/并肩状态一起清掉（下一趟从目标值重新起步） */
    public static void forgetHang(Entity passenger) {
        try {
            if (passenger != null) {
                SEAT_NOW.remove(passenger.getUUID());
                SEAT_SIDE.remove(passenger.getUUID());
            }
        } catch (Throwable ignored) {
        }
    }

    /** 这位乘客是不是她的拴绳枪手（mixin 两侧行为统一入口：服务端看 LINKS / 客户端看 SYNCED_PAIRS） */
    public static boolean isGunner(EntityMaid maid, Entity passenger) {
        if (maid == null || passenger == null) {
            return false;
        }
        try {
            if (maid.level().isClientSide()) {
                // 客户端：包同步的实体 id 对
                Integer pid = SYNCED_PAIRS.get(maid.getId());
                return pid != null && pid.intValue() == passenger.getId();
            }
            Link link = LINKS.get(maid.getUUID());
            ServerPlayer p = link == null ? null : link.player.get();
            return p != null && p.getUUID().equals(passenger.getUUID());
        } catch (Throwable t) {
            return false;
        }
    }

    /* ==================== 挂载 / 解除 ==================== */

    /** 手持武装拴绳右击自己的女仆：挂载（已在挂 → 由 handler 走解除分支） */
    public static void attach(ServerPlayer player, EntityMaid maid) {
        if (!isEnabled()) {
            return;
        }
        LivingEntity owner = maid.getOwner();
        if (owner == null || !owner.getUUID().equals(player.getUUID())) {
            deny(maid, "这不是我的主人，绳子不给别人抓～");
            return;
        }
        if (LINKS.containsKey(maid.getUUID())) {
            deny(maid, "已经有人挂在我身上了～");
            return;
        }
        // 【实测六百七十二：乘坐扫帚时右击要能绑定（= 换到二号位）】玩家原话："玩家在乘坐扫帚的时候
        //  拿着武装拴绳右击还是没能切换成绑定模式。" 671 只放宽了"**女仆**骑着扫帚"那一档，没放宽
        //  "**玩家**骑着扫帚"这一档，所以这个场景必然被这道门拒掉（气泡「主人先从坐骑上下来再抓绳子」）。
        //  现在：玩家骑的正好是**这只女仆的扫帚**时放行——先下扫帚、再挂到她身上，正好是
        //  "绑定态右击 = 坐回扫帚"（{@link #seatBackOnBroom}）的逆操作。
        //  其它坐骑（船 / 矿车 / 别人的扫帚 / 别人的女仆）照旧拒绝。
        EntityBroom herBroom = MaidBroomKit.ridingBroom(maid);
        boolean onHerBroom = herBroom != null && player.getVehicle() == herBroom;
        if (player.isPassenger() && !onHerBroom) {
            deny(maid, "主人先从坐骑上下来再抓绳子～");
            return;
        }
        // 【实测六百六十九：门槛按模式分两档】玩家原话："扫帚必须要飞在空中才能绑定，这个没问题。
        //  但是如果女仆处于空袭摸式下，也是可以直接绑定的。"
        //  · 扫帚模式：**必须已经飞在空中**（地面上挂上去 = 她会把人拖进地里；她本来也要先起飞）
        //  · 空袭模式（flight_combat / flight_ranged）：照玩家要求**随时能挂**——地面上也安全，
        //    因为①悬挂点没空间时 mixin 会沿她身体往上收（EntityGunnerHangMixin 的滑变定位），
        //    ②滑变是平滑的，既不会把玩家按进方块里、也不会上下横跳
        // 【实测六百七十一：扫帚这一档放宽到"她真的骑在扫帚上"】玩家反馈"扫帚状态下右击要能绑定"。
        //  她低空掠地/刚起飞时 onGround() 为真，旧版那一道"等我飞起来再右击我"会把人挡在门外。
        //  现在：只要她**已经骑在扫帚上**（isRidingBroom）就放行，不再要求此刻不在她面上；
        //  "扫帚模式但还没骑上、又站在地上"这一档仍然拒绝（那种情况下挂上去就是把人拖进地里）。
        //  另外她骑着**世界里的扫帚**（任务不是扫帚模式）时也认——"在乘坐扫帚状态下"就算数。
        boolean broomMode = com.maidsmart.combat.MaidBroomKit.isBroomTask(maid);
        boolean ridingBroom = com.maidsmart.combat.MaidBroomKit.isRidingBroom(maid);
        if (broomMode && !ridingBroom && maid.onGround()) {
            deny(maid, "等我飞起来再右击我，你先抓好绳子～");
            return;
        }
        if (!broomMode && !ridingBroom && !com.maidsmart.combat.MaidFlightKit.isFlightTask(maid)) {
            deny(maid, "扫帚或空袭模式时再抓绳子吧～");
            return;
        }
        if (onHerBroom) {
            // 从扫帚驾驶位下来（扫帚留给她）→ 下面 startRiding(女仆) 才挂得上（实测六百七十二）
            player.stopRiding();
        }
        // force = true（实测六百五十七同款）：原版不带 force 的 startRiding 要求
        // 双方"此刻互相没骑"之外还要过 canAddPassenger/canRide——force 一并跳过，
        // 我们只挂自己的主人，这两道门本来也不是给"绑人"用的
        if (!player.startRiding(maid, true)) {
            deny(maid, "绳子没扣上……再试一次？");
            return;
        }
        LINKS.put(maid.getUUID(), new Link(maid, player));
        GROUND_TICKS.remove(maid.getUUID());
        try {
            maid.getPersistentData().putString(TAG_GUNNER, player.getUUID().toString());
        } catch (Throwable ignored) {
        }
        GunnerTetherNetworking.send(maid, true);
        bubble(maid, "上来吧！抓好绳子，我们一起飞～");
        com.maidsmart.tool.PromaidLog.log("武装拴绳", "挂载：主人=" + playerName(player)
                + " 女仆=" + com.maidsmart.tool.PromaidLog.nameOf(maid)
                + "（悬挂 " + hangOffset() + " 格，二号位开火）");
    }

    /** 解除（natural=true 是自动解除而不是右击） */
    public static void detach(EntityMaid maid, boolean natural) {
        detach(maid, natural, natural ? "自动" : "右击");
    }

    /**
     * 解除（{@code why} 只进日志与气泡：右击 / 自动 / 换座）。
     *
     * <p>【实测六百七十二：为什么要带理由】日志里"绳子是怎么松的"必须一眼看得出——"右击解除"、
     * "玩家离鞍"、"落水自动"、"换座"，四种完全不同的原因在旧日志里只有前两种能分辨。
     * 排查"绑定态右击到底走没走坐回扫帚那条路"时，就靠这一行。
     */
    public static void detach(EntityMaid maid, boolean natural, String why) {
        Link link = LINKS.remove(maid.getUUID());
        GROUND_TICKS.remove(maid.getUUID());
        try {
            maid.getPersistentData().remove(TAG_GUNNER);
        } catch (Throwable ignored) {
        }
        ServerPlayer player = link == null ? null : link.player.get();
        GunnerTetherNetworking.send(maid, false);
        forgetHang(player); // 【实测六百七十一】滑变状态跟着解绑一起清
        if (player != null) {
            if (!player.onGround()) {
                // 空中松手：5 秒摔伤豁免（"不会扯断"的绳子不负责防摔死，但也不至于秒摔没）
                try {
                    DISMOUNT_GRACE.put(player.getUUID(),
                            player.level().getGameTime() + DISMOUNT_GRACE_TICKS);
                } catch (Throwable ignored) {
                }
            }
            if (player.getVehicle() == maid) {
                player.stopRiding();
            }
        }
        if (!natural) {
            bubble(maid, "换座".equals(why) ? "回扫帚上坐好，我接着飞～" : "到站啦，小心落地～");
        }
        com.maidsmart.tool.PromaidLog.log("武装拴绳", "解除(" + why + ")：女仆="
                + com.maidsmart.tool.PromaidLog.nameOf(maid)
                + " 玩家=" + (player == null ? "?" : playerName(player)));
    }

    /** 玩家显示名（PromaidLog.nameOf 只收 EntityMaid，玩家这里自己取） */
    private static String playerName(ServerPlayer player) {
        try {
            return player.getName() != null ? player.getName().getString() : player.getUUID().toString();
        } catch (Throwable t) {
            return player.getUUID().toString();
        }
    }

    /** 手持拴绳再右击 = 解除；返回是否处理了（给 handler 决定要不要挥手） */
    public static boolean toggle(ServerPlayer player, EntityMaid maid) {
        if (!isEnabled()) {
            return false;
        }
        if (LINKS.containsKey(maid.getUUID())) {
            LivingEntity owner = maid.getOwner();
            boolean allowed = (owner != null && owner.getUUID().equals(player.getUUID()))
                    || isGunner(maid, player);
            if (!allowed) {
                deny(maid, "绳子只听主人和挂着的那位的话～");
                return true;
            }
            // 【实测六百七十二】绑定态右击：她在扫帚上 → "坐回扫帚"；否则照旧只是解除
            if (!seatBackOnBroom(player, maid)) {
                detach(maid, false);
            }
            return true;
        }
        attach(player, maid);
        return true;
    }

    /**
     * 【实测六百七十二：绑定态右击 = 坐回扫帚】玩家原话："在绑定模式下拿着绳子进行右击就等于是
     * 坐回扫帚。上一版是正确的。这一版反而改错了。玩家直接掉了下去。"
     *
     * <p>旧版之所以"能坐回去"，靠的其实是一个**副作用**：旧 {@link #resolveMaid} 在目标是扫帚时
     * 只看第一个乘客，解析不出女仆 → 这次右击**没被取消** → 落到 TLM 自己的
     * {@code EntityBroom.interact} 上把玩家接上扫帚（javap 实证它的门是
     * {@code !isDiscrete && !isPassenger && !(getControllingPassenger instanceof Player)} 且
     * 乘客数 ≤1 → {@code player.startRiding(this)}），我们随后的 tick 看到"玩家离鞍"才把挂载表清掉。
     * 671 把 {@code resolveMaid} 改成"扫全部乘客"之后，右击扫帚**总能**解析到女仆，于是稳定走解除
     * 分支 = 人直接掉下去（实测日志 13:48:55 / 13:49:09 的「解除(右击)」）。
     *
     * <p>靠"别取消事件、让 TLM 接人"来换座位是**撞运气**——同一版里的 13:49:21 就变成了"接上扫帚"
     * （因为那一瞬间她恰好不在扫帚上）。所以这里改成**显式**换座，不依赖任何副作用：
     * 先解除拴绳，再把她请下扫帚、玩家坐进**驾驶位**（第一乘客），最后把她放回第二乘客。
     *
     * <p>座位顺序是有意义的：TLM 的 {@code getControllingPassenger()} 只在**第一乘客是玩家**时
     * 才非空，而"玩家驾驶"那条链路（{@code PlayerBroomControl}）与我们 mixin 的"有玩家驾驶就
     * 一个字不改"都看它。玩家坐第一乘客 = 他开、她开火，与"玩家自己放一把扫帚再让她上"的天然
     * 顺序完全一致（实测旧日志里那句「玩家在驾驶这把扫帚」就是这个状态）。
     *
     * <p>整段包在 try 里：任何意外都返回 false，调用方退回"普通解除"——绝不让一次换座把绳子卡住。
     *
     * @return true = 已按"坐回扫帚"处理完（挂载表已清）；false = 她没骑扫帚 / 绑的不是他 → 走普通解除
     */
    private static boolean seatBackOnBroom(ServerPlayer player, EntityMaid maid) {
        try {
            if (!isGunner(maid, player)) {
                return false; // 挂着的是别人：照旧"右击解除"，不替人换座
            }
            EntityBroom broom = MaidBroomKit.ridingBroom(maid);
            if (broom == null) {
                return false; // 没骑扫帚：没有"坐回去"这回事
            }
            detach(maid, false, "换座");
            java.util.List<Entity> was = new java.util.ArrayList<>(broom.getPassengers());
            for (Entity e : was) {
                try {
                    e.stopRiding();
                } catch (Throwable ignored) {
                }
            }
            boolean ok = player.startRiding(broom, true); // 玩家先上 = 驾驶位
            if (!ok) {
                com.maidsmart.tool.PromaidLog.log("武装拴绳", "换座失败：玩家上不了扫帚（女仆="
                        + com.maidsmart.tool.PromaidLog.nameOf(maid) + "）");
                return true;
            }
            for (Entity e : was) {
                if (e != player && e.isAlive()) {
                    try {
                        e.startRiding(broom, true); // 再把她放回第二乘客
                    } catch (Throwable ignored) {
                    }
                }
            }
            com.maidsmart.tool.PromaidLog.log("武装拴绳", "换座：玩家=" + playerName(player)
                    + " 坐回扫帚驾驶位，女仆=" + com.maidsmart.tool.PromaidLog.nameOf(maid) + " 在第二乘客");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /* ==================== 每 tick 校验（ProMaidExtension 每 2 tick 调） ==================== */

    public static void tick(MinecraftServer server) {
        try {
            long gt = server.getAllLevels().iterator().next().getGameTime();
            Iterator<Map.Entry<UUID, Link>> it = LINKS.entrySet().iterator();
            while (it.hasNext()) {
                Link link = it.next().getValue();
                EntityMaid maid = link.maid.get();
                ServerPlayer player = link.player.get();
                // ① 一方没了 / 跨维度 → 静默解除
                if (maid == null || player == null || !maid.isAlive() || !player.isAlive()
                        || maid.level() != player.level()) {
                    it.remove();
                    forgetHang(player); // 【实测六百七十一】一方没了/跨维度：滑变状态一起清
                    if (maid != null) {
                        try {
                            maid.getPersistentData().remove(TAG_GUNNER);
                        } catch (Throwable ignored) {
                        }
                        GunnerTetherNetworking.send(maid, false);
                    }
                    continue;
                }
                // ② 玩家自己潜跳下鞍 / 被别的模组拽下去 → 解除 + 空中给摔伤豁免
                if (!maid.hasPassenger(player)) {
                    it.remove();
                    forgetHang(player); // 【实测六百七十一】玩家离鞍：滑变状态一起清
                    try {
                        maid.getPersistentData().remove(TAG_GUNNER);
                    } catch (Throwable ignored) {
                    }
                    if (!player.onGround()) {
                        DISMOUNT_GRACE.put(player.getUUID(),
                                player.level().getGameTime() + DISMOUNT_GRACE_TICKS);
                    }
                    GunnerTetherNetworking.send(maid, false);
                    com.maidsmart.tool.PromaidLog.log("武装拴绳", "解除(玩家离鞍)：女仆="
                            + com.maidsmart.tool.PromaidLog.nameOf(maid));
                    continue;
                }
                // ③【实测六百七十一：绳子不会自己断】**地面那一档删掉了**——玩家原话"那个拴绳…
                //    并且不会断掉"。旧版她落地累计 0.6 秒就把人放下，等于绳子会自己断。
                //    只留入水：她还吊在下方 hang 格，落水会跟着进水，溺水是致命的（这一档留着救命）。
                //    （GROUND_TICKS/GROUND_DISMOUNT_TICKS 沿用旧称，671 起数的是"连续泡在水里的 tick"。）
                if (maid.isInWater()) {
                    int soaked = GROUND_TICKS.merge(maid.getUUID(), 2, Integer::sum);
                    if (soaked >= GROUND_DISMOUNT_TICKS) {
                        detach(maid, true);
                    }
                } else {
                    GROUND_TICKS.remove(maid.getUUID());
                }
            }
            // 豁免表过期清理
            if (!DISMOUNT_GRACE.isEmpty()) {
                Iterator<Map.Entry<UUID, Long>> git = DISMOUNT_GRACE.entrySet().iterator();
                while (git.hasNext()) {
                    if (git.next().getValue() < gt) {
                        git.remove();
                    }
                }
            }
            // 恢复扫描（每 300 次调用 ≈ 30 秒）：存档重载后重建 LINKS
            if (++restoreTimer >= 300) {
                restoreTimer = 0;
                restore(server);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 存档重载/玩家重进后，从女仆 persistentData 的标记重建挂载（必须真的还骑着才恢复） */
    private static void restore(MinecraftServer server) {
        for (ServerLevel lvl : server.getAllLevels()) {
            for (Entity e : lvl.getAllEntities()) {
                if (!(e instanceof EntityMaid maid) || !maid.isAlive()) {
                    continue;
                }
                if (LINKS.containsKey(maid.getUUID())) {
                    continue;
                }
                String pid;
                try {
                    pid = maid.getPersistentData().getString(TAG_GUNNER);
                } catch (Throwable t) {
                    continue;
                }
                if (pid == null || pid.isEmpty()) {
                    continue;
                }
                UUID puid;
                try {
                    puid = UUID.fromString(pid);
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                ServerPlayer p = server.getPlayerList().getPlayer(puid);
                if (p == null) {
                    continue;
                }
                if (maid.hasPassenger(p)) {
                    LINKS.put(maid.getUUID(), new Link(maid, p));
                    GunnerTetherNetworking.send(maid, true);
                    com.maidsmart.tool.PromaidLog.log("武装拴绳", "存档重载恢复：女仆="
                            + com.maidsmart.tool.PromaidLog.nameOf(maid));
                } else {
                    // 骑乘关系没被存档带回来（被拽下去等）——标记清掉，别每 30 秒白扫
                    try {
                        maid.getPersistentData().remove(TAG_GUNNER);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    /**
     * 【实测六百六十九：修"手持拴绳右击骑着扫帚的女仆 → 变成骑上扫帚"】
     * 玩家原话："判定上有一点反直觉。如果拿着武装拴绳去右击坐在扫帚上的女仆，很容易直接乘上
     * 扫帚而不是走道具链路。这边应该要有个判定优先级的，手持武装拴绳的时候，右击应该是优先绑定。"
     *
     * <p>根因**不是**事件优先级不够：本 handler 在 TLM 的 {@code EntityBroom.interact} 之前就会跑到，
     * cancel 掉就能挡住那次 {@code startRiding}。真正的问题是**目标认错了人**——她骑着扫帚时，
     * 客户端射线命中的是**载具（扫帚实体）**，{@code event.getTarget()} 拿到的是 {@code EntityBroom}，
     * 旧版 {@code instanceof EntityMaid} 直接不成立就 return，交互于是落到 TLM 那边去骑乘了。
     * 这里把"目标是扫帚"也解析成它背上的女仆 → 手持拴绳右击 = 绑定优先于骑乘。
     */
    private static EntityMaid resolveMaid(Entity target) {
        try {
            if (target instanceof EntityMaid m) {
                return m;
            }
            // 【实测六百七十一】扫她**全部的**乘客，不再只看第一个：她坐在扫帚上时客户端射线命中的是
            // 载具（扫帚实体），而"第一个乘客"未必就是她（玩家同乘 / 别的模组往车里塞了乘客时）。
            if (target instanceof EntityBroom broom) {
                for (Entity p : broom.getPassengers()) {
                    if (p instanceof EntityMaid m2) {
                        return m2;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /* ==================== 事件 ==================== */

    /** 手持武装拴绳右击女仆 = 挂载/解除（骨架同 IndexStoneInteractHandler） */
    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!isEnabled()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        EntityMaid maid = resolveMaid(event.getTarget());
        if (maid == null) {
            return;
        }
        InteractionHand hand = event.getHand();
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof CombatLeashItem)) {
            return;
        }
        event.setCanceled(true);
        player.swing(hand);
        toggle(player, maid);
    }

    /** 新玩家开始追踪这只女仆时，把当前挂载状态补发给他（晚进服/传过来的人也能看到绳子） */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getTarget() instanceof EntityMaid maid)) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer watcher)) {
            return;
        }
        Link link = LINKS.get(maid.getUUID());
        ServerPlayer rider = link == null ? null : link.player.get();
        GunnerTetherNetworking.sendTo(watcher, maid.getId(), rider == null ? -1 : rider.getId());
    }

    /** 受击链最上游：挂着时卡墙/挤墙伤全免；刚解除 5 秒内摔伤豁免（1.21.1 是合并后的单一事件） */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (shouldCancel(event.getEntity(), event.getSource())) {
            event.setCanceled(true);
        }
    }

    private static boolean shouldCancel(LivingEntity target, DamageSource source) {
        if (!(target instanceof ServerPlayer player) || source == null) {
            return false;
        }
        try {
            String msgId = source.getMsgId();
            // ① 挂着：卡墙(inWall)/挤在一起(cramming) 全免——贴着树冠飞是常态
            // ①【实测六百七十一】挂着期间给"和女仆同款"的豁免：卡墙(inWall)/挤墙(cramming) 之外，
            //   再免摔落(fall)与撞墙飞(flyIntoWall)——与 MaidFlightWallGuard 对女仆那两条**同 msgId**
            //   （那一处是 FLY_INTO_WALL / FALL 两个常量），口径只有一处。
            //   玩家原话："会获得和女仆一样的同款免疫摔落伤害"。
            if (isRidingAsGunner(player)) {
                return "inWall".equals(msgId) || "cramming".equals(msgId)
                        || "fall".equals(msgId) || "flyIntoWall".equals(msgId);
            }
            // ② 刚解除：摔伤豁免（到期由 tick 清理）
            Long until = DISMOUNT_GRACE.get(player.getUUID());
            return until != null && "fall".equals(msgId);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 玩家此刻是否正作为拴绳枪手骑着某只女仆 */
    public static boolean isRidingAsGunner(ServerPlayer player) {
        try {
            Entity vehicle = player.getVehicle();
            return vehicle instanceof EntityMaid maid && isGunner(maid, player);
        } catch (Throwable t) {
            return false;
        }
    }

    /* ==================== 提示 ==================== */

    private static void deny(EntityMaid maid, String msg) {
        long now = System.currentTimeMillis();
        Long last = DENY_LOG.get(maid.getUUID());
        if (last != null && now - last < DENY_INTERVAL_MS) {
            return;
        }
        DENY_LOG.put(maid.getUUID(), now);
        // 【实测六百七十一】拒绝原因也写日志：玩家反馈"右击不灵"时，日志里要能**直接看出卡在哪一道门**
        //（气泡只显示几秒、还会被 4 秒节流吞掉，光靠它查不出是哪一条判据拦的）。
        com.maidsmart.tool.PromaidLog.log("武装拴绳", "拒绝：" + msg + "（女仆="
                + com.maidsmart.tool.PromaidLog.nameOf(maid) + "）");
        bubble(maid, msg);
    }

    private static void bubble(EntityMaid maid, String msg) {
        try {
            maid.getChatBubbleManager().addTextChatBubble(msg);
        } catch (Throwable ignored) {
        }
    }
}
