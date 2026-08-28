package com.maidsmart.protect;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.combat.SelfPreservationBehavior;
import com.maidsmart.config.MaidSmartConfig;
import com.maidsmart.tool.DangerBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * v1.1.0 实测九十：险境脱离——用户追问："如果女仆已经是处于这些危险方块上了，
 * 那么会怎么办？"
 *
 * 寻路避让（八十九）只管"别走进去"；已经站在岩浆/岩浆块/火上、或身处火里的
 * 女仆会持续掉血，旧机制要等到血量跌破 30% 触发自保才动手，中间白挨大量伤害。
 * 本处理器每 0.5 秒巡检一次：站立柱（脚格/头顶/脚下）命中危险表的女仆，
 * 立即挪到最近的安全格并应急灭火。
 *
 * 语义与边界：
 * - 【救命优先】home 模式的女仆也会被挪——但只挪几格脱离险境，不是传送到主人
 *   身边，与"看家不响应传送"的语义不冲突（基岩顶不是家，火面也不是家）；
 * - 【坐姿/骑乘跳过】坐姿由 TLM 椅子系统管理位移、骑乘由载具负责——强拽会与
 *   两套系统打架；这两态女仆仍受自保体系兜底；
 * - 【自保中让位】自保会话有自己的岩浆逃生链路（珍珠/放水/垫高），不重复干预；
 * - 【无处可逃】周围安全格全无（四面环岩浆中央）→ 记冷却后交给自保的资源链路；
 * - 【应急灭火】逃离火源时清除剩余着火时间（m_7311_(-1)）——离开源头后 vanilla
 *   仍会烧完剩余 tick，救急不彻底等于没救。
 */
public class DangerEscapeHandler {
    private static final org.slf4j.Logger LOGGER =
            com.mojang.logging.LogUtils.getLogger();

    /** 巡检节流：每 10 tick（0.5 秒）一轮 */
    private int throttle = 0;

    /** 每女仆脱困尝试冷却（gameTime）——防振荡（刚挪走又在边缘反复触发） */
    private static final Map<UUID, Long> LAST_TRY = new HashMap<>();

    /** 冷却间隔（tick）：30 = 1.5 秒 */
    private static final long RETRY_INTERVAL = 30L;

    /** 搜索半径（格）：最近安全格查找范围（v1.1.0 实测一百二十七：4 → 8——
     *  闭环岩浆测试里女仆距安全地面只有 2~3 格，4 格内全是岩浆+玩家中心格，
     *  容易在可救范围内找不到安全格而走上静默放弃路径） */
    private static final int SEARCH_RADIUS = 8;

