# -*- coding: utf-8 -*-
# 实测六十九：伐木/挖矿"站坑发呆要收回魂符重放才恢复"——发呆看门狗
# 1. 两行为各加"零进展看门狗"：连续 N 秒既没挖掉任何方块、位置也没挪动（原地发呆/
#    内部状态死循环）→ 整体重置该女仆的全部行为状态（静态表 forget + 实例字段），
#    等效收回魂符再放下去（重放正是靠换实体 ID 让静态表失效治好卡死的），不用玩家手动救。
#    走路赶路（位置在动）与垫方块都算进展，不误触发；开关+秒数进配置面板（玩家可配置原则）。
# 2. 顺带修两处会喂出"发呆"的漏洞：
#    a) 关闭透视时被挡目标"丢弃但不记录"→ 扫描层几 tick 后又选中同一块，无限循环；
#       改为进 RECENT_DISCARD 30 秒短排。
#    b) 伐木抬头兜底 firstWoodAbove 不再回选已被硬挡路弃置（blockedWoods）的木材。
import io
import sys

BASE = r"C:\Users\Sketch\.zcode\workspace\default\promaid-mod"
sys.stdout.reconfigure(encoding="utf-8")


def rd(p):
    return io.open(BASE + "\\" + p, encoding="utf-8").read()


def wr(p, s):
    io.open(BASE + "\\" + p, "w", encoding="utf-8").write(s)


def rep(s, old, new, tag, path, cnt=1):
    n = s.count(old)
    if n != cnt:
        print("FAIL[%s/%s] hits=%d expect=%d : %s" % (path, tag, n, cnt, ascii(old[:90])))
        sys.exit(1)
    return s.replace(old, new)


# ==================== A. MaidSmartConfig.java ====================
CF = r"promaid_src\com\maidsmart\config\MaidSmartConfig.java"
t = rd(CF)

t = rep(t, (
    "    public static final ForgeConfigSpec.IntValue WOOD_SCAN_BUDGET;\n"
    "    public static final ForgeConfigSpec.IntValue MINE_SCAN_BUDGET;\n"
), (
    "    public static final ForgeConfigSpec.IntValue WOOD_SCAN_BUDGET;\n"
    "    public static final ForgeConfigSpec.IntValue MINE_SCAN_BUDGET;\n"
    "    /** v1.1.0 实测六十九：发呆看门狗——零进展且原地不动超时自动重置状态 */\n"
    "    public static final ForgeConfigSpec.BooleanValue WOOD_STUCK_WATCHDOG;\n"
    "    public static final ForgeConfigSpec.IntValue WOOD_STUCK_RESET_SECONDS;\n"
    "    public static final ForgeConfigSpec.BooleanValue MINE_STUCK_WATCHDOG;\n"
    "    public static final ForgeConfigSpec.IntValue MINE_STUCK_RESET_SECONDS;\n"
), "decl", CF)

t = rep(t, (
    "                .translation(\"config.promaid.mine.chainLimit\").defineInRange(\"chainLimit\", 16, 4, 64);\n"
    "        BUILDER.pop();\n"
), (
    "                .translation(\"config.promaid.mine.chainLimit\").defineInRange(\"chainLimit\", 16, 4, 64);\n"
    "        // v1.1.0 实测六十九：发呆看门狗——零进展且原地不动超时自动重置状态\n"
    "        MINE_STUCK_WATCHDOG = BUILDER.comment(\"发呆看门狗（默认开）：挖矿期间连续 N 秒既没挖掉任何方块、位置也没挪动（原地发呆/内部状态卡死）时，自动整体重置该女仆的挖矿状态——锚点/扫描缓存/排除表/目标全部清空重新开始，等效收回魂符再放下去，不用玩家手动救；走路赶路、垫方块搭路都算进展，不会误触发\")\n"
    "                .translation(\"config.promaid.mine.stuckWatchdog\").define(\"stuckWatchdog\", true);\n"
    "        MINE_STUCK_RESET_SECONDS = BUILDER.comment(\"看门狗判定时长（秒，默认 45）：连续这么久零进展且原地不动才触发重置。调小救得更快但可能误伤原地啃超硬方块的正常耗时，调大更宽容\")\n"
    "                .translation(\"config.promaid.mine.stuckResetSeconds\").defineInRange(\"stuckResetSeconds\", 45, 10, 300);\n"
    "        BUILDER.pop();\n"
), "mine-def", CF)

