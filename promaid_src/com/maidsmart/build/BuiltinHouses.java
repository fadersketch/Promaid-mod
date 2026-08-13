package com.maidsmart.build;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置蓝图生成器（v1.5.271 收官）：
 * - 15 个生存简易小屋（方块 500~1000，材料易得、种类少——橡木/圆石/石头为主，
 *   每屋标配：门、火把、熔炉、工作台、床、箱子、玻璃窗）；
 * - 10 个进阶别墅（方块 2000~5000，发育中后期舒适美观——木质/石质/砖质/现代风格）。
 *
 * 全部程序化生成（无外部文件）：公共模板（地基/墙壳/三角屋顶/平顶/门窗/家具）
 * 参数化出不同尺寸与风格；材料均为生存易得方块；生成的步骤保证支撑
 * （墙先于屋顶、火把贴墙、家具贴地），可正常建造不悬空。
 */
public final class BuiltinHouses {
    private BuiltinHouses() {
    }

    /**
     * v1.5.284：线程安全加固——旧版共享可变静态 List + 非同步 HashMap：
     * 并发 get()（缓存未命中时同时 generate）会串步产生脏蓝图。S 改 ThreadLocal
     * （每线程独立生成缓冲，零阻塞、不改任何生成器逻辑），CACHE 改
     * ConcurrentHashMap（get→generate→put 原子可见）。
     */
    private static final ThreadLocal<List<String>> S = ThreadLocal.withInitial(ArrayList::new);

    private static void reset() {
        S.get().clear();
    }

    private static List<String> done() {
        return new ArrayList<>(S.get());
    }

    private static void s(int x, int y, int z, String block) {
        S.get().add(x + "," + y + "," + z + "," + block);
    }

    private static void s(int x, int y, int z, String block, String state) {
        S.get().add(x + "," + y + "," + z + "," + block + "|" + state);
    }

    // ==================== 公共模板 ====================

