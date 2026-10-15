package com.maidsmart.affect;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.AbstractMaidContext;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;

/**
 * PAD 情绪层（借鉴 maidsoulcore AffectEngine 简化版）——独立于 TLM 好感等既有系统的
 * 【第四套数值】，只做"情绪状态 + 注入文本"，不改任何现有系统数值。
 *
 * 维度（对齐 maidsoul AffectProfile 精简）：PAD 愉悦/唤醒/支配；关系 亲密/冲突/思念；修复债务
 * hurtDebt 受伤债 / repairDebt 修复债（被主人打后需互动/时间偿还，决定原谅的节奏）。
 * 事件驱动（每类事件 30 秒最多计一次，实测九百七十）：主人对话/喂食/夸奖 → 愉悦+；被主人打 →
 * 愉悦-/支配-/冲突+/受伤债+/修复债+；其他玩家打 → 较轻一档且不记修复债；**非玩家来源的伤害只动
 * 情绪面（愉悦/支配/冲突/受伤债），不动亲密**——亲密只由"主人的行为"与时间推移决定；
 * 静默恢复 → 情绪自然回落（思念+/两种债缓降）。
 * 注入：作为 {@code ai_affect} 上下文注册（对话附带情绪快照，LLM 据此调整语气）。
 * 落盘：{@code <存档>/promaid_memory/<uuid>/affect.json}（独立文件，零依赖）。
 */
public final class AffectManager {
    public static final String AFFECT_FILE = "affect.json";

    /** 静默恢复的"安静期"：这么久没有受伤 / 受委屈，情绪才开始往基线回落（实测九百六十九）。 */
    private static final long RECOVER_QUIET_MS = 10 * 60 * 1000L;

    /** 代码触发的情绪事件最小间隔（实测九百七十，开发者口径）：**同一类事件 30 秒内只计一次**——
     *  高频伤害（每拍重复的环境伤害、连打、连点）不会把她一次性扣死，也不会靠连点把数值刷满；
     *  按事件类别分别计时，挨打与喂食互不挤占。只存内存（重启清零，无副作用）。 */
    private static final long MIN_EVENT_INTERVAL_MS = 30 * 1000L;
    private static final java.util.Map<java.util.UUID, long[]> LAST_EVENT =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int K_HURT_OWNER = 0;
    private static final int K_HURT_STRANGER = 1;
    private static final int K_HURT_WORLD = 2;
    private static final int K_FEED = 3;
    private static final int K_PRAISE = 4;
    private static final int K_MESSAGE = 5;
    private static final int K_APOLOGY = 6;
    private static final int K_QUESTION = 7;

    /** 这一类事件距上次够 30 秒了吗？够 → 记下此刻放行；不够 → 整次丢弃（一个数值都不改）。 */
    private static boolean allow(EntityMaid maid, int kind) {
        long now = System.currentTimeMillis();
        long[] arr = LAST_EVENT.computeIfAbsent(maid.m_20148_(), k -> new long[8]);
        if (now - arr[kind] < MIN_EVENT_INTERVAL_MS) {
            return false;
        }
        arr[kind] = now;
        return true;
    }

    private AffectManager() {
    }

    /** 情绪快照（Gson 可序列化） */
    public static final class AffectProfile {
        public double valence = 0.3;
        public double arousal = 0.4;
        public double dominance = 0.5;
        public double intimacy = 0.35;
        public double conflict = 0.05;
        public double longing = 0.35;
        public double hurtDebt = 0.0;
        public double repairDebt = 0.0;
        public long lastUpdate = 0L;
        /** 实测九百六十九：上一次"受伤 / 受委屈"的时刻——静默恢复只认它（老档没有此字段 = 0 = 早已过去）。
         *  旧版拿 lastUpdate 当恢复闸，喂食 / 聊天也把它刷新 → 越关心她，愉悦越回不来。 */
        public long lastHurtAt = 0L;
    }

    /** 读取（无文件返回默认快照） */
    public static AffectProfile load(EntityMaid maid) {
        try {
            java.nio.file.Path dir = com.maidsmart.memory.AiMemoryExtractor.memoryRoot(
                    ((net.minecraft.server.MinecraftServer) ((ServerLevel) maid.m_9236_()).m_7654_()))
                    .resolve(maid.m_20148_().toString());
            java.nio.file.Path f = dir.resolve(AFFECT_FILE);
            if (java.nio.file.Files.exists(f)) {
                return com.maidsmart.memory.AiMemoryModels.GSON.fromJson(
                        java.nio.file.Files.readString(f), AffectProfile.class);
            }
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("AffectManager", ignored);
        }
        return new AffectProfile();
    }