t = rep(t, (
    "                .translation(\"config.promaid.wood.leavesClear\").define(\"leavesClear\", true);\n"
    "        BUILDER.pop();\n"
), (
    "                .translation(\"config.promaid.wood.leavesClear\").define(\"leavesClear\", true);\n"
    "        // v1.1.0 实测六十九：发呆看门狗——零进展且原地不动超时自动重置状态\n"
    "        WOOD_STUCK_WATCHDOG = BUILDER.comment(\"发呆看门狗（默认开）：伐木期间连续 N 秒既没砍掉任何方块、位置也没挪动（典型如站进挖掉的树洞里对着头顶树干发呆）时，自动整体重置该女仆的伐木状态——锚点/扫描缓存/排除表/目标全部清空重新找树，等效收回魂符再放下去，不用玩家手动救；走路赶路、垫方块搭高都算进展，不会误触发\")\n"
    "                .translation(\"config.promaid.wood.stuckWatchdog\").define(\"stuckWatchdog\", true);\n"
    "        WOOD_STUCK_RESET_SECONDS = BUILDER.comment(\"看门狗判定时长（秒，默认 30）：连续这么久零进展且原地不动才触发重置。调小救得更快但可能误伤原地啃硬木头的正常耗时，调大更宽容\")\n"
    "                .translation(\"config.promaid.wood.stuckResetSeconds\").defineInRange(\"stuckResetSeconds\", 30, 10, 300);\n"
    "        BUILDER.pop();\n"
), "wood-def", CF)

wr(CF, t)
print("config OK")

# ==================== B. MaidWoodBehavior.java ====================
WD = r"promaid_src\com\maidsmart\task\MaidWoodBehavior.java"
w = rd(WD)

# B1 看门狗静态表 + markProgress 助手
w = rep(w, (
    "    /** v1.1.0 实测五：树苗补种限频（实体 ID → 上次种植 tick，5 秒一次防连种） */\n"
    "    private static final Map<Integer, Long> SAPLING_PLANT_SINCE = new HashMap<>();\n"
), (
    "    /** v1.1.0 实测五：树苗补种限频（实体 ID → 上次种植 tick，5 秒一次防连种） */\n"
    "    private static final Map<Integer, Long> SAPLING_PLANT_SINCE = new HashMap<>();\n"
    "    /** v1.1.0 实测六十九：发呆看门狗——最近一次\"真实进展\"时刻（挖掉/垫了方块）。长时间零进展\n"
    "     *  且原地不动 = 发呆/死循环，自动整体重置该女仆的全部行为状态（等效收回魂符再放下去——\n"
    "     *  收放正是靠换实体 ID 让这些以实体 ID 为键的表失效来治好卡死的）。 */\n"
    "    private static final Map<Integer, Long> LAST_PROGRESS = new HashMap<>();\n"
    "    /** 看门狗窗口采样（实体 ID → [窗口起点 tick, 起点 x/y/z 各自的 double 位模式]） */\n"
    "    private static final Map<Integer, long[]> WATCH_SAMPLE = new HashMap<>();\n"
    "    /** 看门狗重置播报限频（实体 ID → 上次播报 tick） */\n"
    "    private static final Map<Integer, Long> RESET_REPORT_SINCE = new HashMap<>();\n"
    "\n"
    "    /** v1.1.0 实测六十九：登记一次真实进展（挖掉/垫了方块）——给看门狗续命 */\n"
    "    private static void markProgress(EntityMaid maid, long gameTime) {\n"
    "        LAST_PROGRESS.put(maid.m_19879_(), gameTime);\n"
    "    }\n"
), "maps", WD)

# B2 forget() 一并清理看门狗表
w = rep(w, (
    "        WOOD_SCANS.remove(maidEntityId); // 实测六十一：分帧扫描游标一并清\n"
    "    }\n"
), (
    "        WOOD_SCANS.remove(maidEntityId); // 实测六十一：分帧扫描游标一并清\n"
    "        LAST_PROGRESS.remove(maidEntityId); // 实测六十九：看门狗三表一并清\n"
    "        WATCH_SAMPLE.remove(maidEntityId);\n"
    "        RESET_REPORT_SINCE.remove(maidEntityId);\n"
    "    }\n"
), "forget", WD)

