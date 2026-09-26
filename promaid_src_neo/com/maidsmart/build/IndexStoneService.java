package com.maidsmart.build;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指标石（Index Stone）——临时蓝图服务端状态机（1.21.1 NeoForge 版）。
 *
 * 玩法契约（与需求逐条对应）：
 * 1. 玩家手持指标石 → 客户端把视线指向的方块渲染成绿色（跟随指针移动）；
 *    右键 → 锁定（渲染变红、不再移动）；**再右键同一个锁定方块** → 取消锁定
 *    （右击别的方块 = 换锁定点；右击空气 = 保持不动）。锁定距离几乎无上限
 *    （客户端 raycast 用 512 格），不可锁空气。
 * 2. 手持指标石右击女仆 → 绑定；再右击同一只 / 右击另一只 → 解绑。必须先锁方块、
 *    再绑女仆；先绑女仆会被拒绝并提示。
 * 3. 两件事齐备 → 从【女仆当时所在方块】到【锁定的红色方块】之间所有空气方块
 *    被客户端渲染成橙色幽灵方块，女仆立刻进入一次性临时建造，从自己所在格逐步
 *    向锁定格填充。
 * 4. 材料无限制：优先从女仆背包取，不够从主人背包取；选材复用搭路规则
 *    （数量最多者优先、必须有碰撞、排除下落方块/TNT/危险方块）。
 * 5. 建造表现与建造任务完全一致（站桩 + 瞬移到工地旁 + 挥臂 + 放置音效）。
 * 6. 一个玩家同时只能有一个"锁定方块 + 绑定女仆"（完成前不能开下一个）；
 *    完成/取消 → 清空状态、女仆还原原任务/作息、发系统消息。
 */
public final class IndexStoneService {

    /** 临时建造任务 UID（隐藏任务，不在 TLM 任务面板显示） */
    public static final ResourceLocation TASK_UID = ResourceLocation.parse("maid_smart:index_build");

    /** 女仆 persistentData：本系统指派的临时建造标记 */
    public static final String TAG_INDEX_ACTIVE = "maid_smart_index_build_active";
    public static final String TAG_INDEX_OWNER = "maid_smart_index_build_player";
    public static final String TAG_INDEX_PREV_TASK = "maid_smart_index_build_prev_task";
    public static final String TAG_INDEX_PREV_HOME = "maid_smart_index_build_prev_home";
    public static final String TAG_INDEX_PREV_SCHEDULE = "maid_smart_index_build_prev_schedule";
    public static final String TAG_INDEX_TOTAL = "maid_smart_index_build_total";

    /**
     * TLM 存女仆任务用的 NBT 键字面值（EntityMaid 私有常量 TASK_TAG）。
     * CFR 反编译实证：1.21.1 与 1.20.1 都是 "MaidTask"。
     * 收回魂符时要把快照里的它还原成原任务，否则放出来立刻回到临时建造。
     */
    private static final String TAG_MAID_TASK = "MaidTask";
    /** NeoForge 存 persistentData 的子标签名（1.20.1 侧是 ForgeData） */
    private static final String FORGE_DATA_TAG = "NeoForgeData";

    /** 锁定距离几乎无上限（客户端 raycast 用）；服务端只做合法性校验 */
    public static final double LOCK_RANGE = 512.0;
    /** 连线填充上限 */
    public static final int MAX_CELLS = IndexStonePlan.MAX_CELLS;

    private IndexStoneService() {
    }

    /** 总开关（配置；关掉后指标石退化为普通物品） */
    public static boolean isEnabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.BUILD_INDEX_STONE.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 给玩家发系统消息（供物品类调用） */
    public static void msgPublic(ServerPlayer player, String text) {
        msg(player, text);
    }

