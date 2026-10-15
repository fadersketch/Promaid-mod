package com.maidsmart.affect;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.bus.api.SubscribeEvent;

/**
 * PAD 情绪事件钩子（v1.5.95）——把游戏事件喂给 AffectManager：
 * - 主人与女仆对话 / **喂食**（右键：手里是食物即算喂饭，见 onOwnerInteract；实测九百六十九）
 * - 主人打女仆 / **别的玩家打女仆**（后者另算一档，实测九百六十九）
 * - 女仆被世界伤害（攻击者非玩家：怪物 / 摔落 / 环境）
 * - tick 静默恢复（每 20 秒）
 *
 * 注意：本类不修改任何现有数值系统（TLM 好感等现有数值），只更新独立情绪层。
 */
public class AffectEventHooks {
    /** v1.5.102：静默恢复间隔从配置面板读取（affect.recoverInterval，秒→tick） */
    private int tick = 0;

    @SubscribeEvent
    public void onHurt(LivingIncomingDamageEvent event) {
        // v1.5.96：情绪总开关（配置面板 affect.enable）
        if (!com.maidsmart.config.MaidSmartConfig.AFFECT_ENABLE.get()) {
            return;
        }
        if (!(event.getEntity() instanceof EntityMaid maid)) {
            return;
        }
        if (!(maid.level() instanceof ServerLevel level)) {
            return;
        }
        if (level.isClientSide()) {
            return;
        }
        net.minecraft.world.entity.Entity src = event.getSource().getEntity();
        if (src instanceof ServerPlayer player) {
            // 实测九百六十九：区分"主人打"和"别的玩家打"——旧版把任何玩家的伤害都按主人档算，
            // 多人服里别人打你的女仆，账会记到你头上。
            if (maid.getOwner() == player) {
                AffectManager.onHurtByOwner(maid);
            } else {
                AffectManager.onHurtByStranger(maid);
            }
        } else {
            // 世界伤害（怪物/摔落/火/挤压/环境）
            AffectManager.onHurtByWorld(maid);
        }
    }

    @SubscribeEvent
    public void onOwnerInteract(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract event) {
        if (!com.maidsmart.config.MaidSmartConfig.AFFECT_ENABLE.get()) {
            return;
        }
        // 主人与女仆交互（喂食/空手点）→ 情绪"主人互动"事件
        if (!(event.getTarget() instanceof EntityMaid maid)) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player
                && maid.getOwner() == player
                && maid.level() instanceof ServerLevel level && !level.isClientSide()) {
            // 实测九百六十九：手里拿着食物右键她 = 喂饭 → 愉悦升（手册承诺的"喂她会变开心"）；
            // 空手 / 非食物右键仍按"主人互动"（亲密与愉悦都微升）
            net.minecraft.world.item.ItemStack held = event.getItemStack();
            if (!held.isEmpty() && held.getItem().components().has(
                    net.minecraft.core.component.DataComponents.FOOD)) {
                AffectManager.onOwnerFeed(maid);
            } else {
                AffectManager.onOwnerMessage(maid);
            }
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
if (!com.maidsmart.config.MaidSmartConfig.AFFECT_ENABLE.get()) {
            return;
        }
        net.minecraft.server.MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        if (++this.tick < com.maidsmart.config.MaidSmartConfig.AFFECT_RECOVER_INTERVAL.get() * 20) {
            return;
        }
        this.tick = 0;
        // 实测八百三十九：零女仆直接返回（名册按 20 tick 复用，O(1)）——下面整段只为女仆服务
        if (!com.maidsmart.tool.MaidRoster.any(server)) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                level.getEntitiesOfClass(EntityMaid.class, player.getBoundingBox().inflate(128.0)).forEach(maid -> {
                    if (maid.isAlive() && maid.isTame()) {
                        AffectManager.tickRecover(maid);
                    }
                });
            }
        }
    }
}
