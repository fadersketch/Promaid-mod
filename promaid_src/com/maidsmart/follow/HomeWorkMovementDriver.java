package com.maidsmart.follow;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * v1.1.0 实测三百三十三（用户："Home模式下女仆又不会动了。也是的，我们直接全盘
 * 推翻。直接为农场和宰杀home模式专门重新写一套可以运动的逻辑"）：
 * Home 工作移动独立驱动——为【home 模式 + 农场/宰杀任务】的女仆提供独立运动，
 * 不依赖 TLM 大脑活动/行为站桩标记。
 *
 * 为什么需要独立驱动（历次修复的教训）：
 * - 农场：TLM farm 行为靠 60 tick 周期重启驱动（v319/v322/v328 已修），但 home
 *   模式下无作物/无种子时 move 任务重搜无果 → 无 WALK_TARGET → 女仆原地站桩
 * - 宰杀：无超阈值组时行为 setStill(true) + 清 WALK_TARGET + 停导航 → 原地站桩
 *   （日志实证 cow=5 阈值 5 不超 → 永远无目标 → 永远不动）
 * - MoveToTargetSink 消费 WALK_TARGET 驱动寻路，但被 MaidMoveSuppressMixin 在
 *   站桩标记下整段取消；直连导航（PathNavigation.moveTo，m_26519_）不走
 *   MoveToTargetSink——自保逃跑验证过的通道，站桩标记拦不住
 *
 * 驱动逻辑（每 5 tick = 0.25 秒一轮）：
 * - 全图扫描（Entity.class 全量 + instanceof，有限 AABB——ClassInstanceMultiMap
 *   桶 bug 与 ±∞ 溢出均已绕开）
 * - 条件：home 模式 + 任务=农场（touhou_little_maid:farm）或宰杀（maid_smart:slaughter）
 * - 有 WALK_TARGET（工作目标）→ 交给 MoveToTargetSink 正常寻路，不干扰
 * - 无 WALK_TARGET → home 锚点附近随机点直连导航（巡逻式移动，保证"会动"）
 */
public final class HomeWorkMovementDriver {
    private static boolean registered = false;
    private int throttle = 0;

    private HomeWorkMovementDriver() {
    }

    public static void ensureRegistered() {
        if (!registered) {
            registered = true;
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new HomeWorkMovementDriver());
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++this.throttle < 5) {
            return; // 每 5 tick = 0.25 秒一轮
        }
        this.throttle = 0;
        net.minecraft.server.MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        try {
            // 有限 AABB（±∞ 经 blockToSection 溢出收敛 → 扫描恒空，实测三百三十二）
            net.minecraft.world.phys.AABB whole = new net.minecraft.world.phys.AABB(
                    -131072.0, -4096.0, -131072.0, 131072.0, 4096.0, 131072.0);
            for (ServerLevel level : server.m_129785_()) {
                for (net.minecraft.world.entity.Entity e : level.m_45976_(
                        net.minecraft.world.entity.Entity.class, whole)) {
                    if (e instanceof EntityMaid maid) {
                        drive(level, maid);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void drive(ServerLevel level, EntityMaid maid) {
        try {
            if (!maid.m_6084_() || !maid.isHomeModeEnable() || !isFarmOrSlaughter(maid)) {
                return;
            }
            // 有工作目标（WALK_TARGET）→ 交给 MoveToTargetSink 正常寻路，不干扰
            var wt = maid.m_6274_().m_21952_(MemoryModuleType.f_26370_);
            if (wt.isPresent()) {
                return;
            }
            // 无目标 → home 锚点附近随机点直连导航（巡逻式移动）
            BlockPos center = maid.m_21534_();
            if (center == null) {
                var sp = maid.getSchedulePos();
                if (sp != null && sp.isConfigured()) {
                    center = sp.getNearestPos(maid);
                }
            }
            if (center == null) {
                center = maid.m_20183_();
            }
            // 巡逻半径：home 限制半径内（留 1 格余量防触发越界传送），上限 12 格
            int radius = Math.max(3, Math.min((int) maid.m_21535_() - 1, 12));
            int dx = maid.m_217043_().m_188503_(radius * 2 + 1) - radius;
            int dz = maid.m_217043_().m_188503_(radius * 2 + 1) - radius;
            // 目标与女仆同一高度层（脚部方块），寻路器自动落地
            BlockPos target = new BlockPos(center.m_123341_() + dx,
                    maid.m_20183_().m_123342_(), center.m_123343_() + dz);
            // 直连导航（m_26519_ = moveTo(x,y,z,speed)）——不走 MoveToTargetSink，
            // 站桩标记/移动抑制拦不住（自保逃跑验证过的通道）
            maid.m_21573_().m_26519_(target.m_123341_() + 0.5,
                    target.m_123342_(), target.m_123343_() + 0.5, 0.7f);
        } catch (Throwable ignored) {
        }
    }

    /** 任务是否为农场（TLM 原生）或宰杀（maid_smart） */
    private static boolean isFarmOrSlaughter(EntityMaid maid) {
        try {
            if (maid.getTask() == null || maid.getTask().getUid() == null) {
                return false;
            }
            String uid = maid.getTask().getUid().toString();
            return "touhou_little_maid:farm".equals(uid)
                    || "maid_smart:slaughter".equals(uid);
        } catch (Throwable t) {
            return false;
        }
    }
}
