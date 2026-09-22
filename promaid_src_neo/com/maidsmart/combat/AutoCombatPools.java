package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.api.task.FunctionCallSwitchResult;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.maidsmart.config.MaidSmartConfig;
import com.maidsmart.task.MaidWorkTags;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.items.IItemHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 主动参战——任务池、战术重调与武器就绪（v1.2.4 从 AutoCombatSwitch 拆出）。
 * 
 * 按威胁距离与武器就绪度组池抽签（近战/远程权重）、模组任务与原生任务的让位、
 * 战术状态机 TACTIC_STATE 的重调、标记清理与战后恢复原任务。
 * 全部是 static，原类保留同签名转发。
 */
public final class AutoCombatPools {
    private AutoCombatPools() {
    }

    static IMaidTask pickCombatTask(EntityMaid maid) {
        TaskPools pools = buildPools(maid);
        // 两类都有 → 按最近敌人距离+偏好权重选池；只有一类 → 直接用
        if (!pools.meleePool().isEmpty() && !pools.rangedPool().isEmpty()) {
            double dist = AutoCombatTargeting.nearestThreatDist(maid);
            boolean useMelee;
            if (dist >= 0 && dist <= MELEE_RANGE) {
                // v1.1.0 实测五十八：近身两池皆可用 → 按 近战:远程 偏好权重随机选池
                //（默认 3:1 ≈ 75% 近战；某类权重 0 = 永不主动选该类；双 0 → 远程兜底）
                double mw = Math.max(0, MaidSmartConfig.COMBAT_PREF_MELEE_WEIGHT.get());
                double rw = Math.max(0, MaidSmartConfig.COMBAT_PREF_RANGED_WEIGHT.get());
                useMelee = mw + rw > 0 && AutoCombatSwitch.RNG.nextDouble() * (mw + rw) < mw;
            } else {
                // 远距离（近战够不着）/ 找不到敌人（威胁消失边缘）→ 远程（实测三十八口径）
                useMelee = false;
            }
            com.mojang.logging.LogUtils.getLogger().info(
                    "auto-combat pick: maid={} both-pools dist={} -> {}",
                    maid.getDisplayName() != null ? maid.getDisplayName().getString() : maid.getUUID(),
                    String.format("%.1f", dist), useMelee ? "melee" : "ranged");
            return useMelee
                    ? weightedPick(pools.meleePool(), pools.meleeWeights())
                    : weightedPick(pools.rangedPool(), pools.rangedWeights());
        }
        if (!pools.meleePool().isEmpty()) {
            return weightedPick(pools.meleePool(), pools.meleeWeights());
        }
        if (!pools.rangedPool().isEmpty()) {
            return weightedPick(pools.rangedPool(), pools.rangedWeights());
        }
        // v1.1.0 实测六十七（反馈："手上完全没有攻击性物品的女仆，就不应该触发自主
        // 战斗，应该维持原任务"）：两池全空 = 主手/背包没有任何攻击任务认的武器
        // → 不参战（返回 null，tryEngageMaid 跳过、维持原任务）；
        // 开关关闭时保留旧行为（空手近战兜底）
        if (!MaidSmartConfig.COMBAT_UNARMED_SKIP.get()) {
            return TaskManager.findTask(ResourceLocation.parse("touhou_little_maid:attack")).orElse(null);
        }
        return null;
    }

    private record TaskPools(List<IMaidTask> meleePool, List<Double> meleeWeights,
                             List<IMaidTask> rangedPool, List<Double> rangedWeights) {
    }

