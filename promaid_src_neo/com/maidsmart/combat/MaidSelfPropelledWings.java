package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * v1.3.0(beta) 实测七百一十【自推鞘翅：不靠烟花也能飞的那一类模组鞘翅】。
 *
 * <p>本类是 {@code promaid_src} 同名类的 **1.21.1 NeoForge 镜像**（官方名，
 * {@code BuiltInRegistries.ITEM} / {@code ResourceLocation.parse}），逐行同口径；
 * 两树差异只在注册表与 SRG 名，**行为一字不差**。
 *
 * ── 一、这两家到底加了什么（反编译实证，逐条）──
 * <b>伊卡洛斯之翼</b>（1.21.1：{@code locusazzurro_icaruswings-1.21-0.7.0}）：
 * <ul>
 *   <li>{@code feather_wings} / {@code colored_feather_wings} / {@code golden_feather_wings}
 *       / {@code paper_wings} / {@code magic_wings} / {@code flandre_magic_wings}
 *       —— 普通 {@code ElytraItem}，<b>没有任何自推</b>，只是耐久不同。<b>不列进本表</b>。</li>
 *   <li>{@code ikaros_wings} / {@code nymph_wings} / {@code astraea_wings} / {@code chaos_wings}
 *       / {@code hiyori_wings} / {@code melan_wings} —— {@code SynapseWings}，**自推**：
 *       {@code FlyingEventsHandler.onPlayerTick}（{@code PlayerTickEvent.Pre}）里，
 *       只要她在滑翔就每 tick 加一份推力
 *       {@code v += (look·d + (look·i − v)·t) · c}（d/i/t 见 {@link #paramsOf}）。
 *       **不需要按跳跃键**。</li>
 * </ul>
 * <b>神秘遗物+</b>（{@code enigmaticlegacyplus-1.21.1-1.1.2}）：{@code majestic_elytra}（壮丽鞘翅）与
 * {@code chaos_elytra}（混沌之傲）继承 {@code BaseElytraItem}，它把 {@code canElytraFly} 直接写成
 * {@code true}（**不看是不是玩家**）→ 女仆穿得上、滑得起来。自推在 {@code flyingBoost(player)}：
 * **按住跳跃键**时 {@code v = v×0.48 + look×0.64}（壮丽）/ {@code v = v×0.5 + look×k}（混沌，
 * k = {@code flyingSpeedModifier}，默认 0.8）。
 *
 * <p>【1.20.1 侧不可用】那边的神秘遗物（{@code EnigmaticLegacy} / {@code enigmaticaddons}）把
 * {@code canElytraFly} 写死了 {@code entity instanceof Player && ...}，女仆**连滑翔都做不到**，
 * 所以表里不列它们（列了也只是死的，还会让"她能动"看起来像我们在瞎认）。
 *
 * ── 二、为什么"认物品"远远不够：三家都只推玩家 ──
 * 上面的自推入口**全部**挂在 {@code PlayerTickEvent} / {@code instanceof ServerPlayer} /
 * 客户端跳跃键包上。**女仆不是 Player**，只把它算作"推进剂"的结果是她跳起来、展开滑翔、
 * 然后**一路往下沉**。所以本类真正的活是 <b>由我们替她施加推力</b>（{@link #tick}）。
 *
 * ── 三、通解通法：同一个形状、各自的小数 ──
 * 三家的公式是**同一个形状**：{@code v ← v×gain + look×add}（原版挂载烟花也是这一条：
 * {@code v ← v×0.5 + look×0.85}）。所以通解 = 一张表（{@link #paramsOf}）+ 一个窗口
 * （{@link #tick}）+ 一条接入链。以后再有同类模组，只需往配置的资格表里加 id——
 * 认不出具体型号就走 {@link #FALLBACK} 兜底模型，**不需要改代码**。
 *
 * ── 四、刻意做的三件事 ──
 * <ol>
 *   <li><b>不消耗、不扣额外耐久</b>：模组对玩家自推是额外扣耐久的（伊卡洛斯每 20 tick、
 *       神秘遗物每 5 tick），女仆这边**只按原版滑翔的节奏扣**（{@code updateFallFlying}
 *       每 20 tick 扣 1）——那两个是我们借它的**动作**，这里只借它的**物理**，不该替它扣钱。</li>
 *   <li><b>排在补推链最末</b>：扇子 → 烟花 → 法术 → 激流 → **自推鞘翅**。前四样存在的存档
 *       手感一字不变。</li>
 *   <li><b>窗口收敛、不会飞走</b>：公式本身收敛（不动点 = 视线 × add/(1−gain)），窗口开着时
 *       速度往那一个值收敛；窗口到期（{@link #WINDOW_TICKS} 40 tick）交还滑翔，掉高了由链路
 *       按同一个判据（速度 &lt; 0.35）再补一次。</li>
 * </ol>
 *
 * ── 五、与"仿创造飞行"（{@code MISC_FREE_FLIGHT}）的分工 ──
 * 那个是另一个功能（给女仆创造模式飞行：悬浮+自由升降，不需要鞘翅、也不是滑翔）。本类走
 * **鞘翅滑翔**这条物理（原版滑翔位 + 滑翔阻力），两者互不干涉，资格表各自独立。
 */
public final class MaidSelfPropelledWings {

    private MaidSelfPropelledWings() {
    }

    /* ==================== 推力模型参数 ==================== */

    /**
     * 一条推力公式的两个系数：{@code v ← v×gain + look×add}。
     *
     * <p>之所以是"gain + add"而不是"不动点 + 收敛率"：这正是三家源码里**逐字**的那个形状
     * （伊卡洛斯 {@code v + (look·d + (look·i − v)·t)} 展开后 = {@code v·(1−t) + look·(d + i·t)}，
     * 所以 {@code gain = 1−t}、{@code add = d + i·t}）。
     */
    public static final class Params {
        /** 速度保留系数（原版烟花 / 混沌 = 0.5，壮丽 = 0.48） */
        public final double gain;
        /** 每 tick 沿视线加的这一份（原版烟花 = 0.85） */
        public final double add;
        /** 一句话说明（日志 / 诊断用，形如「伊卡洛斯·双重可变翼」） */
        public final String label;

        Params(double gain, double add, String label) {
            this.gain = gain;
            this.add = add;
            this.label = label;
        }

        /** 这条公式的收敛速度（格/tick）：不动点 = 视线 × 它。日志/诊断用。 */
        public double cruise() {
            return add / Math.max(1.0E-6, 1.0 - gain);
        }
    }

    /**
     * 认不出具体型号时的**兜底模型**：{@code v ← v×0.5 + look×0.5}（不动点 = 视线 ×1.0）。
     * 取值见 forge 版同名常量的注释（最慢型号约 0.7、最快约 2.2，取中间偏保守的 1.0）。
     */
    public static final Params FALLBACK = new Params(0.5, 0.5, "自推鞘翅（通用）");

    /** 内置参数表：物品注册名 → 这条公式的系数。**逐条来自反编译**（见类注释）。 */
    private static final Map<String, Params> TABLE = new HashMap<>();

    static {
        // ── 伊卡洛斯之翼 · 空域系（SynapseWings，不用按键，滑着就推）──
        TABLE.put("locusazzurro_icaruswings:ikaros_wings", new Params(0.5, 0.35, "伊卡洛斯·双重可变翼"));
        TABLE.put("locusazzurro_icaruswings:nymph_wings", new Params(0.5, 0.68, "伊卡洛斯·电子战用隐秘翼"));
        TABLE.put("locusazzurro_icaruswings:astraea_wings", new Params(0.5, 1.10, "伊卡洛斯·超加速型羽翼"));
        TABLE.put("locusazzurro_icaruswings:chaos_wings", new Params(0.5, 0.64, "伊卡洛斯·混沌进化之翼"));
        TABLE.put("locusazzurro_icaruswings:hiyori_wings", new Params(0.5, 0.63, "伊卡洛斯·缓滞时空羽翼"));
        TABLE.put("locusazzurro_icaruswings:melan_wings", new Params(0.5, 0.35, "伊卡洛斯·反逆可变翼"));
        // ── 神秘遗物+（1.21.1）：v = v×gain + look×add ──
        TABLE.put("enigmaticlegacyplus:majestic_elytra", new Params(0.48, 0.64, "神秘遗物+·壮丽鞘翅"));
        TABLE.put("enigmaticlegacyplus:chaos_elytra", new Params(0.5, 0.80, "神秘遗物+·混沌之傲"));
        // ── 1.20.1 神秘遗物：canElytraFly 写死 instanceof Player，女仆滑不起来，**不列** ──
    }

    /** 默认资格物品表（配置项为空时的回退，也是手册里"默认认哪几件"的出处）。 */
    public static final List<String> DEFAULT_ITEMS = List.of(
            "locusazzurro_icaruswings:ikaros_wings",
            "locusazzurro_icaruswings:nymph_wings",
            "locusazzurro_icaruswings:astraea_wings",
            "locusazzurro_icaruswings:chaos_wings",
            "locusazzurro_icaruswings:hiyori_wings",
            "locusazzurro_icaruswings:melan_wings",
            "enigmaticlegacyplus:majestic_elytra",
            "enigmaticlegacyplus:chaos_elytra");

    /* ==================== 状态表 ==================== */

    /**
     * 推力窗口：UUID → 这一扇窗的状态。理由同 forge 版（自推是收敛的、补推是偶发的，
     * 所以按"补推开一扇窗、窗内每 tick 推一下、到期自动关"的节奏来）。
     */
    private static final Map<UUID, Window> WINDOWS = new HashMap<>();

    /** 窗口时长（tick）：40 = 2 秒（与 {@code MaidRiptideBoost.MAX_THRUST_TICKS} 同量级）。 */
    private static final int WINDOW_TICKS = 40;

    private static final class Window {
        final Params params;
        int left;

        Window(Params params, int left) {
            this.params = params;
            this.left = left;
        }
    }

    /* ==================== 资格判定 ==================== */

    /** 总开关（{@code combat.selfWings}，默认开） */
    public static boolean enabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_SELF_WINGS.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 玩家配置的资格物品表（读不到 / 为空时回退 {@link #DEFAULT_ITEMS}） */
    @SuppressWarnings("unchecked")
    private static List<String> configuredIds() {
        try {
            List<String> ids = (List<String>) com.maidsmart.config.MaidSmartConfig.COMBAT_SELF_WINGS_ITEMS.get();
            if (ids == null || ids.isEmpty()) {
                return DEFAULT_ITEMS;
            }
            return ids;
        } catch (Throwable ignored) {
            return DEFAULT_ITEMS;
        }
    }

    /**
     * 这件物品是不是"自推鞘翅"——资格判定。**只看配置表**（含 {@code #标签} 写法）。
     *
     * <p>【为什么不按类型判】三家分别继承 {@code ElytraItem} / {@code BaseElytraItem}，
     * 而 {@code BaseElytraItem} 不继承 {@code ElytraItem}——按类型判要同时认三棵树还要 import。
     * 按注册名（+ 标签）判是 {@code TwilightFanKit} 认羽扇的同一种做法：模组不在场时恒 false。
     */
    public static boolean isSelfPropelled(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            String id = key == null ? "" : key.toString();
            for (String raw : configuredIds()) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String s = raw.trim();
                if (s.startsWith("#")) {
                    if (matchesTag(stack, s.substring(1))) {
                        return true;
                    }
                } else if (s.equals(id)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 物品标签判定（{@code #命名空间:标签} 写法，与仿创造飞行的物品表同款）。
     *
     * <p>【1.21.1 的写法】{@code TagKey.create(Registries.ITEM, id)} 造键 + {@code stack.is(tag)}——
     * 不遍历 {@code getTags()}（那一个在 1.21.1 返回 {@code Stream}，不是 {@code Iterable}，
     * for-each 编译不过；而且原版/Neo 两侧的标签就是同一套原版标签）。
     */
    private static boolean matchesTag(ItemStack stack, String tagId) {
        try {
            ResourceLocation loc = ResourceLocation.parse(tagId.trim());
            net.minecraft.tags.TagKey<net.minecraft.world.item.Item> tag =
                    net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, loc);
            return stack.is(tag);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 她**胸甲槽**里那件是不是自推鞘翅（返回它，不是就空）。
     * 理由同 forge 版：原版滑翔闸门只认胸甲槽那一件，资格必须与"能滑翔"同口径。
     */
    public static ItemStack wornWings(EntityMaid maid) {
        try {
            if (maid == null) {
                return ItemStack.EMPTY;
            }
            ItemStack chest = maid.getItemBySlot(EquipmentSlot.CHEST);
            return isSelfPropelled(chest) ? chest : ItemStack.EMPTY;
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }

    /** 她此刻有没有资格靠自推鞘翅飞（开关 + 胸甲槽那件在表里） */
    public static boolean hasWings(EntityMaid maid) {
        return enabled() && !wornWings(maid).isEmpty();
    }

    /** 这一件物品对应的推力参数（表里没有 → {@link #FALLBACK}） */
    public static Params paramsOf(ItemStack stack) {
        try {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (key != null) {
                Params p = TABLE.get(key.toString());
                if (p != null) {
                    return p;
                }
            }
        } catch (Throwable ignored) {
        }
        return FALLBACK;
    }

    /** 全局推力倍数（{@code combat.selfWingsScale}，默认 1.0；读不到用 1.0） */
    public static double scale() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_SELF_WINGS_SCALE.get();
        } catch (Throwable ignored) {
            return 1.0;
        }
    }

    /* ==================== 推力窗口 ==================== */

    /**
     * 【补推】开一扇推力窗（或给已开的那扇续期）。返回 true = 这一口确实开了。
     * **不推第一下**——{@link #tick} 就在同一 tick 里稍后被调用，重复推会白多一份。
     */
    public static boolean ignite(EntityMaid maid) {
        try {
            if (maid == null || !enabled()) {
                return false;
            }
            ItemStack wings = wornWings(maid);
            if (wings.isEmpty()) {
                return false;
            }
            // 必须在滑翔：这条推力是"鞘翅滑翔的推进"，收着翅时推了也白推
            if (!MaidFlightKit.isGliding(maid)) {
                return false;
            }
            Params p = paramsOf(wings);
            WINDOWS.put(maid.getUUID(), new Window(p, WINDOW_TICKS));
            com.maidsmart.tool.PromaidLog.log("空袭·自推鞘翅", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 自推鞘翅起飞/补推（" + p.label + "：v←v×" + fmt(p.gain) + " + 视线×"
                    + fmt(p.add * scale()) + "，收敛约 " + fmt(p.cruise() * scale()) + " 格/tick，推 "
                    + WINDOW_TICKS + " tick）");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 每 tick 推一下（由空袭与飞行跟随两条飞行链各调一次，都在各自相位派发**之前**）。
     * 只在她**滑翔**时生效（与 {@code MaidRiptideBoost.tick} 同一个纪律）。
     */
    public static void tick(EntityMaid maid) {
        try {
            if (maid == null) {
                return;
            }
            UUID id = maid.getUUID();
            Window w = WINDOWS.get(id);
            if (w == null) {
                return;
            }
            if (!enabled() || w.left <= 0 || !MaidFlightKit.isGliding(maid) || wornWings(maid).isEmpty()) {
                WINDOWS.remove(id);
                return;
            }
            pushOnce(maid, w.params);
            w.left--;
        } catch (Throwable ignored) {
        }
    }

    /** 推一下：{@code v ← v×gain + look×add}——**照抄该鞘翅自己的公式**（见 {@link Params}）。 */
    private static void pushOnce(EntityMaid maid, Params p) {
        double k = scale();
        double gain = p.gain;
        double add = p.add * k;
        Vec3 look = maid.getLookAngle();
        Vec3 v = maid.getDeltaMovement();
        maid.setDeltaMovement(new Vec3(
                v.x * gain + look.x * add,
                v.y * gain + look.y * add,
                v.z * gain + look.z * add));
    }

    /** 收手：把这扇窗撤掉（返回 true = 本来还开着，供日志用）。已给的速度**不动**。 */
    public static boolean clear(EntityMaid maid) {
        try {
            return maid != null && WINDOWS.remove(maid.getUUID()) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 这一只此刻是否挂着自推鞘翅的推力窗（只读，诊断用） */
    public static boolean isThrusting(EntityMaid maid) {
        try {
            return maid != null && WINDOWS.containsKey(maid.getUUID());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 清场（女仆移除 / 死亡 / 服务器停止） */
    public static void forget(UUID maidId) {
        if (maidId != null) {
            WINDOWS.remove(maidId);
        }
    }

    public static void clearAll() {
        WINDOWS.clear();
    }

    /** 一行诊断（同 forge 版）：她此刻算不算能飞、认的是哪一件、参数多少。 */
    public static String diag(EntityMaid maid) {
        try {
            if (maid == null) {
                return "自推鞘翅: 女仆为空";
            }
            ItemStack chest = maid.getItemBySlot(EquipmentSlot.CHEST);
            StringBuilder sb = new StringBuilder("自推鞘翅: 开关=").append(enabled())
                    .append(" 胸甲=").append(describe(chest))
                    .append(" 在表里=").append(isSelfPropelled(chest));
            if (isSelfPropelled(chest)) {
                Params p = paramsOf(chest);
                sb.append(" 型号=").append(p.label)
                        .append(" 收敛=").append(fmt(p.cruise() * scale())).append(" 格/tick");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "自推鞘翅: 诊断异常 " + t;
        }
    }

    /** 一行物品描述：`1xlocusazzurro_icaruswings:ikaros_wings` / `空`（诊断日志专用） */
    private static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "空";
        }
        String id;
        try {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            id = key == null ? stack.getItem().toString() : key.toString();
        } catch (Throwable ignored) {
            id = stack.getItem().toString();
        }
        return stack.getCount() + "x" + id;
    }

    /** 日志里的小数（两位，定点免得不同 JVM 打出科学计数法） */
    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }

    /** 手册里那张"默认认哪几件、各自多快"的表（手册与 changelog 共用这一份文本）。 */
    public static String manualLines() {
        StringBuilder sb = new StringBuilder();
        for (String id : DEFAULT_ITEMS) {
            Params p = TABLE.get(id);
            if (p == null) {
                continue;
            }
            sb.append("\u00a7e").append(p.label).append("\u00a7r（").append(id).append("）：巡航约 ")
                    .append(fmt(p.cruise())).append(" 格/tick\n");
        }
        return sb.toString();
    }
}
