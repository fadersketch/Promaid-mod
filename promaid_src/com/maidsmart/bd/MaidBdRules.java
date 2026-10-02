package com.maidsmart.bd;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.maidsmart.tool.PromaidLog;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 实测 G-9【超越维度·玩家自定义名单 + 保留规则】。
 *
 * <h2>为什么需要它（需求方的三条原话）</h2>
 * <ul>
 *   <li>「进女仆物品栏的垃圾物品可能各种各样，从敌人身上掉落的废装备到打到草丛之后掉落的各种种子，
 *       挖矿时挖到的不需要的石材」——所以内置白名单一定有漏；</li>
 *   <li>「如果可以的话再做一个玩家自定义白名单（最好支持 tags/component 筛选）」；</li>
 *   <li>「我们能不能配置物品栏里面保留几个/几组」；</li>
 *   <li>胡萝卜这条更说明问题：「玩家中后期大概率给女仆只喂**镀金胡萝卜**」——所以普通胡萝卜该当产物收走；
 *       但**胡萝卜与土豆本身就是播种用的"种子"**，全收走农业女仆就没法补种 ⇒ 正解是"留 N 个、多的收走"。</li>
 * </ul>
 *
 * <h2>规则文件（我们自己的，不动上游配置）</h2>
 * <pre>
 *   config/promaid_bd_rules.json
 *   {
 *     "move":      ["item:minecraft:coal", "tag:c:ores"],      // 一定搬
 *     "keep":      ["minecraft:golden_carrot"],                // 一定不搬
 *     "keepN":     [ {"match": "minecraft:carrot", "keep": 16},// 她背包里最多留 16 个，多的搬走
 *                    {"match": "tag:c:logs", "keep": 64} ]
 *   }
 * </pre>
 * 匹配写法：<b>裸物品 id</b> 等于 {@code item:<id>}；{@code tag:命名空间:路径} 按标签匹配。
 * 所有条目都是**显式优先于内置名单**（玩家写的说了算），改完即存盘、重启自动读回。
 *
 * <h2>判定优先级（verdict）</h2>
 * <pre>
 *   自定义 keep  →  自定义 move  →  内置 move（原版/公共标签 + 明细名单）  →  内置 keep（种子/树苗/食物/桶/工具/照明…）  →  默认不搬
 * </pre>
 * {@code keepN} 不是判定，而是**上限**：只对已被判为"搬"的物品生效——一次回收里，某物品在她背包里
 * 最多留 N 个，超出部分才搬走（所以胡萝卜：留 16 个用于补种，其余进库）。
 *
 * <p>【1.20.1 差异】规则文件路径走 {@code FMLPaths.CONFIGDIR}（本树的房屋范式），读写用
 * {@code Files.readString / writeString}；Gson 用法与 neo 逐字相同。
 */
public final class MaidBdRules {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 规则文件（config/promaid_bd_rules.json）。用方法解析，避免类初始化早于 FML 路径就绪。 */
    private static Path file() {
        return net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().resolve("promaid_bd_rules.json");
    }

    /** 一条保留规则（背包里最多留 N 个，多的搬走）。 */
    public record KeepRule(String match, long keep) {
    }

    /** 一条补货规则（背包里至少留 N 个，少了从库里取）。 */
    public record AtLeastRule(String match, long count) {
    }

    private static List<String> moveList = new ArrayList<>();
    private static List<String> keepList = new ArrayList<>();
    private static List<KeepRule> keepNList = new ArrayList<>();
    private static List<AtLeastRule> atLeastList = new ArrayList<>();
    private static boolean loaded;

    private MaidBdRules() {
    }

    // ---------------- 读写 ----------------

