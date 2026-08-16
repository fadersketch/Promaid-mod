package com.maidsmart.fishing;

import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityChair;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v1.5.252r：钓鱼自动坐垫【普通服务类】——全部逻辑从 FishingAutoChairMixin 迁出。
 *
 * 迁移原因（v1.5.252q 崩溃实证）：mixin 类被 mixin 插件特殊处理，普通代码
 * （ProMaidExtension.onServerTick）直接调用 mixin 静态方法时，JVM 判定该 mixin
 * 类 "is invalid" → NoClassDefFoundError → 进世界第一 tick 即崩。mixin 本身用
 * ASM 应用不受影响，但【普通代码永远不能直接引用 mixin 类】——逻辑放这里，
 * mixin 只留注入壳，ProMaidExtension/ChairNoDropMixin 都调本类。
 *
 * 功能（v1.5.252q 方案）：
 * 1. 原逻辑已找到椅子/船 → 不动，走原模组钓鱼流程；
 * 2. 没有 → 实体直接扫描 8 格内空坐垫/船（绕过传感器可见性）→ 走过去/贴脸直接上骑；
 * 3. 还是没有 → 在离女仆最近的可钓鱼水域岸边生成【带标记】TLM 坐垫（持久 NBT
 *    标记 + 女仆 UUID），startRiding(force) 直接坐上 → TLM 骑乘脑自动找水抛竿；
 * 4. 清理（服务端每秒 sweep）：任务解除（≠钓鱼）/ 脱离坐垫超 2 秒 / 女仆不在 → 删；
 * 5. 标记坐垫被摧毁（玩家拳打/创造击碎）→ 只移除实体无掉落（ChairNoDropMixin 调用
 *    isAutoChair 判定）；
 * 6. 原 no_sit 气泡在"马上要生成坐垫"时压掉，不再两句打架。
 */
public final class FishingChairService {
    /** 标记坐垫的持久 NBT 键 */
    public static final String TAG_AUTO = "promaid_auto_chair";
    public static final String TAG_MAID = "promaid_maid_uuid";
    /** TLM 钓鱼任务 UID 的 path 段（m_135815_ = getPath，javap 实证） */
    private static final String FISHING_UID = "fishing";

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(FishingChairService.class);

    /** 生成坐垫冷却（tick，按女仆 UUID）：防"生成→坐不上→再生成"循环 */
    private static final Map<String, Long> LAST_SPAWN = new HashMap<>();
    /** 找不到水域的限频警告（tick，按女仆 UUID，30 秒一条；大范围扫描同频） */
    private static final Map<String, Long> LAST_NO_WATER_WARN = new HashMap<>();
    /** 脱离坐垫计时：坐垫实体 id → 开始脱离的 tick（内存态，仅服务端） */
    private static final Map<Integer, Long> VACATED = new HashMap<>();
    /** v1.5.255：远处水域自动走向——女仆 uuid → [坐垫实体 id, 生成 tick]（sweep 推进） */
    private static final Map<String, long[]> PENDING = new HashMap<>();
    /** v1.5.269：找椅宽限计时——女仆 uuid → 开始找椅 tick。生成坐垫前先给足
     *  7 秒（140 tick）找椅时间（TLM 原版 FindSit 的可见实体内存/传感器刷新窗口）；
     *  7 秒后原版仍没找到椅子才生成坐垫。 */
    private static final Map<String, Long> FIND_START = new HashMap<>();
    /** v1.5.275：原版 FindSit 找到的椅子目标（女仆 → 目标位置）——高频维持走位用。
     *  根因（用户实测 274 后仍"一步一停"）：FindSit 每 12 tick 触发才设 walk target，
     *  TLM 女仆的站立等行为可能清 WALK_TARGET → 每 12 tick（0.6 秒）只走一小段。
     *  tickKeepSeatWalk 每 3 tick 补寻路（目标不变时寻路器缓存路径），WALK_TARGET
     *  被清后 3 tick 内补回 → 连续走（接近空闲状态）。 */
    private static final Map<String, BlockPos> SEAT_TARGET = new HashMap<>();

    /** mixin 调用：记录原版 FindSit 找到的椅子目标 */
    public static void recordSeatTarget(EntityMaid maid, BlockPos pos) {
        SEAT_TARGET.put(maid.m_20148_().toString(), pos.m_7949_());
    }

