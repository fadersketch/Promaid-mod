package com.maidsmart.tool;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.resources.ResourceLocation;

/**
 * v1.1.0 实测二百三十四（用户："女仆主手或副手持有光源类物品时自身放出亮度，
 * 亮度与所持光源一致——套用雪傀儡套南瓜灯产生亮度的逻辑；此亮度不影响插火把"）：
 *
 * 【诚实实现说明】1.20.1 原版【没有】实体发光机制（javap 字节码实证：SnowGolem 无
 * 任何亮度方法、Entity 无 getLightLevelDependentMagicValue、LightEngine 无实体分支——
 * "雪傀儡戴南瓜灯发光"是基岩版特性）；TLM 本体也无此工程。本特性用【隐藏的
 * minecraft:light 光块跟随女仆】产出真实方块光——1.20.1 上唯一可靠的动态光路径：
 * - 每 10 tick（0.5 秒）检测：主手→副手读物品方块自己的 getLightEmission（getLightBlock）
 *   ——火把 14 / 灯笼 15 / 萤石 15 / 菌光体 15 / 海晶灯 15 / 灵魂火把 10 / 灵魂灯笼 10 /
 *   岩浆块 3 / 模组发光方块自动同值（0 = 无光物品直接跳过）；
 * - 有光：在女仆脚底格放 level=亮度 的隐藏光块（不可见、无碰撞、可含水），
 *   她走动时自动搬移（旧位置若仍是光块才移除——玩家自己放的光块不会被误删）；
 * - 无光：移除她的光块；进服/退服清理路径齐全（不残留）。
 *
 * 【不影响插火把】插火把读世界真实方块光（getBrightness）——本光块与其他环境光源
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
                lightBlock = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                        ResourceLocation.fromNamespaceAndPath("minecraft", "light"));
            } catch (Exception ignored) {
            }
        }
        return lightBlock;
    }

    /** LightBlock.LEVEL 属性（javap：LEVEL） */
    private static final net.minecraft.world.level.block.state.properties.IntegerProperty LEVEL_PROP =
            net.minecraft.world.level.block.LightBlock.LEVEL;

    /** 女仆 uuid → 她名下的光块位置（含维度引用；服务端 Level 对象稳定可长存） */
    private static final java.util.Map<java.util.UUID, Entry> TRACKED = new java.util.concurrent.ConcurrentHashMap<>();

    private record Entry(ServerLevel level, net.minecraft.core.BlockPos pos) {
    }

    private static boolean registered = false;
    private static int tickCounter = 0;
    private static boolean driverLoggedAlive = false;
    private static boolean driverLoggedNoBlock = false;

    private MaidHeldLight() {
    }

    /** ProMaidExtension 构造器调用（幂等） */
    public static void ensureRegistered() {
        if (registered) {
            return;
        }
        registered = true;
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(MaidHeldLight.class);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
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
            if (!driverLoggedNoBlock) {
                driverLoggedNoBlock = true;
                LOGGER.error("held light: minecraft:light block unavailable (registry not ready?)");
            }
            return; // 光块未解析到（极早期/注册表异常）：静默跳过
        }
        net.minecraft.server.MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        try {
            java.util.Set<java.util.UUID> seen = new java.util.HashSet<>();
            for (ServerLevel level : server.getAllLevels()) {
                if (level == null) {
                    continue;
                }
                for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
                    if (!(e instanceof EntityMaid maid) || !maid.isAlive()) {
                        continue;
                    }
                    int light = heldLightOf(maid, level);
                    if (light <= 0) {
                        clear(maid.getUUID());
                        continue;
                    }
                    seen.add(maid.getUUID());
                    net.minecraft.core.BlockPos pos = maid.blockPosition().immutable();
                    Entry prev = TRACKED.get(maid.getUUID());
                    if (prev == null || prev.level() != level || !prev.pos().equals(pos)) {
                        if (prev != null) {
                            clearBlock(prev.level(), prev.pos()); // 搬移/换维：旧位先撤
                        }
                        placeBlock(level, pos, light);
                        if (LIT_LOGGED.add(maid.getUUID())) {
                            LOGGER.info("held light: maid={} level={} pos={} (first lit)",
                                    maid.getUUID(), light, pos);
                        }
                    } else if (level.getBlockState(pos).getBlock() != lightBlock()) {
                        placeBlock(level, pos, light); // 被替换了（罕见）：补回
                    } else {
                        placeBlock(level, pos, light); // 光强更新（placeBlock 内部只写空气/光块格）
                    }
                    TRACKED.put(maid.getUUID(), new Entry(level, pos));
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

    /** 特殊光源白名单（注册名 → 光强）。实测二百三十九（用户："其他光源都验证成功
     *  了，仅仅是火把/灵魂火把/红石火把这些特殊方块不行——为什么不能加个白名单"）：
     *  火把系（StandingAndWallBlockItem）与普通发光方块（标准 BlockItem）的解析
     *  路径确实有差异，白名单【优先于】通用 BlockItem 光强路径——命中即用登记值，
     *  无法解析/被换手的不干净路径全被绕过。 */
    private static final java.util.Map<String, Integer> LIGHT_WHITELIST = new java.util.HashMap<>();

    static {
        LIGHT_WHITELIST.put("minecraft:torch", 14);
        LIGHT_WHITELIST.put("minecraft:soul_torch", 10);
        LIGHT_WHITELIST.put("minecraft:redstone_torch", 7);
        LIGHT_WHITELIST.put("minecraft:lantern", 15);
        LIGHT_WHITELIST.put("minecraft:soul_lantern", 10);
        LIGHT_WHITELIST.put("minecraft:campfire", 15);
        LIGHT_WHITELIST.put("minecraft:soul_campfire", 10);
        LIGHT_WHITELIST.put("minecraft:jack_o_lantern", 15);
        LIGHT_WHITELIST.put("minecraft:glowstone", 15);
        LIGHT_WHITELIST.put("minecraft:sea_lantern", 15);
        LIGHT_WHITELIST.put("minecraft:shroomlight", 15);
        LIGHT_WHITELIST.put("minecraft:magma_block", 3);
    }

    /** 主手 → 副手：① 白名单命中直接用登记亮度（火把系等特殊光源——用户实证
     *  通用路径只对标准 BlockItem 成立）；② 通用路径：物品方块自身发光
     *  （getLightEmission = getLightEmission，字节码实证：BlockStateBase 构造器把
     *  Properties.lightEmission（lightLevel 设置的 ToIntFunction）算进 lightEmission，
     *  getLightEmission 直接返回该字段——蜡烛 lit?3×数量:0、火把 14、萤石 15、岩浆块 3
     *  都走这里；木头等无发光方块 = 0）。
     *  v1.1.0 实测二百六十二（用户："女仆手上拿的是木头，但是它仍然会发光"）：
     *  旧版误用 getLightBlock（getLightBlock——光阻挡值：完整不透明方块返回 15、
     *  实心返回 0、否则 1），木头是完整方块恒返回 15 → 手持任何完整方块都
     *  被当成 15 级光源。改用 getLightEmission（getLightEmission）后木头 = 0 不发光。 */
    private static int heldLightOf(EntityMaid maid, ServerLevel level) {
        try {
            ItemStack main = maid.getMainHandItem();
            ItemStack off = maid.getOffhandItem();
            for (ItemStack stack : new ItemStack[]{main, off}) {
                if (stack.isEmpty()) {
                    continue;
                }
                // ① 白名单（优先）
                net.minecraft.resources.ResourceLocation key =
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (key != null) {
                    Integer w = LIGHT_WHITELIST.get(key.toString());
                    if (w != null) {
                        return Math.min(15, Math.max(1, w));
                    }
                }
                // ② 通用 BlockItem 光强（getLightEmission）
                if (!(stack.getItem() instanceof net.minecraft.world.item.BlockItem bi)) {
                    continue;
                }
                Block b = bi.getBlock();
                if (b == null || b == lightBlock()) {
                    continue; // 手持光块本身不做（防止套娃自校验）
                }
                BlockState st = b.defaultBlockState();
                int l = st.getLightEmission(); // getLightEmission（自身发光，与光照引擎同源）
                if (l > 0) {
                    return Math.min(15, Math.max(1, l));
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** 记录每个女仆首次点亮位置（探针日志：一次/女仆，验证"照亮"确已发生） */
    private static final java.util.Set<java.util.UUID> LIT_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void placeBlock(ServerLevel level, net.minecraft.core.BlockPos pos, int light) {
        try {
            // v1.1.0 实测二百七十一（用户："女仆直接把女仆床拆掉"）：旧版 level.setBlock
            // 【直接替换】目标格方块——女仆睡觉时身体与床方块重叠，光块位置 = 床格，
            // 床被光块顶掉；女仆离开后光块又被清成空气，床彻底消失。放置前检查：目标
            // 格是空气或本光块才可写入，被床等任何方块占据 → 跳过（绝不覆盖）。
            net.minecraft.world.level.block.state.BlockState cur = level.getBlockState(pos);
            if (!cur.isAir() && cur.getBlock() != lightBlock()) {
                return;
            }
            int l = Math.min(15, Math.max(1, light));
            if (cur.getBlock() != lightBlock()) {
                level.setBlock(pos, lightBlock().defaultBlockState().setValue(LEVEL_PROP, l), 3);
            } else {
                int c = cur.getValue(LEVEL_PROP);
                if (c != l) {
                    level.setBlock(pos, lightBlock().defaultBlockState().setValue(LEVEL_PROP, l), 3);
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 撤块：只有【仍在原位的光块】才移除（玩家自己放的光块不误删） */
    private static void clearBlock(ServerLevel level, net.minecraft.core.BlockPos pos) {
        try {
            if (level.getBlockState(pos).getBlock() == lightBlock()) {
                level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
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
    @net.neoforged.bus.api.SubscribeEvent
    public static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        for (java.util.Iterator<java.util.Map.Entry<java.util.UUID, Entry>> it = TRACKED.entrySet().iterator(); it.hasNext(); ) {
            Entry e = it.next().getValue();
            clearBlock(e.level(), e.pos());
            it.remove();
        }
    }

    /** 进服重置跟踪表 */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onServerStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        TRACKED.clear();
    }
}
