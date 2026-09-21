package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * v1.2.0 实测五百一十一【放烟花的瞬间，副手"亮一下"烟花模型】（纯表现，零逻辑改动）。
 *
 * ── 需求 ──
 * "女仆放烟花的瞬间副手拿一下烟花（只加了个动作和烟花模型在副手，不改运行逻辑，用完恢复）"
 *
 * ── 为什么单独做这一层 ──
 * 实测五百一十 已经把副手**永久让出**给盾牌/食物（烟花改为从背包按需取用，发射路径
 * `MaidFlightCombatBehavior#launchFirework` 自己造弹体、根本不读手里的物品）。
 * 但完全看不到"她在放烟花"这件事观感上缺了一环——这个类只补**动作表现**：
 * 发射那一刻把一枚烟花火箭放进副手，几十 tick 后把**原副手物品原样还回去**。
 *
 * ── 设计要点（为什么是安全的）──
 * 1. **不碰任何运行逻辑**：不改弹药判定、不改推力、不改冷却、不改就绪判定。
 *    只是 `setItemSlot(OFFHAND, 烟花)` 与到点后的还原——烟花在手上**不参与**任何计算。
 * 2. **原物必还**：放置前先快照原副手整栈（含 NBT/数量），到点整体写回；不会丢盾牌/食物。
 * 3. **有界**：每个女仆至多一条倒计时记录，到点自清；`forget`/`clearAll` 时立即归还并清表，
 *    即使行为被强杀（切任务/死亡/重载）也不会把烟花永久留在她手上。
 * 4. **同步**：`setItemSlot` 走 `LivingEntity` 的装备槽同步（`EntityMaid` 覆写了该方法），
 *    服务端一改客户端立刻看得到手里的烟花——不需要额外网络包。
 * 5. **展示你实际使用的那枚火箭**（把 `takeFirework` 取出的那一枚原样拿去显示）：
 *    烟花火箭的**物品模型是静态的**——`models/item/firework_rocket.json` 只有
 *    `{"parent":"item/generated","textures":{"layer0":"minecraft:item/firework_rocket"}}`，
 *    **没有任何 overrides、不读 NBT/组件**（两版本 extra jar 实证）。所以"带不带爆炸组件"
 *    在手部看上去完全一样；既然一样，就该用**你真正消耗掉的那一枚**去显示——
 *    语义正确（"她掏出的就是刚才那枚"），也不丢任何信息。
 *
 *    【实测五百一十四更正】初版这里写的是"固定展示一枚朴素火箭"，理由是"避免被当弩弹药吃掉"。
 *    那个顾虑**方向错了**：展示窗口内若正好开弩，取弹药那一步（副手优先）读到的
 *    若是一枚真烟花，它本来就是合格弹药，不存在"误吃"。用户一句
 *    "应该用使用的那个火箭的那个贴图吧"点破了这层——现在改为**用实际那枚**。
 *
 *    【实测五百三十三再补】取弹药已改名为 `MaidFlightKit#takeBestCrossbowFirework`，
 *    而且口径放宽成"**任意**烟花火箭都算弹药"（不再要求带爆炸组件）+ 按威力优先挑。
 *    所以"朴素火箭被当弹药打出去"现在是**可能发生的**（没有更好的就用它兜底）——
 *    这符合原版玩家拿弩装普通烟花的行为，不再是需要规避的意外。
 * 6. **只在空袭链路调用**：调用点是 `MaidFlightCombatBehavior` 的两处真实发射
 *    （近战空袭起飞 / 远程空袭掉高补推），与飞行机制同生命周期。
 */
public final class FlightFireworkPose {

    /** 烟花在副手停留的 tick 数（0.5 秒——够看清"她掏了烟花"，又不至于像换了武器） */
    private static final int HOLD_TICKS = 10;

    /** maidId → [剩余 tick, 原副手物品, 我们放进去的展示品]（第三项用于判断副手有没有被玩家动过） */
    private static final Map<UUID, Object[]> ACTIVE = new HashMap<>();

    private FlightFireworkPose() {
    }

