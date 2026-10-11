package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * 模组仆从坐骑·伤害转移——"如果女仆受到了伤害，会将伤害转移给身下坐着的仆从"。属"模组仆从坐骑"这一
 * 单独区间，判据只在 {@link MaidRideKit#isNoSaddleRideable} 上（原版马/骆驼、卓越前线载具、冰火传说龙、
 * 别的模组生物、通用骑乘逻辑一律不受影响）。
 *
 * 【做法】在受伤链最上游拦下"她正被我们棍子绑坐骑的、无鞍可骑模组仆从"这一档：取消她自己这一记伤害，
 * 再把**等量的同一来源**打到她身下的仆从上——她背靠仆从挡刀，攻击者仇恨也顺势落到仆从身上（原版
 * {@code hurt} 会把伤害源记进仆从的复仇目标）。
 * 【不转移的那几类】{@code outOfWorld}（虚空）与 {@code inWall}/{@code cramming}/{@code flyIntoWall}
 * （卡墙/挤压/撞墙）**不转移**：这几条都是**每拍重复**的环境伤害，而"她俩通常在同一处"——再把她那份
 * 转过去只会变成**死亡螺旋**（一起被挤死），且卡墙是施工问题、不该由这条保护兜。其余一律转移。总开关
 * {@link MaidRideKit#servantTransferEnabled}。
 * 【不碰谁】只在"受害者是女仆 + 她正骑一只无鞍可骑模组仆从 + 是我们棍子绑的"时生效；其余任何实体受伤
 * 本类**直接 return**。与另一树同名类同口径（那份走各自加载器的上游伤害事件）。
 */
@EventBusSubscriber(modid = "promaid")
public final class ServantMountDamageTransfer {

    private ServantMountDamageTransfer() {
    }

    /** 不转移的环境自伤（见类注释）。 */
    private static final java.util.Set<String> NO_TRANSFER = java.util.Set.of(
            "outOfWorld", "inWall", "cramming", "flyIntoWall");

    /** 转移日志节流（毫秒/女仆）。 */
    private static final long LOG_INTERVAL_MS = 2000L;
    private static final java.util.Map<java.util.UUID, Long> LOG_AT = new java.util.HashMap<>();

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        try {
            if (event == null || event.isCanceled() || event.getAmount() <= 0.0f) {
                return;
            }
            if (!(event.getEntity() instanceof EntityMaid maid)) {
                return; // 受害者不是女仆 → 不关我们的事
            }
            if (!MaidRideKit.servantTransferEnabled() || !MaidRideKit.servantAutoEnabled()) {
                return;
            }
            Entity v = maid.getVehicle();
            if (!(v instanceof Mob mount) || !MaidRideKit.isNoSaddleRideable(mount)) {
                return; // 她骑的不是"模组仆从"这一区间 → 原版口径
            }
            if (!MaidRideKit.isDriven(mount)) {
                return; // 不是我们棍子绑的 → 不碰
            }
            if (!mount.isAlive() || mount.isRemoved() || mount.level() != maid.level()) {
                return;
            }
            var source = event.getSource();
            if (source == null) {
                return;
            }
            String msg = source.getMsgId();
            if (msg != null && NO_TRANSFER.contains(msg)) {
                return; // 每拍重复的环境自伤：不转移（见类注释）
            }
            float amount = event.getAmount();
            event.setCanceled(true);       // 她这一下不受伤
            mount.hurt(source, amount);    // 转给身下的仆从（同源，仇恨也落到它身上）
            logTransfer(maid, mount, amount, msg);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ServantMountDamageTransfer", ignored);
        }
    }

    private static void logTransfer(EntityMaid maid, Mob mount, float amount, String msg) {
        try {
            long now = System.currentTimeMillis();
            Long last = LOG_AT.get(maid.getUUID());
            if (last != null && now - last < LOG_INTERVAL_MS) {
                return;
            }
            LOG_AT.put(maid.getUUID(), now);
            if (LOG_AT.size() > 512) {
                LOG_AT.clear();
            }
            com.maidsmart.tool.PromaidLog.log("模组坐骑·转伤",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 受击 → 转给坐骑 "
                            + String.valueOf(mount.getType()).replace("entity.minecraft.", "")
                            + "：" + String.format(java.util.Locale.ROOT, "%.1f", amount)
                            + (msg == null || msg.isEmpty() ? "" : " (" + msg + ")"));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ServantMountDamageTransfer", ignored);
        }
    }
}
