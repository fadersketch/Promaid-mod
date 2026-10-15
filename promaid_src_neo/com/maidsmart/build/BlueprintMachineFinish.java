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
 * 机器蓝图的收尾流程（v1.2.4 从 BlueprintLib 拆出）。
 * 
 * 机器族的判定、收尾顺序（矿车/水/点燃 TNT）、状态规范化与「安静完成」。
 */
public final class BlueprintMachineFinish {
    private BlueprintMachineFinish() {
    }

    public static String machineFamily(String id) {
        if (id == null) {
            return null;
        }
        String low = id.toLowerCase(java.util.Locale.ROOT);
        // v1.5.369：内置农场（机械类蓝图）——走机器专属搭建顺序 + 完工使用说明
        if (low.equals("maid_smart:farm_cactus")) {
            return "farm_cactus";
        }
        if (low.equals("maid_smart:farm_superfurnace")) {
            return "farm_superfurnace";
        }
        if (low.equals("maid_smart:farm_lavafountain")) {
            return "farm_lavafountain";
        }
        if (low.equals("maid_smart:farm_crop")) {
            return "farm_crop";
        }
        if (low.equals("maid_smart:farm_tree")) {
            return "farm_tree";
        }
        if (low.equals("maid_smart:farm_chicken")) {
            return "farm_chicken";
        }
        if (low.equals("maid_smart:machine_furnace_array")) {
            return "furnace_array";
        }
        if (low.contains("never4get")) {
            return "never4get";
        }
        if (low.contains("轰炸机") || low.contains("bomber") || low.contains("推土机")) {
            return "bomber";
        }
        if (low.contains("打包机") || low.contains("packer") || low.contains("打包")) {
            return "packer";
        }
        if (low.contains("潜影盒") || low.contains("shulker") || low.contains("仓库")) {
            return "warehouse";
        }
        if (low.contains("分类机") || low.contains("sorter") || low.contains("分类")) {
            return "sorter";
        }
        if (low.contains("南瓜") || low.contains("西瓜") || low.contains("melon")) {
            return "farm_pumpkin";
        }
        if (low.contains("甘蔗") || low.contains("sugar_cane")) {
            return "farm_sugar";
        }
        if (low.contains("铁砧") || low.contains("anvil")) {
            return "anvil";
        }
        if (low.contains("村民") || low.contains("villager")) {
            return "villager";
        }
        return null;
    }

    public static boolean machineQuietFinish(String id) {
        String fam = machineFamily(id);
        return "bomber".equals(fam) || "never4get".equals(fam);
    }

    public static boolean isMachineBlueprint(String id) {
        if (id == null) {
            return false;
        }
        return id.startsWith("maid_smart:machine_") || machineFamily(id) != null;
    }

    public static String machineFinishTip(String id) {
        String fam = machineFamily(id);
        if (fam == null) {
            return "";
        }
        switch (fam) {
            case "bomber":
                // v1.5.320：动态提示由完工流程生成（已放置 X 辆 / 缺矿车未启动）
                return "";
            case "never4get":
                return "\u00a7e【机器】巨型红石结构已就位,未自动激活——请手动触发标靶/总开关";
            case "packer":
                return "\u00a7e【机器】打包机时钟已就位——放入物品即自动收集打包（有拉杆的机器先拉杆启动）";
            case "warehouse":
                return "\u00a7e【机器】潜影盒仓库已就位——物品入漏斗后自动分类入盒";
            case "sorter":
                return "\u00a7e【机器】分类机已就位——漏斗设好过滤物品后自动分类";
            case "farm_pumpkin":
                return "\u00a7e【机器】注意:该南瓜机蓝图无自动触发时钟,长成后需玩家手动确认";
            case "farm_sugar":
                return "\u00a7e【机器】注意:该甘蔗机为纯水流半自动,无自动收割装置";
            case "anvil":
                return "\u00a7e【机器】铁砧机观察者时钟已就位——放入铁砧/原料自动运作";
            case "villager":
                return "\u00a7e【机器】村民机已就位——放入村民与床后自动运作";
            // v1.5.369：内置农场使用说明（完工系统消息附带）
            case "farm_cactus":
                return "\u00a7e【使用】仙人掌长到 3 格会被断顶环碰碎，掉落物被四角水流冲进中央井，"
                        + "下井梯取箱内仙人掌即可；两层塔自动同时产";
            case "farm_superfurnace":
                return "\u00a7e【使用】顶部一排箱放待烧物品、侧面(背面)一排箱放煤/岩浆桶，"
                        + "成品自动流到底部输出箱——纯漏斗无红石，放料即烧";
            case "farm_lavafountain":
                return "\u00a7e【使用】滴水石锥会随时间把岩浆滴满炼药锅，满了用桶从锅里舀岩浆即可（无限岩浆）";
            case "farm_crop":
                return "\u00a7e【使用】3 个发射器里装满骨粉，站在前方台阶上拿种子对着耕地长按右键，"
                        + "边拨拉杆边种——每次拨杆 3 台发射器齐喷骨粉催熟作物";
            case "farm_tree":
                return "\u00a7e【使用】发射器里装满骨粉，拨拉杆开启后骨粉催熟树苗，活塞塔把长高的树推倒，"
                        + "原木落到前方漏斗进箱；砍完再种树苗重复即可";
            case "farm_chicken":
                return "\u00a7e【使用】在顶部 3×3 漏斗平台上放鸡，鸡下的蛋自动进发射器；拨拉杆向鸡舍投蛋，"
                        + "小鸡长大后透过前排半砖缝隙用剑击杀取熟肉/经验，掉落自动进中央箱";
            case "furnace_array":
                return "\u00a7e【使用】顶部箱子放待烧物品、侧面燃料箱放煤，成品自动流到底部输出箱——"
                        + "纯漏斗无红石";
            default:
                return "";
        }
    }

