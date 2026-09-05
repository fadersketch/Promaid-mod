package com.maidsmart.brew;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 酿造配方链反推（v1.1.0 实测二百七十七）——从原版 PotionBrewing 的运行时配方表
 * （f_43494_：List<Mix<Potion>>，from+reagent→to）构建「目标药水 → 整条酿造链」，
 * 供定向酿造模式精确下料。
 *
 * 实现依据（javap 实证 1.20.1 SRG）：
 * - PotionBrewing.f_43494_ = 药水配方表（private static final，反射读取）
 * - PotionBrewing$Mix.f_43532_ = from、f_43533_ = Ingredient（材料）、
 *   f_43534_ = to——v1.1.0 实测二百七十九：Forge binpatch 把 from/to 的类型改成
 *   Holder.Reference（原版是 Potion/Item 本体），反射取值后必须经 unwrapHolder
 *   解包（m_203334_ = Holder.value()）
 * - PotionBrewing.m_43529_(材料, 瓶) = 公开混合方法（酿造台 m_155290_ 实际调用它）
 * - 酿造台判定（m_155294_）完全走原版 PotionBrewing，无 Forge 介入——反推链与
 *   运行时行为一致
 *
 * 链结构：从目标 Potion 沿 to→from 递归回退至 water/awkward 基底，得到
 * [基底, 材料1, 材料2, ...] 的步骤序列。形态（喷溅/滞留）是 Item 层转换
 * （f_43495_ 表：火药/龙息），由调用方按配置单独处理。
 */
public final class BrewRecipeResolver {
    /** 链上一步：from 药水 + 材料 + 结果药水 */
    public record Step(Potion from, Item reagent, Potion to) {
    }

    /** 完整链：steps 从基底到目标（不含基底本身），如 healing = [water→疣→awkward, awkward→金西瓜→healing] */
    public record Chain(Potion base, List<Step> steps) {
        public boolean isEmpty() {
            return steps.isEmpty();
        }
    }

    /** to → (from, reagent) 反查表 */
    private static final Map<Potion, Step> REVERSE = new HashMap<>();
    /** 已初始化标记（PotionBrewing 静态表在类加载时填充，首次使用时惰性构建） */
    private static volatile boolean built = false;
    /** v1.1.0 实测二百七十九：反射构建失败原因（诊断用，成功后清空） */
    public static volatile String lastError = "";

    private BrewRecipeResolver() {
    }

