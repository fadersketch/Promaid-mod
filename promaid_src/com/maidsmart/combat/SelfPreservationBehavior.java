package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自保行为（v1.5.1）——纯规则实现，零 LLM 延迟。
 *
 * 覆盖范围：不再限定战斗任务——女仆在【任何状态】下（战斗/挖矿/做饭/
 * 跟随/待命/睡觉）血量过低且附近存在威胁时，停止当前一切行为转入自保。
 *
 * 优先级：注册为 core 行为优先级 250。TLM 自身 core 行为最高为 99
 * （MaidBrain.registerCoreGoals 反编译证据：ClearSleep=99、Pickup=10、
 * FollowOwner=3、Panic/Await=1），250 保证自保是全部行为里最高的，
 * 跟随/任务/恐慌全部被压制，不会被任何状态"吃掉"。
 *
 * 状态机（滞回区间防抖）：
 * - 进入：HP < 30% 且 12 格内存在威胁（当前攻击目标 或 最近敌对怪物）
 * - 自保三策略（每 tick 判定）：
 *   1. 使用物品：背包里的金苹果/熟食直接进食（真实回血/吸收/再生，40 tick 冷却）
 *   2. 逃跑：朝威胁反方向以 1.4 倍速撤离——直接走 PathNavigation，
 *      因为 Brain 的移动执行器 MoveToTargetSink（优先级 2）在自保期间
 *      不会被启动，WALK_TARGET memory 无人执行
 *   3. 搭方块：威胁贴身（<4 格）时往脚下垫方块（圆石/泥土/木板，1 秒一层），
 *      近战怪够不着；头顶有方块时不垫（防窒息）
 * - 退出（恢复自保前的状态）：
 *   - HP ≥ 70% → 解除自保
 *   - 威胁消失 且 HP ≥ 45% → 解除自保（危险没了就回去干活）
 *   自保期间不修改任务/跟随/待命设置，解除后 Brain 自动恢复低优先级行为，
 *   女仆回到自保前正在做的事（战斗→重新索敌、挖矿→继续挖、跟随→继续跟）。
 */
public class SelfPreservationBehavior extends Behavior<EntityMaid> {
    /**
     * 自保标记（persistentData）：TLM 的 MaidFollowOwnerTask 在自保期间
     * 读到该标记会拒绝启动，从而禁止"低血逃跑时被传送回主人身边送死"。
     */
    public static final String PRESERVE_TAG = "maid_smart_preserving";

    /** v1.5.166：自保诊断日志（latest.log 搜 self preserve 定位触发/退出/传送） */
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /**
     * v1.1.0 实测十七：战斗搭方块追踪表（维度 → 位置 → 放置信息）。
     *
     * 旧设计：自保（搭高/翻墙/搭桥/封头盖帽/岩浆垫高）搭的方块【永久留下】——
     * 用户当初选择不清理。现改为与挖矿/伐木/搭路同款的打标签机制：
     * - 60 秒后自然消失（战斗方块战斗用，打完仗战场该清干净——比挖矿的 10 秒长，
     *   因为战斗节奏多变，女仆可能还要在塔上待一阵）
     * - 女仆可以直接破坏自己搭的战斗方块（挡路就拆——实际战斗多变，防止女仆
     *   自己搭了个死路还逃不出去）
     * - bridge.reclaimToMaid 开关（垫脚方块回收进背包）同样覆盖这里的回收
     * - 玩家替换过的方块不误破坏（blockId 比对，与挖矿/搭路同口径）
     */
    public record CombatPlacedMark(long tick, String blockId) {
    }

    private static final java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
            java.util.Map<BlockPos, CombatPlacedMark>> COMBAT_PLACED = new java.util.HashMap<>();

    /** 战斗方块登记（放置点统一调用） */
    private static void trackCombatPlaced(EntityMaid maid, BlockPos pos, Block block) {
        if (!(maid.m_9236_() instanceof ServerLevel sl)) {
            return;
        }
        net.minecraft.resources.ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
        COMBAT_PLACED.computeIfAbsent(sl.m_46472_(), k -> new java.util.HashMap<>())
                .put(pos.m_7949_(), new CombatPlacedMark(sl.m_46467_(),
                        key != null ? key.toString() : ""));
    }

