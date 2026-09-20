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
 * ② 重生锚：重生锚（下界不生效）→ 放下 → 替她充 1 级（实测五百九十一起**不要萤石**）；
 * ③ 床：任意床（主世界不生效）→ 放下（原版两格床，朝向按她的朝向）。
 * 三段按 ①②③ 取第一个材料齐的；**放置失败不再试下一段**，直接交还原链路（再次起飞）——
 * 需求原话"不会再进行判定，会直接跳到下一个链路"。
 *
 * ── 实测五百九十一（七件事）──
 * ① 重生锚的放置口径与黑曜石完全一致（同一套三级落点找法）——反馈："重生锚还是放不了，
 *    放置逻辑改为跟放黑曜石一样"；缺料那一路也不再静默（见 ⑨ 的 diagSkip）。
 * ② 方块与水晶 / 充能之间留出**看得见的间隔**（bombing.placeGap，默认 10 tick = 0.5 秒）——
 *    反馈："黑曜石和末地水晶几乎是同时放置的，根本看不出间隔"。
 * ③ **维度闸**（bombing.dimensionGuard）：只在这个维度"原版真的会炸"时才开放重生锚 / 床链路
 *    （见 {@link #explodesHere}）——其他模组新增的维度里那两条写着"能用"就整段不开放。
 * ④ TNT 追踪改成**限时 0.5 秒 + 限转角**（bombing.tntTrackTicks / {@link #TRACK_TURN_DEG}）：
 *    反馈"太鬼畜、运动很不自然"——现在是离手后一小段平滑弧线，只为小幅修准。
 * ⑤ 打火石改成**就地扣耐久**（旧版把整件取出来、只对取出来的那份扣耐久 = 玩家看到的"吞掉"）。
 * ⑥ 放置 / 充能 / 投掷 / 起爆各有一记动作：主手挥臂 + 副手短暂举起正在用的那件东西
 *    （{@link BombPose}，用完原物必还）。
 * ⑦ 回收：黑曜石/基岩底座起爆后仍保留 cfgReclaimSeconds 秒，然后**变成物品回收到她背包**
 *    （实测五百九十三改为：**背包满就掉在她脚下**，与挖矿 / 搭路的方块回收同一口径）；重生锚 / 床
 *    由它们自己那一炸消耗掉（javap 实证原版就是先 removeBlock 再 explode），起爆即撤、
 *    不进回收表、不回背包（回背包 = 放一次白拿一个重生锚）；相位中途失败回滚时原物还她。
 *
 * ── 实测五百九十二（两件事）──
 * ① **萤石要回来**。反馈原文："不行啊，重生锚还是需要一个萤石进行充能的。所以正确的链路应该是
 *    攻击→副手换成重生锚，放置重生锚（摆臂）→副手换成萤石，拿一颗萤石充能（摆臂动画）→继续
 *    切换回飞行。0.5 秒后再挥一次手臂。正好对上重生锚自爆。"
 *    ——于是 pickKind 重新要求 1 颗萤石、stepPayload 重新消耗它，并且**两步各自换一次副手**：
 *    放置时举重生锚、充能时举萤石（{@link BombPose}）；起爆那一刻（放置后 0.5 秒）再挥一记，
 *    副手亮回重生锚——时序正好压上它自己那一炸。
 * ② **轰炸推广到所有攻击模式**。反馈原文："将末地水晶/重生锚/床的机制推广到所有的攻击模式下
 *    （除了远程空袭，因为一直飞在天上，本来就放不了。）"
 *    ——相位机改成由**服务端 tick 统一驱动**（{@link #tickPhases}，它不再只是空袭行为里的一步），
 *    所有战斗任务都在"攻击冷却记忆由无到有"= 刚打完一记那一刻试起手（{@link #tickCombatTnt}），
 *    远程空袭按任务 UID 排除（{@link com.maidsmart.combat.MaidFlightKit#isRangedTask}）。
 *    顺带给整条链路加了最短间隔（bombing.bombInterval，默认 200 tick = 10 秒）：一次挥砍放一枚
 *    会在几秒内烧光她的黑曜石 / 水晶，缺料那一下不占用间隔。
 *
 * ── 实测五百九十三（三件事）──
 * ① 回收的底座**变成掉落物**：塞不进背包的那一份掉在她脚下（{@code spawnAtLocation}，
 *    与挖矿 / 搭路的方块回收同一口径）。
 * ② **爆炸特效对齐原版**：不破坏方块时原版恒走小粒子（{@code ExplosionInteraction.NONE} 的
 *    {@code interactsWithBlocks()} 恒 false，javap 实证）→ 我们**自己补发一枚大粒子**。
 * ③ 落点**兜底强制放置**：原版 place() 会拒绝"那一格站着实体"这类落点，
 *    拒绝之后我们补一手 setBlock（只在那格确实可替换时才做）。
 *
 * ── 实测五百九十四（三件事）──
 * ① **"重生锚还是放不下来"的真相**：实测日志（latest.log，搜「空袭轰炸」）里写的是
 *    `重生锚：缺萤石`——她带着重生锚，只是背包里没有那 1 颗萤石（原版 0 级充能右键不炸）。
 *    旧版这条原因只落日志、而且**床能顶上时连日志都不会有**，于是现在改成**女仆气泡**
 *    把原因说清（{@link #hintAnchorSkip}，60 秒一条）：缺萤石 / 这个维度不炸各有各的说法，
 *    日志同步落一条，下一轮排查不用再猜。
 * ② **投掷 TNT 时副手举打火石**：反馈"在扔 TNT 的时候，副手武器应该切换成打火石"——
 *    旧版亮的是刚扔出去的那枚 TNT，现在点亮火那只手里的打火石（{#link #flintDisplay}，
 *    耐久已在 {@link #useFlintAndSteel} 里就地扣掉，这里只借模型）。
 * ③ **动作表现总开关**（bombing.pose，默认开）：整条链路的"副手亮一下"可以整体关掉，
 *    关掉之后只剩挥臂 / 音效 / 爆炸（见 {@link #pose}）。
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

    /** v1.2.2 实测五百九十：TNT 追踪（默认开）——引信期间朝目标修正朝向 */
    private static boolean cfgTntTrack() {
        return MaidSmartConfig.COMBAT_BOMBING_TNT_TRACK.get();
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

    private static int cfgReclaimSeconds() {
        return MaidSmartConfig.COMBAT_BOMBING_RECLAIM_SECONDS.get();
    }

    /** v1.2.2 实测五百九十一：方块与"挂水晶 / 充能"之间留出的可见间隔（tick，默认 10 = 0.5 秒） */
    private static int cfgPlaceGap() {
        return MaidSmartConfig.COMBAT_BOMBING_PLACE_GAP.get();
    }

    /** v1.2.2 实测五百九十一：TNT 追踪时长（tick，默认 10 = 0.5 秒；0 = 不追踪） */
    private static int cfgTntTrackTicks() {
        return MaidSmartConfig.COMBAT_BOMBING_TNT_TRACK_TICKS.get();
    }

    /** v1.2.2 实测五百九十一：维度闸（默认开）——原版不炸的维度不开放重生锚 / 床链路 */
    private static boolean cfgDimensionGuard() {
        return MaidSmartConfig.COMBAT_BOMBING_DIMENSION_GUARD.get();
    }

    /** v1.2.2 实测五百九十二：重生锚要 1 颗萤石点火（默认开 = 原版口径） */
    private static boolean cfgAnchorNeedsGlowstone() {
        return MaidSmartConfig.COMBAT_BOMBING_ANCHOR_NEEDS_GLOWSTONE.get();
    }

    /** v1.2.2 实测五百九十二：整条轰炸链路的最短间隔（tick，默认 200 = 10 秒） */
    private static int cfgBombInterval() {
        return MaidSmartConfig.COMBAT_BOMBING_BOMB_INTERVAL.get();
    }

    /** v1.2.2 实测五百九十四：动作表现（副手亮一下）总开关（默认开） */
    private static boolean cfgPose() {
        return MaidSmartConfig.COMBAT_BOMBING_POSE.get();
    }

    /**
     * v1.2.2 实测五百九十四【动作表现总开关】。
     *
     * 需求原文："同时这个功能应该有开关。"——整条链路里有三处会被临时换掉的副手物品
     * （放置时举方块 / 充能时举萤石 / 投掷时举打火石，起爆那一记再举一次），这是"看得见她
     * 在做什么"的来源，但也可能有人不想让副手武器在打架时被闪一下——所以给一个开关。
     *
     * 关掉之后只剩挥臂 / 音效 / 爆炸本身（挥臂是打斗反馈，不归这个开关管）；还原逻辑
     * （{@link BombPose#tick}）不受开关影响，所以半路关掉也不会把她的盾牌 / 食物留在手上
     * 换不回来。
     */
    private static void pose(EntityMaid maid, ItemStack display, int ticks) {
        if (!cfgPose()) {
            return;
        }
        BombPose.show(maid, display, ticks);
    }

    /**
     * v1.2.2 实测五百九十四【投掷时副手举打火石】：反馈"在扔 TNT 的时候，副手武器应该切换成
     * 打火石"——照玩家点火的样子来：手里拿的是打火石（那枚 TNT 已经扔出去了、本来就不在手上）。
     * 打火石的耐久早在 {@link #useFlintAndSteel} 里就地扣掉了，这里只借它的模型做表现，
     * 数量与耐久一概不动（{@link BombPose} 到点原物奉还）。
     */
    private static ItemStack flintDisplay() {
        try {
            Item it = item(ID_FLINT_AND_STEEL);
            return it == null ? ItemStack.f_41583_ : new ItemStack(it);
        } catch (Throwable ignored) {
            return ItemStack.f_41583_;
        }
    }

    private static boolean cfgAirPlace() {
        return MaidSmartConfig.COMBAT_BOMBING_AIR_PLACE.get();
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

    /**
     * v1.2.2 实测五百九十一【打火石不再被吞】：**就地**给她手上/背包里的那一件扣 1 点耐久，
     * 没坏就**放回原槽**。
     *
     * 反馈原文："我发现扔 TNT 的时候会直接把打火石吞掉，而不是消耗打火石的耐久。"
     * 旧版走的是 takeOne(...)：把整件打火石从背包里**取出来**（= 从背包消失），只对取出来的那一份
     * 扣耐久——于是背包里那一件凭空没了，玩家看到的就是"被吞掉"。现在从**同一格**取出、扣耐久、
     * 再塞回**同一格**：耐久条正常走，耐久见底才真的少一件（原版口径）。
     */
    private static boolean useFlintAndSteel(EntityMaid maid) {
        if (maid == null) {
            return false;
        }
        try {
            IItemHandler hands = (IItemHandler) maid.getHandsInvWrapper();
            if (damageFlintIn(hands, maid)) {
                return true;
            }
            return damageFlintIn(maid.getMaidInv(), maid);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 在给定容器里找一件打火石：取出 1 件 → 扣 1 点耐久 → 放回原槽（耐久耗尽则不放回） */
    private static boolean damageFlintIn(IItemHandler inv, EntityMaid maid) {
        try {
            for (int i = 0; i < inv.getSlots(); i++) {
                if (!isStack(inv.getStackInSlot(i), ID_FLINT_AND_STEEL)) {
                    continue;
                }
                ItemStack one = inv.extractItem(i, 1, false);
                if (one.m_41619_()) {
                    continue;
                }
                try {
                    // 原版口径：点一次掉 1 点耐久（1.20.1：hurtAndBreak(1, 实体, 损坏回调)）
                    one.m_41622_(1, maid, m -> m.m_21166_(EquipmentSlot.MAINHAND));
                } catch (Throwable ignored) {
                }
                if (!one.m_41619_()) {
                    ItemStack left = inv.insertItem(i, one, false); // 先放回原槽（那一格刚被我们腾出来）
                    if (!left.m_41619_()) {
                        giveBack(maid, left);
                    }
                }
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
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
        final EntityMaid maid;
        /** v1.2.2 实测五百九十二：相位改由服务端 tick 统一驱动 → 自己记住维度与目标 */
        final ServerLevel level;
        final LivingEntity target;
        final long start;
        int step;
        /** 上一步发生的时刻（gameTime）：用来在"放方块"与"挂水晶/充能"之间留可见间隔 */
        long stepAt;
        BlockPos spot;
        /** 这一步正在用的那件东西：副手短暂举起它做动作（{@link BombPose}） */
        ItemStack display;
        final List<BlockPos> placed = new ArrayList<>(2);
        final List<Block> placedBlock = new ArrayList<>(2);

        Phase(Kind kind, EntityMaid maid, ServerLevel level, LivingEntity target, long start) {
            this.kind = kind;
            this.maid = maid;
            this.level = level;
            this.target = target;
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
        /** 起爆那一刻副手要亮一下的那件东西（实测五百九十一："好像真的打了一下水晶/重生锚"） */
        final ItemStack display;

        Bomb(ServerLevel level, EntityMaid maid, Entity entity, BlockPos pos,
             List<BlockPos> placedPos, List<Block> placedBlock, Kind kind, long due, ItemStack display) {
            this.level = level;
            this.maid = maid;
            this.entity = entity;
            this.pos = pos;
            this.placedPos = placedPos;
            this.placedBlock = placedBlock;
            this.kind = kind;
            this.due = due;
            this.display = display;
        }
    }

    /**
     * v1.2.2 实测五百九十【追踪弹】：反馈原文「TNT 再加一个小型追踪功能，TNT 会朝着目标的
     * 方向飞行（只更改方向，速度不变）直到爆炸。」
     *
     * 记住这一发的初速度大小，引信期间每 tick 把速度**方向**朝目标掰一点（大小不变）——
     * 所以它是「拐弯」而不是「加速」。
     *
     * 【实测五百九十一改】只追踪 cfgTntTrackTicks（默认 10 tick = 0.5 秒）、且每 tick 最多转
     * {@link #TRACK_TURN_DEG} 度——反馈"太鬼畜、运动很不自然"就是因为旧版每 tick 把方向**硬掰**
     * 到正指目标；现在是离手后一小段平滑弧线，只为小幅修准，到点即撒手（按当前方向直飞）。
     */
    private static final class Homing {
        final ServerLevel level;
        final PrimedTnt tnt;
        final LivingEntity target;
        /** 初速度大小（格/tick）：每 tick 只改方向，这个值保持不变 */
        final double speed;
        /** 追踪截止时刻（gameTime）：到点就撒手（实测五百九十一：默认离手后 0.5 秒） */
        final long until;

        Homing(ServerLevel level, PrimedTnt tnt, LivingEntity target, double speed, long until) {
            this.level = level;
            this.tnt = tnt;
            this.target = target;
            this.speed = Math.max(0.2, speed);
            this.until = until;
        }
    }

    private static final List<Reclaim> RECLAIMS = new ArrayList<>();

    private static final class Reclaim {
        final ServerLevel level;
        /** 回收进谁的背包（实测五百九十一：底座变成物品还给她；她自己没了则这一块就地消失） */
        final EntityMaid maid;
        final List<BlockPos> pos;
        final List<Block> block;
        final long due;

        Reclaim(ServerLevel level, EntityMaid maid, List<BlockPos> pos, List<Block> block, long due) {
            this.level = level;
            this.maid = maid;
            this.pos = pos;
            this.block = block;
            this.due = due;
        }
    }

    /**
     * 排一次"到期回收"：延迟秒数取自配置（默认 10 秒）。0 = 起爆时立刻回收（旧行为）。
     *
     * v1.2.2 实测五百九十一：回收的**归属**变了——黑曜石/基岩底座不再是"直接抹掉"，
     * 而是变成物品**塞回女仆背包**（{@link #returnBlockItem}）；
     * v1.2.2 实测五百九十三：背包满时改为**掉在她脚下**（掉落物），与挖矿 / 搭路同一口径。
     */
    private static void scheduleReclaim(ServerLevel level, EntityMaid maid, List<BlockPos> pos,
                                       List<Block> block) {
        if (level == null || pos == null || block == null || pos.isEmpty()) {
            return;
        }
        int seconds = Math.max(0, cfgReclaimSeconds());
        if (seconds <= 0) {
            removePlaced(level, pos, block, maid);
            return;
        }
        if (RECLAIMS.size() < MAX_PENDING) {
            RECLAIMS.add(new Reclaim(level, maid, pos, block, level.m_46467_() + seconds * 20L));
        }
    }

    private static final Map<UUID, Phase> PHASE = new HashMap<>();
    private static final Map<UUID, Long> TNT_NEXT = new HashMap<>();
    /** v1.2.2 实测五百九十二：整条轰炸链路的最短间隔下限（女仆 → 下次可起手的 gameTime） */
    private static final Map<UUID, Long> BOMB_NEXT = new HashMap<>();
    private static final List<Bomb> PENDING = new ArrayList<>();
    /** v1.2.2 实测五百九十：攻击链路收尾登记的「待投放」（女仆 UUID → 登记时刻） */
    private static final Map<UUID, Long> TNT_ARMED = new HashMap<>();
    /** 上一次扫描时她是否处于攻击冷却（无 → 有的跳变 = 刚打完一记） */
    private static final Map<UUID, Boolean> COOLDOWN_SEEN = new HashMap<>();
    /** 追踪中的 TNT（引信期间每 tick 把方向掰向目标；只改方向、不改速度） */
    private static final List<Homing> HOMING = new ArrayList<>();
    /** 「待投放」的有效期（tick）：挂这么久还没投出去就作废，免得留一发陈年老弹 */
    private static final long ARMED_TIMEOUT = 200L;
    /** v1.2.2 实测五百九十一：追踪时每 tick 最多转这么多度（限转角 = 平滑弧线，不是瞬间折向） */
    private static final double TRACK_TURN_DEG = 5.0;
    /** 同上（弧度） */
    private static final double TRACK_TURN_RAD = Math.toRadians(TRACK_TURN_DEG);
    /** 动作姿势：副手举起那件东西的时长（tick）——放置/充能/投掷 10（0.5 秒） */
    private static final int BOMB_POSE_TICKS = 10;
    /** 动作姿势：起爆那一记挥臂的时长（tick） */
    private static final int BLAST_POSE_TICKS = 8;
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
        rollbackPhase(maidId);
        TNT_NEXT.remove(maidId);
        BOMB_NEXT.remove(maidId);

        TNT_ARMED.remove(maidId);
        COOLDOWN_SEEN.remove(maidId);
        SKIP_DIAG.remove(maidId);
    }

    public static void clearAll() {
        PHASE.clear();
        TNT_NEXT.clear();
        BOMB_NEXT.clear();
        PENDING.clear();
        RECLAIMS.clear();

        TNT_ARMED.clear();
        COOLDOWN_SEEN.clear();
        HOMING.clear();
        SKIP_DIAG.clear();
        BombPose.clearAll(); // 实测五百九十一：动作姿势的表也一并清（尽量当场把原副手物品还回去）
        combatScanTick = 0;
    }

    /**
     * 硬清某个女仆的相位：能还就把**已放下的方块原物还她**（照 rollback 的口径）。
     * v1.2.2 实测五百九十二：旧版 forget 只 `PHASE.remove`，那一块黑曜石 / 重生锚就白留
     * 在世界里了（空袭行为每次收尾都走 forget，所以这个漏点相当常见）。
     */
    private static void rollbackPhase(UUID maidId) {
        Phase ph = PHASE.remove(maidId);
        if (ph != null && ph.level != null) {
            try {
                rollback(ph.level, ph);
            } catch (Throwable ignored) {
            }
        }
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

            // v1.2.2 实测五百九十：傀儡模式（第三方玩法 Modular Golems 的「傀儡师」）期间
            // 不介入——轰炸也是本模组的战术，那个模式要的是原汁原味的傀儡装配玩法
            if (com.maidsmart.compat.MaidModeCompat.isSuspended(maid)) {
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
            // v1.2.2 实测五百九十二【最短间隔】：轰炸推广到所有攻击模式之后，一次挥砍放一枚会在
            // 几秒内烧光她的黑曜石 / 水晶 / 重生锚——所以整条链路上一个下限（默认 200 tick = 10 秒，
            // 与 TNT 那条"CD 只当下限"同一套思路，面板「轰炸最短间隔」可调）。
            // 缺料那一下**不占用**间隔（下面直接返回，不写 BOMB_NEXT）。
            long now = level.m_46467_();
            if (now < BOMB_NEXT.getOrDefault(id, 0L)) {
                return false;
            }
            Kind kind = pickKind(level, maid);
            if (kind == null) {
                return false; // 材料不齐 / 维度闸关：跳过（需求："判定没有就会直接跳过"；不占间隔）
            }
            PHASE.put(id, new Phase(kind, maid, level, target, now));
            BOMB_NEXT.put(id, now + cfgBombInterval());
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
        // 注：重生锚"必须有 ≥1 级充能才会炸"是原版规则，所以默认要 1 颗萤石当引信；
        // 关掉「重生锚需要萤石」后不检查它（放下即由我们补上那 1 级，见 stepPayload）。
        // ── v1.2.2 实测五百九十二：**萤石要一颗**（反馈："重生锚还是需要一个萤石进行充能的"）──
        // 放置不需要它，但"要它炸"就需要 ≥1 级充能（原版规则：0 级右键不炸）；威力是写死的 5.0F、
        // 与充能等级无关（javap 实证），所以 1 颗就够。维度闸见 explodesHere。
        if (explodesHere(level, Kind.ANCHOR) && has(maid, ID_RESPAWN_ANCHOR)
                && (!cfgAnchorNeedsGlowstone() || has(maid, ID_GLOWSTONE))) {
            return Kind.ANCHOR;
        }
        // ── v1.2.2 实测五百九十四【把原因说到玩家脸上】──
        // 第三次反馈"重生锚还是放不下来"，而实测日志（latest.log 搜「空袭轰炸」）里那一行写得
        // 明明白白：她带着重生锚、**缺萤石**。旧版这条原因只落在日志里（玩家不看日志就等于没有），
        // 而且床能顶上时**连日志都不会有**（diagSkip 只在"一条链路都开不了"时才跑）。
        // 现在不管后面是床顶上、还是整条链都没开成，只要重生锚这一段被跳过就冒气泡说清原因。
        hintAnchorSkip(level, maid);
        // 床同样走维度闸（主世界 = bedWorks 为 true = 不开放）
        if (explodesHere(level, Kind.BED) && hasBed(maid)) {
            return Kind.BED;
        }
        // ── v1.2.2 实测五百九十一【为什么没放】：她带着材料却没起手 → 落一条限频日志
        //（latest.log 搜「轰炸跳过」），把"缺哪一件 / 哪条链路被维度闸关掉"写清楚
        //（反馈："重生锚还是放不了"——旧版这一路是**静默**返回 null，谁都看不出原因）
        diagSkip(level, maid);
        return null;
    }

    /** 缺料 / 维度闸诊断的限频表（女仆 → 上次落日志的 gameTime） */
    private static final Map<UUID, Long> SKIP_DIAG = new HashMap<>();

    /** 维度键（诊断用：告诉玩家"哪条链路在这个维度被闸掉了"） */
    private static String dimKey(ServerLevel level) {
        try {
            return String.valueOf(level.m_46472_().m_135782_());
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** 材料齐但没有可用链路时的限频诊断（每 10 秒最多一条/女仆；她什么都没带就静默） */
    private static void diagSkip(ServerLevel level, EntityMaid maid) {
        try {
            boolean crystal = has(maid, ID_END_CRYSTAL);
            boolean base = has(maid, ID_OBSIDIAN) || has(maid, ID_BEDROCK);
            boolean anchor = has(maid, ID_RESPAWN_ANCHOR);
            boolean bed = hasBed(maid);
            if (!crystal && !anchor && !bed) {
                return; // 她什么都没带：正常跳过，不刷日志
            }
            long now = level.m_46467_();
            Long last = SKIP_DIAG.get(maid.m_20148_());
            if (last != null && now - last < 200L) {
                return;
            }
            SKIP_DIAG.put(maid.m_20148_(), now);
            StringBuilder sb = new StringBuilder();
            if (crystal || base) {
                sb.append("水晶链路缺").append(base ? "末地水晶" : "黑曜石/基岩").append("；");
            }
            if (anchor) {
                sb.append("重生锚：").append(!has(maid, ID_GLOWSTONE) && cfgAnchorNeedsGlowstone()
                        ? "缺萤石；"
                        : (anchorWorks(level) ? "本维度不炸（维度闸关，" + dimKey(level) + "）；" : "可选；"));
            }
            if (bed) {
                sb.append("床：").append(bedWorks(level)
                        ? "本维度不炸（维度闸关，" + dimKey(level) + "）；"
                        : "可选；");
                // v1.2.2 实测五百九十四：维度闸把床拦下（主世界用床）看着最像 bug，冒气泡说清
                if (bedWorks(level)) {
                    hint(level, maid, HINT_BED_DIM);
                }
            }
            // v1.2.2 实测五百九十四：重生锚那一段的原因（缺萤石 / 这个维度不炸）同样说给她主人听
            hintAnchorSkip(level, maid);
            log(com.maidsmart.tool.PromaidLog.nameOf(maid) + " 轰炸跳过（没有可用链路）：" + sb);
        } catch (Throwable ignored) {
        }
    }


    /* ==================== v1.2.2 实测五百九十四：把"为什么没用重生锚"说到玩家脸上 ==================== */

    /** 重生锚没开成的两种说法（同一句话不重复说，见 hint） */
    private static final String HINT_ANCHOR_DIM = "这个维度里重生锚不会炸，我换个办法（维度闸）～";
    private static final String HINT_ANCHOR_GLOW = "主人，给我 1 颗萤石吧——重生锚要充能 1 级才会炸～";
    /** 床没开成的说法（维度闸，主世界） */
    private static final String HINT_BED_DIM = "这个维度里床不会炸，我换个办法（维度闸）～";

    /** 气泡 / 日志的限频（女仆 → 上次时间；女仆 → 上次说的那句） */
    private static final Map<UUID, Long> HINT_CD = new HashMap<>();
    private static final Map<UUID, String> HINT_LAST = new HashMap<>();

    /** 同一句话至少隔这么久才再说一次（tick，60 秒；气泡本身另有 5 秒全局限频兜底） */
    private static final long HINT_CD_TICKS = 1200L;

    /**
     * v1.2.2 实测五百九十四：重生锚这一段被跳过时，把原因说给她主人听。
     *
     * 实测依据（latest.log，1.21.1 实例）：`轰炸跳过（没有可用链路）：水晶链路缺黑曜石/基岩；
     * 重生锚：缺萤石；`——她确实带着重生锚，只是背包里没有那 1 颗萤石（原版 0 级充能右键不炸）。
     * 三种情况：
     * <ul>
     *   <li>她压根没带重生锚 → 什么都不说（不打扰）；</li>
     *   <li>这个维度原版重生锚不会炸（维度闸把它关了）→ 说清是维度的事、不是缺材料；</li>
     *   <li>缺 1 颗萤石 → 直接告诉主人往她背包里放萤石。</li>
     * </ul>
     * 链路是开着的时候**不说话**（真的起手失败另有日志，见 stepBlock 的"放不下"）。
     */
    private static void hintAnchorSkip(ServerLevel level, EntityMaid maid) {
        if (level == null || maid == null || !has(maid, ID_RESPAWN_ANCHOR)) {
            return;
        }
        if (!explodesHere(level, Kind.ANCHOR)) {
            hint(level, maid, HINT_ANCHOR_DIM);
        } else if (cfgAnchorNeedsGlowstone() && !has(maid, ID_GLOWSTONE)) {
            hint(level, maid, HINT_ANCHOR_GLOW);
        }
    }

    /** 限频说一句话（女仆气泡 + 日志；同一句话 60 秒内不重复） */
    private static void hint(ServerLevel level, EntityMaid maid, String said) {
        if (level == null || maid == null || said == null) {
            return;
        }
        try {
            UUID id = maid.m_20148_();
            long now = level.m_46467_();
            Long last = HINT_CD.get(id);
            if (last != null && now - last < HINT_CD_TICKS && said.equals(HINT_LAST.get(id))) {
                return;
            }
            HINT_CD.put(id, now);
            HINT_LAST.put(id, said);
            maid.getChatBubbleManager().addTextChatBubble(said);
            log(com.maidsmart.tool.PromaidLog.nameOf(maid) + " 轰炸链路没开成：" + said);
        } catch (Throwable ignored) {
        }
    }

    /** 下界=false、其余=true（原版 respawn_anchor_works）：重生锚在下界不炸，机制不生效 */
    private static boolean anchorWorks(ServerLevel level) {
        try {
            return net.minecraft.world.level.block.RespawnAnchorBlock.m_55850_(level);
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * v1.2.2 实测五百九十一【维度闸】：只在这个维度"原版真的会炸"时才开放重生锚 / 床链路。
     *
     * 需求原文："重生锚和床判定收紧，因为其他模组会添加其他的维度。在其他的维度不会爆炸。
     * 必须要检测当前维度，有可以爆炸的代码，才会开放这条链路。"
     *
     * 判据就是**原版 use() 里判断"要不要炸"的那两句**（javap 实证，两树同口径）：
     * <ul>
     *   <li>重生锚：{@link #anchorWorks}（= 维度类型的 respawnAnchorWorks）——**false 才炸**，
     *       下界为 true = 不炸；</li>
     *   <li>床：{@link #bedWorks}（= 维度类型的 bedWorks）——**false 才炸**，主世界为 true = 不炸。</li>
     * </ul>
     * 于是数据包 / 其他模组新增的维度只要把这两条写成"能用"，这两条链路就整段不开放——
     * 不会在"那个维度根本炸不了"的地方硬炸。任何异常一律当作"不能炸"（关链路）。
     * 面板开关「维度闸」关掉 = 回到"只看材料、不看维度"。
     */
    private static boolean explodesHere(ServerLevel level, Kind kind) {
        if (level == null) {
            return false;
        }
        if (!cfgDimensionGuard()) {
            return true;
        }
        try {
            if (kind == Kind.ANCHOR) {
                return !anchorWorks(level);
            }
            if (kind == Kind.BED) {
                return !bedWorks(level);
            }
            return true; // 末地水晶不挑维度：原版哪儿都能放、哪儿都炸
        } catch (Throwable ignored) {
            return false;
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
     * 轰炸相位推进一次。step 0 放方块；step 1 挂水晶 / 萤石充能 → 排好起爆时间并交还链路。
     *
     * v1.2.2 实测五百九十二：它不再由空袭行为调用，而是由 {@link #tickPhases}（服务端 tick）
     * 统一驱动——轰炸因此对所有攻击模式一视同仁（远程空袭除外，见 {@link #tickCombatTnt}）。
     */
    private static boolean tick(Phase ph) {
        if (ph == null || ph.level == null || ph.maid == null || !ph.maid.m_6084_()) {
            return false;
        }
        ServerLevel level = ph.level;
        EntityMaid maid = ph.maid;
        UUID id = maid.m_20148_();
        long gameTime = level.m_46467_();
        if (com.maidsmart.compat.MaidModeCompat.isSuspended(maid)) {
            rollback(level, ph);
            PHASE.remove(id);
            return false;
        }
        try {
            if (ph.target == null || !ph.target.m_6084_() || gameTime - ph.start > PHASE_TIMEOUT) {
                rollback(level, ph);
                PHASE.remove(id);
                return false;
            }
            if (ph.step == 0) {
                if (!stepBlock(level, maid, ph.target, ph)) {
                    PHASE.remove(id);
                    return false; // 放不下：整段放弃，直接走下一个链路
                }
                ph.step = 1;
                ph.stepAt = gameTime;
                return true;
            }
            // v1.2.2 实测五百九十一【看得出间隔】：放下方块与"挂水晶 / 充能"之间留一段可见停顿——
            // 反馈："黑曜石和末地水晶几乎是同时放置的，根本看不出间隔"。默认 10 tick = 0.5 秒。
            if (gameTime - ph.stepAt < cfgPlaceGap()) {
                return true;
            }
            if (!stepPayload(level, maid, ph, gameTime)) {
                rollback(level, ph);
                PHASE.remove(id);
                return false;
            }
            PHASE.remove(id);

            // v1.2.2 实测五百九十【TNT 挂链路最末】：轰炸这一段走完 = 这一次攻击链路收尾，
            // TNT 就挂在最后（有料就扔，缺料静默跳过；最短间隔只当下限，见 flushTnt）
            flushTnt(level, maid, ph.target, id, gameTime);
            return false; // 交还链路：她接着放烟花起飞，0.5 秒后那边起爆
        } catch (Throwable t) {
            log("执行异常：" + t);
            rollback(level, ph);
            PHASE.remove(id);
            return false;
        }
    }

    /** 她这一轮是不是正在轰炸段里（空袭行为用它决定本 tick 让不让位） */
    public static boolean isBombing(EntityMaid maid) {
        try {
            return maid != null && PHASE.containsKey(maid.m_20148_());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * v1.2.2 实测五百九十二【相位的唯一驱动器】：每 tick 推一次所有活着的相位（在实体 tick 之前）。
     *
     * 【为什么要改成全服驱动】旧版相位只由空袭行为在它的 tick 里推——于是"打完一记放炸弹"这件事
     * 只存在于飞行近战。需求要把它推广到所有攻击模式（远程空袭除外），而地面近战 / 弓弩 / 三叉戟
     * / 弹幕 / 枪械 / 第三方战斗任务压根没有"空袭行为"这个东西，所以驱动器必须独立出来。
     * 相位表很小（只有正在放炸弹的那几只女仆），每 tick 遍历无压力。
     */
    private static void tickPhases() {
        if (PHASE.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Phase> e : new ArrayList<>(PHASE.entrySet())) {
            UUID id = e.getKey();
            Phase ph = e.getValue();
            try {
                if (ph == null || ph.level == null || ph.maid == null || !ph.maid.m_6084_()) {
                    PHASE.remove(id);
                    if (ph != null && ph.level != null) {
                        rollback(ph.level, ph); // 她没了：已放下的方块撤掉（不掉落）
                    }
                    continue;
                }
                tick(ph);
            } catch (Throwable t) {
                log("相位推进异常：" + t);
                PHASE.remove(id);
            }
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
        // v1.2.2 实测五百九十一【动作】：放置会把这 1 件消耗掉（place 内部 shrink）→ **先留快照**，
        // 拿它当"她手上正举着的那件东西"做动作（副手短暂亮一下，见 BombPose）
        ItemStack display = stack.m_41777_();
        display.m_41764_(1);
        ph.display = display;
        BlockPos spot = placeOnSupport(level, maid, target, stack);
        if (spot == null) {
            giveBack(maid, stack);
            log(ph.kind.cn + " 放不下（目标脚边 4 邻 / 她正下方 16 格 / 空中强制="
                    + (cfgAirPlace() ? "开" : "关") + " 都没成）→ 本次放弃轰炸");
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
        pose(maid, ph.display, BOMB_POSE_TICKS);
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
            // 原版"拿末地水晶物品放在黑曜石上"就是 setShowBottom(false)（EndCrystalItem 反编译实证），
            // 不是末地柱子上那种带底座的形态——按玩家反馈改成普通放置的样子。
            ec.m_31056_(false);
            if (!level.m_7967_(ec)) {
                log("末地水晶生成失败 → 放弃轰炸");
                return false;
            }
            spawned = ec;
            // 起爆那一刻副手亮的是水晶（"好像真的打了一下末影水晶"）
            ph.display = crystal.m_41777_();
            ph.display.m_41764_(1);
            maid.m_6674_(InteractionHand.MAIN_HAND);
            pose(maid, ph.display, BOMB_POSE_TICKS);
        } else if (ph.kind == Kind.ANCHOR) {
            // ── v1.2.2 实测五百九十二：这一段的动作照需求原话走 ──
            // "攻击→副手换成重生锚，放置重生锚（摆臂）→副手换成萤石，拿一颗萤石充能（摆臂动画）
            //  →继续切换回飞行。0.5 秒后再挥一次手臂。正好对上重生锚自爆"
            // step 0 已经放好重生锚（副手举的是它，见 stepBlock）；这里换萤石、充能、摆臂，
            // 副手这一下亮的是萤石；ph.display 不覆盖，起爆那一刻亮的仍是重生锚。
            ItemStack glow = ItemStack.f_41583_;
            if (cfgAnchorNeedsGlowstone()) {
                glow = takeOne(maid, ID_GLOWSTONE);
                if (glow.m_41619_()) {
                    log("萤石取不到（重生锚要有 1 级充能才会炸）→ 放弃轰炸");
                    return false;
                }
                pose(maid, glow, BOMB_POSE_TICKS); // 副手这时换成的就是萤石
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
                pose(maid, ph.display, BOMB_POSE_TICKS); // 没消耗萤石（开关关掉）→ 亮重生锚
            }
        }
        List<BlockPos> posList = ph.placed.isEmpty() ? null : new ArrayList<>(ph.placed);
        List<Block> blockList = ph.placedBlock.isEmpty() ? null : new ArrayList<>(ph.placedBlock);
        if (PENDING.size() < MAX_PENDING) {
            PENDING.add(new Bomb(level, maid, spawned, base, posList, blockList, ph.kind,
                    gameTime + cfgFuse(), ph.display));
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

    /**
     * 撤掉女仆自己放下的那几块。
     *
     * v1.2.2 实测五百九十一：`returnTo` 非空 = 这几块**变成物品还进她的背包**（实测：
     * "黑曜石的回收是回收到女仆的背包里"）——相位中途失败回滚时传她本人，于是"放不下
     * 又撤回"不会白丢她一件材料；起爆后的回收也走这里（带她）。传 null = 只撤不还
     *（重生锚 / 床：它们由自己那一炸消耗掉，还回去等于白送炸药）。
     */
    private static void rollback(ServerLevel level, Phase ph) {
        removePlaced(level, ph.placed, ph.placedBlock, ph.maid);
        ph.placed.clear();
        ph.placedBlock.clear();
    }

    private static void removePlaced(ServerLevel level, List<BlockPos> posList, List<Block> blockList,
                                     EntityMaid returnTo) {
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
            if (returnTo != null) {
                returnBlockItem(returnTo, want);
            }
        }
    }

    /**
     * v1.2.2 实测五百九十一：把撤下来的一块变成物品**塞回女仆背包**。
     *
     * v1.2.2 实测五百九十三【背包满就落地】：反馈"应该变成掉落物"——塞不进背包的那一份
     * **掉在她脚下**（`spawnAtLocation`），与挖矿 / 搭路的方块回收是**完全同一口径**。
     */
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
                    maid.getMaidInv(), st, false);
            if (!left.m_41619_()) {
                maid.m_5552_(left, 0.5f); // 背包满 → 掉在她脚下（实测五百九十三）
                log("回收的炸弹底座塞不进背包（背包满）→ 掉在她脚下");
            }
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 放置 ==================== */

    /** 空中"往下找落点"的扫描深度（格）——空袭时她一直在飞，落点在正下方 */
    private static final int AIR_SCAN_DROP = 16;

    /**
     * 找一个能放下的落点并放下去（v1.2.2 实测五百八十九重写）：
     *
     * ① **目标脚边**那四个水平邻居（离她最近的优先）——贴脸放，水晶正好贴在怪身侧；
     * ② **她正下方**一路往下扫（最多 {@link #AIR_SCAN_DROP} 格）——这是玩家反馈的那条：
     *    "放重生锚会因为一直在空中飞，导致没地方放……落点位于自己下方可放置方块的区域"；
     * ③ ①② 都要求**实心支撑面**；都没有且开了「空中强制放置」时，就在她正下方取第一格
     *    可替换的位置**直接悬空放下**——原版放置本身就允许悬空（`BlockItem.place` 只看
     *    点击位置能不能被替换），只是玩家手点不到空气，我们用构造出来的放置上下文可以。
     */
    private static BlockPos placeOnSupport(ServerLevel level, EntityMaid maid, LivingEntity target, ItemStack stack) {
        BlockPos maidFeet = maid.m_20183_();
        BlockPos maidHead = maidFeet.m_7494_();
        BlockPos tp = target.m_20183_();
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
        if (!cfgAirPlace()) {
            return null;
        }
        // 空中强制放置（不要求支撑面）
        spot = tryPlaceAll(level, maid, stack, near, maidFeet, maidHead, false);
        if (spot != null) {
            return spot;
        }
        return tryPlaceAll(level, maid, stack, below, maidFeet, maidHead, false);
    }

    /** 按"离女仆的 3D 距离"排序（贴脸放优先） */
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

    /**
     * 依次试放（第一格成功就返回实际落点）。requireSupport=true 时跳过悬空格；
     * false 时对着那格空气本身点（= 悬空强制放置）。
     */
    private static BlockPos tryPlaceAll(ServerLevel level, EntityMaid maid, ItemStack stack, List<BlockPos> cand,
                                       BlockPos maidFeet, BlockPos maidHead, boolean requireSupport) {
        for (BlockPos p : cand) {
            if (p.equals(maidFeet) || p.equals(maidHead)) {
                continue; // 别把自己埋了
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
                        log("原版拒绝了落点，已强制放下（那一格站着实体或形状不合规）");
                        return p;
                    }
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
     * 远程空袭盘旋期间的投掷入口（"女仆会在天上盘旋期间额外发射 tnt"）：调用点在
     * {@code fireRanged} 之后 = 这一次开火打完，于是直接走"攻击链路收尾"（
     * {@link #onAttackChainEnd}）。与下面"所有战斗模式"那条共用同一套最短间隔，不会重复扔。
     */
    public static void tickRangedTnt(ServerLevel level, EntityMaid maid, LivingEntity target, UUID id, long gameTime) {
        try {
            if (level == null || maid == null || target == null || !cfgTnt()) {
                return;
            }
            // v1.2.2 实测五百九十【改挂攻击链路】：本方法由空袭远程链路在 fireRanged
            // **之后**调用 = 「这一次开火打完」，所以直接走链路收尾（最短间隔只当下限）
            onAttackChainEnd(level, maid, target);
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
     * ② 有 TNT + 打火石（缺料静默跳过，不占用间隔）；
     * ③ **打完一记之后才扔**（v1.2.2 实测五百九十改）：攻击冷却记忆由无到有 = 刚完成一次
     *    攻击链路收尾 → 登记待投放；距上次投放不足最短间隔（默认 200 tick = 10 秒）就继续
     *    挂着等下一记——最短间隔只当下限，不再是驱动本身（详见 {@link #onAttackChainEnd}）；
     * ④ 半径内有**合法敌对目标**（优先用她记忆里的攻击目标，不合法再按半径找）；
     * ⑤ 防误伤：目标先过 {@link FriendlyFireGuard#isFriendly}（主人 / 同主女仆 / 友军一律不打），
     *    再过任务自己的 {@code IAttackTask.canAttack}（与 TLM 的索敌口径一致）；
     * ⑥ 女仆自己残血（≤ 阈值）时**连投**数发，横向散开——照女仆生存那套"低血量爆发"思路。
     * ⑦ 傀儡模式（第三方玩法）期间整段不介入。
     * ⑧ v1.2.2 实测五百九十二：这一记打完**先试轰炸起手**（末地水晶 / 重生锚 + 萤石 / 床，
     *    见 {@link #tryStartMelee}）——材料齐就整段交给轰炸相位，相位收尾自己会投 TNT；
     *    远程空袭按任务 UID 排除（她一直飞在天上、本来就放不了，需求原话）。
     *
     * 附：本方法只跑【她自己的战斗链路】——TNT 由 TLM 的攻击行为自己触发（近战挥砍/远程开火
     * 都会写攻击冷却记忆），我们只负责"这一记打完的收尾动作"，不额外替她索敌开火。
     */
    public static void tickCombatTnt(ServerLevel level, EntityMaid maid) {
        try {
            // v1.2.2 实测五百九十二：这条扫描现在同时负责【轰炸起手】与【TNT 投放】，所以只要
            // 两个开关里有一个开着就得跑（各自的部分再各自判开关）
            if (level == null || maid == null || (!cfgTnt() && !cfgMelee())) {
                return;
            }
            // v1.2.2 实测五百九十：傀儡模式（第三方玩法）期间不介入
            if (com.maidsmart.compat.MaidModeCompat.isSuspended(maid)) {
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
            // ① 目标先解出来（轰炸与 TNT 共用同一套目标口径：记忆优先、不合法再按半径找）
            LivingEntity target = null;
            try {
                target = maid.m_6274_().m_21952_(MemoryModuleType.f_26372_).orElse(null);
            } catch (Throwable ignored) {
            }
            if (!legalThrowTarget(maid, target)) {
                target = findThrowTarget(level, maid); // 记忆里的目标不合法（友军/非敌人）再按半径找
            }
            // ② 攻击链路完成检测：攻击冷却记忆【由无到有】= 她刚打完一记（TLM 近战与远程
            //    攻击都会写这个记忆）——轰炸与 TNT 都挂在「打完这一记」后面
            boolean cooling = maid.m_6274_().m_21952_(MemoryModuleType.f_26373_).isPresent();
            Boolean prev = COOLDOWN_SEEN.put(id, cooling);
            if (cooling && (prev == null || !prev)) {
                TNT_ARMED.put(id, gameTime);
                // ③ v1.2.2 实测五百九十二【轰炸推广到所有攻击模式】：这一记打完 → 先试轰炸起手
                //    （黑曜石+末地水晶 / 重生锚+萤石 / 床）。起手成功则整段交给轰炸相位，
                //    相位收尾自己会投 TNT（见 tick 末尾的 flushTnt）——所以这里直接返回。
                //    远程空袭除外：她一直飞在天上、本来就放不了（需求原话）。
                if (cfgMelee() && !com.maidsmart.combat.MaidFlightKit.isRangedTask(maid)
                        && tryStartMelee(level, maid, target)) {
                    return;
                }
            }
            // ④ 剩下的才是 TNT 那一发
            if (!cfgTnt()) {
                return;
            }
            Long armedAt = TNT_ARMED.get(id);
            if (armedAt == null) {
                return;
            }
            if (gameTime - armedAt > ARMED_TIMEOUT) {
                TNT_ARMED.remove(id); // 挂太久：作废
                return;
            }
            if (gameTime < TNT_NEXT.getOrDefault(id, 0L)) {
                return; // 最短间隔（默认 200 tick = 10 秒）没到：挂着，等下一记
            }
            flushTnt(level, maid, target, id, gameTime);
        } catch (Throwable t) {
            log("战斗投掷异常：" + t);
        }
    }

    /* ==================== v1.2.2 实测五百九十：TNT 挂进攻击链路 ==================== */

    /**
     * 【为什么要改】反馈原文：「投掷 TNT 这个功能最好是跟末影水晶一样放在攻击链条的某个部分，
     * 我的建议是适当的正常攻击链路走完之后加到最后。而不是一个固定的 Cd，CD 仅作为最小释放，
     * 间隔 10 秒左右。」
     *
     * 于是把「冷却到点就扔」换成「打完一记之后才扔」：
     * ① 攻击链路收尾时登记一发待投放（{@link #TNT_ARMED}）；
     * ② 投放只在【登记过之后】发生，且距上次投放不足最短间隔（默认 200 tick = 10 秒）就继续
     *    挂着等下一记——最短间隔只当下限，不再是驱动本身；
     * ③ 挂太久（{@link #ARMED_TIMEOUT}）自动作废，免得留一发陈年老弹。
     *
     * 登记点（三种链路，见各自的调用处）：
     * - 近战空袭：猛击命中那一记打完 → 轰炸相位（黑曜石/水晶、重生锚、床）走完 → 链路最末投；
     * - 远程空袭：盘旋期间每次开火之后（{@link #tickRangedTnt}）；
     * - 其余战斗任务：扫描里发现她的攻击冷却记忆【由无到有】= 刚打完一记（<b>共用一个最短
     *   间隔，不会两边各扔一发</b>）。
     */
    public static void onAttackChainEnd(ServerLevel level, EntityMaid maid, LivingEntity target) {
        try {
            if (level == null || maid == null || !cfgTnt()) {
                return;
            }
            if (com.maidsmart.compat.MaidModeCompat.isSuspended(maid)) {
                return; // 傀儡模式（第三方玩法）期间不介入
            }
            UUID id = maid.m_20148_();
            long gameTime = level.m_46467_();
            TNT_ARMED.put(id, gameTime);
            flushTnt(level, maid, target, id, gameTime);
        } catch (Throwable t) {
            log("链路投掷异常：" + t);
        }
    }

    /**
     * 链路末段真正投放：过了最短间隔 + 有料 + 目标合法才扔；扔成功才清「待投放」。
     * 放在这里的好处：缺料那一下不会白等一个间隔（下一记只要料齐了立刻投）。
     */
    private static void flushTnt(ServerLevel level, EntityMaid maid, LivingEntity target, UUID id, long gameTime) {
        try {
            if (level == null || maid == null || !cfgTnt()) {
                return;
            }
            if (com.maidsmart.compat.MaidModeCompat.isSuspended(maid)) {
                return;
            }
            if (!legalThrowTarget(maid, target)) {
                return; // 没目标（或目标不合法）：挂着等下一记
            }
            if (gameTime < TNT_NEXT.getOrDefault(id, 0L)) {
                return; // 最短间隔没到
            }
            if (!has(maid, ID_TNT) || !has(maid, ID_FLINT_AND_STEEL)) {
                return; // 不刚需：缺料直接跳过（不占用间隔）
            }
            if (throwTntAt(level, maid, target, id, gameTime) > 0) {
                TNT_ARMED.remove(id);
            }
        } catch (Throwable t) {
            log("投放异常：" + t);
        }
    }

    /**
     * 追踪弹每 tick 一次（在实体 tick 之前调用，于是这一 tick 的原版物理按「新方向」走）。
     *
     * ── v1.2.2 实测五百九十一：改「限时 + 限转角」的小幅修准 ──
     * 反馈原文："追踪这个功能还是太鬼畜了，tnt 运动很不自然。追踪的时间最好控制在 0.5 秒左右。
     * 0.5 秒之后不再追踪。相当于仅仅是稍微提升一下提升准度。（时间可调）"
     *
     * 旧版每 tick 把速度方向**硬掰**成"正指目标"（还带竖直补偿），于是它一离手就折线乱拐。
     * 现在两条限制：① 只在离手后的 cfgTntTrackTicks（默认 10 tick = 0.5 秒）内修正；
     * ② 每 tick 最多转 {@link #TRACK_TURN_DEG} 度——把"当前方向"朝"目标方向"按角度插值，
     * 再按原来的速度大小放回去：观感是一段平滑的小弧线，而不是瞬间转向。
     * 到点 / 贴脸 / 目标没了 / 炸了 → 摘掉条目（剩下按当前方向直飞），绝不留悬挂引用。
     */
    private static void tickHoming() {
        for (Iterator<Homing> it = HOMING.iterator(); it.hasNext(); ) {
            Homing h = it.next();
            try {
                if (h.tnt == null || h.tnt.m_213877_()) {
                    it.remove(); // 已经炸了 / 被清掉了
                    continue;
                }
                if (h.level.m_46467_() >= h.until) {
                    it.remove(); // v1.2.2 实测五百九十一：追踪时间到 → 撒手，按当前方向直飞
                    continue;
                }
                if (h.target == null || !h.target.m_6084_() || h.target.m_213877_()
                        || h.target.m_9236_() != h.level) {
                    it.remove(); // 目标没了 / 换维度了 → 这一发按当前朝向直飞
                    continue;
                }
                double dx = h.target.m_20185_() - h.tnt.m_20185_();
                double dy = (h.target.m_20186_() + h.target.m_20192_() * 0.5) - h.tnt.m_20186_();
                double dz = h.target.m_20189_() - h.tnt.m_20189_();
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len < 1.0) {
                    continue; // 已经贴脸：交给原版物理自然落点（不再硬掰，免得绕着目标打转）
                }
                double nx = dx / len;
                // 补一点抬升抵消 TNT 每 tick 的 −0.04 重力，方向才是真的指向目标
                double ny = dy / len + 0.04 / h.speed;
                double nz = dz / len;
                double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (nl < 1.0E-6) {
                    continue;
                }
                nx /= nl;
                ny /= nl;
                nz /= nl;
                // 它现在朝着哪：这一 tick 的原速度方向
                Vec3 mv = h.tnt.m_20184_();
                double mx = mv.f_82479_;
                double my = mv.f_82480_;
                double mz = mv.f_82481_;
                double ml = Math.sqrt(mx * mx + my * my + mz * mz);
                if (ml < 1.0E-4) {
                    continue; // 速度没了（卡住了）→ 不插手
                }
                mx /= ml;
                my /= ml;
                mz /= ml;
                // 限转角：夹角在阈值内就直接到位，否则只走这一小步（两向量线性插值后归一化）
                double dot = Math.max(-1.0, Math.min(1.0, mx * nx + my * ny + mz * nz));
                double ang = Math.acos(dot);
                double t = ang <= TRACK_TURN_RAD ? 1.0 : TRACK_TURN_RAD / ang;
                double sx = mx + (nx - mx) * t;
                double sy = my + (ny - my) * t;
                double sz = mz + (nz - mz) * t;
                double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
                if (sl < 1.0E-6) {
                    continue;
                }
                // 只改方向、不改速度大小（需求原话）
                h.tnt.m_20256_(new Vec3(sx / sl * h.speed, sy / sl * h.speed, sz / sl * h.speed));
            } catch (Throwable ignored) {
            }
        }
    }

    /** 该实体能不能当投掷目标（防误伤 + 「她的任务认的敌人」两道，与 findThrowTarget 同口径） */
    private static boolean legalThrowTarget(EntityMaid maid, LivingEntity le) {
        try {
            if (le == null || le == maid || !le.m_6084_()) {
                return false;
            }
            if (FriendlyFireGuard.isFriendly(maid, le)) {
                return false; // 防误伤：主人 / 同主女仆 / 友军不扔
            }
            return maid.getTask() instanceof com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask at
                    && at.canAttack(maid, le); // 只打「她的任务认的敌人」（与 TLM 索敌同口径）
        } catch (Throwable ignored) {
            return false;
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

    /**
     * 水平距离 dh、水平初速 vx0 时飞完全程需要的 tick 数。
     * 水平位移是等比级数之和：x_n = vx0·(1−0.98^n)/0.02 → 反解 n = ln(1 − 0.02·dh/vx0)/ln 0.98。
     * 阻尼决定了水平极限射程 ≈ 49·vx0 格，超出就只能给个上限（落点会偏短，属于物理上够不着）。
     */
    private static double flightTicks(double dh, double vx0) {
        if (dh <= 1.0E-4 || vx0 <= 1.0E-6) {
            return 1.0;
        }
        double base = 1.0 - 0.02 * dh / vx0;
        if (base <= 0.02) {
            return 200.0;
        }
        double n = Math.log(base) / Math.log(0.98);
        return Math.max(1.0, Math.min(200.0, n));
    }

    /** 解竖直初速：让 TNT 飞完 n tick 时正好落在目标高度上（闭式，见 {@link #flightTicks} 的注释） */
    private static double requiredVy(double dh, double dy, double vx0) {
        double n = flightTicks(dh, vx0);
        double a = (1.0 - Math.pow(0.98, n)) / 0.02;
        double b = (n - a) / 0.02;
        double vy = (dy + 0.04 * b) / Math.max(1.0E-6, a) + 0.04;
        return Mth.m_14008_(vy, -1.5, 1.5);
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
    private static int throwTntAt(ServerLevel level, EntityMaid maid, LivingEntity target, UUID id, long gameTime) {
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
            // v1.2.2 实测五百九十一【打火石不再被吞】：从**原槽**取出 → 扣 1 点耐久 → 放回**原槽**
            //（旧版是整件取出、只对取出来的那份扣耐久 = 玩家看到的"直接把打火石吞掉"）
            if (!useFlintAndSteel(maid)) {
                giveBack(maid, tntStack);
                break;
            }
            double sx = maid.m_20185_();
            double sy = maid.m_20186_() + maid.m_20192_() * 0.75;
            double sz = maid.m_20189_();
            double tx = target.m_20185_();
            double ty = target.m_20186_() + target.m_20192_() * 0.5;
            double tz = target.m_20189_();
            // ── v1.2.2 实测五百八十九【准度】──
            // 旧版是"飞行时间 ≈ 水平距离 / 水平速度，再补一点落差"——忽略了 0.98/tick 的水平阻尼
            // （水平射程因此是有限的等比级数），目标一动就偏。现在两处一起改：
            // ① 目标带提前量（按它的速度外推预计飞行时间，迭代两轮）；
            // ② 用反编译实证的 TNT 物理做**闭式解**：每 tick 先 −0.04 重力、再位移、最后整体 ×0.98，
            //    于是水平位移 = vx0·A_n、竖直位移 = (vy0−0.04)·A_n − 0.04·B_n
            //    （A_n=(1−0.98^n)/0.02、B_n=(n−A_n)/0.02）：先由水平距离解出飞行时间 n，再解出 vy0。
            double speed = Math.max(0.2, cfgTntSpeed());
            double aimX = tx;
            double aimZ = tz;
            double aimY = ty;
            Vec3 tv = target.m_20184_();
            for (int it = 0; it < 2; it++) {
                double ddx = aimX - sx;
                double ddz = aimZ - sz;
                double flight = flightTicks(Math.sqrt(ddx * ddx + ddz * ddz), speed);
                aimX = tx + tv.f_82479_ * flight;
                aimZ = tz + tv.f_82481_ * flight;
                aimY = ty + tv.f_82480_ * flight * 0.5;
            }
            double dx = aimX - sx;
            double dy = aimY - sy;
            double dz = aimZ - sz;
            double dh = Math.sqrt(dx * dx + dz * dz);
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
            double vy = requiredVy(dh, dy, speed);
            PrimedTnt tnt = new PrimedTnt(level, sx, sy, sz, maid);
            tnt.m_32085_(cfgTntFuse());
            tnt.m_20256_(new Vec3(vx, vy, vz));
            if (!level.m_7967_(tnt)) {
                giveBack(maid, tntStack);
                break;
            }

            // v1.2.2 实测五百九十【追踪】/ 实测五百九十一【限时】：登记这一发——只在离手后的
            // cfgTntTrackTicks（默认 10 tick = 0.5 秒）内朝目标修正方向，之后按当时方向直飞
            if (cfgTntTrack() && cfgTntTrackTicks() > 0 && HOMING.size() < MAX_PENDING) {
                HOMING.add(new Homing(level, tnt, target, Math.sqrt(vx * vx + vy * vy + vz * vz),
                        gameTime + cfgTntTrackTicks()));
            }
            SoundEvent snd = sound(ID_TNT_PRIMED_SOUND);
            if (snd != null) {
                level.m_5594_(null, maid.m_20183_(), snd, SoundSource.BLOCKS, 1.0f, 1.0f);
            }
            maid.m_6674_(InteractionHand.MAIN_HAND);
            // 投掷那一记的动作：副手举的是**打火石**（实测五百九十四）——旧版亮的是刚扔出去的
            // 那枚 TNT，反馈要的是"点火的那只手"：扔 TNT 的时候副手切换成打火石。
            ItemStack flint = flintDisplay();
            pose(maid, flint.m_41619_() ? tntStack : flint, BOMB_POSE_TICKS);
            if (PENDING.size() < MAX_PENDING) {
                PENDING.add(new Bomb(level, maid, tnt, tnt.m_20183_(), null, null,
                        Kind.TNT, gameTime + cfgTntFuse() + BOMB_TIMEOUT / 2, tntStack));
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
        return thrown;
    }

    /* ==================== 起爆 ==================== */

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.START) {
            return; // START = 实体 tick 之前：原版 TNT 那一炸永远轮不到
        }
        try {
            // v1.2.2 实测五百八十九：到期回收（起爆后保留 10 秒的黑曜石/重生锚/床）
            // v1.2.2 实测五百九十：追踪弹每 tick 修正一次朝向（在实体 tick 之前）
            tickHoming();
            // v1.2.2 实测五百九十二：轰炸相位也由服务端统一驱动（所有攻击模式共用同一台机器）
            tickPhases();
            for (Iterator<Reclaim> ri = RECLAIMS.iterator(); ri.hasNext(); ) {
                Reclaim r = ri.next();
                if (r.level == null) {
                    ri.remove();
                    continue;
                }
                long now = r.level.m_46467_();
                if (now < r.due) {
                    if (now > r.due + BOMB_TIMEOUT) {
                        ri.remove();
                    }
                    continue;
                }
                ri.remove();
                removePlaced(r.level, r.pos, r.block, r.maid);
            }
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
        if ((++combatScanTick % 2) == 0) {
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
        // ① v1.2.2 实测五百八十九【保留黑曜石】：起爆这一刻**不再立刻撤掉**水晶底座——
        //    黑曜石留在原地（水晶就是放在它上面的那副样子，爆炸不破坏方块时尤其明显），
        //    过 cfgReclaimSeconds() 秒（默认 10）再由我们回收。配置填 0 就起爆即回收。
        // ② v1.2.2 实测五百九十一【回收口径按种类分】：
        //    · 水晶链路：底座（黑曜石/基岩）**留着 → 到期回收到她背包**（绝不落地）；
        //    · 重生锚 / 床：它们**自己那一炸就把方块消耗掉了**（javap 实证：原版 use() 里先
        //      `removeBlock(pos, false)` 再 explode）——所以我们起爆这一刻直接撤掉、不进回收表、
        //      也不回背包（回背包 = 放一次白拿一个重生锚，那是白送炸药）。
        if (b.kind == Kind.CRYSTAL) {
            scheduleReclaim(level, maid, b.placedPos, b.placedBlock);
        } else if (b.placedPos != null) {
            removePlaced(level, b.placedPos, b.placedBlock, null);
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
                pose(maid, b.display, BLAST_POSE_TICKS);
            }
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
        // v1.2.2 实测五百九十三【特效对齐原版】：反馈"爆炸的特效太小了，比正常生成的末地水晶和
        // TNT 要小很多"。javap 实证原因就在这个模式上——原版 {@code Explosion.finalizeExplosion}
        // 选粒子是"半径 ≥ 2 且 interactsWithBlocks()"才用大粒子 EXPLOSION_EMITTER，否则用小的
        // EXPLOSION；而 ExplosionInteraction.NONE 的 interactsWithBlocks() **恒为 false**
        //（我们默认不破坏方块），于是永远走小粒子分支。修法：不破坏方块时**自己补发一枚大粒子**
        //（服务端广播给附近玩家）；只补视觉，伤害 / 击退 / 地形一概不动。
        if (!cfgBreakBlocks()) {
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