    /** 惰性构建反查表（线程安全；失败静默——定向模式退化为不可用，不影响游戏） */
    private static void ensureBuilt() {
        if (built) {
            return;
        }
        synchronized (BrewRecipeResolver.class) {
            if (built) {
                return;
            }
            try {
                Field tableField = PotionBrewing.class.getDeclaredField("f_43494_");
                tableField.setAccessible(true);
                List<?> table = (List<?>) tableField.get(null);
                Field fromField = Class.forName("net.minecraft.world.item.alchemy.PotionBrewing$Mix")
                        .getDeclaredField("f_43532_");
                Field reagentField = Class.forName("net.minecraft.world.item.alchemy.PotionBrewing$Mix")
                        .getDeclaredField("f_43533_");
                Field toField = Class.forName("net.minecraft.world.item.alchemy.PotionBrewing$Mix")
                        .getDeclaredField("f_43534_");
                fromField.setAccessible(true);
                reagentField.setAccessible(true);
                toField.setAccessible(true);
                for (Object mix : table) {
                    // v1.1.0 实测二百七十九：Forge 1.20.1 的 PotionBrewing binpatch
                    // 把 Mix 的 from/to 字段类型改成 Holder.Reference（原版是 Potion/
                    // Item 本体）——javap 未 patch 的 jar 看不出来，实机反射拿到的是
                    // Holder，instanceof Potion 全部失败 → REVERSE 空 → 所有药水
                    // "无法酿造"（实机症状完全吻合）。取值后必须解包
                    // （m_203334_ = Holder.value()，javap SRG 实证）。
                    Object from = unwrapHolder(fromField.get(mix));
                    Object to = unwrapHolder(toField.get(mix));
                    if (!(from instanceof Potion) || !(to instanceof Potion)) {
                        continue;
                    }
                    Ingredient ing = (Ingredient) reagentField.get(mix);
                    Item reagent = resolveReagent(ing);
                    if (reagent == null) {
                        continue;
                    }
                    // 同一 to 可能有多条路径（如 healing 也可由 mundane 变来）——
                    // 只保留第一条（static{} 里先注册的即原版主路径）
                    REVERSE.putIfAbsent((Potion) to, new Step((Potion) from, reagent, (Potion) to));
                }
                built = true;
                lastError = REVERSE.isEmpty() ? "table empty" : "";
            } catch (Throwable t) {
                // 反射失败（版本不匹配等）：定向模式不可用，批量模式不受影响
                built = true;
                lastError = t.toString();
            }
            // v1.1.0 实测二百八十三：Forge BrewingRecipeRegistry 通用适配——
            // mod 通过 Forge API（BrewingRecipeRegistry.addRecipe）注册的酿造配方
            // 存在 Forge 自己的表里（不进 vanilla PotionBrewing.f_43494_），运行时
            // 直接遍历（getRecipes/getInput/getIngredient/getOutput 全公开 API，
            // Forge 类不混淆），任何 mod 的新药水配方自动进入反推表，无需逐个适配
            try {
                java.util.List<net.minecraftforge.common.brewing.IBrewingRecipe> recipes =
                        net.minecraftforge.common.brewing.BrewingRecipeRegistry.getRecipes();
                for (net.minecraftforge.common.brewing.IBrewingRecipe r : recipes) {
                    if (!(r instanceof net.minecraftforge.common.brewing.BrewingRecipe br)) {
                        continue; // 自定义 IBrewingRecipe 实现无法静态反推，跳过
                    }
                    ItemStack out = br.getOutput();
                    if (out.m_41619_() || !(out.m_41720_() instanceof net.minecraft.world.item.PotionItem)) {
                        continue;
                    }
                    Potion to = PotionUtils.m_43579_(out);
                    ItemStack inSample = sampleOf(br.getInput());
                    if (to == null || inSample.m_41619_()
                            || !(inSample.m_41720_() instanceof net.minecraft.world.item.PotionItem)) {
                        continue;
                    }
                    Potion from = PotionUtils.m_43579_(inSample);
                    Item reagent = resolveReagent(br.getIngredient());
                    if (from == null || reagent == null) {
                        continue;
                    }
                    REVERSE.putIfAbsent(to, new Step(from, reagent, to));
                }
                if (!REVERSE.isEmpty()) {
                    lastError = "";
                }
            } catch (Throwable t) {
                // Forge 表读取失败不影响 vanilla 表已建成的部分
            }
        }
    }

