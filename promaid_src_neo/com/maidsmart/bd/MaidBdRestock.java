package com.maidsmart.bd;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.tool.PromaidLog;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 实测 G-11【超越维度·自动补货 keepAtLeast】——她背包里"至少留 N 个"，少了从主人主网络取。
 *
 * <h2>它是"保留 N 个"的镜像</h2>
 * <pre>
 *   keepN       ：她背包里**最多**留 N 个，多的进库（背包 → 数据库）
 *   keepAtLeast ：她背包里**至少**留 N 个，少了从库里取（数据库 → 背包）
 * </pre>
 * 两条都在 {@code config/promaid_bd_rules.json} 里写，都共用她那一只开关
 * （{@code /maid_smart bd_deposit true}）与同一秒节奏。
 *
 * <h2>为什么这是"取出"里最安全的第一种形式</h2>
 * 取什么**由玩家明确写下来**（物品 id 或标签），不存在"取出任意物品"那种
 * "要取哪个、按什么匹配"的表达难题：按具体物品查是 O(1)；按标签取也是 O(1)，
 * 而且库里返回的 key 会告诉我们**实际给的是哪一种**——所以"用不同种类的石头搭路"
 * 或者"哪个 mod 的火把都行"这种需求才成立。
 *
 * <h2>三条安全规矩</h2>
 * <ol>
 *   <li><b>先取后放</b>：先从库里取出来，再往她背包里放；放不下的部分**还回网络**；
 *       连还都失败（网络恰好满了）就掉在她脚下（绝不凭空消失）。</li>
 *   <li><b>差额驱动</b>：只补到目标数为止（她背包里"总数"跨槽位合计），不会越补越多。</li>
 *   <li><b>有限速</b>：每轮最多补 4 组 / 1024 个，避免一次卡顿。</li>
 * </ol>
 */
public final class MaidBdRestock {

    /** 每轮每物品上限：4 组（64 × 4）。 */
    private static final long PER_RULE_CAP = 256L;
    /** 每轮总量上限。 */
    private static final long PER_TICK_CAP = 1024L;
    /** 与回收同一节奏：1 秒一次。 */
    private static final int CHECK_EVERY = 20;

    private static final Map<UUID, Integer> COUNTER = new HashMap<>();
    private static boolean hooked;

    private MaidBdRestock() {
    }

    public static void ensureHooked() {
        if (hooked) {
            return;
        }
        hooked = true;
        NeoForge.EVENT_BUS.register(new MaidBdRestock());
    }

    @SubscribeEvent
    public void onMaidTick(MaidTickEvent event) {
        try {
            tick(event.getMaid());
        } catch (Throwable ignored) {
        }
    }

    private static void tick(EntityMaid maid) {
        if (maid == null || maid.level().isClientSide() || !MaidBdDeposit.isOn(maid)
                || !MaidBdCompat.enabled()) {
            return;   // 两级门禁：总开关 + 该女仆自己的开关
        }
        if (MaidBdRules.atLeastRules().isEmpty()) {
            return;   // 默认空：玩家没写规则就一次都不动
        }
                com.maidsmart.tool.StateTables.cap("bdMaidBdRestockCOUNTER", COUNTER);
        int n = COUNTER.merge(maid.getUUID(), 1, Integer::sum);
        if (n % CHECK_EVERY != 0) {
            return;
        }
        Player owner = MaidBdCompat.ownerOf(maid);
        if (owner == null || !MaidBdCompat.available() || !MaidBdCompat.hasAnyNet(owner)) {
            return;
        }
        Object net = MaidBdCompat.primaryNet(owner);
        if (net == null) {
            return;
        }
        restock(maid, net, false);
    }

