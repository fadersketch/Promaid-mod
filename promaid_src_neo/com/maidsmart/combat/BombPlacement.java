package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import net.neoforged.neoforge.items.IItemHandler;
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
import net.minecraft.core.registries.BuiltInRegistries;
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
        if (stack.isEmpty()) {
            return false;
        }
        // v1.2.2 实测五百九十一【动作】：放置会把这 1 件消耗掉（place 内部 shrink）→ **先留快照**，
        // 拿它当"她手上正举着的那件东西"做动作（副手短暂亮一下，见 BombPose）
        ItemStack display = stack.copy();
        display.setCount(1);
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
        Direction facing = maid.getDirection();
        for (BlockPos p : new BlockPos[]{spot, spot.relative(facing)}) {
            if (placed != null && level.getBlockState(p).getBlock() == placed) {
                ph.placed.add(p);
                ph.placedBlock.add(placed);
            }
        }
        maid.swing(InteractionHand.MAIN_HAND);
        BombConfig.pose(maid, ph.display, MaidBombing.BOMB_POSE_TICKS);
        MaidBombing.log(com.maidsmart.tool.PromaidLog.nameOf(maid) + " 放下 " + ph.kind.cn
                + " @" + spot.getX() + "," + spot.getY() + "," + spot.getZ());
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
            if (crystal.isEmpty()) {
                MaidBombing.log("末地水晶取不到 → 放弃轰炸");
                return false;
            }
            EndCrystal ec = new EndCrystal(level, base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
            // 原版"拿末地水晶物品放在黑曜石上"就是 setShowBottom(false)（EndCrystalItem 反编译实证），
            // 不是末地柱子上那种带底座的形态——按玩家反馈改成普通放置的样子。
            ec.setShowBottom(false);
            if (!level.addFreshEntity(ec)) {
                MaidBombing.log("末地水晶生成失败 → 放弃轰炸");
                return false;
            }
            spawned = ec;
            // 起爆那一刻副手亮的是水晶（"好像真的打了一下末影水晶"）
            ph.display = crystal.copy();
            ph.display.setCount(1);
            maid.swing(InteractionHand.MAIN_HAND);
            BombConfig.pose(maid, ph.display, MaidBombing.BOMB_POSE_TICKS);
        } else if (ph.kind == MaidBombing.Kind.ANCHOR) {
            // ── v1.2.2 实测五百九十二：这一段的动作照需求原话走 ──
            // "攻击→副手换成重生锚，放置重生锚（摆臂）→副手换成萤石，拿一颗萤石充能（摆臂动画）
            //  →继续切换回飞行。0.5 秒后再挥一次手臂。正好对上重生锚自爆"
            // step 0 已经放好重生锚（副手举的是它，见 stepBlock）；这里换萤石、充能、摆臂，
            // 副手这一下亮的是萤石；ph.display 不覆盖，起爆那一刻亮的仍是重生锚。
            ItemStack glow = ItemStack.EMPTY;
            if (BombConfig.cfgAnchorNeedsGlowstone()) {
                glow = BombItems.takeOne(maid, BombItems.ID_GLOWSTONE);
                if (glow.isEmpty()) {
                    MaidBombing.log("萤石取不到（重生锚要有 1 级充能才会炸）→ 放弃轰炸");
                    return false;
                }
                BombConfig.pose(maid, glow, MaidBombing.BOMB_POSE_TICKS); // 副手这时换成的就是萤石
            }
            // 照原版 charge：音效 + 充能等级 +1（等级只决定"炸不炸"，1 级足够；< 4 只是防越界）
            try {
                BlockState cur = level.getBlockState(base);
                if (cur.getBlock() instanceof net.minecraft.world.level.block.RespawnAnchorBlock
                        && cur.getValue(net.minecraft.world.level.block.RespawnAnchorBlock.CHARGE) < 4) {
                    net.minecraft.world.level.block.RespawnAnchorBlock.charge(maid, level, base, cur);
                }
            } catch (Throwable ignored) {
            }
            maid.swing(InteractionHand.MAIN_HAND);
            if (glow.isEmpty()) {
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
                BombMarkNetworking.send(maid, 1, spawned.getId(), null, BombConfig.cfgFuse() + 20);
            } else {
                BombMarkNetworking.send(maid, 0, 0, base, BombConfig.cfgFuse() + 20);
            }
        }
        MaidBombing.log(com.maidsmart.tool.PromaidLog.nameOf(maid) + " " + ph.kind.cn + " 就位，"
                + BombConfig.cfgFuse() + " tick 后起爆（威力 " + ph.kind.power + (ph.kind.fire ? "，带火" : "") + "）");
        return true;
    }

    static void rollback(ServerLevel level, MaidBombing.Phase ph) {
        removePlaced(level, ph.placed, ph.placedBlock, ph.maid);
        ph.placed.clear();
        ph.placedBlock.clear();
    }

    static void removePlaced(ServerLevel level, List<BlockPos> posList, List<Block> blockList,
                                     EntityMaid returnTo) {
        if (level == null || posList == null || blockList == null) {
            return;
        }
        for (int i = 0; i < posList.size() && i < blockList.size(); i++) {
            BlockPos p = posList.get(i);
            Block want = blockList.get(i);
            BlockState st = level.getBlockState(p);
            if (st.getBlock() != want) {
                continue; // 已经被别人换掉/炸掉 → 别动
            }
            if (st.getBlock() instanceof BedBlock) {
                // 床是两格：带"抑制形状更新"的标志位拆，否则另一格会按原版逻辑掉一张床（白送材料）
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
            } else {
                level.removeBlock(p, false); // 单体方块：removeBlock 不掉落
            }
            if (returnTo != null) {
                returnBlockItem(returnTo, want);
            }
        }
    }

    private static void returnBlockItem(EntityMaid maid, Block block) {
        if (maid == null || block == null) {
            return;
        }
        try {
            ItemStack st = new ItemStack(block.asItem());
            if (st.isEmpty()) {
                return; // 没有对应物品（空气之类）→ 不还原
            }
            ItemStack left = net.neoforged.neoforge.items.ItemHandlerHelper.insertItemStacked(
                    maid.getAvailableBackpackInv(), st, false);
            if (!left.isEmpty()) {
                maid.spawnAtLocation(left, 0.5f); // 背包满 → 掉在她脚下（实测五百九十三）
                MaidBombing.log("回收的炸弹底座塞不进背包（背包满）→ 掉在她脚下");
            }
        } catch (Throwable ignored) {
        }
    }

    private static final int AIR_SCAN_DROP = 16;

    private static BlockPos placeOnSupport(ServerLevel level, EntityMaid maid, BlockPos tp, ItemStack stack,
                                          LivingEntity target) {
        BlockPos maidFeet = maid.blockPosition();
        BlockPos maidHead = maidFeet.above();
        List<BlockPos> near = new ArrayList<>(4);
        for (Direction d : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            near.add(tp.relative(d));
        }
        sortByDistToMaid(near, maid);
        List<BlockPos> below = new ArrayList<>(AIR_SCAN_DROP);
        for (int dy = 1; dy <= AIR_SCAN_DROP; dy++) {
            below.add(maidFeet.relative(Direction.DOWN, dy));
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
        BlockPos aboveTarget = tp.above();
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
            MaidBombing.log("走后门：目标那一格 / 头顶那一格悬空放下 @" + spot.getX() + ","
                    + spot.getY() + "," + spot.getZ() + "（落点无支撑面也算数）");
        }
        return spot;
    }

    private static boolean occupiedByThirdParty(ServerLevel level, BlockPos p, LivingEntity allowed) {
        if (level == null || p == null) {
            return true;
        }
        try {
            for (LivingEntity le : level.getEntitiesOfClass(LivingEntity.class,
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
        final double mx = maid.getX();
        final double my = maid.getY();
        final double mz = maid.getZ();
        list.sort(Comparator.comparingDouble(p -> {
            double dx = p.getX() + 0.5 - mx;
            double dy = p.getY() + 0.5 - my;
            double dz = p.getZ() + 0.5 - mz;
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
            BlockPos support = p.below();
            boolean supported = level.getBlockState(support).isFaceSturdy(level, support, Direction.UP);
            if (requireSupport && !supported) {
                continue;
            }
            // 有支撑：贴在支撑面顶上放；无支撑：直接对着这一格放（悬空强制）
            BlockHitResult hit = supported
                    ? new BlockHitResult(new Vec3(p.getX() + 0.5, p.getY(), p.getZ() + 0.5),
                            Direction.UP, support, false)
                    : new BlockHitResult(new Vec3(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5),
                            Direction.UP, p, false);
            BlockPlaceContext ctx = new MaidPlaceContext(level, maid, InteractionHand.MAIN_HAND, stack, hit);
            try {
                InteractionResult r = ((BlockItem) stack.getItem()).place(ctx);
                if (r != null && r.consumesAction()) {
                    return ctx.getClickedPos();
                }
                // v1.2.2 实测五百九十三【兜底强制放置】：原版 place() 会拒绝一些它认为不合法的
                // 落点——最典型的是"那一格里站着实体"（BlockItem.canPlace 里的 isUnobstructed），
                // 而我们的落点恰恰常常在**目标脚边**（反馈："重生锚还是放不下来"）。这里在它拒绝
                // 之后补一手：那一格确实**可替换**（空气 / 草 / 水…）且不是床（床是两格，强制单格
                // 会留下半张床）→ 直接 setBlock 放下去。只多做这一步，别的判定一概不动。
                if (level.getBlockState(p).canBeReplaced(ctx)) {
                    Block bi = ((BlockItem) stack.getItem()).getBlock();
                    if (bi != null && !(bi instanceof BedBlock)
                            && level.setBlock(p, bi.defaultBlockState(), 3)) {
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
            EnumMap<MaidBombing.Kind, MaidBombing.Phase> map = MaidBombing.PHASE.get(maid.getUUID());
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
            if (stack.getItem() instanceof BlockItem bi) {
                return bi.getBlock();
            }
        } catch (Throwable ignored) {
        }
        return level.getBlockState(spot).getBlock();
    }

    private static final class MaidPlaceContext extends BlockPlaceContext {
        private final Entity placer;

        MaidPlaceContext(Level level, Entity placer, InteractionHand hand, ItemStack stack, BlockHitResult hit) {
            super(level, null, hand, stack, hit);
            this.placer = placer;
        }

        @Override
        public Direction getHorizontalDirection() {
            return this.placer.getDirection();
        }

        @Override
        public float getRotation() {
            return this.placer.getYRot();
        }

        @Override
        public Direction getNearestLookingDirection() {
            return Direction.orderedByNearest(this.placer)[0];
        }

        @Override
        public Direction getNearestLookingVerticalDirection() {
            return Direction.getFacingAxis(this.placer, Direction.Axis.Y);
        }

        @Override
        public Direction[] getNearestLookingDirections() {
            Direction[] dirs = Direction.orderedByNearest(this.placer);
            if (this.replaceClicked) {
                return dirs;
            }
            Direction face = this.getClickedFace().getOpposite();
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
