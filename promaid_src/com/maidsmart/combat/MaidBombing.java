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
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * v1.2.2 实测五百八十七：**空袭轰炸**——近战空袭打完那一记之后、再次起飞之前插几步"放炸弹"。
 *
 * ── 需求原文 ──
 * "当包内同时存在黑耀石/基岩，末地水晶时，在空袭近战攻击打出后再次起飞之前加几步：放置一块
 * 黑耀石→在黑耀石上放置末地水晶。这个末地水晶固定 0.5 秒之后爆炸。如果判定下来方块和末地
 * 水晶放置失败了不会再进行判定，会直接跳到下一个链路。这个末地水晶默认不会伤到主人和友方
 * 单位。"、"对于重生锚和床也有类似的机制，走放置→0.5 秒后爆炸（重生锚这个机制在下界不生效，
 * 床这个机制在主世界不生效）"、"远程空袭加一个投掷 TNT 的机制……所需物品是同时拥有 TNT 和
 * 打火石，但是不刚需，属于附加链接，判定没有就会直接跳过。女仆会在天上盘旋期间额外发射
 * tnt"、"上述提到的爆炸效果，默认不对玩家造成伤害以及击飞，同时不破坏方块。但都可以在手册
 * 内部调试"、"女仆放置的这些物品默认会加一层很淡的粉色渲染"。
 *
 * ── 开工前先查的那件事：重生锚的爆炸威力跟萤石充能等级有没有关系？ ──
 * 【结论：没有关系】1.20.1 与 1.21.1 的 {@code RespawnAnchorBlock} 反编译实证：那一下是固定
 * {@code level.explode(null, …, 5.0F, true, ExplosionInteraction.BLOCK)}——**5.0 是写死的
 * 字面量**（两树同一份字节码常量）。CHARGE 属性只出现在三处：① 等于 0 时右键直接 PASS（不炸）；
 * ② 亮度/红石比较器输出（{@code getScaledChargeLevel}）；③ 水面爆炸时的伤害计算器。
 * 也就是说充能等级**只决定"炸不炸"、不决定"炸多狠"**——所以本功能只需要 **1 颗萤石**把
 * CHARGE 从 0 顶到 1（那一步照原版 {@code RespawnAnchorBlock.charge} 走：有音效、我们额外补挥臂），
 * **不需要**做"充到 4 级"那一套。
 * 同一批反编译顺带记下的数字：末地水晶 6.0F / fire=false；床 5.0F / fire=true；TNT 4.0F / fire=false。
 * 维度判据也是原版口径：床看 {@code !dimensionType.bedWorks()}（主世界之外才炸）、重生锚看
 * {@code !dimensionType.respawnAnchorWorks()}（下界之外才炸）——正好等于需求里的
 * "重生锚在下界不生效、床在主世界不生效"。
 *
 * ── 三段链路（材料齐才做；齐了但放不下就整段放弃）──
 * ① 水晶：黑曜石/基岩（有黑曜石优先）+ 末地水晶 → 目标脚边那一格放方块、上面挂水晶；
 * ② 重生锚：重生锚 + 萤石（下界不生效）→ 放下 → 萤石充 1 级；
 * ③ 床：任意床（主世界不生效）→ 放下（原版两格床，朝向按她的朝向）。
 * 三段按 ①②③ 取第一个材料齐的；**放置失败不再试下一段**，直接交还原链路（再次起飞）——
 * 需求原话"不会再进行判定，会直接跳到下一个链路"。
 *
 * ── 时序（为什么是"起飞之前"）──
 * 猛击命中那一 tick 起手：step 0 放方块、step 1 放水晶/充能，随后立刻把控制权交还
 * {@code MaidFlightCombatBehavior} 的 WAIT_LAUNCH 分支 → 她照常放烟花起飞；爆炸由
 * {@link #onServerTick} 在 0.5 秒后单独触发——**爆炸与起飞重叠**，她自己的那一发顺带当助推。
 *
 * ── 爆炸口径（默认全保护，全部可在配置面板调）──
 * - 伤害源**归因给女仆**（{@code damageSources().explosion(maid, maid)}）：于是
 *   {@link FriendlyFireGuard} 照旧取消"女仆→主人/同主女仆/友军"的伤害，主人与友军**既不掉血
 *   也不被震**（击退那条走既有的 {@link FriendlyWindGuard}，与重锤风爆同一套）；她自己也被
 *   {@code isFriendly(maid, maid)} 覆盖 → 不会被自己的炸弹炸伤；而"风"这一层更严：
 *   实测五百八十八起，**这套炸弹的风对放炸弹的她本人也不生效**（与重锤风爆不同，
 *   窗口见 {@link #isSelfImmuneBlast()}）。
 * - 想改成"原版爆炸"（主人照掉血照被炸飞）：打开配置里的"伤到主人/友军"——那时**不归因**
 *   （伤害源与爆炸来源都留空），并用 {@link #inVanillaBlast()} 让风免在这一瞬间让位，
 *   否则会出现"血掉了、人没飞"这种半吊子状态。
 * - 破坏方块默认关：{@code ExplosionInteraction.NONE}——原版这个档位**只作用于方块**，
 *   实体伤害与击退照常，所以"不破坏方块"与"照样炸伤怪"可以同时成立。
 * - 女仆自己放的那几块（黑曜石/重生锚/床）**无论开关如何都由我们自己撤掉**，且用
 *   {@code destroyBlock/removeBlock(…, false)} 之类不掉落的写法——否则"不破坏方块"模式下
 *   会在世界里留下永久黑曜石，等于白送材料。
 */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = "promaid")
public final class MaidBombing {

    private MaidBombing() {
    }

    /* ==================== 配置 ==================== */

    private static boolean cfgMelee() {
        return MaidSmartConfig.COMBAT_BOMBING_MELEE.get();
    }

    private static boolean cfgTnt() {
        return MaidSmartConfig.COMBAT_BOMBING_TNT.get();
    }

    private static int cfgFuse() {
        return MaidSmartConfig.COMBAT_BOMBING_FUSE.get();
    }

    private static int cfgTntFuse() {
        return MaidSmartConfig.COMBAT_BOMBING_TNT_FUSE.get();
    }

    private static int cfgTntInterval() {
        return MaidSmartConfig.COMBAT_BOMBING_TNT_INTERVAL.get();
    }

    private static double cfgTntSpeed() {
        return MaidSmartConfig.COMBAT_BOMBING_TNT_SPEED.get();
    }

    private static boolean cfgBreakBlocks() {
        return MaidSmartConfig.COMBAT_BOMBING_BREAK_BLOCKS.get();
    }

    private static boolean cfgHurtFriendly() {
        return MaidSmartConfig.COMBAT_BOMBING_HURT_FRIENDLY.get();
    }

    private static boolean cfgPinkMark() {
        return MaidSmartConfig.COMBAT_BOMBING_PINK_MARK.get();
    }

    private static double cfgTntRange() {
        return MaidSmartConfig.COMBAT_BOMBING_TNT_RANGE.get();
    }

    private static double cfgTntBurstRatio() {
        return MaidSmartConfig.COMBAT_BOMBING_TNT_BURST_RATIO.get();
    }

    private static int cfgTntBurstCount() {
        return MaidSmartConfig.COMBAT_BOMBING_TNT_BURST_COUNT.get();
    }

    /* ==================== 物品（按注册名找，不写 SRG 字段名） ==================== */

    private static final String ID_OBSIDIAN = "minecraft:obsidian";
    private static final String ID_BEDROCK = "minecraft:bedrock";
    private static final String ID_END_CRYSTAL = "minecraft:end_crystal";
    private static final String ID_RESPAWN_ANCHOR = "minecraft:respawn_anchor";
    private static final String ID_GLOWSTONE = "minecraft:glowstone";
    private static final String ID_TNT = "minecraft:tnt";
    private static final String ID_FLINT_AND_STEEL = "minecraft:flint_and_steel";
    private static final String ID_TNT_PRIMED_SOUND = "minecraft:entity.tnt.primed";

    private static final Map<String, Item> ITEM_CACHE = new HashMap<>();

    private static Item item(String id) {
        Item cached = ITEM_CACHE.get(id);
        if (cached != null) {
            return cached;
        }
        try {
            Item it = ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation(id));
            if (it != null) {
                ITEM_CACHE.put(id, it);
            }
            return it;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isStack(ItemStack stack, String id) {
        if (stack == null || stack.m_41619_()) {
            return false;
        }
        Item want = item(id);
        return want != null && stack.m_150930_(want);
    }

    private static boolean matchAny(ItemStack stack, String... ids) {
        for (String id : ids) {
            if (isStack(stack, id)) {
                return true;
            }
        }
        return false;
    }

    /** 是不是任意一张床（原版 16 色都算，不写死颜色） */
    private static boolean isBed(ItemStack stack) {
        try {
            if (stack == null || stack.m_41619_()) {
                return false;
            }
            if (!(stack.m_41720_() instanceof BlockItem bi)) {
                return false;
            }
            return bi.m_40614_() instanceof BedBlock;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 手上（主手/副手）+ 女仆背包里有没有这个东西 */
    private static boolean has(EntityMaid maid, String... ids) {
        if (maid == null) {
            return false;
        }
        try {
            IItemHandler hands = (IItemHandler) maid.getHandsInvWrapper();
            for (int i = 0; i < hands.getSlots(); i++) {
                if (matchAny(hands.getStackInSlot(i), ids)) {
                    return true;
                }
            }
            IItemHandler inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                if (matchAny(inv.getStackInSlot(i), ids)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean hasBed(EntityMaid maid) {
        return maid != null && !takeFirst(maid, null, true).m_41619_();
    }

    /** 取 1 个（手 → 背包）；取不到返回空。dryRun=true 时只探测不抽取（内部用） */
    private static ItemStack takeOne(EntityMaid maid, String... ids) {
        return takeFirst(maid, ids, false);
    }

    private static ItemStack takeOneBed(EntityMaid maid) {
        return takeFirst(maid, null, false);
    }

    /**
     * 唯一的取料实现：ids == null 表示"任意床"，否则按注册名匹配。
     * dryRun=true 只回答"有没有"（hasBed 用），一个物品都不动。
     */
    private static ItemStack takeFirst(EntityMaid maid, String[] ids, boolean dryRun) {
        if (maid == null) {
            return ItemStack.f_41583_;
        }
        try {
            IItemHandler hands = (IItemHandler) maid.getHandsInvWrapper();
            for (int i = 0; i < hands.getSlots(); i++) {
                ItemStack s = hands.getStackInSlot(i);
                if (ids == null ? isBed(s) : matchAny(s, ids)) {
                    return dryRun ? s : hands.extractItem(i, 1, false);
                }
            }
            IItemHandler inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (ids == null ? isBed(s) : matchAny(s, ids)) {
                    return dryRun ? s : inv.extractItem(i, 1, false);
                }
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.f_41583_;
    }

    /** 用不掉就还回去（背包满则丢在脚下） */
    private static void giveBack(EntityMaid maid, ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return;
        }
        try {
            IItemHandler inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                if (inv.getStackInSlot(i).m_41619_()) {
                    inv.insertItem(i, stack, false);
                    return;
                }
            }
            maid.m_5552_(stack, 0.5f);
        } catch (Throwable ignored) {
        }
    }

    private static SoundEvent sound(String id) {
        try {
            return ForgeRegistries.SOUND_EVENTS.getValue(new net.minecraft.resources.ResourceLocation(id));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /* ==================== 状态 ==================== */

    /** 四类炸弹的爆炸参数（同一批原版反编译数字） */
    private enum Kind {
        CRYSTAL("末地水晶", 6.0F, false),
        ANCHOR("重生锚", 5.0F, true),
        BED("床", 5.0F, true),
        TNT("TNT", 4.0F, false);

        final String cn;
        final float power;
        final boolean fire;

        Kind(String cn, float power, boolean fire) {
            this.cn = cn;
            this.power = power;
            this.fire = fire;
        }
    }

    /** 近战轰炸的分步相位（step 0 放方块 / step 1 放水晶或充能 → 交还链路） */
    private static final class Phase {
        final Kind kind;
        final long start;
        int step;
        BlockPos spot;
        final List<BlockPos> placed = new ArrayList<>(2);
        final List<Block> placedBlock = new ArrayList<>(2);

        Phase(Kind kind, long start) {
            this.kind = kind;
            this.start = start;
        }
    }

    /** 待起爆的一发 */
    private static final class Bomb {
        final ServerLevel level;
        final EntityMaid maid;
        final Entity entity;
        final BlockPos pos;
        final List<BlockPos> placedPos;
        final List<Block> placedBlock;
        final Kind kind;
        final long due;

        Bomb(ServerLevel level, EntityMaid maid, Entity entity, BlockPos pos,
             List<BlockPos> placedPos, List<Block> placedBlock, Kind kind, long due) {
            this.level = level;
            this.maid = maid;
            this.entity = entity;
            this.pos = pos;
            this.placedPos = placedPos;
            this.placedBlock = placedBlock;
            this.kind = kind;
            this.due = due;
        }
    }

    private static final Map<UUID, Phase> PHASE = new HashMap<>();
    private static final Map<UUID, Long> TNT_NEXT = new HashMap<>();
    private static final List<Bomb> PENDING = new ArrayList<>();
    /** 相位最长存活（tick）：超了当陈旧状态丢掉（防"打到一半目标没了"留下的尾巴） */
    private static final long PHASE_TIMEOUT = 100L;
    private static final int MAX_PENDING = 256;
    /** 待起爆列表里一条最长挂多久（tick）：跨过这个数还没炸（区块没加载等）就丢掉，防泄漏 */
    private static final long BOMB_TIMEOUT = 1200L;

    /** "原版模式"窗口：这一瞬间的爆炸不归因女仆，风免让位（见类注释的爆炸口径） */
    private static volatile boolean vanillaBlast = false;

    /** 供 {@link FriendlyWindGuard} 查询：此刻女仆的炸弹是不是"原版模式"（玩家照震） */
    public static boolean inVanillaBlast() {
        return vanillaBlast;
    }

    /**
     * v1.2.2 实测五百八十八【自爆风免窗口】：这一下爆炸的**风对"放它的女仆本人"也不生效**。
     *
     * 需求原文："此处爆炸机制与重锤不同，通过这种方式产生的风暴对释放的女仆自己也不生效。"
     * ——重锤的风爆会把包括她在内的所有人一起掀飞；我们这条链路做的炸弹不一样：
     * 她自己不会被打退（伤害本来就被友伤守卫拦掉了，这里连击退一起免）。
     */
    private static volatile boolean selfImmuneBlast = false;

    /** 供 {@link FriendlyWindGuard} 查询：此刻这一下爆炸的风，对放炸弹的女仆本人是否也不生效 */
    public static boolean isSelfImmuneBlast() {
        return selfImmuneBlast;
    }

    /** 窗口期内"放这一发炸弹的女仆"UUID（按人认，避免误免别人） */
    private static volatile java.util.UUID selfImmuneMaidId = null;

    /**
     * 这一下要施加在 {@code victim} 身上的风，是不是"放炸弹的她本人"该免掉的。
     * 只有"窗口开着 + 受害者的 UUID 正是这只女仆"才返回 true——这样窗口期内
     * 恰好最后一个 tick 的别的实体、或另一只女仆自己的法术，都不会被误免。
     */
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

    /** 本轮结束/女仆消失时清掉相位（待起爆的那几发照旧自己炸，不跟着清） */
    public static void forget(UUID maidId) {
        if (maidId == null) {
            return;
        }
        PHASE.remove(maidId);
        TNT_NEXT.remove(maidId);
    }

    public static void clearAll() {
        PHASE.clear();
        TNT_NEXT.clear();
        PENDING.clear();
        combatScanTick = 0;
    }

    private static void log(String msg) {
        com.maidsmart.tool.PromaidLog.log("空袭轰炸", msg);
    }

    /* ==================== 近战轰炸：起手 ==================== */

    /**
     * 猛击命中之后调用（在 endSmash 之前）。true = 本 tick 起手成功、由轰炸相位接管；
     * 材料不齐 / 已在本段里 → false（原链路照旧：再次起飞）。
     */
    public static boolean tryStartMelee(ServerLevel level, EntityMaid maid, LivingEntity target) {
        try {
            if (level == null || maid == null || target == null || !cfgMelee()) {
                return false;
            }
            UUID id = maid.m_20148_();
            Phase old = PHASE.get(id);
            if (old != null) {
                if (level.m_46467_() - old.start < PHASE_TIMEOUT) {
                    return true; // 已经在本段里（正常不会走到这里）
                }
                PHASE.remove(id);
            }
            Kind kind = pickKind(level, maid);
            if (kind == null) {
                return false; // 材料不齐：静默跳过（需求："判定没有就会直接跳过"）
            }
            PHASE.put(id, new Phase(kind, level.m_46467_()));
            return true;
        } catch (Throwable t) {
            log("起手异常：" + t);
            return false;
        }
    }

    /** 材料齐的第一段链路（顺序：水晶 → 重生锚 → 床；维度不合适的跳过那一段） */
    private static Kind pickKind(ServerLevel level, EntityMaid maid) {
        if (has(maid, ID_END_CRYSTAL) && has(maid, ID_OBSIDIAN, ID_BEDROCK)) {
            return Kind.CRYSTAL;
        }
        if (!anchorWorks(level) && has(maid, ID_RESPAWN_ANCHOR) && has(maid, ID_GLOWSTONE)) {
            return Kind.ANCHOR;
        }
        if (!bedWorks(level) && hasBed(maid)) {
            return Kind.BED;
        }
        return null;
    }

    /** 下界=false、其余=true（原版 respawn_anchor_works）：重生锚在下界不炸，机制不生效 */
    private static boolean anchorWorks(ServerLevel level) {
        try {
            return net.minecraft.world.level.block.RespawnAnchorBlock.m_55850_(level);
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 主世界=true、下界/末地=false（原版 bed_works）：床在主世界不炸，机制不生效 */
    private static boolean bedWorks(ServerLevel level) {
        try {
            return net.minecraft.world.level.block.BedBlock.m_49488_(level);
        } catch (Throwable ignored) {
            return true;
        }
    }

    /* ==================== 近战轰炸：分步执行 ==================== */

    /**
     * 轰炸相位每 tick 一次（放在空袭主 tick 最前面）。true = 本 tick 由轰炸接管。
     * step 0 放方块；step 1 放水晶 / 萤石充能 → 排好起爆时间并把控制权交还原链路。
     */
    public static boolean tick(ServerLevel level, EntityMaid maid, LivingEntity target, UUID id, long gameTime) {
        Phase ph = PHASE.get(id);
        if (ph == null) {
            return false;
        }
        try {
            if (target == null || !maid.m_6084_() || gameTime - ph.start > PHASE_TIMEOUT) {
                rollback(level, ph);
                PHASE.remove(id);
                return false;
            }
            if (ph.step == 0) {
                if (!stepBlock(level, maid, target, ph)) {
                    PHASE.remove(id);
                    return false; // 放不下：整段放弃，直接走下一个链路
                }
                ph.step = 1;
                return true;
            }
            if (!stepPayload(level, maid, ph, gameTime)) {
                rollback(level, ph);
                PHASE.remove(id);
                return false;
            }
            PHASE.remove(id);
            return false; // 交还链路：她接着放烟花起飞，0.5 秒后那边起爆
        } catch (Throwable t) {
            log("执行异常：" + t);
            rollback(level, ph);
            PHASE.remove(id);
            return false;
        }
    }

    /** step 0：把黑曜石/重生锚/床放到目标脚边那一格（走原版 BlockItem.place，两格床也由它铺） */
    private static boolean stepBlock(ServerLevel level, EntityMaid maid, LivingEntity target, Phase ph) {
        ItemStack stack;
        if (ph.kind == Kind.BED) {
            stack = takeOneBed(maid);
        } else if (ph.kind == Kind.CRYSTAL) {
            stack = takeOne(maid, has(maid, ID_OBSIDIAN) ? ID_OBSIDIAN : ID_BEDROCK);
        } else {
            stack = takeOne(maid, ID_RESPAWN_ANCHOR);
        }
        if (stack.m_41619_()) {
            return false;
        }
        BlockPos spot = placeOnSupport(level, maid, target, stack);
        if (spot == null) {
            giveBack(maid, stack);
            log(ph.kind.cn + " 放不下（附近没有可放置的落点）→ 本次放弃轰炸");
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
        log(com.maidsmart.tool.PromaidLog.nameOf(maid) + " 放下 " + ph.kind.cn
                + " @" + spot.m_123341_() + "," + spot.m_123342_() + "," + spot.m_123343_());
        return true;
    }

    /** step 1：水晶挂上去 / 重生锚用萤石充 1 级 → 排起爆 */
    private static boolean stepPayload(ServerLevel level, EntityMaid maid, Phase ph, long gameTime) {
        BlockPos base = ph.spot;
        if (base == null) {
            return false;
        }
        Entity spawned = null;
        if (ph.kind == Kind.CRYSTAL) {
            ItemStack crystal = takeOne(maid, ID_END_CRYSTAL);
            if (crystal.m_41619_()) {
                log("末地水晶取不到 → 放弃轰炸");
                return false;
            }
            EndCrystal ec = new EndCrystal(level, base.m_123341_() + 0.5, base.m_123342_() + 1.0, base.m_123343_() + 0.5);
            ec.m_31056_(true);
            if (!level.m_7967_(ec)) {
                log("末地水晶生成失败 → 放弃轰炸");
                return false;
            }
            spawned = ec;
            maid.m_6674_(InteractionHand.MAIN_HAND);
        } else if (ph.kind == Kind.ANCHOR) {
            ItemStack glow = takeOne(maid, ID_GLOWSTONE);
            if (glow.m_41619_()) {
                log("萤石取不到（重生锚要有 1 级充能才会炸）→ 放弃轰炸");
                return false;
            }
            try {
                net.minecraft.world.level.block.RespawnAnchorBlock.m_269573_(maid, level, base, level.m_8055_(base));
            } catch (Throwable ignored) {
            }
            maid.m_6674_(InteractionHand.MAIN_HAND);
        }
        List<BlockPos> posList = ph.placed.isEmpty() ? null : new ArrayList<>(ph.placed);
        List<Block> blockList = ph.placedBlock.isEmpty() ? null : new ArrayList<>(ph.placedBlock);
        if (PENDING.size() < MAX_PENDING) {
            PENDING.add(new Bomb(level, maid, spawned, base, posList, blockList, ph.kind, gameTime + cfgFuse()));
        }
        if (cfgPinkMark()) {
            if (spawned != null) {
                BombMarkNetworking.send(maid, 1, spawned.m_19879_(), null, cfgFuse() + 20);
            } else {
                BombMarkNetworking.send(maid, 0, 0, base, cfgFuse() + 20);
            }
        }
        log(com.maidsmart.tool.PromaidLog.nameOf(maid) + " " + ph.kind.cn + " 就位，"
                + cfgFuse() + " tick 后起爆（威力 " + ph.kind.power + (ph.kind.fire ? "，带火" : "") + "）");
        return true;
    }

    /** 把女仆自己放下的那几块撤掉（不掉落） */
    private static void rollback(ServerLevel level, Phase ph) {
        removePlaced(level, ph.placed, ph.placedBlock);
        ph.placed.clear();
        ph.placedBlock.clear();
    }

    private static void removePlaced(ServerLevel level, List<BlockPos> posList, List<Block> blockList) {
        if (level == null || posList == null || blockList == null) {
            return;
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
                level.m_7731_(p, Blocks.f_49990_.m_49966_(), 2 | 16);
            } else {
                level.m_7471_(p, false); // 单体方块：removeBlock 不掉落
            }
        }
    }

    /* ==================== 放置 ==================== */

    /**
     * 在目标脚边找一格放下（离她最近的四个水平邻居优先）。
     * 放在**目标脚边**而不是目标身上：既不把怪顶起来，水晶也正好贴在它身侧。
     */
    private static BlockPos placeOnSupport(ServerLevel level, EntityMaid maid, LivingEntity target, ItemStack stack) {
        BlockPos tp = target.m_20183_();
        List<BlockPos> cand = new ArrayList<>(4);
        for (Direction d : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            cand.add(tp.m_121945_(d));
        }
        final double mx = maid.m_20185_();
        final double my = maid.m_20186_();
        final double mz = maid.m_20189_();
        BlockPos maidFeet = maid.m_20183_();
        BlockPos maidHead = maidFeet.m_7494_();
        cand.sort(Comparator.comparingDouble(p -> {
            double dx = p.m_123341_() + 0.5 - mx;
            double dy = p.m_123342_() + 0.5 - my;
            double dz = p.m_123343_() + 0.5 - mz;
            return dx * dx + dy * dy + dz * dz;
        }));
        for (BlockPos p : cand) {
            if (p.equals(maidFeet) || p.equals(maidHead)) {
                continue; // 别把自己埋了
            }
            BlockState below = level.m_8055_(p.m_7495_());
            if (!below.m_60783_(level, p.m_7495_(), Direction.UP)) {
                continue; // 悬空放不住
            }
            BlockPlaceContext ctx = new MaidPlaceContext(level, maid, InteractionHand.MAIN_HAND, stack,
                    new BlockHitResult(new Vec3(p.m_123341_() + 0.5, p.m_123342_(), p.m_123343_() + 0.5),
                            Direction.UP, p.m_7495_(), false));
            try {
                InteractionResult r = ((BlockItem) stack.m_41720_()).m_40576_(ctx);
                if (r != null && r.m_19077_()) {
                    return ctx.m_8083_();
                }
            } catch (Throwable t) {
                log("放置异常：" + t);
            }
        }
        return null;
    }

    /** 放完之后那一格的方块（用于后面精确撤除） */
    private static Block placedBlockOf(ServerLevel level, BlockPos spot, ItemStack stack) {
        try {
            if (stack.m_41720_() instanceof BlockItem bi) {
                return bi.m_40614_();
            }
        } catch (Throwable ignored) {
        }
        return level.m_8055_(spot).m_60734_();
    }

    /**
     * 女仆用的放置上下文：**玩家位留 null**（原版 BlockItem.place 因此只做"放方块 + 扣物品"，
     * 不碰玩家专属逻辑），"朝向类"取值改用她本人——床的朝向由此与她的朝向一致。
     * 基类带 Level 的那个构造器是 protected，所以只能用一个内部类接下来。
     */
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

    /* ==================== 远程空袭：投掷 TNT ==================== */

    /**
     * 远程空袭盘旋期间的投掷入口（"女仆会在天上盘旋期间额外发射 tnt"）：把她的当前目标直接交给
     * 共用投掷实现——与下面"所有战斗模式"那条走的是**同一套投掷与冷却**，不会重复扔。
     */
    public static void tickRangedTnt(ServerLevel level, EntityMaid maid, LivingEntity target, UUID id, long gameTime) {
        try {
            if (level == null || maid == null || target == null || !cfgTnt()) {
                return;
            }
            if (gameTime < TNT_NEXT.getOrDefault(id, 0L)) {
                return;
            }
            throwTntAt(level, maid, target, id, gameTime);
        } catch (Throwable t) {
            log("投掷异常：" + t);
        }
    }

    /**
     * v1.2.2 实测五百八十八【推广到所有有战斗标签的模式】：不再只在远程空袭的盘旋里扔。
     *
     * 口径（对照女仆生存 MaidTntInteractionController 的做法，自己实现）：
     * ① 任务必须是战斗类——{@code MaidWorkTags.isCombatTask}（IAttackTask 接口判定 + UID 兜底），
     *    于是近战/弓弩/三叉戟/弹幕/枪械，以及本模组两种空袭与第三方战斗任务全部覆盖；
     * ② 有 TNT + 打火石（缺料静默跳过，不占用冷却）；
     * ③ 冷却到点 + 半径内有**合法敌对目标**；
     * ④ 防误伤：目标先过 {@link FriendlyFireGuard#isFriendly}（主人 / 同主女仆 / 友军一律不打），
     *    再过任务自己的 {@code IAttackTask.canAttack}（与 TLM 的索敌口径一致）；
     * ⑤ 女仆自己残血（≤ 阈值）时**连投**数发，横向散开——照女仆生存那套"低血量爆发"思路。
     */
    public static void tickCombatTnt(ServerLevel level, EntityMaid maid) {
        try {
            if (level == null || maid == null || !cfgTnt()) {
                return;
            }
            if (!com.maidsmart.task.MaidWorkTags.isCombatTask(maid)) {
                return; // 非战斗任务不扔（干活/待机/跟随都不打扰）
            }
            if (!maid.m_6084_() || maid.m_213877_()) {
                return;
            }
            UUID id = maid.m_20148_();
            long gameTime = level.m_46467_();
            if (gameTime < TNT_NEXT.getOrDefault(id, 0L)) {
                return;
            }
            if (!has(maid, ID_TNT) || !has(maid, ID_FLINT_AND_STEEL)) {
                return; // 不刚需：缺料直接跳过
            }
            LivingEntity target = findThrowTarget(level, maid);
            if (target == null) {
                return;
            }
            throwTntAt(level, maid, target, id, gameTime);
        } catch (Throwable t) {
            log("战斗投掷异常：" + t);
        }
    }

    /** 半径内最近的**合法敌对**目标（防误伤口径见 {@link #tickCombatTnt} 的注释） */
    private static LivingEntity findThrowTarget(ServerLevel level, EntityMaid maid) {
        double r = Math.max(2.0, cfgTntRange());
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

    /** 这一发要扔几枚：残血（≤ 阈值）连投，否则 1 枚 */
    private static int throwCount(EntityMaid maid) {
        try {
            float max = maid.m_21233_();
            if (max > 0.0f && maid.m_21223_() / max <= cfgTntBurstRatio()) {
                return Math.max(1, cfgTntBurstCount());
            }
        } catch (Throwable ignored) {
        }
        return 1;
    }

    /**
     * 共用的投掷实现（远程空袭盘旋 + 所有战斗模式两条入口都走这里）。
     * 手法照"女仆生存"那套思路自己实现：水平单位向量 × 初速 + 竖直补偿（按 TNT 重力估飞行时间），
     * 落点按目标中心算；连投时按序号横向散开，避免三枚叠在一条线上。
     */
    private static void throwTntAt(ServerLevel level, EntityMaid maid, LivingEntity target, UUID id, long gameTime) {
        int want = throwCount(maid);
        int thrown = 0;
        for (int i = 0; i < want; i++) {
            if (!has(maid, ID_TNT) || !has(maid, ID_FLINT_AND_STEEL)) {
                break;
            }
            ItemStack tntStack = takeOne(maid, ID_TNT);
            if (tntStack.m_41619_()) {
                break;
            }
            ItemStack flint = takeOne(maid, ID_FLINT_AND_STEEL);
            if (flint.m_41619_()) {
                giveBack(maid, tntStack);
                break;
            }
            try {
                flint.m_41622_(1, maid, m -> m.m_21166_(EquipmentSlot.MAINHAND)); // 打火石点一次掉 1 耐久（原版口径）
            } catch (Throwable ignored) {
            }
            double sx = maid.m_20185_();
            double sy = maid.m_20186_() + maid.m_20192_() * 0.75;
            double sz = maid.m_20189_();
            double tx = target.m_20185_();
            double ty = target.m_20186_() + target.m_20192_() * 0.5;
            double tz = target.m_20189_();
            double dx = tx - sx;
            double dy = ty - sy;
            double dz = tz - sz;
            double dh = Math.sqrt(dx * dx + dz * dz);
            double speed = Math.max(0.05, cfgTntSpeed());
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
            // 抛体：飞行时间 ≈ 水平距离 / 水平速度，竖直初速补落差（TNT 重力 0.04、每 tick 阻尼 0.98）
            double t = Math.max(1.0, dh / speed);
            double vy = Mth.m_14008_(dy / t + 0.5 * 0.04 * t, 0.05, 0.9);
            PrimedTnt tnt = new PrimedTnt(level, sx, sy, sz, maid);
            tnt.m_32085_(cfgTntFuse());
            tnt.m_20256_(new Vec3(vx, vy, vz));
            if (!level.m_7967_(tnt)) {
                giveBack(maid, tntStack);
                break;
            }
            SoundEvent snd = sound(ID_TNT_PRIMED_SOUND);
            if (snd != null) {
                level.m_5594_(null, maid.m_20183_(), snd, SoundSource.BLOCKS, 1.0f, 1.0f);
            }
            maid.m_6674_(InteractionHand.MAIN_HAND);
            if (PENDING.size() < MAX_PENDING) {
                PENDING.add(new Bomb(level, maid, tnt, tnt.m_20183_(), null, null,
                        Kind.TNT, gameTime + cfgTntFuse() + BOMB_TIMEOUT / 2));
            }
            thrown++;
            if (cfgPinkMark()) {
                BombMarkNetworking.send(maid, 1, tnt.m_19879_(), null, cfgTntFuse() + 40);
            }
        }
        if (thrown > 0) {
            TNT_NEXT.put(id, gameTime + cfgTntInterval());
            log(com.maidsmart.tool.PromaidLog.nameOf(maid) + " 投掷 TNT ×" + thrown
                    + "（引信 " + cfgTntFuse() + " tick）");
        }
    }

    /* ==================== 起爆 ==================== */

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.START) {
            return; // START = 实体 tick 之前：原版 TNT 那一炸永远轮不到
        }
        try {
            Iterator<Bomb> it = PENDING.iterator();
            while (it.hasNext()) {
                Bomb b = it.next();
                if (b.level == null || b.maid == null || !b.maid.m_6084_()) {
                    dropEntity(b);
                    it.remove();
                    continue;
                }
                if (b.entity != null && !b.entity.m_6084_()) {
                    it.remove(); // 炸弹实体中途没了（被 /kill 之类）→ 这一发作废
                    continue;
                }
                long gameTime = b.level.m_46467_();
                boolean due;
                if (b.entity instanceof PrimedTnt pt) {
                    // TNT 以引信本身为判据：在本处理器（实体 tick 之前）取"引信 ≤ 1"，
                    // 于是原版那一下（会破坏方块）永远轮不到执行
                    due = pt.m_32100_() <= 1;
                } else {
                    due = gameTime >= b.due;
                }
                if (!due) {
                    if (gameTime > b.due + BOMB_TIMEOUT) {
                        // 迟迟炸不了（区块没加载等）→ 连同实体一起丢掉：**必须 discard**，
                        // 否则它将来会按原版自己炸（会破坏方块，与"不破坏方块"的默认口径冲突）
                        dropEntity(b);
                        it.remove();
                    }
                    continue;
                }
                it.remove();
                detonate(b);
            }
        } catch (Throwable t) {
            log("起爆异常：" + t);
        }
        // v1.2.2 实测五百八十八：战斗任务女仆的 TNT 投掷扫描（4 tick 一次；投掷本身有 ≥20 tick 冷却）
        if ((++combatScanTick % 4) == 0) {
            try {
                net.minecraft.server.MinecraftServer server = event.getServer();
                if (server != null) {
                    for (ServerLevel lvl : server.m_129785_()) {
                        for (Entity e : lvl.m_8583_()) {
                            if (e instanceof EntityMaid m) {
                                tickCombatTnt(lvl, m);
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                log("战斗投掷扫描异常：" + t);
            }
        }
    }

    /** 战斗投掷扫描节流计数（tick） */
    private static int combatScanTick = 0;

    /** 放弃一发作废的炸弹：实体一起清掉（不能留着让它走原版爆炸） */
    private static void dropEntity(Bomb b) {
        try {
            if (b.entity != null && b.entity.m_6084_()) {
                b.entity.m_142687_(Entity.RemovalReason.DISCARDED);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void detonate(Bomb b) {
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
        // ① 先撤掉女仆自己放的那几块（无论开不开"破坏方块"都要撤：不撤就等于白送黑曜石/床）
        removePlaced(level, b.placedPos, b.placedBlock);
        // ② 炸弹实体本身清掉（不能走 kill()——末地水晶的 kill 会触发原版那一炸）
        if (b.entity != null && b.entity.m_6084_()) {
            b.entity.m_142687_(Entity.RemovalReason.DISCARDED);
        }
        explode(level, maid, x, y, z, b.kind.power, b.kind.fire);
    }

    /**
     * 统一的爆炸出口：**默认保护口径**（不破坏方块 / 不伤主人友军 / 主人友军不被震），
     * 全部按配置可切。归因给女仆时三条既有护栏自动生效（见类注释）。
     */
    private static void explode(ServerLevel level, EntityMaid maid, double x, double y, double z, float power, boolean fire) {
        Level.ExplosionInteraction mode = cfgBreakBlocks()
                ? Level.ExplosionInteraction.BLOCK
                : Level.ExplosionInteraction.NONE;
        // v1.2.2 实测五百八十八：整个爆炸期间挂"自爆风免"窗口——这个机制的风对放炸弹的她本人
        // 也不生效（与重锤风爆不同）。窗口是同步的，explode() 返回即关。
        selfImmuneBlast = true;
        selfImmuneMaidId = maid == null ? null : maid.m_20148_();
        try {
            if (!cfgHurtFriendly()) {
                // 默认口径：**把女仆当爆炸来源**（伤害源交给原版按来源实体自己构造）。
                // 于是 damageSource.getEntity() == 女仆 → FriendlyFireGuard 取消主人/同主女仆/友军的
                // 伤害；Explosion 的来源实体同样是她 → FriendlyWindGuard 的击退豁免也一并生效。
                level.m_254951_(maid, null, null, new net.minecraft.world.phys.Vec3(x, y, z), power, fire, mode);
                return;
            }
            // 原版口径：来源实体留空 = 完全不归因（主人/友军照掉血照被炸飞），
            // 并用 vanillaBlast 让风免在这一瞬间让位，避免"血掉了、人没飞"。
            vanillaBlast = true;
            try {
                level.m_254951_(null, null, null, new net.minecraft.world.phys.Vec3(x, y, z), power, fire, mode);
            } finally {
                vanillaBlast = false;
            }
        } finally {
            selfImmuneBlast = false;
            selfImmuneMaidId = null;
        }
    }
}
