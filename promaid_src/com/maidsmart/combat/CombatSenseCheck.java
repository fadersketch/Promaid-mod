package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 战斗感知自检（v1.2.2 实测六百一十九）——{@code /maid_smart combat check [女仆]} 干活的地方。
 *
 * ── 为什么要有它 ──
 * 本批两条需求验的都是**判据**，而不是"某个动作发生没发生"：
 * <ol>
 *   <li>"有方块遮挡的怪不能被感知到"——判据在 {@link NeutralThreatDriver#perceivable}；</li>
 *   <li>"战斗状态临时扩圈、回常态用正常范围"——判据在 {@link CombatWorkRange#radius}
 *       （由 {@code CombatWorkRangeMixin} 挂在 {@code EntityMaid.getRestrictRadius} 上）。</li>
 * </ol>
 * 两条都依赖**在线主人**（我们的威胁驱动要求主人实体在线上，专用服务器上没有玩家、
 * 结构上触发不了），所以照 实测五百四十五/六百〇八 的先例：把真场景摆出来、**调真方法**、
 * 把判据的取值打进日志，由 {@code test_target619.py} 逐条断言。
 *
 * ── 验什么（三段，每段都配一条"反向对照"）──
 * <ol>
 *   <li><b>遮挡门</b>：她正前方 2 格砌一堵 2 格高的石墙、墙后 4 格放一只锁定了她的僵尸 →
 *       {@code perceivable=false}、扫描也找不到它；<b>把墙拆掉</b> → 三个判据一起翻成
 *       true/找到它（证明判据不是恒 false）；<b>僵尸不再锁定我方</b> → 又变 false
 *       （证明判据是"锁定 + 看得见"的合取，不是"只要看得见就算威胁"）；</li>
 *   <li><b>战斗扩圈</b>：把她的圈设成 8 格（{@code restrictTo}）→ 常态半径读数 8、
 *       离圈心 10 格在圈外；**往脑里真写一条 ATTACK_TARGET**（模拟接战，走的是
 *       TLM/我们战斗链路同一条记忆）→ 半径读数变成配置的 32、10 格进圈；清掉目标 →
 *       立刻落回 8 / 圈外。顺带把 TLM 那条"半径 + 4 就传送回工位"的阈值算出来
 *       （12 → 36），test_target619.py 断言的就是这几个数；</li>
 *   <li><b>金苹果冷却读数</b>：把 {@code maid_smart_golden_apple_cd} 剩余 tick 打出来
 *       ——吃了之后必须 &gt; 0（test_apple619.py 在烧完之后读这一行）。</li>
 * </ol>
 *
 * ── 不许留痕 ──
 * 摆场景用的方块逐格记原状态、结束时复位；僵尸用完 discard；真圈心/半径、home 模式开关、
 * ATTACK_TARGET 记忆也都原样还原（自检不该改变她的任何状态）。
 */
public final class CombatSenseCheck {

    /** 日志类别（命令层与 test_target619.py 都用它切段） */
    public static final String CAT = "战斗感知自检";

    private CombatSenseCheck() {
    }

    public static List<Component> run(ServerLevel level, EntityMaid maid) {
        List<Component> out = new ArrayList<>();
        if (maid == null) {
            out.add(skip("没给女仆——这一段要一只真女仆（/maid_smart combat check <女仆>）"));
            return out;
        }
        Mob zombie = null;
        try {
            zombie = spawnZombie(level, maid.m_20183_().m_7918_(4, 0, 0));
            if (zombie == null) {
                out.add(skip("这台机器上召唤不出 minecraft:zombie（场景搭不起来）"));
                return out;
            }
            sightGate(out, level, maid, zombie);
            combatCircle(out, maid, zombie);
            goldenAppleCd(out, maid);
        } catch (Throwable t) {
            out.add(fail("自检自己抛异常了：" + t));
        } finally {
            if (zombie != null) {
                try {
                    zombie.m_142687_(Entity.RemovalReason.DISCARDED);
                } catch (Throwable ignored) {
                }
            }
            // 僵尸走了之后再复位"给它腾地方"清掉的格子（留着它站在实心方块里会窒息）
            for (int i = 0; i < CLEARED_POS.size(); i++) {
                place(level, CLEARED_POS.get(i), CLEARED_STATE.get(i));
            }
            CLEARED_POS.clear();
            CLEARED_STATE.clear();
        }
        return out;
    }

    /** 自检期间"清出来给僵尸站"的格子（位置 + 原状态）——僵尸销毁之后再复位 */
    private static final List<BlockPos> CLEARED_POS = new ArrayList<>();
    private static final List<BlockState> CLEARED_STATE = new ArrayList<>();

    private static void clearBlock(ServerLevel level, BlockPos pos,
                                   net.minecraft.world.level.block.Block air) {
        CLEARED_POS.add(pos);
        CLEARED_STATE.add(level.m_8055_(pos));
        place(level, pos, air.m_49966_());
    }

    /* ==================== ① 有方块遮挡的怪不能被感知到（用户第 1 条） ==================== */

    private static void sightGate(List<Component> out, ServerLevel level, EntityMaid maid, Mob zombie) {
        BlockPos base = maid.m_20183_();
        // 她正前方（+X）2 格砌 2 格高的石墙；僵尸站在 x+4 —— 视线（眼高 +1.2）必然穿过
        // 墙的上半格。用固定方向而不是她的朝向：自检不该依赖"她现在朝哪边"。
        BlockPos wallLow = base.m_7918_(2, 0, 0);
        BlockPos wallHigh = base.m_7918_(2, 1, 0);
        BlockState oldLow = level.m_8055_(wallLow);
        BlockState oldHigh = level.m_8055_(wallHigh);
        net.minecraft.world.level.block.Block stone = ForgeRegistries.BLOCKS
                .getValue(ResourceLocation.parse("minecraft:stone"));
        net.minecraft.world.level.block.Block air = ForgeRegistries.BLOCKS
                .getValue(ResourceLocation.parse("minecraft:air"));
        if (stone == null || air == null) {
            out.add(skip("遮挡那条：取不到 minecraft:stone / air 方块，跳过"));
            return;
        }
        BlockPos zpos = base.m_7918_(4, 0, 0);
        clearBlock(level, zpos, air);                         // 僵尸站的地方要空着（别卡在方块里窒息）
        clearBlock(level, zpos.m_7918_(0, 1, 0), air);
        zombie.m_7678_(zpos.m_123341_() + 0.5, zpos.m_123342_(), zpos.m_123343_() + 0.5, 0f, 0f);
        zombie.m_6710_(maid); // setTarget：行为化锁定的核心信号（不看类型）

        // ── A：墙在 → 三个判据都必须说"感知不到" ──
        place(level, wallLow, stone.m_49966_());
        place(level, wallHigh, stone.m_49966_());
        boolean occSight = SelfPreservationBehavior.hasSight(maid, zombie);
        boolean occPerc = NeutralThreatDriver.perceivable(maid, maid, zombie);
        Mob occScan = NeutralThreatDriver.findThreateningMob(maid, maid);
        out.add(!occSight && !occPerc && occScan != zombie
                ? pass("遮挡那条：墙在 —— 看不见（hasSight=false）、不算威胁（perceivable=false）、"
                + "扫描也没选中它（" + name(occScan) + "）")
                : fail("遮挡那条：墙在，但她还是" + (occSight ? "看得见" : "") + (occPerc ? "把它当威胁" : "")
                + (occScan == zombie ? "、扫描选中了它" : "") + " —— 隔着方块感知还在"));

        // ── B：拆墙 → 同一只僵尸必须翻成"看得见 + 算威胁 + 扫描选中它" ──
        place(level, wallLow, air.m_49966_());
        place(level, wallHigh, air.m_49966_());
        boolean visSight = SelfPreservationBehavior.hasSight(maid, zombie);
        boolean visPerc = NeutralThreatDriver.perceivable(maid, maid, zombie);
        Mob visScan = NeutralThreatDriver.findThreateningMob(maid, maid);
        out.add(visSight && visPerc && visScan == zombie
                ? pass("对照：把墙拆掉 —— 同一只僵尸立刻变成看得见（hasSight=true）、算威胁"
                + "（perceivable=true）、扫描也选中了它 —— 上面那条不是恒 false")
                : fail("对照失败：拆了墙她还是" + (visSight ? "看得见" : "看不见")
                + "、" + (visPerc ? "算威胁" : "不算威胁") + "、扫描"
                + (visScan == zombie ? "选中了它" : "没选中它（" + name(visScan) + "）")
                + " —— 判据或场景有问题"));

        // ── C：对照——墙拆了、看得见，但它没锁定我方 → 仍然不算威胁 ──
        zombie.m_6710_(null);
        boolean noLock = NeutralThreatDriver.perceivable(maid, maid, zombie);
        zombie.m_6710_(maid);
        out.add(!noLock
                ? pass("对照：看得见但没锁定我方 —— 不算威胁（判据是「锁定 + 看得见」的合取，"
                + "不是「只要看得见就打」）")
                : fail("对照失败：一只没锁定我俩的僵尸也被当成威胁了（判据退化成「看得见就算」）"));

        // ── D：对照——锁定我方、看得见，但在 40 格外 → 扫描找不到（距离门还在） ──
        BlockPos far = base.m_7918_(40, 0, 0);
        zombie.m_7678_(far.m_123341_() + 0.5, far.m_123342_(), far.m_123343_() + 0.5, 0f, 0f);
        Mob farScan = NeutralThreatDriver.findThreateningMob(maid, maid);
        out.add(farScan != zombie
                ? pass("对照：锁定我方但远在 40 格外 —— 扫描不选它（" + name(farScan)
                + "）—— 16 格的搜索门没被这次改动放宽")
                : fail("对照失败：40 格外的僵尸竟然被扫描选中了"));

        // 还原：墙复位（僵尸站的那两格由 run() 收尾时复位——它还在用那个位置）
        place(level, wallLow, oldLow);
        place(level, wallHigh, oldHigh);
    }

    /* ==================== ② 战斗时临时扩圈（用户第 2 条） ==================== */

    private static void combatCircle(List<Component> out, EntityMaid maid, Mob zombie) {
        int cfg = CombatWorkRange.expandRadius();
        boolean homeBefore = maid.m_21536_();
        BlockPos centerBefore = maid.m_21534_();
        float radiusBefore = maid.m_21535_();
        LivingEntity targetBefore = null;
        LivingEntity mobTargetBefore = null;
        try {
            targetBefore = maid.m_6274_().m_21952_(MemoryModuleType.f_26372_).orElse(null);
            mobTargetBefore = maid.m_5448_();
        } catch (Throwable ignored) {
        }
        BlockPos center = maid.m_20183_();
        BlockPos outside = center.m_7918_(10, 0, 0); // 常态 8 格圈外、扩圈 32 格圈内
        try {
            // 【先把她从"接战"里摘出来再量常态】否则常态读数会是扩圈值——neo 那台第一次跑
            // 就是这么红的：上一段（遮挡那条）把一只锁定了她的僵尸摆在她 4 格外，
            // 她的脑自己就把那只怪当成了目标 → "常态"读数直接是 32。
            maid.m_6274_().m_21936_(MemoryModuleType.f_26372_);
            maid.m_6710_(null);
            maid.setHomeModeEnable(true);
            maid.m_21446_(center, 8); // restrictTo：常态圈 8 格
            float normal = maid.m_21535_();
            boolean outNormal = !maid.m_21444_(outside);
            String reason = CombatWorkRange.combatReason(maid);
            out.add(normal == 8f && outNormal
                    ? pass("扩圈那条：常态圈设成 8 格 —— 半径读数 " + fmt(normal)
                    + "、离圈心 10 格在圈外（isWithinRestriction=false）；接战判据=" + reason)
                    : fail("扩圈那条：常态圈不对（半径 " + fmt(normal) + "，应为 8；"
                    + "10 格在圈外=" + outNormal + "；接战判据=" + reason + "）"));

            if (cfg <= 0) {
                out.add(skip("扩圈那条：配置 combatWorkRange=0（功能关闭），后面的接战读数不做"));
            } else {
                // 模拟接战：真写一条 ATTACK_TARGET（TLM 攻击任务/我们的战术与空袭都读这条记忆）
                maid.m_6274_().m_21886_(MemoryModuleType.f_26372_, Optional.of(zombie));
                float fighting = maid.m_21535_();
                boolean inFighting = maid.m_21444_(outside);
                out.add(fighting == (float) Math.max(8, cfg) && inFighting
                        ? pass("扩圈那条：脑里有活目标时 —— 半径读数 " + fmt(fighting)
                        + "（= max(常态 8, 配置 " + cfg + ")）、离圈心 10 格进圈"
                        + "（isWithinRestriction=true）—— 圈真的变大了")
                        : fail("扩圈那条：接战时半径 " + fmt(fighting) + "（应为 "
                        + Math.max(8, cfg) + "）、10 格进圈=" + inFighting));

                // TLM 的传送门：SchedulePos.tick 每 40 tick 一次，超过 (int)半径 + 4 格直接传送回工位
                int teleNormal = (int) normal + 4;
                int teleFighting = (int) fighting + 4;
                out.add(teleFighting > teleNormal
                        ? pass("扩圈那条：TLM 的传送阈值（(int)半径 + 4）从 " + teleNormal
                        + " 格抬到 " + teleFighting + " 格 —— 接战时不会刚追出去就被拽回工位")
                        : fail("扩圈那条：传送阈值没变（" + teleNormal + " → " + teleFighting + "）"));

                // 战斗结束：清掉目标 → 必须立刻落回常态（"回到常态再用正常设置的工作范围"）
                maid.m_6274_().m_21936_(MemoryModuleType.f_26372_);
                float after = maid.m_21535_();
                boolean outAfter = !maid.m_21444_(outside);
                out.add(after == 8f && outAfter
                        ? pass("扩圈那条：清掉目标（战斗结束）—— 半径立刻落回 " + fmt(after)
                        + "、10 格又出圈 —— 不需要任何收尾代码，自动回到常态工作范围")
                        : fail("扩圈那条：战斗结束后半径没落回常态（" + fmt(after)
                        + "，应为 8；10 格在圈外=" + outAfter + "）"));
            }
        } finally {
            // 还原她的真实圈 + home 模式 + 两层目标记忆
            try {
                maid.m_21446_(centerBefore, (int) radiusBefore);
                maid.setHomeModeEnable(homeBefore);
                if (targetBefore != null) {
                    maid.m_6274_().m_21886_(MemoryModuleType.f_26372_, Optional.of(targetBefore));
                } else {
                    maid.m_6274_().m_21936_(MemoryModuleType.f_26372_);
                }
                if (mobTargetBefore != null) {
                    maid.m_6710_(mobTargetBefore);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /* ==================== ③ 金苹果冷却读数（issue #17） ==================== */

    private static void goldenAppleCd(List<Component> out, EntityMaid maid) {
        long cd = SelfPreservationBehavior.goldenAppleCdTicksLeft(maid);
        // 这一条是**读数**（不是断言）：test_apple619.py 在"点着她烧一段"之后再跑本命令，
        // 期望这里 > 0（她吃掉一个之后冷却就记上了）。冷却是持久数据，重启/重载都还在。
        out.add(pass("金苹果那条：她现在金苹果冷却剩余 " + cd
                + " tick（0 = 没吃过或已过期；吃掉一个后应当 > 0，普通苹果 CD 2400 tick）"));
    }

    /* ==================== 小工具 ==================== */

    private static Mob spawnZombie(ServerLevel level, BlockPos pos) {
        try {
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES
                    .getValue(ResourceLocation.parse("minecraft:zombie"));
            if (type == null) {
                return null;
            }
            Entity e = type.m_20615_(level);
            if (!(e instanceof Mob mob)) {
                return null;
            }
            mob.m_7678_(pos.m_123341_() + 0.5, pos.m_123342_(), pos.m_123343_() + 0.5, 0f, 0f);
            mob.m_21557_(true);  // setNoAi：站着不动（我们要的是"看得见/看不见"，不是它会不会走）
            mob.m_21553_(true);  // setPersistenceRequired：别自己消失
            level.m_7967_(mob);
            return mob;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void place(ServerLevel level, BlockPos pos, BlockState state) {
        try {
            level.m_7731_(pos, state, 3);
        } catch (Throwable ignored) {
        }
    }

    private static String name(Entity e) {
        if (e == null) {
            return "没有";
        }
        try {
            return String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(e.m_6095_()));
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String fmt(float f) {
        return String.format(java.util.Locale.ROOT, "%.1f", f);
    }

    private static Component pass(String s) {
        return Component.m_237113_("\u00a7a[PASS] \u00a7f" + s);
    }

    private static Component fail(String s) {
        return Component.m_237113_("\u00a7c[FAIL] \u00a7f" + s);
    }

    private static Component skip(String s) {
        return Component.m_237113_("\u00a7e[SKIP] \u00a7f" + s);
    }
}
