package com.maidsmart.schedule;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.maidsmart.combat.AutoCombatSwitch;
import com.maidsmart.combat.SelfPreservationBehavior;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;

import java.util.List;

/**
 * 排班调度器（v1.1.0）——按游戏内时间应用每女仆的排班表。
 *
 * 每秒（20 tick）扫描全部已加载女仆：排班开启 → 找当前时间所在段 →
 * 与"已应用段"（去抖键：天数|段起点）不同才应用 setSchedule(工作模式) + setTask(任务)。
 *
 * 优先级让位：自保中不切（自保优先）；主动战斗中不切（战斗还原后由排班接管）。
 * 优先级链：自保 > 排班表 > 主动战斗（还原）> 玩家手动/LLM——排班在主动战斗之上：
 * 玩家手动切的任务由去抖键保护（touchAppliedKey），同一段内不会被排班翻回去。
 */
public final class ScheduleManager {
    private static int throttle = 0;

    /** v1.1.0 实测一百二十九：排班诊断日志限频表——1 秒扫描的"让位/去抖/休息"
     *  类低频跳过每 60 秒记一条（防刷屏），真正的应用/失败/异常全量落盘 */
    private static final java.util.Map<String, Long> DIAG_SINCE = new java.util.HashMap<>();

    /** v1.1.0 实测一百二十九：段应用失败后的重试节流（maidId → 下次重试 gameTime）——
     *  持久失败（任务不存在/守卫拒绝）若每秒重试，setSchedule 会每秒 refreshBrain
     *  重建 AI（比不排班更扰民）；限频 10 秒一轮，条件解除后最多 10 秒恢复 */
    private static final java.util.Map<java.util.UUID, Long> RETRY_AFTER = new java.util.HashMap<>();

    /** v1.1.0 实测一百三十五：本段是否已尝试应用 + 尝试那一刻的任务（maidId → "段键|任务UID"）。
     *  用于识别"排班尝试过本段之后任务被外部（玩家/命令/TLM GUI）改走"：尊重手动选择，
     *  写去抖键结束本段，不再死磕重试把玩家改的任务顶回去/无限重试（用户反馈：改排班
     *  女仆任务 → 排班会卡死）。只记尝试时刻的任务，任务没变=正常"没活不切"重试不误伤。 */
    private static final java.util.Map<java.util.UUID, String> ATTEMPTED = new java.util.HashMap<>();

    private ScheduleManager() {
    }

    /** 限频诊断日志（PromaidLog 落盘 logs/promaid.log；失败/应用类不节流，走直调） */
    private static void diag(EntityMaid maid, String reason, String msg, ServerLevel level) {
        String key = maid.m_20148_() + "|" + reason;
        long now = level.m_46467_();
        Long last = DIAG_SINCE.get(key);
        if (last != null && now - last < 1200L) {
            return; // 60 秒内同原因只记一条
        }
        DIAG_SINCE.put(key, now);
        com.maidsmart.tool.PromaidLog.log("排班", msg);
    }

