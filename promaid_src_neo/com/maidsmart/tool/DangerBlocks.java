package com.maidsmart.tool;

import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * v1.1.0 实测八十九/九十：危险方块表共享工具。
 *
 * 配置面板「战斗与自保 → 危险方块 → 危险方块名单」（toml 键 dangerBlocks，注册名列表）→ Block 集合缓存（按列表实例同一性
 * 失效重建）。两处消费方：
 * - MaidDangerPathMixin（寻路层：节点改判 BLOCKED，绕开危险）；
 * - DangerEscapeHandler（险境脱离：已身处危险方块上时挪到最近安全格）。
 *
 * 三格判定语义：站立格本体命中 / 脚下方块命中（站上去就出事）/ 头顶一格为
 * 灼烧型（火·灵魂火·岩浆——穿行会烧），任一命中即视为危险。
 */
public final class DangerBlocks {

    /** v1.1.0 实测一百二十七：缓存类型 Set<Block> → Set<String>（注册名）。
     *  致命错配：旧版缓存存 Block 对象但 isDanger/idIn 用 rl.toString() 字符串
     *  查询——HashSet<Block>.contains(String) 恒 false → 危险表永远匹配不上，
     *  寻路 mixin 与险境脱离（cellDangerous）全是死代码（实测：女仆站岩浆上
     *  掉血到 25% 自保，脱离处理器一次都没触发、零日志）。 */
    private static volatile Set<String> cache = null;
    private static volatile List<? extends String> cacheKey = null;

    private DangerBlocks() {
    }

    /** 总开关（dangerAvoid；配置未加载等异常时按关闭处理） */
    public static boolean enabled() {
        try {
            return MaidSmartConfig.MISC_DANGER_AVOID.get();
        } catch (Exception e) {
             com.maidsmart.diag.Diag.swallow("DangerBlocks", e); return false;
        }
    }

