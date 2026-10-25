package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidBedTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.task.MaidWakeHandler;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 【R1109】被玩家叫醒后 60 秒内断掉 TLM 自带的找床链路（女仆床）。本模组的原版床行为
 * （{@code MaidBedInteropBehavior}）查同一份 {@link MaidWakeHandler} 表，两条睡觉入口一起管住。
 * 只拦"开始找床"这一步，已有的睡眠/起床流程一个字都不动。
 */
@Mixin(MaidBedTask.class)
public abstract class MaidBedWakeBlockMixin {

    @Inject(method = "checkExtraStartConditions(Lnet/minecraft/server/level/ServerLevel;"
            + "Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;)Z",
            at = @At("HEAD"), cancellable = true)
    private void maidsmart$wakeBlock(ServerLevel level, EntityMaid maid,
                                     CallbackInfoReturnable<Boolean> cir) {
        try {
            if (MaidWakeHandler.isBedBlocked(maid)) {
                cir.setReturnValue(false);
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidBedWakeBlockMixin", ignored);
        }
    }
}
