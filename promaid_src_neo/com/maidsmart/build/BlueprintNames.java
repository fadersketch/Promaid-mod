package com.maidsmart.build;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 方块/物品中文名（v1.2.4 从 BlueprintLib 拆出）。
 * 
 * 显式中文名表 CN_NAMES（约 180 条）+ 颜色/基名拼装表，产出界面与提示里的中文名。
 */
public final class BlueprintNames {
    private BlueprintNames() {
    }

    private static final Map<String, String> CN_NAMES = new HashMap<>();

    private static void cn(String id, String name) {
        CN_NAMES.put(id, name);
    }

    static {
        // —— 木材（原木/木板/树叶/门/楼梯/台阶/栅栏/木材）——
        cn("minecraft:oak_log", "橡木原木"); cn("minecraft:spruce_log", "云杉原木");
        cn("minecraft:birch_log", "白桦原木"); cn("minecraft:jungle_log", "丛林原木");
        cn("minecraft:acacia_log", "金合欢原木"); cn("minecraft:dark_oak_log", "深色橡木原木");
        cn("minecraft:cherry_log", "樱花原木"); cn("minecraft:mangrove_log", "红树原木");
        cn("minecraft:oak_wood", "橡木"); cn("minecraft:spruce_wood", "云杉木");
        cn("minecraft:birch_wood", "白桦木"); cn("minecraft:jungle_wood", "丛林木");
        cn("minecraft:acacia_wood", "金合欢木"); cn("minecraft:dark_oak_wood", "深色橡木");
        cn("minecraft:oak_planks", "橡木木板"); cn("minecraft:spruce_planks", "云杉木板");
        cn("minecraft:birch_planks", "白桦木板"); cn("minecraft:jungle_planks", "丛林木板");
        cn("minecraft:acacia_planks", "金合欢木板"); cn("minecraft:dark_oak_planks", "深色橡木板");
        cn("minecraft:cherry_planks", "樱花木板"); cn("minecraft:mangrove_planks", "红树木板");
        cn("minecraft:bamboo_planks", "竹木板"); cn("minecraft:crimson_planks", "绯红木板");
        cn("minecraft:warped_planks", "诡异木板");
        cn("minecraft:oak_leaves", "橡树树叶"); cn("minecraft:dark_oak_leaves", "深色橡树树叶");
        cn("minecraft:birch_leaves", "白桦树叶"); cn("minecraft:jungle_leaves", "丛林树叶");
        cn("minecraft:acacia_leaves", "金合欢树叶"); cn("minecraft:spruce_leaves", "云杉树叶");
        cn("minecraft:oak_door", "橡木门"); cn("minecraft:spruce_door", "云杉木门");
        cn("minecraft:birch_door", "白桦木门"); cn("minecraft:jungle_door", "丛林门");
        cn("minecraft:acacia_door", "金合欢门"); cn("minecraft:dark_oak_door", "深色橡木门");
        cn("minecraft:cherry_door", "樱花木门"); cn("minecraft:mangrove_door", "红树木门");
        cn("minecraft:iron_door", "铁门");
        cn("minecraft:oak_stairs", "橡木楼梯"); cn("minecraft:spruce_stairs", "云杉木楼梯");
        cn("minecraft:birch_stairs", "白桦木楼梯"); cn("minecraft:jungle_stairs", "丛林楼梯");
        cn("minecraft:acacia_stairs", "金合欢楼梯"); cn("minecraft:dark_oak_stairs", "深色橡木楼梯");
        cn("minecraft:oak_slab", "橡木台阶"); cn("minecraft:spruce_slab", "云杉木台阶");
        cn("minecraft:birch_slab", "白桦木台阶"); cn("minecraft:jungle_slab", "丛林台阶");
        cn("minecraft:acacia_slab", "金合欢台阶"); cn("minecraft:dark_oak_slab", "深色橡木台阶");
        cn("minecraft:oak_fence", "橡木栅栏"); cn("minecraft:spruce_fence", "云杉木栅栏");
        cn("minecraft:dark_oak_fence", "深色橡木栅栏"); cn("minecraft:jungle_fence", "丛林栅栏");
        cn("minecraft:acacia_fence", "金合欢栅栏");
        cn("minecraft:oak_fence_gate", "橡木栅栏门"); cn("minecraft:oak_button", "橡木按钮");
        cn("minecraft:oak_pressure_plate", "橡木压力板"); cn("minecraft:oak_sign", "橡木告示牌");
        cn("minecraft:oak_trapdoor", "橡木活板门"); cn("minecraft:iron_trapdoor", "铁活板门");
        // —— 石头/建材 ——
        cn("minecraft:stone", "石头"); cn("minecraft:cobblestone", "圆石");
        cn("minecraft:mossy_cobblestone", "苔石"); cn("minecraft:stone_bricks", "石砖");
        cn("minecraft:stone_brick_slab", "石砖台阶"); cn("minecraft:stone_brick_stairs", "石砖楼梯");
        cn("minecraft:mossy_stone_bricks", "苔石砖"); cn("minecraft:cracked_stone_bricks", "裂纹石砖");
        cn("minecraft:smooth_stone", "平滑石头"); cn("minecraft:stone_slab", "石头台阶");
        cn("minecraft:cobblestone_slab", "圆石台阶"); cn("minecraft:cobblestone_stairs", "圆石楼梯");
        cn("minecraft:cobblestone_wall", "圆石墙"); cn("minecraft:stone_button", "石按钮");
        cn("minecraft:stone_pressure_plate", "石压力板");
        cn("minecraft:deepslate", "深板岩"); cn("minecraft:deepslate_bricks", "深板岩砖");
        cn("minecraft:deepslate_tiles", "深板岩瓦"); cn("minecraft:cracked_deepslate_bricks", "裂纹深板岩砖");
        cn("minecraft:andesite", "安山岩"); cn("minecraft:polished_andesite", "磨制安山岩");
        cn("minecraft:diorite", "闪长岩"); cn("minecraft:granite", "花岗岩");
        cn("minecraft:tuff", "凝灰岩"); cn("minecraft:calcite", "方解石");
        cn("minecraft:sandstone", "砂岩"); cn("minecraft:smooth_sandstone", "平滑砂岩");
        cn("minecraft:chiseled_sandstone", "錾制砂岩"); cn("minecraft:cut_sandstone", "切制砂岩");
        cn("minecraft:sandstone_slab", "砂岩台阶"); cn("minecraft:sandstone_stairs", "砂岩楼梯");
        cn("minecraft:red_sandstone", "红砂岩"); cn("minecraft:red_sandstone_slab", "红砂岩台阶");
        cn("minecraft:red_sandstone_stairs", "红砂岩楼梯");
        cn("minecraft:quartz_block", "石英块"); cn("minecraft:quartz_pillar", "石英柱");
        cn("minecraft:quartz_stairs", "石英楼梯"); cn("minecraft:quartz_slab", "石英台阶");
        cn("minecraft:smooth_quartz", "平滑石英"); cn("minecraft:chiseled_quartz_block", "錾制石英块");
        cn("minecraft:prismarine", "海晶石"); cn("minecraft:dark_prismarine", "暗海晶石");
        cn("minecraft:prismarine_bricks", "海晶石砖"); cn("minecraft:sea_lantern", "海晶灯");
        cn("minecraft:bricks", "红砖块"); cn("minecraft:brick_block", "红砖块");
        cn("minecraft:brick_slab", "红砖台阶"); cn("minecraft:brick_stairs", "红砖楼梯");
        cn("minecraft:nether_brick", "地狱砖"); cn("minecraft:nether_brick_fence", "地狱砖栅栏");
        cn("minecraft:nether_brick_stairs", "地狱砖楼梯"); cn("minecraft:nether_brick_slab", "地狱砖台阶");
        cn("minecraft:end_stone", "末地石"); cn("minecraft:end_stone_bricks", "末地石砖");
        cn("minecraft:purpur_block", "紫珀块"); cn("minecraft:purpur_pillar", "紫珀柱");
        cn("minecraft:purpur_stairs", "紫珀楼梯"); cn("minecraft:purpur_slab", "紫珀台阶");
        cn("minecraft:obsidian", "黑曜石"); cn("minecraft:bedrock", "基岩");
        cn("minecraft:terracotta", "陶瓦");
        cn("minecraft:white_terracotta", "白色陶瓦"); cn("minecraft:orange_terracotta", "橙色陶瓦");
        cn("minecraft:red_terracotta", "红色陶瓦"); cn("minecraft:brown_terracotta", "棕色陶瓦");
        cn("minecraft:black_terracotta", "黑色陶瓦");
        cn("minecraft:white_concrete", "白色混凝土"); cn("minecraft:orange_concrete", "橙色混凝土");
        cn("minecraft:pink_concrete", "粉色混凝土"); cn("minecraft:gray_concrete", "灰色混凝土");
        cn("minecraft:light_gray_concrete", "淡灰色混凝土"); cn("minecraft:lime_concrete", "黄绿色混凝土");
        cn("minecraft:green_concrete", "绿色混凝土"); cn("minecraft:red_concrete", "红色混凝土");
        cn("minecraft:yellow_concrete", "黄色混凝土"); cn("minecraft:black_concrete", "黑色混凝土");
        cn("minecraft:white_wool", "白色羊毛"); cn("minecraft:red_wool", "红色羊毛");
        cn("minecraft:magenta_wool", "品红色羊毛");
        cn("minecraft:glass", "玻璃"); cn("minecraft:glass_pane", "玻璃板");
        cn("minecraft:white_stained_glass", "白色玻璃"); cn("minecraft:black_stained_glass", "黑色玻璃");
        cn("minecraft:blue_stained_glass", "蓝色玻璃"); cn("minecraft:cyan_stained_glass", "青色玻璃");
        cn("minecraft:green_stained_glass", "绿色玻璃"); cn("minecraft:white_stained_glass_pane", "白色玻璃板");
        cn("minecraft:iron_bars", "铁栏杆");
        cn("minecraft:white_bed", "白色床"); cn("minecraft:red_bed", "红色床");
        cn("minecraft:blue_bed", "蓝色床"); cn("minecraft:green_bed", "绿色床");
        cn("minecraft:amethyst_block", "紫水晶块"); cn("minecraft:budding_amethyst", "紫水晶母岩");
        cn("minecraft:dripstone_block", "滴水石块"); cn("minecraft:magma_block", "岩浆块");
        // —— 功能/装饰方块 ——
        cn("minecraft:bookshelf", "书架"); cn("minecraft:crafting_table", "工作台");
        cn("minecraft:furnace", "熔炉"); cn("minecraft:chest", "箱子");
        cn("minecraft:trapped_chest", "陷阱箱"); cn("minecraft:hopper", "漏斗");
        cn("minecraft:dispenser", "发射器"); cn("minecraft:dropper", "投掷器");
        cn("minecraft:brewing_stand", "酿造台"); cn("minecraft:enchanting_table", "附魔台");
        cn("minecraft:anvil", "铁砧"); cn("minecraft:beacon", "信标");
        cn("minecraft:jukebox", "唱片机"); cn("minecraft:note_block", "音符盒");
        cn("minecraft:daylight_detector", "阳光探测器"); cn("minecraft:lever", "拉杆");
        cn("minecraft:redstone_lamp", "红石灯"); cn("minecraft:redstone_block", "红石块");
        cn("minecraft:redstone_torch", "红石火把"); cn("minecraft:redstone_repeater", "红石中继器");
        // v1.5.287：红石机器蓝图用件补全中文名（缺省会显示英文 id）
        cn("minecraft:redstone_wire", "红石粉"); cn("minecraft:repeater", "红石中继器");
        cn("minecraft:comparator", "红石比较器"); cn("minecraft:observer", "观察者");
        cn("minecraft:hopper", "漏斗"); cn("minecraft:sticky_piston", "粘性活塞");
        cn("minecraft:dropper", "投掷器"); cn("minecraft:dispenser", "发射器");
        cn("minecraft:note_block", "音符盒"); cn("minecraft:sugar_cane", "甘蔗");
        cn("minecraft:torch", "火把"); cn("minecraft:lantern", "灯笼");
        cn("minecraft:soul_lantern", "灵魂灯笼"); cn("minecraft:campfire", "营火");
        cn("minecraft:soul_campfire", "灵魂营火"); cn("minecraft:glowstone", "荧石");
        cn("minecraft:jack_o_lantern", "南瓜灯"); cn("minecraft:pumpkin", "南瓜");
        cn("minecraft:melon", "西瓜"); cn("minecraft:hay_block", "干草块");
        cn("minecraft:ladder", "梯子"); cn("minecraft:rail", "铁轨");
        cn("minecraft:detector_rail", "探测铁轨"); cn("minecraft:piston", "活塞");
        cn("minecraft:sticky_piston", "粘性活塞"); cn("minecraft:tnt", "TNT");
        cn("minecraft:sponge", "海绵"); cn("minecraft:cobweb", "蜘蛛网");
        cn("minecraft:ice", "冰"); cn("minecraft:packed_ice", "浮冰");
        cn("minecraft:snow_block", "雪块"); cn("minecraft:snow", "雪");
        cn("minecraft:clay", "黏土"); cn("minecraft:gravel", "沙砾");
        cn("minecraft:sand", "沙子"); cn("minecraft:red_sand", "红沙");
        cn("minecraft:dirt", "泥土"); cn("minecraft:coarse_dirt", "砂土");
        cn("minecraft:grass_block", "草方块"); cn("minecraft:mycelium", "菌丝");
        cn("minecraft:podzol", "灰化土"); cn("minecraft:moss_block", "苔藓块");
        cn("minecraft:rooted_dirt", "缠根泥土"); cn("minecraft:mud", "泥巴");
        cn("minecraft:farmland", "耕地"); cn("minecraft:grass_path", "土径");
        cn("minecraft:water", "水"); cn("minecraft:lava", "岩浆");
        cn("minecraft:fire", "火"); cn("minecraft:water_bucket", "水桶");
        cn("minecraft:lava_bucket", "岩浆桶"); // v1.5.318：材料表/缺料提示用
        cn("minecraft:minecart", "矿车"); // v1.5.320：轰炸机启动工具（完工自动放置）
        cn("minecraft:kelp", "海带"); cn("minecraft:sugar_cane", "甘蔗");
        cn("minecraft:cactus", "仙人掌"); cn("minecraft:lily_pad", "睡莲");
        cn("minecraft:vine", "藤蔓"); cn("minecraft:fern", "蕨类");
        cn("minecraft:tall_grass", "高草丛"); cn("minecraft:dead_bush", "枯死的灌木");
        cn("minecraft:dandelion", "蒲公英"); cn("minecraft:poppy", "虞美人");
        cn("minecraft:sweet_berry_bush", "甜浆果丛"); cn("minecraft:red_mushroom", "红色蘑菇");
        cn("minecraft:brown_mushroom", "棕色蘑菇"); cn("minecraft:flower_pot", "花盆");
        cn("minecraft:nether_wart", "地狱疣"); cn("minecraft:chorus_plant", "紫颂植株");
        cn("minecraft:chorus_flower", "紫颂花"); cn("minecraft:end_rod", "末地烛");
        cn("minecraft:dragon_egg", "龙蛋"); cn("minecraft:spawner", "刷怪笼");
        cn("minecraft:barrier", "屏障"); cn("minecraft:structure_void", "结构空位");
        cn("minecraft:structure_block", "结构方块"); cn("minecraft:jigsaw", "拼图方块");
        cn("minecraft:command_block", "命令方块"); cn("minecraft:chain_command_block", "连锁命令方块");
        cn("minecraft:repeating_command_block", "循环命令方块");
        // —— 矿物/矿石/矿物块 ——
        cn("minecraft:coal_ore", "煤矿石"); cn("minecraft:deepslate_coal_ore", "深层煤矿石");
        cn("minecraft:iron_ore", "铁矿石"); cn("minecraft:deepslate_iron_ore", "深层铁矿石");
        cn("minecraft:gold_ore", "金矿石"); cn("minecraft:deepslate_gold_ore", "深层金矿石");
        cn("minecraft:diamond_ore", "钻石矿石"); cn("minecraft:deepslate_diamond_ore", "深层钻石矿石");
        cn("minecraft:emerald_ore", "绿宝石矿石"); cn("minecraft:deepslate_emerald_ore", "深层绿宝石矿石");
        cn("minecraft:copper_ore", "铜矿石"); cn("minecraft:deepslate_copper_ore", "深层铜矿石");
        cn("minecraft:lapis_ore", "青金石矿石"); cn("minecraft:deepslate_lapis_ore", "深层青金石矿石");
        cn("minecraft:redstone_ore", "红石矿石"); cn("minecraft:deepslate_redstone_ore", "深层红石矿石");
        cn("minecraft:nether_quartz_ore", "下界石英矿石"); cn("minecraft:nether_gold_ore", "下界金矿石");
        cn("minecraft:netherrack", "下界岩"); cn("minecraft:soul_sand", "灵魂沙");
        cn("minecraft:diamond_block", "钻石块"); cn("minecraft:gold_block", "金块");
        cn("minecraft:iron_block", "铁块"); cn("minecraft:emerald_block", "绿宝石块");
        cn("minecraft:coal_block", "煤炭块"); cn("minecraft:lapis_block", "青金石块");
        // —— 常见物品（烹饪/酿造/交付报告）——
        cn("minecraft:diamond", "钻石"); cn("minecraft:coal", "煤炭");
        cn("minecraft:charcoal", "木炭"); cn("minecraft:iron_ingot", "铁锭");
        cn("minecraft:gold_ingot", "金锭"); cn("minecraft:emerald", "绿宝石");
        cn("minecraft:bread", "面包"); cn("minecraft:apple", "苹果");
        cn("minecraft:golden_apple", "金苹果"); cn("minecraft:enchanted_golden_apple", "附魔金苹果");
        cn("minecraft:beef", "生牛肉"); cn("minecraft:porkchop", "生猪排");
        cn("minecraft:chicken", "生鸡肉"); cn("minecraft:mutton", "生羊肉");
        cn("minecraft:rabbit", "生兔肉"); cn("minecraft:cod", "生鳕鱼");
        cn("minecraft:salmon", "生鲑鱼"); cn("minecraft:pufferfish", "河豚");
        cn("minecraft:cooked_beef", "牛排"); cn("minecraft:cooked_porkchop", "熟猪排");
        cn("minecraft:cooked_chicken", "烤鸡"); cn("minecraft:cooked_mutton", "熟羊肉");
        cn("minecraft:cooked_rabbit", "烤兔肉"); cn("minecraft:cooked_cod", "熟鳕鱼");
        cn("minecraft:cooked_salmon", "熟鲑鱼"); cn("minecraft:potato", "马铃薯");
        cn("minecraft:carrot", "胡萝卜"); cn("minecraft:golden_carrot", "金胡萝卜");
        cn("minecraft:glistering_melon_slice", "闪烁的西瓜片");
        cn("minecraft:blaze_powder", "烈焰粉"); cn("minecraft:ghast_tear", "恶魂之泪");
        cn("minecraft:magma_cream", "岩浆膏"); cn("minecraft:phantom_membrane", "幻翼膜");
        cn("minecraft:ender_pearl", "末影珍珠"); cn("minecraft:glass_bottle", "玻璃瓶");
        cn("minecraft:sugar", "糖"); cn("minecraft:rabbit_foot", "兔子脚");
        cn("minecraft:cauldron", "炼药锅"); cn("minecraft:nether_portal", "下界传送门");
        cn("minecraft:end_portal", "末地传送门"); cn("minecraft:end_portal_frame", "末地传送门框架");
        cn("minecraft:end_gateway", "末地折跃门");
    }

