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
 * 建筑蓝图库（v1.4，v1.5.12 起支持 numen 式结构文件蓝图）。
 *
 * 蓝图格式：List<String>，每个元素 "x,y,z,blockid" 或 "x,y,z,blockid|stateSnbt|beSnbt"
 * （相对坐标，y 从 0 起；stateSnbt = BlockState NBT 文本，beSnbt = 方块实体 NBT 文本）。
 * 来源：
 * - 内置预设（离线可用）：hut 小木屋 / gazebo 凉亭 / fountain 喷泉 / tower 瞭望塔 / well 水井
 * - LLM 现场生成（联网）：smart_build 工具传入 JSON 蓝图
 * - 外部文件（v1.5.11 JSON；v1.5.12 起 .nbt/.snbt 结构文件，numen 式）：
 *   config/maid_smart/blueprints/ 与 存档 schematics/ 文件夹，放入即用（增量扫描）
 *
 * 安全与平衡：
 * - LLM JSON 蓝图：方块白名单（~45 种建筑方块）+ 平面 ±12 + 高度 ≤8 + ≤200 块
 * - 结构文件蓝图：无白名单（玩家自己的文件），黑名单（基岩/命令方块/液体等）+ ≤8192 块
 * - 材料预检：调用时统计背包缺口并回报
 */
public final class BlueprintLib {
    /** v1.5.25h：LogUtils（log4j，必进 latest.log）——之前 System.out 不进日志，
     *  导致 .snbt 解析失败原因一直看不到（诊断盲区） */
    /** v1.5.88：读配置面板（build 段） */
    public static int maxBlocks() {
        return com.maidsmart.config.MaidSmartConfig.BUILD_MAX_BLOCKS.get();
    }

    public static int maxRange() {
        return com.maidsmart.config.MaidSmartConfig.BUILD_MAX_RANGE.get();
    }

    public static int maxHeight() {
        return com.maidsmart.config.MaidSmartConfig.BUILD_MAX_HEIGHT.get();
    }

    public static int structureMaxBlocks() {
        return com.maidsmart.config.MaidSmartConfig.BUILD_STRUCTURE_MAX_BLOCKS.get();
    }

    static int catalogMaxBlocks() {
        return structureMaxBlocks();
    }

    static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    /** v1.5.227：外部文件解析失败 WARN 去重（目录反复重扫时每个文件只提示一次） */
    static final java.util.Set<String> WARNED_PARSE_FAIL =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** v1.2.2 实测五百九十八（issue #12）：解析期"跳过无对应物品方块"的提示去重（同上） */
    static final java.util.Set<String> NO_ITEM_SKIP_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 结构文件蓝图上限（v1.5.34：32768 → 131072——社区大建筑（佛寺 81588 块、
     *  迷你要塞 42656 块）之前被挡在手册外；上限对齐"宏大建筑"需求，
     *  建造时间按 0.15 秒/块：8 万块 ≈ 3.4 小时）v1.5.39：→ 1000000——
     *  现代红石智能住宅 547686 块入库；多女仆共享计划 + 放置间隔 3 tick
     *  加速后，10 名女仆 ≈ 2 小时/54 万块，娱乐玩法可接受 */
    /** 结构文件蓝图黑名单（无论如何都不允许女仆放置） —— 表数据见 BlueprintBlockData.FORBIDDEN（v1.2.4 拆分）。 */
    public static final Set<String> FORBIDDEN = BlueprintBlockData.FORBIDDEN;

    /** v1.5.28：手册目录显示上限 = 结构上限（>32768 的蓝图 parseStructure 直接返回 null
     *  不会注册，因此所有已注册蓝图都能在手册显示；describe/needs 已有缓存，大蓝图不再卡顿。
     *  旧版 5000 导致巨型猫咪雕像/骑士雕像等大建筑从手册消失） */
    /** 格式转换器体积上限（v1.5.102：从配置面板读取 build.structureMaxVolume，litematic/schem 单区域/整体） */
    /** 可覆盖的自然地形（v1.5.13 障碍物预检：建造区域内这类方块不算障碍物，女仆直接覆盖；树/房子/箱子等其他方块视为障碍物 → 气泡提示并拒绝建造） —— 表数据见 BlueprintBlockData.ALLOWED_GROUND（v1.2.4 拆分）。 */
    public static final Set<String> ALLOWED_GROUND = BlueprintBlockData.ALLOWED_GROUND;

    /** v1.5.58：建造破坏黑名单——这些方块女仆不可拆（基岩/命令方块/结构方块等关键方块） —— 表数据见 BlueprintBlockData.UNBREAKABLE（v1.2.4 拆分）。 */
    public static final Set<String> UNBREAKABLE = BlueprintBlockData.UNBREAKABLE;

    /** v1.5.80：地形方块（自然生成、非建筑结构）——世界提取硬过滤（skipWorldBlock）与"底部还原区"逐层占比判定（trimTerrainLayer —— 表数据见 BlueprintBlockData.TERRAIN_BLOCKS（v1.2.4 拆分）。 */
    public static final Set<String> TERRAIN_BLOCKS = BlueprintBlockData.TERRAIN_BLOCKS;

    /** v1.5.58：该方块是否可被建造女仆拆掉（黑名单外的方块）——"位置不对就破坏重建" —— 实现见 BlueprintBlockData.canBreak（v1.2.4 拆分）。 */
    public static boolean canBreak(Block block) { return BlueprintBlockData.canBreak(block); }

    /** 建筑方块白名单（方块 id） —— 表数据见 BlueprintBlockData.WHITELIST（v1.2.4 拆分）。 */
    public static final Set<String> WHITELIST = BlueprintBlockData.WHITELIST;

    /** 等价材料族：蓝图要求的方块可用族内任意物品替代 —— 表数据见 BlueprintBlockData.EQUIVALENT_GROUPS（v1.2.4 拆分）。 */
    public static final Map<String, Set<String>> EQUIVALENT_GROUPS = BlueprintBlockData.EQUIVALENT_GROUPS;

