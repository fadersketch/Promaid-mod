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
 * - 处理间隔 100 tick（5 秒），不瞬间完成烹饪（炉子自身进度驱动）
 * - v1.5.252：绑定炉子并到达后立刻坐下不动；行为停止/炉子丢失恢复站立
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

    /** 可烹饪食材白名单（原版熔炉可烧食物） */
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
        addItem(FOODS, "minecraft:beetroot");
        addItem(FOODS, "minecraft:carrot");
        addItem(FOODS, "minecraft:brown_mushroom");
        addItem(FOODS, "minecraft:cactus");
        addItem(FOODS, "minecraft:dried_kelp");
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
    /** v1.1.0 实测一百六十八：炉子占用表（维度|坐标 → 占用女仆 UUID）——多个女仆同时
     *  在场时各自绑定不同炉子，避免全挤到第一个炉子上（用户："两个女仆三个炉子，
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
        LOGGER.info("cook start: maid={} furnace={}",
                com.maidsmart.tool.PromaidLog.nameOf(maid), this.furnacePos);
    }

    @Override
    protected void m_6725_(ServerLevel level, EntityMaid maid, long gameTime) {
        if (this.furnacePos == null) {
            // v1.5.24：找不到熔炉时站桩等待（不乱跑），节流扫描防每 tick 全量查询
            MaidWorkTags.setStill(maid, true);
            this.standUp(maid);
            if (this.scanCooldown-- > 0) {
                return;
            }
            this.scanCooldown = 20;
            this.furnacePos = this.findFurnace(level, maid);
            if (this.furnacePos == null) {
                return;
            }
            // v1.1.0 实测一百六十八：绑定成功 → 登记炉子占用（多女仆分散）
            this.claimFurnace(level, maid, this.furnacePos);
        }
        BlockState state = level.m_8055_(this.furnacePos);
        if (!(state.m_60734_() instanceof AbstractFurnaceBlock)) {
            this.furnacePos = null;
            this.releaseFurnace(); // v1.1.0 实测一百六十八：炉子没了 → 释放占用
            this.standUp(maid);
            MaidWorkTags.setStill(maid, true); // 熔炉没了：继续站桩等扫描
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
            this.processFurnace(level, maid, (Container) be, be);
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

    private void processFurnace(ServerLevel level, EntityMaid maid, Container furnace, BlockEntity be) {
        IItemHandler maidInv = maid.getMaidInv();
        String beName = be == null ? "null" : be.getClass().getSimpleName();
        // 1. 收取成品
        ItemStack result = furnace.m_8020_(2);
        if (!result.m_41619_()) {
            ItemStack taken = furnace.m_8016_(2);
            ItemStack left = ItemHandlerHelper.insertItemStacked(maidInv, taken, false);
            if (!left.m_41619_()) {
                furnace.m_6836_(2, left);
            }
            this.logRound(maid, beName, "output", "tookOutput=" + taken);
        }
        // 2. 补食材（槽 0 空）
        if (furnace.m_8020_(0).m_41619_()) {
            ItemStack input = ItemStack.f_41583_;
            if (be instanceof FurnaceBlockEntity) {
                input = this.extractFromMaid(maidInv, FOODS, 1);
                if (input.m_41619_()) {
                    // v1.1.0 实测一百五十七：没有食材时兼容矿物类可烧制物
                    //（带矿物/原料标签且当前世界有熔炉配方：铁矿石/粗铁/金矿石等）
                    input = this.extractOreFromMaid(level, maidInv);
                }
                if (input.m_41619_()) {
                    // v1.1.0 实测一百八十二：仍没有 → 通用可烧制物回退——凡当前世界
                    // 有熔炉配方且非装备类的物品都喂（沙子/圆石/原木/模组食材/无矿物
                    // 标签的模组粗矿等）。旧版白名单+矿物标签不认的东西卡死补料，
                    // 表现为"只投一次燃料就再也不喂"（用户实测第 2 只女仆）
                    input = this.extractAnySmeltable(level, maidInv);
                }
            } else {
                // v1.1.0 实测一百五十八：烟熏炉/高炉——按各自配方类型取可烧制物
                input = this.extractForFurnaceType(level, maidInv, be);
            }
            if (!input.m_41619_()) {
                furnace.m_6836_(0, input);
                this.logRound(maid, beName, "fed", "fedSlot0=" + input);
            } else {
                // v1.1.0 实测一百八十六：迁移式记录（同状态只打一条，防刷屏）——
                // 进入"无料可喂"状态时记一条 + 背包 dump（30 秒限频）
                if (this.logRound(maid, beName, "nofeed",
                        "slot0Empty但无料可喂（背包无食材/矿物或配方不匹配）")) {
                    this.dumpInvOnNoFeed(level, maid, maidInv, beName);
                }
            }
        }
        // 3. 补燃料（槽 1 空）——v1.5.252：不限于煤炭，选背包中数量最多的可燃烧物品
        // v1.1.0 实测二百四十一：纯燃料优先（燃烧时长评分，煤炭/木炭/烈焰棒等），
        // 没有纯燃料才退而选可烧制燃料（原木/木板）——不再"用木头烧木头"
        if (furnace.m_8020_(1).m_41619_()) {
            ItemStack fuel = this.extractBestFuel(level, maidInv);
            if (!fuel.m_41619_()) {
                furnace.m_6836_(1, fuel);
            }
        }
    }

    private ItemStack extractFromMaid(IItemHandler maidInv, Set<Item> whitelist, int count) {
        for (int i = 0; i < maidInv.getSlots(); i++) {
            ItemStack stack = maidInv.getStackInSlot(i);
            if (!stack.m_41619_() && whitelist.contains(stack.m_41720_())) {
                return maidInv.extractItem(i, count, false);
            }
        }
        return ItemStack.f_41583_;
    }

    /** v1.1.0 实测一百五十七：物品是否带矿物/原料标签——标签路径含 ores 或
     *  raw_materials（forge:ores、forge:ores/*、minecraft:*_ores、forge:raw_materials 等）。 */
    private static boolean hasOreTag(Item item) {
        try {
            return item.m_204114_().m_203616_().anyMatch(t -> {
                String path = t.f_203868_().m_135827_();
                return path.contains("ores") || path.contains("raw_materials");
            });
        } catch (Exception ignored) {
            return false;
        }
    }

    /** v1.1.0 实测一百五十七：当前世界是否有该物品的指定类型炉子配方（熔炉/高炉/
     *  烟熏炉，用配方管理器查询，模组自定义配方同样生效）。probe 声明为 Container
     *  类型——getRecipeFor 的泛型 C 按实参静态类型推断，SimpleContainer 推不出
     *  Recipe<Container> 的约束。 */
    private static <T extends net.minecraft.world.item.crafting.AbstractCookingRecipe>
    boolean hasRecipe(ServerLevel level, ItemStack stack,
                      net.minecraft.world.item.crafting.RecipeType<T> type) {
        try {
            net.minecraft.world.Container probe = new net.minecraft.world.SimpleContainer(1);
            probe.m_6836_(0, stack);
            return level.m_7465_().m_44015_(type, probe, level).isPresent();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isSmeltable(ServerLevel level, ItemStack stack) {
        return hasRecipe(level, stack, net.minecraft.world.item.crafting.RecipeType.f_44108_);
    }

    /** v1.1.0 实测一百五十八：烟熏炉/高炉按各自配方类型取料——
     *  烟熏炉 = 有烟熏配方的物品（生食）；高炉 = 有高炉配方的物品（矿石/粗金属，
     *  受「熔炉烧矿物」开关约束——高炉只烧矿物，开关关掉时高炉只收成品/补燃料）。 */
    private ItemStack extractForFurnaceType(ServerLevel level, IItemHandler maidInv, BlockEntity be) {
        if (be instanceof net.minecraft.world.level.block.entity.SmokerBlockEntity) {
            return this.extractByRecipe(level, maidInv,
                    net.minecraft.world.item.crafting.RecipeType.f_44110_);
        }
        if (be instanceof net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity) {
            if (!com.maidsmart.config.MaidSmartConfig.MISC_COOK_SMELT_ORES.get()) {
                return ItemStack.f_41583_;
            }
            return this.extractByRecipe(level, maidInv,
                    net.minecraft.world.item.crafting.RecipeType.f_44109_);
        }
        return ItemStack.f_41583_;
    }

    /** v1.1.0 实测一百五十八：从女仆背包取 1 个有指定炉子配方的物品。 */
    private <T extends net.minecraft.world.item.crafting.AbstractCookingRecipe>
    ItemStack extractByRecipe(ServerLevel level, IItemHandler maidInv,
                              net.minecraft.world.item.crafting.RecipeType<T> type) {
        for (int i = 0; i < maidInv.getSlots(); i++) {
            ItemStack stack = maidInv.getStackInSlot(i);
            if (stack.m_41619_()) {
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
    private ItemStack extractOreFromMaid(ServerLevel level, IItemHandler maidInv) {
        if (!com.maidsmart.config.MaidSmartConfig.MISC_COOK_SMELT_ORES.get()) {
            return ItemStack.f_41583_;
        }
        for (int i = 0; i < maidInv.getSlots(); i++) {
            ItemStack stack = maidInv.getStackInSlot(i);
            if (stack.m_41619_() || FOODS.contains(stack.m_41720_())) {
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
    private ItemStack extractAnySmeltable(ServerLevel level, IItemHandler maidInv) {
        if (!com.maidsmart.config.MaidSmartConfig.MISC_COOK_SMELT_ANY.get()) {
            return ItemStack.f_41583_;
        }
        for (int i = 0; i < maidInv.getSlots(); i++) {
            ItemStack stack = maidInv.getStackInSlot(i);
            if (stack.m_41619_() || FOODS.contains(stack.m_41720_())) {
                continue;
            }
            Item it = stack.m_41720_();
            if (it instanceof net.minecraft.world.item.TieredItem
                    || it instanceof net.minecraft.world.item.ArmorItem
                    || it instanceof net.minecraft.world.item.TridentItem
                    || it instanceof net.minecraft.world.item.ShieldItem) {
                continue; // 装备类永不熔（有烧成粒配方的高价值工具/盔甲）
            }
            if (isSmeltable(level, stack)) {
                return maidInv.extractItem(i, 1, false);
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
     *  v1.1.0 实测二百四十一（用户："女仆有的时候会使用木头烧木头"）：旧版按数量
     *  最多选——原木/木板/树苗既是燃料又是可烧制原料，伐木女仆背包原木数量碾压
     *  煤炭 → 原木进燃料槽、原木又进原料槽 = "用木头烧木头"。修复：
     *  ① 评分 = 燃烧时长优先（getFuel 映射，煤炭 1600 tick ≫ 原木 300 tick），
     *     同长再按数量——有煤必用煤，不再被数量带偏；
     *  ② 纯燃料优先：可燃烧且【无熔炉配方】（煤炭/木炭/烈焰棒/干海带块/熔岩桶）
     *     先选；背包没有纯燃料才退而选可烧制燃料（原木烧原木总比炉子熄火好）。 */
    private ItemStack extractBestFuel(ServerLevel level, IItemHandler maidInv) {
        Map<Item, Integer> burnTicks = new HashMap<>();
        Map<Item, Integer> counts = new HashMap<>();
        for (int i = 0; i < maidInv.getSlots(); i++) {
            ItemStack stack = maidInv.getStackInSlot(i);
            if (stack.m_41619_() || !net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity
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
            if (isSmeltable(level, new ItemStack(it))) {
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
        BlockPos pos = maid.m_20183_();
        for (int dy = -com.maidsmart.config.MaidSmartConfig.MISC_VERTICAL_RANGE.get();
             dy <= com.maidsmart.config.MaidSmartConfig.MISC_VERTICAL_RANGE.get(); dy++) {
            for (int dx = -cookRadius(); dx <= cookRadius(); dx++) {
                for (int dz = -cookRadius(); dz <= cookRadius(); dz++) {
                    BlockPos p = pos.m_7918_(dx, dy, dz);
                    if (level.m_8055_(p).m_60734_() instanceof AbstractFurnaceBlock) {
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
        }
        return null;
    }
}
