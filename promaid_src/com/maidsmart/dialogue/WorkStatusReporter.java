package com.maidsmart.dialogue;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.task.MaidWorkTags;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;
import java.util.WeakHashMap;

/**
 * 工作状态播报（v1.5.6）：
 * 女仆选了 maid_smart 的任务却不干活时，每 10 秒气泡解释原因，
 * 让玩家一眼看出卡在哪（被骑乘/被控制/没镐/附近没矿/没蓝图…）。
 * v1.5.82：坐姿不再是卡住原因（坐着也能干活）——不再提示"站起来"。
 * 每 20 tick 检查一次玩家周围 32 格内的女仆，成本可控。
 */
public class WorkStatusReporter {
    /** v1.5.102：检查间隔 / 播报冷却 / 扫描半径均从配置面板读取（dialogue 段） */

    private static final ResourceLocation MINE = ResourceLocation.parse("maid_smart:mine");
    private static final ResourceLocation COOK = ResourceLocation.parse("maid_smart:cook");
    private static final ResourceLocation BREW = ResourceLocation.parse("maid_smart:brew");
    private static final ResourceLocation BUILD = ResourceLocation.parse("maid_smart:build");
    private static final List<ResourceLocation> SMART_TASKS =
            List.of(MINE, COOK, BREW, BUILD);

