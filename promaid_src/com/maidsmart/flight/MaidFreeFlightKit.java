package com.maidsmart.flight;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.tags.ITagManager;

import java.util.List;

/**
 * 实测七百〇二【仿创造飞行 · 1.20.1 精简版 · 能力探测】——判断"她身上有什么东西让她能飞"。
 *
 * 【为什么需要这一层】"能不能飞"对**玩家**是 {@code Player#getAbilities().mayfly}，而
 * **女仆没有这套 Abilities**。而且绝大多数"给飞行的模组物品"把逻辑写死在玩家身上
 * （{@code instanceof Player} / Abilities），女仆装着它们**什么都不会发生**。所以这个功能的
 * 本质是**仿创造飞行**：由我们给她悬浮与升降，物品只是"资格凭证"。
 *
 * 【1.20.1 精简版：只有两路探测（1.21.1 那版是三路）】
 * <ol>
 *   <li>{@code freeFlightItems}（默认空）：物品 id 列表，可写 {@code #命名空间:标签} 认整条标签；
 *       扫描范围 = 双手 / 护甲 / 背包 / TLM 饰品栏 / 额外容器（精妙背包等）；</li>
 *   <li>{@code freeFlightEffects}（默认空）：药水效果 id 列表（效果挂在实体上，这一路对女仆天然有效）。</li>
 * </ol>
 *
 * 【1.20.1 砍掉的两路，以及为什么砍】
 * <ul>
 *   <li><b>重力归零启发式</b>：{@code Attributes.GRAVITY} 是 1.20.5 才有的属性，1.20.1 的
 *       {@code Attributes} 里**根本没有这个字段**（javap 两份 SRG jar 对照实证）——这一路在
 *       1.20.1 物理上没有落点。</li>
 *   <li><b>数据组件两路（{@code @组件} / {@code @组件~文本}）</b>：数据组件（
 *       {@code DataComponentType} / {@code stack.getComponents()}）是 1.20.5+ 的机制，
 *       1.20.1 的 {@code ItemStack} 还是 NBT（{@code m_41783_()} 返回 {@code CompoundTag}）。
 *       勉强改写成 NBT 判据不划算（NBT 里"哪个键代表能力"毫无统一约定），先不做。</li>
 * </ul>
 *
 * 【刻意不做的事】不尝试识别 Iron Jetpacks / 柴油喷气背包这类**自带燃料推进**的装备：它们的
 * 推力逻辑绑在玩家身上，我们"仿创造飞行"等于凭空绕过它的燃料，属于作弊。想用请自己往列表里加，
 * 并明白这一点。
 */
public final class MaidFreeFlightKit {

    private MaidFreeFlightKit() {
    }

    /* ---------------- 限速与目标点常量（照抄扫帚模式那套已验证的数字，见 MaidBroomDrive） ---------------- */

    /** 到达判定（格）：进这个距离直接零速 = 悬停 */
    public static final double ARRIVE = 0.35;
    /** 到点减速带（格）：距离小于它开始线性降速，到点自然收干不冲过头 */
    public static final double ARRIVE_RAMP = 1.5;
    /** 水平限速（格/tick）：0.35 ≈ 7 格/秒 */
    public static final double MAX_H_SPEED = 0.35;
    /** 垂直限速（格/tick）：比水平慢，免得上下猛蹿 */
    public static final double MAX_V_SPEED = 0.22;
    /** 弧度 → 度（凋灵那行 57.295776F） */
    public static final float DEG = 57.295776F;

    /* ---------------- 探测 ---------------- */

