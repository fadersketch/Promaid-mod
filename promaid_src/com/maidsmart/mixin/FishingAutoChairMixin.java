package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidFindSitTask;
import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityChair;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v1.5.252q：钓鱼坐垫重做——"有坐垫直接坐 / 没有就在岸边放一个 / 用完就删"。
 *
 * 背景（v1.5.130 版失败复盘，日志实证）：
 * - 原逻辑只在【传感器可见】的椅子集合里找（brain 记忆 NEAREST_VISIBLE_LIVING_ENTITIES，
 *   需要视线+快照刷新），我们 TAIL 生成的坐垫永远不会被传感器"看见"→ 女仆永不
 *   上骑 → 每 5 秒任务重启又生成一个新坐垫 → 岸边堆积一堆坐垫、离水远近全靠
 *   findWaterSpot 的首个命中（dy 外层优先 → 找进洞穴/远岸）。
 *
 * 重做方案：
 * 1. 原逻辑已找到椅子/船（sitEntity != null）→ 不动，走原模组钓鱼流程；
 * 2. 没有 → 直接实体扫描 8 格内空坐垫/船（绕过传感器可见性）→ 走过去/贴脸直接上骑；
 * 3. 还是没有 → 在【离女仆最近】的可钓鱼水域岸边生成一个【带标记】的 TLM 坐垫
 *    （持久 NBT 标记 + 女仆 UUID），并让女仆【直接上骑】（startRiding force——
 *    绕开传感器，坐上后 TLM 骑乘脑 MaidRideFindWaterTask 自动找水抛竿）；
 * 4. 清理（服务端每秒 sweep）：任务解除（当前任务 ≠ 钓鱼）→ 删；脱离坐垫超 2 秒
 *    → 删；女仆不在/下线 → 删。原 no_sit 气泡在"马上要生成坐垫"时压掉，不再出现
 *    "没有椅子"和"我自己带了个坐垫"两句打架。
 *
 * 总开关：misc.produceTaskEnhance。
 */
@Mixin(MaidFindSitTask.class)
public abstract class FishingAutoChairMixin {
    @Shadow
    private Entity sitEntity;

    /** 标记坐垫的持久 NBT 键 */
    private static final String TAG_AUTO = "promaid_auto_chair";
    private static final String TAG_MAID = "promaid_maid_uuid";
    /** TLM 钓鱼任务 UID 的 path 段（m_135815_ = getPath，javap 实证） */
    private static final String FISHING_UID = "fishing";

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(FishingAutoChairMixin.class);

    /** 生成坐垫冷却（tick）：防"生成→坐不上→再生成"循环 */
    private long maidsmart$lastChairTick = -1L;

    /** v1.5.252i：找不到水域的限频警告（30 秒一条，防刷屏） */
    private long maidsmart$lastNoWaterWarn = -1L;

    /** 脱离坐垫计时：坐垫实体 id → 开始脱离的 tick（内存态，仅服务端） */
    private static final Map<Integer, Long> VACATED = new HashMap<>();

    /**
     * 原逻辑找不到椅子时会弹 no_sit 气泡（lambda$start$2）；若我们马上要生成坐垫，
     * 在这里压掉——避免"周围没有坐垫"和"我自己带了个坐垫"两句同时出现。
     */
    @Inject(method = "lambda$start$2", at = @At("HEAD"), cancellable = true)
    private void maidsmart$suppressNoSitBubble(EntityMaid maid, CallbackInfo ci) {
        if (!com.maidsmart.config.MaidSmartConfig.MISC_PRODUCE_TASK_ENHANCE.get()) {
            return;
        }
        if (!(maid.m_9236_() instanceof ServerLevel level)) {
            return;
        }
        if (findWaterSpot(level, maid) != null) {
            ci.cancel();
        }
    }

