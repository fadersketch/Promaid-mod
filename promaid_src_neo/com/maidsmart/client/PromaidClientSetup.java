package com.maidsmart.client;

import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * 仅客户端类（专用服务器上永不加载）：集中存放一切带客户端类型的方法签名与合成 lambda。
 *
 * 【为什么必须单独成类】@Mod 主类在服务端也会被 FML 反射（getDeclaredConstructor /
 * getDeclaredMethods），反射解析方法描述符时会把内联 lambda 编译出的合成方法一并解析——
 * 主类里内联的 (mc, parent) -> new PromaidConfigScreen(...) 合成方法描述符含
 * net.minecraft.client.gui.screens.Screen，NeoForge/Forge 的 RuntimeDistCleaner 在
 * DEDICATED_SERVER 直接抛 "Attempted to load class ... for invalid dist DEDICATED_SERVER"
 * → 整个 mod 加载失败、服务器启动中止（1.20.1 反馈服崩报告实证）。
 * 主类只在 dist.isClient() 分支里静态调用本类，服务端该分支不执行 → 本类不被加载 →
 * 客户端类型永不被触碰。
 */
public final class PromaidClientSetup {
    private PromaidClientSetup() {
    }

    /** 注册"模组列表→promaid→Config"自定义配置面板（客户端专属扩展点） */
    public static void registerConfigScreen(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (mc, parent) -> new com.maidsmart.config.PromaidConfigScreen(parent));
    }

    /**
     * v1.1.0 实测四百二十：内置日语语音包的客户端钩子——
     * ① PlaySoundEvent：播放窗口内压制 TLM 原生语音包；
     * ② ClientTickEvent.Post：未进世界时清压制窗口。
     * 客户端专属（服务端不注册、本类不加载）。
     */
    public static void registerVoiceHooks() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                com.maidsmart.client.PromaidClientSetup::onPlaySound);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                com.maidsmart.client.PromaidClientSetup::onClientTickPost);
    }

    /** v1.2.0：指标石预览渲染器（绿框跟随指针 / 红框锁定 / 橙色幽灵格 + 长射线锁定） */
    public static void registerIndexStoneHooks() {
        com.maidsmart.build.IndexStonePreviewClient.ensureRegistered();        // v1.2.2 实测五百八十七：女仆放置物的淡粉色标记（纯客户端渲染）
        com.maidsmart.client.BombMarkClient.ensureRegistered();
        // v1.3.7 实测六百六十七：武装拴绳的绳子（S2C 状态 → LINES 管线，与 BombMark 同源）
        com.maidsmart.client.GunnerTetherClient.ensureRegistered();
    }

    /** 实测五百六十二：潜行+中键 工位标记的客户端手势识别（仅客户端注册） */
    public static void registerWorkPosMarker() {
        com.maidsmart.marker.WorkPosMarkerClient.register();
    }

    /** 【实测七百二十四】右击载具后把被 SWB 转掉的视角复述回来（仅客户端注册）。 */
    public static void registerRideViewClamp() {
        com.maidsmart.client.RideBatonViewClamp.ensureRegistered();
    }

    /**
     * 【实测七百二十七·点4】退出世界时清掉"悬空鞍位（龙）"的客户端镜像表。
     *
     * <p>为什么放在客户端专属类：事件类型 {@code ClientPlayerNetworkEvent.LoggingOut} 是
     * 客户端类型，写在 {@code RideBindManager}（两侧都会加载）里会让专用服务器加载到客户端类。
     */
    public static void registerSeatSyncClear() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut e) ->
                        com.maidsmart.combat.RideBindManager.clearSyncedSeats());
    }

    private static void onPlaySound(net.neoforged.neoforge.client.event.sound.PlaySoundEvent event) {
        com.maidsmart.voice.ClientVoicePlayback.onPlaySound(event);
    }

    private static void onClientTickPost(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        com.maidsmart.voice.ClientVoicePlayback.onClientTick();
    }
}