    private static TaskPools buildPools(EntityMaid maid) {
        List<IMaidTask> meleePool = new ArrayList<>();
        List<Double> meleeWeights = new ArrayList<>();
        List<IMaidTask> rangedPool = new ArrayList<>();
        List<Double> rangedWeights = new ArrayList<>();
        // v1.2.2 实测六百二十一：分类表里**点名**写过分类的任务（近战/远程）——这些
        // 不受"模组任务优先让位"影响（玩家点名优先于自动让位，见 CombatModeTable）
        java.util.Set<String> pinnedUids = new java.util.HashSet<>();
        String vanillaNs = "touhou_little_maid";
        double vanillaW = MaidSmartConfig.COMBAT_AUTO_SWITCH_VANILLA_WEIGHT.get();
        double modW = MaidSmartConfig.COMBAT_AUTO_SWITCH_MOD_WEIGHT.get();
        for (IMaidTask task : TaskManager.getTaskIndex()) {
            if (!(task instanceof com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask attack)) {
                continue; // 只认攻击类任务
            }
            // v1.1.0 实测一百七十一：排除【非战斗】的 IAttackTask——TLM 喂动物任务
            // （touhou_little_maid:feed_animal）也实现了 IAttackTask，但喂动物不是
            // 战斗：混进候选池会让女仆战斗中随机切去喂动物（打怪变喂鸡）、还稀释
            // 模组武器权重（"不会拿出拔刀剑"的间接因素）
            try {
                if (task.getUid() != null
                        && "touhou_little_maid:feed_animal".equals(task.getUid().toString())) {
                    continue;
                }
            } catch (Throwable ignored) {
            }
            // v1.2.2 实测五百九十【第三方玩法模式黑名单】：傀儡师（傀儡装配 Modular
            // Golems）不是本模组该碰的战斗模式——自主参战/战中换战术都永不切进去
            //（玩家手动切过去不受影响；届时本模组战术全体让位，见 MaidModeCompat）
            if (com.maidsmart.compat.MaidModeCompat.isBlacklisted(task)) {
                continue;
            }
            // v1.2.2 实测六百二十一【分类表·不参与】：玩家在「战斗模式分类表」里点名
            // 写了「不参与」的任务，入战选任务与战中换战术（两者共用本池）都永不选它。
            // 只影响**自动切换**——玩家在 TLM 面板手动把她切过去完全不受影响。
            // 位置在黑名单之后：黑名单是"本模组不该碰的模式"（第三方玩法优先），
            // 表里写"参与"也进不来，两者不冲突。
            String modeUid = task.getUid() == null ? null : task.getUid().toString();
            if (CombatModeTable.blocksAutoSwitch(modeUid)) {
                continue;
            }
            // v1.2.0：飞行作战【不响应自主切换】——它是玩家手动指定的作战模式（需鞘翅+
            // 重锤+烟花三件齐备才激活），主人被打时不应被自动切进来；已在飞行作战的
            // 女仆也不应被自动切走。本判定同时挡住"入战选任务"与"战中换战术"两条
            // 路径（它们共用本池）。按 UID 精确排除。
            try {
                if (task.getUid() != null
                        && com.maidsmart.combat.MaidFlightKit.isFlightUid(task.getUid())) {
                    continue;
                }
            } catch (Throwable ignored) {
            }
            try {
                if (task.isHidden(maid)) { // isHidden——隐藏任务不进候选（接口方法名编译期已实证（TLM jar 未混淆该方法））
                    continue;
                }
            } catch (Throwable ignored) {
            }
            // 背包/主手有该任务认的武器才算候选（isWeapon 是 IAttackTask 的默认方法：
            // 原版任务按武器类型判；模组任务自定义判定——法书/史诗武器等）
            if (!hasWeaponForTask(maid, attack)) {
                continue;
            }
            // v1.2.0 实测五百五十九【法术任务进池收紧】：万法皆通的法术任务 isWeapon 恒 true
            // （javap 实证），旧版只要求"背包里有任意一件非原版物品"就让它们进池
            //（实测三百七十九的兜底）——那等于"带个模组食物也算会用魔法"。现在要求她
            // **真的带着法术装备**（法术书/法器在主手、副手、背包或饰品栏，或者附属数据里
            // 已经有她的法术书）才允许进池；不满足 → 这个任务不进任何池。
            if (com.maidsmart.combat.MaidSpellCompat.isSpellTask(attack)
                    && !com.maidsmart.combat.MaidSpellCompat.maidHasSpells(maid)) {
                continue;
            }
            // v1.1.0 实测三百七十九【模组物品背书】（反馈："为啥自主战斗老喜欢切换
            // 到魔法？明明我只给了原版武器"）：万法皆通 SpellCombatMeleeTask.isWeapon
            // 恒 true（javap 反汇编实证）——背包里任何物品（原版剑/食物都行）都被
            // 认作"魔法武器" → 模组任务凭空进池 + 模组让位规则（实测一百八十一）
            // 把原版任务挤掉 → 只给原版武器的女仆在远距离被切去 spell_combat_far。
            // 修：模组任务必须匹配到【非原版物品】才进池（拔刀剑/史诗战斗武器本就
            // 是模组物品，不受影响；女仆持有万法皆通物品时也照常参与）；配置关 =
            // 模组任务完全不参与自主切换。
            String taskNs = task.getUid().getNamespace();
            if (!vanillaNs.equals(taskNs)) {
                if (!MaidSmartConfig.COMBAT_AUTO_SWITCH_ALLOW_MOD_TASKS.get()) {
                    continue;
                }
                if (!hasNonVanillaWeaponForTask(maid, attack)) {
                    continue;
                }
            }
            // v1.1.0 实测一百二十①【枪械弹药闸】：枪械任务进池必须弹药可用——
            // TLM TaskGunAttack.isWeapon 只查 isGun（javap 实证），有枪没子弹也会
            // 进池被切到 gun_attack → TLM 换弹失败原地干等。hasGunAndAmmo 判定
            // （背包有枪+任意弹药；卓越前线能量武器免弹药），不满足 → 枪械任务
            // 不进任何池（参战选任务/距离切换都不会把她切到打不出伤害的模式）。
            if ("touhou_little_maid:gun_attack".equals(task.getUid().toString())
                    && !com.maidsmart.combat.GunCompat.hasGunAndAmmo(maid)) {
                continue;
            }
            // v1.1.0 实测二十一：权重可配置（原版/模组各一条）——模组默认 2.0 优先、
            // 原版默认 1.0 降半；两条都是权重值（>0），比例决定被选概率
            double w = vanillaNs.equals(task.getUid().getNamespace()) ? vanillaW : modW;
            w = Math.max(0.01, w);
            // v1.2.2 实测六百二十一：分类表点名写过分类的 → 记进 pinned（不被让位挤掉）
            if (CombatModeTable.modeOf(modeUid) != null && modeUid != null) {
                pinnedUids.add(modeUid);
            }
            if (isRangedTask(task)) {
                rangedPool.add(task);
                rangedWeights.add(w);
            } else {
                meleePool.add(task);
                meleeWeights.add(w);
            }
        }
        // v1.1.0 实测一百八十一（反馈："女仆有概率在拿到拔刀剑的时候选择攻击模式，
        // 而不是选择拔刀剑专属的拔刀剑模式。其他模组武器也有可能会出现这样的问题"）：
        // 同池"模组专属任务 vs 原版通用任务"不再加权随机——旧版拔刀剑同时被原版
        // attack 认作武器（拔刀剑物品继承剑类）→ 两任务同池按 原版:模组=1:2 权重
        // 随机 → 1/3 概率落到原版攻击（= 拿着拔刀剑切普通攻击模式的来源；战中
        // 换战术同池随机同理会把拔刀剑换出）。修复：池内有模组任务时原版通用任务
        // 整体让位（模组武器 → 模组模式确定性生效）；原版任务退化为【无模组武器】
        // 时的兜底。权重随机保留在多个模组任务之间（同为专属任务，随机选不退化）。
        // v1.2.2 实测六百二十一【让位改成可控】：①配置里可以整体关掉（关 = 原版与模组
        // 同池纯按权重随机，原版/模组两条权重照旧生效）②分类表里**点名写过分类**的
        // 任务不参与让位（玩家点名优先于自动让位——否则"我把这个模组任务写成远程"
        // 会被让位规则反手踢掉，等于表没用）。
        if (CombatModeTable.vanillaYieldToMod()) {
            vanillaYieldToMod(meleePool, meleeWeights, vanillaNs, pinnedUids);
            vanillaYieldToMod(rangedPool, rangedWeights, vanillaNs, pinnedUids);
        }
        // v1.1.0 实测一百六十九：候选池内容诊断（每 5 秒/女仆一条，latest.log 搜
        // "combat pools"）——确认模组武器任务（ef_tlm/拔刀剑/truepower 等）有没有进池；
        // 池里只有原版任务 = 模组武器没被 isWeapon 认到；池里有模组任务 = 权重随机问题
        try {
            long nowT = maid.level().getGameTime();
            Long poolLast = AutoCombatSwitch.POOL_DIAG_SINCE.get(maid.getUUID());
            if (poolLast == null || nowT - poolLast >= 100L) {
                AutoCombatSwitch.POOL_DIAG_SINCE.put(maid.getUUID(), nowT);
                StringBuilder mp = new StringBuilder();
                for (IMaidTask t : meleePool) {
                    mp.append(t.getUid()).append(',');
                }
                StringBuilder rp = new StringBuilder();
                for (IMaidTask t : rangedPool) {
                    rp.append(t.getUid()).append(',');
                }
                // v1.2.4 实测六百二十五：池里有法术任务时，把"是哪件东西让她算会用法术"
                // 一并打出来——专门回答"她明明没有法术书，为什么会被切去法术"。
                String gear = "-";
                if (containsSpellTask(meleePool) || containsSpellTask(rangedPool)) {
                    String d = com.maidsmart.combat.MaidSpellCompat.spellGearDetail(maid);
                    gear = d == null ? "无" : d;
                }
                com.mojang.logging.LogUtils.getLogger().info(
                        "combat pools: maid={} melee=[{}] ranged=[{}] spellGear={}",
                        com.maidsmart.tool.PromaidLog.nameOf(maid), mp, rp, gear);
            }
        } catch (Throwable ignored) {
        }
        return new TaskPools(meleePool, meleeWeights, rangedPool, rangedWeights);
    }

