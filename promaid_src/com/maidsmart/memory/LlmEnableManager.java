package com.maidsmart.memory;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * per-maid 大语言模型开关（v1.0.3）——手册女仆记忆页「LLM:开/关」。
 *
 * 语义:该女仆的大语言模型对话是否启用（默认开）。关闭后:
 * - TLM 原版 AI 聊天(MaidAIChatManager.chat)被 MaidChatLlmGateMixin 拦截,不发请求;
 * - heartfelt 的主动对话(家庭互动/纪念日/告白等,chatWithQuota)检查同一 NBT 标记,
 *   直接降级为固定文本气泡(零跨 mod 依赖——只读 persistentData 字符串);
 * - promaid 主动对话走同一 chat 入口,同样被拦截。
 *
 * 存储与 AiMemoryManager 记忆开关同构:persistentData("maid_smart_llm", Byte;
 * 无=默认开) + 磁盘备份(llm_toggles.json,防实体卸载/重载丢失) + 客户端缓存
 * (S2C 广播,persistentData 不同步客户端,不广播则界面显示回"开")。
 */
public final class LlmEnableManager {
    /** per-maid 开关(persistentData key;heartfelt 侧只读此标记做降级) */
    public static final String PERSIST_TAG = "maid_smart_llm";

    /** 磁盘备份:maidUuid -> enabled(防 persistentData 丢失后开关回弹) */
    private static final ConcurrentHashMap<String, Boolean> DISK_TOGGLES = new ConcurrentHashMap<>();
    private static Path togglesFile = null;

    /** 客户端缓存服务端广播状态(客户端 isEnabled 优先读;服务端不写) */
    private static final ConcurrentHashMap<String, Boolean> CLIENT_STATE = new ConcurrentHashMap<>();

    private LlmEnableManager() {
    }

    /** 客户端接收 S2C LlmStateSyncPacket 后写入(仅客户端调用) */
    public static void pushClientState(String maidUuid, boolean enabled) {
        CLIENT_STATE.put(maidUuid, enabled);
    }

    /** 该女仆的 LLM 是否启用(默认开;per-maid 覆盖) */
    public static boolean isEnabled(EntityMaid maid) {
        if (maid == null || maid.m_9236_() == null) {
            return true;
        }
        // 客户端:优先服务端广播的缓存值
        if (maid.m_9236_().m_5776_()) {
            Boolean cached = CLIENT_STATE.get(maid.m_20148_().toString());
            if (cached != null) {
                return cached;
            }
        }
        // 服务端:磁盘优先(实体未加载时开关也可写磁盘生效),再 persistentData
        if (maid.m_9236_() instanceof ServerLevel sLevel) {
            togglesFile(sLevel);
            Boolean disk = DISK_TOGGLES.get(maid.m_20148_().toString());
            if (disk != null) {
                return disk;
            }
        }
        net.minecraft.nbt.CompoundTag pd = maid.getPersistentData();
        if (pd.m_128425_(PERSIST_TAG, 1)) {
            return pd.m_128435_(PERSIST_TAG) != 0;
        }
        return true; // 默认开
    }

    /** 设置 per-maid LLM 开关(手册女仆记忆页调用) */
    public static void setEnabled(EntityMaid maid, boolean enabled) {
        maid.getPersistentData().m_128379_(PERSIST_TAG, enabled);
        if (maid.m_9236_() instanceof ServerLevel level) {
            DISK_TOGGLES.put(maid.m_20148_().toString(), enabled);
            saveDiskToggles(level);
        }
    }

    /** 仅写磁盘(实体未加载/找不到时用;isEnabled 磁盘优先,加载后立即生效) */
    public static void setEnabledDiskOnly(String maidUuid, boolean enabled, ServerLevel level) {
        DISK_TOGGLES.put(maidUuid, enabled);
        saveDiskToggles(level);
    }

    /** 加载/定位 llm_toggles.json(惰性;首次调用才读盘) */
    private static Path togglesFile(ServerLevel level) {
        if (togglesFile == null) {
            togglesFile = AiMemoryExtractor.memoryRoot(level.m_7654_()).resolve("llm_toggles.json");
            try {
                if (Files.exists(togglesFile)) {
                    String json = Files.readString(togglesFile, StandardCharsets.UTF_8);
                    java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<
                            java.util.HashMap<String, Boolean>>() {
                    }.getType();
                    java.util.HashMap<String, Boolean> loaded = AiMemoryModels.GSON.fromJson(json, type);
                    if (loaded != null) {
                        DISK_TOGGLES.putAll(loaded);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return togglesFile;
    }

    private static void saveDiskToggles(ServerLevel level) {
        try {
            Files.writeString(togglesFile(level), AiMemoryModels.GSON.toJson(DISK_TOGGLES),
                    StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }
}
