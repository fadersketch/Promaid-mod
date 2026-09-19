package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 实测五百六十三：空袭 × 暮色森林【孔雀羽扇】软兼容。
 *
 * 需求原文："空袭可以兼容一下暮色森林的孔雀羽扇，也和烟花火箭一样可以起飞（起飞力度
 * 及后续使用烟花的路段都照搬孔雀羽扇自己），每使用一次都使用孔雀羽扇自己的扣耐久机制"。
 *
 * 【全部照搬 1.20.1 / 1.21.1 两版 PeacockFanItem 反编译实证的原版数值】：
 * <ul>
 *   <li><b>滑翔助推</b>（use 的滑翔分支）：Δv = 速度 + 视线×0.1 + (视线×2 − 速度)×0.5
 *       + 竖直 1.25——女仆带鞘翅滑翔，与玩家持扇滑翔同一份推力；</li>
 *   <li><b>扇风盒</b>（getEffectAABB/fanEntitiesInAABB）：视线方向前 3 格、半径 2 格，
 *       盒内 isPushable 生物 / 掉落物 / 弹射物被 视线×2 的速度扇飞——空袭途中顺带扇开
 *       贴脸的怪（友军由友军风免闸口自行拦，见 FriendlyWindGuard）；</li>
 *   <li><b>扣耐久</b>（use 的服务端分支）：{@code hurtAndBreak(扇飞数 + 1)}——
 *       与原版逐字同口径（1.21.1 走 EquipmentSlot），含耐久附魔的随机豁免；</li>
 *   <li><b>使用节奏</b>（getUseDuration = 20）：每 20 tick（1 秒）最多挥一次；</li>
 *   <li><b>音效/粒子</b>：TF 自己的 item.fan.whoosh + 云朵粒子。</li>
 * </ul>
 *
 * 【为什么是软兼容】不 import 任何 TF 类：扇子按注册名 twilightforest:peacock_fan
 * 识别（运行时查注册表），音效按注册名取——暮色森林不在场时本类全部静默返回
 * false / 空物品，零开销零崩溃。也不搬 TF 的方块扇风（吹灭蜡烛/吹散花）——那是
 * 玩家使用路径的彩蛋，女仆空中挥扇不涉及，扇飞数里也不计入。
 *
 * 【能量对比】烟花 = 一次性弹药（30 tick 推力）；羽扇 = 可修装备（20 tick 一挥、
 * 每挥 1+ 耐久）——三件套的"飞行燃料"口径扩为【烟花 或 羽扇】，有扇先用扇。
 */
public final class TwilightFanKit {
    /** 暮色森林孔雀羽扇的注册名（1.20.1 与 1.21.1 相同） */
    public static final String FAN_ID = "twilightforest:peacock_fan";
    /** 原版 use 持续 20 tick（getUseDuration）——扇子的自然使用节奏 */
    public static final int FAN_USE_INTERVAL = 20;
    /** 扇风盒：视线方向前 3 格、半径 2 格（PeacockFanItem 同值） */
    private static final double FAN_RANGE = 3.0;
    private static final double FAN_RADIUS = 2.0;

    private TwilightFanKit() {
    }

