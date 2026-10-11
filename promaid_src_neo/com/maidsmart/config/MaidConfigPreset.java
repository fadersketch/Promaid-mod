package com.maidsmart.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 预设模式（实测八百三十五）：把全模组配置一次性切成「默认」或「DIY」两套。
 *
 * <p>「默认」= 出厂推荐配置（逐项 {@code set(getDefault())}，AI 接口密钥与手动维护的数据表保留）；
 * 「DIY」= 关掉一切会改变女仆行为的开关，只留纯附加子系统（记忆/感知/情绪/对话/语音/手册/
 * 压缩盒/指标石/HUD）与若干护栏、修复项，让玩家从「像 TLM 原版」出发自己挑加强。
 *
 * <p>枚举走反射（{@code MaidSmartConfig} 的全部 static ConfigValue 字段）——以后加配置自动纳入，
 * 不需要维护第二份清单；分类只靠下面的 TOML 路径表。
 */
public final class MaidConfigPreset {
    /** 模式取值：出厂推荐配置 */
    public static final String MODE_DEFAULT = "default";
    /** 模式取值：不介入女仆行为 */
    public static final String MODE_DIY = "diy";

    /** 本项自身（只记录"上次点了哪个模式"，永不当成普通开关改写） */
    private static final String PATH_PRESET_MODE = "misc.presetMode";

    /**
     * 内部簿记，两边都不碰：一次性默认值迁移标记。
     *
     * <p>它们记的是"这条迁移跑过没"，复位成 false 会让迁移在下次启动重跑一遍——不是玩家设置。
     */
    private static final java.util.Set<String> SKIP = java.util.Set.of(
            "build.speedMigrated", "build.scheduleRangeMigrated", "build.strollSpeedMigrated",
            "build.defaultRepairMigrated", "broom.climbMigrated", "broom.climb12Migrated",
            "broom.airAltMigrated", "broom.orbitRadiusMigrated", "misc.dangerCampfireMigrated",
            "broom.patrolEnableMigrated");

    /**
     * DIY 不动的项：纯附加子系统、护栏、修复，以及"关掉反而更危险 / 只是把 bug 放回来"的那些。
     *
     * <p>判据 = 它不动 TLM 的女仆行为（只加我们自己的系统/显示/播报）；会改变女仆行为且有配置项的
     * 一律不收——它们随 DIY 关，代价是有意的：卡住不自愈、骑乘照原版搭方块、模组原木只认名单、不自取光源。
     */
    private static final java.util.Set<String> KEEP = java.util.Set.of(
            // ---- 建造：自己的道具与只在客户端画的投影；不强制 home 是"少管"的一档 ----
            "build.indexStone", "build.projection", "build.noForceHome",
            // ---- 挖矿 / 伐木：只剩"报点弃置"这类不改行为的报告项（自愈/防塌/免搭柱/自动标签已随 DIY 关） ----
            "mine.hardBlockReport", "wood.hardBlockReport",
            // ---- AI 记忆 ----
            "memory.enable", "memory.relationInject", "memory.conflictOverride", "memory.coreFold",
            "memory.workingNote", "memory.openEvents", "memory.lazySave", "memory.indexEnable",
            "memory.indexOnSleep", "memory.indexOnLogout", "memory.affectSnapshot", "memory.persona",
            "memory.carePoints", "memory.dualAgent", "memory.personaUnify", "memory.recallGate",
            "memory.graphEnable", "memory.temporalEnable", "memory.profileItems", "memory.snapshotEnable",
            "memory.continuityEnable", "memory.eventPolicy", "memory.auditEnable",
            // ---- 感知 / 情绪 ----
            "perception.enable", "perception.hostile", "perception.owner", "perception.weather",
            "affect.enable", "affect.inject",
            // ---- AI 工具：读写记忆保留；能直接指挥她行动的（做物/切任务/放方块/空袭/工作区）关掉 ----
            "aitools.remember", "aitools.workingNote", "aitools.perception",
            "aitools.workList", "aitools.ownerInventory", "aitools.readiness",
            // ---- 对话与播报（她说什么，不改变她做什么） ----
            "dialogue.statusReporter", "dialogue.proactive", "dialogue.replyFeedback", "dialogue.langCheck",
            // ---- 语音 / TTS ----
            "voice.systemEnabled", "voice.voicePackEnabled", "voice.jarPackEnabled", "voice.jarPackMuteNative",
            // ---- 手册与压缩盒（本模组自己的物品与界面） ----
            "compressionBox.maidExtension",
            // ---- 第三方模组让位（开着 = 我们少管，不该关） ----
            "compat.puppetBlacklist",
            // ---- 大运：只留"不拆箱子/照常掉落/照常粒子"三个安全项，总开关 / 玩家侧 / 免疫冲撞随 DIY 关 ----
            "accessory.daYunSkipContainers", "accessory.daYunDropItems", "accessory.daYunBreakParticles",
            // ---- v1.3.2 实测八百五十九：自保传送豁免坐/蹲（teleportExemptSitting）移出保留表——它改的是
            //      移动行为（坐/蹲女仆免于自保传送），随 DIY 关 ----
            // ---- 保护玩家已有配置 / 修 bug：罗盘圈不被覆盖、传送后重建、背包溢出兜底 ----
            "misc.compassPriority", "misc.maidTeleportRebuild", "misc.backpackOverflow",
            // ---- 自己的显示与标记 ----
            "misc.cooldownHud", "misc.workPosMarker", "misc.scheduleBubbleEnabled",
            // ---- 放置护栏：悬空禁搭、不搭在主人身上 ----
            "misc.noPlaceInAir", "misc.noPlaceOnOwner",
            // ---- 投弹/搭路的安全档 ----
            "bombing.dimensionGuard", "bombing.fireProtect", "bombing.anchorNeedsGlowstone",
            "bridge.buildOnlyNatural",
            // ---- 本项自身（只记录模式；模式的两个伴生总闸与它同源，DIY 时要一起关，故不进保留表） ----
            PATH_PRESET_MODE);

