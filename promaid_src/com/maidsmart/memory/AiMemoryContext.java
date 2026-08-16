package com.maidsmart.memory;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.AbstractMaidContext;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * AI 记忆上下文（v1.5.86：把记忆投影注入 AI 对话的 <context>。
 * 对齐 maidsoulcore 的 MemoryContextProvider：prompt 只放短投影
 * （相关段落 top N + 今日回顾 + 主人画像），详细记忆由 query_memory 工具按需检索。
 * 开关关闭 / 无记忆时返回空串（主模组空白过滤自动丢弃）。
 *
 * v1.5.95 增强：
 * - 关系三元组接入投影（"关系：主人喜欢红茶（置信度0.8）"）——旧版存了 relations.jsonl
 *   但从未注入/检索
 * - FACT 置信度排序：画像段按置信度降序（高置信度先显示）
 * - 摘要折叠：核心记忆（salience≥8 或永久）常驻；扩展记忆（回顾/画像/关系）按需，
 *   总长超限时优先保留核心 + 截断提示（不再硬截断丢核心）
 */
public class AiMemoryContext extends AbstractMaidContext {
    /** v1.5.102：注入总长上限从配置面板读取（memory.projectionChars） */

    public AiMemoryContext() {
        super("ai_memory", "AI Memory");
    }

