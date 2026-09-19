package com.maidsmart;

import net.minecraft.world.item.Item;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Promaid（更智能的车万女仆，v1.0.0）的 @Mod 入口。
 *
 * 由原 maid_smart 改名重组（v1.0.0 拆分）：全部可独立运行功能（只依赖原版 TLM）
 * 迁入本模组——建造系统/工头、AI 工具、自保、主动对话、基础记忆、挖矿/整理/
 * 烹饪/酿造/建造任务。本模组零依赖，可单独运行。
 *
 * Forge 要求 mods.toml 声明的每个 mod 都能在 jar 中找到对应的 @Mod 注解类；
 * TLM 的扩展发现扫描 ModList 中的 @LittleMaidExtension——本类必须存在，
 * ProMaidExtension 才能被 TLM 发现并注册全部功能。
 *
 * 兼容性说明（v1.0.0）：物品/网络/TaskData/蓝图路径等持久化标识【保留
 * maid_smart 命名空间】（如 maid_smart:blueprint_book）——旧存档物品与数据
 * 不丢失。仅 modId 变为 promaid。
 */
@Mod("promaid")
public class ProMaidMod {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, "maid_smart");

    /** Promaid 手册（v1.5.16）：右键打开全部蓝图列表（内置+外部），点击即让附近女仆建造 */
    public static final RegistryObject<Item> BLUEPRINT_BOOK = ITEMS.register("blueprint_book",
            () -> new com.maidsmart.build.BlueprintBookItem(new Item.Properties()));

    /** 排班表（v1.1.0）：纸+墨囊合成，右键打开排班界面（快捷设置 + 按游戏内时间的日程编排） */
    public static final RegistryObject<Item> SCHEDULE_BOOK = ITEMS.register("schedule_book",
            () -> new com.maidsmart.schedule.ScheduleBookItem(new Item.Properties()));

    /** 女仆药剂手册（v1.1.0 实测二百七十七）：水瓶+书本合成，右键女仆打开酿造配置 GUI */
    public static final RegistryObject<Item> BREW_MANUAL = ITEMS.register("brew_manual",
            () -> new com.maidsmart.brew.BrewManualItem(new Item.Properties()));

    /** 指标石（v1.2.0）：9 平滑石头合成，右击方块锁定（绿→红）→ 右击女仆绑定 → 临时搭建 */
    public static final RegistryObject<Item> INDEX_STONE = ITEMS.register("index_stone",
            () -> new com.maidsmart.build.IndexStoneItem(new Item.Properties()));

    /** v1.2.2 实测五百六十一：官方注册的那个 ModConfig（配置事件里记下来；只读引用，事件里不写盘） */
    public static net.minecraftforge.fml.config.ModConfig COMMON_CONFIG;

    public ProMaidMod() {
        ITEMS.register(FMLJavaModLoadingContext.get().getModEventBus());
        com.maidsmart.build.BlueprintBookNetworking.register();
        // v1.2.0：指标石网络层（C2S 锁定请求 + S2C 会话状态）
        com.maidsmart.build.IndexStoneNetworking.register();
        // v1.1.0：排班表网络层 + 调度器（按游戏内时间自动切工作模式/任务）
        com.maidsmart.schedule.ScheduleNetworking.register();
        com.maidsmart.schedule.ScheduleManager.register();
        // v1.1.0 实测二百七十七：女仆药剂手册网络层 + 右键女仆交互
        com.maidsmart.brew.BrewManualNetworking.register();
        // v1.1.0 实测二百八十五：情绪价值交互（G 摸摸头 / H 抱抱，键位+服务端验证）
        com.maidsmart.emotion.EmotionNetworking.register();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(
                new com.maidsmart.brew.BrewManualInteractHandler());
        // v1.2.0：指标石右键女仆 = 绑定/解绑
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(
                new com.maidsmart.build.IndexStoneInteractHandler());
        // v1.2.0：指标石中断清理（女仆被收回/死亡 → 结束临时搭建）
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(
                new com.maidsmart.build.IndexStoneInterruptHandler());
        // v1.1.0 实测二百三十五：两个自监听 ServerTick 的模块在此注册（@Mod 构造器
        // 保证每次加载恰好一次——TLM 扩展实例化时机不可靠，曾导致驱动永不生效）
        com.maidsmart.task.MaidPlanting.ensureRegistered();
        com.maidsmart.tool.MaidHeldLight.ensureRegistered();
        // v1.5.88：全模组配置（COMMON——客户端/服务端都可读，配置面板可热更新）
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
                net.minecraftforge.fml.config.ModConfig.Type.COMMON, com.maidsmart.config.MaidSmartConfig.SPEC);
        // v1.1.0 实测七十二：穿透预算语义修正后默认 22→6——旧档配置文件里存的
        // 还是旧默认 22，加载时自动迁到 6（玩家手动改过的值 ≠22 不动）
        // v1.2.0【根因修复】ModConfigEvent 是【MOD 总线】事件（implements IModBusEvent，
        // javap 实证），旧版却注册在 MinecraftForge.EVENT_BUS（forge 总线）上——
        // 因此本回调从未被调用过，此前所有"旧默认自动迁移"（22→6、30→8、搭路…）
        // 其实一直是失效的（实测：把 waterFallDistance 故意写成旧默认 6.0 再启动，
        // 优雅退出后读回仍是 6.0）。改为注册到 MOD 总线。
        net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus()
                .addListener((net.minecraftforge.fml.event.config.ModConfigEvent e) -> onConfigLoad(e));
        // v1.5.88：MC 主菜单→模组→promaid→Config 打开自定义配置面板（仅客户端）。
        // v1.1.0【专用服务器崩溃修复】：带 Screen 签名的 lambda 一律放客户端专类
        // （com.maidsmart.client.PromaidClientSetup）——主类内联会让合成方法描述符
        // 带客户端类型，服务端 FML 反射主类时被 RuntimeDistCleaner 拦截
        //（反馈服崩报告：Attempted to load class net.minecraft.client.gui.screens.Screen
        //  for invalid dist DEDICATED_SERVER）。服务端不执行本分支 → 客户端类不加载。
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            com.maidsmart.client.PromaidClientSetup.registerConfigScreen();
            // v1.1.0 实测四百二十：内置日语语音包客户端钩子（播放压制 + tick 兜底）
            com.maidsmart.client.PromaidClientSetup.registerVoiceHooks();
            // 实测四百四十四：冷却 HUD 渲染器显式注册（1.20.1 注解自动注册未生效）
            com.maidsmart.client.PromaidClientSetup.registerHudHooks();
            // 实测五百六十二：潜行+中键 工位标记（客户端手势识别 + C2S 包）
            com.maidsmart.client.PromaidClientSetup.registerWorkPosMarker();
        }
    }

    /** v1.1.0 实测七十二：穿透预算旧默认迁移（22 → 6；手动改过的值不动） */
    /** v1.2.0：把默认值迁移集中到静态入口——配置事件与服务端启动都可调用。
     *  实测教训：NeoForge 侧仅靠 ModConfigEvent 没能生效，故启动时兜底再跑一次
     *（迁移是幂等的：只在"值==旧默认"时才改，跑多次无副作用）。 */
    public static boolean runConfigMigration() {
        boolean changed = false;
        try {
            if (com.maidsmart.config.MaidSmartConfig.MINE_BREAK_BUDGET.get() == 22) {
                com.maidsmart.config.MaidSmartConfig.MINE_BREAK_BUDGET.set(6);
                changed = true;
            }
            if (com.maidsmart.config.MaidSmartConfig.WOOD_STUCK_RESET_SECONDS.get() == 30) {
                com.maidsmart.config.MaidSmartConfig.WOOD_STUCK_RESET_SECONDS.set(8);
                changed = true;
            }
            if (com.maidsmart.config.MaidSmartConfig.MINE_STUCK_RESET_SECONDS.get() == 45) {
                com.maidsmart.config.MaidSmartConfig.MINE_STUCK_RESET_SECONDS.set(8);
                changed = true;
            }
            if (com.maidsmart.config.MaidSmartConfig.BRIDGE_STEP_COOLDOWN.get() == 2
                    || com.maidsmart.config.MaidSmartConfig.BRIDGE_STEP_COOLDOWN.get() == 8) {
                com.maidsmart.config.MaidSmartConfig.BRIDGE_STEP_COOLDOWN.set(5);
                changed = true;
            }
            if (com.maidsmart.config.MaidSmartConfig.BRIDGE_PLACED_LIFETIME.get() == 10) {
                com.maidsmart.config.MaidSmartConfig.BRIDGE_PLACED_LIFETIME.set(2);
                changed = true;
            }
            if (com.maidsmart.config.MaidSmartConfig.MISC_SCHEDULE_AVAILABILITY_CHECK.get()) {
                com.maidsmart.config.MaidSmartConfig.MISC_SCHEDULE_AVAILABILITY_CHECK.set(false);
                changed = true;
            }
            // v1.2.0：落地水触发高度默认 6 → 4 格（旧档存的旧默认自动迁移；手改过的不动）
            if (com.maidsmart.config.MaidSmartConfig.COMBAT_WATER_FALL_DISTANCE.get() == 6.0) {
                com.maidsmart.config.MaidSmartConfig.COMBAT_WATER_FALL_DISTANCE.set(4.0);
                changed = true;
            }
            // v1.2.0 实测五百五十七：建造默认速度 x1.5 → x1、极速模式 开 → 关。
            // 【用一次性标记来判定，而不是"值 == 旧默认"】——实测玩家 toml 里的实际组合是
            // `speedTier = "x1" + turbo = true`（档位早就是 x1、只有极速还开着），
            // 只凭值分不出"旧默认留下的 true"和"玩家自己打开的 true"。
            // 标记跑过一次就不再碰这两个值：玩家之后想开极速，随时打开都能留着。
            if (!com.maidsmart.config.MaidSmartConfig.BUILD_SPEED_MIGRATED.get()) {
                com.maidsmart.config.MaidSmartConfig.BUILD_SPEED_MIGRATED.set(true);
                changed = true;
                if (com.maidsmart.config.MaidSmartConfig.BUILD_TURBO.get()) {
                    com.maidsmart.config.MaidSmartConfig.BUILD_TURBO.set(false);
                }
                if ("x1.5".equals(com.maidsmart.config.MaidSmartConfig.BUILD_SPEED_TIER.get())) {
                    com.maidsmart.config.MaidSmartConfig.BUILD_SPEED_TIER.set("x1");
                }
            }
            // v1.2.2 实测五百六十二：排班活动半径默认 32 → 12（回归 TLM home 工作区域
            // 原版量级）。同款一次性标记——玩家手动设的 32（真想要大圈）不会被误迁。
            if (!com.maidsmart.config.MaidSmartConfig.SCHEDULE_RANGE_MIGRATED.get()) {
                com.maidsmart.config.MaidSmartConfig.SCHEDULE_RANGE_MIGRATED.set(true);
                changed = true;
                if (com.maidsmart.config.MaidSmartConfig.SCHEDULE_ACTIVITY_RANGE.get() == 32) {
                    com.maidsmart.config.MaidSmartConfig.SCHEDULE_ACTIVITY_RANGE.set(12);
                }
            }
            changed |= migrateOreTable();
        } catch (Exception ignored) {
        }
        return changed;
    }

    /**
     * v1.2.2 实测五百六十一：**配置事件里绝对不要写盘**。
     *
     * 【事故】1.2.0 起 NeoForge 侧在这里收到配置事件后显式 `save()`（为了让迁移落盘）。
     * 但 NeoForge 用文件监听器盯着 config/*.toml：**写盘 → 监听器触发 → 再发一次
     * {@code ModConfigEvent.Reloading} → 处理器再 save() → …** 无限写盘风暴。
     * 实测复现（专用服务器，外部把 config 改一次）：20 秒内该文件被重写 **38 次**，
     * 并留下截断的 `promaid-common.new.tmp.toml`。
     *
     * 玩家侧表现完全对得上反馈：点「保存并返回」= 一次写盘 → 风暴起来 → 客户端卡死/崩；
     * 下次进游戏时 NeoForge 修正旧键（"Configuration file ... is not correct. Correcting"）
     * 本身也要写盘 → 同样踩进风暴 → 卡在 mod 加载界面。
     *
     * 【现在】事件里只记录 ModConfig 引用，一个字节都不写；迁移改由服务端启动时跑一次
     * （{@code ProMaidExtension.onServerStarted}），只有真的改过值才
     * {@link #persistConfigQuietly()} 落盘一次。Forge 侧原本没写盘，这里同步收紧，
     * 两树行为一致、也防以后有人再加回来。
     */
    private void onConfigLoad(net.minecraftforge.fml.event.config.ModConfigEvent event) {
        inConfigEvent = true; // 本方法体内禁止任何落盘（见方法注释）
        try {
            if (event.getConfig().getSpec() != com.maidsmart.config.MaidSmartConfig.SPEC) {
                return;
            }
            COMMON_CONFIG = event.getConfig();
        } catch (Throwable ignored) {
        } finally {
            inConfigEvent = false;
        }
    }

    /** 配置事件处理中标志：这期间任何落盘请求一律拒绝（见 {@link #onConfigLoad}） */
    private static boolean inConfigEvent = false;

    /**
     * 迁移/默认值改动后落盘一次。**只能在配置事件之外调用**（配置事件期间会被直接拒绝，
     * 防"写盘→监听器→再写"自触发风暴）。
     */
    public static void persistConfigQuietly() {
        if (inConfigEvent) {
            return;
        }
        try {
            com.maidsmart.config.MaidSmartConfig.SPEC.save();
        } catch (Throwable ignored) {
        }
    }

    /**
     * v1.1.0 实测七十三（反馈："女仆专门不挖铜矿石"）：铜矿 2026-08-21 才首次
     * 进入默认矿表，且更早的默认是【空列表】；而配置文件是唯一事实源（加载时清空
     * 内置表全以文件为准）→ 老玩家存档里的矿表没有铜，女仆永远不选铜矿、甚至把它
     * 当硬挡路报点。两条迁移规则（只补缺，绝不动玩家已有条目）：
     * ① 空表 = 从未配置过 → 播种当前默认全家桶；
     * ② 表里有原版矿但没有铜 → 只补 copper / deepslate_copper 两项。
     */
    private static boolean migrateOreTable() {
        java.util.LinkedHashSet<String> ores = new java.util.LinkedHashSet<>(
                com.maidsmart.config.MaidSmartConfig.MINE_ORE_VALUES.get());
        boolean changed = false;
        if (ores.isEmpty()) {
            ores.addAll(com.maidsmart.config.MaidSmartConfig.DEFAULT_ORE_VALUES);
            changed = true;
        } else {
            boolean hasCopper = ores.stream()
                    .anyMatch(s -> s.startsWith("minecraft:copper_ore="));
            boolean hasVanillaOre = ores.stream()
                    .anyMatch(s -> s.startsWith("minecraft:") && s.contains("_ore="));
            if (!hasCopper && hasVanillaOre) {
                ores.add("minecraft:copper_ore=300");
                ores.add("minecraft:deepslate_copper_ore=300");
                changed = true;
            }
        }
        if (changed) {
            com.maidsmart.config.MaidSmartConfig.MINE_ORE_VALUES.set(
                    new java.util.ArrayList<>(ores));
        }
        return changed;
    }
}
