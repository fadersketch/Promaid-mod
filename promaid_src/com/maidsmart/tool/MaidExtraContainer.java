package com.maidsmart.tool;

import com.github.tartaricacid.touhoulittlemaid.compat.extracontainer.ContainerRef;
import com.github.tartaricacid.touhoulittlemaid.compat.extracontainer.MaidContainerCache;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.util.ItemsUtil;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Predicate;

/**
 * v1.2.4 实测六百三十六【精妙背包适配：自己的背包满了 → 再问问她身上的"额外容器"】。
 *
 * ── 需求原文（issue #20）──
 * 反馈人 MingDeee：「农场模式会连锁收割农作物。然后掉落物放到女仆背包里。而车万女仆本体支持
 * 当女仆背包满了之后会自动放进精妙背包里。但是这里如果一次性收割的掉落物直接超出女仆背包的
 * 容量，是不会把超出来的放进精妙背包，而是以掉落物的形式掉在地上。」用户拍板：「我不打算把
 * 压缩盒这个物品删掉。但是对于精妙背包的相关适配确实是要做的。帮我做一下。简单来说就是发现
 * 自己背包满了之后，再检索一下有没有精妙背包。有的话就装进去。」
 *
 * ── 走 TLM 现成的"额外容器"API，而不是自己去反射精妙背包 ──
 * javap 实证（1.20.1 的 {@code original_tlm.jar} 与 1.21.1 的 neoforge 包**签名一致**）：
 * TLM 本体早就有一整套额外容器体系，把**饰品栏里**的精妙背包 / 旅行者背包当成"背包的延伸"：
 * <pre>
 *   MaidContainerCache.getContainers(maid)  →  List&lt;ContainerRef&gt;
 *       第 0 项永远是 MaidInventoryRef（她自己的背包，所以本类一律从下标 1 开始）
 *       之后是按槽位优先级排好的 CuriosSlotRef（精妙背包是 SBackpackSlotRef，坐 "back" 槽）
 *   ContainerRef.insert(maid, stack, simulate)  →  塞进去，返回**没塞下的那一份**
 *   ContainerRef.containing(maid, stack)        →  那个容器里有没有同类物品
 * </pre>
 * TLM 自己的拾取（{@code ExtraContainerPickupHandler}）就是这么写的：**先放"已经装着同类
 * 物品"的容器，再放任何容器**——本类照抄这个顺序（不然会出现"明明背包里有半组、却新开一格"
 * 的分裂观感）。
 *
 * 【为什么不自己找精妙背包】精妙背包对外给的是它自己的能力
 * （{@code CapabilityBackpackWrapper}），**不是** Forge 的 {@code ITEM_HANDLER}——自己找就得
 * 硬编码它的类名、版本与内部方法名。TLM 已经把这层做完了，而且顺带覆盖了旅行者背包、以及
 * 将来任何注册进它那套体系的容器；我们只调它的公开 API，越少自己摸越不容易碎。
 *
 * ── 什么时候真的会生效（先说清楚，免得当成"没生效"）──
 * TLM 的额外容器完全建立在**饰品槽**上，所以需要：Curios 在场 + TLM 的"女仆饰品"功能开启
 * （{@code MaidConfig.ENABLE_MAID_CURIOS}）+ 精妙背包或旅行者背包**装在女仆的饰品栏里**
 * （精妙背包坐的是 curios 的 {@code back} 槽，{@code SLOT_PRIORITY} 里优先级最高）。
 * 一件都不满足时 {@code getContainers} 只返回"她自己"那一项 → 本类原样返回余量，
 * 行为与旧版**一字不差**（拿在手上/放在别处的背包不算——那两处 TLM 也不认）。
 *
 * ── 与"绝不吞物品"的关系 ──
 * 本类只在**调用方已经确认"她自己的背包塞不下"之后**才被调用，返回值仍是"还塞不下的那一份"，
 * 调用方照旧落地（{@code spawnAtLocation} / {@code popResource}）。所以最坏情况依然是
 * "掉在地上"，不会因为多了一层容器而多吞掉一件东西。
 *
 * 总开关 {@code misc.backpackOverflow}（默认开）：关掉 = 只用自己的背包（旧行为）。
 *
 * ── v1.2.4 实测六百三十九【反方向：取物】──
 * 上面说的是"装进去"，同一套 API 还能反过来用。玩家要求：「兼容方面，所有判定的位点
 * 再加上一个看看精妙背包里面有没有东西就行了。先判背包，再判精妙背包。」于是本类再加
 * 两个方法，给**我们自己的**那些检索/取物位点用（搭方块、工具、种子、烧制/酿造用料、
 * 烟花羽扇三叉戟、火把、蓝图材料……）：
 * <pre>
 *   contains(maid, filter)        只读探针："额外容器里有没有"（取出 1 个立刻放回，净零）
 *   pull(maid, filter, maxCount)  取物：请 TLM 把匹配物搬进她自己的背包（先判背包，再判精妙背包）
 * </pre>
 * 取物不再额外加开关：TLM 本体对它自己的任务（弓弩弹药、呼吸药水、自愈、喂主人、工作餐、
 * 换装备）本来就是无条件的，我们跟着它的口径走——装了精妙背包却没打算让女仆用里面的东西，
 * 那是"别把她打扮成这样"就能解决的事，不是我们该拦的。
 */
