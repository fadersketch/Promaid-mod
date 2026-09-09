package com.maidsmart.schedule;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * 排班任务兼容分类（v1.1.0 实测一百七十六，移植 TLM-Sincerely AutoWorkCompatService
 * 的精简版——去掉检测器依赖，只保留"这个任务能不能被自动切换"的分类）。
 *
 * 背景：排班是"时间驱动"的自动切换（日程表段写什么就切什么），不像 TLM-Sincerely
 * 那样按环境检测从预设里挑。兼容分类的作用是防【已知会出问题的任务】被自动切过去
 * （用户："其他附属的工作模式也需要做兼容"）——附魔任务/需要玩家交互的任务/纯 UI
 * 任务等 setTask 后不会干活甚至卡 AI，逐个注册进 BLOCKED/UNSUPPORTED 表即可。
 *
 * 分类语义（纯时间口径）：
 * - SUPPORTED：已知可自动切换的正常工作任务（本模组 + TLM 原生工作任务）——放行。
 * - FALLBACK：未知任务——放行（用户明确写进日程表就必须切），仅记录到运行日志。
 * - UNSUPPORTED：已知"切了也不会正常干活"的任务——警告但照常切（时间驱动语义：
 *   用户指定了就得切，日志提醒可考虑换任务）。
 * - BLOCKED：已知绝对不应自动切换的任务（切了会卡 AI/报错）——soft fail，不切。
 *
 * 扩展点：注册表（UID 精确 + 命名空间前缀），后续附属任务按需 addBlocked/addUnsupported
 * 即可，无需改调度器。
 */
public final class ScheduleCompatService {
    public enum Classification {
        SUPPORTED, FALLBACK, UNSUPPORTED, BLOCKED
    }

    /** 精确 UID 表 */
    private static final Map<String, Classification> BY_UID = new HashMap<>();
    /** 命名空间前缀表（粒度：namespace 或 namespace:path 前缀） */
    private static final Map<String, Classification> BY_PREFIX = new HashMap<>();

    private ScheduleCompatService() {
    }

    static {
        // 本模组工作任务：全部 SUPPORTED（挖矿/伐木/烧制/酿造/建造/整理等）
        for (String uid : new String[]{
                "maid_smart:mine", "maid_smart:woodcut", "maid_smart:cook",
                "maid_smart:brew", "maid_smart:build", "maid_smart:sort",
                "maid_smart:harvest", "maid_smart:feed", "maid_smart:repair",
                // v1.1.0 实测三百一十一：宰杀任务（5×5 同种牲畜超阈值才杀）
                "maid_smart:slaughter"}) {
            BY_UID.put(uid, Classification.SUPPORTED);
        }
        // TLM 原生工作任务：SUPPORTED（除 feed_animal——它是 IAttackTask 的 TLM 怪胎，
        // 切过去容易参与战斗，见 combat 侧排除同源）
        for (String uid : new String[]{
                "touhou_little_maid:attack", "touhou_little_maid:ranged_attack",
                "touhou_little_maid:crossbow_attack", "touhou_little_maid:trident_attack",
                "touhou_little_maid:danmaku_attack", "touhou_little_maid:gun_attack",
                "touhou_little_maid:fish", "touhou_little_maid:sugar_cane",
                "touhou_little_maid:melon", "touhou_little_maid:pumpkin",
                "touhou_little_maid:cocoa", "touhou_little_maid:potato",
                "touhou_little_maid:carrot", "touhou_little_maid:wheat",
                "touhou_little_maid:beetroot", "touhou_little_maid:berry",
                "touhou_little_maid:shears", "touhou_little_maid:grindstone",
                "touhou_little_maid:leather", "touhou_little_maid:cartograph",
                "touhou_little_maid:loom", "touhou_little_maid:smelt",
                "touhou_little_maid:torch", "touhou_little_maid:extinguishing",
                "touhou_little_maid:feed_animal", "touhou_little_maid:honey",
                "touhou_little_maid:milk", "touhou_little_maid:clean",
                "touhou_little_maid:follow", "touhou_little_maid:home",
                "touhou_little_maid:patrol"}) {
            BY_UID.put(uid, Classification.SUPPORTED);
        }
        // 休息目标：idle 允许（时间驱动下"休息段"是合法日程内容）
        BY_UID.put("touhou_little_maid:idle", Classification.SUPPORTED);
        // 已知不应自动切换的任务（示例；按需追加——切了会卡 AI/纯交互/不可切换）
        // BY_UID.put("example_mod:interactive_task", Classification.BLOCKED);
        // 命名空间级兜底（未知命名空间 → FALLBACK，见 classify 末尾）
    }

    /** 分类目标任务（null 安全） */
    public static Classification classify(IMaidTask task) {
        if (task == null || task.getUid() == null) {
            return Classification.FALLBACK;
        }
        String uid = task.getUid().toString();
        Classification c = BY_UID.get(uid);
        if (c != null) {
            return c;
        }
        for (Map.Entry<String, Classification> e : BY_PREFIX.entrySet()) {
            if (uid.startsWith(e.getKey())) {
                return e.getValue();
            }
        }
        return Classification.FALLBACK;
    }

    /** 该任务是否允许被排班自动切换（BLOCKED = 不允许；其余允许） */
    public static boolean isAutoSwitchable(IMaidTask task) {
        return classify(task) != Classification.BLOCKED;
    }

    /** 扩展点：注册精确 UID 分类 */
    public static void register(String uid, Classification c) {
        if (uid != null && c != null) {
            BY_UID.put(uid, c);
        }
    }

    /** 扩展点：注册命名空间/前缀分类 */
    public static void registerPrefix(String prefix, Classification c) {
        if (prefix != null && !prefix.isEmpty() && c != null) {
            BY_PREFIX.put(prefix, c);
        }
    }
}
