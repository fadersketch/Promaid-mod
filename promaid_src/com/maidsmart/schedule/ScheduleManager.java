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

    /** 应用当前时间段的排班（去抖：段没变不重设）。保存/快捷设置后也调用。 */
    public static void applyNow(EntityMaid maid, ServerLevel level) {
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
            TaskManager.findTask(net.minecraft.resources.ResourceLocation.parse(seg.taskUid()))
                    .ifPresent(maid::setTask);
        }
    }
}
