package com.maidsmart.action;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 「口渴」Thirst Was Taken（dev.ghen.thirst，modid=thirst）软兼容（实测五百七十一）。
 *
 * 需求原文："能让女仆喂水吗（软联动口渴mod），这块的代码直接照搬女仆喂主人食物就可以了。
 * 把判定位点改为口渴值即可。……触发效果和玩家自己喝水是一样的（也就是说会返还玻璃瓶这种）"
 *
 * 【1.20.1-1.4.0 API 形态（反编译实证；1.21.1-2.1.5 只差数据挂载方式，见下）】
 * - 口渴数据：挂在玩家的 Capability（{@code ModCapabilities.PLAYER_THIRST}，
 *   {@code IThirst.getThirst()}，0-20）；1.21.1 改成 AttachmentType（同字段语义）；
 * - 喝水入口：{@code PlayerThirst.drink(ItemStack, Player)}（两版同名静态方法）——
 *   玩家自己"喝完一杯"时 TWT 的钩子（LivingEntityUseItemEvent.Finish）就调它；
 * - 哪些物品能回口渴：{@code ThirstHelper.itemRestoresThirst(ItemStack)}——TWT 自己的
 *   饮品表（config 可扩，水瓶/陶碗水默认在表）+ 关键词匹配，其他 mod 的装水工具
 *   只要在它的表里就认识；增量：{@code getThirst / getQuenched}（喂水选优用）。
 *
 * 【喂水为什么不直接调 drink】主人"自己喝"必须走物品自己的 {@code finishUsingItem}——
 * 药水瓶的口渴在 TWT 的 {@code MixinPotionItem}（注入 finishUsingItem 头部）、
 * TWT 自家 DrinkableItem 在自己的 finishUsingItem、可食用饮品走 FoodData 路径，
 * 容器返还（玻璃瓶/陶碗/空桶）也全在 finishUsingItem 里。本类
 * {@link #applyHookThirst} 只补最后一类缺口：物品在 TWT 饮品表里、但既非药水瓶
 * 也非 TWT 自家物品也非可食用（其他 mod 的装水容器多属此类）——它们的口渴靠
 * TWT 的完成钩子，而钩子只在"玩家真实手持右键喝完"时触发，女仆代喂绕过了它，
 * 所以按钩子自己的排除口径（药水瓶/可食用/TWT 自家物品都不补）直接调统一入口。
 * 这样与玩家自己喝完全同效、且绝不重复计数。
 */
public final class ThirstCompat {
    private ThirstCompat() {
    }

    /** 口渴模组是否在场（喂水整条链 + 配置项注册的总闸） */
    public static boolean available() {
        try {
            return net.minecraftforge.fml.ModList.get().isLoaded("thirst");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ---------------- 惰性反射句柄 ----------------
     * [0]=ThirstHelper.itemRestoresThirst(ItemStack)
     * [1]=ThirstHelper.getThirst(ItemStack)
     * [2]=ThirstHelper.getQuenched(ItemStack)
     * [3]=PlayerThirst.drink(ItemStack,Player)
     * [4]=Capability<IThirst>（ModCapabilities.PLAYER_THIRST 静态字段值）
     * [5]=IThirst.getThirst() 实例方法
     * [6]=DrinkableItem 类（排除判定用）
     * 空数组 = 探测失败（该 mod 版本变化 → 保守禁用喂水，行为与未装一致） */
    private static volatile Object[] API;
    private static final Object API_LOCK = new Object();

    private static Object[] api() {
        Object[] api = API;
        if (api != null) {
            return api;
        }
        synchronized (API_LOCK) {
            if (API == null) {
                try {
                    Class<?> helper = Class.forName("dev.ghen.thirst.api.ThirstHelper");
                    Class<?> thirst = Class.forName("dev.ghen.thirst.content.thirst.PlayerThirst");
                    Class<?> caps = Class.forName(
                            "dev.ghen.thirst.foundation.common.capability.ModCapabilities");
                    Class<?> drinkable = Class.forName(
                            "dev.ghen.thirst.foundation.common.item.DrinkableItem");
                    // 实测五百七十六【水质分级】：再加两个水质句柄（0 肮脏 … 3 纯净）——
                    // 挂在 WaterPurity 上（静态，两版同名同签名，1.20.1-1.4.0 / 1.21.1-2.1.5 反编译实证）
                    Class<?> purity = Class.forName("dev.ghen.thirst.content.purity.WaterPurity");
                    java.lang.reflect.Field capField = caps.getField("PLAYER_THIRST");
                    capField.setAccessible(true);
                    API = new Object[]{
                            helper.getMethod("itemRestoresThirst", ItemStack.class),
                            helper.getMethod("getThirst", ItemStack.class),
                            helper.getMethod("getQuenched", ItemStack.class),
                            thirst.getMethod("drink", ItemStack.class,
                                    net.minecraft.world.entity.player.Player.class),
                            capField.get(null),
                            thirst.getMethod("getThirst"),
                            drinkable,
                            purity.getMethod("isWaterFilledContainer", ItemStack.class),
                            purity.getMethod("getPurity", ItemStack.class)};
                } catch (Throwable t) {
                    API = new Object[0];
                }
            }
            return API;
        }
    }

    /**
     * 主人当前口渴值（0-20）。读不到（意外情况）返回 null，调用方按"不知道"跳过本轮。
     * 1.20.1：Capability——player.getCapability(PLAYER_THIRST, null) 的 LazyOptional。
     */
    public static Integer ownerThirst(ServerPlayer owner) {
        Object[] api = api();
        if (api == null || api.length == 0) {
            return null;
        }
        try {
            Object lazy = owner.getCapability(
                    (net.minecraftforge.common.capabilities.Capability<?>) api[4], null);
            if (lazy == null) {
                return null;
            }
            Object data = ((net.minecraftforge.common.util.LazyOptional<?>) lazy).orElse(null);
            if (data == null) {
                return null;
            }
            return (Integer) ((java.lang.reflect.Method) api[5]).invoke(data);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 该物品是否在 TWT 的饮品/食水表里（能回口渴值）——白名单之外的硬门槛 */
    public static boolean itemRestoresThirst(ItemStack stack) {
        Object[] api = api();
        if (api == null || api.length == 0) {
            return false;
        }
        try {
            return (Boolean) ((java.lang.reflect.Method) api[0]).invoke(null, stack);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 药水瓶是否为纯净水（水瓶）——其它药水（女仆代喂等于替主人做主吃效果）永不进候选。
     * 1.20.1：水瓶 = POTION 物品 + 药水内容 = WATER（TWT 自家 WaterPurity 同判据，
     * SRG 逐名对上：m_43579_ = getPotion、f_43599_ = WATER、f_42589_ = POTION）。
     */
    public static boolean isWaterPotion(ItemStack stack) {
        try {
            return stack.m_41720_() == net.minecraft.world.item.Items.f_42589_
                    && net.minecraft.world.item.alchemy.PotionUtils.m_43579_(stack)
                    == net.minecraft.world.item.alchemy.Potions.f_43599_;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 饮品的口渴恢复量（选优依据；读不到返回 -1） */
    public static int drinkHydration(ItemStack stack) {
        Object[] api = api();
        if (api == null || api.length == 0) {
            return -1;
        }
        try {
            return (Integer) ((java.lang.reflect.Method) api[1]).invoke(null, stack);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 饮品的解渴缓冲量（同分时的次级选优依据；读不到返回 -1） */
    public static int drinkQuenched(ItemStack stack) {
        Object[] api = api();
        if (api == null || api.length == 0) {
            return -1;
        }
        try {
            return (Integer) ((java.lang.reflect.Method) api[2]).invoke(null, stack);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * 复刻 TWT"玩家喝完"钩子的排除口径，直接调它的统一入口
     * {@code PlayerThirst.drink}（内部含 itemRestoresThirst 复查 + 纯度效果）。
     * 只补 finishUsingItem 覆盖不到的那类饮品（其他 mod 的装水容器）；
     * 药水瓶 / TWT 自家 DrinkableItem / 可食用饮品在 finishUsingItem 里已经加过口渴，
     * 这里再调就会双重计数——排除名单与 TWT 自己的钩子一字不差。
     */
    public static void applyHookThirst(ItemStack stack, ServerPlayer owner) {
        Object[] api = api();
        if (api == null || api.length == 0) {
            return;
        }
        try {
            net.minecraft.world.item.Item item = stack.m_41720_();
            if (item instanceof net.minecraft.world.item.PotionItem) {
                return; // 药水瓶：MixinPotionItem 注入 finishUsingItem 头部，已经加过
            }
            if (((Class<?>) api[6]).isInstance(item)) {
                return; // TWT 自家 DrinkableItem：自己的 finishUsingItem 里加过
            }
            if (item.m_41472_()) {
                return; // 可食用饮品：eat → FoodData 路径加过（m_41472_ = isEdible）
            }
            ((java.lang.reflect.Method) api[3]).invoke(null, stack, owner);
        } catch (Throwable ignored) {
        }
    }

    /* ================= 实测五百七十六：水质分级（反馈："喂脏水反而耽误玩家"） ================= */

    /**
     * 这杯饮品的水质等级：**0 肮脏 / 1 有点脏 / 2 可接受的 / 3 纯净**
     * （口渴模组自己的 WaterPurity，lang 键 thirst.purity.* 四档实证）。
     *
     * @return -1 = **没有水质概念**（不是"装水容器"，比如果汁/牛奶）或模组不在场/取不到——
     *         调用方据此**不做**水质过滤，避免把无关饮品一起挡掉
     */
    public static int waterPurity(ItemStack stack) {
        Object[] api = api();
        if (api == null || api.length <= 8 || stack == null || stack.m_41619_()) {
            return -1;
        }
        try {
            Object isWater = ((java.lang.reflect.Method) api[7]).invoke(null, stack);
            if (!Boolean.TRUE.equals(isWater)) {
                return -1; // 不是装水容器 → 没有水质
            }
            Object p = ((java.lang.reflect.Method) api[8]).invoke(null, stack);
            return p instanceof Integer i ? i : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 水质等级的中文名（悬停提示用）；未知返回 null */
    public static String purityLabel(int purity) {
        switch (purity) {
            case 0: return "\u00a7c肮脏";
            case 1: return "\u00a7e有点脏";
            case 2: return "\u00a7a可接受的";
            case 3: return "\u00a7b纯净";
            default: return null;
        }
    }

    /** 水质等级的显示颜色（悬停提示用） */
    public static int purityColor(int purity) {
        switch (purity) {
            case 0: return 0xFF5555;
            case 1: return 0xFFAA00;
            case 2: return 0x55FF55;
            case 3: return 0x55FFFF;
            default: return 0xAAAAAA;
        }
    }

    /** 该物品是不是"装水容器"（水瓶/陶碗/其他模组的装水工具）——用于面板提示 */
    public static boolean isWaterContainer(ItemStack stack) {
        return waterPurity(stack) >= 0;
    }

}
