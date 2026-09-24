package com.maidsmart.mixin;

import net.minecraft.server.level.ServerPlayer;

/**
 * v1.3.5 实测六百六十：{@code ChunkMap$TrackedEntity} 上的「强制配对」桥（本模组内部接口）。
 *
 * ── 为什么需要一座桥 ──
 * 「客户端到底认不认得这个实体」的唯一开关，是原版 {@code ChunkMap.TrackedEntity} 里那个
 * {@code seenBy} 集合（forge 树 = SRG 字段 {@code f_140475_}，类型
 * {@code Set<ServerPlayerConnection>}）。原版往里加人的方法是
 * {@code ServerEntity.addPairing}（forge 树 = SRG {@code m_8541_}）——它把
 * 「spawn 包 + 元数据 + 属性」一次性打包发给这个玩家（字节码实证：内部先
 * {@code sendPairingData} 收集包、再 {@code ClientboundBundlePacket} 一起发，
 * 同一条连接、包序稳定）。
 *
 * 而 {@code TrackedEntity} 是**包私有内部类**（{@code ChunkMap$TrackedEntity}），
 * 我们的普通代码连它的类型都写不出来（编译期就没有这个名字），所以这里拿一个公开接口把它
 * 「借」出来：由 {@link ChunkMapTrackRemoteMixin} 把接口实现在目标类上，
 * {@code RemoteMaidGui.pump} 从 entityMap 里取到的值 cast 成这个接口就能直接用。
 *
 * 【为什么不用反射】走接口是**编译期**就核对过的——名字写错、签名变了，javac 直接不过；
 * 反射失败则是"运行时静默 no-op"，正是本项目最不想要的那种失败模式。
 */
public interface RemoteTrackBridge {

    /** 这个玩家此刻「已经同步着」她吗——读的就是原版那个 seenBy 集合本身 */
    boolean promaid$isPaired(ServerPlayer player);

    /**
     * 强制把这一对（玩家, 女仆）配上：走的就是原版 {@code addPairing} 这条路（同一个方法，
     * 不是另抄一份），所以客户端拿到的数据与「玩家自己走近、原版自己配上」**完全一致**。
     *
     * @return true = 配上了（本来就在 seenBy 里也算，此时一个字都不发，不会重复发包）；
     *         false = 这次不该配：她死了/被移除了、玩家不在场、维度对不上，
     *         或者这次界面本来就不是我们替玩家开的（玩家自己右键那种）——三种都交回原版按
     *         距离处理，「走近才能开」的原版手感一点不动
     */
    boolean promaid$forcePair(ServerPlayer player);
}
