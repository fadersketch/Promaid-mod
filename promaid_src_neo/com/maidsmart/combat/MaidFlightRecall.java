package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 空袭牵引绳：空袭期间，以女仆为圆心、半径 N 格（默认 100，可配）的球内找不到**主人** → 立刻把她传送
 * 回主人身边（等效排班表那次人工传送）。防"飞太高把目标打死后自己回不来"。
 *
 * 【为什么非要单做】现有三条"远距拉回"链路全主动放行空袭：{@code trySameDimPull}（同维度远距 48 格）
 * 一见 {@link MaidFlightKit#isFlightAirborne} 就 return；{@code followIfCrossDimension} 滑翔中不传；
 * TLM 的 {@code teleportToOwner} 被 {@code MaidTeleportPreserveMixin} 在滑翔/作战空中拦掉。于是"放
 * 烟花冲上天→打完目标→主人早已不在脚下"这条路径上谁都不拉她，落点可能在几百格外的山头。
 * 【生效口径】只在"{@code !onGround}"时生效（空中=空袭正在进行，正是本条要管的窗口；地面距离交给
 * trySameDimPull 那套更保守的规则）。**守家（home）时这条绳整条不生效**——守家语义是"把她停在岗位上"，
 * 她自己那条守家回圈才是正解，本绳再插一脚就是两股力打架（旧版曾把守家参照点换成工作区圈心，本版随
 * 这道闸退场）。主人跨维度时不抢（那一路上有 followIfCrossDimension）。【距离按 3D 算】"飞太高"就触发；
 * 【落点】同排班表人工传送（强制 + 无视地块 + 可空中）；【调用点】{@code MaidToolAutoEquipBehavior
 * .checkExtraStartConditions} 飞行分支（core 行为每 tick 一次；有主活动女仆挂强制加载票，所以 brain 照跑）。
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
            // 【R1118】显式「飞过去」/ 拴绳挂载 / 飞行跟随进行中：牵引绳整条静默。
            // 旧版在她飞向远处目标点时反复"距主人 101 格 → 传送回主人身边"，
            // 而 Entity.teleportTo 的 unRide() 会把绳子上的玩家甩下去 = 绳子当场断。
            if (com.maidsmart.patrol.MaidTravelTarget.active(maid)
                    || com.maidsmart.combat.GunnerTetherManager.isHanging(maid)
                    || com.maidsmart.combat.MaidFlightFollowBehavior.isFollowing(maid)) {
                return;
            }
            // 本轮空中攻击进行中（起飞/突袭/等待再点火）：牵引绳不抢。她可能正把敌人往外推，
            // 这时传回主人身边会直接打断长矛突袭；一轮收尾后 isEngaged 自然为假，照常拉回。
            if (MaidFlightCombatBehavior.isEngaged(maid)) {
                return;
            }
            // 状态表在行为 stop/start 的那一拍可能短暂为空；只要她还有活着的交战目标，
            // 这一拍就不算"打完收工"，牵引绳不抢——否则恰好在重新点火的瞬间把她拽回主人身边。
            LivingEntity combatTarget = maid.getTarget();
            if (combatTarget == null) {
                combatTarget = maid.getBrain()
                        .getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET)
                        .orElse(null);
            }
            if (combatTarget != null && combatTarget.isAlive()
                    && maid.distanceToSqr(combatTarget) <= 50.0 * 50.0) {
                return;
            }
            if (!(maid.level() instanceof ServerLevel level)) {
                return;
            }
            if (maid.isRemoved() || maid.isDeadOrDying() || maid.isPassenger()) {
                return; // 已移除 / 死亡 / 骑乘中（骑乘 = 被别的东西带着走，不抢）
            }
            if (maid.onGround()) {
                return; // 已落地：交给 trySameDimPull 的保守口径（它查 home/坐姿/干活豁免）
            }
            // 【实测七百三十七】守家的女仆这条绳**整条不生效**：
            //
            //  牵引绳不再生效」。home 模式的语义是"把她停在岗位上"——她自己那条守家回圈
            //  （TLM {@code SchedulePos.tick}，每 2 秒一次、对空袭任务不被豁免）本来就是"拉她回家"
            //  的正解，这条绳再插一脚只会两股力打架。所以 home 模式直接返回：不传送、不报消息。
            //  非 home 的女仆一个字不变（还是"飞太远就拉回主人"，就是原来的口径）。
            if (maid.isHomeModeEnable()) {
                return;
            }
            LivingEntity owner = maid.getOwner();
            // 【实测七百三十七：非 home 时才生效】上面那道 home 闸之后，这条绳只剩"非守家"这一档：
            //  参照点与落点都是主人。守家那一档（曾经的 WorkAreaClamp.homeAnchor 分支）已随那道闸
            //  一起退场——守家由她自己的守家回圈负责，这条绳在 home 模式下整条不生效（见上面的注释）。
            if (owner == null || !owner.isAlive()) {
                return; // 主人不在已加载世界 / 已死——没有可传的目标
            }
            if (owner.level() != level) {
                return; // 跨维度不抢（本轮攻击结束后由跨维度跟随处理）
            }
            double refX = owner.getX();
            double refY = owner.getY();
            double refZ = owner.getZ();
            double dSq = maid.distanceToSqr(refX, refY, refZ);
            if (dSq <= (double) radius * radius) {
                RETRY_READY.remove(maid); // 回到半径内 → 清失败冷却
                return;
            }
            long now = level.getGameTime();
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
            // 站得住脚——LivingEntity 没有 sendSystemMessage，必须落到 Player 上）
            if (owner instanceof net.minecraft.world.entity.player.Player player) {
                Long msgReady = MESSAGE_READY.get(maid);
                if (msgReady == null || now >= msgReady) {
                    MESSAGE_READY.put(maid, now + MESSAGE_COOLDOWN);
                    player.sendSystemMessage(Component.literal(
                            "§e✦ §f你的女仆 §b" + name + "§f 飞出了 §e" + blocks
                              + " §f格（空袭牵引绳），已先回到你身边。"));
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightRecall", ignored);
        }
    }
}
