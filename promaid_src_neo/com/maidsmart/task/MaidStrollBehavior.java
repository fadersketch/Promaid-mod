package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 空闲散步行为（v1.1.0 实测一百八十三，反馈："增加女仆散步的频率和速度"）。
 *
 * TLM 原生散步 = RandomStroll.stroll(0.3F, 5, 3)（MaidBrain 字节码实证）：
 * 0.3 倍速、5 格半径、触发概率 0.0010×0.09²≈8e-6/tick——平均一两小时才随机走
 * 一次，表现为"女仆站桩不动、活动范围只有几格"。
 *
 * 本行为（core 优先级 50——低于 TLM core 最高 99，只在一切更优先行为都不跑时
 * 生效）按配置间隔给空闲女仆设一个散步目标：
 * - 门禁：战斗/自保/站桩任务占用（复用 isTaskOccupied——挖矿锁定/烹饪酿造/
 *   建造等）/已有移动目标（任务在走/在追人）/坐姿骑乘 → 一律不打扰
 * - 选点：散步半径内随机；排班/在家模式（restrictTo 生效）下以限制中心为圆心、
 *   钳制在限制半径内——配合 ScheduleRangeMixin 放大的「排班活动半径」，
 *   女仆散步范围跟着扩大
 * - 落点：本格可通过 + 头顶空 + 下方实心（水中/悬崖边不选）
 * - 一次性：设完目标即结束，导航接管走过去；下次冷却由 stop 侧登记
 */
public class MaidStrollBehavior extends Behavior<EntityMaid> {
    /** 下次散步时间（gameTime tick，按女仆）——start/stop 反复进出也不刷屏 */
    private static final Map<UUID, Long> NEXT_STROLL = new HashMap<>();
    /** 选点随机源（自有实例——不依赖实体 getRandom 的 SRG 名，与 AutoCombatSwitch 同款） */
    private static final java.util.Random RNG = new java.util.Random();

    public MaidStrollBehavior() {
        super(Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /**
     * 门禁的逐条名字（与 {@link #gates} 同序）——自检/诊断照这个顺序念。
     *
     * v1.2.2 实测六百二十：用户反馈「空闲散步移动速度调不了，0.1 倍速都跟快步跑一样」。
     * 速度这一半是配置下限卡死的（见 {@code MaidSmartConfig.MISC_STROLL_SPEED} 的注释），
     * 另一半是「她到底有没有在散步」得能一眼看出来——所以把门禁从
     * {@link #checkExtraStartConditions} 里抽出来，{@code /maid_smart stroll check}
     * 念的就是这几条。
     */
    public static final String[] GATES = {
            "\u603b\u5f00\u5173\u5173\u7740",           // 0 总开关关着
            "\u81ea\u4fdd\u4e2d",                       // 1 自保中
            "\u53c2\u6218\u4e2d",                       // 2 参战中
            "\u95f4\u9694\u672a\u5230",                 // 3 间隔未到
            "\u5df2\u6709\u79fb\u52a8\u76ee\u6807",     // 4 已有移动目标
            "\u6b63\u5728\u63a5\u6218",                 // 5 正在接战
            "\u5750\u59ff/\u9a91\u4e58",                // 6 坐姿/骑乘
            "\u4efb\u52a1\u5360\u7528"};                // 7 任务占用

    /**
     * 逐条算一遍门禁（true = 这一条把她挡住了）。{@link #checkExtraStartConditions} 与自检
     * **共用**这一份，免得「自检说会散步、实际不散步」这种口径漂移。
     *
     * 顺序与副作用与 一百八十三 那版逐条早退完全等价：早退只是省几次判断，
     * 每一条本身都是无副作用的只读查询（配置 / 持久化标签 / 记忆 / 姿态）。
     */
    public static boolean[] gates(ServerLevel level, EntityMaid maid) {
        boolean[] g = new boolean[GATES.length];
        try {
            g[0] = !MaidSmartConfig.MISC_STROLL_ENABLED.get();
            // 自保中不散步
            g[1] = ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData()
                    .getBoolean(com.maidsmart.combat.SelfPreservationBehavior.PRESERVE_TAG);
            // 本系统参战状态下不散步
            g[2] = com.maidsmart.combat.AutoCombatSwitch.isAutoCombatActive(maid);
            // 间隔节流
            long now = level.getGameTime();
            Long next = NEXT_STROLL.get(maid.getUUID());
            g[3] = next != null && now < next;
            // 已有移动目标（任务在走/其他系统在驱动）不打扰
            try {
                g[4] = maid.getBrain().getMemory(MemoryModuleType.WALK_TARGET).isPresent();
            } catch (Throwable t) {
                g[4] = true;
            }
            // 正在接战不打扰
            try {
                var atk = maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET);
                g[5] = atk.isPresent() && atk.get().isAlive();
            } catch (Throwable ignored) {
            }
            // 坐姿/乘骑不散步
            g[6] = maid.isMaidInSittingPose() || maid.getVehicle() != null;
            // 任务实质占用（挖矿/伐木锁定目标、烹饪/酿造站桩、建造未暂停、战斗任务）不散步
            g[7] = BridgeUpBehavior.isTaskOccupied(maid);
        } catch (Throwable ignored) {
        }
        return g;
    }

    /** 距离下一次散步还有多少 tick（没排上 = 0，负数也按 0 报） */
    public static long nextStrollIn(ServerLevel level, EntityMaid maid) {
        Long next = NEXT_STROLL.get(maid.getUUID());
        if (next == null) {
            return 0L;
        }
        return Math.max(0L, next - level.getGameTime());
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        for (boolean blocked : gates(level, maid)) {
            if (blocked) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        int r = Math.max(4, MaidSmartConfig.MISC_STROLL_RADIUS.get());
        float restrictR = maid.getRestrictRadius();
        BlockPos center = maid.blockPosition();
        // 实测五百六十二：受限与否必须用 hasRestriction（TLM 把它重写为
        // isHomeModeEnable）——旧版判 restrictR >= 0，但 EntityMaid 的半径在非
        // home 模式恒为 MaidNonHomeRange（8，构造器写入、永不为 -1），圈心还是
        // 零点 → 跟随模式永远选不出点，空闲散步在跟随状态下整段失效
        boolean restricted = maid.hasRestriction();
        if (restricted) {
            BlockPos cc = com.maidsmart.follow.WorkAreaClamp.circleCenter(maid);
            if (cc == null) {
                restricted = false; // home 但锚点没配好（圈心=零点）→ 不按圈钳
            } else {
                center = cc;        // getRestrictCenter——限制区中心
            }
        }
        BlockPos pick = null;
        for (int i = 0; i < 14; i++) {
            int dx = RNG.nextInt(r * 2 + 1) - r;
            int dz = RNG.nextInt(r * 2 + 1) - r;
            int dy = RNG.nextInt(7) - 3;
            if (dx * dx + dz * dz < 9) {
                continue; // 太近（<3 格）没意义
            }
            BlockPos p = center.offset(dx, dy, dz);
            if (restricted) {
                double dSq = p.distSqr(center);
                if (dSq > (double) (restrictR * restrictR)) {
                    continue; // 超出活动半径——原地出圈会被拉回，不选
                }
            }
            if (!isStandable(level, p)) {
                continue;
            }
            pick = p;
            break;
        }
        if (pick == null) {
            return; // 找不到落点——本轮放弃，stop 侧照常登记冷却顺延
        }
        float speed = (float) (double) MaidSmartConfig.MISC_STROLL_SPEED.get();
        BlockPosTracker tracker = new BlockPosTracker(pick);
        maid.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, tracker);
        maid.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(tracker, speed, 1));
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        return false; // 一次性——目标已设，导航接管走过去
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        NEXT_STROLL.put(maid.getUUID(),
                level.getGameTime() + Math.max(20, MaidSmartConfig.MISC_STROLL_INTERVAL.get()));
    }

