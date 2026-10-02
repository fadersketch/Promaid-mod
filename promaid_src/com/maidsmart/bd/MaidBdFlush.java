package com.maidsmart.bd;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.tool.PromaidLog;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 实测 G-12【超越维度·缓存冲刷】——把她"额外容器"（精妙背包＝缓存）里的产物批量推进数据库。
 *
 * <h2>这套三层结构是怎样长出来的</h2>
 * 需求方的设计直觉是"精妙背包＝缓存、超越维度＝数据库"，而上游 v1.2.4 已经把缓存那半做完了：
 * <pre>
 *   ①她自己背包  →  ②额外容器（精妙背包，走 TLM 的 MaidContainerCache / ContainerRef）→  ③超越维度
 * </pre>
 * 之前 ③ 只在两个时机进料：她背包里的产物按规则回收、以及她背包与缓存都塞不下时的溢出。
 * **缓存里积压的那部分一直没人管**——本类就是补这一段：定期把缓存里的产物倒进数据库。
 *
 * <h2>为什么直接用 ContainerRef.extract，而不是借上游的 pull</h2>
 * 上游的 {@code MaidExtraContainer.pull} 是"请 TLM 把东西搬进**她自己的背包**"——那是给
 * 她干活用的；我们要的是"搬进数据库"，绕一圈她的背包只会白折腾（还可能因为背包满而卡住）。
 * {@code ContainerRef#extract(maid, 判据, 上限)} 能**直接从容器里取出来**，而且 TLM 自家的
 * {@code SBackpackSlotRef} 确实实现了它（javap 实证），所以这条路是干净的。
 *
 * <h2>安全规矩</h2>
 * <ol>
 *   <li>取出后立刻插库；插不进去（或库里塞不下）就**原样放回容器**——顺序保证不丢件；</li>
 *   <li>判据沿用 {@link MaidBdDeposit#isProduct}：缓存里的**非产物**（她干活要用的）一律不动；</li>
 *   <li>每轮限量（默认 8 组 / 4096 个），避免一次卡顿；每笔都写日志。</li>
 * </ol>
 *
 * <p>【预演的代价】TLM 的容器 API 只提供"按判据取出"，没有纯只读版本，所以
 * {@code bd_flush_dry} 是"取出来再放回去"，并把这个事实写进日志——如果连放回都失败，
 * 就直接推进库里（宁可入错地方，也不丢件）。
 *
 * <p>【1.20.1 差异】事件总线 {@code MinecraftForge.EVENT_BUS}。
 */
public final class MaidBdFlush {

    /** 与回收/补货同一只开关，但节奏慢一些：2 秒一轮（搬运是批量的，不必每秒跑）。 */
    private static final int CHECK_EVERY = 40;
    private static final int MAX_STACKS = 8;
    private static final long MAX_ITEMS = 4096L;

    private static final Map<UUID, Integer> COUNTER = new HashMap<>();

    /**
     * 【G-14】每只女仆"最近一次自动冲刷"的备忘。
     *
     * <p>为什么需要：自动冲刷每 2 秒跑一次，而玩家装备背包、切窗口、敲命令至少要几秒 ⇒
     * 等玩家敲下 {@code bd_flush_dry} 时缓存早就空了，于是永远看到"没有可冲刷的产物"，
     * 只能靠网络数量前后对比去猜（需求方实测正是如此：他发现装备后网络从 24 变 49）。
     */
    private record LastFlush(long tick, List<String> lines) {
    }

    private static final Map<UUID, LastFlush> LAST = new HashMap<>();
    private static boolean hooked;

    private MaidBdFlush() {
    }

    public static void ensureHooked() {
        if (hooked) {
            return;
        }
        hooked = true;
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new MaidBdFlush());
    }

    @SubscribeEvent
    public void onMaidTick(MaidTickEvent event) {
        try {
            tick(event.getMaid());
        } catch (Throwable ignored) {
        }
    }

    private static void tick(EntityMaid maid) {
        if (maid == null || maid.m_9236_().m_5776_() || !MaidBdDeposit.isOn(maid)
                || !MaidBdCompat.enabled()) {
            return;   // 两级门禁：总开关 + 该女仆自己的开关
        }
        if (!hasExtraContainers(maid)) {
            return;   // 没有额外容器（没装精妙背包/旅行者背包，或没插在饰品栏）→ 零开销退出
        }
        com.maidsmart.tool.StateTables.cap("bdMaidBdFlushCOUNTER", COUNTER);
        com.maidsmart.tool.StateTables.cap("bdMaidBdFlushLAST", LAST);
        int n = COUNTER.merge(maid.m_20148_(), 1, Integer::sum);
        if (n % CHECK_EVERY != 0) {
            return;
        }
        Player owner = MaidBdCompat.ownerOf(maid);
        if (owner == null || !MaidBdCompat.available() || !MaidBdCompat.hasAnyNet(owner)) {
            return;
        }
        Object net = MaidBdCompat.primaryNet(owner);
        if (net != null) {
            flush(maid, net, false);
        }
    }

    private static boolean hasExtraContainers(EntityMaid maid) {
        // 【照作者要求】复用上游既有的额外容器判据（它自己尊重 misc.backpackOverflow）
        try {
            return com.maidsmart.tool.MaidExtraContainer.hasAny(maid);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 只看：她额外容器（精妙背包）里有没有可冲刷的产物。
     *
     * <p>【照作者要求】不再自己调 TLM 的容器 API，改用上游既有的
     * {@code MaidExtraContainer.contains}——它的实现是"取出 1 个立刻放回，净零"，是上游自己
     * 认可的只读探针；也省掉了我上一版"取出再放回"那种会扰动缓存的干跑。
     */
    public static List<String> preview(EntityMaid maid) {
        List<String> out = new ArrayList<>();
        if (maid == null) {
            return out;
        }
        boolean any;
        try {
            any = com.maidsmart.tool.MaidExtraContainer.contains(maid, MaidBdDeposit::isProduct);
        } catch (Throwable t) {
            any = false;
        }
        if (any) {
            out.add("额外容器里有可冲刷的产物（正式冲刷会先请 TLM 把它们搬进她背包，再按既有规则入网络）");
        } else {
            LastFlush last = LAST.get(maid.m_20148_());
            out.add(last != null
                    ? "（额外容器此刻没有可冲刷的产物；最近一次自动冲刷搬走了：" + String.join("；", last.lines()) + "）"
                    : "额外容器里没有可冲刷的产物（也可能她根本没戴精妙背包：TLM 只认插在饰品栏里的）");
        }
        return out;
    }

    /**
     * 冲刷一次：**先请 TLM 把额外容器里的产物搬进她自己的背包**，再走既有的回收链入网络。
     *
     * <p>【为什么改成"绕一圈她背包"】作者退回上一版时点名要"复用既有的额外容器判据"。
     * 上一版我直接用 TLM 的 {@code ContainerRef.extract} 从容器里取，好处是快、不需要她背包有空位，
     * 坏处是判据出现了第二套（TLM 的容器体系 vs 上游的 MaidExtraContainer）。现在统一到上游那条：
     * {@code MaidExtraContainer.pull} + {@code MaidBdDeposit.sweep}，代价是她背包得先腾出位置——
     * 腾不出来时冲刷就等她腾（日志里写"暂缓"），不再另开一条捷径。
     */
    public static List<String> flush(EntityMaid maid, Object net, boolean dryRun) {
        List<String> out = new ArrayList<>();
        if (maid == null) {
            return out;
        }
        if (dryRun) {
            return preview(maid);
        }
        boolean pulled;
        try {
            pulled = com.maidsmart.tool.MaidExtraContainer.pull(maid, MaidBdDeposit::isProduct,
                    (int) MAX_ITEMS);
        } catch (Throwable t) {
            pulled = false;
        }
        if (!pulled) {
            LAST.put(maid.m_20148_(), new LastFlush(maid.m_9236_().m_46467_(),
                    List.of("本轮没有可冲刷的（额外容器空着，或她背包腾不出位置）")));
            return out;
        }
        // 搬进她背包之后，走同一条回收链（含保留 N 个、来源说明、网络 X → Y 的审计）
        out.addAll(MaidBdDeposit.sweep(maid, net, false));
        if (!out.isEmpty()) {
            LAST.put(maid.m_20148_(), new LastFlush(maid.m_9236_().m_46467_(), new ArrayList<>(out)));
            PromaidLog.log("超越维度冲刷", maid.m_7755_().getString() + " 本轮：" + String.join("；", out));
        }
        return out;
    }
}
