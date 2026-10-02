package com.maidsmart.bd;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.tool.PromaidLog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 实测 G-6【超越维度·存入】——采矿 / 伐木 / 收割的产物回收到**主人的主网络**。
 *
 * <h2>为什么先做存入、不做取出（需求方的直觉是对的）</h2>
 * <b>存入</b>不需要"知道要什么"：物品已经在她背包里，我们手里就有精确的 {@code ItemStack}，
 * 直接构 key 插进去即可。<b>取出</b>要先回答"取什么"——按物品 key 查是 O(1)，但"任意物品"
 * 要么列举网络内容（O(n)）、要么用 {@code fuzzy=true}（源码自己写了那是**线性扫描**），
 * 所以取出要配一套"向网络要什么"的表达方式，那是下一步的事。
 *
 * <h2>安全口径（宁可少搬，不可搬错）</h2>
 * <ol>
 *   <li><b>只扫背包</b>（{@code getAvailableBackpackInv}），**双手一律不碰**——手里的剑不会被搬走；</li>
 *   <li><b>只搬白名单产物</b>：{@code c:logs} / {@code c:ores} / {@code c:raw_materials} /
 *       {@code c:gems} / {@code c:crops}；</li>
 *   <li><b>硬排除</b>：有耐久的（工具/护甲）、附魔的、改过名的、TLM 自己的物品（{@code touhou_little_maid}）、
 *       以及种子/树苗/食物/桶/火把——这些她干活要用；</li>
 *   <li><b>不丢东西</b>：先 <i>模拟取出</i> 确认数量 → <i>真插入</i>网络（拿回剩余量）→ 才从背包
 *       <i>真扣除</i>。顺序刻意如此：任何一步失败都不会出现"背包扣了、网络没进"的丢件；</li>
 *   <li>每笔都写日志（物品 id × 数量），可在 {@code promaid.log} 里对账。</li>
 * </ol>
 *
 * <h2>开关</h2>
 * 默认关；per-maid 开关存 {@code persistentData}（随魂符/存档走）。
 * {@code /maid_smart bd_deposit_dry} 可以"只看不搬"，先确认名单再开。
 */
public final class MaidBdDeposit {

    /** persistentData 键。 */
    private static final String TAG = "maid_smart_bd_deposit";

    /** 扫描间隔（tick）：1 秒一次。 */
    private static final int SWEEP_EVERY = 20;
    /** 每次最多搬几组 / 几个。 */
    private static final int MAX_STACKS_PER_SWEEP = 8;
    private static final long MAX_ITEMS_PER_SWEEP = 4096L;

    /**
     * 白名单：只搬这些（标签）。
     *
     * <p>【实测 G-6 修正】只写公共标签 {@code c:logs} 会**漏掉原版原木**——公共标签由各模组自己
     * 生成，原版物品不一定被收进去（无头实测：oak_log 没被匹配到）。所以这里**两套都列**：
     * 公共命名空间 {@code c:*} 与**原版自己的** {@code minecraft:*}_ores / {@code minecraft:logs}。
     * 不在任何标签里的（例如某些模组的自定义作物）走下面的 {@link #GOOD_IDS} 明细名单。
     */
    private static TagKey<Item> tag(String ns, String path) {
        return net.minecraft.tags.ItemTags.create(ResourceLocation.fromNamespaceAndPath(ns, path));
    }

    private static final TagKey<Item>[] GOOD_TAGS = new TagKey[]{
            tag("c", "logs"), tag("minecraft", "logs"),
            tag("c", "ores"), tag("minecraft", "ores"),
            tag("minecraft", "coal_ores"), tag("minecraft", "iron_ores"), tag("minecraft", "copper_ores"),
            tag("minecraft", "gold_ores"), tag("minecraft", "redstone_ores"), tag("minecraft", "lapis_ores"),
            tag("minecraft", "diamond_ores"), tag("minecraft", "emerald_ores"), tag("minecraft", "quartz_ores"),
            tag("c", "raw_materials"), tag("c", "gems"), tag("c", "dusts"), tag("c", "crops"),
    };

    /** 白名单补充：不在标签里的常见收成（原版作物大多没有统一标签）。 */
    private static final String[] GOOD_IDS = {
            // 挖矿产物里**不在任何统一标签**里的那些（无头实测：煤就漏了）
            "minecraft:coal", "minecraft:flint", "minecraft:clay_ball", "minecraft:quartz",
            "minecraft:amethyst_shard", "minecraft:glowstone_dust", "minecraft:pointed_dripstone",
            "minecraft:obsidian", "minecraft:ancient_debris", "minecraft:netherite_scrap",
            "minecraft:wheat", "minecraft:carrot", "minecraft:potato", "minecraft:beetroot",
            "minecraft:sugar_cane", "minecraft:bamboo", "minecraft:melon_slice", "minecraft:pumpkin",
            "minecraft:nether_wart", "minecraft:sweet_berries", "minecraft:glow_berries",
            "minecraft:cocoa_beans", "minecraft:kelp", "minecraft:cactus", "minecraft:hay_block",
            "minecraft:string", "minecraft:leather", "minecraft:feather", "minecraft:bone",
            "minecraft:gunpowder", "minecraft:slime_ball", "minecraft:ender_pearl",
    };