# B3 purgeStaleMaids 并集 + removeIf
w = rep(w, "            ids.addAll(WOOD_CACHE.keySet());\n",
        "            ids.addAll(WOOD_CACHE.keySet());\n"
        "            ids.addAll(LAST_PROGRESS.keySet()); // 实测六十九\n"
        "            ids.addAll(WATCH_SAMPLE.keySet());\n"
        "            ids.addAll(RESET_REPORT_SINCE.keySet());\n", "purge-u", WD)
w = rep(w, (
    "            WOOD_CACHE.keySet().removeIf(id -> !alive.contains(id));\n"
    "            WOOD_SCANS.keySet().removeIf(id -> !alive.contains(id)); // 实测六十一\n"
), (
    "            WOOD_CACHE.keySet().removeIf(id -> !alive.contains(id));\n"
    "            WOOD_SCANS.keySet().removeIf(id -> !alive.contains(id)); // 实测六十一\n"
    "            LAST_PROGRESS.keySet().removeIf(id -> !alive.contains(id)); // 实测六十九\n"
    "            WATCH_SAMPLE.keySet().removeIf(id -> !alive.contains(id));\n"
    "            RESET_REPORT_SINCE.keySet().removeIf(id -> !alive.contains(id));\n"
), "purge-r", WD)

# B4 doTick 头部挂看门狗
w = rep(w, (
    "        // 拦不住横向卡入，这是最后一道保险：宁可瞬移半步也不被自己搭的方块闷住\n"
    "        this.antiSuffocate(maid);\n"
), (
    "        // 拦不住横向卡入，这是最后一道保险：宁可瞬移半步也不被自己搭的方块闷住\n"
    "        this.antiSuffocate(maid);\n"
    "        // v1.1.0 实测六十九：发呆看门狗——长时间零进展且原地不动时整体重置状态。\n"
    "        // 用户反馈：站坑发呆要收回魂符重放才恢复（重放换实体 ID 清空全部静态表）；\n"
    "        // 现在行为自己周期性做同款复位，不再需要玩家手动救\n"
    "        if (com.maidsmart.config.MaidSmartConfig.WOOD_STUCK_WATCHDOG.get()\n"
    "                && this.stuckReset(level, maid, gameTime)) {\n"
    "            return; // 本 tick 已重置，下 tick 从头评估\n"
    "        }\n"
), "tick", WD)

