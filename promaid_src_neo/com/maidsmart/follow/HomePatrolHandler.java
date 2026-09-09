package com.maidsmart.follow;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.combat.MaidCombatTacticsBehavior;
import com.maidsmart.combat.SelfPreservationBehavior;
import com.maidsmart.task.MaidWorkTags;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * v1.1.0 实测一百一十三：Home 模式守卫巡逻（呆立根治）。
 *
 * 背景（javap 反编译 TLM 实证）：home 模式女仆的移动驱动只有三个——
 * ①SchedulePos.tick 每 2 秒的回家走位（仅【出圈】时才给目标，站在 home 中心
 * 直接早退）；②IDLE/WORK 活动的 MaidRunOne 随机散步；③follow 任务每 tick
 * 启动占位（home 下 maidStateConditions 全分支空转）。三个驱动各有断点：
 * 排班路径不设 home 锚点（实测一百一十二已修）、isNonCombatWork 漏排跟随
 * 导致 SchedulePosTickMixin 把 tick 整段 cancel（实测一百一十三已修）、
 * 站在 home 中心时 tick 范围内早退不给走位。叠加 → home 女仆没有任何移动
 * 驱动 → 原地呆站。
 *
 * 修复：对【非干活】的 home 模式女仆每 4 秒在 home 锚点附近随机选点给
 * WALK_TARGET（MoveToTargetSink 每 tick 消费，与大脑活动无关）——女仆在
 * 家的范围内持续巡逻走动，不再呆立。干活的（挖矿/农活/建造等由任务驱动）、
 * 战斗/自保/战术/站桩/坐姿/骑乘一律不干扰。
 */
public final class HomePatrolHandler {
    private static int throttle = 0;

    private HomePatrolHandler() {
    }

    /** ProMaidExtension 构造时注册 */
    public static void register() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(new HomePatrolHandler());
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
if (++throttle < 80) {
            return; // 每 4 秒一次
        }
        throttle = 0;
        // v1.1.0 实测三百三十二：全图 AABB 用有限值（±131072/±4096）——±∞ 经
        // SectionPos.blockToSection 换算溢出收敛到同一个值（floor 溢出回绕 + >>4），
        // section 循环只执行一次且落在世界外 → 扫描恒空（v330 改 Entity.class 时
        // 保留了 ±∞，home 巡逻因此失效——"home 模式又不会动了"）
        net.minecraft.world.phys.AABB whole = new net.minecraft.world.phys.AABB(
                -131072.0, -4096.0, -131072.0, 131072.0, 4096.0, 131072.0);
        for (ServerLevel level : event.getServer().getAllLevels()) {
            // v1.1.0 实测三百三十：EntityMaid.class 全图扫描改用 Entity.class 全量 +
            // instanceof 过滤——ClassInstanceMultiMap 桶 bug（同 FarmTillDriver）：
            // 未预建 EntityMaid 桶的 section 被整段跳过，home 女仆单独站的 section
            // 扫不到 → 巡逻驱动失效（"home 女仆呆立"的另一重根因）
            for (net.minecraft.world.entity.Entity e : level.getEntitiesOfClass(
                    net.minecraft.world.entity.Entity.class, whole)) {
                if (e instanceof EntityMaid maid) {
                    patrol(maid);
                }
            }
        }
    }

    private static void patrol(EntityMaid maid) {
        try {
            if (!maid.isHomeModeEnable() || !maid.isAlive()
                    || maid.isPassenger() || maid.isMaidInSittingPose()) {
                return;
            }
            // 干活的由任务驱动移动，不干扰；战斗/自保/战术/站桩也不干扰
            if (MaidWorkTags.isNonCombatWork(maid) || MaidWorkTags.isStill(maid)
                    || MaidWorkTags.isBuildSitting(maid)) {
                return;
            }
            if (((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getBoolean(SelfPreservationBehavior.PRESERVE_TAG)
                    || MaidCombatTacticsBehavior.isActive(maid)) {
                return;
            }
            // 已有行走目标且尚未走到 → 交给现有移动，别每 4 秒硬改方向
            var brain = maid.getBrain();
            var existing = brain.getMemory(MemoryModuleType.WALK_TARGET);
            if (existing.isPresent()) {
                WalkTarget wt = existing.get();
                BlockPos wtPos = wt.getTarget().currentBlockPosition();
                if (wtPos != null && maid.position().distanceTo(Vec3.atCenterOf(wtPos)) > 9.0) {
                    return;
                }
            }
            // home 锚点：restrictCenter（TLM restrictTo 每 2 秒刷新）→ schedulePos 最近点 → 当前位置
            BlockPos center = maid.getRestrictCenter();
            if (center == null) {
                var sp = maid.getSchedulePos();
                if (sp != null && sp.isConfigured()) {
                    center = sp.getNearestPos(maid);
                }
            }
            if (center == null) {
                center = maid.blockPosition();
            }
            // 巡逻半径：home 限制半径内（留 1 格余量防触发越界传送），上限 12 格
            int radius = Math.max(3, Math.min((int) maid.getRestrictRadius() - 1, 12));
            int dx = maid.getRandom().nextInt(radius * 2 + 1) - radius;
            int dz = maid.getRandom().nextInt(radius * 2 + 1) - radius;
            // 目标与女仆同一高度层（脚部方块），寻路器自动落地
            BlockPos target = new BlockPos(center.getX() + dx,
                    maid.blockPosition().getY(), center.getZ() + dz);
            brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, 0.7f, 2));
        } catch (Exception ignored) {
        }
    }
}
