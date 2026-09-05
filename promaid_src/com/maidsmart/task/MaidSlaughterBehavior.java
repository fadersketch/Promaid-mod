package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.Animal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 宰杀行为（v1.1.0 实测三百一十一）：女仆检测周围（misc.slaughterRadius，默认 16）
 * 的牲畜（Animal 子类：牛/猪/羊/鸡/兔/山羊等），按 EntityType 分组计数；某组数量
 * 超过面板阈值（misc.slaughterCount，默认 5）时锁定该组随机一只，走过去近身
 * kill（播放挥臂动画——原版 kill 走正常死亡流程，掉落物照常产生）。
 *
 * v1.1.0 实测三百二十五（用户："宰杀的运动逻辑应该和攻击索敌类似"）：
 * 旧版纯站桩（每 tick setStill + 清 WALK_TARGET + 停导航）——牲畜不在身边
 * 永远够不着，日志反复"范围内无牲畜"却从不挪一步。重写为攻击索敌式状态机：
 * - SEEK（无目标）：扫描选目标（超阈值组随机一只），设 WALK_TARGET 追踪
 * - CHASE（有目标）：每 tick 保持追踪目标（EntityTracker 跟随目标移动）；
 *   目标死/超范围/换组 → 回 SEEK
 * - STRIKE（距目标 ≤ 3 格）：kill + 挥臂 + 冷却 3 秒 → 回 SEEK（杀下一只）
 * 站桩标记只在近身宰杀瞬间保持（走位期间必须允许 MoveToTargetSink 寻路）。
 */
public class MaidSlaughterBehavior extends Behavior<EntityMaid> {
    private int cooldown = 0;
    /** v1.1.0 实测三百二十五：当前锁定的宰杀目标（跨 tick 保持——攻击索敌式追踪） */
    private Animal target = null;
    /** 宰杀近身距离（3 格，攻击同款身位） */
    private static final double STRIKE_DIST = 3.0;
    /** v1.1.0 实测三百二十七：全实体诊断节流（无牲畜时每 5 秒打一次范围内实体清单） */
    private int diagCooldown = 0;
    /** v1.1.0 实测三百四十：无目标闲逛换点节流（每 40 tick = 2 秒换一个闲逛点） */
    private int wanderCooldown = 0;

