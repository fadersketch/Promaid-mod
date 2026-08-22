package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.behavior.Behavior;
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
    /** v1.5.252g7【投喂药水 CD】——按【药水种类】记冷却（key = 药水注册名，
     *  如 minecraft:long_swiftness）：CD = 该药水最长效果时长，期间同种不再投，
     *  **其他种照投**（投了力量、迅捷还能投）；瞬间治疗短 CD（60 tick）。
     *  aidCooldown（3 秒）只是【投掷间隔】，与种类 CD 相互独立。 */
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

        // ================= v1.1.0 实测六：女仆互助（战斗支援） =================
        // 同主人、16 格内的其他女仆低血/着火时也投药水救她（与救主人同款追踪弹）。
        // 每轮最多救一个（防一次检查全场连扔）；自己的保命药水不够时不勉强
        //（治疗药水只剩 1 瓶且自己血也不健康时留着——简单起见不做复杂判断：
        // 有富余就帮，实战中女仆背包通常由玩家统一配药）。
        this.aidMaidSisters(level, maid, gameTime);
    }

    /** v1.1.0 实测六：给附近受伤/着火的姐妹女仆丢药水（治疗/再生；着火先抗火） */
    private void aidMaidSisters(ServerLevel level, EntityMaid maid, long gameTime) {
        try {
            for (EntityMaid sister : level.m_45976_(EntityMaid.class,
                    maid.m_20191_().m_82400_(16.0))) {
                if (sister == maid || !sister.m_6084_() || sister.m_269323_() != maid.m_269323_()) {
                    continue; // 自己/死了/不是同主人的女仆
                }
                double hpRatio2 = sister.m_21223_() / Math.max(1.0f, sister.m_21233_());
                boolean did = false;
                // 着火优先（与主人链同顺序）
                if (sister.m_6060_() && !this.hasEffectOn(sister, "minecraft:fire_resistance")) {
                    did = this.throwPotionTo(level, maid, sister, FIRE_RESIST_POTIONS, "抗火", false) >= 0;
                }
                // 低血 → 治疗/再生（useCd=true 同主人：同种药水 CD 共享记账）
                if (!did && hpRatio2 < com.maidsmart.config.MaidSmartConfig.AID_HEALTH_THRESHOLD.get()) {
                    did = this.throwPotionTo(level, maid, sister, HEAL_POTIONS, "治疗", true) >= 0;
                }
                if (!did && hpRatio2 < com.maidsmart.config.MaidSmartConfig.AID_HEALTH_THRESHOLD.get()) {
                    did = this.throwPotionTo(level, maid, sister, REGEN_POTIONS, "再生", true) >= 0;
                }
                if (did) {
                    maid.getChatBubbleManager().addTextChatBubble("姐妹挺住，药水来了！");
                    return; // 每轮最多救一个
                }
            }
        } catch (Exception ignored) {
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
                if (useCd && !this.potionReady(potionKey(stack), level.m_46467_())) {
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
            net.minecraft.world.entity.projectile.ThrownPotion potion =
                    new net.minecraft.world.entity.projectile.ThrownPotion(level, maid);
            potion.m_37446_(stack.m_41777_());
            // 追踪标签（HomingPotionMixin 通用——目标 UUID）
            potion.getPersistentData().m_128359_("maid_smart_homing",
                    target.m_20148_().toString());
            double dx = target.m_20185_() - maid.m_20185_();
            double dy = target.m_20227_(0.3) - maid.m_20227_(0.6);
            double dz = target.m_20189_() - maid.m_20189_();
            double len = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
            potion.m_6686_(dx / len * 0.9, dy + 0.2, dz / len * 0.9, 1.4f, 1.0f);
            level.m_7967_(potion);
            inv.extractItem(bestSlot, 1, false);
            maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
            int maxDur = 0;
            for (net.minecraft.world.effect.MobEffectInstance e :
                    net.minecraft.world.item.alchemy.PotionUtils.m_43571_(stack)) {
                maxDur = Math.max(maxDur, e.m_19557_());
            }
            if (useCd) {
                this.markPotionUsed(potionKey(stack), level.m_46467_(), maxDur > 0 ? maxDur : 60);
            }
            LOGGER.info("aid-maid throw: label={} potion={} target={}",
                    label, potionKey(stack), target.m_20148_());
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
     * v1.5.231b：投掷药水给主人——【择优 + 追踪弹】：
     * ① 择优：强效/长效优先（strong_healing > healing；long_regeneration >
     * strong_regeneration > regeneration；long_fire_resistance > fire_resistance），
     * 不再按槽位顺序取第一个；
     * ② 追踪：投掷物 persistentData 打 "maid_smart_homing" = 主人 UUID，
     * HomingPotionMixin 每 tick 修正方向锁定主人飞行直至击中（旧版抛物线
     * 命中率极低——"喷溅治疗药水基本没喷中过"）。
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
                if (useCd && !this.potionReady(potionKey(stack), level.m_46467_())) {
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
            net.minecraft.world.entity.projectile.ThrownPotion potion =
                    new net.minecraft.world.entity.projectile.ThrownPotion(level, maid);
            potion.m_37446_(stack.m_41777_()); // setItem（复制药水物品；m_41777_=copy）
            // 追踪标签：HomingPotionMixin 每 tick 修正方向锁定主人，直至命中
            potion.getPersistentData().m_128359_("maid_smart_homing",
                    owner.m_20148_().toString());
            // 初速朝主人（追踪会接管方向；初速只是让实体先动起来）
            double dx = owner.m_20185_() - maid.m_20185_();
            double dy = owner.m_20227_(0.3) - maid.m_20227_(0.6);
            double dz = owner.m_20189_() - maid.m_20189_();
            double len = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
            potion.m_6686_(dx / len * 0.9, dy + 0.2, dz / len * 0.9, 1.4f, 1.0f);
            level.m_7967_(potion);
            inv.extractItem(bestSlot, 1, false); // 消耗 1 个
            maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
            // v1.5.252g13：CD 记账【提前到气泡/日志之前】——旧版在气泡之后，
            // 气泡若抛异常（被 catch 吞掉）药水已扔已消耗但 CD 没记 → 下一次
            // 又投（"一直扔同一种药水"的重大嫌疑）
            int maxDur = 0;
            for (net.minecraft.world.effect.MobEffectInstance e :
                    net.minecraft.world.item.alchemy.PotionUtils.m_43571_(stack)) {
                maxDur = Math.max(maxDur, e.m_19557_());
            }
            if (useCd) {
                this.markPotionUsed(potionKey(stack), level.m_46467_(), maxDur > 0 ? maxDur : 60);
            }
            maid.getChatBubbleManager().addTextChatBubble("主人别怕，" + label + "药水来了！");
            // v1.5.252g9：投掷日志（latest.log 搜 "aid-owner throw"，排查浪费）
            LOGGER.info("aid-owner throw: label={} potion={} ownerDist={}",
                    label, potionKey(stack),
                    String.format("%.1f", maid.m_20238_(owner.m_20182_())));
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
                if (useCd && !this.potionReady(potionKey(stack), maid.m_9236_().m_46467_())) {
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
                    this.markPotionUsed(potionKey(stack), maid.m_9236_().m_46467_(), maxDur2 > 0 ? maxDur2 : 60);
                }
                maid.getChatBubbleManager().addTextChatBubble("主人，" + label + "药水放你背包了，快喝！");
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
                    handSlot = h == 0 ? -2 : -1;
                    handItem = hs;
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
            if (handSlot != -1 && handSat >= bestSat) {
                toGive = handItem.m_41777_(); // copy
                handItem.m_41774_(1);         // shrink(1)
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
     *  此时只喂蜂蜜（蜂蜜只解中毒+饱食，不清增益）。 */
    private boolean feedMilkOrHoneyDirect(EntityMaid maid, ServerPlayer owner) {
        try {
            net.minecraftforge.items.IItemHandler inv = maid.getMaidInv();
            net.minecraft.world.item.Item milk = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                    net.minecraft.resources.ResourceLocation.parse("minecraft:milk_bucket"));
            net.minecraft.world.item.Item honey = net.minecraft.world.item.Items.f_42787_;
            // 牛奶优先（全解）——前提：主人身上没有增益效果
            if (milk != null && !this.ownerHasBeneficialEffect(owner)) {
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
                handHoney.m_41774_(1);
                if (com.maidsmart.action.EmotionalActionExecutor.feedFoodDirect(maid, owner, handHoney)) {
                    maid.getChatBubbleManager().addTextChatBubble("主人，蜂蜜喝下，解一下中毒！");
                    return true;
                }
                net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(inv, handHoney, false);
                return false;
            }
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (stack.m_41619_() || stack.m_41720_() != honey) {
                    continue;
                }
                inv.extractItem(i, 1, false);
                if (com.maidsmart.action.EmotionalActionExecutor.feedFoodDirect(maid, owner, stack)) {
                    maid.getChatBubbleManager().addTextChatBubble("主人，蜂蜜喝下，解一下中毒！");
                    return true;
                }
                net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(inv, stack, false);
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
