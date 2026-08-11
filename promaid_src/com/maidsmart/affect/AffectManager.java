package com.maidsmart.affect;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.AbstractMaidContext;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;

/**
 * PAD 情绪层（v1.5.95，借鉴 maidsoulcore AffectEngine 简化版）。
 *
 * 独立于 TLM 好感 / 心契誓约心情 / 爱憎分明信任恐惧的【第四套数值】——
 * 只做"情绪状态 + 注入文本"，不改任何现有系统数值（兼容心契誓约）。
 *
 * 维度（对齐 maidsoul AffectProfile 精简）：
 * - PAD：valence 愉悦(-1~1) / arousal 唤醒(0~1) / dominance 支配(0~1)
 * - 关系：intimacy 亲密 / conflict 冲突 / longing 思念
 * - 修复债务：hurtDebt 受伤债 / repairDebt 修复债（被主人打了之后，需要
 *   互动/时间偿还——"修复债务"概念，决定原谅的节奏）
 *
 * 事件驱动（对齐 AffectEngine.applyEventImpulse）：
 * - 主人对话 → intimacy+ / conflict-
 * - 主人打女仆 → valence- / conflict+ / hurtDebt+ / intimacy-
 * - 女仆被世界伤害 → conflict+ / hurtDebt+
 * - 静默恢复 → 情绪自然回落（longing+ / repairDebt-）
 *
 * 注入：作为 ai_affect 上下文注册（对话 <context> 附带情绪快照，LLM 据此调整语气）。
 * 落盘：<世界存档>/promaid_memory/<uuid>/affect.json（独立文件，零依赖）。
 */
public final class AffectManager {
    public static final String AFFECT_FILE = "affect.json";

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
        } catch (Exception ignored) {
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
        } catch (Exception ignored) {
        }
    }

    // ---------- 事件驱动 ----------

    /** 主人对话（intimacy+ / conflict- / arousal 略升） */
    public static void onOwnerMessage(EntityMaid maid) {
        AffectProfile p = load(maid);
        p.intimacy = clamp01(p.intimacy + 0.012);
        p.conflict = clamp01(p.conflict - 0.008);
        p.arousal = clamp01(p.arousal + 0.015);
        p.repairDebt = clamp01(p.repairDebt - 0.02);
        p.lastUpdate = System.currentTimeMillis();
        save(maid, p);
    }

    /** 主人打女仆（valence- / conflict+ / hurtDebt+ / intimacy-） */
    public static void onHurtByOwner(EntityMaid maid) {
        AffectProfile p = load(maid);
        p.valence = clampSigned(p.valence - 0.2);
        p.conflict = clamp01(p.conflict + 0.24);
        p.hurtDebt = clamp01(p.hurtDebt + 0.25);
        p.intimacy = clamp01(p.intimacy - 0.12);
        p.arousal = clamp01(p.arousal + 0.2);
        p.lastUpdate = System.currentTimeMillis();
        save(maid, p);
    }

    /** 女仆被世界伤害（conflict+ / hurtDebt+ 轻量） */
    public static void onHurtByWorld(EntityMaid maid) {
        AffectProfile p = load(maid);
        p.valence = clampSigned(p.valence - 0.06);
        p.conflict = clamp01(p.conflict + 0.08);
        p.hurtDebt = clamp01(p.hurtDebt + 0.1);
        p.lastUpdate = System.currentTimeMillis();
        save(maid, p);
    }

    /**
     * v1.5.191：主人道歉（对不起/抱歉/我错了…）——安抚情绪、消修复债。
     * 对齐 maidsoulcore OWNER_APOLOGY（比例降 1/2~1/3）：
     * valence+0.06 / conflict-0.12 / hurtDebt-0.10 / repairDebt-0.14 / intimacy+0.05。
     */
    public static void onOwnerApology(EntityMaid maid) {
        AffectProfile p = load(maid);
        p.valence = clampSigned(p.valence + 0.06);
        p.conflict = clamp01(p.conflict - 0.12);
        p.hurtDebt = clamp01(p.hurtDebt - 0.10);
        p.repairDebt = clamp01(p.repairDebt - 0.14);
        p.intimacy = clamp01(p.intimacy + 0.05);
        p.lastUpdate = System.currentTimeMillis();
        save(maid, p);
    }

    /** v1.5.191：主人提问——好奇/被关注（arousal+ / longing+） */
    public static void onOwnerQuestion(EntityMaid maid) {
        AffectProfile p = load(maid);
        p.arousal = clamp01(p.arousal + 0.03);
        p.longing = clamp01(p.longing + 0.03);
        p.lastUpdate = System.currentTimeMillis();
        save(maid, p);
    }

    /** 静默恢复（时间推移：情绪回落 / longing 微升 / repairDebt 缓降） */
    public static void tickRecover(EntityMaid maid) {
        AffectProfile p = load(maid);
        long now = System.currentTimeMillis();
        if (now - p.lastUpdate < 10 * 60 * 1000L) {
            return; // 10 分钟内不重复恢复
        }
        p.lastUpdate = now;
        p.valence = approach(p.valence, 0.3, 0.04);
        p.arousal = approach(p.arousal, 0.35, 0.03);
        p.conflict = approach(p.conflict, 0.05, 0.02);
        p.hurtDebt = approach(p.hurtDebt, 0.0, 0.02);
        p.repairDebt = approach(p.repairDebt, 0.0, 0.015);
        p.longing = clamp01(p.longing + 0.02);
        save(maid, p);
    }

    /** 生成注入文本（ai_affect 上下文用） */
    public static String render(EntityMaid maid) {
        AffectProfile p = load(maid);
        StringBuilder sb = new StringBuilder();
        sb.append("情绪:愉悦").append(percent(p.valence))
                .append(" 唤醒").append(percent(p.arousal))
                .append(" 支配").append(percent(p.dominance))
                .append("; 关系:亲密").append(percent(p.intimacy))
                .append(" 冲突").append(percent(p.conflict))
                .append(" 思念").append(percent(p.longing));
        if (p.hurtDebt > 0.3 || p.repairDebt > 0.3) {
            sb.append("; 内心仍有芥蒂（受伤").append(percent(p.hurtDebt))
                    .append(" 待修复").append(percent(p.repairDebt))
                    .append("），但已在慢慢释怀，不要装作无事发生");
        }
        return sb.toString();
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
