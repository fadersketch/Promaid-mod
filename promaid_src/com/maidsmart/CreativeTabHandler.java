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
 */
@Mod.EventBusSubscriber(modid = "maid_smart", bus = Mod.EventBusSubscriber.Bus.MOD)
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
    }
}
