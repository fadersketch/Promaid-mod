package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;

import java.util.List;

/**
 * 实测八百一十七【支援物品两张表：通用 / 亡灵 + 四类分类】——把"女仆给谁投什么"从代码里
 * 写死的池子，变成玩家可勾选、可分类的名单，交互与「投喂食物勾选」同款。
 *
 * <h2>
 * 「现在有了控制女仆喂食喂水的配置表。但是没有控制支援的配置表。我想整 2 个：通用支援表，
 *  亡灵支援表，表的范围为所有会给予效果的可食用物品。表的格式就仿照喂食物的表，可以让
 *  玩家自己勾选。」
 * <p>随后补充：「对于支援类物品本身也需要有多类分类。哪些是对策性治疗（产生了对应 debuff
 *  才能给）、哪些是增益类药水（战局一开始就能用，给自己和主人或者其他人）、哪些是生命回复类
 *  （血量低于哪个数值才能使用）。」
 *
 * <h2>四类分类（{@link Cat}）</h2>
 * <ul>
 *   <li><b>对策 {@code COUNTER}</b>：只在"对应的处境/负面效果出现"时才用——抗火（着火时）、
 *       水肺（溺水时）、牛奶/蜂蜜（身上有负面效果时）；</li>
 *   <li><b>增益 {@code BUFF}</b>：战局一开始（附近有威胁）就能用，可以给自己、主人或其他
 *       友军——不等低血、不等 debuff；</li>
 *   <li><b>回复 {@code HEAL}</b>：只在<b>目标血量低于「治疗触发血量」</b>时才用
 *       （见 {@code MaidSmartConfig.AID_HEALTH_THRESHOLD}，默认 0.3 = 30%）；</li>
 *   <li><b>禁用 {@code OFF}</b>：完全不用（勾掉）。</li>
 * </ul>
 * 另有 {@code DEFAULT}：<b>没有在表里分类过</b>的物品——按它的效果自动推断（见 {@link #derive}），
 * 推断不出来的一律"哪条链都能用"（= 本功能引入前的旧行为）。所以两张表<b>留空时逐字节等于旧版</b>。
 *
 * <h2>默认推断（derive）</h2>
 * 治疗/再生 → 回复（亡灵档再生→禁用）；抗火/水肺/牛奶/蜂蜜 → 对策；力量/迅捷/跳跃提升/夜视/抗性提升/生命提升/
 * 吸收/饱和/幸运/海豚的恩惠/潮涌能量/缓降 → 增益；<b>其余（毒/凋零/饥饿/即时伤害等有害效果）→ 禁用</b>。
 * <b>亡灵语义自动翻转</b>：{@code instant_health} 对亡灵是扣血 → 禁用，{@code instant_damage}
 * 对亡灵是回血 → 回复；{@code regeneration} 亡灵免疫（原版 {@code canBeAffected} 拒收）→ 禁用。
 *
 * <h2>记账键（entry key）</h2>
 * 药水 = {@code 物品id|药水id}（裸物品 id = 该形态所有药水）；其它 = {@code 物品id} 可选
 * {@code #剩余次数}。配置里存 {@code 键=分类}。
 */
public final class MaidSupportPolicy {

    /** 药水记账键的分隔符（{@code 物品id|药水id}） */
    public static final char POTION_SEP = '|';

    /** 支援物品分类 */
    public enum Cat {
        /** 没分类过 = 按效果自动推断；推断不出 = 任何链都能用（旧行为） */
        DEFAULT,
        /** 对策性：出现对应处境/debuff 才用 */
        COUNTER,
        /** 增益性：战局一开始就能用（自己 / 主人 / 其它友军） */
        BUFF,
        /** 生命回复性：目标血量低于「治疗触发血量」才用 */
        HEAL,
        /** 禁用 */
        OFF
    }

    /** 选物点所处的"链"（用于分类匹配） */
    public enum Ctx {
        /** 着火/溺水/负面效果这类处境链——只认「对策」 */
        COUNTER,
        /** 战局增益链——只认「增益」 */
        BUFF,
        /** 低血治疗链——只认「回复」 */
        HEAL,
        /**
         * 治疗/处境共用的方法（投药水那条口子同时被"抗火/水肺"与"低血治疗"调用）。
         * <p>只排除「增益」——真正投哪一瓶由调用处传入的药水名集合钉死。
         */
        TREAT
    }

