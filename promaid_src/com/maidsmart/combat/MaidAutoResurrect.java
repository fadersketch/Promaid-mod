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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实测四百一十六【女仆自动复活】——用户："女仆死亡后 60 秒那个墓碑就会自己消失掉，
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
            long due = level.m_46467_()
                    + com.maidsmart.config.MaidSmartConfig.AUTO_RESURRECT_DELAY_SECONDS.get() * 20L;
            Pending p = new Pending(nbt, owner.m_20148_(), due,
                    tombstone != null ? tombstone.m_20148_() : null);
            // 墓碑转为纯标记：清空其容器，物品只存在死亡快照里（复活时归还）。
            // 否则 60 秒窗口内玩家可拾取墓碑 → 复活又归还快照 = 物品复制。
            if (tombstone != null) {
                try {
                    net.minecraftforge.items.ItemStackHandler items = tombstone.getItems();
                    for (int i = 0; i < items.getSlots(); i++) {
                        items.setStackInSlot(i, net.minecraft.world.item.ItemStack.f_41583_);
                    }
                } catch (Throwable ignored) {
                }
            }
            // 统一写主世界表（跨维度共享；读取端也只读主世界表）
            MinecraftServer server = level.m_7654_();
            ServerLevel overworld = server != null ? server.m_129880_(Level.f_46428_) : level;
            store(overworld).put(maidId, p);
        } catch (Throwable ignored) {
        }
    }

    // ================== 到期复活 ==================

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!com.maidsmart.config.MaidSmartConfig.AUTO_RESURRECT_ENABLE.get()) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        // 待复活表跨维度共享（存在主世界 SavedData）
        AutoResurrectStore data = store(server.m_129880_(Level.f_46428_));
        if (data == null || data.isEmpty()) {
            return;
        }
        long now = server.m_129921_();
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
        double[] safe = com.maidsmart.protect.MasterDeathTeleportHandler.findSafeLanding(
                dest, respawn.m_123341_() + 0.5, respawn.m_123342_(), respawn.m_123343_() + 0.5);
        EntityMaid maid = new EntityMaid(dest);
        maid.m_20258_(p.maidNbt); // load：完整恢复背包/饰品/任务/模型
        maid.m_20084_(maidId); // 复用原 UUID——记忆/灵魂目录按 UUID 索引
        maid.m_6034_(safe[0], safe[1], safe[2]);
        // 血量：死亡存档里 Health=0，必须重置，否则复活即死
        float max = Math.max(1.0f, maid.m_21233_());
        float ratio = (float) (double) com.maidsmart.config.MaidSmartConfig.AUTO_RESURRECT_HEALTH_RATIO.get();
        maid.m_21153_(Math.max(1.0f, max * ratio));
        maid.m_146917_(20);
        maid.f_19789_ = 0.0f;
        maid.f_20920_ = 0.0f; // deathTime 清零（存档里的 DeathTime 会被 load 恢复）
        maid.m_20256_(net.minecraft.world.phys.Vec3.f_82478_);
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
                    if (maidId != null && nbt != null && ownerId != null) {
                        pending.put(maidId, new Pending(nbt, ownerId, due, tomb));
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
                if (e.getValue().tombstoneId != null) {
                    c.m_128362_("tomb", e.getValue().tombstoneId);
                }
                list.add(c);
            }
            tag.m_128365_("pending", list);
            return tag;        }
    }
}
