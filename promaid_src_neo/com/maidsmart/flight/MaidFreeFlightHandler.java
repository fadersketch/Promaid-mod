package com.maidsmart.flight;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import net.neoforged.bus.api.SubscribeEvent;

/**
 * 实测六百七十三【仿创造飞行 · 事件挂载】——TLM 的 {@code MaidTickEvent} 每 tick 调一次控制器。
 *
 * 用事件而不是 brain 行为的原因见 {@link MaidFreeFlightController} 的类注释
 * （core 行为被实例化了却从不被咨询，原因待作者确认）。
 *
 * <p>【实测六百八十七：分侧】这个事件**两侧都会发**（{@code EntityMaid.tick()} 在客户端也跑），
 * 而控制器做的事全是服务端写操作——所以"先挡客户端"那一道闸放在
 * {@link MaidFreeFlightController#tick} 的第一行（单一口径，这里不再重一遍）。
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
