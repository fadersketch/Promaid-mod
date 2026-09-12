package com.maidsmart.combat;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 实测四百二十一【冷却可视化】——用户："我希望女仆复活的CD及自己回魂符的CD在
 * 玩家屏幕上可视化。"
 *
 * 服务端每秒（ProMaidExtension 的 hudTimer 节拍，与建造 HUD 同频）为每个在线玩家
 * 打包两类倒计时，走 CooldownHudPacket 发给该玩家：
 *
 * - revive：该主人名下【正在等待自动复活】的女仆——来自 MaidAutoResurrect 的
 *   待复活表（墓碑到期即复活，倒计数与到期判定同源）；
 * - soul：该主人名下【存活女仆里回魂符冷却未到】的——来自女仆 persistentData
 *   的冷却时间戳（MaidSoulSpellGuard.hudCooldownSeconds）。
 *
 * 只发本人名下女仆（不泄露他人的冷却），无条目时不发（客户端超时自动清空 HUD，
 * 省掉空包流量）。异常一律吞掉——HUD 广播失败绝不影响游戏逻辑。
 */
public final class CooldownHudTracker {

    /** 实测四百二十九附：核对日志节流（每 5 秒最多一条） */
    private static long lastLogNanos = 0L;

    private CooldownHudTracker() {
    }

    /** 每秒由 ProMaidExtension 调用：按主人打包两类倒计时并下发 */
    public static void broadcast(MinecraftServer server) {
        if (server == null || !com.maidsmart.config.MaidSmartConfig.MISC_COOLDOWN_HUD.get()) {
            return;
        }
        try {
            List<ServerPlayer> players = server.m_6846_().m_11314_();
            if (players.isEmpty()) {
                return;
            }
            // 存活女仆的回魂符冷却按主人分桶（一次全图扫描，避免每玩家各扫一遍）
            Map<UUID, List<String[]>> soulByOwner = new HashMap<>();
            for (ServerLevel level : server.m_129785_()) {
                for (net.minecraft.world.entity.Entity e : level.m_8583_()) {
                    if (!(e instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid)
                            || !maid.m_6084_()) {
                        continue;
                    }
                    long[] cd = MaidSoulSpellGuard.hudCooldownSeconds(maid, level.m_46467_());
                    if (cd == null) {
                        continue;
                    }
                    net.minecraft.world.entity.LivingEntity owner = maid.m_269323_();
                    if (owner == null) {
                        continue;
                    }
                    UUID ownerId = owner.m_20148_();
                    soulByOwner.computeIfAbsent(ownerId, k -> new ArrayList<>())
                            .add(new String[]{"soul", com.maidsmart.tool.PromaidLog.nameOf(maid),
                                    String.valueOf(cd[0]), String.valueOf(cd[1])});
                }
            }
            for (ServerPlayer player : players) {
                List<String[]> entries = new ArrayList<>();
                // 复活倒计时：待复活表直接按主人取
                for (String[] r : MaidAutoResurrect.hudReviveEntries(server, player.m_20148_())) {
                    entries.add(new String[]{"revive", r[0], r[1], r[2]});
                }
                List<String[]> soul = soulByOwner.get(player.m_20148_());
                if (soul != null) {
                    entries.addAll(soul);
                }
                // 实测四百三十三：主人背包里【冷却中的自动魂符】也计入——女仆被收进符里
                // （实体已移除）时存活女仆扫描查不到，只有靠符本身的冷却戳才能显示出来
                long nowTick = player.m_9236_().m_46467_();
                long charmTotal = Math.max(1L,
                        com.maidsmart.config.MaidSmartConfig.SOUL_SPELL_COOLDOWN_SECONDS.get());
                net.minecraft.world.entity.player.Inventory inv = player.m_150109_();
                for (int i = 0; i < inv.m_6643_(); i++) {
                    net.minecraft.world.item.ItemStack st = inv.m_8020_(i);
                    long until = MaidSoulSpellGuard.charmCooldownUntil(st);
                    if (until <= nowTick) {
                        continue;
                    }
                    long remain = (until - nowTick + 19L) / 20L;
                    String nm = MaidSoulSpellGuard.charmMaidName(st);
                    entries.add(new String[]{"soul", nm != null ? nm : "\u5973\u4ec6",
                            String.valueOf(Math.min(remain, charmTotal)), String.valueOf(charmTotal)});
                }
                if (!entries.isEmpty()) {
                    com.maidsmart.build.BlueprintBookNetworking.sendCooldownHud(player, entries);
                    // 实测四百二十九附：限频核对日志（每 5 秒一条，promaid.log 搜「冷却HUD」）
                    // ——确认服务端确有数据下发（HUD 不显示时用它区分"没数据"还是"没渲染"）
                    long nowNs = System.nanoTime();
                    if (nowNs - lastLogNanos > 5_000_000_000L) {
                        lastLogNanos = nowNs;
                        // 实测四百四十一：日志带上条目详情（kind:名字:剩余/总），
                        // 下次"条不见了"能一眼看出是没数据还是名字丢了
                        StringBuilder detail = new StringBuilder();
                        for (String[] e : entries) {
                            detail.append(detail.length() == 0 ? " | " : ", ")
                                    .append(e[0]).append(':').append(e[1]).append(':')
                                    .append(e[2]).append('/').append(e[3]);
                        }
                        com.maidsmart.tool.PromaidLog.log("冷却HUD",
                                player.m_5446_().getString() + " 下发 " + entries.size() + " 条" + detail);
                    }
                }
            }
        } catch (Throwable ignored) {
            // HUD 广播失败不影响任何游戏逻辑
        }
    }
}