    private static void vanillaYieldToMod(List<IMaidTask> pool, List<Double> weights, String vanillaNs,
                                          java.util.Set<String> pinned) {
        boolean hasMod = false;
        for (IMaidTask t : pool) {
            if (t.getUid() != null && !vanillaNs.equals(t.getUid().getNamespace())) {
                hasMod = true;
                break;
            }
        }
        if (!hasMod) {
            return;
        }
        for (int i = pool.size() - 1; i >= 0; i--) {
            IMaidTask t = pool.get(i);
            if (t.getUid() == null || vanillaNs.equals(t.getUid().getNamespace())) {
                // v1.2.2 实测六百二十一：表里点过名的不踢（点名优先）
                if (t.getUid() != null && pinned.contains(t.getUid().toString())) {
                    continue;
                }
                pool.remove(i);
                weights.remove(i);
            }
        }
    }

    /** 池里有没有法术任务（万法皆通）——诊断用，见 buildPools 里 combat pools 那条日志 */
    private static boolean containsSpellTask(List<IMaidTask> pool) {
        for (IMaidTask t : pool) {
            if (com.maidsmart.combat.MaidSpellCompat.isSpellTask(t)) {
                return true;
            }
        }
        return false;
    }

    static final double JUMP_UNREACHABLE_DIST = 6.0;

