package com.maidsmart.build;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 指标石中断清理——女仆**被收回 / 死亡**时结束该次临时搭建（清幽灵方块 + 解绑）。
 *
 * 三条入口，都靠 {@code IndexStoneService.failSession} 的幂等保证只结算一次：
 * ① {@link EntityLeaveLevelEvent}（项目既有的"女仆离场"统一清理点）：用 RemovalReason 区分
 *    【永久移除】（KILLED 死亡 / DISCARDED 收回解雇）与【区块卸载 / 换维度】——后者女仆会回来，
 *    绝不能结束会话。
 * ② {@link LivingDeathEvent}：死亡兜底（部分流程"死亡→复活"会先移除再重建实体，上面那个事件的
 *    时机不保证）。
 * ③ 魂符收回（{@code MaidAndItemTransformEvent.ToItem}）：TLM 收女仆进魂符的唯一汇聚点。**注意
 *    storeMaidData 是"先存 NBT 快照、再 post ToItem"**——所以只清活体标记晚了一步（标记已进快照），
 *    必须把 {@code event.getData()} 一起洗掉，否则放出来时会连同"临时建造"任务读回 → 建造不停 + 僵住。
 */
public class IndexStoneInterruptHandler {

    @SubscribeEvent
    public void onMaidLeave(EntityLeaveLevelEvent event) {
        if (!IndexStoneService.isEnabled()) {
            return;
        }
        if (event.getEntity() instanceof EntityMaid maid) {
            IndexStoneService.onMaidLeave(maid);
        }
    }

    @SubscribeEvent
    public void onMaidDeath(LivingDeathEvent event) {
        if (!IndexStoneService.isEnabled()) {
            return;
        }
        if (event.getEntity() instanceof EntityMaid maid) {
            IndexStoneService.onMaidDeath(maid);
        }
    }

    /** 女仆 → 物品（魂符 / 相机 / 胶卷共用）→ 结束会话 + 清洗快照里的建造残留 */
    @SubscribeEvent
    public void onSoulSpellToItem(
            com.github.tartaricacid.touhoulittlemaid.api.event.MaidAndItemTransformEvent.ToItem event) {
        if (!IndexStoneService.isEnabled()) {
            return;
        }
        try {
            EntityMaid maid = event.getMaid();
            if (maid == null || maid.m_9236_().f_46443_) {
                return;
            }
            // ToItem 也被相机/胶卷等"存女仆物品"共用——只有魂符才算"收回"
            // （相机/胶卷只该清洗快照，不该把玩家正在进行的搭建判死刑）
            net.minecraft.world.item.ItemStack item = event.getItem();
            boolean soulSlab = item != null && item.m_150930_(
                    com.github.tartaricacid.touhoulittlemaid.init.InitItems.SMART_SLAB_HAS_MAID.get());
            IndexStoneService.onMaidRecalled(maid, event.getData(), soulSlab);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("IndexStoneInterruptHandler", ignored);
        }
    }
}
