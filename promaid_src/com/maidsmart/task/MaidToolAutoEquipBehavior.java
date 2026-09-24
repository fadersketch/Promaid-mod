package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;

import java.util.Collections;

/**
 * 任务工具自动装备行为（v1.5.90）——core 行为，任何 activity 都运行。
 *
 * 在 canUse 里直接执行换工具并返回 false：行为从不占用运行槽位，每 tick 被
 * 调度器评估一次，换完即停，不影响任何其他行为。行为幂等——主手已是合适
 * 工具时零开销（一次物品判断）。覆盖任务：攻击/弓/弩/三叉戟/挖矿（见
 * MaidToolAutoEquip.ensureForTask 的任务 UID 映射）。
 */
public class MaidToolAutoEquipBehavior extends Behavior<EntityMaid> {
    public MaidToolAutoEquipBehavior() {
        super(Collections.emptyMap());
    }

    @Override
    protected boolean m_6114_(ServerLevel level, EntityMaid maid) {
        // v1.2.2 实测五百九十一【炸弹动作的归收入口】：放置/充能/投掷/起爆那一刻副手会短暂举起
        // 正在用的那件（黑曜石 / 末地水晶 / 重生锚 / 床 / TNT，见 BombPose），到点在这里原样还回去——
        // 本行为是 core 行为、任何 activity 都跑、每 tick 一次，所以即使动作链路被强杀（切任务/死亡），
        // 最迟十几 tick 后也会自动还原，不会把盾牌/食物顶掉。无记录时零开销。
        com.maidsmart.combat.BombPose.tick(maid);
        // v1.2.2 实测六百〇八：本句原先在下面"飞行作战任务"分支里（实测五百一十一），
        // 只有空袭/远战任务才跑到 —— 而 六百〇八 的【飞行跟随】根本不是飞行任务，
        // 于是她挂着烟花追主人的整段时间里，副手一直顶着那枚烟花模型收不回来。
        // 挪到 BombPose 旁边这个**无条件区域**：任何玩法（含飞行跟随）、甚至行为被强杀，
        // 最迟 10 tick 都会把盾牌/食物原样换回去。无记录时零开销（先判空 Map）。
        com.maidsmart.combat.FlightFireworkPose.tick(maid);
        // v1.2.2 实测五百九十：傀儡模式（第三方玩法）期间【不自动换装】——她的主手要一直
        // 拿着那个模组的道具（万能手杖），换掉就等于把她的玩法拆了
        if (com.maidsmart.compat.MaidModeCompat.isPuppetMode(maid)) {
            return false;
        }
        // v1.3.0「扫帚模式」：同样【不自动切换武器/盾】——玩家指定什么就是什么（换装由她自己
        // 的激活判定负责"有没有远程武器"，不做主手择优替换）。
        // **刻意不并进下面那个飞行分支**：那个分支会给她穿鞘翅、跑空袭索敌探针与牵引绳
        // （equip / FlightTargetProbe / MaidFlightRecall / notifyNotReady），而扫帚模式
        // 既不背鞘翅也不滑翔——并进去等于凭空给她长出翅膀。这里只要"不换装"这一件事。
        if (com.maidsmart.combat.MaidBroomKit.isBroomTask(maid)) {
            return false;
        }
        // v1.2.0：飞行作战（近战/远战）下【不自动切换武器/盾】——玩家指定什么就是什么；
        // 换装只由 MaidFlightKit.equip 负责"补齐三件套"，不做主手武器择优替换
        if (com.maidsmart.combat.MaidFlightKit.isFlightTask(maid)) {
            // v1.2.0 实测四百九十六【一穿上就渲染鞘翅】：
            // 旧版本分支只提示、把换装完全交给飞行作战行为里的 MaidFlightKit.equip——而那个
            // 行为要【先有攻击目标】才会 tick（currentTarget == null 直接 return）。于是切到
            // 空袭任务后、还没遇到敌人时，鞘翅根本没被穿到胸甲槽，而渲染层的显示条件正是
            // "胸甲槽穿着可用鞘翅"（LayerMaidElytra 判 isUsableElytra(chest)）——玩家看到
            // 的现象就是"必须起飞（=接敌）一次，鞘翅才出现"。反馈原文即此。
            //
            // 修法：在【本 core 行为】里也补齐三件套。本行为任何 activity 都跑、每 tick 一次，
            // 且 equip() 是幂等的（主手已是本任务合法武器、胸甲已有可用鞘翅、且烟花在身上
            // 任意位置时零操作、零开销），所以放在这里是安全的，与行为里的那次调用互不冲突、
            // 重复调用无副作用。
            // v1.2.0 实测五百一十：equip() 已**不再占用副手**（烟花改为按需从背包取用，
            // 发射路径自己造弹体、不需要手持）——副手留给盾牌/食物，幂等性判定从
            // "副手已有烟花"改成了"烟花在身上任意位置"。
            // 这样"切到任务即穿戴整齐、立刻渲染"，与玩家背上鞘翅的表现一致。
            com.maidsmart.combat.MaidFlightKit.equip(maid);
            // v1.2.0 实测五百一十一【放烟花时副手亮一下烟花模型】的**回收入口**：
            // 本行为是 core 行为、任何 activity 都跑、每 tick 一次，所以把"到点归还原副手物品"
            // 放在这里最可靠——即使飞行行为被强杀（切任务/目标丢失/死亡），最迟 10 tick 后
            // 也会自动把盾牌/食物换回去，绝不永久占用副手。无记录时零开销（先判空 Map）。
            // v1.2.2 实测六百〇八：已挪到本方法开头的无条件区域（飞行跟随不是飞行任务，
            // 原先挂在这个分支里她收不回副手）。
            // v1.2.0 实测五百一十五【空袭索敌诊断】：只读探针——空袭任务且当前
            // 没有攻击目标时，按 TLM 原版那条链逐环计数（候选/存活/可攻击/
            // 活动范围内/有视线）并落盘，用来定位"到底哪一环把目标滤掉了"。
            // 挂在本 core 行为上（任何 activity 都跑、每 tick 一次）是为了拿到
            // 真实的排班活动字段——搜索盒子正是按它分流的。有目标时静默。
            com.maidsmart.combat.FlightTargetProbe.tick(maid);
            // v1.2.0 实测五百四十七【空袭牵引绳】：空袭期间以她为圆心、半径 N 格（默认 100，
            // 配置 combat.flightRecallDistance，0=关闭）的球内找不到主人 → 立刻传送回主人身边
            // （与排班表的人工传送同一条强制链路）。
            //
            // 挂在本 core 行为上的理由：① 任何 activity 都跑、每 tick 一次 = "立即"；
            // ② 空袭行为本身要"有攻击目标"才 tick，而本条要管的恰恰是**目标已经死了**之后
            //   "她还挂在几百格高空"的那段（旧版那段谁都不拉她：同维度拉回/跨维度跟随/
            //   TLM 原版 teleportToOwner 三条链路全都主动放行"飞行作战空中"）。
            // 详细口径（为什么不查 home、为什么只在空中生效、为什么跨维度不抢）见
            // MaidFlightRecall 的类注释。
            com.maidsmart.combat.MaidFlightRecall.tick(maid);
            // v1.2.0 实测四百八十八【缺装备提示改为"进入即报"】：本行为是 core 行为
            // （任何 activity 都跑、每 tick 一次），而飞行作战行为要等有敌人才启动——
            // 旧版把提示写在那里面，于是"没有敌人时缺装备永远不报，只有遇到敌人才说一句"
            // （反馈："应该是一进入到这个模式就发现缺乏了然后发一条系统消息"）。
            // 在这里检查 = 切到该任务立刻报；三件齐备时该方法会清冷却（补齐→又缺能立刻再报）。
            com.maidsmart.combat.MaidFlightCombatBehavior.notifyNotReady(
                    maid, level.m_46467_());
            return false;
        }
        MaidToolAutoEquip.ensureForTask(maid);
        // v1.5.142：战斗状态自动装备副手盾牌（空手才换、爆盾自动补）
        MaidToolAutoEquip.ensureShieldForCombat(maid);
        // v1.5.142：建造模式强制坐下（core 行为每 tick 触发——玩家无法让她站起，
        // 建造不再被跟随传送拉走；切任务自动站起）
        com.maidsmart.build.MaidBuildBehavior.tickBuildSit(maid);
        return false; // 永不启动——只在 canUse 里换工具，不占行为槽
    }
}
