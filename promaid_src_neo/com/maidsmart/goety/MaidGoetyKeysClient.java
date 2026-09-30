package com.maidsmart.goety;

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
 * 实测七百四十五·点1【飞行聚晶 · 非 OP 的快捷键】——准星对准女仆按一下，切换她的"飞行聚晶自动档"。
 *
 * <p>玩家原话：「聚晶必须要使用指令这些 OP 权限才可以使用吗？常规生存不能使用？」——七百一十八
 * 那一版只有 OP 命令，普通玩家开不了。这里照搬"仿创造飞行"（同类飞行功能）那套：
 * **默认不绑定**（玩家自己在按键设置 Promaid 分类里绑），准星对准她按一下即可，权限由服务端校验
 * （主人或 OP + 8 格内，见 {@link MaidGoetyNetworking}）。
 *
 * <p>【为什么切的是"自动档"而不是"立刻飞"】自动档（{@link MaidGoetyAuto}）才是"她平时会不会用
 * 聚晶"的总开关：开了之后她自己会追主人、在飞行任务里接敌盘旋。而"立刻飞往某坐标/某目标"是
 * 一次性命令，快捷键没法指定参数——所以快捷键切自动档，与女仆界面里那一行是同一个东西。
 */
@EventBusSubscriber(modid = "promaid", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class MaidGoetyKeysClient {

    /** 切换准星女仆的飞行聚晶自动档（默认未绑定）。 */
    public static KeyMapping GOETY_TOGGLE = null;

    private MaidGoetyKeysClient() {
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        GOETY_TOGGLE = new KeyMapping("key.promaid.goety_toggle",
                GLFW.GLFW_KEY_UNKNOWN, "key.categories.promaid");
        event.register(GOETY_TOGGLE);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(new TickPoll());
    }

    private static final class TickPoll {

        @SubscribeEvent
        public void onClientTick(ClientTickEvent.Post event) {
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null || GOETY_TOGGLE == null) {
                    return;
                }
                while (GOETY_TOGGLE.consumeClick()) {
                    EntityMaid maid = targetMaid(mc);
                    if (maid == null) {
                        mc.player.displayClientMessage(
                                Component.literal("\u00a77没有对准女仆（准星对准她，或站到她 5 格内）"), true);
                        continue;
                    }
                    String uid = maid.getUUID().toString();
                    Boolean cur = MaidGoetyAuto.cachedClient(uid);
                    boolean next = !(cur != null && cur);
                    PacketDistributor.sendToServer(
                            new MaidGoetyNetworking.TogglePacket(uid, (byte) 1, next));
                    mc.player.displayClientMessage(Component.literal("\u00a7e" + maid.getName().getString()
                            + " \u00a7r飞行聚晶自动档：" + (next ? "\u00a7a开" : "\u00a77关")), true);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 准星命中的女仆优先；没命中就取 5 格内最近的自有女仆。 */
    private static EntityMaid targetMaid(Minecraft mc) {
        try {
            if (mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof EntityMaid m) {                return m;
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
