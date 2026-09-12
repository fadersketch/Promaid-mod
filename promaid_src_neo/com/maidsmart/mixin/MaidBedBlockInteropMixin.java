package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.block.BlockMaidBed;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * 床铺互通·方向二（v1.1.0 实测四百一十八，用户："让女仆床和玩家床的代码互通。
 * 女仆和玩家可以互相使用对方的床"）。1.21.1 NeoForge 版（Mojmap 名）。
 *
 * TLM 的女仆床对玩家是"死"的，NeoForge 版堵点有三处：
 * ① {@code BlockMaidBed.isBed(...)} 只在 {@code entity instanceof EntityMaid} 时返回
 *    true，玩家（以及重生点解析传的 null）恒 false——睡不进去；
 * ② {@code useItemOn} 只处理染料，非染料直接 PASS_TO_DEFAULT，从不调 startSleepInBed；
 * ③ NeoForge 的 {@code getRespawnPosition}（IBlockExtension 默认）恒返回 empty()，
 *    而 1.21.1 的重生点解析只在【非强制】分支问这个钩子——不实现的话，玩家睡是睡
 *    下了、重生点也"设"了，但死亡复活时会报"重生方块缺失"并回世界出生点。
 *
 * 本 mixin 把三道门都打开（不改 TLM 源码）：
 * - isBed：玩家 / null 认；女仆仍走 TLM 原 EntityMaid 分支；
 * - useItemOn：染料仍走 TLM 染色；否则服务端调 player.startSleepInBed(head)；
 * - getRespawnPosition：直接在本 mixin 声明同签名方法（合并进 BlockMaidBed 覆盖接口
 *   默认），委托原版 BedBlock.canSetSpawn + findStandUpPosition——与睡原版床的落地
 *   算法完全一致。
 *
 * BlockMaidBed 的 OCCUPIED/PART 与 BedBlock 是同一 Property 实例、FACING 来自
 * HorizontalDirectionalBlock，所以 setBedOccupied/getBedDirection 用 NeoForge 默认
 * 实现即可。开关 MISC_BED_INTEROP 关掉后全部恢复 TLM 原版行为。
 */
@Mixin(BlockMaidBed.class)
public abstract class MaidBedBlockInteropMixin {

    /** 玩家（含重生点解析传的 null）也认这是床——null 必须放行，否则重生点解析失败 */
    @Inject(method = "isBed", at = @At("HEAD"), cancellable = true)
    private void maidsmart$bedInteropIsBed(BlockState state, net.minecraft.world.level.BlockGetter level,
                                           BlockPos pos, LivingEntity entity,
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
    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void maidsmart$bedInteropUse(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                         Player player, InteractionHand hand, BlockHitResult hit,
                                         CallbackInfoReturnable<ItemInteractionResult> cir) {
        if (!MaidSmartConfig.MISC_BED_INTEROP.get()) {
            return;
        }
        if (level.isClientSide()) {
            return; // 客户端不处理（交互在服务端生效，客户端交给默认反馈）
        }
        // 手持可染色染料 → 放行走 TLM 原染色逻辑（染色优先，不抢交互）
        if (stack.getItem() instanceof DyeItem dye
                && BlockMaidBed.AVAILABLE_COLOR.contains(dye.getDyeColor())) {
            return;
        }
        if (player.isShiftKeyDown()) {
            return; // 潜行右键不入睡（留一个"只染色/不躺下"的操作空间）
        }
        BlockPos headPos = state.getValue(BlockMaidBed.PART) == BedPart.HEAD
                ? pos : pos.relative(state.getValue(HorizontalDirectionalBlock.FACING));
        if (state.getValue(BlockMaidBed.OCCUPIED)) {
            player.displayClientMessage(Component.translatable("block.minecraft.bed.occupied"), true);
            cir.setReturnValue(ItemInteractionResult.SUCCESS);
            return;
        }
        if (player instanceof ServerPlayer sp) {
            sp.startSleepInBed(headPos); // 睡眠 + 占床 + 设重生点
        }
        cir.setReturnValue(ItemInteractionResult.SUCCESS);
    }

    /**
     * 让玩家能把女仆床设为生效的重生点（覆盖 IBlockExtension 恒空默认值）：
     * 照原版床的算法委托 BedBlock——只在维度允许且找得到站立位时返回。
     */
    public Optional<ServerPlayer.RespawnPosAngle> getRespawnPosition(BlockState state, EntityType<?> type,
                                                                    LevelReader level, BlockPos pos,
                                                                    float orientation) {
        if (!MaidSmartConfig.MISC_BED_INTEROP.get()) {
            return Optional.empty();
        }
        if (!(level instanceof Level lvl) || !BedBlock.canSetSpawn(lvl)) {
            return Optional.empty();
        }
        return BedBlock.findStandUpPosition(type, level, pos,
                        state.getValue(HorizontalDirectionalBlock.FACING), orientation)
                .map(vec -> ServerPlayer.RespawnPosAngle.of(vec, pos));
    }
}