    /** 只看不补：报告每条规则"她有 / 目标 / 需要补多少"（无头也能测，不需要网络）。 */
    public static List<String> preview(EntityMaid maid) {
        List<String> out = new ArrayList<>();
        IItemHandler inv = backpack(maid);
        if (inv == null) {
            return out;
        }
        List<MaidBdRules.AtLeastRule> rules = MaidBdRules.atLeastRules();
        if (rules.isEmpty()) {
            out.add("（自动补货规则为空——用 /maid_smart bd_rule keepAtLeast 64 minecraft:torch 加一条）");
            return out;
        }
        for (MaidBdRules.AtLeastRule r : rules) {
            long have = MaidBdRules.countMatching(inv, r.match());
            out.add(r.match() + "：她有 " + have + " 个，目标 " + r.count()
                    + "，需要补 " + Math.max(0, r.count() - have) + " 个");
        }
        return out;
    }

    /** 执行补货；dryRun=true 时只算不取。 */
    public static List<String> restock(EntityMaid maid, Object net, boolean dryRun) {
        List<String> out = new ArrayList<>();
        IItemHandler inv = backpack(maid);
        if (inv == null) {
            return out;
        }
        long movedTotal = 0;
        for (MaidBdRules.AtLeastRule r : MaidBdRules.atLeastRules()) {
            if (movedTotal >= PER_TICK_CAP) {
                break;
            }
            long have = MaidBdRules.countMatching(inv, r.match());
            long need = r.count() - have;
            if (need <= 0) {
                continue;
            }
            long want = Math.min(need, Math.min(PER_RULE_CAP, PER_TICK_CAP - movedTotal));
            if (dryRun) {
                out.add(r.match() + "：需要补 " + want + " 个（她有 " + have + "）");
                movedTotal += want;
                continue;
            }
            ItemStack taken = null;
            long amount = 0;
            if (r.match().startsWith("tag:")) {
                MaidBdCompat.Taken tk = MaidBdCompat.extractByTag(net, MaidBdRules.tagOf(r.match().substring(4)), want);
                if (tk != null) {
                    taken = tk.stack();
                    amount = tk.amount();
                }
            } else {
                ItemStack proto = MaidBdCompat.stackOf(r.match());
                if (!proto.isEmpty()) {
                    long got = MaidBdCompat.extract(net, proto, want);
                    if (got > 0) {
                        taken = proto.copy();
                        taken.setCount((int) Math.min(Integer.MAX_VALUE, got));
                        amount = got;
                    }
                }
            }
            if (taken == null || amount <= 0) {
                out.add(r.match() + "：库里没取到（需要 " + want + "）");
                continue;
            }
            long placed = insertIntoMaid(inv, taken);
            long back = amount - placed;
            if (back > 0) {
                // 塞不下的还回网络；连还都失败就掉她脚下（不丢件，但要留痕）
                long leftover = MaidBdCompat.insert(net, taken, back);
                if (leftover != back) {
                    ItemStack drop = taken.copy();
                    drop.setCount((int) (back - Math.max(0, leftover)));
                    if (!drop.isEmpty()) {
                        maid.spawnAtLocation(drop);
                        PromaidLog.log("超越维度补货", "还回网络失败，已掉在她脚下："
                                + MaidBdCompat.itemId(drop) + " × " + drop.getCount());
                    }
                }
            }
            out.add(r.match() + "：补了 " + placed + " 个" + (back > 0 ? "（" + back + " 个塞不下，已还回）" : ""));
            movedTotal += placed;
        }
        if (!dryRun && !out.isEmpty()) {
            PromaidLog.log("超越维度补货", maid.getName().getString() + " 本轮："
                    + String.join("；", out));
        }
        return out;
    }

    /** 往她可用背包里塞，返回实际塞进去的数量（跨槽位）。 */
    private static long insertIntoMaid(IItemHandler inv, ItemStack stack) {
        ItemStack rest = stack.copy();
        for (int i = 0; i < inv.getSlots() && !rest.isEmpty(); i++) {
            rest = inv.insertItem(i, rest, false);
        }
        return stack.getCount() - rest.getCount();
    }

    private static IItemHandler backpack(EntityMaid maid) {
        try {
            return maid == null ? null : maid.getAvailableBackpackInv();
        } catch (Throwable t) {
            return null;
        }
    }
}
