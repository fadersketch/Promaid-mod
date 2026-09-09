package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.NeoForge;

/**
 * v1.1.0 实测二百二十八（用户："种树逻辑直接分开来——手上有树苗就随手种一个；
 * 在一定范围内判定周围有没有树苗和可种地块，没有就随手种一个；与伐木不相关，
 * 但触发仍然是伐木这个模式"）：
 *
 * 「随手种树」独立模块——逻辑与伐木完全分离（不读伐木的任何目标/状态），
 * 由 MaidWoodBehavior 每 20 tick 调起一次（触发 = 伐木模式；不做独立总开关）。
 *
 * 行为：冷却跳表（默认 5 秒，伐木面板「补种树苗冷却」可调）后——
 * 1. 背包有树苗（ItemNameBlockItem 且方块带 #minecraft:saplings——原版+模组树苗兼容）；
 *    没有则先扫身边（XZ 6 × Y ±6）树苗掉落物捡进来（伐木中拾取任务让位，树叶掉的苗落地后捡不到）；
 * 2. 身边（半径 6 格立方体、垂直 ±2）找【可种地块】：空气格 + 脚下 #minecraft:dirt 或草方块
 *    + 格内无存活实体占用 + 不是女仆自己站的那格——取离女仆最近的；
 * 3. 种下（音效粒子 levelEvent 2001 + 摆臂 + extractItem 消耗 1），记入冷却；
 * 4. 找不到苗/地块 → 只记冷却重试（不播报，防刷屏）。
 */
