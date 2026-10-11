package com.maidsmart.box;

import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;

/**
 * 压缩盒的禁入清单——**写死的两条**：① 压缩盒本身；② 带附魔的物品（含一切附魔工具/盔甲，附魔书
 * 单独按物品类型判，见 {@link #isEnchantedItem}）。两条的理由都指向同一件事：**放进去一定出事
 * 或者一定亏**。
 *
 * ① 盒子套盒子：玩家看不见里层、还能把自己装进去（界面开着时 Shift+点手上那格，服务端先取出再
 * 写标签，整个盒子连内容物从世界消失）；② 附魔物品不可堆叠，不同附魔组合互相不是同一件东西，5 格
 * 很快满，且"存进去会消失"正是出在这一类上（盒子自定义数量与原版 {@code getMaxStackSize()=1} 对
 * 不上，多出来的那份作为返回值被丢，而 TLM 侧调用点大多不看返回值）。这个盒子的定位是"大批材料
 * 的压缩仓"，不是装备库。
 * 【判据只有一处】"能不能放进去"有**四个**入口（界面点击 {@link CompressionBoxService}、女仆背包
 * 插入 {@link CompressionBoxMaidInv}、溢出回退、女仆顺手塞盒），各自判一遍迟早漏，故只留一个
 * {@link #reason}，所有入口都问它；拒绝时返回**一句人话**，界面与服务端回包共用同一行字。
 */
public final class CompressionBoxFilter {

    /** 压缩盒不能装进压缩盒（与 六百一十七/六百一十八 的措辞保持一致） */
    public static final String MSG_BOX = "\u538b\u7f29\u76d2\u4e0d\u80fd\u88c5\u8fdb\u538b\u7f29\u76d2";
    /** 带附魔的物品（附魔书/附魔武器等） */
    public static final String MSG_ENCHANT =
            "\u5e26\u9644\u9b54\u7684\u7269\u54c1\u4e0d\u80fd\u653e\u8fdb\u538b\u7f29\u76d2";

    private CompressionBoxFilter() {
    }

    /**
     * 能不能放进压缩盒：{@code null} = 可以，非 null = 拒绝，值就是给玩家看的那句话。
     *
     * 三处调用方（界面点击 / 服务端搬运 / 女仆背包）都只看这一个返回值：
     * {@code null} 放行，非 null 就原样退回、并把这句话显示出来。
     */
    public static String reason(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        if (CompressionBoxData.isBox(stack)) {
            return MSG_BOX;
        }
        if (isEnchantedItem(stack)) {
            return MSG_ENCHANT;
        }
        return null;
    }

    /** 只问「行不行」（数据层/女仆那一侧用这个，不需要文案） */
    public static boolean canStore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return true;
        }
        if (CompressionBoxData.isBox(stack)) {
            return false;
        }
        return !isEnchantedItem(stack);
    }

    /**
     * 带附魔的物品（不含压缩盒——那个由 {@link #reason} 先判，文案不一样）。附魔书要单独判，因为
     * {@code ItemStack.isEnchanted()} 只看 {@code ENCHANTMENTS} 组件，而附魔书把附魔存在
     * {@code STORED_ENCHANTMENTS}，一本附魔书在它眼里是"没附魔的"。判物品类型（{@code EnchantedBookItem}）
     * 比读组件更稳——空白附魔书也一并挡住，反正那玩意本来就没用。
     */
    public static boolean isEnchantedItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            if (stack.getItem() instanceof EnchantedBookItem) {
                return true;
            }
            return stack.isEnchanted();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("CompressionBoxFilter", ignored);
            return false;
        }
    }
}
