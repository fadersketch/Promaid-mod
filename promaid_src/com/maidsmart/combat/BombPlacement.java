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
 * 放置弹药（TNT/床/锚/水晶）到目标位置（v1.2.4 从 MaidBombing 拆出）。
 * 
 * 包括空投选点、支撑面查找、第三方占用检查、按距离排序、
 * 被别的相位占位的排除，以及放置失败时的回滚与物品归还。
 */
public final class BombPlacement {
    private BombPlacement() {
    }

    static boolean stepBlock(ServerLevel level, EntityMaid maid, BlockPos targetPos, MaidBombing.Phase ph) {
        ItemStack stack;
        if (ph.kind == MaidBombing.Kind.BED) {
            stack = BombItems.takeOneBed(maid);
        } else if (ph.kind == MaidBombing.Kind.CRYSTAL) {
            stack = BombItems.takeOne(maid, BombItems.has(maid, BombItems.ID_OBSIDIAN) ? BombItems.ID_OBSIDIAN : BombItems.ID_BEDROCK);
        } else {
            stack = BombItems.takeOne(maid, BombItems.ID_RESPAWN_ANCHOR);
        }
        if (stack.m_41619_()) {
            return false;
        }
        // v1.2.2 实测五百九十一【动作】：放置会把这 1 件消耗掉（place 内部 shrink）→ **先留快照**，
        // 拿它当"她手上正举着的那件东西"做动作（副手短暂亮一下，见 BombPose）
        ItemStack display = stack.m_41777_();
        display.m_41764_(1);
        ph.display = display;
        BlockPos spot = placeOnSupport(level, maid, targetPos, stack, ph.target);
        if (spot == null) {
            BombItems.giveBack(maid, stack);
            MaidBombing.log(ph.kind.cn + " 放不下（目标脚边 4 邻 / 她正下方 16 格 / 空中强制="
                    + (BombConfig.cfgAirPlace() ? "开" : "关") + " / 后门悬空投弹="
                    + (BombConfig.cfgAirDrop() ? "开" : "关") + " 都没成）→ 本次放弃轰炸");
            return false;
        }
        ph.spot = spot;
        Block placed = placedBlockOf(level, spot, stack);
        Direction facing = maid.m_6350_();
        for (BlockPos p : new BlockPos[]{spot, spot.m_121945_(facing)}) {
            if (placed != null && level.m_8055_(p).m_60734_() == placed) {
                ph.placed.add(p);
                ph.placedBlock.add(placed);
            }
        }
        maid.m_6674_(InteractionHand.MAIN_HAND);
        BombConfig.pose(maid, ph.display, MaidBombing.BOMB_POSE_TICKS);
        MaidBombing.log(com.maidsmart.tool.PromaidLog.nameOf(maid) + " 放下 " + ph.kind.cn
                + " @" + spot.m_123341_() + "," + spot.m_123342_() + "," + spot.m_123343_());
        return true;
    }

