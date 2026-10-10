package com.maidsmart.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 【实测八百三十四】渲染护栏：本模组的渲染入口一律"吞异常、不把异常扔给渲染线程"，
 * 可原版那些渲染方法内部是 push 之后直接 pop、**没有 finally**——一旦中间抛异常，那一帧的
 * push 就永远留在姿态栈上；几十毫秒后由 {@code LevelRenderer} 的 checkPoseStack 抛出
 * {@code IllegalStateException("Pose stack not empty")}，渲染线程致命、重进世界照旧崩，
 * 而且因为首个异常早被我们吞掉，崩溃报告里只剩这一句、看不出是谁抛的（issue #37 的扫帚右击闪退）。
 *
 * <p>办法：进渲染前量一次栈深、离开时按差值补 pop；同时把吞掉的异常记一行日志（每处一次）。
 * 量不到栈深（反射失手）就什么都不做 —— 行为与改动前一致，护栏自己绝不抛异常。
 */
@OnlyIn(Dist.CLIENT)
public final class MaidRenderGuard {

    /** PoseStack 内部那个栈字段（两版都是 {@code java.util.Deque}，按类型找、不看名字） */
    private static java.lang.reflect.Field stackField;
    private static boolean stackFieldProbed;
    /** 每个入口只记一次日志，否则每帧一行会把 promaid.log 刷爆 */
    private static final java.util.Set<String> LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private MaidRenderGuard() {
    }

    /** 当前栈深；量不到返回 -1（调用方据此放弃修复） */
    public static int depthOf(PoseStack poseStack) {
        try {
            if (poseStack == null) {
                return -1;
            }
            java.lang.reflect.Field f = stackFieldOf(poseStack.getClass());
            if (f == null) {
                return -1;
            }
            Object v = f.get(poseStack);
            return v instanceof java.util.Deque<?> deque ? deque.size() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 把栈补回 {@code depth} 层：只补 pop、绝不把调用方自己的帧多弹掉；量不到就什么都不做 */
    public static void restore(PoseStack poseStack, int depth) {
        if (poseStack == null || depth < 0) {
            return;
        }
        try {
            for (int guard = 0; guard < 64; guard++) {
                int now = depthOf(poseStack);
                if (now < 0 || now <= depth) {
                    return;
                }
                poseStack.m_85849_();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 被吞掉的异常留一行痕（同一处只记一次）——免得下次又只剩一句"Pose stack not empty" */
    public static void noteSwallowed(String where, Throwable t) {
        try {
            if (t == null || !LOGGED.add(where)) {
                return;
            }
            com.maidsmart.tool.PromaidLog.log("渲染护栏", where
                    + " 内部抛了异常、已吞掉（姿态栈已补回，不会崩游戏）："
                    + t.getClass().getName() + " / " + t.getMessage());
        } catch (Throwable ignored) {
        }
    }

    private static java.lang.reflect.Field stackFieldOf(Class<?> c) {
        if (!stackFieldProbed) {
            stackFieldProbed = true;
            try {
                for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                    for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                        if (f.getType() == java.util.Deque.class) {
                            f.setAccessible(true);
                            stackField = f;
                            return stackField;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return stackField;
    }
}
