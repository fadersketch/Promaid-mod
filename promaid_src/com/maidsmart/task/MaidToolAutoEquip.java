package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.IItemHandlerModifiable;

import java.util.function.Predicate;

/**
 * 任务工具自动装备（v1.5.90）。
 *
 * 把"挖矿自动装备镐子"的机制推广到原 mod 的全部战斗任务：女仆主手没有当前
 * 任务需要的工具时，自动从背包（getMaidInv）换到主手：
 * - maid_smart:mine（挖矿）→ 镐（挑等级最高的一把：钻石 &gt; 铁 &gt; 石 &gt; 木/金）
 * - touhou_little_maid:attack（攻击）→ 近战武器（带攻击力属性的物品：剑/斧/三叉戟）
 * - touhou_little_maid:ranged_attack（弓）→ 弓
 * - touhou_little_maid:crossbow_attack（弩）→ 弩
 * - touhou_little_maid:trident_attack（三叉戟）→ 三叉戟
 *
 * 【关键修复】v1.5.89 旧版 equipPickaxe 把镐写进了错误的位置：getMaidInv() 是
 * 36 格背包（MaidBackpackHandler），不是主手！女仆的主手/副手是独立的
 * getHandsInvWrapper()（Forge EntityHandsInvWrapper，slot 0 = 主手）。旧版把镐
 * 塞进背包 slot 0，真正的主手依然是空的 → "镐放背包没装备"依旧存在。
 * 本类统一通过 getHandsInvWrapper() 换装，主手原物品放回背包腾出的那格（1:1 互换）。
 */
public final class MaidToolAutoEquip {
    private MaidToolAutoEquip() {
    }