    /** v1.5.156：已建判定用等价（仅地形组）；建材组一律返回 false（只认同方块） —— 实现见 BlueprintBlockData.isBuiltEquivalent（v1.2.4 拆分）。 */
    public static boolean isBuiltEquivalent(String blockId, Block actual) { return BlueprintBlockData.isBuiltEquivalent(blockId, actual); }

    /** 蓝图要求的方块（blockId）与目标位置的方块（actual）是否等价（同族） —— 实现见 BlueprintBlockData.isEquivalent（v1.2.4 拆分）。 */
    public static boolean isEquivalent(String blockId, Block actual) { return BlueprintBlockData.isEquivalent(blockId, actual); }

    private BlueprintLib() {
    }

    /** 内置蓝图：id → 步骤列表（v1.5.366：3 小屋 + 12 新结构建筑 + 3 别墅 + 1 熔炉 =19 个，程序化生成——BuiltinHouses —— 实现见 BlueprintCatalog.getBuiltIn（v1.2.4 拆分）。 */
    public static List<String> getBuiltIn(String id) { return BlueprintCatalog.getBuiltIn(id); }

    /** 蓝图目录（v1.5.387：内置预设已全部移除——只列外部蓝图，供 smart_build_list 与提示词使用） —— 实现见 BlueprintCatalog.buildCatalog（v1.2.4 拆分）。 */
    public static String buildCatalog() { return BlueprintCatalog.buildCatalog(); }

    public static void setServer(net.minecraft.server.MinecraftServer server) { BlueprintFileIo.setServer(server); }

    /** 扫描全部外部蓝图目录（增量：仅重读变化的文件；删除的文件自动移除）v1.5.220：手册"导入建筑"——把外部文件复制到 config/maid_smart/b —— 实现见 BlueprintFileIo.importBuildFile（v1.2.4 拆分）。 */
    public static String importBuildFile(String path) { return BlueprintFileIo.importBuildFile(path); }

    /** v1.5.224：手册"导入世界地图"——只接受 .zip（世界存档压缩包或纯建筑包），复制到 blueprints 目录 → 解析（世界存档自动提取建筑）→  —— 实现见 BlueprintFileIo.importWorldFile（v1.2.4 拆分）。 */
    public static String importWorldFile(String path) { return BlueprintFileIo.importWorldFile(path); }

    public static void scanExternalBlueprints() { BlueprintFileIo.scanExternalBlueprints(); }

    /** v1.5.312：force=true 用于导入流程（导入后必须立即刷新目录，不被节流吞掉） —— 实现见 BlueprintFileIo.scanExternalBlueprints（v1.2.4 拆分）。 */
    public static void scanExternalBlueprints(boolean force) { BlueprintFileIo.scanExternalBlueprints(force); }

    /* ==================== v1.5.223 世界存档提取（ZIP map → 建筑蓝图） ====================
     * 以玩家最后位置（playerdata/level.dat）为锚点，解析锚点所在 region（及 8 邻域）
     * 的全部已生成 chunk（1.13+ Sections palette 位解包），收集非空气方块，收敛
     * 包围盒后归一化并压缩地形层，转 plan 步骤。 */

    /** 从世界存档目录提取建筑（level.dat + region/*.mca + playerdata/*.dat）；成功返回 plan 步骤列表，失败返回 nul —— 实现见 BlueprintWorldExtract.extractFromWorldZip（v1.2.4 拆分）。 */
    public static List<String> extractFromWorldZip(java.nio.file.Path dir) { return BlueprintWorldExtract.extractFromWorldZip(dir); }

    /** v1.5.28：LLM 现场生成的 JSON 蓝图落盘到 config/maid_smart/blueprints/——生成即保存，scanExternalBl —— 实现见 BlueprintFileIo.saveJsonBlueprint（v1.2.4 拆分）。 */
    public static void saveJsonBlueprint(String name, String json) { BlueprintFileIo.saveJsonBlueprint(name, json); }

    /** v1.5.94：删除蓝图（手册删除按钮用） —— 实现见 BlueprintFileIo.deleteBlueprint（v1.2.4 拆分）。 */
    public static boolean deleteBlueprint(String id) { return BlueprintFileIo.deleteBlueprint(id); }

