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
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * v1.3.7 实测六百六十七【武装拴绳】——粉丝点单的「武装直升机二号位」。
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
 *       {@code getControllingPassenger}（m_6688_）——玩家乘客不会抢走驾驶权，她的大脑/远程空袭
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
 * ── 【实测六百七十三：多一个"牵绳档"，外加第一人称半透明】──
 * 又一轮实测反馈（三条"可以优化"）落地成三件事：
 * <ul>
 *   <li><b>① 坐回扫帚后能立刻再绑上</b>：换座态（玩家在扫帚驾驶位、她在第二乘客）里准星前方
 *       **一个实体都没有**，客户端射线是 MISS，右击走的是 Forge 的 {@code RightClickItem} 而不是
 *       {@code EntityInteract}——旧版只监听后者，于是"坐在扫帚上右击切不到绑定模式"。
 *       现在补上了那个入口（{@link #onUseItem}）。</li>
 *   <li><b>② 空袭档多一档"牵绳"</b>：绑定空袭模式的女仆时，**她还没起飞就先不挂人**——玩家照常
 *       活动、她跟着走（原版拴绳观感，绳子照画）；她真的起飞才把玩家挂到二号位，落地满 2 秒再
 *       放下来回到牵绳。玩家原话："绑定完成之后是原版的拴绳逻辑是玩家牵着女仆走（不影响自己的
 *       活动）。直到女仆起飞。"</li>
 *   <li><b>③ 第一人称下她半透明</b>：挂在下面时她的模型会挡视野，但继续降低悬挂高度会让人更容易
 *       被打中——所以改成**渲染层面**解决：只对"挂着的那位玩家 + 第一人称"把她的模型画成半透明
 *       （{@code com.maidsmart.client.MaidGhostRender}，透明度可配）。服务端一行都不用改。</li>
 * </ul>
 *
 * ── 【实测六百七十四：三点修正 + 一层金边标记】──
 * <ul>
 *   <li><b>① 半透明默认 0.35 → 0.1</b>，而且**扫帚本体**也一起半透明（{@code MaidGhostRender} / {@code MaidGhostBroomMixin}）。</li>
 *   <li><b>② 修「玩家坐到空袭女仆头上」</b>：牵绳 → 起飞挂载时**没重发相位包**，客户端不认这个枪手 → 悬挂定位不生效、人被原版摆在**她头顶**；现在相位变一次发一次（{@code sync}）。</li>
 *   <li><b>③ 被拴绳选中 = 金色描边</b>（原版发光标记 + 客户端金边，同光灵箭的渲染），她**正式起飞**（牵绳 → 悬挂）的那一刻解除。</li>
 * </ul>
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
 * 【事件】手持武装拴绳右击女仆 = 挂载/解除（与服务端 IndexStoneInteractHandler 同款骨架：
 * EntityInteract 事件比 TLM 的 mobInteract 先到，cancel 掉就不会误开女仆 GUI）。
 */
@Mod.EventBusSubscriber(modid = "promaid")
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
    /**
     * 【实测六百七十三】两档之间的"翻档计时"（tick() 每 2 tick 调一次，所以每次都 +2）：
     * 牵绳档数的是**连续在空中的 tick**（够久 = 真起飞了），悬挂档数的是**连续落地的 tick**
     * （够久 = 真收工落地了）。两个门槛分开写、都带一点耐心，是为了别被"擦一下地""跳一下"骗着翻档。
     */
    private static final Map<UUID, Integer> MODE_TICKS = new HashMap<>();
    /** 牵绳 → 悬挂：连续在空中这么多 tick（10 = 0.5 秒）才算"她起飞了" */
    private static final int TAKEOFF_DETECT_TICKS = 10;
    /** 悬挂 → 牵绳：连续落地这么多 tick（40 = 2 秒）才算"她收工落地了" */
    private static final int LAND_DETECT_TICKS = 40;
    /** 解除后的摔伤豁免：player UUID → 到期 game tick */
    private static final Map<UUID, Long> DISMOUNT_GRACE = new HashMap<>();
    /** 拒绝提示节流：maid UUID → 上次提示 ms */
    private static final Map<UUID, Long> DENY_LOG = new HashMap<>();
    /** 恢复扫描计时（ProMaidExtension 每 2 tick 调一次 tick()，这里再分流） */
    private static int restoreTimer = 0;

    private static final class Link {
        final java.lang.ref.WeakReference<EntityMaid> maid;
        final java.lang.ref.WeakReference<ServerPlayer> player;
        /**
         * 【实测六百七十三】牵绳档：玩家**没有**骑在她身上，只是被拴着一起走。
         * 空袭模式的女仆还没起飞时进这一档（玩家原话："原版的拴绳逻辑是玩家牵着女仆走
         * （不影响自己的活动）。直到女仆起飞。"）；她一起飞就翻成 false（挂到二号位），
         * 落地够久再翻回 true。扫帚那一档永远是 false（要挂就挂、要换座就换座）。
         */
        boolean leash;

        Link(EntityMaid m, ServerPlayer p) {
            this(m, p, false);
        }

        Link(EntityMaid m, ServerPlayer p, boolean leash) {
            this.maid = new java.lang.ref.WeakReference<>(m);
            this.player = new java.lang.ref.WeakReference<>(p);
            this.leash = leash;
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
            return maid != null && LINKS.containsKey(maid.m_20148_());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 【实测六百七十三】这只女仆此刻是不是"人真的挂在下面"那一档（= 有链路 **且** 不是牵绳档）。
     *
     * <p>与 {@link #isTethered} 的区别只有在空袭档才有意义：她还没起飞时 isTethered 为真、
     * isHanging 为假。凡是"拴着就该原地悬停、别再追主人"的判据都必须问 isHanging——
     * 否则牵绳期间她会被按在原地不动，而玩家要的正是"牵着女仆走"。
     * 消费方：{@code MaidFlightFollowBehavior} 那五处（扫帚那条链路永远是悬挂档，两个判据等价）。
     */
    public static boolean isHanging(EntityMaid maid) {
        try {
            if (maid == null) {
                return false;
            }
            Link link = LINKS.get(maid.m_20148_());
            return link != null && !link.leash;
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
            net.minecraft.world.level.Level lvl = passenger.m_9236_();
            net.minecraft.core.BlockPos feet = new net.minecraft.core.BlockPos(
                    (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
            // 【670 的教训】区块没加载时**不要**去 getBlockState——它走的是 requireChunk 那条路
            //（会同步加载地形、或者直接抛异常）。isLoaded 是只读判据。
            if (!lvl.m_46749_(feet)) {
                return true;
            }
            return lvl.m_8055_(feet).m_60795_() && lvl.m_8055_(feet.m_7494_()).m_60795_();
        } catch (Throwable t) {
            return true;
        }
    }

    /** 这一拍最合适的偏移：有空间就用满 hang；没空间就沿她身体往上收（永不超过 0 = 她脚底那一层） */
    public static double hangTarget(EntityMaid maid, Entity passenger, double hang) {
        try {
            double h = Math.max(0.0, hang);
            for (double o = h; o > 0.0; o -= 0.5) {
                if (roomFor(passenger, maid.m_20185_(), maid.m_20186_() - o, maid.m_20189_())) {
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
            UUID id = passenger.m_20148_();
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
        double dx = passenger.m_20185_() - maid.m_20185_();
        double dz = passenger.m_20189_() - maid.m_20189_();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.05) {
            double r = Math.toRadians(maid.m_146908_());
            dx = Math.sin(r);
            dz = -Math.cos(r);
        } else {
            dx /= len;
            dz /= len;
        }
        for (double k : new double[] { 1.0, 0.6, 0.35 }) {
            double o = d * k;
            if (roomFor(passenger, maid.m_20185_() + dx * o, maid.m_20186_(), maid.m_20189_() + dz * o)) {
                return new Vec3(dx * o, 0.0, dz * o);
            }
        }
        return Vec3.f_82478_; // 连贴身位都没空间（1×1 竖井）：只能重叠，交给滑变慢慢收
    }

    /** 把当前偏移朝目标滑一步（各分量限速，见上面那三个常量） */
    private static Vec3 stepTo(Vec3 cur, Vec3 target) {
        return new Vec3(
                cur.f_82479_ + clampStep(target.f_82479_ - cur.f_82479_, SEAT_H_STEP),
                cur.f_82480_ + clampStep(target.f_82480_ - cur.f_82480_,
                        target.f_82480_ > cur.f_82480_ ? SEAT_UP_STEP : SEAT_DOWN_STEP),
                cur.f_82481_ + clampStep(target.f_82481_ - cur.f_82481_, SEAT_H_STEP));
    }

    /** 每 tick 最多挪这么多格（正负对称）：给滑变限速用 */
    private static double clampStep(double d, double max) {
        return d > max ? max : (d < -max ? -max : d);
    }

    /** 解绑时把滑变/并肩状态一起清掉（下一趟从目标值重新起步） */
    public static void forgetHang(Entity passenger) {
        try {
            if (passenger != null) {
                SEAT_NOW.remove(passenger.m_20148_());
                SEAT_SIDE.remove(passenger.m_20148_());
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
            if (maid.m_9236_().m_5776_()) {
                // 客户端：包同步的实体 id 对
                Integer pid = SYNCED_PAIRS.get(maid.m_19879_());
                return pid != null && pid.intValue() == passenger.m_19879_();
            }
            Link link = LINKS.get(maid.m_20148_());
            ServerPlayer p = link == null ? null : link.player.get();
            return p != null && p.m_20148_().equals(passenger.m_20148_());
        } catch (Throwable t) {
            return false;
        }
    }

    /* ==================== 实测六百七十四：相位同步 / 牵绳标记 ==================== */

    /** 挂载相位（S2C 只发这个数）：0 = 没挂；1 = 牵绳档（没骑她，牵着走）；2 = 悬挂档（吊在她下面） */
    public static final int ST_NONE = 0;
    public static final int ST_LEASH = 1;
    public static final int ST_HANG = 2;
    /** 牵绳档的挂载对（**客户端视角**）：只由 S2C 包写。与 SYNCED_PAIRS 的区别是"他人没骑她但绳子照画"，
     *  用于绳子渲染跳过"必须是她乘客"那道门（悬挂档仍然要求乘客）。 */
    public static final java.util.Set<Integer> SYNCED_LEASH = new java.util.HashSet<>();

    /** 牵绳标记落在女仆 persistentData 上的键（只为"崩了/重载后清掉我们加的那圈光"这一件事） */
    private static final String TAG_LEASH_MARK = "maid_smart_leash_mark";

    /** 这只女仆当前的相位（客户端拿不到 LINKS，这正是 S2C 要发的东西） */
    public static int phaseOf(EntityMaid maid) {
        try {
            Link link = maid == null ? null : LINKS.get(maid.m_20148_());
            return link == null ? ST_NONE : (link.leash ? ST_LEASH : ST_HANG);
        } catch (Throwable t) {
            return ST_NONE;
        }
    }

    /** 链路里的那位玩家（**牵绳档他不是乘客**，所以不能问 getFirstPassenger——那正是六百七十四的根因） */
    private static int riderIdOf(EntityMaid maid) {
        try {
            Link link = maid == null ? null : LINKS.get(maid.m_20148_());
            ServerPlayer p = link == null ? null : link.player.get();
            return p == null ? -1 : p.m_19879_();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 【实测六百七十四】把当前相位广播出去——**一个出口**（挂载 / 翻档 / 解除 / 恢复全走它）。
     *
     * <p>为什么必须补这一发：六百七十三的「牵绳 → 起飞挂载」是在 tick 里 startRiding 的，
     * 而当时发的还是 {@code attach} 那一刻的包——**那一刻玩家还不是她的乘客**，于是
     * {@code getFirstPassenger()} 为 null、riderId 记 -1，客户端收到就把这一对清掉了。
     * 结果：客户端不认"他是她的枪手"→ {@code EntityGunnerHangMixin} 不生效 → 玩家被原版摆在
     * **她头顶**（不是吊在身下），牵绳档的绳子也画不出来。玩家原话："此机制没有引用到空袭状态，
     * 而且现在玩家会直接坐到空袭女仆的头上。明明说好的是挂在身下的。"
     * 现在相位每变一次就重发一次（riderId 从链路里取，不看乘客关系）。
     */
    private static void sync(EntityMaid maid) {
        try {
            GunnerTetherNetworking.send(maid, phaseOf(maid), riderIdOf(maid));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【实测六百七十四】给"被拴绳选中、还没正式起飞"的女仆打上发光标记。
     *
     * <p>用的是原版自己的发光标记（{@code setGlowingTag(true)}：写共享标志位 6，自动同步给所有
     * 玩家）——渲染管线与"光灵箭射中敌人"完全同一条，穿墙可见的描边；描边颜色由客户端
     * {@code MaidGlowGoldMixin} 改成金色。玩家原话："对被武装拴绳选中的女仆加一层标记效果。
     * 效果同光灵箭射中敌人时的渲染，但是将光边改为金色。正式起飞时解除该标记效果。"
     */
    private static void markLeash(EntityMaid maid) {
        try {
            maid.m_146915_(true);
            maid.getPersistentData().m_128359_(TAG_LEASH_MARK, "1");
            com.maidsmart.tool.PromaidLog.log("武装拴绳", "标记：女仆="
                    + com.maidsmart.tool.PromaidLog.nameOf(maid) + "（金色描边，她正式起飞时解除）");
        } catch (Throwable ignored) {
        }
    }

    /**
     * 撤掉发光标记（正式起飞 / 解绑 / 她没了的清理）。
     *
     * <p>{@code setGlowingTag(false)} 是**安全**的：它内部会重新求值 {@code isCurrentlyGlowing()}，
     * 所以她要是真中了光灵箭（GLOWING 效果还在），标志位不会被这一下清掉。
     */
    private static void unmarkLeash(EntityMaid maid) {
        try {
            if (maid == null) {
                return;
            }
            maid.m_146915_(false);
            maid.getPersistentData().m_128473_(TAG_LEASH_MARK);
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 挂载 / 解除 ==================== */

    /** 手持武装拴绳右击自己的女仆：挂载（已在挂 → 由 handler 走解除分支） */
    public static void attach(ServerPlayer player, EntityMaid maid) {
        if (!isEnabled()) {
            return;
        }
        LivingEntity owner = maid.m_269323_();
        if (owner == null || !owner.m_20148_().equals(player.m_20148_())) {
            deny(maid, "这不是我的主人，绳子不给别人抓～");
            return;
        }
        if (LINKS.containsKey(maid.m_20148_())) {
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
        boolean onHerBroom = herBroom != null && player.m_20202_() == herBroom;
        // 【实测六百七十三：空袭模式下"她还没起飞" = 牵绳档】玩家原话："绑定完成之后是原版的拴绳
        //  逻辑是玩家牵着女仆走（不影响自己的活动）。直到女仆起飞。"
        //  · 空袭模式（flight_combat / flight_ranged）＋她此刻**不在飞** → 只登记链路、**不挂人**：
        //    她照常跟着主人走，绳子照画（GunnerTetherClient 那条），玩家一切照旧；
        //  · 她已经飞在空中（滑翔位 / 本轮空袭出手，见 flyingNow）→ 老规矩，直接挂到二号位；
        //  · 扫帚模式 / 世界扫帚 → 永远不是牵绳档（要挂就挂、要换座就换座，见 672）。
        //  翻档（起飞→挂人、落地→放人）在 tick() 里做，两个方向各有去抖门槛。
        boolean broomMode = com.maidsmart.combat.MaidBroomKit.isBroomTask(maid);
        boolean ridingBroom = com.maidsmart.combat.MaidBroomKit.isRidingBroom(maid);
        boolean leash = !broomMode && !ridingBroom
                && com.maidsmart.combat.MaidFlightKit.isFlightTask(maid) && !flyingNow(maid);
        // 牵绳档不挂人，所以"玩家自己骑着别的坐骑"这道门对它不适用（"不影响自己的活动"）
        if (!leash && player.m_20152_() && !onHerBroom) {
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
        if (!leash && broomMode && !ridingBroom && maid.m_20096_()) {
            deny(maid, "等我飞起来再右击我，你先抓好绳子～");
            return;
        }
        if (!broomMode && !ridingBroom && !com.maidsmart.combat.MaidFlightKit.isFlightTask(maid)) {
            deny(maid, "扫帚或空袭模式时再抓绳子吧～");
            return;
        }
        if (!leash && onHerBroom) {
            // 从扫帚驾驶位下来（扫帚留给她）→ 下面 startRiding(女仆) 才挂得上（实测六百七十二）
            player.m_8127_();
        }
        // force = true（实测六百五十七同款）：原版不带 force 的 startRiding 要求
        // 双方"此刻互相没骑"之外还要过 canAddPassenger/canRide——force 一并跳过
        //（javap 实证 m_7998_：iload_2 ifne 直接跳过两道门；开门的那道 m_269011_
        //  在基类恒 true，EntityMaid 没覆写）
        if (!leash && !player.m_7998_(maid, true)) {
            deny(maid, "绳子没扣上……再试一次？");
            return;
        }
        LINKS.put(maid.m_20148_(), new Link(maid, player, leash));
        MODE_TICKS.remove(maid.m_20148_());
        GROUND_TICKS.remove(maid.m_20148_());
        try {
            maid.getPersistentData().m_128359_(TAG_GUNNER, player.m_20148_().toString());
        } catch (Throwable ignored) {
        }
        sync(maid);
        bubble(maid, leash ? "先跟着我走，等你起飞我再挂上去～" : "上来吧！抓好绳子，我们一起飞～");
        com.maidsmart.tool.PromaidLog.log("武装拴绳", (leash ? "牵绳：" : "挂载：") + "主人="
                + playerName(player) + " 女仆=" + com.maidsmart.tool.PromaidLog.nameOf(maid)
                + (leash ? "（她还没起飞：你自由活动、她跟着走；她一起飞就挂到二号位）"
                         : "（悬挂 " + hangOffset() + " 格，二号位开火）"));
        // 【实测六百七十四】牵绳档 = 还没正式起飞 → 打金色标记；已经悬挂档 → 清掉上一轮的残留
        if (leash) {
            markLeash(maid);
        } else {
            unmarkLeash(maid);
        }
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
        Link link = LINKS.remove(maid.m_20148_());
        // 【实测六百七十四】解绑 = 撤掉金色描边（她本来没标记时这一下是空操作）
        unmarkLeash(maid);
        GROUND_TICKS.remove(maid.m_20148_());
        MODE_TICKS.remove(maid.m_20148_());
        try {
            maid.getPersistentData().m_128473_(TAG_GUNNER);
        } catch (Throwable ignored) {
        }
        ServerPlayer player = link == null ? null : link.player.get();
        sync(maid);
        forgetHang(player); // 【实测六百七十一】滑变状态跟着解绑一起清
        if (player != null) {
            if (!player.m_20096_()) {
                // 空中松手：5 秒摔伤豁免（"不会扯断"的绳子不负责防摔死，但也不至于秒摔没）
                try {
                    DISMOUNT_GRACE.put(player.m_20148_(),
                            player.m_9236_().m_46467_() + DISMOUNT_GRACE_TICKS);
                } catch (Throwable ignored) {
                }
            }
            if (player.m_20202_() == maid) {
                player.m_8127_();
            }
        }
        if (!natural) {
            bubble(maid, "换座".equals(why) ? "回扫帚上坐好，我接着飞～"
                    : "牵绳".equals(why) ? "绳子收好啦，我在这儿等着～" : "到站啦，小心落地～");
        }
        com.maidsmart.tool.PromaidLog.log("武装拴绳", "解除(" + why + ")：女仆="
                + com.maidsmart.tool.PromaidLog.nameOf(maid)
                + " 玩家=" + (player == null ? "?" : playerName(player)));
    }

    /** 玩家显示名（PromaidLog.nameOf 只收 EntityMaid，玩家这里自己取） */
    private static String playerName(ServerPlayer player) {
        try {
            return player.m_5446_() != null ? player.m_5446_().getString() : player.m_20148_().toString();
        } catch (Throwable t) {
            return player.m_20148_().toString();
        }
    }

    /** 手持拴绳再右击 = 解除；返回是否处理了（给 handler 决定要不要挥手） */
    public static boolean toggle(ServerPlayer player, EntityMaid maid) {
        if (!isEnabled()) {
            return false;
        }
        if (LINKS.containsKey(maid.m_20148_())) {
            LivingEntity owner = maid.m_269323_();
            boolean allowed = (owner != null && owner.m_20148_().equals(player.m_20148_()))
                    || isGunner(maid, player);
            if (!allowed) {
                deny(maid, "绳子只听主人和挂着的那位的话～");
                return true;
            }
            // 【实测六百七十三】牵绳档右击 = 直接收绳（没有"换座"这回事：玩家根本没挂在她身上）
            Link link = LINKS.get(maid.m_20148_());
            if (link != null && link.leash) {
                detach(maid, false, "牵绳");
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
            java.util.List<Entity> was = new java.util.ArrayList<>(broom.m_20197_());
            for (Entity e : was) {
                try {
                    e.m_8127_();
                } catch (Throwable ignored) {
                }
            }
            boolean ok = player.m_7998_(broom, true); // 玩家先上 = 驾驶位
            if (!ok) {
                com.maidsmart.tool.PromaidLog.log("武装拴绳", "换座失败：玩家上不了扫帚（女仆="
                        + com.maidsmart.tool.PromaidLog.nameOf(maid) + "）");
                return true;
            }
            for (Entity e : was) {
                if (e != player && e.m_6084_()) {
                    try {
                        e.m_7998_(broom, true); // 再把她放回第二乘客
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

    /* ==================== 实测六百七十三：牵绳 ⇄ 悬挂 的两个方向 ==================== */

    /**
     * 她此刻算不算"已经起飞"：**在空中 +（在滑翔 或 本轮空袭出手进行中）**。
     *
     * <p>只用 {@code !onGround()} 会把"普通起跳 / 从坎上掉下来"也算成起飞——一挂上去她又落地，
     * 就变成两档之间来回翻（正是 671 那轮"不断攀升"的同类病）。滑翔位与本轮出手这两个判据
     * 只有飞行链路才会置（口径与 {@link com.maidsmart.combat.MaidFlightKit#isFlightAirborne} 同一处）。
     */
    private static boolean flyingNow(EntityMaid maid) {
        try {
            return !maid.m_20096_() && com.maidsmart.combat.MaidFlightKit.isFlightAirborne(maid);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 这只女仆是不是"扫帚那一档"（任务在扫帚模式，或此刻正骑着世界里的扫帚）——
     *  这一档永远保持"悬挂"，不参与 673 的牵绳 ⇄ 悬挂 翻档。 */
    private static boolean isBroomRelated(EntityMaid maid) {
        try {
            return com.maidsmart.combat.MaidBroomKit.isBroomTask(maid)
                    || com.maidsmart.combat.MaidBroomKit.isRidingBroom(maid);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 【实测六百七十三】牵绳档的她起飞了 → 把玩家挂到她下方（二号位）。挂不上就下一拍再试。 */
    private static void mountHang(ServerPlayer player, EntityMaid maid, Link link) {
        MODE_TICKS.remove(maid.m_20148_());
        try {
            if (!player.m_7998_(maid, true)) {
                com.maidsmart.tool.PromaidLog.log("武装拴绳", "起飞挂载失败（下一拍再试）：女仆="
                        + com.maidsmart.tool.PromaidLog.nameOf(maid));
                return;
            }
        } catch (Throwable t) {
            return;
        }
        link.leash = false;
        // 【实测六百七十四】她正式起飞了：撤掉金色标记，并且**把新相位重发一遍**——
        //  没有这一发，客户端仍以为「这对挂载不存在」（attach 时 riderId 记的是 -1），
        //  悬挂定位 mixin 就不生效，玩家会被原版摆在**她头顶**。
        unmarkLeash(maid);
        sync(maid);
        GROUND_TICKS.remove(maid.m_20148_());
        bubble(maid, "起飞啦！抓好绳子～");
        com.maidsmart.tool.PromaidLog.log("武装拴绳", "起飞挂载：主人=" + playerName(player)
                + " 女仆=" + com.maidsmart.tool.PromaidLog.nameOf(maid)
                + "（从牵绳翻成二号位，悬挂 " + hangOffset() + " 格）");
    }

    /** 【实测六百七十三】悬挂档的她落地够久了 → 把玩家放下来、恢复"牵着她走"（空袭档专用）。 */
    private static void switchToLeash(ServerPlayer player, EntityMaid maid, Link link) {
        MODE_TICKS.remove(maid.m_20148_());
        link.leash = true;
        // 【实测六百七十四】她落地放人了 → 又回到「还没起飞」：标记重新打上，相位重发
        markLeash(maid);
        sync(maid);
        try {
            if (player.m_20202_() == maid) {
                player.m_8127_();
            }
            if (!player.m_20096_()) {
                DISMOUNT_GRACE.put(player.m_20148_(),
                        player.m_9236_().m_46467_() + DISMOUNT_GRACE_TICKS);
            }
        } catch (Throwable ignored) {
        }
        forgetHang(player);
        bubble(maid, "先落地歇会儿，你牵着绳子等我起飞～");
        com.maidsmart.tool.PromaidLog.log("武装拴绳", "落地放人：女仆="
                + com.maidsmart.tool.PromaidLog.nameOf(maid) + " 玩家=" + playerName(player)
                + " → 恢复牵绳（她再起飞会重新挂上去）");
    }

    /* ==================== 每 tick 校验（ProMaidExtension 每 2 tick 调） ==================== */

    public static void tick(MinecraftServer server) {
        try {
            long gt = server.m_129785_().iterator().next().m_46467_();
            Iterator<Map.Entry<UUID, Link>> it = LINKS.entrySet().iterator();
            while (it.hasNext()) {
                Link link = it.next().getValue();
                EntityMaid maid = link.maid.get();
                ServerPlayer player = link.player.get();
                // ① 一方没了 / 跨维度 → 静默解除
                if (maid == null || player == null || !maid.m_6084_() || !player.m_6084_()
                        || maid.m_9236_() != player.m_9236_()) {
                    it.remove();
                    forgetHang(player); // 【实测六百七十一】一方没了/跨维度：滑变状态一起清
                    if (maid != null) {
                        try {
                            maid.getPersistentData().m_128473_(TAG_GUNNER);
                        } catch (Throwable ignored) {
                        }
                        sync(maid);
                    }
                    continue;
                }
                // 【实测六百七十三：两档分开走】牵绳档（link.leash）玩家**根本没骑在她身上**
                //   （"不影响自己的活动"），所以下面那条"玩家离鞍 → 解除"不适用；这一档只看
                //   "她起飞了没"：连续 10 tick 真的在空中滑翔 / 出空袭手（flyingNow）才算起飞，
                //   起飞就把他挂到二号位（翻成悬挂档）。普通起跳、从坎上掉下来都不算。
                if (link.leash) {
                    if (!isBroomRelated(maid) && flyingNow(maid)) {
                        int air = MODE_TICKS.merge(maid.m_20148_(), 2, Integer::sum);
                        if (air >= TAKEOFF_DETECT_TICKS) {
                            mountHang(player, maid, link);
                        }
                    } else {
                        MODE_TICKS.remove(maid.m_20148_());
                    }
                    continue; // 牵绳档不做"落水放人"：玩家没吊在下面，不会跟着她进水
                }
                // ② 玩家自己潜跳下鞍 / 被别的模组拽下去 → 解除 + 空中给摔伤豁免
                if (!maid.m_20363_(player)) {
                    it.remove();
                    forgetHang(player); // 【实测六百七十一】玩家离鞍：滑变状态一起清
                    try {
                        maid.getPersistentData().m_128473_(TAG_GUNNER);
                    } catch (Throwable ignored) {
                    }
                    if (!player.m_20096_()) {
                        DISMOUNT_GRACE.put(player.m_20148_(),
                                player.m_9236_().m_46467_() + DISMOUNT_GRACE_TICKS);
                    }
                    MODE_TICKS.remove(maid.m_20148_());
                    sync(maid);
                    com.maidsmart.tool.PromaidLog.log("武装拴绳", "解除(玩家离鞍)：女仆="
                            + com.maidsmart.tool.PromaidLog.nameOf(maid));
                    continue;
                }
                // ③【实测六百七十一：绳子不会自己断】**地面那一档删掉了**——玩家原话"那个拴绳…
                //    并且不会断掉"。旧版她落地累计 0.6 秒就把人放下，等于绳子会自己断。
                //    只留入水：她还吊在下方 hang 格，落水会跟着进水，溺水是致命的（这一档留着救命）。
                //    （GROUND_TICKS/GROUND_DISMOUNT_TICKS 沿用旧称，671 起数的是"连续泡在水里的 tick"。）
                if (maid.m_20069_()) {
                    int soaked = GROUND_TICKS.merge(maid.m_20148_(), 2, Integer::sum);
                    if (soaked >= GROUND_DISMOUNT_TICKS) {
                        detach(maid, true);
                    }
                } else {
                    GROUND_TICKS.remove(maid.m_20148_());
                }
                // ④【实测六百七十三】她落地够久（2 秒）→ 放玩家下来、恢复"牵着她走"。
                //    只有空袭档会走到这儿（扫帚档 isBroomRelated 为真，永远保持悬挂）。
                //    两个方向各有一道去抖门槛，所以"擦一下地""跳一下"都不会翻档。
                if (!isBroomRelated(maid) && maid.m_20096_()) {
                    int grounded = MODE_TICKS.merge(maid.m_20148_(), 2, Integer::sum);
                    if (grounded >= LAND_DETECT_TICKS) {
                        switchToLeash(player, maid, link);
                    }
                } else {
                    MODE_TICKS.remove(maid.m_20148_());
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
            // 恢复扫描（每 600 tick ≈ 30 秒）：存档重载后重建 LINKS
            if (++restoreTimer >= 300) {
                restoreTimer = 0;
                restore(server);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 存档重载/玩家重进后，从女仆 persistentData 的标记重建挂载（必须真的还骑着才恢复） */
    private static void restore(MinecraftServer server) {
        for (ServerLevel lvl : server.m_129785_()) {
            for (Entity e : lvl.m_8583_()) {
                if (!(e instanceof EntityMaid maid) || !maid.m_6084_()) {
                    continue;
                }
                if (LINKS.containsKey(maid.m_20148_())) {
                    continue;
                }
                String pid;
                try {
                    pid = maid.getPersistentData().m_128461_(TAG_GUNNER);
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
                ServerPlayer p = server.m_6846_().m_11259_(puid);
                if (p == null) {
                    continue;
                }
                if (maid.m_20363_(p)) {
                    LINKS.put(maid.m_20148_(), new Link(maid, p));
                    sync(maid);
                    com.maidsmart.tool.PromaidLog.log("武装拴绳", "存档重载恢复：女仆="
                            + com.maidsmart.tool.PromaidLog.nameOf(maid));
                } else {
                    // 【实测六百七十四】这一支也要撤金色标记：牵绳档的人不是乘客、存档重载后
                    //  链路本来就重建不出来，而标记（Glowing 是写进 NBT 的）不撤就会一直亮着。
                    unmarkLeash(maid);
                    // 骑乘关系没被存档带回来（被拽下去等）——标记清掉，别每 30 秒白扫
                    try {
                        maid.getPersistentData().m_128473_(TAG_GUNNER);
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
                for (Entity p : broom.m_20197_()) {
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
        ItemStack stack = player.m_21120_(hand);
        if (!(stack.m_41720_() instanceof CombatLeashItem)) {
            return;
        }
        event.setCanceled(true);
        player.m_6674_(hand);
        toggle(player, maid);
    }

    /**
     * 【实测六百七十三：修"坐在扫帚上右击仍然切不到绑定模式"】玩家原话："坐在扫帚上的时候使用
     * 武装拴绳右击仍然不可以立即切换成绑定模式。"
     *
     * <p>根因是**这一下右击根本没有变成 EntityInteract**：换座之后玩家是扫帚的第一乘客（驾驶位）、
     * 女仆在第二乘客（他身后），准星前方一个实体都没有 → 客户端射线结果是 MISS →
     * 发的是 ServerboundUseItemPacket，落到 Forge 的 RightClickItem；而本类原来只监听
     * EntityInteract，于是这一下什么都不会发生——"右击不灵"就是这么来的。
     *
     * <p>所以补上这个入口：**手持武装拴绳 + 这次右击没打到任何实体 + 玩家正骑着一把载着他女仆的
     * 扫帚**时，等价于右击那只女仆（走同一个 {@link #toggle}：没绑就绑上、绑着就换座/解除）。
     * 只认"自己扫帚上的那只女仆"——对着空气挥绳子不会触发任何东西。
     */
    @SubscribeEvent
    public static void onUseItem(PlayerInteractEvent.RightClickItem event) {
        if (!isEnabled()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!(event.getItemStack().m_41720_() instanceof CombatLeashItem)) {
            return;
        }
        EntityMaid maid = maidOnMyBroom(player);
        if (maid == null) {
            return;
        }
        event.setCanceled(true);
        player.m_6674_(event.getHand());
        toggle(player, maid);
    }

    /** 玩家此刻骑的那把扫帚上有没有"他的"女仆（换座态：他在驾驶位、她在第二乘客） */
    private static EntityMaid maidOnMyBroom(ServerPlayer player) {
        try {
            if (!(player.m_20202_() instanceof EntityBroom broom)) {
                return null;
            }
            for (Entity p : broom.m_20197_()) {
                if (!(p instanceof EntityMaid maid)) {
                    continue;
                }
                LivingEntity owner = maid.m_269323_();
                if (owner != null && owner.m_20148_().equals(player.m_20148_())) {
                    return maid;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
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
        Link link = LINKS.get(maid.m_20148_());
        ServerPlayer rider = link == null ? null : link.player.get();
        GunnerTetherNetworking.sendTo(watcher, maid.m_19879_(), rider == null ? -1 : rider.m_19879_(), phaseOf(maid));
    }

    /** ① 受击事件：挂着时卡墙/挤墙伤全免；刚解除 5 秒内摔伤豁免 */
    @SubscribeEvent
    public static void onLivingAttack(LivingAttackEvent event) {
        if (shouldCancel(event.getEntity(), event.getSource())) {
            event.setCanceled(true);
        }
    }

    /** ①b 受伤事件兜底（与 FriendlyFireGuard 同款双闸） */
    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (shouldCancel(event.getEntity(), event.getSource())) {
            event.setCanceled(true);
        }
    }

    private static boolean shouldCancel(LivingEntity target, DamageSource source) {
        if (!(target instanceof ServerPlayer player) || source == null) {
            return false;
        }
        try {
            String msgId = source.m_19385_();
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
            Long until = DISMOUNT_GRACE.get(player.m_20148_());
            return until != null && "fall".equals(msgId);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 玩家此刻是否正作为拴绳枪手骑着某只女仆 */
    public static boolean isRidingAsGunner(ServerPlayer player) {
        try {
            Entity vehicle = player.m_20202_();
            return vehicle instanceof EntityMaid maid && isGunner(maid, player);
        } catch (Throwable t) {
            return false;
        }
    }

    /* ==================== 提示 ==================== */

    private static void deny(EntityMaid maid, String msg) {
        long now = System.currentTimeMillis();
        Long last = DENY_LOG.get(maid.m_20148_());
        if (last != null && now - last < DENY_INTERVAL_MS) {
            return;
        }
        DENY_LOG.put(maid.m_20148_(), now);
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
