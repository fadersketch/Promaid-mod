package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;

/**
 * v1.5.189：自动投喂/治疗主人（被动技能，非工作状态）——core 行为，任何任务都运行。
 *
 * v1.5.206：投喂策略全面化——不再"仅投喂单一药水/物品"，按主人状态分优先级链
 * （每 3 秒检查一次，命中即止）：
 * 1. 血量 < 30%（aidHealthThreshold）：喷溅治疗药水 → 滞留治疗药水 → 金苹果/附魔
 *    金苹果（即时增益）→ 喷溅/滞留再生药水 → 普通治疗药水塞主人背包（提示快喝）→
 *    喂高饱食物（让主人自然回血）；
 * 2. 着火：喷溅/滞留抗火药水 → 普通抗火塞背包；
 * 3. 负面效果（中毒/凋零/缓慢/虚弱/失明/饥饿/反胃/黑暗/漂浮）：牛奶桶塞背包（全解）
 *    → 蜂蜜瓶（解中毒 + 回饱食）；
 * 4. 饱食度 < 12（aidFoodThreshold）：按饱和度恢复量喂最优食物（EmotionalActionExecutor.
 *    FOODS 全清单 21 种：金胡萝卜 > 熟牛排/猪排 > 兔肉煲 > …；主人背包满自动退回）。
 *
 * 药水识别统一用药水注册名（ForgeRegistries.POTIONS，1.20.1 实证：治疗 =
 * healing/strong_healing、再生 = regeneration/long_regeneration/strong_regeneration、
 * 抗火 = fire_resistance/long_fire_resistance）——不再用 MobEffect 字段名比对
 * （v1.5.205 修过 f_19616_ 不是 instant_health 的坑）。
 * 总开关：combat.aidOwnerEnable（默认开）+ 两个阈值可调。
 * v1.1.0 实测二百四十二（用户："女仆互助之间会传递喝的药水吗？不会的话改一下。
 * 同时女仆战斗时也应该可以传递食物"）：互助链补两条——① 饮用型药水直接喂姐妹
 * （finishUsingItem 强制饮用，空瓶返还；旧版只投喷溅/滞留，普通药水从不传递）；
 * ② 姐妹正在战斗（脑内有攻击目标）且血不满时喂食（战斗续航，低血链已喂则跳过）。
 */
public class MaidAidOwnerBehavior extends Behavior<EntityMaid> {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** v1.5.252g12：投掷间隔【按女仆记】——旧版 aidCooldown 是共享 int：多个女仆
     *  tick 同一个行为实例，远处的女仆也会无条件递减它（60 tick 被 2 个女仆一起
     *  减 → 实际 1.5 秒），女仆越多投掷越频繁 */
    private final java.util.Map<java.util.UUID, Integer> aidCds = new java.util.HashMap<>();

    private void setAidCooldown(java.util.UUID id, int ticks) {
        this.aidCds.entrySet().removeIf(e -> e.getValue() <= 0); // 过期清理
        if (ticks <= 0) {
            this.aidCds.remove(id);
        } else {
            this.aidCds.put(id, ticks);
        }
    }

