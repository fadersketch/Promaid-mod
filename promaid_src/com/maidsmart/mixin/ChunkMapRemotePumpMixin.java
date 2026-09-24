package com.maidsmart.mixin;

import com.maidsmart.schedule.RemoteMaidGui;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.3.5 实测六百六十：远程开界面的「同步泵」——每 tick 一次，把该同步给某个玩家的女仆补上，
 * 同步真落地了才替玩家把界面开出来。
 *
 * ── 为什么泵要挂在 ChunkMap 自己的 tick 上 ──
 * 两个原因，缺一不可：
 * <ol>
 *   <li><b>找得到那条 TrackedEntity</b>：强制同步要动的是 {@code entityMap} 里的
 *       {@code TrackedEntity}（字段 {@code f_140150_}，{@code Int2ObjectMap}，key = 实体网络 id），
 *       而这个表是 <b>ChunkMap 的私有字段</b>——只有混进 ChunkMap 的 mixin 能碰它。
 *       挂在 tick 的**尾巴**上，正好在原版自己刚扫完一遍实体同步之后动手；</li>
 *   <li><b>包序有保证</b>：「客户端先认识她」和「客户端打开她的界面」是两条包，
 *       必须是前者先到，否则客户端按实体 id 找她找不着，打开的就是个残界面
 *       （字节码实证：{@code AbstractMaidContainer} 构造里 {@code level.getEntity(int)}
 *       + {@code cast EntityMaid}，「玩家背包那一片槽位」只在 {@code maid != null} 时才建）。
 *       在同一个方法里「先强制配对、再把界面开出来」，两条包走同一条连接、同一次 tick 内按序发出
 *       ——TCP 有序，客户端按序处理，不存在抢跑。若改成"包处理线程里先开、tick 里再补同步"，
 *       那就是明摆着的抢跑。</li>
 * </ol>
 * 【tick 归属实证】forge 树：{@code ServerChunkCache.m_201698_(BooleanSupplier,boolean)}
 * → {@code m_8490_()} → {@code ChunkMap.m_140421_()}；neo 树：{@code ChunkMap.tick()}
 * 由 {@code ServerChunkCache.tick(BooleanSupplier,boolean)} 直接调用。都是每 tick 一次。
 *
 * <p>开销：{@code RemoteMaidGui.pump} 第一行就是"没有登记就直接返回"，
 * 平时（没人点远程开界面）这里是零成本；真开着的时候也只有那几对要处理。
 *
 * 【映射】forge 树 {@code m_140421_ / f_140133_ / f_140150_}，neo 树
 * {@code tick / level / entityMap}——两版 jar 里 javap 逐个核对过。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapRemotePumpMixin {

    /** 本区块表所属的维度（远程开界面要在她所在的那一维度上做同步） */
    @Shadow
    @Final
    private ServerLevel f_140133_;

    /** 实体 → TrackedEntity 的表：本 mixin 存在的第一个理由就是它 */
    @Shadow
    @Final
    private Int2ObjectMap<Object> f_140150_;

    @Inject(method = "m_140421_", at = @At("TAIL"))
    private void promaid$pumpRemoteGui(CallbackInfo ci) {
        try {
            RemoteMaidGui.pump(f_140133_, f_140150_);
        } catch (Throwable ignored) {
            // 远程开界面是"锦上添花"的功能：这里出任何问题都不该影响真正在跑的区块 tick
        }
    }
}
