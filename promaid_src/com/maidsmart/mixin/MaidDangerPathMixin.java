package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * v1.1.0 实测八十九：女仆寻路【危险方块避让】——用户需求："让他可以自己避开
 * 一些危险的方块"。
 *
 * 原理：注入原版 WalkNodeEvaluator 的逐节点方块路径类型评估（m_7209_ =
 * getBlockPathType(BlockGetter, x, y, z, Mob)，带被寻路生物上下文）。当被寻路的
 * 生物是女仆、且目标格子命中危险表（岩浆/火/灵魂火/岩浆块/仙人掌/甜浆果丛/
 * 凋零玫瑰/细雪/石笋，可在配置面板增删）时，把评估结果强制改判为 BLOCKED——
 * 该节点直接从寻路图中剔除，女仆规划路径时会绕开岩浆湖岸边、火焰地面等，
 * 宁可停下等 TLM 的过远传送兜底也不往里走。
 *
 * 三个细节：
 * - 【起点格不拦】女仆已经身处险境时（掉进岩浆边、着火），必须允许"逃出来"
 *   的路径存在，否则她会在原地冻死；
 * - 【三格判定】站立格本体 / 脚下方块（岩浆·岩浆块·火·细雪等站上去就出事的）
 *   / 头顶一格的灼烧型方块（火·岩浆），任一命中即拦；
 * - 【仅女仆生效】mob 参数 instanceof 过滤，其余生物寻路零影响；
 *   配置关闭（dangerAvoid=false）时整体旁路，方法体第一行返回。
 *
 * 方法名用 SRG（m_7209_）：本项目对原版类编译于 SRG 映射（HomingPotionMixin
 * 的 m_8119_ 同款先例），运行期即为正确名字。
 */
@Mixin(WalkNodeEvaluator.class)
public abstract class MaidDangerPathMixin {

    /** 危险方块集合缓存（按配置列表引用同一性失效重建） */
    private static volatile Set<Block> dangerCache = null;
    private static volatile List<? extends String> dangerCacheKey = null;

    @Inject(method = "m_7209_", at = @At("RETURN"), cancellable = true)
    private void maidsmart$avoidDangerBlocks(BlockGetter level, int x, int y, int z, Mob mob,
                                             CallbackInfoReturnable<BlockPathTypes> cir) {
        if (!(mob instanceof EntityMaid)) {
            return; // 仅女仆生效
        }
        try {
            if (!MaidSmartConfig.MISC_DANGER_AVOID.get()) {
                return; // 开关关闭：整体旁路
            }
            BlockPos mobPos = mob.m_20183_();
            // 起点格不拦——身处险境时必须保留"逃出来"的路径
            if (mobPos.m_123341_() == x && mobPos.m_123342_() == y && mobPos.m_123343_() == z) {
                return;
            }
            BlockPathTypes cur = cir.getReturnValue();
            if (cur == BlockPathTypes.BLOCKED) {
                return; // 本来就不可通行
            }
            if (isDangerousCell(level, x, y, z)) {
                cir.setReturnValue(BlockPathTypes.BLOCKED);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 三格判定：站立格本体命中危险表 / 脚下方块命中危险表（站上去就出事）/
     * 头顶一格为灼烧型方块（火·岩浆——穿行会烧）。任一命中即视为危险。
     */
    private static boolean isDangerousCell(BlockGetter level, int x, int y, int z) {
        if (inDangerList(level, x, y, z)) {
            return true;
        }
        if (inDangerList(level, x, y - 1, z)) {
            return true; // 脚下是危险方块（岩浆面/岩浆块/火/细雪顶…）
        }
        // 头顶灼烧型：火/灵魂火/岩浆（沿固定四种子集判断，不受配置增删影响）
        String above = idOf(level, x, y + 1, z);
        return above.equals("minecraft:fire") || above.equals("minecraft:soul_fire")
                || above.equals("minecraft:lava");
    }

    /** 该坐标的方块注册名是否在配置的危险表中 */
    private static boolean inDangerList(BlockGetter level, int x, int y, int z) {
        String id = idOf(level, x, y, z);
        if (id.isEmpty()) {
            return false;
        }
        return dangerSet().contains(id);
    }

    private static String idOf(BlockGetter level, int x, int y, int z) {
        BlockState st = level.m_8055_(new BlockPos(x, y, z));
        Block b = st.m_60734_();
        ResourceLocation rl = ForgeRegistries.BLOCKS.getKey(b);
        return rl == null ? "" : rl.toString();
    }

    /** 危险方块集合（懒构建；配置列表实例变化即重建） */
    private static Set<Block> dangerSet() {
        List<? extends String> list = MaidSmartConfig.MISC_DANGER_BLOCKS.get();
        Set<Block> local = dangerCache;
        if (local != null && dangerCacheKey == list) {
            return local;
        }
        Set<Block> out = new HashSet<>();
        for (String s : list) {
            try {
                // v1.1.0：1.20.1 无 ResourceLocation.parse（1.20.5+ 才有），用构造器
                Block b = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(s));
                if (b != null) {
                    out.add(b);
                }
            } catch (Exception ignored) {
            }
        }
        synchronized (MaidDangerPathMixin.class) {
            dangerCache = out;
            dangerCacheKey = list;
        }
        return out;
    }
}
