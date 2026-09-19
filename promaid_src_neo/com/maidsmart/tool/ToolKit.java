package com.maidsmart.tool;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Monster;

import java.util.List;

/**
 * v1.2.2 实测五百七十八：新增的"指挥三件套"工具（smart_switch_task / smart_air_raid /
 * smart_work_area）共用的小工具集。
 *
 * 目标口径**与 smart_attack 完全一致**（nearest = 最近的敌对生物 / owner_target = 主人正在
 * 打的 / attacker = 刚打过她的），锁定也用同一套"双写"：Brain 的 {@code ATTACK_TARGET}
 * 记忆 + 实体层 {@code setTarget}。TLM 的 StartAttacking 只选**空**目标，所以锁上之后不会被
 * 它覆盖，单兵战术行为与自保行为会自动接管后续走位与保命。
 *
 * 为什么放进独立类而不是各自复制：三个工具都要做"标题解析 + 目标锁定 + id 解析"，
 * 复制三份的话改一处漏两处（这类漏改在本项目里出过事故）。
 */
final class ToolKit {

    /** 目标模式（工具参数枚举值；顺序 = 提示里的书写顺序） */
    static final String[] TARGET_MODES = {"nearest", "owner_target", "attacker"};

    /** nearest 模式的扫描半径（格）——与 smart_attack 同口径 */
    private static final double NEAREST_RANGE = 12.0;

    private ToolKit() {
    }

    /**
     * 解析"命名空间:路径"形式的 id。
     *
     * 【为什么不用 ResourceLocation.tryParse】1.20.1 的 ResourceLocation **没有** tryParse
     * （javap 实证：只有两个构造器），两树要共用一份逻辑，所以自己切冒号 + 构造器建，
     * 非法字符由构造器抛异常、这里兜住返回 null。缺省命名空间用 {@code touhou_little_maid}
     * （指挥工具最常打交道的就是 TLM 原生任务）。
     */
    static ResourceLocation rl(String nsPath, String defaultNamespace) {
        if (nsPath == null) {
            return null;
        }
        String s = nsPath.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }
        int i = s.indexOf(':');
        String ns = i < 0 ? defaultNamespace : s.substring(0, i);
        String path = i < 0 ? s : s.substring(i + 1);
        if (ns.isEmpty() || path.isEmpty()) {
            return null;
        }
        try {
            return ResourceLocation.fromNamespaceAndPath(ns, path);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 按模式取目标（取不到返回 null） */
    static LivingEntity pick(EntityMaid maid, ServerLevel level, String mode) {
        String m = mode == null ? "" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        if ("owner_target".equals(m)) {
            LivingEntity owner = maid.getOwner();
            // getLastHurtMob = 主人最近攻击的目标（TLM DefaultMonsterType 同款用法）
            return owner == null ? null : owner.getLastHurtMob();
        }
        if ("attacker".equals(m)) {
            return com.maidsmart.combat.SelfPreservationBehavior.recentAttacker(maid);
        }
        List<Monster> monsters = level.getEntitiesOfClass(Monster.class,
                maid.getBoundingBox().inflate(NEAREST_RANGE), e -> e.isAlive() && maid.canAttack(e));
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Monster monster : monsters) {
            double d = maid.distanceTo(monster);
            if (d < bestDist) {
                bestDist = d;
                best = monster;
            }
        }
        return best;
    }

    /**
     * 锁目标（含两条防线）：TLM 原生 {@code canAttack} 完整过滤 + 本模组的主人/友军拒绝。
     *
     * @return true = 已锁定；false = 目标不合法（调用方据此回话，别静默）
     */
    static boolean lock(EntityMaid maid, LivingEntity target) {
        if (target == null || !target.isAlive() || !maid.canAttack(target)) {
            return false;
        }
        if (com.maidsmart.combat.FriendlyFireGuard.isFriendly(maid, target)) {
            return false;
        }
        maid.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, target);
        maid.setTarget(target);
        return true;
    }

    /** 目标/实体的显示名（给工具结果与气泡用） */
    static String name(LivingEntity e) {
        try {
            return e.getName().getString();
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** 气泡（女仆头顶说话）——工具执行成功时给玩家一个即时反馈 */
    static void bubble(EntityMaid maid, String text) {
        try {
            maid.getChatBubbleManager().addTextChatBubble(text);
        } catch (Throwable ignored) {
        }
    }
}
