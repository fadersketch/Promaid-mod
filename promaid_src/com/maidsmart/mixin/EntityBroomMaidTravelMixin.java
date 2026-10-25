package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityBroom;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v1.3.0「扫帚模式」：让**没有玩家驾驶**的扫帚听女仆的——唯一改动 TLM 扫帚飞行的地方。
 * ── 为什么非得动 TLM 的扫帚（而不是用它的 {@code IBroomControl} API）──
 * TLM 的扫帚确实是可扩展的（{@code IBroomControl} + {@code BroomControlManager}），但把
 * {@code EntityBroom.travel}/{@code tickRidden} 的字节码逐条读下来，它的每个回调**都要一个
 * {@code Player} 参数**：
 * <pre>
 *   public void travel(Vec3) {
 *       LivingEntity controlling = this.getControllingPassenger();
 *       Entity second = passengers.size() >= 2 ? passengers.get(1) : null;
 *       if (controlling instanceof Player p &amp;&amp; second instanceof EntityMaid m) {
 *           for (IBroomControl c : broomControls) if (c.inControl(p, m)) { c.travel(p, m); break; }
 *           this.move(MoverType.SELF, this.getDeltaMovement());
 *           return; } // ← 没有玩家驾驶时走这里：自由落体 } public LivingEntity getControllingPassenger() { Entity first = this.getFirstPassenger(); return first instanceof Player p ? p : null;   // ← 女仆当第一乘客时恒为 null } </pre> 结论：`IBroomControl` 是给"**玩家驾驶、女仆搭乘**"设计的（它的整套语义是"玩家开着扫帚时， 我们插一手改改谁说了算"）。**女仆单骑**这条路上根本没有回调点——她一个人骑上去， `getControllingPassenger()` 是 null，扫帚直接自由落体。所以"女仆无需玩家独立骑扫把" 只能在这一处接管。 ── 接管条件（**三条**全中才动，任何一条不中都是原版行为一字不改）── <ol> <li>{@code getPassengers().get(0)} 是 {@code EntityMaid}——注意这同时排除了 "玩家当第一乘客"（那样第一乘客是 Player，这里直接返回 null）；</li> <li>该女仆是**本模组所有**（{@code MaidScope.owned}，无主女仆一律不干预）且当前任务 就是扫帚模式（{@code maid_smart:broom}）——别的任务骑在扫帚上时照旧原版；</li> <li>{@code getControllingPassenger() == null}（没有玩家在驾驶）——双保险，语义自证；</li> </ol> 本批撤掉：那条闸让扫帚模式在"没拿绳子"时**整段失效**（原版对"女仆单骑"只有自由落体， 见上），玩家实测就是"有时正常、有时坐着扫帚掉在地上动也不动"。防冲突的目的一直由 上面这三条保证——不满足就一个字节都不动。 ── 接管后做什么 ── 读本 tick 的推进意图（{@link com.maidsmart.combat.MaidBroomDrive} 写的）→ 写进 {@code deltaMovement} → 自己 {@code move(MoverType.SELF, v)} → 转向 → cancel 掉原版。 **没有意图的 tick 就是"零速悬停"**（仍然 cancel）：不然她会在两拍之间掉一下，看着一颠一颠。 【转向为什么在这儿】没有 Player 时 {@code tickRidden} 根本不会被调用（它只由 "控制乘客是 Player"的那条链路触发），所以朝向也没人管了——由这里按驱动给的 yaw 设。 ── 【v1.3.2 修正：只让服务端驱动】客户端也会被 {@code aiStep} 调到 travel（实证）── {@code LivingEntity.aiStep} 里那次 travel 调用**没有 {@code isControlledByLocalInstance} 门槛**：{@code getControllingPassenger() instanceof Player ? travelRidden(...) : travel(...)} ——是"谁在控制"二选一，不是"哪一侧说了算"。对比一下原版 {@code travelRidden} 内部： 它把 {@code setSpeed + travel} 那一对**包在 {@code if (this.isControlledByLocalInstance())} 里**， 也就是说"玩家驾驶"这条路上，原版**刻意只让一侧真正施加位移**。 我们旧版没有这个门槛，于是**客户端和服务端各驱动一次**，而推进意图是"取走即清"的一格 队列（{@link com.maidsmart.combat.MaidBroomDrive#takeThrust}）——两边抢同一份意图， 谁先读到谁动，另一侧读到空 → 归零悬停。表现就是实测反馈的那三条： 「原地左右鬼畜晃动（客户端动了、服务端同步又把它拽回来）」「被反复拉回」「没有上升高度」。 现在跟原版同款口径：**客户端一律不驱动，位置等服务端权威同步下来**。
 */
@Mixin(EntityBroom.class)
public abstract class EntityBroomMaidTravelMixin {

    /**
     * 【R1126】玩家同乘时，把「控制乘客」改回女仆。
     * <p>原版 {@code LivingEntity.travelRidden} 只在 {@code isControlledByLocalInstance()}
     * 为真时调 {@code travel}——服务端上玩家驾驶时那是**假**，于是服务端一个字节都不动，
     * 位移只发生在客户端（这就是 R1125「飞过去没反应、玩家还在自主操纵」的根因）。
     * 这里让 {@link com.maidsmart.combat.MaidBroomDrive#maidShouldPilot} 判定：该她飞时
     * 返回 null，原版随即走 {@code travel} 分支，由服务端权威驱动；该玩家飞时原样返回。
     */
    @Inject(method = "m_6688_", at = @At("HEAD"), cancellable = true)
    private void maidsmart$maidTakesWheel(
            CallbackInfoReturnable<LivingEntity> cir) {
        try {
            EntityBroom self = (EntityBroom) (Object) this;
            EntityMaid maid = maidsmart$secondMaidPassengerWithPlayerDriver();
            if (maid != null && com.maidsmart.combat.MaidBroomDrive.maidShouldPilot(self, maid)) {
                cir.setReturnValue(null);
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("EntityBroomMaidTravelMixin", ignored);
        }
    }

    @Inject(method = "m_7023_", at = @At("HEAD"), cancellable = true)
    private void maidsmart$maidPilotedTravel(Vec3 input, CallbackInfo ci) {
        EntityBroom self = (EntityBroom) (Object) this;
        EntityMaid maid = maidsmart$firstMaidPassenger();
        if (maid == null) {
            // R1126：第一乘客是玩家、第二是她。只有「该她飞」时才会走到这里（上面的
            // getControllingPassenger 已经把控制乘客改成 null）；不该她飞时原版走
            // travelRidden，本轮根本不进 travel。
            maid = maidsmart$secondMaidPassengerWithPlayerDriver();
            if (maid == null || !com.maidsmart.tool.MaidScope.owned(maid)
                    || !com.maidsmart.combat.MaidBroomDrive.maidShouldPilot(self, maid)) {
                return;
            }
            net.minecraft.world.phys.Vec3 travelAim =
                    com.maidsmart.patrol.MaidTravelTarget.aim(maid);
            if (!com.maidsmart.combat.MaidBroomKit.isBroomTask(maid)) {
                // 原版 TLM 扫帚任务（或别的任务）+ 玩家同乘：没有扫帚行为替她算推进，
                // 就在这一拍直接按目标点算一次。没有目标 = 不该她飞（consistency 兜底）。
                if (travelAim == null) {
                    return;
                }
                com.maidsmart.combat.MaidBroomDrive.steerTo(maid, travelAim);
            }
            if (self.m_9236_().f_46443_) {
                return; // 客户端让位（服务端权威驱动；见类注释的 v1.3.2 段）
            }
            maidsmart$noteTakeover(self, maid);
            maidsmart$drive(self);
            ci.cancel();
            return;
        }
        if (!com.maidsmart.tool.MaidScope.owned(maid)) {
            return; // 无主女仆 → 原版
        }
        net.minecraft.world.phys.Vec3 travelAim = com.maidsmart.patrol.MaidTravelTarget.aim(maid);
        if (!com.maidsmart.combat.MaidBroomKit.isBroomTask(maid)) {
            // R1126：任务不是扫帚模式，但玩家点了「飞过去」——这一趟仍由她飞
            //（第一乘客是她 = 原版本来就没有驾驶者，我们只需补推进意图）。
            if (travelAim == null || self.m_9236_().f_46443_) {
                return;
            }
            com.maidsmart.combat.MaidBroomDrive.steerTo(maid, travelAim);
            maidsmart$noteTakeover(self, maid);
            maidsmart$drive(self);
            ci.cancel();
            return;
        }
        if (self.m_6688_() instanceof Player) {
            return; // 有玩家在驾驶：驾驶权归玩家与 TLM 自己的 IBroomControl，我们一个字不改
        }
        if (self.m_9236_().f_46443_) {
            // v1.3.2 修正：客户端也走这条 travel（见类注释）——谁动都行，但"意图队列"只有一份，
            // 两边都读就谁都动不了。客户端一律让位，等服务端的权威位置同步。
            return;
        }
        // 【实测六百八十二：679 那道「武装拴绳没在用就让位」的闸**撤掉了**】
        //  正被拴着"，不满足就 `travel` 走原版。而**原版对"女仆单独骑扫帚"根本没有驱动**
        //  （见类注释：`getControllingPassenger()` 对女仆恒为 null → 原版那支是自由落体），
        //  于是不拿着绳子时她必然"坐着扫帚掉在地上、动也不动"。
        //  （14:39:27、14:50:37），中间 40 多秒没有任何「扫帚模式」的飞行/爬升日志。
        //  玩家当初那句"不拿则走原版"的本意是不想让我们**改写 TLM 的扫帚链路**（防别的 mod
        //  冲突）——这一条由上面的三道接管条件（第一乘客是她 / 她的任务就是扫帚模式 /
        //  没有玩家在驾驶）已经严格保证：不满足就一个字节都不动。所以这里恢复 679 之前的口径。
        maidsmart$noteTakeover(self, maid);
        maidsmart$drive(self);
        ci.cancel();
    }

    /** 玩家驾驶、女仆坐第二座时返回她；否则 null。 */
    private EntityMaid maidsmart$secondMaidPassengerWithPlayerDriver() {
        try {
            EntityBroom self = (EntityBroom) (Object) this;
            if (self.m_20197_().size() < 2
                    || !(self.m_20197_().get(0) instanceof Player)
                    || !(self.m_20197_().get(1) instanceof EntityMaid m)) {
                return null;
            }
            return m;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("EntityBroomMaidTravelMixin", ignored);
            return null;
        }
    }

    /** 用本 tick 的推进意图移动扫帚（原本只有"女仆单骑"那一支会用）。 */
    private static void maidsmart$drive(EntityBroom self) {
        try {
            Vec3 thrust = com.maidsmart.combat.MaidBroomDrive.takeThrust(self);
            Vec3 v = thrust == null ? Vec3.f_82478_ : thrust;
            self.m_20256_(v);
            self.m_19915_(com.maidsmart.combat.MaidBroomDrive.yaw(self), 0.0f);
            self.m_6478_(MoverType.SELF, v);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("EntityBroomMaidTravelMixin", ignored);
            // 驱动出问题时"原地悬停"而不是放她自由落体（她只有 20 血，掉下去就是死）
        }
    }

    /** 第一乘客是女仆才返回她；此外一律 null（含"第一乘客是玩家"和"空车"） */
    private EntityMaid maidsmart$firstMaidPassenger() {
        try {
            EntityBroom self = (EntityBroom) (Object) this;
            if (self.m_20197_().isEmpty()) {
                return null;
            }
            return self.m_20197_().get(0) instanceof EntityMaid m ? m : null;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("EntityBroomMaidTravelMixin", ignored);
            return null;
        }
    }

    /* ==================== 接管留痕（每把扫帚一次，便于实测验收） ==================== */

    private static final java.util.Set<java.util.UUID> NOTED = new java.util.HashSet<>();
    private static final int NOTE_CAP = 256;

    /**
     * 第一次真正接管这把扫帚时记一条——**只在服务端**（客户端在上面就 return 了）。
     * 用途：实测时一眼分辨"驱动链路到底通没通"。日志里搜「扫帚接管」。
     */
    private static void maidsmart$noteTakeover(EntityBroom broom, EntityMaid maid) {
        try {
            java.util.UUID id = broom.m_20148_();
            synchronized (NOTED) {
                if (!NOTED.add(id)) {
                    return;
                }
                if (NOTED.size() > NOTE_CAP) {
                    NOTED.clear();
                    NOTED.add(id);
                }
            }
            com.maidsmart.tool.PromaidLog.log("扫帚接管",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 服务端开始接管扫帚驱动（车上 "
                            + broom.m_20197_().size() + " 人，驾驶权由女仆接管；y="
                            + String.format(java.util.Locale.ROOT, "%.2f", broom.m_20186_()) + "）");
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("EntityBroomMaidTravelMixin", ignored);
        }
    }

    /* ==================== 让位留痕（679 起、682 止） ==================== */
    // 【实测六百八十二】679 的 `maidsmart$noteStandDown`（"武装拴绳没在用 → 让位"那条留痕）
    // 随那道闸一起删掉了：本类现在只在"她是扫帚模式 + 无玩家驾驶"时接管，不再有"让位"这一档。
    // 留着它等于给下一个人准备一条永远打不出来的日志（本项目"口径只有一处"的同类洁癖）。

}