    /** 是否孔雀羽扇（按注册名识别，暮色森林不在场时恒 false） */
    public static boolean isFan(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return key != null && FAN_ID.equals(key.toString());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 找扇子：主手 → 副手 → 背包（与 MaidFlightKit 的烟花口径一致）；空手返回 EMPTY */
    public static ItemStack findFan(EntityMaid maid) {
        if (maid == null) {
            return ItemStack.EMPTY;
        }
        if (isFan(maid.getMainHandItem())) {
            return maid.getMainHandItem();
        }
        if (isFan(maid.getOffhandItem())) {
            return maid.getOffhandItem();
        }
        try {
            net.neoforged.neoforge.items.IItemHandler inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                if (isFan(inv.getStackInSlot(i))) {
                    return inv.getStackInSlot(i);
                }
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.EMPTY;
    }

    /** 身上是否有孔雀羽扇 */
    public static boolean hasFan(EntityMaid maid) {
        return !findFan(maid).isEmpty();
    }

    /**
     * 挥一次扇子（空袭的"放烟花"等价动作）：滑翔助推 + 扇飞身前实体 + 扣耐久 + 音效粒子。
     *
     * @return true = 确实挥了（调用方按"本次推进已发生"处理）；false = 没有扇子/异常
     */
    public static boolean boostGlide(ServerLevel level, EntityMaid maid) {
        ItemStack fan = findFan(maid);
        if (fan.isEmpty() || level == null || maid == null) {
            return false;
        }
        try {
            Vec3 look = maid.getLookAngle();
            Vec3 mv = maid.getDeltaMovement();
            // 滑翔助推：与 TF use() 滑翔分支逐字同式
            maid.setDeltaMovement(mv.add(
                    look.x * 0.1 + (look.x * 2.0 - mv.x) * 0.5,
                    look.y * 0.1 + (look.y * 2.0 - mv.y) * 0.5 + 1.25,
                    look.z * 0.1 + (look.z * 2.0 - mv.z) * 0.5));
            int fanned = fanEntities(level, maid, look);
            damageFan(maid, fan, fanned);
            playWhoosh(level, maid);
            fanParticles(level, maid, look);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 扇风盒：身前 3 格半径 2 内的可推实体被 视线×2 扇飞（返回扇飞数，计入耐久） */
    private static int fanEntities(ServerLevel level, EntityMaid maid, Vec3 look) {
        try {
            Vec3 src = new Vec3(maid.getX(),
                    maid.getY() + maid.getEyeHeight(), maid.getZ());
            Vec3 dest = src.add(look.x * FAN_RANGE, look.y * FAN_RANGE, look.z * FAN_RANGE);
            AABB box = new AABB(dest.x - FAN_RADIUS, dest.y - FAN_RADIUS,
                    dest.z - FAN_RADIUS, dest.x + FAN_RADIUS,
                    dest.y + FAN_RADIUS, dest.z + FAN_RADIUS);
            Vec3 moveVec = look.scale(2.0);
            int fanned = 0;
            for (Entity e : level.getEntitiesOfClass(Entity.class, box)) {
                if (e == maid) {
                    continue;
                }
                if (e.isPushable() || e instanceof ItemEntity || e instanceof Projectile) {
                    e.setDeltaMovement(moveVec);
                    fanned++;
                }
            }
            return fanned;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 扣耐久：hurtAndBreak(扇飞数 + 1)——与 TF use() 同口径（含耐久附魔随机豁免） */
    private static void damageFan(EntityMaid maid, ItemStack fan, int fanned) {
        try {
            fan.hurtAndBreak(fanned + 1, maid, EquipmentSlot.MAINHAND);
        } catch (Throwable ignored) {
        }
    }

    /** TF 自己的挥扇音效（注册名不存在=暮色森林不在场，静默跳过） */
    private static void playWhoosh(ServerLevel level, EntityMaid maid) {
        try {
            SoundEvent snd = BuiltInRegistries.SOUND_EVENT.get(
                    ResourceLocation.fromNamespaceAndPath("twilightforest", "item.fan.whoosh"));
            if (snd != null) {
                level.playSound(null, maid.blockPosition(), snd, SoundSource.NEUTRAL, 1.0f, 1.0f);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 云朵粒子（TF 同款粒子，服务端 sendParticles 广播给附近玩家） */
    private static void fanParticles(ServerLevel level, EntityMaid maid, Vec3 look) {
        try {
            level.sendParticles(ParticleTypes.CLOUD, maid.getX(),
                    maid.getY() + maid.getEyeHeight(), maid.getZ(),
                    10, look.x * 1.5, look.y * 1.5, look.z * 1.5, 0.02);
        } catch (Throwable ignored) {
        }
    }
}
