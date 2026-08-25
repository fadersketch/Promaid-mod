package com.maidsmart.tool;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/**
 * 运行日志（v1.1.0 实测九十四）——把各系统的【状态迁移事件】落盘到独立文件，
 * 方便日后验查（用户："补上日志记录的功能，方便以后进行验查"）。
 *
 * 文件：游戏目录/logs/promaid.log（UTF-8 追加写，满 4MB 自动轮换为
 * promaid.log.old，仅保留一代——不无限膨胀）。同时镜像一行到 latest.log
 *（INFO 级，前缀 [promaid/分类]）。
 *
 * 只记低频事件：排班段应用、战斗参战/还原/僵局阀/任务被接管、险境脱离挪格+
 * 应急灭火、跨维跟随传送、自保标记自愈。巡检类每秒扫描（调度扫描/还原扫描/
 * 危险巡检的"无事发生"路径）一律不落盘，避免日志爆炸。
 *
 * 安全性：任何 IO 异常静默吞掉——日志系统自身故障绝不外溢影响游戏逻辑；
 * 总开关关闭时完全静默（文件和 latest.log 都不写）。
 */
public final class PromaidLog {
    private static final org.slf4j.Logger SLF = com.mojang.logging.LogUtils.getLogger();
    private static final Object LOCK = new Object();
    /** 单文件上限：超过即整体改名轮换（保留一代 .old） */
    private static final long MAX_BYTES = 4L * 1024 * 1024;
    private static final java.time.format.DateTimeFormatter TS =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private PromaidLog() {
    }

    /** 女仆显示名（自定义名为空时退回 UUID 串） */
    public static String nameOf(EntityMaid maid) {
        if (maid == null) {
            return "null";
        }
        var c = maid.m_5446_();
        return c != null ? c.getString() : maid.m_20148_().toString();
    }

    /** 记一条事件（分类建议用中文短词：排班/战斗/险境脱离/跨维/自保） */
    public static void log(String category, String message) {
        try {
            if (!com.maidsmart.config.MaidSmartConfig.MISC_LOG_ENABLED.get()) {
                return;
            }
            synchronized (LOCK) {
                java.nio.file.Path dir = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()
                        .resolve("logs");
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Path f = dir.resolve("promaid.log");
                if (java.nio.file.Files.exists(f) && java.nio.file.Files.size(f) > MAX_BYTES) {
                    try {
                        java.nio.file.Files.move(f, dir.resolve("promaid.log.old"),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception ignored) {
                        // 轮换失败（文件被占用等）就继续追加原文件
                    }
                }
                String line = "[" + java.time.LocalDateTime.now().format(TS) + "] ["
                        + category + "] " + message + System.lineSeparator();
                java.nio.file.Files.write(f,
                        line.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            }
            SLF.info("[promaid/{}] {}", category, message);
        } catch (Throwable t) {
            // 日志系统自身的故障绝不外溢
        }
    }
}
