package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;

import java.util.Collections;

/**
 * 任务工具自动装备行为（v1.5.90）——core 行为，任何 activity 都运行。
 *
 * 在 canUse 里直接执行换工具并返回 false：行为从不占用运行槽位，每 tick 被
 * 调度器评估一次，换完即停，不影响任何其他行为。行为幂等——主手已是合适
 * 工具时零开销（一次物品判断）。覆盖任务：攻击/弓/弩/三叉戟/挖矿（见
 * MaidToolAutoEquip.ensureForTask 的任务 UID 映射）。
 */
public class MaidToolAutoEquipBehavior extends Behavior<EntityMaid> {
    public MaidToolAutoEquipBehavior() {
        super(Collections.emptyMap());
    }

    @Override
    protected boolean m_6114_(ServerLevel level, EntityMaid maid) {
        MaidToolAutoEquip.ensureForTask(maid);
        // v1.5.142：战斗状态自动装备副手盾牌（空手才换、爆盾自动补）
        MaidToolAutoEquip.ensureShieldForCombat(maid);
        // v1.5.142：建造模式强制坐下（core 行为每 tick 触发——玩家无法让她站起，
        // 建造不再被跟随传送拉走；切任务自动站起）
        com.maidsmart.build.MaidBuildBehavior.tickBuildSit(maid);
        return false; // 永不启动——只在 canUse 里换工具，不占行为槽
    }
}