    public static List<String> sortMachinePlan(List<String> steps, String family) {
        if (steps == null || steps.isEmpty()) {
            return steps;
        }
        List<String> head = new ArrayList<>();
        List<String> body = new ArrayList<>();
        for (String step : steps) {
            if (BlueprintStepMath.parseStep(step) == null) {
                head.add(step);
            } else {
                body.add(step);
            }
        }
        Map<String, Integer> cache = new HashMap<>();
        body.sort((a, b) -> {
            String[] pa = BlueprintStepMath.parseStep(a);
            String[] pb = BlueprintStepMath.parseStep(b);
            if (pa == null || pb == null) {
                return 0;
            }
            int pha = machinePhase(pa[3], family, cache);
            int phb = machinePhase(pb[3], family, cache);
            if (pha != phb) {
                return pha - phb;
            }
            // 动力源相位内：红石线（导体）先于红石块/火把/拉杆等源——源最后放，
            // 全网络在最后一格落下时一次性带电（避免中途半网通电误触）
            if (pha == 6) {
                boolean wa = "minecraft:redstone_wire".equals(pa[3]);
                boolean wb = "minecraft:redstone_wire".equals(pb[3]);
                if (wa != wb) {
                    return wa ? -1 : 1;
                }
            }
            try {
                int ya = Integer.parseInt(pa[1]);
                int yb = Integer.parseInt(pb[1]);
                if (ya != yb) {
                    return ya - yb;
                }
                int xa = Integer.parseInt(pa[0]);
                int xb = Integer.parseInt(pb[0]);
                if (xa != xb) {
                    return xa - xb;
                }
                return Integer.parseInt(pa[2]) - Integer.parseInt(pb[2]);
            } catch (NumberFormatException e) {
                return 0;
            }
        });
        List<String> out = new ArrayList<>(head.size() + body.size());
        out.addAll(head);
        out.addAll(body);
        return out;
    }