    /**
     * v1.1.0 实测九十一：该方块是否在危险表中（不受 dangerAvoid 开关影响）。
     * 搭方块选材（MaidBuildBlockFilter）据此把危险方块无条件排除出垫脚名单。
     */
    public static boolean isDanger(Block b) {
        if (b == null) {
            return false;
        }
        try {
            ResourceLocation rl = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b);
            return rl != null && set().contains(rl.toString());
        } catch (Exception e) {
             com.maidsmart.diag.Diag.swallow("DangerBlocks", e); return false;
        }
    }

    /** 该坐标方块注册名是否在危险表中 */
    public static boolean idIn(BlockGetter level, int x, int y, int z) {
        try {
            BlockState st = level.getBlockState(new BlockPos(x, y, z));
            ResourceLocation rl = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock());
            if (rl == null) {
                return false;
            }
            return set().contains(rl.toString());
        } catch (Exception e) {
             com.maidsmart.diag.Diag.swallow("DangerBlocks", e); return false;
        }
    }

    /**
     * (x, y, z) 是否为危险站立格：本体命中 / 脚下(y-1)命中 / 头顶(y+1)灼烧型。
     */
    public static boolean cellDangerous(BlockGetter level, int x, int y, int z) {
        if (idIn(level, x, y, z) || idIn(level, x, y - 1, z)) {
            return true;
        }
        // 头顶灼烧型子集（固定四种，不受配置增删影响——浆果丛顶上走过去没事）
        return isBurnAbove(level, x, y + 1, z);
    }

    /** 危险表里的「热危险」子集——火 / 灵魂火 / 岩浆 / 营火 / 灵魂营火 / 岩浆块，都是"烫"。
     *  抗火效果与火焰保护饰品对它们豁免（见 {@link #heatOnlyDanger}）；表里的仙人掌 /
     *  甜浆果丛 / 凋零玫瑰 / 细雪 / 石笋不是热危险，不吃这份豁免。 */
    private static final Set<String> HEAT = Set.of(
            "minecraft:lava", "minecraft:fire", "minecraft:soul_fire",
            "minecraft:magma_block", "minecraft:campfire", "minecraft:soul_campfire");

    /** 该站立格的危险是否**全部**由热危险构成——是则抗火 / 火焰保护饰品可豁免脱离。
     *  口径与 {@link #cellDangerous} 逐格对齐：本体 / 脚下在表内且都是热危险、头顶灼烧型
     *  也只算热；只要有一个非热贡献（仙人掌等）就不算，照常脱离。 */
    public static boolean heatOnlyDanger(BlockGetter level, int x, int y, int z) {
        if (!cellDangerous(level, x, y, z)) {
            return false;
        }
        boolean heat = false;
        for (int dy = 0; dy >= -1; dy--) {
            String id = idOf(level, x, y + dy, z);
            if (!set().contains(id)) {
                continue;
            }
            if (HEAT.contains(id)) {
                heat = true;
            } else {
                return false; // 有非热危险贡献 → 不纯，照常脱离
            }
        }
        return heat || isBurnAbove(level, x, y + 1, z);
    }

    /** 头顶那格是否为固定灼烧型（火 / 灵魂火 / 岩浆）——与 {@link #cellDangerous} 同口径 */
    private static boolean isBurnAbove(BlockGetter level, int x, int y, int z) {
        String id = idOf(level, x, y, z);
        return id.equals("minecraft:fire") || id.equals("minecraft:soul_fire")
                || id.equals("minecraft:lava");
    }

    /**
     * <p>──
     * 「对于岩浆这种危险环境的判定，可能需要把女仆自身的碰撞伤害算进去。尽可能的让她规避岩浆。」
     * <p>── {@link #cellDangerous} 漏了什么 ──
     * 它只看**一个格**（站立格 / 脚下 / 头顶灼烧型）。而女仆的碰撞箱是
     * {@code 0.6 × 1.8} 格——她横跨**两格宽**：站在 {@code (x, z)} 的方格上时，
     * 脚已经伸进 {@code x+1} 或 {@code z+1} 那一列了。旧判据下"她自己那一格是安全的、 但隔壁一格就是岩浆"时判**安全**，于是她会贴着岩浆边缘站着/走着，被自己的碰撞箱 推进岩浆（"有时候还是会踩上去"就是这一档）。飞行那一路更明显：{@code detour} 只探中轴那一条线，她的宽度让"擦着岩浆湖面过"仍然进得去。 <p>── 本方法做什么 ── 扫她碰撞箱覆盖到的**全部水平格**（{@code ceil(宽/2) × ceil(宽/2)}，默认宽 0.6 时是 1 格半径 → 3×3，即 {@code x-1..x+1}），任一格命中 {@link #cellDangerous} 就返回 true。 也就是说：**判据从"脚底那一格"扩到"她的整个身位"**。 <p>── 边界（如实写）── ① 只**加宽**不**加高**：竖直仍只算站立格 / 脚下 / 头顶灼烧型——把整个 1.8 格高的箱子 都算上的话，站在深坑边（她头顶那格是空气、坑底是岩浆）也会被判危险，那不是"她会掉进去"， 是过度惊吓。② 已经身处危险格时**不改变原有语义**（调用方各自判断），本方法只管"这一格 算不算危险"。③ 扫描是纯读方块，9 次 {@code getBlockState}；调用方本来就有节流 （{@code lavaScanCooldown} / 飞行那条的 REACH 采样），开销可忽略。 @param radius 水平扩展半径（格）：传 0 = 退化成 {@link #cellDangerous}（旧行为）。 女仆按碰撞箱取 {@code ceil(bbWidth / 2)}（0.6 → 1）。
     */
    public static boolean cellDangerousBoxed(BlockGetter level, int x, int y, int z, int radius) {
        if (radius <= 0) {
            return cellDangerous(level, x, y, z);
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (cellDangerous(level, x + dx, y, z + dz)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 女仆碰撞箱宽度对应的水平扩展半径（格）：0.6 → 1（见 {@link #cellDangerousBoxed}） */
    public static int boxRadius(double bbWidth) {
        double half = bbWidth * 0.5;
        int r = (int) Math.ceil(half - 1.0E-6);
        return Math.max(0, Math.min(2, r));
    }

    private static String idOf(BlockGetter level, int x, int y, int z) {
        BlockState st = level.getBlockState(new BlockPos(x, y, z));
        ResourceLocation rl = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock());
        return rl == null ? "" : rl.toString();
    }

    /** 危险集合（懒构建；配置列表实例变化即重建）——缓存【注册名字符串】，
     *  与 isDanger/idIn 的 contains(rl.toString()) 查询口径一致 */
    private static Set<String> set() {
        List<? extends String> list = MaidSmartConfig.MISC_DANGER_BLOCKS.get();
        Set<String> local = cache;
        if (local != null && cacheKey == list) {
            return local;
        }
        Set<String> out = new HashSet<>();
        for (String s : list) {
            try {
                // 1.20.1 无 ResourceLocation.parse（1.20.5+ 才有），用构造器
                Block b = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(ResourceLocation.parse(s));
                if (b != null) {
                    out.add(s); // 存配置里的注册名字符串（原样）
                }
            } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("DangerBlocks", ignored);
            }
        }
        synchronized (DangerBlocks.class) {
            cache = out;
            cacheKey = list;
        }
        return out;
    }
}