    /** 每 3 tick 调用（ProMaidExtension）：钓鱼任务 + 未骑乘 + 有椅子目标 + 无 walk
     *  target → 补寻路（绕过 FindSit 12 tick 间隙，防"一步一停"） */
    public static void tickKeepSeatWalk(EntityMaid maid) {
        if (maid.m_20202_() != null) {
            SEAT_TARGET.remove(maid.m_20148_().toString()); // 已骑上 → 清目标
            return;
        }
        if (maid.getTask() == null
                || !FISHING_UID.equals(maid.getTask().getUid().m_135815_())) {
            SEAT_TARGET.remove(maid.m_20148_().toString()); // 任务解除 → 清目标
            return;
        }
        BlockPos target = SEAT_TARGET.get(maid.m_20148_().toString());
        if (target == null) {
            return;
        }
        if (maid.m_6274_().m_21952_(
                net.minecraft.world.entity.ai.memory.MemoryModuleType.f_26370_).isEmpty()) {
            net.minecraft.world.entity.ai.behavior.BehaviorUtils.m_22617_(maid, target, 0.6f, 0);
        }
    }

    private FishingChairService() {
    }

    /** 原 no_sit 气泡是否应被压掉（马上要生成坐垫时）——mixin lambda$start$2 用 */
    public static boolean shouldSuppressNoSit(EntityMaid maid) {
        if (!com.maidsmart.config.MaidSmartConfig.MISC_PRODUCE_TASK_ENHANCE.get()) {
            return false;
        }
        if (!(maid.m_9236_() instanceof ServerLevel level)) {
            return false;
        }
        return findWaterSpot(level, maid) != null
                || PENDING.containsKey(maid.m_20148_().toString());
    }

