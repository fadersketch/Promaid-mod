package com.maidsmart.memory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 记忆快照（移植自 maidsoulcore v3 {@code MemorySnapshotStore}）。
 *
 * <p>与"摘要段落/多级日记"不同的东西：那些是**客观记录**（发生了什么）；快照是女仆以第一人称
 * 写下的、**不超过 200 字的一段心里话**——"我一直记得那天主人……"。它常驻注入，给对话提供
 * 情绪连续性：不是每条记忆都念一遍，而是"她心里一直放着一件事"。
 *
 * <p>落盘 {@code <store>/snapshot.txt}（纯文本，可人工查看/编辑）。只有值得长期记住的深刻经历
 * 才更新；没有就保持不变（不写空）。每 N 次摘要刷新一次（阈值见配置 memory.snapshotEveryN）。
 *
 * <p>纯 IO 工具、无状态。两树逐字相同。
 */
public final class AiMemorySnapshotStore {

    /** 快照硬上限（与 maidsoulcore 的 MAX_CHARS 对齐）。 */
    public static final int MAX_CHARS = 200;

    private AiMemorySnapshotStore() {
    }

    private static Path file(Path dir) {
        return dir.resolve("snapshot.txt");
    }

    /** 读取当前快照（无则空串）。 */
    public static String load(Path dir) {
        try {
            Path f = file(dir);
            if (Files.exists(f)) {
                String s = Files.readString(f, StandardCharsets.UTF_8).trim();
                return s;
            }
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("AiMemorySnapshotStore", ignored);
        }
        return "";
    }

    /** 写入快照（自动 clip 到 {@link #MAX_CHARS}；空/none 不写）。 */
    public static void save(Path dir, String text) {
        if (text == null || text.isBlank() || text.equalsIgnoreCase("none")) {
            return;
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(file(dir), clip(text), StandardCharsets.UTF_8);
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("AiMemorySnapshotStore", ignored);
        }
    }

    /** 删除快照（清空记忆时同步）。 */
    public static void clear(Path dir) {
        try {
            Files.deleteIfExists(file(dir));
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("AiMemorySnapshotStore", ignored);
        }
    }

    /** 渲染成注入片段（无快照返回空串）。 */
    public static String render(Path dir) {
        String s = load(dir);
        return s.isEmpty() ? "" : "她心里的记忆：" + s;
    }

    /** clip 到 {@link #MAX_CHARS} 字（超出加省略号）。 */
    public static String clip(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim().replaceAll("\\s+", " ");
        return t.length() <= MAX_CHARS ? t : t.substring(0, MAX_CHARS - 1) + "…";
    }
}