    /** 玩家会话状态：锁定的方块 + 绑定的女仆 + 已展开的临时蓝图格 */
    public static final class Session {
        public BlockPos lockedBlock;
        public UUID maidId;
        public ServerLevel level;
        public List<int[]> cells = new ArrayList<>();
        public boolean done;
        /** 已挂 FORCED 票的区块包围盒 {minCx,maxCx,minCz,maxCz}（无则 null）——收尾时释放 */
        public int[] ticketedBox;

        public boolean isLocked() {
            return lockedBlock != null;
        }
    }

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    public static Session session(UUID playerId) {
        return SESSIONS.get(playerId);
    }

    public static Session sessionOrCreate(UUID playerId) {
        return SESSIONS.computeIfAbsent(playerId, k -> new Session());
    }

    /** 女仆 UUID → 执行本次临时建造的玩家 UUID（反向索引） */
    private static final Map<UUID, UUID> MAID_TO_PLAYER = new ConcurrentHashMap<>();

    /** persistentData 访问（NeoForge 需转 IEntityExtension，1.21.1 约定） */
    private static net.minecraft.nbt.CompoundTag data(EntityMaid maid) {
        return ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData();
    }

    // ==================== 方块锁定 ====================

    /**
     * 锁定/解锁切换。pos == null 表示解锁。
     * 返回 true = 已锁定（或拒绝操作时保持原状），false = 已解锁。
     *
     * v1.2.0 实测四百八十五【锁定期间不可改选】：锁定的语义就是"期间只认这一格"——
     * 右击别的方块一律无效（要保持锁定、不换点），必须先右击锁定方块解锁。
     * 这里做服务端兜底（客户端钩子已拦截，防伪造包 / 其它入口绕过）。
     */
    public static boolean toggleLock(ServerPlayer player, BlockPos pos) {
        Session s = sessionOrCreate(player.getUUID());
        if (pos == null) {
            if (busy(s)) {
                msg(player, "\u00a7c指标石：女仆还在搭建，等这次完成才能取消/开始下一个。");
                return true;
            }
            s.lockedBlock = null;
            s.level = null;
            unbindMaid(player, s);
            return false;
        }
        if (s.isLocked()) {
            if (s.lockedBlock.equals(pos)) {
                // 同一格 → 解锁（唯一解除途径）
                if (busy(s)) {
                    msg(player, "\u00a7c指标石：女仆还在搭建，等这次完成才能取消/开始下一个。");
                    return true;
                }
                s.lockedBlock = null;
                s.level = null;
                unbindMaid(player, s);
                return false;
            }
            // 别的方块 → 锁定期间不可改选：保持原锁定，提示先解锁
            msg(player, "\u00a7c指标石：锁定中——要先右击那个锁定方块解除锁定，才能选别的方块。");
            return true;
        }
        ServerLevel level = player.level() instanceof ServerLevel sl ? sl : null;
        if (level == null || !level.isLoaded(pos)) {
            return s.isLocked();
        }
        BlockState st = level.getBlockState(pos);
        if (st.isAir()) {
            msg(player, "\u00a7c指标石：不能锁定空气，请对准一个方块。");
            return s.isLocked();
        }
        s.lockedBlock = pos.immutable();
        s.level = level;
        return true;
    }

    private static boolean busy(Session s) {
        return s.maidId != null;
    }

    // ==================== 女仆绑定 ====================

