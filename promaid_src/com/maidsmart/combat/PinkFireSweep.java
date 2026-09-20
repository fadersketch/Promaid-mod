package com.maidsmart.combat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * v1.2.2 实测六百〇二【粉色火焰：保证会灭】。
 *
 * ── 反馈原文 ──
 * "粉色的火又不会熄灭了"（"又"= 实测五百九十八 修过一次同类问题）。
 *
 * ── 实测查到的真相（不是 tick 逻辑坏了）──
 * 反编译已部署的 jar 核对：{@link PinkFireBlock#m_213897_} 的字节码里
 * {@code setValue(AGE, age+1) → scheduleTick} 两条都在（五百九十八 那一修还在），
 * 而**在会 tick 的区块里它确实是 ~8 秒自灭**（专用服务器实测：一炸 106 格，10 秒后
 * 该区块 56 格全灭，只剩另一片"不 tick 的区块"里的 50 格原地不动、AGE 一直是 0）。
 * 再看玩家存档（自己写的 NBT 解析）：世界里**残留 70 格粉火**，AGE 全是 0/1，
 * 而它们每格都还挂着"下一次计划 tick"——也就是说 **火没灭的原因不是逻辑，
 * 而是它所在的区块在它老化之前就不再 tick 了**（远处战斗 / 玩家走开 / 区块卸载）：
 * 原版火的熄灭完全依赖方块 tick 链（{@code onPlace} 排第一拍、tick 里再排下一拍），
 * 区块一旦不 tick，这条链就冻住；冻住的区块被存进存档，之后再看就是"粉色的火永远不灭"。
 * 顺带核对：1.20.1 的区块 NBT 里确实有 {@code block_ticks}（计划 tick 会存盘、重载后会恢复），
 * 所以这不是"存盘丢了 tick"，而是"**活在没人 tick 的地方**"。
 *
 * ── 于是加两层兜底（tick 链保持不动，它依然是最主要的那条）──
 * ① {@link #track}：每一格被换成的粉火都登记一条"到期时间"（默认 8 秒，与手册口径一致），
 *    由服务端 tick（{@link #onServerTick}）在**不依赖方块 tick** 的前提下到期抹掉
 *    ——只要那一格所在的区块还加载着，就一定会灭；
 * ② {@link #onChunkLoad}：区块**加载时**扫一遍，把存档里带进来的粉火**收进兜底表**（只读坐标，
 *    真正的抹除由下一 tick 的 ① 做——原因见方法注释：在加载管线里改方块会把服务器卡死）——
 *    这一条既治"老版本时代残留的老火"（实测：玩家存档里那 70 格会在下次进区块时自动消失），
 *    也治"火在卸载期间冻住、回来又看到"的观感。
 *
 * 【为什么不干脆把 tick 链删掉、只留这一层】方块 tick 链是**原版贴图/音效/同步**都在用的
 * 常规路径（而且它不占我们的 tick 预算），只是不能保证；两层叠起来才是"正常路线 + 兜底"。
 *
 * 【开销】登记表只装"女仆炸出来的火"（一次爆炸几十~一百多格、寿命 8 秒），
 * 每 tick 遍历一遍做一个 gameTime 比较；区块加载只对"调色板里真有粉火的段落"逐格扫
 * （{@code maybeHas} 先用调色板判一次，没有粉火的分段零成本）。
 */
public final class PinkFireSweep {

    /** 兜底寿命（tick）：8 秒——与手册「粉火约 8 秒自灭」同一口径 */
    private static final int LIFETIME_TICKS = 160;

    /** 登记表上限（防泄漏）：超了就丢掉最老的一半 */
    private static final int MAX_TRACKED = 4096;

    /** 待兜底清除的粉火（服务端） */
    private static final List<Tracked> TRACKED = new ArrayList<>();

    /**
     * 登记表里"区块还没加载"能挂多久（tick）：过了就丢（存档里那一格会由区块加载清理重新收进来）。
     */
    private static final long KEEP_UNLOADED_TICKS = 1200L;

    private static final class Tracked {
        final ServerLevel level;
        final BlockPos pos;
        final long due;

        Tracked(ServerLevel level, BlockPos pos, long due) {
            this.level = level;
            this.pos = pos;
            this.due = due;
        }
    }

    /** 异常只报前几条（限频：出问题时不刷屏，但排查看得见——实测六百〇二教训：静默 catch 会让"没生效"无从查起） */
    private static int errLogged = 0;

    private static void logErr(String where, Throwable t) {
        if (errLogged >= 5) {
            return;
        }
        errLogged++;
        com.maidsmart.tool.PromaidLog.log("粉色火焰", "兜底异常（" + where + "）：" + t);
    }

    private PinkFireSweep() {
    }

    /**
     * 登记一格刚被换成粉色的火（由 {@code BaseFireBlockPinkMixin} 在换成功那一刻调用），
     * 或由区块加载清理把"存档里带进来的粉火"重新收进来（那时 due 就是"马上"）。
     *
     * 只在**服务端**登记：客户端那一份由服务端同步的方块更新负责，客户端不需要自己扫
     * （而且客户端没有"服务端 tick"可挂）。
     */
    public static void track(net.minecraft.world.level.BlockGetter level, BlockPos pos) {
        track(level, pos, LIFETIME_TICKS);
    }

    private static void track(net.minecraft.world.level.BlockGetter level, BlockPos pos, int delay) {
        try {
            if (pos == null || !(level instanceof ServerLevel sl)) {
                return;
            }
            if (TRACKED.size() >= MAX_TRACKED) {
                TRACKED.subList(0, TRACKED.size() / 2).clear();
            }
            // BlockPos 本身不可变（可变的是 MutableBlockPos），直接存引用即可
            TRACKED.add(new Tracked(sl, pos, sl.m_46467_() + delay));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 到期兜底：条件满足就抹掉那一格粉火——**不管那一格有没有在 tick**。
     *
     * 区块没加载就先留着（每 tick 再看一次；挂太久才丢——那种情况由 {@link #onChunkLoad}
     * 在下次加载时重新收进来）。**必须在这里、而不是在区块加载事件里改方块**：
     * 加载事件是在区块加载管线内部触发的，那时 {@code level.removeBlock} 会把同一格所在
     * 区块的 {@code getChunk} 卡住等自己（实测：服务器 watchdog 报"单 tick 超过 60 秒"）。
     * 所以加载事件只**收**坐标（纯读），真正的写留给本方法在下一 tick 正常做。
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || TRACKED.isEmpty()) {
            return;
        }
        try {
            Block fire = PinkFireBlock.block();
            if (fire == null) {
                TRACKED.clear();
                return;
            }
            int removed = 0;
            for (Iterator<Tracked> it = TRACKED.iterator(); it.hasNext(); ) {
                Tracked t = it.next();
                long now = t.level.m_46467_();
                if (now < t.due) {
                    continue;
                }
                if (!t.level.m_46805_(t.pos)) {
                    if (now - t.due > KEEP_UNLOADED_TICKS) {
                        it.remove(); // 区块一直没回来：丢掉（下次加载会重新收）
                    }
                    continue; // 区块没加载：等它回来
                }
                it.remove();
                if (t.level.m_8055_(t.pos).m_60713_(fire)) {
                    t.level.m_7471_(t.pos, false);
                    removed++;
                }
            }
            if (removed > 0) {
                com.maidsmart.tool.PromaidLog.log("粉色火焰",
                        "兜底熄灭：这一批有 " + removed + " 格粉火过了寿命还没灭（所在区块没在 tick）→ 直接抹掉");
            }
        } catch (Throwable t) {
            logErr("tick", t);
        }
    }

    /**
     * 区块加载时把粉火**收进兜底表**（下一个服务端 tick 抹掉）：**粉火是"那一炸的几秒特效"，
     * 不该被存进世界**。
     *
     * 这一条同时是"老版本残留"的自愈入口——实测玩家存档里那 70 格会在区块下次加载时消失。
     * 服务端独有（{@code instanceof ServerLevel}）。
     *
     * 【这里只读不写，是实测换来的】第一版直接在这里 {@code level.removeBlock}，结果
     * {@code forceload} 一执行服务器就死锁：加载事件在区块加载管线里，那时同一格所在区块还没
     * 发布，removeBlock 内部的 {@code getChunk} 会阻塞等自己（watchdog：单 tick 60 秒）。
     * 现在改成"只扫坐标 + 交给 {@link #onServerTick}"，写方块发生在正常的 tick 上下文里。
     */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        try {
            LevelAccessor accessor = event.getLevel();
            if (!(accessor instanceof ServerLevel level)) {
                return; // 客户端不扫
            }
            ChunkAccess chunk = event.getChunk();
            if (chunk == null) {
                return;
            }
            Block fire = PinkFireBlock.block();
            if (fire == null) {
                return;
            }
            LevelChunkSection[] sections = chunk.m_7103_();
            if (sections == null || sections.length == 0) {
                return;
            }
            ChunkPos cp = chunk.m_7697_();
            int minY = chunk.m_141937_();
            int found = 0;
            for (int si = 0; si < sections.length; si++) {
                LevelChunkSection sec = sections[si];
                if (sec == null || sec.m_188008_() || !sec.m_63002_(st -> st.m_60713_(fire))) {
                    continue; // 空气段 / 调色板里没粉火：零成本跳过
                }
                int yBase = minY + (si << 4);
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            if (!sec.m_62982_(x, y, z).m_60713_(fire)) {
                                continue;
                            }
                            track(level, new BlockPos((cp.f_45578_ << 4) + x, yBase + y,
                                    (cp.f_45579_ << 4) + z), 0);
                            found++;
                        }
                    }
                }
            }
            if (found > 0) {
                com.maidsmart.tool.PromaidLog.log("粉色火焰",
                        "残留粉火清理：区块(" + cp.f_45578_ + "," + cp.f_45579_ + ") 收到 " + found
                                + " 格存档粉火 → 下一 tick 抹掉（粉火不进世界）");
            }
        } catch (Throwable t) {
            logErr("区块加载", t);
        }
    }
}