    @Inject(method = "start", at = @At("TAIL"))
    private void maidsmart$autoChair(ServerLevel world, EntityMaid maid, long gameTime, CallbackInfo ci) {
        if (!com.maidsmart.config.MaidSmartConfig.MISC_PRODUCE_TASK_ENHANCE.get()) {
            return;
        }
        if (this.sitEntity != null) {
            return; // 原逻辑已找到椅子/船——走原模组钓鱼流程
        }
        long now = world.m_46467_();
        if (now - this.maidsmart$lastChairTick < 100L) {
            return; // 5 秒冷却
        }
        // 1) 附近已有空坐垫/船（实体直接扫描——原逻辑的传感器记忆可能过期/不可见）
        Entity near = findNearbySeat(world, maid);
        if (near != null) {
            this.sitEntity = near;
            net.minecraft.world.entity.ai.behavior.BehaviorUtils.m_22590_(maid, near, 0.6f, 0);
            if (near.m_19950_(maid, 2.0)) {
                maid.m_7998_(near, true); // 贴脸直接上骑
            }
            return; // 走原模组钓鱼流程
        }
        // 2) 无坐垫：在最近的可钓鱼水域岸边生成【带标记】坐垫 + 直接坐上去
        BlockPos stand = findWaterSpot(world, maid);
        if (stand == null) {
            // v1.5.252i：留痕——找不到水域时打一条限频日志，方便确认修复是否生效
            if (now - this.maidsmart$lastNoWaterWarn > 600L) {
                this.maidsmart$lastNoWaterWarn = now;
                LOGGER.info("auto-chair: 女仆 {} 位置 ({}, {}, {}) 附近 12 格内未找到可钓鱼水域岸边",
                        maid.m_5446_().getString(), maid.m_20185_(), maid.m_20186_(), maid.m_20187_());
            }
            return; // 保留原 no_sit 气泡（未压制时）
        }
        this.maidsmart$lastChairTick = now;
        removeOldChairs(world, maid); // 先清掉这只女仆的旧标记坐垫（防岸边堆积）
        EntityChair chair = new EntityChair(world,
                stand.m_123341_() + 0.5, stand.m_123342_(), stand.m_123343_() + 0.5, 0.0f);
        CompoundTag tag = chair.getPersistentData();
        tag.m_128359_(TAG_AUTO, "1");
        tag.m_128359_(TAG_MAID, maid.m_20148_().toString());
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

    /** 水面格四周找可站岸边：先试"岸与水面差 1 格"，再试"岸比水面高 2 格"
     *  （1 格深水塘/岸边台阶时，差 1 格的那层是水下的泥土/沙子） */
    private static BlockPos findBank(ServerLevel level, BlockPos w) {
        for (int[] d : DIRS4) {
            BlockPos s1 = w.m_7918_(d[0], 1, d[1]);
            if (isBank(level, s1)) {
                return s1;
            }
            BlockPos s2 = w.m_7918_(d[0], 2, d[1]);
            if (isBank(level, s2)) {
                return s2;
            }
        }
        return null;
    }

    /** 可站岸边：站格空气 + 脚下实心（非空气非液体）+ 头顶空气 */
    private static boolean isBank(ServerLevel level, BlockPos s) {
        if (!level.m_8055_(s).m_60795_()) {
            return false;
        }
        BlockPos under = s.m_7918_(0, -1, 0);
        BlockState us = level.m_8055_(under);
        if (us.m_60795_() || us.m_60815_()) {
            return false;
        }
        return level.m_8055_(s.m_7918_(0, 1, 0)).m_60795_();
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
    public static void sweep(net.minecraft.server.MinecraftServer server) {
        try {
            for (ServerLevel level : server.m_129785_()) {
                Map<String, EntityMaid> maids = new HashMap<>();
                List<EntityChair> chairs = new ArrayList<>();
                for (Entity e : level.m_8583_()) {
                    if (e instanceof EntityChair c && isAutoChair(c)) {
                        chairs.add(c);
                    } else if (e instanceof EntityMaid m && m.m_6084_()) {
                        maids.put(m.m_20148_().toString(), m);
                    }
                }
                if (chairs.isEmpty()) {
                    continue;
                }
                long now = level.m_46467_();
                Set<Integer> seen = new HashSet<>();
                for (EntityChair c : chairs) {
                    seen.add(c.m_19879_());
                    EntityMaid m = maids.get(c.getPersistentData().m_128461_(TAG_MAID));
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
        } catch (Exception ignored) {
            // 清扫失败不影响游戏
        }
    }

    /** 是否 promaid 自动生成的标记坐垫（ChairNoDropMixin 共用：摧毁无掉落） */
    public static boolean isAutoChair(Entity e) {
        return e instanceof EntityChair c && "1".equals(c.getPersistentData().m_128461_(TAG_AUTO));
    }
}
