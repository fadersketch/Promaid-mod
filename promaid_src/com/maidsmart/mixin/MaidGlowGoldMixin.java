package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v1.3.0 实测六百七十四【金色标记】：发光的女仆 = **金边**，不是原版的白边。
 *
 * ── 玩法 ──
 * 被武装拴绳选中（牵绳档：她还没正式起飞）的女仆由服务端 {@code setGlowingTag(true)} 打上原版
 * 发光标记 —— 渲染管线与"光灵箭射中的敌人"完全同一条（{@code LevelRenderer} 里
 * {@code shouldEntityAppearGlowing} → {@code OutlineBufferSource} 描边 + 穿墙可见）。
 * 唯一的差别就是**描边颜色**：原版取的是 {@code entity.getTeamColor()}（没有队伍 = 白），
 * 这里把它改成金色。玩家原话："对被武装拴绳选中的女仆加一层标记效果。效果同光灵箭射中敌人时的
 * 渲染，但是将光边改为金色。正式起飞时解除该标记效果。"
 *
 * ── 为什么动的是 getTeamColor ──
 * 反编译实证（1.21.1 与 1.20.1 同构）：{@code LevelRenderer} 里那一行是
 * {@code outline.setColor(entity.getTeamColor())} —— 描边颜色**只有这一个来源**，
 * 所以改这一处就是"口径只有一处"：不必去碰渲染管线，也不用给女仆塞一个计分板队伍
 * （塞队伍会连带改名牌颜色、友伤规则，还要往存档里写队伍，属于过度侵入）。
 *
 * ── 影响面（有意为之，写在明处）──
 * 判据是"**发光的女仆**"，而不是"我们标记的女仆"：本模组只会让"被拴绳选中的那只"发光，
 * 所以正常玩法里这条规则只对那只生效；万一有别的来源（比如她自己中了光灵箭）让女仆发光，
 * 描边也会是金色而不是白色——这是刻意的取舍（客户端零状态、零同步、零误伤判定）。
 * 总开关跟着武装拴绳走：功能关掉就一个字都不改，照原版白色。
 *
 * <p>服务端不需要这个 mixin，但方法本身两侧都存在，放进通用列表最省事（它在任何逻辑里都不被读：
 * {@code getTeamColor} 只服务于实体描边）。
 */
@Mixin(Entity.class)
public abstract class MaidGlowGoldMixin {

    /** 金色描边（比原版 {@code ChatFormatting.GOLD} 的 0xFFAA00 更亮一档，暗处也认得出） */
    private static final int PROMAID_GOLD = 0xFFD700;

    @Inject(method = "m_19876_()I", at = @At("HEAD"), cancellable = true)
    private void promaid$goldOutlineForMarkedMaid(CallbackInfoReturnable<Integer> cir) {
        try {
            if (!((Object) this instanceof EntityMaid maid)) {
                return;
            }
            if (!com.maidsmart.combat.GunnerTetherManager.isEnabled()) {
                return;
            }
            if (!maid.m_142038_()) {
                return;
            }
            cir.setReturnValue(PROMAID_GOLD);
        } catch (Throwable ignored) {
        }
    }
}