    static final double TARGETING_RANGE = 16.0;

    private record TacticState(long lastSwitchTick, long holdUntil, long cooldownUntil, String fromUid) {
    }

    private static final java.util.Map<java.util.UUID, TacticState> TACTIC_STATE = new java.util.HashMap<>();

    static void retuneCombatTactics(EntityMaid maid) {
        IMaidTask cur = maid.getTask();
        if (cur == null) {
            return;
        }
        // v1.1.0 实测一百零七（反馈："女仆不会自己的近远战切换"）：旧版只允许
        // touhou_little_maid 命名空间任务参与近远程切换，模组任务（拔刀剑/弹幕/御币等）
        // 永远被排除——即使女仆拿着弓站在远处也只会傻站着近战。修复：改为
        // IAttackTask 实例即可参与切换（与 pickCombatTask/buildPools 同口径）。
        if (!(cur instanceof com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask)) {
            return;
        }
        // v1.2.0：飞行作战【不参与近远距离换战术】——它是玩家手动指定的模式
        // （需鞘翅+重锤+烟花三件齐备），换战术把她切去别的战斗任务等于把玩家的
        // 选择顶掉，且滑翔状态会被 setTask 打断。彻底不评估（buildPools 的排除只
        // 挡住"切进来"，挡不住"从这里切出去"，必须在此显式提前返回）。
        try {
            if (com.maidsmart.combat.MaidFlightKit.isFlightUid(cur.getUid())) {
                return;
            }
        } catch (Throwable ignored) {
        }
        // v1.1.0 实测六十一：最短持有 / 反向横跳冷却（防抖三件套之二）
        long now = maid.level().getGameTime();
        TacticState st = TACTIC_STATE.get(maid.getUUID());
        if (st != null) {
            if (now < st.holdUntil()) {
                return; // 刚换过战术，持有期内不再评估
            }
            if (now < st.cooldownUntil()) {
                return; // 横跳冷却中，保持当前战术硬打
            }
        }
        boolean curRanged = isRangedTask(cur);
        double dist = AutoCombatTargeting.nearestThreatDist(maid);
        if (dist < 0) {
            return; // 扫不到敌人（威胁半径外的残余判定），不动
        }
        boolean wantMelee;
        if (curRanged) {
            if (dist > MELEE_RANGE) {
                return; // 还没被近身，远程继续输出
            }
            // v1.1.0 实测五十八：近战偏好权重 0 = 玩家不要近战——被近身也不切，
            // 保持远程硬打（近身反击击退机制兜底）
            if (MaidSmartConfig.COMBAT_PREF_MELEE_WEIGHT.get() <= 0) {
                return;
            }
            wantMelee = true;
        } else {
            if (dist <= JUMP_UNREACHABLE_DIST || dist > TARGETING_RANGE) {
                return; // 追得上（跳跃+贴身可达）或超出索敌范围，维持近战
            }
            // v1.1.0 实测五十八：远程偏好权重 0 = 玩家不要远程——够不着也保持近战追击
            if (MaidSmartConfig.COMBAT_PREF_RANGED_WEIGHT.get() <= 0) {
                return;
            }
            wantMelee = false;
        }
        TaskPools pools = buildPools(maid);
        IMaidTask next = wantMelee
                ? (pools.meleePool().isEmpty() ? null : weightedPick(pools.meleePool(), pools.meleeWeights()))
                : (pools.rangedPool().isEmpty() ? null : weightedPick(pools.rangedPool(), pools.rangedWeights()));
        if (next == null || next.getUid().equals(cur.getUid())) {
            return; // 没有对应武器的任务可换 / 选中的就是当前任务
        }
        // v1.1.0 实测一百四十八：换战术前预检 + 自动装备（同参战入口）——装不上
        // （MISSING_REQUIRED_ITEM）就不切，保持现状（模组武器判定走 isWeaponCap 兼容）
        if (CombatTaskCompat.prepareSwitch(maid, next)
                == FunctionCallSwitchResult.MISSING_REQUIRED_ITEM) {
            return;
        }
        // v1.1.0 实测六十一：反向抑制——刚从 fromUid 换到当前任务，窗口内又想换回去
        // = 来回横跳，拒绝本次切换并进入冷却期
        if (st != null && !st.fromUid().isEmpty() && st.fromUid().equals(next.getUid().toString())
                && now - st.lastSwitchTick() <= MaidSmartConfig.COMBAT_REVERSE_WINDOW_TICKS.get()) {
            long cd = MaidSmartConfig.COMBAT_REVERSE_COOLDOWN_TICKS.get();
            if (cd > 0) {
                TACTIC_STATE.put(maid.getUUID(), new TacticState(st.lastSwitchTick(), 0, now + cd, ""));
                com.mojang.logging.LogUtils.getLogger().info(
                        "auto-combat retune: maid={} reverse {}->{} suppressed, cooldown {} ticks",
                        maid.getDisplayName() != null ? maid.getDisplayName().getString() : maid.getUUID(),
                        cur.getUid(), next.getUid(), cd);
            }
            return;
        }
        // v1.1.0 实测一百三十六：战斗换战术是自动系统——打内部标记放行
        com.maidsmart.schedule.ScheduleSwitchGuard.runInternal(maid.getUUID(),
                next.getUid(), () -> maid.setTask(next));
        // 兼容关键：同步指派标记（见方法注释），否则还原链路误判"玩家接管"
        ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().putString(AutoCombatSwitch.ASSIGNED_TAG, next.getUid().toString());
        // 记录稳定状态：最短持有 + 来源任务（反向判定用）
        TACTIC_STATE.put(maid.getUUID(), new TacticState(now,
                now + MaidSmartConfig.COMBAT_TACTIC_HOLD_TICKS.get(), 0, cur.getUid().toString()));
        com.mojang.logging.LogUtils.getLogger().info(
                "auto-combat retune: maid={} {} -> {} (dist={})",
                maid.getDisplayName() != null ? maid.getDisplayName().getString() : maid.getUUID(),
                cur.getUid(), next.getUid(), String.format("%.1f", dist));
    }

