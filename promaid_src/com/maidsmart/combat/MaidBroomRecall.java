package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityBroom;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * v1.3.6 实测六百六十一【扫帚牵引绳】：扫帚模式期间，以她为圆心、半径 N 格（默认 100，
 * 配置 {@code combat.broom.recallDistance}，0 = 关闭）的球内找不到**参照点** → 立刻**连人带扫帚**
 * 传送回参照点：非守家时参照点 = 主人；**守家时参照点与落点都是她的工作区圈心**（实测六百九十二）。
 *
 * <p>玩家原话：「这个东西应该也要有一个飞行牵引绳。如果隔的太远的话会连人带扫帚一起传送送回来。
 * 但是如果在代码上碰到了什么硬骨头。我们在触发此传送的时候，就先将女仆从扫帚模式切换到空闲模式，
 * 然后将扫帚和女仆传送过来，然后再切换成扫帚模式。」
 *
 * <h2>为什么必须另立一条（不能直接复用「空袭牵引绳」）</h2>
 * {@link MaidFlightRecall} 的第一道门就是 {@link MaidFlightKit#isFlightTask}（只管两种**鞘翅**
 * 空袭任务），而它自己后面还紧跟着一句 {@code if (maid.m_20159_()) return;}——「骑乘中 = 被别的
 * 东西带着走，不抢」。扫帚模式的女仆**永远**是乘客（她骑的就是那把扫帚），所以即便把任务判据
 * 放宽，她也会被那道"骑乘豁免"顶回来。空袭那份代码一个字都不该改（它管的是另一件事），
 * 所以这里另开一条，判据与结构照抄，改的只有两处：**任务判据**与**"骑乘"的含义**
 * （骑扫帚不是"被带走的豁免"，而是"必须连扫帚一起搬"的理由）。
 *
 * <h2>「连人带扫帚一起传送」是怎么做到的（那块硬骨头）</h2>
 * 关键事实：{@code Entity.teleportTo(ServerLevel, …)} 内部会先 {@code unRide()} —— 直接传送她
 * = 她当场被从扫帚上踹下来，落地后成了一把漂在原地的孤儿扫帚 + 一个站在地上的女仆。所以
 * 传送顺序必须是「先解开乘客关系 → 搬扫帚 → 搬她 → 重新落座」，四步都在**同一个 tick** 里
 * 做完（见 {@code MaidChunkLoadManager.recallBroomRider}）。玩家提到的"先切空闲再切回来"
 * 那条路**没有被用到**：它会把一次瞬时搬运变成两次任务切换（任务一换，{@code MaidBroomTask}
 * 的 brain 会重建、她的扫帚会被收进背包），只在"连人带扫帚搬不过去"时才退化成
 * "就地收工 → 传送她 → 下一 tick 她自己重新骑上"。所以那条兜底路径仍然存在，只是排在后面。
 *
 * <h2>生效口径</h2>
 * <ul>
 *   <li><b>只在骑着扫帚时生效</b>（{@code ridingBroom != null}）：她已经落地、或正走在去捡扫帚的
 *       路上时，距离问题交给 {@code MaidChunkLoadManager.trySameDimPull} 那套更保守的规则
 *       （它查 home / 坐姿 / 干活豁免）。这也正是「空袭牵引绳」那句 {@code onGround} 的同一用意；</li>
 *   <li><b>距离按 3D 算</b>（水平 + 竖直）：所以「飞太高」本身就触发——那是"回不来"的字面含义；</li>
 *   <li><b>主人跨维度时不抢</b>：那种情况由 {@code followIfCrossDimension} 那一路负责，
 *       在这里立刻抢会与"另一条链路正在处理"打架。要跨维度叫她回来，用排班表的
 *       「召她过来」/「一键集合」——那条路已经认识扫帚了（见 {@code recallBroomRider}）；</li>
 *   <li><b>【实测六百九十二】守家时拉回的是「家」，不是主人</b>：玩家原话「Home模式下，空袭牵引绳
 *       还在发力。女仆离了主人100格之后，还是会被传送回来。」——她正沿工作范围盘旋（home=true），
 *       主人跑出 100 格就被连人带扫帚搬到主人那边，随后几分钟回不到圈里（实测日志 01:32:22 那一串
 *       「被方块顶住 → 飘到最近的空气格」）。守家的语义是"待在家里"，所以守家期间**距离基准与落点
 *       都换成工作区圈心**（{@code com.maidsmart.follow.WorkAreaClamp.homeAnchor}）：主人走多远
 *       都不再是把守家女仆拽走的理由，而"飞太远回不来"照旧由这条绳兜住——拉回家。
 *       非守家（跟随）时仍按主人算，口径一字未改。想彻底关掉就 {@code combat.broom.recallDistance}=0。</li>
 * </ul>
 *
 * <h2>调用点</h2>
 * {@link MaidBroomBehavior#m_6725_} 的**第一行**（⓪ 相位，每 tick 一次，且是 ①..⑥ 全部之前）
 * ——扫帚模式的行为每 tick 都在跑（她骑着扫帚时 TLM 的 brain 照常 tick，只有 guiOpening 会挡），
 * 所以这条牵引绳是"立即"的，不用另挂 core 行为。
 */
public final class MaidBroomRecall {

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

    private MaidBroomRecall() {
    }

    /**
     * 每 tick 由 {@link MaidBroomBehavior} 调用一次（见类注释的调用点）。
     *
     * <p>任何异常都不许外溢——行为里抛异常会带走整个 brain tick，所以这里整段包 Throwable，
     * 与 {@link MaidFlightRecall#tick} 同一处理口径。
     *
     * @return true = 这一 tick 刚把她（连扫帚）拉回来 → 调用方就此打住，别再写推进意图
     */
    public static boolean tick(EntityMaid maid) {
        try {
            if (maid == null) {
                return false;
            }
            int radius = com.maidsmart.config.MaidSmartConfig.COMBAT_BROOM_RECALL_DISTANCE.get();
            if (radius <= 0) {
                return false; // 0 = 关闭
            }
            if (!MaidBroomKit.isBroomTask(maid)) {
                return false; // 只管扫帚模式
            }
            if (!(maid.m_9236_() instanceof ServerLevel level)) {
                return false;
            }
            if (maid.m_213877_() || maid.m_21224_()) {
                return false; // 已移除 / 死亡
            }
            EntityBroom broom = MaidBroomKit.ridingBroom(maid);
            if (broom == null) {
                return false; // 没骑着扫帚：交给同维度拉回那套更保守的规则（本类只管"她在天上"这一段）
            }
            // 【实测六百九十二：守家的时候拉回的是「她的工作区」，不是主人】
            //  玩家原话：「Home模式下，空袭牵引绳还在发力。女仆离了主人100格之后，还是会被传送回来。」
            //  实测日志实证（2026-09-27 01:32:22）：她正「home=true 沿工作范围盘旋」，主人跑出 100 格
            //  → 这条绳当场把连人带扫帚的她搬到主人那边 100 格外，随后整整 5 分钟回不到圈里
            //  （一路「被方块顶住 → 飘到最近的空气格」，高度从 y=15 一路被垫到 40 多）。
            //  守家的语义就是"待在家里"，所以这一档：**距离基准 = 圈心、落点 = 圈心**，
            //  主人走多远都不再是把守家女仆拽走的理由；"她飞太远回不来"照旧由这条绳兜住，只是拉回家。
            //  口径复用 WorkAreaClamp.homeAnchor（= home 模式 + 圈心有效），别在这里重写一份。
            net.minecraft.core.BlockPos home = com.maidsmart.follow.WorkAreaClamp.homeAnchor(maid);
            LivingEntity owner = maid.m_269323_();
            if (home == null) {
                if (owner == null || !owner.m_6084_()) {
                    return false; // 主人不在已加载世界 / 已死——没有可传的目标
                }
                if (owner.m_9236_() != level) {
                    return false; // 跨维度不抢（那种情况走 followIfCrossDimension / 排班表人工传送）
                }
            }
            double refX = home != null ? home.m_123341_() + 0.5 : owner.m_20185_();
            double refY = home != null ? home.m_123342_() + 0.5 : owner.m_20186_();
            double refZ = home != null ? home.m_123343_() + 0.5 : owner.m_20189_();
            double dSq = maid.m_20275_(refX, refY, refZ);
            if (dSq <= (double) radius * radius) {
                RETRY_READY.remove(maid); // 回到半径内 → 清失败冷却
                return false;
            }
            long now = level.m_46467_();
            Long ready = RETRY_READY.get(maid);
            if (ready != null && now < ready) {
                return false;
            }
            boolean ok = (home != null)
                    ? com.maidsmart.follow.MaidChunkLoadManager.recallBroomRiderTo(maid, home)
                    : com.maidsmart.follow.MaidChunkLoadManager.recallBroomRider(maid, owner);
            if (!ok) {
                RETRY_READY.put(maid, now + RETRY_COOLDOWN);
                return false;
            }
            RETRY_READY.remove(maid);
            int blocks = (int) Math.sqrt(dSq);
            String name = com.maidsmart.tool.PromaidLog.nameOf(maid);
            com.maidsmart.tool.PromaidLog.log("扫帚牵引绳", name + (home != null ? " 距工作区 " : " 距主人 ")
                    + blocks + " 格（> " + radius + " 格），已连人带扫帚传送回"
                    + (home != null ? "工作岗位" : "主人身边"));
            // 系统消息只发给"主人是玩家"的那一档（女仆的主人本来就是玩家，这里只是类型上
            // 站得住脚——LivingEntity 没有 displayClientMessage，必须落到 Player 上）
            if (owner instanceof net.minecraft.world.entity.player.Player player) {
                Long msgReady = MESSAGE_READY.get(maid);
                if (msgReady == null || now >= msgReady) {
                    MESSAGE_READY.put(maid, now + MESSAGE_COOLDOWN);
                    player.m_5661_(Component.m_237113_(home != null
                            ? "§e✦ §f你的女仆 §b" + name + "§f 骑着扫帚飞离工作区 §e" + blocks
                              + " §f格（扫帚牵引绳），已连人带扫帚回到岗位上。"
                            : "§e✦ §f你的女仆 §b" + name + "§f 骑着扫帚跑出了 §e" + blocks
                              + " §f格（扫帚牵引绳），已连人带扫帚回到你身边。"), false);
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
