package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import net.neoforged.bus.api.SubscribeEvent;

/**
 * 实测八百一十四【干活时把移速压回 0 好感档 · 事件挂载】——TLM 的 {@code MaidTickEvent}
 * 每 tick 调一次 {@link MaidWorkSpeedLimiter}。
 *
 * <p>与 {@code MaidFreeFlightHandler} 同一条路：用事件（而不是 brain 行为）驱动，因为属性
 * 修改每 tick 都要自纠正一次，而且必须在她的 {@code tick()} 里、brain 之前跑——
 * {@code MaidTickEvent} 正好在 {@code EntityMaid.tick()} 里、早于 brain tick 发出。
 *
 * <p>事件两侧都会发（{@code EntityMaid.tick()} 客户端也跑），故在
 * {@link MaidWorkSpeedLimiter#tick} 第一行就挡掉客户端——单一口径，这里不再重一遍。
 *
 * <p>注册：由 {@code ProMaidExtension} 构造器显式 {@code NeoForge.EVENT_BUS.register(new ...)}，
 * 与 {@code MaidFreeFlightHandler} 同一条总线、同一个事件。
 */
public final class MaidWorkSpeedHandler {

    @SubscribeEvent
    public void onMaidTick(MaidTickEvent event) {
        try {
            MaidWorkSpeedLimiter.tick(event.getMaid());
            // 【R1146】服务端每 20 tick 兜底：旧存档里叠着的压缩盒拆成单只，防止拆 stack 复制内容
            if (event.getMaid().level() instanceof net.minecraft.server.level.ServerLevel sl
                    && sl.getGameTime() % 20L == 0L) {
                com.maidsmart.box.CompressionBoxMaidInv.sanitizeIfNeeded(event.getMaid());
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidWorkSpeedHandler", ignored);
        }
    }
}
