package com.maidsmart.dialogue;

import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * 全局 LLM API 配额（v1.5.26）：**所有女仆单一天合计**主动调用 ≤ 上限次。
 *
 * 只约束"女仆自主发起的 LLM 调用"（主动对话 / 自主决策 / Love Loathe 主动对话）——
 * 被动对话（玩家打开 AI 聊天界面发消息、玩家发起的广播）不经过本配额，不受限制。
 *
 * 按游戏日（tick/24000）重置；服务端单例状态（静态字段，无并发问题——
 * 所有调用都在服务端主线程）。
 *
 * v1.5.191 配额改革：
 * - dailyApiLimit() == 0 → 不限（旧版 0 语义是"永远禁言"，属于 bug——配置面板
 *   的说明写着 0~100，用户填 0 期望的是不限而不是永久闭嘴）
 * - 配额只在【真正开火前】获取：调用方先做阶段选择（纯计算无副作用），选中才
 *   tryAcquire；失败不推进状态、不烧冷却，下轮重试——修掉旧版"扫描空契机也烧
 *   全局配额"（探测不出话题时一次 API 配额白白消耗）。
 * - 上限可在运行中调高（面板热更新）：调高后立刻恢复可获取，无需等跨日。
 */
public final class ApiQuotaManager {

    private static int count = 0;
    private static long lastDay = -1;

    /** v1.5.88：读配置面板（dialogue.apiDailyLimit）；v1.5.191：0 = 不限 */
    public static int dailyApiLimit() {
        return com.maidsmart.config.MaidSmartConfig.DIALOGUE_API_DAILY_LIMIT.get();
    }

    private ApiQuotaManager() {
    }

    /** 配额内返回 true 并计数；当日已满返回 false（调用方应跳过本次 LLM 调用） */
    public static boolean tryAcquire() {
        try {
            net.minecraft.server.MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                long day = server.m_129921_() / 24000L;
                if (day != lastDay) {
                    lastDay = day;
                    count = 0;
                }
            }
        } catch (Exception ignored) {
        }
        // v1.5.191：0 = 不限（配额改革——原"count>=0 恒 false"把填 0 变成永远禁言）
        int limit = dailyApiLimit();
        if (limit <= 0) {
            return true;
        }
        if (count >= limit) {
            return false;
        }
        count++;
        return true;
    }

    /** 当日剩余配额（用于调试/显示；0=不限时返回 -1 表示不限） */
    public static int remaining() {
        int limit = dailyApiLimit();
        if (limit <= 0) {
            return -1;
        }
        return Math.max(0, limit - count);
    }
}
