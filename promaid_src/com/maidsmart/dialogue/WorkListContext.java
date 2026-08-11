package com.maidsmart.dialogue;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.AbstractMaidContext;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/**
 * 工作清单上下文（v1.5.196）：把 LLM 的跨轮任务计划注入对话。
 *
 * 对齐 AiMemoryContext 的做法：prompt 只放短投影（当前任务计划文本），
 * 详细查询走 work_list 工具的 query_todo / build_need。
 * 开关关闭或无清单时返回空串（主模组空白自动过滤）。
 */
public class WorkListContext extends AbstractMaidContext {
    public WorkListContext() {
        super("ai_worklist", "AI Work List");
    }

    @Override
    public String getValue(EntityMaid maid) {
        if (!com.maidsmart.config.MaidSmartConfig.TOOL_WORK_LIST.get()) {
            return "";
        }
        return MaidWorkList.toPromptString(maid);
    }
}
