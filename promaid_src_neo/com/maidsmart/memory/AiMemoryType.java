package com.maidsmart.memory;

/**
 * AI 记忆类型（对齐 maidsoulcore 的 MemoryType）。
 * 决定写入分层（AiMemoryWriteStrategy）与 salience 下限。
 */
public enum AiMemoryType {
    /** 具体事件（喂食/玩家死亡/某次经历） */
    EVENT,
    /** 稳定偏好 */
    PREFERENCE,
    /** 承诺/约定 */
    PROMISE,
    /** 关系状态/关系事件 */
    RELATION,
    /** 情绪线索/受伤/亏欠 */
    EMOTION,
    /** 世界事实（方块/地点/物品） */
    WORLD,
    /** 摘要（每日回顾/对话摘要） */
    SUMMARY,
    /** 普通对话（默认短期层） */
    DIALOGUE
}
