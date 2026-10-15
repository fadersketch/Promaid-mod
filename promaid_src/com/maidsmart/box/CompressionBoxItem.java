package com.maidsmart.box;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 压缩盒（v1.2.2 实测六百一十六）——5 格、每格最多 114514 个的收纳道具。
 * ── 用法 ──
 * 拿在手上**右键打开**（见 {@link #m_7203_}）；放进女仆背包就是她背包的延伸
 * （{@code MaidCompressionBoxMixin} + {@link CompressionBoxMaidInv}）。
 * ── 为什么是纯道具而不是可放置的方块 ──
 * 用户给的模型是方块模型、要的机制是「和潜影箱差不多」，按理该做成方块。但
 * **编译不过**：方块实体类型必须走 {@code BlockEntityType.Builder.of(...)} 或它的
 * 公开构造器，而这两个的形参类型 {@code BlockEntityType$BlockEntitySupplier} 在
 * 1.20.1 与 1.21.1 的原版 jar 里都是**包内可见**（javap 实证：{@code interface ...}
 * 无修饰符）。Forge/NeoForge 那层的可用性靠运行期的 AccessTransformer 打开，
 * 而本仓是直接拿原版 jar 编译（没有 AT 环境）——编译期一个方法引用就报
 * 「BlockEntitySupplier 在 BlockEntityType 中是 private 访问控制」（探针实测）。
 * 用反射代理硬绕的代价（两棵树各一份、运行期还依赖 AT 是否真的生效、错了就是 放下去崩）明显大于收益，所以这一批做成**纯道具**：手上右键就能开、进女仆背包 就是她的延伸——用户点名要的那两件事都在；「拆掉会掉内容、放置形态」这类 潜影箱的方块侧行为留给以后（接口是现成的：把这个类换成 BlockItem + 补一个 方块实体，数据格式不用动）。 单独一个子类（而不是直接用 Item）只为一件事：{@link #isBox} 靠类型认， 注册表还没初始化完（mixin 早期、静态初始化顺序）也不会拿到 null。
 */
public class CompressionBoxItem extends Item {

    public CompressionBoxItem(Properties properties) {
        super(properties);
    }

    /** 这个堆是不是压缩盒 */
    public static boolean isBox(ItemStack stack) {
        return !stack.m_41619_() && stack.m_41720_() instanceof CompressionBoxItem;
    }

    /** 收纳成功的一记声音：与玩家穿装备同款（原版 {@code ARMOR_EQUIP_GENERIC}，音量/音调 1.0、
     *  声源 PLAYERS、落点取玩家自己脚下）——右键把东西收进盒子时的那一下听感就来自这里。 */
    public static void playStoreSound(Player player) {
        try {
            player.m_5496_(net.minecraft.sounds.SoundEvents.f_11675_, 1.0F, 1.0F);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("CompressionBoxItem", ignored);
        }
    }

    /**
     * 常驻附魔光效（v1.2.2 实测六百一十八，用户要求「给压缩盒打上附魔的特效，
     * 排班表里已经做过了，直接照搬」）。
     * 【为什么是重写 isFoil 而不是给它真加个附魔】原版渲染层判断「要不要画那层流光」
     * 只问 {@code ItemStack.hasFoil()} → {@code Item.isFoil(stack)}，默认实现是
     * {@code stack.isEnchanted()}。所以「恒 true」这一条就够了，物品本身不带任何附魔
     * （附魔会影响铁砧/村民交易/经验修补那些判定，用户要的只是**外观**）。 这一条与 {@code ScheduleBookItem}（排班表）和 {@code BlueprintBookItem}（手册） **逐字同源**——那两件东西上跑了好几个版本没出过问题（实测五十五纠正过 「isFoil 恒 true 会崩」的旧结论：那是误诊，手册一直是这么干的）。 【为什么压缩盒要让它亮】它装的东西玩家看不见（一格能塞 114514 个）， 手上/背包里有个一眼能认出来的记号更省事。
     */
    @Override
    public boolean m_5812_(ItemStack stack) {
        return true;
    }

    /** 右键：服务端发开屏包（内容一起带过去），客户端只负责挥手 */
    @Override
    public InteractionResultHolder<ItemStack> m_7203_(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.m_21120_(hand);
        if (!isBox(stack)) {
            return new InteractionResultHolder<>(InteractionResult.PASS, stack);
        }
        if (level.m_5776_()) {
            return new InteractionResultHolder<>(InteractionResult.SUCCESS, stack);
        }
        if (player instanceof ServerPlayer sp) {
            CompressionBoxNetworking.openFor(sp, hand, CompressionBoxData.read(stack));
        }
        return new InteractionResultHolder<>(InteractionResult.CONSUME, stack);
    }

    /**
     * 收纳袋式交互：光标上拿着压缩盒、右键点某个槽 → 把那一组装进盒子，槽里留装不下的余量。
     * <p>两棵树不一样：Neo 1.21.1 的 {@code AbstractContainerMenu.doClick} 本来就调这个钩子，
     * 这份 override 直接生效；Forge 1.20.1 原版没有任何调用方（方法名只在 ItemStack / Item /
     * BundleItem 三处定义过，原版收纳袋点击逻辑 1.20.5 才进菜单），故 Forge 侧靠
     * {@code CompressionBoxClickMixin} 在 doClick 里手动调它，否则这里是死代码。
     */
    @Override
    public boolean m_142207_(ItemStack box, net.minecraft.world.inventory.Slot slot,
                              net.minecraft.world.inventory.ClickAction action,
                              net.minecraft.world.entity.player.Player player) {
        try {
            if (action != net.minecraft.world.inventory.ClickAction.SECONDARY || box.m_41613_() != 1) {
                return false; // 只接右键；盒子一叠多个时不接管（原版收纳袋同口径）
            }
            ItemStack inSlot = slot.m_7993_();
            if (inSlot.m_41619_() || !CompressionBoxFilter.canStore(inSlot)) {
                return false; // 空槽没得装；禁入的（带附魔 / 盒子本身）不装
            }
            List<ItemStack> items = CompressionBoxData.read(box);
            ItemStack left = CompressionBoxData.merge(items, inSlot.m_41777_());
            if (left.m_41613_() == inSlot.m_41613_()) {
                return false; // 一点都没装进去（5 格满了 / 这一件不合）→ 交还原版
            }
            CompressionBoxData.write(box, items);
            slot.m_5852_(left); // 槽里留下装不下的余量
            slot.m_6654_();
            playStoreSound(player); // 收纳成功 → 一记穿装备声
            return true;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("CompressionBoxItem", ignored);
            return false;
        }
    }

    /**
     * 收纳袋式交互的反向：**盒在槽里、光标上拿着别的东西，右键点盒子** → 把光标那组装进去。
     * 与上面那条成对（原版收纳袋两个方向都收），同样只有 Neo 的原版菜单会主动调它。
     */
    @Override
    public boolean m_142305_(ItemStack box, ItemStack other, net.minecraft.world.inventory.Slot slot,
                           net.minecraft.world.inventory.ClickAction action,
                           net.minecraft.world.entity.player.Player player,
                           net.minecraft.world.entity.SlotAccess access) {
        try {
            if (action != net.minecraft.world.inventory.ClickAction.SECONDARY || box.m_41613_() != 1
                    || other.m_41619_() || !CompressionBoxFilter.canStore(other)) {
                return false;
            }
            List<ItemStack> items = CompressionBoxData.read(box);
            ItemStack left = CompressionBoxData.merge(items, other.m_41777_());
            if (left.m_41613_() == other.m_41613_()) {
                return false;
            }
            CompressionBoxData.write(box, items);
            slot.m_5852_(box); // NBT 变过 → 写回槽（触发脏标记/同步）
            slot.m_6654_();
            access.m_142104_(left); // 光标上留装不下的余量（可能为空）
            playStoreSound(player); // 收纳成功 → 一记穿装备声
            return true;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("CompressionBoxItem", ignored);
            return false;
        }
    }

    /** 物品描述：装了什么 + 两个关键口径（5 格 / 每格上限），不写满屏 */
    @Override
    public void m_7373_(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        try {
            List<ItemStack> items = CompressionBoxData.read(stack);
            long total = CompressionBoxData.totalCount(items);
            int used = CompressionBoxData.usedSlots(items);
            int cap = CompressionBoxData.maxStack();
            if (total <= 0L) {
                tooltip.add(Component.m_237113_("\u00a77空盒：\u00a7f" + CompressionBoxData.SLOTS
                        + " 格\u00a77，每格最多 \u00a7e" + cap + "\u00a77 个"));
            } else {
                tooltip.add(Component.m_237113_("\u00a77已装 \u00a7e" + total + "\u00a77 个，占 \u00a7f"
                        + used + "\u00a77/\u00a7f" + CompressionBoxData.SLOTS + "\u00a77 格"));
                for (int i = 0; i < items.size(); i++) {
                    ItemStack s = items.get(i);
                    if (s.m_41619_()) {
                        continue;
                    }
                    tooltip.add(Component.m_237113_("\u00a78" + (i + 1) + ". \u00a7f"
                            + s.m_41786_().getString() + " \u00a7e\u00d7" + s.m_41613_()));
                }
            }
            tooltip.add(Component.m_237113_("\u00a78每格上限 \u00a7e" + cap
                    + "\u00a78；右键打开；放进女仆背包时视作她背包的延伸（她一次最多拿 64）"));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("CompressionBoxItem", ignored);
            // 描述只是好看，任何异常都不该影响物品本身
        }
    }
}
