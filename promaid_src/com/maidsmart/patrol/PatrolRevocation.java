package com.maidsmart.patrol;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;

/**
 * 删掉之后，女仆脑中那一份副本必须跟着作废，她回到默认状态（扫帚=守家盘旋 / 直升机=悬停 /
 * 陆地载具=跟主人）。与 1.21.1 树 {@code promaid_src_neo} 里的同名类逐条同源，只差 SRG 名字。
 * <h2>问题出在哪（本类为什么必须存在）</h2>
 * 巡逻航迹是**两份**：物品（航图里的书）与女仆（{@code persistentData} 里的深拷）。
 * 绑定那一刻是**快照**语义——，所以两份之后各走各的。
 * 于是 {@code BookOpPacket} 的 {@code delete} 只动了物品那一份：航图上那条轨道没了，
 * 女仆身上那份深拷**一个字节都没动**，她照旧沿那条"已经不存在"的轨道一圈圈飞
 * （实测七百九十八的的根因。
 * <h2>为什么不能只在删除那一瞬间扫一遍已加载的女仆</h2>
 * 她多半**不在已加载区块里**（在跑巡逻的往往离玩家很远，甚至跨维度）。删除那一刻扫不到她，
 * 等她的区块下次加载时，身上那份深拷还在，就又会自己飞起来。所以撤销这件事必须**落盘**：
 * 删除时记一笔 id，之后她在任何时刻被读到时都判得出"这条已经删了"——
 * 判据放在 {@link PatrolChartData#readMaid} 这一个收口处，运行时链路（{@link PatrolFlight}） 与界面名单一并受益。 <h2>为什么按 id 而不是按"书名"</h2> id 是创建轨道时生成的 48 位短随机串（见 {@link PatrolRoute#newId}），一条轨道一个， 稳定不变（改名/换选中都不影响）。同一条轨道的 id 在物品与女仆两侧一字不差， 所以"删了 id=X 的轨道"这件事能精确地落到所有绑着 X 的女仆身上。 <h2>存哪儿 / 会不会膨胀</h2> 存主世界（{@code data/maid_smart_patrol_revoked.dat}），与 {@code BuildArchive} 同款 SavedData。 序列化照 {@code BuildArchive} 的先例走**字符串**而不是 ListTag（那边注释写明：避免 ListTag.add 的 SRG 不确定性）。一个 id 约 12 字节、删一条轨道才记一笔，且删除是玩家的明确 动作——不做上限裁剪，宁可账本长一点，也不能让一条删掉的轨道在很久以后又"复活"。
 */
public final class PatrolRevocation extends SavedData {

    private static final String DATA_NAME = "maid_smart_patrol_revoked";
    private static final String KEY = "revoked";
    /** 分隔符（与 BuildArchive 同款：NBT 字符串放控制字符没问题） */
    private static final String SEP = "\u0001";

    private final Set<String> revoked = new HashSet<>();

    public PatrolRevocation() {
    }

    private PatrolRevocation(CompoundTag tag) {
        String s = tag.m_128461_(KEY);
        if (s != null && !s.isEmpty()) {
            for (String id : s.split(SEP, -1)) {
                if (!id.isEmpty()) {
                    this.revoked.add(id);
                }
            }
        }
    }

    /**
     * 【为什么要缓存这些】{@link #get} 会被 {@link PatrolChartData#readMaid} 每拍每只女仆问
     * 一次（撤销判据的唯一收口）——把两个方法引用做成常量，省掉每次调用新建 lambda。
     *
     * <p>【为什么不顺手把账本本身也缓存】那样就得按服务端实例做键，于是静态字段会一直
     * 攥着上一个世界的 SavedData（单人换存档 = 一处小泄漏）。账本查表本来就是 O(1)，
     * 不值得为这点开销引入跨世界的失效与泄漏风险，所以只缓存工厂。
     */
    private static final java.util.function.Supplier<PatrolRevocation> SUPPLIER = PatrolRevocation::new;
    /** 1.20.1 的 loader 是 {@code Function<CompoundTag, T>}（没有 registries provider，与 FarmlandMarkStore 同款） */
    private static final java.util.function.Function<CompoundTag, PatrolRevocation> LOADER =
            PatrolRevocation::new;