    /** v1.5.252g13【效果判定是情境药水的正门】：主人身上是否已有该效果——
     *  只要有效果（哪怕剩余 1 秒）就不投，任何变体都不需要：用户规则
     *  "一旦主人身上有这种效果，变体药水就都不需要投了"。效果消失瞬间
     *  立即重投（每 tick 检查，无空档）。不再依赖 CD 记时间。 */
    private static boolean hasEffectOn(net.minecraft.world.entity.LivingEntity target, String effectId) {
        try {
            for (net.minecraft.world.effect.MobEffectInstance eff : target.m_21220_()) {
                net.minecraft.resources.ResourceLocation key =
                        net.minecraftforge.registries.ForgeRegistries.MOB_EFFECTS.getKey(eff.m_19544_());
                if (key != null && effectId.equals(key.toString())) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }
    /** v1.5.252g7【投喂药水 CD】——按【女仆 + 药水种类】记冷却（key = maidUUID +
     *  "|" + potionKey，如 uuid|minecraft:long_swiftness）：CD = 该药水最长效果时长，
     *  期间同种不再投，**其他种照投**（投了力量、迅捷还能投）；瞬间治疗短 CD（60 tick）。
     *  aidCooldown（3 秒）只是【投掷间隔】，与种类 CD 相互独立。
     *  v1.1.0 实测十六（审查 P2）：旧版 key 只有 potionKey（按效果种类），被多个女仆
     *  共享 tick 时跨女仆互占——A 女仆给主人投了治疗，B 女仆的治疗链跟着进 CD；投给
     *  姐妹的治疗也占用主人的 CD。改为 (maidUUID, potionKey) 二元 key，每个女仆独立。 */
    private final java.util.Map<String, Long> potionCds = new java.util.HashMap<>();

    private static String potionCdKey(java.util.UUID maidId, String pkey) {
        return maidId.toString() + "|" + pkey;
    }

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
        this.potionCds.put(key, now + Math.max(60, cd));
    }

    /** 药水【效果种类】key（如 minecraft:swiftness）——"同一种药水" CD 的 key。
     *  v1.5.252g9：long_/strong_ 前缀与形态（饮用/喷溅/滞留）不计——同效果不同
     *  变体视为同种（否则普通/延长/喷溅/滞留抗火各是一个 key，跳岩浆会把每种
     *  变体各投一瓶，实测"多扔了不少药水"） */
    private static String potionKey(ItemStack stack) {
        try {
            net.minecraft.world.item.alchemy.Potion potion =
                    net.minecraft.world.item.alchemy.PotionUtils.m_43579_(stack);
            if (potion == null) {
                return "unknown";
            }
            net.minecraft.resources.ResourceLocation key =
                    net.minecraftforge.registries.ForgeRegistries.POTIONS.getKey(potion);
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
    /** v1.5.227：canUse 首调诊断标记（只打第一条） */
    private boolean canUseLogged = false;

    /** 治疗药水（Potion 注册名）——喷溅/滞留投掷、普通饮用塞背包 */
    private static final java.util.Set<String> HEAL_POTIONS = java.util.Set.of(
            "minecraft:healing", "minecraft:strong_healing");
    /** 再生药水（持续回血，低血兜底） */
    private static final java.util.Set<String> REGEN_POTIONS = java.util.Set.of(
            "minecraft:regeneration", "minecraft:long_regeneration", "minecraft:strong_regeneration");
    /** 抗火药水（着火时） */
    private static final java.util.Set<String> FIRE_RESIST_POTIONS = java.util.Set.of(
            "minecraft:fire_resistance", "minecraft:long_fire_resistance");
    /** v1.5.252g：水肺药水（主人溺水时） */
    private static final java.util.Set<String> WATER_BREATHING_POTIONS = java.util.Set.of(
            "minecraft:water_breathing", "minecraft:long_water_breathing");
    /** v1.5.252g：增益药水（迅捷/力量——主人附近有威胁时助战） */
    private static final java.util.Set<String> BUFF_POTIONS = java.util.Set.of(
            "minecraft:swiftness", "minecraft:long_swiftness", "minecraft:strong_swiftness",
            "minecraft:strength", "minecraft:long_strength", "minecraft:strong_strength");
    /** 需要解除的负面效果（牛奶全解；蜂蜜只解中毒但回饱食） */
    private static final java.util.Set<String> NEGATIVE_EFFECTS = java.util.Set.of(
            "minecraft:poison", "minecraft:wither", "minecraft:slowness", "minecraft:weakness",
            "minecraft:blindness", "minecraft:hunger", "minecraft:nausea", "minecraft:darkness",
            "minecraft:levitation");

    public MaidAidOwnerBehavior() {
        super(java.util.Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
        // v1.5.227 诊断：行为构造 = 类被加载 + 实例被创建（女仆 Brain 构建时调用）
        LOGGER.info("aid-owner constructed");
    }

    @Override
    protected boolean m_6114_(ServerLevel level, EntityMaid maid) {
        // v1.5.227 诊断：canUse 被 Brain 调用过一次后不再刷（只打第一条）
        if (!this.canUseLogged) {
            this.canUseLogged = true;
            LOGGER.info("aid-owner canUse first-call: enabled={}",
                    com.maidsmart.config.MaidSmartConfig.AID_OWNER_ENABLE.get());
        }
        return com.maidsmart.config.MaidSmartConfig.AID_OWNER_ENABLE.get();
    }

    /**
     * v1.5.228【重大修复】：canStillUse 必须重写为 true——原版 1.20.1 Behavior 的
     * canStillUse 默认返回【false】！行为 tryStart 后下一个 tick 就 tickOrStop →
     * canStillUse false → 立即 stop，tick() 永远不会执行。投喂行为从 v1.5.189 诞生
     * 起就一直没 tick 过（WaterClutch/SelfPreservation 都重写了所以正常）。
     */
    @Override
    protected boolean m_6737_(ServerLevel level, EntityMaid maid, long gameTime) {
        return true;
    }

    @Override
    protected void m_6725_(ServerLevel level, EntityMaid maid, long gameTime) {
        java.util.UUID maidId = maid.m_20148_();
        Integer c = this.aidCds.get(maidId);
        if (c != null && c > 0) {
            this.aidCds.put(maidId, c - 1); // v1.5.252g12：只减自己女仆的投掷间隔
            return;
        }
        if (!(maid.m_269323_() instanceof ServerPlayer owner)) {
            return;
        }
        if (!owner.m_6084_()) {
            return;
        }
        // 主人不在身边（> 16 格）不管——贴身辅助。
        // v1.1.0 实测六：主人不在身边时【女仆互助仍要运作】（两女仆外出战斗、
        // 主人在家的情况）——互助检查挪到主人距离判定之前，主人链照旧只在 16 格内。
        boolean ownerNear = !(maid.m_20238_(owner.m_20182_()) > 16.0);
        if (!ownerNear) {
            this.aidMaidSisters(level, maid, gameTime);
            return;
        }
        boolean aided = false;
        double hpRatio = owner.m_21223_() / Math.max(1.0f, owner.m_21233_());
        // v1.1.0 实测十六（审查 P1-4）：本轮【任意实质动作】成功后统一收尾记
        // 投喂 CD（60 tick = 3 秒）——旧版只有增益链一处 setAidCooldown，治疗链的
        // 金苹果/喂食 fallback 完全不设防：主人低血 + 治疗药水在种类 CD 内时
        // 每 tick fallback 金苹果 → 几十个金苹果几秒蒸发（喂食同理每 tick 喂一个）。
        // try/finally 保证早退路径也记账。
        // v1.5.252g11【情境保命优先】：着火抗火、溺水水肺排到低血治疗前面——
        // 溺水掉血后 hp 跌到阈值 → 旧版治疗链先抢到（aided=true）→ 水肺分支
        // 被短路跳过，溺水的人只被扔治疗（救不了溺水，纯浪费）；着火同理。
        // 情境药水 CD 内（8 分钟）不重复投，主人在情境中时不需要治疗凑热闹。
        // 1. 着火 → 抗火药水（v1.5.252g13：正门 = 主人身上无抗火效果才投——
        //    效果在就绝不投，任何变体都不需要；效果消失立即重投，无空档）
        if (owner.m_6060_() && !this.hasEffectOn(owner, "minecraft:fire_resistance")) {
            if (this.throwPotionToOwner(level, maid, owner, FIRE_RESIST_POTIONS, "抗火", false) >= 0
                    || this.giveDrinkablePotion(maid, owner, FIRE_RESIST_POTIONS, "抗火", false) >= 0) {
                aided = true;
            }
        }
        // 2. 主人溺水（泡水且氧气不足）→ 水肺药水（v1.5.252g13：正门 = 主人
        //    无效果才投）
        if (!aided && owner.m_20069_() && owner.m_20146_() < 60
                && !this.hasEffectOn(owner, "minecraft:water_breathing")) {
            if (this.throwPotionToOwner(level, maid, owner, WATER_BREATHING_POTIONS, "水肺", false) >= 0
                    || this.giveDrinkablePotion(maid, owner, WATER_BREATHING_POTIONS, "水肺", false) >= 0) {
                aided = true;
            }
        }
        // 3. 负面效果 → 牛奶（全解）/ 蜂蜜瓶（解中毒 + 回饱食）——v1.5.288：
        //    改为【直接喂】（旧版塞主人背包/手上，用户："投喂应该直接喂给主人"）
        //    v1.5.290：提前到治疗分支【之前】——旧版在治疗(3)之后：主人中毒+低血时
        //    治疗先处理 → 负面解除被短路 → 蜂蜜/牛奶永远不喂（用户："还是不会喂蜂蜜"）。
        //    先解毒再治疗更合理（中毒持续掉血，先断源头）
        if (!aided && this.hasNegativeEffect(owner)) {
            if (this.feedMilkOrHoneyDirect(maid, owner)) {
                aided = true;
            }
        }
        // 4. 血量低 → 治疗链（v1.5.206：形态/物品按效果强度排，不再单一药水）
        //    v1.5.252g15【治疗链只看血量】："环境优先"由分支顺序保证（g11：
        //    着火/溺水分支在本分支之前——环境处理成功则 aided 短路跳过治疗；
        //    环境没处理成功（没药水/CD）治疗兜底保命）。旧版 g13/g14 额外加
        //    "情境跳过"条件（身上有抗火/水肺效果就不治），实测：抗火效果残留
        //    2 分钟期间低血竟不治疗（"低血跑到女仆面前不享受治疗"）——删掉
        if (!aided && hpRatio < com.maidsmart.config.MaidSmartConfig.AID_HEALTH_THRESHOLD.get()) {
            // v1.5.252g7：种类 CD 已内化到 throw/give（同种 CD 内自动跳过），
            // 调用处只判成败；aidCooldown 3 秒仍是投掷间隔
            if (this.throwPotionToOwner(level, maid, owner, HEAL_POTIONS, "治疗", true) >= 0) {
                aided = true;
            }
            if (!aided && this.useGoldenApple(maid, owner)) {
                aided = true; // 金苹果：吸收+再生即时生效，比再生药水更顶用
            }
            if (!aided && this.throwPotionToOwner(level, maid, owner, REGEN_POTIONS, "再生", true) >= 0) {
                aided = true;
            }
            if (!aided && this.giveDrinkablePotion(maid, owner, HEAL_POTIONS, "治疗", true) >= 0) {
                aided = true; // 普通治疗药水塞主人背包（主人自己喝）
            }
            if (!aided && this.feedHealingFood(maid, owner)) {
                aided = true; // 高饱食物 → 主人自然回血
            }
        }
        // 5. 饱食度低 → 喂最优食物（全清单按饱和度排序，v1.5.201 起）
        //    v1.5.290：不再依赖 !aided——旧版：负面解除(3)喂牛奶成功（清效果但不加
        //    饱食）→ aided=true → 饱食分支被短路 → 饿了女仆不喂（用户："饿了喂
        //    蜂蜜而女仆没触发"）。饱食投喂独立判定：即使本轮已做其他动作，饱食仍低
        //    就继续喂（蜂蜜/食物都能直接加饱食，连续动作合理）
        if (owner.m_36324_().m_38702_() < com.maidsmart.config.MaidSmartConfig.AID_FOOD_THRESHOLD.get()) {
            if (com.maidsmart.action.EmotionalActionExecutor.giveFoodToOwner(maid, owner)) {
                maid.getChatBubbleManager().addTextChatBubble("主人饿了吧，给你带了吃的～");
                aided = true;
            }
        }
        // 6. 主人附近有威胁且没有力量/迅捷增益 → 增益药水助战（v1.5.252g7：
        this.ownerBuffChain(level, maid, owner, aided, maidId);

        // v1.1.0 实测十六（审查 P1-4）：本轮做过任何实质投喂 → 3 秒内不再进入
        // 本 tick 主链（含金苹果/喂食 fallback——它们没有自己的种类 CD）
        if (aided) {
            this.setAidCooldown(maidId, 60);
        }

        // ================= v1.1.0 实测六：女仆互助（战斗支援） =================
        // 同主人、16 格内的其他女仆低血/着火时也投药水救她（与救主人同款追踪弹）。
        // 每轮最多救一个（防一次检查全场连扔）；自己的保命药水不够时不勉强
        //（治疗药水只剩 1 瓶且自己血也不健康时留着——简单起见不做复杂判断：
        // 有富余就帮，实战中女仆背包通常由玩家统一配药）。
        // v1.1.0 实测十六（审查 P1-4）：互助链加独立 CD 记账（sisterAidCds 按女仆）——
        // 旧版互助链在 tick 里被调用两次（主人不在分支 + 主链尾部）且无任何节流，
        // N 只女仆互邻时每 tick O(N²) 全量扫描 + 金苹果/喂食同样每 tick 连发。
        this.aidMaidSisters(level, maid, gameTime);
    }

    /** v1.1.0 实测六：给附近受伤/着火的姐妹女仆丢药水（治疗/再生；着火先抗火）
     *  v1.1.0 实测八（用户："把套给主人的支援方案全复刻一部分给女仆，主人更高优先级"）：
     *  互助链对齐主人链——着火抗火 → 负面效果（牛奶/蜂蜜）→ 低血（治疗/金苹果/
     *  再生/喂食）。主人链永远先跑完（tick 里顺序固定），同 tick 主人有需求时
     *  互助轮空。 */
    private void aidMaidSisters(ServerLevel level, EntityMaid maid, long gameTime) {
        try {
            // v1.1.0 实测十六（审查 P1-4）：互助链独立 CD（3 秒）——旧版无任何节流：
            // 本方法在 tick 里被调用两次（主人不在分支 + 主链尾部），每 tick 全量扫描
            // 16 格内姐妹（N 只互邻时 O(N²)/tick），金苹果/喂食 fallback 同样每 tick
            // 连发清空背包。CD 与主人链共用 setAidCooldown（同一个女仆同一份间隔）。
            java.util.UUID maidId = maid.m_20148_();
            Integer c = this.aidCds.get(maidId);
            if (c != null && c > 0) {
                return; // 主链已设的投喂间隔内互助也让位（本 tick 主链 return 时 aidCds
                // 不递减——互助链不消耗间隔计数，纯让位判定）
            }
            // v1.1.0 实测一百二十六（用户："女仆没有可支援道具却会说话，系统提示喂了
            // 空气"）：白名单预检——背包+双手连一样能用的支援物品（药水/金苹果/牛奶/
            // 蜂蜜/FOODS 食物）都没有就整轮跳过：不说话、不扫描、不消耗。旧版依赖各
            // 分支返回 null，但手上的【活引用】会被同 tick 其他系统（隐藏物品槽/任务
            // 换手）清空或替换，feedSisterFood 会把空栈当"喂了空气"播报。预检是硬门槛：
            // 没道具 = 不支援 = 静默。
            if (!this.hasAidItemAtAll(maid)) {
                return;
            }
            for (EntityMaid sister : level.m_45976_(EntityMaid.class,
                    maid.m_20191_().m_82400_(16.0))) {
                if (sister == maid || !sister.m_6084_() || sister.m_269323_() != maid.m_269323_()) {
                    continue; // 自己/死了/不是同主人的女仆
                }
                double hpRatio2 = sister.m_21223_() / Math.max(1.0f, sister.m_21233_());
                String action = null; // v1.1.0 实测三十二：本轮支援的具体动作描述（系统字幕用）
                // 1. 着火 → 抗火药水（与主人链同顺序：情境保命最先）
                if (sister.m_6060_() && !this.hasEffectOn(sister, "minecraft:fire_resistance")) {
                    if (this.throwPotionTo(level, maid, sister, FIRE_RESIST_POTIONS, "抗火", false) >= 0) {
                        action = "扔了抗火药水";
                    }
                }
                // 2. 负面效果 → 牛奶（全解；有增益时改蜂蜜，同主人链规则）
                if (action == null && this.hasNegativeEffectOn(sister)) {
                    action = this.feedSisterMilkOrHoney(maid, sister);
                }
                // 3. 低血 → 治疗链（喷溅治疗 → 金苹果 → 再生 → 喂食），同主人链顺序
                if (action == null && hpRatio2 < com.maidsmart.config.MaidSmartConfig.AID_HEALTH_THRESHOLD.get()) {
                    if (this.throwPotionTo(level, maid, sister, HEAL_POTIONS, "治疗", true) >= 0) {
                        action = "扔了治疗药水";
                    }
                }
                if (action == null && hpRatio2 < com.maidsmart.config.MaidSmartConfig.AID_HEALTH_THRESHOLD.get()) {
                    action = this.useGoldenAppleOn(maid, sister);
                }
                if (action == null && hpRatio2 < com.maidsmart.config.MaidSmartConfig.AID_HEALTH_THRESHOLD.get()) {
                    if (this.throwPotionTo(level, maid, sister, REGEN_POTIONS, "再生", true) >= 0) {
                        action = "扔了再生药水";
                    }
                }
                // v1.1.0 实测二百四十二（用户："女仆互助之间会传递喝的药水吗？不会的话
                // 改一下"）：低血链补【饮用型药水直接喂】——喷溅/滞留扔完、金苹果/再生
                // 都没有时，普通治疗药水直接喂给姐妹（强制饮用，效果即时生效）。
                // 与主人链的"塞背包"不同：姐妹没有背包 UI，直接喂才是"传递"。
                if (action == null && hpRatio2 < com.maidsmart.config.MaidSmartConfig.AID_HEALTH_THRESHOLD.get()) {
                    action = this.feedDrinkablePotionTo(maid, sister, HEAL_POTIONS, "治疗", true);
                }
                if (action == null && hpRatio2 < com.maidsmart.config.MaidSmartConfig.AID_HEALTH_THRESHOLD.get()) {
                    action = this.feedSisterFood(maid, sister);
                }
                // v1.1.0 实测二百四十二（用户："女仆战斗时也应该可以传递食物"）：
                // 战斗支援——姐妹正在战斗（脑内有攻击目标）且血不满时喂食（回血+饱食，
                // 战斗续航）。低血链已喂过则跳过（action != null 短路）；血满但战斗中
                // 也喂（战斗消耗大，提前补）。
                if (action == null && sisterFighting(sister)
                        && sister.m_21223_() < sister.m_21233_()) {
                    action = this.feedSisterFood(maid, sister);
                }
                if (action != null) {
                    // v1.1.0 实测三十二（用户：消息不再固定"药水来了"——牛奶/蜂蜜/食物
                    // 也说药水不真实）：气泡改通用文案 + 绿色系统字幕记录具体支援
                    // 内容（与喂主人 [maid_smart] 绿色字幕同款显示方式）
                    maid.getChatBubbleManager().addTextChatBubble("姐妹挺住，我来帮你！");
                    String sisterName = sister.m_5446_() != null
                            ? sister.m_5446_().getString() : "姐妹女仆";
                    String maidName = maid.m_5446_() != null
                            ? maid.m_5446_().getString() : "女仆";
                    for (net.minecraft.server.level.ServerPlayer viewer :
                            level.m_6907_()) {
                        viewer.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                                "\u00a7a[maid_smart] " + maidName + " 支援了 " + sisterName
                                        + "：" + action));
                    }
                    this.setAidCooldown(maidId, 60); // 互助成功同样 3 秒间隔
                    return; // 每轮最多救一个
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** v1.1.0 实测八：姐妹身上是否有需要解除的负面效果（清单同主人链） */
    private static boolean hasNegativeEffectOn(net.minecraft.world.entity.LivingEntity target) {
        try {
            for (net.minecraft.world.effect.MobEffectInstance eff : target.m_21220_()) {
                net.minecraft.resources.ResourceLocation key =
                        net.minecraftforge.registries.ForgeRegistries.MOB_EFFECTS.getKey(eff.m_19544_());
                if (key != null && NEGATIVE_EFFECTS.contains(key.toString())) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * v1.1.0 实测八：喂姐妹牛奶/蜂蜜——牛奶直接 clearAllEffects（女仆没有
     * "不想被清增益"的玩家心智，有增益也照喂——战场实用主义：负面掉血比增益
     * 更致命），空桶返还；没有牛奶退蜂蜜（只解中毒，但蜂蜜走喂食回饱食）。
     * v1.1.0 实测三十二：返回值从 boolean 改 String——null=没喂成，非 null=
     * 具体动作描述（系统字幕"女仆支援了姐妹：喂了牛奶"用）。
     */
    private String feedSisterMilkOrHoney(EntityMaid maid, EntityMaid sister) {
        try {
            net.minecraftforge.items.IItemHandler inv = maid.getMaidInv();
            net.minecraft.world.item.Item milk = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                    net.minecraft.resources.ResourceLocation.parse("minecraft:milk_bucket"));
            if (milk != null) {
                for (int i = 0; i < inv.getSlots(); i++) {
                    ItemStack stack = inv.getStackInSlot(i);
                    if (stack.m_41619_() || stack.m_41720_() != milk) {
                        continue;
                    }
                    inv.extractItem(i, 1, false);
                    sister.m_21219_(); // removeAllEffects（全解）
                    // 空桶返还喂食者背包
                    net.minecraft.world.item.Item bucket = net.minecraftforge.registries.ForgeRegistries.ITEMS
                            .getValue(net.minecraft.resources.ResourceLocation.parse("minecraft:bucket"));
                    if (bucket != null) {
                        net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(
                                inv, new ItemStack(bucket), false);
                    }
                    maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
                    // v1.1.0 实测三十二：喝牛奶音效（旧版静默——手搓效果路径没有
                    // 任何反馈，用户："没有音效这一点很难受"）
                    playSoundAt(sister, "minecraft:entity.generic.drink");
                    return "喂了牛奶（负面全解）";
                }
            }
                // 蜂蜜：解中毒（m_21195_ removeEffect）+ 真实进食回饱食
                net.minecraft.world.item.Item honey = net.minecraft.world.item.Items.f_42787_;
                for (int i = 0; i < inv.getSlots(); i++) {
                    ItemStack stack = inv.getStackInSlot(i);
                    if (stack.m_41619_() || stack.m_41720_() != honey) {
                        continue;
                    }
                    ItemStack taken = inv.extractItem(i, 1, false);
                    if (taken.m_41619_()) {
                        continue;
                    }
                    // m_5584_ = LivingEntity.eat(Level, ItemStack)——真实进食
                    //（TLM 女仆进食同款入口：食物效果+音效+粒子，比手搓 FoodData 通用）
                    sister.m_5584_(sister.m_9236_(), taken);
                    // v1.1.0 实测三十四修复（用户："喂其他女仆蜂蜜没有效果，解不了
                    // 中毒；对主人的路径仍然生效"）：eat() 只加饱食/食物效果——
                    // 原版"喝蜂蜜解中毒"发生在 HoneyBottleItem.finishUsingItem
                    //（玩家喝完才触发），eat() 不经过它 → 姐妹中毒不解。主人链
                    // feedFoodDirect 里是手搓的 m_21195_(poison) 才生效的。这里
                    // 补同款解中毒（m_21195_ = removeEffect）。
                    sister.m_21195_(net.minecraft.world.effect.MobEffects.f_19614_);
                    // v1.1.0 实测三十二：喝蜂蜜音效（eat() 对非玩家实体不出声）
                    playSoundAt(sister, "minecraft:entity.generic.drink");
                    // v1.1.0 实测十六（审查 P3）：蜂蜜玻璃瓶返还喂食者背包——
                    // 旧版吃完蜂蜜不返还瓶子（主人链 feedFoodDirect 有返还逻辑，
                    // 姐妹链漏了），物品凭空消失
                    net.minecraft.world.item.Item bottle = net.minecraftforge.registries.ForgeRegistries.ITEMS
                            .getValue(net.minecraft.resources.ResourceLocation.parse("minecraft:glass_bottle"));
                    if (bottle != null) {
                        net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(
                                inv, new ItemStack(bottle), false);
                    }
                    maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
                    return "喂了蜂蜜瓶（解中毒+回饱食）";
                }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** v1.1.0 实测三十二：在目标位置播通用音效（姐妹链喝牛奶/吃金苹果等手搓
     *  效果路径的补声——只对服务端播，客户端按距离衰减正常听到） */
    private static void playSoundAt(net.minecraft.world.entity.Entity at, String soundId) {
        try {
            net.minecraft.sounds.SoundEvent snd = net.minecraftforge.registries.ForgeRegistries.SOUND_EVENTS
                    .getValue(net.minecraft.resources.ResourceLocation.parse(soundId));
            if (snd != null) {
                at.m_9236_().m_5594_(null, at.m_20183_(), snd,
                        net.minecraft.sounds.SoundSource.NEUTRAL, 1.0f, 1.0f);
            }
        } catch (Exception ignored) {
        }
    }

    /** v1.1.0 实测八：金苹果给姐妹（附魔优先；效果同 useGoldenApple，目标换人）
     *  v1.1.0 实测三十二：返回值 boolean → String（动作描述），补吃苹果音效 */
    private String useGoldenAppleOn(EntityMaid maid, EntityMaid sister) {
        try {
            net.minecraftforge.items.IItemHandler inv = maid.getMaidInv();
            int bestSlot = -1;
            boolean enchanted = false;
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (stack.m_41619_()) {
                    continue;
                }
                net.minecraft.world.item.Item item = stack.m_41720_();
                if (item == net.minecraft.world.item.Items.f_42437_) {
                    bestSlot = i;
                    enchanted = true;
                    break;
                }
                if (item == net.minecraft.world.item.Items.f_42436_ && bestSlot < 0) {
                    bestSlot = i;
                }
            }
            if (bestSlot < 0) {
                return null;
            }
            inv.extractItem(bestSlot, 1, false);
            applyEffect(sister, "minecraft:absorption", 2400, enchanted ? 3 : 0);
            applyEffect(sister, "minecraft:regeneration", enchanted ? 600 : 100, enchanted ? 4 : 1);
            if (enchanted) {
                applyEffect(sister, "minecraft:damage_resistance", 6000, 0);
                applyEffect(sister, "minecraft:fire_resistance", 6000, 0);
            }
            maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
            // v1.1.0 实测三十二：吃金苹果音效（旧版手搓效果路径无声）
            playSoundAt(sister, "minecraft:entity.generic.eat");
            return enchanted ? "喂了附魔金苹果" : "喂了金苹果";
        } catch (Exception ignored) {
            return null;
        }
    }

    /** v1.1.0 实测八：喂姐妹治疗食物（按饱和度择优，手持也认——同主人链）
     *  v1.1.0 实测三十二：返回值 boolean → String（动作描述，系统字幕用） */
    private String feedSisterFood(EntityMaid maid, EntityMaid sister) {
        try {
            net.minecraftforge.items.IItemHandler inv = maid.getMaidInv();
            int bestSlot = -1;
            double bestSat = -1.0;
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (stack.m_41619_()) {
                    continue;
                }
                boolean isFood = false;
                for (ItemStack food : com.maidsmart.action.EmotionalActionExecutor.FOODS) {
                    if (food.m_41720_() == stack.m_41720_()) {
                        isFood = true;
                        break;
                    }
                }
                if (!isFood) {
                    continue;
                }
                double sat = com.maidsmart.action.EmotionalActionExecutor.foodSaturation(stack, sister);
                if (sat > bestSat) {
                    bestSat = sat;
                    bestSlot = i;
                }
            }
            // 手持食物（金苹果已在前面分支处理；这里拿普通食物）
            ItemStack handItem = null;
            double handSat = -1.0;
            int handIdx = -1;
            for (int h = 0; h < 2; h++) {
                ItemStack hs = h == 0 ? maid.m_21205_() : maid.m_21206_();
                if (hs.m_41619_()) {
                    continue;
                }
                boolean isFood = false;
                for (ItemStack food : com.maidsmart.action.EmotionalActionExecutor.FOODS) {
                    if (food.m_41720_() == hs.m_41720_()) {
                        isFood = true;
                        break;
                    }
                }
                if (!isFood) {
                    continue;
                }
                double sat = com.maidsmart.action.EmotionalActionExecutor.foodSaturation(hs, sister);
                if (sat > handSat) {
                    handSat = sat;
                    // v1.1.0 实测一百二十六：存【快照】而非活引用——m_21205_/m_21206_
                    // 返回的是手上真实栈对象，同 tick 其他系统（隐藏物品槽/任务换手）
                    // 会原地清空它；活引用到消耗时已空 → 空栈当食物喂 → 系统播报
                    // "喂了空气"。快照在扫描时刻固定内容，消耗时再核对当前手。
                    handItem = hs.m_41777_();
                    handIdx = h;
                }
            }
            if (bestSlot < 0 && handItem == null) {
                return null;
            }
            ItemStack toGive;
            String foodName;
            if (handItem != null && handSat >= bestSat) {
                toGive = handItem;
                if (toGive.m_41619_()) {
                    return null; // 扫描后手上已被清空——不支援、不播报、不消耗
                }
                // 名字在进食前读（m_5584_ = eat 会 shrink 掉栈内数量，读晚了拿不到）
                foodName = toGive.m_41786_().getString();
                // 消耗前核对：当前手上还是同种食物才 shrink（防同 tick 手被换走，
                // 误把新物品当成旧食物吃掉）
                ItemStack liveNow = handIdx == 0 ? maid.m_21205_() : maid.m_21206_();
                if (liveNow.m_41619_() || !liveNow.m_150930_(toGive.m_41720_())) {
                    return null;
                }
                liveNow.m_41774_(1);
            } else {
                toGive = inv.extractItem(bestSlot, 1, false);
                if (toGive.m_41619_()) {
                    return null;
                }
                foodName = toGive.m_41786_().getString();
            }
            // 真实进食（m_5584_ = eat(Level, ItemStack)）：食物效果/音效/粒子全生效
            sister.m_5584_(sister.m_9236_(), toGive);
            maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
            return "喂了" + foodName;
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * v1.1.0 实测二百四十二（用户："女仆互助之间会传递喝的药水吗？不会的话改一下"）：
     * 普通（饮用型）药水【直接喂给姐妹】——旧版互助链只投喷溅/滞留型（throwPotionTo
     * 只认 Splash/Lingering），饮用型药水从不传递（主人链有塞背包路径，姐妹链完全没有）。
     * 强制饮用：item.m_5922_（finishUsingItem）对姐妹实体生效——治疗/抗火等效果
     * 直接加上，空玻璃瓶返还喂食者背包。useCd=true 时走同种药水 CD（与投掷共用
     * potionKey 种类 CD，防喷溅刚扔完又喂一瓶）。
     */
    private String feedDrinkablePotionTo(EntityMaid maid, EntityMaid sister,
                                         java.util.Set<String> potionNames, String label, boolean useCd) {
        try {
            net.minecraftforge.items.IItemHandler inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (stack.m_41619_() || !(stack.m_41720_() instanceof net.minecraft.world.item.PotionItem)) {
                    continue;
                }
                if (!this.isPotionOf(stack, potionNames)) {
                    continue;
                }
                if (useCd && !this.potionReady(
                        potionCdKey(maid.m_20148_(), potionKey(stack)), maid.m_9236_().m_46467_())) {
                    continue;
                }
                ItemStack taken = inv.extractItem(i, 1, false);
                if (taken.m_41619_()) {
                    continue;
                }
                // 强制饮用：finishUsingItem 对姐妹生效（效果直接加上）
                ItemStack result = taken.m_41720_().m_5922_(taken, sister.m_9236_(), sister);
                // 空玻璃瓶返还喂食者背包
                if (!result.m_41619_()) {
                    net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(inv, result, false);
                }
                if (useCd) {
                    int maxDur = 0;
                    for (net.minecraft.world.effect.MobEffectInstance e :
                            net.minecraft.world.item.alchemy.PotionUtils.m_43571_(taken)) {
                        maxDur = Math.max(maxDur, e.m_19557_());
                    }
                    this.markPotionUsed(
                            potionCdKey(maid.m_20148_(), potionKey(taken)),
                            maid.m_9236_().m_46467_(), maxDur > 0 ? maxDur : 60);
                }
                maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
                playSoundAt(sister, "minecraft:entity.generic.drink");
                return "喂了" + label + "药水";
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** v1.1.0 实测二百四十二：姐妹是否正在战斗（脑内有攻击目标记忆）——战斗时喂食支援 */
    private static boolean sisterFighting(EntityMaid sister) {
        try {
            return sister.m_6274_().m_21952_(MemoryModuleType.f_26372_).isPresent();
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * v1.1.0 实测六：投掷药水给【任意 LivingEntity 目标】（女仆互助用）——
     * 与 throwPotionToOwner 同款择优+追踪弹，只是目标从主人换成姐妹女仆。
     */
    private int throwPotionTo(ServerLevel level, EntityMaid maid,
                              net.minecraft.world.entity.LivingEntity target,
                              java.util.Set<String> potionNames, String label, boolean useCd) {
        try {
            net.minecraftforge.items.IItemHandler inv = maid.getMaidInv();
            int bestSlot = -1;
            int bestScore = -1;
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (stack.m_41619_() || !(stack.m_41720_() instanceof net.minecraft.world.item.SplashPotionItem
                        || stack.m_41720_() instanceof net.minecraft.world.item.LingeringPotionItem)) {
                    continue;
                }
                if (!this.isPotionOf(stack, potionNames)) {
                    continue;
                }
                if (useCd && !this.potionReady(
                        potionCdKey(maid.m_20148_(), potionKey(stack)), level.m_46467_())) {
                    continue;
                }
                int score = potionStrength(stack);
                if (score > bestScore) {
                    bestScore = score;
                    bestSlot = i;
                }
            }
            if (bestSlot < 0) {
                return -1;
            }
            ItemStack stack = inv.getStackInSlot(bestSlot);
            ItemStack potionStack = stack.m_41777_(); // 快照（extract 前复制，防槽位变动）
            // v1.1.0 实测二百五十（用户："干脆就省去那些繁文缛节吧。只要触发扔药水
            // 这个事件，那就给予效果，滞留型则是在目标旁边生成对应的药水效果。然后
            // 会有一个药水抛出的动画就可以了"）：不再投掷实体——触发即生效。
            // ① 消耗药水 + 摆臂动画（抛药水动作）；② 滞留型（LingeringPotionItem）
            // → 目标旁边生成 AreaEffectCloud（半径 3，setPotion + addEffect，持续到
            // 效果结束）；③ 其他形态（喷溅/普通）→ 直接给目标施加所有效果。
            inv.extractItem(bestSlot, 1, false);
            maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
            // v1.1.0 实测二百五十一：抛药水动画（纯观感）——空药水抛物线飞向目标
            this.throwPotionAnimate(level, maid, target);
            int maxDur = 0;
            boolean lingering = potionStack.m_41720_() instanceof net.minecraft.world.item.LingeringPotionItem;
            if (lingering) {
                net.minecraft.world.entity.AreaEffectCloud cloud =
                        new net.minecraft.world.entity.AreaEffectCloud(level,
                                target.m_20185_(), target.m_20186_(), target.m_20189_());
                cloud.m_19712_(3.0f); // setRadius
                cloud.m_19722_(net.minecraft.world.item.alchemy.PotionUtils
                        .m_43579_(potionStack)); // setPotion
                for (net.minecraft.world.effect.MobEffectInstance e :
                        net.minecraft.world.item.alchemy.PotionUtils.m_43571_(potionStack)) {
                    cloud.m_19716_(new net.minecraft.world.effect.MobEffectInstance(e));
                    maxDur = Math.max(maxDur, e.m_19557_());
                }
                cloud.m_19714_(maxDur > 0 ? maxDur : 600); // setDuration
                level.m_7967_(cloud);
            } else {
                // v1.1.0 实测二百五十二（用户："被投掷的女仆连药水粒子效果都没有，
                // 说明效果压根就没给到"）：m_7292_（addEffect）在目标已有同类效果且
                // 不比现有更强时【拒绝施加】返回 false——再生药水每 3~5 秒连投时
                // 第一次生效后后续全部被拒，效果从不刷新/可见，也没有任何粒子。
                // 修复：① 改用 m_147207_（forceAddEffect——跳过"不更强则失败"规则，
                // 强制施加/刷新时长）；② 施加后播 entity_effect 紫色药水粒子（目标
                // 身上可见的"效果已给到"反馈）；③ 日志记录施加结果（aid-maid effect）。
                int applied = 0;
                for (net.minecraft.world.effect.MobEffectInstance e :
                        net.minecraft.world.item.alchemy.PotionUtils.m_43571_(potionStack)) {
                    if (target.m_147207_(new net.minecraft.world.effect.MobEffectInstance(e), maid)) {
                        applied++;
                    }
                    maxDur = Math.max(maxDur, e.m_19557_());
                }
                if (applied > 0) {
                    level.m_8767_(net.minecraft.core.particles.ParticleTypes.f_123811_,
                            target.m_20185_(), target.m_20227_(1.0), target.m_20189_(),
                            20, 0.4, 0.3, 0.4, 0.6);
                    LOGGER.info("aid-maid effect: potion={} applied={} target={}",
                            potionKey(potionStack), applied, target.m_20148_());
                }
            }
            if (useCd) {
                this.markPotionUsed(
                        potionCdKey(maid.m_20148_(), potionKey(potionStack)),
                        level.m_46467_(), maxDur > 0 ? maxDur : 60);
            }
            LOGGER.info("aid-maid cast: label={} potion={} target={} lingering={}",
                    label, potionKey(potionStack), target.m_20148_(), lingering);
            return Math.max(60, Math.min(maxDur > 0 ? maxDur : 60, 12000));
        } catch (Exception ignored) {
        }
        return -1;
    }

    private void ownerBuffChain(ServerLevel level, EntityMaid maid, ServerPlayer owner,
                                boolean aided, java.util.UUID maidId) {
        if (!aided && this.ownerInDanger(level, owner)
                && !owner.m_21023_(net.minecraft.world.effect.MobEffects.f_19600_) // strength
                && !owner.m_21023_(net.minecraft.world.effect.MobEffects.f_19596_)) { // swiftness
            if (this.throwPotionToOwner(level, maid, owner, BUFF_POTIONS, "增益", true) >= 0
                    || this.giveDrinkablePotion(maid, owner, BUFF_POTIONS, "增益", true) >= 0) {
                aided = true;
            }
        }
        if (aided) {
            this.setAidCooldown(maidId, 60); // 3 秒投掷间隔（v1.5.252g12：按女仆记）
        }
    }

    /** v1.5.252g：主人 16 格内是否有敌对生物（给增益药水助战的条件） */
    private boolean ownerInDanger(ServerLevel level, ServerPlayer owner) {
        try {
            return !level.m_45976_(net.minecraft.world.entity.monster.Monster.class,
                    owner.m_20191_().m_82400_(16.0)).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * v1.5.231b：投掷药水给主人——【择优】：
     * ① 择优：强效/长效优先（strong_healing > healing；long_regeneration >
     * strong_regeneration > regeneration；long_fire_resistance > fire_resistance），
     * 不再按槽位顺序取第一个；
     * ② v1.1.0 实测二百五十（用户："干脆就省去那些繁文缛节吧。只要触发扔药水这个
     * 事件，那就给予效果，滞留型则是在目标旁边生成对应的药水效果。然后会有一个药水
     * 抛出的动画就可以了"）：触发即生效——不再投掷实体（历次投掷实体方案均被实测
     * 否决：追踪乱飞/抛物线扔歪/落地不生效）。消耗 + 摆臂动画（抛药水动作）；滞留型
     * → 主人旁边生成 AreaEffectCloud；其他形态 → 直接给主人施加所有效果。
     */
    private int throwPotionToOwner(ServerLevel level, EntityMaid maid, ServerPlayer owner,
                                    java.util.Set<String> potionNames, String label, boolean useCd) {
        try {
            net.minecraftforge.items.IItemHandler inv = maid.getMaidInv();
            int bestSlot = -1;
            int bestScore = -1;
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                // v1.5.252g4：投掷【只选喷溅/滞留型】——饮用型扔出去不施加效果
                //（原版 ThrownPotion 扔饮用型只是碎瓶子），只能塞背包
                if (stack.m_41619_() || !(stack.m_41720_() instanceof net.minecraft.world.item.SplashPotionItem
                        || stack.m_41720_() instanceof net.minecraft.world.item.LingeringPotionItem)) {
                    continue;
                }
                if (!this.isPotionOf(stack, potionNames)) {
                    continue;
                }
                // v1.5.252g13：useCd=false（着火/溺水情境）不走 CD——情境药水的
                // 正门是"主人身上效果判定"（调用处 hasEffectOn），效果没了立即
                // 重投；CD 只用于治疗/再生/增益（无"身上效果"概念的场景）
                if (useCd && !this.potionReady(
                        potionCdKey(maid.m_20148_(), potionKey(stack)), level.m_46467_())) {
                    continue;
                }
                int score = potionStrength(stack);
                if (score > bestScore) {
                    bestScore = score;
                    bestSlot = i;
                }
            }
            if (bestSlot < 0) {
                return -1;
            }
            ItemStack stack = inv.getStackInSlot(bestSlot);
            ItemStack potionStack = stack.m_41777_(); // 快照（extract 前复制，防槽位变动）
            // v1.1.0 实测二百五十：触发即生效（同 throwPotionTo 口径）——不再投掷
            // 实体。消耗 + 摆臂动画；滞留型 → 主人旁边生成 AreaEffectCloud（半径 3，
            // setPotion + addEffect，持续到效果结束）；其他形态 → 直接给主人施加所有
            // 效果。（原版"抛药水"的动作观感由摆臂动画保留。）
            inv.extractItem(bestSlot, 1, false);
            maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
            // v1.1.0 实测二百五十一：抛药水动画（纯观感）——空药水抛物线飞向主人
            this.throwPotionAnimate(level, maid, owner);
            int maxDur = 0;
            boolean lingering = potionStack.m_41720_() instanceof net.minecraft.world.item.LingeringPotionItem;
            if (lingering) {
                net.minecraft.world.entity.AreaEffectCloud cloud =
                        new net.minecraft.world.entity.AreaEffectCloud(level,
                                owner.m_20185_(), owner.m_20186_(), owner.m_20189_());
                cloud.m_19712_(3.0f); // setRadius
                cloud.m_19722_(net.minecraft.world.item.alchemy.PotionUtils
                        .m_43579_(potionStack)); // setPotion
                for (net.minecraft.world.effect.MobEffectInstance e :
                        net.minecraft.world.item.alchemy.PotionUtils.m_43571_(potionStack)) {
                    cloud.m_19716_(new net.minecraft.world.effect.MobEffectInstance(e));
                    maxDur = Math.max(maxDur, e.m_19557_());
                }
                cloud.m_19714_(maxDur > 0 ? maxDur : 600); // setDuration
                level.m_7967_(cloud);
            } else {
                // v1.1.0 实测二百五十二：同 throwPotionTo——forceAddEffect 强制施加
                // + entity_effect 药水粒子 + 日志验证
                int applied = 0;
                for (net.minecraft.world.effect.MobEffectInstance e :
                        net.minecraft.world.item.alchemy.PotionUtils.m_43571_(potionStack)) {
                    if (owner.m_147207_(new net.minecraft.world.effect.MobEffectInstance(e), maid)) {
                        applied++;
                    }
                    maxDur = Math.max(maxDur, e.m_19557_());
                }
                if (applied > 0) {
                    level.m_8767_(net.minecraft.core.particles.ParticleTypes.f_123811_,
                            owner.m_20185_(), owner.m_20227_(1.0), owner.m_20189_(),
                            20, 0.4, 0.3, 0.4, 0.6);
                    LOGGER.info("aid-owner effect: potion={} applied={} owner={}",
                            potionKey(potionStack), applied, owner.m_20148_());
                }
            }
            if (useCd) {
                this.markPotionUsed(
                        potionCdKey(maid.m_20148_(), potionKey(potionStack)),
                        level.m_46467_(), maxDur > 0 ? maxDur : 60);
            }
            maid.getChatBubbleManager().addTextChatBubble("主人别怕，" + label + "药水来了！");
            LOGGER.info("aid-owner cast: label={} potion={} ownerDist={} lingering={}",
                    label, potionKey(potionStack),
                    String.format("%.1f", maid.m_20238_(owner.m_20182_())), lingering);
            return Math.max(60, Math.min(maxDur > 0 ? maxDur : 60, 12000));
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** v1.5.231：药水强度评分（数值越大越优先使用——强效/长效 > 普通） */
    private static int potionStrength(ItemStack stack) {
        try {
            net.minecraft.world.item.alchemy.Potion potion =
                    net.minecraft.world.item.alchemy.PotionUtils.m_43579_(stack);
            if (potion == null) {
                return 0;
            }
            net.minecraft.resources.ResourceLocation key =
                    net.minecraftforge.registries.ForgeRegistries.POTIONS.getKey(potion);
            if (key == null) {
                return 0;
            }
            String id = key.toString();
            int score = id.contains("strong_") || id.contains("long_") ? 2 : 1;
            // v1.5.252g8：喷溅 > 滞留——喷溅命中立即全效；滞留只是 30 秒云，
            // 主人移动中就吃不到（同样一瓶药水效果差一截）
            if (stack.m_41720_() instanceof net.minecraft.world.item.SplashPotionItem) {
                score++;
            }
            return score;
        } catch (Exception ignored) {
            return 0;
        }
    }

    /**
     * v1.5.252g6：给主人的物品优先塞【快捷栏空位】（0-8，主人一眼能看到、马上能
     * 用），快捷栏没空位再走常规背包插入；都放不下返回剩余，空 = 全部放入。
     */
    private ItemStack giveToOwnerHotbarFirst(ServerPlayer owner, ItemStack toGive) {
        try {
            net.minecraftforge.items.IItemHandler mainInv =
                    new net.minecraftforge.items.wrapper.InvWrapper(owner.m_150109_());
            // ① 快捷栏 0-8 优先（insertItem 自动堆叠同类 + 填空位）
            for (int s = 0; s < 9 && !toGive.m_41619_(); s++) {
                toGive = mainInv.insertItem(s, toGive, false);
            }
            // ② 快捷栏满 → 常规背包插入（主背包/已有堆叠）
            if (!toGive.m_41619_()) {
                toGive = net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(mainInv, toGive, false);
            }
        } catch (Exception ignored) {
        }
        return toGive;
    }

    /** 普通（饮用型）药水 → 塞主人背包（主人自己喝；背包满自动退回）。
     *  v1.5.252g3：返回该药水最长效果时长（CD），没给返回 -1
     *  v1.5.252g6：优先塞快捷栏空位
     *  v1.5.252g13：useCd=false（着火/溺水情境）不走 CD——效果判定在调用处 */
    private int giveDrinkablePotion(EntityMaid maid, ServerPlayer owner,
                                    java.util.Set<String> potionNames, String label, boolean useCd) {
        try {
            net.minecraftforge.items.IItemHandler inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (stack.m_41619_() || !(stack.m_41720_() instanceof net.minecraft.world.item.PotionItem)) {
                    continue;
                }
                if (!this.isPotionOf(stack, potionNames)) {
                    continue;
                }
                // v1.5.252g13：同种药水 CD 内跳过（仅 useCd=true 场景）
                if (useCd && !this.potionReady(
                        potionCdKey(maid.m_20148_(), potionKey(stack)),
                        maid.m_9236_().m_46467_())) {
                    continue;
                }
                ItemStack toGive = inv.extractItem(i, 1, false);
                ItemStack remain = this.giveToOwnerHotbarFirst(owner, toGive);
                if (!remain.m_41619_()) {
                    net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(inv, remain, false);
                    // v1.5.231b：背包满提示（否则"给了但没给出去"无任何反馈）
                    // v1.5.231d：明确说"我给不了东西"
                    maid.getChatBubbleManager().addTextChatBubble(
                            "主人背包满了，我给不了东西（" + label + "药水放不进去）……");
                    return -1;
                }
                // v1.5.252g13：CD 记账【提前到气泡之前】（防气泡异常吞 CD）
                int maxDur2 = 0;
                for (net.minecraft.world.effect.MobEffectInstance e :
                        net.minecraft.world.item.alchemy.PotionUtils.m_43571_(stack)) {
                    maxDur2 = Math.max(maxDur2, e.m_19557_());
                }
                if (useCd) {
                    this.markPotionUsed(
                            potionCdKey(maid.m_20148_(), potionKey(stack)),
                            maid.m_9236_().m_46467_(), maxDur2 > 0 ? maxDur2 : 60);
                }
                maid.getChatBubbleManager().addTextChatBubble("主人，" + label + "药水放你背包了，快喝！");
                // v1.1.0 实测三十二（用户："女仆不会传递直接可以饮用的药水"——传递
                // 功能其实一直在，但只有气泡提示没有系统字幕，观感上像没给）：
                // 塞背包成功发绿色系统字幕（与喂食 [maid_smart] 同款显示）
                owner.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                        "\u00a7a[maid_smart] 女仆给了你一瓶" + label + "药水（在背包里，快喝）"));
                // v1.5.252g9：塞背包日志（latest.log 搜 "aid-owner give"）
                LOGGER.info("aid-owner give: label={} potion={}",
                        label, potionKey(stack));
                return Math.max(60, Math.min(maxDur2 > 0 ? maxDur2 : 60, 12000));
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** 指定物品塞主人背包（牛奶/蜂蜜瓶等；背包满自动退回）。无则 false
     *  v1.5.252g6：优先塞快捷栏空位 */
    private boolean giveItemToOwner(EntityMaid maid, ServerPlayer owner, String itemId, String bubble) {
        try {
            net.minecraft.world.item.Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS
                    .getValue(net.minecraft.resources.ResourceLocation.parse(itemId));
            if (item == null) {
                return false;
            }
            net.minecraftforge.items.IItemHandler inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (stack.m_41619_() || stack.m_41720_() != item) {
                    continue;
                }
                ItemStack toGive = inv.extractItem(i, 1, false);
                ItemStack remain = this.giveToOwnerHotbarFirst(owner, toGive);
                if (!remain.m_41619_()) {
                    net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(inv, remain, false);
                    // v1.5.231b：背包满提示
                    // v1.5.231d：明确说"我给不了东西"
                    maid.getChatBubbleManager().addTextChatBubble("主人背包满了，我给不了东西（放不进去）……");
                    return false;
                }
                maid.getChatBubbleManager().addTextChatBubble(bubble);
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * v1.1.0 实测二百五十一（用户："再加一个药水以抛物线方式从女仆飞到目标原有位置
     * 的动画。但那个仅仅是动画"）：抛药水【纯动画】——生成一个空喷溅药水（无任何
     * 药水效果，落地不施加任何东西），从女仆位置按弹道公式抛物线飞向目标位置。
     * 触发即生效的逻辑不变（动画只是观感，效果已由调用方直接施加）。
     * 注意：动画抛掷必须【远距离（>3 格）且目标仍存活】才做——近距离/目标已消失
     * 时抛空药水没有意义（目标位置即女仆脚下/原地）。
     */
    private void throwPotionAnimate(ServerLevel level, EntityMaid maid,
                                    net.minecraft.world.entity.LivingEntity target) {
        try {
            if (target == null || !target.m_6084_()) {
                return;
            }
            double dx = target.m_20185_() - maid.m_20185_();
            double dz = target.m_20189_() - maid.m_20189_();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len <= 3.0) {
                return; // 近距离不做动画（目标就在身边，抛出无观感意义）
            }
            // 空喷溅药水（无药水效果——落地只有碎裂粒子，不施加任何状态）
            ItemStack anim = new ItemStack(net.minecraft.world.item.Items.f_42736_);
            net.minecraft.world.item.alchemy.PotionUtils.m_43549_(anim,
                    net.minecraft.world.item.alchemy.Potions.f_43585_); // setPotion(empty)
            net.minecraft.world.entity.projectile.ThrownPotion potion =
                    new net.minecraft.world.entity.projectile.ThrownPotion(level, maid);
            potion.m_37446_(anim);
            // 弹道公式解仰角（与旧版二百四十九同款）：速度 1.0、无随机散布、
            // 按水平距离/高度差精确飞向目标位置；动画抛掷不设 homing 标签
            //（纯抛物线自然飞行，落地即消失——空药水无效果，安全）。
            double v = 1.0;
            double g = 0.05;
            double h = target.m_20186_() - maid.m_20186_();
            double A = 2 * v * v / (g * len);
            double B = 2 * v * v * h / (g * len * len) + 1;
            double disc = A * A - 4 * B;
            double u;
            if (disc >= 0) {
                u = (A - Math.sqrt(disc)) / 2;
                if (u < 0) {
                    u = (A + Math.sqrt(disc)) / 2;
                }
            } else {
                u = 0.25;
            }
            double vh = v / Math.sqrt(1 + u * u);
            double vy = v * u / Math.sqrt(1 + u * u);
            potion.m_6686_(dx / len * vh, vy, dz / len * vh, 1.0f, 0.0f);
            level.m_7967_(potion);
        } catch (Exception ignored) {
        }
    }

    /** 药水注册名是否在目标集合内（m_43579_=PotionUtils.getPotion） */
    private static boolean isPotionOf(ItemStack stack, java.util.Set<String> potionNames) {
        try {
            net.minecraft.world.item.alchemy.Potion potion =
                    net.minecraft.world.item.alchemy.PotionUtils.m_43579_(stack);
            if (potion == null) {
                return false;
            }
            net.minecraft.resources.ResourceLocation key =
                    net.minecraftforge.registries.ForgeRegistries.POTIONS.getKey(potion);
            return key != null && potionNames.contains(key.toString());
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 主人身上是否有需要解除的负面效果（中毒/凋零/缓慢/虚弱/失明/饥饿/反胃/黑暗/漂浮） */
    private static boolean hasNegativeEffect(ServerPlayer owner) {
        try {
            for (net.minecraft.world.effect.MobEffectInstance eff : owner.m_21220_()) { // getActiveEffects
                net.minecraft.world.effect.MobEffect e = eff.m_19544_();
                net.minecraft.resources.ResourceLocation key =
                        net.minecraftforge.registries.ForgeRegistries.MOB_EFFECTS.getKey(e);
                if (key != null && NEGATIVE_EFFECTS.contains(key.toString())) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** v1.5.215：诊断——女仆背包里治疗/再生药水数量（含喷溅/滞留形态） */
    private static int countHealPotions(EntityMaid maid) {
        int n = 0;
        try {
            net.minecraftforge.items.IItemHandler inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (s.m_41619_()) {
                    continue;
                }
                if (s.m_41720_() instanceof net.minecraft.world.item.PotionItem
                        || s.m_41720_() instanceof net.minecraft.world.item.SplashPotionItem
                        || s.m_41720_() instanceof net.minecraft.world.item.LingeringPotionItem) {
                    if (isPotionOf(s, HEAL_POTIONS) || isPotionOf(s, REGEN_POTIONS)) {
                        n += s.m_41613_();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return n;
    }

    /** v1.5.215：诊断——女仆背包里可投喂的食物数量（FOODS 白名单） */
    private static int countFoods(EntityMaid maid) {
        int n = 0;
        try {
            net.minecraftforge.items.IItemHandler inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (s.m_41619_()) {
                    continue;
                }
                for (net.minecraft.world.item.ItemStack f : com.maidsmart.action.EmotionalActionExecutor.FOODS) {
                    if (!f.m_41619_() && s.m_150930_(f.m_41720_())) {
                        n += s.m_41613_();
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return n;
    }

    /** v1.1.0 实测一百二十六：女仆身上是否有任何可用于支援的物品（背包+双手）——
     *  药水/金苹果/牛奶/蜂蜜/FOODS 食物任一即可。没有就整轮静默——防"没道具还说话/
     *  喂空气"（预检硬门槛，与各分支的"摸到才播报"构成双保险）。 */
    private static boolean hasAidItemAtAll(EntityMaid maid) {
        try {
            net.minecraftforge.items.IItemHandler inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                if (isAidItem(inv.getStackInSlot(i))) {
                    return true;
                }
            }
            return isAidItem(maid.m_21205_()) || isAidItem(maid.m_21206_());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isAidItem(ItemStack s) {
        if (s == null || s.m_41619_()) {
            return false;
        }
        net.minecraft.world.item.Item it = s.m_41720_();
        if (it instanceof net.minecraft.world.item.PotionItem
                || it instanceof net.minecraft.world.item.SplashPotionItem
                || it instanceof net.minecraft.world.item.LingeringPotionItem) {
            return true;
        }
        // 附魔/普通金苹果
        if (it == net.minecraft.world.item.Items.f_42437_ || it == net.minecraft.world.item.Items.f_42436_) {
            return true;
        }
        // 牛奶桶
        net.minecraft.world.item.Item milk = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                net.minecraft.resources.ResourceLocation.parse("minecraft:milk_bucket"));
        if (milk != null && it == milk) {
            return true;
        }
        // 蜂蜜瓶
        if (it == net.minecraft.world.item.Items.f_42787_) {
            return true;
        }
        for (ItemStack food : com.maidsmart.action.EmotionalActionExecutor.FOODS) {
            if (food.m_41720_() == it) {
                return true;
            }
        }
        return false;
    }

    /**
     * v1.5.201：金苹果/附魔金苹果——【不作为常规食物】处理（不进喂食/治疗食物队列，
     * 不进主人背包），而是像喷溅药水一样对主人【即时使用】：消耗 1 个，直接给主人
     * 施加原版金苹果效果（吸收+再生；附魔金苹果额外抗性/抗火）。附魔金苹果优先
     * （效果更强）。背包无则返回 false。
     */
    private boolean useGoldenApple(EntityMaid maid, ServerPlayer owner) {
        try {
            net.minecraftforge.items.IItemHandler inv = maid.getMaidInv();
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
            // 原版金苹果效果（数值与原版食物一致；m_7292_=addEffect）
            applyEffect(owner, "minecraft:absorption", 2400, enchanted ? 3 : 0);
            applyEffect(owner, "minecraft:regeneration", enchanted ? 600 : 100, enchanted ? 4 : 1);
            if (enchanted) {
                applyEffect(owner, "minecraft:damage_resistance", 6000, 0);
                applyEffect(owner, "minecraft:fire_resistance", 6000, 0);
            }
            maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND); // 使用动画
            // v1.1.0 实测三十二（用户："喂食金苹果的时候没有音效"）：吃苹果音效
            playSoundAt(owner, "minecraft:entity.generic.eat");
            maid.getChatBubbleManager().addTextChatBubble(
                    enchanted ? "主人，附魔金苹果给你！" : "主人，金苹果给你！");
            // v1.5.307：金苹果路径补系统提示——用户："喂了什么系统提示不生效"；
            // 排查：食物喂食的系统消息一直在（feedFoodDirect），但金苹果路径只有
            // 气泡没有系统消息（效果是直接加成的，玩家看不到"喂了什么"）
            owner.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                    "\u00a7a[maid_smart] 女仆给你吃了" + (enchanted ? "附魔金苹果" : "金苹果")
                            + "（吸收/再生等效果已加成）"));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 给目标施加效果（注册表取效果，避开 SRG 字段名） */
    private static void applyEffect(net.minecraft.world.entity.LivingEntity target,
                                    String effectId, int duration, int amplifier) {
        try {
            net.minecraft.world.effect.MobEffect effect = net.minecraftforge.registries.ForgeRegistries.MOB_EFFECTS
                    .getValue(net.minecraft.resources.ResourceLocation.parse(effectId));
            if (effect != null) {
                target.m_7292_(new net.minecraft.world.effect.MobEffectInstance(effect, duration, amplifier));
            }
        } catch (Exception ignored) {
        }
    }

    /** 喂治疗食物（v1.5.201：按饱和度恢复量选最优；v1.5.206：清单 = EmotionalActionExecutor.
     *  FOODS 全量安全食物——金胡萝卜/兔肉煲/南瓜派等全覆盖；金苹果/附魔金苹果已移出，
     *  走 useGoldenApple 即时增益路径） */
    private boolean feedHealingFood(EntityMaid maid, ServerPlayer owner) {
        try {
            net.minecraftforge.items.IItemHandler inv = maid.getMaidInv();
            int bestSlot = -1;
            double bestSat = -1.0;
            // v1.5.299：手持食物参与选优（同 giveFoodToOwner——旧版只扫背包，
            // 女仆手上拿着肉排时治疗/饱食喂食都找不到）
            int handSlot = -1;
            ItemStack handItem = null;
            double handSat = -1.0;
            for (int h = 0; h < 2; h++) {
                ItemStack hs = h == 0 ? maid.m_21205_() : maid.m_21206_();
                if (hs.m_41619_()) {
                    continue;
                }
                boolean isFood = false;
                for (ItemStack food : com.maidsmart.action.EmotionalActionExecutor.FOODS) {
                    if (food.m_41720_() == hs.m_41720_()) {
                        isFood = true;
                        break;
                    }
                }
                if (!isFood) {
                    continue;
                }
                double sat = com.maidsmart.action.EmotionalActionExecutor.foodSaturation(hs, owner);
                if (sat > handSat) {
                    handSat = sat;
            // v1.1.0 实测十六（审查 P2-7）：副手哨兵值修复——旧版 h==0→-2 / h==1→-1，
            // 而 -1 同时是"没找到手持食物"的哨兵 → 副手食物（handSlot=-1）与"没有"
            // 无法区分：副手-only 时直接 return false（副手食物永远选不中）。改 -3
            // 表示副手，-1 仍表示"没有"，哨兵不再冲突
            handSlot = h == 0 ? -2 : -3;
                    // v1.1.0 实测一百二十六：存快照而非活引用（同 tick 隐藏槽/换手
                    // 清空活引用 → 后续喂空栈）
                    handItem = hs.m_41777_();
                }
            }
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (stack.m_41619_()) {
                    continue;
                }
                boolean isFood = false;
                for (ItemStack food : com.maidsmart.action.EmotionalActionExecutor.FOODS) {
                    if (food.m_41720_() == stack.m_41720_()) {
                        isFood = true;
                        break;
                    }
                }
                if (!isFood) {
                    continue;
                }
                double sat = com.maidsmart.action.EmotionalActionExecutor.foodSaturation(stack, owner);
                if (sat > bestSat) {
                    bestSat = sat;
                    bestSlot = i;
                }
            }
            if (bestSlot < 0 && handSlot == -1) {
                return false;
            }
            ItemStack toGive;
            // v1.1.0 实测十六：副手哨兵修复后判定（handSlot != -1 即有手持食物）
            // v1.1.0 实测一百二十六：handItem 已是扫描时刻快照，消耗前核对当前手
            // 仍是同种食物才 shrink（防同 tick 手被换走/清空）
            if (handSlot != -1 && handSat >= bestSat) {
                toGive = handItem;
                if (toGive.m_41619_()) {
                    return false;
                }
                ItemStack liveHand = handSlot == -2 ? maid.m_21205_() : maid.m_21206_();
                if (liveHand.m_41619_() || !liveHand.m_150930_(toGive.m_41720_())) {
                    return false;
                }
                liveHand.m_41774_(1);
            } else {
                toGive = inv.extractItem(bestSlot, 1, false);
            }
            // v1.5.288：改为直接喂食（饱食度直接加到主人，不再塞背包/快捷栏）
            if (!com.maidsmart.action.EmotionalActionExecutor.feedFoodDirect(maid, owner, toGive)) {
                net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(inv, toGive, false);
                return false;
            }
            maid.getChatBubbleManager().addTextChatBubble("主人快吃点东西补补！");
            return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    /** v1.5.288：负面效果【直接喂】——牛奶（清主人全部效果 + 空桶返还）优先，
     *  其次蜂蜜瓶（解中毒 + 饱食 + 玻璃瓶返还）。旧版是塞主人背包/手上（用户：
     *  "投喂应该跟本来就有的喂食功能一样是直接喂给主人"）。
     *  v1.5.289：牛奶先查【正面状态】——主人身上有增益效果时不喂牛奶（牛奶清
     *  全部效果会把力量/再生/抗火等增益一起清掉，与女仆自己喝牛奶同款前提）；
     *  此时只喂蜂蜜（蜂蜜只解中毒+饱食，不清增益）。
     *  v1.1.0 实测一百五十二：MISC_MILK_FEED_WITH_BUFF 开启时无视增益照喂——
     *  主人装备/饰品带永久增益时旧版永远不满足"无增益"，中毒/凋零也不解。 */
    private boolean feedMilkOrHoneyDirect(EntityMaid maid, ServerPlayer owner) {
        try {
            net.minecraftforge.items.IItemHandler inv = maid.getMaidInv();
            net.minecraft.world.item.Item milk = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                    net.minecraft.resources.ResourceLocation.parse("minecraft:milk_bucket"));
            net.minecraft.world.item.Item honey = net.minecraft.world.item.Items.f_42787_;
            // 牛奶优先（全解）——前提：主人身上没有增益效果（开关开启时无视增益）
            boolean ownerHasBuff = this.ownerHasBeneficialEffect(owner);
            if (milk != null && (!ownerHasBuff
                    || com.maidsmart.config.MaidSmartConfig.MISC_MILK_FEED_WITH_BUFF.get())) {
                // v1.5.299：手持牛奶也认（主手→副手）——旧版只扫背包
                ItemStack handMilk = maid.m_21205_();
                boolean handIsMilk = !handMilk.m_41619_() && handMilk.m_41720_() == milk;
                if (!handIsMilk) {
                    handMilk = maid.m_21206_();
                    handIsMilk = !handMilk.m_41619_() && handMilk.m_41720_() == milk;
                }
                if (handIsMilk) {
                    handMilk.m_41774_(1); // 消耗手上的牛奶
                    this.applyMilkEffect(maid, owner, inv);
                    return true;
                }
                for (int i = 0; i < inv.getSlots(); i++) {
                    ItemStack stack = inv.getStackInSlot(i);
                    if (stack.m_41619_() || stack.m_41720_() != milk) {
                        continue;
                    }
                    inv.extractItem(i, 1, false);
                    this.applyMilkEffect(maid, owner, inv);
                    return true;
                }
            }
            // 蜂蜜（解中毒 + 饱食，不清增益——有增益时这是唯一安全选择）
            // v1.5.299：手持蜂蜜也认（主手→副手）
            ItemStack handHoney = maid.m_21205_();
            boolean handIsHoney = !handHoney.m_41619_() && handHoney.m_41720_() == honey;
            if (!handIsHoney) {
                handHoney = maid.m_21206_();
                handIsHoney = !handHoney.m_41619_() && handHoney.m_41720_() == honey;
            }
            if (handIsHoney) {
                // v1.1.0 实测三十二修复（用户："喂食蜂蜜瓶似乎没有效果"）：
                // 旧版先 m_41774_(1) 消耗再把手上的【空瓶】传给 feedFoodDirect
                // ——蜂蜜瓶没有 FoodProperties（空瓶），feedFoodDirect 返回 false
                // → 把空瓶插回背包：蜂蜜凭空消失、无任何效果。
                // 修复：先复制一份喂给主人（feedFoodDirect 内部自行处理
                // 饱食/解中毒/返还玻璃瓶），成功后才消耗手上的蜂蜜。
                ItemStack honeyCopy = handHoney.m_41777_();
                if (com.maidsmart.action.EmotionalActionExecutor.feedFoodDirect(maid, owner, honeyCopy)) {
                    handHoney.m_41774_(1);
                    maid.getChatBubbleManager().addTextChatBubble("主人，蜂蜜喝下，解一下中毒！");
                    return true;
                }
                return false;
            }
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (stack.m_41619_() || stack.m_41720_() != honey) {
                    continue;
                }
                // v1.1.0 实测三十二修复：同手持分支——旧版先 extract 再把 extract 出来的
                // （被消耗语义弄混的）栈传 feedFoodDirect；extract 返回的是完整的 1 个
                // 蜂蜜瓶倒是没错，但失败路径 insertItemStacked(inv, stack) 插回的与
                // extract 的等价，本分支实际正常。统一改为复制传参 + 成功才 extract，
                // 与手持分支同口径（防未来 extract 语义变化再翻车）。
                ItemStack honeyCopy2 = stack.m_41777_();
                if (com.maidsmart.action.EmotionalActionExecutor.feedFoodDirect(maid, owner, honeyCopy2)) {
                    inv.extractItem(i, 1, false);
                    maid.getChatBubbleManager().addTextChatBubble("主人，蜂蜜喝下，解一下中毒！");
                    return true;
                }
                return false;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** v1.5.299：牛奶喂食共同动作（清全部效果 + 空桶返还 + 摆臂动画 + 音效 + 提示）——
     *  背包牛奶与手持牛奶共用，防重复代码 */
    private void applyMilkEffect(EntityMaid maid, ServerPlayer owner,
                                 net.minecraftforge.items.IItemHandler inv) {
        java.util.List<net.minecraft.world.effect.MobEffectInstance> effects =
                new java.util.ArrayList<>(owner.m_21220_());
        for (net.minecraft.world.effect.MobEffectInstance ei : effects) {
            owner.m_21195_(ei.m_19544_());
        }
        net.minecraft.world.item.Item bucket = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                net.minecraft.resources.ResourceLocation.parse("minecraft:bucket"));
        if (bucket != null) {
            net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(
                    inv, new ItemStack(bucket), false);
        }
        // v1.5.292：喂食动作（与投药水/金苹果同款摆臂动画）
        maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
        // v1.5.290：喝牛奶音效 + 系统提示（喂了什么）
        net.minecraft.sounds.SoundEvent snd = net.minecraftforge.registries.ForgeRegistries.SOUND_EVENTS
                .getValue(net.minecraft.resources.ResourceLocation.parse("minecraft:entity.generic.drink"));
        if (snd != null) {
            owner.m_9236_().m_5594_(null, owner.m_20183_(), snd,
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.0f);
        }
        owner.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                "\u00a7a[maid_smart] 女仆给你喝了牛奶，负面状态全解！"));
        maid.getChatBubbleManager().addTextChatBubble("主人，牛奶喝下，负面状态全解！");
    }

    /** v1.5.289：主人身上是否有增益效果（牛奶全解的前提检查——有增益不喂牛奶） */
    private static boolean ownerHasBeneficialEffect(ServerPlayer owner) {
        for (net.minecraft.world.effect.MobEffectInstance ei : owner.m_21220_()) {
            if (ei.m_19544_().m_19483_() == net.minecraft.world.effect.MobEffectCategory.BENEFICIAL) {
                return true;
            }
        }
        return false;
    }
}
