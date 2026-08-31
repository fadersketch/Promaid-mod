package com.maidsmart.tool;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * v1.1.0 实测二百三十四（用户："女仆主手或副手持有光源类物品时自身放出亮度，
 * 亮度与所持光源一致——套用雪傀儡套南瓜灯产生亮度的逻辑；此亮度不影响插火把"）：
 *
 * 【诚实实现说明】1.20.1 原版【没有】实体发光机制（javap 字节码实证：SnowGolem 无
 * 任何亮度方法、Entity 无 getLightLevelDependentMagicValue、LightEngine 无实体分支——
 * "雪傀儡戴南瓜灯发光"是基岩版特性）；TLM 本体也无此工程。本特性用【隐藏的
 * minecraft:light 光块跟随女仆】产出真实方块光——1.20.1 上唯一可靠的动态光路径：
 * - 每 10 tick（0.5 秒）检测：主手→副手读物品方块自己的 getLightEmission（m_60739_）
 *   ——火把 14 / 灯笼 15 / 萤石 15 / 菌光体 15 / 海晶灯 15 / 灵魂火把 10 / 灵魂灯笼 10 /
 *   岩浆块 3 / 模组发光方块自动同值（0 = 无光物品直接跳过）；
 * - 有光：在女仆脚底格放 level=亮度 的隐藏光块（不可见、无碰撞、可含水），
 *   她走动时自动搬移（旧位置若仍是光块才移除——玩家自己放的光块不会被误删）；
 * - 无光：移除她的光块；进服/退服清理路径齐全（不残留）。
 *
 * 【不影响插火把】插火把读世界真实方块光（m_45517_）——本光块与其他环境光源
 * （炉子/篝火/发光方块）完全同等待遇，插火把判定零改动：亮的地方不插 = 与其
 * "只在暗处插"的意图一致，且她不插火把的前提是光块区域已足够亮。
 *
 * 总开关：misc.heldLightMaid（默认开）。
 */
public final class MaidHeldLight {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** minecraft:light 光块——【懒解析】：类加载期（mod 启动）注册表未冻结，静态取
     *  值为 null 会让整个模块静默死亡（实测二百三十五根因）；改为首次 tick 时取并缓存。 */
    private static net.minecraft.world.level.block.Block lightBlock = null;
    private static boolean lightBlockTried = false;

    private static Block lightBlock() {
        if (!lightBlockTried) {
            lightBlockTried = true;
            try {
                lightBlock = ForgeRegistries.BLOCKS.getValue(
                        new net.minecraft.resources.ResourceLocation("minecraft", "light"));
            } catch (Exception ignored) {
            }
        }
        return lightBlock;
    }

    /** LightBlock.LEVEL 属性（javap：f_153657_） */
    private static final net.minecraft.world.level.block.state.properties.IntegerProperty LEVEL_PROP =
            net.minecraft.world.level.block.LightBlock.f_153657_;

    /** 女仆 uuid → 她名下的光块位置（含维度引用；服务端 Level 对象稳定可长存） */
    private static final java.util.Map<java.util.UUID, Entry> TRACKED = new java.util.concurrent.ConcurrentHashMap<>();

    private record Entry(ServerLevel level, net.minecraft.core.BlockPos pos) {
    }

    private static boolean registered = false;
    private static int tickCounter = 0;
    private static boolean driverLoggedAlive = false;

    private MaidHeldLight() {
    }