    private MaidSupportPolicy() {
    }

    // ---------------------------------------------------------------- 判据

    /** 是不是药水类物品（饮用/喷溅/滞留） */
    public static boolean potionItem(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        Item it = s.getItem();
        return it instanceof net.minecraft.world.item.PotionItem
                || it instanceof net.minecraft.world.item.SplashPotionItem
                || it instanceof net.minecraft.world.item.LingeringPotionItem;
    }

    /** 药水注册名（如 {@code minecraft:healing}）；非药水/取不到返回 null */
    public static String potionId(ItemStack s) {
        try {
            if (!potionItem(s)) {
                return null;
            }
            Potion p = com.maidsmart.brew.PotionCompat.of(s);
            if (p == null) {
                return null;
            }
            ResourceLocation k = BuiltInRegistries.POTION.getKey(p);
            return k == null ? null : k.toString();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSupportPolicy", ignored);
            return null;
        }
    }

    /** 物品裸注册名（不带变体后缀） */
    public static String baseId(ItemStack s) {
        return com.maidsmart.action.ItemUses.baseIdOf(s);
    }

    /** 记账键（写进分类表/网格勾选用的值） */
    public static String key(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        if (potionItem(s)) {
            String base = baseId(s);
            String pid = potionId(s);
            return pid == null ? base : base + POTION_SEP + pid;
        }
        return com.maidsmart.action.ItemUses.key(s);
    }

    /** 自带效果的普通食物（1.21.1 = FOOD 组件里的 effects 非空） */
    public static boolean effectFood(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        try {
            FoodProperties fp = s.get(net.minecraft.core.component.DataComponents.FOOD);
            return fp != null && !fp.effects().isEmpty();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSupportPolicy", ignored);
            return false;
        }
    }

    /** 牛奶桶 / 蜂蜜瓶 */
    public static boolean milkOrHoney(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        Item it = s.getItem();
        if (it == net.minecraft.world.item.Items.HONEY_BOTTLE) {
            return true;
        }
        Item milk = BuiltInRegistries.ITEM.get(ResourceLocation.parse("minecraft:milk_bucket"));
        return milk != null && it == milk;
    }

    /** 金苹果 / 附魔金苹果 */
    public static boolean goldenApple(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        Item it = s.getItem();
        return it == net.minecraft.world.item.Items.GOLDEN_APPLE
                || it == net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE;
    }

    /** 本表候选判据：药水 ∪ 金苹果 ∪ 牛奶/蜂蜜 ∪ 自带效果的普通食物 */
    public static boolean candidate(ItemStack s) {
        return potionItem(s) || goldenApple(s) || milkOrHoney(s) || effectFood(s);
    }

    // ---------------------------------------------------------------- 分类

    /** 该栈在当前档（通用/亡灵）下的有效分类 */
    public static Cat category(ItemStack s, boolean undead) {
        if (s == null || s.isEmpty()) {
            return Cat.OFF;
        }
        Cat over = override(s, undead);
        if (over != null) {
            return over;
        }
        return derive(s, undead);
    }

