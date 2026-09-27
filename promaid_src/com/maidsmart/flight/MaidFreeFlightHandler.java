package com.maidsmart.flight;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 实测七百〇二【仿创造飞行 · 1.20.1 精简版 · 事件挂载】——TLM 的 {@code MaidTickEvent} 每 tick
 * 调一次控制器。
 *
 * 用事件而不是 brain 行为的原因见 {@link MaidFreeFlightController} 的类注释
 * （core 行为被实例化了却从不被咨询）。
 *
 * <p>【分侧】这个事件**两侧都会发**（{@code EntityMaid.tick()} 在客户端也跑），而控制器做的事
 * 全是服务端写操作——所以"先挡客户端"那一道闸放在 {@link MaidFreeFlightController#tick} 的
 * 第一行（单一口径，这里不再重一遍）。
 *
 * <p>【1.20.1 挂载方式】Forge 上由 {@code ProMaidMod} 构造器显式
 * {@code MinecraftForge.EVENT_BUS.register(new MaidFreeFlightHandler())}——
 * 与本树 {@code GunnerTetherManager} 同一条总线、同一个事件（那里用 {@code @Mod.EventBusSubscriber}
 * 自动注册，这里为了与 1.21.1 树保持"一个 handler 类"的对称性走显式注册，两者等价）。
 */
public final class MaidFreeFlightHandler {

    @SubscribeEvent
    public void onMaidTick(MaidTickEvent event) {
        try {
            MaidFreeFlightController.tick(event.getMaid());
        } catch (Throwable ignored) {
        }
    }
}
