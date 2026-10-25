package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.resources.ResourceLocation;

/**
 * 工作站桩标记（v1.5.24）。
 *
 * 背景：1.20.1 Brain 同一 activity 内多个行为并发启动（无 runningPriority 互斥），
 * MoveToTargetSink 每 tick 读取 WALK_TARGET 并驱动寻路。工作行为（建筑/烹饪/酿造/整理）
 * 虽然在 tick 里每 tick 清 WALK_TARGET + 停导航，但若 MoveToTargetSink 在行为执行
 * 之前跑，会读到（其他行为/上 tick 遗留的）移动目标重新寻路 → 站桩"时灵时不灵"。
 * 于是把"完全站桩"状态打在 persistentData 上，由 MaidMoveSuppressMixin 从源头取消
 * MoveToTargetSink（比事后清 memory 更可靠）。
 */
public final class MaidWorkTags {
    /** persistentData 键：女仆处于工作站桩（建筑/烹饪/酿造/整理，站立不动） */
    public static final String WORK_STILL_TAG = "maid_smart_work_still";

    /** 建造强制坐下标记键（与 MaidBuildBehavior.BUILD_SIT_TAG 同值——字符串复制
     *  避免包循环 import；任务级标记：切建造任务即坐下，切出自动站起） */
    public static final String BUILD_SIT_TAG = "maid_smart_build_sitting";

    private MaidWorkTags() {
    }

    /** 链路走位独占标记键（实测九百一十三）：本模组的链路（挖矿/伐木锁定目标、接战）正在驱动
     *  她走位期间，TLM 的一切走路逻辑让位——跟随行为不再把她写回主人/清她的寻路，排班不再拉她回工位。 */
    public static final String CHAIN_DRIVE_TAG = "maid_smart_chain_drive";

    /** 置/清"链路走位独占"标记（挖矿/伐木行为按"手上有没有锁定的目标"每 tick 维护） */
    public static void setChainDrive(EntityMaid maid, boolean on) {
        try {
            maid.getPersistentData().putBoolean(CHAIN_DRIVE_TAG, on);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidWorkTags", ignored);
        }
    }

    /**
     * 现在是不是「本模组的链路在驱动她」：挖矿/伐木锁着目标，或正在接敌。坐着 / 被骑 / 脑冻结
     * 恒 false（那几种状态她本来就不动）；**home 模式恒 false（实测九百一十四）**：那道工作圈
     * 是最大活动范围，圈里的走位照归 TLM，独占只在跟随模式生效。
     * 实测九百九十三【接管走位】：战斗腿 = 她**自己接上敌**（脑里有活着的非友军目标）或本模组驱动器真在
     * 开她——两者都没有就把走位完整还给 TLM 原版（跟随跟主人、home 在家活动）；开关 `combat.takeOverMovement`
     * 关掉 = 战斗腿直接不成立（战斗状态下不干涉她的走位与移动）。实测九百九十二【第三方玩法模式】：黑名单（傀儡师/拔刀剑/战斗）下恒 false，走位归那个模组。
     */
    public static boolean isChainDrive(EntityMaid maid) {
        try {
            if (maid.isMaidInSittingPose() || maid.isPassenger() || !maid.canBrainMoving()) {
                return false;
            }
            if (maid.isHomeModeEnable()) {
                return false; // home：圈是最大活动范围（见上）
            }
            if (maid.getTask() == null) {
                return false; // 没任务 / 任务刚被清：防残留
            }
            // 第三方玩法模式（傀儡师/拔刀剑/战斗）下【走位独占全体让位】：接战腿会因她
            // 自己的攻击目标咬住，而这类模式的走位归它自己的 AI（口径见 MaidModeCompat）。
            if (com.maidsmart.compat.MaidModeCompat.isSuspended(maid)) {
                return false;
            }
            if (maid.getPersistentData().getBoolean(CHAIN_DRIVE_TAG)) {
                return true; // 挖矿/伐木：标记本身就是"链路锁着目标在驱动"
            }
            if (!com.maidsmart.config.MaidSmartConfig.COMBAT_TAKE_OVER_MOVEMENT.get()) {
                return false; // 实测九百九十三【接管走位·关】：战斗状态下不干涉她的走位与移动
            }
            return com.maidsmart.combat.CombatWorkRange.hasAttackDesire(maid)
                    || com.maidsmart.combat.CombatWorkRange.chainDriverActive(maid);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidWorkTags", ignored);
            return false;
        }
    }

