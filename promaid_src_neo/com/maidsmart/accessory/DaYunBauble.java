package com.maidsmart.accessory;

import com.github.tartaricacid.touhoulittlemaid.api.bauble.IMaidBauble;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * <p>【需求原文】"加入一个新饰品，可以由玩家和女仆进行佩戴。佩戴的效果是使用鞘翅飞行的过程中
 * 可以直接击毁自身周边 5×5 范围内除硬度大于等于黑曜石以及硬度&lt;0 的一切方块。配置选项里面
 * 也可以写。这个效果在女仆空袭的时候生效。"
 * <p>【女仆侧判据（三重闸，缺一不可）】
 * <ol>
 *   <li>她饰品栏里有「大运」（本类被 TLM 只在装了这件时才回调，天然满足）；</li>
 *   <li>她真的在鞘翅滑翔（{@link DaYunBreaker#sweep} 内部判 {@code isFallFlying()}）；</li>
 *   <li>她在**空袭模式**——即飞行作战任务（{@code maid_smart:flight_combat} /
 *       {@code flight_ranged}）。需求原文"这个效果在女仆空袭的时候生效"，所以骑扫帚、
 *       仿创造飞行、单纯滑翔跟随都不触发。</li>
 * </ol>
 * <p>【性能闸】TLM 每 tick 对每件饰品调一次 {@code onTick}；破坏动作内部还有
 * {@code accessory.daYun.maxPerTick} 上限，超出的格子下一 tick 继续清。 <p>本类两树各一份：Neo 树用 Mojmap 名（本文件），Forge 树用 SRG 名。
 */
public class DaYunBauble implements IMaidBauble {

    @Override
    public void onTick(EntityMaid maid, ItemStack stack) {
        try {
            if (maid == null || !maid.isAlive()) {
                return;
            }
            if (!(maid.level() instanceof ServerLevel level)) {
                return; // 客户端不做事（破坏只能在服务端）
            }
            // v1.5.394 实测八百零五：BGM 与总开关无关、与空袭无关——只要「戴着 + 在滑翔」。
            // 放在最前面：下面那几条 return 只该挡住"破坏"，不该挡住"响音乐"
            //（需求原文是"穿饰品飞行时播放"，破坏才是"只在空袭生效"那一条）。
            tickMusic(level, maid);

            if (!MaidSmartConfig.DAYUN_ENABLE.get()) {
                return;
            }
            // 需求："这个效果在女仆空袭的时候生效" —— 只认飞行作战任务
            if (!isAirRaidTask(maid)) {
                return;
            }
            // 实测八百一十一：与玩家侧同款——走 sweepAhead 并带上速度，沿前进方向**提前**清。
            // 旧版是"移动之后才清自己周围"，撞上墙的那一 tick 她已经被挡住了，下一 tick 墙才消失，
            // 观感就是"女仆飞起来一顿一顿的、不如玩家畅通"。见 DaYunBreaker#sweepAhead 的说明。
            DaYunBreaker.sweepAhead(level, maid,
                    MaidSmartConfig.DAYUN_MAX_HARDNESS.get().floatValue(),
                    MaidSmartConfig.DAYUN_SKIP_CONTAINERS.get(),
                    MaidSmartConfig.DAYUN_DROP_ITEMS.get(),
                    MaidSmartConfig.DAYUN_BREAK_PARTICLES.get(),
                    MaidSmartConfig.DAYUN_MAX_PER_TICK.get(),
                    maid.getDeltaMovement(),
                    MaidSmartConfig.DAYUN_RANGE.get());
            // 实测九百〇六：同一片「破坏范围内」的非友方单位，按这一刻的速度吃伤害 + 被击飞
            DaYunSmash.impact(level, maid, maid.getDeltaMovement(), MaidSmartConfig.DAYUN_RANGE.get());
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("DaYunBauble", ignored);
            // 饰品 tick 跑在女仆主 tick 里，任何异常都不能抛穿（最坏也只是这一拍不破坏）
        }
    }

    /**
     * <p>判据只看「戴着（本回调本身就是）+ 在滑翔」，**不要求空袭任务**：需求原文是
     * "玩家和女仆穿饰品飞行时会播放"，说的是"穿饰品飞行"这件事，破坏方块的"只在空袭生效"
     * 是另一条闸（见类注释）。所以骑扫帚/跟随飞行那些不是鞘翅滑翔的方式照样不会响。
     * <p>曲目读 {@code accessory.daYun.bgm}；0 = 关。
     * <p>【v1.5.396 实测八百零九：空袭途中不再"打一下就重播"】
     * 收翅猛击那一段（见 {@code MaidFlightCombatBehavior}）会主动清掉滑翔位，打完再起飞才置回来。 → 音乐从头开始。 <p>现在判据放宽成 {@link MaidFlightKit#isFlightAirborne}（滑翔 **或** 本轮攻击进行中）： 一次空袭从起跳到收手全程都算"在飞"，音乐一口气播到曲终。这条比"给停止加宽限期"更稳—— 猛击段最长是配置项 {@code airRaid.smashMaxTicks}（可调到 200 刻 = 10 秒）， 单靠宽限期盖不住，而"本轮正在打"天然覆盖整段（状态表由行为收尾 {@code forget()} 整清，有界）。
     */
    private static void tickMusic(ServerLevel level, EntityMaid maid) {
        int track = MaidSmartConfig.DAYUN_BGM.get();
        if (track > 0 && isFlying(maid)) {
            DaYunMusic.play(level, maid, Math.min(track, 2));
        } else {
            DaYunMusic.stop(level, maid);
        }
    }

    /** 她此刻算不算"在飞"：真在滑翔，或正处于一轮空袭的任一相位（含收翅猛击那几拍）。 */
    private static boolean isFlying(EntityMaid maid) {
        if (maid.isFallFlying()) {
            return true;
        }
        try {
            return com.maidsmart.combat.MaidFlightKit.isFlightAirborne(maid);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("DaYunBauble", ignored);
            return false;
        }
    }

    /** 她当前是不是空袭（飞行作战）任务。 */
    public static boolean isAirRaidTask(EntityMaid maid) {
        try {
            return com.maidsmart.combat.MaidFlightKit.isFlightTask(maid);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("DaYunBauble", ignored);
            return false;
        }
    }
}
