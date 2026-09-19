package com.maidsmart.build;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import java.util.Collections;

/**
 * 实测五百五十三③：**走去箱子取材料**（服务端行为，注册在建筑任务里、优先级高于建造）。
 *
 * 需求："检索区块内的箱子以及潜影箱，女仆会有一个移动到箱子和打开的动画"。
 *
 * 触发条件（全部满足才出门）：
 * - 开关 {@code build.fetchFromChests}（默认开）、正在做建造任务、计划未暂停；
 * - 计划里有一种料的**需求 > 女仆+主人背包现有**（这个口径不含容器——不然一开箱就算
 *   "有料"，永远不缺、也就永远不会去取）；
 * - 建造区块（外扩 margin）里确实有容器装着这种料。
 *
 * 执行：解除"建造中静止"（建造行为会把女仆钉在工地，见 MaidWorkTags）→ 走到容器旁
 * （3 格内）→ **开箱动画**（原版 `blockEvent(1,…)`，箱子/桶/潜影箱的盖子都吃这一套）
 * → 取 {@code build.chestFetchPerTake} 个（塞不下的还回原槽）→ 关箱 → 恢复静止回工地。
 *
 * 找不到容器时**什么都不做**，缺料提示与延后重试仍由 MaidBuildBehavior 负责。
 */
public class BuildContainerFetchBehavior extends Behavior<EntityMaid> {
    /** 够得着的距离（3 格） */
    private static final double REACH_SQ = 9.0;
    /** 找容器的节流（tick）——canUse 每 tick 被问一次，不能每次都扫区块 */
    private static final int SCAN_INTERVAL = 40;

    private int scanCooldown = 0;
    private long nextTryAt = -1;
    private BlockPos target = null;
    private String neededId = null;
    /** 开箱动画剩余 tick（0 = 没开） */
    private int openTicks = 0;
    /** 本行为是否把女仆从"静止"里放了出来（收尾要还原） */
    private boolean releasedStill = false;
    private boolean tookAnything = false;

