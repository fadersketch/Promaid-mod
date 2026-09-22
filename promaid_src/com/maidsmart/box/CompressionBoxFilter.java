package com.maidsmart.box;

import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 压缩盒的禁入清单（v1.2.2 实测六百二十）——用户反馈「在压缩盒界面内无法放入
 * 附魔书/附魔武器和压缩盒，压缩盒在这个界面内无法被鼠标选中，并再次提示玩家
 * 不能把压缩盒放进压缩袋里」。
 *
 * ── 为什么要有这道门 ──
 * 三条判据、三个理由，都指向同一件事：**放进去一定出事或者一定亏**。
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
 *   <li><b>配置里的禁入清单</b>：上面两条是写死的口径，这一条留给玩家/整合包自己加
 *       （比如「火药别进盒子」）。表里写完整注册名，见配置项
 *       {@code compressionBox.refuseList}。</li>
 * </ol>
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
    /** 配置的禁入清单命中 */
    public static final String MSG_LIST =
            "\u8fd9\u4e2a\u7269\u54c1\u5728\u538b\u7f29\u76d2\u7684\u7981\u5165\u6e05\u5355\u91cc";

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
        if (blockEnchanted() && isEnchantedItem(stack)) {
            return MSG_ENCHANT;
        }
        if (inRefuseList(stack)) {
            return MSG_LIST;
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
        if (blockEnchanted() && isEnchantedItem(stack)) {
            return false;
        }
        return !inRefuseList(stack);
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

    /** 配置里的禁入清单（完整注册名；表本身在 {@code compressionBox.refuseList}） */
    public static boolean inRefuseList(ItemStack stack) {
        Set<String> ids = refuseSet();
        if (ids.isEmpty()) {
            return false;
        }
        String id = idOf(stack);
        return id != null && ids.contains(id);
    }

    /** 这个堆的注册名（拿不到就 null） */
    public static String idOf(ItemStack stack) {
        try {
            net.minecraft.resources.ResourceLocation key =
                    ForgeRegistries.ITEMS.getKey(stack.m_41720_());
            return key == null ? null : key.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 禁入清单 → Set（配置每次读都重新建：表很小，读一次比维护缓存失效省事） */
    private static Set<String> refuseSet() {
        Set<String> out = new HashSet<>();
        try {
            List<? extends String> raw = com.maidsmart.config.MaidSmartConfig.COMPRESSION_BOX_REFUSE_LIST.get();
            for (String s : raw) {
                if (s != null && !s.isBlank()) {
                    out.add(s.trim());
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 「带附魔物品」这条总开关（配置可关——关了就只有压缩盒与禁入清单还拦着） */
    public static boolean blockEnchanted() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMPRESSION_BOX_REFUSE_ENCHANTED.get();
        } catch (Throwable ignored) {
            return true;
        }
    }
}