    private static final double MELEE_RANGE = 5.0;

    private static IMaidTask weightedPick(List<IMaidTask> pool, List<Double> weights) {
        double total = 0;
        for (double w : weights) {
            total += w;
        }
        double roll = AutoCombatSwitch.RNG.nextDouble() * total;
        for (int i = 0; i < pool.size(); i++) {
            roll -= weights.get(i);
            if (roll <= 0) {
                return pool.get(i);
            }
        }
        return pool.get(pool.size() - 1);
    }

    public static boolean isRangedTask(IMaidTask task) {
        try {
            Boolean override = CombatModeTable.rangedOverride(task.getUid().toString());
            if (override != null) {
                return override;
            }
        } catch (Throwable ignored) {
        }
        return defaultRangedByUid(task);
    }

    public static boolean defaultRangedByUid(IMaidTask task) {
        String uid = task.getUid().toString();
        // 原版远程五件套（弓/弩/三叉戟/弹幕/枪械）——近战 attack 不在表里
        if (uid.equals("touhou_little_maid:ranged_attack")
                || uid.equals("touhou_little_maid:crossbow_attack")
                || uid.equals("touhou_little_maid:trident_attack")
                || uid.equals("touhou_little_maid:danmaku_attack")
                || uid.equals("touhou_little_maid:gun_attack")) {
            return true;
        }
        String ns = task.getUid().getNamespace();
        // v1.1.0 实测一百零七：模组远程任务识别——法术系/投射系按远程处理
        if (ns.equals("maidspell") || ns.equals("spellbook")) {
            return true;
        }
        // v1.1.0 实测一百零七：模组远程任务——UID 包含 ranged/gun/danmaku/spell 关键词
        String uidLower = uid.toLowerCase();
        if (uidLower.contains("ranged") || uidLower.contains("gun")
                || uidLower.contains("danmaku") || uidLower.contains("spell")
                || uidLower.contains("crossbow") || uidLower.contains("trident")
                || uidLower.contains("bow")) {
            return true;
        }
        // 明确的近战模组（命名空间=各模组实际 modid，1.21.1 实装 jar 实证：
        // 车万女仆：真正的力量 modid = true_power_of_maid，TaskSlashBlade uid =
        // true_power_of_maid:slashblade_attack；ef_tlm=史诗战斗联动任务、
        // slashblade/sbr_core=拔刀剑重锋 + Slashblade Core）
        if (ns.equals("ef_tlm") || ns.equals("slashblade") || ns.equals("sbr_core")
                || ns.equals("truepower") || ns.equals("true_power_of_maid")) {
            return false;
        }
        // 未知模组任务默认近战（冲脸兜底）
        return false;
    }