    /**
     * 解析标准结构文件（结构方块导出格式）为步骤列表。
     * 支持 rotation（顺时针 0/90/180/270，需 holder 才能旋转方块状态；holder 为 null 时忽略旋转）。
     * 规则（对齐 numen）：
     * - 黑名单方块 / 液体 / 无物品方块 / 二次半块（门上半、床头）→ 跳过
     * - v1.5.317：keepFluids=true（红石机器蓝图）→ 水/岩浆保留为可建步骤
     *   （机器水道/气泡柱/岩浆焚烧口需要）；普通建筑维持剥离（防洪水/岩浆事故）。
     * - 精确 BlockState 与方块实体数据随步骤携带（|stateSnbt|beSnbt）
     */
    public static List<String> parseStructure(net.minecraft.nbt.CompoundTag tag, int quarters,
                                              net.minecraft.core.HolderGetter<net.minecraft.world.level.block.Block> holder,
                                              boolean keepFluids) {
        try {
            net.minecraft.nbt.ListTag sizeTag = tag.m_128437_("size", 3);
            if (sizeTag.m_128763_(0) < 1) {
                return null;
            }
            int sx = sizeTag.m_128763_(0);
            int sy = sizeTag.m_128763_(1);
            int sz = sizeTag.m_128763_(2);
            net.minecraft.nbt.ListTag paletteTag = tag.m_128425_("palettes", 9)
                    ? tag.m_128437_("palettes", 9).m_128744_(0)
                    : tag.m_128437_("palette", 10);
            if (paletteTag == null || paletteTag.size() == 0) {
                return null;
            }
            net.minecraft.world.level.block.Rotation rotation = net.minecraft.world.level.block.Rotation.NONE;
            int q = Math.floorMod(quarters, 4);
            if (q == 1) {
                rotation = net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
            } else if (q == 2) {
                rotation = net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
            } else if (q == 3) {
                rotation = net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
            }
            // 旋转后的 palette 状态（numen 同款：m_247651_ 解析 + m_60717_ 旋转）
            List<net.minecraft.nbt.CompoundTag> palette = new ArrayList<>();
            for (int i = 0; i < paletteTag.size(); i++) {
                net.minecraft.nbt.CompoundTag stateTag = paletteTag.m_128728_(i);
                if (q != 0 && holder != null) {
                    net.minecraft.world.level.block.state.BlockState state =
                            net.minecraft.nbt.NbtUtils.m_247651_(holder, stateTag).m_60717_(rotation);
                    stateTag = net.minecraft.nbt.NbtUtils.m_129202_(state);
                }
                palette.add(stateTag);
            }
            net.minecraft.nbt.ListTag blocks = tag.m_128437_("blocks", 10);
            List<String> steps = new ArrayList<>();
            // v1.2.2 实测五百九十八（issue #12）：跳过的"没有对应物品"格数（调用方写日志用）
            BlueprintStructureCodec.noItemCells = 0;
            for (int i = 0; i < blocks.size(); i++) {
                net.minecraft.nbt.CompoundTag cell = blocks.m_128728_(i);
                net.minecraft.nbt.ListTag pos = cell.m_128437_("pos", 3);
                int x = pos.m_128763_(0);
                int y = pos.m_128763_(1);
                int z = pos.m_128763_(2);
                int stateIndex = cell.m_128451_("state");
                if (stateIndex < 0 || stateIndex >= palette.size()) {
                    continue;
                }
                // 坐标旋转（numen 同款矩阵）
                int rx;
                int rz;
                if (q == 1) {
                    rx = sz - 1 - z;
                    rz = x;
                } else if (q == 2) {
                    rx = sx - 1 - x;
                    rz = sz - 1 - z;
                } else if (q == 3) {
                    rx = z;
                    rz = sx - 1 - x;
                } else {
                    rx = x;
                    rz = z;
                }
                net.minecraft.nbt.CompoundTag stateTag = palette.get(stateIndex);
                String blockName = stateTag.m_128461_("Name");
                // v1.5.317：机器蓝图（keepFluids）保留水/岩浆；其余黑名单照旧剥离
                boolean fluidKept = keepFluids && ("minecraft:water".equals(blockName)
                        || "minecraft:lava".equals(blockName));
                if (FORBIDDEN.contains(blockName) && !fluidKept) {
                    continue;
                }
                // 二次半块（门上半/床头）跳过——放置主半块时 MC 自动补全
                if (BlueprintStructureCodec.isSecondaryHalf(stateTag)) {
                    continue;
                }
                net.minecraft.world.level.block.Block block =
                        net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(ResourceLocation.parse(blockName));
                // v1.2.2 实测五百九十八（issue #12 追查）【这道过滤旧版对墙上方块从来没生效过】：
                // 旧版判"有没有对应物品"用的是 `block.asItem() == AIR`，而原版墙上方块
                // （wall_torch / *_wall_sign / *_wall_banner / *_wall_hanging_sign）的 asItem()
                // **并不是 AIR**——BlockItem 注册时把墙上方块也登记进了 BY_BLOCK，返回的是
                // **前身物品**（redstone_wall_torch → 红石火把、oak_wall_sign → 橡木告示牌）。
                // 于是这类方块照样进计划，然后在取料那一步撞上"同名物品查不到"——那正是
                // issue #12 的现场（反馈者观察到"过滤没拦住"）；材料侧已在实测五百八十三
                // 修好（itemForBlock 做 wall_ 归一）。现在这里统一走**同一个** itemForBlock()：
                // 判据与材料链完全一致，真正没有物品形式的方块（火/传送门…）才跳过，
                // 跳过的格数由调用方写进日志（旧版静默——反馈原话"失败完全静默"）。
                boolean fluidBlock = "minecraft:water".equals(blockName) || "minecraft:lava".equals(blockName);
                if (block == null || (fluidBlock && !fluidKept) || itemForBlock(blockName) == null) {
                    BlueprintStructureCodec.noItemCells++;
                    continue;
                }
                StringBuilder step = new StringBuilder();
                step.append(rx).append(',').append(y).append(',').append(rz).append(',').append(blockName);
                // 精确状态（台阶/楼梯朝向等）随步骤携带
                if (stateTag.m_128425_("Properties", 10)) {
                    step.append('|').append(net.minecraft.nbt.NbtUtils.m_178057_(stateTag));
                }
                // 方块实体数据（箱子内容/告示牌文字等）
                if (cell.m_128425_("nbt", 10)) {
                    step.append('|').append(net.minecraft.nbt.NbtUtils.m_178057_(cell.m_128469_("nbt")));
                }
                steps.add(step.toString());
            }
            if (steps.isEmpty() || steps.size() > structureMaxBlocks()) {
                return null;
            }
            return steps;
        } catch (Exception e) {
            // v1.5.26：记录真实异常（之前静默吞掉 → 日志只有"解析返回 null"看不到原因）
            LOGGER.warn("parseStructure: 解析结构异常 -> {}", e.toString());
            return null;
        }
    }

    /** 兼容重载：非机器路径（普通建筑 .nbt/旋转）默认剥离水/岩浆 */
    public static List<String> parseStructure(net.minecraft.nbt.CompoundTag tag, int quarters,
                                              net.minecraft.core.HolderGetter<net.minecraft.world.level.block.Block> holder) {
        return parseStructure(tag, quarters, holder, false);
    }

    /* ================= 格式转换器（v1.5.14，迁移自 numen BlueprintFormats） =================
     * .litematic（Litematica 模组导出）/ .schem（WorldEdit/Schematica 导出）/
     * .schematic（v1.5.37 Planet Minecraft 标准 MCEdit 格式）→ 标准结构格式
     * （size/palette/blocks/entities），随后统一走 parseStructure。
     * 网上分享的复杂精美建筑图纸绝大多数是这三种格式——这是"能建精美建筑"的关键。
     */

    /** v1.5.81：强制拆除——目标方块【破坏成掉落物】（可回收，玩家可捡） —— 实现见 BlueprintPlacement.forceBreak（v1.2.4 拆分）。 */
    public static void forceBreak(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) { BlueprintPlacement.forceBreak(level, pos, state); }

