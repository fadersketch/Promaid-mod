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
        } catch (Throwable ignored) {
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
        // ① v1.2.2 实测五百八十九【保留黑曜石】：起爆这一刻**不再立刻撤掉**水晶底座——
        //    黑曜石留在原地（水晶就是放在它上面的那副样子，爆炸不破坏方块时尤其明显），
        //    过 cfgReclaimSeconds() 秒（默认 10）再由我们回收。配置填 0 就起爆即回收。
        // ② v1.2.2 实测五百九十一【回收口径按种类分】：
        //    · 水晶链路：底座（黑曜石/基岩）**留着 → 到期回收到她背包**（绝不落地）；
        //    · 重生锚 / 床：它们**自己那一炸就把方块消耗掉了**（javap 实证：原版 use() 里先
        //      `removeBlock(pos, false)` 再 explode）——所以我们起爆这一刻直接撤掉、不进回收表、
        //      也不回背包（回背包 = 放一次白拿一个重生锚，那是白送炸药）。
        if (b.kind == MaidBombing.Kind.CRYSTAL) {
            MaidBombing.scheduleReclaim(level, maid, b.placedPos, b.placedBlock);
        } else if (b.placedPos != null) {
            BombPlacement.removePlaced(level, b.placedPos, b.placedBlock, null);
        }
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
        explode(level, maid, x, y, z, b.kind.power, b.kind.fire);
    }

    private static void explode(ServerLevel level, EntityMaid maid, double x, double y, double z, float power, boolean fire) {
        Level.ExplosionInteraction mode = BombConfig.cfgBreakBlocks()
                ? Level.ExplosionInteraction.BLOCK
                : Level.ExplosionInteraction.NONE;
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
            } catch (Throwable ignored) {
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
    }
}