    /** 总口径：她能不能用"仿创造飞行"（开关 + 任一探测路命中） */
    public static boolean isModeActive(EntityMaid maid) {
        try {
            if (maid == null || !maid.m_6084_() || !MaidSmartConfig.MISC_FREE_FLIGHT.get()) {
                return false;
            }
            return hasFlightItem(maid) || hasFlightEffect(maid);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 物品路：双手 / 护甲 / 背包 / 饰品栏 / 额外容器里有没有列表命中的物品 */
    public static boolean hasFlightItem(EntityMaid maid) {
        try {
            List<String> ids = itemIds();
            if (ids.isEmpty()) {
                return false;
            }
            if (matchesAny(maid.m_21205_(), ids) || matchesAny(maid.m_21206_(), ids)) {
                return true;
            }
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                    EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                if (matchesAny(maid.m_6844_(slot), ids)) {
                    return true;
                }
            }
            // 背包（含 TLM 的女仆背包）——getAvailableInv 返回的 MaidInvWrapper 就是 IItemHandler
            try {
                net.minecraftforge.items.IItemHandler inv = maid.getAvailableBackpackInv();
                for (int i = 0; i < inv.getSlots(); i++) {
                    if (matchesAny(inv.getStackInSlot(i), ids)) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
            // TLM 饰品栏（戒指/挂坠这类飞行物品多半在这）
            try {
                com.github.tartaricacid.touhoulittlemaid.inventory.handler.BaubleItemHandler bauble = maid.getMaidBauble();
                for (int i = 0; i < bauble.getSlots(); i++) {
                    if (matchesAny(bauble.getStackInSlot(i), ids)) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
            // 额外容器（精妙背包 / 旅行者背包）——没有饰品栏时它们至少还认背包
            try {
                if (com.maidsmart.tool.MaidExtraContainer.contains(maid, s -> matchesAny(s, ids))) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 效果路：她身上有没有列表命中的药水效果 */
    public static boolean hasFlightEffect(EntityMaid maid) {
        try {
            List<String> ids = effectIds();
            if (ids.isEmpty()) {
                return false;
            }
            for (MobEffectInstance inst : maid.m_21220_()) {
                ResourceLocation key = ForgeRegistries.MOB_EFFECTS.getKey(inst.m_19544_());
                if (key != null && ids.contains(key.toString())) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 一行可读的"现在算不算能飞"诊断（日志/排错用） */
    public static String diag(EntityMaid maid) {
        try {
            return "物品=" + hasFlightItem(maid) + " 效果=" + hasFlightEffect(maid);
        } catch (Throwable t) {
            return "诊断异常：" + t;
        }
    }

    /* ---------------- 列表匹配 ---------------- */

    /**
     * 物品栈是否命中资格表。1.20.1 精简版**只支持两种写法**（1.21.1 那版的 {@code @组件} 两路
     * 依赖数据组件、1.20.5 才有，已砍，见类注释）：
     * <ul>
     *   <li>{@code modid:item} —— 物品 id；</li>
     *   <li>{@code #命名空间:标签} —— 物品标签（走 Forge 的标签管理器，含第三方模组的标签）。</li>
     * </ul>
     */
    public static boolean matchesAny(ItemStack stack, List<String> ids) {
        try {
            if (stack == null || stack.m_41619_()) {
                return false;
            }
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
            String id = key == null ? "" : key.toString();
            for (String raw : ids) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String s = raw.trim();
                if (s.startsWith("#")) {
                    if (matchesTag(stack, s.substring(1))) {
                        return true;
                    }
                } else if (s.equals(id)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 物品标签判定。1.20.1 没有 {@code Registries.ITEM} 这个 ResourceKey 常量
     * （那是 1.21 的写法），Forge 侧走 {@link ITagManager}：
     * {@code ForgeRegistries.ITEMS.tags().createTagKey(...)}——它与原版
     * {@code TagKey.create} 产出的键是同一个（Forge 的标签即原版标签）。
     */
    private static boolean matchesTag(ItemStack stack, String tagId) {
        try {
            ResourceLocation loc = new ResourceLocation(tagId.trim());
            ITagManager<net.minecraft.world.item.Item> tags = ForgeRegistries.ITEMS.tags();
            if (tags == null) {
                return false;
            }
            TagKey<net.minecraft.world.item.Item> tag = tags.createTagKey(loc);
            return stack.m_204117_(tag);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> itemIds() {
        try {
            return (List<String>) MaidSmartConfig.MISC_FREE_FLIGHT_ITEMS.get();
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> effectIds() {
        try {
            return (List<String>) MaidSmartConfig.MISC_FREE_FLIGHT_EFFECTS.get();
        } catch (Throwable ignored) {
            return List.of();
        }
    }
}
