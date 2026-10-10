package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.combat.MaidFlightKit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v1.2.0（1.20.1）：飞行作战——滑翔时套用女仆的游泳动画（= 展翅姿态）。
 *
 * 为什么必须 mixin 这里：
 * 1. `EntityMaid` 覆写了 `isVisuallySwimming()`（SRG `m_6067_()`）为
 *    `return this.isSwimming();`（`m_6069_()`），**丢掉了 LivingEntity 里
 *    "FALL_FLYING pose 也算 visually swimming" 的分支** → 光靠滑翔 pose 不会出游泳动画；
 * 2. `MaidSwimManager.updateSwimming()`（由 `EntityMaid.m_5844_()` 每 tick 调）会在服务端
 *    每 tick 把游泳位清掉（`updatePose` → `setSwimming(false)`），所以不能靠 setSwimming 硬置。
 * 在 `m_6067_` 的 HEAD 直接按"是否滑翔"返回，最稳。
 *
 * 刻意不动 pose：`EntityMaid.m_6972_(Pose)` 对 SWIMMING 会返回 0.6×0.6 的判定箱，
 * 把滑翔塞进 SWIMMING pose 会让女仆判定箱缩水，副作用太大。
 *
 * ── v1.2.2 实测六百一十一【判据从"飞行任务"放宽成"正在滑翔"】──
 * 根因就在这里：这一支原来多问了一句
 * {@link MaidFlightKit#isFlightTask}，而**飞行跟随（跟着主人飞）的女仆并不是飞行任务**，
 * 于是她滑翔时模型仍是走路/站立的姿态——空袭那套展翅动画一点没跟上。
 *
 * 【为什么放心放宽：扫过整个 TLM 1.5.3 的 2344 个类，引用 `m_6067_` 的只有 3 个】
 * `client/animation/gecko/AnimationRegister`（动画选择谓词）、
 * `client/animation/special/SwimAnimation`（游泳动画本体）、
 * `entity/passive/EntityMaid`（就是它自己的覆写）——**全是客户端动画**，没有一个玩法判定读它，
 * 所以这个放宽只影响渲染。放成"只要在滑翔就为真"同时也是把原版语义还回来（原版
 * `LivingEntity.isVisuallySwimming` 本来就把 FALL_FLYING 算进去，是 TLM 覆写时丢的），
 * 而且判据取的是**同步过的滑翔位**——多人下客户端也认得出，不依赖服务端那张状态表。
 *
 * ── 实测八百一十一【姿势不再"打个架就散了"：判据与翅膀/前倾同口径】──
 *
 *
 *
 * <p>【根因是**判据三处不一致**】滑翔姿态实际上由三段各判各的：
 * <ul>
 *   <li><b>游泳姿态</b>（本 mixin）——原来只认 {@code m_21255_}（= isFallFlying）；</li>
 *   <li><b>背后展翅</b>（{@code LayerMaidElytra} / Gecko 层）——认 {@code isFlightVisual(maid)}；</li>
 *   <li><b>跟随俯角的前倾</b>（{@code FlightDiveTilt}）——认 {@code isFlightVisual(maid) && !onGround()}。</li>
 * </ul>
 * 而空袭里滑翔位是**闪的**：收翅猛击（{@code tickSmash}）、激流旋转突进、落地都会
 * 主动 {@code setGliding(false)}。于是那几拍里游泳姿态一掉、身体从俯卧回正，翅膀却照旧张着、
 * 前倾照旧按 {@code -getXRot()}（俯冲时可达 70°）转——三层叠在一起就是"半立着、又被硬掰了
 * 一个俯角、还背着展翅"的破碎姿势。（同一个"滑翔位闪烁"，实测八百零九 在 BGM 侧修过一次。）
 *
 * <p>【修法：把姿态这一段也收进**同一个口径**】判据改成
 * {@code isFlightVisual(maid) && !onGround()}——与展翅、前倾**逐字一致**。三层从此同进同退：
 * 一轮空袭从起跳到收手，只要还在空中就一直是俯卧+展翅+跟随前倾；落地立刻全收（{@code !onGround()}）。
 * 与实测八百零九 的女仆 BGM 同一个思路（那也是"把判据从闪烁的滑翔位放宽到整轮空中"），
 * 只是姿态这一段**必须用客户端可见的信号**：{@code isFlightVisual} = 飞行任务 UID（已同步，
 * 客户端认得出）+ 滑翔位；而服务端那张"本轮正在打"的状态表客户端看不到。
 *
 * <p>【仍然不设 pose】只改本方法的返回值。判定箱只在 {@code Pose.SWIMMING} 那一支收窄
 * （见 {@code EntityMaid.getDimensions}），而本方法并不参与 pose 的派生，所以碰撞箱一个像素不动。
 */
@Mixin(EntityMaid.class)
public abstract class MaidSwimGlideMixin {

    @Inject(method = "m_6067_()Z", at = @At("HEAD"), cancellable = true)
    private void promaid$glideUsesSwimPose(CallbackInfoReturnable<Boolean> cir) {
        EntityMaid maid = (EntityMaid) (Object) this;
        // 六百一十一：只看"在不在飞"，不再要求"是飞行任务"——原因见类注释
        // （飞行跟随不是飞行任务；且 TLM 里读这个方法的只有 3 个客户端动画类）
        // 实测七百〇二【滑翔动画二选一（1.20.1 精简版一并搬入）】：默认沿用游泳动作（兼容性最好：
        // 官方包与第三方包普遍都有 swim，而几乎没有 elytra_fly）；打开「滑翔时用鞘翅动画」后
        // **不再顶游泳位**，改由 MaidFreeFlightAnimMixin 注册的 elytra_fly 状态接管。
        // 实测八百一十一：判据从 `m_21255_()` 改成 `isFlightVisual && !onGround()`，
        // 与展翅 / 跟随前倾同口径——修掉"收翅那几拍姿势散架"（原因见类注释）。
        if (!com.maidsmart.config.MaidSmartConfig.MISC_GLIDE_ELYTRA_ANIM.get()
                && !maid.m_20096_()
                && com.maidsmart.combat.MaidFlightKit.isFlightVisual(maid)) {
            cir.setReturnValue(true);
            return;
        }
        // 实测八百一十二【自动兜底】：开关开着，但**这个模型根本没做 elytra_fly**
        // （玩家实测现场：模型包里 0 条同名动画）→ 那一档取不出动画、模型会掉回绑定姿势。
        // 这时照样顶游泳位：宁可播游泳动作，也不能让她姿势散架。
        // 判据只看**客户端 TLM 动画表**（与 TLM 取动画同一张表、同一个键），服务端一次都不碰。
        if (com.maidsmart.config.MaidSmartConfig.MISC_GLIDE_ELYTRA_ANIM.get()
                && !maid.m_20096_()
                && com.maidsmart.combat.MaidFlightKit.isFlightVisual(maid)
                && !com.maidsmart.flight.MaidGlideAnimSupport.hasElytraFly(maid)) {
            cir.setReturnValue(true);
        }
    }
}
