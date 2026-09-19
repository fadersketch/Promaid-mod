package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.api.task.meal.IMaidMeal;
import com.github.tartaricacid.touhoulittlemaid.api.task.meal.MaidMealType;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.meal.MaidMealManager;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * v1.2.0 实测五百四十五【投喂效果与"女仆自己吃"对齐】。
 *
 * ── 需求原文 ──
 * "我发现女仆支援喂食功能似乎有问题。一个女仆为另一个女仆进胡萝卜的效果没有女仆自己吃强。
 *  喂食的效果应该等同于这个女仆吃食物回血。"
 *
 * ── 根因：TLM 把"吃食物回血"做在【餐食系统】里，而不是 eat() 里 ──
 * 反编译实证（两版同构，`entity/task/meal/DefaultMaidHealSelfMeal`）：
 * <pre>
 *   // 女仆自己吃（由 MaidHealSelfTask 触发）：
 *   float total = nutrition + nutrition * saturationModifier * 2.0f;   // 1.21.1：nutrition + saturation()
 *   if (random.nextInt(5) &lt; total) {                                     // MAX_PROBABILITY = 5
 *       float healCount = Math.max(total / 5.0f, 1.0f);
 *       maid.heal(healCount);
 *   }
 * </pre>
 * 也就是说女仆吃一根胡萝卜（nutrition=3、saturationModifier=0.6）：
 * `total = 3 + 3×0.6×2 = 6.6` → 有 6.6/5 即 **100%** 触发 → 回 `6.6/5 = 1.32` 点血。
 *
 * 而本模组的投喂（`MaidAidOwnerBehavior.feedSisterFood` / `EmotionalActionExecutor.feedFoodDirect`）
 * 走的是**原版 `LivingEntity.m_5584_`（eat）**——那条路只做"食物自带效果 + 音效 + 饱食度"，
 * **完全不经过 TLM 的 meal 系统**，所以 TLM 那一记回血根本不会发生 → 胡萝卜喂出去只回
 * `eat()` 的那点饱食/自带效果，观感就是"喂食比她自己吃弱"。
 *
 * ── 修法：喂食后补调一次 TLM 的 HEAL_MEAL ──
 * 直接复用 TLM 自己的接口（`MaidMealManager.getMaidMeals(HEAL_MEAL)` →
 * `canMaidEat` → `onMaidEat`），于是：
 * <ul>
 *   <li>回血公式、概率、`heal()` 上限、粒子、饰品（bauble）联动**全部与她自己吃逐行同源**
 *       ——TLM 以后改公式我们也自动跟着变，不再有"两套回血口径"；</li>
 *   <li>黑名单也自动一致：`MaidConfig.MAID_HEAL_MEALS_BLOCK_LIST` 与
 *       `MaidMealRegConfigEvent.HEAL_MEAL_REGEX` 里被禁的食物，喂食同样不给回血
 *       （与"她自己不会吃这个"完全同一个判据）。</li>
 * </ul>
 * 调用点放在**原版 eat 之后**：`eat()` 负责食物自带效果/音效/饱食（本模组原有行为不变），
 * 本方法补上 TLM 那记"吃食物回血"。两者叠加才等于"她自己吃一根胡萝卜"的完整体验。
 *
 * ── 为什么不做成"完全走 meal 系统、不调 eat" ──
 * `onMaidEat` 里**不含**原版食物效果路径（饥饿值/中毒解毒等 `FoodProperties` 效果、
 * 音效、以及 DefaultEatenEvent 的容器返还），那是 `eat()` 的职责；TLM 自己也是
 * "`eat` 给饱食与效果 + meal 给回血/好感/粒子"两者并存（见 `MaidHealSelfTask` 与
 * `MaidAfterEatEvent`）。所以正确做法是**两边都调**，而不是二选一。
 *
 * ── 为什么 HEAL_MEAL 不会给"好感度" ──
 * 好感只发生在 WORK_MEAL / HOME_MEAL；HEAL_MEAL 的分支只做回血 + 治疗粒子。
 * 所以投喂**不会**被玩家用来刷好感，与 TLM 自己"低血才吃"的语义一致。
 */
public final class MaidMealBridge {

