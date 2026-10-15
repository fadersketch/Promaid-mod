package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.AbstractMaidContainer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「真正的远程开界面」：把 TLM 女仆容器里**那条距离判据**放行（排班表点「女仆配置」不再"开一下闪退"）。
 *
 * 【根因】TLM {@code AbstractMaidContainer.stillValid}（本树官方名）**每 tick** 被服务端问一遍"界面还能不能
 * 留着"，末条是 {@code player.canInteractWithEntity(maid, 4.0)}——只有 **4 格**，人一站远下一 tick 就
 * {@code closeContainer()}，客户端看到的就是一闪而过。
 * 【做法】在 {@code stillValid} 的**每个 return 出口**看一眼：TLM 说 false 时，若这次界面是**我们替玩家开
 * 的**（{@link com.maidsmart.schedule.RemoteMaidGui} 登记过）且**除距离外判据全成立**（活着/没睡着/仍属该玩
 * 家），翻成 true。**不用 @Redirect 改那条调用**——那是按被调方方法名定位，而 TLM 这一版在 1.20.1 那边调的偏
 * 偏是**官方名** {@code Player.canReach}（同 jar 别处却调 SRG {@code m_19950_}，混用一改 refmap 就错位、错位
 * 即崩）；{@code @At("RETURN")} 只认"方法出口"，与被调方叫什么无关。
 * 【不做什么】玩家自己右键开的界面照旧吃 4 格限制；**死 / 睡 / 换主人照样关**（这三条在距离之前，这里自己再问
 * 一遍，三条都过才翻 true）。
 * 【映射】本树写官方名（{@code stillValid}/{@code removed}/{@code isAlive}/{@code isSleeping}/{@code isOwnedBy}）。
 */
@Mixin(AbstractMaidContainer.class)
public abstract class MaidContainerRemoteOpenMixin {

    /** {@code stillValid}：只把"距离"这一条翻过来 */
    @Inject(method = "stillValid(Lnet/minecraft/world/entity/player/Player;)Z",
            at = @At("RETURN"), cancellable = true)
    private void maidsmart$allowRemoteOpen(Player player, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (cir.getReturnValueZ()) {
                return; // TLM 自己说"行" → 一个字不改
            }
            EntityMaid maid = ((AbstractMaidContainer) (Object) this).getMaid();
            if (maid == null || player == null) {
                return; // 她不在（客户端/容器已残）→ 原样返回 false，让 TLM 关掉它
            }
            if (!com.maidsmart.schedule.RemoteMaidGui.isActive(player, maid)) {
                return; // 玩家自己右键开的界面：距离照旧由 TLM 管
            }
            if (!maid.isAlive() || maid.isSleeping() || !maid.isOwnedBy(player)) {
                return; // 死了 / 正在睡 / 不再属于他：距离之外的条件照旧把关
            }
            cir.setReturnValue(true);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidContainerRemoteOpenMixin", ignored);
            // 这一处出任何问题都退回 TLM 的结论（就是"按 4 格算"），绝不把界面留成不该留的样子
        }
    }

    /** {@code removed}：界面一关就把这次远程授权收回 */
    @Inject(method = "removed(Lnet/minecraft/world/entity/player/Player;)V", at = @At("HEAD"))
    private void maidsmart$endRemoteOpen(Player player, CallbackInfo ci) {
        try {
            EntityMaid maid = ((AbstractMaidContainer) (Object) this).getMaid();
            if (maid != null && player != null) {
                com.maidsmart.schedule.RemoteMaidGui.disable(player, maid);
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidContainerRemoteOpenMixin", ignored);
        }
    }
}
