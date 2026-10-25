package com.maidsmart.dialogue;

/**
 * 主动对话阶段（环境优先）：追问 → 观察环境 → 安静陪伴 → 空闲。
 *
 * <p>每个阶段只是在"到点后要不要开口"时被检查一次；没内容就顺延到下一阶段，不烧配额、不记冷却。
 * 环境观察只在确实有值得注意的事（天气、夜色、敌人、主人受伤、正在干活）时开口；
 * 安静陪伴是收尾的一句轻话，之后进入空闲，等主人再次互动或空闲计时到期才重启。
 */
public enum ProactiveStage {
    LIGHT_FOLLOWUP(120),
    WORLD_OBSERVE(300),
    QUIET_MOMENT(1200),
    IDLE(-1);

    public final int delaySeconds;

    ProactiveStage(int delaySeconds) {
        this.delaySeconds = delaySeconds;
    }

    public static ProactiveStage start() {
        return LIGHT_FOLLOWUP;
    }

    public ProactiveStage next() {
        return switch (this) {
            case LIGHT_FOLLOWUP -> WORLD_OBSERVE;
            case WORLD_OBSERVE -> QUIET_MOMENT;
            default -> IDLE;
        };
    }
}