    public static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Path f = file();
            if (Files.exists(f)) {
                JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                moveList = readStrings(o, "move");
                keepList = readStrings(o, "keep");
                keepNList = readKeepN(o, "keepN");
                atLeastList = readAtLeast(o);
            } else {
                // 首次生成默认规则：胡萝卜/土豆/甜菜根各留 16（够补种），其余照内置名单走
                keepNList = new ArrayList<>(List.of(
                        new KeepRule("minecraft:carrot", 16),
                        new KeepRule("minecraft:potato", 16),
                        new KeepRule("minecraft:beetroot", 16)));
                save();
            }
        } catch (Throwable t) {
            PromaidLog.log("超越维度规则", "读取 config/promaid_bd_rules.json 失败：" + t);
        }
    }

    private static List<String> readStrings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        try {
            JsonElement e = o.get(key);
            if (e != null && e.isJsonArray()) {
                for (JsonElement x : e.getAsJsonArray()) {
                    out.add(x.getAsString());
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static List<KeepRule> readKeepN(JsonObject o, String key) {
        List<KeepRule> out = new ArrayList<>();
        try {
            JsonElement e = o.get(key);
            if (e != null && e.isJsonArray()) {
                for (JsonElement x : e.getAsJsonArray()) {
                    if (!x.isJsonObject()) {
                        continue;
                    }
                    JsonObject r = x.getAsJsonObject();
                    out.add(new KeepRule(r.get("match").getAsString(), r.get("keep").getAsLong()));
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 读 keepAtLeast（默认空：由玩家自己写。需求方口径："默认配置为空"）。 */
    private static List<AtLeastRule> readAtLeast(JsonObject o) {
        List<AtLeastRule> out = new ArrayList<>();
        try {
            JsonElement e = o.get("keepAtLeast");
            if (e != null && e.isJsonArray()) {
                for (JsonElement x : e.getAsJsonArray()) {
                    if (!x.isJsonObject()) {
                        continue;
                    }
                    JsonObject r = x.getAsJsonObject();
                    out.add(new AtLeastRule(r.get("match").getAsString(), r.get("count").getAsLong()));
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static synchronized void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            JsonObject o = new JsonObject();
            JsonArray m = new JsonArray();
            moveList.forEach(m::add);
            JsonArray k = new JsonArray();
            keepList.forEach(k::add);
            JsonArray kn = new JsonArray();
            for (KeepRule r : keepNList) {
                JsonObject e = new JsonObject();
                e.addProperty("match", r.match());
                e.addProperty("keep", r.keep());
                kn.add(e);
            }
            JsonArray al = new JsonArray();
            for (AtLeastRule r : atLeastList) {
                JsonObject e = new JsonObject();
                e.addProperty("match", r.match());
                e.addProperty("count", r.count());
                al.add(e);
            }
            o.add("move", m);
            o.add("keep", k);
            o.add("keepN", kn);
            o.add("keepAtLeast", al);
            Files.writeString(f, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            PromaidLog.log("超越维度规则", "写入 config/promaid_bd_rules.json 失败：" + t);
        }
    }

    // ---------------- 增删查 ----------------

    public static synchronized String addMove(String entry) {
        ensureLoaded();
        keepList.removeIf(entry::equals);
        if (!moveList.contains(entry)) {
            moveList.add(entry);
        }
        save();
        return "已加入【一定搬】：" + entry;
    }

    public static synchronized String addKeep(String entry) {
        ensureLoaded();
        moveList.removeIf(entry::equals);
        if (!keepList.contains(entry)) {
            keepList.add(entry);
        }
        save();
        return "已加入【一定不搬】：" + entry;
    }

    public static synchronized String setKeepN(String entry, long n) {
        ensureLoaded();
        keepNList.removeIf(r -> r.match().equals(entry));
        keepNList.add(new KeepRule(entry, Math.max(0, n)));
        save();
        return "已设置【保留 N 个】：" + entry + " → " + Math.max(0, n) + "（超出部分会被搬走）";
    }

    public static synchronized String addKeepAtLeast(String entry, long n) {
        ensureLoaded();
        atLeastList.removeIf(r -> r.match().equals(entry));
        atLeastList.add(new AtLeastRule(entry, Math.max(0, n)));
        save();
        return "已设置【至少留 N 个】：" + entry + " → " + Math.max(0, n) + "（少了会从主网络取）";
    }

    public static synchronized List<AtLeastRule> atLeastRules() {
        ensureLoaded();
        return new ArrayList<>(atLeastList);
    }

    /* ==================== 实测七百七十二：面板子页用的只读导出 ==================== */

    /** 面板子页：一类规则的原始写法列表。{@code which} 1=一定搬 2=一定不搬。 */
    public static synchronized List<String> describeMatches(byte which) {
        ensureLoaded();
        return new ArrayList<>(which == 2 ? keepList : moveList);
    }

    /** 面板子页：保留 N 个，导出成 {@code match=keep} 便于一个字符串传。 */
    public static synchronized List<String> describeKeepN() {
        ensureLoaded();
        List<String> out = new ArrayList<>();
        for (KeepRule r : keepNList) {
            out.add(r.match() + "=" + r.keep());
        }
        return out;
    }

    /** 面板子页：至少留 N 个，导出成 {@code match=count}。 */
    public static synchronized List<String> describeAtLeast() {
        ensureLoaded();
        List<String> out = new ArrayList<>();
        for (AtLeastRule r : atLeastList) {
            out.add(r.match() + "=" + r.count());
        }
        return out;
    }

    public static synchronized String remove(String entry) {
        ensureLoaded();
        boolean a = moveList.removeIf(entry::equals);
        boolean b = keepList.removeIf(entry::equals);
        boolean c = keepNList.removeIf(r -> r.match().equals(entry));
        boolean d = atLeastList.removeIf(r -> r.match().equals(entry));
        save();
        return (a || b || c || d) ? "已删除规则：" + entry : "没有这条规则：" + entry;
    }

    public static synchronized List<String> describe() {
        ensureLoaded();
        List<String> out = new ArrayList<>();
        out.add("规则文件：config/promaid_bd_rules.json（改完即存，重启读回）");
        out.add("一定搬：" + (moveList.isEmpty() ? "（空）" : String.join("，", moveList)));
        out.add("一定不搬：" + (keepList.isEmpty() ? "（空）" : String.join("，", keepList)));
        if (keepNList.isEmpty()) {
            out.add("保留 N 个：（空）");
        } else {
            for (KeepRule r : keepNList) {
                out.add("保留 N 个：" + r.match() + " → 最多留 " + r.keep() + " 个");
            }
        }
        if (atLeastList.isEmpty()) {
            out.add("至少留 N 个（自动补货）：（空，由你自己写）");
        } else {
            for (AtLeastRule r : atLeastList) {
                out.add("至少留 N 个（自动补货）：" + r.match() + " → 少于 " + r.count() + " 个就从主网络取");
            }
        }
        return out;
    }

    // ---------------- 匹配 ----------------

    /** 自定义"一定搬"。 */
    public static boolean customMove(ItemStack s) {
        ensureLoaded();
        return matchesAny(s, moveList);
    }

    /** 自定义"一定不搬"。 */
    public static boolean customKeep(ItemStack s) {
        ensureLoaded();
        return matchesAny(s, keepList);
    }

    /** 该物品在她背包里最多留几个（0 = 不保留，全部可搬）。 */
    public static long keepCap(ItemStack s) {
        ensureLoaded();
        for (KeepRule r : keepNList) {
            if (matches(s, r.match())) {
                return r.keep();
            }
        }
        return 0;
    }

    private static boolean matchesAny(ItemStack s, List<String> entries) {
        for (String e : entries) {
            if (matches(s, e)) {
                return true;
            }
        }
        return false;
    }

    /** 匹配一条规则写法：裸 id / {@code item:} / {@code tag:namespace:path}。 */
    public static boolean matches(ItemStack s, String entry) {
        if (s == null || s.m_41619_() || entry == null || entry.isEmpty()) {
            return false;
        }
        try {
            String e = entry.trim();
            if (e.startsWith("tag:")) {
                return s.m_204117_(tagOf(e.substring(4)));
            }
            if (e.startsWith("item:")) {
                e = e.substring(5);
            }
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(s.m_41720_());
            return id != null && id.toString().equals(e);
        } catch (Throwable t) {
            return false;
        }
    }

    public static TagKey<Item> tagOf(String raw) {
        String[] p = raw.split(":", 2);
        return p.length == 2
                ? net.minecraft.tags.ItemTags.create(new ResourceLocation(p[0], p[1]))
                : net.minecraft.tags.ItemTags.create(new ResourceLocation("c", raw));
    }

    /** 供 bd_deposit_dry 用：某物品在这一轮里"最多能搬几个"的预算计算器。 */
    public static Predicate<ItemStack> isSameItem(ItemStack ref) {
        return s -> s != null && !s.m_41619_() && s.m_41720_() == ref.m_41720_();
    }

    /** 统计她背包里某个物品的总数（用于 keepN 预算）。 */
    public static long countIn(net.minecraftforge.items.IItemHandler inv, ItemStack ref) {
        long n = 0;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s != null && !s.m_41619_() && s.m_41720_() == ref.m_41720_()) {
                n += s.m_41613_();
            }
        }
        return n;
    }

    /** 她背包里符合这条写法的物品总数（裸 id / item: / tag: 都支持）。 */
    public static long countMatching(net.minecraftforge.items.IItemHandler inv, String entry) {
        if (inv == null || entry == null || entry.isEmpty()) {
            return 0;
        }
        long n = 0;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s != null && !s.m_41619_() && matches(s, entry)) {
                n += s.m_41613_();
            }
        }
        return n;
    }

    /** 诊断：把当前生效的规则数量报出来。 */
    public static Map<String, Integer> counts() {
        ensureLoaded();
        Map<String, Integer> m = new HashMap<>();
        m.put("move", moveList.size());
        m.put("keep", keepList.size());
        m.put("keepN", keepNList.size());
        return m;
    }
}
