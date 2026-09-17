package com.maidsmart.tool;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;

/**
 * 实测四百四十三【悬空禁搭方块】（v1.2.0 实测四百九十八收紧）——
 * 反馈原文："女仆在处于悬空状态下的时候应该禁止搭建方块（这个机制在挖矿和伐木的
 * 时候也通用，有时候就是因为下落悬空的时候搭方块又放不了落地水，导致自己被摔死
 * 了）。"；实测四百九十八追加反馈："女仆似乎还是会在悬空和滑翔状态下搭方块。"
 *
 * ── 四百四十三为什么没拦住（两处漏口）──
 * 旧判定的口径是"未落地 **且**（重锤跃起 **或** 坠落距离 ≥ 落地水触发高度）"：
 * 1. **滑翔被显式放行**：`isFallFlying()` 与"站在地面/水里/骑乘"并列在放行名单里
 *    ——可空袭期间她本来就在滑翔，于是"滑翔中搭方块"这条被完全放行；
 * 2. **普通悬空只拦深坠落**：阈值是 `combat.waterFallDistance`（默认 6 格）——
 *    3 格、5 格的坠落照样放行。而滑翔中 `LivingEntity.travel` 每 tick 调
 *    `Entity.m_245125_()` 把 `fallDistance` **钳在最多 1.0**，所以即便把滑翔从
 *    放行名单里拿掉、只留这条阈值，滑翔期也**永远不会**触发——必须单独判滑翔位。
 *
 * ── 现在的判定（下面全部满足才禁）──
 * - 开关 `misc.noPlaceInAir`（默认开）；
 * - 未落地（`onGround=false`）——站在地上搭方块是各模块的常态，一律放行；
 * - 不在水/岩浆里（水里搭方块是正常操作；**岩浆里垫方块自救更是保命行为**——
 *   实测五百零九确认了这条必须留，见 blocked 内注释）；没骑乘；
 * - 且满足任一条：
 *   a) 滑翔中 / 飞行作战空中（`MaidFlightKit.isGliding` / `isFlightAirborne`）——
 *      **这是本次新增的主漏口**。滑翔（`fallDistance` 被钳在 1.0）、收翅猛击、
 *      被击落盘旋等"飞行期"一律不搭；空袭期间搭桥/垫脚既无意义又会抽掉她脚下的
 *      落点结构、挡住落地水。
 *   b) 重锤跃起中（`MaidMaceSmashBehavior.isAirborne`）——整段空中都禁
 *      （上升段也算：起跳瞬间乱搭同样会破坏猛击节奏）。
 *   c) 正在下落（`fallDistance > 0`）——**由四百四十三的"≥ 落地水高度"改为"只要在
 *      降就禁"**（本次收紧的第二处）。原阈值是给"浅跳垫脚"留的余量，但反馈明确
 *      要求悬空就不该搭；**上升段（`fallDistance == 0`，如垫脚/搭路把自己顶上去的
 *      那一推）不受影响**，所以正常垫高与搭路链路照常工作。
 *
 * 【统一闸口】四个搭方块模块（自保搭高·搭路·挖矿垫脚·伐木垫脚）都在各自的
 * takeBuildBlock 取料前问一次本判定：被禁 → 返回 null → 各模块自然放弃本次
 * 放置（不消耗方块、不动世界）。"背包里没有搭方块的材料"这类取料失败气泡也
 * 同样静默，避免悬空时误报。
 *
 * 【不拦】落地水/细雪本身（WaterClutchBehavior 直接放水，不走这里——它是保命
 * 机制，正是用来防摔死的）、照明光块（MaidHeldLight，非"搭建"语义且不消耗物品）、
 * 农耕播种——这些要么是保命机制、要么与坠落无关。
 *
 * 注：插火把（MaidTorchPlacerBehavior）与 AI 工具 `smart_place` 不经本闸口——
 * 前者要求目标格脚下有实心支撑面且贴着主人，后者是玩家的显式指令；两者都不属于
 * "她自己在空中乱搭"的情形。
 */
public final class MaidPlaceGuard {
    private MaidPlaceGuard() {
    }