    /** 该位置是否是女仆搭的战斗方块（挡路破坏判定用） */
    public static boolean isCombatPlaced(net.minecraft.world.level.Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel sl)) {
            return false;
        }
        java.util.Map<BlockPos, CombatPlacedMark> marks = COMBAT_PLACED.get(sl.m_46472_());
        return marks != null && marks.containsKey(pos.m_7949_());
    }

    /**
     * 战斗方块到期销毁（ProMaidExtension 每 tick 调；60 秒）。
     * 女仆还站在上面的（搭高塔）延后一轮——supportsBridger 同款保护。
     * bridge.reclaimToMaid 开启时掉落直接进附近女仆背包（复用搭路的回收链路）。
     */
    public static void expireCombatPlaced(ServerLevel level, long gameTime) {
        java.util.Map<BlockPos, CombatPlacedMark> marks = COMBAT_PLACED.get(level.m_46472_());
        if (marks == null || marks.isEmpty()) {
            return;
        }
        long lifetime = com.maidsmart.config.MaidSmartConfig.COMBAT_PLACED_LIFETIME.get() * 20L;
        java.util.Iterator<java.util.Map.Entry<BlockPos, CombatPlacedMark>> it =
                marks.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<BlockPos, CombatPlacedMark> e = it.next();
            if (gameTime - e.getValue().tick < lifetime) {
                continue;
            }
            BlockPos pos = e.getKey();
            if (com.maidsmart.task.BridgeUpBehavior.supportsBridgerPublic(level, pos)) {
                // v1.1.0 实测十八：站在上面【刷新计时】而不是无限延后（同挖矿/伐木/
                // 搭路修复）——塔上打持久战时脚下块重置寿命，走开后还有完整 60 秒
                // 缓冲，不会整根塔瞬间全到期把她摔下去
                e.setValue(new CombatPlacedMark(gameTime, e.getValue().blockId));
                continue;
            }
            it.remove();
            destroyCombatMarked(level, pos, e.getValue());
        }
    }

    /** 服务器停止清场（残留战斗方块立即回收） */
    public static void clearCombatPlaced(net.minecraft.server.MinecraftServer server) {
        for (ServerLevel level : server.m_129785_()) {
            java.util.Map<BlockPos, CombatPlacedMark> marks = COMBAT_PLACED.remove(level.m_46472_());
            if (marks == null) {
                continue;
            }
            for (java.util.Map.Entry<BlockPos, CombatPlacedMark> e : marks.entrySet()) {
                destroyCombatMarked(level, e.getKey(), e.getValue());
            }
        }
        COMBAT_PLACED.clear();
    }

    /** 销毁一个战斗方块：玩家换过的不误破坏；reclaimToMaid 开启进背包（同搭路口径） */
    private static void destroyCombatMarked(ServerLevel level, BlockPos pos, CombatPlacedMark mark) {
        var state = level.m_8055_(pos);
        if (state.m_60795_()) {
            return;
        }
        net.minecraft.resources.ResourceLocation cur = ForgeRegistries.BLOCKS.getKey(state.m_60734_());
        if (!mark.blockId.isEmpty() && cur != null && !mark.blockId.equals(cur.toString())) {
            return; // 玩家已替换，尊重改动
        }
        level.m_46796_(2001, pos, net.minecraft.world.level.block.Block.m_49956_(state));
        if (com.maidsmart.config.MaidSmartConfig.BRIDGE_RECLAIM_TO_MAID.get()) {
            java.util.List<net.minecraft.world.item.ItemStack> drops =
                    net.minecraft.world.level.block.Block.m_49869_(state, level, pos, null);
            EntityMaid nearest = com.maidsmart.task.BridgeUpBehavior.findNearestMaidPublic(level, pos);
            boolean handed = false;
            if (nearest != null && !drops.isEmpty()) {
                try {
                    net.minecraftforge.items.wrapper.CombinedInvWrapper inv = nearest.getAvailableInv(true);
                    for (net.minecraft.world.item.ItemStack stack : drops) {
                        if (stack.m_41619_()) {
                            continue;
                        }
                        net.minecraft.world.item.ItemStack remain = net.minecraftforge.items.ItemHandlerHelper
                                .insertItemStacked(inv, stack, false);
                        if (!remain.m_41619_()) {
                            net.minecraft.world.level.block.Block.m_49840_(level, pos, remain);
                        }
                    }
                    handed = true;
                } catch (Exception ignored) {
                }
            }
            if (!handed && !drops.isEmpty()) {
                for (net.minecraft.world.item.ItemStack stack : drops) {
                    net.minecraft.world.level.block.Block.m_49840_(level, pos, stack);
                }
            }
        } else {
            net.minecraft.world.level.block.Block.m_49892_(state, level, pos, level.m_7702_(pos));
        }
        level.m_7731_(pos, net.minecraft.world.level.block.Blocks.f_50016_.m_49966_(), 3);
    }

    /**
     * v1.5.135：最近攻击者记录（女仆 UUID → 攻击者）。
     *
     * 背景：findThreat 原版只认"当前攻击目标 + 原版 Monster 类"——其他 mod 的
     * 敌对生物若不继承 Monster（自定义基类），或中立生物反咬/PVP，女仆被打
     * 也检测不到威胁，自保不触发。这里用 LivingHurtEvent 记录 5 秒内的攻击者，
     * 无论什么类都算威胁（配合原版视野/距离判定）。
     */
    private record AttackerRecord(int entityId, long gameTick,
                                  net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim) {
    }

    private static final java.util.Map<java.util.UUID, AttackerRecord> LAST_ATTACKERS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** v1.5.135：女仆被攻击 → 记录攻击者（5 秒内视为威胁）。主人误伤不记。 */
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onMaidHurt(net.minecraftforge.event.entity.living.LivingHurtEvent event) {
        if (!(event.getEntity() instanceof EntityMaid maid)) {
            return;
        }
        // v1.5.213：坐下时受伤 → 立刻站起来（任何来源：怪物/环境/玩家）。
        // 站起来后自然脱离"坐下锁移动"判定——锁只作用于安然坐着时，受伤起身
        // 即恢复战术/自保/逃生的完整移动能力；环境伤害（岩浆每秒 4 血）也走
        // LivingHurtEvent，坐着被烫会立即起身逃生
        if (maid.isMaidInSittingPose()) {
            maid.m_21837_(false);
        }
        net.minecraft.world.damagesource.DamageSource src = event.getSource();
        if (src == null || !(src.m_7639_() instanceof LivingEntity attacker)) {
            return; // 自然伤害/无来源伤害不记（走环境逃生分支）
        }
        if (attacker == maid) {
            return; // 反伤/自伤不算
        }
        LivingEntity owner = maid.m_269323_();
        if (owner != null && owner == attacker) {
            return; // 主人误伤不记（防"主人挥剑女仆逃跑 5 秒"）
        }
        long now = maid.m_9236_().m_46467_();
        LAST_ATTACKERS.put(maid.m_20148_(),
                new AttackerRecord(attacker.m_19879_(), now, maid.m_9236_().m_46472_()));
        // 顺手清理过期（超 256 条才清，成本可忽略）
        if (LAST_ATTACKERS.size() > 256) {
            LAST_ATTACKERS.entrySet().removeIf(e -> e.getValue().gameTick() < now - 1200L);
        }
    }

    /** v1.5.136：公开查询 5 秒内最近攻击过女仆的存活生物（smart_attack 工具用） */
    public static LivingEntity recentAttacker(EntityMaid maid) {
        AttackerRecord rec = LAST_ATTACKERS.get(maid.m_20148_());
        if (rec == null) {
            return null;
        }
        long now = maid.m_9236_().m_46467_();
        if (now - rec.gameTick() >= 100L || !rec.dim().equals(maid.m_9236_().m_46472_())) {
            return null;
        }
        net.minecraft.world.entity.Entity e = ((ServerLevel) maid.m_9236_()).m_6815_(rec.entityId());
        if (e instanceof LivingEntity lv && lv.m_6084_()) {
            return lv;
        }
        return null;
    }

    /** v1.5.102：以下自保数值全部从配置面板读取（combat 段，玩家可调） */
    private static float safeReturnRatio() {
        return (float) (double) com.maidsmart.config.MaidSmartConfig.COMBAT_SAFE_RETURN_RATIO.get();
    }

    /** 近距离判定（持续这个距离内视为"被近身难以逃脱"） */
    private static double closeDistance() {
        return com.maidsmart.config.MaidSmartConfig.COMBAT_CLOSE_DISTANCE.get();
    }

    /** v1.5.186：搭高上限（至多向上搭多少个方块，不再按近战/远程划分） */
    private static int pillarMax() {
        return com.maidsmart.config.MaidSmartConfig.COMBAT_PILLAR_MAX.get();
    }

    private static int healCooldown() {
        return com.maidsmart.config.MaidSmartConfig.COMBAT_HEAL_COOLDOWN.get();
    }

    /**
     * 搭方块节奏（v1.5.186：原"近战 4 tick / 远程 3 tick"面板项已删除，统一 4 tick
     * ≈ 0.2 秒/块——太快实体来不及被顶起会窒息；配合 buildUp 的实际头顶空气检查，
     * 彻底防窒息）
     */
    private static int buildCooldown() {
        return 4;
    }

    /** 威胁扫描间隔（tick）：AABB 查询有成本 */
    private static int threatScanInterval() {
        return com.maidsmart.config.MaidSmartConfig.COMBAT_THREAT_SCAN.get();
    }

    private static float fleeSpeed() {
        return (float) (double) com.maidsmart.config.MaidSmartConfig.COMBAT_FLEE_SPEED.get();
    }

    /** 卡住判定：窗口内位移低于阈值视为卡住（借鉴 numen UnstuckDetector） */
    private static int stuckWindow() {
        return com.maidsmart.config.MaidSmartConfig.COMBAT_STUCK_WINDOW.get();
    }

    private static double stuckThreshold() {
        return com.maidsmart.config.MaidSmartConfig.COMBAT_STUCK_THRESHOLD.get();
    }

    /** 威胁消失超过该时长 → 传送回主人身边 */
    private static int threatGoneExit() {
        return com.maidsmart.config.MaidSmartConfig.COMBAT_THREAT_GONE_EXIT.get();
    }
    /** v1.5.21：末影珍珠逃生阈值（v1.5.24：血 <30% 且威胁 <8 格——放宽触发，
     *  珍珠是真正脱身手段，优先级高于搭高/进食，避免"一直搭方块吃东西却脱不了身"）
     *  v1.5.101f：数值改从配置面板读取（combat 段，玩家可调） */
    private static float pearlThreshold() {
        return (float) (double) com.maidsmart.config.MaidSmartConfig.COMBAT_PEARL_RATIO.get();
    }

    private static double pearlThreatDist() {
        return com.maidsmart.config.MaidSmartConfig.COMBAT_PEARL_DIST.get();
    }

    /** v1.5.21：珍珠逃生冷却（v1.5.101f：默认 100 tick = 5 秒，从配置面板读取） */
    private static int pearlCooldown() {
        return com.maidsmart.config.MaidSmartConfig.COMBAT_PEARL_COOLDOWN.get();
    }
    /** v1.5.102：传送回家冷却（默认 1200 tick = 60 秒，防"传回家又被打→又传"循环） */
    private static int teleportCooldown() {
        return com.maidsmart.config.MaidSmartConfig.COMBAT_TELEPORT_COOLDOWN.get();
    }
    /** v1.5.112：传送安全判定半径（自己/主人身边此半径内无可见怪物才传；默认 4，原写死 6） */
    private static double teleportSafeRadius() {
        return com.maidsmart.config.MaidSmartConfig.COMBAT_TELEPORT_SAFE_RADIUS.get();
    }

    /** v1.5.102：瞬间类自救动作间隔（蜂蜜/金苹果短 CD，默认 40 tick = 2 秒）——
     *  v1.5.252g7：持续型药水不再用它（按药水种类记 CD = 药水时长） */
    private static int potionCooldown() {
        return com.maidsmart.config.MaidSmartConfig.COMBAT_POTION_COOLDOWN.get();
    }

    /** v1.5.102：头顶警示粒子间隔（默认 60 tick = 3 秒） */
    private static int alertCooldown() {
        return com.maidsmart.config.MaidSmartConfig.COMBAT_ALERT_COOLDOWN.get();
    }

    /** v1.5.102：策略播报间隔（默认 200 tick = 10 秒，防刷屏） */
    private static int announceCooldown() {
        return com.maidsmart.config.MaidSmartConfig.COMBAT_ANNOUNCE_COOLDOWN.get();
    }
    /** v1.5.21：危险方块（逃跑/落点避让） */
    private static final String[] DANGER_BLOCKS = {
            "minecraft:lava", "minecraft:fire", "minecraft:magma_block",
            "minecraft:cactus", "minecraft:sweet_berry_bush", "minecraft:campfire"
    };

    /** 治疗食物（v1.5.201：金苹果/附魔金苹果已移出——不作为常规食物吃，
     *  走 useGoldenApple 即时增益道具路径，与药水同级） */
    private static final String[] HEAL_FOODS = {
            "minecraft:cooked_beef", "minecraft:cooked_porkchop", "minecraft:cooked_chicken",
            "minecraft:cooked_mutton", "minecraft:cooked_rabbit", "minecraft:cooked_cod",
            "minecraft:cooked_salmon", "minecraft:bread", "minecraft:apple"
    };

    // v1.5.24：搭方块不再限白名单——takeBuildBlock 动态取"背包里数量最多的安全实心方块"
    //（任何 BlockItem + 完整碰撞形状 + 非下落/非 TNT/非伤害方块），旧 BUILD_BLOCKS 已删除

    private int healCooldown = 0;
    private int buildCooldown = 0;
    private int scanCooldown = 0;
    /** 缓存的威胁实体（每 THREAT_SCAN_INTERVAL tick 刷新一次） */
    private LivingEntity cachedThreat = null;
    /** 卡住检测（借鉴 numen UnstuckDetector）：连续 STUCK_WINDOW tick 位移 < 阈值 */
    private double lastStuckX = 0;
    private double lastStuckZ = 0;
    private int stuckTicks = 0;
    /** 威胁已消失的连续 tick 数（v1.5.20；v1.5.153 退出机制改为血≥70%/传送成功，此字段已弃用） */
    private int threatGoneTicks = 0;
    /** v1.5.20：连续近身 tick 数（被近身难以逃脱 → 搭高） */
    private int closeTicks = 0;
    /** v1.5.20：垫高起始地面 y（用于计算当前垫高高度） */
    private int pillarBaseY = -1;
    /** v1.5.21：珍珠逃生冷却计数 */
    private int pearlCooldown = 0;
    /** v1.5.21：上次传送时间（游戏 tick，冷却防循环） */
    private long lastTeleportTime = -1200;
    /** v1.5.227："我回到主人身边了"播报限频（60 秒）——传送冷却 5 秒时反复连传
     *  连喊（实测 02:07-02:11 每 5~25 秒喊一次），播报单独冷却避免刷屏 */
    private long lastHomeAnnounceTick = -1200;
    /** v1.5.21：绕路尝试计数（卡住先绕路，绕不开再搭高） */
    private int detourTicks = 0;
    /** v1.5.21：蛇形走位计时与侧偏方向 */
    private int zigzagTick = 0;
    private int zigzagSide = 1;
    /** v1.5.21：药水尝试间隔 */
    /** v1.5.252g7【按药水种类记 CD】：key = 药水注册名（minecraft:long_swiftness
     *  等）或固定名（honey/golden_apple）。同种药水 CD = 该药水最长效果时长，
     *  CD 内【同种不再喝、其他种照喝】；瞬间治疗短 CD（40 tick）可连喝。
     *  旧版单一 potionCooldown：喝一种药水后所有药水都不能喝（浪费） */
    private final java.util.Map<String, Long> potionCds = new java.util.HashMap<>();

    private boolean potionReady(String key, long now) {
        Long t2 = this.potionCds.get(key);
        if (t2 == null) {
            return true;
        }
        if (now < t2) {
            return false;
        }
        this.potionCds.remove(key); // 过期条目懒清理，防 Map 膨胀
        return true;
    }

    private void markPotionUsed(String key, long now, int cd) {
        this.potionCds.put(key, now + Math.max(40, cd));
    }

    /** 药水【效果种类】key（如 minecraft:swiftness）——"同一种药水" CD 的 key。
     *  v1.5.252g9：long_/strong_ 前缀与形态（饮用/喷溅/滞留）不计——同效果不同
     *  变体视为同种（否则普通/延长/喷溅/滞留抗火各是一个 key，跳岩浆会把每种
     *  变体各投/喝一瓶，实测"多扔了不少药水"） */
    private static String potionKey(ItemStack stack) {
        try {
            net.minecraft.world.item.alchemy.Potion potion =
                    net.minecraft.world.item.alchemy.PotionUtils.m_43579_(stack);
            if (potion == null) {
                return "unknown";
            }
            net.minecraft.resources.ResourceLocation key = ForgeRegistries.POTIONS.getKey(potion);
            if (key == null) {
                return "unknown";
            }
            String id = key.toString(); // minecraft:long_swiftness
            int dot = id.indexOf(':');
            String ns = dot >= 0 ? id.substring(0, dot + 1) : "minecraft:";
            String base = dot >= 0 ? id.substring(dot + 1) : id;
            if (base.startsWith("long_")) {
                base = base.substring(5);
            }
            if (base.startsWith("strong_")) {
                base = base.substring(7);
            }
            return ns + base; // minecraft:swiftness
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    /** v1.5.21：头顶警示粒子间隔 */
    private int alertCooldown = 0;
    /** v1.5.23：策略播报冷却 */
    private int announceCooldown = 0;
    private boolean announcedNoMaterial = false;
    /** v1.5.24：连续被近身计数（≥2 触发"击退+强制搭高"） */
    private int grappleTicks = 0;
    /** v1.5.24：强制搭高状态（连续两次被近身后进入，持续往上垫、不逃跑，防自己掉下去） */
    private boolean forcedPillar = false;
    /** v1.5.194：搭方块中途的窒息封头尝试预算（每场最多 3 次 = 6 块，防耗尽背包） */
    private int suffocateBudget = 0;
    /** v1.5.143：近身回击冷却（tick）——被贴脸时直接挥刀反击，纯逃跑永远脱不了困 */
    private int meleeCooldown = 0;
    /** v1.5.158：是否已播报过"情况不妙"（扫到第一个威胁才播报——无威胁的低血
     *  自保 = 安静回血/回家，避免"传送回家→又进场"循环刷矛盾提示） */
    private boolean announcedThreat = false;
    /** v1.5.199：本次自保是否已播报过环境提示（岩浆/溺水/卡墙，每场一次） */
    private boolean announcedEnv = false;
    /** v1.5.232：本次自保是否已播报过"包里什么都没有，我没招了！救救我！"
     *  （岩浆/着火链路全失败提示，每场一次） */
    private boolean announcedNoResource = false;
    /** v1.5.232：本场自保是否成功用过任一自救资源（垫块/水桶/珍珠/抗火/金苹果/
     *  灭火器）——链路全失败时播报"我没招了"用 */
    private boolean resourceUsed = false;
    /** v1.5.233：环境逃生链路（岩浆 300 > 着火 280 > 自保 250）全失败时间戳——
     *  全失败后 5 秒（100 tick）内恢复正常状态、判断丢给其他链路（自保战斗/
     *  传送/战术/任务），5 秒后仍危险再试环境逃生（防永久摆烂） */
    private long envGiveUpTick = -1000;
    /** v1.5.199：临时放水位置与放置时刻（击退垫水/岩浆灭火；v1.5.204 起 3 秒后收回，
     *  水桶不消耗） */
    private net.minecraft.core.BlockPos waterPos = null;
    private long waterPlacedTick = 0;
    /** v1.5.250：下界倒水节流 tick——下界水会瞬间蒸发，放水灭火无效，但用户明确
     *  要求女仆"喜欢用水桶"：每隔 40 tick（2 秒）尝试倒一次水（动作/蒸汽可见），
     *  其余时间流转珍珠/抗火/逃跑，防止每 tick 傻倒水刷屏 */
    private long netherPourTick = 0;
    /** v1.5.204：附近岩浆感知（bug 3）——每 10 tick 扫一次半径 3（含流动岩浆），
     *  提前绕开而非踩进去才反应；cachedFleeSpot = 避让目标（离岩浆 ≥4 格安全点） */
    private int lavaScanCooldown = 0;
    private net.minecraft.core.BlockPos cachedNearLava = null;
    private net.minecraft.core.BlockPos cachedFleeSpot = null;
    /** v1.5.209：连续处于岩浆内的 tick 数（m_6725_ 每 tick 维护）——"安静走出"
     *  只对浅接触有效，超过 1.5 秒（30 tick，v1.5.216：原 3 秒实测太长）还在
     *  岩浆里说明走不出去（安全格被岩浆隔断/在对岸），必须转主分支 2（垫方块/
     *  倒水/抗火/珍珠） */
    private int walkOutTimer = 0;
    /** v1.5.216：最后泡在岩浆里的游戏 tick——垫高脱离后 30 tick 内仍视为"岩浆
     *  上下文"（卡墙播报保护用：垫高瞬间位移滞后 headInSolid 误报，不该播"卡住"） */
    private long lastInLavaTick = -1000;
    /** v1.5.225 临时诊断：Brain 运行行为日志节流（latest.log 搜 "brain-run"） */
    private long diagTick = -1;
    /** v1.5.216：逃跑方向防抖——fleeEscaping 20 tick 内保持同一目标，治
     *  "疯狂逃窜逃得特别远"（旧版每 tick 重选方向，怪物分布变化→目标乱跳） */
    private int fleeDirCooldown = 0;
    private double fleeTargetX = 0;
    private double fleeTargetZ = 0;
    /** v1.5.202（轻量级自保，落地水格式）：保命会话是否激活（环境危险或低血 <30%
     *  的上升沿进入，血 ≥70% 且环境安全后结束）——会话进出由 m_6725_ 维护，
     *  行为本身常驻（canUse 只查开关） */
    private boolean sessionActive = false;
    /** v1.5.245：会话退出稳定窗口（tick）——满足退出条件后先观察 20 tick（1 秒），
     *  防 danger 短暂消失（浅岩浆接触/移动卡墙抖动）就退出、下一 tick 又进入的
     *  频繁进出（日志实证 07:22 两次 self preserve start 间隔仅 0.5 秒） */
    private int exitStableTicks = 0;

    /**
     * v1.5.202（轻量级自保）：移动接管协调——自保【正在执行移动类保命动作】
     * （濒死垫高/逃跑 或 环境逃生导航）的女仆集合。core 行为并行（Brain 按
     * 优先级逐个运行所有 canUse 的行为），自保 250 先 tick 后战术 230 也会 tick；
     * 若战术此时也控制移动（走位/跳劈），会与自保的垫高/逃跑互相覆盖。战术
     * tick 开头查此集合：自保在保命移动 → 本 tick 让位。瞬时动作（喝药/金苹果/
     * 传送）不进此集合——那些与战斗不冲突。
     */
    private static final java.util.Set<java.util.UUID> MOVING_SURVIVE =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 战术（MaidCombatTacticsBehavior）协调用：女仆是否正在自保的移动保命动作中 */
    public static boolean isMovingToSurvive(EntityMaid maid) {
        return MOVING_SURVIVE.contains(maid.m_20148_());
    }

    public SelfPreservationBehavior() {
        super(java.util.Collections.emptyMap());
    }

    private static float hpRatio(EntityMaid maid) {
        return maid.m_21223_() / Math.max(1.0f, maid.m_21233_());
    }

    @Override
    protected boolean m_6114_(ServerLevel level, EntityMaid maid) {
        // v1.5.202（轻量级自保，落地水格式）：常驻检查器——canUse 只查开关，
        // 是否真正需要保命由 m_6725_ 每 tick 内部判定（环境危险/低血），
        // 不需要时零干预（不占用任何行为、不干扰战斗/任务/战术）
        return com.maidsmart.config.MaidSmartConfig.COMBAT_SELF_PRESERVE.get();
    }

    /** v1.5.135：致命环境危险（岩浆）——任何血量立即逃生
     *  v1.5.225：抗火豁免——喝了抗火药水后泡岩浆不掉血也不点燃，不算致命；
     *  （"身上有抗火效果还是惊慌失措逃跑"的根因：岩浆分支不受豁免） */
    private boolean envDangerCritical(EntityMaid maid) {
        return maid.m_20077_() && !hasFireResist(maid); // isInLava 且无抗火
    }

    /** v1.5.135：软环境危险（着火 / 溺水 / 卡墙窒息）——85% 血量以下才逃。
     *  v1.5.199：卡墙判定收窄为"头部卡实心"（headInSolid）——旧 bodyInSolid
     *  身体中心判定在狭窄地下地形被尸壳推挤/半身卡进方块时误报，导致高血量
     * （95%）也反复触发自保（日志 "self preserve start hp=95%" 刷屏、搭方块
     *  刚放 1-2 块就被打断的根因之一）。
     *  v1.5.216：着火加抗火豁免——喝了抗火药水后即使身上还在烧（m_6060_ 仍
     *  true）也不掉血，不该再惊慌失措/疯狂逃窜（用户实测："服用抗火药水后
     *  仍然处于惊慌失措状态"）；等火自然灭即可。 */
    private boolean envDangerSoft(EntityMaid maid) {
        return (maid.m_6060_() && !hasFireResist(maid))                // isOnFire 且无抗火
                || (maid.m_20069_() && maid.m_20146_() < 60)           // isInWater 且氧气不足 1/5
                || this.headInSolid(maid);                             // 头部卡进实心方块（真窒息）
    }

    /** v1.5.88：读配置面板（combat 段） */
    private static float enterRatio() {
        return (float) (double) com.maidsmart.config.MaidSmartConfig.COMBAT_ENTER_RATIO.get();
    }

    private static float exitRatio() {
        return (float) (double) com.maidsmart.config.MaidSmartConfig.COMBAT_EXIT_RATIO.get();
    }

    private static int threatDistance() {
        return com.maidsmart.config.MaidSmartConfig.COMBAT_THREAT_DISTANCE.get();
    }

    /** v1.5.203：搭高安全高度（补完目标，默认 5）——与落地水触发高度配对：
     *  垫到 5 格跳下，fallDistance 到落地水阈值（3.0）时离地还有约 2 格，
     *  稳定触发落地水（水减速怪物的小配合） */
    private static int safePillarHeight() {
        return com.maidsmart.config.MaidSmartConfig.COMBAT_PILLAR_SAFE_HEIGHT.get();
    }

    @Override
    protected void m_6735_(ServerLevel level, EntityMaid maid, long gameTime) {
        // v1.5.202（轻量级自保，落地水格式）：行为常驻（canUse 只查开关），
        // 本方法只在行为生命周期开头执行一次；真正的"会话进场"动作由
        // m_6725_ 的 sessionEnter（低血/环境危险的上升沿）执行
    }

    /**
     * v1.5.202：会话进场（低血 <30% 或环境危险的上升沿）——重置会话字段 +
     * 清一次残留目标 + 松弓 + 图腾 + 诊断日志。常驻检查器下这就是原"触发自保"
     * 的进场动作（每次真正需要保命时执行一次）。
     */
    private void sessionEnter(EntityMaid maid) {
        this.healCooldown = 0;
        this.buildCooldown = 0;
        this.scanCooldown = 0;
        this.stuckTicks = 0;
        this.cachedThreat = null;
        this.closeTicks = 0;
        this.pillarBaseY = -1;
        this.forcedPillar = false;
        this.grappleTicks = 0;
        this.suffocateBudget = 3; // v1.5.194：每场自保会话 3 次窒息封头尝试
        this.announceCooldown = 0;
        this.meleeCooldown = 0;
        this.announcedThreat = false;
        this.announcedEnv = false;
        this.announcedNoMaterial = false;
        this.announcedNoResource = false;
        this.resourceUsed = false;
        this.envGiveUpTick = -1000;
        this.exitStableTicks = 0;
        this.waterPos = null;
        this.waterPlacedTick = 0;
        this.netherPourTick = 0;
        this.lavaScanCooldown = 0;
        this.cachedNearLava = null;
        this.cachedFleeSpot = null;
        this.walkOutTimer = 0;
        // 进场清一次残留攻击目标（轻量化后保命期间不再每 tick 清——目标保留，
        // 战术 230 并行战斗，"边打边保命"）
        maid.m_6710_(null);
        maid.m_6274_().m_21936_(MemoryModuleType.f_26372_);
        maid.m_6274_().m_21936_(MemoryModuleType.f_26370_); // 清 WALK_TARGET 残留
        // v1.5.166：强制松开弓/弩/一切使用中的物品（拉弓状态会干扰保命动作）
        maid.m_5810_();
        // v1.5.142：把背包里的不死图腾放进饰品栏（有空槽才放；配合
        // MaidBaubleTotemMixin 死亡时从饰品栏触发复活）
        this.tryEquipBaubleTotem(maid);
        // v1.5.166：触发诊断日志（latest.log 搜 "self preserve start"）
        net.minecraft.world.item.Item handItem = maid.m_21205_().m_41720_();
        String weaponName = handItem == null ? "empty"
                : net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(handItem).toString();
        LOGGER.info("self preserve start: maid={} hp={}% weapon={}",
                maid.m_5446_() != null ? maid.m_5446_().getString() : maid.m_20148_(),
                String.format("%.0f", this.hpRatio(maid) * 100.0f), weaponName);
    }

    /**
     * v1.5.142：自保触发时尝试把不死图腾放入饰品栏（TLM BaubleItemHandler，30 格）。
     * - 饰品栏里已有图腾 → 不动
     * - 有空槽 → 从背包取 1 个图腾放入（setStackInSlot 直写，绕过 bauble 物品校验——
     *   原版图腾不是注册 bauble，insertItem 会拒绝）
     * - 饰品栏满 → 不放（不覆盖已有饰品）
     */
    private void tryEquipBaubleTotem(EntityMaid maid) {
        try {
            com.github.tartaricacid.touhoulittlemaid.inventory.handler.BaubleItemHandler bauble =
                    maid.getMaidBauble();
            for (int i = 0; i < bauble.getSlots(); i++) {
                if (bauble.getStackInSlot(i).m_150930_(net.minecraft.world.item.Items.f_42747_)) {
                    return; // 已有图腾（TOTEM_OF_UNDYING）
                }
            }
            int empty = -1;
            for (int i = 0; i < bauble.getSlots(); i++) {
                if (bauble.getStackInSlot(i).m_41619_()) {
                    empty = i;
                    break;
                }
            }
            if (empty < 0) {
                return; // 饰品栏满了 → 不放
            }
            IItemHandler inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (s.m_41619_() || !s.m_150930_(net.minecraft.world.item.Items.f_42747_)) {
                    continue;
                }
                ItemStack taken = inv.extractItem(i, 1, false);
                if (!taken.m_41619_()) {
                    bauble.setStackInSlot(empty, taken);
                }
                return;
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * v1.5.145：近身反击分发——主手是投射武器（弓/弩）→ 贴脸直接放箭；
     * 近战/三叉戟 → 直接挥砍（m_7327_，三叉戟戳有 7 点伤害，横扫走 TLM 逻辑）。
     * v1.5.147：御币（弹幕武器）优先于弓/弩判定——御币是 ProjectileWeaponItem
     * 子类，但攻击方式是射弹幕（DanmakuShoot），不是射箭。
     */
    private boolean counterAttack(EntityMaid maid, LivingEntity threat) {
        ItemStack main = maid.m_21205_();
        if (com.github.tartaricacid.touhoulittlemaid.item.ItemHakureiGohei.isGohei(main)) {
            return this.goheiDanmaku(maid, threat);
        }
        if (main.m_41720_() instanceof net.minecraft.world.item.ProjectileWeaponItem) {
            return this.shootPointBlank(maid, threat);
        }
        return maid.m_7327_(threat);
    }

    /**
     * v1.5.147：御币弹幕还击——TLM DanmakuShoot 链，与 danmaku_attack 任务完全
     * 一致的射击流程（随机颜色/弹型 + 瞄准射击）。伤害/速度/精度公式照抄
     * TaskDanmakuAttack 字节码实证：
     * - 伤害 = 攻击力属性 × (power+1.2)，power=1.0（满蓄）→ ×2.2
     * - 速度 = 0.3×(power+1)×(1+急速附魔) + clamp(距离/40-0.4, 0, 2.4)（无附魔简化）
     * - 精度 = (1 - clamp(距离/100, 0, 0.8)) / 5（aimedShot 瞄准分支同款）
     * - 无重力弹幕；迟钝/末影人附魔简化不读（贴脸自救，基础伤害已够）
     */
    private boolean goheiDanmaku(EntityMaid maid, LivingEntity threat) {
        try {
            net.minecraft.world.level.Level level = maid.m_9236_();
            double dist = maid.m_20270_(threat);
            float atk = 2.0f;
            net.minecraft.world.entity.ai.attributes.AttributeInstance ai =
                    maid.m_21051_(net.minecraft.world.entity.ai.attributes.Attributes.f_22281_);
            if (ai != null) {
                atk = (float) ai.m_22115_();
            }
            float damage = atk * 2.2f;
            float velocity = 0.6f + net.minecraft.util.Mth.m_14036_(
                    (float) (dist / 40.0 - 0.4), 0.0f, 2.4f);
            float inaccuracy = (1.0f - net.minecraft.util.Mth.m_14036_(
                    (float) (dist / 100.0), 0.0f, 0.8f)) / 5.0f;
            com.github.tartaricacid.touhoulittlemaid.entity.projectile.DanmakuShoot.create()
                    .setWorld(level)
                    .setThrower(maid)
                    .setTarget(threat)
                    .setRandomColor()
                    .setRandomType()
                    .setDamage(damage)
                    .setGravity(0.0f)
                    .setVelocity(velocity)
                    .setHurtEnderman(false)
                    .setInaccuracy(inaccuracy)
                    .setImpedingLevel(0)
                    .aimedShot();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * v1.5.145：贴脸放箭——原版箭矢实体直射威胁（不模拟拉弓/装填，贴脸自救简化）。
     * 消耗背包 1 根箭（普通/药水/光灵箭均可，ArrowItem 子类都算）；背包没箭则不射
     * （继续逃跑）。箭矢伤害/命中走原版流程（普通箭基础 2 点）。
     */
    private boolean shootPointBlank(EntityMaid maid, LivingEntity threat) {
        try {
            ItemStack arrowStack = this.takeArrow(maid);
            if (arrowStack.m_41619_() || !(arrowStack.m_41720_() instanceof net.minecraft.world.item.ArrowItem ai)) {
                return false; // 背包没箭 → 不射（继续逃跑）
            }
            net.minecraft.world.level.Level level = maid.m_9236_();
            net.minecraft.world.entity.projectile.AbstractArrow arrow = ai.m_6394_(level, arrowStack, maid);
            arrow.m_5602_(maid); // setOwner
            // 瞄准威胁胸口（原版 Mob 射击同款：目标中心 + 高度 30%）
            double dx = threat.m_20185_() - maid.m_20185_();
            double dy = threat.m_20186_() + threat.m_20206_() * 0.3
                    - (maid.m_20186_() + maid.m_20206_() * 0.5);
            double dz = threat.m_20189_() - maid.m_20189_();
            double dist = Math.sqrt(dx * dx + dz * dz);
            arrow.m_6686_(dx, dy + dist * 0.08, dz, 1.6f, 1.0f); // shoot（1.6 速，贴脸必中）
            level.m_7967_(arrow);
            maid.m_21011_(net.minecraft.world.InteractionHand.MAIN_HAND, true); // 挥臂
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 从背包取 1 根箭（普通/药水/光灵）；没有返回空栈 */
    private ItemStack takeArrow(EntityMaid maid) {
        IItemHandler inv = maid.getMaidInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s.m_41619_() || !(s.m_41720_() instanceof net.minecraft.world.item.ArrowItem)) {
                continue;
            }
            ItemStack taken = inv.extractItem(i, 1, false);
            if (!taken.m_41619_()) {
                return taken;
            }
        }
        return ItemStack.f_41583_;
    }

    @Override
    protected void m_6725_(ServerLevel level, EntityMaid maid, long gameTime) {
        // v1.5.204：临时放水到点回收【必须在会话提前 return 之前】——放水救完人
        // 会话往往立即结束（危险消失），旧位置在会话 return 之后 → 水永远留在
        // 地图上（"放下的水一直不消失"的根因）
        this.tickRecoverWater(maid);
        // v1.5.204：附近岩浆感知（bug 3）——半径 3 内任何岩浆（含流动）即视为
        // 危险，提前绕开；10 tick 节流防每 tick 全量方块扫描
        if (this.lavaScanCooldown-- <= 0) {
            this.lavaScanCooldown = 10;
            this.cachedNearLava = this.findNearbyLava(maid);
            this.cachedFleeSpot = this.cachedNearLava == null ? null
                    : this.fleeLavaSpot(maid, this.cachedNearLava);
        }
        // v1.5.209：岩浆接触计时——连续在岩浆内的 tick 累计，脱离即归零。
        // walk-out 分支据此判定"1.5 秒没走出去"→ 转主分支 2（不把时间耗在
        // 明明走不出去的裸奔上）。v1.5.216：顺带记录最后泡岩浆的 tick（卡墙
        // 播报保护用）
        this.walkOutTimer = maid.m_20077_() ? this.walkOutTimer + 1 : 0;
        if (maid.m_20077_()) {
            this.lastInLavaTick = maid.m_9236_().m_46467_();
        }
        // ===== v1.5.202（轻量级自保，落地水格式）：常驻检查器——会话进出维护 =====
        // v1.5.232：附近岩浆不再进危险态——避让降级为仅移动层修正
        // （avoidLavaMovement，会话外）：只改移动方向/速度，不打断建造/跟随/
        // 插火把等其他行为（"岩浆湖附近常驻危险态"的根因）。真正泡进岩浆
        // （envDangerCritical）或着火/溺水/卡墙（envDangerSoft）才进自保会话。
        boolean danger = this.envDangerCritical(maid) || this.envDangerSoft(maid);
        float ratio = this.hpRatio(maid);
        // v1.5.212：坐下锁移动——"坐下但仍然在移动"的来源是 promaid 自己的
        // 行为不检查坐下姿势（战术 230 走位/后撤、避让 150 逃离——TLM 自己的
        // 跟随/逃跑/游泳都检查 isMaidInSittingPose）。每 tick 判定：坐下且无环境
        // 危险 → 清导航/寻路目标/速度，任何来源都动不了她（危险时例外：岩浆/着火
        // 必须站起来逃命，不锁；v1.5.213：坐下受伤会立刻站起来，站起来后自然
        // 不再走这个判定——锁只作用于"安然坐着"的状态）
        boolean sitting = maid.isMaidInSittingPose();
        if (sitting && !danger) {
            maid.m_6274_().m_21936_(MemoryModuleType.f_26370_); // 清 WALK_TARGET
            maid.m_21573_().m_26573_();                          // navigation.stop()
            // v1.5.348：只清水平速度、保留垂直(y)——旧版整速度清零导致坐姿女仆
            // 下落时重力每 tick 被抹掉、永远加不起速度 → "一直坐着缓慢下落"
            // （还顺带免疫摔落伤害,坐姿被架空成缓降模式）。与 MaidStationaryMixin
            // v1.5.98c 同款教训:保留 y 才能正常下落/上浮/落地(水中不溺水)。
            net.minecraft.world.phys.Vec3 v = maid.m_20184_();
            maid.m_20256_(new net.minecraft.world.phys.Vec3(0.0, v.f_82480_, 0.0));
        }
        // v1.5.281：负面效果自清——【会话外也生效】：旧版蜂蜜瓶检查在会话主流程
        // 内（下方 sessionActive return 之后），满血中毒（血未低到触发自保、无
        // 环境危险）时会话不激活 → 提前 return → 蜂蜜瓶永远不会喝（用户："女仆
        // 面对中毒竟然不用蜂蜜瓶"）。蜂蜜解毒 + 牛奶清负面提升为常驻轻量逻辑
        //（每 tick 只查效果状态，有负面效果才扫背包），与自保会话完全解耦
        this.tickCureNegativeEffects(maid);
        if (!this.sessionActive) {
            if (danger || ratio < enterRatio()) {
                this.sessionActive = true;
                this.sessionEnter(maid); // 上升沿：重置会话字段/图腾/日志
            }
        }
        if (!this.sessionActive) {
            // v1.5.232：会话外岩浆避让——仅移动层修正（正走向岩浆才改道），
            // 零其他干预（不碰目标/移动/任何行为，战术/任务照常）
            this.avoidLavaMovement(maid);
            return;
        }
        // v1.5.241：会话内也做移动层避让——被僵尸追着逃跑（flee/fleeEscaping）
        // 时方向可能踩岩浆，同样改道离岩浆最远安全点；环境危险（泡岩浆/着火）
        // 由下方 environmentalEscape 接管，avoidLavaMovement 内部已排除岩浆中
        this.avoidLavaMovement(maid);
        // 会话结束：血恢复且环境安全——v1.5.245 加 20 tick（1 秒）稳定窗口：
        // danger 短暂消失（浅岩浆接触/移动卡墙抖动）不立即退出，避免"退出→又进"
        // 频繁 start（日志实证 07:22 hp=100%→95% 间隔 0.5 秒两次 self preserve start）
        if (ratio >= exitRatio() && !danger) {
            if (this.exitStableTicks < 20) {
                this.exitStableTicks++;
            } else {
                this.sessionActive = false;
                maid.getPersistentData().m_128379_(PRESERVE_TAG, false);
                MOVING_SURVIVE.remove(maid.m_20148_());
                // v1.5.164：血恢复退出且离主人太远 → 尝试传回主人身边
                this.teleportHomeOnExit(maid);
                return;
            }
        } else {
            this.exitStableTicks = 0; // 仍低血/危险 → 重置稳定计数
        }
        maid.getPersistentData().m_128379_(PRESERVE_TAG, true);
        // 移动接管协调：濒死有威胁 / 环境危险 / 坐下 → 战术（230）本 tick 让位
        //（自保正在执行垫高/逃跑/环境逃生导航/坐下锁定，战术的走位/跳劈会互相覆盖移动）
        // v1.5.212：sitting 并入——坐下时战术恒让位（配合上面每 tick 清移动，
        // 根治"坐下仍移动"：战术不清目标会下一 tick 又拉起她走）
        boolean moving = danger || sitting || (this.cachedThreat != null && ratio < 0.3f);
        if (moving) {
            MOVING_SURVIVE.add(maid.m_20148_());
        } else {
            MOVING_SURVIVE.remove(maid.m_20148_());
        }
        // v1.5.202（轻量级自保）：不再每 tick 清攻击意图——core 行为之间本就
        // 并行（Brain 按优先级逐个运行所有 canUse 的行为），清目标 + 断 sensor/
        // StartAttacking 只会让战术（230）拿不到目标 → "只逃不打"。现在目标
        // 正常存在：自保（250）只做保命动作，战术（230）同 tick 并行战斗——
        // "边打边保命"。仅进场（sessionEnter）清一次残留旧目标。
        // v1.5.152：自保全程【实时判定】能否回主人身边——不再等"威胁消失 20 秒"
        //（逃跑中怪一直追，原威胁判定让传送形同虚设）。每 tick 尝试传送回主人
        //（传送冷却内不重试；主人身边安全即传，传成功立即结束自保）。
        // 自保刚触发时也立即尝试——主人身边安全就直接回家，比就地周旋更安全。
        this.teleportHome(maid);
        // v1.5.140：搭方块边缘保护——搭高进行中每 tick 钳制位置（照搬玩家潜行
        // 防掉落效果，移速不变）：防止女仆移速过快时冲出垫方块边缘飞出去
        //（挖矿 v1.5.87 pillarGuard 同款机制；环境逃生分支内显式调用）
        if (this.forcedPillar || this.pillarBaseY >= 0) {
            this.edgeGuard(maid);
        }
        // v1.5.25：每 tick 防窒息兜底——半身卡进方块立即强制上移到方块顶面之上
        //（搭方块后实体位移滞后/放偏时，头顶检查拦不住横向卡入，必须直接改位置）
        this.antiSuffocate(maid);
        if (this.healCooldown > 0) {
            this.healCooldown--;
        }
        if (this.buildCooldown > 0) {
            this.buildCooldown--;
        }
        if (this.pearlCooldown > 0) {
            this.pearlCooldown--;
        }
        if (this.meleeCooldown > 0) {
            this.meleeCooldown--;
        }
        if (this.scanCooldown-- <= 0) {
            this.scanCooldown = threatScanInterval();
            this.cachedThreat = this.findThreat(maid);
        }
        // v1.5.158：扫到第一个威胁才播报"情况不妙"（每场自保一次）——
        // 无威胁的低血自保安静回血/回家，不喊话
        if (!this.announcedThreat && this.cachedThreat != null) {
            this.announcedThreat = true;
            maid.getChatBubbleManager().addTextChatBubble("情况不妙，我先撤了！");
        }
        // v1.5.21：头顶警示粒子（让主人一眼发现她在危险中）
        this.spawnAlert(maid);
        // v1.5.202（轻量级自保）：环境致命/软危险【优先于战斗】——岩浆/着火/溺水/
        // 窒息在任何威胁状态下都先保命（自然伤害比怪物更急，之前只在无威胁分支
        // 处理，岩浆里被怪打会"边打边烧"）。环境逃生接管移动，战术经
        // isMovingToSurvive 让位。
        // v1.5.233（判定阶梯）：环境逃生 300 岩浆 > 280 着火 > 270 溺水 > 260 卡墙
        // > 自保 250——前级判定必须有结果（成功 return / 全失败让位）后才轮到后级；
        // 环境逃生全部做出判断后才进行其他判断（自保战斗/传送/战术/任务）。
        if (danger) {
            long nowGt = maid.m_9236_().m_46467_();
            if (nowGt - this.envGiveUpTick >= 100) {
                this.environmentalEscape(maid);
                this.edgeGuard(maid);
                return;
            }
            // v1.5.233：环境链路已全失败（5 秒让位窗口内）——恢复正常状态，
            // 判断丢给其他链路（大概率触发自保 250 的战斗/传送/搭高，但不是
            // 直接锁定在自保）；giveUpMovement 仅保留无物品的保命移动兜底
            // （泡岩浆→游向岸边 / 着火→朝主人走）
            this.giveUpMovement(maid);
            // 不 return：继续主流程，其他链路接管
        }
        LivingEntity threat = this.cachedThreat;
        if (threat == null) {
            // v1.5.199：搭高惯性——本次自保已开始搭高时威胁丢失不立即中断，
            // 继续垫到安全高度（safePillarHeight，v1.5.203 起配置化）再停。
            // 地下怪贴墙/绕墙时 hasSight 视线判定让威胁 5 tick 级抖动，
            // "搭两块就停→被围殴打下来"（搭方块积极性下降的直接原因之一）
            if (this.pillarBaseY >= 0) {
                int icy = maid.m_20183_().m_123342_();
                if (icy <= this.pillarBaseY) {
                    this.pillarBaseY = -1;
                }
                int ih = this.pillarBaseY >= 0 ? icy - this.pillarBaseY : 0;
                if (ih < safePillarHeight()) {
                    if (this.buildCooldown <= 0 && this.buildUp(maid)) {
                        this.buildCooldown = buildCooldown();
                        return;
                    }
                    if (this.buildCooldown <= 0) {
                        this.forcedPillar = false; // 没材料/头顶被堵 → 结束强制搭高
                    }
                } else {
                    this.forcedPillar = false; // 已到安全高度，安心回血
                }
            }
            // v1.5.135：无生物威胁但环境危险 → 环境逃生（v1.5.202 已提前到上方
            // 统一判定，任何威胁状态下都优先保命；这里不再重复）
            // 无威胁：安心回血（v1.5.24：进食只在无威胁/安全距离，不再被围殴还一直吃）
            // v1.5.234：回血资源阶梯（前一档失败才下一档，缺物品各档内部提示）：
            // 增益药水(再生省瓶) → 治疗药水(瞬间) → 金苹果(珍贵最后) → 食物(饱食)
            this.tryHealLadder(maid);
            // v1.5.153：传送已在 tick 开头实时判定（自保取消只有血≥70% / 传送成功两种）
            return; // 原地进食回血
        }
        double dist = maid.m_20270_(threat);
        // v1.5.202（轻量级自保）：非濒死（血≥30%）→ 只做瞬时保命动作——喝药/
        // 金苹果 + 搭高惯性补完，移动与战斗交给战术（230）并行处理：
        // "边打边保命"，自保不再长期霸占移动/逃跑（与落地水同构：需要时插一个
        // 动作，不需要时零干预）。濒死（血<30%）才进入下方全套保命移动分支。
        if (this.hpRatio(maid) >= 0.3f) {
            // v1.5.234：威胁中即时回血阶梯（治疗药水 → 金苹果，不打断战斗/搭高）
            this.tryInstantHeal(maid);
            // 搭高惯性：已搭到一半 → 补到安全高度（safePillarHeight），防"搭一半丢下"
            if (this.pillarBaseY >= 0) {
                int pcy = maid.m_20183_().m_123342_();
                if (pcy <= this.pillarBaseY) {
                    this.pillarBaseY = -1;
                }
                int ph = this.pillarBaseY >= 0 ? pcy - this.pillarBaseY : 0;
                if (ph < safePillarHeight() && this.buildCooldown <= 0 && this.buildUp(maid)) {
                    this.buildCooldown = buildCooldown();
                }
            }
            return; // 其余交给战术（230 同 tick 并行战斗）
        }
        // v1.5.199：濒死喝药——贴脸分支前先即时回血（治疗药水是即时动作，
        // 不打断搭方块/反击）。原逻辑回血只在无威胁/8 格外，贴脸被尸壳群殴时
        // 永远轮不到喝药（"饿死/被围殴血量见底也不喝治疗药水"的自保断点）
        // v1.5.234：统一走即时回血阶梯（治疗药水 → 金苹果）
        this.tryInstantHeal(maid);
        // v1.5.143：近身回击——敌人贴脸（<2.5 格）时直接还手，任何策略
        // （逃跑/搭高/珍珠/围壁）之前优先处理：直接调 m_7327_（doHurtTarget，
        // 含横扫），不写 brain 目标（自保每 tick 清 ATTACK_TARGET 的机制不变，
        // 反击完继续原策略——纯逃跑永远脱不了困，边跑边打才能拉开身位）
        // v1.5.145：远程武器（弓/弩）贴脸时不再用武器敲（敲一下几乎没伤害），
        // 改为直接放箭（消耗背包箭矢，无箭不射）；近战/三叉戟维持挥砍
        if (dist < 2.5 && this.meleeCooldown <= 0) {
            if (this.counterAttack(maid, threat)) {
                this.meleeCooldown = 12; // ≈ 玩家平A节奏（攻击完 0.6 秒再还手）
            }
        }
        // v1.5.186：搭高上限/节奏统一（不再按敌人近战/远程划分）
        int maxPillar = pillarMax();
        int buildCd = buildCooldown();

        /* ============ 自保技能优先级链（v1.5.24 整理）============
         * 1. 珍珠逃生：血 <30% 且威胁 <8 格 → 扔珍珠瞬移（真正脱身，最高优先）
         * 2. 连续近身爆发：连续 2 次被近身 → 击退周围敌人 + 强制搭高
         * 3. 强制搭高：爆发后持续垫高（防窒息检查），期间不逃跑（防掉下去）
         * 4. 被近身搭高：垫到安全高度（4 块，v1.5.186 统一）后站桩等
         * 5. 逃跑：威胁 4~12 格 → 隐身/迅捷药水 + 反方向撤离
         * 6. 回血：威胁在 8 格外（相对安全）顺带回血
         * 7. 卡住兜底：逃跑中长时间没位移 → 垫台阶翻越
         * v1.5.194：搭方块中途顺手对贴身的威胁窒息封头（见 trySuffocateDuringBuild）
         * ==================================================== */

        // 1. 珍珠逃生（脱身 > 拖延：血 30% 以下且威胁贴身，立即瞬移）
        // v1.5.157：已搭高（pillarBaseY/forcedPillar——高处已安全）不再扔珍珠
        //（日志实证：搭高到安全位置后仍扔珍珠，用户反馈）
        if (this.hpRatio(maid) < pearlThreshold() && dist < pearlThreatDist()
                && !this.forcedPillar && this.pillarBaseY < 0) {
            if (this.pearlEscape(maid, threat)) {
                return; // 已瞬移走，下 tick 重新评估
            }
        }
        // 2. 连续两次被近身 → 击退周围敌人 + 强制搭高
        if (dist < closeDistance()) {
            if (!this.forcedPillar) {
                this.grappleTicks++;
                if (this.grappleTicks >= 2) {
                    this.grappleTicks = 0;
                    this.triggerPillarBurst(maid);
                }
            }
        } else {
            this.grappleTicks = 0;
        }
        // 3. 强制搭高模式：持续往上垫，期间【不逃跑】（防"搭高了又自己掉下去"）
        if (this.forcedPillar) {
            int fy = maid.m_20183_().m_123342_();
            if (this.pillarBaseY >= 0 && fy <= this.pillarBaseY) {
                this.pillarBaseY = -1;
            }
            int fh = this.pillarBaseY >= 0 ? fy - this.pillarBaseY : 0;
            if (fh >= maxPillar) {
                // v1.5.194：搭到顶 → 站在最高处等威胁消失（原"糊脸"已删除——
                // 30 格时怪物早丢索敌，封头无意义；窒息封头改为搭方块中途触发）
                this.buildCooldown = buildCd;
            } else if (this.buildCooldown <= 0) {
                if (this.buildUp(maid)) {
                    this.buildCooldown = buildCd;
                    // v1.5.194：搭方块中途的额外判定——威胁还贴在脚下时顺手窒息封头
                    this.trySuffocateDuringBuild(maid, threat);
                } else {
                    this.forcedPillar = false; // 头顶被堵/无材料 → 结束强制
                }
            }
            return; // 强制搭高期间不做其他动作
        }
        // 4. 被近身：垫到安全高度后站桩（不再无脑一直搭）
        int currentY = maid.m_20183_().m_123342_();
        if (this.pillarBaseY >= 0 && currentY <= this.pillarBaseY) {
            this.pillarBaseY = -1;
        }
        int pillarHeight = this.pillarBaseY >= 0 ? currentY - this.pillarBaseY : 0;
        // v1.5.186：安全高度统一（v1.5.203 起配置化——默认 5，与落地水触发
        // 高度配对：垫到 5 格跳下稳定触发落地水，水减速怪物）
        int safePillar = safePillarHeight();
        if (dist < closeDistance()) {
            if (pillarHeight < safePillar) {
                if (this.buildCooldown <= 0) {
                    if (this.buildUp(maid)) {
                        if (this.pillarBaseY < 0) {
                            this.pillarBaseY = currentY;
                        }
                        this.buildCooldown = buildCd;
                        // v1.5.194：搭方块中途的额外判定——威胁贴脸时顺手窒息封头
                        //（怪物还在近身范围成功率最高，纯被动无气泡）
                        this.trySuffocateDuringBuild(maid, threat);
                    } else if (this.bridgeStep(maid, threat)) {
                        this.buildCooldown = buildCd;
                    } else {
                        // v1.5.23 兜底：没有搭方块材料 → 播报 + 朝怪物最少的方向突围
                        this.announceNoMaterial(maid);
                        this.fleeEscaping(maid, threat);
                    }
                }
            }
            // 已垫到安全高度：站在高处等（不 flee，防掉落），威胁变化交给珍珠/强制搭高/传送
            return;
        }
        // 5. 有威胁但离得远（4~12 格）：增益药水（隐身/迅捷/再生等）+ 逃跑 + 前方阻挡垫台阶
        if (dist < threatDistance()) {
            // v1.5.25：通用增益药水（不再硬编码隐身/迅捷两种；再生/力量/抗火等都能喝）
            // v1.5.252g7：CD 按药水种类内部自管（= 药水时长）——CD 内同种不喝、
            // 其他种照喝；瞬间效果短 CD
            this.useBeneficialPotion(maid);
            if (this.buildCooldown <= 0 && this.bridgeStep(maid, threat)) {
                this.buildCooldown = buildCd;
            }
            this.flee(maid, threat);
            // 6. 威胁在 8 格外（相对安全）：顺带回血（贴身时不"一直吃"）
            if (dist > 8.0) {
                // v1.5.234：相对安全距离 → 完整回血阶梯（增益→治疗→金苹果→食物）
                this.tryHealLadder(maid);
            }
            return;
        }
        // 7. 卡住兜底：逃跑中长时间没位移 → 垫路铺台阶
        this.trackStuck(maid);
        if (this.stuckTicks >= stuckWindow()) {
            if (this.bridgeStep(maid, threat)) {
                this.stuckTicks = 0;
            }
        }
    }

    /** v1.5.23：材料不足播报（10 秒最多一次） */
    private void announceNoMaterial(EntityMaid maid) {
        if (this.announceCooldown-- > 0 || this.announcedNoMaterial) {
            return;
        }
        this.announcedNoMaterial = true;
        this.announceCooldown = announceCooldown();
        maid.getChatBubbleManager().addTextChatBubble(
                "背包里没有搭方块的材料（圆石/泥土/石头等），我先想办法突围！");
    }

    /** v1.5.232：自救资源全失败播报（每场一次）——岩浆/着火链路走到底仍无解时，
     *  明确告诉主人"我没招了"，然后交回自保机制（物理移动兜底/朝主人走）
     *  v1.5.247：文本修正——旧"包里什么都没有"误导（女仆包里明明有水桶/草方块
     *  也说"什么都没有"，实际含义是"本场自救手段（垫块/水桶/珍珠/抗火/金苹果）
     *  全部失败"），改为"自救手段都用尽了" */
    private void announceNoResource(EntityMaid maid) {
        if (this.announcedNoResource) {
            return;
        }
        this.announcedNoResource = true;
        maid.getChatBubbleManager().addTextChatBubble("自救手段都用尽了，我没招了！救救我！");
    }

    /**
     * v1.5.23 突围：被围殴且无法搭高时，扫描 8 个方向，朝怪物最少的
     * 可行走方向逃跑（比单纯反威胁方向更能脱出包围圈）。
     * v1.5.135：threat 允许为 null（着火兜底）——无威胁时只按怪物密度选方向。
     */
    private void fleeEscaping(EntityMaid maid, LivingEntity threat) {
        // v1.5.216：方向防抖——20 tick（1 秒）内保持同一逃跑目标。旧版每 tick
        // 重选方向：怪物分布稍变目标就跳，女仆来回折返 = "疯狂逃窜，逃得特别远"
        //（还叠加每 tick 重新寻路开销）；固定方向后直线跑出危险区，1 秒后再评估
        if (this.fleeDirCooldown > 0) {
            this.fleeDirCooldown--;
            maid.m_21573_().m_26519_(this.fleeTargetX,
                    maid.m_20183_().m_123342_(), this.fleeTargetZ, fleeSpeed());
            return;
        }
        int bestDx = 0;
        int bestDz = 0;
        double bestScore = -1.0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos probe = maid.m_20183_().m_7918_(dx * 5, 0, dz * 5);
                // v1.5.241：该方向路径上（2~5 格）有岩浆 → 直接排除该方向
                //（僵尸追击逃跑走进岩浆的根因：只按怪物密度选方向，不避岩浆）
                boolean lavaPath = false;
                for (int s = 2; s <= 5; s += 3) {
                    BlockPos p = maid.m_20183_().m_7918_(dx * s, 0, dz * s);
                    if (maid.m_9236_().m_46749_(p)
                            && isLavaBlock(maid.m_9236_().m_8055_(p).m_60734_())) {
                        lavaPath = true;
                        break;
                    }
                }
                if (lavaPath) {
                    continue;
                }
                // v1.5.195：威胁 = 敌对生物 + 对女仆/主人带仇恨的中立生物（isThreat）
                int monsters = maid.m_9236_().m_6443_(Mob.class,
                        new net.minecraft.world.phys.AABB(probe).m_82400_(4.0),
                        m -> m instanceof LivingEntity && com.maidsmart.dialogue.PerceptionManager.isThreat((LivingEntity) m, maid)).size();
                // 怪物越少分越高；有威胁时优先反威胁方向作为平局加分
                double score = 20.0 - monsters * 5.0;
                double dot = 0;
                if (threat != null) {
                    dot = (dx * (maid.m_20185_() - threat.m_20185_())
                            + dz * (maid.m_20189_() - threat.m_20189_())) / 8.0;
                }
                if (score > bestScore || (score == bestScore && dot > 0)) {
                    bestScore = score;
                    bestDx = dx;
                    bestDz = dz;
                }
            }
        }
        int tx = (int) maid.m_20185_() + bestDx * 8;
        int tz = (int) maid.m_20189_() + bestDz * 8;
        this.fleeDirCooldown = 20; // 1 秒内保持方向
        this.fleeTargetX = tx;
        this.fleeTargetZ = tz;
        // v1.1.0 实测十七：逃跑方向被【自己搭的战斗方块】挡住 → 直接拆掉它。
        // 实际战斗多变——翻墙台阶/封头块可能恰好堵死逃跑路线，女仆不能被自己
        // 搭的方块困死。先拆脚下面前 1~2 格的战斗方块再导航。
        this.breakBlockingCombatBlocks(maid, bestDx, bestDz);
        maid.m_21573_().m_26519_(tx, maid.m_20183_().m_123342_(), tz, fleeSpeed());
    }

    /**
     * v1.1.0 实测十七：拆掉逃跑方向上挡路的【自己搭的战斗方块】（身体+头部高度，
     * 前方 1~2 格）。只拆 COMBAT_PLACED 表里登记过的（自然地形/玩家建筑绝不碰）；
     * 拆除掉落走 reclaimToMaid 口径（开着进背包，否则落地）。返回 true = 拆了东西。
     */
    private boolean breakBlockingCombatBlocks(EntityMaid maid, int dx, int dz) {
        boolean broke = false;
        try {
            if (!(maid.m_9236_() instanceof ServerLevel sl)) {
                return false;
            }
            int y = maid.m_20183_().m_123342_();
            for (int s = 1; s <= 2; s++) {
                int bx = (int) Math.floor(maid.m_20185_()) + dx * s;
                int bz = (int) Math.floor(maid.m_20189_()) + dz * s;
                for (int dy = 0; dy <= 1; dy++) { // 脚部+头部
                    BlockPos p = new BlockPos(bx, y + dy, bz);
                    if (!isCombatPlaced(sl, p)) {
                        continue;
                    }
                    var state = sl.m_8055_(p);
                    if (state.m_60795_()) {
                        continue;
                    }
                    sl.m_46796_(2001, p, net.minecraft.world.level.block.Block.m_49956_(state));
                    if (com.maidsmart.config.MaidSmartConfig.BRIDGE_RECLAIM_TO_MAID.get()) {
                        java.util.List<net.minecraft.world.item.ItemStack> drops =
                                net.minecraft.world.level.block.Block.m_49869_(state, sl, p, null);
                        EntityMaid nearest = com.maidsmart.task.BridgeUpBehavior.findNearestMaidPublic(sl, p);
                        boolean handed = false;
                        if (nearest != null && !drops.isEmpty()) {
                            try {
                                net.minecraftforge.items.wrapper.CombinedInvWrapper inv = nearest.getAvailableInv(true);
                                for (net.minecraft.world.item.ItemStack stack : drops) {
                                    if (stack.m_41619_()) {
                                        continue;
                                    }
                                    net.minecraft.world.item.ItemStack remain =
                                            net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(inv, stack, false);
                                    if (!remain.m_41619_()) {
                                        net.minecraft.world.level.block.Block.m_49840_(sl, p, remain);
                                    }
                                }
                                handed = true;
                            } catch (Exception ignored) {
                            }
                        }
                        if (!handed && !drops.isEmpty()) {
                            for (net.minecraft.world.item.ItemStack stack : drops) {
                                net.minecraft.world.level.block.Block.m_49840_(sl, p, stack);
                            }
                        }
                    } else {
                        net.minecraft.world.level.block.Block.m_49892_(state, sl, p, sl.m_7702_(p));
                    }
                    sl.m_7731_(p, net.minecraft.world.level.block.Blocks.f_50016_.m_49966_(), 3);
                    // 从追踪表移除（已物理拆除）
                    java.util.Map<BlockPos, CombatPlacedMark> marks = COMBAT_PLACED.get(sl.m_46472_());
                    if (marks != null) {
                        marks.remove(p.m_7949_());
                    }
                    broke = true;
                }
            }
        } catch (Exception ignored) {
        }
        return broke;
    }

    /**
     * 逃跑路径预判（v1.5.17 保留给 bridgeStep 内部使用场景，此处不再直接调用）。
     * 前方 2 格非空（墙）即视为需要搭台阶翻越——已由 bridgeStep 的情况 A 覆盖。
     */

    @Override
    protected boolean m_6737_(ServerLevel level, EntityMaid maid, long gameTime) {
        // v1.5.202（轻量级自保，落地水格式）：常驻检查器永不停止——会话进出
        // 由 m_6725_ 内部维护（sessionActive + PRESERVE_TAG），非会话 tick 零干预
        return true;
    }

    /**
     * v1.5.164：退出自保（血恢复）时传送回主人身边。
     * v1.5.165：按用户确认——血机制解除自保后的【第一件事】就是定位主人并传送过去：
     * 只要不在主人 5 格内就传送（无论远近、无论主人身边是否有怪——血已恢复到 70%，
     * 传过去帮忙/护卫主人，不再自己慢慢走回去）；仅保留传送冷却防反复连传。
     */
    private void teleportHomeOnExit(EntityMaid maid) {
        try {
            LivingEntity owner = maid.m_269323_();
            if (owner == null) {
                return;
            }
            double dx = maid.m_20185_() - owner.m_20185_();
            double dz = maid.m_20189_() - owner.m_20189_();
            if (dx * dx + dz * dz < 25.0) {
                return; // 已在主人 5 格内（就在身边，不用传）
            }
            long now = maid.m_9236_().m_46467_();
            if (now - this.lastTeleportTime < Math.min(teleportCooldown(), 100)) {
                return; // 传送冷却（5 秒封顶，防反复传送）
            }
            maid.m_6034_(owner.m_20185_(), owner.m_20186_(), owner.m_20189_());
            maid.f_19789_ = 0.0f;
            this.lastTeleportTime = now;
            // v1.5.227：播报 60 秒限频——5 秒传送冷却会反复触发"回到身边"播报刷屏
            if (now - this.lastHomeAnnounceTick >= 1200) {
                this.lastHomeAnnounceTick = now;
                maid.getChatBubbleManager().addTextChatBubble("我回到主人身边了，先缓缓……");
            }
            LOGGER.info("self preserve exit teleport: maid={} -> owner dist={}",
                    maid.m_5446_() != null ? maid.m_5446_().getString() : maid.m_20148_(),
                    String.format("%.1f", Math.sqrt(dx * dx + dz * dz)));
        } catch (Exception ignored) {
        }
    }

    /** 吃背包里的治疗食物（真实进食：食物效果+声音+粒子） */
    private boolean eatHealingFood(EntityMaid maid) {
        IItemHandler inv = maid.getMaidInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.m_41619_()) {
                continue;
            }
            if (!this.isHealFood(stack.m_41720_())) {
                continue;
            }
            ItemStack taken = inv.extractItem(i, 1, false);
            if (taken.m_41619_()) {
                continue;
            }
            // 真实进食：应用食物属性（金苹果=吸收+再生，熟食=饱食）
            maid.m_5584_(maid.m_9236_(), taken);
            return true;
        }
        // v1.5.231b：金苹果/附魔金苹果【仅极端饥饿且无其他食物】才作为食物——
        // 珍贵资源常规不吃（已移出 HEAL_FOODS/FOODS 名单），走药水类判定；
        // 极端饥饿（爱憎分明 HungerData ≤9，反射读取；未装则 false）且背包
        // 没有任何治疗食物时才兜底吃
        if (isExtremeHungry(maid)) {
            return this.eatGoldenAppleForFire(maid);
        }
        return false;
    }

    /** v1.5.234：回血资源阶梯（无威胁/威胁 8 格外安全距离，前一档失败才下一档）——
     *  增益药水(再生持续回血省瓶) → 治疗药水(瞬间) → 金苹果/附魔金苹果(珍贵资源最后)
     *  → 食物(饱食)。每档缺物品由各档方法内部提示（治疗/金苹果/食物），
     *  与岩浆/着火"阶梯判定"同思想：前级有结果（成功/失败）才轮到下一档。 */
    private void tryHealLadder(EntityMaid maid) {
        if (this.hpRatio(maid) >= 0.5f) {
            return; // 血量够，不喝
        }
        // v1.5.252g7【CD 规则】：CD 按药水种类记（= 药水时长）——CD 内同种不喝、
        // 其他种照喝；瞬间治疗短 CD 可连喝。各喝药方法内部自管 CD。
        if (this.useBeneficialPotion(maid) < 0
                && !this.useHealingPotion(maid)
                && !this.useGoldenApple(maid)
                && this.healCooldown <= 0 && this.eatHealingFood(maid)) {
            this.healCooldown = healCooldown();
        }
    }

    /** v1.5.234：威胁中的即时回血阶梯（不打断搭方块/反击）——治疗药水 → 金苹果
     * （被围殴时只做即时动作，不吃食物/增益——回血慢的档留给安全距离） */
    private void tryInstantHeal(EntityMaid maid) {
        if (this.hpRatio(maid) < 0.5f) {
            // v1.5.252g7：CD 内部自管（治疗短 CD 可连喝、金苹果短 CD）
            if (!this.useHealingPotion(maid)) {
                this.useGoldenApple(maid);
            }
        }
    }

    /** v1.5.231b：极端饥饿判定——反射读爱憎分明（Love Loathe）HungerData.get
     *  （0-100，≤9 = 饥饿）；未装爱憎分明/异常返回 false（原版女仆无饥饿系统）。
     *  v1.5.284：双包名兼容——2.0.2 迁移 com.github.tartaricacid →
     *  com.github.JumDa5he（旧包名反射永远 ClassNotFoundException → 判定静默失效）；
     *  先试新包名、失败回退旧包名 */
    private static boolean isExtremeHungry(EntityMaid maid) {
        // v1.5.310：联动总开关 + 极端饥饿保命开关（配置面板「爱憎分明模组调试」页可调）
        if (!com.maidsmart.config.MaidSmartConfig.MISC_LOVELOATHE_MASTER.get()
                || !com.maidsmart.config.MaidSmartConfig.MISC_LOVELOATHE_EXTREME_HUNGER.get()) {
            return false;
        }
        try {
            Class<?> cls = null;
            try {
                cls = Class.forName("com.github.JumDa5he.callresponse.compat.hunger.HungerData");
            } catch (Exception ignored) {
                cls = Class.forName("com.github.tartaricacid.callresponse.compat.hunger.HungerData");
            }
            java.lang.reflect.Method get = cls.getMethod("get", EntityMaid.class);
            Object v = get.invoke(null, maid);
            return v instanceof Number n && n.floatValue() <= 9.0f;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isHealFood(Item item) {
        for (String id : HEAL_FOODS) {
            Item candidate = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(id));
            if (candidate != null && candidate == item) {
                return true;
            }
        }
        return false;
    }

    /** 找威胁：优先当前攻击目标，其次最近攻击者，最后最近的敌对怪物（12 格内且可见） */
    private LivingEntity findThreat(EntityMaid maid) {
        LivingEntity target = maid.m_5448_();
        if (target != null && target.m_6084_() && maid.m_20270_(target) < threatDistance()
                && this.hasSight(maid, target)) {
            return target;
        }
        // v1.5.135：最近攻击者（LivingHurtEvent 记录）——覆盖其他 mod 的
        // 非 Monster 类敌对生物 / 中立反咬 / PVP，5 秒窗口内视为威胁
        AttackerRecord rec = LAST_ATTACKERS.get(maid.m_20148_());
        if (rec != null) {
            long now = maid.m_9236_().m_46467_();
            if (now - rec.gameTick() < 100L && rec.dim().equals(maid.m_9236_().m_46472_())) {
                net.minecraft.world.entity.Entity e = ((ServerLevel) maid.m_9236_()).m_6815_(rec.entityId());
                if (e instanceof LivingEntity lv && lv.m_6084_()
                        && maid.m_20270_(lv) < threatDistance() && this.hasSight(maid, lv)) {
                    return lv;
                }
                if (e == null || !e.m_6084_()) {
                    LAST_ATTACKERS.remove(maid.m_20148_()); // 攻击者已死/消失 → 清记录
                }
            } else if (now - rec.gameTick() >= 100L) {
                LAST_ATTACKERS.remove(maid.m_20148_()); // 窗口过期 → 清记录
            }
        }
        // v1.5.195：威胁扫描从"敌对怪物"扩大为"所有对女仆/主人携带仇恨的生物"
        // （中立反咬/主人被攻击的生物也算，getTarget 判定是 O(1) 字段读，先过滤
        // 再做昂贵的 hasSight raycast，性能不受影响）；Monster 兜底保证不漏判
        List<Mob> mobs = maid.m_9236_().m_6443_(Mob.class, maid.m_20191_().m_82400_(threatDistance()),
                m -> m.m_6084_());
        LivingEntity nearest = null;
        double minDist = Double.MAX_VALUE;
        for (Mob mob : mobs) {
            if (!com.maidsmart.dialogue.PerceptionManager.isThreat(mob, maid)) {
                continue;
            }
            // v1.5.21：隔墙/被遮挡的怪不算威胁（避免隔墙恐慌）
            if (!this.hasSight(maid, mob)) {
                continue;
            }
            double dist = maid.m_20270_(mob);
            if (dist < minDist) {
                minDist = dist;
                nearest = mob;
            }
        }
        return nearest;
    }

    /** v1.5.211：贴脸（radius 格内）最近的威胁实体——着火分支反击兜底用。
     *  与 findThreat 不同：不要求视线（贴脸默认看得见），只认紧贴身周的
     *  敌对/仇恨生物（isThreat），找不到返回 null */
    private LivingEntity findNearestThreatNearby(EntityMaid maid, double radius) {
        try {
            net.minecraft.world.level.Level level = maid.m_9236_();
            LivingEntity best = null;
            double bestDist = Double.MAX_VALUE;
            for (net.minecraft.world.entity.LivingEntity e : level.m_6443_(
                    net.minecraft.world.entity.LivingEntity.class,
                    maid.m_20191_().m_82400_(radius),
                    m -> m != maid && m.m_6084_()
                            && com.maidsmart.dialogue.PerceptionManager.isThreat(m, maid))) {
                double d = maid.m_20270_(e);
                if (d < bestDist) {
                    bestDist = d;
                    best = e;
                }
            }
            return best;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * v1.5.91：以某个实体（女仆/主人）为中心、圆半径 radius 内是否【可见】的威胁。
     * 与 findThreat 的可见性判定一致（raycast 隔墙不算）——用于传送回主人的
     * 安全闸：主人身边无可见威胁才放行传送。
     * v1.5.195：威胁 = 敌对生物 + 对女仆/主人带仇恨的中立生物（isThreat）。
     */
    private static boolean anyVisibleMonsterAround(LivingEntity center, double radius) {
        List<Mob> mobs = center.m_9236_().m_6443_(Mob.class,
                center.m_20191_().m_82400_(radius), m -> m.m_6084_());
        for (Mob mob : mobs) {
            if (center instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid
                    ? !com.maidsmart.dialogue.PerceptionManager.isThreat(mob, maid)
                    : !(mob instanceof Monster)) {
                continue;
            }
            if (!SelfPreservationBehavior.hasSight(center, mob)) {
                continue; // 隔墙/被遮挡的不算（与威胁判定一致）
            }
            return true;
        }
        return false;
    }

    /** v1.5.21：视线检查（raycast，隔墙不算威胁）。v1.5.135 公开给战斗战术共用 */
    public static boolean hasSight(LivingEntity from, LivingEntity other) {
        try {
            net.minecraft.world.level.ClipContext ctx = new net.minecraft.world.level.ClipContext(
                    from.m_20182_().m_82520_(0.0, 1.2, 0.0),
                    other.m_20182_().m_82520_(0.0, 1.2, 0.0),
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, from);
            net.minecraft.world.phys.BlockHitResult hit = from.m_9236_().m_45547_(ctx);
            return hit.m_6662_() == net.minecraft.world.phys.HitResult.Type.MISS;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 朝威胁反方向逃跑（v1.5.21：蛇形走位躲远程 + 目标点避开危险方块）。
     * 直接调用 PathNavigation 寻路——自保期间 MoveToTargetSink（core 优先级 2）
     * 被压制不会执行 WALK_TARGET memory，只能自己走。
     */
    private void flee(EntityMaid maid, LivingEntity threat) {
        double dx = maid.m_20185_() - threat.m_20185_();
        double dz = maid.m_20189_() - threat.m_20189_();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) {
            return;
        }
        // v1.5.21 蛇形走位：每 10 tick 换一次侧偏方向，降低被远程命中率
        if (this.zigzagTick-- <= 0) {
            this.zigzagTick = 10;
            this.zigzagSide = this.zigzagSide == 1 ? -1 : 1;
        }
        double fx = dx / len * 8.0 + (-dz / len) * this.zigzagSide * 3.0;
        double fz = dz / len * 8.0 + (dx / len) * this.zigzagSide * 3.0;
        int tx = (int) (maid.m_20185_() + fx);
        int tz = (int) (maid.m_20189_() + fz);
        // v1.5.21：目标点有危险方块（岩浆/火/仙人掌等）→ 反向侧偏再试
        // v1.5.241：路径【中间】（每 2 格）也检查——只查目标点会被"半路踩岩浆"
        // 漏掉（僵尸追击逃跑走进岩浆的根因之一）
        int ty = maid.m_20183_().m_123342_();
        boolean pathDanger = false;
        for (int s = 1; s <= 3; s++) {
            BlockPos mid = new BlockPos((int) (maid.m_20185_() + fx / 4.0 * s), ty,
                    (int) (maid.m_20189_() + fz / 4.0 * s));
            if (this.hasDangerAt(maid.m_9236_(), mid)) {
                pathDanger = true;
                break;
            }
        }
        if (pathDanger || this.hasDangerAt(maid.m_9236_(), new BlockPos(tx, ty, tz))) {
            this.zigzagSide = -this.zigzagSide;
            fx = dx / len * 8.0 + (-dz / len) * this.zigzagSide * 3.0;
            fz = dz / len * 8.0 + (dx / len) * this.zigzagSide * 3.0;
            tx = (int) (maid.m_20185_() + fx);
            tz = (int) (maid.m_20189_() + fz);
        }
        maid.m_21573_().m_26519_(tx, ty, tz, fleeSpeed());
    }

    /** v1.5.21：某格（及脚下）是否危险方块
     *  v1.5.241：改用 getKey 注册名比较——不再依赖 ForgeRegistries.BLOCKS.getValue
     *  （疑似偶发返回 null 导致岩浆/火等危险方块不被识别，逃跑时径直走进岩浆） */
    private boolean hasDangerAt(net.minecraft.world.level.Level level, BlockPos pos) {
        BlockState state = level.m_8055_(pos);
        BlockState below = level.m_8055_(pos.m_7918_(0, -1, 0));
        return isDangerBlock(state.m_60734_()) || isDangerBlock(below.m_60734_());
    }

    /** v1.5.241：是否为危险方块（注册名比较，不查 getValue） */
    private static boolean isDangerBlock(net.minecraft.world.level.block.Block b) {
        net.minecraft.resources.ResourceLocation key = ForgeRegistries.BLOCKS.getKey(b);
        if (key == null) {
            return false;
        }
        String id = key.toString();
        for (String d : DANGER_BLOCKS) {
            if (id.equals(d)) {
                return true;
            }
        }
        return false;
    }

    /**
     * v1.5.140：搭方块边缘保护——照搬玩家潜行防掉落效果（挖矿 pillarGuard 同款）：
     * 脚下是实心支撑时，把实体中心钳制到支撑方块中心的 ±(0.5 - 碰撞箱半宽) 内。
     * 只钳位置、不改速度（移速不变），防止搭高/逃跑移速过快时冲出方块边缘飞出去。
     */
    private void edgeGuard(EntityMaid maid) {
        BlockPos feet = maid.m_20183_();
        BlockPos under = feet.m_7918_(0, -1, 0);
        BlockPos support = null;
        if (maid.m_9236_().m_8055_(under).m_60796_(maid.m_9236_(), under)) {
            support = under;
        } else {
            // v1.5.241：脚下悬空 → 查 4 个斜下对角支撑（从垫块边缘滑落瞬间仍被
            // 钳住——旧版脚下无支撑直接 return，"搭方块中途防跌落没生效"的根因：
            // 滑出边缘那一刻脚下已无支撑，edgeGuard 立即失效，人就掉下去了）
            for (int dx = -1; dx <= 1 && support == null; dx += 2) {
                for (int dz = -1; dz <= 1 && support == null; dz += 2) {
                    BlockPos diag = feet.m_7918_(dx, -1, dz);
                    if (maid.m_9236_().m_8055_(diag).m_60796_(maid.m_9236_(), diag)) {
                        support = diag;
                    }
                }
            }
            if (support == null) {
                return; // 完全无支撑（真悬空）→ 不管
            }
        }
        double halfW = maid.m_20205_() / 2.0;
        double limit = Math.max(0.05, 0.5 - halfW);
        double cx = support.m_123341_() + 0.5;
        double cz = support.m_123343_() + 0.5;
        double x = maid.m_20185_();
        double z = maid.m_20189_();
        double nx = Math.max(cx - limit, Math.min(cx + limit, x));
        double nz = Math.max(cz - limit, Math.min(cz + limit, z));
        if (nx != x || nz != z) {
            maid.m_6034_(nx, maid.m_20186_(), nz);
        }
    }

    /** v1.5.135：身体中心是否卡进实心满块（窒息判定，antiSuffocate / envDangerSoft 共用） */
    private boolean bodyInSolid(EntityMaid maid) {
        net.minecraft.world.phys.AABB box = maid.m_20191_();
        double cy = box.f_82289_ + (box.f_82292_ - box.f_82289_) * 0.5; // 身体中心高度（半身）
        BlockPos mid = new BlockPos((int) Math.floor(maid.m_20185_()),
                (int) Math.floor(cy), (int) Math.floor(maid.m_20189_()));
        BlockState st = maid.m_9236_().m_8055_(mid);
        return !st.m_60795_() && st.m_60796_(maid.m_9236_(), mid);
    }

    /** v1.5.199：头部中心是否卡进实心满块（真正窒息判定——envDangerSoft 用）。
     *  与 bodyInSolid 的区别：只卡下半身（被推挤进墙/半身在方块里）不算窒息，
     *  不会误触发整场自保；antiSuffocate 兜底仍用 bodyInSolid（挪位置无害） */
    private boolean headInSolid(EntityMaid maid) {
        net.minecraft.world.phys.AABB box = maid.m_20191_();
        double hy = box.f_82289_ + (box.f_82292_ - box.f_82289_) * 0.9; // 头部高度
        BlockPos head = new BlockPos((int) Math.floor(maid.m_20185_()),
                (int) Math.floor(hy), (int) Math.floor(maid.m_20189_()));
        BlockState st = maid.m_9236_().m_8055_(head);
        return !st.m_60795_() && st.m_60796_(maid.m_9236_(), head);
    }

    /** v1.5.199：背包是否有水桶（仅判定不消耗——与落地水一致）。
     *  v1.5.231b：也查【主手/副手】——主人把水桶"给"女仆时可能装备在手上
     *  （实测"给了水桶但不用水灭火"的一半原因：水桶在手不在背包）
     *  v1.5.240：改用 getKey 注册名比较——不再依赖 ForgeRegistries.ITEMS.getValue
     *  （疑似 getValue 偶发返回 null 导致"主手 water_bucket 却说没招了"）；
     *  getKey 从 Item 实例直接映射注册名，已注册物品必非 null，更可靠 */
    private boolean hasWaterBucket(EntityMaid maid) {
        if (isWaterBucketItem(maid.m_21205_())) {
            return true;
        }
        if (isWaterBucketItem(maid.m_21206_())) {
            return true;
        }
        IItemHandler inv = maid.getMaidInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            if (isWaterBucketItem(inv.getStackInSlot(i))) {
                return true;
            }
        }
        return false;
    }

    /** v1.5.240：是否为水桶（注册名比较，不查 getValue） */
    private static boolean isWaterBucketItem(net.minecraft.world.item.ItemStack stack) {
        if (stack.m_41619_()) {
            return false;
        }
        net.minecraft.resources.ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
        return key != null && "minecraft:water_bucket".equals(key.toString());
    }

    /** v1.5.199：临时放水（击退搭高缓冲 / 岩浆灭火 / 着火灭火）——自身格可替换
     *  则放自身格，否则放上方格；3 秒后由 tickRecoverWater 收回，水桶不消耗
     *  （与落地水一致）。
     *  v1.5.204：放水位置判定重写——1.20.1 液体块的 Properties 没有 replaceable
     *  标志，m_60815_()（1 参 canBeReplaced）对岩浆/水恒为 false；旧版只认
     *  m_60815_() → 没垫方块时站在岩浆里"自身格 + 上方格都是岩浆"判定不可放 →
     *  永远倒不出水（"包里有水桶却不自救"的根因）。现在空气 / 可替换 / 液体
     *  都允许倒水——直接用水替换脚下的岩浆。
     *  v1.5.230：位置判定加 fallback——自身 → 上方 → 上方 2 格（屋檐下等
     *  头顶被实心堵住的场景：上方 2 格放水，水流下来淋到身上灭火）。 */
    private boolean canPourWaterAt(net.minecraft.world.level.Level level, BlockPos pos) {
        BlockState st = level.m_8055_(pos);
        // v1.5.259：m_60815_（0 参）是 isSolid（有碰撞），不是 canBeReplaced！
        // 旧版 `st.m_60795_() || st.m_60815_()` 把实心方块当"可替换"（植物/火把
        // isSolid=false 反而判不可倒）→ 岩浆自救方向反了。非实心（空气/植物/
        // 火把位/岩浆/水）都可被水替换，直接 !isSolid 覆盖全部。
        return !st.m_60815_();
    }

    /** v1.5.249：放水成功日志节流（每女仆 10 秒一次，防每 tick 刷屏） */
    private static final java.util.Map<String, Long> WATER_LOG_CD =
            new java.util.concurrent.ConcurrentHashMap<>();

    private void placeClutchWater(EntityMaid maid) {
        try {
            // v1.5.231b【着火不用水桶修复】：旧水未收回时【先收旧再放新】——
            // 岩浆分支放的水在岩浆那边（waterPos != null），着火分支直接跳过 →
            // 火一直烧（"逃出岩浆后乱跑不灭火"的根因之一）。灭火需要水在
            // 自己脚下，旧水位置不对就立即收掉重放。
            if (this.waterPos != null) {
                this.recoverWaterNow(maid);
            }
            net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) maid.m_9236_();
            // v1.5.240：原版静态引用（不依赖 Forge 注册表 getValue）
            net.minecraft.world.level.block.Block water = net.minecraft.world.level.block.Blocks.f_49990_;
            BlockPos pos = maid.m_20183_();
            // v1.5.250【下界放水】：下界水会瞬间蒸发（灭火无效），但用户明确要求
            // 女仆"喜欢用水桶"——每 40 tick（2 秒）尝试倒一次水（放置动作/蒸汽
            // 粒子可见），【不设 waterPos】（蒸发后无需回收），调用侧流转珍珠/
            // 抗火/逃跑；冷却内直接 return，防止每 tick 傻倒水刷屏
            if (isNether(maid)) {
                long gt = level.m_46467_();
                if (gt - this.netherPourTick < 40) {
                    return;
                }
                this.netherPourTick = gt;
                for (int up : new int[]{1, 0, 2}) {
                    BlockPos cand = pos.m_7918_(0, up, 0);
                    if (this.canPourWaterAt(level, cand)) {
                        level.m_7731_(cand, water.m_49966_(), 3);
                        Long lastLog = WATER_LOG_CD.get(maid.m_20148_().toString());
                        if (lastLog == null || gt - lastLog > 200) {
                            WATER_LOG_CD.put(maid.m_20148_().toString(), gt);
                            LOGGER.info("placeClutchWater ok(nether): maid={} waterAt={}",
                                    maid.m_5446_() != null ? maid.m_5446_().getString() : "?", cand);
                        }
                        return;
                    }
                }
                LOGGER.info("placeClutchWater fail(nether): maid={} y={} up1={} self={} up2={}",
                        maid.m_5446_() != null ? maid.m_5446_().getString() : "?",
                        pos.m_123342_(),
                        this.canPourWaterAt(level, pos.m_7918_(0, 1, 0)),
                        this.canPourWaterAt(level, pos),
                        this.canPourWaterAt(level, pos.m_7918_(0, 2, 0)));
                return;
            }
            // v1.5.230：位置候选链——自身 → 上方 1 → 上方 2（屋檐下头顶被堵的
            // 场景上方 2 格放水，水流下来淋到身上）
            // v1.5.232：头顶优先（用户规范"在自己头顶位置生成一滩水"）——
            // 上方 1 → 自身 → 上方 2（屋檐下头顶被堵的场景上方 2 格放水兜底）
            for (int up : new int[]{1, 0, 2}) {
                BlockPos cand = pos.m_7918_(0, up, 0);
                if (this.canPourWaterAt(level, cand)) {
                    level.m_7731_(cand, water.m_49966_(), 3);
                    this.waterPos = cand;
                    this.waterPlacedTick = level.m_46467_();
                    // v1.5.249：放水成功诊断（节流 10 秒）——确认放水真的执行了
                    long gt2 = level.m_46467_();
                    Long lastLog = WATER_LOG_CD.get(maid.m_20148_().toString());
                    if (lastLog == null || gt2 - lastLog > 200) {
                        WATER_LOG_CD.put(maid.m_20148_().toString(), gt2);
                        LOGGER.info("placeClutchWater ok: maid={} waterAt={}",
                                maid.m_5446_() != null ? maid.m_5446_().getString() : "?",
                                cand);
                    }
                    return;
                }
            }
            // v1.5.246：放水失败诊断——候选格（上1/自身/上2）全不可放水时的
            // 具体状态，定位"有水桶却放不出水"的根因
            LOGGER.info("placeClutchWater fail: maid={} y={} up1={} self={} up2={}",
                    maid.m_5446_() != null ? maid.m_5446_().getString() : "?",
                    pos.m_123342_(),
                    this.canPourWaterAt(level, pos.m_7918_(0, 1, 0)),
                    this.canPourWaterAt(level, pos),
                    this.canPourWaterAt(level, pos.m_7918_(0, 2, 0)));
        } catch (Exception e) {
            // v1.5.246：放水异常诊断（不再静默吞）
            LOGGER.info("placeClutchWater error: {} {}",
                    e.getClass().getSimpleName(),
                    e.getMessage() != null ? e.getMessage() : "");
        }
    }

    /** v1.5.231b：立即收回当前的水（若该位置还是水）——placeClutchWater 重放前用 */
    private void recoverWaterNow(EntityMaid maid) {
        if (this.waterPos == null) {
            return;
        }
        try {
            net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) maid.m_9236_();
            // v1.5.240：原版静态引用（不依赖 Forge 注册表 getValue）
            net.minecraft.world.level.block.Block water = net.minecraft.world.level.block.Blocks.f_49990_;
            net.minecraft.world.level.block.Block air = net.minecraft.world.level.block.Blocks.f_50016_;
            BlockState st = level.m_8055_(this.waterPos);
            if (water != null && st.m_60734_() == water) {
                level.m_7731_(this.waterPos,
                        air != null ? air.m_49966_() : net.minecraft.world.level.block.Blocks.f_50016_.m_49966_(), 3);
            }
        } catch (Exception ignored) {
        }
        this.waterPos = null;
    }

    /** v1.5.199：每 tick 检查——临时放水满 3 秒（60 tick）收回（v1.5.204：1 秒 → 3 秒，
     *  用户反馈"放下的水应该 3 秒后自动消失"） */
    private void tickRecoverWater(EntityMaid maid) {
        if (this.waterPos == null) {
            return;
        }
        try {
            if (maid.m_9236_().m_46467_() - this.waterPlacedTick < 60) {
                return;
            }
            net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) maid.m_9236_();
            // v1.5.240：原版静态引用（不依赖 Forge 注册表 getValue）
            net.minecraft.world.level.block.Block water = net.minecraft.world.level.block.Blocks.f_49990_;
            net.minecraft.world.level.block.Block air = net.minecraft.world.level.block.Blocks.f_50016_;
            BlockState st = level.m_8055_(this.waterPos);
            if (water != null && st.m_60734_() == water) {
                level.m_7731_(this.waterPos,
                        air != null ? air.m_49966_() : net.minecraft.world.level.block.Blocks.f_50016_.m_49966_(), 3);
            }
            this.waterPos = null;
        } catch (Exception ignored) {
        }
    }

    /**
     * v1.5.25 防窒息兜底：每 tick 检测女仆"身体中心"（半身位置）所在格是否为实心
     * 满块——是则说明她卡进了方块（搭方块后位移滞后 / 放偏 / 被推挤），
     * 直接 setPos 把位置强制移到该方块顶面之上。头顶检查拦不住横向卡入，
     * 这是最后一道保险：宁可瞬移半步也不让她被自己搭的方块闷住。
     */
    private void antiSuffocate(EntityMaid maid) {
        if (!this.bodyInSolid(maid)) {
            return; // 身体中心在空气 / 非实心满块（楼梯台阶不算卡住）
        }
        net.minecraft.world.phys.AABB box = maid.m_20191_();
        double cy = box.f_82289_ + (box.f_82292_ - box.f_82289_) * 0.5;
        BlockPos mid = new BlockPos((int) Math.floor(maid.m_20185_()),
                (int) Math.floor(cy), (int) Math.floor(maid.m_20189_()));
        double top = mid.m_123342_() + 1.0;
        if (top > box.f_82289_ + 0.01) {
            maid.m_6034_(maid.m_20185_(), top + 0.02, maid.m_20189_());
        }
    }

    /* ============ v1.5.135：环境逃生（自然伤害 / 无来源伤害） ============ */

    /** v1.5.233：环境逃生链路全失败后的【无物品保命移动】——泡岩浆 → 游向最近
     *  岸边（findLavaEdge）；着火 → 朝主人正常速度走（可收魂符/救火，不逃跑）。
     *  v1.5.234：溺水/卡墙也纳入让位兜底（溺水继续上浮找空气、卡墙 antiSuffocate
     *  顶出）——"全失败恢复正常状态"后仍保留最低限度的保命移动。
     *  只改移动方向，不做任何物品判定（自救判断已交给其他链路）。 */
    private void giveUpMovement(EntityMaid maid) {
        if (maid.m_20077_() && !hasFireResist(maid)) {
            BlockPos edge = this.findLavaEdge(maid);
            if (edge != null) {
                maid.m_21573_().m_26519_(edge.m_123341_() + 0.5,
                        edge.m_123342_(), edge.m_123343_() + 0.5, 1.2f);
            }
            return;
        }
        if (maid.m_6060_() && !hasFireResist(maid)) {
            LivingEntity owner = maid.m_269323_();
            if (owner != null && owner.m_6084_()) {
                double odx = owner.m_20185_() - maid.m_20185_();
                double odz = owner.m_20189_() - maid.m_20189_();
                if (odx * odx + odz * odz > 4.0) { // 已贴身边就不用走，让主人收
                    maid.m_21573_().m_26519_(owner.m_20185_(), owner.m_20186_(), owner.m_20189_(), 1.0f);
                }
            }
            return;
        }
        if (maid.m_20069_() && maid.m_20146_() < 60) {
            // 溺水兜底：继续上浮 + 找空气/岸边（与溺水分支同动作，无物品）
            maid.m_20256_(maid.m_20184_().m_82520_(0.0, 0.28, 0.0));
            BlockPos air = this.findAirSpot(maid);
            if (air != null) {
                maid.m_21573_().m_26519_(air.m_123341_() + 0.5, air.m_123342_(), air.m_123343_() + 0.5, 1.1f);
            } else {
                BlockPos shore = this.findShore(maid);
                if (shore != null) {
                    maid.m_21573_().m_26519_(shore.m_123341_() + 0.5, shore.m_123342_(), shore.m_123343_() + 0.5, 1.1f);
                }
            }
            return;
        }
        if (this.headInSolid(maid)) {
            this.antiSuffocate(maid); // 卡墙兜底：强制顶出
        }
    }

    /** 环境逃生主流程（tick 调用；v1.5.202 起任何威胁状态下都先执行）。
     *  v1.5.234 环境逃生阶梯（前级判定有结果后才轮到后级）：
     *    300 岩浆 → 280 着火 → 270 溺水 → 260 卡墙 → 避让（移动层）
     *  岩浆/着火带资源链，全失败 → envGiveUpTick 让位恢复正常状态；
     *  溺水/卡墙每 tick 有移动结果（导航/antiSuffocate），不引入让位；
     *  避让只绕行，排在所有"正在掉血"的逃生之后。 */
    private void environmentalEscape(EntityMaid maid) {
        // 1. 岩浆：触发=进入岩浆，解除=脱离岩浆（v1.5.232 重写链路）
        //    逃生链：安静走出(1.5s) → 垫方块脱离液面 → 水桶 → 末影珍珠 →
        //          抗火药水 → 金苹果/附魔金苹果 → 物理岸边兜底。
        //    身上已有抗火 → 整条链路直接否定、恢复正常（哪怕走了一半）。
        //    职责分离：本链路只管"脱离岩浆"；身上的火交给下方着火分支处理。
        //（v1.5.137：改用 m_20077_ = isInLava——旧 m_20070_ 是 isInWaterOrLava，
        //  站水里也会误进岩浆逃生）
        if (maid.m_20077_()) {
            // v1.5.232：抗火随时断开——有抗火(≥30s)时岩浆无害，链路否定恢复正常
            if (hasFireResist(maid)) {
                return;
            }
            BlockPos safe = this.findSafeSpot(maid);
            // v1.5.204：能直接走出来（旁边就有实心安全地面）→ 安静走出去——
            // 不播报"掉进岩浆里了！我垫上来！"也不垫方块。旧版只要脚碰到岩浆
            // 流体（m_20077_ 对浅接触也为 true）就播报+垫高，明明走出去更优，
            // 却没方块时又垫不了（"被烫了只会喊垫上来"的笑话）
            // v1.5.209：加时限 + 低血门槛——"安静走出"只对浅接触有效；连续 1.5 秒
            // （30 tick，v1.5.216：原 3 秒实测太长）还没走出岩浆，说明安全格在对岸/
            // 被隔断，裸奔风险太大；血量已很低（<40%）时也不赌步行，直接转下一步
            if (safe != null && this.walkOutTimer <= 30 && this.hpRatio(maid) >= 0.4f) {
                maid.m_21573_().m_26519_(safe.m_123341_() + 0.5,
                        safe.m_123342_(), safe.m_123343_() + 0.5, 1.2f);
                return;
            }
            // 深陷（找不到安全地面）/ 超时 / 低血 → 垫方块脱离液面
            // v1.5.199：岩浆提示（每场一次；自动走系统消息 TTS 朗读）
            if (!this.announcedEnv) {
                this.announcedEnv = true;
                maid.getChatBubbleManager().addTextChatBubble("掉进岩浆里了！我垫上来！");
                // v1.5.231b：逃生资源诊断（一次/场）——下次测试直接看出哪项缺失
                try {
                    boolean hasPearl = false;
                    boolean hasFirePot = false;
                    int buildBlocks = 0;
                    net.minecraftforge.items.IItemHandler inv = maid.getMaidInv();
                    for (int i = 0; i < inv.getSlots(); i++) {
                        ItemStack s = inv.getStackInSlot(i);
                        if (s.m_41619_()) {
                            continue;
                        }
                        if (s.m_41720_() == ForgeRegistries.ITEMS.getValue(
                                net.minecraft.resources.ResourceLocation.parse("minecraft:ender_pearl"))) {
                            hasPearl = true;
                        }
                        if (isFireResistPotion(s)) {
                            hasFirePot = true;
                        }
                        if (com.maidsmart.tool.MaidBuildBlockFilter.isUsableBuildBlock(
                                net.minecraft.world.level.block.Block.m_49814_(s.m_41720_()),
                                maid.m_9236_(), maid.m_20183_())) { // v1.1.0 实测七：统一过滤
                            buildBlocks += s.m_41613_();
                        }
                    }
                    LOGGER.info("lava escape resources: maid={} dim={} bucket={} blocks={} fireResistPot={} pearl={} hp={}%",
                            maid.m_5446_() != null ? maid.m_5446_().getString() : "?",
                            maid.m_9236_().m_46472_().m_135782_().m_135815_(),
                            this.hasWaterBucket(maid), buildBlocks, hasFirePot, hasPearl,
                            String.format("%.0f", this.hpRatio(maid) * 100.0f));
                } catch (Exception ignored) {
                }
            }
            // v1.5.232：垫方块在第二步（用户规范）——垫成功 = 脱离液面 = 本链路
            // 成功，下一 tick 自然进入着火分支处理身上的火；还在岩浆才轮到水桶等
            // 资源手段（buildCooldown 由 m_6725_ 顶部统一递减，这里只置冷却）
            if (this.buildCooldown <= 0) {
                if (this.lavaStepUp(maid)) {
                    this.buildCooldown = 10;
                }
            }
            if (!maid.m_20077_()) {
                return; // 已脱离液面——宣布脱离岩浆，成功，交给着火分支灭火
            }
            // 仍在岩浆：水桶 → 末影珍珠 → 抗火药水 → 金苹果/附魔金苹果。
            // v1.5.232：统一顺序不分维度（地狱放水瞬间蒸发由 placeWaterOnLava
            // 内部跳过，自然流转到珍珠/抗火）。抗火判定带"将过期"检查（剩余
            // <30 秒视为没有，提前续杯）
            if (!this.placeWaterOnLava(maid)) {
                if (!this.pearlEscapeFromLava(maid) && !hasFireResist(maid)) {
                    if (!this.drinkFireResistPotion(maid)) {
                        this.throwFireResistPotion(maid);
                    }
                }
                if (!hasFireResist(maid)) {
                    this.eatGoldenAppleForFire(maid);
                }
            }
            // v1.5.232：链路全失败（任一资源手段都没成功）→ 播报"我没招了"，
            // 交回自保机制（下面 findLavaEdge 物理移动兜底继续游向岸边，不站桩）
            // v1.5.233：记录让位时间戳——5 秒内恢复正常状态、判断丢给其他链路
            //（自保战斗/传送/战术），5 秒后仍危险再试环境逃生
            if (!this.resourceUsed) {
                this.announceNoResource(maid);
                this.envGiveUpTick = maid.m_9236_().m_46467_();
            }
            // v1.5.231【女仆烧死修复】：资源手段全失败后的【物理逃离兜底】——
            // 背包空（没水桶/方块/药水/珍珠）且附近没水时，前面所有手段都失败，
            // 旧版直接 return = 站在岩浆里等死（实测新召唤空背包女仆掉岩浆 4 秒
            // 烧死）。这里持续导航到最近的非岩浆岸边（半径 12），哪怕游得慢，
            // 也绝不会原地站着烧死。
            BlockPos edge = this.findLavaEdge(maid);
            if (edge != null) {
                maid.m_21573_().m_26519_(edge.m_123341_() + 0.5,
                        edge.m_123342_(), edge.m_123343_() + 0.5, 1.2f);
            }
            return;
        }
        // 2. 着火：灭火器 > 水桶（头顶放水）> 跑向水坑 > 末影珍珠 > 抗火药水
        //  > 金苹果/附魔金苹果 → 全失败则本链路失败，交回自保机制（贴脸反击/
        //  朝主人走，不逃跑）。
        //（v1.5.208：旧顺序"灭火器 > 找自然水 > 水桶"——附近有河就先傻跑向河、
        //  身上水桶反而不用的根因；水桶水是便宜资源，原地倒水立即灭火最优先；
        //  v1.5.210：抗火/找水互换——自然水 0 成本先试，抗火药水是消耗品，
        //  留给无水解救（岩浆深陷）的场合；地狱放水瞬间蒸发 → 跳过水桶/自然水，
        //  直接抗火或逃跑；
        //  v1.5.232：链路重写——有抗火随时否定恢复正常；灭火器→水桶→水坑→
        //  抗火→金苹果；全失败交回自保；
        //  v1.5.243：找水后补末影珍珠逃生——灭火/找水失败时瞬移脱离火源区域
        //  （"着火后跳入水坑和用末影珍珠都没有了"：珍珠此前只在岩浆链路））
        if (maid.m_6060_()) {
            // v1.5.232：抗火随时断开——火不伤人，等自然灭即可（m_6060_ 仍 true）
            if (hasFireResist(maid)) {
                return;
            }
            if (this.extinguishSelf(maid)) {
                return;
            }
            // 水桶优先（非地狱）：头顶放水灭火，不用跑去找河
            // v1.5.250：水桶步不再按维度跳过——下界也尝试倒水（v1.5.210 旧设计
            // "地狱放水瞬间蒸发 → 跳过"让下界女仆明明有水桶却全程不用，用户实测
            // 不满；现在下界每 2 秒倒一次（动作可见），蒸发后流转珍珠/抗火）
            boolean fireBucketStep = com.maidsmart.config.MaidSmartConfig.COMBAT_WATER_BUCKET_LAVA.get()
                    && this.hasWaterBucket(maid);
            if (fireBucketStep) {
                this.placeClutchWater(maid);
                if (this.waterPos != null) {
                    this.resourceUsed = true; // v1.5.232：倒水成功 = 自救资源
                    return; // 倒水成功——水淋在头顶/身上，灭火
                }
                // v1.5.246：进了水桶步但放水失败——placeClutchWater 已打 fail 诊断
                // v1.5.249：调用侧诊断——placeClutchWater 内部成功时会设置 waterPos，
                // 这里仍为 null 说明内部静默失败（fail/error 日志应已打）；若内部
                // 成功但这里读不到，则是字段/实例问题
                LOGGER.info("fire bucket step: placeClutchWater returned, waterPos={}",
                        this.waterPos);
            } else if (!isNether(maid)) {
                // v1.5.246：没进水桶步的诊断——配置/水桶判定哪个没满足
                LOGGER.info("fire bucket step skipped: cfg={} hasBucket={}",
                        com.maidsmart.config.MaidSmartConfig.COMBAT_WATER_BUCKET_LAVA.get(),
                        this.hasWaterBucket(maid));
            }
            // v1.5.210：找自然水（半径 12，0 成本立即灭火）→ 导航过去跳进水里
            if (!isNether(maid)) {
                BlockPos water = this.findWater(maid);
                if (water != null) {
                    this.resourceUsed = true; // v1.5.232：找到水源 = 自救资源
                    double wx = water.m_123341_() + 0.5;
                    double wz = water.m_123343_() + 0.5;
                    double dx = wx - maid.m_20185_();
                    double dz = wz - maid.m_20189_();
                    double distSq = dx * dx + dz * dz;
                    // v1.5.288：女仆是陆地生物，寻路器默认避水——导航到水面格只会
                    // 走到水边就停（用户："着火了却在水边下不去，难道女仆对水有恐惧？
                    // "——不是恐惧，是寻路不下水）。
                    // v1.5.290：近水直接【传送入水】——旧版(288)给水平+垂直速度"冲进去"
                    // 在岸沿高一格/悬空边缘时反复小跳悬空进不了水（用户："一直悬空在
                    // 半空中，没有办法跳下去"）。传送到水源格内部直接泡水灭火
                    if (distSq < 6.25) {
                        maid.m_7678_(wx, water.m_123342_(), wz,
                                maid.m_146908_(), maid.m_146909_());
                        maid.f_19789_ = 0.0f; // 清摔落距离
                    } else {
                        maid.m_21573_().m_26519_(wx, water.m_123342_(), wz, 1.15f);
                    }
                    return;
                }
            }
            // v1.5.243：末影珍珠逃生——灭火/找水失败后瞬移脱离火源区域
            //（pearlEscapeFromLava 找半径 12 安全落点，落点排除岩浆/火等危险方块；
            //  成功设 resourceUsed，不触发"我没招了"）
            if (this.pearlEscapeFromLava(maid)) {
                return;
            }
            // v1.5.208：抗火药水（饮用直接喝、喷溅/滞留扔自己脚下）——8 分钟免疫
            // v1.5.209：已有抗火效果（岩浆分支喝过/刚扔过）→ 跳过，不重复浪费
            if (!hasFireResist(maid)) {
                if (this.drinkFireResistPotion(maid)) {
                    return;
                }
                if (this.throwFireResistPotion(maid)) {
                    return;
                }
            }
            // v1.5.232：金苹果/附魔金苹果兜底（珍贵资源，仅自保模式吃）——附魔
            // 金苹果自带 5 分钟抗火（+吸收/再生/抗性），是着火最后的免疫手段；
            // 普通金苹果（吸收+再生）撑血等火自然灭
            if (!hasFireResist(maid)) {
                if (this.eatGoldenAppleForFire(maid)) {
                    return;
                }
            }
            // v1.5.232：灭火链路全失败 → 播报"我没招了"，交回自保机制（下面
            // 贴脸反击/朝主人走——恢复成正常状态，其他链路取而代之）
            // v1.5.233：记录让位时间戳（同岩浆链路，5 秒窗口）
            // v1.5.245：高血量(≥60%)着火时灭火失败【不喊救命也不设让位窗口】——
            // 火约 15 秒自然灭顶多掉 15 血，100% 血不会死；安静朝主人走/反击即可，
            // 不惊动主人（日志实证 hp=95%/75% 掉岩浆爬出后 0.1 秒喊"我没招了"）
            if (!this.resourceUsed && this.hpRatio(maid) < 0.6f) {
                this.announceNoResource(maid);
                this.envGiveUpTick = maid.m_9236_().m_46467_();
                // v1.5.240：灭火链路全失败诊断（每场一次，对齐 lava escape resources）
                // ——直接看出 hasWaterBucket 修复后是否命中、getValue 是否异常
                try {
                    String hand = "?";
                    net.minecraft.resources.ResourceLocation hk = ForgeRegistries.ITEMS
                            .getKey(maid.m_21205_().m_41720_());
                    if (hk != null) {
                        hand = hk.toString();
                    }
                    net.minecraft.world.item.Item wb = ForgeRegistries.ITEMS.getValue(
                            net.minecraft.resources.ResourceLocation.parse("minecraft:water_bucket"));
                    LOGGER.info("fire escape resources: maid={} hand={} bucket(getKey)={} getValueNotNull={} fireResist={}",
                            maid.m_5446_() != null ? maid.m_5446_().getString() : "?",
                            hand, this.hasWaterBucket(maid), wb != null, hasFireResist(maid));
                } catch (Exception ignored) {
                }
            }
            // v1.5.211：灭火手段全失败后的贴脸反击兜底——延续"边打边保命"：
            // 纯逃跑永远脱不了困，火自然灭（约 15 秒）前被怪追着打不还手，
            // 低血可能被打死。怪贴脸（<2.5 格）且反击冷却好 → 顺手还手
            // （御币弹幕/弓弩点射/近战挥砍），反击后下 tick 继续跑（12 tick 冷却
            // 就是"打一下→跑一步"的节奏）
            LivingEntity nearThreat = this.findNearestThreatNearby(maid, 2.5);
            if (nearThreat != null && this.meleeCooldown <= 0
                    && this.counterAttack(maid, nearThreat)) {
                this.meleeCooldown = 12;
                return;
            }
            // v1.5.231c【着火不要乱跑】：兜底从"朝怪物最少方向逃窜"改为
            // 【朝主人方向正常速度走】——①主人可以把着火的女仆收进魂符，
            // 逃窜太快根本收不到；②正常速度（1.0，不用 flee 高速），不影响主人
            // 操作；③无主人/主人不在身边 → 原地站着等火自然灭（15 秒）也比
            // 乱跑强（跑进水里/岩浆/怪堆更糟）
            LivingEntity owner = maid.m_269323_();
            if (owner != null && owner.m_6084_()) {
                double odx = owner.m_20185_() - maid.m_20185_();
                double odz = owner.m_20189_() - maid.m_20189_();
                if (odx * odx + odz * odz > 4.0) { // 已贴身边就不用走，让主人收
                    maid.m_21573_().m_26519_(owner.m_20185_(), owner.m_20186_(), owner.m_20189_(), 1.0f);
                }
            }
            return;
        }
        // 3. 溺水：朝上方空气格游（导航上浮为主，速度辅助为辅——moveControl
        //    tick 会覆盖 velocity 的 y，但导航目标在上方时会持续向上游）
        //（v1.5.137：isInWater = m_20069_，旧 m_20077_ 实为 isInLava）
        // v1.5.234（环境逃生阶梯）：300 岩浆 > 280 着火 > 270 溺水 > 260 卡墙 >
        // 避让（仅移动层，最不紧急）——溺水掉血比卡墙急，排前；避让只是预防性
        // 绕行，放在所有"正在掉血"的逃生之后（前级有结果才轮到后级）
        if (maid.m_20069_() && maid.m_20146_() < 60) {
            // v1.5.199：溺水提示（每场一次）
            if (!this.announcedEnv) {
                this.announcedEnv = true;
                maid.getChatBubbleManager().addTextChatBubble("溺水了！我游上去！");
            }
            // v1.5.252g7：快窒息 → 尝试喝水肺药水（饮用型优先，喷溅/滞留扔云；
            // CD 按药水种类内部自管 = 药水时长——水肺期间同种不再喝）
            if (this.drinkPotionWithEffect(maid, "minecraft:water_breathing") < 0) {
                this.splashPotionWithEffect(maid, "minecraft:water_breathing");
            }
            maid.m_20256_(maid.m_20184_().m_82520_(0.0, 0.28, 0.0)); // 上浮辅助
            BlockPos air = this.findAirSpot(maid); // 水面/附近空气格（半径 4 含上方）
            if (air != null) {
                maid.m_21573_().m_26519_(air.m_123341_() + 0.5, air.m_123342_(), air.m_123343_() + 0.5, 1.1f);
                return;
            }
            BlockPos shore = this.findShore(maid);
            if (shore != null) {
                maid.m_21573_().m_26519_(shore.m_123341_() + 0.5, shore.m_123342_(), shore.m_123343_() + 0.5, 1.1f);
            }
            return;
        }
        // 4. 卡墙窒息：最近空气格；找不到靠 antiSuffocate 强制上移兜底
        // v1.5.216：岩浆上下文保护——30 tick 内泡过岩浆（垫高脱离瞬间位移滞后
        // headInSolid 误报，"被岩浆烫到却说被卡住了"的根因）→ 静默兜底不播报，
        // 交给 antiSuffocate 顶出即可；真卡墙（无岩浆上下文）才播报提示
        // v1.5.236：窗口 30 → 100 tick（5 秒）——垫块顶出后坑沿压头/岩浆坑场景
        // 实测仍偶发 30 tick 后误报"卡住"（掉进岩浆里还说自己卡住了，语义矛盾）
        long nowTick = maid.m_9236_().m_46467_();
        boolean lavaContext = nowTick - this.lastInLavaTick <= 100;
        if (!lavaContext && !this.announcedEnv) {
            this.announcedEnv = true;
            maid.getChatBubbleManager().addTextChatBubble("我卡住了，快喘不过气了！");
        }
        BlockPos air = this.findAirSpot(maid);
        if (air != null) {
            maid.m_21573_().m_26519_(air.m_123341_() + 0.5, air.m_123342_(), air.m_123343_() + 0.5, 1.0f);
        } else {
            this.antiSuffocate(maid);
        }
        // 5. 岩浆避让（v1.5.204，bug 3）：没陷进去但附近有岩浆（含流动）→ 提前
        //    绕开（"面前一格岩浆也有逃跑的迹象"）。v1.5.234 移到最后——避让只是
        //    预防性绕行不直接掉血，所有"正在掉血"的逃生（岩浆/着火/溺水/卡墙）
        //    判定有结果后才轮到它。
        //    v1.5.229：有抗火时跳过避让——岩浆无害，绕行是浪费（地狱常驻岩浆，
        //    有抗火就该正常行走而不是永远绕路）
        if (this.cachedNearLava != null && !hasFireResist(maid)) {
            // v1.5.204：避让提示（每场一次；自动走系统消息 TTS 朗读）
            if (!this.announcedEnv) {
                this.announcedEnv = true;
                maid.getChatBubbleManager().addTextChatBubble("附近有岩浆，我绕开走！");
            }
            if (this.cachedFleeSpot != null) {
                maid.m_21573_().m_26519_(this.cachedFleeSpot.m_123341_() + 0.5,
                        this.cachedFleeSpot.m_123342_(), this.cachedFleeSpot.m_123343_() + 0.5, 1.2f);
            }
            return;
        }
    }

    /** v1.5.208：当前维度是否地狱——地狱放水瞬间蒸发，水桶/自然水判定全部跳过
     *  （防做无用功：岩浆里放水、着火找河都是白费）。
     *  v1.5.250【重大修复】：f_46428_ 是 OVERWORLD（"overworld"），NETHER 是
     *  f_46429_（"the_nether"）——旧代码拿 f_46428_ 当 NETHER 比，主世界女仆被
     *  误判成"地狱"→ 水桶步/找水坑永远跳过 → "包里明明有水桶却不用水"（新存档
     *  不喜欢放水、着火不跳水坑的根因）。改回 f_46429_。 */
    private static boolean isNether(EntityMaid maid) {
        return maid.m_9236_().m_46472_() == net.minecraft.world.level.Level.f_46429_;
    }

    /** v1.5.209：岩浆逃生——水桶放在【最近的岩浆格上方一格】，水流下渗冷却
     *  脚下岩浆（源变黑曜石/玄武岩），深陷时比垫自己脚下更直接。3 秒后由
     *  tickRecoverWater 收回，水桶不消耗（与落地水一致）。地狱跳过（瞬间蒸发）。
     *  返回 false = 没放成（没桶/已有一摊水/地狱/找不到岩浆） */
    private boolean placeWaterOnLava(EntityMaid maid) {
        try {
            if (this.waterPos != null
                    || !com.maidsmart.config.MaidSmartConfig.COMBAT_WATER_BUCKET_LAVA.get()
                    || !this.hasWaterBucket(maid)) {
                return false;
            }
            BlockPos lava = this.findNearbyLava(maid);
            if (lava == null) {
                return false;
            }
            net.minecraft.server.level.ServerLevel level =
                    (net.minecraft.server.level.ServerLevel) maid.m_9236_();
            // v1.5.250：下界放水节流（v1.5.210 旧设计下界直接跳过——下界水蒸发
            // 灭火无效，但用户要求女仆尝试用水桶；每 40 tick 倒一次，其余流转珍珠/
            // 抗火）
            if (isNether(maid)) {
                long gt = level.m_46467_();
                if (gt - this.netherPourTick < 40) {
                    return false;
                }
                this.netherPourTick = gt;
            }
            BlockPos target = lava.m_7918_(0, 1, 0); // 最近岩浆格上方一格
            // v1.5.230：上方放不了（岩浆在封闭空间/脚下隔了垫块）→ 直接替换
            // 岩浆自身格（液体可被水替换）——水流替换岩浆源 = 冷却为黑曜石，更直接
            if (!this.canPourWaterAt(level, target)) {
                target = lava;
            }
            if (!this.canPourWaterAt(level, target)) {
                return false;
            }
            // v1.5.240：原版静态引用（不依赖 Forge 注册表 getValue）
            net.minecraft.world.level.block.Block water = net.minecraft.world.level.block.Blocks.f_49990_;
            level.m_7731_(target, water.m_49966_(), 3);
            this.waterPos = target;
            this.waterPlacedTick = level.m_46467_();
            maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
            this.resourceUsed = true; // v1.5.232：用过自救资源，链路不算全失败
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** v1.5.209：岩浆逃生——末影珍珠：半径 12 内找安全落点（自身非岩浆、脚下实心
     *  可站、站立位 + 头顶 2 格空气防活埋、无危险方块），瞬移过去脱离岩浆。
     *  没有威胁目标，落点用安全格扫描而非"反威胁方向"。 */
    private boolean pearlEscapeFromLava(EntityMaid maid) {
        if (this.pearlCooldown > 0) {
            return false;
        }
        IItemHandler inv = maid.getMaidInv();
        net.minecraft.world.item.Item pearlItem = ForgeRegistries.ITEMS
                .getValue(ResourceLocation.parse("minecraft:ender_pearl"));
        int slot = -1;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.m_41619_() && pearlItem != null && s.m_41720_() == pearlItem) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            return false; // 没有珍珠
        }
        BlockPos land = this.findBlockBy(maid, 12, (level, p) -> {
            if (isLavaBlock(level.m_8055_(p).m_60734_())) {
                return false; // 落点不能是岩浆
            }
            BlockPos below = p.m_7918_(0, -1, 0);
            BlockState belowSt = level.m_8055_(below);
            if (isLavaBlock(belowSt.m_60734_()) || !belowSt.m_60796_(level, below)) {
                return false; // 脚下岩浆/不实心
            }
            if (!level.m_8055_(p).m_60795_()
                    || !level.m_8055_(p.m_7918_(0, 1, 0)).m_60795_()
                    || !level.m_8055_(p.m_7918_(0, 2, 0)).m_60795_()) {
                return false; // 站立位/头顶被堵，瞬移过去会活埋
            }
            return !this.hasDangerAt(level, p);
        });
        if (land == null) {
            return false; // 附近没有安全落点
        }
        net.minecraft.server.level.ServerLevel level =
                (net.minecraft.server.level.ServerLevel) maid.m_9236_();
        inv.extractItem(slot, 1, false);
        // v1.5.237：投掷原版末影珍珠（紫色轨迹 + 落地传送特效），不再直接瞬移
        if (!this.throwPearlTo(maid, level,
                land.m_123341_() + 0.5, land.m_123342_() + 0.5, land.m_123343_() + 0.5)) {
            // 投掷失败（异常兜底）→ 直接瞬移，保证逃生不失效
            maid.m_6034_(land.m_123341_() + 0.5, land.m_123342_() + 0.0, land.m_123343_() + 0.5);
            maid.f_19789_ = 0.0f; // 珍珠瞬移落地不继承摔落距离
        }
        this.resourceUsed = true; // v1.5.232：珍珠逃生成功 = 自救资源
        this.pearlCooldown = pearlCooldown();
        return true;
    }

    /** v1.5.237：投掷原版末影珍珠（原版特效）——生成 ThrownEnderpearl 实体瞄准落点
     *  抛物线飞出（紫色投掷轨迹），命中地面后由原版逻辑把女仆传送过去（落地紫色
     *  爆炸粒子 + 音效；非玩家投掷者只 teleportTo + resetFallDistance，不受伤害）。
     *  不再直接 setPos 瞬移（旧版只有末影人音效、特效不明显）。
     *  返回是否成功生成投掷物（失败由调用方兜底直接瞬移，保证逃生不失效）。 */
    private boolean throwPearlTo(EntityMaid maid, net.minecraft.server.level.ServerLevel level,
                                 double tx, double ty, double tz) {
        try {
            net.minecraft.world.entity.projectile.ThrownEnderpearl pearl =
                    new net.minecraft.world.entity.projectile.ThrownEnderpearl(level, maid);
            // 从女仆眼睛高度投出（朝向无关，珍珠按速度方向飞行）
            pearl.m_6034_(maid.m_20185_(), maid.m_20188_() + 1.0, maid.m_20189_());
            double dx = tx - maid.m_20185_();
            double dy = ty - (maid.m_20188_() + 1.0);
            double dz = tz - maid.m_20189_();
            double hDist = Math.sqrt(dx * dx + dz * dz);
            double vh = 1.8; // 水平速度（每 tick 格，≈原版珍珠，略快减少逃生等待）
            double t = Math.max(hDist / vh, 0.4); // 飞行 tick 数
            double g = 0.03; // 珍珠重力（每 tick²，原版 ThrowableItemProjectile）
            double vy = (dy + 0.5 * g * t * t) / t; // 垂直初速：命中目标格所需的抛物线
            double vx = hDist < 0.01 ? 0.0 : dx / hDist * vh;
            double vz = hDist < 0.01 ? 0.0 : dz / hDist * vh;
            pearl.m_20256_(new net.minecraft.world.phys.Vec3(vx, vy, vz));
            level.m_7967_(pearl);
            maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** v1.5.209：身上是否已有抗火效果（喝/扔抗火前检查——已有 8 分钟免疫时
     *  再喝/扔就是浪费一瓶药水）。
     *  v1.5.229：剩余 <30 秒（600 tick）视为【没有】——泡岩浆时效果 30 秒内
     *  就会过期，等过期了才判定会多掉 30 秒的血；提前续杯无空档。 */
    private static boolean hasFireResist(EntityMaid maid) {
        for (net.minecraft.world.effect.MobEffectInstance eff : maid.m_21220_()) {
            net.minecraft.resources.ResourceLocation key = ForgeRegistries.MOB_EFFECTS
                    .getKey(eff.m_19544_());
            if (key != null && "minecraft:fire_resistance".equals(key.toString())) {
                if (eff.m_19557_() >= 600) { // getDuration：剩余 ≥30 秒才算有效
                    return true;
                }
            }
        }
        return false;
    }

    /** v1.5.208：喝饮用型抗火药水（自己喝掉、返还空瓶）——掉岩浆/着火的免疫手段，
     *  8 分钟抗火。背包无饮用型抗火返回 false */
    private boolean drinkFireResistPotion(EntityMaid maid) {
        try {
            IItemHandler inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (stack.m_41619_() || !(stack.m_41720_() instanceof net.minecraft.world.item.PotionItem)) {
                    continue;
                }
                if (!isFireResistPotion(stack)) {
                    continue;
                }
                inv.extractItem(i, 1, false);
                this.giveBottle(maid); // 喝药留空玻璃瓶（与玩家一致）
                applyEffectTo(maid, "minecraft:fire_resistance", 9600, 0);
                maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
                this.resourceUsed = true; // v1.5.232：用过自救资源
                LOGGER.info("self drink: potion={} hp={}%",
                        potionKey(stack),
                        String.format("%.0f", this.hpRatio(maid) * 100.0f));
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** v1.5.208：扔喷溅/滞留型抗火药水（低抛落在自己脚下溅射，灭火/免疫岩浆）。
     *  背包无匹配药水返回 false
     *  v1.5.216：不再投掷——直接在自己位置生成 AreaEffectCloud（范围云）：
     *  旧版 ThrownPotion 低抛投掷实测"不会留下范围云/溅射"（投掷物理不确定：
     *  药水穿过/卡住/速度太慢），直接生成云 100% 生效——云 tick 时对半径内
     *  实体（含自己）施加抗火药水效果，与滞留药水同机制 */
    private boolean throwFireResistPotion(EntityMaid maid) {
        try {
            IItemHandler inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (stack.m_41619_()) {
                    continue;
                }
                net.minecraft.world.item.Item item = stack.m_41720_();
                boolean splash = item instanceof net.minecraft.world.item.SplashPotionItem
                        || item instanceof net.minecraft.world.item.LingeringPotionItem;
                if (!splash || !isFireResistPotion(stack)) {
                    continue;
                }
                net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) maid.m_9236_();
                net.minecraft.world.entity.AreaEffectCloud cloud =
                        new net.minecraft.world.entity.AreaEffectCloud(level,
                                maid.m_20185_(), maid.m_20188_(), maid.m_20189_());
                cloud.m_19722_(net.minecraft.world.item.alchemy.PotionUtils.m_43579_(stack)); // setPotion
                cloud.m_19732_(4.0f);  // setRadius：4 格溅射范围
                cloud.m_19734_(10);    // setWaitTime：0.5 秒后开始生效
                cloud.m_19714_(600);   // setDuration：30 秒（滞留云同款，持续刷新效果）
                cloud.m_19718_(maid);  // setOwner
                level.m_7967_(cloud);
                inv.extractItem(i, 1, false);
                maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
                this.resourceUsed = true; // v1.5.232：用过自救资源
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 抗火药水判定（饮用/喷溅/滞留通用——Potion 注册名 fire_resistance/long_fire_resistance） */
    private static boolean isFireResistPotion(ItemStack stack) {
        try {
            net.minecraft.world.item.alchemy.Potion potion =
                    net.minecraft.world.item.alchemy.PotionUtils.m_43579_(stack);
            if (potion == null) {
                return false;
            }
            net.minecraft.resources.ResourceLocation key = ForgeRegistries.POTIONS.getKey(potion);
            if (key == null) {
                return false;
            }
            String id = key.toString();
            return "minecraft:fire_resistance".equals(id) || "minecraft:long_fire_resistance".equals(id);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** v1.5.231：岩浆/着火最后兜底——吃金苹果/附魔金苹果（珍贵资源，仅自保模式
     *  调用；常规食物名单外）。附魔金苹果优先（吸收 III + 再生 IV + 抗火 5 分钟 +
     *  抗性——直接免疫岩浆）；普通金苹果（吸收 + 再生）撑血游出。返回 false = 没有。 */
    private boolean eatGoldenAppleForFire(EntityMaid maid) {
        try {
            net.minecraftforge.items.IItemHandler inv = maid.getMaidInv();
            int slot = -1;
            boolean enchanted = false;
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (stack.m_41619_()) {
                    continue;
                }
                net.minecraft.world.item.Item item = stack.m_41720_();
                if (item == net.minecraft.world.item.Items.f_42437_) { // enchanted_golden_apple
                    slot = i;
                    enchanted = true;
                    break; // 附魔金苹果效果最强，直接选
                }
                if (item == net.minecraft.world.item.Items.f_42436_ && slot < 0) { // golden_apple
                    slot = i;
                }
            }
            if (slot < 0) {
                return false;
            }
            ItemStack taken = inv.extractItem(slot, 1, false);
            if (taken.m_41619_()) {
                return false;
            }
            // v1.5.231b：真实进食（m_5584_）——走原版食物属性：附魔金苹果 =
            // 吸收 IV + 再生 II + 抗火 5 分钟 + 抗性；普通金苹果 = 吸收 I + 再生。
            // 不再手动 applyEffectTo（效果数值与原版一致，还加饱食——极端饥饿
            // 场景当食物吃也正确）
            maid.m_5584_(maid.m_9236_(), taken);
            maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
            this.resourceUsed = true; // v1.5.232：用过自救资源
            return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 给目标施加效果（注册表取效果，避开 SRG 字段名；m_7292_=addEffect） */
    private static void applyEffectTo(net.minecraft.world.entity.LivingEntity target,
                                      String effectId, int duration, int amplifier) {
        try {
            net.minecraft.world.effect.MobEffect effect = ForgeRegistries.MOB_EFFECTS
                    .getValue(ResourceLocation.parse(effectId));
            if (effect != null) {
                target.m_7292_(new net.minecraft.world.effect.MobEffectInstance(effect, duration, amplifier));
            }
        } catch (Exception ignored) {
        }
    }

    /** 岩浆专用垫高：身体格是液体（岩浆/水，可被替换）→ 放方块把自己顶出液面。
     *  与 buildUp 的区别：不查头顶空气（液体不窒息，只查实心） */
    private boolean lavaStepUp(EntityMaid maid) {
        BlockPos pos = maid.m_20183_();
        BlockState st = maid.m_9236_().m_8055_(pos);
        if (st.m_60796_(maid.m_9236_(), pos)) {
            return false; // 身体格已是实心（防御，不该发生）
        }
        Block block = this.takeBuildBlock(maid);
        if (block == null) {
            return false;
        }
        maid.m_9236_().m_7731_(pos, block.m_49966_(), 3);
        trackCombatPlaced(maid, pos, block); // v1.1.0 实测十七：战斗方块登记（60 秒自清）
        this.resourceUsed = true; // v1.5.232：垫块成功 = 自救资源
        return true;
    }

    /** 灭火器灭火（TLM 灭火器：在自己位置生成灭火 agent 灭身上的火） */
    private boolean extinguishSelf(EntityMaid maid) {
        net.minecraft.world.item.Item ext =
                com.github.tartaricacid.touhoulittlemaid.init.InitItems.EXTINGUISHER.get();
        if (ext == null) {
            return false;
        }
        IItemHandler inv = maid.getMaidInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s.m_41619_() || s.m_41720_() != ext) {
                continue;
            }
            inv.extractItem(i, 1, false);
            maid.m_9236_().m_7967_(new com.github.tartaricacid.touhoulittlemaid.entity.item.EntityExtinguishingAgent(
                    maid.m_9236_(), maid.m_20182_()));
            this.resourceUsed = true; // v1.5.232：灭火器成功 = 自救资源
            return true;
        }
        return false;
    }

    /** 方块扫描（半径内找满足条件的最近格；dy ±3） */
    private interface BlockCheck {
        boolean test(net.minecraft.world.level.Level level, BlockPos pos);
    }

    private BlockPos findBlockBy(EntityMaid maid, int radius, BlockCheck check) {
        BlockPos pos = maid.m_20183_();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = pos.m_7918_(dx, dy, dz);
                    // v1.5.214：区块加载检查——Server thread 上 getBlockState 未加载
                    // 区块会触发区块加载并等待自己的任务队列 → 自锁卡死（FarmSweepMixin
                    // 线程 dump 实证同款问题）；扫描一律只碰已加载区块
                    if (!maid.m_9236_().m_46749_(p)) {
                        continue;
                    }
                    if (!check.test(maid.m_9236_(), p)) {
                        continue;
                    }
                    double d = dx * dx + dz * dz + dy * dy * 0.25; // 水平距离为主，垂直轻微加权
                    if (d < bestDist) {
                        bestDist = d;
                        best = p;
                    }
                }
            }
        }
        return best;
    }

    private static boolean isWaterBlock(net.minecraft.world.level.block.Block b) {
        net.minecraft.resources.ResourceLocation key =
                net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(b);
        return key != null && "minecraft:water".equals(key.toString());
    }

    private static boolean isLavaBlock(net.minecraft.world.level.block.Block b) {
        net.minecraft.resources.ResourceLocation key =
                net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(b);
        return key != null && "minecraft:lava".equals(key.toString());
    }

    /** 着火逃生：最近水源（半径 12，覆盖静态/流动水） */
    private BlockPos findWater(EntityMaid maid) {
        return this.findBlockBy(maid, 12, (level, p) -> isWaterBlock(level.m_8055_(p).m_60734_()));
    }

    /** v1.5.232：会话外岩浆避让（仅影响移动方向及速度，不影响其他行为）——
     *  附近有岩浆且无抗火时：若女仆当前水平移动方向 3 格内会踩进岩浆格，
     *  改道离岩浆最远的安全点（cachedFleeSpot，找不到则不动）；方向干净则
     *  完全不干预——建造/跟随/插火把照常，不再进危险态、不打断任何行为。 */
    private void avoidLavaMovement(EntityMaid maid) {
        if (this.cachedNearLava == null || hasFireResist(maid) || maid.m_20077_()) {
            return;
        }
        net.minecraft.world.phys.Vec3 vel = maid.m_20184_();
        double hSpeed = Math.sqrt(vel.f_82479_ * vel.f_82479_ + vel.f_82481_ * vel.f_82481_);
        if (hSpeed < 0.05) {
            return; // 没在移动，不干预
        }
        double hx = vel.f_82479_ / hSpeed;
        double hz = vel.f_82481_ / hSpeed;
        net.minecraft.world.level.Level level = maid.m_9236_();
        for (int step = 1; step <= 3; step++) {
            BlockPos p = new BlockPos((int) Math.floor(maid.m_20185_() + hx * step),
                    (int) Math.floor(maid.m_20188_()),
                    (int) Math.floor(maid.m_20189_() + hz * step));
            if (!level.m_46749_(p)) {
                return; // 出了已加载区块就不预判
            }
            if (isLavaBlock(level.m_8055_(p).m_60734_())) {
                // 再往前走会踩进岩浆 → 改道离岩浆最远的安全点（仅移动）
                if (this.cachedFleeSpot != null) {
                    maid.m_21573_().m_26519_(this.cachedFleeSpot.m_123341_() + 0.5,
                            this.cachedFleeSpot.m_123342_(), this.cachedFleeSpot.m_123343_() + 0.5, 1.1f);
                }
                return;
            }
        }
    }

    /** v1.5.204（bug 3）：附近岩浆感知——半径内最近的岩浆方块（静态/流动都是
     *  minecraft:lava 块，一次覆盖）。dy ±2：垫高脱险后脚下隔一层垫块（y-1 是
     *  方块、y-2 才是岩浆）仍能感知 → 走"附近岩浆绕开"而不是误判卡墙。
     *  v1.5.229：感知半径主世界扩到 5 格（提前预警绕行岩浆湖——"感知岩浆能力
     *  约等于没有"的根因：3 格内发现时已经快踩进去了）；地狱收窄到 3 格
     *  （地狱岩浆遍地，半径 5 会让女仆永远"附近有岩浆"常驻危险态，无法工作）。
     *  返回 null = 附近没有岩浆。 */
    private BlockPos findNearbyLava(EntityMaid maid) {
        BlockPos pos = maid.m_20183_();
        int radius = isNether(maid) ? 3 : 5; // v1.5.229：维度感知半径
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = pos.m_7918_(dx, dy, dz);
                    // v1.5.214：区块加载检查（同 findBlockBy）
                    if (!maid.m_9236_().m_46749_(p)) {
                        continue;
                    }
                    if (isLavaBlock(maid.m_9236_().m_8055_(p).m_60734_())) {
                        double d = dx * dx + dz * dz + dy * dy * 0.25;
                        if (d < bestDist) {
                            bestDist = d;
                            best = p;
                        }
                    }
                }
            }
        }
        return best;
    }

    /** v1.5.204（bug 3）：岩浆避让目标——离岩浆 ≥4 格、自身非岩浆、脚下实心、
     *  无危险方块的安全站立点。找不到返回 null = 原地不动，绝不往岩浆方向走。
     *  v1.5.229：从"最近安全点"改为【离岩浆最远】的安全点（半径 8 内）——
     *  旧版选最近点会沿岩浆边缘"贴边走"甚至回头（感知半径 5 后更容易发生）；
     *  选最远点 = 持续远离岩浆，单调脱离威胁区。 */
    private BlockPos fleeLavaSpot(EntityMaid maid, BlockPos lava) {
        BlockPos pos = maid.m_20183_();
        net.minecraft.world.level.Level level = maid.m_9236_();
        BlockPos best = null;
        double bestDist = -1.0;
        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -8; dx <= 8; dx++) {
                for (int dz = -8; dz <= 8; dz++) {
                    BlockPos p = pos.m_7918_(dx, dy, dz);
                    if (!level.m_46749_(p)) {
                        continue;
                    }
                    if (isLavaBlock(level.m_8055_(p).m_60734_())) {
                        continue;
                    }
                    BlockPos below = p.m_7918_(0, -1, 0);
                    BlockState belowSt = level.m_8055_(below);
                    if (isLavaBlock(belowSt.m_60734_()) || !belowSt.m_60796_(level, below)) {
                        continue; // 脚下岩浆/不实心
                    }
                    int dx2 = p.m_123341_() - lava.m_123341_();
                    int dz2 = p.m_123343_() - lava.m_123343_();
                    double distFromLava = dx2 * dx2 + dz2 * dz2;
                    if (distFromLava < 16.0) {
                        continue; // 离岩浆 <4 格不算安全
                    }
                    if (this.hasDangerAt(level, p)) {
                        continue;
                    }
                    if (distFromLava > bestDist) {
                        bestDist = distFromLava;
                        best = p;
                    }
                }
            }
        }
        return best;
    }

    /** 岩浆逃生：最近安全格——自身非岩浆、脚下实心、无危险方块（半径 6） */
    private BlockPos findSafeSpot(EntityMaid maid) {
        return this.findBlockBy(maid, 6, (level, p) -> {
            if (isLavaBlock(level.m_8055_(p).m_60734_())) {
                return false;
            }
            BlockPos below = p.m_7918_(0, -1, 0);
            BlockState belowSt = level.m_8055_(below);
            if (isLavaBlock(belowSt.m_60734_()) || !belowSt.m_60796_(level, below)) {
                return false; // 脚下岩浆/不实心
            }
            return !this.hasDangerAt(level, p);
        });
    }

    /** v1.5.231：岩浆物理逃离目标——半径 12 内最近的非岩浆可站立格（岸边/高台）。
     *  与 findSafeSpot 的区别：半径更大（12 vs 6）+ 专用于资源手段全失败后的
     *  持续导航兜底（背包空的女仆掉岩浆，靠这个游回岸边，不再原地等死）。 */
    private BlockPos findLavaEdge(EntityMaid maid) {
        return this.findBlockBy(maid, 12, (level, p) -> {
            if (isLavaBlock(level.m_8055_(p).m_60734_())) {
                return false;
            }
            BlockPos below = p.m_7918_(0, -1, 0);
            BlockState belowSt = level.m_8055_(below);
            if (isLavaBlock(belowSt.m_60734_()) || !belowSt.m_60796_(level, below)) {
                return false; // 脚下岩浆/不实心
            }
            return !this.hasDangerAt(level, p);
        });
    }

    /** 溺水逃生：最近岸边——自身非水、脚下实心（半径 10） */
    private BlockPos findShore(EntityMaid maid) {
        return this.findBlockBy(maid, 10, (level, p) -> {
            if (isWaterBlock(level.m_8055_(p).m_60734_())) {
                return false;
            }
            BlockPos below = p.m_7918_(0, -1, 0);
            return level.m_8055_(below).m_60796_(level, below);
        });
    }

    /** 窒息逃生：最近双格空气（半径 4） */
    private BlockPos findAirSpot(EntityMaid maid) {
        return this.findBlockBy(maid, 4, (level, p) -> level.m_8055_(p).m_60795_()
                && level.m_8055_(p.m_7918_(0, 1, 0)).m_60795_());
    }

    /**
     * 垂直垫高（v1.5.20 重写）：像僵尸搭方块一样往自己所在格放方块，被顶起一格——
     * 这才是"真搭高"（旧版只往脚下悬空处垫，地面战斗永不触发）。
     * v1.5.24 防窒息强化：除了所在格上方 2 格，还检查**女仆实际头顶**
     * （碰撞盒顶面，含移动中的真实位置）上方必须空旷——确保女仆永远是
     * 所在位置"最上面的方块"之上的实体，不会被自己搭的方块夹住窒息。
     * v1.5.25 再优化：放块位置【脚下格优先】——脚下悬空就垫脚下（人自然站上去，
     * 不夹人）；脚下是实心地面（平地）才放所在格把自己顶起。配合 antiSuffocate
     * 每 tick 兜底，搭高基本不再窒息。
     */
    private boolean buildUp(EntityMaid maid) {
        BlockPos pos = maid.m_20183_();
        // v1.5.25b：选择放置格——脚下悬空 → 垫所在格 pos（站上去升高）；
        // 脚下实心（平地）→ 垫身体格 pos+1（方块放置的实体挤压机制把她顶起一格，
        // 不碰地面方块）。旧版（v1.5.25）要求"所在格必须空气"，女仆站在平地上
        // 脚部格是地面方块 → 搭高永远失败 → "只会平搭/搭高成功率低"的根因。
        BlockPos place;
        boolean posAir = maid.m_9236_().m_8055_(pos).m_60795_();
        if (posAir) {
            place = pos; // 悬空：垫所在格，站上去升高
        } else {
            place = pos.m_7918_(0, 1, 0); // 平地：垫身体格顶起
        }
        // 防窒息：放置格 + 上方 2 格必须空气（顶起后的身体空间 + 头顶空间）
        if (!maid.m_9236_().m_8055_(place).m_60795_()
                || !maid.m_9236_().m_8055_(place.m_7918_(0, 1, 0)).m_60795_()
                || !maid.m_9236_().m_8055_(place.m_7918_(0, 2, 0)).m_60795_()) {
            return false;
        }
        // v1.5.24：女仆实际头顶（bounding box 顶面）上方必须空旷——
        // 快速连续搭高时实体位移滞后，blockPosition 检查不够，必须按真实位置判定
        double headY = maid.m_20191_().m_82374_(net.minecraft.core.Direction.Axis.Y);
        BlockPos headPos = new BlockPos((int) maid.m_20185_(), (int) (headY + 0.05), (int) maid.m_20189_());
        if (!maid.m_9236_().m_8055_(headPos).m_60795_()
                || !maid.m_9236_().m_8055_(headPos.m_7918_(0, 1, 0)).m_60795_()) {
            return false; // 实际头顶被堵（正在被顶起中）→ 等站稳再垫，防窒息
        }
        Block block = this.takeBuildBlock(maid);
        if (block == null) {
            return false;
        }
        maid.m_9236_().m_7731_(place, block.m_49966_(), 3);
        trackCombatPlaced(maid, place, block); // v1.1.0 实测十七：战斗方块登记（60 秒自清）
        return true;
    }

    /**
     * v1.5.24：连续两次被近身 → 击退周围所有敌人（无伤害，仅击退）+
     * 起步搭高 1 块，并进入强制搭高状态（持续往上垫、不逃跑）。
     * 旧版同一 tick 连放 3 层——实体来不及被顶起 → 自己窒息。改为只放 1 块
     * 起步，后续由强制搭高状态按安全节奏（BUILD_COOLDOWN）继续，效果仍是快速上高。
     */
    private void triggerPillarBurst(EntityMaid maid) {
        this.forcedPillar = true;
        // 1. 击退周围 5 格敌人（无伤害）
        this.pushBackNearby(maid);
        // v1.5.250：删除击退搭高垫水（旧 v1.5.199 水桶垫水缓冲——实测严重
        // 影响向上搭高节奏：脚下放水+1 秒后收回的收放循环拖慢垫高，删除）
        // 2. 起步搭高 1 块（防窒息：不在同一 tick 连放多层）
        if (this.pillarBaseY < 0) {
            this.pillarBaseY = maid.m_20183_().m_123342_();
        }
        if (!this.buildUp(maid)) {
            this.forcedPillar = false; // 起步失败（头顶被堵/没材料）→ 不进入强制搭高
        }
        this.buildCooldown = 3;
    }

    /** v1.5.24：击退女仆周围 5 格的所有敌人（无伤害，仅击退，从女仆位置向外推）
     *  v1.5.195：威胁 = 敌对生物 + 对女仆/主人带仇恨的中立生物（isThreat） */
    private void pushBackNearby(EntityMaid maid) {
        List<Mob> nearby = maid.m_9236_().m_6443_(Mob.class,
                maid.m_20191_().m_82400_(5.0),
                m -> m instanceof LivingEntity && com.maidsmart.dialogue.PerceptionManager.isThreat((LivingEntity) m, maid));
        for (Mob m : nearby) {
            double dx = m.m_20185_() - maid.m_20185_();
            double dz = m.m_20189_() - maid.m_20189_();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 0.01) {
                continue; // 完全重叠：跳过
            }
            m.m_147240_(0.5, dx / len, dz / len); // 小力度击退（无伤害）
        }
    }

    /** 卡住检测（借鉴 numen UnstuckDetector 的滑动窗口思想，简化为连续计数） */
    private void trackStuck(EntityMaid maid) {
        double x = maid.m_20185_();
        double z = maid.m_20189_();
        double dx = x - this.lastStuckX;
        double dz = z - this.lastStuckZ;
        if (dx * dx + dz * dz < stuckThreshold() * stuckThreshold()) {
            this.stuckTicks++;
        } else {
            this.stuckTicks = 0;
            this.lastStuckX = x;
            this.lastStuckZ = z;
        }
    }

    /**
     * 搭方块翻越（v1.5.20，借鉴 EpicSiege/史诗战斗僵尸"搭方块追人"的逆向逃生版）：
     * 逃跑方向前方 2 格有墙 → 垫自己与墙之间的脚下形成上升台阶（连续垫，
     * 她走上台阶再垫下一级，阶梯式翻墙）；前方脚下悬空（断崖）→ 垫前方脚下搭桥。
     * 只垫"该垫"的位置，不破坏任何方块。
     */
    private boolean bridgeStep(EntityMaid maid, LivingEntity threat) {
        double dx = maid.m_20185_() - threat.m_20185_();
        double dz = maid.m_20189_() - threat.m_20189_();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) {
            return false;
        }
        int y = maid.m_20183_().m_123342_();
        int tx1 = (int) Math.floor(maid.m_20185_() + dx / len);
        int tz1 = (int) Math.floor(maid.m_20189_() + dz / len);
        int tx2 = (int) Math.floor(maid.m_20185_() + dx / len * 2.0);
        int tz2 = (int) Math.floor(maid.m_20189_() + dz / len * 2.0);
        // 情况 A：前方 2 格是【实心满块】墙（v1.5.25：树叶/高草/水等非实心不算墙，
        // 防止"只喜欢原地平搭"乱垫浪费方块）→ 垫前方 1 格脚下，形成上升台阶
        BlockPos wallPos = new BlockPos(tx2, y, tz2);
        if (maid.m_9236_().m_8055_(wallPos).m_60796_(maid.m_9236_(), wallPos)) {
            BlockPos step = new BlockPos(tx1, y - 1, tz1);
            if (maid.m_9236_().m_8055_(step).m_60795_()
                    && maid.m_9236_().m_8055_(new BlockPos(tx1, y, tz1)).m_60795_()) {
                return this.placeBlock(maid, step);
            }
            return false;
        }
        // 情况 B：前方 1 格脚下悬空（断崖/缺口）→ 垫前方脚下搭桥
        BlockPos gap = new BlockPos(tx1, y - 1, tz1);
        if (maid.m_9236_().m_8055_(gap).m_60795_()
                && maid.m_9236_().m_8055_(new BlockPos(tx1, y, tz1)).m_60795_()) {
            return this.placeBlock(maid, gap);
        }
        return false;
    }

    /** 放一块搭方块（背包数量最多的优先，v1.5.20） */
    private boolean placeBlock(EntityMaid maid, BlockPos pos) {
        Block block = this.takeBuildBlock(maid);
        if (block == null) {
            return false;
        }
        maid.m_9236_().m_7731_(pos, block.m_49966_(), 3);
        trackCombatPlaced(maid, pos, block); // v1.1.0 实测十七：战斗方块登记（60 秒自清）
        return true;
    }

    /**
     * v1.5.194：搭方块中途的额外判定——威胁还贴在脚下（< 近身距离）时，顺手往
     * 它的【眼睛格】（脚底+1，MC 窒息判定即头部所在格）放方块封头 + 头顶盖帽，
     * 尝试让它窒息。纯被动机制，无任何气泡。
     *
     * 设计要点（替代旧 v1.5.187 "搭完后糊脸"——旧版等搭到顶/安全高度才操作，
     * 30 格时怪物早丢索敌，封头无意义）：
     * - 只在搭方块（buildUp）成功之后调用，与搭高共享 buildCooldown（4 tick），
     *   形成"搭一块 → 封一次头 → 再搭一块"的交替节奏；
     * - 眼睛格已实心 → 直接返回（已封住/已被地形挡住），不重复烧方块；
     * - 身高 <1.4 的低矮怪（蜘蛛 0.9）跳过——眼睛在自身格内，单方块封不住；
     * - 每场自保会话最多 3 次尝试（suffocateBudget，m_6735_ 重置），防耗尽背包；
     * - 方块永久留下（用户选择，不自动清理）。
     */
    private void trySuffocateDuringBuild(EntityMaid maid, LivingEntity threat) {
        try {
            if (this.suffocateBudget <= 0) {
                return;
            }
            if (threat == null || !threat.m_6084_()) {
                return;
            }
            double bb = threat.m_20191_().m_82374_(net.minecraft.core.Direction.Axis.Y);
            double eyeY = threat.m_20188_(); // getEyeY
            if (eyeY - bb < 1.4) {
                return; // 低矮怪：眼睛格在自身格内，单方块封不住
            }
            BlockPos foot = threat.m_20183_();
            BlockPos eyeCap = foot.m_7918_(0, 1, 0);
            if (!maid.m_9236_().m_8055_(eyeCap).m_60795_()) {
                return; // 眼睛格已实心 → 已封住/已被堵，跳过
            }
            Block block = this.takeBuildBlock(maid);
            if (block == null) {
                return;
            }
            this.suffocateBudget--;
            maid.m_9236_().m_7731_(eyeCap, block.m_49966_(), 3); // 头封块（窒息判定格）
            trackCombatPlaced(maid, eyeCap, block); // v1.1.0 实测十七：战斗方块登记（60 秒自清）
            // 盖帽块：脚底 + ceil(身高)（如僵尸 +2），空气才放；取不到跳过（头封块已挡视线）
            int headH = (int) Math.ceil(threat.m_20206_());
            BlockPos cap = foot.m_7918_(0, headH, 0);
            if (!cap.equals(eyeCap) && maid.m_9236_().m_8055_(cap).m_60795_()) {
                Block capBlock = this.takeBuildBlock(maid);
                if (capBlock != null) {
                    maid.m_9236_().m_7731_(cap, capBlock.m_49966_(), 3);
                    trackCombatPlaced(maid, cap, capBlock); // v1.1.0 实测十七：战斗方块登记
                }
            }
            // 与搭高共享冷却：本次调用不重置（buildUp 成功后已置 buildCd），
            // 下一轮 4 tick 后照常搭高/封头交替
        } catch (Exception ignored) {
        }
    }

    /**
     * v1.5.20：无威胁持续一段时间后传送回主人身边。
     * v1.5.21：加传送冷却（60 秒），防"传回家又被打→又传"循环。
     * 主人主动 TP 女仆不受影响（不走 teleportToOwner）。
     *
     * v1.5.91【双判定】曾改为同时判定自己身边与主人身边都无怪才传——v1.5.149
     * 撤销自己身边判定：被怪追着跑时自己身边永远有怪 → 永远不传（用户反馈
     * "怪一直在追导致无法传送"）。现只确认【主人身边】安全即可传送（防
     * "传回主人身边送死"）；传送成功 → 立即结束自保。
     */
    private void teleportHome(EntityMaid maid) {
        long now = maid.m_9236_().m_46467_();
        // v1.5.157：传送失败重试间隔封顶 100 tick（5 秒）——旧配置 teleportCooldown
        // = 1200（60 秒）不会跟随新默认，导致"主人身边没怪也不传送"（用户反馈，
        // 配置实证 teleportCooldown=1200）。用 min(配置, 100) 保证实时性：
        // 传送失败（主人身边有怪）最多 5 秒后重试；成功即结束自保，不会循环。
        if (now - this.lastTeleportTime < Math.min(teleportCooldown(), 100)) {
            return; // 重试冷却中，等下一轮
        }
        LivingEntity owner = maid.m_269323_();
        if (owner == null) {
            return;
        }
        double dx = maid.m_20185_() - owner.m_20185_();
        double dz = maid.m_20189_() - owner.m_20189_();
        if (dx * dx + dz * dz < 25.0) {
            return; // 已在主人 5 格内，不用传
        }
        // v1.5.112：半径从写死 6 改为配置（teleportSafeRadius，默认 4）——用户反馈
        // 6 格太宽松（远处怪物也算不安全，导致一直不传），4 格更贴身合理
        double sr = teleportSafeRadius();
        // v1.5.149：只判定主人身边安全即可传送（自己身边有怪不再拦截——被追时
        // 自己身边永远有怪，双判定会导致永远传不回去）
        if (this.anyVisibleMonsterAround(owner, sr)) {
            return; // 主人身边不安全，本轮不传
        }
        // 主人身边已验证安全 → 传送（v1.5.202：传送不再结束保命会话——会话由
        // m_6725_ 维护；传回后血若仍低会继续回血/警戒，血恢复且环境安全后自然结束）
        maid.m_6034_(owner.m_20185_(), owner.m_20186_(), owner.m_20189_());
        // v1.5.27：传送不重置 fallDistance——主人若悬空/站在高处边缘，女仆传过去
        // 立即坠落会带着柱子上累计的摔落距离 → 配合落地水重新计算（WaterClutch 也有突变兜底）
        maid.f_19789_ = 0.0f;
        this.lastTeleportTime = now;
        // v1.5.158：真传送成功播报（不暗示"绝对安全"，与可能紧接着的"情况不妙"
        // 语义连贯："撤了" → "回到主人身边"）
        // v1.5.227：播报 60 秒限频——传送冷却 5 秒时会反复连传连喊（实测刷屏）
        if (now - this.lastHomeAnnounceTick >= 1200) {
            this.lastHomeAnnounceTick = now;
            maid.getChatBubbleManager().addTextChatBubble("我回到主人身边了，先缓缓……");
        }
    }

    /** v1.5.21：末影珍珠逃生——致命时刻（血<20% 且威胁贴身）扔珍珠瞬移走 */
    private boolean pearlEscape(EntityMaid maid, LivingEntity threat) {
        if (this.pearlCooldown > 0) {
            return false;
        }
        IItemHandler inv = maid.getMaidInv();
        net.minecraft.world.item.Item pearlItem = ForgeRegistries.ITEMS
                .getValue(ResourceLocation.parse("minecraft:ender_pearl"));
        int slot = -1;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.m_41619_() && pearlItem != null && s.m_41720_() == pearlItem) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            return false;
        }
        // 落点：反威胁方向 16 格
        double dx = maid.m_20185_() - threat.m_20185_();
        double dz = maid.m_20189_() - threat.m_20189_();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) {
            return false;
        }
        int tx = (int) Math.floor(maid.m_20185_() + dx / len * 16.0);
        int tz = (int) Math.floor(maid.m_20189_() + dz / len * 16.0);
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) maid.m_9236_();
        // 找该列的地面（从当前高度向下找到第一个实心格）
        int ty = maid.m_20183_().m_123342_();
        while (ty > 0 && level.m_8055_(new BlockPos(tx, ty, tz)).m_60795_()) {
            ty--;
        }
        if (this.hasDangerAt(level, new BlockPos(tx, ty, tz))) {
            return false; // 落点危险（岩浆等），不扔
        }
        // v1.5.91【珍珠落点安全判定（治活埋/卡墙）】：落点不是简单"有地面"就够。
        // 珍珠瞬移是直接 setPos——落点上下被实心方块夹住 / 头顶封死会窒息或卡墙。
        // 守卫：脚下是实心可站（防落入岩浆/深渊）+ 站立位与头顶 ≥2 格空气（防窒息）。
        BlockPos standPos = new BlockPos(tx, ty + 1, tz); // 站立格
        BlockPos belowPos = new BlockPos(tx, ty, tz); // 脚下格
        if (!level.m_8055_(belowPos).m_60795_() // 脚下是实心（非空）
                || !level.m_8055_(standPos).m_60795_() // 站立格是空气
                || !level.m_8055_(standPos.m_7918_(0, 1, 0)).m_60795_() // 头顶 1 格空气
                || !level.m_8055_(standPos.m_7918_(0, 2, 0)).m_60795_()) { // 头顶 2 格空气
            return false; // 落点会被活埋/卡墙 → 不扔（治旧版 stuck）
        }
        inv.extractItem(slot, 1, false);
        // v1.5.237：投掷原版末影珍珠（紫色轨迹 + 落地传送特效），不再直接瞬移
        if (!this.throwPearlTo(maid, level, tx + 0.5, standPos.m_123342_() + 0.5, tz + 0.5)) {
            // 投掷失败（异常兜底）→ 直接瞬移
            maid.m_6034_(tx + 0.5, standPos.m_123342_() + 0.0, tz + 0.5);
            // v1.5.27：传送不重置 fallDistance——珍珠逃生落地瞬间摔落距离归零，
            // 避免从战斗地点带过去的累计伤害（落地水突变检测也有兜底）
            maid.f_19789_ = 0.0f;
        }
        this.pearlCooldown = pearlCooldown();
        return true;
    }

    /** v1.5.21：喝治疗药水（Instant Health 直接回血）；没有返回 false */
    private boolean useHealingPotion(EntityMaid maid) {
        IItemHandler inv = maid.getMaidInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.m_41619_()) {
                continue;
            }
            Item item = stack.m_41720_();
            // v1.5.24：饮用/喷溅/滞留治疗药水都算（旧版只认饮用型 PotionItem）
            boolean isPotion = item instanceof net.minecraft.world.item.PotionItem
                    || item instanceof net.minecraft.world.item.SplashPotionItem
                    || item instanceof net.minecraft.world.item.LingeringPotionItem;
            if (!isPotion) {
                continue;
            }
            net.minecraft.world.item.alchemy.Potion potion =
                    net.minecraft.world.item.alchemy.PotionUtils.m_43579_(stack);
            if (potion == null) {
                continue;
            }
            net.minecraft.resources.ResourceLocation key = ForgeRegistries.POTIONS.getKey(potion);
            if (key == null) {
                continue;
            }
            // 治疗 I（healing）= 4 点，治疗 II（strong_healing）= 8 点（与玩家喝药一致）
            float heal;
            if ("minecraft:healing".equals(key.toString())) {
                heal = 4.0f;
            } else if ("minecraft:strong_healing".equals(key.toString())) {
                heal = 8.0f;
            } else {
                continue;
            }
            inv.extractItem(i, 1, false);
            maid.m_5634_(heal);
            this.giveBottle(maid); // v1.5.24：喝药留空玻璃瓶（原版逻辑）
            LOGGER.info("self drink: potion={} hp={}%",
                    potionKey(stack),
                    String.format("%.0f", this.hpRatio(maid) * 100.0f));
            // v1.5.252g7：瞬间治疗短 CD（40 tick ≈ 2 秒可连喝）——用户规则
            // "除瞬间治疗外其他药水 CD = 时长"
            this.markPotionUsed(potionKey(stack), maid.m_9236_().m_46467_(), 40);
            return true;
        }
        return false;
    }

    /** v1.5.24：消耗药水后返还空玻璃瓶（与玩家喝药一致，不"凭空消失"） */
    private void giveBottle(EntityMaid maid) {
        net.minecraft.world.item.Item bottle = ForgeRegistries.ITEMS
                .getValue(ResourceLocation.parse("minecraft:glass_bottle"));
        if (bottle != null) {
            ItemHandlerHelper.insertItemStacked(maid.getMaidInv(), new ItemStack(bottle), false);
        }
    }

    /**
     * v1.5.252g：通用喝【饮用型】药水——背包找含指定效果 id 的药水，喝掉返还
     * 空瓶、施加全部效果。返回该药水最长效果时长（tick，作 CD；瞬间效果短 CD）；
     * 没喝到返回 -1。（溺水喝水肺 / 其他情境药水复用）
     */
    private int drinkPotionWithEffect(EntityMaid maid, String effectId) {
        try {
            IItemHandler inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (stack.m_41619_() || !(stack.m_41720_() instanceof net.minecraft.world.item.PotionItem)) {
                    continue;
                }
                net.minecraft.world.item.alchemy.Potion potion =
                        net.minecraft.world.item.alchemy.PotionUtils.m_43579_(stack);
                if (potion == null || !potionHasEffect(potion, effectId)) {
                    continue;
                }
                inv.extractItem(i, 1, false);
                this.giveBottle(maid);
                LOGGER.info("self drink: potion={} hp={}%",
                        potionKey(stack),
                        String.format("%.0f", this.hpRatio(maid) * 100.0f));
                int maxDur = 0;
                for (net.minecraft.world.effect.MobEffectInstance e :
                        net.minecraft.world.item.alchemy.PotionUtils.m_43571_(stack)) {
                    maid.m_7292_(new net.minecraft.world.effect.MobEffectInstance(e));
                    maxDur = Math.max(maxDur, e.m_19557_());
                }
                maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
                this.resourceUsed = true;
                // v1.5.252g7：按药水种类记 CD（= 该药水最长时长；瞬间效果短 CD）
                this.markPotionUsed(potionKey(stack), maid.m_9236_().m_46467_(), maxDur > 0 ? maxDur : 40);
                return Math.max(40, Math.min(maxDur > 0 ? maxDur : 40, 12000));
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /**
     * v1.5.252g：扔【喷溅/滞留型】药水——在自己位置生成 AreaEffectCloud 施加
     * 效果（与抗火云同机制）。返回最长效果时长（CD）；没找到返回 -1。
     */
    private int splashPotionWithEffect(EntityMaid maid, String effectId) {
        try {
            IItemHandler inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (stack.m_41619_()) {
                    continue;
                }
                net.minecraft.world.item.Item item = stack.m_41720_();
                boolean splash = item instanceof net.minecraft.world.item.SplashPotionItem
                        || item instanceof net.minecraft.world.item.LingeringPotionItem;
                if (!splash) {
                    continue;
                }
                net.minecraft.world.item.alchemy.Potion potion =
                        net.minecraft.world.item.alchemy.PotionUtils.m_43579_(stack);
                if (potion == null || !potionHasEffect(potion, effectId)) {
                    continue;
                }
                net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) maid.m_9236_();
                net.minecraft.world.entity.AreaEffectCloud cloud =
                        new net.minecraft.world.entity.AreaEffectCloud(level,
                                maid.m_20185_(), maid.m_20188_(), maid.m_20189_());
                cloud.m_19722_(potion);   // setPotion
                cloud.m_19732_(4.0f);     // setRadius
                cloud.m_19734_(10);       // setWaitTime
                cloud.m_19714_(600);      // setDuration：30 秒云
                cloud.m_19718_(maid);     // setOwner
                level.m_7967_(cloud);
                inv.extractItem(i, 1, false);
                maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
                this.resourceUsed = true;
                LOGGER.info("self drink: potion={} hp={}%",
                        potionKey(stack),
                        String.format("%.0f", this.hpRatio(maid) * 100.0f));
                int maxDur = 0;
                for (net.minecraft.world.effect.MobEffectInstance e : potion.m_43488_()) {
                    maxDur = Math.max(maxDur, e.m_19557_());
                }
                // v1.5.252g7：按药水种类记 CD（= 该药水最长时长；瞬间效果短 CD）
                this.markPotionUsed(potionKey(stack), level.m_46467_(), maxDur > 0 ? maxDur : 40);
                return Math.max(40, Math.min(maxDur > 0 ? maxDur : 40, 12000));
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** v1.5.252g：药水是否含指定效果（按注册名，如 minecraft:water_breathing） */
    private static boolean potionHasEffect(net.minecraft.world.item.alchemy.Potion potion, String effectId) {
        try {
            for (net.minecraft.world.effect.MobEffectInstance e : potion.m_43488_()) {
                net.minecraft.world.effect.MobEffect effect = e.m_19544_();
                if (effect == null) {
                    continue;
                }
                net.minecraft.resources.ResourceLocation key = ForgeRegistries.MOB_EFFECTS.getKey(effect);
                if (key != null && effectId.equals(key.toString())) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * v1.5.252g：中毒时喝蜂蜜瓶——原版蜂蜜瓶清除中毒效果 + 恢复 6 饥饿；
     * 这里手动模拟（清除中毒 + 少量回血支撑），喝完留玻璃瓶。没有返回 false。
     */
    private boolean drinkHoneyForPoison(EntityMaid maid) {
        try {
            IItemHandler inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (stack.m_41619_() || stack.m_41720_() != net.minecraft.world.item.Items.f_42787_) {
                    continue; // honey_bottle
                }
                inv.extractItem(i, 1, false);
                this.giveBottle(maid); // 喝完留玻璃瓶（原版一致）
                maid.m_21195_(net.minecraft.world.effect.MobEffects.f_19614_); // removeEffect(POISON)
                maid.m_5634_(3.0f);    // 回 1.5 心（近似原版饥饿→饱和恢复）
                maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
                this.resourceUsed = true;
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * v1.5.281：负面效果自清（会话内外常驻，见 m_6725_ 调用点）——
     * (1) 中毒 → 蜂蜜瓶（精准解毒 + 回血 + 留玻璃瓶；蜂蜜只清中毒，不误伤增益）；
     * (2) 蜂蜜已喝/CD 中/无蜂蜜 → 若自身【无增益效果】且【有负面效果】→ 喝牛奶
     * 清全部效果（牛奶清所有——有增益时喝会把增益也清掉，所以前提是"只有负面
     * buff"）。蜂蜜与牛奶各自独立 CD（potionReady("honey"/"milk")）。
     */
    private void tickCureNegativeEffects(EntityMaid maid) {
        long nowTick = maid.m_9236_().m_46467_();
        if (this.potionReady("honey", nowTick)
                && maid.m_21023_(net.minecraft.world.effect.MobEffects.f_19614_) // hasEffect(POISON)
                && this.drinkHoneyForPoison(maid)) {
            this.markPotionUsed("honey", nowTick, potionCooldown());
            return;
        }
        if (this.potionReady("milk", nowTick)
                && this.hasOnlyNegativeEffects(maid)
                && this.drinkMilkBucket(maid)) {
            this.markPotionUsed("milk", nowTick, potionCooldown());
        }
    }

    /** v1.5.281：是否"无增益 + 有负面"——牛奶前提（牛奶清全部效果，
     *  有增益时喝 = 把增益也清掉；中性效果如发光不参与判定） */
    private static boolean hasOnlyNegativeEffects(EntityMaid maid) {
        boolean hasHarmful = false;
        for (net.minecraft.world.effect.MobEffectInstance ei : maid.m_21220_()) {
            net.minecraft.world.effect.MobEffectCategory cat = ei.m_19544_().m_19483_();
            if (cat == net.minecraft.world.effect.MobEffectCategory.BENEFICIAL) {
                return false; // 有增益 → 不喝牛奶
            }
            if (cat == net.minecraft.world.effect.MobEffectCategory.HARMFUL) {
                hasHarmful = true;
            }
        }
        return hasHarmful;
    }

    /** v1.5.281：喝牛奶桶——消耗 1 桶、清全部效果、返还空桶（原版一致） */
    private boolean drinkMilkBucket(EntityMaid maid) {
        try {
            IItemHandler inv = maid.getMaidInv();
            net.minecraft.world.item.Item milk = ForgeRegistries.ITEMS
                    .getValue(ResourceLocation.parse("minecraft:milk_bucket"));
            if (milk == null) {
                return false;
            }
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (stack.m_41619_() || stack.m_41720_() != milk) {
                    continue;
                }
                inv.extractItem(i, 1, false);
                // 清全部效果（原版牛奶语义 removeAllEffects）
                java.util.List<net.minecraft.world.effect.MobEffectInstance> effects =
                        new java.util.ArrayList<>(maid.m_21220_());
                for (net.minecraft.world.effect.MobEffectInstance ei : effects) {
                    maid.m_21195_(ei.m_19544_());
                }
                this.giveBucket(maid); // 喝完返还空桶（原版一致）
                maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
                this.resourceUsed = true;
                LOGGER.info("self drink: milk_bucket hp={}% cleared={}",
                        String.format("%.0f", this.hpRatio(maid) * 100.0f), effects.size());
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** v1.5.281：返还空桶（喝牛奶桶后——原版喝完变空桶） */
    private void giveBucket(EntityMaid maid) {
        net.minecraft.world.item.Item bucket = ForgeRegistries.ITEMS
                .getValue(ResourceLocation.parse("minecraft:bucket"));
        if (bucket != null) {
            ItemHandlerHelper.insertItemStacked(maid.getMaidInv(), new ItemStack(bucket), false);
        }
    }

    /**
     * v1.5.201：金苹果/附魔金苹果——【不作为常规食物吃】（已移出 HEAL_FOODS），
     * 像药水一样即时使用：消耗 1 个，给自己施加原版金苹果效果（吸收+再生；
     * 附魔金苹果额外抗性/抗火）。附魔金苹果优先（效果更强）。没有返回 false。
     */
    private boolean useGoldenApple(EntityMaid maid) {
        try {
            IItemHandler inv = maid.getMaidInv();
            int bestSlot = -1;
            boolean enchanted = false;
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (stack.m_41619_()) {
                    continue;
                }
                net.minecraft.world.item.Item item = stack.m_41720_();
                if (item == net.minecraft.world.item.Items.f_42437_) { // enchanted_golden_apple
                    bestSlot = i;
                    enchanted = true;
                    break; // 附魔金苹果效果最强，直接选
                }
                if (item == net.minecraft.world.item.Items.f_42436_ && bestSlot < 0) { // golden_apple
                    bestSlot = i;
                }
            }
            if (bestSlot < 0) {
                return false;
            }
            inv.extractItem(bestSlot, 1, false);
            applyGoldenAppleEffects(maid, enchanted);
            // v1.5.252g7：金苹果短 CD（40 tick，可连吃）
            this.markPotionUsed("golden_apple", maid.m_9236_().m_46467_(), 40);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 施加原版金苹果效果（数值与原版食物一致；m_7292_=addEffect） */
    private static void applyGoldenAppleEffects(net.minecraft.world.entity.LivingEntity target, boolean enchanted) {
        try {
            net.minecraft.world.effect.MobEffect absorption = ForgeRegistries.MOB_EFFECTS
                    .getValue(ResourceLocation.parse("minecraft:absorption"));
            net.minecraft.world.effect.MobEffect regen = ForgeRegistries.MOB_EFFECTS
                    .getValue(ResourceLocation.parse("minecraft:regeneration"));
            if (absorption != null) {
                target.m_7292_(new net.minecraft.world.effect.MobEffectInstance(absorption, 2400, enchanted ? 3 : 0));
            }
            if (regen != null) {
                target.m_7292_(new net.minecraft.world.effect.MobEffectInstance(regen, enchanted ? 600 : 100, enchanted ? 4 : 1));
            }
            if (enchanted) {
                net.minecraft.world.effect.MobEffect res = ForgeRegistries.MOB_EFFECTS
                        .getValue(ResourceLocation.parse("minecraft:damage_resistance"));
                net.minecraft.world.effect.MobEffect fire = ForgeRegistries.MOB_EFFECTS
                        .getValue(ResourceLocation.parse("minecraft:fire_resistance"));
                if (res != null) {
                    target.m_7292_(new net.minecraft.world.effect.MobEffectInstance(res, 6000, 0));
                }
                if (fire != null) {
                    target.m_7292_(new net.minecraft.world.effect.MobEffectInstance(fire, 6000, 0));
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * v1.5.25：喝任意【增益药水】——不再硬编码两种。
     * 扫描背包，饮用/喷溅/滞留药水都算；取药水的实际效果（PotionUtils），
     * 只要含增益效果（再生/迅捷/力量/抗火/隐身/夜视/跳跃/伤害吸收/生命提升）就喝，
     * 自动适配延长版（long_*）/II级（strong_*）；喝掉后按原效果施加 + 返还空瓶。
     * v1.5.252g【CD 规则】：返回该药水最长效果时长（tick）作 CD（瞬间效果短 CD）；
     * 没喝到返回 -1。
     */
    private int useBeneficialPotion(EntityMaid maid) {
        long nowTick = maid.m_9236_().m_46467_();
        IItemHandler inv = maid.getMaidInv();
        // v1.5.252g8：第一遍【只找饮用型】——喷溅/滞留型效果时长只有饮用型的
        // 1/2~1/4，同样一瓶药水吃滞留型纯浪费（旧版按槽位顺序取第一瓶，可能
        // 先吃到滞留型）；没有饮用型才用喷溅/滞留兜底
        int r = this.drinkBeneficialPass(maid, inv, nowTick, true);
        if (r >= 0) {
            return r;
        }
        return this.drinkBeneficialPass(maid, inv, nowTick, false);
    }

    /** 喝增益药水的一遍扫描：drinkOnly=true 只认饮用型（PotionItem），false 只认
     *  喷溅/滞留型。同种 CD 门 + 增益判定都在内部；找到即喝，返回 CD（没喝到 -1） */
    private int drinkBeneficialPass(EntityMaid maid, IItemHandler inv, long nowTick, boolean drinkOnly) {
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.m_41619_()) {
                continue;
            }
            Item item = stack.m_41720_();
            boolean isDrink = item instanceof net.minecraft.world.item.PotionItem;
            boolean isSplash = item instanceof net.minecraft.world.item.SplashPotionItem
                    || item instanceof net.minecraft.world.item.LingeringPotionItem;
            if (drinkOnly ? !isDrink : !isSplash) {
                continue;
            }
            net.minecraft.world.item.alchemy.Potion potion =
                    net.minecraft.world.item.alchemy.PotionUtils.m_43579_(stack);
            if (potion == null) {
                continue;
            }
            // v1.5.252g7：同种药水 CD 内跳过（CD = 该药水时长；其他种照喝）
            if (!this.potionReady(potionKey(stack), nowTick)) {
                continue;
            }
            java.util.List<net.minecraft.world.effect.MobEffectInstance> effects = potion.m_43488_();
            if (effects == null || effects.isEmpty()) {
                continue;
            }
            // 该药水是否含增益效果（任一效果是增益且非瞬间治疗——治疗走 useHealingPotion）
            boolean beneficial = false;
            for (net.minecraft.world.effect.MobEffectInstance ei : effects) {
                net.minecraft.world.effect.MobEffect effect = ei.m_19544_();
                if (effect == null) {
                    continue;
                }
                // 瞬间治疗（healing）走 useHealingPotion 精确回血；其余增益效果（再生/迅捷/
                // 力量/隐身/跳跃/伤害吸收/生命提升）这里统一喝（抗火/水肺/夜视是情境药水，
                // 由专项分支按情境喝，见 isSituational）
                net.minecraft.resources.ResourceLocation eid = ForgeRegistries.MOB_EFFECTS.getKey(effect);
                boolean isHealing = eid != null
                        && ("minecraft:healing".equals(eid.toString())
                        || "minecraft:strong_healing".equals(eid.toString()));
                // v1.5.252g8【情境药水不通用喝】：水肺/夜视/抗火/跳跃是"情境药水"
                // （溺水喝水肺、着火喝抗火由 drinkPotionWithEffect/着火分支专项喝；
                // 跳跃只在搭高/跑酷有用），被僵尸打时不该浪费——旧版把一切
                // BENEFICIAL 效果都当增益喝，实测"没溺水被僵尸打却在喝水肺"
                boolean isSituational = eid != null
                        && ("minecraft:water_breathing".equals(eid.toString())
                        || "minecraft:night_vision".equals(eid.toString())
                        || "minecraft:fire_resistance".equals(eid.toString())
                        || "minecraft:jump_boost".equals(eid.toString()));
                // v1.5.25d 修复：m_8093_() 基类恒返回 false（不是 isBeneficial）——
                // 旧判断永远 false → 增益药水全被跳过（"仍不喝再生/迅捷"的根因）。
                // 改用 category 判断：m_19483_() == MobEffectCategory.BENEFICIAL
                boolean isBeneficial = effect.m_19483_() == net.minecraft.world.effect.MobEffectCategory.BENEFICIAL;
                if (isBeneficial && !isHealing && !isSituational) {
                    beneficial = true;
                    break;
                }
            }
            if (!beneficial) {
                continue;
            }
            // 喝掉：从背包取 1 瓶，施加全部效果（原版喝药逻辑），返还空玻璃瓶
            inv.extractItem(i, 1, false);
            this.giveBottle(maid);
            // v1.5.252g9：自喝日志（latest.log 搜 "self drink"，排查浪费）
            LOGGER.info("self drink: potion={} hp={}%",
                    potionKey(stack),
                    String.format("%.0f", this.hpRatio(maid) * 100.0f));
            int maxDur = 0;
            for (net.minecraft.world.effect.MobEffectInstance ei : effects) {
                // 延长版/II级自带 duration/amplifier，用拷贝构造器原样施加
                maid.m_7292_(new net.minecraft.world.effect.MobEffectInstance(ei));
                maxDur = Math.max(maxDur, ei.m_19557_());
            }
            // v1.5.252g7【CD = 药水种类】= 最长效果时长（上限 10 分钟防呆）——
            // CD 内同种不再喝，其他种照喝；瞬间效果短 CD
            this.markPotionUsed(potionKey(stack), nowTick, maxDur > 0 ? maxDur : 40);
            return Math.max(40, Math.min(maxDur > 0 ? maxDur : 40, 12000));
        }
        return -1;
    }

    /** v1.5.21：头顶愤怒警示粒子（红色气雾，让主人一眼发现她在危险中） */
    private void spawnAlert(EntityMaid maid) {
        if (this.alertCooldown-- > 0) {
            return;
        }
        this.alertCooldown = alertCooldown();
        try {
            net.minecraft.core.particles.SimpleParticleType angry =
                    (net.minecraft.core.particles.SimpleParticleType) ForgeRegistries.PARTICLE_TYPES
                            .getValue(ResourceLocation.parse("minecraft:angry_villager"));
            if (angry != null && maid.m_9236_() instanceof ServerLevel level) {
                level.m_8767_(angry, maid.m_20185_(), maid.m_20186_() + 2.2, maid.m_20189_(),
                        4, 0.2, 0.3, 0.2, 0.0);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * v1.5.24：优先取背包中数量最多的【安全可搭方块】。
     * 旧版限定 8 种白名单（圆石/泥土/石头…）→ 背包没圆石就完全拿不到材料、不搭方块。
     * 现在动态扫描：任何 BlockItem 对应的实心完整方块（非下落/非 TNT/非伤害方块）
     * 都可用于搭高，数量最多的先取（不浪费稀有方块）。
     */
    private Block takeBuildBlock(EntityMaid maid) {
        // v1.1.0 实测七：选材统一走 MaidBuildBlockFilter——火把等无碰撞方块、
        // 可替换方块（草/雪片）一律不再入选（旧 isSafeBuildBlock 的本地逻辑
        // 并入工具类，本类保留壳调用）。返回 Block（自保内部用 Block 放置）。
        net.minecraft.world.item.Item item = com.maidsmart.tool.MaidBuildBlockFilter
                .takeBuildBlock(maid.getMaidInv(), maid.m_9236_(), maid.m_20183_());
        if (item == null) {
            return null;
        }
        return ForgeRegistries.BLOCKS.getValue(ForgeRegistries.ITEMS.getKey(item));
    }

    /**
     * v1.5.24：该方块能否用于搭高——实心完整碰撞形状、非下落（沙/砾石/铁砧）、
     * 非 TNT、非会伤害女仆的方块（仙人掌/岩浆块/甜浆果/营火）。
     * v1.1.0 实测七：此判定已并入公共工具类 MaidBuildBlockFilter（额外补了
     * 【无碰撞形状】与【REPLACEABLE tag】两道闸，火把/花/草/雪片全拦）——
     * 本方法保留委托调用（类内其他引用不改签名）。
     */
    private boolean isSafeBuildBlock(EntityMaid maid, Block block) {
        return com.maidsmart.tool.MaidBuildBlockFilter.isUsableBuildBlock(
                block, maid.m_9236_(), maid.m_20183_());
    }
}