    static boolean hasWeaponForTask(EntityMaid maid, IMaidTask task) {
        // v1.1.0 实测六十八（反馈："拿斧子的女仆被切到三叉戟模式无法攻击"）：
        // 旧版异常兜底是【整个方法级】的——任何物品的 isWeapon 抛异常就让整个
        // 方法 return true，该任务无凭无据进候选池（三叉戟任务就是这样混进去的，
        // 没三叉戟的女仆切过去根本无法攻击，怪杀不掉威胁不消失也永远不还原）。
        // 改为【逐物品】安全判定：单件物品判定异常只跳过该件，绝不放行整个任务。
        try {
            ItemStack main = maid.getMainHandItem();
            if (!main.isEmpty() && isWeaponSafe(task, maid, main)) {
                return true;
            }
            IItemHandler inv = maid.getAvailableBackpackInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (!s.isEmpty() && isWeaponSafe(task, maid, s)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false; // 背包遍历本身异常 → 视为无武器（与「空手不参战」同口径）
        }
    }

    private static boolean isWeaponSafe(IMaidTask task, EntityMaid maid, ItemStack s) {
        return com.maidsmart.combat.CombatTaskCompat.isWeapon(maid, task, s);
    }

    private static boolean hasNonVanillaWeaponForTask(EntityMaid maid, IMaidTask task) {
        try {
            ItemStack main = maid.getMainHandItem();
            if (!main.isEmpty() && isWeaponSafe(task, maid, main) && !isVanillaItem(main)) {
                return true;
            }
            IItemHandler inv = maid.getAvailableBackpackInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (!s.isEmpty() && isWeaponSafe(task, maid, s) && !isVanillaItem(s)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isVanillaItem(ItemStack s) {
        try {
            net.minecraft.resources.ResourceLocation key =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem());
            return key != null && "minecraft".equals(key.getNamespace());
        } catch (Exception e) {
            return false; // 注册名异常按非原版处理（模组侧自定义注册）
        }
    }

    private static boolean hasAttackDamage(ItemStack stack) {
        try {
            return stack.getOrDefault(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS,
                    net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY).modifiers().stream()
            .anyMatch(en -> en.attribute().is(Attributes.ATTACK_DAMAGE));
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isAutoCombatActive(EntityMaid maid) {
        return ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getBoolean(AutoCombatSwitch.COMBAT_ACTIVE_TAG);
    }

    public static boolean isTaskAutoAssigned(EntityMaid maid) {
        try {
            IMaidTask task = maid.getTask();
            if (task == null || task.getUid() == null) {
                return false;
            }
            String assigned = ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getString(AutoCombatSwitch.ASSIGNED_TAG);
            return !assigned.isEmpty() && assigned.equals(task.getUid().toString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isReallyCombatActive(EntityMaid maid) {
        if (!((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getBoolean(AutoCombatSwitch.COMBAT_ACTIVE_TAG)) {
            return false;
        }
        IMaidTask task = maid.getTask();
        if (com.maidsmart.schedule.ScheduleData.isOn(maid)) {
            String assigned = ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getString(AutoCombatSwitch.ASSIGNED_TAG);
            return !assigned.isEmpty() && task != null
                    && assigned.equals(task.getUid().toString());
        }
        return isAssignedOrCombatTask(maid, task);
    }

    public static boolean isRealCombatActive(EntityMaid maid) {
        try {
            if (!((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getBoolean(AutoCombatSwitch.COMBAT_ACTIVE_TAG)) {
                return false;
            }
            String assigned = ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getString(AutoCombatSwitch.ASSIGNED_TAG);
            var task = maid.getTask();
            boolean real = !assigned.isEmpty() && task != null
                    && assigned.equals(task.getUid().toString());
            if (!real) {
                clearMarkersForExternal(maid); // 残留 → 清掉，让外部系统正常应用
            }
            return real;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void clearMarkersForExternal(EntityMaid maid) {
        try {
            clearMarkers(maid);
            restorePrevMode(maid);
        } catch (Throwable ignored) {
        }
    }

    static void clearMarkers(EntityMaid maid) {
        net.minecraft.nbt.CompoundTag nbt = ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData();
        nbt.remove(AutoCombatSwitch.COMBAT_ACTIVE_TAG);
        nbt.remove(AutoCombatSwitch.PREV_TASK_TAG);
        nbt.remove(AutoCombatSwitch.LAST_THREAT_TAG);
        nbt.remove(AutoCombatSwitch.ASSIGNED_TAG);
        nbt.remove(AutoCombatSwitch.LAST_CONTACT_TAG);
        nbt.remove(AutoCombatSwitch.COMBAT_START_TAG);
        nbt.remove(AutoCombatSwitch.ATTACKER_UUID_TAG);
        nbt.remove(AutoCombatSwitch.ATTACKER_TIME_TAG);
        nbt.remove(AutoCombatSwitch.COMBAT_PREV_HOME_TAG);
        nbt.remove(AutoCombatSwitch.COMBAT_PREV_SCHEDULE_TAG);
        AutoCombatSwitch.RESTORE_DIAG_SINCE.remove(maid.getUUID());
        TACTIC_STATE.remove(maid.getUUID());
    }

    static boolean isAssignedOrCombatTask(EntityMaid maid, IMaidTask task) {
        String assigned = ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getString(AutoCombatSwitch.ASSIGNED_TAG);
        if (assigned.isEmpty() || task == null) {
            return false;
        }
        if (assigned.equals(task.getUid().toString())) {
            return true;
        }
        return task instanceof com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask;
    }

    static boolean isIdleReadingTask(IMaidTask task) {
        return task == null || task.getUid() == null
                || "touhou_little_maid:idle".equals(task.getUid().toString());
    }

    static void restorePrevMode(EntityMaid maid) {
        try {
            if (!com.maidsmart.schedule.ScheduleData.isOn(maid)) {
                maid.setHomeModeEnable(((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getBoolean(AutoCombatSwitch.COMBAT_PREV_HOME_TAG));
                String sched = ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getString(AutoCombatSwitch.COMBAT_PREV_SCHEDULE_TAG);
                if (!sched.isEmpty()) {
                    for (MaidSchedule ms : MaidSchedule.values()) {
                        if (ms.name().equals(sched)) {
                            maid.setSchedule(ms);
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    static String resolvePrevTaskUid(EntityMaid maid) {
        if (maid.getTask() != null && maid.getTask().getUid() != null
                && !"touhou_little_maid:idle".equals(maid.getTask().getUid().toString())) {
            return maid.getTask().getUid().toString();
        }
        try {
            if (com.maidsmart.schedule.ScheduleData.isOn(maid)
                    && maid.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                var segs = com.maidsmart.schedule.ScheduleData.load(maid);
                if (!segs.isEmpty()) {
                    var seg = com.maidsmart.schedule.ScheduleData.segmentAt(segs,
                            com.maidsmart.schedule.ScheduleData.currentMinute(sl));
                    if (seg != null && seg.taskUid() != null && !seg.taskUid().isEmpty()
                            && !"touhou_little_maid:idle".equals(seg.taskUid())) {
                        return seg.taskUid();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "touhou_little_maid:idle";
    }
}
