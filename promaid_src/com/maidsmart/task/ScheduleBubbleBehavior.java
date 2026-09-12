package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import com.maidsmart.schedule.ScheduleData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.behavior.Behavior;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 排班贴身气泡（v1.1.0 实测四百一十，情绪价值彩蛋）——core 行为，任何任务都运行。
 *
 * 主人靠近\u00a7e排班中\u00a7r（ScheduleData.isOn）的女仆（水平距离 < scheduleBubbleDist，
 * 默认 3.5 格）时，她随机冒出一条贴身气泡对话——30 条文本池（工作吐槽/撒娇/报备
 * 风格，与排班情境贴合），每只女仆触发冷却 30 秒（按 UUID 记，多女仆互不干扰）。
 *
 * 触发豁免（不打扰）：战斗中（脑内有攻击目标）/ 自保中 / 睡觉中 / 坐着 / 骑乘中 /
 * 主人不同维度——这些状态下她有更要紧的事，硬聊很出戏。
 *
 * 与 ChatBubbleLimitMixin 的 5 秒全局限频相互独立：本行为自带 30 秒 CD，远大于
 * 全局限频，不会出现"被限频吞掉"的观感（限频兜的是全系统刷屏，本 CD 兜的是本功能）。
 *
 * 开关与距离：misc.scheduleBubbleEnabled（默认开）/ misc.scheduleBubbleDist（默认 3.5 格）。
 */
public class ScheduleBubbleBehavior extends Behavior<EntityMaid> {

    /** 每只女仆下次允许气泡的时间（gameTime tick）——跨 start/stop 不残留（懒清理） */
    private static final Map<UUID, Long> NEXT_ALLOWED = new HashMap<>();

    /** 触发冷却（tick）：30 秒 */
    private static final long COOLDOWN_TICKS = 30L * 20L;

    /** 贴身气泡文本池（50 条）——排班情境：正在干活被主人靠近时的碎碎念/撒娇/报备
     *  + 打工人毒鸡汤（实测四百一十一，用户："傻乎乎被半骗着说这些话挺有意思的"）。
     *  口径：全部贴合"被排班表支配但心甘情愿"的女仆身份——提到老板/工资/下班都
     *  落回主人身上（老板=主人、工资=摸摸头/加餐），不出戏。 */
    private static final String[] TEXTS = {
            "哎呀，主人来啦——我正按日程干活呢，没有偷懒哦！",
            "嗯嗯，这个时段轮到我做这个，马上就好～",
            "主人要站远一点吗？别被工具碰到啦。",
            "今日份日程过半了，我很有干劲的！",
            "呼……这段活有点累，不过排班表上写着呢，坚持一下。",
            "主人的脚步声我一听就认得出来～",
            "这里的活我熟，交给我吧！",
            "等等我，把这一小块做完就去找你！",
            "主人来视察工作啦？表现好的话……有奖励吗？",
            "我可是按排班表行事的模范女仆哦。",
            "唔，手上有灰，先不碰你，等下工再抱！",
            "排班表可是我自己排的，含泪也要干完……",
            "嘿嘿，被主人看到努力的样子了。",
            "到点我就换下一个活，绝对不会赖在这一段。",
            "主人饿不饿？等我这段做完给你做点吃的～",
            "这活儿越干越熟练了，夸我夸我！",
            "虽然想偷懒……但是日程在那儿挂着呢。",
            "主人也别太累，我看着日程，你看着自己好不好？",
            "别担心我，我可是专业的。",
            "这段做完，距离下班又近了一格～",
            "主人是来检查进度的吗？绝对保质保量！",
            "要是没有排班表，我现在就想赖在你身边了。",
            "哼哼，今天的我依然勤快得可怕。",
            "工作的时候被看着……总感觉干劲又多了一点。",
            "等这一段结束了，就有休息时间啦。",
            "主人小心脚下，我刚整理过这边的工具。",
            "日程过半，进度无忧～",
            "被主人看着干活，总觉得该做得更好一点呢。",
            "我的效率可是有排班表认证的！",
            "主人慢走～记得晚点来看我干活的样子哦。",
            "上班第……第几个小时来着？算了，不重要，干活！",
            "老板说了，好好干，下辈子给你换个大点的扫帚。",
            "别人下班我上班，别人上班我也上班——排班表就是这么写的。",
            "累吗？累。但是一想到主人还在等我，就有力气了。",
            "这份工作最大的好处，就是能天天见到主人。",
            "工资是什么？有主人的摸摸头就够了……大概。",
            "今天也是元气满满地被排班表支配的一天！",
            "同事（别的女仆）说她也想歇歇——大家都一样呢。",
            "干完这一段就能休息了——咦，怎么还有下一段？",
            "加油！你是最胖的……不是，最棒的女仆！",
            "别人家的女仆在休息，我在干活——但我开心，哼。",
            "要是干活能长个子就好了……",
            "主人，我们的目标是什么？——好好干活，天天向上！",
            "听说干满一百天就给加餐，我已经信了三十天了。",
            "这个时段的活干完，我就离「女仆楷模」又近了一步。",
            "摸鱼是不可能摸鱼的，排班表盯着我呢。",
            "今天也是被自己感动的一天——怎么会这么勤快呢。",
            "老板画的饼有点大，不过没关系，我饭量好。",
            "努力工作的话，主人会看到我的对吧？对吧！"
    };

