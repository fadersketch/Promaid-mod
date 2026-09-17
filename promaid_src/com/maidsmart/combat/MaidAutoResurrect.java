package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityTombstone;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实测四百一十六【女仆自动复活】——反馈："女仆死亡后 60 秒那个墓碑就会自己消失掉，
 * 然后在主人的出生点复活。也是 60 秒的 CD。"
 *
 * 机制（对齐 TLM 原版死亡→墓碑→取回链路，但免去手动拾取）：
 * 1. 女仆死亡时（TLM 的 EntityMaid.die 会 post MaidDeathEvent，随后 dropEquipment
 *    创建墓碑并 post MaidTombstoneEvent）——本类监听 MaidTombstoneEvent 抓住墓碑，
 *    把【女仆完整存档 NBT】（m_20240_ saveWithoutId，与魂符/TLM 取回同源）快照进
 *    SavedData 持久化（防重启/区块卸载丢失）；
 * 2. 延迟到期（默认 60 秒）→ 自动让【墓碑消失】（discard，走 TLM 的 remove 钩子
 *    同步清理 MaidWorldData 的墓碑记录）→ 在【主人重生点】复活女仆
 *    （床/重生锚，无则主世界出生点；复用 MasterDeathTeleportHandler 的解析与安全落点）。
 *
 * 复活实现与 TLM 的"魂符释放"完全同构：新建 EntityMaid(level) → load(nbt) →
 * 设 UUID/血量 → moveTo 落点 → level.addFreshEntity。这样背包/饰品/任务/模型/
 * 记忆等全部原样回来（NBT 就是她死亡瞬间的完整存档）。
 *
 * 主人不在线：等待主人上线后再复活（墓碑保留），不丢女仆。
 */
public final class MaidAutoResurrect {

    private static final String DATA_NAME = "maid_smart_auto_resurrect";

    /** 待复活快照：女仆 UUID → 死亡时的完整 NBT + 主人 UUID + 到期游戏刻 + 墓碑 UUID */
    private static final class Pending {
        final CompoundTag maidNbt;
        final UUID ownerId;
        long dueTick;
        UUID tombstoneId;
        /** 实测四百二十一：显示名——HUD 倒计时按名字列出（旧存档无此字段时回退"女仆"） */
        final String maidName;

        Pending(CompoundTag maidNbt, UUID ownerId, long dueTick, UUID tombstoneId, String maidName) {
            this.maidNbt = maidNbt;
            this.ownerId = ownerId;
            this.dueTick = dueTick;
            this.tombstoneId = tombstoneId;
            this.maidName = maidName == null ? "" : maidName;
        }
    }

    private MaidAutoResurrect() {
    }

    // ================== 死亡捕获 ==================

    /** 死亡瞬间快照（MaidDeathEvent 在 die 早期、背包尚未被转移到墓碑之前 post） */
    private static final java.util.Map<UUID, CompoundTag> DEATH_NBT = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onMaidDeath(com.github.tartaricacid.touhoulittlemaid.api.event.MaidDeathEvent event) {
        if (!com.maidsmart.config.MaidSmartConfig.AUTO_RESURRECT_ENABLE.get()) {
            return;
        }
        EntityMaid maid = event.getMaid();
        try {
            if (maid.m_9236_().f_46443_ || maid.m_269323_() == null) {
                return; // 客户端 / 无主女仆
            }
            CompoundTag nbt = new CompoundTag();
            maid.m_20240_(nbt); // saveWithoutId：此时背包/饰品完整
            DEATH_NBT.put(maid.m_20148_(), nbt);
        } catch (Throwable ignored) {
        }
    }

