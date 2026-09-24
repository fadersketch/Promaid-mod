package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.PositionTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 烹饪行为（v1.5.252 重写燃料，v1.5.252e 去掉冶炼、回归纯烹饪）：
 * 给附近的炉子补燃料/生食，收取烧好的成品。
 * 炉子槽位：0=待烧食材，1=燃料，2=成品。
 * 平衡设计：
 * - 只操作熔炉类方块（烟熏炉/高炉/熔炉通用），每次处理 1 轮（收成品 > 补食材 > 补燃料）
 * - v1.1.0 实测一百五十八：兼容高炉与烟熏炉（开关 misc.cookSmokerBlast）——烟熏炉按
 *   烟熏配方喂生食、高炉按高炉配方喂矿石/粗金属（受「熔炉烧矿物」开关约束，高炉只烧
 *   矿物）、熔炉保持食材→矿物顺序；成品收取/燃料逻辑三种炉子通用
 * - 食材由女仆背包携带（内置白名单：生肉/鱼/土豆等）
 * - v1.1.0 实测一百五十七：背包没有食材时兼容【矿物类可烧制物】——带矿物/原料标签
 *   （forge:ores、minecraft:*_ores、forge:raw_materials 等）且当前世界有熔炉配方的
 *   物品（铁矿石/粗铁/金矿石/远古残骸等）照常放进熔炉烧（开关 misc.cookSmeltOres）
 * - v1.5.252 燃料修正：**不限于煤炭——凡是可燃烧物品（原版 isFuel）都可用，
 *   优先选背包中数量最多的那个**
 * - v1.1.0 实测二百四十一：燃料选择再修正——纯燃料优先（燃烧时长评分：
 *   煤炭/木炭/烈焰棒/干海带块/熔岩桶等不可烧制的可燃烧物），背包没有纯燃料
 *   才退而选可烧制燃料（原木/木板/树苗）——不再"用木头烧木头"
 * - v1.1.0 实测三百：按背包物品类型选炉子——先扫物品栏：有食物 → 烟熏炉优先
 *   （其次熔炉）；无食物有矿物（有高炉配方）→ 高炉优先（其次熔炉）；只有仅熔炉
 *   可烧物 → 只选熔炉。绑定后背包没有该炉型可烧物 → 取消绑定重新找。木材类
 *   （原木/木板/树苗/竹等）默认黑名单不烧（开关 misc.cookBurnWood，默认关）
 * - 处理间隔 100 tick（5 秒），不瞬间完成烹饪（炉子自身进度驱动）
 * - v1.5.252：绑定炉子并到达后立刻坐下不动；行为停止/炉子丢失恢复站立
 * - v1.2.4 实测六百三十七【没炉子可烧时不再"把自己冻住"】：见 {@link #wander}
 */
public class MaidCookBehavior extends Behavior<EntityMaid> {
    /** v1.1.0 实测一百六十一：诊断日志（latest.log 搜 "cook "）——定位"炉子就在
     *  附近却烧不起来"：行为是否在跑 / 绑定哪个炉子 / 门控是否通过 / 喂了什么 */
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static int cookRadius() {
        return com.maidsmart.config.MaidSmartConfig.MISC_COOK_RADIUS.get();
    }

    private static int processCooldown() {
        return com.maidsmart.config.MaidSmartConfig.MISC_PROCESS_COOLDOWN.get();
    }

    /** 可烹饪食材白名单（原版熔炉可烧食物）
     *
     *  v1.2.5 实测六百五十一【删掉五个"根本烧不了"的条目】：旧清单里
     *  {@code beetroot / carrot / brown_mushroom / cactus / dried_kelp} 在原版
     *  **没有任何炉子配方**——两份客户端 jar 的 {@code data/minecraft/recipes}
     *  逐个查过（smelting / smoking / blasting 全找遍）：这五个一条都没有，
     *  而同清单里的 potato / kelp / 生肉 / 鱼都查得到。
     *  清单里多这五个的直接后果是"女仆会烧胡萝卜"（玩家反馈）。
     *  删掉之后：模组/数据包**自己给这些物品补了配方**时照样能烧——那条路走
     *  {@link #extractAnySmeltable} 与 {@link #pickFurnaceKind} 的通用分支，
     *  它们查的是真实配方而不是这张表，所以这里是"白名单说法变准"，不是"功能变少"。 */
    private static final Set<Item> FOODS = new HashSet<>();

    static {
        addItem(FOODS, "minecraft:beef");
        addItem(FOODS, "minecraft:porkchop");
        addItem(FOODS, "minecraft:chicken");
        addItem(FOODS, "minecraft:mutton");
        addItem(FOODS, "minecraft:rabbit");
        addItem(FOODS, "minecraft:cod");
        addItem(FOODS, "minecraft:salmon");
        addItem(FOODS, "minecraft:potato");
        addItem(FOODS, "minecraft:kelp");
    }

    private static void addItem(Set<Item> set, String id) {
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(id));
        if (item != null) {
            set.add(item);
        }
    }

    private BlockPos furnacePos = null;
    private int cooldown = 0;
    /** 目标扫描节流：找不到熔炉时每 20 tick 才扫一次 */
    private int scanCooldown = 0;
    /** 闲逛点节流（v1.2.4 实测六百三十七）：每 40 tick（2 秒）换一个闲逛点 */
    private int wanderCooldown = 0;
    /** v1.2.5 实测六百五十三：绑定炉子后【最后一次有效动作】的世界时刻——看门狗用。
     *  有效动作 = 收了成品 / 喂进料 / 补了柴 / 取回了烧不动的东西。 */
    private long lastProgressGameTime = 0L;
    /** v1.1.0 实测一百六十八：炉子占用表（维度|坐标 → 占用女仆 UUID）——多个女仆同时
     *  在场时各自绑定不同炉子，避免全挤到第一个炉子上（反馈："两个女仆三个炉子，
     *  只有一个炉子工作"）。占用者死亡/换维/停行为时释放（m_6732_ + 扫描时懒清理）。 */
    private static final java.util.Map<String, java.util.UUID> FURNACE_USERS = new java.util.HashMap<>();
    /** 本行为实例当前占用的炉子 key（行为停止/炉子丢失时释放） */
    private String myFurnaceKey = null;

    /** 炉子占用键：维度 + 坐标（防跨维度同坐标冲突） */
    private static String furnaceKey(ServerLevel level, BlockPos pos) {
        return level.m_46472_().m_135782_() + "|"
                + pos.m_123341_() + "," + pos.m_123342_() + "," + pos.m_123343_();
    }

    /** 占用当前绑定的炉子（替换旧占用）。
     *  v1.1.0 实测一百八十六：登记前【所有权校验】——findFurnace 会跳过别人占用的
     *  炉子，但 m_6735_ 重启路径（furnacePos 保留 + myFurnaceKey 已释放）直接调这里
     *  put 覆盖写，可把另一只女仆在用的炉子抢过来 → 两女仆挤一个炉、另一炉闲置
     *  （"偶有发生"的竞争性根因）。现在别人在占（占用者存活）→ 放弃本炉并清空
     *  furnacePos，走 doTick 重新找炉。 */
    private void claimFurnace(ServerLevel level, EntityMaid maid, BlockPos pos) {
        String k = furnaceKey(level, pos);
        java.util.UUID owner = FURNACE_USERS.get(k);
        if (owner != null && !owner.equals(maid.m_20148_())) {
            net.minecraft.world.entity.Entity o = level.m_8791_(owner);
            if (o != null && o.m_6084_()) {
                this.furnacePos = null; // 别人在用——放弃本炉，重新找炉
                this.releaseFurnace();
                return;
            }
            FURNACE_USERS.remove(k); // 占用者没了 → 释放后再占
        }
        this.releaseFurnace();
        this.myFurnaceKey = k;
        FURNACE_USERS.put(k, maid.m_20148_());
    }

    /** 释放本实例占用的炉子（仅当占用者是自己） */
    private void releaseFurnace() {
        if (this.myFurnaceKey != null) {
            FURNACE_USERS.remove(this.myFurnaceKey);
            this.myFurnaceKey = null;
        }
    }

    public MaidCookBehavior() {
        // v1.5.124：无限运行时长（旧版默认 60 tick 上限导致行为每 3 秒重启）
        super(java.util.Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Override
    protected boolean m_6114_(ServerLevel level, EntityMaid maid) {
        // v1.5.24 站桩（参考建筑行为）：只要任务是烹饪就启动——
        // 不再依赖"附近有熔炉"（旧逻辑找不到熔炉时行为不启动 → 漫游乱跑）
        return isCookTask(maid);
    }

    private static boolean isCookTask(EntityMaid maid) {
        return maid.getTask() != null
                && net.minecraft.resources.ResourceLocation.parse("maid_smart:cook")
                .equals(maid.getTask().getUid());
    }

    @Override
    protected void m_6735_(ServerLevel level, EntityMaid maid, long gameTime) {
        if (this.furnacePos == null) {
            this.furnacePos = this.findFurnace(level, maid);
        }
        // v1.1.0 实测一百七十四【启动即登记占用】：旧版只在 doTick 里"找不到炉子后
        // 补扫到"时才 claim——启动时 findFurnace 直接找到的炉子从不进占用表
        // （phantom claim）。多女仆同时开烧时各自从启动就"虚占"同一炉子，占用表
        // 形同虚设（互不排斥）；释放侧 myFurnaceKey 也是 null，行为停止清不掉任何
        // 东西。现在启动找到炉子就立即登记，与 doTick 补扫路径统一。
        if (this.furnacePos != null && this.myFurnaceKey == null) {
            this.claimFurnace(level, maid, this.furnacePos);
        }
        this.cooldown = 0;
        this.lastProgressGameTime = gameTime; // v1.2.5 实测六百五十三：看门狗计时起点
        LOGGER.info("cook start: maid={} furnace={}",
                com.maidsmart.tool.PromaidLog.nameOf(maid), this.furnacePos);
    }

    @Override
    protected void m_6725_(ServerLevel level, EntityMaid maid, long gameTime) {
        if (this.furnacePos == null) {
            // v1.2.4 实测六百三十七【不再"没炉子就把自己钉死"】：旧版这里
            // setStill(true) 站桩等待，而 WORK_STILL 经 MaidMoveSuppressMixin 是
            // **彻底静止**（每 tick 清走位 + 停导航 + 取消 MoveToTargetSink），
            // 于是"烧制任务 + home 模式 + 附近没有可用的炉子"= 永远不动。
            // 玩家日志实证（latest.log 13:54 起）：反复 `cook start: maid=… furnace=null`
            // 之后再没挪过一步；反馈原文「不进入我们自己写的烧制且处于 home 状态
            // 这个模式的时候会直接卡在原地不动」。
            // 现在改为一心两用：放行移动（不站桩），每 2 秒在附近挑个闲逛点
            // ——与宰杀"没有牲畜就四处闲逛"（实测三百四十）同一套口径，
            // 边走边扫炉子，找得到就绑定、找不到也不冻人。
            MaidWorkTags.setStill(maid, false);
            this.standUp(maid);
            if (this.scanCooldown-- > 0) {
                this.wander(maid);
                return;
            }
            this.scanCooldown = 20;
            this.furnacePos = this.findFurnace(level, maid);
            if (this.furnacePos == null) {
                this.wander(maid);
                return;
            }
            // v1.1.0 实测一百六十八：绑定成功 → 登记炉子占用（多女仆分散）
            this.claimFurnace(level, maid, this.furnacePos);
            this.lastProgressGameTime = gameTime; // v1.2.5 实测六百五十三：换炉子重新计时
        }
        BlockState state = level.m_8055_(this.furnacePos);
        if (!(state.m_60734_() instanceof AbstractFurnaceBlock)) {
            this.furnacePos = null;
            this.releaseFurnace(); // v1.1.0 实测一百六十八：炉子没了 → 释放占用
            this.standUp(maid);
            // 炉子没了：不站桩（下一 tick 走上面的"找炉子"分支，边走边找）
            MaidWorkTags.setStill(maid, false);
            this.wander(maid);
            return;
        }
        // v1.1.0 实测三百（反馈："如果女仆发现自己包中没有可以对应的物品，那么会将
        // 这个炉子的绑定取消掉……重新寻找一个新的"）：绑定后背包没有该炉型可烧物 →
        // 取消绑定重新找（如高炉烧完矿后背包只剩食物 → 换烟熏炉/熔炉）
        if (!this.furnaceMatchesInv(level, maid, this.furnacePos)) {
            this.furnacePos = null;
            this.releaseFurnace();
            this.standUp(maid);
            // v1.2.4 实测六百三十七：背包里没有这型炉子能烧的东西 → 解绑重找，
            // 并且**不站桩**（旧版这里 setStill(true)，配上"每 20 tick 又扫回同一个
            // 不匹配的炉子"就是永久冻结）；边走边找，换个炉型就有得烧了。
            MaidWorkTags.setStill(maid, false);
            this.wander(maid);
            return;
        }
        double distSq = maid.m_20275_(this.furnacePos.m_123341_() + 0.5, this.furnacePos.m_123342_() + 0.5, this.furnacePos.m_123343_() + 0.5);
        if (distSq > 6.25) {
            // 还没到熔炉：解除站桩标记，允许 MoveToTargetSink 走过去
            MaidWorkTags.setStill(maid, false);
            this.standUp(maid);
            maid.m_6274_().m_21879_(MemoryModuleType.f_26370_,
                    new WalkTarget(new BlockPosTracker(this.furnacePos), 1.0f, 2));
            return;
        }
        // v1.5.17 站桩强化：绑定工作方块后每 tick 清移动目标 + 停止导航，
        // 冷却期间也清——防止漫游/跟随在冷却间隙重新设目标（完全站桩不动）
        MaidWorkTags.setStill(maid, true);
        maid.m_6274_().m_21936_(MemoryModuleType.f_26370_);
        maid.m_21573_().m_26569_();
        // v1.5.252：绑定完成 → 立刻坐下不动（每 tick 保持坐姿防状态机重置）
        if (!maid.isMaidInSittingPose()) {
            maid.m_20124_(net.minecraft.world.entity.Pose.SITTING);
        }
        // v1.2.5 实测六百五十三【看门狗：最后一道保险】——绑定 + 站桩，却在 60 秒里一次
        // 有效动作都没有（不喂料、不收成品、不补柴、也没把卡住的东西取回）→ 解绑走人，
        // 绝不"坐在炉子前永久不动"。有它兜底，三种炉子（含模组炉）任何"匹配得上却喂不进、
        // 烧不动"的死角都不会再表现成玩家看到的"站在原卡死"。正常烧制时每 2 秒一轮必有
        // 动作，而原版最长的炉子配方也就 200 tick（10 秒），60 秒（1200 tick）留了 6 倍余量，
        // 不会误触发（真遇到超慢模组配方：她先走开、成品好了再回来收，不会丢东西）。
        if (this.lastProgressGameTime <= 0L) {
            this.lastProgressGameTime = gameTime; // 兜底初始化（start/绑定时已设）
        }
        if (gameTime - this.lastProgressGameTime > 1200L) {
            this.furnacePos = null;
            this.releaseFurnace();
            this.standUp(maid);
            MaidWorkTags.setStill(maid, false);
            LOGGER.info("cook watchdog: maid={} 绑定炉子 60 秒零进展（喂不进/烧不动）→ 解绑重新找",
                    com.maidsmart.tool.PromaidLog.nameOf(maid));
            this.wander(maid);
            return;
        }
        if (this.cooldown-- > 0) {
            return;
        }
        this.cooldown = processCooldown();
        BlockEntity be = level.m_7702_(this.furnacePos);
        // v1.1.0 实测一百五十八：兼容高炉/烟熏炉——开关开启时三种炉子都操作；
        // 关闭 = 旧行为（只处理熔炉，烟熏炉/高炉前干坐）
        if (be instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity
                && (be instanceof FurnaceBlockEntity
                        || com.maidsmart.config.MaidSmartConfig.MISC_COOK_SMOKER_BLAST.get())) {
            // v1.2.5 实测六百五十三：这一轮有有效动作 → 刷新看门狗计时
            if (this.processFurnace(level, maid, (Container) be, be)) {
                this.lastProgressGameTime = gameTime;
            }
        } else {
            // v1.1.0 实测一百六十一：诊断——门控未过（BE 类型不对/开关关），不该发生
            LOGGER.info("cook gate-blocked: maid={} be={} switch={} furnace={} distSq={}",
                    com.maidsmart.tool.PromaidLog.nameOf(maid),
                    be == null ? "null" : be.getClass().getSimpleName(),
                    com.maidsmart.config.MaidSmartConfig.MISC_COOK_SMOKER_BLAST.get(),
                    this.furnacePos, distSq);
        }
    }

    @Override
    protected boolean m_6737_(ServerLevel level, EntityMaid maid, long gameTime) {
        // v1.5.24 站桩强化：只要还在烹饪任务就持续站桩——找不到熔炉时也保持行为运行
        boolean still = isCookTask(maid);
        if (!still) {
            MaidWorkTags.setStill(maid, false);
            this.standUp(maid);
        }
        return still;
    }

    @Override
    protected void m_6732_(ServerLevel level, EntityMaid maid, long gameTime) {
        // v1.5.24：行为真正停止时解除站桩标记（双保险）+ 恢复站立
        MaidWorkTags.setStill(maid, false);
        this.standUp(maid);
        // v1.1.0 实测一百六十八：行为停止 → 释放炉子占用（其他女仆可接手）
        this.releaseFurnace();
    }

    /** v1.5.252：恢复站立（若当前是坐姿） */
    private void standUp(EntityMaid maid) {
        try {
            if (maid.isMaidInSittingPose()) {
                maid.m_20124_(net.minecraft.world.entity.Pose.STANDING);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * v1.2.4 实测六百三十七：**没炉子可烧时的闲逛**（"别把自己冻在原地"）。
     *
     * ── 为什么要有这一段 ──
     * 旧版在"找不到熔炉 / 炉子被拆 / 背包里没有这型炉子能烧的东西"三个分支里都是
     * {@code setStill(true)} = 站桩等待。单看是"不乱跑"的美意，实际效果是**永久静止**：
     * <ul>
     *   <li>WORK_STILL 由 {@code MaidMoveSuppressMixin} 在 MoveToTargetSink 入口整段取消
     *       ——不仅拦 WALK_TARGET，还会**每 tick 调 stopNavigation**，所以连直连导航
     *       （m_26519_ moveTo）这种"绕开 MoveToTargetSink"的通道也会被立刻掐掉；</li>
     *   <li>本任务 {@code enableLookAndRandomWalk=false}（不给 TLM 随机散步），
     *       {@code HomeWorkMovementDriver} 只认农场/宰杀，{@code HomePatrolHandler}
     *       跳过"非战斗干活中"——三条外部驱动全都不管她。</li>
     * </ul>
     * 三个条件叠加 → 玩家看到的就是"卡在原地不动"（日志实证：反复
     * {@code cook start: maid=… furnace=null} 之后再没移动过）。
     *
     * ── 口径 ──
     * 与宰杀（实测三百四十"没有需要宰杀的动物就四处闲逛"）**完全同一套**：
     * 每 40 tick（2 秒）在她周围 8 格内随机取一点设 WALK_TARGET
     * （{@code BlockPosTracker} 固定点，MoveToTargetSink 消费驱动寻路），
     * 走位期间不站桩；闲逛点同样受工作圈钳制（{@link com.maidsmart.follow.WorkAreaClamp#allows}
     * ——home 模式不逛出限制圈，跟随模式不逛离主人）。扫描照旧每 20 tick 一次
     * （{@code findFurnace} 是以她当前位置为中心扫的），所以"边走边找炉子"，
     * 一旦扫到就绑定并走过去坐下干活。
     */
    private void wander(EntityMaid maid) {
        if (this.wanderCooldown-- > 0) {
            return;
        }
        this.wanderCooldown = 40; // 2 秒换一个闲逛点
        int r = 8;
        int dx = maid.m_217043_().m_188503_(r * 2 + 1) - r;
        int dz = maid.m_217043_().m_188503_(r * 2 + 1) - r;
        BlockPos base = maid.m_20183_();
        BlockPos spot = new BlockPos(base.m_123341_() + dx, base.m_123342_(), base.m_123343_() + dz);
        if (!com.maidsmart.follow.WorkAreaClamp.allows(maid, spot)) {
            return; // 本轮不闲逛（下轮再选），绝不逛出工作圈
        }
        try {
            maid.m_6274_().m_21879_(MemoryModuleType.f_26370_,
                    new WalkTarget(new BlockPosTracker(spot), 0.7f, 1));
        } catch (Throwable ignored) {
        }
    }

    /** v1.1.0 实测一百八十六：轮次日志状态迁移式——同状态只记一次（旧版每处理轮
     *  一条，绑定炉子无料可喂时日志被刷爆）。喂料成功/收成品（状态变化）后重记，
     *  完整反映"有料→无料→再喂上"的真实迁移。 */
    private static final java.util.Map<java.util.UUID, String> LAST_ROUND_OUTCOME = new HashMap<>();

    /** 记录一轮处理结果（同状态只记一次）——返回是否记录了本次（false = 与上次同状态，跳过） */
    private boolean logRound(EntityMaid maid, String beName, String outcome, String detail) {
        String last = LAST_ROUND_OUTCOME.get(maid.m_20148_());
        if (outcome.equals(last)) {
            return false;
        }
        LAST_ROUND_OUTCOME.put(maid.m_20148_(), outcome);
        LOGGER.info("cook round: maid={} be={} {}",
                com.maidsmart.tool.PromaidLog.nameOf(maid), beName, detail);
        return true;
    }

    private boolean processFurnace(ServerLevel level, EntityMaid maid, Container furnace, BlockEntity be) {
        IItemHandler maidInv = maid.getAvailableBackpackInv();
        String beName = be == null ? "null" : be.getClass().getSimpleName();
        // v1.2.5 实测六百五十三：这一轮有没有"有效动作"（给看门狗计时用）
        boolean changed = false;
        // 1. 收取成品
        ItemStack result = furnace.m_8020_(2);
        if (!result.m_41619_()) {
            ItemStack taken = furnace.m_8016_(2);
            ItemStack left = ItemHandlerHelper.insertItemStacked(maidInv, taken, false);
            if (!left.m_41619_()) {
                furnace.m_6836_(2, left);
            }
            this.logRound(maid, beName, "output", "tookOutput=" + taken);
            changed = true;
        }
        // 2. 补燃料（槽 1 空）——v1.2.5 实测六百五十三：从"最后一步"挪到"喂料之前"。
        //    旧顺序（先喂料、后补柴）留了一个死法：她背包里有料、却一根柴都没有 →
        //    料被喂进槽 0、柴补不上、炉子点不着、槽 0 从此非空 → 之后每一轮都跳过喂料
        //    → 她绑着炉子坐下、永久不动（玩家看到的就是"站在原地卡死"）。
        //    先补柴，就能在喂之前先知道"有没有柴"：没柴就不喂，料留在她背包里，
        //    不会变成炉子里的路障。
        //    v1.5.252：不限于煤炭，选背包中数量最多的可燃烧物品
        //    v1.1.0 实测二百四十一：纯燃料优先（燃烧时长评分，煤炭/木炭/烈焰棒等），
        //    没有纯燃料才退而选可烧制燃料（原木/木板）——不再"用木头烧木头"
        if (furnace.m_8020_(1).m_41619_()) {
            ItemStack fuel = this.extractBestFuel(level, maid, maidInv);
            if (!fuel.m_41619_()) {
                furnace.m_6836_(1, fuel);
                changed = true;
            }
        }
        // 3. 补食材（槽 0 空）
        if (furnace.m_8020_(0).m_41619_()) {
            if (furnace.m_8020_(1).m_41619_()) {
                // v1.2.5 实测六百五十三：没柴可添 = 喂进去也点不着 → 不喂
                if (this.logRound(maid, beName, "nofuel",
                        "槽0空但无柴可添（背包里没有能烧的燃料）——不喂，避免料卡在炉子里")) {
                    this.dumpInvOnNoFeed(level, maid, maidInv, beName);
                }
                return changed;
            }
            ItemStack input = ItemStack.f_41583_;
            if (be instanceof FurnaceBlockEntity) {
                // v1.2.5 实测六百五十一：改成**查配方**的白名单提取——旧版这里只按
                // FOODS.contains 取第一件，于是背包里"胡萝卜在生牛排前面"时喂进去的
                // 就是胡萝卜（炉子永远烧不动它）。
                input = this.extractCookFood(level, maid, maidInv);
                if (input.m_41619_()) {
                    // v1.1.0 实测一百五十七：没有食材时兼容矿物类可烧制物
                    //（带矿物/原料标签且当前世界有熔炉配方：铁矿石/粗铁/金矿石等）
                    input = this.extractOreFromMaid(level, maid, maidInv);
                }
                if (input.m_41619_()) {
                    // v1.1.0 实测一百八十二：仍没有 → 通用可烧制物回退——凡当前世界
                    // 有熔炉配方且非装备类的物品都喂（沙子/圆石/原木/模组食材/无矿物
                    // 标签的模组粗矿等）。旧版白名单+矿物标签不认的东西卡死补料，
                    // 表现为"只投一次燃料就再也不喂"（实测第 2 只女仆）
                    input = this.extractAnySmeltable(level, maid, maidInv);
                }
            } else {
                // v1.1.0 实测一百五十八：烟熏炉/高炉——按各自配方类型取可烧制物
                input = this.extractForFurnaceType(level, maid, maidInv, be);
            }
            if (!input.m_41619_()) {
                furnace.m_6836_(0, input);
                this.logRound(maid, beName, "fed", "fedSlot0=" + input);
                changed = true;
            } else {
                // v1.1.0 实测一百八十六：迁移式记录（同状态只打一条，防刷屏）——
                // 进入"无料可喂"状态时记一条 + 背包 dump（30 秒限频）
                if (this.logRound(maid, beName, "nofeed",
                        "slot0Empty但无料可喂（背包无食材/矿物或配方不匹配）")) {
                    this.dumpInvOnNoFeed(level, maid, maidInv, beName);
                }
            }
            return changed;
        }
        // 4. 槽 0 有东西，但这型炉子根本烧不动它 → 取回（v1.2.5 实测六百五十三）
        //    这正是玩家 1.21.1 实测卡死的那一格：旧版把胡萝卜喂进了熔炉（胡萝卜在原版
        //    没有任何炉子配方），炉子永远不会消耗它 → 槽 0 从此非空 → 每一轮都跳过喂料
        //    → 女仆绑着炉子坐下、永久不动（日志实证：喂进胡萝卜后再没出现过第二轮）。
        //    实测六百五十一只治了"不再喂胡萝卜"，治不了"已经躺在炉子里的"——所以这里
        //    必须能把烧不动的东西拿回来，否则老存档里那口炉子会继续毒死她。
        //    判据用 hasRecipeRaw（**不看**玩家那四张清单）：这里问的是"炉子能不能消化
        //    它"，不是"我准不准放它"——玩家自己放进去正常烧的矿石不能被误取回。
        ItemStack stuck = furnace.m_8020_(0);
        if (!canSmeltHere(level, be, stuck)) {
            ItemStack taken = furnace.m_8016_(0);
            ItemStack left = ItemHandlerHelper.insertItemStacked(maidInv, taken, false);
            if (!left.m_41619_() && this.furnacePos != null) {
                net.minecraft.world.level.block.Block.m_49840_(level, this.furnacePos, left);
            }
            this.logRound(maid, beName, "evict", "槽0取出烧不动的东西=" + taken);
            changed = true;
        }
        return changed;
    }

    /** v1.2.5 实测六百五十三：这型炉子自己能不能消化这件东西——只看配方，既不看玩家的
     *  四张清单，也不看那几个开关（都是"炉子能不能烧"的事实问题，不是"我允不允许"）。 */
    private static boolean canSmeltHere(ServerLevel level, BlockEntity be, ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return true; // 空槽不算"卡住"
        }
        if (be instanceof net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity) {
            return hasRecipeRaw(level, stack, net.minecraft.world.item.crafting.RecipeType.f_44109_);
        }
        if (be instanceof net.minecraft.world.level.block.entity.SmokerBlockEntity) {
            return hasRecipeRaw(level, stack, net.minecraft.world.item.crafting.RecipeType.f_44110_);
        }
        return hasRecipeRaw(level, stack, net.minecraft.world.item.crafting.RecipeType.f_44108_);
    }

    /** v1.2.5 实测六百五十三：通用可烧制物（圆石/沙子/模组粗矿等）现在还留着喂料通道吗——
     *  「熔炉烧矿物」或「烧任何可烧制物」任一开着即可（前者管矿物标签那一档，后者管通用
     *  回退那一档）。两个都关掉时，选炉/匹配探针就不该再把这些东西算成"有活干"：否则她
     *  会绑上炉子，而喂料路径全被开关挡住 → 坐在炉前干等（玩家视角同样是"卡死"）。 */
    private static boolean smeltAnyAllowed() {
        return com.maidsmart.config.MaidSmartConfig.MISC_COOK_SMELT_ORES.get()
                || com.maidsmart.config.MaidSmartConfig.MISC_COOK_SMELT_ANY.get();
    }

    /** v1.2.5 实测六百五十一：从女仆背包取 1 件**真能在这个世界烧**的白名单食材
     *  （{@link #isCookFood} = 在白名单里 且 有烟熏或熔炉配方）。
     *  旧版是"白名单里有就取"，见 isCookFood 的注释——胡萝卜就是这么被喂进炉子的。 */
    private ItemStack extractCookFood(ServerLevel level, EntityMaid maid, IItemHandler maidInv) {
        // v1.2.4 实测六百三十九【先判背包，再判精妙背包】：自己背包里没有 → 先请 TLM 从
        // 精妙背包/旅行者背包把食材搬一组进来（pull 先扫她自己的背包，有就什么都不做）
        com.maidsmart.tool.MaidExtraContainer.pull(maid, s -> isCookFood(level, s), 1);
        for (int i = 0; i < maidInv.getSlots(); i++) {
            ItemStack stack = maidInv.getStackInSlot(i);
            if (isCookFood(level, stack)) {
                return maidInv.extractItem(i, 1, false);
            }
        }
        return ItemStack.f_41583_;
    }

    /** v1.1.0 实测一百五十七：物品是否带矿物/原料标签——标签路径含 ores 或
     *  raw_materials（forge:ores、forge:ores/*、minecraft:*_ores、forge:raw_materials 等）。
     *
     *  v1.2.5 实测六百五十一【顺手修掉：一直查错字段】：官方映射+tsrg 链式实证
     *  {@code m_135827_ = getNamespace}、{@code m_135815_ = getPath}。旧版取的是
     *  **命名空间**（forge / minecraft / 模组 id），而"ores"/"raw_materials"住在
     *  **路径**里（{@code forge:ores/iron} 的 path 是 {@code ores/iron}）——
     *  命名空间永远不含这两个词，于是本方法**恒返回 false**：矿物永远判不出来。
     *  后果只是"矿物优先"失效（回退到通用可烧制物那条路照样能烧矿石），
     *  所以一直没被发现；既然在改这个文件就一并按注释的原意修回来。
     */
    private static boolean hasOreTag(Item item) {
        try {
            return item.m_204114_().m_203616_().anyMatch(t -> {
                String path = t.f_203868_().m_135815_();
                return path.contains("ores") || path.contains("raw_materials");
            });
        } catch (Exception ignored) {
            return false;
        }
    }

    /* ==================== v1.2.5 实测六百五十二：烧制清单（面板可编辑的四张名单） ==================== */

    /**
     * 四张清单（默认全空 = 行为与旧版**一字不变**）：
     * 只烧这些 / 禁止烧制 / 只用这些燃料 / 禁用燃料。
     *
     * ── 为什么要有 ──
     * 以前"她该烧什么、该拿什么当柴"全写在代码里（食材白名单 + 矿物标签 + 通用回退 + 按燃烧时长
     * 自动评分），玩家想"别拿我的钻石去烧""只许用煤炭当柴"只能改代码。挖矿/伐木/喂食都有
     * 面板可编辑名单，烧制独缺一套——这四张表把它补齐（面板：生产与工作 → 烹饪与酿造 → 烧制清单）。
     *
     * ── 语义（一条说清）──
     * **禁止永远优先**；**允许清单非空 = 只在这些里挑**，留空 = 自动（旧行为）。
     *
     * ── 一条硬约束：允许清单只"缩小"，绝不"放开" ──
     * 判定挂在 {@link #hasRecipeRaw} **之上**（见 {@link #hasRecipe}），所以清单里就算写了一个
     * 原版根本烧不动的物品，也不会让她把炉子占住——那正是实测六百五十一 刚修掉的毛病
     *（"抱着一个烧不动的炉子卡死不动"）。清单改的是"挑哪些"，不是"能不能烧"。
     *
     * ── 生效时机 ──
     * 与挖矿矿表/伐木木材表同一套路：面板保存时 {@link #loadCookLists()} 重建，
     * 首次用到时懒加载（配置在静态初始化期读不到）。手改 toml 文件后需重开面板或重启游戏。
     */
    private static final java.util.Set<Item> SMELT_ALLOW = new java.util.HashSet<>();
    private static final java.util.Set<Item> SMELT_DENY = new java.util.HashSet<>();
    private static final java.util.Set<Item> FUEL_ALLOW = new java.util.HashSet<>();
    private static final java.util.Set<Item> FUEL_DENY = new java.util.HashSet<>();
    /** 是否已装载（照 MaidMineBehavior.ensureCustomOres 的懒加载套路） */
    private static boolean cookListsLoaded = false;

    /** 面板保存时调用：清空并按注册名重建四张清单（与挖矿 loadCustomOres / 伐木 loadCustomWoods 同一套路） */
    public static void loadCookLists() {
        try {
            SMELT_ALLOW.clear();
            SMELT_DENY.clear();
            FUEL_ALLOW.clear();
            FUEL_DENY.clear();
            fillCookList(SMELT_ALLOW, com.maidsmart.config.MaidSmartConfig.MISC_COOK_SMELT_ALLOW::get);
            fillCookList(SMELT_DENY, com.maidsmart.config.MaidSmartConfig.MISC_COOK_SMELT_DENY::get);
            fillCookList(FUEL_ALLOW, com.maidsmart.config.MaidSmartConfig.MISC_COOK_FUEL_ALLOW::get);
            fillCookList(FUEL_DENY, com.maidsmart.config.MaidSmartConfig.MISC_COOK_FUEL_DENY::get);
            cookListsLoaded = true;
        } catch (Throwable ignored) {
            // 配置尚未就绪：四张表保持空（= 全自动判定），下次用到再试
        }
    }

    private static void ensureCookLists() {
        if (!cookListsLoaded) {
            loadCookLists();
        }
    }

    /** 把配置里的 id 清单解析成 Item 集合（解析不出来的条目静默跳过，与挖矿矿表同口径） */
    private static void fillCookList(java.util.Set<Item> out,
                                     java.util.function.Supplier<java.util.List<? extends String>> cfg) {
        try {
            for (String id : cfg.get()) {
                if (id == null || id.isBlank()) {
                    continue;
                }
                Item it = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(id.trim()));
                if (it != null) {
                    out.add(it);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 该物品是否允许当**原料**进炉子（禁止优先；允许清单非空 = 只认清单） */
    private static boolean smeltListed(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return false;
        }
        ensureCookLists();
        Item it = stack.m_41720_();
        if (SMELT_DENY.contains(it)) {
            return false;
        }
        return SMELT_ALLOW.isEmpty() || SMELT_ALLOW.contains(it);
    }

    /** 该物品是否允许当**燃料**（禁止优先；允许清单非空 = 只认清单） */
    private static boolean fuelListed(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return false;
        }
        ensureCookLists();
        Item it = stack.m_41720_();
        if (FUEL_DENY.contains(it)) {
            return false;
        }
        return FUEL_ALLOW.isEmpty() || FUEL_ALLOW.contains(it);
    }

    /** v1.1.0 实测一百五十七：当前世界是否有该物品的指定类型炉子配方（熔炉/高炉/
     *  烟熏炉，用配方管理器查询，模组自定义配方同样生效）。probe 声明为 Container
     *  类型——getRecipeFor 的泛型 C 按实参静态类型推断，SimpleContainer 推不出
     *  Recipe<Container> 的约束。
     *
     *  v1.2.5 实测六百五十二：这是**不看烧制清单**的原始判定——只有燃料那条路
     *  （判断"它本来算不算可烧制燃料"）用它：那里问的是"它自己能不能熔"，
     *  不该被玩家的原料白名单/黑名单改写，否则"把木材列进禁止烧制"会反过来
     *  把它算成"纯燃料"优先烧掉，与玩家意图正好相反。 */
    private static <T extends net.minecraft.world.item.crafting.AbstractCookingRecipe>
    boolean hasRecipeRaw(ServerLevel level, ItemStack stack,
                         net.minecraft.world.item.crafting.RecipeType<T> type) {
        try {
            net.minecraft.world.Container probe = new net.minecraft.world.SimpleContainer(1);
            probe.m_6836_(0, stack);
            return level.m_7465_().m_44015_(type, probe, level).isPresent();
        } catch (Exception ignored) {
            return false;
        }
    }

    /** v1.2.5 实测六百五十二：**原料判定总闸** = 烧制清单 + 原始配方。
     *  所有"她能不能把这个放进炉子"的路径最终都落到这里（食材/矿物/通用回退/烟熏炉/高炉，
     *  以及选炉型与绑定成立性那几处探针），所以清单只作用于**原料侧**；
     *  燃料侧另有 {@link #fuelListed}，两条线互不干扰。 */
    private static <T extends net.minecraft.world.item.crafting.AbstractCookingRecipe>
    boolean hasRecipe(ServerLevel level, ItemStack stack,
                      net.minecraft.world.item.crafting.RecipeType<T> type) {
        return smeltListed(stack) && hasRecipeRaw(level, stack, type);
    }

    private static boolean isSmeltable(ServerLevel level, ItemStack stack) {
        return hasRecipe(level, stack, net.minecraft.world.item.crafting.RecipeType.f_44108_);
    }

    /**
     * v1.2.5 实测六百五十一【"女仆会烧胡萝卜" + "抱着炉子卡死不动"的根因】：
     * 白名单食材必须**真在当前世界有炉子配方**才算"可烹饪"。
     *
     * 旧版所有"她有没有吃的可烧"的判据都只查 {@code FOODS.contains(item)}——把
     * 硬编码清单当成事实断言。而清单里的
     * {@code carrot / beetroot / brown_mushroom / cactus / dried_kelp} 在原版
     * **没有任何炉子配方**（两份客户端 jar 的 {@code data/minecraft/recipes} 实证：
     * 这五个既无 smelting 也无 smoking；对比 potato/kelp/生肉/鱼都查得到）。
     * 后果是一条链上的两个现象：
     * <ul>
     *   <li>{@link #pickFurnaceKind} 见到胡萝卜就判成 FOOD → 她去占烟熏炉/熔炉；</li>
     *   <li>{@link #processFurnace} 把胡萝卜塞进原料槽（提取路径也不查配方）；</li>
     *   <li>{@link #furnaceMatchesInv} 用的是**同一个**白名单判据 → 恒为真 →
     *       她永远不解绑，抱着一个烧不动的炉子坐到底 = 玩家看到的"卡死不动"。</li>
     * </ul>
     * 实测六百三十七 只治了"附近**找不到**炉子"那条冻结路径；这条"炉子找到了、
     * 但里面根本没有能烧的东西"是另一条，两条都会冻人。
     *
     * 现在白名单只当**优先项**、不当**事实**：要有烟熏或熔炉配方才算数。
     * 模组给这些物品补了配方时照样认（走的都是同一个配方管理器）。
     */
    private static boolean isCookFood(ServerLevel level, ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return false;
        }
        if (!FOODS.contains(stack.m_41720_())) {
            return false;
        }
        return hasRecipe(level, stack, net.minecraft.world.item.crafting.RecipeType.f_44110_)
                || hasRecipe(level, stack, net.minecraft.world.item.crafting.RecipeType.f_44108_);
    }

    /**
     * v1.1.0 实测三百四十九（反馈："女仆在进行烧制的时候，会把附魔的物品拿去
     * 烧掉"）：可烧制 ≠ 可以喂——带附魔（经验修补/耐久/时运等，哪怕只有 1 条）
     * 或耐久未满（用旧过的工具/武器/盔甲）的物品一律不进炉子。三类取料路径
     * （食材白名单外的矿物回退 extractOreFromMaid / 通用可烧制物回退
     * extractAnySmeltable / 烟熏炉·高炉 extractByRecipe）统一在入口拦截。
     * m_41793_ = isEnchanted（附魔表非空，javap 实证查 NBT Enchantments 列表），
     * m_41768_ = isDamaged（当前耐久 &lt; 最大）、m_41776_ = getMaxDamage。
     *
     * v1.2.5 实测六百五十一【这一条以前**从来没生效过**】：旧版写的是
     * {@code m_41753_() && m_41773_() > 0}，而按官方映射（obf→Mojmap）+ tsrg 链式
     * 实证：{@code m_41753_ = isStackable}（不是 isDamaged）、
     * {@code m_41773_ = getDamageValue}（不是 getMaxDamage）。
     * {@code isStackable() && getDamageValue() > 0} 在原版几乎恒为假——
     * 能堆叠的物品根本不吃耐久，吃耐久的（工具/盔甲）又都是单件不可堆叠，
     * 于是"用旧的耐久物品不熔"这道闸等于不存在：**她会把自己用过的铁剑之类
     * 直接丢进炉子**（实测三百四十九 报的是附魔物品，附魔那半条由 isEnchanted
     * 挡住了，这半条一直漏着）。现在照注释的原意写回来。
     */
    private static boolean isSafeToFeed(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return false;
        }
        if (stack.m_41793_()) {
            return false; // 附魔物品永不熔（原版/模组配方都可能烧掉它）
        }
        if (stack.m_41768_() && stack.m_41776_() > 0) {
            return false; // 用旧过的耐久物品不熔（最大耐久 > 0 才算耐久物品）
        }
        return true;
    }

    /** v1.1.0 实测一百五十八：烟熏炉/高炉按各自配方类型取料——
     *  烟熏炉 = 有烟熏配方的物品（生食）；高炉 = 有高炉配方的物品（矿石/粗金属，
     *  受「熔炉烧矿物」开关约束——高炉只烧矿物，开关关掉时高炉只收成品/补燃料）。 */
    private ItemStack extractForFurnaceType(ServerLevel level, EntityMaid maid, IItemHandler maidInv, BlockEntity be) {
        if (be instanceof net.minecraft.world.level.block.entity.SmokerBlockEntity) {
            return this.extractByRecipe(level, maid, maidInv,
                    net.minecraft.world.item.crafting.RecipeType.f_44110_);
        }
        if (be instanceof net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity) {
            if (!com.maidsmart.config.MaidSmartConfig.MISC_COOK_SMELT_ORES.get()) {
                return ItemStack.f_41583_;
            }
            return this.extractByRecipe(level, maid, maidInv,
                    net.minecraft.world.item.crafting.RecipeType.f_44109_);
        }
        return ItemStack.f_41583_;
    }

    /** v1.1.0 实测一百五十八：从女仆背包取 1 个有指定炉子配方的物品。 */
    private <T extends net.minecraft.world.item.crafting.AbstractCookingRecipe>
    ItemStack extractByRecipe(ServerLevel level, EntityMaid maid, IItemHandler maidInv,
                              net.minecraft.world.item.crafting.RecipeType<T> type) {
        // v1.2.4 实测六百三十九【先判背包，再判精妙背包】：同上（烟熏炉/高炉那条路）
        com.maidsmart.tool.MaidExtraContainer.pull(maid,
                s -> !s.m_41619_() && isSafeToFeed(s) && hasRecipe(level, s, type), 1);
        for (int i = 0; i < maidInv.getSlots(); i++) {
            ItemStack stack = maidInv.getStackInSlot(i);
            if (stack.m_41619_() || !isSafeToFeed(stack)) {
                continue;
            }
            if (hasRecipe(level, stack, type)) {
                return maidInv.extractItem(i, 1, false);
            }
        }
        return ItemStack.f_41583_;
    }

    /** v1.1.0 实测一百五十七：从女仆背包取 1 个矿物类可烧制物（带矿物标签且
     *  有熔炉配方）。食材优先顺序由调用侧保证（先 FOODS 后本方法）。 */
    private ItemStack extractOreFromMaid(ServerLevel level, EntityMaid maid, IItemHandler maidInv) {
        if (!com.maidsmart.config.MaidSmartConfig.MISC_COOK_SMELT_ORES.get()) {
            return ItemStack.f_41583_;
        }
        // v1.2.4 实测六百三十九【先判背包，再判精妙背包】：同上（矿物类可烧制物）
        com.maidsmart.tool.MaidExtraContainer.pull(maid,
                s -> !s.m_41619_() && isSafeToFeed(s) && !FOODS.contains(s.m_41720_())
                        && hasOreTag(s.m_41720_()) && isSmeltable(level, s), 1);
        for (int i = 0; i < maidInv.getSlots(); i++) {
            ItemStack stack = maidInv.getStackInSlot(i);
            if (stack.m_41619_() || !isSafeToFeed(stack) || FOODS.contains(stack.m_41720_())) {
                continue;
            }
            if (hasOreTag(stack.m_41720_()) && isSmeltable(level, stack)) {
                return maidInv.extractItem(i, 1, false);
            }
        }
        return ItemStack.f_41583_;
    }

    /**
     * v1.1.0 实测一百八十二：通用可烧制物回退——背包里没有白名单食材/矿物标签
     * 物品时，喂任何【当前世界有熔炉配方 且 非装备类】的物品（沙子→玻璃、
     * 圆石→石头、原木→木炭、模组食材、无矿物标签的模组粗矿等）。
     * 装备类排除（TieredItem=剑镐斧锹锄 / ArmorItem=盔甲 / TridentItem / ShieldItem）：
     * 铁金钻石质工具盔甲在原版有"烧成粒"配方，绝不能把女仆自己的装备熔掉。
     * 开关 misc.cookSmeltAny（默认开）；关闭 = 一百五十七旧行为。
     */
    private ItemStack extractAnySmeltable(ServerLevel level, EntityMaid maid, IItemHandler maidInv) {
        if (!com.maidsmart.config.MaidSmartConfig.MISC_COOK_SMELT_ANY.get()) {
            return ItemStack.f_41583_;
        }
        // v1.2.4 实测六百三十九【先判背包，再判精妙背包】：同上（通用可烧制物回退）
        com.maidsmart.tool.MaidExtraContainer.pull(maid,
                s -> !s.m_41619_() && isSafeToFeed(s) && !FOODS.contains(s.m_41720_())
                        && !(s.m_41720_() instanceof net.minecraft.world.item.TieredItem)
                        && !(s.m_41720_() instanceof net.minecraft.world.item.ArmorItem)
                        && !(s.m_41720_() instanceof net.minecraft.world.item.TridentItem)
                        && !(s.m_41720_() instanceof net.minecraft.world.item.ShieldItem)
                        && isSmeltable(level, s), 1);
        // v1.1.0 实测二百九十九（反馈："仍然会出现拿木材烧木头的情况，而不是优先
        // 先烧别的物品"）：可烧制物里【不可燃烧的优先】（圆石/沙子/矿石等——烧它们
        // 不会抢燃料），可烧制燃料（原木/木板/树苗——既是原料又是燃料）最后兜底。
        // 旧版按槽位顺序取，原木槽位在前就喂原木 → 原木进原料槽、原木又进燃料槽
        // = "用木头烧木头"。
        ItemStack fallback = ItemStack.f_41583_;
        for (int i = 0; i < maidInv.getSlots(); i++) {
            ItemStack stack = maidInv.getStackInSlot(i);
            if (stack.m_41619_() || !isSafeToFeed(stack) || FOODS.contains(stack.m_41720_())) {
                continue;
            }
            Item it = stack.m_41720_();
            if (it instanceof net.minecraft.world.item.TieredItem
                    || it instanceof net.minecraft.world.item.ArmorItem
                    || it instanceof net.minecraft.world.item.TridentItem
                    || it instanceof net.minecraft.world.item.ShieldItem) {
                continue; // 装备类永不熔（有烧成粒配方的高价值工具/盔甲）
            }
            if (!isSmeltable(level, stack)) {
                continue;
            }
            if (net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity
                    .m_58399_(stack)) {
                // 可烧制燃料（原木/木板/树苗）——留作兜底
                if (fallback.m_41619_()) {
                    fallback = stack.m_41777_();
                }
                continue;
            }
            return maidInv.extractItem(i, 1, false); // 不可燃烧的可烧制物：优先
        }
        if (!fallback.m_41619_()) {
            for (int i = 0; i < maidInv.getSlots(); i++) {
                ItemStack stack = maidInv.getStackInSlot(i);
                if (!stack.m_41619_() && stack.m_41720_() == fallback.m_41720_()) {
                    return maidInv.extractItem(i, 1, false);
                }
            }
        }
        return ItemStack.f_41583_;
    }

    /** 无料可喂时的背包 dump（30 秒限频/女仆；latest.log 搜 "cook no-feed diag"） */
    private static final Map<java.util.UUID, Long> NO_FEED_DUMP_SINCE = new HashMap<>();

    private void dumpInvOnNoFeed(ServerLevel level, EntityMaid maid,
                                 IItemHandler maidInv, String beName) {
        try {
            long now = level.m_46467_();
            Long last = NO_FEED_DUMP_SINCE.get(maid.m_20148_());
            if (last != null && now - last < 600L) {
                return;
            }
            NO_FEED_DUMP_SINCE.put(maid.m_20148_(), now);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < maidInv.getSlots(); i++) {
                ItemStack st = maidInv.getStackInSlot(i);
                if (st.m_41619_()) {
                    continue;
                }
                ResourceLocation rl = ForgeRegistries.ITEMS.getKey(st.m_41720_());
                sb.append(rl == null ? "?" : rl.toString()).append('x')
                        .append(st.m_41613_()).append(' ');
            }
            LOGGER.info("cook no-feed diag: maid={} be={} inv=[{}]",
                    com.maidsmart.tool.PromaidLog.nameOf(maid), beName, sb);
        } catch (Throwable ignored) {
        }
    }

    /** v1.5.252：燃料 = 背包中【数量最多】的可燃烧物品（原版 isFuel 判定，不限于煤炭）。
     *  v1.1.0 实测二百四十一（反馈："女仆有的时候会使用木头烧木头"）：旧版按数量
     *  最多选——原木/木板/树苗既是燃料又是可烧制原料，伐木女仆背包原木数量碾压
     *  煤炭 → 原木进燃料槽、原木又进原料槽 = "用木头烧木头"。修复：
     *  ① 评分 = 燃烧时长优先（getFuel 映射，煤炭 1600 tick ≫ 原木 300 tick），
     *     同长再按数量——有煤必用煤，不再被数量带偏；
     *  ② 纯燃料优先：可燃烧且【无熔炉配方】（煤炭/木炭/烈焰棒/干海带块/熔岩桶）
     *     先选；背包没有纯燃料才退而选可烧制燃料（原木烧原木总比炉子熄火好）。
     *  v1.1.0 实测三百零一：曾按要求改为"按物品栏摆放顺序取第一个"，实测后
     *  玩家改回评分制（"还是改回按燃烧时长/数量评分吧"）。木材黑名单只拦"当原料
     *  烧"，当燃料不受影响（原木/木板照常可烧炉子）。 */
    private ItemStack extractBestFuel(ServerLevel level, EntityMaid maid, IItemHandler maidInv) {
        // v1.2.4 实测六百三十九【先判背包，再判精妙背包】：自己背包里没有可烧的东西 →
        // 先请 TLM 从精妙背包/旅行者背包搬一组燃料进来（pull 先扫她自己的背包）
        try {
            com.maidsmart.tool.MaidExtraContainer.pull(maid,
                    s -> !s.m_41619_() && isSafeToFeed(s) && fuelListed(s)
                            && net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity
                            .m_58399_(s), -1);
        } catch (Throwable ignored) {
        }
        Map<Item, Integer> burnTicks = new HashMap<>();
        Map<Item, Integer> counts = new HashMap<>();
        for (int i = 0; i < maidInv.getSlots(); i++) {
            ItemStack stack = maidInv.getStackInSlot(i);
            // v1.1.0 实测三百四十九：燃料侧同拦——附魔物品（经验修补的装备/附魔书/
            // 药水箭等）和用旧的耐久物品绝不当柴火烧
            // v1.2.5 实测六百五十二：再过一层烧制清单（禁用燃料永远优先；指定燃料非空则只认清单）
            if (stack.m_41619_() || !isSafeToFeed(stack) || !fuelListed(stack)
                    || !net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity
                    .m_58399_(stack)) {
                continue;
            }
            counts.merge(stack.m_41720_(), stack.m_41613_(), Integer::sum);
            Integer ticks = net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity
                    .m_58423_().get(stack.m_41720_());
            burnTicks.putIfAbsent(stack.m_41720_(), ticks == null ? 0 : ticks);
        }
        if (counts.isEmpty()) {
            return ItemStack.f_41583_;
        }
        Item best = null;
        int bestScore = Integer.MIN_VALUE;
        // 第一轮：纯燃料（可燃烧且不可烧制）——煤炭/木炭/烈焰棒/干海带块/熔岩桶
        for (Item it : counts.keySet()) {
            // v1.2.5 实测六百五十二：这里问的是"它自己能不能熔"，走**不看清单**的原始判定——
            // 否则"把木材列入禁止烧制"会把它算成纯燃料反而优先烧掉，与玩家意图相反
            if (hasRecipeRaw(level, new ItemStack(it),
                    net.minecraft.world.item.crafting.RecipeType.f_44108_)) {
                continue; // 可烧制燃料（原木/木板/树苗）留到第二轮兜底
            }
            int score = burnTicks.getOrDefault(it, 0) * 100000 + counts.get(it);
            if (score > bestScore) {
                bestScore = score;
                best = it;
            }
        }
        if (best == null) {
            // 第二轮：没有纯燃料 → 可烧制燃料兜底（原木烧原木总比炉子熄火好）
            for (Item it : counts.keySet()) {
                int score = burnTicks.getOrDefault(it, 0) * 100000 + counts.get(it);
                if (score > bestScore) {
                    bestScore = score;
                    best = it;
                }
            }
        }
        if (best == null) {
            return ItemStack.f_41583_;
        }
        for (int i = 0; i < maidInv.getSlots(); i++) {
            ItemStack stack = maidInv.getStackInSlot(i);
            if (!stack.m_41619_() && stack.m_41720_() == best) {
                return maidInv.extractItem(i, 1, false);
            }
        }
        return ItemStack.f_41583_;
    }

    private BlockPos findFurnace(ServerLevel level, EntityMaid maid) {
        // v1.1.0 实测三百（反馈："先扫物品栏，依次检索可烧制的物品……遇到食物时，
        // 在附近搜索烟熏炉和熔炉。烟熏炉优先级高于熔炉。遇到矿物时搜索高炉和熔炉。
        // 如果是仅能通过熔炉烧制的物品，则仅用熔炉进行烧制"）：按背包物品类型选炉子
        // ——食物 → 烟熏炉优先（其次熔炉）；无食物但有高炉可烧物（矿物）→ 高炉优先
        // （其次熔炉）；只有仅熔炉可烧物 → 只选熔炉。背包里既没有可烧制物也没有
        // 燃料时返回 null（站桩等待，不白跑）。
        FurnaceKind kind = pickFurnaceKind(level, maid);
        if (kind == FurnaceKind.NONE) {
            return null;
        }
        BlockPos pos = maid.m_20183_();
        for (int dy = -com.maidsmart.config.MaidSmartConfig.MISC_VERTICAL_RANGE.get();
             dy <= com.maidsmart.config.MaidSmartConfig.MISC_VERTICAL_RANGE.get(); dy++) {
            for (int dx = -cookRadius(); dx <= cookRadius(); dx++) {
                for (int dz = -cookRadius(); dz <= cookRadius(); dz++) {
                    BlockPos p = pos.m_7918_(dx, dy, dz);
                    BlockState bs = level.m_8055_(p);
                    if (!(bs.m_60734_() instanceof AbstractFurnaceBlock)) {
                        continue;
                    }
                    // 炉型匹配：烟熏炉只给食物、高炉只给矿物、熔炉通用
                    if (bs.m_60734_() instanceof net.minecraft.world.level.block.SmokerBlock
                            && kind != FurnaceKind.FOOD) {
                        continue;
                    }
                    if (bs.m_60734_() instanceof net.minecraft.world.level.block.BlastFurnaceBlock
                            && kind != FurnaceKind.ORE) {
                        continue;
                    }
                    // v1.1.0 实测一百五十八：开关关闭 = 只操作熔炉（旧行为）
                    if (!com.maidsmart.config.MaidSmartConfig.MISC_COOK_SMOKER_BLAST.get()
                            && !(bs.m_60734_() instanceof net.minecraft.world.level.block.FurnaceBlock)) {
                        continue;
                    }
                    // v1.1.0 实测一百六十八：跳过被【其他女仆】占用的炉子（自己
                    // 占的不跳）——多个女仆分散到不同炉子，不挤同一个；占用者
                    // 已死/已移除 → 懒清理该占用
                    String k = furnaceKey(level, p);
                    java.util.UUID owner = FURNACE_USERS.get(k);
                    if (owner != null && !owner.equals(maid.m_20148_())) {
                        net.minecraft.world.entity.Entity o = level.m_8791_(owner);
                        if (o == null || !o.m_6084_()) {
                            FURNACE_USERS.remove(k); // 占用者没了 → 释放
                        } else {
                            continue; // 别的女仆在用 → 换下一个炉子
                        }
                    }
                    return p;
                }
            }
        }
        return null;
    }

    /** 炉型偏好：NONE=无可烧制物（不找炉子）/ FOOD=食物（烟熏炉优先）/
     *  ORE=矿物（高炉优先）/ ANY=仅熔炉可烧物（只选熔炉） */
    private enum FurnaceKind { NONE, FOOD, ORE, ANY }

    /** v1.1.0 实测三百：按背包物品类型决定炉型偏好——先扫物品栏，依次检索可烧制
     *  的物品：遇到食物 → FOOD（烟熏炉优先）；遇到矿物（有高炉配方）→ ORE（高炉
     *  优先）；都没有 → 有仅熔炉可烧物 → ANY（只选熔炉）。燃料不算可烧制物
     *  （燃料是烧炉子的，不是被烧的）。 */
    private FurnaceKind pickFurnaceKind(ServerLevel level, EntityMaid maid) {
        try {
            IItemHandler inv = maid.getAvailableBackpackInv();
            boolean anySmeltable = false;
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (stack.m_41619_()) {
                    continue;
                }
                Item it = stack.m_41720_();
                if (isCookFood(level, stack)) {
                    return FurnaceKind.FOOD;
                }
                if (it instanceof net.minecraft.world.item.TieredItem
                        || it instanceof net.minecraft.world.item.ArmorItem
                        || it instanceof net.minecraft.world.item.TridentItem
                        || it instanceof net.minecraft.world.item.ShieldItem) {
                    continue; // 装备类永不熔
                }
                if (isWood(it)) {
                    continue; // 木材黑名单（默认不烧，开关开启才烧）
                }
                // v1.2.5 实测六百五十三：「熔炉烧矿物」关掉时高炉喂料路径也是关的
                // （extractForFurnaceType 里那道门）——探针就别再算它，免得绑上高炉干坐
                if (com.maidsmart.config.MaidSmartConfig.MISC_COOK_SMELT_ORES.get()
                        && hasRecipe(level, stack, net.minecraft.world.item.crafting.RecipeType.f_44109_)) {
                    return FurnaceKind.ORE; // 有高炉配方（矿物/粗金属）→ 高炉优先
                }
                if (smeltAnyAllowed() && isSmeltable(level, stack)) {
                    anySmeltable = true; // 仅熔炉可烧物（圆石/沙子等）
                }
            }
            // v1.2.4 实测六百三十九【先判背包，再判精妙背包】：自己背包里判断不出炉型时再看
            // 精妙背包/旅行者背包（只读探针）。这一步不能省——否则"背包空、精妙背包里一堆生肉"
            // 的女仆会判成 NONE → 连炉子都不去找 → 站桩（也就永远走不到"取物"那一步）。
            // 口径与上面的循环逐条对齐（食材 → 高炉矿物 → 熔炉通用物）。
            if (com.maidsmart.tool.MaidExtraContainer.contains(maid,
                    s -> isCookFood(level, s))) {
                return FurnaceKind.FOOD;
            }
            if (com.maidsmart.config.MaidSmartConfig.MISC_COOK_SMELT_ORES.get()
                    && com.maidsmart.tool.MaidExtraContainer.contains(maid,
                    s -> !s.m_41619_() && isSafeToFeed(s) && !FOODS.contains(s.m_41720_())
                            && !(s.m_41720_() instanceof net.minecraft.world.item.TieredItem)
                            && !(s.m_41720_() instanceof net.minecraft.world.item.ArmorItem)
                            && !(s.m_41720_() instanceof net.minecraft.world.item.TridentItem)
                            && !(s.m_41720_() instanceof net.minecraft.world.item.ShieldItem)
                            && !isWood(s.m_41720_())
                            && hasRecipe(level, s, net.minecraft.world.item.crafting.RecipeType.f_44109_))) {
                return FurnaceKind.ORE;
            }
            if (!anySmeltable && smeltAnyAllowed()
                    && com.maidsmart.tool.MaidExtraContainer.contains(maid,
                    s -> !s.m_41619_() && isSafeToFeed(s) && !FOODS.contains(s.m_41720_())
                            && !(s.m_41720_() instanceof net.minecraft.world.item.TieredItem)
                            && !(s.m_41720_() instanceof net.minecraft.world.item.ArmorItem)
                            && !(s.m_41720_() instanceof net.minecraft.world.item.TridentItem)
                            && !(s.m_41720_() instanceof net.minecraft.world.item.ShieldItem)
                            && !isWood(s.m_41720_()) && isSmeltable(level, s))) {
                anySmeltable = true;
            }
            return anySmeltable ? FurnaceKind.ANY : FurnaceKind.NONE;
        } catch (Throwable ignored) {
            return FurnaceKind.NONE;
        }
    }

    /** v1.1.0 实测三百：木材类判定（原木/木板/树苗/竹等）——BlockItem 且方块是
     *  RotatedPillarBlock（原木/竹）/ LeavesBlock（树叶）/ 树苗（SaplingBlock）。
     *  木材默认黑名单不烧（开关 misc.cookBurnWood 开启才放行）。 */
    private static boolean isWood(Item item) {
        if (!com.maidsmart.config.MaidSmartConfig.MISC_COOK_BURN_WOOD.get()) {
            if (item instanceof net.minecraft.world.item.BlockItem) {
                net.minecraft.world.level.block.Block b = ((net.minecraft.world.item.BlockItem) item).m_40614_();
                if (b instanceof net.minecraft.world.level.block.RotatedPillarBlock
                        || b instanceof net.minecraft.world.level.block.LeavesBlock
                        || b instanceof net.minecraft.world.level.block.SaplingBlock) {
                    return true;
                }
            }
        }
        return false;
    }

    /** v1.1.0 实测三百：已绑定的炉子与背包是否匹配——烟熏炉要求背包有食物、
     *  高炉要求背包有矿物（高炉配方可烧物）、熔炉要求背包有任一可烧制物
     *  （食物/矿物/仅熔炉可烧物）。不匹配 → 取消绑定重新找。 */
    private boolean furnaceMatchesInv(ServerLevel level, EntityMaid maid, BlockPos pos) {
        try {
            BlockState bs = level.m_8055_(pos);
            IItemHandler inv = maid.getAvailableBackpackInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (stack.m_41619_()) {
                    continue;
                }
                Item it = stack.m_41720_();
                if (isCookFood(level, stack)) {
                    if (bs.m_60734_() instanceof net.minecraft.world.level.block.SmokerBlock
                            || bs.m_60734_() instanceof net.minecraft.world.level.block.FurnaceBlock) {
                        return true;
                    }
                    continue;
                }
                if (it instanceof net.minecraft.world.item.TieredItem
                        || it instanceof net.minecraft.world.item.ArmorItem
                        || it instanceof net.minecraft.world.item.TridentItem
                        || it instanceof net.minecraft.world.item.ShieldItem) {
                    continue; // 装备类永不熔
                }
                if (isWood(it)) {
                    continue; // 木材黑名单
                }
                if (bs.m_60734_() instanceof net.minecraft.world.level.block.BlastFurnaceBlock) {
                    // v1.2.5 实测六百五十三：「熔炉烧矿物」关掉 = 高炉喂料门也关（与喂料侧同口径）
                    if (com.maidsmart.config.MaidSmartConfig.MISC_COOK_SMELT_ORES.get()
                            && hasRecipe(level, stack, net.minecraft.world.item.crafting.RecipeType.f_44109_)) {
                        return true; // 高炉：有高炉配方可烧物
                    }
                    continue;
                }
                if (smeltAnyAllowed() && isSmeltable(level, stack)) {
                    return true; // 熔炉：任一可烧制物
                }
            }
            // v1.2.4 实测六百三十九【先判背包，再判精妙背包】：自己背包里没有这型炉子能烧的
            // 东西 → 再看精妙背包/旅行者背包（只读探针）。不跟着放宽的话，会出现"背包空、
            // 精妙背包里有料"时她反复解绑炉子重新找（bind/unbind 空转），一路站桩。
            // 口径与上面的循环逐条对齐：烟熏炉/熔炉 ← 食材；高炉 ← 高炉配方；熔炉 ← 任一可烧物。
            boolean smokerOrFurnace = bs.m_60734_() instanceof net.minecraft.world.level.block.SmokerBlock
                    || bs.m_60734_() instanceof net.minecraft.world.level.block.FurnaceBlock;
            boolean blast = bs.m_60734_() instanceof net.minecraft.world.level.block.BlastFurnaceBlock;
            if (smokerOrFurnace && com.maidsmart.tool.MaidExtraContainer.contains(maid,
                    s -> isCookFood(level, s))) {
                return true;
            }
            if (blast && com.maidsmart.config.MaidSmartConfig.MISC_COOK_SMELT_ORES.get()
                    && com.maidsmart.tool.MaidExtraContainer.contains(maid,
                    s -> !s.m_41619_() && isSafeToFeed(s) && !FOODS.contains(s.m_41720_())
                            && !(s.m_41720_() instanceof net.minecraft.world.item.TieredItem)
                            && !(s.m_41720_() instanceof net.minecraft.world.item.ArmorItem)
                            && !(s.m_41720_() instanceof net.minecraft.world.item.TridentItem)
                            && !(s.m_41720_() instanceof net.minecraft.world.item.ShieldItem)
                            && !isWood(s.m_41720_())
                            && hasRecipe(level, s, net.minecraft.world.item.crafting.RecipeType.f_44109_))) {
                return true;
            }
            if (!blast && smeltAnyAllowed()
                    && com.maidsmart.tool.MaidExtraContainer.contains(maid,
                    s -> !s.m_41619_() && isSafeToFeed(s) && !FOODS.contains(s.m_41720_())
                            && !(s.m_41720_() instanceof net.minecraft.world.item.TieredItem)
                            && !(s.m_41720_() instanceof net.minecraft.world.item.ArmorItem)
                            && !(s.m_41720_() instanceof net.minecraft.world.item.TridentItem)
                            && !(s.m_41720_() instanceof net.minecraft.world.item.ShieldItem)
                            && !isWood(s.m_41720_()) && isSmeltable(level, s))) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
