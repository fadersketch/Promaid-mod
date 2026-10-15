package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/**
 * 「她该动却没动」滞留探针（诊断用，只读）。
 *
 * <p>链路走位独占（{@link com.maidsmart.task.MaidWorkTags#isChainDrive}）是唯一能整段掐掉跟随
 * start 与 {@code MoveToTargetSink} 的闸门。实测九百九十一之后闸门口径改为「挖矿/伐木标记 或
 * 她自己接上敌 / 本模组驱动器在开她」；{@code combat.takeOverMovement=false}（接管走位·关）时战斗腿直接不成立
 * （接战判定的间接来源多：ATTACK_TARGET / getTarget / 自保标记 / 最近 5 秒挨打或打人，单看日志
 * 分不出哪条腿在咬）。于是两个取消点各挂一发 {@link #note}：只有闸门确实咬住、且 <b>独占标记
 * 残留</b>（她已不在挖矿/伐木）或 <b>咬住却无人驱动</b>（没有攻击目标，战术/自保/喂的目标/飞行
 * 也都没在开她）才落一行。
 *
 * <p>{@link #sweep} 是另一路：战斗任务、离主人 16 格开外、闸门没咬、也没目标可打——把「闸门咬住」
 * 与「与闸门无关的滞留」分开（后者日志全哑，本身就是结论）。只读；本轮排查用，之后由修复替换。
 */
public final class CombatStallProbe {

    /** 每（女仆 × 站点）的节流戳——只用于限频，不参与任何游戏逻辑 */
    private static final java.util.Map<String, Long> LAST = new java.util.concurrent.ConcurrentHashMap<>();

    /** 节流窗口（毫秒）：同一女仆同一站点 5 秒最多一行 */
    private static final long THROTTLE_MS = 5000L;

    /** 滞留判定的最小距离（格）：16 格内算跟随已达成（与跟随每 tick 驱动的门槛同口径） */
    private static final double SWEEP_DIST = 16.0;

    /** 探针直接写 latest.log（不走 promaid.log 的开关——排查期不能被配置挡住） */
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private CombatStallProbe() {
    }

