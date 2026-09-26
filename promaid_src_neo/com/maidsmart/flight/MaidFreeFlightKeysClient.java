package com.maidsmart.flight;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * 实测六百七十八【仿创造飞行 · 快捷键】——准星对准女仆按一下，切换她的创造飞行开关。
 *
 * 【默认不绑定】需求方选的方案 B：玩家自己在按键设置里绑（Promaid 分类下）。
 * 不与现有键位冲突（摸头 G / 抱 hug H / 建造旋转等都在同一分类里）。
 *
 * 【取谁】优先准星命中的实体（`hitResult` 是 EntityHitResult 且为女仆）；没命中时兜底取
 * 5 格内最近的**自有**女仆。真正的权限校验在服务端（主人或 OP + 4 格内，见网络层）。
 */
@EventBusSubscriber(modid = "promaid", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
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
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(new TickPoll());
    }

    private static final class TickPoll {

        @SubscribeEvent
        public void onClientTick(ClientTickEvent.Post event) {
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null || FREE_FLIGHT_TOGGLE == null) {
                    return;
                }
                while (FREE_FLIGHT_TOGGLE.consumeClick()) {
                    EntityMaid maid = targetMaid(mc);
                    if (maid == null) {
                        mc.player.displayClientMessage(
                                Component.literal("\u00a77没有对准女仆（准星对准她，或站到她 5 格内）"), true);
                        continue;
                    }
                    String uid = maid.getUUID().toString();
                    Boolean cur = MaidFreeFlightFlags.cachedClient(uid);
                    boolean next = !(cur != null ? cur : MaidFreeFlightFlags.globalOn());
                    PacketDistributor.sendToServer(
                            new MaidFreeFlightNetworking.TogglePacket(uid, (byte) 1, next));
                    mc.player.displayClientMessage(Component.literal("\u00a7e" + maid.getName().getString()
                            + " \u00a7r仿创造飞行：" + (next ? "\u00a7a开" : "\u00a77关")), true);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 准星命中的女仆优先；没命中就取 5 格内最近的自有女仆 */
    private static EntityMaid targetMaid(Minecraft mc) {
        try {
            if (mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof EntityMaid m) {
                return m;
            }
            if (mc.player == null || mc.level == null) {
                return null;
            }
            List<EntityMaid> near = mc.level.getEntitiesOfClass(EntityMaid.class,
                    mc.player.getBoundingBox().inflate(5.0),
                    m -> m.isAlive() && m.isOwnedBy(mc.player));
            return near.stream().min((a, b) -> Double.compare(
                    a.distanceToSqr(mc.player), b.distanceToSqr(mc.player))).orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
