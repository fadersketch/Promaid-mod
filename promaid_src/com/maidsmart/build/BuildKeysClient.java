package com.maidsmart.build;

import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * v1.1.0 实测九十七：建造预览转向键——注册进【原版按键设置】
 *（选项 → 按键绑定 → "Promaid 建造"分类），玩家可在原版界面里改键，
 * 操作方式与调整原版按键完全一致。默认 Z（实测一百由 P 改来，避开原版社交互动）。
 *
 * 金色预览激活期间每 tick 轮询 m_90859_（consumeClick，BlueprintAreaPreview.onClientTick）：
 * 按一次 = 整个建筑顺时针旋转 90°（占地轮廓 W/D 互换 + 方块状态转向 +
 * 青色幽灵投影实时刷新），确认建造时以该朝向落地。
 */
@Mod.EventBusSubscriber(modid = "promaid", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class BuildKeysClient {
    /** 建造预览·旋转朝向（默认 Z；原版按键设置可改） */
    public static KeyMapping ROTATE_BLUEPRINT = null;

    private BuildKeysClient() {
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        // v1.1.0 实测一百：默认键 P→Z——字节码实证原版「社交互动」默认同为 P，
        // 按 P 转向会同时弹出社交界面；Z 原版零占用且不与 JEI(R)/OptiFine(C)/
        // 背包(B)/语音(V)/地图(M) 等主流模组默认键相撞
        ROTATE_BLUEPRINT = new KeyMapping("key.promaid.build_rotate",
                GLFW.GLFW_KEY_Z, "key.categories.promaid");
        event.register(ROTATE_BLUEPRINT);
    }

    /**
     * v1.1.0 实测九十七复查：客户端启动即把 BlueprintAreaPreview 挂到 FORGE 总线
     * ——它的转向键轮询必须从开机就在跑才能持续排空按键计数器（否则首次预览
     * 前误按的 P 会攒到开启瞬间爆转）；渲染入口自带空态短路，常驻零开销。
     */
    @SubscribeEvent
    public static void onClientSetup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(BlueprintAreaPreview.class);
    }
}