    /** "TLM 未注册餐食实现"只报一次（见 applySelfEatingEffect 里的说明） */
    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    /**
     * "喂了但物品没被消耗"的本地冷却（键 = 被喂者 UUID|物品 id，值 = 到期毫秒）。
     * 见 {@link #eatByItemLogic} 第三条说明。
     */
    private static final java.util.Map<String, Long> NON_CONSUMED_CD = new java.util.HashMap<>();
    /** 遗物类食物（自己不消耗）的本地冷却：10 秒（与奇异饰品「永恒牛排」默认冷却同量级） */
    private static final long NON_CONSUMED_CD_MS = 10_000L;

    private MaidMealBridge() {
    }

    /**
     * v1.2.2 实测五百七十九【喂食必须走物品自己的 finishUsingItem】——对**真栈**原地结算，
     * 返回真正吃掉的份数。
     *
     * 【反馈】"女仆投喂其她女仆的时候，会把像永恒牛排这样的吃不掉的食物给消耗掉。
     *  理论上我们走的效果是进行一次吃，但其实可能漏掉了什么环节。"
     *
     * 【根因（javap 实证，两版同构）】`Item.finishUsingItem` 的默认实现**就是** eat：
     * <pre>
     *   public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
     *       if (this.isEdible()) { return entity.eat(level, stack); }   // ← 普通食物：与 eat() 完全等价
     *       return stack;
     *   }
     * </pre>
     * 所以对**普通食物**走 eat() 与走 finishUsingItem 没有任何区别；但**重写了
     * finishUsingItem 的物品**只有走物品自己的路径才生效——例：奇异饰品的「永恒牛排」
     * `artifacts:eternal_steak`（`artifacts.item.EverlastingFoodItem`）：
     * <pre>
     *   finishUsingItem(stack, level, entity) {
     *       if (isEdible()) { entity.eat(level, stack.copy());      // 吃的是**副本**
     *                         addCooldown(entity, eatingCooldown); } // 给实体上冷却
     *       return stack;                                           // 原栈原样返回 ⇒ **不消耗**
     *   }
     * </pre>
     * 旧版投喂是"手搓 `shrink(1)` / `extractItem(1)` 再调 `eat()`"——等于把这类
     * "吃完不消失"的遗物硬扣掉了，它的冷却也从来没上过（所以还能被反复喂）。
     *
     * 【本方法的语义】把**真栈**交给物品自己的逻辑，让它决定消耗几个：
     * <ul>
     *   <li>普通食物 → 内部走 `eat()` → 原地减 1（与旧版等效，无回归）；</li>
     *   <li>遗物类 → 不消耗（返回 0），并记一笔**本地冷却**：这类物品自己的冷却通常只对
     *       玩家生效（`Player.getCooldowns()`，`LivingEntity` 没有这个 API），女仆身上读不到，
     *       不记就等着互助链每 3 秒反复触发同一次效果。</li>
     * </ul>
     *
     * @param eater 被喂的那个实体（女仆 / 主人）
     * @param live  真栈——手上或背包槽位里的**那个对象**（不是快照）；本方法会原地改它
     * @return 真正被吃掉的份数（0 = 物品自己的逻辑不消耗）
     */
    public static int eatByItemLogic(net.minecraft.world.entity.LivingEntity eater, ItemStack live) {
        if (eater == null || live == null || live.isEmpty()) {
            return 0;
        }
        try {
            int before = live.getCount();
            // finishUsingItem = Item.finishUsingItem（ItemStack 的同名方法就是转发到它）
            live.getItem().finishUsingItem(live, eater.level(), eater);
            int consumed = Math.max(0, before - live.getCount());
            if (consumed == 0) {
                NON_CONSUMED_CD.put(cdKey(eater, live), System.currentTimeMillis() + NON_CONSUMED_CD_MS);
            }
            return consumed;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 这个食物对"她"是否还在喂食冷却里——**选食物时必须先问这一句**，否则"自己不消耗"的
     * 遗物会被互补链每 3 秒反复触发（物品自己的冷却只对玩家生效，见 {@link #eatByItemLogic}）。
     */
    public static boolean onFeedCooldown(net.minecraft.world.entity.LivingEntity eater, ItemStack stack) {
        if (eater == null || stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            String key = cdKey(eater, stack);
            Long until = NON_CONSUMED_CD.get(key);
            if (until != null) {
                if (until > System.currentTimeMillis()) {
                    return true;
                }
                NON_CONSUMED_CD.remove(key);
            }
            // 玩家侧还有真正的物品冷却（getCooldowns / isOnCooldown）；女仆侧靠上面的本地表
            if (eater instanceof net.minecraft.world.entity.player.Player player) {
                return player.getCooldowns().isOnCooldown(stack.getItem());
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String cdKey(net.minecraft.world.entity.LivingEntity eater, ItemStack stack) {
        String id;
        try {
            net.minecraft.resources.ResourceLocation rl =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            id = rl == null ? String.valueOf(stack.getItem()) : rl.toString();
        } catch (Throwable ignored) {
            id = "?";
        }
        return eater.getUUID() + "|" + id;
    }

    /**
     * 按 TLM 女仆"自己吃"的完整口径结算一次进食：补上餐食系统那一记回血。
     *
     * 【调用时机】必须在**原版 `eat()` 之后**调——见类文档"为什么不做成完全走 meal 系统"。
     *
     * 【幂等说明】本方法只做"补 TLM 那一记"，同一份食物不要重复调用（重复 = 重复回血）。
     * 目前每条投喂分支都只喂一次、喂完即 return，天然满足。
     *
     * @param eater 被投喂的女仆
     * @param food  喂进去的那一份食物（调用方已从背包扣除）
     * @return true = 至少有一份 HEAL_MEAL 处理过它（= TLM 口径的回血已结算）
     */
    public static boolean applySelfEatingEffect(EntityMaid eater, ItemStack food) {
        // 【两树差异（仅限符号名，逻辑逐字相同）】1.21.1 走 Mojmap：
        //   m_41619_() → isEmpty()、m_41786_() → getHoverName()、m_21223_() → getHealth()。
        if (eater == null || food == null || food.isEmpty()) {
            return false;
        }
        try {
            List<IMaidMeal> meals = MaidMealManager.getMaidMeals(MaidMealType.HEAL_MEAL);
            if (meals.isEmpty()) {
                // 【只有这一种情况算异常】TLM 的 MaidMealManager.init() 还没跑（或未来把
                // HEAL_MEAL 注册删了）→ 我们拿不到任何餐食实现，回血补不上。这是"功能
                // 静默失效"，必须留下痕迹（与 实测五百四十五 的根因同一类问题：看不见的
                // 缺失最难排查）。只报一次，不刷屏。
                // 注意：食物被 TLM 黑名单禁掉 **不算** 异常（那是用户配置的正常行为）。
                if (WARNED.compareAndSet(false, true)) {
                    com.maidsmart.tool.PromaidLog.log("投喂回血",
                            "TLM 未注册 HEAL_MEAL 餐食实现，投喂将只走原版进食（不回血）——"
                                    + "请检查 TLM 版本是否 >= 1.5.3");
                }
                return false;
            }
            for (IMaidMeal meal : meals) {
                if (!meal.canMaidEat(eater, food, InteractionHand.MAIN_HAND)) {
                    continue;
                }
                // 【可观测性】这一行是本链路的唯一证据来源：投喂发生在两个女仆之间、
                // 不产生任何原版事件，出问题时（回血没生效 / 被黑名单挡了）只看得到
                // "血量没变"。打一行日志把"谁给谁喂了什么、走了哪个餐食实现"钉住，
                // 与 实测五百四十五 的排查方式一致（先能看见，才谈得上修）。
                float before = eater.getHealth();
                meal.onMaidEat(eater, food, InteractionHand.MAIN_HAND);
                com.maidsmart.tool.PromaidLog.log("投喂回血",
                        com.maidsmart.tool.PromaidLog.nameOf(eater) + " 按 TLM 餐食口径结算 "
                                + food.getHoverName().getString() + "（" + meal.getClass().getSimpleName()
                                + "）：血量 " + String.format("%.2f", before)
                                + " → " + String.format("%.2f", eater.getHealth()));
                return true;
            }
        } catch (Throwable ignored) {
            // TLM 缺这个 API（未来版本改名/移除）→ 退回"只有原版 eat"的旧行为，
            // 绝不因为补回血失败而把整条投喂链路打断（食物已经吃下去了）。
        }
        return false;
    }
}
