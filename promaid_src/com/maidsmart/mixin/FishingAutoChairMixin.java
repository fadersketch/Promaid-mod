package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidFindSitTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.5.252r：钓鱼自动坐垫【注入壳】——逻辑全部在普通类
 * com.maidsmart.fishing.FishingChairService。
 *
 * v1.5.252q 崩溃实证：普通代码（ProMaidExtension）直接调用本 mixin 的静态方法时，
 * JVM 判定 mixin 类 "is invalid" → NoClassDefFoundError → 进世界第一 tick 即崩。
 * 因此本类只保留 @Inject 注入点，所有实现委托给普通类（mixin 可调用普通类，
 * 普通类不可调用 mixin 类）。
 *
 * 功能：
 * 1. start TAIL：原逻辑没找到椅子/船时，自动在最近水域岸边生成带标记坐垫并
 *    直接坐上（有现成坐垫/船则走过去坐，走原模组钓鱼流程）；
 * 2. lambda$start$2 HEAD：马上要生成坐垫时压掉原 no_sit 气泡（防两句打架）。
 */
@Mixin(MaidFindSitTask.class)
public abstract class FishingAutoChairMixin {
    @Shadow
    private Entity sitEntity;

    @Inject(method = "lambda$start$2", at = @At("HEAD"), cancellable = true)
    private void maidsmart$suppressNoSitBubble(EntityMaid maid, CallbackInfo ci) {
        if (com.maidsmart.fishing.FishingChairService.shouldSuppressNoSit(maid)) {
            ci.cancel();
        }
    }

    @Inject(method = "start", at = @At("TAIL"))
    private void maidsmart$autoChair(ServerLevel world, EntityMaid maid, long gameTime, CallbackInfo ci) {
        com.maidsmart.fishing.FishingChairService.tryAutoChair(world, maid, this.sitEntity);
        if (this.sitEntity != null && this.sitEntity.m_6084_()) {
            // v1.5.275：记录原版椅子目标位置（高频维持走位——tickKeepSeatWalk 用）
            com.maidsmart.fishing.FishingChairService.recordSeatTarget(maid, this.sitEntity.m_20183_());
        }
    }
}
