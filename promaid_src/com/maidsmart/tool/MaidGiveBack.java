package com.maidsmart.tool;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemHandlerHelper;

/**
 * v1.2.2 实测五百九十五【"还给她"的时候绝不吞】：换装、动作展示、回收这些
 * "先把东西从她身上/背包里取下来、用完再放回去"的路径，统一走这里。
 *
 * 【为什么要有这个类】这类"还回去"的旧写法几乎都是同一个形状：
 * <pre>
 *   for (每一个槽) if (槽是空的) { inv.insertItem(槽, stack, false); return; }  // ← 不看返回值
 *   落地
 * </pre>
 * 那个 `return` 是致命的：`insertItem` 会把**没塞进去的部分原样退回来**，而下面三种
 * 情形都会让它退货——
 * <ol>
 *   <li>TLM 的女仆背包有禁放规则（`MaidBackpackHandler.isItemValid` → `EntityMaid.canInsertItem`）；</li>
 *   <li>模组背包（箱子 / 熔炉 / 抽屉背包等）对槽位有自己的限制，第一格空格未必要收这件；</li>
 *   <li>那一件物品一栈根本放不进这一格的剩余空间。</li>
 * </ol>
 * 不看返回值 = 把物品扔进黑洞：反馈里的"收放魂符之后在物品栏 / 装备栏之间反复拖动、
 * 装备会被卡掉"，以及此前出现过的"换下来的装备不见了"，都是这个形状。
 *
 * 【现在的口径】走 Forge / NeoForge 的标准实现 `ItemHandlerHelper.insertItemStacked`：
 * 先往同类物品上堆叠、再找空槽，返回**最后没塞进去的那一份**；只要还有剩的就
 * `spawnAtLocation` 掉在她脚下（与挖矿 / 搭路的方块回收同一口径）。于是最坏情况是
 * "东西掉在地上"，而不是"凭空消失"。
 */
public final class MaidGiveBack {

    private MaidGiveBack() {
    }

    /**
     * 还给她：能进背包就进，进不去的部分掉在她脚下。
     *
     * @param why 这次归还的来源（只用于日志，方便排查"谁把东西掉地上了"；可为 null）
     */
    public static void give(EntityMaid maid, ItemStack stack, String why) {
        if (maid == null || stack == null || stack.m_41619_()) {
            return;
        }
        ItemStack rest;
        try {
            rest = ItemHandlerHelper.insertItemStacked(maid.getAvailableBackpackInv(), stack, false);
        } catch (Throwable t) {
            // 插入过程本身抛异常：无法判断已经塞进去多少，整栈落地（宁可多一件也绝不吞）
            rest = stack;
            PromaidLog.log("归还", nameOf(maid) + " 背包插入异常，整栈落地："
                    + stack.m_41786_().getString());
        }
        if (rest == null || rest.m_41619_()) {
            return;
        }
        // v1.2.4 实测六百三十六【精妙背包适配】：她自己的背包塞不下时，先问问她身上的
        // "额外容器"（饰品栏里的精妙背包 / 旅行者背包）——见 MaidExtraContainer
        rest = MaidExtraContainer.overflow(maid, rest);
        if (rest == null || rest.m_41619_()) {
            return;
        }
        try {
            maid.m_5552_(rest, 0.5f);
        } catch (Throwable ignored) {
            return;
        }
        PromaidLog.log("归还", nameOf(maid) + " 背包塞不下 → 掉在她脚下：" + rest.m_41613_()
                + "x " + rest.m_41786_().getString()
                + (why == null ? "" : "（来源：" + why + "）"));
    }

    private static String nameOf(EntityMaid maid) {
        try {
            return PromaidLog.nameOf(maid);
        } catch (Throwable ignored) {
            return "女仆";
        }
    }
}