    /** v1.5.46：清理建造原点附近掉落物（悬空方块历史掉落的物品堆积，完成时调用一次）v1.5.46：清理建造区掉落物（悬空方块历史掉落的物品堆积，实体区块曾达  —— 实现见 BlueprintPlacement.cleanupDrops（v1.2.4 拆分）。 */
    public static void cleanupDrops(net.minecraft.server.level.ServerLevel level, BlockPos origin, List<String> plan) { BlueprintPlacement.cleanupDrops(level, origin, plan); }

    /** v1.5.45：附着方块的支撑方向（null = 无需支撑） —— 实现见 BlueprintPlacement.supportDirection（v1.2.4 拆分）。 */
    public static net.minecraft.core.Direction supportDirection(net.minecraft.world.level.block.state.BlockState state) { return BlueprintPlacement.supportDirection(state); }

    /** v1.5.349：蓝图指定世界坐标是否为【流体步骤】(水/岩浆)——识别"设计意图的流体支撑"：村民机水闸活板门等，顶部活板门上方就是水（原版 canSurvi —— 实现见 BlueprintPlacement.isFluidStepAt（v1.2.4 拆分）。 */
    public static boolean isFluidStepAt(List<String> plan, net.minecraft.core.BlockPos origin, net.minecraft.core.BlockPos worldPos) { return BlueprintPlacement.isFluidStepAt(plan, origin, worldPos); }

    /** v1.5.79：统计建造完成时仍悬空的重力方块数（沙子/沙砾/混凝土粉末/铁砧）——图纸的悬浮设计在原版物理中不成立：建造期间被重力冻结（GravityFree —— 实现见 BlueprintPlacement.countSuspendedGravity（v1.2.4 拆分）。 */
    public static int countSuspendedGravity(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos origin, java.util.List<String> plan) { return BlueprintPlacement.countSuspendedGravity(level, origin, plan); }

    /** 空气或自然地形（可覆盖）？运行时放置兜底判定 —— 实现见 BlueprintBlockData.isAllowedGround（v1.2.4 拆分）。 */
    public static boolean isAllowedGround(net.minecraft.world.level.block.state.BlockState state) { return BlueprintBlockData.isAllowedGround(state); }

    /** 解析步骤串 → {x,y,z,blockid,stateSnbt,beSnbt}（state/be 可为 null） —— 实现见 BlueprintStepMath.parseStep（v1.2.4 拆分）。 */
    public static String[] parseStep(String step) { return BlueprintStepMath.parseStep(step); }

    /** v1.5.159：蓝图占地尺寸 {宽, 高, 深}（相对坐标 min..max +1；无法解析返回 {0,0,0}）——手册"区块显示"预览用：以玩家为中心展示 —— 实现见 BlueprintStepMath.blueprintSize（v1.2.4 拆分）。 */
    public static int[] blueprintSize(List<String> steps) { return BlueprintStepMath.blueprintSize(steps); }

    /** 带缓存的占地尺寸（预热后按 id 命中；未命中时计算并缓存） —— 实现见 BlueprintStepMath.blueprintSizeCached（v1.2.4 拆分）。 */
    public static int[] blueprintSizeCached(String id, List<String> steps) { return BlueprintStepMath.blueprintSizeCached(id, steps); }

    /** 统一查找蓝图（旋转 0）：内置优先，其次外部（config/maid_smart/blueprints + 存档 schematics/） —— 实现见 BlueprintStepMath.getBlueprint（v1.2.4 拆分）。 */
    public static List<String> getBlueprint(String id) { return BlueprintStepMath.getBlueprint(id); }

    /** 统一查找蓝图并应用旋转（仅外部结构蓝图支持；内置/JSON 不支持旋转时返回 0 度版本） —— 实现见 BlueprintStepMath.rotateSteps（v1.2.4 拆分）。 */
    public static List<String> rotateSteps(List<String> steps, int quarters, net.minecraft.core.HolderGetter<net.minecraft.world.level.block.Block> holder) { return BlueprintStepMath.rotateSteps(steps, quarters, holder); }

    public static List<String> getBlueprintRotated(String id, int quarters, net.minecraft.core.HolderGetter<net.minecraft.world.level.block.Block> holder) { return BlueprintStepMath.getBlueprintRotated(id, quarters, holder); }

    /** 蓝图显示名（内置中文名 / 外部文件名或 JSON name 字段） —— 实现见 BlueprintCatalog.getBlueprintName（v1.2.4 拆分）。 */
    public static String getBlueprintName(String id) { return BlueprintCatalog.getBlueprintName(id); }

    /** 全部蓝图目录条目（v1.5.16，Promaid 手册 GUI 用）：每项 {id, 显示名, 描述}v1.5.366：内置 19 个（3 小屋 + 12 新结 —— 实现见 BlueprintCatalog.buildCatalogEntries（v1.2.4 拆分）。 */
    public static List<String[]> buildCatalogEntries() { return BlueprintCatalog.buildCatalogEntries(); }

    /** 带材料缺口的目录条目（v1.5.18；v1.5.24 改为以【主人背包】为准——材料从主人背包确认并自动交付，避免多女仆/女仆背包空时误导） —— 实现见 BlueprintCatalog.buildCatalogEntriesWithMaterials（v1.2.4 拆分）。 */
    public static Map<String[], Map<String, int[]>> buildCatalogEntriesWithMaterials(Player player) { return BlueprintCatalog.buildCatalogEntriesWithMaterials(player); }

    /** 蓝图摘要（尺寸/块数/材料 top5），供 smart_build 汇报 —— 实现见 BlueprintCatalog.describe（v1.2.4 拆分）。 */
    public static String describe(String id, List<String> steps) { return BlueprintCatalog.describe(id, steps); }

    /** 解析 LLM 生成的 JSON 蓝图；失败返回 null —— 实现见 BlueprintCatalog.parseJson（v1.2.4 拆分）。 */
    public static List<String> parseJson(String json) { return BlueprintCatalog.parseJson(json); }