    /** 悬空/滑翔/坠落中 → true = 本次禁止搭方块 */
    public static boolean blocked(EntityMaid maid) {
        try {
            if (maid == null || !com.maidsmart.config.MaidSmartConfig.MISC_NO_PLACE_IN_AIR.get()) {
                return false;
            }
            // v1.2.0 实测五百零九【岩浆放行】：1.21.1 侧这里一直是对的（用 Mojmap 的
            // `isInLava()`，不存在 1.20.1 那个"SRG 名字注反"的问题）。本次与 1.20.1 同步
            // 补注，说明这条为什么必须留：
            // 岩浆不会像水那样把 fallDistance 清零，只每 tick 减半（Entity.baseTick 里
            // `fallDistance *= 0.5f`），所以泡岩浆下沉时它稳定停在一个小正数——若不在此
            // 放行，会落到下面 `fallDistance > 0` 那条被禁搭，`lavaStepUp` 取料失败返回
            // null → **岩浆垫方块自救整条失效**（她背包只有方块、没水桶/珍珠/抗火药时会
            // 一路走到"自救手段都用尽了"）。用户反馈："女仆在没有火焰保护饰品和抗火效果的
            // 情况下，在岩浆里垫方块自救是一个很正常的行为。"
            if (maid.onGround() || maid.isInWater() || maid.isInLava()
                    || maid.isPassenger()) {
                return false;
            }
            // (a) 滑翔 / 飞行作战空中——飞行期一律不搭（旧版在这里把滑翔放行了）
            if (com.maidsmart.combat.MaidFlightKit.isFlightAirborne(maid)) {
                return true;
            }
            // (b) 重锤跃起：整段空中禁搭（上升段也算，避免起跳瞬间乱搭）
            if (com.maidsmart.combat.MaidMaceSmashBehavior.isAirborne(maid)) {
                return true;
            }
            // (c) 只要在下落就禁（旧版要坠够 6 格才禁；上升段 fallDistance==0 不受影响）
            return maid.fallDistance > 0.0f;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 实测五百三十六【不得搭在主人身上】——目标格被主人碰撞箱占着就不搭。
     *
     * 需求原文："搭方块新要求，不得将方块搭在主人（尤其是头部）所在位置。
     * 即不得将方块搭在主人碰撞箱所触碰到的空气方块位置。"
     *
     * 【判据】`new AABB(pos).intersects(owner.getBoundingBox())`。边界语义已用字节码核实
     * （`AABB(BlockPos)` 就是该格整格体积（`x..x+1, y..y+1, z..z+1`）；
     * `intersects`/`m_82381_` 内部走 `m_82314_`，六个分量全是**严格不等式**）——
     * 所以主人**站在方块上**时，脚下那格与碰撞箱只是**相切**、不算被占；
     * 只有碰撞箱真正探进去的格（身体格、头部格）才算占。
     * 这正是需求要的语义：防的是"把方块塞进主人身体/头部"，
     * 而不是"主人站在某块方块上就不能在他脚下搞建设"。
     *
     * 【不限于自保/搭路】四个自主搭块模块、插火把、AI 工具 `smart_place`、
     * 蓝图建造与碑石建造全部接本判定：—— 这一条是**安全约束**（防把主人痊住/卡住），
     * 与“是否她自己主动”无关：玩家一句"把这里填上"也不该把自己埋了。
     *
     * 【不管她自己】女仆自身所在格依然照搭——`placeStep` 把块垫在自己脚下、
     * 蓝图以她脚下为原点先垫自己那块，均是设计意图（不改）。
     */
    public static boolean blockedAtOwner(EntityMaid maid, BlockPos pos) {
        try {
            if (maid == null || pos == null
                    || !com.maidsmart.config.MaidSmartConfig.MISC_NO_PLACE_ON_OWNER.get()) {
                return false;
            }
            net.minecraft.world.entity.LivingEntity owner = maid.getOwner();
            if (owner == null || !owner.isAlive() || owner.level() != maid.level()) {
                return false;
            }
            return new net.minecraft.world.phys.AABB(pos).intersects(owner.getBoundingBox());
        } catch (Throwable ignored) {
            return false;
        }
    }
}
