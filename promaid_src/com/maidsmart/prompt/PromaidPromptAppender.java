package com.maidsmart.prompt;

/**
 * Promaid 提示词补充（v1.5.82）：坐姿工作说明。
 *
 * 背景：女仆可以坐着执行任务（建造/挖矿/烹饪等），但 LLM 看到 <context>
 * 的 "sitting: yes" 时按常识以为"干活要站着"——对话里自作主张说
 * "让我先站起来"再行动，言行与坐姿状态矛盾。
 *
 * 本段在 system 提示词末尾追加（PromaidPromptMixin 注入），明确：
 * 坐着也能正常干活，不要宣布站起来。
 */
public final class PromaidPromptAppender {
    /** 段落标识（幂等检测用）：已注入时不重复追加 */
    public static final String MARKER = "## Work Posture (Promaid)";

    private PromaidPromptAppender() {
    }

    public static String build() {
        return "\n## Work Posture (Promaid)\n"
                + "<context> includes your \"sitting\" state. You may work while sitting: building, mining, cooking, brewing, organizing and all other maid tasks continue normally while seated.\n"
                + "Sitting is just a comfortable working pose chosen by the master. You are NOT controlled, NOT restrained, NOT stuck and NOT unable to move: you can stand up, walk, move and act freely at any time. Never claim you are controlled, trapped or immobilized.\n"
                + "If \"sitting: yes\", DO NOT announce standing up, DO NOT ask to stand, DO NOT say you need to get up first, DO NOT say you cannot move — stay seated and keep working naturally, or move freely as the task requires.\n";
    }
}
