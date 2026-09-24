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
 * v1.3.4 实测六百五十九「真正的远程开界面」：把 TLM 女仆容器里**那一条距离判据**放行。
 * （本树 = 1.21.1 NeoForge，方法名写官方名；1.20.1 那边同一个方法叫 SRG {@code m_6875_}。）
 *
 * ── 症状与根因 ──
 * 玩家在排班表里点「女仆配置」，人站在十几格外时：界面"开一下、立刻闪退"。
 * 根因是 TLM 的 {@code AbstractMaidContainer.stillValid}——它**每 tick** 被服务端问一遍
 * "这个界面还能不能留着"，字节码逐条读下来是：
 * <pre>
 *   maid != null → maid.isOwnedBy(player) → maid.isAlive() &amp;&amp; !maid.isSleeping()
 *                → player.canInteractWithEntity(maid, 4.0)   // 1.20.1 是 canReach(maid, 3.0)
 * </pre>
 * 最后那一条只有 **4 格**，于是人一站远，服务端下一 tick 就 {@code closeContainer()}，
 * 客户端看到的就是一闪而过。
 *
 * ── 这个 mixin 做的事情只有一条：把"距离"这一条放行 ──
 * 在 {@code stillValid} 的**每个 return 出口**上看一眼：TLM 说 false 时，如果这次界面是
 * **我们自己替玩家开的**（{@link com.maidsmart.schedule.RemoteMaidGui} 里登记过），
 * 并且**除距离以外的判据全部成立**（活着 / 没睡着 / 仍然属于这个玩家），就把结果翻成 true。
 * 除此之外一个字都不改：
 * <ul>
 *   <li><b>不用 @Redirect 去改那条调用</b>——那是"按被调方的方法名"定位，而 TLM 这一版
 *       在 1.20.1 那边调的偏偏是**官方名** {@code Player.canReach}（同一只 jar 里其它类调的
 *       却是 SRG 的 {@code m_19950_}，两种写法混着——mixin 的 refmap 一改就错位，
 *       错位的结果是**启动即崩**）。{@code @At("RETURN")} 只认"方法出口"这个位置，
 *       与被调方是谁、叫什么名字完全无关，所以换版本、改名字都不会挂在这儿。</li>
 *   <li><b>不碰"玩家自己右键开的界面"</b>：没登记过的（玩家右键）照旧吃 4 格限制——
 *       本模组的铁律是"只改该改的那一处"，原版手感一点不动。</li>
 *   <li><b>死 / 睡 / 换主人 照样关</b>：这几条是 {@code stillValid} 里距离**之前**的条件，
 *       这里自己再问一遍（返回值 false 时分不清是哪一条否掉的），三条都过才翻成 true。</li>
 * </ul>
 *
 * ── 为什么不干脆 return true ──
 * 那样"她死了界面还开着"、"她睡着了界面还开着"就全放过去了。这里的口径是：**只放行距离**。
 */
@Mixin(AbstractMaidContainer.class)
public abstract class MaidContainerRemoteOpenMixin {

    /** {@code stillValid}：只把"距离"这一条翻过来 */
    @Inject(method = "stillValid", at = @At("RETURN"), cancellable = true)
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
        } catch (Throwable ignored) {
            // 这一处出任何问题都退回 TLM 的结论（就是"按 4 格算"），绝不把界面留成不该留的样子
        }
    }

    /** {@code removed}：界面一关就把这次远程授权收回 */
    @Inject(method = "removed", at = @At("HEAD"))
    private void maidsmart$endRemoteOpen(Player player, CallbackInfo ci) {
        try {
            EntityMaid maid = ((AbstractMaidContainer) (Object) this).getMaid();
            if (maid != null && player != null) {
                com.maidsmart.schedule.RemoteMaidGui.disable(player, maid);
            }
        } catch (Throwable ignored) {
        }
    }
}
