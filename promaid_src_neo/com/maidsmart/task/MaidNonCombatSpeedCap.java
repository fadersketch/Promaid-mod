package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/**
 * 【实测八百一十六：非战斗模式下女仆移速硬上限——不许比玩家快】
 *
 * <p>【
 *
 *
 *
 * <p>【先交代「寻找任务目标」到底走了哪条速度链路——javap 逐层实证】
 * <ol>
 *   <li>Brain 写 {@code WALK_TARGET}（或各任务直接调 {@code PathNavigation.moveTo}）→ 原版
 *       {@code MoveToTargetSink} 把它变成一条 {@code Path}；</li>
 *   <li>{@code PathNavigation} 每 tick 把下一个路径点喂给 {@code MoveControl.setWantedPosition
 *       (x,y,z,speedModifier)}；</li>
 *   <li>TLM 的 {@code MaidMoveControl.tick()} 在 MOVE_TO / STRAFE 分支里算
 *       {@code 速度 = speedModifier × getAttributeValue(MOVEMENT_SPEED)}，然后调
 *       {@code Mob.setSpeed}；</li>
 *   <li>{@code Mob.setSpeed} 只有两行：{@code LivingEntity.setSpeed(value)}（写 speed 字段）
 *       + {@code setZza(value)}（写前向输入 zza）。而 {@code LivingEntity.travel} 用的正是
 *       zza 与那个 speed 字段去算这一 tick 的位移。</li>
 * </ol>
 * 于是结论很硬：<b>女仆靠腿走的一切速度，最后都必然经过 {@code Mob.setSpeed} 这一个口子</b>
 * ——导航走路、直连 moveTo、闲逛 WALK_TARGET、农活、跟随、挖矿赶路，一条都绕不开。
 * （唯二不经过它的是"直接 setDeltaMovement 施加速度矢量"那几处：搭路推一步、自保逃跑、
 * 飞行/扫帚——那些不是"走路"，本项一律不碰。）
 *
 * <p>【为什么"默认就是玩家奔跑速度"】六百二十 在本模组专用服务器上量过一张表（女仆
 * MOVEMENT_SPEED = 0.7，也就是她的裸值）：把 speedModifier 记作倍率，实测巡航格/秒是
 * <pre>
 *   0.3 → 1.9 ｜ 0.4 → 3.3（默认散步）｜ 0.5 → 4.9 ｜ 0.6 → 6.0 ｜ 0.7 → 8.2 ｜ 1.0 → 14
 *   （玩家走路 4.32、奔跑 5.61 格/秒；同一档换地形/机器约 ±20% 波动）
 * </pre>
 * 也就是说 <b>TLM 给女仆的默认倍率（跟随/攻击走位多为 1.0，本模组挖矿赶路是 0.6×1.5=0.9）
 * 实测都在 8~14 格/秒，本来就比玩家奔跑还快</b>——看到的
 * 就是这个数。按这张表插值，<b>倍率 0.56 左右 ≈ 5.6 格/秒 ≈ 玩家奔跑</b>，这就是默认上限
 * 的来源。
 *
 * <p>【所以这一项做什么】非战斗时，把送进 {@code Mob.setSpeed} 的那个数<b>硬钳到不超过
 * {@code misc.nonCombatMaxSpeed}</b>（默认 0.56，单位与「散步速度倍率」/「挖矿移速」同一套；
 * 只往下钳、绝不往上抬）。这是<b>绝对上限</b>——哪怕饰品把她的 MOVEMENT_SPEED 抬高一倍，
 * 钳的是"最终送进去的那个数"，所以她照样不会比玩家快。
 *
 * <p>【什么时候不钳（让位判据，缺一不可）】
 * <ul>
 *   <li><b>战斗</b>：攻击类任务（含第三方 IAttackTask）/ 参战中 / 单兵战术接管——
 *       「非战斗模式下」，战斗要走位躲技能，不能压；</li>
 *   <li><b>自保逃跑</b>（PRESERVE 标记）：低血/逃跑/逃离险境，压速等于送死；</li>
 *   <li><b>飞行 / 扫帚</b>（滑翔位 / 扫帚腾空）：她此刻不靠腿走，压了只会干扰飞行姿态；</li>
 *   <li><b>坐着 / 乘客 / 睡觉</b>：本来就不动或由坐具驱动；</li>
 *   <li><b>无主女仆</b>：与跟随收紧/移动抑制/814 同一条规矩——整合包对野生女仆的规则一律不动。</li>
 * </ul>
 *
 * <p>【开关】{@code misc.nonCombatSpeedCap}（默认开）。关掉 = 完全不碰 {@code setSpeed}，
 * 与旧版逐字节一致（本模组自己的挖矿赶路 1.5×、砍树、宰杀追击等原样恢复）。
 * 数值用 {@code misc.nonCombatMaxSpeed} 调：填大（如 1.0）= 放宽回接近原版；填小 = 更老实。
 */
