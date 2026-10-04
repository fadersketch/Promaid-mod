package com.maidsmart.patrol;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.3.8【巡逻航迹】净空校验：沿**密采样后的曲线**查阻挡方块。
 *
 * <p>── 为什么必须在曲线上查、不能只看标记（玩家原话）──
 * 「（但是要加一个前提，不能有阻挡方块。）」——两个标记本身都在空气里，它们**之间**完全
 * 可能穿进山体。所以检查跑在 {@link PatrolCurve} 密采样出来的折线上（每 0.35 格一个采样点），
 * 每个采样点再取一个半径 = 净空值的方盒。
 *
 * <p>── 什么算"阻挡" ──
 * 用**碰撞形状**判空（{@code getCollisionShape(...).isEmpty()}），比单纯 {@code isAir()} 准：
 * 草、花、告示牌、火把这类没有碰撞箱的东西会被正确放行，而栅栏、玻璃板、台阶会被正确拦住。
 * 再叠一道**危险方块**（岩浆/火/岩浆块/仙人掌…）——那些没有碰撞箱，但飞进去就是事故。
 *
 * <p>── 竖直怎么算 ──
 * 女仆碰撞箱 0.6 × 1.5（{@code MaidBroomDrive} 里 {@code MAID_HALF_W} / {@code MAID_HEIGHT}
 * 同口径），座位又在扫帚中心下方 0.3125 格。所以采样点往上取两层（y 与 y+1）就覆盖她的身位，
 * 再加脚下那一层判"别贴着地面擦过去"。
 *
 * <p>── 发现阻挡之后 ──
 * 不做自动修复（自动抬升会悄悄改掉玩家的航迹）。本类只**如实报告**：第几个采样点、坐标、
 * 挡路方块的注册名。玩家在编辑器里据此补一个标记，或把那一处抬高。跑的时候还有兜底——
 * {@code MaidBroomDrive.steerTo} 里本来就有卡墙脱困与危险绕行。
 */
public final class PatrolClearance {

    /** 竖直方向多查几层（她 1.5 格高 + 脚下留一格） */
    private static final int V_UP = 1;
    private static final int V_DOWN = 1;

    private PatrolClearance() {
    }

    /** 一处阻挡 */
    public record Hit(Vec3 pos, String block, boolean dangerous) {
    }

    /** 校验结论 */
    public record Result(boolean ok, List<Hit> hits, int scanned) {
    }

    /**
     * 沿曲线查一遍。
     *
     * @param poly   密采样折线（{@link PatrolCurve#polyline}）
     * @param radius 净空半径（格）：每个采样点周围这个范围都要能过
     * @return 结论；{@code ok=false} 时 {@code hits} 里是最多 {@link #MAX_HITS} 处挡路点
     */
    public static Result check(Level level, List<Vec3> poly, double radius) {
        List<Hit> hits = new ArrayList<>();
        if (level == null || poly == null || poly.isEmpty()) {
            return new Result(true, hits, 0);
        }
        int r = Math.max(0, (int) Math.ceil(radius)); // 半径 1.5 → 扩 2 格（宁可多判一格，不可漏判）
        int scanned = 0;
        // 每个采样点最多记一处（命中即跳到下一个采样点）：报的是"航迹上哪几处过不去"，
        // 不是"这一处有几个方块挡着"——后者对玩家没用，只会把列表刷满。
        for (Vec3 p : poly) {
            scanned++;
            int bx = (int) Math.floor(p.f_82479_);
            int by = (int) Math.floor(p.f_82480_);
            int bz = (int) Math.floor(p.f_82481_);
            Hit found = scanPoint(level, bx, by, bz, r);
            if (found != null) {
                hits.add(found);
                if (hits.size() >= MAX_HITS) {
                    return new Result(false, hits, scanned);
                }
            }
        }
        return new Result(hits.isEmpty(), hits, scanned);
    }

    /** 最多报几处（编辑器一屏放得下就行） */
    public static final int MAX_HITS = 6;

    /**
     * 以一个采样点的方块坐标为心，查 (2r+1)² × 竖直层 的方盒；命中一处就返回它。
     * 竖直只查"她身位"那几层（她 1.5 格高：脚下留 1 层、头顶留 1 层）。
     */
    private static Hit scanPoint(Level level, int bx, int by, int bz, int r) {
        for (int dy = -V_DOWN; dy <= V_UP; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = new BlockPos(bx + dx, by + dy, bz + dz);
                    if (!level.m_46749_(pos)) {
                        continue; // 未加载的区块不判（她飞到那儿时区块票会把它加载出来）
                    }
                    BlockState st;
                    boolean blocking;
                    try {
                        st = level.m_8055_(pos);
                        blocking = !st.m_60812_(level, pos).m_83281_();
                    } catch (Throwable ignored) {
                        continue; // 判不出来就放行（宁可漏一处，不可误拦）
                    }
                    boolean dangerous = false;
                    try {
                        dangerous = com.maidsmart.tool.DangerBlocks.idIn(level, pos.m_123341_(), pos.m_123342_(), pos.m_123343_())
                                || isAlwaysDangerous(level, pos);
                    } catch (Throwable ignored) {
                    }
                    if (!blocking && !dangerous) {
                        continue;
                    }
                    String id;
                    try {
                        net.minecraft.resources.ResourceLocation rl =
                                net.minecraft.core.registries.BuiltInRegistries.f_256975_.m_7981_(st.m_60734_());
                        id = rl == null ? "?" : rl.toString();
                    } catch (Throwable ignored) {
                        id = "?";
                    }
                    return new Hit(new Vec3(pos.m_123341_() + 0.5, pos.m_123342_() + 0.5, pos.m_123343_() + 0.5),
                            id, dangerous);
                }
            }
        }
        return null;
    }

    /**
     * 永远算危险的那几种（不受 {@code misc.dangerBlocks} 配置影响，与
     * {@code DangerBlocks.cellDangerous} 的"头顶灼烧型"同一套名单 +  cactus/浆果丛这类
     * 碰到就掉血的）。
     */
    private static boolean isAlwaysDangerous(Level level, BlockPos pos) {
        try {
            String id = net.minecraft.core.registries.BuiltInRegistries.f_256975_
                    .m_7981_(level.m_8055_(pos).m_60734_()).toString();
            return switch (id) {
                case "minecraft:lava", "minecraft:fire", "minecraft:soul_fire",
                     "minecraft:magma_block", "minecraft:cactus", "minecraft:campfire",
                     "minecraft:soul_campfire", "minecraft:sweet_berry_bush",
                     "minecraft:powder_snow", "minecraft:sculk_shrieker",
                     "minecraft:wither_rose" -> true;
                default -> false;
            };
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 把结论拼成一行可读文本（编辑器/聊天栏用） */
    public static String describe(Result res) {
        if (res.ok()) {
            return "净空 OK（沿曲线查了 " + res.scanned() + " 个采样点）";
        }
        Hit h = res.hits().get(0);
        return String.format("净空不通过：%d 处挡路，第一处在 %.0f, %.0f, %.0f（%s%s）",
                res.hits().size(), h.pos().f_82479_, h.pos().f_82480_, h.pos().f_82481_, h.block(),
                h.dangerous() ? "，危险方块" : "");
    }
}
