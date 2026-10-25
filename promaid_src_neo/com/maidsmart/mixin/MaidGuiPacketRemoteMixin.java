package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 【R1135】排班表远程开的「女仆配置」里，点「饰品 / 背包 / 任务」页签没反应。
 *
 * <p>根因（javap）：页签包 {@code OpenMaidGuiPackage} 的处理器另有一道 5 格距离闸
 * （{@code stillValid = 同营 && !睡 && 活 && distanceTo(maid) < 5.0}），不满足直接丢弃、无提示；
 * 排班表那条路玩家站在几十格外，换页请求就被它拦掉。
 *
 * <p>做法与 {@link MaidContainerRemoteOpenMixin} 同口径：只在"界面确实是我们替玩家开的"
 * （{@link com.maidsmart.schedule.RemoteMaidGui#isActive}）时放行距离；同营 / 未睡 / 存活照旧自查。
 * 玩家自己右键开的界面、以及非远程会话的换页，一个字节都不改。
 *
 * <p>【映射】1.21.1 目标类名是 {@code OpenMaidGuiPackage}（1.20.1 叫 {@code OpenMaidGuiMessage}）。
 */
@Mixin(targets = "com.github.tartaricacid.touhoulittlemaid.network.message.OpenMaidGuiPackage")
public abstract class MaidGuiPacketRemoteMixin {

    /** {@code stillValid(Player, EntityMaid)}：只把"距离 < 5 格"这一条在远程会话里放行 */
    @Inject(method = "stillValid(Lnet/minecraft/world/entity/player/Player;"
            + "Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;)Z",
            at = @At("RETURN"), cancellable = true)
    private static void maidsmart$allowRemoteTab(Player player, EntityMaid maid,
                                                 CallbackInfoReturnable<Boolean> cir) {
        try {
            if (cir.getReturnValueZ() || player == null || maid == null) {
                return; // TLM 自己说"行" → 一个字不改
            }
            if (!maid.isAlive() || maid.isSleeping() || !maid.isOwnedBy(player)) {
                return; // 死了 / 正在睡 / 不再同营：距离之外的条件照旧把关
            }
            if (com.maidsmart.schedule.RemoteMaidGui.isActive(player, maid)) {
                cir.setReturnValue(true); // 远程界面里的换页：距离不拦
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidGuiPacketRemoteMixin", ignored);
        }
    }
}