public final class MaidNonCombatSpeedCap {

    private MaidNonCombatSpeedCap() {
    }

    /**
     * 把一次 {@code Mob.setSpeed} 的值钳到非战斗上限；不需要钳就<b>原值返回</b>
     * （mixin 里 {@code Math.min} 的语义：永远只可能更慢，不会更快）。
     *
     * @param maid  正在被设速的女仆（mixin 已判过 instanceof）
     * @param speed 调用方算出来的值（= speedModifier × MOVEMENT_SPEED）
     * @return 钳过之后的值；不该钳时就是入参本身
     */
    public static float clamp(EntityMaid maid, float speed) {
        try {
            if (!(speed > 0.0f)) {
                return speed; // 0 或负/NaN：不是"走路"，别动（也避免把静止写成别的数）
            }
            if (!shouldCap(maid)) {
                return speed;
            }
            double max = com.maidsmart.config.MaidSmartConfig.MISC_NONCOMBAT_MAX_SPEED.get();
            if (!(max > 0.0)) {
                return speed; // 配成 0 或负数 = 视为关掉上限，不干预
            }
            float capped = (float) Math.min((double) speed, Math.max(0.05, max));
            return capped < speed ? capped : speed;
        } catch (Throwable ignored) {
            return speed; // 任何异常都退化成"不钳"——最坏是没压住，绝不误伤移动
        }
    }

    /** 此刻该不该压她的走路速度（非战斗 + 不坐着/不乘客/不睡/不飞 + 有主）。 */
    public static boolean shouldCap(EntityMaid maid) {
        if (maid == null) {
            return false;
        }
        if (!com.maidsmart.config.MaidSmartConfig.MISC_NONCOMBAT_SPEED_CAP.get()) {
            return false;
        }
        if (!com.maidsmart.tool.MaidScope.owned(maid)) {
            return false; // 无主女仆不干预
        }
        if (maid.isPassenger() || maid.isMaidInSittingPose() || maid.isSleeping()) {
            return false; // 乘客 / 坐姿 / 睡觉：不靠自己的腿走
        }
        if (MaidWorkTags.isAttackTask(maid)) {
            return false; // 战斗任务（含第三方 IAttackTask）：战斗走位不压
        }
        if (com.maidsmart.combat.AutoCombatSwitch.isAutoCombatActive(maid)) {
            return false; // 本系统参战中
        }
        if (com.maidsmart.combat.MaidCombatTacticsBehavior.isActive(maid)) {
            return false; // 单兵战术接管移动
        }
        if (((net.neoforged.neoforge.common.extensions.IEntityExtension) maid)
                .getPersistentData()
                .getBoolean(com.maidsmart.combat.SelfPreservationBehavior.PRESERVE_TAG)) {
            return false; // 自保逃跑：压速等于送死
        }
        if (com.maidsmart.combat.MaidFlightKit.isFlightAirborne(maid)
                || com.maidsmart.combat.MaidBroomKit.isBroomAirborne(maid)) {
            return false; // 滑翔 / 扫帚腾空：此刻不走腿
        }
        return true;
    }
}