    /** 取全服账本（主世界那一份；拿不到服务端 → null） */
    public static PatrolRevocation get(MinecraftServer server) {
        try {
            ServerLevel ow = server == null ? null : server.m_129783_();
            if (ow == null) {
                return null;
            }
            // 1.20.1 的参数顺序是 (loader, factory, name)（见 FarmlandMarkStore），别与 1.21.1 弄反
            return ow.m_8895_().m_164861_(LOADER, SUPPLIER, DATA_NAME);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PatrolRevocation", ignored);
            return null;
        }
    }

    /* ==================== 对外只读 ==================== */

    /** 这条轨道 id 是否已被删除（没有服务端/空 id → false，宁可放行也不误伤） */
    public static boolean revoked(MinecraftServer server, String routeId) {
        if (routeId == null || routeId.isEmpty()) {
            return false;
        }
        try {
            PatrolRevocation s = get(server);
            return s != null && s.revoked.contains(routeId);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PatrolRevocation", ignored);
            return false;
        }
    }

    /**
     * 这只女仆身上那份绑定是不是已经作废了（轨道被删）。客户端恒为 false——
     * 撤销是服务端权威的事，客户端那份深拷只用于显示，不驱动移动。
     */
    public static boolean revokedFor(EntityMaid maid, String routeId) {
        try {
            if (maid == null || maid.m_9236_().m_5776_()) {
                return false;
            }
            return revoked(maid.m_9236_().m_7654_(), routeId);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PatrolRevocation", ignored);
            return false;
        }
    }

    /* ==================== 记一笔 + 顺手解绑已加载的女仆 ==================== */

    /**
     * 记下"id 这条轨道被删了"，并**立刻**把当前已加载的女仆里绑着它的一律解绑（含运行时缓存）。
     *
     * <p>返回当场解绑了几只。没扫到的（区块未加载）不着急——她们下次被读到时会走
     * {@link PatrolChartData#readMaid} 的同一个判据自行作废，一个都不会漏。
     */
    public static int revokeAndUnbind(MinecraftServer server, String routeId) {
        if (routeId == null || routeId.isEmpty()) {
            return 0;
        }
        try {
            PatrolRevocation s = get(server);
            if (s != null && s.revoked.add(routeId)) {
                s.m_77762_();
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PatrolRevocation", ignored);
        }
        return unbindLoaded(server, routeId);
    }

    /** 遍历全服**已加载**的女仆，把绑着这条轨道的就地解绑（走 EntitySnapshot，见其类注释） */
    private static int unbindLoaded(MinecraftServer server, String routeId) {
        int n = 0;
        try {
            if (server == null) {
                return 0;
            }
            for (ServerLevel lvl : server.m_129785_()) {
                for (Entity e : com.maidsmart.tool.MaidRoster.inAsEntities(lvl)) {
                    if (!(e instanceof EntityMaid maid) || !maid.m_6084_()) {
                        continue;
                    }
                    PatrolRoute bound = PatrolChartData.readMaidRaw(maid);
                    if (bound == null || !routeId.equals(bound.id())) {
                        continue;
                    }
                    PatrolChartData.unbind(maid);
                    PatrolFlight.forget(maid);
                    n++;
                    com.maidsmart.tool.PromaidLog.log("扫帚巡逻",
                            com.maidsmart.tool.PromaidLog.nameOf(maid)
                                    + " 绑定的轨道「" + bound.displayName()
                                    + "」已被玩家删除 → 就地解绑，她回到默认状态");
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PatrolRevocation", ignored);
        }
        return n;
    }

    /* ==================== NBT ==================== */

    @Override
    public CompoundTag m_7176_(CompoundTag tag) {
        StringBuilder sb = new StringBuilder();
        for (String id : revoked) {
            if (sb.length() > 0) {
                sb.append(SEP);
            }
            sb.append(id);
        }
        tag.m_128359_(KEY, sb.toString());
        return tag;
    }
}
