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
 * 外部蓝图文件的扫描 / 导入 / 持久化（v1.2.4 从 BlueprintLib 拆出）。
 * 
 * config/maid_smart/blueprints 与存档 schematics 的增量扫描、文件导入
 * （json/nbt/snbt/litematic/schem/schematic/zip）、内置蓝图释放、增删。
 */
public final class BlueprintFileIo {
    private BlueprintFileIo() {
    }

    private static final List<String> EXTENSIONS = List.of(".json", ".nbt", ".snbt", ".litematic", ".schem", ".schematic", ".zip");

    static final Map<String, List<String>> EXTERNAL = new HashMap<>();

    static final Map<String, String> EXTERNAL_NAMES = new HashMap<>();

    /** 登记项版本（材料/描述/尺寸缓存拿它当哨兵）：**只在内容真的变了时**才更新。
     *  实测九百一十 起不再拿它判“文件变了没”——那是同名文件互相顶 mtime 的老根因。 */
    static final Map<String, Long> EXTERNAL_MTIMES = new HashMap<>();

    /** 实测九百一十：扫描的“变了没”按**绝对路径**记（旧版按 id＝去扩展名的文件名记，
     *  两个不同文件同名时共用一格 → 互相把对方的 mtime 顶掉 → 每趟重解析 + 重算材料）。
     *  成功/失败都记，所以损坏文件也不再反复重扫。 */
    private static final Map<String, Long> FILE_MTIMES = new HashMap<>();

    private static volatile long LAST_EXTERNAL_SCAN_MS = 0L;

    private static final long EXTERNAL_SCAN_INTERVAL_MS = 3000L;

    /** 实测九百一十：一趟扫描最多占服务端线程多少毫秒。到点就停、下一跳自己接上
     *  （每趟至少真解析一个，保证有进展、不会空转）。 */
    private static final long SCAN_BUDGET_MS = 40L;

    /** 实测九百一十：同一个 id 被两个文件争用时只提示一次（`config/blueprints` 与
     *  `<存档>/schematics` 同名，或 zip 解出的内层文件与已有蓝图同名）。 */
    private static final Set<String> WARNED_ID_CLASH =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private static final Map<String, java.nio.file.Path> EXTERNAL_PATHS = new HashMap<>();

    private static volatile net.minecraft.server.MinecraftServer SERVER = null;

    public static void setServer(net.minecraft.server.MinecraftServer server) {
        SERVER = server;
        EXTERNAL.clear();
        EXTERNAL_NAMES.clear();
        EXTERNAL_MTIMES.clear();
        EXTERNAL_PATHS.clear();
        FILE_MTIMES.clear();
        WARNED_ID_CLASH.clear();
        BlueprintMaterials.NEEDS_CACHE.clear();
        BlueprintMaterials.NEEDS_MTIME.clear();
        BlueprintCatalog.DESCRIBE_CACHE.clear();
        BlueprintStepMath.SIZE_CACHE.clear();
        if (server != null) {
            // v1.5.387：不再自动复制内置预制蓝图（原 8 个大 snbt）——要求手册
            // 建造目录只保留玩家导入/生成的内容。已在 blueprints 目录的残留副本
            // 由 cleanupLegacyBuiltinFiles() 删除（mod 自动生成的，非玩家内容）。
            cleanupLegacyBuiltinFiles();
            // v1.5.391：首次运行自动解压内置玩家蓝图包到 config/maid_smart/blueprints/，
            // 让玩家开箱即见（干净安装也能在手册建造目录看到默认蓝图）。
            ensureBundledBlueprints();
            // v1.5.25g：服务端启动时预热外部蓝图需求缓存——手册右击不再首次卡 5 秒
            // （countNeeds 对每个蓝图遍历上万步骤在启动时完成，右击只读缓存）
            warmupNeedsCache();
        }
    }

    private static void warmupNeedsCache() {
        scanExternalBlueprints();
        for (Map.Entry<String, List<String>> e : EXTERNAL.entrySet()) {
            BlueprintMaterials.countNeedsCached(e.getKey(), e.getValue());
            // v1.5.375：一并预热摘要与占地尺寸——手册打开时 buildCatalogEntries 对
            // 每个外部蓝图调 describe、openFor 对每个调 blueprintSize，旧版只在
            // 首次打开时现算（8000+ 蓝图 × 遍历全部步骤 = 主线程卡死）；这里在
            // 服务端启动（读世界阶段）预热完，打开手册纯读缓存
            BlueprintCatalog.describe(e.getKey(), e.getValue());
            BlueprintStepMath.blueprintSizeCached(e.getKey(), e.getValue());
        }
        // v1.5.375：内置预设同样预热（小屋/农场/别墅，量小但一并覆盖）
        for (String id : BlueprintCatalog.BUILT_IN_NAMES.keySet()) {
            List<String> steps = BlueprintCatalog.getBuiltIn(id);
            if (steps != null && !steps.isEmpty()) {
                BlueprintMaterials.countNeedsCached(id, steps);
                BlueprintCatalog.describe(id, steps);
                BlueprintStepMath.blueprintSizeCached(id, steps);
            }
        }
    }