    /** 主手没有当前任务需要的工具时，从背包装备一把；换好/已是合适工具返回 true。
     *  v1.5.140：换武器按【任务词条匹配】——主手物品符合当前任务类型（攻击=近战
     *  武器、弓=弓、弩=弩、三叉戟=三叉戟、弹幕=御币）就不换（玩家战术安排优先）；
     *  不符合词条（攻击模式拿弓 / 弓模式拿剑）→ 从背包挑评分最高的一把换。
     *  v1.5.167：评分统一为武器评分（weaponScore）——DPS（最终结算，算不出则
     *  DPH）> 耐久 > 附魔词条数，所有战斗任务共用。挖矿机制不变
     *  （ensurePickaxeIfEmpty + ensureForTarget 按目标矿升级，评分=耐久>附魔）。 */
    public static boolean ensureForTask(EntityMaid maid) {
        try {
            ResourceLocation uid = maid.getTask().getUid();
            // v1.5.99c：SRG 实测（javap 字节码）：m_135827_ = getNamespace（返回
            // 构造器参数 1 存入的 f_135804_），m_135815_ = getPath。旧版颠倒 →
            // ns 拿到 path、path 拿到 namespace → 任何任务都匹配不上 → 自动装备
            // 从未生效（"挖矿不装备镐/攻击不装备武器"的另一重根因）。
            String ns = uid.m_135827_(); // getNamespace（SRG）
            String path = uid.m_135815_(); // getPath（SRG）
            Predicate<ItemStack> need;
            java.util.function.ToLongFunction<ItemStack> scorer;
            if ("maid_smart".equals(ns) && "mine".equals(path)) {
                // v1.5.109：挖矿【只保证主手有镐】——空手/非镐才从背包装一把，绝不追求
                // 背包最高级（无条件切最高级 = 用户反馈的换镐问题，由每 tick 的
                // MaidToolAutoEquipBehavior 反复触发）。按目标矿换镐交给
                // MaidMineBehavior.ensureForTarget：手中够用不换、不够才换能挖的。
                // v1.5.172：挖矿【不再任务级自动换镐】——用户反馈"一切换到挖矿模式
                // 就判定换镐太心急"：换镐完全改为【发现矿石后】由
                // MaidMineBehavior.ensureForTarget 判定（找到目标矿时检查手中镐能否
                // 挖，不够才从背包装备能挖的）；空手状态等发现矿石再说
                return false;
            } else if ("touhou_little_maid".equals(ns)) {
                switch (path) {
                    case "attack" -> {
                        need = MaidToolAutoEquip::isMeleeWeapon;
                        // v1.5.167：近战评分 = 武器评分（DPS>耐久>附魔）——旧版
                        // meleeScore 只看攻击力（满耐久无附魔的剑压过半耐久锋利剑）。
                        scorer = MaidToolAutoEquip::weaponScore;
                    }
                    case "ranged_attack" -> {
                        need = s -> s.m_41720_() instanceof BowItem;
                        scorer = MaidToolAutoEquip::weaponScore;
                    }
                    case "crossbow_attack" -> {
                        need = s -> s.m_41720_() instanceof CrossbowItem;
                        scorer = MaidToolAutoEquip::weaponScore;
                    }
                    case "trident_attack" -> {
                        need = s -> s.m_41720_() instanceof TridentItem;
                        scorer = MaidToolAutoEquip::weaponScore;
                    }
                    case "danmaku_attack" -> {
                        // v1.5.166：弹幕任务自动装备御币——主手不是御币就从背包
                        // 掏一把（旧版 default 直接 return false：弹幕女仆空手/拿剑
                        // 时永远不掏出御币，弹幕任务形同虚设）
                        need = com.github.tartaricacid.touhoulittlemaid.item.ItemHakureiGohei::isGohei;
                        scorer = MaidToolAutoEquip::weaponScore;
                    }
                    case "gun_attack" -> {
                        // v1.1.0：枪械任务（TACZ/卓越前线）自动装枪——主手不是枪就从
                        // 背包掏一把（开枪/换弹由 TLM gun_attack 任务负责，这里只管装备）。
                        // 评分 = 武器评分 + 背包有弹药的枪加成（同分时优先弹药充足的枪）
                        final EntityMaid gunMaid = maid;
                        need = com.maidsmart.combat.GunCompat::isGun;
                        scorer = stack -> {
                            long base = weaponScore(stack);
                            return base + (gunHasAmmoInBackpack(gunMaid) ? 1_000_000L : 0L);
                        };
                    }
                    default -> {
                        return false; // 其他任务（待命/工作）不需要工具
                    }
                }
            } else {
                return false;
            }
            // v1.5.140：按任务词条匹配——符合不换，不符合才换（挑最高分）
            return equipIfMismatched(maid, need, scorer);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** v1.5.140：词条匹配——主手物品符合任务类型 → 不换；不符合 → 从背包装备最高分。
     *  v1.5.169：主手符合词条但【即将用坏】（剩余耐久 ≤10%）→ 不算"正在使用"，
     *  自动从背包装备下一把（equip 内部把快坏主手视为零分强制让位）。 */
    private static boolean equipIfMismatched(EntityMaid maid, Predicate<ItemStack> need,
                                             java.util.function.ToLongFunction<ItemStack> scorer) {
        IItemHandlerModifiable hands = (IItemHandlerModifiable) maid.getHandsInvWrapper();
        ItemStack cur = hands.getStackInSlot(0);
        if (!cur.m_41619_() && need.test(cur) && !isNearlyBroken(cur)) {
            return true; // 主手符合词条且未即将用坏 → 不换（玩家战术安排优先）
        }
        return equip(maid, need, scorer);
    }

    /** 交换工具：主手原物品放回背包腾出的那格，工具换到主手。
     *  v1.5.102d：主手已有工具但背包里有【更高级】的同种工具时也会换——
     *  旧版主手是镐就直接 return，导致"石镐装备着、背包里有钻石镐"时遇到
     *  钻石矿直接报"需要钻石镐"而不换装（用户反馈）
     *  v1.5.167：评分按任务传入（scorer）——战斗按武器评分（DPS>耐久>附魔），
     *  挖矿按镐评分（耐久>附魔），彻底分离，互不干扰。
     *  v1.5.168：评分层级调整为 附魔 > 耐久；背包扫描跳过即将用坏的物品
     *  （黑名单保护——剩余耐久 ≤10% 不切换，耐久恢复自动解除）。
     *  v1.5.169：主手即将用坏 → curScore 视为零分（强制让位给背包可用品）；
     *  背包无合格品时保持现状继续用。 */
    private static boolean equip(EntityMaid maid, Predicate<ItemStack> need,
                                 java.util.function.ToLongFunction<ItemStack> scorer) {
        IItemHandlerModifiable hands = (IItemHandlerModifiable) maid.getHandsInvWrapper();
        ItemStack cur = hands.getStackInSlot(0);
        long curScore = (!cur.m_41619_() && need.test(cur) && !isNearlyBroken(cur))
                ? scorer.applyAsLong(cur) : Long.MIN_VALUE;
        // 背包里挑：评分最高的一把（战斗按 DPS>附魔>耐久、镐按 等级对标/质量>附魔>耐久）
        IItemHandlerModifiable inv = maid.getMaidInv();
        int bestSlot = -1;
        long bestScore = Long.MIN_VALUE;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.m_41619_() || !need.test(stack)) {
                continue;
            }
            if (isNearlyBroken(stack)) {
                continue; // v1.5.168：黑名单保护——即将用坏的物品不切（耐久恢复自动解除）
            }
            long score = scorer.applyAsLong(stack);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        if (bestSlot < 0) {
            return !cur.m_41619_() && need.test(cur); // 背包无同种工具：主手已有即算合格
        }
        if (curScore >= bestScore) {
            return true; // 主手已是背包里最高级，零开销
        }
        hands.setStackInSlot(0, inv.getStackInSlot(bestSlot));
        inv.setStackInSlot(bestSlot, cur);
        return true;
    }

    /**
     * v1.5.107：挖矿【按需换镐】——先判目标矿，再按手中镐等级决定是否切换：
     * - 手中镐能挖目标矿且未即将用坏 → 零开销不换（不再无条件切背包最高级镐）——
     *   人工调整不造成影响：玩家亲手放的镐（高耐久/高附魔/高等级都行）
     *   只要能挖目标矿就绝不触发女仆切换
     * - 手中镐能挖但【即将用坏】（≤10%）→ 自动从背包装备下一把能挖的
     * - 手中镐挖不动 → 从背包装备一把【能挖目标】的镐，评分 = 挖掘等级对标
     *   （最低够用的镐最"贴近"该矿物，最高优先）> 附魔词条数 > 剩余耐久；
     *   即将用坏的镐由黑名单保护直接跳过
     * - 手 + 背包都没有能挖的镐 → 返回 false（调用方播报"需要更高镐"）
     * v1.5.168：例——10 把石镐 + 1 把铁镐挖铁矿 → 石镐（tier 1 恰好对标铁矿）
     * 优先于铁镐（tier 2 多出 1 级）；其中附魔的石镐再优先；附魔石镐若只剩
     * 1 点耐久 → 黑名单跳过，换下一把。
     */
    public static boolean ensureForTarget(EntityMaid maid, BlockState target) {
        IItemHandlerModifiable hands = (IItemHandlerModifiable) maid.getHandsInvWrapper();
        ItemStack cur = hands.getStackInSlot(0);
        if (isPickaxe(cur) && canHarvest(cur, target) && !isNearlyBroken(cur)) {
            return true; // 手中够用且未即将用坏，不换
        }
        IItemHandlerModifiable inv = maid.getMaidInv();
        int bestSlot = -1;
        long bestScore = Long.MIN_VALUE;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.m_41619_() || !isPickaxe(stack) || !canHarvest(stack, target)) {
                continue;
            }
            if (isNearlyBroken(stack)) {
                continue; // v1.5.168：黑名单保护——即将用坏的镐不切（耐久恢复自动解除）
            }
            long score = targetPickaxeScore(stack);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        if (bestSlot < 0) {
            return isPickaxe(cur) && canHarvest(cur, target); // 没得换：保持现状
        }
        hands.setStackInSlot(0, inv.getStackInSlot(bestSlot));
        inv.setStackInSlot(bestSlot, cur);
        return true;
    }

