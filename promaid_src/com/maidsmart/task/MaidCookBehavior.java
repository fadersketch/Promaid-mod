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
 * - v1.5.252 燃料修正：**不限于煤炭——凡是可燃烧物品（原版 isFuel）都可用， 优先选背包中数量最多的那个** - v1.1.0 实测二百四十一：燃料选择再修正——纯燃料优先（燃烧时长评分： 煤炭/木炭/烈焰棒/干海带块/熔岩桶等不可烧制的可燃烧物），背包没有纯燃料 才退而选可烧制燃料（原木/木板/树苗）——不再"用木头烧木头" - v1.1.0 实测三百：按背包物品类型选炉子——先扫物品栏：有食物 → 烟熏炉优先 （其次熔炉）；无食物有矿物（有高炉配方）→ 高炉优先（其次熔炉）；只有仅熔炉 可烧物 → 只选熔炉。绑定后背包没有该炉型可烧物 → 取消绑定重新找。木材类 （原木/木板/树苗/竹等）默认黑名单不烧（开关 misc.cookBurnWood，默认关） - 处理间隔 100 tick（5 秒），不瞬间完成烹饪（炉子自身进度驱动） - v1.5.252：绑定炉子并到达后立刻坐下不动；行为停止/炉子丢失恢复站立 - v1.2.4 实测六百三十七【没炉子可烧时不再"把自己冻住"】：见 {@link #wander} - 【R1101】两个可选扩展（默认关）：misc.cookFurnaceFamily 熔炉等价族 / misc.cookCampfire 营火烹饪
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
     *  {@code beetroot / carrot / brown_mushroom / cactus / dried_kelp} 在原版
     *  **没有任何炉子配方**——两份客户端 jar 的 {@code data/minecraft/recipes}
     *  逐个查过（smelting / smoking / blasting 全找遍）：这五个一条都没有，
     *  而同清单里的 potato / kelp / 生肉 / 鱼都查得到。
     *  清单里多这五个的直接后果是"女仆会烧胡萝卜"（
     *  删掉之后：模组/数据包**自己给这些物品补了配方**时照样能烧——那条路走
     *  {@link #extractAnySmeltable} 与 {@link #pickFurnaceKind} 的通用分支， 它们查的是真实配方而不是这张表，所以这里是"白名单说法变准"，不是"功能变少"。 */
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
    /** 【R1101】当前绑定的是营火（true）还是炉子——营火走"站旁边摆食材/捡成品"，不站火格 */
    private boolean stationCampfire = false;
    /**
     * 【R1104·实测一千一百〇六·玩家定调】本轮是否**收回了成品**（炉子收了成品槽 /
     * 营火捡回了弹出的成品）——用于"收完这一件就别再往旧台补料"的判断；
     * 单纯喂料/补柴/摆食材不算。
     */
    private boolean tookOutputThisRound = false;
    /** 【R1104】最近一次扫描里看到的点着营火（无视工作区/占用——诊断"为什么没绑上"用） */
    private BlockPos nearestLitCampfire = null;
    /** 【R1105】最近一次扫描里看到的营火方块（**点着与否都算**——诊断"营火没点亮"用） */
    private BlockPos nearestAnyCampfire = null;
    /** 【R1106】熄灭且她身上没有点燃道具的营火：台键|女仆UUID → 解禁 gameTime（30 秒 = 600 tick） */
    private static final java.util.Map<String, Long> UNLIT_CAMPFIRE_UNTIL = new java.util.HashMap<>();
    private static final long UNLIT_CAMPFIRE_COOLDOWN = 600L;
    /** 最近一次行为 tick 的 gameTime（营火冷却判定用——不依赖 Level 取时 API） */
    private long lastGameTime;
    /** 已处理到的工位解绑代数（魂符收放/区块重载 → 解绑一次，见 MaidWorkTags#stationGen） */
    private int seenStationGen = -1;
    /** 【R1117】"没有能烧的原料"提示的限频（gameTime）：30 秒一次，避免边走边找时连播 */
    private long noIngredientVoiceAt = 0L;
    /** 【R1139】站位计量：上一次判距、连续"毫无进展"的 tick 数。只要比上次近了就清零，
     *  所以走得慢不会被误杀；只有真的 10 秒一步没靠近才判这台走不到。 */
    private double lastStandDistSq = -1.0;
    private int standNoProgressTicks = 0;
    /** 【R1140】当前台"一轮动作都没有"的起点（gameTime）；有动作或台上在烧就清零。
     *  同一台连续 10 秒零动作 = 她的料在这台根本喂不进/烧不动 → 冷却 30 秒换台。 */
    private long idleSinceGameTime = 0L;
    /** 【R1139】链路定位日志：**按 step 各自** 5 秒最多一条（旧版一对 step|detail
     *  节流被 check:ok / stand:walk 每 tick 交替击穿，两秒刷几百行）。 */
    private final java.util.Map<String, Long> traceAtByStep = new java.util.HashMap<>();
    /** 【R1139】"移速为 0"自检日志的限频（gameTime）。 */
    private long speedFixLoggedAt = Long.MIN_VALUE;
    /** 【R1133】看门狗判过"60 秒零进展"的炉子：台键 → 解禁 gameTime。专炉现在排在最前，
     *  万一那台够不着/烧不动，解绑后重扫会立刻挑回同一台 → 60 秒内先别选它。 */
    private static final java.util.Map<String, Long> STATION_STUCK_UNTIL = new java.util.HashMap<>();
    /** 【R1146】上一 tick 的 TLM 坐姿标记——玩家反复坐/站时把整条链路清空重跑 */
    private Boolean lastSitting = null;

    /** 清掉该女仆在静态占用表里的记录（魂符收起/放出时台子不再被"预留"）。 */
    public static void forgetStation(java.util.UUID maidId) {
        if (maidId == null) {
            return;
        }
        FURNACE_USERS.entrySet().removeIf(e -> maidId.equals(e.getValue()));
        String suffix = "|" + maidId;
        UNLIT_CAMPFIRE_UNTIL.keySet().removeIf(k -> k.endsWith(suffix));
    }

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

    /**
     * 【R1137】放弃当前台、回到"继续找台"：先收干净这一台的成品，再给台子记 30 秒冷却
     * （别下一 tick 又原样选回来），然后解绑、放行移动、闲逛等下一轮扫描。
     * 触发点只有两个：没有可站邻格、或 10 秒完全没有靠近标准站位。
     */
    private void giveUpStation(ServerLevel level, EntityMaid maid) {
        if (this.furnacePos != null) {
            STATION_STUCK_UNTIL.put(furnaceKey(level, this.furnacePos),
                    level.m_46467_() + 600L);
        }
        this.harvestStation(level, maid);
        this.furnacePos = null;
        this.stationCampfire = false;
        this.standNoProgressTicks = 0;
        this.lastStandDistSq = -1.0;
        this.idleSinceGameTime = 0L; // 【R1140】换台 → 空转计时归零
        this.releaseFurnace();
        this.standUp(maid);
        MaidWorkTags.setStationLock(maid, false);
        MaidWorkTags.setStill(maid, false);
        this.wander(maid);
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
        this.lastGameTime = gameTime;
        // R1131：骑乘中（被载具/扫帚载着）做不了饭——不选台、不留台，落地后再绑
        if (maid.m_20202_() != null) {
            LOGGER.info("cook start: maid={} 骑乘中 → 先不选台（落地后再找）",
                    com.maidsmart.tool.PromaidLog.nameOf(maid));
            return;
        }
        stashHandFood(level, maid); // 【R1107】手里的食材先搬进背包，选台/喂料才看得见
        this.seenStationGen = MaidWorkTags.stationGen(maid);
        if (this.furnacePos == null) {
            this.furnacePos = this.findStation(level, maid);
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
        this.standNoProgressTicks = 0; // 【R1139】行为重启 → 重新给站位 10 秒
        this.lastStandDistSq = -1.0;
        this.idleSinceGameTime = 0L; // 【R1140】行为重启 → 空转计时归零
        LOGGER.info("cook start: maid={} 台={} {}",
                com.maidsmart.tool.PromaidLog.nameOf(maid),
                this.stationCampfire ? "营火" : "炉子", this.furnacePos);
    }

    @Override
    protected void m_6725_(ServerLevel level, EntityMaid maid, long gameTime) {
        this.lastGameTime = gameTime;
        // 【R1146】玩家在烧制中让她坐下/站起：旧链路里的台、站位、冷却全部作废，
        // 下一 tick 从"找台"重新走一遍，避免坐姿切换后卡在旧状态里不再动作。
        this.syncPostureReset(level, maid);
        // 【R1149】危险优先于烟熏炉：燃烧 / 岩浆 / 灵魂灼烧期间立刻放台、解除站桩，
        // 交给 DangerEscapeHandler 撤离；否则她会为了守住炉子站在灵魂营火旁继续挨打。
        if (com.maidsmart.protect.DangerEscapeHandler.isHeatDanger(maid)) {
            if (this.furnacePos != null) {
                this.releaseFurnace();
                this.furnacePos = null;
                this.stationCampfire = false;
            }
            this.standUp(maid);
            MaidWorkTags.setStationLock(maid, false);
            MaidWorkTags.setStill(maid, false);
            return;
        }
        // R1131：骑乘中不占台——否则启动时绑上的台会被"带走"，落地后还挂着一个
        // 远在几十格外的台（她以为自己在做饭，实际在跟着主人飞）。落地后由 start/doTick 重找。
        if (maid.m_20202_() != null) {
            if (this.furnacePos != null) {
                this.releaseFurnace();
                this.furnacePos = null;
                this.stationCampfire = false;
                MaidWorkTags.setStationLock(maid, false);
                MaidWorkTags.setStill(maid, false);
                this.standUp(maid);
            }
            return;
        }
        stashHandFood(level, maid); // 【R1107】玩家刚塞到她手里的牛肉等，先搬进背包
        // 魂符收放/区块重载：解绑一次并重新扫台（不再抱着收魂前那台炉子）。
        int gen = MaidWorkTags.stationGen(maid);
        if (gen != this.seenStationGen) {
            this.seenStationGen = gen;
            this.releaseFurnace();
            this.furnacePos = null;
            MaidWorkTags.setStationLock(maid, false);
            MaidWorkTags.setStill(maid, false);
            this.standUp(maid);
        }
        // 【R1110】附近 5×5×5 里任何炉子的成品栏都先进包（塞不下才留槽）——切换台子/走动
        // 路过时不会把成品落在别人家炉子里
        this.sweepNearbyOutputs(level, maid);
        if (this.furnacePos == null) {
            // v1.2.4 实测六百三十七【不再"没炉子就把自己钉死"】：旧版这里
            // setStill(true) 站桩等待，而 WORK_STILL 经 MaidMoveSuppressMixin 是
            // **彻底静止**（每 tick 清走位 + 停导航 + 取消 MoveToTargetSink），
            // 于是"烧制任务 + home 模式 + 附近没有可用的炉子"= 永远不动。
            // 玩家日志实证（latest.log 13:54 起）：反复 `cook start: maid=… furnace=null`
            // 之后再没挪过一步；反馈原文「不进入我们自己写的烧制且处于 home 状态
            // 这个模式的时候会直接卡在原地不动」。
            // 现在改为一心两用：放行移动（不站桩），每 2 秒在附近挑个闲逛点 ——与宰杀"没有牲畜就四处闲逛"（实测三百四十）同一套口径， 边走边扫炉子，找得到就绑定、找不到也不冻人。
            MaidWorkTags.setStill(maid, false);
            this.standUp(maid);
            if (this.scanCooldown-- > 0) {
                this.trace(maid, gameTime, "scan:wait", "没绑台，闲逛等重扫");
                this.wander(maid);
                return;
            }
            this.scanCooldown = 20;
            this.furnacePos = this.findStation(level, maid);
            if (this.furnacePos == null) {
                this.trace(maid, gameTime, "scan:none", "扫描半径 " + cookRadius()
                        + " 内没有可用的台（详见 cook scan）");
                this.logScanWhy(level, maid); // 【R1104】没绑上台时把"为什么"写进日志
                this.notifyNoIngredient(level, maid); // 【R1117】一开始就没料也要能播报
                MaidWorkTags.setStationLock(maid, false);
                this.wander(maid);
                return;
            }
            // v1.1.0 实测一百六十八：绑定成功 → 登记炉子占用（多女仆分散）
            this.claimFurnace(level, maid, this.furnacePos);
            if (this.furnacePos == null) {
                // 炉子被别的女仆占用：claimFurnace 已清空，本轮重新找
                this.trace(maid, gameTime, "scan:taken", "选中的台被别的女仆占用 → 重扫");
                MaidWorkTags.setStationLock(maid, false);
                this.wander(maid);
                return;
            }
            this.trace(maid, gameTime, "scan:bind", "绑定 " + (this.stationCampfire ? "营火" : "炉子")
                    + " " + this.furnacePos + " 距离="
                    + String.format("%.2f", Math.sqrt(maid.m_20275_(
                            this.furnacePos.m_123341_() + 0.5, this.furnacePos.m_123342_() + 0.5,
                            this.furnacePos.m_123343_() + 0.5))));
            this.cooldown = 0; // R1131：刚绑上台就立刻做第一轮（不等最多 5 秒的处理间隔）
            this.lastProgressGameTime = gameTime; // v1.2.5 实测六百五十三：换炉子重新计时
            this.standNoProgressTicks = 0; // 【R1139】新台重新计站位超时
            this.lastStandDistSq = -1.0;
            this.idleSinceGameTime = 0L; // 【R1140】新台重新计空转
        }
        // 【R1137】营火↔炉子的优先级不再走"每 2 秒单独迁移"：一轮四步结束时统一回选台
        // （见 reconsiderStation），营火优先的口径由 findStation 保证；正在烧的台不打断。
        BlockState state = level.m_8055_(this.furnacePos);
        // 【R1106】绑定校验改看"是不是营火"——熄灭的营火也算有效台，点亮逻辑在走到之后
        // 【R1149】灵魂营火与普通营火同级：同一套方块实体与营火配方，都算有效台
        if (this.stationCampfire ? !isCampfireBlock(state)
                : !isFurnaceFamilyBlock(level, this.furnacePos, state)) {
            this.trace(maid, gameTime, "check:block", "台=" + this.furnacePos + " 方块已不是烧制台");
            this.furnacePos = null;
            this.stationCampfire = false;
            this.releaseFurnace(); // v1.1.0 实测一百六十八：炉子没了 → 释放占用
            this.standUp(maid);
            MaidWorkTags.setStationLock(maid, false); // 炉子被破坏 → 跟随链路恢复
            // 炉子没了：不站桩（下一 tick 走上面的"找炉子"分支，边走边找）
            MaidWorkTags.setStill(maid, false);
            this.wander(maid);
            return;
        }
        // 实测一千〇五【home 检索范围】：绑着的炉子落在工作区外（玩家把圈心挪了位置）→
        // 与"炉子没了"同待遇：解绑、边走边找圈内的
        if (!com.maidsmart.follow.WorkAreaClamp.allowsWork(maid, this.furnacePos)) {
            this.trace(maid, gameTime, "check:area", "台=" + this.furnacePos + " 在工作圈外 → 解绑");
            this.harvestStation(level, maid); // 【R1109】解绑前先收回成品
            this.furnacePos = null;
            this.stationCampfire = false;
            this.releaseFurnace();
            this.standUp(maid);
            MaidWorkTags.setStationLock(maid, false);
            MaidWorkTags.setStill(maid, false);
            this.wander(maid);
            return;
        }
        // v1.1.0 实测三百（反馈："如果女仆发现自己包中没有可以对应的物品，那么会将
        // 这个炉子的绑定取消掉……重新寻找一个新的"）：绑定后背包没有该炉型可烧物 →
        // 取消绑定重新找（如高炉烧完矿后背包只剩食物 → 换烟熏炉/熔炉）
        if (this.stationCampfire ? !this.campfireStillWorth(level, maid, this.furnacePos)
                : !this.furnaceMatchesInv(level, maid, this.furnacePos)) {
            this.trace(maid, gameTime, "check:inv", "台=" + this.furnacePos
                    + " 身上已没有这型炉子能烧的料 → 解绑重选");
            this.harvestStation(level, maid); // 【R1109】解绑前先收回成品
            this.furnacePos = null;
            this.stationCampfire = false;
            this.releaseFurnace();
            this.standUp(maid);
            MaidWorkTags.setStationLock(maid, false);
            // v1.2.4 实测六百三十七：背包里没有这型炉子能烧的东西 → 解绑重找，
            // 并且**不站桩**（旧版这里 setStill(true)，配上"每 20 tick 又扫回同一个
            // 不匹配的炉子"就是永久冻结）；边走边找，换个炉型就有得烧了。
            MaidWorkTags.setStill(maid, false);
            this.notifyNoIngredient(level, maid); // 【R1117】全都没料才播报（换炉型静默）
            this.wander(maid);
            return;
        }
        // 【R1106】熄灭的营火也能绑：绑上后发现没点着，身上又没打火石/火焰弹 → 立刻解绑，
        // 这台火 30 秒内不再检索（拿到道具或它自己被点亮后立刻恢复）
        if (this.stationCampfire && !isLitCampfire(level.m_8055_(this.furnacePos))
                && !hasIgniter(maid)) {
            this.skipUnlitCampfire(level, maid, this.furnacePos);
            LOGGER.info("cook unlit: maid={} 营火 {} 没点着，身上没有打火石/火焰弹 → 30 秒内不再看它",
                    com.maidsmart.tool.PromaidLog.nameOf(maid), this.furnacePos);
            this.furnacePos = null;
            this.stationCampfire = false;
            this.releaseFurnace();
            this.standUp(maid);
            MaidWorkTags.setStationLock(maid, false);
            MaidWorkTags.setStill(maid, false);
            this.wander(maid);
            return;
        }
        // 炉子有效 → 从这一刻起锁住：不再跟主人（走去炉子的路上也不再被跟随打断）
        this.trace(maid, gameTime, "check:ok", "台=" + this.furnacePos
                + (this.stationCampfire ? "（营火）" : "（炉子）"));
        MaidWorkTags.setStationLock(maid, true);
        double distSq = maid.m_20275_(
                this.furnacePos.m_123341_() + 0.5, this.furnacePos.m_123342_() + 0.5,
                this.furnacePos.m_123343_() + 0.5);
        // 【R1137】标准站位是开工的前提（营火与三种炉子同口径）：必须先站到台子旁边的
        // 那个站位格，才允许坐下操作。选台时已要求"存在可站邻格"，这里每 tick 再判一次：
        // 站位被方块堵掉 / 工作圈被挪走 → 这台当场作废，收成品、记冷却、回选台链路。
        BlockPos stand = stationStandSpot(level, maid, this.furnacePos);
        if (stand == null) {
            LOGGER.info("cook stand-giveup: maid={} 台={} 已没有可站的邻格 → 收成品换台",
                    com.maidsmart.tool.PromaidLog.nameOf(maid), this.furnacePos);
            this.trace(maid, gameTime, "stand:none", "台=" + this.furnacePos + " 无站位 → 换台");
            this.giveUpStation(level, maid);
            return;
        }
        double standSq = maid.m_20275_(
                stand.m_123341_() + 0.5, stand.m_123342_() + 0.5, stand.m_123343_() + 0.5);
        // 站在台子格上（含营火格，会烧伤）也算没到位，必须先挪到邻格
        if (standSq > 1.0 || maid.m_20183_().equals(this.furnacePos)) {
            // 【R1149】走位期间先解除站桩：上一 tick 若已 setStill(true)，WORK_STILL 会
            // 经 MaidMoveSuppressMixin 把导航掐掉——站在点燃的营火/灵魂营火上就永远下不来。
            MaidWorkTags.setStill(maid, false);
            this.standUp(maid); // 同时解除上一步强制写入的 Pose.SITTING，站起来迈步
            // 【R1139】先确认她的腿是通的：MOVEMENT_SPEED 被第三方事件置 0 时，
            // 寻路再正确也会被 MaidMoveControl 算成 setSpeed(0)——有路、navDone=false、一步不动。
            this.ensureMovable(maid, gameTime);
            // 【R1138】直连导航（WALK_TARGET 在这里实测不产生位移）
            boolean moveTo = this.walkTo(maid, stand, 1.0);
            this.trace(maid, gameTime, "stand:walk", "站位=" + stand
                    + " 距离=" + String.format("%.2f", Math.sqrt(standSq))
                    + " moveTo=" + moveTo
                    + " 移速=" + String.format("%.2f",
                            maid.m_21133_(net.minecraft.world.entity.ai.attributes.Attributes.f_22279_))
                    + " navDone=" + maid.m_21573_().m_26571_());
            // 比上一次近了 0.2 格以上 = 有进展 → 清零（慢走不会被 10 秒误杀）
            if (this.lastStandDistSq < 0.0 || standSq < this.lastStandDistSq - 0.04) {
                this.standNoProgressTicks = 0;
            } else {
                ++this.standNoProgressTicks;
            }
            this.lastStandDistSq = standSq;
            // 【R1149】已站在营火上、标准站位就在 2 格内却一步不动（导航 navDone 但不位移）
            // → 直接挪到站位，别再干等 10 秒；这是玩家看到的"站了半天才开工"。
            if (this.standNoProgressTicks >= 20 && standSq <= 4.0) {
                maid.m_6034_(stand.m_123341_() + 0.5, stand.m_123342_(),
                        stand.m_123343_() + 0.5);
                maid.m_20256_(net.minecraft.world.phys.Vec3.f_82478_);
                MaidWorkTags.setStill(maid, false);
                this.standNoProgressTicks = 0;
                this.lastStandDistSq = -1.0;
                this.trace(maid, gameTime, "stand:snap", "站位=" + stand + " 1 秒未动 → 直接落位");
                return;
            }
            if (this.standNoProgressTicks >= 60) {
                // 3 秒一步没靠近：这台走不到，不再"隔空开工"
                this.standNoProgressTicks = 0;
                this.lastStandDistSq = -1.0;
                LOGGER.info("cook stand-giveup: maid={} 3 秒没靠近标准站位（台={}，站位={}，距台 {} 格"
                                + "，移速={}）→ 收成品换台",
                        com.maidsmart.tool.PromaidLog.nameOf(maid), this.furnacePos, stand,
                        String.format("%.2f", Math.sqrt(distSq)),
                        String.format("%.2f", maid.m_21133_(
                                net.minecraft.world.entity.ai.attributes.Attributes.f_22279_)));
                this.trace(maid, gameTime, "stand:giveup", "台=" + this.furnacePos
                        + " 站位=" + stand + " 3 秒未靠近 → 换台");
                this.giveUpStation(level, maid);
            }
            return;
        }
        this.standNoProgressTicks = 0; // 已到标准站位
        this.lastStandDistSq = -1.0;
        this.trace(maid, gameTime, "stand:ok", "站位=" + stand
                + " 距离=" + String.format("%.2f", Math.sqrt(standSq)));
        // 【R1106】站定后火还没点着：把打火石/火焰弹换到副手，点一次，然后继续原链路
        if (this.stationCampfire && !isLitCampfire(level.m_8055_(this.furnacePos))
                && !this.tryLightCampfire(level, maid, this.furnacePos)) {
            this.skipUnlitCampfire(level, maid, this.furnacePos);
            LOGGER.info("cook unlit: maid={} 营火 {} 仍没点着（点燃道具不在了）→ 30 秒内不再看它",
                    com.maidsmart.tool.PromaidLog.nameOf(maid), this.furnacePos);
            this.furnacePos = null;
            this.stationCampfire = false;
            this.releaseFurnace();
            this.standUp(maid);
            MaidWorkTags.setStationLock(maid, false);
            MaidWorkTags.setStill(maid, false);
            this.wander(maid);
            return;
        }
        // v1.5.17 站桩强化：绑定工作方块后每 tick 清移动目标 + 停止导航，
        // 冷却期间也清——防止漫游/跟随在冷却间隙重新设目标（完全站桩不动）
        MaidWorkTags.setStill(maid, true);
        maid.m_6274_().m_21936_(MemoryModuleType.f_26370_);
        maid.m_21573_().m_26573_(); // 【R1138】到站：停掉直连导航（recomputePath 会重新算路）
        this.faceStation(maid); // 【R1146】烧制时视线与身体正对自己绑定的台
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
            // 【R1133】这台 60 秒没进展（喂不进/够不着/烧不动）→ 60 秒内不再选它。
            // 新排序把专炉放在最前，若那台够不着，"解绑 → 重扫 → 又挑回同一台"会原地打转。
            if (!this.stationCampfire && this.furnacePos != null) {
                STATION_STUCK_UNTIL.put(furnaceKey(level, this.furnacePos), gameTime + 1200L);
            }
            this.furnacePos = null;
            this.stationCampfire = false;
            this.releaseFurnace();
            this.standUp(maid);
            MaidWorkTags.setStationLock(maid, false);
            MaidWorkTags.setStill(maid, false);
            LOGGER.info("cook watchdog: maid={} 绑定炉子 60 秒零进展（喂不进/烧不动）→ 解绑重新找",
                    com.maidsmart.tool.PromaidLog.nameOf(maid));
            this.wander(maid);
            return;
        }
        if (this.cooldown-- > 0) {
            this.trace(maid, gameTime, "wait", "站位内等待处理间隔");
            return;
        }
        this.cooldown = processCooldown();
        BlockEntity be = level.m_7702_(this.furnacePos);
        this.trace(maid, gameTime, "round", "开始一轮 be="
                + (be == null ? "null" : be.getClass().getSimpleName())
                + (this.stationCampfire ? " 营火" : " 炉子"));
        boolean acted = false;
        if (this.stationCampfire) {
            // 【R1101】营火一轮：先捡成品（原版烤好会弹出掉落物），再摆一个食材
            if (be instanceof net.minecraft.world.level.block.entity.CampfireBlockEntity cbe
                    && this.processCampfire(level, maid, cbe)) {
                acted = true;
            }
        } else if (be instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity
                && (be instanceof FurnaceBlockEntity
                        || com.maidsmart.config.MaidSmartConfig.MISC_COOK_SMOKER_BLAST.get()
                        || furnaceFamilyOn())) {
            // v1.1.0 实测一百五十八：兼容高炉/烟熏炉——开关开启时三种炉子都操作；
            // 关闭 = 旧行为（只处理熔炉，烟熏炉/高炉前干坐）。
            // 【R1101】熔炉等价族开着时，"实体挂在原版熔炉族"的模组炉也放行
            // v1.2.5 实测六百五十三：这一轮有有效动作 → 刷新看门狗计时
            acted = this.processFurnace(level, maid, (Container) be, be);
        } else {
            // v1.1.0 实测一百六十一：诊断——门控未过（BE 类型不对/开关关），不该发生
            LOGGER.info("cook gate-blocked: maid={} be={} switch={} furnace={} distSq={}",
                    com.maidsmart.tool.PromaidLog.nameOf(maid),
                    be == null ? "null" : be.getClass().getSimpleName(),
                    com.maidsmart.config.MaidSmartConfig.MISC_COOK_SMOKER_BLAST.get(),
                    this.furnacePos, distSq);
        }
        boolean busy = this.stationBusy(level);
        if (acted) {
            this.lastProgressGameTime = gameTime;
        }
        if (acted || busy) {
            this.idleSinceGameTime = 0L;
        } else if (this.idleSinceGameTime == 0L) {
            this.idleSinceGameTime = gameTime; // 【R1140】开始计"空转"
        }
        this.trace(maid, gameTime, "round:end", "acted=" + acted + " busy=" + busy);
        // 【R1137】一轮四步（收成品 → 补柴 → 喂料 → 取回烧不动的）走完，回到"选台"这一步
        // 重新判断——不再只在"收完成品"时特判换台。正在烧的台由 reconsiderStation 保留。
        this.reconsiderStation(level, maid);
    }

    /** 【R1146】坐下/站起切换一次 → 清空一次绑定与计时，让整条链路从"找台"重跑。
     *  只清本行为的瞬时状态；不给台子记冷却、不强制改变她的姿势（姿势是玩家刚切的）。 */
    private void syncPostureReset(ServerLevel level, EntityMaid maid) {
        boolean sitting = maid.isMaidInSittingPose();
        Boolean prev = this.lastSitting;
        this.lastSitting = sitting;
        if (prev == null || prev == sitting) {
            return;
        }
        BlockPos old = this.furnacePos;
        this.releaseFurnace();
        this.furnacePos = null;
        this.stationCampfire = false;
        this.cooldown = 0;
        this.scanCooldown = 0;
        this.wanderCooldown = 0;
        this.lastProgressGameTime = this.lastGameTime;
        this.standNoProgressTicks = 0;
        this.lastStandDistSq = -1.0;
        this.idleSinceGameTime = 0L;
        this.tookOutputThisRound = false;
        MaidWorkTags.setStationLock(maid, false);
        MaidWorkTags.setStill(maid, false);
        maid.m_6274_().m_21936_(MemoryModuleType.f_26370_);
        if (maid.m_21573_() != null) {
            maid.m_21573_().m_26573_();
        }
        this.trace(maid, this.lastGameTime, "posture:reset",
                (sitting ? "坐下" : "站起") + " → 清空链路" + (old == null ? "" : "（原台=" + old + "）"));
    }

    /** 【R1146】站定后把身体/头/视线都转向绑定的台（营火/炉子同一套）。 */
    private void faceStation(EntityMaid maid) {
        if (this.furnacePos == null) {
            return;
        }
        double tx = this.furnacePos.m_123341_() + 0.5;
        double ty = this.furnacePos.m_123342_() + 0.5;
        double tz = this.furnacePos.m_123343_() + 0.5;
        double dx = tx - maid.m_20185_();
        double dz = tz - maid.m_20189_();
        if (dx * dx + dz * dz > 1.0E-4) {
            float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
            maid.m_146922_(yaw); // setYRot
            maid.m_5616_(yaw);   // setYBodyRot
            maid.m_5618_(yaw);   // setYHeadRot
        }
        maid.m_21563_().m_24950_(tx, ty, tz, 30.0F, 30.0F);
    }

    /**
     * 【R1137】一轮走完 → 回选台（链路里的"步骤④"）。台上还在烧 → 守着烧完；
     * 台上没活 → 现算最好的台（营火优先、专炉其次、同级就近）：还是它 → 继续守，
     * 换了 → 收成品、解绑、占新台、立刻做第一轮；一台都没有 → 解绑，下一 tick
     * 边走边找（"没原料"播报只认全台判定）。取代原来的 rebindAfterCycle 特例。
     */
    private void reconsiderStation(ServerLevel level, EntityMaid maid) {
        if (this.furnacePos == null) {
            return; // 下一 tick 走"没绑上台"分支
        }
        if (this.stationBusy(level)) {
            this.trace(maid, level.m_46467_(), "reselect:keep", "台上还在烧 → 守完再选");
            return; // 正在烧：等这一件烧完再重新选
        }
        // 【R1140】这台连续 10 秒一轮动作都没有（不喂、不收、不补柴、也没东西可取回）：
        // 配方判据和喂料判据一定有一边不对。旧版这里会一路 reselect:same 原地空转
        // （实测 1.20.1 双女仆：第二只绑高炉、包里只有猪排 → 站在炉前 1 分多钟不动）。
        // 不再等 60 秒看门狗：直接给台子 30 秒冷却，去试下一台。
        if (this.idleSinceGameTime > 0L
                && level.m_46467_() - this.idleSinceGameTime >= 200L) {
            LOGGER.info("cook idle-giveup: maid={} 台={} 连续 10 秒零动作（喂不进/烧不动）"
                            + " → 30 秒内不再选它",
                    com.maidsmart.tool.PromaidLog.nameOf(maid), this.furnacePos);
            this.trace(maid, level.m_46467_(), "reselect:idle",
                    "台=" + this.furnacePos + " 10 秒零动作 → 换台");
            this.giveUpStation(level, maid);
            return;
        }
        BlockPos old = this.furnacePos;
        boolean oldCampfire = this.stationCampfire;
        BlockPos next = this.findStation(level, maid); // 营火优先；内部会更新 stationCampfire
        boolean nextCampfire = this.stationCampfire;
        if (next != null && next.equals(old) && nextCampfire == oldCampfire) {
            this.trace(maid, level.m_46467_(), "reselect:same", "现算最好的仍是 " + old);
            return; // 现算最好的还是这一台
        }
        this.harvestStation(level, maid); // 【R1109】换台前先把成品收干净
        this.releaseFurnace();
        this.furnacePos = null;
        this.stationCampfire = false;
        this.standNoProgressTicks = 0;
        this.lastStandDistSq = -1.0;
        this.idleSinceGameTime = 0L; // 【R1140】换台 → 空转计时归零
        if (next == null) {
            MaidWorkTags.setStationLock(maid, false);
            this.standUp(maid);
            MaidWorkTags.setStill(maid, false);
            LOGGER.info("cook reselect: maid={} 一轮结束 → 附近已没有可用台（旧台={}）",
                    com.maidsmart.tool.PromaidLog.nameOf(maid), old);
            this.trace(maid, level.m_46467_(), "reselect:none", "旧台=" + old + " → 附近已无可用台");
            this.notifyNoIngredient(level, maid); // 全台判定后才可能播报
            this.wander(maid);
            return;
        }
        this.furnacePos = next;
        this.stationCampfire = nextCampfire;
        this.claimFurnace(level, maid, next);
        if (this.furnacePos == null) {
            this.stationCampfire = false; // 被别的女仆占了 → 下一 tick 重扫
            this.wander(maid);
            return;
        }
        this.cooldown = 0; // 新台立刻来一轮（先走到标准站位）
        this.lastProgressGameTime = level.m_46467_();
        LOGGER.info("cook reselect: maid={} 一轮结束 → 换台 {} → {}",
                com.maidsmart.tool.PromaidLog.nameOf(maid), old, next);
        this.trace(maid, level.m_46467_(), "reselect:switch", old + " → " + next);
    }

    @Override
    protected boolean m_6737_(ServerLevel level, EntityMaid maid, long gameTime) {
        // v1.5.24 站桩强化：只要还在烹饪任务就持续站桩——找不到熔炉时也保持行为运行
        boolean still = isCookTask(maid);
        if (!still) {
            MaidWorkTags.setStill(maid, false);
            MaidWorkTags.setStationLock(maid, false); // 切出烧制任务 → 跟随链路立刻恢复
            this.standUp(maid);
        }
        return still;
    }

    @Override
    protected void m_6732_(ServerLevel level, EntityMaid maid, long gameTime) {
        // v1.5.24：行为真正停止时解除站桩标记（双保险）+ 恢复站立
        MaidWorkTags.setStill(maid, false);
        MaidWorkTags.setStationLock(maid, false);
        this.standUp(maid);
        // v1.1.0 实测一百六十八：行为停止 → 释放炉子占用（其他女仆可接手）
        this.harvestStation(level, maid); // 【R1109】解绑前先收回成品
        this.releaseFurnace();
    }

    /** v1.5.252：恢复站立。R1139：按**当前姿势**清，别只看 TLM 的坐姿标志——
     *  本行为自己只改过 {@code Pose.SITTING}（没碰标志），旧判断永远不成立，
     *  于是她会带着坐姿去走路。 */
    private void standUp(EntityMaid maid) {
        try {
            if (maid.m_20089_() == net.minecraft.world.entity.Pose.SITTING) {
                maid.m_20124_(net.minecraft.world.entity.Pose.STANDING);
            }
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
        }
    }

    /**
     * 【R1139】走路前确认"腿是通的"：MOVEMENT_SPEED ≈ 0 时 MaidMoveControl 会 setSpeed(0)，
     * 表现与寻路失败一模一样（有路、navDone=false、位置不变）。实机取证（存档 NBT）：
     * 该女仆 movement_speed 的 base 就是 0.0，来源是 maid_cute_activity 的梳尾巴/沙子事件
     * 把基础移速临时置 0 后没还原（该模组配置注释与 maid_cute_activity_patch 都写明了）。
     * 这里只做很窄的一件事：有主 + 干活任务 + 真走不动 + base ≤ 0 → 恢复原版默认 0.7，
     * 并留一条指名道姓的日志；base 正常（只是被别的修改器压到 0）时只记日志，不抢别人的笔。
     */
    private void ensureMovable(EntityMaid maid, long gameTime) {
        try {
            if (!com.maidsmart.tool.MaidScope.owned(maid)) {
                return; // 无主女仆不干预
            }
            double eff = maid.m_21133_(net.minecraft.world.entity.ai.attributes.Attributes.f_22279_);
            if (eff > 0.001) {
                return; // 能走，正常
            }
            net.minecraft.world.entity.ai.attributes.AttributeInstance inst =
                    maid.m_21051_(net.minecraft.world.entity.ai.attributes.Attributes.f_22279_);
            if (inst == null) {
                return;
            }
            if (inst.m_22115_() <= 0.001) {
                double brokenBase = inst.m_22115_();
                inst.m_22100_(0.7);
                if (gameTime - this.speedFixLoggedAt >= 200L) {
                    this.speedFixLoggedAt = gameTime;
                    LOGGER.info("cook speed-fix: maid={} 移速 base={} 被置 0（有效值 {}）→ 走路必然原地不动，"
                                    + "不是寻路问题；已恢复 0.7。来源通常是 maid_cute_activity 的梳尾巴事件，"
                                    + "可启用 maid_cute_activity_patch 或用糖右键她",
                            com.maidsmart.tool.PromaidLog.nameOf(maid),
                            String.format("%.2f", brokenBase),
                            String.format("%.2f", eff));
                }
            } else if (gameTime - this.speedFixLoggedAt >= 200L) {
                this.speedFixLoggedAt = gameTime;
                LOGGER.info("cook speed-zero: maid={} 移速有效值 {}（base={}）被别的修改器压到 0 → 走不动；"
                                + "本行为不改别人的修改器，请检查饰品/药水",
                        com.maidsmart.tool.PromaidLog.nameOf(maid),
                        String.format("%.2f", eff), String.format("%.2f", inst.m_22115_()));
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
        }
    }

    /**
     * 【R1138】走路统一走**直连导航**（与 {@code MaidWorkBehavior} 同口径）：烧制链路原来写
     * {@code WALK_TARGET} 记忆等 {@code MoveToTargetSink} 消费，实测距台 4.27 格站满 5 秒一步不动
     * （日志：反复 {@code cook stand-giveup ... 距台 4.27}）。同项目的挖矿/伐木早就因为同一类问题
     * 改成 {@code moveTo} 直连；这里照搬：>14 格先朝目标走 12 格接力（导航搜索盒约 16~17 格）。
     *
     * @return {@code moveTo} 是否接受（false = 没找到路；调用方据此在日志里区分"没路"与"走不动"）
     */
    private boolean walkTo(EntityMaid maid, BlockPos target, double speed) {
        try {
            MaidWorkTags.setStill(maid, false); // 走位期间绝不能被 WORK_STILL 掐掉导航
            this.standUp(maid);
            maid.m_6274_().m_21936_(MemoryModuleType.f_26370_); // 清掉别的行为塞的旧目标
            double hd = Math.sqrt(
                    (target.m_123341_() + 0.5 - maid.m_20185_()) * (target.m_123341_() + 0.5 - maid.m_20185_())
                            + (target.m_123343_() + 0.5 - maid.m_20189_()) * (target.m_123343_() + 0.5 - maid.m_20189_()));
            if (hd > 14.0) {
                double k = 12.0 / hd;
                return maid.m_21573_().m_26519_(
                        maid.m_20185_() + (target.m_123341_() + 0.5 - maid.m_20185_()) * k,
                        target.m_123342_() + 0.5,
                        maid.m_20189_() + (target.m_123343_() + 0.5 - maid.m_20189_()) * k, speed);
            }
            return maid.m_21573_().m_26519_(target.m_123341_() + 0.5, target.m_123342_() + 0.5,
                    target.m_123343_() + 0.5, speed);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
            return false;
        }
    }

    /** 【R1139】链路步骤日志：每个 step 各自最多每 5 秒一条（首次出现立刻记）。
     *  旧版按键对节流，被 check:ok / stand:walk 的每 tick 交替击穿——两秒几百行。 */
    private void trace(EntityMaid maid, long gameTime, String step, String detail) {
        try {
            Long last = this.traceAtByStep.get(step);
            if (last != null && gameTime - last < 100L) {
                return;
            }
            this.traceAtByStep.put(step, gameTime);
            LOGGER.info("cook chain: maid={} step={} {}",
                    com.maidsmart.tool.PromaidLog.nameOf(maid), step, detail);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
        }
    }

    /**
     * ── 为什么要有这一段 ──
     * {@code setStill(true)} = 站桩等待。单看是"不乱跑"的美意，实际效果是**永久静止**：
     * <ul>
     *   <li>WORK_STILL 由 {@code MaidMoveSuppressMixin} 在 MoveToTargetSink 入口整段取消
     *       ——不仅拦 WALK_TARGET，还会**每 tick 调 stopNavigation**，所以连直连导航
     *       （m_26519_ moveTo）这种"绕开 MoveToTargetSink"的通道也会被立刻掐掉；</li> <li>本任务 {@code enableLookAndRandomWalk=false}（不给 TLM 随机散步）， {@code HomeWorkMovementDriver} 只认农场/宰杀，{@code HomePatrolHandler} 跳过"非战斗干活中"——三条外部驱动全都不管她。</li> </ul> 三个条件叠加 → 玩家看到的就是"卡在原地不动"（日志实证：反复 {@code cook start: maid=… furnace=null} 之后再没移动过）。 ── 口径 ── 与宰杀（实测三百四十"没有需要宰杀的动物就四处闲逛"）**完全同一套**： 每 40 tick（2 秒）在她周围 8 格内随机取一点设 WALK_TARGET （{@code BlockPosTracker} 固定点，MoveToTargetSink 消费驱动寻路）， 走位期间不站桩；闲逛点同样受工作圈钳制（{@link com.maidsmart.follow.WorkAreaClamp#allows} ——home 模式不逛出限制圈，跟随模式不逛离主人）。扫描照旧每 20 tick 一次 （{@code findFurnace} 是以她当前位置为中心扫的），所以"边走边找炉子"， 一旦扫到就绑定并走过去坐下干活。
     */
    private void wander(EntityMaid maid) {
        if (this.wanderCooldown-- > 0) {
            return;
        }
        this.wanderCooldown = 40; // 2 秒换一个闲逛点
        // 实测八百一十五【乱窜根治】：取点锚点从"她此刻位置"改为固定锚点
        //（home=玩家画的圈心 / 跟随=主人身边）。旧版每 2 秒"在她当前位置周围 8 格"取点
        // = 无回复力的随机游走，每步都可能同方向多走一点 → 几十步就漂出去几十格。
        BlockPos spot = com.maidsmart.follow.WorkAreaClamp.pickWanderSpot(
                maid, com.maidsmart.follow.WorkAreaClamp.wanderRadius(maid), maid.m_217043_());
        if (spot == null) {
            return;
        }
        if (!com.maidsmart.follow.WorkAreaClamp.allowsWander(maid, spot)) {
            return; // 本轮不闲逛（下轮再选），绝不逛出工作圈
        }
        try {
            // 【R1138】闲逛也走直连导航（与去台子同口径）；散步速度走「散步速度倍率」（默认 0.4）
            this.walkTo(maid, spot,
                    (double) com.maidsmart.config.MaidSmartConfig.MISC_STROLL_SPEED.get());
            this.trace(maid, this.lastGameTime, "wander", "闲逛点=" + spot);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
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
        this.tookOutputThisRound = false; // 【R1104】每轮重置，收回成品才算完成流程
        // 1. 收取成品
        ItemStack result = furnace.m_8020_(2);
        if (!result.m_41619_()) {
            ItemStack taken = furnace.m_8016_(2);
            ItemStack left = ItemHandlerHelper.insertItemStacked(maidInv, taken, false);
            if (!left.m_41619_()) {
                // 【R1109】背包塞不下就继续塞附属容器，别把成品落在炉子里
                left = com.maidsmart.tool.MaidExtraContainer.overflow(maid, left);
            }
            if (!left.m_41619_()) {
                furnace.m_6836_(2, left);
            }
            this.logRound(maid, beName, "output", "tookOutput=" + taken
                    + (left.m_41619_() ? "" : " left=" + left));
            if (left.m_41613_() < taken.m_41613_()) {
                maid.m_21011_(net.minecraft.world.InteractionHand.MAIN_HAND, true); // 【R1109】收取要有手上动作
                changed = true;
            }
            if (left.m_41619_()) {
                this.tookOutputThisRound = true; // 【R1104】全部收回才算完成流程
            }
        }
        // 2. 补燃料（槽 1 空）——v1.2.5 实测六百五十三：从"最后一步"挪到"喂料之前"。
        //    旧顺序（先喂料、后补柴）留了一个死法：她背包里有料、却一根柴都没有 →
        //    料被喂进槽 0、柴补不上、炉子点不着、槽 0 从此非空 → 之后每一轮都跳过喂料
        //    → 她绑着炉子坐下、永久不动（玩家看到的就是"站在原地卡死"）。
        //    先补柴，就能在喂之前先知道"有没有柴"：没柴就不喂，料留在她背包里，
        //    不会变成炉子里的路障。
        //    没有纯燃料才退而选可烧制燃料（原木/木板）——不再"用木头烧木头"
        if (furnace.m_8020_(1).m_41619_()) {
            ItemStack fuel = this.extractBestFuel(level, maid, maidInv);
            if (!fuel.m_41619_()) {
                furnace.m_6836_(1, fuel);
                maid.m_21011_(net.minecraft.world.InteractionHand.MAIN_HAND, true); // 【R1109】放料要有手上动作
                changed = true;
            }
        }
        // 3. 补食材（槽 0 空）
        if (furnace.m_8020_(0).m_41619_()) {
            if (furnace.m_8020_(1).m_41619_()) {
                // v1.2.5 实测六百五十三：没柴可添 = 喂进去也点不着 → 不喂
                if (this.logRound(maid, beName, "nofuel",
                        "槽0空但无柴可添（背包里没有能烧的燃料）——不喂，避免料卡在炉子里")) {
                    say(maid, "没有柴火了…主人，能给我一点燃料吗？"); // 【R1110】
                    this.dumpInvOnNoFeed(level, maid, maidInv, beName);
                }
                return changed;
            }
            // 【R1133】刚收完成品、而附近已经有更好的台（营火/专炉）→ 这一轮不往旧台喂料，
            // 让外层"收完成品 → 解绑重选"把台换掉，下一轮在新台上继续。旧版这里紧接着
            // 就补料，进料槽一非空，外层的换台判据就永远不成立 —— 玩家看到的正是
            // "收完就接着烧同一个，新放的好炉子不认"。
            if (this.tookOutputThisRound && !this.currentStationIsBest(level, maid)) {
                this.logRound(maid, beName, "switch",
                        "收完成品后附近有更好的烧制台 → 本轮不喂旧台，换台后再烧");
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
                maid.m_21011_(net.minecraft.world.InteractionHand.MAIN_HAND, true); // 【R1109】放料要有手上动作
                this.logRound(maid, beName, "fed", "fedSlot0=" + input);
                changed = true;
            } else {
                // v1.1.0 实测一百八十六：迁移式记录（同状态只打一条，防刷屏）
                // 【R1137】这里不再自行解绑：一轮四步走完统一由 reconsiderStation 回选台，
                // "这台吃不下 → 换台 / 附近没台 → 解绑"只在一个地方决定，避免两套判据打架。
                if (this.logRound(maid, beName, "nofeed",
                        "slot0Empty但无料可喂（背包/手/额外容器无食材/矿物或配方不匹配）")) {
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
            maid.m_21011_(net.minecraft.world.InteractionHand.MAIN_HAND, true); // 【R1109】取出也要有手上动作
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
        // R1116：背包没有再看手里（与 hasCampfireFood 同口径）
        return extractFromHands(maid, s -> isCookFood(level, s));
    }

    /** R1116：手部槽（主/副手）有没有满足条件的物品——与背包同一条判定口径 */
    private static boolean hasInHands(EntityMaid maid, java.util.function.Predicate<ItemStack> filter) {
        try {
            IItemHandler hands = maid.getHandsInvWrapper();
            for (int i = 0; i < hands.getSlots(); i++) {
                ItemStack st = hands.getStackInSlot(i);
                if (!st.m_41619_() && filter.test(st)) {
                    return true;
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
        }
        return false;
    }

    /** R1116：从手部槽取 1 件满足条件的物品——玩家把最后一块料拿在手上也要能烧 */
    private static ItemStack extractFromHands(EntityMaid maid, java.util.function.Predicate<ItemStack> filter) {
        try {
            IItemHandler hands = maid.getHandsInvWrapper();
            for (int i = 0; i < hands.getSlots(); i++) {
                ItemStack st = hands.getStackInSlot(i);
                if (!st.m_41619_() && filter.test(st)) {
                    return hands.extractItem(i, 1, false);
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
        }
        return ItemStack.f_41583_;
    }

    /** v1.1.0 实测一百五十七：物品是否带矿物/原料标签——标签路径含 ores 或
     *  raw_materials（forge:ores、forge:ores/*、minecraft:*_ores、forge:raw_materials 等）。
     *  {@code m_135827_ = getNamespace}、{@code m_135815_ = getPath}。旧版取的是
     *  **命名空间**（forge / minecraft / 模组 id），而"ores"/"raw_materials"住在
     *  **路径**里（{@code forge:ores/iron} 的 path 是 {@code ores/iron}）——
     *  命名空间永远不含这两个词，于是本方法**恒返回 false**：矿物永远判不出来。
     *  后果只是"矿物优先"失效（回退到通用可烧制物那条路照样能烧矿石）， 所以一直没被发现；既然在改这个文件就一并按注释的原意修回来。
     */
    private static boolean hasOreTag(Item item) {
        try {
            return item.m_204114_().m_203616_().anyMatch(t -> {
                String path = t.f_203868_().m_135815_();
                return path.contains("ores") || path.contains("raw_materials");
            });
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
            return false;
        }
    }

    /* ==================== v1.2.5 实测六百五十二：烧制清单（面板可编辑的四张名单） ==================== */

    /**
     * 四张清单（默认全空 = 行为与旧版**一字不变**）：
     * 只烧这些 / 禁止烧制 / 只用这些燃料 / 禁用燃料。
     * ── 为什么要有 ──
     * 以前"她该烧什么、该拿什么当柴"全写在代码里（食材白名单 + 矿物标签 + 通用回退 + 按燃烧时长
     * 自动评分），玩家想"别拿我的钻石去烧""只许用煤炭当柴"只能改代码。挖矿/伐木/喂食都有
     * 面板可编辑名单，烧制独缺一套——这四张表把它补齐（面板：生产与工作 → 烹饪与酿造 → 烧制清单）。 ── 语义（一条说清）── **禁止永远优先**；**允许清单非空 = 只在这些里挑**，留空 = 自动（旧行为）。 ── 一条硬约束：允许清单只"缩小"，绝不"放开" ── 判定挂在 {@link #hasRecipeRaw} **之上**（见 {@link #hasRecipe}），所以清单里就算写了一个 原版根本烧不动的物品，也不会让她把炉子占住——那正是实测六百五十一 刚修掉的毛病 （"抱着一个烧不动的炉子卡死不动"）。清单改的是"挑哪些"，不是"能不能烧"。 ── 生效时机 ── 与挖矿矿表/伐木木材表同一套路：面板保存时 {@link #loadCookLists()} 重建， 首次用到时懒加载（配置在静态初始化期读不到）。手改 toml 文件后需重开面板或重启游戏。
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
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
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
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
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
        if (FUEL_DENY.contains(it) || neverFuel(stack)) {
            return false;
        }
        return FUEL_ALLOW.isEmpty() || FUEL_ALLOW.contains(it);
    }

    /** v1.1.0 实测一百五十七：当前世界是否有该物品的指定类型炉子配方（熔炉/高炉/
     *  烟熏炉，用配方管理器查询，模组自定义配方同样生效）。probe 声明为 Container
     *  类型——getRecipeFor 的泛型 C 按实参静态类型推断，SimpleContainer 推不出
     *  Recipe<Container> 的约束。
     *  v1.2.5 实测六百五十二：这是**不看烧制清单**的原始判定——只有燃料那条路
     *  （判断"它本来算不算可烧制燃料"）用它：那里问的是"它自己能不能熔"，
     *  不该被玩家的原料白名单/黑名单改写，否则"把木材列进禁止烧制"会反过来
     *  把它算成"纯燃料"优先烧掉，与玩家意图正好相反。 */
    private static <T extends net.minecraft.world.item.crafting.AbstractCookingRecipe>
    boolean hasRecipeRaw(net.minecraft.world.level.Level level, ItemStack stack,
                         net.minecraft.world.item.crafting.RecipeType<T> type) {
        try {
            net.minecraft.world.Container probe = new net.minecraft.world.SimpleContainer(1);
            probe.m_6836_(0, stack);
            return level.m_7465_().m_44015_(type, probe, level).isPresent();
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
            return false;
        }
    }

    /* ==================== v1.3.2 实测六百五十六：面板候选网格的判据 ==================== */

    /**
     * 面板「烧制清单」候选网格用：这件物品在当前世界**真的有炉子配方**吗——熔炉 / 烟熏炉 /
     * 高炉任一。
     * <p>── 实测六百五十六【"网格里全是烧不动的东西"】── 旧版网格是**全物品**（把注册表里所有 item 铺出来），理由写在旧注释里："真正能不能烧 由女仆那边的配方判定管"。结果玩家打开这一页看到的是几千个物品：绝大多数根本没有炉子 配方（钻石、石头、面包、工具……），而真正能烧的生肉/矿石/沙子要翻页＋搜索才找得到 ——反馈原文「那些不可烧东西都进这张表了，原本可以烧制的食物和物品反而不在里面」。 现在网格只列**真的有配方**的：判据就在这里，与行为侧同源，所以"面板上看得见的" 和"她真的会烧的"不会再是两套东西。 <p>── 两条刻意的"不" ── <ul> <li>**不看**玩家的四张清单（{@link #smeltListed}）：网格正是用来填清单的，再套一层 清单过滤会变成"只有已经在清单里的才显示"，自己把自己锁死；</li> <li>**不看**开关（「烧任何可烧制物」「熔炉烧矿物」）：那是"我允不允许她烧"， 而"这东西能不能烧"是事实问题——面板该展示事实，开关在行为侧把关。</li> </ul> 装备类（工具/盔甲/三叉戟/盾）与附魔/用旧的物品按 {@link #isSafeToFeed} 一并排除： 她永不喂这些（它们有"烧成粒"配方），网格就不该鼓动玩家把它们填进清单。
     */
    /**
     * v1.3.0(beta) 实测六百六十五【面板「只看食物 / 填入原版食物」的唯一数据源】—— 内置食材清单的注册名（只列**当前世界真有炉子配方**的那些，与行为侧 {@code isCookFood} 同一套判据；level 为 null 时不筛）。 <p>为什么要走这里而不是面板自己再写一张表：内置食材清单只有一份（{@link #FOODS}）， 面板若自己抄一遍，迟早出现"面板说能烧、她却不烧"（本模组反复强调的红线）。
     */
    public static java.util.List<String> builtinFoodIds(net.minecraft.world.level.Level level) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Item it : FOODS) {
            try {
                ResourceLocation key = ForgeRegistries.ITEMS.getKey(it);
                if (key == null) {
                    continue;
                }
                if (level != null && !smeltableForPicker(level, new ItemStack(it))) {
                    continue; // 当前世界没配方 → 不进面板（也不该被填进名单）
                }
                out.add(key.toString());
            } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** 这件东西在不在**内置食材白名单**里（面板「只看食物」用；判据与行为侧同一张表） */
    public static boolean isBuiltinFood(ItemStack stack) {
        try {
            return stack != null && !stack.m_41619_() && FOODS.contains(stack.m_41720_());
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
            return false;
        }
    }

    /**
     * 面板「只看矿物」的判据：当前世界**有高炉配方**（矿石 / 粗金属这一族）。
     * 与行为侧选炉型用的那一条（{@code blastable}）同一个配方查询，只是把它开到面板上。
     */
    public static boolean blastableForPicker(net.minecraft.world.level.Level level, ItemStack stack) {
        try {
            return level != null && hasRecipeRaw(level, stack,
                    net.minecraft.world.item.crafting.RecipeType.f_44109_);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
            return false;
        }
    }

    public static boolean smeltableForPicker(net.minecraft.world.level.Level level, ItemStack stack) {
        if (level == null || !isSafeToFeed(stack)) {
            return false;
        }
        Item it = stack.m_41720_();
        if (it instanceof net.minecraft.world.item.TieredItem
                || it instanceof net.minecraft.world.item.ArmorItem
                || it instanceof net.minecraft.world.item.TridentItem
                || it instanceof net.minecraft.world.item.ShieldItem) {
            return false;
        }
        // f_44108_ = SMELTING / f_44110_ = SMOKING / f_44109_ = BLASTING（与 canSmeltHere 同三个常量）
        return hasRecipeRaw(level, stack, net.minecraft.world.item.crafting.RecipeType.f_44108_)
                || hasRecipeRaw(level, stack, net.minecraft.world.item.crafting.RecipeType.f_44110_)
                || hasRecipeRaw(level, stack, net.minecraft.world.item.crafting.RecipeType.f_44109_);
    }

    /**
     * 面板「烧制清单」的**燃料**两档（只用这些燃料 / 禁用燃料）用的候选判据：原版能不能
     * 当柴烧（{@code AbstractFurnaceBlockEntity.isFuel}，与行为侧 {@link #extractBestFuel} 同源）。
     *
     * <p>燃料两档和原料两档要展示的东西完全不同——把几千个物品铺给"只用这些燃料"，
     * 玩家同样没法用（旧版就是这样）。同一个网格按当前档位换判据。
     */
    public static boolean fuelForPicker(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return false;
        }
        try {
            return net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity.m_58399_(stack);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
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
     * 硬编码清单当成事实断言。而清单里的
     * {@code carrot / beetroot / brown_mushroom / cactus / dried_kelp} 在原版
     * **没有任何炉子配方**（两份客户端 jar 的 {@code data/minecraft/recipes} 实证：
     * 这五个既无 smelting 也无 smoking；对比 potato/kelp/生肉/鱼都查得到）。 后果是一条链上的两个现象： <ul> <li>{@link #pickFurnaceKind} 见到胡萝卜就判成 FOOD → 她去占烟熏炉/熔炉；</li> <li>{@link #processFurnace} 把胡萝卜塞进原料槽（提取路径也不查配方）；</li> <li>{@link #furnaceMatchesInv} 用的是**同一个**白名单判据 → 恒为真 → 她永远不解绑，抱着一个烧不动的炉子坐到底 = 玩家看到的"卡死不动"。</li> </ul> 但里面根本没有能烧的东西"是另一条，两条都会冻人。 现在白名单只当**优先项**、不当**事实**：要有烟熏或熔炉配方才算数。 模组给这些物品补了配方时照样认（走的都是同一个配方管理器）。
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
     * 烧掉"）：可烧制 ≠ 可以喂——带附魔（经验修补/耐久/时运等，哪怕只有 1 条）
     * 或耐久未满（用旧过的工具/武器/盔甲）的物品一律不进炉子。三类取料路径
     * （食材白名单外的矿物回退 extractOreFromMaid / 通用可烧制物回退
     * extractAnySmeltable / 烟熏炉·高炉 extractByRecipe）统一在入口拦截。
     * m_41793_ = isEnchanted（附魔表非空，javap 实证查 NBT Enchantments 列表），
     * m_41768_ = isDamaged（当前耐久 &lt; 最大）、m_41776_ = getMaxDamage。 {@code m_41753_() && m_41773_() > 0}，而按官方映射（obf→Mojmap）+ tsrg 链式 实证：{@code m_41753_ = isStackable}（不是 isDamaged）、 {@code m_41773_ = getDamageValue}（不是 getMaxDamage）。 {@code isStackable() && getDamageValue() > 0} 在原版几乎恒为假—— 能堆叠的物品根本不吃耐久，吃耐久的（工具/盔甲）又都是单件不可堆叠， 于是"用旧的耐久物品不熔"这道闸等于不存在：**她会把自己用过的铁剑之类 直接丢进炉子**（实测三百四十九 报的是附魔物品，附魔那半条由 isEnchanted 挡住了，这半条一直漏着）。现在照注释的原意写回来。
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
        if (stack.m_41720_() instanceof net.minecraft.world.item.ProjectileWeaponItem) {
            return false; // 【R1142】弓 / 弩 / 投掷武器：原料与燃料两条路都不碰
        }
        return true;
    }

    /** 【R1142】工具 / 武器 / 有耐久的物品**永不当燃料**：弓、弩、木剑、三叉戟、盾牌、
     *  任何有耐久的物品，以及附魔物品。这是硬闸（不受「只用这些燃料」放行），
     *  防她把你正在用的弓、剑当柴烧掉。 */
    public static boolean neverFuel(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return true;
        }
        if (stack.m_41776_() > 0 || stack.m_41793_()) {
            return true;
        }
        Item it = stack.m_41720_();
        return it instanceof net.minecraft.world.item.TieredItem
                || it instanceof net.minecraft.world.item.ArmorItem
                || it instanceof net.minecraft.world.item.TridentItem
                || it instanceof net.minecraft.world.item.ShieldItem
                || it instanceof net.minecraft.world.item.ProjectileWeaponItem;
    }

    /** 【R1142】面板用：注册名 → 是否被上面这条硬闸挡住（解析不到物品时按"不挡"处理）。 */
    public static boolean neverFuel(String id) {
        try {
            Item it = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(id));
            return it != null && neverFuel(new ItemStack(it));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
            return false;
        }
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
        // R1116：背包没有再看手里
        return extractFromHands(maid, s -> !s.m_41619_() && isSafeToFeed(s) && hasRecipe(level, s, type));
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
        // R1116：背包没有再看手里
        return extractFromHands(maid, s -> !s.m_41619_() && isSafeToFeed(s)
                && !FOODS.contains(s.m_41720_()) && hasOreTag(s.m_41720_()) && isSmeltable(level, s));
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
        // R1116：背包没有再看手里——同样"不可燃的可烧制物优先，可烧制燃料兜底"
        java.util.function.Predicate<ItemStack> handOk = s -> !s.m_41619_() && isSafeToFeed(s)
                && !FOODS.contains(s.m_41720_())
                && !(s.m_41720_() instanceof net.minecraft.world.item.TieredItem)
                && !(s.m_41720_() instanceof net.minecraft.world.item.ArmorItem)
                && !(s.m_41720_() instanceof net.minecraft.world.item.TridentItem)
                && !(s.m_41720_() instanceof net.minecraft.world.item.ShieldItem)
                && !isWood(s.m_41720_()) && isSmeltable(level, s);
        ItemStack hand = extractFromHands(maid, s -> handOk.test(s)
                && !net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity.m_58399_(s));
        if (!hand.m_41619_()) {
            return hand;
        }
        return extractFromHands(maid, handOk);
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
            com.maidsmart.tool.StateTables.cap("MaidCookBehavior.NO_FEED_DUMP_SINCE", NO_FEED_DUMP_SINCE);
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
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
        }
    }

    /** v1.5.252：燃料 = 背包中【数量最多】的可燃烧物品（原版 isFuel 判定，不限于煤炭）。
     *  最多选——原木/木板/树苗既是燃料又是可烧制原料，伐木女仆背包原木数量碾压
     *  煤炭 → 原木进燃料槽、原木又进原料槽 = "用木头烧木头"。修复：
     *  ① 评分 = 燃烧时长优先（getFuel 映射，煤炭 1600 tick ≫ 原木 300 tick），
     *     同长再按数量——有煤必用煤，不再被数量带偏；
     *  ② 纯燃料优先：可燃烧且【无熔炉配方】（煤炭/木炭/烈焰棒/干海带块/熔岩桶）
     *     先选；背包没有纯燃料才退而选可烧制燃料（原木烧原木总比炉子熄火好）。
     *  玩家改回评分制（"还是改回按燃烧时长/数量评分吧"）。木材黑名单只拦"当原料 烧"，当燃料不受影响（原木/木板照常可烧炉子）。 */
    private ItemStack extractBestFuel(ServerLevel level, EntityMaid maid, IItemHandler maidInv) {
        // v1.2.4 实测六百三十九【先判背包，再判精妙背包】：自己背包里没有可烧的东西 →
        // 先请 TLM 从精妙背包/旅行者背包搬一组燃料进来（pull 先扫她自己的背包）
        try {
            com.maidsmart.tool.MaidExtraContainer.pull(maid,
                    s -> !s.m_41619_() && isSafeToFeed(s) && fuelListed(s)
                            && net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity
                            .m_58399_(s), -1);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
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
        // v1.1.0 实测三百的择台口径（食物→烟熏炉、矿物→高炉、仅熔炉可烧物→熔炉）不变，
        // 【R1133】改的是"按哪一件料决定"：旧版先扫背包算出唯一 kind，再拿它否决台子——
        // 背包里先出现肉（kind=FOOD）时，旁边只有高炉也会被否掉（她的铁就在包里，却既不烧
        // 也不吭声）。现在逐台问 furnaceMatchesInv：这台炉子按**它自己的**配方能不能吃下她
        // 身上某件料，能吃下就是候选；再按「专炉（烟熏炉/高炉）优先 → 熔炉兜底」+ 就近排序，
        // 所以"最好的台"是每次现算的，不取决于物品栏顺序。
        BlockPos pos = maid.m_20183_();
        BlockPos best = null;
        int bestRank = Integer.MIN_VALUE;
        double bestSq = Double.MAX_VALUE;
        for (int dy = -com.maidsmart.config.MaidSmartConfig.MISC_VERTICAL_RANGE.get();
             dy <= com.maidsmart.config.MaidSmartConfig.MISC_VERTICAL_RANGE.get(); dy++) {
            for (int dx = -cookRadius(); dx <= cookRadius(); dx++) {
                for (int dz = -cookRadius(); dz <= cookRadius(); dz++) {
                    BlockPos p = pos.m_7918_(dx, dy, dz);
                    BlockState bs = level.m_8055_(p);
                    if (!isFurnaceFamilyBlock(level, p, bs)) {
                        continue;
                    }
                    // 实测一千〇五【home 检索范围】：只认工作区（河童罗盘/排班锚点画的圈）
                    // 里的炉子——圈外的一概不管；非 home 模式恒放行（口径见 WorkAreaClamp）
                    if (!com.maidsmart.follow.WorkAreaClamp.allowsWork(maid, p)) {
                        continue;
                    }
                    // v1.1.0 实测一百五十八：开关关闭 = 只操作熔炉（旧行为）。
                    // 【R1101】熔炉等价族开着时，模组熔炉（AbstractFurnaceBlock 族 /
                    // 实体挂在原版熔炉族）也放行——不再被这个开关拦在门外
                    if (!com.maidsmart.config.MaidSmartConfig.MISC_COOK_SMOKER_BLAST.get()
                            && !furnaceFamilyOn()
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
                    // 【R1133】看门狗刚判过"60 秒零进展"的台 → 60 秒内跳过（够不着/烧不动）
                    Long stuckUntil = STATION_STUCK_UNTIL.get(k);
                    if (stuckUntil != null) {
                        if (level.m_46467_() < stuckUntil) {
                            continue;
                        }
                        STATION_STUCK_UNTIL.remove(k);
                    }
                    // 【R1133】这台炉子现在有没有活干（按它自己的炉型查配方）
                    if (!this.furnaceMatchesInv(level, maid, p)) {
                        continue;
                    }
                    // 【R1137】没有可站邻格的台不选：旧版会退化成"走向炉子方块本身"，
                    // 目标不可站立，寻路永远到不了 → 每 tick 静默发一次路，玩家看到的就是卡死。
                    if (stationStandSpot(level, maid, p) == null) {
                        continue;
                    }
                    int rank = stationRank(bs);
                    double sq = maid.m_20275_(
                            p.m_123341_() + 0.5, p.m_123342_() + 0.5, p.m_123343_() + 0.5);
                    if (rank > bestRank || (rank == bestRank && sq < bestSq)) {
                        bestRank = rank;
                        bestSq = sq;
                        best = p;
                    }
                }
            }
        }
        return best;
    }

    /** 【R1133】台型优先档：烟熏炉/高炉（只烧自己那一型、快一倍）优先于熔炉（通用兜底） */
    private static int stationRank(BlockState bs) {
        net.minecraft.world.level.block.Block b = bs.m_60734_();
        return b instanceof net.minecraft.world.level.block.SmokerBlock
                || b instanceof net.minecraft.world.level.block.BlastFurnaceBlock ? 2 : 1;
    }

    /**
     * 【R1133】现在绑着的炉子是不是"现算出来的最好那台"（营火按 {@link #findStation}
     * 同一套口径算最高档）。用于"收完成品后还要不要往旧台补料"——不是最好就先换台。
     * 只读：不占台、不改绑定（换台统一在 reconsiderStation）。
     */
    private boolean currentStationIsBest(ServerLevel level, EntityMaid maid) {
        if (this.furnacePos == null) {
            return false;
        }
        if (!this.stationCampfire && campfireOn() && hasCampfireFood(level, maid)
                && this.scanCampfire(level, maid, true) != null) {
            return false; // 有能用的点着营火（不耗燃料）→ 炉子不是最好
        }
        if (this.stationCampfire) {
            return true; // 营火本身已是最高档
        }
        BlockPos best = this.findFurnace(level, maid);
        return best != null && best.equals(this.furnacePos);
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
            // R1116：手部槽与背包同权——玩家把生肉/矿拿在手上也要能选出对应炉型
            if (hasInHands(maid, s -> isCookFood(level, s))) {
                return FurnaceKind.FOOD;
            }
            if (com.maidsmart.config.MaidSmartConfig.MISC_COOK_SMELT_ORES.get()
                    && hasInHands(maid,
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
                    && hasInHands(maid,
                    s -> !s.m_41619_() && isSafeToFeed(s) && !FOODS.contains(s.m_41720_())
                            && !(s.m_41720_() instanceof net.minecraft.world.item.TieredItem)
                            && !(s.m_41720_() instanceof net.minecraft.world.item.ArmorItem)
                            && !(s.m_41720_() instanceof net.minecraft.world.item.TridentItem)
                            && !(s.m_41720_() instanceof net.minecraft.world.item.ShieldItem)
                            && !isWood(s.m_41720_()) && isSmeltable(level, s))) {
                anySmeltable = true;
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
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
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
                    // 【R1133】烟熏炉只认**烟熏配方**（喂料侧 extractForFurnaceType 就只喂这个）。
                    // 旧版按"有烟熏或熔炉配方"放行，只在熔炉里能熟的料会让她抱着烟熏炉干坐。
                    // 熔炉/等价族仍然通吃。
                    if (bs.m_60734_() instanceof net.minecraft.world.level.block.SmokerBlock
                            ? hasRecipe(level, stack, net.minecraft.world.item.crafting.RecipeType.f_44110_)
                            : isFurnaceLikeBlock(level, pos, bs)) {
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
                if (bs.m_60734_() instanceof net.minecraft.world.level.block.SmokerBlock) {
                    // 【R1141】烟熏配方不限于原版食材清单（模组可给别的东西加），
                    // 与喂料侧 extractByRecipe 同口径：能安全喂、有烟熏配方就算有活干。
                    if (isSafeToFeed(stack)
                            && hasRecipe(level, stack, net.minecraft.world.item.crafting.RecipeType.f_44110_)) {
                        return true;
                    }
                    continue;
                }
                if (bs.m_60734_() instanceof net.minecraft.world.level.block.BlastFurnaceBlock) {
                    // v1.2.5 实测六百五十三：「熔炉烧矿物」关掉 = 高炉喂料门也关（与喂料侧同口径）
                    if (com.maidsmart.config.MaidSmartConfig.MISC_COOK_SMELT_ORES.get()
                            && isSafeToFeed(stack)
                            && hasRecipe(level, stack, net.minecraft.world.item.crafting.RecipeType.f_44109_)) {
                        return true; // 高炉：有高炉配方可烧物
                    }
                    continue;
                }
                if (smeltAnyAllowed() && !(bs.m_60734_() instanceof net.minecraft.world.level.block.SmokerBlock)
                        && isSafeToFeed(stack) && isSmeltable(level, stack)) {
                    return true; // 熔炉：任一可烧制物（烟熏炉不认非食材——R1116）
                }
            }
            // v1.2.4 实测六百三十九【先判背包，再判精妙背包】：自己背包里没有这型炉子能烧的
            // 东西 → 再看精妙背包/旅行者背包（只读探针）。不跟着放宽的话，会出现"背包空、
            // 精妙背包里有料"时她反复解绑炉子重新找（bind/unbind 空转），一路站桩。
            // 口径与上面的循环逐条对齐：烟熏炉/熔炉 ← 食材；高炉 ← 高炉配方；熔炉 ← 任一可烧物。
            boolean smokerOrFurnace = isFurnaceLikeBlock(level, pos, bs);
            boolean blast = bs.m_60734_() instanceof net.minecraft.world.level.block.BlastFurnaceBlock;
            boolean smoker = bs.m_60734_() instanceof net.minecraft.world.level.block.SmokerBlock;
            // R1116：手部槽与背包同权（玩家把最后一块料拿在手上也算"有活干"）
            // 【R1133】烟熏炉仍按"烟熏配方"这一条更窄的口径（与背包循环同上）
            if (smoker && hasInHands(maid,
                    s -> hasRecipe(level, s, net.minecraft.world.item.crafting.RecipeType.f_44110_))) {
                return true;
            }
            if (!smoker && smokerOrFurnace && hasInHands(maid, s -> isCookFood(level, s))) {
                return true;
            }
            if (blast && com.maidsmart.config.MaidSmartConfig.MISC_COOK_SMELT_ORES.get()
                    && hasInHands(maid,
                    s -> !s.m_41619_() && isSafeToFeed(s) && !FOODS.contains(s.m_41720_())
                            && !(s.m_41720_() instanceof net.minecraft.world.item.TieredItem)
                            && !(s.m_41720_() instanceof net.minecraft.world.item.ArmorItem)
                            && !(s.m_41720_() instanceof net.minecraft.world.item.TridentItem)
                            && !(s.m_41720_() instanceof net.minecraft.world.item.ShieldItem)
                            && !isWood(s.m_41720_())
                            && hasRecipe(level, s, net.minecraft.world.item.crafting.RecipeType.f_44109_))) {
                return true;
            }
            if (!blast && !smoker && smeltAnyAllowed()
                    && hasInHands(maid,
                    s -> !s.m_41619_() && isSafeToFeed(s) && !FOODS.contains(s.m_41720_())
                            && !(s.m_41720_() instanceof net.minecraft.world.item.TieredItem)
                            && !(s.m_41720_() instanceof net.minecraft.world.item.ArmorItem)
                            && !(s.m_41720_() instanceof net.minecraft.world.item.TridentItem)
                            && !(s.m_41720_() instanceof net.minecraft.world.item.ShieldItem)
                            && !isWood(s.m_41720_()) && isSmeltable(level, s))) {
                return true;
            }
            // 【R1133】烟熏炉同口径收窄到烟熏配方
            if (smoker && com.maidsmart.tool.MaidExtraContainer.contains(maid,
                    s -> hasRecipe(level, s, net.minecraft.world.item.crafting.RecipeType.f_44110_))) {
                return true;
            }
            if (!smoker && smokerOrFurnace && com.maidsmart.tool.MaidExtraContainer.contains(maid,
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
            if (!blast && !smoker && smeltAnyAllowed()
                    && com.maidsmart.tool.MaidExtraContainer.contains(maid,
                    s -> !s.m_41619_() && isSafeToFeed(s) && !FOODS.contains(s.m_41720_())
                            && !(s.m_41720_() instanceof net.minecraft.world.item.TieredItem)
                            && !(s.m_41720_() instanceof net.minecraft.world.item.ArmorItem)
                            && !(s.m_41720_() instanceof net.minecraft.world.item.TridentItem)
                            && !(s.m_41720_() instanceof net.minecraft.world.item.ShieldItem)
                            && !isWood(s.m_41720_()) && isSmeltable(level, s))) {
                return true;
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
        }
        return false;
    }

    /* ==================== 【R1101】熔炉等价族 + 营火/灵魂营火烹饪 ==================== */

    /** 熔炉等价族开关（misc.cookFurnaceFamily，默认关） */
    private static boolean furnaceFamilyOn() {
        try {
            return com.maidsmart.config.MaidSmartConfig.MISC_COOK_FURNACE_FAMILY.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
            return false;
        }
    }

    /** 营火烹饪开关（misc.cookCampfire，默认关） */
    private static boolean campfireOn() {
        try {
            return com.maidsmart.config.MaidSmartConfig.MISC_COOK_CAMPFIRE.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
            return false;
        }
    }

    /**
     * 熔炉等价族判据（8 行内说清）：方块挂在 {@code AbstractFurnaceBlock} 族照旧；
     * 开关开着时，"方块不是熔炉类、实体却挂在 {@code AbstractFurnaceBlockEntity} 族"
     * 的模组炉（方块本身不是熔炉类、方块实体却继承原版熔炉）也算——槽位语义与原版一致才敢喂。
     */
    private static boolean isFurnaceFamilyBlock(ServerLevel level, BlockPos p, BlockState bs) {
        if (bs.m_60734_() instanceof AbstractFurnaceBlock) {
            return true;
        }
        if (!furnaceFamilyOn() || !(bs.m_60734_() instanceof net.minecraft.world.level.block.EntityBlock)) {
            return false;
        }
        try {
            return level.m_7702_(p)
                    instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
            return false;
        }
    }

    /**
     * 烟熏炉/熔炉，或（等价族开着）熔炉族的模组炉——食物可喂的台子。
     * 【R1140】高炉不算：喂料侧对高炉只认高炉配方，食物一件都进不去。旧版经
     * {@code isFurnaceFamilyBlock}（按 {@code AbstractFurnaceBlock} 判，高炉也在族内）
     * 把高炉算成"能接食物"，于是背包里一块猪排就让 {@link #furnaceMatchesInv} 恒为真：
     * 绑上高炉、每轮喂不进、{@code reselect:same} 又算回同一台 → 站在炉前无限空转。
     */
    private static boolean isFurnaceLikeBlock(ServerLevel level, BlockPos pos, BlockState bs) {
        if (bs.m_60734_() instanceof net.minecraft.world.level.block.BlastFurnaceBlock) {
            return false;
        }
        return bs.m_60734_() instanceof net.minecraft.world.level.block.SmokerBlock
                || bs.m_60734_() instanceof net.minecraft.world.level.block.FurnaceBlock
                || isFurnaceFamilyBlock(level, pos, bs);
    }

    /** 营火/灵魂营火方块本身（不看 LIT——诊断要能说"有营火但没点着"） */
    private static boolean isCampfireBlock(BlockState state) {
        try {
            return state.m_60734_() instanceof net.minecraft.world.level.block.CampfireBlock;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
            return false;
        }
    }

    /** 点着的营火/灵魂营火（灵魂营火也继承 CampfireBlock，同一套方块实体） */
    private static boolean isLitCampfire(BlockState state) {
        try {
            return state.m_60734_() instanceof net.minecraft.world.level.block.CampfireBlock
                    && state.m_61143_(net.minecraft.world.level.block.CampfireBlock.f_51227_);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
            return false;
        }
    }

    /** 有营火配方（CAMPFIRE_COOKING）且按"不烧装备/附魔"口径允许喂的东西 */
    private static boolean isCampfireFood(ServerLevel level, ItemStack stack) {
        return stack != null && !stack.m_41619_() && isSafeToFeed(stack)
                && hasRecipeRaw(level, stack, net.minecraft.world.item.crafting.RecipeType.f_44111_);
    }

    /** 她"身上"（手 + 背包 + 额外容器只读探针）有没有营火可烤的食材 */
    private static boolean hasCampfireFood(ServerLevel level, EntityMaid maid) {
        try {
            IItemHandler hands = maid.getHandsInvWrapper();
            for (int i = 0; i < hands.getSlots(); i++) {
                if (isCampfireFood(level, hands.getStackInSlot(i))) {
                    return true; // 【R1107】手里拿着生肉也算有食材
                }
            }
            IItemHandler inv = maid.getAvailableBackpackInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                if (isCampfireFood(level, inv.getStackInSlot(i))) {
                    return true;
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
        }
        return com.maidsmart.tool.MaidExtraContainer.contains(maid, s -> isCampfireFood(level, s));
    }

    /** 【R1107】把她手里的可烧食材搬进背包（营火/熔炉共用：玩家塞到手里的肉也要能烤） */
    private static void stashHandFood(ServerLevel level, EntityMaid maid) {
        try {
            IItemHandler hands = maid.getHandsInvWrapper();
            IItemHandler inv = maid.getAvailableBackpackInv();
            for (int i = 0; i < hands.getSlots(); i++) {
                ItemStack st = hands.getStackInSlot(i);
                if (st.m_41619_() || !(isCampfireFood(level, st) || isCookFood(level, st))) {
                    continue; // 武器/工具/装备不动
                }
                ItemStack leftover = ItemHandlerHelper.insertItemStacked(inv, st.m_41777_(), false);
                int moved = st.m_41613_() - leftover.m_41613_();
                if (moved > 0) {
                    hands.extractItem(i, moved, false);
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
        }
    }

    /** 【R1106】能点燃营火的东西：打火石族（含模组子类）与火焰弹 */
    private static boolean isIgniter(ItemStack stack) {
        return stack != null && !stack.m_41619_()
                && (stack.m_41720_() instanceof net.minecraft.world.item.FlintAndSteelItem
                || stack.m_41720_() instanceof net.minecraft.world.item.FireChargeItem);
    }

    /** 手上/背包里有没有点燃道具（不含附属容器——取料前用） */
    private static boolean hasIgniterInInv(EntityMaid maid) {
        try {
            IItemHandler hands = maid.getHandsInvWrapper();
            for (int i = 0; i < hands.getSlots(); i++) {
                if (isIgniter(hands.getStackInSlot(i))) {
                    return true;
                }
            }
            IItemHandler inv = maid.getAvailableBackpackInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                if (isIgniter(inv.getStackInSlot(i))) {
                    return true;
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
        }
        return false;
    }

    /** 她"身上"（手 + 背包 + 附属容器）有没有点燃道具 */
    private static boolean hasIgniter(EntityMaid maid) {
        return hasIgniterInInv(maid)
                || com.maidsmart.tool.MaidExtraContainer.contains(maid, s -> isIgniter(s));
    }

    /** 未点着营火的冷却键：台占用键 + 女仆（同一台火对不同女仆各算各的） */
    private static String campfireSkipKey(ServerLevel level, EntityMaid maid, BlockPos pos) {
        return furnaceKey(level, pos) + "|" + maid.m_20148_();
    }

    /** 【R1106】记下"这一台熄灭营火 30 秒内不再检索" */
    private void skipUnlitCampfire(ServerLevel level, EntityMaid maid, BlockPos pos) {
        if (UNLIT_CAMPFIRE_UNTIL.size() > 512) {
            long now = this.lastGameTime;
            UNLIT_CAMPFIRE_UNTIL.entrySet().removeIf(e -> e.getValue() < now); // 过期项顺手清掉
        }
        UNLIT_CAMPFIRE_UNTIL.put(campfireSkipKey(level, maid, pos),
                this.lastGameTime + UNLIT_CAMPFIRE_COOLDOWN);
    }

    /** 把点燃道具换到主手（原主手物放进刚腾空的背包格）；返回是否已就位 */
    private static boolean equipIgniterToMainHand(EntityMaid maid) {
        try {
            ItemStack main = maid.m_6844_(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
            if (isIgniter(main)) {
                return true;
            }
            ItemStack off = maid.m_6844_(net.minecraft.world.entity.EquipmentSlot.OFFHAND);
            if (isIgniter(off)) {
                // 【R1109】副手里有打火石也算——主副手直接对调，别让她明明带着道具却说没有
                maid.m_8061_(net.minecraft.world.entity.EquipmentSlot.MAINHAND, off);
                maid.m_8061_(net.minecraft.world.entity.EquipmentSlot.OFFHAND, main);
                return true;
            }
            IItemHandler inv = maid.getAvailableBackpackInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack st = inv.getStackInSlot(i);
                if (!isIgniter(st)) {
                    continue;
                }
                ItemStack moved = inv.extractItem(i, st.m_41613_(), false);
                if (moved.m_41619_()) {
                    return false;
                }
                maid.m_8061_(net.minecraft.world.entity.EquipmentSlot.MAINHAND, moved);
                if (!main.m_41619_()) {
                    inv.insertItem(i, main, false); // 该格已腾空，整栈必然放得下
                }
                return true;
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
        }
        return false;
    }

    /**
     * 【R1106/R1107】点一次营火：主手换成打火石/火焰弹 → 置 LIT → 播原版点燃音 → 扣 1 耐久 / 消耗 1 个。
     * 返回 false 只表示"她没道具或方块已不是营火"，调用方据此挂 30 秒冷却。
     */
    private boolean tryLightCampfire(ServerLevel level, EntityMaid maid, BlockPos pos) {
        try {
            BlockState state = level.m_8055_(pos);
            if (isLitCampfire(state)) {
                return true;
            }
            if (!isCampfireBlock(state)) {
                return false;
            }
            if (!hasIgniterInInv(maid)) {
                com.maidsmart.tool.MaidExtraContainer.pull(maid, s -> isIgniter(s), 1);
            }
            if (!equipIgniterToMainHand(maid)) {
                return false;
            }
            ItemStack used = maid.m_6844_(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
            boolean flint = used.m_41720_() instanceof net.minecraft.world.item.FlintAndSteelItem;
            level.m_7731_(pos, state.m_61124_(net.minecraft.world.level.block.CampfireBlock.f_51227_, true), 11);
            level.m_5594_(null, pos,
                    flint ? net.minecraft.sounds.SoundEvents.f_11942_ : net.minecraft.sounds.SoundEvents.f_11874_,
                    net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 0.9F);
            if (flint) {
                used.m_41622_(1, maid, e -> e.m_21190_(net.minecraft.world.InteractionHand.MAIN_HAND));
            } else {
                used.m_41774_(1);
            }
            maid.m_21011_(net.minecraft.world.InteractionHand.MAIN_HAND, true);
            UNLIT_CAMPFIRE_UNTIL.remove(campfireSkipKey(level, maid, pos));
            LOGGER.info("cook ignite: maid={} 用{}点亮了营火 {}",
                    com.maidsmart.tool.PromaidLog.nameOf(maid), flint ? "打火石" : "火焰弹", pos);
            return isLitCampfire(level.m_8055_(pos));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
        }
        return false;
    }

    /**
     * 选台（R1104）：台型优先级每轮流程结束重算——**营火（普通 / 灵魂同级）**最高（开关开 +
     * 身上有营火食材 + 点着 + 工作区内 + 未被占用 + **有空位**，不耗燃料），其次按
     * 背包炉型偏好的烟熏炉/高炉，最后熔炉（含等价族）。满载的营火不入选：先去炉子，
     * 弹出成品腾出空位后下一轮自然回到营火。两类台共用同一张占用表（同维同坐标不撞）。
     * 【R1149】灵魂营火与普通营火同档：营火配方相同、同样不耗燃料，比烟熏炉优先。
     */
    private BlockPos findStation(ServerLevel level, EntityMaid maid) {
        this.nearestLitCampfire = null;
        this.nearestAnyCampfire = null;
        if (campfireOn() && hasCampfireFood(level, maid)) {
            BlockPos fire = this.scanCampfire(level, maid, true);
            if (fire != null) {
                this.stationCampfire = true;
                return fire;
            }
        }
        this.stationCampfire = false;
        return this.findFurnace(level, maid);
    }

    /**
     * 扫营火（R1104 起就近选择）：口径与 findFurnace 同一套（工作区 + 多女仆占用表），
     * {@code needFreeSlot=true} 时只认还有空位的（迁营火用——不为一个满载的火挪窝）。
     * 顺手把"最近看到的点着营火"（无视工作区/占用/空位）记进 {@link #nearestLitCampfire}，
     * 供 {@link #logScanWhy} 说明"为什么没绑上"。
     */
    private BlockPos scanCampfire(ServerLevel level, EntityMaid maid, boolean needFreeSlot) {
        BlockPos pos = maid.m_20183_();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (int dy = -com.maidsmart.config.MaidSmartConfig.MISC_VERTICAL_RANGE.get();
             dy <= com.maidsmart.config.MaidSmartConfig.MISC_VERTICAL_RANGE.get(); dy++) {
            for (int dx = -cookRadius(); dx <= cookRadius(); dx++) {
                for (int dz = -cookRadius(); dz <= cookRadius(); dz++) {
                    BlockPos p = pos.m_7918_(dx, dy, dz);
                    BlockState st = level.m_8055_(p);
                    if (!isCampfireBlock(st)) {
                        continue;
                    }
                    if (this.nearestAnyCampfire == null) {
                        this.nearestAnyCampfire = p; // 第一个扫到的营火（点着与否，诊断用）
                    }
                    String skipKey = campfireSkipKey(level, maid, p);
                    boolean lit = isLitCampfire(st);
                    if (lit) {
                        UNLIT_CAMPFIRE_UNTIL.remove(skipKey); // 火被点亮 → 冷却立刻作废
                        if (this.nearestLitCampfire == null) {
                            this.nearestLitCampfire = p; // 第一个扫到的点着营火（诊断用）
                        }
                    } else if (hasIgniter(maid)) {
                        // 【R1106】熄灭的营火也能绑：她有打火石/火焰弹 → 走去点亮（冷却立刻作废）
                        UNLIT_CAMPFIRE_UNTIL.remove(skipKey);
                    } else {
                        // 没点燃道具：这台火 30 秒内不再检索（拿到道具后下一轮立刻恢复）
                        Long until = UNLIT_CAMPFIRE_UNTIL.get(skipKey);
                        long now = this.lastGameTime;
                        if (until != null && now < until) {
                            continue;
                        }
                        UNLIT_CAMPFIRE_UNTIL.put(skipKey, now + UNLIT_CAMPFIRE_COOLDOWN);
                        continue;
                    }
                    if (!com.maidsmart.follow.WorkAreaClamp.allowsWork(maid, p)) {
                        continue;
                    }
                    // 【R1107】熄灭的营火就算四位全满也要绑——满火更需要点着才烧得起来，
                    // 点亮后成品弹出腾位，她自然会接着摆；只有"已点着"才挑空位
                    if (needFreeSlot && lit && !campfireHasFreeSlot(level, p)) {
                        continue;
                    }
                    String k = furnaceKey(level, p);
                    // 【R1137】走不到/没站位的营火也进同一张冷却表：放弃后 30 秒内不再选回
                    Long stuckUntil = STATION_STUCK_UNTIL.get(k);
                    if (stuckUntil != null) {
                        if (this.lastGameTime < stuckUntil) {
                            continue;
                        }
                        STATION_STUCK_UNTIL.remove(k);
                    }
                    // 【R1137】必须存在可站邻格（与炉子同口径）
                    if (stationStandSpot(level, maid, p) == null) {
                        continue;
                    }
                    java.util.UUID owner = FURNACE_USERS.get(k);
                    if (owner != null && !owner.equals(maid.m_20148_())) {
                        net.minecraft.world.entity.Entity o = level.m_8791_(owner);
                        if (o == null || !o.m_6084_()) {
                            FURNACE_USERS.remove(k);
                        } else {
                            continue;
                        }
                    }
                    double sq = maid.m_20275_(p.m_123341_() + 0.5, p.m_123342_() + 0.5, p.m_123343_() + 0.5);
                    if (sq < bestSq) {
                        bestSq = sq;
                        best = p;
                    }
                }
            }
        }
        return best;
    }

    /** 营火还有没有空食材位（四位全满时摆不进去） */
    private static boolean campfireHasFreeSlot(ServerLevel level, BlockPos p) {
        BlockEntity be = level.m_7702_(p);
        return be instanceof net.minecraft.world.level.block.entity.CampfireBlockEntity cbe
                && cbe.m_59065_().stream().anyMatch(s -> s.m_41619_());
    }

    /**
     * 【R1104·实测一千一百〇五】没绑上台时的"为什么"（同状态只记一次，不刷屏）：
     * 开关 / 身上有无营火食材 / 范围内最近的点着营火（距离、是否在工作区、有无空位）。
     */
    private static final java.util.Map<java.util.UUID, String> LAST_SCAN_OUTCOME = new HashMap<>();

    private void logScanWhy(ServerLevel level, EntityMaid maid) {
        try {
            boolean food = hasCampfireFood(level, maid);
            // 【R1105】没食材时也扫一眼：否则"点着的营火=无"会把"她没肉"误报成"附近没火"
            if (campfireOn() && !food) {
                this.scanCampfire(level, maid, false);
            }
            BlockPos lit = this.nearestLitCampfire;
            BlockPos any = this.nearestAnyCampfire;
            String fire;
            if (lit != null) {
                double dist = Math.sqrt(maid.m_20275_(
                        lit.m_123341_() + 0.5, lit.m_123342_() + 0.5, lit.m_123343_() + 0.5));
                fire = lit + "（距 " + Math.round(dist) + " 格"
                        + (com.maidsmart.follow.WorkAreaClamp.allowsWork(maid, lit) ? "/工作区内" : "/工作区外")
                        + (campfireHasFreeSlot(level, lit) ? "/有空位）" : "/四位全满）");
            } else if (any != null) {
                double dist = Math.sqrt(maid.m_20275_(
                        any.m_123341_() + 0.5, any.m_123342_() + 0.5, any.m_123343_() + 0.5));
                fire = any + "（距 " + Math.round(dist) + " 格/未点着"
                        + (hasIgniter(maid) ? "/身上有打火石→会去点亮）" : "/身上没有点燃道具→30 秒内跳过）");
            } else {
                fire = "无（水平 " + cookRadius() + " 格、垂直 ±"
                        + com.maidsmart.config.MaidSmartConfig.MISC_VERTICAL_RANGE.get() + " 格内没有营火）";
            }
            String msg = "cook scan: maid=" + com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 营火开关=" + (campfireOn() ? "开" : "关")
                    + " 身上营火食材=" + (food ? "有" : "无（把生肉/鱼放进她的背包或附属容器）")
                    + " 点着的营火=" + fire;
            if (!msg.equals(LAST_SCAN_OUTCOME.get(maid.m_20148_()))) {
                LAST_SCAN_OUTCOME.put(maid.m_20148_(), msg);
                LOGGER.info(msg);
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior.logScanWhy", ignored);
        }
    }

    /**
     * 台子旁的站位（R1101 营火 / R1110 炉子与烟熏炉、高炉同口径）：**绝不站台子格**——只挑旁边
     * "脚下不实、头上不实、不是营火/危险方块、下方有可站面"的邻格，就近优先。找不到返回 null。
     * <p>【R1132】营火常常和地面齐平（草方块挖掉再摆进去）：站位必须在台子**上一层**，
     * 只扫同一层的四个邻格永远只看到实心地面 → 返回 null → 绑了又解、看起来完全没动作。
     * 头格也必须用 {@code above()}——历史写法误用了 {@code below()}，把"下方是实心"
     * 当成"上方被堵"，正常摆在草地上的营火同样一个站位都挑不出来。
     */
    private static BlockPos stationStandSpot(ServerLevel level, EntityMaid maid, BlockPos station) {
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos p = station.m_7918_(d.m_122429_(), dy, d.m_122431_());
                BlockState feet = level.m_8055_(p);
                BlockState head = level.m_8055_(p.m_7494_());
                if (feet.m_60734_() instanceof net.minecraft.world.level.block.CampfireBlock
                        || !feet.m_60812_(level, p).m_83281_()
                        || !head.m_60812_(level, p.m_7494_()).m_83281_()) {
                    continue;
                }
                if (com.maidsmart.tool.DangerBlocks.isDanger(feet.m_60734_())
                        || com.maidsmart.tool.DangerBlocks.isDanger(head.m_60734_())) {
                    continue;
                }
                BlockPos support = p.m_7495_();
                if (!level.m_8055_(support).m_60783_(level, support, net.minecraft.core.Direction.UP)) {
                    continue; // 脚下没有可站的面（空中/草/雪层）→ 走了也站不住
                }
                if (!com.maidsmart.follow.WorkAreaClamp.allowsWork(maid, p)) {
                    continue;
                }
                double sq = maid.m_20275_(p.m_123341_() + 0.5, p.m_123342_() + 0.5, p.m_123343_() + 0.5);
                if (sq < bestSq) {
                    bestSq = sq;
                    best = p;
                }
            }
        }
        return best;
    }

    /** 【R1110】台上还有活：炉子进料槽还有这型炉子烧得动的东西 / 营火槽里还有料 */
    private boolean stationBusy(ServerLevel level) {
        try {
            BlockPos pos = this.furnacePos;
            if (pos == null) {
                return false;
            }
            BlockEntity be = level.m_7702_(pos);
            if (this.stationCampfire) {
                if (be instanceof net.minecraft.world.level.block.entity.CampfireBlockEntity cbe) {
                    for (ItemStack s : cbe.m_59065_()) {
                        if (!s.m_41619_()) {
                            return true;
                        }
                    }
                }
                return false;
            }
            if (be instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity f) {
                ItemStack in = f.m_8020_(0);
                return !in.m_41619_() && canSmeltHere(level, be, in);
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
        }
        return false;
    }

    /**
     * 【R1110】附近 5×5×5 内炉子的成品栏自动进包；【R1134】自己绑的那台留给 processFurnace
     * （它才会置 tookOutputThisRound、刷新看门狗）；【R1141】姐妹女仆绑着的台同理不碰——
     * 抢收她的成品会让她的换台判据永远不成立（永远不解绑换位），营火旁的掉落物也留给她。
     */
    private void sweepNearbyOutputs(ServerLevel level, EntityMaid maid) {
        try {
            BlockPos base = maid.m_20183_();
            java.util.List<BlockPos> sisterFires = new java.util.ArrayList<>();
            for (BlockPos p : BlockPos.m_121940_(
                    base.m_7918_(-2, -2, -2), base.m_7918_(2, 2, 2))) {
                if (this.furnacePos != null && p.equals(this.furnacePos)) {
                    continue; // 【R1134】自己绑的台归 processFurnace
                }
                if (!(level.m_7702_(p)
                        instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity f)) {
                    // 【R1141】姐妹占用的营火：她的成品掉落物也留给她自己收
                    if (isCampfireBlock(level.m_8055_(p))
                            && this.stationOwnedByOther(level, maid, p)) {
                        sisterFires.add(p.m_7949_());
                    }
                    continue;
                }
                // 【R1141】姐妹女仆绑着的台不碰：成品必须留给她自己的 processFurnace 收，
                // 被抢先收走后她的 tookOutputThisRound 永远为假 → 换台判据永远不成立。
                if (this.stationOwnedByOther(level, maid, p)) {
                    continue;
                }
                ItemStack out = f.m_8020_(2);
                if (out.m_41619_()) {
                    continue;
                }
                ItemStack taken = f.m_8016_(2);
                ItemStack left = ItemHandlerHelper.insertItemStacked(
                        maid.getAvailableBackpackInv(), taken, false);
                if (!left.m_41619_()) {
                    left = com.maidsmart.tool.MaidExtraContainer.overflow(maid, left);
                }
                if (!left.m_41619_()) {
                    f.m_6836_(2, left);
                }
                if (left.m_41613_() != taken.m_41613_()) {
                    maid.m_21011_(net.minecraft.world.InteractionHand.MAIN_HAND, true);
                    this.logRound(maid, f.getClass().getSimpleName(), "sweep",
                            "附近成品=" + taken + (left.m_41619_() ? "" : " 余=" + left));
                }
            }
            this.sweepCampfireDrops(level, maid, sisterFires); // 【R1136】周身 5×5 的营火成品（烤肉掉火外）
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
        }
    }

    /** 【R1141】这个台是不是正被别的（尚存活）女仆占着——成品/掉落物该留给她自己收。 */
    private boolean stationOwnedByOther(ServerLevel level, EntityMaid maid, BlockPos p) {
        String k = furnaceKey(level, p);
        java.util.UUID owner = FURNACE_USERS.get(k);
        if (owner == null || owner.equals(maid.m_20148_())) {
            return false;
        }
        net.minecraft.world.entity.Entity o = level.m_8791_(owner);
        if (o != null && o.m_6084_()) {
            return true;
        }
        FURNACE_USERS.remove(k); // 占用者没了 → 释放
        return false;
    }

    /** 【R1110】一句气泡（无燃料/无烧制物时提醒主人） */
    private static void say(EntityMaid maid, String text) {
        try {
            maid.getChatBubbleManager().addTextChatBubble(text);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
        }
    }

    /**
     * 【R1117/R1137】"没有能烧的原料"只在对**周围所有台子**判定完之后才说（30 秒一次）。
     *
     * <p>旧版只问一座台（或只问背包里第一件料的炉型），于是"旁边一台烟熏炉吃不下她的矿"就播报
     * 没原料——可她包里明明有铁，只是那台炉子用不上。现在问 {@link #anyNearbyStationCanCook}：
     * 半径内每一台炉子/营火都问一遍"你能不能吃下她身上的某件料"，全都不能才播报。
     */
    private void notifyNoIngredient(ServerLevel level, EntityMaid maid) {
        try {
            long now = level.m_46467_();
            if (now - this.noIngredientVoiceAt < 600L) {
                return; // 30 秒内刚播过 → 连台子都不用重扫
            }
            if (this.anyNearbyStationCanCook(level, maid)) {
                return; // 周围至少有一台用得上她的料 → 不是"没原料"
            }
            this.noIngredientVoiceAt = now;
            say(maid, "没有能烧的原料了…主人，给我一点生食或矿石吧？");
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
        }
    }

    /**
     * 【R1137】"周围所有台子"的最终判定：半径内有没有**任何一台**能吃下她身上（背包/手/附属容器）
     * 的东西。只读探测，不占台、不改绑定。
     *
     * <p>刻意**不看**占用、冷却、工作圈与站位：那些是"这台现在用不了"，不是"她没有原料"。
     * 只要还有一台理论上烧得动她的料，就不该喊"没有能烧的原料"。
     * 营火食材同理——半径内存在营火，就算它没点着/满位，也算"有地方可烧"。
     */
    private boolean anyNearbyStationCanCook(ServerLevel level, EntityMaid maid) {
        try {
            boolean campfireFood = campfireOn() && hasCampfireFood(level, maid);
            boolean anyCampfire = false;
            BlockPos base = maid.m_20183_();
            for (int dy = -com.maidsmart.config.MaidSmartConfig.MISC_VERTICAL_RANGE.get();
                 dy <= com.maidsmart.config.MaidSmartConfig.MISC_VERTICAL_RANGE.get(); dy++) {
                for (int dx = -cookRadius(); dx <= cookRadius(); dx++) {
                    for (int dz = -cookRadius(); dz <= cookRadius(); dz++) {
                        BlockPos p = base.m_7918_(dx, dy, dz);
                        BlockState bs = level.m_8055_(p);
                        if (isCampfireBlock(bs)) {
                            anyCampfire = true;
                            continue;
                        }
                        if (!isFurnaceFamilyBlock(level, p, bs)) {
                            continue;
                        }
                        if (!com.maidsmart.config.MaidSmartConfig.MISC_COOK_SMOKER_BLAST.get()
                                && !furnaceFamilyOn()
                                && !(bs.m_60734_() instanceof net.minecraft.world.level.block.FurnaceBlock)) {
                            continue; // 开关关着时她只操作熔炉，烟熏炉/高炉不算
                        }
                        if (this.furnaceMatchesInv(level, maid, p)) {
                            return true; // 这台吃得上她的料
                        }
                    }
                }
            }
            return campfireFood && anyCampfire;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
        }
        return false;
    }

    /** 营火绑定还值不值得保持：还有食材、或火上还烤着东西（烤好弹出后她会捡） */
    private boolean campfireStillWorth(ServerLevel level, EntityMaid maid, BlockPos pos) {
        if (hasCampfireFood(level, maid)) {
            return true;
        }
        BlockEntity be = level.m_7702_(pos);
        return be instanceof net.minecraft.world.level.block.entity.CampfireBlockEntity cbe
                && cbe.m_59065_().stream().anyMatch(s -> !s.m_41619_());
    }

    /**
     * 营火一轮（R1101）：① 先捡成品——原版烤好会 {@code m_18992_} 弹出掉落物堆在
     * 火上，她站在旁边主动收进背包；② 再摆一个食材（满载时原版 {@code m_59051_}
     * 返回 empty，同一道闸）。节奏与熔炉一致：每 {@code processCooldown} 一轮。
     */
    private boolean processCampfire(ServerLevel level, EntityMaid maid,
                                    net.minecraft.world.level.block.entity.CampfireBlockEntity be) {
        boolean changed = false;
        this.tookOutputThisRound = false; // 【R1104】每轮重置，捡回弹出的成品才算完成流程
        BlockPos pos = be.m_58899_();
        try {
            this.tookOutputThisRound = collectCampfireDrops(level, maid, pos);
            changed = this.tookOutputThisRound;
            // 【R1105】与熔炉链路同口径：食材在附属容器里也要先取一件到背包再摆
            com.maidsmart.tool.MaidExtraContainer.pull(maid, s -> isCampfireFood(level, s), 1);
            IItemHandler inv = maid.getAvailableBackpackInv();
            boolean placed = false;
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack st = inv.getStackInSlot(i);
                if (!isCampfireFood(level, st)) {
                    continue;
                }
                java.util.Optional<net.minecraft.world.item.crafting.CampfireCookingRecipe> recipe =
                        be.m_59051_(st);
                if (recipe.isEmpty()) {
                    break; // 四个位都满——等下一轮
                }
                ItemStack one = inv.extractItem(i, 1, false);
                if (one.m_41619_()) {
                    continue;
                }
                ItemStack placing = one.m_41777_(); // placeFood 会消耗入参，日志改用副本
                if (be.m_238284_(maid, one, recipe.get().m_43753_())) {
                    maid.m_21011_(net.minecraft.world.InteractionHand.MAIN_HAND, true);
                    this.logRound(maid, "Campfire", "campfire", "placed=" + placing);
                    changed = true;
                    placed = true;
                }
                break; // 一轮只摆一个
            }
            if (!placed) {
                changed |= placeFromHands(level, maid, be); // 【R1107】背包塞不下：手里的肉直接摆
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
        }
        return changed;
    }

    /** 【R1107】背包满了也烤得成：从手部槽直接取一件食材摆上营火（武器/工具不动，四位全满等下一轮） */
    private boolean placeFromHands(ServerLevel level, EntityMaid maid,
                                   net.minecraft.world.level.block.entity.CampfireBlockEntity be) {
        try {
            IItemHandler hands = maid.getHandsInvWrapper();
            for (int i = 0; i < hands.getSlots(); i++) {
                ItemStack st = hands.getStackInSlot(i);
                if (st.m_41619_() || !isCampfireFood(level, st)) {
                    continue;
                }
                java.util.Optional<net.minecraft.world.item.crafting.CampfireCookingRecipe> recipe =
                        be.m_59051_(st);
                if (recipe.isEmpty()) {
                    return false;
                }
                ItemStack one = hands.extractItem(i, 1, false);
                if (one.m_41619_()) {
                    continue;
                }
                ItemStack placing = one.m_41777_();
                if (!be.m_238284_(maid, one, recipe.get().m_43753_())) {
                    return false;
                }
                maid.m_21011_(net.minecraft.world.InteractionHand.MAIN_HAND, true);
                this.logRound(maid, "Campfire", "campfire", "placed=" + placing);
                return true;
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
        }
        return false;
    }

    /**
     * 【R1109】把营火旁弹出的成品捡进背包/附属容器；返回是否真的收回了成品。
     * 与 {@link #processCampfire} 同一套口径（只捡营火配方成品），换台/解绑前也走它。
     */
    private boolean collectCampfireDrops(ServerLevel level, EntityMaid maid, BlockPos pos) {
        return this.collectDropsIn(level, maid, cookDropBox(maid, pos));
    }

    /**
     * 【R1136】烧炉子时也收**她周身 5×5**的营火成品：营火烤好会随机弹出，落在火外 2~3 格很常见；
     * 旧版只在绑着营火时、以火为中心 1.5 格内捡，她守着熔炉就永远看不见掉在旁边的烤肉。
     * 与 {@link #collectCampfireDrops} 同一道过滤（只捡营火配方成品，不碰玩家丢的东西）。
     */
    private void sweepCampfireDrops(ServerLevel level, EntityMaid maid,
                                    java.util.List<BlockPos> sisterFires) {
        this.collectDropsIn(level, maid,
                new net.minecraft.world.phys.AABB(maid.m_20183_()).m_82400_(2.0),
                e -> {
                    // 【R1141】姐妹占用的营火旁 3 格内的成品留给她自己收
                    for (BlockPos c : sisterFires) {
                        double dx = e.m_20185_() - (c.m_123341_() + 0.5);
                        double dz = e.m_20189_() - (c.m_123343_() + 0.5);
                        if (dx * dx + dz * dz <= 9.0) {
                            return true;
                        }
                    }
                    return false;
                });
    }

    /** 【R1136】营火自身 ±1.5 与"她周身 5×5"的合并盒：两边哪个够得到都算 */
    private static net.minecraft.world.phys.AABB cookDropBox(EntityMaid maid, BlockPos pos) {
        BlockPos b = maid.m_20183_();
        return new net.minecraft.world.phys.AABB(
                Math.min(b.m_123341_(), pos.m_123341_()) - 2.0,
                Math.min(b.m_123342_(), pos.m_123342_()) - 2.0,
                Math.min(b.m_123343_(), pos.m_123343_()) - 2.0,
                Math.max(b.m_123341_(), pos.m_123341_()) + 3.0,
                Math.max(b.m_123342_(), pos.m_123342_()) + 3.0,
                Math.max(b.m_123343_(), pos.m_123343_()) + 3.0);
    }

    /** 在 box 里捡营火配方成品进背包/附属容器；返回是否真的收回了东西 */
    private boolean collectDropsIn(ServerLevel level, EntityMaid maid,
                                   net.minecraft.world.phys.AABB box) {
        return this.collectDropsIn(level, maid,
                box, null);
    }

    private boolean collectDropsIn(ServerLevel level, EntityMaid maid,
                                   net.minecraft.world.phys.AABB box,
                                   java.util.function.Predicate<
                                           net.minecraft.world.entity.item.ItemEntity> skip) {
        boolean took = false;
        int n = 0;
        try {
            for (net.minecraft.world.entity.item.ItemEntity e : level.m_45976_(
                    net.minecraft.world.entity.item.ItemEntity.class, box)) {
                if (skip != null && skip.test(e)) {
                    continue; // 【R1141】姐妹营火的成品不碰
                }
                ItemStack st = e.m_32055_();
                if (st.m_41619_() || !isCampfireResult(level, st)) {
                    continue; // 只捡营火配方的成品——别人丢在火边的东西不碰
                }
                ItemStack left = ItemHandlerHelper.insertItemStacked(
                        maid.getAvailableBackpackInv(), st, false);
                if (!left.m_41619_()) {
                    // 【R1107】背包满 → 继续塞附属容器（精妙背包等），别让烤好的肉掉在地上
                    left = com.maidsmart.tool.MaidExtraContainer.overflow(maid, left);
                }
                if (left.m_41613_() == st.m_41613_()) {
                    continue; // 背包与附属容器都满——留在原地
                }
                n += st.m_41613_() - left.m_41613_();
                if (left.m_41619_()) {
                    e.m_142687_(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                } else {
                    e.m_32045_(left);
                }
                maid.m_21011_(net.minecraft.world.InteractionHand.MAIN_HAND, true);
                took = true;
            }
            if (took) {
                this.logRound(maid, "Campfire", "drop", "附近营火成品×" + n);
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
        }
        return took;
    }

    /**
     * 【R1109】解绑/换台前先把这一台里已经烧好的东西收回来：炉子取成品槽（含附属容器溢出），
     * 营火捡弹出的成品。绝不让"她走了、成品还落在炉子/火里"发生。
     * 背包与附属容器都满时余量留在原处（下一轮再试），不会凭空销毁。
     */
    private void harvestStation(ServerLevel level, EntityMaid maid) {
        BlockPos pos = this.furnacePos;
        if (pos == null) {
            return;
        }
        try {
            if (this.stationCampfire) {
                if (collectCampfireDrops(level, maid, pos)) {
                    this.logRound(maid, "Campfire", "harvest", "解绑前收回成品");
                }
                return;
            }
            BlockEntity be = level.m_7702_(pos);
            if (!(be instanceof net.minecraft.world.Container furnace)) {
                return;
            }
            ItemStack result = furnace.m_8020_(2);
            if (result.m_41619_()) {
                return;
            }
            ItemStack taken = furnace.m_8016_(2);
            ItemStack left = ItemHandlerHelper.insertItemStacked(maid.getAvailableBackpackInv(), taken, false);
            if (!left.m_41619_()) {
                left = com.maidsmart.tool.MaidExtraContainer.overflow(maid, left);
            }
            if (!left.m_41619_()) {
                furnace.m_6836_(2, left);
            }
            if (left.m_41613_() != taken.m_41613_()) {
                maid.m_21011_(net.minecraft.world.InteractionHand.MAIN_HAND, true);
                this.logRound(maid, be.getClass().getSimpleName(), "harvest",
                        "解绑前收回成品=" + taken + (left.m_41619_() ? "" : " 余=" + left));
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
        }
    }

    /** 这件东西是不是营火配方的<b>成品</b>（收掉落物用——只捡烤出来的，不捡别的掉落物） */
    private static boolean isCampfireResult(ServerLevel level, ItemStack stack) {
        try {
            for (net.minecraft.world.item.crafting.CampfireCookingRecipe r : level.m_7465_()
                    .m_44013_(net.minecraft.world.item.crafting.RecipeType.f_44111_)) {
                ItemStack result = r.m_8043_(level.m_9598_());
                if (!result.m_41619_() && result.m_41720_() == stack.m_41720_()) {
                    return true;
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCookBehavior", ignored);
        }
        return false;
    }
}
