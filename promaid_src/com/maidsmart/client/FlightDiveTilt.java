package com.maidsmart.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.combat.MaidFlightKit;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * v1.2.0 实测五百零六【飞行作战"跟随俯角"前倾】（1.20.1，纯客户端渲染）。
 *
 * ── 需求 ──
 * "近战空袭飞向敌人时身体建模可能还要向前方向翻一下，大致 35 度左右，这样更接近玩家的
 * 鞘翅飞行动作，现在的游泳动作让女仆在俯冲时更类似全身体正对敌人。仅修改建模，机制不变。"
 *
 * ── "游泳动作"是怎么来的（两树反编译实证）──
 * 本项目自己的 {@code MaidSwimGlideMixin} 让女仆滑翔时 `isVisuallySwimming()` 返回 true；
 * 而 TLM 的 Gecko 动画选择表 `AnimationRegister` 里，游泳动画的触发谓词正是它：
 * <pre>
 *   AnimationRegister.register("swim", 0, (maid, event) -> maid.asEntity().m_6067_());   // = isVisuallySwimming
 * </pre>
 * 于是滑翔期间模型播放自带的 `swim`（游泳）动画——身体被摆成水平俯卧、中轴直指目标，
 * 这就是反馈里的"全身体正对敌人"。本类**不改动画本身**（机制不变），只在整体矩阵上再叠
 * 一层随俯角变化的额外前倾，观感上向玩家鞘翅靠拢。
 *
 * ── 玩家鞘翅是怎么做的（权威配方，字节码实证）──
 * `PlayerRenderer.setupRotations`：
 * <pre>
 *   float ramp = Mth.clamp(t * t / 100.0F, 0.0F, 1.0F);   // t = 连续滑翔 tick
 *   poseStack.mulPose(Axis.XP.rotationDegrees(ramp * (-90.0F - player.getXRot())));
 * </pre>
 * 即"基础 -90° 之外再减掉俯仰角"。女仆这边的基础 -90° 由模型动画负责，所以本类只补
 * **`- getXRot()` 那一项**——俯冲时低头为正角，`-pitch` 让身体继续向前翻；平飞/盘旋
 * （俯角≈0）几乎无变化，无需再判阶段。这与"再往前翻约 35 度"的描述一致：35° 正是
 * 俯冲角本身，不是一个固定要加的角度。
 *
 * ── 施加位置 ──
 * 调用点是 `GeoReplacedEntityRenderer.setupRotations` 的 RETURN（见
 * {@code MaidFlightDiveTiltMixin}）。该方法先调 `LivingEntityRenderer.setupRotations`
 * 摆好朝向，此刻矩阵仍在"Y 朝上、原点在脚下"的世界式空间里（`scale(-1,-1,1)` 与
 * `translate(0,-1.501,0)` 尚未施加，属模型渲染之前），原版所有飞行/俯卧姿态都在这一层用
 * `Axis.XP` 施加——**轴向与枢轴因此与游戏一致**，只是在本基础上再叠一层，与模型自带的
 * swim 动画叠加而非互相覆盖。
 *
 * `Axis.f_252529_` = **XP**：对应关系取自 TLM 两树源码同一行（1.20.1 写 `f_252529_`、
 * 1.21.1 写 `XP` 的 `SwimAnimation.setupRotations`）。
 *
 * ── 触发口径 ──
 * `MaidFlightKit.isFlightTask(maid) && !onGround()`——与鞘翅图层的展翅口径完全一致
 * （`LayerMaidElytra` 同款）。**刻意不认 `isFallFlying()`**：收翅猛击那段 `tickSmash`
 * 会主动 `setGliding(false)`（收翅才吃得到猛击判定），只认滑翔位的话"飞向敌人"最需要
 * 前倾的那一段恰好不生效。
 *
 * ── 可调 ──
 * {@link #TILT_FACTOR} 是唯一调节量：1.0 = 与玩家鞘翅同款；嫌翻太多调小、嫌不够调大；
 * 方向若相反，把 {@link #apply} 里的取负去掉即可（一行）。纯客户端渲染，**不动任何飞行
 * 机制**、不加网络包、服务端无感。
 */
@OnlyIn(Dist.CLIENT)
public final class FlightDiveTilt {

    /** 前倾倍率：1.0 = 与玩家鞘翅同款（额外旋转 = -getXRot() × 本值） */
    private static final float TILT_FACTOR = 1.0f;

    private FlightDiveTilt() {
    }

    /**
     * 给飞行作战中的女仆叠一层"跟随俯角"的前倾。
     *
     * @param entity    当前渲染的实体（非女仆 / 未在飞行作战任务 / 已落地时原样返回）
     * @param poseStack 渲染矩阵（调用点在世界式空间，Y 朝上）
     */
    public static void apply(LivingEntity entity, PoseStack poseStack) {
        try {
            if (!(entity instanceof EntityMaid maid)) {
                return;
            }
            if (!MaidFlightKit.isFlightTask(maid) || maid.m_20096_()) {
                return;
            }
            float pitch = maid.m_146909_();
            if (pitch == 0.0f) {
                return;
            }
            // 原版低头为正角 → 取负与基础 -90° 同向，即"继续往前翻"
            poseStack.m_252781_(Axis.f_252529_.m_252977_(-pitch * TILT_FACTOR));
        } catch (Throwable ignored) {
        }
    }
}
