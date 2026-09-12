package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.block.BlockMaidBed;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 床铺互通·方向二（v1.1.0 实测四百一十八，用户："让女仆床和玩家床的代码互通。
 * 女仆和玩家可以互相使用对方的床"）。1.20.1 Forge 版。
 *
 * TLM 的女仆床对玩家是"死"的，堵点有两处：
 * ① {@code BlockMaidBed.isBed(...)} 只在 {@code entity instanceof EntityMaid} 时返回
 *    true，玩家（以及重生点解析时传的 null）走到 super（= instanceof BedBlock）恒 false
 *    ——所以玩家既睡不进去，也没法把它设成重生点；
 * ② 它的 use 方法只处理染料，非染料直接 PASS，从不调 player.startSleepInBed。
 *
 * 本 mixin 把这两道门打开（不改 TLM 源码）：
 * - isBed：玩家 / null（重生点解析）/ 女仆都认，其余走原逻辑；
 * - use：手持可染色染料仍走 TLM 原逻辑（染色优先）；否则服务端调
 *   player.startSleepInBed(head)——原版睡眠/占床/重生点设置全部由既有逻辑接管
 *   （BlockMaidBed 的 OCCUPIED/PART 与 BedBlock 是同一 Property 实例，FACING 来自
 *   HorizontalDirectionalBlock，Forge 的 setBedOccupied/getRespawnPosition 默认
 *   实现直接可用）。
 *
 * 开关 MISC_BED_INTEROP 关掉后 isBed 恢复原状（玩家不能睡女仆床）。
 */
@Mixin(BlockMaidBed.class)
public abstract class MaidBedBlockInteropMixin {

    /** 玩家（含重生点解析传的 null）也认这是床——null 必须放行，否则重生点解析失败 */
    @Inject(method = "isBed", at = @At("HEAD"), cancellable = true)
    private void maidsmart$bedInteropIsBed(BlockState state, net.minecraft.world.level.BlockGetter level,
                                           BlockPos pos, Entity entity,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (!MaidSmartConfig.MISC_BED_INTEROP.get()) {
            return; // 关掉 = 保持 TLM 原版行为
        }
        // null = 原版解析重生点时不带实体；玩家 = 要睡进去。女仆仍走 TLM 自己的
        // EntityMaid 分支（原方法），所以这里只管 null/玩家，不放行其他生物。
        if (entity == null || entity instanceof Player) {
            cir.setReturnValue(true);
        }
    }

    /** 右键女仆床：染料仍交给 TLM 染色；否则（空手/其他物品）玩家躺上去 */
    @Inject(method = "m_6227_", at = @At("HEAD"), cancellable = true)
    private void maidsmart$bedInteropUse(BlockState state, Level level, BlockPos pos, Player player,
                                         InteractionHand hand, BlockHitResult hit,
                                         CallbackInfoReturnable<InteractionResult> cir) {
        if (!MaidSmartConfig.MISC_BED_INTEROP.get()) {
            return;
        }
        if (level.f_46443_) {
            return; // 客户端不处理（原版交互在服务端生效，客户端交给默认反馈）
        }
        // 手持可染色染料 → 放行走 TLM 原染色逻辑（染色优先，不抢交互）
        net.minecraft.world.item.ItemStack stack = player.m_21120_(hand);
        if (stack.m_41720_() instanceof net.minecraft.world.item.DyeItem dye
                && BlockMaidBed.AVAILABLE_COLOR.contains(dye.m_41089_())) {
            return;
        }
        if (player.m_6047_()) {
            return; // 潜行右键不入睡（给玩家留一个"只染色/不躺下"的操作空间）
        }
        BlockPos headPos = state.m_61143_(BlockMaidBed.PART) == BedPart.HEAD
                ? pos : pos.m_121945_(state.m_61143_(net.minecraft.world.level.block.HorizontalDirectionalBlock.f_54117_));
        if (state.m_61143_(BlockMaidBed.OCCUPIED)) {
            player.m_5661_(Component.m_237115_("block.minecraft.bed.occupied"), true);
            cir.setReturnValue(InteractionResult.SUCCESS);
            return;
        }
        if (player instanceof ServerPlayer sp) {
            sp.m_7720_(headPos); // startSleepInBed：睡眠 + 占床 + 重生点
        }
        cir.setReturnValue(InteractionResult.SUCCESS);
    }
}
