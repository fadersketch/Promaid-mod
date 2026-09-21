package com.maidsmart.box;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.ProMaidMod;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.wrapper.PlayerMainInvWrapper;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * 压缩盒自检（v1.2.2 实测六百一十七）——{@code /maid_smart box check [女仆]} 干活的地方。
 *
 * ── 为什么要有这个东西 ──
 * 这一批修的是「收纳箱可以把自己装进去，导致被卡掉」。这个 bug 的入口是**界面的一次
 * 点击**，而本仓的自动化回归只有专用服务端（没有客户端、点不了界面）——也就是说，
 * 光靠 {@code test_*.py} 那套根本碰不到出事的那条路，很容易「改完了、测过了、其实没修」。
 * 所以这里用**假的玩家**（{@code FakePlayerFactory}）在服务端把那条路原样走一遍：
 * 手上放一个盒子、背包某一格放另一个盒子，然后调**界面点击真正调用的那个方法**
 * （{@link CompressionBoxService#handle}），要的点是：
 * <ul>
 *   <li>存另一个盒子 → 被拒，两个盒子都在、里面的东西一个不少；</li>
 *   <li>存**自己那一格** → 被拒，盒子还在手上（就是用户报的那一下）；</li>
 *   <li>对照：同一个动作存普通物品 → **必须成功**（证明上面两次「被拒」不是这条路
 *       整个坏了——这是本仓六百一十六那批学到的教训：红/绿要配一个会变的对照）；</li>
 *   <li>收尾对账：两个盒子合计的件数 = 开始 + 对照存进去的，**没有东西凭空消失**。</li>
 * </ul>
 * 女仆那一条（给了女仆才跑）在她**真实的背包视图**上再走一遍收/放，最后把试出来的
 * 东西清干净——自检不在任何人的背包里留东西。
 *
 * 全部只读世界以外的东西：动作发生在假玩家和自检自己造的物品上，唯一会改的是女仆
 * 盒子里那个空格子（用完清回空）。
 */
public final class CompressionBoxCheck {

    private CompressionBoxCheck() {
    }

    /** 跑一遍自检；返回逐行结果（[PASS]/[FAIL]/[SKIP] 打头，后面是证据） */
    public static List<Component> run(ServerLevel level, EntityMaid maid) {
        List<Component> out = new ArrayList<>();
        try {
            dataLayer(out);
            playerClicks(out, level);
            if (maid == null) {
                out.add(skip("女仆那一条没跑（带上女仆才会跑：/maid_smart box check <女仆>）"));
            } else {
                maidView(out, maid);
            }
        } catch (Throwable t) {
            out.add(fail("自检自己抛异常了：" + t));
        }
        return out;
    }

    /* ==================== ① 数据层：盒子不许进盒子 ==================== */

    private static void dataLayer(List<Component> out) {
        List<ItemStack> items = CompressionBoxData.empty();
        items.set(0, stack("minecraft:stone", 3));
        ItemStack box = boxWith(3);
        long before = CompressionBoxData.totalCount(items);

        // 先验"写进去再读回来还是原来的数"——数量口径一旦差 1（m_41769_ 是 grow 不是 setCount），
        // 后面每一条都会跟着错，这一条把它单拎出来（实测六百一十七：第一版自检就是在这里看见 4 的）
        out.add(content(box) == 3
                ? pass("读写往返：造一个装 3 个石头的盒子，读回来 " + content(box) + " 个（" + cells(box) + "）")
                : fail("读写往返不对：装 3 个读回来 " + content(box) + " 个（" + cells(box) + "）"));

        ItemStack left = CompressionBoxData.merge(items, box);
        boolean ok = CompressionBoxData.isBox(left) && left.m_41613_() == 1
                && CompressionBoxData.totalCount(items) == before
                && CompressionBoxData.usedSlots(items) == 1;
        out.add(ok ? pass("数据层 merge：压缩盒原样退回（盒子里还是 " + before + " 个石头、占 1 格）")
                : fail("数据层 merge：竟然收下了压缩盒（退回 " + name(left) + "，盒子现在 "
                + CompressionBoxData.totalCount(items) + " 个）"));

        ItemStack left2 = CompressionBoxData.mergeInto(items, 1, box);
        out.add(CompressionBoxData.isBox(left2)
                && items.get(1).m_41619_()
                ? pass("数据层 mergeInto：压缩盒原样退回（目标第 2 格仍为空）")
                : fail("数据层 mergeInto：压缩盒被塞进了第 2 格（" + name(items.get(1)) + "）"));

        // 对照：同一套方法收普通物品必须没问题——否则上面两条"被拒"说明不了任何事
        ItemStack left3 = CompressionBoxData.merge(items, stack("minecraft:stone", 5));
        out.add(left3.m_41619_() && CompressionBoxData.totalCount(items) == before + 5
                ? pass("对照：同一套方法收普通物品正常（5 个石头并进去 → 共 "
                + CompressionBoxData.totalCount(items) + " 个）")
                : fail("对照失败：普通物品都塞不进去了（退回 " + name(left3) + "，盒子现在 "
                + CompressionBoxData.totalCount(items) + " 个，应为 " + (before + 5) + "）"));
    }

    /* ==================== ② 界面点击那条路（假玩家） ==================== */

    private static void playerClicks(List<Component> out, ServerLevel level) {
        FakePlayer fp = FakePlayerFactory.getMinecraft(level);
        IItemHandlerModifiable inv = new PlayerMainInvWrapper(fp.m_150109_());
        for (int i = 0; i < inv.getSlots(); i++) {
            inv.setStackInSlot(i, ItemStack.f_41583_); // 假玩家是共用的，先把上次留下的清掉
        }
        ItemStack boxA = boxWith(3);   // 手上这个（界面上"打开的"就是它）
        ItemStack boxB = boxWith(7);   // 背包里另一个
        // setItemInHand 是 **LivingEntity.m_21008_**（javap 实证）；Player 上那个 m_6986_(ItemStack,
        // InteractionHand) 是个**空方法**（javap 里只有一句 return），写错了不报错、只是手上一
        // 直是空的——第一版自检就是这么"红"的，红得很有用。
        fp.m_21008_(InteractionHand.MAIN_HAND, boxA);
        inv.setStackInSlot(12, boxB);
        int handSlot = -1;
        ItemStack inHand = fp.m_21120_(InteractionHand.MAIN_HAND);
        for (int i = 0; i < inv.getSlots(); i++) {
            if (inv.getStackInSlot(i) == inHand) {
                handSlot = i;
                break;
            }
        }

        boolean changed1 = CompressionBoxService.handle(fp, 0, CompressionBoxService.DEPOSIT_STACK, 12);
        boolean ok1 = !changed1
                && CompressionBoxData.isBox(inv.getStackInSlot(12))
                && CompressionBoxData.isBox(fp.m_21120_(InteractionHand.MAIN_HAND))
                && content(boxA) == 3 && content(inv.getStackInSlot(12)) == 7;
        out.add(ok1 ? pass("点击路径：把背包里另一个盒子（第 12 格）Shift 存入 → 被拒，两个盒子都还在、"
                + "里面的东西没动（" + cells(boxA) + " / " + cells(inv.getStackInSlot(12)) + "）")
                : fail("点击路径：把另一个盒子存进去没被拦住（changed=" + changed1 + "，第 12 格 "
                + name(inv.getStackInSlot(12)) + "，手上 " + name(fp.m_21120_(InteractionHand.MAIN_HAND))
                + "，A " + cells(boxA) + "，B " + cells(inv.getStackInSlot(12)) + "）"));

        if (handSlot < 0) {
            out.add(skip("点击路径（自己那一格）：手上那件在背包里找不到对应槽位，跳过"));
        } else {
            boolean changed2 = CompressionBoxService.handle(fp, 0, CompressionBoxService.DEPOSIT_ONE, handSlot);
            boolean ok2 = !changed2
                    && CompressionBoxData.isBox(fp.m_21120_(InteractionHand.MAIN_HAND))
                    && content(fp.m_21120_(InteractionHand.MAIN_HAND)) == 3;
            out.add(ok2 ? pass("点击路径：Shift+点**自己那一格**（背包第 " + handSlot
                    + " 格 = 手上这个盒子）→ 被拒，盒子还在手上、里面还是 3 个 —— "
                    + "这条就是用户报的「把自己装进去被卡掉」")
                    : fail("点击路径：盒子把自己装进去了（changed=" + changed2 + "，手上 "
                    + name(fp.m_21120_(InteractionHand.MAIN_HAND)) + "，A " + cells(boxA) + "）"));
        }

        inv.setStackInSlot(13, stack("minecraft:cobblestone", 5));
        boolean changed3 = CompressionBoxService.handle(fp, 0, CompressionBoxService.DEPOSIT_STACK, 13);
        boolean ok3 = changed3 && inv.getStackInSlot(13).m_41619_()
                && content(boxA) == 3 + 5;
        out.add(ok3 ? pass("对照：同一只手、同一个动作，存普通物品（第 13 格 5 个圆石）→ 成功，"
                + "盒子里现在 " + content(boxA) + " 个、来源格已空")
                : fail("对照失败：普通物品也存不进去了（changed=" + changed3 + "，A " + cells(boxA)
                + "，第 13 格 " + name(inv.getStackInSlot(13)) + "）"));

        long sum = content(boxA) + content(inv.getStackInSlot(12));
        out.add(sum == 15L
                ? pass("对账：两个盒子合计 " + sum + " 个（自检开始 3+7=10，加上对照存进去的 5）——没有东西凭空消失")
                : fail("对账失败：两个盒子合计 " + sum + " 个，应该是 15 个"));

        for (int i = 0; i < inv.getSlots(); i++) {
            inv.setStackInSlot(i, ItemStack.f_41583_); // 假玩家的背包收拾干净
        }
    }

    /* ==================== ③ 女仆那一侧的背包视图 ==================== */

    private static void maidView(List<Component> out, EntityMaid maid) {
        IItemHandler inv = maid.getMaidInv();
        if (!(inv instanceof CompressionBoxMaidInv view) || view.boxCount() <= 0) {
            out.add(skip("女仆那一条：她背包里没有压缩盒（或压缩盒的女仆延伸关着），视图就是原版背包"));
            return;
        }
        int base = view.baseSlots();                 // 她自己的 36 格之后就是盒子格
        int free = -1;
        for (int i = base; i < view.getSlots(); i++) {
            if (view.getStackInSlot(i).m_41619_()) {
                free = i;
                break;
            }
        }
        if (free < 0) {
            out.add(skip("女仆那一条：她盒子里 5 格全满，没有空格子可试（腾一格再跑）"));
            return;
        }
        int cell = free - base + 1;

        ItemStack left = view.insertItem(free, boxWith(1), false);
        out.add(CompressionBoxData.isBox(left) && view.getStackInSlot(free).m_41619_()
                ? pass("女仆那一条：往她盒子第 " + cell + " 格（视图第 " + free + " 格）插压缩盒 → "
                + "原样退回、那格还是空的")
                : fail("女仆那一条：压缩盒被插进她背包里的盒子了（退回 " + name(left) + "，那格现在 "
                + name(view.getStackInSlot(free)) + "）"));

        ItemStack left2 = view.insertItem(free, stack("minecraft:stone", 5), false);
        out.add(left2.m_41619_() && view.getStackInSlot(free).m_41613_() == 5
                ? pass("对照：同一格插 5 个石头 → 成功（她那一侧收东西的路是通的）")
                : fail("对照失败：石头也插不进去（退回 " + name(left2) + "，那格现在 "
                + name(view.getStackInSlot(free)) + "）"));

        // 取出侧也顺手证一下：同一格拿走 4 个应当成功（别为了一个 bug 把正常路一起堵死）
        ItemStack got = view.extractItem(free, 4, false);
        out.add(got.m_41613_() == 4 && view.getStackInSlot(free).m_41613_() == 1
                ? pass("取出侧：同一格拿走 4 个 → 成功（那格现在剩 1 个）")
                : fail("取出侧不对：拿回 " + name(got) + "，那格现在 "
                + name(view.getStackInSlot(free))));

        view.setStackInSlot(free, ItemStack.f_41583_); // 自检不留痕
        out.add(view.getStackInSlot(free).m_41619_()
                ? pass("收尾：那一格已清回空（自检不在她背包里留东西）")
                : fail("收尾失败：那格没清干净（" + name(view.getStackInSlot(free)) + "）"));
    }

    /* ==================== 小工具 ==================== */

    /** 造一个盒子，第 1 格放 n 个石头 */
    private static ItemStack boxWith(int n) {
        List<ItemStack> items = CompressionBoxData.empty();
        items.set(0, stack("minecraft:stone", n));
        ItemStack box = new ItemStack(ProMaidMod.COMPRESSION_BOX.get());
        CompressionBoxData.write(box, items);
        return box;
    }

    private static int count(String id, int n) {
        return n;
    }

    /** 盒子里一共装了多少件 */
    private static long content(ItemStack box) {
        return CompressionBoxData.totalCount(CompressionBoxData.read(box));
    }

    /** 盒子里每一格的样子（诊断用："第1格 minecraft:stone ×3"；空格子不写） */
    private static String cells(ItemStack box) {
        List<ItemStack> items = CompressionBoxData.read(box);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).m_41619_()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('、');
            }
            sb.append('第').append(i + 1).append('格').append(name(items.get(i)));
        }
        return sb.length() == 0 ? "空盒" : sb.toString();
    }

    private static ItemStack stack(String id, int n) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
        return item == null ? ItemStack.f_41583_ : new ItemStack(item, n);
    }

    private static String name(ItemStack s) {
        if (s.m_41619_()) {
            return "空";
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(s.m_41720_());
        return (id == null ? String.valueOf(s.m_41720_()) : id.toString()) + " ×" + s.m_41613_();
    }

    private static Component pass(String what) {
        return Component.m_237113_("\u00a7a[PASS] \u00a7f" + what);
    }

    private static Component fail(String what) {
        return Component.m_237113_("\u00a7c[FAIL] \u00a7f" + what);
    }

    private static Component skip(String what) {
        return Component.m_237113_("\u00a7e[SKIP] \u00a7f" + what);
    }
}