    public static void register() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new DangerEscapeHandler());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++this.throttle < 10) {
            return; // 每 0.5 秒一轮
        }
        this.throttle = 0;
        if (!MaidSmartConfig.MISC_DANGER_ESCAPE.get()
                || !MaidSmartConfig.MISC_DANGER_AVOID.get()) {
            return; // 总开关或避让开关关闭（共用同一张危险表）
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        AABB whole = new AABB(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        long now = server.m_129921_();
        for (ServerLevel level : server.m_129785_()) {
            for (EntityMaid maid : level.m_45976_(EntityMaid.class, whole)) {
                if (!maid.m_6084_() || maid.m_269323_() == null) {
                    continue; // 无主不处理
                }
                // 自保中让位（自保有珍珠/放水/垫高专属链路）；坐姿/骑乘跳过（见类注释）
                if (maid.getPersistentData().m_128471_(SelfPreservationBehavior.PRESERVE_TAG)
                        || maid.isMaidInSittingPose() || maid.m_20159_()) {
                    continue;
                }
                BlockPos feet = maid.m_20183_();
                boolean inDanger = DangerBlocks.cellDangerous(level,
                        feet.m_123341_(), feet.m_123342_(), feet.m_123343_());
                if (!inDanger) {
                    LAST_TRY.remove(maid.m_20148_());
                    continue;
                }
                Long last = LAST_TRY.get(maid.m_20148_());
                if (last != null && now - last < RETRY_INTERVAL) {
                    continue;
                }
                LAST_TRY.put(maid.m_20148_(), now);
                BlockPos safe = findNearestSafe(level, feet);
                String name = maid.m_5446_() != null ? maid.m_5446_().getString()
                        : maid.m_20148_().toString();
                if (safe == null) {
                    // v1.1.0 实测一百二十七：四面皆险的静默放弃路径补日志——
                    // 旧版直接 continue 且不落盘，故障时完全不可见（只能猜）
                    com.maidsmart.tool.PromaidLog.log("险境脱离",
                            name + " 危险中但 8 格内无可落足安全格（四面皆险），"
                                    + "交由自保资源链路");
                    continue;
                }
                maid.m_6034_(safe.m_123341_() + 0.5, safe.m_123342_(),
                        safe.m_123343_() + 0.5);
                maid.f_19789_ = 0.0f; // 脱离时不继承摔落距离
                maid.m_20256_(net.minecraft.world.phys.Vec3.f_82478_);
                // 应急灭火：身上还带着火就清掉（离开源头后 vanilla 会继续烧完剩余时间）
                boolean doused = false;
                if (maid.m_6060_()) {
                    maid.m_7311_(-1);
                    doused = true;
                }
                // v1.1.0 实测九十四：运行日志（替代原 latest.log 直写）
                com.maidsmart.tool.PromaidLog.log("险境脱离", name + " "
                        + feet.m_123341_() + "," + feet.m_123342_() + "," + feet.m_123343_()
                        + " -> " + safe.m_123341_() + "," + safe.m_123342_() + "," + safe.m_123343_()
                        + (doused ? "（应急灭火）" : ""));
            }
        }
    }

    /**
     * 找最近的安全格：环形半径 0~SEARCH_RADIUS，每列纵向 -2~+2 窗口。
     * 安全 = 站立格与头顶均为空气且不在危险表、脚下方块实心且不在危险表、
     * 且【没有被实体占用】。
     * （空气判定天然排除火/岩浆本身——它们不是空气。）
     * v1.1.0 实测一百二十七：实体占用排除——旧版不查，闭环岩浆测试里唯一的
     * 近距"安全格"是主人脚下那格，脱离会把女仆传进玩家身体，碰撞再把女仆
     * 挤回岩浆（反复循环掉血、永远站不上安全格）。
     */
    private static BlockPos findNearestSafe(ServerLevel level, BlockPos from) {
        for (int r = 0; r <= SEARCH_RADIUS; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue; // 只扫外环，由近及远
                    }
                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos c = from.m_7918_(dx, dy, dz);
                        if (isSafeCell(level, c) && !cellOccupied(level, c)) {
                            return c;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** 该格是否被存活实体占用（主人站在中心洞里时中心格不可作为脱离目标——
     *  传送进去会被碰撞挤回危险区） */
    private static boolean cellOccupied(ServerLevel level, BlockPos c) {
        try {
            net.minecraft.world.phys.AABB box = new AABB(c).m_82400_(-0.05);
            return !level.m_45976_(net.minecraft.world.entity.LivingEntity.class, box).isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isSafeCell(ServerLevel level, BlockPos c) {
        Level lvl = level;
        // 站立格 + 头顶必须都是空气（顺带排除火/岩浆等非空气危险体）
        if (!lvl.m_8055_(c).m_60795_() || !lvl.m_8055_(c.m_7918_(0, 1, 0)).m_60795_()) {
            return false;
        }
        // 站立格本体/头顶不得在危险表（细雪是完整方块但可列入表内拦截）
        if (DangerBlocks.idIn(lvl, c.m_123341_(), c.m_123342_(), c.m_123343_())
                || DangerBlocks.idIn(lvl, c.m_123341_(), c.m_123342_() + 1, c.m_123343_())) {
            return false;
        }
        BlockPos below = c.m_7495_();
        var belowSt = lvl.m_8055_(below);
        // 脚下必须实心且不在危险表
        return belowSt.m_60796_(lvl, below) && !DangerBlocks.idIn(lvl,
                below.m_123341_(), below.m_123342_(), below.m_123343_());
    }
}
