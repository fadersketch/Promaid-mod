package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 【R1134】排班表远程开的「女仆配置」里，点「饰品 / 背包 / 任务」那几个页签没反应。
 *
 * <p>根因（javap 实证）：页签按钮发的是 TLM 自己的 {@code OpenMaidGuiMessage(maidId, tabId)}，
 * 它的处理器里**另有一道 5 格距离闸**——{@code stillValid = 同营 && !睡 && 活 && player.distanceTo(maid) < 5.0}，
 * 不满足直接丢弃，**不发任何提示**。排班表那条路玩家站在几十格外，主界面是我们替他开的
 * （{@code RemoteMaidGui} 让容器 {@code stillValid} 放行），可这个**换页**请求照样被这道 5 格闸拦掉，
 * 玩家看到的就是"女仆配置能开，但饰品栏点不开"。
 *
 * <p>做法与 {@link MaidContainerRemoteOpenMixin} 同一口径：只在"这次界面确实是我们替玩家开的"
 * （{@link com.maidsmart.schedule.RemoteMaidGui#isActive}）时，把**距离**这一条翻过来；
 * 同营 / 没在睡 / 活着三条照旧自己再问一遍（不干脆 return true）。玩家自己右键开的界面、
 * 以及不是远程会话的任何一次换页，一个字节都不改。
 */
@Mixin(targets = "com.github.tartaricacid.touhoulittlemaid.network.message.OpenMaidGuiMessage")
public abstract class MaidGuiPacketRemoteMixin {

    /** {@code stillValid(Player, EntityMaid)}：只把"距离 < 5 格"这一条在远程会话里放行 */
    @Inject(method = "stillValid(Lnet/minecraft/world/entity/player/Player;"
            + "Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;)Z",
            at = @At("RETURN"), cancellable = true, remap = false)
    private static void maidsmart$allowRemoteTab(Player player, EntityMaid maid,
                                                 CallbackInfoReturnable<Boolean> cir) {
        try {
            if (cir.getReturnValueZ() || player == null || maid == null) {
                return; // TLM 自己说"行" → 一个字不改
            }
            if (!maid.m_6084_() || maid.m_5803_() || !maid.m_21830_(player)) {
                return; // 死了 / 正在睡 / 不再同营：距离之外的条件照旧把关
            }
            if (com.maidsmart.schedule.RemoteMaidGui.isActive(player, maid)) {
                cir.setReturnValue(true); // 远程界面里的换页：距离不拦
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidGuiPacketRemoteMixin", ignored);
        }
    }
}
