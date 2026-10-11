package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.follow.MaidTeleportRebuild;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 重建实体。（本文件是 forge / 1.20.1 树；neo 版见同名文件，官方名与 SRG 各写各的。）
 * <p>完整机制、以及为什么"当帧重建"之外还必须再排一次"延迟补包"，全写在
 * {@link MaidTeleportRebuild} 的类注释里（照搬上游开源项目 2415564388/maidsync 的现场实测，MIT）。
 * <h3>为什么挂在这里</h3>
 * {@code ServerEntity.m_8533_()}（= sendChanges）是全原版<b>唯一</b>的位置包出口（唯一调用点是
 * {@code ChunkMap.m_140421_}（= tick），条件是 {@code entityMoved || inEntityTickingRange}——javap 实证）。
 * 挂在这里意味着无论女仆是被谁、用什么方法、从哪条代码路径传走的，都会经过这里 ——
 * 不需要去枚举 {@code teleportTo} / {@code moveTo} / {@code setPos} / 跨维度流程等等入口。
 * <h3>两条触发路径</h3>
 * <ol>
 *   <li>{@code EntityMoveToMarkMixin} 已经标记过"这个实体被远距离挪动过"——直接重建；</li>
 *   <li>兜底：位置基线出现大跳变。这条不依赖任何调用点，是真正的完备网。</li>
 * </ol> <p>{@code ci.cancel()} 是关键：拦住本 tick 的后续处理，杜绝那条会引发客户端 {@code lerpTo} 的传送包漏出去。跳过的 {@code m_8543_}（= sendDirtyEntityData）已由 {@code m_8541_ → m_289200_}（= addPairing → sendPairingData）补上（它把 spawn + 元数据 + 属性一起打包发）。 【映射】本树 {@code m_8533_ / f_8510_ / f_8509_ / f_214995_ / m_6907_ / m_213870_ / m_238031_ / m_238033_ / m_46467_ / m_20681_}；neo 树写官方名 （{@code sendChanges / entity / level / positionCodec / players() / trackingPosition() / delta / setBase / getGameTime() / clientTrackingRange()}）——两版 jar 里逐个 javap 核对过。
 */
@Mixin(ServerEntity.class)
public abstract class ServerEntityRebuildMixin {

    @Shadow
    @Final
    private Entity f_8510_;

    @Shadow
    @Final
    private ServerLevel f_8509_;

    @Shadow
    @Final
    private VecDeltaCodec f_214995_;

    @Inject(method = "m_8533_", at = @At("HEAD"), cancellable = true)
    private void promaid$rebuildOnBigJump(CallbackInfo ci) {
        try {
            Entity self = this.f_8510_;
            if (self == null || self.m_213877_()) {
                return;
            }
            if (!com.maidsmart.config.MaidSmartConfig.MISC_MAID_TELEPORT_REBUILD.get()) {
                return;
            }
            if (!(self instanceof EntityMaid maid) || !maid.m_6084_()) {
                return;
            }
            // Sable 物理子关卡：见 MaidTeleportRebuild.inSubLevel。标记**刻意不消费**——
            // 它是"她被真正传送过"的凭证，要留着她真正离开子关卡后的第一次 sendChanges 再用。
            if (MaidTeleportRebuild.inSubLevel(self)) {
                return;
            }
            Vec3 current = self.m_213870_();
            double delta = Math.sqrt(this.f_214995_.m_238031_(current).m_82556_());
            boolean marked = MaidTeleportRebuild.consume(self.m_19879_());
            if (!marked && delta <= MaidTeleportRebuild.REBUILD_DISTANCE) {
                return;
            }
            // 冷却：刚修过就不再修，挡"判定连续成立 → 每 tick 重建一次"的风暴。
            // 跳过时【刻意不 cancel】——cancel 会吞掉本 tick 的旋转包与脏数据同步，
            // 只有确实要重建、用重建换掉它们时才划算。标记【原样还回去】。
            if (MaidTeleportRebuild.coolingDown(maid, this.f_8509_.m_46467_())) {
                if (marked) {
                    MaidTeleportRebuild.restoreMark(self.m_19879_());
                }
                return;
            }
            ServerEntity serverEntity = (ServerEntity) (Object) this;
            int rebuilt = 0;
            // 只给「服务端认为现在有这只实体」的玩家重建——removePairing+addPairing 发的是裸包，
            // 不会登记追踪关系；给没在追踪她的玩家发，只会在他客户端上造出一个服务端再也不管的幽灵实体。
            for (ServerPlayer player : this.f_8509_.m_6907_()) {
                if (!MaidTeleportRebuild.shouldPairTo(self, player)) {
                    continue;
                }
                try {
                    serverEntity.m_8534_(player);
                    serverEntity.m_8541_(player);
                    rebuilt++;
                } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ServerEntityRebuildMixin", ignored);
                }
            }
            // 基线对齐到当前位置：否则下一 tick 会判定"还是大跳变"而反复重建
            this.f_214995_.m_238033_(current);
            MaidTeleportRebuild.markRebuilt(maid, this.f_8509_.m_46467_());
            if (rebuilt > 0) {
                com.maidsmart.tool.PromaidLog.log("重同步", com.maidsmart.tool.PromaidLog.nameOf(maid)
                        + " 检测到大跳变（位移基线 " + String.format(java.util.Locale.ROOT, "%.0f", delta)
                        + " 格）→ 已当场重建客户端实体（拦下传送包，防「客户端插值冻在未加载区块」）："
                        + rebuilt + " 个客户端");
            }
            // 关键补充：光靠上面这次"当帧重建"救不了"落点在客户端未加载区块"的情况——
            // 那一刻她的服务端位置可能还是旧的。再排一次延迟补包，等传送真正生效后重建，
            // 生成包才会带玩家身边的新坐标（那个区块客户端是加载的）。
            MaidTeleportRebuild.schedule(maid);
            // 拦住本 tick 的后续处理——绝不让那个会引发 lerpTo 的传送包发出去
            ci.cancel();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ServerEntityRebuildMixin", ignored);
            // 任何意外都退回"按原版发"——宁可偶尔漏修一次，也不能因为这里坏了把实体同步整个带崩
        }
    }
}
