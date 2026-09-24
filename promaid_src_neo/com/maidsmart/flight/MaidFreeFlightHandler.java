package com.maidsmart.flight;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import net.neoforged.bus.api.SubscribeEvent;

/**
 * v1.2.5 实测六百五十六【仿创造飞行 · 事件挂载】——TLM 的 {@code MaidTickEvent} 每 tick 调一次控制器。
 *
 * 用事件而不是 brain 行为的原因见 {@link MaidFreeFlightController} 的类注释
 * （core 行为被实例化了却从不被咨询，原因待作者确认）。
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
