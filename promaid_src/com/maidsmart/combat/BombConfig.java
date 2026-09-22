package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraftforge.items.IItemHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 轰炸的全部配置读取（v1.2.4 从 MaidBombing 拆出）。
 * 
 * 23 个 cfg* 取值函数 + 摆姿势动作。每一版配置面板改名都会牵动这里，
 * 集中一处便于对照 MaidSmartConfig。
 */
public final class BombConfig {
    private BombConfig() {
    }

    static boolean cfgMelee() {
        return MaidSmartConfig.COMBAT_BOMBING_MELEE.get();
    }

    static boolean cfgTnt() {
        return MaidSmartConfig.COMBAT_BOMBING_TNT.get();
    }

    static int cfgFuse() {
        return MaidSmartConfig.COMBAT_BOMBING_FUSE.get();
    }

    static int cfgTntFuse() {
        return MaidSmartConfig.COMBAT_BOMBING_TNT_FUSE.get();
    }

    static int cfgTntInterval() {
        return MaidSmartConfig.COMBAT_BOMBING_TNT_INTERVAL.get();
    }

    static boolean cfgTntTrack() {
        return MaidSmartConfig.COMBAT_BOMBING_TNT_TRACK.get();
    }

    static double cfgTntSpeed() {
        return MaidSmartConfig.COMBAT_BOMBING_TNT_SPEED.get();
    }

    static boolean cfgBreakBlocks() {
        return MaidSmartConfig.COMBAT_BOMBING_BREAK_BLOCKS.get();
    }

    static boolean cfgHurtFriendly() {
        return MaidSmartConfig.COMBAT_BOMBING_HURT_FRIENDLY.get();
    }

    static boolean cfgPinkMark() {
        return MaidSmartConfig.COMBAT_BOMBING_PINK_MARK.get();
    }

    static int cfgReclaimSeconds() {
        return MaidSmartConfig.COMBAT_BOMBING_RECLAIM_SECONDS.get();
    }

    static int cfgPlaceGap() {
        return MaidSmartConfig.COMBAT_BOMBING_PLACE_GAP.get();
    }

    static int cfgTntTrackTicks() {
        return MaidSmartConfig.COMBAT_BOMBING_TNT_TRACK_TICKS.get();
    }

    static boolean cfgDimensionGuard() {
        return MaidSmartConfig.COMBAT_BOMBING_DIMENSION_GUARD.get();
    }

    static boolean cfgAnchorNeedsGlowstone() {
        return MaidSmartConfig.COMBAT_BOMBING_ANCHOR_NEEDS_GLOWSTONE.get();
    }

    static int cfgBombInterval() {
        return MaidSmartConfig.COMBAT_BOMBING_BOMB_INTERVAL.get();
    }

    private static boolean cfgPose() {
        return MaidSmartConfig.COMBAT_BOMBING_POSE.get();
    }

    static void pose(EntityMaid maid, ItemStack display, int ticks) {
        // v1.2.2 实测五百九十七：开关判定收进 BombPose.showGated（全模组同一条开关）
        BombPose.showGated(maid, display, ticks);
    }

    static boolean cfgAirPlace() {
        return MaidSmartConfig.COMBAT_BOMBING_AIR_PLACE.get();
    }

    static boolean cfgAirDrop() {
        return MaidSmartConfig.COMBAT_BOMBING_AIR_DROP.get();
    }

    static double cfgTntRange() {
        return MaidSmartConfig.COMBAT_BOMBING_TNT_RANGE.get();
    }

    static double cfgTntBurstRatio() {
        return MaidSmartConfig.COMBAT_BOMBING_TNT_BURST_RATIO.get();
    }

    static int cfgTntBurstCount() {
        return MaidSmartConfig.COMBAT_BOMBING_TNT_BURST_COUNT.get();
    }

    static boolean cfgPinkFire() {
        return MaidSmartConfig.COMBAT_BOMBING_PINK_FIRE.get();
    }
}
