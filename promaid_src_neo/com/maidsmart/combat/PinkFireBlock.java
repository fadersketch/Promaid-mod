package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.joml.Vector3f;

/**
 * v1.2.2 实测五百九十七【粉色火焰】（纯表现 + 一层伤害豁免）。
 *
 * ── 需求原文 ──
 * "同时将重生锚，末影水晶，床爆炸渲染出来的火焰变成粉色。不需要有其他的文字描述。
 * 玩家和女仆会免疫这个火焰造成的伤害。配置里面可以关闭这个火焰的渲染或者伤害保护。"
 *
 * ── 为什么必须是"真的一个方块" ──
 * 需求说的是**火焰本身**（原版那种在地面上跳动的火），不是粒子特效。原版火的外观来自方块模型
 * （{@code block/fire_floor0/1 + fire_side0/1 + fire_side_alt0/1 + fire_up0/1 + fire_up_alt0/1}，
 * 贴图 {@code block/fire_0}、{@code fire_1}，32 帧动画），"哪里着火"则由 {@link FireBlock} 的
 * NORTH/EAST/SOUTH/WEST/UP 五个布尔属性 + multipart blockstate 决定。而**颜色没法靠 tint 改**：
 * 火焰贴图的蓝通道几乎为 0，乘任何颜色都只能变暗、乘不出粉色。所以只有两条路——改贴图，
 * 或者加一个自定义方块。这里选后者：继承 {@link FireBlock}（属性、连接逻辑、点燃 AABB 全部照原版），
 * 只把**贴图换成粉色**、把**行为收紧**（不蔓延、几秒后自灭、对玩家与女仆不生效）。
 *
 * 贴图怎么来的：把原版 {@code block/fire_0.png} / {@code fire_1.png} 做**色相映射**到粉色
 * （保持明度与 alpha，火焰形状、32 帧动画、{@code .mcmeta} 帧表全部沿用原版），
 * 于是形状就是玩家熟悉的那团火，只是颜色换成粉色。
 *
 * ── 谁把它放下来 ──
 * 只有 {@link MaidBombing} 的爆炸：那一炸期间开着"点火窗口"，原版每点着一格火都会经过
 * {@code BaseFireBlock.getState}，窗口内的 mixin 直接把返回值换成这个方块（见 {@link #beginWindow}）。
 * 每一格连着哪几面照抄原版状态（{@link #fromVanillaFire}）——外观位置与原版一模一样，
 * 而主人自己的火堆**根本不走点火路径**，不受影响。
 *
 * ── 三条行为收紧（这是"女仆自己放的火"，不该像野火一样烧掉她的家）──
 * ① {@link #tick}：**不蔓延**。原版 {@code FireBlock.tick} 会按可燃度往四向 + 上方传火
 *    （那才是"一把火烧掉整片森林"的来源），这里整段替换成"数秒后自己熄灭"，一个邻居都不点。
 * ② {@link #entityInside}：**玩家与女仆免伤**（开关 {@code bombing.fireProtect}）。原版这一处
 *    既点燃（{@code remainingFireTicks + 1}、归零那一下 {@code setSecondsOnFire(8)}）又掉血
 *    （{@code damageSources().inFire()}），所以豁免要**整个跳过**——只挡伤害不挡点燃等于
 *    "跑出火圈还在烧"，那不算免疫。
 * ③ {@link #animateTick}：原版火星是**按"火下面那块方块"选粒子**的（灵魂沙/灵魂土 → 灵魂火
 *    粒子，其它 → 橙色焰火粒子），这里换成**粉色尘粒**（{@code DustParticleOptions}，与贴图同一个
 *    粉色），否则粉色火会冒橙色火星。
 *
 * ── 渲染开关 ──
 * {@code bombing.fireRender} 关掉时 {@link #getRenderShape} 返回 {@code INVISIBLE}：方块**还在**
 * 那一格（熄灭逻辑、按 {@code fireProtect} 决定的伤害判定照旧），只是不画出来。想彻底不要火，
 * 关 {@code bombing.pinkFire} 那一条——那时 {@link MaidBombing} 根本不换火，保持旧版原版橙色火。
 */
public final class PinkFireBlock extends FireBlock {

    /** 与物品同一命名空间（{@code assets/maid_smart/**} 下放 blockstate / 模型 / 贴图） */
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("maid_smart");

    public static final DeferredBlock<PinkFireBlock> PINK_FIRE =
            BLOCKS.register("pink_fire", PinkFireBlock::new);

    /** 粉色火星的颜色（与贴图色相一致） */
    private static final Vector3f PINK = new Vector3f(1.00F, 0.36F, 0.86F);

    /** AGE 到这条线就自灭；每拍间隔：第一拍沿用原版 onPlace 的 30~39 tick，之后 15~24 tick
     *  （实测：放下去到 AGE=4 约 8 秒，再下一拍消失） */
    private static final int MAX_AGE_TICKS = 4;

