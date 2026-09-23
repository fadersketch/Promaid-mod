package com.maidsmart.box;

import com.github.tartaricacid.touhoulittlemaid.api.task.meal.IMaidMeal;
import com.github.tartaricacid.touhoulittlemaid.api.task.meal.MaidMealType;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.meal.MaidMealManager;
import com.github.tartaricacid.touhoulittlemaid.util.ItemsUtil;
import com.maidsmart.ProMaidMod;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.wrapper.PlayerMainInvWrapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 压缩盒自检（v1.2.2 实测六百一十七；六百一十八扩到四条需求）——
 * {@code /maid_smart box check [女仆]} 干活的地方。
 *
 * ── 为什么要有这个东西 ──
 * 六百一十七那批修的是「收纳箱可以把自己装进去，导致被卡掉」。这个 bug 的入口是
 * **界面的一次点击**，而本仓的自动化回归只有专用服务端（没有客户端、点不了界面）
 * ——也就是说，光靠 {@code test_*.py} 那套根本碰不到出事的那条路，很容易「改完了、
 * 测过了、其实没修」。所以这里用**假的玩家**（{@code FakePlayerFactory}）在服务端把
 * 那条路原样走一遍：假玩家的背包里放东西、调**界面点击真正调用的那个方法**
 * （{@link CompressionBoxService#handle}），再逐条对账。
 *
 * ── 六百一十八批自检覆盖的四件事（对应用户的四条反馈）──
 * <ol>
 *   <li><b>「盒子里放食物她不吃、放弹药/TNT 她认」</b> → {@link #maidView}：
 *       拿**TLM 自己的判据**（{@code MaidMealManager.getMaidMeals(HEAL_MEAL).canMaidEat}）
 *       去问她那两条「可用背包」里找不找得到盒子里的食物。找不到 = 就是用户报的那条
 *       （女仆自己吃饭走 {@code getAvailableBackpackInv}，六百一十六只注入了
 *       {@code getMaidInv}）；再配一条对照（盒子里没有的东西必须找不到）；</li>
 *   <li><b>「附魔类物品存进去会消失」</b> → {@link #stackSafety} + {@link #maidView}：
 *       不可堆叠的东西（附魔书）一格存了 2 个时，交给原版的**必须是合法堆**
 *       （{@code ≤ getMaxStackSize()}）——不然原版会只收下 1 个、剩下的作为返回值退回去，
 *       而调用点大多不看返回值 = 凭空消失。另配一条「自定义数据过一圈盒子还在」的
 *       往返（用 {@code isSameItemSameComponents} 判，不是只看物品 id）；</li>
 *   <li><b>「像箱子一样用鼠标拖进去」</b> → {@link #mouseDrag}：左键拿起 → 点盒子格放下 →
 *       再拿回来 → Shift+左键快速移动，以及「鼠标上拿着一个盒子去点盒子格必须被拒」
 *       这条新入口；</li>
 *   <li><b>「手上那一叠不许丢」</b> → {@link #mouseDrag} 的最后一条：挂着一叠时收手
 *       （{@link CompressionBoxService#CARRY_DROP}）→ 东西回到背包，一件不少。</li>
 * </ol>
 * 女仆那几条（给了女仆才跑）在**她真实的背包视图**上走，最后把试出来的东西清干净
 * ——自检不在任何人的背包里留东西。
 *
 * 【1.21.1 与 1.20.1 的差异】附魔在 1.21 是**数据驱动**的（{@code Enchantments} 不再是
 * 编译期常量类，要用注册表 + {@code Holder}），而这条自检验的从来不是"附魔"本身，
 * 而是「**不可堆叠 + 带 NBT 的物品**过一圈盒子」。所以 1.21 这一侧改用
 * {@code DataComponents.CUSTOM_DATA} 当那个 NBT：同样是 maxStackSize=1 的附魔书 +
 * 一段自定义数据，判据与 1.20.1 侧逐字对应（那边写的是真附魔 + 自定义标签）。
 */
public final class CompressionBoxCheck {

    private CompressionBoxCheck() {
    }

    /** 跑一遍自检；返回逐行结果（[PASS]/[FAIL]/[SKIP] 打头，后面是证据） */
    public static List<Component> run(ServerLevel level, EntityMaid maid) {
        List<Component> out = new ArrayList<>();
        try {
            dataLayer(out);
            stackSafety(out);
            playerClicks(out, level);
            mouseDrag(out, level);
            refuseGate(out, level, maid);
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

    /**
     * 「不可堆叠」这一类（原版堆叠上限 = 1）的探针：一把**没附魔**的钻石剑 + 一段自定义数据
     * （v1.2.4 实测六百四十五）。
     *
     * 【为什么不再是附魔书】六百二十 ~ 六百四十四 之间这里的探针是一本附魔书，靠一个临时
     * 开关（{@code compressionBox.refuseEnchanted}）把它放进盒子跑那段回归。六百四十五 起
     * 附魔物品**一律禁入**、开关已删，附魔书再也进不去盒子，所以换成同样不可堆叠、同样能挂
     * 自定义数据的钻石剑——验的还是那一件事：**交给原版的堆必须合法**（盒子的自定义数量与
     * 原版 {@code getMaxStackSize()=1} 对不上时，多出来的那份会被原版当返回值丢掉，而
     * 调用点大多不看返回值）。
     */
    private static ItemStack unstackableProbe() {
        ItemStack probe = stack("minecraft:diamond_sword", 1);
        if (probe.isEmpty()) {
            return ItemStack.EMPTY;
        }
        try {
            CompoundTag tag = new CompoundTag();
            tag.putInt("probe", 645);
            probe.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        } catch (Throwable ignored) {
        }
        return probe;
    }

    /* ==================== ① 数据层：盒子不许进盒子 ==================== */

    private static void dataLayer(List<Component> out) {
        List<ItemStack> items = CompressionBoxData.empty();
        items.set(0, stack("minecraft:stone", 3));
        ItemStack box = boxWith(3);
        long before = CompressionBoxData.totalCount(items);

        // 先验"写进去再读回来还是原来的数"——数量口径一旦差 1（grow 不是 setCount），
        // 后面每一条都会跟着错，这一条把它单拎出来
        out.add(content(box) == 3
                ? pass("读写往返：造一个装 3 个石头的盒子，读回来 " + content(box) + " 个（" + cells(box) + "）")
                : fail("读写往返不对：装 3 个读回来 " + content(box) + " 个（" + cells(box) + "）"));

        ItemStack left = CompressionBoxData.merge(items, box);
        boolean ok = CompressionBoxData.isBox(left) && left.getCount() == 1
                && CompressionBoxData.totalCount(items) == before
                && CompressionBoxData.usedSlots(items) == 1;
        out.add(ok ? pass("数据层 merge：压缩盒原样退回（盒子里还是 " + before + " 个石头、占 1 格）")
                : fail("数据层 merge：竟然收下了压缩盒（退回 " + name(left) + "，盒子现在 "
                + CompressionBoxData.totalCount(items) + " 个）"));

        ItemStack left2 = CompressionBoxData.mergeInto(items, 1, box);
        out.add(CompressionBoxData.isBox(left2)
                && items.get(1).isEmpty()
                ? pass("数据层 mergeInto：压缩盒原样退回（目标第 2 格仍为空）")
                : fail("数据层 mergeInto：压缩盒被塞进了第 2 格（" + name(items.get(1)) + "）"));

        // 对照：同一套方法收普通物品必须没问题——否则上面两条"被拒"说明不了任何事
        ItemStack left3 = CompressionBoxData.merge(items, stack("minecraft:stone", 5));
        out.add(left3.isEmpty() && CompressionBoxData.totalCount(items) == before + 5
                ? pass("对照：同一套方法收普通物品正常（5 个石头并进去 → 共 "
                + CompressionBoxData.totalCount(items) + " 个）")
                : fail("对照失败：普通物品都塞不进去了（退回 " + name(left3) + "，盒子现在 "
                + CompressionBoxData.totalCount(items) + " 个，应为 " + (before + 5) + "）"));
    }

    /* ==================== ② 不可堆叠：交给原版的必须是合法堆 ==================== */

    /**
     * 六百一十八【用户报的「附魔类物品存进去会消失」】。
     *
     * 根因不是"存"这一步，而是**读出去**那一步：盒子一格能堆 114514 个，两件同名（附魔书
     * 则是同附魔）的东西存进同一格，那一格的堆就是 {@code ×2}；而它们的原版堆叠上限是 **1**。
     * 六百一十六起女仆那一侧的视野封顶写死 64，于是原版拿到了一个「2 个的附魔书」这种
     * **非法堆**——原版任何一处「插进背包/手里」都会按上限 1 收下 1 个、把剩下的当返回值
     * 退回，而 TLM 那几十处调用点大多不看返回值（正常世界里它们的入参永远装得下）：
     * 多出来的那个就这样没了。所以这里验两件事：
     * <ul>
     *   <li>不可堆叠的东西过一圈盒子，自定义数据**原样还在**
     *       （{@code isSameItemSameComponents} 判的是整个组件集，不是只看 id）；</li>
     *   <li>一格里有 2 个时，{@link CompressionBoxData#viewCap} 只放 1 个出去
     *       ——**交给原版的堆永远合法**。</li>
     * </ul>
     *
     * 【探针为什么是钻石剑】见 {@link #unstackableProbe}：六百四十五 起附魔物品一律禁入，
     * 附魔书再也进不去盒子，探针换成一把没附魔的钻石剑（同样不可堆叠）。
     */
    private static void stackSafety(List<Component> out) {
        ItemStack probe = unstackableProbe();
        if (probe.isEmpty()) {
            out.add(skip("不可堆叠那条：这台机器上没有 minecraft:diamond_sword，跳过"));
            return;
        }
        stackSafetyBody(out, probe);
    }

    private static void stackSafetyBody(List<Component> out, ItemStack probe) {
        // 一格存两个（同一份组件 → 会并到同一格）
        List<ItemStack> items = CompressionBoxData.empty();
        items.set(0, probe.copy());
        ItemStack left = CompressionBoxData.merge(items, probe.copy());
        out.add(left.isEmpty() && items.get(0).getCount() == 2
                ? pass("不可堆叠那条：两把同款钻石剑并进一格，盒子里那格 = ×2（真实数量 2，"
                + "这一层大数量只活在盒子的数据组件里）")
                : fail("不可堆叠那条：并格不对（退回 " + name(left) + "，那格 "
                + name(items.get(0)) + "）"));

        int cap = CompressionBoxData.viewCap(items.get(0));
        out.add(cap == 1
                ? pass("不可堆叠那条：这格的「视野上限」= 1（= 钻石剑自己的堆叠上限，"
                + "原版口径 getMaxStackSize）——交给原版的堆合法，不会再被退回一半丢掉")
                : fail("不可堆叠那条：视野上限算成 " + cap + "，应该是 1（钻石剑不可堆叠）"));

        // 存进盒子再读回来：整个组件必须一模一样
        ItemStack box = new ItemStack(ProMaidMod.COMPRESSION_BOX.get());
        List<ItemStack> one = CompressionBoxData.empty();
        one.set(2, probe.copy());
        CompressionBoxData.write(box, one);
        ItemStack back = CompressionBoxData.read(box).get(2);
        out.add(ItemStack.isSameItemSameComponents(back, probe)
                ? pass("不可堆叠那条：钻石剑过一圈盒子（写组件→读回来）整个组件一模一样"
                + "（isSameItemSameComponents 判的是全部组件，不是只看 id）")
                : fail("不可堆叠那条：钻石剑过一圈盒子就变了：进去 " + data(probe)
                + "，出来 " + data(back)));

        // 对照组：普通物品的视野上限仍是 64（别为了修附魔把大堆的口径一起改小）
        out.add(CompressionBoxData.viewCap(stack("minecraft:stone", 1)) == CompressionBoxData.MAID_VIEW_CAP
                ? pass("对照：普通物品的视野上限仍是 " + CompressionBoxData.MAID_VIEW_CAP + "（大堆口径没被改小）")
                : fail("对照失败：普通物品的视野上限变成 "
                + CompressionBoxData.viewCap(stack("minecraft:stone", 1)) + " 了"));
    }

    /* ==================== ③ 界面点击那条路（假玩家） ==================== */

    private static void playerClicks(List<Component> out, ServerLevel level) {
        FakePlayer fp = FakePlayerFactory.getMinecraft(level);
        IItemHandlerModifiable inv = new PlayerMainInvWrapper(fp.getInventory());
        clear(fp, inv);
        ItemStack boxA = boxWith(3);   // 手上这个（界面上"打开的"就是它）
        ItemStack boxB = boxWith(7);   // 背包里另一个
        fp.setItemInHand(InteractionHand.MAIN_HAND, boxA);
        inv.setStackInSlot(12, boxB);
        int handSlot = handSlot(fp, inv);

        boolean changed1 = CompressionBoxService.handle(fp, 0, CompressionBoxService.DEPOSIT_STACK, 12);
        boolean ok1 = !changed1
                && CompressionBoxData.isBox(inv.getStackInSlot(12))
                && CompressionBoxData.isBox(fp.getItemInHand(InteractionHand.MAIN_HAND))
                && content(boxA) == 3 && content(inv.getStackInSlot(12)) == 7;
        out.add(ok1 ? pass("点击路径：把背包里另一个盒子（第 12 格）Shift 存入 → 被拒，两个盒子都还在、"
                + "里面的东西没动（" + cells(boxA) + " / " + cells(inv.getStackInSlot(12)) + "）")
                : fail("点击路径：把另一个盒子存进去没被拦住（changed=" + changed1 + "，第 12 格 "
                + name(inv.getStackInSlot(12)) + "，手上 " + name(fp.getItemInHand(InteractionHand.MAIN_HAND))
                + "，A " + cells(boxA) + "，B " + cells(inv.getStackInSlot(12)) + "）"));

        if (handSlot < 0) {
            out.add(skip("点击路径（自己那一格）：手上那件在背包里找不到对应槽位，跳过"));
        } else {
            boolean changed2 = CompressionBoxService.handle(fp, 0, CompressionBoxService.DEPOSIT_ONE, handSlot);
            boolean ok2 = !changed2
                    && CompressionBoxData.isBox(fp.getItemInHand(InteractionHand.MAIN_HAND))
                    && content(fp.getItemInHand(InteractionHand.MAIN_HAND)) == 3;
            out.add(ok2 ? pass("点击路径：Shift+点**自己那一格**（背包第 " + handSlot
                    + " 格 = 手上这个盒子）→ 被拒，盒子还在手上、里面还是 3 个 —— "
                    + "这条就是用户报的「把自己装进去被卡掉」")
                    : fail("点击路径：盒子把自己装进去了（changed=" + changed2 + "，手上 "
                    + name(fp.getItemInHand(InteractionHand.MAIN_HAND)) + "，A " + cells(boxA) + "）"));
        }

        inv.setStackInSlot(13, stack("minecraft:cobblestone", 5));
        boolean changed3 = CompressionBoxService.handle(fp, 0, CompressionBoxService.DEPOSIT_STACK, 13);
        boolean ok3 = changed3 && inv.getStackInSlot(13).isEmpty()
                && content(boxA) == 3 + 5;
        out.add(ok3 ? pass("对照：同一只手、同一个动作，存普通物品（第 13 格 5 个圆石）→ 成功，"
                + "盒子里现在 " + content(boxA) + " 个、来源格已空")
                : fail("对照失败：普通物品也存不进去了（changed=" + changed3 + "，A " + cells(boxA)
                + "，第 13 格 " + name(inv.getStackInSlot(13)) + "）"));

        long sum = content(boxA) + content(inv.getStackInSlot(12));
        out.add(sum == 15L
                ? pass("对账：两个盒子合计 " + sum + " 个（自检开始 3+7=10，加上对照存进去的 5）——没有东西凭空消失")
                : fail("对账失败：两个盒子合计 " + sum + " 个，应该是 15 个"));

        clear(fp, inv);
    }

    /* ==================== ④ 箱子式鼠标取放（六百一十八） ==================== */

    /**
     * 报的是用户那句「可以像往箱子存东西一样，玩家可以通过用鼠标的方式将物品拖进去」。
     * 界面那一层（光标画在哪、点了哪个像素）测不了——**但界面点下去真正执行的那条路
     * 全在这里**：{@link CompressionBoxService#SLOT_CLICK} 用「槽位号 + 左右键 + Shift」
     * 表示一次点击，盒子格是 0..4、背包格是 5+槽位号。
     *
     * 一步步走一遍玩家会做的事，每一步都查对账（东西只在盒子/背包/鼠标三处之间搬）。
     */
    private static void mouseDrag(List<Component> out, ServerLevel level) {
        FakePlayer fp = FakePlayerFactory.getMinecraft(level);
        IItemHandlerModifiable inv = new PlayerMainInvWrapper(fp.getInventory());
        clear(fp, inv);
        ItemStack box = boxWith(20);           // 盒子第 1 格 = 20 个石头
        fp.setItemInHand(InteractionHand.MAIN_HAND, box);
        inv.setStackInSlot(13, stack("minecraft:cobblestone", 5));

        // ① 左键点背包第 13 格 = 拿起（挂到鼠标上）
        boolean c1 = CompressionBoxService.handle(fp, 0, CompressionBoxService.SLOT_CLICK,
                CompressionBoxData.SLOTS + 13, 0, false);
        ItemStack carry1 = CompressionBoxService.carryOf(fp);
        out.add(c1 && carry1.getCount() == 5 && inv.getStackInSlot(13).isEmpty()
                ? pass("鼠标取放①：左键点背包第 13 格（5 个圆石）→ 拿起来了（鼠标上 ×5），来源格空了")
                : fail("鼠标取放①：拿起失败（changed=" + c1 + "，鼠标上 " + name(carry1)
                + "，第 13 格 " + name(inv.getStackInSlot(13)) + "）"));

        // ② 左键点盒子第 2 格 = 放下
        boolean c2 = CompressionBoxService.handle(fp, 0, CompressionBoxService.SLOT_CLICK, 1, 0, false);
        ItemStack carry2 = CompressionBoxService.carryOf(fp);
        List<ItemStack> inBox = CompressionBoxData.read(fp.getItemInHand(InteractionHand.MAIN_HAND));
        out.add(c2 && carry2.isEmpty() && inBox.get(1).getCount() == 5
                ? pass("鼠标取放②：再左键点盒子第 2 格 → 放下了（那里现在 "
                + name(inBox.get(1)) + "），鼠标空了")
                : fail("鼠标取放②：放下失败（changed=" + c2 + "，鼠标上 " + name(carry2)
                + "，盒子第 2 格 " + name(inBox.get(1)) + "）"));

        // ③ 左键点盒子第 2 格 = 再拿回来，然后左键点背包第 14 格放下（换一格）
        CompressionBoxService.handle(fp, 0, CompressionBoxService.SLOT_CLICK, 1, 0, false);
        boolean c3 = CompressionBoxService.handle(fp, 0, CompressionBoxService.SLOT_CLICK,
                CompressionBoxData.SLOTS + 14, 0, false);
        ItemStack carry3 = CompressionBoxService.carryOf(fp);
        List<ItemStack> inBox3 = CompressionBoxData.read(fp.getItemInHand(InteractionHand.MAIN_HAND));
        out.add(c3 && carry3.isEmpty() && inv.getStackInSlot(14).getCount() == 5
                && inBox3.get(1).isEmpty()
                ? pass("鼠标取放③：从盒子第 2 格拿回来、再点背包第 14 格 → 落在第 14 格（"
                + name(inv.getStackInSlot(14)) + "），盒子那一格空了")
                : fail("鼠标取放③：搬回来不对（changed=" + c3 + "，鼠标上 " + name(carry3)
                + "，第 14 格 " + name(inv.getStackInSlot(14)) + "，盒子第 2 格 "
                + name(inBox3.get(1)) + "）"));

        // ④ Shift+左键点盒子第 1 格 = 原版"快速移动"（进背包，最多 64）
        boolean c4 = CompressionBoxService.handle(fp, 0, CompressionBoxService.SLOT_CLICK, 0, 0, true);
        List<ItemStack> inBox4 = CompressionBoxData.read(fp.getItemInHand(InteractionHand.MAIN_HAND));
        int moved = 0;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.isEmpty() && isId(s, "minecraft:stone")) {
                moved += s.getCount();
            }
        }
        out.add(c4 && inBox4.get(0).isEmpty() && moved == 20
                ? pass("鼠标取放④：Shift+左键点盒子第 1 格 → 20 个石头全进背包（原版「快速移动」），盒子那格空了")
                : fail("鼠标取放④：快速移动不对（changed=" + c4 + "，背包里石头 " + moved
                + " 个，盒子第 1 格 " + name(inBox4.get(0)) + "）"));

        // ⑤ 六百二十【用户要的「压缩盒在这个界面内无法被鼠标选中」】：左键点背包里的压缩盒
        //    → 拿不起来（鼠标仍然空着、那一格还是盒子），并且服务端留下一句提示
        inv.setStackInSlot(15, boxWith(1));
        boolean c5 = CompressionBoxService.handle(fp, 0, CompressionBoxService.SLOT_CLICK,
                CompressionBoxData.SLOTS + 15, 0, false); // 想把它拿到鼠标上
        ItemStack carry5 = CompressionBoxService.carryOf(fp);
        boolean boxStillThere = CompressionBoxData.isBox(inv.getStackInSlot(15));
        String notice5 = CompressionBoxService.noticeOf(fp);
        out.add(!c5 && carry5.isEmpty() && boxStillThere
                && CompressionBoxFilter.MSG_BOX.equals(notice5)
                ? pass("鼠标取放⑤（六百二十，用户要的那条）：左键点背包里的压缩盒（第 15 格）"
                + " → **拿不起来**（鼠标仍空、那一格还是盒子），服务端回了一句「" + notice5 + "」"
                + "—— 这个界面里鼠标选不中压缩盒")
                : fail("鼠标取放⑤：压缩盒还是被拿到鼠标上了（changed=" + c5 + "，鼠标上 "
                + name(carry5) + "，第 15 格 " + name(inv.getStackInSlot(15))
                + "，提示=" + notice5 + "）"));

        // ⑤b 鼠标上先挂着别的东西，再去点它 —— 走的是「交换」那一路，同样不许把盒子顶到鼠标上
        CompressionBoxService.handle(fp, 0, CompressionBoxService.SLOT_CLICK,
                CompressionBoxData.SLOTS + 14, 0, false); // 先把第 14 格那 5 个圆石拿起来
        ItemStack carryB = CompressionBoxService.carryOf(fp);
        boolean c5b = CompressionBoxService.handle(fp, 0, CompressionBoxService.SLOT_CLICK,
                CompressionBoxData.SLOTS + 15, 0, false); // 手上拿着圆石去点压缩盒那一格
        ItemStack carryB2 = CompressionBoxService.carryOf(fp);
        out.add(!c5b && carryB.getCount() == 5 && carryB2.getCount() == 5
                && CompressionBoxData.isBox(inv.getStackInSlot(15))
                ? pass("鼠标取放⑤b：鼠标上挂着 5 个圆石再去点压缩盒那一格 → 也不换"
                + "（鼠标上还是那 5 个圆石、压缩盒还在原地）——「交换」那条路同样选不中它")
                : fail("鼠标取放⑤b：交换那一路把压缩盒顶到鼠标上了（changed=" + c5b
                + "，鼠标上 " + name(carryB2) + "，第 15 格 " + name(inv.getStackInSlot(15)) + "）"));

        // ⑤c 对照：同一叠圆石放进**盒子格**必须没问题（别为了拦压缩盒把正常搬运一起堵死）
        boolean c5c = CompressionBoxService.handle(fp, 0, CompressionBoxService.SLOT_CLICK, 2, 0, false);
        List<ItemStack> inBox5 = CompressionBoxData.read(fp.getItemInHand(InteractionHand.MAIN_HAND));
        out.add(c5c && CompressionBoxService.carryOf(fp).isEmpty() && inBox5.get(2).getCount() == 5
                ? pass("对照：同一叠圆石点盒子第 3 格 → 正常放下（那里现在 " + name(inBox5.get(2)) + "）")
                : fail("对照失败：普通物品都放不进去了（changed=" + c5c + "，鼠标上 "
                + name(CompressionBoxService.carryOf(fp)) + "，第 3 格 " + name(inBox5.get(2)) + "）"));

        // ⑥ 手上那一叠还回去：点空白处 / 关界面走的就是这条
        inv.setStackInSlot(16, stack("minecraft:dirt", 3));
        CompressionBoxService.handle(fp, 0, CompressionBoxService.SLOT_CLICK,
                CompressionBoxData.SLOTS + 16, 0, false); // 拿起 3 个土
        boolean before = !CompressionBoxService.carryOf(fp).isEmpty();
        CompressionBoxService.handle(fp, 0, CompressionBoxService.CARRY_DROP, 0, 0, false);
        boolean c6 = CompressionBoxService.carryOf(fp).isEmpty();
        int backDirt = 0;
        // 数一数背包里有几个盒子：手上那个 + 第 15 格那个 = 2
        int boxes = 0;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (CompressionBoxData.isBox(s)) {
                boxes++;
            }
            if (isId(s, "minecraft:dirt")) {
                backDirt += s.getCount();
            }
        }
        out.add(before && c6 && boxes == 2 && backDirt == 3
                ? pass("鼠标取放⑥：挂着一叠时收手（关界面/ESC 走的那条）→ 东西回到背包（3 个土一件不少，"
                + "背包里 " + boxes + " 个盒子）、鼠标空了（**任何路径都不许把它弄丢**）")
                : fail("鼠标取放⑥：收手没还回去（收手前挂着=" + before + "，收手后鼠标空=" + c6
                + "，背包里盒子数=" + boxes + "，应为 2）"));

        clear(fp, inv);
    }

    /* ==================== ⑤ 女仆那一侧的背包视图 ==================== */

    private static void maidView(List<Component> out, EntityMaid maid) {
        IItemHandler inv = maid.getMaidInv();
        if (!(inv instanceof CompressionBoxMaidInv view) || view.boxCount() <= 0) {
            out.add(skip("女仆那一条：她背包里没有压缩盒（或压缩盒的女仆延伸关着），视图就是原版背包"));
            return;
        }
        int base = view.baseSlots();                 // 她自己的 36 格之后就是盒子格
        int free = -1;
        for (int i = base; i < view.getSlots(); i++) {
            if (view.getStackInSlot(i).isEmpty()) {
                free = i;
                break;
            }
        }
        if (free >= 0) {
            foodVisible(out, maid, view, free, free - base + 1);
        } else {
            out.add(skip("食物那条：她盒子里 5 格全满，没有空格子可放测试食物（腾一格再跑）"));
        }
        if (free < 0) {
            out.add(skip("女仆那一条：她盒子里 5 格全满，没有空格子可试（腾一格再跑）"));
            return;
        }
        int cell = free - base + 1;

        ItemStack left = view.insertItem(free, boxWith(1), false);
        out.add(CompressionBoxData.isBox(left) && view.getStackInSlot(free).isEmpty()
                ? pass("女仆那一条：往她盒子第 " + cell + " 格（视图第 " + free + " 格）插压缩盒 → "
                + "原样退回、那格还是空的")
                : fail("女仆那一条：压缩盒被插进她背包里的盒子了（退回 " + name(left) + "，那格现在 "
                + name(view.getStackInSlot(free)) + "）"));

        ItemStack left2 = view.insertItem(free, stack("minecraft:stone", 5), false);
        out.add(left2.isEmpty() && view.getStackInSlot(free).getCount() == 5
                ? pass("对照：同一格插 5 个石头 → 成功（她那一侧收东西的路是通的）")
                : fail("对照失败：石头也插不进去（退回 " + name(left2) + "，那格现在 "
                + name(view.getStackInSlot(free)) + "）"));

        // 取出侧也顺手证一下：同一格拿走 4 个应当成功（别为了一个 bug 把正常路一起堵死）
        ItemStack got = view.extractItem(free, 4, false);
        out.add(got.getCount() == 4 && view.getStackInSlot(free).getCount() == 1
                ? pass("取出侧：同一格拿走 4 个 → 成功（那格现在剩 1 个）")
                : fail("取出侧不对：拿回 " + name(got) + "，那格现在 "
                + name(view.getStackInSlot(free))));

        view.setStackInSlot(free, ItemStack.EMPTY); // 自检不留痕
        unstackableView(out, view, free, cell);
        out.add(view.getStackInSlot(free).isEmpty()
                ? pass("收尾：那一格已清回空（自检不在她背包里留东西）")
                : fail("收尾失败：那格没清干净（" + name(view.getStackInSlot(free)) + "）"));
    }

    /**
     * 用户第 1 条：「往压缩盒里面加食物，女仆是不会去吃的。只是往里面加弹药以及TNT这些会认。」
     *
     * 【为什么这条一定要拿 TLM 自己的判据来验】女仆自己吃饭走的是
     * {@code EntityMaid.getAvailableBackpackInv()}（{@code MaidHealSelfTask} 拿它逐格找
     * 「能吃的那个」，判据就是 meal 系统的 {@code canMaidEat}），而那条路读的是
     * {@code maidInv} **字段**——六百一十六那批只注入了 {@code getMaidInv()}，所以盒子里的
     * 食物她**根本看不见**；弹药/TNT 是我们自己的代码在 {@code getMaidInv()} 上找的，
     * 一直看得见。这就是"只认弹药不认饭"的由来。
     *
     * 【为什么是"插进去、同一拍扫、再清掉"，而不是"扫盒子里本来就有的那份"】
     * 六百一十八第一轮用的是后者，结果食物那条红着、看着像没修——其实是**她把那份熟牛肉
     * 吃了**（这条修好之后她真能吃到盒子里的饭！）。判据本身与血量无关（{@code canMaidEat}
     * 只看能不能吃），所以自己插一份同一拍扫，既确定性又能证明"她那条路看得见盒子"。
     * （"她真的会去吃"另有一条端到端的行为用例，在 test_box618.py 的 A 段：把她打伤、
     * 等着看盒子里的饭少没少、她的血回没回。）
     */
    private static void foodVisible(List<Component> out, EntityMaid maid, CompressionBoxMaidInv view,
                                    int free, int cell) {
        List<IMaidMeal> meals;
        try {
            meals = MaidMealManager.getMaidMeals(MaidMealType.HEAL_MEAL);
        } catch (Throwable t) {
            out.add(skip("食物那条：这台机器上取不到 TLM 的 HEAL_MEAL 餐食实现，跳过"));
            return;
        }
        if (meals.isEmpty()) {
            out.add(skip("食物那条：TLM 没注册 HEAL_MEAL（女仆不会自己吃），跳过"));
            return;
        }
        java.util.function.Predicate<ItemStack> edible = s -> {
            if (s.isEmpty()) {
                return false;
            }
            try {
                for (IMaidMeal meal : meals) {
                    if (meal.canMaidEat(maid, s, InteractionHand.MAIN_HAND)) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
            return false;
        };
        // 盒子那一侧现在必须**接在她的可用背包后面**（六百一十八注入 getAvailableBackpackInv）
        var backpack = maid.getAvailableBackpackInv();
        // 【诊断】三条路的格数一起打出来：哪条没接上盒子，一眼就能看出来
        // （实测六百一十八第一版就是在这里看见"可用背包 11 格"的）
        String sizes = "（getMaidInv=" + maid.getMaidInv().getSlots()
                + "、getAvailableBackpackInv=" + backpack.getSlots()
                + "、getAvailableInv(false)=" + maid.getAvailableInv(false).getSlots()
                + "、getAvailableInv(true)=" + maid.getAvailableInv(true).getSlots()
                + "；她自己的背包 " + view.baseSlots() + " 格 + 盒子 " + view.boxCount() + " 个）";

        // ① 往她盒子里那个空格子插一份熟牛肉（走她那一侧的 insertItem），**同一拍**扫
        ItemStack beef = stack("minecraft:cooked_beef", 4);
        view.insertItem(free, beef.copy(), false);
        int found = ItemsUtil.findStackSlot(backpack, edible);
        // 【判据里"盒子那一段"从哪开始】不是 view.baseSlots()（那是**她自己背包**的 36 格），
        // 而是「可用背包的格数 − 盒子那几格」：盒子段是接在这条 wrapper 的**最后**的
        // （女仆的背包等级可能只有 6 格——实测这台就是 6+2+5=13 —— 后面才是盒子）。
        // 第一版写成 >= view.baseSlots()，于是"扫到第 7 格"（正是盒子那一格）也判红。
        int boxStart = backpack.getSlots() - CompressionBoxData.SLOTS * view.boxCount();
        boolean sawBeef = found >= boxStart
                && isId(backpack.getStackInSlot(found), "minecraft:cooked_beef");
        view.setStackInSlot(free, ItemStack.EMPTY); // 立刻清掉，别让她真吃了
        out.add(sawBeef
                ? pass("食物那条（用户第 1 条）：往盒子第 " + cell + " 格插一份熟牛肉，"
                + "拿 TLM 自己的「能不能吃」判据扫她的**可用背包**（女仆自己吃饭走的就是这条）"
                + "→ 在视图第 " + found + " 格扫到了（盒子段从第 " + boxStart
                + " 格起）—— 盒子里放饭她看得见了" + sizes)
                : fail("食物那条：她看不到盒子里的熟牛肉（扫出来 slot=" + found
                + "，盒子段从第 " + boxStart + " 格起）" + sizes
                + "—— 这正是用户报的「放食物她不吃」"));

        // ② 对照：**没放进去**的东西必须扫不到（否则上面那条等于"扫啥都有"）
        int notFound = ItemsUtil.findStackSlot(backpack, s -> isId(s, "minecraft:cake"));
        out.add(notFound < 0
                ? pass("食物对照：盒子里没有的东西（蛋糕）扫出来 -1 —— 上面那条不是「扫啥都有」")
                : fail("食物对照失败：盒子里没有蛋糕却扫到了第 " + notFound + " 格"));

        // ③ 对照：弹药（走 getAvailableInv 的那条路）——插一份光谱箭，同一拍扫，
        //    必须出现在「她自己那几格之后」= 盒子那一段
        ItemStack arrow = stack("minecraft:spectral_arrow", 4);
        view.insertItem(free, arrow.copy(), false);
        var avail = maid.getAvailableInv(false);
        int ammo = -1;
        for (int i = 0; i < avail.getSlots(); i++) {
            if (isId(avail.getStackInSlot(i), "minecraft:spectral_arrow")) {
                ammo = i;
                break;
            }
        }
        view.setStackInSlot(free, ItemStack.EMPTY);
        int availBoxStart = avail.getSlots() - CompressionBoxData.SLOTS * view.boxCount();
        out.add(ammo >= availBoxStart
                ? pass("弹药对照：同一拍插进去的光谱箭也在「可用背包」第 " + ammo
                + " 格扫到了（盒子段从第 " + availBoxStart + " 格起）"
                + "—— 用户报的「弹药/TNT 她认」这条没被弄坏")
                : skip("弹药对照：没扫到光谱箭（第 " + ammo + " 格，盒子段从第 "
                + availBoxStart + " 格起；测试数据里放一份就会验）"));
    }

    /**
     * 用户第 2 条的**产出侧**：盒子里一格存了 2 个不可堆叠的东西时，她那一侧
     * 「看得见」和「拿得走」的都必须**只有 1 个**（原版堆叠上限）。
     *
     * 【为什么在这条上较真】给原版一个「2 个的附魔书」这种非法堆，原版按上限 1 收下 1 个、
     * 把另一个作为返回值退回；TLM 那几十处调用点大多不看返回值（正常世界不可能装不下）
     * ——那一个就凭空没了 = 用户报的「附魔物品存进去会消失」。
     *
     * 探针自 六百四十五 起换成没附魔的钻石剑（附魔书已一律禁入，见 {@link #unstackableProbe}）。
     */
    private static void unstackableView(List<Component> out, CompressionBoxMaidInv view, int free,
                                        int cell) {
        ItemStack probe = unstackableProbe();
        if (probe.isEmpty()) {
            out.add(skip("不可堆叠那条：这台机器上没有 minecraft:diamond_sword，跳过"));
            return;
        }
        unstackableViewBody(out, view, free, cell, probe);
    }

    private static void unstackableViewBody(List<Component> out, CompressionBoxMaidInv view, int free,
                                            int cell, ItemStack probe) {
        view.insertItem(free, probe.copy(), false);
        view.insertItem(free, probe.copy(), false);
        ItemStack seen = view.getStackInSlot(free);
        out.add(seen.getCount() == 1
                ? pass("不可堆叠那条（她那一侧）：往盒子第 " + cell + " 格插 2 把同款钻石剑 → "
                + "她**只看得见 1 把**（原版堆叠上限 1，交给原版的堆合法，不会退一半丢掉）")
                : fail("不可堆叠那条：她看见的是 ×" + seen.getCount() + " 的钻石剑（非法堆，"
                + "原版只会收下 1 把、剩下的丢掉）"));
        ItemStack got = view.extractItem(free, 64, false);
        out.add(got.getCount() == 1
                ? pass("不可堆叠那条（她那一侧）：一次「拿整格」（要 64）也只给 1 把 —— "
                + "手里永远不会出现非法堆")
                : fail("不可堆叠那条：一次拿出了 ×" + got.getCount() + " 把钻石剑（应为 1）"));
        view.setStackInSlot(free, ItemStack.EMPTY);
    }

    /* ==================== ⑥ 禁入判据（六百二十；六百四十五 收敛为写死的两条） ==================== */

    /**
     * 用户那条：「加一个黑名单，在压缩盒界面内无法放入附魔书/附魔武器和压缩盒，
     * 压缩盒在这个界面内无法被鼠标选中，并再次提示玩家不能把压缩盒放进压缩袋里」。
     *
     * 界面本身在专用服务器上点不了（没有客户端），但**界面每一次点击真正执行的方法**
     * 就是这里调的 {@link CompressionBoxService#handle}，所以这一条能把「放进不去」和
     * 「选不中」都验到；提示那一半看 {@link CompressionBoxService#noticeOf}
     * （随内容同步发给客户端画成红字，服务端这一侧只存那句话）。
     *
     * 六百四十五 起这里多验一条（④）：那两个配置项（「禁入带附魔的物品」开关与
     * 「禁入清单」）**必须已经从 {@code MaidSmartConfig} 上删掉**——口径写死，玩家
     * 再没有能把它关掉的入口。
     */
    private static void refuseGate(List<Component> out, ServerLevel level, EntityMaid maid) {
        ItemStack book = probeBook();
        ItemStack stone = stack("minecraft:stone", 1);
        ItemStack box = boxWith(2);

        // ① 判据本身：两种拒绝 + 一条放行
        if (book.isEmpty()) {
            out.add(skip("禁入判据①：这台机器上没有 minecraft:enchanted_book，跳过附魔那两条"));
        } else {
            String whyBook = CompressionBoxFilter.reason(book);
            out.add(CompressionBoxFilter.MSG_ENCHANT.equals(whyBook)
                    ? pass("禁入判据①：附魔书（1.21.1 的 isEnchanted() 只看 ENCHANTMENTS 组件，"
                    + "附魔书在 STORED_ENCHANTMENTS 里——所以按物品类型判）→ 拒绝：「" + whyBook + "」")
                    : fail("禁入判据①：附魔书没被拒（reason=" + whyBook + "）"));
        }
        out.add(CompressionBoxFilter.MSG_BOX.equals(CompressionBoxFilter.reason(box))
                ? pass("禁入判据①：压缩盒 → 拒绝：「" + CompressionBoxFilter.MSG_BOX + "」")
                : fail("禁入判据①：压缩盒没被拒（reason=" + CompressionBoxFilter.reason(box) + "）"));
        out.add(CompressionBoxFilter.reason(stone) == null
                ? pass("禁入判据①：对照——普通物品（石头）放行（reason=null）")
                : fail("禁入判据①：石头竟被拒了（reason=" + CompressionBoxFilter.reason(stone) + "）"));

        // ② 数据层：附魔物品进盒子被原样退回（这里是所有入口的最后一关）
        List<ItemStack> items = CompressionBoxData.empty();
        if (!book.isEmpty()) {
            ItemStack left = CompressionBoxData.mergeInto(items, 0, book.copy());
            out.add(left.getCount() == 1 && items.get(0).isEmpty()
                    ? pass("禁入判据②：数据层 mergeInto 收附魔书 → 原样退回（第 1 格仍是空的）"
                    + "—— 界面、女仆、溢出回退三条路都要过这一关")
                    : fail("禁入判据②：附魔书被塞进盒子了（退回 " + name(left) + "，第 1 格 "
                    + name(items.get(0)) + "）"));
        }
        ItemStack left2 = CompressionBoxData.mergeInto(items, 0, stone.copy());
        out.add(left2.isEmpty() && items.get(0).getCount() == 1
                ? pass("禁入判据②对照：同一方法收石头 → 正常进格（第 1 格 " + name(items.get(0)) + "）")
                : fail("禁入判据②对照失败：石头都进不去（退回 " + name(left2) + "）"));
        if (!book.isEmpty()) {
            ItemStack left3 = CompressionBoxData.merge(items, book.copy());
            out.add(left3.getCount() == 1 && CompressionBoxData.totalCount(items) == 1
                    ? pass("禁入判据②：数据层 merge 收附魔书 → 一件都没进（盒子里仍是 1 个石头）")
                    : fail("禁入判据②：merge 把附魔书收进去了（退回 " + name(left3)
                    + "，盒子现在 " + CompressionBoxData.totalCount(items) + " 件）"));
        }

        // ③ 界面点击那条路（假玩家）：Shift+左键点背包里的附魔书 → 被拒 + 一句提示
        FakePlayer fp = FakePlayerFactory.getMinecraft(level);
        IItemHandlerModifiable inv = new PlayerMainInvWrapper(fp.getInventory());
        clear(fp, inv);
        ItemStack handBox = boxWith(3);
        fp.setItemInHand(InteractionHand.MAIN_HAND, handBox);
        if (!book.isEmpty()) {
            inv.setStackInSlot(13, book.copy());
            boolean changed = CompressionBoxService.handle(fp, 0, CompressionBoxService.SLOT_CLICK,
                    CompressionBoxData.SLOTS + 13, 0, true); // Shift+左键 = 整叠存进去
            String notice = CompressionBoxService.noticeOf(fp);
            boolean still = !inv.getStackInSlot(13).isEmpty();
            out.add(!changed && still && CompressionBoxFilter.MSG_ENCHANT.equals(notice)
                    && content(handBox) == 3
                    ? pass("禁入判据③（界面那条路）：Shift+左键点背包里的附魔书 → 被拒、书还在背包里、"
                    + "盒子里还是 3 个，服务端回了「" + notice + "」")
                    : fail("禁入判据③：附魔书被 Shift 存进去了（changed=" + changed
                    + "，第 13 格 " + name(inv.getStackInSlot(13)) + "，盒子 " + content(handBox)
                    + " 件，提示=" + notice + "）"));
        } else {
            out.add(skip("禁入判据③：没有附魔书可用，跳过（界面那条路已由 ⑤/⑤b 覆盖压缩盒）"));
        }

        // ④ 六百四十五【用户要求：开关与自定义清单都删掉】——那两个配置字段必须已经
        //    从 MaidSmartConfig 上消失（反射取不到 = 删干净了）。用户原话：「把压缩盒
        //    禁止加入附魔物品的开关和选项关掉，我们始终不允许附魔物品装进压缩盒。
        //    随后是自定义哪个不允许装进去这个功能给删掉了。」
        boolean noSwitch = fieldGone("COMPRESSION_BOX_REFUSE_ENCHANTED");
        boolean noList = fieldGone("COMPRESSION_BOX_REFUSE_LIST");
        out.add(noSwitch && noList
                ? pass("禁入判据④：口径写死——配置里那两个字段"
                + "（COMPRESSION_BOX_REFUSE_ENCHANTED 开关 / COMPRESSION_BOX_REFUSE_LIST 清单）"
                + "都已经不存在（反射取不到），玩家没有能关掉「附魔禁入」的入口")
                : fail("禁入判据④：配置字段还在（开关=" + (noSwitch ? "已删" : "仍在")
                + "，清单=" + (noList ? "已删" : "仍在") + "）——附魔禁入必须是写死的"));

        // ⑤ 女仆那一侧：往她背包里的盒子插附魔书 → 原样退回（她也不会把这类东西顺手塞进盒子）
        if (maid == null) {
            out.add(skip("禁入判据⑤：没给女仆，跳过（带上女仆才会跑）"));
        } else if (book.isEmpty()) {
            out.add(skip("禁入判据⑤：没有附魔书可用，跳过"));
        } else {
            IItemHandler minv = maid.getMaidInv();
            if (!(minv instanceof CompressionBoxMaidInv view) || view.boxCount() <= 0) {
                out.add(skip("禁入判据⑤：她背包里没有压缩盒（或女仆延伸关着），跳过"));
            } else {
                int base = view.baseSlots();
                int free = -1;
                for (int i = base; i < view.getSlots(); i++) {
                    if (view.getStackInSlot(i).isEmpty()) {
                        free = i;
                        break;
                    }
                }
                if (free < 0) {
                    out.add(skip("禁入判据⑤：她盒子里 5 格全满，跳过"));
                } else {
                    ItemStack left = view.insertItem(free, book.copy(), false);
                    boolean ok = left.getCount() == 1 && view.getStackInSlot(free).isEmpty()
                            && !view.isItemValid(free, book.copy());
                    out.add(ok ? pass("禁入判据⑤（女仆那一侧）：往她盒子第 " + (free - base + 1)
                            + " 格插附魔书 → 原样退回、那格还是空的（isItemValid 也判 false）"
                            + "—— 她捡到附魔书不会顺手塞进盒子")
                            : fail("禁入判据⑤：女仆那一侧收下了附魔书（退回 " + name(left)
                            + "，那格现在 " + name(view.getStackInSlot(free)) + "）"));
                    view.setStackInSlot(free, ItemStack.EMPTY); // 自检不留痕
                }
            }
        }
        clear(fp, inv);
    }

    /** 这个配置字段是不是已经从 {@code MaidSmartConfig} 上删掉了（六百四十五 的反射探针） */
    private static boolean fieldGone(String name) {
        try {
            com.maidsmart.config.MaidSmartConfig.class.getField(name);
            return false;
        } catch (NoSuchFieldException e) {
            return true;
        } catch (Throwable ignored) {
            return false;
        }
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

    /**
     * 一本附魔书 + 一段自定义数据（"整个组件原样过一圈"的探针）。
     * 见类文档：1.21 的附魔是数据驱动的，这里用 CUSTOM_DATA 当那段数据，
     * maxStackSize=1 这一点与 1.20.1 侧的附魔书完全一致。
     *
     * 六百四十五 起它**只用来验拒绝**（{@link #refuseGate}：带附魔的物品进不去盒子）；
     * 六百二十 ~ 六百四十四 之间它还兼任「不可堆叠探针」，那个角色已交给
     * {@link #unstackableProbe}（没附魔的钻石剑）。
     */
    private static ItemStack probeBook() {
        ItemStack book = stack("minecraft:enchanted_book", 1);
        if (book.isEmpty()) {
            return ItemStack.EMPTY;
        }
        try {
            CompoundTag probe = new CompoundTag();
            probe.putInt("probe", 618);
            book.set(DataComponents.CUSTOM_DATA, CustomData.of(probe));
        } catch (Throwable ignored) {
        }
        return book;
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
            if (items.get(i).isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('、');
            }
            sb.append('第').append(i + 1).append('格').append(name(items.get(i)));
        }
        return sb.length() == 0 ? "空盒" : sb.toString();
    }

    /** 手上那件在背包里的第几格（拿不到 -1） */
    private static int handSlot(FakePlayer fp, IItemHandlerModifiable inv) {
        ItemStack inHand = fp.getItemInHand(InteractionHand.MAIN_HAND);
        for (int i = 0; i < inv.getSlots(); i++) {
            if (inv.getStackInSlot(i) == inHand) {
                return i;
            }
        }
        return -1;
    }

    /** 把假玩家收拾干净（它是**共用**的，不清会把上一次的残留算进这一次的对账） */
    private static void clear(FakePlayer fp, IItemHandlerModifiable inv) {
        for (int i = 0; i < inv.getSlots(); i++) {
            inv.setStackInSlot(i, ItemStack.EMPTY);
        }
        CompressionBoxService.returnCarry(fp);
    }

    private static boolean isId(ItemStack s, String id) {
        if (s.isEmpty()) {
            return false;
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(s.getItem());
        return key != null && key.toString().equals(id);
    }

    private static ItemStack stack(String id, int n) {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        return item == null ? ItemStack.EMPTY : new ItemStack(item, n);
    }

    private static String name(ItemStack s) {
        if (s.isEmpty()) {
            return "空";
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
        return (id == null ? String.valueOf(s.getItem()) : id.toString()) + " ×" + s.getCount();
    }

    /** 出问题时看得见组件（附魔/自定义数据都在这行里） */
    private static String data(ItemStack s) {
        return s.isEmpty() ? "空" : String.valueOf(s.getComponents());
    }

    private static Component pass(String what) {
        return Component.literal("\u00a7a[PASS] \u00a7f" + what);
    }

    private static Component fail(String what) {
        return Component.literal("\u00a7c[FAIL] \u00a7f" + what);
    }

    private static Component skip(String what) {
        return Component.literal("\u00a7e[SKIP] \u00a7f" + what);
    }
}
