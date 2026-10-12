package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 采集类 AI 行为的共享基类（挖矿 / 伐木）：承接两行为**逐字相同**的静态数据表与纯工具。
 */
public abstract class MaidWorkBehavior extends Behavior<EntityMaid> {

    /** 共享构造：与两个子类原来的 super(emptyMap, MAX, MAX) 完全一致（无限时长）。 */
    protected MaidWorkBehavior() {
        super(java.util.Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /** v1.5.47：废石白名单（丢弃判定用；注册名 path） */
    protected static final java.util.Set<String> JUNK_STONES = java.util.Set.of(
            "stone", "cobblestone", "deepslate", "cobbled_deepslate", "granite", "diorite",
            "andesite", "tuff", "gravel", "dirt", "netherrack", "blackstone", "basalt",
            "soul_sand", "soul_soil", "magma_block", "calcite", "dripstone_block", "sandstone",
            "red_sandstone");
    /**
     * v1.5.47：可开路方块白名单（穿透挖掘只挖这些，不碰箱子/机器等）。
     * v1.5.87 细分：石头类（需镐，耗耐久开路）+ 徒手可挖软方块（草/土/沙，开路不耗镐子耐久）。
     * 名单之外的实心挡路（箱子/机器/基岩等）→ 报点 + 弃置该矿，绝不隔墙挖矿。
     */
    protected static final java.util.Set<String> OPEN_BREAKABLE = new java.util.HashSet<>(JUNK_STONES);

    static {
        OPEN_BREAKABLE.add("grass_block");
        OPEN_BREAKABLE.add("dirt");
        OPEN_BREAKABLE.add("coarse_dirt");
        OPEN_BREAKABLE.add("rooted_dirt");
        OPEN_BREAKABLE.add("podzol");
        OPEN_BREAKABLE.add("mycelium");
        OPEN_BREAKABLE.add("sand");
        OPEN_BREAKABLE.add("red_sand");
        OPEN_BREAKABLE.add("clay");
        OPEN_BREAKABLE.add("mud");
        OPEN_BREAKABLE.add("moss_block");
        OPEN_BREAKABLE.add("snow_block");
        // v1.5.100c：批量加入自然生成、硬度相近的方块（原木/菌柄/去皮/蘑菇/
        // 南瓜西瓜/冰/苔石/珊瑚）——女仆挖矿遇到不再当硬挡路报点弃置。
        // 只进开路白名单，不进 JUNK_STONES（挖出的原木等不是废石，保留）
        OPEN_BREAKABLE.add("oak_log");
        OPEN_BREAKABLE.add("spruce_log");
        OPEN_BREAKABLE.add("birch_log");
        OPEN_BREAKABLE.add("jungle_log");
        OPEN_BREAKABLE.add("acacia_log");
        OPEN_BREAKABLE.add("dark_oak_log");
        OPEN_BREAKABLE.add("mangrove_log");
        OPEN_BREAKABLE.add("cherry_log");
        OPEN_BREAKABLE.add("crimson_stem");
        OPEN_BREAKABLE.add("warped_stem");
        OPEN_BREAKABLE.add("bamboo_block");
        OPEN_BREAKABLE.add("stripped_oak_log");
        OPEN_BREAKABLE.add("stripped_spruce_log");
        OPEN_BREAKABLE.add("stripped_birch_log");
        OPEN_BREAKABLE.add("stripped_jungle_log");
        OPEN_BREAKABLE.add("stripped_acacia_log");
        OPEN_BREAKABLE.add("stripped_dark_oak_log");
        OPEN_BREAKABLE.add("stripped_mangrove_log");
        OPEN_BREAKABLE.add("stripped_cherry_log");
        OPEN_BREAKABLE.add("stripped_crimson_stem");
        OPEN_BREAKABLE.add("stripped_warped_stem");
        OPEN_BREAKABLE.add("brown_mushroom_block");
        OPEN_BREAKABLE.add("red_mushroom_block");
        OPEN_BREAKABLE.add("mushroom_stem");
        OPEN_BREAKABLE.add("pumpkin");
        OPEN_BREAKABLE.add("melon");
        OPEN_BREAKABLE.add("ice");
        OPEN_BREAKABLE.add("packed_ice");
        OPEN_BREAKABLE.add("mossy_cobblestone");
        OPEN_BREAKABLE.add("mossy_stone_bricks");
        OPEN_BREAKABLE.add("tube_coral_block");
        OPEN_BREAKABLE.add("brain_coral_block");
        OPEN_BREAKABLE.add("bubble_coral_block");
        OPEN_BREAKABLE.add("fire_coral_block");
        OPEN_BREAKABLE.add("horn_coral_block");
        OPEN_BREAKABLE.add("dead_tube_coral_block");
        OPEN_BREAKABLE.add("dead_brain_coral_block");
        OPEN_BREAKABLE.add("dead_bubble_coral_block");
        OPEN_BREAKABLE.add("dead_fire_coral_block");
        OPEN_BREAKABLE.add("dead_horn_coral_block");
        // v1.5.102c：石质方块族批量进开路白名单——地表/建筑常见的石头变体
        // （石砖/磨制/深板岩砖/砂岩切制/石英等）不再当硬挡路报点弃置
        OPEN_BREAKABLE.add("smooth_stone");
        OPEN_BREAKABLE.add("stone_bricks");
        OPEN_BREAKABLE.add("cracked_stone_bricks");
        OPEN_BREAKABLE.add("chiseled_stone_bricks");
        OPEN_BREAKABLE.add("polished_granite");
        OPEN_BREAKABLE.add("polished_diorite");
        OPEN_BREAKABLE.add("polished_andesite");
        OPEN_BREAKABLE.add("deepslate_bricks");
        OPEN_BREAKABLE.add("cracked_deepslate_bricks");
        OPEN_BREAKABLE.add("deepslate_tiles");
        OPEN_BREAKABLE.add("cracked_deepslate_tiles");
        OPEN_BREAKABLE.add("polished_deepslate");
        OPEN_BREAKABLE.add("chiseled_deepslate");
        OPEN_BREAKABLE.add("polished_basalt");
        OPEN_BREAKABLE.add("polished_blackstone");
        OPEN_BREAKABLE.add("polished_blackstone_bricks");
        OPEN_BREAKABLE.add("cracked_polished_blackstone_bricks");
        OPEN_BREAKABLE.add("chiseled_polished_blackstone");
        OPEN_BREAKABLE.add("smooth_sandstone");
        OPEN_BREAKABLE.add("cut_sandstone");
        OPEN_BREAKABLE.add("chiseled_sandstone");
        OPEN_BREAKABLE.add("smooth_red_sandstone");
        OPEN_BREAKABLE.add("cut_red_sandstone");
        OPEN_BREAKABLE.add("chiseled_red_sandstone");
        OPEN_BREAKABLE.add("quartz_block");
        OPEN_BREAKABLE.add("smooth_quartz");
        OPEN_BREAKABLE.add("chiseled_quartz_block");
        OPEN_BREAKABLE.add("quartz_bricks");
        OPEN_BREAKABLE.add("prismarine");
        OPEN_BREAKABLE.add("dark_prismarine");
        OPEN_BREAKABLE.add("prismarine_bricks");
        OPEN_BREAKABLE.add("purpur_block");
        OPEN_BREAKABLE.add("purpur_pillar");
        OPEN_BREAKABLE.add("end_stone");
        OPEN_BREAKABLE.add("end_stone_bricks");
        OPEN_BREAKABLE.add("nether_bricks");
        OPEN_BREAKABLE.add("red_nether_bricks");
        OPEN_BREAKABLE.add("cracked_nether_bricks");
        OPEN_BREAKABLE.add("chiseled_nether_bricks");
        OPEN_BREAKABLE.add("packed_mud");
        OPEN_BREAKABLE.add("mud_bricks");
    }
    /** v1.5.102c：报点用中文方块名（矿石/石头/常见挡路块；未知回退英文 path） */
    protected static final java.util.Map<String, String> BLOCK_CN = new java.util.HashMap<>();

    static {
        BLOCK_CN.put("stone", "石头");
        BLOCK_CN.put("cobblestone", "圆石");
        BLOCK_CN.put("deepslate", "深板岩");
        BLOCK_CN.put("cobbled_deepslate", "深板岩圆石");
        BLOCK_CN.put("granite", "花岗岩");
        BLOCK_CN.put("diorite", "闪长岩");
        BLOCK_CN.put("andesite", "安山岩");
        BLOCK_CN.put("tuff", "凝灰岩");
        BLOCK_CN.put("smooth_stone", "平滑石头");
        BLOCK_CN.put("stone_bricks", "石砖");
        BLOCK_CN.put("dirt", "泥土");
        BLOCK_CN.put("coarse_dirt", "砂土");
        BLOCK_CN.put("sand", "沙子");
        BLOCK_CN.put("red_sand", "红沙");
        BLOCK_CN.put("gravel", "沙砾");
        BLOCK_CN.put("clay", "黏土");
        BLOCK_CN.put("mud", "泥巴");
        BLOCK_CN.put("grass_block", "草方块");
        BLOCK_CN.put("bedrock", "基岩");
        BLOCK_CN.put("obsidian", "黑曜石");
        BLOCK_CN.put("water", "水");
        BLOCK_CN.put("lava", "岩浆");
        BLOCK_CN.put("chest", "箱子");
        BLOCK_CN.put("barrel", "木桶");
        BLOCK_CN.put("furnace", "熔炉");
        BLOCK_CN.put("crafting_table", "工作台");
        BLOCK_CN.put("netherrack", "下界岩");
        BLOCK_CN.put("blackstone", "黑石");
        BLOCK_CN.put("basalt", "玄武岩");
        BLOCK_CN.put("magma_block", "岩浆块");
        BLOCK_CN.put("soul_sand", "灵魂沙");
        BLOCK_CN.put("soul_soil", "灵魂土");
        BLOCK_CN.put("calcite", "方解石");
        BLOCK_CN.put("dripstone_block", "滴水石块");
        BLOCK_CN.put("sandstone", "砂岩");
        BLOCK_CN.put("red_sandstone", "红砂岩");
        BLOCK_CN.put("quartz_block", "石英块");
        // 矿石（跳过/稀有播报用）
        BLOCK_CN.put("diamond_ore", "钻石矿石");
        BLOCK_CN.put("deepslate_diamond_ore", "深层钻石矿石");
        BLOCK_CN.put("emerald_ore", "绿宝石矿石");
        BLOCK_CN.put("deepslate_emerald_ore", "深层绿宝石矿石");
        BLOCK_CN.put("gold_ore", "金矿石");
        BLOCK_CN.put("deepslate_gold_ore", "深层金矿石");
        BLOCK_CN.put("nether_gold_ore", "下界金矿石");
        BLOCK_CN.put("iron_ore", "铁矿石");
        BLOCK_CN.put("deepslate_iron_ore", "深层铁矿石");
        BLOCK_CN.put("copper_ore", "铜矿石");
        BLOCK_CN.put("deepslate_copper_ore", "深层铜矿石");
        BLOCK_CN.put("redstone_ore", "红石矿石");
        BLOCK_CN.put("deepslate_redstone_ore", "深层红石矿石");
        BLOCK_CN.put("lapis_ore", "青金石矿石");
        BLOCK_CN.put("deepslate_lapis_ore", "深层青金石矿石");
        BLOCK_CN.put("coal_ore", "煤矿石");
        BLOCK_CN.put("deepslate_coal_ore", "深层煤矿石");
        BLOCK_CN.put("nether_quartz_ore", "下界石英矿石");
        BLOCK_CN.put("ancient_debris", "远古残骸");
        BLOCK_CN.put("amethyst_block", "紫水晶块");
    }
    /** v1.1.0 实测一百一十九：目标方块 AABB 上离女仆最近点的距离平方（与伐木同款）。
     *  旧版取方块中心距离——垂直边界场景（站坑里挖正上方矿石/头顶矿）中心恰好压
     *  在伸手边界上，水平偏移 0.1 格就够不着 → 永远进搭路分支 → 冻结点。最近点
     *  判定后正上方矿底面稳定够得着。 */
    protected static double distSqToBlock(EntityMaid maid, BlockPos pos) {
        double x = Math.max(pos.getX(), Math.min(maid.getX(), pos.getX() + 1));
        double y = Math.max(pos.getY(), Math.min(maid.getY(), pos.getY() + 1));
        double z = Math.max(pos.getZ(), Math.min(maid.getZ(), pos.getZ() + 1));
        double dx = maid.getX() - x;
        double dy = maid.getY() - y;
        double dz = maid.getZ() - z;
        return dx * dx + dy * dy + dz * dz;
    }
    /** v1.5.102c：方块注册名 path → 中文名。v1.5.140：未知方块回退改用 BlueprintLib
     *  cnName（CN_NAMES 精确表 + 颜色/材质规则兜底，mod 方块也尽量出中文——
     *  旧版直接回退英文 path，反馈"阻挡汇报用英文字符"） */
    protected static String blockCnName(net.minecraft.world.level.block.Block block) {
        net.minecraft.resources.ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
        if (key == null) {
            return "方块";
        }
        String cn = com.maidsmart.build.BlueprintLib.cnName(key.toString());
        return cn.isEmpty() ? key.getPath() : cn;
    }
    /**
     * 开路不耗镐子耐久、穿透预算不计阻挡；硬方块（矿/石头/箱子/基岩）反之。
     */
    /**
     * 旧判定 hardness ≤ 1 会把低硬度的 mod 矿物（如匠魂/魔法 mod 的 1 硬度矿石）
     * 误判为"软方块"→ softNoDurability=true 时挖矿物也不扣耐久（反馈
     * "就算挖的是矿物也不会掉耐久"的根因）。改为按"镐是否为正确工具"判定： 镐是正确工具的方块（原版+mod 全部矿物/石头/深板岩，无论硬度）一律算硬，必扣。
     */
    protected static boolean isSoft(ItemStack pick, BlockState st) {
        if (pick.isEmpty() || !(pick.getItem() instanceof net.minecraft.world.item.DiggerItem d)) {
            return true; // 徒手/非镐 → 视为软（软方块开路路径，不扣镐耐久）
        }
        return !d.isCorrectToolForDrops(pick, st); // 镐非正确工具 = 徒手可挖的软方块
    }
    /**
     * v1.5.85：当前主手是否能挖这个方块（正确工具判定，对齐玩家破坏判定）。
     * 矿都需要对应等级的镐子：非镐工具 / 徒手 / 等级不够（DiggerItem.m_8096_ 检查
     * needs_stone/iron/diamond_tool tag）都返回 false → 该矿被跳过。
     */
    protected static boolean canHarvest(EntityMaid maid, BlockState state) {
        ItemStack tool = maid.getMainHandItem();
        if (tool.isEmpty() || !(tool.getItem() instanceof net.minecraft.world.item.DiggerItem digger)) {
            return false;
        }
        return digger.isCorrectToolForDrops(tool, state);
    }
}