    /** 解析 JSON 蓝图中的 name 字段（可选）；解析失败返回 null —— 实现见 BlueprintCatalog.parseJsonName（v1.2.4 拆分）。 */
    public static String parseJsonName(String json) { return BlueprintCatalog.parseJsonName(json); }

    /** 校验步骤列表（块数/范围/白名单），非法返回错误信息，合法返回 null */
    public static String validate(List<String> steps) {
        if (steps == null || steps.size() > maxBlocks()) {
            return "蓝图无效：块数必须为 1~200";
        }
        for (String step : steps) {
            // v1.5.311：走 parseStep——步骤可能带 |stateSnbt|beSnbt（SNBT 内含逗号），
            // 旧版 step.split(",") 会把 state 里的逗号拆开误报"步骤格式错误"
            String[] parts = parseStep(step);
            if (parts == null || parts.length < 4) {
                return "蓝图无效：步骤格式错误";
            }
            try {
                int x = Integer.parseInt(parts[0]);
                int y = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[2]);
                if (Math.abs(x) > maxRange() || Math.abs(z) > maxRange() || y < 0 || y > maxHeight()) {
                    return "蓝图无效：坐标超出范围（平面 ±12，高度 0~8）";
                }
                if (!WHITELIST.contains(parts[3])) {
                    return "蓝图无效：方块不在建筑白名单内: " + parts[3];
                }
            } catch (NumberFormatException e) {
                return "蓝图无效：坐标不是数字";
            }
        }
        return null;
    }

    /** 统计蓝图步骤的总需求（blockId → 数量；等价族合并到"主方块"上） —— 实现见 BlueprintMaterials.countNeeds（v1.2.4 拆分）。 */
    public static Map<String, Integer> countNeeds(List<String> steps) { return BlueprintMaterials.countNeeds(steps); }

    /** v1.5.318：蓝图液体【工具/材料】需求——水需要 1 个水桶作工具（不消耗，无限放水源）；岩浆需要 N 个岩浆桶（放置后返还空桶，需周转） —— 实现见 BlueprintMaterials.fluidBucketNeeds（v1.2.4 拆分）。 */
    public static Map<String, Integer> fluidBucketNeeds(List<String> steps) { return BlueprintMaterials.fluidBucketNeeds(steps); }

    /** v1.5.318：液体/工具需求中文提示——"另需水桶×1(作工具，不消耗)、岩浆桶×N(放置后返还空桶)、矿车×N(完工自动放置，需备齐)"；蓝图无需求返回空 —— 实现见 BlueprintMaterials.fluidNeedText（v1.2.4 拆分）。 */
    public static String fluidNeedText(List<String> steps) { return BlueprintMaterials.fluidNeedText(steps); }

    /** 带缓存的材料需求统计；steps 为 null 时返回空 Map —— 实现见 BlueprintMaterials.countNeedsCached（v1.2.4 拆分）。 */
    public static Map<String, Integer> countNeedsCached(String id, List<String> steps) { return BlueprintMaterials.countNeedsCached(id, steps); }

    /** 材料预检：返回缺失清单（block → 缺口数量）；材料充足返回 null —— 实现见 BlueprintMaterials.calcShortfall（v1.2.4 拆分）。 */
    public static Map<String, Integer> calcShortfall(EntityMaid maid, List<String> steps) { return BlueprintMaterials.calcShortfall(maid, steps); }

    /** v1.5.24：玩家是否创造模式（创造模式材料视为无限，跳过检测/扣除/交付） —— 实现见 BlueprintMaterials.isCreative（v1.2.4 拆分）。 */
    public static boolean isCreative(Player player) { return BlueprintMaterials.isCreative(player); }

    /** v1.5.124：玩家 + 女仆合计持有（防整数溢出）——创造模式玩家 countPlayerMaterial返回 Integer.MAX_VALUE（无限）， —— 实现见 BlueprintMaterials.combinedHave（v1.2.4 拆分）。 */
    public static int combinedHave(Player owner, EntityMaid maid, String blockId) { return BlueprintMaterials.combinedHave(owner, maid, blockId); }

    /** v1.5.179：主人背包 + 该维度所有【绑定女仆】（建筑任务）背包的持有量合计——实时缺料 = 需求 − 已建 − 该合计 —— 实现见 BlueprintMaterials.combinedHaveAll（v1.2.4 拆分）。 */
    public static int combinedHaveAll(net.minecraft.server.level.ServerLevel level, Player owner, String blockId) { return BlueprintMaterials.combinedHaveAll(level, owner, blockId); }

    // ==================== 方块/物品 id → 中文名（v1.5.126：材料提示友好输出） ====================

    /** v1.5.126：方块/物品 id → 中文名（材料提示用） —— 实现见 BlueprintNames.cnName（v1.2.4 拆分）。 */
    public static String cnName(String id) { return BlueprintNames.cnName(id); }

    /** v1.5.24：玩家背包材料预检（材料以主人背包为准）——返回缺失清单；充足返回 null —— 实现见 BlueprintMaterials.calcPlayerShortfall（v1.2.4 拆分）。 */
    public static Map<String, Integer> calcPlayerShortfall(Player player, List<String> steps) { return BlueprintMaterials.calcPlayerShortfall(player, steps); }

    /** v1.5.24：统计玩家背包中指定方块物品的持有数量（等价族感知；玩家为 null 返回 0） —— 实现见 BlueprintMaterials.countPlayerMaterial（v1.2.4 拆分）。 */
    public static int countPlayerMaterial(Player player, String blockId) { return BlueprintMaterials.countPlayerMaterial(player, blockId); }

    /** v1.5.24：材料确认后，把蓝图所需（女仆背包缺的部分）从玩家背包自动转交给女仆 —— 实现见 BlueprintMaterials.deliverToMaid（v1.2.4 拆分）。 */
    public static void deliverToMaid(Player player, EntityMaid maid, Map<String, Integer> need) { BlueprintMaterials.deliverToMaid(player, maid, need); }

    /** 统计背包中指定方块物品的持有数量（支持等价族：族内任意物品都算） —— 实现见 BlueprintMaterials.countMaterial（v1.2.4 拆分）。 */
    public static int countMaterial(EntityMaid maid, String blockId) { return BlueprintMaterials.countMaterial(maid, blockId); }

    /** 从背包取 1 个指定方块对应的物品（支持等价族 + v1.5.254 自定义替代） —— 实现见 BlueprintMaterials.consumeBlock（v1.2.4 拆分）。 */
    public static Item consumeBlock(EntityMaid maid, String blockId) { return BlueprintMaterials.consumeBlock(maid, blockId); }

    // ================= v1.5.254：缺料自定义替代（高度分类） =================
    /** v1.5.287：方块 id → 物品 id 特例——1.20.1 不存在 redstone_wire 物品（红石粉物品是 minecraft:redstone —— 表数据见 BlueprintMaterials.BLOCK_ITEM_OVERRIDES（v1.2.4 拆分）。 */
    public static final Map<String, String> BLOCK_ITEM_OVERRIDES = BlueprintMaterials.BLOCK_ITEM_OVERRIDES;

    /** 方块 id → 对应物品 id（含特例映射 + 动态归一；两类都对不上返回原 id） —— 实现见 BlueprintMaterials.itemIdForBlock（v1.2.4 拆分）。 */
    public static String itemIdForBlock(String blockId) { return BlueprintMaterials.itemIdForBlock(blockId); }

    /** 方块 id → 对应物品（含特例与 wall_* 归一）——材料链统一入口 —— 实现见 BlueprintMaterials.itemForBlock（v1.2.4 拆分）。 */
    public static Item itemForBlock(String blockId) { return BlueprintMaterials.itemForBlock(blockId); }

    /** v1.2.2 实测五百八十三（issue #12）：材料提示改念【物品】名——玩家要去找的是物品 —— 实现见 BlueprintMaterials.cnItemName（v1.2.4 拆分）。 */
    public static String cnItemName(String blockId) { return BlueprintMaterials.cnItemName(blockId); }

    /* ================= v1.2.2 实测五百八十五（issue #15）：流体保留判据 ================= */

    /** 玩家要"保留流体"时可加的文件名关键词（提示文案用） —— 实现见 BlueprintMachineDetect.machineKeywordHint（v1.2.4 拆分）。 */
    public static String machineKeywordHint() { return BlueprintMachineDetect.machineKeywordHint(); }

    /** v1.2.2 实测五百九十八（issue #14）：这份图纸要不要保留水/岩浆步骤 —— 实现见 BlueprintMachineDetect.keepFluidsFor（v1.2.4 拆分）。 */
    public static boolean keepFluidsFor(net.minecraft.nbt.CompoundTag tag, String stem) { return BlueprintMachineDetect.keepFluidsFor(tag, stem); }

    /** v1.2.2 实测五百八十五（issue #15）：剥离提示全文（建造开始时给玩家看一眼）；无则空串 —— 实现见 BlueprintMachineDetect.fluidStripWarning（v1.2.4 拆分）。 */
    public static String fluidStripWarning(String blueprintId) { return BlueprintMachineDetect.fluidStripWarning(blueprintId); }

    /* ================= v1.2.2 实测五百八十六（issue #14）：缺料同类宽松 =================
     * 建筑要外观严格（一栋橡木+云杉混搭的房子不该被换成清一色橡木、樱花树不该变橡树），
     * 但机器里告示牌/树叶/羊毛/染色玻璃只是【功能件】（挡水/标记/遮光），缺一个颜色就整台
     * 卡住不值当。所以放宽做成档位：off（旧行为）/ machine（只对机器蓝图）/ always。 */

    /** 建造行为 / 建造入口在动手前告诉材料链"现在是哪份图纸"（无图纸传 null = 严格） —— 实现见 BlueprintLooseMatching.setMaterialScope（v1.2.4 拆分）。 */
    public static void setMaterialScope(String blueprintId) { BlueprintLooseMatching.setMaterialScope(blueprintId); }

    /** v1.2.2 实测五百八十六（issue #14）：等价族查询统一入口（含按档位生效的同类宽族） —— 实现见 BlueprintLooseMatching.equivalentGroup（v1.2.4 拆分）。 */
    public static Set<String> equivalentGroup(String blockId) { return BlueprintLooseMatching.equivalentGroup(blockId); }

    /** v1.2.2 实测五百九十八（issue #14）：**缺料同类宽松用的"机器"判据**——与流体判据（{@link #keepFluidsFor}）用同一把尺 —— 实现见 BlueprintLooseMatching.isMachineForMaterials（v1.2.4 拆分）。 */
    public static boolean isMachineForMaterials(String id) { return BlueprintLooseMatching.isMachineForMaterials(id); }

    /** 该方块（按归一后的物品 id）所属的同类宽族；不在放宽范围 / 档位不允许 → null —— 实现见 BlueprintLooseMatching.looseGroup（v1.2.4 拆分）。 */
    public static Set<String> looseGroup(String blockId) { return BlueprintLooseMatching.looseGroup(blockId); }

    /** 半格高判定（台阶类——替换表按此分类） —— 实现见 BlueprintBlockTraits.isSlabHeight（v1.2.4 拆分）。 */
    public static boolean isSlabHeight(Block block) { return BlueprintBlockTraits.isSlabHeight(block); }

    /** 两格高判定（门/双植物/甘蔗/竹子——替换表按此分类）v1.5.275：拆分为"竖两格"（本方法）与"横两格"（isWideHeight——床） —— 实现见 BlueprintBlockTraits.isTallHeight（v1.2.4 拆分）。 */
    public static boolean isTallHeight(Block block) { return BlueprintBlockTraits.isTallHeight(block); }

    /** 竖两格（高 2 格：门/高植物/甘蔗/竹子） —— 实现见 BlueprintBlockTraits.isTallVertical（v1.2.4 拆分）。 */
    public static boolean isTallVertical(Block block) { return BlueprintBlockTraits.isTallVertical(block); }

    /** 横两格（宽 2 格：床） —— 实现见 BlueprintBlockTraits.isWideHeight（v1.2.4 拆分）。 */
    public static boolean isWideHeight(Block block) { return BlueprintBlockTraits.isWideHeight(block); }

    /** 无碰撞体积方块（花/草/蕨/火把/地毯/线/红石线/压力板/按钮/梯子/铁轨/花盆/横幅/告示牌/雪层等——isSolid（有碰撞）为 false 的可放置方块 —— 实现见 BlueprintBlockTraits.isNoClip（v1.2.4 拆分）。 */
    public static boolean isNoClip(Block block) { return BlueprintBlockTraits.isNoClip(block); }

    /* ================= v1.5.279：替代方块多维度划分 =================
     * 反馈："自定义方块的种类需要根据多方面维度进行新的划分，仅仅根据一格高、
     * 半格高这些不够"。新增两个自动判定维度（无需配置）：
     * - 材质族：木/石/砖/矿/璃/陶/毛/他（匹配时同族优先）
     * - 功能：红石/照明/存储/炉/装饰/结构（面板显示标记）
     * 与既有形态（半格/一格/竖两格/横两格）+ 碰撞（无碰撞区）共同构成多维标签。 */
    /** 材质族标记（替代品面板显示 + 同族优先匹配用） —— 实现见 BlueprintBlockTraits.materialFamily（v1.2.4 拆分）。 */
    public static String materialFamily(Block block) { return BlueprintBlockTraits.materialFamily(block); }

    /** 功能标记（替代品面板显示）——判定顺序：红石 > 照明 > 存储 > 炉 > 装饰 > 结构 —— 实现见 BlueprintBlockTraits.blockFunction（v1.2.4 拆分）。 */
    public static String blockFunction(Block block) { return BlueprintBlockTraits.blockFunction(block); }

    public static List<String> altSlabs() { return BlueprintBlockTraits.altSlabs(); }

    public static List<String> altBlocks() { return BlueprintBlockTraits.altBlocks(); }

    public static List<String> altTalls() { return BlueprintBlockTraits.altTalls(); }

    /** v1.5.275：横两格替代品（床） —— 实现见 BlueprintBlockTraits.altWides（v1.2.4 拆分）。 */
    public static List<String> altWides() { return BlueprintBlockTraits.altWides(); }

    /** v1.5.275：无碰撞替代品（花/火把/地毯等） —— 实现见 BlueprintBlockTraits.altNoClips（v1.2.4 拆分）。 */
    public static List<String> altNoClips() { return BlueprintBlockTraits.altNoClips(); }

    /** v1.5.254：解析步骤状态串为 BlockState —— 实现见 BlueprintBlockTraits.parseStepState（v1.2.4 拆分）。 */
    public static net.minecraft.world.level.block.state.BlockState parseStepState( net.minecraft.server.level.ServerLevel level, Block placed, String stateSnbt) { return BlueprintBlockTraits.parseStepState(level, placed, stateSnbt); }

    /** v1.5.317：岩浆放置后返还空桶（岩浆桶用后变空桶）——主人背包优先，其次女仆背包，最后掉落在主人/女仆身边（不掉在建造区内，防被完工清理回收） —— 实现见 BlueprintMaterials.returnEmptyBucket（v1.2.4 拆分）。 */
    public static void returnEmptyBucket(net.minecraft.server.level.ServerLevel level, EntityMaid maid, net.minecraft.core.BlockPos target) { BlueprintMaterials.returnEmptyBucket(level, maid, target); }

    /** 以女仆为中心：把蓝图步骤整体平移，使蓝图的平面中心落在原点（原点 = 女仆脚下，由调用方给出） —— 实现见 BlueprintStepMath.trimTerrainLayers（v1.2.4 拆分）。 */
    public static List<String> trimTerrainLayers(List<String> steps) { return BlueprintStepMath.trimTerrainLayers(steps); }

    public static List<String> centerSteps(List<String> steps) { return BlueprintStepMath.centerSteps(steps); }

    /** v1.5.57：建造完成 → 红石统一激活 —— 实现见 BlueprintPlacement.recalcRedstone（v1.2.4 拆分）。 */
    public static void recalcRedstone(net.minecraft.server.level.ServerLevel level, BlockPos origin, List<String> plan) { BlueprintPlacement.recalcRedstone(level, origin, plan); }

    /* ==================== v1.5.315+ 红石机器专属建造策略 ====================
     * 逐个解析 blueprints 目录 21 台外部机器蓝图（litematic 方块构成+红石朝向）后归类：
     * - 轰炸机类（三向/双向/2TNT/自返回/推土机）：活塞+粘液块弹头，红石块为移动
     *   信号源，观察者检测弹头运动，探测铁轨（矿车压轨）触发 TNT 复制。
     * - 打包机类（伪12倍速/六倍速/混杂/带分类/自适应）：灵魂沙气泡柱+冰道水流冲
     *   物品→漏斗→投掷器/活塞打包入箱，观察者/音符盒时钟自转。
     * - 潜影盒仓库类（单排/双排/大仓库）：观察者/音符盒 BUD 检测物品输入→活塞推
     *   潜影盒→比较器/漏斗分类，冰道+气泡柱运输。
     * - 其他：分类机（漏斗链+比较器）/铁砧机（观察者时钟）/村民机（压板触发）/
     *   南瓜机（附生茎无法建造）/甘蔗机（纯水流半自动）。
     * - Never4Get：巨型 TNT 大炮——手动装填触发。
     *
     * v1.5.316【红石机器改革：专属顺序 + 活建造（去禁锢）】：
     * - 机器不再"静默放置（flag 2）+ 完工唤醒/不唤醒"（旧禁锢：机器建好冻结在
     *   蓝图导出时的瞬态，线保留冻结 power、活塞保留冻结伸缩态 → 建好不运行）。
     * - 机器改走【专属搭建顺序 sortMachinePlan】：按红石拓扑分层
     *   结构 → 惰性机构 → 活动件 → 传感/信号 → 动力源 → TNT，动力源最后落位。
     * - 机器【活放置】（doPlace flag 3）：红石/水流随放随算；机器在最后一格
     *   动力源落下时自然进入运行态——"建好就能跑"，无需完工唤醒。
     * - 轰炸机类完工自动在探测铁轨上生成矿车（spawnStartMinecarts）启动复制循环。
     * - 关闭开关 MaidSmartConfig.BUILD_MACHINE_SMART = 完整回退旧行为。
     */
    /** v1.5.315：蓝图 id（内置 maid_smart:xxx 或外部 maid_smart_ext:<文件名>）→ 机器家族；非机器返回 null —— 实现见 BlueprintMachineFinish.machineFamily（v1.2.4 拆分）。 */
    public static String machineFamily(String id) { return BlueprintMachineFinish.machineFamily(id); }

    /** v1.5.315：TNT 类机器完成后【不唤醒红石】——旧禁锢遗留：唤醒瞬间活塞预伸缩/观察者预脉冲会把弹头推飞、TNT 错位（轰炸机/Never4Get 巨型 —— 实现见 BlueprintMachineFinish.machineQuietFinish（v1.2.4 拆分）。 */
    public static boolean machineQuietFinish(String id) { return BlueprintMachineFinish.machineQuietFinish(id); }

    /** v1.5.316：是否红石机器蓝图（内置 machine_ 前缀 或 外部机器家族名匹配）——是则走机器专属搭建顺序 + 活建造 —— 实现见 BlueprintMachineFinish.isMachineBlueprint（v1.2.4 拆分）。 */
    public static boolean isMachineBlueprint(String id) { return BlueprintMachineFinish.isMachineBlueprint(id); }

    /** v1.5.315：机器完成提示（中文）——告知玩家该机器如何启动/预期行为；非机器返回空串 —— 实现见 BlueprintMachineFinish.machineFinishTip（v1.2.4 拆分）。 */
    public static String machineFinishTip(String id) { return BlueprintMachineFinish.machineFinishTip(id); }

    /** v1.5.316：机器专属搭建顺序——排序键 = 机器相位 → y → x → z（自下而上稳定） —— 实现见 BlueprintMachineFinish.sortMachinePlan（v1.2.4 拆分）。 */
    public static List<String> sortMachinePlan(List<String> steps, String family) { return BlueprintMachineFinish.sortMachinePlan(steps, family); }

    /** v1.5.316：机器模式状态归一化——丢弃蓝图导出时的冻结瞬态（伸出活塞/通电铁轨/冻结 power/lit），让方块以自然初始态落地，由活放置（flag 3 —— 实现见 BlueprintMachineFinish.normalizeMachineState（v1.2.4 拆分）。 */
    public static String normalizeMachineState(String blockId, String stateSnbt) { return BlueprintMachineFinish.normalizeMachineState(blockId, stateSnbt); }

    /** v1.5.316：轰炸机家族完工自动启动——在蓝图全部探测铁轨上生成矿车（探测铁轨被矿车压住才带电，是 TNT 复制循环的触发条件） —— 实现见 BlueprintMachineFinish.spawnStartMinecarts（v1.2.4 拆分）。 */
    public static int spawnStartMinecarts(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos origin, List<String> plan, String family, EntityMaid maid) { return BlueprintMachineFinish.spawnStartMinecarts(level, origin, plan, family, maid); }

    /** v1.5.315：重放蓝图内全部水方块（flag 3）→ 触发流动计算/灵魂沙气泡柱生成 —— 实现见 BlueprintMachineFinish.activateWater（v1.2.4 拆分）。 */
    public static void activateWater(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos origin, List<String> plan) { BlueprintMachineFinish.activateWater(level, origin, plan); }

    /** v1.5.331：完工 TNT 点火结算——遍历蓝图 TNT 步骤，只点燃【当前邻接带电】的TNT（恢复正确终态）：轰炸机（矿车压轨带电）当场启动复制循环；天机 —— 实现见 BlueprintMachineFinish.settleTntIgnition（v1.2.4 拆分）。 */
    public static void settleTntIgnition(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos origin, List<String> plan) { BlueprintMachineFinish.settleTntIgnition(level, origin, plan); }

    /** v1.5.25：过滤掉已建好的步骤（目标格已是目标方块或等价族内替代品），返回"尚未建造"的步骤列表 —— 实现见 BlueprintPlacement.filterBuilt（v1.2.4 拆分）。 */
    public static List<String> filterBuilt( net.minecraft.world.level.Level level, BlockPos origin, List<String> steps) { return BlueprintPlacement.filterBuilt(level, origin, steps); }

    /** v1.5.179：扫描区块内已与蓝图匹配（已建）的方块，按材料种类计数——实时缺料 = 总需求 − 已建（本方法）− 背包（combinedHaveAll） —— 实现见 BlueprintPlacement.countBuiltMaterials（v1.2.4 拆分）。 */
    public static Map<String, Integer> countBuiltMaterials( net.minecraft.world.level.Level level, BlockPos origin, List<String> steps) { return BlueprintPlacement.countBuiltMaterials(level, origin, steps); }

    /** 建造区域障碍物预检（v1.5.13）：检查每个目标格是否可建造 —— 实现见 BlueprintPlacement.findObstacles（v1.2.4 拆分）。 */
    public static String findObstacles( net.minecraft.world.level.Level level, BlockPos origin, List<String> steps) { return BlueprintPlacement.findObstacles(level, origin, steps); }

    /** v1.5.28：建造完成后自动开入口（外部蓝图普遍没有门的痛点） —— 实现见 BlueprintPlacement.carveEntrance（v1.2.4 拆分）。 */
    public static void carveEntrance(net.minecraft.server.level.ServerLevel level, BlockPos origin, List<String> plan, com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid) { BlueprintPlacement.carveEntrance(level, origin, plan, maid); }

    /** v1.5.33：识别世界中"蓝图已建部分"的原点（计划丢失/名字不匹配时的续建兜底） —— 实现见 BlueprintPlacement.findExistingOrigin（v1.2.4 拆分）。 */
    public static BlockPos findExistingOrigin(net.minecraft.server.level.ServerLevel level, com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid, List<String> steps) { return BlueprintPlacement.findExistingOrigin(level, maid, steps); }

}