    /** 黑名单：干活要用 / 不该动（标签）。 */
    private static final TagKey<Item>[] KEEP_TAGS = new TagKey[]{
            net.minecraft.tags.ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "seeds")),
            net.minecraft.tags.ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "saplings")),
            net.minecraft.tags.ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "foods")),
            net.minecraft.tags.ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "buckets")),
            net.minecraft.tags.ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "tools")),
            net.minecraft.tags.ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "armors")),
    };

    /** 黑名单：这些模组自己的东西一律不动（TLM 的魂符/背包等她自己的物件）。 */
    private static final String[] KEEP_MODIDS = {"touhou_little_maid", "promaid"};

    private static final Map<UUID, Integer> COUNTER = new HashMap<>();

    /** 【G-14】每只女仆"最近一次自动回收"的备忘：否则玩家敲 dry 时它早跑完了，只看到一片空白。 */
    private static final Map<UUID, String> LAST_SWEEP = new HashMap<>();
    private static boolean hooked;

    private MaidBdDeposit() {
    }

    public static void ensureHooked() {
        if (hooked) {
            return;
        }
        hooked = true;
        NeoForge.EVENT_BUS.register(new MaidBdDeposit());
    }

    public static boolean isOn(EntityMaid maid) {
        try {
            return maid != null && ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid)
                    .getPersistentData().getBoolean(TAG);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setOn(EntityMaid maid, boolean on) {
        try {
            ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid)
                    .getPersistentData().putBoolean(TAG, on);
        } catch (Throwable ignored) {
        }
        PromaidLog.log("超越维度存入", (maid == null ? "?" : maid.getName().getString())
                + " 产出回收 = " + on);
    }

    /** 内置名单的只读说明（{@code /maid_smart bd_rule builtin} 用）。 */
    public static List<String> describeBuiltin() {
        List<String> out = new ArrayList<>();
        out.add("内置白名单（会搬）——标签：");
        for (TagKey<Item> t : GOOD_TAGS) {
            out.add("    tag:" + t.location());
        }
        out.add("内置白名单（会搬）——明细：" + String.join("，", GOOD_IDS));
        out.add("内置保留（不搬）——标签：");
        for (TagKey<Item> t : KEEP_TAGS) {
            out.add("    tag:" + t.location());
        }
        out.add("内置保留：有耐久的（工具/护甲）、附魔的、改过名的、模组自有（" + String.join("/", KEEP_MODIDS)
                + "）、火把与灯笼");
        out.add("想改：用 bd_rule move / keep / keepN / keepAtLeast（你的规则永远优先于内置名单）");
        return out;
    }

    /** 这一堆该不该搬：白名单命中、且没踩任何一条硬排除。 */
    public static boolean isProduct(ItemStack s) {
        return !s.isEmpty() && verdict(s) == null;
    }

    /**
     * dry-run：把**每一格**的判定结果与理由都列出来（不只列命中的）——
     * 调名单时必须能看见"为什么没搬这一格"，否则只能猜。
     */
    public static List<String> preview(EntityMaid maid) {
        List<String> out = new ArrayList<>();
        String lastSweep = maid == null ? null : LAST_SWEEP.get(maid.getUUID());
        IItemHandler inv = backpack(maid);
        if (inv == null) {
            if (out.isEmpty() && lastSweep != null) {
            out.add("（她背包里此刻没有可回收的；最近一次自动回收：" + lastSweep + "）");
        }
        return out;
        }
        java.util.Map<net.minecraft.world.item.Item, Long> movedByItem = new java.util.HashMap<>();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s == null || s.isEmpty()) {
                continue;
            }
            String id = MaidBdCompat.itemId(s);
            String why = verdict(s);
            if (why != null) {
                out.add("槽" + i + " " + id + " × " + s.getCount() + " → 保留（" + why + "）");
                continue;
            }
            long cap = MaidBdRules.keepCap(s);
            long total = MaidBdRules.countIn(inv, s);
            long moved = movedByItem.getOrDefault(s.getItem(), 0L);
            long here = Math.max(0, Math.min(s.getCount(), (total - cap) - moved));
            movedByItem.put(s.getItem(), moved + here);
            if (here <= 0) {
                out.add("槽" + i + " " + id + " × " + s.getCount() + " → 保留（保留 N 个：上限 " + cap
                        + "，她一共 " + total + " 个）");
            } else if (here < s.getCount()) {
                out.add("槽" + i + " " + id + " × " + s.getCount() + " → 会搬 " + here + " 个、留 "
                        + (s.getCount() - here) + " 个（保留 N 个：上限 " + cap + "，她一共 " + total + " 个）");
            } else {
                String via = moveVia(s);
                out.add("槽" + i + " " + id + " × " + s.getCount() + " → 会搬（" + via + "）"
                        + (cap > 0 ? "；保留 N 个：上限 " + cap + "，她一共 " + total + " 个" : ""));
            }
        }
        if (out.isEmpty() && lastSweep != null) {
            out.add("（她背包里此刻没有可回收的；最近一次自动回收：" + lastSweep + "）");
        }
        return out;
    }

    /** 判定这一堆该不该搬；返回 null = 该搬，否则返回**保留的理由**。 */
    public static String verdict(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return "空";
        }
        try {
            // 【G-9 优先级】玩家写的规则优先于内置名单：一定不搬 > 一定搬
            if (MaidBdRules.customKeep(s)) {
                return "自定义【一定不搬】";
            }
            if (MaidBdRules.customMove(s)) {
                return null;
            }
            if (s.isDamageableItem()) {
                return "有耐久（工具/护甲）";
            }
            if (s.isEnchanted()) {
                return "附魔";
            }
            if (s.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) {
                return "改过名";
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
            if (id != null) {
                for (String m : KEEP_MODIDS) {
                    if (m.equals(id.getNamespace())) {
                        return "模组自有物品（" + m + "）";
                    }
                }
                if (id.getPath().equals("torch") || id.getPath().equals("lantern")) {
                    return "照明要用的";
                }
            }
            // 【G-9】明细 id 排在保留标签之前：胡萝卜既是收成又在 c:foods 里，
            // 先认收成、再由 keepN 决定留几个（否则它会被当成食物永远不搬）。
            if (id != null) {
                String full = id.toString();
                for (String good : GOOD_IDS) {
                    if (good.equals(full)) {
                        return null;
                    }
                }
            }
            for (TagKey<Item> t : KEEP_TAGS) {
                if (s.is(t)) {
                    return "保留标签 " + t.location();
                }
            }
            for (TagKey<Item> t : GOOD_TAGS) {
                if (s.is(t)) {
                    return null;
                }
            }
            return "未命中白名单";
        } catch (Throwable t) {
            return "判定异常";
        }
    }

    /**
     * 这一堆**为什么该搬**（判定不透明是需求方实测里最难受的一点：钻石不是他写的规则却照样进库、
     * 金锭他以为该搬却不动）。返回 null = 不该搬。
     */
    public static String moveVia(ItemStack s) {
        if (s == null || s.isEmpty() || verdict(s) != null) {
            return null;
        }
        try {
            if (MaidBdRules.customMove(s)) {
                return "你的自定义规则";
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
            if (id != null) {
                String full = id.toString();
                for (String good : GOOD_IDS) {
                    if (good.equals(full)) {
                        return "内置明细 " + full;
                    }
                }
            }
            for (TagKey<Item> t : GOOD_TAGS) {
                if (s.is(t)) {
                    return "内置标签 " + t.location();
                }
            }
        } catch (Throwable ignored) {
        }
        return "内置名单";
    }

    private static IItemHandler backpack(EntityMaid maid) {
        try {
            return maid == null ? null : maid.getAvailableBackpackInv();
        } catch (Throwable t) {
            return null;
        }
    }

    @SubscribeEvent
    public void onMaidTick(MaidTickEvent event) {
        try {
            tick(event.getMaid());
        } catch (Throwable ignored) {
        }
    }

    private static void tick(EntityMaid maid) {
        if (maid == null || maid.level().isClientSide() || !isOn(maid) || !MaidBdCompat.enabled()) {
            return;   // 两级门禁：总开关（默认关）+ 该女仆自己的开关
        }
        // 【静态表兜底】与上游其余链路同口径（StateTables.cap）
        com.maidsmart.tool.StateTables.cap("bdDeposit", COUNTER);
        com.maidsmart.tool.StateTables.cap("bdDepositLast", LAST_SWEEP);
        int n = COUNTER.merge(maid.getUUID(), 1, Integer::sum);
        if (n % SWEEP_EVERY != 0) {
            return;
        }
        Player owner = MaidBdCompat.ownerOf(maid);
        if (owner == null || !MaidBdCompat.available() || !MaidBdCompat.hasAnyNet(owner)) {
            return;
        }
        Object net = MaidBdCompat.primaryNet(owner);
        if (net == null) {
            return;
        }
        sweep(maid, net, false);
    }

    /**
     * 执行一次回收。
     *
     * @param dryRun true 只看不搬（返回"会搬什么"的清单，不改变任何东西）
     * @return 本次实际搬入 / 会被搬入的条目（"物品 id × 数量 → 结果"）
     */
    public static List<String> sweep(EntityMaid maid, Object net, boolean dryRun) {
        List<String> out = new ArrayList<>();
        IItemHandler inv = backpack(maid);
        if (inv == null || net == null) {
            return out;
        }
        int stacks = 0;
        long movedItems = 0;
        // 【G-10】保留 N 个的预算：按**具体物品种类**在本轮内累计，避免跨槽位搬多。
        // 之前这里漏了（dry-run 有、真正搬运没有），实机表现就是"预览说留 16、实际一个不留"。
        final java.util.Map<net.minecraft.world.item.Item, Long> sweepBudget = new java.util.HashMap<>();
        final java.util.List<String> keptNotes = new java.util.ArrayList<>();
        for (int i = 0; i < inv.getSlots(); i++) {
            if (stacks >= MAX_STACKS_PER_SWEEP || movedItems >= MAX_ITEMS_PER_SWEEP) {
                break;
            }
            ItemStack s = inv.getStackInSlot(i);
            if (!isProduct(s)) {
                continue;
            }
            String id = MaidBdCompat.itemId(s);
            // 【保留 N 个】只搬"超出上限"的那部分
            long cap = MaidBdRules.keepCap(s);
            long allow = Math.max(0, MaidBdRules.countIn(inv, s) - cap);
            long already = sweepBudget.getOrDefault(s.getItem(), 0L);
            long canMove = Math.max(0, Math.min(s.getCount(), allow - already));
            if (canMove <= 0) {
                if (cap > 0) {
                    keptNotes.add(id + " × " + s.getCount() + " 保留（上限 " + cap + "，她一共 "
                            + MaidBdRules.countIn(inv, s) + " 个）");
                }
                continue;
            }
            if (dryRun) {
                out.add(id + " × " + canMove + "（会不会搬：会" + (cap > 0 ? "，保留上限 " + cap : "") + "）");
                sweepBudget.put(s.getItem(), already + canMove);
                stacks++;
                movedItems += canMove;
                continue;
            }
            // ① 模拟取出：确认背包这一格真的能拿出这么多（改完还得放回去，所以用 simulate）
            ItemStack probe = inv.extractItem(i, (int) canMove, true);
            if (probe.isEmpty()) {
                continue;
            }
            long want = probe.getCount();
            // ② 真插入网络：返回的是**剩余量**，插进去的就是 want - remainder
            // 【G-13 审计】记下"网络里 X → Y"，让玩家事后能对账（需求方实测里就是缺这个，
            // 只能凭印象说"应该有 +1 吧"）。
            long beforeInNet = MaidBdCompat.countOf(net, s);
            long remainder = MaidBdCompat.insert(net, s, want);
            if (remainder < 0) {
                out.add(id + " × " + want + "（失败：插入调用异常）");
                continue;
            }
            long accepted = want - remainder;
            if (accepted <= 0) {
                out.add(id + " × " + want + "（网络拒收）");
                continue;
            }
            // ③ 网络已收下，才从背包真扣除
            ItemStack removed = inv.extractItem(i, (int) accepted, false);
            long really = removed.getCount();
            if (really < accepted) {
                // 理论上不会发生（上一步刚模拟成功、同 tick 无并发）；真发生就把多出来的还给网络
                long back = accepted - really;
                MaidBdCompat.extract(net, s, back);
                out.add(id + " × " + accepted + "（异常：只扣掉 " + really + "，已退回 " + back + "）");
                continue;
            }
            long afterInNet = MaidBdCompat.countOf(net, s);
            out.add(id + " × " + accepted + " → 已存入（" + moveVia(s) + "；网络 " + beforeInNet + " → " + afterInNet + "）"
                    + (cap > 0 ? "（保留上限 " + cap + "）" : ""));
            sweepBudget.put(s.getItem(), already + accepted);
            stacks++;
            movedItems += accepted;
        }
        if (!dryRun && (!out.isEmpty() || !keptNotes.isEmpty())) {
            // 每次决策都留痕（含"因保留上限没搬"），否则玩家只看到"东西没动"，
            // 分不清是判定不该搬还是到了上限——上一轮实测就卡在这里。
            String summary = (out.isEmpty() ? "无入库" : String.join("；", out))
                    + (keptNotes.isEmpty() ? "" : " ‖ 因保留上限未搬：" + String.join("；", keptNotes));
            PromaidLog.log("超越维度存入", maid.getName().getString() + " 本轮：" + summary);
            LAST_SWEEP.put(maid.getUUID(), summary);
            if (LAST_SWEEP.size() > 64) {
                LAST_SWEEP.clear();   // 备忘而已，大了就整体丢掉，不必精细维护
            }
        }
        return out;
    }
}
