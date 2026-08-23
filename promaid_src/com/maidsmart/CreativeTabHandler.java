package com.maidsmart;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 创造模式物品栏（v1.5.11）：把蓝图卷轴加入"建筑方块"与"工具与实用品"标签页，
 * 玩家可以直接从创造物品栏拿取，无需 /give。
 * v1.5.284：modid 修复——旧版写 "maid_smart"（物品注册命名空间）≠ modId "promaid"
 * （mods.toml）→ 事件订阅对不存在的 mod 注册，创造栏注入从未生效。
 * v1.1.0 实测二十六：排班表也进"工具与实用品"标签页（用户：创造物品栏直接拿）。
 */
@Mod.EventBusSubscriber(modid = "promaid", bus = Mod.EventBusSubscriber.Bus.MOD)
public class CreativeTabHandler {
    private static final ResourceKey<CreativeModeTab> TAB_BUILDING_BLOCKS =
            ResourceKey.m_135785_(Registries.f_279569_, new ResourceLocation("minecraft", "building_blocks"));
    private static final ResourceKey<CreativeModeTab> TAB_TOOLS_AND_UTILITIES =
            ResourceKey.m_135785_(Registries.f_279569_, new ResourceLocation("minecraft", "tools_and_utilities"));

    @SubscribeEvent
    public static void onBuildContents(BuildCreativeModeTabContentsEvent event) {
        ResourceKey<CreativeModeTab> key = event.getTabKey();
        if (!key.equals(TAB_BUILDING_BLOCKS) && !key.equals(TAB_TOOLS_AND_UTILITIES)) {
            return;
        }
        event.accept(ProMaidMod.BLUEPRINT_BOOK);
        // 排班表是管理道具不是建材，只进工具页（实测五十五：光效走 m_5812_，
        // 与手册同源——创造栏拿出来的即带附魔流光）
        if (key.equals(TAB_TOOLS_AND_UTILITIES)) {
            event.accept(ProMaidMod.SCHEDULE_BOOK);
        }
    }
}