public final class MaidPlanting {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** 随手种树的搜索半径（格）——用户说"在一定范围内" */
    private static final int RADIUS = 6;
    /** 树苗物品判定标签（原版+模组树苗） */
    private static final net.minecraft.tags.TagKey<Block> SAPLINGS_TAG =
            net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
                    net.minecraft.resources.ResourceLocation.parse("minecraft:saplings"));
    /** 实测三百五十一：树叶标签——干列净空检查里树叶放行（原版 TreeFeature.isFree：
     *  树干可以穿过树叶生长，实心方块才会卡死） */
    private static final net.minecraft.tags.TagKey<Block> LEAVES_TAG =
            net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
                    net.minecraft.resources.ResourceLocation.parse("minecraft:leaves"));
    /**
     * v1.1.0 实测三百五十一：干列净空高度（种点上方连续 N 格必须能穿过树干）。
     * 依据：橡树干 4~6 格、桦树最高 7 格（原版树高分布）；树冠是"尽力而为"
     * （树叶放不下就剪枝，树照样长），只有【树干列被实心方块挡住】整棵树才
     * 100% 长不出来——所以只查干列，不查树冠。
     */
    private static final int TRUNK_CLEARANCE = 7;

    /** 冷却表（女仆实体 ID → 上次种植/尝试 tick），默认 100 tick = 5 秒 */
    private static final java.util.Map<Integer, Long> PLANT_SINCE = new java.util.HashMap<>();
    /** 跳过原因日志限频表（女仆实体 ID → 上次记录 tick，60 秒一条防刷屏） */
    private static final java.util.Map<Integer, Long> PLANT_LOG_SINCE = new java.util.HashMap<>();

    /** v1.1.0 实测二百三十五（二次）：诊断升级——每次扫描都落盘（去掉 20 秒节流，
     *  调试期每 2 秒/女仆一条可接受）；成功永远记。 */
    private static void logAttempt(ServerLevel level, EntityMaid maid, String result,
                                   int bagSaplings, int handSaplings, int spots) {
        try {
            LOGGER.info("plant scan: maid={} result={} bagSaplings={} handSaplings={} spots={} pos={}",
                    maid.getUUID(), result, bagSaplings, handSaplings, spots, maid.blockPosition());
        } catch (Exception ignored) {
        }
    }

    private MaidPlanting() {
    }

    // ================= 任务级驱动（参考 maid_useful_task 的种树语义） =================
    // v1.1.0 实测二百三十三（用户提供参考 jar [女仆实用任务]maid_useful_task-1.4.2）：
    // 参考模组的种树是【任务级】——只要女仆选着伐木任务，TLM 原生放置机就持续工作，
    // 与"砍树行为是否有目标/是否运行窗口"无关。我们旧版把检查挂在伐木【行为 tick】
    // 里：行为只在实际砍树窗口运行（日志实证每 20~30 秒启停一次），窗口外检查不跑
    // ——"明明包里有苗却不种"最合理的解释（用户自检：超平坦地表草方块 + 包里有苗，
    // 判定链条本身无懈可击）。本模块改为自己监听 ServerTickEvent：每 40 tick（2 秒）
    // 扫一遍全部加载女仆，任务 == maid_smart:woodcut 才调 tick（触发仍是伐木模式）。
    private static boolean registered = false;
    private static int serverTickCounter = 0;
    private static boolean driverLoggedAlive = false;
    private static boolean driverLoggedMaid = false;

    /** ProMaidExtension 构造器调用：注册服务端 tick 监听（幂等）。 */
    public static void ensureRegistered() {
        if (registered) {
            return;
        }
        registered = true;
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(MaidPlanting.class);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
// v1.1.0 实测三百五十三：驱动节拍从 40 tick 加密到 10 tick（0.5 秒）——
        // 骨粉催熟按用户要求 0.5 秒尝试一次（原版 isBonemealSuccess 是 45% 概率
        // 判定，太慢的节拍观感就是"催熟积极性不高"）；种树本身仍每 40 tick 一轮
        if (++serverTickCounter % 10 != 0) {
            return;
        }
        boolean plantPhase = serverTickCounter % 40 == 0; // 每 2 秒一轮种树
        if (!driverLoggedAlive) {
            driverLoggedAlive = true;
            LOGGER.info("plant driver: alive (ServerTick driver registered)");
        }
        net.minecraft.server.MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        try {
            for (net.minecraft.server.level.ServerLevel lvl : server.getAllLevels()) {
                if (lvl == null) {
                    continue;
                }
                for (net.minecraft.world.entity.Entity e : lvl.getAllEntities()) {
                    if (!(e instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid)
                            || !maid.isAlive()) {
                        continue;
                    }
                    try {
                        // 实测三百五十四：施肥停止 1 秒后还原主手——对所有女仆生效
                        //（含刚切走伐木任务的：手上还举着骨粉要放回去）
                        tryRestoreHand(maid, maid.getId(), lvl.getGameTime());
                        com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask task = maid.getTask();
                        if (task == null || task.getUid() == null) {
                            continue;
                        }
                        if (!"maid_smart:woodcut".equals(task.getUid().toString())) {
                            continue; // 触发 = 伐木模式
                        }
                        if (!driverLoggedMaid) {
                            driverLoggedMaid = true;
                            LOGGER.info("plant driver: sees woodcut maid {} task={}",
                                    maid.getUUID(), task.getUid());
                        }
                        if (plantPhase) {
                            tick(lvl, maid);
                        }
                        tryBonemealSapling(lvl, maid);
                    } catch (Throwable t) {
                        LOGGER.error("plant driver per-maid error", t);
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.error("plant driver error", t);
        }
    }

    /** 实体卸载清理（MaidWoodBehavior.forget 调用） */
    public static void forget(int maidEntityId) {
        PLANT_SINCE.remove(maidEntityId);
        // 实测三百五十四：换手持骨粉状态一并清（女仆已卸载——手上若还举着骨粉，
        // 她重载进来后会由 tryRestoreHand 按 restoreAt 到期还原）
        HAND_HOLD.remove(maidEntityId);
    }

    /** 全部登记中的女仆 id（MaidWoodBehavior 的 purge 并集用——防本表条目被漏清） */
    public static java.util.Set<Integer> knownIds() {
        java.util.Set<Integer> out = new java.util.HashSet<>(PLANT_SINCE.keySet());
        out.addAll(HAND_HOLD.keySet());
        return out;
    }

    /** 清理已不在位女仆的条目（aliveChecker=存活女仆判定） */
    public static void purgeStale(java.util.function.Predicate<Integer> aliveChecker) {
        PLANT_SINCE.keySet().removeIf(id -> !aliveChecker.test(id));
    }

    /** 由伐木行为每 20 tick 调起；内部 5 秒冷却；总开关 wood.plantSaplingEnabled（默认开）。 */
    public static void tick(ServerLevel level, EntityMaid maid) {
        try {
            if (!com.maidsmart.config.MaidSmartConfig.WOOD_PLANT_SAPLING_ENABLED.get()) {
                return; // 开关关闭：只砍树不种树
            }
            int id = maid.getId();
            long now = level.getGameTime();
            Long last = PLANT_SINCE.get(id);
            int cd = com.maidsmart.config.MaidSmartConfig.WOOD_PLANT_SAPLING_COOLDOWN.get();
            if (last != null && now - last < cd) {
                return; // 冷却中
            }
            PLANT_SINCE.put(id, now + cd); // 无论成败都进冷却（避免每 20 tick 全量扫描）
            // 0) 统计（每 20 秒落一条日志用：本 tick 是否真的执行、背包/手里多少苗）
            int bagCount = 0;
            int handCount = 0;
            int scanCount = 0;
            try {
                net.neoforged.neoforge.items.IItemHandler inv = maid.getMaidInv();
                for (int i = 0; i < inv.getSlots(); i++) {
                    if (isSaplingItem(inv.getStackInSlot(i))) {
                        bagCount += inv.getStackInSlot(i).getCount();
                    }
                }
                for (int h = 0; h < 2; h++) {
                    ItemStack hs = h == 0 ? maid.getMainHandItem() : maid.getOffhandItem();
                    if (isSaplingItem(hs)) {
                        handCount += hs.getCount();
                    }
                }
            } catch (Exception ignored) {
            }
            // 1) 树苗：主手 → 副手 → 背包；都没有才捡身边掉落物
            // 实测二百三十（用户："女仆手中拿的是云杉树苗"）：旧版只扫背包
            // （getMaidInv），手拿苗永远判"没苗"——手的槽位在独立手部栏
            int handSlot = -1;
            int bagSlot = -1;
            if (isSaplingItem(maid.getMainHandItem())) {
                handSlot = 0; // 主手
            } else if (isSaplingItem(maid.getOffhandItem())) {
                handSlot = 1; // 副手
            }
            net.minecraft.world.item.ItemStack sapling = null;
            if (handSlot >= 0) {
                sapling = handSlot == 0 ? maid.getMainHandItem() : maid.getOffhandItem();
            } else {
                try {
                    net.neoforged.neoforge.items.IItemHandler inv = maid.getMaidInv();
                    for (int i = 0; i < inv.getSlots(); i++) {
                        ItemStack stack = inv.getStackInSlot(i);
                        if (isSaplingItem(stack)) {
                            bagSlot = i;
                            sapling = stack;
                            break;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            if (sapling == null) {
                pickupNearbySaplings(level, maid, maid.blockPosition());
                try {
                    net.neoforged.neoforge.items.IItemHandler inv = maid.getMaidInv();
                    for (int i = 0; i < inv.getSlots(); i++) {
                        ItemStack stack = inv.getStackInSlot(i);
                        if (isSaplingItem(stack)) {
                            bagSlot = i;
                            sapling = stack;
                            break;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            if (sapling == null) {
                logAttempt(level, maid, "no-sapling", bagCount, handCount, -1);
                return; // 没苗：冷却后重试
            }
            // 2) 找最近的可种地块（统计扫描范围内的合格格数：0 = 真没土块）
            int[] spotInfo = findPlantSpotCount(level, maid);
            net.minecraft.core.BlockPos spot = spotInfo.length > 0
                    ? new net.minecraft.core.BlockPos(spotInfo[1], spotInfo[2], spotInfo[3]) : null;
            if (spot == null) {
                logAttempt(level, maid, "no-spot", bagCount, handCount, 0);
                return; // 范围内没有可种土块：冷却后重试
            }
            // 3) 种下（消耗对应来源格：手部栏 extractItem / 背包 extractItem）
            // 实测二百三十七：强转改 BlockItem——物品标签口径下的树苗可能是普通
            // BlockItem 子类（mangrove_propagule 等），ItemNameBlockItem 强转会炸
            Block saplingBlock = ((net.minecraft.world.item.BlockItem) sapling.getItem()).getBlock();
            level.setBlock(spot, saplingBlock.defaultBlockState(), 3);
            level.levelEvent(2001, spot, Block.getId(saplingBlock.defaultBlockState()));
            try {
                if (handSlot >= 0) {
                    ((net.neoforged.neoforge.items.IItemHandlerModifiable) maid.getHandsInvWrapper())
                            .extractItem(handSlot, 1, false);
                } else {
                    maid.getMaidInv().extractItem(bagSlot, 1, false);
                }
            } catch (Exception ignored) {
            }
            maid.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            LOGGER.info("plant sapling: maid={} pos={} sapling={}",
                    maid.getUUID(), spot, net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(saplingBlock));
        } catch (Throwable t) {
            LOGGER.error("plant tick error", t);
        }
    }

    /** v1.1.0 实测二百三十一：findPlantSpot 的统计版——返回 [找到(0/1), x, y, z]；
     *  count=0 即"范围内确实没有可种土块"。
     *  v1.1.0 实测三百五十（用户："原来是仅检查这周围有没有树苗，现在在此基础
     *  上进一步要求3×3范围内没有碰撞体积方块才可以种植，火把什么的不算"）：
     *  候选格在"空气 + 脚下泥土"之外，再要求以该格为中心的 3×3（同层）没有
     *  【带碰撞体积】的方块——树苗有生长空间，不会被墙/箱子闷死；火把、
     *  花草等无碰撞方块照常放行（getCollisionShape isEmpty 口径）。 */
    private static int[] findPlantSpotCount(ServerLevel level, EntityMaid maid) {
        net.minecraft.core.BlockPos feet = maid.blockPosition();
        net.minecraft.core.BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    net.minecraft.core.BlockPos p = feet.offset(dx, dy, dz);
                    if (!level.getBlockState(p).isAir()) {
                        continue; // 格内已被占用
                    }
                    net.minecraft.world.level.block.state.BlockState under =
                            level.getBlockState(p.offset(0, -1, 0));
                    if (!(under.is(net.minecraft.tags.BlockTags.DIRT) /* #minecraft:dirt */
                            || under.is(net.minecraft.world.level.block.Blocks.SNOW) /* grass_block */)) {
                        continue;
                    }
                    if (!areaClearOfCollision(level, p)) {
                        continue; // 实测三百五十：3×3 内有碰撞方块（墙/箱子等）不种
                    }
                    // 实测三百五十一：干列净空 + 光照——种下去长不出来的格子不浪费树苗
                    if (!hasGrowthSpace(level, p)) {
                        continue; // 上方 7 格内有实心方块（天花板/桥面）——树干顶死长不成
                    }
                    if (level.getMaxLocalRawBrightness(p.above()) < 9) {
                        continue; // 原版 randomTick 同款条件：光照 <9 永不自然生长（地下/屋内）
                    }
                    if (p.equals(feet)) {
                        best = p; // 树桩格允许（见 findPlantSpot 注释）
                        break;
                    }
                    net.minecraft.world.phys.AABB cellBox =
                            new net.minecraft.world.phys.AABB(p.getX(), p.getY(), p.getZ(),
                                    p.getX() + 1.0, p.getY() + 1.0, p.getZ() + 1.0);
                    if (!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, cellBox,
                            e -> e.isAlive()).isEmpty()) {
                        continue;
                    }
                    double d = maid.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
                    if (d < bestD) {
                        bestD = d;
                        best = p;
                    }
                }
            }
        }
        return best == null ? new int[]{0, 0, 0, 0}
                : new int[]{1, best.getX(), best.getY(), best.getZ()};
    }

    /**
     * v1.1.0 实测三百五十：以 center 为中心的 3×3（同一层）是否有【带碰撞体积】
     * 的方块——有任一格挡碰撞就 false（树苗被闷住长不成树）。判定用原版碰撞箱：
     * getCollisionShape(...).isEmpty()——火把/花草/地毯等无碰撞方块 isEmpty=true
     * 照常放行（与自保安全落点 standableCell 同款口径）。
     */
    private static boolean areaClearOfCollision(ServerLevel level, net.minecraft.core.BlockPos center) {
        for (int ddx = -1; ddx <= 1; ddx++) {
            for (int ddz = -1; ddz <= 1; ddz++) {
                net.minecraft.core.BlockPos q = center.offset(ddx, 0, ddz);
                if (!level.getBlockState(q).getCollisionShape(level, q,
                        net.minecraft.world.phys.shapes.CollisionContext.empty()).isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * v1.1.0 实测三百五十一：干列净空——种点上方 1~TRUNK_CLEARANCE 格内没有
     * 【挡住树干】的方块。镜像原版 TreeFeature.isFree（javap 实证：树干可放置于
     * 空气 / 可替换方块 / 树叶——树叶能被树干穿过，实心方块才会让整棵树
     * 生长失败）。判定：空气 → 过；树叶标签 → 过；无碰撞（火把/花草等，
     * getCollisionShape isEmpty，同可替换植物口径）→ 过；其余（土/石/木板/
     * 玻璃等实心）→ false。
     */
    private static boolean hasGrowthSpace(ServerLevel level, net.minecraft.core.BlockPos spot) {
        for (int i = 1; i <= TRUNK_CLEARANCE; i++) {
            net.minecraft.core.BlockPos q = spot.offset(0, i, 0);
            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(q);
            if (st.isAir()) {
                continue; // isAir
            }
            if (st.is(LEAVES_TAG)) {
                continue; // 树叶可被树干穿过（原版 isFree 口径）
            }
            if (st.getCollisionShape(level, q,
                    net.minecraft.world.phys.shapes.CollisionContext.empty()).isEmpty()) {
                continue; // 无碰撞方块（火把/花草/地毯——同可替换植物放行）
            }
            return false; // 实心方块：树干顶死，整棵树长不出来
        }
        return true;
    }

    /**
     * v1.1.0 实测三百五十二：骨粉催熟——对身边（半径 RADIUS、垂直 ±2）最近的一株
     * 合格树苗使用背包里的骨粉。实测三百五十三（用户："催熟的积极性不高；树苗
     * 没有冒出粒子特效；施肥速度应该是每 0.5 秒尝试一次"）调整：
     * ① 节拍独立于种树冷却，由驱动每 10 tick（0.5 秒）调一次；
     * ② 修双扣——growCrop（growCrop）尾部自带 shrink(1)（javap 实证 offset 83，
     * 45% 判定成败都扣），旧版再手动 extractItem = 每次扣 2；
     * ③ 补粒子——growCrop 本身不放 1505（原版粒子在玩家 useOn 路径），每次
     * 施肥手动 levelEvent(1505) 给视觉反馈。
     * 选择条件不变：SaplingBlock 实例 + 非深色橡树（单株永不生长）+ 干列净空；
     * 【不查光照】——骨粉不经过原版 randomTick 的光照门槛（地下/室内也能催熟）。
     */
    private static void tryBonemealSapling(ServerLevel level, EntityMaid maid) {
        if (!com.maidsmart.config.MaidSmartConfig.MISC_MAID_BONEMEAL_SAPLING.get()) {
            return;
        }
        // 实测三百六十：伐木优先——正在砍树/找树（伐木工作占用）时不施肥，
        // 避免斧头和骨粉来回抢主手（鬼畜）；伐木空闲（没树可砍/在找树间隙）才催
        if (com.maidsmart.task.MaidWoodBehavior.isWooding(maid)) {
            return;
        }
        net.minecraft.world.item.Item boneMeal = boneMealItem();
        if (boneMeal == null) {
            return;
        }
        int id = maid.getId();
        long now = level.getGameTime();
        // v1.1.0 实测三百五十四：找骨粉 主手 → 副手 → 背包 + 换手持骨粉
        //（实测三百五十五：抽成 equipBoneMeal 供农场施肥复用）
        if (!equipBoneMeal(maid, id, now)) {
            return; // 手上和背包都没有骨粉
        }
        net.minecraft.core.BlockPos feet = maid.blockPosition();
        net.minecraft.core.BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    net.minecraft.core.BlockPos p = feet.offset(dx, dy, dz);
                    net.minecraft.world.level.block.state.BlockState st = level.getBlockState(p);
                    if (!(st.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock)) {
                        continue;
                    }
                    net.minecraft.resources.ResourceLocation key =
                            net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock());
                    if (key != null && "dark_oak_sapling".equals(key.getPath())) {
                        continue; // 深色橡树单株永不生长，不浪费骨粉
                    }
                    if (!hasGrowthSpace(level, p)) {
                        continue; // 树干上方被实心方块挡死——骨粉也长不成
                    }
                    double d = maid.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5,
                            p.getZ() + 0.5);
                    if (d < bestD) {
                        bestD = d;
                        best = p;
                    }
                }
            }
        }
        if (best == null) {
            return; // 身边没有合格树苗
        }
        // growCrop：isValidBonemealTarget → isBonemealSuccess（树苗 45% 概率，原版
        // 判定）→ performBonemeal（进阶/长成）→ 自 shrink(1)——骨粉消耗它自己管
        ItemStack boneStack = maid.getMainHandItem();
        boolean ok = net.minecraft.world.item.BoneMealItem.growCrop(boneStack, level, best);
        if (ok) {
            // growCrop 不放 1505（原版粒子在 useOn 路径）——每次施肥都给粒子反馈
            level.levelEvent(1505, best, 15);
            maid.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            LOGGER.info("bone meal sapling: maid={} pos={}", maid.getUUID(), best);
        }
    }

    /** 骨粉物品懒加载（注册表按名查询一次并缓存；类加载早于注册表冻结时不炸） */
    private static net.minecraft.world.item.Item BONE_MEAL_CACHE = null;
    private static boolean BONE_MEAL_RESOLVED = false;

    public static net.minecraft.world.item.Item boneMealItem() {
        if (!BONE_MEAL_RESOLVED) {
            try {
                BONE_MEAL_CACHE = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                        net.minecraft.resources.ResourceLocation.parse("minecraft:bone_meal"));
            } catch (Exception ignored) {
            }
            BONE_MEAL_RESOLVED = true;
        }
        return BONE_MEAL_CACHE;
    }

    /**
     * v1.1.0 实测三百五十四：施肥换手持骨粉状态。实测三百六十【状态机重写】
     * （用户："工具和骨粉之间反复切换的鬼畜；有概率刷出背包里面出现大量道具"）：
     * 旧版把原主手物品 insert 进背包槽、还原时又 setStackInSlot(0, original)——
     * 【同一个 ItemStack 实例同时被背包槽和主手槽引用】（背包槽旧引用未清），
     * 每次换手循环留一个幽灵引用 → 存档时同一实例重复序列化 → 读档刷出道具。
     * 新状态机：
     * ① 原主手物品放【副手】（随实体持久化——关服/存档都不丢），不在内存记账、
     *    不进背包，任何实例任一时刻只存在于一个槽位；
     * ② 全链路 extract-then-place：每个槽位写入前先把旧实例完整取走（拿引用 +
     *    setStackInSlot(EMPTY)），杜绝共享引用；
     * ③ HAND_HOLD 只记还原时刻（maidId → restoreAt tick）。
     */
    private static final java.util.Map<Integer, Long> HAND_HOLD = new java.util.HashMap<>();

    /**
     * 把骨粉换到主手（树苗/农场施肥共用）：主手已是骨粉 → 刷新还原时刻直接返回
     * true；否则骨粉整组拉到主手、原主手物品放副手暂存（副手旧物收背包）；
     * 没有骨粉 → false。
     */
    public static boolean equipBoneMeal(EntityMaid maid, int id, long now) {
        net.minecraft.world.item.Item boneMeal = boneMealItem();
        if (boneMeal == null) {
            return false;
        }
        try {
            net.neoforged.neoforge.items.IItemHandlerModifiable hands =
                    (net.neoforged.neoforge.items.IItemHandlerModifiable) maid.getHandsInvWrapper();
            ItemStack main = maid.getMainHandItem();
            if (!main.isEmpty() && main.getItem() == boneMeal) {
                if (HAND_HOLD.containsKey(id)) {
                    HAND_HOLD.put(id, now + 20L);
                }
                return true; // 主手已是骨粉（含换手存续中）——直接用
            }
            boolean holding = HAND_HOLD.containsKey(id);
            // 找骨粉：副手 → 背包（换手存续中副手是暂存的原物品，自然落到背包找）
            int boneSlot = -1;
            ItemStack boneStack = null;
            ItemStack off = maid.getOffhandItem();
            if (!off.isEmpty() && off.getItem() == boneMeal) {
                boneStack = off;
            } else {
                net.neoforged.neoforge.items.IItemHandler inv = maid.getMaidInv();
                for (int i = 0; i < inv.getSlots(); i++) {
                    ItemStack st = inv.getStackInSlot(i);
                    if (!st.isEmpty() && st.getItem() == boneMeal) {
                        boneSlot = i;
                        boneStack = st;
                        break;
                    }
                }
            }
            if (boneStack == null) {
                return false; // 手上和背包都没有骨粉
            }
            ItemStack pulled = boneStack == off
                    ? hands.extractItem(1, boneStack.getCount(), false)
                    : maid.getMaidInv().extractItem(boneSlot, boneStack.getCount(), false);
            if (pulled.isEmpty()) {
                return false;
            }
            // 取走当前主手物品（实例脱离槽位）
            ItemStack curMain = hands.getStackInSlot(0);
            hands.setStackInSlot(0, ItemStack.EMPTY);
            if (holding) {
                // 换手存续中（原物品在副手）：主手是第三方顶进来的工具 → 收背包
                if (!curMain.isEmpty()) {
                    ItemStack left = net.neoforged.neoforge.items.ItemHandlerHelper
                            .insertItemStacked(maid.getMaidInv(), curMain, false);
                    if (!left.isEmpty()) {
                        maid.spawnAtLocation(left, 0.5f);
                    }
                }
                HAND_HOLD.put(id, now + 20L);
            } else {
                // 首次换手：原主手物品 → 副手暂存（副手旧物 → 背包，实例脱离后再放）
                ItemStack offOld = hands.getStackInSlot(1);
                hands.setStackInSlot(1, ItemStack.EMPTY);
                if (!offOld.isEmpty()) {
                    ItemStack left = net.neoforged.neoforge.items.ItemHandlerHelper
                            .insertItemStacked(maid.getMaidInv(), offOld, false);
                    if (!left.isEmpty()) {
                        maid.spawnAtLocation(left, 0.5f);
                    }
                }
                hands.setStackInSlot(1, curMain);
                HAND_HOLD.put(id, now + 20L);
            }
            hands.setStackInSlot(0, pulled);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 实测三百六十一：该女仆是否处于施肥换手暂存中（主手是骨粉、原物品在副手） */
    public static boolean isHandHolding(int id) {
        return HAND_HOLD.containsKey(id);
    }

    /** 施肥停止后还原主手（驱动每 0.5 秒对所有女仆调用，含非伐木——她可能刚切走任务） */
    public static void tryRestoreHand(EntityMaid maid, int id, long now) {
        Long expire = HAND_HOLD.get(id);
        if (expire == null || now < expire) {
            return;
        }
        HAND_HOLD.remove(id);
        restoreNow(maid);
    }

    /**
     * 立即还原主手（不等 1 秒宽限）——锄地/自动装备需要主手时调用（农场锄地前
     * 还原：骨粉收回背包、副手暂存的原物品放回主手，锄头才能干净地换上来）。
     */
    public static void forceRestoreHand(EntityMaid maid, int id) {
        if (HAND_HOLD.remove(id) == null) {
            return;
        }
        restoreNow(maid);
    }

    /** 还原本体（实测三百六十：extract-then-place——骨粉收背包、副手暂存物回主手） */
    private static void restoreNow(EntityMaid maid) {
        try {
            net.neoforged.neoforge.items.IItemHandlerModifiable hands =
                    (net.neoforged.neoforge.items.IItemHandlerModifiable) maid.getHandsInvWrapper();
            ItemStack cur = hands.getStackInSlot(0);
            net.minecraft.world.item.Item bone = boneMealItem();
            if (!cur.isEmpty() && cur.getItem() == bone) {
                // 手上还是我们的骨粉 → 收回背包（满则掉在脚下），防丢
                hands.setStackInSlot(0, ItemStack.EMPTY);
                ItemStack left = net.neoforged.neoforge.items.ItemHandlerHelper
                        .insertItemStacked(maid.getMaidInv(), cur, false);
                if (!left.isEmpty()) {
                    maid.spawnAtLocation(left, 0.5f);
                }
            }
            // 副手暂存的原物品 → 主手（主手被第三方占着则收背包，不强抢）
            ItemStack held = hands.getStackInSlot(1);
            if (!held.isEmpty()) {
                hands.setStackInSlot(1, ItemStack.EMPTY);
                if (hands.getStackInSlot(0).isEmpty()) {
                    hands.setStackInSlot(0, held);
                } else {
                    ItemStack left = net.neoforged.neoforge.items.ItemHandlerHelper
                            .insertItemStacked(maid.getMaidInv(), held, false);
                    if (!left.isEmpty()) {
                        maid.spawnAtLocation(left, 0.5f);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 捡起身边（XZ 6 × Y ±6）掉落在地上的树苗（伐木中拾取任务让位，树叶掉的苗捡不到） */
    private static void pickupNearbySaplings(ServerLevel level, EntityMaid maid, net.minecraft.core.BlockPos base) {
        try {
            net.minecraft.world.phys.AABB box =
                    new net.minecraft.world.phys.AABB(base).inflate(RADIUS + 2.0);
            for (net.minecraft.world.entity.item.ItemEntity e :
                    level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box)) {
                if (e == null || !e.isAlive()) {
                    continue;
                }
                double dx = e.getX() - (base.getX() + 0.5);
                double dy = e.getY() - (base.getY() + 0.5);
                double dz = e.getZ() - (base.getZ() + 0.5);
                // v1.1.0 实测二百三十二：垂直范围改回 ±6（去除 -6..+12 放宽——用户指定改回）
                if (Math.abs(dx) > RADIUS || Math.abs(dy) > RADIUS || Math.abs(dz) > RADIUS) {
                    continue;
                }
                if (isSaplingItem(e.getItem())) {
                    try {
                        maid.pickupItem(e, false);
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 是否为树苗物品：优先物品标签 #minecraft:saplings（参考 maid_useful_task 同款——
     *  云杉/橡/桦等全部树苗 + 登记标签的模组苗自动兼容；实测二百三十七：该标签还涵盖
     *  mangrove_propagule 等【普通 BlockItem 子类】的幼苗——物品标签分支必须验
     *  BlockItem（旧版放行了非 ItemNameBlockItem 的块物品，种植强转当场
     *  ClassCastException→整条种树链 silently 崩溃）；兜底 ItemNameBlockItem+方块标签。
     *  v1.1.0 实测三百五十一：深色橡树苗排除——javap 实证 DarkOakTreeGrower 的
     *  单株特征 getConfiguredFeature 返回 null（只有 2×2 mega 特征），单株深色橡
     *  树苗【永不生长】；女仆只会单种，种了就是白种还白占冷却，捡取/种植一并
     *  排除（按注册名判定，模组自定义 2×2 树可按需扩展黑名单）。 */
    public static boolean isSaplingItem(ItemStack stack) {
        try {
            if (stack.isEmpty()) {
                return false;
            }
            if (!(stack.getItem() instanceof net.minecraft.world.item.BlockItem)) {
                return false;
            }
            Block b = ((net.minecraft.world.item.BlockItem) stack.getItem()).getBlock();
            if (b == null) {
                return false;
            }
            net.minecraft.resources.ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b);
            if (key != null && "dark_oak_sapling".equals(key.getPath())) {
                return false; // 单株永不生长（原版必须 2×2 四株）
            }
            if (stack.is(net.minecraft.tags.ItemTags.SAPLINGS)) {
                return true;
            }
            return stack.getItem() instanceof net.minecraft.world.item.ItemNameBlockItem
                    && b.defaultBlockState().is(SAPLINGS_TAG);
        } catch (Exception ignored) {
            return false;
        }
    }
}