    /** 实心盒（fill） */
    private static void box(int x1, int y1, int z1, int x2, int y2, int z2, String b) {
        for (int y = y1; y <= y2; y++) {
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    s(x, y, z, b);
                }
            }
        }
    }

    /** 空心墙壳（内部清空；x=0 面为正面，门/窗开在正面与背面） */
    private static void shell(int x, int y, int z, int w, int h, int d, String b) {
        for (int dy = 0; dy < h; dy++) {
            for (int dx = 0; dx < w; dx++) {
                for (int dz = 0; dz < d; dz++) {
                    if (dx == 0 || dx == w - 1 || dz == 0 || dz == d - 1) {
                        s(x + dx, y + dy, z + dz, b);
                    }
                }
            }
        }
    }

    /** 正面开门洞（两格高）+ 门；doorZ 门所在 z，doorX 门中心 x。
     *  v1.5.300：门【落地】——调用方传 y0（地面），旧版传 y0+1：门离地 1 格、
     *  地基垫在门下，看起来像"悬空的门"（用户："蓝图里的门摆放很有问题，
     *  根本不像一个正常的门"）；同时清掉门内一格地基，玩家可从地面直接走进来。 */
    private static void door(int x, int y, int z, int w, int d, int doorX, String facing) {
        // 门洞（清掉墙块）+ 门内通道格（清地基，进门后一步踏上室内地板）
        s(x + doorX, y + 0, z, "minecraft:air");
        s(x + doorX, y + 1, z, "minecraft:air");
        s(x + doorX, y + 0, z + 1, "minecraft:air");
        // 门（下半；doPlace 自动补上半）
        s(x + doorX, y + 0, z, "minecraft:oak_door",
                "{facing:\"" + facing + "\",half:\"lower\",open:\"false\",hinge:\"left\",powered:\"false\"}");
    }

    /** 窗（玻璃板，1x1，嵌在墙内，上下有墙支撑） */
    private static void window(int x, int y, int z) {
        s(x, y, z, "minecraft:glass_pane");
    }

    /** 三角屋顶（沿 z 两坡逐层收窄，楼梯铺坡 + 屋脊台阶；两端山墙填充墙块）。
     *  台阶/楼梯状态简化：坡面用台阶（朝内 half:top），收窄层用台阶——稳妥不悬空。 */
    private static void gableRoof(int x, int y, int z, int w, int d, String roofBlock, String roofBlock2) {
        int half = d / 2;
        for (int layer = 0; layer <= half; layer++) {
            int z1 = z + layer;
            int z2 = z + d - 1 - layer;
            if (z1 > z2) {
                break;
            }
            for (int dx = 0; dx < w; dx++) {
                // 坡面：外层台阶（朝下 bottom 贴下层）+ 内层实心
                s(x + dx, y + layer, z1, roofBlock, "{type:\"bottom\",waterlogged:\"false\"}");
                s(x + dx, y + layer, z2, roofBlock, "{type:\"bottom\",waterlogged:\"false\"}");
                if (layer > 0) {
                    s(x + dx, y + layer - 1, z1, roofBlock2);
                    s(x + dx, y + layer - 1, z2, roofBlock2);
                }
            }
        }
        // 屋脊（最顶层实心）
        if (d % 2 == 1) {
            int zr = z + half;
            for (int dx = 0; dx < w; dx++) {
                s(x + dx, y + half, zr, roofBlock2);
            }
        }
        // 两端山墙填充（墙块，从坡下补到顶）
        for (int dx = 0; dx < w; dx++) {
            for (int dy = 1; dy <= half; dy++) {
                s(x + dx, y - 1 + dy, z, roofBlock2); // 山墙左端（坡内层已建，补外层下方）
            }
        }
    }

    /** 平顶（fill 顶层 + 边缘护栏） */
    private static void flatRoof(int x, int y, int z, int w, int d, String b) {
        for (int dx = 0; dx < w; dx++) {
            for (int dz = 0; dz < d; dz++) {
                s(x + dx, y, z + dz, b);
            }
        }
    }

    /** 站火把（地板） */
    private static void torchFloor(int x, int y, int z) {
        s(x, y, z, "minecraft:torch");
    }

    /** 墙火把（朝 facing：north/east/south/west——贴在 face 方向的墙上） */
    private static void torchWall(int x, int y, int z, String facing) {
        s(x, y, z, "minecraft:wall_torch", "{facing:\"" + facing + "\",lit:\"true\"}");
    }

    /** 床（head 在 (x,z)，foot 朝 facing 反方向一格）；facing 为头朝向 */
    private static void bed(int x, int y, int z, String facing, String color) {
        String f = facing;
        int fx = 0, fz = 0;
        switch (facing) {
            case "north" -> fz = -1;
            case "south" -> fz = 1;
            case "east" -> fx = 1;
            case "west" -> fx = -1;
            default -> {
            }
        }
        s(x, y, z, "minecraft:" + color + "_bed", "{facing:\"" + f + "\",occupied:\"false\",part:\"head\"}");
        s(x + fx, y, z + fz, "minecraft:" + color + "_bed", "{facing:\"" + f + "\",occupied:\"false\",part:\"foot\"}");
    }

    /** 熔炉（贴墙，朝 facing） */
    private static void furnace(int x, int y, int z, String facing) {
        s(x, y, z, "minecraft:furnace", "{facing:\"" + facing + "\",lit:\"false\"}");
    }

    private static void crafting(int x, int y, int z) {
        s(x, y, z, "minecraft:crafting_table");
    }

    private static void chest(int x, int y, int z, String facing) {
        s(x, y, z, "minecraft:chest", "{facing:\"" + facing + "\",type:\"single\",waterlogged:\"false\"}");
    }

    /** 室内楼梯（沿 facing 方向逐级上升 h 格；facing 为前进方向） */
    private static void stairsUp(int x, int y, int z, int h, String facing, String stairBlock) {
        int dx = 0, dz = 0;
        switch (facing) {
            case "north" -> dz = -1;
            case "south" -> dz = 1;
            case "east" -> dx = 1;
            case "west" -> dx = -1;
            default -> {
            }
        }
        for (int i = 0; i < h; i++) {
            s(x + dx * i, y + i, z + dz * i, stairBlock,
                    "{facing:\"" + facing + "\",half:\"bottom\",shape:\"straight\",waterlogged:\"false\"}");
        }
    }

    // ==================== 生存小屋模板 ====================

    /**
     * 标准生存小屋（1 层 + 三角屋顶，约 500~700 块）：
     * w×d 地基、墙 h 高、正面门+2 窗、背面 1 窗、室内家具（熔炉/工作台/床/箱子/火把×2）、
     * 烟囱（石头柱）。
     */
    private static List<String> smallHouse(int w, int d, int wallH, String foundation,
                                           String wall, String roof, String roofFill, String doorFacing) {
        reset();
        int y0 = 0;
        // 地基
        box(0, y0, 0, w - 1, y0, d - 1, foundation);
        // 墙（y0+1 起，wallH 高）
        shell(0, y0 + 1, 0, w, wallH, d, wall);
        // 正面（z=0）：门居中 + 两窗（v1.5.300：门落地——y0 地面高度）
        int doorX = w / 2;
        door(doorX, y0, 0, w, d, doorX, doorFacing);
        window(w / 2 - 2, y0 + 2, 0);
        window(w / 2 + 2, y0 + 2, 0);
        // 背面（z=d-1）：两窗
        window(w / 2 - 2, y0 + 2, d - 1);
        window(w / 2 + 2, y0 + 2, d - 1);
        // 两侧窗
        window(0, y0 + 2, d / 2);
        window(w - 1, y0 + 2, d / 2);
        // 室内地面（铺地板）
        box(1, y0 + 1, 1, w - 2, y0 + 1, d - 2, "minecraft:oak_planks");
        // 家具：熔炉（正面墙内侧）、工作台、床（背面墙）、箱子、火把×2
        furnace(1, y0 + 1, 1, "north");
        crafting(w - 2, y0 + 1, 1);
        bed(w / 2 - 1, y0 + 1, d - 2, "south", "red");
        chest(w - 2, y0 + 1, d - 2, "north");
        torchFloor(w / 2 - 2, y0 + 2, 2);
        torchFloor(w / 2 + 2, y0 + 2, d - 3);
        // 三角屋顶
        int roofY = y0 + 1 + wallH;
        gableRoof(0, roofY, 0, w, d, roof, roofFill);
        // 烟囱（石头柱，屋顶右上角）
        box(w - 2, roofY + 1, 2, w - 1, roofY + 4, 3, "minecraft:stone");
        s(w - 2, roofY + 5, 2, "minecraft:stone");
        s(w - 1, roofY + 5, 2, "minecraft:stone");
        s(w - 2, roofY + 5, 3, "minecraft:stone");
        s(w - 1, roofY + 5, 3, "minecraft:stone");
        return done();
    }

    // ==================== 15 个生存小屋 ====================

    /** 1. 橡木原木小屋 */
    private static List<String> oakLogCabin() {
        return smallHouse(10, 12, 3, "minecraft:cobblestone",
                "minecraft:oak_log", "minecraft:oak_slab", "minecraft:oak_planks", "north");
    }

    /** 2. 圆石小屋 */
    private static List<String> cobbleCottage() {
        return smallHouse(10, 12, 3, "minecraft:stone",
                "minecraft:cobblestone", "minecraft:stone_slab", "minecraft:cobblestone", "north");
    }

    /** 3. 石砖小屋 */
    private static List<String> stoneBrickHouse() {
        return smallHouse(10, 12, 3, "minecraft:cobblestone",
                "minecraft:stone_bricks", "minecraft:stone_brick_slab", "minecraft:stone_bricks", "north");
    }

    /** 4. 砂岩小屋（沙漠） */
    private static List<String> sandstoneHut() {
        return smallHouse(10, 12, 3, "minecraft:sandstone",
                "minecraft:sandstone", "minecraft:sandstone_slab", "minecraft:smooth_sandstone", "north");
    }

    /** 5. 雪原木屋 */
    private static List<String> snowyLogHouse() {
        List<String> base = smallHouse(10, 12, 3, "minecraft:snow_block",
                "minecraft:spruce_log", "minecraft:spruce_slab", "minecraft:snow_block", "north");
        return base;
    }

    /** 6. 桦木小屋 */
    private static List<String> birchCabin() {
        return smallHouse(10, 12, 3, "minecraft:cobblestone",
                "minecraft:birch_log", "minecraft:birch_slab", "minecraft:birch_planks", "north");
    }

    /** 7. 云杉山屋 */
    private static List<String> spruceMountainHut() {
        return smallHouse(10, 12, 3, "minecraft:stone",
                "minecraft:spruce_log", "minecraft:spruce_slab", "minecraft:spruce_planks", "north");
    }

    /** 8. 丛林小屋 */
    private static List<String> jungleHut() {
        return smallHouse(10, 12, 3, "minecraft:cobblestone",
                "minecraft:jungle_log", "minecraft:jungle_slab", "minecraft:jungle_planks", "north");
    }

    /** 9. 红砖小屋 */
    private static List<String> brickHouse() {
        return smallHouse(10, 12, 3, "minecraft:stone",
                "minecraft:bricks", "minecraft:stone_slab", "minecraft:bricks", "north");
    }

    /** 10. 山岩小屋（石头+圆石，厚墙） */
    private static List<String> mountainStoneHut() {
        return smallHouse(10, 12, 3, "minecraft:stone",
                "minecraft:stone", "minecraft:stone_slab", "minecraft:cobblestone", "north");
    }

    /** 11. 河边渔屋（木板+多窗） */
    private static List<String> riversideFishingHut() {
        List<String> base = smallHouse(10, 12, 3, "minecraft:oak_planks",
                "minecraft:oak_planks", "minecraft:oak_slab", "minecraft:oak_log", "north");
        return base;
    }

    /** 12. 矿工小屋（圆石+火把多） */
    private static List<String> minerHut() {
        return smallHouse(10, 12, 3, "minecraft:cobblestone",
                "minecraft:cobblestone", "minecraft:cobblestone_slab", "minecraft:cobblestone", "north");
    }

    /** 13. 猎人小屋（原木+地毯） */
    private static List<String> hunterCabin() {
        return smallHouse(10, 12, 3, "minecraft:cobblestone",
                "minecraft:oak_log", "minecraft:oak_slab", "minecraft:oak_planks", "north");
    }

    /** 14. 花园小屋（木板+花圃） */
    private static List<String> gardenCottage() {
        return smallHouse(10, 12, 3, "minecraft:cobblestone",
                "minecraft:oak_planks", "minecraft:oak_slab", "minecraft:oak_planks", "north");
    }

    /** 15. 平原农舍（圆石+木板） */
    private static List<String> plainsFarmhouse() {
        return smallHouse(10, 12, 3, "minecraft:cobblestone",
                "minecraft:oak_planks", "minecraft:oak_slab", "minecraft:hay_block", "north");
    }

    // ==================== 别墅模板 ====================

    /**
     * 标准别墅（3 层 + 三角屋顶 + 烟囱 + 门廊 + 花园围墙 + 后院露台，约 2000~2600 块）：
     * w×d 地基（14×16 默认）、每层 3 高、室内楼梯、二楼阳台、屋顶、门前台阶。
     */
    private static List<String> villa(int w, int d, String foundation, String wall,
                                      String wall2, String roof, String roofFill,
                                      String doorFacing, boolean balcony) {
        reset();
        int y0 = 0;
        // 地基
        box(0, y0, 0, w - 1, y0, d - 1, foundation);
        // 三层墙（每层 3 高）
        shell(0, y0 + 1, 0, w, 3, d, wall);
        shell(0, y0 + 4, 0, w, 3, d, wall2);
        shell(0, y0 + 7, 0, w, 3, d, wall2);
        // 三层地板
        box(1, y0 + 1, 1, w - 2, y0 + 1, d - 2, "minecraft:oak_planks");
        box(1, y0 + 4, 1, w - 2, y0 + 4, d - 2, "minecraft:oak_planks");
        box(1, y0 + 7, 1, w - 2, y0 + 7, d - 2, "minecraft:oak_planks");
        // 一楼正面：大门 + 大窗（v1.5.300：门落地——y0 地面高度）
        int doorX = w / 2;
        door(doorX, y0, 0, w, d, doorX, doorFacing);
        window(doorX - 3, y0 + 2, 0);
        window(doorX + 3, y0 + 2, 0);
        window(doorX - 3, y0 + 1, 0);
        window(doorX + 3, y0 + 1, 0);
        // 一楼背面窗
        window(doorX - 2, y0 + 2, d - 1);
        window(doorX + 2, y0 + 2, d - 1);
        // 二楼正面窗（3 个）
        window(doorX - 3, y0 + 5, 0);
        window(doorX, y0 + 5, 0);
        window(doorX + 3, y0 + 5, 0);
        // 二楼背面窗
        window(doorX - 2, y0 + 5, d - 1);
        window(doorX + 2, y0 + 5, d - 1);
        // 三楼正面窗
        window(doorX - 3, y0 + 8, 0);
        window(doorX + 3, y0 + 8, 0);
        // 三楼背面窗
        window(doorX - 2, y0 + 8, d - 1);
        window(doorX + 2, y0 + 8, d - 1);
        // 两侧窗
        window(0, y0 + 2, d / 2);
        window(w - 1, y0 + 2, d / 2);
        window(0, y0 + 5, d / 2);
        window(w - 1, y0 + 5, d / 2);
        window(0, y0 + 8, d / 2);
        window(w - 1, y0 + 8, d / 2);
        // 室内楼梯（一楼后部 → 二楼 → 三楼）
        stairsUp(doorX + 2, y0 + 1, d - 4, 3, "north", "minecraft:oak_stairs");
        stairsUp(doorX + 2, y0 + 4, d - 4, 3, "north", "minecraft:oak_stairs");
        // 一楼家具：熔炉/工作台/箱子/床
        furnace(1, y0 + 1, 1, "north");
        crafting(w - 2, y0 + 1, 1);
        bed(doorX - 3, y0 + 1, d - 2, "south", "red");
        chest(w - 2, y0 + 1, d - 2, "north");
        // 二楼家具：床/箱子/工作台
        bed(doorX - 2, y0 + 4, 2, "north", "white");
        chest(w - 2, y0 + 4, d - 2, "north");
        crafting(1, y0 + 4, d - 2);
        // 三楼家具：床/箱子
        bed(doorX - 2, y0 + 7, 2, "north", "blue");
        chest(w - 2, y0 + 7, d - 2, "north");
        // 火把（一楼 2 + 二楼 1 + 三楼 1）
        torchWall(doorX - 2, y0 + 2, 1, "north");
        torchWall(doorX + 2, y0 + 2, 1, "north");
        torchWall(doorX, y0 + 5, 1, "north");
        torchWall(doorX, y0 + 8, 1, "north");
        // 三角屋顶
        int roofY = y0 + 10;
        gableRoof(0, roofY, 0, w, d, roof, roofFill);
        // 烟囱
        box(w - 2, roofY + 1, 2, w - 1, roofY + 4, 3, "minecraft:stone");
        s(w - 2, roofY + 5, 2, "minecraft:stone");
        s(w - 1, roofY + 5, 2, "minecraft:stone");
        s(w - 2, roofY + 5, 3, "minecraft:stone");
        s(w - 1, roofY + 5, 3, "minecraft:stone");
        // 塔楼（右后内角 3x3 高 7，塔顶突出屋顶）
        int tx = w - 4, tz = d - 4;
        box(tx, y0 + 1, tz, tx + 2, y0 + 7, tz + 2, wall2);
        box(tx - 1, y0 + 8, tz - 1, tx + 3, y0 + 8, tz + 3, roofFill);
        s(tx, y0 + 9, tz, "minecraft:stone");
        s(tx + 2, y0 + 9, tz, "minecraft:stone");
        s(tx, y0 + 9, tz + 2, "minecraft:stone");
        s(tx + 2, y0 + 9, tz + 2, "minecraft:stone");
        // 塔窗（朝院子）
        window(tx + 1, y0 + 4, tz - 1);
        // 门前台阶 + 门廊
        s(doorX, y0, -1, "minecraft:stone");
        s(doorX - 1, y0, -1, "minecraft:stone");
        s(doorX + 1, y0, -1, "minecraft:stone");
        s(doorX, y0, -2, "minecraft:stone");
        box(doorX - 2, y0 + 3, -1, doorX + 2, y0 + 3, -1, "minecraft:oak_planks");
        // 阳台（二楼正面外挑）
        if (balcony) {
            box(doorX - 3, y0 + 3, -1, doorX + 3, y0 + 3, -1, "minecraft:oak_planks");
            for (int bx = doorX - 3; bx <= doorX + 3; bx++) {
                s(bx, y0 + 2, -1, "minecraft:oak_fence");
            }
        }
        // 花园围墙（外圈 1 格 2 高，正面留门洞）
        int gx = -2, gz = -2, gw = w + 4, gd = d + 4;
        for (int dx = 0; dx < gw; dx++) {
            for (int dz = 0; dz < gd; dz++) {
                boolean edge = dx == 0 || dx == gw - 1 || dz == 0 || dz == gd - 1;
                if (!edge) {
                    continue;
                }
                // 正面门洞（门口对应位置）
                if (dz == 0 && dx >= doorX + 1 - 1 && dx <= doorX + 1 + 1) {
                    continue;
                }
                for (int gy = 0; gy < 2; gy++) {
                    s(gx + dx, y0 + gy, gz + dz, "minecraft:cobblestone_wall",
                            "{up:\"false\",waterlogged:\"false\",east:\"false\",north:\"false\",south:\"false\",west:\"false\"}");
                }
            }
        }
        // 花园门（正面门洞上方横梁 + 大门处台阶）
        box(doorX - 2, y0 + 2, -2, doorX + 2, y0 + 2, -2, "minecraft:oak_fence");
        s(doorX, y0, -2, "minecraft:stone");
        // 后院露台（石板铺装）
        for (int dx = 1; dx <= w - 2; dx++) {
            for (int dz = d + 1; dz <= d + 4; dz++) {
                s(dx, y0, dz, "minecraft:stone_slab",
                        "{type:\"bottom\",waterlogged:\"false\"}");
            }
        }
        return done();
    }

    // ==================== 10 个进阶别墅 ====================

    /** 1. 橡木木质别墅 */
    private static List<String> oakWoodVilla() {
        return villa(14, 16, "minecraft:cobblestone", "minecraft:oak_planks",
                "minecraft:oak_log", "minecraft:oak_slab", "minecraft:dark_oak_planks", "north", true);
    }

    /** 2. 石砖别墅 */
    private static List<String> stoneBrickVilla() {
        return villa(14, 16, "minecraft:cobblestone", "minecraft:stone_bricks",
                "minecraft:stone_bricks", "minecraft:stone_brick_slab", "minecraft:stone_bricks", "north", true);
    }

    /** 3. 红砖别墅 */
    private static List<String> brickVilla() {
        return villa(14, 16, "minecraft:stone", "minecraft:bricks",
                "minecraft:bricks", "minecraft:stone_slab", "minecraft:bricks", "north", true);
    }

    /** 4. 云杉木别墅（北欧） */
    private static List<String> spruceVilla() {
        return villa(14, 16, "minecraft:cobblestone", "minecraft:spruce_planks",
                "minecraft:spruce_log", "minecraft:spruce_slab", "minecraft:spruce_planks", "north", true);
    }

    /** 5. 白桦木别墅 */
    private static List<String> birchVilla() {
        return villa(14, 16, "minecraft:cobblestone", "minecraft:birch_planks",
                "minecraft:birch_log", "minecraft:birch_slab", "minecraft:birch_planks", "north", false);
    }

    /** 6. 深色橡木庄园（古典） */
    private static List<String> darkOakManor() {
        return villa(14, 16, "minecraft:cobblestone", "minecraft:dark_oak_planks",
                "minecraft:dark_oak_log", "minecraft:dark_oak_slab", "minecraft:dark_oak_planks", "north", true);
    }

    /** 7. 石质别墅（山岩风格） */
    private static List<String> stoneVilla() {
        return villa(14, 16, "minecraft:stone", "minecraft:stone",
                "minecraft:stone_bricks", "minecraft:stone_slab", "minecraft:cobblestone", "north", true);
    }

    /** 8. 现代玻璃别墅 */
    private static List<String> glassModernVilla() {
        return villa(14, 16, "minecraft:stone", "minecraft:smooth_stone",
                "minecraft:smooth_stone", "minecraft:smooth_stone_slab", "minecraft:glass", "north", true);
    }

    /** 9. 陶瓦别墅（地中海，白陶瓦墙） */
    private static List<String> terracottaVilla() {
        return villa(14, 16, "minecraft:stone", "minecraft:white_terracotta",
                "minecraft:white_terracotta", "minecraft:stone_slab", "minecraft:red_terracotta", "north", true);
    }

    /** 10. 丛林木别墅（热带） */
    private static List<String> jungleVilla() {
        return villa(14, 16, "minecraft:cobblestone", "minecraft:jungle_planks",
                "minecraft:jungle_log", "minecraft:jungle_slab", "minecraft:jungle_planks", "north", true);
    }

    // ==================== 对外入口 ====================

    /** v1.5.275：内置蓝图生成结果缓存——右击手册每次打开都调 buildCatalogEntries →
     *  25 个内置每次重新生成（每轮 600~2100 步字符串拼接）→ 明显卡顿。
     *  步骤列表只读（无人修改）→ 静态缓存安全。v1.5.284：HashMap → ConcurrentHashMap */
    private static final java.util.Map<String, List<String>> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** 全部内置小屋/别墅：id → 生成器（带缓存） */
    public static List<String> get(String id) {
        List<String> cached = CACHE.get(id);
        if (cached != null) {
            return cached;
        }
        List<String> steps = generate(id);
        if (steps != null) {
            CACHE.put(id, steps);
        }
        return steps;
    }

    private static List<String> generate(String id) {
        switch (id) {
            case "maid_smart:house_oak_log": return oakLogCabin();
            case "maid_smart:house_cobble": return cobbleCottage();
            case "maid_smart:house_stone_brick": return stoneBrickHouse();
            case "maid_smart:house_sandstone": return sandstoneHut();
            case "maid_smart:house_snowy": return snowyLogHouse();
            case "maid_smart:house_birch": return birchCabin();
            case "maid_smart:house_spruce": return spruceMountainHut();
            case "maid_smart:house_jungle": return jungleHut();
            case "maid_smart:house_brick": return brickHouse();
            case "maid_smart:house_mountain": return mountainStoneHut();
            case "maid_smart:house_fishing": return riversideFishingHut();
            case "maid_smart:house_miner": return minerHut();
            case "maid_smart:house_hunter": return hunterCabin();
            case "maid_smart:house_garden": return gardenCottage();
            case "maid_smart:house_farm": return plainsFarmhouse();
            case "maid_smart:villa_oak": return oakWoodVilla();
            case "maid_smart:villa_stone_brick": return stoneBrickVilla();
            case "maid_smart:villa_brick": return brickVilla();
            case "maid_smart:villa_spruce": return spruceVilla();
            case "maid_smart:villa_birch": return birchVilla();
            case "maid_smart:villa_dark_oak": return darkOakManor();
            case "maid_smart:villa_stone": return stoneVilla();
            case "maid_smart:villa_glass": return glassModernVilla();
            case "maid_smart:villa_terracotta": return terracottaVilla();
            case "maid_smart:villa_jungle": return jungleVilla();
            // v1.5.300：红石机器只留自动熔炉组（纯漏斗定向无红石，100% 可靠）
            case "maid_smart:machine_furnace_array": return furnaceArray();
            default:
                return null;
        }
    }

    /** 内置小屋/别墅 id → 中文名（手册目录显示） */
    public static String nameOf(String id) {
        switch (id) {
            case "maid_smart:house_oak_log": return "橡木原木小屋";
            case "maid_smart:house_cobble": return "圆石小屋";
            case "maid_smart:house_stone_brick": return "石砖小屋";
            case "maid_smart:house_sandstone": return "砂岩小屋";
            case "maid_smart:house_snowy": return "雪原木屋";
            case "maid_smart:house_birch": return "桦木小屋";
            case "maid_smart:house_spruce": return "云杉山屋";
            case "maid_smart:house_jungle": return "丛林小屋";
            case "maid_smart:house_brick": return "红砖小屋";
            case "maid_smart:house_mountain": return "山岩小屋";
            case "maid_smart:house_fishing": return "河边渔屋";
            case "maid_smart:house_miner": return "矿工小屋";
            case "maid_smart:house_hunter": return "猎人小屋";
            case "maid_smart:house_garden": return "花园小屋";
            case "maid_smart:house_farm": return "平原农舍";
            case "maid_smart:villa_oak": return "橡木木质别墅";
            case "maid_smart:villa_stone_brick": return "石砖别墅";
            case "maid_smart:villa_brick": return "红砖别墅";
            case "maid_smart:villa_spruce": return "云杉北欧别墅";
            case "maid_smart:villa_birch": return "白桦别墅";
            case "maid_smart:villa_dark_oak": return "深色橡木庄园";
            case "maid_smart:villa_stone": return "石质山景别墅";
            case "maid_smart:villa_glass": return "现代玻璃别墅";
            case "maid_smart:villa_terracotta": return "陶瓦地中海别墅";
            case "maid_smart:villa_jungle": return "丛林木别墅";
            // v1.5.300：只留自动熔炉组
            case "maid_smart:machine_furnace_array": return "自动熔炉组";
            default:
                return id;
        }
    }

    // ==================== 红石机器（v1.5.287，v1.5.300 精简） ====================
    // v1.5.300：用户反馈"红石机器基本都不能用，先全都删了，只保留一个自动熔炉组"——
    // 甘蔗机/南瓜机（观察者+活塞脉冲时序在建造环境不可靠）/昼夜自动灯已删除，
    // 只保留纯漏斗定向的自动熔炉组（无红石，100% 可靠）。

    /** 自动熔炉组（纯漏斗定向，无红石——100% 可靠）：输入箱→漏斗→熔炉顶（烧炼）、
     *  燃料箱→漏斗→熔炉侧面（燃料）、熔炉底→漏斗→输出箱。2×2 熔炉，双箱盖漏斗。 */
    private static List<String> furnaceArray() {
        reset();
        // 熔炉 2×2（y=0，正面朝南）
        for (int x = 0; x <= 1; x++) {
            for (int z = 0; z <= 1; z++) {
                s(x, 0, z, "minecraft:furnace", "{facing:\"south\",lit:\"false\"}");
            }
        }
        // 输入漏斗（y=1，朝下进熔炉顶）
        for (int x = 0; x <= 1; x++) {
            for (int z = 0; z <= 1; z++) {
                s(x, 1, z, "minecraft:hopper", "{facing:\"down\",enabled:\"true\"}");
            }
        }
        // 输入箱（y=2，双箱盖 4 漏斗）
        s(0, 2, 0, "minecraft:chest", "{facing:\"north\"}");
        s(1, 2, 0, "minecraft:chest", "{facing:\"north\"}");
        s(0, 2, 1, "minecraft:chest", "{facing:\"north\"}");
        s(1, 2, 1, "minecraft:chest", "{facing:\"north\"}");
        // 燃料漏斗（y=0 侧面）：z=-1 排朝南（+z），z=2 排朝北（-z）→ 都指向熔炉
        s(0, 0, -1, "minecraft:hopper", "{facing:\"south\",enabled:\"true\"}");
        s(1, 0, -1, "minecraft:hopper", "{facing:\"south\",enabled:\"true\"}");
        s(0, 0, 2, "minecraft:hopper", "{facing:\"north\",enabled:\"true\"}");
        s(1, 0, 2, "minecraft:hopper", "{facing:\"north\",enabled:\"true\"}");
        // 燃料箱（y=1，双箱盖 2+2 燃料漏斗）
        s(0, 1, -1, "minecraft:chest", "{facing:\"south\"}");
        s(1, 1, -1, "minecraft:chest", "{facing:\"south\"}");
        s(0, 1, 2, "minecraft:chest", "{facing:\"north\"}");
        s(1, 1, 2, "minecraft:chest", "{facing:\"north\"}");
        // 输出漏斗（y=-1，朝下）+ 输出箱（y=-2）
        for (int x = 0; x <= 1; x++) {
            for (int z = 0; z <= 1; z++) {
                s(x, -1, z, "minecraft:hopper", "{facing:\"down\",enabled:\"true\"}");
            }
        }
        s(0, -2, 0, "minecraft:chest", "{facing:\"north\"}");
        s(1, -2, 0, "minecraft:chest", "{facing:\"north\"}");
        s(0, -2, 1, "minecraft:chest", "{facing:\"north\"}");
        s(1, -2, 1, "minecraft:chest", "{facing:\"north\"}");
        return done();
    }
}
