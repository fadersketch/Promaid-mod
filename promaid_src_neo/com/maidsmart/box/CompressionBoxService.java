package com.maidsmart.box;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.PlayerMainInvWrapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 压缩盒的取放服务（v1.2.2 实测六百一十六；六百一十八改成箱子式鼠标取放）。
 *
 * 玩家打开界面时手上那件压缩盒就是「打开的这一个」：每次操作都**重新确认那只手
 * 里还是压缩盒**（{@link #handle}），换了东西/丢掉了就直接拒绝——不需要追踪
 * 会话，也不存在「界面开着、物品已经不在」的错位。
 *
 * 为什么是「服务端说了算」：这个界面**不是原版容器界面**（没有 Menu），客户端只发
 * 「我在第几格点了哪个键」，搬多少、搬到哪、剩不剩，全在这里算。客户端伪造包最多
 * 只能让自己多搬几次，搬不出合同以外的东西（每次操作都要真实存在的东西才动）。
 *
 * ── 六百一十八：鼠标上那一叠（{@link #carryOf}） ──
 * 用户要的是「像往箱子里存东西一样，用鼠标把物品拖进去」。原版是「左键拿起、
 * 再左键放下」，光标上一直挂着那一叠——这需要**服务端也有一份**，否则关界面/掉线
 * 时那一叠就凭空没了（客户端自己记账 = 想变多少变多少，等于送物品）。
 * 所以这里的口径是：
 * <ul>
 *   <li>那一叠存在服务端（{@link #CARRIES}），客户端看到的只是服务端告诉它的样子；</li>
 *   <li>**任何路径都不许把它弄丢**：关界面（{@link #CARRY_DROP}）、换手、掉线、
 *       每 tick 的兜底（{@link #tickPlayer}）都会把它**放回背包，装不下才掉在脚边**；</li>
 *   <li>一次点击只走一条路（盒子格或背包格），而且**先在副本上把账算完再动真东西**
 *       （{@link CompressionBoxData#duplicate}）——见下面 {@link #clickBox} 的说明。</li>
 * </ul>
 *
 * 动作清单（与 {@link CompressionBoxNetworking} 的常量一一对应）：
 * <ul>
 *   <li>{@link #TAKE_ONE} / {@link #TAKE_STACK} / {@link #TAKE_ALL}：旧口径的取（直接进背包）。
 *       **界面已经不用它们**，保留是因为自检与老客户端都要走；（六百一十八起界面走 {@link #SLOT_CLICK}）</li>
 *   <li>{@link #DEPOSIT_STACK} / {@link #DEPOSIT_ONE}：旧口径的存（Shift+点背包格）；</li>
 *   <li>{@link #SLOT_CLICK}：**箱子式**——index = 槽位号（{@code < SLOTS} 是盒子格，
 *       {@code >= SLOTS} 是背包格，减 SLOTS 就是背包槽位），button = 0 左 / 1 右，shift = 快速移动；</li>
 *   <li>{@link #CARRY_DROP}：把手上的东西还回去（关界面/ESC 时发）。</li>
 * </ul>
 * 背包满时多出来的部分**掉在玩家脚边**（与原版拿取习惯一致），提示写在界面里。
 * 压缩盒本身**永远不往下存**（见 {@link #deposit}）——盒子装盒子既看不见里面，
 * 又是「把自己装进去就整盒消失」那个 bug 的入口。
 */
public final class CompressionBoxService {

    public static final int TAKE_ONE = 0;
    public static final int TAKE_STACK = 1;
    public static final int TAKE_ALL = 2;
    public static final int DEPOSIT_STACK = 3;
    public static final int DEPOSIT_ONE = 4;
    /** 鼠标点一格（箱子式）：index = 槽位号，button / shift 另传 */
    public static final int SLOT_CLICK = 5;
    /** 把手上的东西还回背包（装不下掉脚边）：关界面 / ESC / 换手时发 */
    public static final int CARRY_DROP = 6;

    /**
     * 玩家「鼠标上那一叠」。
     *
     * 【为什么用普通 HashMap】所有访问都发生在**服务端主线程**（包处理进了 enqueueWork，
     * 事件也在主线程），不存在并发读写；用并发容器只会让人误以为别处也能随便碰。
     */
    private static final Map<UUID, Carry> CARRIES = new HashMap<>();

    /**
     * 「这一下为什么没成」——最近一次被拒的原因文案（v1.2.2 实测六百二十）。
     *
     * 【为什么要有】六百一十八起界面点击的裁决在服务端，而服务端拒了就什么都不发生：
     * 玩家只看得到「点了没反应」。界面上那几条红字是**客户端自己算的**（压缩盒/附魔
     * 这两条写死的判据，客户端读得到同一份 {@link CompressionBoxFilter}）；但真正的
     * 裁决在服务端，只有它说得清「这一次为什么没成」。所以这里记一句人话，
     * 随下一次内容同步发给客户端画出来
     * （{@link CompressionBoxNetworking.BoxStatePacket} 的 notice 字段）。
     * 与 {@link #CARRIES} 一样：所有访问都在服务端主线程，用普通 HashMap。
     */
    private static final Map<UUID, String> NOTICES = new HashMap<>();

    private CompressionBoxService() {
    }

    /** 手序号（网络里传的是 ordinal）→ 交互手 */
    public static InteractionHand handOf(int ordinal) {
        return ordinal == 1 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    /** 某个玩家「鼠标上那一叠」的持有者（没有就现造一个空的，省得到处判 null） */
    private static final class Carry {
        /** 这一叠属于哪只手的界面（换手/关界面后要还回去） */
        private int hand;
        private ItemStack stack = ItemStack.EMPTY;
    }

    /* ==================== 鼠标上那一叠 ==================== */

    /** 客户端渲染用：这个玩家现在手上挂着什么（空 = 没有） */
    public static ItemStack carryOf(ServerPlayer player) {
        Carry c = CARRIES.get(player.getUUID());
        return c == null ? ItemStack.EMPTY : c.stack;
    }

    /** 上一次被拒的原因（没有 = 空串）——随内容同步发给客户端，界面上画成一行红字 */
    public static String noticeOf(ServerPlayer player) {
        String s = NOTICES.get(player.getUUID());
        return s == null ? "" : s;
    }

    /** 记一句「为什么没成」（{@link #noticeOf} 读的就是它）；传 null/空串 = 清掉 */
    private static void notice(ServerPlayer player, String text) {
        if (text == null || text.isEmpty()) {
            NOTICES.remove(player.getUUID());
        } else {
            NOTICES.put(player.getUUID(), text);
        }
    }

    /** 拒绝并记一句人话（所有「不肯收」的分支都走这里，免得漏掉提示） */
    private static boolean refuse(ServerPlayer player, String text) {
        notice(player, text);
        return false;
    }

    private static Carry carry(ServerPlayer player, int handOrdinal) {
        Carry c = CARRIES.get(player.getUUID());
        if (c == null) {
            c = new Carry();
            CARRIES.put(player.getUUID(), c);
        }
        c.hand = handOrdinal;
        return c;
    }

    /**
     * 把手上的东西**还回背包**（装不下再掉在脚边），然后清掉。
     *
     * 这是「不许弄丢」的唯一出口：关界面、换手、掉线、每 tick 兜底、甚至操作过程里
     * 突然发现那只手已经不是盒子了，全部走这里。掉在脚边也好过凭空消失——玩家至少
     * 看得见、捡得回来。
     */
    public static void returnCarry(ServerPlayer player) {
        Carry c = CARRIES.remove(player.getUUID());
        if (c == null || c.stack.isEmpty()) {
            return;
        }
        ItemStack left = c.stack;
        c.stack = ItemStack.EMPTY;
        try {
            left = ItemHandlerHelper.insertItemStacked(
                    new PlayerMainInvWrapper(player.getInventory()), left, false);
        } catch (Throwable ignored) {
            // 背包访问出问题也要掉出来，不能留在手里
        }
        if (!left.isEmpty()) {
            player.drop(left, false); // 装不下的掉脚边
        }
    }

    /**
     * 每 tick 兜底（由 {@code ProMaidExtension} 的 PlayerTickEvent 调）。
     *
     * 【为什么要有这一道】界面上挂着东西时玩家可能**根本没关界面**就出事了：死了、
     * 被传送、盒子被别的模组拿走、客户端崩了。客户端那几种情况各自会发包或发不出来，
     * 但只要**那一手里已经不是压缩盒**，这一叠就该还回去——这条判据客户端伪造不了，
     * 也不依赖客户端是否还在线。
     */
    public static void tickPlayer(ServerPlayer player) {
        Carry c = CARRIES.get(player.getUUID());
        if (c == null) {
            return;
        }
        if (c.stack.isEmpty()) {
            CARRIES.remove(player.getUUID()); // 手上空着：顺手把空壳清掉，别一直留着
            return;
        }
        if (!CompressionBoxData.isBox(player.getItemInHand(handOf(c.hand)))) {
            returnCarry(player);
        }
    }

    /* ==================== 入口 ==================== */

    /** 旧口径的四参入口（自检/老客户端）：等于左键单击、不按 Shift */
    public static boolean handle(ServerPlayer player, int handOrdinal, int action, int index) {
        return handle(player, handOrdinal, action, index, 0, false);
    }

    /**
     * 服务端处理一次点击；返回 true = 有东西变了（调用方据此回包刷新界面）。
     * 那只手里已经不是压缩盒了（换手/丢出去/被拿走）→ 把手上的东西还回去，什么都不改。
     */
    public static boolean handle(ServerPlayer player, int handOrdinal, int action, int index,
                                 int button, boolean shift) {
        try {
            ItemStack box = player.getItemInHand(handOf(handOrdinal));
            if (action == CARRY_DROP) {
                // 关界面 / ESC：手上的东西还回背包（装不下掉脚边）。**先于**下面的
                // 「那只手还是不是盒子」判断——界面都关了，盒子在不在手边都得还回去。
                returnCarry(player);
                return CompressionBoxData.isBox(box); // 还是盒子 → 让界面收一份「手上空了」
            }
            if (!CompressionBoxData.isBox(box)) {
                // 界面还开着、那只手已经不是盒子了（滚轮换了快捷栏 / 被拿走 / 丢出去）
                // → 手上的东西还回背包，别让它挂在虚空里
                returnCarry(player);
                return false;
            }
            List<ItemStack> items = CompressionBoxData.read(box);
            IItemHandler inv = new PlayerMainInvWrapper(player.getInventory());
            // 新的一次点击：先清掉上一次的拒绝提示（本次真被拒了会重新写上）
            notice(player, null);
            if (action <= DEPOSIT_ONE) {
                // 旧口径（自检一直在跑这几条，保留）
                return action <= TAKE_ALL
                        ? take(player, inv, box, items, index, action)
                        : deposit(player, inv, box, items, index,
                        action == DEPOSIT_ONE ? 1 : Integer.MAX_VALUE);
            }
            if (action == SLOT_CLICK) {
                return click(player, inv, box, items, handOrdinal, index, button, shift);
            }
            return false;
        } catch (Throwable ignored) {
            // 界面动作出错不该把服务端拖下水：什么都不改，下次点击重来。
            // 【注意】六百一十八起，各条动作自己保证「要么两边都改完，要么一点都不改」
            // （见 clickBox 的「先在副本上算」），所以这里吞掉异常不会再吃掉物品。
            return false;
        }
    }

    /* ==================== 旧口径：取 / 存 ==================== */

    /**
     * 从第 slot 格往外拿（直接进背包）。
     *
     * 【改动都在副本上】先 {@link CompressionBoxData#duplicate} 一份，全部搬完再
     * {@link CompressionBoxData#write} 落盘：万一中途抛异常，盒子的数据组件还是原样
     * （最坏是背包里多了一份，绝不会少一份——丢东西比多东西严重得多）。
     */
    private static boolean take(ServerPlayer player, IItemHandler inv, ItemStack box,
                                List<ItemStack> items, int slot, int action) {
        if (action == TAKE_ALL) {
            return takeAll(player, inv, box, items, slot);
        }
        List<ItemStack> work = CompressionBoxData.duplicate(items);
        int want = action == TAKE_ONE ? 1 : CompressionBoxData.TAKE_PER_CLICK;
        ItemStack out = CompressionBoxData.take(work, slot, want);
        if (out.isEmpty()) {
            return false;
        }
        give(player, inv, work, out);
        CompressionBoxData.write(box, work);
        return true;
    }

    /** 整格取：反复取 64 直到那格空**或背包满**（装不进的那份原样放回盒子，不丢） */
    private static boolean takeAll(ServerPlayer player, IItemHandler inv, ItemStack box,
                                   List<ItemStack> items, int slot) {
        List<ItemStack> work = CompressionBoxData.duplicate(items);
        boolean any = false;
        while (true) {
            ItemStack out = CompressionBoxData.take(work, slot, CompressionBoxData.TAKE_PER_CLICK);
            if (out.isEmpty()) {
                break;
            }
            any = true;
            if (!give(player, inv, work, out)) {
                break; // 背包满了：剩下的留在盒子里，本轮收手
            }
        }
        if (any) {
            CompressionBoxData.write(box, work);
        }
        return any;
    }

    /**
     * 给玩家一堆东西：先进背包，装不下的**放回盒子**，盒子也放不回去才掉在脚边。
     * 返回 false = 一件都没进背包（调用方据此停下「整格取」的循环）。
     */
    private static boolean give(ServerPlayer player, IItemHandler inv, List<ItemStack> work,
                                ItemStack out) {
        ItemStack left = ItemHandlerHelper.insertItemStacked(inv, out, false);
        if (left.isEmpty()) {
            return true;
        }
        ItemStack back = CompressionBoxData.merge(work, left);
        if (!back.isEmpty()) {
            player.drop(back, false);
        }
        return left.getCount() < out.getCount();
    }

    /**
     * 把玩家背包第 slot 格存进盒子（旧入口：Shift+点背包格）。
     *
     * <b>压缩盒一律不存</b>（v1.2.2 实测六百一十七修的 bug）：先取出来再发现塞不进去、
     * 或者干脆把自己装进自己——界面开着时 Shift+点手上那一格就是这个动作，而那时
     * 取出来的正好是「打开着的这个盒子」，写数据组件的对象已经不在背包里，整盒东西就没了。
     * 所以在**动手取之前**就拒掉（{@link CompressionBoxData#mergeInto} 里还有一道，
     * 防的是女仆背包那条路）。
     *
     * 【六百一十八：顺序改成「先落盘、再从背包扣」】旧版是「先从背包取出来、再合进盒子」，
     * 中间任何一步抛异常（写数据组件失败、合并不下）都会让那份东西**只离开背包、没进盒子**
     * ——正是用户报的「存进去就消失了」。现在先在**副本**上把能装多少算清楚
     * （{@link CompressionBoxData#merge} 在副本上跑，算不动真列表），写盒子成功之后
     * 才去扣背包：错了也只是盒子先收下、背包没扣（多一份），不会少一份。
     */
    private static boolean deposit(ServerPlayer player, IItemHandler inv, ItemStack box,
                                   List<ItemStack> items, int slot, int amount) {
        if (slot < 0 || slot >= inv.getSlots()) {
            return false;
        }
        ItemStack from = inv.getStackInSlot(slot);
        if (from.isEmpty()) {
            return false;
        }
        // 六百二十：压缩盒 / 带附魔的物品——一律不往盒子里放，
        // 并且把「为什么」留给界面（notice → 客户端画一行红字）
        String why = CompressionBoxFilter.reason(from);
        if (why != null) {
            return refuse(player, why);
        }
        int want = Math.min(amount, from.getCount());
        List<ItemStack> work = CompressionBoxData.duplicate(items);
        ItemStack left = CompressionBoxData.merge(work, from.copyWithCount(want));
        int moved = want - left.getCount();
        if (moved <= 0) {
            return false; // 盒子满了：一件都没动
        }
        CompressionBoxData.write(box, work);              // ① 盒子先落盘
        ItemStack got = inv.extractItem(slot, moved, false); // ② 再从背包扣（同一拍、同一格，扣得动）
        if (got.getCount() != moved) {
            // 理论上到不了这里（同一 tick 里没有别人动这一格）。真到了就**整个回退**：
            // 盒子写回操作前的样子、扣出来的那份还给背包 —— 宁可这次白干，也不许对不上账。
            CompressionBoxData.write(box, items);
            putBack(player, inv, got);
            return false;
        }
        return true;
    }

    /* ==================== 箱子式：一次点击 ==================== */

    /**
     * 鼠标点了某一格。槽位号约定：{@code < SLOTS} = 盒子格；{@code >= SLOTS} = 背包格
     * （减 SLOTS 就是玩家背包槽位，与 {@link PlayerMainInvWrapper} 一致）。
     *
     * 动作与「往箱子里存东西」的原版手感对齐：
     * <ul>
     *   <li>左键空格子…手里空 → **拿起**（盒子格最多 64，因为不可堆叠的东西一次只该拿它自己能堆的那么多）；
     *       手里有 → **放下**（能塞多少塞多少，塞不下的继续挂在手上）；</li>
     *   <li>右键：手里空 → 拿 1 个（背包格按原版拿一半）；手里有 → 放 1 个；</li>
     *   <li>Shift+左键：**快速移动**——盒子格 → 背包（最多 64），背包格 → 盒子；</li>
     *   <li>背包格上「手里有、那格也有别的物品」→ **交换**（与原版一致）。</li>
     * </ul>
     */
    private static boolean click(ServerPlayer player, IItemHandler inv, ItemStack box,
                                 List<ItemStack> items, int handOrdinal, int index, int button,
                                 boolean shift) {
        boolean inBox = index < CompressionBoxData.SLOTS;
        int slot = inBox ? index : index - CompressionBoxData.SLOTS;
        if (!inBox && (slot < 0 || slot >= inv.getSlots())) {
            return false;
        }
        if (shift && button == 0) {
            return quickMove(player, inv, box, items, inBox, slot);
        }
        Carry c = carry(player, handOrdinal);
        if (inBox) {
            // 六百二十：手上这一叠先过禁入判据（压缩盒/附魔物品）。
            // 挡在这里而不是 clickBox 里——clickBox 拿不到玩家，说不清「为什么」。
            String why = CompressionBoxFilter.reason(c.stack);
            if (why != null) {
                return refuse(player, why);
            }
            // 【为什么先拷副本】拿起/放下同时会改「盒子那一格」和「手上这一叠」，
            // 而盒子的真身要到最后一次 write 才落盘。在副本上算完再写，中途出错时
            // 盒子保持原样（手上的那一叠还没发出去，客户端下一拍刷新就回到旧样子）。
            List<ItemStack> work = CompressionBoxData.duplicate(items);
            if (!clickBox(work, slot, c, button)) {
                return false;
            }
            CompressionBoxData.write(box, work);
            return true;
        }
        return clickInv(player, inv, slot, c, button);
    }

    /** 左/右键点盒子某一格（拿起或放下）。{@code items} 必须是**副本**（见 click 的说明） */
    private static boolean clickBox(List<ItemStack> items, int slot, Carry c, int button) {
        ItemStack cur = items.get(slot);
        if (c.stack.isEmpty()) {
            // 拿起：左键拿「原版堆叠口径」那么多（不可堆叠的拿 1），右键拿 1
            int want = button == 1 ? 1 : CompressionBoxData.viewCap(cur);
            ItemStack out = CompressionBoxData.take(items, slot, want);
            if (out.isEmpty()) {
                return false;
            }
            c.stack = out;
            return true;
        }
        // 放下：左键放整叠（能塞多少塞多少），右键放 1 个
        int want = Math.min(button == 1 ? 1 : c.stack.getCount(), c.stack.getCount());
        ItemStack put = c.stack.copyWithCount(want);
        if (!CompressionBoxFilter.canStore(put)) {
            return false; // 禁入判据（mergeInto 里还有一道，这里先挡以便界面给提示）
        }
        ItemStack left = CompressionBoxData.mergeInto(items, slot, put);
        int moved = want - left.getCount();
        if (moved <= 0) {
            return false; // 那一格是别的物品 / 已满
        }
        c.stack.shrink(moved); // 手上少掉放下去的那些
        if (c.stack.isEmpty()) {
            c.stack = ItemStack.EMPTY;
        }
        return true;
    }

    /**
     * 左/右键点背包某一格（拿起 / 放下 / 合并 / 交换）。背包是**真列表**（不像盒子要先落盘）。
     *
     * 六百二十【用户要的「压缩盒在这个界面内无法被鼠标选中」】：这一格装的是压缩盒时，
     * 鼠标对它**什么都不做**——不能被拿起来（挂着走 = 之后每一次点击都会被服务端的
     * 「那只手里还是不是盒子」判据挡掉，看着像界面坏了），也不能被「交换」那一路
     * 顶到鼠标上去。全库只有这里能改变鼠标上挂着的东西，所以挡在入口就够：
     * <ul>
     *   <li>手里空 + 这一格是盒子 → 不拿（原来会整叠拿起来）；</li>
     *   <li>手上有东西 + 这一格是盒子 → 不换（原来会走「交换」把手上的东西塞进去、
     *       把盒子提到鼠标上）。</li>
     * </ul>
     * 拒绝时回一句「压缩盒不能装进压缩盒」——这正是用户要的那句再次提示。
     */
    private static boolean clickInv(ServerPlayer player, IItemHandler inv, int slot, Carry c,
                                    int button) {
        ItemStack cur = inv.getStackInSlot(slot);
        if (CompressionBoxData.isBox(cur)) {
            return refuse(player, CompressionBoxFilter.MSG_BOX);
        }
        if (c.stack.isEmpty()) {
            if (cur.isEmpty()) {
                return false;
            }
            // 拿起：左键整叠，右键一半（原版口径）
            int want = button == 1 ? (cur.getCount() + 1) / 2 : cur.getCount();
            ItemStack out = inv.extractItem(slot, want, false);
            if (out.isEmpty()) {
                return false;
            }
            c.stack = out;
            return true;
        }
        if (cur.isEmpty()) {
            return placeIntoSlot(inv, slot, c, button == 1 ? 1 : c.stack.getCount());
        }
        if (ItemStack.isSameItemSameComponents(cur, c.stack)) {
            return placeIntoSlot(inv, slot, c, button == 1 ? 1 : c.stack.getCount());
        }
        if (button == 1) {
            return false; // 右键遇上是别的物品 = 原版也只是放 1 个，这里不换
        }
        // 交换：**只有手上这叠能整个放进那一格时才换**。反过来的顺序（先取出原格、
        // 再把手上的塞进去）一旦塞不下就会把原本那一叠挤在手上/挤丢，不如干脆不换。
        if (!inv.isItemValid(slot, c.stack)) {
            return false;
        }
        int limit = Math.min(inv.getSlotLimit(slot), c.stack.getMaxStackSize());
        if (c.stack.getCount() > limit) {
            return false; // 放不下整叠（例：手上 100 个、这一格上限 64）→ 不换，免得退不回去
        }
        ItemStack old = inv.extractItem(slot, cur.getCount(), false);
        if (old.getCount() != cur.getCount()) {
            putBack(player, inv, old); // 不该发生；发生了也不吞
            return false;
        }
        ItemStack back = inv.insertItem(slot, c.stack, false);
        if (!back.isEmpty()) {
            // 也不该发生（刚腾空 + 数量已经查过）。真到了这里就把原来那叠还给玩家，
            // 手上一叠保持原样 —— 宁可这次没换成，也不能丢任何一边。
            putBack(player, inv, old);
            return false;
        }
        c.stack = old;
        return true;
    }

    /** 把手上的东西放进**指定的背包格**（同物品合并 / 空格子放下），放不下的留在手上 */
    private static boolean placeIntoSlot(IItemHandler inv, int slot, Carry c, int want) {
        want = Math.min(want, c.stack.getCount());
        if (want <= 0) {
            return false;
        }
        ItemStack put = c.stack.copyWithCount(want);
        ItemStack left = inv.insertItem(slot, put, false);
        int moved = want - left.getCount();
        if (moved <= 0) {
            return false;
        }
        c.stack.shrink(moved);
        if (c.stack.isEmpty()) {
            c.stack = ItemStack.EMPTY;
        }
        return true;
    }

    /**
     * Shift+左键 = 原版「快速移动」：
     * 盒子格 → 背包（最多 64，装不下的留在盒子里）；背包格 → 盒子（整叠，盒子满了就一点不存）。
     */
    private static boolean quickMove(ServerPlayer player, IItemHandler inv, ItemStack box,
                                     List<ItemStack> items, boolean inBox, int slot) {
        if (!inBox) {
            ItemStack from = inv.getStackInSlot(slot);
            if (from.isEmpty()) {
                return false; // 空的没得存
            }
            // 六百二十：Shift+点这一格 = 「整叠存进去」，同样要过禁入判据
            // （压缩盒不许装压缩盒是用户报过的那个 bug；附魔物品是这一批新加的口径）
            String why = CompressionBoxFilter.reason(from);
            if (why != null) {
                return refuse(player, why);
            }
            return deposit(player, inv, box, items, slot, Integer.MAX_VALUE);
        }
        return takeAll(player, inv, box, items, slot);
    }

    /** 实在没地方放的东西：先塞背包，塞不下掉在脚边——**绝不销毁** */
    private static void putBack(ServerPlayer player, IItemHandler inv, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack left = ItemHandlerHelper.insertItemStacked(inv, stack, false);
        if (!left.isEmpty()) {
            player.drop(left, false);
        }
    }
}