    static boolean stepPayload(ServerLevel level, EntityMaid maid, MaidBombing.Phase ph, long gameTime) {
        BlockPos base = ph.spot;
        if (base == null) {
            return false;
        }
        Entity spawned = null;
        if (ph.kind == MaidBombing.Kind.CRYSTAL) {
            ItemStack crystal = BombItems.takeOne(maid, BombItems.ID_END_CRYSTAL);
            if (crystal.m_41619_()) {
                MaidBombing.log("末地水晶取不到 → 放弃轰炸");
                return false;
            }
            EndCrystal ec = new EndCrystal(level, base.m_123341_() + 0.5, base.m_123342_() + 1.0, base.m_123343_() + 0.5);
            // 原版"拿末地水晶物品放在黑曜石上"就是 setShowBottom(false)（EndCrystalItem 反编译实证），
            // 不是末地柱子上那种带底座的形态——按玩家反馈改成普通放置的样子。
            ec.m_31056_(false);
            if (!level.m_7967_(ec)) {
                MaidBombing.log("末地水晶生成失败 → 放弃轰炸");
                return false;
            }
            spawned = ec;
            // 起爆那一刻副手亮的是水晶（"好像真的打了一下末影水晶"）
            ph.display = crystal.m_41777_();
            ph.display.m_41764_(1);
            maid.m_6674_(InteractionHand.MAIN_HAND);
            BombConfig.pose(maid, ph.display, MaidBombing.BOMB_POSE_TICKS);
        } else if (ph.kind == MaidBombing.Kind.ANCHOR) {
            // ── v1.2.2 实测五百九十二：这一段的动作照需求原话走 ──
            // "攻击→副手换成重生锚，放置重生锚（摆臂）→副手换成萤石，拿一颗萤石充能（摆臂动画）
            //  →继续切换回飞行。0.5 秒后再挥一次手臂。正好对上重生锚自爆"
            // step 0 已经放好重生锚（副手举的是它，见 stepBlock）；这里换萤石、充能、摆臂，
            // 副手这一下亮的是萤石；ph.display 不覆盖，起爆那一刻亮的仍是重生锚。
            ItemStack glow = ItemStack.f_41583_;
            if (BombConfig.cfgAnchorNeedsGlowstone()) {
                glow = BombItems.takeOne(maid, BombItems.ID_GLOWSTONE);
                if (glow.m_41619_()) {
                    MaidBombing.log("萤石取不到（重生锚要有 1 级充能才会炸）→ 放弃轰炸");
                    return false;
                }
                BombConfig.pose(maid, glow, MaidBombing.BOMB_POSE_TICKS); // 副手这时换成的就是萤石
            }
            // 照原版 charge：音效 + 充能等级 +1（等级只决定"炸不炸"，1 级足够；< 4 只是防越界）
            try {
                BlockState cur = level.m_8055_(base);
                if (cur.m_60734_() instanceof net.minecraft.world.level.block.RespawnAnchorBlock
                        && cur.m_61143_(net.minecraft.world.level.block.RespawnAnchorBlock.f_55833_) < 4) {
                    net.minecraft.world.level.block.RespawnAnchorBlock.m_269573_(maid, level, base, cur);
                }
            } catch (Throwable ignored) {
            }
            maid.m_6674_(InteractionHand.MAIN_HAND);
            if (glow.m_41619_()) {
                BombConfig.pose(maid, ph.display, MaidBombing.BOMB_POSE_TICKS); // 没消耗萤石（开关关掉）→ 亮重生锚
            }
        }
        List<BlockPos> posList = ph.placed.isEmpty() ? null : new ArrayList<>(ph.placed);
        List<Block> blockList = ph.placedBlock.isEmpty() ? null : new ArrayList<>(ph.placedBlock);
        if (MaidBombing.PENDING.size() < MaidBombing.MAX_PENDING) {
            MaidBombing.PENDING.add(new MaidBombing.Bomb(level, maid, spawned, base, posList, blockList, ph.kind,
                    gameTime + BombConfig.cfgFuse(), ph.display));
        }
        if (BombConfig.cfgPinkMark()) {
            if (spawned != null) {
                BombMarkNetworking.send(maid, 1, spawned.m_19879_(), null, BombConfig.cfgFuse() + 20);
            } else {
                BombMarkNetworking.send(maid, 0, 0, base, BombConfig.cfgFuse() + 20);
            }
        }
        MaidBombing.log(com.maidsmart.tool.PromaidLog.nameOf(maid) + " " + ph.kind.cn + " 就位，"
                + BombConfig.cfgFuse() + " tick 后起爆（威力 " + ph.kind.power + (ph.kind.fire ? "，带火" : "") + "）");
        return true;
    }

    static void rollback(ServerLevel level, MaidBombing.Phase ph) {
        removePlaced(level, ph.placed, ph.placedBlock, ph.maid, false);
        ph.placed.clear();
        ph.placedBlock.clear();
    }

