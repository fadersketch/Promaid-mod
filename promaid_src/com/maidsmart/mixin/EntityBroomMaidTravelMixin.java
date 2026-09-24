package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityBroom;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.3.0「扫帚模式」：让**没有玩家驾驶**的扫帚听女仆的——唯一改动 TLM 扫帚飞行的地方。
 *
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
 *           return;
 *       }
 *       // ← 没有玩家驾驶时走这里：自由落体
 *       ...
 *   }
 *   public LivingEntity getControllingPassenger() {
 *       Entity first = this.getFirstPassenger();
 *       return first instanceof Player p ? p : null;   // ← 女仆当第一乘客时恒为 null
 *   }
 * </pre>
 * 结论：`IBroomControl` 是给"**玩家驾驶、女仆搭乘**"设计的（它的整套语义是"玩家开着扫帚时，
 * 我们插一手改改谁说了算"）。**女仆单骑**这条路上根本没有回调点——她一个人骑上去，
 * `getControllingPassenger()` 是 null，扫帚直接自由落体。所以"女仆无需玩家独立骑扫把"
 * 只能在这一处接管。
 *
 * ── 接管条件（三条全中才动，任何一条不中都是原版行为一字不改）──
 * <ol>
 *   <li>{@code getPassengers().get(0)} 是 {@code EntityMaid}——注意这同时排除了
 *       "玩家当第一乘客"（那样第一乘客是 Player，这里直接返回 null）；</li>
 *   <li>该女仆是**本模组所有**（{@code MaidScope.owned}，无主女仆一律不干预）且当前任务
 *       就是扫帚模式（{@code maid_smart:broom}）——别的任务骑在扫帚上时照旧原版；</li>
 *   <li>{@code getControllingPassenger() == null}（没有玩家在驾驶）——双保险，语义自证。</li>
 * </ol>
 *
 * ── 接管后做什么 ──
 * 读本 tick 的推进意图（{@link com.maidsmart.combat.MaidBroomDrive} 写的）→ 写进
 * {@code deltaMovement} → 自己 {@code move(MoverType.SELF, v)} → 转向 → cancel 掉原版。
 * **没有意图的 tick 就是"零速悬停"**（仍然 cancel）：不然她会在两拍之间掉一下，看着一颠一颠。
 *
 * 【转向为什么在这儿】没有 Player 时 {@code tickRidden} 根本不会被调用（它只由
 * "控制乘客是 Player"的那条链路触发），所以朝向也没人管了——由这里按驱动给的 yaw 设。
 */
@Mixin(EntityBroom.class)
public abstract class EntityBroomMaidTravelMixin {

    @Inject(method = "m_7023_", at = @At("HEAD"), cancellable = true)
    private void maidsmart$maidPilotedTravel(Vec3 input, CallbackInfo ci) {
        EntityMaid maid = maidsmart$firstMaidPassenger();
        if (maid == null) {
            return; // 没女仆乘客（或第一乘客是玩家）→ 原版
        }
        if (!com.maidsmart.tool.MaidScope.owned(maid)
                || !com.maidsmart.combat.MaidBroomKit.isBroomTask(maid)) {
            return; // 无主女仆 / 不是扫帚模式 → 原版
        }
        EntityBroom self = (EntityBroom) (Object) this;
        if (self.m_6688_() instanceof Player) {
            return; // 有玩家在驾驶：驾驶权归玩家与 TLM 自己的 IBroomControl，我们一个字不改
        }
        try {
            Vec3 thrust = com.maidsmart.combat.MaidBroomDrive.takeThrust(self);
            Vec3 v = thrust == null ? Vec3.f_82478_ : thrust;
            self.m_20256_(v);
            self.m_19915_(com.maidsmart.combat.MaidBroomDrive.yaw(self), 0.0f);
            self.m_6478_(MoverType.SELF, v);
        } catch (Throwable ignored) {
            // 驱动出问题时"原地悬停"而不是放她自由落体（她只有 20 血，掉下去就是死）
        }
        ci.cancel();
    }

    /** 第一乘客是女仆才返回她；此外一律 null（含"第一乘客是玩家"和"空车"） */
    private EntityMaid maidsmart$firstMaidPassenger() {
        try {
            EntityBroom self = (EntityBroom) (Object) this;
            if (self.m_20197_().isEmpty()) {
                return null;
            }
            return self.m_20197_().get(0) instanceof EntityMaid m ? m : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
