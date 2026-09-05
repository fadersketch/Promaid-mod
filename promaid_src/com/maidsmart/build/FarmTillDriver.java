package com.maidsmart.build;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

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

    private FarmTillDriver() {
    }

    public static void ensureRegistered() {
        if (!registered) {
            registered = true;
            MinecraftForge.EVENT_BUS.register(new FarmTillDriver());
            // v1.1.0 实测三百零二：锄地事件监听（玩家/女仆锄地 → 打"曾经是耕地"标记）
            MinecraftForge.EVENT_BUS.addListener(FarmSweepCache::onToolModification);
            // v1.1.0 实测三百零三：区块加载扫描（自然生成耕地 → 打标记）
            MinecraftForge.EVENT_BUS.addListener(FarmSweepCache::onChunkLoad);
        }
    }

    /** 扫描节流（v1.1.0 实测三百三十一：每 10 tick = 0.5 秒一次——用户反馈
     *  "农场耕地的频率太低了"，旧版 20 tick = 1 秒） */
    private int throttle = 0;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++this.throttle < 10) {
            return;
        }
        this.throttle = 0;
        net.minecraft.server.MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        try {
            for (ServerLevel level : server.m_129785_()) {
                // v1.1.0 实测三百三十（用户："只有跟随的时候才会锄地，home 模式下
                // 不会"）：EntityMaid.class 全图扫描改用 Entity.class 全量 + instanceof
                // 过滤——与宰杀 Animal.class 同构的 ClassInstanceMultiMap 桶 bug：
                // m_13533_(Class) 按请求 Class 精确建桶，未预建的 key 返回空桶 →
                // EntitySection.m_188348_ 直接跳过整个 section。跟随模式女仆所在的
                // section 被 TLM 感知系统预建了 EntityMaid 桶 → 能扫到 → 会锄地；
                // home 女仆单独站的 section 没预建 → 空桶 → 永远找不到 → 不锄地。
                for (net.minecraft.world.entity.Entity e : level.m_45976_(
                        net.minecraft.world.entity.Entity.class,
                        new net.minecraft.world.phys.AABB(-131072.0, -4096.0, -131072.0,
                                131072.0, 4096.0, 131072.0))) {
                    if (!(e instanceof EntityMaid maid) || !maid.m_6084_() || !isFarmTask(maid)) {
                        continue;
                    }
                    this.tillNearby(level, maid);
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
     * v1.1.0 实测三百三十四（用户："女仆对于耕地的积极性太低了。先检查一下对于
     * 更替的整个路径和判定，看看有没有办法提高积极性"）：锄地索敌重写——
     * 旧版只扫女仆【脚下 5×5】且 1 秒一轮：home 模式下女仆在锚点附近，农田稍远
     * 就永远锄不到，只能等随机巡逻撞上（积极性低的根因）。重写：
     * ① 扫描半径 5×5 → 16 格（与酿造/熔炉/宰杀同口径，misc.brewRadius 同值）
     * ② 冷却 1 秒 → 0.5 秒（与扫描节流一致）
     * ③ 近身（≤3 格）直接锄；远的目标【直连导航走过去】（m_26519_，不走
     *    MoveToTargetSink——站桩标记/移动抑制拦不住，自保逃跑验证过的通道）
     * ④ 标记自愈保留（范围内当前是耕地的地块实时打标）
     */
    private void tillNearby(ServerLevel world, EntityMaid maid) {
        try {
            if (!com.maidsmart.config.MaidSmartConfig.MISC_PRODUCE_TASK_ENHANCE.get()) {
                return;
            }
            long now = world.m_46467_();
            Long last = FarmSweepCache.TILL_CD.get(maid.m_20148_().toString());
            if (last != null && now - last < 10) {
                return; // 0.5 秒冷却（与扫描节流一致）
            }
            // 先确认背包/主手有锄头（没有就不锄，也不写冷却——补锄头后立即生效）
            if (!com.maidsmart.task.MaidToolAutoEquip.ensureHoeForFarm(maid)) {
                return;
            }
            FarmSweepCache.TILL_CD.put(maid.m_20148_().toString(), now);
            BlockPos base = maid.m_20183_();
            int radius = com.maidsmart.config.MaidSmartConfig.MISC_BREW_RADIUS.get();
            BlockPos tillTarget = null;
            double bestDistSq = Double.MAX_VALUE;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos b = base.m_7918_(dx, 0, dz);
                    if (!world.m_46749_(b)) {
                        continue; // 区块未加载跳过
                    }
                    // v1.1.0 实测三百零四（用户："现在是怎么搞女仆都不会进行耕地"）：
                    // 标记自愈——扫描范围内【当前是耕地】的地块直接打标。耕地是"曾经
                    // 是耕地"的活证据，实时补标后踩坏的地块立刻有标记可锄；且从未
                    // 耕过的泥土依然无标记 → 不会连锁扩散。旧版只靠锄地事件/区块加载
                    // 打标：女仆锄地需要标记、标记又只能靠锄地产生（死锁），区块加载
                    // 扫描又只在区块加载瞬间跑一次（玩家站农田旁时早已加载）→ 女仆
                    // 永远锄不了地。
                    if (world.m_8055_(b).m_60734_()
                            == net.minecraft.world.level.block.Blocks.f_50093_) {
                        FarmlandMarkStore.get(world).mark(b);
                        continue;
                    }
                    if (!FarmSweepCache.isTillable(world, maid, b)) {
                        continue;
                    }
                    double dsq = (double) dx * dx + (double) dz * dz;
                    if (dsq < bestDistSq) {
                        bestDistSq = dsq;
                        tillTarget = b;
                    }
                }
            }
            if (tillTarget == null) {
                return; // 范围内无可锄地块
            }
            // 近身（≤3 格）直接锄；远的目标直连导航走过去（下轮近身再锄）
            double distSq = maid.m_20275_(tillTarget.m_123341_() + 0.5,
                    tillTarget.m_123342_() + 0.5, tillTarget.m_123343_() + 0.5);
            if (distSq > 9.0) {
                maid.m_21573_().m_26519_(tillTarget.m_123341_() + 0.5,
                        tillTarget.m_123342_(), tillTarget.m_123343_() + 0.5, 0.8f);
                return;
            }
            // 锄成耕地（与 HoeItem 静态表同目标：dirt/grass_block → farmland）
            world.m_7731_(tillTarget, net.minecraft.world.level.block.Blocks.f_50093_.m_49966_(), 3);
            // v1.1.0 实测三百零二：女仆锄地后保持标记（标记制——标记是
            // "曾经是耕地"的凭证，锄完不能丢，否则下次踩坏后女仆不认）
            FarmlandMarkStore.get(world).mark(tillTarget);
            // 锄地音效（HoeItem.m_6225_ 字节码实证：SoundEvents.f_11955_）
            world.m_5594_(null, tillTarget, net.minecraft.sounds.SoundEvents.f_11955_,
                    net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 1.0f);
            maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND); // 挥臂
            // 消耗 1 点耐久（HoeItem.m_6225_ 同款：m_41622_(1, LivingEntity, Consumer)）
            ItemStack hoe = maid.m_21205_();
            if (!hoe.m_41619_()) {
                hoe.m_41622_(1, maid, e -> {
                });
            }
        } catch (Throwable ignored) {
        }
    }
}
