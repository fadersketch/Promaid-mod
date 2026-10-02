package com.maidsmart.bd;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 超越维度（BeyondDimensions，modid = {@code beyonddimensions}，作者 WinterCogs）的**软兼容层**。
 * 范式与 {@link com.maidsmart.combat.GunCompat}（枪械）、{@link com.maidsmart.goety.MaidGoetyCompat}
 * 一致：**全程反射，能调就调、调不到就当没有**。
 *
 * <h2>API 事实（本地源码 {@code 1.21.1} 分支 + 实例里的 0.7.30 jar 双向核对）</h2>
 * <pre>
 *   DimensionsNet.hasAnyNet(Player)              : static boolean
 *   DimensionsNet.getPrimaryNetFromPlayer(Player): static @Nullable DimensionsNet
 *   DimensionsNet.getAllNetFromPlayer(Player)    : static List&lt;DimensionsNet&gt;
 *   DimensionsNet#getUnifiedStorage()            : UnifiedStorage
 *   UnifiedStorage#getStorage()                  : 可枚举的内容（KeyAmount 序列）
 *   UnifiedStorage#insert(IStackKey, long, boolean simulate)      : KeyAmount（**返回剩余量**）
 *   UnifiedStorage#extract(IStackKey, long, boolean simulate, boolean fuzzy) : KeyAmount（**返回取出的量**）
 *   UnifiedStorage#extract(TagKey, long, boolean simulate)        : KeyAmount ← **按标签取**（"任意一种木板"）
 *   KeyAmount                                    : record(IStackKey key, long amount)
 *   ItemStackKey                                 : new ItemStackKey(ItemStack) / getReadOnlyStack()
 * </pre>
 *
 * <h3>三条关键结论</h3>
 * <ol>
 *   <li><b>网络是"按玩家"的</b>：入口全是 {@code Player}。所以女仆要用的是**主人的**网络
 *       （她自己不是玩家、实体侧没有 capability；方块侧才有）。</li>
 *   <li><b>"她有多少"不需要遍历</b>：{@code extract(key, Long.MAX_VALUE, true, false)} 模拟一次，
 *       返回的 amount 就是"能取出多少" = 库存量（O(1)）。遍历 {@code getStorage()} 只在"列出内容"时才用。</li>
 *   <li><b>按物品查是 O(1)、按标签查也是 O(1)</b>；但 {@code fuzzy=true} 是线性扫描（源码注释自己写了），
 *       所以精度优先用 key、模糊匹配优先用 TagKey，尽量避免 fuzzy。</li>
 * </ol>
 *
 * <p>【包名兜底】上游 API 未发 maven、分支间类名有差异，所以这里按候选包名顺序试，
 * 命中哪个用哪个；全都解析不到就 {@link #available()} 返回 false。
 */
public final class MaidBdCompat {

    /** modid（{@code ModList.get().isLoaded} 用）。 */
    public static final String MOD_ID = "beyonddimensions";

    /** 候选包根（上游未发 maven，分支间类名可能不同）。 */
    private static final String[] ROOTS = {
            "com.wintercogs.beyonddimensions.api",
            "com.wintercogs.beyonddimensions",
    };

    private static boolean inited;
    private static boolean ok;

    private static Class<?> cNet;
    private static Class<?> cStorage;
    private static Class<?> cKey;
    private static Class<?> cKeyAmount;
    private static Method mHasAnyNet;
    private static Method mGetPrimary;
    private static Method mGetAllNets;
    private static Method mGetUnifiedStorage;
    private static Method mGetStorage;
    private static Method mExtractKey;
    private static Method mExtractTag;
    private static Method mInsert;
    private static java.lang.reflect.Constructor<?> mItemStackKeyCtor;
    private static Method mReadOnlyStack;
    private static Method mAmount;
    private static Method mKey;

    private MaidBdCompat() {
    }

    private static synchronized void init() {
        if (inited) {
            return;
        }
        inited = true;
        for (String root : ROOTS) {
            try {
                Class<?> net = Class.forName(root + ".dimensionnet.DimensionsNet");
                Class<?> storage = Class.forName(root + ".dimensionnet.UnifiedStorage");
                Class<?> key = Class.forName(root + ".storage.key.IStackKey");
                Class<?> itemKey = Class.forName(root + ".storage.key.impl.ItemStackKey");
                Class<?> keyAmount = Class.forName(root + ".storage.key.KeyAmount");
                cNet = net;
                cStorage = storage;
                cKey = key;
                cKeyAmount = keyAmount;
                mHasAnyNet = net.getMethod("hasAnyNet", Player.class);
                mGetPrimary = net.getMethod("getPrimaryNetFromPlayer", Player.class);
                mGetAllNets = net.getMethod("getAllNetFromPlayer", Player.class);
                mGetUnifiedStorage = net.getMethod("getUnifiedStorage");
                mGetStorage = storage.getMethod("getStorage");
                mExtractKey = storage.getMethod("extract", key, long.class, boolean.class, boolean.class);
                mExtractTag = storage.getMethod("extract", net.minecraft.tags.TagKey.class, long.class, boolean.class);
                mInsert = storage.getMethod("insert", key, long.class, boolean.class);
                mItemStackKeyCtor = itemKey.getConstructor(ItemStack.class);
                mReadOnlyStack = itemKey.getMethod("getReadOnlyStack");
                mAmount = keyAmount.getMethod("amount");
                mKey = keyAmount.getMethod("key");
                ok = true;
                return;
            } catch (Throwable ignored) {
                // 换下一个包根
            }
        }
        ok = false;
    }

    /**
     * 总开关：{@code config/promaid-common.toml} 里的 {@code misc.bdStorage}（**默认关**）。
     *
     * <p>【为什么要有它】本功能会**真实搬动物品**（从她背包或掉落物里扣除、插进维度网络），
     * 且只在装了超越维度时才有意义——一个玩家没主动开过的第三方存储联动不该默认生效。
     * 它和"每只女仆自己的开关"（{@code /maid_smart bd_deposit true}，存 persistentData）构成两级门禁。
     */
    public static boolean enabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.MISC_BD_STORAGE.get();
        } catch (Throwable t) {
            return false;   // 读不到就当关（宁可不动）
        }
    }

    /** 反射路径能不能用。 */
    public static boolean available() {
        init();
        return ok;
    }

    /**
     * 物品注册名（{@code minecraft:coal} 这种）。
     * 【G-15】本来借用 Goety 兼容层里那个同名方法，但那样"超越维度"就依赖"Goety"了——
     * 两个功能各自要提 PR，必须互不依赖，所以这里自带一份。
     */
    public static String itemId(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) {
                return "";
            }
            ResourceLocation rl = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            return rl == null ? "" : rl.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 这只女仆的主人（没有就是 null）。 */
    public static Player ownerOf(EntityMaid maid) {
        try {
            LivingEntity owner = maid == null ? null : maid.getOwner();
            return owner instanceof Player p ? p : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 主人有没有任何网络。 */
    public static boolean hasAnyNet(Player player) {
        init();
        if (!ok || player == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(mHasAnyNet.invoke(null, player));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 主人的主网络（没有就是 null）。 */
    public static Object primaryNet(Player player) {
        init();
        if (!ok || player == null) {
            return null;
        }
        try {
            return mGetPrimary.invoke(null, player);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 主人全部网络的数量。 */
    public static int netCount(Player player) {
        init();
        if (!ok || player == null) {
            return 0;
        }
        try {
            Object list = mGetAllNets.invoke(null, player);
            return list instanceof java.util.Collection<?> c ? c.size() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Object storageOf(Object net) {
        if (!ok || net == null) {
            return null;
        }
        try {
            return mGetUnifiedStorage.invoke(net);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 网络的 id / 名字（诊断用）。 */
    public static String netDescribe(Object net) {
        if (net == null) {
            return "无";
        }
        try {
            Object id = cNet.getMethod("getId").invoke(net);
            Object name = cNet.getMethod("getNetworkName").invoke(net);
            return "#" + id + "（" + name + "）";
        } catch (Throwable t) {
            return "（读取失败）";
        }
    }

    /** 一条内容：物品 id + 数量（非物品类 key 显示 typeId）。 */
    public record Line(String id, long amount) {
    }

    /** 列出网络的全部内容（最多 {@code limit} 条；limit <= 0 表示不限）。 */
    public static List<Line> contents(Object net, int limit) {
        init();
        List<Line> out = new ArrayList<>();
        if (!ok || net == null) {
            return out;
        }
        try {
            Object storage = storageOf(net);
            Object seq = mGetStorage.invoke(storage);
            if (seq instanceof Iterable<?> it) {
                for (Object ka : it) {
                    if (limit > 0 && out.size() >= limit) {
                        break;
                    }
                    long amount = ((Number) mAmount.invoke(ka)).longValue();
                    Object key = mKey.invoke(ka);
                    String id = keyId(key);
                    out.add(new Line(id, amount));
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 网络内容种类数（不建列表，省内存）。 */
    public static int contentKinds(Object net) {
        init();
        if (!ok || net == null) {
            return 0;
        }
        try {
            Object seq = mGetStorage.invoke(storageOf(net));
            if (seq instanceof java.util.Collection<?> c) {
                return c.size();
            }
            int n = 0;
            if (seq instanceof Iterable<?> it) {
                for (Object ignored : it) {
                    n++;
                }
            }
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** key → 可读 id（物品 key 走 getReadOnlyStack，其它类型退回 typeId）。 */
    private static String keyId(Object key) {
        if (key == null) {
            return "?";
        }
        try {
            if (mReadOnlyStack.getDeclaringClass().isInstance(key)) {
                ItemStack st = (ItemStack) mReadOnlyStack.invoke(key);
                String id = MaidBdCompat.itemId(st);
                return id.isEmpty() ? String.valueOf(key) : id;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object t = cKey.getMethod("getTypeId").invoke(key);
            return String.valueOf(t);
        } catch (Throwable t) {
            return String.valueOf(key);
        }
    }

    /** 「她有多少」：模拟取一次（O(1)），返回库存量。 */
    public static long countOf(Object net, ItemStack stack) {
        init();
        if (!ok || net == null || stack == null || stack.isEmpty()) {
            return 0;
        }
        try {
            Object key = mItemStackKeyCtor.newInstance(stack);
            Object ka = mExtractKey.invoke(storageOf(net), key, Long.MAX_VALUE, true, false);
            return ((Number) mAmount.invoke(ka)).longValue();
        } catch (Throwable t) {
            return -1;   // -1 = 查询失败（与"0 个"区分开）
        }
    }

    /** 取出来的一堆东西：栈 + 数量（按标签取时，"是哪种物品"由库里返回）。 */
    public record Taken(ItemStack stack, long amount) {
    }

    /** 物品 id → 栈（{@code item:} 前缀可有可无）；认不出来返回空栈。 */
    public static ItemStack stackOf(String rawId) {
        try {
            String s = rawId.startsWith("item:") ? rawId.substring(5) : rawId;
            ResourceLocation rl = ResourceLocation.tryParse(s);
            if (rl == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(rl)) {
                return ItemStack.EMPTY;
            }
            return new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl));
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * 按标签取（"任意一种符合标签的东西"）。库里返回 {@code KeyAmount}，其中的 key 告诉我们
     * **实际给的是哪一种物品**——所以补货时"用不同种类的石头搭路 / 不同 mod 的火把照明"才成立。
     */
    public static Taken extractByTag(Object net, net.minecraft.tags.TagKey<?> tag, long want) {
        init();
        if (!ok || net == null || tag == null || want <= 0) {
            return null;
        }
        try {
            Object ka = mExtractTag.invoke(storageOf(net), tag, want, false);
            long amount = ((Number) mAmount.invoke(ka)).longValue();
            if (amount <= 0) {
                return null;
            }
            ItemStack st = readStackOf(mKey.invoke(ka));
            if (st == null || st.isEmpty()) {
                return null;
            }
            ItemStack copy = st.copy();
            copy.setCount((int) Math.min(Integer.MAX_VALUE, amount));
            return new Taken(copy, amount);
        } catch (Throwable t) {
            return null;
        }
    }

    private static ItemStack readStackOf(Object key) {
        try {
            if (key != null && mReadOnlyStack.getDeclaringClass().isInstance(key)) {
                Object o = mReadOnlyStack.invoke(key);
                return o instanceof ItemStack s ? s : null;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 真的取出来（{@code simulate=false}）；返回取到的数量，-1 = 失败。 */
    public static long extract(Object net, ItemStack stack, long want) {
        init();
        if (!ok || net == null || stack == null || stack.isEmpty() || want <= 0) {
            return 0;
        }
        try {
            Object key = mItemStackKeyCtor.newInstance(stack);
            Object ka = mExtractKey.invoke(storageOf(net), key, want, false, false);
            return ((Number) mAmount.invoke(ka)).longValue();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 存进去；返回**剩余量**（0 = 全存进去了），-1 = 失败。 */
    public static long insert(Object net, ItemStack stack, long amount) {
        init();
        if (!ok || net == null || stack == null || stack.isEmpty() || amount <= 0) {
            return -1;
        }
        try {
            Object key = mItemStackKeyCtor.newInstance(stack);
            Object ka = mInsert.invoke(storageOf(net), key, amount, false);
            return ((Number) mAmount.invoke(ka)).longValue();
        } catch (Throwable t) {
            return -1;
        }
    }
}
