package com.maidsmart.schedule;

import com.github.tartaricacid.touhoulittlemaid.api.task.IFarmTask;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashSet;
import java.util.Set;

/**
 * 目标任务的"有活可干"可用性检测（v1.1.0 实测一百三十三，借鉴 TLM-Sincerely 的
 * 环境驱动探测思想，做了务实裁剪）。
 *
 * 排班是【按时间】驱动：该时段该上什么任务由日程表说了算。但直接把女仆切到一个
 * "现在根本没事可做"的任务上，女仆就会原地呆站，表现为"排班失效"。所以段应用前
 * 先问一句：目标现在真的能干活吗？
 *
 * 两条分层判定：
 *  ① 硬闸（始终执行）：{@code IMaidTask.isEnable(maid)} —— TLM 任务自己的"当前
 *     可用"开关（钓鱼没杆 / 任务被关 / 配置不满足等），false 直接判不可用。
 *  ② 软探测（按任务类型，尽力而为）：只在条件明确、成本可控时做——
 *     mine      附近有没有可挖矿（mine.oreValues 表）
 *     woodcut   附近有没有可砍木材（wood.values + 原版 logs/bamboo_blocks tag）
 *     cook      附近有没有熔炉
 *     brew      附近有没有酿造台
 *     farm      附近有没有可收作物 / 可种空地（IFarmTask 接口判定）
 *
 * 其余任务（战斗 / 待机 / 跟随 / 第三方附属任务）不做软探测，只查 isEnable——
 * 战斗可能是"夜间守家"这类本意就歇着压阵的安排，第三方任务我们无从判别，
 * 一律保守放行，绝不误伤其它附属工作模式（用户明确要求兼容）。
 */
public final class ScheduleTaskAvailability {
    private static final ResourceLocation MINE = ResourceLocation.parse("maid_smart:mine");
    private static final ResourceLocation WOOD = ResourceLocation.parse("maid_smart:woodcut");
    private static final ResourceLocation COOK = ResourceLocation.parse("maid_smart:cook");
    private static final ResourceLocation BREW = ResourceLocation.parse("maid_smart:brew");

    private static final TagKey<Block> LOGS_TAG = BlockTags.create(ResourceLocation.parse("minecraft:logs"));
    private static final TagKey<Block> BAMBOO_TAG = BlockTags.create(ResourceLocation.parse("minecraft:bamboo_blocks"));

    /** 农场软探测的水平半宽（以女仆当前脚下方为中心，做一次粗扫即可） */
    private static final int FARM_RADIUS = 8;

    private ScheduleTaskAvailability() {
    }

    /**
     * 目标任务当前是否有活可干。任何无法确定的场景一律返回 true（保守放行——
     * 宁可切过去让女仆自己兜着，也不误伤第三方/探测失败的任务）。
     */
    public static boolean isAvailable(EntityMaid maid, IMaidTask task) {
        if (task == null) {
            return false;
        }
        // ① 硬闸：TLM 任务自己的可用开关
        try {
            if (!task.isEnable(maid)) {
                return false;
            }
        } catch (Throwable t) {
            return false; // isEnable 抛异常视为不可用（未知任务宁可不切）
        }
        ResourceLocation uid = task.getUid();
        if (uid == null) {
            return true; // 无法按 UID 分派，only trust isEnable
        }
        try {
            // ② 软探测：只对本模组自己的生产任务 + TLM 农场做"有事可干"检查
            if (MINE.equals(uid)) {
                return hasOreInRange(maid);
            }
            if (WOOD.equals(uid)) {
                return hasWoodInRange(maid);
            }
            if (COOK.equals(uid)) {
                return hasBlockInRange(maid, AbstractFurnaceBlock.class,
                        com.maidsmart.config.MaidSmartConfig.MISC_COOK_RADIUS.get(),
                        com.maidsmart.config.MaidSmartConfig.MISC_VERTICAL_RANGE.get());
            }
            if (BREW.equals(uid)) {
                return hasBlockInRange(maid, BrewingStandBlock.class,
                        com.maidsmart.config.MaidSmartConfig.MISC_BREW_RADIUS.get(),
                        com.maidsmart.config.MaidSmartConfig.MISC_VERTICAL_RANGE.get());
            }
            if (task instanceof IFarmTask farm) {
                return hasFarmWork(maid, farm);
            }
        } catch (Throwable t) {
            return true; // 探测异常视为"无法判定"，保守放行
        }
        return true; // 战斗/待机/跟随/第三方任务：只查 isEnable
    }

    /* ---------------- 软探测实现 ---------------- */

