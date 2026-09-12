package com.maidsmart.voice;

import com.maidsmart.memory.AiMemoryModels;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v1.1.0 实测四百二十：内置日语语音包（打进 mod jar：assets/promaid/voice/）。
 *
 * 用户需求：「帮我训练一个语音包，日语……训练的内容就是目前游戏里的那些系统消息语音，
 * 打包放进 .jar 里。效果就是当触发了这些系统消息之后，会自动播放这个语音。」
 *
 * 与 SystemVoicePack（磁盘包 config/maid_smart/system_voice/）互补：
 * - 本包随 jar 分发，开箱即用、**优先级最高**（SystemTTSManager 先问本包，
 *   再问磁盘包，再问 TTS 缓存，最后才合成）；
 * - manifest.json + <key>.ogg 全部在 jar 内的 assets/promaid/voice/ 下，
 *   用 classloader 读取（不落盘）。
 *
 * 匹配语义与磁盘包完全一致：manifest 顺序自上而下，mode 省略=exact，
 * contains=文本包含（所以更具体的模式必须排在前面）。
 */
public final class JarVoicePack {
    private static final String ROOT = "/assets/promaid/voice/";
    private static final String MANIFEST = ROOT + "manifest.json";
    private static final Map<String, byte[]> BYTES_CACHE = new ConcurrentHashMap<>();

    /** manifest 条目（与 SystemVoicePack.Entry 同构） */
    public static class Entry {
        public String text;
        public String file;
        public String mode;
    }

    public static class ManifestData {
        public List<Entry> entries;
    }

    private static volatile List<Entry> entries = Collections.emptyList();
    private static volatile boolean loaded = false;

    private JarVoicePack() {
    }

    /** 懒加载（首次使用时读 jar 内 manifest；失败则视为空包，不抛） */
    private static List<Entry> entries() {
        if (loaded) {
            return entries;
        }
        synchronized (JarVoicePack.class) {
            if (loaded) {
                return entries;
            }
            List<Entry> list = new ArrayList<>();
            try (InputStream in = JarVoicePack.class.getResourceAsStream(MANIFEST)) {
                if (in != null) {
                    String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    ManifestData data = AiMemoryModels.GSON.fromJson(json, ManifestData.class);
                    if (data != null && data.entries != null) {
                        for (Entry e : data.entries) {
                            if (e != null && e.text != null && !e.text.isBlank()
                                    && e.file != null && !e.file.isBlank()) {
                                list.add(e);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            entries = list;
            loaded = true;
            return entries;
        }
    }

    /** 当前已加载映射条数（面板状态显示用） */
    public static int entryCount() {
        return entries().size();
    }

    /**
     * 匹配系统消息文本 → 命中返回 jar 内音频文件名（如 "build_done.ogg"），未命中 null。
     * 服务端把文件名发给客户端，客户端从自己 jar 里取字节播放——省流量且两端一致。
     */
    public static String matchKey(String text) {
        if (text == null) {
            return null;
        }
        List<Entry> list = entries();
        if (list.isEmpty()) {
            return null;
        }
        String t = text.trim();
        for (Entry e : list) {
            boolean hit = "contains".equalsIgnoreCase(e.mode)
                    ? t.contains(e.text)
                    : t.equals(e.text);
            if (hit) {
                return e.file;
            }
        }
        return null;
    }

    /**
     * 匹配系统消息文本 → 命中返回 ogg 字节（惰性读取 + 内存缓存），未命中返回 null。
     */
    public static byte[] match(String text) {
        String key = matchKey(text);
        return key == null ? null : bytesOfKey(key);
    }

    /** 按文件名取 jar 内音频字节（客户端按服务端下发的 key 播放） */
    public static byte[] bytesOfKey(String file) {
        return file == null || file.isBlank() ? null : bytesOf(file);
    }

    /** 读取 jar 内音频字节（惰性 + 内存缓存） */
    private static byte[] bytesOf(String file) {
        byte[] cached = BYTES_CACHE.get(file);
        if (cached != null) {
            return cached;
        }
        try (InputStream in = JarVoicePack.class.getResourceAsStream(ROOT + file)) {
            if (in == null) {
                return null;
            }
            byte[] data = in.readAllBytes();
            if (data.length > 0) {
                BYTES_CACHE.put(file, data);
                return data;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