    /** 从 Ingredient 取首个候选 stack（m_43908_ = getItems 公开 API） */
    private static ItemStack sampleOf(Ingredient ing) {
        try {
            ItemStack[] stacks = ing.m_43908_();
            if (stacks != null && stacks.length > 0) {
                return stacks[0];
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.f_41583_;
    }

    /** v1.1.0 实测二百七十九：Forge patch 后 Mix.from/to 是 Holder.Reference，
     *  解包出注册对象本体（m_203334_ = Holder.value()）；本体直传 */
    private static Object unwrapHolder(Object o) {
        if (o instanceof net.minecraft.core.Holder<?> h) {
            try {
                return h.m_203334_();
            } catch (Throwable t) {
                return null;
            }
        }
        return o;
    }

    /** 从 Ingredient 解析出代表物品（取第一个候选；m_43908_ = getItems 公开 API） */
    private static Item resolveReagent(Ingredient ing) {
        try {
            ItemStack[] stacks = ing.m_43908_();
            if (stacks != null && stacks.length > 0 && !stacks[0].m_41619_()) {
                return stacks[0].m_41720_();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 反推目标药水的完整酿造链；不可达（无链/非药水）返回空链 */
    public static Chain resolveChain(Potion target) {
        ensureBuilt();
        if (target == null) {
            return new Chain(null, Collections.emptyList());
        }
        List<Step> steps = new ArrayList<>();
        Potion cur = target;
        int guard = 0;
        while (guard++ < 16) {
            Step step = REVERSE.get(cur);
            if (step == null) {
                break;
            }
            steps.add(step);
            cur = step.from();
            if (isBase(cur)) {
                return new Chain(cur, reverse(steps));
            }
        }
        return new Chain(null, Collections.emptyList());
    }

    /**
     * 基底判定：v1.1.0 实测二百八十改为只认 water。
     * 旧版（water 或 awkward）的隐患：awkward 基底的链不含 water→awkward 步，
     * processTargeted 空槽补水瓶后 progressOf(水瓶)=-1（不在链上）→ 水瓶被收走
     * → 再补再收的死循环（原版绝大多数药水从 awkward 起步，仅 weakness 等少数
     * water 直达）→ 定向模式实际无法工作。链始终回退到 water 后：女仆从水瓶
     * 完整酿造（水瓶→疣→awkward→…），GUI 配方链/材料清单同样完整。
     */
    private static boolean isBase(Potion p) {
        if (p == null) {
            return false;
        }
        ResourceLocation key = ForgeRegistries.POTIONS.getKey(p);
        return key != null && "minecraft:water".equals(key.toString());
    }

    private static List<Step> reverse(List<Step> steps) {
        List<Step> out = new ArrayList<>(steps.size());
        for (int i = steps.size() - 1; i >= 0; i--) {
            out.add(steps.get(i));
        }
        return out;
    }

    /** 目标药水是否可酿造（有链可达）——GUI 药水列表过滤用 */
    public static boolean isBrewable(Potion p) {
        return !resolveChain(p).isEmpty();
    }

    /** 配方表是否可用（反射构建成功且非空）——GUI 降级用：表不可用时列出全部
     *  药水（悬停显示"无法酿造"），保证界面永远可用 */
    public static boolean isTableUsable() {
        ensureBuilt();
        return !REVERSE.isEmpty();
    }

    /** v1.1.0 实测三百二十四：反查表大小（诊断日志用——表空 = 定向模式整体退化
     *  为"不下料"，而 ensureBuilt 失败完全静默，玩家只看到女仆空做动作） */
    public static int tableSize() {
        ensureBuilt();
        return REVERSE.size();
    }

    /** 链上第 index 步需要的材料（index 从 0 起，对应基底后的第一步） */
    public static Item reagentAt(Chain chain, int index) {
        if (chain == null || index < 0 || index >= chain.steps().size()) {
            return null;
        }
        return chain.steps().get(index).reagent();
    }

    /** 链上第 index 步完成后的药水（index = steps.size() 时即目标） */
    public static Potion potionAfter(Chain chain, int index) {
        if (chain == null || chain.isEmpty()) {
            return null;
        }
        if (index < 0) {
            return chain.base();
        }
        if (index >= chain.steps().size()) {
            return chain.steps().get(chain.steps().size() - 1).to();
        }
        return chain.steps().get(index).to();
    }

    /** 便捷：目标药水（Potion 实例）→ 链 */
    public static Chain chainFor(String potionId) {
        if (potionId == null || potionId.isEmpty()) {
            return new Chain(null, Collections.emptyList());
        }
        try {
            Potion p = ForgeRegistries.POTIONS.getValue(ResourceLocation.parse(potionId));
            return p == null ? new Chain(null, Collections.emptyList()) : resolveChain(p);
        } catch (Throwable t) {
            return new Chain(null, Collections.emptyList());
        }
    }

    /** 便捷：当前瓶（ItemStack）在链中的进度（0 = 基底，steps.size() = 目标） */
    public static int progressOf(ItemStack bottle, Chain chain) {
        if (bottle == null || bottle.m_41619_() || chain == null || chain.isEmpty()) {
            return 0;
        }
        Potion p = PotionUtils.m_43579_(bottle);
        if (p == null) {
            return 0;
        }
        if (p == chain.base()) {
            return 0;
        }
        for (int i = 0; i < chain.steps().size(); i++) {
            if (p == chain.steps().get(i).to()) {
                return i + 1;
            }
        }
        return -1; // 不在链上（无关药水）
    }

    /** 形态转换材料（Item 层）：饮用→喷溅=火药，喷溅→滞留=龙息 */
    public static Item formReagent(int form) {
        return switch (form) {
            case BrewConfig.FORM_SPLASH -> item("minecraft:gunpowder");
            case BrewConfig.FORM_LINGERING -> item("minecraft:dragon_breath");
            default -> null;
        };
    }

    /** 按注册名取物品（避免 SRG 字段名依赖） */
    public static Item item(String id) {
        try {
            return ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(id));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 形态转换是否可一步完成（饮用→滞留需要先喷溅，两步） */
    public static boolean formReachable(int fromForm, int toForm) {
        return toForm >= fromForm;
    }
}
