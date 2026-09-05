package com.maidsmart.storage;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.1.0 实测三百二十一：超越维度【网络终端】取物适配器——把超越维度的
 * UnifiedStorage（网络统一存储）包装成 Forge IItemHandler，供女仆取物
 * （建造/酿药调用方零改动，boundHandlerOf 返回本适配器即可）。
 *
 * 反射链路（javap 实证 0.7.29，全部公开 API，零编译期依赖）：
 * - NetedBlockEntity.getNet() → DimensionsNet
 * - DimensionsNet.getUnifiedStorage() → UnifiedStorage
 * - UnifiedStorage.getStorage() → List<KeyAmount>（网络内全部物品条目）
 * - UnifiedStorage.extract(IStackKey, long, boolean) → KeyAmount（真提取）
 * - ItemStackKey(ItemStack) 公开构造；KeyAmount.key()/amount()/toStack() 公开
 * - IStackKey.getReadOnlyStack() → ItemStack（只读快照，统计用）
 *
 * 槽位映射：getStorage() 的条目顺序即槽位（每次 getSlots 刷新缓存）。
 * extractItem 走 extract(key, count, simulate)——simulate 时只读不扣。
 */
public final class UnifiedStorageItemHandler implements IItemHandler {
    /** UnifiedStorage 实例（反射持有） */
    private final Object storage;
    /** 缓存条目（getSlots 时刷新） */
    private List<Object> entries = new ArrayList<>();

    private static Method M_GET_STORAGE;
    private static Method M_EXTRACT;
    private static Method M_KEY;
    private static Method M_AMOUNT;
    private static Method M_TO_STACK;
    private static Method M_READ_ONLY_STACK;
    private static java.lang.reflect.Constructor<?> M_ITEMSTACKKEY_CTOR;

    static {
        try {
            Class<?> us = Class.forName("com.wintercogs.beyonddimensions.api.dimensionnet.UnifiedStorage");
            M_GET_STORAGE = us.getMethod("getStorage");
            M_EXTRACT = us.getMethod("extract",
                    Class.forName("com.wintercogs.beyonddimensions.api.storage.key.IStackKey"),
                    long.class, boolean.class);
            Class<?> ka = Class.forName("com.wintercogs.beyonddimensions.api.storage.key.KeyAmount");
            M_KEY = ka.getMethod("key");
            M_AMOUNT = ka.getMethod("amount");
            M_TO_STACK = ka.getMethod("toStack");
            Class<?> isk = Class.forName("com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey");
            M_ITEMSTACKKEY_CTOR = isk.getConstructor(ItemStack.class);
            M_READ_ONLY_STACK = Class.forName("com.wintercogs.beyonddimensions.api.storage.key.IStackKey")
                    .getMethod("getReadOnlyStack");
        } catch (Throwable ignored) {
            // 超越维度未安装/版本不匹配：适配器不可用（boundHandlerOf 返回 null 自然回退）
        }
    }

    /** 反射链路是否可用（超越维度已安装且版本匹配） */
    public static boolean isUsable() {
        return M_GET_STORAGE != null && M_EXTRACT != null && M_KEY != null
                && M_AMOUNT != null && M_TO_STACK != null && M_READ_ONLY_STACK != null
                && M_ITEMSTACKKEY_CTOR != null;
    }

    public UnifiedStorageItemHandler(Object unifiedStorage) {
        this.storage = unifiedStorage;
    }

    /** 刷新条目缓存（getStorage 返回 List<KeyAmount>） */
    private void refresh() {
        try {
            Object list = M_GET_STORAGE.invoke(this.storage);
            if (list instanceof List<?> l) {
                this.entries = new ArrayList<>(l);
            } else {
                this.entries = new ArrayList<>();
            }
        } catch (Throwable ignored) {
            this.entries = new ArrayList<>();
        }
    }

    @Override
    public int getSlots() {
        this.refresh();
        return this.entries.size();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        this.refresh();
        if (slot < 0 || slot >= this.entries.size()) {
            return ItemStack.f_41583_;
        }
        try {
            Object key = M_KEY.invoke(this.entries.get(slot));
            if (key == null) {
                return ItemStack.f_41583_;
            }
            Object stack = M_READ_ONLY_STACK.invoke(key);
            return stack instanceof ItemStack s ? s : ItemStack.f_41583_;
        } catch (Throwable ignored) {
            return ItemStack.f_41583_;
        }
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        this.refresh();
        if (slot < 0 || slot >= this.entries.size() || amount <= 0) {
            return ItemStack.f_41583_;
        }
        try {
            Object key = M_KEY.invoke(this.entries.get(slot));
            if (key == null) {
                return ItemStack.f_41583_;
            }
            Object ka = M_EXTRACT.invoke(this.storage, key, (long) amount, simulate);
            if (ka == null) {
                return ItemStack.f_41583_;
            }
            Object stack = M_TO_STACK.invoke(ka);
            return stack instanceof ItemStack s ? s : ItemStack.f_41583_;
        } catch (Throwable ignored) {
            return ItemStack.f_41583_;
        }
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        return stack; // 只读取物（女仆只从网络取，不放回）
    }

    @Override
    public int getSlotLimit(int slot) {
        return 64;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return false;
    }
}
