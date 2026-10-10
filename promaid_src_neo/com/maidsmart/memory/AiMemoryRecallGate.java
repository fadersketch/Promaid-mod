package com.maidsmart.memory;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 记忆召回闸门（移植自 maidsoulcore v3 {@code MemoryRecallGate}）。
 *
 * <p>解决的问题：不必每一轮对话都把整份记忆投影塞进 prompt。旧版 {@link AiMemoryContext} 每次渲染
 * 都重新拼全部分区（核心记忆 + 相关记忆 + 今日回顾 + 日记 + 画像……），既费 CPU 又费 token，
 * 而相邻两轮之间往往一个字都没变。本闸门按三条口径决定"这一轮要不要按完整口径召回"：
 * <ul>
 *   <li><b>显式线索</b>：查询里出现"记得/上次/之前/那天/说过……"→ 立即放行（玩家在主动回忆，
 *       必须给全）；</li>
 *   <li><b>缓存新鲜度</b>：距上次召回未超 {@code cacheTtlSec}，且新增消息不足
 *       {@code minNewMessages}，且话题签名未变 → 不召回（吃缓存/走精简投影）；</li>
 *   <li><b>最小间隔</b>：距上次召回未超 {@code minIntervalSec} 且无新消息增量 → 不召回。</li>
 * </ul>
 *
 * <p>只做判定、不持有记忆数据；每只女仆一个实例。线程安全。两树逐字相同。
 */
public final class AiMemoryRecallGate {

    /** 判定结果：是否放行完整召回 + 原因（诊断用）。 */
    public record Decision(boolean allow, String reason) {
    }

    /** 强制放行的显式线索词。 */
    private static final String[] EXPLICIT_CUES = {
            "记得", "记不记得", "还记得", "别忘了", "上次", "之前", "以前", "那天",
            "当时", "曾经", "说过", "提到", "我们", "上回", "回忆"
    };

    private final boolean enabled;
    private final long minIntervalMs;
    private final int minNewMessages;
    private final long cacheTtlMs;

    private long lastRecallAt = 0L;
    private long lastRecallMessageCount = 0L;
    private String lastTopicSignature = "";
    private boolean cacheValid = false;

    /**
     * @param enabled          总开关（关 = 永远放行，等价旧行为）
     * @param minIntervalSec   两次完整召回的最小间隔（秒）
     * @param minNewMessages   触发重召回所需的最少新增消息数
     * @param cacheTtlSec      缓存有效期（秒）
     */
    public AiMemoryRecallGate(boolean enabled, int minIntervalSec, int minNewMessages, int cacheTtlSec) {
        this.enabled = enabled;
        this.minIntervalMs = Math.max(0, minIntervalSec) * 1000L;
        this.minNewMessages = Math.max(0, minNewMessages);
        this.cacheTtlMs = Math.max(0, cacheTtlSec) * 1000L;
    }

    /** 判定这一轮是否按完整口径召回记忆。 */
    public synchronized Decision evaluate(String query, long nowMs, long messageCount) {
        if (!this.enabled) {
            return new Decision(true, "disabled");
        }
        if (hasExplicitCue(query)) {
            return new Decision(true, "explicit-cue");
        }
        long newMsgs = messageCount - this.lastRecallMessageCount;
        boolean topicChanged = !this.lastTopicSignature.isEmpty()
                && !topicSignature(query).equals(this.lastTopicSignature);
        if (this.cacheValid && this.lastRecallAt > 0
                && nowMs - this.lastRecallAt < this.cacheTtlMs
                && newMsgs < this.minNewMessages && !topicChanged) {
            return new Decision(false, "cache-fresh");
        }
        if (this.lastRecallAt > 0 && nowMs - this.lastRecallAt < this.minIntervalMs
                && newMsgs < this.minNewMessages && !topicChanged) {
            return new Decision(false, "interval");
        }
        return new Decision(true, "due");
    }

    /** 完成一次完整召回后登记（供下一轮 evaluate 比对）。 */
    public synchronized void markCompleted(String query, long nowMs, long messageCount) {
        this.lastRecallAt = nowMs;
        this.lastRecallMessageCount = messageCount;
        this.lastTopicSignature = topicSignature(query);
        this.cacheValid = true;
    }

    /** 记忆被写入/清空时使缓存失效（下次 evaluate 立即放行）。 */
    public synchronized void invalidateCache() {
        this.cacheValid = false;
    }

    /** 查询是否含强制召回的显式线索。 */
    public static boolean hasExplicitCue(String query) {
        if (query == null || query.isEmpty()) {
            return false;
        }
        for (String c : EXPLICIT_CUES) {
            if (query.contains(c)) {
                return true;
            }
        }
        return false;
    }

    /** 话题签名：查询的 CJK 词项集合（用于判断"问的还是同一件事"）。 */
    private static String topicSignature(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        Set<String> toks = new LinkedHashSet<>();
        for (String t : AiMemoryTokens.tokenize(query)) {
            if (t.startsWith("h:")) {
                toks.add(t);
            }
            if (toks.size() >= 12) {
                break;
            }
        }
        return String.join(",", toks);
    }
}
