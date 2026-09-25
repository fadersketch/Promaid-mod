package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityBroom;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * v1.3.0「扫帚模式」的行为（两树镜像）——骑扫帚、起飞悬停、接敌爬升、盘旋、照搬远程空袭开火。
 *
 * ── 每 tick 的相位（实测六百五十六定的顺序，v1.3.3 把"找扫帚"提到最前）──
 * <pre>
 *   ① 总开关关  → 下扫帚 + 气泡（与旧版一致）
 *   ①.5 扫帚优先 → 身上没扫帚也没骑着：**先去找**（地上掉的那把，MaidBroomDrive.seekBroom）；
 *                    找不到才报缺件待命。这一档排在"跟随主人"之前——玩家原话
 *                    "扫帚模式应该优先找扫帚，而不是优先跟随主人"。
 *   ② 缺件      → 下扫帚 + 气泡报缺什么（原地待命，不退化成地面近战，见下）
 *   ③ 骑上      → 从她背包取一把扫帚放出来骑上（已骑着就跳过；她坐在椅子上也能换过来，
 *                    走 force 骑乘——实测六百五十七）
 *   ③.4 玩家在开 → 让位：只开火，不写推进意图、不开爬升相位（驾驶权在玩家，见 drivenByPlayer）
 *   ③.5 起飞相位 → 原地往上抬 1 格（头顶顶住就悬停在此处）
 *   ④ 接敌      → 先爬到**敌上 8 格**（顶住就按实际高度），并把"这一场遭遇的盘旋高度"
 *                    定下来（v1.3.3：以前爬升与盘旋两套高度不接，才会"升上去又掉下来"）
 *   ⓪ 牵引绳    → 离主人超过配置距离（默认 100 格，按 3D 算）→ 连人带扫帚传送回主人身边
 *   ⑤ 平时      → 守家（工作范围）生效时沿工作范围那个圈盘旋巡逻；否则按"飞行跟随"同款的
 *                 起手/收手距离跟主人（默认开；扫帚没耐久）；都没有就原地悬停
 * </pre>
 * 两个爬升相位与"怎么飞"全部在 {@link MaidBroomDrive}（速度公式照搬 TLM 给玩家驾驶写的
 * {@code PlayerBroomControl}，一分不加）。
 *
 * ── 一件必须说清的事：她**不会**退化成地面近战 ──
 * 空袭在缺件时会退回"和普通攻击模式一致"的地面近战（那是空袭的既有设计）。扫帚模式
 * **不注册 TLM 的 {@code MaidMeleeAttack}**，所以缺件时她是"原地待命 + 气泡报告缺什么"，
 * 不会自己冲上去挥拳头。这是刻意的第一批取舍：把"未激活时怎么打"留到下一批做
 * （要么接一条地面近战回退、要么干脆让她切回普通攻击任务），免得在这一批里把
 * "骑乘 + 盘旋 + 开火"这条主链路和另一条战斗链路的交互一起搅进来。
 * 因此缺件气泡里**不会**写"先按普通战斗来"——那句话在这里会是假的。
 *
 * ── 为什么行为本身不移动她 ──
 * 她骑在扫帚上是**乘客**：TLM {@code EntityMaid.canBrainMoving()} 在 {@code isPassenger()}
 * 时为 false，她自己那套寻路/巡逻/范围约束整条失效，动她的是载具。所以这里只做三件事：
 * 决定"去哪"（{@link MaidBroomDrive}）、决定"打谁"、以及"骑上/下来"。
 * 真正每 tick 调 {@code move()} 的是 {@link com.maidsmart.mixin.EntityBroomMaidTravelMixin}。
 *
 * ── 开火 ──
 * 走 {@link MaidFlightCombatBehavior#fireRanged}——**与远程空袭同一个方法**（射程/视线/
 * 冷却/枪械换弹瞄准全部同款），不是抄一份。用户原话："手上武器的运作直接照搬远程空袭模式"。
 */
public class MaidBroomBehavior extends Behavior<EntityMaid> {

    public MaidBroomBehavior() {
        super(Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /* ==================== 缺件播报（形状照搬空袭的 notifyNotReady） ==================== */

    /** 第一次见到这具女仆实体的 gameTime（入世界宽限用） */
    private static final Map<EntityMaid, Long> FIRST_SEEN =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** 入世界后的静默窗口（tick）：0.5 秒——刚放出来那一拍她的背包/饰品数据还在就绪中 */
    private static final long GRACE_TICKS = 10;
    /** 缺件必须**连续**成立这么久才播报、齐备也必须连续成立这么久才清冷却（防抖动刷屏） */
    private static final long STABLE_TICKS = 40;
    /** 同一只女仆两条播报之间的最短间隔（tick）= 15 秒 */
    private static final int NOTIFY_COOLDOWN = 300;
    private static final Map<EntityMaid, Long> MISSING_SINCE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<EntityMaid, Long> COMPLETE_SINCE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<EntityMaid, Long> NOTIFY_READY =
            Collections.synchronizedMap(new WeakHashMap<>());

    /* ==================== 行为生命周期 ==================== */

    @Override
    protected boolean m_6114_(ServerLevel level, EntityMaid maid) {
        return MaidBroomKit.isBroomTask(maid);
    }

    @Override
    protected boolean m_6737_(ServerLevel level, EntityMaid maid, long gameTime) {
        return MaidBroomKit.isBroomTask(maid) && maid.m_6084_();
    }

    @Override
    protected void m_6732_(ServerLevel level, EntityMaid maid, long gameTime) {
        // 换任务 / 行为结束 / 她死了：一定要下来并把这件扫帚还回去，绝不能留一把孤儿扫帚漂在天上
        // 【实测六百七十二】理由带出去：这条是"她为什么突然下扫帚"的四个可能来源之一，
        //  旧日志里四个来源长得一模一样（一个字节都不打），排查高频上下扫帚时全靠猜。
        MaidBroomDrive.dismount(maid, "行为结束/换任务");
        MaidBroomDrive.forgetMaid(maid.m_20148_());
        FOLLOWING.remove(maid);
        PLAYER_DRIVE_LOGGED.remove(maid);
    }

    @Override
    protected void m_6725_(ServerLevel level, EntityMaid maid, long gameTime) {
        if (maid == null || !maid.m_6084_()) {
            return;
        }
        // ⓪【v1.3.6 实测六百六十一】牵引绳：她骑在扫帚上离主人太远（含「飞太高」）→ 立刻
        //    连人带扫帚一起传回主人身边，并就此打住——传送刚落地时写推进意图只会把她从落点上推走。
        //    口径与「空袭牵引绳」同源（她自己走了回不来的兜底），见 MaidBroomRecall。
        if (MaidBroomRecall.tick(maid)) {
            return;
        }
        // ① 总开关关掉 → 整段不激活（与旧版一致：下扫帚 + 报缺件；连缺件气泡都照旧，
        //   免得"关掉开关她还在喊缺件"这种状态变化引入新的困惑）
        if (!MaidBroomKit.enabled()) {
            MaidBroomDrive.dismount(maid, "扫帚模式总开关关着");
            notifyNotReady(maid, gameTime);
            return;
        }
        // ②【扫帚优先·v1.3.4】她身上没有扫帚、也没骑着 → **先去找一把**，再谈别的。
        // 玩家原话："扫帚模式应该优先找扫帚，而不是优先跟随主人。" 所以这一条排在
        // ③骑上、④接敌、⑤跟随**全部**之前：没扫帚时她不会去跟主人（那一段根本走不到），
        // 而是去找扫帚（见 MaidBroomDrive.seekBroom）——**首选拔世界里已经放着的那把扫帚实体，
        // 走过去骑上它**（玩家原话："让她去骑世界里已经放着的那把扫帚实体"）；
        // 没有实体可骑，才退一步捡地上掉的那件（主手/副手/背包/精妙背包里的由 ensureMounted
        // 直接取，那不叫"找"叫"取"）。找不到（或找了很久够不着）才报缺件待命。
        if (!MaidBroomKit.hasBroomItem(maid) && !MaidBroomKit.isRidingBroom(maid)) {
            MaidBroomDrive.dismount(maid, "手上没扫帚");
            // 【实测六百七十二：去抖】旧版这里一旦"没扫帚"就立刻去找/骑，于是"上一秒骑上、下一秒
            //  又下"时变成 5~11 次/秒的高频循环（实测日志 13:48:55 / 13:49:09 / 13:49:21 三次各
            //  5~11 行「骑上了世界里放着的那把扫帚」），而**每次重新骑上都会重开一次起飞相位**
            //  （+1.35 格）——那就是玩家看到的"女仆不断攀升"。现在要求"连续 1 秒都没有扫帚"才真的
            //  去找：正常玩法感觉不到（她本来也要走两步），抖动/循环时不会来回抽搐。
            if (!MaidBroomDrive.broomlessLongEnough(maid, gameTime)) {
                return;
            }
            if (MaidBroomDrive.seekBroom(level, maid)) {
                return; // 这一 tick 正在去找/刚骑上——本 tick 不做别的
            }
            notifyNotReady(maid, gameTime);
            return;
        }
        // ③ 未激活（缺远程武器 / 缺弹药）→ 下来、报缺件
        if (!MaidBroomKit.isModeActive(maid)) {
            MaidBroomDrive.dismount(maid, "缺远程武器/弹药");
            notifyNotReady(maid, gameTime);
            return;
        }
        // ④ 骑上（身上有扫帚物品就取出来放一把；已经骑着就原样返回）
        EntityBroom broom = MaidBroomDrive.ensureMounted(level, maid);
        if (broom == null) {
            notifyNotReady(maid, gameTime);
            return;
        }
        clearNotReady(maid, gameTime);

        // ④.4 玩家在开这把扫帚 → 我们只开火，不碰飞行（见 drivenByPlayer 的注释：
        // mixin 那边"有玩家驾驶就一个字不改"，这里跟着让位，否则每 tick 都会写一份没人用的
        // 推进意图、还会把爬升相位开起来每 tick 判"顶头"刷日志）
        if (MaidBroomDrive.drivenByPlayer(broom)) {
            MaidBroomDrive.clearClimb(maid);
            LivingEntity driven = currentTarget(maid);
            if (driven != null && driven.m_6084_() && driven.m_9236_() == level) {
                MaidBroomDrive.faceYawTo(broom, driven);
                MaidFlightCombatBehavior.fireRanged(maid, driven, maid.m_20148_(), gameTime);
            }
            notePlayerDriving(maid, gameTime);
            return;
        }

        // ④.5 起飞相位：原地往上抬 1 格再到别的地方去（玩家原话"如果拿到了扫帚，原地往上飞 1 格
        // 悬停（头顶如果被顶住了那就悬停在此处）"）。这一段不转向、不开火——就是那一下"腾空"。
        // 【v1.3.3：走 steerVerticalTo（扫帚坐标），不再拿她的坐标当目标点】见 Drive 里那段因果。
        Double riseY = MaidBroomDrive.takeoffTarget(maid);
        if (riseY != null) {
            MaidBroomDrive.steerVerticalTo(maid, riseY);
            return;
        }

        // ⑤ 有目标 → 先爬到它上方 8 格（顶头即就地悬停，并按实际高度定下本场盘旋高度）
        //    → 再绕着敌人盘旋 + 照搬远程空袭开火
        LivingEntity target = currentTarget(maid);
        if (target != null && target.m_6084_() && target.m_9236_() == level) {
            faceTarget(maid, target);
            Double climbY = MaidBroomDrive.combatClimbTarget(maid, target);
            if (climbY != null) {
                MaidBroomDrive.steerVerticalTo(maid, climbY);
                // 朝向放在 steerTo **之后**（steerTo 会用"速度方向"覆盖朝向；爬升是纯垂直、
                // 本来不改朝向，但顺序摆对了以后"不朝向目标"这一类 bug 不会再回来）
                MaidBroomDrive.faceYawTo(broom, target);
                MaidFlightCombatBehavior.fireRanged(maid, target, maid.m_20148_(), gameTime);
                return;
            }
            MaidBroomDrive.steerTo(maid, MaidBroomDrive.combatPoint(maid, target));
            // 朝向改成"看着目标"而不是"朝着速度方向"：她在绕着目标侧移，脸得对着它才像在射击。
            // **必须在 steerTo 之后**（前面那一版只有盘旋这一支摆对了位置，跟随那一支摆反了，
            // 于是每 tick 被速度方向盖掉 —— 见下面 ⑥ 的注释）。
            MaidBroomDrive.faceYawTo(broom, target);
            MaidFlightCombatBehavior.fireRanged(maid, target, maid.m_20148_(), gameTime);
            return;
        }

        // ⑥ 没目标 → 平时。三档，按优先级走：
        //    ①【v1.3.6 实测六百六十一】守家（工作范围）生效 → **沿着工作范围那个圈盘旋巡逻**，
        //       直到接敌。玩家原话：「如果我在扫把模式下开启鸿蒙，那个女仆正常就会在工作范围内
        //       对着工作范围那个圈进行盘旋，直到接敌。」旧版 home 对扫帚模式只剩"夹取目标点"，
        //       看不出守家——这一档就是补上"守家该有的样子"。
        //    ② 否则跟随主人（配置可关）
        //    ③ 都没有 → 原地悬停待命
        MaidBroomDrive.clearClimb(maid); // 打完/丢目标 → 爬升相位与本场盘旋高度一起作废
        noteHome(maid, gameTime); // v1.3.0(beta) 实测六百六十四：守家诊断（低频）
        // ⑥.0【实测六百六十九 → 六百七十二：武装拴绳只负责"绑住人"，不再给她定高度】
        //  669 原本是"绑上以后升到离地 tetherHover() 格悬停"，671 修掉了"找不到地面就自相对"的
        //  兜底，但"给她定一个绝对高度"这件事本身还在。实测反馈㊀："扫帚模式下反复横跳这个问题
        //  还是没有解决，依然会不断的攀升。" 空袭那一条（MaidFlightFollowBehavior.maidsmart$tetherHold）
        //  已经改成"只保持她自己当前高度"，扫帚这边现在**跟它同一个口径**：
        //  目标点 = 扫帚自己现在的位置 → 她原地悬停，高度一个字不改（steerTo 到点那一支会把速度
        //  乘 IDLE_DECAY 收干，绝不会掉下去）。"去哪"仍然由她自己的链路决定——接敌那一档（⑤）
        //  在这些之前，一个字没动，所以"接敌不变"照旧是字面意思。
        if (com.maidsmart.combat.GunnerTetherManager.isTethered(maid)) {
            MaidBroomDrive.hoverInPlace(maid);
            return;
        }
        if (MaidBroomKit.homeRestricted(maid)) {
            net.minecraft.world.phys.Vec3 patrol = MaidBroomDrive.homeOrbitPoint(maid);
            if (patrol != null) {
                MaidBroomDrive.steerTo(maid, patrol);
                // 朝向交给 steerTo 写的"速度方向"（= 圆周切线）——绕圈巡逻本来就该朝着前进方向。
                // 这里刻意**不**调 faceYawTo：那会让她横着飘，看着像侧滑。
                return;
            }
        }
        LivingEntity owner = ownerOf(maid);
        if (followEnabled() && owner != null && owner.m_6084_() && owner.m_9236_() == level) {
            faceTarget(maid, owner);
            if (shouldFollow(maid, owner)) {
                MaidBroomDrive.steerTo(maid, MaidBroomDrive.followPoint(maid, owner));
            } else {
                // 迟滞带内：**留在原地悬停**。v1.3.3：目标点取扫帚自己的位置
                // （hoverInPlace），不是她的——她是乘客、座位在朝向后方 0.5 格，
                // 用她的坐标会永远差半格、被一路推着走（玩家反馈的"主人在旁边时
                // 她在空中不停地旋转"就是这个回路，详见 MaidBroomDrive.hoverInPlace）。
                MaidBroomDrive.hoverInPlace(maid);
            }
            // 【顺序】朝向必须写在 steerTo **之后**：写在前面会被 steerTo 里的
            // "朝向 = 速度方向"整条盖掉，而"速度方向"对"原地悬停"那一支恰好是背对主人的
            // ——每 tick 翻 180°（那个"打转"的另一半原因）。
            MaidBroomDrive.faceYawTo(broom, owner);
        } else {
            // 原地悬停：目标点就是扫帚当前位置 → steerTo 走到"到点"那一支，速度收干
            MaidBroomDrive.hoverInPlace(maid);
        }
    }

    /* ==================== 平时跟随：与"飞行跟随"同款的起手/收手迟滞 ==================== */

    /** 这只女仆当前是否处于"跟随主人这一趟"里（迟滞带内不再起飞） */
    private static final Map<EntityMaid, Boolean> FOLLOWING =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 平时要不要飞过去跟主人——**照飞行跟随的同款机制**（玩家原话："引用飞行跟随的逻辑以及
     * 同款机制来决定是否启动跟随（这个是默认开启的，因为扫帚没有耐久）"）。
     *
     * <p>同款机制 = 那一对**起手 / 收手距离**（{@code flightFollow.start / end}，默认 25 / 5）：
     * 主人远过起手距离才开始跟，进到收手距离内就停下悬停。起手与收手是两个不同的球，
     * 中间那段就是迟滞带——没有它她会在阈值上"动一下停一下"地抖。
     * 这里**直接读同一对配置**，不另开一套数字：口径只有一处（本模组反复强调的红线）。
     *
     * <p>与飞行跟随的区别只剩"谁来飞"：那边要鞘翅 + 烟花且默认关（会烧料、磨耐久），
     * 这边是扫帚、没有耐久，所以开关是 {@code combat.broomFollow}（默认开）。
     */
    private static boolean shouldFollow(EntityMaid maid, LivingEntity owner) {
        double d = maid.m_20270_(owner);
        double start = followDistCfg();
        double end = Math.min(followEndDistCfg(), Math.max(1.0, start - 1.0));
        boolean following = Boolean.TRUE.equals(FOLLOWING.get(maid));
        if (!following && d > start) {
            following = true;
        } else if (following && d <= end) {
            following = false;
        }
        FOLLOWING.put(maid, following);
        return following;
    }

    /**
     * v1.3.0(beta) 实测六百六十四【扫帚模式自己的跟随距离】——玩家原话：「它没有一个像飞行跟随
     * 一样的调试跟随启动半径的选项（飞行跟随默认主人飞出了 20 格且没有视线阻拦以后再进行飞行。
     * 扫帚模式好像照搬了这个，但是没有任何程度上的调试面板。）」
     *
     * <p>旧版直接读 {@code [flightFollow]} 那一对（同一份数字管两件事），现在扫帚模式有自己的
     * 一对 {@code combat.broom.followStart / followEnd}（默认同为 25 / 5，面板「扫帚模式」板块可调）
     * ——口径分家是玩家点名要的"调试面板"；两条链路的物理照旧完全不同（那边烧烟花磨鞘翅，这边
     * 骑扫帚飞）。
     */
    private static double followDistCfg() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_BROOM_FOLLOW_START.get();
        } catch (Throwable ignored) {
            return 6.0; // 【实测六百七十五】与配置默认值对齐（默认 25 → 6，这个兜底也得跟）
        }
    }

    private static double followEndDistCfg() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_BROOM_FOLLOW_END.get();
        } catch (Throwable ignored) {
            return 3.0; // 【实测六百七十五】同上（默认 5 → 3）
        }
    }

    /* ==================== 守家诊断（v1.3.0(beta) 实测六百六十四） ==================== */

    /**
     * 「她现在的圈是怎么回事」——一行日志（状态变了马上打，否则 20 秒一条上限）。
     *
     * <p>【为什么必须有这一条】玩家反馈：「我刚刚用河童的罗盘画了一个圈，但女仆却不照着那个飞，
     * 而是绕着一个我根本就不知道的范围在飞行」。那个"圈"= {@code getRestrictCenter()} +
     * {@code getRestrictRadius()}，而圈心**按当前活动档（工作/休闲/睡眠）从 SchedulePos 的三个
     * 锚点里取**、半径按 TLM 配置取（本模组只抬高下限）——所以"圈在哪儿"取决于时刻与她身上的
     * 锚点，光看游戏里那一圈看不出是哪一档。这一行把 home 开关 / 活动档 / 圈心 / 圈心来源 / 半径 /
     * 三个锚点全打出来（{@link com.maidsmart.follow.WorkAreaClamp#describe}），一条日志就能定位。
     */
    private static void noteHome(EntityMaid maid, long gameTime) {
        try {
            String why = MaidBroomKit.homeRestricted(maid) ? "沿工作范围盘旋" : "不守家（跟主人/原地悬停）";
            String now = why + " || " + com.maidsmart.follow.WorkAreaClamp.describe(maid);
            if (now.equals(HOME_STATE.get(maid))) {
                Long t = HOME_LOGGED.get(maid);
                if (t != null && gameTime - t < HOME_LOG_GAP) {
                    return;
                }
            }
            HOME_STATE.put(maid, now);
            HOME_LOGGED.put(maid, gameTime);
            com.maidsmart.tool.PromaidLog.log("扫帚守家",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " " + now);
        } catch (Throwable ignored) {
        }
    }

    /** 同一条守家日志的最短间隔（tick）= 20 秒 */
    private static final long HOME_LOG_GAP = 400;
    private static final Map<EntityMaid, String> HOME_STATE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<EntityMaid, Long> HOME_LOGGED =
            Collections.synchronizedMap(new WeakHashMap<>());

    /* ==================== 目标 / 朝向 ==================== */

    /** 当前攻击目标：优先脑里的 ATTACK_TARGET（TLM 攻击任务与我们的行为都写这一条），其次实体层 target */
    private static LivingEntity currentTarget(EntityMaid maid) {
        try {
            var brain = maid.m_6274_();
            if (brain != null) {
                LivingEntity t = brain
                        .m_21952_(net.minecraft.world.entity.ai.memory.MemoryModuleType.f_26372_)
                        .orElse(null);
                if (t != null && t.m_6084_()) {
                    return t;
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

    private static LivingEntity ownerOf(EntityMaid maid) {
        try {
            // 先落到 Entity 再判：TLM 的 getOwner() 在两树的返回类型不同（一版是 Player 语义、
            // 一版直接是 LivingEntity），用中间变量后 `instanceof` 在两棵树里都是**有条件**模式
            // ——否则 forge 树的 --release 17 会以"无条件模式"直接编译失败。
            net.minecraft.world.entity.Entity o = maid.m_269323_();
            return o instanceof LivingEntity le ? le : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 让她**看着**目标（纯表现：子弹方向一直是按坐标算的，不看她的朝哪）。
     *
     * 【为什么两只都设】她骑在扫帚上，而 {@code EntityBroom} 本身是 {@code LivingEntity}——
     * MC 对"载具是 LivingEntity 的乘客"有一套"身体朝向跟着载具"的逻辑，所以光设她的 yRot
     * 可能每 tick 被覆写回去。**载具的朝向**由 {@link MaidBroomDrive#faceYawTo} 设（那一份是稳的），
     * 这里再补她的偏航与俯仰：俯仰（抬头/低头看目标）只有她自己的 XRot 能表达。
     *
     * <p>【v1.3.3：扫帚的朝向不在本类算了】原先这里另有一个 {@code yawTo(maid, target)}
     * 负责扫帚的偏航（从**她的**位置算）。现在扫帚的朝向统一走
     * {@link MaidBroomDrive#faceYawTo}（从**扫帚的**位置算）——"从她的位置算朝向"会把
     * "她动→朝向动→她再动"连成一个自引用回路，那正是"主人在旁边她就不停打转"的一半原因。
     * 同一份口径只留一处。
     */
    private static void faceTarget(EntityMaid maid, LivingEntity target) {
        try {
            double dx = target.m_20185_() - maid.m_20185_();
            double dz = target.m_20189_() - maid.m_20189_();
            double dy = (target.m_20186_() + 1.0) - (maid.m_20186_() + 1.0);
            double horiz = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float) (-Math.atan2(dx, dz) * 57.295776F);
            float pitch = (float) (-Math.atan2(dy, Math.max(horiz, 1.0E-4)) * 57.295776F);
            maid.m_146922_(yaw);
            maid.m_146926_(pitch);
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 缺件播报 ==================== */

    /**
     * 缺件时给她头顶一条气泡——**形状与空袭的 {@code MaidFlightCombatBehavior.notifyNotReady}
     * 完全一致**（入世界宽限 0.5 秒 → 缺件要连续站稳 2 秒才报 → 15 秒冷却 → 齐备也要站稳 2 秒
     * 才把冷却清零）。理由与空袭那边一字不差：我们自己的动作表现会把副手那件借走十几 tick，
     * 若"看到一拍就报/就清冷却"，气泡会每 10 秒一条、永远不停（空袭那边吃过的亏）。
     */
    private static void notifyNotReady(EntityMaid maid, long gameTime) {
        try {
            String missing = MaidBroomKit.missingParts(maid);
            if (missing == null) {
                // 判定竞态：其实是齐的 → 不误报（并让"齐备"重新计时）
                MISSING_SINCE.remove(maid);
                if (COMPLETE_SINCE.putIfAbsent(maid, gameTime) == null) {
                    return;
                }
                Long cSince = COMPLETE_SINCE.get(maid);
                if (cSince != null && gameTime - cSince >= STABLE_TICKS) {
                    COMPLETE_SINCE.remove(maid);
                    NOTIFY_READY.remove(maid);
                }
                return;
            }
            COMPLETE_SINCE.remove(maid);
            Long firstSeen = FIRST_SEEN.get(maid);
            if (firstSeen == null) {
                FIRST_SEEN.put(maid, gameTime);
                return;
            }
            if (gameTime - firstSeen < GRACE_TICKS) {
                return;
            }
            if (MISSING_SINCE.putIfAbsent(maid, gameTime) == null) {
                return;
            }
            Long mSince = MISSING_SINCE.get(maid);
            if (mSince != null && gameTime - mSince < STABLE_TICKS) {
                return;
            }
            Long ready = NOTIFY_READY.get(maid);
            if (ready != null && gameTime < ready) {
                return;
            }
            NOTIFY_READY.put(maid, gameTime + NOTIFY_COOLDOWN);
            com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 扫帚模式缺件：" + missing + "（原地待命中）");
            maid.getChatBubbleManager().addTextChatBubble("扫帚模式还差" + missing + "，先待着不动");
        } catch (Throwable ignored) {
        }
    }

    /** 齐备了：清缺件计时（冷却的清零走 notifyNotReady 里"齐备也要站稳"那条） */
    private static void clearNotReady(EntityMaid maid, long gameTime) {
        MISSING_SINCE.remove(maid);
    }

    /**
     * 玩家在开这把扫帚 → 15 秒最多一条日志（同 {@link #NOTIFY_COOLDOWN} 的节奏）。
     * <p>
     * 这是**必须能看见**的一种状态：此时她会正常开火，但**永远不会自己飞**（驾驶权在玩家），
     * 玩家看到的却是"女仆骑在扫帚上却不跟着我飞/不升空"。旧版这种情况一个字都不打，
     * 只能靠猜。现在留痕：日志搜「玩家在驾驶」。
     */
    private static void notePlayerDriving(EntityMaid maid, long gameTime) {
        try {
            Long ready = PLAYER_DRIVE_LOGGED.get(maid);
            if (ready != null && gameTime < ready) {
                return;
            }
            PLAYER_DRIVE_LOGGED.put(maid, gameTime + NOTIFY_COOLDOWN);
            com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 玩家在驾驶这把扫帚 → 扫帚模式只负责开火、不接管飞行（想让她自己飞，请让玩家下扫帚）");
        } catch (Throwable ignored) {
        }
    }

    /** 女仆 → 「玩家在驾驶」这条日志的下次可打时间（tick） */
    private static final Map<EntityMaid, Long> PLAYER_DRIVE_LOGGED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static boolean followEnabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_BROOM_FOLLOW.get();
        } catch (Throwable ignored) {
            return true;
        }
    }
}
