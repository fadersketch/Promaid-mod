package com.maidsmart.client;

import net.minecraftforge.event.TickEvent;

/**
 * 【实测七百二十四】"右击载具不再转玩家视角"的客户端专属那一半。
 *
 * <h2>为什么单独一个客户端类</h2>
 * 视角复述要读 {@code net.minecraft.client.Minecraft}，而 {@code RideBindManager} 两侧都会加载
 * （Forge 专用服务器也加载它）——在那边内联客户端类型会在专用服务器上抛
 * {@code NoClassDefFoundError / Attempted to load class ... for invalid dist DEDICATED_SERVER}
 * （本工程 {@code PromaidClientSetup} 类注释里记着这条教训）。
 * 所以"每客户端 tick 复述一次朝向"只放在本类：只由客户端分支注册，服务端永不加载。
 *
 * <h2>它干什么</h2>
 * 右击载具那一下，SWB 的 {@code VehicleVecUtils.setDriverAngle} 会先把玩家转向车头
 * （在 {@code player.startRiding} 之前，反编译实证）——我们随后拦下了登乘，却拦不回已经转过的朝向。
 * {@code RideBindManager} 在客户端右击时打一个"视角钉子"（快照 + 4 拍时效），本类每客户端 tick
 * 把还活着的钉子复述回去，把那一拍抹平。玩家自己转视角不受影响（钉子 4 拍后自动失效）。
 *
 * <p>【1.20.1 侧差异】事件换成 Forge 的 {@code TickEvent.ClientTickEvent}（{@code Phase.END} 才是
 * "这一拍结束时"，与 1.21 的 {@code ClientTickEvent.Post} 同口径），注册走
 * {@code MinecraftForge.EVENT_BUS.addListener}。
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
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(RideBatonViewClamp::onClientTick);
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // 本树（1.20.1）客户端类同样走 SRG 名：m_91087_ = Minecraft.getInstance，f_91074_ = mc.player
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.m_91087_();
        if (mc == null || mc.f_91074_ == null) {
            return;
        }
        com.maidsmart.combat.RideBindManager.clampPinnedView(mc.f_91074_);
    }
}
