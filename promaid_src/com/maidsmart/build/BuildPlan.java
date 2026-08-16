package com.maidsmart.build;

import com.github.tartaricacid.touhoulittlemaid.api.entity.data.TaskDataKey;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 建造计划：格式 ["O,x,y,z,name,id", "x,y,z,blockid", ...] —— 第一项为原点（绝对坐标）
 * +蓝图名+蓝图 id，其余为相对原点的步骤。
 *
 * v1.5.180：多区块共存重构——单计划（每维度一份 GLOBAL_PLAN）升级为
 * 【多区块共存 + 女仆-区块显式绑定】：
 * - PlanState：一个建造区块 = 一份计划（planId 全局唯一，跨维度）
 * - 存储全部按 planId 键控（PLANS / 进度 / 包围盒 / 强制加载 / 游标节流）
 * - 女仆-区块绑定：MAID_PLAN（内存）+ persistentData（maid_smart_bound_plan
 *   持久化，重启恢复）；只有绑定该区块的女仆参与建造
 * - 创建区块不依赖女仆在场（BlueprintBuildExecutor 直接以玩家脚下为原点创建），
 *   唯一硬性要求：不与已有区块重叠（findOverlap）
 * - 存档：BuildArchive 存 SavedPlan 列表（每维度多区块），旧单计划档自动迁移
 *
 * CODEC 注意：TLM 的 TaskData 同步要求编码结果为 CompoundTag（对象），直接用
 * Codec.list 会编码成 ListTag → 每 tick 同步 ClassCastException 崩溃。因此用
 * RecordCodecBuilder 把列表包装成 {"steps": [...]} 对象（v1.5.3 修复）。
 */
