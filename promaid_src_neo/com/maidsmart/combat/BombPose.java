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
 * v1.2.2 实测五百九十一【放置 / 充能 / 投掷那一记动作】+ 实测五百九十七【推广到所有链路】。
 *
 * ── 需求原文（五百九十一）──
 * "放置方块/给重生锚充能/扔tnt的时候要有一个动作。同时使用动作的时候替换一下副手上的物品。
 * 放置完重生锚/末地水晶后 0.5s 也会有一个挥臂的动作（好像真的打了一下末影水晶/重生锚）"
 *
 * ── 需求原文（五百九十七）──
 * "在放置重生锚的时候副手应该要拿对应的方块。使用荧石激活的时候也是。我们这个新的版本加的
 * 任何一个链路行动的时候副手都应该拿对应的物品。这样更拟真。现版本只有动作。"
 * ——于是本类从"轰炸专用"变成**全模组公用的动作表现**：{@link #showGated} 给所有"从背包掏出
 * 一件东西立刻用掉"的链路调用（搭路 / 火把 / 建造 / 种植 / 酿造 / 喂食主人 / 轰炸），
 * 拿的就是她这一下真正在用的那一件。**已在主手看得见的链路（挖矿的镐、砍树的斧、耕地/骨粉）
 * 不需要**，那是重复显示。
 *
 * ── 做法（照 实测五百一十一 的 {@link FlightFireworkPose} 同款：只补表现，原物必还）──
 * 1. 动作那一刻：主手由调用方 `swing`（服务端会把挥臂广播给客户端）+ 把"她正在用的那件东西"
 *    放进副手举 `ticks` tick；到点把**原副手整栈**（含 NBT/数量）原样写回。
 * 2. 起爆那一刻（末地水晶 / 重生锚 / 床 / 她自己扔的 TNT）：只有她还在近处（8 格内）时才挥一记，
 *    副手亮的是当时那一件——观感就是"她真的打了一下水晶/重生锚"。
 * 3. **不碰任何运行逻辑**：展示件是**复制品**（copy 后只留 1 个），不进背包、不参与任何判定；
 *    材料消耗与投掷判定都在调用方，这里只负责"看起来在做事"。
 * 4. **有界 + 可兜底**：每个女仆至多一条倒计时；{@code tick} 由 core 行为
 *    {@code MaidToolAutoEquipBehavior} 每 tick 调用（任何 activity 都跑，是最可靠的归还入口）。
 * 5. **与烟花姿势互不打扰**：{@link FlightFireworkPose} 正在用副手时本类**不动手**
 *    （见 {@link #show}：这时**记一条待办**，等它还原完的下一 tick 再举，见 {@link #tick}
 *    ——旧版是直接丢弃、于是那一记动作在视觉上整个消失）。
 *    已经举着的若碰上它开始，就等它还原完再还原（见 {@link #tick}）——两套"先快照再还原"
 *    若互相覆盖，会把盾牌/食物这类原物弄丢。
 * 6. **比 {@link FlightFireworkPose} 多做一步**：这里记的是**弱引用**，所以 forget / clearAll 清表时
 *    只要她还活着就顺手把原物还回去（弱引用已被回收说明她自己也没了，那就没什么可还的）。
 *
 * ── 实测五百九十七（三件事）──
 * ① **改完立刻广播装备包**（{@link #syncOffhand}）。原版靠 {@code LivingEntity} 每 tick 的
 *    装备变更检测（检测 → 处理 → ServerChunkCache.broadcastAndSend）把副手变化发给追踪者，
 *    理论上同 tick 就到位；但展示窗口只有 10 tick，任何一环没跑（她自己那一 tick 被别的
 *    逻辑提前 return、或追踪者刚好这一 tick 才加入）就整段看不见。这里**主动补一包**
 *    {@code ClientboundSetEquipmentPacket}，把"副手亮一下"从"应该会同步"变成"一定同步"。
 * ② **不再静默丢弃**：见第 5 条。
 * ③ **总开关改名**为"动作表现"（{@code combat.bombing.pose} 这条配置沿用，语义扩到全链路）。
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
        /** 原副手整栈快照 */
        final ItemStack original;
        /** 我们放进副手的那件展示品（可能被换成新的一件） */
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
        } catch (Throwable ignored) {
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
                maid.setItemInHand(InteractionHand.OFF_HAND, want);
                syncOffhand(maid);
                return;
            }
            ItemStack original = maid.getOffhandItem().copy(); // 副手整栈快照（含 NBT/数量）
            maid.setItemInHand(InteractionHand.OFF_HAND, want);
            syncOffhand(maid);
            // v1.2.2 实测五百九十九【"所有链路的副手都不换"的真凶就在这里】：
            // 旧版新建 State 时**忘了把 ticks 写进 left**（字段默认 0）→ 下一 tick 判定
            // `left - 1 <= 0` 立刻还原 → 那件东西只在副手上存在了 **1 tick**（16 毫秒），
            // 玩家屏幕上根本看不到（实测：请求 40 tick，+2 tick 就已经是原物）。
            // 注意 `cur != null` 那条分支一直是好的（那里显式写了 cur.left），所以"连续两次
            // 展示"时看起来正常——这也是这个 bug 一直没被发现的原因。
            State fresh = new State(original, want, maid);
            fresh.left = Math.max(1, ticks);
            ACTIVE.put(id, fresh);
        } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
        }
    }

    /** 这个女仆此刻是不是正举着东西（供别处让位用） */
    public static boolean isShowing(EntityMaid maid) {
        try {
            return maid != null && ACTIVE.containsKey(maid.getUUID());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * v1.2.4【搭路刷物品·issue #19】：副手此刻是不是被"动作表现"借去展示了——**取材方必须先
     * 问这里，借走期间一律不许把副手那件当材料**。
     *
     * 起因就是本类 {@link #show} 把展示件写进的是**真实副手槽**（复制品，但货真价实、能被
     * {@code extractItem} 扣走），而类注释里"不进背包、不参与任何判定"这句在**扫双手取材**的
     * 链路上并不成立：搭路节奏默认 4 tick、展示 10 tick，下一次取材时展示件还在副手上，
     * 于是取材扣的是它、扣完这里又补一件 —— 真料永不减少，可方块到期回收是逐格还一件真物品
     * （{@link com.maidsmart.task.PlacedBlockTracker#reclaimDrops}）。实测：给 1 个铁块，
     * 铺了 128 格桥并全部回收 → 净多 127 个（GitHub issue #19 同源）。
     *
     * 起飞烟花姿势（{@link FlightFireworkPose}）同样借副手，一并算进来；两边都归还干净后
     * 本判定自然回到 false，取材恢复正常（副手挂方块是玩家的正常用法，不受影响）。
     */
    public static boolean offhandBorrowed(EntityMaid maid) {
        try {
            return maid != null && (isShowing(maid) || FlightFireworkPose.isShowing(maid));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * v1.2.2 实测六百〇九：**我们借走的那件副手物品**的只读查询（没借走就返回空栈）。
     *
     * 【为什么必须有】展示窗口里那件东西**不在任何槽位里**——它就是本类 {@code State.original}
     * 那份快照（原栈已被 {@code setItemInHand} 顶掉）。于是"她身上有没有某件东西"的判定
     * （{@code MaidFlightKit.hasFirework/hasFan}、{@code TwilightFanKit.hasFan}）只要还只看
     * 槽位，就会在**玩家那件东西明明在她身上**的十几 tick 里得到 false。
     *
     * 实测现场（粉丝反馈"明明拿着孔雀羽扇却说缺飞行道具、系统消息不停"）：玩家把羽扇挂在
     * 她副手，空袭每隔十几秒起手一次轰炸 → 副手被本类借走 → 就绪判定报「可以飞行的道具」；
     * 还回去之后判定又齐了、把播报冷却清零 → 下一轮再报一条，永远不停。
     * 判定口径修在 {@link com.maidsmart.combat.MaidFlightKit#borrowedOffhand}（那一处同时
     * 覆盖 {@link FlightFireworkPose}），这里只提供"借走的是哪一件"。
     */
    public static ItemStack savedOffhand(EntityMaid maid) {
        try {
            State st = maid == null ? null : ACTIVE.get(maid.getUUID());
            return st == null ? ItemStack.EMPTY : st.original;
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
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
     * 把原副手物品写回去。
     *
     * v1.2.2 实测五百九十五【不覆盖玩家刚放进去的东西】：旧版无条件
     * `setItemInHand(OFF_HAND, 原物)`——这十几 tick 的展示窗口里，玩家手动往她副手
     * 放的东西会被这一写直接顶掉（那件物品就没了）。现在只有"副手还是我们那件展示品
     * 或空着"才写回原位；玩家已经换了别的 → 原物走
     * {@link com.maidsmart.tool.MaidGiveBack}（进背包、塞不下落地），两边都不丢。
     */
    private static void restore(EntityMaid maid, State st) {
        ItemStack now = maid.getOffhandItem();
        boolean untouched = now.isEmpty()
                || (!st.shown.isEmpty() && ItemStack.isSameItemSameComponents(now, st.shown));
        if (untouched) {
            maid.setItemInHand(InteractionHand.OFF_HAND, st.original);
            syncOffhand(maid);
        } else if (!st.original.isEmpty()) {
            com.maidsmart.tool.MaidGiveBack.give(maid, st.original, "副手展示期间被换下的原物");
        }
    }

    /**
     * v1.2.2 实测五百九十七【不用等原版那一次装备同步】。
     *
     * 原版把装备变化发给追踪者的链路是 {@code LivingEntity} 每 tick 的装备变更检测 →
     * {@code ServerChunkCache.broadcastAndSend}。也就是说**正常情况下**改完副手同 tick 就会同步；
     * 但展示只有 10 tick，中间任何一环被跳过（她那一 tick 的装备检测没跑到 / 追踪者刚加入还在
     * 首包流程里）就整段看不见——而"副手亮一下"这件事的观感完全取决于它。
     * 这里主动补一包，成本是一个小包。
     */
    private static void syncOffhand(EntityMaid maid) {
        try {
            if (maid.level() instanceof ServerLevel sl) {
                sl.getChunkSource().broadcastAndSend(maid, new ClientboundSetEquipmentPacket(
                        maid.getId(),
                        List.of(Pair.of(EquipmentSlot.OFFHAND, maid.getOffhandItem().copy()))));
            }
        } catch (Throwable ignored) {
        }
    }
}
