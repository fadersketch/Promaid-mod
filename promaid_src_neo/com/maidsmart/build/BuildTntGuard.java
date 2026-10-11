package com.maidsmart.build;

/**
 * TNT 建造期防自燃护栏——配合 MixinTntBuildGuard 使用。仅线程标记只挡 doPlace 放置瞬间的 onPlace，
 * 挡不住"机器电路推动"的点火链（实测：320 个 TNT 六邻无红石，靠观察者→活塞推 TNT，活塞推动发生在
 * doPlace 之外，落位后活塞被放置事件触发 → 推 TNT → 移动重放的 onPlace 检测到邻接带电 → 点燃 →
 * 全链引爆 = "刚建好直接炸膛"），故升级为时间窗。
 *
 * 护栏：① {@code suppressing(level)} = 线程标记（放置瞬间）|| 时间窗内（gameTime &lt;
 * suppressUntilTick），时间窗覆盖【建造期 + 完工激活期 + 宽限期】，任何点火入口（放置 / 活塞推动的
 * onPlace / neighborChanged）都被压制；② 窗口由每次放置与完工激活前延长，宽度 =
 * {@code BUILD_TNT_IGNITION_GRACE}；③ 完工时做"点火结算"——只点燃【当前邻接带电】的 TNT，宽限期满后
 * 机器按正常红石逻辑点火（"建好就能跑/手动触发"不受影响）。
 */
public final class BuildTntGuard {
    private static final ThreadLocal<Boolean> SUPPRESS =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 保护窗口截止游戏刻（含宽限期；游戏刻恒 ≥0，Long.MIN_VALUE = 永不压制） */
    private static long suppressUntilTick = Long.MIN_VALUE;

    private BuildTntGuard() {
    }

    /** 是否处于"TNT 点火压制"内（MixinTntBuildGuard 检查：放置/活塞推动/邻居更新） */
    public static boolean suppressing(net.minecraft.server.level.ServerLevel level) {
        if (SUPPRESS.get()) {
            return true;
        }
        return level != null && level.getGameTime() < suppressUntilTick;
    }

    /** 延长保护窗口（游戏刻；多次调用取最大——建造期每次放置都刷新） */
    public static void suppressTntFor(net.minecraft.server.level.ServerLevel level, int ticks) {
        if (level == null) {
            return;
        }
        suppressUntilTick = Math.max(suppressUntilTick, level.getGameTime() + Math.max(0, ticks));
    }

    public static void setSuppress(boolean v) {
        SUPPRESS.set(v);
    }
}