    /** start TAIL 主逻辑：sitEntity 为原逻辑找到的椅子/船（非 null 走原流程） */
    public static void tryAutoChair(ServerLevel world, EntityMaid maid, Entity sitEntity) {
        if (!com.maidsmart.config.MaidSmartConfig.MISC_PRODUCE_TASK_ENHANCE.get()) {
            return;
        }
        if (sitEntity != null) {
            return; // 原逻辑已找到椅子/船——走原模组钓鱼流程
        }
        String key = maid.m_20148_().toString();
        long now = world.m_46467_();
        Long last = LAST_SPAWN.get(key);
        if (last != null && now - last < 100L) {
            return; // 5 秒冷却
        }
        // v1.5.273：已在走向远水坐垫（PENDING）→ 直接跳过（sweep 推进，不打断寻路）
        if (PENDING.containsKey(key)) {
            return;
        }
        // v1.5.274：找椅子完全套用原版——TLM 原版 FindSit 已经用可见实体内存
        // （NEAREST_VISIBLE_LIVING_ENTITIES）找椅子并设置 walk target，原版找到
        // 时 sitEntity != null（上面已 return，走原版流畅流程）。这里只处理"原版
        // 没找到椅子"（no_sit 分支替换为生成坐垫）——【不再自己扫世界找椅子】：
        // 旧版 findNearbySeat（16/32 格实体扫描）与原版可见内存产生分歧——原版
        // 找不到但 promaid 扫到 → promaid 接管走向（每 12 tick 重设 walk target
        // → 一步一停，日志实证 25 格走 18 秒）。套用原版后：可见空椅子原版必坐
        // （promaid 零干预）；原版找不到（不可见/被占）→ 7 秒宽限后生成坐垫。
        // 7 秒宽限（传感器/实体列表刷新窗口，原版多轮 FindSit 触发内可能找到）
        Long fs = FIND_START.get(key);
        if (fs == null) {
            FIND_START.put(key, now);
            return; // 开始计时，本轮不生成（等原版再找几轮）
        }
        if (now - fs < 140L) {
            return; // 7 秒内原版没找到 → 再等等（FindSit 每 12 tick 重触发）
        }
        FIND_START.remove(key); // 7 秒到了 → 生成坐垫
        // 2) 无坐垫：在最近的可钓鱼水域岸边生成【带标记】坐垫 + 直接坐上去
        BlockPos stand = findWaterSpot(world, maid);
        boolean far = false;
        if (stand == null) {
            // v1.5.255：12 格内没有 → 大范围扫描最近水域（限频 30 秒——大扫描
            // 是主线程逐格检查，不能高频跑）。找到则生成坐垫并自动走向岸边
            //（用户实测：女仆在地下空洞 12 格内无水 → 一直不放坐垫）
            // v1.5.256：半径 40 → 64、垂直 -32..+64（用户测试：水在 46 格外、
            // 高 46 层差——40/±40 覆盖不到 → 依然无反应）
            Long w = LAST_NO_WATER_WARN.get(key);
            if (w != null && now - w < 600L) {
                return;
            }
            LAST_NO_WATER_WARN.put(key, now);
            // v1.5.257：判定链路统计（失败日志显示卡在哪一步：区块未加载/无液体/
            // 上方被占/岸边不合法）
            int[] stats = new int[3];
            stand = findWaterSpotFar(world, maid, stats);
            if (stand == null) {
                // v1.5.258：stats[2] 是"findBank 岸边判定失败数"（全部失败才走到这）
                LOGGER.info("auto-chair: 女仆 {} 位置 ({}, {}, {}) 附近 64 格内未找到可钓鱼水域岸边"
                                + "（液体 {} 格 / 上方空气 {} 格 / 岸边不合法 {} 处）",
                        maid.m_5446_().getString(), maid.m_20185_(), maid.m_20186_(), maid.m_20187_(),
                        stats[0], stats[1], stats[2]);
                // v1.5.256：游戏内提示（旧版只有日志——玩家体感"毫无反应"）
                maid.getChatBubbleManager().addTextChatBubble("附近没找到可钓鱼的水，我找不到地方坐下钓鱼～");
                return; // 保留原 no_sit 气泡（未压制时）
            }
            far = true;
        }
        LAST_SPAWN.put(key, now);
        removeOldChairs(world, maid); // 先清掉这只女仆的旧标记坐垫（防岸边堆积）
        EntityChair chair = spawnChair(world, maid, stand);
        if (chair == null) {
            return;
        }
        if (!far) {
            // 近处：直接坐上
            boolean mounted = maid.m_7998_(chair, true);
            // v1.5.252v：诊断——坐垫下方位块注册名（确认贴地；用户实测"坐垫在水上"）
            BlockPos under = stand.m_7918_(0, -1, 0);
            net.minecraft.resources.ResourceLocation bid = net.minecraftforge.registries.ForgeRegistries.BLOCKS
                    .getKey(world.m_8055_(under).m_60734_());
            LOGGER.info("auto-chair: 女仆 {} 在 ({}, {}, {}) 生成标记坐垫{}（下方位块 {}）",
                    maid.m_5446_().getString(), stand.m_123341_(), stand.m_123342_(), stand.m_123343_(),
                    mounted ? "并坐上" : "（上骑失败）", bid == null ? "?" : bid);
            maid.getChatBubbleManager().addTextChatBubble("这里没有椅子，我自己带了个坐垫，找个水边坐下钓鱼～");
        } else {
            // 远处：记录 PENDING，sweep 每秒推进走向/上骑
            PENDING.put(key, new long[]{chair.m_19879_(), now});
            double dist = Math.sqrt(maid.m_20238_(chair.m_20182_()));
            net.minecraft.world.entity.ai.behavior.BehaviorUtils.m_22617_(maid, chair.m_20183_(), 0.6f, 0);
            LOGGER.info("auto-chair: 女仆 {} 附近 12 格无水域，找到 ({}, {}, {}) 处岸边生成坐垫并走向（距离 {} 格）",
                    maid.m_5446_().getString(), stand.m_123341_(), stand.m_123342_(), stand.m_123343_(),
                    (int) dist);
            maid.getChatBubbleManager().addTextChatBubble("这里没有水，我找个水边坐下钓鱼～");
        }
    }

    /** 生成带标记的坐垫（世界生成成功返回实体；失败返回 null） */
    private static EntityChair spawnChair(ServerLevel world, EntityMaid maid, BlockPos stand) {
        EntityChair chair = new EntityChair(world,
                stand.m_123341_() + 0.5, stand.m_123342_(), stand.m_123343_() + 0.5, 0.0f);
        CompoundTag tag = chair.getPersistentData();
        tag.m_128359_(TAG_AUTO, "1");
        tag.m_128359_(TAG_MAID, maid.m_20148_().toString());
        return world.m_7967_(chair) ? chair : null;
    }

