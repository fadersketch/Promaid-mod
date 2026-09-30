package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * v1.2.0 实测五百四十七【空袭牵引绳】：空袭期间，以女仆为圆心、半径 N 格（默认 100，可配）
 * 的球内找不到**主人** → 立刻把她传送回主人身边（等效排班表的那次人工传送）。
 *
 * 需求原文："空袭期间加个机制，如果以自身为圆心，半径100格范围内没有发现主人。
 * 立即执行一次传送到主人身边（等效拿排班表的传送）。防止女仆飞太高把目标打死后，
 * 自己回不来。"
 *
 * 【为什么非要单独做这一条】现有三条"远距拉回"链路全都主动放行空袭：
 * <ul>
 *   <li>{@code MaidChunkLoadManager.trySameDimPull}（同维度远距，默认 48 格）：一见
 *       {@link MaidFlightKit#isFlightAirborne} 就 return——那一条是实测四百八十七 为
 *       "别打断扑击"加的，本身没错，副作用却是"一轮打完还挂在空中时也没人管"；</li>
 *   <li>{@code followIfCrossDimension}（跨维度跟随）：滑翔中同样不传（猛击落地后自然恢复）；</li>
 *   <li>TLM 原版的 {@code teleportToOwner}：被 {@code MaidTeleportPreserveMixin} 在
 *       滑翔/飞行作战空中时直接拦掉。</li>
 * </ul>
 * 于是"放烟花冲上天 → 打完目标 → 主人早已不在脚下"这条路径上谁都不拉她，她只能一路
 * 滑翔到落地，落点可能在几百格外的山头上——用户报的"自己回不来"正是这个。
 *
 * 【生效口径】只在"她确实在空中"（{@code !onGround}）时生效：
 * <ul>
 *   <li>空中 = 空袭正在进行，这正是本条要管的窗口。地面上的距离问题交给
 *       {@code trySameDimPull}（48 格，且守家/坐姿/骑乘/干活都有豁免）那套更保守的规则，
 *       不在这里抢——否则"主人出门 100 格、空袭女仆守家站着不动"会被无条件拽走；</li>
 *   <li><b>【实测七百三十七】守家（home 模式）时这条绳**整条不生效**</b>：玩家原话「如果给
 *       home 模式的女仆设置空袭模式的话女仆就会一直被牵引绳往玩家那拉」，本条的决定是
 *       「home 模式下空袭牵引绳不再生效」。守家的语义是"把她停在岗位上"——她自己那条守家回圈
 *       （TLM {@code SchedulePos.tick}，每 2 秒一次）才是正解，这条绳再插一脚就是两股力打架。
 *       所以 home 模式直接返回；非 home 的女仆口径不变（飞太远就拉回主人）。
 *       旧版（实测六百九十二）曾把守家的参照点/落点换成工作区圈心，本版随这道闸一起退场;</li>
 *   <li>主人跨维度时不抢：那一路上有 {@code followIfCrossDimension}（本轮攻击结束即传），
 *       在这里立刻抢会重演实测四百八十七"猛击被传送打断"的老问题。</li>
 * </ul>
 *
 * 【距离口径】3D 距离（水平 + 竖直一起算）。所以"飞太高"本身就会触发：她爬到主人上方
 * 100 格以上时，主人仍在她的球外——那正是"飞太高回不来"的字面含义。
 *
 * 【落点】与排班表的人工传送同一条链路：{@code recallFromFlight} →
 * {@code teleportCore(force = true)} / {@code standSpotAt}——强制（参照点边找不到可站立格
 * 就直接落在参照点所在格）+ 无视地块 + 可空中。
 *
 * 【调用点】挂在 {@code MaidToolAutoEquipBehavior.checkExtraStartConditions} 的飞行任务
 * 分支（core 行为，任何 activity、每 tick 一次）。挂那里的另一个好处：本模组给所有有主
 * 活动女仆挂了 2 级强制加载票（= 实体正常 ticking），所以哪怕她离主人几百格、区块早已
 * 出了模拟距离，她的 brain 照样在跑——这条牵引绳才真的"立即"。
 */
public final class MaidFlightRecall {
    /** 传送失败（主人实体不在已加载世界 / 维度异常）后的重试间隔（tick）——防每 tick 硬撞 */
    private static final int RETRY_COOLDOWN = 60;
    /** 成功召回后"再报一条系统消息"的间隔（tick）：正常要再飞够 N 格才会触发第二次，这里只是兜底防刷屏 */
    private static final int MESSAGE_COOLDOWN = 200;

    /** 失败重试冷却（女仆实例 → 可再试的 gameTime）。键用实体、WeakHashMap——女仆卸载/移除后自动回收 */
    private static final Map<EntityMaid, Long> RETRY_READY =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** 系统消息冷却（同上） */
    private static final Map<EntityMaid, Long> MESSAGE_READY =
            Collections.synchronizedMap(new WeakHashMap<>());

    private MaidFlightRecall() {
    }

    /**
     * 每 tick 由 core 行为调用（见类注释）。
     *
     * 任何异常都不许外溢——core 行为抛异常会带走整个 brain tick，所以这里整段包 Throwable，
     * 与 {@code MaidFlightCombatBehavior.notifyNotReady} 同一处理口径。
     */
    public static void tick(EntityMaid maid) {
        try {
            if (maid == null) {
                return;
            }
            int radius = com.maidsmart.config.MaidSmartConfig.COMBAT_FLIGHT_RECALL_DISTANCE.get();
            if (radius <= 0) {
                return; // 0 = 关闭
            }
            if (!MaidFlightKit.isFlightTask(maid)) {
                return; // 只管两种空袭任务
            }
            if (!(maid.m_9236_() instanceof ServerLevel level)) {
                return;
            }
            if (maid.m_213877_() || maid.m_21224_() || maid.m_20159_()) {
                return; // 已移除 / 死亡 / 骑乘中（骑乘 = 被别的东西带着走，不抢）
            }
            if (maid.m_20096_()) {
                return; // 已落地：交给 trySameDimPull 的保守口径（它查 home/坐姿/干活豁免）
            }
            // 【实测七百三十七】守家的女仆这条绳**整条不生效**：玩家原话「如果给 home 模式的女仆
            //  设置空袭模式的话女仆就会一直被牵引绳往玩家那拉」，本条的决定是「home 模式下空袭
            //  牵引绳不再生效」。home 模式的语义是"把她停在岗位上"——她自己那条守家回圈
            //  （TLM {@code SchedulePos.tick}，每 2 秒一次、对空袭任务不被豁免）本来就是"拉她回家"
            //  的正解，这条绳再插一脚只会两股力打架。所以 home 模式直接返回：不传送、不报消息。
            //  非 home 的女仆一个字不变（还是"飞太远就拉回主人"，就是原来的口径）。
            if (maid.isHomeModeEnable()) {
                return;
            }
            LivingEntity owner = maid.m_269323_();
            // 【实测七百三十七：非 home 时才生效】上面那道 home 闸之后，这条绳只剩"非守家"这一档：
            //  参照点与落点都是主人。守家那一档（曾经的 WorkAreaClamp.homeAnchor 分支）已随那道闸
            //  一起退场——守家由她自己的守家回圈负责，这条绳在 home 模式下整条不生效（见上面的注释）。
            if (owner == null || !owner.m_6084_()) {
                return; // 主人不在已加载世界 / 已死——没有可传的目标
            }
            if (owner.m_9236_() != level) {
                return; // 跨维度不抢（本轮攻击结束后由跨维度跟随处理）
            }
            double refX = owner.m_20185_();
            double refY = owner.m_20186_();
            double refZ = owner.m_20189_();
            double dSq = maid.m_20275_(refX, refY, refZ);
            if (dSq <= (double) radius * radius) {
                RETRY_READY.remove(maid); // 回到半径内 → 清失败冷却
                return;
            }
            long now = level.m_46467_();
            Long ready = RETRY_READY.get(maid);
            if (ready != null && now < ready) {
                return;
            }
            boolean ok = com.maidsmart.follow.MaidChunkLoadManager.recallFromFlight(maid, owner);
            if (!ok) {
                RETRY_READY.put(maid, now + RETRY_COOLDOWN);
                return;
            }
            RETRY_READY.remove(maid);
            int blocks = (int) Math.sqrt(dSq);
            String name = com.maidsmart.tool.PromaidLog.nameOf(maid);
            com.maidsmart.tool.PromaidLog.log("空袭牵引绳", name + " 距主人 "
                    + blocks + " 格（> " + radius + " 格），已传送回主人身边");
            // 系统消息只发给"主人是玩家"的那一档（女仆的主人本来就是玩家，这里只是类型上
            // 站得住脚——LivingEntity 没有 displayClientMessage，必须落到 Player 上）
            if (owner instanceof net.minecraft.world.entity.player.Player player) {
                Long msgReady = MESSAGE_READY.get(maid);
                if (msgReady == null || now >= msgReady) {
                    MESSAGE_READY.put(maid, now + MESSAGE_COOLDOWN);
                    player.m_5661_(Component.m_237113_(
                            "§e✦ §f你的女仆 §b" + name + "§f 飞出了 §e" + blocks
                              + " §f格（空袭牵引绳），已先回到你身边。"), false);
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
