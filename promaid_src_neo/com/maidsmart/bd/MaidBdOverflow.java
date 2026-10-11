package com.maidsmart.bd;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidPickupEvent;
import com.github.tartaricacid.touhoulittlemaid.compat.extracontainer.ContainerRef;
import com.github.tartaricacid.touhoulittlemaid.compat.extracontainer.MaidContainerCache;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.tool.PromaidLog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.List;

/**
 * 超越维度·溢出入库——"她自己放不下、精妙背包也放不下"时，产物直接进数据库。
 *
 * 【为什么挂 TLM 拾取事件而非自己扫地面】TLM 本体有 {@code ExtraContainerPickupHandler}，上游
 * {@code MaidExtraContainer} 复用同一套容器 API，已覆盖精妙背包与旅行者背包。"她捡东西"三层：
 * ① 自己的背包 ② 饰品栏里的额外容器 ③（本条新增）超越维度 = 数据库。我们只补第 ③ 层：前两层都用
 * **模拟（只读）**判据判一遍，都放不下才轮到我们，故绝大多数情况一行都不改变行为。
 * 【复制 bug 教训】第 ② 层判据必须用只读的 {@code MaidExtraContainer#canAccept}
 * （{@code insert(...,true)} 模拟），**不能**用真插入的 {@code overflow}（探测时就塞进去 → 同一份
 * 物品被容器和实体各留一次 = 复制）。**探测类调用必须走"模拟"分支，"真搬"只能在确实要搬时用。**
 * 【三条安全规矩】① 只搬产物；② 只搬"多余的"：两处都收不下才入库，入库成功后才从掉落物扣**同样多**
 * （顺序反了造复制品）；③ 不取消拾取——少拿无所谓，多拿/复制不行。
 */
public final class MaidBdOverflow {

    private static boolean hooked;

    private MaidBdOverflow() {
    }

    public static void ensureHooked() {
        if (hooked) {
            return;
        }
        hooked = true;
        NeoForge.EVENT_BUS.register(new MaidBdOverflow());
    }

    @SubscribeEvent
    public void onMaidPickup(MaidPickupEvent.ItemResultPre event) {
        try {
            handle(event);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidBdOverflow", ignored);
        }
    }

    private static void handle(MaidPickupEvent.ItemResultPre event) {
        if (event.isSimulate()) {
            return;   // 模拟那一遍什么都不动（TLM 自己会用它做判定）
        }
        EntityMaid maid = event.getMaid();
        if (maid == null || maid.level().isClientSide() || !MaidBdDeposit.isOn(maid) || !MaidBdCompat.enabled()) {
            return;
        }
        ItemEntity itemEntity = event.getEntityItem();
        if (itemEntity == null || !itemEntity.isAlive()) {
            return;
        }
        ItemStack stack = itemEntity.getItem();
        if (stack.isEmpty() || !MaidBdDeposit.isProduct(stack)) {
            return;   // 只搬产物
        }
        Player owner = MaidBdCompat.ownerOf(maid);
        if (owner == null || !MaidBdCompat.available() || !MaidBdCompat.hasAnyNet(owner)) {
            return;
        }
        // ① 她自己的背包放得下吗？
        ItemStack afterSelf = simulateIntoMaidInv(maid, stack);
        if (afterSelf.isEmpty()) {
            return;
        }
        // ② 她饰品栏里的额外容器（精妙背包＝缓存）放得下吗？
        // 【实测七百七十二·复制 bug 修复】这里**只能探测、绝不能真搬**：原先调的是
        // com.maidsmart.tool.MaidExtraContainer.overflow(maid, afterSelf)，可那个方法是
        // **真插入**（源码 MaidExtraContainer.java:283,300 走 insert(...,false)）——
        // 结果"容器已经把东西收下了"，而我们随后只按**网络**收下的量去缩减地面实体，
        // 同一份物品在容器和实体里各留一次 = 复制。
        // 改用只读容量判据 canAccept（insert(...,true) 模拟，不改任何东西）：能收下就交给
        // TLM 自己的拾取链真正去收（我们这一层直接 return，什么都不动）；两层都放不下才轮到 ③。
        boolean containerCanTake;
        try {
            containerCanTake = com.maidsmart.tool.MaidExtraContainer.canAccept(maid, afterSelf);
        } catch (Throwable t) {
            containerCanTake = false;
        }
        if (containerCanTake) {
            return;   // 缓存收得下 → 交给 TLM 的拾取链，我们不动它
        }
        // ③ 两层都满了：这一份进数据库
        Object net = MaidBdCompat.primaryNet(owner);
        long remainder = MaidBdCompat.insert(net, afterSelf, afterSelf.getCount());
        if (remainder < 0) {
            return;   // 调用失败 → 什么都不改，照旧落地
        }
        int accepted = afterSelf.getCount() - (int) Math.max(0, remainder);
        if (accepted <= 0) {
            return;   // 数据库也拒收 → 照旧落地
        }
        ItemStack inEntity = itemEntity.getItem();
        if (inEntity.getCount() < accepted) {
            accepted = inEntity.getCount();   // 防御：实体这一拍变了就只拿它有的
        }
        inEntity.shrink(accepted);
        if (inEntity.isEmpty()) {
            itemEntity.discard();
        }
        PromaidLog.log("超越维度存入", maid.getName().getString() + " 溢出入库 "
                + MaidBdCompat.itemId(stack) + " × " + accepted
                + "（她背包与额外容器都满了）");
    }

    /** 模拟塞进她自己的背包，返回**没塞下的那一份**（全塞得下就是空栈）。 */
    private static ItemStack simulateIntoMaidInv(EntityMaid maid, ItemStack stack) {
        try {
            IItemHandler inv = maid.getAvailableInv(false);
            if (inv == null) {
                return stack;
            }
            ItemStack rest = stack.copy();
            for (int i = 0; i < inv.getSlots() && !rest.isEmpty(); i++) {
                rest = inv.insertItem(i, rest, true);
            }
            return rest;
        } catch (Throwable t) {
             com.maidsmart.diag.Diag.swallow("MaidBdOverflow", t); return stack;   // 判定不了就当"她放不下"，让上一层去处理（保守）
        }
    }

    /** 诊断用：报告这只女仆"现在能不能再吃下某个物品"（bd_probe 会打印）。 */
    public static String capacityReport(EntityMaid maid) {
        try {
            List<ContainerRef> refs = MaidContainerCache.getContainers(maid);
            int extra = refs == null ? 0 : Math.max(0, refs.size() - 1);
            StringBuilder sb = new StringBuilder("额外容器数=" + extra);
            if (refs != null) {
                for (int i = 1; i < refs.size(); i++) {
                    sb.append(" [").append(refs.get(i).getClass().getSimpleName()).append(']');
                }
            }
            return sb.toString();
        } catch (Throwable t) {
             com.maidsmart.diag.Diag.swallow("MaidBdOverflow", t); return "额外容器查询失败：" + t.getClass().getSimpleName();
        }
    }

    /** 物品 id（诊断用）。 */
    public static String idOf(ItemStack s) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
        return id == null ? "?" : id.toString();
    }
}
