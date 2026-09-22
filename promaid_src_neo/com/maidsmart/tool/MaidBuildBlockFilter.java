package com.maidsmart.tool;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.TntBlock;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * v1.1.0 实测七【搭方块安全过滤·全局统一】：女仆一切"垫脚/搭高/搭桥"的选材
 * 都必须过这一关——火把/花/草/地毯/雪片等【无碰撞体积】方块放下去踩不住、
 * 挡不住，旧版四个模块（自保搭高/挖矿/伐木/搭路）各自的 takeBuildBlock 只拦
 * "下落方块"，火把进了"数量最多"就选它 → 垫了个寂寞还白消耗。
 *
 * 判定（全部通过才可用）：
 * 1. BlockItem（必须是方块物品）；
 * 2. 非下落方块（沙/砾石/铁砧——放下就掉）；
 * 3. 非 TNT；
 * 4. 不在伤害黑名单（仙人掌/岩浆块/甜浆果/营火/灵魂营火——踩上掉血）；
 * 5. 【有完整碰撞形状】：isRedstoneConductor（isSolidRender…实为 canOcclude/isCollisionShapeFullBlock
 *    口径与自保旧判定一致）+ 碰撞箱非空（getCollisionShape getCollisionShape → isEmpty isEmpty
 *    双保险——火把/花/雪片等 collisionShape 为空直接拦）。
 * 6. 非可替换方块（火把/草/雪片同时也在 REPLACEABLE tag 里——双保险第二层）。
 *
 * 注意：不依赖具体坐标做碰撞查询（getCollisionShape 需要 BlockGetter，这里用空 Level 的
 * 世界坐标风险大）——退而求其次用方块默认状态的碰撞形状（getOcclusionShape getShape 无
 * Level 参与的默认形状；火把等默认形状也为空，判定成立）。为稳妥起见两个口径
 * 都检查：默认 shape 为空 或 REPLACEABLE tag 命中 → 一律拒绝。
 *
 * v1.2.4【issue #19·搭路刷物品】：取材这一侧再加一条口径——**取值区间不许包含"被动作表现
 * 借走的副手"**（见 {@link #takeBuildBlock(IItemHandler, IItemHandler, Level, BlockPos, boolean)}）。
 * 判定器只管"哪种方块能垫"，"哪一格算材料"由调用方按此口径传参。
 */
public final class MaidBuildBlockFilter {

    private MaidBuildBlockFilter() {
    }

    /** 伤害/危险方块黑名单（注册名——与自保 DANGER_BLOCKS 同源） */
    private static final java.util.Set<String> HARM_BLOCK_IDS = java.util.Set.of(
            "minecraft:cactus", "minecraft:magma_block",
            "minecraft:sweet_berry_bush", "minecraft:campfire", "minecraft:soul_campfire",
            "minecraft:fire", "minecraft:soul_fire", "minecraft:powder_snow"
    );

    /**
     * 手部栏里【副手】的槽位号（v1.2.4：取材跳过被借走的副手用）。
     *
     * 口径证据（EntityHandsInvWrapper = LivingEntity 的双手，0=主手、1=副手）：
     * {@code MaidTorchPlacerBehavior} 写的是 {@code isTorchItem(主手) ? 0 : 1}；
     * {@code MaidPlanting.restoreNow} 把"副手暂存的原物品"放回主手时读的正是槽 1；
     * 本工程探针 {@code probeHands} 的注释也是"slot0=主手 / slot1=副手"。
     */
    public static final int OFFHAND_HAND_SLOT = 1;

