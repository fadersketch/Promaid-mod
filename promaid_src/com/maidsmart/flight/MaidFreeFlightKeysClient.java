package com.maidsmart.flight;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * 实测七百〇二【仿创造飞行 · 1.20.1 精简版 · 快捷键】——准星对准女仆按一下，切换她的创造飞行开关。
 *
 * 【默认不绑定】需求方选的方案 B：玩家自己在按键设置里绑（Promaid 分类下）。
 * 不与现有键位冲突（摸头 G / 抱 hug H / 建造旋转等都在同一分类里）。
 *
 * 【取谁】优先准星命中的实体（hitResult 是 EntityHitResult 且为女仆）；没命中时兜底取
 * 5 格内最近的**自有**女仆。真正的权限校验在服务端（主人或 OP + 8 格内，见网络层）。
 *
 * 【1.20.1 落法】与 {@code EmotionKeysClient}/{@code BuildKeysClient} 同款：
 * {@code Dist.CLIENT} 的 MOD 总线 {@code RegisterKeyMappingsEvent} 注册键位，客户端 tick 轮询
 * {@code consumeClick()}（Forge 1.20.1 用 {@code TickEvent.ClientTickEvent}，非
 * {@code ClientTickEvent.Post}）。
 */
@Mod.EventBusSubscriber(modid = "promaid", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class MaidFreeFlightKeysClient {

    /** 切换准星女仆的创造飞行（默认未绑定） */
    public static KeyMapping FREE_FLIGHT_TOGGLE = null;

    private MaidFreeFlightKeysClient() {
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        FREE_FLIGHT_TOGGLE = new KeyMapping("key.promaid.free_flight_toggle",
                GLFW.GLFW_KEY_UNKNOWN, "key.categories.promaid");
        event.register(FREE_FLIGHT_TOGGLE);
        // tick 轮询挂 FORGE 总线（客户端启动即生效），与 EmotionKeysClient 同款
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new TickPoll());
    }

    private static final class TickPoll {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            try {
                Minecraft mc = Minecraft.m_91087_();
                if (mc.f_91074_ == null || FREE_FLIGHT_TOGGLE == null) {
                    return;
                }
                while (FREE_FLIGHT_TOGGLE.m_90859_()) {
                    EntityMaid maid = targetMaid(mc);
                    if (maid == null) {
                        mc.f_91074_.m_5661_(Component.m_237113_("\u00a77没有对准女仆（准星对准她，或站到她 5 格内）"), true);
                        continue;
                    }
                    String uid = maid.m_20148_().toString();
                    Boolean cur = MaidFreeFlightFlags.cachedClient(uid);
                    boolean next = !(cur != null ? cur : MaidFreeFlightFlags.globalOn());
                    MaidFreeFlightNetworking.CHANNEL.sendToServer(
                            new MaidFreeFlightNetworking.TogglePacket(uid, (byte) 1, next));
                    mc.f_91074_.m_5661_(Component.m_237113_("\u00a7e" + maid.m_7755_().getString()
                            + " \u00a7r仿创造飞行：" + (next ? "\u00a7a开" : "\u00a77关")), true);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 准星命中的女仆优先；没命中就取 5 格内最近的自有女仆 */
    private static EntityMaid targetMaid(Minecraft mc) {
        try {
            if (mc.f_91077_ instanceof EntityHitResult hit && hit.m_82443_() instanceof EntityMaid m) {
                return m;
            }
            if (mc.f_91074_ == null || mc.f_91073_ == null) {
                return null;
            }
            List<EntityMaid> near = mc.f_91073_.m_45976_(EntityMaid.class,
                    mc.f_91074_.m_20191_().m_82400_(5.0));
            return near.stream()
                    .filter(m -> m.m_6084_() && m.m_21830_(mc.f_91074_))
                    .min((a, b) -> Double.compare(a.m_20280_(mc.f_91074_), b.m_20280_(mc.f_91074_)))
                    .orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
