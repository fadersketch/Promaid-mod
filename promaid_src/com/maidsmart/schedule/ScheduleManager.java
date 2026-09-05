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

    /** v1.1.0 实测一百七十六（移植 TLM-Sincerely FORCE_BRAIN_REFRESH_ON_STUCK）：切段成功
     *  后登记的大脑自愈待检（maidId → 检查 tick + 段任务 UID）。一次切换最多治一次。 */
    private static final java.util.Map<java.util.UUID, PendingRefresh> PENDING_REFRESH =
            new java.util.HashMap<>();

    private record PendingRefresh(long checkAtTick, String taskUid) {
    }

    /** v1.1.0 实测一百七十六：切段后大脑自愈——段任务已应用但脑内无任何工作记忆
     *  （走位/攻击目标），强制 refreshBrain 一次重建 AI（TLM 偶尔脑活动没接上，女仆
     *  站着不动）。保守守卫：任务已被外部/战斗换走不治；idle/战斗任务不治；坐姿
     *  （烹饪/酿造贴方块站桩，无走位记忆是常态）不治；每女仆一次切换最多触发一次。 */
    private static void checkBrainRefresh(EntityMaid maid, ServerLevel level, long nowTick) {
        try {
            PendingRefresh p = PENDING_REFRESH.get(maid.m_20148_());
            if (p == null || nowTick < p.checkAtTick) {
                return;
            }
            PENDING_REFRESH.remove(maid.m_20148_());
            var task = maid.getTask();
            if (task == null || task.getUid() == null
                    || !p.taskUid.equals(task.getUid().toString())) {
                return; // 任务已变（外部/战斗/玩家）——尊重，不治
            }
            if (task instanceof com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask
                    || "touhou_little_maid:idle".equals(task.getUid().toString())) {
                return; // 战斗任务有自己的体系；idle 不算工作
            }
            if (maid.isMaidInSittingPose()) {
                return; // 坐姿 = 站桩工作正常（烹饪/酿造贴方块），无走位记忆是常态
            }
            var walk = maid.m_6274_().m_21952_(
                    net.minecraft.world.entity.ai.memory.MemoryModuleType.f_26370_);
            var attack = maid.m_6274_().m_21952_(
                    net.minecraft.world.entity.ai.memory.MemoryModuleType.f_26372_);
            if (walk.isPresent() || attack.isPresent()) {
                return; // 脑内已有工作记忆——正常工作中
            }
            maid.refreshBrain(level);
            com.maidsmart.tool.PromaidLog.log("排班", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 切段后大脑无工作记忆（任务=" + p.taskUid + "）——refreshBrain 自愈");
        } catch (Throwable ignored) {
        }
    }

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
        // v1.1.0 实测一百七十六【排班扫描 AABB 崩溃修复】：旧版用无限 AABB 想扫全维度
        // 女仆——getEntitiesOfClass 内部经 SectionPos.blockToSection 换算后 ±∞ 都溢出
        // 收敛到同一列（134217727，与 AutoCombatSwitch 实测一百七十三同源 bug），查询
        // 永远返回空列表 → 排班扫描从未扫到过任何女仆，"任务不随时间段切换"的根因之一
        //（日志实证此前的"应用段"全部来自保存/重入/战斗还原路径）。改用覆盖整个可玩
        // 范围的有限 AABB（x/z ±131072 = ±128km，y ±4096）：blockToSection 对有限值
        // 正常换算，循环覆盖所有已加载区块。
        net.minecraft.world.phys.AABB whole = new net.minecraft.world.phys.AABB(
                -131072.0, -4096.0, -131072.0, 131072.0, 4096.0, 131072.0);
        for (ServerLevel level : event.getServer().m_129785_()) {
            // v1.1.0 实测三百三十：EntityMaid.class 全图扫描改用 Entity.class 全量 +
            // instanceof 过滤——ClassInstanceMultiMap 桶 bug（同 FarmTillDriver）：
            // 未预建 EntityMaid 桶的 section 被整段跳过，排班扫描扫不到该 section
            // 里的女仆 → 任务不随时间段切换
            for (net.minecraft.world.entity.Entity e : level.m_45976_(
                    net.minecraft.world.entity.Entity.class, whole)) {
                if (!(e instanceof EntityMaid maid) || !maid.m_6084_()
                        || !ScheduleData.isOn(maid)) {
                    continue;
                }
                applyNow(maid, level);
                // v1.1.0 实测一百七十六：切段后大脑自愈（顺路检查待检女仆）
                checkBrainRefresh(maid, level, level.m_46467_());
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
        // 主动战斗中让位（战斗还原原任务后再由排班接管下一段）——v1.1.0 实测一百六十三：
        // 只有【真实在战斗任务】才算战斗中；COMBAT_ACTIVE 残留 true 但任务已不是攻击
        // 任务（老版本残留标记）→ 不拦截，排班照常应用（否则排班被残留标记挡死 =
        // "排班不切换、女仆一直跟随主人"，8月28日起日志实证排班永不应用段）
        if (AutoCombatSwitch.isReallyCombatActive(maid)) {
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
            // v1.1.0 实测二百六十九（用户："明明排班里面有了对应的日程，但是女仆工作的
            // 状态仍然不符合日程安排"）：旧版休息时段直接 return——【模式也不切】，晚班
            // 女仆（白天休息）保持排班前的模式（如图：早班+空闲），"排班开了状态却与
            // 日程不符"。休息时段不再什么都不做：把作息切到班次模式（早班=DAY/晚班=
            // NIGHT/全天=ALL），TLM 作息系统接管睡觉/待机；任务留给工作窗口内的段应用。
            // 女仆模式 = 班次模式 = 排班规定的状态，GUI 同步后与日程一致。
            int shift = ScheduleData.inferShift(segs);
            try {
                var modes = com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule.values();
                if (shift >= 0 && shift < modes.length) {
                    var cur = maid.getSchedule();
                    if (cur == null || cur != modes[shift]) {
                        ScheduleSwitchGuard.runInternal(maid.m_20148_(), null,
                                () -> maid.setSchedule(modes[shift]));
                        // GUI 立即同步（排班规定的模式已生效）
                        try {
                            if (maid.m_269323_() instanceof net.minecraft.server.level.ServerPlayer sp) {
                                ScheduleNetworking.sendMaidStateSync(sp, maid);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            diag(maid, "rest", who + " 当前游戏时间无段覆盖（休息时段）——作息已切到班次模式，待机/睡觉", level);
            return;
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
        // v1.1.0 实测一百七十六：切换动作委托给 ScheduleSwitchEngine（单一切换出口，
        // 镜像 TLM-Sincerely TaskSwitchDecisionEngine——可用性/最短持有/反向抑制/兼容
        // 门/读回校验都在引擎内；ScheduleManager 只做门外调度与结果处理）
        ScheduleSwitchEngine.Result result = ScheduleSwitchEngine.applySegment(maid, level, seg, nowTick);
        String fail = result.success ? null : result.message;
        // v1.1.0 实测一百三十三：soft=true 表示"不是失败，是主动暂不切换"（没活/反向
        // 抑制/最短持有/兼容门），日志措辞与硬失败分开——但同样不写去抖键、同样限频
        // 10 秒重试
        boolean soft = result.soft;
        if (result.warning != null) {
            com.maidsmart.tool.PromaidLog.log("排班", who + " " + result.warning);
        }
        if (fail == null) {
            RETRY_AFTER.remove(maid.m_20148_());
            ATTEMPTED.remove(maid.m_20148_());
            maid.getPersistentData().m_128359_(ScheduleData.APPLIED_TAG, key);
            // v1.1.0 实测九十四：运行日志——段应用落盘（去抖保证每段每天至多一条）
            com.maidsmart.tool.PromaidLog.log("排班", who + " 应用段 " + segLabel
                    + " 模式=" + seg.mode() + " 任务=" + seg.taskUid());
            // v1.1.0 实测二百六十八（用户："排班生效之后，快捷设置页的 GUI 应该也统一
            // 立刻更改为排班所规定的状态，然后再锁定"）：段应用成功 → 把最新真实状态
            // 推给打开着排班书的主人——快捷设置页立即显示排班规定的模式/任务并锁定，
            // 不再停留在打开排班书那一刻的旧状态（旧版 GUI 数据来自打开包，排班生效后
            // 永不刷新，观感"排班没生效"）。
            try {
                if (maid.m_269323_() instanceof net.minecraft.server.level.ServerPlayer sp) {
                    ScheduleNetworking.sendMaidStateSync(sp, maid);
                }
            } catch (Throwable ignored) {
            }
            // v1.1.0 实测一百七十六（移植 TLM-Sincerely FORCE_BRAIN_REFRESH_ON_STUCK）：
            // 切段成功 → 登记大脑自愈待检（60 tick 后若无工作记忆则 refreshBrain 一次）
            if (com.maidsmart.config.MaidSmartConfig.MISC_SCHEDULE_FORCE_BRAIN_REFRESH.get()
                    && seg.taskUid() != null && !seg.taskUid().isEmpty()) {
                if (PENDING_REFRESH.size() > 4096) {
                    PENDING_REFRESH.clear();
                }
                PENDING_REFRESH.put(maid.m_20148_(),
                        new PendingRefresh(nowTick + 60L, seg.taskUid()));
            }
        } else {
            // 未生效：不写去抖键 + 10 秒重试节流——任务非法/不存在/守卫拒绝/没活/反向
            // 抑制各类每段最多记 ~6 条（10 秒限频，防刷屏也防每秒 refreshBrain 重建 AI）；
            // 条件是暂时性的（睡眠解除/地里长出作物/矿被清出空位）最多 10 秒后自动恢复
            RETRY_AFTER.put(maid.m_20148_(), nowTick + 200L);
            // v1.1.0 实测一百八十六：失败/暂不切换日志 5 分钟限频——重试本体验照旧每
            // 10 秒进行，但"可用性检测常开 + 段任务长期不可用"时旧版每 10 秒落一条，
            // 绑定炉子的女仆日志被刷到"非常吵"
            long logNow = level.m_46467_();
            Long lastFailLog = APPLY_FAIL_LOG_SINCE.get(maid.m_20148_());
            if (lastFailLog == null || logNow - lastFailLog >= 6000L) {
                APPLY_FAIL_LOG_SINCE.put(maid.m_20148_(), logNow);
                com.maidsmart.tool.PromaidLog.log("排班", who + " 段 " + segLabel
                        + (soft ? " 暂不切换：" : " 应用失败：") + fail
                        + "（去抖键未写，10 秒后重试）");
            }
        }
        } catch (Throwable t) {
            // v1.1.0 实测一百三十五：隔离层——任一异常落日志 + 10 秒重试节流，
            // 不让它击穿 ServerTickEvent 每 tick 重演（排班系统瘫痪）
            try {
                RETRY_AFTER.put(maid.m_20148_(), level.m_46467_() + 200L);
                // 实测一百八十六：异常日志同样 5 分钟限频（持续异常时每 10 秒刷一条很吵）
                long logNowE = level.m_46467_();
                Long lastFailLogE = APPLY_FAIL_LOG_SINCE.get(maid.m_20148_());
                if (lastFailLogE == null || logNowE - lastFailLogE >= 6000L) {
                    APPLY_FAIL_LOG_SINCE.put(maid.m_20148_(), logNowE);
                    com.maidsmart.tool.PromaidLog.log("排班",
                            com.maidsmart.tool.PromaidLog.nameOf(maid)
                                    + " applyNow 异常（已隔离，10 秒后重试）：" + t);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 排班失败/暂不切换日志限频（vtick，6000=5 分钟/女仆——重试照旧，日志不刷屏）
     *  v1.1.0 实测一百八十六 */
    private static final java.util.Map<java.util.UUID, Long> APPLY_FAIL_LOG_SINCE = new java.util.HashMap<>();

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

    /** v1.1.0 实测一百四十四：实体重新入世界（魂符收放/区块重载/跨维度）时清【持久化的
     *  本段去抖键】。魂符收进再放出：persistentData（含 APPLIED_TAG）随魂符保存 → 去抖键
     *  与本段一致 → 调度器"本段已应用"跳过 = 排班不重放（任务/模式保持放出时的原样）。
     *  只清 APPLIED_TAG：ATTEMPTED（尊重手动选择）/RETRY_AFTER（重试冷却）是内存态，
     *  保留使区块重载场景下"任务被外部改过"的保护继续生效，不会误伤手动选择。 */
    public static void clearAppliedForJoin(EntityMaid maid) {
        try {
            maid.getPersistentData().m_128359_(ScheduleData.APPLIED_TAG, "");
        } catch (Throwable ignored) {
        }
    }
}