    private static final String[] BUNDLED_BLUEPRINT_FILES = {
        "协议源石01（已授权）.litematic",
        "地形__MC版 总统山.litematic",
        "地形__加冕圣地.litematic",
        "天空__倒金字塔.litematic",
        "天空__哈尔的移动城堡.litematic",
        "天空__天空之城.litematic",
        "天空__月宫.litematic",
        "天空__水晶塔.litematic",
        "天空__热气球幻想屋.litematic",
        "天空__空中花园.litematic",
        "天空__罗莎琳娜彗星天文台.litematic",
        "小蛋挞01（已授权）.litematic",
        "房屋__bilibili小屋.litematic",
        "房屋__《传说之下》金字塔(同人).litematic",
        "房屋__《哥谭》韦恩塔.litematic",
        "房屋__《魔兽争霸》市政厅.litematic",
        "房屋__三层木制别墅.litematic",
        "房屋__三角形现代别墅.litematic",
        "房屋__上海中心大厦.litematic",
        "房屋__上海环球金融中心.litematic",
        "房屋__下午茶凉亭.litematic",
        "房屋__下界砖顶中世纪房屋.litematic",
        "房屋__丛林宅邸.litematic",
        "房屋__丛林温泉馆.litematic",
        "房屋__东方大堂.litematic",
        "房屋__中世纪城堡堡垒.litematic",
        "房屋__中世纪小巫师塔.litematic",
        "房屋__中世纪庄园.litematic",
        "房屋__中世纪异世界酒馆.litematic",
        "房屋__中世纪旅馆.litematic",
        "树木__巨型樱花树.litematic",
        "树木__樱花树1.litematic",
        "树木__用末地烛装饰的树.litematic",
        "树木__神树.litematic",
        "武器__F-16 战隼.litematic",
        "武器__SR-71 黑鸟 .litematic",
        "武器__北风之神级核潜艇.litematic",
        "武器__星球大战AT-AT 步行机.litematic",
        "武器__苏俄 天启坦克.litematic",
        "武器__鹦鹉螺号.litematic",
        "水__圣剑池.litematic",
        "水__樱花木小码头.litematic",
        "水__沙滩别墅.litematic",
        "水__河滨度假别墅.litematic",
        "红石__优雅的村民交易所.litematic",
        "红石__全树种树场（有红树）.litematic",
        "红石__国风刷铁机——黑金楼2024.1.15.litematic",
        "红石__彩虹信标.litematic",
        "红石__心形下界传送门.litematic",
        "节日__万圣节漂浮教堂.litematic",
        "节日__中秋快乐.litematic",
        "节日__南瓜屋.litematic",
        "节日__圣诞教堂.litematic",
        "节日__大圣诞树.litematic",
        "节日__魔法圣诞城堡.litematic",
        "装饰__中式灯笼路灯.litematic",
        "装饰__太阳能路灯.litematic",
        "装饰__布鲁克林大桥.litematic",
        "装饰__现代路灯（大）.litematic",
        "载具__A380.litematic",
        "载具__BF7 星际航母.litematic",
        "载具__《光环》UNSC M510猛犸象.litematic",
        "载具__《海贼王》 岛屿之船.litematic",
        "载具__《银翼杀手》飞行警车.litematic",
        "载具__中式飞艇.litematic",
        "载具__云梯消防车.litematic",
        "载具__仙女座号.litematic",
        "雕像__《东方Project》小型角色雕像合集.litematic",
        "雕像__《艾尔登法环》黄金树 1.litematic",
        "雕像__《进击的巨人》超大型巨人头部.litematic",
        "雕像__侏罗纪世界苍龙.litematic",
        "雕像__克拉肯.litematic",
        "雕像__初音未来.litematic",
        "雕像__地球和月球.litematic",
        "雕像__巨型坦克.litematic",
        "雕像__无限手套.litematic",
        "雕像__暴虐霸王龙.litematic",
        // 实测九百八十四：玩家自用的两张，一并随包附赠
        "唯一.litematic",
        "《Mistry Memory》.litematic"
    };