    public MaidSlaughterBehavior() {
        // 无限运行时长（与烧制/酿造同款——行为不因超时重启）
        super(java.util.Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Override
    protected boolean m_6114_(ServerLevel level, EntityMaid maid) {
        boolean ok = isSlaughterTask(maid);
        // v1.1.0 实测三百一十七（排查）：canUse 无论结果都记录——区分"行为没被
        // 评估"（无此日志）与"任务判定失败"（有日志但 ok=false，含当前任务 UID）
        try {
            String cur = maid.getTask() == null ? "null"
                    : (maid.getTask().getUid() == null ? "?" : maid.getTask().getUid().toString());
            com.maidsmart.tool.PromaidLog.log("宰杀",
                    com.maidsmart.tool.PromaidLog.nameOf(maid)
                            + " 行为评估 canUse=" + ok + "（当前任务=" + cur + "）");
        } catch (Throwable ignored) {
        }
        return ok;
    }

    private static boolean isSlaughterTask(EntityMaid maid) {
        return maid.getTask() != null
                && net.minecraft.resources.ResourceLocation.parse("maid_smart:slaughter")
                .equals(maid.getTask().getUid());
    }

    @Override
    protected void m_6735_(ServerLevel level, EntityMaid maid, long gameTime) {
        this.cooldown = 0;
    }

    @Override
    protected void m_6725_(ServerLevel level, EntityMaid maid, long gameTime) {
        // v1.1.0 实测三百二十五：攻击索敌式状态机——SEEK（扫描选目标）/ CHASE
        // （追踪走位）/ STRIKE（近身 kill）。走位期间【不】站桩（MoveToTargetSink
        // 要消费 WALK_TARGET 驱动寻路——旧版每 tick setStill + 清目标 + 停导航，
        // 女仆永远站在原地"够不着"牲畜）。
        // 目标有效性：死了/被移除/超范围（比扫描半径多 2 格容差，防追出圈瞬失效）
        if (this.target != null && (!this.target.m_6084_() || this.target.m_213877_())) {
            this.target = null;
        }
        if (this.target != null
                && this.target.m_20280_(maid) > this.scanRadiusSq(maid) * 1.44) {
            this.target = null; // 追出圈：放弃本目标重新索敌（1.2 倍半径容差）
        }
        // 近身宰杀：距目标 ≤ 3 格 → kill + 挥臂 + 冷却
        if (this.target != null) {
            double distSq = this.target.m_20280_(maid);
            if (distSq <= STRIKE_DIST * STRIKE_DIST) {
                // 近身宰杀瞬间才站桩（防追击移动残留），清掉追踪目标
                MaidWorkTags.setStill(maid, true);
                maid.m_6274_().m_21936_(MemoryModuleType.f_26370_);
                maid.m_21573_().m_26569_();
                if (this.cooldown-- > 0) {
                    return;
                }
                this.cooldown = 60; // 3 秒一只
                com.maidsmart.tool.PromaidLog.log("宰杀",
                        com.maidsmart.tool.PromaidLog.nameOf(maid) + " 宰杀 "
                                + net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES
                                .getKey(this.target.m_6095_()));
                this.target.m_6074_(); // m_6074_ = Entity.kill——正常死亡流程，掉落物照常
                maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND); // 挥臂动画
                this.target = null;
                return;
            }
            // CHASE：保持追踪目标（EntityTracker 跟随目标移动，速度 1.0 攻击同款）
            MaidWorkTags.setStill(maid, false);
            maid.m_6274_().m_21879_(MemoryModuleType.f_26370_,
                    new net.minecraft.world.entity.ai.memory.WalkTarget(
                            new net.minecraft.world.entity.ai.behavior.EntityTracker(this.target, false),
                            1.0f, 1));
            return;
        }
        // SEEK：无目标——冷却节流扫描，选超阈值组随机一只锁定
        if (this.cooldown-- > 0) {
            return;
        }
        this.cooldown = 20; // 扫描间隔 1 秒
        Animal picked = this.scanAndPick(level, maid);
        if (picked != null) {
            this.target = picked;
            MaidWorkTags.setStill(maid, false);
            com.maidsmart.tool.PromaidLog.log("宰杀",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 锁定目标："
                            + net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES
                            .getKey(picked.m_6095_()) + "（追踪走位中）");
        } else {
            // v1.1.0 实测三百四十（用户："女仆在宰杀状态下如果没有需要宰杀的动物，
            // 不应该站在原地不动，而应该四处闲逛"）：无超阈值组 → 不再站桩，改为
            // 四处闲逛——每 2 秒在周围 8 格内随机选一个点设 WALK_TARGET（BlockPosTracker
            // 固定点，MoveToTargetSink 消费驱动寻路），走位期间不站桩；扫到目标立即
            // 打断闲逛去追（下轮 tick 的 SEEK 分支优先）。
            MaidWorkTags.setStill(maid, false);
            if (this.wanderCooldown-- > 0) {
                return;
            }
            this.wanderCooldown = 40; // 2 秒换一个闲逛点
            int r = 8;
            int dx = maid.m_217043_().m_188503_(r * 2 + 1) - r;
            int dz = maid.m_217043_().m_188503_(r * 2 + 1) - r;
            net.minecraft.core.BlockPos base = maid.m_20183_();
            net.minecraft.core.BlockPos spot = new net.minecraft.core.BlockPos(
                    base.m_123341_() + dx, base.m_123342_(), base.m_123343_() + dz);
            maid.m_6274_().m_21879_(MemoryModuleType.f_26370_,
                    new net.minecraft.world.entity.ai.memory.WalkTarget(
                            new net.minecraft.world.entity.ai.behavior.BlockPosTracker(spot),
                            0.7f, 1));
        }
    }

    /** 扫描半径平方（追踪失效判定用） */
    private double scanRadiusSq(EntityMaid maid) {
        int r = com.maidsmart.config.MaidSmartConfig.MISC_SLAUGHTER_RADIUS.get();
        return (double) r * r;
    }

    /**
     * v1.1.0 实测三百三十二（用户："应该直接重写一套检索和判定逻辑"）：宰杀检索
     * 判定重写——旧版过滤链有致命 bug：`a.m_6336_() == MobType.f_21640_` 排除
     * "亡灵马"，但 javap 实证 LivingEntity.m_6336_()（getMobType）默认实现恒返回
     * MobType.f_21640_（UNDEAD）——原版牲畜（牛/羊/猪/鸡/兔/山羊等）都不覆写
     * 该方法 → 全部命中排除 → alive=0 → 永远无目标 → 站桩不动（统计诊断实证：
     * total=45 animal=17 alive=0 kept=0）。重写后的判定链：
     * ① Entity.class 全量扫描（ClassInstanceMultiMap 桶 bug 已绕开，日志实证可靠）
     * ② instanceof Animal（牲畜基类）
     * ③ 存活（isAlive && !isRemoved）
     * ④ 排除驯服宠物（TamableAnimal：狼/猫/鹦鹉——Animal 子类但非牲畜）
     * ⑤ 排除亡灵马（AbstractHorse 且 getMobType==UNDEAD——骷髅马/僵尸马；
     *    普通马 getMobType 返回 UNDEFINED 不排除，马/驴/骡算牲畜）
     * ⑥ 按 EntityType 分组计数，超阈值组随机选一只
     */
    private Animal scanAndPick(ServerLevel level, EntityMaid maid) {
        int radius = com.maidsmart.config.MaidSmartConfig.MISC_SLAUGHTER_RADIUS.get();
        Map<EntityType<?>, List<Animal>> groups = new HashMap<>();
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                maid.m_20185_() - radius, maid.m_20186_() - 4.0, maid.m_20189_() - radius,
                maid.m_20185_() + radius, maid.m_20186_() + 4.0, maid.m_20189_() + radius);
        for (net.minecraft.world.entity.Entity e : level.m_45976_(
                net.minecraft.world.entity.Entity.class, box)) {
            if (!(e instanceof Animal a) || !a.m_6084_() || a.m_213877_()) {
                continue;
            }
            // 排除驯服宠物（狼/猫/鹦鹉——TamableAnimal 是 Animal 子类但非牲畜）
            if (a instanceof net.minecraft.world.entity.TamableAnimal) {
                continue;
            }
            // 排除亡灵马（骷髅马/僵尸马）：AbstractHorse 且 getMobType==UNDEAD。
            // 注意：不能直接判 getMobType==UNDEAD——LivingEntity 默认实现恒返回
            // UNDEAD（javap 实证），原版牲畜全命中；必须限定 AbstractHorse
            if (a instanceof net.minecraft.world.entity.animal.horse.AbstractHorse
                    && a.m_6336_() == net.minecraft.world.entity.MobType.f_21640_) {
                continue;
            }
            groups.computeIfAbsent(a.m_6095_(), k -> new ArrayList<>()).add(a);
        }
        int threshold = com.maidsmart.config.MaidSmartConfig.MISC_SLAUGHTER_COUNT.get();
        if (groups.isEmpty()) {
            com.maidsmart.tool.PromaidLog.log("宰杀",
                    com.maidsmart.tool.PromaidLog.nameOf(maid)
                            + " 范围内无牲畜（半径 " + radius + "，阈值 " + threshold
                            + "，位于 " + maid.m_20185_() + "," + maid.m_20186_() + "," + maid.m_20189_() + "）");
            // v1.1.0 实测三百二十七（用户："不要老是误判生物的位置，明明当时生物
            // 是贴的很近的"）：无牲畜时把扫描框内【全部实体】的类型+坐标+与女仆的
            // 水平距离打出来（节流 5 秒）——直接对出"牛在框外"（位置误判）还是
            // "牛在框内但扫描漏了"（扫描 bug）。框内无任何实体也如实记录。
            if (this.diagCooldown-- <= 0) {
                this.diagCooldown = 100;
                StringBuilder all = new StringBuilder();
                for (net.minecraft.world.entity.Entity e : level.m_45976_(
                        net.minecraft.world.entity.Entity.class, box)) {
                    if (all.length() > 0) {
                        all.append(", ");
                    }
                    net.minecraft.resources.ResourceLocation ek =
                            net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.m_6095_());
                    double dx = e.m_20185_() - maid.m_20185_();
                    double dz = e.m_20189_() - maid.m_20189_();
                    all.append(ek == null ? "?" : ek.m_135815_())
                            .append('@').append((int) e.m_20185_()).append(',').append((int) e.m_20186_())
                            .append(',').append((int) e.m_20189_())
                            .append(" 水平距").append((int) Math.sqrt(dx * dx + dz * dz)).append('格');
                }
                com.maidsmart.tool.PromaidLog.log("宰杀",
                        com.maidsmart.tool.PromaidLog.nameOf(maid)
                                + " 扫描框内实体: " + (all.length() == 0 ? "无" : all.toString()));
            }
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<EntityType<?>, List<Animal>> e : groups.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            net.minecraft.resources.ResourceLocation key =
                    net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getKey());
            sb.append(key == null ? "?" : key.m_135815_()).append("=").append(e.getValue().size());
        }
        com.maidsmart.tool.PromaidLog.log("宰杀",
                com.maidsmart.tool.PromaidLog.nameOf(maid) + " 扫描到牲畜组: " + sb
                        + "（阈值 " + threshold + "）");
        for (Map.Entry<EntityType<?>, List<Animal>> e : groups.entrySet()) {
            if (e.getValue().size() > threshold) {
                List<Animal> list = e.getValue();
                // m_188503_(int) = nextInt(bound)——随机选一只
                return list.get(maid.m_217043_().m_188503_(list.size()));
            }
        }
        com.maidsmart.tool.PromaidLog.log("宰杀",
                com.maidsmart.tool.PromaidLog.nameOf(maid) + " 无超阈值组，本轮不宰杀");
        return null;
    }

    @Override
    protected boolean m_6737_(ServerLevel level, EntityMaid maid, long gameTime) {
        boolean still = isSlaughterTask(maid);
        if (!still) {
            MaidWorkTags.setStill(maid, false);
        }
        return still;
    }

    @Override
    protected void m_6732_(ServerLevel level, EntityMaid maid, long gameTime) {
        MaidWorkTags.setStill(maid, false);
        this.target = null; // v1.1.0 实测三百二十五：行为停止丢弃目标（防残留锁定）
    }
}
