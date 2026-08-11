package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidCollectHoneyTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.5.189：养蜂闭环——采蜜后给蜂箱点火（防蛰）。
 *
 * TLM MaidCollectHoneyTask 只采蜜不点火：蜂箱下方没有营火时，蜜蜂会主动蜇采蜜者。
 * 这里在 start TAIL 后：若蜂箱下方 1 格无营火/篝火、且女仆背包有营火（campfire），
 * 则放置一个营火（防蛰，原版机制——蜂箱下方有营火 = 蜜蜂不攻击）。
 * 需要重新采集蜂蜜时（采集动作把 honey_level 重置为 0），会自动补上营火。
 * 总开关：misc.produceTaskEnhance。
 */
@Mixin(MaidCollectHoneyTask.class)
public abstract class MaidHoneyIgniteMixin {

    @Inject(method = "start", at = @At("TAIL"))
    private void maidsmart$igniteBelow(ServerLevel level, EntityMaid maid, long gameTime, CallbackInfo ci) {
        if (!com.maidsmart.config.MaidSmartConfig.MISC_PRODUCE_TASK_ENHANCE.get()) {
            return;
        }
        try {
            BlockPos hive = maid.m_20183_(); // 采蜜后女仆就在蜂箱旁
            BlockPos below = hive.m_7918_(0, -1, 0);
            // 找蜂箱：女仆周围 6 格内最近的蜂箱（honey_level 可被重置的方块）
            BlockPos hivePos = null;
            int best = Integer.MAX_VALUE;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos p = hive.m_7918_(dx, 0, dz);
                    BlockState st = level.m_8055_(p);
                    if (st.m_60734_() instanceof net.minecraft.world.level.block.BeehiveBlock) {
                        int d = Math.abs(dx) + Math.abs(dz);
                        if (d < best) {
                            best = d;
                            hivePos = p;
                        }
                    }
                }
            }
            if (hivePos == null) {
                return;
            }
            BlockPos fireBelow = hivePos.m_7918_(0, -1, 0);
            BlockState belowState = level.m_8055_(fireBelow);
            if (belowState.m_60734_() instanceof CampfireBlock) {
                return; // 已有营火 → 无需重复放置
            }
            // 下方不是空气/可替换（被占）→ 不强行放（m_60795_=isAir、m_60819_=getFluidState、
            // FluidState.m_76178_ = isEmpty）
            if (!belowState.m_60795_() && !belowState.m_60819_().m_76178_()) {
                return;
            }
            CombinedInvWrapper inv = maid.getAvailableInv(true);
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (stack.m_41619_() || !stack.m_150930_(net.minecraft.world.item.Items.f_42781_)) {
                    continue; // 不是营火（campfire，SRG f_42781_ 实证 = Blocks.f_50683_ 的物品）
                }
                stack.m_41774_(1);
                level.m_7731_(fireBelow, net.minecraft.world.level.block.Blocks.f_50683_.m_49966_(), 3);
                maid.m_6674_(InteractionHand.MAIN_HAND);
                return;
            }
        } catch (Exception ignored) {
        }
    }
}
