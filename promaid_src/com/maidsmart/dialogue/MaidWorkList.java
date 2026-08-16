package com.maidsmart.dialogue;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 工作清单（v1.5.196，移植 PatchouliAI TodoList 思想到 TLM 工具链）。
 *
 * LLM 跨轮规划：用 work_list 工具写入/更新"当前任务计划"（[{content,status,priority}]），
 * 之后每一轮对话/主动会话都把这清单注入上下文 —— LLM 知道自己"在做什么、下一步做什么"，
 * 不必每轮重复向玩家确认，也不需要在一次回复里把整件事做完（这是超时的根源）。
 *
 * 注入方式：在 WorkListContext（GameContext）里投影 —— 主对话的 system 上下文自动带上
 * 清单文本，零额外工具轮次。工具本身只做查询（query_todo/build_need）与更新（set）。
 */
public final class MaidWorkList {
    private static final Map<java.util.UUID, List<Item>> LISTS = new ConcurrentHashMap<>();

    /** 审计：女仆卸载/移除时清理工作清单 */
    public static void forgetMaid(java.util.UUID maidUuid) {
        LISTS.remove(maidUuid);
    }

    private MaidWorkList() {
    }

    /** 清单项 */
    public record Item(String content, String status, String priority) {
    }

    /** 读取女仆当前清单（空则空列表） */
    public static List<Item> items(EntityMaid maid) {
        if (maid == null) {
            return List.of();
        }
        List<Item> list = LISTS.get(maid.m_20148_());
        return list == null ? List.of() : list;
    }

    /** 替换整个清单（只允许一个 in_progress） */
    public static void set(EntityMaid maid, List<Item> items) {
        List<Item> clean = items == null ? List.of() : items;
        int inProgress = 0;
        for (Item it : clean) {
            if ("in_progress".equals(it.status())) {
                inProgress++;
            }
        }
        if (inProgress > 1) {
            return; // 拒绝：只允许一个进行中
        }
        LISTS.put(maid.m_20148_(), new ArrayList<>(clean));
    }

    /** 清空 */
    public static void clear(EntityMaid maid) {
        LISTS.remove(maid.m_20148_());
    }

    /** 注入上下文文本（供 WorkListContext 用） */
    public static String toPromptString(EntityMaid maid) {
        List<Item> items = items(maid);
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("== 当前任务计划 ==\n");
        for (Item it : items) {
            String marker = switch (it.status() == null ? "pending" : it.status()) {
                case "completed" -> "[x]";
                case "in_progress" -> "[>]";
                case "cancelled" -> "[-]";
                default -> "[ ]";
            };
            sb.append(marker).append(" ").append(it.priority() == null ? "medium" : it.priority())
                    .append(" - ").append(it.content() == null ? "" : it.content()).append("\n");
        }
        return sb.toString();
    }

    /** JSON 解析（{content,status,priority}[]）→ 清单；失败返回 null */
    public static List<Item> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            String s = json.trim();
            if (s.startsWith("[")) {
                // 数组形式
                com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(s).getAsJsonArray();
                List<Item> out = new ArrayList<>();
                for (com.google.gson.JsonElement el : arr) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    com.google.gson.JsonObject o = el.getAsJsonObject();
                    String content = o.has("content") && !o.get("content").isJsonNull() ? o.get("content").getAsString() : "";
                    String status = o.has("status") && !o.get("status").isJsonNull() ? o.get("status").getAsString() : "pending";
                    String priority = o.has("priority") && !o.get("priority").isJsonNull() ? o.get("priority").getAsString() : "medium";
                    out.add(new Item(content, status, priority));
                }
                return out;
            }
            // 对象形式 {"content":...,"status":...,"priority":...}
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(s).getAsJsonObject();
            String content = o.has("content") && !o.get("content").isJsonNull() ? o.get("content").getAsString() : "";
            String status = o.has("status") && !o.get("status").isJsonNull() ? o.get("status").getAsString() : "pending";
            String priority = o.has("priority") && !o.get("priority").isJsonNull() ? o.get("priority").getAsString() : "medium";
            if (content.isBlank()) {
                return null;
            }
            List<Item> out = new ArrayList<>();
            out.add(new Item(content, status, priority));
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
