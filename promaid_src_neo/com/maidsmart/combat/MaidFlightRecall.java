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
 * 的球内找不到**参照点** → 立刻把她传送回参照点（等效排班表的那次人工传送）：
 * **非守家时参照点 = 主人；守家时参照点与落点都是她的工作区圈心**（实测六百九十二）。
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
 *   <li><b>【实测六百九十二】守家时把「参照点」换成她的工作区</b>：玩家原话「Home模式下，
 *       空袭牵引绳还在发力。女仆离了主人100格之后，还是会被传送回来。」——旧版正是"不查 home"
 *       才会这样：守家的她只要飞在空中，主人一走远就被拽到主人那边（扫帚那一侧有实测日志
 *       ：01:32:22 她正 home=true 沿工作范围盘旋，被搬到主人那边 100 格外后 5 分钟回不到圈里）。
 *       现在守家期间**距离基准与落点都用圈心**（{@code WorkAreaClamp.homeAnchor}）：
 *       防"回不来"的初衷照旧成立（拉回家），而"主人走远"不再是理由。
 *       旧版担心的"排班表自动锚定 home → 排班女仆永远召不回"也一并解决——她会被召回**岗位**；</li>
 *   <li>主人跨维度时不抢：那一路上有 {@code followIfCrossDimension}（本轮攻击结束即传），
 *       在这里立刻抢会重演实测四百八十七"猛击被传送打断"的老问题。守家那一档不看主人维度
 *       （落点是自家圈心，与主人在哪个维度无关）。</li>
 * </ul>
 *
 * 【距离口径】3D 距离（水平 + 竖直一起算）。所以"飞太高"本身就会触发：她爬升到参照点
 * 100 格以上时，参照点仍在她的球外——那正是"飞太高回不来"的字面含义。
 *
 * 【落点】与排班表的人工传送同一条链路：{@code recallFromFlight} /
 * {@code recallFromFlightTo} → {@code teleportCore(force = true)} / {@code standSpotAt}——
 * 强制（参照点边找不到可站立格就直接落在参照点所在格）+ 无视地块 + 可空中。
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
            if (!(maid.level() instanceof ServerLevel level)) {
                return;
            }
            if (maid.isRemoved() || maid.isDeadOrDying() || maid.isPassenger()) {
                return; // 已移除 / 死亡 / 骑乘中（骑乘 = 被别的东西带着走，不抢）
            }
            if (maid.onGround()) {
                return; // 已落地：交给 trySameDimPull 的保守口径（它查 home/坐姿/干活豁免）
            }
            LivingEntity owner = maid.getOwner();
            // 【实测六百九十二：守家的时候拉回的是「她的工作区」，不是主人】
            //  玩家原话：「Home模式下，空袭牵引绳还在发力。女仆离了主人100格之后，还是会被传送回来。」
            //  旧版那段注释（"不看 home 模式"）的理由是"排班表会自动给女仆锚定 home，照抄那道门会变成
            //  用了排班的女仆永远召不回"——那条担心的解法不是"无视 home"，而是**把落点从主人换成家**：
            //  守家期间距离基准与落点都用工作区圈心（WorkAreaClamp.homeAnchor，口径只此一处），
            //  主人走多远都不再是把守家女仆拽走的理由，而"她飞太远回不来"照旧由这条绳兜住（拉回家）。
            //  实测日志实证（2026-09-27 01:32:22，扫帚那一侧同一条病）：她正「home=true 沿工作范围
            //  盘旋」，主人跑出 100 格就被搬到主人那边 100 格外，随后 5 分钟回不到圈里。
            net.minecraft.core.BlockPos home = com.maidsmart.follow.WorkAreaClamp.homeAnchor(maid);
            if (home == null) {
                if (owner == null || !owner.isAlive()) {
                    return; // 主人不在已加载世界 / 已死——没有可传的目标
                }
                if (owner.level() != level) {
                    return; // 跨维度不抢（本轮攻击结束后由跨维度跟随处理）
                }
            }
            double refX = home != null ? home.getX() + 0.5 : owner.getX();
            double refY = home != null ? home.getY() + 0.5 : owner.getY();
            double refZ = home != null ? home.getZ() + 0.5 : owner.getZ();
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
            boolean ok = (home != null)
                    ? com.maidsmart.follow.MaidChunkLoadManager.recallFromFlightTo(maid, home)
                    : com.maidsmart.follow.MaidChunkLoadManager.recallFromFlight(maid, owner);
            if (!ok) {
                RETRY_READY.put(maid, now + RETRY_COOLDOWN);
                return;
            }
            RETRY_READY.remove(maid);
            int blocks = (int) Math.sqrt(dSq);
            String name = com.maidsmart.tool.PromaidLog.nameOf(maid);
            com.maidsmart.tool.PromaidLog.log("空袭牵引绳", name + (home != null ? " 距工作区 " : " 距主人 ")
                    + blocks + " 格（> " + radius + " 格），已传送回"
                    + (home != null ? "工作岗位" : "主人身边"));
            // 系统消息只发给"主人是玩家"的那一档（女仆的主人本来就是玩家，这里只是类型上
            // 站得住脚——LivingEntity 没有 sendSystemMessage，必须落到 Player 上）
            if (owner instanceof net.minecraft.world.entity.player.Player player) {
                Long msgReady = MESSAGE_READY.get(maid);
                if (msgReady == null || now >= msgReady) {
                    MESSAGE_READY.put(maid, now + MESSAGE_COOLDOWN);
                    player.sendSystemMessage(Component.literal(home != null
                            ? "§e✦ §f你的女仆 §b" + name + "§f 飞离工作区 §e" + blocks
                              + " §f格（空袭牵引绳），已先回到岗位上。"
                            : "§e✦ §f你的女仆 §b" + name + "§f 飞出了 §e" + blocks
                              + " §f格（空袭牵引绳），已先回到你身边。"));
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
