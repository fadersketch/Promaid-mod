package com.maidsmart.mixin;

import com.mojang.datafixers.util.Pair;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.List;

/**
 * 【装备消失根因·两树镜像】装备同步包必须携带**副本**，不能把服务端的 ItemStack 原对象交给客户端。
 *
 * <p>单机（整合服）的本地连接不走网络序列化，{@code ClientboundSetEquipmentPacket} 的构造器又只
 * 把 {@code List<Pair<EquipmentSlot, ItemStack>>} 原样存下（javap 实证）。而传送后的客户端实体重建
 * 会重新发装备包，于是客户端女仆的装备格与**服务端**持有同一枚 ItemStack；客户端点击预判里的
 * {@code carried.split()} 会把这枚共享对象原地 {@code setCount(0)}，服务端下一次处理放置包时
 * 光标已是空 → 物品凭空消失（实机第四轮：Render thread 清零、服务端没有任何清零调用）。
 *
 * <p>这里在构造器入参处逐枚 {@code copy()}，切断跨端对象共享；专服本来就会在序列化时复制，
 * 多一次复制无行为影响。neoforge/1.21.1 树（Mojmap）。
 */
@Mixin(ClientboundSetEquipmentPacket.class)
public abstract class EquipmentPacketCopyMixin {

    @ModifyVariable(method = "<init>(ILjava/util/List;)V", at = @At("HEAD"),
            argsOnly = true, index = 2)
    private static List<Pair<EquipmentSlot, ItemStack>> promaid$copyEquipmentStacks(
            List<Pair<EquipmentSlot, ItemStack>> slots) {
        try {
            if (slots == null || slots.isEmpty()) {
                return slots;
            }
            List<Pair<EquipmentSlot, ItemStack>> copied = new ArrayList<>(slots.size());
            for (Pair<EquipmentSlot, ItemStack> entry : slots) {
                copied.add(Pair.of(entry.getFirst(), entry.getSecond().copy()));
            }
            return copied;
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("EquipmentPacketCopyMixin", ignored);
            return slots; // 复制失败就按原样发，行为退回修复前，绝不阻断同步
        }
    }
}
