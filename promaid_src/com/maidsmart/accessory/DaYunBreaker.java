package com.maidsmart.accessory;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.List;

/**
 * 符合条件的方块直接击毁。
 * <p>【判据（需求原文）】"使用鞘翅飞行的过程中可以直接击毁自身周边 5×5 范围内除
 * 硬度大于等于黑曜石以及硬度&lt;0 的一切方块"。
 * <ul>
 *   <li><b>触发条件</b>：真的在滑翔（{@code m_21255_}，即滑翔位为真）——站立/坐骑/
 *       扫帚悬停都不算。女仆侧另有"必须是空袭模式"的要求（见 {@code DaYunBauble}）。</li>
 *   <li><b>范围</b>：以她所站格为中心、前后左右上下各 {@code (边长-1)/2} 格。
 *       边长默认 5（= 5×5×5，125 格），实测八百一十三 起由配置项
 *       {@code accessory.daYunRange} 可调（1~15，偶数自动进一位取奇数保对称）。</li>
 *   <li><b>硬度门槛</b>：只清 {@code 0 <= hardness < 50}。黑曜石正好 50 → 被排除；
 *       基岩/屏障/命令方块等为 -1 → 被排除；石头 1.5、深板岩 3、铁块 5 都照清。</li>
 *   <li><b>容器安全</b>：带方块实体（箱子/熔炉/漏斗/潜影盒/刷怪笼…）整个跳过，绝不破坏。</li>
 *   <li><b>掉落</b>：普通方块按原版战利品表掉落（与原版挖掘一致）。</li> </ul> <p>【性能】每 tick 最多处理 {@code maxBlocksPerTick} 格；顺序是"先收集、清完再统一掉落"， 不会出现"方块还在、物品先掉"的中间态。范围调大后，每 tick 光是"扫一遍空气"的次数也按 边长的立方上升（空中大多为空气、不消耗破坏预算），所以超大范围建议同时把 {@code daYunMaxPerTick} 也调大。 <p>本类两树各一份：Forge 树用 SRG 名（本文件），Neo 树用 Mojmap 名。
 */
public final class DaYunBreaker {

    private DaYunBreaker() {
    }

    /** 黑曜石硬度——正好 50；需求"硬度大于等于黑曜石"即 {@code >= 50} 一律不清。 */
    public static final float OBSIDIAN_HARDNESS = 50.0f;

    /** 半边长默认值：2 → 5×5×5（前后左右上下各 2 格）。等价于 {@link #RANGE_DEFAULT} 的边长。 */
    public static final int HALF = 2;

    /** v1.5.398 实测八百一十三：破坏范围（立方体**边长**）的默认值与可调区间。 */
    public static final int RANGE_DEFAULT = 5;
    public static final int RANGE_MIN = 1;
    public static final int RANGE_MAX = 15;

    /**
     * 原版「方块被破坏」事件号——{@code level.m_46796_(2001, pos, Block.m_49956_(state))} 让客户端
     * 喷出该方块自己的破坏碎屑并播放破坏音（同玩家手挖）。本模组挖矿/伐木/种植/搭桥全用它。
     */
    public static final int BLOCK_BREAK_EVENT = 2001;

    /**
     * 前瞻系数：把这一 tick 的速度乘上它当"要看多远"（实测八百一十）。
     * v1.3.2 实测八百五十八：3.0 → 4.0（鞘翅带烟花/俯冲时速度明显超平飞，系数太小会算近）。
     */
    private static final double AHEAD_FACTOR = 4.0D;

    /**
     * 前瞻距离上限（格）。v1.3.2 实测八百五十八：3.0 → 8.0——旧上限只按"平飞 0.6~1.2 格/tick"
     * 定的，高速（烟花加速/俯冲）时"这一 tick 会走到哪"算得比实际近，墙还是撞上才清。
     * 8 格足以覆盖现实里鞘翅能到的速度；再快也是 8 格前瞻 + 立方体半宽，实际覆盖更远。
     */
    private static final double AHEAD_MAX = 8.0D;

