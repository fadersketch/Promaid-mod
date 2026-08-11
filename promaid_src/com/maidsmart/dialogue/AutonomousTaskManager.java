package com.maidsmart.dialogue;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.ChatClientInfo;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自主决策（P4）：女仆空闲时，让 LLM 自己判断现在该做什么。
 *
 * 实现方式：不走新的规划管线，而是通过主模组现成的 chat() 管线注入一条"自我指令"——
 * 系统提示词里的 "Action First / Just Do It" 规则会驱动 LLM 直接调用
 * switch_work_task 等工具切换任务，回复还会以气泡呈现（"我自己决定去挖矿了"）。
 *
 * 平衡设计：
 * - 仅当任务为"空闲"、白天（1000~13000 tick）、主人在 16 格内时触发
 * - 每 10 分钟最多一次；每天最多 10 次；LLM 未启用不触发
 */
public class AutonomousTaskManager {
    /** v1.5.102：检查间隔 / 主人范围 / 工作时间段均从配置面板读取（dialogue 段） */

    private final Map<UUID, Long> lastDecideTime = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> dailyCount = new ConcurrentHashMap<>();
    private long lastDay = -1;

    /** v1.5.88：读配置面板（dialogue 段，分钟→tick） */
    private static long decideCooldown() {
        return (long) com.maidsmart.config.MaidSmartConfig.DIALOGUE_AUTONOMOUS_COOLDOWN.get() * 1200L;
    }

    private static int autonomousDaily() {
        return com.maidsmart.config.MaidSmartConfig.DIALOGUE_AUTONOMOUS_DAILY.get();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (!com.maidsmart.config.MaidSmartConfig.DIALOGUE_AUTONOMOUS.get()) {
            return;
        }
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!AIConfig.LLM_ENABLED.get()) {
            return;
        }
        MinecraftServer server = event.getServer();
        long tick = server.m_129921_();
        if (tick % ((long) com.maidsmart.config.MaidSmartConfig.DIALOGUE_AUTO_SCAN.get() * 20) != 0) {
            return;
        }
        long day = tick / 24000L;
        if (day != this.lastDay) {
            this.lastDay = day;
            this.dailyCount.clear();
        }
        for (ServerPlayer player : server.m_6846_().m_11314_()) {
            player.m_9236_().m_45976_(EntityMaid.class, player.m_20191_().m_82400_(
                    com.maidsmart.config.MaidSmartConfig.DIALOGUE_AUTO_OWNER_RANGE.get())).forEach(maid -> {
                if (!maid.m_21824_() || !maid.m_6084_()) {
                    return;
                }
                LivingEntity owner = maid.m_269323_();
                if (!(owner instanceof ServerPlayer) || owner != player) {
                    return;
                }
                // 仅空闲任务触发；白天才工作
                if (maid.getTask() != TaskManager.getIdleTask()) {
                    return;
                }
                long dayTime = player.m_9236_().m_46468_();
                if (dayTime < com.maidsmart.config.MaidSmartConfig.DIALOGUE_AUTO_DAY_START.get()
                        || dayTime >= com.maidsmart.config.MaidSmartConfig.DIALOGUE_AUTO_DAY_END.get()) {
                    return;
                }
                UUID maidId = maid.m_20148_();
                Long last = this.lastDecideTime.get(maidId);
                if (last != null && tick - last < decideCooldown()) {
                    return;
                }
                if (this.dailyCount.getOrDefault(maidId, 0) >= autonomousDaily()) {
                    return;
                }
                // v1.5.193：敌袭冻结——战斗中不自主切换任务（先保命/打架，别惦记干活）
                if (com.maidsmart.dialogue.PerceptionManager.dangerActive(maid)) {
                    return;
                }
                // v1.5.191：配额先判后记（与主动对话同一修正）——旧版先记冷却/日计数
                // 再 tryAcquire，配额满时女仆白烧 10 分钟冷却、日计数照涨（"自主决策没反应"根因）
                if (!ApiQuotaManager.tryAcquire()) {
                    return;
                }
                this.lastDecideTime.put(maidId, tick);
                this.dailyCount.merge(maidId, 1, Integer::sum);
                String selfPrompt = "（你独自待了一会儿，主人暂时没有吩咐。"
                        + "请根据现在的天色、天气和你自己的状态，判断现在最该做什么："
                        + "需要干活就切换到合适的工作任务，"
                        + "一切正常也可以继续休息。不用问主人，自己决定就好。）";
                // v1.5.198：语言强制（dialogue.outputLanguage；留空 = 跟随 TLM/客户端语言）
                maid.getAiChatManager().chat(selfPrompt, ChatInfoUtil.fromMaid(maid), player);
            });
        }
    }
}
