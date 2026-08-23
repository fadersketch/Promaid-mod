package com.maidsmart.schedule;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * 排班数据（v1.1.0）——每女仆一张 00:00～24:00 的日程表（游戏内时间，一天 20 分钟）。
 *
 * 结构：分段列表，每段 {startMin, endMin, 工作模式, 任务UID}（分钟 0~1440；
 * 00:00 = 游戏 dayTime 0 = 黎明）。时间换算：dayTime 24000 tick = 1440 分钟，
 * 1 分钟 = 50/3 tick；分钟 = dayTime × 3 ÷ 50。
 *
 * 工作模式 = TLM MaidSchedule：0=DAY(早班)，1=NIGHT(晚班)，2=ALL(全天)。
 *
 * 持久化：女仆 persistentData（ListTag "maid_smart_schedule" + 开关 + 去抖键），
 * 随实体存档——魂符收放/跨存档不丢。
 */
public final class ScheduleData {
    public static final String TAG = "maid_smart_schedule";
    public static final String ON_TAG = "maid_smart_schedule_on";
    /** 去抖：当前已应用段的标识（"dayIndex|startMin"），段没变不重设任务 */
    public static final String APPLIED_TAG = "maid_smart_schedule_applied";

    /** 一段的排班：[startMin, endMin) 分钟 + 工作模式 + 要切的任务 */
    public record Segment(int startMin, int endMin, int mode, String taskUid) {
    }

    private ScheduleData() {
    }

    /* ---------------- 持久化 ---------------- */

    public static void save(EntityMaid maid, List<Segment> segs, boolean on) {
        ListTag list = new ListTag();
        for (Segment s : segs) {
            CompoundTag t = new CompoundTag();
            t.m_128405_("s", s.startMin()); // putInt
            t.m_128405_("e", s.endMin());
            t.m_128405_("m", s.mode());
            t.m_128359_("t", s.taskUid() == null ? "" : s.taskUid());
            list.add(t);
        }
        maid.getPersistentData().m_128365_(TAG, list); // put(String, Tag)
        maid.getPersistentData().m_128379_(ON_TAG, on);
    }

    public static List<Segment> load(EntityMaid maid) {
        List<Segment> out = new ArrayList<>();
        Tag raw = maid.getPersistentData().m_128423_(TAG); // get(String)
        if (!(raw instanceof ListTag list)) {
            return out;
        }
        for (Tag t : list) {
            if (!(t instanceof CompoundTag ct)) {
                continue;
            }
            out.add(new Segment(ct.m_128451_("s"), ct.m_128451_("e"),
                    ct.m_128451_("m"), ct.m_128461_("t")));
        }
        return out;
    }

    public static boolean isOn(EntityMaid maid) {
        // v1.1.0 实测十六（审查 P3-11）：getBoolean 缺键本就返回 false，
        // contains 检查冗余——单 getBoolean 即可
        return maid.getPersistentData().m_128471_(ON_TAG);
    }

    public static void setOn(EntityMaid maid, boolean on) {
        maid.getPersistentData().m_128379_(ON_TAG, on);
    }

    /* ---------------- 归一化与查询 ---------------- */

    /**
     * 归一化：按 start 排序 → 去缝隙（前一段 end 不足下一段 start 时延伸补齐）→
     * 首段不足 0 从 0 起 → 末段延伸到 1440（凑满 24:00）。保存时调用——玩家
     * 只管填行，缝隙和收尾自动补。
     */
    public static List<Segment> normalize(List<Segment> raw) {
        List<Segment> sorted = new ArrayList<>(raw);
        sorted.sort(java.util.Comparator.comparingInt(Segment::startMin));
        List<Segment> out = new ArrayList<>();
        int cursor = 0;
        for (Segment s : sorted) {
            int start = Math.max(0, Math.min(1440, s.startMin()));
            int end = Math.max(start, Math.min(1440, s.endMin()));
            if (end <= start) {
                continue; // 空段丢弃
            }
            if (start > cursor) {
                start = cursor; // 缝隙归入本段（从前一段结束处开始）
            }
            out.add(new Segment(start, end, s.mode(), s.taskUid()));
            cursor = end;
        }
        if (!out.isEmpty() && cursor < 1440) {
            // 末段延伸到 24:00（用户格式："没凑到 24:00 就继续延伸直到凑满"）
            Segment last = out.remove(out.size() - 1);
            out.add(new Segment(last.startMin(), 1440, last.mode(), last.taskUid()));
        }
        return out;
    }

    /** 当前分钟所在的段（无排班/未覆盖返回 null） */
    public static Segment segmentAt(List<Segment> segs, int minute) {
        for (Segment s : segs) {
            if (minute >= s.startMin() && minute < s.endMin()) {
                return s;
            }
        }
        return null;
    }