    /**
     * 前瞻距离下限（格）。v1.3.2 实测八百五十八：速度被墙撞没（已经贴墙/卡住）时旧版前瞻整段跳过
     * （reach=0），只能等下一 tick 中心段救，观感是"贴墙愣一下"。给个最小前瞻，配上"贴着墙就沿
     * 视线方向清"，能立刻把自己顶出来。
     */
    private static final double AHEAD_MIN = 2.5D;

    /**
     * v1.5.398 实测八百一十三：把配置里的「边长」规整成合法值——
     * 偶数进一位（{@code 4 → 5}，保证以她所站格为中心时上下左右对称）、再夹进
     * {@code [RANGE_MIN, RANGE_MAX]}。这样界面上只填一个数，效果就是那个数乘自己乘自己。
     */
    public static int normalizeSide(int side) {
        if ((side & 1) == 0) {
            side++;
        }
        if (side < RANGE_MIN) {
            side = RANGE_MIN;
        }
        if (side > RANGE_MAX) {
            side = RANGE_MAX;
        }
        return side;
    }

    /** 边长 → 半边长：5 → 2（即 ±2 格 = 5×5×5）。非法值先经 {@link #normalizeSide}。 */
    public static int halfOf(int side) {
        return normalizeSide(side) / 2;
    }

    /**
     * 对一只正在滑翔的实体执行一次破坏（用默认范围，等价于旧的 5×5×5）。
     * @param level            服务端世界
     * @param entity           执行者（女仆或玩家）
     * @param maxHardness      硬度上限（&lt; 此值才清；配置项，默认 {@link #OBSIDIAN_HARDNESS}）
     * @param skipContainers   true = 带方块实体的一律跳过（容器安全）
     * @param dropItems        true = 按战利品表掉落；false = 直接消失 @param breakParticles   true = 喷原版「方块被破坏」粒子+音效（实测八百一十的开关） @param maxBlocksPerTick 单 tick 上限（性能闸） @return 实际清掉的方块数
     */
    public static int sweep(ServerLevel level, LivingEntity entity,
                            float maxHardness, boolean skipContainers,
                            boolean dropItems, boolean breakParticles, int maxBlocksPerTick) {
        return sweep(level, entity, maxHardness, skipContainers, dropItems, breakParticles,
                maxBlocksPerTick, null, RANGE_DEFAULT, false);
    }

    /**
     * <p>根因是时序：滑翔的移动碰撞在
     * {@code travel} 里结算，而本模组是在移动**之后**才清方块——所以撞上墙的那一 tick
     * 他已经被挡住了，下一 tick 墙才消失。观感就是"一顿一顿地撞过去"。
     * <p>（女仆侧不明显，是因为空袭本来是上下翻飞的打法，而且她常从高处掠过；玩家一路
     * 平飞赶路时才觉得别扭。）
     * <p>现在在**同一个 tick 内**先沿速度方向把前方一段的方块清掉，再让移动照常结算： 用他的速度反推"这一 tick 会走到哪"（{@code 速度 × 前瞻系数}），沿途按每 1 格采样， 对每个采样点做一次 N×N×N 的 {@link #clearAround}。于是墙在他撞上之前就没了， 移动不会被挡、也就没有顿挫。 @param motion 本 tick 的速度矢量；null = 不前瞻（等价于旧行为） @param side   破坏立方体的**边长**（实测八百一十三 起可调；偶数自动进一位）
     */
    public static int sweepAhead(ServerLevel level, LivingEntity entity,
                                 float maxHardness, boolean skipContainers,
                                 boolean dropItems, boolean breakParticles, int maxBlocksPerTick,
                                 net.minecraft.world.phys.Vec3 motion, int side) {
        return sweep(level, entity, maxHardness, skipContainers, dropItems, breakParticles,
                maxBlocksPerTick, motion, side, false);
    }

