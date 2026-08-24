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
 * 烹饪/酿造/建造任务。关系联动（心契誓约 × 爱憎分明）已迁往独立的
 * Heartfelt-connection 补丁（本模组零依赖，可单独运行）。
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

    public ProMaidMod() {
        ITEMS.register(FMLJavaModLoadingContext.get().getModEventBus());
        com.maidsmart.build.BlueprintBookNetworking.register();
        // v1.1.0：排班表网络层 + 调度器（按游戏内时间自动切工作模式/任务）
        com.maidsmart.schedule.ScheduleNetworking.register();
        com.maidsmart.schedule.ScheduleManager.register();
        // v1.5.88：全模组配置（COMMON——客户端/服务端都可读，配置面板可热更新）
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
                net.minecraftforge.fml.config.ModConfig.Type.COMMON, com.maidsmart.config.MaidSmartConfig.SPEC);
        // v1.1.0 实测七十二：穿透预算语义修正后默认 22→6——旧档配置文件里存的
        // 还是旧默认 22，加载时自动迁到 6（玩家手动改过的值 ≠22 不动）
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                (net.minecraftforge.fml.event.config.ModConfigEvent e) -> onConfigLoad(e));
        // v1.5.88：MC 主菜单→模组→promaid→Config 打开自定义配置面板（仅客户端）
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            net.minecraftforge.fml.ModLoadingContext.get().registerExtensionPoint(
                    net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory(
                            (mc, parent) -> new com.maidsmart.config.PromaidConfigScreen(parent)));
        }
    }

    /** v1.1.0 实测七十二：穿透预算旧默认迁移（22 → 6；手动改过的值不动） */
    private void onConfigLoad(net.minecraftforge.fml.event.config.ModConfigEvent event) {
        try {
            if (event.getConfig().getSpec() != com.maidsmart.config.MaidSmartConfig.SPEC) {
                return;
            }
            if (com.maidsmart.config.MaidSmartConfig.MINE_BREAK_BUDGET.get() == 22) {
                com.maidsmart.config.MaidSmartConfig.MINE_BREAK_BUDGET.set(6);
            }
        } catch (Exception ignored) {
        }
    }
}
