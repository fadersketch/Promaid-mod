package com.maidsmart.build;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指标石（Index Stone）——临时蓝图服务端状态机。
 *
 * 玩法契约（与需求逐条对应）：
 * 1. 玩家手持指标石 → 客户端把视线指向的方块渲染成绿色（跟随指针移动）；
 *    右键 → 锁定（渲染变红、不再移动）；**再右键同一个锁定方块** → 取消锁定
 *    （右击别的方块 = 换锁定点；右击空气 = 保持不动）。锁定距离几乎无上限
 *    （客户端 raycast 用 512 格），不可锁空气。
 * 2. 手持指标石右击女仆 → 绑定；再右击同一只 / 右击另一只 → 解绑。必须先锁方块、
 *    再绑女仆；先绑女仆会被拒绝并提示。
 * 3. 两件事齐备 → 从【女仆当时所在方块】到【锁定的红色方块】之间所有空气方块
 *    被客户端渲染成橙色幽灵方块（与蓝图投影同款），女仆立刻进入一次性临时建造，
 *    从自己所在格逐步向锁定格填充。
 * 4. 材料无限制：优先从女仆背包取，不够从主人背包取；选材规则复用搭路
 *    （MaidBuildBlockFilter.takeBuildBlock：数量最多者优先、必须有碰撞、排除
 *     下落方块/TNT/危险方块）。
 * 5. 建造表现与建造任务完全一致（站桩 + 瞬移到工地旁 + 挥臂 + 放置音效）。
 * 6. 一个玩家同时只能有一个"锁定方块 + 绑定女仆"（任务完成前不能开下一个）；
 *    完成/取消 → 清空该玩家状态、女仆恢复原任务/原作息、发系统消息。
 *
 * 状态存储：会话内存态（ConcurrentHashMap，玩家 UUID 键控）+ 女仆侧持久标记
 * （persistentData，重启/收符后自愈还原，防"卡在临时建造任务"）。这与项目既有
 * BuildPlan（内存 + persistentData 双写）一致。
 */
public final class IndexStoneService {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** 临时建造任务 UID（隐藏任务，不在 TLM 任务面板显示） */
    public static final ResourceLocation TASK_UID = ResourceLocation.parse("maid_smart:index_build");

    /** 女仆 persistentData：本系统指派的临时建造任务标记（完成/取消时清除） */
    public static final String TAG_INDEX_ACTIVE = "maid_smart_index_build_active";
    /** 女仆 persistentData：执行本次临时建造的玩家 UUID */
    public static final String TAG_INDEX_OWNER = "maid_smart_index_build_player";
    /** 女仆 persistentData：临时建造前原任务 UID（还原用） */
    public static final String TAG_INDEX_PREV_TASK = "maid_smart_index_build_prev_task";
    /** 女仆 persistentData：原 home 模式 */
    public static final String TAG_INDEX_PREV_HOME = "maid_smart_index_build_prev_home";
    /** 女仆 persistentData：原作息名（可空） */
    public static final String TAG_INDEX_PREV_SCHEDULE = "maid_smart_index_build_prev_schedule";
    /** 女仆 persistentData：本次临时建造的格数（完成播报用） */
    public static final String TAG_INDEX_TOTAL = "maid_smart_index_build_total";

    /**
     * TLM 存女仆任务用的 NBT 键字面值（EntityMaid 私有常量 TASK_TAG）。
     * CFR 反编译实证：1.20.1 与 1.21.1 都是 "MaidTask"。
     * 收回魂符时要把快照里的它还原成原任务，否则放出来立刻回到临时建造。
     */
    private static final String TAG_MAID_TASK = "MaidTask";
    /** Forge 存 persistentData 的子标签名（1.21.1 侧是 NeoForgeData） */
    private static final String FORGE_DATA_TAG = "ForgeData";

    /** 锁定距离几乎无上限（客户端 raycast 用）；服务端只做合法性校验 */
    public static final double LOCK_RANGE = 512.0;
    /** 连线填充上限（防玩家把距离拉爆导致单次几万格——超出只截断并按截断后执行） */
    public static final int MAX_CELLS = 8192;