    /**
     * 【R1126】空袭状态专用：本轮空袭里她可能主动收翅（猛击/激流/贴地那几拍），
     * 此时滑翔位为假，但玩家看到的仍然是「空袭途中」——大运不该整段失效。
     * 调用方只在 `!onGround() && isFlightAirborne()` 时传 true，落地后照旧不吃。
     */
    public static int sweepAheadAirRaid(ServerLevel level, LivingEntity entity,
                                        float maxHardness, boolean skipContainers,
                                        boolean dropItems, boolean breakParticles,
                                        int maxBlocksPerTick,
                                        net.minecraft.world.phys.Vec3 motion, int side,
                                        boolean allowNonGliding) {
        return sweep(level, entity, maxHardness, skipContainers, dropItems, breakParticles,
                maxBlocksPerTick, motion, side, allowNonGliding);
    }

    /**
     * 「本 tick 会碰到的那些格」——开路与撞击（{@code DaYunSmash}）共用的范围口径。
     * <p>顺序即优先级：前瞻采样点（由近到远、每 1 格一个）在前、自身那一格在后——破坏按这个
     * 顺序花预算，于是"这一 tick 要撞上的墙"优先于"脚边"（见 {@code sweep}）。
     * <p>前瞻段的数值与旧 {@code sweep} 内联那段完全一致（{@link #AHEAD_FACTOR} / {@link #AHEAD_MAX} /
     * {@link #AHEAD_MIN}）；{@code motion} 为 null 时只剩自身那一格。撞击拿这份列表逐格取实体，
     * 所以"破坏范围内"两边指同一片地方（边长由调用方自己的 {@code half} 定）。
     */
    public static List<BlockPos> coveredCenters(LivingEntity entity, net.minecraft.world.phys.Vec3 motion) {
        List<BlockPos> centers = new ArrayList<>();
        if (entity == null) {
            return centers;
        }
        BlockPos base = entity.m_20183_();
        if (motion != null) {
            // m_7096_/m_7098_/m_7094_ = Vec3.x()/y()/z()——注意不是 f_82479_ 那组字段，
            // 本模组读 Vec3 分量一律走 Position 接口的这三个访问器（见 DaYunWallPhase#exitParam）
            double vx = motion.m_7096_();
            double vy = motion.m_7098_();
            double vz = motion.m_7094_();
            double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
            double dx;
            double dy;
            double dz;
            double reach;
            if (speed > 1.0E-4) {
                // 前瞻距离 = clamp(速度 × 系数, 最小, 上限)：系数 4 / 上限 8 格——带烟花/俯冲的
                // 高速也能覆盖"这一 tick 会走到哪"；最小 2.5 格保证低速同样有前瞻。
                dx = vx / speed;
                dy = vy / speed;
                dz = vz / speed;
                reach = Math.min(Math.max(speed * AHEAD_FACTOR, AHEAD_MIN), AHEAD_MAX);
            } else {
                // 【v1.3.2】速度被墙撞没了（已经贴墙/卡住）：改沿**视线方向**清一段把自己顶出来。
                // 旧版这里 speed≈0 → 前瞻整段跳过，只能等中心段救 —— 观感是"贴墙愣一下"。
                net.minecraft.world.phys.Vec3 look = entity.m_20154_(); // getLookAngle
                double lx = look.m_7096_();
                double ly = look.m_7098_();
                double lz = look.m_7094_();
                double ls = Math.sqrt(lx * lx + ly * ly + lz * lz);
                if (ls > 1.0E-4) {
                    dx = lx / ls;
                    dy = ly / ls;
                    dz = lz / ls;
                    reach = AHEAD_MIN;
                } else {
                    dx = 0.0D;
                    dy = 0.0D;
                    dz = 0.0D;
                    reach = 0.0D;
                }
            }
            int steps = (int) Math.ceil(reach);
            for (int step = 1; step <= steps; step++) {
                double t = Math.min(step, reach);
                centers.add(BlockPos.m_274561_(
                        base.m_123341_() + 0.5 + dx * t,
                        base.m_123342_() + 0.5 + dy * t,
                        base.m_123343_() + 0.5 + dz * t));
            }
        }
        centers.add(base);
        return centers;
    }

