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
 * 步骤列表的几何运算（v1.2.4 从 BlueprintLib 拆出）。
 * 
 * 解析步骤串、算尺寸、旋转、居中、去重、地形层裁剪——全部是纯函数，
 * 缓存表 SIZE_CACHE / ROTATE_CACHE 随本类一起搬走。
 */
public final class BlueprintStepMath {
    private BlueprintStepMath() {
    }

    private static boolean isSkeleton(String[] parts, int[] skel) {
        try {
            int x = Integer.parseInt(parts[0]);
            int z = Integer.parseInt(parts[2]);
            return x == skel[0] || x == skel[1] || z == skel[2] || z == skel[3];
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int buildPriority(String blockId, java.util.Map<String, Integer> prioCache) {
        Integer cached = prioCache.get(blockId);
        if (cached != null) {
            return cached;
        }
        int prio = 1;
        Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(blockId));
        if (block != null) {
            if (block instanceof net.minecraft.world.level.block.TntBlock) {
                // v1.5.314：TNT 最后放（优先级低于红石 prio 2）——先放的红石线/
                // 中继器等激活信号会把已放置的 TNT 点燃（TNT 大炮蓝图实证：
                // "红石信号最后放上去就炸"）；TNT 排最后时信号源已就位稳定，
                // 放置 TNT 不再产生新的红石更新，不会炸。
                prio = 3;
            } else if (block instanceof net.minecraft.world.level.block.BushBlock
                    || block instanceof net.minecraft.world.level.block.CarpetBlock
                    || block instanceof net.minecraft.world.level.block.TorchBlock
                    || block instanceof net.minecraft.world.level.block.ButtonBlock
                    || block instanceof net.minecraft.world.level.block.LeverBlock
                    || block instanceof net.minecraft.world.level.block.PressurePlateBlock
                    || block instanceof net.minecraft.world.level.block.BannerBlock
                    || block instanceof net.minecraft.world.level.block.SignBlock
                    || block instanceof net.minecraft.world.level.block.FlowerPotBlock
                    || block instanceof net.minecraft.world.level.block.SnowLayerBlock
                    || block instanceof net.minecraft.world.level.block.VineBlock
                    || block instanceof net.minecraft.world.level.block.WaterlilyBlock
                    || block instanceof net.minecraft.world.level.block.SugarCaneBlock
                    || block instanceof net.minecraft.world.level.block.RedStoneWireBlock
                    || block instanceof net.minecraft.world.level.block.RailBlock
                    || block instanceof net.minecraft.world.level.block.piston.PistonBaseBlock
                    || block instanceof net.minecraft.world.level.block.DispenserBlock
                    || block instanceof net.minecraft.world.level.block.DropperBlock
                    || block instanceof net.minecraft.world.level.block.HopperBlock
                    || block instanceof net.minecraft.world.level.block.ObserverBlock
                    || block instanceof net.minecraft.world.level.block.RedstoneLampBlock
                    || block instanceof net.minecraft.world.level.block.DiodeBlock
                    // v1.5.252p：补常见装饰方块——灯笼/书架/蜡烛/陶罐/发光地衣/
                    // 紫水晶簇/钟乳石/孢子花/大滴水叶/珊瑚/干草捆等原被归为结构
                    // （prio 0）与墙一起先建，拟人化效果被稀释（wizard_tower 实证）
                    || block instanceof net.minecraft.world.level.block.LanternBlock
                    || block instanceof net.minecraft.world.level.block.ChiseledBookShelfBlock
                    || block instanceof net.minecraft.world.level.block.CandleBlock
                    || block instanceof net.minecraft.world.level.block.DecoratedPotBlock
                    || block instanceof net.minecraft.world.level.block.GlowLichenBlock
                    || block instanceof net.minecraft.world.level.block.AmethystClusterBlock
                    || block instanceof net.minecraft.world.level.block.PointedDripstoneBlock
                    || block instanceof net.minecraft.world.level.block.SporeBlossomBlock
                    || block instanceof net.minecraft.world.level.block.BigDripleafBlock
                    || block instanceof net.minecraft.world.level.block.CoralBlock
                    || block instanceof net.minecraft.world.level.block.CoralFanBlock
                    || block instanceof net.minecraft.world.level.block.CoralPlantBlock
                    || block instanceof net.minecraft.world.level.block.HayBlock
                    || block instanceof net.minecraft.world.level.block.CocoaBlock
                    || block instanceof net.minecraft.world.level.block.SweetBerryBushBlock
                    || block instanceof net.minecraft.world.level.block.CaveVinesBlock
                    || block instanceof net.minecraft.world.level.block.CaveVinesPlantBlock
                    || block instanceof net.minecraft.world.level.block.MangrovePropaguleBlock
                    || block instanceof net.minecraft.world.level.block.EndRodBlock
                    || block instanceof net.minecraft.world.level.block.ChainBlock) {
                prio = 2;
            } else if (block instanceof net.minecraft.world.level.block.DoorBlock
                    || block instanceof net.minecraft.world.level.block.TrapDoorBlock
                    || block instanceof net.minecraft.world.level.block.FenceGateBlock
                    || block instanceof net.minecraft.world.level.block.StairBlock
                    || block instanceof net.minecraft.world.level.block.SlabBlock
                    || block instanceof net.minecraft.world.level.block.FenceBlock
                    || block instanceof net.minecraft.world.level.block.WallBlock
                    || block instanceof net.minecraft.world.level.block.ChestBlock
                    || block instanceof net.minecraft.world.level.block.BedBlock) {
                prio = 1;
            } else {
                prio = 0;
            }
        }
        prioCache.put(blockId, prio);
        return prio;
    }

    public static String[] parseStep(String step) {
        String[] head = step.split("\\|");
        String[] pos = head[0].split(",");
        if (pos.length != 4) {
            return null;
        }
        String[] out = new String[6];
        out[0] = pos[0];
        out[1] = pos[1];
        out[2] = pos[2];
        out[3] = pos[3];
        out[4] = head.length > 1 ? head[1] : null;
        out[5] = head.length > 2 ? head[2] : null;
        return out;
    }

    public static int[] blueprintSize(List<String> steps) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        if (steps != null) {
            for (int i = 1; i < steps.size(); i++) {
                String[] p = parseStep(steps.get(i));
                if (p == null) {
                    continue;
                }
                try {
                    int x = Integer.parseInt(p[0]);
                    int y = Integer.parseInt(p[1]);
                    int z = Integer.parseInt(p[2]);
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                    minZ = Math.min(minZ, z);
                    maxZ = Math.max(maxZ, z);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (minX == Integer.MAX_VALUE) {
            return new int[]{0, 0, 0};
        }
        return new int[]{maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1};
    }

    static final Map<String, int[]> SIZE_CACHE = new HashMap<>();

    public static int[] blueprintSizeCached(String id, List<String> steps) {
        if (id != null) {
            int[] c = SIZE_CACHE.get(id);
            if (c != null) {
                return c;
            }
        }
        int[] sz = blueprintSize(steps);
        if (id != null) {
            SIZE_CACHE.put(id, sz);
        }
        return sz;
    }

    public static List<String> getBlueprint(String id) {
        List<String> builtIn = BlueprintCatalog.getBuiltIn(id);
        if (builtIn != null) {
            return builtIn;
        }
        BlueprintFileIo.scanExternalBlueprints();
        return BlueprintFileIo.EXTERNAL.get(id);
    }

    public static List<String> rotateSteps(List<String> steps, int quarters,
                                           net.minecraft.core.HolderGetter<net.minecraft.world.level.block.Block> holder) {
        int q = Math.floorMod(quarters, 4);
        if (q == 0 || steps == null || steps.isEmpty()) {
            return steps;
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (String s : steps) {
            String[] p = parseStep(s);
            if (p == null) {
                continue;
            }
            try {
                int x = Integer.parseInt(p[0]);
                int z = Integer.parseInt(p[2]);
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
            } catch (NumberFormatException ignored) {
            }
        }
        if (minX == Integer.MAX_VALUE) {
            return steps;
        }
        int sx = maxX - minX + 1;
        int sz = maxZ - minZ + 1;
        List<String> out = new ArrayList<>(steps.size());
        for (String s : steps) {
            String[] p = parseStep(s);
            if (p == null) {
                out.add(s); // 首部 tag 行保序
                continue;
            }
            try {
                int x = Integer.parseInt(p[0]);
                int y = Integer.parseInt(p[1]);
                int z = Integer.parseInt(p[2]);
                int rx;
                int rz;
                if (q == 1) {
                    rx = sz - 1 - z;
                    rz = x;
                } else if (q == 2) {
                    rx = sx - 1 - x;
                    rz = sz - 1 - z;
                } else {
                    rx = z;
                    rz = sx - 1 - x;
                }
                // v1.1.0 实测九十七：从原始串切分（而非 parseStep 的定长段）——
                // 第一道 | 前是坐标+方块名，第二道 | 后（含 BE 与任何未知扩展段）
                // 原样保留，绝不因重建截断
                int i1 = s.indexOf('|');
                String rest = i1 < 0 ? "" : s.substring(i1 + 1);
                int i2 = rest.indexOf('|');
                String statePart = i2 < 0 ? rest : rest.substring(0, i2);
                String tailPart = i2 < 0 ? "" : rest.substring(i2);
                StringBuilder step = new StringBuilder();
                step.append(rx).append(',').append(y).append(',').append(rz).append(',').append(p[3]);
                // 状态 SNBT 跟随旋转（holder 缺失时保持原样——放置端仍有默认状态兜底）
                if (!statePart.isEmpty() && holder != null) {
                    try {
                        net.minecraft.nbt.CompoundTag stateTag =
                                net.minecraft.nbt.TagParser.parseTag(statePart);
                        // v1.1.0 实测一百一十一：统一重建为 {Name,Properties} 全格式。
                        // ①内置/JSON 蓝图是 state-only SNBT（{facing:"south"} 无 Name）——
                        // readBlockState（readBlockState）对无 Name 标签直接返回空气（字节码实证），
                        // 旧版旋转后写回 {Name:"minecraft:air"}，状态全毁；
                        // ②外部结构文件是全格式——旧版重建步骤漏加 '|' 分隔符，状态串
                        // 紧跟方块名且含逗号，parseStep 按逗号切分长度不对 → 带状态步骤
                        // 整块被丢弃（Python 字符串级模拟实证）。两处一并修复。
                        net.minecraft.nbt.CompoundTag props;
                        String name = null;
                        if (stateTag.contains("Name", 8)) {
                            name = stateTag.getString("Name");
                        }
                        if (stateTag.contains("Properties", 10)) {
                            props = stateTag.getCompound("Properties");
                        } else {
                            props = new net.minecraft.nbt.CompoundTag();
                            for (String key : stateTag.getAllKeys()) {
                                if ("Name".equals(key)) {
                                    continue;
                                }
                                net.minecraft.nbt.Tag v = stateTag.get(key);
                                if (v != null) {
                                    props.put(key, v);
                                }
                            }
                        }
                        if (name == null) {
                            name = p[3]; // state-only 蓝图：方块名取自步骤头
                        }
                        net.minecraft.nbt.CompoundTag full = new net.minecraft.nbt.CompoundTag();
                        full.putString("Name", name);
                        full.put("Properties", props);
                        net.minecraft.world.level.block.state.BlockState st =
                                net.minecraft.nbt.NbtUtils.readBlockState(holder, full).rotate(rotationOf(q));
                        step.append('|').append(net.minecraft.nbt.NbtUtils.prettyPrint(
                                net.minecraft.nbt.NbtUtils.writeBlockState(st)));
                    } catch (Exception ignored) {
                        step.append('|').append(statePart);
                    }
                }
                step.append(tailPart);
                out.add(step.toString());
            } catch (NumberFormatException ignored) {
                out.add(s); // 坐标异常的步骤原样保留，不静默丢块
            }
        }
        return out;
    }

    private static net.minecraft.world.level.block.Rotation rotationOf(int q) {
        return switch (Math.floorMod(q, 4)) {
            case 1 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
            case 2 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
            case 3 -> net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
            default -> net.minecraft.world.level.block.Rotation.NONE;
        };
    }

    public static List<String> getBlueprintRotated(String id, int quarters,
                                                   net.minecraft.core.HolderGetter<net.minecraft.world.level.block.Block> holder) {
        // v1.1.0 实测九十七：统一走"先解析后旋转"——内置蓝图此前被直接跳过旋转，
        // 现在内置/外部/JSON 全格式经 rotateSteps 整体旋转（含 BlockState 转向）
        int q = Math.floorMod(quarters, 4);
        if (q == 0) {
            return getBlueprint(id);
        }
        // v1.1.0 实测九十七：旋转结果缓存（id#q，按源列表引用同一性失效）——
        // 大蓝图（数十万步）每次请求都重跑 SNBT 往返会在服务端线程卡顿
        String key = id + "#" + q;
        List<String> base = getBlueprint(id);
        if (base == null) {
            return null;
        }
        RotCached c = ROTATE_CACHE.get(key);
        if (c != null && c.src == base) {
            return c.out;
        }
        List<String> out = rotateSteps(base, q, holder);
        ROTATE_CACHE.put(key, new RotCached(base, out));
        return out;
    }

    private record RotCached(List<String> src, List<String> out) {
    }

    private static final Map<String, RotCached> ROTATE_CACHE = new HashMap<>();

    public static List<String> trimTerrainLayers(List<String> steps) {
        if (steps == null || steps.size() < 4) {
            return steps;
        }
        java.util.TreeMap<Integer, int[]> perLayer = new java.util.TreeMap<>(); // y -> [地形数, 总数]
        for (String step : steps) {
            String[] parts = parseStep(step);
            if (parts == null) {
                continue;
            }
            try {
                int y = Integer.parseInt(parts[1]);
                int[] c = perLayer.computeIfAbsent(y, k -> new int[2]);
                c[1]++;
                if (BlueprintBlockData.TERRAIN_BLOCKS.contains(parts[3])) {
                    c[0]++;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (perLayer.size() <= 1) {
            return steps;
        }
        // 从最低层向上找第一个"非地形层"（建筑开始层）
        int groundY = -1;
        for (java.util.Map.Entry<Integer, int[]> e : perLayer.entrySet()) {
            int[] c = e.getValue();
            if (c[1] < 32 || c[0] * 100 / c[1] < 85) {
                groundY = e.getKey();
                break;
            }
        }
        if (groundY < 0) {
            return steps; // 全是地形层（纯地形蓝图）——不压缩
        }
        int minKeep = groundY - 1; // 保留建筑层及其下 1 层地基
        if (perLayer.firstKey() >= minKeep) {
            return steps; // 无还原区
        }
        java.util.List<String> out = new java.util.ArrayList<>(steps.size());
        int dropped = 0;
        for (String step : steps) {
            String[] parts = parseStep(step);
            if (parts != null) {
                try {
                    if (Integer.parseInt(parts[1]) < minKeep) {
                        dropped++;
                        continue;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            out.add(step);
        }
        if (dropped > 0) {
            BlueprintLib.LOGGER.info("地形层压缩：丢弃 {} 个地形方块步骤（{} 层之下的还原区，保留 {} 层地基）",
                    dropped, groundY, minKeep);
        }
        return out;
    }

    public static List<String> centerSteps(List<String> steps) {
        if (steps == null || steps.isEmpty()) {
            return steps;
        }
        // v1.5.80：地形层压缩（外部蓝图常含原址地形——草地/矿层，不还原）
        steps = trimTerrainLayers(steps);
        // v1.5.77：y 升序排序（底部先建）——外部蓝图（.schem/.nbt）步骤保持文件
        // 原始顺序，装饰/植物可能排在支撑地面之前 → "支撑缺失死锁"：主循环窗口
        // 全是放不上的附着方块（延后），支撑步骤在游标之后永远建不到，延后集
        // 越积越多、游标停滞（build stall 死循环）。排序保证地面/墙体先于其上的
        // 附着物；首行 tag（parseStep 不可解析行）保序在前；同层稳定排序。
        java.util.List<String> head = new java.util.ArrayList<>();
        java.util.List<String> body = new java.util.ArrayList<>();
        for (String step : steps) {
            if (parseStep(step) == null) {
                head.add(step);
            } else {
                body.add(step);
            }
        }
        // v1.5.78：拟人化搭建优先级——排序键 = 优先级 → y → x → z。
        // 结构主体（墙/地板/屋顶）最先立起 → 功能/家具（门/楼梯/箱子）次之 →
        // 装饰与红石机械（花/火把/按钮/活塞）最后。视觉上"骨架先成型再填充"；
        // 装饰的支撑（结构方块）先建好 → 支撑缺失延后大幅减少 → 不钻牛角尖；
        // 中途取消/中断时主体已完整，损失最小。
        // v1.5.252x：结构类内部再分【骨架优先】——水平外轮廓（x/z 在蓝图边界）的
        // 方块先建，内部填充后建（排序键 = prio → 骨架 → y → x → z）。
        // 效果：四面墙圈/四角柱/屋顶边缘先立起来（建筑"轮廓"从底到顶成型），
        // 再逐层填充墙面与内部——更接近真人"先搭骨架再填墙"的建造习惯。
        java.util.Map<String, Integer> prioCache = new java.util.HashMap<>();
        // 骨架判定用的水平范围（非空气步骤的 x/z 边界）
        final int[] skel = new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE,
                Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (String s : body) {
            String[] pp = parseStep(s);
            if (pp == null) {
                continue;
            }
            try {
                int px = Integer.parseInt(pp[0]);
                int pz = Integer.parseInt(pp[2]);
                skel[0] = Math.min(skel[0], px);
                skel[1] = Math.max(skel[1], px);
                skel[2] = Math.min(skel[2], pz);
                skel[3] = Math.max(skel[3], pz);
            } catch (NumberFormatException ignored) {
            }
        }
        body.sort((a, b) -> {
            String[] pa = parseStep(a);
            String[] pb = parseStep(b);
            if (pa == null || pb == null) {
                return 0;
            }
            int pra = buildPriority(pa[3], prioCache);
            int prb = buildPriority(pb[3], prioCache);
            if (pra != prb) {
                return pra - prb;
            }
            // v1.5.252x：结构类（prio 0）内部——骨架（水平外轮廓）优先于填充
            if (pra == 0 && skel[0] != Integer.MAX_VALUE) {
                boolean sa = isSkeleton(pa, skel);
                boolean sb = isSkeleton(pb, skel);
                if (sa != sb) {
                    return sa ? -1 : 1;
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
        steps = new java.util.ArrayList<>(head.size() + body.size());
        steps.addAll(head);
        steps.addAll(body);
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (String step : steps) {
            String[] parts = parseStep(step);
            if (parts == null) {
                continue;
            }
            int x = Integer.parseInt(parts[0]);
            int z = Integer.parseInt(parts[2]);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }
        int offX = -(minX + maxX) / 2;
        int offZ = -(minZ + maxZ) / 2;
        if (offX == 0 && offZ == 0) {
            return dedupeSteps(steps);
        }
        List<String> out = new ArrayList<>(steps.size());
        for (String step : steps) {
            String[] parts = parseStep(step);
            if (parts == null) {
                out.add(step);
                continue;
            }
            int x = Integer.parseInt(parts[0]) + offX;
            int z = Integer.parseInt(parts[2]) + offZ;
            StringBuilder sb = new StringBuilder();
            sb.append(x).append(',').append(parts[1]).append(',').append(z).append(',').append(parts[3]);
            if (parts[4] != null) {
                sb.append('|').append(parts[4]);
            }
            if (parts[5] != null) {
                sb.append('|').append(parts[5]);
            }
            out.add(sb.toString());
        }
        return dedupeSteps(out);
    }

    static List<String> dedupeSteps(List<String> steps) {
        if (steps == null || steps.size() < 2) {
            return steps;
        }
        java.util.LinkedHashMap<Long, String> dedup = new java.util.LinkedHashMap<>();
        for (String step : steps) {
            String[] parts = parseStep(step);
            if (parts == null) {
                dedup.put(-1L - dedup.size(), step); // 无法解析的行保序保留
                continue;
            }
            long key;
            try {
                key = (long) (Integer.parseInt(parts[0]) & 0xFFFFF) << 42
                        | (long) (Integer.parseInt(parts[1]) & 0x1FFFFF) << 21
                        | (Integer.parseInt(parts[2]) & 0x1FFFFF);
            } catch (NumberFormatException e) {
                dedup.put(-1L - dedup.size(), step);
                continue;
            }
            dedup.put(key, step); // 后写覆盖先写 = 保留最后一条
        }
        return new ArrayList<>(dedup.values());
    }
}