    /**
     * 能不能站（本格空气 + 头顶空气 + 下方实心）。{@code public}：自检
     * （{@link MaidStrollCheck#go}）选测量落点时用的是同一条判据，两处不能各写一份。
     */
    public static boolean isStandable(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        if (!st.isAir()) {
            return false;
        }
        if (!level.getBlockState(p.offset(0, 1, 0)).isAir()) {
            return false;
        }
        BlockPos below = p.offset(0, -1, 0);
        return level.getBlockState(below).isRedstoneConductor(level, below);
    }

    /**
     * 找一个**能一路走过去**的远点（v1.2.2 实测六百二十，自检量速度用）。
     *
     * 八个水平方向各往前踩格子：走不下去就换方向，取「能连续站住的步数最多」的那条
     * （至少 {@code minRun} 步），返回那条直线上最后一格。这样 {@code /maid_smart stroll go}
     * 给出的目标点是她真能走到的——不会因为撞墙/掉坑半路停住，把速度测成 0。
     *
     * 找不到（她卡在窄坑里、四周都是墙）返回 null，自检会照实报「没找到可走的直线」。
     */
    public static BlockPos pickLongRun(ServerLevel level, EntityMaid maid, int max, int minRun) {
        BlockPos base = maid.blockPosition();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        BlockPos best = null;
        int bestRun = 0;
        for (int[] d : dirs) {
            int run = 0;
            BlockPos last = null;
            for (int k = 1; k <= max; k++) {
                BlockPos p = base.offset(d[0] * k, 0, d[1] * k);
                BlockPos stand = null;
                // 台阶上下各让一格：上坡/下坡也算走得过去
                for (int dy = 1; dy >= -1; dy--) {
                    BlockPos c = p.offset(0, dy, 0);
                    if (isStandable(level, c)) {
                        stand = c;
                        break;
                    }
                }
                if (stand == null) {
                    break;
                }
                run = k;
                last = stand;
            }
            if (run > bestRun) {
                bestRun = run;
                best = last;
            }
        }
        return bestRun >= minRun ? best : null;
    }
}