    private static int sweep(ServerLevel level, LivingEntity entity,
                             float maxHardness, boolean skipContainers,
                             boolean dropItems, boolean breakParticles, int maxBlocksPerTick,
                             net.minecraft.world.phys.Vec3 motion, int side,
                             boolean allowNonGliding) {
        if (level == null || entity == null || !entity.m_6084_()) {
            return 0;
        }
        // 【必须先判滑翔】需求原文："使用鞘翅飞行的过程中"——只有真在滑翔才动手
        if (!entity.m_21255_() && !allowNonGliding) {
            return 0;
        }
        final int half = halfOf(side);
        int cleared = 0;
        // 【R1105】每 tick 破坏粒子/音效配额：只给前 N 格发 levelEvent（方块照破、掉落照给）。
        // 用一个单元素数组在"多个中心段"之间共享，避免每段都重新起算。
        final boolean fxOn = breakParticles && com.maidsmart.config.MaidSmartConfig.DAYUN_PARTICLE_LIMIT.get();
        final int[] fxLeft = {fxOn ? com.maidsmart.config.MaidSmartConfig.DAYUN_PARTICLE_CAP.get() : Integer.MAX_VALUE};
        List<BlockPos> removed = new ArrayList<>();
        List<BlockState> removedStates = new ArrayList<>();

        // 【v1.3.2 实测八百五十八：前瞻段【先跑】】——高速时"这一 tick 要撞上的墙"优先于"脚边"，
        // 且旧版中心段先跑可能吃光 maxBlocksPerTick 预算、恰好在最需要前瞻的时候把前瞻饿死。
        // 范围口径统一收在 coveredCenters（前瞻采样点在前、自身那一格在后）——撞击（DaYunSmash）共用同一份。
        for (BlockPos center : coveredCenters(entity, motion)) {
            if (cleared >= maxBlocksPerTick) {
                break;
            }
            cleared += clearAround(level, entity, center, maxHardness, skipContainers,
                    breakParticles, half, maxBlocksPerTick - cleared, fxLeft, removed, removedStates);
        }

        if (dropItems) {
            for (int i = 0; i < removed.size(); i++) {
                // 与原版挖掘同款的掉落结算（战利品表 + 方块自身掉落）
                Block.m_49881_(removedStates.get(i), level, removed.get(i), null,
                        entity, ItemStack.f_41583_);
            }
        }
        return cleared;
    }

