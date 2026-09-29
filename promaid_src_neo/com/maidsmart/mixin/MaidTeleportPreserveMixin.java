package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 自保传送豁免第二道防线（v1.5.17）。
 *
 * 第一道（FollowPreserveMixin）拦 MaidFollowOwnerTask.checkExtraStartConditions——
 * 但那只在"跟随行为尚未启动"时生效；若跟随行为已处于 RUNNING 状态
 * （比如自保触发前正在跟随），或跟随逻辑走到了 start 里的 teleportToOwner，
 * 仍需直接拦截传送动作本身。
 *
 * 这里 mixin EntityMaid.teleportToOwner（开发名目标）：自保标记存在且血量
 * 未恢复 70% 时直接返回 false——无论从哪个路径调用都不再瞬移回主人身边。
 */
@Mixin(EntityMaid.class)
public abstract class MaidTeleportPreserveMixin {
    private static final String PRESERVE_TAG = "maid_smart_preserving";

    @Inject(method = "teleportToOwner", at = @At("HEAD"), cancellable = true)
    private void maidSmartPreserveTeleport(LivingEntity owner, CallbackInfoReturnable<Boolean> cir) {
        EntityMaid maid = (EntityMaid) (Object) this;
        // 实测四百四十二：重锤跃起中禁止 TLM 跟随瞬移——她跳到半空被 teleportToOwner
        // 拽回主人身边，猛击白跳、战位全乱（反馈："重锤状态下空中禁止传送，否则很
        // 容易因为飞到高空又传送回来，造成战术上的失误"）。跃起 ≤5 秒自动收尾。
        if (com.maidsmart.combat.MaidMaceSmashBehavior.isAirborne(maid)) {
            cir.setReturnValue(false);
            return;
        }
        // v1.5.138：建造女仆（任务 = maid_smart:build）任何时刻禁止 TLM 瞬移回主人身边。
        // 根治"下达建造后女仆被传送走"：v1.5.121 的 home 模式只在建造行为 doStart 后
        // 才强制开启，切任务→行为启动前的窗口期 FollowOwner（CORE 3）若在跑且
        // 距离主人 > 7 格 → teleportToOwner 把女仆瞬移回主人身边（主人没动、女仆被传）。
        // 这里从 TLM 源头一网打尽：建造女仆永不瞬移（站桩定位由建造行为自己负责）。
        try {
            // v1.5.252q：getPath = getPath（javap 实证）——旧版写 "maid_smart:build"
            // 恒为 false，建造女仆瞬移拦截从未生效，此处修正
            if (maid.getTask() != null && "build".equals(maid.getTask().getUid().getPath())) {
                cir.setReturnValue(false);
                return;
            }
            // v1.1.0：搭路中禁瞬移——正在垫方块靠近主人时被 teleportToOwner 拉走
            // 会白搭（桥断了还浪费方块）；搭完自然恢复
            if (((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getBoolean("maid_smart_bridging")) {
                cir.setReturnValue(false);
                return;
            }
            // v1.1.0 实测三百一十五（反馈："怀疑是老代码作祟"——基岩层传送问题复查）：
            // 坐垫/骑乘/蹲下豁免——TLM 原版 teleportToOwner（离主人过远自动传送）只被
            // 自保/建造/搭路拦截，坐垫/骑乘/蹲下的女仆仍会被拉走。反馈"蹲下、坐垫全都
            // 固定会这样"正是这条路径（我们 mod 的救援/拉回已豁免，TLM 原版没拦）。
            // 坐垫/骑乘/蹲下 = 玩家明确停放，TLM 原版传送同样不拉。
            if (maid.isMaidInSittingPose() || maid.isPassenger()
                    || maid.canBreatheUnderwater()) { // canBreatheUnderwater = isShiftKeyDown（蹲下）
                cir.setReturnValue(false);
                return;
            }
            // v1.2.0【实测四百八十七】：飞行作战进行中禁止 TLM 跟随瞬移——她正扑向敌人，
            // 主人一远就被 teleportToOwner 拽回主人身边 = 这一轮攻击白费（用户反馈：
            // "飞向敌人离地面较近的时候，如果主人离得太远会触发自动传送，导致本次攻击
            // 被卡掉"）。与 1.20.1 同款豁免；旧版这棵树定义了 isFlightAirborne 却从未接线。
            // 注意：本判定含"收翅猛击"阶段（那一刻滑翔位是清的），一轮打完即恢复。
            if (com.maidsmart.combat.MaidFlightKit.isFlightAirborne(maid)) {
                cir.setReturnValue(false);
                return;
            }
            // 【v1.3.0(beta) 实测六百六十四：扫帚模式补上同一道闸】滑翔那条早就豁免了，扫帚漏了
            //  ——原版 teleportToOwner 走 {@code Entity.teleportTo}，而它第一件事就是 unRide()：
            //  把她从扫帚上踹下来再传回主人身边，一趟飞行当场白费（她只有 20 血，摔下来更糟）。
            //  玩家原话「开着 home 的扫帚模式，女仆不应该响应排班表的传送」在这里落地。
            if (com.maidsmart.combat.MaidBroomKit.isBroomAirborne(maid)) {
                cir.setReturnValue(false);
                return;
            }
            // 【v1.3.0(beta) 实测七百一十六·点4】骑坐骑的女仆不必在这里再补一道：她一定是乘客
            //  （isRideRider 要求 getVehicle() 非空），上面 isPassenger() 那道闸已经拦住原版
            //  teleportToOwner。玩家要的"她们也可以被传送过来"走本模组自己那条"连人带坐骑一起搬"
            //  （MaidChunkLoadManager.recallRideRider，内部先 stopRiding 再落座），不经过这一支。
            //
            // 【实测七百二十四】但"悬空鞍位"（冰火传说的龙）那条**必须补一道**：她**不是乘客**
            //  （降级方案里我们不 startRiding），上面 isPassenger() 拦不住她——于是 TLM 原版
            //  teleportToOwner 会把她从龙背鞍位上拽走，下一拍距离超线就被判定"断开链路"
            //  （玩家原话「动不动就传送和断开乘坐」）。这里与坐垫/骑乘/蹲下同口径：停放，不拉。
            //  玩家要的"她能被传送过来"走本模组自己那条"悬空鞍位：只传她不传龙"。
            //
            // 【实测七百二十六·点1】判据放宽到 isSpecialMountRider（龙 + 卓越前线载具）：
            //  玩家原话「对于卓越前线以及龙这两个特殊的载具……并不会去主动产生传送。所有的
            //  传送方面的行为必须由玩家来。」卓越前线的载具那条她一定是乘客（上面 isPassenger
            //  已拦），这里把判据统一成 isSpecialMountRider，语义只有一处、将来漏判也只会漏在
            //  这一格。TLM 原生 teleportToOwner 是"自动传送"的一种，特殊载具上一律不生效。
            if (com.maidsmart.combat.RideBindManager.isSpecialMountRider(maid)) {
                cir.setReturnValue(false);
                return;
            }
        } catch (Exception ignored) {
        }
        // v1.5.92：原"防窒息 20 秒传送冷却"抑制分支已移除——建仆不被瞬移回施工区
        // 由建造行为强制 home 模式从 TLM 源头保证（见 MaidBuildBehavior），不再需要
        // 后置的 teleportToOwner 拦截。此 mixin 只保留自保传送豁免。
        if (((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getBoolean(PRESERVE_TAG)
                && maid.getHealth() / Math.max(1.0f, maid.getMaxHealth()) < 0.70f) {
            cir.setReturnValue(false);
        }
    }
}
