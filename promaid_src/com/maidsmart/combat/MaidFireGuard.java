package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * v1.1.0 实测六十二：女仆着火不传主人。
 *
 * 场景：女仆踩进火/岩浆烧起来后又贴着主人（跟随贴身），主人身上也跟着烧起来。
 * 原版没有"实体接触传火"的事件可拦，防护做两层：
 *
 * 1.【攻击路径】燃烧的女仆对主人造成任何伤害（LivingAttackEvent，最上游可取消）
 *   → 直接取消——"以燃烧女仆为来源"的攻击是唯一能把火以女仆为媒介挂到主人
 *   身上的显式路径（燃烧实体近战附带点燃类机制，原版箭矢同款思路）。
 *
 * 2.【接触路径】每 10 tick 扫一遍燃烧的自己的女仆——主人身上有火、但主人自己
 *   并没有站在火/岩浆里（脚下与身体格无火焰方块、无非空流体）→ 判定火来自
 *   贴身的燃烧女仆，直接给主人灭火（m_20095_ = clearFire，javap 核实内部
 *   调 setRemainingFireTicks(0)）。
 *   主人自己站在火/岩浆里（或浸在水中）则不干预——那是玩家自己的火，
 *   贸然灭火就成了作弊外挂。
 *
 * 火状态 SRG（javap 反编译核实）：m_20094_ = getRemainingFireTicks，
 * m_20095_ = clearFire，m_7311_ = setRemainingFireTicks。
 * 总开关：maidFireGuard（默认开，手册配置面板「贴身辅助」小节可关）。
 */
public class MaidFireGuard {
    /** 每 10 tick（0.5 秒）扫一轮接触传火 */
    private int throttle = 0;

    @SubscribeEvent
    public void onOwnerAttackedByMaid(LivingAttackEvent event) {
        if (!com.maidsmart.config.MaidSmartConfig.MAID_FIRE_GUARD.get()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        net.minecraft.world.damagesource.DamageSource src = event.getSource();
        if (src == null || !(src.m_7639_() instanceof EntityMaid maid)) {
            return;
        }
        if (maid.m_20094_() <= 0) {
            return; // 女仆没烧着，与本病无关
        }
        if (maid.m_269323_() != player) {
            return; // 只护主人本人（其他玩家被女仆打走 TLM 正常仇恨/管教体系）
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++this.throttle < 10) {
            return;
        }
        this.throttle = 0;
        if (!com.maidsmart.config.MaidSmartConfig.MAID_FIRE_GUARD.get()) {
            return;
        }
        for (ServerLevel level : event.getServer().m_129785_()) {
            for (net.minecraft.world.entity.Entity e : level.m_8583_()) {
                if (!(e instanceof EntityMaid maid) || !maid.m_6084_() || maid.m_20094_() <= 0) {
                    continue; // 只看烧着的女仆
                }
                if (!(maid.m_269323_() instanceof ServerPlayer owner) || owner.m_21224_()) {
                    continue;
                }
                if (owner.m_20094_() <= 0) {
                    continue; // 主人没着火
                }
                // 贴身接触（≤2 格）
                if (maid.m_20238_(owner.m_20182_()) > 4.0) {
                    continue;
                }
                // 主人自己站在火/岩浆/流体里 → 火是自己的，不干预
                if (selfInFireOrFluid(owner)) {
                    continue;
                }
                owner.m_20095_(); // clearFire——火来自贴身的燃烧女仆，直接扑灭
            }
        }
    }

    /** 主人脚下/身体格是否有火焰方块或浸在非空流体（岩浆/水——水会自然灭火，同样不干预） */
    private static boolean selfInFireOrFluid(ServerPlayer p) {
        net.minecraft.world.level.Level lvl = p.m_9236_();
        net.minecraft.core.BlockPos base = p.m_20183_();
        for (net.minecraft.core.BlockPos pos : new net.minecraft.core.BlockPos[]{base, base.m_7918_(0, 1, 0)}) {
            var st = lvl.m_8055_(pos);
            if (st.m_60734_() instanceof net.minecraft.world.level.block.BaseFireBlock) {
                return true;
            }
            if (!st.m_60819_().m_76178_()) {
                return true; // 非空流体（m_76178_ = FluidState.isEmpty，工程内已核实口径）
            }
        }
        // 主人本身是燃烧弹射物的近期受害者等场景无法追溯——只按"当前是否身处火源"判定
        return false;
    }
}
