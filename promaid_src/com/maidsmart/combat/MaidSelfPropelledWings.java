package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * v1.3.0(beta) 实测七百一十【自推鞘翅：不靠烟花也能飞的那一类模组鞘翅】。
 *
 * ── 玩家原话（本次需求的起点）──
 * <pre>
 *   "我现在又安装了伊卡洛斯之翼和神秘遗物+，加完模组之后有两种鞘翅不需要烟花也可以起飞。
 *    我觉得需要做相关的兼容，如果装配了这些物品，相当于同时满足了烟花以及推进物品的要求。
 *    然后同时也需要考虑一下他们的运动逻辑是怎么样的，看看怎么安排在女仆身上。
 *    之后其他的类似物品看有没有一个通解通法。"
 * </pre>
 *
 * ── 一、这两家到底加了什么（反编译实证，逐条）──
 * <b>伊卡洛斯之翼</b>（1.21.1：{@code locusazzurro_icaruswings-1.21-0.7.0}）：
 * <ul>
 *   <li>{@code feather_wings} / {@code colored_feather_wings} / {@code golden_feather_wings}
 *       / {@code paper_wings} / {@code magic_wings} / {@code flandre_magic_wings}
 *       —— 普通 {@code ElytraItem}（{@code FeatherWings} / 匿名 {@code AbstractWings}），
 *       <b>没有任何自推</b>，只是耐久不同。<b>不列进本表</b>（列了也没用：
 *       {@link #paramsOf} 认不出它们，只会走兜底模型）。</li>
 *   <li>{@code ikaros_wings} / {@code nymph_wings} / {@code astraea_wings} / {@code chaos_wings}
 *       / {@code hiyori_wings} / {@code melan_wings} —— {@code SynapseWings}，**自推**：
 *       {@code FlyingEventsHandler.onPlayerTick}（{@code PlayerTickEvent.Pre}）里，
 *       只要她在滑翔就每 tick 加一份推力
 *       {@code v += (look·d + (look·i − v)·t) · c}（d/i/t 见 {@link #paramsOf}，
 *       c = 它自己的配置 {@code wings_speed_mod}，默认 1）。**不需要按跳跃键**。</li>
 * </ul>
 * <b>神秘遗物 / 神秘遗物+</b>：
 * <ul>
 *   <li>1.21.1（{@code enigmaticlegacyplus-1.21.1-1.1.2}）：{@code majestic_elytra}（壮丽鞘翅）与
 *       {@code chaos_elytra}（混沌之傲）继承 {@code BaseElytraItem}，它把
 *       {@code canElytraFly} 直接写成 {@code true}（**不看是不是玩家**）→ 女仆穿得上、滑得起来。
 *       自推在 {@code flyingBoost(player)}：**按住跳跃键**时
 *       {@code v = v×0.48 + look×0.64}（壮丽）/ {@code v = v×0.5 + look×k}（混沌，
 *       k = 它自己的 {@code flyingSpeedModifier}，默认 0.8）。</li>
 *   <li>1.20.1（{@code EnigmaticLegacy-2.30.1} / {@code enigmaticaddons-1.2.6}）：**不可用**——
 *       两件的 {@code canElytraFly} 都写死 {@code entity instanceof Player && ...}
 *       （{@code EnigmaticElytra.java:204}、{@code ChaosElytra.java:312} 实证），
 *       女仆**连滑翔都做不到**，穿上去就是一件普通胸甲。所以 1.20.1 树里本表为空、
 *       功能自然不生效（手册里写明，不做 hack 去强改别人的类）。</li>
 * </ul>
 *
 * ── 二、为什么"认物品"远远不够：三家都只推玩家 ──
 * 上面的自推入口**全部**挂在 {@code PlayerTickEvent} / {@code instanceof ServerPlayer} /
 * 客户端跳跃键包上。**女仆不是 Player**，所以：
 * 只把它算作"推进剂"的结果是——她跳起来、展开滑翔、然后**一路往下沉**（没有任何东西在推）。
 * 所以本类真正的活是 <b>由我们替她施加推力</b>（{@link #tick}），
 * "认物品"只是资格凭证（{@link #hasWings}）。
 *
 * ── 三、通解通法：同一个形状、各自的小数 ──
 * 三家的推力公式其实是**同一个形状**（每 tick 把速度往视线方向拉一档）：
 * <pre>
 *   v ← v×GAIN + look×add        // GAIN/add 因型号而异
 * </pre>
 * 原版挂载烟花也是这一条（{@code v ← v×0.5 + look×0.85}，不动点 = 视线×1.7）。
 * 所以通解 = <b>一张表（{@link #paramsOf}：物品 id → 这条公式的两个系数）+ 一个窗口
 * （{@link #tick}）+ 一条接入链（空袭/飞行跟随各一处调用）</b>。
 * 以后再有同类模组，只要往 {@link com.maidsmart.config.MaidSmartConfig#COMBAT_SELF_WINGS_ITEMS}
 * 里加 id —— 认不出具体型号就走 {@link #FALLBACK} 兜底模型，**不需要改代码**。
 *
 * ── 四、刻意做的三件事（与其它推进剂的边界）──
 * <ol>
 *   <li><b>不消耗、不扣额外耐久</b>：模组那边对玩家自推是**额外扣耐久**的
 *       （伊卡洛斯 {@code elytraFlightTick} 每 20 tick 扣、神秘遗物自推时每 5 tick 扣），
 *       女仆这边**只按原版滑翔的节奏扣**（{@code updateFallFlying} 自己每 20 tick 扣 1）——
 *       与"烟花/羽扇/激流"那几条腿的"按它自己的口径扣"区分开：
 *       那两个是我们**借它的动作**，这里只是借它的**物理**，不该替它扣钱。
 *       想让她飞得更贵？把 {@code combat.selfWingsScale} 调小（慢）而不是罚耐久。</li>
 *   <li><b>排在补推链最末</b>：扇子 → 烟花 → 法术 → 激流 → **自推鞘翅**。前三样存在的存档
 *       手感一字不变（与 实测六百三十三 加激流那条腿时同一个纪律）。</li>
 *   <li><b>窗口收敛、不会飞走</b>：这条公式本身是收敛的（不动点 = 视线 × add/(1−GAIN)），
 *       窗口开着时速度往那一个值收敛，不会"越推越快一路飞走"；窗口到期（
 *       {@link #WINDOW_TICKS} 40 tick = 2 秒）就交还滑翔，掉高了由链路的 {@code shouldBoost}
 *       按同一个判据（速度 &lt; 0.35）再补一次。</li>
 * </ol>
 *
 * ── 五、与"仿创造飞行"（{@code MISC_FREE_FLIGHT}）的分工 ──
 * 那个是**另一个功能**：给女仆"创造模式飞行"（悬浮+自由升降，不需要鞘翅、也不是滑翔）。
 * 本类走的是**鞘翅滑翔**这条物理（原版滑翔位 + 滑翔阻力），两者互不干涉：
 * 资格物品表也各自独立（那个默认空、这个默认放自推鞘翅），可以同时开。
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
     * 所以 {@code gain = 1−t}、{@code add = d + i·t}）——照抄形状，将来对不上也能一眼看出差在哪。
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
     * 认不出具体型号时的**兜底模型**：{@code v ← v×0.5 + look×0.5}
     * （不动点 = 视线 ×1.0 格/tick）。
     *
     * <p>取值理由：原版挂载烟花是 1.7 倍视线（太快，那是"点火一冲"的量级），
     * 而自推是**持续巡航**——空域系里最慢的型号（{@code ALPHA/DEFAULT}）不动点约 0.7、
     * 最快（{@code DELTA}，d=0.3/i=1.6/t=0.5）约 2.2。取中间偏保守的 1.0：
     * 够巡航赶路、又不会一开窗就窜出去。真正在表里的型号走各自的真值，不受它影响。
     */
    public static final Params FALLBACK = new Params(0.5, 0.5, "自推鞘翅（通用）");

    /**
     * 内置参数表：物品注册名 → 这条公式的系数。**逐条来自反编译**（见类注释）。
     *
     * <p>【为什么硬编码而不是反射问对方】① 名字一改我们就崩，硬编码至少崩在编译期看不到、
     * 运行期也只是"退回兜底模型"；② 1.20.1 树不该 import 1.21.1 才有的类；
     * ③ 这些数值是"物理常量"，抄进注释里比"运行时去问"更容易核对（本模组一贯做法，
     * 见 {@code TwilightFanKit} 抄暮色森林的公式）。
     *
     * <p>【伊卡洛斯空域系的换算】源码是 {@code v + (look·d + (look·i − v)·t)}：
     * {@code gain = 1 − t}、{@code add = d + i·t}，c（{@code wings_speed_mod}，默认 1）
     * 整体乘在括号里，所以这里按 c=1 折算（玩家改了对方的配置我们这边不跟——那是"它自己的手感"，
     * 本模组的倍数旋钮是 {@code combat.selfWingsScale}）。
     * <pre>
     *   型号      d     i     t     → gain  add   不动点
     *   ALPHA   0.1   0.5   0.5       0.5   0.35    0.70
     *   BETA    0.08  1.2   0.5       0.5   0.68    1.36
     *   DELTA   0.3   1.6   0.5       0.5   1.10    2.20
     *   EPSILON 0.04  1.2   0.5       0.5   0.64    1.28
     *   ZETA    0.08  1.1   0.5       0.5   0.63    1.26
     *   THETA   0.1   0.5   0.5       0.5   0.35    0.70
     * </pre>
     */
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
        // 壮丽 = v×0.48 + look×0.64（MajesticElytra.flyingBoost 逐字）
        TABLE.put("enigmaticlegacyplus:majestic_elytra", new Params(0.48, 0.64, "神秘遗物+·壮丽鞘翅"));
        // 混沌 = v×0.5 + look×flyingSpeedModifier（默认 0.8；ChaosElytra.flyingBoost 逐字）
        TABLE.put("enigmaticlegacyplus:chaos_elytra", new Params(0.5, 0.80, "神秘遗物+·混沌之傲"));
        // ── 1.20.1 神秘遗物（EnigmaticLegacy）──
        // 【刻意留空注释】那两件的 canElytraFly 写死 instanceof Player，女仆滑不起来，
        //   这里列了也是死的（且会让"她能动"这件事看起来像我们在瞎认）。详见类注释。
        // 万一将来那个模组放开非玩家（或装了别的版本），把 id 填回来即可：
        // TABLE.put("enigmaticlegacy:enigmatic_elytra", new Params(0.5, 0.85, "神秘遗物·壮丽鞘翅"));
        // TABLE.put("enigmaticaddons:chaos_elytra",     new Params(0.5, 0.85, "神秘遗物扩展·混沌之傲"));
        // ── 原版挂载烟花那一档的参考值（**不列进来**，只是注释：它由烟花那条腿负责）──
        // new Params(0.5, 0.85)  → 不动点 1.7（原版 FireworkRocketEntity 对骑手的推力）
    }

    /**
     * 默认资格物品表（配置项的空值回退，也是手册里"默认认哪几件"的出处）。
     * 顺序与 {@link #TABLE} 一致，玩家一眼能对上。
     */
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
     * 推力窗口：UUID → 这一扇窗的状态。
     *
     * <p>【为什么需要一张表而不是"每 tick 都推"】自推鞘翅的推力是**收敛**的（见 {@link Params}），
     * 而"补推"在现有链路里本来就是**偶发**的（掉高/掉速才补，见 {@code shouldBoost}）——
     * 所以本类照同样的节奏：补推那一刻**开一扇窗**，窗内每 tick 推一下，到期自动关。
     * 这样"她一直飞着"与"她刚补了一口"在日志/诊断里可区分，也能被她被击落、收翅猛击时立刻掐掉。
     */
    private static final Map<UUID, Window> WINDOWS = new HashMap<>();

    /** 窗口时长（tick）：40 = 2 秒。与 {@code MaidRiptideBoost.MAX_THRUST_TICKS} 同量级——
     *  那一条按力度衰减决定，这一条按固定时长（这条公式没有"力度"可衰减，收敛本身就是它的尽头）。*/
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
     * <p>【为什么不按类型判】这三家分别继承 {@code ElytraItem} / {@code BaseElytraItem}，
     * 而 {@code BaseElytraItem} 并不继承 {@code ElytraItem}（它是 {@code BaseCurioItem implements Equipable}）
     * ——想按类型判就得同时认三棵树，还要 import 它们的类。按注册名（+ 标签）判是
     * {@code TwilightFanKit} 认羽扇的同一种做法：模组不在场时**恒 false、零开销**。
     */
    public static boolean isSelfPropelled(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return false;
        }
        try {
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
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

    /** 物品标签判定（{@code #命名空间:标签} 写法，与仿创造飞行的物品表同款） */
    private static boolean matchesTag(ItemStack stack, String tagId) {
        try {
            ResourceLocation loc = new ResourceLocation(tagId.trim());
            var tags = ForgeRegistries.ITEMS.tags();
            if (tags == null) {
                return false;
            }
            return stack.m_204117_(tags.createTagKey(loc));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 她**胸甲槽**里那件是不是自推鞘翅（返回它，不是就空）。
     *
     * <p>【为什么只认胸甲槽】自推的前提是"她正在滑翔"，而原版滑翔闸门
     * （{@code LivingEntity.updateFallFlying} → 胸甲槽 {@code ItemStack.canElytraFly}）
     * **只认胸甲槽那一件**——背包里有、胸甲槽没有，她根本滑不起来，推力也就无从谈起。
     * 所以资格必须与"能滑翔"同口径，否则会出现"判定说能飞、她却在往下沉"。
     */
    public static ItemStack wornWings(EntityMaid maid) {
        try {
            if (maid == null) {
                return ItemStack.f_41583_;
            }
            ItemStack chest = maid.m_6844_(EquipmentSlot.CHEST);
            return isSelfPropelled(chest) ? chest : ItemStack.f_41583_;
        } catch (Throwable ignored) {
            return ItemStack.f_41583_;
        }
    }

    /** 她此刻有没有资格靠自推鞘翅飞（开关 + 胸甲槽那件在表里） */
    public static boolean hasWings(EntityMaid maid) {
        return enabled() && !wornWings(maid).m_41619_();
    }

    /** 这一件物品对应的推力参数（表里没有 → {@link #FALLBACK}） */
    public static Params paramsOf(ItemStack stack) {
        try {
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
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
     * 【补推】开一扇推力窗（或给已开的那扇续期）。返回 true = 这一口确实开了
     * （调用方按"本次补推已发生"处理：记间隔、日志）。
     *
     * <p>与 {@code MaidRiptideBoost.ignite} 的分工：那一条**点火后立刻推第一下**（它是"一记"），
     * 这一条**不推第一下**——因为 {@link #tick} 就在同一个 tick 里稍后被调用
     * （两条飞行链里 {@code tick} 的位置紧随补推之后），推一次就够，重复推会白多一份。
     *
     * @return 这一口是否开了（没资格 / 开关关 / 不在滑翔 → false，调用方继续试下一条腿）
     */
    public static boolean ignite(EntityMaid maid) {
        try {
            if (maid == null || !enabled()) {
                return false;
            }
            ItemStack wings = wornWings(maid);
            if (wings.m_41619_()) {
                return false;
            }
            // 必须在滑翔：这条推力是"鞘翅滑翔的推进"，她收着翅时推了也白推
            //（而且原版滑翔闸门每 tick 都在校验胸甲槽，收翅状态下推一下等于凭空给速度）
            if (!MaidFlightKit.isGliding(maid)) {
                return false;
            }
            Params p = paramsOf(wings);
            WINDOWS.put(maid.m_20148_(), new Window(p, WINDOW_TICKS));
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
     *
     * <p>【与 {@code MaidRiptideBoost.tick} 的同一个纪律】只在她**滑翔**时生效——
     * 收翅猛击、落地、链路已停都不再推（那时窗口直接掐掉，交还滑翔/重力）。
     * 窗口到期（{@link #WINDOW_TICKS}）同样掐掉；掉高了由链路按"速度 &lt; 0.35"
     * 再补一口（与烟花/扇子/激流**共用同一张补推判据**）。
     */
    public static void tick(EntityMaid maid) {
        try {
            if (maid == null) {
                return;
            }
            UUID id = maid.m_20148_();
            Window w = WINDOWS.get(id);
            if (w == null) {
                return;
            }
            // 开关被关 / 资格没了（胸甲换掉）/ 不在滑翔（收翅猛击、落地）/ 到期 → 掐掉，交还滑翔
            if (!enabled() || w.left <= 0 || !MaidFlightKit.isGliding(maid) || wornWings(maid).m_41619_()) {
                WINDOWS.remove(id);
                return;
            }
            pushOnce(maid, w.params);
            w.left--;
        } catch (Throwable ignored) {
        }
    }

    /**
     * 推一下：{@code v ← v×gain + look×add}——**照抄该鞘翅自己的公式**（见 {@link Params}）。
     *
     * <p>方向永远是她这一 tick 的**视线**（与玩家按住跳跃键时同一个语义）：所以调用方
     * （空袭/飞行跟随）把视线摆在"该去的方向"之后调 {@link #tick}，这一口天然就朝那儿。
     */
    private static void pushOnce(EntityMaid maid, Params p) {
        double k = scale();
        double gain = p.gain;
        double add = p.add * k;
        Vec3 look = maid.m_20154_();
        Vec3 v = maid.m_20184_();
        maid.m_20256_(new Vec3(
                v.f_82479_ * gain + look.f_82479_ * add,
                v.f_82480_ * gain + look.f_82480_ * add,
                v.f_82481_ * gain + look.f_82481_ * add));
    }

    /** 收手：把这扇窗撤掉（返回 true = 本来还开着，供日志用）。已给的速度**不动**（交还滑翔）。 */
    public static boolean clear(EntityMaid maid) {
        try {
            return maid != null && WINDOWS.remove(maid.m_20148_()) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 这一只此刻是否挂着自推鞘翅的推力窗（只读，诊断用） */
    public static boolean isThrusting(EntityMaid maid) {
        try {
            return maid != null && WINDOWS.containsKey(maid.m_20148_());
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

    /**
     * 一行诊断：她此刻算不算"自推鞘翅能飞"、认的是哪一件、参数是多少。
     * 由缺件播报（{@code fuelDiagnostic} 那条同节流）落盘——现场"她明明穿着它却不飞"时，
     * 这一行能直接把"我们没认"与"那件东西没让她滑起来"分开。
     */
    public static String diag(EntityMaid maid) {
        try {
            if (maid == null) {
                return "自推鞘翅: 女仆为空";
            }
            ItemStack chest = maid.m_6844_(EquipmentSlot.CHEST);
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
        if (stack == null || stack.m_41619_()) {
            return "空";
        }
        String id;
        try {
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
            id = key == null ? stack.m_41720_().toString() : key.toString();
        } catch (Throwable ignored) {
            id = stack.m_41720_().toString();
        }
        return stack.m_41613_() + "x" + id;
    }

    /** 日志里的小数（两位，定点免得不同 JVM 打出科学计数法） */
    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }

    /**
     * 手册里那张"默认认哪几件、各自多快"的表 —— 手册与 changelog 共用这一份文本，
     * 免得文档与实际参数各写各的（本模组反复强调的红线）。
     */
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
