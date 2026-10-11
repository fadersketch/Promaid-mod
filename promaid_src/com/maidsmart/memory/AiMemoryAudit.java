package com.maidsmart.memory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 记忆审计与索引清单（移植自 maidsoulcore v3 {@code MemoryV3Store} 的审计元数据 +
 * {@code MemoryV3IndexManifest} 增量重建思路）。
 *
 * <p>两件事：
 * <ul>
 *   <li><b>审计日志</b>（{@code audit_log.jsonl}）：每次纠错/删除/撤销都追加一行
 *       {@code {"time":..,"action":..,"hash":..,"detail":..}}——只把段落标 deleted 无法排查"记忆怎么变了"。</li>
 *   <li><b>索引清单</b>（{@code memory_index_manifest.json}）：记录"生成这份多级日记索引时段落集合的
 *       指纹（所有段落 hash 的哈希）"。{@code AiMemoryArchiver} 据此判断索引是否过期，只有指纹变了才重建
 *       ——避免每次跨日都全量重扫。</li>
 * </ul>
 *
 * <p>纯 IO 工具、无状态。两树逐字相同。
 */
public final class AiMemoryAudit {

    /** 清单落盘实体。 */
    public static final class Manifest {
        public String fingerprint = "";
        public int indexCount = 0;
        public long updatedAt = 0L;
    }

    private AiMemoryAudit() {
    }

    /** 追加一行审计（失败静默——记忆系统不能影响游戏运行）。 */
    public static void append(Path dir, String action, String hash, String detail) {
        try {
            Files.createDirectories(dir);
            StringBuilder sb = new StringBuilder("{\"time\":").append(System.currentTimeMillis());
            sb.append(",\"action\":").append(quote(action));
            sb.append(",\"hash\":").append(quote(hash));
            sb.append(",\"detail\":").append(quote(detail)).append("}\n");
            Files.writeString(dir.resolve("audit_log.jsonl"), sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("AiMemoryAudit", ignored);
        }
    }

    /** 段落集合指纹：所有非删除段落 hash 拼接后的哈希（顺序无关——先排序）。 */
    public static String fingerprint(List<AiMemoryModels.Paragraph> paragraphs) {
        if (paragraphs == null || paragraphs.isEmpty()) {
            return "";
        }
        List<String> hashes = new java.util.ArrayList<>();
        for (AiMemoryModels.Paragraph p : paragraphs) {
            if (p != null && !p.deleted() && p.hash() != null) {
                hashes.add(p.hash());
            }
        }
        java.util.Collections.sort(hashes);
        StringBuilder sb = new StringBuilder(hashes.size() * 10);
        for (String h : hashes) {
            sb.append(h).append(';');
        }
        return Integer.toHexString(sb.toString().hashCode()) + ":" + hashes.size();
    }

    /** 读清单指纹（无清单/坏文件返回空串）。 */
    public static String readManifestFingerprint(Path dir) {
        try {
            Path f = dir.resolve("memory_index_manifest.json");
            if (Files.exists(f)) {
                Manifest m = AiMemoryModels.GSON.fromJson(
                        Files.readString(f, StandardCharsets.UTF_8), Manifest.class);
                if (m != null && m.fingerprint != null) {
                    return m.fingerprint;
                }
            }
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("AiMemoryAudit", ignored);
        }
        return "";
    }

    /** 写清单（索引生成/重建后调用）。 */
    public static void writeManifest(Path dir, String fingerprint, int indexCount) {
        try {
            Files.createDirectories(dir);
            Manifest m = new Manifest();
            m.fingerprint = fingerprint == null ? "" : fingerprint;
            m.indexCount = indexCount;
            m.updatedAt = System.currentTimeMillis();
            Files.writeString(dir.resolve("memory_index_manifest.json"),
                    AiMemoryModels.GSON.toJson(m), StandardCharsets.UTF_8);
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("AiMemoryAudit", ignored);
        }
    }

    private static String quote(String s) {
        if (s == null) {
            return "\"\"";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }
}
