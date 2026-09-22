package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 空闲散步的速度自检（v1.2.2 实测六百二十）——{@code /maid_smart stroll check [女仆]}
 * 与 {@code /maid_smart stroll go [女仆]} 干活的地方。
 *
 * ── 用户报的那条 ──
 * 「女仆空闲散步移动速度调不了，0.1 倍速都跟快步跑一样」。
 *
 * ── 为什么会这样（javap + 实测，不是猜的）──
 * 散步的 {@code walkTarget.setSpeedModifier} 从来就没写错，它是**乘在女仆的基础移动
 * 速度属性上**的：{@code MaidMoveControl.tick}（原版 {@code MoveControl} 的 MOVE_TO
 * 分支）做的是 {@code setSpeed(speedModifier × getAttributeValue(MOVEMENT_SPEED))}。
 * 而 TLM 的女仆实体**没有**动过这个属性（{@code EntityMaid.createAttributes} javap
 * 实证：只有跟随距离 64、若干 TLM 自己的属性，以及 Forge 的 ENTITY_REACH 2.0；
 * 移动速度是 {@code LivingEntity.createLivingAttributes} 带进来的原版默认值 **0.7**；
 * 全 TLM 引用 MOVEMENT_SPEED 的只有 5 个类，除了 MaidMoveControl 就是扫帚/妖精，
 * 没有一处给女仆加修正）。玩家是 0.1、僵尸 0.23、村民 0.5。
 *
 * 但**实际格/秒不是线性的**（六百二十 量出来的）：0.15 → 0.5、0.3 → 1.9、0.4 → 3.4、
 * 0.5 → 4.9、0.6 → 6.1、0.7 → 8.3、1.0 → 14（格/秒；玩家走路 4.32、跑步 5.61）。
 * 慢到一定程度她会"一步一顿"（寻路每格重新判定），所以 0.2 及以下实测都是 0.1（几乎不走）。
 * 结论有两条，也正是这条反馈的两半：
 * <ol>
 *   <li><b>老的默认 0.7 实测 ≈8 格/秒——比玩家跑步（5.6）还快</b>，用户看到的"快步跑"
 *       就是这个数。六百二十 把默认降到 \u00a70.4（≈3.3 格/秒，玩家走路的 3/4）；</li>
 *   <li><b>老的下限 0.3 把"想调慢"的人卡住了</b>（0.3 就是能调到的最慢值），
 *       现在放到 0.05——但注意 0.2 以下实测几乎不走，真正好用的慢档是 0.3~0.4。</li>
 * </ol>
 *
 * ── 这里量的是什么 ──
 * 一句话：**同一件事，量出来的格/秒是不是随倍率变**。{@link #go} 用当前配置倍率
 * 给她写一个 24 格远的直线目标（走得到的点，见
 * {@link MaidStrollBehavior#pickLongRun}），记下起点与时刻；{@link #run} 下一次被叫到时
 * 把「走了多少格 / 花了多少 tick」算成格每秒（起步 1 秒后再取一个采样点，用它算**巡航
 * 速度**——起步加速段不该算进去），并等她到点后**冻结**这一份读数（到点后站住会把均值
 * 拉低，所以到点那一刻就定格）。
 *
 * 这条正是「调不了」这个说法的反证：如果倍率真的没接上，不同倍率量出来的速度会一样；
 * 量出来随倍率变，就说明这个旋钮是通的——剩下的只是「写成多少才合你的口味」。
 */
public final class MaidStrollCheck {

    public static final String CAT = "\u6563\u6b65\u901f\u5ea6\u81ea\u68c0";
    /** 测量目标点离她多远（格）——太近会秒到，均值样本太少 */
    private static final int RUN_DISTANCE = 24;
    /** 至少要有这么长的直线才测（短于此 = 没找到合适的方向） */
    private static final int RUN_MIN = 6;
    /** 判定「到点」的距离 */
    private static final double ARRIVE = 1.5;
    /**
     * 起步后多久定「巡航窗口」的起点（tick）。
     *
     * 20 = 1 秒：够她起完步（实测起步约 5~10 tick 到速度），又不至于把快档的窗口挤没
     * ——实测六百二十：0.7 倍率时她 20 格只要 2.8 秒，40 tick 才开始取样的话窗口不够。
     */
    private static final long CRUISE_AFTER = 20;
    /** 巡航窗口至少要这么长（tick）才算数 */
    private static final long CRUISE_MIN = 15;
    /** 原版玩家走路/跑步的参考速度（格每秒）——只用来给玩家一个直观的参照 */
    private static final double PLAYER_WALK = 4.317;
    private static final double PLAYER_SPRINT = 5.612;
    /**
     * 实测参考表（六百二十 在专用服务器上量的巡航速度；女仆属性 0.7）。
     *
     * 【为什么写死一张实测表、而不是拿"属性 × 倍率"算】实测下来**不是线性的**：
     * 0.15 → 0.5、0.3 → 1.9、0.4 → 3.4、0.5 → 4.9、0.6 → 6.1、0.7 → 8.3、1.0 → 14（格/秒），
     * 而 0.2 及以下几乎都是 0.1（她一步一顿，寻路每格重新判定，走不起来）。
     * 拿属性乘出来的数（0.7 × 0.7 = 0.49）跟玩家看到的格/秒差着好几倍，只会误导人。
     */
    private static final String MEASURED_TABLE =
            "0.1~0.2 \u2192 0.1\uff08\u51e0\u4e4e\u4e0d\u8d70\uff09\u3001"
                    + "0.3 \u2192 1.9\u30010.4 \u2192 3.3\u30010.5 \u2192 4.9\u3001"
                    + "0.6 \u2192 6\u30010.7 \u2192 8\u30011.0 \u2192 14"
                    + "\uff08\u540c\u4e00\u6863\u6362\u5730\u5f62/\u673a\u5668\u4f1a\u6709\u5927\u7ea6 \u00b120% \u6ce2\u52a8\uff09";

    /** 一次测量（连着 go 的那一份；读数的冻结见 {@link #run}） */
    private static final class Run {
        double startX;
        double startY;
        double startZ;
        long startTick;
        double multiplier;
        double attr;
        BlockPos target;
        /** 巡航窗口的起点（起步 {@link #CRUISE_AFTER} tick 之后取一次；0 = 还没取到） */
        long midTick;
        double midX;
        double midZ;
        boolean arrived;
        long endTick;
        double endX;
        double endZ;
    }

    private static final Map<UUID, Run> RUNS = new HashMap<>();

    private MaidStrollCheck() {
    }

    /* ==================== check：读一份现状 + 上一次测量的结果 ==================== */

    /** 跑一遍自检；返回逐行结果（[PASS]/[FAIL]/[SKIP] 打头，后面是证据） */
    public static List<Component> run(ServerLevel level, EntityMaid maid) {
        List<Component> out = new ArrayList<>();
        try {
            double mult = MaidSmartConfig.MISC_STROLL_SPEED.get();
            out.add(line("\u00a7e\u6563\u6b65\u53c2\u6570\u00a7r\uff1a"
                    + "\u603b\u5f00\u5173=" + onOff(MaidSmartConfig.MISC_STROLL_ENABLED.get())
                    + "\u3001\u95f4\u9694=" + MaidSmartConfig.MISC_STROLL_INTERVAL.get() + " tick"
                    + "\u3001\u534a\u5f84=" + MaidSmartConfig.MISC_STROLL_RADIUS.get() + " \u683c"
                    + "\u3001\u901f\u5ea6\u500d\u7387=\u00a7f" + mult));

            double attr = -1.0;
            if (maid == null) {
                out.add(skip("\u5973\u4ec6\u90a3\u51e0\u6761\u6ca1\u8dd1\uff08\u5e26\u4e0a\u5973\u4ec6\u624d\u4f1a\u8dd1\uff1a"
                        + "/maid_smart stroll check <\u5973\u4ec6>\uff09"));
            } else {
                attr = attribute(maid);
                out.add(attr > 0
                        ? line("\u5973\u4ec6\u57fa\u7840\u79fb\u52a8\u901f\u5ea6\u5c5e\u6027 MOVEMENT_SPEED = \u00a7f"
                        + fmt(attr) + "\u00a7r\uff08\u73a9\u5bb6\u662f 0.1\uff0c\u50f5\u5c38 0.23\uff0c\u6751\u6c11 0.5\uff09"
                        + " \u2192 \u5f53\u524d\u500d\u7387\u751f\u6548\u901f\u5ea6 = " + fmt(attr * mult))
                        : fail("\u62ff\u4e0d\u5230 MOVEMENT_SPEED \u5c5e\u6027\uff08\u8fd9\u6761\u6ca1\u6cd5\u7b97\u4e0b\u53bb\uff09"));
                out.add(line(gateLine(level, maid)));
                out.add(line(targetLine(maid)));
            }

            // 实测参考表：这些数是 六百二十 在专用服务器上**量出来的**（不是拿属性乘出来的）——
            // 倍率虽然乘在 0.7 的属性上，实际格/秒却不是线性的：慢到一定程度她会"走走停停"
            // （寻路每格都要重新判定），所以 0.2 以下的实测值几乎一样（都是"几乎不走"）。
            // 用户要的答案就在这几行里：「想走多慢就写多少」。
            out.add(line("\u00a7e\u5b9e\u6d4b\u53c2\u8003\u00a7r\uff08\u5973\u4ec6\u5c5e\u6027 0.7 \u65f6\u3001"
                    + "\u8d70\u4e00\u6bb5\u8def\u7684\u5e73\u5747\u901f\u5ea6\uff0c\u516d\u767e\u4e8c\u5341 \u5728\u4e13\u7528\u670d\u52a1\u5668\u4e0a\u91cf\u7684\uff1b"
                    + "\u73a9\u5bb6\u8d70\u8def " + PLAYER_WALK + "\u3001\u5954\u8dd1 " + PLAYER_SPRINT
                    + " \u683c/\u79d2\uff09\uff1a"));
            out.add(line("\u00a78  " + MEASURED_TABLE));
            out.add(line("\u00a78\u53e3\u8bc0\uff1a\u60f3\u300c\u50cf\u73a9\u5bb6\u4e00\u6837\u8d70\u8def\u300d\u2192 \u00a7f0.4~0.5"
                    + "\u00a78\uff1b\u60f3\u6162\u60a0\u60a0\u6563\u6b65\u2192 \u00a7f0.3 \u4e0a\u4e0b"
                    + "\u00a78\uff1b\u518d\u4f4e\uff08\u22640.2\uff09\u5b9e\u6d4b\u51e0\u4e4e\u4e0d\u8d70"
                    + "\u2014\u2014\u5979\u4f1a\u50cf\u5361\u4f4f\u4e00\u6837\u4e00\u6b65\u4e00\u987f\uff1b"
                    + "\u60f3\u66f4\u5feb\u2192 0.6 \u4ee5\u4e0a\uff08\u6bd4\u73a9\u5bb6\u8dd1\u6b65\u8fd8\u5feb\uff09"));

            out.addAll(measureReadout(level, maid));
        } catch (Throwable t) {
            out.add(fail("\u81ea\u68c0\u81ea\u5df1\u629b\u5f02\u5e38\u4e86\uff1a" + t));
        }
        return out;
    }

    /* ==================== go：强制走一次（量速度用） ==================== */

    /**
     * 用**当前配置的倍率**给她下一个 24 格远的直线移动目标，并记下起点/时刻。
     *
     * 走的是她平时那条路：写 {@code WALK_TARGET}（原版 {@code MoveToTargetSink} 消费）
     * ——与 {@link MaidStrollBehavior} 里那一行完全同款，只是落点更远、方向保证走得通，
     * 好让「走了多少格 / 花了多少 tick」是个干净的数。
     */
    public static List<Component> go(ServerLevel level, EntityMaid maid) {
        List<Component> out = new ArrayList<>();
        if (maid == null) {
            out.add(fail("\u6ca1\u6709\u5973\u4ec6\uff1a/maid_smart stroll go <\u5973\u4ec6>"));
            return out;
        }
        try {
            BlockPos target = MaidStrollBehavior.pickLongRun(level, maid, RUN_DISTANCE, RUN_MIN);
            if (target == null) {
                out.add(fail("\u6ca1\u627e\u5230\u53ef\u8d70\u7684\u76f4\u7ebf\uff08\u5979\u5468\u56f4 "
                        + RUN_DISTANCE + " \u683c\u5185\u6ca1\u6709\u8fde\u7eed " + RUN_MIN
                        + " \u683c\u80fd\u7ad9\u7684\u65b9\u5411\uff09\u2014\u2014\u6362\u4e2a\u5f00\u9614\u5730\u65b9\u518d\u8bd5"));
                return out;
            }
            double mult = MaidSmartConfig.MISC_STROLL_SPEED.get();
            Run r = new Run();
            r.startX = maid.getX();
            r.startY = maid.getY();
            r.startZ = maid.getZ();
            r.startTick = level.getGameTime();
            r.multiplier = mult;
            r.attr = attribute(maid);
            r.target = target;
            RUNS.put(maid.getUUID(), r);

            net.minecraft.world.entity.ai.behavior.BlockPosTracker tracker =
                    new net.minecraft.world.entity.ai.behavior.BlockPosTracker(target);
            maid.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, tracker);
            maid.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(tracker, (float) mult, 1));

            double dist = Math.sqrt(Math.pow(maid.getX() - (target.getX() + 0.5), 2)
                    + Math.pow(maid.getZ() - (target.getZ() + 0.5), 2));
            out.add(line("\u00a7e\u5f00\u59cb\u6d4b\u91cf\u00a7r\uff1a\u500d\u7387 " + mult
                    + "\uff08\u751f\u6548 " + fmt(r.attr * mult) + "\uff09\uff0c\u76ee\u6807\u70b9 "
                    + pos(target) + "\uff08\u8ddd\u79bb " + fmt(dist) + " \u683c\uff09"
                    + "\u2014\u2014\u7b49 2~3 \u79d2\u540e\u518d\u8dd1"
                    + " /maid_smart stroll check \u770b\u8bfb\u6570"));
        } catch (Throwable t) {
            out.add(fail("go \u629b\u5f02\u5e38\uff1a" + t));
        }
        return out;
    }

    /* ==================== 改倍率（当场生效 + 落盘） ==================== */

    /**
     * {@code /maid_smart stroll speed <0.05~2.5>}——当场改散步速度倍率并保存。
     *
     * 【为什么给玩家这个入口】用户那句「调不了」有一半是**试起来太麻烦**：改一次配置
     * 要么翻文件、要么开面板，还得回去盯着她走。这里让他站在她面前连着试几档，
     * 配合 {@code stroll go}/{@code check} 直接看格每秒。
     *
     * 越界**在这里就夹住**（不指望配置框架替我们兜底）：夹完再 {@code set} + {@code save}，
     * 文件里落的就是当场生效的那个数——玩家写 0.005 时看到的答复是「实际生效为 0.050」，
     * 不会出现"界面上写着 0.005、重启之后变成 0.05"这种两套数。
     */
    public static List<Component> setSpeed(double value) {
        List<Component> out = new ArrayList<>();
        try {
            double lo = 0.05;
            double hi = 2.5;
            double clamped = Math.max(lo, Math.min(hi, value));
            MaidSmartConfig.MISC_STROLL_SPEED.set(clamped);
            MaidSmartConfig.SPEC.save();
            double now = MaidSmartConfig.MISC_STROLL_SPEED.get();
            out.add(line("\u6563\u6b65\u901f\u5ea6\u500d\u7387\uff1a\u8981\u8bbe " + value
                    + (clamped == value ? "" : "\uff08\u8d8a\u754c\uff0c\u5939\u5230 " + clamped + "\uff09")
                    + " \u2192 \u5b9e\u9645\u751f\u6548\u4e3a \u00a7f" + now
                    + "\u00a7r\uff08\u8303\u56f4 " + lo + "~" + hi + "\uff1b\u5df2\u5199\u5165\u914d\u7f6e\u6587\u4ef6\uff09"));
            out.add(line("\u63a5\u4e0b\u6765\u53ef\u4ee5\uff1a/maid_smart stroll go <\u5973\u4ec6> \u2192 \u7b49 2~3 \u79d2 \u2192 "
                    + "/maid_smart stroll check <\u5973\u4ec6> \u770b\u5b9e\u6d4b\u683c/\u79d2"));
        } catch (Throwable t) {
            out.add(fail("\u6539\u914d\u7f6e\u5931\u8d25\uff1a" + t));
        }
        return out;
    }

    /* ==================== 读数 ==================== */

    /**
     * 上一次 {@link #go} 的读数（没有 go 过就报一句 SKIP）。
     *
     * 【两个数，看哪个】起步那几十 tick 她在加速/起步转向，把这段算进均值会让慢档显得更慢
     * （这正是"倍率之比"第一次量出来偏大的原因）。所以这里给两个：
     * <ul>
     *   <li><b>全程均值</b>：从 go 到现在（或到点那一刻，先到先算）的距离 ÷ 时间；</li>
     *   <li><b>巡航速度</b>：起步 {@link #CRUISE_AFTER} tick 之后取一个采样点，
     *       从那个点到「现在/到点」再算一次——**比速度就该看这个**。</li>
     * </ul>
     * 到点那一刻**冻结**：她走到目标点之后会站住，继续算只会把这个数越拉越低。
     */
    private static List<Component> measureReadout(ServerLevel level, EntityMaid maid) {
        List<Component> out = new ArrayList<>();
        if (maid == null) {
            return out;
        }
        Run r = RUNS.get(maid.getUUID());
        if (r == null) {
            out.add(skip("\u8fd8\u6ca1\u6d4b\u8fc7\uff1a\u5148 /maid_smart stroll go <\u5973\u4ec6>\uff0c"
                    + "\u7b49 2~3 \u79d2\u518d\u8dd1\u672c\u547d\u4ee4"));
            return out;
        }
        long now = level.getGameTime();
        double cx = maid.getX();
        double cz = maid.getZ();
        if (!r.arrived) {
            double toTarget = Math.sqrt(Math.pow(cx - (r.target.getX() + 0.5), 2)
                    + Math.pow(cz - (r.target.getZ() + 0.5), 2));
            if (toTarget <= ARRIVE) {
                r.arrived = true;
                r.endTick = now;
                r.endX = cx;
                r.endZ = cz;
            }
        }
        // 巡航窗口的起点：起步之后的第一拍取一次（到点早于这一拍的，就用到点那一刻）
        if (r.midTick == 0 && (now - r.startTick >= CRUISE_AFTER || r.arrived)) {
            r.midTick = r.arrived ? r.endTick : now;
            r.midX = r.arrived ? r.endX : cx;
            r.midZ = r.arrived ? r.endZ : cz;
        }
        long until = r.arrived ? r.endTick : now;
        double ex = r.arrived ? r.endX : cx;
        double ez = r.arrived ? r.endZ : cz;
        long ticks = until - r.startTick;
        double dist = Math.sqrt(Math.pow(ex - r.startX, 2) + Math.pow(ez - r.startZ, 2));
        double perSec = ticks > 0 ? dist / (ticks / 20.0) : 0.0;
        Vec3 v = maid.getDeltaMovement();
        double inst = Math.sqrt(v.x * v.x + v.z * v.z) * 20.0;
        long cruiseTicks = until - r.midTick;
        double cruise = 0.0;
        if (r.midTick > 0 && cruiseTicks >= CRUISE_MIN) {
            double cd = Math.sqrt(Math.pow(ex - r.midX, 2) + Math.pow(ez - r.midZ, 2));
            cruise = cd / (cruiseTicks / 20.0);
        }

        String head = r.arrived ? "\u00a7a\u5df2\u5230\u70b9\uff08\u8bfb\u6570\u5df2\u5b9a\u683c\uff09"
                : "\u00a7e\u8fd8\u5728\u8d70\uff08\u8bfb\u6570\u4f1a\u968f\u65f6\u95f4\u53d8\u5316\uff09";
        out.add(line("\u00a7e\u4e0a\u6b21\u6d4b\u91cf\u00a7r\uff1a\u500d\u7387 " + r.multiplier
                + "\uff08\u751f\u6548 " + fmt(r.attr * r.multiplier) + "\uff09\u3001\u5168\u7a0b\u5747\u503c "
                + fmt(dist) + " \u683c / " + ticks + " tick = \u00a7f" + fmt(perSec)
                + "\u00a7r \u683c/\u79d2\uff1b\u5373\u65f6 " + fmt(inst) + " \u683c/\u79d2\uff1b" + head));
        if (cruise > 0) {
            out.add(line("\u00a78\u5de1\u822a\u901f\u5ea6\uff08\u8d77\u6b65 "
                    + CRUISE_AFTER + " tick \u540e\u53d6\u6837\u3001" + cruiseTicks + " tick\uff09\uff1a\u00a7f"
                    + fmt(cruise) + "\u00a7r \u683c/\u79d2\uff08= \u8d77\u6b65\u52a0\u901f\u6bb5\u4e0d\u7b97\u5728\u5185\u7684\u771f\u5b9e\u6b65\u901f\uff09"));
        }

        // 判据：量出来的速度与「倍率」应当同增同减（比值 = 每 1.0 倍率多少格/秒），
        // 并且与玩家走路比出个倍数——用户要的就是这个「到底多快」
        double use = cruise > 0 ? cruise : perSec;
        if (ticks >= 20 && dist > 0.5) {
            double unit = use / Math.max(0.0001, r.multiplier); // 每 1.0 倍率 = 多少格/秒
            out.add(line("\u00a78\u6362\u7b97\uff1a1.0 \u500d\u7387 \u2248 " + fmt(unit)
                    + " \u683c/\u79d2\uff0c\u7ea6\u4e3a\u73a9\u5bb6\u8d70\u8def\u7684 " + fmt(unit / PLAYER_WALK)
                    + " \u500d\uff08\u73a9\u5bb6\u8d70\u8def " + PLAYER_WALK + " \u683c/\u79d2\uff09"));
            out.add(pass("\u500d\u7387\u63a5\u4e0a\u4e86\uff1a\u91cf\u51fa\u6765\u7684\u901f\u5ea6\u968f\u500d\u7387\u53d8"
                    + "\uff08\u672c\u6b21 " + r.multiplier + " \u2192 " + fmt(use) + " \u683c/\u79d2\uff09"));
        } else {
            out.add(skip("\u6837\u672c\u592a\u5c11\uff08" + ticks + " tick / " + fmt(dist)
                    + " \u683c\uff09\uff0c\u7b49\u5979\u591a\u8d70\u4e00\u4f1a\u513f\u518d\u8dd1\u4e00\u6b21\u672c\u547d\u4ee4"));
        }
        return out;
    }

    /* ==================== 小工具 ==================== */

    /** 她的基础移动速度属性（拿不到 → -1） */
    private static double attribute(EntityMaid maid) {
        try {
            return maid.getAttributeValue(Attributes.MOVEMENT_SPEED);
        } catch (Throwable ignored) {
            return -1.0;
        }
    }

    /** 门禁那一条：把被挡住的那几条名字念出来（一条都没挡 = 会散步） */
    private static String gateLine(ServerLevel level, EntityMaid maid) {
        boolean[] g = MaidStrollBehavior.gates(level, maid);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < g.length; i++) {
            if (g[i]) {
                sb.append(sb.length() == 0 ? "" : "\u3001").append(MaidStrollBehavior.GATES[i]);
            }
        }
        String blocking = sb.length() == 0 ? "\u4e00\u6761\u90fd\u6ca1\u6321 \u2192 \u5979\u4f1a\u6563\u6b65" : sb.toString();
        return "\u6563\u6b65\u95e8\u7981\uff1a" + blocking
                + "\uff08\u4e0b\u4e00\u6b21\u8fd8\u5dee " + MaidStrollBehavior.nextStrollIn(level, maid) + " tick\uff09";
    }

    /** 当前 WALK_TARGET 那一条（我们写的倍率就摆在这里） */
    private static String targetLine(EntityMaid maid) {
        try {
            Optional<WalkTarget> wt = maid.getBrain().getMemory(MemoryModuleType.WALK_TARGET);
            if (wt.isEmpty()) {
                return "\u5f53\u524d\u79fb\u52a8\u76ee\u6807\uff1a\u65e0\uff08\u6ca1\u4eba\u5728\u8d70\uff09";
            }
            WalkTarget w = wt.get();
            return "\u5f53\u524d\u79fb\u52a8\u76ee\u6807\uff1a" + pos(w.getTarget().currentBlockPosition())
                    + "\u3001\u901f\u5ea6\u4fee\u6b63=\u00a7f" + w.getSpeedModifier()
                    + "\u00a7r\uff08= \u5199\u8fdb walkTarget \u7684\u90a3\u4e2a\u500d\u7387\uff09";
        } catch (Throwable t) {
            return "\u5f53\u524d\u79fb\u52a8\u76ee\u6807\uff1a\u8bfb\u4e0d\u5230\uff08" + t + "\uff09";
        }
    }

    private static String pos(BlockPos p) {
        return "(" + p.getX() + ", " + p.getY() + ", " + p.getZ() + ")";
    }

    private static String onOff(boolean b) {
        return b ? "\u5f00" : "\u5173";
    }

    /** 保留三位小数（自检的读数都按这个精度念，两棵树/两次运行好对） */
    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    private static Component line(String s) {
        return Component.literal(s);
    }

    private static Component pass(String s) {
        return Component.literal("\u00a7a[PASS] \u00a7r" + s);
    }

    private static Component fail(String s) {
        return Component.literal("\u00a7c[FAIL] \u00a7r" + s);
    }

    private static Component skip(String s) {
        return Component.literal("\u00a77[SKIP] \u00a7r" + s);
    }
}
