package com.maidsmart.patrol;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.3.8【巡逻航迹】校验汇总：几何（{@link PatrolGeometry}）+ 净空（{@link PatrolClearance}）合成一份结论。
 *
 * <p>几何不需要世界，净空需要——所以"只做几何"这条入口（编辑器打开时、纯客户端）也留在这里。
 *
 * <p>【v1.3.9.2：净空不再是门槛】玩家实测反馈「判定过严、一直在说有方块阻挡」，于是净空不通过
 * 只作为一句**提示**放进 {@code notes}（见 {@link #validate}）。能不能飞过去交给运行时：
 * {@link PatrolAdapt} 抬一抬，{@code MaidBroomDrive.steerTo} 的卡墙脱困与危险绕行再兜一道。
 *
 * <p>【v1.3.9.3：连接 / 绑定也不再有门槛】玩家原话：「连接方面就不要再加入门禁了，强制连接，
 * 后果由玩家自己负责。原来那些门禁可以作为一个提醒，触犯了以后就提醒一下。」⇒ 连接与绑定
 * **一律照做**（{@code PatrolNetworking} 里不再有 return 型拒绝），
 * {@link PatrolGeometry.Report#problems()} 里的每一项（标记不够 / 陡坡 / 自交 / 未闭环）都由
 * {@link #warnings} 渲染成一句 ⚠ 提醒；半径超限在调用方单独提醒。{@link #lines} 仍是给
 * {@code /maid_smart broom_patrol status} 看的诊断输出。
 */
public final class PatrolValidation {

    private PatrolValidation() {
    }

    /**
     * 只做几何（不看方块）：**编辑器每 0.5 秒的定时刷新**与打点后的回推走这一条。
     *
     * <p>【为什么刷新不能走 {@link #validate}】净空要逐点扫方块（一条 128 格半径的航迹是数万次
     * {@code getCollisionShape}），而刷新是每 0.5 秒一次——只有"点连接"与"右键绑定女仆"这两处
     * 才值得全量扫描。刷新只要列表/时长/几何问题跟着变就够了。
     */
    public static PatrolGeometry.Report geometryOnly(PatrolRoute route) {
        return PatrolGeometry.geometryReport(route);
    }

    /**
     * 完整校验（几何 + 净空）。{@code level} 为 null 时净空一项按"未检查"放行。
     */
    public static PatrolGeometry.Report validate(Level level, PatrolRoute route) {
        PatrolGeometry.Report g = PatrolGeometry.geometryReport(route);
        if (level == null || route == null || route.size() < PatrolRoute.MIN_POINTS) {
            return g;
        }
        List<String> problems = new ArrayList<>(g.problems());
        List<String> notes = new ArrayList<>(g.notes());
        boolean clearanceOk = true;
        try {
            if (route.closed()) {
                List<Vec3> poly = PatrolCurve.polyline(route.points(), true, 0.35);
                PatrolClearance.Result cres = PatrolClearance.check(level, poly, route.clearance());
                clearanceOk = cres.ok();
                if (!cres.ok()) {
                    // 【v1.3.9.2】挡路方块**不再是门槛**（玩家原话："不要把它做一个门槛了，交给女仆
                    // 自己的寻路"）。它只作一句**黄色提示**：连接照样成功，飞的时候 PatrolAdapt 会把
                    // 航线抬一抬、steerTo 的脱困与危险绕行再兜一道。
                    PatrolClearance.Hit h = cres.hits().get(0);
                    notes.add(String.format(
                            "⚠ 沿航线查到 %d 处可能有方块挡路（第一处在 %.0f, %.0f, %.0f 是 %s%s）——"
                                    + "不影响连接，飞行时她自己会绕开/抬升",
                            cres.hits().size(), h.pos().x, h.pos().y, h.pos().z, h.block(),
                            h.dangerous() ? "，危险方块" : ""));
                } else {
                    notes.add(PatrolClearance.describe(cres));
                }
            } else {
                clearanceOk = false; // 没闭环就没得查净空——连接那一步才是查它的时机
            }
        } catch (Throwable t) {
            notes.add("净空校验异常（按未检查处理）：" + t);
        }
        return new PatrolGeometry.Report(g.geometryOk(), clearanceOk, problems, notes,
                g.length(), g.seconds(), g.maxSlopeDeg(), g.radius(), g.center());
    }

    /**
     * 【v1.3.9.3】把原来的"门禁问题"渲染成**提醒**：连接 / 绑定不再被这些拦住，只是照实说一声
     * （玩家原话：「原来那些门禁可以作为一个提醒，触犯了以后就提醒一下」）。空列表 = 没有要提醒的。
     *
     * <p>半径超限不在 {@code problems} 里（它来自 {@link PatrolCommand#radiusAllowed}），
     * 由调用方自己补一句。
     */
    public static List<String> warnings(PatrolGeometry.Report rep) {
        List<String> out = new ArrayList<>();
        if (rep == null || rep.problems() == null) {
            return out;
        }
        for (String p : rep.problems()) {
            out.add("§e⚠ " + p);
        }
        return out;
    }

    /** 把所有条目拼成若干行（编辑器/聊天栏逐行显示） */
    public static List<String> lines(PatrolGeometry.Report rep) {
        List<String> out = new ArrayList<>();
        // 【v1.3.9.2】"通过"只看几何——净空已降级为提示（见 validate 里的说明）
        if (rep.problems().isEmpty() && rep.geometryOk()) {
            out.add("§a✔ 校验通过");
        }
        for (String p : rep.problems()) {
            // 【v1.3.9.3】这些项已不再拦任何动作，报告里也就用 ⚠ 而不是 ✘
            out.add("§e⚠ " + p);
        }
        for (String n : rep.notes()) {
            out.add("§7· " + n);
        }
        return out;
    }
}
