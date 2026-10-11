package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import com.mojang.datafixers.util.Pair;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ── 需求原文（五百九十一）──
 * "放置方块/给重生锚充能/扔tnt的时候要有一个动作。同时使用动作的时候替换一下副手上的物品。
 * 放置完重生锚/末地水晶后 0.5s 也会有一个挥臂的动作（好像真的打了一下末影水晶/重生锚）"
 * ── 需求原文（五百九十七）──
 * "在放置重生锚的时候副手应该要拿对应的方块。使用荧石激活的时候也是。我们这个新的版本加的
 * 任何一个链路行动的时候副手都应该拿对应的物品。这样更拟真。现版本只有动作。"
 * ——于是本类从"轰炸专用"变成**全模组公用的动作表现**：{@link #showGated} 给所有"从背包掏出
 * 一件东西立刻用掉"的链路调用（搭路 / 火把 / 建造 / 种植 / 酿造 / 喂食主人 / 轰炸），
 * 拿的就是她这一下真正在用的那一件。**已在主手看得见的链路（挖矿的镐、砍树的斧、耕地/骨粉）
 * 不需要**，那是重复显示。
 * ── 做法（照 实测五百一十一 的 {@link FlightFireworkPose} 同款：只补表现，原物必还）──
 * 1. 动作那一刻：主手由调用方 `swing`（服务端会把挥臂广播给客户端）+ 把"她正在用的那件东西"
 *    放进副手举 `ticks` tick；到点把**原副手整栈**（含 NBT/数量）原样写回。 2. 起爆那一刻（末地水晶 / 重生锚 / 床 / 她自己扔的 TNT）：只有她还在近处（8 格内）时才挥一记， 副手亮的是当时那一件——观感就是"她真的打了一下水晶/重生锚"。 3. **不碰任何运行逻辑**：展示件是**复制品**（copy 后只留 1 个），不进背包、不参与任何判定； 材料消耗与投掷判定都在调用方，这里只负责"看起来在做事"。 ── 实测七百九十九【issue #19 四修：展示件改为"只发给客户端"】── 上面第 3 条原来是**假的**：旧版把展示件用 {@link net.minecraft.world.entity.LivingEntity#setItemInHand} 写进**真实副手槽**——它确实是复制品，但货真价实、就在她的槽位里，于是任何"扫双手取材"的链路 （搭路/挖矿/伐木/自保/战斗放置/索引石/插火把/自动点灯）都可能把它当材料扣走；扣完这里又补一件 ——真料一次不掉，而方块到期按方块 id 逐格还真物品（{@link com.maidsmart.task.PlacedBlockTracker#reclaimDrops}）， 一进一出净 +1。前几版用"被借走的副手不算材料"（{@code offhandBorrowed}）去堵，但那是个**布尔闸**： 展示 10 tick / 搭路 4 tick、以及"副手被别的链路搬进背包"（施肥换手等）这些时序窗口里，只要有一拍 闸门没问上、或那件复制品已经被搬出副手槽，账就漏了——这正是报告者那句"长期使用仍缓慢增加"。 现在改成**服务端槽位一个字都不动**：展示件只经 {@link #syncOffhand} 以一包 {@code ClientboundSetEquipmentPacket} 发给正在追踪她的客户端，10 tick 后再发一包"真实副手"还原。 观感与旧版一模一样（客户端渲染读的就是这一包），但服务端副手槽里**始终是玩家的真东西**： - 取材扣的就是真料、回收还的就是那一件 → 总账天然守恒（{@code offhandBorrowed} 对搭方块取材 不再是"必须问"的闸，见该方法新注释）； - 施肥换手 / 递给主人 / 盾牌分享这些"把副手搬进背包"的链路不会再误搬一件复制品（旧版那会凭空 +1）； - 玩家打开她的背包界面时看到的是真东西，不会出现"界面上有个其实不存在的方块"。 4. **有界 + 可兜底**：每个女仆至多一条倒计时；{@code tick} 由 core 行为 {@code MaidToolAutoEquipBehavior} 每 tick 调用（任何 activity 都跑，是最可靠的归还入口）。 5. **与烟花姿势互不打扰**：{@link FlightFireworkPose} 正在用副手时本类**不动手** （见 {@link #show}：这时**记一条待办**，等它还原完的下一 tick 再举，见 {@link #tick} ——旧版是直接丢弃、于是那一记动作在视觉上整个消失）。 已经举着的若碰上它开始，就等它还原完再还原（见 {@link #tick}）——两套"先快照再还原" 若互相覆盖，会把盾牌/食物这类原物弄丢。 6. **比 {@link FlightFireworkPose} 多做一步**：这里记的是**弱引用**，所以 forget / clearAll 清表时 只要她还活着就顺手把原物还回去（弱引用已被回收说明她自己也没了，那就没什么可还的）。 ── 实测五百九十七（三件事）── ① **改完立刻广播装备包**（{@link #syncOffhand}）。原版靠 {@code LivingEntity} 每 tick 的 装备变更检测（检测 → 处理 → ServerChunkCache.broadcastAndSend）把副手变化发给追踪者， 理论上同 tick 就到位；但展示窗口只有 10 tick，任何一环没跑（她自己那一 tick 被别的 逻辑提前 return、或追踪者刚好这一 tick 才加入）就整段看不见。这里**主动补一包** {@code ClientboundSetEquipmentPacket}，把"副手亮一下"从"应该会同步"变成"一定同步"。 ② **不再静默丢弃**：见第 5 条。 ③ **总开关改名**为"动作表现"（{@code combat.bombing.pose} 这条配置沿用，语义扩到全链路）。
 */
public final class BombPose {

    /** 让位的最大轮数（每轮 5 tick）——烟花姿势总共也就 10 tick，两轮足够 */
    private static final int MAX_DEFER = 3;

    /** 默认举多久（tick）——0.5 秒，够看清"她掏的是这件" */
    public static final int DEFAULT_TICKS = 10;

    /** maidId → 状态 */
    private static final Map<UUID, State> ACTIVE = new HashMap<>();

    /** maidId → 因为烟花姿势正在用副手而**排队等**的下一件（实测五百九十七：旧版直接丢） */
    private static final Map<UUID, Pending> DEFERRED = new HashMap<>();

    private static final class Pending {
        final ItemStack display;
        final int ticks;
        final WeakReference<EntityMaid> ref;

        Pending(ItemStack display, int ticks, EntityMaid maid) {
            this.display = display;
            this.ticks = ticks;
            this.ref = new WeakReference<>(maid);
        }
    }

    private static final class State {
        /** 还剩多少 tick 归还 */
        int left;
        /** 已经为"让烟花姿势先还"等过几轮 */
        int defer;
        /**
         * v1.2.2 实测五百九十九【"举 10 tick"实际只举了 3~5 tick 的根因】：
         * core 行为 {@code MaidToolAutoEquipBehavior} 在同一 tick 里可能被大脑评估多次
         *（实测：请求 40 tick，不到 20 tick 就归还了），于是展示窗口被静默缩短、
         * 玩家几乎看不见。这里记"上一次真正扣数的 gameTime"，一 tick 只扣一次。
         */
        long lastTick = Long.MIN_VALUE;
        /**
         * 实测七百九十九：展示件只发给客户端，服务端副手槽从未被动过——这里存"展示期间
         * 服务端副手的真实内容"仅作诊断/兼容（旧版存的是被顶掉的原物快照）。
         */
        final ItemStack original;
        /** 我们发给客户端的展示品（归还时用它判断客户端是否还看着我们的展示） */
        ItemStack shown;
        /** 女仆弱引用（清表时还能还就还） */
        final WeakReference<EntityMaid> ref;

        State(ItemStack original, ItemStack shown, EntityMaid maid) {
            this.original = original;
            this.shown = shown;
            this.ref = new WeakReference<>(maid);
        }
    }

    private BombPose() {
    }

    /**
     * 全链路入口：受"动作表现"总开关（{@code combat.bombing.pose}）管辖的举一下。
     *
     * 关掉之后连"待办"都不记（开关是给玩家关掉整件事用的）；还原逻辑不受开关影响，
     * 所以半路关掉也不会把她的盾牌 / 食物留在手上换不回来。
     */
    public static void showGated(EntityMaid maid, ItemStack display, int ticks) {
        try {
            if (!MaidSmartConfig.COMBAT_BOMBING_POSE.get()) {
                return;
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombPose", ignored);
        }
        show(maid, display, ticks);
    }

    /** 同上，用默认时长 */
    public static void showGated(EntityMaid maid, ItemStack display) {
        showGated(maid, display, DEFAULT_TICKS);
    }

    /**
     * 举一下这件东西（展示 {@code ticks} tick 后归还原副手物品）。
     *
     * @param maid    女仆
     * @param display 她这一次真正在用的那件（放置的方块 / 萤石 / 末地水晶 / 重生锚 / 床 / TNT /
     *                火把 / 树苗 / 酿造材料 / 喂主人的食物…）
     * @param ticks   举多久（tick）
     */
    public static void show(EntityMaid maid, ItemStack display, int ticks) {
        if (maid == null || display == null || display.isEmpty()) {
            return;
        }
        try {
            UUID id = maid.getUUID();
            ItemStack want = display.copy();
            want.setCount(1);
            if (FlightFireworkPose.isShowing(maid)) {
                // 烟花姿势正在用副手：**记一条待办**，等它还原完的下一 tick 再举
                //（实测五百九十七：旧版在这里直接 return，那一记动作就等于没有）
                DEFERRED.put(id, new Pending(want, Math.max(1, ticks), maid));
                return;
            }
            State cur = ACTIVE.get(id);
            if (cur != null) {
                cur.left = Math.max(1, ticks); // 已经在举着 → 续期 + 换成这一件（原物快照不动）
                cur.shown = want;
                syncOffhand(maid, want);
                return;
            }
            // 实测七百九十九：**服务端副手槽一个字都不动**——只把展示件发给追踪她的客户端。
            // 旧版这里是 `setItemInHand(OFF_HAND, want)`，等于把一件真物品放进她的槽位，
            // 任何扫双手取材/搬手的链路都可能把它当材料扣走或搬进背包 → 净 +1（issue #19
            // "长期缓慢增加"的第三条缝）。
            State fresh = new State(want, want, maid);
            fresh.left = Math.max(1, ticks);
            ACTIVE.put(id, fresh);
            syncOffhand(maid, want);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombPose", ignored);
        }
    }

    /** 每 tick 递减（由 core 行为调用；两张表都空时零开销） */
    public static void tick(EntityMaid maid) {
        if (maid == null || (ACTIVE.isEmpty() && DEFERRED.isEmpty())) {
            return;
        }
        try {
            UUID id = maid.getUUID();
            long now = maid.level().getGameTime();
            State st = ACTIVE.get(id);
            if (st == null) {
                // 没有在举 → 看有没有排队的（等烟花姿势让位）
                Pending pending = DEFERRED.get(id);
                if (pending == null) {
                    return;
                }
                if (FlightFireworkPose.isShowing(maid)) {
                    return; // 烟花还在手上，继续等
                }
                DEFERRED.remove(id);
                show(maid, pending.display, pending.ticks);
                return;
            }
            if (st.lastTick == now) {
                return; // 这一 tick 已经扣过了（core 行为同 tick 可能被评估多次）
            }
            st.lastTick = now;
            int left = st.left - 1;
            if (left > 0) {
                st.left = left;
                return;
            }
            // 到点了，但此刻烟花姿势正在用副手 → 等它先还（它快照到的正是我们举着的那件），
            // 免得两边互相覆盖把原物（盾牌/食物）弄丢
            if (FlightFireworkPose.isShowing(maid) && st.defer < MAX_DEFER) {
                st.defer++;
                st.left = 5;
                st.lastTick = now;
                return;
            }
            ACTIVE.remove(id);
            restore(maid, st);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombPose", ignored);
        }
    }

    /** 立即归还并清记录（需要马上收手的场合） */
    public static void restoreNow(EntityMaid maid) {
        if (maid == null) {
            return;
        }
        try {
            UUID id = maid.getUUID();
            DEFERRED.remove(id);
            State st = ACTIVE.remove(id);
            if (st != null) {
                restore(maid, st);
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombPose", ignored);
        }
    }

    /** 这个女仆此刻是不是正举着东西（供别处让位用） */
    public static boolean isShowing(EntityMaid maid) {
        try {
            return maid != null && ACTIVE.containsKey(maid.getUUID());
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombPose", ignored);
            return false;
        }
    }

    /**
     * 见类注释），所以本判定只看起飞烟花姿势 {@link FlightFireworkPose}——那个仍会把烟花
     * 写进真实副手槽。
     * <p>旧版这里是"本类借走 或 烟花姿势借走"，那个**布尔闸**就是残余泄漏的来源：搭路 4 tick /
     * 展示 10 tick、以及"副手被别的链路搬进背包"这些时序窗口里只要有一拍没问上，账就漏了。
     * 现在本类退出这场博弈后，闸门只剩下真正还需要它的一条链路；而且搭方块取材扫不到烟花
     * 火箭（不是 BlockItem），所以这条闸在 issue #19 的场景里天然不再参与。 @deprecated 仅剩烟花姿势仍借副手时返回 true；搭方块取材其实已用不到（那件不是方块）。
     */
    @Deprecated
    public static boolean offhandBorrowed(EntityMaid maid) {
        try {
            return maid != null && FlightFireworkPose.isShowing(maid);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombPose", ignored);
            return false;
        }
    }

    /**
     * 因此没有"被借走的那一件"可查，恒返回空栈。
     * <p>旧版这里返回被顶掉的原物快照，供 {@code MaidFlightKit#hasFirework/hasFan} 这类
     * 就绪判定把"借走的那件"算作仍在她身上。现在原物**一直在副手槽里**，那些判定走
     * 槽位那一支就已经看得到它，不需要本方法兜底。
     * @deprecated 展示不再借走副手槽，恒返回空栈（保留仅因 {@code MaidFlightKit} 仍引用）。
     */
    @Deprecated
    public static ItemStack savedOffhand(EntityMaid maid) {
        return ItemStack.EMPTY;
    }

    /**
     * 清记录（女仆被移除等）：这里手上还留着**弱引用**，所以只要她还在就顺手把原物还回去
     * ——{@code FlightFireworkPose.forget} 只有 UUID、做不到这一步。
     */
    public static void forget(UUID maidId) {
        if (maidId == null) {
            return;
        }
        DEFERRED.remove(maidId);
        restoreIfPossible(ACTIVE.remove(maidId));
    }

    public static void clearAll() {
        for (State st : ACTIVE.values()) {
            restoreIfPossible(st);
        }
        ACTIVE.clear();
        DEFERRED.clear();
    }

    private static void restoreIfPossible(State st) {
        if (st == null) {
            return;
        }
        EntityMaid maid = st.ref.get();
        if (maid != null && maid.isAlive()) {
            restore(maid, st);
        }
    }

    /**
     * 把客户端的副手显示还原成**服务端真实副手**。
     *
     * 实测七百九十九：本类不再改服务端槽位，所以"还原"= 把真实副手那一栈重新发给客户端。
     * 玩家在这十几 tick 里往她副手放的东西服务端一直好好地在槽里（我们从没顶掉它），
     * 这一包发出去客户端也就显示回真东西——不存在旧版"覆盖玩家刚放进去的东西"的问题，
     * 也不需要 {@code MaidGiveBack} 兜底（旧版那条分支已随"不再借槽"一并作废）。
     */
    private static void restore(EntityMaid maid, State st) {
        syncOffhand(maid, maid.getOffhandItem());
    }

    /**
     * 这一包本身就是全部实现：只把要展示的那一栈发给正在追踪她的客户端（不动服务端槽位）。
     * 归还时传入 {@code maid.getOffhandItem()}（真实副手）即还原。
     * {@code broadcastAndSend} 只发给"正在追踪该实体"的玩家，所以未加载/远处的人看到的仍是
     * 服务端真实状态；而渲染女仆的手部模型读的正是这包装备数据——观感与旧版一模一样。
     */
    private static void syncOffhand(EntityMaid maid, ItemStack toShow) {
        try {
            if (maid.level() instanceof ServerLevel sl) {
                ItemStack send = toShow == null ? ItemStack.EMPTY : toShow.copy();
                sl.getChunkSource().broadcastAndSend(maid, new ClientboundSetEquipmentPacket(
                        maid.getId(), List.of(Pair.of(EquipmentSlot.OFFHAND, send))));
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombPose", ignored);
        }
    }
}