    /**
     * 撤掉这一批"女仆放的方块"并按口径归还（床走抑制形状更新那一路，其余单体方块走
     * {@code removeBlock(pos, false)} 不掉落，再按方块 id 合成物品还她）。
     *
     * v1.3.8 实测六百七十：为了"她飞远之后那块黑曜石也收得回来"，这里多了两样东西——
     * <ul>
     *   <li><b>{@code requireLoaded}</b>：true = 只在<b>已经加载</b>的区块里动手；只要有一个
     *       格子躺在没加载的区块里就<b>整批不动</b>、直接返回 false。旧版无条件读
     *       {@code level.getBlockState(pos)}，而它会走 {@code Level.getChunkAt(pos)} →
     *       {@code getChunk(x, z, FULL, requireChunk=true)}（javap 实证：{@code LevelReader
     *       .m_46819_} 里就是 {@code iconst_1}）：轻则在 tick 里<b>同步强制加载一片地形</b>，
     *       重则加载失败抛 {@code IllegalStateException}（失败分支见
     *       {@code ServerChunkCache.m_8421_}），把调用者的回收条目连着一起带走。</li>
     *   <li><b>返回值</b>：true = 这一批处理完了（该撤的撤了 / 那格早就不是我们放的东西）；
     *       false = 还没处理（区块没加载）→ 调用方留着条目下一 tick 再试。</li>
     * </ul>
     * 起爆那一刻（重生锚 / 床那一炸）与相位回滚仍传 false：它们都跑在"她本人就在旁边"的
     * 近距场景里，行为与改动前一字不变。
     */
    static boolean removePlaced(ServerLevel level, List<BlockPos> posList, List<Block> blockList,
                                EntityMaid returnTo, boolean requireLoaded) {
        if (level == null || posList == null || blockList == null) {
            return true; // 没东西可撤
        }
        if (requireLoaded) {
            for (BlockPos p : posList) {
                if (p != null && !level.m_46749_(p)) {
                    return false; // 还有格子躺在没加载的区块里 → 整批留着，下一 tick 再试
                }
            }
        }
        for (int i = 0; i < posList.size() && i < blockList.size(); i++) {
            BlockPos p = posList.get(i);
            Block want = blockList.get(i);
            BlockState st = level.m_8055_(p);
            if (st.m_60734_() != want) {
                continue; // 已经被别人换掉/炸掉 → 别动
            }
            if (st.m_60734_() instanceof BedBlock) {
                // 床是两格：带"抑制形状更新"的标志位拆，否则另一格会按原版逻辑掉一张床（白送材料）
                // v1.2.5 修复：这里原写 Blocks.f_49990_，那是 **water**（1.20.1 SRG 实证；
                // 本工程 SelfPreservationBehavior 也拿它当水用）——于是女仆放的床一炸，
                // 原处留下的是一格水而不是空气（实测反馈："放床爆炸不产生火而是水"：
                // 水把那一格占了，原版点火的判据正是"那一格是空气"→ 火自然也点不着）。
                // 空气的 SRG 名是 f_50016_（同一份 Blocks 实证，本工程其余 30 余处都写它）。
                level.m_7731_(p, Blocks.f_50016_.m_49966_(), 2 | 16);
            } else {
                level.m_7471_(p, false); // 单体方块：removeBlock 不掉落
            }
            if (returnTo != null) {
                returnBlockItem(returnTo, want);
            }
        }
        return true;
    }

    private static void returnBlockItem(EntityMaid maid, Block block) {
        if (maid == null || block == null) {
            return;
        }
        try {
            ItemStack st = new ItemStack(block.m_5456_());
            if (st.m_41619_()) {
                return; // 没有对应物品（空气之类）→ 不还原
            }
            ItemStack left = net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(
                    maid.getAvailableBackpackInv(), st, false);
            if (!left.m_41619_()) {
                maid.m_5552_(left, 0.5f); // 背包满 → 掉在她脚下（实测五百九十三）
                MaidBombing.log("回收的炸弹底座塞不进背包（背包满）→ 掉在她脚下");
            }
        } catch (Throwable ignored) {
        }
    }

    private static final int AIR_SCAN_DROP = 16;

