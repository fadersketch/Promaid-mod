package com.maidsmart.voice;

import com.maidsmart.memory.AiMemoryModels;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v1.5.198：系统语音包——config/maid_smart/system_voice/ 下的 manifest.json + ogg 音频。
 *
 * 把"系统消息文本 → 音频"映射落盘（一次制作，永久使用），系统气泡命中后
 * 免 TTS 直接播放（不每次调用 TTS API）。格式：
 * {"entries":[
 *   {"text":"建好啦！","file":"build_done.ogg"},
 *   {"text":"材料不够","file":"no_material.ogg","mode":"contains"}
 * ]}
 * mode 省略 = exact（文本完全相等）；contains = 文本包含（按 manifest 顺序匹配）。
 * 音频文件相对 manifest 所在目录（可放子目录，file 写相对路径）。
 */
public final class SystemVoicePack {
    private static final String MANIFEST_FILE = "manifest.json";
    private static final Map<String, byte[]> BYTES_CACHE = new ConcurrentHashMap<>();

    /** manifest 条目 */
    public static class Entry {
        public String text;
        public String file;
        public String mode;
    }

    /** manifest 根 */
    public static class ManifestData {
        public List<Entry> entries;
    }

    private static volatile List<Entry> entries = Collections.emptyList();

    private SystemVoicePack() {
    }

    /** 系统语音包目录：config/maid_smart/system_voice/ */
    public static Path rootDir() {
        return net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                .resolve("maid_smart").resolve("system_voice");
    }

    /** 从磁盘（重新）加载 manifest；无 manifest 时清空条目 */
    public static void reload() {
        entries = Collections.emptyList();
        BYTES_CACHE.clear();
        Path manifest = rootDir().resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifest)) {
            return;
        }
        try {
            String json = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
            ManifestData data = AiMemoryModels.GSON.fromJson(json, ManifestData.class);
            List<Entry> list = new ArrayList<>();
            if (data != null && data.entries != null) {
                for (Entry e : data.entries) {
                    if (e != null && e.text != null && !e.text.isBlank() && e.file != null && !e.file.isBlank()) {
                        list.add(e);
                    }
                }
            }
            entries = list;
        } catch (Exception e) {
            entries = Collections.emptyList();
        }
    }

    /** 当前已加载映射条数（面板状态显示用） */
    public static int entryCount() {
        return entries.size();
    }

    /**
     * 匹配系统消息文本 → 命中返回 ogg 字节（惰性读取并缓存），未命中返回 null。
     * 门禁：TTS_VOICE_PACK_ENABLED 关闭时直接返回 null（走 TTS 缓存/合成）。
     */
    public static byte[] match(String text) {
        if (!com.maidsmart.config.MaidSmartConfig.TTS_VOICE_PACK_ENABLED.get()) {
            return null;
        }
        if (text == null || entries.isEmpty()) {
            return null;
        }
        String t = text.trim();
        for (Entry e : entries) {
            if (e.text == null || e.file == null) {
                continue;
            }
            boolean hit = "contains".equalsIgnoreCase(e.mode)
                    ? t.contains(e.text)
                    : t.equals(e.text);
            if (hit) {
                return bytesOf(e.file);
            }
        }
        return null;
    }

    /** 读取语音包内音频字节（惰性 + 内存缓存） */
    private static byte[] bytesOf(String file) {
        byte[] cached = BYTES_CACHE.get(file);
        if (cached != null) {
            return cached;
        }
        try {
            Path p = rootDir().resolve(file).normalize();
            if (!p.startsWith(rootDir()) || !Files.isRegularFile(p)) {
                return null;
            }
            byte[] data = Files.readAllBytes(p);
            if (data.length > 0) {
                BYTES_CACHE.put(file, data);
                return data;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 导入语音包（设置面板填写路径后由服务端调用）：文件夹或 .zip，拷贝
     * manifest.json + 全部 .ogg（保留相对路径）到 system_voice/ 并重载。
     * 返回给玩家的结果文本。
     */
    public static String importPack(String pathStr) {
        try {
            if (pathStr.contains("..")) {
                return "导入失败：路径不合法（包含 ..）";
            }
            Path src = Path.of(pathStr.trim());
            if (!Files.exists(src)) {
                return "导入失败：路径不存在（" + pathStr + "）";
            }
            Path dir = rootDir();
            Files.createDirectories(dir);
            int copied = 0;
            if (Files.isDirectory(src)) {
                try (java.util.stream.Stream<Path> walk = Files.walk(src)) {
                    for (Path p : (Iterable<Path>) walk::iterator) {
                        if (Files.isRegularFile(p) && isPackFile(p.getFileName().toString())) {
                            Path out = dir.resolve(src.relativize(p).toString()).normalize();
                            if (out.startsWith(dir)) {
                                Files.createDirectories(out.getParent());
                                Files.copy(p, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                copied++;
                            }
                        }
                    }
                }
            } else if (pathStr.trim().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(src.toFile())) {
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zip.entries();
                    while (en.hasMoreElements()) {
                        java.util.zip.ZipEntry ze = en.nextElement();
                        if (ze.isDirectory()) {
                            continue;
                        }
                        String name = ze.getName();
                        int slash = name.lastIndexOf('/');
                        String base = slash >= 0 ? name.substring(slash + 1) : name;
                        if (!isPackFile(base)) {
                            continue;
                        }
                        Path out = dir.resolve(name.replace('/', java.io.File.separatorChar)).normalize();
                        if (!out.startsWith(dir)) {
                            continue; // zip-slip 防护
                        }
                        Files.createDirectories(out.getParent());
                        try (java.io.InputStream in = zip.getInputStream(ze)) {
                            Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                        copied++;
                    }
                }
            } else {
                return "导入失败：路径必须是文件夹或 .zip 文件（" + pathStr + "）";
            }
            reload();
            return "语音包导入完成：复制 " + copied + " 个文件（manifest + ogg），当前已加载 "
                    + entryCount() + " 条文本映射。";
        } catch (Exception e) {
            return "语音包导入失败：" + e.getClass().getSimpleName() + " " + e.getMessage();
        }
    }

    private static boolean isPackFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return MANIFEST_FILE.equalsIgnoreCase(lower) || lower.endsWith(".ogg");
    }
}
