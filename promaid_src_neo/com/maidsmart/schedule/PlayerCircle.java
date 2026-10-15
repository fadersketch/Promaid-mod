package com.maidsmart.schedule;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/**
 * <p>【为什么需要单独记一件事】TLM 的 {@code SchedulePos} 只存"三个锚点 + configured + 维度"，
 * 不记来源。而锚点有三个写入方：
 * <ol>
 *   <li><b>河童的罗盘</b>（{@code ItemKappaCompass}）——玩家亲手画；</li>
 *   <li><b>排班表开启</b>（{@link ScheduleManager#applyNow}）——她没配过锚点时，拿
 *       <b>当前站的地方</b>自动锚定；</li>
 *   <li><b>对话工具</b>{@code smart_work_area}（{@link com.maidsmart.tool.WorkAreaTool}）
 *       ——玩家/模型明确说"这块归你管"。</li>
 * </ol>
 * 三者写的是同一份存储（谁后写谁生效）。
 * <p>【口径】玩家画过的圈在本模组侧打上 {@link #PLAYER_TAG}；我们自己的自动锚定打
 * {@link #AUTO_TAG}。**只要 PLAYER 在，{@link #mayAutoAnchor} 就恒为 false**——排班/建造 的一切"自动拿当前位置当锚点"都被拦下，玩家画的圈一直有效，直到玩家自己再画一次（罗盘会重新打 PLAYER）或在 TLM GUI 里改、或把罗盘切到清除模式清掉（{@link #clearPlayerCircle}）。 <p>【不干预】本类只回答"这圈是不是玩家画的"，不参与各写入方的排序。开关 {@code misc.compassPriority}； 关掉 = 本类判定恒放行（回到旧行为）。
 */
public final class PlayerCircle {
    /** 玩家亲手画过圈（罗盘 / 明确的"这块归你管"） */
    public static final String PLAYER_TAG = "maid_smart_player_circle";
    /** 本模组自动锚定过（排班开启时拿当前位置兜底）——与 PLAYER_TAG 互斥 */
    public static final String AUTO_TAG = "maid_smart_auto_anchor";

    private PlayerCircle() {
    }

    private static net.minecraft.nbt.CompoundTag data(EntityMaid maid) {
        return ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData();
    }

    /** 玩家画了圈（罗盘写锚点、或明确"这块归你管"）——落标记，自动锚定从此让位。 */
    public static void markPlayerCircle(EntityMaid maid) {
        try {
            if (maid == null) {
                return;
            }
            data(maid).putBoolean(PLAYER_TAG, true);
            data(maid).putBoolean(AUTO_TAG, false);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PlayerCircle", ignored);
        }
    }

    /** 圈被玩家清掉了（罗盘切清除模式右键女仆）——撤销标记，允许重新自动锚定。 */
    public static void clearPlayerCircle(EntityMaid maid) {
        try {
            if (maid != null) {
                data(maid).putBoolean(PLAYER_TAG, false);
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PlayerCircle", ignored);
        }
    }

    /** 本模组自动锚定了——落标记（与玩家圈互斥）。 */
    public static void markAutoAnchor(EntityMaid maid) {
        try {
            if (maid == null) {
                return;
            }
            data(maid).putBoolean(AUTO_TAG, true);
            data(maid).putBoolean(PLAYER_TAG, false);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PlayerCircle", ignored);
        }
    }

    /** 这个圈是不是玩家亲手画的（且开关开着）。 */
    public static boolean isPlayerCircle(EntityMaid maid) {
        try {
            if (maid == null || !com.maidsmart.config.MaidSmartConfig.MISC_COMPASS_PRIORITY.get()) {
                return false;
            }
            return data(maid).getBoolean(PLAYER_TAG);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PlayerCircle", ignored);
            return false;
        }
    }

    /**
     * 允许"拿当前坐标自动锚定"吗？——玩家画过圈就一律不允许（罗盘优先）。
     * 开关关掉时恒 true（旧行为：没配过锚点就自动锚定）。
     */
    public static boolean mayAutoAnchor(EntityMaid maid) {
        try {
            if (!com.maidsmart.config.MaidSmartConfig.MISC_COMPASS_PRIORITY.get()) {
                return true;
            }
            return !data(maid).getBoolean(PLAYER_TAG);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PlayerCircle", ignored);
            return true;
        }
    }

    /** 诊断一行（日志里搜「罗盘优先」）。 */
    public static String describe(EntityMaid maid) {
        try {
            if (maid == null) {
                return "无女仆";
            }
            return "玩家圈=" + data(maid).getBoolean(PLAYER_TAG)
                    + " 自动锚定=" + data(maid).getBoolean(AUTO_TAG)
                    + " 开关=" + com.maidsmart.config.MaidSmartConfig.MISC_COMPASS_PRIORITY.get()
                    + " 允许自动锚定=" + mayAutoAnchor(maid);
        } catch (Throwable t) {
             com.maidsmart.diag.Diag.swallow("PlayerCircle", t); return "读取异常：" + t;
        }
    }
}
