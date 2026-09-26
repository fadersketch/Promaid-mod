package com.maidsmart.flight;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 实测六百七十八【仿创造飞行 · per-maid 开关】——全局总闸 + 每只女仆可单独关掉。
 *
 * 【语义（需求方拍板）】全局配置 `freeFlight` = **总闸**（默认关）；per-maid 开关
 * **默认跟随全局**（无标记 = 跟随），玩家可以在女仆配置界面里把某一只单独关掉。
 * 于是 `effective() = 总闸开 且 这只没被显式关掉`。
 *
 * 【为什么要"磁盘备份 + 客户端缓存"两套】（照搬 AiMemoryManager 的既有教训）
 * <ul>
 *   <li>**磁盘备份**：per-maid 标记存在 `persistentData` 里，而它只随实体 NBT 保存——
 *       女仆被魂符收走/实体重建/跨维度时可以丢；丢了就会回落成"跟随全局"，于是
 *       "我明明关了她"变成"她又飞了"。所以 setServer 同时写一份磁盘备份，
 *       读取时 persistentData 没有就回落它。</li>
 *   <li>**客户端缓存**：`persistentData` 只在服务端读写、不同步客户端，界面重开时
 *       客户端会读到过期值（关了的开关又显示回"开"）。服务端每次改动后 S2C 广播
 *       {@code FreeFlightStatePacket}，客户端写进缓存。</li>
 *   <li>**绝不用 TLM 的 TaskData 存**：同类开关早年用过，TLM 的 `TaskDataRegister.writeSyncData`
 *       会把编码结果强转 CompoundTag → 一点开关就 ClassCastException 崩服（AiMemoryManager 实测）。</li>
 * </ul>
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
        } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
        }
        loadDisk();
        return DISK.get(maid.getUUID().toString());
    }

    /** 服务端写显式标记（persistentData + 磁盘备份） */
    public static void setServer(EntityMaid maid, boolean value) {
        try {
            ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid)
                    .getPersistentData().putBoolean(PERSIST_TAG, value);
        } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
        }
    }
}