# B5 看门狗方法体（插在 approachWood 的 javadoc 前）
w = rep(w, (
    "    /**\n"
    "     * v1.5.25 搭路决策：向上搭 vs 向前搭 的判定链。\n"
), (
    "    /** v1.1.0 实测六十九：窗口起点采样 */\n"
    "    private static long[] watchSample(long gameTime, EntityMaid maid) {\n"
    "        return new long[]{gameTime,\n"
    "                Double.doubleToLongBits(maid.m_20185_()),\n"
    "                Double.doubleToLongBits(maid.m_20186_()),\n"
    "                Double.doubleToLongBits(maid.m_20189_())};\n"
    "    }\n"
    "\n"
    "    /**\n"
    "     * v1.1.0 实测六十九：发呆看门狗判定。每 tick 廉价检查；窗口到期（默认 30 秒）结算：\n"
    "     * 期间【挪动过 ≥1.5 格】或【有过真实进展（挖掉/垫方块）】= 正常工作，续窗；否则判发呆，\n"
    "     * 整体重置该女仆在本行为里的全部状态并返回 true。走路赶路位置一直在变，不会误触发。\n"
    "     */\n"
    "    private boolean stuckReset(ServerLevel level, EntityMaid maid, long gameTime) {\n"
    "        int id = maid.m_19879_();\n"
    "        long window = com.maidsmart.config.MaidSmartConfig.WOOD_STUCK_RESET_SECONDS.get() * 20L;\n"
    "        if (window <= 0) {\n"
    "            return false;\n"
    "        }\n"
    "        long[] sample = WATCH_SAMPLE.get(id);\n"
    "        if (sample == null) {\n"
    "            WATCH_SAMPLE.put(id, watchSample(gameTime, maid));\n"
    "            LAST_PROGRESS.putIfAbsent(id, gameTime);\n"
    "            return false;\n"
    "        }\n"
    "        if (gameTime - sample[0] < window) {\n"
    "            return false; // 窗口未到期\n"
    "        }\n"
    "        double moved = Math.sqrt(\n"
    "                Math.pow(maid.m_20185_() - Double.longBitsToDouble(sample[1]), 2)\n"
    "                        + Math.pow(maid.m_20186_() - Double.longBitsToDouble(sample[2]), 2)\n"
    "                        + Math.pow(maid.m_20189_() - Double.longBitsToDouble(sample[3]), 2));\n"
    "        Long lastProg = LAST_PROGRESS.get(id);\n"
    "        if (moved >= 1.5 || (lastProg != null && lastProg >= sample[0])) {\n"
    "            WATCH_SAMPLE.put(id, watchSample(gameTime, maid)); // 有在干活：续窗\n"
    "            return false;\n"
    "        }\n"
    "        this.hardResetStuck(level, maid, gameTime);\n"
    "        return true;\n"
    "    }\n"
    "\n"
    "    /**\n"
    "     * v1.1.0 实测六十九：发呆整体重置——forget 清空本女仆的全部静态表（锚点/缓存/\n"
    "     * 扫描游标/弃置排除/各类限频），实例字段（目标/进度/连锁队列/挡路名单/走路记忆等）\n"
    "     * 一并归零，等效收回魂符再放下去；气泡播报一次（60 秒限频）。\n"
    "     */\n"
    "    private void hardResetStuck(ServerLevel level, EntityMaid maid, long gameTime) {\n"
    "        int id = maid.m_19879_();\n"
    "        long idleTicks = gameTime - LAST_PROGRESS.getOrDefault(id, gameTime);\n"
    "        forget(id);\n"
    "        this.targetPos = null;\n"
    "        this.destroyProgress = 0.0f;\n"
    "        this.saveProgressNow(maid);\n"
    "        this.abandonedPos = null;\n"
    "        this.blockedWoods.clear();\n"
    "        this.skippedWoodPos = null;\n"
    "        this.skippedWoodName = null;\n"
    "        this.skippedWoodTool = null;\n"
    "        this.skippedWoodValue = -1;\n"
    "        this.chainQueue.clear();\n"
    "        this.chainBlock = null;\n"
    "        this.lastWalkTarget = null;\n"
    "        this.scanCooldown = 0;\n"
    "        this.pillarCooldown = 0;\n"
    "        this.walkRetargetCooldown = 0;\n"
    "        if (this.lastCrackPos != null) {\n"
    "            this.broadcastCrack(level, maid, this.lastCrackPos, 10); // 清残留挖掘裂纹\n"
    "        }\n"
    "        this.lastCrackPos = null;\n"
    "        this.lastCrackStage = -1;\n"
    "        LAST_PROGRESS.put(id, gameTime);\n"
    "        WATCH_SAMPLE.put(id, watchSample(gameTime, maid));\n"
    "        LOGGER.info(\"wood stuck-reset: maid={} idle={}t pos={}\", id, idleTicks, maid.m_20183_());\n"
    "        Long lastReport = RESET_REPORT_SINCE.get(id);\n"
    "        if (lastReport == null || gameTime - lastReport >= 1200L) {\n"
    "            RESET_REPORT_SINCE.put(id, gameTime);\n"
    "            maid.getChatBubbleManager().addTextChatBubble(\"好像走神了……我重新理一下思路，继续干活！\");\n"
    "        }\n"
    "    }\n"
    "\n"
    "    /**\n"
    "     * v1.5.25 搭路决策：向上搭 vs 向前搭 的判定链。\n"
), "methods", WD)

# B6 视线被挡的目标进短排（不再无限"选中→看不见→丢弃"）
w = rep(w, (
    "            if (!com.maidsmart.config.MaidSmartConfig.WOOD_SEEK_THROUGH_WALLS.get()\n"
    "                    && !this.hasClearSight(level, maid, this.targetPos)) {\n"
    "                this.targetPos = null;\n"
    "                this.destroyProgress = 0.0f;\n"
    "                this.saveProgress(maid);\n"
    "                return;\n"
    "            }\n"
), (
    "            if (!com.maidsmart.config.MaidSmartConfig.WOOD_SEEK_THROUGH_WALLS.get()\n"
    "                    && !this.hasClearSight(level, maid, this.targetPos)) {\n"
    "                // v1.1.0 实测六十九：视线被挡的目标进 30 秒短排——旧版只丢目标不记录，\n"
    "                // 扫描层几 tick 后又选中同一块，\"选中→看不见→丢弃\"无限循环站桩发呆\n"
    "                RECENT_DISCARD.computeIfAbsent(maid.m_19879_(), k -> new java.util.HashMap<>())\n"
    "                        .put(this.targetPos.m_7949_(), level.m_46467_());\n"
    "                this.targetPos = null;\n"
    "                this.destroyProgress = 0.0f;\n"
    "                this.saveProgress(maid);\n"
    "                return;\n"
    "            }\n"
), "sight", WD)