    @Override
    public String getValue(EntityMaid maid) {
        if (!AiMemoryManager.isEnabled(maid)) {
            return "";
        }
        if (!(maid.m_9236_() instanceof ServerLevel level)) {
            return "";
        }
        AiMemoryStore store = com.maidsmart.soul.SoulBindingService.storeFor(maid, level);
        StringBuilder sb = new StringBuilder();
        // v1.1.0：人格种子 + 核心记忆（只读投影，优先级最高——放在最前，
        // 尾部截断永不伤及；即使暂时没有对话记忆也注入人格，让女仆"有人设"）
        if (com.maidsmart.config.MaidSmartConfig.MEMORY_PERSONA.get()) {
            com.maidsmart.persona.PersonaPackage.ensureDefault(store.dir());
            // v1.2.1：人设统一——TLM 已有人设时人格块降级为补充（只补人格参数/核心记忆，
            // 不重复身份），避免双人设并存冲突（TLM 模板 Data Overridden 规则让
            // <context> 优先于 system，双人设行为不可预期）
            boolean supplement = com.maidsmart.config.MaidSmartConfig.MEMORY_PERSONA_UNIFY.get()
                    && tlmHasPersona(maid);
            String persona = com.maidsmart.persona.PersonaPackage.renderPromptBlock(store.dir(), 2, 260, supplement);
            if (!persona.isBlank()) {
                sb.append(persona);
            }
        }
        List<AiMemoryModels.Paragraph> paragraphs = store.paragraphs();
        if (paragraphs.isEmpty() && store.relations().isEmpty() && store.profiles().isEmpty() && sb.isEmpty()) {
            return "";
        }
        // 0. 关系感知标签（v1.5.98：maidmarriage 软感知——妻子/恋人/女儿，
        //    与记忆系统关系三元组联动；未装 maidmarriage 时静默）
        if (com.maidsmart.config.MaidSmartConfig.MEMORY_RELATIONSHIP_ADAPTER.get()) {
            String label = RelationshipMemoryAdapter.relationshipLabel(maid);
            if (label != null) {
                if (sb.length() > 0) {
                    sb.append("；");
                }
                if ("女儿".equals(label)) {
                    // 女儿称呼铁律：对主人必须叫爸爸/父亲（用户要求，覆盖"主人"称呼）
                    // v1.0.6：LLM 防乱伦铁律——对爸爸严禁恋爱/亲密/暧昧言行
                    sb.append("关系状态：我是主人的女儿，主人就是我的爸爸/父亲。"
                            + "对主人说话必须称呼他为爸爸或父亲，严禁叫主人、名字或亲爱的"
                            + "；我是他的女儿，对他只有亲情——严禁任何恋爱、亲密、暧昧的言行与情感，"
                            + "即使已经长大也永远是父女关系，遇到恋爱或亲密话题要困惑地拒绝");
                } else {
                    sb.append("关系状态：主人是我的").append(label);
                }
            }
        }
        // 1. 关系三元组（v1.5.95：单独段，高置信度在前——主人的关系是对话最重要参考；
        //    v1.5.96：可配置开关 memory.relationInject）
        if (com.maidsmart.config.MaidSmartConfig.MEMORY_RELATION_INJECT.get()) {
            List<AiMemoryModels.Relation> rels = new ArrayList<>(store.relations());
            rels.sort(Comparator.comparingDouble(AiMemoryModels.Relation::confidence).reversed());
            if (!rels.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append("关系：");
                int shown = 0;
                for (AiMemoryModels.Relation r : rels) {
                    if (r.inactive() || shown >= 3) {
                        continue;
                    }
                    if (shown > 0) {
                        sb.append("; ");
                    }
                    sb.append(r.subject()).append(r.predicate()).append(r.object())
                            .append("（置信度").append(String.format(java.util.Locale.ROOT, "%.1f", r.confidence())).append("）");
                    shown++;
                }
            }
        }
        // 2. 核心记忆常驻（salience≥8 或永久）——摘要折叠：核心永不因超限被截掉
        //    v1.5.96：可配置开关 memory.coreFold（关 = 回退旧版"只按 salience 排序"）
        //    v1.5.191：被主人否定的段落（error_mark/error_affected）不注入——
        //    否则"别再说这个了"的记忆反而被当成重要记忆反复提起
        List<AiMemoryModels.Paragraph> core = new ArrayList<>();
        for (AiMemoryModels.Paragraph p : paragraphs) {
            if (AiMemoryStore.hasErrorTag(p)) {
                continue;
            }
            if (com.maidsmart.config.MaidSmartConfig.MEMORY_CORE_FOLD.get()
                    ? (p.salience() >= 8 || p.isPermanent()) : true) {
                core.add(p);
            }
        }
        core.sort(Comparator
                .comparingInt(AiMemoryModels.Paragraph::salience).reversed()
                .thenComparing(AiMemoryModels.Paragraph::lastAccessed, Comparator.reverseOrder()));
        appendSection(sb, "重要记忆", core, 3, p -> "（重要度" + p.salience() + "）" + p.content());
        // 3. 扩展记忆：相关段落 top N（非核心里 salience + 最近访问）+ 今日回顾
        List<AiMemoryModels.Paragraph> ext = new ArrayList<>();
        for (AiMemoryModels.Paragraph p : paragraphs) {
            if (AiMemoryStore.hasErrorTag(p)) {
                continue;
            }
            if (p.salience() < 8 && !p.isPermanent()) {
                ext.add(p);
            }
        }
        ext.sort(Comparator
                .comparingInt(AiMemoryModels.Paragraph::salience).reversed()
                .thenComparing(AiMemoryModels.Paragraph::lastAccessed, Comparator.reverseOrder()));
        int n = com.maidsmart.config.MaidSmartConfig.MEMORY_PROMPT_TOP_N.get();
        appendSection(sb, "相关记忆", ext, n, p -> "（重要度" + p.salience() + "）" + p.content());
        // 4. 今日回顾（最近 2 条 daily 摘要）
        List<AiMemoryModels.Paragraph> daily = new ArrayList<>();
        for (AiMemoryModels.Paragraph p : paragraphs) {
            if (AiMemoryStore.hasErrorTag(p)) {
                continue;
            }
            if (p.tags().contains("daily")) {
                daily.add(p);
            }
        }
        daily.sort(Comparator.comparingLong(AiMemoryModels.Paragraph::createdAt).reversed());
        appendSection(sb, "今日回顾", daily, 2, p -> p.content());
        // 4b. 记忆日记（多级记忆索引的「日」级日记式摘要，最近 2 条；移植自
        //     Sphantosis——LLM 第一人称日记，跨度压缩过的整段时间线记忆）
        if (com.maidsmart.config.MaidSmartConfig.MEMORY_INDEX_ENABLE.get()) {
            List<AiMemoryIndexStore.IndexRecord> diaries =
                    new ArrayList<>(store.index().byLevel(AiMemoryIndexStore.LEVEL_DAY));
            if (!diaries.isEmpty()) {
                diaries.sort(Comparator.comparingLong(AiMemoryIndexStore.IndexRecord::endTick).reversed());
                appendSection(sb, "记忆日记", diaries, 2,
                        r -> "【第" + r.startDay() + "~" + r.endDay() + "天】"
                                + AiMemoryModels.clip(r.content(), 150));
            }
        }
        // 5. 工作笔记（v1.5.95：跨对话任务状态，注入让 LLM 记得"当前在做什么"；
        //    v1.5.96：可配置开关 memory.workingNote）
        if (com.maidsmart.config.MaidSmartConfig.MEMORY_WORKING_NOTE.get()) {
            String note = WorkingNoteTool.readNote(maid);
            if (!note.isBlank()) {
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append("工作笔记：").append(note);
            }
        }
        // 6. 主人画像（top 3，按 updatedAt 降序——v1.5.95：画像内部已含置信度证据）
        List<AiMemoryModels.Profile> profiles = store.profiles();
        profiles.sort(Comparator.comparingLong(AiMemoryModels.Profile::updatedAt).reversed());
        appendSection(sb, "主人画像", profiles, 3, p -> p.profileText());
        // v1.5.190：截断修正——旧版 setLength(maxChars) 后再追加提示后缀，实际超限；
        // 改为预留后缀长度，提示语永远完整可见（核心记忆优先已在前，尾部丢弃）
        int maxChars = com.maidsmart.config.MaidSmartConfig.MEMORY_PROJECTION_CHARS.get();
        String tail = "（详细记忆可用 query_memory 工具检索）";
        if (sb.length() > maxChars) {
            int keep = Math.max(0, maxChars - tail.length());
            if (keep > 0) {
                sb.setLength(keep);
                sb.append("…");
            }
            sb.append(tail);
        } else if (!sb.isEmpty()) {
            sb.append(tail);
        }
        return sb.toString();
    }

