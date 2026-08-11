package com.maidsmart.follow;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * v1.5.142：女仆跟随主人跨维度传送。
 *
 * 背景：TLM 原生 MaidFollowOwnerTask 的 ownerStateConditions 要求
 * maid.f_19853_ == owner.f_19853_（同一维度）→ 主人穿过传送门/传送进另一个维度后，
 * 跟随女仆被留在原地（跟随行为直接不启动）。TLM 的 EntityMaid.changeDimension
 * override 又只会"原地随机瞬移并返回 null"（TeleportHelper 在当前位置 ±16 格随机
 * 落点），无法用于跨维度。
 *
 * 方案：每 5 秒扫描一次全服女仆——跟随模式（非在家模式、未坐下、未骑乘、存活）
 * 且与主人不在同一维度 → 手动跨维度移动：
 *   setRemoved(CHANGED_DIMENSION) → setPos(主人身边落点) → addFreshEntity(新维度)
 * 落点取主人身边第一个"脚下实心、站立格空气"的位置（向下最多 16 格）；
 * 找不到可站格（主人在高空/虚空飞行）→ 本次不传，等主人落地后再跟。
 *
 * 坐着的女仆不拉（建造模式强制坐下 = 玩家明确想让她留在原地，见
 * MaidBuildBehavior.tickBuildSit）；在家模式 = 不跟随，同样不拉。
 */
public final class MaidDimensionFollow {
    private MaidDimensionFollow() {
    }

    /** 每 100 tick（5 秒）由 ProMaidExtension.onServerTick 调用 */
    public static void tick(MinecraftServer server) {
        if (!com.maidsmart.config.MaidSmartConfig.MISC_DIMENSION_FOLLOW.get()) {
            return;
        }
        for (ServerLevel level : server.m_129785_()) {
            for (Entity e : level.m_8583_()) {
                if (e instanceof EntityMaid maid) {
                    followIfCrossDimension(maid);
                }
            }
        }
    }

    private static void followIfCrossDimension(EntityMaid maid) {
        try {
            if (maid.m_213877_() || maid.m_21224_()) {
                return; // 已移除/死亡
            }
            if (maid.m_20159_()) {
                return; // 骑乘中（乘客跨维度跟随由载具负责，不单独拉）
            }
            if (maid.isMaidInSittingPose()) {
                return; // 坐着的女仆不拉（建造强制坐下 = 玩家要她留在原地）
            }
            if (maid.isHomeModeEnable()) {
                return; // 在家模式 = 不跟随
            }
            LivingEntity owner = maid.m_269323_();
            if (owner == null || owner.m_21224_()) {
                return;
            }
            if (maid.m_9236_() == owner.m_9236_()) {
                return; // 同一维度（f_19853_ 是 private，用 m_9236_() 取 Level）
            }
            if (!(owner.m_9236_() instanceof ServerLevel newLevel)) {
                return;
            }
            BlockPos stand = findStand(newLevel,
                    new BlockPos((int) Math.floor(owner.m_20185_()),
                            (int) Math.floor(owner.m_20186_()),
                            (int) Math.floor(owner.m_20189_())));
            if (stand == null) {
                return; // 主人身边 16 格内无可站立点（高空飞行/虚空）→ 等落地再跟
            }
            // 跨维度移动（不走 changeDimension——TLM override 会随机瞬移+返回 null）
            maid.m_142687_(Entity.RemovalReason.CHANGED_DIMENSION);
            maid.m_6034_(stand.m_123341_() + 0.5, stand.m_123342_(), stand.m_123343_() + 0.5);
            newLevel.m_7967_(maid);
            // 传送后清理：摔落距离归零 + 停止旧导航
            maid.f_19789_ = 0.0f;
            maid.m_21573_().m_26569_();
            // 末影人传送音效（提示玩家女仆跟过来了）
            newLevel.m_5594_(null, stand, net.minecraft.sounds.SoundEvents.f_11852_,
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.0f);
        } catch (Exception ignored) {
        }
    }

    /** 从主人所在格向下找第一个"站立格空气 + 脚下实心不悬空"的位置（最多 16 格） */
    private static BlockPos findStand(ServerLevel level, BlockPos from) {
        BlockPos cur = from;
        for (int i = 0; i < 16; i++) {
            BlockState st = level.m_8055_(cur);
            BlockPos belowPos = cur.m_7495_();
            BlockState below = level.m_8055_(belowPos);
            if (st.m_60795_() && !below.m_60795_() && !below.m_60815_()
                    && below.m_60796_(level, belowPos)) {
                return cur;
            }
            cur = belowPos;
        }
        return null;
    }
}
