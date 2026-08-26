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

    private ScheduleManager() {
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
     *  清宽限立即生效）。 */
    public static void applyNow(EntityMaid maid, ServerLevel level) {
        // v1.1.0 实测九十三：总开关闸必须设在方法最前面——applyNow 有三个调用方
        //（调度器扫描 / 保存包立即应用 / 战斗还原直通），此前只有调度器上游检查了
        // 总开关：全局关闭排班系统后，战斗还原直通仍会强制 home 模式并按旧日程
        // 应用段任务（跟随女仆打完一仗被留在在家模式、不再跟随主人）。统一在此闸住。
        if (!com.maidsmart.config.MaidSmartConfig.MISC_SCHEDULE_ENABLED.get()) {
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
        }
        if (level.m_46467_() < maid.getPersistentData().m_128454_(ScheduleData.GRACE_TAG)) {
            return; // 宽限期内
        }
        // 自保中让位（自保优先——等血量恢复退出自保后排班照常）
        if (maid.getPersistentData().m_128471_(SelfPreservationBehavior.PRESERVE_TAG)) {
            return;
        }
        // 主动战斗中让位（战斗还原原任务后再由排班接管下一段）
        if (AutoCombatSwitch.isAutoCombatActive(maid)) {
            return;
        }
        List<ScheduleData.Segment> segs = ScheduleData.load(maid);
        if (segs.isEmpty()) {
            return;
        }
        ScheduleData.Segment seg = ScheduleData.segmentAt(segs, ScheduleData.currentMinute(level));
        if (seg == null) {
            return; // normalize 保证覆盖 0~1440，理论到不了这里
        }
        String key = ScheduleData.dayIndex(level) + "|" + seg.startMin();
        if (key.equals(maid.getPersistentData().m_128461_(ScheduleData.APPLIED_TAG))) {
            return; // 本段已应用过（去抖，避免每秒重建 brain）
        }
        maid.getPersistentData().m_128359_(ScheduleData.APPLIED_TAG, key);
        // 工作模式（0=DAY 早班 / 1=NIGHT 晚班 / 2=ALL 全天）
        var modes = com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule.values();
        if (seg.mode() >= 0 && seg.mode() < modes.length) {
            maid.setSchedule(modes[seg.mode()]);
        }
        // 任务
        if (seg.taskUid() != null && !seg.taskUid().isEmpty()) {
            // v1.1.0 实测十六（审查 P1-5）：非法 taskUid 防护——taskUid 来自客户端包
            // （SchedSavePacket/QuickApplyPacket），恶意包/损坏 NBT 的非法串（如 "###"）
            // 会让 parse 抛 ResourceLocationException，且此处在主线程 enqueueWork 里
            // 执行 = 服务端直接崩。1.20.1 SRG 名单里 ResourceLocation 没有 tryParse
            //（那是 1.20.5+ 的方法），等效做法：m_135830_ = isValidResourceLocation
            // 预检（parse 用的同一套校验，静态方法不抛异常）+ try/catch 兜底。
            try {
                if (net.minecraft.resources.ResourceLocation.m_135830_(seg.taskUid())) {
                    TaskManager.findTask(net.minecraft.resources.ResourceLocation.parse(seg.taskUid()))
                            .ifPresent(maid::setTask);
                }
            } catch (Exception ignored) {
                // 双保险：isValid 万一漏过，parse 抛异常也不上行（跳过本段任务）
            }
        }
        // v1.1.0 实测九十四：运行日志——段应用落盘（去抖保证每段每天至多一条）
        com.maidsmart.tool.PromaidLog.log("排班",
                com.maidsmart.tool.PromaidLog.nameOf(maid) + " 应用段 "
                        + ScheduleData.fmt(seg.startMin()) + "~" + ScheduleData.fmt(seg.endMin())
                        + " 模式=" + seg.mode() + " 任务=" + seg.taskUid());
    }
}
