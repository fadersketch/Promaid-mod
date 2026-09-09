package com.maidsmart.build;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.bus.api.SubscribeEvent;

/**
 * v1.1.0 实测二百九十七：锄地独立驱动（用户："女仆耕地的积极性真的很差，
 * 基本上就是只是放下去一下，会锄一下地，但是之后就再也不锄地了"）。
 *
 * 根因：锄地只挂在 MaidFarmPlantTask.start 的 TAIL——而 start 只在 TARGET_POS
 * 存在时触发（MaidFarmMoveTask.searchForDestination 设置，只认可收割/可种植/
 * 可锄目标）。锄完一块泥土变成耕地后，周围没有成熟作物/空耕地时扫描空转 →
 * TARGET_POS 不设置 → start 不触发 → 锄地再也不跑。
 *
 * v1.1.0 实测二百九十八（用户："耕地改为一个顺带逻辑。先将整个农场模式运作的
 * 逻辑改回原版。但是如果在自己 5×5 范围内发现到曾经是耕地的地块，然后执行目前
 * 的换工具逻辑，并播放一下动画，并将地块变为耕地"）：锄地降级为【顺带逻辑】——
 * 农场模式运作完全回原版（FarmMoveTillMixin 注入作废，锄地目标不再占用移动
 * 扫描），本驱动每 1 秒扫描女仆周围 5×5（水平）的可锄泥土并顺带锄掉。
 * 冷却表复用 FarmSweepCache.TILL_CD。
 *
 * v1.1.0 实测三百零二（用户："对曾经已经是耕地的地块打上一个标记……在 5×5
 * 范围内检索到以后发现不是耕地就动用锄头将其锄成耕地"）：锄地判定改为【标记制】——
 * 只锄"有标记（曾经是耕地）且当前不是耕地"的地块（FarmSweepCache.isTillable），
 * 不再用"3×3 内有耕地"启发式（连锁扩散 → 超平坦地形 5×5 全变耕地）。标记由
 * 锄地事件自动打（玩家/女仆锄地时，FarmSweepCache.onToolModification），
 * SavedData 持久化（FarmlandMarkStore）。
 *
 * v1.1.0 实测三百零三（用户："有些结构会自然生成耕地，那那些耕地也要打上标记"）：
 * 区块加载扫描兜底——结构生成（村庄农田等）的耕地是直接放置方块，不触发锄地
 * 事件，ChunkEvent.Load 时遍历区块内耕地方块打标记（FarmSweepCache.onChunkLoad）。
 *
 * v1.1.0 实测三百零四（用户："现在是怎么搞女仆都不会进行耕地"）：标记自愈——
 * 扫描范围内【当前是耕地】的地块直接打标（耕地是"曾经是耕地"的活证据）。旧版
 * 只靠锄地事件/区块加载打标：女仆锄地需要标记、标记又只能靠锄地产生（死锁），
 * 区块加载扫描又只在区块加载瞬间跑一次（玩家站农田旁时早已加载）→ 女仆永远
 * 锄不了地。自愈后：农田的标记实时补上 → 踩坏的地块有标记可锄；从未耕过的泥土
 * 依然无标记 → 不连锁扩散。
 */
public final class FarmTillDriver {
    private static boolean registered = false;

    /** 实测三百五十八：锄地诊断日志（30 秒/女仆限频——失效时看卡在哪一环） */
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final java.util.Map<java.util.UUID, Long> TILL_DIAG_SINCE =
            new java.util.HashMap<>();

    private FarmTillDriver() {
    }

    public static void ensureRegistered() {
        if (!registered) {
            registered = true;
            NeoForge.EVENT_BUS.register(new FarmTillDriver());
            // v1.1.0 实测三百零二：锄地事件监听（玩家/女仆锄地 → 打"曾经是耕地"标记）
            NeoForge.EVENT_BUS.addListener(FarmSweepCache::onToolModification);
            // v1.1.0 实测三百零三：区块加载扫描（自然生成耕地 → 打标记）
            NeoForge.EVENT_BUS.addListener(FarmSweepCache::onChunkLoad);
        }
    }

