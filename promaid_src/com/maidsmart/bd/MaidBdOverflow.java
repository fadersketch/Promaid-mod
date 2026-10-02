package com.maidsmart.bd;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidPickupEvent;
import com.github.tartaricacid.touhoulittlemaid.compat.extracontainer.ContainerRef;
import com.github.tartaricacid.touhoulittlemaid.compat.extracontainer.MaidContainerCache;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.tool.PromaidLog;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * 实测 G-8【超越维度·溢出入库】——"她自己放不下、精妙背包也放不下"时，产物直接进数据库。
 *
 * <h2>需求方口径（逐字）</h2>
 * 「我们有没有方法能让女仆在物品栏已满的时候把东西捡进维度网络？」
 * 二选一时他选了："两层都满了才进数据库（最贴我最初的问题，行为改动最小）"，
 * 而不是"扫附近掉落物直接收进库"（他担心后者出兼容问题）。
 *
 * <h2>为什么挂在 TLM 的拾取事件上，而不是自己扫地面</h2>
 * TLM 本体有一条 {@code ExtraContainerPickupHandler}，上游的 {@code MaidExtraContainer} 也
 * 复用同一套容器 API（{@code MaidContainerCache.getContainers} → {@code ContainerRef}），
 * 已经覆盖精妙背包与旅行者背包。所以"她捡东西"这条链的**三层**是：
 * <pre>
 *   ①她自己的背包  ②她饰品栏里的额外容器（精妙背包＝缓存）  ③（本条新增）超越维度＝数据库
 * </pre>
 * 我们只补第 ③ 层：前两层**都用模拟（只读）判据**判一遍（不动任何东西），都放不下才轮到我们，
 * 因此绝大多数情况下本类一行都不改变行为（不改判定、不改数量、不取消事件）。
 *
 * <p>【实测七百七十二·复制 bug 修复】第 ② 层的判据原先用的是
 * {@code MaidExtraContainer.overflow}——那是个**真插入**方法，探测时就把东西塞进了容器，
 * 而本类只按网络收下的量缩减地面实体 ⇒ 同一份物品被容器和实体各留一次 = 复制。
 * 现改用只读的 {@link com.maidsmart.tool.MaidExtraContainer#canAccept}
 * （{@code insert(...,true)} 模拟）；能收下就直接交给 TLM 自己的拾取链真正去收，我们不动它。
 * <b>教训：探测类调用必须走"模拟"分支，"真搬"的方法只能在确实要搬时用。</b>
 *
 * <h2>三条安全规矩</h2>
 * <ol>
 *   <li><b>只搬产物</b>：判据沿用 {@link MaidBdDeposit#isProduct}。非产物（敌人掉的装备、种子、
 *       她干活要用的东西）即便放不下也照旧落地——不改变现状，也不会把垃圾推进你的网络。</li>
 *   <li><b>只搬"多余的"那一部分</b>：只有她自己的背包**和**额外容器都收不下时，我们才入库；
 *       入库成功后才从掉落物实体里扣除<b>同样多</b>（顺序反了会造出复制品）。额外容器只要还能
 *       收下一点，就整份交给 TLM 的拾取链，我们不碰。</li>
 *   <li><b>不取消拾取</b>：我们只从掉落物里拿走我们能收下的那部分，剩下的照旧由 TLM 走它自己的
 *       流程（含额外容器）。少拿一点无所谓，多拿或复制是绝对不行的。</li>
 * </ol>
 *
 * <p>【1.20.1 差异】事件总线 {@code MinecraftForge.EVENT_BUS}；拾取事件是
 * {@code MaidPickupEvent.ItemResultPre}（{@code getMaid()} / {@code isSimulate()} /
 * {@code getEntityItem()} 均在 Forge 编译 jar 实证存在）；掉落物取物 {@code m_32055_}（getItem）、
 * 丢弃 {@code m_142687_(RemovalReason.DISCARDED)}、缩小 {@code m_41774_(int)}。
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
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new MaidBdOverflow());
    }

    @SubscribeEvent
    public void onMaidPickup(MaidPickupEvent.ItemResultPre event) {
        try {
            handle(event);
        } catch (Throwable ignored) {
        }
    }

    private static void handle(MaidPickupEvent.ItemResultPre event) {
        if (event.isSimulate()) {
            return;   // 模拟那一遍什么都不动（TLM 自己会用它做判定）
        }
        EntityMaid maid = event.getMaid();
        if (maid == null || maid.m_9236_().m_5776_() || !MaidBdDeposit.isOn(maid) || !MaidBdCompat.enabled()) {
            return;
        }
        ItemEntity itemEntity = event.getEntityItem();
        if (itemEntity == null || !itemEntity.m_6084_()) {
            return;
        }
        ItemStack stack = itemEntity.m_32055_();
        if (stack.m_41619_() || !MaidBdDeposit.isProduct(stack)) {
            return;   // 只搬产物
        }
        Player owner = MaidBdCompat.ownerOf(maid);
        if (owner == null || !MaidBdCompat.available() || !MaidBdCompat.hasAnyNet(owner)) {
            return;
        }
        // ① 她自己的背包放得下吗？
        ItemStack afterSelf = simulateIntoMaidInv(maid, stack);
        if (afterSelf.m_41619_()) {
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
        Object netObj = MaidBdCompat.primaryNet(owner);
        long remainder = MaidBdCompat.insert(netObj, afterSelf, afterSelf.m_41613_());
        if (remainder < 0) {
            return;   // 调用失败 → 什么都不改，照旧落地
        }
        int accepted = afterSelf.m_41613_() - (int) Math.max(0, remainder);
        if (accepted <= 0) {
            return;   // 数据库也拒收 → 照旧落地
        }
        ItemStack inEntity = itemEntity.m_32055_();
        if (inEntity.m_41613_() < accepted) {
            accepted = inEntity.m_41613_();   // 防御：实体这一拍变了就只拿它有的
        }
        inEntity.m_41774_(accepted);
        if (inEntity.m_41619_()) {
            itemEntity.m_142687_(Entity.RemovalReason.DISCARDED);
        }
        PromaidLog.log("超越维度存入", maid.m_7755_().getString() + " 溢出入库 "
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
            ItemStack rest = stack.m_41777_();
            for (int i = 0; i < inv.getSlots() && !rest.m_41619_(); i++) {
                rest = inv.insertItem(i, rest, true);
            }
            return rest;
        } catch (Throwable t) {
            return stack;   // 判定不了就当"她放不下"，让上一层去处理（保守）
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
            return "额外容器查询失败：" + t.getClass().getSimpleName();
        }
    }

    /** 物品 id（诊断用）。 */
    public static String idOf(ItemStack s) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(s.m_41720_());
        return id == null ? "?" : id.toString();
    }
}