    /** 右击女仆：绑定 / 解绑 / 换绑（先锁方块 → 再绑女仆） */
    public static void bindOrUnbind(ServerPlayer player, EntityMaid maid) {
        Session s = sessionOrCreate(player.getUUID());
        if (!maid.isOwnedBy(player)) {
            msg(player, "\u00a7c指标石：这不是你的女仆。");
            return;
        }
        if (maid.getUUID().equals(s.maidId)) {
            unbindMaid(player, s);
            msg(player, "\u00a76指标石：已解除绑定（锁定框保留，可重新绑定）。");
            return;
        }
        if (!s.isLocked()) {
            msg(player, "\u00a7c指标石：要先用它锁定一个方块（右键方块，绿→红），再来绑定我。");
            return;
        }
        if (s.maidId != null) {
            EntityMaid old = findMaid(player, s.maidId);
            if (old != null) {
                releaseMaid(player, old, false);
            }
            MAID_TO_PLAYER.remove(s.maidId);
            s.maidId = null;
            s.cells = new ArrayList<>();
            s.done = false;
        }
        if (!buildCells(maid, s)) {
            msg(player, "\u00a7c指标石：这两点之间没有可填充的空气方块（或已被占满）。");
            return;
        }
        s.maidId = maid.getUUID();
        MAID_TO_PLAYER.put(maid.getUUID(), player.getUUID());
        forceBuildTask(player, maid, s);
        msg(player, "\u00a7a指标石：绑定成功！" + name(maid) + " 开始搭建 " + s.cells.size()
                + " 个方块（材料从她背包取，不够从你背包取）。");
    }

    /** 解绑（不改变女仆任务；仅清会话绑定） */
    private static void unbindMaid(ServerPlayer player, Session s) {
        if (s.maidId == null) {
            return;
        }
        MAID_TO_PLAYER.remove(s.maidId);
        EntityMaid maid = findMaid(player, s.maidId);
        if (maid != null) {
            releaseMaid(player, maid, false);
        }
        s.maidId = null;
        s.cells = new ArrayList<>();
        s.done = false;
        releaseTickets(s); // 解绑 → 释放强制加载票（锁定框保留也不该继续挂票）
    }

    // ==================== 蓝图展开 ====================

    /** 女仆起点 → 锁定方块之间的空气格（与客户端幽灵渲染共用同一几何） */
    private static boolean buildCells(EntityMaid maid, Session s) {
        ServerLevel level = s.level;
        if (level == null || s.lockedBlock == null) {
            return false;
        }
        BlockPos start = maid.blockPosition();
        List<int[]> cells = IndexStonePlan.airCells(level, start, s.lockedBlock, MAX_CELLS);
        if (cells.isEmpty()) {
            return false;
        }
        s.cells = cells;
        s.done = false;
        // 强制加载：锁定距离可到 512 格，中间区块未加载就放不了（对齐常规建造行为）
        forceLoadSpan(level, s, start, s.lockedBlock);
        return true;
    }

