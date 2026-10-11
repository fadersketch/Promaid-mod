package com.maidsmart.memory;

/**
 * 世界事件持久化策略（移植自 maidsoulcore v3 {@code WorldEventMemoryPolicy} 的
 * {@code shouldPersist / shouldWakePlanner}）。
 *
 * <p>在**写入前**过一道闸（否则"路上遇到僵尸"这类原版背景噪音会反复进记忆、反复被注入）：
 * <ul>
 *   <li>{@link #shouldPersist}：过滤纯背景噪音（无危险/承诺/关系/发现线索、且重要度低）；</li>
 *   <li>{@link #escalateSalience}：把"危及主人/女仆""明确承诺""关系转折"这类事件的 salience 抬到高位
 *       （对应 shouldWakePlanner：必须以高重要度写进记忆）；</li>
 *   <li>{@link #isUrgent}：紧急线索（供调用方决定是否额外播报/主动开口）。</li>
 * </ul>
 *
 * <p>纯规则、无状态。两树逐字相同。
 */
public final class AiMemoryEventPolicy {

    /** 需要抬重要度的线索（危险/承诺/关系/重大发现/损失）。 */
    private static final String[] IMPORTANT_CUES = {
            "死亡", "死了", "濒死", "受伤", "流血", "救我", "危险", "着火", "爆炸",
            "承诺", "答应", "约定", "发誓", "永远",
            "喜欢", "爱上", "表白", "结婚", "分手", "吵架", "道歉", "和好",
            "发现", "找到", "第一次", "终于"
    };

    /** 明显是原版背景噪音的线索（普通刷怪/路遇，默认不进记忆）。 */
    private static final String[] NOISE_CUES = {
            "遇到", "碰到", "打死了", "击杀", "刷出", "路过", "经过", "天气", "下雨", "天黑了"
    };

    private AiMemoryEventPolicy() {
    }

    /**
     * 这条事件该不该写进长期记忆。
     *
     * @param content  事件正文
     * @param salience 模型给的 1..10 重要度
     * @param tags     段落 tags（含 open:1 等标记）
     */
    public static boolean shouldPersist(String content, int salience, String tags) {
        if (content == null || content.isBlank()) {
            return false;
        }
        if (salience >= 7) {
            return true; // 模型已判定重要，放行
        }
        if (tags != null && (tags.contains("open:1") || tags.contains("emotion")
                || tags.contains("relationship_event") || tags.contains("promise"))) {
            return true;
        }
        if (hasCue(content, IMPORTANT_CUES)) {
            return true;
        }
        // 噪音：短句 + 命中噪音线索 + 无重要线索 → 丢弃
        if (content.length() <= 24 && hasCue(content, NOISE_CUES)) {
            return false;
        }
        return true; // 拿不准 → 保留（宁缺毋滥的反面：宁可多记一条，不可漏掉主线）
    }

    /** 按线索抬升 important 事件的 salience（返回新的 1..10）。 */
    public static int escalateSalience(String content, int salience) {
        if (content != null && hasCue(content, IMPORTANT_CUES)) {
            return Math.max(salience, 8);
        }
        return salience;
    }

    /** 是否紧急（危险线索）——调用方可据此决定是否立刻写入/提醒。 */
    public static boolean isUrgent(String content) {
        return content != null && hasCue(content,
                new String[]{"危险", "救我", "着火", "爆炸", "濒死", "受伤", "死亡", "死了"});
    }

    private static boolean hasCue(String content, String[] cues) {
        for (String c : cues) {
            if (content.contains(c)) {
                return true;
            }
        }
        return false;
    }
}