    private static BlockPos placeOnSupport(ServerLevel level, EntityMaid maid, BlockPos tp, ItemStack stack,
                                          LivingEntity target) {
        BlockPos maidFeet = maid.m_20183_();
        BlockPos maidHead = maidFeet.m_7494_();
        List<BlockPos> near = new ArrayList<>(4);
        for (Direction d : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            near.add(tp.m_121945_(d));
        }
        sortByDistToMaid(near, maid);
        List<BlockPos> below = new ArrayList<>(AIR_SCAN_DROP);
        for (int dy = 1; dy <= AIR_SCAN_DROP; dy++) {
            below.add(maidFeet.m_5484_(Direction.DOWN, dy));
        }
        BlockPos spot = tryPlaceAll(level, maid, stack, near, maidFeet, maidHead, true);
        if (spot != null) {
            return spot;
        }
        spot = tryPlaceAll(level, maid, stack, below, maidFeet, maidHead, true);
        if (spot != null) {
            return spot;
        }
        if (BombConfig.cfgAirPlace()) {
            // 空中强制放置（不要求支撑面）
            spot = tryPlaceAll(level, maid, stack, near, maidFeet, maidHead, false);
            if (spot != null) {
                return spot;
            }
            spot = tryPlaceAll(level, maid, stack, below, maidFeet, maidHead, false);
            if (spot != null) {
                return spot;
            }
        }
        // v1.2.2 实测六百〇三【走后门】：最后一级——目标头顶 / 目标自己那一格，悬空也认
        return BombConfig.cfgAirDrop() ? tryPlaceAirDrop(level, maid, stack, tp, target, maidFeet, maidHead) : null;
    }

    private static BlockPos tryPlaceAirDrop(ServerLevel level, EntityMaid maid, ItemStack stack, BlockPos tp,
                                           LivingEntity target, BlockPos maidFeet, BlockPos maidHead) {
        List<BlockPos> cand = new ArrayList<>(2);
        BlockPos aboveTarget = tp.m_7494_();
        if (!occupiedByThirdParty(level, aboveTarget, target)) {
            cand.add(aboveTarget); // ① 目标头顶那一格（最自然：像"照头砸下来"）
        }
        if (!occupiedByThirdParty(level, tp, target)) {
            cand.add(tp);          // ② 目标自己那一格（贴脸）
        }
        if (cand.isEmpty()) {
            return null;
        }
        BlockPos spot = tryPlaceAll(level, maid, stack, cand, maidFeet, maidHead, false);
        if (spot != null) {
            MaidBombing.log("走后门：目标那一格 / 头顶那一格悬空放下 @" + spot.m_123341_() + ","
                    + spot.m_123342_() + "," + spot.m_123343_() + "（落点无支撑面也算数）");
        }
        return spot;
    }