public final class BuildPlan {
    public static final ResourceLocation KEY_ID = ResourceLocation.parse("maid_smart:build_plan");
    public static final Codec<List<String>> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.list(Codec.STRING).fieldOf("steps").forGetter(list -> list)
    ).apply(instance, list -> list));
    /** v1.5.180：TaskData 引用行机制已废弃（v2.0 起 getPlan 不读）——KEY 保留注册兼容 */
    public static TaskDataKey<List<String>> KEY = null;

    /** v1.5.180：女仆-区块绑定持久化键（persistentData） */
    private static final String BOUND_PLAN_TAG = "maid_smart_bound_plan";

    /**
     * v1.5.180：建造区块 = 一份计划。planId 全局唯一（跨维度），重启后由存档
     * 恢复同一 planId（女仆绑定持久化引用它，身份稳定）。
     */
    public static final class PlanState {
        public final String planId;
        public final ResourceKey<Level> dim;
        public final BlockPos origin;
        public final String name;
        public final String blueprintId;
        /** 居中后的步骤（不含原点行） */
        public final List<String> steps;
        /** 暂停（持久化在存档，重启保持） */
        public boolean paused = false;
        /** 工头女仆 UUID（空 = 未设） */
        public String foremanUuid = "";
        /** 存档游标（persistCursor 写入；重启恢复进度用） */
        public int savedCursor = 1;

        /** v1.5.287：toPlan 惰性缓存——steps/origin/name/planId 建后不可变 → 拼一次
         *  复用。旧版每次 toPlan 都 new ArrayList + 拷贝全部步骤（tick 热路径每 tick
         *  调用：55 万步 × 多女仆 = 每 tick 上千万次引用拷贝 + GC 压力） */
        private List<String> cachedPlan = null;

        PlanState(String planId, ResourceKey<Level> dim, BlockPos origin, String name,
                  String blueprintId, List<String> steps) {
            this.planId = planId;
            this.dim = dim;
            this.origin = origin;
            this.name = name;
            this.blueprintId = blueprintId;
            this.steps = steps;
        }

        /** 组装传统计划格式（原点行 + 步骤）——兼容现有解析 API（getOrigin/planName/planId） */
        public List<String> toPlan() {
            if (this.cachedPlan == null) {
                List<String> plan = new ArrayList<>(steps.size() + 1);
                plan.add("O," + origin.m_123341_() + "," + origin.m_123342_() + "," + origin.m_123343_()
                        + "," + name + "," + planId);
                plan.addAll(steps);
                this.cachedPlan = plan;
            }
            return this.cachedPlan;
        }
    }

    /**
     * v1.5.40：全局建造进度（v1.5.180：按 planId 键控，每区块独立）。
     * - 游标 cursor：该下标之前的步骤均已处理（已建好，或已记入延后集）
     * - 延后集 deferred：因缺料/障碍/区块未加载而暂缓的步骤下标，条件恢复后自动重试
     * 仅内存态（重启后游标从存档恢复，首次扫描一次性快进已建部分，代价可接受）
     */
    public static final class Progress {
        final String tag; // 计划身份（planId）
        public int cursor = 1;
        /** v1.5.46：延后步骤 {下标 → 连续失败次数}（插入顺序≈计划顺序，轮询从最早开始）。
         *  连续失败 ≥3 次视为"蓝图本身悬空" → 永久跳过，避免建造永不完成。 */
        public final java.util.LinkedHashMap<Integer, Integer> deferred = new java.util.LinkedHashMap<>();
        /** v1.5.46：被永久跳过的悬空步骤数（完成时气泡报告） */
        public int skipped = 0;
        /** v1.5.62：真实放置数（进度显示用——游标是扫描位置会虚高，不是已建数） */
        public int placedCount = 0;
        /** v1.5.82：已放置位置集合（相对坐标 key）——补建/覆盖重复放置同一位置
         *  不再重复计数（修复进度出现 150% 等超 100% 的重复计算） */
        public final java.util.Set<Long> placedSet = new java.util.HashSet<>();
        /** v1.5.66：已判定永久跳过的步骤下标（悬空/障碍/无物品/区块未加载）——
         *  完成时缺口检查不再重复尝试（防补建死循环） */
        public final java.util.Set<Integer> skippedIdx = new java.util.HashSet<>();
        /** v1.5.276：替代品验收表 {步骤下标 → 实际放置的替代方块注册名}——缺料替换
         *  放置成功后记录；主循环/延后轮询/缺口扫描据此把"目标格=该替代品"视为已建，
         *  不再拆掉重放（拆→掉→捡回→再放→再拆 = 背包材料"翻倍"观感，回收掉落物
         *  只治标）。仅内存态（重启后首次扫描重建一次替代品，一次性成本可接受）。 */
        public final java.util.Map<Integer, String> altUsed = new java.util.HashMap<>();
        /** v1.5.287：延后条目退避表 {步骤下标 → 下次允许重试的 gameTime}——缺料/
         *  未加载/放置失败时记录，轮询在退避期内跳过该条目（旧版缺料时每 tick 反复
         *  扫空背包；补料/障碍移除后最多 2 秒内续建，不影响节奏） */
        public transient java.util.Map<Integer, Long> deferredRetryAt = new java.util.HashMap<>();

        /** v1.5.51：蓝图步骤位置集合（懒构建，O(N) 一次性；用于补支撑时判断
         *  "支撑格是否蓝图内位置"——蓝图有步骤的支撑格不补，等支撑步骤先建） */
        public transient java.util.Set<Long> plannedPositions = null;
        /** v1.5.114：计划级方块注册表缓存（blockId → Block）——主循环/延后轮询
         *  每步都 ForgeRegistries.BLOCKS.getValue(parse(id))，同种方块大量重复，
         *  缓存后每 tick 少几百次注册表查询（内存态，无持久化需求） */
        public transient java.util.Map<String, net.minecraft.world.level.block.Block> blockCache =
                new java.util.HashMap<>();

        public java.util.Set<Long> plannedPositions(List<String> plan) {
            if (plannedPositions == null) {
                java.util.Set<Long> s = new java.util.HashSet<>();
                for (int i = 1; i < plan.size(); i++) {
                    String[] parts = BlueprintLib.parseStep(plan.get(i));
                    if (parts == null) {
                        continue;
                    }
                    try {
                        s.add((long) (Integer.parseInt(parts[0]) & 0xFFFFF) << 42
                                | (long) (Integer.parseInt(parts[1]) & 0x1FFFFF) << 21
                                | (Integer.parseInt(parts[2]) & 0x1FFFFF));
                    } catch (NumberFormatException ignored) {
                    }
                }
                plannedPositions = s;
            }
            return plannedPositions;
        }

        Progress(String tag) {
            this.tag = tag;
        }
    }

    /** v1.5.180：全部区块计划（planId → PlanState，跨维度） */
    private static final Map<String, PlanState> PLANS = new HashMap<>();
    /** v1.5.180：进度按 planId */
    private static final Map<String, Progress> GLOBAL_PROGRESS = new HashMap<>();
    /** v1.5.180：工头按 planId */
    private static final Map<String, String> FOREMAN = new HashMap<>();
    /** v1.5.41：建造区强制加载包围盒/票证标记按 planId */
    private static final Map<String, int[]> GLOBAL_BOX = new HashMap<>();
    private static final Map<String, Boolean> GLOBAL_TICKETED = new HashMap<>();
    /** v1.5.43：游标持久化节流（planId → gameTime） */
    private static final Map<String, Long> CURSOR_SAVE_TIME = new HashMap<>();
    /** v1.5.180：女仆-区块绑定（会话内存态 + persistentData 持久化双写） */
    private static final Map<java.util.UUID, String> MAID_PLAN = new java.util.concurrent.ConcurrentHashMap<>();

    private BuildPlan() {
    }

    // ==================== 区块计划查询 ====================

    /** v1.5.180：该维度全部区块计划（先惰性恢复存档） */
    public static List<PlanState> getPlans(net.minecraft.server.level.ServerLevel level) {
        restoreAll(level);
        List<PlanState> out = new ArrayList<>();
        ResourceKey<Level> dim = level.m_46472_();
        for (PlanState ps : PLANS.values()) {
            if (ps.dim.equals(dim)) {
                out.add(ps);
            }
        }
        return out;
    }

    /** v1.5.180：按 planId 取计划（全局；不存在返回 null） */
    public static PlanState getPlanById(String planId) {
        return planId == null ? null : PLANS.get(planId);
    }

    /** v1.5.252j：全部区块快照（建造 HUD 广播用） */
    public static java.util.List<PlanState> allPlansSnapshot() {
        return new java.util.ArrayList<>(PLANS.values());
    }

    /** v1.5.180：女仆绑定的区块 id（无绑定/计划已删 → null） */
    public static String getBoundPlanId(EntityMaid maid) {
        String pid = MAID_PLAN.get(maid.m_20148_());
        if (pid != null && PLANS.containsKey(pid)) {
            return pid;
        }
        // 重启恢复：persistentData 持久化绑定（MAID_PLAN 内存态重启清空）
        if (maid.getPersistentData().m_128425_(BOUND_PLAN_TAG, 8)) {
            String saved = maid.getPersistentData().m_128461_(BOUND_PLAN_TAG);
            if (!saved.isEmpty() && PLANS.containsKey(saved)) {
                return saved;
            }
        }
        return null;
    }

    /** v1.5.180：女仆绑定计划（传统 List 格式；无绑定返回空列表） */
    public static List<String> getBoundPlan(EntityMaid maid) {
        PlanState ps = getBoundPlanState(maid);
        return ps == null ? new ArrayList<>() : ps.toPlan();
    }

    /** v1.5.180：女仆绑定计划状态（无绑定返回 null） */
    public static PlanState getBoundPlanState(EntityMaid maid) {
        String pid = getBoundPlanId(maid);
        return pid == null ? null : PLANS.get(pid);
    }

    /** v1.5.180：女仆绑定计划是否暂停（未绑定 = 不暂停）——mixin 强制 WORK 判定用 */
    public static boolean isBoundPlanPaused(EntityMaid maid) {
        PlanState ps = getBoundPlanState(maid);
        return ps != null && ps.paused;
    }

    // ==================== 创建 / 删除 ====================

    /**
     * v1.5.180：创建区块计划（不依赖女仆在场）。
     * 同蓝图同原点已存在 → 返回已有 planId（续建语义由调用方处理）；否则新建并写存档。
     */
    public static String setPlan(net.minecraft.server.level.ServerLevel level, BlockPos origin,
                                 List<String> steps, String name, String blueprintId) {
        for (PlanState ex : getPlans(level)) {
            if (ex.blueprintId.equals(blueprintId) && ex.origin.equals(origin)) {
                return ex.planId; // 续建：同一区块
            }
        }
        String planId = java.util.UUID.randomUUID().toString();
        PlanState ps = new PlanState(planId, level.m_46472_(), origin, name, blueprintId,
                new ArrayList<>(steps));
        PLANS.put(planId, ps);
        GLOBAL_BOX.remove(planId); // 新计划重新计算包围盒
        // 写存档（多区块列表）
        BuildArchive arch = BuildArchive.get(level);
        BuildArchive.SavedPlan sp = toSavedPlan(ps);
        String foreman = chooseForeman(level, ps);
        ps.foremanUuid = foreman;
        sp.foremanUuid = foreman;
        arch.upsert(sp);
        LOGGER.info("build plan created: id={} name={} origin={},{},{} planId={}",
                blueprintId, name, origin.m_123341_(), origin.m_123342_(), origin.m_123343_(), planId);
        return planId;
    }

    /** v1.5.180：PlanState → 存档条目 */
    private static BuildArchive.SavedPlan toSavedPlan(PlanState ps) {
        BuildArchive.SavedPlan sp = new BuildArchive.SavedPlan();
        sp.planId = ps.planId;
        sp.ox = ps.origin.m_123341_();
        sp.oy = ps.origin.m_123342_();
        sp.oz = ps.origin.m_123343_();
        sp.blueprintId = ps.blueprintId;
        sp.name = ps.name;
        sp.cursor = ps.savedCursor;
        sp.paused = ps.paused;
        sp.foremanUuid = ps.foremanUuid;
        return sp;
    }

    /** v1.5.180：清除单个区块（完成/取消）——释放加载 + 清进度/存档 */
    public static void clear(net.minecraft.server.level.ServerLevel level, String planId) {
        PlanState ps = PLANS.get(planId);
        if (ps == null) {
            return;
        }
        releaseChunks(level, ps);
        GLOBAL_PROGRESS.remove(planId);
        GLOBAL_BOX.remove(planId);
        GLOBAL_TICKETED.remove(planId);
        CURSOR_SAVE_TIME.remove(planId);
        FOREMAN.remove(planId);
        PLANS.remove(planId);
        BuildArchive.get(level).remove(planId);
        LOGGER.info("build plan cleared: planId={} name={}", planId, ps.name);
    }

    /** v1.5.180：取消建造（玩家操作，同 clear）——已建方块保留，重下达自动已建感知续建 */
    public static void cancel(net.minecraft.server.level.ServerLevel level, String planId,
                              net.minecraft.world.entity.player.Player player) {
        clear(level, planId);
    }

    // ==================== 暂停（按区块） ====================

    public static boolean isPaused(PlanState ps) {
        return ps != null && ps.paused;
    }

    /** v1.5.180：翻转区块暂停（写存档持久化） */
    public static boolean togglePause(net.minecraft.server.level.ServerLevel level, PlanState ps) {
        if (ps == null) {
            return false;
        }
        ps.paused = !ps.paused;
        BuildArchive arch = BuildArchive.get(level);
        BuildArchive.SavedPlan sp = arch.find(ps.planId);
        if (sp != null) {
            sp.paused = ps.paused;
            arch.m_77762_();
        }
        return ps.paused;
    }

    // ==================== 女仆-区块绑定 ====================

    /** v1.5.180：绑定女仆到区块（调用方保证女仆与区块同维度）——写内存 + persistentData */
    public static void bindMaid(EntityMaid maid, String planId) {
        if (planId == null) {
            unbindMaid(maid);
            return;
        }
        MAID_PLAN.put(maid.m_20148_(), planId);
        maid.getPersistentData().m_128359_(BOUND_PLAN_TAG, planId);
    }

    /** v1.5.180：解绑女仆（离开建筑状态由调用方切任务） */
    public static void unbindMaid(EntityMaid maid) {
        MAID_PLAN.remove(maid.m_20148_());
        maid.getPersistentData().m_128359_(BOUND_PLAN_TAG, "");
    }

    /** v1.5.180：全员加入——玩家 128 格内所有建筑任务女仆绑定到指定区块 */
    public static int joinAll(net.minecraft.server.level.ServerLevel level,
                              net.minecraft.world.entity.player.Player player, String planId) {
        PlanState ps = PLANS.get(planId);
        if (ps == null || !ps.dim.equals(level.m_46472_())) {
            return 0;
        }
        net.minecraft.world.phys.AABB box = player.m_20191_().m_82400_(128.0);
        int n = 0;
        for (EntityMaid m : level.m_45976_(EntityMaid.class, box)) {
            if (BlueprintBuildExecutor.isBuildingTask(m)) {
                bindMaid(m, planId);
                n++;
            }
        }
        return n;
    }

    // ==================== 进度 ====================

    /** v1.5.180：取区块进度（按 planId 隔离） */
    public static Progress progress(PlanState ps) {
        Progress p = GLOBAL_PROGRESS.get(ps.planId);
        if (p == null || !p.tag.equals(ps.planId)) {
            p = new Progress(ps.planId);
            p.cursor = Math.max(1, ps.savedCursor);
            GLOBAL_PROGRESS.put(ps.planId, p);
        }
        return p;
    }

    /** v1.5.43：放置进度定期写存档（每 200 tick 节流），重启后从游标继续 */
    public static void persistCursor(net.minecraft.server.level.ServerLevel level, PlanState ps, int cursor) {
        long now = level.m_46467_(); // getGameTime
        Long last = CURSOR_SAVE_TIME.get(ps.planId);
        if (last != null && now - last < 200) {
            return; // 10 秒最多写一次
        }
        BuildArchive arch = BuildArchive.get(level);
        BuildArchive.SavedPlan sp = arch.find(ps.planId);
        if (sp == null || sp.cursor == cursor) {
            return;
        }
        sp.cursor = cursor;
        ps.savedCursor = cursor;
        arch.m_77762_();
        CURSOR_SAVE_TIME.put(ps.planId, now);
    }

    /** v1.5.65：进度百分比（0-100；-1 = 无计划）——手册进度条绘制用 */
    public static int progressPct(PlanState ps) {
        List<String> plan = ps.toPlan();
        int total = plan.size() - 1;
        if (total <= 0) {
            return -1;
        }
        Progress p = progress(ps);
        // v1.5.83：不 clamp 到 100——超过时客户端显示 >100% 并染红（超料警示）
        return Math.max(0, p.placedCount * 100 / total);
    }

    // ==================== 状态文本 ====================

    /**
     * v1.5.43：区块建造状态文本。
     * v1.5.179：缺料实时计算 = 总需求 − 已建（区块内匹配方块）− 背包（绑定女仆 + 主人）。
     * v1.5.252u：多行分行显示（\n 分隔）——每行一个信息块、短小独立，
     * 客户端逐行右对齐绘制，不再挤成一长串被截断（根治"字段突出屏幕"）。
     */
    public static String statusText(net.minecraft.server.level.ServerLevel level, PlanState ps,
                                    net.minecraft.world.entity.player.Player owner) {
        if (ps == null) {
            return "当前没有进行中的建造计划。";
        }
        List<String> plan = ps.toPlan();
        // v1.5.252t：蓝图名截断（超长名撑爆字段显示）
        String nm = ps.name == null ? "" : ps.name;
        if (nm.length() > 18) {
            nm = nm.substring(0, 18) + "\u2026";
        }
        StringBuilder line1 = new StringBuilder("\u5efa\u9020\u8fdb\u5ea6\uff1a\u300c").append(nm).append("\u300d");
        StringBuilder line2 = new StringBuilder();
        StringBuilder line3 = new StringBuilder();
        if (plan.size() > 1) {
            Progress prog = progress(ps);
            int done = Math.max(0, prog.placedCount);
            int total = plan.size() - 1;
            line1.append(" \u5df2\u5efa ").append(done).append("/").append(total)
                    .append(" \u5757\uff08").append(total == 0 ? 0 : done * 100 / total).append("%\uff09");
            // 第二行：等待补建 / 缺料
            if (!prog.deferred.isEmpty()) {
                line2.append("\u7b49\u5f85\u8865\u5efa ").append(prog.deferred.size()).append(" \u5757");
            }
            // v1.5.179：实时缺料 = 总需求 − 已建 − 背包
            java.util.Map<String, Integer> shortfall = realShortfall(level, ps.blueprintId, plan, ps.origin, owner);
            if (!shortfall.isEmpty()) {
                if (line2.length() > 0) {
                    line2.append("\uff0c");
                }
                line2.append("\u7f3a\u6599\uff1a");
                int shown = 0;
                for (java.util.Map.Entry<String, Integer> e : shortfall.entrySet()) {
                    if (shown++ >= 3) {
                        line2.append("\u2026");
                        break;
                    }
                    if (shown > 1) {
                        line2.append("\u3001");
                    }
                    line2.append(BlueprintLib.cnName(e.getKey())).append("\u00d7").append(e.getValue());
                }
            }
        } else {
            line1.append("\uff08\u84dd\u56fe\u89e3\u6790\u5931\u8d25\uff09");
        }
        // 第三行：参与女仆 / 状态 / 档位
        line3.append("\u53c2\u4e0e\u5973\u4ec6\uff1a").append(countBuildersNear(level, ps)).append(" \u53ea");
        line3.append(" \u00b7 ").append(ps.paused ? "\u3010\u6682\u505c\u4e2d\u3011" : "\u5efa\u9020\u4e2d");
        line3.append(" \u00b7 \u901f\u5ea6\uff1a").append(MaidBuildBehavior.speedLabel());
        StringBuilder sb = new StringBuilder(line1);
        if (line2.length() > 0) {
            sb.append('\n').append(line2);
        }
        sb.append('\n').append(line3);
        return sb.toString();
    }

    /** v1.5.179：实时材料缺口 = 总需求 − 已建（区块内与蓝图匹配的方块）− 背包
     *  （该维度绑定女仆 + 主人）；材料充足返回空 Map */
    private static java.util.Map<String, Integer> realShortfall(
            net.minecraft.server.level.ServerLevel level, String blueprintId, List<String> plan,
            BlockPos origin, net.minecraft.world.entity.player.Player owner) {
        // v1.5.252p：创造模式材料视为齐——旧版 built + combinedHaveAll(MAX_VALUE)
        // 溢出成负数 → 缺料报告出现 -21 亿/巨量缺料
        if (BlueprintLib.isCreative(owner)) {
            return new java.util.HashMap<>();
        }
        // v1.5.287：走 countNeedsCached（内置蓝图材料需求已缓存——旧版每 2 秒
        // UI 刷新都白遍历全表；blueprintId 为空的外部导入蓝图退回 countNeeds）
        java.util.Map<String, Integer> needed = (blueprintId == null || blueprintId.isEmpty())
                ? BlueprintLib.countNeeds(plan) : BlueprintLib.countNeedsCached(blueprintId, plan);
        if (needed.isEmpty()) {
            return new java.util.HashMap<>();
        }
        java.util.Map<String, Integer> built = origin == null
                ? new java.util.HashMap<>()
                : BlueprintLib.countBuiltMaterials(level, origin, plan);
        java.util.Map<String, Integer> shortfall = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, Integer> e : needed.entrySet()) {
            int have = built.getOrDefault(e.getKey(), 0)
                    + BlueprintLib.combinedHaveAll(level, owner, e.getKey());
            if (have < e.getValue()) {
                shortfall.put(e.getKey(), e.getValue() - have);
            }
        }
        return shortfall;
    }

    /** v1.5.180：参与该区块的绑定女仆数。
     *  v1.5.252n：改为【绑定数】口径——旧版按"原点 ±128 格内"统计：建造是隔空的
     *  （setBlock 不需要女仆在场），远处手动绑定的女仆照样在建造却不计数；
     *  全员加入（玩家在区块内）计数正确、女仆管理手动绑定（女仆在远处）不增长——
     *  用户实测的两入口计数不一致。绑定与区块同维度由各入口保证，跨维度存量安全。 */
    private static int countBuildersNear(net.minecraft.server.level.ServerLevel level, PlanState ps) {
        int n = 0;
        for (java.util.Map.Entry<java.util.UUID, String> e : MAID_PLAN.entrySet()) {
            if (ps.planId.equals(e.getValue())) {
                n++;
            }
        }
        return n;
    }

    // ==================== 工头（按区块） ====================

    /** v1.5.69：随机选一只绑定该区块的活跃建造女仆当工头（无则空串） */
    public static String chooseForeman(net.minecraft.server.level.ServerLevel level, PlanState ps) {
        java.util.List<EntityMaid> list = new java.util.ArrayList<>();
        for (EntityMaid m : scanAreaMaids(level)) {
            if (BlueprintBuildExecutor.isBuildingTask(m) && ps.planId.equals(getBoundPlanId(m))) {
                list.add(m);
            }
        }
        if (list.isEmpty()) {
            return "";
        }
        return list.get(level.f_46441_.m_188503_(list.size())).m_20148_().toString();
    }

    /** v1.5.69：玩家在手册女仆面板手动设定工头（写存档持久化） */
    public static void setForeman(net.minecraft.server.level.ServerLevel level, PlanState ps, String uuid) {
        if (ps == null) {
            return;
        }
        ps.foremanUuid = uuid == null ? "" : uuid;
        FOREMAN.put(ps.planId, ps.foremanUuid);
        BuildArchive arch = BuildArchive.get(level);
        BuildArchive.SavedPlan sp = arch.find(ps.planId);
        if (sp != null) {
            sp.foremanUuid = ps.foremanUuid;
            arch.m_77762_();
        }
    }

    /**
     * v1.5.69：该女仆是否为当前工头（按其绑定区块判断）。
     * v1.5.72/74 语义修正：无工头/工头失效/工头被暂停 → 放行（防全员静默）。
     * v1.5.266：无工头/工头失效 → 【当场随机挑一只顶上并持久化】（用户："不是说
     * 没设置的时候会随机设置一个吗"——v1.5.182 的补选在 start 时机经常失败：
     * 创建区块时女仆还没绑定、远程绑定 scanAreaMaids 扫不到 → foremanUuid 恒空
     * → 全员放行 = "所有人都在发"的根因）。服务端单线程顺序执行无竞态：
     * 第一只调用即设好，后续女仆走正常判断。随机失败（真没人）→ 放行兜底防静默。
     * 注意：这是【行为层放行判断】，UI 工头标记必须用 isExplicitForeman。
     */
    public static boolean isForeman(EntityMaid maid) {
        PlanState ps = getBoundPlanState(maid);
        if (ps == null) {
            return true; // 未绑定 → 放行
        }
        net.minecraft.server.level.ServerLevel level = maid.m_9236_()
                instanceof net.minecraft.server.level.ServerLevel sl ? sl : null;
        String f = ps.foremanUuid;
        if (f == null || f.isEmpty()) {
            if (level != null) {
                String nf = chooseForeman(level, ps);
                if (!nf.isEmpty()) {
                    setForeman(level, ps, nf);
                    return nf.equals(maid.m_20148_().toString());
                }
            }
            return true; // 随机失败（无人可当）→ 放行兜底
        }
        if (f.equals(maid.m_20148_().toString())) {
            return true;
        }
        if (level == null) {
            return false;
        }
        EntityMaid fm = findForemanMaid(level, f);
        if (fm == null) {
            // v1.5.266：工头失效（死亡/解散/离开）→ 重新随机挑一只顶上
            //（旧版放行所有 → 全员 isForeman=true → 全员播报）
            String nf = chooseForeman(level, ps);
            if (!nf.isEmpty()) {
                setForeman(level, ps, nf);
                return nf.equals(maid.m_20148_().toString());
            }
            return true; // 随机也失败（无人）→ 放行兜底
        }
        if (isMaidPaused(fm)) {
            return true; // v1.5.74：工头被暂停 → 放行所有（防全员静默）
        }
        return false;
    }

    /** v1.5.72：严格工头判断（UI 标记用）——只有明确标记的工头为 true，无工头时无人标记 */
    public static boolean isExplicitForeman(EntityMaid maid) {
        PlanState ps = getBoundPlanState(maid);
        if (ps == null) {
            return false;
        }
        String f = ps.foremanUuid;
        return f != null && !f.isEmpty() && f.equals(maid.m_20148_().toString());
    }

    /** 按 UUID 找当前维度已加载的女仆（无则 null） */
    private static EntityMaid findForemanMaid(net.minecraft.world.level.Level level, String uuid) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel sl)) {
            return null;
        }
        for (EntityMaid m : scanAreaMaids(sl)) {
            if (m.m_20148_().toString().equals(uuid)) {
                return m;
            }
        }
        return null;
    }

    /**
     * v1.5.187b：安全女仆扫描——按【各建造区块 box 外扩 64 格】的小盒收集女仆（去重）。
     * 旧版全图 ±3E7 AABB 曾触发 visibleChunks 树迭代死循环（fastutil 病态 Subset，
     * 游戏全卡死）；绑定女仆必然在区块附近建造，小盒覆盖足够，且不跨越坐标正负分界。
     * 没有建造区块 → 空列表。
     */
    public static java.util.List<EntityMaid> scanAreaMaids(net.minecraft.server.level.ServerLevel level) {
        java.util.LinkedHashSet<EntityMaid> set = new java.util.LinkedHashSet<>();
        for (PlanState ps : getPlans(level)) {
            int[] r = planRegion(ps);
            if (r == null) {
                continue;
            }
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                    r[0] - 64.0, r[1] - 32.0, r[2] - 64.0,
                    r[3] + 64.0, r[4] + 32.0, r[5] + 64.0);
            for (EntityMaid m : level.m_45976_(EntityMaid.class, box)) {
                set.add(m);
            }
        }
        return new java.util.ArrayList<>(set);
    }

    // ==================== 强制加载（按区块） ====================

    /** v1.5.41：建造区强制加载（v1.5.180：按区块 planId 独立挂票证） */
    public static void ensureChunks(net.minecraft.server.level.ServerLevel level, PlanState ps) {
        if (GLOBAL_TICKETED.getOrDefault(ps.planId, false)) {
            return;
        }
        int[] box = GLOBAL_BOX.get(ps.planId);
        if (box == null) {
            BlockPos origin = ps.origin;
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (String step : ps.steps) {
                String[] parts = step.split(",", -1);
                if (parts.length < 3) {
                    continue;
                }
                try {
                    int x = Integer.parseInt(parts[0]);
                    int z = Integer.parseInt(parts[2]);
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minZ = Math.min(minZ, z);
                    maxZ = Math.max(maxZ, z);
                } catch (NumberFormatException ignored) {
                }
            }
            if (minX > maxX) {
                return;
            }
            int minCx = (origin.m_123341_() + minX) >> 4;
            int maxCx = (origin.m_123341_() + maxX) >> 4;
            int minCz = (origin.m_123343_() + minZ) >> 4;
            int maxCz = (origin.m_123343_() + maxZ) >> 4;
            if ((long) (maxCx - minCx + 1) * (maxCz - minCz + 1) > com.maidsmart.config.MaidSmartConfig.BUILD_MAX_FORCE_CHUNKS.get()) {
                GLOBAL_BOX.put(ps.planId, new int[]{0, -1, 0, -1}); // 超大区域：标记不加载
                return;
            }
            box = new int[]{minCx, maxCx, minCz, maxCz};
            GLOBAL_BOX.put(ps.planId, box);
        }
        if (box[1] < box[0]) {
            return; // 超大区域已标记跳过
        }
        net.minecraft.server.level.TicketType<net.minecraft.world.level.ChunkPos> forced =
                net.minecraft.server.level.TicketType.f_9445_; // FORCED
        for (int cx = box[0]; cx <= box[1]; cx++) {
            for (int cz = box[2]; cz <= box[3]; cz++) {
                net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(cx, cz);
                level.m_7726_().m_8387_(forced, cp, 0, cp); // addRegionTicket
            }
        }
        GLOBAL_TICKETED.put(ps.planId, true);
        // v1.5.66：建造区块方块时间静止（randomTick 冻结；多区块共存由 ChunkFreeze 分桶）
        com.maidsmart.build.ChunkFreeze.freeze(level, ps.planId, box[0], box[1], box[2], box[3]);
        // v1.5.67：蓝图树叶永久豁免（装饰树打印后不衰减消失）
        com.maidsmart.build.ChunkFreeze.protectLeaves(level, ps.planId, ps.toPlan(), ps.origin);
    }

    /** v1.5.41：释放单个区块的强制加载（完成/取消） */
    public static void releaseChunks(net.minecraft.server.level.ServerLevel level, PlanState ps) {
        int[] box = GLOBAL_BOX.remove(ps.planId);
        GLOBAL_TICKETED.remove(ps.planId);
        // v1.5.66：解冻（只解本区块；其他区块冻结由 ChunkFreeze 维护）
        com.maidsmart.build.ChunkFreeze.unfreeze(level, ps.planId);
        if (box == null || box[1] < box[0]) {
            return;
        }
        net.minecraft.server.level.TicketType<net.minecraft.world.level.ChunkPos> forced =
                net.minecraft.server.level.TicketType.f_9445_; // FORCED
        for (int cx = box[0]; cx <= box[1]; cx++) {
            for (int cz = box[2]; cz <= box[3]; cz++) {
                net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(cx, cz);
                level.m_7726_().m_8438_(forced, cp, 0, cp); // removeRegionTicket
            }
        }
    }

    /** v1.5.79：位置是否处于任意"建造中"蓝图区域（重力冻结/时间静止/避让共用）。
     *  多区块：任一区块包围盒命中即 true */
    public static boolean isBuildingRegion(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        ResourceKey<Level> dim = level.m_46472_();
        int cx = pos.m_123341_() >> 4;
        int cz = pos.m_123343_() >> 4;
        for (PlanState ps : PLANS.values()) {
            if (!ps.dim.equals(dim)) {
                continue;
            }
            int[] box = GLOBAL_BOX.get(ps.planId);
            if (box == null || box[1] < box[0]) {
                continue; // 未计算或超大区域跳过
            }
            if (cx >= box[0] && cx <= box[1] && cz >= box[2] && cz <= box[3]) {
                return true;
            }
        }
        return false;
    }

    // ==================== 重叠检查 ====================

    /**
     * v1.5.180：新计划区域（origin 为中心 w×h×d）与该维度其他区块是否重叠。
     * 返回重叠的区块（无重叠返回 null）。创建区块的唯一硬性要求。
     */
    public static PlanState findOverlap(net.minecraft.server.level.ServerLevel level, BlockPos origin,
                                        int w, int h, int d, String excludePlanId) {
        int nx0 = origin.m_123341_() - w / 2;
        int nx1 = nx0 + w;
        int ny0 = origin.m_123342_();
        int ny1 = ny0 + h;
        int nz0 = origin.m_123343_() - d / 2;
        int nz1 = nz0 + d;
        for (PlanState ps : getPlans(level)) {
            if (excludePlanId != null && ps.planId.equals(excludePlanId)) {
                continue;
            }
            int[] r = planRegion(ps);
            if (r == null) {
                continue;
            }
            if (nx1 > r[0] && nx0 < r[3] && ny1 > r[1] && ny0 < r[4] && nz1 > r[2] && nz0 < r[5]) {
                return ps;
            }
        }
        return null;
    }

    /**
     * v1.5.180：区块方块范围 {minX,minY,minZ,maxX+1,maxY+1,maxZ+1}（绝对坐标）。
     * 由步骤相对坐标 + 原点解析；步骤为空返回 null。
     */
    public static int[] planRegion(PlanState ps) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (String step : ps.steps) {
            String[] parts = step.split(",", -1);
            if (parts.length < 3) {
                continue;
            }
            try {
                int x = Integer.parseInt(parts[0]);
                int y = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[2]);
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
            } catch (NumberFormatException ignored) {
            }
        }
        if (minX > maxX) {
            return null;
        }
        return new int[]{ps.origin.m_123341_() + minX, ps.origin.m_123342_() + minY, ps.origin.m_123343_() + minZ,
                ps.origin.m_123341_() + maxX + 1, ps.origin.m_123342_() + maxY + 1, ps.origin.m_123343_() + maxZ + 1};
    }

    // ==================== 逐只暂停（女仆级，保留） ====================

    /**
     * v1.5.124：逐只暂停改【会话内存态】——旧版存 persistentData（随实体永久存档），
     * 一旦暂停过就永远 true。暂停是临时操作，重启后恢复未暂停是合理语义。
     */
    private static final Map<java.util.UUID, Boolean> MAID_PAUSED =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void setMaidPaused(EntityMaid maid, boolean paused) {
        if (paused) {
            MAID_PAUSED.put(maid.m_20148_(), true);
        } else {
            MAID_PAUSED.remove(maid.m_20148_());
        }
    }

    public static boolean isMaidPaused(EntityMaid maid) {
        return Boolean.TRUE.equals(MAID_PAUSED.get(maid.m_20148_()));
    }

    // ==================== 存档恢复（多区块） ====================

    /** v1.5.180：从存档恢复该维度全部区块（幂等：已在内存的跳过；O(N) 仅首次） */
    private static void restoreAll(net.minecraft.server.level.ServerLevel level) {
        BuildArchive arch = BuildArchive.get(level);
        for (BuildArchive.SavedPlan sp : arch.plans) {
            if (PLANS.containsKey(sp.planId)) {
                continue;
            }
            List<String> steps = BlueprintLib.getBlueprint(sp.blueprintId);
            if (steps == null || steps.isEmpty()) {
                continue;
            }
            List<String> centered = BlueprintLib.centerSteps(steps);
            PlanState ps = new PlanState(sp.planId, level.m_46472_(),
                    new BlockPos(sp.ox, sp.oy, sp.oz), sp.name, sp.blueprintId, centered);
            ps.paused = sp.paused;
            ps.foremanUuid = sp.foremanUuid == null ? "" : sp.foremanUuid;
            ps.savedCursor = Math.max(1, sp.cursor);
            FOREMAN.put(ps.planId, ps.foremanUuid);
            PLANS.put(ps.planId, ps);
            restoreProgress(level, ps);
            LOGGER.info("build plan restored from archive: id={} name={} origin={},{},{} cursor={} paused={}",
                    sp.blueprintId, sp.name, sp.ox, sp.oy, sp.oz, sp.cursor, sp.paused);
        }
    }

    /** v1.5.180：恢复区块进度（游标 + 已建位置预登记）——只在无活跃进度时重建 */
    private static void restoreProgress(net.minecraft.server.level.ServerLevel level, PlanState ps) {
        Progress p = GLOBAL_PROGRESS.get(ps.planId);
        if (p != null) {
            return;
        }
        p = new Progress(ps.planId);
        p.cursor = Math.max(1, ps.savedCursor);
        // v1.5.83：预登记已建位置到 placedSet（扫描世界状态 O(N) 一次，仅恢复时）
        List<String> plan = ps.toPlan();
        for (int i = 1; i < plan.size(); i++) {
            String[] pp = BlueprintLib.parseStep(plan.get(i));
            if (pp == null) {
                continue;
            }
            try {
                int rx = Integer.parseInt(pp[0]);
                int ry = Integer.parseInt(pp[1]);
                int rz = Integer.parseInt(pp[2]);
                net.minecraft.core.BlockPos target = new net.minecraft.core.BlockPos(
                        ps.origin.m_123341_() + rx, ps.origin.m_123342_() + ry, ps.origin.m_123343_() + rz);
                net.minecraft.world.level.block.Block want = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(
                        net.minecraft.resources.ResourceLocation.parse(pp[3]));
                net.minecraft.world.level.block.state.BlockState st = level.m_8055_(target);
                if (want != null && (st.m_60734_() == want
                        || BlueprintLib.isBuiltEquivalent(pp[3], st.m_60734_()))) {
                    long key = (long) (rx & 0xFFFFF) << 42
                            | (long) (ry & 0x1FFFFF) << 21
                            | (long) (rz & 0x1FFFFF);
                    p.placedSet.add(key);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        // v1.5.83：placedCount 用【实际已建数】（placedSet.size）
        p.placedCount = p.placedSet.size();
        GLOBAL_PROGRESS.put(ps.planId, p);
    }

    // ==================== 计划解析（传统格式，保留） ====================

    public static BlockPos getOrigin(List<String> plan) {
        if (plan.isEmpty()) {
            return null;
        }
        String[] parts = plan.get(0).split(",");
        if (parts.length < 4 || !"O".equals(parts[0])) {
            return null;
        }
        try {
            return new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 计划中的蓝图名（第一行第 5 段）；无则返回 "建筑" */
    public static String planName(List<String> plan) {
        if (plan.isEmpty()) {
            return "建筑";
        }
        String[] parts = plan.get(0).split(",");
        if (parts.length >= 5 && !parts[4].isEmpty()) {
            return parts[4];
        }
        return "建筑";
    }

    /** 计划中的蓝图 id（第一行第 6 段；旧计划无 id 返回 null——匹配退化为名字匹配） */
    public static String planId(List<String> plan) {
        if (plan.isEmpty()) {
            return null;
        }
        String[] parts = plan.get(0).split(",");
        if (parts.length >= 6 && !parts[5].isEmpty()) {
            return parts[5];
        }
        return null;
    }

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
}
