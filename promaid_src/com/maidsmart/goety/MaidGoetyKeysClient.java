package com.maidsmart.goety;

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
 * 实测七百四十五·点1【飞行聚晶 · 1.20.1 非 OP 入口 · 快捷键】——准星对准女仆按一下，
 * 切换她的飞行聚晶自动档。
 *
 * 【默认不绑定】需求方选的方案 B：玩家自己在按键设置里绑（Promaid 分类下）。
 * 与 {@code MaidFreeFlightKeysClient}（创造飞行）同一格式、同一分类，互不冲突。
 *
 * 【取谁】优先准星命中的实体（hitResult 是 EntityHitResult 且为女仆）；没命中时兜底取
 * 5 格内最近的**自有**女仆。真正的权限校验在服务端（主人或 OP + 8 格内，见网络层）。
 *
 * 【1.20.1 落法】与 {@code MaidFreeFlightKeysClient} 同款：{@code Dist.CLIENT} 的 MOD 总线
 * {@code RegisterKeyMappingsEvent} 注册键位，客户端 tick 轮询 {@code consumeClick()}
 * （Forge 1.20.1 用 {@code TickEvent.ClientTickEvent}，非 {@code ClientTickEvent.Post}）。
 */
@Mod.EventBusSubscriber(modid = "promaid", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class MaidGoetyKeysClient {

    /** 切换准星女仆的飞行聚晶自动档（默认未绑定） */
    public static KeyMapping GOETY_TOGGLE = null;

    private MaidGoetyKeysClient() {
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        GOETY_TOGGLE = new KeyMapping("key.promaid.goety_toggle",
                GLFW.GLFW_KEY_UNKNOWN, "key.categories.promaid");
        event.register(GOETY_TOGGLE);
        // tick 轮询挂 FORGE 总线（客户端启动即生效），与 MaidFreeFlightKeysClient 同款
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
                if (mc.f_91074_ == null || GOETY_TOGGLE == null) {
                    return;
                }
                while (GOETY_TOGGLE.m_90859_()) {
                    EntityMaid maid = targetMaid(mc);
                    if (maid == null) {
                        mc.f_91074_.m_5661_(Component.m_237113_(
                                "\u00a77没有对准女仆（准星对准她，或站到她 5 格内）"), true);
                        continue;
                    }
                    String uid = maid.m_20148_().toString();
                    Boolean cur = MaidGoetyAuto.cachedClient(uid);
                    boolean next = !(cur != null && cur);
                    MaidGoetyNetworking.CHANNEL.sendToServer(
                            new MaidGoetyNetworking.TogglePacket(uid, (byte) 1, next));
                    mc.f_91074_.m_5661_(Component.m_237113_("\u00a7e" + maid.m_7755_().getString()
                            + " \u00a7r飞行聚晶自动档：" + (next ? "\u00a7a开" : "\u00a77关")), true);
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
