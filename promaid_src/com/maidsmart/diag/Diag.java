package com.maidsmart.diag;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 诊断助手：给"吞掉异常"的 catch 一个统一的留痕出口。
 *
 * <p>历史问题：全项目有数千处 {@code catch (... ignored)} 空块，出 bug 时日志一片空白
 * （"她不动、日志什么都没有"）。本类提供 {@link #swallow(String, Throwable)}：同一个站点只记
 * 一次（不刷屏），并把抛出点栈帧与异常一并写进日志，让"空白"变成"一条能定位的记录"。
 *
 * <p>分两档：反射/类加载探测"目标不在场"（可选模组没装）属预期，只写 DEBUG；
 * 其余一律 WARN 并附栈。这样既补上可见性，又不会让"可选模组没装"刷一屏警告。
 *
 * <p>站点标识 = 传入标签 + 抛出点栈帧（类.方法:行）；标签用类名即可，两树保持同一个字符串。
 */
public final class Diag {
    private static final Logger LOGGER = LoggerFactory.getLogger("promaid");
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    private Diag() {
    }

    /** 在 {@code catch (... ignored)} 处调用：同一站点只留一条记录（含抛出点与异常），避免刷屏。 */
    public static void swallow(String tag, Throwable t) {
        if (t == null) {
            return;
        }
        String site = tag;
        StackTraceElement[] frames = t.getStackTrace();
        if (frames.length > 0) {
            site = tag + " @" + frames[0];
        }
        if (!SEEN.add(site)) {
            return;
        }
        if (isProbeAbsence(t)) {
            LOGGER.debug("[promaid] 探测不可用（预期内）{}: {}", site, String.valueOf(t));
        } else {
            LOGGER.warn("[promaid] 已吞掉异常 {}: {}", site, String.valueOf(t), t);
        }
    }

    /** 反射 / 类加载探测"目标不在场"这一类失败：属预期，不算异常（与 WORKFLOW §2.4 的"未知 ≠ 不存在"同源）。 */
    private static boolean isProbeAbsence(Throwable t) {
        return t instanceof ClassNotFoundException
                || t instanceof NoClassDefFoundError
                || t instanceof NoSuchMethodException
                || t instanceof NoSuchFieldException;
    }
}