    /** 每只女仆上次播报时间（WeakHashMap 防泄漏） */
    private final WeakHashMap<EntityMaid, Long> lastReport = new WeakHashMap<>();
    /** v1.5.252f：每只女仆"脱离工作状态（卡住）"的开始 tick——卡住持续满
     *  20 秒（400 tick）才播报一次，之后每 20 秒最多一次（防刷屏） */
    private final WeakHashMap<EntityMaid, Long> stuckSince = new WeakHashMap<>();
    /** v1.5.190：已检查集合（防同一 tick 同一女仆被多个玩家重复扫描/重复诊断） */
    private final java.util.Set<EntityMaid> checkedThisTick = java.util.Collections.newSetFromMap(new WeakHashMap<>());
    private int tick = 0;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++this.tick < com.maidsmart.config.MaidSmartConfig.DIALOGUE_REPORT_CHECK.get()) {
            return;
        }
        this.tick = 0;
        this.checkedThisTick.clear(); // v1.5.190：每轮重建，天然防泄漏
        for (ServerLevel level : event.getServer().m_129785_()) {
            for (ServerPlayer player : event.getServer().m_6846_().m_11314_()) {
                for (EntityMaid maid : level.m_45976_(EntityMaid.class,
                        player.m_20191_().m_82363_(
                                com.maidsmart.config.MaidSmartConfig.DIALOGUE_REPORT_RADIUS.get(),
                                com.maidsmart.config.MaidSmartConfig.DIALOGUE_REPORT_RADIUS.get(),
                                com.maidsmart.config.MaidSmartConfig.DIALOGUE_REPORT_RADIUS.get()))) {
                    // v1.5.190：同一女仆在一轮里只检查一次——旧版 3 个玩家同区时
                    // 一只女仆被检查 3 遍（熔炉/酿造台诊断扫描跑 3 次 33×9×33）
                    if (this.checkedThisTick.add(maid)) {
                        this.checkMaid(maid);
                    }
                }
            }
        }
    }

    private void checkMaid(EntityMaid maid) {
        // v1.5.103：getTask() 可能为 null（刚召出/数据未就绪），先判空防 NPE 崩服
        if (maid.getTask() == null || !SMART_TASKS.contains(maid.getTask().getUid())) {
            return;
        }
        // v1.5.88：工作播报开关（配置面板 dialogue.statusReporter）
        if (!com.maidsmart.config.MaidSmartConfig.DIALOGUE_STATUS_REPORTER.get()) {
            return;
        }
        long now = maid.m_9236_().m_46467_();
        // v1.5.192：卡住原因抽取为公共静态 reasonOf（手册链路调试面板复用，不触发气泡）
        String reason = reasonOf(maid);
        if (reason == null) {
            // 恢复正常（在干活/寻路中/非卡住）→ 清除卡住计时
            this.stuckSince.remove(maid);
            return;
        }
        // v1.5.252f【播报降频】：脱离工作状态（卡住）持续满 N 秒才播报一次；之后
        // 每 N 秒最多一次——N = 配置面板 dialogue.reportInterval（秒），旧版硬编码
        // 20 秒（400 tick）导致"熔炉明明就在附近"刷屏；v1.0.4 改为读配置（秒×20=tick），
        // 玩家可在面板"对话提示→播报间隔（秒）"调整。烹饪/酿造/挖矿同逻辑
        long reportTicks = (long) (com.maidsmart.config.MaidSmartConfig.DIALOGUE_REPORT_INTERVAL.get() * 20L);
        Long since = this.stuckSince.get(maid);
        if (since == null) {
            this.stuckSince.put(maid, now); // 开始计时（静默等待 N 秒）
            return;
        }
        if (now - since < reportTicks) {
            return; // 卡住不足 N 秒，不打扰
        }
        Long last = this.lastReport.get(maid);
        if (last != null && now - last < reportTicks) {
            return; // 距上次播报不足 N 秒（持续卡住时每 N 秒最多一句）
        }
        maid.getChatBubbleManager().addTextChatBubble(reason);
        this.lastReport.put(maid, now);
    }

    /**
     * v1.5.192：公共卡住原因（Promaid 手册·链路调试面板显示"工作链路"用）。
     * 返回 null = 正常（不卡/非 maid_smart 任务/播报关闭）；否则返回中文原因。
     */
    public static String reasonOf(EntityMaid maid) {
        if (maid.getTask() == null || !SMART_TASKS.contains(maid.getTask().getUid())) {
            return null;
        }
        // v1.5.82：坐姿不再是"卡住"原因——女仆可以坐着干活（任务照常执行）
        if (maid.m_20159_()) {
            return "被骑乘着，动不了";
        }
        // v1.1.0 实测一百七十四【站桩干活误报修复】：烹饪/酿造女仆坐姿 + 站桩标记 =
        // 正贴着炉子/酿造台干活（收成品/补料/补燃料中）——导航空闲是站桩工作的
        // 正常状态，不是卡住。日志实证：K螺诺亚一边正常取成品/喂牛肉，一边每 10 秒
        // 报"炉子明明就在附近但我没能开始烧制"。坐姿+WORK_STILL 是烹饪/酿造贴方块
        // 干活的特征（挖矿/伐木/建造站桩不坐下），命中即视为正常不播报；
        // 站着等炉子的（furnacePos 为空时行为 standUp + setStill）仍会如实播报。
        if (maid.isMaidInSittingPose()
                && maid.getPersistentData().m_128471_(MaidWorkTags.WORK_STILL_TAG)) {
            return null;
        }
        if (!maid.m_21573_().m_26571_()) {
            return null; // 寻路不空闲 = 正在干活
        }
        return idleReason(maid);
    }

    private static String idleReason(EntityMaid maid) {
        ResourceLocation uid = maid.getTask().getUid();
        if (uid.equals(MINE)) {
            ItemStack hand = maid.m_21205_();
            if (hand.m_41619_() || !(hand.m_41720_() instanceof PickaxeItem)) {
                return "我没有镐，没法挖矿——给我一把镐吧";
            }
            return "附近没有值得挖的矿石";
        }
        if (uid.equals(BUILD)) {
            // v1.5.67：建造任务且无计划 → 静默站桩等待（不再每 10 秒弹"没有建造
            // 需求"——已加入建造的女仆只安静等蓝图，由气泡限频兜底整体音量）
            return null;
        }
        if (uid.equals(COOK)) {
            // v1.1.0 实测二百八十三：删除"炉子明明就在附近（N个）但我没能开始烧制"
            // 诊断播报——烹饪/酿造早已改为站桩模式，女仆贴着炉子等待（烧制中/
            // 缺料/背包满）是正常工作状态，这句老版本诊断就是纯误报（用户实证
            // 熔炉与酿造双双弹这句话）。空闲+附近有炉子 = 站桩等待，不播报
            return null;
        }
        if (uid.equals(BREW)) {
            return null; // 同上：站桩等待是正常状态，不播报
        }
        return null;
    }
}
