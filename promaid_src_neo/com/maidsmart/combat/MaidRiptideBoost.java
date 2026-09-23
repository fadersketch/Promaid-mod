package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.mixin.LivingEntitySpinAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * v1.2.4 实测六百四十一 / 六百四十二【激流三叉戟的推进剂改成"拟真烟花"：启动与行为效果走烟花那条路，
 * 数值照玩家在水里放同一把三叉戟（判附魔等级）再整体 ×1.3，动作仍是三叉戟那一记旋转、
 * 途中撞到的敌人照原版结算伤害】。
 *
 * ── 六百四十一 的需求（用户原话，五项反馈 ＋ 一条设计决定）──
 * <pre>
 *   ①"实测下来，起飞的时候女仆还是会飞的特别高。尤其是在配合到重锤的弹射起跳的时候，
 *     还是会轻松飞出100格。"
 *   ②"再配合三叉戟和重锤的时候女仆会在还没起飞的时候开始就打出一次风暴。"
 *   ③"单纯使用激流三叉戟进行近战空袭的时候，女仆会在落地之后愣一下再起飞。这一点对于实战是致命的。"
 *   ④"在飞行跟随状态下三叉戟的朝向似乎并不是主人。并且没有像烟花那样子的一旦主人在判定圈内就把矢量删去。"
 *   ⑤"远程空袭不认这把武器能够起飞。"
 *   ── 设计决定 ──
 *   "综合考虑，我决定还是将激流三叉戟在起飞/俯冲/飞行突进的链路改为拟真烟花，也就是实际上的
 *    启动和行为效果走烟花的路线，数值跟玩家在水中使用三叉戟（还要判定附魔等级）一致。但是动作
 *    会变成三叉戟的动作，并且对途中敌人造成对应的伤害。"
 * </pre>
 *
 * ── 六百四十二 的追加（用户原话）──
 * <pre>
 *   "现在使用激流三叉戟速度矢量又太慢了，激流三甚至还没有俯冲飞行自己飞得快，导致其缺乏实战价值。
 *    感觉要在现在的基础上所有数值乘以1.3倍。这个不难，只需要改一改数值就行了。"
 * </pre>
 * 于是把**力度**这一个量整体 ×1.3（{@link #PLAY_SCALE}）。**结构与物理常量一律不动**：递推系数
 * 0.5（原版烟花那一句的形状）、水阻力 0.80、收手阈值 0.35（滑翔常态巡航速度）、窗口上限 40 tick、
 * 旋转时长 20 tick——这些是"原版 / 滑翔的既有口径"，跟着缩放会失真（例：0.80×1.3 = 1.04 就成了
 * 加速）。力度一变，峰值速度、行程、窗口长度各随之涨约 1.35 倍，正是用户要的"比俯冲自己飞得快"。
 *
 * ── ①④ 的病根：旧版是"一次性冲量"，竖直分量谁也管不住 ──
 * 旧版（实测六百四十）点火给的是**一口气**：{@code v ← v×0.5 + 视线×力度}，之后只按玩家的空气
 * 阻力压**水平**（{@code v.y} 原样写回）。而鞘翅滑翔对竖直的衰减只有 **×0.98/tick**
 * （反编译实证 {@code LivingEntity.travel} 滑翔分支的收尾句是
 * {@code setDeltaMovement(v.multiply(0.99, 0.98, 0.99))}），抬头时重力还被削掉一大半——
 * 于是"起飞相位那 62° 的一口"换来的是**一路窜高**，再叠上重锤风爆把她自己掀飞的那一份
 * （风免刻意不管"她推自己"，见 {@link FriendlyWindGuard}；1.21.1 侧的闸门见
 * {@code MaidMaceSmashBehavior}），实测就是"轻松飞出 100 格"。
 *
 * ── 现在的口径（一句话）──
 * <pre>
 *   点火：力度 P0 = 原版 3.0×(1+等级)/4 × 1.3 × 折扣（默认不打折）  // 玩家水里那一记的 1.3 倍
 *   之后：每 tick 一道**烟花式的递推**，把**整个速度矢量**往"视线 × 当前力度"上拉：
 *           v ← v×0.5 + 视线×(当前力度/2)                      // 不动点 = 视线 × 当前力度
 *         当前力度按**玩家在水里的阻力** ×0.80/tick 递减         // 玩家那一记的速度就是这么掉的
 *   收手：力度掉到滑翔常态（0.35 格/tick）以下，或满 {@link #MAX_THRUST_TICKS}
 *   行程：I ≈ 7.6 / II ≈ 12.5 / III ≈ 17.3 格                // 按递推式逐 tick 累加（已含 ×1.3）
 *        （闭式 力度 ÷ (1 − 0.80) = 9.75 / 14.6 / 19.5 是**理想上限**：
 *          递推式的 0.5 收敛有一拍滞后，实际略小——{@link #travelBlocks} 就是照递推算的，
 *          日志里印的也是这个值）
 *   峰值：I ≈ 1.27 / II ≈ 1.90 / III ≈ 2.54 格/tick           // 六百四十二 起明显快过滑翔/俯冲常态
 * </pre>
 * 三件事因此成立：**竖直也归推进管**（不再有"只压水平、竖直原样留着"的漏洞，① 的窜高消失）；
 * **方向永远是她这一 tick 的视线**（调用方把视线摆在"该去的方向"，④ 的"朝向不是主人"消失）；
 * **整份推进是可以随时删掉的一个状态**（与烟花那枚火箭同款，④ 的"进判定圈要删矢量"成立）。
 *
 * ── 为什么是"烟花式递推"而不是玩家那种"一次性冲量" ──
 * 原版挂载烟花对骑手的推力**不是一次性的**（1.21.1 `FireworkRocketEntity.tick` 字节码实证）：
 * <pre>
 *   if (this.isAttachedToEntity() && rider.isFallFlying()) {
 *       Vec3 look = rider.getLookAngle();
 *       rider.setDeltaMovement(v.add(look.x*0.1 + (look.x*1.5 - v.x)*0.5, …y…, …z…));
 *   }
 *   // 即 v ← v×0.5 + 视线×0.85，向"1.7 倍视线"这个不动点收敛
 * </pre>
 * 用户要的正是这条路的**形状**（每 tick 拉着走、随她视线转、能被收掉、烧掉一件），
 * 而**数值**换成玩家在水里那一记：把上面那个 0.85 换成 {@code 力度/2}（不动点 = 力度），
 * 再让力度按水阻力 0.80 递减——于是"烟花的行为 + 水里的数值"两头都占。
 *
 * ── 与玩家那一记的两处差异（说明白）──
 * <ol>
 *   <li><b>形状</b>：玩家是"一口 impulse 然后被水吃掉"，这里是"每 tick 往视线方向拉"（烟花式）。
 *       这是设计决定里"行为效果走烟花的路线"要求的；也因此竖直分量才有人管（见上）。</li>
 *   <li><b>力度递减的那份阻力用的是"水"（0.80）</b>，不是上一版那把"空气"（0.91）。用户口径
 *       是"玩家**在水中**使用三叉戟"——水里那一记的速度就是这么掉的，行程也照它算
 *       （I 7.5 / II 11 / III 15 格）。旧版按空气算出来是 17/25/33 格，正是"还是太远"的一半原因。</li>
 *   <li><b>力度整体 ×1.3</b>（六百四十二）：用户实测"激流三甚至还没有俯冲飞行自己飞得快"——
 *       照搬玩家那一记在实战里偏慢，于是给**力度**乘 {@link #PLAY_SCALE}。只动这一个量：
 *       形状、阻力、收手阈值、旋转时长都保持"玩家 / 原版那一记"的原样。</li>
 * </ol>
 *
 * ── 动作与伤害（用户点名要留着的两件）──
 * <ul>
 *   <li><b>动作 = 三叉戟那一记</b>：照原版 {@code startAutoSpinAttack(20)} 摆出旋转
 *       （标志位 4 + {@link #AIR_SPIN_TICKS} 的旋转计数，画面由 Gecko 去重 mixin 负责），
 *       音效按等级三选一（`TRIDENT_RIPTIDE_1/2/3`）——不是"骑上烟花"那套姿势。</li>
 *   <li><b>途中敌人照原版结算伤害</b>：本类**不登记** {@code MaidTridentSpinBehavior} 的 DASH，
 *       所以撞到实体时由原版 `checkAutoSpinAttack` 口径走
 *       {@code MaidSpinAttackTouchMixin} → {@link MaidTridentSpinBehavior#onSpinTouch} 结算
 *       （攻击力 + 附魔加成 × 命中 → 击退 → 火焰附加 → 荆棘），与玩家"激流冲过去扫到怪"一致；
 *       命中后原版会自己清标志位（= 旋转收招），但**推力不受影响**（那是本类的窗口在管）。</li>
 * </ul>
 *
 * ── 四条腿与开关 ──
 * <ol>
 *   <li>起飞：{@code MaidFlightCombatBehavior#tryLaunch}（地面起跳后在空中点火）与
 *       {@code MaidFlightFollowBehavior#boost}；</li>
 *   <li>飞行跟随补推：同上（{@link #ignite} 由 {@code boostForFlight} 转调）；</li>
 *   <li>掉高抬升：{@link #liftForRanged}（先抬头再点火，见那里的注释）；</li>
 *   <li>俯冲冲刺：{@link #diveDash}（方向取她此刻的视线 = 朝敌人朝下扎）。</li>
 * </ol>
 * 总开关沿用 {@code combat.riptideDash}（默认开）；力度倍数 {@code combat.riptideFlightScale}
 * （默认 **1.0 = 不打折**，即上面那个"玩家在水里那一记 ×1.3"；范围 0.1~2.0——往 0.77 调 ≈ 回到
 * 六百四十一 的手感，往 2.0 调更猛）；飞行跟随那一路
 * 另有"不消耗三叉戟耐久"的省料开关 {@code flightFollow.trident}（只影响飞行跟随；战斗里的
 * 起飞/抬升/俯冲照旧扣）。
 *
 * ── 谁在驱动这一口气 ──
 * {@link #tick} 由两条飞行链每 tick 各调一次（空袭与飞行跟随的 {@code m_6725_}，都在各自相位派发
 * **之前**），且**只在她滑翔时生效**（收翅猛击、落地、链路已停都不再推）；另有 {@link #clear}
 * 显式收手（飞行跟随"进到主人判定圈内解除矢量"那一 tick、任务收手）与
 * {@link #forget}/{@link #clearAll} 的清场接线。
 *
 * ── 与近战突进（旋转冲击）的关系 ──
 * 本条只管**推进剂**。近战那一记（{@code MaidTridentSpinBehavior} 的 DASH）仍是"朝目标的突进"：
 * 力度、时长、撞人结算一字未动（那里要的就是原版那一口的爆发力），突进起手时会收掉本类的
 * 推进剂窗口（那十几 tick 的速度归突进）。共用只有 {@link MaidFlightKit#fetchRiptide}
 * （找三叉戟：主手 → 副手 → 背包 → 精妙背包/旅行者背包）。
 *
 * ── 设计迭代（留个明白账）──
 * <ol>
 *   <li><b>v1（六百四十 第一版）"隐形烟花"</b>：照烟花的递推式、固定点 = 原版力度 × 0.6。
 *       被用户点破："一枚'隐形烟花'？不应该照搬玩家在雨中飞行的速度嘛？"</li>
 *   <li><b>v2（六百四十）"玩家在雨里那一记"</b>：力度一分不打折 + 补上玩家的空气阻力（0.91），
 *       行程 17/25/33。实测仍然"起飞特别高、轻松 100 格"——因为那一版**竖直不衰减**（只压水平），
 *       而滑翔对竖直几乎不设防。</li>
 *   <li><b>v3（六百四十一，本版）"拟真烟花 + 玩家在水里那一记"</b>：形状回到烟花（每 tick
 *       拉着走、整份可删），数值与阻力都换成"水里"（力度 3.0×(1+等级)/4、阻力 0.80），
 *       于是竖直也被钉在视线方向上——① 的窜高与 ④ 的"不删矢量"一起解决。
 *       迭代的教训写在 ①：**病根不在"力度多大"，在"竖直分量没人管"**。</li>
 *   <li><b>v4（六百四十二，本版）"力度整体 ×1.3"</b>：v3 的形状对了，但力度偏保守——用户实测
 *       "激流三甚至还没有俯冲飞行自己飞得快，缺乏实战价值"。于是给力度乘 {@link #PLAY_SCALE}：
 *       III 级行程 12.9 → 17.3 格、峰值 1.95 → 2.54 格/tick、窗口 10 → 11 tick；
 *       形状与物理常量一字不动（v3 的解法没被推翻，只是拧大了力度那一档）。</li>
 * </ol>
 */
public final class MaidRiptideBoost {

    private MaidRiptideBoost() {
    }

    /** 原版冲量基数：{@code 3.0f * (1 + 激流等级) / 4.0f}（I 1.5 / II 2.25 / III 3.0 格/tick） */
    private static final double POWER_BASE = 3.0;

    /**
     * **实测调参系数（v1.2.4 实测六百四十二）：三叉戟推进的力度整体 ×1.3**。
     *
     * 用户实测原话："现在使用激流三叉戟速度矢量又太慢了，激流三甚至还没有俯冲飞行自己飞得快，
     * 导致其缺乏实战价值。感觉要在现在的基础上所有数值乘以1.3倍。这个不难，只需要改一改数值就行了。"
     *
     * 【为什么只乘力度】"所有数值"在这里只能是**力度**——递推系数 {@link #GAIN}（原版烟花那一句的
     * 0.5）、水阻力 {@link #WATER_DRAG}（0.80）、收手阈值 {@link #DECAY_UNTIL}（0.35 = 滑翔常态）、
     * 窗口上限 {@link #MAX_THRUST_TICKS}、旋转时长 {@link #AIR_SPIN_TICKS} 全是"原版 / 滑翔的既有
     * 口径"，乘上去就失真（例：0.80×1.3 = 1.04 变成加速）。力度一变，峰值速度、行程、窗口长度
     * 各随之涨约 1.35 倍，正是用户要的"比俯冲自己飞得快"。
     *
     * 【想回到六百四十一 的手感】把 {@code combat.riptideFlightScale} 调到 {@code 1 / 1.3 ≈ 0.77}。
     */
    private static final double PLAY_SCALE = 1.3;

    /**
     * 递推式的收敛系数：{@code v ← v×0.5 + 视线×(力度×0.5)}——**照原版挂载烟花的 0.5**
     * （字节码见类文档；原版那一条是 {@code v×0.5 + 视线×0.85}，不动点 = 2×0.85 = 1.7 倍视线）。
     * 本类把"不动点"换成激流的力度，系数不动——这就是"行为效果走烟花的路线"。
     */
    private static final double GAIN = 0.5;

    /**
     * **玩家在水里的阻力**：速度每 tick ×0.80（原版 {@code LivingEntity.travel} 的水中分支，
     * 玩家在水里用激流时那一口就是这么掉的）。
     *
     * 【为什么是水不是空气】用户口径逐字是"数值跟玩家在**水中**使用三叉戟一致"。上一版照"雨里"
     * （空气 0.91）算出来是 33 格（III 级），实测就是"还是太远"；水里那一记的闭式是
     * {@code 3.0 ÷ (1 − 0.80) = 15 格}，这才是玩家真正熟悉的那一记——女仆这边走烟花递推、
     * III 级实到约 12.9 格，与它同一个量级（差的 2 格是递推式那一拍滞后，见类文档"行程"那行）。
     * 六百四十二 起力度再 ×1.3（{@link #PLAY_SCALE}）→ 女仆 III 级实到约 17.3 格。
     */
    private static final double WATER_DRAG = 0.80;

    /**
     * 力度衰减到这个数（格/tick）就算"这一记放完了"——0.35 是**滑翔的常态巡航速度**
     * （飞行跟随的补推判据 `speed < 0.35` 用的就是它）。到这儿就把操纵权还给滑翔。
     */
    private static final double DECAY_UNTIL = 0.35;

    /**
     * 推进剂窗口的硬上限（tick，40 = 2 秒）。正常档根本用不到（III 级从 3.9 掉到 0.35 只要
     * 约 11 tick，见 {@link #decayTicks}）；它只防"力度被配置调得极大"这类意外，免得窗口无限期挂着。
     */
    private static final int MAX_THRUST_TICKS = 40;

    /**
     * 动作时长（tick）：**照原版 {@code startAutoSpinAttack(20)}**——那一记的旋转放 20 tick。
     * （推进剂窗口与它无关：窗口的长短由**力度衰减**决定，见 {@link #DECAY_UNTIL}。）
     */
    private static final int AIR_SPIN_TICKS = 20;

    /** 力度倍数读不到时的兜底值：与配置默认值一致（1.0 = 不打折，即"玩家在水里那一记 ×1.3"） */
    private static final double FALLBACK_SCALE = 1.0;

    /**
     * 一记推进剂的状态：
     * <ul>
     *   <li>{@code power0} = 点火那一 tick 的力度（格/tick，= 原版力度 × 折扣）；</li>
     *   <li>{@code step} = 已经推到第几步（每一次 {@link #pushOnce} 后 +1）。</li>
     * </ul>
     * 当前力度 = {@code power0 × 0.80^step}（水里那份阻力），所以窗口的进度只靠 {@code step}
     * 一个整数就能复原——不必另存速度。
     */
    private static final class Thrust {
        final double power0;
        int step;

        Thrust(double power0) {
            this.power0 = power0;
        }

        double powerNow() {
            return power0 * Math.pow(WATER_DRAG, step);
        }
    }

    /** 推进剂窗口：UUID → 这一记的状态（没有条目 = 没有推进剂挂着） */
    private static final Map<UUID, Thrust> THRUST = new HashMap<>();

    /** 总开关：{@code combat.riptideDash}（默认开）——与近战突进 / 六百三十三 的推进剂共用 */
    public static boolean enabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.RIPTIDE_DASH_ENABLE.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 力度倍数：{@code combat.riptideFlightScale}（默认 1.0 = 不打折 = "玩家在水里那一记 ×1.3"；读不到用兜底值） */
    private static double scale() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_RIPTIDE_FLIGHT_SCALE.get();
        } catch (Throwable ignored) {
            return FALLBACK_SCALE;
        }
    }

    /**
     * ① 远程空袭·掉高补推：**先把机头抬到 {@code pitch}（朝目标的水平方向 + 抬头）**，
     * 再点火。返回 true = 这一记已经点着（调用方按"这一口补推已发生"处理：记间隔、开抬头窗口、
     * 写日志）。
     *
     * @param pitch 仰角（度，负 = 抬头）：调用方传的 {@code airRaid.rangedBoostPitch}
     *              （默认 -45），与烟花/法术那两条腿的抬头窗口**同一个口径**。
     *              【六百四十一 起为什么更要紧】推进剂每 tick 把速度钉在"视线 × 当前力度"上，
     *              所以这一口**方向完全由视线决定**——抬头就抬升、平视就平飞（不再是"点火那一
     *              tick 一次性带上竖直分量"）。
     */
    public static boolean liftForRanged(ServerLevel level, EntityMaid maid, LivingEntity target, float pitch) {
        try {
            if (level == null || maid == null || target == null) {
                return false;
            }
            aimUpForward(maid, target, pitch);
            return ignite(level, maid, "空袭·激流", "掉高抬升", target, true);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * ② 俯冲段冲刺：方向取她**此刻的视线**——调用方（{@code tickDiveBoost}）在
     * {@code faceTarget} 之后才调这里，所以那一记天然是"朝着敌人、朝下扎"。
     * 本方法**不动她的朝向**（动了就把俯冲掰平了）。
     */
    public static boolean diveDash(ServerLevel level, EntityMaid maid) {
        try {
            if (level == null || maid == null) {
                return false;
            }
            return ignite(level, maid, "空袭·激流", "俯冲下扎", null, true);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ==================== 点火 / 每 tick 推 / 收手 ==================== */

    /**
     * 点着这一记：记下力度并**立刻推第一下**（同一 tick 不再重复），之后每 tick 由 {@link #tick}
     * 按烟花式递推继续拉，直到力度掉回滑翔常态（{@link #DECAY_UNTIL}）或满
     * {@link #MAX_THRUST_TICKS}。
     *
     * 【方向由调用方负责】本方法取的是 {@code maid.getLookAngle()}（她这一 tick 的视线）。
     * 空中起飞/俯冲那几路调用方已经用 {@code faceLaunchDirection} / {@code faceTarget} 摆好；
     * 飞行跟随则由 {@code faceToward(aim, false)} 钉在主人身上——**刻意不在这里改朝向**，
     * 否则会把调用方摆好的方向拧偏（六百四十一 的 ④ 就是旧版在这里多摆了一次"抬头"造成的）。
     *
     * 动作照原版那一记（旋转 {@link #AIR_SPIN_TICKS} + 标志位 4 + 按等级原版音效）；
     * 耐久 −1 与投掷共用同一条（{@code consumeDurability = false} 时跳过——飞行跟随的省料档）。
     *
     * @param channel 日志频道（{@code 空袭·激流} / {@code 激流起飞}，与改动前一致，方便验收 grep）
     * @param what    日志里的这一记叫什么（掉高抬升 / 俯冲下扎 / 激流推进）
     * @param target  仅用于日志（"距敌 N 格"）；俯冲/起飞那几路传 null
     */
    public static boolean ignite(ServerLevel level, EntityMaid maid, String channel, String what,
                                 LivingEntity target, boolean consumeDurability) {
        try {
            if (level == null || maid == null || !enabled()) {
                return false;
            }
            // v1.2.4 实测六百三十九：取用（不是判定）——主手/副手/背包都没有就问精妙背包/
            // 旅行者背包要（先看身上，再问额外容器；实测六百四十 起主手与副手重新算数，
            // 见 MaidFlightKit.findRiptide 的那段来龙去脉）
            ItemStack weapon = MaidFlightKit.fetchRiptide(maid);
            if (weapon.isEmpty()) {
                return false;
            }
            int lvl = riptideLevel(maid, weapon);
            double vanilla = POWER_BASE * (1.0 + lvl) / 4.0; // 玩家水里那一记：I 1.5 / II 2.25 / III 3.0
            double power = vanilla * PLAY_SCALE * scale();   // 再 ×1.3（六百四十二 实测调参）、折扣默认 1.0

            // ① 记状态并立刻推第一下（step 归 1：同一 tick 的 tick() 不会再推一次）
            Thrust t = new Thrust(power);
            pushOnce(maid, t);
            t.step = 1;
            THRUST.put(maid.getUUID(), t);

            // ② 动作：原版 startAutoSpinAttack 的那一套（旋转计数 + 标志位 4）
            LivingEntitySpinAccessor spin = (LivingEntitySpinAccessor) (Object) maid;
            spin.promaid$setSpinTicks(AIR_SPIN_TICKS);
            spin.promaid$setLivingFlag(4, true);

            // ③ 音效按等级（原版三选一）
            try {
                net.minecraft.sounds.SoundEvent snd = lvl >= 3
                        ? net.minecraft.sounds.SoundEvents.TRIDENT_RIPTIDE_3.value()
                        : (lvl == 2 ? net.minecraft.sounds.SoundEvents.TRIDENT_RIPTIDE_2.value()
                                    : net.minecraft.sounds.SoundEvents.TRIDENT_RIPTIDE_1.value());
                level.playSound(null, maid.blockPosition(), snd,
                        net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.0f);
            } catch (Throwable ignored) {
            }
            // ④ 耐久 −1（原版与投掷共用那一条；飞行跟随的省料开关走 consumeDurability = false）
            if (consumeDurability) {
                weapon.hurtAndBreak(1, maid, EquipmentSlot.MAINHAND);
            }

            com.maidsmart.tool.PromaidLog.log(channel, com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " " + what + "（激流 " + lvl + " 级，力度 " + fmt(power) + " 格/tick"
                    + (scale() >= 0.999 ? "" : "（折扣 " + fmt(scale()) + "）")
                    + "，照玩家在水里那一记 ×" + fmt(PLAY_SCALE) + "；水里那份阻力 ×0.80/tick → 约 "
                    + decayTicks(power)
                    + " tick、行程约 " + fmt(travelBlocks(power)) + " 格"
                    + (consumeDurability ? "" : "，不消耗耐久")
                    + (target == null ? "" : "，距敌 " + fmt(maid.distanceTo(target)) + " 格") + "）");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 每 tick 推一下（由空袭与飞行跟随两条飞行链各调一次，都在各自相位派发**之前**）：
     * {@code v ← v×0.5 + 视线 × (当前力度/2)}——**整个矢量**（含竖直）都被拉向"视线 × 当前力度"，
     * 当前力度按水阻力 ×0.80 递减。
     *
     * 【什么时候收】力度已经掉回滑翔常态（{@link #DECAY_UNTIL}）= 这一记放完了；或满
     * {@link #MAX_THRUST_TICKS}。收掉之后操纵权完全还给滑翔（包括飞行跟随自己的补推判据
     * ——它认的正是同一个 0.35）。
     *
     * 【为什么必须"只在她滑翔时生效"】推进剂是"挂载烟花"那一档的东西——她一旦不在滑翔
     * （收翅猛击、落地），这条通道就不成立；而且链路已经不在跑时也没人再驱动它。
     */
    public static void tick(EntityMaid maid) {
        try {
            if (maid == null) {
                return;
            }
            UUID id = maid.getUUID();
            Thrust t = THRUST.get(id);
            if (t == null) {
                return;
            }
            if (!enabled() || !MaidFlightKit.isGliding(maid) || t.step >= MAX_THRUST_TICKS) {
                THRUST.remove(id); // 开关被关掉 / 她不在滑翔（收翅猛击、落地）/ 到期 → 交还操纵权
                return;
            }
            if (t.powerNow() <= DECAY_UNTIL) {
                THRUST.remove(id); // 力度放完了 = 这一记到头了
                return;
            }
            pushOnce(maid, t);
            t.step++;
        } catch (Throwable ignored) {
        }
    }

    /**
     * 收手：把推进剂窗口撤掉（返回 true = 本来还有一记挂着，供日志用）。
     *
     * 【这就是"像烟花那样把矢量删去"】推进剂现在与那枚火箭是同一个东西（每 tick 都在拉她的速度），
     * 所以飞行跟随"进到主人判定圈内"那一 tick 必须先把它**收掉**，再清零速度
     * （{@code MaidFlightFollowBehavior#releaseThrust} 就是这个顺序）——否则下一 tick 它又把速度
     * 拉回来，清零等于白清（六百一十二 收火箭那条教训一字不差地适用）。
     *
     * 【已给的速度**不动**】本方法只停"接下来还要不要继续拉"，不做任何额外的加减速——
     * "威胁收手"那几档要的正是**保持动量**自然滑翔。两件事分开，语义才不会打架。
     */
    public static boolean clear(EntityMaid maid) {
        try {
            return maid != null && THRUST.remove(maid.getUUID()) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * v1.2.4 实测六百四十三：这一只此刻是否**挂着激流推进剂窗口**（只读，诊断用）。
     *
     * 【为什么要它】用户报"用别的飞行道具（烟花）能开火、用激流三叉戟就不射"——
     * 空袭的开火诊断行（{@code [promaid/远程开火]}）要把"这一 tick 挂着哪一路推进剂"一起印出来，
     * 才能把"不射"与具体那一路推进剂对上号。本方法只查表，不改任何状态。
     */
    public static boolean isThrusting(EntityMaid maid) {
        try {
            return maid != null && THRUST.containsKey(maid.getUUID());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 清场（女仆移除 / 死亡 / 服务器停止） */
    public static void forget(UUID maidId) {
        if (maidId != null) {
            THRUST.remove(maidId);
        }
    }

    public static void clearAll() {
        THRUST.clear();
    }

    /* ==================== 内部：矢量 / 朝向 / 数字 ==================== */

    /**
     * 推一下：{@code v ← v×0.5 + 视线×(当前力度/2)}（原版挂载烟花那一条的同一个形状，
     * 不动点从 1.7 换成激流的力度）。
     *
     * 【为什么这一句能把"飞得特别高"治住】旧版只压水平、{@code v.y} 原样留着，而鞘翅滑翔对竖直
     * 几乎不衰减（×0.98/tick）；这一句把**竖直也拉向"视线 × 当前力度"**，所以视线一旦平视/俯视，
     * 她就不会继续往上窜——与挂载烟花的行为一致。
     */
    private static void pushOnce(EntityMaid maid, Thrust t) {
        Vec3 look = maid.getLookAngle();
        Vec3 v = maid.getDeltaMovement();
        double add = t.powerNow() * GAIN;
        maid.setDeltaMovement(v.x * GAIN + look.x * add,
                v.y * GAIN + look.y * add,
                v.z * GAIN + look.z * add);
    }

    /** 从 power0 掉到滑翔常态（0.35）要几 tick（按水阻力 ×0.80）——只用于日志 */
    private static int decayTicks(double power0) {
        try {
            if (power0 <= DECAY_UNTIL) {
                return 1;
            }
            int k = (int) Math.ceil(Math.log(DECAY_UNTIL / power0) / Math.log(WATER_DRAG));
            return Math.max(1, Math.min(MAX_THRUST_TICKS, k));
        } catch (Throwable ignored) {
            return 1;
        }
    }

    /**
     * 这一记的水平行程（格）——**照递推式本身算**（从静止起算的近似值），只用于日志。
     *
     * 【为什么不再用 power ÷ (1 − 阻力) 那个闭式】递推式里的 0.5 收敛让它比"理想几何级数"略小
     * （每 tick 只走 {@code v} 的 0.5 增益那一份），闭式会把行程说大 1~2 格。日志宁可说保守值。
     */
    private static double travelBlocks(double power0) {
        try {
            double v = 0.0;
            double sum = 0.0;
            for (int k = 0; k < MAX_THRUST_TICKS; k++) {
                double p = power0 * Math.pow(WATER_DRAG, k);
                if (p <= DECAY_UNTIL) {
                    break;
                }
                v = v * GAIN + p * GAIN;
                sum += v;
            }
            return sum;
        } catch (Throwable ignored) {
            return power0;
        }
    }

    /** 日志里的小数（两位，定点免得不同 JVM 打出科学计数法） */
    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }

    /**
     * 把机头抬到"朝目标的水平方向 + {@code pitch} 仰角"，并立即写角度。
     *
     * 【为什么自己写一遍而不复用空袭里的 {@code faceUpForward}】这一套要能在空袭之外独立使用
     * （将来别的链路要用激流抬升不必依赖空袭的实例方法）。角度写法与那份**保持一致**
     * （立即写 yaw/pitch + 同步 prev 值 + 同步头部/身体朝向）：只改 LookControl 要等下一 tick
     * 才生效，而推进剂**每 tick 都按她的视线推**——不立即写角度，这一记就会沿旧视线推出去
     * （= 白抬）。
     */
    private static void aimUpForward(EntityMaid maid, LivingEntity target, float pitch) {
        double dx = target.getX() - maid.getX();
        double dz = target.getZ() - maid.getZ();
        double dh = Math.sqrt(dx * dx + dz * dz);
        if (dh < 1.0E-4) {
            dx = 0.0;
            dz = 1.0;
            dh = 1.0;
        }
        float yaw = (float) (net.minecraft.util.Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        maid.setYRot(yaw);
        maid.setXRot(pitch);
        maid.yRotO = yaw;
        maid.xRotO = pitch;
        maid.setYHeadRot(yaw);
        maid.setYBodyRot(yaw);
        try {
            double tan = Math.tan(Math.toRadians(-pitch));
            maid.getLookControl().setLookAt(
                    maid.getX() + dx / dh, maid.getEyeY() + tan, maid.getZ() + dz / dh,
                    360.0f, 360.0f);
        } catch (Throwable ignored) {
        }
    }

    /** 读激流等级（1.21.1 用 TLM 的 EnchantmentKeys，与 MaidFlightKit.isRiptide 同一套） */
    private static int riptideLevel(EntityMaid maid, ItemStack stack) {
        try {
            return com.github.tartaricacid.touhoulittlemaid.datagen.EnchantmentKeys
                    .getEnchantmentLevel(maid.level().registryAccess(),
                            net.minecraft.world.item.enchantment.Enchantments.RIPTIDE, stack);
        } catch (Throwable ignored) {
            return 1;
        }
    }
}
