package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.PositionTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.HashMap;
import java.util.Map;

/**
 * 挖矿行为：找最近且最有价值的矿石 → 走过去 → 手持镐渐进开采（v1.5.19）。
 * 平衡设计（对齐 maid_useful_task 伐木逻辑 + 玩家挖掘公式）：
 * - 必须手持镐才能开采（无镐时只寻路不破坏）
 * - **渐进挖掘**：不是秒破——每 tick 累计破坏进度（与玩家同公式）
 * - **工具加速**：石镐/铁镐/钻石镐/效率附魔自动加快挖掘（空手极慢）
 * - **正确工具判定**（v1.5.24）：石镐挖钻石矿这类"需更高阶镐"的矿石，
 *   按玩家规则用 100 除数（慢 3.3 倍），不会"秒破钻石"
 * - **女仆稍快**（v1.5.24）：整体 ×1.2——人机智商不够，用数值补
 * - **破坏音效**：挖完用 levelEvent 2001（与玩家挖矿完全相同的方块破坏音+粒子）
 * - 只开采白名单矿石，不破坏地形
 * - 掉落物走女仆拾取模式，不凭空生成
 * - v1.5.4（借鉴 numen MiningEconomics）：目标选择 = 她到矿的距离平方（最近优先）
 *   实测九百一十三起：旧的"深度惩罚/价值加成"两项已废除——圈内最近的那块就是目标
 */
public class MaidMineBehavior extends MaidWorkBehavior {
    /** 矿石价值（仅用于"挖不动时的播报挑哪块报"——v1.5.102 起不再参与选矿排序） */
    private static final Map<Block, Integer> ORE_VALUE = new HashMap<>();

    // 实测九百八十【面板 = 唯一来源】：内置表 / 标签自动识别 / `!id` 拉黑三条旧入口全删——
    // 现在"什么算矿（木材）"只有一个答案：配置里那张目标名单（面板上绿框勾中的那些），由
    // loadCustom*() 读进下面的表；默认值来自 MaidSmartConfig 里名单的默认项。
    /** 方块能不能当矿——**本模组唯一判据**（挖矿行为与排班探测共用这一份，不再各写一套）。 */
    public static boolean isTargetOre(BlockState state) {
        return oreValueOf(state) != null;
    }