    /** 主手或背包中是否有能挖目标矿的镐（findOre 过滤用——手中镐不够但背包有可换镐时不算"挖不动"） */
    public static boolean canHarvestWithHandOrBackpack(EntityMaid maid, BlockState target) {
        try {
            IItemHandlerModifiable hands = (IItemHandlerModifiable) maid.getHandsInvWrapper();
            ItemStack cur = hands.getStackInSlot(0);
            if (isPickaxe(cur) && canHarvest(cur, target)) {
                return true;
            }
            IItemHandlerModifiable inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (!s.m_41619_() && isPickaxe(s) && canHarvest(s, target)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 镐能否挖该方块（DiggerItem.isCorrectToolForDrops，与 MaidMineBehavior.canHarvest 同判据） */
    private static boolean canHarvest(ItemStack stack, BlockState state) {
        if (stack.m_41619_() || !(stack.m_41720_() instanceof net.minecraft.world.item.DiggerItem digger)) {
            return false;
        }
        return digger.m_8096_(state);
    }

    /** v1.1.0：是否为斧（伐木任务用） */
    private static boolean isAxe(ItemStack stack) {
        return !stack.m_41619_() && stack.m_41720_() instanceof net.minecraft.world.item.AxeItem;
    }

    /** v1.1.0：女仆背包是否有任意枪械弹药（枪械评分加成用——弹药充足的枪优先装备） */
    private static boolean gunHasAmmoInBackpack(EntityMaid maid) {
        try {
            IItemHandlerModifiable inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (!s.m_41619_() && com.maidsmart.combat.GunCompat.isAmmo(s)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** v1.1.0：主手或背包中是否有能砍该木材的斧（findWood 过滤用——与镐判定同构） */
    public static boolean canHarvestWoodWithHandOrBackpack(EntityMaid maid, BlockState target) {
        try {
            IItemHandlerModifiable hands = (IItemHandlerModifiable) maid.getHandsInvWrapper();
            ItemStack cur = hands.getStackInSlot(0);
            if (isAxe(cur) && canHarvest(cur, target)) {
                return true;
            }
            IItemHandlerModifiable inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (!s.m_41619_() && isAxe(s) && canHarvest(s, target)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * v1.1.0：伐木【按需换斧】——手持斧能砍目标木材且未即将用坏 → 零开销不换
     * （玩家亲手放的斧只要能砍就绝不触发切换，与挖矿换镐同规则）；
     * 空手/非斧/砍不动 → 从背包装备一把能砍的斧。
     */
    public static boolean ensureAxeForTarget(EntityMaid maid, BlockState target) {
        IItemHandlerModifiable hands = (IItemHandlerModifiable) maid.getHandsInvWrapper();
        ItemStack cur = hands.getStackInSlot(0);
        if (isAxe(cur) && canHarvest(cur, target) && !isNearlyBroken(cur)) {
            return true; // 手中够用且未即将用坏，不换
        }
        IItemHandlerModifiable inv = maid.getMaidInv();
        int bestSlot = -1;
        long bestScore = Long.MIN_VALUE;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.m_41619_() || !isAxe(stack) || !canHarvest(stack, target)) {
                continue;
            }
            if (isNearlyBroken(stack)) {
                continue; // 黑名单保护——即将用坏的斧不切
            }
            long score = targetAxeScore(stack);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        if (bestSlot < 0) {
            return isAxe(cur) && canHarvest(cur, target); // 没得换：保持现状
        }
        hands.setStackInSlot(0, inv.getStackInSlot(bestSlot));
        inv.setStackInSlot(bestSlot, cur);
        return true;
    }

    /** v1.1.0：斧评分（伐木场景）——附魔词条数 > 剩余耐久（斧对木材无挖掘等级差异） */
    private static long targetAxeScore(ItemStack stack) {
        if (stack.m_41619_()) {
            return Long.MIN_VALUE;
        }
        try {
            return enchantCount(stack) * 10_000L + durabilityScore(stack);
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    /**
     * v1.5.109：挖矿【只保证主手有镐】——手中已是镐则零开销不换（无论背包有没有
     * 更高级的）；空手/非镐才从背包装一把。按目标矿升级由 ensureForTarget 负责。
     * v1.5.168：空手时没有目标矿可对标 → 按【质量】选：挖掘等级最高 > 附魔 > 耐久
     * （与 ensureForTarget 的"等级对标"评分分开，避免空手拿最低级镐）。
     * v1.5.169：手中镐【即将用坏】→ 自动从背包装备下一把（equip 内部让位）。
     */
    private static boolean ensurePickaxeIfEmpty(EntityMaid maid) {
        IItemHandlerModifiable hands = (IItemHandlerModifiable) maid.getHandsInvWrapper();
        ItemStack cur = hands.getStackInSlot(0);
        if (isPickaxe(cur) && !isNearlyBroken(cur)) {
            return true; // 手中已有镐且未即将用坏 → 不换
        }
        return equip(maid, MaidToolAutoEquip::isPickaxe, MaidToolAutoEquip::pickaxeScore);
    }

    /**
     * v1.5.167：武器评分（战斗任务通用）——严格分层：
     *   ① DPS（最终结算后；算不出 DPS 则用 DPH）② 附魔词条数 ③ 剩余耐久。
     * - DPS = 最终伤害 × 攻速：
     *   最终伤害 = 空手基础 1 + 攻击力属性修饰符 + 附魔最终结算（锋利
     *   1+0.5×(级-1)；亡灵杀手/节肢杀手 2.5×级，三者取最大；火焰附加
     *   4×级 总伤害——1.20.1 原版附魔公式）；
     *   攻速 = 4.0 基础 + 攻速属性修饰符（剑 1.6、斧 1.0、三叉戟 1.1 等）。
     * - 算不出 DPS（无攻击力属性修饰符——弓/弩/纯工具）→ 退化为 DPH：
     *   DPH 同样拿不到 → 0 分段，同白名单内全部 0 分 → 落到附魔/耐久比。
     *   弓弩伤害取决于箭矢与蓄力，无法从武器本身结算，按附魔/耐久择优合理。
     * - 附魔 = 词条数目；耐久 = 剩余耐久比例（无限耐久物品按满 1.0）。
     *   v1.5.168：附魔 > 耐久（用户推翻——即将用坏由黑名单保护兜底，
     *   10% 以上的耐久差异不再压过附魔）。
     * 分层权重：DPS 段 ×1e6（0.001 DPS = 1000 分），附魔段 ×1e4（1 词条 = 1 万分），
     * 耐久段 ×1（千分比）——任意 DPS 差 > 任意附魔差 > 任意耐久差。
     */
    private static long weaponScore(ItemStack stack) {
        if (stack.m_41619_()) {
            return Long.MIN_VALUE;
        }
        try {
            double dmg = 1.0; // 空手基础伤害
            boolean hasDamage = false;
            double speed = 4.0; // 原版基础攻速
            for (net.minecraft.world.entity.ai.attributes.AttributeModifier m :
                    stack.m_41638_(EquipmentSlot.MAINHAND).get(Attributes.f_22281_)) {
                dmg += m.m_22218_(); // 攻击力修饰符累计
                hasDamage = true;
            }
            for (net.minecraft.world.entity.ai.attributes.AttributeModifier m :
                    stack.m_41638_(EquipmentSlot.MAINHAND).get(Attributes.f_22283_)) {
                speed += m.m_22218_(); // 攻速修饰符累计（剑 1.6 / 斧 1.0 / 三叉戟 1.1）
            }
            if (!hasDamage) {
                // 算不出 DPS（无攻击力属性——弓/弩等）→ DPH 也拿不到 → 0 分段，
                // 同白名单内全部 0 分，落到附魔/耐久段比
                return enchantCount(stack) * 10_000L + durabilityScore(stack);
            }
            // 附魔最终结算（1.20.1 原版公式）：
            // 锋利 = 1 + 0.5×(级-1)；亡灵杀手/节肢杀手 = 2.5×级（对特定生物，
            // 取三者最大）；火焰附加 = 4×级 总伤害（每秒 1 点 × 4×级 秒）
            double enchBonus = enchantDamageBonus(stack);
            double dph = dmg + enchBonus;
            // 算不出攻速（无攻速修饰符且速度无效）→ 退化为 DPH
            double dps = speed > 0.05 ? dph * speed : dph;
            long score = (long) Math.floor(dps * 1000.0) * 1_000_000L;
            // v1.5.168：附魔段 1e4 权重 > 耐久段 ×1（黑名单已兜底即将用坏）
            score += enchantCount(stack) * 10_000L + durabilityScore(stack);
            return score;
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    /**
     * v1.5.168：镐评分（有目标矿的对标场景，ensureForTarget 用）——严格分层：
     *   ① 挖掘等级对标：在能挖目标矿的镐里，等级【最低】的最"贴近"该矿物
     *      （恰好够用优先，不浪费高级镐）——例：铁矿前 10 把石镐（tier 1 恰好
     *      对标）+ 1 把铁镐（tier 2 多出 1 级）→ 优先石镐
     *   ② 附魔词条数（附魔石镐优先）
     *   ③ 剩余耐久（附魔相同比耐久）
     *   即将用坏的镐（≤10%）由黑名单保护跳过，不参与评分。
     */
    private static long targetPickaxeScore(ItemStack stack) {
        if (stack.m_41619_()) {
            return Long.MIN_VALUE;
        }
        try {
            long score = (10L - tierLevel(stack)) * 1_000_000L; // 等级越低越贴近
            score += enchantCount(stack) * 10_000L; // 附魔词条数
            score += durabilityScore(stack); // 剩余耐久
            return score;
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    /**
     * v1.5.168：镐评分（空手装备场景，ensurePickaxeIfEmpty 用）——严格分层：
     *   ① 挖掘等级最高（没有目标矿可对标，拿最好的）② 附魔词条数 ③ 剩余耐久。
     */
    private static long pickaxeScore(ItemStack stack) {
        if (stack.m_41619_()) {
            return Long.MIN_VALUE;
        }
        try {
            long score = tierLevel(stack) * 1_000_000L; // 等级越高越好
            score += enchantCount(stack) * 10_000L; // 附魔词条数
            score += durabilityScore(stack); // 剩余耐久
            return score;
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    /** 镐的材质等级（Tier.getLevel：木/金 0、石 1、铁 2、钻石 3、下界合金 4；
     *  非 TieredItem 的 mod 镐保底 1 分——至少与石镐同级，不会因未知等级而
     *  输给原版石镐） */
    private static int tierLevel(ItemStack stack) {
        try {
            if (stack.m_41720_() instanceof net.minecraft.world.item.TieredItem) {
                return ((net.minecraft.world.item.TieredItem) stack.m_41720_()).m_43314_().m_6604_();
            }
        } catch (Exception e) {
            // fallthrough
        }
        return 1;
    }

    /** v1.5.168：即将用坏阈值——剩余耐久 ≤ 总耐久的 10% 视为"即将用坏"（黑名单保护） */
    private static final double BROKEN_SOON_RATIO = 0.1;

    /** v1.5.168：黑名单保护判定——即将用坏的物品不参与任何背包切换选择；
     *  无限耐久物品（maxDamage ≤ 0）永不保护；耐久恢复（>10%）后自动解除
     *  （动态判定，无需持久化）。
     *  v1.5.169：主手正在使用的物品【快坏时】由调用方处理——equipIfMismatched/
     *  ensureForTarget 判主手快坏即触发换装（curScore 视为零分强制让位），
     *  背包无合格品时才保持现状继续用；黑名单只管"不被选中"，不保主手。 */
    private static boolean isNearlyBroken(ItemStack stack) {
        try {
            int max = stack.m_41776_(); // getMaxDamage
            if (max <= 0) {
                return false; // 无限耐久
            }
            return max - stack.m_41773_() <= max * BROKEN_SOON_RATIO;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * v1.5.167 旧 toolScore（耐久>附魔）已被 v1.5.168 拆分为：
     * - targetPickaxeScore（有目标矿：等级对标 > 附魔 > 耐久）
     * - pickaxeScore（空手：等级最高 > 附魔 > 耐久）
     */

    /** 剩余耐久比例（0.0~1.0；不可损坏/无限耐久物品按满 1.0） */
    private static double durabilityRatio(ItemStack stack) {
        try {
            int max = stack.m_41776_(); // getMaxDamage
            if (max <= 0) {
                return 1.0; // 无限耐久（鞘翅等）
            }
            int dmg = stack.m_41773_(); // getDamageValue
            return Math.max(0.0, Math.min(1.0, 1.0 - (double) dmg / (double) max));
        } catch (Exception e) {
            return 0.0;
        }
    }

    /** 耐久层分数（0~1000，千分比） */
    private static long durabilityScore(ItemStack stack) {
        return (long) Math.floor(durabilityRatio(stack) * 1000.0);
    }

    /** 附魔 NBT 列表（Enchantments ListTag；无附魔/无 NBT 返回 null。
     *  SRG 实证：m_41783_ = getTag、m_128441_ = contains、m_128437_ = getList） */
    private static net.minecraft.nbt.ListTag enchantList(ItemStack stack) {
        try {
            net.minecraft.nbt.CompoundTag tag = stack.m_41783_(); // getTag
            if (tag == null || !tag.m_128441_("Enchantments")) {
                return null;
            }
            return tag.m_128437_("Enchantments", 9); // getList，类型 9 = ListTag
        } catch (Exception e) {
            return null;
        }
    }

    /** 附魔词条数（Enchantments list 条目数） */
    private static long enchantCount(ItemStack stack) {
        net.minecraft.nbt.ListTag list = enchantList(stack);
        return list == null ? 0 : list.size();
    }

    /** 附魔最终结算加成：锋利/亡灵杀手/节肢杀手（取最大）+ 火焰附加总伤 */
    private static double enchantDamageBonus(ItemStack stack) {
        try {
            double best = 0.0;
            double fire = 0.0;
            net.minecraft.nbt.ListTag list = enchantList(stack);
            if (list == null) {
                return 0.0;
            }
            for (net.minecraft.nbt.Tag tag : list) {
                if (!(tag instanceof net.minecraft.nbt.CompoundTag ct)) {
                    continue;
                }
                String id = ct.m_128461_("id"); // getString（SRG 实证）
                int lvl = ct.m_128451_("lvl"); // getInt（SRG 实证）
                switch (id) {
                    case "minecraft:sharpness" -> best = Math.max(best, 1.0 + 0.5 * Math.max(0, lvl - 1));
                    case "minecraft:smite", "minecraft:bane_of_arthropods" ->
                            best = Math.max(best, 2.5 * lvl);
                    case "minecraft:fire_aspect" -> fire = 4.0 * lvl; // 每秒 1 点 × 4×级 秒
                    default -> {
                    }
                }
            }
            return best + fire;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static boolean isPickaxe(ItemStack stack) {
        return !stack.m_41619_() && stack.m_41720_() instanceof PickaxeItem;
    }

    /** 对齐 TaskAttack.isWeapon：主手装备槽带攻击力属性的物品（剑/斧/三叉戟等）。
     *  v1.5.141：攻击模式排除 镐/弓/弩（用户指定——攻击模式不能拿这三样）；
     *  三叉戟/斧/剑等保留（三叉戟投掷近战双用、斧高伤害）。
     *  v1.5.166：再排除御币——御币是 ProjectileWeaponItem 子类（弹幕武器），
     *  攻击模式下自动装备机制会把御币当"合格近战武器"留下不换，女仆拿着御币
     *  在攻击任务里打不出弹幕（弹幕走弹幕任务），等于拿根弱棍打架。 */
    private static boolean isMeleeWeapon(ItemStack stack) {
        if (stack.m_41619_()) {
            return false;
        }
        net.minecraft.world.item.Item item = stack.m_41720_();
        if (item instanceof PickaxeItem || item instanceof BowItem
                || item instanceof CrossbowItem
                || com.github.tartaricacid.touhoulittlemaid.item.ItemHakureiGohei.isGohei(stack)) {
            return false; // 镐/弓/弩/御币不是近战武器
        }
        return stack.m_41638_(EquipmentSlot.MAINHAND).containsKey(Attributes.f_22281_);
    }

    /**
     * v1.5.142：战斗状态自动装备副手盾牌。
     * - 只在战斗任务（攻击/弓/弩/三叉戟/弹幕）下生效
     * - 副手【空手】时才从背包装盾——副手已有任何非盾物品（包括 mod 的副手
     *   装备）一律不动，尊重玩家/模组的副手搭配
     * - v1.5.169：与主手武器同一规则——副手已装备的盾【即将用坏】（剩余耐久
     *   ≤10%）→ 自动从背包装备下一把；未快坏 → 不触发切换
     * - 盾牌爆掉后副手变空 → 下个 tick 自动补新盾（与换武器同一轮询节奏）
     * - 背包扫描统一走黑名单保护（快坏的盾不装）+ 评分 附魔词条数 > 剩余耐久
     * - 无盾/背包无可用盾 → 零开销返回
     */
    public static void ensureShieldForCombat(EntityMaid maid) {
        try {
            if (!MaidWorkTags.isCombatTask(maid)) {
                return;
            }
            IItemHandlerModifiable hands = (IItemHandlerModifiable) maid.getHandsInvWrapper();
            ItemStack cur = hands.getStackInSlot(1);
            if (!cur.m_41619_() && !(cur.m_41720_() instanceof net.minecraft.world.item.ShieldItem)) {
                return; // 副手已有非盾物品 → 不动（尊重搭配）
            }
            IItemHandlerModifiable inv = maid.getMaidInv();
            int best = -1;
            long bestScore = Long.MIN_VALUE;
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (stack.m_41619_() || !(stack.m_41720_() instanceof net.minecraft.world.item.ShieldItem)) {
                    continue;
                }
                if (isNearlyBroken(stack)) {
                    continue; // v1.5.169：黑名单保护——即将用坏的盾不装
                }
                long score = shieldScore(stack);
                if (score > bestScore) {
                    bestScore = score;
                    best = i;
                }
            }
            if (best < 0) {
                return; // 背包没有可用盾（全是快坏/没有）→ 保持现状
            }
            ItemStack picked = inv.getStackInSlot(best);
            // 副手已是盾且未即将用坏 → 不换（与主手武器同一规则）
            if (!cur.m_41619_() && !isNearlyBroken(cur)) {
                return;
            }
            hands.setStackInSlot(1, picked);
            // 副手原本空 → 背包格直接清空；原本是快坏的盾 → 放回背包
            inv.setStackInSlot(best, cur.m_41619_() ? ItemStack.f_41583_ : cur);
        } catch (Exception ignored) {
        }
    }

    /** v1.5.169：盾牌评分（副手）——附魔词条数 > 剩余耐久（与武器/镐一致） */
    private static long shieldScore(ItemStack stack) {
        if (stack.m_41619_()) {
            return Long.MIN_VALUE;
        }
        try {
            return enchantCount(stack) * 10_000L + durabilityScore(stack);
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }
}
