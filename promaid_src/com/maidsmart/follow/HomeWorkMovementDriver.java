package com.maidsmart.follow;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * v1.1.0 实测三百三十三（反馈："Home模式下女仆又不会动了。也是的，我们直接全盘
 * 推翻。直接为农场和宰杀home模式专门重新写一套可以运动的逻辑"）：
 * Home 工作移动独立驱动——为【home 模式 + 宰杀任务】的女仆提供独立运动，
 * 不依赖 TLM 大脑活动/行为站桩标记。
 *
 * <p>v1.2.4 实测六百四十六（issue #22 第一条："Home 模式下农场工作选项，女仆会快速
 * 地到处乱转，检测耕地作物是否成熟时，不是去看而是走遍每一块耕地"）：
 * <b>农场已从本驱动摘掉，本驱动只管宰杀</b>。本驱动给的是"没有移动目标就直连
 * 导航去 home 锚点附近的随机点"，这一招与 TLM 原版农场任务天生打架：</p>
 * <ul>
 *   <li>TLM 的 {@code MaidFarmMoveTask} 要求 WALK_TARGET <b>为空</b>才会去搜下一块地，
 *       搜到就自己 setWalkTarget 走过去；而 {@code MaidArriveAtBlockTask} 一到站就把
 *       WALK_TARGET/TARGET_POS 两份记忆清掉——"到站 → 清空 → 再搜"之间天然有空档。</li>
 *   <li>一次搜不到活（地里没有可收/可种的）时，TLM 会静默等
 *       {@code maxCheckRate(120) + rand(120)} = <b>6~12 秒</b>才再搜一次。旧版本驱动在这
 *       6~12 秒里每 5 tick 就 {@code PathNavigation.moveTo} 换一个随机目的地——直接覆盖
 *       掉 TLM 正在走的路径，表现就是"在农田上快速地到处乱转"（随机点铺满农田，
 *       看着就像"走遍每一块耕地去检查"）。</li>
 *   <li>跟随模式没有本驱动（本驱动只服务 home 模式）——所以这个问题只在 home 模式出现，
 *       与用户观察一致。</li>
 *   <li>三百三十三 当时需要这份驱动，是因为那会儿我们把"需要锄的泥土"塞进了 TLM 的
 *       移动扫描（FarmMoveTillMixin）。实测二百九十八 已按用户要求把农场运作
 *       <b>完全回原版</b>——原版自己会走去收成熟作物，不再需要一个"随机巡逻"替她找活。</li>
 * </ul>
 *
 * <p>现在农场这条链路的口径与用户对"正常"的定义一致（六百四十六 原话："车万女仆本体
 * 对农场工作选项的支持，正常来说就是……平常就是跟着走或者站着，只有作物成熟了才跑过去
 * 收割"）：<b>本驱动不再给农场女仆注入任何移动</b>——闲着时走的是
 * {@code MaidStrollBehavior}（空闲散步，自带"已有移动目标就不打扰"的门禁，不会抢
 * TLM 的路径），忙时走的是原版农场任务自己。宰杀保持原样（TLM 那边没有"自己找活"的
 * 能力，无牲畜可杀时会永久站桩，必须由本驱动兜着）。</p>
 *
 * <p>驱动逻辑（每 5 tick = 0.25 秒一轮）：全图扫描（Entity.class 全量 + instanceof，
 * 有限 AABB——ClassInstanceMultiMap 桶 bug 与 ±∞ 溢出均已绕开）；条件 = home 模式 +
 * 宰杀任务；有 WALK_TARGET（工作目标）→ 交给 MoveToTargetSink 正常寻路，不干扰；
 * 无 WALK_TARGET → home 锚点附近随机点直连导航（巡逻式移动，保证"会动"）。</p>
 */
public final class HomeWorkMovementDriver {
    private static boolean registered = false;
    private int throttle = 0;

    /** 实测六百四十六：驱动日志限频（30 秒/女仆）——验收搜「home 工作移动」 */
    private static final java.util.Map<java.util.UUID, Long> DRIVE_DIAG_SINCE = new java.util.HashMap<>();

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
            for (ServerLevel level : server.m_129785_()) {
                for (net.minecraft.world.entity.Entity e : level.m_8583_()) {
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
            if (!maid.m_6084_() || !maid.isHomeModeEnable() || !isSlaughter(maid)) {
                return;
            }
            // 有工作目标（WALK_TARGET）→ 交给 MoveToTargetSink 正常寻路，不干扰
            var wt = maid.m_6274_().m_21952_(MemoryModuleType.f_26370_);
            if (wt.isPresent()) {
                return;
            }
            // 无目标 → home 锚点附近随机点直连导航（巡逻式移动）
            // 实测五百六十二：锚点链去掉"当前位置"兜底——restrictCenter（干活中也
            // 刷新，排除零点）→ schedulePos 最近点；都拿不到 → 本轮不巡逻
            BlockPos center = com.maidsmart.follow.WorkAreaClamp.circleCenter(maid);
            if (center == null) {
                var sp = maid.getSchedulePos();
                if (sp != null && sp.isConfigured()) {
                    center = sp.getNearestPos(maid);
                }
            }
            if (center == null) {
                return;
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
            // 实测六百四十六：落一行限频日志——"她没有牲畜可杀时才由本驱动带着走"
            // 这件事要能从日志里直接对账（本驱动不再碰农场女仆）
            long now = level.m_46467_();
            Long last = DRIVE_DIAG_SINCE.get(maid.m_20148_());
            if (last == null || now - last >= 600L) {
                DRIVE_DIAG_SINCE.put(maid.m_20148_(), now);
                com.maidsmart.tool.PromaidLog.log("驻守", com.maidsmart.tool.PromaidLog.nameOf(maid)
                        + " home 工作移动：宰杀任务当前无牲畜目标 → 去 home 附近闲逛点 " + target);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 任务是否为宰杀（maid_smart）。v1.2.4 实测六百四十六：农场（TLM 原生）已移出本驱动。 */
    private static boolean isSlaughter(EntityMaid maid) {
        try {
            if (maid.getTask() == null || maid.getTask().getUid() == null) {
                return false;
            }
            return "maid_smart:slaughter".equals(maid.getTask().getUid().toString());
        } catch (Throwable t) {
            return false;
        }
    }
}
