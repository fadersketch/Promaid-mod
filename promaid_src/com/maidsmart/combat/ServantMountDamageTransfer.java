package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * v1.3.0(beta)（1.20.1）实测七百七十【模组仆从坐骑·伤害转移】。
 *
 * <h2>玩家原话</h2>
 * 「如果女仆受到了伤害，会将伤害转移给身下坐着的仆从。」（本条与"只赋速度、其余归它自己的 AI"
 * 同属"模组仆从坐骑"这一个单独区间，判据只在 {@link MaidRideKit#isNoSaddleRideable} 上——
 * 原版马/骆驼、卓越前线载具、冰火传说龙、别的模组生物、通用骑乘逻辑一律不受影响。）
 *
 * <h2>做法</h2>
 * 在受伤链最上游（1.20.1 的 {@code LivingAttackEvent}）拦下"她正在被我们棍子绑坐骑的、无鞍可骑
 * 模组仆从"这一档：取消她自己的这一记伤害，再把**等量的同一来源**打到她身下的仆从上——她背靠
 * 仆从挡刀，攻击者的仇恨也顺势落到仆从身上（原版 {@code hurt} 会把伤害源记进仆从的复仇目标）。
 *
 * <h2>不转移的那几类（环境自伤）</h2>
 * {@code outOfWorld}（虚空）与 {@code inWall} / {@code cramming} / {@code flyIntoWall}
 * （卡墙/挤压/撞墙）**不转移**：这几条都是**每拍重复**的环境伤害，而"她们俩通常在同一处"
 * ——她卡墙时仆从自己也在卡墙、本来就在吃同一类伤害；再把她那一份转过去只会变成**死亡螺旋**
 * （两个一起被挤死），且卡墙是"位置没摆好"的施工问题，不该由这条保护来兜。其余一切（近战/弹射物/
 * 爆炸/火/岩浆/毒…）一律转移。总开关见 {@link MaidRideKit#servantTransferEnabled}。
 *
 * <h2>不碰谁</h2>
 * 只在"受害者是女仆 + 她此刻正骑着一只无鞍可骑模组仆从 + 是我们棍子绑的"时生效；其余任何实体
 * 受伤（含仆从自己受伤）本类**直接 return**，原版/别的模组/别的守卫链一个字节不动。
 * 与 1.21.1 树同名类同口径（那份走 {@code LivingIncomingDamageEvent}）。
 */
@Mod.EventBusSubscriber(modid = "promaid")
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
    public static void onLivingAttack(LivingAttackEvent event) {
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
            Entity v = maid.m_20202_(); // m_20202_ = getVehicle
            if (!(v instanceof Mob mount) || !MaidRideKit.isNoSaddleRideable(mount)) {
                return; // 她骑的不是"模组仆从"这一区间 → 原版口径
            }
            if (!MaidRideKit.isDriven(mount)) {
                return; // 不是我们棍子绑的 → 不碰
            }
            if (!mount.m_6084_() || mount.m_213877_() || mount.m_9236_() != maid.m_9236_()) { // isAlive/isRemoved/level
                return;
            }
            var source = event.getSource();
            if (source == null) {
                return;
            }
            String msg = source.m_19385_(); // m_19385_ = getMsgId
            if (msg != null && NO_TRANSFER.contains(msg)) {
                return; // 每拍重复的环境自伤：不转移（见类注释）
            }
            float amount = event.getAmount();
            event.setCanceled(true);            // 她这一下不受伤
            mount.m_6469_(source, amount);      // m_6469_ = hurt：转给身下的仆从（同源，仇恨也落到它身上）
            logTransfer(maid, mount, amount, msg);
        } catch (Throwable ignored) {
        }
    }

    private static void logTransfer(EntityMaid maid, Mob mount, float amount, String msg) {
        try {
            long now = System.currentTimeMillis();
            Long last = LOG_AT.get(maid.m_20148_());
            if (last != null && now - last < LOG_INTERVAL_MS) {
                return;
            }
            LOG_AT.put(maid.m_20148_(), now);
            if (LOG_AT.size() > 512) {
                LOG_AT.clear();
            }
            com.maidsmart.tool.PromaidLog.log("模组坐骑·转伤",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 受击 → 转给坐骑 "
                            + String.valueOf(mount.m_6095_()).replace("entity.minecraft.", "")
                            + "：" + String.format(java.util.Locale.ROOT, "%.1f", amount)
                            + (msg == null || msg.isEmpty() ? "" : " (" + msg + ")"));
        } catch (Throwable ignored) {
        }
    }
}
