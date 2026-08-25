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
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

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
 * 5. 【有完整碰撞形状】：m_60796_（isSolidRender…实为 canOcclude/isCollisionShapeFullBlock
 *    口径与自保旧判定一致）+ 碰撞箱非空（m_5939_ getCollisionShape → m_83281_ isEmpty
 *    双保险——火把/花/雪片等 collisionShape 为空直接拦）。
 * 6. 非可替换方块（火把/草/雪片同时也在 REPLACEABLE tag 里——双保险第二层）。
 *
 * 注意：不依赖具体坐标做碰撞查询（m_5939_ 需要 BlockGetter，这里用空 Level 的
 * 世界坐标风险大）——退而求其次用方块默认状态的碰撞形状（m_7952_ getShape 无
 * Level 参与的默认形状；火把等默认形状也为空，判定成立）。为稳妥起见两个口径
 * 都检查：默认 shape 为空 或 REPLACEABLE tag 命中 → 一律拒绝。
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
     * 该物品栈是否是【可用于垫脚的实心方块】。
     * 全模块统一入口：自保搭高/挖矿/伐木/搭路的取材都必须走这里。
     */
    public static boolean isUsableBuildStack(ItemStack stack, Level level, BlockPos pos) {
        if (stack == null || stack.m_41619_() || !(stack.m_41720_() instanceof BlockItem bi)) {
            return false;
        }
        return isUsableBuildBlock(bi.m_40614_(), level, pos);
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
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
            String sid = id == null ? "" : id.toString();
            if (HARM_BLOCK_IDS.contains(sid)) {
                return false;
            }
            // v1.1.0 实测九十一：配置危险表（dangerBlocks）同样永不入搭块名单——
            // 与寻路避让/险境脱离共享同一张表，且不受避让开关影响（无论如何都不会搭）；
            // 粉丝往表里加模组危险方块即自动从垫脚选材中消失
            if (DangerBlocks.isDanger(block)) {
                return false;
            }
            var state = block.m_49966_();
            // 可替换方块（火把/草/雪片/水——玩家右键能直接顶掉的那些）绝不能垫脚
            //（f_278394_ = BlockTags.REPLACEABLE，字节码实证）
            if (state.m_60795_() || state.m_204336_(net.minecraft.tags.BlockTags.f_278394_)) {
                return false;
            }
            // 碰撞形状为空（火把/花/蘑菇/地毯等）——最直接的"无碰撞体积"判定。
            // m_60742_ = BlockStateBase.getCollisionShape(BlockGetter, BlockPos, CollisionContext)
            //（SRG 实证：BlockState 上没有 m_5939_，碰撞形状查询在 BlockStateBase）
            if (level != null && pos != null) {
                net.minecraft.world.phys.shapes.VoxelShape collision =
                        state.m_60742_(level, pos, net.minecraft.world.phys.shapes.CollisionContext.m_82749_());
                if (collision.m_83281_()) { // isEmpty（SRG 实证）
                    return false;
                }
                // 旧口径保留：canOcclude（完整实心渲染口径——台阶/栅栏/门等不完整形状在此拦）
                if (!state.m_60796_(level, pos)) {
                    return false;
                }
            } else {
                // 静态口径（无坐标）：默认形状为空 → 无碰撞。
                // 不用 EmptyBlockGetter（该类在 Forge client jar 里未打包）——
                // getShape 对 BlockGetter 只查方块实体/邻居，静态判定传 null 安全
                //（火把等的 getCollisionShape 是常量 Shapes.empty，不读 BlockGetter）
                net.minecraft.world.phys.shapes.VoxelShape shape =
                        state.m_60742_(null, net.minecraft.core.BlockPos.f_121853_,
                                net.minecraft.world.phys.shapes.CollisionContext.m_82749_());
                if (shape.m_83281_()) {
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
     */
    public static Item takeBuildBlock(IItemHandler inv, Level level, BlockPos pos) {
        java.util.Map<Item, Integer> counts = new java.util.HashMap<>();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.m_41619_() || !(stack.m_41720_() instanceof BlockItem bi)) {
                continue;
            }
            if (!isUsableBuildBlock(bi.m_40614_(), level, pos)) {
                continue;
            }
            counts.merge(stack.m_41720_(), stack.m_41613_(), Integer::sum);
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
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.m_41619_() && stack.m_41720_() == best) {
                ItemStack taken = inv.extractItem(i, 1, false);
                if (!taken.m_41619_()) {
                    return best;
                }
            }
        }
        return null;
    }
}
