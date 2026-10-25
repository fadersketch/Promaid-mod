package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.inventory.handler.MaidHandsInvWrapper;
import com.maidsmart.combat.SpearCoreCompat;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.wrapper.EntityEquipmentInvWrapper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 【实测一千〇二十】女仆手槽取物：先收矛、再交出一份**副本**。
 *
 * <p>NeoForge {@code EntityEquipmentInvWrapper.extractItem} 整组取出时返回槽里那个
 * ItemStack **原对象**并把槽置空（不 copy）：玩家光标/背包拿到的是女仆 {@code useItem}
 * 仍指着的同一份。槽的 {@code setStackInSlot} 又有 {@code ItemStack.matches} 提前
 * return，同款长矛互换时光标被换成槽里的引用 → 手槽/光标/背包出现同一对象的多份引用，
 * 经 {@code split/grow} 变成真实复制。这里只对 TLM 的 {@link MaidHandsInvWrapper}
 * 主手槽：真正取物前 {@link SpearCoreCompat#stopCharge} 清掉 useItem；返回时换
 * {@code copy()}（槽已空，副本是唯一引用）。simulate（mayPickup）一律放过。
 */
@Mixin(EntityEquipmentInvWrapper.class)
public abstract class MaidHandExtractGuardMixin {

    @Shadow
    @Final
    protected LivingEntity entity;

    @Inject(method = "extractItem", at = @At("HEAD"))
    private void promaid$stopSpearUseBeforeExtract(int slot, int amount, boolean simulate,
                                                   CallbackInfoReturnable<ItemStack> cir) {
        if (simulate || amount <= 0 || slot != 0
                || !((Object) this instanceof MaidHandsInvWrapper)
                || !(entity instanceof EntityMaid maid)) {
            return;
        }
        SpearCoreCompat.stopCharge(maid);
    }

    @Inject(method = "extractItem", at = @At("RETURN"), cancellable = true)
    private void promaid$handOutCopy(int slot, int amount, boolean simulate,
                                     CallbackInfoReturnable<ItemStack> cir) {
        if (simulate || amount <= 0 || slot != 0
                || !((Object) this instanceof MaidHandsInvWrapper)) {
            return;
        }
        ItemStack out = cir.getReturnValue();
        if (out != null && !out.isEmpty()) {
            cir.setReturnValue(out.copy());
        }
    }

    /**
     * 原版 {@code setStackInSlot} 在 {@code ItemStack.matches(旧, 新)} 时直接 return：
     * 同款长矛换同款长矛不会真正写槽，容器菜单却把光标换成"旧槽那份"的引用——
     * 手槽与光标从此指向同一个对象，再放进背包就是复制。这里把这次写入强制做掉，
     * 新旧两份各自独立；不同物品/不同耐久仍走原逻辑。
     */
    @Inject(method = "setStackInSlot", at = @At("HEAD"), cancellable = true)
    private void promaid$forceMaidHandWrite(int slot, ItemStack stack, CallbackInfo ci) {
        if (slot != 0 || stack == null || !((Object) this instanceof MaidHandsInvWrapper)) {
            return;
        }
        ItemStack current = entity.getItemBySlot(EquipmentSlot.MAINHAND);
        if (current == stack || !ItemStack.matches(current, stack)) {
            return;
        }
        entity.setItemSlot(EquipmentSlot.MAINHAND, stack);
        ci.cancel();
    }
}
