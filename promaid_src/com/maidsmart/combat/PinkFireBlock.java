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
import net.minecraft.world.level.LevelAccessor;
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
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
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
 * **只有 {@link MaidBombing} 自己那一炸**：爆炸前后开一段同步窗口（{@link #beginWindow()}），
 * 窗口内原版每点着一格火都会经过 {@code BaseFireBlock.getState}，mixin 就把返回值换成这个方块
 * ——那一炸点着的**每一格**都是粉色火，与距离无关（实测日志：`粉色火焰：这一炸点着的 119 格火
 * 直接生成为粉色火`）。
 * <p>
 * 实测六百〇一【范围收紧，反馈："你这样等于直接开挂了呀"】：实测六百 曾经把这条改成"开关开着
 * 就把世界里每一格新火都换掉"，那等于顺手关掉了全世界的火蔓延（含玩家自己点的火）——
 * <b>已经收回</b>：窗口之外的火一概不碰，谁点的还是谁的（打火石 / 闪电 / 岩浆 / 别的模组 /
 * 世界里早就存在的原版火都不受影响）。
 * 每一格连着哪几面照抄原版状态（{@link #fromVanillaFire}）——外观位置与原版一模一样。
 *
 * ── 三条行为收紧（这是"女仆自己放的火"，不该像野火一样烧掉她的家）──
 * ① {@link #m_213897_}（tick）：**不蔓延**。原版 {@code FireBlock.tick} 会按可燃度往四向 + 上方
 *    传火（那才是"一把火烧掉整片森林"的来源），这里整段替换成"数秒后自己熄灭"，一个邻居都不点。
 *    **v1.2.2 实测六百〇二【一定会灭】**：这条路依赖方块 tick 链，而链只在"区块正在 tick"时才会走
 *    ——实测玩家世界里残留的 70 格粉火 AGE 全是 0/1、每格都还挂着计划 tick，说明它们的区块在老化
 *    之前就停了 tick（远处战斗 / 玩家走开 / 区块卸载），于是"粉色的火又不会熄灭了"。
 *    现在由 {@link PinkFireSweep} 补两条不依赖 tick 的兜底（登记表到期抹除 + 区块加载即清），
 *    tick 链本身保持不动。
 * ② {@link #m_7892_}（entityInside）：**玩家与女仆免伤**（开关 {@code bombing.fireProtect}）。
 *    原版这一处既点燃（{@code remainingFireTicks + 1}、归零那一下 {@code setSecondsOnFire(8)}）
 *    又掉血（{@code damageSources().inFire()}），所以豁免要**整个跳过**——只挡伤害不挡点燃
 *    等于"跑出火圈还在烧"，那不算免疫。
 * ③ {@link #m_214162_}（animateTick）：原版火星是**按"火下面那块方块"选粒子**的
 *    （灵魂沙/灵魂土 → 灵魂火粒子，其它 → 橙色焰火粒子），这里换成**粉色尘粒**
 *    （{@code DustParticleOptions}，与贴图同一个粉色），否则粉色火会冒橙色火星。
 *
 * ── 渲染开关 ──
 * {@code bombing.fireRender} 关掉时 {@link #m_7514_} 返回 {@code INVISIBLE}：方块**还在**那一格
 * （熄灭逻辑、按 {@code fireProtect} 决定的伤害判定照旧），只是不画出来。想彻底不要火，
 * 关 {@code bombing.pinkFire} 那一条——那时 {@link MaidBombing} 根本不换火，保持旧版原版橙色火。
 */
public final class PinkFireBlock extends FireBlock {

    /** 与物品同一命名空间（{@code assets/maid_smart/**} 下放 blockstate / 模型 / 贴图） */
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, "maid_smart");

    public static final RegistryObject<Block> PINK_FIRE =
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

    /**
     * v1.2.2 实测六百〇一【范围收回到"她那一炸"】。
     *
     * 实测六百 曾经把这里改成"开关开着 → 世界里每一格新火都换成粉色"（为了兜住窗口之外那些
     * 把玩家烧着的火）。反馈原话：**"你这样等于直接开挂了呀。你应该只影响女仆造成爆炸所产生的
     * 火焰，并且将它替换。而不是将所有的火焰全都开了。"**——确实如此：粉火不蔓延、几秒自灭，
     * "全都换"实际上等于顺手把整个世界的火蔓延关掉了（你自己点的火也一样），
     * 那已经超出这个功能的边界。现在收回成**只认她自己那一炸**：
     *
     * <ul>
     *   <li>换的时机 = {@link MaidBombing} 爆炸前后开的那一段窗口（{@link #inWindow()}）——
     *       原版点火的每一格都在这个同步调用链里拿到粉色火，一炸点着多少格就换多少格
     *       （实测日志：`粉色火焰：这一炸点着的 119 格火直接生成为粉色火`）；</li>
     *   <li>窗口之外（打火石 / 闪电 / 火焰弹 / 岩浆 / 别的模组 / 世界里早就存在的原版火）
     *       <b>一概不碰</b>——那些火是谁的就是谁的，本模组既不换也不灭；</li>
     *   <li>{@link #pinkEnabled()} 只作为"这一炸要不要换"的判据（半路把开关关掉 → 之后的炸
     *       照原版口径留橙色火）。</li>
     * </ul>
     */
    public static boolean pinkEnabled() {
        try {
            return MaidSmartConfig.COMBAT_BOMBING_PINK_FIRE.get();
        } catch (Throwable ignored) {
            return false; // 读不到配置 = 什么都不换（绝不擅自改世界）
        }
    }

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
        // 属性照抄原版火（Blocks 反编译实证：of().mapColor(COLOR_ORANGE)….noCollission().
        // instabreak().lightLevel(s -> 15).sound(WOOL).pushReaction(DESTROY)），只换音效之外的零项
        super(BlockBehaviour.Properties.m_284310_()
                .m_284180_(MapColor.f_283816_)
                .m_280170_()
                .m_60910_()
                .m_60966_()
                .m_60953_(s -> 15)
                .m_60918_(SoundType.f_56745_)
                .m_278166_(PushReaction.DESTROY));
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
        BlockState out = self.m_49966_();
        try {
            if (vanillaFire.m_60713_(Blocks.f_50084_)) { // 灵魂火
                return out.m_61124_(FireBlock.f_53408_, vanillaFire.m_61143_(FireBlock.f_53408_))
                        .m_61124_(FireBlock.f_53413_, true);
            }
            out = out
                    .m_61124_(FireBlock.f_53408_, vanillaFire.m_61143_(FireBlock.f_53408_))
                    .m_61124_(FireBlock.f_53409_, vanillaFire.m_61143_(FireBlock.f_53409_))
                    .m_61124_(FireBlock.f_53410_, vanillaFire.m_61143_(FireBlock.f_53410_))
                    .m_61124_(FireBlock.f_53411_, vanillaFire.m_61143_(FireBlock.f_53411_))
                    .m_61124_(FireBlock.f_53412_, vanillaFire.m_61143_(FireBlock.f_53412_))
                    .m_61124_(FireBlock.f_53413_, vanillaFire.m_61143_(FireBlock.f_53413_));
        } catch (Throwable ignored) {
            out = out.m_61124_(FireBlock.f_53413_, true); // 读不到属性就退回"地面火"形态，至少看得见
        }
        return out;
    }

    /* ==================== ① 不蔓延、数秒后自灭 ==================== */

    /**
     * 只做"自己熄灭 + 排下一拍"，**一个邻居都不点**：原版 {@code FireBlock.tick} 的传火逻辑
     * （四向 + 上方按可燃度掷骰子）在这里被整段替换掉。
     */
    @Override
    public void m_213897_(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        try {
            if (!state.m_60713_(this)) {
                return; // 那一格已经不是我们的火了（被拆 / 被替换）→ 什么都不做
            }
            if (!this.m_7898_(state, level, pos)) {
                level.m_7471_(pos, false); // 支撑面没了 → 立刻撤
                return;
            }
            int age = state.m_61143_(FireBlock.f_53408_);
            if (age >= MAX_AGE_TICKS) {
                level.m_7471_(pos, false); // 烧够了 → 自灭（不掉落任何东西）
                return;
            }
            // v1.2.2 实测五百九十八【"粉色火焰永远不熄灭"的根因】：原版火每 tick 都靠
            // `ServerLevel.scheduleTick(pos, this, 30 + rand(10))`（SRG **m_186460_**）给自己
            // 排下一拍；旧版这里误写成了 `m_6933_(pos, state, 15 + rand(10), 0)`——那是
            // **Level.setBlock 的四参重载**（m_7731_ 的兄弟，不是排 tick！），于是：
            // ① 方块只在原版 onPlace 排的那一拍动过一次（AGE 0→1），之后**永远不再 tick**，
            //    AGE 到不了 MAX_AGE_TICKS，火就那样烧到天荒地老（实测反馈原文）；
            // ② 那次"假设置"还把状态写回默认值 + 用了个 15~24 的奇怪 flag（含 16 =
            //    UPDATE_KNOWN_SHAPE），等于每次 tick 顺手把火的外形重置一遍。
            // 现在照原版 FireBlock.tick 的口径：先改 AGE（flag 4，与 `p_221161_.m_7731_(pos, state, 4)`
            // 同一个值：只发变化、不惊动邻居），再排下一拍。
            level.m_7731_(pos, state.m_61124_(FireBlock.f_53408_, age + 1), 4);
            level.m_186460_(pos, this, 15 + random.m_188503_(10));
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
    public void m_7892_(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (protectedFromFire(entity)) {
            return;
        }
        super.m_7892_(state, level, pos, entity);
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
    public void m_214162_(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!cfgRender()) {
            return;
        }
        try {
            if (random.m_188503_(24) == 0) {
                level.m_7785_(pos.m_123341_() + 0.5, pos.m_123342_() + 0.5, pos.m_123343_() + 0.5,
                        SoundEvents.f_11936_, SoundSource.BLOCKS,
                        1.0F + random.m_188501_(), random.m_188501_() * 0.7F + 0.3F, false);
            }
            boolean floorFire = false;
            for (Direction d : SIDES) {
                BlockPos np = pos.m_121945_(d);
                if (!this.m_7599_(level.m_8055_(np))) {
                    continue;
                }
                for (int i = 0; i < 2; i++) {
                    spark(level, random,
                            np.m_123341_() + 0.5 + (random.m_188500_() - 0.5) * 0.6,
                            np.m_123342_() + random.m_188500_(),
                            np.m_123343_() + 0.5 + (random.m_188500_() - 0.5) * 0.6);
                }
                floorFire = true;
            }
            if (this.m_7599_(level.m_8055_(pos.m_7494_()))) {
                for (int i = 0; i < 2; i++) {
                    spark(level, random,
                            pos.m_123341_() + random.m_188500_(),
                            pos.m_123342_() + 1.0 - random.m_188500_() * 0.1,
                            pos.m_123343_() + random.m_188500_());
                }
                floorFire = true;
            }
            if (floorFire) {
                return; // 贴着燃料的火：只在燃料那一面冒（与原版分支一致）
            }
            for (int i = 0; i < 3; i++) {
                spark(level, random,
                        pos.m_123341_() + random.m_188500_(),
                        pos.m_123342_() + random.m_188500_() * 0.5 + 0.5,
                        pos.m_123343_() + random.m_188500_());
            }
        } catch (Throwable ignored) {
        }
    }

    private static final Direction[] SIDES = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private static void spark(Level level, RandomSource random, double x, double y, double z) {
        try {
            level.m_7106_(new DustParticleOptions(PINK, 0.9F + random.m_188501_() * 0.4F),
                    x, y, z, 0.0, 0.0, 0.0);
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 渲染开关 ==================== */

    /**
     * 关掉渲染时返回 {@code INVISIBLE}：方块还在那一格（熄灭、伤害判定照旧），只是不画。
     */
    @Override
    public RenderShape m_7514_(BlockState state) {
        return cfgRender() ? RenderShape.MODEL : RenderShape.INVISIBLE;
    }

    private static boolean cfgRender() {
        try {
            return MaidSmartConfig.COMBAT_BOMBING_FIRE_RENDER.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /* ==================== ④ 邻居更新不许把粉火打回原版火 ==================== */

    /**
     * v1.2.2 实测六百〇二【"又出现橙火焰"与"火不灭"的真正根因——就在这两处转发】。
     *
     * 原版 {@code FireBlock.m_7417_}（updateShape）在**任何邻居变化**时都会把火重算一遍：
     * {@code canSurvive ? m_53437_(level, pos, AGE) : AIR}，而那个私有助手算出来的是
     * **原版火**的状态——它内部走 {@code BaseFireBlock.getState(level, pos)}，里面写死
     * {@code (FireBlock) Blocks.f_50083_}。于是粉火只要旁边动一下（爆炸、方块变化、水流、别的火…）
     * 就会被打回 {@code minecraft:fire}。
     *
     * 【实测证据】专用服务器：命令放下粉火 → 旁边放一块石头 → 探针 `if block … maid_smart:pink_fire`
     * 立刻为假；存档 NBT 里那一格是 {@code minecraft:fire age=1..3}，整段调色板里根本没有
     * {@code maid_smart:pink_fire}。玩家看到的两个现象因此都能对上：
     * ① "爆炸的时候又产生粉色火焰又产生普通火焰"（橙色那些就是被打回原版的）；
     * ② **原版火在不燃物上是永不熄灭的**（粉火会自灭，橙火不会）——"粉色的火又不会熄灭了"。
     *
     * 现在把这一步翻回粉色，并且**保留它自己当前的 AGE**：否则每次邻居更新都会把自灭计时清零，
     * 频繁更新等于给它反复续命。
     */
    @Override
    public BlockState m_7417_(BlockState state, Direction dir, BlockState neighborState, LevelAccessor level,
                              BlockPos pos, BlockPos neighborPos) {
        BlockState out = super.m_7417_(state, dir, neighborState, level, pos, neighborPos);
        try {
            if (out == null || !isVanillaFire(out)) {
                return out; // 支撑没了（原版给 air）等其它情形：原样返回，绝不在该消失的地方复活
            }
            BlockState pink = fromVanillaFire(out);
            if (pink == null) {
                return out;
            }
            return pink.m_61124_(FireBlock.f_53408_, state.m_61143_(FireBlock.f_53408_));
        } catch (Throwable ignored) {
            return out;
        }
    }

    /**
     * 这一格是不是"原版火"（{@code Blocks.f_50083_} = FIRE、{@code f_50084_} = SOUL_FIRE）。
     *
     * 【用字段身份比较，不用 instanceof】反编译实证（javap {@code BaseFireBlock.m_49245_}）：
     * 原版火是 {@code Blocks.f_50083_}——注意**不是** {@code f_50025_}（实测六百〇二踩过这个坑：
     * 一开始写错字段，守卫永远为假，于是"粉火被打回原版火"照旧发生）。
     */
    private static boolean isVanillaFire(BlockState st) {
        Block owner = st.m_60734_();
        return owner == Blocks.f_50083_ || owner == Blocks.f_50084_;
    }

    /** 上面那一支的重算源头（原版同样返回原版火）——照抄它，只把颜色换回粉色 */
    @Override
    protected BlockState m_53470_(BlockGetter level, BlockPos pos) {
        BlockState out = super.m_53470_(level, pos);
        try {
            BlockState pink = fromVanillaFire(out);
            return pink != null ? pink : out;
        } catch (Throwable ignored) {
            return out;
        }
    }

    /** 点燃判定盒沿用原版火（{@code BaseFireBlock} 那个扁平形状），这里显式转发一次便于阅读 */
    @Override
    public VoxelShape m_5940_(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return super.m_5940_(state, level, pos, ctx);
    }
}
