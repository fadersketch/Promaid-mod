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
 * 那么会怎么办？"
 * 寻路避让（八十九）只管"别走进去"；已经站在岩浆/岩浆块/火上、或身处火里的
 * 女仆会持续掉血，旧机制要等到血量跌破 30% 触发自保才动手，中间白挨大量伤害。
 * 本处理器每 0.5 秒巡检一次：站立柱（脚格/头顶/脚下）命中危险表的女仆，
 * 立即挪到最近的安全格并应急灭火。
 * 语义与边界：
 * - 【救命优先】home 模式的女仆也会被挪——但只挪几格脱离险境，不是传送到主人
 *   身边，与"看家不响应传送"的语义不冲突（基岩顶不是家，火面也不是家）；
 * - 【坐姿/骑乘跳过】坐姿由 TLM 椅子系统管理位移、骑乘由载具负责——强拽会与
 *   两套系统打架；这两态女仆仍受自保体系兜底；
 * - 【自保中让位】自保会话有自己的岩浆逃生链路（珍珠/放水/垫高），不重复干预；
 * - 【无处可逃】周围安全格全无（四面环岩浆中央）→ 记冷却后交给自保的资源链路；
 * - 【应急灭火】逃离火源时清除剩余着火时间（m_7311_(-1)）——离开源头后 vanilla 仍会烧完剩余 tick，救急不彻底等于没救。
 */
public class DangerEscapeHandler {
    private static final org.slf4j.Logger LOGGER =
            com.mojang.logging.LogUtils.getLogger();

    /** 巡检节流：每 10 tick（0.5 秒）一轮 */
    private int throttle = 0;

    /** 每女仆脱困尝试冷却（gameTime）——防振荡（刚挪走又在边缘反复触发） */
    private static final Map<UUID, Long> LAST_TRY = new HashMap<>();

    /** 【R1149】窒息传送冷却（gameTime）：2 秒，避免连续窒息判定反复传 */
    private static final Map<UUID, Long> SUFFOCATE_ESCAPE_UNTIL = new HashMap<>();

    /** 冷却间隔（tick）：30 = 1.5 秒 */
    private static final long RETRY_INTERVAL = 30L;

