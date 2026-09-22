package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraftforge.items.IItemHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 投掷 TNT 的弹道与制导（v1.2.4 从 MaidBombing 拆出）。
 * 
 * 射程/初速反解（flightTicks / requiredVy）、目标合法性、命中判定，
 * 以及飞行中的制导与点燃。
 */
public final class BombThrow {
    private BombThrow() {
    }

    static void tickHoming() {
        for (Iterator<MaidBombing.Homing> it = MaidBombing.HOMING.iterator(); it.hasNext(); ) {
            MaidBombing.Homing h = it.next();
            try {
                if (h.tnt == null || h.tnt.m_213877_()) {
                    it.remove(); // 已经炸了 / 被清掉了
                    continue;
                }
                if (h.level.m_46467_() >= h.until) {
                    it.remove(); // v1.2.2 实测五百九十一：追踪时间到 → 撒手，按当前方向直飞
                    continue;
                }
                if (h.target == null || !h.target.m_6084_() || h.target.m_213877_()
                        || h.target.m_9236_() != h.level) {
                    it.remove(); // 目标没了 / 换维度了 → 这一发按当前朝向直飞
                    continue;
                }
                double dx = h.target.m_20185_() - h.tnt.m_20185_();
                double dy = (h.target.m_20186_() + h.target.m_20192_() * 0.5) - h.tnt.m_20186_();
                double dz = h.target.m_20189_() - h.tnt.m_20189_();
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len < 1.0) {
                    continue; // 已经贴脸：交给原版物理自然落点（不再硬掰，免得绕着目标打转）
                }
                double nx = dx / len;
                // 补一点抬升抵消 TNT 每 tick 的 −0.04 重力，方向才是真的指向目标
                double ny = dy / len + 0.04 / h.speed;
                double nz = dz / len;
                double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (nl < 1.0E-6) {
                    continue;
                }
                nx /= nl;
                ny /= nl;
                nz /= nl;
                // 它现在朝着哪：这一 tick 的原速度方向
                Vec3 mv = h.tnt.m_20184_();
                double mx = mv.f_82479_;
                double my = mv.f_82480_;
                double mz = mv.f_82481_;
                double ml = Math.sqrt(mx * mx + my * my + mz * mz);
                if (ml < 1.0E-4) {
                    continue; // 速度没了（卡住了）→ 不插手
                }
                mx /= ml;
                my /= ml;
                mz /= ml;
                // 限转角：夹角在阈值内就直接到位，否则只走这一小步（两向量线性插值后归一化）
                double dot = Math.max(-1.0, Math.min(1.0, mx * nx + my * ny + mz * nz));
                double ang = Math.acos(dot);
                double t = ang <= MaidBombing.TRACK_TURN_RAD ? 1.0 : MaidBombing.TRACK_TURN_RAD / ang;
                double sx = mx + (nx - mx) * t;
                double sy = my + (ny - my) * t;
                double sz = mz + (nz - mz) * t;
                double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
                if (sl < 1.0E-6) {
                    continue;
                }
                // 只改方向、不改速度大小（需求原话）
                h.tnt.m_20256_(new Vec3(sx / sl * h.speed, sy / sl * h.speed, sz / sl * h.speed));
            } catch (Throwable ignored) {
            }
        }
    }

    static boolean legalThrowTarget(EntityMaid maid, LivingEntity le) {
        try {
            if (le == null || le == maid || !le.m_6084_()) {
                return false;
            }
            if (FriendlyFireGuard.isFriendly(maid, le)) {
                return false; // 防误伤：主人 / 同主女仆 / 友军不扔
            }
            return maid.getTask() instanceof com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask at
                    && at.canAttack(maid, le); // 只打「她的任务认的敌人」（与 TLM 索敌同口径）
        } catch (Throwable ignored) {
            return false;
        }
    }

    static LivingEntity findThrowTarget(ServerLevel level, EntityMaid maid) {
        double r = Math.max(2.0, BombConfig.cfgTntRange());
        LivingEntity best = null;
        double bestSqr = Double.MAX_VALUE;
        for (LivingEntity le : level.m_6443_(LivingEntity.class, maid.m_20191_().m_82400_(r), e -> true)) {
            try {
                if (le == maid || !le.m_6084_()) {
                    continue;
                }
                if (FriendlyFireGuard.isFriendly(maid, le)) {
                    continue; // 防误伤：主人 / 同主女仆 / 友军不扔
                }
                if (!(maid.getTask() instanceof com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask at)
                        || !at.canAttack(maid, le)) {
                    continue; // 只打"她的任务认的敌人"（与 TLM 索敌同口径）
                }
                double d = maid.m_20280_(le);
                if (d < bestSqr) {
                    bestSqr = d;
                    best = le;
                }
            } catch (Throwable ignored) {
            }
        }
        return best;
    }

    private static double flightTicks(double dh, double vx0) {
        if (dh <= 1.0E-4 || vx0 <= 1.0E-6) {
            return 1.0;
        }
        double base = 1.0 - 0.02 * dh / vx0;
        if (base <= 0.02) {
            return 200.0;
        }
        double n = Math.log(base) / Math.log(0.98);
        return Math.max(1.0, Math.min(200.0, n));
    }

    private static double requiredVy(double dh, double dy, double vx0) {
        double n = flightTicks(dh, vx0);
        double a = (1.0 - Math.pow(0.98, n)) / 0.02;
        double b = (n - a) / 0.02;
        double vy = (dy + 0.04 * b) / Math.max(1.0E-6, a) + 0.04;
        return Mth.m_14008_(vy, -1.5, 1.5);
    }

    private static int throwCount(EntityMaid maid) {
        try {
            float max = maid.m_21233_();
            if (max > 0.0f && maid.m_21223_() / max <= BombConfig.cfgTntBurstRatio()) {
                return Math.max(1, BombConfig.cfgTntBurstCount());
            }
        } catch (Throwable ignored) {
        }
        return 1;
    }

    static int throwTntAt(ServerLevel level, EntityMaid maid, LivingEntity target, UUID id, long gameTime) {
        int want = throwCount(maid);
        int thrown = 0;
        // v1.2.2 实测六百〇四：扔的到底是哪一件（模组 TNT 也认之后，日志里得说清，排查不用猜）
        String usedId = "";
        // v1.2.2 实测六百〇五：放出来的到底是哪一枚（它自己那一枚 / 模组方块放的是原版引信 TNT）
        String usedNote = "";
        for (int i = 0; i < want; i++) {
            if (!BombItems.hasTnt(maid) || !BombItems.hasIgniter(maid)) {
                break;
            }
            ItemStack tntStack = BombItems.takeOneTnt(maid);
            if (tntStack.m_41619_()) {
                break;
            }
            usedId = BombItems.idOf(tntStack);
            // v1.2.2 实测五百九十一【打火石不再被吞】：从**原槽**取出 → 扣 1 点耐久 → 放回**原槽**
            //（旧版是整件取出、只对取出来的那份扣耐久 = 玩家看到的"直接把打火石吞掉"）
            // v1.2.2 实测六百〇三：没有打火石时用 **1 个烈焰弹**（真的消耗掉）——返回的是"刚用掉的
            // 那一件"，下面拿它做副手动作（亮打火石 / 亮烈焰弹，各亮各的）
            ItemStack igniter = BombItems.useIgniter(maid);
            if (igniter.m_41619_()) {
                BombItems.giveBack(maid, tntStack);
                break;
            }
            double sx = maid.m_20185_();
            double sy = maid.m_20186_() + maid.m_20192_() * 0.75;
            double sz = maid.m_20189_();
            double tx = target.m_20185_();
            double ty = target.m_20186_() + target.m_20192_() * 0.5;
            double tz = target.m_20189_();
            // ── v1.2.2 实测五百八十九【准度】──
            // 旧版是"飞行时间 ≈ 水平距离 / 水平速度，再补一点落差"——忽略了 0.98/tick 的水平阻尼
            // （水平射程因此是有限的等比级数），目标一动就偏。现在两处一起改：
            // ① 目标带提前量（按它的速度外推预计飞行时间，迭代两轮）；
            // ② 用反编译实证的 TNT 物理做**闭式解**：每 tick 先 −0.04 重力、再位移、最后整体 ×0.98，
            //    于是水平位移 = vx0·A_n、竖直位移 = (vy0−0.04)·A_n − 0.04·B_n
            //    （A_n=(1−0.98^n)/0.02、B_n=(n−A_n)/0.02）：先由水平距离解出飞行时间 n，再解出 vy0。
            double speed = Math.max(0.2, BombConfig.cfgTntSpeed());
            double aimX = tx;
            double aimZ = tz;
            double aimY = ty;
            Vec3 tv = target.m_20184_();
            for (int it = 0; it < 2; it++) {
                double ddx = aimX - sx;
                double ddz = aimZ - sz;
                double flight = flightTicks(Math.sqrt(ddx * ddx + ddz * ddz), speed);
                aimX = tx + tv.f_82479_ * flight;
                aimZ = tz + tv.f_82481_ * flight;
                aimY = ty + tv.f_82480_ * flight * 0.5;
            }
            double dx = aimX - sx;
            double dy = aimY - sy;
            double dz = aimZ - sz;
            double dh = Math.sqrt(dx * dx + dz * dz);
            double vx = 0.0;
            double vz = 0.0;
            if (dh > 1.0E-4) {
                vx = dx / dh * speed;
                vz = dz / dh * speed;
                if (want > 1) {
                    double spread = (i - (want - 1) * 0.5) * 0.18;
                    double sx2 = -vz / speed * spread;
                    double sz2 = vx / speed * spread;
                    vx += sx2;
                    vz += sz2;
                }
            }
            double vy = requiredVy(dh, dy, speed);
            // ── v1.2.2 实测六百〇五【认出来的 TNT，就放它自己那一枚】──
            // 旧版（含实测六百〇四）不论手上拿的是哪一件，扔出去的都是这里 new 出来的**原版**引信 TNT
            // ——模组 TNT 的威力 / 破不破方块 / 带不带火全被抹掉。现在先问那一件自己：方块继承
            // TntBlock 吗？继承就走它自己的点火钩子（onCaughtFire），放出来的是它自己的 TNT 形式。
            TntOut primed = primeTnt(level, maid, tntStack, sx, sy, sz);
            if (primed == null) {
                BombItems.giveBack(maid, tntStack);
                break;
            }
            Entity bomb = primed.entity;
            bomb.m_6034_(sx, sy, sz); // 模组钩子按"方块中心"放，挪回她手上那一格（抛物线才算得准）
            bomb.m_20256_(new Vec3(vx, vy, vz));
            PrimedTnt tnt = bomb instanceof PrimedTnt pt ? pt : null;
            if (tnt != null) {
                tnt.m_32085_(BombConfig.cfgTntFuse());
            }
            if (!primed.added && !level.m_7967_(bomb)) {
                BombItems.giveBack(maid, tntStack);
                break;
            }
            if (!primed.note.isEmpty()) {
                usedNote = primed.note;
            }

            // v1.2.2 实测五百九十【追踪】/ 实测五百九十一【限时】：登记这一发——只在离手后的
            // cfgTntTrackTicks（默认 10 tick = 0.5 秒）内朝目标修正方向，之后按当时方向直飞
            //（模组自己的 TNT 实体也继承 PrimedTnt，追踪照旧生效）
            if (tnt != null && BombConfig.cfgTntTrack() && BombConfig.cfgTntTrackTicks() > 0 && MaidBombing.HOMING.size() < MaidBombing.MAX_PENDING) {
                MaidBombing.HOMING.add(new MaidBombing.Homing(level, tnt, target, Math.sqrt(vx * vx + vy * vy + vz * vz),
                        gameTime + BombConfig.cfgTntTrackTicks()));
            }
            SoundEvent snd = BombItems.sound(BombItems.ID_TNT_PRIMED_SOUND);
            if (snd != null) {
                level.m_5594_(null, maid.m_20183_(), snd, SoundSource.BLOCKS, 1.0f, 1.0f);
            }
            maid.m_6674_(InteractionHand.MAIN_HAND);
            // 投掷那一记的动作：副手举的是**点火的那一件**（实测五百九十四）——旧版亮的是刚扔出去的
            // 那枚 TNT，反馈要的是"点火的那只手"：扔 TNT 的时候副手切换成打火石；
            // 实测六百〇三起没有打火石就用烈焰弹，这时副手亮的自然是那枚烈焰弹。
            BombConfig.pose(maid, igniter.m_41619_() ? tntStack : igniter, MaidBombing.BOMB_POSE_TICKS);
            // ── v1.2.2 实测六百〇五【那一炸归谁】──
            // 只有"原版那一枚"才登记进 PENDING（本模组接管那一炸 = 默认不破坏方块 / 不伤友军）；
            // 模组自己的 TNT 形式**一律不登记**：它自己那一炸该多大、破不破方块、带不带火，
            // 全归它自己——本模组不替换、不拦截（伤害的友军豁免仍由 FriendlyFireGuard /
            // FriendlyWindGuard 按"爆炸来源是女仆"那条既有判据兜住）。
            if (primed.vanilla && tnt != null && MaidBombing.PENDING.size() < MaidBombing.MAX_PENDING) {
                MaidBombing.PENDING.add(new MaidBombing.Bomb(level, maid, tnt, bomb.m_20183_(), null, null,
                        MaidBombing.Kind.TNT, gameTime + BombConfig.cfgTntFuse() + MaidBombing.BOMB_TIMEOUT / 2, tntStack));
            }
            thrown++;
            if (BombConfig.cfgPinkMark()) {
                BombMarkNetworking.send(maid, 1, bomb.m_19879_(), null, BombConfig.cfgTntFuse() + 40);
            }
        }
        if (thrown > 0) {
            MaidBombing.TNT_NEXT.put(id, gameTime + BombConfig.cfgTntInterval());
            MaidBombing.log(com.maidsmart.tool.PromaidLog.nameOf(maid) + " 投掷 TNT ×" + thrown
                    + "（" + (usedId.isEmpty() ? "未知物品" : usedId) + "，引信 " + BombConfig.cfgTntFuse() + " tick"
                    + (usedNote.isEmpty() ? "" : "，" + usedNote) + "）");
        }
        return thrown;
    }

    private static final class TntOut {
        final Entity entity;
        final boolean added;
        final boolean vanilla;
        final String note;

        TntOut(Entity entity, boolean added, boolean vanilla, String note) {
            this.entity = entity;
            this.added = added;
            this.vanilla = vanilla;
            this.note = note;
        }
    }

    private static TntOut primeTnt(ServerLevel level, EntityMaid maid, ItemStack stack,
                                   double sx, double sy, double sz) {
        try {
            TntBlock tb = BombItems.tntBlockOf(stack);
            if (tb != null && tb.getClass() != TntBlock.class) {
                Entity made = igniteByBlock(level, maid, tb, sx, sy, sz);
                if (made != null) {
                    // 放出来的是引信 TNT 的子类 = 模组自己的 TNT 形式（那一炸归它自己）；
                    // 放出来的就是原版 PrimedTnt = 这个方块没改点火路径，照原版 TNT 的老口径接管
                    boolean plain = made.getClass() == PrimedTnt.class;
                    if (!plain) {
                        // v1.2.2 实测六百〇七【模组 TNT 也受「不破坏方块」管辖】：把这一枚登记给
                        // MaidTntBlastGuard —— 它的爆炸不经过我们的出口（威力/带火归它自己），
                        // 所以"破不破方块"得靠那一层在爆炸事件里收走。登记带过期时间，见那类注释。
                        MaidTntBlastGuard.rememberTnt(made, maid,
                                level.m_46467_() + MaidTntBlastGuard.TRACK_TTL);
                    }
                    return new TntOut(made, true, plain, plain
                            ? "模组方块放的是原版引信 TNT"
                            : "它自己那一枚 " + entityIdOf(made));
                }
                // 钩子什么也没放出来（这个方块压根不是 TNT 那种用法）→ 回落到原版那一枚
            }
        } catch (Throwable t) {
            MaidBombing.log("模组 TNT 点火失败：" + t);
        }
        return new TntOut(new PrimedTnt(level, sx, sy, sz, maid), false, true, "");
    }

    private static Entity igniteByBlock(ServerLevel level, EntityMaid maid, TntBlock tb,
                                       double sx, double sy, double sz) {
        BlockPos pos = BlockPos.m_274561_(sx, sy, sz);
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(pos).m_82377_(2.0, 2.0, 2.0);
        java.util.Set<Integer> before = new java.util.HashSet<>();
        for (Entity e : level.m_45976_(Entity.class, box)) {
            before.add(e.m_19879_());
        }
        try {
            // 点火者就是她——原版拿它当这一炸的"造成者"（见 MaidTntBlastGuard 类注释与实测六百一十），
            // 主人/友军免伤正是靠这条认人；模组不记它时由那张登记表兜底。
            tb.onCaughtFire(tb.m_49966_(), level, pos, Direction.UP, maid);
        } catch (Throwable t) {
            MaidBombing.log("模组 TNT 点火钩子异常：" + t);
        }
        Entity pick = null;
        for (Entity e : level.m_45976_(Entity.class, box)) {
            if (before.contains(e.m_19879_())) {
                continue;
            }
            if (e instanceof PrimedTnt) {
                return e; // 引信 TNT 那一类优先
            }
            if (pick == null) {
                pick = e;
            }
        }
        return pick;
    }

    private static String entityIdOf(Entity e) {
        try {
            net.minecraft.resources.ResourceLocation rl =
                    net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.m_6095_());
            if (rl != null) {
                return rl.toString();
            }
        } catch (Throwable ignored) {
        }
        try {
            return e.getClass().getSimpleName();
        } catch (Throwable ignored) {
            return "未知实体";
        }
    }
}