    /**
     * 在副手显示一下烟花（并记住原物）。
     *
     * @param maid       女仆
     * @param consumedFw 本次真正消耗掉的那枚烟花（`takeFirework` 已从背包/手上取出）——
     *                   直接拿它显示，模型与你实际用的那枚完全一致；为空时退化为朴素火箭兜底
     */
    public static void show(EntityMaid maid, ItemStack consumedFw) {
        if (maid == null) {
            return;
        }
        try {
            UUID id = maid.m_20148_();
            // 已经在显示中 → 只续期，不覆盖原物快照（否则会把"烟花"当成原物存下来）
            Object[] cur = ACTIVE.get(id);
            if (cur != null) {
                cur[0] = HOLD_TICKS;
                return;
            }
            ItemStack original = maid.m_21206_().m_41777_(); // 副手整栈快照（含 NBT）
            ItemStack display = (consumedFw != null && !consumedFw.m_41619_())
                    ? consumedFw.m_41777_()                 // 用实际那枚（模型与它完全一致）
                    : new ItemStack(Items.f_42688_);        // 兜底（理论上不会走到）
            display.m_41764_(1); // 只显示一枚
            maid.m_21008_(InteractionHand.OFF_HAND, display);
            // v1.2.2 实测五百九十五：连"我们放进去的那件"一起记——归还时要靠它判断副手
            // 是否还保持着我们的展示品（玩家中途放进别的东西就不能覆盖，见 restore）
            ACTIVE.put(id, new Object[]{HOLD_TICKS, original, display.m_41777_()});
        } catch (Throwable ignored) {
        }
    }

    /** 每 tick 递减（由飞行行为在 tick 里调用；无记录时零开销） */
    public static void tick(EntityMaid maid) {
        if (maid == null || ACTIVE.isEmpty()) {
            return;
        }
        try {
            UUID id = maid.m_20148_();
            Object[] cur = ACTIVE.get(id);
            if (cur == null) {
                return;
            }
            int left = (Integer) cur[0] - 1;
            if (left > 0) {
                cur[0] = left;
                return;
            }
            restore(maid, cur);
            ACTIVE.remove(id);
        } catch (Throwable ignored) {
        }
    }

    /**
     * v1.2.2 实测五百九十一：这个女仆此刻是不是正举着烟花。
     *
     * 给 {@code BombPose} 让位用的只读查询——两套姿势都是"先快照副手、到点还原"，同时上会把
     * 对方的快照覆盖掉（盾牌/食物会丢）。炸弹姿势那边见到这里为 true 就不动手（或等这里还完）。
     */
    public static boolean isShowing(EntityMaid maid) {
        try {
            return maid != null && ACTIVE.containsKey(maid.m_20148_());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * v1.2.2 实测六百〇九：**我们借走的那件副手物品**的只读查询（没借走就返回空栈）。
     *
     * 与 {@link BombPose#savedOffhand} 同一用途：展示窗口里那件东西不在任何槽位里
     * （原栈已被 {@code setItemInHand} 顶掉，快照就在本类的记录里），"她身上有没有某件东西"
     * 的判定若只看槽位，就会在**那件东西明明在她身上**的十几 tick 里得到 false。
     * 判定口径修在 {@link com.maidsmart.combat.MaidFlightKit#borrowedOffhand}。
     */
    public static ItemStack savedOffhand(EntityMaid maid) {
        try {
            Object[] cur = maid == null ? null : ACTIVE.get(maid.m_20148_());
            if (cur == null || cur.length < 2 || !(cur[1] instanceof ItemStack s)) {
                return ItemStack.f_41583_;
            }
            return s;
        } catch (Throwable ignored) {
            return ItemStack.f_41583_;
        }
    }

    /** 立即归还并清记录（行为收尾 / 强杀兜底） */
    public static void restoreNow(EntityMaid maid) {
        if (maid == null) {
            return;
        }
        try {
            UUID id = maid.m_20148_();
            Object[] cur = ACTIVE.remove(id);
            if (cur != null) {
                restore(maid, cur);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 用 UUID 版本（forget 只有 id，没有实体引用——此时无法归还，但必须清表防泄漏） */
    public static void forget(UUID maidId) {
        if (maidId != null) {
            ACTIVE.remove(maidId);
        }
    }

    public static void clearAll() {
        ACTIVE.clear();
    }

    /**
     * 归还：副手还是我们的展示品（或空着）→ 原物写回；已被玩家换成别的 → 原物走
     * {@link com.maidsmart.tool.MaidGiveBack}（进背包/落地），绝不覆盖玩家的东西。
     * v1.2.2 实测五百九十五：旧版无条件写回，会把展示窗口里玩家放进去的装备顶掉。
     */
    private static void restore(EntityMaid maid, Object[] cur) {
        ItemStack original = (cur[1] instanceof ItemStack s) ? s : ItemStack.f_41583_;
        ItemStack shown = (cur.length > 2 && cur[2] instanceof ItemStack d) ? d : ItemStack.f_41583_;
        ItemStack now = maid.m_21206_();
        boolean untouched = now.m_41619_()
                || (!shown.m_41619_() && ItemStack.m_41728_(now, shown));
        if (untouched) {
            maid.m_21008_(InteractionHand.OFF_HAND, original);
        } else if (!original.m_41619_()) {
            com.maidsmart.tool.MaidGiveBack.give(maid, original, "副手展示烟花期间被换下的原物");
        }
    }
}
