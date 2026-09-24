package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.schedule.RemoteMaidGui;
import com.maidsmart.schedule.RemoteTrackBridge;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * v1.3.5 实测六百六十「几百格外也真的能开界面」——把「客户端看得见她」这件事，
 * 对**我们替他开界面的那一对**（玩家, 女仆）做一次强制同步。
 * （本文件是 neo / 1.21.1 树；forge 版见同名文件，差别只在方法名与那道额外的 chunk gate。）
 *
 * ── 玩家问的那句：「不是有过一个女仆区块强制加载吗？难道解决不了这样的问题吗？」 ──
 * 区块加载**解决不了这一条**，因为它们是两条完全独立的管线：
 * <ul>
 *   <li><b>区块票据</b>（本模组的 {@code MaidChunkLoadManager}，票级 2 = 实体正常 ticking）
 *       管的是「她在服务端还在不在、还动不动」——她在，AI 照跑，跨维度跟随扫得到她。
 *       票据里**没有任何一个字**跟"客户端认不认得她"有关。</li>
 *   <li><b>实体同步</b>管的是「客户端知不知道有这么个实体」，判据在
 *       {@code ChunkMap$TrackedEntity.updatePlayer} 里，本 mixin 下面细说，
 *       只有距离 + 可见性（1.21.1 还多一道 chunk gate），与区块票据完全无关。</li>
 * </ul>
 *
 * ── 原版那道门到底长什么样（javap 实证，1.21.1 neo 树） ──
 * <pre>
 *   public void updatePlayer(ServerPlayer player) {
 *       if (player == this.entity) return;
 *       Vec3 d = player.position().subtract(entity.position());
 *       int view = this$0.getPlayerViewDistance(player);                    // ← 1.21.1 是【按玩家】的视距
 *       double r = Math.min(getEffectiveRange(), view * 16);
 *       boolean flag = (d.x*d.x + d.z*d.z) <= r*r                           // ← 只算水平！不算 Y
 *                      &amp;&amp; entity.broadcastToPlayer(player)
 *                      &amp;&amp; this$0.isChunkTracked(player, entity.chunkPosition().x, entity.chunkPosition().z);
 *       if (flag)  { if (seenBy.add(player.connection)) serverEntity.addPairing(player); }
 *       else       { if (seenBy.remove(player.connection)) serverEntity.removePairing(player); }
 *   }
 * </pre>
 * 其中 {@code getEffectiveRange()} 的起点是 {@code ChunkMap.addEntity} 里写进去的那个数：
 * {@code entity.getType().clientTrackingRange() * 16}——也就是 <b>区块数 × 16 = 方块数</b>
 * （女仆声明的是 10 区块 → 160 格）。于是「自然能同步到多远」= {@code min(160, 视距×16)}
 * ——玩家视距 8 区块时只有 128 格。
 *
 * <p>【1.21.1 比 1.20.1 多一道 {@code isChunkTracked}】客户端必须已经把她的区块收下来了才算数。
 * 本 mixin 在方法**入口**直接接管，这道额外的 gate 一并绕开——也就是本树里"客户端没有她的区块"
 * 也不影响同步（她照样会被 spawn 到客户端，只是那个区块本身不会因此被发过去；
 * 界面要的是"客户端认得这个实体"，{@code level.getEntity(int)} 与区块加载无关）。
 *
 * ── 这个 mixin 做的两件事 ──
 * <ol>
 *   <li><b>配</b>：把这一对塞进 {@code seenBy} 并调原版 {@code addPairing}——
 *       客户端由此拿到「她」这个实体（spawn + 元数据 + 属性，与走近时一模一样）；</li>
 *   <li><b>不许被拆</b>：原版还有两条路会把她从客户端拆掉，会话期间一并拦下：
 *       {@code updatePlayer}（玩家走远后这一条会 removePairing）与
 *       {@code removePlayer}（她的区块滑出玩家视野时原版直接拆）。两者都只在
 *       "这次界面是我们替玩家开的"前提下拦，拦的时候顺手把配对补上。</li>
 * </ol>
 *
 * ── 一个字都不碰的地方 ──
 * <ul>
 *   <li>没登记过的（玩家自己右键女仆）→ {@code promaid$forcePair} 直接返回 false，
 *       原版按距离算，4 格手感照旧；</li>
 *   <li>她死了 / 被魂符收走 → 也返回 false，交给原版正常拆（界面该关就关，
 *       不会留一个"她不在了、客户端还挂着一个幽灵"的尾巴）；</li>
 *   <li>{@code seenBy} 里已有的不重复发（{@code add} 的返回值就是"这次新加进去没有"），
 *       所以不会出现"同一个实体发两遍 spawn"；</li>
 *   <li>界面一关，{@code AbstractMaidContainer.removed} 那条注入就撤销登记
 *       （见 {@code MaidContainerRemoteOpenMixin}），下一个 tick 原版自己把她拆回原样
 *       ——不会有任何长期驻留的"强制同步"。</li>
 * </ul>
 *
 * 【映射】本树写官方名（{@code updatePlayer} / {@code removePlayer} / {@code addPairing} /
 * {@code serverEntity} / {@code entity} / {@code seenBy} / {@code connection}），
 * forge 树写 SRG——两版 jar 里逐个 javap 核对过。
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class ChunkMapTrackRemoteMixin implements RemoteTrackBridge {

    /** {@code ServerEntity}：真正发配对数据的那一头（addPairing / removePairing 都在这儿） */
    @Shadow
    @Final
    private ServerEntity serverEntity;

    /** 正在被跟踪的实体（我们要认的就是"她是不是女仆"） */
    @Shadow
    @Final
    private Entity entity;

    /** 已经同步给哪些连接——原版自己的集合，我们只是往里看一眼 / 塞一个 */
    @Shadow
    @Final
    private Set<ServerPlayerConnection> seenBy;

    @Override
    public boolean promaid$isPaired(ServerPlayer player) {
        try {
            return player != null && seenBy.contains(player.connection);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public boolean promaid$forcePair(ServerPlayer player) {
        try {
            if (!promaid$mayForce(player)) {
                return false;
            }
            if (seenBy.add(player.connection)) {
                // 原版同一个方法：spawn + 元数据 + 属性 一起打包发出去
                serverEntity.addPairing(player);
            }
            return true;
        } catch (Throwable ignored) {
            // 任何意外都退回"按原版算"——远一点开不出来，但绝不会因为这里坏了把别的东西带崩
            return false;
        }
    }

    /**
     * 这次该不该强制配：她是女仆 + 这一对开着我们替玩家开的远程界面 + 双方都还在场、同一维度。
     *
     * <p>她已死亡/已移除时**故意**返回 false：那正是原版该把她从客户端拆掉的时刻
     * （{@code ChunkMap.removeEntity} 会调 {@code removePlayer}），拦下来只会留一个幽灵。
     * 界面那边同时也会因为 {@code isAlive()} 为假而自己关掉，两条路收敛到同一个结果。
     */
    private boolean promaid$mayForce(ServerPlayer player) {
        if (player == null || player.isRemoved()) {
            return false; // 玩家已移除（掉线/换维度途中）
        }
        if (!(entity instanceof EntityMaid maid)) {
            return false; // 不是女仆：本 mixin 与它无关
        }
        if (!maid.isAlive() || maid.isRemoved()) {
            return false; // 她死了/没了 → 交给原版拆干净
        }
        if (maid.level() != player.level()) {
            return false; // 维度对不上（跨维度开界面在 SchedulePacketsMaid 那一步就被拒了）
        }
        return RemoteMaidGui.isActive(player, maid);
    }

    /** {@code updatePlayer}：原版在这里按距离决定"配上/拆掉"——会话期间改由我们说了算 */
    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
    private void promaid$keepRemotePair(ServerPlayer player, CallbackInfo ci) {
        if (promaid$forcePair(player)) {
            ci.cancel();
        }
    }

    /** {@code removePlayer}：她的区块滑出玩家视野时原版直接拆——会话期间同样揽下来 */
    @Inject(method = "removePlayer", at = @At("HEAD"), cancellable = true)
    private void promaid$keepRemotePairOnUntrack(ServerPlayer player, CallbackInfo ci) {
        if (promaid$forcePair(player)) {
            ci.cancel();
        }
    }
}