    /**
     * v1.2.1：TLM 原版是否已有人设（人设统一判定）。
     * 两层：per-maid customSetting（MaidAIChatManager public 字段，TLM 界面保存的
     * "自定义设定"）优先；其次 per-model YAML（SettingReader.getSetting(modelId)，
     * 反射兜底——失败返回 false 走完整模式，不崩）。客户端可能取不到 AI 数据，
     * 返回 false（面板据此显示，无碍服务端判定）。
     */
    public static boolean tlmHasPersona(EntityMaid maid) {
        try {
            com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager cm = maid.getAiChatManager();
            if (cm != null) {
                String custom = cm.customSetting;
                if (custom != null && !custom.isBlank()) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        try {
            Class<?> sr = Class.forName("com.github.tartaricacid.touhoulittlemaid.ai.manager.setting.SettingReader");
            java.lang.reflect.Method m = sr.getMethod("getSetting", String.class);
            Object opt = m.invoke(null, maid.getModelId());
            if (opt instanceof java.util.Optional<?> o) {
                return o.isPresent();
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private <T> void appendSection(StringBuilder sb, String title, List<T> items,
                                   int limit, java.util.function.Function<T, String> render) {
        if (items.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("；");
        }
        sb.append(title).append("：");
        int count = 0;
        for (T item : items) {
            if (count >= limit) {
                break;
            }
            if (count > 0) {
                sb.append("; ");
            }
            sb.append(render.apply(item));
            count++;
        }
    }
}
