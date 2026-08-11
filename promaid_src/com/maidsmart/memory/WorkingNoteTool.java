package com.maidsmart.memory;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * working_note（v1.5.95，借鉴 maidsoulcore WorkingNoteStore）：
 * LLM 跨对话的"工作笔记"——当前进行中的任务/约定/待办（与长期记忆不同：
 * 工作笔记是临时的、可覆盖的、主动更新的）。
 *
 * 玩家说"帮我修好这座桥"、"别忘了待会提醒我去挖矿"这类进行中任务时，
 * LLM 用本工具记下；任务完成/取消时再调用清除。注入对话上下文让女仆
 * 跨对话记得"当前在做什么"。
 *
 * 落盘：<世界存档>/promaid_memory/<uuid>/working_note.txt（单文件，最新覆盖）。
 */
public class WorkingNoteTool implements ITool<WorkingNoteTool.Result> {
    public static final String TOOL_ID = "working_note";

    private static final String TOOL_DESC = "Use this to keep a SHORT-TERM working note about the current "
            + "ongoing task or agreement (e.g. the player asked you to fix a bridge, reminded you to remind "
            + "them to mine later). Different from remember() which is long-term memory — working notes are "
            + "temporary, overwritten, and cleared when the task is done.\n"
            + "action: set (record/overwrite), get (read current note), clear (remove the note).\n"
            + "content: the note text (only for action=set).";

    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("action", "set").forGetter(Result::action),
            Codec.STRING.optionalFieldOf("content", "").forGetter(Result::content)
    ).apply(instance, Result::new));

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return TOOL_DESC;
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        root.addProperties("action", StringParameter.create()
                .addEnumValues("set", "get", "clear").setDescription("set=记录/覆盖, get=读取, clear=清除"));
        root.addProperties("content", StringParameter.create()
                .setDescription("笔记内容（action=set 时必填）"));
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    /** 笔记文件路径 */
    private static Path notePath(EntityMaid maid) {
        if (!(maid.m_9236_() instanceof net.minecraft.server.level.ServerLevel level)) {
            return null;
        }
        return com.maidsmart.memory.AiMemoryExtractor.memoryRoot(level.m_7654_())
                .resolve(maid.m_20148_().toString()).resolve("working_note.txt");
    }

    /** 读取当前笔记（注入上下文用） */
    public static String readNote(EntityMaid maid) {
        try {
            Path p = notePath(maid);
            return p != null && Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public LLMCallback onCall(String toolId, Result result, LLMCallback callback) {
        // v1.5.96：工具开关（配置面板 aitools.workingNote）
        if (!com.maidsmart.config.MaidSmartConfig.TOOL_WORKING_NOTE.get()) {
            return callback.addToolResult("working_note 工具已被禁用（配置面板 AI 工具页可开启）。", toolId);
        }
        EntityMaid maid = callback.getMaid();
        // v1.5.190：与 remember/query_memory 一致——记忆功能关闭时拒绝读写笔记
        // （旧版笔记绕过记忆开关，关了记忆笔记还在上下文里注入）
        if (!com.maidsmart.memory.AiMemoryManager.isEnabled(maid)) {
            return callback.addToolResult("记忆功能已关闭（女仆配置界面可开启）。", toolId);
        }
        String action = result.action() == null ? "set" : result.action().trim();
        try {
            Path p = notePath(maid);
            if (p == null) {
                return callback.addToolResult("工作笔记需要在服务端使用", toolId);
            }
            switch (action) {
                case "get" -> {
                    String cur = Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
                    return callback.addToolResult(cur.isEmpty()
                            ? "当前没有工作笔记。"
                            : "当前工作笔记：" + cur, toolId);
                }
                case "clear" -> {
                    Files.deleteIfExists(p);
                    return callback.addToolResult("已清除工作笔记。", toolId);
                }
                default -> {
                    String content = result.content() == null ? "" : result.content().trim();
                    if (content.isEmpty()) {
                        return callback.addToolResult("笔记内容不能为空（action=set 需提供 content）。", toolId);
                    }
                    Files.createDirectories(p.getParent());
                    Files.writeString(p, content, StandardCharsets.UTF_8);
                    return callback.addToolResult("已记下工作笔记：「" + content + "」。任务完成或变化时可再次调用更新/清除。", toolId);
                }
            }
        } catch (Exception e) {
            return callback.addToolResult("工作笔记操作失败：" + e, toolId);
        }
    }

    public record Result(String action, String content) {
    }
}