    /** 游戏内时间 → 分钟（0~1439；dayTime 0 = 00:00 = 黎明） */
    public static int currentMinute(ServerLevel level) {
        long dayTime = level.m_46468_(); // getDayTime（含天数×24000）
        return (int) ((dayTime % 24000L) * 3L / 50L);
    }

    /** 游戏内天数（去抖键用——同一天同一段只应用一次） */
    public static long dayIndex(ServerLevel level) {
        return level.m_46468_() / 24000L;
    }

    /** 分钟 → "H:MM" 显示 */
    public static String fmt(int minute) {
        minute = Math.max(0, Math.min(1440, minute));
        return (minute / 60) + ":" + String.format("%02d", minute % 60);
    }

    /** "H:MM"/"H"/"H：MM" 宽松解析（容忍全角冒号/波浪线/空格；非法返回 -1） */
    public static int parseTime(String text) {
        String t = text.trim().replace("：", ":");
        try {
            int idx = t.indexOf(':');
            if (idx < 0) {
                return Math.max(0, Math.min(1440, Integer.parseInt(t) * 60));
            }
            int h = Integer.parseInt(t.substring(0, idx).trim());
            int m = Integer.parseInt(t.substring(idx + 1).trim());
            return Math.max(0, Math.min(1440, h * 60 + m));
        } catch (Exception e) {
            return -1;
        }
    }

    /* ================= 班次 × 6 任务槽（v1.1.0 实测五十一） ================= */

    /**
     * v1.1.0 实测五十一：排班 UI 重做——玩家只选班次 + 6 个任务按钮，不再手填时间。
     * 班次工作窗口（分钟）：早班 = 白天（0~720，黎明~黄昏），晚班 = 夜晚
     * （720~1440，黄昏~黎明），全天 = 0~1440。mod 分钟约定 0:00 = dayTime 0 =
     * 黎明，所以 0~720 恰是白昼、720~1440 恰是黑夜，与 TLM DAY/NIGHT 作息对齐；
     * 【休息时间不排段】——segmentAt 返回 null → 调度器不切任务，由 TLM 作息
     * 让她睡觉（早班夜休 / 晚班昼休）。
     */
    public static int[] shiftWindow(int shift) {
        if (shift == 0) {
            return new int[]{0, 720};
        }
        if (shift == 1) {
            return new int[]{720, 1440};
        }
        return new int[]{0, 1440};
    }

    /**
     * 班次 + 6 任务槽 → 段列表（相邻同任务自动合并——3+3 两档存成 2 段，避免
     * 同任务边界重复触发 setTask 重建 brain）。只覆盖工作窗口，【不经 normalize】
     * （normalize 会把末段延伸补满 24:00，把休息时间也吃掉）。
     */
    public static List<Segment> segmentsFromSlots(int shift, List<String> slots) {
        int[] win = shiftWindow(shift);
        int len = (win[1] - win[0]) / 6;
        List<Segment> out = new ArrayList<>();
        for (int i = 0; i < 6 && i < slots.size(); i++) {
            String uid = slots.get(i) == null ? "" : slots.get(i);
            int start = win[0] + len * i;
            int end = win[0] + len * (i + 1);
            if (!out.isEmpty()) {
                Segment prev = out.get(out.size() - 1);
                if (prev.taskUid().equals(uid) && prev.endMin() == start) {
                    out.set(out.size() - 1, new Segment(prev.startMin(), end, prev.mode(), prev.taskUid()));
                    continue;
                }
            }
            out.add(new Segment(start, end, shift, uid));
        }
        return out;
    }

    /** 已存段 → 班次推断（首段 mode；空表 = 2 全天） */
    public static int inferShift(List<Segment> segs) {
        if (segs == null || segs.isEmpty()) {
            return 2;
        }
        return Math.max(0, Math.min(2, segs.get(0).mode()));
    }

    /** 已存段 → 6 槽任务（各槽中点所在段的任务；没覆盖的槽用默认任务填） */
    public static String[] slotTasks(List<Segment> segs, int shift, String defaultTask) {
        int[] win = shiftWindow(shift);
        int len = (win[1] - win[0]) / 6;
        String[] out = new String[6];
        for (int i = 0; i < 6; i++) {
            int mid = win[0] + len * i + len / 2;
            Segment s = segs == null ? null : segmentAt(segs, mid);
            out[i] = s != null && s.taskUid() != null ? s.taskUid()
                    : (defaultTask == null ? "" : defaultTask);
        }
        return out;
    }
}