    /** ProMaidExtension 构造时注册 */
    public static void register() {
        MinecraftForge.EVENT_BUS.register(new ScheduleManager());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // v1.1.0 实测一百三十三 ③：tick 开头防御性清理残留的内部 setTask 标记
        //（异常逃逸时 ThreadLocal 可能残留，防跨调用点污染）
        ScheduleSwitchGuard.clearIfStale("ScheduleManager#onServerTick");
        if (++throttle < 20) {
            return; // 每秒一次
        }
        throttle = 0;
        // v1.1.0：排班系统总开关（手册杂项页；关闭=调度停摆，已保存的日程保留）
        if (!com.maidsmart.config.MaidSmartConfig.MISC_SCHEDULE_ENABLED.get()) {
            return;
        }
        net.minecraft.world.phys.AABB whole = new net.minecraft.world.phys.AABB(
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        for (ServerLevel level : event.getServer().m_129785_()) {
            for (EntityMaid maid : level.m_45976_(EntityMaid.class, whole)) {
                if (!maid.m_6084_() || !ScheduleData.isOn(maid)) {
                    continue;
                }
                applyNow(maid, level);
            }
        }
    }

    /** 应用当前时间段的排班（去抖：段没变不重设）。保存/快捷设置后也调用。
     *  v1.1.0 实测六十一：战斗还原宽限期内不接管（还原后先让她干原任务一段时间，
     *  防威胁在还原威胁半径边缘闪烁时战斗/还原/排班反复拉扯；玩家手动保存日程会
     *  清宽限立即生效）。
     *  v1.1.0 实测一百二十九：全链路诊断日志——每一步让位/跳过/失败都落盘
     *  logs/promaid.log（低频限频防刷屏），并加【应用后任务读回校验】：TLM
     *  setTask 有守卫（睡眠/活动中等）时会静默拒绝，旧版去抖键已写过 → 本段
     *  永不重试 = 排班"应用了但没生效"的静默失效；读回对比能当场暴露。 */
    public static void applyNow(EntityMaid maid, ServerLevel level) {
        // v1.1.0 实测一百三十五：整体隔离——任何异常都不许击穿 applyNow（否则每
        // tick 抛一次 = 排班系统整体瘫痪，即用户反馈的"排班会卡死"形态之一），
        // 统一落日志 + 10 秒重试节流，下一轮继续
        try {
        String who = com.maidsmart.tool.PromaidLog.nameOf(maid);
        // v1.1.0 实测九十三：总开关闸必须设在方法最前面——applyNow 有三个调用方
        //（调度器扫描 / 保存包立即应用 / 战斗还原直通），此前只有调度器上游检查了
        // 总开关：全局关闭排班系统后，战斗还原直通仍会强制 home 模式并按旧日程
        // 应用段任务（跟随女仆打完一仗被留在在家模式、不再跟随主人）。统一在此闸住。
        if (!com.maidsmart.config.MaidSmartConfig.MISC_SCHEDULE_ENABLED.get()) {
            diag(maid, "off", who + " 排班总开关关闭（misc.scheduleEnabled=false）——调度停摆", level);
            return;
        }
        // v1.1.0 实测七十：排班中的女仆自动 home 模式——旧档已开排班的女仆在这里
        // 自动迁移；建造行为临时关过 home 的也会被重新扶正（有翻转才写，无存档压力）
        if (!maid.isHomeModeEnable()) {
            maid.setHomeModeEnable(true);
        }
        // v1.1.0 实测一百一十二【呆立根因】：home 模式必须同时给女仆一个 home 锚点。
        // TLM setHomeModeEnable(boolean) 只置 DATA_HOME_MODE 标志、不设坐标（MaidConfigManager
        // 字节码实证 = entityData.set 单行）；SchedulePos.tick（每 2 秒）的 restrictTo 拿
        // null workPos 调 setRestriction → hasRestriction=false → isWithinRestriction 恒 true
        // → tick 早退：无回家走位、无越界拉回，整个 home 机制空转 → 无任务女仆原地呆站。
        // TLM GUI 的 home 走 SchedulePos.setHomeModeEnable(maid, pos)（workPos=idlePos=
        // sleepPos=当前位置），排班路径补上这一环：未配置过的女仆以当前坐标作锚点
        // （玩家在 TLM GUI 配过 home 的保留原锚点不动；锚定后走位/越界拉回全部激活）。
        var maidSchedulePos = maid.getSchedulePos();
        if (maidSchedulePos != null && !maidSchedulePos.isConfigured()) {
            maidSchedulePos.setHomeModeEnable(maid, maid.m_20183_());
            maidSchedulePos.setConfigured(true);
            com.maidsmart.tool.PromaidLog.log("排班", who + " 排班启用：自动锚定 home（"
                    + maid.m_20183_().m_123341_() + "," + maid.m_20183_().m_123342_()
                    + "," + maid.m_20183_().m_123343_() + "），homeMode=true");
        }
        long nowTick = level.m_46467_();
        long grace = maid.getPersistentData().m_128454_(ScheduleData.GRACE_TAG);
        if (nowTick < grace) {
            diag(maid, "grace", who + " 战斗还原宽限期内让位（剩 "
                    + ((grace - nowTick) / 20) + " 秒）——排班暂不接管", level);
            return; // 宽限期内
        }
        // 自保中让位（自保优先——等血量恢复退出自保后排班照常）
        if (maid.getPersistentData().m_128471_(SelfPreservationBehavior.PRESERVE_TAG)) {
            diag(maid, "preserve", who + " 自保中——排班让位（自保优先）", level);
            return;
        }
        // 主动战斗中让位（战斗还原原任务后再由排班接管下一段）
        if (AutoCombatSwitch.isAutoCombatActive(maid)) {
            diag(maid, "combat", who + " 主动战斗中——排班让位（战斗还原后由排班接管）", level);
            return;
        }
        List<ScheduleData.Segment> segs = ScheduleData.load(maid);
        if (segs.isEmpty()) {
            diag(maid, "empty", who + " 排班开关已开但日程表为空（未保存任何段？）", level);
            return;
        }
        ScheduleData.Segment seg = ScheduleData.segmentAt(segs, ScheduleData.currentMinute(level));
        if (seg == null) {
            diag(maid, "rest", who + " 当前游戏时间无段覆盖（休息时段）——待机/睡觉", level);
            return; // normalize 保证覆盖 0~1440，理论到不了这里
        }
        String segLabel = ScheduleData.fmt(seg.startMin()) + "~" + ScheduleData.fmt(seg.endMin());
        String key = ScheduleData.dayIndex(level) + "|" + seg.startMin();
        if (key.equals(maid.getPersistentData().m_128461_(ScheduleData.APPLIED_TAG))) {
            diag(maid, "debounce", who + " 扫描正常：段 " + segLabel
                    + " 本日已应用（去抖命中，段内不重设）", level);
            return; // 本段已应用过（去抖，避免每秒重建 brain）
        }
        // 上次应用失败的重试节流（见 RETRY_AFTER 注释）
        Long retryAfter = RETRY_AFTER.get(maid.m_20148_());
        if (retryAfter != null && nowTick < retryAfter) {
            diag(maid, "retry-cool", who + " 段应用失败后的 10 秒重试冷却中（" + segLabel + "）", level);
            return;
        }
        // v1.1.0 实测一百三十五【手动改动死磕根治】：排班尝试过本段（记录里是本次段的
        // 键）之后，当前任务 ≠ 尝试那一刻的任务 = 外部把任务改走了（玩家 TLM GUI/
        // 命令/LLM）。不跟它抢——写去抖键"本段按外部选择过了"+ 清重试，下个时段边界
        // 再接管；否则每 10 秒重试会把玩家刚改的任务顶回，或无限重试变成"排班卡死"。
        // 尝试时刻任务没变 = 正常"没活不切"重试循环，不误伤。
        String curNow = (maid.getTask() != null && maid.getTask().getUid() != null)
                ? maid.getTask().getUid().toString() : "null";
        String attempted = ATTEMPTED.get(maid.m_20148_());
        if (attempted != null && attempted.startsWith(key + "|")) {
            String taskAtAttempt = attempted.substring(key.length() + 1);
            if (!taskAtAttempt.equals(curNow)) {
                maid.getPersistentData().m_128359_(ScheduleData.APPLIED_TAG, key);
                RETRY_AFTER.remove(maid.m_20148_());
                ATTEMPTED.remove(maid.m_20148_());
                com.maidsmart.tool.PromaidLog.log("排班", who + " 排班尝试本段后任务被外部改为 "
                        + curNow + "（段任务 " + seg.taskUid() + "）——尊重手动选择，本段不再"
                        + "重试，下个时段边界接管");
                return;
            }
        }
        ATTEMPTED.put(maid.m_20148_(), key + "|" + curNow);
        // —— 真正应用（去抖键在末尾写：失败不标记，下秒重试可见）——
        String fail = null;
        // v1.1.0 实测一百三十三：soft=true 表示"不是失败，是主动暂不切换"（没活/反向
        // 抑制），日志措辞与硬失败分开——但同样不写去抖键、同样限频 10 秒重试
        boolean soft = false;
        try {
            // 工作模式（0=DAY 早班 / 1=NIGHT 晚班 / 2=ALL 全天）
            var modes = com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule.values();
            if (seg.mode() >= 0 && seg.mode() < modes.length) {
                maid.setSchedule(modes[seg.mode()]);
            } else {
                fail = "段模式越界 mode=" + seg.mode();
            }
            // 任务
            if (fail == null && seg.taskUid() != null && !seg.taskUid().isEmpty()) {
                // v1.1.0 实测十六（审查 P1-5）：非法 taskUid 防护——taskUid 来自客户端包
                // （SchedSavePacket/QuickApplyPacket），恶意包/损坏 NBT 的非法串（如 "###"）
                // 会让 parse 抛 ResourceLocationException，且此处在主线程 enqueueWork 里
                // 执行 = 服务端直接崩。1.20.1 SRG 名单里 ResourceLocation 没有 tryParse
                //（那是 1.20.5+ 的方法），等效做法：m_135830_ = isValidResourceLocation
                // 预检（parse 用的同一套校验，静态方法不抛异常）+ try/catch 兜底。
                try {
                    if (!net.minecraft.resources.ResourceLocation.m_135830_(seg.taskUid())) {
                        fail = "段任务 UID 非法 '" + seg.taskUid() + "'（保留模式，跳过任务）";
                    } else {
                        var found = TaskManager.findTask(
                                net.minecraft.resources.ResourceLocation.parse(seg.taskUid()));
                        if (found.isPresent()) {
                            var target = found.get();
                            String fromUid = (maid.getTask() != null && maid.getTask().getUid() != null)
                                    ? maid.getTask().getUid().toString() : "null";
                            String toUid = target.getUid() == null ? "null" : target.getUid().toString();
                            if (fromUid.equals(toUid)) {
                                // 已经在该任务上：无需重设（避免无意义 refreshBrain 重建 AI）
                            } else if (com.maidsmart.config.MaidSmartConfig.MISC_SCHEDULE_AVAILABILITY_CHECK.get()
                                    && !ScheduleTaskAvailability.isAvailable(maid, target)) {
                                // v1.1.0 实测一百三十三 ①：切换前可用性检测——没活不切
                                soft = true;
                                fail = "目标任务 '" + seg.taskUid() + "' 当前无可用工作（没活不切，约 10 秒后重试）";
                            } else if (ScheduleSwitchState.shouldSuppressReverseSwitch(maid.m_20148_(),
                                    fromUid, toUid, nowTick,
                                    com.maidsmart.config.MaidSmartConfig.MISC_SCHEDULE_REVERSE_WINDOW_TICKS.get(),
                                    com.maidsmart.config.MaidSmartConfig.MISC_SCHEDULE_REVERSE_THRESHOLD.get(),
                                    com.maidsmart.config.MaidSmartConfig.MISC_SCHEDULE_REVERSE_COOLDOWN_TICKS.get())) {
                                // v1.1.0 实测一百三十三 ②：反向切换抑制——A→B→A 横跳冷却
                                soft = true;
                                fail = "反向切换抑制：'" + fromUid + "' ↔ '" + toUid + "' 短窗口内反复横跳";
                            } else {
                                // v1.1.0 实测一百三十三 ③：内部 setTask 标记——排班的自动切换
                                // 与其它系统（战斗/蓝图/一键应用/LLM）的 setTask 区分开，防互相覆盖
                                ScheduleSwitchGuard.runInternal(maid.m_20148_(), target.getUid(),
                                        () -> maid.setTask(target));
                                // v1.1.0 实测一百二十九：应用后读回校验——TLM setTask 有守卫
                                // （睡眠/活动/不可换任务状态）时会静默拒绝，读回对比当场暴露
                                String cur = (maid.getTask() != null && maid.getTask().getUid() != null)
                                        ? maid.getTask().getUid().toString() : "null";
                                if (!seg.taskUid().equals(cur)) {
                                    fail = "【任务未生效】目标 '" + seg.taskUid() + "' 应用后任务仍 = "
                                            + cur + "（被 TLM 守卫/睡眠/活动拒绝？）";
                                } else {
                                    ScheduleSwitchState.recordSwitch(maid.m_20148_(), fromUid, toUid,
                                            nowTick, com.maidsmart.config.MaidSmartConfig.MISC_SCHEDULE_REVERSE_WINDOW_TICKS.get());
                                }
                            }
                        } else {
                            fail = "段任务 '" + seg.taskUid() + "' 不存在（模组任务未装/任务被删）";
                        }
                    }
                } catch (Exception e) {
                    fail = "段任务应用异常：" + e;
                }
            }
        } catch (Exception e) {
            fail = "段应用异常：" + e;
        }
        if (fail == null) {
            RETRY_AFTER.remove(maid.m_20148_());
            ATTEMPTED.remove(maid.m_20148_());
            maid.getPersistentData().m_128359_(ScheduleData.APPLIED_TAG, key);
            // v1.1.0 实测九十四：运行日志——段应用落盘（去抖保证每段每天至多一条）
            com.maidsmart.tool.PromaidLog.log("排班", who + " 应用段 " + segLabel
                    + " 模式=" + seg.mode() + " 任务=" + seg.taskUid());
        } else {
            // 未生效：不写去抖键 + 10 秒重试节流——任务非法/不存在/守卫拒绝/没活/反向
            // 抑制各类每段最多记 ~6 条（10 秒限频，防刷屏也防每秒 refreshBrain 重建 AI）；
            // 条件是暂时性的（睡眠解除/地里长出作物/矿被清出空位）最多 10 秒后自动恢复
            RETRY_AFTER.put(maid.m_20148_(), nowTick + 200L);
            com.maidsmart.tool.PromaidLog.log("排班", who + " 段 " + segLabel
                    + (soft ? " 暂不切换：" : " 应用失败：") + fail
                    + "（去抖键未写，10 秒后重试）");
        }
        } catch (Throwable t) {
            // v1.1.0 实测一百三十五：隔离层——任一异常落日志 + 10 秒重试节流，
            // 不让它击穿 ServerTickEvent 每 tick 重演（排班系统瘫痪）
            try {
                RETRY_AFTER.put(maid.m_20148_(), level.m_46467_() + 200L);
                com.maidsmart.tool.PromaidLog.log("排班",
                        com.maidsmart.tool.PromaidLog.nameOf(maid)
                                + " applyNow 异常（已隔离，10 秒后重试）：" + t);
            } catch (Throwable ignored) {
            }
        }
    }

    /** v1.1.0 实测一百三十五：保存排班 = 明确意图——清掉本段去抖键/尝试记录/重试冷却，
     *  让保存后的立即应用真正落一次（修"改当前时段任务保存不生效"的观感） */
    public static void clearAppliedForSave(EntityMaid maid) {
        try {
            maid.getPersistentData().m_128359_(ScheduleData.APPLIED_TAG, "");
            ATTEMPTED.remove(maid.m_20148_());
            RETRY_AFTER.remove(maid.m_20148_());
        } catch (Throwable ignored) {
        }
    }
}