    /** 是否处于完全站桩工作状态（标记 + 当前任务确为工作任务，双保险防残留冻结） */
    public static boolean isStill(EntityMaid maid) {
        if (!((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getBoolean(WORK_STILL_TAG)) {
            return false;
        }
        // 防残留：标记在但任务已切走（行为被移除没走到清标记路径）→ 不冻结女仆
        return isWorkTask(maid);
    }

    /** 工作站绑定锁（实测一千〇七）：酿造/烧制任务一旦绑定到可用工作方块，就临时
     *  掐掉原版 TLM 的跟随链路——跟随行为 start 取消 + 跨维/同维自动拉回豁免，直到
     *  切出该任务或目标方块被破坏。与 WORK_STILL 分开：WORK_STILL 只在走到工作方块
     *  前才置（走去的路上必须能移动），本锁从"绑定成功"起就生效。 */
    public static final String STATION_LOCK_TAG = "maid_smart_station_lock";

    /** 置/清"工作站绑定锁"（MaidBrewBehavior / MaidCookBehavior 按方块有效性维护） */
    public static void setStationLock(EntityMaid maid, boolean on) {
        try {
            ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid)
                    .getPersistentData().putBoolean(STATION_LOCK_TAG, on);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidWorkTags", ignored);
        }
    }

    /** 现在是不是"锁在工作站上"（标记 + 当前任务确为酿造/烧制，双保险防残留） */
    public static boolean isStationLocked(EntityMaid maid) {
        try {
            if (!((net.neoforged.neoforge.common.extensions.IEntityExtension) maid)
                    .getPersistentData().getBoolean(STATION_LOCK_TAG)) {
                return false;
            }
            if (maid.getTask() == null) {
                return false;
            }
            ResourceLocation uid = maid.getTask().getUid();
            return uid.equals(ResourceLocation.parse("maid_smart:brew"))
                    || uid.equals(ResourceLocation.parse("maid_smart:cook"));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidWorkTags", ignored);
            return false;
        }
    }

    /** 工位解绑代数（女仆 UUID → 代数）。魂符收放/区块重载 = 一次"重新入世界"：
     *  烧制/酿造行为看到代数变了就丢掉旧工位、重新扫台，免得一直抱着收魂前那台炉子。 */
    private static final java.util.Map<java.util.UUID, Integer> STATION_GEN =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 记一次"重新入世界"（ProMaidExtension 在 EntityJoinLevelEvent 里调）。 */
    public static void bumpStationGen(EntityMaid maid) {
        try {
            STATION_GEN.merge(maid.getUUID(), 1, Integer::sum);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidWorkTags", ignored);
        }
    }

    /** 当前解绑代数（行为每 tick 对比，变化即解绑一次）。 */
    public static int stationGen(EntityMaid maid) {
        try {
            Integer v = STATION_GEN.get(maid.getUUID());
            return v == null ? 0 : v;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidWorkTags", ignored);
            return 0;
        }
    }

    /** 是否为"站桩工作"任务之一（建筑/烹饪/酿造；v1.5.134 整理任务已删除）。
     *  实测一千〇六【指标石同款】：index_build 临时搭建也吃站桩锁——她的行为一直在
     *  setStill(true)，而这里的白名单不含她 → isStill 恒 false → 移动抑制（源头取消
     *  MoveToTargetSink + 移动层清水平位移）对她从未生效，就是"搭建过程中乱动"的根因。 */
    public static boolean isWorkTask(EntityMaid maid) {
        if (maid.getTask() == null) {
            return false;
        }
        ResourceLocation uid = maid.getTask().getUid();
        return uid.equals(ResourceLocation.parse("maid_smart:build"))
                || uid.equals(ResourceLocation.parse("maid_smart:cook"))
                || uid.equals(ResourceLocation.parse("maid_smart:brew"))
                || uid.equals(ResourceLocation.parse("maid_smart:index_build"));
    }

    /** v1.5.129：战斗任务 UID（后备名单——接口判定不认识的再按 path 兜底）。
     *  v1.1.0：加入 gun_attack（TLM 内置枪械任务，TACZ/卓越前线） */
    private static final java.util.Set<String> COMBAT_UIDS = java.util.Set.of(
            "attack", "ranged_attack", "crossbow_attack", "trident_attack", "danmaku_attack",
            "gun_attack");

    /**
     * 为什么改接口判定：整合包生态（万法皆通 / 史诗战斗 / 真正的力量等）给女仆
     * 注册的第三方攻击任务 UID 各不相同（maidspell:xxx、eftlm:xxx…），UID 名单
     * 永远枚举不全。TLM 任务体系里"攻击任务"的官方契约就是实现 IAttackTask：
     * 近战 TaskAttack 直实现；弓/弩/三叉戟/弹幕/枪械（TaskGunAttack）以及万法
     * 皆通的两个法术战斗任务走子接口 IRangedAttackTask（字节码实证 extends
     * IAttackTask）；史诗战斗 FightModeTask 也直实现 IAttackTask——一个 instanceof 全覆盖。 兜底链：任务对象接口判定 → UID path 名单（防极个别没实现接口但语义上 是战斗的任务漏网；名单内任务在原版 TLM 都实现了接口，正常走不到）。 反编译实证：IAttackTask 默认 enablePanic()=false——战斗任务不吃恐慌， 与本判定口径天然一致。
     */
    public static boolean isAttackTask(EntityMaid maid) {
        if (maid.getTask() == null) {
            return false;
        }
        if (maid.getTask() instanceof com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask) {
            return true;
        }
        return COMBAT_UIDS.contains(maid.getTask().getUid().getPath());
    }

    /**
     * （Promaid 挖矿/建筑/烹饪/酿造 + TLM 原生 farm/挤奶/钓鱼/剪毛/蜂蜜等
     * 全部命中；idle/跟随/战斗类不命中）。用于"干活不被打断"系列闸门
     * （吃饭/偷吃/恐慌/切班拉回），战斗女仆不受影响。
     * 与 COMBAT_UIDS（path 名）永远不匹配 → isNonCombatWork 恒 true →
     * 战斗女仆也吃"干活不打断"闸门（战斗时不吃饭/不恐慌）。改为 getPath
     * （= getPath，返回 attack/ranged_attack 等）——MaidToolAutoEquip 注释实证。
     */
    public static boolean isNonCombatWork(EntityMaid maid) {
        if (maid.getTask() == null) {
            return false;
        }
        if (isAttackTask(maid)) {
            return false;
        }
        String path = maid.getTask().getUid().getPath();
        // v1.1.0 实测一百一十三：排除跟随——与文档契约一致（"idle/跟随/战斗类不命中"）。
        // 旧版只排除 idle/攻击，跟随任务误命中"干活不打断"闸门 → SchedulePosTickMixin
        // 把 home 模式女仆每 2 秒的回家走位 tick 整段 cancel（home 下 TLM 跟随本就空转，
        // 唯一移动驱动被掐）→ home 女仆原地呆站。补排 follow 后跟随状态女仆恢复被
        // 日程管理（回家走位/巡逻驱动照常）。
        // v1.5.287：idle 不算"干活"——旧版"非战斗即干活"把待机女仆一并门控
        //（待机不吃工作餐/不偷吃/被攻击不恐慌，与 docstring "空闲照常"矛盾——
        // 反馈："干活不被打断"只应作用于真正干活时）
        return !"idle".equals(path) && !"follow".equals(path);
    }

    /** v1.5.142：是否处于战斗任务（攻击/弓/弩/三叉戟/弹幕）——副手盾牌自动装备判定用。
     *  v1.1.0：升级为接口判定（isAttackTask）——万法皆通/史诗战斗等第三方攻击任务
     *  一并算战斗（盾牌装备/干活豁免/主动切换让位全部正确覆盖）。 */
    public static boolean isCombatTask(EntityMaid maid) {
        return isAttackTask(maid);
    }

    public static void setStill(EntityMaid maid, boolean still) {
        ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().putBoolean(WORK_STILL_TAG, still);
    }

    /**
     * 与 WORK_STILL 的区别：WORK_STILL 由建造/烹饪/酿造【行为】在激活期间设置，
     * 行为未激活的间隙（缺料等待/找区块/暂停）会被清掉；BUILD_SIT 是【任务级】
     * 标记（tickBuildSit 每 tick 维护），覆盖建造任务的全程。玩家在建造中"解除
     * 坐下"后，TLM MaidMoveControl 会用 setDeltaMovement 直接施加速度矢量、
     * 多行为也能直连导航 moveTo——这些通道绕过 tickBuildSit 的事后清理
     * （清 WALK_TARGET/停导航/清水平速度），出现"坐着走动"。MaidMoveSuppressMixin 与 MaidStationaryMixin 对本状态启用与 WORK_STILL 同款的移动源头锁。
     */
    public static boolean isBuildSitting(EntityMaid maid) {
        if (!((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getBoolean(BUILD_SIT_TAG)) {
            return false;
        }
        if (maid.getTask() == null) {
            return false;
        }
        return "build".equals(maid.getTask().getUid().getPath())
                && "maid_smart".equals(maid.getTask().getUid().getNamespace());
    }
    /**
     * 刷怪笼插火把·**走位所有权**标记（v1.3.0(beta) 实测六百六十四）。
     * <p>【为什么需要它】。
     * 那个「优先」在代码里原本只落在行为优先级 186 上——而 1.20.1 的 Brain 里
     * **CORE 与当前活动（WORK）是两套并发的行为表、互不阻断**：她去插火把的同时，
     * 挖矿/伐木/农活那几条驱动照样每 tick 发自己的寻路（直连 {@code moveTo}），
     * 谁最后写谁说了算 → 她被一路按在工位上，"优先"名存实亡。 <p>【怎么用】{@code MaidSpawnerTorchBehavior} 只在"有目标、正在走过去"这段时间置上它： <ul> <li>{@code MaidMoveSuppressMixin}：清 WALK_TARGET + 取消 MoveToTargetSink （TLM 原生任务、跟随、远程走位写的 WALK_TARGET 一律不执行）；</li> <li>{@code SpawnerTorchNavGuardMixin}：掐掉**别的驱动**发起的直连 {@code moveTo} （她自己的那一发走"自标记放行"的窄门）；</li> <li>挖矿/伐木/农活驱动不必逐个改——它们的寻路在上面那面闸前就被拦下了。</li> </ul> <p>【什么时候自动让位】打架（攻击类任务）、被骑、坐着、或玩家把 {@code combat.spawnerTorch.priority} 关掉时，本判据恒为 false——**战斗永远优先**， 而且行为自己在"脑里出现攻击目标"的那一 tick 就会清标记。
     */
    public static final String SPAWNER_TORCH_TAG = "maid_smart_spawner_torch";

    /**
     * <p>【为什么要与插火把分开一枚】模组刷怪笼无视光照，插火把压不住，改为**直接拆掉**。
     * 拆除期间她的走位同样必须独占（否则挖矿/伐木/战斗的直连寻路会把她从刷怪笼前拖走、
     * 一镐都刨不下去），但"拆除"是**默认行为**，不能跟着 {@code spawnerTorch.priority}
     * （那个开关是给"要不要为了插火把放下手上的活"用的）一起被关掉——所以单开一枚标记，
     * 拆除期间恒独占（见 {@link #isSpawnerTorchRun}）。
     */
    public static final String SPAWNER_BREAK_TAG = "maid_smart_spawner_break";

    /** 是否处于"正走去插火把/拆刷怪笼"的走位独占期（标记 + 若干让位判据，双保险防残留冻结） */
    public static boolean isSpawnerTorchRun(EntityMaid maid) {
        try {
            boolean brk = maid.getPersistentData().getBoolean(SPAWNER_BREAK_TAG);
            if (!brk && !maid.getPersistentData().getBoolean(SPAWNER_TORCH_TAG)) {
                return false;
            }
            if (maid.getTask() == null) {
                return false;
            }
            // 实测八百二十七【拆 > 战斗】：**正在拆**时即便她是战斗任务也独占走位——
            // 。够得着的怪由
            // NeutralThreatDriver 原地反击（不走导航），她钉在刷怪笼前把这一镐刨完。
            // 插火把（brk=false）仍走旧口径：战斗任务让位（火把没那么要紧）。
            if (isAttackTask(maid) && !brk) {
                return false; // 打架 + 插火把：她的走位归战斗，谁都不许挡
            }
            if (maid.isMaidInSittingPose() || maid.isPassenger()) {
                return false; // 坐着 / 乘客：本来就走不动
            }
            // 拆除：恒独占（默认行为，不受"优先去插"开关影响）；插火把：仍受该开关管
            return brk || com.maidsmart.config.MaidSmartConfig.COMBAT_SPAWNER_TORCH_PRIORITY.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidWorkTags", ignored);
            return false;
        }
    }

    /** 置/清走位所有权标记（由 {@code MaidSpawnerTorchBehavior} 维护） */
    public static void setSpawnerTorch(EntityMaid maid, boolean on) {
        try {
            maid.getPersistentData().putBoolean(SPAWNER_TORCH_TAG, on);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidWorkTags", ignored);
        }
    }

    /** 实测八百二十二：置/清"正在拆刷怪笼"走位所有权标记 */
    public static void setSpawnerBreak(EntityMaid maid, boolean on) {
        try {
            maid.getPersistentData().putBoolean(SPAWNER_BREAK_TAG, on);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidWorkTags", ignored);
        }
    }

    /** 实测八百二十二：她此刻是否挂了"拆除"走位所有权标记 */
    public static boolean isSpawnerBreak(EntityMaid maid) {
        try {
            return maid.getPersistentData().getBoolean(SPAWNER_BREAK_TAG);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidWorkTags", ignored);
            return false;
        }
    }

    /** 实测九百一十六【干活时也捡掉落物】：拾取任务挑中某件掉落物、开始驱动她走过去时打的戳
     *  （{@code MaidPickupEntitiesTask.start} 里打，见 {@code MaidPickupPriorityMixin}）。链路
     *  走位独占（{@link #isChainDrive}）期间只有这一发能让 MoveToTargetSink 照常把 WALK_TARGET
     *  变成寻路——不然"干活时去捡东西"的走位目标会被独占闸每 tick 清掉（反馈："砍树伐木时不捡
     *  掉落物"）。 */
    private static final java.util.Map<Integer, Long> PICKUP_DRIVE_UNTIL = new java.util.concurrent.ConcurrentHashMap<>();

    /** 拾取任务开始驱动走位时打戳（30 秒安全窗口；目标消化后由独占闸清） */
    public static void markPickupDrive(EntityMaid maid, long gameTime) {
        try {
            if (PICKUP_DRIVE_UNTIL.size() > 1024) {
                PICKUP_DRIVE_UNTIL.clear(); // 兜底防增长（正常情况下过期即自清）
            }
            PICKUP_DRIVE_UNTIL.put(maid.getId(), gameTime + 600L);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidWorkTags", ignored);
        }
    }

    /** 结束拾取驱动放行（走位目标已被消化/失效时由独占闸清） */
    public static void clearPickupDrive(EntityMaid maid) {
        try {
            PICKUP_DRIVE_UNTIL.remove(maid.getId());
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidWorkTags", ignored);
        }
    }

    /** 现在是不是"拾取任务正在驱动她走去捡东西"（窗口内打过戳；过期即失效） */
    public static boolean isPickupDrive(EntityMaid maid, long gameTime) {
        Long until = PICKUP_DRIVE_UNTIL.get(maid.getId());
        if (until == null) {
            return false;
        }
        if (gameTime > until) {
            PICKUP_DRIVE_UNTIL.remove(maid.getId());
            return false;
        }
        return true;
    }

}
