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
        List<AiMemoryModels.Paragraph> paragraphs = store.paragraphs();
        if (paragraphs.isEmpty() && store.relations().isEmpty() && store.profiles().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        // 0. 关系感知标签（v1.5.98：maidmarriage 软感知——妻子/恋人/女儿，
        //    与记忆系统关系三元组联动；未装 maidmarriage 时静默）
        if (com.maidsmart.config.MaidSmartConfig.MEMORY_RELATIONSHIP_ADAPTER.get()) {
            String label = RelationshipMemoryAdapter.relationshipLabel(maid);
            if (label != null) {
                sb.append("关系状态：主人是我的").append(label);
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