# B7 firstWoodAbove 不回选硬挡弃置的木材
w = rep(w, (
    "            BlockPos p = feet.m_7918_(0, dy, 0);\n"
    "            if (this.isWood(level, p) && !isWoodingPlaced(level, p)) {\n"
    "                return p;\n"
    "            }\n"
), (
    "            BlockPos p = feet.m_7918_(0, dy, 0);\n"
    "            // v1.1.0 实测六十九：被硬挡路弃置的不回选（否则「抬头选中→硬挡弃置→再抬头」\n"
    "            // 死循环，站在树洞里永远出不来）\n"
    "            if (this.isWood(level, p) && !isWoodingPlaced(level, p)\n"
    "                    && !this.blockedWoods.contains(p)) {\n"
    "                return p;\n"
    "            }\n"
), "above", WD)

# B8 进展喂狗点：trackPlaced / 连锁砍完 / 树叶冲破
w = rep(w, (
    "    private static void trackPlaced(ServerLevel level, BlockPos pos, Block block, EntityMaid maid) {\n"
    "        PLACED_TRACKER.track(level, pos, block, maid);\n"
    "    }\n"
), (
    "    private static void trackPlaced(ServerLevel level, BlockPos pos, Block block, EntityMaid maid) {\n"
    "        PLACED_TRACKER.track(level, pos, block, maid);\n"
    "        markProgress(maid, level.m_46467_()); // 实测六十九：垫方块也是进展\n"
    "    }\n"
), "track", WD)

w = rep(w, "        this.chainBreakAll(level, maid, mainHand);\n",
        "        this.chainBreakAll(level, maid, mainHand);\n"
        "        markProgress(maid, gameTime); // 实测六十九：喂看门狗（真实进展）\n", "chain", WD)

w = rep(w, (
    "            if (broken > 0) {\n"
    "                maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND); // 摆臂动画\n"
    "                LOGGER.info(\"wood leaves burst: maid={} broken={}\", maid.m_20148_(), broken);\n"
    "            }\n"
), (
    "            if (broken > 0) {\n"
    "                maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND); // 摆臂动画\n"
    "                LOGGER.info(\"wood leaves burst: maid={} broken={}\", maid.m_20148_(), broken);\n"
    "                markProgress(maid, level.m_46467_()); // 实测六十九：清树叶也算活动\n"
    "            }\n"
), "leaf", WD)

wr(WD, w)
print("wood OK")

# ==================== C. MaidMineBehavior.java ====================
MN = r"promaid_src\com\maidsmart\task\MaidMineBehavior.java"
m = rd(MN)

m = rep(m, (
    "    /** v1.5.113：搭方块材料耗尽播报限频（实体 ID → 上次播报 tick） */\n"
    "    private static final Map<Integer, Long> NO_BLOCK_REPORT_SINCE = new HashMap<>();\n"
), (
    "    /** v1.5.113：搭方块材料耗尽播报限频（实体 ID → 上次播报 tick） */\n"
    "    private static final Map<Integer, Long> NO_BLOCK_REPORT_SINCE = new HashMap<>();\n"
    "    /** v1.1.0 实测六十九：发呆看门狗——最近一次\"真实进展\"时刻（挖掉/垫了方块）。长时间零进展\n"
    "     *  且原地不动 = 发呆/死循环，自动整体重置该女仆的全部行为状态（等效收回魂符再放下去）。 */\n"
    "    private static final Map<Integer, Long> LAST_PROGRESS = new HashMap<>();\n"
    "    /** 看门狗窗口采样（实体 ID → [窗口起点 tick, 起点 x/y/z 各自的 double 位模式]） */\n"
    "    private static final Map<Integer, long[]> WATCH_SAMPLE = new HashMap<>();\n"
    "    /** 看门狗重置播报限频（实体 ID → 上次播报 tick） */\n"
    "    private static final Map<Integer, Long> RESET_REPORT_SINCE = new HashMap<>();\n"
    "\n"
    "    /** v1.1.0 实测六十九：登记一次真实进展（挖掉/垫了方块）——给看门狗续命 */\n"
    "    private static void markProgress(EntityMaid maid, long gameTime) {\n"
    "        LAST_PROGRESS.put(maid.m_19879_(), gameTime);\n"
    "    }\n"
), "maps", MN)

