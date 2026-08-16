package com.maidsmart.build;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.task.MaidWorkTags;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.PositionTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * 建筑行为：按 BuildPlan 逐块放置。
 * 规则：
 * - 目标位置已是目标方块 → 跳过（已建好）
 * - 目标位置被其他方块占用 → 跳过（不破坏玩家建筑）
 * - 背包缺料 → 自动从主人背包取料（每 tick 尝试，无需重新下达）；都没有则气泡提示一次
 * - 每 3 tick 放置一块（约 0.15 秒/块，v1.5.31 提速 3.3 倍）
 * - 完成 → 气泡报告 + 清空计划
 */
public class MaidBuildBehavior extends Behavior<EntityMaid> {
    /** v1.5.31：放置间隔（3 tick = 0.15 秒/块——旧版 10 tick 0.5 秒/块，速度约 3.3 倍；
     *  大型建筑（远古巨树 19610 块）从约 6.5 小时缩短到约 2 小时）
     *  v1.5.43：改为可调（手册速度按钮 ×1/×1.5/×3 = 3/2/1 tick）
     *  v1.5.53：默认最快档 ×3（interval=1）——单女仆 10 分钟完成目标 */
    private static int PLACE_INTERVAL = 1;

    /** v1.5.61：极速模式——大块连续摆放的视觉冲击（"哗啦一大片"效果）。
     *  直接吃满服务器能力上限（上限 600 块/tick ≈ 1.2 万块/秒，TPS 保护兜底）。
     *  受众有限 → 手册速度按钮可切换：×1 → ×1.5 → ×3 → 极速 → ×1 */
    private static boolean TURBO = true;

    /**
     * v1.5.88：服务器启动时应用配置面板的默认建造档位（build.speedTier / build.turbo）。
     * 运行中档位由手册速度按钮循环切换，此处只设初始值。
     */
    public static void applyConfigDefaults() {
        try {
            TURBO = com.maidsmart.config.MaidSmartConfig.BUILD_TURBO.get();
            String tier = com.maidsmart.config.MaidSmartConfig.BUILD_SPEED_TIER.get();
            // v1.5.140：档位标记重定——×1=6、×1.5=4、×3=2（tick 粒度仅供错峰/标签，
            // 实际速率由 currentShare 的档位硬限决定：×1=1 块/秒、×1.5=1.5、×3=3）
            PLACE_INTERVAL = switch (tier) {
                case "x1" -> 6;
                case "x1.5" -> 4;
                default -> 2;
            };
        } catch (Exception ignored) {
        }
    }

    public static String speedLabel() {
        return TURBO ? "极速" : (PLACE_INTERVAL == 2 ? "×3" : (PLACE_INTERVAL == 4 ? "×1.5" : "×1"));
    }

    /** 速度循环切换 ×1 → ×1.5 → ×3 → 极速 → ×1，返回当前档位文本 */
    public static String cycleSpeed() {
        if (TURBO) {
            TURBO = false;
            PLACE_INTERVAL = 6;
            return speedLabel();
        }
        PLACE_INTERVAL = PLACE_INTERVAL == 6 ? 4 : (PLACE_INTERVAL == 4 ? 2 : 6);
        if (PLACE_INTERVAL == 2) {
            TURBO = true; // ×3 之后进入极速档
        }
        return speedLabel();
    }

    /** v1.5.102：停顿重试间隔 / 前瞻上限 / 延后轮询上限均从配置面板读取（build 段） */

    private int placeCooldown = 0;
    private String missingNotified = null;
    /** v1.5.54：本女仆放置配额余额（浮点累积，每 tick += 份额；取整放置，平滑无突变） */
    private double placeQuota = 0.0;

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    /** v1.5.48：停滞诊断日志节流（每维度 20 秒一条） */
    private static final java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, Long>
            LAST_STALL_LOG = new java.util.HashMap<>();
    /** v1.5.48：悬空跳过提示限频（同一女仆最多提示 3 次，完成时汇总报告） */
    private static final java.util.Map<java.util.UUID, Integer> SKIP_NOTIFIED = new java.util.HashMap<>();

    /** v1.5.43：最近缺料记录（手册女仆状态列表用：显示"缺料:xxx"） */
    private static final java.util.Map<java.util.UUID, String> LAST_MISSING = new java.util.HashMap<>();
    /** v1.5.275：缺料播报 30 秒冷却（女仆 → 上次播报 tick）——blockId 轮流缺时旧版
     *  每 5~11 秒刷屏（日志实证），冷却期内静默 */
    private static final java.util.Map<java.util.UUID, Long> MISSING_CD = new java.util.HashMap<>();
    /** v1.5.275：替代品播报 30 秒冷却（女仆 → 上次播报 tick）——"缺 X，我用 Y 替代" */
    private static final java.util.Map<java.util.UUID, Long> ALT_NOTIFIED = new java.util.HashMap<>();

    /** 审计：女仆卸载/移除时清理建造提示/缺料冷却表 */
    public static void forgetMaid(java.util.UUID maidUuid) {
        SKIP_NOTIFIED.remove(maidUuid);
        LAST_MISSING.remove(maidUuid);
        MISSING_CD.remove(maidUuid);
        ALT_NOTIFIED.remove(maidUuid);
    }

    /** v1.5.142：建造强制坐下标记（persistentData）——进入建造任务即坐下，
     *  玩家无法让她站起（每 tick 重新按压坐下姿势）；切出建造任务自动站起 */
    public static final String BUILD_SIT_TAG = "maid_smart_build_sitting";

    /**
     * v1.5.142：建造模式强制坐下（每 tick 由 MaidToolAutoEquipBehavior 调用，
     *  core 行为任何 activity 都跑——覆盖夜晚切休息班等行为停摆窗口）：
     * - 处于建造任务 → 强制 m_21837_(true)（setInSittingPose，TLM 原生坐姿）。
     *   坐下后 TLM 跟随行为（MaidFollowOwnerTask 检查 canBrainMoving = !坐姿）
     *   不再启动 → 建造中不会再被"跟随传送"小范围瞬移拉走；
     *   建造本身是隔空放置（行为从不移动），坐下不影响搭方块。
     * - 玩家试图让她站起（GUI/交互）→ 下一 tick 被重新按回坐下。
     * - 切出建造任务 → 恢复站立并清标记（只恢复我们自己按下的坐姿）。
     */
    public static void tickBuildSit(EntityMaid maid) {
        boolean building = BlueprintBuildExecutor.isBuildingTask(maid);
        boolean wasSitting = maid.getPersistentData().m_128471_(BUILD_SIT_TAG);
        // v1.5.333：心契誓约（MaidHugManager）交互中——交互会锁定女仆站立姿势并锁位
        // （lockMaid：m_20124_(STANDING) + m_21837_(false) + 锁定坐标），此处若继续强制
        // 坐下会与交互每 tick 互搏（坐下↔站起振荡）；跳过本轮，交互结束后自然恢复。
        if (building && inMaidmarriageInteraction(maid)) {
            return;
        }
        if (building) {
            if (!maid.m_21825_()) { // isInSittingPose
                maid.m_21837_(true); // setInSittingPose（TLM override，连坐姿+指令位一起设）
            }
            if (!wasSitting) {
                maid.getPersistentData().m_128379_(BUILD_SIT_TAG, true);
            }
        } else if (wasSitting) {
            if (maid.m_21825_()) {
                maid.m_21837_(false);
            }
            maid.getPersistentData().m_128379_(BUILD_SIT_TAG, false);
        }
    }

