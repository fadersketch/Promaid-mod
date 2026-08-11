package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidShearTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.5.189：剪羊毛掉落【直进女仆背包】。
 *
 * TLM MaidShearTask.start 剪完毛后掉落物用 m_5552_（spawnAtLocation）落在羊旁边
 * （可能滚落/被草吞/玩家没注意到），与 farm 收割（dropResourcesToMaidInv 直进背包）
 * 不一致。这里在 start TAIL 后扫描剪毛点附近的 ItemEntity，用女仆 pickupItem 收集
 * 进背包（与 FarmSweepMixin.collectDrops 同款机制，无作弊）。
 * 总开关：misc.produceTaskEnhance（产出型任务增强）。
 */
@Mixin(MaidShearTask.class)
public abstract class MaidShearDropMixin {

    @Inject(method = "start", at = @At("TAIL"))
    private void maidsmart$collectShearDrops(ServerLevel world, EntityMaid maid, long gameTime, CallbackInfo ci) {
        if (!com.maidsmart.config.MaidSmartConfig.MISC_PRODUCE_TASK_ENHANCE.get()) {
            return;
        }
        try {
            // 女仆周围 4 格内的羊毛/掉落物——剪毛点必然在女仆附近（start 内贴近 2 格）
            net.minecraft.world.phys.AABB box = maid.m_20191_().m_82400_(4.0);
            for (net.minecraft.world.entity.item.ItemEntity e :
                    world.m_45976_(net.minecraft.world.entity.item.ItemEntity.class, box)) {
                if (e.m_6084_()) {
                    maid.pickupItem(e, false);
                }
            }
        } catch (Exception ignored) {
        }
    }
}
