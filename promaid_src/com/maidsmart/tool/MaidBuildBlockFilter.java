package com.maidsmart.tool;

import com.maidsmart.config.MaidSmartConfig;
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
            // 反馈往表里加模组危险方块即自动从垫脚选材中消失
            if (DangerBlocks.isDanger(block)) {
                return false;
            }
            // v1.3.0(beta) 实测六百八十：玩家自己那张「搭方块禁用名单」（面板「移动与行为 →
            // 搭路 → 搭方块禁用名单」）——本方法就是四个消费方（自保搭高/挖矿/伐木/搭路）
            // 的公共入口，所以这一条天然覆盖玩家要求的全部四个链路（口径只有一处）。
            if (isBlacklistedBuildBlock(sid)) {
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

    /* ==================== v1.3.0(beta) 实测六百八十：玩家那张「搭方块禁用名单」 ==================== */

    /**
     * 该方块是否被【玩家自己那张搭方块禁用名单】拦住——本模组对"哪种方块能垫脚"的第四条判定
     * （前三条：安全黑名单 / dangerBlocks 危险表 / 碰撞形状），也是玩家能直接改的那一条。
     *
     * <p>判定顺序（**优先级从高到低**，与配置面板上显示的状态完全同源——面板画的就是这个方法）：
     * <ol>
     *   <li><b>放宽名单</b>（{@code bridge.buildWhitelist}）里有 → <b>允许</b>：面板上被取消勾选
     *       （绿框✔）的那些，含玩家想用的<b>模组方块</b>；</li>
     *   <li><b>禁用名单</b>（{@code bridge.buildBlacklist}）里有 → <b>禁止</b>：面板上点成红框✖ 的；</li>
     *   <li><b>「只用原版天然方块」</b>开着（默认）→ 不在 {@link NaturalBlocks} 表里就 <b>禁止</b>
     *       ——于是<b>全部模组方块默认都被这一条拦住</b>（玩家要的默认）；</li>
     *   <li>以上都不适用 → 放行，交给前面那三条判定（沙子/沙砾这类下落方块照样过不去，
     *       所以「现有的那些在黑名单里的仍然是不允许的，比如沙子」这句原话是自洽的）。</li>
     * </ol>
     *
     * <p>匹配用完整注册名（也认裸 path：写 {@code sand} 等于 {@code minecraft:sand}），
     * 与 {@code dangerBlocks} / 投喂黑名单同一套键口径。任何异常一律返回 false（放行）——
     * 最坏情况是"这张玩家名单没生效、退回旧行为"，绝不会因为一次异常把搭路整条掐死。
     */
    public static boolean isBlacklistedBuildBlock(Block block) {
        if (block == null) {
            return false;
        }
        try {
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
            return id != null && isBlacklistedBuildBlock(id.toString());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 同一个口径的字符串版（{@link #isUsableBuildBlock} 内部已经算好注册名，不必再查一次注册表）。 */
    public static boolean isBlacklistedBuildBlock(String blockId) {
        try {
            String full = NaturalBlocks.normalize(blockId);
            if (full.isEmpty()) {
                return false;
            }
            if (containsId(MaidSmartConfig.BRIDGE_BUILD_ALLOWED.get(), full)) {
                return false; // ① 玩家放开的例外（含模组方块）
            }
            if (containsId(MaidSmartConfig.BRIDGE_BUILD_FORBIDDEN.get(), full)) {
                return true; // ② 玩家显式禁用
            }
            if (MaidSmartConfig.BRIDGE_BUILD_ONLY_NATURAL.get()) {
                return !NaturalBlocks.contains(full); // ③ 默认：非原版天然方块一律不许搭
            }
            return false;
        } catch (Throwable t) {
            return false; // 配置未加载等异常 → 放行（退回旧行为，不掐死链路）
        }
    }

    /** 名单里有没有这个注册名（两侧都规范化，大小写不敏感）。 */
    private static boolean containsId(java.util.List<? extends String> list, String fullId) {
        if (list == null || list.isEmpty()) {
            return false;
        }
        for (String raw : list) {
            if (NaturalBlocks.normalize(raw).equals(fullId)) {
                return true;
            }
        }
        return false;
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
            if (stack.m_41619_() || !(stack.m_41720_() instanceof BlockItem bi)) {
                continue;
            }
            if (!isUsableBuildBlock(bi.m_40614_(), level, pos)) {
                continue;
            }
            counts.merge(stack.m_41720_(), stack.m_41613_(), Integer::sum);
        }
        if (hands != null) {
            for (int i = 0; i < Math.min(2, hands.getSlots()); i++) {
                if (skipBorrowedOffhand && i == OFFHAND_HAND_SLOT) {
                    continue; // 副手正被动作表现借去展示——那件不是材料
                }
                ItemStack stack = hands.getStackInSlot(i);
                if (stack.m_41619_() || !(stack.m_41720_() instanceof BlockItem bi)) {
                    continue;
                }
                if (!isUsableBuildBlock(bi.m_40614_(), level, pos)) {
                    continue;
                }
                counts.merge(stack.m_41720_(), stack.m_41613_(), Integer::sum);
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
                if (!stack.m_41619_() && stack.m_41720_() == best) {
                    ItemStack taken = hands.extractItem(i, 1, false);
                    if (!taken.m_41619_()) {
                        return best;
                    }
                }
            }
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
            if (s == null || s.m_41619_() || !(s.m_41720_() instanceof BlockItem)) {
                continue;
            }
            ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(s.m_41720_());
            m.merge(id == null ? "?" : id.toString(), s.m_41613_(), Integer::sum);
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
        if (s == null || s.m_41619_()) {
            return "-";
        }
        ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(s.m_41720_());
        return (id == null ? "?" : id.toString()) + "x" + s.m_41613_();
    }

    /** 探针：单个物品（取料返回值）的 id；null（没取到）→ null。 */
    public static String probeItem(Item item) {
        if (item == null) {
            return null;
        }
        ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item);
        return id == null ? "?" : id.toString();
    }

    /** 探针：方块注册名。 */
    public static String probeBlock(Block block) {
        if (block == null) {
            return "-";
        }
        ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block);
        return id == null ? "?" : id.toString();
    }

}
