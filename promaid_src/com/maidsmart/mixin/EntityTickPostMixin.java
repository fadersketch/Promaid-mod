package com.maidsmart.mixin;

import com.maidsmart.combat.RideBindManager;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.3.0(beta) 实测七百三十六【1.20.1 侧替代】：**实体 tick 之后的每拍摆位钩子**。
 *
 * <h2>为什么需要这个 mixin</h2>
 * 1.21.1 侧用的是 NeoForge 的 {@code EntityTickEvent.Post}——它在 {@code Entity.tick()} **返回之后**
 * 发，正好是"龙的 setPos 已经落地"的那一刻。{@code RideBindManager.onEntityTickPost} 靠它把
 * 悬空鞍位（冰火传说的龙）上的女仆摆到龙**这一帧的最终位置**：谁后 tick 谁的 Post 就是最后一次摆位，
 * 天然收敛到"她的位置 = 龙这一帧的最终鞍位"（用 {@code MaidTickEvent} 只能在**她自己**的 tick 里摆，
 * 龙若后 tick 她就整整落后一帧，飞起来就是玩家看到的"错位"）。
 *
 * <p><b>1.20.1（Forge 47.x）没有 {@code EntityTickEvent}</b>（它到 1.20.2 才进原版），所以这里用
 * 与本工程 {@link EntityTickBlameMixin} 同款的手法补齐：在 {@code Entity.tick}（SRG {@code m_8119_}）
 * 的 **RETURN** 处注入，把实体转交给 {@link RideBindManager#onEntityTickPost}——
 * 与 1.21.1 侧挂在 {@code EntityTickEvent.Post} 上逐字同口径（同一点、同一守卫、同一摆位算式）。
 *
 * <h2>成本</h2>
 * 每个实体每 tick 都会调进来一次；{@code RideBindManager.onEntityTickPost} 自己第一句就是最便宜的
 * 两道守卫（不是女仆也不是龙 → 立刻返回），所以这里不做任何筛选，保持与 1.21.1 侧一致。
 *
 * <p>SRG 名（手工编译无 refmap）：{@code m_8119_} = {@code Entity.tick}。
 */
@Mixin(Entity.class)
public abstract class EntityTickPostMixin {

    @Inject(method = "m_8119_", at = @At("RETURN"))
    private void promaid$seatAfterEntityTick(CallbackInfo ci) {
        RideBindManager.onEntityTickPost((Entity) (Object) this);
    }
}