    /** 注册名 → 方块（矿表行 / 拉黑行共用；取不到给 null） */
    private static Block blockOf(String id) {
        try {
            return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id.trim()));
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("MaidMineBehavior", ignored);
            return null;
        }
    }

    /**
     * 【实测九百九十九】标签行（`#命名空间:标签`，矿表 / 木材表共用）→ 该方块标签下全部方块的注册名。
     *
     * <p>取不到 / 标签不存在 / 任何异常 → 空表（恒不命中的标签什么都不影响）。直接查注册表的
     * tag 视图，不做全表扫描；服务端与客户端（面板绿勾判定）都可用。
     */
    public static java.util.List<String> blockIdsInTag(String line) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            String name = line == null ? "" : line.trim();
            if (name.startsWith("#")) {
                name = name.substring(1);
            }
            if (name.isEmpty()) {
                return out;
            }
            net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> tag =
                    net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
                    net.minecraft.resources.ResourceLocation.parse(name));
            for (net.minecraft.core.Holder<net.minecraft.world.level.block.Block> h : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getTagOrEmpty(tag)) {
                net.minecraft.resources.ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(h.value());
                if (id != null) {
                    out.add(id.toString());
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidMineBehavior", ignored);
        }
        return out;
    }

    /** 矿值查询（**唯一判据**）：只认配置里那张目标矿物表——不在表里就不是矿。
     *  实测九百八十：旧的"标签自动识别"（forge:ores / c:ores 兜底）已删，面板上没勾的
     *  方块一律不算矿（开发者口径："自动识别只会把绿色框里面的矿物识别为有效矿物"）。
     *  首次调用会懒加载配置，早于任何扫描也能拿到正确的表。 */
    private static Integer oreValueOf(BlockState state) {
        if (!customOresLoaded) {
            ensureCustomOres();
        }
        return ORE_VALUE.get(state.getBlock());
    }

    /**
     * 自定义矿表——适配其他 mod 的矿石。首次扫描/配置保存后调用。
     * ORE_VALUE 残留该方块 → 女仆仍继续挖（"取消勾选不生效"根因）。
     * 配置 oreValues 是唯一事实源：从配置删除的矿（含曾 add 的模组矿如银矿石、
     * 以及从默认列表删掉的内置矿）立即从 ORE_VALUE 消失；删光矿 = 女仆不挖矿，
     * 这是玩家主动选择，属正常响应。
     */
    public static void loadCustomOres() {
        try {
            ORE_VALUE.clear(); // v1.0.3.1：先清空——保证移除/取消勾选生效
            for (String line : com.maidsmart.config.MaidSmartConfig.MINE_ORE_VALUES.get()) {
                if (line == null) {
                    continue;
                }
                String raw = line.trim();
                // 实测九百八十：只认 `id=价值` 行；实测九百九十九起 `#命名空间:标签` 的标签行
                // 也认（展开成整条标签的方块，见 blockIdsInTag）。旧版写下的 `!id`（拉黑行）在这里
                // 被跳过 = 不进表 = 不挖，与旧行为完全一致（"拉黑"这个概念本身已删）。
                String[] parts = raw.split("=", 2);
                if (parts.length != 2) {
                    continue;
                }
                try {
                    int v = Integer.parseInt(parts[1].trim());
                    // 实测九百九十九：`#` 开头的标签行——整条方块标签按同一价值一起进表
                    // （默认表尾两条就是矿物标签，模组矿石不必逐块添加）；取不到的标签整行跳过。
                    String key = parts[0].trim();
                    if (key.startsWith("#")) {
                        for (String tid : blockIdsInTag(key)) {
                            Block tb = blockOf(tid);
                            if (tb != null) {
                                ORE_VALUE.put(tb, v);
                            }
                        }
                        continue;
                    }
                    Block b = blockOf(key);
                    if (b != null) {
                        ORE_VALUE.put(b, v);
                    }
                } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("MaidMineBehavior", ignored);
                }
            }
            customOresLoaded = true;
            ORE_CACHE.clear(); // v1.5.113：矿表变化 → 缓存失效，强制重建
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("MaidMineBehavior", ignored);
        }
    }

    /** v1.5.88：懒加载自定义矿表（config 文件加载完成后首次扫描才真正读到值） */
    private static void ensureCustomOres() {
        if (!customOresLoaded) {
            loadCustomOres();
        }
    }

    /** v1.5.25g：搜索半径扩大（16→24）——区块内更多矿物能感知。
     *  v1.5.47：改为以【锚点】为中心（防漂移），水平半径 16（33×33 扫描面）
     *  v1.5.87：水平半径放大到 24（49×49 扫描面）——矿物检索范围更大
     *  v1.5.88：改为读配置面板（mine.searchRadius） */
    /** v1.5.102：检索半径/锚点半径/穿透预算/垂直范围/废石保留量等已由配置面板驱动（mine 段）。
     *  剩余数值（锚点超时/重定位节流/迁移停滞/目标超时/挖掘距离/搭高节奏/清理间隔/播报限频）
     *  同样全部改从配置面板读取，常量已删。 */
    private static double reachSq() {
        double r = com.maidsmart.config.MaidSmartConfig.MINE_REACH.get();
        return r * r;
    }

    /** 实测八百六十九：没镐不启动的气泡提示限频（实体 ID → 上次提示 tick） */
    private static final Map<Integer, Long> NO_PICK_REPORT_SINCE = new HashMap<>();

    /** v1.5.113：找矿结果缓存——全量扫描每 5 秒一次，期间只做廉价校验（A1 性能优化） */
    private static final Map<Integer, OreCache> ORE_CACHE = new HashMap<>();
    /** 缓存 TTL（tick，5 秒）——矿石静态不变，5 秒内只校验存在性即可 */
    private static final long ORE_CACHE_TTL = 100L;

    /** 实测九百三十六：这条挡路块"走不到"记下的位置 + 记住到哪一拍（见挡路块分支，防来回横跳）。 */
    private BlockPos unreachableBlocker;
    private long unreachableBlockerUntil;
    /** 挡路块"走不到"的记忆时长（拍）：这几十拍内不再试它，直接交给接近链。 */
    private static final long MINE_BLOCKER_UNREACHABLE_TICKS = 40L;

    /**
     * v1.1.0 实测六十一（借鉴 TLM-Sincerely 预算制探测）：全量扫描的分帧游标状态
     * （与伐木 WoodScanState 同款）——每 tick 最多扫 MINE_SCAN_BUDGET 格，跨 tick 续进。
     */
    private static final class OreScanState {
        final BlockPos anchor;
        final long startedAt;
        int dy;
        int dx;
        int dz;
        final java.util.List<BlockPos> found = new java.util.ArrayList<>();
        final java.util.Map<BlockPos, Integer> blockingCache = new java.util.HashMap<>();
        BlockPos best;
        double bestScore = Double.MAX_VALUE;

        OreScanState(BlockPos anchor, long startedAt, int radius, int down) {
            this.anchor = anchor;
            this.startedAt = startedAt;
            this.dy = -down;
            this.dx = -radius;
            this.dz = -radius;
        }
    }

    private static final Map<Integer, OreScanState> MINE_SCANS = new HashMap<>();

    /** 审计M4修复（v1.5.383）：女仆实体卸载时清理其全部行为状态表（防长会话泄漏） */
    public static void forget(int maidEntityId) {
        ANCHORS.remove(maidEntityId);
        OUT_SINCE.remove(maidEntityId);
        LAST_RELOCATE.remove(maidEntityId);
        SLIDE_SINCE.remove(maidEntityId);
        TARGET_SINCE.remove(maidEntityId);
        SKIP_REPORT_SINCE.remove(maidEntityId);
        BLOCKED_REPORT_SINCE.remove(maidEntityId);
        NO_BLOCK_REPORT_SINCE.remove(maidEntityId);
        IDLE_PAUSE_UNTIL.remove(maidEntityId); // 实测九百七十七：无目标静默窗口一并清
        NO_PICK_REPORT_SINCE.remove(maidEntityId); // 实测八百六十九
        ORE_CACHE.remove(maidEntityId);
        MINE_SCANS.remove(maidEntityId); // 实测六十一：分帧扫描游标一并清
        RECENT_DISCARD.remove(maidEntityId); // 实测九百四十一：停滞弃置表（与伐木共表）
    }

    /** 审计 P-9：按 UUID 清理 MINING 集合（int 表与 UUID 表分开清理） */
    public static void forgetUuid(java.util.UUID maidUuid) {
        MINING.remove(maidUuid);
    }

    /** v1.5.113：找矿缓存条目——全量扫描得到的矿位置 + 各自挡路预算（供缓存轮复用） */
    private record OreCache(long builtAt, java.util.List<BlockPos> ores,
                            java.util.Map<BlockPos, Integer> blocking) {
    }

    /** v1.5.87：正在挖矿的女仆（拾取任务据此让位——捡掉落物最低优先级） */
    private static final java.util.Set<java.util.UUID> MINING =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** v1.5.87：女仆是否正在挖矿（MaidPickupPriorityMixin 检查用） */
    public static boolean isMining(EntityMaid maid) {
        return MINING.contains(maid.getUUID());
    }

    /** v1.5.87：该位置是否是女仆搭的方块（实测七十一改跨系统统一查询——挖矿的
     *  扫描/连锁/挡路判定不再把搭路/自保/伐木垫的方块当成矿石目标或开路块） */
    public static boolean isMiningPlaced(ServerLevel level, BlockPos pos) {
        return PlacedBlockTracker.isAnyPlaced(level, pos);
    }

    /** v1.5.87：被硬挡路（箱子/机器等）弃置的矿——持续排除，不再反复选中报点 */
    private final java.util.Set<BlockPos> blockedOres = new java.util.HashSet<>();
    /** v1.5.85：本次扫描中镐子挖不动的矿（记录价值最高的一个，用于播报） */
    private BlockPos skippedOrePos = null;
    private String skippedOreName = null;
    private String skippedOreTool = null;
    private int skippedOreValue = -1;

    /** 实测九百四十一【"卡死脱困"补回，但只认真的没挪窝】：九百三十二 把弃置整段删了
     *  （当时口径：她有镐有方块、不存在够不着，推进链必须自己送她上去）——可实机里站位在矿
     *  下方、头顶那格刨不动、导航又给不出路径这三件事同时成立时，推进链的三个出口（垫高 /
     *  挖开头顶 / 走位）全落空，她会顶着 mine 够不着-继续推进 与 work walk stuck … path=false
     *  这一对日志原地卡死（17:34 实测 ≥25 秒，直到退出游戏）。这里按伐木同款补回"停滞检测"，
     *  判据收紧为【同一目标 + 双脚位置连续 MINE_WEDGE_TICKS 拍一字未动】——她只要在推进
     *  （垫高 / 走位 / 开挖）双脚就一直在变，不会被误弃。见 distSq > reachSq 分支。 */
    private static final int MINE_WEDGE_TICKS = 40;
    private BlockPos lastStuckPos = null;
    private int stuckTicks = 0;
    private BlockPos stuckTarget = null;
    private int wedgeStreak = 0;
    private long lastWedgeAt = 0L;

    /** 实测九百二十九：弃置留痕限频（拍，5 秒/条）。 */
    private long giveUpLogAt = 0L;
    /** 实测九百二十六：框空换框时「主人在锚点上」用的 8 向轮转计数（每滑一次转 45°）。 */
    private int slideTurn = 0;
    /** v1.5.25：本次自己搭过的位置（搭高/斜坡/搭桥）——10 秒后自动销毁，绝不挖自然地形。
     *  v1.5.28：改为【全局静态追踪器】——旧版挂在行为实例上，行为停止（挖完矿
     *  canContinue=false）后 expirePlacedBlocks 不再运行 → 搭的方块永久残留。
     *  现在放置即登记到全局表，由 ServerTickEvent 每 tick 统一清理，与行为生命周期无关。
     *  v1.5.102：清理时限从配置面板读取（mine.placedLifetime，秒→tick）
     *  v1.1.0 实测四十二：换 PlacedBlockTracker——绑定搭建女仆（到期强制进她背包，
     *  不再 8 格附近查找）；女仆魂符收回暂停计时。 */

    /** 全局追踪器（绑定搭建者 + 魂符暂停计时；实测四十二）
     *  实测四百二十五：refreshOnOwnerStand=true——主人踩在她垫的方块上也刷新寿命
     *  （玩家跟在女仆后面挖矿时，踩着她的垫脚方块不会突然到期消失而踩空坠落）。 */
    static final PlacedBlockTracker PLACED_TRACKER = new PlacedBlockTracker(
            () -> com.maidsmart.config.MaidSmartConfig.MINE_PLACED_LIFETIME.get() * 20L, 4.0, 6.0, true);

    /** v1.5.28：登记一个挖矿搭的方块（每块从自己放置时刻起单独计时，满 10 秒各自销毁） */
    private static void trackPlaced(ServerLevel level, BlockPos pos, Block block, EntityMaid maid) {
        PLACED_TRACKER.track(level, pos, block, maid);
    }

    /** v1.5.28：销毁所有放置超过 10 秒的挖矿搭方块（玩家同款破坏音效+粒子+掉落物）。
     *  每 tick 调用（ServerTickEvent 兜底 + 行为运行中 tick 双保险）。
     *  v1.5.88：清理时间从配置面板读取（mine.placedLifetime 秒）。
     *  v1.5.113（B3）：【女仆正站在上面的搭方块延后清理】——搭高挖垂直矿脉时
     *  底部柱子满 10 秒自动消失会把女仆摔下去；只对"即将销毁的旧块"检查附近
     *  是否有挖矿女仆站着（脚下/所在格），有则下轮再清（走开即清，不残留）。
     *  v1.1.0 实测四十二：改走 PlacedBlockTracker（绑定搭建者/魂符暂停）。 */
    public static void expirePlaced(net.minecraft.server.MinecraftServer server, long gameTime) {
        // v1.1.0 实测四十七：站方块刷新寿命有两道判定（tracker 内实现）——
        // ①绑定女仆本人站在方块上 → 恒刷新（挖矿标记 MINING 在"空闲无矿"时
        // 会移除，但女仆仍站在垫脚方块上等扫描/迁移，旧版只查 MINING 成员
        // → 方块在她脚下照常倒计时到期 = "挖矿/伐木没落实站上刷新"根因）；
        // ②本谓词：任意【正在挖矿】的女仆踩上去也刷新（同任务姐妹借踩不塌）。
        PLACED_TRACKER.expirePlaced(server, gameTime,
                pos -> anyMaidStanding(server, pos, m -> MINING.contains(m.getUUID())));
    }

    /** v1.1.0 实测四十二：supportsAnyMiner 已由 PlacedBlockTracker.anyMaidStanding 取代 */

    /** v1.5.28：服务器停止时清场——内存追踪器会随进程消失，残留方块立即销毁回收
     *  （不等待 10 秒；重进存档不会看到"永不消失"的搭方块）
     *  v1.1.0 实测四十二：改走 PlacedBlockTracker.clearAll */
    public static void clearAll(net.minecraft.server.MinecraftServer server) {
        PLACED_TRACKER.clearAll(server);
    }

    /** v1.5.103：清理已不在任何维度中的女仆的静态 per-maid 数据（防长时运行内存泄漏）。
     *  以 maid.getId()（Entity.getId，单调递增永不回收）为 key 的各 Map 只增不清，
     *  女仆消失（魂符收放/卸载/死亡）后条目永久残留。用 ServerLevel.getEntity(id)（getEntity）
     *  O(1) 查存活，定期由 ProMaidExtension 调用（每 30 秒）。 */
    public static void purgeStaleMaids(net.minecraft.server.MinecraftServer server) {
        try {
            // 全部 per-maid 表的 key 并集（不能只查 ANCHORS——其他表可能有 ANCHORS 没有的 id）
            java.util.Set<Integer> ids = new java.util.HashSet<>();
            ids.addAll(ANCHORS.keySet());
            ids.addAll(OUT_SINCE.keySet());
            ids.addAll(LAST_RELOCATE.keySet());
            ids.addAll(SLIDE_SINCE.keySet());
            ids.addAll(TARGET_SINCE.keySet());
            ids.addAll(SKIP_REPORT_SINCE.keySet());
            ids.addAll(BLOCKED_REPORT_SINCE.keySet());
            ids.addAll(NO_BLOCK_REPORT_SINCE.keySet());
            ids.addAll(NO_PICK_REPORT_SINCE.keySet()); // 实测八百六十九
            ids.addAll(ORE_CACHE.keySet());
            java.util.Set<Integer> alive = new java.util.HashSet<>();
            for (int id : ids) {
                for (ServerLevel lvl : server.getAllLevels()) {
                    net.minecraft.world.entity.Entity e = lvl.getEntity(id);
                    if (e != null && e.isAlive()) {
                        alive.add(id);
                        break;
                    }
                }
            }
            ANCHORS.keySet().removeIf(id -> !alive.contains(id));
            OUT_SINCE.keySet().removeIf(id -> !alive.contains(id));
            LAST_RELOCATE.keySet().removeIf(id -> !alive.contains(id));
            SLIDE_SINCE.keySet().removeIf(id -> !alive.contains(id));
            TARGET_SINCE.keySet().removeIf(id -> !alive.contains(id));
            SKIP_REPORT_SINCE.keySet().removeIf(id -> !alive.contains(id));
            BLOCKED_REPORT_SINCE.keySet().removeIf(id -> !alive.contains(id));
            NO_BLOCK_REPORT_SINCE.keySet().removeIf(id -> !alive.contains(id));
            NO_PICK_REPORT_SINCE.keySet().removeIf(id -> !alive.contains(id)); // 实测八百六十九
            ORE_CACHE.keySet().removeIf(id -> !alive.contains(id));
            MINE_SCANS.keySet().removeIf(id -> !alive.contains(id)); // 实测六十一
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("MaidMineBehavior", ignored);
        }
    }

    /** 破坏一个追踪方块：v1.1.0 实测四十二后由 PlacedBlockTracker 内部处理，
     *  本方法保留给 isOwnPlaced 相关的旧引用（实际已无调用者）。 */
    @SuppressWarnings("unused")
    private static void destroyMarked(ServerLevel level, BlockPos pos, String blockId) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return;
        }
        net.minecraft.resources.ResourceLocation cur = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String curId = cur != null ? cur.toString() : "";
        if (!blockId.isEmpty() && !blockId.equals(curId)) {
            return; // 方块已被替换，不误破坏
        }
        level.levelEvent(2001, pos, Block.getId(state));
        BlockEntity be = level.getBlockEntity(pos);
        if (com.maidsmart.config.MaidSmartConfig.MINE_AUTO_COLLECT.get()) {
            EntityMaid miner = nearbyMiner(level, pos);
            if (miner != null) {
                insertIntoMaidInventory(miner, level,
                        Block.getDrops(state, level, pos, be, miner, miner.getMainHandItem()), pos);
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                return;
            }
        }
        Block.dropResources(state, level, pos, be);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
    }

    /**
     * v1.5.161：自动收集——找离该位置最近的挖矿女仆（搭方块销毁时把掉落物给她，
     * 而不是掉在地上等拾取任务来捡）。3 格内没有就不收集（保留原落地逻辑）。
     */
    private static EntityMaid nearbyMiner(ServerLevel level, BlockPos pos) {
        EntityMaid best = null;
        double bestD = Double.MAX_VALUE;
        for (EntityMaid m : level.getEntitiesOfClass(EntityMaid.class,
                new net.minecraft.world.phys.AABB(pos).inflate(3.0))) {
            if (!m.isAlive() || !MINING.contains(m.getUUID())) {
                continue;
            }
            double d = m.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (d < bestD) {
                bestD = d;
                best = m;
            }
        }
        return best;
    }

    public MaidMineBehavior() {
        // v1.5.124：无限运行时长——旧版 super(emptyMap) 默认 60 tick 上限，
        // 行为每 3 秒自动停止再启动（start 反复执行、实例状态反复重置，
        // 日志刷屏）；挖矿是常驻行为（任务期间持续运行），不应自动重启
        super();
    }

    /** 本行为只认挖矿任务：uid 取自任务类本身（实测九百一十一——不再与伐木共用一份字面量） */
    @Override
    protected ResourceLocation workTaskUid() {
        return MaidMineTask.UID;
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        // v1.5.113：挖矿行为【常驻】——只要任务还是挖矿就持续运行：扫描找矿、
        // 换镐、滑动迁移全部收敛到 doTick 统一处理。旧版 canStart 无矿即返回
        // false → 行为停止 → doTick 不再运行 → 空框时女仆原地发呆、永不迁移
        // （"矿挖完就站住不动"根因）。
        if (maid.getTask() == null || !workTaskUid().equals(maid.getTask().getUid())) {
            return false;
        }
        // 实测八百六十九【没镐不启动，对齐参考 mod 官方流程图「身上有镐子吗」】：手/背包
        // 一把镐都没有 → 挖矿任务不启动 + 限频气泡提示；给了镐下一 tick 自动开工。
        if (com.maidsmart.config.MaidSmartConfig.MINE_REQUIRE_PICK.get()
                && !MaidToolAutoEquip.hasAnyPickaxe(maid)) {
            this.hintNoPickaxe(maid, level);
            return false;
        }
        return true;
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        // v1.5.164：诊断日志（确认女仆跑的是 maid_smart:mine 任务）
        LOGGER.info("mine behavior start: maid={}", maid.getUUID());
        // v1.5.107：不再无条件装备最高级镐——目标矿已由 doTick 按需换镐；
        // 这里仅兜底"start 时 targetPos 为空重新找矿"的按需换镐
        if (this.targetPos == null) {
            BlockPos anchor = this.resolveAnchor(level, maid);
            if (anchor != null) {
                this.targetPos = this.findOre(level, maid, anchor);
                if (this.targetPos != null) {
                    MaidToolAutoEquip.ensureForTarget(maid, level.getBlockState(this.targetPos));
                    TARGET_SINCE.put(maid.getId(), gameTime);
                }
            }
        }
        // v1.5.24：重置搭高节奏（行为重启不残留）
        this.pillarCooldown = 0;
        // v1.5.28：不再清 placedBlocks——追踪已移入全局表（GLOBAL_PLACED），
        // 行为重启不清除，10 秒清理由 ServerTickEvent 统一执行（行为停转也不残留）
        // v1.5.22：恢复持久化进度（行为重启不从头挖）
        this.destroyProgress = this.loadProgress(maid);
        if (this.destroyProgress >= 1.0f) {
            this.destroyProgress = 0.0f; // 异常残留：重置
        }
        // v1.5.87：登记"正在挖矿"——拾取任务据此让位（捡掉落物 = 最低优先级）
        MINING.add(maid.getUUID());
    }

    /** v1.5.87：行为停止 → 退出"正在挖矿"（拾取任务恢复） */
    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        LOGGER.info("mine behavior stop: maid={} target={} progress={} feet={}", maid.getUUID(), this.targetPos, this.destroyProgress, maid.blockPosition());
        MINING.remove(maid.getUUID());
        // 实测九百一十三：停手即让出"链路走位独占"——下 tick TLM 的走路逻辑（跟随/闲逛）恢复
        com.maidsmart.task.MaidWorkTags.setChainDrive(maid, false);
        // v1.5.161：停止时清连锁队列（任务切换/被抢占后不残留过期矿位）
        this.chainQueue.clear();
        this.chainBlock = null;
        super.stop(level, maid, gameTime);
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        // 实测九百一十三【链路走位独占】：手上锁着矿 = 本模组的链路在驱动她 → 原版 TLM 的
        // 走路逻辑（跟随把她写回主人、排班把她拉回工位）整体让位；没目标时立刻还权
        //（"没有矿物 → 回归空闲走路逻辑"）。
        com.maidsmart.task.MaidWorkTags.setChainDrive(maid, this.targetPos != null);
        // v1.5.25f：每 tick 防窒息兜底（移植自保 antiSuffocate）——半身卡进方块
        // 立即强制上移到方块顶面之上。搭高挖矿时实体位移滞后/放偏，头顶检查
        // 拦不住横向卡入，这是最后一道保险：宁可瞬移半步也不被自己搭的方块闷住
        this.antiSuffocate(maid);
        this.antiWaterStuck(level, maid, gameTime);
        // 实测九百七十七：无目标静默窗口内整条工作链路让位。
        // 实测九百八十【照办：让 TLM 的空闲逻辑自己接管】——旧版在这里另写一个闲逛点，既抢
        // TLM 的跟随目标（实机"只跟主人两秒"），点又常常离她不到 1 格（"走一步停一步"）。
        // 现在什么都不写：清目标、停导航、交回走位权，这 10 秒她走的完全是空闲那一套。
        if (this.inIdlePause(maid, gameTime)) {
            this.targetPos = null;
            this.destroyProgress = 0.0f;
            maid.getNavigation().stop();
            com.maidsmart.task.MaidWorkTags.setChainDrive(maid, false);
            return;
        }
        // 实测九百七十九：链路卡死自愈——同一目标 + 没挪窝 + 进度不动满 8 秒 → 弃目标重扫
        if (this.stallWatchdog(level, maid, gameTime)) {
            return;
        }
        // v1.5.109：移除 pullTowardTarget（setDeltaMovement 直接注入速度）——
        // 它与导航互相覆盖、搭高时把女仆从柱子上推走（"移速过快疯狂漂移"根因）。
        // 移动完全交给导航：目标够得着→挖；够不着→搭方块/走过去（approachOre）。
        // v1.5.111：珍稀矿物掉落物回收子系统整体移除（反馈：女仆挖矿途中被
        // 掉落物吸引、满场飞奔去捡，支柱永远建不起来）——挖出的掉落物就地由
        // pickupOreDrops 收进背包；捡不到的留给拾取任务处理，不再打断挖矿流程。
        // v1.5.87：搭方块防掉落窗口——刚搭完方块钳制在格子中心（潜行效果，速度不变），
        // 防止重心滑出方块边缘从柱子上掉下去；v1.5.88 可配置（mine.pillarGuard）
        if (com.maidsmart.config.MaidSmartConfig.MINE_PILLAR_GUARD.get() && this.pillarGuardTicks > 0
                && !BlockWalkOn.isActive(maid)) { // 实测一百九十二：步进推送期间不钳制（防按回上一块中心与推送互相抵消）
            this.pillarGuardTicks--;
            this.pillarGuard(level, maid);
        }
        // v1.5.28：搭方块 10 秒后统一销毁（全局表——行为停止后由 ServerTickEvent 兜底，
        // 此处保留双保险；不做任何即时破坏）
        expirePlaced(level.getServer(), gameTime);
        // v1.5.47：废石丢弃（每 100 tick 一次；保留 JUNK_KEEP 份，超出销毁）
        if (--this.junkCooldown <= 0) {
            this.junkCooldown = com.maidsmart.config.MaidSmartConfig.MINE_JUNK_CHECK_INTERVAL.get();
            this.maybeDropJunk(maid);
        }
        // v1.5.47：锚点解析——迁移推进 / 出框超时重埋（防漂移的边界兜底）
        BlockPos anchor = this.resolveAnchor(level, maid);
        if (anchor == null) {
            this.targetPos = null;
            this.giveUpLog(maid, "锚点解析不出来", null, gameTime);
            return;
        }
        if (this.targetPos == null) {
            // v1.5.161：连锁采集——队列里还有相连的同族矿就先挖队列（不重新扫描；
            // 队列矿若已被挖掉/失效，走下面的目标失效检查自然跳过，再取下一块）
            if (!this.chainQueue.isEmpty()) {
                BlockPos chained = this.chainQueue.poll();
                // 实测五百六十二：连锁队列的矿同样受工作圈钳制
                if (chained != null && this.isOre(level, chained)
                        && com.maidsmart.follow.WorkAreaClamp.allowsWork(maid, chained)) {
                    this.targetPos = chained;
                    TARGET_SINCE.put(maid.getId(), gameTime);
                    MINING.add(maid.getUUID());
                }
            }
            if (this.targetPos == null) {
                // v1.5.113（A1）：扫描节流——每 20 tick 一次廉价校验（全量重建由
                // findOre 内部按 5 秒 TTL 控制，旧版每 3 tick 全量扫 8.9 万格）
                // v1.5.124：20 → 10 tick——挖完一块后找下一块矿更快（缓存轮只校验
                // 已记录矿的位置，廉价），"原地愣几秒"减少一半
                // v1.5.127：10 → 5 tick——缓存轮空时 findOre 已改为立即全量重建，
                // 此节流只控制"挖完后重新评估"的频率，再减半让块间衔接更紧凑
                if (!MINE_SCANS.containsKey(maid.getId()) && this.scanCooldown-- > 0) {
                    this.traceExit(maid, "mine 扫描节流(等5拍)", "cooldown=" + this.scanCooldown);
                    return;
                }
                this.scanCooldown = 5;
                this.targetPos = this.findOre(level, maid, anchor);
                if (this.targetPos == null && MINE_SCANS.containsKey(maid.getId())) {
                    this.traceExit(maid, "mine 分帧扫描进行中(等扫描)", "anchor=" + anchor);
                    // v1.1.0 实测六十二（自查修复）：分帧扫描进行中——只等扫描，不做
                    // 迁移/播报（低扫描预算下单轮扫描超过 5 秒时，旧逻辑会把锚点滑走
                    // → 游标状态因锚点变化作废 → 重扫再滑 → 永远扫不完）
                    return;
                }
                if (this.targetPos == null) {
                // 实测九百一十六【套用伐木的抬头兜底】：扫描框/排除表都给不出目标时，先抬头看一眼
                // 脚下正上方同柱还有没有矿（挖穿的矿洞最终兜底：挖掉几节后站进洞里，剩余矿就在头顶，
                // 不该对着它发呆）。实测九百三十二：不再看"刚弃置"表（弃置已删）。
                BlockPos aboveIdle = this.firstOreAbove(level, maid);
                if (aboveIdle != null) {
                    this.traceExit(maid, "mine 抬头兜底选中", "above=" + aboveIdle);
                    this.targetPos = aboveIdle;
                    TARGET_SINCE.put(maid.getId(), gameTime);
                    MINING.add(maid.getUUID());
                    return;
                }
                // v1.5.140：挖矿空闲（附近无矿）→ 退出"挖矿中"标记，拾取任务恢复正常
                //（反馈：空闲时捡东西积极性太低；空闲 = 与其他工作任务的空闲一致）
                MINING.remove(maid.getUUID());
                // v1.5.85：框内有矿但镐子挖不动 → 不迁移（换镐还是挖不动）：
                // 气泡+主人聊天栏播报一次（限频）等玩家换镐
                if (this.skippedOrePos != null) {
                    this.traceExit(maid, "mine 只剩挖不动的矿", "ore=" + this.skippedOreName
                            + " need=" + this.skippedOreTool + " at=" + this.skippedOrePos);
                    this.reportSkippedOre(maid, gameTime);
                    return;
                }
                // v1.5.113：只有【全量重建】确认本框没矿才迁移——缓存轮空不迁移
                // （等 5 秒重建再判，防锚点高速滑动）
                this.traceExit(maid, "mine 框空", "full=" + this.lastScanWasFull
                        + " blocked=" + this.blockedOres.size() + " anchor=" + anchor);
                if (this.lastScanWasFull) {
                    // 实测九百七十三：确认这一片没矿——气泡提醒主人一次（30 秒/条），
                    // 并把走位交回"空闲"那一套（工作圈内闲逛），不再原地站桩
                    this.notifyNoTarget(maid, gameTime,
                            "这片区域没有目标矿物了，我先在这边活动～");
                    this.startIdlePause(maid, gameTime);
                    // v1.5.102d：此前有被硬物挡住的矿 → 上报一次挡路原因
                    if (!this.blockedOres.isEmpty()) {
                        this.reportBlockedArea(maid, level);
                    }
                    // v1.5.113（B1/B2）：框内挖空 → 锚点朝主人方向滑动一个环宽
                    // （不走路、不原地重埋——旧版"朝主人走走出旧框"被墙堵住时
                    // 原地重埋与旧框重合 → 死循环；纯走路又零产出）
                    if (this.slideAnchor(level, maid, anchor)) {
                        BlockPos next = ANCHORS.get(maid.getId());
                        if (next != null) {
                            LOGGER.info("mine relocate (box empty → fresh box): maid={} next={}",
                                    maid.getUUID(), next);
                        }
                    }
                }
                return;
            }
            // v1.5.107：找到矿 → 按需换镐（手中够用不换；不够换背包能挖的；换不到播报）
            MaidToolAutoEquip.ensureForTarget(maid, level.getBlockState(this.targetPos));
            TARGET_SINCE.put(maid.getId(), gameTime);
            // v1.5.140：有目标 = 挖矿进行中 → 登记标记（拾取任务让位，与 doStart 一致）
            MINING.add(maid.getUUID());
            }
        }
        // 实测九百三十二【删"弃置"】：旧版这里有"锁定超时够不着 → 弃置重选"（targetTimeoutTicks
        // 按破坏速度放大）。开发者口径：她有镐、有垫脚方块时**不存在够不着**——真够不着必然是
        // "走位逻辑少了一个动作"，不该用超时把目标丢掉（丢了换下一块、下一块同样够不着 →
        // 原地打转，就是实机 12:49 那个循环）。现在一律不丢目标：走位 / 垫高 / 挖开头顶三步把
        // 她送到矿前；真正走不到的（头顶是挖不开的方块）由下面"硬挡路"那条另算。
        // v1.5.47：目标失效检查——矿 或 开路废石（穿透挖掘的临时目标）
        // 实测九百三十八【目标跑出检索半径 → 单独清空一次，不进任何排除表】：她走开了 / 目标在框外，
        //   "发现自己锁的矿在检索半径外"就放弃它、重新走一遍链路（下次扫描照旧可能重选，不留记忆）。
        if (this.targetPos != null && targetOutOfRadius(maid, this.targetPos,
                com.maidsmart.config.MaidSmartConfig.MINE_SEARCH_RADIUS.get())) {
            this.traceExit(maid, "mine 目标跑出检索半径→清空并弃框", "target=" + this.targetPos);
            this.targetPos = null;
            this.destroyProgress = 0.0f;
            // 实测九百八十【断掉循环】：光清目标不够——缓存与分帧游标里还留着同一个格，下一轮
            // 又把它选回来（实机 00:34:54~00:35:31 原地站 37 秒）。一并弃框 + 把扫描框挪到她
            // 自己脚下，下一轮从她当前位置重新判定"这一片到底有没有矿"。
            this.dropStaleScan(maid);
            return;
        }
        // 实测九百三十八【目标路径上有岩浆 → 汇报一次】
        if (this.targetPos != null && lavaOnPath(level, maid, this.targetPos)) {
            this.reportLavaPath(maid, this.targetPos, gameTime);
        }
        if (!isOre(level, this.targetPos) && !this.isOpenStone(level, this.targetPos)) {
            this.traceExit(maid, "mine 目标已不是矿/废石", "pos=" + this.targetPos + " block=" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(this.targetPos).getBlock()));
            this.targetPos = null;
            this.destroyProgress = 0.0f;
            return;
        }
        // v1.5.85：目标矿镐子挖不动（中途换镐/低等级镐/镐断）→ 先试着从背包装备
        // 能挖的镐（v1.5.107 按需换镐），换不到才弃目标重选
        // v1.5.113（C3）：换不到镐 → 立即记录并播报"需要更高镐"（不用等 15 秒目标超时）
        if (isOre(level, this.targetPos) && !canHarvest(maid, level.getBlockState(this.targetPos))) {
            this.traceExit(maid, "mine 主手挖不动(试着换镐)", "pos=" + this.targetPos
                    + " block=" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(this.targetPos).getBlock()) + " hand=" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(maid.getMainHandItem().getItem()));
            boolean swapped = MaidToolAutoEquip.ensureForTarget(maid, level.getBlockState(this.targetPos));
            if (!swapped || !canHarvest(maid, level.getBlockState(this.targetPos))) {
                this.recordSkippedOre(maid, this.targetPos, level.getBlockState(this.targetPos));
                this.reportSkippedOre(maid, gameTime);
                // v1.5.161：队列里的矿是同类型——挖不动就全清，别反复试（等换到
                // 更高阶镐后正常找矿流程自然会重新选中）
                this.chainQueue.clear();
                this.chainBlock = null;
                this.targetPos = null;
                this.destroyProgress = 0.0f;
                this.saveProgressNow(maid);
                return;
            }
        }
        // v1.5.90：挡路块优先判定——不必等够到矿本身（"非矿物挡路只报不挖"根因）。
        // 旧版只有"矿在伸手范围内"才检查挡路：矿被厚土墙/岩壁隔着时女仆永远够不着
        // 矿 → 走到墙边站着不动（或 15 秒超时弃置），绝不挖开挡路方块。
        // 现在只要"挡路块"在伸手范围内就开挖，挖穿一层再评估下一层，逐层推进到矿。
        Blocker blocker = null;
        if (isOre(level, this.targetPos)) {
            // v1.0.4：关闭透视时，已锁定目标也不隔墙挖——看不见就不找挡路块（否则
            // findBlockingBlock 会把墙当挡路块挖穿 = 女仆再次获得透视，"勾选障碍物后
            // 透视失效"根因）。
            // 实测九百三十二【不再弃置】：旧版在这里把目标丢掉，于是"选中→看不见→丢弃→
            // 又选中"原地打转。现在只**不隔墙挖**、目标照留——她照常朝它走（走过去自然
            // 重新看见），到跟前要么看见开挖、要么交给下面的挡路/推进逻辑。
            if (com.maidsmart.config.MaidSmartConfig.MINE_SEEK_THROUGH_WALLS.get()
                    || this.hasClearSight(level, maid, this.targetPos)) {
                blocker = this.findBlockingBlock(level, maid, this.targetPos);
            }
        }
        if (blocker != null && blocker.openable()) {
            double bDistSq = maid.distanceToSqr(blocker.pos().getX() + 0.5,
                    blocker.pos().getY() + 0.5, blocker.pos().getZ() + 0.5);
            if (bDistSq <= reachSq()) {
                // 挡路块够得着 → 切目标为挡路块，走下面正常挖掘流程
                this.targetPos = blocker.pos();
                TARGET_SINCE.put(maid.getId(), gameTime);
            } else {
                // 挡路块还够不着 → 朝挡路块推进（目标 = 挡路块脚下，不朝矿——
                // 矿在岩壁深处，以矿为终点永远走不到）
                // 实测九百三十二：这里原来挂着"2 秒没挪窝就弃置"（九百三十一 补的）——弃置已删；
                // 挡路块在头顶爬不上去由下面的搭高接管（搭不上还有 approachOre 的挖开头顶）。
                // 挡路块在她上方且够不着 → 先试着搭高抬升（与 approachOre 的 dy>=1 同一条能力；
                // 放不上去/没料返回 false，落到下面的走位兜底）
                if (blocker.pos().getY() > maid.blockPosition().getY() && this.pillarUpStep(level, maid)) {
                    maid.getNavigation().stop();
                    this.traceExit(maid, "mine 朝挡路块搭高", "blocker=" + blocker.pos()
                            + " feet=" + maid.blockPosition());
                    return;
                }
                // 实测九百三十四【搭不上就挖开头顶】：pillarUpStep 失败的头号原因是"头顶那两格是实心
                // 方块"（垫不上去）。这条"挖开头顶"原来只在 approachOre 的 dy>=1 分支里接了，挡路块
                // 这条路漏了——实机日志里她顶着 "mine 朝挡路块推进(够不着矿) path=false" 站了 15 秒不动：
                // 够不着、搭不上、又不挖头顶，只能一遍遍 stop+retarget。现在搭不上就试一次挖开正上方的方块。
                if (blocker.pos().getY() > maid.blockPosition().getY() && this.digUpCap(level, maid)) {
                    maid.getNavigation().stop();
                    return;
                }
                // 实测九百三十五【走不到就换动作】：实机日志里她顶着 "mine 朝挡路块推进(够不着矿) …
                // path=false" 站了 52 秒（周围明明空着）——因为"朝挡路块脚下走"这条路的落脚点
                // 导航给不出路径。走不到就改走 approachOre 的接近链（下沉 / 搭高 / 垫桥 / 挖开
                // 头顶都在那条链上），给她一个真能动的动作，而不是原地等下一步。
                // 实测九百三十六【别来回横跳】：旧版每拍都在"朝挡路块推进"（walkToBlockFace 把目标设到
                // 挡路块脚边）与"走不到→改走接近链"（approachOre 把目标设到矿边）之间翻——两处目标只差
                // 一格，实机就是"在一个方块里来回走动"。一旦这条挡路块走不到，就记下来、几十拍内不再试它，
                // 直接交给接近链（挖 / 垫 / 降都在那条链上，是真动作），横跳自然消失。
                if (!blocker.pos().equals(this.unreachableBlocker) || gameTime >= this.unreachableBlockerUntil) {
                    this.walkToBlockFace(level, maid, blocker.pos());
                    if (!maid.getNavigation().isDone()) {
                        this.traceExit(maid, "mine 朝挡路块推进(够不着矿)", "blocker=" + blocker.pos()
                                + " target=" + this.targetPos);
                        return;
                    }
                    this.unreachableBlocker = blocker.pos().immutable();
                    this.unreachableBlockerUntil = gameTime + MINE_BLOCKER_UNREACHABLE_TICKS;
                }
                this.approachOre(level, maid);
                this.traceExit(maid, "mine 挡路块走不到→改走接近链", "blocker=" + blocker.pos()
                        + " target=" + this.targetPos);
                return;
            }
        } else if (blocker != null
                && com.maidsmart.config.MaidSmartConfig.MINE_HARD_BLOCK_REPORT.get()) {
            // v1.5.87：硬挡路——报点（气泡+主人聊天栏）+ 弃置该矿（持续排除，不再反复选中）
            this.blockedOres.add(this.targetPos);
            this.giveUpLog(maid, "硬挡路", this.targetPos, gameTime);
            this.reportBlockedOre(maid, level, this.targetPos, blocker.pos());
            this.targetPos = null;
            this.destroyProgress = 0.0f;
            this.saveProgress(maid);
            return;
        }
        double distSq = distSqToBlock(maid, this.targetPos);
        if (distSq > reachSq()) {
            // 实测九百四十一【停滞检测】：够不着时推进链本该送她上去（垫高 / 挖开头顶 / 走位），
            // 但站位在矿下方、头顶那格刨不动、导航又无路三者同时成立时三个出口全落空 → 原地卡死
            // （见字段注释里的 17:34 实测）。同一目标 + 双脚连续 MINE_WEDGE_TICKS 拍一字未动 →
            // 认这块真够不到：当场弃置（记 30 秒短排，下一拍立刻重扫），不再一遍遍 stop+retarget
            // 同一块。连弃 3 次 = 这一框没戏 → 清缓存走"框空"迁移换框。
            BlockPos stuckFeet = maid.blockPosition();
            if (!stuckFeet.equals(this.lastStuckPos)
                    || this.stuckTarget == null || !this.stuckTarget.equals(this.targetPos)) {
                this.lastStuckPos = stuckFeet;
                this.stuckTicks = 0;
                this.stuckTarget = this.targetPos;
            } else {
                this.stuckTicks++;
            }
            if (this.stuckTicks >= MINE_WEDGE_TICKS) {
                this.wedgeStreak = gameTime - this.lastWedgeAt <= 200L ? this.wedgeStreak + 1 : 1;
                this.lastWedgeAt = gameTime;
                this.giveUpLog(maid, "够不着且没挪窝", this.targetPos, gameTime);
                RECENT_DISCARD.computeIfAbsent(maid.getId(), k -> new java.util.HashMap<>())
                        .put(this.targetPos.immutable(), gameTime);
                this.targetPos = null;
                this.destroyProgress = 0.0f;
                this.lastWalkTarget = null;
                this.scanCooldown = 0;   // 下一拍立刻重扫
                this.saveProgressNow(maid);
                if (this.wedgeStreak >= 3) {
                    this.wedgeStreak = 0;
                    ORE_CACHE.remove(maid.getId());
                    MINE_SCANS.remove(maid.getId());
                    LOGGER.info("mine box unreachable (3 连弃置、零破坏) → fresh box: maid={} anchor={}",
                            maid.getUUID(), anchor);
                    this.slideAnchor(level, maid, anchor);
                }
                return;
            }
            // 实测九百二十七【咬住不放】：够不着就每 tick 继续推进（approachOre 下走位 / 垫路指令 /
            // 挖开头顶）。
            this.traceExit(maid, "mine 够不着-继续推进", "target=" + this.targetPos
                    + " dist=" + (long) Math.sqrt(distSq) + " reach=" + (long) Math.sqrt(reachSq()));
            this.approachOre(level, maid);
            return;
        }
        ItemStack mainHand = maid.getMainHandItem();
        if (mainHand.isEmpty() || !(mainHand.getItem() instanceof PickaxeItem)) {
            // v1.5.113（C3）：主手镐没了（碎裂/被换走）且背包也没有能挖的镐 →
            // 立即播报并弃目标（不用等 15 秒超时；有备用镐时 ensureForTarget 已换好）
            if (!MaidToolAutoEquip.canHarvestWithHandOrBackpack(maid, level.getBlockState(this.targetPos))) {
                this.recordSkippedOre(maid, this.targetPos, level.getBlockState(this.targetPos));
                this.reportSkippedOre(maid, gameTime);
                // v1.5.161：主手镐没了且背包装备不上 → 连锁队列一并清空
                this.chainQueue.clear();
                this.chainBlock = null;
                this.targetPos = null;
                this.destroyProgress = 0.0f;
                this.saveProgressNow(maid);
            }
            this.traceExit(maid, "mine 主手不是镐(暂停挖掘)", "hand=" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(mainHand.getItem())
                    + " 背包能挖=" + MaidToolAutoEquip.canHarvestWithHandOrBackpack(maid, level.getBlockState(this.targetPos)));
            return; // 镐被换走/碎裂，暂停挖掘
        }
        // v1.5.19 渐进挖掘；v1.5.22 重写——直接公式，不依赖 FakePlayer
        // （旧版依赖 FakePlayer.getDestroyProgress，假玩家状态异常时 delta=0 → 永远挖不完）
        BlockState state = level.getBlockState(this.targetPos);
        float hardness = state.getDestroySpeed(level, this.targetPos);   // 1.21.1 硬度正解（旧写 getShadeBrightness = 亮度，不是硬度）
        if (hardness <= 0.0f || hardness == Float.MAX_VALUE) {
            this.traceExit(maid, "mine 硬度≤0/不可破坏", "pos=" + this.targetPos + " block=" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(this.targetPos).getBlock())
                    + " hardness=" + hardness);
            this.targetPos = null; // 不可破坏，换目标
            this.destroyProgress = 0.0f;
            this.saveProgress(maid);
            return;
        }
        // v1.5.24：完全对齐玩家公式（Player.getDestroyProgress）——
        // 1. 正确工具判定：石镐挖钻石矿（需铁镐）→ 除数 100 慢 3.3 倍，与玩家一致
        // 2. 效率附魔加成：效率 I = +2、效率 V = +26，与玩家 getDigSpeed 相同
        // 3. 女仆整体 ×1.2（人机智商不够，用数值补：比玩家稍快）
        boolean correct = mainHand.getItem() instanceof net.minecraft.world.item.DiggerItem
                && mainHand.getItem().isCorrectToolForDrops(mainHand, state);
        float digSpeed = Math.max(1.0f, mainHand.getDestroySpeed(state));
        net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment> eff =
                maid.level().registryAccess()
                        .registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                        .getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.EFFICIENCY);
        if (eff != null && digSpeed > 1.0f) {
            int effLevel = net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(eff, mainHand);
            if (effLevel > 0) {
                digSpeed += effLevel * effLevel + 1;
            }
        }
        // 玩家同款公式：每 tick 进度 = digSpeed / hardness / 除数（正确工具 30，不合适 100）
                    // v1.5.88：挖矿速度系数从配置面板读取（mine.speedFactor，默认 1.2）
                    float delta = digSpeed / hardness / (correct ? 30.0f : 100.0f)
                            * (float) (double) com.maidsmart.config.MaidSmartConfig.MINE_SPEED_FACTOR.get();
                    // v1.5.102e：单 tick 进度封顶 0.25——高等级镐+效率附魔会让石头/泥土
                    // 1~2 tick"秒破"（Efficiency V 下 delta≈0.93）。封顶后任何方块至少
                    // 4 tick（0.2 秒）挖完，配合裂纹动画保留渐进破坏观感
                    delta = Math.min(delta, 0.25f);
        maid.swing(InteractionHand.MAIN_HAND, true); // 每 tick 挥镐（挖掘动作）
        this.destroyProgress += delta;
        this.saveProgress(maid); // v1.5.22：进度持久化，行为重启不丢
        // v1.5.102e：客户端显示挖掘裂纹（0~9 阶段）——女仆不是玩家，原版不会自动发
        // 破坏进度包，方块一直无裂纹到瞬间消失 = "秒破"观感。每 tick 广播裂纹阶段
        this.broadcastCrack(level, maid, this.targetPos,
                Math.max(0, Math.min(9, (int) (this.destroyProgress * 10.0f))));
        if (this.destroyProgress < 1.0f) {
            this.traceExit(maid, "mine 挖掘中(健康)", "pos=" + this.targetPos + " block=" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(this.targetPos).getBlock())
                    + " progress=" + this.destroyProgress);
            return; // 还在挖掘中，下一 tick 继续
        }
        // 实测二百二十四：挖掉前登记是不是矿（setBlock 换成空气后就判不了了）
        boolean dugOre = this.isOre(level, this.targetPos);
        // 挖完：清除裂纹（stage 10 = 移除）再破坏方块
        this.broadcastCrack(level, maid, this.targetPos, 10);
        // 挖完：玩家同款破坏音效+粒子（levelEvent 2001），再掉落实体方块
        level.levelEvent(2001, this.targetPos, Block.getId(state));
        BlockEntity be = level.getBlockEntity(this.targetPos);
        // v1.5.161：自动收集（mine.autoCollect，默认关闭）——掉落物不进世界，
        // 直接塞进女仆背包（放不下才落地）；getDrops 六参版（getDrops）与
        // dropResources 同款精准/时运判定。关闭时保持原逻辑（落地 + 即时回收）
        if (com.maidsmart.config.MaidSmartConfig.MINE_AUTO_COLLECT.get()) {
            java.util.List<ItemStack> drops = Block.getDrops(state, level, this.targetPos, be, maid, mainHand);
            insertIntoMaidInventory(maid, level, drops, this.targetPos);
            // v1.5.164：诊断日志（排查"自动收集没生效"）
            LOGGER.info("mine autoCollect: maid={} pos={} drops={}",
                    maid.getUUID(), this.targetPos, drops == null ? 0 : drops.size());
        } else {
            // v1.5.85：掉落物+经验球走完整 dropResources（六参版）——内部做精准采集检查，
            // 精准采集镐挖矿不掉经验球，与原版玩家完全一致（旧 4 参版只掉物品不掉经验）
            Block.dropResources(state, level, this.targetPos, be, maid, mainHand);
            // v1.5.87：挖出的矿石掉落物即时回收（原地拾取，不走"走过去捡"）——
            // 拾取任务在挖矿中已让位，这里直接把刚挖出的掉落物收进背包，不掉地上
            this.pickupOreDrops(level, maid);
        }
        level.setBlock(this.targetPos, Blocks.AIR.defaultBlockState(), 3);
        // v1.5.85：对齐原版——镐子扣耐久（hurtAndBreak；归零碎裂：广播破坏事件
        // 客户端自动播碎裂音效 + 移除碎裂的镐）
        // v1.5.87：只扣"需要工具"的方块（矿/石头）——徒手可挖软方块开路不磨损镐子
        // v1.5.88：软方块不耗耐久可配置（mine.softNoDurability）
        // v1.5.102e：修复耐久扣错对象——旧版在背包里找"同类型的镐"扣耐久/清除，
        // 但主手是独立手部栏（getHandsInvWrapper），背包刚好有同款镐时会扣背包那把、
        // 主手那把永远不磨损（v1.5.90 改手部栏后残留的旧逻辑）。现在直接扣主手那把，
        // 碎裂清主手槽（下一 tick 自动换装备用镐）。
        boolean softNoDur = com.maidsmart.config.MaidSmartConfig.MINE_SOFT_NO_DURABILITY.get();
        if (!mainHand.isEmpty() && (!softNoDur || !isSoft(mainHand, state))) {
            final ItemStack damaged = mainHand;
            com.maidsmart.task.MaidToolAutoEquip.recordDurabilityBreak(maid, damaged);
            damaged.hurtAndBreak(1, maid, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
                maid.swing(InteractionHand.MAIN_HAND);
        }
        // v1.5.87：硬挡排除清除（开路改变了地形，阻挡可能已解除）
        this.blockedOres.clear();
        // v1.5.25h：挖到矿后不再主动清理/下柱（旧 cleanupStep/descendStep 已移除）——
        // 搭的方块统一由 expirePlacedBlocks 在放置 10 秒后自动销毁变掉落物回收。
        // 女仆直接找下一个矿继续挖；站在柱子上的话等方块自然消失（掉落物回收）
        // v1.5.161：连锁采集——记录刚挖掉的方块类型并 BFS 填充连锁队列（队列里的
        // 同族矿挖完一块自动接下一块，直到挖完矿脉）；开关关闭时清空队列走正常找矿
        // 实测二百二十四：连锁扩展到障碍物——挖开的挡路块（同类可挖穿）同样连锁，
        // 与矿脉共用开关与上限（mine.chainMining / mine.chainLimit）
        this.traceExit(maid, "mine 挖掉一块", "pos=" + this.targetPos + " block=" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock())
                + " ore=" + dugOre);
        this.chainBlock = state.getBlock();
        if (dugOre) {
            this.refillChainQueue(level, maid);
            // v1.5.172：连锁采集【同时破坏】（FTB Ultimine 式）——目标矿挖完瞬间把
            // 队列里相连的同族矿一次性全部破坏（掉落直接进背包），视觉上一挖一串；
            // 不再逐个挖（旧版"自动连挖"看不出连锁效果，反馈）
            this.chainBreakAll(level, maid, mainHand);
        } else if (isOpenStoneBlock(state)) {
            this.refillObstacleChainQueue(level, maid);
            this.obstacleChainBreakAll(level, maid, mainHand);
        }
        this.targetPos = null;
        this.destroyProgress = 0.0f;
        this.saveProgressNow(maid);
    }

    /**
     * 一次性全部破坏：掉落物直接进女仆背包（放不下落地，与单块自动收集一致），
     * 音效/粒子只在目标矿播（连锁块静默，防 16 连爆音刷屏）。
     * <p>【耐久口径】历史上"镐耐久只扣目标矿一次"（本方法不扣，由单块那一处扣 1 点）。
     * 打开后这里**每破坏一块也扣 1 点**（并沿用单块那套"碎裂清主手槽"回调），关着时一字不动。
     * 破坏后队列自然清空（下一 tick 取队列时 isOre 校验失败被 poll 掉，不残留）。
     */
    private void chainBreakAll(ServerLevel level, EntityMaid maid, ItemStack mainHand) {
        if (!com.maidsmart.config.MaidSmartConfig.MINE_CHAIN_MINING.get()
                || this.chainBlock == null) {
            this.chainQueue.clear();
            return;
        }
        boolean fullDur = com.maidsmart.config.MaidSmartConfig.MINE_CHAIN_FULL_DURABILITY.get();
        int broken = 0;
        int limit = com.maidsmart.config.MaidSmartConfig.MINE_CHAIN_LIMIT.get();
        while (!this.chainQueue.isEmpty() && broken < limit) {
            BlockPos pos = this.chainQueue.poll();
            if (pos == null || pos.equals(this.targetPos)) {
                continue;
            }
            BlockState st = level.getBlockState(pos);
            if (st.isAir() || st.getBlock() != this.chainBlock || !this.isOre(level, pos)) {
                continue; // 已被挖掉/类型不符（队列里的过期矿位）
            }
            if (isMiningPlaced(level, pos)) {
                continue; // 自己搭的方块不连锁
            }
            BlockEntity be = level.getBlockEntity(pos);
            java.util.List<ItemStack> drops = Block.getDrops(st, level, pos, be, maid, mainHand);
            insertIntoMaidInventory(maid, level, drops, pos);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            broken++;
            // 实测七百一十七：开关打开时连锁块也逐块扣耐久（碎裂即停，别拿碎镐继续连锁）
            if (fullDur && damageMainHandForChain(maid, mainHand)) {
                break;
            }
        }
        if (broken > 0) {
            LOGGER.info("mine chain burst: maid={} block={} broken={}",
                    maid.getUUID(), net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(this.chainBlock), broken);
        }
    }

    /**
     * v1.3.0(beta) 实测七百一十七【issue #29】：连锁块逐块扣耐久（只在开关打开时调用）。
     *
     * <p>沿用单块挖掘那一处完全相同的口径（{@code mainHand} 主手那把、
     * {@code hurtAndBreak} 归零时广播破坏事件 + 清主手槽，下一 tick 自动换装备用镐）。
     * 返回 true = 这一下把镐挖碎了（调用方应停止本次连锁，避免拿碎镐继续）。
     */
    private static boolean damageMainHandForChain(EntityMaid maid, ItemStack mainHand) {
        if (mainHand == null || mainHand.isEmpty()) {
            return true; // 已经空手（上一块挖碎了）→ 别继续
        }
        final ItemStack damaged = mainHand;
        com.maidsmart.task.MaidToolAutoEquip.recordDurabilityBreak(maid, damaged);
        damaged.hurtAndBreak(1, maid, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
        return damaged.isEmpty();
    }

    /**
     * 目标够不着时的接近策略（每 tick 重算，垫一块就重新评估，直到够得着）：
     * ① 高度优先——dy≥1 原地搭高、dy≤−1 逐级下降，**先把高度对齐**；
     * ② 高度一致（dy==0）后再水平靠近——目标方向前方脚下悬空（断崖/水）先搭桥，否则走过去；
     * ③ 高度一致且水平进了可交互圈 → 原地不动，交给挖掘入口。
     * 搭高失败（没料/头顶堵）不立刻放弃——退到"走过去"兜底，走不通由导航处理。
     */
    private void approachOre(ServerLevel level, EntityMaid maid) {
        // v1.1.0 实测一百九十二【垫块后走上去】——步进推送消费本 tick（不导航不垫块）；
        // 旧版垫完靠 walkToStep 寻路：跨沟/断崖半路折断 → 女仆踩不上刚垫的方块，
        // 在某几格上死循环（反馈："运动的幅度真的太小了"）
        if (BlockWalkOn.tick(maid)) {
            return;
        }
        BlockPos t = this.targetPos;
        double hx = t.getX() + 0.5 - maid.getX();
        double hz = t.getZ() + 0.5 - maid.getZ();
        double hDist = Math.sqrt(hx * hx + hz * hz);
        int dy = t.getY() - maid.blockPosition().getY();
        double close = Math.min(2.5, Math.max(1.0, Math.sqrt(reachSq()) - 0.5));
        // 实测九百一十五【到位就停】高度已一致 + 水平进了可交互圈 → 不再走/垫，交给挖掘入口。
        // 旧版没有这道闸：她在目标轴附近一抖，方向单位量就翻号 → 朝【身后】垫块再走回去，
        // 两个位置反复横跳（反馈的"迈克尔杰克逊步"）。
        if (dy == 0 && hDist <= close && this.hasClearSight(level, maid, t)) {
            return;
        }
        // v1.5.111：搭高【站桩】——停掉直接导航（v1.5.127：旧版清 WALK_TARGET 记忆，
        // 现在改为停导航，语义一致：原地站定让 pillarGuard 钳位，垂直列干净成型）
        if (dy >= 1) {
            if (this.pillarUpStep(level, maid)) {
                maid.getNavigation().stop();
                return;
            }
            // 实测九百三十二【垫不上去就先挖开头顶】：pillarUpStep 失败的头号原因是"头顶那两格
            // 是实心会窒息的方块"（她在矿洞里、上面就是岩层）。她有镐 → 把脚下正上方第一块可挖开
            // 的方块挖掉，下一拍头顶空了、垫高自然成立（一格一格开天窗往上走）。
            // 这一步是"删掉弃置"的前提：矿在她上方、头顶又是岩石时，旧链路只剩"走过去"（落脚点
            // 在岩层里、导航必失败）→ 她会永久站住；有了它，"够不着"永远有动作可做。
            if (this.digUpCap(level, maid)) {
                return;
            }
            // 实测九百一十六【套用伐木的搭高失败兜底】：垫不上去（没料/头顶堵）时改把目标换成
            // 头顶正上方第一块矿（下一 tick 走正常挖掘入口——够得着直接挖掉、逐节往上啃完整列，
            // 不需要垫方块）；水平偏开 >1 格就先走到矿柱底最近可站格（沿柱向下找第一个可站空气），
            // 走位到正下方后挖掘入口自然接管。旧版这里直接跌进 walkToOreBase → 目标可能是
            // 墙里/悬空的格 → 走到垃圾目标 = 发呆。
            BlockPos above = this.firstOreAbove(level, maid);
            if (above != null) {
                if (!above.equals(t)) {
                    this.targetPos = above;
                    TARGET_SINCE.put(maid.getId(), level.getGameTime());
                    return;
                }
                double hx2 = t.getX() + 0.5 - maid.getX();
                double hz2 = t.getZ() + 0.5 - maid.getZ();
                if (Math.sqrt(hx2 * hx2 + hz2 * hz2) > 1.0) {
                    BlockPos stand = null;
                    for (int dy2 = -1; dy2 >= -10; dy2--) {
                        BlockPos c = t.offset(0, dy2, 0);
                        if (level.getBlockState(c).isAir()) {
                            BlockPos uc = c.offset(0, -1, 0);
                            if (!level.getBlockState(uc).isAir()
                                    && level.getBlockState(uc).isRedstoneConductor(level, uc)) {
                                stand = c;
                            }
                            break;
                        }
                    }
                    if (stand == null) {
                        this.walkToOreBase(level, maid, t);
                    } else {
                        this.setWalkTarget(maid, stand, approachSpeed(maid, t));
                    }
                }
                return;
            }
        }
        // 3) v1.1.0【下方矿逐级下降】——目标比自身低时，向下垫一格并踩下去，
        // 逐级降低高度接近矿物（旧版只能垫平/垫高，y 永不下降）
        if (dy <= -1) {
            if (this.descendStep(level, maid, hx, hz, hDist)) {
                if (!BlockWalkOn.isActive(maid)) {
                    this.startStepOn(maid, hx, hz, hDist, -1);
                }
                return;
            }
            // 下不去（下面有地可走 / 危险 / 没料）→ 走位兜底；绝不再走到 bridgeToOre
            //（它会在下降通道垫平块把高度顶住，反而卡死无法下降）
            if (this.walkRetargetCooldown > 0) {
                this.walkRetargetCooldown--;
            } else {
                this.walkRetargetCooldown = 10;
                this.walkToOreBase(level, maid, t);
            }
            return;
        }
        // 4) 其他：目标方向前方脚下悬空 → 搭桥；否则走过去
        // v1.5.113：搭桥后【走一步上桥】——旧版搭完直接 return（无移动目标）
        // → 女仆站在桥头"搭一格就站着不动"根因
        if (this.bridgeToOre(level, maid, hx, hz, hDist)) {
            if (!BlockWalkOn.isActive(maid)) {
                this.startStepOn(maid, hx, hz, hDist);
            }
            return;
        }
        // v1.5.87：走路目标 = 矿基座（矿下方 1 格）——旧版目标是矿本身（常在空中/岩壁内），
        // 导航走不到 → 女仆站在远处不动（"欲望太低"根因）；改为可达位置后她会持续贴近矿物
        // v1.5.105：重设节流——单纯"走过去"时每 30 tick 才尝试重设一次 WalkTarget，
        // 避免每 tick 重设让 TLM 每 tick 重寻路（移动顿挫）；目标没变（±1 容差）时
        // setWalkTarget 直接跳过（v1.5.116/121），此节流只是兜底下限；搭高/斜坡
        // 成功后的即时重设（垫一步走一步）不受影响
        if (this.walkRetargetCooldown > 0) {
            this.walkRetargetCooldown--;
        } else {
            // v1.5.124：重设节流 30 → 10 tick——旧版 1.5 秒才重试一次目标，
            // 撞上 MoveToTargetSink 的失败冷却（最多 40 tick）时女仆原地愣 2~3 秒；
            // 10 tick 后重试，setWalkTarget 内部对"目标未变且导航行进中"本就跳过
            // （不重新寻路），节流只是兜底，调低响应更快、不会增加寻路负担
            this.walkRetargetCooldown = 10;
            this.walkToOreBase(level, maid, t);
        }
    }

    /**
     * v1.5.87：朝矿基座推进——搭高/垫台阶成功后立刻走，让女仆在水平方向持续贴近矿物。
     * v1.5.113（B5/C1）：站立点改为【矿 6 方向最近的可站空气格】——旧版只向上找
     * "矿正上方空气格"，岩壁深处最近空气在矿上方 5+ 格 → 女仆走到够不着的地方空转；
     * 且接近速度两档（远快走近慢走，防搭高漂移）。
     */
    private void walkToOreBase(ServerLevel level, EntityMaid maid, BlockPos t) {
        BlockPos stand = findStandNearOre(level, maid, t);
        if (stand == null) {
            // 实测九百一十六【套用伐木】：找不到立足点就【直接朝矿坐标走】——旧版"不设移动目标"
            // = 站桩干等的来源之一；走过去后挡路挖掘 / 搭高接管
            stand = t;
        }
        this.setWalkTarget(maid, stand, approachSpeed(maid, t));
    }

    /** 实测九百一十六【套用伐木的抬头兜底】：脚下正上方同柱还有矿吗——扫描框/排除表都给不出目标、
     *  或搭高失败时，先抬头看一眼头顶同一柱的矿，有就直接当目标（挖穿挡路块 + 逐节往上啃，不必垫
     *  方块）。被硬挡路弃置的（{@code blockedOres}）不回选，防"抬头选中→硬挡弃置→再抬头"死循环。 */
    private BlockPos firstOreAbove(ServerLevel level, EntityMaid maid) {
        BlockPos feet = maid.blockPosition();
        int[][] colOrder = {{0, 0},
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {-1, 1}, {1, -1}, {-1, -1},
                {2, 0}, {-2, 0}, {0, 2}, {0, -2},
                {2, 1}, {1, 2}, {-1, 2}, {-2, 1}, {-2, -1}, {-1, -2}, {1, -2}, {2, -1},
                {2, 2}, {-2, 2}, {-2, -2}, {2, -2}};
        for (int[] off : colOrder) {
            for (int dy = 1; dy <= 10; dy++) {
                BlockPos p = feet.offset(off[0], dy, off[1]);
                if (this.isOre(level, p) && !isMiningPlaced(level, p)
                        && !this.blockedOres.contains(p)) {
                    return p;
                }
            }
        }
        return null;
    }

    /**
     * v1.5.113（B5）：找矿附近最近的【可站立格】——候选 = 矿 6 方向各 1 格 + 矿下方 2 格，
     * 要求：候选格可呼吸（空气）、其脚下（候选-1）实心可站、且离女仆最近（走过去最近）。
     * 找不到（矿被完全包住）返回 null（挡路挖掘接管）。
     */
    private BlockPos findStandNearOre(ServerLevel level, EntityMaid maid, BlockPos t) {
        // 实测八百六十八：候选扩到 ±2 外圈 + 斜角（对齐伐木的 15 个候选）——旧版只有 6 方向 ±1 +
        // 下方 2 格，稍宽一点的矿洞/岩壁就选不出立足点 → 不给移动目标 → 站着不动
        int[][] offsets = {{0, -1, 0}, {0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -2, 0},
                {2, 0, 0}, {-2, 0, 0}, {0, 0, 2}, {0, 0, -2},
                {1, 0, 1}, {1, 0, -1}, {-1, 0, 1}, {-1, 0, -1}};
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int[] o : offsets) {
            BlockPos cand = t.offset(o[0], o[1], o[2]);
            if (!level.getBlockState(cand).isAir()) {
                continue; // 候选格不是空气
            }
            BlockPos under = cand.offset(0, -1, 0);
            BlockState underState = level.getBlockState(under);
            if (underState.isAir() || !underState.isRedstoneConductor(level, under)) {
                continue; // 脚下无支撑（悬空）
            }
            double d = maid.distanceToSqr(cand.getX() + 0.5, cand.getY() + 0.5, cand.getZ() + 0.5);
            if (d < bestD) {
                bestD = d;
                best = cand;
            }
        }
        if (best != null) {
            return best;
        }
        // 实测九百一十六【套用伐木】：删掉"矿正上方向上的最近空气"兜底——岩壁里矿上方那格是
        // 【导航去不了的悬空空气】（无支撑），设为移动目标后寻路必失败 → 女仆站着发呆；而且旧版
        // 末尾无条件 `return t` 把矿本身当移动目标（也在墙里/悬空）→ 同样走不到。改返回 null：
        // walkToOreBase 对 null 的处理是直接朝矿坐标走（走到跟前，够得着就挖、够不着搭高接管）。
        return null;
    }

    /**
     * v1.5.113：搭路后【走一步】——目标 = 前方 1 格（刚垫桥/台阶的位置，脚下已有
     * 支撑），走过去后下一 tick 再评估下一块。修复"搭一格就站着不动"。
     */
    private void walkToStep(ServerLevel level, EntityMaid maid, double hx, double hz, double hDist) {
        int y = maid.blockPosition().getY();
        int tx = (int) Math.floor(maid.getX() + hx / hDist);
        int tz = (int) Math.floor(maid.getZ() + hz / hDist);
        BlockPos stand = new BlockPos(tx, y, tz);
        if (!level.getBlockState(stand).isAir()) {
            stand = stand.offset(0, 1, 0); // 前方格被占用（方块顶起）→ 站上一格
        }
        this.setWalkTarget(maid, stand, approachSpeed(maid, this.targetPos));
    }

    /**
     * v1.5.113（C1）：两档接近速度——矿远（>8 格）1.5× 快走赶路，矿近（≤3 格）
     * 0.8× 慢走（准备搭高/挖掘，防冲过头漂移）；中间距离用基础速度（mine.moveSpeed）。
     * 实测八百二十八：远处赶路加成 1.5× → 1.0×（，
     * 要求本 mod 不再给女仆额外速度加成）。近处 0.8× 的减速保留（防冲过头）。
     */
    private float approachSpeed(EntityMaid maid, BlockPos t) {
        if (t == null) {
            return (float) (double) com.maidsmart.config.MaidSmartConfig.MINE_MOVE_SPEED.get();
        }
        double d = maid.distanceToSqr(t.getX() + 0.5, t.getY() + 0.5, t.getZ() + 0.5);
        float base = (float) (double) com.maidsmart.config.MaidSmartConfig.MINE_MOVE_SPEED.get();
        if (d > 8.0) {
            return base; // 实测八百二十八：不再 ×1.5 加速
        }
        if (d < 3.0) {
            return base * 0.8f;
        }
        return base;
    }

    /**
     * v1.5.90：朝挡路块推进（穿透挖掘用）——目标是挡路块"脚下 1 格"（与挡路块
     * 同柱的地面），女仆走到墙根后挡路块就在伸手范围内 → 下一 tick 开始挖穿。
     * 与 walkToOreBase 的区别：挡路块脚下通常是实心地基，不需要"找上方空气站到
     * 矿正上方往下挖"（那是埋地矿专用的）；脚下悬空才退回 walkToOreBase 兜底。
     */
    private void walkToBlockFace(ServerLevel level, EntityMaid maid, BlockPos b) {
        // 实测九百三十六【"支撑"取错格】：旧版把 stand（她要站的那一格）当"脚下"去查——stand 本身
        //   就该是空气，于是"是空气 → 退回 walkToOreBase"这条闸几乎必中（与 approachOre 抢目标、
        //   来回横跳）；少数 stand 恰是实心时又被指向"方块里面"、导航给不出路径。正确判据 =
        //   站位是空气 ∧ 脚下那一格实心。
        BlockPos stand = new BlockPos(b.getX(), b.getY() - 1, b.getZ());
        BlockPos support = stand.below();
        BlockState at = level.getBlockState(stand);
        BlockState under = level.getBlockState(support);
        if (!at.isAir() || under.isAir() || !under.isRedstoneConductor(level, support)) {
            this.walkToOreBase(level, maid, b);
            return;
        }
        this.setWalkTarget(maid, stand, approachSpeed(maid, b));
    }

    /**
     * v1.5.87：挖出的掉落物即时回收——拾取任务在挖矿中已让位（最低优先级），
     * 这里把刚挖出（生成在挖矿格附近）的掉落物原地收进背包，不掉地上、不走"走过去捡"。
     */
    private void pickupOreDrops(ServerLevel level, EntityMaid maid) {
        BlockPos p = this.targetPos;
        if (p == null) {
            return;
        }
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(p).inflate(1.5);
        for (net.minecraft.world.entity.item.ItemEntity e :
                level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box)) {
            if (e.isAlive()) {
                maid.pickupItem(e, false);
            }
        }
    }

    /**
     * 匹配规则（借鉴 FTB Ultimine 的 BlockMatcher：同 Block 判定）：
     * - 方块类型与刚挖掉的矿相同（chainBlock）
     * - 在矿表内（isOre，含自定义矿表）——只连锁矿，不连锁石头/泥土
     * - 不是女仆自己搭的方块（isMiningPlaced 排除）
     * 队列上限从配置面板读取（mine.chainLimit，默认 16）；BFS 展开上限
     * 64 防超大矿脉卡顿。开关关闭时直接清空。
     */
    private void refillChainQueue(ServerLevel level, EntityMaid maid) {
        if (!com.maidsmart.config.MaidSmartConfig.MINE_CHAIN_MINING.get()
                || this.chainBlock == null) {
            this.chainQueue.clear();
            this.chainBlock = null;
            return;
        }
        if (!this.chainQueue.isEmpty()) {
            return; // 队列还有货，挖完再补
        }
        int limit = com.maidsmart.config.MaidSmartConfig.MINE_CHAIN_LIMIT.get();
        BlockPos start = this.targetPos;
        java.util.Set<BlockPos> visited = new java.util.HashSet<>();
        java.util.ArrayDeque<BlockPos> bfs = new java.util.ArrayDeque<>();
        bfs.add(start.immutable());
        visited.add(start.immutable());
        while (!bfs.isEmpty() && this.chainQueue.size() < limit) {
            BlockPos cur = bfs.poll();
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                net.minecraft.core.Vec3i step = d.getNormal();
                BlockPos nb = cur.offset(step.getX(), step.getY(), step.getZ());
                if (!visited.add(nb.immutable())) {
                    continue;
                }
                BlockState ns = level.getBlockState(nb);
                if (ns.isAir() || ns.getBlock() != this.chainBlock) {
                    continue;
                }
                if (!this.isOre(level, nb)) {
                    continue; // 只连锁矿脉，不连锁石头
                }
                if (isMiningPlaced(level, nb)) {
                    continue; // 自己搭的方块不连锁
                }
                if (this.chainQueue.size() < limit) {
                    this.chainQueue.add(nb.immutable());
                }
                if (bfs.size() < 64) {
                    bfs.add(nb.immutable());
                }
            }
        }
        // v1.5.164：诊断日志（排查"连锁采集没生效"）——每次新矿脉填充打一次
        if (!this.chainQueue.isEmpty()) {
            LOGGER.info("mine chain filled: maid={} block={} queued={} limit={}",
                    maid.getUUID(),
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(this.chainBlock),
                    this.chainQueue.size(), limit);
        } else if (com.maidsmart.config.MaidSmartConfig.MINE_CHAIN_MINING.get()) {
            LOGGER.info("mine chain empty: maid={} block={} (no adjacent ore)",
                    maid.getUUID(), net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(this.chainBlock));
        }
    }

    /**
     * 实测二百二十四：连锁采集扩展到障碍物——从刚挖掉的挡路块 BFS（6 方向）找相连的
     * 同类可挖穿块排队（反馈："挖一个旁边的障碍物，周围的同类障碍物也生效；要有触发
     * 上限"）。匹配规则与矿脉一致：同 Block + isBreakable（内置+面板名单，扣除排除名单）
     * + 不是女仆自己搭的方块 + 不是矿（矿走矿脉链路，不误连锁）。上限共用
     * mine.chainLimit（默认 16）；BFS 展开上限 64；开关关闭时直接清空。
     */
    private void refillObstacleChainQueue(ServerLevel level, EntityMaid maid) {
        if (!com.maidsmart.config.MaidSmartConfig.MINE_CHAIN_MINING.get()
                || this.chainBlock == null) {
            this.chainQueue.clear();
            this.chainBlock = null;
            return;
        }
        if (!this.chainQueue.isEmpty()) {
            return;
        }
        int limit = com.maidsmart.config.MaidSmartConfig.MINE_CHAIN_LIMIT.get();
        BlockPos start = this.targetPos;
        java.util.Set<BlockPos> visited = new java.util.HashSet<>();
        java.util.ArrayDeque<BlockPos> bfs = new java.util.ArrayDeque<>();
        bfs.add(start.immutable());
        visited.add(start.immutable());
        while (!bfs.isEmpty() && this.chainQueue.size() < limit) {
            BlockPos cur = bfs.poll();
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                net.minecraft.core.Vec3i step = d.getNormal();
                BlockPos nb = cur.offset(step.getX(), step.getY(), step.getZ());
                if (!visited.add(nb.immutable())) {
                    continue;
                }
                BlockState ns = level.getBlockState(nb);
                if (ns.isAir() || ns.getBlock() != this.chainBlock) {
                    continue;
                }
                if (!this.isOpenStone(level, nb)) {
                    continue; // 只连锁可挖穿的障碍物（同类石头/泥土/圆石等）
                }
                if (this.isOre(level, nb)) {
                    continue; // 矿归矿脉连锁，不混入障碍物链
                }
                if (isMiningPlaced(level, nb)) {
                    continue; // 自己搭的方块不连锁
                }
                if (this.chainQueue.size() < limit) {
                    this.chainQueue.add(nb.immutable());
                }
                if (bfs.size() < 64) {
                    bfs.add(nb.immutable());
                }
            }
        }
        if (!this.chainQueue.isEmpty()) {
            LOGGER.info("mine chain filled (blocking): maid={} block={} queued={} limit={}",
                    maid.getUUID(),
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(this.chainBlock),
                    this.chainQueue.size(), limit);
        }
    }

    /** 实测二百二十四：障碍物连锁【同时破坏】——与 chainBreakAll 同款：掉落直接进
     *  背包（放不下落地）、连锁块静默。耐久口径与 chainBreakAll 一致：历史"只由主目标扣
     *  一次"，{@code mine.chainFullDurability} 打开后逐块扣（实测七百一十七·issue #29）。 */
    private void obstacleChainBreakAll(ServerLevel level, EntityMaid maid, ItemStack mainHand) {
        if (!com.maidsmart.config.MaidSmartConfig.MINE_CHAIN_MINING.get()
                || this.chainBlock == null) {
            this.chainQueue.clear();
            return;
        }
        boolean fullDur = com.maidsmart.config.MaidSmartConfig.MINE_CHAIN_FULL_DURABILITY.get();
        int broken = 0;
        int limit = com.maidsmart.config.MaidSmartConfig.MINE_CHAIN_LIMIT.get();
        while (!this.chainQueue.isEmpty() && broken < limit) {
            BlockPos pos = this.chainQueue.poll();
            if (pos == null || pos.equals(this.targetPos)) {
                continue;
            }
            BlockState st = level.getBlockState(pos);
            if (st.isAir() || st.getBlock() != this.chainBlock) {
                continue; // 已被挖掉/类型不符
            }
            if (!this.isOpenStone(level, pos)) {
                continue;
            }
            if (isMiningPlaced(level, pos)) {
                continue;
            }
            BlockEntity be = level.getBlockEntity(pos);
            java.util.List<ItemStack> drops = Block.getDrops(st, level, pos, be, maid, mainHand);
            insertIntoMaidInventory(maid, level, drops, pos);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            broken++;
            if (fullDur && damageMainHandForChain(maid, mainHand)) {
                break;
            }
        }
        if (broken > 0) {
            LOGGER.info("mine chain burst (blocking): maid={} block={} broken={}",
                    maid.getUUID(), net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(this.chainBlock), broken);
        }
    }

    /**
     * v1.5.25 搭高一步：往脚下放方块把自己垫高。
     * v1.5.24 头顶检查防窒息；v1.5.25 放块位置【脚下格优先】（脚下悬空垫脚下，
     * 人自然站上去不夹人；脚下实心才放所在格顶起），头顶 1 格检查放宽——
     * 目标矿在正上方也不误判为"障碍"（旧版把要挖的矿当障碍直接放弃 → 成功率低）。
     */
    private boolean pillarUpStep(ServerLevel level, EntityMaid maid) {
        // v1.1.0 实测一百五十六：骑乘中（扫帚等载具）不搭方块——移动由载具控制，
        // 垫方块只会留一堆残渣（反馈："和女仆在扫帚上挖矿时女仆会搭方块"）
        if (com.maidsmart.tool.MaidPlaceGuard.seatedBlocked(maid)) {
            return false;
        }
        if (this.pillarCooldown > 0) {
            this.pillarCooldown--;
            return true; // 冷却中：下 tick 再垫（v1.5.113 B6：成功放置才设冷却）
        }
        BlockPos pos = maid.blockPosition();
        // 实测九百五十八【垫块只盖下半身】：放置格一律取她【脚下那一格】——方块最多盖住
        // 下半身（腿/脚），下一拍被游戏顶起一格升高。旧版在"脚下那格不是空气"（站在水里/
        // 树叶里/雪层上）时改垫 pos+1 = 她的上半身/脑袋，顶起稍有滞后就是自己把自己闷住
        //（反馈："垫方块可以覆盖到自己的脑袋→自己把自己弄窒息了"）。
        // 脚下那格本身是会窒息的实心块（异常态）或岩浆时不垫——交给 antiSuffocate / 自保链。
        BlockPos place = pos;
        if (this.suffocates(level, pos)
                || level.getBlockState(pos).getFluidState()
                .is(net.minecraft.tags.FluidTags.LAVA)) {
            return false;
        }
        // 防窒息：头顶空间（place+1/place+2）放宽——目标矿所在格排除（搭高就是
        // 为了够它，垫块后头短暂插进矿里，下一 tick 挖掘入口立即挖掉，属预期），只拦真正会闷住她的
        // 实心满块（isSuffocating）。实测九百一十六：旧版严格 isAir 检查在"正上方就是矿"时必然
        // 失败 → 搭高永远失败、原地等到超时。
        if ((this.suffocates(level, place.offset(0, 1, 0)) && !place.offset(0, 1, 0).equals(this.targetPos))
                || (this.suffocates(level, place.offset(0, 2, 0)) && !place.offset(0, 2, 0).equals(this.targetPos))) {
            return false;
        }
        // v1.5.25e 借鉴自保 buildUp：女仆实际头顶（bounding box 顶面）上方必须空旷——
        // 快速连续搭高时实体位移滞后，blockPosition 检查不够，必须按真实位置判定，
        // 否则"搭太快把自己埋了"窒息（挖矿搭高也会踩同一坑）。headPos/headPos+1 同口径排除目标矿。
        double headY = maid.getBoundingBox().max(net.minecraft.core.Direction.Axis.Y);
        BlockPos headPos = new BlockPos((int) maid.getX(), (int) (headY + 0.05), (int) maid.getZ());
        if ((this.suffocates(level, headPos) && !headPos.equals(this.targetPos))
                || (this.suffocates(level, headPos.offset(0, 1, 0))
                && !headPos.offset(0, 1, 0).equals(this.targetPos))) {
            return false; // 实际头顶被会窒息的实心块堵住（正在被顶起中）→ 等站稳再垫，防窒息
        }
        // 目标矿在正上方时不误判为"障碍"（v1.5.25：头顶检查放宽，不检查矿所在格）
        // 实测五百三十六：目标格被主人碰撞箱占着就不搭
        // （防把主人痊住/盖头）——在取料前拦，不浪费方块
        if (com.maidsmart.tool.MaidPlaceGuard.blockedAtOwner(maid, place)) {
            return false;
        }
        Item item = this.takeBuildBlock(maid);
        if (item == null) {
            // v1.5.113（C2）：搭方块材料耗尽 → 气泡提示一次（限频），不再反复尝试
            this.notifyNoBuildBlock(maid);
            return false;
        }
        Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item));
        if (block == null) {
            return false;
        }
        level.setBlock(place, block.defaultBlockState(), 3);
        trackPlaced(level, place, block, maid); // v1.5.28：全局登记（实测四十二：绑定搭建女仆）
        // v1.1.0 实测三十七（反馈："搭方块的时候也播放一下动作"）：摆臂动画 +
        // 放置音效（blockEvent 3000/3001 = 放置方块音效+粒子，原版放置同款）
        maid.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        com.maidsmart.task.PlacedBlockTracker.placeSound(level, place, block);
        this.pillarGuardTicks = 12; // v1.5.87：搭块防掉落窗口
        // v1.5.113（B6）：冷却只在【成功放置】后设置——旧版失败也消耗冷却，
        // 搭高被错误阻塞
        this.pillarCooldown = com.maidsmart.config.MaidSmartConfig.MINE_PILLAR_COOLDOWN.get();
        return true;
    }

    /** 实测九百一十六【套用伐木】：该格是否【会窒息】——isSuffocating 为 true 的实心满块
     *  （石头/泥土/矿石等）会闷住女仆；空气/水/火把/树叶等不窒息。搭高防窒息检查用它替代
     *  isAir：目标矿在正上方时 isAir 检查让搭高永远失败。 */
    private boolean suffocates(ServerLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        return !st.isAir() && st.isSuffocating(level, pos);
    }

    /**
     * 实测九百三十二【垫不上去就先挖开头顶】——目标在她上方、头顶又是岩层时的唯一出路。
     * 把脚下正上方第一块**可挖开**的方块当临时目标挖掉（下一拍头顶空了，垫高自然成立，
     * 一格一格开天窗往上走）。挖矿 tick 的"目标可挖"判据本就认可开路石，挖掉后自动连锁同族。
     * <p>正上方就是矿（那是正常目标）或那块挖不开（基岩/箱子/机器）时返回 false，交给调用方
     * 继续走位——挖不开的那条才是真正的走不到（唯一合法的"够不着"）。
     * @return true = 已把头顶那块设为临时目标（本拍不要再走位）
     */
    private boolean digUpCap(ServerLevel level, EntityMaid maid) {
        BlockPos feet = maid.blockPosition();
        for (int dy = 1; dy <= 3; dy++) {
            BlockPos cap = feet.offset(0, dy, 0);
            if (level.getBlockState(cap).isAir()) {
                continue;   // 这格已经通了，继续往上看
            }
            if (this.isOre(level, cap)) {
                return false;   // 正上方就是矿 → 交给主链路（正常目标），这里不抢
            }
            if (!this.isOpenStone(level, cap)) {
                return false;   // 挖不开（基岩/箱子/机器）→ 这条才是真走不到
            }
            this.targetPos = cap.immutable();
            TARGET_SINCE.put(maid.getId(), level.getGameTime());
            this.traceExit(maid, "mine 头顶压住挖开", "cap=" + cap + " feet=" + feet);
            return true;
        }
        return false;
    }

    /** 实测八百六十九【没镐不启动】：挖矿任务因没镐不启动时气泡提示（限频 30 秒，不刷屏） */
    private void hintNoPickaxe(EntityMaid maid, ServerLevel level) {
        long now = level.getGameTime();
        Long last = NO_PICK_REPORT_SINCE.get(maid.getId());
        if (last != null && now - last < com.maidsmart.config.MaidSmartConfig.MINE_SKIP_REPORT_INTERVAL.get()) {
            return;
        }
        NO_PICK_REPORT_SINCE.put(maid.getId(), now);
        maid.getChatBubbleManager().addTextChatBubble(
                "我身上一把镐子都没有，挖不了矿呀～先给我一把镐子再让我开工吧！");
    }

    /**
     * v1.1.0【下方矿逐级下降】——目标比自己低时，在朝目标方向的前方一格【向下垫一格】
     * 形成下行台阶（比普通垫块低一层），女仆踩下去后高度降低 1 格；反复执行
     * 即可逐级下降接近矿物。其余逻辑（取料/摆臂/音效/冷却/回收）与普通垫块一致。
     *
     * 触发条件（缺一不可）：前方落脚层 y-1 与身体层 y 都是空气（能站能过）；
     * 落脚层脚下 y-2 无支撑（空气/水）→ 需要垫一块；已有支撑（纯落差）→ 交给行走兜底。
     */
    private boolean descendStep(ServerLevel level, EntityMaid maid, double hx, double hz, double hDist) {
        if (com.maidsmart.tool.MaidPlaceGuard.seatedBlocked(maid)) {
            return false; // 骑乘/坐下中不搭方块（实测六百三十五：统一闸口，与普通垫块同口径）
        }
        if (hDist < 1.0) {
            return false;
        }
        if (this.pillarCooldown > 0) {
            this.pillarCooldown--;
            return true;
        }
        int y = maid.blockPosition().getY();
        int tx = (int) Math.floor(maid.getX() + hx / hDist);
        int tz = (int) Math.floor(maid.getZ() + hz / hDist);
        BlockPos land = new BlockPos(tx, y - 1, tz);
        BlockPos body = new BlockPos(tx, y, tz);
        BlockPos support = land.offset(0, -1, 0);
        BlockState supportState = level.getBlockState(support);
        boolean noSupport = supportState.isAir() || supportState.getBlock()
                == net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("minecraft:water"));
        if (!noSupport || !level.getBlockState(land).isAir() || !level.getBlockState(body).isAir()) {
            return false;
        }
        if (com.maidsmart.tool.DangerBlocks.enabled()
                && (com.maidsmart.tool.DangerBlocks.cellDangerous(level, tx, y - 1, tz)
                        || com.maidsmart.tool.DangerBlocks.cellDangerous(level, tx, y - 2, tz))) {
            return false;
        }
        // 实测五百三十六：目标格被主人碰撞箱占着就不搭
        // （防把主人痊住/盖头）——在取料前拦，不浪费方块
        if (com.maidsmart.tool.MaidPlaceGuard.blockedAtOwner(maid, support)) {
            return false;
        }
        // 实测九百七十八：下行台阶同理——先转身再垫那一格
        com.maidsmart.task.BlockWalkOn.faceToward(maid, tx + 0.5, tz + 0.5);
        Item item = this.takeBuildBlock(maid);
        if (item == null) {
            this.notifyNoBuildBlock(maid);
            return false;
        }
        Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item));
        if (block == null) {
            return false;
        }
        level.setBlock(support, block.defaultBlockState(), 3);
        trackPlaced(level, support, block, maid);
        maid.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        com.maidsmart.task.PlacedBlockTracker.placeSound(level, support, block);
        this.pillarGuardTicks = 12;
        this.pillarCooldown = com.maidsmart.config.MaidSmartConfig.MINE_PILLAR_COOLDOWN.get();
        return true;
    }

    /**
     * v1.5.25 搭桥：目标方向前方 1 格脚下悬空（断崖/水）→ 垫 1 块桥，再走过去。
     * 只垫"该垫"的位置，不破坏任何方块；垫一块后重算（下 tick 再评估/走）。
     */
    private boolean bridgeToOre(ServerLevel level, EntityMaid maid, double hx, double hz, double hDist) {
        if (com.maidsmart.tool.MaidPlaceGuard.seatedBlocked(maid)) {
            return false; // 骑乘/坐下中不搭方块（v1.1.0 实测一百五十六；v1.2.4 实测六百三十五 并入统一闸口）
        }
        if (hDist < 1.0) {
            return false;
        }
        if (this.pillarCooldown > 0) {
            this.pillarCooldown--;
            return true; // v1.5.113（B6）：冷却中——等一步走完再垫（不重复消耗）
        }
        int y = maid.blockPosition().getY();
        int tx = (int) Math.floor(maid.getX() + hx / hDist);
        int tz = (int) Math.floor(maid.getZ() + hz / hDist);
        BlockPos ahead = new BlockPos(tx, y, tz);
        BlockPos fill = ahead.offset(0, -1, 0);
        BlockState below = level.getBlockState(fill);
        boolean gap = below.isAir() || below.getBlock()
                == net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("minecraft:water"));
        if (gap && level.getBlockState(ahead).isAir()) {
            // 实测五百三十六：目标格被主人碰撞箱占着就不搭
            // （防把主人痊住/盖头）——在取料前拦，不浪费方块
            if (com.maidsmart.tool.MaidPlaceGuard.blockedAtOwner(maid, fill)) {
                return false;
            }
            // 实测九百七十八：垫桥块之前先把身子转到要垫的那一格——旧版只垫不转身，
            // 垫完靠直推走过去，头朝向和前进方向无关（"倒着搭、倒着走"）
            com.maidsmart.task.BlockWalkOn.faceToward(maid, tx + 0.5, tz + 0.5);
            Item item = this.takeBuildBlock(maid);
            if (item == null) {
                this.notifyNoBuildBlock(maid); // v1.5.113（C2）
                return false;
            }
            Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item));
            if (block == null) {
                return false;
            }
            level.setBlock(fill, block.defaultBlockState(), 3);
            trackPlaced(level, fill, block, maid); // v1.5.28：全局登记（实测四十二：绑定搭建女仆）
            // v1.1.0 实测三十七：搭方块摆臂动画 + 放置音效
            maid.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            com.maidsmart.task.PlacedBlockTracker.placeSound(level, fill, block);
            this.pillarGuardTicks = 12; // v1.5.87：搭块防掉落窗口
            this.pillarCooldown = com.maidsmart.config.MaidSmartConfig.MINE_PILLAR_COOLDOWN.get(); // v1.5.113（B6）
            return true;
        }
        return false;
    }

    /**
     * v1.5.87：搭方块防掉落（仅在挖矿搭方块时生效，效果照搬玩家潜行、移速不变）——
     * 刚搭完柱子/桥的短窗口内，女仆站在自己搭的方块上时，水平位置不允许超出
     * 脚下支撑方块的范围（碰撞箱不越出边缘，与玩家潜行"边缘夹住"一致），
     * 而不是缓慢拉回；窗口结束自动释放不困住女仆。
     */
    private void pillarGuard(ServerLevel level, EntityMaid maid) {
        BlockPos feet = maid.blockPosition();
        BlockPos under = feet.offset(0, -1, 0);
        if (!isMiningPlaced(level, under) && !isMiningPlaced(level, feet)) {
            return; // 脚下不是自己搭的方块 → 不钳制
        }
        // 潜行核心：实体中心距支撑方块中心的最大偏移 = 0.5 - 碰撞箱半宽
        // （中心到边缘的余量正好让碰撞箱贴着方块边缘，不悬空越界）
        double halfW = maid.getBbWidth() / 2.0;
        double limit = Math.max(0.05, 0.5 - halfW);
        double cx = under.getX() + 0.5;
        double cz = under.getZ() + 0.5;
        double x = maid.getX();
        double z = maid.getZ();
        double nx = Math.max(cx - limit, Math.min(cx + limit, x));
        double nz = Math.max(cz - limit, Math.min(cz + limit, z));
        if (nx != x || nz != z) {
            maid.setPos(nx, maid.getY(), nz);
        }
    }

    private boolean isOre(ServerLevel level, BlockPos pos) {
        return oreValueOf(level.getBlockState(pos)) != null;
    }

    /**
     * v1.5.189：危险方块规避——该格（或脚下）是岩浆/火/岩浆块/仙人掌/甜浆果/营火
     * 视为危险：挖矿目标或路径上不选（照抄自保 DANGER_BLOCKS 判定，天然回避）
     */
    private boolean isDangerAt(ServerLevel level, BlockPos pos) {
        try {
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
            net.minecraft.world.level.block.state.BlockState below = level.getBlockState(pos.offset(0, -1, 0));
            for (String id : DANGER_BLOCK_IDS) {
                net.minecraft.world.level.block.Block b =
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                                net.minecraft.resources.ResourceLocation.parse(id));
                if (b != null && (state.getBlock() == b || below.getBlock() == b)) {
                    return true;
                }
            }
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("MaidMineBehavior", ignored);
        }
        return false;
    }

    /** 实测二百二十四：BlockState 是否为可挖穿障碍物（与 isOpenStone 同口径，接受状态）——
     *  挖完瞬间方块已变空气，坐标版判不了，用挖掉的 state 登记。 */
    private static boolean isOpenStoneBlock(net.minecraft.world.level.block.state.BlockState st) {
        try {
            net.minecraft.resources.ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock());
            return key != null && isBreakable(key.getPath());
        } catch (Exception e) {
             com.maidsmart.diag.Diag.swallow("MaidMineBehavior", e); return false;
        }
    }

    /**
     * v1.5.47：解析锚点——无锚点则设脚下；出框超时重埋。
     * v1.5.113：迁移不再走独立状态机（旧 MIGRATING/MIGRATE_SINCE 已删）——
     * 框空时由 doTick 直接 slideAnchor（滑动锚点，一次到位）。
     * 返回当前锚点（null = 无锚点，调用方应停止）。
     */
    private BlockPos resolveAnchor(ServerLevel level, EntityMaid maid) {
        int id = maid.getId();
        long now = level.getGameTime();
        BlockPos anchor = ANCHORS.get(id);
        if (anchor == null) {
            this.relocate(level, maid, now); // 首次定位：脚下
            return ANCHORS.get(id);
        }
        // 出框超时：连续 10 秒在框外 → 重埋（节流 20 tick 防边界抖动）
        if (this.outOfBox(maid.blockPosition(), anchor)) {
            Long since = OUT_SINCE.get(id);
            if (since == null) {
                OUT_SINCE.put(id, now);
            } else if (now - since >= com.maidsmart.config.MaidSmartConfig.MINE_ANCHOR_TIMEOUT.get()) {
                OUT_SINCE.remove(id);
                this.relocate(level, maid, now);
            }
        } else {
            OUT_SINCE.remove(id);
        }
        return ANCHORS.get(id);
    }

    /** v1.5.47：女仆是否在锚点框外（水平 ANCHOR_RADIUS + 垂直 DOWN/UP 范围）
     *  v1.5.88：锚点半径跟随配置面板 mine.searchRadius */
    private boolean outOfBox(BlockPos feet, BlockPos anchor) {
        int radius = com.maidsmart.config.MaidSmartConfig.MINE_SEARCH_RADIUS.get();
        int down = com.maidsmart.config.MaidSmartConfig.MINE_DOWN_RANGE.get();
        int up = com.maidsmart.config.MaidSmartConfig.MINE_UP_RANGE.get();
        return Math.abs(feet.getX() - anchor.getX()) > radius
                || Math.abs(feet.getZ() - anchor.getZ()) > radius
                || feet.getY() - anchor.getY() > up
                || anchor.getY() - feet.getY() > down;
    }

    /** v1.5.47：重埋锚点（脚下；20 tick 节流） */
    private void relocate(ServerLevel level, EntityMaid maid, long now) {
        int id = maid.getId();
        Long last = LAST_RELOCATE.get(id);
        if (last != null && now - last < com.maidsmart.config.MaidSmartConfig.MINE_RELOCATE_THROTTLE.get()) {
            return;
        }
        LAST_RELOCATE.put(id, now);
        // 实测五百六十二：home 模式下女仆在限制圈外（被推/被打飞）时，锚点落在圈心
        // 而不是脚下——她自然走回工作区域，而不是原地开新框把圈外越挖越远
        BlockPos anchorPos = maid.blockPosition();
        if (!com.maidsmart.follow.WorkAreaClamp.allowsWork(maid, anchorPos)) {
            BlockPos cc = com.maidsmart.follow.WorkAreaClamp.circleCenter(maid);
            if (cc != null) {
                anchorPos = cc;
            }
        }
        ANCHORS.put(id, anchorPos.immutable());
        OUT_SINCE.remove(id);
    }

    /**
     * 一个搜索环宽（新框与旧框错开一半以上），女仆无需走路、马上能挖新框的矿。① 被墙/悬崖堵住走不动 → 30 秒停滞就地重埋 → 新框与旧框重合 → 又空 → 又迁移（死循环）；② 迁移期纯走路零产出。
     * 每 5 秒最多滑一次（SLIDE_SINCE 节流），空旷地形也不会高速漂移。清 ORE_CACHE（新框未扫描）；**不设移动目标**（实测九百一十三：框空不该赶路）。
     * <p>实测九百二十六【必须真的滑走】：主人在锚点上（她守在主人脚边干活）时，旧版把方向算成 (0,0) → 锚点原地不动 → 同一片空框反复全量扫、她永远没目标（实机 03:24:09 起 27 秒零动作）。
     * 现在改成 8 向轮转取方向：框绕着主人一格一格转着找，既不原地不动、也不会越挖越远。
     */
    private boolean slideAnchor(ServerLevel level, EntityMaid maid, BlockPos old) {
        int id = maid.getId();
        long now = level.getGameTime();
        Long last = SLIDE_SINCE.get(id);
        if (last != null && now - last < 100L) {
            return false; // 5 秒一次
        }
        SLIDE_SINCE.put(id, now);
        // 实测五百六十二：home 模式锚点固定在工作区域内——迁移正是"挖矿女仆越走越远"
        // 的源头（框框相连追出去半个地图），圈挖空就待在圈里等（区块刷新/换任务）
        if (maid.hasRestriction()) {
            LOGGER.info("mine box empty but home-restricted (pinned work area, no migration): maid={} anchor={}",
                    id, old);
            return false; // home 模式不迁移：工作圈钉死（圈内挖空就等区块刷新/换任务）
        }
        int step = Math.max(4, com.maidsmart.config.MaidSmartConfig.MINE_SEARCH_RADIUS.get());
        int dirX = 1;
        int dirZ = 0;
        // v1.5.113：owner 直接取（getOwner 静态类型就是 LivingEntity，
        // instanceof LivingEntity 在 --release 17 是"无条件模式"编译错误）
        net.minecraft.world.entity.LivingEntity owner = maid.getOwner();
        if (owner != null) {
            double dx = owner.getX() - old.getX();
            double dz = owner.getZ() - old.getZ();
            if (Math.abs(dx) < 1.0 && Math.abs(dz) < 1.0) {
                // 实测九百二十六【框空站桩的死路】：主人在锚点上（她就守在主人脚边干活）时，
                // 旧版把方向算成 (0,0) = 锚点原地不动，于是同一片空框反复全量扫、她永远没目标
                //（实机 03:24:09 起 27 秒零动作、看门狗每 8 秒重置一次）。改成按 8 向轮转取一个
                // 方向：框绕着主人一格一格转着找，既不原地不动、也不会越挖越远。
                this.slideTurn++;
                double ang = (this.slideTurn % 8) * Math.PI / 4.0;
                dirX = (int) Math.round(Math.cos(ang));
                dirZ = (int) Math.round(Math.sin(ang));
                if (dirX == 0 && dirZ == 0) {
                    dirX = 1;
                }
            } else {
                dirX = dx >= 0 ? 1 : -1;
                dirZ = dz >= 0 ? 1 : -1;
            }
        }
        BlockPos na = new BlockPos(old.getX() + dirX * step,
                old.getY(), old.getZ() + dirZ * step).immutable();
        ANCHORS.put(id, na);
        OUT_SINCE.remove(id);
        ORE_CACHE.remove(id); // 新框未扫描，强制重建
        // 实测九百一十三：框空 = 没找到矿 → 【不再朝新框赶路】——原版 TLM 的走路逻辑
        // （空闲闲逛 / 跟随主人）接管；她走到哪，下一轮 relocate 就把搜索框埋回她脚下。
        return true;
    }

    /** v1.5.85：记录本次扫描中镐子挖不动的矿（只保留价值最高的一个，用于播报） */
    private void recordSkippedOre(EntityMaid maid, BlockPos pos, BlockState state) {
        Integer value = oreValueOf(state);
        if (value == null || (this.skippedOrePos != null && value <= this.skippedOreValue)) {
            return;
        }
        this.skippedOreValue = value;
        this.skippedOrePos = pos.immutable();
        // v1.5.102c：报中文矿石名（旧版英文 path 如 diamond_ore）
        this.skippedOreName = blockCnName(state.getBlock());
        this.skippedOreTool = requiredTool(state);
    }

    /**
     * v1.5.85：播报"附近有矿但镐子挖不动"——气泡 + 主人聊天栏（v1.5.68 气泡同步），
     * 报坐标和需要的镐子等级；每 30 秒最多一次，不刷屏。
     */
    private void reportSkippedOre(EntityMaid maid, long gameTime) {
        if (this.skippedOrePos == null) {
            return;
        }
        Long last = SKIP_REPORT_SINCE.get(maid.getId());
        if (last != null && gameTime - last < com.maidsmart.config.MaidSmartConfig.MINE_SKIP_REPORT_INTERVAL.get()) {
            return;
        }
        SKIP_REPORT_SINCE.put(maid.getId(), gameTime);
        // v1.5.89：诊断——带上主手实际物品（排查"镐没装备导致挖不了"）
        String handName = "空";
        ItemStack hand = maid.getMainHandItem();
        if (!hand.isEmpty()) {
            net.minecraft.resources.ResourceLocation hk = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(hand.getItem());
            handName = hk != null ? com.maidsmart.build.BlueprintLib.cnName(hk.toString()) : hand.getItem().toString();
        }
        LOGGER.info("mine skip-report: maid={} ore={} at={} need={} hand={}",
                maid.getUUID(), this.skippedOreName, this.skippedOrePos, this.skippedOreTool, handName);
        maid.getChatBubbleManager().addTextChatBubble(
                "我发现了一个" + this.skippedOreName + "（坐标 " + this.skippedOrePos.getX()
                        + ", " + this.skippedOrePos.getY() + ", " + this.skippedOrePos.getZ()
                        + "），需要" + this.skippedOreTool + "才能挖，我现在的镐子（主手：" + handName
                        + "）挖不动，先跳过啦～");
    }

    /**
     * v1.5.87：播报"矿被硬挡路（箱子/机器/基岩等）挡住"——气泡 + 主人聊天栏，
     * 报挡路方块与坐标；每 30 秒限频一次。该矿同时加入 blockedOres 持续排除。
     */
    private void reportBlockedOre(EntityMaid maid, ServerLevel level, BlockPos orePos, BlockPos blockerPos) {
        long now = level.getGameTime();
        Long last = BLOCKED_REPORT_SINCE.get(maid.getId());
        if (last != null && now - last < com.maidsmart.config.MaidSmartConfig.MINE_SKIP_REPORT_INTERVAL.get()) {
            return;
        }
        BLOCKED_REPORT_SINCE.put(maid.getId(), now);
        // v1.5.102c：报中文方块名（旧版英文 path，如 "bedrock"/"chest"）
        String name = blockCnName(level.getBlockState(blockerPos).getBlock());
        // v1.5.143：被挡的矿物名也报中文（旧版只说"挡住了矿物"，不说挡住的是哪种矿）
        String oreName = blockCnName(level.getBlockState(orePos).getBlock());
        LOGGER.info("mine blocked-report: maid={} ore={} at={} blocker={} at={}",
                maid.getUUID(), oreName, orePos, name, blockerPos);
        maid.getChatBubbleManager().addTextChatBubble(
                "前方有" + name + "（坐标 " + blockerPos.getX() + ", " + blockerPos.getY()
                        + ", " + blockerPos.getZ() + "）挡住了" + oreName + "（坐标 " + orePos.getX()
                        + ", " + orePos.getY() + ", " + orePos.getZ() + "），我挖不过去，先换个目标啦～"
                        + "（提示：如果挡路的其实就是你想让我挖的矿，说明它还没进矿表——配置面板挖矿·矿表里加入即可）");
    }

    /**
     * v1.5.102d：场上没有可挖矿物、但此前有被硬物挡住的矿时，仍上报一次挡路原因
     * （30 秒限频）——让主人知道"没矿"其实是被基岩/箱子等挡住了，而不是女仆偷懒。
     */
    private void reportBlockedArea(EntityMaid maid, ServerLevel level) {
        long now = level.getGameTime();
        Long last = BLOCKED_REPORT_SINCE.get(maid.getId());
        if (last != null && now - last < com.maidsmart.config.MaidSmartConfig.MINE_SKIP_REPORT_INTERVAL.get()) {
            return;
        }
        BLOCKED_REPORT_SINCE.put(maid.getId(), now);
        LOGGER.info("mine blocked-area: maid={} blockedCount={}", maid.getUUID(), this.blockedOres.size());
        maid.getChatBubbleManager().addTextChatBubble(
                "附近有矿物被硬方块挡住（基岩/箱子等），我挖不过去，暂时没有可挖的矿物～");
        if (maid.getOwner() instanceof net.minecraft.server.level.ServerPlayer owner) {
            // v1.1.0 实测二百七十四：建造女仆静默非建造字幕（气泡已由 mixin 拦）
            if (!com.maidsmart.combat.BuildShieldGuard.shouldMute(maid)) {
                owner.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "\u00a7c【挖矿】" + maid.getDisplayName().getString()
                                + "：附近有矿物被硬方块挡住，暂时没有可挖的矿物～"));
            }
        }
    }

    /** 实测九百二十九【弃置必须留痕】：每一次"这块/这棵不要了"都留一行（5 秒/条）。
     *  旧版六条弃置路径**一条日志都没有**——实机里"她站着不动二十五秒、日志零行"就是这么来的，
     *  连着两轮都在猜根因。留痕口径与走路那条（{@code work walk stuck}）一致。 */
    private void giveUpLog(EntityMaid maid, String reason, BlockPos p, long gameTime) {
        if (gameTime - this.giveUpLogAt < 100L) {
            return;
        }
        this.giveUpLogAt = gameTime;
        try {
            double d = p == null ? -1.0 : Math.sqrt(this.distSqToBlock(maid, p));
            LOGGER.info("mine give-up: maid={} reason={} target={} dist={} feet={} progress={}",
                    maid.getUUID(), reason, p, (long) d, maid.blockPosition(), this.destroyProgress);
        } catch (Exception ex) { com.maidsmart.diag.Diag.swallow("MaidMineBehavior", ex);
        }
    }
    // 实测九百三十二【删"弃置"】：原 abandon() / wedgeAbandon() / targetTimeoutTicks() 三个方法
    // 整体删除——"够不着就丢掉这块、记 30 秒短排、连弃 3 次换框"全部取消。

    /** 实测九百八十：丢弃过期扫描状态、把扫描框挪到她自己脚下（目标跑出检索半径时用）。
     *  只动"框"和缓存，不碰世界、不碰游标以外的任何状态。 */
    private void dropStaleScan(EntityMaid maid) {
        int id = maid.getId();
        ORE_CACHE.remove(id);
        MINE_SCANS.remove(id);
        ANCHORS.put(id, maid.blockPosition().immutable());
    }

    private BlockPos findOre(ServerLevel level, EntityMaid maid, BlockPos anchor) {
        int id = maid.getId();
        long now = level.getGameTime();
        // v1.5.85：每次扫描重置"镐子挖不动的矿"记录（记录本次扫描价值最高的，用于播报）
        this.skippedOrePos = null;
        this.skippedOreName = null;
        this.skippedOreTool = null;
        this.skippedOreValue = -1;
        // 实测九百四十一：修剪过期的短时排除（30 秒）——与伐木同表同口径（RECENT_DISCARD）。
        java.util.Map<BlockPos, Long> disc = RECENT_DISCARD.get(id);
        if (disc != null) {
            disc.entrySet().removeIf(e -> now - e.getValue() > 600L);
            if (disc.isEmpty()) {
                RECENT_DISCARD.remove(id);
            }
        }
        // v1.5.88：懒加载自定义矿表（config 文件加载完成后首次扫描才真正读到值）
        ensureCustomOres();
        OreCache cache = ORE_CACHE.get(id);
        if (cache != null) {
            // v1.1.0 实测一百四十二：去掉 5 秒 TTL 到期强制重建——缓存轮每次都会逐格
            // 校验矿是否还在（存在性/框内/可挖/挡路/弃置，pickFromCache 内），到期重建
            // 只会造成"每 5 秒停一下重扫/换目标"（反馈：挖矿伐木每 5 秒重置一次状态，
            // 等效收放魂符）。缓存一直用到轮空才全量重建（锚点变化/迁框另有强制重建
            // 路径）；框内新露头的矿在缓存轮空后的下一次全量扫描里自然被找到
            this.lastScanWasFull = false;
            BlockPos fromCache = this.pickFromCache(level, maid, anchor, cache);
            if (fromCache != null) {
                return fromCache;
            }
            // v1.5.127：缓存轮空 → 不等 TTL 立即全量重建——"原地愣 2~3 秒"根因：
            // 挖完缓存里最后一块矿后 pickFromCache 返回 null，旧版要等 ORE_CACHE_TTL
            // （5 秒）到期才重建，女仆在这 2~5 秒里站着发呆；重建顺带把"框内无矿
            // 迁移"从等 5 秒提速到即时。重建仍无 → 返回 null 走迁移逻辑（不变）。
            this.lastScanWasFull = true;
            return this.fullScanOres(level, maid, anchor, now);
        }
        this.lastScanWasFull = true;
        return this.fullScanOres(level, maid, anchor, now);
    }

    /**
     * v1.5.113：全量重建扫描（每 5 秒一次）——扫满整个搜索框，记录候选矿 + 挡路预算。
     * v1.1.0 实测六十一（借鉴 TLM-Sincerely 预算制探测）：旧版一帧内同步扫完
     * （8.9 万格 = 单 tick 尖峰），改为【分帧游标续扫】——每 tick 最多检查
     * MINE_SCAN_BUDGET 格，游标跨 tick 续进；扫完才写 ORE_CACHE 返回最优。
     * 扫描期间返回 null（调用方按"本 tick 无目标"处理）；锚点变化/状态过期作废重扫。
     */
    private BlockPos fullScanOres(ServerLevel level, EntityMaid maid, BlockPos anchor, long now) {
        int id = maid.getId();
        int radius = com.maidsmart.config.MaidSmartConfig.MINE_SEARCH_RADIUS.get();
        int down = com.maidsmart.config.MaidSmartConfig.MINE_DOWN_RANGE.get();
        int up = com.maidsmart.config.MaidSmartConfig.MINE_UP_RANGE.get();
        OreScanState st = MINE_SCANS.get(id);
        if (st == null || !st.anchor.equals(anchor) || now - st.startedAt > 1200L
                || st.dy < -down || st.dy > up
                || st.dx < -radius || st.dx > radius || st.dz < -radius || st.dz > radius) {
            st = new OreScanState(anchor, now, radius, down);
            MINE_SCANS.put(id, st);
        }
        int budget = com.maidsmart.config.MaidSmartConfig.MINE_SCAN_BUDGET.get();
        int breakBudget = com.maidsmart.config.MaidSmartConfig.MINE_BREAK_BUDGET.get();
        java.util.Map<BlockPos, Long> disc = RECENT_DISCARD.get(id);
        int processed = 0;
        while (st.dy <= up && processed < budget) {
            BlockPos p = anchor.offset(st.dx, st.dy, st.dz);
            // 游标前进：dz → dx → dy（dz/dx 越界回绕进位）
            st.dz++;
            if (st.dz > radius) {
                st.dz = -radius;
                st.dx++;
            }
            if (st.dx > radius) {
                st.dx = -radius;
                st.dy++;
            }
            processed++;
            if (this.blockedOres.contains(p)
                    || (disc != null && disc.containsKey(p.immutable()))) {
                continue; // v1.5.87：硬挡路持续排除；实测九百四十一：再加"短时排除"（停滞弃置 30 秒）
            }
            BlockState oreState = level.getBlockState(p);
            Integer value = oreValueOf(oreState);
            if (value == null) {
                continue;
            }
            // 实测五百六十二：home 模式限制圈外的矿不进候选（工作区域=工位锚点圈，
            // 与 TLM 原生 MaidMoveToBlockTask 同口径）；圈外矿曾靠锚点滑动越追越远
            if (!com.maidsmart.follow.WorkAreaClamp.allowsWork(maid, p)) {
                continue;
            }
            // v1.1.0：不把自己 10 秒内搭的方块当矿（搭路材料恰好在矿表里会循环拆了再搭）
            if (isMiningPlaced(level, p)) {
                continue;
            }
            // v1.0.4：透视感知开关（默认关）——关闭时女仆像玩家一样只发现视线无阻
            // 的矿物：被墙/实心方块挡住的矿不可见（不进候选，也就没有系统/气泡播报）；
            // 开启 = 旧版隔墙找矿逻辑，不检查视线
            if (!com.maidsmart.config.MaidSmartConfig.MINE_SEEK_THROUGH_WALLS.get()
                    && !this.hasClearSight(level, maid, p)) {
                continue;
            }
            // v1.5.189：危险方块规避——目标矿自身或路径上有岩浆/火/岩浆块/
            // 仙人掌/甜浆果/营火 → 不选（挖过去会烫伤/引燃；复用自保 DANGER 判定）
            if (this.isDangerAt(level, p)) {
                continue;
            }
            // v1.5.85/107：手+背包都挖不动才算真挖不动（跳过+播报）
            // v1.5.113（A3）：先查主手（零开销），主手不够才查背包最高级镐
            if (!MaidToolAutoEquip.canHarvestWithHandOrBackpack(maid, oreState)) {
                this.recordSkippedOre(maid, p, oreState);
                continue;
            }
            // v1.5.47：穿透预算——挡路实心方块 >预算 的矿不选（挖不过去）
            int blocking = this.countBlocking(level, maid, p);
            if (blocking > breakBudget) {
                continue;
            }
            st.blockingCache.put(p.immutable(), blocking); // v1.5.113（A3）：缓存轮复用
            st.found.add(p.immutable());
            // 实测九百八十【口径统一】：框内但在她自己检索半径外的格不进候选——否则外层
            // "跑出检索半径"清空它、下一轮又从缓存选回同一格（实机原地站 37 秒的根因）。
            // 仍记进 found（缓存保持完整）：等她走近了下一轮缓存校验自然会放行。
            if (this.targetOutOfRadius(maid, p, radius)) {
                continue;
            }
            // 实测八百六十八：排序基准从"锚点"改成"她当前位置"（与 maid_mining 同口径），
            // 并删掉旧的 reachBonus（-400 会随她移动实时翻转目标 = "锁定能力差"根因之一）
            // 实测九百一十三【最近优先】：连价值/深度两项也一并废除——只按"她到矿的距离²"
            // 排序，圈内最近的那块就是目标（配置项 mine.valueWeight / mine.depthPenalty 已删）。
            double score = distSqToBlock(maid, p);
            if (score < st.bestScore) {
                st.bestScore = score;
                st.best = p.immutable();
            }
        }
        if (st.dy <= up) {
            return null; // 预算用尽，下 tick 续扫
        }
        // 扫描完成：写缓存（与旧版同口径），清游标状态
        this.traceExit(maid, "mine 全量扫描完成", "found=" + st.found.size() + " best=" + st.best);
        MINE_SCANS.remove(id);
        ORE_CACHE.put(id, new OreCache(now, st.found, st.blockingCache));
        return st.best;
    }

    /** v1.5.113：缓存轮——只校验已记录的矿（存在性/框内/可挖/挡路预算），廉价 */
    private BlockPos pickFromCache(ServerLevel level, EntityMaid maid, BlockPos anchor, OreCache cache) {
        int id = maid.getId();
        long now = level.getGameTime();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        int radius = com.maidsmart.config.MaidSmartConfig.MINE_SEARCH_RADIUS.get();
        int down = com.maidsmart.config.MaidSmartConfig.MINE_DOWN_RANGE.get();
        int up = com.maidsmart.config.MaidSmartConfig.MINE_UP_RANGE.get();
        int budget = com.maidsmart.config.MaidSmartConfig.MINE_BREAK_BUDGET.get();
        java.util.Map<BlockPos, Long> disc = RECENT_DISCARD.get(id);
        for (BlockPos p : cache.ores) {
            if (this.blockedOres.contains(p) || (disc != null && disc.containsKey(p.immutable()))) {
                continue; // 实测九百三十二：只留硬挡路；实测九百四十一：再加"短时排除"（停滞弃置 30 秒）
            }
            if (!this.isOre(level, p)) {
                continue; // 已被挖掉
            }
            // 实测五百六十二：缓存轮同口径——限制圈外的矿不选（home 工作区域）
            if (!com.maidsmart.follow.WorkAreaClamp.allowsWork(maid, p)) {
                continue;
            }
            // v1.1.0：缓存轮同样跳过自己 10 秒内搭的方块（不当矿挖，与全量扫描同口径）
            if (isMiningPlaced(level, p)) {
                continue;
            }
            // v1.0.4：透视感知默认关——缓存轮同样只认视线无阻的矿（女仆移动/配置热更新后
            // 与发现层判定一致；开启透视则跳过）
            if (!com.maidsmart.config.MaidSmartConfig.MINE_SEEK_THROUGH_WALLS.get()
                    && !this.hasClearSight(level, maid, p)) {
                continue;
            }
            int dx = p.getX() - anchor.getX();
            int dz = p.getZ() - anchor.getZ();
            int dy = p.getY() - anchor.getY();
            if (Math.abs(dx) > radius || Math.abs(dz) > radius
                    || dy > up || -dy > down) {
                continue; // 锚点滑动/重埋后出框
            }
            BlockState st = level.getBlockState(p);
            if (!MaidToolAutoEquip.canHarvestWithHandOrBackpack(maid, st)) {
                this.recordSkippedOre(maid, p, st);
                continue;
            }
            // v1.5.189：危险方块规避（缓存轮同样跳过岩浆/火/岩浆块等目标）
            if (this.isDangerAt(level, p)) {
                continue;
            }
            Integer value = oreValueOf(st);
            int blocking = cache.blocking.getOrDefault(p, budget + 1);
            if (value == null || blocking > budget) {
                continue;
            }
            // 实测九百八十【口径统一】：缓存的"框"是相对【锚点】算的，她挖远之后同一个格可能已经
            // 在她自己的检索半径外——外层判据（她到格）会把它清掉，可缓存下一轮又把同一格选回来，
            // 于是"选中 → 跑出半径 → 清空 → 再选中"无限循环（实机 00:34:54 起原地站了 37 秒、
            // 无气泡无日志，只能收放魂符一次）。缓存轮必须按【她到格】的同一口径筛一遍。
            if (this.targetOutOfRadius(maid, p, radius)) {
                continue;
            }
            // 实测八百六十八：与全量扫描同口径——按到女仆的距离² 排序、去掉 reachBonus
            // 实测九百一十三【最近优先】：价值/深度两项一并废除（配置项已删），只看距离²
            double score = distSqToBlock(maid, p);
            if (score < bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }

    /**
     * 从眼睛到矿物中心采样（每 0.5 格，与 countBlocking 同一口径），到达矿物前碰到任何
     * 【非空气、非流体】方块即判定视线被挡 → 不可见。除水/岩浆外一律挡视线：
     * - 泥土/石头/矿石/箱子等自不必说；
     * - 玻璃/半砖/树叶等透明或半格方块也挡——它们不是矿洞天然生成物，玩家手动把矿
     *   裹起来说明不想破坏里面的矿（半格高按一格高算）；
     * - 水/岩浆是矿洞常见液体，女仆要能挖水下/岩浆旁的矿（岩浆旁的目标另有危险规避）。 注意与 countBlocking 的区别：那是"挖不挖得过去"（穿透预算），这里是"看不得看见" （发现层过滤）——关闭透视后视线被挡的矿不进候选，也不产生任何播报。 底面任一可见即算可见（贴地矿顶面可见、悬空矿底面可见，玩家能看到矿的任何一个 面就算看见）。凹槽里的矿仍须视线从开口进入：开口顶面低于眼睛时三条线都撞墙， 依旧不可见（与玩家站在同一位置一致）。
     */
    private boolean hasClearSight(ServerLevel level, EntityMaid maid, BlockPos target) {
        double sx = maid.getX();
        double sy = maid.getY() + EYE_HEIGHT; // 与 countBlocking/findBlockingBlock 同一眼睛高度
        double sz = maid.getZ();
        double cx = target.getX() + 0.5;
        double cz = target.getZ() + 0.5;
        if (hasClearRay(level, sx, sy, sz, cx, target.getY() + 0.5, cz, target)
                || hasClearRay(level, sx, sy, sz, cx, target.getY() + 1.0, cz, target)
                || hasClearRay(level, sx, sy, sz, cx, target.getY(), cz, target)) {
            return true;
        }
        // 【v1.3.9.5 露头的矿也要看得见】
        //
        // 玩家在下挖的坑道里走得靠前一点，射线擦着坑壁/台阶边缘就整条被否掉——可那块矿的
        // **某个面明明就露在空气里**。补一条"看暴露面"的判据：六个面里哪一面贴着空气/流体
        // （= 露头），就把射线打到那一面**外侧格的中心**；打得到就说明这一面看得见 → 矿可见。
        // 六个面全被实心方块裹着的埋矿，六条外侧射线同样被岩壁挡住 → 仍然不可见（不产生透视）。
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            BlockPos out = target.relative(d);
            BlockState os = level.getBlockState(out);
            if (!os.isAir() && os.getFluidState().isEmpty()) {
                continue; // 这一面没露头（贴着实心方块）
            }
            if (hasClearRay(level, sx, sy, sz,
                    out.getX() + 0.5, out.getY() + 0.5, out.getZ() + 0.5, target)) {
                return true;
            }
        }
        return false;
    }

    /** 从眼睛位置到目标点每 0.5 格采样：到矿本身即停，路径上任何非空气/非流体方块都挡 */
    private boolean hasClearRay(ServerLevel level, double sx, double sy, double sz,
                                double tx, double ty, double tz, BlockPos target) {
        double dist = Math.sqrt((tx - sx) * (tx - sx) + (ty - sy) * (ty - sy) + (tz - sz) * (tz - sz));
        int steps = Math.max(1, (int) Math.ceil(dist * 2.0));
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            double x = sx + (tx - sx) * t;
            double y = sy + (ty - sy) * t;
            double z = sz + (tz - sz) * t;
            BlockPos sample = new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
            if (sample.equals(target)) {
                break; // 到矿本身，不再检查
            }
            BlockState st = level.getBlockState(sample);
            if (st.isAir()) {
                continue; // 空气不挡
            }
            if (!st.getFluidState().isEmpty()) {
                continue; // 水/岩浆等流体不挡（isEmpty = FluidState.isEmpty）
            }
            return false; // 任何其他方块（含玻璃/半砖/树叶/土/石）都挡视线
        }
        return true;
    }

    /**
     * v1.5.47：女仆到目标路径上的实心非矿方块数（≤预算可穿透；矿石/空气/非满块/水不算）。
     * 改造自 v1.5.25g hasLineOfSight——旧版"全遮挡排除"导致被岩壁包住的矿永远挖不到。
     */
    private int countBlocking(ServerLevel level, EntityMaid maid, BlockPos target) {
        double sx = maid.getX();
        double sy = maid.getY() + EYE_HEIGHT; // 眼睛高度
        double sz = maid.getZ();
        double tx = target.getX() + 0.5;
        double ty = target.getY() + 0.5;
        double tz = target.getZ() + 0.5;
        double dist = Math.sqrt((tx - sx) * (tx - sx) + (ty - sy) * (ty - sy) + (tz - sz) * (tz - sz));
        int steps = Math.max(1, (int) Math.ceil(dist * 2.0)); // 每 0.5 格采样一次
        int blocking = 0;
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            double x = sx + (tx - sx) * t;
            double y = sy + (ty - sy) * t;
            double z = sz + (tz - sz) * t;
            BlockPos sample = new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
            if (sample.equals(target)) {
                break; // 到矿本身，不再检查
            }
            BlockState st = level.getBlockState(sample);
            if (st.isAir()) {
                continue; // 空气
            }
            // v1.1.0 实测二十三：岩浆格视作硬阻挡——旧版流体不算阻挡，岩浆湖对岸
            // 的矿预算为 0 照样入选 → 搭桥判空不含岩浆、findStandNearOre 找不到
            // 立足点 → 女仆对着湖心矿原地站满 300 tick 目标超时才弃。选矿阶段
            // 直接把射线上的岩浆计入阻挡，湖心矿大概率超预算、压根不选中
            if (isLavaBlock(st.getBlock())) {
                blocking++;
                continue;
            }
            if (oreValueOf(st) != null) {
                continue; // 矿石不挡
            }
            if (isMiningPlaced(level, sample)) {
                continue; // v1.1.0：自己 10 秒内搭的方块不算阻挡（到期自动销毁，不算预算）
            }
            if (!st.isRedstoneConductor(level, sample)) {
                continue; // 非满块（楼梯/台阶/花/草）不挡；水不算
            }
            // 实测九百一十七【套用伐木的穿透预算口径】：可开路方块（石头/泥土/深板岩/安山岩等
            // OPEN_BREAKABLE + 徒手可挖软方块）不计阻挡——它们本就是"挖穿开路"的对象，不是
            // 障碍。旧版把它们逐层计满预算，而隔墙透视一开、入选的矿几乎全埋在石头里，预算
            // 当场爆掉 → 一块也选不出来（反馈"开了透视还是站着发呆"）。伐木侧一直就是这个
            // 口径（见 MaidWoodBehavior.countBlocking 放行 isSoft/isOpenStone）。真硬挡路
            // （箱子/机器/基岩等）与岩浆照旧计数。
            if (isSoft(maid.getMainHandItem(), st) || this.isOpenStone(level, sample)) {
                continue;
            }
            blocking++;
        }
        return blocking;
    }

    /**
     * v1.5.47：路径上第一个挡路块（穿透挖掘用——够得着但中间挡着方块时细分处理）。
     * v1.5.87 细分：
     * - OPEN_BREAKABLE（石头类 + 徒手可挖软方块）→ Blocker(pos, true)，挖掉开路
     * - 其他实心满块（箱子/机器/基岩等）→ Blocker(pos, false)，报点弃置（绝不隔墙挖矿）
     * - 无挡路 → null（视线通畅，直接挖矿）
     */
    private Blocker findBlockingBlock(ServerLevel level, EntityMaid maid, BlockPos target) {
        double sx = maid.getX();
        double sy = maid.getY() + EYE_HEIGHT; // 眼睛高度
        double sz = maid.getZ();
        double tx = target.getX() + 0.5;
        double ty = target.getY() + 0.5;
        double tz = target.getZ() + 0.5;
        double dist = Math.sqrt((tx - sx) * (tx - sx) + (ty - sy) * (ty - sy) + (tz - sz) * (tz - sz));
        int steps = Math.max(1, (int) Math.ceil(dist * 2.0));
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            double x = sx + (tx - sx) * t;
            double y = sy + (ty - sy) * t;
            double z = sz + (tz - sz) * t;
            BlockPos sample = new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
            if (sample.equals(target)) {
                return null; // 无挡路
            }
            BlockState st = level.getBlockState(sample);
            if (st.isAir() || oreValueOf(st) != null || !st.isRedstoneConductor(level, sample)) {
                continue;
            }
            // v1.1.0 实测二十三：岩浆格视作硬阻挡——湖心矿若因缓存轮/预算恰好被
            // 选中，走到这里把岩浆判为"不可开路"的挡路块 → 立即报点弃置
            // （进 30 秒短时排除名单），不再原地空转等 300 tick 目标超时
            if (isLavaBlock(st.getBlock())) {
                return new Blocker(sample.immutable(), false);
            }
            // v1.1.0：自己 10 秒内搭的方块不当挡路块挖——搭高/搭桥的方块恰好挡住射线时，
            // 旧版会把它当障碍物挖掉 → 掉下来 → 再搭 → 再挖 死循环；搭的方块到期自动销毁，
            // 挖穿判断直接跳过它们（找后面的真实障碍）
            if (isMiningPlaced(level, sample)) {
                continue;
            }
            net.minecraft.resources.ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock());
            boolean openable = key != null && isBreakable(key.getPath());
            return new Blocker(sample.immutable(), openable);
        }
        return null;
    }

    /** v1.1.0 实测二十三：是否岩浆方块（静态/流动都是 minecraft:lava，与自保
     *  SelfPreservationBehavior.isLavaBlock 同款判定） */
    private static boolean isLavaBlock(net.minecraft.world.level.block.Block b) {
        net.minecraft.resources.ResourceLocation key =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b);
        return key != null && "minecraft:lava".equals(key.toString());
    }

    /** 实测九百七十九：分帧扫描进行中她的"站着等"是正常的，不打卡死表 */
    @Override
    protected boolean stallWatchdogSuppressed(ServerLevel level, EntityMaid maid) {
        return MINE_SCANS.containsKey(maid.getId());
    }

    /** 实测九百七十九：卡死弃目标的收尾——把这轮进度落盘（目标本身已进 30 秒短排） */
    @Override
    protected void onStallGiveUp(ServerLevel level, EntityMaid maid, long gameTime, BlockPos dead) {
        this.saveProgressNow(maid);
    }

    /** 实测九百七十九：连卡 3 次 = 这一框没戏 → 清缓存 + 挪锚点换一片 */
    @Override
    protected void onStallStreak(ServerLevel level, EntityMaid maid, long gameTime) {
        ORE_CACHE.remove(maid.getId());
        MINE_SCANS.remove(maid.getId());
        this.scanCooldown = 0;
        BlockPos anchor = this.resolveAnchor(level, maid);
        if (anchor != null) {
            this.slideAnchor(level, maid, anchor);
        }
        LOGGER.info("mine stall x3 -> fresh box: maid={} anchor={}", maid.getUUID(), anchor);
    }

    /** v1.5.47：废石丢弃——每种保留 JUNK_KEEP 份，超出直接销毁（不生成掉落物，防被捡回）
     *  v1.5.88：保留量从配置面板读取（mine.junkKeep） */
    private void maybeDropJunk(EntityMaid maid) {
        int keep = com.maidsmart.config.MaidSmartConfig.MINE_JUNK_KEEP.get();
        IItemHandler inv = maid.getAvailableBackpackInv();
        java.util.Map<Item, Integer> counts = new HashMap<>();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi)) {
                continue;
            }
            net.minecraft.resources.ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(bi.getBlock());
            if (key != null && JUNK_STONES.contains(key.getPath())) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        for (java.util.Map.Entry<Item, Integer> e : counts.entrySet()) {
            if (e.getValue() <= keep) {
                continue;
            }
            int excess = e.getValue() - keep;
            for (int i = 0; i < inv.getSlots() && excess > 0; i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (!stack.isEmpty() && stack.getItem() == e.getKey()) {
                    int take = Math.min(excess, stack.getCount());
                    inv.extractItem(i, take, false);
                    excess -= take;
                }
            }
        }
    }
}
