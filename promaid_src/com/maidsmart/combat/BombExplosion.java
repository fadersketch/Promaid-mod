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
 * 爆炸来源标记与自定义爆炸（v1.2.4 从 MaidBombing 拆出）。
 * 
 * vanillaBlast / selfImmuneBlast 两个 volatile 标记（给免伤与友军判定读），
 * 以及拆弹：先给方块实体结算再走 detonate/explode。
 */
public final class BombExplosion {
    private BombExplosion() {
    }

    private static volatile boolean vanillaBlast = false;

    public static boolean inVanillaBlast() {
        return vanillaBlast;
    }

    private static volatile boolean selfImmuneBlast = false;

    public static boolean isSelfImmuneBlast() {
        return selfImmuneBlast;
    }

    private static volatile java.util.UUID selfImmuneMaidId = null;

    public static boolean isSelfImmuneBlastFor(Entity victim) {
        if (!selfImmuneBlast || victim == null) {
            return false;
        }
        try {
            java.util.UUID caster = selfImmuneMaidId;
            return caster != null && caster.equals(victim.m_20148_());
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombExplosion", ignored);
            return false;
        }
    }

    static void detonate(MaidBombing.Bomb b) {
        ServerLevel level = b.level;
        EntityMaid maid = b.maid;
        double x;
        double y;
        double z;
        if (b.entity != null) {
            x = b.entity.m_20185_();
            y = b.entity.m_20186_();
            z = b.entity.m_20189_();
        } else {
            Vec3 c = b.pos.m_252807_();
            x = c.f_82479_;
            y = c.f_82480_;
            z = c.f_82481_;
        }
        // 起爆这一刻的收尾口径（两种）：① 水晶链路：黑曜石/基岩底座**不再立刻撤掉**，留原地
        // （水晶就放在它上面的样子），过 cfgReclaimSeconds() 秒（默认 10）再由我们回收，到期回收
        // 到她背包（绝不落地）；填 0 = 起爆即回收。② 重生锚 / 床：它们自己那一炸就把方块消耗掉了
        // （原版 use() 先 removeBlock 再 explode），所以起爆这一刻直接撤掉、不进回收表、也不回背包
        // （回背包 = 放一次白拿一个，等于白送炸药）。两种收尾都在 MaidBombing.settleBase（口径只有
        // 一处），与"炸弹半路作废"那三条路共用同一份定义。
        MaidBombing.settleBase(b);
        // ③ 炸弹实体本身清掉（不能走 kill()——末地水晶的 kill 会触发原版那一炸）
        if (b.entity != null && b.entity.m_6084_()) {
            b.entity.m_142687_(Entity.RemovalReason.DISCARDED);
        }
        // ④ v1.2.2 实测五百九十一【起爆那一记挥臂】：反馈"放置完重生锚/末地水晶后 0.5s 也会有一个
        //    挥臂的动作（好像真的打了一下末影水晶/重生锚）"——只有她还在近处（8 格内）才做，
        //    副手亮的是当时用的那一件（水晶 / 重生锚 / 床 / TNT）。
        if (b.display != null && maid != null && maid.m_6084_()) {
            double ddx = maid.m_20185_() - x;
            double ddy = maid.m_20186_() - y;
            double ddz = maid.m_20189_() - z;
            if (ddx * ddx + ddy * ddy + ddz * ddz <= 64.0) {
                maid.m_6674_(InteractionHand.MAIN_HAND);
                BombConfig.pose(maid, b.display, MaidBombing.BLAST_POSE_TICKS);
            }
        }
        // 【实测一千〇六十】末地水晶 / 压缩盒连炸：原版 LivingEntity.hurt 在
        // invulnerableTime > 10 时会把"伤害 ≤ 上一记"的命中直接吞掉——五枚叠放在同一
        // tick 起爆时，第二枚起全是 0 伤害，玩家看到的就是"五枚只吃一枚、甚至完全打不到"。
        // 每一炸前清掉爆心范围内活体的无敌帧，让每一枚按自己的威力完整结算；
        // 爆炸本身会重新写入无敌帧，所以只影响这一次连续爆发。
        // 水晶恒走这条（刚被平A打中的目标也有无敌帧，水晶照样该炸出伤害）；
        // 重生锚的并排压缩爆发由 burst 标记。
        if (b.kind == MaidBombing.Kind.CRYSTAL || b.burst) {
            clearInvulnerability(level, maid, x, y, z, b.kind.power);
        }
        explode(level, maid, x, y, z, b.kind.power, b.kind.fire, b.kind.cn);
    }