    /** TLM 创建墓碑后触发（EntityMaid.dropEquipment 内 post，此时背包已搬进墓碑）——
     *  取死亡瞬间的快照 + 墓碑引用，排入待复活表 */
    @SubscribeEvent
    public static void onTombstone(com.github.tartaricacid.touhoulittlemaid.api.event.MaidTombstoneEvent event) {
        if (!com.maidsmart.config.MaidSmartConfig.AUTO_RESURRECT_ENABLE.get()) {
            return;
        }
        EntityMaid maid = event.getMaid();
        EntityTombstone tombstone = event.getTombstone();
        try {
            if (maid.m_9236_().f_46443_ || !(maid.m_9236_() instanceof ServerLevel level)) {
                return;
            }
            net.minecraft.world.entity.LivingEntity owner = maid.m_269323_();
            if (owner == null) {
                return; // 无主女仆不自动复活（无重生点可去）
            }
            UUID maidId = maid.m_20148_();
            // 优先用死亡瞬间快照（背包完整）；缺失则退回当前存档
            CompoundTag nbt = DEATH_NBT.remove(maidId);
            if (nbt == null || nbt.m_128456_()) {
                nbt = new CompoundTag();
                maid.m_20240_(nbt);
            }
            long due = computeDueTick(level);
            // v1.2.0【死亡循环断路器】：若她是在"上次复活后 30 秒内"又死的，说明复活点
            // 有问题（实机日志：复活→4 秒摔死→1 秒复活→再摔死）。此时不再按原延迟傻转，
            // 而是退避推迟（1 分钟起、翻倍、上限 10 分钟）+ 明确告警，给玩家介入的机会。
            long breaker = loopBreakerDelay(level, maidId);
            if (breaker > 0L) {
                due = Math.max(due, level.m_46467_() + breaker);
                ReviveGuard g = REVIVE_GUARD.get(maidId);
                int n = g != null ? g.consecutiveDeaths : 1;
                if (g != null && !g.warned) {
                    g.warned = true;
                    String nm = com.maidsmart.tool.PromaidLog.nameOf(maid);
                    long sec = breaker / 20L;
                    try {
                        net.minecraft.world.entity.LivingEntity ow = maid.m_269323_();
                        if (ow instanceof ServerPlayer sp) {
                            sp.m_5661_(net.minecraft.network.chat.Component.m_237113_(
                                    "\u00a7c\u26a0 \u00a7f你的女仆 \u00a7b" + nm
                                            + "\u00a7f 复活后很快又死亡（第 " + n
                                            + " 次）——复活点可能不安全（地形被改动/床被拆/"
                                            + "她落在会摔死的位置）。已把下次复活推迟 "
                                            + sec + " 秒；请检查她的重生点，或先关掉自动复活。"), false);
                        }
                    } catch (Throwable ignored) {
                    }
                    com.maidsmart.tool.PromaidLog.log("自动复活",
                            nm + " 复活后 " + (LOOP_WINDOW_TICKS / 20L) + " 秒内又死亡（第 " + n
                                    + " 次）→ 断路器退避 " + sec + " 秒（复活点可能不安全）");
                }
            }
            Pending p = new Pending(nbt, owner.m_20148_(), due,
                    tombstone != null ? tombstone.m_20148_() : null,
                    com.maidsmart.tool.PromaidLog.nameOf(maid));
            // 实测四百二十六【不再清空墓碑】：旧版把墓碑容器清空（防复制），导致右键墓碑
            // 什么也拿不到、墓碑直接消失——看起来像"点击墓碑复活没了"。现在墓碑物品原样
            // 实测四百三十八：右键墓碑【不再立即复活】——按 TLM 原版归还物品，同时取消
            // 这次待复活登记（见 cancelPendingOnTombstoneClick）；关闭自动复活时纯原版。
            // 统一写主世界表（跨维度共享；读取端也只读主世界表）
            MinecraftServer server = level.m_7654_();
            ServerLevel overworld = server != null ? server.m_129880_(Level.f_46428_) : level;
            store(overworld).put(maidId, p);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 实测四百二十六：计算到期时刻（统一 gameTime 口径——旧版登记用 getGameTime、
     * 检查用 getTickCount，老存档里前者远大于后者，now>=due 永不成立 = "自动复活不触发"）。
     * 时机取自配置：0 = 延迟 N 秒；1 = 次日黎明（dayTime%24000==1，照驯养革新宠物床）。
     */
    private static long computeDueTick(ServerLevel level) {
        long nowGame = level.m_46467_();
        int mode = com.maidsmart.config.MaidSmartConfig.AUTO_RESURRECT_TIMING.get();
        if (mode == 1) {
            long into = ((level.m_8044_() % 24000L) + 24000L) % 24000L; // 当前白天时间
            long delta = (1L - into + 24000L) % 24000L;                  // 距下一个"黎明(时刻1)"
            if (delta <= 0L) {
                delta = 24000L; // 正好死在黎明这一 tick → 顺延到第二天
            }
            return nowGame + delta;
        }
        return nowGame + Math.max(1,
                com.maidsmart.config.MaidSmartConfig.AUTO_RESURRECT_DELAY_SECONDS.get()) * 20L;
    }

    // ================== 到期复活 ==================

    /**
     * v1.2.0【死亡循环断路器】——实机日志暴露的真实问题。
     *
     * 现场（龙之梦整合包 promail.log/latest.log，2026-09-13）：同一只女仆
     * 「复活 → 4 秒后摔死 → 1 秒后又复活」连转 3 轮以上，日志里
     * `[maidhurt] 类型=fall 伤害=91.4 位置=(28.2,294.0,101.9)` 明确是摔落致死。
     * 落点 bug 已修（findSafeLanding 高度上下界取反 → 落点在 y≈386 天空），
     * 但只要【任何】因素让复活点不安全（地形被改、床被拆、跨维传送、维度规则），
     * 这个"复活即死"循环就会再次出现，而且延迟设得越小转得越快。
     *
     * 断路器：记住每只女仆【上次复活的世界时间】。若她又在【循环窗口】内死亡，
     * 说明复活点有问题——不再按原延迟傻转，而是把这次复活【推迟】并升级告警，
     * 让玩家有机会介入。连续多次则推迟得越来越久（退避），上限 10 分钟。
     */
    private static final Map<UUID, ReviveGuard> REVIVE_GUARD = new java.util.concurrent.ConcurrentHashMap<>();

    /** 复活的"循环窗口"：在这个时间内又死 = 视为复活点不安全 */
    private static final long LOOP_WINDOW_TICKS = 20L * 30L; // 30 秒
    /** 触发断路器后的基础推迟（tick） */
    private static final long BREAKER_BASE_DELAY = 20L * 60L; // 1 分钟
    /** 退避上限（tick）= 10 分钟 */
    private static final long BREAKER_MAX_DELAY = 20L * 600L;

    private static final class ReviveGuard {
        long lastReviveGameTime;   // 上次复活时刻
        int consecutiveDeaths;     // 连续"复活后很快又死"的次数
        boolean warned;            // 是否已就该女仆连续告警过（每次退避只告警一次）

        ReviveGuard(long t) {
            this.lastReviveGameTime = t;
        }
    }

    /**
     * 判断本次死亡是否落在"上次复活的循环窗口"内；是则累计次数并返回退避推迟量
     * （tick）。返回 0 = 不需要推迟。
     */
    private static long loopBreakerDelay(ServerLevel level, UUID maidId) {
        long now = level.m_46467_();
        ReviveGuard g = REVIVE_GUARD.get(maidId);
        if (g == null) {
            REVIVE_GUARD.put(maidId, new ReviveGuard(now));
            return 0;
        }
        if (now - g.lastReviveGameTime > LOOP_WINDOW_TICKS) {
            // 距上次复活已经很久 → 正常死亡，重置计数
            g.consecutiveDeaths = 0;
            g.warned = false;
            return 0;
        }
        // 循环窗口内又死 → 退避
        g.consecutiveDeaths++;
        long delay = Math.min(BREAKER_MAX_DELAY,
                BREAKER_BASE_DELAY * (1L << Math.min(4, g.consecutiveDeaths - 1)));
        return delay;
    }

    private static void noteRevive(ServerLevel level, UUID maidId) {
        ReviveGuard g = REVIVE_GUARD.get(maidId);
        if (g == null) {
            REVIVE_GUARD.put(maidId, new ReviveGuard(level.m_46467_()));
        } else {
            g.lastReviveGameTime = level.m_46467_();
            g.warned = false;
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!com.maidsmart.config.MaidSmartConfig.AUTO_RESURRECT_ENABLE.get()) {
            // 实测四百二十六：关掉 = 纯 TLM 原版——丢弃待复活表（墓碑物品仍在，
            // 玩家右键仍是原版"归还物品"），避免"关掉后又打开"对已被取回的墓碑重复复活。
            MinecraftServer s0 = ServerLifecycleHooks.getCurrentServer();
            if (s0 != null) {
                AutoResurrectStore d0 = store(s0.m_129880_(Level.f_46428_));
                if (d0 != null && !d0.isEmpty()) {
                    d0.clear();
                }
            }
            // v1.2.0：断路器状态一并清空——玩家关掉（等于已介入处理）后重开，
            // 退避不应残留（否则"明明修好了还得等十分钟"）。
            REVIVE_GUARD.clear();
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        // 待复活表跨维度共享（存在主世界 SavedData）
        ServerLevel overworld = server.m_129880_(Level.f_46428_);
        AutoResurrectStore data = store(overworld);
        if (data == null || data.isEmpty()) {
            return;
        }
        // 实测四百二十六：now 改用【世界游戏时间】——与登记 due 同源（旧版用 tickCount，
        // 老存档 gameTime 远大于 tickCount，now>=due 永不成立）
        long now = overworld != null ? overworld.m_46467_() : server.m_129921_();
        List<UUID> dueIds = new ArrayList<>();
        for (java.util.Map.Entry<UUID, Pending> e : data.entries()) {
            if (now >= e.getValue().dueTick) {
                dueIds.add(e.getKey());
            }
        }
        if (dueIds.isEmpty()) {
            return;
        }
        for (UUID maidId : dueIds) {
            Pending p = data.get(maidId);
            if (p == null) {
                continue;
            }
            ServerPlayer owner = server.m_6846_().m_11259_(p.ownerId);
            if (owner == null) {
                continue; // 主人不在线——墓碑保留，等她回来再复活
            }
            try {
                // 1. 让墓碑消失（走 TLM remove 钩子清理 MaidWorldData 记录）
                removeTombstone(server, p);
                // 2. 在主人重生点复活
                if (resurrect(server, owner, p, maidId)) {
                    data.remove(maidId);
                    // v1.2.0：记下复活时刻，供死亡循环断路器判断"是否复活后很快又死"
                    noteRevive(overworld != null ? overworld : (ServerLevel) owner.m_9236_(), maidId);
                } else {
                    // 复活失败（异常）：推迟 5 秒重试，不丢快照
                    p.dueTick = now + 100L;
                }
            } catch (Throwable ignored) {
                p.dueTick = now + 100L;
            }
        }
    }

    /** 删除墓碑实体（所有维度找同名 UUID） */
    private static void removeTombstone(MinecraftServer server, Pending p) {
        if (p.tombstoneId == null) {
            return;
        }
        for (ServerLevel lvl : server.m_129785_()) {
            net.minecraft.world.entity.Entity e = lvl.m_8791_(p.tombstoneId);
            if (e instanceof EntityTombstone ts) {
                ts.m_146870_(); // discard → TLM remove 钩子清 MaidWorldData
            }
        }
    }

    /** 在主人重生点复活女仆（与 TLM 魂符释放同构：new + load + 落点 + addFreshEntity） */
    private static boolean resurrect(MinecraftServer server, ServerPlayer owner, Pending p, UUID maidId) {
        // 重生点解析（复用死亡传送的口径：床/重生锚校验，无则主世界出生点）
        ServerLevel dest = server.m_129880_(owner.m_8963_());
        net.minecraft.core.BlockPos respawn = owner.m_8961_();
        if (dest == null || respawn == null
                || !com.maidsmart.protect.MasterDeathTeleportHandler.isRespawnPointValid(dest, respawn)) {
            dest = server.m_129880_(Level.f_46428_);
            respawn = dest != null ? dest.m_220360_() : null;
        }
        if (dest == null || respawn == null) {
            return false;
        }
        // v1.2.0【复活原地摔死根因修复】：落点改用原版等效站位——床/重生锚走
        // findStandUpPosition（与玩家复活站的那一格完全一致），而不是自己柱状扫描。
        // 旧版的 findSafeLanding 高度上下界取反（getHeight 当成最低建筑高度），
        // 扫描循环全空 → 兜底返回 y≈386 的天空 → 女仆一放出来就自由落体摔死，
        // 摔死又触发自动收符、放出再摔，形成"原地反复摔死"的死循环。
        double[] safe = com.maidsmart.protect.MasterDeathTeleportHandler.respawnLanding(dest, respawn);
        EntityMaid maid = new EntityMaid(dest);
        // v1.2.0：死亡快照先清洗再 load——存档里带着"坠落中"的状态（FallDistance/
        // 下坠速度），不清就会"落地前先扣一次摔落伤害"；同时清死亡计时/受击计时，
        // 保证她以干净状态出现（原版复活等价）。
        com.maidsmart.protect.MasterDeathTeleportHandler.sanitizeDeathState(p.maidNbt);
        maid.m_20258_(p.maidNbt); // load：完整恢复背包/饰品/任务/模型
        maid.m_20084_(maidId); // 复用原 UUID——记忆/灵魂目录按 UUID 索引
        maid.m_6034_(safe[0], safe[1], safe[2]);
        // 血量：死亡存档里 Health=0，必须重置，否则复活即死
        float max = Math.max(1.0f, maid.m_21233_());
        float ratio = (float) (double) com.maidsmart.config.MaidSmartConfig.AUTO_RESURRECT_HEALTH_RATIO.get();
        maid.m_21153_(Math.max(1.0f, max * ratio));
        maid.m_146917_(20); // ticksFrozen 清零
        com.maidsmart.protect.MasterDeathTeleportHandler.cleanseAfterRevive(maid);
        if (!dest.m_7967_(maid)) {
            return false;
        }
        // 复活提示：女仆自己的话语气泡 + 主人的系统消息（带名字，不怕气泡被错过）
        String name = com.maidsmart.tool.PromaidLog.nameOf(maid);
        maid.getChatBubbleManager().addTextChatBubble("主人，我回来啦！让你担心了～");
        owner.m_5661_(net.minecraft.network.chat.Component.m_237113_(
                "\u00a7e✦ \u00a7f你的女仆 \u00a7b" + name + "\u00a7f 已复活，正在主人重生点等你。"), false);
        com.maidsmart.tool.PromaidLog.log("自动复活", name + " 已在主人重生点复活");
        return true;
    }

    private static AutoResurrectStore store(ServerLevel level) {
        if (level == null) {
            return null;
        }
        return AutoResurrectStore.get(level);
    }

    // ================== HUD 查询（实测四百二十一） ==================

    /**
     * 实测四百二十一【冷却可视化】：HUD 用——该主人名下正在等待自动复活的女仆。
     * 返回每项 {女仆显示名, 剩余秒, 总秒}；无待复活返回空表。
     * 复用踢人用的同一份 SavedData，倒计时口径与 onServerTick 的到期判定一致
     * （server.getTickCount()，见下方注释）。
     */
    public static java.util.List<String[]> hudReviveEntries(net.minecraft.server.MinecraftServer server,
                                                            java.util.UUID ownerId) {
        java.util.List<String[]> out = new ArrayList<>();
        if (server == null || ownerId == null
                || !com.maidsmart.config.MaidSmartConfig.MISC_COOLDOWN_HUD.get()) {
            return out;
        }
        // 与 tick 判定同源：待复活表统一存主世界
        ServerLevel ow = server.m_129880_(Level.f_46428_);
        AutoResurrectStore data = store(ow);
        if (data == null || data.isEmpty()) {
            return out;
        }
        // 实测四百二十六：now 用世界游戏时间（与 due 同源）；总秒按当前时机模式给
        long now = ow != null ? ow.m_46467_() : server.m_129921_();
        int mode = com.maidsmart.config.MaidSmartConfig.AUTO_RESURRECT_TIMING.get();
        // 延迟模式：总秒 = 配置延迟（用于进度条/夹紧）；黎明模式：总秒未知，给 0（HUD 不做夹紧）
        long totalSec = mode == 0
                ? Math.max(1L, Math.max(1, com.maidsmart.config.MaidSmartConfig.AUTO_RESURRECT_DELAY_SECONDS.get()))
                : 0L;
        for (java.util.Map.Entry<UUID, Pending> e : data.entries()) {
            Pending p = e.getValue();
            if (!ownerId.equals(p.ownerId)) {
                continue;
            }
            long remainTicks = Math.max(0L, p.dueTick - now);
            long remainSec = (remainTicks + 19L) / 20L; // 向上取整，0 只在到期瞬间出现
            if (totalSec > 0 && remainSec > totalSec) {
                remainSec = totalSec; // 异常情形：显示不超过配置上限
            }
            String name = p.maidName == null || p.maidName.isEmpty() ? "女仆" : p.maidName;
            out.add(new String[]{name, String.valueOf(remainSec), String.valueOf(totalSec)});
        }
        return out;
    }

    // ================== 墓碑右键：原版交互 + 取消自动复活（实测四百三十八） ==================

    /**
     * 实测四百三十八（反馈：「在女仆死亡期间右击墓碑会直接复活……使得我设的冷却毫无意义。
     * 应当改为：自动复活开始以后右击墓碑仍然跟原版一致，同时取消复活事件」）。
     *
     * 右键墓碑【不再当场复活】——松开手，行为与 TLM 原版完全一致（归还墓碑里的物品等），
     * 只是把这块墓碑对应的【待复活登记】移除：玩家既然选择手动处理，就不再自动把她拉
     * 回来，复活冷却因此恢复意义。只有墓碑所属主人本人的主手右键才会取消（防他人误触）；
     * 未登记 / 非主人 → 什么都不做，纯原版。
     */
    public static boolean cancelPendingOnTombstoneClick(MinecraftServer server, ServerPlayer player,
                                                        UUID tombstoneId) {
        if (server == null || player == null || tombstoneId == null
                || !com.maidsmart.config.MaidSmartConfig.AUTO_RESURRECT_ENABLE.get()) {
            return false;
        }
        AutoResurrectStore data = store(server.m_129880_(Level.f_46428_));
        if (data == null || data.isEmpty()) {
            return false;
        }
        UUID maidId = null;
        Pending found = null;
        for (java.util.Map.Entry<UUID, Pending> e : data.entries()) {
            Pending p = e.getValue();
            if (tombstoneId.equals(p.tombstoneId) && player.m_20148_().equals(p.ownerId)) {
                maidId = e.getKey();
                found = p;
                break;
            }
        }
        if (maidId == null) {
            return false;
        }
        data.remove(maidId);
        String name = found.maidName == null || found.maidName.isEmpty() ? "女仆" : found.maidName;
        com.maidsmart.tool.PromaidLog.log("自动复活",
                name + " 右键墓碑 → 取消本次自动复活（墓碑按原版归还物品）");
        return true;
    }

    // ================== 持久化 ==================

    /** 待复活快照的 SavedData（存主世界，跨维度共享） */
    public static final class AutoResurrectStore extends net.minecraft.world.level.saveddata.SavedData {
        private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();

        public AutoResurrectStore() {
        }

        public AutoResurrectStore(CompoundTag tag) {
            net.minecraft.nbt.ListTag list = tag.m_128437_("pending", 10);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag c = list.m_128728_(i);
                try {
                    UUID maidId = c.m_128342_("maid");
                    CompoundTag nbt = c.m_128469_("nbt");
                    UUID ownerId = c.m_128342_("owner");
                    long due = c.m_128454_("due");
                    UUID tomb = c.m_128403_("tomb") ? c.m_128342_("tomb") : null;
                    String name = c.m_128403_("name") ? c.m_128461_("name") : "";
                    if (maidId != null && nbt != null && ownerId != null) {
                        pending.put(maidId, new Pending(nbt, ownerId, due, tomb, name));
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        public static AutoResurrectStore get(ServerLevel level) {
            return level.m_8895_().m_164861_(AutoResurrectStore::new, AutoResurrectStore::new, DATA_NAME);
        }

        public void put(UUID maidId, Pending p) {
            pending.put(maidId, p);
            m_77762_();
        }

        public Pending get(UUID maidId) {
            return pending.get(maidId);
        }

        public void remove(UUID maidId) {
            if (pending.remove(maidId) != null) {
                m_77762_();
            }
        }

        public boolean isEmpty() {
            return pending.isEmpty();
        }

        /** 实测四百二十六：关掉自动复活时丢弃全部待复活快照（墓碑物品仍在，走原版右键取回）。 */
        public void clear() {
            if (!pending.isEmpty()) {
                pending.clear();
                m_77762_();
            }
        }

        public java.util.Set<java.util.Map.Entry<UUID, Pending>> entries() {
            return pending.entrySet();
        }

        @Override
        public CompoundTag m_7176_(CompoundTag tag) {
            net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
            for (java.util.Map.Entry<UUID, Pending> e : pending.entrySet()) {
                CompoundTag c = new CompoundTag();
                c.m_128362_("maid", e.getKey());
                c.m_128365_("nbt", e.getValue().maidNbt);
                c.m_128362_("owner", e.getValue().ownerId);
                c.m_128356_("due", e.getValue().dueTick);
                c.m_128359_("name", e.getValue().maidName);
                if (e.getValue().tombstoneId != null) {
                    c.m_128362_("tomb", e.getValue().tombstoneId);
                }
                list.add(c);
            }
            tag.m_128365_("pending", list);
            return tag;        }
    }
}
