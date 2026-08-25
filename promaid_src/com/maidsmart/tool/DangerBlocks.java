package com.maidsmart.tool;

import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * v1.1.0 实测八十九/九十：危险方块表共享工具。
 *
 * 配置面板杂项「dangerBlocks」（注册名列表）→ Block 集合缓存（按列表实例同一性
 * 失效重建）。两处消费方：
 * - MaidDangerPathMixin（寻路层：节点改判 BLOCKED，绕开危险）；
 * - DangerEscapeHandler（险境脱离：已身处危险方块上时挪到最近安全格）。
 *
 * 三格判定语义：站立格本体命中 / 脚下方块命中（站上去就出事）/ 头顶一格为
 * 灼烧型（火·灵魂火·岩浆——穿行会烧），任一命中即视为危险。
 */
public final class DangerBlocks {

    private static volatile Set<Block> cache = null;
    private static volatile List<? extends String> cacheKey = null;

    private DangerBlocks() {
    }

    /** 总开关（dangerAvoid；配置未加载等异常时按关闭处理） */
    public static boolean enabled() {
        try {
            return MaidSmartConfig.MISC_DANGER_AVOID.get();
        } catch (Exception e) {
            return false;
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
            ResourceLocation rl = ForgeRegistries.BLOCKS.getKey(b);
            return rl != null && set().contains(rl.toString());
        } catch (Exception e) {
            return false;
        }
    }

    /** 该坐标方块注册名是否在危险表中 */
    public static boolean idIn(BlockGetter level, int x, int y, int z) {
        try {
            BlockState st = level.m_8055_(new BlockPos(x, y, z));
            ResourceLocation rl = ForgeRegistries.BLOCKS.getKey(st.m_60734_());
            if (rl == null) {
                return false;
            }
            return set().contains(rl.toString());
        } catch (Exception e) {
            return false;
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
        String above = idOf(level, x, y + 1, z);
        return above.equals("minecraft:fire") || above.equals("minecraft:soul_fire")
                || above.equals("minecraft:lava");
    }

    private static String idOf(BlockGetter level, int x, int y, int z) {
        BlockState st = level.m_8055_(new BlockPos(x, y, z));
        ResourceLocation rl = ForgeRegistries.BLOCKS.getKey(st.m_60734_());
        return rl == null ? "" : rl.toString();
    }

    /** 危险集合（懒构建；配置列表实例变化即重建） */
    private static Set<Block> set() {
        List<? extends String> list = MaidSmartConfig.MISC_DANGER_BLOCKS.get();
        Set<Block> local = cache;
        if (local != null && cacheKey == list) {
            return local;
        }
        Set<Block> out = new HashSet<>();
        for (String s : list) {
            try {
                // 1.20.1 无 ResourceLocation.parse（1.20.5+ 才有），用构造器
                Block b = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(s));
                if (b != null) {
                    out.add(b);
                }
            } catch (Exception ignored) {
            }
        }
        synchronized (DangerBlocks.class) {
            cache = out;
            cacheKey = list;
        }
        return out;
    }
}