    private IndexStoneService() {
    }

    /** 总开关（配置；关掉后指标石退化为普通物品，不锁不绑不建） */
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

    /** 玩家会话状态：锁定的方块（红框）+ 绑定的女仆 + 已展开的临时蓝图格 */
    public static final class Session {
        public BlockPos lockedBlock;
        public UUID maidId;
        public ServerLevel level;
        /** 相对女仆起点的填充格（x,y,z 三元组，按"离起点近→远"排序） */
        public List<int[]> cells = new ArrayList<>();
        /** 已完成（用于幂等收尾） */
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

    /** 女仆 UUID → 执行本次临时建造的玩家 UUID（反向索引，女仆 tick 查会话用） */
    private static final Map<UUID, UUID> MAID_TO_PLAYER = new ConcurrentHashMap<>();

    // ==================== 方块锁定 ====================

    /**
     * 锁定/解锁切换。返回 true = 已锁定，false = 已解锁/被拒。
     * 校验：不可锁空气；已有未完成任务时不允许改锁（契约 6：任务完成前不能开下一个）。
     * pos == null 表示"解锁"。
     *
     * v1.2.0 实测四百八十五【锁定期间不可改选】：锁定的语义就是"期间只认这一格"——
     * 右击别的方块一律无效（要保持锁定、不换点），必须先右击锁定方块解锁。
     * 这里做服务端兜底（客户端钩子已拦截，防伪造包 / 其它入口绕过）。
     */
    public static boolean toggleLock(ServerPlayer player, BlockPos pos) {
        Session s = sessionOrCreate(player.m_20148_());
        if (pos == null) {
            // 解锁：女仆还在干且未完成 → 拒绝（契约：必须完成才能搞下一个）
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
        ServerLevel level = player.m_9236_() instanceof ServerLevel sl ? sl : null;
        if (level == null || !level.m_46749_(pos)) {
            return s.isLocked();
        }
        BlockState st = level.m_8055_(pos);
        if (st.m_60795_()) {
            // 不可锁空气
            msg(player, "\u00a7c指标石：不能锁定空气，请对准一个方块。");
            return s.isLocked();
        }
        s.lockedBlock = pos.m_7949_(); // immutable（SRG m_7949_）
        s.level = level;
        return true;
    }

    /** 是否有"绑定中且未完成"的任务（此时不允许换锁定点/换女仆/开下一个） */
    private static boolean busy(Session s) {
        return s.maidId != null;
    }

    // ==================== 女仆绑定 ====================

    /**
     * 右击女仆：绑定 / 解绑 / 换绑。
     * 契约：必须先锁方块再绑女仆；先绑女仆 → 提示不行；
     * 再右击同一只 = 解绑；右击另一只 = 先把旧的解绑再绑新的（需已锁方块）。
     */
    public static void bindOrUnbind(ServerPlayer player, EntityMaid maid) {
        Session s = sessionOrCreate(player.m_20148_());
        if (!maid.m_21830_(player)) {
            msg(player, "\u00a7c指标石：这不是你的女仆。");
            return;
        }
        // 已绑定同一只 → 解绑（契约：再次右击解绑）
        if (maid.m_20148_().equals(s.maidId)) {
            unbindMaid(player, s);
            msg(player, "\u00a76指标石：已解除绑定（锁定框保留，可重新绑定）。");
            return;
        }
        // 未锁方块 → 拒绝（契约：先绑女仆会提示不行）
        if (!s.isLocked()) {
            msg(player, "\u00a7c指标石：要先用它锁定一个方块（右键方块，绿→红），再来绑定我。");
            return;
        }
        // 已绑定另一只 → 先把旧的解绑还原，再绑新的（契约：点其它女仆解绑）
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
        // 绑定时先算蓝图（女仆起点 → 锁定方块之间的空气格）
        if (!buildCells(maid, s)) {
            msg(player, "\u00a7c指标石：这两点之间没有可填充的空气方块（或已被占满）。");
            return;
        }
        s.maidId = maid.m_20148_();
        MAID_TO_PLAYER.put(maid.m_20148_(), player.m_20148_());
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

    /**
     * 女仆起点 → 锁定方块之间【所有空气方块】的格集合（契约 3）。
     * 几何与客户端幽灵渲染共用 IndexStonePlan.airCells（保证"看到什么就填什么"）。
     */
    private static boolean buildCells(EntityMaid maid, Session s) {
        ServerLevel level = s.level;
        if (level == null || s.lockedBlock == null) {
            return false;
        }
        BlockPos start = maid.m_20183_(); // 女仆所在方块
        List<int[]> cells = IndexStonePlan.airCells(level, start, s.lockedBlock, MAX_CELLS);
        if (cells.isEmpty()) {
            return false;
        }
        s.cells = cells;
        s.done = false;
        // 强制加载：锁定距离可到 512 格，中间区块未加载就放不了（对齐常规建造
        // "整个蓝图区域挂 FORCED 票据，远端也同时建造"的行为）
        forceLoadSpan(level, s, start, s.lockedBlock);
        return true;
    }

    /**
     * 给起点→锁定点之间的区块挂 FORCED 票。上限保护：跨度超过配置的强制加载区块
     * 上限就不挂（只建已加载部分）——与 BuildPlan 超大区域语义一致，绝不因超远
     * 锁定拖垮服务器。
     */
    private static void forceLoadSpan(ServerLevel level, Session s, BlockPos a, BlockPos b) {
        try {
            int minCx = Math.min(a.m_123341_(), b.m_123341_()) >> 4;
            int maxCx = Math.max(a.m_123341_(), b.m_123341_()) >> 4;
            int minCz = Math.min(a.m_123343_(), b.m_123343_()) >> 4;
            int maxCz = Math.max(a.m_123343_(), b.m_123343_()) >> 4;
            if ((long) (maxCx - minCx + 1) * (maxCz - minCz + 1)
                    > com.maidsmart.config.MaidSmartConfig.BUILD_MAX_FORCE_CHUNKS.get()) {
                return; // 跨度太大 → 不挂票
            }
            net.minecraft.server.level.TicketType<net.minecraft.world.level.ChunkPos> forced =
                    net.minecraft.server.level.TicketType.f_9445_; // FORCED
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    net.minecraft.world.level.ChunkPos cp =
                            new net.minecraft.world.level.ChunkPos(cx, cz);
                    level.m_7726_().m_8387_(forced, cp, 0, cp); // addRegionTicket
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
                    net.minecraft.server.level.TicketType.f_9445_; // FORCED
            for (int cx = box[0]; cx <= box[1]; cx++) {
                for (int cz = box[2]; cz <= box[3]; cz++) {
                    net.minecraft.world.level.ChunkPos cp =
                            new net.minecraft.world.level.ChunkPos(cx, cz);
                    level.m_7726_().m_8438_(forced, cp, 0, cp); // removeRegionTicket
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ==================== 任务强制 / 还原 ====================

    /** 把女仆切进临时建造任务（记录原任务/作息以便还原） */
    private static void forceBuildTask(ServerPlayer player, EntityMaid maid, Session s) {
        var nbt = maid.getPersistentData();
        // 记录原任务（resolvePrevTaskUid 同款语义：idle 视为待机）
        String prev = "touhou_little_maid:idle";
        try {
            if (maid.getTask() != null && maid.getTask().getUid() != null) {
                prev = maid.getTask().getUid().toString();
            }
        } catch (Throwable ignored) {
        }
        nbt.m_128359_(TAG_INDEX_PREV_TASK, prev);
        nbt.m_128359_(TAG_INDEX_OWNER, player.m_20148_().toString());
        nbt.m_128379_(TAG_INDEX_PREV_HOME, maid.isHomeModeEnable());
        try {
            nbt.m_128359_(TAG_INDEX_PREV_SCHEDULE,
                    maid.getSchedule() == null ? "" : maid.getSchedule().name());
        } catch (Throwable ignored) {
        }
        nbt.m_128356_(TAG_INDEX_TOTAL, s.cells.size());
        nbt.m_128379_(TAG_INDEX_ACTIVE, true);
        var task = com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager
                .findTask(TASK_UID).orElse(null);
        if (task != null) {
            com.maidsmart.schedule.ScheduleSwitchGuard.runInternal(
                    maid.m_20148_(), TASK_UID, () -> maid.setTask(task));
        }
        com.maidsmart.tool.PromaidLog.log("指标石", name(maid)
                + " 临时建造开始：" + prev + " -> " + TASK_UID + " 格数=" + s.cells.size());
    }

    /**
     * 释放女仆：还原原任务/作息 + 清全部标记。
     * @param completed true = 正常完成（播报"建好了"），false = 取消/解绑
     */
    public static void releaseMaid(ServerPlayer player, EntityMaid maid, boolean completed) {
        var nbt = maid.getPersistentData();
        if (!nbt.m_128471_(TAG_INDEX_ACTIVE)) {
            return;
        }
        int total = (int) nbt.m_128454_(TAG_INDEX_TOTAL); // 只写 getLong/putLong（无 putInt，SRG 实证）
        String prevUid = nbt.m_128461_(TAG_INDEX_PREV_TASK);
        // 先快照再清标记——否则还原 home/作息时读到的是已删除的默认值（永远还原错）
        boolean prevHome = nbt.m_128471_(TAG_INDEX_PREV_HOME);
        String prevSchedule = nbt.m_128461_(TAG_INDEX_PREV_SCHEDULE);
        nbt.m_128473_(TAG_INDEX_ACTIVE);
        nbt.m_128473_(TAG_INDEX_OWNER);
        nbt.m_128473_(TAG_INDEX_PREV_TASK);
        nbt.m_128473_(TAG_INDEX_PREV_HOME);
        nbt.m_128473_(TAG_INDEX_PREV_SCHEDULE);
        nbt.m_128473_(TAG_INDEX_TOTAL);
        MAID_TO_PLAYER.remove(maid.m_20148_());
        // 还原任务
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
                        maid.m_20148_(), target.getUid(), () -> maid.setTask(target));
            }
        } catch (Throwable ignored) {
        }
        // 还原 home/作息（排班关闭时；排班开启交给排班接管）
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
        var nbt = maid.getPersistentData();
        if (!nbt.m_128471_(TAG_INDEX_ACTIVE)) {
            return;
        }
        UUID playerId = null;
        try {
            playerId = UUID.fromString(nbt.m_128461_(TAG_INDEX_OWNER));
        } catch (Throwable ignored) {
        }
        ServerPlayer player = playerId == null ? null : findPlayer(playerId);
        Session s = playerId == null ? null : SESSIONS.get(playerId);
        if (s != null && s.maidId != null && s.maidId.equals(maid.m_20148_())) {
            // 完成 = 会话结束并可立刻开下一个：清幽灵格 + 解绑 + 取消锁定框
            s.done = true;
            s.maidId = null;
            s.cells = new ArrayList<>();
            s.lockedBlock = null;
            s.level = null;
            releaseTickets(s); // 释放强制加载票
        }
        // 无论会话是否还在，都要把女仆从临时建造任务里解除（释放标记 + 还原原任务/作息）
        // ——否则一旦会话先一步消失，她会永久卡在"临时搭建"任务上
        MAID_TO_PLAYER.remove(maid.m_20148_());
        releaseMaid(player, maid, true);
        if (player != null) {
            IndexStoneNetworking.syncTo(player); // 推空状态 → 客户端红框/橙影立刻消失
        }
    }

    /**
     * v1.2.0：中途失败/中断收尾（缺材料 / 女仆被收回 / 女仆死亡）——**视作结束和初始化**：
     * 清橙色幽灵格 + 解除绑定 + **取消锁定框** + 释放强制加载票 + 还原女仆任务 + 系统提示。
     * 实际观感就是"搭了一半 → 报告 → 烂尾 → 结束"（消息里如实报"已搭 X / Y 块"）。
     *
     * 幂等：同一会话多处触发（事件 + 周期兜底）只结算一次。
     *
     * @param reason 失败原因（写进系统消息与日志，如"缺少材料"/"女仆被收回"/"女仆死亡"）
     * @param releaseMaidTask 是否要还原女仆任务——**女仆已被永久移除时传 false**
     *        （收魂符/死亡后实体已 markRemoved，再 setTask 没意义）
     */
    public static void failSession(EntityMaid maid, String reason, boolean releaseMaidTask) {
        if (maid == null) {
            return;
        }
        var nbt = maid.getPersistentData();
        if (!nbt.m_128471_(TAG_INDEX_ACTIVE)) {
            return; // 已结算过（幂等：死亡/收回/兜底多处触发只结算一次）
        }
        UUID playerId = null;
        try {
            playerId = UUID.fromString(nbt.m_128461_(TAG_INDEX_OWNER));
        } catch (Throwable ignored) {
        }
        ServerPlayer player = playerId == null ? null : findPlayer(playerId);
        int total = (int) nbt.m_128454_(TAG_INDEX_TOTAL);
        Session s = playerId == null ? null : SESSIONS.get(playerId);
        // 会话归属校验：只有"确实是这只女仆的会话"才动会话。她可能是魂符放回来时
        // 带着旧标记，而主人此刻已经开了【另一只女仆】的新会话——那种情况绝不能
        // 清掉主人的新会话（否则"放回来一个旧女仆，正在干活的新任务没了"）。
        boolean owns = s != null && s.maidId != null && s.maidId.equals(maid.m_20148_());
        int left = owns ? s.cells.size() : 0;
        int doneCells = Math.max(0, total - left);
        if (owns) {
            // 清幽灵格 + 解绑 + 取消锁定框（"初始化"语义）+ 释放强制加载票
            s.cells = new ArrayList<>();
            s.maidId = null;
            s.lockedBlock = null;
            s.level = null;
            s.done = true;
            releaseTickets(s);
        }
        MAID_TO_PLAYER.remove(maid.m_20148_());
        if (releaseMaidTask) {
            releaseMaid(player, maid, false); // 清标记 + 还原原任务/作息
        } else {
            clearMaidTags(maid); // 实体已移除/已收进魂符 → 只清标记，不碰任务
        }
        if (player != null && owns) {
            msg(player, "\u00a7c【指标石】" + name(maid) + " 的临时搭建结束了（" + reason
                    + "）——已搭 " + doneCells + " / " + total + " 块，剩下的不搭了。"
                    + "锁定框与橙色幽灵格已清除，可以开始下一个。");
            IndexStoneNetworking.syncTo(player); // 推空状态 → 客户端红框/橙影立刻消失
        }
        com.maidsmart.tool.PromaidLog.log("指标石", name(maid) + " 临时搭建失败收尾：" + reason
                + "（已搭 " + doneCells + "/" + total + (owns ? "" : "，非本会话所属") + "）");
    }

    /**
     * v1.2.0：女仆自愈——她身上带着本系统的"临时建造中"标记，但**已经没有对应会话**
     * （魂符收回时 NBT 被一起存进魂符，放回来后标记还在，而会话早已结算）。
     * 清标记 + 还原她原来任务；**绝不触碰主人的任何会话**（那可能已属于另一只女仆）。
     */
    public static void selfHealStale(EntityMaid maid) {
        if (maid == null) {
            return;
        }
        try {
            var nbt = maid.getPersistentData();
            if (!nbt.m_128471_(TAG_INDEX_ACTIVE)) {
                return;
            }
            UUID playerId = null;
            try {
                playerId = UUID.fromString(nbt.m_128461_(TAG_INDEX_OWNER));
            } catch (Throwable ignored) {
            }
            ServerPlayer player = playerId == null ? null : findPlayer(playerId);
            Session s = playerId == null ? null : SESSIONS.get(playerId);
            if (s != null && s.maidId != null && s.maidId.equals(maid.m_20148_())) {
                return; // 会话还在且就是她 → 正常建造中，别碰
            }
            releaseMaid(player, maid, false); // 还原原任务/作息 + 清标记
            com.maidsmart.tool.PromaidLog.log("指标石",
                    name(maid) + " 残留临时建造标记自愈（无对应会话，已还原任务）");
        } catch (Throwable ignored) {
        }
    }

    /** 只清本系统的女仆标记（不碰任务）——女仆已被永久移除/收进魂符时用 */
    private static void clearMaidTags(EntityMaid maid) {
        try {
            var nbt = maid.getPersistentData();
            nbt.m_128473_(TAG_INDEX_ACTIVE);
            nbt.m_128473_(TAG_INDEX_OWNER);
            nbt.m_128473_(TAG_INDEX_PREV_TASK);
            nbt.m_128473_(TAG_INDEX_PREV_HOME);
            nbt.m_128473_(TAG_INDEX_PREV_SCHEDULE);
            nbt.m_128473_(TAG_INDEX_TOTAL);
            MAID_TO_PLAYER.remove(maid.m_20148_());
        } catch (Throwable ignored) {
        }
    }

    /**
     * v1.2.0：女仆被**永久移除**（死亡 / 收回 / 解雇）→ 结束该次临时搭建。
     * 由 EntityLeaveLevelEvent 调用（项目既有的"女仆离场"统一清理点）。
     *
     * 用 RemovalReason 精确区分（不能用 isRemoved()——区块卸载的实体 removalReason
     * 也非 null，会把"她走远被卸载"误判成结束，而她其实会回来继续搭）：
     * - KILLED = 死亡      → 结束
     * - DISCARDED = 收回/解雇 → 结束
     * - UNLOADED_TO_CHUNK / UNLOADED_WITH_PLAYER → 不结束（会回来）
     * - CHANGED_DIMENSION → 不结束（跨维照旧）
     */
    public static void onMaidLeave(EntityMaid maid) {
        if (maid == null) {
            return;
        }
        try {
            if (!maid.getPersistentData().m_128471_(TAG_INDEX_ACTIVE)) {
                return;
            }
            net.minecraft.world.entity.Entity.RemovalReason reason = maid.m_146911_();
            boolean permanent = reason == net.minecraft.world.entity.Entity.RemovalReason.KILLED
                    || reason == net.minecraft.world.entity.Entity.RemovalReason.DISCARDED;
            if (!permanent) {
                return; // 卸载/换维度 → 不结束，等她回来继续
            }
            boolean dead = reason == net.minecraft.world.entity.Entity.RemovalReason.KILLED
                    || !maid.m_6084_();
            failSession(maid, dead ? "女仆死亡" : "女仆被收回", false);
        } catch (Throwable ignored) {
        }
    }

    /**
     * v1.2.0：女仆死亡事件（LivingDeathEvent）——EntityLeaveLevelEvent 在死亡时
     * 时机不保证（部分模组先复活再移除），这里直接兜一层：死了就结束本次搭建。
     * 幂等（failSession 自带幂等），两者同时触发也只结算一次。
     */
    public static void onMaidDeath(EntityMaid maid) {
        if (maid == null) {
            return;
        }
        try {
            if (!maid.getPersistentData().m_128471_(TAG_INDEX_ACTIVE)) {
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
     * 【为什么清活体数据不够】TLM 的 `ItemSmartSlab.storeMaidData` 是
     * 【先给女仆拍 NBT 快照、再 post ToItem 事件】（CFR 反编译实证，1.20.1 与
     * 1.21.1 同一顺序）：
     * ```
     *   CompoundTag data = stack.getCompound("MaidInfo");
     *   maid.save(data);                    // ← 快照此刻已写好
     *   post(new ToItem(maid, stack, data)); // ← 我们在这里
     * ```
     * 也就是说本方法执行时，魂符里已经躺着一份"任务 = maid_smart:index_build、
     * persistentData 里建造标记 = true"的快照。只清活体 persistentData 改不到它。
     *
     * 放出来时 TLM `maid.load(快照)`（1.20.1 `m_20258_`）会：
     * ① 读 `MaidTask` → `setTask(index_build)`：隐藏的临时建造任务当场复活，
     *    而此刻会话早已结算 → 她又在"没有会话"的状态下进入临时建造；
     * ② 读回 `ForgeData` 里的 `maid_smart_index_build_active=true` → 行为 canUse
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
            building = maid.getPersistentData().m_128471_(TAG_INDEX_ACTIVE);
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
            if (!TASK_UID.toString().equals(snapshot.m_128461_(TAG_MAID_TASK))) {
                return; // 快照里不是临时建造任务 → 与我们无关
            }
            // 原任务要从快照自己的 persistentData 里读：活体那份已被 clearMaidTags 删掉了
            String prev = "touhou_little_maid:idle";
            boolean hasPd = snapshot.m_128425_(FORGE_DATA_TAG, 10);
            net.minecraft.nbt.CompoundTag pd = hasPd
                    ? snapshot.m_128469_(FORGE_DATA_TAG) : null;
            if (pd != null) {
                String p = pd.m_128461_(TAG_INDEX_PREV_TASK);
                if (p != null && !p.isEmpty()) {
                    prev = p;
                }
            }
            snapshot.m_128359_(TAG_MAID_TASK, prev); // ① 任务写回原任务
            if (pd != null) {                        // ② 建造/站桩标记整段删掉
                pd.m_128473_(TAG_INDEX_ACTIVE);
                pd.m_128473_(TAG_INDEX_OWNER);
                pd.m_128473_(TAG_INDEX_PREV_TASK);
                pd.m_128473_(TAG_INDEX_PREV_HOME);
                pd.m_128473_(TAG_INDEX_PREV_SCHEDULE);
                pd.m_128473_(TAG_INDEX_TOTAL);
                pd.m_128473_("maid_smart_work_still"); // 站桩标记（会冻住移动/播报）
                snapshot.m_128365_(FORGE_DATA_TAG, pd);
            }
            com.maidsmart.tool.PromaidLog.log("指标石", name(maid)
                    + " 魂符快照残留已清洗：任务还原为 " + prev);
        } catch (Throwable ignored) {
        }
    }

    // ==================== 查询 ====================

    /** 女仆是否正在执行指标石临时建造 */
    public static boolean isIndexBuilding(EntityMaid maid) {
        return maid.getPersistentData().m_128471_(TAG_INDEX_ACTIVE);
    }

    /** 该女仆的执行者会话（女仆 tick 取填充格用） */
    public static Session sessionForMaid(EntityMaid maid) {
        UUID pid = MAID_TO_PLAYER.get(maid.m_20148_());
        return pid == null ? null : SESSIONS.get(pid);
    }

    /** 玩家离线清理 */
    public static void onPlayerLeave(ServerPlayer player) {
        Session s = SESSIONS.get(player.m_20148_());
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
        SESSIONS.remove(player.m_20148_());
    }

    /** 服务器停止清空（防跨存档残留） */
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
        ServerLevel level = player.m_9236_() instanceof ServerLevel sl ? sl : null;
        if (level == null) {
            return null;
        }
        for (net.minecraft.world.entity.Entity e : level.m_8583_()) {
            if (e instanceof EntityMaid m && m.m_20148_().equals(maidId)) {
                return m;
            }
        }
        return null;
    }

    private static ServerPlayer findPlayer(UUID playerId) {
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return null;
        }
        for (ServerPlayer p : server.m_6846_().m_11314_()) {
            if (p.m_20148_().equals(playerId)) {
                return p;
            }
        }
        return null;
    }

    private static String name(EntityMaid maid) {
        return maid.m_5446_() != null ? maid.m_5446_().getString() : "女仆";
    }

    private static void msg(ServerPlayer player, String text) {
        if (player != null) {
            player.m_213846_(net.minecraft.network.chat.Component.m_237113_(text));
        }
    }
}