    private static final String[][] CN_COLORS = {
            {"light_blue", "淡蓝"}, {"light_gray", "淡灰"}, {"dark_oak", ""}, // dark_oak 是木头名不是颜色
            {"white", "白"}, {"orange", "橙"}, {"magenta", "品红"}, {"yellow", "黄"},
            {"lime", "黄绿"}, {"pink", "粉"}, {"gray", "灰"}, {"cyan", "青"},
            {"purple", "紫"}, {"blue", "蓝"}, {"brown", "棕"}, {"green", "绿"},
            {"red", "红"}, {"black", "黑"},
    };

    private static final String[][] CN_BASE = {
            {"oak", "橡木"}, {"spruce", "云杉木"}, {"birch", "白桦木"}, {"jungle", "丛林木"},
            {"acacia", "金合欢木"}, {"dark_oak", "深色橡木"}, {"cherry", "樱花木"},
            {"mangrove", "红树木"}, {"bamboo", "竹"}, {"crimson", "绯红木"}, {"warped", "诡异木"},
            {"stone", "石头"}, {"cobblestone", "圆石"}, {"deepslate", "深板岩"},
            {"sandstone", "砂岩"}, {"red_sandstone", "红砂岩"}, {"quartz", "石英"},
            {"purpur", "紫珀"}, {"prismarine", "海晶石"}, {"brick", "红砖"},
            {"nether_brick", "地狱砖"}, {"end_stone", "末地石"}, {"stone_brick", "石砖"},
            {"blackstone", "黑石"}, {"polished_blackstone", "磨制黑石"},
            {"concrete", "混凝土"}, {"terracotta", "陶瓦"}, {"wool", "羊毛"},
            {"glass", "玻璃"}, {"stained", "染色"}, {"iron", "铁"}, {"gold", "金"}, {"diamond", "钻石"},
            {"emerald", "绿宝石"}, {"lapis", "青金石"}, {"redstone", "红石"},
            {"coal", "煤炭"}, {"copper", "铜"}, {"snow", "雪"}, {"ice", "冰"},
            {"netherrack", "下界岩"}, {"soul_sand", "灵魂沙"}, {"bedrock", "基岩"},
            {"glowstone", "荧石"}, {"obsidian", "黑曜石"}, {"clay", "黏土"},
            {"gravel", "沙砾"}, {"sand", "沙子"}, {"dirt", "泥土"}, {"log", "原木"},
            {"planks", "木板"}, {"leaves", "树叶"}, {"slab", "台阶"}, {"stairs", "楼梯"},
            {"fence", "栅栏"}, {"trapdoor", "活板门"}, {"door", "门"}, {"wall", "墙"},
            {"ore", "矿石"}, {"block", "块"}, {"bricks", "砖"}, {"brick", "砖"},
            {"lantern", "灯笼"}, {"torch", "火把"}, {"button", "按钮"}, {"sign", "告示牌"},
            {"bed", "床"}, {"carpet", "地毯"}, {"glass_pane", "玻璃板"}, {"bars", "栏杆"},
            // 工具/装备（镐子报告等）
            {"pickaxe", "镐"}, {"axe", "斧"}, {"shovel", "锹"}, {"hoe", "锄"},
            {"sword", "剑"}, {"bow", "弓"}, {"helmet", "头盔"}, {"chestplate", "胸甲"},
            {"leggings", "护腿"}, {"boots", "靴子"}, {"golden", "金"}, {"wooden", "木"},
            {"leather", "皮革"}, {"chainmail", "锁链"}, {"netherite", "下界合金"}, {"elytra", "鞘翅"},
    };

