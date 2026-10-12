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
 * 「大运」撞击：佩戴者鞘翅滑翔时，**破坏范围内**的非友方单位按**这一刻的速度**吃一记伤害 + 被击飞。
 *
 * <p>【范围】与「开路」共用同一份口径——{@link DaYunBreaker#coveredCenters}（前瞻采样点 + 自身那格），
 * 立方体边长取 {@code accessory.daYunRange}，所以"破坏范围内"在两边指同一片地方。
 *
 * <p>【伤害】{@code min(速度 × daYunSmashDamage, 40)}；【击飞】{@code min(速度 × daYunSmashKnockback, 10)}，
 * 方向顺着佩戴者这一刻的**运动方向**（含上下——平飞顺着飞出去、俯冲往下砸、爬升往上抛）；低于 {@link #MIN_SPEED} 不结算。
 *
 * <p>【谁不算】使用者自己、主人、同主女仆、使用者名下的宠物、同队同盟——见 {@link #isFriendly}。
 * 该判据**刻意不看** {@code COMBAT_FRIENDLY_FIRE_GUARD} 总闸，所以玩家关掉它主人也不会反被撞飞。
 *
 * <p>【开关】{@code accessory.daYunSmash}（默认开）。每 tick 每个佩戴者一次，同一 tick 内相邻立方体
 * 重叠的目标只结算一次。两树各一份：Forge 用 SRG 名（本文件）、Neo 用 Mojmap 名。
 */
public final class DaYunSmash {

    private DaYunSmash() {
    }

    /** 触发速度下限（格/tick）：低于它（几乎悬停）不结算，免得站着不动也把旁边的东西撞飞。 */
    private static final double MIN_SPEED = 0.4D;

    /** 伤害上限（点）：速度再高也不会一撞就秒掉精英怪。 */
    private static final float MAX_DAMAGE = 40.0F;

    /** 击飞强度上限（原版 knockback 的 strength 口径；普攻击退约 0.5 作参照）。 */
    private static final double MAX_KNOCKBACK = 10.0D;

    /**
     * 对范围内非友方单位结算一次撞击（伤害 + 击飞，均按速度）。
     * @param level  服务端世界
     * @param user   佩戴者（玩家或女仆）
     * @param motion 本 tick 速度矢量（判定速度与击飞方向都用它——击飞就顺着这个方向）
     * @param side   破坏立方体边长（与开路同一项配置）
     */
    public static void impact(ServerLevel level, LivingEntity user, Vec3 motion, int side) {
        try {
            if (level == null || user == null || motion == null || !user.isAlive()) {
                return;
            }
            if (!MaidSmartConfig.DAYUN_SMASH.get()) {
                return;
            }
            if (!user.isFallFlying()) {
                return; // 与开路同口径：只有真在滑翔才动手（见 DaYunBreaker#sweep）
            }
            double vx = motion.x;
            double vy = motion.y;
            double vz = motion.z;
            double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (speed < MIN_SPEED) {
                return; // 几乎静止：不结算
            }
            float damage = (float) Math.min(speed * MaidSmartConfig.DAYUN_SMASH_DAMAGE.get(), MAX_DAMAGE);
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
                    strike(user, target, damage, knock, vx, vy, vz);
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("DaYunSmash", ignored);
            // 饰品 tick 跑在女仆主 tick / 玩家 ServerTick 里，任何异常都不能抛穿
        }
    }

    /**
     * 单次结算：伤害（按速度，归因给使用者）+ 击飞（按速度，方向顺着佩戴者的运动方向）。
     * @param knock 击飞强度（速度 × 系数，已封顶）
     */
    private static void strike(LivingEntity user, LivingEntity target,
                               float damage, double knock, double vx, double vy, double vz) {
        // 伤害源：玩家 → 玩家攻击（死亡信息/击杀归玩家）；女仆 → 生物攻击（与她的近战同源）
        net.minecraft.world.damagesource.DamageSource src = (user instanceof Player player)
                ? user.damageSources().playerAttack(player)
                : user.damageSources().mobAttack(user);
        target.hurt(src, damage);
        // 击飞方向：顺着佩戴者这一刻的**运动方向**（含上下）——平飞就顺着飞出去、俯冲往下砸、爬升往上抛。
        // 水平分量走原版击退（会先把目标原有水平速度折半，像"被打飞"）；竖直分量用 push 补上。
        double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (speed < 1.0E-4) {
            return; // 这一刻没有运动方向：只吃伤害，不击飞
        }
        double ux = vx / speed;
        double uy = vy / speed;
        double uz = vz / speed;
        double horizontal = Math.sqrt(ux * ux + uz * uz);
        if (horizontal > 1.0E-4) {
            target.knockback(knock * horizontal, ux / horizontal, uz / horizontal);
        }
        if (Math.abs(uy) > 1.0E-4) {
            target.push(0.0D, knock * uy, 0.0D);
        }
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