m = rep(m, (
    "        MINE_SCANS.remove(maidEntityId); // 实测六十一：分帧扫描游标一并清\n"
    "    }\n"
), (
    "        MINE_SCANS.remove(maidEntityId); // 实测六十一：分帧扫描游标一并清\n"
    "        LAST_PROGRESS.remove(maidEntityId); // 实测六十九：看门狗三表一并清\n"
    "        WATCH_SAMPLE.remove(maidEntityId);\n"
    "        RESET_REPORT_SINCE.remove(maidEntityId);\n"
    "    }\n"
), "forget", MN)

m = rep(m, "            ids.addAll(ORE_CACHE.keySet());\n",
        "            ids.addAll(ORE_CACHE.keySet());\n"
        "            ids.addAll(LAST_PROGRESS.keySet()); // 实测六十九\n"
        "            ids.addAll(WATCH_SAMPLE.keySet());\n"
        "            ids.addAll(RESET_REPORT_SINCE.keySet());\n", "purge-u", MN)
m = rep(m, (
    "            ORE_CACHE.keySet().removeIf(id -> !alive.contains(id));\n"
    "            MINE_SCANS.keySet().removeIf(id -> !alive.contains(id)); // 实测六十一\n"
), (
    "            ORE_CACHE.keySet().removeIf(id -> !alive.contains(id));\n"
    "            MINE_SCANS.keySet().removeIf(id -> !alive.contains(id)); // 实测六十一\n"
    "            LAST_PROGRESS.keySet().removeIf(id -> !alive.contains(id)); // 实测六十九\n"
    "            WATCH_SAMPLE.keySet().removeIf(id -> !alive.contains(id));\n"
    "            RESET_REPORT_SINCE.keySet().removeIf(id -> !alive.contains(id));\n"
), "purge-r", MN)

m = rep(m, (
    "        // 拦不住横向卡入，这是最后一道保险：宁可瞬移半步也不被自己搭的方块闷住\n"
    "        this.antiSuffocate(maid);\n"
), (
    "        // 拦不住横向卡入，这是最后一道保险：宁可瞬移半步也不被自己搭的方块闷住\n"
    "        this.antiSuffocate(maid);\n"
    "        // v1.1.0 实测六十九：发呆看门狗——长时间零进展且原地不动时整体重置状态。\n"
    "        // 与伐木同款（实测四十三的走路卡死检测只覆盖接近阶段，这里兜住挖掘/扫描/\n"
    "        // 迁移全流程的死循环），不再需要收回魂符重放来救\n"
    "        if (com.maidsmart.config.MaidSmartConfig.MINE_STUCK_WATCHDOG.get()\n"
    "                && this.stuckReset(level, maid, gameTime)) {\n"
    "            return; // 本 tick 已重置，下 tick 从头评估\n"
    "        }\n"
), "tick", MN)

