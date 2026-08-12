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
    /** 找不到水域的限频警告（tick，按女仆 UUID，30 秒一条） */
    private static final Map<String, Long> LAST_NO_WATER_WARN = new HashMap<>();
    /** 脱离坐垫计时：坐垫实体 id → 开始脱离的 tick（内存态，仅服务端） */
    private static final Map<Integer, Long> VACATED = new HashMap<>();

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
        return findWaterSpot(level, maid) != null;
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
        // 1) 附近已有空坐垫/船（实体直接扫描——原逻辑的传感器记忆可能过期/不可见）
        Entity near = findNearbySeat(world, maid);
        if (near != null) {
            net.minecraft.world.entity.ai.behavior.BehaviorUtils.m_22590_(maid, near, 0.6f, 0);
            if (near.m_19950_(maid, 2.0)) {
                maid.m_7998_(near, true); // 贴脸直接上骑
            }
            return; // 走原模组钓鱼流程
        }
        // 2) 无坐垫：在最近的可钓鱼水域岸边生成【带标记】坐垫 + 直接坐上去
        BlockPos stand = findWaterSpot(world, maid);
        if (stand == null) {
            Long w = LAST_NO_WATER_WARN.get(key);
            if (w == null || now - w > 600L) {
                LAST_NO_WATER_WARN.put(key, now);
                LOGGER.info("auto-chair: 女仆 {} 位置 ({}, {}, {}) 附近 12 格内未找到可钓鱼水域岸边",
                        maid.m_5446_().getString(), maid.m_20185_(), maid.m_20186_(), maid.m_20187_());
            }
            return; // 保留原 no_sit 气泡（未压制时）
        }
        LAST_SPAWN.put(key, now);
        removeOldChairs(world, maid); // 先清掉这只女仆的旧标记坐垫（防岸边堆积）
        EntityChair chair = new EntityChair(world,
                stand.m_123341_() + 0.5, stand.m_123342_(), stand.m_123343_() + 0.5, 0.0f);
        CompoundTag tag = chair.getPersistentData();
        tag.m_128359_(TAG_AUTO, "1");
        tag.m_128359_(TAG_MAID, key);
        if (world.m_7967_(chair)) {
            boolean mounted = maid.m_7998_(chair, true);
            LOGGER.info("auto-chair: 女仆 {} 在 ({}, {}, {}) 生成标记坐垫{}",
                    maid.m_5446_().getString(), stand.m_123341_(), stand.m_123342_(), stand.m_123343_(),
                    mounted ? "并坐上" : "（上骑失败）");
            maid.getChatBubbleManager().addTextChatBubble("这里没有椅子，我自己带了个坐垫，找个水边坐下钓鱼～");
        }
    }

    /** 8 格内空坐垫/船（存活 + 无乘客；不要求传感器可见） */
    private static Entity findNearbySeat(ServerLevel world, EntityMaid maid) {
        BlockPos c = maid.m_20183_();
        AABB box = new AABB(c.m_123341_() - 8.0, c.m_123342_() - 4.0, c.m_123343_() - 8.0,
                c.m_123341_() + 9.0, c.m_123342_() + 5.0, c.m_123343_() + 9.0);
        List<Entity> list = world.m_6443_(Entity.class, box, e -> e != maid
                && e.m_6084_() && e.m_20197_().isEmpty()
                && (e instanceof EntityChair || e instanceof Boat));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 找"离女仆最近的可钓鱼水域岸边"：水面格 + 上方空气，岸格空气 + 脚下实心 + 头顶空气 */
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
                        if (!level.m_8055_(w).m_60815_()) {
                            continue; // 水面格
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

    /** 水面格四周找可站岸边：找该方向岸边柱的【最上面实心方块】，坐垫放在它上面
     *  （贴地不悬浮）。旧版只查"水面+1/+2 层"——水面与地面同高（1 格深水塘）时
     *  正确的岸（水面层空气格）永远查不到 → 只能退回 +2 层 → 坐垫悬浮在
     *  水塘上方（用户实测："把坐垫放进水里"） */
    private static BlockPos findBank(ServerLevel level, BlockPos w) {
        int wx = w.m_123341_();
        int wz = w.m_123343_();
        for (int[] d : DIRS4) {
            int bx = wx + d[0];
            int bz = wz + d[1];
            for (int y = w.m_123342_() + 1; y >= w.m_123342_() - 2; y--) {
                BlockState st = level.m_8055_(new BlockPos(bx, y, bz));
                if (st.m_60795_() || st.m_60815_()) {
                    continue; // 空气/液体不算地面
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
                if (chairs.isEmpty()) {
                    continue;
                }
                long now = level.m_46467_();
                Set<Integer> seen = new HashSet<>();
                for (EntityChair c : chairs) {
                    seen.add(c.m_19879_());
                    EntityMaid m = allMaids.get(c.getPersistentData().m_128461_(TAG_MAID));
                    boolean fishing = m != null && m.getTask() != null
                            && FISHING_UID.equals(m.getTask().getUid().m_135815_());
                    boolean ridden = !c.m_20197_().isEmpty();
                    if (m == null || !fishing) {
                        c.m_146870_(); // 任务解除/女仆不在 → 删
                        VACATED.remove(c.m_19879_());
                    } else if (ridden) {
                        VACATED.remove(c.m_19879_()); // 正常钓鱼中
                    } else {
                        Long v = VACATED.get(c.m_19879_());
                        if (v == null) {
                            VACATED.put(c.m_19879_(), now); // 刚脱离，开始计时
                        } else if (now - v > 40L) {
                            c.m_146870_(); // 脱离超过 2 秒 → 删
                            VACATED.remove(c.m_19879_());
                        }
                    }
                }
                VACATED.keySet().removeIf(k -> !seen.contains(k));
            }
            // 冷却/限频表随女仆清理（防长时运行内存泄漏）
            LAST_SPAWN.keySet().removeIf(k -> !allMaids.containsKey(k));
            LAST_NO_WATER_WARN.keySet().removeIf(k -> !allMaids.containsKey(k));
        } catch (Exception ignored) {
            // 清扫失败不影响游戏
        }
    }

    /** 是否 promaid 自动生成的标记坐垫（ChairNoDropMixin 共用：摧毁无掉落） */
    public static boolean isAutoChair(Entity e) {
        return e instanceof EntityChair c && "1".equals(c.getPersistentData().m_128461_(TAG_AUTO));
    }
}