    /**
     * 给起点→锁定点之间的区块挂 FORCED 票。上限保护：跨度超过配置的强制加载区块
     * 上限就不挂（只建已加载部分）——与 BuildPlan 超大区域语义一致。
     */
    private static void forceLoadSpan(ServerLevel level, Session s, BlockPos a, BlockPos b) {
        try {
            int minCx = Math.min(a.getX(), b.getX()) >> 4;
            int maxCx = Math.max(a.getX(), b.getX()) >> 4;
            int minCz = Math.min(a.getZ(), b.getZ()) >> 4;
            int maxCz = Math.max(a.getZ(), b.getZ()) >> 4;
            if ((long) (maxCx - minCx + 1) * (maxCz - minCz + 1)
                    > com.maidsmart.config.MaidSmartConfig.BUILD_MAX_FORCE_CHUNKS.get()) {
                return;
            }
            net.minecraft.server.level.TicketType<net.minecraft.world.level.ChunkPos> forced =
                    net.minecraft.server.level.TicketType.FORCED;
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    net.minecraft.world.level.ChunkPos cp =
                            new net.minecraft.world.level.ChunkPos(cx, cz);
                    level.getChunkSource().addRegionTicket(forced, cp, 0, cp);
                }
            }
            s.ticketedBox = new int[]{minCx, maxCx, minCz, maxCz};
        } catch (Throwable ignored) {
        }
    }

    /** 释放本会话挂的 FORCED 票（完成/取消/玩家离线时调用，防票残留锁区块） */
    private static void releaseTickets(Session s) {
        if (s == null || s.ticketedBox == null) {
            return;
        }
        int[] box = s.ticketedBox;
        s.ticketedBox = null;
        ServerLevel level = s.level;
        if (level == null) {
            return;
        }
        try {
            net.minecraft.server.level.TicketType<net.minecraft.world.level.ChunkPos> forced =
                    net.minecraft.server.level.TicketType.FORCED;
            for (int cx = box[0]; cx <= box[1]; cx++) {
                for (int cz = box[2]; cz <= box[3]; cz++) {
                    net.minecraft.world.level.ChunkPos cp =
                            new net.minecraft.world.level.ChunkPos(cx, cz);
                    level.getChunkSource().removeRegionTicket(forced, cp, 0, cp);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ==================== 任务强制 / 还原 ====================

    private static void forceBuildTask(ServerPlayer player, EntityMaid maid, Session s) {
        var nbt = data(maid);
        String prev = "touhou_little_maid:idle";
        try {
            if (maid.getTask() != null && maid.getTask().getUid() != null) {
                prev = maid.getTask().getUid().toString();
            }
        } catch (Throwable ignored) {
        }
        nbt.putString(TAG_INDEX_PREV_TASK, prev);
        nbt.putString(TAG_INDEX_OWNER, player.getUUID().toString());
        nbt.putBoolean(TAG_INDEX_PREV_HOME, maid.isHomeModeEnable());
        try {
            nbt.putString(TAG_INDEX_PREV_SCHEDULE,
                    maid.getSchedule() == null ? "" : maid.getSchedule().name());
        } catch (Throwable ignored) {
        }
        nbt.putLong(TAG_INDEX_TOTAL, s.cells.size());
        nbt.putBoolean(TAG_INDEX_ACTIVE, true);
        var task = com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager
                .findTask(TASK_UID).orElse(null);
        if (task != null) {
            com.maidsmart.schedule.ScheduleSwitchGuard.runInternal(
                    maid.getUUID(), TASK_UID, () -> maid.setTask(task));
        }
        com.maidsmart.tool.PromaidLog.log("指标石", name(maid)
                + " 临时建造开始：" + prev + " -> " + TASK_UID + " 格数=" + s.cells.size());
    }

    /** 释放女仆：还原原任务/作息 + 清标记（completed = 正常完成，会播报） */
    public static void releaseMaid(ServerPlayer player, EntityMaid maid, boolean completed) {
        var nbt = data(maid);
        if (!nbt.getBoolean(TAG_INDEX_ACTIVE)) {
            return;
        }
        int total = (int) nbt.getLong(TAG_INDEX_TOTAL);
        String prevUid = nbt.getString(TAG_INDEX_PREV_TASK);
        // 先快照再清标记——否则还原 home/作息时读到的是已删除的默认值
        boolean prevHome = nbt.getBoolean(TAG_INDEX_PREV_HOME);
        String prevSchedule = nbt.getString(TAG_INDEX_PREV_SCHEDULE);
        nbt.remove(TAG_INDEX_ACTIVE);
        nbt.remove(TAG_INDEX_OWNER);
        nbt.remove(TAG_INDEX_PREV_TASK);
        nbt.remove(TAG_INDEX_PREV_HOME);
        nbt.remove(TAG_INDEX_PREV_SCHEDULE);
        nbt.remove(TAG_INDEX_TOTAL);
        MAID_TO_PLAYER.remove(maid.getUUID());
        try {
            var prev = com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager
                    .findTask(ResourceLocation.parse(prevUid)).orElse(null);
            if (prev == null) {
                prev = com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager
                        .findTask(ResourceLocation.parse("touhou_little_maid:idle")).orElse(null);
            }
            if (prev != null) {
                var target = prev;
                com.maidsmart.schedule.ScheduleSwitchGuard.runInternal(
                        maid.getUUID(), target.getUid(), () -> maid.setTask(target));
            }
        } catch (Throwable ignored) {
        }
        try {
            if (!com.maidsmart.schedule.ScheduleData.isOn(maid)) {
                maid.setHomeModeEnable(prevHome);
                if (prevSchedule != null && !prevSchedule.isEmpty()) {
                    for (var ms : com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule.values()) {
                        if (ms.name().equals(prevSchedule)) {
                            maid.setSchedule(ms);
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (completed && player != null) {
            msg(player, "\u00a7a【指标石】" + name(maid) + " 搭好了，共 " + total
                    + " 个方块。你可以开始下一个了。");
        }
        com.maidsmart.tool.PromaidLog.log("指标石", name(maid)
                + (completed ? " 临时建造完成，还原为 " : " 临时建造取消，还原为 ") + prevUid);
    }

    /** 女仆完成本次临时建造（由行为在最后一格放完后调用） */
    public static void onMaidFinished(EntityMaid maid) {
        var nbt = data(maid);
        if (!nbt.getBoolean(TAG_INDEX_ACTIVE)) {
            return;
        }
        UUID playerId = null;
        try {
            playerId = UUID.fromString(nbt.getString(TAG_INDEX_OWNER));
        } catch (Throwable ignored) {
        }
        ServerPlayer player = playerId == null ? null : findPlayer(playerId);
        Session s = playerId == null ? null : SESSIONS.get(playerId);
        if (s != null && s.maidId != null && s.maidId.equals(maid.getUUID())) {
            // 完成 = 会话结束并可立刻开下一个：清幽灵格 + 解绑 + 取消锁定框
            s.done = true;
            s.maidId = null;
            s.cells = new ArrayList<>();
            s.lockedBlock = null;
            s.level = null;
            releaseTickets(s);
        }
        // 无论会话是否还在，都要把她从临时建造任务里解除（否则一旦会话先消失，
        // 她会永久卡在"临时搭建"任务上）
        MAID_TO_PLAYER.remove(maid.getUUID());
        releaseMaid(player, maid, true);
        if (player != null) {
            IndexStoneNetworking.syncTo(player); // 推空状态 → 客户端红框/橙影立刻消失
        }
    }

    /**
     * v1.2.0：中途失败/中断收尾（缺材料 / 女仆被收回 / 女仆死亡）——**视作结束和初始化**：
     * 清橙色幽灵格 + 解除绑定 + **取消锁定框** + 释放强制加载票 + 还原女仆任务 + 系统提示。
     * 观感就是"搭了一半 → 报告 → 烂尾 → 结束"（消息如实报"已搭 X / Y 块"）。
     * 幂等：同一会话多处触发（事件 + 行为）只结算一次。
     *
     * @param releaseMaidTask 女仆已被永久移除/收进魂符时传 false（再 setTask 没意义）
     */
    public static void failSession(EntityMaid maid, String reason, boolean releaseMaidTask) {
        if (maid == null) {
            return;
        }
        var nbt = data(maid);
        if (!nbt.getBoolean(TAG_INDEX_ACTIVE)) {
            return; // 已结算过（幂等）
        }
        UUID playerId = null;
        try {
            playerId = UUID.fromString(nbt.getString(TAG_INDEX_OWNER));
        } catch (Throwable ignored) {
        }
        ServerPlayer player = playerId == null ? null : findPlayer(playerId);
        int total = (int) nbt.getLong(TAG_INDEX_TOTAL);
        Session s = playerId == null ? null : SESSIONS.get(playerId);
        // 会话归属校验：只有"确实是这只女仆的会话"才动会话——她可能是魂符放回来时
        // 带着旧标记，而主人此刻已经开了另一只女仆的新会话，绝不能清掉新会话。
        boolean owns = s != null && s.maidId != null && s.maidId.equals(maid.getUUID());
        int left = owns ? s.cells.size() : 0;
        int doneCells = Math.max(0, total - left);
        if (owns) {
            s.cells = new ArrayList<>();
            s.maidId = null;
            s.lockedBlock = null;
            s.level = null;
            s.done = true;
            releaseTickets(s);
        }
        MAID_TO_PLAYER.remove(maid.getUUID());
        if (releaseMaidTask) {
            releaseMaid(player, maid, false);
        } else {
            clearMaidTags(maid);
        }
        if (player != null && owns) {
            msg(player, "\u00a7c【指标石】" + name(maid) + " 的临时搭建结束了（" + reason
                    + "）——已搭 " + doneCells + " / " + total + " 块，剩下的不搭了。"
                    + "锁定框与橙色幽灵格已清除，可以开始下一个。");
            IndexStoneNetworking.syncTo(player);
        }
        com.maidsmart.tool.PromaidLog.log("指标石", name(maid) + " 临时搭建失败收尾：" + reason
                + "（已搭 " + doneCells + "/" + total + (owns ? "" : "，非本会话所属") + "）");
    }

    /**
     * v1.2.0：女仆自愈——身上带着"临时建造中"标记但**没有对应会话**
     * （魂符收回时 NBT 一起被存进魂符，放回来后标记还在而会话早已结算）。
     * 清标记 + 还原原任务；**绝不触碰主人的任何会话**。
     */
    public static void selfHealStale(EntityMaid maid) {
        if (maid == null) {
            return;
        }
        try {
            var nbt = data(maid);
            if (!nbt.getBoolean(TAG_INDEX_ACTIVE)) {
                return;
            }
            UUID playerId = null;
            try {
                playerId = UUID.fromString(nbt.getString(TAG_INDEX_OWNER));
            } catch (Throwable ignored) {
            }
            ServerPlayer player = playerId == null ? null : findPlayer(playerId);
            Session s = playerId == null ? null : SESSIONS.get(playerId);
            if (s != null && s.maidId != null && s.maidId.equals(maid.getUUID())) {
                return; // 会话还在且就是她 → 正常建造中
            }
            releaseMaid(player, maid, false);
            com.maidsmart.tool.PromaidLog.log("指标石",
                    name(maid) + " 残留临时建造标记自愈（无对应会话，已还原任务）");
        } catch (Throwable ignored) {
        }
    }

    /** 只清标记（不碰任务）——女仆已被永久移除/收进魂符时用 */
    private static void clearMaidTags(EntityMaid maid) {
        try {
            var nbt = data(maid);
            nbt.remove(TAG_INDEX_ACTIVE);
            nbt.remove(TAG_INDEX_OWNER);
            nbt.remove(TAG_INDEX_PREV_TASK);
            nbt.remove(TAG_INDEX_PREV_HOME);
            nbt.remove(TAG_INDEX_PREV_SCHEDULE);
            nbt.remove(TAG_INDEX_TOTAL);
            MAID_TO_PLAYER.remove(maid.getUUID());
        } catch (Throwable ignored) {
        }
    }

    /**
     * v1.2.0：女仆被**永久移除**（死亡/收回/解雇）→ 结束该次临时搭建。
     * 用 RemovalReason 精确区分（不能用 isRemoved()——区块卸载也非 null，会误判）：
     * KILLED 死亡 / DISCARDED 收回 → 结束；UNLOADED_* 与 CHANGED_DIMENSION → 不结束。
     */
    public static void onMaidLeave(EntityMaid maid) {
        if (maid == null) {
            return;
        }
        try {
            if (!data(maid).getBoolean(TAG_INDEX_ACTIVE)) {
                return;
            }
            net.minecraft.world.entity.Entity.RemovalReason reason = maid.getRemovalReason();
            boolean permanent = reason == net.minecraft.world.entity.Entity.RemovalReason.KILLED
                    || reason == net.minecraft.world.entity.Entity.RemovalReason.DISCARDED;
            if (!permanent) {
                return;
            }
            boolean dead = reason == net.minecraft.world.entity.Entity.RemovalReason.KILLED
                    || !maid.isAlive();
            failSession(maid, dead ? "女仆死亡" : "女仆被收回", false);
        } catch (Throwable ignored) {
        }
    }

    /** v1.2.0：女仆死亡（LivingDeathEvent）→ 结束本次搭建（幂等，与 onMaidLeave 互不重复） */
    public static void onMaidDeath(EntityMaid maid) {
        if (maid == null) {
            return;
        }
        try {
            if (!data(maid).getBoolean(TAG_INDEX_ACTIVE)) {
                return;
            }
            failSession(maid, "女仆死亡", false);
        } catch (Throwable ignored) {
        }
    }

    /**
     * v1.2.0【收回后"建造不停 / 放出来卡住"根因修复】：女仆被收进魂符 → 结束本次
     * 临时搭建，并且**必须连魂符里那份 NBT 快照一起清洗**。
     *
     * 【为什么清活体数据不够】TLM 的 `AbstractStoreMaidItem.storeMaidData` 是
     * 【先给女仆拍 NBT 快照、再 post ToItem 事件】（CFR 反编译实证，1.21.1 与
     * 1.20.1 同一顺序）：
     * ```
     *   CompoundTag tag = new CompoundTag();
     *   maid.saveWithoutId(tag);            // ← 快照此刻已写好
     *   post(new ToItem(maid, stack, tag)); // ← 我们在这里
     * ```
     * 也就是说本方法执行时，魂符里已经躺着一份"任务 = maid_smart:index_build、
     * persistentData 里建造标记 = true"的快照。只清活体 persistentData 改不到它。
     *
     * 放出来时 TLM `maid.load(快照)` 会：
     * ① 读 `MaidTask` → `setTask(index_build)`：隐藏的临时建造任务当场复活，
     *    而此刻会话早已结算 → 她又在"没有会话"的状态下进入临时建造；
     * ② 读回 `NeoForgeData` 里的 `maid_smart_index_build_active=true` → 行为 canUse
     *    通过 → 只能靠 selfHealStale 自愈，而自愈路径会在**行为 tick 内部**调
     *    `setTask` → TLM `refreshBrain` 重建整个 Brain = 行为被换掉（未定义行为，
     *    项目在 `IndexStoneBuildBehavior.finish` 的注释里已明确记录该风险）→
     *    女仆僵在原地（反馈的"再放出来建模被卡掉"）。
     *
     * 修法：把快照里的 `MaidTask` 写回【原任务】、把建造标记整段删掉——放出来就是
     * 一只干净的原任务女仆，行为不会被复活。
     *
     * @param snapshot TLM 即将存进物品的那份女仆 NBT（`event.getData()`，**同一对象引用**，
     *                 改它才有效；不是副本）
     * @param soulSlab 本次 ToItem 是否为"女仆 → 魂符"（相机/胶卷等存女仆物品也共用
     *                 该事件：那种情况只清洗快照、不结束会话——玩家只是拍个照，不该
     *                 把正在进行的搭建判死刑）
     */
    public static void onMaidRecalled(EntityMaid maid, net.minecraft.nbt.CompoundTag snapshot,
                                      boolean soulSlab) {
        if (maid == null) {
            return;
        }
        boolean building = false;
        try {
            building = data(maid).getBoolean(TAG_INDEX_ACTIVE);
        } catch (Throwable ignored) {
        }
        if (soulSlab && building) {
            try {
                // 实体马上就被 TLM discard，所以这里不还原任务、只清标记（原任务改在快照里）
                failSession(maid, "女仆被收回", false);
            } catch (Throwable ignored) {
            }
        }
        sanitizeSnapshot(maid, snapshot);
    }

    /**
     * v1.2.0：清洗魂符快照里的"临时建造"残留（见 {@link #onMaidRecalled} 的根因说明）。
     *
     * 自守护：**只有快照里的任务确实是我们那个隐藏任务时才动**——女仆身上没在临时
     * 建造，或这枚物品存的不是女仆，就一个字段都不碰（绝不改写别人的 NBT）。
     */
    private static void sanitizeSnapshot(EntityMaid maid, net.minecraft.nbt.CompoundTag snapshot) {
        if (snapshot == null) {
            return;
        }
        try {
            if (!TASK_UID.toString().equals(snapshot.getString(TAG_MAID_TASK))) {
                return; // 快照里不是临时建造任务 → 与我们无关
            }
            // 原任务要从快照自己的 persistentData 里读：活体那份已被 clearMaidTags 删掉了
            String prev = "touhou_little_maid:idle";
            boolean hasPd = snapshot.contains(FORGE_DATA_TAG, 10);
            net.minecraft.nbt.CompoundTag pd = hasPd
                    ? snapshot.getCompound(FORGE_DATA_TAG) : null;
            if (pd != null) {
                String p = pd.getString(TAG_INDEX_PREV_TASK);
                if (p != null && !p.isEmpty()) {
                    prev = p;
                }
            }
            snapshot.putString(TAG_MAID_TASK, prev); // ① 任务写回原任务
            if (pd != null) {                        // ② 建造/站桩标记整段删掉
                pd.remove(TAG_INDEX_ACTIVE);
                pd.remove(TAG_INDEX_OWNER);
                pd.remove(TAG_INDEX_PREV_TASK);
                pd.remove(TAG_INDEX_PREV_HOME);
                pd.remove(TAG_INDEX_PREV_SCHEDULE);
                pd.remove(TAG_INDEX_TOTAL);
                pd.remove("maid_smart_work_still"); // 站桩标记（会冻住移动/播报）
                snapshot.put(FORGE_DATA_TAG, pd);
            }
            com.maidsmart.tool.PromaidLog.log("指标石", name(maid)
                    + " 魂符快照残留已清洗：任务还原为 " + prev);
        } catch (Throwable ignored) {
        }
    }

    // ==================== 查询 ====================

    public static boolean isIndexBuilding(EntityMaid maid) {
        return data(maid).getBoolean(TAG_INDEX_ACTIVE);
    }

    public static Session sessionForMaid(EntityMaid maid) {
        UUID pid = MAID_TO_PLAYER.get(maid.getUUID());
        return pid == null ? null : SESSIONS.get(pid);
    }

    /** 玩家离线清理 */
    public static void onPlayerLeave(ServerPlayer player) {
        Session s = SESSIONS.get(player.getUUID());
        if (s == null) {
            return;
        }
        if (s.maidId != null) {
            EntityMaid maid = findMaid(player, s.maidId);
            if (maid != null) {
                releaseMaid(player, maid, false);
            }
            MAID_TO_PLAYER.remove(s.maidId);
        }
        releaseTickets(s);
        SESSIONS.remove(player.getUUID());
    }

    /** 服务器停止清空 */
    public static void clearAll() {
        for (Session s : SESSIONS.values()) {
            releaseTickets(s); // 释放全部强制加载票（防票残留锁区块）
        }
        SESSIONS.clear();
        MAID_TO_PLAYER.clear();
    }

    // ==================== 工具 ====================

    private static EntityMaid findMaid(ServerPlayer player, UUID maidId) {
        if (maidId == null || player == null) {
            return null;
        }
        ServerLevel level = player.level() instanceof ServerLevel sl ? sl : null;
        if (level == null) {
            return null;
        }
        // v1.2.0（2026-09-18）【Sable 兼容】：全世界 AABB → getAllEntities()（超大 AABB 被 Sable 拒查并且每次刷一份堆栈日志）
        for (net.minecraft.world.entity.Entity e : com.maidsmart.tool.EntitySnapshot.of(level)) {
            if (e instanceof EntityMaid m && m.getUUID().equals(maidId)) {
                return m;
            }
        }
        return null;
    }

    private static ServerPlayer findPlayer(UUID playerId) {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return null;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getUUID().equals(playerId)) {
                return p;
            }
        }
        return null;
    }

    private static String name(EntityMaid maid) {
        return maid.getDisplayName() != null ? maid.getDisplayName().getString() : "女仆";
    }

    private static void msg(ServerPlayer player, String text) {
        if (player != null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(text));
        }
    }
}