    /** 扫描节流（v1.1.0 实测三百三十一：每 10 tick = 0.5 秒一次——用户反馈
     *  "农场耕地的频率太低了"，旧版 20 tick = 1 秒） */
    private int throttle = 0;

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
if (++this.throttle < 10) {
            return;
        }
        this.throttle = 0;
        net.minecraft.server.MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        try {
            for (ServerLevel level : server.getAllLevels()) {
                // v1.1.0 实测三百三十（用户："只有跟随的时候才会锄地，home 模式下
                // 不会"）：EntityMaid.class 全图扫描改用 Entity.class 全量 + instanceof
                // 过滤——与宰杀 Animal.class 同构的 ClassInstanceMultiMap 桶 bug：
                // find(Class) 按请求 Class 精确建桶，未预建的 key 返回空桶 →
                // EntitySection.getEntities 直接跳过整个 section。跟随模式女仆所在的
                // section 被 TLM 感知系统预建了 EntityMaid 桶 → 能扫到 → 会锄地；
                // home 女仆单独站的 section 没预建 → 空桶 → 永远找不到 → 不锄地。
                for (net.minecraft.world.entity.Entity e : level.getEntitiesOfClass(
                        net.minecraft.world.entity.Entity.class,
                        new net.minecraft.world.phys.AABB(-131072.0, -4096.0, -131072.0,
                                131072.0, 4096.0, 131072.0))) {
                    if (!(e instanceof EntityMaid maid) || !maid.isAlive() || !isFarmTask(maid)) {
                        continue;
                    }
                    // v1.1.0 实测三百五十五：农场作物骨粉催熟（与树苗同款逻辑——
                    // 主副手/背包找骨粉、施肥时主手换持骨粉、0.5 秒一株、粒子反馈）
                    // v1.1.0 实测三百六十【优先级】（用户："伐木/种植/耕地的优先级
                    // 应该高于骨粉，现在容易在骨粉和工具之间反复切换的鬼畜"）：
                    // 锄地有活（找到可锄目标/正在锄/正在走过去）→ 本轮不施肥——
                    // 耕地独占主手，不再和骨粉来回抢；锄地无活（范围内没有可锄
                    // 地块）→ 才轮到施肥催熟作物。
                    if (!this.tillNearby(level, maid)) {
                        this.bonemealNearby(level, maid);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isFarmTask(EntityMaid maid) {
        try {
            return maid.getTask() != null
                    && "touhou_little_maid:farm".equals(maid.getTask().getUid().toString());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * v1.1.0 实测三百五十五（用户："将同样的逻辑引入到农场给农作物施肥里面，
     * 配置界面也是如此"）：农场作物骨粉催熟——与树苗催熟同款逻辑：
     * ① 找骨粉 主手 → 副手 → 背包（equipBoneMeal 复用，施肥时主手换持骨粉，
     *    停止 1 秒后由 MaidPlanting.tryRestoreHand 还原）；
     * ② 每 0.5 秒（随本驱动节拍）对身边（半径 16 格、垂直 ±4）最近的一株
     *    未成熟作物施肥，带粒子特效；
     * ③ 成熟过滤用 isValidBonemealTarget（isValidBonemealTarget）——CropBlock 字节码实证：
     *    该方法 = age < maxAge（未成熟 true / 成熟 false），isBonemealSuccess
     *    对 CropBlock 恒 true（实测三百五十六修正：旧版误用 isBonemealSuccess 当成熟
     *    过滤——它恒 true，把全部作物都跳过了 = 骨粉在手上一株都不催）；
     * ④ 树苗/草方块/蘑菇/藤蔓等非农作物排除（isNonCropGrower）。
     *
     * @return true = 本轮施了肥（主手已换持骨粉）——调用方跳过锄地，
     *         避免锄头自动装备把骨粉从主手抢走
     */
    private boolean bonemealNearby(ServerLevel world, EntityMaid maid) {
        try {
            if (!com.maidsmart.config.MaidSmartConfig.MISC_MAID_BONEMEAL_FARM.get()) {
                return false;
            }
            BlockPos base = maid.blockPosition();
            int radius = com.maidsmart.config.MaidSmartConfig.MISC_BREW_RADIUS.get();
            BlockPos best = null;
            double bestDistSq = Double.MAX_VALUE;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dy = -4; dy <= 4; dy++) {
                        BlockPos b = base.offset(dx, dy, dz);
                        if (!world.isLoaded(b)) {
                            continue; // 区块未加载跳过
                        }
                        BlockState st = world.getBlockState(b);
                        Block blk = st.getBlock();
                        if (!(blk instanceof BonemealableBlock)) {
                            continue;
                        }
                        if (isNonCropGrower(blk)) {
                            continue; // 树苗归伐木催熟管；草/蘑菇/藤蔓等不是农作物
                        }
                        // 只催未成熟作物：isValidBonemealTarget = age < maxAge
                        //（成熟 false = 催了也白费——不浪费骨粉）
                        if (!((BonemealableBlock) blk).isValidBonemealTarget(world, b, st)) {
                            continue;
                        }
                        double dsq = (double) dx * dx + (double) dz * dz + (double) dy * dy;
                        if (dsq < bestDistSq) {
                            bestDistSq = dsq;
                            best = b;
                        }
                    }
                }
            }
            if (best == null) {
                return false; // 范围内没有未成熟作物
            }
            int id = maid.getId();
            long now = world.getGameTime();
            if (!com.maidsmart.task.MaidPlanting.equipBoneMeal(maid, id, now)) {
                return false; // 手上和背包都没有骨粉
            }
            // growCrop：isValidBonemealTarget → isBonemealSuccess → performBonemeal
            // → 自 shrink(1)——骨粉消耗它自己管
            ItemStack boneStack = maid.getMainHandItem();
            boolean ok = net.minecraft.world.item.BoneMealItem.growCrop(boneStack, world, best);
            if (ok) {
                // growCrop 不放 1505（原版粒子在 useOn 路径）——每次施肥都给粒子反馈
                world.levelEvent(1505, best, 15);
                maid.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 实测三百五十六：非农作物的骨粉目标排除表——原版实现 BonemealableBlock
     * 但不是"农作物"的方块（骨粉有效但不该由农场女仆催）：
     * 树苗（伐木那边管）/ 草方块与草丛蕨（催出草花）/ 藤蔓 / 发光地衣 / 蘑菇与
     * 菌类（催巨型蘑菇）/ 菌岩（扩散菌落）/ 海带海草 / 竹子 / 紫颂 / 垂滴叶 /
     * 洞穴藤（发光浆果）/ 缠怨与垂泣藤 / 杜鹃（催成树）/ 苔藓块。
     * 保留：CropBlock 系（小麦/胡萝卜/土豆/甜菜/火把花/猪笼草）、西瓜南瓜茎、
     * 可可豆、甜浆果丛、地狱疣（isValidBonemealTarget 恒 false 自动排除）。
     */
    private static boolean isNonCropGrower(Block blk) {
        return blk instanceof SaplingBlock
                || blk instanceof net.minecraft.world.level.block.GrassBlock
                || blk instanceof net.minecraft.world.level.block.TallGrassBlock
                || blk instanceof net.minecraft.world.level.block.VineBlock
                || blk instanceof net.minecraft.world.level.block.GlowLichenBlock
                || blk instanceof net.minecraft.world.level.block.MushroomBlock
                || blk instanceof net.minecraft.world.level.block.FungusBlock
                || blk instanceof net.minecraft.world.level.block.NyliumBlock
                || blk instanceof net.minecraft.world.level.block.KelpBlock
                || blk instanceof net.minecraft.world.level.block.KelpPlantBlock
                || blk instanceof net.minecraft.world.level.block.SeagrassBlock
                || blk instanceof net.minecraft.world.level.block.TallSeagrassBlock
                || blk instanceof net.minecraft.world.level.block.BambooStalkBlock
                || blk instanceof net.minecraft.world.level.block.ChorusPlantBlock
                || blk instanceof net.minecraft.world.level.block.ChorusFlowerBlock
                || blk instanceof net.minecraft.world.level.block.BigDripleafBlock
                || blk instanceof net.minecraft.world.level.block.SmallDripleafBlock
                || blk instanceof net.minecraft.world.level.block.CaveVinesBlock
                || blk instanceof net.minecraft.world.level.block.CaveVinesPlantBlock
                || blk instanceof net.minecraft.world.level.block.TwistingVinesBlock
                || blk instanceof net.minecraft.world.level.block.WeepingVinesBlock
                || blk instanceof net.minecraft.world.level.block.AzaleaBlock
                || blk instanceof net.minecraft.world.level.block.MossBlock;
    }

    /**
     * v1.1.0 实测三百三十四（用户："女仆对于耕地的积极性太低了。先检查一下对于
     * 更替的整个路径和判定，看看有没有办法提高积极性"）：锄地索敌重写——
     * 旧版只扫女仆【脚下 5×5】且 1 秒一轮：home 模式下女仆在锚点附近，农田稍远
     * 就永远锄不到，只能等随机巡逻撞上（积极性低的根因）。重写：
     * ① 扫描半径 5×5 → 16 格（与酿造/熔炉/宰杀同口径，misc.brewRadius 同值）
     * ② 冷却 1 秒 → 0.5 秒（与扫描节流一致）
     * ③ 近身（≤3 格）直接锄；远的目标【直连导航走过去】（moveTo，不走
     *    MoveToTargetSink——站桩标记/移动抑制拦不住，自保逃跑验证过的通道）
     * ④ 标记自愈保留（范围内当前是耕地的地块实时打标）
     */
    /**
     * v1.1.0 实测三百六十：返回本轮锄地是否"有活"（找到可锄目标/正在锄/正在
     * 走过去）——true = 耕地工作中，调用方跳过施肥（耕地优先独占主手）；
     * false = 无活（无锄头/范围内无可锄地块），施肥才轮得上。
     */
    private boolean tillNearby(ServerLevel world, EntityMaid maid) {
        try {
            if (!com.maidsmart.config.MaidSmartConfig.MISC_PRODUCE_TASK_ENHANCE.get()) {
                return false;
            }
            long now = world.getGameTime();
            // v1.1.0 实测三百五十七：锄地前强制还原施肥换手——主手若还举着骨粉，
            // 锄头自动装备会被打断（骨粉被顶到别处、换手状态机错乱）。先还原：
            // 剩余骨粉收回背包、原主手物品放回，锄头干净地换上来。
            com.maidsmart.task.MaidPlanting.forceRestoreHand(maid, maid.getId());
            Long last = FarmSweepCache.TILL_CD.get(maid.getUUID().toString());
            if (last != null && now - last < 10) {
                return false; // 0.5 秒冷却（与扫描节流一致）
            }
            // 先确认背包/主手有锄头（没有就不锄，也不写冷却——补锄头后立即生效）
            boolean hasHoe = com.maidsmart.task.MaidToolAutoEquip.ensureHoeForFarm(maid);
            if (!hasHoe) {
                Long lastDiag0 = TILL_DIAG_SINCE.get(maid.getUUID());
                if (lastDiag0 == null || now - lastDiag0 >= 600) {
                    TILL_DIAG_SINCE.put(maid.getUUID(), now);
                    LOGGER.info("till diag: maid={} hoe=false（无锄头——补锄头后立即生效）pos={}",
                            com.maidsmart.tool.PromaidLog.nameOf(maid), maid.blockPosition());
                }
                return false;
            }
            FarmSweepCache.TILL_CD.put(maid.getUUID().toString(), now);
            BlockPos base = maid.blockPosition();
            int radius = com.maidsmart.config.MaidSmartConfig.MISC_BREW_RADIUS.get();
            BlockPos tillTarget = null;
            double bestDistSq = Double.MAX_VALUE;
            // v1.1.0 实测三百五十八：诊断统计（与 isTillable 同口径分环计数——
            // dirt 总数 / 其中有"曾经是耕地"标记的 / 其中上方是空气的 / 最终合格数）
            int farmlandCount = 0;
            int dirtCount = 0;
            int markedCount = 0;
            int airAboveCount = 0;
            int tillableCount = 0;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos b = base.offset(dx, 0, dz);
                    if (!world.isLoaded(b)) {
                        continue; // 区块未加载跳过
                    }
                    // v1.1.0 实测三百零四（用户："现在是怎么搞女仆都不会进行耕地"）：
                    // 标记自愈——扫描范围内【当前是耕地】的地块直接打标。耕地是"曾经
                    // 是耕地"的活证据，实时补标后踩坏的地块立刻有标记可锄；且从未
                    // 耕过的泥土依然无标记 → 不会连锁扩散。旧版只靠锄地事件/区块加载
                    // 打标：女仆锄地需要标记、标记又只能靠锄地产生（死锁），区块加载
                    // 扫描又只在区块加载瞬间跑一次（玩家站农田旁时早已加载）→ 女仆
                    // 永远锄不了地。
                    if (world.getBlockState(b).getBlock()
                            == net.minecraft.world.level.block.Blocks.FARMLAND) {
                        farmlandCount++;
                        FarmlandMarkStore.get(world).mark(b);
                        continue;
                    }
                    net.minecraft.world.level.block.Block tb = world.getBlockState(b).getBlock();
                    if (tb == net.minecraft.world.level.block.Blocks.DIRT
                            || tb == net.minecraft.world.level.block.Blocks.GRASS_BLOCK) {
                        dirtCount++;
                        if (FarmlandMarkStore.get(world).isMarked(b)) {
                            markedCount++;
                        }
                        if (world.getBlockState(b.above()).isAir()) {
                            airAboveCount++;
                        }
                    }
                    if (!FarmSweepCache.isTillable(world, maid, b)) {
                        continue;
                    }
                    tillableCount++;
                    double dsq = (double) dx * dx + (double) dz * dz;
                    if (dsq < bestDistSq) {
                        bestDistSq = dsq;
                        tillTarget = b;
                    }
                }
            }
            // 实测三百五十八：锄地状态诊断（30 秒/女仆限频）——hoe=有无锄头、
            // farmland=范围内耕地数（自愈打标来源）、dirt/marked/airAbove=候选漏斗、
            // tillable=最终合格数、target=本次目标
            Long lastDiag = TILL_DIAG_SINCE.get(maid.getUUID());
            if (lastDiag == null || now - lastDiag >= 600) {
                TILL_DIAG_SINCE.put(maid.getUUID(), now);
                LOGGER.info("till diag: maid={} hoe={} farmland={} dirt={} marked={} airAbove={}"
                                + " tillable={} target={} pos={}",
                        com.maidsmart.tool.PromaidLog.nameOf(maid), hasHoe, farmlandCount,
                        dirtCount, markedCount, airAboveCount, tillableCount, tillTarget,
                        maid.blockPosition());
            }
            if (!hasHoe) {
                return false; // 无锄头：补锄头后下一轮生效
            }
            if (tillTarget == null) {
                return false; // 范围内无可锄地块
            }
            // 近身（≤3 格）直接锄；远的目标直连导航走过去（下轮近身再锄）
            double distSq = maid.distanceToSqr(tillTarget.getX() + 0.5,
                    tillTarget.getY() + 0.5, tillTarget.getZ() + 0.5);
            if (distSq > 9.0) {
                maid.getNavigation().moveTo(tillTarget.getX() + 0.5,
                        tillTarget.getY(), tillTarget.getZ() + 0.5, 0.8f);
                return true; // 正在走过去锄 = 耕地工作中
            }
            // 锄成耕地（与 HoeItem 静态表同目标：dirt/grass_block → farmland）
            world.setBlock(tillTarget, net.minecraft.world.level.block.Blocks.FARMLAND.defaultBlockState(), 3);
            // 实测三百五十八：锄地成功落日志（每次落——频率低，方便对账"踩坏的地修没修"）
            LOGGER.info("till ok: maid={} pos={}",
                    com.maidsmart.tool.PromaidLog.nameOf(maid), tillTarget);
            // v1.1.0 实测三百零二：女仆锄地后保持标记（标记制——标记是
            // "曾经是耕地"的凭证，锄完不能丢，否则下次踩坏后女仆不认）
            FarmlandMarkStore.get(world).mark(tillTarget);
            // 锄地音效（HoeItem.m_6225_ 字节码实证：SoundEvents.HOE_TILL）
            world.playSound(null, tillTarget, net.minecraft.sounds.SoundEvents.HOE_TILL,
                    net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 1.0f);
            maid.swing(net.minecraft.world.InteractionHand.MAIN_HAND); // 挥臂
            // 消耗 1 点耐久（HoeItem.m_6225_ 同款：hurtAndBreak(1, LivingEntity, Consumer)）
            ItemStack hoe = maid.getMainHandItem();
            if (!hoe.isEmpty()) {
                hoe.hurtAndBreak(1, maid, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
            }
            return true; // 本轮锄了地 = 耕地工作中
        } catch (Throwable ignored) {
            return false;
        }
    }
}
