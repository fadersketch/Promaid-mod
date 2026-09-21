package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Explosion;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * v1.2.2 实测六百〇七：**模组 TNT 的那一炸也受「不破坏方块」管辖**。
 *
 * ── 需求原文 ──
 * "mod的TNT也受我们的不破坏方块管辖。"
 *
 * ── 为什么要单独开一层（六百〇五 留下的边界）──
 * 实测六百〇五 起，判据认出来的模组 TNT 是**它自己那一枚就放它自己**——她扔出去的是模组
 * 自己的 TNT 实体（等价交换的 {@code projecte:nova_catalyst_primed}，威力 16、走它自己的
 * {@code NovaExplosion}）。当时定的口径是"威力 / 破不破方块 / 带不带火**全归它自己**"，
 * 所以那一炸**照样在地上留坑**：本模组的「轰炸破坏方块」开关管不到它（我们只在
 * {@link MaidBombing} 的爆炸出口里按开关挑 {@code ExplosionInteraction}，而它那一炸
 * 根本不经过我们的出口）。现在把**"破不破方块"收回本模组的开关管**：
 * 开关关着（默认）→ 她扔的模组 TNT 也不许动地形；开着 → 照它自己那一炸拆坑。
 * **威力与带不带火仍归它自己**（本类一概不改，也不改伤害）。
 *
 * ── 拦截点为什么是 {@link ExplosionEvent.Detonate}（javap 实证，两版一致）──
 * <ul>
 *   <li>{@code Explosion} **没有**威力 / 交互模式的公开读取口（{@code explosionPower} 与
 *       {@code blockInteraction} 都是 private，全表核对过），所以"在事件里照着原威力重炸一遍"
 *       这条路走不通；</li>
 *   <li>但 Forge / Neo 都在 **{@code Explosion.explode()} 里**发布本事件（forge-client 偏移 589、
 *       neoforge-client 偏移 581），而**方块真正被拆发生在之后的 {@code finalizeExplosion}**，
 *       且它读的就是 {@code getToBlow()} 这张表；</li>
 *   <li>而 {@code Detonate.getAffectedBlocks()} **直接返回活的 {@code getToBlow()} 表**
 *       （不是副本：字节码就是 {@code invokevirtual getToBlow()} 之后 {@code areturn}）。</li>
 * </ul>
 * 所以**在事件里把这张表清空 = 这一炸不再拆任何方块**。实体伤害与击退完全不受影响
 * （伤害走另一个列表，且同样在事件之后结算）——这正是需求要的"只收走地形权限，别的一概不动"。
 *
 * 【为什么对 ProjectE 也成立】它覆盖了 {@code finalizeExplosion}（{@code NovaExplosion}），
 * 但那边顺序同样是**先 {@code explode()}（本事件在这里发）再 {@code finalizeExplosion()}**，
 * 并且在 {@code finalizeExplosion} 里才把 {@code getToBlow()} 拷一份出来拆
 * （字节码：偏移 166 取表 → {@code new ObjectArrayList<>(…)} → 逐个处理）。事件在它拷贝之前，
 * 所以清空这一手对它同样生效。
 *
 * 【为什么碰不到本模组自己的四类炸弹】我们自己那一炸走的是
 * {@code Level.explode(..., ExplosionInteraction.NONE)}——{@code interactsWithBlocks()} 恒假，
 * {@code finalizeExplosion} 里拆方块那一整段根本不执行，本来就一个方块都不拆。这层对它是空转。
 *
 * ── 怎么认出"这是她扔的那一枚" ──
 * 两条取并集，**只认 TNT**（绝不对别人的爆炸动手）：
 * <ol>
 *   <li>**主人链**（主路径、无状态）：直系来源是 {@link PrimedTnt}、而间系来源（= 点火者）
 *       是女仆。原版/加载器自己就是这么把 TNT 归给主人的（{@code PrimedTnt} 是
 *       {@code TraceableEntity}；我们 {@code primeTnt} 点火时传的 igniter 就是她；
 *       ProjectE 的 {@code EntityNovaCatalystPrimed(level,x,y,z,igniter)} 同样把 owner 收下了）；</li>
 *   <li>**我们亲手登记过的那一枚**（兜底）：{@link #rememberTnt} 由 {@code MaidBombing.primeTnt}
 *       在我们放出"模组自己的 TNT 实体"时登记（连带是哪位女仆扔的）。个别模组不记 owner 时
 *       靠这条兜住——**我们自己放出去的一定认得出**。</li>
 * </ol>
 *
 * ── 边界（写在这里，免得以后误以为它管得更宽）──
 * · **地形这一半**归本类（清 {@code getToBlow()}）；**伤害那一半**：六百〇七 当时写的是
 *   "那一炸的伤害源是那枚 TNT 实体、不是女仆，所以主人/友军免伤覆盖不到它"——六百一十
 *   实机更正：那颗 TNT 的**造成者就是她**（原版拿引信 TNT 记的点火者当造成者，javap 实证：
 *   {@code DamageSources.explosion} 的造成者参数就是 {@code Explosion.getIndirectSourceEntity()}
 *   = {@code PrimedTnt.getOwner()}），所以主人/友军本来就被 {@link FriendlyFireGuard} 护住了。
 *   本类真正的用处是**模组没把点火者写进伤害源**的那一类：{@link #maidOfTnt} 按这张登记表
 *   把账算回她头上，让"她扔的模组 TNT 不伤主人/友军"这个承诺不依赖第三方怎么写。
 * · 若某个模组的爆炸**不走 {@code getToBlow()}**、而是自己另开一套循环拆方块，这层拦不住。
 */
@Mod.EventBusSubscriber(modid = "promaid")
public final class MaidTntBlastGuard {

    /** 一枚"我们放出去的模组 TNT"：是谁扔的 + 登记到什么时候 */
    private static final class Tracked {
        final EntityMaid maid;
        final long expireAt;

        Tracked(EntityMaid maid, long expireAt) {
            this.maid = maid;
            this.expireAt = expireAt;
        }
    }

    /**
     * 实体 id → 那一枚的登记。
     *
     * 【为什么带过期时间】这一枚会在引信到点时自己消失，我们没有可靠的"它炸了"回调；
     * 一张只增不减的表迟早攒出一堆死 id（而实体 id 会被复用，攒多了还可能误伤同 id 的新实体）。
     * 引信上限 200 tick（面板「投掷 TNT 的引信」的最大值），所以过期时间给到 400 tick——
     * 足够覆盖"扔出去 → 引信烧完 → 炸"的全程，又远短于 id 复用的时间尺度。
     */
    private static final Map<Integer, Tracked> TRACKED = new HashMap<>();
    /** 登记余量（tick）：见 {@link #TRACKED} 的注释 */
    public static final long TRACK_TTL = 400L;
    /** 惰性清理的节流（tick）与上次清理时刻 */
    private static final long SWEEP_INTERVAL = 100L;
    private static long lastSweep = Long.MIN_VALUE;
    /** 日志限频：同一只女仆至多 5 秒一条（这一炸是常态，不节流会把日志刷满） */
    private static final Map<String, Long> LOG_AT = new HashMap<>();
    private static final long LOG_INTERVAL = 100L;

    private MaidTntBlastGuard() {
    }

    /**
     * 记下"这一枚是我们放出去的模组 TNT"——由 {@code MaidBombing.primeTnt} 在放出
     * 模组自己的 TNT 实体时调用。
     *
     * @param tnt      刚放进世界的那一枚（模组自己的 TNT 实体，不是原版引信 TNT）
     * @param maid     扔它的女仆
     * @param expireAt 过期游戏刻（调用方给 {@code level.getGameTime() + TRACK_TTL}）
     */
    public static void rememberTnt(Entity tnt, EntityMaid maid, long expireAt) {
        if (tnt == null) {
            return;
        }
        try {
            TRACKED.put(tnt.m_19879_(), new Tracked(maid, expireAt));
        } catch (Throwable ignored) {
        }
    }

    /** 服务器停止 / 重载时清干净（与其它守卫的 clearAll 同口径） */
    public static void clearAll() {
        TRACKED.clear();
        LOG_AT.clear();
        lastSweep = Long.MIN_VALUE;
    }

    /**
     * 起爆前的最后一道闸：是她扔的模组 TNT、且「轰炸破坏方块」关着 → 把这一炸要拆的方块表清空。
     *
     * 实体伤害与击退一概不动（见类注释）；开关开着就直接放行（照它自己那一炸拆坑）。
     */
    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        try {
            if (event == null || event.getExplosion() == null) {
                return;
            }
            // 开关开着 = 交回给它自己（这正是"受我们的开关管辖"的另一半）
            if (MaidSmartConfig.COMBAT_BOMBING_BREAK_BLOCKS.get()) {
                return;
            }
            List<BlockPos> blocks = event.getAffectedBlocks();
            if (blocks == null || blocks.isEmpty()) {
                return;
            }
            Explosion ex = event.getExplosion();
            EntityMaid maid = ownerMaidOf(ex);
            if (maid == null) {
                return; // 不是她扔的 TNT：一概不碰
            }
            int n = blocks.size();
            blocks.clear(); // 活表（= getToBlow()）：清掉即"这一炸不拆方块"，伤害照旧
            log(maid, n, ex);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 这一炸是不是"她扔的模组 TNT"——是就返回那位女仆，否则 null。
     * 两条判据见类注释；两条都要求直系来源是**引信 TNT 那一类**（不碰别的爆炸来源）。
     */
    private static EntityMaid ownerMaidOf(Explosion ex) {
        try {
            Entity direct = ex.m_253049_();    // getDirectSourceEntity
            if (!(direct instanceof PrimedTnt)) {
                return null; // 只认 TNT（她扔的模组 TNT 也继承 PrimedTnt）
            }
            // ① 主人链（点火者就是女仆）+ ② 我们亲手登记过的那一枚
            EntityMaid mine = maidOfTnt(direct);
            if (mine != null) {
                return mine;
            }
            // 直系是 TNT 但两条都不中：再看一眼间系（个别模组把点火者放在别处）
            Entity indirect = ex.m_252906_();  // getIndirectSourceEntity
            return indirect instanceof EntityMaid m ? m : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * v1.2.2 实测六百一十：**这一枚 TNT 是不是她放的** —— 是就返回那位女仆，否则 null。
     *
     * 由 {@link #ownerMaidOf} 与主人/友军免伤（{@code FriendlyFireGuard.maidOfDamage}）共用：
     * 爆炸事件里我们拿得到 {@code Explosion}，而伤害事件里只有一枚**直接实体**，
     * 两边都要能把账算回她头上，所以这条判据必须是公开的、与 {@link #tracked} 的表同一份。
     *
     * 判据与类注释那两条一致（只认 {@link PrimedTnt} 这一类）：
     * <ol>
     *   <li><b>主人链</b>：引信 TNT 自己记的点火者（原版 {@code PrimedTnt.getOwner()}，
     *       {@code Explosion.getIndirectSourceEntity()} 读的就是它）；</li>
     *   <li><b>我们亲手登记过的那一枚</b>：{@code MaidBombing.primeTnt} 放出模组自己的 TNT 时登记，
     *       防的是"模组不把点火者写进爆炸/伤害源"那一类（这时前一条整段落空）。</li>
     * </ol>
     */
    public static EntityMaid maidOfTnt(Entity tnt) {
        if (!(tnt instanceof PrimedTnt)) {
            return null;
        }
        try {
            net.minecraft.world.entity.LivingEntity owner = ((PrimedTnt) tnt).m_19749_(); // getOwner
            if (owner instanceof EntityMaid m) {
                return m;
            }
        } catch (Throwable ignored) {
        }
        Tracked t = tracked(tnt);
        return t == null ? null : t.maid;
    }

    /** 这一枚是否还在"我们亲手放出去的模组 TNT"表里（顺手做过期清理） */
    private static Tracked tracked(Entity e) {
        try {
            long now = e.m_9236_() == null ? 0L : e.m_9236_().m_46467_();
            if (now - lastSweep >= SWEEP_INTERVAL) {
                lastSweep = now;
                Iterator<Map.Entry<Integer, Tracked>> it = TRACKED.entrySet().iterator();
                while (it.hasNext()) {
                    if (it.next().getValue().expireAt < now) {
                        it.remove();
                    }
                }
            }
            Tracked t = TRACKED.get(e.m_19879_());
            return (t != null && t.expireAt >= now) ? t : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 一条限频日志：说清"这一炸按不破坏方块处理了、别的一概没动"，并带上归因现场 */
    private static void log(EntityMaid maid, int n, Explosion ex) {
        try {
            long now = maid.m_9236_() == null ? 0L : maid.m_9236_().m_46467_();
            String who = com.maidsmart.tool.PromaidLog.nameOf(maid);
            Long last = LOG_AT.get(who);
            if (last != null && now - last < LOG_INTERVAL) {
                return;
            }
            LOG_AT.put(who, now);
            com.maidsmart.tool.PromaidLog.log("空袭轰炸", who
                    + " 模组 TNT 那一炸按「不破坏方块」处理（清掉 " + n + " 个方块；"
                    + "威力与伤害照它自己的）；" + attribution(ex));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 这一炸的**归因现场**（给日志用）：她到底认在哪一条上、伤害源又是怎么构造的。
     *
     * 为什么要有这一行：六百一十 定"主人/友军免伤"这一半时，`伤害源里谁是造成者` 决定了
     * {:@link FriendlyFireGuard} 能不能接住这一炸——而那件事**只能靠实机读出来**
     * （爆炸是模组自己 new 的，直系/间系/伤害源三者未必一致）。第一次跑实测就是靠这一行
     * 才看清：伤害源的造成者是那枚 TNT、不是她，所以旧的"造成者是女仆才免伤"接不住。
     */
    private static String attribution(Explosion ex) {
        try {
            net.minecraft.world.damagesource.DamageSource ds = ex.m_46077_();
            Entity cause = ds == null ? null : ds.m_7639_();   // getEntity（造成者）
            Entity direct = ds == null ? null : ds.m_7640_();  // getDirectEntity（直接实体）
            return "归因[直系=" + who(ex.m_253049_()) + " 间系=" + who(ex.m_252906_())
                    + " 伤害源{造成者=" + who(cause) + " 直接=" + who(direct)
                    + " 类型=" + (ds == null ? "无" : String.valueOf(ds.m_19385_())) + "}"
                    + " 我方登记=" + (tracked(ex.m_253049_()) != null) + "]";
        } catch (Throwable ignored) {
            return "归因[读不到]";
        }
    }

    /** 日志里那个实体叫什么（女仆点名，其它给类名；空给"空"） */
    private static String who(Entity e) {
        try {
            if (e == null) {
                return "空";
            }
            if (e instanceof EntityMaid m) {
                return "女仆(" + com.maidsmart.tool.PromaidLog.nameOf(m) + ")";
            }
            return e.getClass().getSimpleName();
        } catch (Throwable ignored) {
            return "?";
        }
    }
}
