package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.build.BlueprintBuildExecutor;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * v1.1.0 实测二百七十三（反馈："给处于建造模式的女仆时长为无限的抗性5效果，
 * 且取消受击事件以及其他怪物的仇恨。在建造模式解除以后这些机制去掉"）：
 * 建造护盾——建造任务（maid_smart:build）进行中的女仆：
 *  - 无限时长抗性提升 V（MobEffectInstance.INFINITE_DURATION = -1 无限，字节码实证；
 *    MobEffects.DAMAGE_RESISTANCE = resistance，MobEffects static 块 putstatic 顺序实证）：
 *    首次进入建造施加，时长无限永不掉，切出建造移除并清标记；
 *  - 受击取消：LivingIncomingDamageEvent 直接 cancel（覆盖近战/箭矢/弹幕/爆炸/魔法等
 *    一切伤害来源，比抗性减伤更彻底——虚空/窒息等不吃抗性的伤害也免疫）；
 *  - 仇恨拦截：LivingChangeTargetEvent.setNewTarget(null)——Forge 在
 *    Mob.setTarget（setTarget）发此事件，怪物脑内传感器/行为每次重评估目标
 *    都经过它 → 任何生物（含敌对怪、其他女仆）永远不会把建造中的女仆锁定
 *    为目标，也顺带清掉已存在的旧仇恨（下次重评估被置空）。
 * 三机制全部【任务级判定】——切出建造任务立刻失效，无残留。
 */
@EventBusSubscriber(modid = "promaid")
public final class BuildShieldGuard {

    /** persistentData 标记：本系统给过无限抗性（切出建造时只移除我们给的，
     *  玩家自己叠加的其他抗性来源不动） */
    private static final String SHIELD_TAG = "maid_smart_build_shield";

    private BuildShieldGuard() {
    }

    /** 建造任务的 UID（与 {@link BlueprintBuildExecutor#isBuildingTask} 同一份口径） */
    private static final String BUILD_TASK_UID = "maid_smart:build";

    /**
     * 是否处于建造任务（与 tickBuildSit / BlueprintBuildExecutor 同口径）。
     *
     * <p>【实测六百九十八 建造女仆被打死】护盾原先只认**当前**任务是不是建造——于是
     * 本模组自己的「主动参战」把她切成 {@code touhou_little_maid:attack} 的那一瞬间，
     * 护盾当场失效（{@code tickShield} 走 else 支把抗性 V 摘掉、受击取消也不再拦），
     * 她成了一个穿着建筑工衣服的普通战斗女仆。玩家原话：「之前提到过，建造模式下的
     * 女仆应该是有5级的抗性的，但她却仍然被打死了。」日志实证（2026-09-27 整合包
     * latest.log）：04:38:24.856 「战斗 参战：maid_smart:build -> touhou_little_maid:attack」
     * → 04:38:26.762 「森近霖之助被下界合金巨兽杀死了」（2 秒）；更早一次 04:35:25.021
     * 参战 → 04:35:25.056 被 genericKill 打中（当晚靠魂符收走才没死）。
     *
     * <p>修法：护盾跟着**「她是建造女仆」这件事**走，不跟着"这一拍的任务字段"走——
     * 处于本模组主动参战会话中（{@code COMBAT_ACTIVE_TAG}）且战前任务（{@code PREV_TASK_TAG}）
     * 就是建造 → 照样算建造中。战斗结束还原后标记被清，自动回到常规判定。
     */
    public static boolean isBuilding(EntityMaid maid) {
        if (maid == null || !maid.isAlive()) {
            return false;
        }
        if (BlueprintBuildExecutor.isBuildingTask(maid)) {
            return true;
        }
        try {
            net.minecraft.nbt.CompoundTag nbt =
                    ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData();
            return nbt.getBoolean(AutoCombatSwitch.COMBAT_ACTIVE_TAG)
                    && BUILD_TASK_UID.equals(nbt.getString(AutoCombatSwitch.PREV_TASK_TAG));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** v1.1.0 实测二百七十四（反馈："建造模式屏蔽除了建造以外的其他所有系统信息
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
    public static void onMaidHurt(LivingIncomingDamageEvent event) {
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
            if (event.getNewAboutToBeSetTarget() instanceof EntityMaid maid && isBuilding(maid)) {
                event.setNewAboutToBeSetTarget(null);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 效果维护（由 MaidBuildBehavior.tickBuildSit 每 tick 调用，全 activity 覆盖）：
     *  建造中 → 保证无限时长抗性 V 存在；切出建造 → 移除效果 + 清标记 */
    public static void tickShield(EntityMaid maid) {
        try {
            boolean building = isBuilding(maid);
            boolean marked = ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getBoolean(SHIELD_TAG);
            if (building) {
                if (!marked) {
                    ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().putBoolean(SHIELD_TAG, true);
                }
                if (!maid.hasEffect(MobEffects.DAMAGE_RESISTANCE)) {
                    // 无限时长（INFINITE_DURATION = -1）+ 抗性 V（amplifier 4，0 基）
                    // ambient=false + visible=false：不播粒子，HUD 显示 ∞ 图标
                    maid.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE,
                            MobEffectInstance.INFINITE_DURATION, 4, false, false));
                }
            } else if (marked) {
                ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().putBoolean(SHIELD_TAG, false);
                if (maid.hasEffect(MobEffects.DAMAGE_RESISTANCE)) {
                    maid.removeEffect(MobEffects.DAMAGE_RESISTANCE);
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