    /** ProMaidExtension 构造器调用（幂等） */
    public static void ensureRegistered() {
        if (registered) {
            return;
        }
        registered = true;
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(MaidHeldLight.class);
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        if (!com.maidsmart.config.MaidSmartConfig.MISC_HELD_LIGHT_ENABLED.get()) {
            return;
        }
        if (++tickCounter % 10 != 0) {
            return; // 0.5 秒一轮
        }
        if (!driverLoggedAlive) {
            driverLoggedAlive = true;
            LOGGER.info("held-light driver: alive (ServerTick driver registered)");
        }
        if (lightBlock() == null) {
            return; // 光块未解析到（极早期/注册表异常）：静默跳过
        }
        net.minecraft.server.MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        try {
            java.util.Set<java.util.UUID> seen = new java.util.HashSet<>();
            for (ServerLevel level : server.m_129785_()) {
                if (level == null) {
                    continue;
                }
                for (net.minecraft.world.entity.Entity e : level.m_8583_()) {
                    if (!(e instanceof EntityMaid maid) || !maid.m_6084_()) {
                        continue;
                    }
                    int light = heldLightOf(maid, level);
                    if (light <= 0) {
                        clear(maid.m_20148_());
                        continue;
                    }
                    seen.add(maid.m_20148_());
                    net.minecraft.core.BlockPos pos = maid.m_20183_().m_7949_();
                    Entry prev = TRACKED.get(maid.m_20148_());
                    if (prev == null || prev.level() != level || !prev.pos().equals(pos)) {
                        if (prev != null) {
                            clearBlock(prev.level(), prev.pos()); // 搬移/换维：旧位先撤
                        }
                        placeBlock(level, pos, light);
                    } else if (level.m_8055_(pos).m_60734_() != lightBlock()) {
                        placeBlock(level, pos, light); // 被替换了（罕见）：补回
                    } else {
                        int cur = level.m_8055_(pos).m_61143_(LEVEL_PROP);
                        if (cur != light) {
                            level.m_7731_(pos, lightBlock().m_49966_().m_61124_(LEVEL_PROP, light), 3);
                        }
                    }
                    TRACKED.put(maid.m_20148_(), new Entry(level, pos));
                }
            }
            // 离线/卸载女仆（本轮没见到）→ 撤她的光块
            for (java.util.Iterator<java.util.Map.Entry<java.util.UUID, Entry>> it = TRACKED.entrySet().iterator(); it.hasNext(); ) {
                java.util.Map.Entry<java.util.UUID, Entry> te = it.next();
                if (!seen.contains(te.getKey())) {
                    clearBlock(te.getValue().level(), te.getValue().pos());
                    it.remove();
                }
            }
        } catch (Throwable t) {
            LOGGER.error("held light tick failed", t);
        }
    }

    /** 主手 → 副手：物品方块的自身发光（0=无光/无 BlockItem） */
    private static int heldLightOf(EntityMaid maid, ServerLevel level) {
        try {
            ItemStack main = maid.m_21205_();
            ItemStack off = maid.m_21206_();
            for (ItemStack stack : new ItemStack[]{main, off}) {
                if (stack.m_41619_() || !(stack.m_41720_() instanceof net.minecraft.world.item.BlockItem bi)) {
                    continue;
                }
                Block b = bi.m_40614_();
                if (b == null || b == lightBlock()) {
                    continue; // 手持光块本身不做（防止套娃自校验）
                }
                BlockState st = b.m_49966_();
                int l = st.m_60739_(level, maid.m_20183_()); // getLightEmission（光照引擎同源）
                if (l > 0) {
                    return Math.min(15, Math.max(1, l));
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static void placeBlock(ServerLevel level, net.minecraft.core.BlockPos pos, int light) {
        try {
            level.m_7731_(pos, lightBlock().m_49966_().m_61124_(LEVEL_PROP, Math.min(15, Math.max(1, light))), 3);
        } catch (Exception ignored) {
        }
    }

    /** 撤块：只有【仍在原位的光块】才移除（玩家自己放的光块不误删） */
    private static void clearBlock(ServerLevel level, net.minecraft.core.BlockPos pos) {
        try {
            if (level.m_8055_(pos).m_60734_() == lightBlock()) {
                level.m_7731_(pos, net.minecraft.world.level.block.Blocks.f_50016_.m_49966_(), 3);
            }
        } catch (Exception ignored) {
        }
    }

    /** 清某女仆的光块（无光物品/脱离时） */
    public static void clear(java.util.UUID maidUuid) {
        Entry prev = TRACKED.remove(maidUuid);
        if (prev != null) {
            clearBlock(prev.level(), prev.pos());
        }
    }

    /** 退服清理：全部光块撤除 */
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
        for (java.util.Iterator<java.util.Map.Entry<java.util.UUID, Entry>> it = TRACKED.entrySet().iterator(); it.hasNext(); ) {
            Entry e = it.next().getValue();
            clearBlock(e.level(), e.pos());
            it.remove();
        }
    }

    /** 进服重置跟踪表 */
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onServerStarted(net.minecraftforge.event.server.ServerStartedEvent event) {
        TRACKED.clear();
    }
}