public final class MaidExtraContainer {

    private MaidExtraContainer() {
    }

    /** 总开关：{@code misc.backpackOverflow}（默认开），关掉直接不试额外容器 */
    public static boolean enabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.MISC_BACKPACK_OVERFLOW.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 她身上有没有"额外容器"可放（不含她自己那个背包）。
     *
     * 【谁在用】只给气泡/日志这类"要说明白现在什么状况"的地方用（例如"背包满了，
     * 但没找到精妙背包"）；真正的插入走 {@link #overflow}。
     */
    public static boolean hasAny(EntityMaid maid) {
        return extraContainers(maid) != null;
    }

    /* ==================== v1.2.4 实测六百三十九【取物的那半边】 ==================== */

    /**
     * v1.2.4 实测六百三十九【先判背包，再判精妙背包：**看**】——额外容器
     * （精妙背包 / 旅行者背包……）里有没有匹配 {@code filter} 的物品。**只读探针**。
     *
     * ── 为什么不能直接读 ──
     * TLM 的 {@code ContainerRef} 只给了三个动作：{@code containing(maid, stack)}
     * （要求给一件**具体物品**做同类比较）、{@code insert}、{@code extract}——
     * 没有"按谓词看一眼"这个口子。所以本方法用**取出 1 个 + 立刻放回**实现"看一眼"：
     * 净零改动（放回的是同一个容器、同一件物品）。
     *
     * ── 万一放不回去 ──
     * 兜底链三级，**绝不吞物品**：原容器放回 → 塞进她自己背包 → 落地成掉落物
     * （真的走到第三级会打一条日志）。正常路径永远在第一级就回去了。
     *
     * 【谁用它】判定类的位点（"她身上到底有没有这个东西"）：气泡、就绪判定、
     * 能不能起飞/能不能干活这类只取布尔的闸门。真要**动手拿**的地方用 {@link #pull}。
     */
    public static boolean contains(EntityMaid maid, Predicate<ItemStack> filter) {
        if (maid == null || filter == null) {
            return false;
        }
        List<ContainerRef> refs = extraContainers(maid);
        if (refs == null) {
            return false;
        }
        for (ContainerRef ref : refs) {
            if (ref == null) {
                continue;
            }
            try {
                ItemStack got = ref.extract(maid, filter, 1);
                if (got == null || got.m_41619_()) {
                    continue; // 这个容器里没有
                }
                ItemStack back = ref.insert(maid, got, false);
                if (back == null || back.m_41619_()) {
                    return true; // 原样放回 → 净零改动
                }
                // 放不回去（理论上不该发生）：第二级塞她自己背包，第三级落地
                ItemStack rest = net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(
                        maid.getAvailableInv(false), back, false);
                if (!rest.m_41619_()) {
                    maid.m_19983_(rest);
                    PromaidLog.log("额外容器", nameOf(maid) + " 取物探针放回失败，已落地 "
                            + rest.m_41613_() + " 件（未吞物品）");
                }
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /**
     * v1.2.4 实测六百三十九【先判背包，再判精妙背包：**取**】——她自己的背包里没有时，
     * 请 TLM 的额外容器系统把匹配物**搬进她的背包**。
     *
     * ── 走的是 TLM 自己那条路 ──
     * {@code ItemsUtil.findStackSlot(她自己的背包, filter, maxCount)}：先扫她背包
     * （找到就返回，**一点副作用都没有**）；没找到且处理器是 TLM 的
     * {@code MaidInvWrapper}（{@code getAvailableInv}/{@code getAvailableBackpackInv}
     * 返回的正是它）→ 发 {@code MaidRequestItemEvent} → TLM 的
     * {@code ExtraContainerRequestHandler} 从精妙背包/旅行者背包里抽出物品放进她的背包，
     * 然后再扫一遍。**TLM 自己的任务（弓弩弹药/呼吸药水/自愈/喂主人/工作餐/换装备）
     * 用的就是这一条**——所以口径与本体完全一致，包括"她的背包满了时，把她最后一格
     * 非空物品挪进额外容器腾地方"这个动作（那是 TLM 的既定行为，不是我们加的）。
     *
     * @param filter   要什么
     * @param maxCount 一次最多搬几件（{@code -1} = 整组；与 TLM 自己传的口径一致）
     * @return true = 现在她自己的背包里有了（调用方按原来的循环再扫一遍即可拿到）
     */
    public static boolean pull(EntityMaid maid, Predicate<ItemStack> filter, int maxCount) {
        if (maid == null || filter == null) {
            return false;
        }
        if (extraContainers(maid) == null) {
            return false; // 没有额外容器 → 连扫描都不做（零开销，行为与旧版一字不差）
        }
        try {
            // 必须传 MaidInvWrapper（getAvailableInv/getAvailableBackpackInv 返回的就是它）
            // ——findStackSlot 只对这一个类型发请求；传 getMaidInv()（裸 ItemStackHandler）
            // 不会触发额外容器，客户端的 handler 也只会返回 -1（内部已判）
            net.minecraftforge.items.IItemHandler inv = maid.getAvailableInv(false);
            // 【v1.2.4 实测六百四十：先自己手写扫一遍】判据与 findStackSlot 的第一遍**完全相同**
            // （都是 filter.test(stack)、都按槽位顺序），但手写循环不会发请求。两个好处：
            // ① 她自己有的常见情形下省掉内部的重复扫描与事件构造；
            // ② **"她自己没有、请求之后却有了"= 这一件确实是从额外容器搬进来的**——
            //    这正是下面那条日志能成立的原因（不然没法区分"本来就有"和"刚拿进来"）。
            if (firstMatch(inv, filter) >= 0) {
                return true;
            }
            int slot = ItemsUtil.findStackSlot(inv, filter, maxCount);
            if (slot < 0) {
                return false;
            }
            logPull(maid, inv.getStackInSlot(slot), maxCount);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** {@link #pull} 的默认口径：一次只搬 1 件（按"有没有、够不够用一件"的判定位点用） */
    public static boolean pull(EntityMaid maid, Predicate<ItemStack> filter) {
        return pull(maid, filter, 1);
    }

    /**
     * 手写扫一遍**她自己的**背包（与 {@code ItemsUtil.findStackSlot} 的第一遍判据一致，
     * 但**不会**触发 {@code MaidRequestItemEvent}——这正是"先看自己、再问额外容器"的前提）。
     */
    private static int firstMatch(net.minecraftforge.items.IItemHandler inv, Predicate<ItemStack> filter) {
        for (int i = 0; i < inv.getSlots(); i++) {
            try {
                if (filter.test(inv.getStackInSlot(i))) {
                    return i;
                }
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    /** "从额外容器里真的取到了东西"这一行的限频表（tick，40 = 2 秒/只女仆，防"每 tick 取一件"刷屏） */
    private static final java.util.Map<EntityMaid, Long> PULL_LOG =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * v1.2.4 实测六百四十：**取物方向也留一行证据**。
     *
     * 【为什么值得记】"女仆到底认不认精妙背包 / 旅行者背包里的东西"这件事，旧版只有"装进去"
     * 那一侧有日志（{@link #overflow}），取物这一侧是静默的——玩家只能靠"她有没有拿出来用"
     * 反推，而"没拿出来"既可能是"没认出来"、也可能是"她这会儿压根不需要"。现在真的从额外容器
     * 搬进来一件就写一行（限频 2 秒），运行日志里搜「额外容器」即可：
     * 有这行 = 那条链路通了；一行都没有 = 她可能本来就有、也可能额外容器没生效
     * （那就看飞行缺件诊断里的 {@code 额外容器激流三叉戟=}）。
     */
    private static void logPull(EntityMaid maid, ItemStack got, int maxCount) {
        try {
            if (got == null || got.m_41619_()) {
                return;
            }
            long now = maid.m_9236_().m_46467_();
            Long last = PULL_LOG.get(maid);
            if (last != null && now - last < 40L) {
                return;
            }
            PULL_LOG.put(maid, now);
            String id;
            try {
                id = String.valueOf(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(got.m_41720_()));
            } catch (Throwable ignored) {
                id = got.m_41720_().toString();
            }
            PromaidLog.log("额外容器", nameOf(maid) + " 她身上没有 → 从精妙背包/旅行者背包取来 "
                    + got.m_41613_() + "x" + id + (maxCount == -1 ? "（整组）" : ""));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把"她自己的背包塞不下的那一份"再往额外容器（精妙背包 / 旅行者背包……）里塞一遍。
     *
     * @param rest 已经被她自己的背包拒绝的那一份（调用方保证非空）
     * @return 额外容器也塞不下的余量（可以就是入参本身、也可以更少；绝不返回 null）
     */
    public static ItemStack overflow(EntityMaid maid, ItemStack rest) {
        if (maid == null || rest == null || rest.m_41619_()) {
            return rest;
        }
        if (!enabled()) {
            return rest;
        }
        List<ContainerRef> refs = extraContainers(maid);
        if (refs == null) {
            return rest;
        }
        int moved = 0;
        // 第一轮：优先放"已经装着同类物品"的容器（与 TLM 自己的拾取口径一致）
        for (int i = 0; i < refs.size() && !rest.m_41619_(); i++) {
            ContainerRef ref = refs.get(i);
            if (ref == null) {
                continue;
            }
            try {
                if (!ref.containing(maid, rest)) {
                    continue;
                }
                int before = rest.m_41613_();
                ItemStack next = ref.insert(maid, rest, false);
                if (next == null) {
                    break;
                }
                rest = next;
                moved += before - rest.m_41613_();
            } catch (Throwable ignored) {
            }
        }
        // 第二轮：任何容器（第 0 项是她自己的背包，已经试过了）
        for (int i = 0; i < refs.size() && !rest.m_41619_(); i++) {
            ContainerRef ref = refs.get(i);
            if (ref == null) {
                continue;
            }
            try {
                int before = rest.m_41613_();
                ItemStack next = ref.insert(maid, rest, false);
                if (next == null) {
                    break;
                }
                rest = next;
                moved += before - rest.m_41613_();
            } catch (Throwable ignored) {
            }
        }
        if (moved > 0) {
            PromaidLog.log("额外容器", nameOf(maid) + " 自己背包塞不下 → 精妙背包/额外容器收下 "
                    + moved + " 件" + (rest.m_41619_() ? "（全部收下）" : "（还剩 " + rest.m_41613_() + " 件）"));
        }
        return rest;
    }

    /**
     * 取出"她身上的额外容器"（**不含她自己那个背包**）；没有则返回 null。
     *
     * 【每个坑都在这里挡掉】
     * <ol>
     *   <li>用 {@code try/catch(Throwable)} 包住：没装 Curios / TLM 关掉了饰品功能时，
     *       {@code getContainers} 会走"只返回她自己"那条早退分支——但真出了
     *       {@code NoClassDefFoundError}（Error 不是 Exception！）也不能让它掀掉调用方
     *       （挖矿/伐木/搭路的收方块链路）。</li>
     *   <li>{@code size() <= 1} → 说明只有"她自己"那一项 → 返回 null（调用方立刻返回原样）。</li>
     *   <li>复制成新列表再切片：{@code getContainers} 返回的是 TLM 自己的缓存实例
     *       （{@code WeakHashMap} 里那一份），我们**不能**就地改它（改坏了影响 TLM 自己的拾取）。</li>
     * </ol>
     */
    private static List<ContainerRef> extraContainers(EntityMaid maid) {
        if (maid == null) {
            return null;
        }
        try {
            List<ContainerRef> all = MaidContainerCache.getContainers(maid);
            if (all == null || all.size() <= 1) {
                return null;
            }
            return new java.util.ArrayList<>(all.subList(1, all.size()));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String nameOf(EntityMaid maid) {
        try {
            return PromaidLog.nameOf(maid);
        } catch (Throwable ignored) {
            return "女仆";
        }
    }
}
