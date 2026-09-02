package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.build.BlueprintBuildExecutor;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * v1.1.0 实测二百七十三（用户："给处于建造模式的女仆时长为无限的抗性5效果，
 * 且取消受击事件以及其他怪物的仇恨。在建造模式解除以后这些机制去掉"）：
 * 建造护盾——建造任务（maid_smart:build）进行中的女仆：
 *  - 无限时长抗性提升 V（MobEffectInstance.f_267388_ = -1 无限，字节码实证；
 *    MobEffects.f_19606_ = resistance，MobEffects static 块 putstatic 顺序实证）：
 *    首次进入建造施加，时长无限永不掉，切出建造移除并清标记；
 *  - 受击取消：LivingHurtEvent 直接 cancel（覆盖近战/箭矢/弹幕/爆炸/魔法等
 *    一切伤害来源，比抗性减伤更彻底——虚空/窒息等不吃抗性的伤害也免疫）；
 *  - 仇恨拦截：LivingChangeTargetEvent.setNewTarget(null)——Forge 在
 *    Mob.setTarget（m_6710_）发此事件，怪物脑内传感器/行为每次重评估目标
 *    都经过它 → 任何生物（含敌对怪、其他女仆）永远不会把建造中的女仆锁定
 *    为目标，也顺带清掉已存在的旧仇恨（下次重评估被置空）。
 * 三机制全部【任务级判定】——切出建造任务立刻失效，无残留。
 */
@Mod.EventBusSubscriber(modid = "promaid")
public final class BuildShieldGuard {

    /** persistentData 标记：本系统给过无限抗性（切出建造时只移除我们给的，
     *  玩家自己叠加的其他抗性来源不动） */
    private static final String SHIELD_TAG = "maid_smart_build_shield";

    private BuildShieldGuard() {
    }

    /** 是否处于建造任务（与 tickBuildSit / BlueprintBuildExecutor 同口径） */
    public static boolean isBuilding(EntityMaid maid) {
        return maid != null && maid.m_6084_() && BlueprintBuildExecutor.isBuildingTask(maid);
    }

    /** v1.1.0 实测二百七十四（用户："建造模式屏蔽除了建造以外的其他所有系统信息
     *  系统消息及气泡"）：当前调用栈是否来自建造系统（com.maidsmart.build 包）——
     *  气泡来源判定（零侵入调用点）。调用栈开销小（气泡天然限频，频率低）。 */
    public static boolean fromBuildSystem() {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        // index 0=getStackTrace 1=本方法 2=调用者——从 3 起扫真实调用链
        for (int i = 3; i < st.length; i++) {
            String cn = st[i].getClassName();
            if (cn != null && cn.startsWith("com.maidsmart.build.")) {
                return true;
            }
        }
        return false;
    }

    /** v1.1.0 实测二百七十四：建造女仆的【非建造来源】消息/气泡是否应静默。
     *  maid 由调用方传入（气泡 mixin 有 @Shadow maid，字幕点方法签名都有 maid）。 */
    public static boolean shouldMute(EntityMaid maid) {
        return isBuilding(maid) && !fromBuildSystem();
    }

    /** 受击取消：建造中的女仆任何伤害来源都被取消 */
    @SubscribeEvent
    public static void onMaidHurt(LivingHurtEvent event) {
        try {
            if (event.getEntity() instanceof EntityMaid maid && isBuilding(maid)) {
                event.setCanceled(true);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 仇恨拦截：任何生物试图锁定建造中的女仆为目标 → 目标置空 */
    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        try {
            if (event.getNewTarget() instanceof EntityMaid maid && isBuilding(maid)) {
                event.setNewTarget(null);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 效果维护（由 MaidBuildBehavior.tickBuildSit 每 tick 调用，全 activity 覆盖）：
     *  建造中 → 保证无限时长抗性 V 存在；切出建造 → 移除效果 + 清标记 */
    public static void tickShield(EntityMaid maid) {
        try {
            boolean building = isBuilding(maid);
            boolean marked = maid.getPersistentData().m_128471_(SHIELD_TAG);
            if (building) {
                if (!marked) {
                    maid.getPersistentData().m_128379_(SHIELD_TAG, true);
                }
                if (!maid.m_21023_(MobEffects.f_19606_)) {
                    // 无限时长（f_267388_ = -1）+ 抗性 V（amplifier 4，0 基）
                    // ambient=false + visible=false：不播粒子，HUD 显示 ∞ 图标
                    maid.m_7292_(new MobEffectInstance(MobEffects.f_19606_,
                            MobEffectInstance.f_267388_, 4, false, false));
                }
            } else if (marked) {
                maid.getPersistentData().m_128379_(SHIELD_TAG, false);
                if (maid.m_21023_(MobEffects.f_19606_)) {
                    maid.m_21195_(MobEffects.f_19606_);
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
