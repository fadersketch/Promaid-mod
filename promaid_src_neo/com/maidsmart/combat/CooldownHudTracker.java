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
 * 实测四百二十一【冷却可视化】——反馈："我希望女仆复活的CD及自己回魂符的CD在
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

    /**
     * 每秒由 ProMaidExtension 调用：按主人打包倒计时（+ 实测六百七十八 起的"绑定中"指示）并下发。
     *
     * <p>【为什么这个"关掉"的闸从函数开头挪到了每一段自己头上】实测六百七十八 起，这个包还顺带
     * 带一条"你现在被武装拴绳拴着"的指示（{@code kind = "bound"}）。玩家要的是"进入了绑定状态就
     * 在左上角显示"，与"要不要看复活/回魂符倒计时"是两件事——所以关掉冷却 HUD 只该关掉冷却那两段
     * （{@link #soulScanWanted()} 三处判据），**不该把绑定指示一起灭掉**。
     */
    public static void broadcast(MinecraftServer server) {
        if (server == null) {
            return;
        }
        try {
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            if (players.isEmpty()) {
                return;
            }
            // 存活女仆的回魂符冷却按主人分桶（一次全图扫描，避免每玩家各扫一遍）
            // ——这一段只在"冷却 HUD 开着"时才做（全图扫描是这里最贵的一步）
            Map<UUID, List<String[]>> soulByOwner = new HashMap<>();
            if (soulScanWanted()) {
                for (ServerLevel level : server.getAllLevels()) {
                    for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
                        if (!(e instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid)
                                || !maid.isAlive()) {
                            continue;
                        }
                        long[] cd = MaidSoulSpellGuard.hudCooldownSeconds(maid, level.getGameTime());
                        if (cd == null) {
                            continue;
                        }
                        net.minecraft.world.entity.LivingEntity owner = maid.getOwner();
                        if (owner == null) {
                            continue;
                        }
                        UUID ownerId = owner.getUUID();
                        soulByOwner.computeIfAbsent(ownerId, k -> new ArrayList<>())
                                .add(new String[]{"soul", com.maidsmart.tool.PromaidLog.nameOf(maid),
                                        String.valueOf(cd[0]), String.valueOf(cd[1])});
                    }
                }
            }
            for (ServerPlayer player : players) {
                List<String[]> entries = new ArrayList<>();
                // 复活倒计时：待复活表直接按主人取
                if (soulScanWanted()) {
                    for (String[] r : MaidAutoResurrect.hudReviveEntries(server, player.getUUID())) {
                        entries.add(new String[]{"revive", r[0], r[1], r[2]});
                    }
                }
                List<String[]> soul = soulByOwner.get(player.getUUID());
                if (soul != null) {
                    entries.addAll(soul);
                }
                // 【实测六百七十八】左上角"绑定中"：玩家原话"在进入绑定状态下，最好是在左上角用
                //  蓝色字体显示一下玩家现在处于绑定状态。（渲染机制同冷却计时）"——渲染那一半
                //  一个字节都没新写（就是下面这个包 → CooldownHudRenderer，左上角同一条竖直串、
                //  同样的带阴影文字），只是这一段在客户端被画成**蓝色**。判据只有一处来源：
                //  GunnerTetherManager 的挂载表（谁在拴着谁）。放在最前面 = 永远显示在最上面。
                //  它**不受冷却 HUD 开关影响**（那是两件事，见上面 broadcast 的注释）。
                String[] bound = com.maidsmart.combat.GunnerTetherManager.hudBound(player);
                if (bound != null) {
                    entries.add(new String[]{"bound", bound[0], bound[1], ""});
                }
                // 实测四百三十三：主人背包里【冷却中的自动魂符】也计入——女仆被收进符里
                // （实体已移除）时存活女仆扫描查不到，只有靠符本身的冷却戳才能显示出来
                long nowTick = player.level().getGameTime();
                long charmTotal = Math.max(1L,
                        com.maidsmart.config.MaidSmartConfig.SOUL_SPELL_COOLDOWN_SECONDS.get());
                net.minecraft.world.entity.player.Inventory inv = player.getInventory();
                if (soulScanWanted()) {
                    for (int i = 0; i < inv.getContainerSize(); i++) {
                        net.minecraft.world.item.ItemStack st = inv.getItem(i);
                        long until = MaidSoulSpellGuard.charmCooldownUntil(st);
                        if (until <= nowTick) {
                            continue;
                        }
                        long remain = (until - nowTick + 19L) / 20L;
                        String nm = MaidSoulSpellGuard.charmMaidName(st);
                        entries.add(new String[]{"soul", nm != null ? nm : "\u5973\u4ec6",
                                String.valueOf(Math.min(remain, charmTotal)), String.valueOf(charmTotal)});
                    }
                }
                if (!entries.isEmpty()) {
                    com.maidsmart.build.BlueprintBookNetworking.sendCooldownHud(player, entries);
                    // 实测四百二十九附：限频核对日志（每 5 秒一条，promaid.log 搜「冷却HUD」）
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
                                player.getDisplayName().getString() + " 下发 " + entries.size() + " 条" + detail);
                    }
                }
            }
        } catch (Throwable ignored) {
            // HUD 广播失败不影响任何游戏逻辑
        }
    }

    /**
     * 「冷却那一半要不要算」——复活倒计时 / 回魂符倒计时（三条判据：女仆身上的、背包里符上的）都看它。
     *
     * <p>【实测六百七十八 起这个包还有第二种用途】{@code kind = "bound"} 那条"绑定中"指示**不走这里**
     * ——玩家要的是"进入绑定状态就在左上角显示"，与"要不要看倒计时"是两件事，所以关掉冷却 HUD
     * 只该关掉倒计时那三段（连全图扫描一起省掉），不该把绑定指示一起灭掉。
     */
    private static boolean soulScanWanted() {
        try {
            return com.maidsmart.config.MaidSmartConfig.MISC_COOLDOWN_HUD.get();
        } catch (Throwable t) {
            return false; // 读不到配置就按旧行为（关）——这里宁可少算，也不能凭空刷包
        }
    }
}
