package com.maidsmart.mixin;

import com.maidsmart.box.CompressionBoxData;
import com.maidsmart.box.CompressionBoxFilter;
import com.maidsmart.box.CompressionBoxItem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Forge 1.20.1 专用：把压缩盒的「收纳袋式右键」接进原版菜单。
 *
 * <p>── 为什么要这个 mixin ──
 * 原版 1.20.5 才把收纳袋点击逻辑写进 {@code AbstractContainerMenu}（调
 * {@code ItemStack.overrideStackedOnOther}）。扫本仓编译用的 vanilla {@code -srg.jar} 与
 * forge-patched client jar：方法名 {@code m_142207_} / {@code m_142305_} 只出现在
 * {@code ItemStack} / {@code Item} / {@code BundleItem} 三处**定义**里，**一个调用方都没有**
 * ——光在 {@link CompressionBoxItem} 里 override 那两个钩子 = 死代码，本类就是补上那个调用方
 * （Neo 1.21.1 的原版菜单本来就调，那边不需要本类）。
 *
 * <p>── 只在很窄的条件下接管 ──
 * 拾取键 + 右键 + 光标上拿着 1 个压缩盒 + 目标槽有东西且可装；其余情况一律直接返回，
 * 原版逻辑一字不改。
 */
@Mixin(AbstractContainerMenu.class)
public abstract class CompressionBoxClickMixin {

    @Inject(method = "m_150399_(IILnet/minecraft/world/inventory/ClickType;Lnet/minecraft/world/entity/player/Player;)V",
            at = @At("HEAD"), cancellable = true)
    private void promaid$compressionBoxPickup(int slotId, int button, ClickType type, Player player,
                                              CallbackInfo ci) {
        try {
            if (type != ClickType.PICKUP || button != 1) {
                return; // 只接「拾取 + 右键」
            }
            AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
            if (slotId < 0 || slotId >= menu.f_38839_.size()) {
                return; // -999（丢出窗口）等特殊槽不碰
            }
            Slot slot = menu.m_38853_(slotId);
            if (slot == null || !slot.m_8010_(player)) {
                return; // 不可拾取的槽（输出槽 / 只读槽）交还原版
            }
            ItemStack carried = menu.m_142621_();
            if (!CompressionBoxItem.isBox(carried) || carried.m_41613_() != 1) {
                return;
            }
            ItemStack inSlot = slot.m_7993_();
            if (inSlot.m_41619_() || !CompressionBoxFilter.canStore(inSlot)) {
                return; // 空槽没得装；禁入的（带附魔 / 盒子本身）不装
            }
            List<ItemStack> items = CompressionBoxData.read(carried);
            ItemStack left = CompressionBoxData.merge(items, inSlot.m_41777_());
            if (left.m_41613_() == inSlot.m_41613_()) {
                return; // 一点都没装进去（5 格满 / 这件不合）→ 交还原版
            }
            CompressionBoxData.write(carried, items);
            slot.m_5852_(left); // 槽里留下装不下的余量
            slot.m_6654_();
            menu.m_142503_(carried); // getCarried 不保证返回同一对象引用 → 写回
            CompressionBoxItem.playStoreSound(player); // 收纳成功 → 一记穿装备声
            ci.cancel();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("CompressionBoxClickMixin", ignored);
            // 背包交互是高频路径，这里绝不把异常抛进菜单点击链
        }
    }
}