    private static void save(EntityMaid maid, AffectProfile p) {
        try {
            if (!(maid.m_9236_() instanceof ServerLevel level)) {
                return;
            }
            java.nio.file.Path dir = com.maidsmart.memory.AiMemoryExtractor.memoryRoot(level.m_7654_())
                    .resolve(maid.m_20148_().toString());
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve(AFFECT_FILE),
                    com.maidsmart.memory.AiMemoryModels.GSON.toJson(p));
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("AffectManager", ignored);
        }
    }

    // ---------- 事件驱动 ----------

    /** 主人对话（intimacy+ / conflict- / arousal 略升；实测九百六十九：愉悦也微升——陪她说句话有回馈） */
    public static void onOwnerMessage(EntityMaid maid) {
        if (!allow(maid, K_MESSAGE)) {
            return;
        }
        AffectProfile p = load(maid);
        p.valence = clampSigned(p.valence + 0.01);
        p.intimacy = clamp01(p.intimacy + 0.012);
        p.conflict = clamp01(p.conflict - 0.008);
        p.arousal = clamp01(p.arousal + 0.015);
        p.repairDebt = clamp01(p.repairDebt - 0.02);
        p.lastUpdate = System.currentTimeMillis();
        save(maid, p);
    }

    /** 主人喂食（实测九百六十九新增）：手里拿着食物右键她 = 喂饭 → 愉悦升。
     *  手册一直承诺"喂她、夸她会让她变开心"，而旧实现里这两件事**完全不碰愉悦**（只加亲密 / 唤醒），
     *  于是玩家顿顿金苹果也看不到愉悦动一下——这一条是补齐手册口径。 */
    public static void onOwnerFeed(EntityMaid maid) {
        if (!allow(maid, K_FEED)) {
            return;
        }
        AffectProfile p = load(maid);
        p.valence = clampSigned(p.valence + 0.06);
        p.intimacy = clamp01(p.intimacy + 0.02);
        p.arousal = clamp01(p.arousal + 0.01);
        p.dominance = clamp01(p.dominance + 0.01);
        p.conflict = clamp01(p.conflict - 0.01);
        p.repairDebt = clamp01(p.repairDebt - 0.03);
        p.lastUpdate = System.currentTimeMillis();
        save(maid, p);
    }

    /** 主人夸奖（实测九百六十九新增）：对话里夸她 → 愉悦 / 支配升（同"喂她"的手册口径）。 */
    public static void onOwnerPraise(EntityMaid maid) {
        if (!allow(maid, K_PRAISE)) {
            return;
        }
        AffectProfile p = load(maid);
        p.valence = clampSigned(p.valence + 0.08);
        p.intimacy = clamp01(p.intimacy + 0.03);
        p.dominance = clamp01(p.dominance + 0.02);
        p.conflict = clamp01(p.conflict - 0.01);
        p.hurtDebt = clamp01(p.hurtDebt - 0.02);
        p.repairDebt = clamp01(p.repairDebt - 0.04);
        p.lastUpdate = System.currentTimeMillis();
        save(maid, p);
    }

    /** 主人打女仆（valence- / dominance- / conflict+ / hurtDebt+ / **repairDebt+** / intimacy-）。
     *  实测九百六十九两处改动：① 支配也降——手册说"打她一下，支配值变化"，旧实现从不碰支配；
     *  ② 只有主人打的才记"修复债"（= 你欠她的），世界给的伤不算在你头上。 */
    public static void onHurtByOwner(EntityMaid maid) {
        if (!allow(maid, K_HURT_OWNER)) {
            return;
        }
        AffectProfile p = load(maid);
        p.valence = clampSigned(p.valence - 0.25);
        p.dominance = clamp01(p.dominance - 0.15);
        p.conflict = clamp01(p.conflict + 0.25);
        p.hurtDebt = clamp01(p.hurtDebt + 0.25);
        p.repairDebt = clamp01(p.repairDebt + 0.2);
        p.intimacy = clamp01(p.intimacy - 0.12);
        p.arousal = clamp01(p.arousal + 0.2);
        touchHurt(p);
        save(maid, p);
    }

    /** 别的玩家打她（实测九百六十九新增）：不是主人干的，按"外人"档算——比主人轻，且不记修复债。 */
    public static void onHurtByStranger(EntityMaid maid) {
        if (!allow(maid, K_HURT_STRANGER)) {
            return;
        }
        AffectProfile p = load(maid);
        p.valence = clampSigned(p.valence - 0.1);
        p.dominance = clamp01(p.dominance - 0.05);
        p.conflict = clamp01(p.conflict + 0.12);
        p.hurtDebt = clamp01(p.hurtDebt + 0.12);
        p.arousal = clamp01(p.arousal + 0.1);
        touchHurt(p);
        save(maid, p);
    }

    /** 女仆被世界伤害（怪物 / 摔落 / 火 / 挤压…：conflict+ / hurtDebt+ 轻量，支配略降）。
     *  实测九百六十九：这一档**不算"你欠她的"**（repairDebt 不动）——旧版两种伤混在一起，玩家明明
     *  没动手也会看到"内心仍有芥蒂"，还会引出"慢慢把以前的事放下"这种无中生有的话。 */
    public static void onHurtByWorld(EntityMaid maid) {
        if (!allow(maid, K_HURT_WORLD)) {
            return;
        }
        AffectProfile p = load(maid);
        p.valence = clampSigned(p.valence - 0.06);
        p.dominance = clamp01(p.dominance - 0.03);
        p.conflict = clamp01(p.conflict + 0.08);
        p.hurtDebt = clamp01(p.hurtDebt + 0.1);
        touchHurt(p);
        save(maid, p);
    }

    /** 记下"刚受了委屈"的时刻——静默恢复以它为闸（实测九百六十九）。 */
    private static void touchHurt(AffectProfile p) {
        p.lastHurtAt = System.currentTimeMillis();
        p.lastUpdate = p.lastHurtAt;
    }

    /**
     * v1.5.191：主人道歉（对不起/抱歉/我错了…）——安抚情绪、还修复债。
     * 实测九百六十九调参：道歉是**修复债的主要偿还手段**（-0.25），顺带愉悦 +0.08、冲突 -0.15、
     * 受伤债 -0.12、支配 +0.01（抬手认错，她重新有了底气）。
     */
    public static void onOwnerApology(EntityMaid maid) {
        if (!allow(maid, K_APOLOGY)) {
            return;
        }
        AffectProfile p = load(maid);
        p.valence = clampSigned(p.valence + 0.08);
        p.dominance = clamp01(p.dominance + 0.01);
        p.conflict = clamp01(p.conflict - 0.15);
        p.hurtDebt = clamp01(p.hurtDebt - 0.12);
        p.repairDebt = clamp01(p.repairDebt - 0.25);
        p.intimacy = clamp01(p.intimacy + 0.04);
        p.lastUpdate = System.currentTimeMillis();
        save(maid, p);
    }

    /** v1.5.191：主人提问——好奇/被关注（arousal+ / longing+） */
    public static void onOwnerQuestion(EntityMaid maid) {
        if (!allow(maid, K_QUESTION)) {
            return;
        }
        AffectProfile p = load(maid);
        p.arousal = clamp01(p.arousal + 0.03);
        p.longing = clamp01(p.longing + 0.03);
        p.lastUpdate = System.currentTimeMillis();
        save(maid, p);
    }



    /** 静默恢复（时间推移：情绪回落 / longing 微升 / 两种"债"缓降）。
     *  实测九百六十九：闸门从 lastUpdate 换成 lastHurtAt——**只有受伤 / 受委屈才顺延**；喂食、夸奖、
     *  聊天不再阻止恢复（旧版任何互动都刷新 lastUpdate，玩家越关心她、愉悦越回不来）。 */
    public static void tickRecover(EntityMaid maid) {
        AffectProfile p = load(maid);
        long now = System.currentTimeMillis();
        if (now - p.lastHurtAt < RECOVER_QUIET_MS) {
            return; // 这段时间里挨过打 / 受过委屈 → 先不安抚（安静下来才慢慢回落）
        }
        p.lastHurtAt = now;
        p.lastUpdate = now;
        p.valence = approach(p.valence, 0.3, 0.05);
        p.arousal = approach(p.arousal, 0.35, 0.03);
        p.dominance = approach(p.dominance, 0.5, 0.03);
        p.conflict = approach(p.conflict, 0.05, 0.03);
        p.hurtDebt = approach(p.hurtDebt, 0.0, 0.03);
        p.repairDebt = approach(p.repairDebt, 0.0, 0.03);
        p.longing = clamp01(p.longing + 0.02);
        save(maid, p);
    }

    /** 生成注入文本（ai_affect 上下文用） */
    public static String render(EntityMaid maid) {
        AffectProfile p = load(maid);
        StringBuilder sb = new StringBuilder();
        sb.append("情绪:愉悦").append(signedPercent(p.valence))
                .append(" 唤醒").append(percent(p.arousal))
                .append(" 支配").append(percent(p.dominance))
                .append("; 关系:亲密").append(percent(p.intimacy))
                .append(" 冲突").append(percent(p.conflict))
                .append(" 思念").append(percent(p.longing));
        // 实测九百六十九：两种"委屈"分开说——① 你伤过她（修复债）才是"你欠她的"；② 世界给的伤只报
        // "她最近受了委屈"，不再让模型把责任揽到主人头上（旧版两种混着说，才有"慢慢把以前的事放下"
        // 这种无中生有的话），也修掉"受伤 98% / 待修复 0%"并列出现时的误读。
        if (p.repairDebt > 0.2) {
            sb.append("; 你欠她一次道歉（待修复").append(percent(p.repairDebt))
                    .append("%）：她记得被主人伤到的那次，需要你哄，别装作无事发生");
        } else if (p.hurtDebt > 0.3) {
            sb.append("; 她最近受了委屈（受伤").append(percent(p.hurtDebt))
                    .append("%）——不是主人造成的，别把责任算到主人头上，语气护着她一点");
        }
        // v1.1.0：表达建议（规则式，借鉴 maidsoulcore replyStyleAdvice）——
        // 情绪状态 → 具体"该怎么说话"，让 LLM 不只是看到数值
        String advice = styleAdvice(p);
        if (!advice.isEmpty()) {
            sb.append("；表达建议：").append(advice);
        }
        return sb.toString();
    }

    /**
     * v1.1.0：情绪 → 回复风格建议（规则式，零 LLM）。
     * 优先级：受伤/修复债 > 亲密+思念高 > 心情低落 > 疏离期。
     */
    public static String styleAdvice(AffectProfile p) {
        if (p == null) {
            return "";
        }
        if (p.repairDebt > 0.2) {
            return "可以委屈、可以想要一句道歉，但不许翻旧账、也不许把世界给的委屈算到主人头上";
        }
        if (p.hurtDebt > 0.3) {
            return "可以提一句最近受的委屈（被打/摔伤之类），但别暗示主人伤害过她";
        }
        if (p.intimacy > 0.6 && p.longing > 0.5 && p.conflict < 0.1) {
            return "可以更温柔、更喜欢主人、更粘一点，但不要无视当前事实";
        }
        if (p.valence < 0.25) {
            return "心情偏低，回复要短一些、软一些，避免跳到无关话题";
        }
        if (p.conflict < 0.1 && p.intimacy < 0.4) {
            return "保持礼貌和适度距离，先建立熟悉感";
        }
        return "";
    }

    /** 愉悦是 -1~1 的双向量：负值也照样显示（旧版把负数一律显示成 0%，玩家以为"归零了"）。 */
    private static String signedPercent(double v) {
        return String.format(java.util.Locale.ROOT, "%.0f%%", Math.max(-1.0, Math.min(1.0, v)) * 100.0);
    }

    private static String percent(double v) {
        return String.format(java.util.Locale.ROOT, "%.0f%%", Math.max(0.0, Math.min(1.0, v)) * 100.0);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double clampSigned(double v) {
        return Math.max(-1.0, Math.min(1.0, v));
    }

    private static double approach(double v, double target, double step) {
        if (v < target) {
            return Math.min(target, v + step);
        }
        if (v > target) {
            return Math.max(target, v - step);
        }
        return v;
    }

    /** 注入上下文（注册为 ai_affect 类别） */
    public static final class AffectContext extends AbstractMaidContext {
        public AffectContext() {
            super("ai_affect", "AI Affect");
        }

        @Override
        public String getValue(EntityMaid maid) {
            if (!com.maidsmart.memory.AiMemoryManager.isEnabled(maid)) {
                return "";
            }
            // v1.5.96：情绪注入开关（配置面板 affect.inject）
            if (!com.maidsmart.config.MaidSmartConfig.AFFECT_INJECT.get()) {
                return "";
            }
            if (!(maid.m_9236_() instanceof ServerLevel)) {
                return "";
            }
            return AffectManager.render(maid);
        }
    }
}
