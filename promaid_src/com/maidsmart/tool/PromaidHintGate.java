package com.maidsmart.tool;

/**
 * 【R1109】"气泡与系统消息"总开关的判定：关掉时只静默**本模组自己发**的聊天系统消息，
 * 原版与别的模组的消息一律不动。判定来源用调用栈（本模组包名 com.maidsmart.）——
 * 模组内 170 处 sendSystemMessage 不必逐个改。
 *
 * <p>开关开着（默认）时连栈都不取：判定只发生在"玩家已经把提示关掉"之后。
 */
public final class PromaidHintGate {

    private PromaidHintGate() {
    }

    /** 是否应当吞掉这一条系统消息 */
    public static boolean shouldSuppress() {
        try {
            if (com.maidsmart.config.MaidSmartConfig.MISC_PROMAID_HINTS.get()) {
                return false;
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidHintGate", ignored);
            return false;
        }
        return fromThisMod();
    }

    /** 调用栈里有没有本模组（跳过门自己那一帧） */
    private static boolean fromThisMod() {
        try {
            for (StackTraceElement e : new Throwable().getStackTrace()) {
                String c = e.getClassName();
                if (c.equals("com.maidsmart.mixin.PromaidMessageGateMixin")) {
                    continue;
                }
                if (c.startsWith("com.maidsmart.")) {
                    return true;
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidHintGate", ignored);
        }
        return false;
    }
}
