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
 *
 * ── 用法 ──
 * 拿在手上**右键打开**（见 {@link #use}）；放进女仆背包就是她背包的延伸
 * （{@code MaidCompressionBoxMixin} + {@link CompressionBoxMaidInv}）。
 *
 * ── 为什么是纯道具而不是可放置的方块 ──
 * 用户给的模型是方块模型、要的机制是「和潜影箱差不多」，按理该做成方块。但
 * **编译不过**：方块实体类型必须走 {@code BlockEntityType.Builder.of(...)} 或它的
 * 公开构造器，而这两个的形参类型 {@code BlockEntityType$BlockEntitySupplier} 在
 * 1.20.1 与 1.21.1 的原版 jar 里都是**包内可见**（javap 实证：{@code interface ...}
 * 无修饰符）。Forge/NeoForge 那层的可用性靠运行期的 AccessTransformer 打开，
 * 而本仓是直接拿原版 jar 编译（没有 AT 环境）——编译期一个方法引用就报
 * 「BlockEntitySupplier 在 BlockEntityType 中是 private 访问控制」（探针实测）。
 * 用反射代理硬绕的代价（两棵树各一份、运行期还依赖 AT 是否真的生效、错了就是
 * 放下去崩）明显大于收益，所以这一批做成**纯道具**：手上右键就能开、进女仆背包
 * 就是她的延伸——用户点名要的那两件事都在；「拆掉会掉内容、放置形态」这类
 * 潜影箱的方块侧行为留给以后（接口是现成的：把这个类换成 BlockItem + 补一个
 * 方块实体，数据格式不用动）。
 *
 * 单独一个子类（而不是直接用 Item）只为一件事：{@link #isBox} 靠类型认，
 * 注册表还没初始化完（mixin 早期、静态初始化顺序）也不会拿到 null。
 */
public class CompressionBoxItem extends Item {

    public CompressionBoxItem(Properties properties) {
        super(properties);
    }

    /** 这个堆是不是压缩盒 */
    public static boolean isBox(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof CompressionBoxItem;
    }

    /**
     * 常驻附魔光效（v1.2.2 实测六百一十八，用户要求「给压缩盒打上附魔的特效，
     * 排班表里已经做过了，直接照搬」）。
     *
     * 【为什么是重写 isFoil 而不是给它真加个附魔】原版渲染层判断「要不要画那层流光」
     * 只问 {@code ItemStack.hasFoil()} → {@code Item.isFoil(stack)}，默认实现是
     * {@code stack.isEnchanted()}。所以「恒 true」这一条就够了，物品本身不带任何附魔
     * （附魔会影响铁砧/村民交易/经验修补那些判定，用户要的只是**外观**）。
     * 这一条与 {@code ScheduleBookItem}（排班表）和 {@code BlueprintBookItem}（手册）
     * **逐字同源**——那两件东西上跑了好几个版本没出过问题（实测五十五纠正过
     * 「isFoil 恒 true 会崩」的旧结论：那是误诊，手册一直是这么干的）。
     *
     * 【为什么压缩盒要让它亮】它装的东西玩家看不见（一格能塞 114514 个），
     * 手上/背包里有个一眼能认出来的记号更省事。
     */
    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    /** 右键：服务端发开屏包（内容一起带过去），客户端只负责挥手 */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!isBox(stack)) {
            return new InteractionResultHolder<>(InteractionResult.PASS, stack);
        }
        if (level.isClientSide()) {
            return new InteractionResultHolder<>(InteractionResult.SUCCESS, stack);
        }
        if (player instanceof ServerPlayer sp) {
            CompressionBoxNetworking.openFor(sp, hand, CompressionBoxData.read(stack));
        }
        return new InteractionResultHolder<>(InteractionResult.CONSUME, stack);
    }

    /** 物品描述：装了什么 + 两个关键口径（5 格 / 每格上限），不写满屏 */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        try {
            List<ItemStack> items = CompressionBoxData.read(stack);
            long total = CompressionBoxData.totalCount(items);
            int used = CompressionBoxData.usedSlots(items);
            int cap = CompressionBoxData.maxStack();
            if (total <= 0L) {
                tooltip.add(Component.literal("\u00a77空盒：\u00a7f" + CompressionBoxData.SLOTS
                        + " 格\u00a77，每格最多 \u00a7e" + cap + "\u00a77 个"));
            } else {
                tooltip.add(Component.literal("\u00a77已装 \u00a7e" + total + "\u00a77 个，占 \u00a7f"
                        + used + "\u00a77/\u00a7f" + CompressionBoxData.SLOTS + "\u00a77 格"));
                for (int i = 0; i < items.size(); i++) {
                    ItemStack s = items.get(i);
                    if (s.isEmpty()) {
                        continue;
                    }
                    tooltip.add(Component.literal("\u00a78" + (i + 1) + ". \u00a7f"
                            + s.getHoverName().getString() + " \u00a7e\u00d7" + s.getCount()));
                }
            }
            tooltip.add(Component.literal("\u00a78每格上限 \u00a7e" + cap
                    + "\u00a78；右键打开；放进女仆背包时视作她背包的延伸（她一次最多拿 64）"));
        } catch (Throwable ignored) {
            // 描述只是好看，任何异常都不该影响物品本身
        }
    }
}
