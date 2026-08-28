package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.api.task.FunctionCallSwitchResult;
import com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v1.1.0 实测一百四十八：战斗任务兼容层（参考 tlm_beyond_space 的
 * CombatTaskCompatibility——用户："女仆切换武器的时候还是不会使用模组武器；
 * 塞入模组武器后即使再拿出来，主动战斗就再也不触发了"）。
 *
 * 两个问题同根：战斗任务的【武器契约不完整 + 切换前不预检】。
 * ① ef_tlm:fight_mode_task（史诗战斗联动）【没有覆写 isWeapon(maid, stack)】——
 *    IAttackTask 默认实现恒 false（javap 实证）→ 该任务的武器永远进不了战斗候选池、
 *    永远不被自动装备 → "切换武器时不用模组武器"。该任务只暴露 isWeaponCap(ItemStack)
 *    与 hasCapWeapon(maid)，这里用反射调用 isWeaponCap 补上判定。
 * ② 切任务前不做【预检+自动装备】——TLM 官方 onFunctionCallSwitch 默认实现（javap
 *    实证）：主手有匹配武器 → NO_CHANGE；没有 → TaskEquipUtil.tryEquipFromBackpack
 *    自动装备 → OK；装不上 → MISSING_REQUIRED_ITEM。预检失败就不切入 → 不会把女仆
 *    卡在打不出伤害的战斗任务上（模组武器被拿走后的永久"战斗中"死锁 = 主动战斗
 *    再也不触发）。
 */
public final class CombatTaskCompat {
    public static final ResourceLocation EPIC_FIGHT_TASK =
            ResourceLocation.parse("ef_tlm:fight_mode_task");
    private static final String EPIC_WEAPON_CHECK_METHOD = "isWeaponCap";
    private static final Map<Class<?>, Optional<Method>> EPIC_WEAPON_CHECKS =
            new ConcurrentHashMap<>();

    private CombatTaskCompat() {
    }

    /** 兼容的武器判定：ef_tlm 走 isWeaponCap 反射，其余走任务自己的 isWeapon */
    public static boolean isWeapon(EntityMaid maid, IMaidTask task, ItemStack stack) {
        if (task == null || stack == null || stack.m_41619_()) {
            return false;
        }
        if (isEpicFightTask(task)) {
            return invokeEpicWeaponCheck(task, stack);
        }
        if (!(task instanceof IAttackTask attackTask)) {
            return false;
        }
        try {
            return attackTask.isWeapon(maid, stack);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 切任务前预检 + 自动装备（参考 tlm_beyond_space TaskSwitchService.prepareAndSwitch）：
     *  非 ef_tlm 走 TLM 官方 onFunctionCallSwitch（默认实现 = 主手无武器则从背包装备，
     *  装不上返回 MISSING_REQUIRED_ITEM）；ef_tlm 的 isWeapon 恒 false 走不了官方路径，
     *  按 isWeaponCap 判定 + tryEquipFromBackpack 装备。 */
    public static FunctionCallSwitchResult prepareSwitch(EntityMaid maid, IMaidTask task) {
        if (task == null) {
            return FunctionCallSwitchResult.MISSING_REQUIRED_ITEM;
        }
        if (isEpicFightTask(task)) {
            if (isWeapon(maid, task, maid.m_21205_())) {
                return FunctionCallSwitchResult.NO_CHANGE;
            }
            boolean equipped = com.github.tartaricacid.touhoulittlemaid.util.TaskEquipUtil
                    .tryEquipFromBackpack(maid, s -> isWeapon(maid, task, s));
            return equipped ? FunctionCallSwitchResult.OK : FunctionCallSwitchResult.MISSING_REQUIRED_ITEM;
        }
        try {
            return task.onFunctionCallSwitch(maid);
        } catch (Throwable t) {
            return FunctionCallSwitchResult.MISSING_REQUIRED_ITEM;
        }
    }

    private static boolean isEpicFightTask(IMaidTask task) {
        try {
            return task != null && EPIC_FIGHT_TASK.equals(task.getUid());
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean invokeEpicWeaponCheck(IMaidTask task, ItemStack stack) {
        Class<?> taskClass = task.getClass();
        Optional<Method> method = EPIC_WEAPON_CHECKS.computeIfAbsent(taskClass,
                CombatTaskCompat::findEpicWeaponCheck);
        if (method.isEmpty()) {
            return false;
        }
        try {
            Object result = method.get().invoke(task, stack);
            return result instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Optional<Method> findEpicWeaponCheck(Class<?> taskClass) {
        try {
            Method method = taskClass.getMethod(EPIC_WEAPON_CHECK_METHOD, ItemStack.class);
            Class<?> returnType = method.getReturnType();
            if (returnType != boolean.class && returnType != Boolean.class) {
                return Optional.empty();
            }
            return Optional.of(method);
        } catch (NoSuchMethodException | SecurityException e) {
            return Optional.empty();
        }
    }
}
