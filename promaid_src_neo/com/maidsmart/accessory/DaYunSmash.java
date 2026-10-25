package com.maidsmart.accessory;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 「大运」撞击：佩戴者鞘翅滑翔时，**破坏范围内**的非友方单位按**这一刻的速度**吃一记伤害 + 被炸飞。
 *
 * <p>【伤害·鞘翅同款】取「速度 × daYunSmashDamage」与「max(0, 速度×10 − 3)」（原版鞘翅撞墙公式）
 * 两者较高者，上限 40——与鞘翅飞行撞墙同一套速度曲线，越飞快越疼。
 *
 * <p>【击飞·TNT 式】以佩戴者为圆心、朝目标径向炸开（不再顺飞行方向），越近越狠并带向上分量，
 * 强度 = 速度 × daYunSmashKnockback（上限 16）；范围与「开路」同源，低于 {@link #MIN_SPEED} 不结算。
 *
 * <p>【谁不算】使用者自己、主人、同主女仆、使用者名下的宠物、同队同盟——见 {@link #isFriendly}。
 * 该判据**刻意不看** {@code COMBAT_FRIENDLY_FIRE_GUARD} 总闸，所以玩家关掉它主人也不会反被撞飞。
 *
 * <p>【开关】{@code accessory.daYunSmash}（默认开）。每 tick 每个佩戴者一次，同一 tick 内相邻立方体
 * 重叠的目标只结算一次。两树各一份：Forge 用 SRG 名、Neo 用 Mojmap 名。
 */
public final class DaYunSmash {

    private DaYunSmash() {
    }

    /** 触发速度下限（格/tick）：低于它（几乎悬停）不结算，免得站着不动也把旁边的东西撞飞。 */
    private static final double MIN_SPEED = 0.4D;

    /** 伤害上限（点）：速度再高也不会一撞就秒掉精英怪。 */
    private static final float MAX_DAMAGE = 40.0F;

    /** 击飞强度上限（TNT 式径向 push 口径；旧版顺飞行方向时为 10，R1105 起加大到 16）。 */
    private static final double MAX_KNOCKBACK = 16.0D;

    /** 原版鞘翅撞墙伤害系数：{@code max(0, 速度 × 10 − 3)}（与 LivingEntity 滑翔撞墙同一公式） */
    private static final double ELYTRA_DAMAGE_SCALE = 10.0D;
    private static final double ELYTRA_DAMAGE_BASE = 3.0D;

    /**
     * 对范围内非友方单位结算一次撞击（伤害 + 击飞，均按速度）。
     * @param level  服务端世界
     * @param user   佩戴者（玩家或女仆）——也是 TNT 式击飞的圆心
     * @param motion 本 tick 速度矢量（只用来算速度大小，不再决定击飞方向）
     * @param side   破坏立方体边长（与开路同一项配置）
     */
    public static void impact(ServerLevel level, LivingEntity user, Vec3 motion, int side) {
        impact(level, user, motion, side, false);
    }

    /** 【R1126】空袭状态专用：收翅猛击/激流那几拍滑翔位为假，但本轮仍在空袭。 */
    public static void impactAirRaid(ServerLevel level, LivingEntity user, Vec3 motion, int side,
                                     boolean allowNonGliding) {
        impact(level, user, motion, side, allowNonGliding);
    }

    private static void impact(ServerLevel level, LivingEntity user, Vec3 motion, int side,
                               boolean allowNonGliding) {
        try {
            if (level == null || user == null || motion == null || !user.isAlive()) {
                return;
            }
            if (!MaidSmartConfig.DAYUN_SMASH.get()) {
                return;
            }
            if (!user.isFallFlying() && !allowNonGliding) {
                return; // 与开路同口径：只有真在滑翔才动手（见 DaYunBreaker#sweep）
            }
            double vx = motion.x;
            double vy = motion.y;
            double vz = motion.z;
            double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (speed < MIN_SPEED) {
                return; // 几乎静止：不结算
            }
            // 伤害取两者较高：配置的「每格速度」与鞘翅撞墙同款公式（max(0, 速度×10−3)）
            float configured = (float) Math.min(speed * MaidSmartConfig.DAYUN_SMASH_DAMAGE.get(), MAX_DAMAGE);
            float elytra = (float) Math.max(0.0D, speed * ELYTRA_DAMAGE_SCALE - ELYTRA_DAMAGE_BASE);
            float damage = Math.min(Math.max(configured, elytra), MAX_DAMAGE);
            double knock = Math.min(speed * MaidSmartConfig.DAYUN_SMASH_KNOCKBACK.get(), MAX_KNOCKBACK);
            int half = DaYunBreaker.halfOf(side);
            Set<UUID> done = new HashSet<>();
            for (BlockPos center : DaYunBreaker.coveredCenters(user, motion)) {
                AABB box = new AABB(
                        center.getX() - half, center.getY() - half, center.getZ() - half,
                        center.getX() + half + 1, center.getY() + half + 1, center.getZ() + half + 1);
                for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, box)) {
                    if (!done.add(target.getUUID())) {
                        continue; // 同一 tick 内相邻立方体重叠：只结算一次
                    }
                    if (target == user || !target.isAlive()) {
                        continue;
                    }
                    if (isFriendly(user, target)) {
                        continue; // 主人 / 同主女仆 / 自己的宠物 / 友军：绝不撞击
                    }
                    strike(user, target, damage, knock, half + 1.0D);
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("DaYunSmash", ignored);
            // 饰品 tick 跑在女仆主 tick / 玩家 ServerTick 里，任何异常都不能抛穿
        }
    }

    /**
     * 单次结算：伤害（按速度，归因给使用者）+ TNT 式径向击飞（以佩戴者为圆心）。
     * @param knock  击飞强度（速度 × 系数，已封顶）
     * @param radius 范围半径（做距离衰减用：圆心处最强，边缘仍保留 35%）
     */
    private static void strike(LivingEntity user, LivingEntity target,
                               float damage, double knock, double radius) {
        // 伤害源：玩家 → 玩家攻击（死亡信息/击杀归玩家）；女仆 → 生物攻击（与她的近战同源）
        net.minecraft.world.damagesource.DamageSource src = (user instanceof Player player)
                ? user.damageSources().playerAttack(player)
                : user.damageSources().mobAttack(user);
        target.hurt(src, damage);
        // 击飞方向：**以佩戴者为圆心 → 目标**（TNT 爆炸的口径），而不是顺着飞行方向单推。
        // 这样一次滑翔穿过一群敌人时，他们会以她/他为中心四散炸开。
        double dx = target.getX() - user.getX();
        double dy = (target.getY() + target.getBbHeight() * 0.5D)
                - (user.getY() + user.getBbHeight() * 0.5D);
        double dz = target.getZ() - user.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double ux;
        double uy;
        double uz;
        if (dist < 1.0E-4D) {
            ux = 0.0D; // 叠在圆心：向上炸开
            uy = 1.0D;
            uz = 0.0D;
        } else {
            ux = dx / dist;
            uy = dy / dist;
            uz = dz / dist;
        }
        // 距离衰减：圆心 100%，到范围边缘仍有 35%（贴脸最狠，远处也不会像被轻轻碰一下）
        double falloff = 1.0D - 0.65D * Math.min(1.0D, dist / Math.max(1.0D, radius));
        double strength = knock * falloff;
        if (strength <= 0.0D) {
            return;
        }
        // TNT 同款 push：三个分量一起给（水平四散 + 向上弹）；push 自带 hasImpulse，客户端会同步
        target.push(ux * strength, uy * strength + strength * 0.35D, uz * strength);
    }

    /**
     * 撞击不伤友军——与 {@code FriendlyFireGuard#isFriendly} 同口径，但两处刻意不同：
     * ①不看 {@code COMBAT_FRIENDLY_FIRE_GUARD} 总闸：本功能是"她/他的撞击"，不能因为玩家
     * 把那个总闸关了，主人就反被自家女仆撞飞；②覆盖玩家侧：玩家自己也有"自己的女仆 / 宠物 / 队友"。
     * 判据读不出来时一律返回 true（宁可不撞），避免在异常路径上误伤。
     */
    static boolean isFriendly(LivingEntity user, LivingEntity target) {
        try {
            if (target == null || target == user) {
                return true;
            }
            LivingEntity owner = (user instanceof EntityMaid maid) ? maid.getOwner()
                    : (user instanceof Player p ? p : null);
            if (owner != null && owner.getUUID().equals(target.getUUID())) {
                return true; // 自己 / 主人
            }
            if (target instanceof EntityMaid other && other.getOwner() != null && owner != null
                    && owner.getUUID().equals(other.getOwner().getUUID())) {
                return true; // 同一个主人的女仆
            }
            if (target instanceof TamableAnimal pet && pet.isTame() && owner != null
                    && owner.getUUID().equals(pet.getOwnerUUID())) {
                return true; // 使用者名下的宠物
            }
            return user.isAlliedTo(target); // isAlliedTo：同队 / 同盟
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("DaYunSmash", ignored);
            return true;
        }
    }
}