    /** 玩家在表里显式写过的分类；没写过返回 null（= 用推断值） */
    public static Cat override(ItemStack s, boolean undead) {
        try {
            List<? extends String> entries = undead
                    ? com.maidsmart.config.MaidSmartConfig.AID_SUPPORT_CATEGORY_UNDEAD.get()
                    : com.maidsmart.config.MaidSmartConfig.AID_SUPPORT_CATEGORY.get();
            String k = key(s);
            String base = baseId(s);
            for (String e : entries) {
                if (e == null || e.trim().isEmpty()) {
                    continue;
                }
                int eq = e.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String lhs = e.substring(0, eq).trim();
                Cat c = parseCat(e.substring(eq + 1));
                if (c == null) {
                    continue;
                }
                if (entryMatches(lhs, s, k, base)) {
                    return c;
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSupportPolicy", ignored);
        }
        return null;
    }

    /** 表里 lhs 是否命中该栈 */
    private static boolean entryMatches(String lhs, ItemStack s, String key, String base) {
        if (potionItem(s)) {
            String pid = potionId(s);
            if (pid != null && lhs.equalsIgnoreCase(base + POTION_SEP + pid)) {
                return true;
            }
            return lhs.equalsIgnoreCase(base); // 裸物品 id = 该形态所有药水
        }
        return com.maidsmart.action.ItemUses.matches(lhs, s);
    }

    /** 分类字符串 → 枚举；不认识返回 null */
    public static Cat parseCat(String s) {
        if (s == null) {
            return null;
        }
        switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "counter":
            case "对策":
                return Cat.COUNTER;
            case "buff":
            case "增益":
                return Cat.BUFF;
            case "heal":
            case "回复":
                return Cat.HEAL;
            case "off":
            case "禁用":
                return Cat.OFF;
            default:
                return null;
        }
    }

    /** 分类 → 配置里存的字符串 */
    public static String catName(Cat c) {
        switch (c) {
            case COUNTER:
                return "counter";
            case BUFF:
                return "buff";
            case HEAL:
                return "heal";
            case OFF:
                return "off";
            default:
                return "";
        }
    }

    /** 配置条目 {@code 键=分类} 的左侧「键」；格式非法返回 null（面板用） */
    public static String entryLhs(String entry) {
        if (entry == null) {
            return null;
        }
        int eq = entry.indexOf('=');
        if (eq < 0) {
            return entry.trim();
        }
        return entry.substring(0, eq).trim();
    }

    /** 两张表里出现过的所有条目（面板候选兜底用） */
    public static java.util.List<String> allConfiguredEntries() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            out.addAll(com.maidsmart.config.MaidSmartConfig.AID_SUPPORT_CATEGORY.get());
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSupportPolicy", ignored);
        }
        try {
            out.addAll(com.maidsmart.config.MaidSmartConfig.AID_SUPPORT_CATEGORY_UNDEAD.get());
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSupportPolicy", ignored);
        }
        return out;
    }

    /** 按效果自动推断分类（玩家没在表里写过时用）；推断不出 → OFF */
    public static Cat derive(ItemStack s, boolean undead) {
        try {
            if (goldenApple(s)) {
                return Cat.HEAL; // 金苹果走低血即时增益链
            }
            if (milkOrHoney(s)) {
                return Cat.COUNTER; // 解负面 / 解中毒
            }
            java.util.List<String> effIds = new java.util.ArrayList<>();
            if (potionItem(s)) {
                for (MobEffectInstance e : com.maidsmart.brew.PotionCompat.allEffects(s)) {
                    ResourceLocation k = BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect().value());
                    if (k != null) {
                        effIds.add(k.toString());
                    }
                }
            } else if (effectFood(s)) {
                FoodProperties fp = s.get(net.minecraft.core.component.DataComponents.FOOD);
                if (fp != null) {
                    for (FoodProperties.PossibleEffect pe : fp.effects()) {
                        ResourceLocation k = BuiltInRegistries.MOB_EFFECT.getKey(
                                pe.effect().getEffect().value());
                        if (k != null) {
                            effIds.add(k.toString());
                        }
                    }
                }
            } else {
                return Cat.DEFAULT; // 不在候选范围
            }
            return classifyEffects(effIds, undead);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSupportPolicy", ignored);
            return Cat.OFF;
        }
    }

    /** 效果 id 列表 → 分类（亡灵语义翻转：治疗↔伤害） */
    public static Cat classifyEffects(java.util.List<String> ids, boolean undead) {
        int best = 0; // 0=OFF 1=COUNTER 2=BUFF 3=HEAL
        for (String id : ids) {
            int r;
            switch (id) {
                case "minecraft:instant_health":
                    r = undead ? 0 : 3; // 治疗药水对亡灵是扣血
                    break;
                case "minecraft:instant_damage":
                    r = undead ? 3 : 0; // 伤害药水对亡灵是回血
                    break;
                case "minecraft:regeneration":
                    // 实测八百一十七·十四：亡灵免疫再生——原版 LivingEntity.canBeAffected 直接拒收
                    // （1.20.1 = MobType.UNDEAD + MobEffects.f_19605_；1.21.1 = 实体类型标签
                    // ignores_poison_and_regen + MobEffects.REGENERATION），所以亡灵档算「禁用」。
                    r = undead ? 0 : 3; break;
                case "minecraft:fire_resistance":
                case "minecraft:water_breathing":
                    r = 1; break;
                case "minecraft:strength":
                case "minecraft:speed":
                case "minecraft:jump_boost":
                case "minecraft:night_vision":
                case "minecraft:resistance":
                case "minecraft:health_boost":
                case "minecraft:absorption":
                case "minecraft:saturation":
                case "minecraft:luck":
                case "minecraft:dolphins_grace":
                case "minecraft:conduit_power":
                case "minecraft:slow_falling":
                case "minecraft:glowing":
                    r = 2; break;
                default:
                    r = 0; break;
            }
            if (r > best) {
                best = r;
            }
        }
        switch (best) {
            case 3: return Cat.HEAL;
            case 2: return Cat.BUFF;
            case 1: return Cat.COUNTER;
            default: return Cat.OFF;
        }
    }

    // ---------------------------------------------------------------- 门禁

    /**
     * <b>支援链统一门禁</b>：该物品在当前档、当前链（ctx）下是否可用。
     * <p>{@code OFF} → 任何链都不用；显式分类 → 只在该类链里用；{@code DEFAULT}（没分类过）
     * → 哪条链都能用（旧行为，留空表逐字节一致）。不在候选范围的物品一律放行。
     */
    public static boolean allow(ItemStack s, boolean undead, Ctx ctx) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        if (!candidate(s)) {
            return true; // 不在本表范围 → 本表不管
        }
        return catOk(category(s, undead), ctx);
    }

    /**
     * 实测八百一十七（续）：<b>女仆自用</b>门禁——女仆自己喝药水/吃食物时也走支援分类表。
     *
     * <p>两点实证：<br>
     * ① 女仆自己<b>不是亡灵</b>——javap：{@code LivingEntity.getMobType()}
     *    默认返回非亡灵，只有 Zombie 这类覆写为亡灵；
     *    {@code EntityMaid} 两个都不覆写 → {@code isInvertedHealAndHarm()=false}。
     *    所以她自用一律走<b>通用表</b>，与「亡灵支援表」无关。<br>
     * ② 与「支援女仆」<b>同一份白名单</b>：。
     *
     * <p>开关 {@code combat.aidSelfUse}（默认开）：关掉 = 恒 {@code true}，
     * 自用退回旧行为（不看两张表，仍按效果自动挑 + 治疗食物名单）。
     */
    public static boolean allowSelf(ItemStack s, Ctx ctx) {
        try {
            if (!com.maidsmart.config.MaidSmartConfig.AID_SELF_USE.get()) {
                return true; // 关掉 = 不管（与旧版逐字节一致）
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSupportPolicy", ignored);
            return true;
        }
        return allow(s, false, ctx); // 女仆非亡灵 → 通用表
    }

    /** 分类与链是否相容 */
    public static boolean catOk(Cat c, Ctx ctx) {
        if (c == Cat.OFF) {
            return false;
        }
        if (c == Cat.DEFAULT) {
            return true; // 没分类过 → 任何链都能用
        }
        switch (ctx) {
            case COUNTER: return c == Cat.COUNTER;
            case BUFF: return c == Cat.BUFF;
            case HEAL: return c == Cat.HEAL;
            // 治疗/处境共用口子：放行对策+回复（实际投的药水由调用处的药水名集合钉死），排除增益
            case TREAT: return c != Cat.BUFF;
            default: return false;
        }
    }

    /** 是否属于"生命回复类"（决定走低血链）；未分类算回复类（照旧只在低血时用） */
    public static boolean isHealClass(ItemStack s, boolean undead) {
        Cat c = category(s, undead);
        return c == Cat.HEAL || c == Cat.DEFAULT;
    }

    /** 生命回复类的触发血量比例（「治疗触发血量」，默认 0.3） */
    public static double healThreshold() {
        try {
            return com.maidsmart.config.MaidSmartConfig.AID_HEALTH_THRESHOLD.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSupportPolicy", ignored);
            return 0.3;
        }
    }

    /** 目标血比例是否已达"可以用回复类"的门槛 */
    public static boolean healReady(net.minecraft.world.entity.LivingEntity target) {
        try {
            double ratio = target.getHealth() / Math.max(1.0f, target.getMaxHealth());
            return ratio < healThreshold();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSupportPolicy", ignored);
            return false;
        }
    }

    /**
     * 预检用：女仆身上有没有"至少一个可用"的支援物品。
     * @param includeDefault 是否把"没分类过"的物品也算可用
     */
    public static boolean anyUsable(EntityMaid maid, boolean includeDefault) {
        try {
            net.neoforged.neoforge.items.IItemHandler inv = maid.getAvailableBackpackInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                if (usable(inv.getStackInSlot(i), includeDefault)) {
                    return true;
                }
            }
            for (ItemStack s : new ItemStack[]{maid.getMainHandItem(), maid.getOffhandItem()}) {
                if (usable(s, includeDefault)) {
                    return true;
                }
            }
            return com.maidsmart.tool.MaidExtraContainer.contains(
                    maid, st -> usable(st, includeDefault));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSupportPolicy", ignored);
            return false;
        }
    }

    private static boolean usable(ItemStack s, boolean includeDefault) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        if (!candidate(s)) {
            return true; // 普通物品归「投喂食物」管
        }
        for (boolean undead : new boolean[]{false, true}) {
            Cat c = category(s, undead);
            if (c == Cat.OFF) {
                continue;
            }
            if (includeDefault || c != Cat.DEFAULT) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 展示

    /** 网格/名单行的后缀（药水标效果名，耐久标"剩余 N/M 次"）；无后缀返回空串 */
    public static String variantLabel(ItemStack s) {
        if (potionItem(s)) {
            String pid = potionId(s);
            return pid == null ? "" : pid;
        }
        return com.maidsmart.action.ItemUses.label(s);
    }

    /** 分类图标（面板用） */
    public static String catIcon(Cat c) {
        switch (c) {
            case COUNTER: return "\u25c6"; // ◆ 对策
            case BUFF: return "\u25b2";    // ▲ 增益
            case HEAL: return "\u2665";    // ♥ 回复
            case OFF: return "\u2716";     // ✖ 禁用
            default: return "\u00b7";      // · 自动
        }
    }

    /** 分类名字（面板/悬停用） */
    public static String catLabel(Cat c) {
        switch (c) {
            case COUNTER: return "对策";
            case BUFF: return "增益";
            case HEAL: return "回复";
            case OFF: return "禁用";
            default: return "自动";
        }
    }

    /** 这瓶药水是不是"对亡灵有害"（治疗药水系）——面板亡灵档提示用 */
    public static boolean harmfulToUndead(ItemStack s) {
        try {
            if (!potionItem(s)) {
                return false;
            }
            for (MobEffectInstance e : com.maidsmart.brew.PotionCompat.allEffects(s)) {
                ResourceLocation k = BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect().value());
                if (k != null && "minecraft:instant_health".equals(k.toString())) {
                    return true;
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSupportPolicy", ignored);
        }
        return false;
    }

    /**
     * 实测八百一十七·十四：这瓶药水对亡灵是不是「无效」（再生系）——面板亡灵档提醒用。
     * 与 {@link #harmfulToUndead} 分开：那是「扣血」，这是「白喝」。原版
     * {@code LivingEntity.canBeAffected} 对亡灵直接拒收 REGENERATION（同拒收中毒），
     * 所以给亡灵灌再生药水是整瓶白费、一滴血不回。
     */
    public static boolean uselessToUndead(ItemStack s) {
        try {
            if (!potionItem(s)) {
                return false;
            }
            for (MobEffectInstance e : com.maidsmart.brew.PotionCompat.allEffects(s)) {
                ResourceLocation k = BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect().value());
                if (k != null && "minecraft:regeneration".equals(k.toString())) {
                    return true;
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidSupportPolicy", ignored);
        }
        return false;
    }

    /** 面板点击时的下一个分类：自动·对策·增益·回复·禁用·自动…（"自动"= 删条目） */
    public static Cat next(Cat c) {
        switch (c) {
            case DEFAULT: return Cat.COUNTER;
            case COUNTER: return Cat.BUFF;
            case BUFF: return Cat.HEAL;
            case HEAL: return Cat.OFF;
            default: return Cat.DEFAULT;
        }
    }
}
