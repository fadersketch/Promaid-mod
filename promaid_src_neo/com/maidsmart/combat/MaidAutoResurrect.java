package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityTombstone;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实测四百一十六【女仆自动复活】——反馈："女仆死亡后 60 秒那个墓碑就会自己消失掉，
 * 然后在主人的出生点复活。也是 60 秒的 CD。"
 *
 * 机制（对齐 TLM 原版死亡→墓碑→取回链路，但免去手动拾取）：
 * 1. 女仆死亡时 TLM 会创建墓碑并 post MaidTombstoneEvent——本类监听该事件，
 *    把【女仆完整存档 NBT】（saveWithoutId，与魂符/TLM 取回同源）连同主人 UUID、
 *    到期刻、墓碑 UUID 快照进 SavedData 持久化（防重启/区块卸载丢失）；
 * 2. 延迟到期（默认 10 秒）→ 自动让墓碑消失（discard，走 TLM 的 remove 钩子同步
 *    清理 MaidWorldData 的墓碑记录）→ 在【主人重生点】复活女仆（床/重生锚，无则
 *    主世界出生点；复用 MasterDeathTeleportHandler 的解析与安全落点）。
 *
 * 复活实现与 TLM 的"魂符释放"同构：new EntityMaid(level) → load(nbt) →
 * 设 UUID/血量 → setPos(落点) → level.addFreshEntity。背包/饰品/任务/模型等全部原样恢复。
 * 主人不在线：等待主人上线后再复活（墓碑保留），不丢女仆。
 */
public final class MaidAutoResurrect {

    private static final String DATA_NAME = "maid_smart_auto_resurrect";

