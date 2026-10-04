package com.maidsmart.patrol;

import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * v1.3.9【巡逻航图 · 标记态】"正在打标记"这件事的服务端权威状态（按玩家 UUID）。
 *
 * <p>── 玩家原话（这一版的核心口径）──
 * 「开始标记之后玩家会暂时退出那个界面，系统也会提示玩家，这个时候可以开始进行标记了，点击鼠标中键
 * 进行标记。注意，如果你没有点击这个开始标记这个方式，那么你使用中键是没有任何用处的。也就是说，
 * 你只是一开始就把巡逻航图拿在手上中键是没用的。」
 *
 * <p>⇒ 中键打点**不再是无条件的手势**，而是"先进入标记态、再打点"。判定只能放在服务端：
 * 客户端自己说"我在标记"不算数（物品数据、坐标、状态全在服务端一侧收口，见
 * {@code PatrolNetworking.MarkPatrolPointPacket}）。
 *
 * <p>── 生命周期 ──
 * <ul>
 *   <li>起点：界面里点「开始标记」→ {@code StartMarkingPacket(start=true)}；</li>
 *   <li>终点：再点一次「结束标记」，或**右键航图回到界面**（服务端在 {@code openFor} 里主动清），
 *       或者玩家下线（{@code PatrolChartKit} 的登出钩子）。</li>
 * </ul>
 * 只记一个 {@code routeId}：标记永远只落到"开始标记时那一条轨道"上，玩家中途改名/新建别的轨道
 * 都不会把点打到别处。
 *
 * <p>【为什么不做成物品上的 NBT 标志】那会让"标记中"这件事跟着物品走（丢给别人也还在标记）。
 * 标记是**某个玩家此刻的动作**，按玩家 UUID 记才符合语义；也顺带避免了物品数据每打一个点就
 * 多同步一次网络。
 */
public final class PatrolMarking {

    /** 玩家 UUID → 正在标记的轨道 id（值是空串 = 标记但没指定轨道，视为"当前选中的那条"） */
    private static final Map<UUID, String> ACTIVE = new HashMap<>();

    private PatrolMarking() {
    }

    /** 进入标记态（{@code routeId} 为空 = 落到"当前选中那条"） */
    public static void start(ServerPlayer player, String routeId) {
        if (player == null) {
            return;
        }
        ACTIVE.put(player.getUUID(), routeId == null ? "" : routeId);
    }

    /** 退出标记态 */
    public static void stop(ServerPlayer player) {
        if (player != null) {
            ACTIVE.remove(player.getUUID());
        }
    }

    /** 玩家下线：地图条目别留着（与 {@link #stop} 同义，单独取名只是为了调用点读得懂） */
    public static void clear(ServerPlayer player) {
        stop(player);
    }

    public static boolean active(ServerPlayer player) {
        return player != null && ACTIVE.containsKey(player.getUUID());
    }

    /** 正在标记的轨道 id（空串 = 没指定 / 没在标记） */
    public static String routeId(ServerPlayer player) {
        if (player == null) {
            return "";
        }
        String s = ACTIVE.get(player.getUUID());
        return s == null ? "" : s;
    }
}
