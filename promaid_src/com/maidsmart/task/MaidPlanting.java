package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

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
            net.minecraft.tags.BlockTags.create(
                    net.minecraft.resources.ResourceLocation.parse("minecraft:saplings"));

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
                    maid.m_20148_(), result, bagSaplings, handSaplings, spots, maid.m_20183_());
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
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(MaidPlanting.class);
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        if (++serverTickCounter % 40 != 0) {
            return; // 每 2 秒一轮
        }
        if (!driverLoggedAlive) {
            driverLoggedAlive = true;
            LOGGER.info("plant driver: alive (ServerTick driver registered)");
        }
        net.minecraft.server.MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        try {
            for (net.minecraft.server.level.ServerLevel lvl : server.m_129785_()) {
                if (lvl == null) {
                    continue;
                }
                for (net.minecraft.world.entity.Entity e : lvl.m_8583_()) {
                    if (!(e instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid)
                            || !maid.m_6084_()) {
                        continue;
                    }
                    try {
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
                                    maid.m_20148_(), task.getUid());
                        }
                        tick(lvl, maid);
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
    }

    /** 全部登记中的女仆 id（MaidWoodBehavior 的 purge 并集用——防本表条目被漏清） */
    public static java.util.Set<Integer> knownIds() {
        return new java.util.HashSet<>(PLANT_SINCE.keySet());
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
            int id = maid.m_19879_();
            long now = level.m_46467_();
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
                net.minecraftforge.items.IItemHandler inv = maid.getMaidInv();
                for (int i = 0; i < inv.getSlots(); i++) {
                    if (isSaplingItem(inv.getStackInSlot(i))) {
                        bagCount += inv.getStackInSlot(i).m_41613_();
                    }
                }
                for (int h = 0; h < 2; h++) {
                    ItemStack hs = h == 0 ? maid.m_21205_() : maid.m_21206_();
                    if (isSaplingItem(hs)) {
                        handCount += hs.m_41613_();
                    }
                }
            } catch (Exception ignored) {
            }
            // 1) 树苗：主手 → 副手 → 背包；都没有才捡身边掉落物
            // 实测二百三十（用户："女仆手中拿的是云杉树苗"）：旧版只扫背包
            // （getMaidInv），手拿苗永远判"没苗"——手的槽位在独立手部栏
            int handSlot = -1;
            int bagSlot = -1;
            if (isSaplingItem(maid.m_21205_())) {
                handSlot = 0; // 主手
            } else if (isSaplingItem(maid.m_21206_())) {
                handSlot = 1; // 副手
            }
            net.minecraft.world.item.ItemStack sapling = null;
            if (handSlot >= 0) {
                sapling = handSlot == 0 ? maid.m_21205_() : maid.m_21206_();
            } else {
                try {
                    net.minecraftforge.items.IItemHandler inv = maid.getMaidInv();
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
                pickupNearbySaplings(level, maid, maid.m_20183_());
                try {
                    net.minecraftforge.items.IItemHandler inv = maid.getMaidInv();
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
            Block saplingBlock = ((net.minecraft.world.item.ItemNameBlockItem) sapling.m_41720_()).m_40614_();
            level.m_7731_(spot, saplingBlock.m_49966_(), 3);
            level.m_46796_(2001, spot, Block.m_49956_(saplingBlock.m_49966_()));
            try {
                if (handSlot >= 0) {
                    ((net.minecraftforge.items.IItemHandlerModifiable) maid.getHandsInvWrapper())
                            .extractItem(handSlot, 1, false);
                } else {
                    maid.getMaidInv().extractItem(bagSlot, 1, false);
                }
            } catch (Exception ignored) {
            }
            maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
            LOGGER.info("plant sapling: maid={} pos={} sapling={}",
                    maid.m_20148_(), spot, ForgeRegistries.BLOCKS.getKey(saplingBlock));
        } catch (Throwable t) {
            LOGGER.error("plant tick error", t);
        }
    }

    /** v1.1.0 实测二百三十一：findPlantSpot 的统计版——返回 [找到(0/1), x, y, z]；
     *  count=0 即"范围内确实没有可种土块"。 */
    private static int[] findPlantSpotCount(ServerLevel level, EntityMaid maid) {
        net.minecraft.core.BlockPos feet = maid.m_20183_();
        net.minecraft.core.BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    net.minecraft.core.BlockPos p = feet.m_7918_(dx, dy, dz);
                    if (!level.m_8055_(p).m_60795_()) {
                        continue; // 格内已被占用
                    }
                    net.minecraft.world.level.block.state.BlockState under =
                            level.m_8055_(p.m_7918_(0, -1, 0));
                    if (!(under.m_204336_(net.minecraft.tags.BlockTags.f_144274_) /* #minecraft:dirt */
                            || under.m_60713_(net.minecraft.world.level.block.Blocks.f_50125_) /* grass_block */)) {
                        continue;
                    }
                    if (p.equals(feet)) {
                        best = p; // 树桩格允许（见 findPlantSpot 注释）
                        break;
                    }
                    net.minecraft.world.phys.AABB cellBox =
                            new net.minecraft.world.phys.AABB(p.m_123341_(), p.m_123342_(), p.m_123343_(),
                                    p.m_123341_() + 1.0, p.m_123342_() + 1.0, p.m_123343_() + 1.0);
                    if (!level.m_6443_(net.minecraft.world.entity.LivingEntity.class, cellBox,
                            e -> e.m_6084_()).isEmpty()) {
                        continue;
                    }
                    double d = maid.m_20275_(p.m_123341_() + 0.5, p.m_123342_() + 0.5, p.m_123343_() + 0.5);
                    if (d < bestD) {
                        bestD = d;
                        best = p;
                    }
                }
            }
        }
        return best == null ? new int[]{0, 0, 0, 0}
                : new int[]{1, best.m_123341_(), best.m_123342_(), best.m_123343_()};
    }

    /**
     * 身边（半径 RADIUS、垂直 ±2）最近的可种地块：空气 + 脚下 #minecraft:dirt/草方块 +
     * 格内无存活实体占用。允许【女仆自己站的那格】——树洞/洞穴树桩场景下该格往往是
     * 唯一有泥土的下方格（1×1 树桩窝），排除它永远找不到种点（v1.1.0 实测二百三十）。
     * 种进自己脚下格后该格变非空气，后续扫描自然跳过，不会原地重复种。
     */
    private static net.minecraft.core.BlockPos findPlantSpot(ServerLevel level, EntityMaid maid) {
        net.minecraft.core.BlockPos feet = maid.m_20183_();
        net.minecraft.core.BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    net.minecraft.core.BlockPos p = feet.m_7918_(dx, dy, dz);
                    if (!level.m_8055_(p).m_60795_()) {
                        continue; // 格内已被占用
                    }
                    net.minecraft.world.level.block.state.BlockState under =
                            level.m_8055_(p.m_7918_(0, -1, 0));
                    if (!(under.m_204336_(net.minecraft.tags.BlockTags.f_144274_) /* #minecraft:dirt */
                            || under.m_60713_(net.minecraft.world.level.block.Blocks.f_50125_) /* grass_block */)) {
                        continue;
                    }
                    if (p.equals(feet)) {
                        // 她正站着的格：允许（树洞树桩格），排除原则见上——直接选中
                        best = p;
                        break;
                    }
                    // 其他格：无存活实体占用（防把苗种进别的女仆/怪物身体）
                    net.minecraft.world.phys.AABB cellBox =
                            new net.minecraft.world.phys.AABB(p.m_123341_(), p.m_123342_(), p.m_123343_(),
                                    p.m_123341_() + 1.0, p.m_123342_() + 1.0, p.m_123343_() + 1.0);
                    if (!level.m_6443_(net.minecraft.world.entity.LivingEntity.class, cellBox,
                            e -> e.m_6084_()).isEmpty()) {
                        continue;
                    }
                    double d = maid.m_20275_(p.m_123341_() + 0.5, p.m_123342_() + 0.5, p.m_123343_() + 0.5);
                    if (d < bestD) {
                        bestD = d;
                        best = p;
                    }
                }
            }
        }
        return best;
    }

    /** 捡起身边（XZ 6 × Y ±6）掉落在地上的树苗（伐木中拾取任务让位，树叶掉的苗捡不到） */
    private static void pickupNearbySaplings(ServerLevel level, EntityMaid maid, net.minecraft.core.BlockPos base) {
        try {
            net.minecraft.world.phys.AABB box =
                    new net.minecraft.world.phys.AABB(base).m_82400_(RADIUS + 2.0);
            for (net.minecraft.world.entity.item.ItemEntity e :
                    level.m_45976_(net.minecraft.world.entity.item.ItemEntity.class, box)) {
                if (e == null || !e.m_6084_()) {
                    continue;
                }
                double dx = e.m_20185_() - (base.m_123341_() + 0.5);
                double dy = e.m_20186_() - (base.m_123342_() + 0.5);
                double dz = e.m_20189_() - (base.m_123343_() + 0.5);
                // v1.1.0 实测二百三十二：垂直范围改回 ±6（去除 -6..+12 放宽——用户指定改回）
                if (Math.abs(dx) > RADIUS || Math.abs(dy) > RADIUS || Math.abs(dz) > RADIUS) {
                    continue;
                }
                if (isSaplingItem(e.m_32055_())) {
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
     *  原版云杉/橡/桦等全部树苗都在内，模组树苗登记了标签也自动兼容）；
     *  兜底 ItemNameBlockItem+方块带 #minecraft:saplings（老式判定，覆盖未登记标签的模组苗）。 */
    public static boolean isSaplingItem(ItemStack stack) {
        try {
            if (stack.m_41619_()) {
                return false;
            }
            if (stack.m_204117_(net.minecraft.tags.ItemTags.f_13180_)) {
                return true; // #minecraft:saplings（物品标签）
            }
            if (!(stack.m_41720_() instanceof net.minecraft.world.item.ItemNameBlockItem)) {
                return false;
            }
            Block b = ((net.minecraft.world.item.ItemNameBlockItem) stack.m_41720_()).m_40614_();
            return b != null && b.m_49966_().m_204336_(SAPLINGS_TAG);
        } catch (Exception ignored) {
            return false;
        }
    }
}
