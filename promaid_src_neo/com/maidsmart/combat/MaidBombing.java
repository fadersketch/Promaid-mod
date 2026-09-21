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
 * v1.2.2 实测六百【三段并行】：旧版是"按 ①②③ 取第一个材料齐的"，于是**只要她带着末地水晶，
 * 重生锚和床就永远轮不到**（反馈原文："在女仆同时可以放 TNT 和末影水晶时，重生锚和床的链路会被
 * 吞掉。也就是说此时女仆只会扔 TNT 和放末影水晶，压根不会去放重生锚。我认为它的优先级应和放末影
 * 水晶是一样的。两者可以同时启动。"）。现在**每一段各有一个相位、各有一条最短间隔**，
 * 同一次攻击里材料齐的几段同时起手（最多三段），落点互不抢（{@code claimedByOtherPhase}）；
 * 三段**放置走的是完全同一套代码**（{@link #placeOnSupport}：原版 place → 落点兜底强制放下 →
 * 空中强制，{@link #stepBlock} 只是取的那件东西不同）——"照搬放末影水晶黑曜石那套逻辑"
 * 就是这么落地的。
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
 *    所有战斗任务都在"攻击冷却记忆由无到有"= 刚打完一记那一刻试起手（{@link #tickCombatTnt}）。
 *    （实测五百九十二当时把远程空袭按任务 UID 排除了——「她一直飞在天上，本来就放不了」；
 *    实测六百〇三 按新需求放开，见下。）
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
 * ── 实测六百〇二【轰炸 = 附加链路，不占用原链路】──
 * 反馈原文："目前我发现这套链路可能因为动作占用而会影响起飞，导致反而拖累了战斗。
 * 所以整个轰炸链路最终应该是被判定为一个额外附加链路，不影响原链路的行动和飞行。"
 *
 * 旧版这里确实"占人"：{@code MaidFlightCombatBehavior.tick} 开头有一道
 * {@code if (MaidBombing.isBombing(maid)) return;}——相位在飞的那十几 tick 里飞行行为整段让位。
 * 而起手时机正好是"收翅猛击打完、马上要再起飞"的那一刻，于是**起飞被压后 0.5~1 秒**。
 * 现在那道闸**整个删掉**：本类只负责"在她攻击链路末尾挂一份额外的礼物"，
 * 相位推进由服务端 tick 驱动（{@link #tickPhases}），放方块 / 挂水晶 / 充能都不需要她停手
 * （落点是目标脚边或她正下方，她飞着也照放），飞行、扑击、开火、起飞一概不受影响。
 * {@link #isBombing(EntityMaid)} 保留下来只作**查询/日志**用（不再是让位判据）。
 *
 * ── 实测六百〇三【远程空袭也放炸弹 + 点火料多个烈焰弹】──
 * 反馈原文："现在将重生锚之类的放置也加入到远程空袭，同时走后门让它在空中也可以放置。"
 *             "女仆包内有烈焰弹的时候也可以触发投掷 tnt 效果是消耗一个烈焰弹。优先用打火石。"
 *
 * ① **远程空袭加入放置链路**：旧版 {@link #tickCombatTnt} 里有一道
 *    {@code !MaidFlightKit.isRangedTask(maid)}——那是 实测五百九十二 按当时的理解（"她一直飞在
 *    天上，本来就放不了"）加的闸。现在整个放开：远程空袭盘旋期间**每次开火之后**先试
 *    {@link #tryStartMelee}（材料齐就起相位，相位收尾自己投 TNT），没起手才就地投 TNT
 *    （{@link #tickRangedTnt}，与近战猛击那条路完全对称）。
 * ② **走后门：悬空也放得下**（{@link #tryPlaceAirDrop}，面板「空中悬空投弹」默认开）：
 *    前三级落点都要求"那一格没被别人占着 + 原版肯收"，而目标**悬空**（蝙蝠 / 恶魂 / 半空中的怪）
 *    时它脚边那四格全是空气、原版又以"那一格站着实体"为由拒绝 → 整段放弃。这最后一级把落点
 *    直接取在**目标头顶那一格 → 目标自己那一格**上，不要求支撑面、也不要求那一格没站着它
 *    （只避开"第三方生物"，免得把路过的别人埋进去）——于是炸弹可以是一座**悬在空中的底座**，
 *    正好贴在目标身上。
 * ③ **投掷 TNT 的点火料多了烈焰弹**（{@link #useIgniter}）：打火石**优先**（就地扣 1 点耐久，
 *    照 实测五百九十一 的口径），没有打火石时消耗 **1 个烈焰弹 / 火焰弹**
 *    （{@code minecraft:fire_charge}），两者都没有才跳过——材料判定因此从"TNT + 打火石"
 *    放宽成"TNT + 任意一种点火料"（{@link #hasIgniter}）。
 *
 * ── 实测六百〇四【TNT 的边界放宽到"注册名里带 tnt"】──
 * 反馈原文："为 tnt 放宽界线，模组内含有 tnt 词条的都可被视作 tnt。"
 *
 * 旧版这一段的判据是写死的 {@link #ID_TNT}（{@code minecraft:tnt}）——别的模组自己加的 TNT
 * （{@code tntmod:tnt} / {@code xxx:tnt_block} / {@code yyy:super_tnt} …）她一概看不见，
 * 玩家把那种 TNT 塞进背包也触发不了投掷。现在判据换成 {@link #isTnt}：**注册名里出现 tnt
 * 就算**（大小写不敏感）——原版那一件天然还在里面，各模组的 TNT 一视同仁；判断处只有两处
 * （{@link #flushTnt} 与 {@link #throwTntAt}），都走 {@link #hasTnt} / {@link #takeOneTnt}。
 * 顺带把日志说清：那一行会写明**这一发到底扔的是哪一件**
 * （{@code 投掷 TNT ×1（modid:tnt，引信 40 tick）}），模组 TNT 生效与否不用再猜。
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
@net.neoforged.fml.common.EventBusSubscriber(modid = "promaid")
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
        // v1.2.2 实测五百九十七：开关判定收进 BombPose.showGated（全模组同一条开关）
        BombPose.showGated(maid, display, ticks);
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
            return it == null ? ItemStack.EMPTY : new ItemStack(it);
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }

    private static boolean cfgAirPlace() {
        return MaidSmartConfig.COMBAT_BOMBING_AIR_PLACE.get();
    }

    /**
     * v1.2.2 实测六百〇三【走后门：悬空也放得下】（默认开）——落点的最后一级。
     *
     * 需求原文："同时走后门让它在空中也可以放置。"前三级（目标脚边四邻 / 她正下方 / 悬空）
     * 都有一个共同前提：那一格**没被别人占着**、原版也肯收。目标悬空时（蝙蝠 / 恶魂 /
     * 半空中的怪）它脚边那四格全是空气、原版又以"那一格站着实体"为由拒绝 → 整段放弃。
     * 这一级直接把落点取在**目标头顶 / 目标自己那一格**上（见 {@link #tryPlaceAirDrop}）。
     */
    private static boolean cfgAirDrop() {
        return MaidSmartConfig.COMBAT_BOMBING_AIR_DROP.get();
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
    /** 原版 TNT：v1.2.2 实测六百〇四起判据放宽为"注册名里带 tnt 的都算"（见 {@link #isTnt}），这个常量只代表那一件本身 */
    private static final String ID_TNT = "minecraft:tnt";
    private static final String ID_FLINT_AND_STEEL = "minecraft:flint_and_steel";
    /** v1.2.2 实测六百〇三：烈焰弹 / 火焰弹——打火石之后的第二号点火料（原版口径也是靠它点着 TNT 的） */
    private static final String ID_FIRE_CHARGE = "minecraft:fire_charge";
    private static final String ID_TNT_PRIMED_SOUND = "minecraft:entity.tnt.primed";

    private static final Map<String, Item> ITEM_CACHE = new HashMap<>();

    private static Item item(String id) {
        Item cached = ITEM_CACHE.get(id);
        if (cached != null) {
            return cached;
        }
        try {
            Item it = BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(id));
            if (it != null) {
                ITEM_CACHE.put(id, it);
            }
            return it;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isStack(ItemStack stack, String id) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Item want = item(id);
        return want != null && stack.is(want);
    }

    private static boolean matchAny(ItemStack stack, String... ids) {
        for (String id : ids) {
            if (isStack(stack, id)) {
                return true;
            }
        }
        return false;
    }

    /** 物品的注册名（拿不到就空串）——v1.2.2 实测六百〇四：TNT 判据与投掷日志都靠它 */
    private static String idOf(ItemStack stack) {
        try {
            net.minecraft.resources.ResourceLocation rl = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return rl == null ? "" : rl.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 是不是任意一张床（原版 16 色都算，不写死颜色） */
    private static boolean isBed(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) {
                return false;
            }
            if (!(stack.getItem() instanceof BlockItem bi)) {
                return false;
            }
            return bi.getBlock() instanceof BedBlock;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * v1.2.2 实测六百〇四【TNT 的边界放宽】：**注册名里带 tnt 的都算 TNT**。
     *
     * 需求原文："为 tnt 放宽界线，模组内含有 tnt 词条的都可被视作 tnt。"——旧版只认
     * {@link #ID_TNT} 这一件，模组自加的 TNT 全被漏掉。现在判据是"注册名（{@code namespace:path}）
     * 里出现 {@code tnt}"，大小写不敏感（有的模组把 path 写成大写）。
     *
     * 【为什么不看显示名 / 中文名】那是本地化文本（中文客户端里它根本不叫 tnt），按它判会随
     * 语言变、还会误伤名字里恰好带 tnt 的别的物品；注册名才是稳定的那份身份。
     * 【边界】判据只看名字、不看"这一件能不能放"——所以像 {@code minecraft:tnt_minecart}
     * 这种名字里带 tnt 的也算数（她扔出去的本来就是一枚原版引信 TNT，与手上那件的样子无关）。
     */
    private static boolean isTnt(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (isStack(stack, ID_TNT)) {
            return true; // 原版那一件：注册表查询万一失手也照认
        }
        return idOf(stack).toLowerCase(java.util.Locale.ROOT).contains("tnt");
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
        return maid != null && !takeFirstMatch(maid, MaidBombing::isBed, true).isEmpty();
    }

    /** TNT：v1.2.2 实测六百〇四起判据放宽（注册名里带 tnt 的都算，见 {@link #isTnt}） */
    private static boolean hasTnt(EntityMaid maid) {
        return !takeFirstMatch(maid, MaidBombing::isTnt, true).isEmpty();
    }

    /** 取 1 个（手 → 背包）；取不到返回空。dryRun=true 时只探测不抽取（内部用） */
    private static ItemStack takeOne(EntityMaid maid, String... ids) {
        return takeFirst(maid, ids, false);
    }

    private static ItemStack takeOneBed(EntityMaid maid) {
        return takeFirst(maid, null, false);
    }

    /** 取 1 个 TNT（同样是放宽后的判据）——v1.2.2 实测六百〇四 */
    private static ItemStack takeOneTnt(EntityMaid maid) {
        return takeFirstMatch(maid, MaidBombing::isTnt, false);
    }

    /** 按注册名取：ids == null 表示"任意床"（照旧的特例），否则按注册名逐个匹配 */
    private static ItemStack takeFirst(EntityMaid maid, String[] ids, boolean dryRun) {
        return takeFirstMatch(maid, ids == null ? MaidBombing::isBed : s -> matchAny(s, ids), dryRun);
    }

    /**
     * 唯一的取料实现（v1.2.2 实测六百〇四改成**按判据取**）：手上 → 背包里第一个满足 match
     * 的那一件。dryRun=true 只回答"有没有"（hasBed / hasTnt 用），一个物品都不动。
     *
     * 【为什么改收判据】旧版收的是注册名数组，再外挂一个"ids == null 表示任意床"的特例；
     * TNT 那条放宽规则（"注册名里带 tnt"，不是一个固定 id）用注册名数组表达不出来，
     * 所以改成收 {@link java.util.function.Predicate}——床 / 固定注册名 / TNT 各自把判据传进来。
     */
    private static ItemStack takeFirstMatch(EntityMaid maid,
                                            java.util.function.Predicate<ItemStack> match, boolean dryRun) {
        if (maid == null) {
            return ItemStack.EMPTY;
        }
        try {
            IItemHandler hands = (IItemHandler) maid.getHandsInvWrapper();
            for (int i = 0; i < hands.getSlots(); i++) {
                ItemStack s = hands.getStackInSlot(i);
                if (match.test(s)) {
                    return dryRun ? s : hands.extractItem(i, 1, false);
                }
            }
            IItemHandler inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (match.test(s)) {
                    return dryRun ? s : inv.extractItem(i, 1, false);
                }
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.EMPTY;
    }

    /**
     * 用不掉就还回去（背包满则丢在脚下）。
     *
     * v1.2.2 实测五百九十五【不再吞装备】：旧版是"找第一个空格 → `insertItem` →
     * 不看返回值直接 return"——那一格被规则拒收（TLM 女仆背包的禁放规则
     * `MaidBackpackHandler.isItemValid`、模组背包的槽位限制）或那一栈放不下时，
     * `insertItem` 会把**整栈原样退回来**，旧版拿到就丢 = 物品凭空消失。
     * 现在统一走 {@link com.maidsmart.tool.MaidGiveBack}：堆叠插入，塞不下的落地。
     */
    private static void giveBack(EntityMaid maid, ItemStack stack) {
        com.maidsmart.tool.MaidGiveBack.give(maid, stack, "轰炸用不掉的材料");
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
                if (one.isEmpty()) {
                    continue;
                }
                try {
                    // 原版口径：点一次掉 1 点耐久（1.21.1：hurtAndBreak(1, 实体, 装备槽)）
                    one.hurtAndBreak(1, maid, EquipmentSlot.MAINHAND);
                } catch (Throwable ignored) {
                }
                if (!one.isEmpty()) {
                    ItemStack left = inv.insertItem(i, one, false); // 先放回原槽（那一格刚被我们腾出来）
                    if (!left.isEmpty()) {
                        giveBack(maid, left);
                    }
                }
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * v1.2.2 实测六百〇三【点火料：打火石 或 烈焰弹】。
     *
     * 需求原文："女仆包内有烈焰弹的时候也可以触发投掷 tnt 效果是消耗一个烈焰弹。优先用打火石。"
     * ——打火石**优先**（按原版点一次掉 1 点耐久，见 {@link #useFlintAndSteel}）；
     * 没有打火石时消耗 **1 个烈焰弹 / 火焰弹**（{@code minecraft:fire_charge}）。
     * 两者都没有 → 这一发跳过（不记间隔）。
     */
    private static boolean hasIgniter(EntityMaid maid) {
        return has(maid, ID_FLINT_AND_STEEL) || has(maid, ID_FIRE_CHARGE);
    }

    /**
     * 用掉一份点火料，返回"刚用掉的那一件"（给 {@link BombPose} 做动作：副手亮一下）；
     * 空 = 一件都没有（这一发别投）。
     *
     * ① 打火石优先：就地扣 1 点耐久（不消耗整件），返回它的模型快照；
     * ② 没有打火石：从背包里**取出 1 个烈焰弹**（真的消耗掉，照需求原话"效果是消耗一个烈焰弹"），
     *    返回的正是这一件——动作里亮的也就是它。
     */
    private static ItemStack useIgniter(EntityMaid maid) {
        if (maid == null) {
            return ItemStack.EMPTY;
        }
        try {
            if (useFlintAndSteel(maid)) {
                return flintDisplay();
            }
            return takeOne(maid, ID_FIRE_CHARGE);
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }

    private static SoundEvent sound(String id) {
        try {
            return BuiltInRegistries.SOUND_EVENT.get(net.minecraft.resources.ResourceLocation.parse(id));
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
        /**
         * v1.2.2 实测五百九十九【目标死了也把这一轮走完】：她这一轮要"贴在哪一格"放炸弹。
         *
         * 目标还活着时每拍刷新；目标一死就冻结在它最后站的那一格——收翅猛击常常就是致命一击，
         * 旧版一见 {@code !target.isAlive()} 就整段回滚，于是近战空袭**从来看不到她放重生锚**
         *（实测：`tryStartMelee=true` → 目标死 → 下一拍 `isBombing=false`、附近重生锚 0）。
         */
        BlockPos targetPos;
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
            this.targetPos = target == null ? null : target.blockPosition();
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
            RECLAIMS.add(new Reclaim(level, maid, pos, block, level.getGameTime() + seconds * 20L));
        }
    }

    /**
     * 正在推进的轰炸相位：**女仆 → (链路 → 相位)**。
     *
     * v1.2.2 实测六百【三段并行】：旧版是「女仆 → 单个相位」，于是 {@code pickKind} 按
     * 水晶 → 重生锚 → 床 取第一个材料齐的——**只要她带着末地水晶，重生锚和床就永远轮不到**
     *（反馈原文："在女仆同时可以放 TNT 和末影水晶时，重生锚和床的链路会被吞掉。也就是说此时
     * 女仆只会扔 TNT 和放末影水晶，压根不会去放重生锚。我认为它的优先级应和放末影水晶是一样的。
     * 两者可以同时启动。"）。现在一条链路一个相位、各走各的步骤与间隔，
     * 同一次攻击里**材料齐的几段可以同时起手**（最多三段）。
     */
    private static final Map<UUID, EnumMap<Kind, Phase>> PHASE = new HashMap<>();
    private static final Map<UUID, Long> TNT_NEXT = new HashMap<>();
    /**
     * v1.2.2 实测五百九十二：整条轰炸链路的最短间隔下限（女仆 → 链路 → 下次可起手的 gameTime）。
     *
     * 实测六百：从"每只女仆一条"细化成"**每只女仆、每条链路各一条**"——三段并行之后，
     * 若还共用一条下限，先起手的把下限顶掉就等于又把其它两段吞了（正好是要修的那个毛病）。
     */
    private static final Map<UUID, EnumMap<Kind, Long>> BOMB_NEXT = new HashMap<>();
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
            return caster != null && caster.equals(victim.getUUID());
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
        HINT_CD.clear(); // 实测五百九十四：气泡限频表（forget 不清它，见字段注释）
        HINT_LAST.clear();
        BombPose.clearAll(); // 实测五百九十一：动作姿势的表也一并清（尽量当场把原副手物品还回去）
        combatScanTick = 0;
    }

    /**
     * 硬清某个女仆的相位：能还就把**已放下的方块原物还她**（照 rollback 的口径）。
     * v1.2.2 实测五百九十二：旧版 forget 只 `PHASE.remove`，那一块黑曜石 / 重生锚就白留
     * 在世界里了（空袭行为每次收尾都走 forget，所以这个漏点相当常见）。
     * v1.2.2 实测六百：三段并行后要**逐段**回滚（每段各自放过的东西各自还）。
     */
    private static void rollbackPhase(UUID maidId) {
        EnumMap<Kind, Phase> map = PHASE.remove(maidId);
        if (map == null) {
            return;
        }
        for (Phase ph : map.values()) {
            if (ph != null && ph.level != null) {
                try {
                    rollback(ph.level, ph);
                } catch (Throwable ignored) {
                }
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
     *
     * v1.2.2 实测六百【三段并行】：不再是"取第一个材料齐的链路"，而是**材料齐的每一段各自
     * 起一个相位**（水晶 / 重生锚 / 床 最多三段同时在飞）——需求原文："我认为它的优先级应和
     * 放末影水晶是一样的。两者可以同时启动。"
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
            UUID id = maid.getUUID();
            long now = level.getGameTime();
            EnumMap<Kind, Phase> running = PHASE.get(id);
            if (running != null) {
                // 陈旧相位（超时）先丢掉：它自己那一轮已经不成立了
                for (Iterator<Phase> it = running.values().iterator(); it.hasNext(); ) {
                    Phase ph = it.next();
                    if (now - ph.start >= PHASE_TIMEOUT) {
                        it.remove();
                    }
                }
                if (running.isEmpty()) {
                    PHASE.remove(id);
                }
            }
            // v1.2.2 实测五百九十二【最短间隔】+ 实测六百【按段各算】：一次挥砍放一枚会在几秒内
            // 烧光她的材料，所以每段各有一条下限（默认 200 tick = 10 秒，面板「轰炸最短间隔」可调）。
            // 缺料那一下**不占用**间隔（没起手的段不写 BOMB_NEXT）。
            List<Kind> ready = readyKinds(level, maid, id, now, running);
            if (ready.isEmpty()) {
                return false; // 一段都起不来：跳过（需求："判定没有就会直接跳过"）
            }
            EnumMap<Kind, Long> next = BOMB_NEXT.computeIfAbsent(id, k -> new EnumMap<>(Kind.class));
            boolean started = false;
            for (Kind kind : ready) {
                if (running != null && running.containsKey(kind)) {
                    continue; // 这一段还在飞，不重复起手
                }
                PHASE.computeIfAbsent(id, k -> new EnumMap<>(Kind.class))
                        .put(kind, new Phase(kind, maid, level, target, now));
                next.put(kind, now + cfgBombInterval());
                started = true;
            }
            return started;
        } catch (Throwable t) {
            log("起手异常：" + t);
            return false;
        }
    }

    /**
     * 这一步可以起手的链路（顺序：水晶 → 重生锚 → 床；维度不合适的跳过那一段），
     * 并顺手把"没开成的原因"记进日志（重生锚那一段仍然照 实测五百九十四 的口径）。
     *
     * v1.2.2 实测六百：从"返回第一个"改成"返回全部可以起的"——{@code running} 里已经在飞的
     * 那几段由调用方排除，所以这里只判"材料 / 维度 / 间隔"。
     */
    private static List<Kind> readyKinds(ServerLevel level, EntityMaid maid, UUID id, long now,
                                         EnumMap<Kind, Phase> running) {
        List<Kind> out = new ArrayList<>(3);
        EnumMap<Kind, Long> next = BOMB_NEXT.get(id);
        // ① 末地水晶（黑曜石 / 基岩底座）
        if (has(maid, ID_END_CRYSTAL) && has(maid, ID_OBSIDIAN, ID_BEDROCK)
                && !onCooldown(next, Kind.CRYSTAL, now)) {
            out.add(Kind.CRYSTAL);
        }
        // ② 重生锚（需要 1 颗萤石当引信；维度闸见 explodesHere）
        boolean anchorReady = explodesHere(level, Kind.ANCHOR) && has(maid, ID_RESPAWN_ANCHOR)
                && (!cfgAnchorNeedsGlowstone() || has(maid, ID_GLOWSTONE));
        if (anchorReady && !onCooldown(next, Kind.ANCHOR, now)) {
            out.add(Kind.ANCHOR);
        } else if (!anchorReady) {
            // ── v1.2.2 实测五百九十四【把原因说到玩家脸上】──
            // 第三次反馈"重生锚还是放不下来"，而实测日志（latest.log 搜「空袭轰炸」）里那一行写得
            // 明明白白：她带着重生锚、**缺萤石**。旧版这条原因只落在日志里（玩家不看日志就等于没有），
            // 而且床能顶上时**连日志都不会有**（diagSkip 只在"一条链路都开不了"时才跑）。
            // 现在不管后面是床顶上、还是整条链都没开成，只要重生锚这一段被跳过就记明原因。
            hintAnchorSkip(level, maid);
        }
        // ③ 床（同样走维度闸：主世界 = bedWorks 为 true = 不开放）
        if (explodesHere(level, Kind.BED) && hasBed(maid) && !onCooldown(next, Kind.BED, now)) {
            out.add(Kind.BED);
        }
        if (out.isEmpty() && (running == null || running.isEmpty())) {
            // ── v1.2.2 实测五百九十一【为什么没放】：她带着材料却没起手 → 落一条限频日志
            //（latest.log 搜「轰炸跳过」），把"缺哪一件 / 哪条链路被维度闸关掉"写清楚
            //（反馈："重生锚还是放不了"——旧版这一路是**静默**返回 null，谁都看不出原因）
            diagSkip(level, maid);
        }
        return out;
    }

    /** 这一段是不是还在最短间隔里（没有记录 = 没起过 = 不冷却） */
    private static boolean onCooldown(EnumMap<Kind, Long> next, Kind kind, long now) {
        return next != null && now < next.getOrDefault(kind, 0L);
    }

    /** 材料齐的第一段链路（顺序：水晶 → 重生锚 → 床；维度不合适的跳过那一段） */
    /** 缺料 / 维度闸诊断的限频表（女仆 → 上次落日志的 gameTime） */
    private static final Map<UUID, Long> SKIP_DIAG = new HashMap<>();

    /** 维度键（诊断用：告诉玩家"哪条链路在这个维度被闸掉了"） */
    private static String dimKey(ServerLevel level) {
        try {
            return String.valueOf(level.dimension().location());
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
            long now = level.getGameTime();
            Long last = SKIP_DIAG.get(maid.getUUID());
            if (last != null && now - last < 200L) {
                return;
            }
            SKIP_DIAG.put(maid.getUUID(), now);
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
                // v1.2.2 实测五百九十四：维度闸把床拦下（主世界用床）看着最像 bug，写下来
                if (bedWorks(level)) {
                    hint(level, maid, HINT_BED_DIM);
                }
            }
            // v1.2.2 实测五百九十四：重生锚那一段的原因（缺萤石 / 这个维度不炸）同样写下来
            hintAnchorSkip(level, maid);
            log(com.maidsmart.tool.PromaidLog.nameOf(maid) + " 轰炸跳过（没有可用链路）：" + sb);
        } catch (Throwable ignored) {
        }
    }


    /* ============ v1.2.2 实测五百九十四起：把"为什么没用重生锚/床"写进日志（五百九十五撤掉气泡） ============ */

    /** 重生锚没开成的两种原因（同一句话不重复记，见 hint） */
    private static final String HINT_ANCHOR_DIM = "本维度重生锚不炸（维度闸）→ 换别的链路";
    private static final String HINT_ANCHOR_GLOW = "缺萤石（重生锚要充能 1 级才会炸）";
    /** 床没开成的原因（维度闸，主世界） */
    private static final String HINT_BED_DIM = "本维度床不炸（维度闸）→ 换别的链路";

    /**
     * 日志的限频（女仆 → 上次时间；女仆 → 上次记的那句）。
     *
     * 注意这两张表**不跟 {@link #forget} 一起清**：空袭行为每次收尾都会调 forget，
     * 一清 60 秒限频就形同虚设（每次轰炸都会再记一遍同一句）。只在 {@link #clearAll} 清。
     */
    private static final Map<UUID, Long> HINT_CD = new HashMap<>();
    private static final Map<UUID, String> HINT_LAST = new HashMap<>();

    /** 同一句话至少隔这么久才再记一次（tick，60 秒） */
    private static final long HINT_CD_TICKS = 1200L;

    /**
     * v1.2.2 实测五百九十四：重生锚这一段被跳过时，把原因写下来（五百九十五起只进日志）。
     *
     * 实测依据（latest.log，1.21.1 实例）：`轰炸跳过（没有可用链路）：水晶链路缺黑曜石/基岩；
     * 重生锚：缺萤石；`——她确实带着重生锚，只是背包里没有那 1 颗萤石（原版 0 级充能右键不炸）。
     * 三种情况：
     * <ul>
     *   <li>她压根没带重生锚 → 什么都不记（不打扰）；</li>
     *   <li>这个维度原版重生锚不会炸（维度闸把它关了）→ 记明是维度的事、不是缺材料；</li>
     *   <li>缺 1 颗萤石 → 记明"往她背包里放 1 颗萤石就好"。</li>
     * </ul>
     * 链路是开着的时候**不记**（真的起手失败另有日志，见 stepBlock 的"放不下"）。
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

    /** 限频记一句（只进日志；同一句话 60 秒内不重复）。
     *  v1.2.2 实测五百九十五【气泡删掉】：这一批（五百九十四）加的气泡按要求撤掉——
     *  原因只写日志（搜「轰炸链路没开成」）。 */
    private static void hint(ServerLevel level, EntityMaid maid, String said) {
        if (level == null || maid == null || said == null) {
            return;
        }
        try {
            UUID id = maid.getUUID();
            long now = level.getGameTime();
            Long last = HINT_CD.get(id);
            if (last != null && now - last < HINT_CD_TICKS && said.equals(HINT_LAST.get(id))) {
                return;
            }
            HINT_CD.put(id, now);
            HINT_LAST.put(id, said);
            log(com.maidsmart.tool.PromaidLog.nameOf(maid) + " 轰炸链路没开成：" + said);
        } catch (Throwable ignored) {
        }
    }

    /** 下界=false、其余=true（原版 respawn_anchor_works）：重生锚在下界不炸，机制不生效 */
    private static boolean anchorWorks(ServerLevel level) {
        try {
            return net.minecraft.world.level.block.RespawnAnchorBlock.canSetSpawn(level);
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
            return net.minecraft.world.level.block.BedBlock.canSetSpawn(level);
        } catch (Throwable ignored) {
            return true;
        }
    }

    /* ==================== 近战轰炸：分步执行 ==================== */

    /**
     * 轰炸相位推进一次。step 0 放方块；step 1 挂水晶 / 萤石充能 → 排好起爆时间并交还链路。
     *
     * v1.2.2 实测五百九十二：它不再由空袭行为调用，而是由 {@link #tickPhases}（服务端 tick）
     * 统一驱动——轰炸因此对所有攻击模式一视同仁（实测六百〇三起**远程空袭也算在内**，
     * 见 {@link #tickRangedTnt}）；旧版那道"远程空袭除外"的闸见 {@link #tickCombatTnt} 的注释。
     *
     * v1.2.2 实测六百：返回 {@link Result}，**不再自己从相位表里摘自己**——三段并行之后
     * 摘除与"这一段走完了要不要投 TNT"都由驱动器（{@link #tickPhases}）统一处理。
     */
    private enum Result {
        /** 还在这一段的中间（等间隔 / 等下一拍） */
        CONTINUE,
        /** 这一段走完了（已经排好起爆） */
        DONE,
        /** 这一段作废（放不下 / 超时 / 她被傀儡模式接管）——已放下的方块撤回她背包 */
        ABORT
    }

    private static Result tick(Phase ph) {
        if (ph == null || ph.level == null || ph.maid == null || !ph.maid.isAlive()) {
            return Result.ABORT;
        }
        ServerLevel level = ph.level;
        EntityMaid maid = ph.maid;
        long gameTime = level.getGameTime();
        if (com.maidsmart.compat.MaidModeCompat.isSuspended(maid)) {
            rollback(level, ph);
            return Result.ABORT;
        }
        try {
            // v1.2.2 实测五百九十九【"近战空袭从不放重生锚"的根因】：旧版这一行目标是"死了/没了"
            // 就整段回滚 —— 而收翅猛击**常常就是致命一击**：起手那一 tick 之后目标马上就死了，
            // 于是相位在 step 0 之前被撤掉，玩家永远看不到她放重生锚（实测日志：
            // `C tryStartMelee=true isBombing=true` → 目标死 → 下一拍 `isBombing=false 附近重生锚=0`）。
            // 现在：目标死了**不中止**，改用"它最后站的那一格"把这一轮走完（炸弹落在原地），
            // 只有"从没拿到过目标位置"或超时才回滚。
            if (ph.targetPos == null || gameTime - ph.start > PHASE_TIMEOUT) {
                rollback(level, ph);
                return Result.ABORT;
            }
            if (ph.target != null && ph.target.isAlive()) {
                ph.targetPos = ph.target.blockPosition(); // 目标活着 → 跟着它走
            }
            if (ph.step == 0) {
                if (!stepBlock(level, maid, ph.targetPos, ph)) {
                    return Result.ABORT; // 放不下：这一段放弃（其它段各走各的，不受影响）
                }
                ph.step = 1;
                ph.stepAt = gameTime;
                return Result.CONTINUE;
            }
            // v1.2.2 实测五百九十一【看得出间隔】：放下方块与"挂水晶 / 充能"之间留一段可见停顿——
            // 反馈："黑曜石和末地水晶几乎是同时放置的，根本看不出间隔"。默认 10 tick = 0.5 秒。
            if (gameTime - ph.stepAt < cfgPlaceGap()) {
                return Result.CONTINUE;
            }
            if (!stepPayload(level, maid, ph, gameTime)) {
                rollback(level, ph);
                return Result.ABORT;
            }
            return Result.DONE; // 交还链路：她接着放烟花起飞，0.5 秒后那边起爆
        } catch (Throwable t) {
            log("执行异常：" + t);
            rollback(level, ph);
            return Result.ABORT;
        }
    }

    /**
     * 她这一轮是不是正在轰炸段里（三段里**任一段**还在飞就算）。
     *
     * v1.2.2 实测六百〇二：**它不再是"让位判据"**——飞行行为已经一眼都不看轰炸状态
     * （轰炸按需求改判为"附加链路"，不影响原链路的行动与飞行）。保留它是为了查询与排查日志
     * （"她现在在不在放炸弹"是一句有用的话）。
     */
    public static boolean isBombing(EntityMaid maid) {
        try {
            if (maid == null) {
                return false;
            }
            EnumMap<Kind, Phase> map = PHASE.get(maid.getUUID());
            return map != null && !map.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * v1.2.2 实测五百九十二【相位的唯一驱动器】：每 tick 推一次所有活着的相位（在实体 tick 之前）。
     *
     * 【为什么要改成全服驱动】旧版相位只由空袭行为在它的 tick 里推——于是"打完一记放炸弹"这件事
     * 只存在于飞行近战。需求要把它推广到所有攻击模式（实测六百〇三起连远程空袭也算，见
     * {@link #tickRangedTnt}），而地面近战 / 弓弩 / 三叉戟
     * / 弹幕 / 枪械 / 第三方战斗任务压根没有"空袭行为"这个东西，所以驱动器必须独立出来。
     * 相位表很小（只有正在放炸弹的那几只女仆），每 tick 遍历无压力。
     *
     * v1.2.2 实测六百：一个女仆可能有多段（水晶 / 重生锚 / 床）在飞——逐段推、逐段摘，
     * **她的最后一段走完那一刻**才投 TNT（照旧"TNT 挂链路最末"，只是链路末端变成了
     * "所有能放的段都放完"）。
     */
    private static void tickPhases() {
        if (PHASE.isEmpty()) {
            return;
        }
        for (UUID id : new ArrayList<>(PHASE.keySet())) {
            EnumMap<Kind, Phase> map = PHASE.get(id);
            if (map == null || map.isEmpty()) {
                PHASE.remove(id);
                continue;
            }
            EntityMaid maid = null;
            LivingEntity target = null;
            for (Phase ph : map.values()) {
                if (ph != null && ph.maid != null) {
                    maid = ph.maid;
                    target = ph.target;
                    break;
                }
            }
            try {
                boolean anyDone = false;
                for (Phase ph : new ArrayList<>(map.values())) {
                    if (ph == null || ph.level == null || ph.maid == null || !ph.maid.isAlive()) {
                        if (ph != null && ph.level != null) {
                            rollback(ph.level, ph); // 她没了：已放下的方块撤掉（不掉落）
                        }
                        if (ph != null) {
                            map.remove(ph.kind);
                        }
                        continue;
                    }
                    Result r = tick(ph);
                    if (r == Result.CONTINUE) {
                        continue;
                    }
                    map.remove(ph.kind);
                    if (r == Result.DONE) {
                        anyDone = true;
                    }
                }
                if (map.isEmpty()) {
                    PHASE.remove(id);
                    if (anyDone && maid != null && maid.isAlive()) {
                        // v1.2.2 实测五百九十【TNT 挂链路最末】：所有段都放完了 = 这一次攻击链路收尾，
                        // TNT 挂在最后（有料就扔，缺料静默跳过；最短间隔只当下限，见 flushTnt）
                        flushTnt(maid.level() instanceof ServerLevel sl ? sl : null, maid, target, id,
                                maid.level().getGameTime());
                    }
                }
            } catch (Throwable t) {
                log("相位推进异常：" + t);
                rollbackPhase(id);
                PHASE.remove(id);
            }
        }
    }

    /** step 0：把黑曜石/重生锚/床放到目标脚边那一格（走原版 BlockItem.place，两格床也由它铺） */
    private static boolean stepBlock(ServerLevel level, EntityMaid maid, BlockPos targetPos, Phase ph) {
        ItemStack stack;
        if (ph.kind == Kind.BED) {
            stack = takeOneBed(maid);
        } else if (ph.kind == Kind.CRYSTAL) {
            stack = takeOne(maid, has(maid, ID_OBSIDIAN) ? ID_OBSIDIAN : ID_BEDROCK);
        } else {
            stack = takeOne(maid, ID_RESPAWN_ANCHOR);
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
            giveBack(maid, stack);
            log(ph.kind.cn + " 放不下（目标脚边 4 邻 / 她正下方 16 格 / 空中强制="
                    + (cfgAirPlace() ? "开" : "关") + " / 后门悬空投弹="
                    + (cfgAirDrop() ? "开" : "关") + " 都没成）→ 本次放弃轰炸");
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
        pose(maid, ph.display, BOMB_POSE_TICKS);
        log(com.maidsmart.tool.PromaidLog.nameOf(maid) + " 放下 " + ph.kind.cn
                + " @" + spot.getX() + "," + spot.getY() + "," + spot.getZ());
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
            if (crystal.isEmpty()) {
                log("末地水晶取不到 → 放弃轰炸");
                return false;
            }
            EndCrystal ec = new EndCrystal(level, base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
            // 原版"拿末地水晶物品放在黑曜石上"就是 setShowBottom(false)（EndCrystalItem 反编译实证），
            // 不是末地柱子上那种带底座的形态——按玩家反馈改成普通放置的样子。
            ec.setShowBottom(false);
            if (!level.addFreshEntity(ec)) {
                log("末地水晶生成失败 → 放弃轰炸");
                return false;
            }
            spawned = ec;
            // 起爆那一刻副手亮的是水晶（"好像真的打了一下末影水晶"）
            ph.display = crystal.copy();
            ph.display.setCount(1);
            maid.swing(InteractionHand.MAIN_HAND);
            pose(maid, ph.display, BOMB_POSE_TICKS);
        } else if (ph.kind == Kind.ANCHOR) {
            // ── v1.2.2 实测五百九十二：这一段的动作照需求原话走 ──
            // "攻击→副手换成重生锚，放置重生锚（摆臂）→副手换成萤石，拿一颗萤石充能（摆臂动画）
            //  →继续切换回飞行。0.5 秒后再挥一次手臂。正好对上重生锚自爆"
            // step 0 已经放好重生锚（副手举的是它，见 stepBlock）；这里换萤石、充能、摆臂，
            // 副手这一下亮的是萤石；ph.display 不覆盖，起爆那一刻亮的仍是重生锚。
            ItemStack glow = ItemStack.EMPTY;
            if (cfgAnchorNeedsGlowstone()) {
                glow = takeOne(maid, ID_GLOWSTONE);
                if (glow.isEmpty()) {
                    log("萤石取不到（重生锚要有 1 级充能才会炸）→ 放弃轰炸");
                    return false;
                }
                pose(maid, glow, BOMB_POSE_TICKS); // 副手这时换成的就是萤石
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
                BombMarkNetworking.send(maid, 1, spawned.getId(), null, cfgFuse() + 20);
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
            ItemStack st = new ItemStack(block.asItem());
            if (st.isEmpty()) {
                return; // 没有对应物品（空气之类）→ 不还原
            }
            ItemStack left = net.neoforged.neoforge.items.ItemHandlerHelper.insertItemStacked(
                    maid.getMaidInv(), st, false);
            if (!left.isEmpty()) {
                maid.spawnAtLocation(left, 0.5f); // 背包满 → 掉在她脚下（实测五百九十三）
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
     *
     * v1.2.2 实测六百〇三【走后门：悬空也放得下】：上面三级都失败（典型就是**目标悬空**——
     * 蝙蝠 / 恶魂 / 半空中的怪：它脚边那四格全是空气、原版又以"那一格站着实体"为由拒绝）
     * 时，再走 {@link #tryPlaceAirDrop}——落点直接取在目标头顶 / 目标自己那一格上。
     */
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
        if (cfgAirPlace()) {
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
        return cfgAirDrop() ? tryPlaceAirDrop(level, maid, stack, tp, target, maidFeet, maidHead) : null;
    }

    /**
     * v1.2.2 实测六百〇三【走后门：悬空也放得下】。
     *
     * 需求原文："现在将重生锚之类的放置也加入到远程空袭，同时走后门让它在空中也可以放置。"
     *
     * 【为什么需要它】远程空袭她一直在天上盘旋，目标还常常**自己就悬空**（蝙蝠 / 恶魂 /
     * 被击飞到半空中的怪 / 站在水里的怪）：目标脚边那四个邻居全是空气、她正下方也没有地面时，
     * 前四级要么找不到支撑面、要么被原版以"那一格站着实体"（{@code isUnobstructed}）拒掉，
     * 于是整段放弃——玩家看到的就是"她带着重生锚却从来不放"。
     *
     * 【走后门怎么走】落点直接取**目标头顶那一格 → 目标自己那一格**：不要求支撑面、
     * 也不要求那一格没站着目标自己（原版拒绝之后由 {@code tryPlaceAll} 的兜底强制放下接手，
     * 那一格可替换就 setBlock），于是底座可以**悬在空中**、正好贴在目标身上，0.5 秒后原地开花。
     * 唯一保留的避让是"**第三方生物**"：那一格里若站着除目标以外的任何活物就跳过，
     * 免得把路过的友军 / 主人埋进黑曜石里（`occupiedByThirdParty`）。
     */
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
            log("走后门：目标那一格 / 头顶那一格悬空放下 @" + spot.getX() + ","
                    + spot.getY() + "," + spot.getZ() + "（落点无支撑面也算数）");
        }
        return spot;
    }

    /**
     * 这一格里是不是站着**除 {@code allowed} 以外**的活物（走后门落点的唯一避让，见
     * {@link #tryPlaceAirDrop}）。
     */
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

    /** 按"离女仆的 3D 距离"排序（贴脸放优先） */
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

    /**
     * 这一格是不是被她**另一段**轰炸链路占了（实测六百：三段并行时各占各的落点）。
     *
     * 同一 tick 起手的几段会各自去挑"目标脚边 / 她正下方"的落点，候选表是同一个——
     * 没有这道闸时第二段会挑到第一段刚放下的那一格（原版 place 会拒绝，但拒绝走的是
     * "这一段放弃"那条路，等于把没抢到落点的那段整段吞掉，正是要修的现象）。
     */
    private static boolean claimedByOtherPhase(EntityMaid maid, BlockPos p) {
        try {
            EnumMap<Kind, Phase> map = PHASE.get(maid.getUUID());
            if (map == null || p == null) {
                return false;
            }
            for (Phase ph : map.values()) {
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

    /** 放完之后那一格的方块（用于后面精确撤除） */
    private static Block placedBlockOf(ServerLevel level, BlockPos spot, ItemStack stack) {
        try {
            if (stack.getItem() instanceof BlockItem bi) {
                return bi.getBlock();
            }
        } catch (Throwable ignored) {
        }
        return level.getBlockState(spot).getBlock();
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

    /* ==================== 远程空袭：投掷 TNT ==================== */

    /**
     * 远程空袭盘旋期间的投掷入口（"女仆会在天上盘旋期间额外发射 tnt"）：调用点在
     * {@code fireRanged} 之后 = 这一次开火打完，于是直接走"攻击链路收尾"（
     * {@link #onAttackChainEnd}）。与下面"所有战斗模式"那条共用同一套最短间隔，不会重复扔。
     *
     * v1.2.2 实测六百〇三【远程空袭也放炸弹】：这一步的顺序改成与近战猛击那条路**完全对称**——
     * 需求原文："现在将重生锚之类的放置也加入到远程空袭"。先试 {@link #tryStartMelee}
     * （末地水晶 / 重生锚 + 萤石 / 床；材料齐才起手、放不下就整段跳过），起手成功就交给相位，
     * **TNT 由相位收尾那一步投**（{@link #tickPhases} → {@link #flushTnt}），所以这里直接返回
     * （免得同一记打完既放了炸弹又立刻多扔一发）；没起手才就地投 TNT。
     */
    public static void tickRangedTnt(ServerLevel level, EntityMaid maid, LivingEntity target, UUID id, long gameTime) {
        try {
            if (level == null || maid == null || target == null || (!cfgTnt() && !cfgMelee())) {
                return;
            }
            if (com.maidsmart.compat.MaidModeCompat.isSuspended(maid)) {
                return; // 傀儡模式（第三方玩法）期间不介入
            }
            // v1.2.2 实测五百九十【改挂攻击链路】：本方法由空袭远程链路在 fireRanged
            // **之后**调用 = 「这一次开火打完」，所以直接走链路收尾（最短间隔只当下限）
            if (cfgMelee() && tryStartMelee(level, maid, target)) {
                return; // 起手成功：TNT 归相位收尾（与猛击那条路一致）
            }
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
     * ② 有 TNT + 点火料（打火石 或 烈焰弹，见 {@link #hasIgniter}；缺料静默跳过，不占用间隔）；
     *    TNT 的判据 v1.2.2 实测六百〇四 起放宽成"注册名里带 tnt 的都算"（{@link #isTnt}），
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
     *    v1.2.2 实测六百〇三：**远程空袭也一视同仁**（旧版这里有一道
     *    {@code !MaidFlightKit.isRangedTask} 的闸，是 实测五百九十二 按当时的理解加的；
     *    现在放开，落点悬空那一档由 {@link #tryPlaceAirDrop} 兜住）。
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
            if (!maid.isAlive() || maid.isRemoved()) {
                return;
            }
            UUID id = maid.getUUID();
            long gameTime = level.getGameTime();
            // ① 目标先解出来（轰炸与 TNT 共用同一套目标口径：记忆优先、不合法再按半径找）
            LivingEntity target = null;
            try {
                target = maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
            } catch (Throwable ignored) {
            }
            if (!legalThrowTarget(maid, target)) {
                target = findThrowTarget(level, maid); // 记忆里的目标不合法（友军/非敌人）再按半径找
            }
            // ② 攻击链路完成检测：攻击冷却记忆【由无到有】= 她刚打完一记（TLM 近战与远程
            //    攻击都会写这个记忆）——轰炸与 TNT 都挂在「打完这一记」后面
            boolean cooling = maid.getBrain().getMemory(MemoryModuleType.ATTACK_COOLING_DOWN).isPresent();
            Boolean prev = COOLDOWN_SEEN.put(id, cooling);
            if (cooling && (prev == null || !prev)) {
                TNT_ARMED.put(id, gameTime);
                // ③ v1.2.2 实测五百九十二【轰炸推广到所有攻击模式】：这一记打完 → 先试轰炸起手
                //    （黑曜石+末地水晶 / 重生锚+萤石 / 床）。起手成功则整段交给轰炸相位，
                //    相位收尾自己会投 TNT（见 tick 末尾的 flushTnt）——所以这里直接返回。
                //    v1.2.2 实测六百〇三：远程空袭那道闸**删掉**（需求："将重生锚之类的放置
                //    也加入到远程空袭"）——她飞在天上也照放，落点是目标脚边 / 她正下方 /
                //    悬空 / 最后走 tryPlaceAirDrop 的后门。
                if (cfgMelee() && tryStartMelee(level, maid, target)) {
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
            UUID id = maid.getUUID();
            long gameTime = level.getGameTime();
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
            if (!hasTnt(maid) || !hasIgniter(maid)) {
                return; // 不刚需：缺料直接跳过（不占用间隔）——v1.2.2 实测六百〇三：点火料 = 打火石 或 烈焰弹；
                        // 实测六百〇四：TNT 判据放宽，注册名里带 tnt 的模组 TNT 也算（见 isTnt）
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
                if (h.tnt == null || h.tnt.isRemoved()) {
                    it.remove(); // 已经炸了 / 被清掉了
                    continue;
                }
                if (h.level.getGameTime() >= h.until) {
                    it.remove(); // v1.2.2 实测五百九十一：追踪时间到 → 撒手，按当前方向直飞
                    continue;
                }
                if (h.target == null || !h.target.isAlive() || h.target.isRemoved()
                        || h.target.level() != h.level) {
                    it.remove(); // 目标没了 / 换维度了 → 这一发按当前朝向直飞
                    continue;
                }
                double dx = h.target.getX() - h.tnt.getX();
                double dy = (h.target.getY() + h.target.getEyeHeight() * 0.5) - h.tnt.getY();
                double dz = h.target.getZ() - h.tnt.getZ();
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
                Vec3 mv = h.tnt.getDeltaMovement();
                double mx = mv.x;
                double my = mv.y;
                double mz = mv.z;
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
                h.tnt.setDeltaMovement(new Vec3(sx / sl * h.speed, sy / sl * h.speed, sz / sl * h.speed));
            } catch (Throwable ignored) {
            }
        }
    }

    /** 该实体能不能当投掷目标（防误伤 + 「她的任务认的敌人」两道，与 findThrowTarget 同口径） */
    private static boolean legalThrowTarget(EntityMaid maid, LivingEntity le) {
        try {
            if (le == null || le == maid || !le.isAlive()) {
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
        for (LivingEntity le : level.getEntitiesOfClass(LivingEntity.class, maid.getBoundingBox().inflate(r), e -> true)) {
            try {
                if (le == maid || !le.isAlive()) {
                    continue;
                }
                if (FriendlyFireGuard.isFriendly(maid, le)) {
                    continue; // 防误伤：主人 / 同主女仆 / 友军不扔
                }
                if (!(maid.getTask() instanceof com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask at)
                        || !at.canAttack(maid, le)) {
                    continue; // 只打"她的任务认的敌人"（与 TLM 索敌同口径）
                }
                double d = maid.distanceToSqr(le);
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
        return Mth.clamp(vy, -1.5, 1.5);
    }

    /** 这一发要扔几枚：残血（≤ 阈值）连投，否则 1 枚 */
    private static int throwCount(EntityMaid maid) {
        try {
            float max = maid.getMaxHealth();
            if (max > 0.0f && maid.getHealth() / max <= cfgTntBurstRatio()) {
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
        // v1.2.2 实测六百〇四：扔的到底是哪一件（模组 TNT 也认之后，日志里得说清，排查不用猜）
        String usedId = "";
        for (int i = 0; i < want; i++) {
            if (!hasTnt(maid) || !hasIgniter(maid)) {
                break;
            }
            ItemStack tntStack = takeOneTnt(maid);
            if (tntStack.isEmpty()) {
                break;
            }
            usedId = idOf(tntStack);
            // v1.2.2 实测五百九十一【打火石不再被吞】：从**原槽**取出 → 扣 1 点耐久 → 放回**原槽**
            //（旧版是整件取出、只对取出来的那份扣耐久 = 玩家看到的"直接把打火石吞掉"）
            // v1.2.2 实测六百〇三：没有打火石时用 **1 个烈焰弹**（真的消耗掉）——返回的是"刚用掉的
            // 那一件"，下面拿它做副手动作（亮打火石 / 亮烈焰弹，各亮各的）
            ItemStack igniter = useIgniter(maid);
            if (igniter.isEmpty()) {
                giveBack(maid, tntStack);
                break;
            }
            double sx = maid.getX();
            double sy = maid.getY() + maid.getBbHeight() * 0.75;
            double sz = maid.getZ();
            double tx = target.getX();
            double ty = target.getY() + target.getBbHeight() * 0.5;
            double tz = target.getZ();
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
            Vec3 tv = target.getDeltaMovement();
            for (int it = 0; it < 2; it++) {
                double ddx = aimX - sx;
                double ddz = aimZ - sz;
                double flight = flightTicks(Math.sqrt(ddx * ddx + ddz * ddz), speed);
                aimX = tx + tv.x * flight;
                aimZ = tz + tv.z * flight;
                aimY = ty + tv.y * flight * 0.5;
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
            tnt.setFuse(cfgTntFuse());
            tnt.setDeltaMovement(new Vec3(vx, vy, vz));
            if (!level.addFreshEntity(tnt)) {
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
                level.playSound(null, maid.blockPosition(), snd, SoundSource.BLOCKS, 1.0f, 1.0f);
            }
            maid.swing(InteractionHand.MAIN_HAND);
            // 投掷那一记的动作：副手举的是**点火的那一件**（实测五百九十四）——旧版亮的是刚扔出去的
            // 那枚 TNT，反馈要的是"点火的那只手"：扔 TNT 的时候副手切换成打火石；
            // 实测六百〇三起没有打火石就用烈焰弹，这时副手亮的自然是那枚烈焰弹。
            pose(maid, igniter.isEmpty() ? tntStack : igniter, BOMB_POSE_TICKS);
            if (PENDING.size() < MAX_PENDING) {
                PENDING.add(new Bomb(level, maid, tnt, tnt.blockPosition(), null, null,
                        Kind.TNT, gameTime + cfgTntFuse() + BOMB_TIMEOUT / 2, tntStack));
            }
            thrown++;
            if (cfgPinkMark()) {
                BombMarkNetworking.send(maid, 1, tnt.getId(), null, cfgTntFuse() + 40);
            }
        }
        if (thrown > 0) {
            TNT_NEXT.put(id, gameTime + cfgTntInterval());
            log(com.maidsmart.tool.PromaidLog.nameOf(maid) + " 投掷 TNT ×" + thrown
                    + "（" + (usedId.isEmpty() ? "未知物品" : usedId) + "，引信 " + cfgTntFuse() + " tick）");
        }
        return thrown;
    }

    /* ==================== 起爆 ==================== */

    @net.neoforged.bus.api.SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Pre event) {
        // Pre = 实体 tick 之前：原版 TNT 那一炸永远轮不到
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
                long now = r.level.getGameTime();
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
                if (b.level == null || b.maid == null || !b.maid.isAlive()) {
                    dropEntity(b);
                    it.remove();
                    continue;
                }
                if (b.entity != null && !b.entity.isAlive()) {
                    it.remove(); // 炸弹实体中途没了（被 /kill 之类）→ 这一发作废
                    continue;
                }
                long gameTime = b.level.getGameTime();
                boolean due;
                if (b.entity instanceof PrimedTnt pt) {
                    // TNT 以引信本身为判据：在本处理器（实体 tick 之前）取"引信 ≤ 1"，
                    // 于是原版那一下（会破坏方块）永远轮不到执行
                    due = pt.getFuse() <= 1;
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
                    for (ServerLevel lvl : server.getAllLevels()) {
                        for (Entity e : lvl.getAllEntities()) {
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
            if (b.entity != null && b.entity.isAlive()) {
                b.entity.discard();
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
            x = b.entity.getX();
            y = b.entity.getY();
            z = b.entity.getZ();
        } else {
            Vec3 c = b.pos.getCenter();
            x = c.x;
            y = c.y;
            z = c.z;
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
        if (b.entity != null && b.entity.isAlive()) {
            b.entity.discard();
        }
        // ④ v1.2.2 实测五百九十一【起爆那一记挥臂】：反馈"放置完重生锚/末地水晶后 0.5s 也会有一个
        //    挥臂的动作（好像真的打了一下末影水晶/重生锚）"——只有她还在近处（8 格内）才做，
        //    副手亮的是当时用的那一件（水晶 / 重生锚 / 床 / TNT）。
        if (b.display != null && maid != null && maid.isAlive()) {
            double ddx = maid.getX() - x;
            double ddy = maid.getY() - y;
            double ddz = maid.getZ() - z;
            if (ddx * ddx + ddy * ddy + ddz * ddz <= 64.0) {
                maid.swing(InteractionHand.MAIN_HAND);
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
                level.sendParticles(net.minecraft.core.particles.ParticleTypes.EXPLOSION_EMITTER,
                        x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
            } catch (Throwable ignored) {
            }
        }
        // v1.2.2 实测五百九十七【粉色火焰】：原版这一炸会**自己点火**——javap 实证
        // （Explosion.finalizeExplosion）着火那段在 `interactsWithBlocks()` 之外，只看 fire=true，
        // 所以哪怕我们默认"不破坏方块"（ExplosionInteraction.NONE → BlockInteraction.KEEP），
        // 重生锚 / 床那一炸照样在地上留下原版橙色火（末地水晶 / TNT 原版 fire=false，不留火）。
        // v1.2.2 实测五百九十八【改法换代】：旧版是"爆炸后按盒子把新出现的原版火换成粉色"，
        // 而原版点火的那些格子是**射线**扫出来的（空气阻力 0 → 射线能跑二十格开外），盒子永远
        // 罩不住 → 实测"又粉又橙"。现在换成"边点边换"：爆炸期间开着点火窗口，原版点火那一刻
        // 拿到手的就是粉色火（见 PinkFireBlock.beginWindow 与 BaseFireBlockPinkMixin）。
        boolean pinkWindow = fire && cfgPinkFire();
        if (pinkWindow) {
            PinkFireBlock.beginWindow();
        }
        // v1.2.2 实测五百八十八：整个爆炸期间挂"自爆风免"窗口——这个机制的风对放炸弹的她本人
        // 也不生效（与重锤风爆不同）。窗口是同步的，explode() 返回即关。
        selfImmuneBlast = true;
        selfImmuneMaidId = maid == null ? null : maid.getUUID();
        try {
            if (!cfgHurtFriendly()) {
                // 默认口径：**把女仆当爆炸来源**（伤害源交给原版按来源实体自己构造）。
                // 于是 damageSource.getEntity() == 女仆 → FriendlyFireGuard 取消主人/同主女仆/友军的
                // 伤害；Explosion 的来源实体同样是她 → FriendlyWindGuard 的击退豁免也一并生效。
                level.explode(maid, null, null, x, y, z, power, fire, mode);
            } else {
                // 原版口径：来源实体留空 = 完全不归因（主人/友军照掉血照被炸飞），
                // 并用 vanillaBlast 让风免在这一瞬间让位，避免"血掉了、人没飞"。
                vanillaBlast = true;
                try {
                    level.explode(null, null, null, x, y, z, power, fire, mode);
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
                    log("粉色火焰：这一炸点着的 " + pink + " 格火直接生成为粉色火");
                }
            }
        }
    }

    /** v1.2.2 实测五百九十七：爆炸火焰改粉色的总开关 */
    private static boolean cfgPinkFire() {
        return MaidSmartConfig.COMBAT_BOMBING_PINK_FIRE.get();
    }

}
