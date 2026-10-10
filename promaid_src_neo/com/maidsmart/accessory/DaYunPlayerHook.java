package com.maidsmart.accessory;

import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * v1.5.393 实测八百零四：「大运」的**玩家侧**驱动——自己戴着它鞘翅滑翔时，同样开出 5×5×5
 * 通道。
 *
 * <p>【为什么玩家侧要单开一条】女仆侧走 TLM 的 {@code IMaidBauble#onTick}（TLM 每 tick 回调
 * 装了饰品的女仆）；玩家的 Curios 饰品栏没有那条回调，本模组也没有"玩家饰品 tick"入口，
 * 所以在自己的 ServerTick 里扫一遍在线玩家。
 *
 * <p>【识别方式（全反射，零硬依赖）】玩家佩没佩戴「大运」问
 * {@link com.maidsmart.combat.CuriosElytraCompat#hasCurio}——与女仆饰品栏同一套反射链路，
 * 只认 Curios 槽位。没装 Curios 的一律跳过（物品照旧可用，只是玩家侧不生效）。
 *
 * <p>【成本】每 tick 对每个在线玩家先判滑翔，不飞的人一个反射都不做；真在飞且戴着才进破坏，
 * 块数上限由 {@code accessory.daYun.maxPerTick} 兜着。
 *
 * <p>本类两树各一份：Neo 树用 Mojmap 名（本文件），Forge 树用 SRG 名。
 */
public final class DaYunPlayerHook {

    private DaYunPlayerHook() {
    }

    /** 由 ServerTick 每 tick 调用一次。 */
    public static void tick(net.minecraft.server.MinecraftServer server) {
        try {
            if (server == null) {
                return;
            }
            boolean mayBreak = MaidSmartConfig.DAYUN_ENABLE.get()
                    && MaidSmartConfig.DAYUN_PLAYER_ENABLE.get();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                try {
                    if (player == null || !player.isAlive()) {
                        continue;
                    }
                    // v1.5.396 实测八百零九：**戴着就每 tick 都驱动一次 BGM**（飞没飞交给判据）。
                    // 旧版把 tickMusic 写在 isFallFlying 之内，于是"落地"那一刻根本走不到 stop ——
                    // 音乐会在落地后一直响到曲终；而 DaYunMusic 新加的"停止宽限期"也需要调用方
                    // 持续来问才能到点。判据（戴着 + 在飞）由 tickMusic 内部的 isFlying 负责。
                    //
                    // 省反射：既没在滑翔、又没响着（= 没佩戴，或 BGM 关着）的玩家直接跳过——
                    // 常见情形（大多数玩家没戴）就退化成"一次标志位读 + 一次 Map 查询"。
                    if (!player.isFallFlying() && !DaYunMusic.isActive(player)) {
                        continue;
                    }
                    if (!hasDaYun(player)) {
                        continue;
                    }
                    ServerLevel level = (ServerLevel) player.level();
                    tickMusic(level, player);
                    // 破坏那一段才要求"真在滑翔"（需求原文是"使用鞘翅飞行的过程中"）
                    if (mayBreak && player.isFallFlying()) {
                        // 实测八百一十：走 sweepAhead 并带上速度——沿前进方向提前清，
                        // 否则是"先撞墙停住、下一 tick 墙才没"的顿挫（）。
                        DaYunBreaker.sweepAhead(level, player,
                                MaidSmartConfig.DAYUN_MAX_HARDNESS.get().floatValue(),
                                MaidSmartConfig.DAYUN_SKIP_CONTAINERS.get(),
                                MaidSmartConfig.DAYUN_DROP_ITEMS.get(),
                                MaidSmartConfig.DAYUN_BREAK_PARTICLES.get(),
                                MaidSmartConfig.DAYUN_MAX_PER_TICK.get(),
                                player.getDeltaMovement(),
                                MaidSmartConfig.DAYUN_RANGE.get());
                    }
                } catch (Throwable ignored) {
                    // 单个玩家出错不影响其它玩家
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * v1.5.394 实测八百零五：玩家侧 BGM——戴着「大运」滑翔就起播，落地就停。
     *
     * <p>与女仆侧同一个状态机（{@link DaYunMusic} 内部做边沿触发），所以这里每 tick 无条件
     * 调一次就够了：状态没变时它一个包都不发。
     *
     * <p>v1.5.396 实测八百零九：判据补上"戴着才谈得上响"——调用点改成"戴着就调本方法"，
     * 于是**摘掉饰品**（或把 BGM 配置改成 0）也能走 {@link DaYunMusic#stop} 真正停下来，
     * 而不是留着一条响到曲终。
     */
    private static void tickMusic(ServerLevel level, ServerPlayer player) {
        int track = MaidSmartConfig.DAYUN_BGM.get();
        if (track > 0 && player.isFallFlying()) {
            DaYunMusic.play(level, player, Math.min(track, 2));
        } else {
            DaYunMusic.stop(level, player);
        }
    }

    /** 玩家饰品栏里有没有「大运」（Curios 反射；没装 Curios → false）。 */
    public static boolean hasDaYun(ServerPlayer player) {
        try {
            return com.maidsmart.combat.CuriosElytraCompat.hasCurio(player, DaYunPlayerHook::isDaYun);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 这件物品是不是「大运」——两树各一份（物品注册对象的取法两树不同）。 */
    static boolean isDaYun(ItemStack stack) {
        try {
            return stack != null && !stack.isEmpty()
                    && stack.is(com.maidsmart.ProMaidMod.DA_YUN.get());
        } catch (Throwable ignored) {
            return false;
        }
    }
}