    /** 待复活快照 */
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
            if (maid.level().isClientSide() || maid.getOwner() == null) {
                return;
            }
            CompoundTag nbt = new CompoundTag();
            maid.saveWithoutId(nbt); // 此时背包/饰品完整
            DEATH_NBT.put(maid.getUUID(), nbt);
        } catch (Throwable ignored) {
        }
    }

    @SubscribeEvent
    public static void onTombstone(com.github.tartaricacid.touhoulittlemaid.api.event.MaidTombstoneEvent event) {
        if (!com.maidsmart.config.MaidSmartConfig.AUTO_RESURRECT_ENABLE.get()) {
            return;
        }
        EntityMaid maid = event.getMaid();
        EntityTombstone tombstone = event.getTombstone();
        try {
            if (maid.level().isClientSide() || !(maid.level() instanceof ServerLevel level)) {
                return;
            }
            net.minecraft.world.entity.LivingEntity owner = maid.getOwner();
            if (owner == null) {
                return;
            }
            UUID maidId = maid.getUUID();
            CompoundTag nbt = DEATH_NBT.remove(maidId);
            if (nbt == null || nbt.isEmpty()) {
                nbt = new CompoundTag();
                maid.saveWithoutId(nbt);
            }
            long due = computeDueTick(level);
            // v1.2.0 实测五百四十六【去掉内置 CD（原"死亡循环断路器"）】：反馈"系统消息写着
            // 再过 60 秒再触发一次复活，但实际一直被卡住、复活不了"。
            // 那个断路器是 实测四百一十六 加的硬编码退避（死亡→复活→又死 → 推迟 60 秒，
            // 连续则 120/240/480/600 秒封顶）。它制造的问题比解决的多：
            // ①它的提示语承诺"60 秒后再试"，而退避是翻倍的 —— 玩家看到的就是"卡住"；
            // ②它把"复活点不安全"这个真问题掩盖成了"等一会就好"。
            // 现在直接删掉退避（不再是 CD），改由 resurrect() 侧从根上处理：
            // **重生点不可用就强制落在主人所在位置**（见那里的注释）。
            Pending p = new Pending(nbt, owner.getUUID(), due,
                    tombstone != null ? tombstone.getUUID() : null,
                    com.maidsmart.tool.PromaidLog.nameOf(maid));
            // 实测四百二十六【不再清空墓碑】：旧版把墓碑容器清空（防复制），导致右键墓碑
            // 什么也拿不到、墓碑直接消失——看起来像"点击墓碑复活没了"。现在墓碑物品原样
            // 实测四百三十八：右键墓碑【不再立即复活】——按 TLM 原版归还物品，同时取消
            // 这次待复活登记（见 cancelPendingOnTombstoneClick）；关闭自动复活时纯原版。
            MinecraftServer server = level.getServer();
            store(server != null ? server.overworld() : level).put(maidId, p);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 实测四百二十六：计算到期时刻（统一 gameTime 口径——旧版登记用 getGameTime、
     * 检查用 getTickCount，老存档里前者远大于后者，now>=due 永不成立 = "自动复活不触发"）。
     * 时机取自配置：0 = 延迟 N 秒；1 = 次日黎明（dayTime%24000==1，照驯养革新宠物床）。
     */
    private static long computeDueTick(ServerLevel level) {
        long nowGame = level.getGameTime();
        int mode = com.maidsmart.config.MaidSmartConfig.AUTO_RESURRECT_TIMING.get();
        if (mode == 1) {
            long into = ((level.getDayTime() % 24000L) + 24000L) % 24000L;
            long delta = (1L - into + 24000L) % 24000L;
            if (delta <= 0L) {
                delta = 24000L;
            }
            return nowGame + delta;
        }
        return nowGame + Math.max(1,
                com.maidsmart.config.MaidSmartConfig.AUTO_RESURRECT_DELAY_SECONDS.get()) * 20L;
    }

    // ================== 到期复活 ==================

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!com.maidsmart.config.MaidSmartConfig.AUTO_RESURRECT_ENABLE.get()) {
            // 实测四百二十六：关掉 = 纯 TLM 原版——丢弃待复活表（墓碑物品仍在，
            // 玩家右键仍是原版"归还物品"），避免"关掉后又打开"对已取回的墓碑重复复活。
            MinecraftServer s0 = ServerLifecycleHooks.getCurrentServer();
            if (s0 != null) {
                AutoResurrectStore d0 = store(s0.overworld());
                if (d0 != null && !d0.isEmpty()) {
                    d0.clear();
                }
            }
            // v1.2.0 实测五百四十六：断路器已移除（见 onTombstone 的注释）——关掉再打开时
            // 不再有任何退避状态需要清理。
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        ServerLevel overworld = server.overworld();
        AutoResurrectStore data = store(overworld);
        if (data == null || data.isEmpty()) {
            return;
        }
        // 实测四百二十六：now 改用【世界游戏时间】——与登记 due 同源
        long now = overworld != null ? overworld.getGameTime() : server.getTickCount();
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
            ServerPlayer owner = server.getPlayerList().getPlayer(p.ownerId);
            if (owner == null) {
                continue; // 主人不在线——墓碑保留，等她回来再复活
            }
            try {
                removeTombstone(server, p);
                // 复用原 UUID——记忆/灵魂目录按 UUID 索引，换 UUID 会丢记忆
                if (resurrect(server, owner, p, maidId)) {
                    data.remove(maidId);
                } else {
                    p.dueTick = now + 100L;
                }
            } catch (Throwable ignored) {
                p.dueTick = now + 100L;
            }
        }
    }

    private static void removeTombstone(MinecraftServer server, Pending p) {
        if (p.tombstoneId == null) {
            return;
        }
        for (ServerLevel lvl : server.getAllLevels()) {
            net.minecraft.world.entity.Entity e = lvl.getEntity(p.tombstoneId);
            if (e instanceof EntityTombstone ts) {
                ts.discard(); // → TLM remove 钩子清 MaidWorldData
            }
        }
    }

    private static boolean resurrect(MinecraftServer server, ServerPlayer owner, Pending p, UUID maidId) {
        // 重生点解析（复用死亡传送的口径：床/重生锚校验）
        ServerLevel dest = server.getLevel(owner.getRespawnDimension());
        net.minecraft.core.BlockPos respawn = owner.getRespawnPosition();
        boolean respawnUsable = dest != null && respawn != null
                && com.maidsmart.protect.MasterDeathTeleportHandler.isRespawnPointValid(dest, respawn);
        double[] safe;
        boolean forced;
        if (respawnUsable) {
            // v1.2.0【复活原地摔死根因修复】：落点改用原版等效站位——床/重生锚走
            // findStandUpPosition（与玩家复活站的那一格完全一致），而不是自己柱状扫描。
            // 旧版的 findSafeLanding 高度上下界取反（getHeight 当成最低建筑高度），
            // 扫描循环全空 → 兜底返回 y≈386 的天空 → 女仆一放出来就自由落体摔死。
            safe = com.maidsmart.protect.MasterDeathTeleportHandler.respawnLanding(dest, respawn);
            forced = false;
        } else {
            // ── v1.2.0 实测五百四十六【重生点不可用 → 强制落在主人所在位置】──
            // 反馈原文："自动复活触发以后发现女仆没有合适的复活点。明面上系统消息写的是
            // 再过 60 秒之后再触发一次复活，但是实际上会被卡住无法复活。"
            //
            // 【旧版为什么是"卡住"】这里原本退回【主世界出生点】。出生点往往是另一个
            // 坐标（甚至另一个维度）的荒郊：女仆落在那儿可能立刻又死，于是 实测四百一十六
            // 的"死亡循环断路器"把复活一再推迟（60→120→…→600 秒），玩家看到的就是
            // "说要等 60 秒，然后一直不来"。
            //
            // 【现在的口径】重生点不可用（床被拆 / 重生锚没电 / 维度不允许 / 从没设过
            // /spawnpoint）→ 直接落在【主人当前所在位置】，**强制生效、不看地块**
            // （主人在高空、岩浆边、水底也照落）。理由：主人站的地方 = 玩家自己站的
            // 地方，是这颗星球上最不会让她"一放出来又死一次"的落点；这也是"复活到她
            // 该在的人身边"这个功能的本意。
            // 主人在线的判定由调用方保证（主人不在线时整条复活都会跳过、墓碑保留）。
            dest = owner.level() instanceof ServerLevel own ? own : server.overworld();
            if (dest == null) {
                return false;
            }
            safe = new double[]{owner.getX(), owner.getY(), owner.getZ()};
            forced = true;
        }
        EntityMaid maid = new EntityMaid(dest);
        // v1.2.0：死亡快照先清洗再 load——存档里带着"坠落中"的状态（FallDistance/
        // 下坠速度），不清就会"落地前先扣一次摔落伤害"；同时清死亡计时/受击计时，
        // 保证她以干净状态出现（原版复活等价）。
        com.maidsmart.protect.MasterDeathTeleportHandler.sanitizeDeathState(p.maidNbt);
        maid.load(p.maidNbt);
        maid.setUUID(maidId); // 复用原 UUID——记忆/灵魂目录按 UUID 索引
        maid.setPos(safe[0], safe[1], safe[2]);
        float max = Math.max(1.0f, maid.getMaxHealth());
        float ratio = (float) (double) com.maidsmart.config.MaidSmartConfig.AUTO_RESURRECT_HEALTH_RATIO.get();
        maid.setHealth(Math.max(1.0f, max * ratio));
        maid.setAirSupply(20);
        maid.setTicksFrozen(0);
        com.maidsmart.protect.MasterDeathTeleportHandler.cleanseAfterRevive(maid);
        if (!dest.addFreshEntity(maid)) {
            return false;
        }
        // 复活提示：女仆自己的话语气泡 + 主人的系统消息（带名字，不怕气泡被错过）
        String name = com.maidsmart.tool.PromaidLog.nameOf(maid);
        maid.getChatBubbleManager().addTextChatBubble("主人，我回来啦！让你担心了～");
        // v1.2.0 实测五百四十六：措辞按实际落点分流——旧版无论落在哪都写"正在主人重生点等你"，
        // 而重生点不可用时她其实是在主人身边（甚至另一个维度），玩家照着提示去找会扑空。
        owner.sendSystemMessage(net.minecraft.network.chat.Component.literal(forced
                ? "\u00a7e\u2726 \u00a7f你的女仆 \u00a7b" + name
                        + "\u00a7f 的重生点不可用（床被拆/重生锚没电/维度不允许），"
                        + "\u00a7e已直接在你所在的位置复活\u00a7f。"
                : "\u00a7e\u2726 \u00a7f你的女仆 \u00a7b" + name
                        + "\u00a7f 已复活，正在主人重生点等你。"));
        com.maidsmart.tool.PromaidLog.log("自动复活", name + (forced
                ? " 的重生点不可用 → 已在主人所在位置强制复活"
                : " 已在主人重生点复活"));
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
     * 倒计时口径与 onServerTick 的到期判定同源（server.getTickCount()）。
     */
    public static java.util.List<String[]> hudReviveEntries(MinecraftServer server, UUID ownerId) {
        java.util.List<String[]> out = new ArrayList<>();
        if (server == null || ownerId == null
                || !com.maidsmart.config.MaidSmartConfig.MISC_COOLDOWN_HUD.get()) {
            return out;
        }
        AutoResurrectStore data = store(server.overworld());
        if (data == null || data.isEmpty()) {
            return out;
        }
        // 实测四百二十六：now 用世界游戏时间（与 due 同源）；总秒按当前时机模式给
        ServerLevel ow = server.overworld();
        long now = ow != null ? ow.getGameTime() : server.getTickCount();
        int mode = com.maidsmart.config.MaidSmartConfig.AUTO_RESURRECT_TIMING.get();
        long totalSec = mode == 0
                ? Math.max(1L, Math.max(1, com.maidsmart.config.MaidSmartConfig.AUTO_RESURRECT_DELAY_SECONDS.get()))
                : 0L;
        for (java.util.Map.Entry<UUID, Pending> e : data.entries()) {
            Pending p = e.getValue();
            if (!ownerId.equals(p.ownerId)) {
                continue;
            }
            long remainTicks = Math.max(0L, p.dueTick - now);
            long remainSec = (remainTicks + 19L) / 20L;
            if (totalSec > 0 && remainSec > totalSec) {
                remainSec = totalSec;
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
        AutoResurrectStore data = store(server.overworld());
        if (data == null || data.isEmpty()) {
            return false;
        }
        UUID maidId = null;
        Pending found = null;
        for (java.util.Map.Entry<UUID, Pending> e : data.entries()) {
            Pending p = e.getValue();
            if (tombstoneId.equals(p.tombstoneId) && player.getUUID().equals(p.ownerId)) {
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

    public static final class AutoResurrectStore extends net.minecraft.world.level.saveddata.SavedData {
        private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();

        public AutoResurrectStore() {
        }

        public AutoResurrectStore(CompoundTag tag) {
            net.minecraft.nbt.ListTag list = tag.getList("pending", 10);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag c = list.getCompound(i);
                try {
                    UUID maidId = c.getUUID("maid");
                    CompoundTag nbt = c.getCompound("nbt");
                    UUID ownerId = c.getUUID("owner");
                    long due = c.getLong("due");
                    UUID tomb = c.contains("tomb") ? c.getUUID("tomb") : null;
                    String name = c.contains("name") ? c.getString("name") : "";
                    if (maidId != null && nbt != null && !nbt.isEmpty() && ownerId != null) {
                        pending.put(maidId, new Pending(nbt, ownerId, due, tomb, name));
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        public static AutoResurrectStore get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(
                    new net.minecraft.world.level.saveddata.SavedData.Factory<>(
                            AutoResurrectStore::new,
                            (t, registries) -> new AutoResurrectStore(t),
                            net.minecraft.util.datafix.DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES),
                    DATA_NAME);
        }

        public void put(UUID maidId, Pending p) {
            pending.put(maidId, p);
            setDirty();
        }

        public Pending get(UUID maidId) {
            return pending.get(maidId);
        }

        public void remove(UUID maidId) {
            if (pending.remove(maidId) != null) {
                setDirty();
            }
        }

        public boolean isEmpty() {
            return pending.isEmpty();
        }

        /** 实测四百二十六：关掉自动复活时丢弃全部待复活快照（墓碑物品仍在，走原版右键取回）。 */
        public void clear() {
            if (!pending.isEmpty()) {
                pending.clear();
                setDirty();
            }
        }

        public java.util.Set<java.util.Map.Entry<UUID, Pending>> entries() {
            return pending.entrySet();
        }

        @Override
        public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
            net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
            for (java.util.Map.Entry<UUID, Pending> e : pending.entrySet()) {
                CompoundTag c = new CompoundTag();
                c.putUUID("maid", e.getKey());
                c.put("nbt", e.getValue().maidNbt);
                c.putUUID("owner", e.getValue().ownerId);
                c.putLong("due", e.getValue().dueTick);
                c.putString("name", e.getValue().maidName);
                if (e.getValue().tombstoneId != null) {
                    c.putUUID("tomb", e.getValue().tombstoneId);
                }
                list.add(c);
            }
            tag.put("pending", list);
            return tag;
        }
    }
}