    /* ==================== ⓪ 点火窗口（爆炸时"边点边换"） ==================== */

    /**
     * v1.2.2 实测五百九十八【"又粉又橙"的根因与改法】。
     *
     * 旧做法是"爆炸前把这一带已有的火扫一遍、爆炸后把新出现的原版火逐个换成粉色"，两个洞：
     * <ul>
     *   <li><b>盒子永远不够大</b>：原版点火的位置来自 {@code Explosion.finalizeExplosion} 里那个
     *       {@code toBlow} 集合，而它是**射线**扫出来的——空气的爆炸阻力是 0，射线每步只花
     *       0.09 能量，于是"功率 5"的一炸，射线能穿过空气跑到 {@code 5 × 1.3 ÷ 0.09 × 0.3}
     *       ≈ <b>20 格开外</b>。旧版按 {@code ceil(power)+2 = 7} 扫，盒子外的那些火自然还是
     *       原版的——实测反馈"爆炸的时候又产生粉色火焰又产生普通火焰"就是这个；</li>
     *   <li><b>顺序不对</b>：换的动作发生在爆炸之后，那一瞬间火仍是原版火（受重力/蔓延规则影响）。</li>
     * </ul>
     * 现在改成<b>在它点火那一刻就把状态换掉</b>：{@link MaidBombing} 在 {@code level.explode(...)}
     * 前后开/关这个窗口，{@code BaseFireBlockPinkMixin} 在窗口内把 {@code BaseFireBlock.getState}
     * 的返回值翻译成粉色火——于是原版点出来的**每一格**直接就是粉色火，与距离无关，
     * 也没有"先橙后粉"的中间态。窗口之外（打火石 / 闪电 / 火焰弹点的火）一概不动，
     * 主人自己的火堆更不会受影响（它压根不走点火路径）。
     */
    private static boolean window = false;
    /** 本次爆炸点着的火有几格（关窗口时取走并清零，只用于日志） */
    private static int windowCount = 0;

    /** 开窗口（{@link MaidBombing} 在爆炸前调用） */
    public static void beginWindow() {
        window = true;
        windowCount = 0;
    }

    /** 关窗口，返回这一次点着的粉色火格数 */
    public static int endWindow() {
        window = false;
        int n = windowCount;
        windowCount = 0;
        return n;
    }

    public static boolean inWindow() {
        return window;
    }

    /** mixin 每换掉一格调一次 */
    public static void countConverted() {
        windowCount++;
    }

