package com.maidsmart.voice;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.TTSCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSConfig;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSystemServices;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.network.NetworkHandler;
import com.github.tartaricacid.touhoulittlemaid.network.message.ai.TTSAudioToClientPackage;
import com.github.tartaricacid.touhoulittlemaid.network.message.ai.TTSSystemAudioToClientPackage;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * v1.5.198：系统消息 TTS——所有系统气泡（感知/工作/自保/建好啦等规则消息，
 * 由 ChatBubbleLimitMixin 拦截 addTextChatBubble 汇入本管理器）朗读：
 * ① 内置日语语音包命中（随 jar 分发，最高优先级）→ 直接播放（免 TTS，独立最小间隔）
 * ② 系统语音包命中（config/maid_smart/system_voice/）→ 直接播放（免 TTS）
 * ③ 语音缓存命中（config/maid_smart/voice_cache/<sha256>.ogg，"训练一次保存"）→ 直接播放
 * ④ 未命中 → 调 TLM TTS 站点合成，字节落盘缓存后播放（SystemTtsCallback）
 *
 * 门禁：TTS_SYSTEM_ENABLED + TLM AIConfig.TTS_ENABLED + 站点存在启用 + 有主人 +
 * 文本可朗读（含中文/空格，过滤 TLM 翻译 key 与省略号）+ per-maid 冷却。
 * 注意：① 内置语音包在站点/总开关检查之前——没配 TTS 站点也能响（用户要求"触发即播放"）。
 */
public final class SystemTTSManager {
    /** 每只女仆上次朗读时间（UUID → 时间戳） */
    private static final Map<UUID, Long> LAST_SPEAK = new ConcurrentHashMap<>();
    /** v1.1.0 实测四百二十：每只女仆上次内置日语语音包播放时间（独立最小间隔门禁） */
    private static final Map<UUID, Long> LAST_JAR_PACK = new ConcurrentHashMap<>();

    /** 审计：女仆卸载/移除时清理 TTS 限频表 */
    public static void forgetMaid(UUID maidUuid) {
        LAST_SPEAK.remove(maidUuid);
        LAST_JAR_PACK.remove(maidUuid);
    }

    private SystemTTSManager() {
    }

    /** 语音缓存目录：config/maid_smart/voice_cache/ */
    public static Path cacheDir() {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                .resolve("maid_smart").resolve("voice_cache");
    }

