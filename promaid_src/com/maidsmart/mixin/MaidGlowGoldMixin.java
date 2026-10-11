package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 【金色标记】：发光的女仆 = **金边**，不是原版的白边。
 *
 * 【玩法】被武装拴绳选中（牵绳档：她还没正式起飞）的女仆由服务端 {@code setGlowingTag(true)} 打上原版发光
 * 标记——渲染管线与"光灵箭射中的敌人"完全同一条（{@code LevelRenderer} 里
 * {@code shouldEntityAppearGlowing} → {@code OutlineBufferSource} 描边 + 穿墙可见）。唯一差别是**描边
 * 颜色**：原版取 {@code entity.getTeamColor()}（没有队伍 = 白），这里改成金色。
 * 【为什么动的是 getTeamColor】反编译实证（1.21.1 与 1.20.1 同构）：{@code LevelRenderer} 里那一行是
 * {@code outline.setColor(entity.getTeamColor())}——描边颜色**只有这一个来源**，所以改这一处就是"口径只有
 * 一处"：不必碰渲染管线，也不用给女仆塞一个计分板队伍（塞队伍会连带改名牌颜色、友伤规则，还要写存档）。
 * 【影响面（有意为之）】判据是"**发光的女仆**"而不是"我们标记的女仆"：本模组只会让"被拴绳选中的那只"发光，
 * 所以正常玩法里只对那只生效；万一有别的来源（如她自己中了光灵箭）让女仆发光，描边也会是金色——刻意的
 * 取舍（客户端零状态、零同步、零误伤判定）。总开关跟武装拴绳走：功能关掉就照原版白色。服务端不需要这个
 * mixin，但方法两侧都存在，放进通用列表最省事。
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
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidGlowGoldMixin", ignored);
        }
    }
}
