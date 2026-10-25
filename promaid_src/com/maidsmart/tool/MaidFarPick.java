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
            Vec3 eye = player.m_20299_(1.0F);
            Vec3 end = eye.m_82549_(player.m_20252_(1.0F).m_82490_(range));
            BlockHitResult block = player.m_9236_().m_45547_(new ClipContext(
                    eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            double wall = block.m_6662_() == HitResult.Type.MISS
                    ? range : eye.m_82554_(block.m_82450_());
            EntityHitResult hit = ProjectileUtil.m_37304_(player.m_9236_(), player,
                    eye, end, new AABB(eye, end).m_82400_(1.0),
                    e -> e instanceof EntityMaid && e.m_6084_());
            if (hit == null) {
                return null;
            }
            if (eye.m_82554_(hit.m_82450_()) > wall + 0.5) {
                return null; // 隔着方块/地形：不隔墙选人
            }
            return (EntityMaid) hit.m_82443_();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFarPick", ignored);
        }
        return null;
    }
}
