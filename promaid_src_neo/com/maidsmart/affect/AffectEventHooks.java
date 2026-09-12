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
 * - 主人与女仆对话（PlayerLoggedIn 简化：每次玩家对女仆右键喂食/交互触发，见 onOwnerInteract）
 * - 主人打女仆（LivingIncomingDamageEvent：攻击者=主人）
 * - 女仆被世界伤害（攻击者非主人）
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
        if (src instanceof ServerPlayer) {
            // 主人（或任意玩家）打女仆
            AffectManager.onHurtByOwner(maid);
        } else {
            // 世界伤害（怪物/摔落/环境）
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
        if (event.getEntity() instanceof ServerPlayer
                && maid.getOwner() == event.getEntity()
                && maid.level() instanceof ServerLevel level && !level.isClientSide()) {
            AffectManager.onOwnerMessage(maid);
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