    /**
     * 两个取消点（跟随 start 取消 / MoveToTargetSink 取消）各调一发。
     * 判据（实测九百九十一与新闸门口径对齐）：闸门不咬 → 静默（那种情况根本不取消）；
     * 咬住了才看两种可疑——① <b>独占标记残留</b>（她已不在挖矿/伐木）；② <b>咬住却无人驱动</b>
     * （没有攻击目标、六个驱动器一个都没在开她——「接管走位」关掉后战斗腿直接不成立，
     *  也就到不了这里）。
     */
    public static void note(EntityMaid maid, String site) {
        try {
            if (!com.maidsmart.task.MaidWorkTags.isChainDrive(maid)) {
                return; // 闸没咬：这一下不可能因它滞留
            }
            Snap s = snap(maid);
            if (s == null) {
                return;
            }
            if (s.stale()) {
                emit(s, site, "标记残留");
                return;
            }
            if (!s.pilot() && !CombatWorkRange.hasAttackDesire(maid)) {
                emit(s, site, "接战无驱动");
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("CombatStallProbe", ignored);
        }
    }

    /**
     * 战斗任务、离主人远超 16 格、闸门没咬、也没目标可打：她"该跟却没跟"的另一半情形。
     * 另有驱动器的状态（扫帚/空袭/自由飞/牵引绳/指挥棒绑定/刷怪笼插火把）与不可动状态
     * （守家/坐下/乘客/睡觉/脑冻结）一并排除；第三方玩法模式（让位中）不判——那时的她
     * 本来就归那个模组管；DIY 模式不落盘（配置全关，行为本来就不一样）。
     */
    public static void sweep(EntityMaid maid) {
        try {
            Snap s = snap(maid);
            if (s == null || com.maidsmart.compat.MaidModeCompat.isSuspended(maid)
                    || !s.attack || !s.canMove || s.home || s.seated || s.dist < SWEEP_DIST
                    || s.combat || s.tag || s.pilot() || s.broom || s.flight || s.free
                    || s.tethered || s.baton || s.torch || com.maidsmart.config.MaidConfigPreset.isDiy()) {
                return;
            }
            emit(s, "no-gate", "无闸滞留");
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("CombatStallProbe", ignored);
        }
    }

    /** 一次只读快照——任何一项读失败都按 false/默认值处理，绝不让探针自己抛 */
    private static final class Snap {
        EntityMaid maid;
        String task = "?";
        String reason = "?";
        boolean tag;
        boolean mining;
        boolean wooding;
        boolean combat;
        boolean tactic;
        boolean preserve;
        boolean fed;
        boolean broom;
        boolean flight;
        boolean free;
        boolean attack;
        boolean canMove;
        boolean home;
        boolean seated;
        boolean tethered;
        boolean baton;
        boolean torch;
        double dist = -1.0;

        /** 独占标记在、可她已不在挖矿/伐木：标记是残留（该由任务切换清掉却没清） */
        boolean stale() {
            return tag && !mining && !wooding;
        }

        /** 有没有任何一个本模组的驱动器真在开她（有它，独占就是正当的） */
        boolean pilot() {
            return tactic || preserve || fed || broom || flight || free;
        }
    }

    /** 采集快照；不属于本模组的女仆 / 客户端侧 / 状态读不出来 → null（不判、不记） */
    private static Snap snap(EntityMaid maid) {
        if (maid == null || maid.level() == null || maid.level().isClientSide()
                || !com.maidsmart.tool.MaidScope.owned(maid)) {
            return null;
        }
        Snap s = new Snap();
        s.maid = maid;
        s.tag = maid.getPersistentData().getBoolean(com.maidsmart.task.MaidWorkTags.CHAIN_DRIVE_TAG);
        s.mining = com.maidsmart.task.MaidMineBehavior.isMining(maid);
        s.wooding = com.maidsmart.task.MaidWoodBehavior.isWooding(maid);
        s.combat = CombatWorkRange.inCombat(maid);
        s.reason = CombatWorkRange.combatProbeDesc(maid);
        s.tactic = MaidCombatTacticsBehavior.isActive(maid);
        s.preserve = maid.getPersistentData().getBoolean(SelfPreservationBehavior.PRESERVE_TAG);
        s.fed = NeutralThreatDriver.drivingFedTarget(maid);
        s.broom = MaidBroomKit.isBroomAirborne(maid);
        s.flight = MaidFlightKit.isFlightAirborne(maid);
        s.free = com.maidsmart.flight.MaidFreeFlightController.isControlling(maid);
        s.attack = com.maidsmart.task.MaidWorkTags.isAttackTask(maid);
        s.canMove = maid.canBrainMoving();
        s.home = maid.isHomeModeEnable();
        s.seated = maid.isMaidInSittingPose() || maid.isPassenger() || maid.isSleeping();
        s.tethered = GunnerTetherManager.isTethered(maid);
        s.baton = MaidRideKit.isBatonBound(maid);
        s.torch = com.maidsmart.task.MaidWorkTags.isSpawnerTorchRun(maid);
        s.task = maid.getTask() == null ? "null" : String.valueOf(maid.getTask().getUid());
        net.minecraft.world.entity.LivingEntity owner = maid.getOwner();
        if (owner != null && owner.level() == maid.level()) {
            s.dist = Math.sqrt(maid.distanceToSqr(owner));
        }
        return s;
    }

    /** 落一行（同女仆同站点 5 秒最多一条；表满即清空兜底防增长） */
    private static void emit(Snap s, String site, String why) {
        String key = s.maid.getUUID() + "|" + site;
        long now = System.currentTimeMillis();
        Long last = LAST.get(key);
        if (last != null && now - last < THROTTLE_MS) {
            return;
        }
        if (LAST.size() > 512) {
            LAST.clear();
        }
        LAST.put(key, now);
        LOG.info("[promaid/stall] why={} site={} maid=#{} {} task={} tag={} mine={} wood={} combat={}"
                        + " reason={} tactics={} preserve={} fed={} broom={} flight={} free={}"
                        + " attack={} canMove={} home={} seated={} dist={} mode={}",
                why, site, s.maid.getId(), com.maidsmart.tool.PromaidLog.nameOf(s.maid), s.task,
                s.tag, s.mining, s.wooding, s.combat, s.reason, s.tactic, s.preserve, s.fed,
                s.broom, s.flight, s.free, s.attack, s.canMove, s.home, s.seated, fmt(s.dist),
                com.maidsmart.config.MaidConfigPreset.mode());
    }

    /** 距离格式化（一位小数；负数 = 主人不在同维度，记 "-"） */
    private static String fmt(double d) {
        return d < 0 ? "-" : String.valueOf(Math.round(d * 10.0) / 10.0);
    }
}