m = rep(m, (
    "    /**\n"
    "     * v1.5.172：连锁采集【同时破坏】——把 refillChainQueue 填好的队列（相连同族矿）\n"
), (
    "    /** v1.1.0 实测六十九：窗口起点采样 */\n"
    "    private static long[] watchSample(long gameTime, EntityMaid maid) {\n"
    "        return new long[]{gameTime,\n"
    "                Double.doubleToLongBits(maid.m_20185_()),\n"
    "                Double.doubleToLongBits(maid.m_20186_()),\n"
    "                Double.doubleToLongBits(maid.m_20189_())};\n"
    "    }\n"
    "\n"
    "    /**\n"
    "     * v1.1.0 实测六十九：发呆看门狗判定（与伐木同款）。窗口到期（默认 45 秒）结算：\n"
    "     * 期间【挪动过 ≥1.5 格】或【有过真实进展（挖掉/垫方块）】= 正常工作，续窗；否则判发呆，\n"
    "     * 整体重置该女仆在本行为里的全部状态并返回 true。深井走路赶路位置一直在变，不误触发。\n"
    "     */\n"
    "    private boolean stuckReset(ServerLevel level, EntityMaid maid, long gameTime) {\n"
    "        int id = maid.m_19879_();\n"
    "        long window = com.maidsmart.config.MaidSmartConfig.MINE_STUCK_RESET_SECONDS.get() * 20L;\n"
    "        if (window <= 0) {\n"
    "            return false;\n"
    "        }\n"
    "        long[] sample = WATCH_SAMPLE.get(id);\n"
    "        if (sample == null) {\n"
    "            WATCH_SAMPLE.put(id, watchSample(gameTime, maid));\n"
    "            LAST_PROGRESS.putIfAbsent(id, gameTime);\n"
    "            return false;\n"
    "        }\n"
    "        if (gameTime - sample[0] < window) {\n"
    "            return false; // 窗口未到期\n"
    "        }\n"
    "        double moved = Math.sqrt(\n"
    "                Math.pow(maid.m_20185_() - Double.longBitsToDouble(sample[1]), 2)\n"
    "                        + Math.pow(maid.m_20186_() - Double.longBitsToDouble(sample[2]), 2)\n"
    "                        + Math.pow(maid.m_20189_() - Double.longBitsToDouble(sample[3]), 2));\n"
    "        Long lastProg = LAST_PROGRESS.get(id);\n"
    "        if (moved >= 1.5 || (lastProg != null && lastProg >= sample[0])) {\n"
    "            WATCH_SAMPLE.put(id, watchSample(gameTime, maid)); // 有在干活：续窗\n"
    "            return false;\n"
    "        }\n"
    "        this.hardResetStuck(level, maid, gameTime);\n"
    "        return true;\n"
    "    }\n"
    "\n"
    "    /**\n"
    "     * v1.1.0 实测六十九：发呆整体重置——forget 清空本女仆的全部静态表，实例字段\n"
    "     * （目标/进度/连锁队列/挡路名单/走路记忆/卡死计数等）一并归零，等效收回魂符再放下去。\n"
    "     */\n"
    "    private void hardResetStuck(ServerLevel level, EntityMaid maid, long gameTime) {\n"
    "        int id = maid.m_19879_();\n"
    "        long idleTicks = gameTime - LAST_PROGRESS.getOrDefault(id, gameTime);\n"
    "        forget(id);\n"
    "        this.targetPos = null;\n"
    "        this.destroyProgress = 0.0f;\n"
    "        this.saveProgressNow(maid);\n"
    "        this.abandonedPos = null;\n"
    "        this.blockedOres.clear();\n"
    "        this.skippedOrePos = null;\n"
    "        this.skippedOreName = null;\n"
    "        this.skippedOreTool = null;\n"
    "        this.skippedOreValue = -1;\n"
    "        this.chainQueue.clear();\n"
    "        this.chainBlock = null;\n"
    "        this.lastWalkTarget = null;\n"
    "        this.scanCooldown = 0;\n"
    "        this.pillarCooldown = 0;\n"
    "        this.walkRetargetCooldown = 0;\n"
    "        this.lastStuckPos = null;\n"
    "        this.stuckTicks = 0;\n"
    "        this.repathAttempts = 0;\n"
    "        this.stuckTarget = null;\n"
    "        if (this.lastCrackPos != null) {\n"
    "            this.broadcastCrack(level, maid, this.lastCrackPos, 10); // 清残留挖掘裂纹\n"
    "        }\n"
    "        this.lastCrackPos = null;\n"
    "        this.lastCrackStage = -1;\n"
    "        LAST_PROGRESS.put(id, gameTime);\n"
    "        WATCH_SAMPLE.put(id, watchSample(gameTime, maid));\n"
    "        LOGGER.info(\"mine stuck-reset: maid={} idle={}t pos={}\", id, idleTicks, maid.m_20183_());\n"
    "        Long lastReport = RESET_REPORT_SINCE.get(id);\n"
    "        if (lastReport == null || gameTime - lastReport >= 1200L) {\n"
    "            RESET_REPORT_SINCE.put(id, gameTime);\n"
    "            maid.getChatBubbleManager().addTextChatBubble(\"好像走神了……我重新理一下思路，继续干活！\");\n"
    "        }\n"
    "    }\n"
    "\n"
    "    /**\n"
    "     * v1.5.172：连锁采集【同时破坏】——把 refillChainQueue 填好的队列（相连同族矿）\n"
), "methods", MN)

