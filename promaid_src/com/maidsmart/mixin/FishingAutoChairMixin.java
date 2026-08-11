package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidFindSitTask;
import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityChair;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.5.130：钓鱼专项——找不到椅子/船时主动找水域并自带坐垫。
 *
 * 根因：TaskFishing 只有 MaidFindSitTask：找附近空 EntityChair/Boat 走过去坐下
 * 钓鱼；找不到就弹 no_sit 气泡原地空转（"钓鱼空转"缺陷）。
 *
 * 修复：原逻辑没找到椅子/船时，扫描女仆附近开阔水域（3x3 水面 + 岸边可站立
 * 格），在岸边生成一个 TLM 坐垫椅子实体（EntityChair，与原版坐垫同款），
 * 女仆走过去坐下开钓——5 秒冷却防极端循环。找不到水域才保留原 no_sit 气泡。
 * 总开关：misc.produceTaskEnhance。
 */
@Mixin(MaidFindSitTask.class)
public abstract class FishingAutoChairMixin {
    @Shadow
    private Entity sitEntity;

    /** 生成坐垫冷却（tick）：防"生成→坐不上→再生成"循环 */
    private long maidsmart$lastChairTick = -1L;

    /** v1.5.252i：找不到水域的限频警告（30 秒一条，防刷屏） */
    private long maidsmart$lastNoWaterWarn = -1L;

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(FishingAutoChairMixin.class);

    @Inject(method = "start", at = @At("TAIL"))
    private void maidsmart$autoChair(ServerLevel world, EntityMaid maid, long gameTime, CallbackInfo ci) {
        if (!com.maidsmart.config.MaidSmartConfig.MISC_PRODUCE_TASK_ENHANCE.get()) {
            return;
        }
        if (this.sitEntity != null) {
            return; // 原逻辑已找到椅子/船
        }
        long now = world.m_46467_();
        if (now - this.maidsmart$lastChairTick < 100L) {
            return; // 5 秒冷却
        }
        BlockPos stand = findWaterSpot(world, maid);
        if (stand == null) {
            // v1.5.252i：留痕——找不到水域时打一条限频日志，方便确认修复是否生效
            if (now - this.maidsmart$lastNoWaterWarn > 600L) {
                this.maidsmart$lastNoWaterWarn = now;
                LOGGER.info("auto-chair: 女仆 {} 位置 ({}, {}, {}) 附近 16 格内未找到开阔水域岸边",
                        maid.m_5446_().getString(), maid.m_20185_(), maid.m_20186_(), maid.m_20187_());
            }
            return; // 附近没有开阔水域，保留原 no_sit 气泡
        }
        this.maidsmart$lastChairTick = now;
        EntityChair chair = new EntityChair(world,
                stand.m_123341_() + 0.5, stand.m_123342_(), stand.m_123343_() + 0.5, 0.0f);
        world.m_7967_(chair);
        LOGGER.info("auto-chair: 女仆 {} 在 ({}, {}, {}) 生成坐垫",
                maid.m_5446_().getString(), stand.m_123341_(), stand.m_123342_(), stand.m_123343_());
        maid.getChatBubbleManager().addTextChatBubble("这里没有椅子，我自己带了个坐垫，找个水边坐下钓鱼～");
    }

    /** 找"开阔水域岸边可站格"：3x3 水面 + 站立格空气 + 脚下实心 + 头顶空气 */
    private static BlockPos findWaterSpot(ServerLevel level, EntityMaid maid) {
        BlockPos pos = maid.m_20183_();
        int r = 16;
        for (int dy = -4; dy <= 8; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = pos.m_7918_(dx, dy, dz);
                    if (!level.m_8055_(p).m_60815_()) {
                        continue; // 水面格
                    }
                    if (!level.m_8055_(p.m_7918_(0, 1, 0)).m_60795_()) {
                        continue; // 水上方需空气（鱼钩落点）
                    }
                    if (!openWater(level, p)) {
                        continue; // 需 3x3 开阔水面
                    }
                    // 岸边 4 方向找可站立格（v1.5.252i：岸边格必须在水面【高 1 格】——
                    // 水面同高度的水平相邻格是水位线以下的泥土/沙子，永远不是空气，
                    // 旧判定在任何自然河岸都失败 → "找不到水域" → 修复不触发）
                    for (int[] d : DIRS4) {
                        BlockPos stand = p.m_7918_(d[0], 1, d[1]);
                        if (!level.m_8055_(stand).m_60795_()) {
                            continue;
                        }
                        BlockPos under = stand.m_7918_(0, -1, 0);
                        BlockState us = level.m_8055_(under);
                        if (us.m_60795_() || !us.m_60796_(level, under)) {
                            continue; // 脚下需实心
                        }
                        if (!level.m_8055_(stand.m_7918_(0, 1, 0)).m_60795_()) {
                            continue; // 头顶需空气
                        }
                        return stand;
                    }
                }
            }
        }
        return null;
    }

    private static final int[][] DIRS4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** 以水面格为中心的 3x3 是否都是"水 + 上方空气"（开阔水域判定） */
    private static boolean openWater(ServerLevel level, BlockPos water) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos p = water.m_7918_(dx, 0, dz);
                if (!level.m_8055_(p).m_60815_()) {
                    return false;
                }
                if (!level.m_8055_(p.m_7918_(0, 1, 0)).m_60795_()) {
                    return false;
                }
            }
        }
        return true;
    }
}