    private static final java.util.Random RNG = new java.util.Random();

    public ScheduleBubbleBehavior() {
        super(Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Override
    protected boolean m_6114_(ServerLevel level, EntityMaid maid) {
        if (!MaidSmartConfig.MISC_SCHEDULE_BUBBLE_ENABLED.get()) {
            return false;
        }
        // 只对排班中的女仆生效（本功能的存在前提）
        if (!ScheduleData.isOn(maid)) {
            return false;
        }
        // 坐姿/骑乘不聊（出戏）
        if (maid.isMaidInSittingPose() || maid.m_20202_() != null) {
            return false;
        }
        // 实测四百二十九：睡觉中不聊（LivingEntity.isSleeping，SRG m_5803_）
        if (maid.m_5803_()) {
            return false;
        }
        // 战斗中不聊（正在接战）
        try {
            var atk = maid.m_6274_().m_21952_(net.minecraft.world.entity.ai.memory.MemoryModuleType.f_26372_);
            if (atk.isPresent() && atk.get().m_6084_()) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        // 自保中不聊
        if (maid.getPersistentData().m_128471_(
                com.maidsmart.combat.SelfPreservationBehavior.PRESERVE_TAG)) {
            return false;
        }
        // 主人在身边（同维度 + 水平距离 < 触发距离）
        if (!(maid.m_269323_() instanceof ServerPlayer owner) || owner.m_9236_() != level) {
            return false;
        }
        double dx = owner.m_20185_() - maid.m_20185_();
        double dz = owner.m_20189_() - maid.m_20189_();
        double dist = MaidSmartConfig.MISC_SCHEDULE_BUBBLE_RADIUS.get();
        if (dx * dx + dz * dz >= dist * dist) {
            return false;
        }
        // 每只女仆 30 秒冷却
        long now = level.m_46467_();
        Long next = NEXT_ALLOWED.get(maid.m_20148_());
        if (next != null && now < next) {
            return false;
        }
        return true;
    }

    @Override
    protected void m_6735_(ServerLevel level, EntityMaid maid, long gameTime) {
        // 选一条没在冷却里弹过的话——随机挑；下次冷却结束后再随机（允许重复，30 条池子足够散）
        String text = TEXTS[RNG.nextInt(TEXTS.length)];
        try {
            maid.getChatBubbleManager().addTextChatBubble(text);
        } catch (Throwable ignored) {
        }
        // 实测四百二十八：排班气泡也接入语音（内置日语语音包命中即播放并压制原生；
        // 未命中则按 SystemTTSManager 原有链路走磁盘包/缓存/TTS 合成）
        try {
            com.maidsmart.voice.SystemTTSManager.speak(maid, text);
        } catch (Throwable ignored) {
        }
        NEXT_ALLOWED.put(maid.m_20148_(), gameTime + COOLDOWN_TICKS);
        // 懒清理：条目数失控时全表过期清扫（低频行为，代价可忽略）
        if (NEXT_ALLOWED.size() > 512) {
            NEXT_ALLOWED.values().removeIf(t -> t < gameTime);
        }
        // 单次行为即结束（m_6732_ 走 super 即可）
    }
}