    /** 反语义项：默认 false 恰恰等于"我们介入了"，DIY 要显式置 true（回 TLM 原版惊慌） */
    private static final java.util.Set<String> FORCE_ON = java.util.Set.of("combat.fleeWithSaveItem");

    /**
     * 少数"数值本身即介入"的项：DIY 改写成的具体值。
     *
     * <p>布尔开关能表达"别管"，表达不了"按 TLM 原版的公式算"，这些项只能落到具体数字上。
     */
    private static final java.util.Map<String, Object> VALUES = java.util.Map.of(
            "combat.playerDamageMode", 0,        // 0 = TLM 原版规则：玩家伤害 ÷5、上限 2
            "combat.combatWorkRange", 0,         // 0 = 不接管战斗工作圈
            "combat.assistRadius", 0,            // 0 = 不援护、不撒手
            "combat.flightRecallDistance", 0,    // 0 = 空袭牵引绳关闭
            "broom.recallDistance", 0,           // 0 = 扫帚牵引绳关闭
            "build.speedTier", "x1",
            "ride.startupDelaySeconds", 0.0);            // x1 = 原速档（非加速档）

    /** 恢复出厂默认时要保留的项：个人凭据（重置不该丢钥匙） */
    private static final java.util.Set<String> RESET_KEEP = java.util.Set.of(
            "memory.apiUrl", "memory.apiKey", "memory.apiModel",
            "memory.embedUrl", "memory.embedKey", "memory.embedModel");

    /** 反射枚举结果缓存（{path, ConfigValue}），按路径排序 */
    private static java.util.List<Object[]> FIELDS;

    private MaidConfigPreset() {
    }

    /** 反射取全部配置项（静态 ConfigValue 字段）；失败返回空表（预设退化为什么都不做） */
    private static java.util.List<Object[]> fields() {
        java.util.List<Object[]> local = FIELDS;
        if (local != null) {
            return local;
        }
        java.util.List<Object[]> out = new java.util.ArrayList<>();
        try {
            for (java.lang.reflect.Field f : MaidSmartConfig.class.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        || !ModConfigSpec.ConfigValue.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                f.setAccessible(true);
                Object cv = f.get(null);
                if (cv == null) {
                    continue;
                }
                java.util.List<String> p = ((ModConfigSpec.ConfigValue<?>) cv).getPath();
                out.add(new Object[]{String.join(".", p), cv});
            }
        } catch (Throwable t) {
            com.maidsmart.tool.PromaidLog.log("预设模式", "反射读配置项失败：" + t);
        }
        out.sort((a, b) -> ((String) a[0]).compareTo((String) b[0]));
        FIELDS = out;
        return out;
    }