    private static void cleanupLegacyBuiltinFiles() {
        String[] legacy = {
                "seaside_villa.snbt", "grand_palace.snbt", "skyscraper.snbt",
                "tabby_cat_statue.snbt",
                "mega_castle.snbt", "mega_pyramid.snbt", "mega_colosseum.snbt",
                "mega_knight_statue.snbt",
                // 实测九百八十五：三张从默认蓝图包里下架——已经在 config/maid_smart/
                // blueprints/ 落过地的副本一并删（模组自己解压的，不是玩家导入的内容）
                "水__圆形水族箱.litematic", "水__水立方.litematic",
                "红石__中世纪刷石机.litematic"
        };
        try {
            java.nio.file.Path dir = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve("maid_smart").resolve("blueprints");
            if (!java.nio.file.Files.isDirectory(dir)) {
                return;
            }
            for (String file : legacy) {
                try {
                    java.nio.file.Path out = dir.resolve(file);
                    if (java.nio.file.Files.deleteIfExists(out)) {
                        BlueprintLib.LOGGER.info("cleanupLegacyBuiltinFiles: 已删除内置预制残留 {}", out);
                    }
                } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("BlueprintFileIo", ignored);
                }
            }
            // 清理后同步内存注册（增量扫描会在下次触发时自然移除；此处主动清）
            EXTERNAL.clear();
            EXTERNAL_NAMES.clear();
            EXTERNAL_MTIMES.clear();
            EXTERNAL_PATHS.clear();
            BlueprintMaterials.NEEDS_CACHE.clear();
            BlueprintMaterials.NEEDS_MTIME.clear();
            BlueprintCatalog.DESCRIBE_CACHE.clear();
            BlueprintStepMath.SIZE_CACHE.clear();
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("BlueprintFileIo", ignored);
        }
    }

    /**
     * 【R1079】内置蓝图"解压一次"清单：清单里已有、文件却不在 → 说明是玩家/整合包作者
     * **主动删的**，不再每次启动补回来（旧版无条件补，删了也白删）。想恢复：删掉清单文件
     * {@value #BUNDLED_MANIFEST}，下次启动会把缺的内置蓝图重新解压。
     */
    static final String BUNDLED_MANIFEST = ".bundled-manifest";

    private static void ensureBundledBlueprints() {
        try {
            java.nio.file.Path dir = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve("maid_smart").resolve("blueprints");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path manifest = dir.resolve(BUNDLED_MANIFEST);
            java.util.Set<String> extracted = new java.util.LinkedHashSet<>();
            if (java.nio.file.Files.isRegularFile(manifest)) {
                try {
                    for (String line : java.nio.file.Files.readAllLines(manifest,
                            java.nio.charset.StandardCharsets.UTF_8)) {
                        if (!line.isBlank()) {
                            extracted.add(line.trim());
                        }
                    }
                } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("BlueprintFileIo", ignored);
                }
            }
            boolean changed = !java.nio.file.Files.isRegularFile(manifest);
            for (String file : BUNDLED_BLUEPRINT_FILES) {
                if (BlueprintStepMath.isDisabled(file)) {
                    continue; // 【R1079】配置禁用：不复制、也不记清单
                }
                java.nio.file.Path out = dir.resolve(file);
                if (java.nio.file.Files.exists(out)) {
                    changed |= extracted.add(file); // 已有则跳过，不覆盖玩家自己的内容
                    continue;
                }
                if (extracted.contains(file)) {
                    continue; // 清单里有、文件却没了 = 主动删除 → 尊重删除，不再补
                }
                try (java.io.InputStream in = BlueprintLib.class.getClassLoader()
                        .getResourceAsStream("assets/maid_smart/bundled_blueprints/" + file)) {
                    if (in == null) {
                        BlueprintLib.LOGGER.warn("ensureBundledBlueprints: 缺少内置蓝图资源 {}", file);
                        continue;
                    }
                    java.nio.file.Files.copy(in, out);
                    extracted.add(file);
                    changed = true;
                    BlueprintLib.LOGGER.info("ensureBundledBlueprints: 已内置蓝图 {}", file);
                } catch (Exception e) {
                    BlueprintLib.LOGGER.warn("ensureBundledBlueprints: 复制失败 {}: {}", file, e.getMessage());
                }
            }
            if (changed) {
                java.nio.file.Files.write(manifest, extracted, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("BlueprintFileIo", ignored);
        }
    }

    public static String importBuildFile(String path) {
        try {
            if (path.contains("..")) {
                return "导入失败: 路径不合法（包含 ..）";
            }
            java.io.File src = new java.io.File(path);
            if (!src.isFile()) {
                return "导入失败: 文件不存在（" + path + "）";
            }
            String lower = src.getName().toLowerCase(java.util.Locale.ROOT);
            String ext = null;
            for (String e : EXTENSIONS) {
                if (lower.endsWith(e)) {
                    ext = e;
                    break;
                }
            }
            if (ext == null) {
                return "导入失败: 不支持的格式（支持 .schem/.litematic/.nbt/.snbt/.schematic/.json）";
            }
            java.nio.file.Path dir = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve("maid_smart").resolve("blueprints");
            java.nio.file.Files.createDirectories(dir);
            // 审计1.5.384：文件名清洗——客户端可控文件名含路径分隔符/.. 可逃逸
            // blueprints 目录（服务器端已 OP 门槛，此处纵深防御）
            String fname = src.getName();
            if (fname.contains("/") || fname.contains("\\") || fname.contains("..")
                    || fname.equals(".")) {
                return "导入失败: 文件名不合法（" + fname + "）";
            }
            java.io.File dst = new java.io.File(dir.toFile(), fname);
            if (dst.exists()) {
                return "导入失败: 同名文件已存在（" + dst.getAbsolutePath()
                        + "，可直接使用，无需重复导入）";
            }
            java.nio.file.Files.copy(src.toPath(), dst.toPath());
            try {
                List<String> steps = loadExternalFile(dst.toPath(), ext);
                if (steps == null || steps.isEmpty()) {
                    java.nio.file.Files.deleteIfExists(dst.toPath());
                    return "导入失败: 文件无法解析（可能是版本不兼容——蓝图需与当前 "
                            + "MC 1.20.1 一致，否则建筑可能损毁；已删除无效副本）";
                }
                scanExternalBlueprints(true);
                return "导入成功: " + fname + "（" + steps.size() + " 块，"
                        + "已注册，可在手册建造目录找到）";
            } catch (Exception e) {
                try {
                    java.nio.file.Files.deleteIfExists(dst.toPath());
                } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("BlueprintFileIo", ignored);
                }
                return "导入失败: 文件无法解析（" + e.getClass().getSimpleName()
                        + "——可能是版本不兼容，蓝图需与当前 MC 1.20.1 一致；已删除无效副本）";
            }
        } catch (Exception e) {
             com.maidsmart.diag.Diag.swallow("BlueprintFileIo", e); return "导入失败: " + e.getMessage();
        }
    }

    public static String importWorldFile(String path) {
        try {
            if (path.contains("..")) {
                return "导入失败: 路径不合法（包含 ..）";
            }
            java.io.File src = new java.io.File(path);
            if (!src.isFile()) {
                return "导入失败: 文件不存在（" + path + "）";
            }
            String lower = src.getName().toLowerCase(java.util.Locale.ROOT);
            if (!lower.endsWith(".zip")) {
                return "导入失败: 世界地图导入只支持 .zip 压缩包（世界存档或建筑包）";
            }
            java.nio.file.Path dir = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve("maid_smart").resolve("blueprints");
            java.nio.file.Files.createDirectories(dir);
            // 审计1.5.384：文件名清洗（与 importBuildFile 一致）
            String fname = src.getName();
            if (fname.contains("/") || fname.contains("\\") || fname.contains("..")
                    || fname.equals(".")) {
                return "导入失败: 文件名不合法（" + fname + "）";
            }
            java.io.File dst = new java.io.File(dir.toFile(), fname);
            if (dst.exists()) {
                return "导入失败: 同名文件已存在（" + dst.getAbsolutePath()
                        + "，可直接使用，无需重复导入）";
            }
            java.nio.file.Files.copy(src.toPath(), dst.toPath());
            List<String> steps;
            try {
                steps = loadExternalFile(dst.toPath(), ".zip");
            } catch (Exception e) {
                java.nio.file.Files.deleteIfExists(dst.toPath());
                return "导入失败: 文件无法解析（" + e.getClass().getSimpleName()
                        + "——可能是版本不兼容，已删除无效副本）";
            }
            if (steps == null || steps.isEmpty()) {
                java.nio.file.Files.deleteIfExists(dst.toPath());
                return "导入失败: 未提取到任何建筑（zip 需为世界存档——含 level.dat 与 "
                        + "region/*.mca——或打包了 .schem/.litematic/.nbt 等建筑文件；"
                        + "世界存档会以玩家最后位置为中心自动提取，已删除无效副本）";
            }
            int[] size = BlueprintStepMath.blueprintSize(steps);
            scanExternalBlueprints(true);
            return "导入成功: " + fname + "（提取 " + steps.size() + " 块，"
                    + "尺寸 " + size[0] + "×" + size[1] + "×" + size[2]
                    + "，已注册，可在手册建造目录找到）";
        } catch (Exception e) {
             com.maidsmart.diag.Diag.swallow("BlueprintFileIo", e); return "导入失败: " + e.getMessage();
        }
    }

    public static void scanExternalBlueprints() {
        scanExternalBlueprints(false, true);
    }

    /** force=true（导入流程）：绕过节流、且**不设预算** —— 导入后必须当场登记，不能被预算截断。 */
    public static void scanExternalBlueprints(boolean force) {
        scanExternalBlueprints(force, !force);
    }

    /** 实测九百一十：预算到点后的续跑（不节流、仍按预算），由服务端线程下一跳自己接上。 */
    private static void scanExternalBlueprintsContinue() {
        scanExternalBlueprints(true, true);
    }

    private static void scanExternalBlueprints(boolean force, boolean budget) {
        long nowMs = System.currentTimeMillis();
        if (!force && nowMs - LAST_EXTERNAL_SCAN_MS < EXTERNAL_SCAN_INTERVAL_MS) {
            return;
        }
        LAST_EXTERNAL_SCAN_MS = nowMs;
        List<java.nio.file.Path> dirs = new ArrayList<>();
        try {
            java.nio.file.Path cfg = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve("maid_smart").resolve("blueprints");
            if (java.nio.file.Files.isDirectory(cfg)) {
                dirs.add(cfg);
            }
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("BlueprintFileIo", ignored);
        }
        if (SERVER != null) {
            try {
                java.nio.file.Path sched = SERVER.getServerDirectory().resolve("schematics");
                if (java.nio.file.Files.isDirectory(sched)) {
                    dirs.add(sched);
                }
            } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("BlueprintFileIo", ignored);
            }
        }
        // v1.5.25 诊断日志：确认扫描到的目录与文件数（帮助排查"手册看不到蓝图"）
        int[] registered = {0};
        // 实测九百一十：本趟预算的状态（lambda 里改不了局部变量，用一格数组兜）
        int[] parsedThisCall = {0};
        boolean[] reachedBudget = {false};
        long deadline = nowMs + SCAN_BUDGET_MS;
        // 已扫描文件集合（用于删除检测）
        Set<String> seen = new HashSet<>();
        // 实测九百一十：另记一份**路径**集合——FILE_MTIMES 按路径存，陈旧项也按路径清
        Set<String> seenPaths = new HashSet<>();
        for (java.nio.file.Path dir : dirs) {
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(dir)) {
                files.forEach(p -> {
                    String fileName = p.getFileName().toString();
                    String lower = fileName.toLowerCase(java.util.Locale.ROOT);
                    String ext = null;
                    for (String e : EXTENSIONS) {
                        if (lower.endsWith(e)) {
                            ext = e;
                            break;
                        }
                    }
                    if (ext == null) {
                        return;
                    }
                    // 【R1079】配置禁用：不注册；已在内存里的会在本轮末尾按 seen 清理掉
                    if (BlueprintStepMath.isDisabled(fileName)) {
                        return;
                    }
                    String id = "maid_smart_ext:" + fileName.substring(0, fileName.length() - ext.length());
                    seen.add(id);
                    seenPaths.add(p.toString());
                    try {
                        if (reachedBudget[0]) {
                            return; // 本趟预算已到：剩下的留给下一跳续跑
                        }
                        long mtime = java.nio.file.Files.getLastModifiedTime(p).toMillis();
                        if (mtime == FILE_MTIMES.getOrDefault(p.toString(), -1L)) {
                            return; // 未变化（按**路径**判——同名文件不再互相顶 mtime）
                        }
                        // 实测九百一十：至少真解析一个才允许停（保证有进展，不会空转）
                        if (budget && parsedThisCall[0] > 0 && System.currentTimeMillis() > deadline) {
                            reachedBudget[0] = true;
                            return;
                        }
                        parsedThisCall[0]++;
                        List<String> steps = loadExternalFile(p, ext);
                        // 实测九百一十：成功/失败都按路径记 mtime —— 本会话不再反复重解析
                        // （含损坏 zip / 世界 zip 提取失败那类 0.5s+ 的坑）
                        FILE_MTIMES.put(p.toString(), mtime);
                        if (steps != null && !steps.isEmpty()) {
                            // 实测九百一十：同名撞车（config/blueprints 与 <存档>/schematics 同名，
                            // 或 zip 解出的内层文件与已有蓝图同名）→ **先到者胜**、不再来回覆盖。
                            // 旧版这一对共享同一格 mtime，每趟互相顶掉 → 重解析 + 重算 → 卡死主线程。
                            java.nio.file.Path owner = EXTERNAL_PATHS.get(id);
                            if (owner != null && !owner.equals(p)) {
                                if (WARNED_ID_CLASH.add(id)) {
                                    BlueprintLib.LOGGER.warn(
                                            "scanExternalBlueprints: 蓝图 id 撞车 {} → 保留 {}，忽略 {}",
                                            id, owner, p);
                                }
                                return;
                            }
                            List<String> prev = EXTERNAL.get(id);
                            if (prev != null && prev.equals(steps)) {
                                // 实测九百一十：解析结果与已登记内容**逐字相同**（文件被外部 touch /
                                // 被自己解压重写）→ 不重登记、不失效四个缓存。旧版每次都清缓存，
                                // 于是开手册时把 234 张的材料重算一遍（spark 快照里 itemForBlock
                                // "疯狂拼方块 ID" 就是这一半）。
                                return;
                            }
                            // v1.5.25g：不再在扫描阶段跳过超大蓝图——LLM smart_build 走
                            // getBlueprint/EXTERNAL 也需要大建筑（之前 5000 限制导致 LLM
                            // 也建不了）。大小限制只作用于手册目录显示（buildCatalogEntries）。
                            EXTERNAL.put(id, steps);
                            EXTERNAL_NAMES.put(id, externalName(p, ext, steps));
                            EXTERNAL_PATHS.put(id, p);
                            EXTERNAL_MTIMES.put(id, mtime);
                            // v1.5.25d：文件变化 → 清材料需求缓存
                            BlueprintMaterials.NEEDS_CACHE.remove(id);
                            BlueprintMaterials.NEEDS_MTIME.remove(id);
                            BlueprintCatalog.DESCRIBE_CACHE.remove(id);
                            BlueprintStepMath.SIZE_CACHE.remove(id);
                            // 实测九百一十：当场把这三样预热回来——把「开手册时重算材料 / 现算尺寸」
                            // 挪到扫描这一趟（受预算约束），开手册就只剩纯读缓存。
                            BlueprintMaterials.countNeedsCached(id, steps);
                            BlueprintCatalog.describe(id, steps);
                            BlueprintStepMath.blueprintSizeCached(id, steps);
                            registered[0]++;
                        } else if (EXTERNAL_PATHS.get(id) == null
                                || EXTERNAL_PATHS.get(id).equals(p)) {
                            // 解析失败/空：只有这份文件**自己**登记着这个 id 时才摘掉——
                            // 否则会把同名撞车里先到那份的登记一起误摘
                            EXTERNAL.remove(id);
                            EXTERNAL_NAMES.remove(id);
                            EXTERNAL_PATHS.remove(id);
                            // v1.5.312：失败也记 mtime → 本次会话不再反复重扫（损坏 zip /
                            // 世界 zip 提取失败每次 0.5s+，目录循环重扫会卡死服务端主线程）；
                            // 文件被替换后 mtime 变化才会重新尝试解析。（实测九百一十：改由
                            // FILE_MTIMES 按路径记，这里只把登记项版本一并清掉。）
                            EXTERNAL_MTIMES.remove(id);
                            BlueprintMaterials.NEEDS_CACHE.remove(id);
                            BlueprintMaterials.NEEDS_MTIME.remove(id);
                            BlueprintCatalog.DESCRIBE_CACHE.remove(id);
                            BlueprintStepMath.SIZE_CACHE.remove(id);
                        }
                    } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("BlueprintFileIo", ignored);
                    }
                });
            } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("BlueprintFileIo", ignored);
            }
            if (reachedBudget[0]) {
                break; // 到点：别开下一个扫描目录
            }
        }
        if (!reachedBudget[0]) {
            // 删除的文件：移除缓存（**只在走完整趟时**做——早停时 seen 不全，
            // 清下去会把还没扫到的那批登记一起删掉）
            EXTERNAL.keySet().removeIf(id -> !seen.contains(id));
            EXTERNAL_NAMES.keySet().removeIf(id -> !seen.contains(id));
            EXTERNAL_PATHS.keySet().removeIf(id -> !seen.contains(id));
            EXTERNAL_MTIMES.keySet().removeIf(id -> !seen.contains(id));
            BlueprintStepMath.SIZE_CACHE.keySet().removeIf(id -> !seen.contains(id));
            FILE_MTIMES.keySet().removeIf(k -> !seenPaths.contains(k));
        }
        if (registered[0] > 0) {
            // v1.5.25 诊断日志（LogUtils → 进 latest.log）
            BlueprintLib.LOGGER.info("scanExternalBlueprints: 新注册 {} 个外部蓝图，共 {} 个", registered[0], EXTERNAL.size());
        }
        if (reachedBudget[0] && SERVER != null) {
            // 实测九百一十：剩下的下一跳自己接上（不节流、仍按预算），
            // 把一次性灌进来的大批蓝图摊到多个 tick，而不是在主线程上一刀切跑完
            SERVER.execute(BlueprintFileIo::scanExternalBlueprintsContinue);
        }
    }

    private static List<String> loadExternalFile(java.nio.file.Path p, String ext) {
        try {
            if (".zip".equals(ext)) {
                String zipName = p.getFileName() != null ? p.getFileName().toString() : p.toString();
                // ZIP 打包蓝图：解压 zip 内所有建筑文件（.schem/.litematic/.nbt/.snbt/.schematic/.json，
                // 跳过 .zip 防递归）到蓝图目录，返回第一个建筑文件的解析结果；解出的子文件由
                // scanExternalBlueprints 各自注册（一次导入多个建筑）。防路径穿越：只取纯文件名。
                // 若 zip 是【世界存档】（含 level.dat/region/*.mca），世界文件解压到「蓝图目录/<zip名>/」
                // 子目录，并尝试以玩家最后位置为锚点解析区块提取完整建筑——成功则作为该 zip 的蓝图。
                // 失败 WARN 只提示一次（与 .nbt 等一致），损坏 zip 反复重扫时不刷屏。
                java.nio.file.Path dir = p.getParent();
                String zipBase = p.getFileName().toString().replaceFirst("(?i)\\.zip$", "");
                java.nio.file.Path worldDir = null;
                List<String> first = null;
                java.util.zip.ZipFile zf0 = null;
                try {
                    zf0 = new java.util.zip.ZipFile(p.toFile());
                } catch (Exception utf8Fail) {
                     com.maidsmart.diag.Diag.swallow("BlueprintFileIo", utf8Fail); // v1.5.313：中文 Windows 工具/百度网盘压缩的 zip 条目名是 GBK 编码，
                    // Java 默认按 UTF-8 解码 → "invalid CEN header (bad entry name or
                    // comment)" 打不开（豪华大别墅.zip 实测）；GBK 兜底重试。
                    try {
                        zf0 = new java.util.zip.ZipFile(p.toFile(),
                                java.nio.charset.Charset.forName("GBK"));
                    } catch (Exception gbkFail) {
                        throw new java.io.IOException("zip open failed: " + p, gbkFail);
                    }
                }
                try (java.util.zip.ZipFile zf = zf0) {
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                    while (en.hasMoreElements()) {
                        java.util.zip.ZipEntry ze = en.nextElement();
                        if (ze.isDirectory()) {
                            continue;
                        }
                        String name = ze.getName();
                        String lower = name.toLowerCase(java.util.Locale.ROOT);
                        boolean worldFile = lower.endsWith(".mca")
                                || lower.endsWith("level.dat")
                                || lower.endsWith("level.dat_old")
                                || lower.contains("/playerdata/")
                                || lower.endsWith("/level.dat");
                        if (worldFile) {
                            // 世界存档文件 → 解压到子目录（保持相对路径，防穿越）
                            if (worldDir == null) {
                                worldDir = dir.resolve(zipBase);
                            }
                            java.nio.file.Path out = worldDir.resolve(name).normalize();
                            if (!out.startsWith(worldDir)) {
                                continue;
                            }
                            java.nio.file.Files.createDirectories(out.getParent());
                            try (java.io.InputStream in = zf.getInputStream(ze)) {
                                java.nio.file.Files.copy(in, out,
                                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            }
                            continue;
                        }
                        String subExt = null;
                        for (String e : EXTENSIONS) {
                            if (!".zip".equals(e) && lower.endsWith(e)) {
                                subExt = e;
                                break;
                            }
                        }
                        if (subExt == null) {
                            continue; // 不是建筑文件
                        }
                        java.nio.file.Path out = new java.io.File(
                                dir.toFile(), new java.io.File(name).getName()).toPath();
                        try (java.io.InputStream in = zf.getInputStream(ze)) {
                            java.nio.file.Files.copy(in, out,
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                        if (first == null) {
                            first = loadExternalFile(out, subExt); // 第一个建筑文件即本 zip 的蓝图
                        }
                    }
                } catch (Exception e) {
                    if (BlueprintLib.WARNED_PARSE_FAIL.add(zipName)) {
                        BlueprintLib.LOGGER.warn("loadExternalFile: zip {} 异常 -> {}", p.getFileName(), e.toString());
                    }
                }
                // v1.5.223：世界存档 zip → 提取完整建筑优先
                // v1.5.252i：先递归定位世界根目录——网上下载的世界 zip 几乎都带
                // 一层顶层目录（<zip名>/<世界名>/level.dat），直接扫只查直接子层
                // 会"找不到锚点/没有 mca" → 误报导入失败
                if (worldDir != null) {
                    java.nio.file.Path worldRoot = BlueprintWorldExtract.findWorldRoot(worldDir);
                    if (worldRoot == null) {
                        if (BlueprintLib.WARNED_PARSE_FAIL.add(zipName)) {
                            BlueprintLib.LOGGER.warn("loadExternalFile: {} 解压后未找到 level.dat", p.getFileName());
                        }
                        worldRoot = worldDir;
                    }
                    List<String> worldSteps = BlueprintWorldExtract.extractFromWorldZip(worldRoot);
                    if (worldSteps != null && !worldSteps.isEmpty()) {
                        return worldSteps;
                    }
                }
                if (first == null) {
                    if (BlueprintLib.WARNED_PARSE_FAIL.add(zipName)) {
                        BlueprintLib.LOGGER.warn("loadExternalFile: {} zip 内没有可识别的建筑文件", p.getFileName());
                    }
                }
                return first;
            }
            if (".json".equals(ext)) {
                String json = java.nio.file.Files.readString(p);
                return BlueprintCatalog.parseJson(json);
            }
            net.minecraft.nbt.CompoundTag tag;
            if (".snbt".equals(ext)) {
                // v1.5.27 根因修复：NbtUtils.snbtToStructure 内部会做 BlockState 转换
                // （unpackStructureTemplate：palette 元素 → BlockStateParser 字符串格式），NBT 形式的
                // palette（{Name:..,Properties:..}）全部转换失败被清空 → 结构解析必失败。
                // 用 TagParser 纯解析（SNBT→NBT，完整保留结构，已独立验证 47 个文件全通过）。
                tag = net.minecraft.nbt.TagParser.parseTag(java.nio.file.Files.readString(p));
            } else {
                tag = net.minecraft.nbt.NbtIo.readCompressed(p, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            }
            // 非标准结构格式 → 先转换为标准结构格式（numen 同款转换器）
            if (".litematic".equals(ext)) {
                tag = BlueprintStructureCodec.fromLitematic(tag);
            } else if (".schem".equals(ext)) {
                tag = BlueprintStructureCodec.fromSchem(tag);
            } else if (".schematic".equals(ext)) {
                // v1.5.37：Planet Minecraft 标准格式（MCEdit 老格式：旧方块 ID + Data）
                tag = BlueprintStructureCodec.fromSchematic(tag);
            }
            // v1.5.317：机器蓝图（文件名匹配机器家族）保留水/岩浆步骤——机器水道/
            // 气泡柱/岩浆焚烧口需要；普通建筑维持剥离（防洪水/岩浆事故）
            // v1.2.2 实测五百八十五（issue #15）：判据改为可配置，且 auto 档除文件名
            // 关键词外还【看图内容】（红石机器件）——"川川12w刷石机"这类名字里不含关键词
            // 的机器不再被静默剥掉水/岩浆，导致"建好了机器一动不动、毫无线索"。
            String bpFile = p.getFileName() != null ? p.getFileName().toString() : "";
            int dot = bpFile.lastIndexOf('.');
            String stem = dot > 0 ? bpFile.substring(0, dot) : bpFile;
            boolean keepFluids = BlueprintMachineDetect.keepFluidsFor(tag, stem);
            // v1.2.2 实测五百九十八（issue #14）：把内容判据的结果记在这份图纸 id 下——
            // "缺料同类宽松（machine 档）"要与流体判据用同一把尺子（见 isMachineForMaterials）
            BlueprintMachineDetect.recordMachineByContent("maid_smart_ext:" + stem, tag);
            List<String> steps = BlueprintLib.parseStructure(tag, 0, null, keepFluids);
            if (!keepFluids) {
                BlueprintMachineDetect.recordFluidStrip("maid_smart_ext:" + stem, tag, bpFile);
            }
            // v1.2.2 实测五百九十八（issue #12）：解析期跳过了哪些"没有对应物品"的方块——旧版静默
            if (BlueprintStructureCodec.noItemCells > 0 && BlueprintLib.NO_ITEM_SKIP_LOGGED.add(stem)) {
                BlueprintLib.LOGGER.info("loadExternalFile: {} 有 {} 格方块在游戏里没有对应物品（火/传送门/活塞头这类"
                                + "不可获取方块）→ 已跳过（同一种方块全图纸只提示一次）",
                        bpFile, BlueprintStructureCodec.noItemCells);
            }
            if (steps == null) {
                // v1.5.25f 诊断：解析失败原因（LogUtils → 进 latest.log）
                // v1.5.227：同一文件只 WARN 一次——目录每 2 秒重扫一次外部文件，
                // 失败文件每次重扫都打 WARN → 日志被刷屏（实测 www/qqq/aaa.nbt 每秒
                // 几十条）
                String fname = p.getFileName() != null ? p.getFileName().toString() : p.toString();
                if (BlueprintLib.WARNED_PARSE_FAIL.add(fname)) {
                    BlueprintLib.LOGGER.warn("loadExternalFile: {} 解析返回 null（仅提示一次，目录会持续重扫）", fname);
                }
            }
            return steps;
        } catch (Exception e) {
             com.maidsmart.diag.Diag.swallow("BlueprintFileIo", e); // v1.5.25f 诊断：记录具体异常（LogUtils → 进 latest.log）
            String fname = p.getFileName() != null ? p.getFileName().toString() : p.toString();
            if (BlueprintLib.WARNED_PARSE_FAIL.add(fname)) {
                BlueprintLib.LOGGER.warn("loadExternalFile: {} 异常 -> {}（仅提示一次）", fname, e.toString());
            }
            return null;
        }
    }

    private static String externalName(java.nio.file.Path p, String ext, List<String> steps) {
        if (".json".equals(ext)) {
            try {
                JsonObject root = JsonParser.parseString(java.nio.file.Files.readString(p)).getAsJsonObject();
                JsonElement name = root.get("name");
                if (name != null && !name.getAsString().isEmpty()) {
                    return name.getAsString();
                }
            } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("BlueprintFileIo", ignored);
            }
        }
        // v1.5.28：.snbt 文件名 → 中文显示名（litematic/schem 文件名本身多为中文，直接返回原名）
        String base = p.getFileName().toString().replace(ext, "");
        String cn = BlueprintCatalog.EXT_CN_NAMES.get(base);
        return cn != null ? cn : base;
    }

    public static void saveJsonBlueprint(String name, String json) {
        try {
            java.nio.file.Path dir = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve("maid_smart").resolve("blueprints");
            java.nio.file.Files.createDirectories(dir);
            String safe = name == null || name.trim().isEmpty() ? "llm_blueprint" : name.trim();
            safe = safe.replaceAll("[\\\\/:*?\"<>|]", "_");
            java.nio.file.Path out = dir.resolve(safe + ".json");
            java.nio.file.Files.writeString(out, json);
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("BlueprintFileIo", ignored);
        }
    }

    public static boolean deleteBlueprint(String id) {
        try {
            java.nio.file.Path path = EXTERNAL_PATHS.get(id);
            String base = id.startsWith("maid_smart_ext:")
                    ? id.substring("maid_smart_ext:".length()) : id;
            // v1.5.252ad：删除诊断（latest.log 搜 "deleteBlueprint"）
            BlueprintLib.LOGGER.info("deleteBlueprint: 请求删除 id={} path={}", id,
                    path == null ? "(无注册路径!)" : path.toString());
            boolean deletedAny = false;
            if (path != null) {
                java.nio.file.Files.deleteIfExists(path);
                deletedAny = true;
            }
            // 收集全部扫描目录（与 scanExternalBlueprints 同一套：config + <cwd>/schematics）
            java.util.List<java.nio.file.Path> dirs = new java.util.ArrayList<>();
            try {
                java.nio.file.Path cfg = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                        .resolve("maid_smart").resolve("blueprints");
                if (java.nio.file.Files.isDirectory(cfg)) {
                    dirs.add(cfg);
                }
            } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("BlueprintFileIo", ignored);
            }
            try {
                if (SERVER != null) {
                    java.nio.file.Path sched = SERVER.getServerDirectory().resolve("schematics");
                    if (java.nio.file.Files.isDirectory(sched)) {
                        dirs.add(sched);
                    }
                }
            } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("BlueprintFileIo", ignored);
            }
            // 在所有扫描目录里清除同名副本（去扩展名、大小写不敏感——RRR/rrr 都删）
            for (java.nio.file.Path dir : dirs) {
                try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(dir)) {
                    for (java.nio.file.Path hit : files
                            .filter(p -> {
                                String n = p.getFileName().toString();
                                int dot = n.lastIndexOf('.');
                                String nb = dot > 0 ? n.substring(0, dot) : n;
                                return nb.equalsIgnoreCase(base);
                            })
                            .toList()) {
                        java.nio.file.Files.deleteIfExists(hit);
                        deletedAny = true;
                        BlueprintLib.LOGGER.info("deleteBlueprint: 清理副本 {}", hit);
                    }
                } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("BlueprintFileIo", ignored);
                }
            }
            if (!deletedAny) {
                return false; // 内置蓝图（无文件）或全部未找到
            }
            EXTERNAL.remove(id);
            EXTERNAL_NAMES.remove(id);
            EXTERNAL_PATHS.remove(id);
            EXTERNAL_MTIMES.remove(id);
            BlueprintMaterials.NEEDS_CACHE.remove(id);
            BlueprintMaterials.NEEDS_MTIME.remove(id);
            BlueprintCatalog.DESCRIBE_CACHE.remove(id);
            BlueprintLib.LOGGER.info("deleteBlueprint: 已删除蓝图 {} ({})", id, base);
            return true;
        } catch (Exception e) {
            BlueprintLib.LOGGER.warn("deleteBlueprint: 删除 {} 失败 -> {}", id, e.toString());
            return false;
        }
    }
}
