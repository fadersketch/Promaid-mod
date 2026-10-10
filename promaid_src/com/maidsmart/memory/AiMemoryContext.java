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

    /** 实测八百二十九c：每女仆一个召回闸门（移植自 maidsoulcore MemoryRecallGate）。 */
    private static final java.util.Map<String, AiMemoryRecallGate> GATES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static AiMemoryRecallGate gateFor(EntityMaid maid) {
        return GATES.computeIfAbsent(maid.m_20148_().toString(), k -> new AiMemoryRecallGate(
                com.maidsmart.config.MaidSmartConfig.MEMORY_RECALL_GATE.get(),
                com.maidsmart.config.MaidSmartConfig.MEMORY_RECALL_MIN_INTERVAL.get(),
                com.maidsmart.config.MaidSmartConfig.MEMORY_RECALL_MIN_MESSAGES.get(),
                com.maidsmart.config.MaidSmartConfig.MEMORY_RECALL_CACHE_TTL.get()));
    }

    /** 女仆卸载时清闸门缓存（由 AiMemoryManager.onEntityLeaveLevel 调用）。 */
    public static void forget(java.util.UUID maidUuid) {
        if (maidUuid != null) {
            GATES.remove(maidUuid.toString());
        }
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
        if (paragraphs.isEmpty() && store.relations().isEmpty() && store.profiles().isEmpty()
                && store.profileItems().isEmpty() && sb.isEmpty()) {
            return "";
        }
        // 实测八百二十九c：召回闸门——用"段落数"当作"有没有新信息"的信号，无新信息且在
        // 最小间隔/缓存期内 → 只注入核心分区（跳过 相关记忆/今日回顾/记忆日记 这些动态大块），
        // 省 token 省 CPU；显式回忆线索（记得/上次/之前）在本入口拿不到
        // QueryMemoryTool 那侧强制放行。
        boolean full = true;
        if (com.maidsmart.config.MaidSmartConfig.MEMORY_RECALL_GATE.get()) {
            long nowMs = System.currentTimeMillis();
            long count = paragraphs.size();
            AiMemoryRecallGate gate = gateFor(maid);
            AiMemoryRecallGate.Decision d = gate.evaluate("", nowMs, count);
            full = d.allow();
            if (full) {
                gate.markCompleted("", nowMs, count);
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
        if (full) {
            appendSection(sb, "相关记忆", ext, n, p -> "（重要度" + p.salience() + "）" + p.content());
        }
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
        if (full) {
            appendSection(sb, "今日回顾", daily, 2, p -> p.content());
        }
        // 4b. 记忆日记（多级记忆索引的「日」级日记式摘要，最近 2 条；移植自
        //     Sphantosis——LLM 第一人称日记，跨度压缩过的整段时间线记忆）
        if (com.maidsmart.config.MaidSmartConfig.MEMORY_INDEX_ENABLE.get()) {
            List<AiMemoryIndexStore.IndexRecord> diaries =
                    new ArrayList<>(store.index().byLevel(AiMemoryIndexStore.LEVEL_DAY));
            if (!diaries.isEmpty()) {
                diaries.sort(Comparator.comparingLong(AiMemoryIndexStore.IndexRecord::endTick).reversed());
                if (full) {
                    appendSection(sb, "记忆日记", diaries, 2,
                            r -> "【第" + r.startDay() + "~" + r.endDay() + "天】"
                                    + AiMemoryModels.clip(r.content(), 150));
                }
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
        // 5b. 未完成的事（v1.3.0(beta)·移植自 Sphantosis 的事件「终止:0」标记）：
        //     提取时把"主人说要去做某事 / 在等一个结果 / 计划还没落地"这类事件打上 open:1，
        //     这里把它们单独拎出来——让女仆能主动接上"上次那件事怎么样了"，
        //     而不是等主人自己再提。只取最近 2 条，且与「相关记忆」去重不会重复啰嗦。
        if (com.maidsmart.config.MaidSmartConfig.MEMORY_OPEN_EVENTS.get()) {
            List<AiMemoryModels.Paragraph> open = new ArrayList<>();
            for (AiMemoryModels.Paragraph p : paragraphs) {
                if (AiMemoryStore.hasErrorTag(p)) {
                    continue;
                }
                if (p.tags() != null && p.tags().contains("open:1")) {
                    open.add(p);
                }
            }
            open.sort(Comparator.comparingLong(AiMemoryModels.Paragraph::createdAt).reversed());
            appendSection(sb, "她记挂着的事", open, 2, AiMemoryModels.Paragraph::content);
        }
        // 5c. 记忆快照（实测八百二十九c：女仆第一人称"心里话"，常驻注入——提供情绪连续性，
        //     与"客观记录"不同，是"她心里一直放着一件事"）
        if (com.maidsmart.config.MaidSmartConfig.MEMORY_SNAPSHOT_ENABLE.get()) {
            String snap = store.snapshotText();
            if (!snap.isBlank()) {
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append("她心里的记忆：").append(snap);
            }
        }
        // 6. 主人画像——实测八百二十九c：优先用结构化画像条目（按置信度降序、每类限量），
        //    没有条目时回退旧的拼接文本。
        List<AiMemoryModels.ProfileItem> items = com.maidsmart.config.MaidSmartConfig.MEMORY_PROFILE_ITEMS.get()
                ? store.profileItems() : new ArrayList<>();
        if (!items.isEmpty()) {
            items.sort(Comparator.comparingInt(AiMemoryModels.ProfileItem::confidence).reversed()
                    .thenComparing(AiMemoryModels.ProfileItem::updatedAt, Comparator.reverseOrder()));
            appendSection(sb, "主人画像", items, 5,
                    it -> "（" + it.category() + "）" + it.content() + "（置信度" + it.confidence() + "）");
        } else {
            List<AiMemoryModels.Profile> profiles = store.profiles();
            profiles.sort(Comparator.comparingLong(AiMemoryModels.Profile::updatedAt).reversed());
            appendSection(sb, "主人画像", profiles, 3, p -> p.profileText());
        }
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
