package com.maidsmart.flight;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * v1.2.5 实测六百五十六【仿创造飞行 · 能力探测】——判断"她身上有什么东西让她能飞"。
 *
 * 【为什么需要这一层】1.21.1 里"能不能飞"对**玩家**是 {@code Player#getAbilities().mayfly}
 * （NBT 里就是 abilities:{mayfly,flying}），而**女仆没有这套 Abilities**（全仓 grep：TLM 本体
 * 除了一处玩家判定，从没碰过 mayfly/Abilities）。所以不存在"读一个通用标记就知道她能飞"的路。
 *
 * 【而且不能指望物品自己生效】绝大多数"给飞行的模组物品"把逻辑写死在玩家身上
 * （`instanceof Player` / Abilities），女仆装着它们**什么都不会发生**——我们在鞘翅那条线上
 * 已经踩过同一个坑（见 MaidFlightKit.elytraDiagnostic 的那行提示）。所以这个功能的本质是
 * **仿创造飞行**：由我们给她悬浮与升降，物品只是"资格凭证"。
 *
 * 【三路探测，只认确定的、可配置的，绝不猜】
 * <ol>
 *   <li>{@code freeFlightItems}（默认空）：物品 id 列表，可写 {@code #命名空间:标签} 认整条标签；
 *       扫描范围 = 双手 / 护甲 / 背包 / TLM 饰品栏 / 额外容器（精妙背包等）；</li>
 *   <li>{@code freeFlightEffects}（默认空）：药水效果 id 列表（效果挂在实体上，这一路对女仆天然有效）；</li>
 *   <li>{@code freeFlightGravity}（默认开）：**通用启发式**——她的 {@code Attributes.GRAVITY} ≈ 0
 *       就算数（任何"重力归零"型来源都自动覆盖，不需要点名模组）。她在飘，我们只是把飘变成可控飞行。
 *       注意这条需要 1.21.1（GRAVITY 属性 1.20.5 才有），本文件属 neo 树。</li>
 * </ol>
 *
 * 【刻意不做的事】不尝试识别 Iron Jetpacks / MI 柴油喷气背包这类**自带燃料推进**的装备：它们的
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
            if (maid == null || !maid.isAlive() || !MaidSmartConfig.MISC_FREE_FLIGHT.get()) {
                return false;
            }
            return hasFlightItem(maid) || hasFlightEffect(maid) || gravityFreed(maid);
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
            if (matchesAny(maid.getMainHandItem(), ids) || matchesAny(maid.getOffhandItem(), ids)) {
                return true;
            }
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                    EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                if (matchesAny(maid.getItemBySlot(slot), ids)) {
                    return true;
                }
            }
            try {
                net.neoforged.neoforge.items.IItemHandler inv = maid.getAvailableBackpackInv();
                for (int i = 0; i < inv.getSlots(); i++) {
                    if (matchesAny(inv.getStackInSlot(i), ids)) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
            // TLM 饰品栏（curios 类物品真正待的地方——戒指/挂坠这类飞行物品多半在这）
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
            for (MobEffectInstance inst : maid.getActiveEffects()) {
                ResourceLocation key = BuiltInRegistries.MOB_EFFECT.getKey(inst.getEffect().value());
                if (key != null && ids.contains(key.toString())) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 通用启发式路：重力属性被抹平 = 她本来就在飘（任何"重力归零"型来源都覆盖）。
     *
     * 【为什么要它】配置表永远追不上整合包里的每一个模组；而"重力没了"这个信号是模组无关的。
     * 她既然飘着，我们就接管成"可控飞行"，而不是让她原地悬空晃。
     */
    public static boolean gravityFreed(EntityMaid maid) {
        try {
            if (!MaidSmartConfig.MISC_FREE_FLIGHT_GRAVITY.get()) {
                return false;
            }
            double g = maid.getAttributeValue(Attributes.GRAVITY);
            return g <= 0.001;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 一行可读的"现在算不算能飞"诊断（日志/排错用） */
    public static String diag(EntityMaid maid) {
        try {
            return "物品/组件=" + hasFlightItem(maid) + " 效果=" + hasFlightEffect(maid)
                    + " 重力=" + String.format("%.3f", maid.getAttributeValue(Attributes.GRAVITY))
                    + " 判定=" + gravityFreed(maid);
        } catch (Throwable t) {
            return "诊断异常：" + t;
        }
    }

    /* ---------------- 列表匹配 ---------------- */

    /**
     * 物品栈是否命中资格表。条目语法（v1.2.5 实测六百五十九 起支持第 3/4 种）：
     * <ul>
     *   <li>{@code modid:item} —— 物品 id；</li>
     *   <li>{@code #命名空间:标签} —— 物品标签；</li>
     *   <li>{@code @命名空间:组件} —— **有该数据组件**就算格；</li>
     *   <li>{@code @命名空间:组件~文本} —— 该组件的值里包含这段文本才算格
     *       （例：{@code @apothic_attributes:bonus_stack_attribute_modifiers~neoforge:creative_flight}）。</li>
     * </ul>
     *
     * 【为什么需要组件那两路（实测六百五十九）】很多整合包用**数据组件**授予能力，
     * 而不是换一件物品：例如神化（Apotheosis）的 Apothic Attributes 用命令给一件胸甲挂
     * {@code neoforge:creative_flight} 修饰符——物品 id 没变、物品标签也匹配不到
     * （{@code #neoforge:creative_flight} 是**修饰符 id**，不是物品标签），
     * 只能按"组件存在 + 组件值含某段文本"来认。
     */
    public static boolean matchesAny(ItemStack stack, List<String> ids) {
        try {
            if (stack == null || stack.isEmpty()) {
                return false;
            }
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            String id = key == null ? "" : key.toString();
            for (String raw : ids) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String s = raw.trim();
                if (s.startsWith("#")) {
                    TagKey<net.minecraft.world.item.Item> tag = TagKey.create(Registries.ITEM,
                            ResourceLocation.parse(s.substring(1)));
                    if (stack.is(tag)) {
                        return true;
                    }
                } else if (s.startsWith("@")) {
                    if (matchesComponent(stack, s.substring(1))) {
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
     * {@code @组件} / {@code @组件~文本} 的判定。
     *
     * 实现口径：按 id 从**数据组件注册表**取到类型 → {@code stack.has(type)} 判存在 →
     * 需要文本时把**该组件自己的值**（不是整个组件包的文本）转成字符串再找子串。
     * 只取该组件自己的值可以避免"别的组件里出现同一个词"造成的误判。
     */
    private static boolean matchesComponent(ItemStack stack, String spec) {
        try {
            if (spec == null || spec.isBlank()) {
                return false;
            }
            int tilde = spec.indexOf('~');
            String compId = tilde >= 0 ? spec.substring(0, tilde).trim() : spec.trim();
            String needle = tilde >= 0 ? spec.substring(tilde + 1).trim() : null;
            if ("*".equals(compId)) {
                // @*~文本：任意组件命中即可（更宽——用它要心里有数）
                if (needle == null || needle.isBlank()) {
                    return !stack.getComponents().isEmpty();
                }
                return String.valueOf(stack.getComponents()).contains(needle);
            }
            net.minecraft.core.component.DataComponentType<?> type =
                    BuiltInRegistries.DATA_COMPONENT_TYPE.get(ResourceLocation.parse(compId));
            if (type == null || !stack.has(type)) {
                return false;
            }
            if (needle == null || needle.isBlank()) {
                return true;   // 只要求"有这个组件"
            }
            return String.valueOf(stack.get(type)).contains(needle);
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