    /**
     * 该物品栈是否是【可用于垫脚的实心方块】。
     * 全模块统一入口：自保搭高/挖矿/伐木/搭路的取材都必须走这里。
     */
    public static boolean isUsableBuildStack(ItemStack stack, Level level, BlockPos pos) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi)) {
            return false;
        }
        return isUsableBuildBlock(bi.getBlock(), level, pos);
    }

    /**
     * 该方块是否可用于垫脚（碰撞体积判定 + 黑名单）。
     * level/pos 用于碰撞形状查询（canOcclude 旧口径需要）；null 时跳过坐标相关
     * 检查只做静态判定（默认碰撞形状/黑名单/tag）。
     */
    public static boolean isUsableBuildBlock(Block block, Level level, BlockPos pos) {
        if (block == null) {
            return false;
        }
        try {
            // 下落方块（沙/砾石/铁砧）+ TNT
            if (block instanceof FallingBlock || block instanceof TntBlock) {
                return false;
            }
            ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
            String sid = id == null ? "" : id.toString();
            if (HARM_BLOCK_IDS.contains(sid)) {
                return false;
            }
            // v1.1.0 实测九十一：配置危险表（dangerBlocks）同样永不入搭块名单——
            // 与寻路避让/险境脱离共享同一张表，且不受避让开关影响（无论如何都不会搭）；
            // 反馈往表里加模组危险方块即自动从垫脚选材中消失
            if (DangerBlocks.isDanger(block)) {
                return false;
            }
            var state = block.defaultBlockState();
            // 可替换方块（火把/草/雪片/水——玩家右键能直接顶掉的那些）绝不能垫脚
            //（REPLACEABLE = BlockTags.REPLACEABLE，字节码实证）
            if (state.isAir() || state.is(net.minecraft.tags.BlockTags.REPLACEABLE)) {
                return false;
            }
            // 碰撞形状为空（火把/花/蘑菇/地毯等）——最直接的"无碰撞体积"判定。
            // getCollisionShape = BlockStateBase.getCollisionShape(BlockGetter, BlockPos, CollisionContext)
            //（SRG 实证：BlockState 上没有 getCollisionShape，碰撞形状查询在 BlockStateBase）
            if (level != null && pos != null) {
                net.minecraft.world.phys.shapes.VoxelShape collision =
                        state.getCollisionShape(level, pos, net.minecraft.world.phys.shapes.CollisionContext.empty());
                if (collision.isEmpty()) { // isEmpty（SRG 实证）
                    return false;
                }
                // 旧口径保留：canOcclude（完整实心渲染口径——台阶/栅栏/门等不完整形状在此拦）
                if (!state.isRedstoneConductor(level, pos)) {
                    return false;
                }
            } else {
                // 静态口径（无坐标）：默认形状为空 → 无碰撞。
                // 不用 EmptyBlockGetter（该类在 Forge client jar 里未打包）——
                // getShape 对 BlockGetter 只查方块实体/邻居，静态判定传 null 安全
                //（火把等的 getCollisionShape 是常量 Shapes.empty，不读 BlockGetter）
                net.minecraft.world.phys.shapes.VoxelShape shape =
                        state.getCollisionShape(null, net.minecraft.core.BlockPos.ZERO,
                                net.minecraft.world.phys.shapes.CollisionContext.empty());
                if (shape.isEmpty()) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false; // 查询异常（怪异模组方块）——保守拒绝
        }
    }

    /**
     * 背包里是否有可用垫脚方块（无副作用——canUse 探测用）。
     */
    public static boolean hasBuildBlock(EntityMaidInv inv, Level level, BlockPos pos) {
        for (int i = 0; i < inv.slots(); i++) {
            if (isUsableBuildStack(inv.stackAt(i), level, pos)) {
                return true;
            }
        }
        return false;
    }

    /** IItemHandler 的薄视图（避免直接依赖 EntityMaid——自保/挖矿/伐木/搭路通用） */
    public interface EntityMaidInv {
        int slots();

        ItemStack stackAt(int slot);
    }

    /** IItemHandler → 视图适配 */
    public static EntityMaidInv view(IItemHandler handler) {
        return new EntityMaidInv() {
            @Override
            public int slots() {
                return handler.getSlots();
            }

            @Override
            public ItemStack stackAt(int slot) {
                return handler.getStackInSlot(slot);
            }
        };
    }

    /**
     * 背包里数量最多的可用垫脚方块（各模块 takeBuildBlock 的统一实现）。
     * 返回该物品（已从背包扣 1 个）；没有可用方块返回 null。
     * v1.1.0 实测二百三十一（反馈"审计一下主副手识别"）：加 hands 版重载——
     * 计数含手部栏（主/副手），扣取时先扣手（她手里正拿的就是想用的），无手再扣背包。
     */
    public static Item takeBuildBlock(IItemHandler inv, Level level, BlockPos pos) {
        return takeBuildBlock(inv, null, level, pos);
    }

    public static Item takeBuildBlock(IItemHandler inv, IItemHandler hands, Level level, BlockPos pos) {
        return takeBuildBlock(inv, hands, level, pos, false);
    }

    /**
     * v1.2.4【搭路刷物品·issue #19】{@code skipBorrowedOffhand = true} = **这一下不许把副手
     * 当材料**（计数与扣取都跳过副手槽）。
     *
     * 为什么必须有这个口径：动作表现（{@link com.maidsmart.combat.BombPose}）把"她这一下正在
     * 用的那件"以**复制品**写进**真实副手槽**举 10 tick。搭路节奏默认 4 tick（BRIDGE_STEP_COOLDOWN）、
     * 展示 10 tick —— 于是下一次取材时那件复制品还在副手上，而本方法旧口径"计数含双手、先扣手"
     * 正好把它当材料扣走；扣完 `showGated` 又补一件新的。净效果：**真料一次都不掉**，但方块到期
     * 回收按方块 id 逐格还一件真物品（{@link com.maidsmart.task.PlacedBlockTracker#reclaimDrops}）
     * ——实测「给 1 个铁块、走了 128 格桥」，回收 128 件，凭空多 127 件。
     *
     * 调用侧一律传 {@code BombPose.offhandBorrowed(maid)}：**借走期间副手不算材料**；没被借走时
     * 照旧（玩家把方块挂她副手是正常用法）。主手不跳——那格是她自己真握着的。
     */
    public static Item takeBuildBlock(IItemHandler inv, IItemHandler hands, Level level, BlockPos pos,
                                      boolean skipBorrowedOffhand) {
        java.util.Map<Item, Integer> counts = new java.util.HashMap<>();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi)) {
                continue;
            }
            if (!isUsableBuildBlock(bi.getBlock(), level, pos)) {
                continue;
            }
            counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        if (hands != null) {
            for (int i = 0; i < Math.min(2, hands.getSlots()); i++) {
                if (skipBorrowedOffhand && i == OFFHAND_HAND_SLOT) {
                    continue; // 副手正被动作表现借去展示——那件不是材料
                }
                ItemStack stack = hands.getStackInSlot(i);
                if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi)) {
                    continue;
                }
                if (!isUsableBuildBlock(bi.getBlock(), level, pos)) {
                    continue;
                }
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        Item best = null;
        int bestCount = 0;
        for (java.util.Map.Entry<Item, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        if (best == null) {
            return null;
        }
        // 优先扣手部栏（主→副），再扣背包（与计数口径一致，见类注释）
        if (hands != null) {
            for (int i = 0; i < Math.min(2, hands.getSlots()); i++) {
                if (skipBorrowedOffhand && i == OFFHAND_HAND_SLOT) {
                    continue; // 同上：被借走的副手既不算材料、也不许从这里扣
                }
                ItemStack stack = hands.getStackInSlot(i);
                if (!stack.isEmpty() && stack.getItem() == best) {
                    ItemStack taken = hands.extractItem(i, 1, false);
                    if (!taken.isEmpty()) {
                        return best;
                    }
                }
            }
        }
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty() && stack.getItem() == best) {
                ItemStack taken = inv.extractItem(i, 1, false);
                if (!taken.isEmpty()) {
                    return best;
                }
            }
        }
        return null;
    }

    /* ==================== v1.2.3-dbg 探针取值助手（查完删掉整段） ==================== */

    /** 探针：inv + hands 里所有【方块物品】的 "id x 总数" 统计串（按 id 排序，可直接比字符串）。 */
    public static String probeCounts(IItemHandler inv, IItemHandler hands) {
        java.util.TreeMap<String, Integer> m = new java.util.TreeMap<>();
        probeCollect(m, inv);
        probeCollect(m, hands);
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : m.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('x').append(e.getValue());
        }
        return sb.toString();
    }

    private static void probeCollect(java.util.Map<String, Integer> m, IItemHandler h) {
        if (h == null) {
            return;
        }
        for (int i = 0; i < h.getSlots(); i++) {
            ItemStack s = h.getStackInSlot(i);
            if (s == null || s.isEmpty() || !(s.getItem() instanceof BlockItem)) {
                continue;
            }
            ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem());
            m.merge(id == null ? "?" : id.toString(), s.getCount(), Integer::sum);
        }
    }

    /** 探针：手部栏逐槽内容（slot0=主手 / slot1=副手）——用来看副手上那件"展示品"。 */
    public static String probeHands(IItemHandler hands) {
        if (hands == null) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(2, hands.getSlots()); i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(i).append(':').append(probeStack(hands.getStackInSlot(i)));
        }
        return sb.toString();
    }

    /** 探针：单个物品栈的 "id x 数量"（空栈 → "-"）。 */
    public static String probeStack(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return "-";
        }
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem());
        return (id == null ? "?" : id.toString()) + "x" + s.getCount();
    }

    /** 探针：单个物品（取料返回值）的 id；null（没取到）→ null。 */
    public static String probeItem(Item item) {
        if (item == null) {
            return null;
        }
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        return id == null ? "?" : id.toString();
    }

    /** 探针：方块注册名。 */
    public static String probeBlock(Block block) {
        if (block == null) {
            return "-";
        }
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
        return id == null ? "?" : id.toString();
    }

}
