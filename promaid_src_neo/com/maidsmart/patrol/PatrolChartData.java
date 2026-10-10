package com.maidsmart.patrol;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * v1.3.8【巡逻航迹】两种载体上的读写：**物品**（{@code CUSTOM_DATA}）与**女仆**（{@code persistentData}）。
 *
 * <p>── 为什么只有一处编解码 ──
 * 两边的 NBT 结构一字不差（都走 {@link PatrolRoute#save/load}），所以本类只负责"把 CompoundTag
 * 从哪个容器里取出来 / 放回去"。口径只留一处，免得物品与女仆的存档格式悄悄分家。
 *
 * <p>── 绑定 = 快照 ──
 * 右键女仆时把物品那一份**深拷**进她的 {@code persistentData}（见 {@link #bind}）。之后两者
 * 各走各的：你把航图锁进箱子、或转手给别人，她照样按绑定时那一份巡逻；想改就再右键一次覆盖。
 *
 * <p>── 两树差异（本批只做 1.21.1）──
 * 这里用 1.21.1 的数据组件口径（{@code DataComponents.CUSTOM_DATA} + {@code CustomData.of/copyTag}），
 * 与 {@code CompressionBoxData} 完全同款。1.20.1 树若日后要镜像，只需按 {@code CompressionBoxData}
 * 的注释把那几行换成 {@code m_41783_() / m_41698_()}。
 */
public final class PatrolChartData {

    private PatrolChartData() {
    }

    /* ==================== 物品侧（一本航图 = 多条轨道） ==================== */

    /** 读物品上的整本书（不是巡逻航图 / 没数据 → 空书）。旧版单条结构会被自动迁移。 */
    public static PatrolBook readBook(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()
                    || !(stack.getItem() instanceof PatrolChartItem)) {
                return new PatrolBook();
            }
            CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            return PatrolBook.load(tag);
        } catch (Throwable ignored) {
            return new PatrolBook();
        }
    }

    /** 写回整本书（会把旧的单条标签一并清掉——迁移完成后它就不该再留在物品上了） */
    public static void writeBook(ItemStack stack, PatrolBook book) {
        try {
            if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof PatrolChartItem)) {
                return;
            }
            CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            tag.put(PatrolBook.TAG_ROOT, book.save());
            tag.remove(PatrolBook.TAG_LEGACY_SINGLE); // 旧结构已迁走，别留着占地方
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 读物品上**当前选中**的那条航迹（右键打点时往它上面加）。
     * 返回 null = 这本书里一条都没有（调用方据此提示"先新建一条"）。
     */
    public static PatrolRoute readItem(ItemStack stack) {
        return readBook(stack).selected();
    }

    /** 便捷：把"当前选中那条"的改动写回（内部整本重写，保证 id/名字/选中状态一致） */
    public static void writeItem(ItemStack stack, PatrolRoute route) {
        try {
            if (route == null) {
                return;
            }
            PatrolBook book = readBook(stack);
            PatrolRoute existing = book.byId(route.id());
            if (existing == null) {
                return; // 这条已经不在书里了（被删掉）→ 不写
            }
            int idx = book.routes().indexOf(existing);
            book.routes().set(idx, route);
            writeBook(stack, book);
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 女仆侧 ==================== */

    /**
     * 读女仆身上绑的那一份（没绑 → null，调用方据此判定"她没在巡逻"）。
     *
     * <p>【实测七百九十八：轨道被删 = 这一份作废】绑定是**深拷**（见类注释），所以玩家在航图里
     * 删掉一条轨道时，她身上这份副本不会自己消失——她会照旧沿那条"已经不存在的轨道"一圈圈飞
     * （
     * {@link PatrolRevocation} 的"已删除账本"，就当**没绑**返回 null，她立刻回到默认状态
     * （回到原本的守家盘旋 / 跟随 / 悬停）。
     *
     * <p>为什么判据放在这里、而不是删轨道那一瞬间扫一遍女仆：她可能在未加载区块里，
     * 删除时根本扫不到（见 {@link PatrolRevocation} 的类注释）。收口在这里，
     * {@link PatrolFlight#effective} 与界面名单（{@code PatrolNetworking.collectOwnMaids}）
     * 自动全部受益，口径只有一处。
     */
    public static PatrolRoute readMaid(EntityMaid maid) {
        PatrolRoute r = readMaidRaw(maid);
        if (r == null) {
            return null;
        }
        // 被删掉的轨道对女仆不再生效（客户端恒 false：撤销是服务端权威的事）。
        // 【自愈】读到一条已作废的绑定时**顺手把它从她身上抹掉**——这样"未加载区块里那批
        // 女仆"的陈旧副本也会在她第一次被读到的那一拍真正消失（而不是永远挂在 NBT 里
        // 只当作看不见）。unbind 后就回到上面那行的 null 分支，之后每拍的代价只有一次
        // "有没有 TAG_ROOT" 的判断。
        if (PatrolRevocation.revokedFor(maid, r.id())) {
            unbind(maid);
            PatrolFlight.forget(maid);
            com.maidsmart.tool.PromaidLog.log("扫帚巡逻",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 绑定的轨道「" + r.displayName()
                            + "」已被玩家删除 → 清除她身上那份副本，她回到默认状态");
            return null;
        }
        return r;
    }

    /**
     * 读女仆身上绑的那一份，**不看"是否已删除"**——只给 {@link PatrolRevocation} 解绑时
     * 比对 id 用（它要读得出那份即将作废的绑定，才谈得上解绑），其余一切调用点都用
     * {@link #readMaid}。
     */
    public static PatrolRoute readMaidRaw(EntityMaid maid) {
        try {
            if (maid == null) {
                return null;
            }
            CompoundTag data = ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid)
                    .getPersistentData();
            if (!data.contains(PatrolRoute.TAG_ROOT, 10)) {
                return null;
            }
            return PatrolRoute.load(data.getCompound(PatrolRoute.TAG_ROOT));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 把一份航迹绑到女仆身上（深拷；同时记录**当前维度**——她跨维度后不再巡逻）。
     * 返回 false = 航迹不够格（点太少/没闭环），此时**不写**、由调用方给出可读原因。
     */
    public static boolean bind(EntityMaid maid, PatrolRoute route) {
        try {
            if (maid == null || route == null) {
                return false;
            }
            PatrolRoute copy = route.copy();
            copy.setDimension(dimensionOf(maid));
            ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid)
                    .getPersistentData().put(PatrolRoute.TAG_ROOT, copy.save());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 解绑（清掉女仆身上那一份；她立刻回到扫帚模式原本的平时行为） */
    public static void unbind(EntityMaid maid) {
        try {
            if (maid == null) {
                return;
            }
            ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid)
                    .getPersistentData().remove(PatrolRoute.TAG_ROOT);
        } catch (Throwable ignored) {
        }
    }

    public static boolean bound(EntityMaid maid) {
        return readMaid(maid) != null;
    }

    private static String dimensionOf(EntityMaid maid) {
        try {
            return maid.level().dimension().location().toString();
        } catch (Throwable ignored) {
            return "";
        }
    }
}
