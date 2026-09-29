package com.maidsmart.client;

import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 【实测七百二十四】"右击载具不再转玩家视角"的客户端专属那一半。
 *
 * <h2>为什么单独一个客户端类</h2>
 * 视角复述要读 {@code net.minecraft.client.Minecraft}，而 {@code RideBindManager} 两侧都会加载
 * （NeoForge 专用服务器也加载它）——在那边内联客户端类型会触发 RuntimeDistCleaner 的
 * DEDICATED_SERVER 崩（本工程 {@code PromaidClientSetup} 类注释里记着这条教训）。
 * 所以"每客户端 tick 复述一次朝向"只放在本类：只由客户端分支注册，服务端永不加载。
 *
 * <h2>它干什么</h2>
 * 右击载具那一下，SWB 的 {@code VehicleVecUtils.setDriverAngle} 会先把玩家转向车头
 * （在 {@code player.startRiding} 之前，反编译实证）——我们随后拦下了登乘，却拦不回已经转过的朝向。
 * {@code RideBindManager} 在客户端右击时打一个"视角钉子"（快照 + 4 拍时效），本类每客户端 tick
 * 把还活着的钉子复述回去，把那一拍抹平。玩家自己转视角不受影响（钉子 4 拍后自动失效）。
 */
public final class RideBatonViewClamp {
    private static boolean registered;

    private RideBatonViewClamp() {
    }

    /** 注册客户端 tick（幂等；只由客户端分支调用）。 */
    public static void ensureRegistered() {
        if (registered) {
            return;
        }
        registered = true;
        NeoForge.EVENT_BUS.addListener(RideBatonViewClamp::onClientTick);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return;
        }
        com.maidsmart.combat.RideBindManager.clampPinnedView(mc.player);
    }
}
