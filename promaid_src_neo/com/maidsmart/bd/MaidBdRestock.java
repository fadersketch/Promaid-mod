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
 * 超越维度·自动补货 keepAtLeast——她背包里"至少留 N 个"，少了从主人主网络取。
 *
 * 【它是"保留 N 个"的镜像】{@code keepN}：背包里**最多**留 N 个，多的进库（背包→数据库）；
 * {@code keepAtLeast}：**至少**留 N 个，少了从库里取（数据库→背包）。两条都写在
 * {@code config/promaid_bd_rules.json}，共用她那一只开关（{@code /maid_smart bd_deposit true}）。
 * 【为什么这是"取出"里最安全的第一种形式】取什么**由玩家明确写下来**（物品 id 或标签），不存在
 * "取出任意物品"那种匹配难题：按具体物品查是 O(1)、按标签取也是 O(1)，且库里返回的 key 会告诉我们
 * **实际给的是哪一种**——所以"用不同种类的石头搭路"/"哪个 mod 的火把都行"才成立。
 * 【三条安全规矩】① 先取后放：先从库里取、再往背包放；放不下的部分**还回网络**，连还都失败（网络恰好
 * 满）就掉在她脚下（绝不凭空消失）；② 差额驱动：只补到目标数（跨槽位合计），不会越补越多；
 * ③ 有限速：每轮最多 4 组 / 1024 个，避免卡顿。
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
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidBdRestock", ignored);
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
            String tail = back > 0 ? "（" + back + " 个塞不下，已还回）" : "";
            if (back > 0) {
                // 塞不下的还回网络。insert 返回的是**剩余量**（未吃下的）：0 = 全还回去了；
                // -1 = 调用失败。所以"还要处理的那一份" = leftover（成功）或 back（失败）。
                long leftover = MaidBdCompat.insert(net, taken, back);
                long toDrop = leftover < 0 ? back : leftover;
                if (toDrop > 0) {
                    ItemStack drop = taken.copy();
                    drop.setCount((int) Math.min(Integer.MAX_VALUE, toDrop));
                    maid.spawnAtLocation(drop);
                    PromaidLog.log("超越维度补货", "还回网络失败，已掉在她脚下："
                            + MaidBdCompat.itemId(drop) + " × " + drop.getCount());
                    tail = "（" + placed + " 个入包，" + toDrop + " 个还库失败已落地）";
                }
            }
            out.add(r.match() + "：补了 " + placed + " 个" + tail);
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
             com.maidsmart.diag.Diag.swallow("MaidBdRestock", t); return null;
        }
    }
}
