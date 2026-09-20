package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * v1.2.2 实测五百九十一【放置 / 充能 / 投掷 / 起爆那一记动作】（纯表现，零逻辑改动）。
 *
 * ── 需求原文 ──
 * "放置方块/给重生锚充能/扔tnt的时候要有一个动作。同时使用动作的时候替换一下副手上的物品。
 * 放置完重生锚/末地水晶后 0.5s 也会有一个挥臂的动作（好像真的打了一下末影水晶/重生锚）"
 *
 * ── 做法（照 实测五百一十一 的 {@link FlightFireworkPose} 同款：只补表现，原物必还）──
 * 1. 动作那一刻：主手由调用方 `swing`（服务端会把挥臂广播给客户端）+ 把"她正在用的那件东西"
 *    放进副手举 `ticks` tick；到点把**原副手整栈**（含 NBT/数量）原样写回。
 * 2. 起爆那一刻（末地水晶 / 重生锚 / 床 / 她自己扔的 TNT）：只有她还在近处（8 格内）时才挥一记，
 *    副手亮的是当时那一件——观感就是"她真的打了一下水晶/重生锚"。
 * 3. **不碰任何运行逻辑**：展示件是**复制品**（copy 后只留 1 个），不进背包、不参与任何判定；
 *    材料消耗与投掷判定都在 {@link MaidBombing} 里，这里只负责"看起来在做事"。
 * 4. **有界 + 可兜底**：每个女仆至多一条倒计时；{@code tick} 由 core 行为
 *    {@code MaidToolAutoEquipBehavior} 每 tick 调用（任何 activity 都跑，是最可靠的归还入口）。
 * 5. **与烟花姿势互不打扰**：{@link FlightFireworkPose} 正在用副手时本类**不动手**（直接跳过）；
 *    已经举着的若碰上它开始，就等它还原完再还原（见 {@link #tick}）——两套"先快照再还原"
 *    若互相覆盖，会把盾牌/食物这类原物弄丢。
 * 6. **比 {@link FlightFireworkPose} 多做一步**：这里记的是**弱引用**，所以 forget / clearAll 清表时
 *    只要她还活着就顺手把原物还回去（弱引用已被回收说明她自己也没了，那就没什么可还的）。
 */
public final class BombPose {

    /** 让位的最大轮数（每轮 5 tick）——烟花姿势总共也就 10 tick，两轮足够 */
    private static final int MAX_DEFER = 3;

    /** maidId → 状态 */
    private static final Map<UUID, State> ACTIVE = new HashMap<>();

    private static final class State {
        /** 还剩多少 tick 归还 */
        int left;
        /** 已经为"让烟花姿势先还"等过几轮 */
        int defer;
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
     * 举一下这件东西（展示 {@code ticks} tick 后归还原副手物品）。
     *
     * @param maid    女仆
     * @param display 她这一次真正在用的那件（放置的方块 / 末地水晶 / 重生锚 / 床 / TNT）
     * @param ticks   举多久（tick）
     */
    public static void show(EntityMaid maid, ItemStack display, int ticks) {
        if (maid == null || display == null || display.m_41619_()) {
            return;
        }
        try {
            if (FlightFireworkPose.isShowing(maid)) {
                return; // 烟花姿势正在用副手：让它先（见类注释第 5 条）
            }
            UUID id = maid.m_20148_();
            ItemStack want = display.m_41777_();
            want.m_41764_(1);
            State cur = ACTIVE.get(id);
            if (cur != null) {
                cur.left = Math.max(1, ticks); // 已经在举着 → 续期 + 换成这一件（原物快照不动）
                cur.shown = want;
                maid.m_21008_(InteractionHand.OFF_HAND, want);
                return;
            }
            ItemStack original = maid.m_21206_().m_41777_(); // 副手整栈快照（含 NBT/数量）
            maid.m_21008_(InteractionHand.OFF_HAND, want);
            ACTIVE.put(id, new State(original, want, maid));
        } catch (Throwable ignored) {
        }
    }

    /** 每 tick 递减（由 core 行为调用；无记录时零开销） */
    public static void tick(EntityMaid maid) {
        if (maid == null || ACTIVE.isEmpty()) {
            return;
        }
        try {
            UUID id = maid.m_20148_();
            State st = ACTIVE.get(id);
            if (st == null) {
                return;
            }
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
            State st = ACTIVE.remove(maid.m_20148_());
            if (st != null) {
                restore(maid, st);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 这个女仆此刻是不是正举着东西（供别处让位用） */
    public static boolean isShowing(EntityMaid maid) {
        try {
            return maid != null && ACTIVE.containsKey(maid.m_20148_());
        } catch (Throwable ignored) {
            return false;
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
        restoreIfPossible(ACTIVE.remove(maidId));
    }

    public static void clearAll() {
        for (State st : ACTIVE.values()) {
            restoreIfPossible(st);
        }
        ACTIVE.clear();
    }

    private static void restoreIfPossible(State st) {
        if (st == null) {
            return;
        }
        EntityMaid maid = st.ref.get();
        if (maid != null && maid.m_6084_()) {
            restore(maid, st);
        }
    }

    /** 把原副手物品写回去（口径与 FlightFireworkPose 一致：到了就还，不做条件判断） */
    private static void restore(EntityMaid maid, State st) {
        maid.m_21008_(InteractionHand.OFF_HAND, st.original);
    }
}
