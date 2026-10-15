package com.maidsmart.config;

import net.minecraftforge.common.ForgeConfigSpec;

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
     * 内部簿记：现无——一次性迁移标记已移出用户配置（见 MaidSmartConfig.INTERNAL_SPEC）。
     */
    private static final java.util.Set<String> SKIP = java.util.Set.of();

    /**
     * DIY 不动的项：纯附加子系统、护栏、修复，以及"关掉反而更危险 / 只是把 bug 放回来"的那些
     * （判据 = 它不动 TLM 的女仆行为，只加我们自己的系统 / 显示 / 播报）。
     * <p>DIY 新规起「新任务（挖矿/伐木/建造/扫帚/宰杀/空袭）与新物品」整块排除在 DIY 之外，
     * 走 {@link #DIY_KEEP_PREFIX} / {@link #DIY_KEEP_EXACT}，不再逐条往这张表里搬——
     * DIY 之后 = TLM 原版的女仆行为 + 我们新增的任务与物品照常可用。
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
            "misc.cooldownHud", "misc.scheduleBubbleEnabled",
            // ---- 放置护栏：悬空禁搭、不搭在主人身上 ----
            "misc.noPlaceInAir", "misc.noPlaceOnOwner",
            // ---- 投弹/搭路的安全档 ----
            "bombing.dimensionGuard", "bombing.fireProtect", "bombing.anchorNeedsGlowstone",
            "bridge.buildOnlyNatural",
            // ---- 本项自身（只记录模式；模式的两个伴生总闸与它同源，DIY 时要一起关，故不进保留表） ----
            PATH_PRESET_MODE);

    /**
     * 实测九百四十七【DIY 新规】：「新功能」的前缀表——这些前缀下的项 DIY **一概不动**。
     *
     * <p>它们是「本模组**新加的功能**」，不是「本模组对女仆原有行为的改动」，所以不吃 DIY：
     * 新任务（挖矿 / 伐木 / 建造 / 扫帚 / 空袭 + 接敌机动）、新物品（武装拴绳 / 骑乘指挥棒 /
     * 压缩盒 / 饰品「大运」）。判据用**前缀**而不是逐条列，因为一个功能往往有十几到几十个配置项
     * （空袭 48 + 轰炸 25 + 骑乘 18 + 扫帚 17 …），逐条维护必然漂。
     */
    private static final java.util.Set<String> DIY_KEEP_PREFIX = java.util.Set.of(
            "mine.", "wood.", "build.",           // 新任务：挖矿 / 伐木 / 建造
            "broom.",                              // 新任务：扫帚模式
            "bombing.", "airRaid.", "maneuver.",   // 新任务：空袭（投放 / 数值 / 空中接敌机动）
            "tether.", "ride.",                    // 新物品：武装拴绳 / 骑乘指挥棒
            "compressionBox.", "accessory.");      // 新物品：压缩盒 / 饰品「大运」

    /**
     * 新任务里挂在 {@code misc.*} 下的那些项（宰杀 / 农场整片与连锁 / 烹饪 / 酿造）——同样 DIY 不动。
     * 它们没有独立前缀（历史上都写进 misc 段），所以只能点名列出。
     */
    private static final java.util.Set<String> DIY_KEEP_EXACT = java.util.Set.of(
            "misc.slaughterCount", "misc.slaughterRadius",
            "misc.produceTaskEnhance", "misc.chainHarvest", "misc.chainHarvestLimit",
            "misc.batchPlant", "misc.batchPlantLimit",
            "misc.maidBonemealFarm", "misc.maidBonemealSapling", "misc.autoCollect",
            "misc.cookBurnWood", "misc.cookFuelAllow", "misc.cookFuelDeny", "misc.cookRadius",
            "misc.cookSmeltAllow", "misc.cookSmeltAny", "misc.cookSmeltDeny", "misc.cookSmeltOres",
            "misc.cookSmokerBlast", "misc.brewAuto", "misc.brewRadius");

    /** 这个路径是不是「新功能」（新任务 / 新物品）→ DIY 不动它。 */
    static boolean isNewFeature(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        if (DIY_KEEP_EXACT.contains(path)) {
            return true;
        }
        for (String pre : DIY_KEEP_PREFIX) {
            if (path.startsWith(pre)) {
                return true;
            }
        }
        return false;
    }

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
            "combat.assistRadius", 0);           // 0 = 不援护、不撒手
            // 实测九百四十七：空袭牵引绳（combat.flightRecallDistance）、扫帚牵引绳（broom.recallDistance）、
            // 建造速度档（build.speedTier）、骑乘启动延迟（ride.startupDelaySeconds）**已从本表移出**——
            // 空袭 / 扫帚 / 建造 / 骑乘属「新功能」，DIY 下与默认模式一模一样（见 DIY_KEEP_PREFIX）。

    /** 恢复出厂默认时要保留的项：个人凭据（重置不该丢钥匙） */
    private static final java.util.Set<String> RESET_KEEP = java.util.Set.of(
            "memory.apiUrl", "memory.apiKey", "memory.apiModel",
            "memory.embedUrl", "memory.embedKey", "memory.embedModel");

    /** 反射枚举结果缓存（{path, ConfigValue}），按路径排序 */
    private static java.util.List<Object[]> FIELDS;

    private MaidConfigPreset() {
    }

    /** 反射取全部配置项（静态 ConfigValue 字段）；失败返回空表（预设退化为什么都不做） */
    static java.util.List<Object[]> fields() {
        java.util.List<Object[]> local = FIELDS;
        if (local != null) {
            return local;
        }
        java.util.List<Object[]> out = new java.util.ArrayList<>();
        try {
            for (java.lang.reflect.Field f : MaidSmartConfig.class.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        || !ForgeConfigSpec.ConfigValue.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                f.setAccessible(true);
                Object cv = f.get(null);
                if (cv == null) {
                    continue;
                }
                java.util.List<String> p = ((ForgeConfigSpec.ConfigValue<?>) cv).getPath();
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
            // 实测九百四十七【DIY 新规】：新任务 / 新物品整块不参与 DIY——
            // 它们的开关与数值在 DIY 下与默认模式**逐项相同**。
            if (isNewFeature(path)) {
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
            return ((ForgeConfigSpec.ConfigValue) cv).get();
        } catch (Throwable t) {
             com.maidsmart.diag.Diag.swallow("MaidConfigPreset", t); return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object defaultValue(Object cv) {
        try {
            return ((ForgeConfigSpec.ConfigValue) cv).getDefault();
        } catch (Throwable t) {
             com.maidsmart.diag.Diag.swallow("MaidConfigPreset", t); return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setRaw(Object cv, Object want) {
        ((ForgeConfigSpec.ConfigValue) cv).set(want);
    }

    /** 值是否相同（null 一律视为不同，好让调用方保守地写一遍） */
    private static boolean sameValue(Object a, Object b) {
        return a != null && a.equals(b);
    }
}
