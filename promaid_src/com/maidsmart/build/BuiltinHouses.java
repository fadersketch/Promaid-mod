package com.maidsmart.build;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置蓝图生成器（v1.5.366 改版）：
 * - 3 个经典生存小屋（方块 500~700，原木/雪原/沙漠三种材质风，每屋标配：
 *   门、火把、熔炉、工作台、床、箱子、玻璃窗——v1.5.366 删 12 个同款盒）；
 * - 12 种不同结构建筑（方块 500~5000，A字架/瞭望塔/灯塔/庭院/谷仓/高脚屋/
 *   风车/地堡/树屋/坡地阶梯/双子屋/城堡门楼——v1.5.366 新增）；
 * - 3 个进阶别墅（方块 2000~2600，石质/玻璃/陶瓦——v1.5.366 删 7 个同款盒）。
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

    /** 梯子（贴墙，facing = 贴墙方向） */
    private static void ladder(int x, int y, int z, String facing) {
        s(x, y, z, "minecraft:ladder", "{facing:\"" + facing + "\",waterlogged:\"false\"}");
    }

    /** 实心圆盘（灯塔/风车/冰屋的圆层用） */
    private static void circleFill(int cx, int cz, int r, int y, String b) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz <= r * r) {
                    s(cx + dx, y, cz + dz, b);
                }
            }
        }
    }

    /** 空心圆环（圆塔墙） */
    private static void circleRing(int cx, int cz, int r, int y, String b) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int dist = dx * dx + dz * dz;
                if (dist <= r * r && dist > (r - 1) * (r - 1)) {
                    s(cx + dx, y, cz + dz, b);
                }
            }
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
        // v1.5.366：门 x 传【墙原点 0】（旧版传 doorX，door() 内部再 +doorX →
        // 门被放到 x=2*doorX=屋外一格，门"不在正面墙上"）
        int doorX = w / 2;
        door(0, y0, 0, w, d, doorX, doorFacing);
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

    // ==================== 生存小屋（v1.5.366：删雷同，保留 3 种材质系） ====================
    // v1.5.366：旧 15 个小屋全是 smallHouse 同款盒（仅材质不同，用户："种类很多但构造都
    // 大差不差"）——删 12 个，保留 3 种材质风格（原木/雪原/沙漠），新增 12 种不同结构建筑。
    /** 1. 橡木原木小屋（经典原木风） */
    private static List<String> oakLogCabin() {
        return smallHouse(10, 12, 3, "minecraft:cobblestone",
                "minecraft:oak_log", "minecraft:oak_slab", "minecraft:oak_planks", "north");
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

    // ==================== v1.5.366：新增 12 种不同结构建筑 ====================

    /** A 字架小屋——两面斜墙交汇成屋脊（三角截面），两端山墙封板，门开在山墙 */
    private static List<String> aFrameHut() {
        reset();
        int w = 9, d = 12, y0 = 0, ridge = 4;
        box(0, y0, 0, w - 1, y0, d - 1, "minecraft:oak_planks");
        for (int y = 1; y <= ridge; y++) {
            int inset = ridge + 1 - y;
            for (int z = inset; z <= d - 1 - inset; z++) {
                for (int x = 0; x < w; x++) {
                    s(x, y, z, "minecraft:oak_planks");
                }
            }
        }
        for (int y = 1; y <= ridge; y++) {
            int inset = ridge + 1 - y;
            for (int z = 0; z < inset; z++) {
                for (int x = 0; x < w; x++) {
                    s(x, y, z, "minecraft:oak_log");
                    s(x, y, d - 1 - z, "minecraft:oak_log");
                }
            }
        }
        door(0, y0, 0, w, d, w / 2, "north");
        window(2, y0 + 2, d / 2);
        window(w - 3, y0 + 2, d / 2);
        bed(2, y0 + 1, d - 3, "south", "red");
        crafting(w - 3, y0 + 1, 2);
        furnace(2, y0 + 1, 2, "north");
        chest(w - 3, y0 + 1, d - 3, "north");
        torchFloor(w / 2, y0 + 2, 2);
        torchFloor(w / 2, y0 + 2, d - 3);
        return done();
    }

    /** 瞭望塔——9×9 基座 + 7×7 空心塔身 16 高 + 垛口顶，内部梯子，箭孔窗 */
    private static List<String> watchtower() {
        reset();
        int y0 = 0, w = 7, h = 16;
        box(-1, y0, -1, w, y0, w, "minecraft:stone");
        for (int y = y0 + 1; y <= y0 + h; y++) {
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < w; z++) {
                    if (x == 0 || x == w - 1 || z == 0 || z == w - 1) {
                        s(x, y, z, "minecraft:stone_bricks");
                    }
                }
            }
        }
        door(0, y0, 0, w, w, w / 2, "north");
        for (int y = y0 + 1; y <= y0 + h; y++) {
            ladder(1, y, w / 2, "west");
        }
        window(w / 2, y0 + 4, 0);
        window(w / 2, y0 + 8, 0);
        window(0, y0 + 4, w / 2);
        window(w - 1, y0 + 4, w / 2);
        box(1, y0 + h, 1, w - 2, y0 + h, w - 2, "minecraft:oak_planks");
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < w; z++) {
                boolean corner = (x == 0 || x == w - 1) && (z == 0 || z == w - 1);
                boolean edge = x == 0 || x == w - 1 || z == 0 || z == w - 1;
                if (corner) {
                    s(x, y0 + h + 1, z, "minecraft:stone_bricks");
                    s(x, y0 + h + 2, z, "minecraft:stone_bricks");
                } else if (edge) {
                    s(x, y0 + h + 1, z, "minecraft:stone_brick_wall",
                            "{up:\"false\",waterlogged:\"false\",east:\"false\",north:\"false\",south:\"false\",west:\"false\"}");
                }
            }
        }
        torchFloor(w / 2, y0 + h + 1, w / 2);
        return done();
    }

    /** 灯塔——圆塔（r4 高 16）红白条纹 + 顶部灯室（海晶灯），内部梯子 */
    private static List<String> lighthouse() {
        reset();
        int y0 = 0, cx = 4, cz = 4, r = 4, h = 16;
        circleFill(cx, cz, r + 1, y0, "minecraft:stone");
        for (int y = y0 + 1; y <= y0 + h; y++) {
            String b = ((y - y0) / 3 % 2 == 0) ? "minecraft:red_concrete" : "minecraft:white_concrete";
            circleRing(cx, cz, r, y, b);
        }
        s(cx, y0, cz - r, "minecraft:air");
        s(cx, y0 + 1, cz - r, "minecraft:air");
        s(cx, y0, cz - r + 1, "minecraft:air");
        s(cx, y0, cz - r, "minecraft:oak_door", "{facing:\"north\",half:\"lower\",open:\"false\",hinge:\"left\",powered:\"false\"}");
        for (int y = y0 + 1; y <= y0 + h; y++) {
            ladder(cx, y, cz - r + 1, "north");
        }
        for (int y = y0 + 2; y <= y0 + h - 3; y += 3) {
            window(cx, y, cz - r);
            window(cx, y, cz + r);
            window(cx - r, y, cz);
            window(cx + r, y, cz);
        }
        circleFill(cx, cz, r, y0 + h + 1, "minecraft:glass");
        circleRing(cx, cz, r, y0 + h + 2, "minecraft:iron_bars");
        circleFill(cx, cz, r - 1, y0 + h + 2, "minecraft:sea_lantern");
        circleFill(cx, cz, r, y0 + h + 3, "minecraft:red_concrete");
        s(cx, y0 + h + 4, cz, "minecraft:red_concrete");
        return done();
    }

    /** 庭院屋——16×16 外墙环抱 8×8 露天中庭，平顶环 + 中庭护栏，四角布置家具 */
    private static List<String> courtyardHouse() {
        reset();
        int y0 = 0, w = 16, d = 16;
        box(0, y0, 0, w - 1, y0, d - 1, "minecraft:stone");
        for (int y = y0 + 1; y <= y0 + 3; y++) {
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    if (x == 0 || x == w - 1 || z == 0 || z == d - 1) {
                        s(x, y, z, "minecraft:stone_bricks");
                    }
                }
            }
        }
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                boolean open = x >= 3 && x <= 12 && z >= 3 && z <= 12;
                if (!open) {
                    s(x, y0 + 4, z, "minecraft:stone_bricks");
                }
            }
        }
        for (int x = 3; x <= 12; x++) {
            s(x, y0 + 5, 3, "minecraft:stone_brick_wall", "{up:\"false\",waterlogged:\"false\",east:\"false\",north:\"false\",south:\"false\",west:\"false\"}");
            s(x, y0 + 5, 12, "minecraft:stone_brick_wall", "{up:\"false\",waterlogged:\"false\",east:\"false\",north:\"false\",south:\"false\",west:\"false\"}");
        }
        for (int z = 4; z <= 11; z++) {
            s(3, y0 + 5, z, "minecraft:stone_brick_wall", "{up:\"false\",waterlogged:\"false\",east:\"false\",north:\"false\",south:\"false\",west:\"false\"}");
            s(12, y0 + 5, z, "minecraft:stone_brick_wall", "{up:\"false\",waterlogged:\"false\",east:\"false\",north:\"false\",south:\"false\",west:\"false\"}");
        }
        door(0, y0, 0, w, d, w / 2, "south");
        for (int x = 4; x <= 11; x++) {
            for (int z = 4; z <= 11; z++) {
                s(x, y0, z, "minecraft:oak_planks");
            }
        }
        window(1, y0 + 2, 8);
        window(14, y0 + 2, 8);
        window(8, y0 + 2, 1);
        window(8, y0 + 2, 14);
        bed(1, y0 + 1, 1, "south", "red");
        bed(14, y0 + 1, 1, "south", "white");
        furnace(1, y0 + 1, 14, "north");
        crafting(14, y0 + 1, 14);
        chest(1, y0 + 1, 8, "north");
        torchFloor(8, y0 + 2, 4);
        torchFloor(8, y0 + 2, 11);
        return done();
    }

    /** 谷仓——12×16 大空间 + 高双坡屋顶 + 双开大门 + 干草堆 */
    private static List<String> barn() {
        reset();
        int w = 12, d = 16, y0 = 0;
        box(0, y0, 0, w - 1, y0, d - 1, "minecraft:stone");
        shell(0, y0 + 1, 0, w, 3, d, "minecraft:spruce_planks");
        door(0, y0, 0, w, d, w / 2 - 1, "south");
        door(0, y0, 0, w, d, w / 2, "south");
        window(w / 2 - 2, y0 + 2, 0);
        window(w / 2 + 2, y0 + 2, 0);
        window(w / 2 - 2, y0 + 2, d - 1);
        window(w / 2 + 2, y0 + 2, d - 1);
        window(0, y0 + 2, d / 2);
        window(w - 1, y0 + 2, d / 2);
        box(1, y0 + 1, 1, w - 2, y0 + 1, d - 2, "minecraft:oak_planks");
        gableRoof(0, y0 + 4, 0, w, d, "minecraft:spruce_slab", "minecraft:spruce_planks");
        for (int x = 1; x <= 3; x++) {
            for (int z = 2; z <= 4; z++) {
                s(x, y0 + 1, z, "minecraft:hay_block");
            }
        }
        chest(w - 3, y0 + 1, d - 3, "north");
        crafting(2, y0 + 1, d - 3);
        torchFloor(w / 2, y0 + 2, 3);
        torchFloor(w / 2, y0 + 2, d - 4);
        return done();
    }

    /** 高脚小屋——木屋架在 6 根高柱上（架空层），梯子上楼，带门廊 */
    private static List<String> stiltCabin() {
        reset();
        int w = 9, d = 14, y0 = 0, lift = 3;
        int[][] posts = {{0, 0}, {w - 1, 0}, {0, d - 1}, {w - 1, d - 1}, {0, d / 2}, {w - 1, d / 2}};
        for (int[] p : posts) {
            for (int y = y0; y < y0 + lift; y++) {
                s(p[0], y, p[1], "minecraft:oak_log");
            }
        }
        box(0, y0 + lift, 0, w - 1, y0 + lift, d - 1, "minecraft:oak_planks");
        shell(0, y0 + lift + 1, 0, w, 2, d, "minecraft:oak_planks");
        gableRoof(0, y0 + lift + 3, 0, w, d, "minecraft:oak_slab", "minecraft:oak_log");
        box(w / 2 - 2, y0 + lift, -1, w / 2 + 2, y0 + lift, -1, "minecraft:oak_planks");
        for (int y = y0; y < y0 + lift; y++) {
            ladder(w / 2 + 1, y, -1, "south");
        }
        door(0, y0 + lift, 0, w, d, w / 2, "north");
        window(2, y0 + lift + 1, d / 2);
        window(w - 3, y0 + lift + 1, d / 2);
        chest(1, y0, d / 2, "north");
        bed(1, y0 + lift + 1, d - 2, "south", "red");
        furnace(1, y0 + lift + 1, 1, "north");
        crafting(w - 2, y0 + lift + 1, d - 2);
        torchFloor(w / 2, y0 + lift + 2, d / 2);
        return done();
    }

    /** 风车——圆石圆塔（r4 高 16）+ 锥顶 + 十字木翼 + 室内磨坊 */
    private static List<String> windmill() {
        reset();
        int cx = 4, cz = 4, y0 = 0, r = 4, h = 16;
        circleFill(cx, cz, r + 1, y0, "minecraft:stone");
        for (int y = y0 + 1; y <= y0 + h; y++) {
            circleRing(cx, cz, r, y, "minecraft:cobblestone");
        }
        s(cx, y0, cz - r, "minecraft:air");
        s(cx, y0 + 1, cz - r, "minecraft:air");
        s(cx, y0, cz - r + 1, "minecraft:air");
        s(cx, y0, cz - r, "minecraft:oak_door", "{facing:\"north\",half:\"lower\",open:\"false\",hinge:\"left\",powered:\"false\"}");
        for (int y = y0 + 1; y <= y0 + h - 1; y++) {
            ladder(cx, y, cz - r + 1, "north");
        }
        window(cx, y0 + 4, cz - r);
        window(cx - r, y0 + 6, cz);
        for (int y = y0 + h + 1; y <= y0 + h + 3; y++) {
            int rr = y0 + h + 4 - y;
            circleRing(cx, cz, rr, y, "minecraft:cobblestone");
        }
        s(cx, y0 + h + 4, cz, "minecraft:cobblestone");
        int wy = y0 + h + 1;
        for (int i = 1; i <= 4; i++) {
            s(cx + i, wy, cz, "minecraft:oak_fence");
            s(cx - i, wy, cz, "minecraft:oak_fence");
            s(cx, wy, cz + i, "minecraft:oak_fence");
            s(cx, wy, cz - i, "minecraft:oak_fence");
            s(cx + i, wy + 1, cz, "minecraft:oak_planks");
            s(cx - i, wy + 1, cz, "minecraft:oak_planks");
            s(cx, wy + 1, cz + i, "minecraft:oak_planks");
            s(cx, wy + 1, cz - i, "minecraft:oak_planks");
        }
        bed(cx - 1, y0 + 1, cz - 1, "south", "red");
        chest(cx + 1, y0 + 1, cz + 1, "north");
        s(cx, y0 + 1, cz, "minecraft:crafting_table");
        torchFloor(cx, y0 + 2, cz);
        // 磨坊附间（南侧小房间）
        box(cx - 2, y0, cz + r, cx + 2, y0, cz + r + 2, "minecraft:cobblestone");
        shell(cx - 2, y0 + 1, cz + r, 5, 2, 3, "minecraft:cobblestone");
        flatRoof(cx - 2, y0 + 3, cz + r, 5, 3, "minecraft:cobblestone");
        s(cx, y0, cz + r + 2, "minecraft:air");
        s(cx, y0 + 1, cz + r + 2, "minecraft:air");
        s(cx, y0, cz + r + 2, "minecraft:oak_door", "{facing:\"south\",half:\"lower\",open:\"false\",hinge:\"left\",powered:\"false\"}");
        s(cx, y0 + 1, cz + r, "minecraft:chest", "{facing:\"south\",type:\"single\",waterlogged:\"false\"}");
        return done();
    }

    /** 半地堡屋——厚石墙 6 高的防御小屋，下沉式入口坡道 + 天窗 */
    private static List<String> bunkerHut() {
        reset();
        int w = 11, d = 13, y0 = 0;
        box(0, y0 - 1, 0, w - 1, y0 - 1, d - 1, "minecraft:stone");
        shell(0, y0, 0, w, 6, d, "minecraft:stone");
        box(1, y0 - 1, 1, w - 2, y0 - 1, d - 2, "minecraft:oak_planks");
        for (int z = -1; z >= -4; z--) {
            for (int x = w / 2 - 1; x <= w / 2 + 1; x++) {
                s(x, y0 - 1 - (-1 - z), z, "minecraft:stone_slab", "{type:\"bottom\",waterlogged:\"false\"}");
            }
        }
        door(0, y0, 0, w, d, w / 2, "north");
        s(w / 2 - 1, y0 + 5, d / 2 - 1, "minecraft:glass");
        s(w / 2, y0 + 5, d / 2 - 1, "minecraft:glass");
        s(w / 2 - 1, y0 + 5, d / 2, "minecraft:glass");
        s(w / 2, y0 + 5, d / 2, "minecraft:glass");
        window(0, y0 + 2, d / 2);
        window(w - 1, y0 + 2, d / 2);
        bed(1, y0, 1, "south", "red");
        furnace(1, y0, d - 2, "north");
        crafting(w - 2, y0, d - 2);
        chest(w - 2, y0, 1, "north");
        torchFloor(w / 2, y0 + 1, 3);
        torchFloor(w / 2, y0 + 1, d - 4);
        return done();
    }

    /** 树屋——粗大树干（4×4 云杉原木 8 高）+ 平台树屋（带护栏）+ 梯子上树 */
    private static List<String> treeBase() {
        reset();
        int y0 = 0;
        box(3, y0, 3, 6, y0 + 7, 6, "minecraft:spruce_log");
        box(0, y0 + 8, 0, 8, y0 + 8, 8, "minecraft:oak_planks");
        shell(0, y0 + 9, 0, 9, 2, 9, "minecraft:spruce_planks");
        gableRoof(0, y0 + 11, 0, 9, 9, "minecraft:spruce_slab", "minecraft:spruce_log");
        for (int y = y0; y < y0 + 8; y++) {
            ladder(2, y, 4, "east");
        }
        for (int x = 0; x <= 8; x++) {
            s(x, y0 + 9, 0, "minecraft:oak_fence");
            s(x, y0 + 9, 8, "minecraft:oak_fence");
        }
        for (int z = 1; z <= 7; z++) {
            s(0, y0 + 9, z, "minecraft:oak_fence");
            s(8, y0 + 9, z, "minecraft:oak_fence");
        }
        door(0, y0 + 8, 0, 9, 9, 4, "north");
        window(2, y0 + 10, 8);
        window(6, y0 + 10, 8);
        bed(1, y0 + 10, 1, "south", "red");
        chest(7, y0 + 10, 7, "north");
        furnace(1, y0 + 10, 7, "north");
        torchFloor(4, y0 + 11, 4);
        return done();
    }

    /** 坡地阶梯屋——三级阶梯式平台逐级抬升（依坡而建），每级一个小功能间 */
    private static List<String> hillsideHut() {
        reset();
        int y0 = 0;
        box(0, y0, 0, 9, y0, 9, "minecraft:stone");
        shell(0, y0 + 1, 0, 10, 3, 10, "minecraft:cobblestone");
        flatRoof(0, y0 + 4, 0, 10, 10, "minecraft:stone_bricks");
        door(0, y0, 0, 10, 10, 5, "north");
        window(2, y0 + 2, 0);
        window(7, y0 + 2, 0);
        int y1 = y0 + 4;
        box(4, y1, 0, 11, y1, 7, "minecraft:stone");
        shell(4, y1 + 1, 0, 8, 3, 8, "minecraft:stone_bricks");
        flatRoof(4, y1 + 4, 0, 8, 8, "minecraft:cobblestone");
        int y2 = y1 + 4;
        box(8, y2, 0, 15, y2, 5, "minecraft:stone");
        shell(8, y2 + 1, 0, 8, 3, 6, "minecraft:spruce_planks");
        gableRoof(8, y2 + 4, 0, 8, 6, "minecraft:spruce_slab", "minecraft:spruce_log");
        stairsUp(9, y1 + 4, 7, 2, "south", "minecraft:stone_stairs");
        bed(1, y0 + 1, 1, "south", "red");
        furnace(1, y0 + 1, 8, "north");
        chest(8, y0 + 1, 8, "north");
        crafting(5, y1 + 1, 1);
        bed(6, y1 + 1, 1, "south", "white");
        chest(10, y2 + 1, 1, "north");
        torchFloor(5, y0 + 2, 5);
        torchFloor(8, y1 + 2, 4);
        torchFloor(12, y2 + 2, 3);
        return done();
    }

    /** 双子屋——两座 8×10 小屋隔 2 格相望，连廊相接，共用前院 */
    private static List<String> dualCabin() {
        reset();
        int y0 = 0, rx = 10;
        box(0, y0, 0, 7, y0, 9, "minecraft:stone");
        shell(0, y0 + 1, 0, 8, 3, 10, "minecraft:birch_log");
        gableRoof(0, y0 + 4, 0, 8, 10, "minecraft:birch_slab", "minecraft:birch_planks");
        door(0, y0, 0, 8, 10, 4, "north");
        box(rx, y0, 0, rx + 7, y0, 9, "minecraft:stone");
        shell(rx, y0 + 1, 0, 8, 3, 10, "minecraft:dark_oak_log");
        gableRoof(rx, y0 + 4, 0, 8, 10, "minecraft:dark_oak_slab", "minecraft:dark_oak_planks");
        door(rx, y0, 0, 8, 10, 4, "north");
        box(8, y0, 0, 9, y0, 9, "minecraft:stone");
        for (int z = 0; z <= 9; z++) {
            s(8, y0 + 1, z, "minecraft:oak_fence");
            s(9, y0 + 1, z, "minecraft:oak_fence");
        }
        box(8, y0 + 3, 0, 9, y0 + 3, 9, "minecraft:oak_planks");
        for (int x = 0; x <= rx + 7; x++) {
            s(x, y0, -1, "minecraft:stone_slab", "{type:\"bottom\",waterlogged:\"false\"}");
        }
        bed(1, y0 + 1, 1, "south", "red");
        chest(6, y0 + 1, 1, "north");
        torchFloor(4, y0 + 2, 2);
        furnace(rx + 1, y0 + 1, 1, "north");
        crafting(rx + 6, y0 + 1, 1);
        chest(rx + 1, y0 + 1, 8, "north");
        torchFloor(rx + 4, y0 + 2, 2);
        return done();
    }

    /** 城堡门楼——两座方塔夹门楼 + 中庭过道，塔顶垛口，内部梯子 */
    private static List<String> gatehouse() {
        reset();
        int y0 = 0;
        box(0, y0, 0, 4, y0, 4, "minecraft:stone");
        box(13, y0, 0, 17, y0, 4, "minecraft:stone");
        for (int y = y0 + 1; y <= y0 + 9; y++) {
            for (int x = 0; x <= 4; x++) {
                for (int z = 0; z <= 4; z++) {
                    if (x == 0 || x == 4 || z == 0 || z == 4) {
                        s(x, y, z, "minecraft:stone_bricks");
                    }
                }
            }
            for (int x = 13; x <= 17; x++) {
                for (int z = 0; z <= 4; z++) {
                    if (x == 13 || x == 17 || z == 0 || z == 4) {
                        s(x, y, z, "minecraft:stone_bricks");
                    }
                }
            }
        }
        box(5, y0, 0, 12, y0, 4, "minecraft:stone");
        shell(5, y0 + 1, 0, 8, 6, 5, "minecraft:stone_bricks");
        box(5, y0 + 7, 0, 12, y0 + 7, 4, "minecraft:stone_bricks");
        door(5, y0, 0, 8, 5, 4, "north");
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                if ((x == 0 || x == 4) && (z == 0 || z == 4)) {
                    s(x, y0 + 10, z, "minecraft:stone_bricks");
                    s(x, y0 + 11, z, "minecraft:stone_bricks");
                }
            }
        }
        for (int x = 13; x <= 17; x++) {
            for (int z = 0; z <= 4; z++) {
                if ((x == 13 || x == 17) && (z == 0 || z == 4)) {
                    s(x, y0 + 10, z, "minecraft:stone_bricks");
                    s(x, y0 + 11, z, "minecraft:stone_bricks");
                }
            }
        }
        for (int y = y0 + 1; y <= y0 + 9; y++) {
            ladder(1, y, 2, "west");
            ladder(14, y, 2, "west");
        }
        window(2, y0 + 4, 0);
        window(15, y0 + 4, 0);
        bed(6, y0 + 1, 1, "south", "red");
        chest(11, y0 + 1, 1, "north");
        crafting(9, y0 + 1, 1);
        torchFloor(6, y0 + 2, 3);
        torchFloor(11, y0 + 2, 3);
        return done();
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
        // 一楼正面：大门 + 大窗（v1.5.300：门落地——y0 地面高度；v1.5.366：门 x 传墙
        // 原点 0——旧版传 doorX 导致门被放到 x=2*doorX=屋外一格）
        int doorX = w / 2;
        door(0, y0, 0, w, d, doorX, doorFacing);
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

    // ==================== 进阶别墅（v1.5.366：删雷同，保留 3 种风格） ====================
    // v1.5.366：旧 10 个别墅全是 villa 同款盒（仅材质不同）——删 7 个，保留
    // 石质山景 / 现代玻璃 / 陶瓦地中海 三种风格。

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

    // ==================== 农场蓝图（v1.5.369：仿 MCBlanky《10 个 1 分钟农场》全新版） ====================
    // 6 座生存农场：仙人掌塔 / 超级熔炉组 / 无限岩浆泉 / 高效作物农场 / 自动伐木场 / 全自动养鸡场。
    // 设计原则：无实体（矿车/生物无法由女仆放置，原视频用漏斗矿车的熔炉组改为纯漏斗）；
    // 红石尽量简化可靠（拉杆/红石粉/火把/漏斗，避开 0-tick 观察者时钟的建造期时序问题）；
    // 流体步骤（水/岩浆）由女仆用桶放置，recalcRedstone 在完工后唤醒红石。

    /** 1. 仙人掌塔农场（farm_cactus）——11×11 石砖围墙两层塔式：
     *  沙环 + 仙人掌 + 栅栏断顶环（y=3/y=7，离地 3 格碰碎第 3 格）+ 四角水流 +
     *  正中 3×3 收集井（漏斗→箱子，梯子下井）。无红石，全自动。
     *  v1.5.369：仿 MCBlanky 视频仙人掌农场，加高成两层塔（视频同款"盖整座仙人掌塔"思路）。 */
    private static List<String> cactusTower() {
        reset();
        // 收集井（正中 3×3，3 格深 y=0..-2）：先清空井内，再放机器
        for (int x = 4; x <= 6; x++) {
            for (int z = 4; z <= 6; z++) {
                s(x, 0, z, "minecraft:air");
                s(x, -1, z, "minecraft:air");
                s(x, -2, z, "minecraft:air");
            }
        }
        s(5, -2, 5, "minecraft:hopper", "{facing:\"west\",enabled:\"true\"}");
        s(4, -2, 5, "minecraft:chest", "{facing:\"east\",type:\"single\",waterlogged:\"false\"}");
        ladder(5, -1, 4, "north");
        ladder(5, -2, 4, "north");
        // 第一层围墙（y=0..2，11×11）+ 门 + 窗
        shell(0, 0, 0, 11, 3, 11, "minecraft:stone_bricks");
        door(0, 0, 0, 11, 11, 5, "south");
        window(2, 1, 0);
        window(8, 1, 0);
        window(0, 1, 2);
        window(10, 1, 2);
        window(0, 1, 8);
        window(10, 1, 8);
        // 沙环（y=0，留 4 个缺口让水流进中央井）+ 仙人掌（y=1，只种外缘——断顶环只碰外缘那圈）
        for (int x = 2; x <= 8; x++) {
            for (int z = 2; z <= 8; z++) {
                if (x >= 4 && x <= 6 && z >= 4 && z <= 6) {
                    continue; // 中央井口
                }
                if ((x == 5 && z == 2) || (x == 5 && z == 8)
                        || (x == 2 && z == 5) || (x == 8 && z == 5)) {
                    s(x, 0, z, "minecraft:air"); // 沙环缺口 = 水流道
                    continue;
                }
                s(x, 0, z, "minecraft:sand");
                if (x == 2 || x == 8 || z == 2 || z == 8) {
                    s(x, 1, z, "minecraft:cactus");
                }
            }
        }
        // 四角水（墙脚，沿留空道冲向中央井）
        s(1, 0, 1, "minecraft:water");
        s(9, 0, 1, "minecraft:water");
        s(1, 0, 9, "minecraft:water");
        s(9, 0, 9, "minecraft:water");
        // 墙火把（避开正门）
        torchWall(1, 1, 4, "west");
        torchWall(9, 1, 6, "east");
        torchWall(4, 1, 1, "north");
        // 第二层楼板环（y=3，石砖，兼作第一层断顶环——离地 3 格碰碎仙人掌第 3 格）
        for (int z = 1; z <= 9; z++) {
            s(1, 3, z, "minecraft:stone_bricks");
            s(9, 3, z, "minecraft:stone_bricks");
        }
        for (int x = 2; x <= 8; x++) {
            s(x, 3, 1, "minecraft:stone_bricks");
            s(x, 3, 9, "minecraft:stone_bricks");
        }
        // 第二层围墙（y=3..5）+ 窗
        shell(0, 3, 0, 11, 3, 11, "minecraft:stone_bricks");
        window(2, 4, 0);
        window(8, 4, 0);
        // 第二层沙环 + 仙人掌（y=4/y=5，只种外缘）+ 四角水（y=4，站第二层楼板环上）
        for (int x = 2; x <= 8; x++) {
            for (int z = 2; z <= 8; z++) {
                if (x >= 4 && x <= 6 && z >= 4 && z <= 6) {
                    continue;
                }
                if ((x == 5 && z == 2) || (x == 5 && z == 8)
                        || (x == 2 && z == 5) || (x == 8 && z == 5)) {
                    continue; // 水流道缺口（楼板环已封，缺口处不铺沙）
                }
                s(x, 4, z, "minecraft:sand");
                if (x == 2 || x == 8 || z == 2 || z == 8) {
                    s(x, 5, z, "minecraft:cactus");
                }
            }
        }
        s(1, 4, 1, "minecraft:water");
        s(9, 4, 1, "minecraft:water");
        s(1, 4, 9, "minecraft:water");
        s(9, 4, 9, "minecraft:water");
        // 第二层断顶环（y=7，浮空石砖，离第二层沙 3 格）
        for (int z = 1; z <= 9; z++) {
            s(1, 7, z, "minecraft:stone_bricks");
            s(9, 7, z, "minecraft:stone_bricks");
        }
        for (int x = 2; x <= 8; x++) {
            s(x, 7, 1, "minecraft:stone_bricks");
            s(x, 7, 9, "minecraft:stone_bricks");
        }
        return done();
    }

    /** 2. 超级熔炉组（farm_superfurnace）——6 座熔炉一字排开，纯漏斗定向（无红石 100% 可靠）：
     *  输入箱→漏斗→熔炉顶；燃料箱→漏斗→熔炉背面；熔炉底→漏斗→输出箱。石砖机棚。 */
    private static List<String> superFurnace() {
        reset();
        // 机棚地板（y=0）
        box(0, 0, 0, 7, 0, 3, "minecraft:stone_bricks");
        // 输出（y=-1 漏斗 / y=-2 箱）
        for (int x = 0; x <= 5; x++) {
            s(x, -1, 1, "minecraft:hopper", "{facing:\"down\",enabled:\"true\"}");
        }
        for (int x = 0; x <= 5; x++) {
            s(x, -2, 1, "minecraft:chest", "{facing:\"north\",type:\"single\",waterlogged:\"false\"}");
        }
        // 熔炉 6 座（y=0，朝南）
        for (int x = 0; x <= 5; x++) {
            s(x, 0, 1, "minecraft:furnace", "{facing:\"south\",lit:\"false\"}");
        }
        // 输入漏斗（y=1，进熔炉顶）+ 输入箱（y=2）
        for (int x = 0; x <= 5; x++) {
            s(x, 1, 1, "minecraft:hopper", "{facing:\"down\",enabled:\"true\"}");
            s(x, 2, 1, "minecraft:chest", "{facing:\"north\",type:\"single\",waterlogged:\"false\"}");
        }
        // 燃料漏斗（y=0 背面 z=0 排，朝南进熔炉背面）+ 燃料箱（y=1）
        for (int x = 0; x <= 5; x++) {
            s(x, 0, 0, "minecraft:hopper", "{facing:\"south\",enabled:\"true\"}");
            s(x, 1, 0, "minecraft:chest", "{facing:\"south\",type:\"single\",waterlogged:\"false\"}");
        }
        // 后墙（y=1..2，z=0 排背面的燃料箱上方留空取放）+ 侧墙 + 前立柱
        for (int x = 0; x <= 7; x++) {
            s(x, 2, 0, "minecraft:stone_bricks");
        }
        for (int y = 1; y <= 2; y++) {
            s(7, y, 0, "minecraft:stone_bricks");
            s(7, y, 1, "minecraft:stone_bricks");
            s(7, y, 2, "minecraft:stone_bricks");
            s(7, y, 3, "minecraft:stone_bricks");
            s(0, y, 2, "minecraft:stone_bricks");
            s(0, y, 3, "minecraft:stone_bricks");
        }
        // 前立柱（z=3 前排两角，撑顶棚）+ 后立柱（z=0，避开燃料箱 y=1）+ 顶棚（y=3）
        for (int y = 1; y <= 3; y++) {
            s(0, y, 3, "minecraft:stone_bricks");
            s(7, y, 3, "minecraft:stone_bricks");
        }
        for (int y = 2; y <= 3; y++) {
            s(0, y, 0, "minecraft:stone_bricks");
            s(7, y, 0, "minecraft:stone_bricks");
        }
        box(0, 3, 0, 7, 3, 3, "minecraft:stone_bricks");
        // 照明
        s(3, 4, 1, "minecraft:lantern", "{hanging:\"false\",waterlogged:\"false\"}");
        s(4, 4, 1, "minecraft:lantern", "{hanging:\"false\",waterlogged:\"false\"}");
        return done();
    }

    /** 3. 无限岩浆泉（farm_lavafountain）——5 口炼药锅 + 滴水石锥 + 岩浆池：
     *  锅上 4 格高石砖井道，井顶滴水石（尖朝下）悬在锅正上方，石砖平台盛岩浆，
     *  台阶封顶防刷怪。无红石，随时间自动满锅。 */
    private static List<String> lavaFountain() {
        reset();
        // 底座（y=0，7×5）+ 5 口炼药锅（z=2）
        box(0, 0, 0, 6, 0, 4, "minecraft:stone_bricks");
        for (int x = 0; x <= 4; x++) {
            s(x, 0, 2, "minecraft:cauldron");
        }
        // 井道壁（y=1..2，四周围一圈留口看锅）
        for (int y = 1; y <= 2; y++) {
            for (int x = 0; x <= 6; x++) {
                s(x, y, 0, "minecraft:stone_bricks");
                s(x, y, 4, "minecraft:stone_bricks");
            }
            for (int z = 1; z <= 3; z++) {
                s(0, y, z, "minecraft:stone_bricks");
                s(6, y, z, "minecraft:stone_bricks");
            }
        }
        // 滴水石锥（尖朝下悬在锅上：y=1 尖 / y=2 基）
        for (int x = 0; x <= 4; x++) {
            s(x, 1, 2, "minecraft:pointed_dripstone",
                    "{tip:\"down\",thickness:\"tip\",vertical_direction:\"down\"}");
            s(x, 2, 2, "minecraft:pointed_dripstone",
                    "{tip:\"up\",thickness:\"base\",vertical_direction:\"up\"}");
        }
        // 平台（y=3 石砖盛岩浆）+ 岩浆（y=4 池）+ 台阶封顶（y=5）
        box(0, 3, 0, 6, 3, 4, "minecraft:stone_bricks");
        for (int x = 0; x <= 4; x++) {
            s(x, 4, 2, "minecraft:lava");
        }
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 4; z++) {
                if (x <= 4 && z == 2) {
                    continue; // 岩浆格
                }
                s(x, 4, z, "minecraft:stone_brick_slab", "{type:\"bottom\",waterlogged:\"false\"}");
            }
        }
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 4; z++) {
                s(x, 5, z, "minecraft:stone_brick_slab", "{type:\"bottom\",waterlogged:\"false\"}");
            }
        }
        // 转角立柱 + 灯笼
        for (int y = 1; y <= 2; y++) {
            s(0, y, 0, "minecraft:stone_bricks");
            s(6, y, 0, "minecraft:stone_bricks");
            s(0, y, 4, "minecraft:stone_bricks");
            s(6, y, 4, "minecraft:stone_bricks");
        }
        s(3, 2, 0, "minecraft:lantern", "{hanging:\"false\",waterlogged:\"false\"}");
        s(3, 2, 4, "minecraft:lantern", "{hanging:\"false\",waterlogged:\"false\"}");
        return done();
    }

    /** 4. 高效作物农场（farm_crop）——拉杆触发 3 座发射器骨粉齐射的"催熟小屋"：
     *  中央耕地 + 水坑保湿 + 前/右/后 3 发射器贴耕地面 + 拉杆 → 红石粉网 → 三发射器同射。
     *  半自动（每拨一次拉杆喷一次骨粉，边按住种子右键边拨拉杆即可持续催熟），
     *  比原视频的 0-tick 观察者时钟在女仆建造环境下可靠得多。 */
    private static List<String> cropFarm() {
        reset();
        // 石砖台（y=0，3×3）+ 中央耕地（y=1）+ 水（左）
        box(0, 0, 0, 2, 0, 2, "minecraft:stone_bricks");
        s(1, 1, 1, "minecraft:farmland", "{moisture:\"0\"}");
        s(0, 1, 1, "minecraft:water");
        // 3 座发射器（前/右/后，口都朝中央耕地）
        s(1, 1, 0, "minecraft:dispenser", "{facing:\"south\",triggered:\"false\"}");
        s(2, 1, 1, "minecraft:dispenser", "{facing:\"west\",triggered:\"false\"}");
        s(1, 1, 2, "minecraft:dispenser", "{facing:\"north\",triggered:\"false\"}");
        // 四角封顶（y=2 石砖）+ 发射器顶红石粉网（y=2，连成 L 形）
        s(0, 2, 0, "minecraft:stone_bricks");
        s(2, 2, 0, "minecraft:stone_bricks");
        s(0, 2, 2, "minecraft:stone_bricks");
        s(2, 2, 2, "minecraft:stone_bricks");
        s(0, 2, 1, "minecraft:stone_bricks");
        s(1, 2, 0, "minecraft:redstone_wire");
        s(2, 2, 1, "minecraft:redstone_wire");
        s(1, 2, 2, "minecraft:redstone_wire");
        s(1, 2, 1, "minecraft:redstone_wire");
        // 拉杆（右发射器东面）——拨动强充能发射器 → 相邻红石网 → 三发射器齐射
        s(3, 1, 1, "minecraft:lever", "{face:\"wall\",facing:\"east\",powered:\"false\"}");
        // 顶部红石一排（装饰按原视频"每个方块顶部铺一排红石"）
        s(0, 3, 0, "minecraft:redstone_wire");
        s(2, 3, 0, "minecraft:redstone_wire");
        s(0, 3, 2, "minecraft:redstone_wire");
        s(2, 3, 2, "minecraft:redstone_wire");
        // 前台阶（站上去对耕地长按右键种作物）
        s(1, 0, -1, "minecraft:andesite_stairs",
                "{facing:\"south\",half:\"bottom\",shape:\"straight\",waterlogged:\"false\"}");
        return done();
    }

    /** 5. 自动伐木场（farm_tree）——骨粉催熟 + 7 活塞塔推树：
     *  发射器朝树苗喷骨粉（拉杆开关），7 个粘性活塞竖直堆叠朝南，
     *  红石"楼梯"（活塞东侧 zigzag：方块与红石粉逐级交替，斜向连通）激活活塞把长高的树推倒，
     *  树苗位前方地面漏斗收集掉落。仿 MCBlanky 视频布局，红石细节以视频为准。 */
    private static List<String> treeFarm() {
        reset();
        // 树苗台（y=0 泥土 3×3 + y=1 树苗）
        box(0, 0, 0, 2, 0, 2, "minecraft:dirt");
        s(1, 1, 0, "minecraft:oak_sapling");
        // 骨粉发射器（树苗西侧，朝东）+ 发射器顶红石 + 侧面红石火把 + 上方建筑方块 + 拉杆
        s(0, 1, 0, "minecraft:dispenser", "{facing:\"east\",triggered:\"false\"}");
        s(0, 2, 0, "minecraft:redstone_wire");
        s(0, 1, -1, "minecraft:redstone_wall_torch", "{facing:\"north\",lit:\"true\"}");
        s(0, 1, 1, "minecraft:redstone_wall_torch", "{facing:\"south\",lit:\"true\"}");
        s(0, 2, -1, "minecraft:stone_bricks");
        s(0, 2, 1, "minecraft:stone_bricks");
        s(-1, 2, -1, "minecraft:lever", "{face:\"wall\",facing:\"west\",powered:\"false\"}");
        // 7 活塞塔（树苗北侧 z=-1，朝南推树）
        for (int y = 0; y <= 6; y++) {
            s(1, y, -1, "minecraft:sticky_piston", "{extended:\"false\",facing:\"south\"}");
        }
        // 塔顶 2 方块（防止高大树木长过来）
        s(1, 7, -1, "minecraft:stone_bricks");
        s(1, 8, -1, "minecraft:stone_bricks");
        // 红石楼梯（活塞东侧 zigzag：砖 x=2/3 交替逐级、红石粉在砖顶，斜向连通同时激活）
        int bx = 2;
        for (int y = 0; y <= 6; y++) {
            s(bx, y, -1, "minecraft:stone_bricks");
            s(bx, y + 1, -1, "minecraft:redstone_wire");
            bx = (bx == 2) ? 3 : 2;
        }
        // 比较器（发射器西侧，读取发射器物品量做脉冲）+ 方块 + 红石火把
        s(-1, 1, 0, "minecraft:comparator", "{facing:\"west\",mode:\"compare\",powered:\"false\"}");
        s(-2, 1, 0, "minecraft:stone_bricks");
        s(-2, 2, 0, "minecraft:redstone_torch", "{lit:\"true\"}");
        // 原木收集（树苗南侧地面漏斗 → 箱子）
        s(1, 0, 2, "minecraft:hopper", "{facing:\"down\",enabled:\"true\"}");
        s(1, -1, 2, "minecraft:chest", "{facing:\"north\",type:\"single\",waterlogged:\"false\"}");
        return done();
    }

    /** 6. 全自动养鸡场（farm_chicken）——鸡在顶部 9 漏斗平台下蛋 → 漏斗链进发射器 →
     *  拉杆向 3×2 鸡舍投蛋 → 小鸡孵出长大 → 玩家透过半砖缝隙击杀取肉 + 经验，
     *  掉落在鸡舍地板的 5 漏斗收进中央箱子。 */
    private static List<String> chickenFarm() {
        reset();
        // 3×2 鸡舍（y=0..1 石砖墙）
        for (int y = 0; y <= 1; y++) {
            for (int x = -1; x <= 3; x++) {
                s(x, y, -1, "minecraft:stone_bricks");
                s(x, y, 2, "minecraft:stone_bricks");
            }
            s(-1, y, 0, "minecraft:stone_bricks");
            s(-1, y, 1, "minecraft:stone_bricks");
            s(3, y, 0, "minecraft:stone_bricks");
            s(3, y, 1, "minecraft:stone_bricks");
        }
        // 鸡舍地板：中央箱子 + 周围 5 漏斗全指箱
        s(1, 0, 0, "minecraft:chest", "{facing:\"north\",type:\"single\",waterlogged:\"false\"}");
        s(0, 0, 0, "minecraft:hopper", "{facing:\"east\",enabled:\"true\"}");
        s(2, 0, 0, "minecraft:hopper", "{facing:\"west\",enabled:\"true\"}");
        s(0, 0, 1, "minecraft:hopper", "{facing:\"east\",enabled:\"true\"}");
        s(2, 0, 1, "minecraft:hopper", "{facing:\"west\",enabled:\"true\"}");
        s(1, 0, 1, "minecraft:hopper", "{facing:\"north\",enabled:\"true\"}");
        // 后排漏斗铺地毯、前排漏斗上半砖（留缝隙杀鸡）
        s(0, 1, 1, "minecraft:white_carpet");
        s(1, 1, 1, "minecraft:white_carpet");
        s(2, 1, 1, "minecraft:white_carpet");
        s(0, 1, 0, "minecraft:stone_slab", "{type:\"bottom\",waterlogged:\"false\"}");
        s(1, 1, 0, "minecraft:stone_slab", "{type:\"bottom\",waterlogged:\"false\"}");
        s(2, 1, 0, "minecraft:stone_slab", "{type:\"bottom\",waterlogged:\"false\"}");
        // 投蛋发射器（鸡舍南墙 y=1 内嵌，朝北射进鸡舍）+ 拉杆（发射器东侧墙外）
        s(1, 1, 2, "minecraft:dispenser", "{facing:\"north\",triggered:\"false\"}");
        s(2, 1, 2, "minecraft:lever", "{face:\"wall\",facing:\"east\",powered:\"false\"}");
        // 指向发射器的漏斗（发射器正后方、同层，朝北进发射器后背）——视频同款"放一个指向它的漏斗"
        s(1, 1, 3, "minecraft:hopper", "{facing:\"north\",enabled:\"true\"}");
        // 9 漏斗平台基座（y=2 石砖铺底支撑）
        for (int x = 0; x <= 2; x++) {
            for (int z = 2; z <= 4; z++) {
                s(x, 2, z, "minecraft:stone_bricks");
            }
        }
        // 上方再加一个漏斗（承接上层平台，往下灌，覆盖基座中心）
        s(1, 2, 3, "minecraft:hopper", "{facing:\"down\",enabled:\"true\"}");
        // 9 漏斗平台（y=3，3×3 at x=0..2/z=2..4，外圈指向中心，中心朝下进上层漏斗）
        s(0, 3, 2, "minecraft:hopper", "{facing:\"east\",enabled:\"true\"}");
        s(1, 3, 2, "minecraft:hopper", "{facing:\"south\",enabled:\"true\"}");
        s(2, 3, 2, "minecraft:hopper", "{facing:\"west\",enabled:\"true\"}");
        s(0, 3, 3, "minecraft:hopper", "{facing:\"east\",enabled:\"true\"}");
        s(1, 3, 3, "minecraft:hopper", "{facing:\"down\",enabled:\"true\"}");
        s(2, 3, 3, "minecraft:hopper", "{facing:\"west\",enabled:\"true\"}");
        s(0, 3, 4, "minecraft:hopper", "{facing:\"east\",enabled:\"true\"}");
        s(1, 3, 4, "minecraft:hopper", "{facing:\"north\",enabled:\"true\"}");
        s(2, 3, 4, "minecraft:hopper", "{facing:\"west\",enabled:\"true\"}");
        // 平台围栏（2 格高）
        for (int y = 3; y <= 4; y++) {
            for (int x = -1; x <= 3; x++) {
                s(x, y, 1, "minecraft:oak_fence");
                s(x, y, 5, "minecraft:oak_fence");
            }
            s(-1, y, 2, "minecraft:oak_fence");
            s(-1, y, 3, "minecraft:oak_fence");
            s(-1, y, 4, "minecraft:oak_fence");
            s(3, y, 2, "minecraft:oak_fence");
            s(3, y, 3, "minecraft:oak_fence");
            s(3, y, 4, "minecraft:oak_fence");
        }
        return done();
    }

    // ==================== 对外入口 ====================

    /** v1.5.275：内置蓝图生成结果缓存——右击手册每次打开都调 buildCatalogEntries →
     *  19 个内置每次重新生成（每轮 600~2100 步字符串拼接）→ 明显卡顿。
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
            case "maid_smart:house_sandstone": return sandstoneHut();
            case "maid_smart:house_snowy": return snowyLogHouse();
            // v1.5.366：新增 12 种不同结构建筑
            case "maid_smart:house_aframe": return aFrameHut();
            case "maid_smart:house_watchtower": return watchtower();
            case "maid_smart:house_lighthouse": return lighthouse();
            case "maid_smart:house_courtyard": return courtyardHouse();
            case "maid_smart:house_barn": return barn();
            case "maid_smart:house_stilt": return stiltCabin();
            case "maid_smart:house_windmill": return windmill();
            case "maid_smart:house_bunker": return bunkerHut();
            case "maid_smart:house_tree": return treeBase();
            case "maid_smart:house_hillside": return hillsideHut();
            case "maid_smart:house_dual": return dualCabin();
            case "maid_smart:house_gatehouse": return gatehouse();
            // v1.5.366：别墅保留 3 种风格
            case "maid_smart:villa_stone": return stoneVilla();
            case "maid_smart:villa_glass": return glassModernVilla();
            case "maid_smart:villa_terracotta": return terracottaVilla();
            // v1.5.300：红石机器只留自动熔炉组（纯漏斗定向无红石，100% 可靠）
            case "maid_smart:machine_furnace_array": return furnaceArray();
            // v1.5.369：6 座生存农场（仿 MCBlanky《10 个 1 分钟农场》全新版）
            case "maid_smart:farm_cactus": return cactusTower();
            case "maid_smart:farm_superfurnace": return superFurnace();
            case "maid_smart:farm_lavafountain": return lavaFountain();
            case "maid_smart:farm_crop": return cropFarm();
            case "maid_smart:farm_tree": return treeFarm();
            case "maid_smart:farm_chicken": return chickenFarm();
            default:
                return null;
        }
    }

    /** 内置小屋/别墅 id → 中文名（手册目录显示） */
    public static String nameOf(String id) {
        switch (id) {
            case "maid_smart:house_oak_log": return "橡木原木小屋";
            case "maid_smart:house_sandstone": return "砂岩小屋";
            case "maid_smart:house_snowy": return "雪原木屋";
            case "maid_smart:house_aframe": return "A字架小屋";
            case "maid_smart:house_watchtower": return "瞭望塔";
            case "maid_smart:house_lighthouse": return "灯塔";
            case "maid_smart:house_courtyard": return "庭院屋";
            case "maid_smart:house_barn": return "谷仓";
            case "maid_smart:house_stilt": return "高脚小屋";
            case "maid_smart:house_windmill": return "风车";
            case "maid_smart:house_bunker": return "半地堡屋";
            case "maid_smart:house_tree": return "树屋";
            case "maid_smart:house_hillside": return "坡地阶梯屋";
            case "maid_smart:house_dual": return "双子屋";
            case "maid_smart:house_gatehouse": return "城堡门楼";
            case "maid_smart:villa_stone": return "石质山景别墅";
            case "maid_smart:villa_glass": return "现代玻璃别墅";
            case "maid_smart:villa_terracotta": return "陶瓦地中海别墅";
            // v1.5.300：只留自动熔炉组
            case "maid_smart:machine_furnace_array": return "自动熔炉组";
            // v1.5.369：6 座生存农场
            case "maid_smart:farm_cactus": return "仙人掌塔农场";
            case "maid_smart:farm_superfurnace": return "超级熔炉组";
            case "maid_smart:farm_lavafountain": return "无限岩浆泉";
            case "maid_smart:farm_crop": return "高效作物农场";
            case "maid_smart:farm_tree": return "自动伐木场";
            case "maid_smart:farm_chicken": return "全自动养鸡场";
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