    private static int machinePhase(String blockId, String family, Map<String, Integer> cache) {
        Integer cached = cache.get(blockId);
        if (cached != null) {
            return cached;
        }
        int phase = 1;
        Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(ResourceLocation.parse(blockId));
        if (block != null) {
            if (block instanceof net.minecraft.world.level.block.TntBlock) {
                phase = 7;
            } else if (BlueprintStepMath.isFluid(block)) {
                // v1.5.399：流体插在【动力源之前】——结构/机构/信号全部就位之后才放液体，
                // 但必须早于最后一批红石电源：机器是活放置（flag 3），电源一落下机器立刻运行，
                // 此时水道/岩浆槽必须已经完整。放在结构相位里（旧相位 2）会先流水：
                // 围壁没建起来水就流出去，冲散推人水道、漫过没砌好的槽，还会冲掉随后放下的
                // 红石粉 / 火把 / 铁轨 / 作物——机器看着建好了却不工作。
                phase = 5;
            } else if (block instanceof net.minecraft.world.level.block.RedStoneWireBlock
                    || block instanceof net.minecraft.world.level.block.RedstoneTorchBlock
                    || block instanceof net.minecraft.world.level.block.RedstoneWallTorchBlock
                    || block instanceof net.minecraft.world.level.block.PoweredBlock
                    || block instanceof net.minecraft.world.level.block.LeverBlock
                    || block instanceof net.minecraft.world.level.block.ButtonBlock
                    || block instanceof net.minecraft.world.level.block.PressurePlateBlock) {
                phase = 6;
            } else if (block instanceof net.minecraft.world.level.block.ObserverBlock
                    || block instanceof net.minecraft.world.level.block.DiodeBlock
                    || block instanceof net.minecraft.world.level.block.TripWireHookBlock
                    || block instanceof net.minecraft.world.level.block.TripWireBlock
                    || block instanceof net.minecraft.world.level.block.TargetBlock
                    || block instanceof net.minecraft.world.level.block.DaylightDetectorBlock
                    || block instanceof net.minecraft.world.level.block.TrappedChestBlock) {
                phase = 4;
            } else if (block instanceof net.minecraft.world.level.block.piston.PistonBaseBlock
                    || block instanceof net.minecraft.world.level.block.SlimeBlock
                    || block instanceof net.minecraft.world.level.block.HoneyBlock
                    || block instanceof net.minecraft.world.level.block.RedstoneLampBlock) {
                phase = 3;
            } else if (block instanceof net.minecraft.world.level.block.HopperBlock
                    || block instanceof net.minecraft.world.level.block.ChestBlock
                    || block instanceof net.minecraft.world.level.block.BarrelBlock
                    || block instanceof net.minecraft.world.level.block.DispenserBlock
                    || block instanceof net.minecraft.world.level.block.DropperBlock
                    || block instanceof net.minecraft.world.level.block.ComposterBlock
                    || block instanceof net.minecraft.world.level.block.NoteBlock
                    || block instanceof net.minecraft.world.level.block.BaseRailBlock
                    || block instanceof net.minecraft.world.level.block.IceBlock
                    || block instanceof net.minecraft.world.level.block.SoulSandBlock
                    || block instanceof net.minecraft.world.level.block.FarmBlock
                    || block instanceof net.minecraft.world.level.block.SugarCaneBlock
                    || block instanceof net.minecraft.world.level.block.DoorBlock
                    || block instanceof net.minecraft.world.level.block.TrapDoorBlock
                    || block instanceof net.minecraft.world.level.block.FenceGateBlock
                    || block instanceof net.minecraft.world.level.block.BedBlock
                    || block instanceof net.minecraft.world.level.block.ShulkerBoxBlock
                    || block instanceof net.minecraft.world.level.block.AnvilBlock
                    || block instanceof net.minecraft.world.level.block.AbstractFurnaceBlock
                    || block instanceof net.minecraft.world.level.block.BubbleColumnBlock) {
                phase = 2;
            }
        }
        cache.put(blockId, phase);
        return phase;
    }

    /**
     * 【实测九百八十五】发射器 / 投掷器的状态一律改成 {@code triggered=true}（“已经触发过”）——
     * 蓝图导出态里它常常是 false，于是**红石一落位就带电**：邻居更新让它当场排上队，几拍后把
     * **盒子里存着的东西**吐出来，完工后掉一地（“红石重放的时候会产生掉落物”）。triggered=true
     * 正是原版“已触发、仍通电但不再重复触发”的稳态：通电期间不会再动，等信号掉一次再上来照常
     * 工作——机器该跑还是跑，只是不再“刚建好先吐一个”。完工重放（{@link #snapFinish}）与建造
     * 放置（doPlace）走同一口径，故它是纯字符串处理、与平台无关。
     */
    public static String safeDispenserState(String blockId, String stateSnbt) {
        if (blockId == null || stateSnbt == null
                || !(blockId.endsWith(":dispenser") || blockId.endsWith(":dropper"))) {
            return stateSnbt;
        }
        if (stateSnbt.contains("triggered")) {
            return stateSnbt.replaceAll("triggered:\"[^\"]*\"", "triggered:\"true\"");
        }
        int close = stateSnbt.lastIndexOf('}');
        if (close < 0) {
            return stateSnbt;
        }
        String head = stateSnbt.substring(0, close);
        boolean empty = head.trim().endsWith("{");
        return head + (empty ? "" : ",") + "triggered:\"true\"" + stateSnbt.substring(close);
    }

