package com.maidsmart.flight;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 仿创造飞行 · per-maid 开关——全局总闸 + 每只女仆可单独关掉。
 *
 * 【语义】全局配置 {@code freeFlight} = **总闸**（默认关）；per-maid 开关**默认跟随全局**（无标记 =
 * 跟随），玩家可在女仆配置界面把某一只单独关掉。故 {@code effective() = 总闸开 且 这只没被显式关掉}。
 * 【为什么"磁盘备份 + 客户端缓存"两套】（照搬 AiMemoryManager 的既有教训）
 * ① 磁盘备份：标记存在 {@code persistentData} 里，只随实体 NBT 保存——女仆被魂符收走/实体重建/跨维度
 * 时可丢，丢了会回落成"跟随全局"（"我明明关了她"→"她又飞了"），故 setServer 同时写一份磁盘备份。
 * ② 客户端缓存：{@code persistentData} 只在服务端读写、不同步客户端，界面重开读到过期值；服务端每次
 * 改动 S2C 广播 {@code FreeFlightStatePacket}，客户端写缓存。
 * ③ **绝不用 TLM 的 TaskData 存**：TLM 的 {@code TaskDataRegister.writeSyncData} 会把编码结果强转
 * CompoundTag → 一点开关就 ClassCastException 崩服。
 */
public final class MaidFreeFlightFlags {

    private MaidFreeFlightFlags() {
    }

    /** per-maid 显式标记（persistentData key；无 = 跟随全局） */
    private static final String PERSIST_TAG = "maid_smart_free_flight";
    /** 服务端磁盘备份（UUID → 显式值） */
    private static final Map<String, Boolean> DISK = new HashMap<>();
    /** 客户端缓存（服务端 S2C 广播写入） */
    private static final Map<String, Boolean> CLIENT_STATE = new HashMap<>();
    private static boolean diskLoaded = false;

    /* ---------------- 通用 ---------------- */

    /** 全局总闸是否开着（配置读取，客户端也读得到——COMMON 配置会同步） */
    public static boolean globalOn() {
        try {
            return MaidSmartConfig.MISC_FREE_FLIGHT.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFreeFlightFlags", ignored);
            return false;
        }
    }

    /** 最终是否启用这只女仆的仿创造飞行：总闸开 且 没被单独关掉 */
    public static boolean effective(EntityMaid maid) {
        if (maid == null || !globalOn()) {
            return false;
        }
        return !Boolean.FALSE.equals(explicitServer(maid));
    }

    /* ---------------- 服务端 ---------------- */

    /** 服务端读显式标记：persistentData → 磁盘备份 → null（= 跟随全局） */
    public static Boolean explicitServer(EntityMaid maid) {
        try {
            var data = ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData();
            if (data.contains(PERSIST_TAG)) {
                return data.getBoolean(PERSIST_TAG);
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFreeFlightFlags", ignored);
        }
        loadDisk();
        return DISK.get(maid.getUUID().toString());
    }

    /** 服务端写显式标记（persistentData + 磁盘备份） */
    public static void setServer(EntityMaid maid, boolean value) {
        try {
            ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid)
                    .getPersistentData().putBoolean(PERSIST_TAG, value);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFreeFlightFlags", ignored);
        }
        loadDisk();
        DISK.put(maid.getUUID().toString(), value);
        saveDisk();
    }

    /* ---------------- 客户端 ---------------- */

    /** 客户端收到 S2C 状态包时写入（仅客户端） */
    public static void pushClientState(String maidUuid, Boolean value) {
        if (maidUuid != null) {
            CLIENT_STATE.put(maidUuid, value);
        }
    }

    /** 客户端缓存的显式值（null = 未知/跟随全局） */
    public static Boolean cachedClient(String maidUuid) {
        return CLIENT_STATE.get(maidUuid);
    }

    /* ---------------- 磁盘备份 ---------------- */

    private static Path diskPath() {
        try {
            return FMLPaths.GAMEDIR.get().resolve("promaid_free_flight_toggles.json");
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFreeFlightFlags", ignored);
            return null;
        }
    }

    private static synchronized void loadDisk() {
        if (diskLoaded) {
            return;
        }
        diskLoaded = true;
        try {
            Path p = diskPath();
            if (p == null || !Files.exists(p)) {
                return;
            }
            String txt = Files.readString(p);
            txt = txt.trim();
            if (txt.startsWith("{")) {
                txt = txt.substring(1);
            }
            if (txt.endsWith("}")) {
                txt = txt.substring(0, txt.length() - 1);
            }
            for (String pair : txt.split(",")) {
                String[] kv = pair.split(":");
                if (kv.length == 2) {
                    String k = kv[0].trim().replace("\"", "");
                    String v = kv[1].trim().replace("\"", "");
                    if (!k.isEmpty()) {
                        DISK.put(k, Boolean.parseBoolean(v));
                    }
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFreeFlightFlags", ignored);
        }
    }

    private static synchronized void saveDisk() {
        try {
            Path p = diskPath();
            if (p == null) {
                return;
            }
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Boolean> e : DISK.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
            }
            sb.append('}');
            Files.writeString(p, sb.toString());
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFreeFlightFlags", ignored);
        }
    }
}
