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
                long now = h.level.m_46467_();
                if (now >= h.expire) {
                    it.remove(); // 引信早该走完还没炸（区块卸载等）→ 这条监看作废
                    continue;
                }
                // ② 实测九百八十九【物理体积】：拿「上一 tick 位置 → 这一 tick 位置」这条扫掠线
                //    撞一遍路上的敌人——撞上就把引信归 1，本 tick 的 PENDING 循环（同一次
                //    Pre 事件、排在后面）在接触点当场引爆。与追踪开关无关：关掉追踪也要有体积，
                //    不然 TNT 还是从敌人身上穿过去。
                double cx = h.tnt.m_20185_();
                double cy = h.tnt.m_20186_();
                double cz = h.tnt.m_20189_();
                if (hitsEnemyOnSegment(h, h.lx, h.ly, h.lz, cx, cy, cz)) {
                    h.tnt.m_32085_(1);
                    it.remove();
                    continue;
                }
                h.lx = cx;
                h.ly = cy;
                h.lz = cz;
                // ③ 追踪（实测五百九十一：限时 + 限转角）——到点 / 目标没了就只保留上面的体积监看
                if (!h.track || now >= h.until) {
                    continue;
                }
                if (h.target == null || !h.target.m_6084_() || h.target.m_213877_()
                        || h.target.m_9236_() != h.level) {
                    continue; // 目标没了 / 换维度了 → 这一发按当前朝向直飞
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
                // 只改方向、不改速度大小（需求
                h.tnt.m_20256_(new Vec3(sx / sl * h.speed, sy / sl * h.speed, sz / sl * h.speed));
            } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombThrow", ignored);
            }
        }
    }

    /**
     * 实测九百八十九【物理体积】：这一段扫掠线上有没有撞到"她打得着的敌人"。
     *
     * 只认敌人——她本人 / 主人 / 友军 / 路人生物一律不算，不然飞过一头牛、主人挡了一下
     * 就把炸弹带炸了。判据 = 线段点到各候选**包围盒中心**的最近距离 ≤ 敌人半宽 + 炸弹半径；
     * 候选先用"线段外扩 0.75 格的盒子"粗筛（盒子只用于查询，细判是纯算术，不碰映射名）。
     */
    private static boolean hitsEnemyOnSegment(MaidBombing.Homing h, double ax, double ay, double az,
                                              double bx, double by, double bz) {
        try {
            if (h.maid == null) {
                return false;
            }
            double pad = 0.75;
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                    Math.min(ax, bx) - pad, Math.min(ay, by) - pad, Math.min(az, bz) - pad,
                    Math.max(ax, bx) + pad, Math.max(ay, by) + pad, Math.max(az, bz) + pad);
            for (LivingEntity le : h.level.m_6443_(LivingEntity.class, box, e -> true)) {
                try {
                    if (le == null || le == h.maid || !le.m_6084_()) {
                        continue;
                    }
                    if (le != h.target && !legalThrowTarget(h.maid, le)) {
                        continue; // 只认敌人：这一发原定的目标，或她任务认得的敌人
                    }
                    double px = le.m_20185_();
                    double py = le.m_20186_() + le.m_20192_() * 0.5;
                    double pz = le.m_20189_();
                    double reach = le.m_20205_() * 0.5 + 0.5; // 敌人半宽 + 炸弹半径
                    if (distToSegmentSqr(px, py, pz, ax, ay, az, bx, by, bz) <= reach * reach) {
                        return true;
                    }
                } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombThrow", ignored);
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombThrow", ignored);
        }
        return false;
    }

    /** 点到线段（三维）的最近距离平方——纯算术，不用任何带映射名的方法 */
    private static double distToSegmentSqr(double px, double py, double pz,
                                           double ax, double ay, double az,
                                           double bx, double by, double bz) {
        double abx = bx - ax;
        double aby = by - ay;
        double abz = bz - az;
        double len2 = abx * abx + aby * aby + abz * abz;
        double t = 0.0;
        if (len2 > 1.0E-9) {
            t = ((px - ax) * abx + (py - ay) * aby + (pz - az) * abz) / len2;
            t = Math.max(0.0, Math.min(1.0, t));
        }
        double dx = px - (ax + abx * t);
        double dy = py - (ay + aby * t);
        double dz = pz - (az + abz * t);
        return dx * dx + dy * dy + dz * dz;
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
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombThrow", ignored);
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
            } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombThrow", ignored);
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

    /**
     * TNT 矿车版的飞行时间：矿车离轨后每拍 **先加重力 −0.04、位移、离地再 ×0.95 阻尼**（javap
     * 实证 {@code AbstractMinecart.tick} 与 {@code comeOffTrack}），与引信 TNT 的 0.98 不同，
     * 所以闭式解另算一份。形状与 {@link #flightTicks} 一致（0.02 → 0.05），水平射程上限 = 初速 ÷ 0.05。
     */
    private static double flightTicksCart(double dh, double vx0) {
        if (dh <= 1.0E-4 || vx0 <= 1.0E-6) {
            return 1.0;
        }
        double base = 1.0 - 0.05 * dh / vx0;
        if (base <= 0.02) {
            return 200.0;
        }
        double n = Math.log(base) / Math.log(0.95);
        return Math.max(1.0, Math.min(200.0, n));
    }

    /** TNT 矿车版的出膛竖直速度（形状同 {@link #requiredVy}：0.02 → 0.05 ⇒ 那个 0.04 系数变成 0.8）。 */
    private static double requiredVyCart(double dh, double dy, double vx0) {
        double n = flightTicksCart(dh, vx0);
        double a = (1.0 - Math.pow(0.95, n)) / 0.05;
        double b = (n - a) / 0.05;
        double vy = (dy + 0.04 * b) / Math.max(1.0E-6, a) + 0.04;
        return Mth.m_14008_(vy, -1.5, 1.5);
    }

    private static int throwCount(EntityMaid maid) {
        // 实测九百四十五【压缩盒差异化】：盒子里存了 TNT → 一次多投（与低血爆发取大者）
        int boxBurst = BombItems.boxBurstTnt(maid);
        try {
            float max = maid.m_21233_();
            if (max > 0.0f && maid.m_21223_() / max <= BombConfig.cfgTntBurstRatio()) {
                return Math.max(boxBurst, Math.max(1, BombConfig.cfgTntBurstCount()));
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombThrow", ignored);
        }
        return boxBurst;
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
            // 【TNT 矿车：弹道另算一份】矿车离轨后的阻尼是 0.95（见 flightTicksCart 的 javap 实证），
            // 用引信 TNT 那条 0.98 的闭式解会系统性打短——所以这一发走矿车自己那套解。
            boolean cart = BombItems.isTntMinecart(tntStack);
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
                double dhd = Math.sqrt(ddx * ddx + ddz * ddz);
                double flight = cart ? flightTicksCart(dhd, speed) : flightTicks(dhd, speed);
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
            double vy = cart ? requiredVyCart(dh, dy, speed) : requiredVy(dh, dy, speed);
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

            // v1.2.2 实测五百九十【追踪】/ 实测五百九十一【限时】/ 实测九百八十九【物理体积】：
            // 登记这一发飞行监看——追踪只是其中附带的一小段转向（限时 cfgTntTrackTicks）；而
            // "撞到敌人就引爆"不管追踪开关都必须做（不然近身还是穿孔而过）。条目留到它自己
            // 炸掉为止，最迟 expire（引信 + 余量）兜底，防表泄漏。模组自己的 TNT 实体也继承
            // PrimedTnt，同样登记。
            // 【TNT 矿车不进这一档】它不是 PrimedTnt、也不吃追踪：它自己的"撞一下"是原版那套
            //（撞到方块/墙且水平速度 ≥ 0.1 就炸，javap 实证 MinecartTNT.tick），不需要我们接管。
            if (tnt != null && MaidBombing.HOMING.size() < MaidBombing.MAX_PENDING) {
                boolean track = BombConfig.cfgTntTrack() && BombConfig.cfgTntTrackTicks() > 0;
                MaidBombing.HOMING.add(new MaidBombing.Homing(level, maid, tnt, target,
                        Math.sqrt(vx * vx + vy * vy + vz * vz), track,
                        gameTime + BombConfig.cfgTntTrackTicks(),
                        gameTime + BombConfig.cfgTntFuse() + 100L));
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
            MaidBombing.SETTLE_NEXT.put(id, gameTime + MaidBombing.SETTLE_TNT_TICKS); // 实测九百九十五：离手 +3 秒静置
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
            // ── v1.3.2【TNT 矿车：认哪件就放它那一辆】──
            // 注册名带 tnt 的矿车物品本来就会被 isTnt 认到，但老兜底会把它换成一枚普通引信 TNT
            // ——矿车就没了。它自己的形式是**点着的 TNT 矿车**：撞到方块/墙、或引信到点都会炸，
            // 威力随水平速度涨（javap 实证 MinecartTNT：4.0 + rand×1.5×min(5, √水平速度²)），
            // 所以照「认出来的那一件就放它自己那一枚」的老规矩：放出它自己那一辆。
            // 地形权限（不破坏方块）与"她扔的"归因照模组 TNT 那套收回本模组开关管，登记见下。
            if (BombItems.isTntMinecart(stack)) {
                Entity cart = spawnTntMinecart(level, maid, sx, sy, sz);
                if (cart != null) {
                    return new TntOut(cart, false, false, "它自己那一辆 " + entityIdOf(cart)
                            + (writeMinecartFuse(cart, BombConfig.cfgTntFuse()) ? "" : "（引信写不进去，退回固定 80 tick）"));
                }
            }
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

    /** 【TNT 矿车】造一辆点着的 TNT 矿车；放不出来（构造炸了）就 null，走老兜底那一枚普通 TNT。 */
    private static Entity spawnTntMinecart(ServerLevel level, EntityMaid maid, double sx, double sy, double sz) {
        try {
            net.minecraft.world.entity.vehicle.MinecartTNT cart =
                    new net.minecraft.world.entity.vehicle.MinecartTNT(level, sx, sy, sz);
            // 与模组 TNT 同一张登记表：地形权限（不破坏方块）与"她扔的"归因都靠它兜住
            MaidTntBlastGuard.rememberTnt(cart, maid, level.m_46467_() + MaidTntBlastGuard.TRACK_TTL);
            return cart;
        } catch (Throwable t) {
            MaidBombing.log("TNT 矿车放出失败：" + t);
            return null;
        }
    }

    /**
     * 【TNT 矿车】把引信写成配置值。两版原版都**没有**任意引信写口（javap 实证：字段
     * {@code f_38647_} / {@code fuse} 是 private，公开口只有 {@code primeFuse()} 的固定 80），
     * 所以反射直写那个字段（手法同 BrewRecipeResolver 对原版私有字段）；两个名字都试，都拿不到就
     * 退回 {@code primeFuse()}（80 tick = 4 秒）并返回 false，让日志说清楚。
     */
    private static boolean writeMinecartFuse(Entity cart, int fuse) {
        for (String name : new String[]{"f_38647_", "fuse"}) {
            try {
                java.lang.reflect.Field f = net.minecraft.world.entity.vehicle.MinecartTNT.class
                        .getDeclaredField(name);
                f.setAccessible(true);
                f.setInt(cart, fuse);
                return true;
            } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombThrow", ignored);
            }
        }
        try {
            ((net.minecraft.world.entity.vehicle.MinecartTNT) cart).m_38693_();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombThrow", ignored);
        }
        return false;
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
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombThrow", ignored);
        }
        try {
            return e.getClass().getSimpleName();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombThrow", ignored);
            return "未知实体";
        }
    }
}
