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
import net.minecraft.world.entity.player.Player;
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
 * ── 【实测六百七十五：一只绳子只牵一只 + 魂符残留 + 扫帚档下沉】──
 * <ul>
 *   <li><b>① 换绑先松旧的</b>：牵绳档玩家**不是**乘客，所以理论上能同时牵好几只女仆（绳子会画好几根、
 *       她一起飞就打架）。现在 {@link #attach} 第一件事就是把这位玩家**已有的那条链路**先解除
 *       （连带她的金色标记、绳子、以及"他还骑在她身上"的话把他放下来），再绑新的。玩家原话：
 *       "如果同时用武装拴绳绑定了多个女仆，怎么办？…如果标记了第2个女仆。那么会自动把第1个女仆
 *       相关的标记先清掉。"</li>
 *   <li><b>② 魂符收放的残留</b>：金边标记（{@code Glowing}）是写进女仆 NBT 的，收进魂符再放出来
 *       会跟着回来，但拴绳链路不会——旧版于是"边框还在、效果却没了"。现在女仆**重新入世界**
 *       就走 {@link #onMaidJoin}：人还挂在她身上（区块重载/跨维度）就当场把链路重建；否则把标记
 *       与残留键一起清掉。这一枪挂在放出的**最后一步**（EntityJoinLevelEvent），不碰魂符本身的流程。</li>
 *   <li><b>③ 扫帚档再往下让 0.3 格</b>：扫帚模式下她是骑在扫帚上的，扫帚模型比她的脚底更低，
 *       只按悬挂距离吊着还是会有少量重叠——{@link #hangFor} 在扫帚档把绳长加长
 *       {@code combat.tether.broomExtra}（默认 0.3，面板可调）。</li>
 * </ul>
 *
 * ── 【实测六百七十八：换座不再把她请下扫帚 + 左上角"绑定中"】──
 * <ul>
 *   <li><b>① 换座全程不下鞍</b>：玩家原话"当玩家坐在扫帚上，女仆处于扫帚模式时，玩家拿着武装拴绳
 *       对着女仆进行右击的时候，不应该把女仆的骑乘状态也解除掉。这样子可能会导致失控。"
 *       旧版换座会先把所有人请下扫帚再逐个放回去，她**真的被下过一次鞍**，而且第二下失败就把她
 *       留在空中。现在改用原版 {@code Entity.addPassenger} 自带的"玩家插队"规则（玩家插到第 0 位
 *       = 驾驶位）——她的骑乘关系一个字节都不动，见 {@link #seatBackOnBroom}。</li>
 *   <li><b>② 左上角蓝色"绑定中"</b>：玩家原话"在进入绑定状态下，最好是在左上角用蓝色字体显示一下
 *       玩家现在处于绑定状态。（渲染机制同冷却计时）"。走的是**同一条链路**（同一个包 →
 *       {@code CooldownHudRenderer}，左上角同一条竖直串、同样的带阴影文字），只是配色改成蓝色；
 *       数据由 {@link #hudBound} 提供，冷却 HUD 关掉也照常显示（那是两件事）。</li>
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

    /* ==================== 实测六百七十七：拉扯（原版拴绳同款分档） ==================== */

    /**
     * 原版拴绳的"绷紧"距离（1.21.1 {@code Leashable.LEASH_ELASTIC_DIST}；1.20.1 是
     * {@code PathfinderMob.customServerAiStep} 里同一个字面量 6.0f——两版逐字相同，对着反编译源码核过）。
     */
    private static final double PULL_ELASTIC = 6.0;
    /**
     * 原版拴绳的"太远"距离（{@code LEASH_TOO_FAR_DIST} = 10.0）——**原版在这里直接撒手掉拴绳**。
     * 我们的绳子是"不会断的绳"（玩家原话，实测六百七十一），所以这一档不撒手：照旧按弹性档拉，
     * 再远由她自己的牵引绳链路（{@code MaidFlightRecall} / {@code MaidBroomRecall}）连人带扫帚传回来。
     */
    private static final double PULL_TOO_FAR = 10.0;
    /** 原版那一记冲量的系数（{@code legacyElasticRangeLeashBehaviour}：每轴 {@code copySign(d²×0.4, d)}） */
    private static final double PULL_IMPULSE = 0.4;
    /** 原版"闭区间"里的余量（{@code PathfinderMob} 里那个 {@code float $$5 = 2.0f}：走到离持有者 2 格处就够） */
    private static final double PULL_SLACK = 2.0;
    /**
     * "闭区间"朝主人的**接近速度上限**（格/tick）：0.215 ≈ 原版 {@code followLeashSpeed} 那个量级
     * （普通生物的行走速度属性 0.25 ≈ 4.3 格/秒，与玩家的行走速度同档）。
     *
     * <p>【为什么不是原版那行 {@code navigation.moveTo}】原版在 2~6 格这一档是**每 tick 重新算一条
     * A* 路径**（被拴的牛就是这么走的）。女仆一多，每 tick 一次寻路就是服务器灾难；而且她本来就有
     * 自己的跟随链路在算路（{@code MaidFlightFollowBehavior}）。所以我们只补**速度**、不碰导航：
     * 朝主人的水平速度不足"走到离你 2 格"所需时补到那个上限，够快就一个字不改（不去跟她的链路打架）。
     */
    private static final double PULL_WALK_SPEED = 0.215;
    /** "太远了"那条日志的节流（tick）：每只女仆 5 秒最多一行 */
    private static final int PULL_LOG_COOLDOWN = 100;
    /** 拉扯日志的节流表：maid UUID → 上次日志 game tick */
    private static final Map<UUID, Long> PULL_LOGGED = new HashMap<>();

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
    /**
     * 【实测六百八十二】"她换模式了"这个判定的去抖计数：maid UUID → 连续不符的 tick 数
     * （{@link #tick} 每 2 tick 调一次，所以每次 +2）。够久才真的松手，见 {@link #KIND_SWITCH_TICKS}。
     */
    private static final Map<UUID, Integer> KIND_TICKS = new HashMap<>();
    /**
     * 换模式去抖门槛（tick）：新模式**连续**站满 1 秒才算数。
     * <p>为什么不是"一拍就算"：TLM 换任务时 {@code getTask()} 可能有一两拍读不到（新旧交接），
     * 只凭一拍就松手会误伤正常玩法；绑定本来就以几十秒计，晚一秒松开感觉不到。
     */
    private static final int KIND_SWITCH_TICKS = 20;
    /** 拒绝提示节流：maid UUID → 上次提示 ms */
    private static final Map<UUID, Long> DENY_LOG = new HashMap<>();
    /** 恢复扫描计时（ProMaidExtension 每 2 tick 调一次 tick()，这里再分流） */
    private static int restoreTimer = 0;

    /* ---- 【实测六百七十六】二号位重锤猛击：见 trackRideFall / consumeRideFall 的说明 ---- */

    /** "她这一段俯冲，吊在下面的他跟着下落了多少格"（玩家 UUID → 格数） */
    private static final Map<UUID, Float> RIDE_FALL = new HashMap<>();
    /** 上一拍采样的玩家 Y（算下降量用；tick 每 2 tick 跑一次） */
    private static final Map<UUID, Double> RIDE_LAST_Y = new HashMap<>();
    /** 单次采样（2 tick）下降少于此值就不算"真在下坠"：把累计钳回 1.0（≈原版 0.5 格/tick） */
    private static final float RIDE_SLOW_MIN = 1.0f;
    /** 能用猛击的最低下落格数：原版 {@code MaceItem.SMASH_ATTACK_FALL_THRESHOLD}（= 1.5） */
    private static final float RIDE_SMASH_MIN = 1.5f;

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
        /**
         * 【实测六百八十二】挂上那一刻她的"战斗模式档"（{@link #modeKind}：broom / flight / none）。
         * 绑定期间她换了模式（空袭 ⇄ 扫帚，或干脆退出这两个模式）→ 这条链路自动松开一次，
         * 见 {@link #tick} 的 ⑤。玩家原话："如果玩家绑定了一个空袭状态的女仆。这个时候再把女仆
         * 切换到扫帚模式，那个标记仍然在，但是却是显示一个无效的效果……那反正，设定成女仆，
         * 在切换模式的时候会自动将标记和绑定消除一次就行了。"
         */
        String kind;

        Link(EntityMaid m, ServerPlayer p) {
            this(m, p, false);
        }

        Link(EntityMaid m, ServerPlayer p, boolean leash) {
            this.maid = new java.lang.ref.WeakReference<>(m);
            this.player = new java.lang.ref.WeakReference<>(p);
            this.leash = leash;
            this.kind = modeKind(m);
        }
    }

    /**
     * 【实测六百八十二】这只女仆此刻的"战斗模式档"——只用来判"绑定期间她换模式了没有"。
     *
     * <p>{@code broom} = 任务在扫帚模式**或**此刻正骑着世界里的扫帚（{@link #isBroomRelated} 同款口径）；
     * {@code flight} = 任务在空袭（近战 / 远战）；{@code none} = 都不是（换成了别的任务）。
     *
     * <p>为什么"骑着扫帚"也算 broom：玩家反馈的那个场景是"她骑着扫帚但任务是空袭"——
     * 那时她已经被 {@link #isBroomRelated} 归到扫帚档（悬挂距离也按扫帚算），链路本来就不该
     * 还停在"牵绳"上。
     */
    public static String modeKind(EntityMaid maid) {
        try {
            if (maid == null) {
                return "none";
            }
            if (com.maidsmart.combat.MaidBroomKit.isBroomTask(maid)
                    || com.maidsmart.combat.MaidBroomKit.isRidingBroom(maid)) {
                return "broom";
            }
            if (com.maidsmart.combat.MaidFlightKit.isFlightTask(maid)) {
                return "flight";
            }
        } catch (Throwable ignored) {
        }
        return "none";
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

    /** 【实测六百七十五】扫帚档额外下沉（格，默认 0.3）：扫帚模型比她的脚底更低，得再让一点 */
    private static double broomExtra() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_TETHER_BROOM_EXTRA.get();
        } catch (Throwable t) {
            return 0.3;
        }
    }

    /**
     * 【实测六百七十五】这位乘客实际要吊多深 = 悬挂距离 + 扫帚档的额外下沉。
     *
     * <p>玩家原话："扫帚飞行的时候再把玩家的高度再往下调个0.3格左右吧。现在还是会有少量的重叠。"
     * 扫帚模式下她是**骑在扫帚上**的，扫帚模型比她的脚底更低，所以同一个悬挂距离在扫帚档会显得
     * 偏浅。判据用 {@link #isBroomRelated}（任务在扫帚模式，或此刻正骑着世界里的扫帚）——
     * 与"这一档永远保持悬挂、不参与牵绳翻档"是同一个口径。
     *
     * <p>{@link com.maidsmart.mixin.EntityGunnerHangMixin} 走这一个入口，别再自己拼算式
     * （"口径只有一处"）。
     */
    public static double hangFor(EntityMaid maid) {
        double h = hangOffset();
        try {
            if (isBroomRelated(maid)) {
                h += broomExtra();
            }
        } catch (Throwable ignored) {
        }
        return h;
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
            Link link = LINKS.get(maid.getUUID());
            return link != null && !link.leash;
        } catch (Throwable t) {
            return false;
        }
    }

    /* 【实测六百八十二：679 的 leashChainActive() 整段删掉】——它唯一的消费方是
     *  EntityBroomMaidTravelMixin 里那道"武装拴绳没在用就让位"的闸，而那道闸在 实测六百八十二
     *  撤掉了（它让扫帚模式在没拿绳子时整段失效 = 玩家看到的"坐着扫帚掉在地上动也不动"）。
     *  一个没人调、名字又很像"该怎么飞"的方法留着，只会给下一个人准备第二份口径
     *  （本项目"口径只有一处"的老规矩：672 删 tetherHoldPos 用的是同一条理由）。
     *  扫帚驱动现在的判据就是 mixin 里那三条：第一乘客是她 / 她的任务就是扫帚模式 / 没有玩家驾驶。 */

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
                // 【实测六百七十六】重锤那笔"已下落格数"也跟着解绑一起清：
                //  人都下来了，别让他下一趟一上手就还揣着上一趟俯冲攒的加成
                RIDE_FALL.remove(passenger.getUUID());
                RIDE_LAST_Y.remove(passenger.getUUID());
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
            Link link = maid == null ? null : LINKS.get(maid.getUUID());
            return link == null ? ST_NONE : (link.leash ? ST_LEASH : ST_HANG);
        } catch (Throwable t) {
            return ST_NONE;
        }
    }

    /** 链路里的那位玩家（**牵绳档他不是乘客**，所以不能问 getFirstPassenger——那正是六百七十四的根因） */
    private static int riderIdOf(EntityMaid maid) {
        try {
            Link link = maid == null ? null : LINKS.get(maid.getUUID());
            ServerPlayer p = link == null ? null : link.player.get();
            return p == null ? -1 : p.getId();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 【实测六百七十八】左上角"绑定中"指示要的那一行数据：{@code {女仆名, 相位}}——
     * 相位是 {@code "leash"}（牵绳档：你自由活动、她跟着走）或 {@code "hang"}（二号位：你吊在她下面）。
     * 这位玩家此刻没拴着任何一只 → {@code null}。
     *
     * <p>玩家原话："在进入绑定状态下，最好是在左上角用蓝色字体显示一下玩家现在处于绑定状态。
     * （渲染机制同冷却计时）"。渲染那一半**一个字节都没新写**：数据塞进冷却 HUD 那个包
     * （{@code CooldownHudPacket} → {@code com.maidsmart.client.CooldownHudRenderer}），
     * 于是左上角的位置、字体、行高、三秒无数据自动清空全套行为都与冷却计时完全一致，
     * 只是那一段的配色是蓝色。口径只有一处：谁在拴着谁，只有 {@link #LINKS} 一个来源。
     *
     * <p>一趟线性扫描（链路表本来就只有"一位玩家 ↔ 她"的规模），异常一律吞掉——HUD 永远不该
     * 影响游戏逻辑。
     */
    public static String[] hudBound(ServerPlayer player) {
        try {
            if (player == null) {
                return null;
            }
            for (Map.Entry<UUID, Link> e : LINKS.entrySet()) {
                Link link = e.getValue();
                ServerPlayer p = link.player.get();
                if (p == null || !p.getUUID().equals(player.getUUID())) {
                    continue;
                }
                EntityMaid m = link.maid.get();
                if (m == null || !m.isAlive()) {
                    continue;
                }
                return new String[] { com.maidsmart.tool.PromaidLog.nameOf(m),
                        link.leash ? "leash" : "hang" };
            }
        } catch (Throwable ignored) {
        }
        return null;
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
            // 【实测六百七十五】开关关掉 = 完全不发光（连标记位都不写），见 COMBAT_TETHER_GLOW_MARK
            if (!com.maidsmart.config.MaidSmartConfig.COMBAT_TETHER_GLOW_MARK.get()) {
                return;
            }
            maid.setGlowingTag(true);
            maid.getPersistentData().putString(TAG_LEASH_MARK, "1");
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
            maid.setGlowingTag(false);
            maid.getPersistentData().remove(TAG_LEASH_MARK);
        } catch (Throwable ignored) {
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
        // 【实测六百七十五：一只绳子只牵一只女仆】玩家原话："如果同时用武装拴绳绑定了多个女仆，
        //  怎么办？那不炸了吗？…如果标记了第2个女仆。那么会自动把第1个女仆相关的标记先清掉。"
        //  为什么真的能"同时绑两只"：牵绳档（空袭的女仆还没起飞）玩家**不是**乘客，所以既不占
        //  座位、也不互相顶；于是两条绳子会同时画、她俩一起飞时抢座位。这一枪在这里把这位玩家
        //  已有的链路全部解除（连带金色标记、绳子、以及"他还吊在她下面"的话把他放下来），
        //  再挂新的——就是"换绑"。
        releaseOtherLinks(player, maid);
        // 【实测六百七十二：乘坐扫帚时右击要能绑定（= 换到二号位）】玩家原话："玩家在乘坐扫帚的时候
        //  拿着武装拴绳右击还是没能切换成绑定模式。" 671 只放宽了"**女仆**骑着扫帚"那一档，没放宽
        //  "**玩家**骑着扫帚"这一档，所以这个场景必然被这道门拒掉（气泡「主人先从坐骑上下来再抓绳子」）。
        //  现在：玩家骑的正好是**这只女仆的扫帚**时放行——先下扫帚、再挂到她身上，正好是
        //  "绑定态右击 = 坐回扫帚"（{@link #seatBackOnBroom}）的逆操作。
        //  其它坐骑（船 / 矿车 / 别人的扫帚 / 别人的女仆）照旧拒绝。
        EntityBroom herBroom = MaidBroomKit.ridingBroom(maid);
        boolean onHerBroom = herBroom != null && player.getVehicle() == herBroom;
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
        if (!leash && player.isPassenger() && !onHerBroom) {
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
        if (!leash && broomMode && !ridingBroom && maid.onGround()) {
            deny(maid, "等我飞起来再右击我，你先抓好绳子～");
            return;
        }
        if (!broomMode && !ridingBroom && !com.maidsmart.combat.MaidFlightKit.isFlightTask(maid)) {
            deny(maid, "扫帚或空袭模式时再抓绳子吧～");
            return;
        }
        if (!leash && onHerBroom) {
            // 从扫帚驾驶位下来（扫帚留给她）→ 下面 startRiding(女仆) 才挂得上（实测六百七十二）
            player.stopRiding();
        }
        // force = true（实测六百五十七同款）：原版不带 force 的 startRiding 要求
        // 双方"此刻互相没骑"之外还要过 canAddPassenger/canRide——force 一并跳过，
        // 我们只挂自己的主人，这两道门本来也不是给"绑人"用的
        if (!leash && !player.startRiding(maid, true)) {
            deny(maid, "绳子没扣上……再试一次？");
            return;
        }
        LINKS.put(maid.getUUID(), new Link(maid, player, leash));
        MODE_TICKS.remove(maid.getUUID());
        GROUND_TICKS.remove(maid.getUUID());
        try {
            maid.getPersistentData().putString(TAG_GUNNER, player.getUUID().toString());
        } catch (Throwable ignored) {
        }
        sync(maid);
        bubble(maid, leash ? "先跟着我走，等你起飞我再挂上去～" : "上来吧！抓好绳子，我们一起飞～");
        com.maidsmart.tool.PromaidLog.log("武装拴绳", (leash ? "牵绳：" : "挂载：") + "主人="
                + playerName(player) + " 女仆=" + com.maidsmart.tool.PromaidLog.nameOf(maid)
                + (leash ? "（她还没起飞：你自由活动、她跟着走；她一起飞就挂到二号位）"
                         : "（悬挂 " + hangFor(maid) + " 格，二号位开火）"));
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
        Link link = LINKS.remove(maid.getUUID());
        // 【实测六百七十四】解绑 = 撤掉金色描边（她本来没标记时这一下是空操作）
        unmarkLeash(maid);
        GROUND_TICKS.remove(maid.getUUID());
        MODE_TICKS.remove(maid.getUUID());
        KIND_TICKS.remove(maid.getUUID()); // 【实测六百八十二】换模式去抖计数跟着解绑一起清
        PULL_LOGGED.remove(maid.getUUID()); // 【实测六百七十七】拉力日志节流跟着解绑一起清
        try {
            maid.getPersistentData().remove(TAG_GUNNER);
        } catch (Throwable ignored) {
        }
        ServerPlayer player = link == null ? null : link.player.get();
        sync(maid);
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
            bubble(maid, "换座".equals(why) ? "回扫帚上坐好，我接着飞～"
                    : "牵绳".equals(why) || "切模式".equals(why) ? "绳子收好啦，我在这儿等着～"
                    // 【实测六百七十五】换绑：这句话直接复用上面那句牵绳台词——台词包是按"包含"
                    // 匹配的，复用既有文本才有对应的语音（新写一句会说不出话）。
                    : "换绑".equals(why) ? "绳子收好啦，我在这儿等着～" : "到站啦，小心落地～");
        }
        com.maidsmart.tool.PromaidLog.log("武装拴绳", "解除(" + why + ")：女仆="
                + com.maidsmart.tool.PromaidLog.nameOf(maid)
                + " 玩家=" + (player == null ? "?" : playerName(player)));
    }

    /**
     * 【实测六百七十五】把这位玩家**已经有的**拴绳链路全部解除（{@code except} 那一只除外）。
     *
     * <p>玩家原话："如果同时用武装拴绳绑定了多个女仆，怎么办？那不炸了吗？这边采用一个机制，
     * 如果标记了第2个女仆。那么会自动把第1个女仆相关的标记先清掉。"
     *
     * <p>为什么真能"同时绑两只"：牵绳档（空袭的女仆还没起飞）玩家**不是**她的乘客，所以既不占
     * 座位也不互相顶开——旧版于是会同时存在两条链路：两根绳子一起画、她俩一起飞的时候抢座位
     * （谁先 startRiding 谁赢，另一条下一拍被"玩家离鞍"判掉）。现在绑第二只之前先把第一只**完整
     * 松开**：走 {@link #detach}（撤金色标记、清 persistentData、清相位包、把他从她身上放下来、
     * 空中给 5 秒摔伤豁免），日志里写一行"换绑"。
     *
     * <p>只认"链路里的玩家 == 这位玩家"：别人牵着别人的女仆，一根都不动。
     * 先收集再解除（{@code detach} 会改 {@code LINKS}，边走边删会炸迭代器）。
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
                ServerPlayer p = link.player.get();
                if (p != null && p.getUUID().equals(player.getUUID())) {
                    others.add(m);
                }
            }
            for (EntityMaid m : others) {
                detach(m, false, "换绑");
                com.maidsmart.tool.PromaidLog.log("武装拴绳", "换绑：先松开女仆="
                        + com.maidsmart.tool.PromaidLog.nameOf(m) + "（主人=" + playerName(player) + "）");
            }
        } catch (Throwable ignored) {
        }
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
            // 【实测六百七十三】牵绳档右击 = 直接收绳（没有"换座"这回事：玩家根本没挂在她身上）
            Link link = LINKS.get(maid.getUUID());
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
     * （因为那一瞬间她恰好不在扫帚上）。所以这里改成**显式**换座，不依赖任何副作用。
     *
     * <p>座位顺序是有意义的：TLM 的 {@code getControllingPassenger()} 只在**第一乘客是玩家**时
     * 才非空，而"玩家驾驶"那条链路（{@code PlayerBroomControl}）与我们 mixin 的"有玩家驾驶就
     * 一个字不改"都看它。玩家坐第一乘客 = 他开、她开火，与"玩家自己放一把扫帚再让她上"的天然
     * 顺序完全一致（实测旧日志里那句「玩家在驾驶这把扫帚」就是这个状态）。
     *
     * ── 【实测六百七十八：换座不再把她请下扫帚】玩家原话 ──
     * "当玩家坐在扫帚上，女仆处于扫帚模式时，玩家拿着武装拴绳对着女仆进行右击的时候，不应该把
     * 女仆的骑乘状态也解除掉。这样子可能会导致失控。"
     *
     * <p>旧版（672-677）的换座是"先把**所有人**请下扫帚 → 玩家坐进第一乘客 → 再把她放回第二乘客"
     * ——她真的被 {@code stopRiding()} 过一次，而且那两下如果第二下失败（她没上得去 / 那只扫帚已经
     * 不可用），她就**留在扫帚外面**：她只有 20 血，从空中掉下去就是死。这就是玩家说的"失控"。
     *
     * <p>现在**一次都不用叫她下鞍**——原版 {@code Entity.addPassenger} 自己就有"玩家插队"这条规则
     * （javap 实证，1.21.1 {@code Entity.addPassenger} 与 1.20.1 {@code Entity.m_20348_} 字节码逐条
     * 同形）：新乘客是 {@code Player}、且**第一乘客不是** {@code Player} 时，走
     * {@code List.add(0, passenger)} 插到**第 0 位**（否则才 append）。她的扫帚上第一乘客正是她
     * 自己（女仆）→ 玩家一上鞍就自动坐进驾驶位，她的座次原地不动（还是第一/唯一那位，只是往后
     * 挪了一格变成第二乘客），骑乘关系、载具引用、碰撞位置全程一个字节都没变。
     *
     * <p>顺带把 672 那句"靠副作用撞运气"彻底了结：这里仍然是**显式**调用
     * {@code startRiding(broom, force)}，只是不再需要那段"请所有人下鞍"的舞蹈。
     *
     * <p>整段包在 try 里：任何意外都返回 false，调用方退回"普通解除"——绝不让一次换座把绳子卡住。
     * 玩家上不去扫帚时**她也照旧好好骑在扫帚上**（旧版会把两个人一起丢在空中），只是这一下换座
     * 没成功、绳子照常解除。
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
            // 玩家上鞍 = 自己坐进第一乘客（驾驶位），她**全程不下鞍**（原版 addPassenger 的插队规则）
            if (!player.startRiding(broom, true)) {
                com.maidsmart.tool.PromaidLog.log("武装拴绳", "换座失败：玩家上不了扫帚（女仆="
                        + com.maidsmart.tool.PromaidLog.nameOf(maid)
                        + " 仍安全骑在扫帚上）→ 绳子已解除，她继续自己飞");
                return true;
            }
            String order = "";
            try {
                for (Entity e : broom.getPassengers()) {
                    order = order.isEmpty() ? entityKind(e) : (order + "," + entityKind(e));
                }
            } catch (Throwable ignored) {
            }
            com.maidsmart.tool.PromaidLog.log("武装拴绳", "换座：玩家=" + playerName(player)
                    + " 坐回扫帚驾驶位，女仆=" + com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 在第二乘客（她全程没下鞍；乘客顺序=" + order + "）");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 日志用的乘客身份（只区分玩家 / 女仆 / 其它，不带任何客户端类型） */
    private static String entityKind(Entity e) {
        try {
            if (e instanceof ServerPlayer) {
                return "玩家";
            }
            if (e instanceof EntityMaid) {
                return "女仆";
            }
        } catch (Throwable ignored) {
        }
        return "其它";
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
            return !maid.onGround() && com.maidsmart.combat.MaidFlightKit.isFlightAirborne(maid);
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
        MODE_TICKS.remove(maid.getUUID());
        try {
            if (!player.startRiding(maid, true)) {
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
        GROUND_TICKS.remove(maid.getUUID());
        bubble(maid, "起飞啦！抓好绳子～");
        com.maidsmart.tool.PromaidLog.log("武装拴绳", "起飞挂载：主人=" + playerName(player)
                + " 女仆=" + com.maidsmart.tool.PromaidLog.nameOf(maid)
                + "（从牵绳翻成二号位，悬挂 " + hangFor(maid) + " 格）");
    }

    /** 【实测六百七十三】悬挂档的她落地够久了 → 把玩家放下来、恢复"牵着她走"（空袭档专用）。 */
    private static void switchToLeash(ServerPlayer player, EntityMaid maid, Link link) {
        MODE_TICKS.remove(maid.getUUID());
        link.leash = true;
        // 【实测六百七十四】她落地放人了 → 又回到「还没起飞」：标记重新打上，相位重发
        markLeash(maid);
        sync(maid);
        try {
            if (player.getVehicle() == maid) {
                player.stopRiding();
            }
            if (!player.onGround()) {
                DISMOUNT_GRACE.put(player.getUUID(),
                        player.level().getGameTime() + DISMOUNT_GRACE_TICKS);
            }
        } catch (Throwable ignored) {
        }
        forgetHang(player);
        bubble(maid, "先落地歇会儿，你牵着绳子等我起飞～");
        com.maidsmart.tool.PromaidLog.log("武装拴绳", "落地放人：女仆="
                + com.maidsmart.tool.PromaidLog.nameOf(maid) + " 玩家=" + playerName(player)
                + " → 恢复牵绳（她再起飞会重新挂上去）");
    }

    /* ==================== 实测六百七十六：二号位重锤猛击 ==================== */

    /**
     * 【实测六百七十六】替他记"她这一段俯冲，吊在下面的他跟着下落了多少格"。
     *
     * <p>玩家原话："我刚刚在运行游戏的时候，让女仆进行了近战空袭，然后我手里面也拿了个重锤。
     * 那么我可以正常触发这个重锤的增伤等效果吗？我更希望玩家可以吃到这些效果。而不受坐下这个
     * 状态影响。"
     *
     * <p><b>【为什么原来吃不到：反编译实证（1.21.1）】</b>重锤的下落加成只读
     * {@code LivingEntity.fallDistance} 这一个字段：
     * <ul>
     *   <li>{@code MaceItem.getAttackDamageBonus} → {@code canSmashAttack(entity)} =
     *       {@code entity.fallDistance > 1.5f && !entity.isFallFlying()}，然后按 4f / 12+2(f-3) /
     *       22+(f-8) 三段算加成；</li>
     *   <li>{@code Player.attack} 里唯一那次读取：{@code $$1 += this.getWeaponItem()
     *       .getAttackDamageBonus(target, $$1, source)}（1.21.1 {@code Player.java} 第 209 行），
     *       猛击命中后 {@code MaceItem.hurtEnemy} 还会放音效 / 周围击退 / 免摔
     *       （{@code setIgnoreFallDamageFromCurrentImpulse}），{@code postHurtEnemy} 再
     *       {@code resetFallDistance()}；</li>
     *   <li>而 {@code fallDistance} 只在 {@code Entity.move}（{@code Entity.java} 第 656 行）→
     *       {@code Entity.checkFallDamage(dy, onGround, ...)}（第 1135 行）里累加/清零。
     *       <b>乘客根本不走 {@code move}</b>：{@code Entity.rideTick} 先把
     *       {@code deltaMovement} 清零、再由载具的 {@code positionRider} 直接
     *       {@code setPos}（我们的 {@link com.maidsmart.mixin.EntityGunnerHangMixin} 也正是这么
     *       定位的）→ 所以吊在她下面的你 {@code fallDistance} 恒为 0，一锤都砸不出猛击。</li>
     * </ul>
     *
     * <p><b>【现在怎么算】</b>既然真正在动的是她，就按**玩家的实际下降**替他记一份
     * （{@code y} 的下降量，只累加不抵消），再到 {@code Player.attack} 的 HEAD 把这份值写回
     * {@code player.fallDistance}（{@link com.maidsmart.mixin.PlayerMaceRideFallMixin}）——
     * 之后原版那整套自己就跑通了。
     *
     * <p><b>三条口径</b>：
     * <ul>
     *   <li><b>她落地 = 清零</b>：与 {@code Entity.checkFallDamage} 里 {@code onGround} 那一支
     *       （{@code fallOn} + {@code resetFallDistance}）同一个时机；</li>
     *   <li><b>慢降/悬停不计</b>：单次采样下降不足 {@link #RIDE_SLOW_MIN}（≈原版 0.5 格/tick）
     *       就把累计钳回 1.0 —— 照抄原版 {@code Entity.checkSlowFallDistance} 的口径（滑翔时它把
     *       {@code fallDistance} 钳在 1.0，低于 1.5 门槛 = 原版就不给猛击），免得慢慢飘着也攒出
     *       超重击；</li>
     *   <li><b>一次下落只换一锤</b>：{@link #consumeRideFall} 取用即清零。</li>
     * </ul>
     *
     * <p>参数 {@code maid} 只用来判"她是不是落地了"；异常一律静默（最坏 = 这一趟吃不到加成，
     * 绝不影响挂载本身）。
     */
    private static void trackRideFall(ServerPlayer player, EntityMaid maid) {
        try {
            UUID id = player.getUUID();
            double nowY = player.getY();
            Double prev = RIDE_LAST_Y.get(id);
            RIDE_LAST_Y.put(id, nowY);
            if (maid.onGround()) {
                // 载具落地 → 原版 resetFallDistance 的时机
                RIDE_FALL.remove(id);
                return;
            }
            if (prev == null) {
                return; // 刚挂上：这一拍还没有上一拍可比
            }
            float acc = RIDE_FALL.containsKey(id) ? RIDE_FALL.get(id) : 0.0f;
            double dy = prev - nowY;
            if (dy > (double) RIDE_SLOW_MIN) {
                acc += (float) dy; // 真在下坠：累计（2 tick 采一次，所以门槛按 2 tick 折算）
            } else if (acc > 1.0f) {
                acc = 1.0f; // 悬停/慢降：钳回 1.0（< 1.5 → 打不出猛击，同原版滑翔那一档）
            }
            RIDE_FALL.put(id, acc);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【实测六百七十六】攻击那一刻取用（由
     * {@link com.maidsmart.mixin.PlayerMaceRideFallMixin} 在 {@code Player.attack} 的 HEAD 调）：
     * 返回"这一段俯冲已下落格数"，并把它**花掉**。
     *
     * <p>取用即清零的理由与 {@code MaceItem.postHurtEnemy} 的 {@code resetFallDistance()} 一致：
     * 这一下下落已经被兑换成猛击了，想再来一锤就得再俯冲一次。
     * 不在二号位（挂载表里没有他）时恒返回 0 → 原版行为一个字不改。
     */
    public static float consumeRideFall(Player player) {
        try {
            if (!(player instanceof ServerPlayer sp)) {
                return 0.0f; // 客户端预测不算（伤害/音效本来就是服务端算的）
            }
            UUID id = sp.getUUID();
            Float v = RIDE_FALL.remove(id);
            RIDE_LAST_Y.put(id, sp.getY());
            return v == null ? 0.0f : v;
        } catch (Throwable t) {
            return 0.0f;
        }
    }

    /** 能用猛击的最低下落格数（= 原版 {@code MaceItem.SMASH_ATTACK_FALL_THRESHOLD}，供混入比对） */
    public static float rideSmashMin() {
        return RIDE_SMASH_MIN;
    }

    /** 【实测六百七十六】这条开关的开/关（配置没挂上时按"开"走，与本项默认值一致） */
    public static boolean maceSmashWhileRiding() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_TETHER_MACE_SMASH.get();
        } catch (Throwable t) {
            return true;
        }
    }

    /* ==================== 每 tick 校验（ProMaidExtension 每 2 tick 调） ==================== */

    public static void tick(MinecraftServer server) {
        try {
            long gt = server.getAllLevels().iterator().next().getGameTime();
            // 【实测六百八十二：要"解除"的先攒起来，等这一轮走完再解】
            //  detach() 自己会改 LINKS，而在迭代器里改 map 会让下一个 it.next() 抛
            //  ConcurrentModificationException——整段 tick 被最外层 catch 吞掉，于是**本 tick
            //  剩下的女仆全部漏检**（旧版 ③ 落水那一支就有这个毛病，只是被吞得无声无息）。
            //  现在统一：这一支只登记，循环结束后再逐个 detach（键是女仆，值是理由）。
            java.util.LinkedHashMap<EntityMaid, String> deferredDetach = new java.util.LinkedHashMap<>();
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
                        PULL_LOGGED.remove(maid.getUUID()); // 【实测六百七十七】拉力日志节流一起清
                        KIND_TICKS.remove(maid.getUUID()); // 【实测六百八十二】去抖计数一起清
                        try {
                            maid.getPersistentData().remove(TAG_GUNNER);
                        } catch (Throwable ignored) {
                        }
                        sync(maid);
                    }
                    continue;
                }
                // 【实测六百八十二】绑定期间她换了模式 → 自动松开（连带金色标记、绳子、相位的清理）
                //    玩家原话："如果玩家绑定了一个空袭状态的女仆。这个时候再把女仆切换到扫帚模式，
                //    那个标记仍然在，但是却是显示一个无效的效果……设定成女仆，在切换模式的时候会
                //    自动将标记和绑定消除一次就行了。"
                //    【为什么要去抖】换任务那一两拍 `getTask()` 可能瞬间读不到（新旧任务交接），
                //    只凭一拍就松手会误伤正常玩法。这里要新模式**连续**站满 1 秒才算数——
                //    绑定本来就是几十秒以上的事，晚一秒松开感觉不到。
                String kindNow = modeKind(maid);
                if (!kindNow.equals(link.kind)) {
                    int n = KIND_TICKS.merge(maid.getUUID(), 2, Integer::sum);
                    if (n >= KIND_SWITCH_TICKS) {
                        deferredDetach.put(maid, "切模式");
                        KIND_TICKS.remove(maid.getUUID());
                    }
                    // 去抖期间**不做别的**：这一拍她的模式正在变，翻档/放人都没有意义
                    continue;
                }
                KIND_TICKS.remove(maid.getUUID());
                // 【实测六百七十三：两档分开走】牵绳档（link.leash）玩家**根本没骑在她身上**
                //   （"不影响自己的活动"），所以下面那条"玩家离鞍 → 解除"不适用；这一档只看
                //   "她起飞了没"：连续 10 tick 真的在空中滑翔 / 出空袭手（flyingNow）才算起飞，
                //   起飞就把他挂到二号位（翻成悬挂档）。普通起跳、从坎上掉下来都不算。
                if (link.leash) {
                    if (!isBroomRelated(maid) && flyingNow(maid)) {
                        int air = MODE_TICKS.merge(maid.getUUID(), 2, Integer::sum);
                        if (air >= TAKEOFF_DETECT_TICKS) {
                            mountHang(player, maid, link);
                        }
                    } else {
                        MODE_TICKS.remove(maid.getUUID());
                    }
                    continue; // 牵绳档不做"落水放人"：玩家没吊在下面，不会跟着她进水
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
                    MODE_TICKS.remove(maid.getUUID());
                    sync(maid);
                    com.maidsmart.tool.PromaidLog.log("武装拴绳", "解除(玩家离鞍)：女仆="
                            + com.maidsmart.tool.PromaidLog.nameOf(maid));
                    continue;
                }
                // ⑤【实测六百七十六】二号位重锤猛击：替他记"这一段俯冲下落了多少格"。
                //    走到这儿说明他确实还乘客在她身上（② 已经把那几种断链处理掉了）。
                //    为什么乘客要别人替他记，见 RIDE_FALL 的说明（乘客不走 Entity.move）。
                trackRideFall(player, maid);
                // ③【实测六百七十一：绳子不会自己断】**地面那一档删掉了**——玩家原话"那个拴绳…
                //    并且不会断掉"。旧版她落地累计 0.6 秒就把人放下，等于绳子会自己断。
                //    只留入水：她还吊在下方 hang 格，落水会跟着进水，溺水是致命的（这一档留着救命）。
                //    （GROUND_TICKS/GROUND_DISMOUNT_TICKS 沿用旧称，671 起数的是"连续泡在水里的 tick"。）
                if (maid.isInWater()) {
                    int soaked = GROUND_TICKS.merge(maid.getUUID(), 2, Integer::sum);
                    if (soaked >= GROUND_DISMOUNT_TICKS) {
                        // 【实测六百八十二】改成"攒起来等这一轮走完再解"：detach() 会改 LINKS，
                        //  在迭代器里改 map 会让下一拍 it.next() 抛异常、把本 tick 剩下的女仆全漏掉
                        //  （旧版这里直接 detach，被最外层 catch 吞了，无声无息）。
                        deferredDetach.put(maid, "落水自动");
                    }
                } else {
                    GROUND_TICKS.remove(maid.getUUID());
                }
                // ④【实测六百七十三】她落地够久（2 秒）→ 放玩家下来、恢复"牵着她走"。
                //    只有空袭档会走到这儿（扫帚档 isBroomRelated 为真，永远保持悬挂）。
                //    两个方向各有一道去抖门槛，所以"擦一下地""跳一下"都不会翻档。
                if (!isBroomRelated(maid) && maid.onGround()) {
                    int grounded = MODE_TICKS.merge(maid.getUUID(), 2, Integer::sum);
                    if (grounded >= LAND_DETECT_TICKS) {
                        switchToLeash(player, maid, link);
                    }
                } else {
                    MODE_TICKS.remove(maid.getUUID());
                }
            }
            // 【实测六百八十二】这一轮攒下来的"自动解除"在这里统一执行（理由见 tick 开头）：
            //  ① 落水自动：早就在做，只是从"边走边解"改成"走完再解"；
            //  ② 切模式：绑定期间她换了战斗模式 → 松开 + 撤金色标记（detach 全套）。
            for (Map.Entry<EntityMaid, String> e : deferredDetach.entrySet()) {
                EntityMaid m = e.getKey();
                String why = e.getValue();
                if (m == null) {
                    continue;
                }
                // 切模式给她一句气泡（复用 675 那句"绳子收好啦"的既有台词：台词包按"包含"匹配，
                // 复用才有语音）；落水那一档照旧静默（本来就是紧急放人，不额外说话）。
                boolean natural = !"切模式".equals(why);
                detach(m, natural, why);
                com.maidsmart.tool.PromaidLog.log("武装拴绳", "自动解除(" + why + ")：女仆="
                        + com.maidsmart.tool.PromaidLog.nameOf(m)
                        + ("切模式".equals(why)
                                ? "（她换成了 " + modeKind(m) + " 那一档，原链路的效果已经无效）" : ""));
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

    /**
     * 【实测六百七十五】女仆**重新入世界**（魂符收放 / 区块重载 / 跨维度传送）时的收尾。
     *
     * <p>玩家原话："如果成功用武装拴绳绑定了一个女仆，然后再将这个女仆收进魂符再放出来，
     * 那么那个像光灵箭一样的效果会仍然存在，但是效果却没了。在把他从魂符放出来之后的**最后一步**
     * 应该就是将她身上已有的此类边框先清掉。（为什么不是第1步？如果可以的话，当然第1部最好了。
     * 但是我怕又出现像上次那样子的，重新放置之后又出现更加严重的bug.）"
     *
     * <p>根因：金色描边标记是**原版发光位**（{@code Glowing} 写进女仆 NBT），收进魂符会被一起
     * 保存、放出时一起带回来；而拴绳链路（{@code LINKS}）只活在内存里，收符那一刻就没了。旧版
     * 只有 30 秒一次的 {@link #restore} 兜底，而且它要求"主人此刻在线"——所以经常看到"边框还在、
     * 效果却没了"。这一枪挂在**入世界事件**上（对魂符放出而言就是最后一步，魂符本身的流程一个字
     * 不动），分两支：
     * <ul>
     *   <li><b>骑乘关系还在</b>（区块重载 / 跨维度：她 NBT 里的主人还在、而且他确实还乘客在她身上）
     *       → 当场把悬挂档链路重建，别等那 30 秒（绳子不会有一段时间消失）；</li>
     *   <li><b>其余情况</b>（魂符收放 / 真解除了）→ 把金色标记与两个残留键一起清掉。</li>
     * </ul>
     * 清理动作只在"她真的带着我们的标记位"（{@link #TAG_LEASH_MARK}，只在真的打了发光标记时写）
     * 时才动手，所以绝不会去动一只"单纯中了光灵箭"的女仆的发光状态。
     */
    public static void onMaidJoin(EntityMaid maid) {
        if (maid == null) {
            return;
        }
        try {
            if (LINKS.containsKey(maid.getUUID())) {
                return; // 链路还在（同维度内被重新加回）：什么都不用动
            }
            ServerPlayer p = null;
            String pid = maid.getPersistentData().getString(TAG_GUNNER);
            if (pid != null && !pid.isEmpty()) {
                try {
                    net.minecraft.server.MinecraftServer srv = maid.level().getServer();
                    p = srv == null ? null : srv.getPlayerList().getPlayer(UUID.fromString(pid));
                } catch (Throwable ignored) {
                }
            }
            // 她 NBT 里带着"我们打过发光标记"的证据——下面所有清理动作都以它为准，
            // 绝不因为"她此刻在发光"就去动发光位（光灵箭的发光与本模组无关）。
            boolean hadMark = maid.getPersistentData().contains(TAG_LEASH_MARK);
            if (p != null && maid.hasPassenger(p)) {
                // 区块重载 / 跨维度：人还挂在她身上 → 当场重建悬挂档（相位包也补一发，客户端立刻认得）
                LINKS.put(maid.getUUID(), new Link(maid, p));
                MODE_TICKS.remove(maid.getUUID());
                GROUND_TICKS.remove(maid.getUUID());
                if (hadMark) {
                    unmarkLeash(maid); // 重建出来的是悬挂档，本来就不该亮
                }
                sync(maid);
                com.maidsmart.tool.PromaidLog.log("武装拴绳", "入世界重建：女仆="
                        + com.maidsmart.tool.PromaidLog.nameOf(maid) + "（他仍挂在她身上）");
                return;
            }
            // 魂符收放的"最后一步"：拴绳早就没了、标记却跟着 NBT 回来 → 清干净
            if (hadMark) {
                unmarkLeash(maid);
                com.maidsmart.tool.PromaidLog.log("武装拴绳", "入世界清理：女仆="
                        + com.maidsmart.tool.PromaidLog.nameOf(maid)
                        + "（拴绳已不在，撤掉残留的金色描边标记）");
            }
            try {
                maid.getPersistentData().remove(TAG_GUNNER);
            } catch (Throwable ignored) {
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
                    sync(maid);
                    com.maidsmart.tool.PromaidLog.log("武装拴绳", "存档重载恢复：女仆="
                            + com.maidsmart.tool.PromaidLog.nameOf(maid));
                } else {
                    // 【实测六百七十四】这一支也要撤金色标记：牵绳档的人不是乘客、存档重载后
                    //  链路本来就重建不出来，而标记（Glowing 是写进 NBT 的）不撤就会一直亮着。
                    unmarkLeash(maid);
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

    /**
     * 【实测六百七十七】拉扯：每 tick（在她的 tick **之前**）把原版拴绳那套力学作用在她身上。
     *
     * <p>玩家原话："我要的拉扯感是可以拉着女仆走，像原版拴绳一样。"
     *
     * <p>【为什么挂在 {@code MaidTickEvent} 上，而不是本类那个每 2 tick 的 {@link #tick}】
     * 两个理由，都是字节码实证：
     * <ol>
     *   <li><b>时机</b>：TLM 的 {@code EntityMaid.tick()} 第一件事就是 post 这个事件，然后才
     *       {@code super.tick()}（1.20.1 / 1.21.1 两版 javap 都是这个顺序）。原版拴绳的拉力
     *       也正是在"她自己的这一拍里、{@code travel()} 之前"给的——写在这里，速度才会真的
     *       走进这一 tick 的位移；写在 {@code tick()}（= 服务端 tick 末）那只会在下一 tick 被她
     *       自己的飞行控制覆盖掉。</li>
     *   <li><b>频率</b>：原版那记冲量是**每 tick**一次的量级，2 tick 一次等于把拉力砍半。</li>
     * </ol>
     * <p>事件两侧都会发（{@code tick()} 在客户端也会跑），所以这里先挡客户端：拉力只由服务端给
     * （{@code LINKS} 本来也只有服务端有）。
     *
     * <p>【为什么跳过"他正骑着她"】悬挂档里玩家就吊在她身下 hang 格（默认 2.6，扫帚档 2.9），
     * 那个位置是 {@link com.maidsmart.mixin.EntityGunnerHangMixin} 每拍刚性摆出来的：对绳子使劲
     * 只会跟骑乘定位打架（而且悬挂距离最大可配到 6.3 格，一过 6 格就会变成"每 tick 朝自己乘客
     * 加速"的荒唐场景）。**受力的是牵绳档**——玩家原话"原版的拴绳逻辑是玩家牵着女仆走
     * （不影响自己的活动）"，那一档你**不是**她的乘客、能自由走动，绳子才受得上力。
     */
    @SubscribeEvent
    public static void onMaidTick(com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent event) {
        try {
            if (!isEnabled() || !pullEnabled()) {
                return;
            }
            EntityMaid maid = event.getMaid();
            if (maid == null || maid.level().isClientSide()) {
                return; // 客户端那半不发使劲（LINKS 也只活在服务端）
            }
            Link link = LINKS.get(maid.getUUID());
            ServerPlayer player = link == null ? null : link.player.get();
            if (player == null || !player.isAlive() || maid.level() != player.level()) {
                return;
            }
            if (player.getVehicle() == maid) {
                return; // 悬挂档：他吊在她身下、由骑乘定位刚性控制（见上面那段）
            }
            pullTick(maid, player);
        } catch (Throwable ignored) {
        }
    }

    /** 拉扯开关（配置没挂上时按"开"——它只是让绳子更有手感，不该因为读不到配置就静默失效） */
    private static boolean pullEnabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_TETHER_PULL.get();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 原版拴绳力学的两档（第三档">10 格撒手"按本项目的"不会断的绳"改成继续拉）。
     *
     * <p>分档与字面量逐条对着两版反编译源码核过（1.21.1 {@code Leashable.tickLeash} 调
     * {@code elasticRangeLeashBehaviour} / {@code closeRangeLeashBehaviour}；1.20.1 是
     * {@code PathfinderMob.customServerAiStep} 里那一段 if-else 链，两边一模一样）：
     * <pre>
     *   dist &gt; 6  → 每轴 {@code copySign(d²×0.4, d)} 的冲量（原版"拽"的那一下）
     *   dist ≤ 6  → 走到离持有者 2 格处（原版是寻路，我们是速度补足，见 PULL_WALK_SPEED）
     * </pre>
     * <p>距离取的是原版那个 **3D 中心距**（{@code Entity.distanceTo}），不是水平距——原版也是这么量的。
     */
    private static void pullTick(EntityMaid maid, ServerPlayer player) {
        try {
            double dist = maid.distanceTo(player);
            if (dist > PULL_ELASTIC) {
                // ── 弹性档：原版那一记冲量，逐字照抄（含 copySign：分量为负要把冲量也翻过来）──
                double k = 1.0 / dist;
                double dx = (player.getX() - maid.getX()) * k;
                double dy = (player.getY() - maid.getY()) * k;
                double dz = (player.getZ() - maid.getZ()) * k;
                maid.setDeltaMovement(maid.getDeltaMovement().add(
                        Math.copySign(dx * dx * PULL_IMPULSE, dx),
                        Math.copySign(dy * dy * PULL_IMPULSE, dy),
                        Math.copySign(dz * dz * PULL_IMPULSE, dz)));
                maid.hasImpulse = true; // 原版紧跟着的 hasImpulse = true（告诉服务端"这一拍要重发位置"）
                if (dist > PULL_TOO_FAR) {
                    logPullTooFar(maid, player, dist);
                }
                return;
            }
            PULL_LOGGED.remove(maid.getUUID());
            if (dist <= PULL_SLACK) {
                return; // 已经在"离你 2 格"以内：原版这一档也是走到这儿就停
            }
            double hx = player.getX() - maid.getX();
            double hz = player.getZ() - maid.getZ();
            double hl = Math.sqrt(hx * hx + hz * hz);
            if (hl < 0.05) {
                return; // 她正上方/正下方：没有水平方向可拉（这一档垂直差由她自己的飞行处理）
            }
            hx /= hl;
            hz /= hl;
            // 想要的接近速度：离得越远越快，但封顶在"走得跟你一样快"（原版 followLeashSpeed 的量级）
            double want = Math.min(dist - PULL_SLACK, 1.0) * PULL_WALK_SPEED;
            Vec3 dm = maid.getDeltaMovement();
            double have = dm.x * hx + dm.z * hz; // 她此刻朝主人的水平速度（投影）
            if (have >= want) {
                return; // 她自己已经在靠近（或比这更快）：不插手，免得两条链路打架
            }
            double add = want - have;
            maid.setDeltaMovement(dm.x + hx * add, dm.y, dm.z + hz * add);
            maid.hasImpulse = true;
        } catch (Throwable ignored) {
        }
    }

    /** "她离得太远"这一条每 5 秒最多写一行（原版到这儿就撒手了，我们继续拉——日志里要能看见） */
    private static void logPullTooFar(EntityMaid maid, ServerPlayer player, double dist) {
        try {
            long now = player.level().getGameTime();
            Long last = PULL_LOGGED.get(maid.getUUID());
            if (last != null && now - last < PULL_LOG_COOLDOWN) {
                return;
            }
            PULL_LOGGED.put(maid.getUUID(), now);
            com.maidsmart.tool.PromaidLog.log("武装拴绳", "拉扯：女仆="
                    + com.maidsmart.tool.PromaidLog.nameOf(maid) + " 离主人 " + String.format("%.1f", dist)
                    + " 格（原版拴绳到 10 格就撒手，我们的绳子不撒手：照旧按原版那一记冲量拉，"
                    + "再远由她自己的牵引绳连人带扫帚传回来）");
        } catch (Throwable ignored) {
        }
    }

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
        if (!(event.getItemStack().getItem() instanceof CombatLeashItem)) {
            return;
        }
        EntityMaid maid = maidOnMyBroom(player);
        if (maid == null) {
            // 【实测六百七十六：悬挂档补的这一档】玩家此刻骑的**就是她本人**（吊在她下方 hang 格）。
            //  详见 {@link #linkedMaidImRiding}：这一档里准星射线打不到她（她在头顶上方），
            //  于是右击既不是 EntityInteract、也不满足上面"骑着载着她女仆的扫帚"，
            //  旧版就是**什么都不做**——"绑定态右击坐回扫帚"时灵时不灵里的那个"不灵"。
            maid = linkedMaidImRiding(player);
            if (maid != null) {
                com.maidsmart.tool.PromaidLog.log("武装拴绳", "右击（悬挂档，射线没打到她）：按「骑着的女仆」"
                        + "解析 → 走同一个切换：女仆=" + com.maidsmart.tool.PromaidLog.nameOf(maid));
            }
        }
        if (maid == null) {
            return;
        }
        event.setCanceled(true);
        player.swing(event.getHand());
        toggle(player, maid);
    }

    /**
     * 【实测六百七十六】玩家此刻骑的**就是**"他自己拴着的那只女仆"吗（悬挂档：他吊在她下方）。
     *
     * <p>为什么不靠准星射线：悬挂档里她在玩家**头顶上方** hang 格（默认 2.6，扫帚档 2.9），
     * 玩家的视线基本是水平的（还常在往下看地形）——射线根本扫不到她的碰撞箱，客户端发的是
     * {@code ServerboundUseItemPacket}（落到 {@code RightClickItem}），而不是打到实体的
     * {@code EntityInteract}。旧版这一档只认"骑着载着她女仆的扫帚"，于是悬挂档这一下右击是**空的**：
     * 实测日志里能看到"挂载 2.9 格 → 十几秒后 解除(玩家离鞍)"，中间**既没有「换座」也没有
     * 「解除(右击)」**——那一下右击我们的 handler 压根没跑到（玩家最后是潜跳下鞍下来的）。
     * 六百七十二 那条"坐回扫帚"本身是对的，问题一直在于**它有时候根本不会被触发**。
     *
     * <p>认人规则：他骑的实体是女仆 + 挂载表里这一对正好是"她 ↔ 他"。别人拴的女仆、别人的乘客
     * 一律不认；射线打中方块时走的是 {@code RightClickBlock} 那一档，本项目没接（那一档要动方块
     * 交互，风险比收益大）。
     */
    private static EntityMaid linkedMaidImRiding(ServerPlayer player) {
        try {
            if (!(player.getVehicle() instanceof EntityMaid maid)) {
                return null;
            }
            Link link = LINKS.get(maid.getUUID());
            ServerPlayer rider = link == null ? null : link.player.get();
            if (rider != null && rider.getUUID().equals(player.getUUID())) {
                return maid;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 玩家此刻骑的那把扫帚上有没有"他的"女仆（换座态：他在驾驶位、她在第二乘客） */
    private static EntityMaid maidOnMyBroom(ServerPlayer player) {
        try {
            if (!(player.getVehicle() instanceof EntityBroom broom)) {
                return null;
            }
            for (Entity p : broom.getPassengers()) {
                if (!(p instanceof EntityMaid maid)) {
                    continue;
                }
                LivingEntity owner = maid.getOwner();
                if (owner != null && owner.getUUID().equals(player.getUUID())) {
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
        Link link = LINKS.get(maid.getUUID());
        ServerPlayer rider = link == null ? null : link.player.get();
        GunnerTetherNetworking.sendTo(watcher, maid.getId(), rider == null ? -1 : rider.getId(), phaseOf(maid));
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