    /** 当前模式（配置未加载或值非法时按 {@link #MODE_DEFAULT} 处理，永远非 null） */
    public static String mode() {
        try {
            String v = MaidSmartConfig.MISC_PRESET_MODE.get();
            if (v != null && MODE_DIY.equalsIgnoreCase(v.trim())) {
                return MODE_DIY;
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidConfigPreset", ignored);
        }
        return MODE_DEFAULT;
    }

    /** 当前是不是 DIY 模式（供界面显示；行为开关各走自己的配置项） */
    public static boolean isDiy() {
        return MODE_DIY.equals(mode());
    }

    /** DIY 的目标值表（path → 目标值）；不改的项不出现 */
    private static java.util.LinkedHashMap<String, Object> diyTargets() {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        for (Object[] e : fields()) {
            String path = (String) e[0];
            if (SKIP.contains(path) || PATH_PRESET_MODE.equals(path)) {
                continue;
            }
            Object cur = read(e[1]);
            if (cur instanceof Boolean) {
                if (FORCE_ON.contains(path)) {
                    m.put(path, Boolean.TRUE);
                } else if (!KEEP.contains(path)) {
                    m.put(path, Boolean.FALSE);
                }
            } else if (VALUES.containsKey(path)) {
                m.put(path, VALUES.get(path));
            }
        }
        return m;
    }

    /**
     * 干跑：DIY 模式会改动哪些项（{@code 路径=目标值} 文本列表），只读不写。
     *
     * <p>给手册那一页显示条数、给日志对账、也给校验脚本当"分类有没有漂"的探针。
     */
    public static java.util.List<String> planDiy() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, Object> en : diyTargets().entrySet()) {
            Object[] e = byPath(en.getKey());
            if (!sameValue(e == null ? null : read(e[1]), en.getValue())) {
                out.add(en.getKey() + "=" + en.getValue());
            }
        }
        return out;
    }

    /** 应用 DIY 模式；返回真正改动了几项 */
    public static int applyDiy() {
        java.util.LinkedHashMap<String, Object> t = diyTargets();
        t.put(PATH_PRESET_MODE, MODE_DIY);
        return write(t);
    }

    /**
     * 恢复默认模式（逐项回到出厂默认）；返回真正改动了几项。
     *
     * <p>刻意保留：AI 接口密钥与端点（个人凭据）、{@code defineList} 的数据表（矿表/木材表/危险表等
     * 手工维护的名单）、一次性迁移标记——这三类被清掉是"丢数据"而不是"回默认"。
     */
    public static int applyFactoryDefault() {
        java.util.LinkedHashMap<String, Object> t = new java.util.LinkedHashMap<>();
        for (Object[] e : fields()) {
            String path = (String) e[0];
            if (SKIP.contains(path) || RESET_KEEP.contains(path)) {
                continue;
            }
            if (PATH_PRESET_MODE.equals(path)) {
                t.put(path, MODE_DEFAULT);
                continue;
            }
            Object def = defaultValue(e[1]);
            if (def == null || def instanceof java.util.List) {
                continue; // 手工维护的数据表（矿表/木材表/危险表…）不重置
            }
            t.put(path, def);
        }
        return write(t);
    }

    /** 逐项写值：单项失败不牵连其它项；有改动才落盘 + 刷新派生缓存 */
    private static int write(java.util.Map<String, Object> targets) {
        int changed = 0;
        java.util.Set<String> done = new java.util.HashSet<>();
        for (Object[] e : fields()) {
            String path = (String) e[0];
            if (!targets.containsKey(path) || !done.add(path)) {
                continue; // 同一路径可能被定义两次（如配置里的 patrolEnable 与 broom enable 撞键），只算一项
            }
            Object want = targets.get(path);
            try {
                Object cv = e[1];
                if (sameValue(read(cv), want)) {
                    continue;
                }
                setRaw(cv, want);
                changed++;
            } catch (Throwable t) {
                com.maidsmart.tool.PromaidLog.log("预设模式", "写不进配置项 " + path + "：" + t);
            }
        }
        if (changed > 0) {
            persistAndRefresh();
            com.maidsmart.tool.PromaidLog.log("预设模式", "预设应用完成：改动 " + changed + " 项");
        }
        return changed;
    }

    /** 写盘 + 刷新三张从配置派生的缓存（与配置面板「保存并返回」同口径） */
    private static void persistAndRefresh() {
        try {
            com.maidsmart.ProMaidMod.persistConfigQuietly();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidConfigPreset", ignored);
        }
        try {
            com.maidsmart.task.MaidMineBehavior.loadCustomOres();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidConfigPreset", ignored);
        }
        try {
            com.maidsmart.task.MaidWoodBehavior.loadCustomWoods();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidConfigPreset", ignored);
        }
        try {
            com.maidsmart.task.MaidCookBehavior.loadCookLists();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidConfigPreset", ignored);
        }
    }

    private static Object[] byPath(String path) {
        for (Object[] e : fields()) {
            if (path.equals(e[0])) {
                return e;
            }
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object read(Object cv) {
        if (cv == null) {
            return null;
        }
        try {
            return ((ModConfigSpec.ConfigValue) cv).get();
        } catch (Throwable t) {
             com.maidsmart.diag.Diag.swallow("MaidConfigPreset", t); return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object defaultValue(Object cv) {
        try {
            return ((ModConfigSpec.ConfigValue) cv).getDefault();
        } catch (Throwable t) {
             com.maidsmart.diag.Diag.swallow("MaidConfigPreset", t); return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setRaw(Object cv, Object want) {
        ((ModConfigSpec.ConfigValue) cv).set(want);
    }

    /** 值是否相同（null 一律视为不同，好让调用方保守地写一遍） */
    private static boolean sameValue(Object a, Object b) {
        return a != null && a.equals(b);
    }
}
