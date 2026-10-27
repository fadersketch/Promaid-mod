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
        // 实测一千〇六：女仆放置方块的金色描边（S2C 位置表 → LINES 管线，与 BombMark 同源）
        com.maidsmart.client.PlacedMarkClient.ensureRegistered();
        // v1.3.7 实测六百六十七：武装拴绳的绳子（S2C 状态 → LINES 管线，与 BombMark 同源）
        com.maidsmart.client.GunnerTetherClient.ensureRegistered();
        // 【R1108】手持武装拴绳/指标石时准星指向的女仆描边（客户端 tick 维护目标，零网络）
        com.maidsmart.client.MaidAimGlowClient.ensureRegistered();
    }

    /**
     * v1.3.9：巡逻航图的两件客户端东西——中键打点手势 + 航迹/标记预览渲染。
     * 与服务端铁律一致（{@code Screen} 一律在本客户端专类里开）。
     */
    public static void registerPatrolHooks() {
        com.maidsmart.patrol.PatrolMarkerClient.register();
        com.maidsmart.patrol.PatrolPreviewClient.ensureRegistered();
    }

    /**
     * v1.3.8：S2C 到达 → 打开/刷新巡逻航迹编辑界面。
     *
     * <p>【为什么必须有这个入口】网络包的 {@code handle} 在通用代码里，直接 {@code new
     * PatrolChartScreen(...)} 会让专用服务器加载到客户端类（{@code Screen}）——
     * 与主类注释里那条 {@code RuntimeDistCleaner} 事故同源。所以开屏这一步统一收敛到这里，
     * 由包只调这一个方法。
     */
    public static void openPatrolScreen(
            com.maidsmart.patrol.PatrolNetworking.OpenPatrolPacket pkt) {
        try {
            com.maidsmart.patrol.PatrolChartScreen.accept(pkt.offHand, pkt.book,
                    pkt.problems, pkt.notes, pkt.length, pkt.seconds, pkt.open,
                    pkt.canFlyToTarget, pkt.playerDim);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidClientSetup", ignored);
        }
    }

    /** v1.3.9：女仆名单（S2C）→ 交给航图界面 */
    public static void updatePatrolMaids(
            com.maidsmart.patrol.PatrolNetworking.MaidListPacket pkt) {
        try {
            com.maidsmart.patrol.PatrolChartScreen.acceptMaids(pkt.offHand, pkt.rows);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidClientSetup", ignored);
        }
    }

    /** v1.3.9：标记态（S2C）→ 预览高亮 + 界面按钮文字 */
    public static void updatePatrolMarking(
            com.maidsmart.patrol.PatrolNetworking.MarkingStatePacket pkt) {
        try {
            com.maidsmart.patrol.PatrolChartScreen.acceptMarking(pkt.marking, pkt.routeId);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidClientSetup", ignored);
        }
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
                (net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut e) -> {
                    com.maidsmart.combat.RideBindManager.clearSyncedSeats();
                    // 烟花尾迹的客户端窗口（实体 id 跨世界会撞，退出必须清）
                    com.maidsmart.client.FireworkTrailClient.clear();
                });
    }

    /**
     * v1.5.394 实测八百零五：「大运」BGM 的客户端两件东西——
     * ① ClientTickEvent.Post：推进 {@link com.maidsmart.client.DaYunMusicPlayer} 的心跳/重建/选谁响；
     * ② LoggingOut：退出世界时清空名单（避免跨世界残留）。
     * 客户端专属（声音实例与 {@code Minecraft} 都是客户端类型，服务端不加载本类）。
     */
    public static void registerDaYunMusicHooks() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientTickEvent.Post e) ->
                        com.maidsmart.client.DaYunMusicPlayer.onClientTick());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut e) ->
                        com.maidsmart.client.DaYunMusicPlayer.clear());
    }

    /** v1.5.394 实测八百零五：S2C 收到「某佩戴者的 BGM 开始/停止」→ 交给客户端播放器。 */
    public static void handleDaYunMusic(int wearerId, int track, boolean playing) {
        try {
            com.maidsmart.client.DaYunMusicPlayer.handle(wearerId, track, playing);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidClientSetup", ignored);
        }
    }

    /**
     * 多人下「配置以服务端为准」的客户端钩子：退出世界时把客户端自己那份配置还原回来
     * （锁定期间套用的是服务端值，不清会跨世界残留）。客户端专属——事件类型是客户端类型。
     */
    public static void registerConfigSyncClient() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut e) ->
                        com.maidsmart.config.ConfigSyncNetworking.restoreOwn());
    }

    /**
     * v1.3.3 实测一千〇二【粉火黑块】：把 {@code maid_smart:pink_fire} 登记进 cutout 渲染层——
     * 渲染层按【方块实例】查表（javap 实证 ItemBlockRenderTypes：原版把 FIRE 映射到 cutout，
     * 表里没有的方块回退 solid）——{@code extends FireBlock} 不继承表项，粉火落回 solid：贴图
     * 透明像素（火焰形状之外）被画成纯黑，整团火就是一块黑板。NeoForge 21.1 的表与回退同构
     * （javap 实证），一样要登记；时机有硬校验（{@code checkClientLoading}：仅客户端加载期），
     * 故挂 FMLClientSetupEvent。
     */
    public static void registerPinkFireRenderType(net.neoforged.bus.api.IEventBus modBus) {
        modBus.addListener((net.neoforged.fml.event.lifecycle.FMLClientSetupEvent e) ->
                e.enqueueWork(() -> net.minecraft.client.renderer.ItemBlockRenderTypes.setRenderLayer(
                        com.maidsmart.combat.PinkFireBlock.block(),
                        // RenderType.cutout()（Mojmap；两树同为 cutout 层，口径一致）
                        net.minecraft.client.renderer.RenderType.cutout())));
    }

    private static void onPlaySound(net.neoforged.neoforge.client.event.sound.PlaySoundEvent event) {
        com.maidsmart.voice.ClientVoicePlayback.onPlaySound(event);
    }

    private static void onClientTickPost(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        com.maidsmart.voice.ClientVoicePlayback.onClientTick();
        com.maidsmart.client.PromaidVoiceSoundInstance.tickPending();
    }
}