    public static String cnName(String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        String exact = CN_NAMES.get(id);
        if (exact != null) {
            return exact;
        }
        String s = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        if (s.isEmpty()) {
            return id;
        }
        // 颜色前缀（最长优先，dark_oak 除外——它是木头名）
        for (String[] c : CN_COLORS) {
            String prefix = c[0] + "_";
            if (s.startsWith(prefix) && !s.startsWith("dark_oak_")) {
                return c[1] + cnBase(s.substring(prefix.length()));
            }
        }
        String base = cnBase(s);
        return base.equals(s) && id.startsWith("minecraft:") ? s : base;
    }

    private static String cnBase(String s) {
        for (String[] b : CN_BASE) {
            if (s.equals(b[0])) {
                return b[1];
            }
        }
        String bestSuf = null;
        String bestName = null;
        for (String[] b : CN_BASE) {
            String suf = b[0];
            if (suf.length() < 2 || !s.endsWith(suf) || s.equals(suf)) {
                continue;
            }
            if (bestSuf == null || suf.length() > bestSuf.length()) {
                bestSuf = suf;
                bestName = b[1];
            }
        }
        if (bestSuf != null) {
            String stem = s.substring(0, s.length() - bestSuf.length());
            if (stem.endsWith("_")) {
                stem = stem.substring(0, stem.length() - 1);
            }
            return cnBase(stem) + bestName;
        }
        return s;
    }
}