    /** 系统气泡朗读入口（服务端线程调用；fire-and-forget，全部异常静默） */
    public static void speak(EntityMaid maid, String text) {
        try {
            if (maid == null || text == null || text.isBlank()) {
                return;
            }
            // 实测四百二十九：睡觉中不朗读（气泡同样被 ChatBubbleLimitMixin 静默）
            if (maid.isSleeping()) {
                return;
            }
            if (!(maid.level() instanceof ServerLevel level)) {
                return;
            }
            if (!(maid.getOwner() instanceof ServerPlayer owner)) {
                return;
            }
            if (!speakable(text)) {
                return;
            }
            // ① 内置日语语音包（随 jar 分发，最高优先级——用户要求：触发系统消息即自动
            //    播放）。【独立门禁】只认 TTS_JAR_PACK_ENABLED + 自带最小间隔，不要求
            //    TLM 的 TTS 站点/总开关——本地有音频，开箱即用；只发文件名（客户端从
            //    自己 jar 取字节），省流量且两端一致。
            if (MaidSmartConfig.TTS_JAR_PACK_ENABLED.get()) {
                // 实测四百四十五：先做【命中判定】再谈最小间隔——旧版把间隔判断写在
                // 命中之前，命中但被间隔挡下时会继续往下走，最终落到 ④ TLM TTS 合成
                // （用户实测：「触发了 [警示]落地水，却没有内置语音，还是人机语音」）。
                // 现在：命中即由内置包负责——被间隔挡下就【静默】，绝不回落 TTS 合成。
                String jarKey = JarVoicePack.matchKey(text);
                if (jarKey != null) {
                    long nowJar = System.currentTimeMillis();
                    int jarCdMs = MaidSmartConfig.TTS_JAR_PACK_MIN_INTERVAL_S.get() * 1000;
                    Long lastJar = LAST_JAR_PACK.get(maid.getUUID());
                    boolean throttled = jarCdMs > 0 && lastJar != null && nowJar - lastJar < jarCdMs;
                    if (throttled && !bypassJarInterval(jarKey)) {
                        return; // 间隔内不重复轰炸，但也不换成人机语音
                    }
                    LAST_JAR_PACK.put(maid.getUUID(), nowJar);
                    sendJarVoice(owner, maid, jarKey);
                    return;
                }
            }
            // ---- 以下为 TTS 合成路径（需要 TLM TTS 总开关 + 站点 + per-maid 冷却）----
            if (!MaidSmartConfig.TTS_SYSTEM_ENABLED.get()) {
                return;
            }
            if (!AIConfig.TTS_ENABLED.get()) {
                return;
            }
            // per-maid 冷却（防连续气泡轰炸 TTS）
            long now = System.currentTimeMillis();
            int cdMs = MaidSmartConfig.TTS_SYSTEM_COOLDOWN_S.get() * 1000;
            if (cdMs > 0) {
                Long last = LAST_SPEAK.get(maid.getUUID());
                if (last != null && now - last < cdMs) {
                    return;
                }
                LAST_SPEAK.put(maid.getUUID(), now);
            }
            if (maid.getAiChatManager() == null) {
                return;
            }
            TTSSite site = maid.getAiChatManager().getTTSSite();
            if (site == null || !site.enabled()) {
                return;
            }
            TTSClient client = site.client();
            if (client == null) {
                return;
            }
            String model = maid.getAiChatManager().getTTSModel();
            String lang = "en";
            String[] split = maid.getAiChatManager().getTTSLanguage().split("_");
            if (split.length >= 2) {
                lang = split[0];
            }
            // ① 系统语音包命中（免 TTS）
            byte[] pack = SystemVoicePack.match(text);
            if (pack != null) {
                sendToOwner(owner, maid, pack);
                return;
            }
            // ② 语音缓存命中（训练一次保存，此后直接复用）
            String key = sha256(text + "|" + model + "|" + lang);
            Path cache = cacheDir().resolve(key + ".ogg");
            if (Files.isRegularFile(cache)) {
                try {
                    byte[] data = Files.readAllBytes(cache);
                    if (data.length > 0) {
                        sendToOwner(owner, maid, data);
                        return;
                    }
                } catch (Exception ignored) {
                }
            }
            // ③ 未命中 → 调 TLM TTS 站点合成（SystemTtsCallback 落盘缓存 + 播放）
            TTSConfig config = new TTSConfig(model, lang);
            if (client instanceof TTSSystemServices services) {
                // system 叙述者/player2 本地播放（无字节可缓存，实时播放）
                NetworkHandler.sendToClientPlayer(
                        new TTSSystemAudioToClientPackage(site.id(), text, config, services), owner);
            } else {
                client.play(text, config, new SystemTtsCallback(maid, text, 0L, cache));
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 实测四百四十五：这些短促关键台词不受「内置包最小间隔」限制——被间隔吞掉时
     * 玩家会以为语音包失效（落地水/落地雪是自行动作反馈；敌人靠近是敌袭预警）。
     */
    private static boolean bypassJarInterval(String jarKey) {
        return "clutch_water.ogg".equals(jarKey)
                || "clutch_snow.ogg".equals(jarKey)
                || "enemy_near.ogg".equals(jarKey);
    }

    /** 状态文本（设置面板"查看语音包状态"） */
    public static String statusText() {
        return "内置日语语音包：已加载 " + JarVoicePack.entryCount() + " 条文本映射（随 jar 分发，最高优先级）"
                + "；系统语音包（磁盘）：已加载 " + SystemVoicePack.entryCount() + " 条文本映射"
                + "；TTS 语音缓存 " + cacheCount() + " 个音频文件（voice_cache/，训练一次保存后复用）。";
    }

    /** 语音缓存文件数 */
    public static int cacheCount() {
        try {
            Path dir = cacheDir();
            if (!Files.isDirectory(dir)) {
                return 0;
            }
            try (Stream<Path> list = Files.list(dir)) {
                return (int) list.filter(Files::isRegularFile).count();
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /** 文本可朗读：含中文（过滤 TLM 翻译 key "ai.touhou_little_maid.xxx"）或含空格（自然句子） */
    private static boolean speakable(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return text.contains(" ");
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** 把音频字节发给主人客户端（客户端 MaidAISoundInstance 播放，音量走 TtsVolumeHandler 倍率） */
    private static void sendToOwner(ServerPlayer owner, EntityMaid maid, byte[] data) {
        try {
            NetworkHandler.sendToClientPlayer(new TTSAudioToClientPackage(maid.getId(), data), owner);
        } catch (Exception ignored) {
        }
    }

    /** v1.1.0 实测四百二十：内置语音包——只发文件名（客户端从自己 jar 取字节播放） */
    private static void sendJarVoice(ServerPlayer owner, EntityMaid maid, String file) {
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(owner,
                    new com.maidsmart.build.BlueprintBookNetworking.PlayJarVoicePacket(
                            maid.getId(), file));
        } catch (Exception ignored) {
        }
    }

    /** 落盘缓存 + 超出上限删最旧 */
    private static void saveToCache(Path cacheFile, byte[] data) {
        try {
            Path dir = cacheFile.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Files.write(cacheFile, data);
            int max = MaidSmartConfig.TTS_CACHE_MAX_FILES.get();
            if (dir == null || !Files.isDirectory(dir)) {
                return;
            }
            List<Path> files = new ArrayList<>();
            try (Stream<Path> list = Files.list(dir)) {
                list.filter(Files::isRegularFile).forEach(files::add);
            }
            if (files.size() > max) {
                files.sort(Comparator.comparingLong(p -> {
                    try {
                        return Files.getLastModifiedTime(p).toMillis();
                    } catch (Exception e) {
                        return 0L;
                    }
                }));
                for (int i = 0; i < files.size() - max; i++) {
                    try {
                        Files.deleteIfExists(files.get(i));
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 系统消息专用 TTS 回调：成功 → 字节落盘缓存（"训练一次保存"，下次直接复用）
     * + 发给主人播放；不调 addLLMChatText（系统气泡已展示，不重复刷）。
     * 失败 → 只记日志（不弹错误、不刷 LLM 文本）。
     */
    private static final class SystemTtsCallback extends TTSCallback {
        private final Path cacheFile;

        SystemTtsCallback(EntityMaid maid, String chatText, long waitingChatBubbleId, Path cacheFile) {
            super(maid, chatText, waitingChatBubbleId);
            this.cacheFile = cacheFile;
        }

        @Override
        public void onSuccess(byte[] data) {
            try {
                if (data == null || data.length == 0) {
                    return;
                }
                EntityMaid maid = this.getMaid();
                if (!(maid.level() instanceof ServerLevel level)) {
                    return;
                }
                if (!(maid.getOwner() instanceof ServerPlayer owner)) {
                    return;
                }
                MinecraftServer server = level.getServer();
                server.submit(() -> {
                    try {
                        saveToCache(cacheFile, data);
                        sendToOwner(owner, maid, data);
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception ignored) {
            }
        }

        @Override
        public void onFailure(java.net.http.HttpRequest request, Throwable throwable, int errorCode) {
            com.mojang.logging.LogUtils.getLogger().warn(
                    "promaid 系统消息 TTS 失败: {}", throwable != null ? throwable.getMessage() : "null");
        }
    }
}