    private static boolean occupiedByThirdParty(ServerLevel level, BlockPos p, LivingEntity allowed) {
        if (level == null || p == null) {
            return true;
        }
        try {
            for (LivingEntity le : level.m_6443_(LivingEntity.class,
                    new net.minecraft.world.phys.AABB(p), e -> true)) {
                if (le != allowed) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void sortByDistToMaid(List<BlockPos> list, EntityMaid maid) {
        final double mx = maid.m_20185_();
        final double my = maid.m_20186_();
        final double mz = maid.m_20189_();
        list.sort(Comparator.comparingDouble(p -> {
            double dx = p.m_123341_() + 0.5 - mx;
            double dy = p.m_123342_() + 0.5 - my;
            double dz = p.m_123343_() + 0.5 - mz;
            return dx * dx + dy * dy + dz * dz;
        }));
    }

    private static BlockPos tryPlaceAll(ServerLevel level, EntityMaid maid, ItemStack stack, List<BlockPos> cand,
                                       BlockPos maidFeet, BlockPos maidHead, boolean requireSupport) {
        for (BlockPos p : cand) {
            if (p.equals(maidFeet) || p.equals(maidHead)) {
                continue; // 别把自己埋了
            }
            if (claimedByOtherPhase(maid, p)) {
                continue; // 她**另一段**链路已经占了这一格（实测六百：三段并行时各占各的落点）
            }
            BlockPos support = p.m_7495_();
            boolean supported = level.m_8055_(support).m_60783_(level, support, Direction.UP);
            if (requireSupport && !supported) {
                continue;
            }
            // 有支撑：贴在支撑面顶上放；无支撑：直接对着这一格放（悬空强制）
            BlockHitResult hit = supported
                    ? new BlockHitResult(new Vec3(p.m_123341_() + 0.5, p.m_123342_(), p.m_123343_() + 0.5),
                            Direction.UP, support, false)
                    : new BlockHitResult(new Vec3(p.m_123341_() + 0.5, p.m_123342_() + 0.5, p.m_123343_() + 0.5),
                            Direction.UP, p, false);
            BlockPlaceContext ctx = new MaidPlaceContext(level, maid, InteractionHand.MAIN_HAND, stack, hit);
            try {
                InteractionResult r = ((BlockItem) stack.m_41720_()).m_40576_(ctx);
                if (r != null && r.m_19077_()) {
                    return ctx.m_8083_();
                }
                // v1.2.2 实测五百九十三【兜底强制放置】：原版 place() 会拒绝一些它认为不合法的
                // 落点——最典型的是"那一格里站着实体"（BlockItem.canPlace 里的 isUnobstructed），
                // 而我们的落点恰恰常常在**目标脚边**（反馈："重生锚还是放不下来"）。这里在它拒绝
                // 之后补一手：那一格确实**可替换**（空气 / 草 / 水…）且不是床（床是两格，强制单格
                // 会留下半张床）→ 直接 setBlock 放下去。只多做这一步，别的判定一概不动。
                if (level.m_8055_(p).m_60629_(ctx)) {
                    Block bi = ((BlockItem) stack.m_41720_()).m_40614_();
                    if (bi != null && !(bi instanceof BedBlock)
                            && level.m_7731_(p, bi.m_49966_(), 3)) {
                        MaidBombing.log("原版拒绝了落点，已强制放下（那一格站着实体或形状不合规）");
                        return p;
                    }
                }
            } catch (Throwable t) {
                MaidBombing.log("放置异常：" + t);
            }
        }
        return null;
    }

    private static boolean claimedByOtherPhase(EntityMaid maid, BlockPos p) {
        try {
            EnumMap<MaidBombing.Kind, MaidBombing.Phase> map = MaidBombing.PHASE.get(maid.m_20148_());
            if (map == null || p == null) {
                return false;
            }
            for (MaidBombing.Phase ph : map.values()) {
                if (ph == null) {
                    continue;
                }
                if (p.equals(ph.spot) || ph.placed.contains(p)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static Block placedBlockOf(ServerLevel level, BlockPos spot, ItemStack stack) {
        try {
            if (stack.m_41720_() instanceof BlockItem bi) {
                return bi.m_40614_();
            }
        } catch (Throwable ignored) {
        }
        return level.m_8055_(spot).m_60734_();
    }

    private static final class MaidPlaceContext extends BlockPlaceContext {
        private final Entity placer;

        MaidPlaceContext(Level level, Entity placer, InteractionHand hand, ItemStack stack, BlockHitResult hit) {
            super(level, null, hand, stack, hit);
            this.placer = placer;
        }

        @Override
        public Direction m_8125_() {
            return this.placer.m_6350_();
        }

        @Override
        public float m_7074_() {
            return this.placer.m_146908_();
        }

        @Override
        public Direction m_7820_() {
            return Direction.m_122382_(this.placer)[0];
        }

        @Override
        public Direction m_151260_() {
            return Direction.m_175357_(this.placer, Direction.Axis.Y);
        }

        @Override
        public Direction[] m_6232_() {
            Direction[] dirs = Direction.m_122382_(this.placer);
            if (this.f_43628_) {
                return dirs;
            }
            Direction face = this.m_43719_().m_122424_();
            Direction[] out = dirs.clone();
            int i = 0;
            while (i < out.length && out[i] != face) {
                i++;
            }
            if (i > 0) {
                System.arraycopy(out, 0, out, 1, i);
                out[0] = face;
            }
            return out;
        }
    }
}
