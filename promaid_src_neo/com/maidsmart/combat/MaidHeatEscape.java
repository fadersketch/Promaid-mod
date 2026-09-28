package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import com.maidsmart.tool.DangerBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v1.3.0(beta) 实测七百一十一【烫伤脱困】——飞行中（扫帚 / 空袭 / 飞行跟随）她**真的**被烫到时，
 * 立刻（本 tick 内）传送到最近的空气格。
 *
 * <p>【玩家原话】「扫帚和空袭模式下有关岩浆的寻路还是不太聪明。好像是跟之前那个窒息的问题
 * 同一个问题，用同样的方法解决就行。同时再加一个如果被烫到了立刻传送到最近的空气方块。」
 *
 * <h2>为什么"跟窒息是同一个问题"</h2>
 * 地面那套危险方块处理（{@code DangerEscapeHandler}）有一条**明确的豁免**：
 * {@code if (maid.isMaidInSittingPose() || maid.isPassenger()) continue;}——
 * 理由是"坐姿由椅子系统管、骑乘由载具负责"。而**扫帚模式的女仆永远是乘客**（她骑的就是扫帚），
 * 空袭/飞行跟随的女仆则整天在空中：于是这三个模式里，**危险方块（岩浆/火）一条逃生都没有**
 * ——飞行一侧只剩 {@link MaidFlightHazardGuard} 的"预测式避让"（{@code detour}/{@code antiSink}：
 * 只在她**还没进去**时绕开/抬平），一旦真的贴上了（被击退、被地形挤、烟花推偏），
 * 没有任何"已经在里面了就出来"的机制。这正是玩家说的"跟窒息同一个问题"：**豁免挂错了对象**
 * （窒息那条挂的是"扫帚的位移"，这条挂的是"她是乘客"）。
 *
 * <h2>判据就用原版那一个</h2>
 * {@code LivingEntity.isInLava()} / {@code isOnFire()}——**恰好**是"这一 tick 原版要不要烧她"
 * 那两道判据，与窒息那档用 {@code isInWall()} 同源：不自己写几何判定，就不会出现
 * "看着泡在岩浆里、其实没掉血"的误触发。烫不疼的（抗火效果 / 火焰保护饰品）**不算**
 * ——那两样本来就是"泡岩浆不掉血"，不该惊慌。
 *
 * <h2>为什么是"传送"而不是"飘过去"</h2>
 * 扫帚那条链路里"飘到空气格"是一段有时长的转向相位（{@link MaidBroomDrive} 的脱困相位），
 * 而岩浆每秒 4 点伤害、她只有 20 血——**等不起**。所以这一档直接传送（与原版
 * {@code Entity.teleportTo} 同一条链路，清摔落/清速度），落点当场生效。
 * 骑扫帚时**连人带扫帚一起搬**（走 {@code MaidChunkLoadManager.recallBroomRiderTo}，
 * 与「扫帚牵引绳」同一段搬运代码——直接传她会被 {@code unRide} 从扫帚上踹下来）。
 *
 * <h2>落点：她放得下的最近空气格</h2>
 * 她宽 0.6 / 高 1.5 → 站立格 + 头顶格都得是空气；再排除"自身/脚下就是危险方块"的格子
 * （否则下一拍又踩回去）。一圈都找不到"脚下不危险"的，就退一步只要求两格空气
 * ——**先脱离流体最重要**，后续飞行逻辑会把她带走。水平 ±{@link #SCAN_R}、竖直
 * -{@link #SCAN_DOWN}~+{@link #SCAN_UP}，取最近的；一次扫描 ≈ 300 格方块读取，
 * 只在"真被烫到"时触发，开销可忽略。
 *
 * <h2>调用点</h2>
 * 三个飞行行为各自 {@code tick} 的**最前面**（扫帚 / 空袭 / 飞行跟随），返回 true 时调用方
 * 就此打住——刚传送完再写这一 tick 的推进意图只会把她从落点上推走（与「扫帚牵引绳」同一条约定）。
 * 冷却 {@link #COOLDOWN} tick：防"落点又烫、立刻再传"的抖动。
 * 【兜底】冷却中 / 一时找不到落点时不传送；扫帚那条链路的转向脱困会把同一道判据再兜一次。
 *
 * <p>【两树同一份】只用到 {@code isInLava/isOnFire/isInWater} 这些加载器共有的名字，
 * 本文件在 SRG 树与 Mojmap 树里逐字相同。
 */
public final class MaidHeatEscape {

    /** 水平扫描半径（格）——"最近的空气方块"，扫小一点才叫最近 */
    private static final int SCAN_R = 3;
    /** 向下扫几格（她可能已经沉进岩浆面下） */
    private static final int SCAN_DOWN = 2;
    /** 向上扫几格（岩浆湖上方多半就是空气） */
    private static final int SCAN_UP = 3;
    /** 两次传送之间的最短间隔（tick）= 1 秒，防抖 */
    private static final long COOLDOWN = 20L;

    /** 女仆 UUID → 可再传送的 gameTime */
    private static final Map<UUID, Long> READY = new ConcurrentHashMap<>();

    private MaidHeatEscape() {
    }

    /** 开关（配置未加载等异常按关闭处理——绝不因为脱困坏了飞行） */
    public static boolean enabled() {
        try {
            return MaidSmartConfig.COMBAT_HEAT_ESCAPE.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 每 tick 由三个飞行行为调用一次。
     *
     * @return true = 这一 tick 刚把她（连扫帚）传送出烫伤区 → 调用方就此打住
     */
    public static boolean tick(EntityMaid maid) {
        try {
            if (maid == null || !enabled()) {
                return false;
            }
            if (!(maid.level() instanceof ServerLevel level)) {
                return false;
            }
            if (maid.isRemoved() || !maid.isAlive()) {
                return false; // 已移除 / 死亡
            }
            if (!burning(maid)) {
                return false;
            }
            long now = level.getGameTime();
            Long ready = READY.get(maid.getUUID());
            if (ready != null && now < ready) {
                return false; // 冷却中：交给扫帚那条转向脱困兜底
            }
            double x = maid.getX();
            double y = maid.getY();
            double z = maid.getZ();
            boolean wasLava = maid.isInLava();
            BlockPos cell = nearestAirCell(level, x, y, z);
            if (cell == null) {
                READY.put(maid.getUUID(), now + COOLDOWN);
                return false; // 附近真找不出一格放得下她的空气 → 下一轮再来（转向脱困也在跑）
            }
            READY.put(maid.getUUID(), now + COOLDOWN);
            boolean riding = MaidBroomKit.ridingBroom(maid) != null;
            boolean ok = riding
                    ? com.maidsmart.follow.MaidChunkLoadManager.relocateBroomRiderToCell(maid, cell)
                    : teleportSelf(maid, level, cell);
            if (!ok) {
                return false;
            }
            // 应急灭火：离开源头后原版仍会烧完剩余 tick，不扑灭等于没救（同 DangerEscapeHandler 口径）
            boolean doused = false;
            if (maid.isOnFire()) {
                maid.setRemainingFireTicks(-1);
                doused = true;
            }
            com.maidsmart.tool.PromaidLog.log("烫伤脱困",
                    com.maidsmart.tool.PromaidLog.nameOf(maid)
                            + " 正在" + (wasLava ? "泡岩浆" : "着火")
                            + " → 立刻传送到最近的空气格 ("
                            + cell.getX() + ", " + cell.getY() + ", " + cell.getZ() + ")"
                            + (riding ? "（连人带扫帚）" : "")
                            + (doused ? "（应急灭火）" : ""));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 她此刻**真的**被烫到了吗。
     *
     * <p>在水里一律不算：水会把火自然浇灭，而且"在水里"与"在岩浆里"互斥。
     * 烫不疼的（抗火效果 / 火焰保护饰品）也不算——见类注释。
     */
    private static boolean burning(EntityMaid maid) {
        try {
            if (maid.isInWater()) {
                return false;
            }
            if (SelfPreservationBehavior.fireImmune(maid)) {
                return false;
            }
            return maid.isInLava() || maid.isOnFire();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 把她一个人传过去（清摔落 / 清速度 + 末影人音效，同 {@code teleportCoreTo} 口径） */
    private static boolean teleportSelf(EntityMaid maid, ServerLevel level, BlockPos cell) {
        try {
            maid.teleportTo(level, cell.getX() + 0.5, cell.getY(),
                    cell.getZ() + 0.5, Collections.emptySet(), maid.getYRot(), maid.getXRot());
            maid.fallDistance = 0.0f;
            maid.setDeltaMovement(Vec3.ZERO);
            level.playSound(null, cell, net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT,
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.0f);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 最近的、她放得下的空气格：站立格 + 头顶格都是空气，且**自身与脚下都不是危险方块**。
     * 一圈都没有"脚下安全"的，就退一步只要求"两格空气"（先脱离流体最重要）。
     */
    private static BlockPos nearestAirCell(ServerLevel level, double x, double y, double z) {
        try {
            BlockPos base = BlockPos.containing(x, y, z);
            BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            for (int dx = -SCAN_R; dx <= SCAN_R; dx++) {
                for (int dz = -SCAN_R; dz <= SCAN_R; dz++) {
                    for (int dy = -SCAN_DOWN; dy <= SCAN_UP; dy++) {
                        BlockPos p = base.offset(dx, dy, dz);
                        if (!airColumns(level, p)) {
                            continue;
                        }
                        if (DangerBlocks.idIn(level, p.getX(), p.getY(), p.getZ())
                                || DangerBlocks.idIn(level, p.getX(), p.getY() - 1, p.getZ())) {
                            continue; // 脚下就是岩浆/火 → 不选（下一拍又踩回去）
                        }
                        double d = dx * dx + dy * dy + dz * dz;
                        if (d < bestD) {
                            bestD = d;
                            best = p;
                        }
                    }
                }
            }
            if (best != null) {
                return best;
            }
            // 退一步：只要两格空气（哪怕下面是岩浆）——先脱离流体，后续飞行逻辑会把她带走
            for (int dx = -SCAN_R; dx <= SCAN_R; dx++) {
                for (int dz = -SCAN_R; dz <= SCAN_R; dz++) {
                    for (int dy = -SCAN_DOWN; dy <= SCAN_UP; dy++) {
                        BlockPos p = base.offset(dx, dy, dz);
                        if (!airColumns(level, p)) {
                            continue;
                        }
                        double d = dx * dx + dy * dy + dz * dz;
                        if (d < bestD) {
                            bestD = d;
                            best = p;
                        }
                    }
                }
            }
            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 站立格与头顶格都是空气（她高 1.5 格 → 要两格） */
    private static boolean airColumns(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).isAir() && level.getBlockState(p.offset(0, 1, 0)).isAir();
    }
}
