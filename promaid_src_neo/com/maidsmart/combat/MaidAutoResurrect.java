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
 * 实测四百一十六【女仆自动复活】——用户："女仆死亡后 60 秒那个墓碑就会自己消失掉，
 * 然后在主人的出生点复活。也是 60 秒的 CD。"
 *
 * 机制（对齐 TLM 原版死亡→墓碑→取回链路，但免去手动拾取）：
 * 1. 女仆死亡时 TLM 会创建墓碑并 post MaidTombstoneEvent——本类监听该事件，
 *    把【女仆完整存档 NBT】（saveWithoutId，与魂符/TLM 取回同源）连同主人 UUID、
 *    到期刻、墓碑 UUID 快照进 SavedData 持久化（防重启/区块卸载丢失）；
 * 2. 延迟到期（默认 60 秒）→ 自动让墓碑消失（discard，走 TLM 的 remove 钩子同步
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

        Pending(CompoundTag maidNbt, UUID ownerId, long dueTick, UUID tombstoneId) {
            this.maidNbt = maidNbt;
            this.ownerId = ownerId;
            this.dueTick = dueTick;
            this.tombstoneId = tombstoneId;
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
            long due = level.getGameTime()
                    + com.maidsmart.config.MaidSmartConfig.AUTO_RESURRECT_DELAY_SECONDS.get() * 20L;
            Pending p = new Pending(nbt, owner.getUUID(), due,
                    tombstone != null ? tombstone.getUUID() : null);
            // 墓碑转为纯标记：清空其容器，物品只存在死亡快照里（复活时归还）。
            // 否则 60 秒窗口内玩家可拾取墓碑 → 复活又归还快照 = 物品复制。
            if (tombstone != null) {
                try {
                    net.neoforged.neoforge.items.ItemStackHandler items = tombstone.getItems();
                    for (int i = 0; i < items.getSlots(); i++) {
                        items.setStackInSlot(i, net.minecraft.world.item.ItemStack.EMPTY);
                    }
                } catch (Throwable ignored) {
                }
            }
            // 统一写主世界表（跨维度共享；读取端也只读主世界表）
            MinecraftServer server = level.getServer();
            store(server != null ? server.overworld() : level).put(maidId, p);
        } catch (Throwable ignored) {
        }
    }

    // ================== 到期复活 ==================

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!com.maidsmart.config.MaidSmartConfig.AUTO_RESURRECT_ENABLE.get()) {
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
        long now = server.getTickCount();
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
        ServerLevel dest = server.getLevel(owner.getRespawnDimension());
        net.minecraft.core.BlockPos respawn = owner.getRespawnPosition();
        if (dest == null || respawn == null
                || !com.maidsmart.protect.MasterDeathTeleportHandler.isRespawnPointValid(dest, respawn)) {
            dest = server.overworld();
            respawn = dest != null ? dest.getSharedSpawnPos() : null;
        }
        if (dest == null || respawn == null) {
            return false;
        }
        double[] safe = com.maidsmart.protect.MasterDeathTeleportHandler.findSafeLanding(
                dest, respawn.getX() + 0.5, respawn.getY(), respawn.getZ() + 0.5);
        EntityMaid maid = new EntityMaid(dest);
        maid.load(p.maidNbt);
        maid.setUUID(maidId); // 复用原 UUID——记忆/灵魂目录按 UUID 索引
        maid.setPos(safe[0], safe[1], safe[2]);
        float max = Math.max(1.0f, maid.getMaxHealth());
        float ratio = (float) (double) com.maidsmart.config.MaidSmartConfig.AUTO_RESURRECT_HEALTH_RATIO.get();
        maid.setHealth(Math.max(1.0f, max * ratio));
        maid.setAirSupply(20);
        maid.fallDistance = 0.0f;
        maid.deathTime = 0; // 存档里的 DeathTime 会被 load 恢复
        maid.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        if (!dest.addFreshEntity(maid)) {
            return false;
        }
        // 复活提示：女仆自己的话语气泡 + 主人的系统消息（带名字，不怕气泡被错过）
        String name = com.maidsmart.tool.PromaidLog.nameOf(maid);
        maid.getChatBubbleManager().addTextChatBubble("主人，我回来啦！让你担心了～");
        owner.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "\u00a7e✦ \u00a7f你的女仆 \u00a7b" + name + "\u00a7f 已复活，正在主人重生点等你。"));
        com.maidsmart.tool.PromaidLog.log("自动复活", name + " 已在主人重生点复活");
        return true;
    }

    private static AutoResurrectStore store(ServerLevel level) {
        if (level == null) {
            return null;
        }
        return AutoResurrectStore.get(level);
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
                    if (maidId != null && nbt != null && !nbt.isEmpty() && ownerId != null) {
                        pending.put(maidId, new Pending(nbt, ownerId, due, tomb));
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
