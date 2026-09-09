package com.maidsmart.brew;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.Ingredient;

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
 * - PotionBrewing$Mix.from = from、ingredient = Ingredient（材料）、
 *   to = to——v1.1.0 实测二百七十九：Forge binpatch 把 from/to 的类型改成
 *   Holder.Reference（原版是 Potion/Item 本体），反射取值后必须经 unwrapHolder
 *   解包（value = Holder.value()）
 * - PotionBrewing.mix(材料, 瓶) = 公开混合方法（酿造台 doBrew 实际调用它）
 * - 酿造台判定（isBrewable）完全走原版 PotionBrewing，无 Forge 介入——反推链与
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
                // 1.21.1：PotionBrewing 由静态表改为实例（server.potionBrewing()），
                // 字段名 potionMixes（mojmap 运行时名），Mix 为 record（from/ingredient/to）。
                // NeoForge 的 RegisterBrewingRecipesEvent 写入的配方同样落在这张表里，
                // 因此无需再遍历 Forge 专属 BrewingRecipeRegistry。
                net.minecraft.server.MinecraftServer server =
                        net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
                if (server == null) {
                    built = true;
                    lastError = "no server";
                    return;
                }
                Field tableField = PotionBrewing.class.getDeclaredField("potionMixes");
                tableField.setAccessible(true);
                List<?> table = (List<?>) tableField.get(server.potionBrewing());
                Class<?> mixClass = Class.forName("net.minecraft.world.item.alchemy.PotionBrewing$Mix");
                Field fromField = mixClass.getDeclaredField("from");
                Field reagentField = mixClass.getDeclaredField("ingredient");
                Field toField = mixClass.getDeclaredField("to");
                fromField.setAccessible(true);
                reagentField.setAccessible(true);
                toField.setAccessible(true);
                for (Object mix : table) {
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
                    REVERSE.putIfAbsent((Potion) to, new Step((Potion) from, reagent, (Potion) to));
                }
                built = true;
                lastError = REVERSE.isEmpty() ? "table empty" : "";
            } catch (Throwable t) {
                built = true;
                lastError = t.toString();
            }
        }
    }

    /** 从 Ingredient 取首个候选 stack（getItems = getItems 公开 API） */
    private static ItemStack sampleOf(Ingredient ing) {
        try {
            ItemStack[] stacks = ing.getItems();
            if (stacks != null && stacks.length > 0) {
                return stacks[0];
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.EMPTY;
    }

    /** v1.1.0 实测二百七十九：Forge patch 后 Mix.from/to 是 Holder.Reference，
     *  解包出注册对象本体（value = Holder.value()）；本体直传 */
    private static Object unwrapHolder(Object o) {
        if (o instanceof net.minecraft.core.Holder<?> h) {
            try {
                return h.value();
            } catch (Throwable t) {
                return null;
            }
        }
        return o;
    }

    /** 从 Ingredient 解析出代表物品（取第一个候选；getItems = getItems 公开 API） */
    private static Item resolveReagent(Ingredient ing) {
        try {
            ItemStack[] stacks = ing.getItems();
            if (stacks != null && stacks.length > 0 && !stacks[0].isEmpty()) {
                return stacks[0].getItem();
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
        ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.POTION.getKey(p);
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
            Potion p = net.minecraft.core.registries.BuiltInRegistries.POTION.get(ResourceLocation.parse(potionId));
            return p == null ? new Chain(null, Collections.emptyList()) : resolveChain(p);
        } catch (Throwable t) {
            return new Chain(null, Collections.emptyList());
        }
    }

    /** 便捷：当前瓶（ItemStack）在链中的进度（0 = 基底，steps.size() = 目标） */
    public static int progressOf(ItemStack bottle, Chain chain) {
        if (bottle == null || bottle.isEmpty() || chain == null || chain.isEmpty()) {
            return 0;
        }
        Potion p = com.maidsmart.brew.PotionCompat.of(bottle);
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
            return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 形态转换是否可一步完成（饮用→滞留需要先喷溅，两步） */
    public static boolean formReachable(int fromForm, int toForm) {
        return toForm >= fromForm;
    }
}