    public PinkFireBlock() {
        // 属性照抄原版火（Blocks 反编译实证：of().mapColor(FIRE).replaceable().noCollission().
        // instabreak().lightLevel(s -> 15).sound(WOOL).pushReaction(DESTROY)）
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.FIRE)
                .replaceable()
                .noCollission()
                .instabreak()
                .lightLevel(s -> 15)
                .sound(SoundType.WOOL)
                .pushReaction(PushReaction.DESTROY));
    }

    /** 注册进 mod 事件总线（由 {@code ProMaidMod} 构造器调用） */
    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }

    /** 当前注册表里的实例；未注册（理论上不会）返回 null */
    public static Block block() {
        return PINK_FIRE.get();
    }

    /**
     * 把"原版火在这一格的状态"翻译成粉色火的同款状态：连着哪几面（NORTH/EAST/SOUTH/WEST/UP）
     * 与 AGE 全部照抄 —— 原版 multipart 模型正是按这几个布尔挑模型的，所以外观与原版完全一致。
     *
     * 灵魂火（{@code Blocks.SOUL_FIRE}）只有 AGE、没有方向属性，它本来就是"地面火"形态，
     * 于是按 UP=true 翻译。
     */
    public static BlockState fromVanillaFire(BlockState vanillaFire) {
        Block self = block();
        if (self == null) {
            return null;
        }
        BlockState out = self.defaultBlockState();
        try {
            if (vanillaFire.is(Blocks.SOUL_FIRE)) {
                return out.setValue(FireBlock.AGE, vanillaFire.getValue(FireBlock.AGE))
                        .setValue(FireBlock.UP, true);
            }
            out = out
                    .setValue(FireBlock.AGE, vanillaFire.getValue(FireBlock.AGE))
                    .setValue(FireBlock.NORTH, vanillaFire.getValue(FireBlock.NORTH))
                    .setValue(FireBlock.EAST, vanillaFire.getValue(FireBlock.EAST))
                    .setValue(FireBlock.SOUTH, vanillaFire.getValue(FireBlock.SOUTH))
                    .setValue(FireBlock.WEST, vanillaFire.getValue(FireBlock.WEST))
                    .setValue(FireBlock.UP, vanillaFire.getValue(FireBlock.UP));
        } catch (Throwable ignored) {
            out = out.setValue(FireBlock.UP, true); // 读不到属性就退回"地面火"形态，至少看得见
        }
        return out;
    }

    /* ==================== ① 不蔓延、数秒后自灭 ==================== */

    /**
     * 只做"自己熄灭 + 排下一拍"，**一个邻居都不点**：原版 {@code FireBlock.tick} 的传火逻辑
     * （四向 + 上方按可燃度掷骰子）在这里被整段替换掉。
     */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        try {
            if (!state.is(this)) {
                return; // 那一格已经不是我们的火了（被拆 / 被替换）→ 什么都不做
            }
            if (!this.canSurvive(state, level, pos)) {
                level.removeBlock(pos, false); // 支撑面没了 → 立刻撤
                return;
            }
            int age = state.getValue(FireBlock.AGE);
            if (age >= MAX_AGE_TICKS) {
                level.removeBlock(pos, false); // 烧够了 → 自灭（不掉落任何东西）
                return;
            }
            // v1.2.2 实测五百九十八：flag 4 与原版 FireBlock.tick 一致（只发状态变化、
            // 不惊动邻居）——1.20.1 那棵树曾把"排下一拍"误写成 setBlock 的四参重载，
            // 导致粉色火焰永远不再 tick（实测"永远不熄灭"）；这边一直是 scheduleTick，
            // 顺手把 flag 对齐原版。
            level.setBlock(pos, state.setValue(FireBlock.AGE, age + 1), 4);
            level.scheduleTick(pos, this, 15 + random.nextInt(10));
        } catch (Throwable ignored) {
        }
    }

    /* ==================== ② 玩家与女仆免伤 ==================== */

    /**
     * 原版这一处：先点燃（{@code getRemainingFireTicks() + 1}，归零那一下 {@code setSecondsOnFire(8)}）
     * 再 {@code hurt(inFire, 1)}。对**玩家与女仆**整段跳过 —— 不动 remainingFireTicks、不掉血，
     * "免疫"就是免疫到"出了火也不烧"。其它生物照旧（她放的是炸弹，怪物该烧）。
     */
    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (protectedFromFire(entity)) {
            return;
        }
        super.entityInside(state, level, pos, entity);
    }

    private static boolean protectedFromFire(Entity entity) {
        try {
            if (!MaidSmartConfig.COMBAT_BOMBING_FIRE_PROTECT.get()) {
                return false;
            }
            return entity instanceof Player || entity instanceof EntityMaid;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ==================== ③ 粉色火星（替掉原版的橙色焰火 / 灵魂火粒子） ==================== */

    /**
     * 结构照抄原版 {@code BaseFireBlock.animateTick}：贴着可燃方块的那几面各冒 2 颗、
     * 地面火在外侧冒 3 颗；只把粒子换成**粉色尘粒**，并且"关闭渲染"时一颗都不冒。
     */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!cfgRender()) {
            return;
        }
        try {
            if (random.nextInt(24) == 0) {
                level.playLocalSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                        SoundEvents.FIRE_AMBIENT, SoundSource.BLOCKS,
                        1.0F + random.nextFloat(), random.nextFloat() * 0.7F + 0.3F, false);
            }
            boolean floorFire = false;
            for (Direction d : SIDES) {
                BlockPos np = pos.relative(d);
                if (!this.canBurn(level.getBlockState(np))) {
                    continue;
                }
                for (int i = 0; i < 2; i++) {
                    spark(level, random,
                            np.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.6,
                            np.getY() + random.nextDouble(),
                            np.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.6);
                }
                floorFire = true;
            }
            if (this.canBurn(level.getBlockState(pos.above()))) {
                for (int i = 0; i < 2; i++) {
                    spark(level, random,
                            pos.getX() + random.nextDouble(),
                            pos.getY() + 1.0 - random.nextDouble() * 0.1,
                            pos.getZ() + random.nextDouble());
                }
                floorFire = true;
            }
            if (floorFire) {
                return; // 贴着燃料的火：只在燃料那一面冒（与原版分支一致）
            }
            for (int i = 0; i < 3; i++) {
                spark(level, random,
                        pos.getX() + random.nextDouble(),
                        pos.getY() + random.nextDouble() * 0.5 + 0.5,
                        pos.getZ() + random.nextDouble());
            }
        } catch (Throwable ignored) {
        }
    }

    private static final Direction[] SIDES = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private static void spark(Level level, RandomSource random, double x, double y, double z) {
        try {
            level.addParticle(new DustParticleOptions(PINK, 0.9F + random.nextFloat() * 0.4F),
                    x, y, z, 0.0, 0.0, 0.0);
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 渲染开关 ==================== */

    /**
     * 关掉渲染时返回 {@code INVISIBLE}：方块还在那一格（熄灭、伤害判定照旧），只是不画。
     */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return cfgRender() ? RenderShape.MODEL : RenderShape.INVISIBLE;
    }

    private static boolean cfgRender() {
        try {
            return MaidSmartConfig.COMBAT_BOMBING_FIRE_RENDER.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 点燃判定盒沿用原版火（{@code BaseFireBlock} 那个扁平形状），这里显式转发一次便于阅读 */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return super.getShape(state, level, pos, ctx);
    }
}
