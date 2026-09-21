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
                // v1.2.0 实测四百七十二：留一条可核验的记录。此前玩家反馈"免疫没生效"，
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
}