    /**
     * 以 {@code center} 为中心清边长 {@code half*2+1} 的立方体；返回清掉的格数
     * （受 {@code budget} 限制）。
     */
    private static int clearAround(ServerLevel level, LivingEntity entity, BlockPos center,
                                   float maxHardness, boolean skipContainers, boolean breakParticles,
                                   int half, int budget, int[] fxLeft,
                                   List<BlockPos> removed, List<BlockState> removedStates) {
        int cleared = 0;
        for (int dx = -half; dx <= half && cleared < budget; dx++) {
            for (int dy = -half; dy <= half && cleared < budget; dy++) {
                for (int dz = -half; dz <= half && cleared < budget; dz++) {
                    BlockPos pos = center.m_7918_(dx, dy, dz);
                    if (!level.m_46749_(pos)) {
                        continue; // 未加载区块 getBlockState 返回伪空气，不能按它判定
                    }
                    BlockState state = level.m_8055_(pos);
                    if (state.m_60795_()) {
                        continue;
                    }
                    // 流体单独跳过：它没有硬度概念，且清掉水源会引发大面积流动计算
                    FluidState fluid = state.m_60819_();
                    if (!fluid.m_76178_() && state.m_60734_() instanceof LiquidBlock) {
                        continue;
                    }
                    if (!isBreakable(state, level, pos, maxHardness)) {
                        continue;
                    }
                    if (skipContainers) {
                        BlockEntity be = level.m_7702_(pos);
                        if (be != null) {
                            continue;
                        }
                    }
                    if (removed.contains(pos)) {
                        continue; // 前瞻段与自身段可能重叠
                    }
                    removed.add(pos.m_7949_());
                    removedStates.add(state);
                    if (level.m_7471_(pos, false)) {
                        cleared++;
                        // 【v1.5.395 实测八百零九：破坏要有挖掘同款粒子，不能凭空消失】
                        //
                        // m_46796_(2001, pos, Block.m_49956_(state)) = 原版「方块被破坏」的粒子+音效事件，
                        // 客户端按 Block id 取到方块本体、喷它自己的碎屑并播放破坏音——本模组的
                        // 挖矿/伐木/种植/搭桥全部用这一条（MaidMineBehavior / MaidWoodBehavior /
                        // MaidPlanting / BridgeUpBehavior），所以这里也照它走，手感与原版手挖一致。
                        // 实测八百一十起由配置项 breakParticles 控制（默认开，关掉则静静消失）。
                        if (breakParticles && fxLeft[0] > 0) {
                            fxLeft[0]--; // 【R1105】每 tick 粒子/音效配额，超出的只破坏不喷
                            level.m_46796_(BLOCK_BREAK_EVENT, pos, Block.m_49956_(state));
                        }
                    }
                }
            }
        }
        return cleared;
    }

    /** 是否满足"可清"的硬度门槛：{@code 0 <= hardness < maxHardness}。 */
    public static boolean isBreakable(BlockState state, net.minecraft.world.level.BlockGetter level,
                                      BlockPos pos, float maxHardness) {
        try {
            // m_7749_ = Block.getDestroySpeed(state, level, pos)——与 MaidMineBehavior /
            // MaidWoodBehavior 读硬度的写法完全一致（同一个 SRG 名，别改成 BlockState 上的那个）
            float hardness = state.m_60734_().m_7749_(state, level, pos);
            return hardness >= 0.0f && hardness < maxHardness;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("DaYunBreaker", ignored);
            return false;
        }
    }

    /**
     * <p>与 {@link #sweep} 用**同一个**硬度判据（{@link #isBreakable}），外加容器口径：
     * 配置里开着「跳过容器」时，带方块实体的方块她其实**撞不开**（飞行时会被它挡住），
     * 所以锁敌时也不该穿过去——否则她会隔着自家仓库锁人、冲过去却卡在箱子上。
     * <p>流体（水/岩浆）算"能穿"：没有硬度概念，而且她本来就能从中飞过去。
     * 未加载区块算"不能穿"（宁可保守，不要让她隔着没加载的地方锁人）。
     */
    public static boolean canPhaseThrough(net.minecraft.world.level.Level level, BlockPos pos,
                                          float maxHardness, boolean skipContainers) {
        try {
            if (level == null || pos == null || !level.m_46749_(pos)) {
                return false;
            }
            BlockState state = level.m_8055_(pos);
            if (state.m_60795_()) {
                return true; // 空气：随便穿
            }
            if (state.m_60734_() instanceof LiquidBlock) {
                return true; // 流体：没有硬度，飞得过去
            }
            if (!isBreakable(state, level, pos, maxHardness)) {
                return false; // 硬度 >= 上限（黑曜石及以上）或 < 0（基岩/屏障）：撞不开
            }
            if (skipContainers && level.m_7702_(pos) != null) {
                return false; // 容器：配置里说了不破坏 → 她也撞不开
            }
            return true;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("DaYunBreaker", ignored);
            return false;
        }
    }
}
