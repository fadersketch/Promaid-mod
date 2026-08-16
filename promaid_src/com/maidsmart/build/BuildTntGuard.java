package com.maidsmart.build;

/**
 * v1.5.328：TNT 建造期防自燃护栏——线程级标记，配合 MixinTntBuildGuard 使用。
 *
 * v1.5.331【升级为时间窗】：仅线程标记只挡住 doPlace 放置瞬间的 onPlace——
 * 挡不住"机器电路推动"的点火链。实测（天机屠龙炮/箭矢加速炮，Never4Get 蓝图）：
 * 320 个 TNT 六邻【无任何红石组件】（玻璃/TNT/石英/栅栏门/活塞/釉陶），点火靠
 * 【观察者 → 活塞 → 推动 TNT】——活建造下机器电路随建随跑，TNT 落位后观察者/
 * 活塞被放置事件触发 → 推 TNT → 移动重放的 onPlace 检测到邻接带电 → 点燃 →
 * 全链引爆 = "刚建好直接炸膛"（活塞推动发生在 doPlace 之外，线程标记覆盖不到）。
 *
 * 本护栏：
 * - suppressing(level)：线程标记（放置瞬间）|| 时间窗内（gameTime < suppressUntilTick）——
 *   时间窗覆盖【建造期 + 完工激活期 + 宽限期】，任何 TNT 点火入口（放置/活塞推动
 *   的 onPlace、邻居更新的 neighborChanged）都被压制；
 * - 窗口由每次放置（placeTntSafe）与完工激活前延长，宽度 = BUILD_TNT_IGNITION_GRACE；
 * - 完工时 BlueprintLib.settleTntIgnition 做"点火结算"——只点燃【当前邻接带电】的
 *   TNT（轰炸机矿车压轨当场启动；天机屠龙炮 TNT 静止无电 → 保持惰性，触发时才点火），
 *   宽限期满后机器按正常红石逻辑点火（"建好就能跑/手动触发"不受影响）。
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
        return level != null && level.m_46467_() < suppressUntilTick;
    }

    /** 延长保护窗口（游戏刻；多次调用取最大——建造期每次放置都刷新） */
    public static void suppressTntFor(net.minecraft.server.level.ServerLevel level, int ticks) {
        if (level == null) {
            return;
        }
        suppressUntilTick = Math.max(suppressUntilTick, level.m_46467_() + Math.max(0, ticks));
    }

    public static void setSuppress(boolean v) {
        SUPPRESS.set(v);
    }
}
