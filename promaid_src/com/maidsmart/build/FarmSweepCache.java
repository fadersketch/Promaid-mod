package com.maidsmart.build;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v1.5.386：农场冷却表普通工具类（从 FarmSweepMixin 抽出）。
 *
 * 根因：AiMemoryManager.onEntityLeaveLevel 直接引用了 com.maidsmart.mixin.FarmSweepMixin
 * 的静态方法 forgetMaid。Mixin 类不能被普通 classloader 当作普通类加载——
 * Mixin 系统会把它们标记为 "invalid"，触发 NoClassDefFoundError（crash-2026-
 * 08-17_15.24.41：交互女仆触发实体离开事件 → AiMemoryManager.onEntityLeaveLevel
 * → 引用 FarmSweepMixin → ModLauncherClassTracker.handlesClass 抛 NoClassDefFoundError）。
 *
 * 修复：把静态冷却表和 forgetMaid 提到这个普通工具类，FarmSweepMixin 委托到这里，
 * AiMemoryManager 也改为引用本类。Mixin 类不再被业务代码直接引用。
 */
public final class FarmSweepCache {
    private FarmSweepCache() {
    }

    /** 连锁收割冷却（10 tick）——见 FarmSweepMixin.sweepChain */
    public static final java.util.Map<String, Long> HARVEST_CD =
            new ConcurrentHashMap<>();

    /** 批量种植冷却（40 tick / 2 秒）——见 FarmSweepMixin.batchPlantAround */
    public static final java.util.Map<String, Long> PLANT_CD =
            new ConcurrentHashMap<>();

    /** 审计：女仆卸载/移除时清理农场冷却表 */
    public static void forgetMaid(UUID maidUuid) {
        String key = maidUuid.toString();
        HARVEST_CD.remove(key);
        PLANT_CD.remove(key);
    }
}
