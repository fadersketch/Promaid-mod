package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * v1.1.0 实测二百五十八（用户："给女仆加一个不会踩坏农田的效果"）：
 * 女仆经过/落在农田（农场方块）上不再把它踩成泥土——
 * Forge 的 FarmlandTrampleEvent（农田踩踏入口，走路/跳跃/坠落都经过）：
 * 触发者是女仆 → cancel（农田保持原样，不变成泥土）。
 * 原版踩踏：实体与农田碰撞时按 fallDistance 概率 turnToDirt（变泥土），
 * 女仆移动走位频繁、容易把玩家的农业区踩坏；本守卫让女仆对农田完全"无痕"。
 */
@Mod.EventBusSubscriber(modid = "promaid")
public final class FarmlandGuard {

    private FarmlandGuard() {
    }

    @SubscribeEvent
    public static void onFarmlandTrample(BlockEvent.FarmlandTrampleEvent event) {
        try {
            if (event.getEntity() instanceof EntityMaid) {
                event.setCanceled(true);
            }
        } catch (Throwable ignored) {
        }
    }
}
