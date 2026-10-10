package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * v1.2.0（1.21.1）实测四百六十九：飞行作战——免疫"鞘翅撞击伤害"（用户要求的后门）。
 *
 * 女仆在飞行作战里高速滑翔，撞到方块会吃原版 `fly_into_wall` 伤害（原版对鞘翅玩家也照常结算）。
 * 飞行链路（烟花推进 → 盘旋/俯冲）撞墙并不罕见，一撞就掉血会直接打断连招，所以在
 * "生存与复活"分区给了开关：默认开 = 免疫。
 *
 * 判定用 `getMsgId().equals("flyIntoWall")`——这个字符串跨版本稳定（1.20.1 与 1.21.1 的
 * `data/minecraft/damage_type/fly_into_wall.json` 都是 `"message_id": "flyIntoWall"`），
 * 比绑定某个 SRG/Mojmap 常量名更耐版本变化。
 *
 * 只管飞行任务（`MaidFlightKit.isFlightTask`），不影响普通坠落/撞墙。
 *
 * v1.2.0 实测四百九十四：本类同时承担**空袭免疫摔落伤害**（`fall`，**默认关**）。
 * 同一个 message_id 判定手法——`data/minecraft/damage_type/fall.json` 两个版本都是
 * `"message_id": "fall"`（1.20.1 与 1.21.1 的版本目录 jar 逐个开包核对过）。
 *
 * <p>实测八百五十六：「大运」佩戴者的冲撞免疫也挂在这里（开关 `accessory.daYunNoWallDamage`，默认开）。
 * 戴着「大运」的**玩家或女仆**只要在鞘翅滑翔，撞到方块就不再掉血——它与下面那条女仆飞行链路是
 * **两道独立闸**：那条认「女仆在飞任务 / 跟随 / 收手中」，这条只认「戴着大运 + 在滑翔」，
 * 所以玩家自己飞、或女仆不在飞任务时也管。判"戴没戴"复用现成的两处：
 * 玩家 {@code DaYunPlayerHook.hasDaYun}（Curios 反射）、女仆 {@code DaYunWallPhase.wearsDaYun}
 * （TLM 饰品栏扫描）。**只认服务端**——客户端也取消会造成"本地不掉血、服务端照扣"的错位。
 */
@EventBusSubscriber(modid = "promaid")
public final class MaidFlightWallGuard {

    /** 原版 fly_into_wall 的 message_id（两个版本一致，json 实证） */
    private static final String FLY_INTO_WALL = "flyIntoWall";
    /** 原版 fall 的 message_id（两个版本一致，json 实证） */
    private static final String FALL = "fall";

    private MaidFlightWallGuard() {
    }

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        // 实测八百五十六：「大运」佩戴者的冲撞免疫（前置闸；不归它管就落回下面的女仆飞行链路）
        if (tryDaYunWallImmunity(event)) {
            return;
        }
        if (!(event.getEntity() instanceof EntityMaid maid)) {
            return;
        }
        if (!MaidFlightKit.isFlightTask(maid)) {
            // v1.2.2 实测六百〇八【飞行跟随也要一起管】：她不是飞行任务，但此刻确实挂在鞘翅上
            // ——撞墙/摔落这两个开关若按"飞行任务"判就会把她整个漏掉（女仆 20 血，撞一次墙
            // 就没）。只多认这一个状态，普通坠落/撞墙照旧不碰。
            // 【isSettling 那一半】她收手时若还在空中，鞘翅会继续戴着让她滑翔落地（空中摘 =
            // 自由落体），那一段"行为已停、人还在鞘翅上"的窗口同样要管。
            if (!com.maidsmart.combat.MaidFlightFollowBehavior.isFollowing(maid)
                    && !com.maidsmart.combat.MaidFlightFollowBehavior.isSettling(maid)) {
                return;
            }
        }
        var src = event.getSource();
        if (src == null) {
            return;
        }
        try {
            String msg = src.getMsgId();
            // 撞墙免疫（默认开）
            if (FLY_INTO_WALL.equals(msg)
                    && com.maidsmart.config.MaidSmartConfig.COMBAT_FLIGHT_NO_WALL_DAMAGE.get()) {
                event.setCanceled(true);
                // v1.2.0 实测四百七十二：留一条可核验的记录。此前，
                // 但日志里根本没有 flyIntoWall —— 无从分辨"没触发"还是"触发了没拦住"。
                // 记一笔之后，latest.log 搜「飞行撞击免疫」即可确认是本闸拦下的。
                com.maidsmart.tool.PromaidLog.log("飞行撞击免疫",
                        com.maidsmart.tool.PromaidLog.nameOf(maid) + " 拦下鞘翅撞击伤害");
                return;
            }
            // 摔落免疫（实测五百二十六 起默认开）——v1.2.0 实测四百九十四 引入
            if (FALL.equals(msg)
                    && com.maidsmart.config.MaidSmartConfig.COMBAT_FLIGHT_NO_FALL_DAMAGE.get()) {
                event.setCanceled(true);
                com.maidsmart.tool.PromaidLog.log("空袭免摔",
                        com.maidsmart.tool.PromaidLog.nameOf(maid) + " 拦下摔落伤害");
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 「大运」佩戴者的鞘翅冲撞免疫：戴着「大运」的玩家 / 女仆在滑翔中免疫 {@code fly_into_wall}。
     *
     * <p>只认「戴着 + 在滑翔」两件事——不看任务、不看 loader 侧（两树同一套判据）。
     * 取消之后写一条可核验的日志（latest.log 搜「大运免疫冲撞」），与既有的「飞行撞击免疫」区分开。
     *
     * @return true = 本闸已拦下（调用方直接返回）；false = 不归本闸管，交给女仆飞行链路
     */
    private static boolean tryDaYunWallImmunity(LivingIncomingDamageEvent event) {
        try {
            if (!com.maidsmart.config.MaidSmartConfig.DAYUN_NO_WALL_DAMAGE.get()) {
                return false;
            }
            var entity = event.getEntity();
            if (entity == null || entity.level().isClientSide() || !entity.isFallFlying()) {
                return false;
            }
            var src = event.getSource();
            if (src == null || !FLY_INTO_WALL.equals(src.getMsgId())) {
                return false;
            }
            if (!isDaYunWearer(entity)) {
                return false;
            }
            event.setCanceled(true);
            try {
                // 日志自己一层 try：写日志失败不该把已经做出的取消再吐回去
                com.maidsmart.tool.PromaidLog.log("大运免疫冲撞",
                        daYunNameOf(entity) + " 拦下鞘翅撞击伤害");
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 这个实体戴着「大运」吗：玩家走 Curios 反射，女仆走 TLM 饰品栏。 */
    private static boolean isDaYunWearer(net.minecraft.world.entity.Entity entity) {
        if (entity instanceof net.minecraft.server.level.ServerPlayer player) {
            return com.maidsmart.accessory.DaYunPlayerHook.hasDaYun(player);
        }
        if (entity instanceof EntityMaid maid) {
            return com.maidsmart.accessory.DaYunWallPhase.wearsDaYun(maid);
        }
        return false;
    }

    /** 日志里的称呼：女仆用现成的 nameOf，其它实体用显示名。 */
    private static String daYunNameOf(net.minecraft.world.entity.Entity entity) {
        if (entity instanceof EntityMaid maid) {
            return com.maidsmart.tool.PromaidLog.nameOf(maid);
        }
        return entity.getName().getString();
    }
}
