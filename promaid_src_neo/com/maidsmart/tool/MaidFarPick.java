package com.maidsmart.tool;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 远距离选女仆（R1108）：手持武装拴绳/指标石时，从眼睛沿视线做一次最长 512 格的实体射线，
 * 命中女仆就当作"点了她一下"。原版 {@code EntityInteract} 只在玩家交互距离（≈4.5 格）内
 * 才会发生，这个入口补上 512 格那一档。
 *
 * <p>墙后不选：先做一次同长度的方块裁剪，女仆必须比方块命中点更近。异常一律静默——
 * 最坏只是这一下没选中，不影响物品本身的任何行为。
 */
public final class MaidFarPick {

    /** 与指标石锁定方块口径一致 */
    public static final double PICK_RANGE = 512.0;

    private MaidFarPick() {
    }

    public static EntityMaid pick(ServerPlayer player) {
        return pick(player, PICK_RANGE);
    }

    public static EntityMaid pick(ServerPlayer player, double range) {
        try {
            Vec3 eye = player.getEyePosition();
            Vec3 end = eye.add(player.getViewVector(1.0F).scale(range));
            BlockHitResult block = player.level().clip(new ClipContext(
                    eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            double wall = block.getType() == HitResult.Type.MISS
                    ? range : eye.distanceTo(block.getLocation());
            EntityHitResult hit = ProjectileUtil.getEntityHitResult(player.level(), player,
                    eye, end, new AABB(eye, end).inflate(1.0),
                    e -> e instanceof EntityMaid && e.isAlive());
            if (hit == null) {
                return null;
            }
            if (eye.distanceTo(hit.getLocation()) > wall + 0.5) {
                return null; // 隔着方块/地形：不隔墙选人
            }
            return (EntityMaid) hit.getEntity();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFarPick", ignored);
        }
        return null;
    }
}