    private static boolean hasOreInRange(EntityMaid maid) {
        Set<String> ores = keySetOf(com.maidsmart.config.MaidSmartConfig.MINE_ORE_VALUES.get());
        if (ores.isEmpty()) {
            return true; // 挖矿表为空（加载时序/玩家清空）：无法判定，放行
        }
        int r = Math.max(1, com.maidsmart.config.MaidSmartConfig.MINE_SEARCH_RADIUS.get());
        int down = Math.max(0, com.maidsmart.config.MaidSmartConfig.MINE_DOWN_RANGE.get());
        int up = Math.max(0, com.maidsmart.config.MaidSmartConfig.MINE_UP_RANGE.get());
        ServerLevel level = serverLevel(maid);
        if (level == null) {
            return true;
        }
        BlockPos c = maid.m_20183_();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -down; dy <= up; dy++) {
                    BlockPos p = c.m_7918_(dx, dy, dz);
                    if (!level.m_46749_(p)) {
                        continue; // 未加载不触发加载
                    }
                    Block b = level.m_8055_(p).m_60734_();
                    if (b != null && ores.contains(keyOf(b))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasWoodInRange(EntityMaid maid) {
        Set<String> woods = keySetOf(com.maidsmart.config.MaidSmartConfig.WOOD_VALUES.get());
        boolean tagAuto = com.maidsmart.config.MaidSmartConfig.WOOD_TAG_AUTO.get();
        if (woods.isEmpty() && !tagAuto) {
            return true; // 既无名单、又关 tag 自动识别：无法判定，放行
        }
        int r = Math.max(1, com.maidsmart.config.MaidSmartConfig.WOOD_SEARCH_RADIUS.get());
        int down = Math.max(0, com.maidsmart.config.MaidSmartConfig.WOOD_DOWN_RANGE.get());
        int up = Math.max(0, com.maidsmart.config.MaidSmartConfig.WOOD_UP_RANGE.get());
        ServerLevel level = serverLevel(maid);
        if (level == null) {
            return true;
        }
        BlockPos c = maid.m_20183_();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -down; dy <= up; dy++) {
                    BlockPos p = c.m_7918_(dx, dy, dz);
                    if (!level.m_46749_(p)) {
                        continue;
                    }
                    BlockState st = level.m_8055_(p);
                    if (st.m_60795_()) {
                        continue;
                    }
                    Block b = st.m_60734_();
                    if (woods.contains(keyOf(b))
                            || (tagAuto && (st.m_204336_(LOGS_TAG) || st.m_204336_(BAMBOO_TAG)))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasBlockInRange(EntityMaid maid, Class<? extends Block> blockClass,
                                           int radius, int vertical) {
        ServerLevel level = serverLevel(maid);
        if (level == null) {
            return true;
        }
        int r = Math.max(1, radius);
        int v = Math.max(0, vertical);
        BlockPos c = maid.m_20183_();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -v; dy <= v; dy++) {
                    BlockPos p = c.m_7918_(dx, dy, dz);
                    if (!level.m_46749_(p)) {
                        continue;
                    }
                    Block b = level.m_8055_(p).m_60734_();
                    if (b != null && blockClass.isInstance(b)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasFarmWork(EntityMaid maid, IFarmTask farm) {
        ServerLevel level = serverLevel(maid);
        if (level == null) {
            return true;
        }
        java.util.List<ItemStack> seeds = collectSeeds(maid, farm);
        BlockPos c = maid.m_20183_();
        for (int dx = -FARM_RADIUS; dx <= FARM_RADIUS; dx++) {
            for (int dz = -FARM_RADIUS; dz <= FARM_RADIUS; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos base = c.m_7918_(dx, dy, dz);
                    if (!level.m_46749_(base)) {
                        continue;
                    }
                    BlockPos crop = c.m_7918_(dx, dy + 1, dz);
                    try {
                        if (farm.canHarvest(maid, crop, level.m_8055_(crop))) {
                            return true;
                        }
                        BlockState baseState = level.m_8055_(base);
                        if (!seeds.isEmpty()) {
                            for (ItemStack seed : seeds) {
                                if (farm.canPlant(maid, base, baseState, seed)) {
                                    return true;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                        // 单格异常不影响继续扫
                    }
                }
            }
        }
        return false;
    }

    private static java.util.List<ItemStack> collectSeeds(EntityMaid maid, IFarmTask farm) {
        java.util.List<ItemStack> seeds = new java.util.ArrayList<>();
        try {
            IItemHandler inv = maid.getAvailableInv(true);
            for (int slot = 0; slot < inv.getSlots(); slot++) {
                ItemStack stack = inv.getStackInSlot(slot);
                if (!stack.m_41619_() && farm.isSeed(stack)) {
                    seeds.add(stack.m_41777_()); // 副本，防后续同刻被清
                }
            }
        } catch (Throwable ignored) {
        }
        return seeds;
    }

    private static ServerLevel serverLevel(EntityMaid maid) {
        return maid.m_9236_() instanceof ServerLevel sl ? sl : null;
    }

    /** 配置表 "modid:block=value" → "modid:block" 键集合（忽略非法行） */
    private static Set<String> keySetOf(java.util.Collection<? extends String> lines) {
        Set<String> keys = new HashSet<>();
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String k = line;
            int eq = k.indexOf('=');
            if (eq >= 0) {
                k = k.substring(0, eq);
            }
            k = k.trim();
            if (!k.isEmpty()) {
                keys.add(k);
            }
        }
        return keys;
    }

    private static String keyOf(Block b) {
        try {
            ResourceLocation rl = ForgeRegistries.BLOCKS.getKey(b);
            return rl == null ? "" : rl.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}