    /** 找"离女仆最近的可钓鱼水域岸边"：水面格 + 上方空气，岸格空气 + 脚下实心 + 头顶空气
     *  v1.5.259：水面判定修复——m_60815_ 是 isSolid（有碰撞）不是 isLiquid！
     *  旧版 `!m_8055_(w).m_60815_()` 把"实心方块"当"水面"（水 isSolid=false 被跳过）
     *  → 永远找不到水（252s 重写起，用户实测"草地上挖一格水识别不到"、日志
     *  "液体 75958 格"= 地形实心格数）。改用 TLM 同款判定：
     *  getFluidState().is(FluidTags.WATER)（水源/流动水都算）。 */
    private static BlockPos findWaterSpot(ServerLevel level, EntityMaid maid) {
        BlockPos c = maid.m_20183_();
        for (int ring = 1; ring <= 12; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue; // 只扫当前距离环（最近优先）
                    }
                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos w = c.m_7918_(dx, dy, dz);
                        if (!level.m_6425_(w).m_205070_(net.minecraft.tags.FluidTags.f_13131_)) {
                            continue; // 水面格（流体是水）
                        }
                        if (!level.m_8055_(w.m_7918_(0, 1, 0)).m_60795_()) {
                            continue; // 鱼钩落点上方需空气
                        }
                        BlockPos stand = findBank(level, w);
                        if (stand != null) {
                            return stand;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static final int[][] DIRS4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * v1.5.255/256：大范围找水——v1.5.258 性能重构（256 实测卡死：单次
     * 160 万次逐格检查 + 30 秒一次 → 服务端主线程冻结 7 秒，日志实证
     * "Can't keep up! Running 7404ms"）。
     * 多阶段递减（水面越近扫描越少）：
     *  A1 同层 dy=0（半径 64，~1.6 万次 ≈ 5ms）——草地水池/同层水立即命中；
     *  A2 贴近 ±3（~11 万次 ≈ 35ms）；
     *  B  全垂直 -16..+32 半径 40（~29 万次 ≈ 100ms，30 秒限频）。
     * 统计：stats[0]=液体格 / stats[1]=通过上方空气 / stats[2]=合法岸边
     *（诊断：失败日志显示判定卡在哪一步）。
     */
    private static BlockPos findWaterSpotFar(ServerLevel level, EntityMaid maid, int[] stats) {
        BlockPos c = maid.m_20183_();
        BlockPos hit = scanWaterRings(level, c, 13, 64, 0, 0, stats);
        if (hit != null) {
            return hit;
        }
        hit = scanWaterRings(level, c, 13, 64, -3, 3, stats);
        if (hit != null) {
            return hit;
        }
        return scanWaterRings(level, c, 13, 40, -16, 32, stats);
    }

    /** 距离环扫描（水面格 + 上方空气 + findBank 岸边；stats 统计） */
    private static BlockPos scanWaterRings(ServerLevel level, BlockPos c,
                                           int ringMin, int ringMax, int dyMin, int dyMax, int[] stats) {
        for (int ring = ringMin; ring <= ringMax; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue; // 只扫当前距离环（最近优先）
                    }
                    for (int dy = dyMin; dy <= dyMax; dy++) {
                        BlockPos w = c.m_7918_(dx, dy, dz);
                        // v1.5.259：水面判定同 findWaterSpot（m_6425_ + FluidTags.WATER）
                        if (!level.m_6425_(w).m_205070_(net.minecraft.tags.FluidTags.f_13131_)) {
                            continue; // 水面格（流体是水）
                        }
                        stats[0]++;
                        if (!level.m_8055_(w.m_7918_(0, 1, 0)).m_60795_()) {
                            continue; // 鱼钩落点上方需空气
                        }
                        stats[1]++;
                        BlockPos stand = findBank(level, w);
                        if (stand != null) {
                            return stand;
                        }
                        stats[2]++;
                    }
                }
            }
        }
        return null;
    }

    /** 水面格四周找可站岸边：找该方向岸边柱的【最上面实心方块】，坐垫放在它上面
     *  （贴地不悬浮）。旧版只查"水面+1/+2 层"——水面与地面同高（1 格深水塘）时
     *  正确的岸（水面层空气格）永远查不到 → 只能退回 +2 层 → 坐垫悬浮在
     *  水塘上方（用户实测："把坐垫放进水里"）。
     *  v1.5.252v：实心判定改用【碰撞箱】——花/草/火把等无碰撞箱的装饰方块
     *  不再被误当"地面"（旧 isAir/isLiquid 判定会把它们当地面 → 坐垫"浮"在草上） */
    private static BlockPos findBank(ServerLevel level, BlockPos w) {
        int wx = w.m_123341_();
        int wz = w.m_123343_();
        for (int[] d : DIRS4) {
            int bx = wx + d[0];
            int bz = wz + d[1];
            for (int y = w.m_123342_() + 1; y >= w.m_123342_() - 3; y--) {
                BlockPos solid = new BlockPos(bx, y, bz);
                BlockState st = level.m_8055_(solid);
                // v1.5.259：m_60815_ 是 isSolid（有碰撞）不是 isLiquid！旧版用它
                // 跳"液体"→ 实心岸（草地/石头 isSolid=true）全被跳过 → findBank
                // 永远 null（用户实测：水就在女仆脚下，344 格水面 32 处全失败）。
                // isSolidRender（m_60804_）对液体/空气/花等已返回 false，直接用它
                if (!st.m_60804_(level, solid)) {
                    continue;
                }
                BlockPos stand = new BlockPos(bx, y + 1, bz);
                if (level.m_8055_(stand).m_60795_()
                        && level.m_8055_(stand.m_7918_(0, 1, 0)).m_60795_()) {
                    return stand; // 站格空气 + 头顶空气 → 坐垫贴地
                }
                break; // 该列已到最上面实心但站格被占 → 换方向
            }
        }
        return null;
    }

    /** 删除该女仆名下所有旧标记坐垫（生成新坐垫前调用，防堆积） */
    private static void removeOldChairs(ServerLevel level, EntityMaid maid) {
        String uid = maid.m_20148_().toString();
        for (Entity e : level.m_8583_()) {
            if (e instanceof EntityChair c && isAutoChair(c)
                    && uid.equals(c.getPersistentData().m_128461_(TAG_MAID))) {
                if (!c.m_20197_().isEmpty()) {
                    continue; // v1.5.267：正被骑着 → 不删（防误删导致女仆掉下来循环）
                }
                c.m_146870_();
                VACATED.remove(c.m_19879_());
            }
        }
    }

    /**
     * 服务端每秒清扫（ProMaidExtension 调用）：
     * - 任务解除（女仆当前任务 ≠ 钓鱼）或女仆不在 → 删除坐垫；
     * - 女仆脱离坐垫超 2 秒（40 tick）→ 删除坐垫；
     * - 正常骑着钓鱼 → 保留。
     */
    public static void sweep(MinecraftServer server) {
        try {
            // 全维度收集存活女仆（跨维度判定任务）
            Map<String, EntityMaid> allMaids = new HashMap<>();
            for (ServerLevel level : server.m_129785_()) {
                for (Entity e : level.m_8583_()) {
                    if (e instanceof EntityMaid m && m.m_6084_()) {
                        allMaids.put(m.m_20148_().toString(), m);
                    }
                }
            }
            for (ServerLevel level : server.m_129785_()) {
                List<EntityChair> chairs = new ArrayList<>();
                for (Entity e : level.m_8583_()) {
                    if (e instanceof EntityChair c && isAutoChair(c)) {
                        chairs.add(c);
                    }
                }
                if (!chairs.isEmpty()) {
                    long now = level.m_46467_();
                    Set<Integer> seen = new HashSet<>();
                    for (EntityChair c : chairs) {
                        seen.add(c.m_19879_());
                        EntityMaid m = allMaids.get(c.getPersistentData().m_128461_(TAG_MAID));
                        boolean fishing = m != null && m.getTask() != null
                                && FISHING_UID.equals(m.getTask().getUid().m_135815_());
                        boolean ridden = !c.m_20197_().isEmpty();
                        if (m == null || !fishing) {
                            // v1.5.267 诊断：删除原因打日志（定位"生成坐垫后被删循环"）
                            LOGGER.info("auto-chair sweep: 删除坐垫 id={} 位置({},{},{}) 原因={}",
                                    c.m_19879_(), (int) c.m_20185_(), (int) c.m_20186_(), (int) c.m_20187_(),
                                    m == null ? "女仆不在/已消失"
                                            : "任务非钓鱼(" + (m.getTask() == null ? "null"
                                            : m.getTask().getUid().m_135815_()) + ")");
                            c.m_146870_(); // 任务解除/女仆不在 → 删
                            VACATED.remove(c.m_19879_());
                        } else if (ridden) {
                            VACATED.remove(c.m_19879_()); // 正常钓鱼中
                        } else {
                            Long v = VACATED.get(c.m_19879_());
                            if (v == null) {
                                VACATED.put(c.m_19879_(), now); // 刚脱离，开始计时
                                LOGGER.info("auto-chair sweep: 女仆 {} 脱离坐垫 id={} 开始 2 秒计时",
                                        m.m_5446_().getString(), c.m_19879_());
                            } else if (now - v > 40L) {
                                LOGGER.info("auto-chair sweep: 删除坐垫 id={} 原因=女仆脱离超 2 秒（{} 秒）",
                                        c.m_19879_(), (now - v) / 20);
                                c.m_146870_(); // 脱离超过 2 秒 → 删
                                VACATED.remove(c.m_19879_());
                            }
                        }
                    }
                    VACATED.keySet().removeIf(k -> !seen.contains(k));
                }
                // v1.5.255：远处水域自动走向——推进 PENDING（到达上骑 / 超时删垫放弃）
                if (!PENDING.isEmpty()) {
                    long now = level.m_46467_();
                    java.util.Iterator<Map.Entry<String, long[]>> pit = PENDING.entrySet().iterator();
                    while (pit.hasNext()) {
                        Map.Entry<String, long[]> en = pit.next();
                        String uid = en.getKey();
                        long[] v = en.getValue();
                        EntityMaid m = allMaids.get(uid);
                        if (m == null || !m.m_6084_() || m.m_9236_() != level) {
                            continue; // 不在本维度/已消失（尾部统一清理）
                        }
                        boolean fishing = m.getTask() != null
                                && FISHING_UID.equals(m.getTask().getUid().m_135815_());
                        if (!fishing) {
                            pit.remove(); // 任务解除 → 坐垫由上方清理逻辑删除
                            continue;
                        }
                        Entity ch = level.m_6815_((int) v[0]);
                        if (!(ch instanceof EntityChair c) || !c.m_6084_() || !isAutoChair(c)) {
                            pit.remove(); // 坐垫没了 → 下次 tryAutoChair 重新生成
                            continue;
                        }
                        if (!c.m_20197_().isEmpty()) {
                            pit.remove(); // 已坐上（原逻辑兜底上骑）
                            continue;
                        }
                        double distSq = m.m_20238_(c.m_20182_());
                        if (distSq <= 6.25) {
                            // 到达岸边 2.5 格内 → 上骑
                            if (m.m_7998_(c, true)) {
                                pit.remove();
                                LOGGER.info("auto-chair: 女仆 {} 走到水边坐上坐垫", m.m_5446_().getString());
                            }
                    } else if (now - v[1] > 1200L) {
                        pit.remove();
                        c.m_146870_(); // 60 秒没走到（可能被卡住）→ 放弃，下次重试
                        VACATED.remove(c.m_19879_());
                        LOGGER.info("auto-chair: 女仆 {} 60 秒未到达水边，删除坐垫", m.m_5446_().getString());
                        // v1.5.256：走不到时提示（旧版静默删垫——玩家体感"无反应"）
                        m.getChatBubbleManager().addTextChatBubble("去水边的路走不通，我放弃啦～");
                    } else {
                            // v1.5.272：只在没有 walk target 时补寻路——旧版每秒无条件
                            // 重设（与 FindSit 每 12 tick 的设置交替重置）→ 寻路频繁
                            // 重算 → 女仆"一走一卡"（用户实测）。FindSit 已设目标时
                            // 不打扰（MoveToTargetSink 持续走，流畅）；目标被清才补。
                            if (m.m_6274_().m_21952_(
                                    net.minecraft.world.entity.ai.memory.MemoryModuleType.f_26370_).isEmpty()) {
                                net.minecraft.world.entity.ai.behavior.BehaviorUtils.m_22617_(
                                        m, c.m_20183_(), 0.6f, 0);
                            }
                        }
                    }
                }
                // 冷却/限频表随女仆清理（防长时运行内存泄漏）
                LAST_SPAWN.keySet().removeIf(k -> !allMaids.containsKey(k));
                LAST_NO_WATER_WARN.keySet().removeIf(k -> !allMaids.containsKey(k));
                PENDING.keySet().removeIf(k -> !allMaids.containsKey(k));
                FIND_START.keySet().removeIf(k -> !allMaids.containsKey(k));
                SEAT_TARGET.keySet().removeIf(k -> !allMaids.containsKey(k)); // v1.5.275
            }
        } catch (Exception ignored) {
            // 清扫失败不影响游戏
        }
    }

    /** 是否 promaid 自动生成的标记坐垫（ChairNoDropMixin 共用：摧毁无掉落） */
    public static boolean isAutoChair(Entity e) {
        return e instanceof EntityChair c && "1".equals(c.getPersistentData().m_128461_(TAG_AUTO));
    }
}