    /**
     * 把爆心附近活体的受击无敌帧清零（放过放炸弹的女仆与友军）。每炸一次都会重新写入，
     * 所以只作用于紧跟着的那一次爆炸结算。
     */
    private static void clearInvulnerability(ServerLevel level, EntityMaid maid,
                                             double x, double y, double z, float power) {
        try {
            double r = Math.max(1.0, power * 2.0);
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                    x - r, y - r, z - r, x + r, y + r, z + r);
            for (LivingEntity le : level.m_45976_(LivingEntity.class, box)) {
                if (le == null || le == maid) {
                    continue;
                }
                if (maid != null && FriendlyFireGuard.isFriendly(maid, le)) {
                    continue; // 主人 / 同主女仆 / 友军本来就不吃这一炸，别动他们的无敌帧
                }
                le.f_19802_ = 0;
            }
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("BombExplosion", ignored);
        }
    }

    private static void explode(ServerLevel level, EntityMaid maid, double x, double y, double z, float power, boolean fire, String label) {
        Level.ExplosionInteraction mode = BombConfig.cfgBreakBlocks()
                ? Level.ExplosionInteraction.BLOCK
                : Level.ExplosionInteraction.NONE;
        // 【实测一千〇六十·伤害探针】起爆前记下半径内每个非友方活体的血量，起爆后对比。
        // 这样日志能直接回答"五枚叠放到底每枚打掉多少、有没有一枚都没打中"。
        List<LivingEntity> probeVictims = new ArrayList<>();
        List<Float> probeBefore = new ArrayList<>();
        try {
            double r = Math.max(1.0, power * 2.0);
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                    x - r, y - r, z - r, x + r, y + r, z + r);
            for (LivingEntity le : level.m_45976_(LivingEntity.class, box)) {
                if (le == null || le == maid) {
                    continue;
                }
                if (maid != null && FriendlyFireGuard.isFriendly(maid, le)) {
                    continue;
                }
                probeVictims.add(le);
                probeBefore.add(le.m_21223_());
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombExplosion", ignored);
        }
        // v1.2.2 实测五百九十七【粉色火焰】：原版这一炸会**自己点火**——javap 实证
        // （Explosion.finalizeExplosion）着火那段在 `interactsWithBlocks()` 之外，只看 fire=true，
        // 所以哪怕我们默认"不破坏方块"（ExplosionInteraction.NONE → BlockInteraction.KEEP），
        // 重生锚 / 床那一炸照样在地上留下原版橙色火（末地水晶 / TNT 原版 fire=false，不留火）。
        // v1.2.2 实测五百九十八【改法换代】：旧版是"爆炸后按盒子把新出现的原版火换成粉色"，
        // 而原版点火的那些格子是**射线**扫出来的（空气阻力 0 → 射线能跑二十格开外），盒子永远
        // 罩不住 → 实测"又粉又橙"。现在换成"边点边换"：爆炸期间开着点火窗口，原版点火那一刻
        // 拿到手的就是粉色火（见 PinkFireBlock.beginWindow 与 BaseFireBlockPinkMixin）。
        boolean pinkWindow = fire && BombConfig.cfgPinkFire();
        if (pinkWindow) {
            PinkFireBlock.beginWindow();
        }
        // v1.2.2 实测五百九十三【特效对齐原版】：反馈"爆炸的特效太小了，比正常生成的末地水晶和
        // TNT 要小很多"。javap 实证原因就在这个模式上——原版 {@code Explosion.finalizeExplosion}
        // 选粒子是"半径 ≥ 2 且 interactsWithBlocks()"才用大粒子 EXPLOSION_EMITTER，否则用小的
        // EXPLOSION；而 ExplosionInteraction.NONE 的 interactsWithBlocks() **恒为 false**
        //（我们默认不破坏方块），于是永远走小粒子分支。修法：不破坏方块时**自己补发一枚大粒子**
        //（服务端广播给附近玩家）；只补视觉，伤害 / 击退 / 地形一概不动。
        if (!BombConfig.cfgBreakBlocks()) {
            try {
                level.m_8767_(net.minecraft.core.particles.ParticleTypes.f_123812_,
                        x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
            } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombExplosion", ignored);
            }
        }
        // v1.2.2 实测五百八十八：整个爆炸期间挂"自爆风免"窗口——这个机制的风对放炸弹的她本人
        // 也不生效（与重锤风爆不同）。窗口是同步的，explode() 返回即关。
        selfImmuneBlast = true;
        selfImmuneMaidId = maid == null ? null : maid.m_20148_();
        try {
            if (!BombConfig.cfgHurtFriendly()) {
                // 默认口径：**把女仆当爆炸来源**（伤害源交给原版按来源实体自己构造）。
                // 于是 damageSource.getEntity() == 女仆 → FriendlyFireGuard 取消主人/同主女仆/友军的
                // 伤害；Explosion 的来源实体同样是她 → FriendlyWindGuard 的击退豁免也一并生效。
                level.m_254951_(maid, null, null, new net.minecraft.world.phys.Vec3(x, y, z), power, fire, mode);
            } else {
                // 原版口径：来源实体留空 = 完全不归因（主人/友军照掉血照被炸飞），
                // 并用 vanillaBlast 让风免在这一瞬间让位，避免"血掉了、人没飞"。
                vanillaBlast = true;
                try {
                    level.m_254951_(null, null, null, new net.minecraft.world.phys.Vec3(x, y, z), power, fire, mode);
                } finally {
                    vanillaBlast = false;
                }
            }
        } finally {
            selfImmuneBlast = false;
            selfImmuneMaidId = null;
            // 关窗口（若开着）：这一炸点着的每一格都已经是粉色火，这里只把条数取出来写日志
            // v1.2.2 实测六百〇一【范围收回】：旧版这里顺手把场地里既有的原版火也扫成粉色，
            // 反馈"你这样等于直接开挂了呀"——那些火不是她点的，本模组一概不碰，已删掉那一步。
            if (pinkWindow) {
                int pink = PinkFireBlock.endWindow();
                if (pink > 0) {
                    MaidBombing.log("粉色火焰：这一炸点着的 " + pink + " 格火直接生成为粉色火");
                }
            }
        }
        // 【实测一千〇六十·伤害探针】起爆后逐个体比对血量，写出这一炸的真实战绩。
        // 判读口径：一行 = 一枚炸弹；若所有目标都没掉血，会写明"半径内无掉血目标"。
        try {
            StringBuilder sb = new StringBuilder();
            int lost = 0;
            for (int i = 0; i < probeVictims.size(); i++) {
                LivingEntity le = probeVictims.get(i);
                float b = probeBefore.get(i);
                float a = le.m_21223_();
                if (a < b - 0.05f) {
                    lost++;
                    String name = le.m_5446_() == null
                            ? le.m_6095_().toString()
                            : le.m_5446_().getString();
                    sb.append(name).append(' ')
                            .append(String.format(java.util.Locale.ROOT, "%.1f→%.1f（-%s）；", b, a,
                                    String.format(java.util.Locale.ROOT, "%.1f", b - a)));
                }
            }
            MaidBombing.log(label + " 起爆 @" + String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.1f", x, y, z)
                    + " 威力 " + power + "："
                    + (lost == 0 ? "半径内无掉血目标" : sb.toString()));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BombExplosion", ignored);
        }
    }
}
