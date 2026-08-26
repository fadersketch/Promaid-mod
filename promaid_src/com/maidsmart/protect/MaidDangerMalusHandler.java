package com.maidsmart.protect;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * v1.1.0 实测一百一十七：危险方块避让——【原版寻路 malus 机制】（用户建议：
 * "这边就不能套用原版生物的一些寻路逻辑吗？"）。
 *
 * 背景（javap 字节码实证）：原版寻路对"危险格子"的通行裁决是 Mob 的
 * getPathfindingMalus（m_21439_）——没设覆盖时回落 BlockPathTypes 枚举默认值
 * （m_77124_）：LAVA=-1（不可通行，原版生物自动躲岩浆）、DAMAGE_OTHER=-1
 * （仙人掌/浆果丛，原版自动躲）、而 **DAMAGE_FIRE=+16（正数=可通行只是代价
 * 高）——原版生物会踩岩浆块/火/营火**。这正是用户实测"女仆仍会走岩浆块"的
 * 原版层面根因：TLM 女仆寻路（MaidPathNavigation → MaidWrappedPathFinder →
 * MaidNodeEvaluator，均 extends 原版 WalkNodeEvaluator 体系）对岩浆格/岩浆
 * 上方的空气格都返回 DAMAGE_FIRE，而 16 的 malus 不拦路。
 *
 * 修复：用原版决策点本身——把女仆的 DAMAGE_FIRE 等危险类型 malus 覆盖为 -1
 * （m_21441_ = setPathfindingMalus），与 LAVA 完全同款"不可通行"语义。原版
 * WalkNodeEvaluator 生成邻居节点时（m_164725_：mob.getPathfindingMalus(类型)
 * < 0 → 节点直接跳过；m_7209_：malus<0 → 类型直接短路返回）对所有寻路路径
 * （跟随/巡逻/干活/战斗）统一生效——不依赖任何注入点，无论哪条移动链路最终
 * 都走到原版寻路裁决。危险方块表（dangerBlocks）里的方块按原版 raw 映射归入
 * 对应类型；不在表里时恢复枚举默认值（关开关/删条目即时生效）。
 *
 * 与 MaidDangerPathMixin 双保险：mixin 在节点层按【配置方块 ID】拦截（覆盖
 * 模组危险方块、头顶灼烧型等原版 malus 管不到的格子）；本处理器按【原版类型】
 * 覆盖 malus（独立于注入点）。岩浆/火/营火两路都拦。
 */
public final class MaidDangerMalusHandler {

    /** 受管理的原版危险路径类型（DANGER_* 邻接型不管理——允许在危险旁正常走位绕行） */
    private static final Set<BlockPathTypes> MANAGED = EnumSet.of(
            BlockPathTypes.DAMAGE_FIRE,   // 岩浆块/火/灵魂火/营火/灵魂营火（本体格+脚下格+上方空气格）
            BlockPathTypes.DAMAGE_OTHER,  // 仙人掌/甜浆果丛/凋零玫瑰/石笋（枚举默认已 -1，显式管理防回退）
            BlockPathTypes.LAVA,          // 岩浆（枚举默认已 -1，显式管理防回退）
            BlockPathTypes.POWDER_SNOW);  // 细雪（枚举默认已 -1，显式管理防回退）

    private static int throttle = 0;

    private MaidDangerMalusHandler() {
    }

    /** ProMaidExtension 构造时注册 */
    public static void register() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new MaidDangerMalusHandler());
    }

    @SubscribeEvent
    public void onMaidJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof EntityMaid maid) {
            apply(maid);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++throttle < 100) {
            return; // 每 5 秒一次（覆盖配置热改/晚注册的女仆，Map.put 开销可忽略）
        }
        throttle = 0;
        net.minecraft.world.phys.AABB whole = new net.minecraft.world.phys.AABB(
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        for (ServerLevel level : event.getServer().m_129785_()) {
            for (EntityMaid maid : level.m_45976_(EntityMaid.class, whole)) {
                apply(maid);
            }
        }
    }

    /** 按当前配置给女仆刷 malus：表内类型 → -1（不可通行），表外/开关关 → 枚举默认值 */
    private static void apply(EntityMaid maid) {
        try {
            boolean on = MaidSmartConfig.MISC_DANGER_AVOID.get();
            Set<BlockPathTypes> danger = typesFor(MaidSmartConfig.MISC_DANGER_BLOCKS.get());
            for (BlockPathTypes t : MANAGED) {
                float v = (on && danger.contains(t)) ? -1.0F : t.m_77124_();
                maid.m_21441_(t, v);
            }
        } catch (Exception ignored) {
        }
    }

    /** 配置危险方块表 → 原版路径类型集合（按列表实例同一性缓存，改表即时生效） */
    private static volatile Set<BlockPathTypes> typesCache = null;
    private static volatile List<? extends String> typesCacheKey = null;

    private static Set<BlockPathTypes> typesFor(List<? extends String> list) {
        Set<BlockPathTypes> local = typesCache;
        if (local != null && typesCacheKey == list) {
            return local;
        }
        Set<BlockPathTypes> out = EnumSet.noneOf(BlockPathTypes.class);
        for (String s : list) {
            // 与原版 WalkNodeEvaluator 的 raw 映射同口径；未知/模组方块 → 空集，
            // 交给 MaidDangerPathMixin 按 ID 拦截
            switch (s) {
                case "minecraft:magma_block":
                case "minecraft:fire":
                case "minecraft:soul_fire":
                case "minecraft:campfire":
                case "minecraft:soul_campfire":
                    out.add(BlockPathTypes.DAMAGE_FIRE);
                    break;
                case "minecraft:lava":
                    out.add(BlockPathTypes.LAVA);
                    break;
                case "minecraft:cactus":
                case "minecraft:sweet_berry_bush":
                case "minecraft:wither_rose":
                case "minecraft:pointed_dripstone":
                    out.add(BlockPathTypes.DAMAGE_OTHER);
                    break;
                case "minecraft:powder_snow":
                    out.add(BlockPathTypes.POWDER_SNOW);
                    break;
                default:
                    break;
            }
        }
        synchronized (MaidDangerMalusHandler.class) {
            typesCache = out;
            typesCacheKey = list;
        }
        return out;
    }
}