    public BuildContainerFetchBehavior() {
        super(Collections.emptyMap());
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        if (!com.maidsmart.config.MaidSmartConfig.BUILD_FETCH_FROM_CHESTS.get()) {
            return false;
        }
        if (maid.isPassenger() || maid.isMaidInSittingPose()) {
            return false;
        }
        if (!BlueprintBuildExecutor.isBuildingTask(maid)) {
            return false;
        }
        if (this.scanCooldown > 0) {
            this.scanCooldown--;
            return false;
        }
        this.scanCooldown = SCAN_INTERVAL;
        long now = level.getGameTime();
        if (now < this.nextTryAt) {
            return false;
        }
        BuildPlan.PlanState ps = BuildPlan.getBoundPlanState(maid);
        // v1.2.2 实测五百八十六（issue #14）：容器取料的材料匹配与就地建造同一口径
        BlueprintLib.setMaterialScope(ps == null ? null : ps.blueprintId);
        if (ps == null || ps.paused || ps.steps == null || ps.steps.isEmpty()) {
            return false;
        }
        int[] box = BuildContainerSource.regionOf(ps);
        if (box == null) {
            return false;
        }
        // 找"缺的那种料"：需求 > 女仆+主人背包（不含容器）
        java.util.Map<String, Integer> needs = (ps.blueprintId == null || ps.blueprintId.isEmpty())
                ? BlueprintLib.countNeeds(ps.steps) : BlueprintLib.countNeedsCached(ps.blueprintId, ps.steps);
        String bestId = null;
        int bestMiss = 0;
        net.minecraft.world.entity.player.Player owner = ownerOf(maid);
        for (java.util.Map.Entry<String, Integer> e : needs.entrySet()) {
            int have = BlueprintLib.combinedHaveAll(level, owner, e.getKey());
            int miss = e.getValue() - have;
            if (miss > bestMiss) {
                bestMiss = miss;
                bestId = e.getKey();
            }
        }
        if (bestId == null) {
            return false; // 不缺料（或料已经在手上）→ 不打扰建造
        }
        net.minecraft.world.level.block.entity.BlockEntity c = BuildContainerSource.findContainer(level, box, bestId);
        if (c == null) {
            this.nextTryAt = now + Math.max(20, com.maidsmart.config.MaidSmartConfig.BUILD_CHEST_FETCH_COOLDOWN.get());
            return false;
        }
        this.neededId = bestId;
        this.target = c.getBlockPos();
        this.tookAnything = false;
        return true;
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        // 建造行为每 tick 会把女仆钉在工地（setStill=true + 清走位）——取料这段时间先放开
        com.maidsmart.task.MaidWorkTags.setStill(maid, false);
        this.releasedStill = true;
        if (this.target != null) {
            BehaviorUtils.setWalkAndLookTargetMemories(maid, this.target, 0.7f, 2);
        }
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        if (this.target == null || this.neededId == null) {
            return false;
        }
        if (level.getBlockEntity(this.target) == null) {
            return false; // 箱子被拆了
        }
        return true;
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        if (this.target == null) {
            return;
        }
        double dSq = maid.blockPosition().distSqr(this.target);
        if (dSq > REACH_SQ) {
            // 还在路上：走位目标丢了就补一次
            if (maid.getBrain().getMemory(MemoryModuleType.WALK_TARGET).isEmpty()) {
                BehaviorUtils.setWalkAndLookTargetMemories(maid, this.target, 0.7f, 2);
            }
            return;
        }
        // 到位：先开箱（原版盖子动画），等几 tick 再取——"走过去 → 开箱 → 取 → 关箱"
        if (this.openTicks > 0) {
            this.openTicks--;
            if (this.openTicks == 8) {
                net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(this.target);
                if (be instanceof net.minecraft.world.Container c) {
                    int got = BuildContainerSource.take(c, this.neededId,
                            com.maidsmart.config.MaidSmartConfig.BUILD_CHEST_FETCH_PER_TAKE.get(), maid);
                    this.tookAnything = got > 0;
                    if (got > 0) {
                        maid.swing(net.minecraft.world.InteractionHand.MAIN_HAND); // 挥臂"拿东西"
                    }
                }
            }
            if (this.openTicks == 0) {
                closeLid(level); // 关箱
            }
            return;
        }
        if (!this.tookAnything && this.openTicks == 0) {
            openLid(level);
            this.openTicks = 14;
        }
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        closeLid(level);
        this.openTicks = 0;
        if (this.releasedStill) {
            com.maidsmart.task.MaidWorkTags.setStill(maid, true); // 交还"建造中静止"
            this.releasedStill = false;
        }
        if (this.target != null && this.neededId != null) {
            this.nextTryAt = level.getGameTime()
                    + Math.max(20, com.maidsmart.config.MaidSmartConfig.BUILD_CHEST_FETCH_COOLDOWN.get());
        }
        this.target = null;
        this.neededId = null;
    }

    /** 开箱盖（原版 blockEvent id=1：箱子/桶/潜影箱的 triggerEvent 都处理它） */
    private void openLid(ServerLevel level) {
        if (this.target == null) {
            return;
        }
        try {
            level.blockEvent(this.target, level.getBlockState(this.target).getBlock(), 1, 1);
        } catch (Throwable ignored) {
        }
    }

    /** 关箱盖 */
    private void closeLid(ServerLevel level) {
        if (this.target == null) {
            return;
        }
        try {
            level.blockEvent(this.target, level.getBlockState(this.target).getBlock(), 1, 0);
        } catch (Throwable ignored) {
        }
    }

    /** 女仆的主人（取料口径与建造一致：主人背包也算已有材料） */
    private static net.minecraft.world.entity.player.Player ownerOf(EntityMaid maid) {
        net.minecraft.world.entity.LivingEntity owner = maid.getOwner();
        return owner instanceof net.minecraft.world.entity.player.Player p ? p : null;
    }
}
