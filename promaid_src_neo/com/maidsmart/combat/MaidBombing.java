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
 * ② **投掷 TNT 时副手举点火料**：反馈"在扔 TNT 的时候，副手武器应该切换成打火石"——
 *    旧版亮的是刚扔出去的那枚 TNT，现在点亮火那只手里的那一件（打火石 / 烈焰弹 / 模组自己的
 *    点火料，见 {@link #useIgniterIn}：耐久在用掉那一刻就地扣掉，这里只借模型）。
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
 * ── 实测六百〇五【认出来的 TNT 就放它自己那一枚 + 点火料按"类"认】──
 * 反馈原文："1.我们应该判断一下这个方块是否存在其对应 tnt 形式，如果有则确认，并释放其对应的
 * TNT（而不是原版tnt）。2.类打火石/火焰弹识别；即可以（点燃 tnt 的消耗品走消耗，道具走自己的
 * 耐久机制）。"
 *
 * ① **判据再加一条结构证据**（{@link #tntBlockOf}）：注册名里带 tnt 只是"起名习惯"，
 *    模组 TNT 十有八九根本不含这三个字母（ProjectE 的爆破新星 = {@code projecte:nova_catalyst}，
 *    怎么念都念不出 tnt）。可靠的那条是**血统**：那一件是方块物品、**方块继承 {@link TntBlock}**
 *    就算 TNT——名字可以随便写，方块的血统骗不了人。两条取并集（原版那一件两条都命中）。
 * ② **放出来的是它自己的 TNT 形式**（{@link #primeTnt}）：旧版不论手上拿的是哪一件，扔出去的
 *    都是自己 {@code new} 出来的**原版**引信 TNT——ProjectE 的新星被换成一枚原版 TNT 炸，
 *    威力 / 破不破方块 / 带不带火全被抹掉。现在投放前先问那一件自己：方块不是 {@code TntBlock}
 *    本身（= 模组自己写的 TNT 方块）就走它自己的点火钩子 {@code onCaughtFire}
 *    （原版 {@code TntBlock.use}——打火石点 TNT——走的正是这一步），放出来的是**它自己的 TNT
 *    实体**（ProjectE 在这一步 new 的是 {@code projecte:nova_catalyst_primed}）；那一炸完全
 *    归它自己，本模组**不接管**（它继承 {@code PrimedTnt}，所以追踪照旧生效，引信仍按
 *    {@code bombing.tntFuse}）。原版那一件一字没动：还是自己造引信 TNT + 本模组"默认不破坏
 *    方块"的那一炸。
 * ③ **点火料按"类"认**（{@link #isFlintLike} / {@link #isChargeLike}）：**类打火石**
 *    （{@code FlintAndSteelItem} 的子类）/ **类火焰弹**（{@code FireChargeItem} 的子类）都算，
 *    注册名兜底——模组自加的点火料不用再各自打补丁。
 * ④ **道具走耐久、消耗品走消耗**（{@link #useIgniterIn}）：走哪一条不看名字，看这一件
 *    **有没有耐久**（{@code getMaxDamage() > 0}）——打火石 64 点耐久 → 扣 1 点、放回原槽；
 *    火焰弹没耐久 → 整件消耗。模组自加的点火料各按自己的性质落进对应那一档。
 * ⑤ 日志那一行还会点名**放出来的是哪一枚**（搜「投掷 TNT」），例如
 *    {@code 投掷 TNT ×1（projecte:nova_catalyst，引信 40 tick，它自己那一枚 projecte:nova_catalyst_primed）}；
 *    方块没改点火路径（放出来的还是原版引信 TNT）也会写明，验收不用猜。
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

    /* ==================== 物品（按注册名找，不写 SRG 字段名） ==================== */

    /* ==================== 状态 ==================== */

    /** 四类炸弹的爆炸参数（同一批原版反编译数字） */
    enum Kind {
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
    static final class Phase {
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
    static final class Bomb {
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
    static final class Homing {
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
        /**
         * 回收进谁的背包（实测五百九十一：底座变成物品还给她）。**存 UUID、不存实体**：
         * v1.3.8 实测六百七十——旧版抓着 10 秒前那个 {@code EntityMaid} 引用，她若在这 10 秒里
         * 被卸载重载 / 收进魂符再放出（都是**另一个对象**），回收的物品就塞进一个已经不在
         * 世界里的旧对象里 = 凭空消失。现在每次回收按 UUID 重新解析（口径同
         * {@code PlacedBlockTracker.findMaid}）。null = 没记到人 → 那一块就地撤掉、不回背包。
         */
        final UUID maidId;
        final List<BlockPos> pos;
        final List<Block> block;
        final long due;
        /** "这一条还在等"只记一条日志（区块没加载 / 她不在世界里），不刷屏 */
        boolean noted;

        Reclaim(ServerLevel level, UUID maidId, List<BlockPos> pos, List<Block> block, long due) {
            this.level = level;
            this.maidId = maidId;
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
    static void scheduleReclaim(ServerLevel level, EntityMaid maid, List<BlockPos> pos,
                                       List<Block> block) {
        if (level == null || pos == null || block == null || pos.isEmpty()) {
            return;
        }
        int seconds = Math.max(0, BombConfig.cfgReclaimSeconds());
        if (seconds <= 0) {
            BombPlacement.removePlaced(level, pos, block, maid, false);
            return;
        }
        if (RECLAIMS.size() >= MAX_RECLAIMS) {
            log("底座回收表已满（" + MAX_RECLAIMS + " 条）→ 这一块不再登记，它会留在世界里");
            return;
        }
        RECLAIMS.add(new Reclaim(level, maid == null ? null : maid.getUUID(), pos, block,
                level.getGameTime() + seconds * 20L));
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
    static final Map<UUID, EnumMap<Kind, Phase>> PHASE = new HashMap<>();
    static final Map<UUID, Long> TNT_NEXT = new HashMap<>();
    /**
     * v1.2.2 实测五百九十二：整条轰炸链路的最短间隔下限（女仆 → 链路 → 下次可起手的 gameTime）。
     *
     * 实测六百：从"每只女仆一条"细化成"**每只女仆、每条链路各一条**"——三段并行之后，
     * 若还共用一条下限，先起手的把下限顶掉就等于又把其它两段吞了（正好是要修的那个毛病）。
     */
    private static final Map<UUID, EnumMap<Kind, Long>> BOMB_NEXT = new HashMap<>();
    static final List<Bomb> PENDING = new ArrayList<>();
    /** v1.2.2 实测五百九十：攻击链路收尾登记的「待投放」（女仆 UUID → 登记时刻） */
    static final Map<UUID, Long> TNT_ARMED = new HashMap<>();
    /** 上一次扫描时她是否处于攻击冷却（无 → 有的跳变 = 刚打完一记） */
    static final Map<UUID, Boolean> COOLDOWN_SEEN = new HashMap<>();
    /** 追踪中的 TNT（引信期间每 tick 把方向掰向目标；只改方向、不改速度） */
    static final List<Homing> HOMING = new ArrayList<>();
    /** 「待投放」的有效期（tick）：挂这么久还没投出去就作废，免得留一发陈年老弹 */
    static final long ARMED_TIMEOUT = 200L;
    /** v1.2.2 实测五百九十一：追踪时每 tick 最多转这么多度（限转角 = 平滑弧线，不是瞬间折向） */
    private static final double TRACK_TURN_DEG = 5.0;
    /** 同上（弧度） */
    static final double TRACK_TURN_RAD = Math.toRadians(TRACK_TURN_DEG);
    /** 动作姿势：副手举起那件东西的时长（tick）——放置/充能/投掷 10（0.5 秒） */
    static final int BOMB_POSE_TICKS = 10;
    /** 动作姿势：起爆那一记挥臂的时长（tick） */
    static final int BLAST_POSE_TICKS = 8;
    /** 相位最长存活（tick）：超了当陈旧状态丢掉（防"打到一半目标没了"留下的尾巴） */
    private static final long PHASE_TIMEOUT = 100L;
    static final int MAX_PENDING = 256;
    /** 到期回收表上限（条，v1.3.8 实测六百七十）：旧版与待起爆共用 {@link #MAX_PENDING}（256），
     *  满了就**静默丢弃**——那一块底座从此不回收、物品也不还她；而"她飞远、区块卸载"会让条目
     *  留得比较久。单给一档，并且溢出时说话（见 {@link #scheduleReclaim}）。 */
    static final int MAX_RECLAIMS = 1024;
    /** 待起爆列表里一条最长挂多久（tick）：跨过这个数还没炸（区块没加载等）就丢掉，防泄漏 */
    static final long BOMB_TIMEOUT = 1200L;

    /** 供 {@link FriendlyWindGuard} 查询：此刻女仆的炸弹是不是"原版模式"（玩家照震） —— 实现见 BombExplosion.inVanillaBlast（v1.2.4 拆分）。 */
    public static boolean inVanillaBlast() { return BombExplosion.inVanillaBlast(); }

    /** 供 {@link FriendlyWindGuard} 查询：此刻这一下爆炸的风，对放炸弹的女仆本人是否也不生效 —— 实现见 BombExplosion.isSelfImmuneBlast（v1.2.4 拆分）。 */
    public static boolean isSelfImmuneBlast() { return BombExplosion.isSelfImmuneBlast(); }

    /** 这一下要施加在 {@code victim} 身上的风，是不是"放炸弹的她本人"该免掉的 —— 实现见 BombExplosion.isSelfImmuneBlastFor（v1.2.4 拆分）。 */
    public static boolean isSelfImmuneBlastFor(Entity victim) { return BombExplosion.isSelfImmuneBlastFor(victim); }

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
        MaidTntBlastGuard.clearAll(); // 实测六百〇七：模组 TNT 的登记表（跨存档/重载不留死 id）
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
                    BombPlacement.rollback(ph.level, ph);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * v1.3.8 实测六百七十【飞远了也要收得回来】：到期回收逐条推进。
     *
     * 旧版这一段有三个坑，症状是同一条——"她飞远之后那块黑曜石就收不回来了"：
     * <ol>
     *   <li><b>先删条目、后干活</b>：{@code ri.remove()} 写在 {@code removePlaced} <b>之前</b>，
     *       而整段只有一个 try/catch。飞远之后那块底座所在的区块早已卸载（她的区块票跟着她
     *       本人飞走），{@code level.getBlockState(pos)} 于是走
     *       {@code Level.getChunkAt} → {@code getChunk(…, requireChunk=true)}（javap 实证：
     *       {@code LevelReader.m_46819_} 里就是 {@code iconst_1}）——轻则在 tick 里<b>同步强制
     *       加载一片地形</b>，重则加载失败直接抛 {@code IllegalStateException}
     *       （{@code ServerChunkCache.m_8421_} 的失败分支 / "No chunk holder after ticket has
     *       been added"）。一旦抛了，条目已经没了 → 这一块<b>永久失收</b>、物品也回不到她背包；
     *       同一次异常还会连带跳过<b>同一个 tick 后面的起爆处理</b>。</li>
     *   <li><b>抓着 10 秒前的实体引用</b>：见 {@link Reclaim#maidId}。</li>
     *   <li><b>登记表满了静默丢</b>：见 {@link #MAX_RECLAIMS}。</li>
     * </ol>
     * 现在的口径：<b>那块底座所在的区块还没加载 / 她不在世界里的任何维度 → 这一条留着、
     * 下一 tick 再试</b>（不在 tick 里强制加载地形，也不把物品塞给一个已经不在世界里的旧
     * 对象）；真的动手了才把条目撤掉；每条各自 try/catch，一条出问题不牵连同 tick 的起爆
     * 与邻条。日志搜「底座回收再等等」/「回收底座」。
     */
    private static void tickReclaims() {
        for (Iterator<Reclaim> ri = RECLAIMS.iterator(); ri.hasNext(); ) {
            Reclaim r = ri.next();
            if (r.level == null) {
                ri.remove();
                continue;
            }
            long now;
            try {
                now = r.level.getGameTime();
            } catch (Throwable ignored) {
                ri.remove(); // 关卡对象已经不可用，这一条没意义了
                continue;
            }
            if (now < r.due) {
                continue;
            }
            try {
                EntityMaid maid = findMaid(r.level, r.maidId);
                if (r.maidId != null && maid == null) {
                    noteWaiting(r, "她不在世界里的任何维度（魂符收起 / 区块卸载）→ 留着，等她回来再还她背包");
                    continue;
                }
                if (!BombPlacement.removePlaced(r.level, r.pos, r.block, maid, true)) {
                    noteWaiting(r, "那块底座所在的区块还没加载 → 留着，等它加载了再收（不在 tick 里强制加载地形）");
                    continue;
                }
                ri.remove();
                log("回收底座 " + r.pos.size() + " 格 → " + (maid == null
                        ? "就地撤掉（没记到人，不回背包）"
                        : "还回 " + com.maidsmart.tool.PromaidLog.nameOf(maid) + " 的背包"));
            } catch (Throwable t) {
                log("回收底座异常（这一条留到下一 tick 再试）：" + t);
            }
        }
    }

    /** "这一条还在等"只记一条日志（第一句已经把原因说清，够排查了；同一块底座不刷屏） */
    private static void noteWaiting(Reclaim r, String why) {
        if (!r.noted) {
            r.noted = true;
            log("底座回收再等等：" + why);
        }
    }

    /**
     * v1.3.8 实测六百七十：按 UUID 找这只女仆（先同一维度，再跨维度——她可能已经换了维度）。
     *
     * 口径同 {@code PlacedBlockTracker.findMaid}：<b>任何维度在线且活着即算</b>；找不到
     * （被魂符收起 / 区块卸载 / 已删除）返回 null，调用方据此把回收<b>留着</b>——绝不把
     * 物品塞给一个已经不在世界里的旧对象（那等于物品凭空消失）。
     */
    private static EntityMaid findMaid(ServerLevel level, UUID id) {
        if (level == null || id == null) {
            return null;
        }
        try {
            if (level.getEntity(id) instanceof EntityMaid m && m.isAlive()) {
                return m;
            }
            net.minecraft.server.MinecraftServer server = level.getServer();
            if (server != null) {
                for (ServerLevel lvl : server.getAllLevels()) {
                    if (lvl == level) {
                        continue;
                    }
                    if (lvl.getEntity(id) instanceof EntityMaid m2 && m2.isAlive()) {
                        return m2;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * v1.3.8 实测六百七十【炸弹半路作废，底座也要还她】：把这一发已经放进世界的底座按口径收尾。
     *
     * 起爆那一刻（{@link BombExplosion#detonate}）与"这一发作废"的那三条路共用这一份定义。
     * 旧版那三条路（炸弹放下去之后：她死了 / 水晶实体没了 / 迟迟炸不了）只把这一发丢掉，
     * <b>那块已经放进世界的黑曜石就永远留在地上了</b>——既不回收、也不还她。
     *
     * 口径照 detonate 那一处：水晶链路 → 登记到期回收（回她背包）；重生锚 / 床 → 直接撤掉
     * （它们自己那一炸就把方块消耗掉了，回背包等于放一次白拿一个重生锚）。
     */
    static void settleBase(Bomb b) {
        if (b == null || b.placedPos == null || b.placedBlock == null) {
            return;
        }
        if (b.kind == Kind.CRYSTAL) {
            scheduleReclaim(b.level, b.maid, b.placedPos, b.placedBlock);
        } else {
            BombPlacement.removePlaced(b.level, b.placedPos, b.placedBlock, null, false);
        }
    }

    static void log(String msg) {
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
            if (level == null || maid == null || target == null || !BombConfig.cfgMelee()) {
                return false;
            }

            // v1.2.2 实测五百九十：傀儡模式（第三方玩法 Modular Golems 的「傀儡师」）期间
            // 不介入——轰炸也是本模组的战术，那个模式要的是原汁原味的傀儡装配玩法
            if (com.maidsmart.compat.MaidModeCompat.isSuspended(maid)) {
                return false;
            }

            // v1.3.6 实测六百六十一【扫帚模式：不放放置类战术】她骑在扫帚上，只有主手武器在打。
            // 放置那条链路（本方法 / BombTntTick 的战斗扫描 / 相位机）只看"任务是不是战斗类"，
            // 全程没有 isPassenger 判定——她飞在天上照样会起手放水晶/重生锚/床（源码实证）。
            // 手册里那句「扫帚模式下不能放 TNT / 末地水晶 / 重生锚 / 床」要成立，就必须真拦一道。
            // 判据在 MaidBroomKit.forbidsBombing（= 任务是扫帚模式），一处定义、三处调用。
            if (com.maidsmart.combat.MaidBroomKit.forbidsBombing(maid)) {
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
                next.put(kind, now + BombConfig.cfgBombInterval());
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
        if (BombItems.has(maid, BombItems.ID_END_CRYSTAL) && BombItems.has(maid, BombItems.ID_OBSIDIAN, BombItems.ID_BEDROCK)
                && !onCooldown(next, Kind.CRYSTAL, now)) {
            out.add(Kind.CRYSTAL);
        }
        // ② 重生锚（需要 1 颗萤石当引信；维度闸见 explodesHere）
        boolean anchorReady = explodesHere(level, Kind.ANCHOR) && BombItems.has(maid, BombItems.ID_RESPAWN_ANCHOR)
                && (!BombConfig.cfgAnchorNeedsGlowstone() || BombItems.has(maid, BombItems.ID_GLOWSTONE));
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
        if (explodesHere(level, Kind.BED) && BombItems.hasBed(maid) && !onCooldown(next, Kind.BED, now)) {
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
            boolean crystal = BombItems.has(maid, BombItems.ID_END_CRYSTAL);
            boolean base = BombItems.has(maid, BombItems.ID_OBSIDIAN) || BombItems.has(maid, BombItems.ID_BEDROCK);
            boolean anchor = BombItems.has(maid, BombItems.ID_RESPAWN_ANCHOR);
            boolean bed = BombItems.hasBed(maid);
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
                sb.append("重生锚：").append(!BombItems.has(maid, BombItems.ID_GLOWSTONE) && BombConfig.cfgAnchorNeedsGlowstone()
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
        if (level == null || maid == null || !BombItems.has(maid, BombItems.ID_RESPAWN_ANCHOR)) {
            return;
        }
        if (!explodesHere(level, Kind.ANCHOR)) {
            hint(level, maid, HINT_ANCHOR_DIM);
        } else if (BombConfig.cfgAnchorNeedsGlowstone() && !BombItems.has(maid, BombItems.ID_GLOWSTONE)) {
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
        if (!BombConfig.cfgDimensionGuard()) {
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
            BombPlacement.rollback(level, ph);
            return Result.ABORT;
        }
        // v1.3.6 实测六百六十一：扫帚模式不放放置类战术（她骑在扫帚上只能主手武器输出）。
        // 这一档管的是"相位已经在飞"的情况：她刚被换成扫帚模式时，在飞的那一段当场作废，
        // 已经放下的方块回滚进背包——否则手册里那句声明在切换的那几秒里会是假的。
        if (com.maidsmart.combat.MaidBroomKit.forbidsBombing(maid)) {
            BombPlacement.rollback(level, ph);
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
                BombPlacement.rollback(level, ph);
                return Result.ABORT;
            }
            if (ph.target != null && ph.target.isAlive()) {
                ph.targetPos = ph.target.blockPosition(); // 目标活着 → 跟着它走
            }
            if (ph.step == 0) {
                if (!BombPlacement.stepBlock(level, maid, ph.targetPos, ph)) {
                    return Result.ABORT; // 放不下：这一段放弃（其它段各走各的，不受影响）
                }
                ph.step = 1;
                ph.stepAt = gameTime;
                return Result.CONTINUE;
            }
            // v1.2.2 实测五百九十一【看得出间隔】：放下方块与"挂水晶 / 充能"之间留一段可见停顿——
            // 反馈："黑曜石和末地水晶几乎是同时放置的，根本看不出间隔"。默认 10 tick = 0.5 秒。
            if (gameTime - ph.stepAt < BombConfig.cfgPlaceGap()) {
                return Result.CONTINUE;
            }
            if (!BombPlacement.stepPayload(level, maid, ph, gameTime)) {
                BombPlacement.rollback(level, ph);
                return Result.ABORT;
            }
            return Result.DONE; // 交还链路：她接着放烟花起飞，0.5 秒后那边起爆
        } catch (Throwable t) {
            log("执行异常：" + t);
            BombPlacement.rollback(level, ph);
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
                            BombPlacement.rollback(ph.level, ph); // 她没了：已放下的方块撤掉（不掉落）
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
                        BombTntTick.flushTnt(maid.level() instanceof ServerLevel sl ? sl : null, maid, target, id,
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

    /* ==================== 放置 ==================== */

    /* ==================== 远程空袭：投掷 TNT ==================== */
    /** 远程空袭盘旋期间的投掷入口（"女仆会在天上盘旋期间额外发射 tnt"）：调用点在{@code fireRanged} 之后 = 这一次开火打完，于是直接走"攻击 —— 实现见 BombTntTick.tickRangedTnt（v1.2.4 拆分）。 */
    public static void tickRangedTnt(ServerLevel level, EntityMaid maid, LivingEntity target, UUID id, long gameTime) { BombTntTick.tickRangedTnt(level, maid, target, id, gameTime); }

    /** v1.2.2 实测五百八十八【推广到所有有战斗标签的模式】：不再只在远程空袭的盘旋里扔 —— 实现见 BombTntTick.tickCombatTnt（v1.2.4 拆分）。 */
    public static void tickCombatTnt(ServerLevel level, EntityMaid maid) { BombTntTick.tickCombatTnt(level, maid); }

    /* ==================== v1.2.2 实测五百九十：TNT 挂进攻击链路 ==================== */
    /** 【为什么要改】反馈原文：「投掷 TNT 这个功能最好是跟末影水晶一样放在攻击链条的某个部分，我的建议是适当的正常攻击链路走完之后加到最后 —— 实现见 BombTntTick.onAttackChainEnd（v1.2.4 拆分）。 */
    public static void onAttackChainEnd(ServerLevel level, EntityMaid maid, LivingEntity target) { BombTntTick.onAttackChainEnd(level, maid, target); }

    /* ==================== v1.2.2 实测六百〇五：放出"它自己的 TNT 形式" ==================== */

    /* ==================== 起爆 ==================== */

    @net.neoforged.bus.api.SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Pre event) {
        // Pre = 实体 tick 之前：原版 TNT 那一炸永远轮不到
        try {
            // v1.2.2 实测五百八十九：到期回收（起爆后保留 10 秒的黑曜石/重生锚/床）
            // v1.2.2 实测五百九十：追踪弹每 tick 修正一次朝向（在实体 tick 之前）
            BombThrow.tickHoming();
            // v1.2.2 实测五百九十二：轰炸相位也由服务端统一驱动（所有攻击模式共用同一台机器）
            tickPhases();
            tickReclaims();
            Iterator<Bomb> it = PENDING.iterator();
            while (it.hasNext()) {
                Bomb b = it.next();
                if (b.level == null || b.maid == null || !b.maid.isAlive()) {
                    settleBase(b); // v1.3.8：这一发作废，已经放进世界的那块底座也要还她
                    dropEntity(b);
                    it.remove();
                    continue;
                }
                if (b.entity != null && !b.entity.isAlive()) {
                    settleBase(b); // v1.3.8：同上——旧版这里只丢炸弹，黑曜石就永远留在地上
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
                        settleBase(b); // v1.3.8：同上——这一发作废，底座不能白留在地上
                        dropEntity(b);
                        it.remove();
                    }
                    continue;
                }
                it.remove();
                BombExplosion.detonate(b);
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

}