    /**
     * v1.5.333：心契誓约（maidmarriage）交互中？——反射 MaidHugManager.
     * getInteractionPlayer(maid)（MAID_TO_PLAYER 映射，交互中返回玩家、否则 null）。
     * 全反射软联动：未装心契誓约/方法变动 → 返回 false（维持原行为）。
     * v1.5.352：同时查儿童管理器 ChildInteractionManager——旧版只查成人，
     * 建造中的女儿（儿童路径 Alt+J）交互时豁免不生效 → tickBuildSit 每 tick
     * 强坐 → ChildInteractionManager.isValidInteractionPair 判坐姿失效 →
     * 会话立刻终止 → 面板"进入一下就瞬间退出"。
     */
    private static boolean inMaidmarriageInteraction(EntityMaid maid) {
        try {
            Class<?> c = Class.forName("com.example.maidmarriage.compat.MaidHugManager");
            java.lang.reflect.Method m = c.getDeclaredMethod("getInteractionPlayer",
                    com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid.class);
            if (m.invoke(null, maid) != null) {
                return true;
            }
            Class<?> cc = Class.forName("com.example.maidmarriage.compat.ChildInteractionManager");
            java.lang.reflect.Method cm = cc.getDeclaredMethod("getInteractionPlayer",
                    com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid.class);
            return cm.invoke(null, maid) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** v1.5.52：TPS 实时测量（每 20 tick 一次，配额反馈的依据） */
    public static volatile float CURRENT_TPS = 20.0f;
    private static long lastTickNanos = 0;
    private static int tpsTicks = 0;
    /** v1.5.60：放置音效限频（每 tick 最多 3 个，冲刺模式防音效爆棚） */
    private static long lastSoundGameTime = -1;
    private static int soundBudget = 0;
    /** v1.5.54：全局放置配额（块/tick）——平滑反馈控制，消除忽快忽慢：
     *  每 2 秒按 TPS + 实际放置量微调（超载 ×0.75/×0.5、有余量 ×1.15），
     *  总量逐步收敛到服务器真实极限后稳定不动——不再有"全速↔停摆"的硬切振荡。 */
    public static volatile float GLOBAL_QUOTA = 350f;
    /** v1.5.54：最近 2 秒窗口内实际放置数（所有女仆合计，由放置成功处累加） */
    public static int PLACED_IN_WINDOW = 0;
    private static int quotaWindowTicks = 0;

    public static void tickTpsMonitor() {
        long now = System.nanoTime();
        if (lastTickNanos == 0) {
            lastTickNanos = now;
            return;
        }
        if (++tpsTicks >= 20) {
            double perTickMs = (now - lastTickNanos) / 1e6 / tpsTicks;
            CURRENT_TPS = (float) Math.min(20.0, 1000.0 / Math.max(perTickMs, 1.0));
            tpsTicks = 0;
            lastTickNanos = now;
        }
        // v1.5.54：每 40 tick（2 秒）调整一次配额——缓慢逼近服务器极限，平滑无振荡
        if (++quotaWindowTicks >= 40) {
            quotaWindowTicks = 0;
            // v1.5.61：极速模式用更宽松的保护线（允许更低 TPS、更高上限）
            // v1.5.81：极速上限 600 → 1500（更接近服务器真实极限），试探加速 ×1.25 → ×1.5
            // v1.5.88：极速封顶从配置面板读取（build.globalQuota）
            float lowLine = TURBO ? 10.0f : 12.0f;
            float midLine = TURBO ? 13.0f : 14.5f;
            float highLine = TURBO ? 16.5f : 17.5f;
            float capMax = TURBO ? com.maidsmart.config.MaidSmartConfig.BUILD_GLOBAL_QUOTA.get() : 200.0f;
            if (CURRENT_TPS < lowLine) {
                GLOBAL_QUOTA *= 0.5f; // 急刹
            } else if (CURRENT_TPS < midLine) {
                GLOBAL_QUOTA *= 0.75f; // 减速
            } else if (CURRENT_TPS > highLine && PLACED_IN_WINDOW >= GLOBAL_QUOTA * 36) {
                GLOBAL_QUOTA *= 1.5f; // 有富余且放满了 → 试探加速（更快逼近服务器极限）
            }
            GLOBAL_QUOTA = Math.max(1.0f, Math.min(capMax, GLOBAL_QUOTA));
            PLACED_IN_WINDOW = 0;
        }
    }

    /** v1.5.53：活跃建造女仆数（按维度，MaidSmartExtension 每 40 tick 统计）——
     *  全局配额按女仆数均分。 */
    private static final java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, Integer>
            ACTIVE_MAIDS = new java.util.HashMap<>();

    public static void updateActiveBuilders(net.minecraft.server.MinecraftServer server) {
        ACTIVE_MAIDS.clear();
        for (net.minecraft.server.level.ServerLevel level : server.m_129785_()) {
            int c = 0;
            for (net.minecraft.world.entity.Entity e : level.m_8583_()) {
                if (e instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid m
                        && BlueprintBuildExecutor.isBuildingTask(m)) {
                    c++;
                }
            }
            if (c > 0) {
                ACTIVE_MAIDS.put(level.m_46472_(), c);
            }
        }
    }

    /** v1.5.59：本女仆每 tick 的放置份额 = 目标速率 / 活跃女仆数，且不超过服务器能力上限。
     *  目标速率算法（用户规则）：
     *  - 先按当前正常速度估算单女仆耗时；若 >10 分钟 → 锁定 10 分钟反推速度（更快）
     *  - 若 ≤10 分钟 → 按正常速度搭（小建筑不刻意加速）
     *  - 多女仆 → 单女仆目标 × 2^(n-1) 指数增长（封顶 512 防溢出），再均分到每个女仆
     *  - 手册档位 ×1/×1.5/×3 → 目标时间 30/20/10 分钟
     *  - GLOBAL_QUOTA（能力上限）是物理天花板：服务器扛不住时反馈自动压低 */
    private static float currentShare(net.minecraft.resources.ResourceKey<Level> dim, int size) {
        int n = Math.max(1, ACTIVE_MAIDS.getOrDefault(dim, 1));
        // v1.5.61：极速模式——直接吃满能力上限（大块连续摆放）
        if (TURBO) {
            return Math.max(1.0f, GLOBAL_QUOTA / n);
        }
        // v1.5.140：档位硬限速率（块/秒）——×1=1、×1.5=1.5、×3=3。
        // 修复旧逻辑：`Math.max(GLOBAL_QUOTA, ...)` 在 TPS 高时档位速率被能力上限
        // 顶掉 → ×1 也全速搭建（用户反馈"只开 x1 还是太快，小建筑没过程"）。
        // 多女仆指数增长：总目标 = 单女仆 × 2^(n-1)（封顶 512 倍）
        double tierRate = PLACE_INTERVAL == 6 ? 1.0 : (PLACE_INTERVAL == 4 ? 1.5 : 3.0);
        double perTick = tierRate / 20.0;
        double totalTarget = perTick * Math.min(Math.pow(2.0, n - 1), 512.0);
        // 每女仆份额 = min(目标/女仆数, 能力上限/女仆数)——GLOBAL_QUOTA 仅作物理天花板
        double share = Math.min(totalTarget / n, GLOBAL_QUOTA / n);
        return (float) Math.max(0.5, share);
    }

    /** v1.5.52：实际放置间隔——仅手册档位（×1/×1.5/×3），不再做 TPS 硬切（总量由配额控制） */
    private static int currentInterval() {
        return PLACE_INTERVAL;
    }

    public static String lastMissing(EntityMaid maid) {
        return LAST_MISSING.get(maid.m_20148_());
    }

    private static void clearMissing(EntityMaid maid) {
        LAST_MISSING.remove(maid.m_20148_());
    }

    public MaidBuildBehavior() {
        // v1.5.124：无限运行时长——旧版 super(emptyMap) 默认 60 tick 上限，
        // 行为每 3 秒自动停止再启动（日志 18:45 实测 "build behavior start" 每 3 秒
        // 一条 = 行为反复重启；建造是常驻行为，不应自动重启）
        super(java.util.Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /** v1.5.171：canUse 诊断日志限频（每 5 秒一条）——"下达后只回话不建造"定位用 */
    private static long LAST_CANUSE_LOG = 0;

    @Override
    protected boolean m_6114_(ServerLevel level, EntityMaid maid) {
        // v1.5.18：只要处于建筑任务就启动行为（站桩）——即使还没有建造计划，
        // 也保持站立不动等待主人下达蓝图；有计划则开始放置
        // v1.5.171：诊断日志（latest.log 搜 "build canUse"）——行为是否被评估、
        // 评估时任务/计划/暂停状态，直接定位"下达后不建造"（行为没启动 =
        // 任务没切过来 或 activity 不对；启动后不放置 = plan/paused 状态）
        long now = level.m_46467_();
        if (now - LAST_CANUSE_LOG > 100) {
            LAST_CANUSE_LOG = now;
            boolean btask = BlueprintBuildExecutor.isBuildingTask(maid);
            LOGGER.info("build canUse: maid={} buildTask={} task={} plan={} paused={} maidPaused={}",
                    maid.m_5446_() != null ? maid.m_5446_().getString() : maid.m_20148_(),
                    btask,
                    maid.getTask() == null ? "null"
                            : maid.getTask().getUid().m_135815_() + "@" + maid.getTask().getUid().m_135827_(),
                    BuildPlan.getBoundPlan(maid).size(),
                    BuildPlan.isBoundPlanPaused(maid), BuildPlan.isMaidPaused(maid));
        }
        // v1.5.177：暂停 = 解除绑定——canUse 直接 false（行为停止评估），女仆不再
        // 每 tick 站桩/清移动目标（旧版暂停只停放置，站桩代码在 paused 检查之前
        // 照跑 → 女仆被钉死在原地，无法停下来去干别的事）；恢复建造后行为重新
        // 启动（start），tick 自动重新站桩。
        // v1.5.180：暂停按女仆绑定区块判定（多区块共存）
        return BlueprintBuildExecutor.isBuildingTask(maid)
                && !BuildPlan.isBoundPlanPaused(maid)
                && !BuildPlan.isMaidPaused(maid);
    }

    @Override
    protected void m_6735_(ServerLevel level, EntityMaid maid, long gameTime) {
        this.placeCooldown = 0;
        this.missingNotified = null;
        // v1.5.182：开始建造前——若该区块还没有工头（玩家未在名单页预设），
        // 从绑定该区块的女仆中随机挑一只当工头（一区块一工头）
        try {
            BuildPlan.PlanState ps0 = BuildPlan.getBoundPlanState(maid);
            if (ps0 != null && (ps0.foremanUuid == null || ps0.foremanUuid.isEmpty())) {
                String fm = BuildPlan.chooseForeman(level, ps0);
                if (!fm.isEmpty()) {
                    BuildPlan.setForeman(level, ps0, fm);
                }
            }
        } catch (Exception ignored) {
        }
        // v1.5.121：恢复"进入建造 → 强制 home 模式"（v1.5.117 曾撤销，导致建造不执行：
        // 非 home 模式下 TLM MaidFollowOwnerTask 持续启动，把女仆从工作状态拉向跟随，
        // WORK activity 的建造行为不再运行 → 下达后只回话不建造）。
        // home 模式本身【不产生传送】——它恰恰是阻止 TLM 跟随/瞬移回主人的机制；
        // "随机传送"的根源（setPlan 清场 / suffocateCheck 救援）在 v1.5.117 已全部
        // 禁用，恢复 home 后 promaid 侧 0 传送 + TLM 不瞬移 = 彻底无随机传送，且建造正常。
        maid.setHomeModeEnable(true);
        // v1.5.41：多女仆错峰——初始冷却按女仆 id 错开，避免 25 只女仆同一 tick 全部放置
        // （每 tick 25 次 setBlock+光照+客户端包造成 tick 尖峰；错峰后均匀分布，总量不变）
        this.placeCooldown = maid.m_19879_() % PLACE_INTERVAL;
        // v1.5.13：进入建造模式立刻停止移动
        maid.m_6274_().m_21936_(MemoryModuleType.f_26370_);
        // v1.5.122：诊断日志——建造行为是否真的启动（"下达后不建造"定位用）
        LOGGER.info("build behavior start: maid={} planBlocks={}",
                maid.m_5446_() != null ? maid.m_5446_().getString() : maid.m_20148_(),
                BuildPlan.getBoundPlan(maid).size());
    }

    @Override
    protected void m_6725_(ServerLevel level, EntityMaid maid, long gameTime) {
        // v1.5.24：进入完全站桩——标记由 MaidMoveSuppressMixin 从源头拦掉 MoveToTargetSink
        //（1.20.1 行为并发，仅每 tick 清 WALK_TARGET + 停导航会被 MoveToTargetSink 竞态盖掉）
        MaidWorkTags.setStill(maid, true);
        // v1.5.180：计划来源 = 女仆绑定的区块（多区块共存；无绑定 → 站桩等待绑定）
        BuildPlan.PlanState ps = BuildPlan.getBoundPlanState(maid);
        List<String> plan = ps == null ? new java.util.ArrayList<>() : ps.toPlan();
        // v1.5.18：站桩等待——每 tick 清移动目标 + 停止导航，即使没有计划也站立不动
        maid.m_6274_().m_21936_(MemoryModuleType.f_26370_);
        maid.m_21573_().m_26569_();
        if (plan.isEmpty()) {
            return; // 等待下达蓝图
        }
        BlockPos origin = BuildPlan.getOrigin(plan);
        if (origin == null) {
            BuildPlan.clear(level, ps.planId);
            return;
        }
        // v1.5.41：建造区强制加载——整个蓝图区域挂 FORCED 票据，超出视距的远端
        // 也同时建造（幂等，计划清除时由 BuildPlan.clear 释放）
        BuildPlan.ensureChunks(level, ps);
        // v1.5.43：暂停检查（区块暂停 或 逐只暂停）→ 站桩不放置，恢复后从游标继续
        if (ps.paused || BuildPlan.isMaidPaused(maid)) {
            return;
        }
        if (this.placeCooldown-- > 0) {
            return;
        }
        net.minecraft.resources.ResourceKey<Level> dim = level.m_46472_();
        int size = plan.size();
        // v1.5.54/59：每 tick 累积放置配额（目标速率按建筑大小：10 分钟锁定 × 多女仆
        // 指数增长，能力上限封顶），取整为本轮批量——总量恒定、平滑无突变
        this.placeQuota += currentShare(dim, size);
        boolean placedAny = false;
        // v1.5.42：进度按区块隔离（v1.5.180：按 planId 键控，多区块互不串扰）
        BuildPlan.Progress prog = BuildPlan.progress(ps);
        if (prog.cursor < 1 || prog.cursor > size) {
            prog.cursor = Math.min(Math.max(1, prog.cursor), size);
        }
        int cursor = prog.cursor;
        // v1.5.40：从全局游标开始扫描（已建步骤快速前进），每轮最多 LOOKAHEAD 步——
        // 彻底消除旧版"每轮从第 1 步全表扫描"的 O(N) 开销（55 万步 × 25 女仆曾把
        // 服务器拖到 299 ticks 落后 ≈ 0.07 TPS，表现为"女仆在动作但看不到进展"）。
        // 缺料/障碍/区块未加载的步骤记入延后集；游标停在第一个未建步骤前；
        // 窗口内无活可干时整窗滑过（延后步骤由下方轮询补建）
        int lookaheadLeft = com.maidsmart.config.MaidSmartConfig.BUILD_LOOKAHEAD.get();
        // v1.5.54：本轮可放置数 = 配额余额取整（上限 64 防单 tick 尖峰）
        // v1.5.81：极速批量上限 512 → 768（暂停恢复后更快消化囤积配额）
        int batchLeft = (int) Math.min(this.placeQuota, TURBO ? 768 : 64);
        // v1.5.322：本轮实际放置过的区块 → 记入静态待重发集（重发刷新客户端半透明
        // 渲染层；v1.5.328：跨轮累积 + 修溢出，见 resendPlacedChunks）
        int i = cursor;
        for (; i < size && lookaheadLeft > 0; i++) {
            // v1.5.77：已在延后集的步骤由轮询补建——主循环直接滑过，防止"窗口内
            // 全是延后步骤时游标停滞、支撑步骤（在更后面）永远建不到"的死锁
            if (prog.deferred.containsKey(i)) {
                lookaheadLeft--;
                continue;
            }
            String[] parts = BlueprintLib.parseStep(plan.get(i));
            if (parts == null) {
                if (i == prog.cursor) {
                    prog.cursor = i + 1;
                }
                continue;
            }
            int x;
            int y;
            int z;
            try {
                x = Integer.parseInt(parts[0]);
                y = Integer.parseInt(parts[1]);
                z = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                if (i == prog.cursor) {
                    prog.cursor = i + 1;
                }
                continue;
            }
            String blockId = parts[3];
            String stateSnbt = parts[4];
            String beSnbt = parts[5];
            // v1.5.114：计划级缓存 blockId→Block——同种方块大量重复，免每步查注册表
            Block block = prog.blockCache.computeIfAbsent(blockId,
                    id -> ForgeRegistries.BLOCKS.getValue(net.minecraft.resources.ResourceLocation.parse(id)));
            if (block == null) {
                if (i == prog.cursor) {
                    prog.cursor = i + 1;
                }
                continue;
            }
            BlockPos target = origin.m_7918_(x, y, z);
            // v1.5.24：女仆自身所在格【不再跳过】——蓝图以她脚下为原点，
            // 脚下正是要建的位置时先放脚下把自己垫上去（不会"缺自己站的那块"）。
            // 若该格已建好（同方块/等价族）下面状态检查会跳过；被非地形占用也会跳过。
            BlockState state = level.m_8055_(target);
            // v1.5.276：+替代品验收——缺料替换放置后认可，不再拆掉重放（防掉落循环）
            if (state.m_60734_() == block || BlueprintLib.isBuiltEquivalent(blockId, state.m_60734_())
                    || isAltPlaced(prog, i, state)) {
                // 已建好（同方块或等价族内替代品）——仅当它就是游标所在步骤时前进
                if (i == prog.cursor) {
                    prog.cursor = i + 1;
                }
                continue;
            }
            // v1.5.79：空气步骤（蓝图挖空/清除要求）——空气不可能在生存收集，
            // 默认无限（生存/创造都不消耗材料）：目标有方块（黑名单外可拆）→ 清除
            if (block == net.minecraft.world.level.block.Blocks.f_50016_) {
                if (!state.m_60795_() && BlueprintLib.canBreak(state.m_60734_())) {
                    level.m_7731_(target, net.minecraft.world.level.block.Blocks.f_50016_.m_49966_(), 3);
                }
        // v1.5.322：清除/放置都改了渲染 → 记入待重发区块（v1.5.328：静态累积集）
        PENDING_RESEND_CHUNKS.add(chunkKeyOf(origin, x, z));
                if (i == prog.cursor) {
                    prog.cursor = i + 1;
                }
                countPlaced(prog, x, y, z);
                placedAny = true;
                lookaheadLeft--;
                continue;
            }
            // v1.5.40：区块未加载 → 延后（不消耗材料、不播放动作；旧版 setBlock 对
            // 未加载区块静默失败 → 出现"女仆在挥臂但方块不出现"+ 白白烧掉材料）
            if (!level.m_46749_(target)) {
                prog.deferred.putIfAbsent(i, 0);
                lookaheadLeft--;
                continue;
            }
            if (!state.m_60795_()) {
                // v1.5.58：障碍 → 直接拆掉重建（位置不对就破坏，不再左右脑互搏）。
                // v1.5.81：地形/障碍统一【破坏成掉落物】（可回收）——普通与强制
                // 建造一致；黑名单（基岩/命令方块）不可拆：允许地面直接覆盖，其余延后
                if (BlueprintLib.canBreak(state.m_60734_())) {
                    BlueprintLib.forceBreak(level, target, state);
                    // 破坏后继续放置流程（不延后）
                } else if (!BlueprintLib.isAllowedGround(state)) {
                    // v1.5.84：黑名单永久障碍（基岩/命令方块等）→ 立即跳过（不再
                    // 重试 3 次——如"地下避难所"画在基岩层的地板，延后只会反复尝试）
                    prog.skipped++;
                    prog.skippedIdx.add(i);
                    int n = SKIP_NOTIFIED.merge(maid.m_20148_(), 1, Integer::sum);
                    if (BuildPlan.isForeman(maid) && n <= 3) { // v1.5.265：汇报只由工头发
                        maid.getChatBubbleManager().addTextChatBubble(
                                "有个" + BlueprintLib.cnName(blockId) + "被基岩之类的方块挡住了，我跳过它啦～");
                    }
                    lookaheadLeft--;
                    continue;
                }
            }
            // 背包取料（支持等价族：要求橡木木板时任何木板都行），放置实际消耗的方块
            net.minecraft.world.item.Item used = BlueprintLib.consumeBlock(maid, blockId);
            if (used == null) {
                // v1.5.24 材料以主人背包为准：缺料时自动取一组（补料后自动续建）
                if (tryTakeFromOwner(maid, blockId)) {
                    used = BlueprintLib.consumeBlock(maid, blockId);
                }
            }
            if (used == null) {
                // 缺料：延后，补料后轮询自动续建（每轮只提示一次）
                // v1.5.317：水/岩浆缺的是桶（材料链按桶结算），提示报桶名
                prog.deferred.putIfAbsent(i, 0);
                this.notifyMissing(maid, fluidBucketId(blockId));
                lookaheadLeft--;
                continue;
            }
            net.minecraft.resources.ResourceLocation usedId = ForgeRegistries.ITEMS.getKey(used);
            Block placed = usedId != null ? ForgeRegistries.BLOCKS.getValue(usedId) : null;
            if (placed == null) {
                placed = block;
            }
            // v1.5.316：机器活建造——机器模式 flag 3 活放置 + 状态归一化（丢弃蓝图
            // 冻结瞬态：伸出活塞/通电铁轨/冻结 power），方块以自然初始态落地、
            // 由活放置收敛到设计态；普通建筑保持 flag 2 静默 + 原状态。
            boolean machineLive = BlueprintLib.isMachineBlueprint(ps.blueprintId)
                    && com.maidsmart.config.MaidSmartConfig.BUILD_MACHINE_SMART.get();
            String machineState = machineLive
                    ? BlueprintLib.normalizeMachineState(blockId, stateSnbt) : stateSnbt;
            if (!doPlace(level, maid, origin, target, plan, placed, machineState, beSnbt,
                    prog.plannedPositions(plan), false, planMainBlock(ps, plan), machineLive)) {
                // v1.5.45：支撑缺失（火把/按钮/拉杆等，支撑块未建）→ 延后，支撑建好后自动补建
                prog.deferred.putIfAbsent(i, 0);
                lookaheadLeft--;
                continue;
            }
            // v1.5.276：缺料替换放置成功 → 记录替代品（后续检查认可，不拆不重放）
            recordAltUsed(prog, i, blockId, used, usedId);
            // v1.5.317：岩浆放置后返还空桶（岩浆桶用后变空桶）
            if ("minecraft:lava".equals(blockId)) {
                BlueprintLib.returnEmptyBucket(level, maid, target);
            }
            if (i == prog.cursor) {
                prog.cursor = i + 1;
            }
            // v1.5.252aa：极速模式放置冷却归零（连续摆放）——旧版放置后仍设 2 tick
            // 冷却 → 每只女仆每 3 tick 只放 1 块（6.7 块/秒/只），13 只合计被压到
            // ~25 块/秒（用户实测"13 个女仆速度只有五格"）——TURBO 本意是吃满
            // 服务器能力上限（批量由 batchLeft 配额控制，TPS 反馈兜底）
            this.placeCooldown = TURBO ? 0 : currentInterval();
            this.missingNotified = null;
            clearMissing(maid);
            BuildPlan.persistCursor(level, ps, prog.cursor);
            placedAny = true;
            // v1.5.322：本轮放置过的区块记入待重发（刷新客户端半透明渲染层）
            // v1.5.329：与延后补建方法(v1.5.328 已改)统一用静态累积集
            // PENDING_RESEND_CHUNKS——旧局部变量 placedChunks 从未声明,
            // 132 sources 全量编译时暴露("找不到符号")
            PENDING_RESEND_CHUNKS.add(chunkKeyOf(origin, x, z));
            // v1.5.54：配额记账（窗口统计 + 余额递减）
            PLACED_IN_WINDOW++;
            this.placeQuota -= 1.0;
            countPlaced(prog, x, y, z); // v1.5.82：真实放置数（placedSet 防重复计数）
            // 批量——连续放置（每块都推进游标），本轮配额用完才收手
            if (--batchLeft <= 0) {
                break;
            }
            lookaheadLeft--;
        }
        // v1.5.40：本轮无放置 → 游标滑过已扫描窗口（其中延后的步骤由下方轮询补建）
        if (!placedAny) {
            prog.cursor = i;
        }
        // v1.5.40：延后步骤轮询（每轮最多 DEFERRED_SCAN_CAP 项、从最早开始）——
        // 补料/障碍移除/区块加载后最快一两个轮次内自动续建，不打断主循环进度
        // v1.5.56：失败/未就绪的条目移到队尾（本轮不占位）——下一轮换别的条目先试，
        // 根治"女仆死磕一个方块"的牛角尖（旧版失败项永远排最前，反复尝试+阻塞后面）
        if (!prog.deferred.isEmpty()) {
            java.util.List<Integer> reorder = new java.util.ArrayList<>();
            int checked = 0;
            for (java.util.Iterator<java.util.Map.Entry<Integer, Integer>> it = prog.deferred.entrySet().iterator();
                 it.hasNext() && checked < com.maidsmart.config.MaidSmartConfig.BUILD_DEFERRED_SCAN_CAP.get(); checked++) {
                int idx = it.next().getKey();
                // v1.5.42：越界防护——计划可能已变化（重启/换蓝图），丢弃失效下标
                if (idx < 1 || idx >= size) {
                    it.remove();
                    continue;
                }
                // v1.5.287：缺料退避——失败条目在退避期内跳过（沉底轮换）：
                // 旧版缺料时每 tick 反复扫空背包（DEFERRED_SCAN_CAP 个条目 ×
                // consumeBlock 三轮全背包扫描）；补料/障碍移除后最多 2 秒续建
                Long retryAt = prog.deferredRetryAt.get(idx);
                if (retryAt != null && retryAt > level.m_46467_()) {
                    reorder.add(idx);
                    continue;
                }
                // v1.5.48：游标走完后（cursor==size）主循环已结束——deferred 全部
                // 由本轮询处理（旧版 continue 导致这些条目无人处理 → 永不完成）
                if (idx > prog.cursor && prog.cursor < size) {
                    // v1.5.114：只跳过"游标之后"的条目；idx==cursor 的延后条目由轮询
                    // 补建——主循环对延后条目只滑过（containsKey）、游标又只在放置成功
                    // 时推进，若游标钉在延后条目上（窗口内有放置时 placedAny=true 不回拨），
                    // 旧版 idx>=cursor 会让它永远无人补建 = 缺料补料后不续建的死锁
                    continue;
                }
                String[] parts = BlueprintLib.parseStep(plan.get(idx));
                if (parts == null) {
                    it.remove();
                    continue;
                }
                int x;
                int y;
                int z;
                try {
                    x = Integer.parseInt(parts[0]);
                    y = Integer.parseInt(parts[1]);
                    z = Integer.parseInt(parts[2]);
                } catch (NumberFormatException e) {
                    it.remove();
                    continue;
                }
                String blockId = parts[3];
                String stateSnbt = parts[4];
                String beSnbt = parts[5];
                // v1.5.114：计划级缓存（与主循环同一 map，见上）
                Block block = prog.blockCache.computeIfAbsent(blockId,
                        id -> ForgeRegistries.BLOCKS.getValue(net.minecraft.resources.ResourceLocation.parse(id)));
                if (block == null) {
                    it.remove();
                    continue;
                }
                BlockPos target = origin.m_7918_(x, y, z);
                BlockState state = level.m_8055_(target);
                // v1.5.276：+替代品验收（同主循环——替代品放置后不再拆掉重放）
                if (state.m_60734_() == block || BlueprintLib.isBuiltEquivalent(blockId, state.m_60734_())
                        || isAltPlaced(prog, idx, state)) {
                    it.remove(); // 已被其他女仆/主循环补建
                    continue;
                }
                // v1.5.79：空气步骤（蓝图挖空/清除要求）——空气无限（生存/创造都不
                // 消耗材料）：目标有方块（黑名单外可拆）→ 清除
                if (block == net.minecraft.world.level.block.Blocks.f_50016_) {
                    if (!state.m_60795_() && BlueprintLib.canBreak(state.m_60734_())) {
                        level.m_7731_(target, net.minecraft.world.level.block.Blocks.f_50016_.m_49966_(), 3);
                    }
                    it.remove();
                    countPlaced(prog, x, y, z); // v1.5.82：真实放置数（placedSet 防重复计数）
                    placedAny = true;
                    break;
                }
                if (!level.m_46749_(target)) {
                    // v1.5.64：区块未加载沉底 + 计数——≥10 次（强制加载下通常 <1 秒
                    // 就绪）视为永久未加载 → 跳过防卡（88% 卡死防护）
                    int fails = prog.deferred.merge(idx, 1, Integer::sum);
                    if (fails >= 10) {
                        it.remove();
                        prog.skipped++;
                        prog.skippedIdx.add(idx); // v1.5.66：缺口检查不再重复尝试
                        int n = SKIP_NOTIFIED.merge(maid.m_20148_(), 1, Integer::sum);
                        if (BuildPlan.isForeman(maid) && n <= 3) { // v1.5.265：汇报只由工头发
                            maid.getChatBubbleManager().addTextChatBubble(
                                    "有个" + BlueprintLib.cnName(blockId) + "的区块一直没加载，我跳过它啦～");
                        }
                    } else {
                        reorder.add(idx);
                    }
                    continue;
                }
                if (!state.m_60795_()) {
                    // v1.5.58：障碍 → 直接拆掉重建（不再反复尝试/跳过）。
                    // v1.5.81：地形/障碍统一【破坏成掉落物】（可回收）。
                    // 黑名单不可拆：允许地面直接覆盖，其余计数跳过兜底
                    if (BlueprintLib.canBreak(state.m_60734_())) {
                        BlueprintLib.forceBreak(level, target, state);
                        // 拆掉后继续放置流程
                    } else if (!BlueprintLib.isAllowedGround(state)) {
                        // v1.5.84：黑名单永久障碍（基岩等）→ 立即跳过（不再重试 3 次）
                        it.remove();
                        prog.skipped++;
                        prog.skippedIdx.add(idx); // v1.5.66：缺口检查不再重复尝试
                        int n = SKIP_NOTIFIED.merge(maid.m_20148_(), 1, Integer::sum);
                        if (BuildPlan.isForeman(maid) && n <= 3) { // v1.5.265：汇报只由工头发
                            maid.getChatBubbleManager().addTextChatBubble(
                                    "有个" + BlueprintLib.cnName(blockId) + "被基岩之类的方块挡住了，我跳过它啦～");
                        }
                        continue;
                    }
                }
                net.minecraft.world.item.Item used = BlueprintLib.consumeBlock(maid, blockId);
                if (used == null && tryTakeFromOwner(maid, blockId)) {
                    used = BlueprintLib.consumeBlock(maid, blockId);
                }
                if (used != null) {
                    // v1.5.275：用了替代品 → 提示"用 X 替代 Y"（30 秒冷却）——
                    // 有替换时不再报缺料（用户："既然有替换品了，应该换换系统提示，
                    // 没有的时候才播报缺材料"）
                    // v1.5.287：itemForBlock（redstone_wire → 红石粉）
                    net.minecraft.world.item.Item exactItem = BlueprintLib.itemForBlock(blockId);
                    if (exactItem != null && used != exactItem) {
                        long nowTick2 = level.m_46467_();
                        Long lastAlt = ALT_NOTIFIED.get(maid.m_20148_());
                        if (lastAlt == null || nowTick2 - lastAlt >= 600L) {
                            ALT_NOTIFIED.put(maid.m_20148_(), nowTick2);
                            if (BuildPlan.isForeman(maid)) {
                                maid.getChatBubbleManager().addTextChatBubble(
                                        "缺 " + BlueprintLib.cnName(blockId) + "，我用 "
                                                + BlueprintLib.cnName(ForgeRegistries.ITEMS.getKey(used).toString())
                                                + " 替代一下～");
                            }
                        }
                        this.missingNotified = null; // 用了替代 → 清缺料标记（下次真缺料才再报）
                        clearMissing(maid);
                    }
                }
                if (used == null) {
                    // v1.5.64：区分"无对应物品的方块"（数据缺陷，永远拿不到料 → 跳过）
                    // 与"真缺料"（沉底轮换等补料，不跳过）
                    // v1.5.287：itemForBlock（redstone_wire → 红石粉）
                    net.minecraft.world.item.Item exactItem = BlueprintLib.itemForBlock(blockId);
                    if (exactItem == null) {
                        it.remove();
                        prog.skipped++;
                        prog.skippedIdx.add(idx); // v1.5.66：缺口检查不再重复尝试
                        int n = SKIP_NOTIFIED.merge(maid.m_20148_(), 1, Integer::sum);
                        if (BuildPlan.isForeman(maid) && n <= 3) { // v1.5.265：汇报只由工头发
                            maid.getChatBubbleManager().addTextChatBubble(
                                    "有个" + BlueprintLib.cnName(blockId) + "没有对应物品，我跳过它啦～");
                        }
                    } else {
                        reorder.add(idx); // 真缺料：沉底轮换，补料后自然排到前面补建
                        // v1.5.287：缺料退避 40 tick（不再每 tick 反复扫空背包）
                        prog.deferredRetryAt.put(idx, level.m_46467_() + 40);
                        // v1.5.317：水/岩浆缺的是桶（材料链按桶结算），提示报桶名
                        this.notifyMissing(maid, fluidBucketId(blockId));
                    }
                    continue;
                }
                net.minecraft.resources.ResourceLocation usedId = ForgeRegistries.ITEMS.getKey(used);
                Block placed = usedId != null ? ForgeRegistries.BLOCKS.getValue(usedId) : null;
                if (placed == null) {
                    placed = block;
                }
                // v1.5.252i：先计失败次数，第 3 次起用 force 模式——蓝图支撑步骤
                // 是空气/水/永未建成时，强制补支撑再放（不再等永远等不到的支撑）
                int fails = prog.deferred.merge(idx, 1, Integer::sum);
                // v1.5.287：放置失败退避 40 tick（防连续失败时每 tick 死磕）
                prog.deferredRetryAt.put(idx, level.m_46467_() + 40);
                // v1.5.316：机器活建造（同主循环——flag 3 活放置 + 状态归一化）
                boolean machineLive = BlueprintLib.isMachineBlueprint(ps.blueprintId)
                        && com.maidsmart.config.MaidSmartConfig.BUILD_MACHINE_SMART.get();
                String machineState = machineLive
                        ? BlueprintLib.normalizeMachineState(blockId, stateSnbt) : stateSnbt;
                if (!doPlace(level, maid, origin, target, plan, placed, machineState, beSnbt,
                        prog.plannedPositions(plan), fails >= 3, planMainBlock(ps, plan), machineLive)) {
                    // v1.5.46：支撑缺失——连续失败 ≥3 次视为"蓝图本身悬空"（作者画图
                    // 失误，永无支撑），永久跳过不阻塞完成；顺序问题（支撑后建）期间
                    // 1 分钟内会成功，计数随成功清零
                    if (fails >= 3) {
                        // v1.5.252af：支撑格在蓝图计划内 → 不跳过，延后等蓝图支撑建好。
                        // 甘蔗种泥巴上（泥巴缺料延后）→ force 补支撑 setBlock 失败 → 旧版
                        // 跳过 124 个（用户实测"甘蔗农场跳过 172 个"）；支撑是蓝图真实
                        // 步骤时应等待（补料后泥巴建好，甘蔗自然能放），而非永久跳过。
                        boolean waitPlanSupport = false;
                        net.minecraft.core.BlockPos supPos = null; // v1.5.264：提声明到 try 外（超时判定用）
                        try {
                            net.minecraft.world.level.block.state.BlockState ps2 = placed.m_49966_();
                            // v1.5.254：parseStepState 补 Name（同 doPlace——无 Name 被 NbtUtils 当空气）
                            net.minecraft.world.level.block.state.BlockState parsed2 =
                                    BlueprintLib.parseStepState(level, placed, machineState);
                            if (parsed2 != null) {
                                ps2 = parsed2;
                            }
                            net.minecraft.core.Direction sup = BlueprintLib.supportDirection(ps2);
                            if (sup != null) {
                                supPos = target.m_121945_(sup);
                                net.minecraft.world.level.block.state.BlockState supState =
                                        level.m_8055_(supPos);
                                // 仅"支撑格在蓝图内且尚未建（空/流体）"才等蓝图支撑——
                                // 已建但类型不合法（红石线下方玻璃）→ 走 force 补支撑换合法支撑
                                waitPlanSupport = (supState.m_60795_()
                                        || supState.m_60819_().m_205070_(net.minecraft.tags.FluidTags.f_13131_))
                                        && prog.plannedPositions(plan)
                                                .contains(posKey(supPos, origin));
                            }
                        } catch (Exception ignored) {
                        }
                        if (waitPlanSupport) {
                            // v1.5.264：支撑格区块未加载 → 延后等区块加载（252af
                            // 甘蔗泥巴场景：加载后泥巴步骤建成，甘蔗自然能放）；
                            // 区块已加载但重试 10 次仍未建（悬空依赖链：支撑步骤
                            // 本身也放不上/需要特殊环境）→ 旧版无限延后 → 建造永不
                            // 收敛（olymp-final 实测：延后 10324 永不减少、完成永远
                            // 不触发）。超时走跳过兜底，缺口由重新下达蓝图补建。
                            if (supPos != null && !level.m_46749_(supPos)) {
                                reorder.add(idx); // 支撑格区块未加载 → 延后等区块
                                continue;
                            }
                            if (fails < 10) {
                                reorder.add(idx); // 支撑在蓝图内 → 延后等蓝图支撑（不跳过）
                                continue;
                            }
                            LOGGER.info("build skip: {}@({},{},{}) 原因=蓝图支撑格已加载但 {} 次未建成（悬空依赖链）",
                                    blockId, x, y, z, fails);
                        }
                        it.remove();
                        prog.skipped++;
                        prog.skippedIdx.add(idx); // v1.5.66：缺口检查不再重复尝试
                        // v1.5.252w：跳过实锤日志（方块 + 坐标 + 失败次数）
                        LOGGER.info("build skip: {}@({},{},{}) 原因=悬空放不上（doPlace 失败 {} 次）",
                                blockId, x, y, z, fails);
                        // v1.5.48：跳过提示限频（同一女仆最多 3 次气泡，避免刷屏）
                        int n = SKIP_NOTIFIED.merge(maid.m_20148_(), 1, Integer::sum);
                        if (BuildPlan.isForeman(maid) && n <= 3) { // v1.5.265：汇报只由工头发
                            maid.getChatBubbleManager().addTextChatBubble(
                                    "有个" + BlueprintLib.cnName(blockId) + "悬空放不上，我跳过它啦～");
                        }
                    } else {
                        reorder.add(idx); // v1.5.56：失败 <3 → 沉底轮换（不牛角尖）
                    }
                    continue;
                }
                it.remove();
                // v1.5.287：成功 → 清退避记录
                prog.deferredRetryAt.remove(idx);
                // v1.5.276：缺料替换放置成功 → 记录替代品（后续检查认可，不拆不重放）
                recordAltUsed(prog, idx, blockId, used, usedId);
                // v1.5.317：岩浆放置后返还空桶（岩浆桶用后变空桶）
                if ("minecraft:lava".equals(blockId)) {
                    BlueprintLib.returnEmptyBucket(level, maid, target);
                }
                this.placeCooldown = currentInterval();
                this.missingNotified = null;
                clearMissing(maid);
                BuildPlan.persistCursor(level, ps, prog.cursor);
                placedAny = true;
                // v1.5.322：本轮放置过的区块记入待重发（刷新客户端半透明渲染层）
                // v1.5.328：局部集改静态累积集（PENDING_RESEND_CHUNKS）——节流 2 秒
                // 窗口内"非触发轮"的放置跨轮累积，重发时一次清空，不漏区块
                PENDING_RESEND_CHUNKS.add(chunkKeyOf(origin, x, z));
                // v1.5.54：配额记账（延后补建同样消耗预算，总量守恒）
                PLACED_IN_WINDOW++;
                this.placeQuota -= 1.0;
                countPlaced(prog, x, y, z); // v1.5.82：真实放置数（placedSet 防重复计数）
                break;
            }
            // v1.5.56：失败/未就绪条目沉底——移回队尾，下一轮换别的先试（不牛角尖）
            for (Integer k : reorder) {
                Integer v = prog.deferred.remove(k);
                if (v != null) {
                    prog.deferred.put(k, v);
                }
            }
        }
        if (placedAny) {
            // v1.5.322/328：节流重发待重发区块 → 强制客户端整块重建（半透明渲染层
            // 刷新——快速批量放置时玻璃/粘液块从区块外看不见的修复；v1.5.328 修
            // 节流溢出使重发真正执行 + 跨轮累积不丢区块）
            resendPlacedChunks(level);
            return;
        }
        // 未放置：游标到尽头且无延后 → 全部完成；否则停顿后重试（避免每 tick 空转扫描）
        if (prog.cursor >= size && prog.deferred.isEmpty()) {
            // v1.5.66：缺口补建（苦力怕炸洞/方块消失）——完成时扫描所有步骤的真实
            // 存在性，位置空了/变地形则重新补建（材料按正常流程计算：创造无限/
            // 生存从女仆+主人背包取，不够则提示等补料）。补完后再回来检查，无缺口才完成
            // v1.5.84：完成执行/汇报【统一由工头】——工头最大的价值就是上报。
            // 非工头女仆发现完成（cursor 到尽头且延后清空）时【等待工头执行】——
            // 不再自己 clear（旧版非工头先完成会清掉 GLOBAL_PLAN，把工头挡在
            // 完成分支外 → 无人汇报）。isForeman 语义：工头本人 → true 执行；
            // 无工头/工头失效/工头暂停 → true（任何女仆接管）；非工头（工头在场）→ false 等待。
            // v1.5.79：附悬空重力方块提示（图纸悬浮设计——解冻后会落下）
            if (!BuildPlan.isForeman(maid)) {
                return;
            }
            // v1.5.114：只有"有跳过/未放满"才全表扫描缺口——正常完成（全部位置
            // 成功放置）直接进入收尾，省去 55 万步蓝图完成瞬间的 O(N) parseStep +
            // 世界状态检查 tick 尖峰（放置成功的方块已完成 canSurvive+掉落双重验证
            // 不会自行消失；外力破坏的洞重新下达蓝图即可补建）
            // v1.5.252ab：红石激活提前到缺口检查【之前】——旧版在 scanGaps 之后，
            // scanGaps 发现缺口（哪怕 1 个）就 return → recalcRedstone 永不执行 →
            // 红石机器建好不运行（用户实测：甘蔗农场红石机器无法运行）
            // v1.5.316：红石机器改革——机器走活建造（flag 3）+ 专属顺序，红石/水流
            // 在放置时已自然就位；完工不再"唤醒/不唤醒"（recalcRedstone/activateWater
            // 对机器均多余）。轰炸机类完工自动放矿车（spawnStartMinecarts）启动复制循环。
            // 开关 BUILD_MACHINE_SMART 关闭时机器完整回退旧行为（静默+完工唤醒）。
            boolean machineSmart = BlueprintLib.isMachineBlueprint(ps.blueprintId)
                    && com.maidsmart.config.MaidSmartConfig.BUILD_MACHINE_SMART.get();
            // v1.5.331：完工激活前延长 TNT 点火保护窗口——覆盖 recalcRedstone/
            // activateWater/机器电路推进对已放置 TNT 的点火（天机屠龙炮"刚建好
            // 炸膛"根因：观察者→活塞→推 TNT 链在完工瞬间触发）；窗口宽度 =
            // BUILD_TNT_IGNITION_GRACE（秒），期满后机器按正常红石逻辑点火
            com.maidsmart.build.BuildTntGuard.suppressTntFor(level,
                    com.maidsmart.config.MaidSmartConfig.BUILD_TNT_IGNITION_GRACE.get() * 20);
            if (!machineSmart) {
                BlueprintLib.recalcRedstone(level, origin, plan);
            }
            if (prog.skipped > 0 || prog.placedCount < size - 1) {
                if (scanGaps(level, origin, plan, prog)) {
                    return;
                }
            }
            // v1.5.28：自动开入口（蓝图无门时在主人方向外墙开门洞）
            // v1.5.287：红石机器蓝图豁免——machine_ 前缀不开门洞（围壳可能被打穿，
            // 机器内部结构（活塞/漏斗/红石）被破坏）
            if (ps.blueprintId == null || !ps.blueprintId.startsWith("maid_smart:machine_")) {
                BlueprintLib.carveEntrance(level, origin, plan, maid);
            }
            // v1.5.320：轰炸机完工矿车动态提示——已放置 X 辆 / 缺矿车未启动
            int bomberSpawned = 0;
            if (machineSmart) {
                // 机器活建完成：红石已随放随算就位；轰炸机类消耗矿车补矿车启动
                bomberSpawned = BlueprintLib.spawnStartMinecarts(level, origin, plan,
                        BlueprintLib.machineFamily(ps.blueprintId), maid);
            } else {
                // v1.5.57：红石统一激活（普通建筑）——建造期间机械冻结（活塞不推墙），
                // 完成后重放红石组件触发邻居更新 → 线重算 → 机械正常启动
                //（此处保留——补建完成的最终路径再次激活，幂等）
                BlueprintLib.recalcRedstone(level, origin, plan);
            }
            // v1.5.317：所有计划完工统一批激活水/岩浆源（水源静默放置为源块，重放
            // flag 3 → 流动/气泡柱成形——机器水道/冰道与建筑水池都需要；旧版机器
            // 计划无水、activateWater 空转的根因已在 v1.5.317 导入层修复）
            BlueprintLib.activateWater(level, origin, plan);
            // v1.5.331：完工 TNT 点火结算——保护窗口内压制了建造期/激活期点火，
            // 这里只点燃【当前邻接带电】的 TNT，恢复正确终态：轰炸机（矿车压轨
            // 带电）当场启动复制循环；天机屠龙炮等观察者/活塞触发机器（TNT 静止
            // 时无带电邻居）保持惰性，触发时才点火——不再"刚建好炸膛"
            BlueprintLib.settleTntIgnition(level, origin, plan);
            // v1.5.46：清理建造区掉落物（悬空方块历史掉落的物品堆积，实体区块曾达 3.36MB）
            // v1.5.75：范围限制到蓝图包围盒 + 4 格边距（不误清建筑外掉落物）
            BlueprintLib.cleanupDrops(level, origin, plan);
            BuildPlan.clear(level, ps.planId);
            String skipNote = prog.skipped > 0
                    ? "（跳过 " + prog.skipped + " 个蓝图里悬空放不上的方块）" : "";
            int susp = BlueprintLib.countSuspendedGravity(level, origin, plan);
            // v1.5.320：轰炸机完工矿车动态提示——已放置 X 辆 / 缺矿车未启动
            String machineTip = BlueprintLib.machineFinishTip(ps.blueprintId);
            if (machineSmart && "bomber".equals(BlueprintLib.machineFamily(ps.blueprintId))) {
                int need = BlueprintLib.fluidBucketNeeds(plan)
                        .getOrDefault("minecraft:minecart", 0);
                machineTip = bomberSpawned >= need && need > 0
                        ? "\u00a7e【机器】已自动放置矿车 " + bomberSpawned
                                + " 辆，即将启动轰炸循环——请撤离机器附近！"
                        : "\u00a7e【机器】缺少矿车" + (need > 0 ? "（需 " + need + " 辆）" : "")
                                + "，未自动启动——把矿车放进背包后，手动放到探测铁轨上即可启动轰炸循环";
            }
            // v1.5.103：完成——配额归零（防陈旧配额泄洪到下一个计划）+ 通知主人
            this.placeQuota = 0.0;
            if (maid.m_269323_() instanceof net.minecraft.server.level.ServerPlayer owner) {
                owner.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                        "\u00a7a【建造完成】" + (maid.m_5446_() != null ? maid.m_5446_().getString() : "女仆")
                                + " 建好了「" + BuildPlan.planName(plan) + "」" + skipNote
                                + (susp > 0 ? "（注意：" + susp + " 个悬空重力方块，下次方块更新时会落下）" : "")
                                + machineTip));
            }
            maid.getChatBubbleManager().addTextChatBubble(
                    "建好啦！来看看我搭的" + BuildPlan.planName(plan) + skipNote
                    + (susp > 0 ? "（注意：" + susp + " 个悬空重力方块，下次方块更新时会落下）" : ""));
            return;
        }
        // v1.5.48：停滞诊断日志——无放置且未完成时每 20 秒一条（下次卡住直接看日志定位）
        // v1.5.64：附延后明细（前 5 项：方块@坐标 当前位置/区块加载/失败次数）——直接定位卡点
        Long lastLog = LAST_STALL_LOG.get(dim);
        if (lastLog == null || level.m_46467_() - lastLog > 400) {
            LAST_STALL_LOG.put(dim, level.m_46467_());
            StringBuilder detail = new StringBuilder();
            int shown = 0;
            for (java.util.Map.Entry<Integer, Integer> e : prog.deferred.entrySet()) {
                if (shown++ >= 5) {
                    break;
                }
                String[] pp = BlueprintLib.parseStep(plan.get(e.getKey()));
                if (pp == null) {
                    continue;
                }
                int dx;
                int dy;
                int dz;
                try {
                    dx = Integer.parseInt(pp[0]);
                    dy = Integer.parseInt(pp[1]);
                    dz = Integer.parseInt(pp[2]);
                } catch (NumberFormatException ex) {
                    continue;
                }
                BlockPos t = origin.m_7918_(dx, dy, dz);
                detail.append(" [").append(pp[3].replace("minecraft:", ""))
                        .append('@').append(dx).append(',').append(dy).append(',').append(dz)
                        .append(" 现=").append(ForgeRegistries.BLOCKS.getKey(level.m_8055_(t).m_60734_()))
                        .append(" 载=").append(level.m_46749_(t))
                        .append(" 次=").append(e.getValue()).append(']');
            }
            LOGGER.info("build stall: 计划={} 游标={}/{} 延后={} 跳过={} 暂停={}{}",
                    BuildPlan.planName(plan), prog.cursor, size,
                    prog.deferred.size(), prog.skipped, ps != null && ps.paused, detail);
        }
        this.placeCooldown = com.maidsmart.config.MaidSmartConfig.BUILD_STALL_INTERVAL.get();
    }

    /**
     * v1.5.328：TNT 建造期防自燃放置——flag 2 静默挡不住 TntBlock.onPlace
     * （LevelChunk.setBlockState 服务端无条件调 onPlace，字节码实证），活建造下
     * TNT 挨着已带电红石线放置即自燃 → "放置后目标仍空气" → 320 个 TNT 全延后 →
     * 机器游标走到底卡死（旧计划日志实证）。放置 TNT 时挂 BuildTntGuard 护栏
     * （MixinTntBuildGuard 在 onPlace 头部 cancel），TNT 以稳定方块落地。
     * v1.5.331：每次放置都【延长保护时间窗】——覆盖"观察者→活塞→推 TNT"这类
     * 发生在 doPlace 之外的点火链（天机屠龙炮"刚建好炸膛"根因），窗口宽度 =
     * BUILD_TNT_IGNITION_GRACE（秒）；完工结算见 BlueprintLib.settleTntIgnition，
     * 宽限期满后机器按正常红石逻辑点火——"建好就能跑/手动触发"不受影响。
     */
    private static void placeTntSafe(ServerLevel level, BlockPos pos, BlockState state, int flag) {
        com.maidsmart.build.BuildTntGuard.suppressTntFor(level,
                com.maidsmart.config.MaidSmartConfig.BUILD_TNT_IGNITION_GRACE.get() * 20);
        if (state.m_60734_() instanceof net.minecraft.world.level.block.TntBlock) {
            com.maidsmart.build.BuildTntGuard.setSuppress(true);
            try {
                level.m_7731_(pos, state, flag);
            } finally {
                com.maidsmart.build.BuildTntGuard.setSuppress(false);
            }
        } else {
            level.m_7731_(pos, state, flag);
        }
    }

    /** v1.5.40：放置方块 + 恢复精确状态/门上半/方块实体 + 挥臂音效（材料已由调用方消耗）。
     *  v1.5.45：返回 false = 支撑缺失未放置（调用方延后等支撑块建好，防悬空掉落）
     *  v1.5.51：补支撑（时间静止）——支撑格是空气且蓝图无该格步骤（挖空版/图纸缺陷
     *  的悬空方块）时自动垫一块石头再放置；物品不会因物理法则掉落，建造不会卡住。
     *  蓝图有该格步骤（支撑后建）→ 维持延后等待支撑步骤先建。
     *  v1.5.77：修复支撑格判定坐标系——plannedPositions 用【蓝图相对坐标】编码，
     *  旧版把世界坐标塞进去查，原点非 (0,0,0) 时永远查不到 → 补石头无视蓝图步骤
     *  乱放（石头覆盖房间方块/草方块 → 女仆覆盖回去 → 反复循环）。
     *  v1.5.252i：force=true（延后重试 ≥3 次的强制模式）——支撑格是空气/流体时
     *  【无视蓝图步骤直接补支撑】；放置后 canSurvive 失败时【无条件把支撑格换成
     *  合法支撑】（甘蔗→沙子、植物→泥土、其他→石头）再重放——根治外部蓝图
     *  "大量悬空放不上"（蓝图作者在创造模式画的悬空活板门/火把/甘蔗/横幅等，
     *  支撑格是空气步骤/水/石头时旧版永远等不到支撑 → 3 次后永久跳过）。 */
    private static boolean doPlace(ServerLevel level, EntityMaid maid, BlockPos origin, BlockPos target,
                                   java.util.List<String> plan, Block placed, String stateSnbt, String beSnbt,
                                   java.util.Set<Long> plannedPos, boolean force, Block fallbackBlock,
                                   boolean live) {
        BlockState placeState = placed.m_49966_();
        // 结构文件蓝图：恢复精确状态（台阶/楼梯朝向/门等）
        // v1.5.254：parseStepState 补 Name——旧版内联解析无 Name 被 NbtUtils 当空气
        if (stateSnbt != null) {
            BlockState parsed = BlueprintLib.parseStepState(level, placed, stateSnbt);
            if (parsed != null) {
                placeState = parsed;
            }
        }
        // v1.5.45：支撑检查——火把/按钮/拉杆/梯子/地毯/花等附着方块若支撑面缺失
        // （支撑块在计划中排在后面/未建），先延后，等支撑建好后自动补建（防悬空掉落）
        net.minecraft.core.Direction sup = BlueprintLib.supportDirection(placeState);
        boolean fluidSupport = false;
        if (sup != null) {
            BlockPos supPos = target.m_121945_(sup); // relative(Direction)
            BlockState supState = level.m_8055_(supPos);
            if (supState.m_60795_()
                    || supState.m_60819_().m_205070_(net.minecraft.tags.FluidTags.f_13131_)) {
                // v1.5.349：支撑格已是【流体】且蓝图该格就是流体步骤（水/岩浆）——
                // 设计意图的流体支撑（村民机水闸活板门：顶部活板门上方是水，原版
                // canSurvive 放不上，蓝图作者用工具强放；活板门 neighborChanged 只
                // 切开合不自毁，javap 实证）→ 直接放置附着块，不替换流体、不无限
                // 延后（旧版延后到 3 次后 force 把水换成石头/缺失 → 机器"无法工作"）
                if (supState.m_60819_().m_205070_(net.minecraft.tags.FluidTags.f_13131_)
                        && plannedPos != null && plannedPos.contains(posKey(supPos, origin))
                        && BlueprintLib.isFluidStepAt(plan, origin, supPos)) {
                    fluidSupport = true;
                } else if (plannedPos == null || !plannedPos.contains(posKey(supPos, origin)) || force) {
                    // v1.5.51：补支撑——支撑格空/流体且蓝图里没有该格步骤 → 自动垫支撑
                    // v1.5.82：按类型选合法支撑（甘蔗→沙子、植物→泥土、其他→石头）——
                    // 补石头对甘蔗不合法（canSurvive 失败 → 邻居更新时被打掉 → 反复循环）
                    // v1.5.252i：force 模式无视蓝图步骤直接补——蓝图有支撑步骤但该步骤
                    // 是空气/水（外部蓝图悬空设计）或永未建成时，旧版永远延后 → 3 次跳过
                    net.minecraft.world.level.block.Block support = supportBlockFor(placed, fallbackBlock);
                    if (support != null) {
                        // v1.5.252af：支撑格区块未加载 → 直接延后（setBlock 会静默
                        // 失败——"补支撑后支撑格仍空/流体"的根因，用户实测甘蔗 124 个跳过）
                        if (!level.m_46749_(supPos)) {
                            logPlaceFail(level, target, placed, "支撑格区块未加载");
                            return false;
                        }
                        level.m_7731_(supPos, support.m_49966_(), 3);
                        supState = level.m_8055_(supPos);
                        if (!supState.m_60795_()
                                && !supState.m_60819_().m_205070_(net.minecraft.tags.FluidTags.f_13131_)) {
                            // 补支撑成功 → 继续放置（物品不会掉落 = 时间静止）
                        } else {
                            logPlaceFail(level, target, placed, "补支撑后支撑格仍空/流体");
                            return false; // 支撑没放上（异常）→ 延后兜底
                        }
                    } else {
                        logPlaceFail(level, target, placed, "无合法支撑方块类型");
                        return false;
                    }
                } else {
                    return false; // 蓝图有步骤（支撑后建）→ 延后，等支撑建好
                }
            }
        }
        // v1.5.57：建造期间一律静默放置（flag 2，不触发邻居更新）——红石线/火把/
        // 红石块/拉杆放置时不激活邻居 → 活塞/机械冻结，不再"推掉刚建的墙"互搏
        // （红石住宅 282 个活塞，95 个有激活源：线一放好就通电推墙 → 女仆补建
        // → 再推 → 无限循环"石头跟石英块过不去"）。
        // v1.5.316：机器活建造——live=true 时 flag 3（正常更新，红石/水流随放随算），
        // 配合机器专属顺序（动力源最后落位）机器建好即自然运行，无需完工唤醒；
        // 普通建筑保持 flag 2 静默（机械冻结）。
        // v1.5.317：TNT 与液体（水/岩浆）始终 flag 2 静默——
        // TNT 防放置时被已带电的信号点燃（TNT 大炮实证）；液体静默放置全部源块
        // 后由完工 activateWater 批激活 → 统一流动/气泡柱成形（逐个活放会因相邻
        // 水源先流过来而跳过后续源块 → 气泡柱/水道不完整）。
        // v1.5.328：flag 2 挡不住 TntBlock.onPlace（LevelChunk.setBlockState 服务端
        // 无条件调 onPlace，字节码实证）——TNT 放置改走 placeTntSafe 挂护栏
        // （MixinTntBuildGuard 在 onPlace 头部 cancel），TNT 以稳定方块落地；
        // 机器运行时红石翻转/触发走 neighborChanged 正常点燃。
        // 支撑由 v1.5.51 补支撑保证；掉落验证仍兜底。
        int flag = 2;
        if (live && !(placed instanceof net.minecraft.world.level.block.TntBlock)
                && !(placed instanceof net.minecraft.world.level.block.LiquidBlock)) {
            flag = 3;
        }
        placeTntSafe(level, target, placeState, flag);
        // v1.5.48：放置后掉落验证——支撑检查覆盖不到的类型（雪层/甘蔗/花/藤蔓等
        // 漏网方块）放置后若立即掉落（悬空），回收掉落物并返回 false → 延后/跳过，
        // 杜绝"女仆挥臂成功（有动作有声音）但方块消失"的假进展
        // v1.5.51：先尝试补下方石头重放一次（漏网类型自动补支撑），仍失败才跳过
        // v1.5.82：加 canSurvive 验证（m_60796_）——支撑不合法（如甘蔗下方是补的
        // 石头）时静默放置看似成功，但邻居更新时会被破坏（"放置又被打掉"循环）；
        // 补【合法】支撑（甘蔗→沙子、植物→泥土）后重放，仍不合法才延后
        // v1.5.252i：支撑格【无条件换成合法支撑】再重放——下方是石头/水/台阶等
        // 不合法支撑（外部蓝图常见：甘蔗种石头上/水边、活板门下是台阶）时旧版
        // 只补"空气/流体"格，其余直接失败 → 3 次跳过
        // v1.5.252ad：bad 判定放宽——目标已是目标方块（placed 或解析状态同方块）
        // → 成功。旧版 !canSurvive 对已放置的玻璃/树叶/活塞/观察者/漏斗等误判失败
        // （日志实证：484 玻璃、96 树叶…"目标现=目标方块"却 place-fail）→ 全方块
        // 延后 3 次 → 大量跳过（用户实测"甘蔗农场跳过 130 个"）。canSurvive 对
        // 解析出的状态（stateSnbt）在部分方块上返回 false 的原因待查，但目标方块
        // 已在 = 放置成功，不应判失败。
        BlockState targetState = level.m_8055_(target);
        boolean bad = targetState.m_60795_()
                || (targetState.m_60734_() != placeState.m_60734_()
                    && targetState.m_60734_() != placed);
        if (bad) {
            if (fluidSupport) {
                // v1.5.349：流体支撑的附着块——setBlock 粘住即成功（活板门等不
                // 自毁，跳过 canSurvive 判定，水保持原位）；没粘住（异常）→ 延后
                // 不替换流体（水是机器水闸的一部分，换石头=毁机器）
                if (level.m_8055_(target).m_60795_()) {
                    logPlaceFail(level, target, placed, "流体支撑附着块放置失败");
                    return false;
                }
            } else {
                net.minecraft.world.level.block.Block support = supportBlockFor(placed, fallbackBlock);
                if (support != null) {
                    if (sup != null) {
                    // 附着类：把支撑方向格换成合法支撑（覆盖空气/流体/不合法方块）
                    // v1.5.252m：保护树叶——树冠装饰（火把/按钮/红石线等）的支撑格
                    // 是树叶时【不换】（树叶换成石头毁掉树冠外观，252i 副作用），
                    // 保持失败 → 延后 → 跳过（蓝图缺陷本来就不该强建）
                    BlockPos supPos = target.m_121945_(sup);
                    if (!(level.m_8055_(supPos).m_60734_()
                            instanceof net.minecraft.world.level.block.LeavesBlock)) {
                        level.m_7731_(supPos, support.m_49966_(), 3);
                        placeTntSafe(level, target, placeState, flag);
                    }
                } else {
                    // 无支撑方向的漏网类型（雪层/重力等）：补/换下方格
                    BlockPos below = target.m_7918_(0, -1, 0);
                    BlockState belowState = level.m_8055_(below);
                    if (belowState.m_60795_()
                            || belowState.m_60819_().m_205070_(net.minecraft.tags.FluidTags.f_13131_)
                            || force) {
                        if (plannedPos == null || !plannedPos.contains(posKey(below, origin)) || force) {
                            // v1.5.252m：树叶不换（同附着类，保护树冠）
                            if (!(belowState.m_60734_()
                                    instanceof net.minecraft.world.level.block.LeavesBlock)) {
                                level.m_7731_(below, support.m_49966_(), 3);
                                // 重放（复用上面的 flag：附着/红石类已按 flag 3 计算）
                                placeTntSafe(level, target, placeState, flag);
                            }
                        }
                    }
                }
            }
            // v1.5.263：空气步骤（清除）跳过 canSurvive 验证——air.canSurvive 对部分
            // 状态返回 false，清除成功仍报"目标仍空气或 canSurvive 失败"（用户日志：
            // air 清除红石线失败，目标现=redstone_wire）
            if (placed != net.minecraft.world.level.block.Blocks.f_50016_
                    && (level.m_8055_(target).m_60795_() || !placeState.m_60796_(level, target))) {
                logPlaceFail(level, target, placed, "放置后目标仍空气或 canSurvive 失败");
                net.minecraft.world.phys.AABB dropBox = new net.minecraft.world.phys.AABB(target).m_82400_(1.5);
                for (net.minecraft.world.entity.item.ItemEntity e
                        : level.m_45976_(net.minecraft.world.entity.item.ItemEntity.class, dropBox)) {
                    e.m_142687_(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                }
                return false;
            }
            }
        }
        // v1.5.15：门下半放置后补全上半（setBlock 不会自动补，避免"半扇门"）
        ensureDoorUpper(level, target, placeState);
        // v1.5.275/276：放置成功后回收目标格附近【同 id】掉落物——替代品"搭一个又掉
        // 一个"的循环根源已由替代品验收（altUsed/isAltPlaced）根治：替代品放置后被
        // 认可为已建，不再被拆掉重放（拆→掉→捡回背包→再放→再拆 = 材料数量翻倍观感）。
        // 此处仅兜底清理拆障残留/悬空掉落的同 id 废料（只清同 id，不影响其他掉落物）。
        try {
            String placedId = ForgeRegistries.ITEMS.getKey(placed.m_5456_()).toString();
            net.minecraft.world.phys.AABB dropBox2 = new net.minecraft.world.phys.AABB(target).m_82400_(1.5);
            for (net.minecraft.world.entity.item.ItemEntity e
                    : level.m_45976_(net.minecraft.world.entity.item.ItemEntity.class, dropBox2)) {
                net.minecraft.world.item.ItemStack stk = e.m_32055_();
                if (!stk.m_41619_() && ForgeRegistries.ITEMS.getKey(stk.m_41720_()).toString().equals(placedId)) {
                    e.m_142687_(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                }
            }
        } catch (Exception ignored) {
        }
        // 方块实体数据（箱子内容/告示牌文字等）
        if (beSnbt != null) {
            try {
                net.minecraft.nbt.CompoundTag beTag = net.minecraft.nbt.NbtUtils.m_178024_(beSnbt);
                net.minecraft.world.level.block.entity.BlockEntity be = level.m_7702_(target);
                if (be != null) {
                    be.m_142466_(beTag);
                    be.m_7651_();
                }
            } catch (Exception ignored) {
            }
        }
        // v1.5.13：每放一块输出一次动作——挥臂 + 方块放置音效（隔空建造的"手感"）
        maid.m_21011_(net.minecraft.world.InteractionHand.MAIN_HAND, true);
        // v1.5.60: 放置音效限频 (冲刺模式每秒几百块的音效爆棚防护 - 每 tick 最多 3 个)
        if (lastSoundGameTime != level.m_46467_()) {
            lastSoundGameTime = level.m_46467_();
            soundBudget = 3;
        }
        if (soundBudget > 0) {
            soundBudget--;
            level.m_5594_(null, target, placeState.m_60827_().m_56777_(),
                    net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 1.0f);
        }
        return true;
    }

    /** v1.5.276：替代品验收——该步骤已用替代品放置且目标格就是它（缺料替换不再
     *  被拆了重放：拆→掉→捡回→再放→再拆 = 背包材料"翻倍"观感；回收掉落物只治标） */
    private static boolean isAltPlaced(BuildPlan.Progress prog, int idx, BlockState st) {
        String altId = prog.altUsed.get(idx);
        if (altId == null) {
            return false;
        }
        // v1.5.284：getKey 判空——未注册方块不 NPE
        net.minecraft.resources.ResourceLocation key = ForgeRegistries.BLOCKS.getKey(st.m_60734_());
        return key != null && key.toString().equals(altId);
    }

    /** v1.5.276：放置成功路径记录替代品（替换时配套 isAltPlaced 验收） */
    private static void recordAltUsed(BuildPlan.Progress prog, int idx,
                                      String blockId, net.minecraft.world.item.Item used,
                                      net.minecraft.resources.ResourceLocation usedId) {
        // v1.5.287：itemForBlock（redstone_wire → 红石粉——红石线不会被误判为替代品）
        net.minecraft.world.item.Item exactItem = BlueprintLib.itemForBlock(blockId);
        if (exactItem != null && used != exactItem && usedId != null) {
            prog.altUsed.put(idx, usedId.toString());
        }
    }

    /** v1.5.51：位置 → 64 位键（与 Progress.plannedPositions 编码一致）。
     *  v1.5.77：世界坐标先转【相对原点】坐标——plannedPositions 用蓝图相对坐标
     *  编码（plan 步骤坐标），旧版直接编码世界坐标导致 contains 永远不命中。 */
    private static long posKey(BlockPos p, BlockPos origin) {
        return (long) ((p.m_123341_() - origin.m_123341_()) & 0xFFFFF) << 42
                | (long) ((p.m_123342_() - origin.m_123342_()) & 0x1FFFFF) << 21
                | (long) ((p.m_123343_() - origin.m_123343_()) & 0x1FFFFF);
    }

    /** v1.5.252w：doPlace 失败诊断（同一位置 10 秒限频）——latest.log 搜 "build place-fail" */
    private static final java.util.Map<String, Long> FAIL_LOG = new java.util.HashMap<>();

    private static void logPlaceFail(ServerLevel level, BlockPos target, Block placed, String reason) {
        long now = level.m_46467_();
        String key = target.m_123341_() + "," + target.m_123342_() + "," + target.m_123343_();
        Long last = FAIL_LOG.get(key);
        if (last != null && now - last < 200) {
            return;
        }
        // v1.5.287：惰性防膨胀——超过 1024 条清一半（老记录已过期，防长期服务器慢漏）
        if (FAIL_LOG.size() > 1024) {
            java.util.List<String> keys = new java.util.ArrayList<>(FAIL_LOG.keySet());
            int cut = keys.size() / 2;
            for (int i = 0; i < cut; i++) {
                FAIL_LOG.remove(keys.get(i));
            }
        }
        FAIL_LOG.put(key, now);
        net.minecraft.resources.ResourceLocation bid = ForgeRegistries.BLOCKS.getKey(placed);
        net.minecraft.resources.ResourceLocation cur = ForgeRegistries.BLOCKS.getKey(level.m_8055_(target).m_60734_());
        LOGGER.info("build place-fail: {}@({},{},{}) 原因={} 目标现={}",
                bid, target.m_123341_(), target.m_123342_(), target.m_123343_(), reason, cur);
    }

    /** v1.5.82：按放置方块类型选【合法支撑】——甘蔗→沙子、植物（BushBlock）→泥土、
     *  其他→fallback（蓝图主要建材，无则石头）。旧版一律补石头：甘蔗下方是石头时
     *  canSurvive 失败（MC 甘蔗只认沙子/泥土/甘蔗），静默放置看似成功，但邻居更新
     *  （flag 3）触发检查时被打掉 → 反复"放置又被打掉"循环。
     *  v1.5.252aa：fallback 用蓝图主要建材——旧版无中生有补石头 → 建筑里出现
     *  材料表没有的石头（用户实测：甘蔗农场大量石头）——改用蓝图自己的材料视觉一致
     *  v1.5.263：红石科技件固定石头——fallback（蓝图主要建材）可能是甘蔗/水等
     *  非 canSupportRigidBlock 方块 → 红石线/中继器补在甘蔗上 canSurvive 失败
     *  → 3 次永久跳过（用户实测："悬浮的红石放不上"） */
    private static net.minecraft.world.level.block.Block supportBlockFor(Block placed, Block fallback) {
        // v1.5.284：各分支判空兜底——查询结果缺失时落到下一分支/fallback，不返回 null
        if (placed instanceof net.minecraft.world.level.block.SugarCaneBlock) {
            net.minecraft.world.level.block.Block b = ForgeRegistries.BLOCKS.getValue(
                    net.minecraft.resources.ResourceLocation.parse("minecraft:sand"));
            if (b != null) {
                return b;
            }
        }
        // v1.5.268：仙人掌同甘蔗——canSurvive 需要沙地（补石头也会掉）
        if (placed instanceof net.minecraft.world.level.block.CactusBlock) {
            net.minecraft.world.level.block.Block b = ForgeRegistries.BLOCKS.getValue(
                    net.minecraft.resources.ResourceLocation.parse("minecraft:sand"));
            if (b != null) {
                return b;
            }
        }
        if (placed instanceof net.minecraft.world.level.block.BushBlock) {
            net.minecraft.world.level.block.Block b = ForgeRegistries.BLOCKS.getValue(
                    net.minecraft.resources.ResourceLocation.parse("minecraft:dirt"));
            if (b != null) {
                return b;
            }
        }
        // v1.5.263：需要 canSupportRigidBlock 支撑的科技件 → 石头（无视 fallback）
        if (placed instanceof net.minecraft.world.level.block.RedStoneWireBlock
                || placed instanceof net.minecraft.world.level.block.DiodeBlock
                || placed instanceof net.minecraft.world.level.block.BaseRailBlock
                || placed instanceof net.minecraft.world.level.block.PressurePlateBlock
                || placed instanceof net.minecraft.world.level.block.DaylightDetectorBlock
                || placed instanceof net.minecraft.world.level.block.TrapDoorBlock
                || placed instanceof net.minecraft.world.level.block.TripWireHookBlock
                || placed instanceof net.minecraft.world.level.block.LeverBlock
                || placed instanceof net.minecraft.world.level.block.ButtonBlock
                || placed instanceof net.minecraft.world.level.block.TorchBlock) {
            net.minecraft.world.level.block.Block b = ForgeRegistries.BLOCKS.getValue(
                    net.minecraft.resources.ResourceLocation.parse("minecraft:stone"));
            if (b != null) {
                return b;
            }
        }
        if (fallback != null) {
            return fallback;
        }
        return ForgeRegistries.BLOCKS.getValue(
                net.minecraft.resources.ResourceLocation.parse("minecraft:stone"));
    }

    /** v1.5.252aa：计划主要建材缓存（planId → 出现最多的完整方块）——补支撑默认方块 */
    private static final java.util.Map<String, Block> PLAN_MAIN = new java.util.HashMap<>();

    /** v1.5.317：水/岩浆缺料提示按桶结算（材料链 itemIdForBlock 已映射到桶）——
     *  缺"水"报"水桶"、缺"岩浆"报"岩浆桶"，其余原样 */
    private static String fluidBucketId(String blockId) {
        if ("minecraft:water".equals(blockId)) {
            return "minecraft:water_bucket";
        }
        if ("minecraft:lava".equals(blockId)) {
            return "minecraft:lava_bucket";
        }
        return blockId;
    }

    /** v1.5.322：相对坐标 → 世界区块键（重发区块用） */
    private static long chunkKeyOf(BlockPos origin, int rx, int rz) {
        int cx = (origin.m_123341_() + rx) >> 4;
        int cz = (origin.m_123343_() + rz) >> 4;
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /** v1.5.322：客户端半透明渲染刷新——建造过程中快速批量放置时，客户端区块重建
     *  队列积压，玻璃/粘液块等【半透明渲染层】迟迟不重建，从区块外往里看"看不见"
     *  （实心层先重建、半透明层滞后）。每 2 秒把放置过的区块【整块重发】给
     *  同维度玩家（空光源掩码=只刷方块渲染不动光照）→ 强制客户端整块重建（含
     *  半透明层），玻璃/粘液块即时可见。
     *  v1.5.328【修复无效根因】：旧版 LAST_CHUNK_RESEND = Long.MIN_VALUE——首次调用
     *  now - Long.MIN_VALUE 整数溢出为负 → 节流判定恒 <40 → return，重发【从不执行】
     *  （"修复了但还是看不见"的确切原因）。改 0（游戏刻恒 ≥0，首次即生效）。
     *  另：待重发集合改【静态跨轮累积】（PENDING_RESEND_CHUNKS）——旧版每轮新建
     *  局部集，节流 2 秒窗口内"非触发轮"的放置会漏发；累积集在重发成功后一次清空。 */
    private static final java.util.Set<Long> PENDING_RESEND_CHUNKS = new java.util.HashSet<>();

    private static long LAST_CHUNK_RESEND = 0;

    private static void resendPlacedChunks(net.minecraft.server.level.ServerLevel level) {
        if (PENDING_RESEND_CHUNKS.isEmpty()) {
            return;
        }
        long now = level.m_46467_();
        if (now - LAST_CHUNK_RESEND < 40) {
            return; // 节流：每 2 秒最多一次（批量建造下足够，避免每 tick 刷区块）
        }
        LAST_CHUNK_RESEND = now;
        for (Long ck : PENDING_RESEND_CHUNKS) {
            int cx = (int) (long) (ck >> 32);
            int cz = (int) (long) ck;
            net.minecraft.world.level.chunk.LevelChunk chunk = level.m_6325_(cx, cz);
            if (chunk == null) {
                continue;
            }
            net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket pkt =
                    new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                            chunk, level.m_7726_().m_7827_(),
                            new java.util.BitSet(), new java.util.BitSet());
            for (net.minecraft.server.level.ServerPlayer player : level.m_6907_()) {
                if (player.m_9236_() == level) {
                    player.f_8906_.m_9829_(pkt);
                }
            }
        }
        PENDING_RESEND_CHUNKS.clear(); // 已重发 → 清空待发集（跨轮累积防漏）
    }

    private static Block planMainBlock(BuildPlan.PlanState ps, List<String> plan) {
        Block cached = PLAN_MAIN.get(ps.planId);
        if (cached != null) {
            return cached;
        }
        java.util.Map<String, Integer> cnt = new java.util.HashMap<>();
        for (int i = 1; i < plan.size(); i++) {
            String[] parts = BlueprintLib.parseStep(plan.get(i));
            if (parts == null) {
                continue;
            }
            cnt.merge(parts[3], 1, Integer::sum);
        }
        String best = null;
        int bestN = 0;
        for (java.util.Map.Entry<String, Integer> e : cnt.entrySet()) {
            if (BlueprintLib.FORBIDDEN.contains(e.getKey())) {
                continue;
            }
            Block b = ForgeRegistries.BLOCKS.getValue(
                    net.minecraft.resources.ResourceLocation.parse(e.getKey()));
            if (b == null || !isFullBuildBlock(b)) {
                continue;
            }
            if (e.getValue() > bestN) {
                bestN = e.getValue();
                best = e.getKey();
            }
        }
        Block main = best == null ? null : ForgeRegistries.BLOCKS.getValue(
                net.minecraft.resources.ResourceLocation.parse(best));
        // v1.5.287：惰性防膨胀——超过 256 个计划条目清空重建（旧计划已 clear 的残留，
        // 防长期服务器慢漏；重建成本 O(N) 一次可接受）
        if (PLAN_MAIN.size() > 256) {
            PLAN_MAIN.clear();
        }
        PLAN_MAIN.put(ps.planId, main);
        return main;
    }

    /** 完整建材（排除空气/液体/台阶/楼梯/栅栏/玻璃/树叶/附着/装饰等非完整方块） */
    private static boolean isFullBuildBlock(Block b) {
        net.minecraft.world.level.block.state.BlockState st = b.m_49966_();
        if (st.m_60795_()
                || st.m_60819_().m_205070_(net.minecraft.tags.FluidTags.f_13131_)) {
            return false;
        }
        return !(b instanceof net.minecraft.world.level.block.SlabBlock
                || b instanceof net.minecraft.world.level.block.StairBlock
                || b instanceof net.minecraft.world.level.block.FenceBlock
                || b instanceof net.minecraft.world.level.block.FenceGateBlock
                || b instanceof net.minecraft.world.level.block.WallBlock
                || b instanceof net.minecraft.world.level.block.GlassBlock
                || b instanceof net.minecraft.world.level.block.StainedGlassBlock
                || b instanceof net.minecraft.world.level.block.LeavesBlock
                || b instanceof net.minecraft.world.level.block.BushBlock
                || b instanceof net.minecraft.world.level.block.CarpetBlock
                || b instanceof net.minecraft.world.level.block.TorchBlock
                || b instanceof net.minecraft.world.level.block.RedStoneWireBlock
                || b instanceof net.minecraft.world.level.block.DiodeBlock
                || b instanceof net.minecraft.world.level.block.DoorBlock
                || b instanceof net.minecraft.world.level.block.TrapDoorBlock
                || b instanceof net.minecraft.world.level.block.LadderBlock
                || b instanceof net.minecraft.world.level.block.ChainBlock
                || b instanceof net.minecraft.world.level.block.LanternBlock
                || b instanceof net.minecraft.world.level.block.FlowerPotBlock
                || b instanceof net.minecraft.world.level.block.BannerBlock);
    }

    /** v1.5.82：放置成功计数——placedSet 按相对坐标去重，补建/覆盖重复放置
     *  同一位置不再重复累加（修复进度出现 150% 等超 100% 的重复计算） */
    private static void countPlaced(BuildPlan.Progress prog, int x, int y, int z) {
        long key = (long) (x & 0xFFFFF) << 42
                | (long) (y & 0x1FFFFF) << 21
                | (long) (z & 0x1FFFFF);
        if (prog.placedSet.add(key)) {
            prog.placedCount++;
        }
    }

    /** v1.5.66：缺口补建扫描——完成时检查所有步骤的真实存在性：
     *  位置变空/变地形（苦力怕炸洞、方块消失）→ 重新加入延后补建；
     *  已判定跳过的步骤（skippedIdx）不重复尝试（防死循环）；
     *  位置是障碍 → 不算缺口（不破坏玩家建筑）。返回是否发现缺口。 */
    /** v1.5.273：scanGaps 全表扫描限频（维度 → 上次扫描 tick）——55 万步蓝图每 20 秒
     *  一次 O(N) parseStep 全表扫 = tick 尖峰；限频 60 秒（限频期内沿用"未完成"判定，
     *  deferred 清空才放行完成） */
    private static final java.util.Map<String, Long> LAST_GAP_SCAN = new java.util.HashMap<>();

    private static boolean scanGaps(ServerLevel level, BlockPos origin, List<String> plan,
                                    BuildPlan.Progress prog) {
        // v1.5.287：限频键从维度改 planId——同维度多区块共存时互不挤占扫描窗口
        String dimKey = prog.tag;
        long now = level.m_46467_();
        Long lastScan = LAST_GAP_SCAN.get(dimKey);
        if (lastScan != null && now - lastScan < 1200L) {
            // 限频期内：deferred 还有条目 → 未完成；空 → 放行完成
            return !prog.deferred.isEmpty();
        }
        LAST_GAP_SCAN.put(dimKey, now);
        boolean found = false;
        java.util.Map<String, Integer> gapStats = new java.util.HashMap<>();
        for (int i = 1; i < plan.size(); i++) {
            if (prog.skippedIdx.contains(i)) {
                continue;
            }
            String[] parts = BlueprintLib.parseStep(plan.get(i));
            if (parts == null) {
                continue;
            }
            int x;
            int y;
            int z;
            try {
                x = Integer.parseInt(parts[0]);
                y = Integer.parseInt(parts[1]);
                z = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                continue;
            }
            String blockId = parts[3];
            // v1.5.287：改用计划级 blockCache（旧版每步重查注册表，同主循环 394 行）
            Block block = prog.blockCache.computeIfAbsent(blockId,
                    id -> ForgeRegistries.BLOCKS.getValue(net.minecraft.resources.ResourceLocation.parse(id)));
            if (block == null) {
                continue;
            }
            BlockPos pos = origin.m_7918_(x, y, z);
            if (!level.m_46749_(pos)) {
                continue; // 区块未加载的留到强制加载就绪后再查
            }
            BlockState st = level.m_8055_(pos);
            // v1.5.276：+替代品验收（缺料替换不再被缺口扫描重放——拆→掉→捡→再放循环）
            if (st.m_60734_() == block || BlueprintLib.isBuiltEquivalent(blockId, st.m_60734_())
                    || isAltPlaced(prog, i, st)) {
                continue; // 已建
            }
            if (st.m_60795_() || BlueprintLib.isAllowedGround(st)) {
                prog.deferred.putIfAbsent(i, 0); // 缺口 → 重新补建
                found = true;
                gapStats.merge(blockId, 1, Integer::sum);
            }
        }
        if (found) {
            // v1.5.273：缺口明细（前 8 种方块）——"建造永不完成"直接看日志定位
            StringBuilder sb = new StringBuilder();
            gapStats.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(8)
                    .forEach(e -> sb.append('[').append(e.getKey().replace("minecraft:", ""))
                            .append("×").append(e.getValue()).append(']'));
            int total = gapStats.values().stream().mapToInt(Integer::intValue).sum();
            LOGGER.info("scanGaps: 缺口 {} 个：{}", total, sb);
        }
        return found;
    }

    /** v1.5.25：主人背包有该材料时自动转一组给女仆（分批补料自动续建，无需重新下达） */
    private boolean tryTakeFromOwner(EntityMaid maid, String blockId) {
        net.minecraft.world.entity.player.Player owner = null;
        if (maid.m_269323_() instanceof net.minecraft.world.entity.player.Player p) {
            owner = p;
        }
        if (owner == null || BlueprintLib.isCreative(owner)) {
            return false;
        }
        if (BlueprintLib.countPlayerMaterial(owner, blockId) <= 0) {
            return false;
        }
        int before = BlueprintLib.countMaterial(maid, blockId);
        BlueprintLib.deliverToMaid(owner, maid, java.util.Map.of(blockId, 64));
        return BlueprintLib.countMaterial(maid, blockId) > before;
    }

    @Override
    protected boolean m_6737_(ServerLevel level, EntityMaid maid, long gameTime) {
        // 建筑任务期间持续站桩（无论有无计划）
        // v1.5.177：暂停 = 解除绑定——暂停时 canStillUse 返回 false → 行为停止
        //（框架调 stop：清站桩标记 + 退出 home 模式），女仆恢复自由移动/切任务，
        // 可以停下来去干别的事；恢复建造后重新启动。
        // v1.5.180：暂停按女仆绑定区块判定（多区块共存）
        boolean still = BlueprintBuildExecutor.isBuildingTask(maid)
                && !BuildPlan.isBoundPlanPaused(maid)
                && !BuildPlan.isMaidPaused(maid);
        if (!still) {
            // v1.5.24：任务切走 → 解除站桩标记（防止 MoveToTargetSink 被永久拦截）
            MaidWorkTags.setStill(maid, false);
        }
        return still;
    }

    @Override
    protected void m_6732_(ServerLevel level, EntityMaid maid, long gameTime) {
        // v1.5.24：行为真正停止时也解除站桩标记（双保险）
        MaidWorkTags.setStill(maid, false);
        // v1.5.121：建造结束/切任务/完工 → 退出 home 模式，恢复跟随能力
        //（home 模式只在建造行为运行时才强制开启，行为一停立即切回）
        maid.setHomeModeEnable(false);
    }

    private void notifyMissing(EntityMaid maid, String blockId) {
        // v1.5.265：缺料播报只由工头发（有有效工头时）——14 只建造女仆同时
        // 缺料时旧版每只都报（各限一次也刷屏），工头汇报一次足够
        if (!BuildPlan.isForeman(maid)) {
            return;
        }
        // v1.5.275：缺料播报 30 秒冷却（按女仆）——旧版 blockId 轮流缺就轮流报
        //（日志实证：石头 407→376→344…红色床×2…石砖 每 5~11 秒一条刷屏）。
        // 冷却期内静默（女仆继续干，30 秒后仍缺才再提醒）
        long nowTick = maid.m_9236_().m_46467_();
        Long lastCd = MISSING_CD.get(maid.m_20148_());
        if (lastCd != null && nowTick - lastCd < 600L) {
            return;
        }
        if (blockId.equals(this.missingNotified)) {
            return;
        }
        this.missingNotified = blockId;
        MISSING_CD.put(maid.m_20148_(), nowTick);
        LAST_MISSING.put(maid.m_20148_(), blockId);
        // v1.5.31：明确续建操作——材料放进【主人自己的背包】即可，女仆每 tick 自动
        // 拿料继续建（不用再点手册/不用切任务；已建部分自动跳过）
        // v1.5.114：带缺口数量（计划剩余需求 - 女仆已有 - 主人已有）
        // v1.5.144：方块名改中文（旧版直接输出英文注册名 minecraft:lantern）
        int need = estimateMissing(maid, blockId);
        String cn = BlueprintLib.cnName(blockId);
        maid.getChatBubbleManager().addTextChatBubble("材料不够了，还缺 " + cn
                + (need > 0 ? " ×" + need : "") + "。把 " + cn
                + " 放进你自己的背包里，我会自己拿，接着盖～（不用再点手册）");
    }

    /** v1.5.114：估算某方块还缺多少（计划剩余需求 − 女仆背包 − 主人背包；创造模式视为充足）
     *  v1.5.218：材料计算按用户要求 = 蓝图总需求 − 已累计搭建的 − 永久跳过的
     *  − 主人背包 − 女仆背包（旧版漏扣"已搭建"，建到一半缺口显示还是全量需求） */
    private int estimateMissing(EntityMaid maid, String blockId) {
        List<String> plan = BuildPlan.getBoundPlan(maid);
        if (plan.isEmpty()) {
            return 0;
        }
        com.maidsmart.build.BuildPlan.PlanState ps = BuildPlan.getBoundPlanState(maid);
        com.maidsmart.build.BuildPlan.Progress prog = ps != null ? BuildPlan.progress(ps) : null;
        int needed = 0;
        for (int i = 1; i < plan.size(); i++) {
            String[] parts = BlueprintLib.parseStep(plan.get(i));
            if (parts == null || !parts[3].equals(blockId)) {
                continue;
            }
            if (prog != null && prog.skippedIdx.contains(i)) {
                continue; // 永久跳过（悬空/基岩挡住等）→ 不再计入需求
            }
            if (prog != null) {
                try {
                    long key = (long) (Integer.parseInt(parts[0]) & 0xFFFFF) << 42
                            | (long) (Integer.parseInt(parts[1]) & 0x1FFFFF) << 21
                            | (Integer.parseInt(parts[2]) & 0x1FFFFF);
                    if (prog.placedSet.contains(key)) {
                        continue; // 已累计搭建 → 不再计入需求
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            needed++;
        }
        net.minecraft.world.entity.player.Player owner = null;
        if (maid.m_269323_() instanceof net.minecraft.world.entity.player.Player p) {
            owner = p;
        }
        if (BlueprintLib.isCreative(owner)) {
            return 0;
        }
        int have = BlueprintLib.combinedHave(owner, maid, blockId);
        return Math.max(0, needed - have);
    }

    /** 门上半自动补全：放置 door 下半后，若上方是空气则补 upper half（防止半扇门） */
    private static void ensureDoorUpper(ServerLevel level, BlockPos target, BlockState state) {
        for (net.minecraft.world.level.block.state.properties.Property<?> p : state.m_61147_()) {
            if (!"half".equals(p.m_61708_())) {
                continue;
            }
            try {
                @SuppressWarnings("unchecked")
                net.minecraft.world.level.block.state.properties.Property<net.minecraft.world.level.block.state.properties.DoubleBlockHalf> halfProp =
                        (net.minecraft.world.level.block.state.properties.Property<net.minecraft.world.level.block.state.properties.DoubleBlockHalf>) p;
                if (state.m_61143_(halfProp) != net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) {
                    return;
                }
                BlockPos above = target.m_7918_(0, 1, 0);
                if (level.m_8055_(above).m_60795_()) {
                    level.m_7731_(above, state.m_61124_(halfProp, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 3);
                }
            } catch (Exception ignored) {
            }
            return;
        }
    }
}
