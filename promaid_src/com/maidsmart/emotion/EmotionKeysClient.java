package com.maidsmart.emotion;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * 情绪交互键位（v1.1.0 实测二百八十五）——默认 G【摸摸头】/ H【抱抱】，
 * 注册进原版按键设置（"Promaid 建造"分类同款，玩家可改键）。
 *
 * 默认键选型：G/H 在原版零占用；避开 V（语音 TTS）、B（背包装卸/录像）等
 * 主流模组默认键。客户端 tick 轮询 consumeClick → 发 C2S 动作包，服务端做
 * 视线/归属/冷却全量校验（见 EmotionNetworking）。
 */
@Mod.EventBusSubscriber(modid = "promaid", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class EmotionKeysClient {
    /** 摸摸头（默认 G） */
    public static KeyMapping MAID_PAT = null;
    /** 抱抱（默认 H） */
    public static KeyMapping MAID_HUG = null;

    private EmotionKeysClient() {
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        MAID_PAT = new KeyMapping("key.promaid.maid_pat",
                GLFW.GLFW_KEY_G, "key.categories.promaid");
        MAID_HUG = new KeyMapping("key.promaid.maid_hug",
                GLFW.GLFW_KEY_H, "key.categories.promaid");
        event.register(MAID_PAT);
        event.register(MAID_HUG);
        // tick 轮询挂 FORGE 总线（客户端启动即生效）
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new TickPoll());
    }

    /** 客户端 tick 轮询按键（consumeClick 排队计数，按一次发一次）。
     *  无需 Screen 判断——原版机制下 Screen 打开时按键不会进入 KeyMapping 计数。
     *  v1.1.0 实测二百八十八：应玩家要求恢复默认 G/H 直触发（二百八十七的
     *  Shift 修饰方案作废——Screen.m_96638_ 的 SRG 归属未做字节码实证，
     *  存在映射错误的可能，导致触发不了；后续如需组合键须先 javap 实证）。
     *  v1.1.0 实测二百八十九：按键捕获/发包加诊断日志（latest.log 搜
     *  "emotion key"）——用户反馈按 G/H 无反应，需定位断点（客户端未捕获
     *  按键 / 包未达服务端 / 服务端判定失败）。 */
    private static final class TickPoll {
        private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft mc = Minecraft.m_91087_();
            if (mc.f_91074_ == null) {
                return; // 无玩家
            }
            while (MAID_PAT != null && MAID_PAT.m_90859_()) {
                LOG.info("emotion key: PAT pressed, sending C2S");
                EmotionNetworking.CHANNEL.sendToServer(new EmotionNetworking.TriggerEmotionPacket(0));
            }
            while (MAID_HUG != null && MAID_HUG.m_90859_()) {
                LOG.info("emotion key: HUG pressed, sending C2S");
                EmotionNetworking.CHANNEL.sendToServer(new EmotionNetworking.TriggerEmotionPacket(1));
            }
        }
    }
}