    public static String normalizeMachineState(String blockId, String stateSnbt) {
        if (stateSnbt == null || stateSnbt.indexOf('{') < 0) {
            return stateSnbt;
        }
        String keys;
        if ("minecraft:redstone_wire".equals(blockId)) {
            keys = "power|east|west|north|south|up|down";
        } else if (BlueprintStepMath.isFluidBlock(blockId)) {
            // v1.5.317：流体只放源块（level 归一为 0）——蓝图导出的流动态 level>0
            // 不直接放置，流动由水源模拟产生（防串流/重复；气泡柱/冰道需要源块）
            keys = "level";
        } else if (blockId.contains("piston")) {
            keys = "extended";
        } else if (blockId.contains("rail")) {
            keys = "powered";
        } else if (blockId.contains("torch") || blockId.contains("lamp")) {
            keys = "lit";
        } else if (blockId.contains("lever") || blockId.contains("button")
                || blockId.contains("pressure_plate") || blockId.contains("target")) {
            keys = "powered";
        } else if (blockId.contains("repeater") || blockId.contains("comparator")) {
            keys = "powered|locked";
        } else if (blockId.contains("observer") || blockId.contains("daylight_detector")) {
            keys = "powered";
        } else if (blockId.contains("tnt")) {
            // v1.5.328：TNT 归一 unstable=false——蓝图若导出"已点燃"的 TNT
            //（unstable=true，结构文件在点燃瞬间保存），活建造下任意邻居更新
            // 都会点燃；建造期应放稳定 TNT，由机器运行时触发（neighborChanged）
            keys = "unstable";
        } else {
            return stateSnbt;
        }
        String out = stateSnbt;
        // ① 删 ",key:\"value\""（属性不在最前）
        out = out.replaceAll(",(?:" + keys + "):\"[^\"]*\"", "");
        // ② 删 "key:\"value\","（属性在最前）
        out = out.replaceAll("(?<![A-Za-z])(?:" + keys + "):\"[^\"]*\",", "");
        // ③ 删 "{key:\"value\"}"（唯一属性）→ "{}"
        out = out.replaceAll("\\{(?:" + keys + "):\"[^\"]*\"\\}", "{}");
        return out;
    }

    public static int spawnStartMinecarts(net.minecraft.server.level.ServerLevel level,
                                          net.minecraft.core.BlockPos origin,
                                          List<String> plan, String family, EntityMaid maid) {
        if (!"bomber".equals(family)) {
            return 0;
        }
        int spawned = 0;
        for (int i = 1; i < plan.size(); i++) {
            String[] parts = BlueprintStepMath.parseStep(plan.get(i));
            if (parts == null) {
                continue;
            }
            if (!"minecraft:detector_rail".equals(parts[3])) {
                continue;
            }
            try {
                int x = origin.getX() + Integer.parseInt(parts[0]);
                int y = origin.getY() + Integer.parseInt(parts[1]);
                int z = origin.getZ() + Integer.parseInt(parts[2]);
                net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
                if (!(level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.DetectorRailBlock)) {
                    continue; // 探测铁轨未建成 → 跳过
                }
                // v1.5.320：消耗 1 辆矿车（没有矿车 → 不自动生成，调用方提示缺矿车）
                if (maid != null && !consumeMinecart(maid)) {
                    break;
                }
                net.minecraft.world.entity.vehicle.Minecart cart = new net.minecraft.world.entity.vehicle.Minecart(
                        net.minecraft.world.entity.EntityType.MINECART, level);
                cart.setPos(x + 0.5, y + 0.0625, z + 0.5);
                level.addFreshEntity(cart);
                spawned++;
            } catch (NumberFormatException ignored) {
            }
        }
        if (spawned > 0) {
            BlueprintLib.LOGGER.info("spawnStartMinecarts: 轰炸机启动矿车 {} 辆", spawned);
        }
        return spawned;
    }

