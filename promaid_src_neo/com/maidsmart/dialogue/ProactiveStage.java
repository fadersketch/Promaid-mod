package com.maidsmart.dialogue;

/**
 * 主动对话阶段状态机（对齐 maidsoulcore 的 ProactiveStage 7 阶段）：
 * LIGHT_FOLLOWUP → TOPIC_PUSH → WORLD_OBSERVE → RELATION_CANDIDATE → FINAL_NOTICE →
 * LONG_SILENCE_CHECK → IDLE（空闲，等待主人互动重启）。
 *
 * 节奏（相对上一阶段开火时刻的 tick 延迟，20 tick = 1 秒）：
 * - LIGHT_FOLLOWUP 90s：仅当"主人上条是提问且女仆还没答"才开火；- TOPIC_PUSH 300s：从长期记忆挑一条
 *   偏好/日常话题；- WORLD_OBSERVE 600s：世界简报（夜晚/下雨/怪物/主人血量/任务），有内容才开火；
 * - RELATION_CANDIDATE 900s：提一句关系记忆；- FINAL_NOTICE 1200s：本周期已发言 == maxReplies-1 时收尾；
 * - LONG_SILENCE_CHECK 1800s：主人"还在吗"轻确认（每天最多 dialogue.longSilenceMax 次）。
 * IDLE：停。主人互动 → 重置回 LIGHT_FOLLOWUP；或沉默 ≥ dialogue.proactiveIdleMin 分钟重启新周期。
 *
 * 每轮最多 maxReplies 次（dialogue.maxReplies，默认 4）——FINAL_NOTICE 只在接近上限时开火，其余阶段
 * 多数因"无内容"跳过，实际一轮常为 2-3 次发言。
 */
public enum ProactiveStage {
    LIGHT_FOLLOWUP(90),
    TOPIC_PUSH(300),
    WORLD_OBSERVE(600),
    RELATION_CANDIDATE(900),
    FINAL_NOTICE(1200),
    LONG_SILENCE_CHECK(1800),
    IDLE(-1);

    /** 相对上一阶段开火时刻的延迟（秒）；IDLE=-1 */
    public final int delaySeconds;

    ProactiveStage(int delaySeconds) {
        this.delaySeconds = delaySeconds;
    }

    /** 主互动后重新开始的阶段 */
    public static ProactiveStage start() {
        return LIGHT_FOLLOWUP;
    }

    /** 下一阶段（IDLE 停） */
    public ProactiveStage next() {
        if (this == IDLE) {
            return IDLE;
        }
        ProactiveStage[] all = values();
        return all[Math.min(ordinal() + 1, all.length - 1)];
    }
}