    /** 搜索半径（格）：最近安全格查找范围（v1.1.0 实测一百二十七：4 → 8——
     *  闭环岩浆测试里女仆距安全地面只有 2~3 格，4 格内全是岩浆+玩家中心格，
     *  容易在可救范围内找不到安全格而走上静默放弃路径） */
    private static final int SEARCH_RADIUS = 8;
    /** 【R1149】灵魂营火：扫描半径 6 格找源头；撤离只要站到营火旁 1 格，不往远处甩 */
    private static final int SOUL_SCAN_RADIUS = 6;
    private static final double SOUL_ESCAPE_DIST_SQ = 0.9;

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
        // v1.5.395 实测八百二十一【解耦】：险境脱离只由自己的开关（dangerEscape）控制。
        // 旧版这里还串了 dangerAvoid——两者本是"避让（别走进去）/脱离（已经在里面了拉出来）"
        // 两件事，共用一张危险表不等于共用开关：关闭寻路避让（想让女仆照常踩过、只别出事）
        // 的玩家，不该连"已经站在火上把她拉出来"一起失去。表仍是同一张（misc.dangerBlocks）。
        if (!MaidSmartConfig.MISC_DANGER_ESCAPE.get()) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        // v1.1.0 实测三百三十二：全图 AABB 用有限值（±∞ 经 blockToSection 溢出
        // 收敛到同一值 → 扫描恒空，同 HomePatrolHandler）
        long now = server.m_129921_();
        for (ServerLevel level : server.m_129785_()) {
            // v1.1.0 实测三百三十：EntityMaid.class 全图扫描改用 Entity.class 全量 +
            // instanceof 过滤——类查询漏扫（同 FarmTillDriver，真因见 MaidRoster）
            for (net.minecraft.world.entity.Entity e : com.maidsmart.tool.MaidRoster.inAsEntities(level)) {
                if (!(e instanceof EntityMaid maid) || !maid.m_6084_()
                        || maid.m_269323_() == null) {
                    continue; // 无主不处理
                }
                // 【R1149】坐姿/自保不再是无条件的免死金牌：烧制会强制 Pose.SITTING，
                // 而灵魂营火的「灵魂灼烧」范围比燃烧更远——她吃不到火却持续掉血，
                // 自保会话也不认这个 debuff。骑乘/特殊鞍位仍然跳过（有专属链路）。
                BlockPos feet = maid.m_20183_();
                boolean burning = maid.m_6060_() || maid.m_20077_();
                BlockPos soulFire = findLitSoulCampfire(level, feet, SOUL_SCAN_RADIUS);
                // 只有整个人站在营火格上才撤；站到旁边 1 格就停，避免反复传送
                boolean soulBurn = soulFire != null && hasSoulBurn(maid)
                        && sq(feet, soulFire) < 1.0;
                if (maid.m_20159_()
                        || com.maidsmart.combat.RideBindManager.isSpecialMountRider(maid)) {
                    continue;
                }
                if (((net.minecraftforge.common.extensions.IForgeEntity) maid).getPersistentData().m_128471_(SelfPreservationBehavior.PRESERVE_TAG)
                        && !burning && !soulBurn) {
                    continue;
                }
                if (maid.isMaidInSittingPose() && !burning && !soulBurn) {
                    continue;
                }
                // 【R1149】点燃的营火/灵魂营火单独判一次：营火的伤害不一定给她挂上
                // isOnFire（它直接造成 inFire 伤害），只靠燃烧标志会漏；也不能只靠
                // 危险表——老存档的危险表可能被玩家删过营火。营火格本身即危险。
                boolean litCampfire = isLitCampfire(level.m_8055_(feet))
                        || isLitCampfire(level.m_8055_(feet.m_7495_()));
                boolean inDanger = burning || litCampfire || soulBurn || DangerBlocks.cellDangerous(level,
                        feet.m_123341_(), feet.m_123342_(), feet.m_123343_());
                if (!inDanger) {
                    LAST_TRY.remove(maid.m_20148_());
                    continue;
                }
                // 实测九百三十九【抗火 / 火焰保护饰品豁免】：危险**全部**来自热源（火 / 岩浆 /
                // 营火 / 岩浆块）而她此刻烫不疼时，不搬走——否则每次路过熔岩 / 营火旁边都被
                // 弹开（有抗火仍被传送的根因）。非热危险（仙人掌 / 细雪 / 石笋等）不走此豁免。
                // 【R1149】已经在燃烧/灵魂灼烧就不豁免（豁免只挡"没受伤的烫"）。
                if (!burning && !soulBurn && SelfPreservationBehavior.fireImmune(maid)
                        && (litCampfire || DangerBlocks.heatOnlyDanger(level, feet.m_123341_(),
                                feet.m_123342_(), feet.m_123343_()))) {
                    LAST_TRY.remove(maid.m_20148_()); // 不记账：豁免一旦消失，下一轮立即恢复抢救
                    continue;
                }
                Long last = LAST_TRY.get(maid.m_20148_());
                if (last != null && now - last < RETRY_INTERVAL) {
                    continue;
                }
                LAST_TRY.put(maid.m_20148_(), now);
                // 灵魂灼烧：只要离开营火格、站到相邻安全格即可（最近的那座营火）
                BlockPos safe = soulFire == null ? findNearestSafe(level, feet)
                        : findNearestSafeAwayFrom(level, feet, soulFire, SOUL_ESCAPE_DIST_SQ);
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
                        + (doused ? "（应急灭火）" : "")
                        + (soulBurn ? "（灵魂灼烧）" : ""));
            }
        }
    }

    /** 【R1149】点燃的营火：灵魂营火也继承 CampfireBlock，共用 LIT */
    private static boolean isLitCampfire(net.minecraft.world.level.block.state.BlockState state) {
        return state != null
                && state.m_60734_() instanceof net.minecraft.world.level.block.CampfireBlock
                && state.m_61143_(net.minecraft.world.level.block.CampfireBlock.f_51227_);
    }

    /** 【R1149】两格中心距平方（BlockPos 没有实体那套距离方法） */
    private static double sq(BlockPos a, BlockPos b) {
        double dx = a.m_123341_() - b.m_123341_();
        double dy = a.m_123342_() - b.m_123342_();
        double dz = a.m_123343_() - b.m_123343_();
        return dx * dx + dy * dy + dz * dz;
    }

    /** 【R1149】工作行为让位判据：正在燃烧 / 泡岩浆 / **站在**灵魂营火格上且被灼烧 → 先处理危险。
     *  灵魂灼烧残留（已退到旁边）不打断烧制/酿造，否则灵魂营火不能当烹饪台。 */
    public static boolean isHeatDanger(EntityMaid maid) {
        if (maid == null) {
            return false;
        }
        if (maid.m_6060_() || maid.m_20077_()) {
            return true;
        }
        if (!hasSoulBurn(maid) || !(maid.m_9236_() instanceof ServerLevel level)) {
            return false;
        }
        BlockPos feet = maid.m_20183_();
        BlockPos fire = findLitSoulCampfire(level, feet, SOUL_SCAN_RADIUS);
        return fire != null && sq(feet, fire) < 1.0;
    }

    /** 【R1149】窒息自保：到次数后传送到 8 格内最近的安全格；返回是否真的传送 */
    public static boolean escapeSuffocation(EntityMaid maid) {
        try {
            if (maid == null || !(maid.m_9236_() instanceof ServerLevel level)) {
                return false;
            }
            long now = level.m_46467_();
            Long last = SUFFOCATE_ESCAPE_UNTIL.get(maid.m_20148_());
            if (last != null && now < last) {
                return false;
            }
            SUFFOCATE_ESCAPE_UNTIL.put(maid.m_20148_(), now + 40L);
            BlockPos from = maid.m_20183_();
            BlockPos safe = findNearestSafe(level, from);
            String name = maid.m_5446_() != null ? maid.m_5446_().getString()
                    : maid.m_20148_().toString();
            if (safe == null) {
                com.maidsmart.tool.PromaidLog.log("窒息脱离",
                        name + " 8 格内没有可落足的安全格，保留原地顶出兜底");
                return false;
            }
            maid.m_6034_(safe.m_123341_() + 0.5, safe.m_123342_(), safe.m_123343_() + 0.5);
            maid.f_19789_ = 0.0f;
            maid.m_20256_(net.minecraft.world.phys.Vec3.f_82478_);
            com.maidsmart.task.MaidWorkTags.setStill(maid, false);
            com.maidsmart.tool.PromaidLog.log("窒息脱离", name + " "
                    + from.m_123341_() + "," + from.m_123342_() + "," + from.m_123343_()
                    + " -> " + safe.m_123341_() + "," + safe.m_123342_() + "," + safe.m_123343_());
            return true;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("DangerEscapeHandler", ignored);
            return false;
        }
    }

    /** 【R1149】点燃的灵魂营火：按注册名认，避免 SRG 常量差异；灵魂营火也继承 CampfireBlock */
    private static BlockPos findLitSoulCampfire(ServerLevel level, BlockPos feet, int radius) {
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.m_121940_(feet.m_7918_(-radius, -4, -radius),
                feet.m_7918_(radius, 4, radius))) {
            var state = level.m_8055_(p);
            var id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.m_60734_());
            if (id == null || !"minecraft:soul_campfire".equals(id.toString())
                    || !state.m_61143_(net.minecraft.world.level.block.CampfireBlock.f_51227_)) {
                continue;
            }
            double sq = sq(feet, p);
            if (sq < bestSq) {
                bestSq = sq;
                best = new BlockPos(p);
            }
        }
        return best;
    }

    /** 【R1149】身上带「灵魂灼烧」类 debuff：注册名同时含 soul 与 burn（如 goety:soul_burn） */
    public static boolean hasSoulBurn(EntityMaid maid) {
        try {
            for (net.minecraft.world.effect.MobEffectInstance inst : maid.m_21220_()) {
                var id = net.minecraftforge.registries.ForgeRegistries.MOB_EFFECTS
                        .getKey(inst.m_19544_());
                if (id == null) {
                    continue;
                }
                String path = id.m_135815_().toLowerCase();
                if (path.contains("soul") && path.contains("burn")) {
                    return true;
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("DangerEscapeHandler", ignored);
        }
        return false;
    }

    /**
     * 找最近的安全格：环形半径 0~SEARCH_RADIUS，每列纵向 -2~+2 窗口。
     * 安全 = 站立格与头顶均为空气且不在危险表、脚下方块实心且不在危险表、
     * 且【没有被实体占用】。
     * （空气判定天然排除火/岩浆本身——它们不是空气。）
     * v1.1.0 实测一百二十七：实体占用排除——旧版不查，闭环岩浆测试里唯一的
     * 近距"安全格"是主人脚下那格，脱离会把女仆传进玩家身体，碰撞再把女仆 挤回岩浆（反复循环掉血、永远站不上安全格）。
     */
    private static BlockPos findNearestSafe(ServerLevel level, BlockPos from) {
        return findNearestSafe(level, from, null, 0.0, SEARCH_RADIUS);
    }

    /** 【R1149】灵魂灼烧专用：目标格必须离开源头 minDistSq 以上；搜不到才退回普通安全格 */
    private static BlockPos findNearestSafeAwayFrom(ServerLevel level, BlockPos from,
                                                    BlockPos avoid, double minDistSq) {
        BlockPos safe = findNearestSafe(level, from, avoid, minDistSq, SEARCH_RADIUS);
        return safe != null ? safe : findNearestSafe(level, from);
    }

    private static BlockPos findNearestSafe(ServerLevel level, BlockPos from,
                                            BlockPos avoid, double minDistSq, int radius) {
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue; // 只扫外环，由近及远
                    }
                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos c = from.m_7918_(dx, dy, dz);
                        if (avoid != null && sq(c, avoid) <= minDistSq) {
                            continue;
                        }
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
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("DangerEscapeHandler", ignored);
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
