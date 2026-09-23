package com.maidsmart.box;

import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;

/**
 * 压缩盒的禁入清单（v1.2.2 实测六百二十；v1.2.4 实测六百四十五 收敛为写死的两条）——
 * 用户反馈「在压缩盒界面内无法放入附魔书/附魔武器和压缩盒，压缩盒在这个界面内无法被
 * 鼠标选中，并再次提示玩家不能把压缩盒放进压缩袋里」。
 *
 * ── 为什么要有这道门 ──
 * 两条判据、两个理由，都指向同一件事：**放进去一定出事或者一定亏**。
 * <ol>
 *   <li><b>压缩盒本身</b>（六百一十七修过的那个 bug）：盒子套盒子，玩家看不见里层，
 *       还能把自己装进去——界面开着时 Shift+点手上那一格，服务端先把它从背包取出来
 *       再写标签，**整个盒子连里面的东西**就从世界里消失了。这条以前只在
 *       {@link CompressionBoxData#mergeInto} 里挡着，界面点击那条路上仍然会把盒子
 *       拿在鼠标上（然后卡在那里），现在连「拿起来」都不允许；</li>
 *   <li><b>带附魔的物品</b>（用户点名的附魔书/附魔武器，含一切附魔工具/盔甲）：
 *       盒子的格数是 5，而附魔物品是**不可堆叠**的——不同附魔组合互相不是同一件东西，
 *       一格只能放一种组合，5 格很快就满；更要紧的是六百一十八之前「附魔类物品存进去
 *       会消失」正是出在这一类上（盒子的自定义数量与原版 {@code getMaxStackSize()=1}
 *       对不上，交给原版时多出来的那份作为返回值被丢掉，而 TLM 那一侧几十处调用点
 *       大多不看返回值）。这个盒子本来的定位是「大批材料的压缩仓」，不是装备库——
 *       索性把这一类挡在外面，比事后补更省心。附魔书要单独判：它的附魔存在
 *       {@code StoredEnchantments} 里，{@code ItemStack.isEnchanted()} 看不见它
 *       （javap 实证 1.20.1 只读 {@code Enchantments}）；</li>
 * </ol>
 *
 * ── v1.2.4 实测六百四十五：为什么把「开关 + 自定义清单」删掉 ──
 * 六百二十 那版把第 2 条做成了配置开关（{@code compressionBox.refuseEnchanted}），
 * 另配一张自定义禁入清单（{@code compressionBox.refuseList}，默认空）。实际用法说明
 * 这两条都不该存在：
 * <ul>
 *   <li>开关关掉之后，上面第 2 条理由**一个字都没变**（附魔物品照样不可堆叠、照样是
 *       「存进去会消失」的那一类），只是把已经堵上的坑重新打开——那不是可选项；</li>
 *   <li>自定义清单要求玩家手写**完整注册名**（{@code minecraft:gunpowder} 这种），而它
 *       唯一的效果是"让女仆看不见某样东西"。玩家真正想挡的东西（附魔物）已经写死了，
 *       留一张要手写注册名的表只会变成"填了没生效"的咨询来源。</li>
 * </ul>
 * 这一版把判据收敛成**写死的两条**：盒子本身、带附魔的物品。配置里那两行一并删除
 * （老配置文件里残留的 {@code refuseEnchanted} / {@code refuseList} 不再被读取，
 * 不影响启动）。
 *
 * ── 为什么是一个方法给三处用 ──
 * 「能不能放进去」这个问题有**四个**入口：界面点击（{@link CompressionBoxService}）、
 * 女仆背包插入（{@link CompressionBoxMaidInv}）、溢出回退、以及女仆自己「把东西顺手
 * 塞进盒子」那条路。四个入口各自判一遍迟早会漏（六百一十七的盒子套盒子就是只在数据层
 * 挡了、界面那条路没挡），所以这里只留一个判据 {@link #reason}，所有入口都问它。
 *
 * ── 提示口径 ──
 * 拒绝时给的是**一句人话**（{@link #reason} 的返回值），界面拿它画红字、服务端拿它
 * 回包给客户端画同一行字——从哪条路被拒，玩家看到的都是同一句。
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
        if (stack == null || stack.m_41619_()) {
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
        if (stack == null || stack.m_41619_()) {
            return true;
        }
        if (CompressionBoxData.isBox(stack)) {
            return false;
        }
        return !isEnchantedItem(stack);
    }

    /**
     * 带附魔的物品（不含压缩盒——那个由 {@link #reason} 先判，文案不一样）。
     *
     * 【为什么附魔书要单独判】{@code ItemStack.m_41793_()}（javap 实证 = isEnchanted）
     * 只看 NBT 里的 {@code Enchantments}：附魔书把附魔存在 {@code StoredEnchantments}，
     * 所以一本附魔书在它眼里是「没附魔的」。判物品类型（{@code EnchantedBookItem}）
     * 比去读 StoredEnchantments 更稳——空白的附魔书也一并挡住，反正那玩意本来就没用。
     */
    public static boolean isEnchantedItem(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return false;
        }
        try {
            if (stack.m_41720_() instanceof EnchantedBookItem) {
                return true;
            }
            return stack.m_41793_();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
