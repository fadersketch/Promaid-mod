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
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;
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
            if (pha == 5) {
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
        Block block = ForgeRegistries.BLOCKS.getValue(ResourceLocation.parse(blockId));
        if (block != null) {
            if (block instanceof net.minecraft.world.level.block.TntBlock) {
                phase = 6;
            } else if (block instanceof net.minecraft.world.level.block.RedStoneWireBlock
                    || block instanceof net.minecraft.world.level.block.RedstoneTorchBlock
                    || block instanceof net.minecraft.world.level.block.RedstoneWallTorchBlock
                    || block instanceof net.minecraft.world.level.block.PoweredBlock
                    || block instanceof net.minecraft.world.level.block.LeverBlock
                    || block instanceof net.minecraft.world.level.block.ButtonBlock
                    || block instanceof net.minecraft.world.level.block.PressurePlateBlock) {
                phase = 5;
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
                    || block instanceof net.minecraft.world.level.block.LiquidBlock
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

    public static String normalizeMachineState(String blockId, String stateSnbt) {
        if (stateSnbt == null || stateSnbt.indexOf('{') < 0) {
            return stateSnbt;
        }
        String keys;
        if ("minecraft:redstone_wire".equals(blockId)) {
            keys = "power|east|west|north|south|up|down";
        } else if ("minecraft:water".equals(blockId) || "minecraft:lava".equals(blockId)) {
            // v1.5.317：液体只放源块（level 归一为 0）——蓝图导出的流动态 level>0
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
                int x = origin.m_123341_() + Integer.parseInt(parts[0]);
                int y = origin.m_123342_() + Integer.parseInt(parts[1]);
                int z = origin.m_123343_() + Integer.parseInt(parts[2]);
                net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
                if (!(level.m_8055_(pos).m_60734_() instanceof net.minecraft.world.level.block.DetectorRailBlock)) {
                    continue; // 探测铁轨未建成 → 跳过
                }
                // v1.5.320：消耗 1 辆矿车（没有矿车 → 不自动生成，调用方提示缺矿车）
                if (maid != null && !consumeMinecart(maid)) {
                    break;
                }
                net.minecraft.world.entity.vehicle.Minecart cart = new net.minecraft.world.entity.vehicle.Minecart(
                        net.minecraft.world.entity.EntityType.f_20469_, level);
                cart.m_6034_(x + 0.5, y + 0.0625, z + 0.5);
                level.m_7967_(cart);
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
        Item minecart = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                net.minecraft.resources.ResourceLocation.parse("minecraft:minecart"));
        if (minecart == null) {
            return false;
        }
        if (maid.m_269323_() instanceof net.minecraft.world.entity.player.Player owner) {
            if (com.maidsmart.build.BlueprintLib.isCreative(owner)) {
                return true; // 创造模式无限
            }
            // 主人背包：取 1 辆矿车
            net.minecraft.world.entity.player.Inventory inv = owner.m_150109_();
            for (int i = 0; i < inv.m_6643_(); i++) {
                net.minecraft.world.item.ItemStack s = inv.m_8020_(i);
                if (!s.m_41619_() && s.m_41720_() == minecart) {
                    s.m_41774_(1); // shrink(1)
                    if (s.m_41619_()) {
                        inv.m_6836_(i, net.minecraft.world.item.ItemStack.f_41583_);
                    }
                    return true;
                }
            }
        }
        // 女仆背包：取 1 辆矿车
        net.minecraft.world.item.ItemStack taken = BlueprintMaterials.extractExact(maid.getAvailableBackpackInv(), minecart, 1);
        return !taken.m_41619_();
    }

    public static void activateWater(net.minecraft.server.level.ServerLevel level,
                                     net.minecraft.core.BlockPos origin, List<String> plan) {
        int n = 0;
        for (int i = 1; i < plan.size(); i++) {
            String[] parts = BlueprintStepMath.parseStep(plan.get(i));
            if (parts == null) {
                continue;
            }
            if (!"minecraft:water".equals(parts[3])) {
                continue;
            }
            try {
                int x = origin.m_123341_() + Integer.parseInt(parts[0]);
                int y = origin.m_123342_() + Integer.parseInt(parts[1]);
                int z = origin.m_123343_() + Integer.parseInt(parts[2]);
                net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
                net.minecraft.world.level.block.state.BlockState st = level.m_8055_(pos);
                if (st.m_60734_() instanceof net.minecraft.world.level.block.LiquidBlock) {
                    level.m_7731_(pos, st, 3); // 同状态重放 → 流动计算/气泡柱生成
                    n++;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (n > 0) {
            BlueprintLib.LOGGER.info("activateWater: 重放 {} 个水方块激活水流/气泡柱", n);
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
                int x = origin.m_123341_() + Integer.parseInt(parts[0]);
                int y = origin.m_123342_() + Integer.parseInt(parts[1]);
                int z = origin.m_123343_() + Integer.parseInt(parts[2]);
                net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
                net.minecraft.world.level.block.state.BlockState st = level.m_8055_(pos);
                if (!(st.m_60734_() instanceof net.minecraft.world.level.block.TntBlock)) {
                    continue;
                }
                // 只有"当前邻接带电"才点燃（等效于它此刻自然触发）
                if (level.m_276867_(pos)) {
                    net.minecraft.world.level.block.TntBlock.m_57433_(level, pos);
                    n++;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (n > 0) {
            BlueprintLib.LOGGER.info("settleTntIgnition: 完工点燃 {} 个邻接带电的 TNT", n);
        }
    }
}
