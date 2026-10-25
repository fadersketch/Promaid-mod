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

    /** 贴身气泡文本池（49 条）——排班情境：正在干活被主人靠近时的碎碎念 / 抬头打个招呼。
     *  风格：日常干活的自言自语，像随口嘟囔；少数自嘲式毒鸡汤，后半句自己也不太信
     *  （"你不干有的是人干……吗？"）。不出现老板/同事/工资/下班这类职场词，也不写
     *  "班表盯着我"这种生硬机制说明。 */
    private static final String[] TEXTS = {
            "哎呀，主人来啦——我正忙着呢，没有偷懒哦。",
            "嗯……这堆东西搬完，就歇一小会儿。",
            "主人站远一点，别让工具碰到你。",
            "今天的活好多呀……不过慢慢做总会做完的。",
            "呼……有点累了，再坚持一下下。",
            "主人的脚步声，我一听就认得出来～",
            "这块我熟，交给我吧！",
            "等等我，把这一点做完就去找你。",
            "主人是来看我的吗？那我要好好表现一下～",
            "我做事可认真了……虽然偶尔也会走神。",
            "手上都是灰，先不碰你啦，等我洗洗。",
            "嘿嘿，被主人看到努力的样子了。",
            "干完这些就休息……真的，就休息。",
            "主人饿不饿？等我忙完给你做点吃的～",
            "越干越顺手了，夸我夸我！",
            "好想偷懒啊……不过都做到这儿了，算了。",
            "主人也别太累，记得歇一歇。",
            "别担心我，我没事的。",
            "再做完这一小堆，就差不多了……",
            "主人别站在太阳底下，会晒的。",
            "想让主人多陪一会儿……但活还没干完呢。",
            "哼哼，今天的我干劲十足。",
            "被主人看着，总觉得要做得更好一点。",
            "等忙完这一段，就能好好休息啦。",
            "主人小心脚下，这边刚收拾过。",
            "做一半了……应该算做一半了吧？",
            "被主人看着干活，好像没那么累了。",
            "我做得还挺好的……应该吧？",
            "主人慢走～晚点再来看我呀。",
            "你不干有的是人干……吗？算了，还是我自己干吧。",
            "只要一直努力，总会被看到的……大概。",
            "累吗？累。但主人还在等我，那就还能再撑一会儿。",
            "能天天见到主人，这活也不算太亏。",
            "奖励什么的……有主人的摸摸头就够了吧。",
            "今天也要打起精神……嗯，先喝口水。",
            "别的女仆在休息，我还在忙——不过也没什么不好。",
            "干完这一段就能歇了……咦，怎么还有一堆。",
            "加油，你是最棒的……最棒的什么来着？",
            "多干一点总没坏处……应该没坏处吧。",
            "要是再长高一点就好了……先把这堆搬完。",
            "主人，我这么努力，你可得看见呀。",
            "听说坚持一百天会有好事发生，我已经信了三十天了。",
            "把这点做完，就又向前挪了一小步。",
            "偷懒？不偷了……就歇一小会儿。",
            "今天也是被自己感动的一天——怎么这么勤快呢。",
            "大饼画得有点大……不过肚子确实饿了。",
            "努力干活的话，主人会看到我的，对吧？对吧！",
            "这活干完，应该就能歇口气了吧……应该。",
            "主人来啦？我这边马上就好，等我一下～"
    };

    private static final java.util.Random RNG = new java.util.Random();

    public ScheduleBubbleBehavior() {
        super(Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        if (!MaidSmartConfig.MISC_SCHEDULE_BUBBLE_ENABLED.get()) {
            return false;
        }
        // 只对排班中的女仆生效（本功能的存在前提）
        if (!ScheduleData.isOn(maid)) {
            return false;
        }
        // 坐姿/骑乘不聊（出戏）
        if (maid.isMaidInSittingPose() || maid.getVehicle() != null) {
            return false;
        }
        // 实测四百二十九：睡觉中不聊（LivingEntity.isSleeping）
        if (maid.isSleeping()) {
            return false;
        }
        // 战斗中不聊（正在接战）
        try {
            var atk = maid.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET);
            if (atk.isPresent() && atk.get().isAlive()) {
                return false;
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ScheduleBubbleBehavior", ignored);
        }
        // 自保中不聊
        if (((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getBoolean(
                com.maidsmart.combat.SelfPreservationBehavior.PRESERVE_TAG)) {
            return false;
        }
        // 主人在身边（同维度 + 水平距离 < 触发距离）
        if (!(maid.getOwner() instanceof ServerPlayer owner) || owner.level() != level) {
            return false;
        }
        double dx = owner.getX() - maid.getX();
        double dz = owner.getZ() - maid.getZ();
        double dist = MaidSmartConfig.MISC_SCHEDULE_BUBBLE_RADIUS.get();
        if (dx * dx + dz * dz >= dist * dist) {
            return false;
        }
        // 每只女仆 30 秒冷却
        long now = level.getGameTime();
        Long next = NEXT_ALLOWED.get(maid.getUUID());
        if (next != null && now < next) {
            return false;
        }
        return true;
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        // 选一条没在冷却里弹过的话——随机挑；下次冷却结束后再随机（允许重复，30 条池子足够散）
        String text = TEXTS[RNG.nextInt(TEXTS.length)];
        try {
            maid.getChatBubbleManager().addTextChatBubble(text);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ScheduleBubbleBehavior", ignored);
        }
        // 实测四百二十八：排班气泡也接入语音（内置日语语音包命中即播放并压制原生；
        // 未命中则按 SystemTTSManager 原有链路走磁盘包/缓存/TTS 合成）
        try {
            com.maidsmart.voice.SystemTTSManager.speak(maid, text);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ScheduleBubbleBehavior", ignored);
        }
        NEXT_ALLOWED.put(maid.getUUID(), gameTime + COOLDOWN_TICKS);
        com.maidsmart.tool.StateTables.cap("ScheduleBubbleBehavior.NEXT_ALLOWED", NEXT_ALLOWED);
        // 懒清理：条目数失控时全表过期清扫（低频行为，代价可忽略）
        if (NEXT_ALLOWED.size() > 512) {
            NEXT_ALLOWED.values().removeIf(t -> t < gameTime);
        }
        // 单次行为即结束（stop 走 super 即可）
    }
}