    private static boolean consumeMinecart(EntityMaid maid) {
        Item minecart = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                net.minecraft.resources.ResourceLocation.parse("minecraft:minecart"));
        if (minecart == null) {
            return false;
        }
        if (maid.getOwner() instanceof net.minecraft.world.entity.player.Player owner) {
            if (com.maidsmart.build.BlueprintLib.isCreative(owner)) {
                return true; // 创造模式无限
            }
            // 主人背包：取 1 辆矿车
            net.minecraft.world.entity.player.Inventory inv = owner.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                net.minecraft.world.item.ItemStack s = inv.getItem(i);
                if (!s.isEmpty() && s.getItem() == minecart) {
                    s.shrink(1); // shrink(1)
                    if (s.isEmpty()) {
                        inv.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
                    }
                    return true;
                }
            }
        }
        // 女仆背包：取 1 辆矿车
        net.minecraft.world.item.ItemStack taken = BlueprintMaterials.extractExact(maid.getAvailableBackpackInv(), minecart, 1);
        return !taken.isEmpty();
    }

    public static void activateWater(net.minecraft.server.level.ServerLevel level,
                                     net.minecraft.core.BlockPos origin, List<String> plan) {
        int n = 0;
        for (int i = 1; i < plan.size(); i++) {
            String[] parts = BlueprintStepMath.parseStep(plan.get(i));
            if (parts == null) {
                continue;
            }
            // 【实测九百六十八】完工"统一重放"的流体部分**按流体判**（不看名字里有没有 water/lava）：
            // 水、岩浆与模组流体一并激活（调用点注释一直写着"批激活水/岩浆源"，旧实现只认水 → 只带
            // 岩浆的机器完工后岩浆不流：它自己那一 tick 早在建造期被冻结/消耗掉了）。
            if (!BlueprintStepMath.isFluidStep(parts)) {
                continue;
            }
            try {
                int x = origin.getX() + Integer.parseInt(parts[0]);
                int y = origin.getY() + Integer.parseInt(parts[1]);
                int z = origin.getZ() + Integer.parseInt(parts[2]);
                net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
                net.minecraft.world.level.block.state.BlockState st = level.getBlockState(pos);
                if (!st.getFluidState().isEmpty()) {
                    level.setBlock(pos, st, 3); // 同状态重放 → 流动计算/气泡柱生成
                    n++;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (n > 0) {
            BlueprintLib.LOGGER.info("activateWater: 重放 {} 个流体方块（水 / 岩浆 / 模组流体）激活流动/气泡柱", n);
        }
    }

    public static void settleTntIgnition(net.minecraft.server.level.ServerLevel level,
                                         net.minecraft.core.BlockPos origin, List<String> plan) {
        int n = 0;
        for (int i = 1; i < plan.size(); i++) {
            String step = plan.get(i);
            if (step == null || !step.contains("tnt")) {
                continue; // 廉价预筛（parseStep 的 split 更贵）
            }
            String[] parts = BlueprintStepMath.parseStep(step);
            if (parts == null || !"minecraft:tnt".equals(parts[3])) {
                continue;
            }
            try {
                int x = origin.getX() + Integer.parseInt(parts[0]);
                int y = origin.getY() + Integer.parseInt(parts[1]);
                int z = origin.getZ() + Integer.parseInt(parts[2]);
                net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
                net.minecraft.world.level.block.state.BlockState st = level.getBlockState(pos);
                if (!(st.getBlock() instanceof net.minecraft.world.level.block.TntBlock)) {
                    continue;
                }
                // 只有"当前邻接带电"才点燃（等效于它此刻自然触发）
                if (level.hasNeighborSignal(pos)) {
                    net.minecraft.world.level.block.TntBlock.explode(level, pos);
                    n++;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (n > 0) {
            BlueprintLib.LOGGER.info("settleTntIgnition: 完工点燃 {} 个邻接带电的 TNT", n);
        }
    }

    /** 完工重放的计划步数上限：整片清空 + 整片重放都挤在完工这**一个 tick** 里，几十万步会把
     *  服务端卡住好几秒——超限自动跳过（保持完工原样）并落一条日志。上限 2 万 → 5 万（实测九百六十九：
     *  2 万对现代大工程太紧），并把清空/重放两趟各自的解析合并成一趟，压掉这一档的瞬时开销。 */
    public static final int SNAP_MAX_STEPS = 50000;

    /**
     * 完工「后门」（实测九百六十一）：完工瞬间把她建好的这一片【静默】推倒重来——
     * 第一趟把蓝图占的每格清成空气（flag 2：无粒子、无音效、不触发邻居更新），第二趟
     * 按计划顺序整片重放（机器活建造 flag 3，液体与 TNT 静默 + 护栏）。终态因此严格等于
     * 蓝图本身：建造中被活塞推走 / 被信号误触 / 被水流冲散 / 补支撑多出的中间态全抹平。
     * 只动蓝图自己的格子；重放不扣材料（材料建造时已付过）。开关 {@code build.snapFinish}
     * 关闭、或计划步数超过 {@link #SNAP_MAX_STEPS} 时直接跳过（不重放）。
     */
    public static boolean snapFinish(net.minecraft.server.level.ServerLevel level,
                                     net.minecraft.core.BlockPos origin, List<String> plan, boolean live) {
        return snapFinish(level, origin, plan, live, false);
    }

    /** 【实测九百八十五】带“这批材料用过等价替代”判据的重载——见 {@code altSubstituted}
     *  与下面那段“等价材料替换检测”。 */
    public static boolean snapFinish(net.minecraft.server.level.ServerLevel level,
                                     net.minecraft.core.BlockPos origin, List<String> plan,
                                     boolean live, boolean altSubstituted) {
        if (!com.maidsmart.config.MaidSmartConfig.BUILD_SNAP_FINISH.get()) {
            return false;
        }
        if (level == null || origin == null || plan == null || plan.size() <= 1) {
            return false;
        }
        if (plan.size() > SNAP_MAX_STEPS) {
            BlueprintLib.LOGGER.info("snapFinish: 计划 {} 步超过上限 {} —— 跳过完工重放（保持完工原样）",
                    plan.size(), SNAP_MAX_STEPS);
            return false;
        }
        // 【实测九百八十五·点1】这批材料用过**等价替代**（她缺料时换成等价族里的别的
        // 方块，记录在计划表的 altUsed 里）→ 整批放弃重放：「如果玩家使用了等价材料组
        // 进行替换最终把东西建造了出来，那最后还是会被重新放置，导致整个建筑变了个样」。
        // 重放的语义是“把这一片还原成图纸”，而替换过的这一片本来就不是图纸。
        if (altSubstituted) {
            BlueprintLib.LOGGER.info("snapFinish: 本次建造用过等价族替代材料 —— 跳过完工重放"
                    + "（不把替代品换回图纸原材料，避免整栋建筑变样）");
            return false;
        }
        // 一趟解析（实测九百六十九）：先把"能落地的步骤"收成（位置 + 步骤串），下面清空与重放两趟直接
        // 复用——旧版两趟各解析一次（2×N 次 parseStep + 注册表查找）全挤在完工这一个 tick 里。
        int total = plan.size();
        net.minecraft.core.BlockPos[] poss = new net.minecraft.core.BlockPos[total];
        String[][] parsedSteps = new String[total][];
        int cursor = 0;
        for (int i = 1; i < total; i++) {
            String[] parts = BlueprintStepMath.parseStep(plan.get(i));
            if (parts == null) {
                continue;
            }
            net.minecraft.core.BlockPos pos = snapStepPos(origin, parts);
            if (pos == null || !level.isLoaded(pos)) {
                continue;
            }
            poss[cursor] = pos;
            parsedSteps[cursor] = parts;
            cursor++;
        }
        // 【实测九百八十五·点2】再拿世界状态兜一层（防“替代记录只在内存里”：重启后继建的那一批
        // altUsed 是空的）：普通建筑（!live）的完工终态**本来应该严格等于图纸**，所以任一格
        // “现在放的不是图纸要的那种、却属于同一等价族”就说明这一片被等价材料替过 → 同样放弃重放。
        // 机器不查（live）：机器建造期本来就会被活塞/水流推动，格子里出现的同族异种方块
        //（被推进来的圆石 vs 图纸要的深板岩）是正常中间态，正是重放要抹平的东西；机器那一路只认
        // altUsed 那份精确记录。地形等价（泥土↔草方块）不算——那档在建造的每一道检查里都按
        // “已建”处理，重放它也符合预期。**必须在动任何格子之前**查完：发现即整批放弃。
        if (!live) {
            int altCells = 0;
            String altSample = "";
            for (int k = 0; k < cursor; k++) {
                String[] parts = parsedSteps[k];
                net.minecraft.world.level.block.Block want = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(ResourceLocation.parse(parts[3]));
                if (want == null || want == net.minecraft.world.level.block.Blocks.AIR) {
                    continue; // 空气步骤没有“材料”可言
                }
                net.minecraft.world.level.block.state.BlockState now = level.getBlockState(poss[k]);
                net.minecraft.world.level.block.Block actual = now.getBlock();
                if (actual == want || now.isAir()) {
                    continue;
                }
                if (!BlueprintLib.isEquivalent(parts[3], actual)
                        || BlueprintLib.isBuiltEquivalent(parts[3], actual)) {
                    continue;
                }
                altCells++;
                if (altCells <= 3) {
                    altSample = altSample + " " + parts[3] + "→" + String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(actual));
                }
            }
            if (altCells > 0) {
                BlueprintLib.LOGGER.info("snapFinish: {} 格放的是等价族里的替代材料（{}）—— 跳过完工重放"
                        + "（不把替代品换回图纸原材料，避免整栋建筑变样）", altCells, altSample.trim());
                return false;
            }
        }
        // 第一趟：清空蓝图占的格子（流体源一起清）。本来就是空气的格不必动。
        int cleared = 0;
        for (int k = 0; k < cursor; k++) {
            net.minecraft.core.BlockPos pos = poss[k];
            if (level.getBlockState(pos).isAir()) {
                continue;
            }
            level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
            cleared++;
        }
        // 第二趟：按计划原顺序重放（机器已按红石拓扑排过：结构→机构→活动件→传感→
        // 动力源→TNT）。液体照旧静默铺源块，由随后的完工 activateWater 统一激活
        // （逐个活放会因邻水先流而跳过后面的源块 → 气泡柱/水道不完整）。
        int placed = 0;
        for (int k = 0; k < cursor; k++) {
            net.minecraft.core.BlockPos pos = poss[k];
            String[] parts = parsedSteps[k];
            Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(ResourceLocation.parse(parts[3]));
            if (block == null || block == net.minecraft.world.level.block.Blocks.AIR) {
                continue; // 空气步骤：第一趟已经清过
            }
            // 【实测九百八十五】发射器/投掷器一律以“已触发”态重放（同建造口径，见
            // safeDispenserState）：否则红石一通电它就往外吐盒子里的东西。
            String stateSnbt = safeDispenserState(parts[3],
                    live ? normalizeMachineState(parts[3], parts[4]) : parts[4]);
            net.minecraft.world.level.block.state.BlockState state = block.defaultBlockState();
            if (stateSnbt != null) {
                net.minecraft.world.level.block.state.BlockState parsed =
                        BlueprintLib.parseStepState(level, block, stateSnbt);
                if (parsed != null) {
                    state = parsed;
                }
            }
            boolean tnt = block instanceof net.minecraft.world.level.block.TntBlock;
            boolean fluid = BlueprintStepMath.isFluid(block);
            // 与建造同口径：机器活放 flag 3，液体/TNT/静默建筑 flag 2
            int flag = (live && !tnt && !fluid) ? 3 : 2;
            if (tnt) {
                BuildTntGuard.setSuppress(true);
                try {
                    level.setBlock(pos, state, flag);
                } finally {
                    BuildTntGuard.setSuppress(false);
                }
            } else {
                level.setBlock(pos, state, flag);
            }
            snapApplyBe(level, pos, parts[5]);
            placed++;
        }
        BlueprintLib.LOGGER.info("snapFinish: 完工重放——静默清 {} 格 / 按蓝图重放 {} 格（活建造={}）",
                cleared, placed, live);
        return true;
    }

    /** 步骤坐标 + 原点（解析不出坐标返回 null）。 */
    private static net.minecraft.core.BlockPos snapStepPos(net.minecraft.core.BlockPos origin, String[] parts) {
        try {
            return new net.minecraft.core.BlockPos(origin.getX() + Integer.parseInt(parts[0]),
                    origin.getY() + Integer.parseInt(parts[1]),
                    origin.getZ() + Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 恢复方块实体数据（箱子内容/告示牌文字等；与 doPlace 同口径）。 */
    private static void snapApplyBe(net.minecraft.server.level.ServerLevel level,
                                    net.minecraft.core.BlockPos pos, String beSnbt) {
        if (beSnbt == null) {
            return;
        }
        try {
            net.minecraft.nbt.CompoundTag beTag = net.minecraft.nbt.NbtUtils.snbtToStructure(beSnbt);
            net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                be.loadWithComponents(beTag, level.registryAccess());
                be.setRemoved();
            }
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("BlueprintMachineFinish", ignored);
        }
    }
}
