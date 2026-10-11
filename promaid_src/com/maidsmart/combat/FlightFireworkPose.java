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
 *
 * ── 实测七百九十九【issue #19 同族：展示改为"只发给客户端"】──
 * 与 {@link BombPose} 同一处收口。旧版把烟花用 {@code setItemInHand(OFF_HAND, 烟花)} 写进
 * **真实副手槽**：那件是复制品，但就在槽里，于是"把副手搬进背包"的链路（施肥换手
 * {@code MaidPlanting.equipBoneMeal} 会把副手旧物 {@code insertItemStacked} 进背包、
 * 递物/换装同理）可能把这枚烟花当真物品搬进她背包 → 净 +1。
 * 现在**服务端副手槽一个字都不动**：展示只经 {@link #syncOffhand} 发给追踪她的客户端，
 * 10 tick 后再发一包真实副手还原。观感不变，服务端槽里始终是玩家的真东西。
 */
public final class FlightFireworkPose {

    /** 烟花在副手停留的 tick 数（0.5 秒——够看清"她掏了烟花"，又不至于像换了武器） */
    private static final int HOLD_TICKS = 10;

    /** maidId → [剩余 tick, 展示给客户端的那件]（第二项用于还原时判断客户端是否还看着我们的展示） */
    private static final Map<UUID, Object[]> ACTIVE = new HashMap<>();

    private FlightFireworkPose() {
    }

    /**
     * 在副手显示一下烟花。
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
            ItemStack display = (consumedFw != null && !consumedFw.m_41619_())
                    ? consumedFw.m_41777_()                 // 用实际那枚（模型与它完全一致）
                    : new ItemStack(Items.f_42688_);        // 兜底（理论上不会走到）
            display.m_41764_(1); // 只显示一枚
            // 已经在显示中 → 只续期并换新展示件，不重复动别的东西
            Object[] cur = ACTIVE.get(id);
            if (cur != null) {
                cur[0] = HOLD_TICKS;
                cur[1] = display;
                syncOffhand(maid, display);
                return;
            }
            // 实测七百九十九：**服务端副手槽一个字都不动**——只把烟花发给追踪她的客户端
            // （旧版这里是 setItemInHand(OFF_HAND, display)，那件复制品会被"搬副手进背包"
            //  的链路当真物品收走 → 净 +1，见类注释）
            ACTIVE.put(id, new Object[]{HOLD_TICKS, display});
            syncOffhand(maid, display);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("FlightFireworkPose", ignored);
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
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("FlightFireworkPose", ignored);
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
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("FlightFireworkPose", ignored);
            return false;
        }
    }

    /**
     * 实测七百九十九：展示件不再写进服务端槽位，所以没有"被借走的那一件"可查——恒空栈。
     *
     * <p>旧版这里返回被顶掉的原副手快照，供 {@link com.maidsmart.combat.MaidFlightKit#borrowedOffhand}
     * 把"借走的那件"算作仍在她身上。现在原物**一直在副手槽里**，那些就绪判定走槽位那一支
     * 本就看得见它，不需要本方法兜底。
     *
     * @deprecated 展示不再借走副手槽，恒返回空栈（保留仅因 {@code MaidFlightKit} 仍引用）。
     */
    @Deprecated
    public static ItemStack savedOffhand(EntityMaid maid) {
        return ItemStack.f_41583_;
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
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("FlightFireworkPose", ignored);
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
     * 归还：把**服务端真实副手**重新发给客户端。
     *
     * 实测七百九十九：本类不再改服务端槽位，所以"归还"只是把真东西发回去——不存在旧版
     * "覆盖玩家刚放进去的东西 / 原物走 MaidGiveBack"的问题（服务端槽里始终是玩家的真东西）。
     */
    private static void restore(EntityMaid maid, Object[] cur) {
        syncOffhand(maid, maid.m_21206_());
    }

    /**
     * 实测七百九十九：把要展示的那一栈**只发给正在追踪她的客户端**（服务端槽位不动）。
     *
     * {@code broadcastAndSend} 只发给正在追踪该实体的玩家；渲染女仆手部模型读的正是这包
     * 装备数据——观感与旧版一模一样，而服务端副手槽里始终是玩家的真东西。
     */
    private static void syncOffhand(EntityMaid maid, ItemStack toShow) {
        try {
            if (maid.m_9236_() instanceof net.minecraft.server.level.ServerLevel sl) {
                ItemStack send = toShow == null ? ItemStack.f_41583_ : toShow.m_41777_();
                sl.m_7726_().m_8445_(maid, new net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket(
                        maid.m_19879_(),
                        java.util.List.of(com.mojang.datafixers.util.Pair.of(
                                net.minecraft.world.entity.EquipmentSlot.OFFHAND, send))));
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("FlightFireworkPose", ignored);
        }
    }
}
