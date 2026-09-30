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
        // 【实测七百四十一·点1】指挥棒左击换座：客户端只负责"认出这一下并上报"。
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(RideBatonViewClamp::onInteractionKey);
    }

    /**
     * 【实测七百四十一·点1】手持骑乘指挥棒**左击** → 请求与女仆互换座位（主驾 ↔ 副驾）。
     *
     * <p>玩家原话：「如果玩家处于副座，可以通过手持骑乘指挥棒进行左击，从而把自己交换到主座位。
     * 再左击一下再换回去。」
     *
     * <h2>为什么挂在 {@code InteractionKeyMappingTriggered} 上</h2>
     * 反编译 {@code Minecraft.startAttack()} 实证：左击键在**真正发起攻击之前**会先发这个事件
     * （{@code ClientHooks.onClickInput(keyAttack, MAIN_HAND)}），被取消就**整段跳过**。所以在这里：
     * <ol>
     *   <li>认出"手里拿着指挥棒 + 自己正坐在一辆卓越前线载具上"→ 发换座请求包并**取消这一下**
     *       （否则左击会顺便打一下自己坐的车，把它敲掉血）；</li>
     *   <li>其余一切情况**一个字节都不动**（拿别的物品左击、拿指挥棒左击实体/方块——
     *       后者的"选中/解绑"语义在右击那条链上，左击不该掺和）。</li>
     * </ol>
     * 服务端再核一遍（见 {@code RideBindManager.handleSwapSeatRequest}），客户端这份只是"顺手"。
     */
    private static void onInteractionKey(
            net.minecraftforge.client.event.InputEvent.InteractionKeyMappingTriggered event) {
        try {
            if (!event.isAttack()) {
                return;
            }
            // 本树（1.20.1）客户端类同样走 SRG 名：m_91087_ = Minecraft.getInstance，f_91074_ = mc.player
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.m_91087_();
            if (mc == null || mc.f_91074_ == null) {
                return;
            }
            net.minecraft.client.player.LocalPlayer p = mc.f_91074_;
            boolean baton = com.maidsmart.combat.RideBindManager.holdsBatonClient(p);
            if (!baton) {
                return;
            }
            net.minecraft.world.entity.Entity v = p.m_20202_();
            if (v == null || !com.maidsmart.combat.RideBindManager.isModVehicleClient(v)) {
                return;
            }
            // 认得出才吞这一下 + 上报（服务端自己算座位、自己校验，不信任这里推出来的任何东西）
            com.maidsmart.combat.MaidSeatNetworking.requestSwapSeat(v.m_19879_());
            event.setCanceled(true);
        } catch (Throwable ignored) {
        }
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