m = rep(m, (
    "            if (!com.maidsmart.config.MaidSmartConfig.MINE_SEEK_THROUGH_WALLS.get()\n"
    "                    && !this.hasClearSight(level, maid, this.targetPos)) {\n"
    "                this.targetPos = null;\n"
    "                this.destroyProgress = 0.0f;\n"
    "                this.saveProgress(maid);\n"
    "                return;\n"
    "            }\n"
), (
    "            if (!com.maidsmart.config.MaidSmartConfig.MINE_SEEK_THROUGH_WALLS.get()\n"
    "                    && !this.hasClearSight(level, maid, this.targetPos)) {\n"
    "                // v1.1.0 实测六十九：视线被挡的目标进 30 秒短排——旧版只丢目标不记录，\n"
    "                // 扫描层几 tick 后又选中同一块，\"选中→看不见→丢弃\"无限循环站桩发呆\n"
    "                RECENT_DISCARD.computeIfAbsent(maid.m_19879_(), k -> new java.util.HashMap<>())\n"
    "                        .put(this.targetPos.m_7949_(), level.m_46467_());\n"
    "                this.targetPos = null;\n"
    "                this.destroyProgress = 0.0f;\n"
    "                this.saveProgress(maid);\n"
    "                return;\n"
    "            }\n"
), "sight", MN)

m = rep(m, (
    "    private static void trackPlaced(ServerLevel level, BlockPos pos, Block block, EntityMaid maid) {\n"
    "        PLACED_TRACKER.track(level, pos, block, maid);\n"
    "    }\n"
), (
    "    private static void trackPlaced(ServerLevel level, BlockPos pos, Block block, EntityMaid maid) {\n"
    "        PLACED_TRACKER.track(level, pos, block, maid);\n"
    "        markProgress(maid, level.m_46467_()); // 实测六十九：垫方块也是进展\n"
    "    }\n"
), "track", MN)

m = rep(m, "        this.chainBreakAll(level, maid, mainHand);\n",
        "        this.chainBreakAll(level, maid, mainHand);\n"
        "        markProgress(maid, gameTime); // 实测六十九：喂看门狗（真实进展）\n", "chain", MN)

wr(MN, m)
print("mine OK")

# ==================== D. GuideContent.java 手册两章补看门狗说明 ====================
GC = r"promaid_src\com\maidsmart\guide\GuideContent.java"
g = rd(GC)

g = rep(g, "                \"· §e废石保留量§r：背包里圆石等留多少格，防塞满。\",\n",
        "                \"· §e废石保留量§r：背包里圆石等留多少格，防塞满。\",\n"
        "                \"· §e发呆看门狗§r（默认开，45 秒）：挖矿时长时间既没挖到东西也没挪窝（原地发呆/状态卡死）\"\n"
        "                        + \"会自动整体重置她的挖矿状态再重新找矿——不用收回魂符重放救她；判定时长在配置面板「mine.stuckResetSeconds」。\",\n",
        "guide-mine", GC)

g = rep(g, "                \"· §e废石保留量§r：砍树途中挖穿泥土/石头产生的废石限量保留，防背包塞满。\",\n",
        "                \"· §e废石保留量§r：砍树途中挖穿泥土/石头产生的废石限量保留，防背包塞满。\",\n"
        "                \"· §e发呆看门狗§r（默认开，30 秒）：伐木时长时间既没砍到木头也没挪窝（典型如站进树洞里对着头顶树干发呆）\"\n"
        "                        + \"会自动整体重置她的伐木状态再重新找树——不用收回魂符重放救她；判定时长在配置面板「wood.stuckResetSeconds」。\",\n",
        "guide-wood", GC)

wr(GC, g)
print("guide OK")

# ==================== E. changelog.txt 插入条目 ====================
CL = r"promaid_src\assets\promaid\guide\changelog.txt"
c = rd(CL)
lines = c.split("\n")
entry = ("[05:30:0] v1.1.0 实测六十九（用户反馈：伐木站坑发呆仍要收回魂符重放才恢复）："
         "伐木/挖矿新增「发呆看门狗」——连续 N 秒（wood 默认 30/mine 默认 45，配置面板可调）既没挖掉任何方块、"
         "位置也没挪动时，自动整体重置该女仆的全部行为状态（锚点/扫描缓存/弃置排除表/目标/连锁队列/走路记忆），"
         "等效收回魂符再放下去，不再需要玩家手动救；走路赶路与垫方块都算进展不会误触发，重置时气泡说一声（限频）。"
         "顺带修复两处会喂出\"发呆\"的漏洞：关闭透视时被挡目标从\"丢弃但不记录\"改为进 30 秒短排（不再无限"
         "\"选中→看不见→丢弃\"循环）；伐木抬头兜底不再回选已被硬挡路弃置的木材。开关与时长都在配置面板挖矿/伐木小节。")
if entry[:20] not in c:
    lines.insert(1, entry)
    wr(CL, "\n".join(lines))
    print("changelog OK")
else:
    print("changelog already patched")

print("ALL DONE")
