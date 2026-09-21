package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * v1.2.2 实测六百〇八 / 六百一十一【飞行跟随】：主人自己飞走了，她也能背上鞘翅追上来——而不是只在
 * 底下垫方块干着急。
 *
 * ── 需求原文（六百〇八）──
 * "女仆跟随能不能给她整个使用鞘翅一起飞呢，自己飞了后女仆只能搭着方块干着急了，两人一起飞
 * 想想还挺有意思的。"（本条由作者回复确认为**可选玩法**：默认关闭，要玩的人在手册/面板里打开）
 *
 * ── 六百一十一 改的三件事（用户反馈原文）──
 * "飞行跟随的启动只认烟花，而且动作没有换成空袭飞行的动作。而且不需要和主人离那么近，在飞行
 * 跟随期间自身周围 15 格内找到主人那么此链路就中断，不需要紧挨着主人。具体的表现就跟空袭女仆
 * 把怪打死一样。自然滑翔。反正飞出去了会再次启动链路。"
 * <ol>
 *   <li><b>推进剂口径并入羽扇</b>：起飞与补推的燃料从"只认烟花"扩成
 *       {@link MaidFlightKit#hasFlightFuel}（烟花火箭 **或** 孔雀羽扇），与空袭
 *       {@code canLaunch} 同一口径；有扇先挥扇（{@link TwilightFanKit#boostGlide}），
 *       顺序也与空袭的"扇子优先"一致。</li>
 *   <li><b>收手距离 4 格 → 15 格，并且不再"抬头泄速"</b>：主人进到她
 *       {@link #endRadius()} 格内，本趟链路就中断——**不需要贴到身边**；她若还在空中，
 *       就照原样继续自然滑翔、落地自然收尾，与"空袭女仆把怪打死之后"那一段
 *       （{@code MaidFlightCombatBehavior.endFlightSafely}）完全是同一套。</li>
 *   <li><b>外观与空袭同款</b>：滑翔中的女仆现在也走空袭那三样——模型游泳（展翅）姿态、
 *       鞘翅翅膀图层、跟随俯角的前倾。判据统一取"滑翔位"（同步过的共享标志位 7），
 *       多人下客户端也认得出（与 {@code MaidFlightKit.isGliding} 同源）。</li>
 * </ol>
 *
 * ── 六百一十二 改的四件事（用户反馈原文）──
 * "1.飞行跟随配置默认距离从16改为5，同时加一个判定，在距离内检测到主人之后解除烟花带来的矢量
 * （之前空袭状态下是不解除，在此模式下改为解除）2.2个空袭状态（未接敌），也可以触发飞行跟随，
 * 不做额外抑制。简单来说，就是目前飞行跟随触发判定推广到所有模式下。3.跟搭路有同款的判定，
 * 检测到威胁的时候会解除这个链路（又变成自然滑行）。4.我需要确认一点，在此类状态下，女仆在
 * 什么时候会使用烟花火箭这类推进呢？"
 * <ol>
 *   <li><b>触发距离默认 16 → 5 格</b>（当时的键 {@code bridge.flightFollowDist}，范围 3~128；
 *       **六百一十五 起这个键改名为 {@code flightFollow.dist} 并搬到自己的小节，默认值 25**——
 *       本条的 5 是历史值，见类注释末尾的六百一十五 一节）。
 *       16 格那档配上"收手取 min(15, 距离-1)"只剩 1 格迟滞，实际很难触发；5 格才是"她跟得上
 *       你"的距离。**注意**：老存档的 config 文件里若已写着 16，NeoForge 不会替你改小——要么删掉
 *       那一行、要么手动改 5，见 changelog 的"升级注意"。</li>
 *   <li><b>进到主人身边 = 解除推进矢量</b>（{@link #releaseThrust}，由 {@link #stop} 执行）：
 *       主人进到 {@link #endRadius()} 格内，本趟不但中断，还要**把烟花给的这份速度解掉**——收掉还挂在
 *       背上的那枚助推火箭（它活着期间每 tick 都把速度往 1.7×视线拉，见
 *       {@code MaidFlightKit#launchBoostRocket} 的字节码实证）+ 速度归零，之后交给原版滑翔
 *       自然下沉。空袭那边**不解除**（她正需要这份动量贴脸/扑击），这是本模式与空袭的第一处
 *       刻意分歧，用户口径里点名了这一条。**为什么由 stop() 而不是 tick() 执行**：Brain 的
 *       {@code Behavior.tickOrStop} 是"canContinue 为假就直接 stop、本 tick 不调 tick()"，
 *       而"进半径"这条判据就在 canContinue 末尾——写在 tick() 里等于**永不执行**（绿轮实测
 *       踩到：日志里没有那一行），见 {@link #RELEASE_ON_STOP} 的注释。</li>
 *   <li><b>触发判定推广到所有模式</b>：旧版有一句"她是空袭任务就不起飞"（"它自己有飞行作战
 *       链路"）——现在拆成**接敌与否**（{@link #ownFlightBusy}）：两个空袭任务**未接敌**
 *       （脑内没有存活攻击目标、且没在起跳/爬升/猛击/助推那几段出手里）照样可以由本链路接管，
 *       其余任务（空闲/战斗/挖矿/烹饪……）本来就一视同仁。真在打、或真有活干
 *       （{@code isTaskOccupied}，与搭路同一套）才让位——**不做额外抑制**。</li>
 *   <li><b>威胁解除链路</b>：与搭路同一口径（同一个配置 {@code bridge.threatDist}、同一个
 *       {@code instanceof Enemy}）——威胁半径内出现敌对生物，本趟当场中断、交回普通跟随/战斗，
 *       空中就**保持动量自然滑翔**（这一条 六百一十一 起就在 canContinue 里生效，本批把它与
 *       "进距离解除矢量"的**区别**写清楚：威胁是"撤"，进距离是"收"）。</li>
 * </ol>
 * 第 4 条是**提问**（"女仆在什么时候会使用烟花火箭这类推进呢？"），答案见
 * {@link #shouldBoost} 的注释与手册里的"她什么时候烧烟花"：起飞后第一口、以及此后每
 * {@link #BOOST_INTERVAL}（1.5 秒）一次的"比你低 / 水平速度掉了（&lt;0.35 格/tick）"补推；
 * 进到 {@link #endRadius()} 格内不再推（本批起还会把已给的速度解掉）。背包里有孔雀羽扇时
 * 走扇子那条（不烧烟花），与空袭"扇子优先"同序。
 *
 * ── 六百一十三 改的一件事（用户原话："位移法术也可以加入到飞行跟随的启动中"）──
 * <b>启动的"能飞的道具"从两选一扩成三选一</b>：烟花火箭 / 孔雀羽扇 / **能上天的位移法术**
 * （{@link MaidSpellCastCompat} 的「提供高度」那一类）。此前只带法术书、不带烟花的存档里，
 * 她**压根不会起飞**——判定链上第一条就把她挡掉了（"背包里既没有烟花火箭、也没有孔雀羽扇"）。
 *
 * <ol>
 *   <li><b>门禁</b>（{@link #m_6114_} 与 {@link #m_6737_}）：改调
 *       {@link MaidFlightKit#hasFlightPropellant}——与空袭三件套的第三条**同一份定义**。
 *       （六百一十三 顺手把散在三处的"三选一"收敛成一个方法，见该方法的注释：
 *       口径只能有一处定义，否则迟早又出现"气泡说齐了、她却不飞"。）</li>
 *   <li><b>那一口推进</b>（{@link #boost}）：顺序 = 扇子 → 烟花 → **位移法术**（新增）。
 *       这是**空袭的起飞顺序**（"有烟花先走烟花链路、没有才用位移法术"），不是它"盘旋掉高"
 *       那一套（那边是法术优先，实测五百七十八 A 方案单独确认过）。用户这次要的是"加入
 *       **启动**"，所以带了烟花/羽扇的存档**手感与烧料节奏一字不变**——法术是**第三条腿**：
 *       烟花烧完（消耗档）或压根没带烟花时，她照样起飞、照样跟进。</li>
 *   <li><b>打法照抄空袭的"平地起飞 / 补高"</b>（{@link #castClimbSpell}）：先清掉法术模组那份
 *       施法目标（否则它施法前 `forceLookAtTarget` 会把朝向拧平——升腾就变成"向前扑"而不是
 *       "窜上天"），再 {@link #faceToward}(…, takeoff=true)：**抬头至少 {@link #TAKEOFF_PITCH}
 *       度、瞄着主人**。节流用本链路自己的 {@link #BOOST_INTERVAL}（与烟花共一张表——抢的是
 *       同一种"这一口推进"），写回冷却用 {@code combat.flightDashInterval}（与空袭"提供高度"
 *       那一类的写回值完全一致）。</li>
 * </ol>
 * <b>开关归属</b>：法术这一支由空袭的「位移法术·起飞/补高」（{@code combat.flightDashClimb}，
 * 默认开）管——关掉它，本链路也**一起**退回只认烟花/羽扇（不新增配置项，两处口径仍然一致）。
 * 起飞那行日志会把这一趟的燃料写成三种里的哪一种（{@link #fuelLabel}），
 * 验收入口：日志搜「飞行跟随」看到「放位移法术追主人（irons_spellbooks:ascension Lv3）」。
 *
 * ── 六百一十四 改的一件事（来源：粉丝 Roderick32 的「鞘翅赶路」分支，取其精华）──
 * 他的分支里"主人跑远 → 穿鞘翅飞过去"这件事**与本链路完全撞车**（我们这边六百〇八～六百一十三
 * 已经把它做全了：所有模式通用、烟花/羽扇/位移法术三种燃料、两个省料开关、与搭路/自保/传送的
 * 让位全套）。所以本批**没有**照搬他那套平行状态机（自带会话表、8 个配置项、没进展判定，还要
 * mixin 拦 TLM 的跟随瞬移），只取他这条链里本类确实没有的那一件能力：
 * <b>目标可以是一个坐标</b>（{@code /maid_smart elytra_goto <x> <y> <z> [女仆]}，他给的两个用途是
 * "想让她飞去那边的高台/浮空岛"与"无头服里复现整条飞行链路"）。实现方式是把"目标"抽成一个点
 * （{@link Aim}：实体档 / 坐标档），**判定、起飞、补推、收手全部复用本类**。
 *
 * 另外顺手收了他一条诊断（与本链路无关，但站在同一处代码上）：法术 id 写错时，配置表里
 * **排在第一的那个法术会永远静默失效**——现在 {@link MaidSpellCastCompat#warnUnknownSpellIds}
 * 会报一条 {@code [法术兼容]} 日志（判据是他用字节码钉出来的：ISS 的 {@code SpellRegistry.getSpell}
 * 对未知 id 返回的是 {@code noneSpell} 而不是 null）。
 *
 * <b>他那份实现里我们**刻意没要**的部分</b>（都写在 CHANGELOG 里）：
 * mixin 拦 TLM 跟随瞬移（本链路用"距离阈值 + isFollowing 让位"达成同一目的，不动原版链路）、
 * 8 个平行配置项（本链路已有等价开关）、"没进展就收手"的定时器（本链路已有 60 秒上限 +
 * 起飞前视线判定）、"不消耗鞘翅耐久"（本链路已有 {@code flightFollow.elytra}）。
 *
 * ── 六百一十五 改的两件事（用户反馈原文）──
 * "1.现在女仆稍微走出去一点就开始飞（默认5格导致的），需要对判定进行一个收紧。首先，启动飞行跟随
 * 要求：以自身为圆心，半径25格球内无主人 + 无方块阻挡 + 未发现威胁 + 开关打开 = 启动跟随飞行。
 * 重点：如何结束这个模式？当发现5格内有主人后，结束此模式。如果有烟花矢量则消去（现在已经做了）。
 * 在先前的版本，进入此模式和取消此模式走的都是同一个判定球，这样子带来的麻烦很多。实际上开始跟结束
 * 两个半点的球大小应该不一样，默认值就是我说的那两个。依旧可以在手册内调试。
 * 2.然后把飞行跟随这个板块单独拎出来，不要放在搭路板块的里面，而是改成跟搭路平行的一个板块。"
 * <ol>
 *   <li><b>起手球 25 格 / 收手球 5 格（两个不同的球）</b>：旧版只有一个判定球——收手半径是
 *       "起手距离 − 1"推出来的（六百一十二 起默认 5 → 收手 4），迟滞只有 1 格，于是"刚过起手线
 *       就起飞、走两步又进收手线就收手"的来回抖动是常态。现在起手 {@link #cfgDist()}（默认 25）
 *       与收手 {@link #cfgEndDist()}（默认 5）**各读各的配置**，默认档留 20 格迟滞；
 *       两个值都能在配置面板「移动与行为 → 飞行跟随」里调（用户："依旧可以在手册内调试"）。</li>
 *   <li><b>整块从 {@code [bridge]} 搬到自己的 {@code [flightFollow]} 板块</b>（用户第 2 条）：
 *       配置小节、配置面板的页、手册里的引用一起搬——**与搭路平级**，不再是搭路参数里的一行。
 *       <b>升级注意</b>：老配置里 {@code [bridge]} 下的四个 {@code flightFollow*} 键**不再生效**
 *       （Forge/NeoForge 不会替你把值搬过来），重新打开一次开关即可。</li>
 * </ol>
 * 判定链本身一字未改，仍是：开关打开 → 目标存在且同维度 → **3D 距离 > 起手距离（25）** →
 * 有可用鞘翅 + 能飞的道具 → 与目标之间没有方块阻挡视线 → 威胁半径内没有敌对生物。
 *
 * ── 触发位置（为什么卡在"搭路"这一档）──
 * 作者给的口径："开启开关之后，女仆在判定使用搭路时，发现主人离自己太远且自己跟主人之间
 * 没有方块阻拦，自己包里面还有鞘翅和烟花的时候，target=主人，执行飞行（跟空袭模式的起飞
 * 是一样的，但是 target 等于主人）"。所以本行为注册在 core 246——**刚好压过搭路（245）**：
 * 条件满足时她直接起飞，条件不满足时本行为不启动、搭路那条老链路一字不动（搭路自己另加了
 * 一道"飞行跟随进行中就不搭"的让位，见 {@code BridgeUpBehavior.checkExtraStartConditions}）。
 *
 * ── 判定（全部满足才起飞）──
 * <ul>
 *   <li>开关 {@code flightFollow.enabled}（**默认关**）打开；</li>
 *   <li>她**没有在打**（六百一十二 起口径）：不在守家/坐姿/骑乘/睡觉、不在自保、任务没有实质
 *       占用（{@link com.maidsmart.task.BridgeUpBehavior#isTaskOccupied}，与搭路同一口径
 *       ——"另一种空闲"照飞，接战中绝不飞）；两个空袭任务**未接敌**时也在放行之列
 *       （见 {@link #ownFlightBusy}），旧版那句"空袭任务一律不起飞"已删除；</li>
 *   <li>目标存在、活着、同维度（主人 / {@code /maid_smart flyfollow} 挂的"替代主人" / 六百一十四
 *       起的 {@code /maid_smart elytra_goto} 指定的坐标），且**3D 距离超过
 *       {@code flightFollow.dist}（**起手距离**，默认 **25** 格，范围 3~128；六百一十二 曾把它
 *       从 16 降到 5、六百一十五 又改回 25）**——太近就走路/搭路，犯不上烧烟花；</li>
 *   <li>她包里有**可用鞘翅**，以及**能飞的道具**——烟花火箭 **或** 孔雀羽扇 **或** 能上天的
 *       位移法术（{@link MaidFlightKit#hasFlightPropellant}，缺一件就不飞；六百一十一 起
 *       不再只认烟花、六百一十三 起法术也算；与空袭三件套的第三条同一口径）；</li>
 *   <li>与主人之间**没有方块阻拦**（raycast 视线，复用自保那套 {@code hasSight}）；</li>
 *   <li>周围 {@code bridge.threatDist} 格内没有敌对生物（与搭路同口径，绝不往怪堆里飞）。</li>
 * </ul>
 *
 * ── 飞起来是什么样（"跟空袭的起飞一样"）──
 * 完全照搬空袭那套起手机制，只是把"敌人"换成主人：
 * <ol>
 *   <li><b>起跳滑翔</b>——先原地跳一下离地（{@code updateFallFlying} 硬要求非落地），
 *       置滑翔位；抬头至少 {@link #TAKEOFF_PITCH} 度先换一点高度；</li>
 *   <li><b>放烟花</b>——挂载型烟花（只写 {@code Flight}、不写爆炸星，所以到期不会炸到她自己，
 *       与空袭 {@code launchFirework} 同一口径）。推力沿她的**视线**方向生效，而她此刻正看着
 *       主人，所以推力方向天然就是"朝主人去"（不需要额外造速度）；</li>
 *   <li><b>持续操纵</b>——每 tick 把视线钉在主人身上（滑翔的操纵杆就是视线），
 *       离得远/比主人低/速度掉了就补一口推进（{@link #BOOST_INTERVAL} = 1.5 秒最短间隔；
 *       有孔雀羽扇先挥扇，没扇才烧烟花——与空袭的推进顺序同款）；</li>
 *   <li><b>飞出去就重启</b>（六百一十一）——她滑过头、或者主人又飞远了，距离重新超过
 *       「飞行跟随距离」时本行为再次启动（再起飞 → 再补推 → 再飞过来），一趟一趟地跟，
 *       不需要玩家干预。**每趟仍有界**：一趟最长 {@link #MAX_TICKS}（60 秒）；超时 / 燃料
 *       没了 / 鞘翅没了 / 威胁出现 / 自保 / 主人跨维度 一律立刻收手（收手**不清滑翔位**——
 *       空中清位就是自由落体，那条老教训见 {@code MaidFlightCombatBehavior.endFlightSafely}）。</li>
 * </ol>
 *
 * ── 收手长什么样（六百一十一 改口径：与"空袭把怪打死之后"完全一致；六百一十二 加"解除矢量"）──
 * 目标进到 {@link #endRadius()} 格内 → 本趟中断。**六百一十五 起收手半径是自己的一个配置**
 * （{@code flightFollow.endDist}，默认 **5** 格；起手是另一个配置、默认 **25**）——旧版只有一个
 * 判定球（收手 = 起手 − 1），迟滞 1 格、来回抖动，见 {@link #endRadius()} 的注释。
 * 六百一十二 起这里多一步 {@link #releaseThrust}：**把烟花给的速度解掉**（收掉还挂着的助推
 * 火箭 + 速度归零）——不然她带着 1.7 格/tick 的动量从你身边冲过去。解除之后她还在空中就
 * 什么都不做，原版滑翔物理会把她自然带下去（滑翔分支里 `checkSlowFallDistance` 会在下落缓慢时
 * 清坠落距离，所以不会摔伤），落地那一 tick 由 {@link #settleOnGround} 把胸甲还回去。旧版
 * （六百〇八）要贴到 4 格、还要"抬头 {@code FLARE_PITCH} 度泄速 {@code FLARE_TICKS} tick"
 * 才算收手——用户要的是**自然滑翔**，那套主动减速整段删掉了。
 *
 * ── 外观：与空袭同款（六百一十一）──
 * 用户原话是"动作没有换成空袭飞行的动作"。空袭那三样外观各有各的判据，原来全都写着
 * {@code MaidFlightKit.isFlightTask(maid)}——而**跟着飞的她并不是飞行任务**，于是三样一样都没跟上：
 * <ul>
 *   <li>{@code MaidSwimGlideMixin}：滑翔时让 {@code isVisuallySwimming} 为真 → TLM 的
 *       {@code AnimationRegister} 播放自带的游泳（展翅）动画。**实测六百一十一 扫过 TLM
 *       1.5.3 的 2344 个类：引用这个方法的只有 3 个（AnimationRegister / SwimAnimation /
 *       EntityMaid 自己的覆写），全是客户端动画**——所以这里放宽成"只要在滑翔就为真"
 *       （正是原版 {@code LivingEntity} 里被 TLM 覆写丢掉的那一支），不会碰到任何玩法判定；</li>
 *   <li>{@code LayerMaidElytra} / {@code LayerMaidElytraGecko}：背上画鞘翅翅膀（并在离地时
 *       强制展翅）；</li>
 *   <li>{@code FlightDiveTilt}：按俯角叠前倾（玩家鞘翅那套 {@code -getXRot()}）。</li>
 * </ul>
 * 判据统一收在 {@code MaidFlightKit.isFlightVisual(maid)} = {@code isFlightTask || isGliding}：
 * 空袭那边一字不变（它本来就滑翔），跟着飞的她从此也认；判据取**同步过的滑翔位**，所以多人下
 * 客户端不需要服务端那张 {@link #FOLLOWING} 表也认得出。
 *
 * ── 两个"省料"开关（作者要求"可以调整是否消耗烟花和鞘翅耐久"）──
 * <ul>
     *   <li>{@code flightFollow.firework}（默认**开** = 真消耗）：关掉之后**照旧需要包里有
     *       能飞的道具**（烟花火箭 **或** 孔雀羽扇 **或** 能上天的位移法术，它是"她能飞"的凭证；
     *       六百一十三 起法术也在这一列），但每次补推不再从背包扣那一枚
 *       ——纯观赏档，适合"只想看她跟着飞"的存档。**背包里有羽扇时走扇子那条**（不烧烟花，
 *       照羽扇自己的口径扣耐久），这条开关只管烟花那一支。</li>
 *   <li>{@code flightFollow.elytra}（默认**开** = 照原版扣）：关掉之后滑翔不再啃鞘翅耐久，
 *       由 {@link com.maidsmart.mixin.ElytraWearGuardMixin} 在
 *       {@code ElytraItem.elytraFlightTick} 入口拦掉那次 {@code hurtAndBreak}
 *       （**注意**：只认原版 {@code ElytraItem} 及其子类；模组"内置鞘翅的护甲"走它自己的
 *       钩子，这里拦不到——边界写在手册里）。</li>
 * </ul>
 *
 * ── 与其它链路的关系（全部是"让位"，不是"抢") ──
 * <ul>
 *   <li><b>自动传送</b>：飞行期间她的滑翔位为真 → {@code isFlightAirborne} 为真 → TLM 原生
 *       teleportToOwner 与"跨维度跟随"本来就让位；**同维度远距拉回**额外认一条
 *       {@link #isFollowing}——不然她飞到 48 格会被直接传送过去，"一起飞"这件事当场结束；</li>
 *   <li><b>搭路</b>：{@code BridgeUpBehavior.checkExtraStartConditions} 见 {@link #isFollowing}
 *       直接返回 false（她正在飞，脚下没有桥要铺）；她自己的垫脚方块那一套由
 *       {@code MaidPlaceGuard} 的"滑翔中不搭"管住；</li>
 *   <li><b>落地保护</b>：{@code MaidFlightWallGuard} 的撞墙/摔落免疫扩到本状态——她原本不是
 *       飞行任务，那两个开关按 {@code isFlightTask} 判会把跟着飞的她漏掉（20 血撞一次墙就没了）。</li>
 * </ul>
 *
 * ── 有界性 ──
 * 见上一条"飞出去就重启"：每趟最多 {@link #MAX_TICKS}（60 秒），到点/缺料/威胁一律收手，
 * 收手之后能不能再起飞由下一趟判定重新决定（**重启没有次数上限**，但每次都重新过一遍全套判定）。
 *
 * ── 专用服务器上的验收入口 ──
 * 这条链的 target 是**在线主人实体**（{@code maid.getOwner()} 走 PlayerList，专用服务器上
 * 没有玩家就是 null），所以与"女仆喂女仆"（实测五百四十五）同一类困境：结构上无法端到端触发。
 * 照那条的先例留了 {@code /maid_smart flyfollow <实体>} 指定一个"替代主人"
 * （{@link #setDebugTarget}）——走的仍是本类**同一套**判定与飞行链路，只替换目标来源。
 * 六百一十四 起又多一个更省事的入口：{@code /maid_smart elytra_goto <x> <y> <z> [女仆]}
 * （{@link #setGotoTarget}）——**连"替代主人"那个实体都不用摆**，直接给坐标；平时它也是
 * "送她飞去某地"的实用命令（到点/超时/遇敌即收手，见 {@link #stop}）。
 */
public class MaidFlightFollowBehavior extends Behavior<EntityMaid> {

    /*
     * 【六百一十五 删掉了一个常量】旧版这里写着 {@code END_RADIUS = 15}（六百一十一 定的"收手半径
     * 上限"，六百一十二 把起手距离降到 5 之后它实际只在"起手距离 ≥ 16"时才生效）。本批收手半径
     * 独立成了一个配置（{@code flightFollow.endDist}，默认 5），那个写死的上限就没有存在意义，
     * 连同它的 {@code min(15, …)} 一起删掉了——历史留在 {@link #endRadius()} 的注释里。
     */
    /** 滑翔操纵杆的俯仰限幅（度）：抬头 60 / 低头 45（与空袭同档） */
    private static final float PITCH_UP = 60.0f;
    private static final float PITCH_DOWN = 45.0f;
    /** 起跳后至少抬头这么多度：滑翔初速为 0，不先换点高度会一路贴地 */
    private static final float TAKEOFF_PITCH = 20.0f;
    /** 两次补推的最短间隔（tick，30 = 1.5 秒；与空袭 fireworkCooldown 默认值同档） */
    private static final int BOOST_INTERVAL = 30;
    /** 一趟飞行的最长时长（tick，1200 = 60 秒）——到点还没追上就放弃 */
    private static final long MAX_TICKS = 1200L;
    /** "补推"日志限频（tick，100 = 5 秒）——补推每次都会发生，不能每 1.5 秒刷一行 */
    private static final long LOG_INTERVAL = 100L;
    /**
     * "起飞/结束"这两行的限频（tick，100 = 5 秒）。
     *
     * 【六百一十一 新增，起因是新的重启口径】收手距离改成 15 格之后，她在"刚过 15 格"与
     * "刚过触发距离"之间来回滑是常态（滑过去 → 中断 → 再滑出去 → 重启），若不加限频，
     * 这两行会跟着来回刷。**与补推那行的限频分开**（各有各的 5 秒预算），否则补推会把
     * 起降两行挤掉——而这两行正是玩家/验收用来判断"她这一趟到底飞没飞"的证据。
     * **起飞与结束之间也各记各的**（{@link #START_LOG} / {@link #END_LOG} 两张表）：共用一张
     * 表时"起飞 1~2 秒后收手"这种短趟会把**结束行整条吞掉**（绿轮实测踩到）。
     */
    private static final long STATE_LOG_INTERVAL = 100L;

    /** 正在飞行跟随（滑翔位之外的第二判据——收翅猛击那套让位判据的同类，见类注释） */
    private static final Set<UUID> FOLLOWING = new HashSet<>();
    /** 下次可以补推的 gameTime */
    private static final Map<UUID, Long> BOOST_READY = new HashMap<>();
    /**
     * 【实测六百一十二】本趟我们替她放的**最后一枚**助推火箭（没收掉就留在表里）。
     *
     * 【为什么必须记着它】烟花对骑手的推力不是一次性的：它活着的那十几 tick 里**每 tick**
     * 都把骑手速度往 1.7×视线拉（字节码实证见 {@code MaidFlightKit#launchBoostRocket}）。
     * 于是"进到主人身边就把矢量解除"这件事**必须把那枚火箭收掉**——只把速度清零的话，
     * 下一 tick 它又给补回来，等于没解除。收掉之后她只剩滑翔的自然下沉。
     *
     * 【有界性】链一停就 {@code remove}（不 discard——威胁/超时那几种收手要保持动量自然滑翔，
     * 这与"进距离解除矢量"是两件事，见 {@link #releaseThrust}）；女仆卸载走 {@link #forget}。
     */
    private static final Map<UUID, net.minecraft.world.entity.projectile.FireworkRocketEntity>
            BOOST_ROCKET = new HashMap<>();
    /** 本趟起飞时刻（超时用） */
    private static final Map<UUID, Long> STARTED_AT = new HashMap<>();
    /** 我们替她换上的鞘翅：原胸甲物品（收手时原样还回去；她自己本来就穿着鞘翅时不记） */
    private static final Map<UUID, ItemStack> SWAPPED_CHEST = new HashMap<>();
    /**
     * "已收手但还在滑翔下降"的小名单：收手那一刻她若还在空中，胸甲上那件鞘翅必须继续戴着
     * （空中摘 = 自由落体，见 {@link #stop} 注释），于是有一段"行为已停、人还在鞘翅上"的
     * 窗口。落地那一 tick 由 {@link #settleOnGround} 收尾。撞墙免疫与"免耐久"都要认这条。
     */
    private static final Set<UUID> SETTLING = new HashSet<>();
    /** 补推日志限频 */
    private static final Map<UUID, Long> LAST_LOG = new HashMap<>();
    /**
     * 【实测六百一十二】"这一趟是因为**主人进到收手半径内**而停的"——{@link #stop} 据此决定
     * 要不要**解除推进矢量**。
     *
     * 【为什么非得绕这一道】Brain 的行为循环是"先 canContinue、为真才 tick，否则直接 stop"
     * （原版 {@code Behavior.tickOrStop}）——**canContinue 一旦为假，本 tick 的 {@code tick()}
     * 根本不会被调用**。而"主人进到收手半径内"这条判据写在 canContinue 的**末尾**，所以
     * "在 tick 里看到进半径了再解除"那段代码**一次都跑不到**（绿轮实测：起飞 10.0 格、
     * 结束 3.9 格、"她还在滑翔"，但运行日志里根本没有「解除火箭推进矢量」那一行）。
     * 现在改成：canContinue 判出"是因为进半径才停"时把 UUID 记进本表，{@link #stop}
     * 开头照单执行——解除发生在**链真正收手的那一 tick**，时机反而更准。
     *
     * 【为什么威胁/超时那几种不收】本表**只在进半径那一支**写；威胁出现（canContinue 里更靠前的
     * 那条）与超时/缺料都是"撤"，要**保持动量**自然滑翔（见 {@link #releaseThrust} 的注释）。
     */
    private static final Set<UUID> RELEASE_ON_STOP = new HashSet<>();
    /**
     * "起飞 / 结束"两行的限频（**各记各的**，见 {@link #STATE_LOG_INTERVAL}）。
     *
     * 【为什么要分成两张表：绿轮实测踩到的】一开始两行共用一张表，于是"起飞 → 1~2 秒后收手"
     * 这种**又快又短的一趟**里，结束那行被起飞那行的 5 秒预算吃掉——运行日志里只剩「起飞、
     * 补推、已落地」，**偏偏少掉验收要读的「结束（主人 N 格…）」**（实测六百一十一 绿轮第一趟
     * 就是这样：起飞 22.0 格 → 1 秒后收手，结束行一个字都没落盘）。分开之后每类各自 5 秒最多
     * 一行，最坏情况 2 行/5 秒/只，仍然不刷屏，但**任何一趟的起降都能各留一行**。
     */
    private static final Map<UUID, Long> START_LOG = new HashMap<>();
    private static final Map<UUID, Long> END_LOG = new HashMap<>();
    /**
     * 【实测六百一十二】"解除推进矢量"这一行的限频（5 秒，**再开一张表**）。
     *
     * 起因还是六百一十一 那条教训（起降两行共用一张表会把结束行整条吞掉）：本批新增的这行
     * 发生在"进到主人 4 格内"的那一 tick，而结束行就在**下一 tick**——两者若共用预算，
     * 结束行必被吞。所以解除那行单独一张表：一趟最多一行，且永远吃不掉起降两行。
     */
    private static final Map<UUID, Long> RELEASE_LOG = new HashMap<>();
    /** "为什么不起飞"诊断日志限频（tick，200 = 10 秒/只女仆；只在开关开着时才可能记） */
    private static final long SKIP_LOG_INTERVAL = 200L;
    private static final Map<UUID, Long> SKIP_LOG = new HashMap<>();
    /** checkExtraStartConditions 的昂贵判定（背包扫描 + 视线 raycast + 威胁扫描）10 tick 节流，与搭路同款 */
    private static final Map<Integer, Integer> CANUSE_THROTTLE = new HashMap<>();

    /**
     * 调试/验收用的"替代主人"：专用服务器上没有在线玩家，{@code getOwner()} 恒为 null，
     * 这条链就永远触发不了（与实测五百四十五 的"女仆喂女仆"同一类困境）。
     * {@code /maid_smart flyfollow &lt;实体&gt;} 在这里挂一个实体当跟随目标，走的是本类
     * **同一套**判定与飞行链路，只替换目标来源。键用弱引用（女仆卸载即回收）。
     */
    private static final Map<EntityMaid, LivingEntity> DEBUG_TARGET =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 【v1.2.2 实测六百一十四 · 取自粉丝 Roderick32 的「鞘翅赶路」分支】坐标档：
     * {@code /maid_smart elytra_goto <x> <y> <z>} 指定的"要去的地方"（键 = 女仆 UUID）。
     *
     * 【为什么并入本类，而不是照他的分支另起一套】他那份实现自带一条与飞行跟随**平行**的飞行状态机
     * （自己的会话表、自己的 8 个配置项、自己的"没进展"判定、还要 mixin 拦 TLM 的跟随瞬移）——而
     * "主人跑远 → 穿鞘翅追过去"这件事本类已经做了（实测六百〇八～六百一十三：所有模式通用、
     * 鞘翅+烟花+羽扇+位移法术四件都能当燃料、两个省料开关）。所以只取他这条链里本类**确实没有**
     * 的那一件能力：**目标可以是一个坐标**（他给的两个用途都很实在——"想让她飞去那边的高台/浮空岛"，
     * 以及"无头测试服里没有玩家/没有主人，怎么复现整条飞行链路"）。目标来源换了，判定/起飞/补推/
     * 收手**全部复用本类**：一条状态机、一份开关、一份日志口径。
     *
     * 【一次性】到点 / 超时 / 威胁 / 缺料 / 跨维度：链一停就把它摘掉（见 {@link #stop}）——它是
     * "去一趟"，不是"长期驻扎在那儿"；摘掉之后她恢复正常跟随（主人还远的话由 TLM 的瞬移接手）。
     */
    private static final Map<UUID, Vec3> GOTO = new HashMap<>();
    /**
     * 坐标档下单时她所在的维度：她在**别的维度**里时这条目标作废（与"主人跨维度不追"同口径）。
     *
     * 【为什么存维度而不是存 level 对象】静态表不能长期持有 {@code ServerLevel}（存档卸载会漏，
     * 而这张表按 UUID 存活、只在 {@link #forget} 时才清）——存 {@code ResourceKey} 是同一个判据
     * 的无泄漏写法（与 {@code SelfPreservationBehavior} 记攻击者维度同款）。
     */
    private static final Map<UUID, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>>
            GOTO_DIM = new HashMap<>();

    public MaidFlightFollowBehavior() {
        super(Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /* ==================== 开关 ==================== */

    private static boolean cfg() {
        return MaidSmartConfig.FLIGHT_FOLLOW_ENABLED.get();
    }

    /** 起手距离（格，默认 25）：她比目标远这么多才起飞——六百一十五 起与收手距离**拆成两个球** */
    private static double cfgDist() {
        return MaidSmartConfig.FLIGHT_FOLLOW_DIST.get();
    }

    /** 收手距离（格，默认 5，六百一十五 新增）：目标进到这么近就收手 + 解除推进矢量 */
    private static double cfgEndDist() {
        return MaidSmartConfig.FLIGHT_FOLLOW_END_DIST.get();
    }

    private static boolean cfgFirework() {
        return MaidSmartConfig.FLIGHT_FOLLOW_FIREWORK.get();
    }

    private static boolean cfgElytra() {
        return MaidSmartConfig.FLIGHT_FOLLOW_ELYTRA.get();
    }

    /**
     * 六百一十三：位移法术那一支的开关归属——**复用空袭的**「位移法术·起飞/补高」
     * （{@code combat.flightDashClimb}，默认开）。
     *
     * 【为什么不新增一个配置项】"她能不能用位移法术飞"这件事在空袭与飞行跟随里是同一件事
     * （同一张法术表、同一条施法链路），分成两个开关只会造出"空袭能用、跟随不能用"这种
     * 没人想要的中间档；玩家关掉它时想的也是"我不想让她放这个法术"，不是"我不想让她在某个
     * 模式下放"。{@link MaidFlightKit#hasFlightPropellant} 里读的也是这一个。
     */
    private static boolean cfgClimbSpell() {
        return MaidSmartConfig.COMBAT_FLIGHT_DASH_CLIMB.get();
    }

    /* ==================== 对外只读/清理 ==================== */

    /** 她此刻正在"飞行跟随"（同维度拉回让位、搭路让位、落地保护都认这一条） */
    public static boolean isFollowing(EntityMaid maid) {
        try {
            return maid != null && FOLLOWING.contains(maid.getUUID());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 她已经收手、但还在滑翔下降（胸甲上那件鞘翅还没还回去）。
     *
     * 【为什么单独一个判据】收手那一刻如果她还在空中，鞘翅必须继续戴着（空中摘 = 自由落体，
     * 见 {@link #stop} 注释），于是存在一小段"行为已停、人还在鞘翅上"的窗口——撞墙免疫
     * 若不认这一条就会在这段窗口里漏掉她。
     */
    public static boolean isSettling(EntityMaid maid) {
        try {
            return maid != null && SETTLING.contains(maid.getUUID());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 滑翔扣鞘翅耐久这一下要不要免掉（{@link com.maidsmart.mixin.ElytraWearGuardMixin} 用）。
     *
     * 只看两件事：她是女仆、她正在飞行跟随、且"不消耗鞘翅耐久"开关关着（= 玩家要省料）。
     * 任何异常一律 false（不免）——最坏情况是照原版扣耐久，绝不会误伤别人。
     */
    public static boolean shouldSkipElytraWear(Object entity) {
        try {
            if (!(entity instanceof EntityMaid maid)) {
                return false;
            }
            return !cfgElytra() && (isFollowing(maid) || isSettling(maid));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 挂一个"替代主人"（调试/验收入口，见 DEBUG_TARGET 注释）；null = 摘掉 */
    public static void setDebugTarget(EntityMaid maid, LivingEntity target) {
        if (maid == null) {
            return;
        }
        try {
            if (target == null) {
                DEBUG_TARGET.remove(maid);
            } else {
                DEBUG_TARGET.put(maid, target);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 摘掉全部"替代主人"（{@code /maid_smart flyfollow clear} 用；这张表本来就很小） */
    public static void clearDebugTargets() {
        try {
            DEBUG_TARGET.clear();
        } catch (Throwable ignored) {
        }
    }

    /**
     * v1.2.2 实测六百一十四：挂上"要去的地方"（{@code /maid_smart elytra_goto <x> <y> <z>}）。
     *
     * @param dim 下单时**她**所在的维度（不是命令执行者的）——两者不同时这条目标压根不会生效，
     *            命令侧已经把这一条拦下来并报了原因，这里只是把判据存下来。
     */
    public static void setGotoTarget(EntityMaid maid, Vec3 pos,
                                     net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim) {
        if (maid == null || pos == null) {
            return;
        }
        try {
            GOTO.put(maid.getUUID(), pos);
            GOTO_DIM.put(maid.getUUID(), dim);
        } catch (Throwable ignored) {
        }
    }

    /** 摘掉"要去的地方"（一次性目标的清尾；链收手时本类自己会调，见 {@link #stop}） */
    public static void clearGotoTarget(EntityMaid maid) {
        if (maid == null) {
            return;
        }
        try {
            GOTO.remove(maid.getUUID());
            GOTO_DIM.remove(maid.getUUID());
        } catch (Throwable ignored) {
        }
    }

    /** 本趟走的是"坐标档"吗（只影响日志措辞与收尾清目标，判定一字不动） */
    private static boolean isGoto(EntityMaid maid) {
        try {
            return maid != null && GOTO.containsKey(maid.getUUID());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 女仆卸载/死亡/服务器停止：清这个 UUID 的全部状态（收手时的归还由实体侧自己走） */
    public static void forget(UUID maidId) {
        if (maidId == null) {
            return;
        }
        FOLLOWING.remove(maidId);
        BOOST_READY.remove(maidId);
        BOOST_ROCKET.remove(maidId);
        RELEASE_ON_STOP.remove(maidId);
        STARTED_AT.remove(maidId);
        SWAPPED_CHEST.remove(maidId);
        SETTLING.remove(maidId);
        LAST_LOG.remove(maidId);
        START_LOG.remove(maidId);
        END_LOG.remove(maidId);
        RELEASE_LOG.remove(maidId);
        SKIP_LOG.remove(maidId);
        CANUSE_THROTTLE.remove(maidId);
        GOTO.remove(maidId);
        GOTO_DIM.remove(maidId);
        try {
            DEBUG_TARGET.keySet().removeIf(m -> maidId.equals(m.getUUID()));
        } catch (Throwable ignored) {
        }
    }

    public static void clearAll() {
        FOLLOWING.clear();
        BOOST_READY.clear();
        BOOST_ROCKET.clear();
        RELEASE_ON_STOP.clear();
        STARTED_AT.clear();
        SWAPPED_CHEST.clear();
        SETTLING.clear();
        LAST_LOG.clear();
        START_LOG.clear();
        END_LOG.clear();
        RELEASE_LOG.clear();
        SKIP_LOG.clear();
        CANUSE_THROTTLE.clear();
        GOTO.clear();
        GOTO_DIM.clear();
        DEBUG_TARGET.clear();
    }

    /* ==================== 行为本体 ==================== */

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        // 【落地结算】胸甲的归还只允许在"她已经站到地上"时发生（见 settleOnGround 的注释：
        // 空中一摘鞘翅 = 原版下一 tick 就清滑翔位 = 自由落体）。绝大多数 tick 这里零开销。
        settleOnGround(maid);
        if (!cfg()) {
            return false; // 开关关着：完全不介入（也不记日志，默认档零噪音）
        }
        long now = level.getGameTime(); // 诊断日志限频用
        try {
            if (maid.isSleeping() || maid.isPassenger() || maid.isMaidInSittingPose()) {
                return skip(maid, now, "睡觉/骑乘/坐下中"); // 与搭路同口径
            }
            if (maid.isHomeModeEnable()) {
                return skip(maid, now, "守家（home）模式中");
            }
            if (ownFlightBusy(maid)) {
                // 【六百一十二】旧版这里是"她是空袭任务就不起飞"（"它自己有飞行作战链路"）。
                // 那一条被用户否了："2 个空袭状态（未接敌），也可以触发飞行跟随，不做额外抑制。
                // 简单来说，就是目前飞行跟随触发判定推广到所有模式下。" 现在只挡"真在打"。
                return skip(maid, now, "空袭任务正在接敌（这一轮出手不可打断，她自己的飞行链路优先）");
            }
            if (SelfPreservationBehavior.isSelfPreserving(maid)) {
                return skip(maid, now, "自保中");
            }
            if (com.maidsmart.task.BridgeUpBehavior.isTaskOccupied(maid)) {
                return skip(maid, now, "任务占用中（在干活/正在接战）");
            }
            Aim aim = aimOf(maid, level);
            if (aim == null) {
                return skip(maid, now, isGoto(maid)
                        ? "指定的目标点不在她这个维度（跨维度不飞）"
                        : "没有可追的目标（主人离线/跨维度，或调试目标没挂上）");
            }
            double dSq = aim.distSq(maid);
            if (dSq <= cfgDist() * cfgDist()) {
                return skip(maid, now, String.format("距离不够（%.1f 格 ≤ 起手距离 %.1f）",
                        Math.sqrt(dSq), cfgDist())); // 还不够远：走路/搭路足够
            }
            // 昂贵的判定（背包扫描 + 视线 raycast + 威胁扫描）10 tick 一次，与搭路同款节流
            int eid = maid.getId();
            Integer cd = CANUSE_THROTTLE.get(eid);
            if (cd != null && cd > 0) {
                CANUSE_THROTTLE.put(eid, cd - 1);
                return false; // 节流中，不算失败（不记日志）
            }
            CANUSE_THROTTLE.put(eid, 10);
            if (!MaidFlightKit.hasElytra(maid)) {
                return skip(maid, now, "背包里没有可用鞘翅");
            }
            if (!MaidFlightKit.hasFlightPropellant(maid)) {
                // 六百一十一：燃料口径与空袭一致——烟花火箭 **或** 孔雀羽扇任一即可；
                // 六百一十三：再加第三条腿——**能上天的位移法术**（与空袭三件套的第三条同一份
                // 定义，同一个开关 combat.flightDashClimb 管），于是"只带法术书、不带烟花"的
                // 女仆也能起飞。措辞沿用实测五百七十四 的口径「可以飞行的道具」，
                // 三种是哪三种在日志里展开（手册/气泡那边仍然只说「可以飞行的道具」）。
                return skip(maid, now, "背包里没有「可以飞行的道具」"
                        + "（烟花火箭 / 孔雀羽扇 / 能上天的位移法术，三选一）");
            }
            if (!sightOk(maid, aim)) {
                return skip(maid, now, "与目标之间被方块挡住视线"); // 让她自己绕（搭路/走路）
            }
            if (threatNearby(level, maid)) {
                return skip(maid, now, "附近有敌对生物（威胁半径内）");
            }
            return true;
        } catch (Throwable t) {
            return skip(maid, now, "判定抛异常：" + t);
        }
    }

    /**
     * 不起飞时记一条"为什么"（限频 {@link #SKIP_LOG_INTERVAL} tick = 10 秒/只女仆）。
     *
     * 【为什么值得常驻】开关一开，玩家看到的现象就是"她怎么不飞"——没有这一行，判定链上十来个
     * 条件里到底是哪一个没过，玩家与作者都只能猜（这跟"搭路跳过/轰炸跳过"是同一类可核验性问题）。
     * 【为什么不会吵】只在 {@code flightFollow.enabled} 开着时才可能记（默认关 = 一行都没有），
     * 且每只女仆 10 秒最多一条。运行日志搜「飞行跟随跳过」即可。
     */
    private static boolean skip(EntityMaid maid, long now, String why) {
        try {
            UUID id = maid.getUUID();
            Long last = SKIP_LOG.get(id);
            if (last == null || now - last >= SKIP_LOG_INTERVAL) {
                SKIP_LOG.put(id, now);
                com.maidsmart.tool.PromaidLog.log("飞行跟随跳过",
                        com.maidsmart.tool.PromaidLog.nameOf(maid) + " 不起飞：" + why);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        UUID id = maid.getUUID();
        FOLLOWING.add(id);
        BOOST_READY.remove(id);
        STARTED_AT.put(id, gameTime);
        wearElytra(maid, id);
        // 【六百一十四】坐标档的措辞单列一句（"目标点飞远了"不成话）；实体档那一句逐字未动——
        // 它是验收（logs 里搜「飞行跟随」）与 test_spellfly613 / test_flight612 断言的原文。
        Aim aim = aimOf(maid, level);
        String head = isGoto(maid)
                ? "飞向指定坐标（" + fmtDist(maid, aim) + " 格）"
                : "主人飞远了（" + fmtDist(maid, aim) + " 格）";
        logState(START_LOG, maid, id, gameTime, head
                + "，背上鞘翅追过去（燃料=" + fuelLabel(maid)
                + "，烟花=" + (cfgFirework() ? "消耗" : "不消耗")
                + "，位移法术=" + (cfgClimbSpell() ? "开" : "关")
                + "，鞘翅耐久=" + (cfgElytra() ? "照原版扣" : "不消耗") + "）");
    }

    /**
     * 这一趟按什么飞（六百一十一 起燃料有两种，六百一十三 起三种，日志里写清楚是哪一种）。
     *
     * 【为什么值得单独一行日志】"她说自己没烟花却照样飞了"这类疑问，读一行就知道走的是羽扇
     * 还是法术那条；与 {@link #boost} 的取用顺序**严格同序**（扇子 → 烟花 → 位移法术），
     * 所以这行永远与现实一致——不能各写各的顺序，否则日志会撒谎。
     */
    private static String fuelLabel(EntityMaid maid) {
        try {
            if (TwilightFanKit.hasFan(maid)) {
                return "孔雀羽扇（优先）";
            }
            if (MaidFlightKit.hasFirework(maid)) {
                return "烟花火箭";
            }
            // 六百一十三：只剩法术书的女仆（她没有烟花也没有羽扇，门禁是靠法术过的）
            String spell = MaidSpellCastCompat.findClimbSpellIgnoringCooldown(
                    maid, MaidSpellCastCompat.climbSpellIds());
            return spell != null ? "位移法术（" + spell + "）" : "位移法术";
        } catch (Throwable ignored) {
            return "烟花火箭";
        }
    }

    /** 她与目标点的距离（格），给日志用；目标没了就返回 `?`（不抛异常） */
    private static String fmtDist(EntityMaid maid, Aim aim) {
        try {
            if (aim == null) {
                return "?";
            }
            return String.format("%.1f", aim.dist(maid));
        } catch (Throwable ignored) {
            return "?";
        }
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        UUID id = maid.getUUID();
        Aim aim = aimOf(maid, level);
        if (aim == null) {
            return; // canStillUse 会收手
        }
        // ── 起跳滑翔（与空袭的 jumpForLaunch 同一套：先离地，滑翔位才站得住）──
        if (maid.onGround()) {
            faceToward(maid, aim, true);
            Vec3 dm = maid.getDeltaMovement();
            maid.setDeltaMovement(new Vec3(dm.x, 0.42, dm.z));
            MaidFlightKit.setGliding(maid, true);
            return;
        }
        // ── 空中：保持滑翔 + 视线钉在主人身上 + 需要时补一口推进 ──
        MaidFlightKit.setGliding(maid, true);
        if (aim.dist(maid) <= endRadius()) {
            // 目标已经进到收手半径内：本趟到此为止（canStillUse 下一 tick 收手并解除矢量）。
            // 【这段理论上是死代码】canStillUse 先跑、为假就直接 stop（tick 不执行），所以正常
            // 情况下走不到这里；留着当"判定顺序哪天变了"的安全带——真跑到了也只是提前解除一次。
            releaseThrust(level, maid, id, gameTime);
            return;
        }
        faceToward(maid, aim, false);
        if (shouldBoost(maid, aim, gameTime)) {
            boost(level, maid, id, gameTime, aim);
        }
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        UUID id = maid.getUUID();
        if (!cfg()) {
            return false;
        }
        Aim aim = aimOf(maid, level);
        if (aim == null) {
            return false; // 目标没了/跨维度 → 追不上也不该追
        }
        if (maid.isSleeping() || maid.isPassenger() || maid.isMaidInSittingPose() || maid.isHomeModeEnable()) {
            return false;
        }
        if (((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData()
                .getBoolean(SelfPreservationBehavior.PRESERVE_TAG)) {
            return false;
        }
        if (gameTime - STARTED_AT.getOrDefault(id, gameTime) > MAX_TICKS) {
            return false; // 超时收手
        }
        if (!MaidFlightKit.hasElytra(maid) || !MaidFlightKit.hasFlightPropellant(maid)) {
            // 鞘翅飞坏了 / 能飞的道具没了（烟花烧完、没羽扇、法术书也丢了）→ 落地走
            return false;
        }
        if (threatNearby(level, maid)) {
            return false; // 威胁出现：交回战斗/自保（**保持动量**自然滑翔，与"进距离解除矢量"相反）
        }
        if (ownFlightBusy(maid)) {
            // 【六百一十二】链飞到一半她接上敌了（脑内有存活攻击目标，或正在起跳/爬升/猛击/
            // 助推那几段出手里）→ 当场把控制权交回她自己的空袭链路。这是"未接敌才由本链路
            // 接管"的另一半：起飞时挡（checkExtraStartConditions），飞到一半也要让。让了之后
            // 她保持滑翔，空袭那一套会自己接上（它的 canUse 只看目标，不看谁在飞）。
            return false;
        }
        if (aim.dist(maid) > endRadius()) {
            return true; // 还没进到收手半径内 → 本趟继续
        }
        // 主人已经进到收手半径内 → 本趟中断（**不需要贴到身边**，用户口径）。
        // 【解除推进矢量交给 stop()】见 RELEASE_ON_STOP 的注释：canContinue 判假时 tick 不会再跑，
        // 所以这里只登记"原因是进半径"，真正的"收火箭 + 速度归零"在 stop() 开头做。
        RELEASE_ON_STOP.add(id);
        return false;
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        UUID id = maid.getUUID();
        // 【六百一十四】坐标档是**一次性**的：链一停就摘掉目标（到点/超时/威胁/缺料一律如此）。
        // 摘掉之后她恢复正常跟随；不摘的话她会一直"想飞去那个点"（跟着主人走两步又想飞回去）。
        // 【摘的时机：本方法**最后**一步】下面两行日志（解除矢量 / 结束）都要用到"这一趟的瞄准点"，
        // 摘早了它们就只能打「进到主人 ? 格内」——绿轮实测踩到（releaseThrust 里 aimOf 返回 null、
        // isGoto 也变 false，措辞当场退回主人版）。所以先取快照、日志打完再摘。
        Aim aim = aimOf(maid, level);
        boolean gotoMode = isGoto(maid);
        FOLLOWING.remove(id);
        BOOST_READY.remove(id);
        STARTED_AT.remove(id);
        LAST_LOG.remove(id);
        // 【实测六百一十二】本趟若是"主人进到收手半径内"才停的，**这一 tick** 就解除推进矢量
        // （收掉还挂着的助推火箭 + 速度归零；为什么不能在 tick() 里做，见 RELEASE_ON_STOP 注释）
        if (RELEASE_ON_STOP.remove(id)) {
            releaseThrust(level, maid, id, gameTime);
        } else {
            BOOST_ROCKET.remove(id); // 别的收手理由（威胁/超时/缺料）：保持动量自然滑翔，只丢引用
        }
        // 【绝不在空中摘鞘翅——它和"空中清滑翔位"是同一件事】原版 updateFallFlying 每 tick 都要
        // 看胸甲槽里那件鞘翅能不能飞（{@code ItemStack.canElytraFly} + {@code elytraFlightTick}）：
        // 空中把鞘翅摘走，下一 tick 滑翔位就被清掉 = 自由落体（女仆 20 血，实测四百七十二 的教训）。
        // 所以只在【已经落地】时收翅 + 还胸甲；还在空中就把两样都留着，让原版继续把她安全滑下去，
        // 落地那一 tick 由 checkExtraStartConditions 开头的 settleOnGround 收尾。
        boolean grounded = maid.onGround();
        if (grounded) {
            MaidFlightKit.setGliding(maid, false);
            restoreChest(maid, id);
        } else {
            SETTLING.add(id); // 交给 settleOnGround 在她落地那一 tick 收尾
        }
        logState(END_LOG, maid, id, gameTime, "结束（" + (gotoMode ? "目标点" : "主人") + " "
                + fmtDist(maid, aim) + " 格，"
                + (grounded ? "她已落地，胸甲还回去" : "她还在滑翔，落地后自动还回胸甲") + "）");
        // 日志都打完了，这才摘掉坐标档（见本方法开头那段注释：摘早了上面两行会退回主人版措辞）
        if (gotoMode) {
            clearGotoTarget(maid);
        }
    }

    /**
     * 收手时她还在空中 → 那件鞘翅先别摘，等她落地再还（{@link #stop} 的注释里讲了为什么）。
     * 挂在 checkExtraStartConditions 最开头每 tick 探一次：无记录时只做一次 set 查找，零开销。
     */
    private static void settleOnGround(EntityMaid maid) {
        try {
            UUID id = maid.getUUID();
            if (!SETTLING.contains(id)) {
                return; // 绝大多数 tick 走这里（她没在收手后继续滑翔）
            }
            if (!maid.onGround() || FOLLOWING.contains(id)) {
                return; // 还在空中，或者她这次又飞起来了（飞行中由行为自己管）
            }
            SETTLING.remove(id);
            restoreChest(maid, id);
            MaidFlightKit.setGliding(maid, false);
            com.maidsmart.tool.PromaidLog.log("飞行跟随",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 已落地，把胸甲还回去");
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 起飞/操纵 ==================== */

    /**
     * 本趟"够了"的半径（格）：目标进到这么近就中断本趟链路 + **解除推进矢量**。
     *
     * 【六百一十五：起手与收手是两个不同的球】用户原话："开始跟结束两个半点的球大小应该不一样，
     * 默认值就是我说的那两个（起手 25 / 收手 5）。" 它要治的是**旧版只有一个判定球**的毛病：
     * 六百一十二 起收手半径是"起手距离 − 1"推导出来的，于是迟滞只有 1 格——她在"刚过起手线"与
     * "刚进收手线"之间来回滑，就是常态（滑出去 → 起飞 → 走两步又进线 → 收手 → 再滑出去……）。
     * 现在两个数**各读各的配置**：起手 {@link #cfgDist()}（默认 25）、收手 {@link #cfgEndDist()}
     * （默认 5），默认档留 20 格迟滞。
     *
     * 【为什么要压一道上限】收手半径 ≥ 起手距离时，她会"一起飞就已经在收手半径内"= 起飞即刻收手、
     * 一次都飞不起来（旧版那个"减 1"就是为了防这个）。所以这里仍取 {@code min(收手, 起手 − 1)}，
     * 下界 1：玩家把两个数都调到很小（比如起手 3 / 收手 5）时自动退化成"起飞即收手"的边界档，
     * 而**不是**静悄悄地什么都不做。默认 25 / 5 用不到这条兜底。
     *
     * 【六百一十五 删掉了一个常量】旧版这里写着 {@code END_RADIUS = 15}（六百一十一 定的"收手半径
     * 上限"，六百一十二 把起手距离降到 5 之后它实际只在"起手距离 ≥ 16"时才生效）——收手半径本批
     * 独立成一个配置之后，那个写死的上限就没有存在意义，连同它的 {@code min(15, …)} 一起删掉了。
     */
    private static double endRadius() {
        double start = cfgDist();
        return Math.max(1.0, Math.min(cfgEndDist(), Math.max(1.0, start - 1.0)));
    }

    /**
     * 【实测六百一十二】进到主人身边 → **解除烟花（/羽扇）给的推进矢量**。
     *
     * 用户口径："在距离内检测到主人之后解除烟花带来的矢量（之前空袭状态下是不解除，在此模式下
     * 改为解除）。" 两件事一起做，缺一不可：
     * <ol>
     *   <li><b>收掉还挂在背上的那枚助推火箭</b>（{@link #BOOST_ROCKET}）——烟花不是"一次性给一份
     *       速度"，而是它活着的那十几 tick 里每 tick 都把骑手速度往 1.7×视线拉（字节码实证见
     *       {@code MaidFlightKit#launchBoostRocket}）。不收掉它，下面那句清零下一 tick 就被
     *       它补回来，"解除"等于白做；</li>
     *   <li><b>速度归零</b>。滑翔位**不动**：她仍然"在滑翔"，只是没有推进——原版
     *       {@code LivingEntity.travel} 的滑翔分支会把她自然带下去（水平没有推力就一直很小，
     *       下沉被那一支的升力项压得很慢，`checkSlowFallDistance` 顺带清坠落距离，所以既不摔伤
     *       也不"往前冲"）。这正是用户要的"自然滑翔"，与旧版"抬头泄速刹车"（{@code FLARE_PITCH}
     *       那套，六百一十一 已整段删除）**不是一回事**：那套是"主动减速但仍然朝你飞"，
     *       这一条是"把推进整份解掉"。</li>
     * </ol>
     *
     * 【六百一十三：位移法术给的那一份也一并解掉】法术是**瞬发**改速度（没有烟花那种"活着期间
     * 每 tick 都推"的残留），所以下面那句"速度归零"天然把法术给的那一份也解掉了——这点与烟花
     * 不同（烟花必须额外收掉那枚火箭，见上）。两句合起来看就是"进半径 → 推进整份归零"。
     *
     * 【与威胁收手的区别（刻意不一致）】威胁出现（canStillUse 里那条）是"撤"——**保持动量**
     * 自然滑翔；本方法只用于"主人已经进到收手半径内"这一种收手，是"收"。空袭那边**两处都不解除**
     * （她要那一份动量贴脸、扑击），所以本方法只属于本模式。
     *
     * 【调用点】{@link #stop}（链收手那一 tick，由 {@link #RELEASE_ON_STOP} 点名）——
     * **不能写在 tick() 里**：Brain 的 {@code Behavior.tickOrStop} 先问 canStillUse，为假就直接
     * stop、本 tick 的 tick() 不执行，而"进半径"正是 canStillUse 末尾那条（绿轮实测踩到）。
     *
     * 【为什么是"清零"而不是"减掉烟花那一份"】烟花给的是叠加在滑翔速度上的插值
     * （`v = v*0.5 + look*0.85`），来源无法从 `getDeltaMovement()` 里分离出来；而用户要的语义
     * 就是"到你身边不要再有推进"，清零即达意。清零之后她立刻变成零速滑翔，落地那一 tick 由
     * {@link #settleOnGround} 收尾。
     */
    private static void releaseThrust(ServerLevel level, EntityMaid maid, UUID id, long gameTime) {
        try {
            boolean dropped = false;
            net.minecraft.world.entity.projectile.FireworkRocketEntity rocket = BOOST_ROCKET.remove(id);
            if (rocket != null && rocket.isAlive()) {
                rocket.discard(); // 停掉它每 tick 那一下推力
                dropped = true;
            }
            Vec3 v = maid.getDeltaMovement();
            double speed = Math.sqrt(v.x * v.x + v.z * v.z);
            maid.setDeltaMovement(Vec3.ZERO);
            logState(RELEASE_LOG, maid, id, gameTime, "进到" + (isGoto(maid) ? "目标点" : "主人") + " "
                    + fmtDist(maid, aimOf(maid, level))
                    + " 格内，解除火箭推进矢量（" + (dropped ? "收掉还挂着的烟花，" : "")
                    + "水平速度 " + String.format("%.2f", speed) + " → 0），改自然滑翔");
        } catch (Throwable ignored) {
        }
    }

    /**
     * 本 tick 该不该补一口推进：比主人低、或者速度掉了（滑翔没速度就等着掉高）。
     *
     * ── 【实测六百一十二 第 4 条：她到底什么时候烧烟花？】──
     * 用户原话："我需要确认一点，在此类状态下，女仆在什么时候会使用烟花火箭这类推进呢？"
     * 答案是**只有三个闸门同时开着**的时候（本方法 + {@link #BOOST_INTERVAL} 冷却 + 燃料口径）：
     * <ol>
     *   <li><b>本趟还没进到 {@link #endRadius()} 格内</b>（进了就不再推，还会把已给的矢量解除）；</li>
     *   <li><b>距离上次补推 ≥ {@link #BOOST_INTERVAL}（30 tick = 1.5 秒）</b>；</li>
     *   <li><b>她比主人低 0.5 格以上，或者水平速度掉到 0.35 格/tick 以下</b>——后者就是"起飞那一下"：
     *       刚跳起来时水平速度是 0，所以**起飞后第一口推进几乎是立刻发生的**；之后滑翔靠视线
     *       操纵维持速度，速度掉下来才补。</li>
     * </ol>
     * 也就是说：**不是"每隔几秒固定烧一枚"，而是"掉了才补"**。背包里有孔雀羽扇时这一口走扇子
     * （不烧烟花，见 {@link #boost}）；两者都没有就没得补，本趟会因燃料门禁收手。
     *
     * 【六百一十一：删掉了"最后十几格就松油门"那一条】旧版要靠它压住"离得近还补推 → 超车 →
     * 掉头 → 再超车"的绕圈（六百〇八 实测吃过两次：22 格的目标飞了 43 秒、烧掉 29 枚烟花）。
     * 收手半径提到 15 格（六百一十二 起默认实际 4 格）之后，**这一趟根本进不到"最后十几格"**
     * （进半径就中断了），那条判据成了死代码，索性去掉；剩下的两条就是"该补才补"。
     */
    private static boolean shouldBoost(EntityMaid maid, Aim aim, long gameTime) {
        Long ready = BOOST_READY.get(maid.getUUID());
        if (ready != null && gameTime < ready) {
            return false;
        }
        Vec3 v = maid.getDeltaMovement();
        double speed = Math.sqrt(v.x * v.x + v.z * v.z);
        double dy = aim.y - maid.getY();
        // 【六百一十一：删掉了"最后十几格就松油门"那一条】旧版要靠它压住"离得近还补推 → 超车 →
        // 掉头 → 再超车"的绕圈（六百〇八 实测吃过两次：22 格的目标飞了 43 秒、烧掉 29 枚烟花）。
        // 现在收手半径从 4 格提到 15 格，**这一趟根本进不到"最后十几格"**（进 15 格就中断了），
        // 那条判据成了死代码，索性去掉；剩下的两条就是"该补才补"：低于主人（爬升）或速度掉了。
        return dy > 0.5 || speed < 0.35;
    }

    /**
     * 补一口推进：**有孔雀羽扇先挥扇**（与空袭 {@code tryLaunch} 同序），没扇才烧烟花，
     * 烟花也拿不出来时用**位移法术**顶上（六百一十三）。
     *
     * 【为什么扇子优先】空袭从实测五百六十三 起就是"有扇用扇"（扇子按它自己的公式推进、
     * 扣它自己的耐久）。飞行跟随是同一件事（都只是"给滑翔补一口推力"），口径必须同源，
     * 否则同一个背包在两套模式里会烧不同的东西。
     *
     * 【为什么法术排在最后（六百一十三 的口径）】用户原话是"位移法术也可以加入到飞行跟随的
     * **启动**中"——要的是"只带法术书也能飞"这条腿，**不是**"法术抢烟花的活"。这个顺序正是
     * 空袭**起飞**那一套（"有烟花先走烟花链路、没有才用位移法术"，见 {@code tryLaunch} →
     * {@code tryDashClimb}）：带烟花的存档手感与烧料节奏一字不变，法术只在"扇子不在、
     * 烟花也真拿不出来"时接手——消耗档把最后一枚烧完之后的下一脚，就是它。
     */
    private static void boost(ServerLevel level, EntityMaid maid, UUID id, long gameTime,
                              Aim aim) {
        if (TwilightFanKit.hasFan(maid)) {
            if (!TwilightFanKit.boostGlide(level, maid)) {
                return; // 扇子挥不动（异常/扇子没了）——这一 tick 就算了
            }
            // 必须置位：滑翔每 tick 吃朝向，扇子那一口速度也只在滑翔状态下站得住（同空袭）
            MaidFlightKit.setGliding(maid, true);
            BOOST_READY.put(id, gameTime + BOOST_INTERVAL);
            logThrottled(maid, id, gameTime, "挥羽扇追主人");
            return;
        }
        ItemStack display;
        boolean haveFirework;
        if (cfgFirework()) {
            display = MaidFlightKit.takeFirework(maid);
            haveFirework = !display.isEmpty();
            // 【六百一十三：这一支不再就地收手】旧版"背包真空了"直接 return（交给 canStillUse
            // 的燃料门禁收手）；现在继续往下试位移法术——她可能只是"烟花烧完了、法术书还在"。
        } else {
            // 不消耗档：**照旧要求背包里有能飞的道具**（它是"能飞"的凭证），但不扣那一枚
            display = new ItemStack(Items.FIREWORK_ROCKET);
            haveFirework = MaidFlightKit.hasFirework(maid);
        }
        if (haveFirework) {
            // 六百一十二：把这枚火箭记下来——进到主人身边要**收掉它**才算"解除推进矢量"
            // （见 releaseThrust 与 BOOST_ROCKET 的注释：烟花每 tick 都在推，不收掉它清零就白清）
            net.minecraft.world.entity.projectile.FireworkRocketEntity rocket =
                    MaidFlightKit.launchBoostRocket(level, maid);
            if (rocket != null) {
                BOOST_ROCKET.put(id, rocket);
                FlightFireworkPose.show(maid, display);
                BOOST_READY.put(id, gameTime + BOOST_INTERVAL);
                logThrottled(maid, id, gameTime, "补一枚烟花追主人（烟花="
                        + (cfgFirework() ? "消耗" : "不消耗") + "）");
                return;
            }
            // 没放成（异常/世界拒绝）——继续往下试法术，别因为一次异常白丢这一口推进
        }
        // ③ 位移法术（六百一十三）：扇子不在、烟花也拿不出来时的第三条腿
        castClimbSpell(maid, aim, id, gameTime);
    }

    /**
     * ③ 用位移法术补一口（v1.2.2 实测六百一十三）——顺序与理由见 {@link #boost}。
     *
     * ── 朝向：照抄空袭"平地起飞/补高"那一套（两个坑都要躲）──
     * <ol>
     *   <li>先 {@link MaidSpellCastCompat#clearCastTarget}：法术模组施法前会
     *       {@code forceLookAtTarget}，把她的朝向拧向它自己那份目标——**起飞那一枪会被掰平**
     *       （{@code AscensionSpell} 取的是 {@code getLookAngle()}，朝前一平就从"窜上天"
     *       变成"向前扑"）；清掉目标它就跳过这一步；</li>
     *   <li>再 {@link #faceToward}(…, takeoff=true)：**抬头至少 {@link #TAKEOFF_PITCH} 度、
     *       瞄着主人**。空袭的口径是"起飞/补高一律抬头瞄着放"（{@code tryDashClimb} 里的
     *       {@code faceLaunchDirection}），这里不发明新角度——本链路烟花那一口用的朝向本来
     *       就是"看着主人"（实测五百七十八 的教训：位移法术的朝向一律取自"该相位烟花会用的
     *       朝向"），只多一个抬头下限，保证她不会越飞越低。</li>
     * </ol>
     *
     * ── 节流与冷却 ──
     * 本地节流用本链路自己的 {@link #BOOST_INTERVAL}（30 tick），与烟花**共用同一张**
     * {@link #BOOST_READY} 表——它们抢的是同一种"这一口推进"，各记一张表等于同一 tick 能放两口。
     * 写回法术模组冷却表用 {@code combat.flightDashInterval}（40 tick），与空袭"提供高度"那一类
     * 的写回值完全一致（那边同样**不看法术自身冷却**，依据见
     * {@link MaidSpellCastCompat#findClimbSpellIgnoringCooldown}：不这样就满足不了
     * "没有烟花也能持续飞"）。法术不消耗物资，所以"消耗烟花"那个开关对它无意义。
     *
     * @return true = 这一口放出去了
     */
    private static boolean castClimbSpell(EntityMaid maid, Aim aim, UUID id,
                                          long gameTime) {
        try {
            if (!cfgClimbSpell()) {
                return false; // 玩家关了「位移法术·起飞/补高」→ 本链路也退回只认烟花/羽扇
            }
            // 【实测六百一十四】挑之前先校验两张法术表：id 写错时本链路只会"静默跳过"，
            // 看不出任何原因（这条诊断取自粉丝 Roderick32 的「鞘翅赶路」分支）。
            MaidSpellCastCompat.checkSpellTables();
            String spell = MaidSpellCastCompat.findClimbSpellIgnoringCooldown(
                    maid, MaidSpellCastCompat.climbSpellIds());
            if (spell == null) {
                return false; // 她书里没有这张表里的法术（或法术模组不在场/版本不符）
            }
            MaidSpellCastCompat.clearCastTarget(maid); // 别让它施法前把朝向拧平（同空袭）
            faceToward(maid, aim, true);               // 抬头瞄着主人放（至少 TAKEOFF_PITCH）
            int lvl = MaidSpellCastCompat.spellLevelOrDefault(maid, spell);
            if (!MaidSpellCastCompat.castSpecific(maid, spell, lvl,
                    MaidSmartConfig.COMBAT_FLIGHT_DASH_INTERVAL.get())) {
                return false; // 前置条件不过/世界拒绝——这一 tick 就算了
            }
            MaidFlightKit.setGliding(maid, true); // 滑翔位每 tick 都要站住（同扇子那条）
            BOOST_READY.put(id, gameTime + BOOST_INTERVAL);
            logThrottled(maid, id, gameTime, "放位移法术追主人（" + spell + " Lv" + lvl + "）");
            return true;
        } catch (Throwable ignored) {
            return false; // 任何异常都当"这一口没有"（canStillUse 的燃料门禁会自己收手）
        }
    }

    /** 补推日志：同一只女仆 {@link #LOG_INTERVAL}（5 秒）最多一行 */
    private static void logThrottled(EntityMaid maid, UUID id, long gameTime, String what) {
        try {
            if (gameTime - LAST_LOG.getOrDefault(id, Long.MIN_VALUE / 2) < LOG_INTERVAL) {
                return;
            }
            LAST_LOG.put(id, gameTime);
            com.maidsmart.tool.PromaidLog.log("飞行跟随",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " " + what);
        } catch (Throwable ignored) {
        }
    }

    /**
     * "起飞/结束"两行：**每类各自** {@link #STATE_LOG_INTERVAL}（5 秒）最多一行。
     *
     * 【六百一十一 新增，起因是新的重启口径】收手半径提到 15 格之后，她会在"滑出去 → 重启 →
     * 滑回来 → 中断"之间反复，这两行要限频；但又不能跟补推那行共用一个预算（补推会把起降挤掉，
     * 而那两行才是"她这一趟到底飞没飞"的证据）。
     * 【两行也不能共用一张表】详见 {@link #START_LOG} 的注释：共用时"起飞 1 秒后收手"这种短趟
     * 会把结束行整条吞掉（绿轮实测）。
     */
    private static void logState(Map<UUID, Long> table, EntityMaid maid, UUID id, long gameTime,
                                 String what) {
        try {
            if (gameTime - table.getOrDefault(id, Long.MIN_VALUE / 2) < STATE_LOG_INTERVAL) {
                return;
            }
            table.put(id, gameTime);
            com.maidsmart.tool.PromaidLog.log("飞行跟随",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " " + what);
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 朝向 ==================== */

    /** 把视线钉在目标点上（滑翔的操纵杆就是视线）；takeoff=true 时至少抬头 TAKEOFF_PITCH 度 */
    private static void faceToward(EntityMaid maid, Aim aim, boolean takeoff) {
        double dx = aim.x - maid.getX();
        double dz = aim.z - maid.getZ();
        double dh = Math.sqrt(dx * dx + dz * dz);
        double eyeT = aim.centerY();
        double eyeM = maid.getY() + maid.getBbHeight() * 0.5;
        float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) (-(Math.atan2(eyeT - eyeM, Math.max(1.0E-4, dh)) * (180.0 / Math.PI)));
        pitch = Math.max(-PITCH_UP, Math.min(PITCH_DOWN, pitch));
        if (takeoff) {
            pitch = Math.min(pitch, -TAKEOFF_PITCH); // 起跳那一下先抬头（负 = 抬头）
        }
        applyRotation(maid, yaw, pitch, aim);
    }

    /**
     * 立即把身体与头转向指定角度，并把"期望角度"交给 LookControl。
     *
     * 【为什么不能只调 lookAt】LookControl 每 tick 会把 xRot 归零（1.20.1 与 1.21.1 都一样，
     * 反编译实证见 {@code MaidFlightCombatBehavior.faceAwayAndUp}），只写期望值下一 tick 就被
     * 抹平 —— 所以既要直接写实体（含 O 值，渲染插值用），也要交给 LookControl 让它每 tick 重施加。
     *
     * 【实测六百一十四：坐标档走坐标重载】实体档照旧用 {@code setLookAt(Entity, …)}（它按对方的
     * **眼睛**高度算，与原本口径一致）；坐标档没有实体，改用 {@code setLookAt(double, double,
     * double, …)} 传那个点本身。两条路径写进去的都是"她这一 tick 的视线"，滑翔的转向完全一致。
     */
    private static void applyRotation(EntityMaid maid, float yaw, float pitch, Aim aim) {
        maid.setYRot(yaw);
        maid.setXRot(pitch);
        maid.yRotO = yaw;
        maid.xRotO = pitch;
        maid.setYHeadRot(yaw);
        maid.setYBodyRot(yaw);
        try {
            if (aim.entity != null) {
                maid.getLookControl().setLookAt(aim.entity, 360.0f, 360.0f);
            } else {
                maid.getLookControl().setLookAt(aim.x, aim.y, aim.z, 360.0f, 360.0f);
            }
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 装备/归还 ==================== */

    /**
     * 背上鞘翅（她自己本来就穿着可用鞘翅时一字不动）。
     *
     * 【为什么要"记原物、收手还原"】飞行跟随是**跟着走路的女仆**的观赏玩法——她本来可能穿着
     * 普通胸甲（甚至钻石胸甲），我们不能飞完就把玩家的护甲弄丢/长期占着胸甲槽。所以换下的那件
     * 记在 {@link #SWAPPED_CHEST}，收手时原样还回去（鞘翅回背包）。
     */
    private static void wearElytra(EntityMaid maid, UUID id) {
        try {
            if (MaidFlightKit.isElytraLike(maid.getItemBySlot(EquipmentSlot.CHEST), maid)) {
                return; // 已经穿着
            }
            ItemStack ely = MaidFlightKit.takeElytra(maid);
            if (ely.isEmpty()) {
                return;
            }
            ItemStack old = maid.getItemBySlot(EquipmentSlot.CHEST);
            maid.setItemSlot(EquipmentSlot.CHEST, ely);
            SWAPPED_CHEST.put(id, old.isEmpty() ? ItemStack.EMPTY : old.copy());
            if (!old.isEmpty()) {
                com.maidsmart.tool.MaidGiveBack.give(maid, old, "飞行跟随换下胸甲");
            }
        } catch (Throwable ignored) {
        }
    }

    /** 收手归还：只有"胸甲槽还保持着我们给她穿的鞘翅"才动它——玩家中途换过的东西一律不覆盖 */
    private static void restoreChest(EntityMaid maid, UUID id) {
        try {
            ItemStack orig = SWAPPED_CHEST.remove(id);
            if (orig == null) {
                return; // 她本来就穿着鞘翅（我们没换过）
            }
            ItemStack now = maid.getItemBySlot(EquipmentSlot.CHEST);
            if (!MaidFlightKit.isElytraLike(now, maid)) {
                if (!orig.isEmpty()) {
                    com.maidsmart.tool.MaidGiveBack.give(maid, orig, "飞行跟随结束还回胸甲");
                }
                return;
            }
            maid.setItemSlot(EquipmentSlot.CHEST, orig);
            if (!now.isEmpty()) {
                com.maidsmart.tool.MaidGiveBack.give(maid, now, "飞行跟随结束，还回鞘翅");
            }
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 目标/环境 ==================== */

    /**
     * 本趟"要去的那个点"（v1.2.2 实测六百一十四）——两种来源，几何上统一成一个点：
     * <ul>
     *   <li><b>实体档</b>：主人，或 {@code /maid_smart flyfollow} 挂的"替代主人"（{@link #DEBUG_TARGET}）
     *       ——{@link #entity} 非空；</li>
     *   <li><b>坐标档</b>：{@code /maid_smart elytra_goto} 指定的固定坐标（{@link #GOTO}）
     *       ——{@link #entity} 为 null。</li>
     * </ul>
     *
     * 【为什么值得抽出来】本链路真正用到目标的只有三件事：**距离、高差、朝向**（滑翔的操纵杆就是
     * 视线）——全是坐标；只有两处需要实体：判"还在不在/同不同维度"，以及 LookControl 的实体重载
     * （坐标档改用坐标重载，两者写在 {@link #applyRotation} 里）。抽成一个点之后，"追主人"与
     * "飞去某地"共用同一条起飞/补推/收手链路——**这正是我们没有照搬粉丝那份实现的原因**：
     * 他那版为"飞去某地"又养了一整套平行的飞行状态机（自己的配置项、自己的没进展判定）。
     */
    private static final class Aim {
        /** 实体档的实体；坐标档为 null */
        final LivingEntity entity;
        /** 脚底坐标（实体档 = {@code entity.getX/Y/Z()}，与改动前的取值口径完全一致） */
        final double x, y, z;
        /** 碰撞箱高度（坐标档 = 0）：朝向判定要"瞄身体中心"，见 {@link #centerY()} */
        final double height;

        private Aim(LivingEntity entity, double x, double y, double z, double height) {
            this.entity = entity;
            this.x = x;
            this.y = y;
            this.z = z;
            this.height = height;
        }

        static Aim ofEntity(LivingEntity e) {
            return new Aim(e, e.getX(), e.getY(), e.getZ(), e.getBbHeight());
        }

        static Aim ofPos(Vec3 p) {
            return new Aim(null, p.x, p.y, p.z, 0.0);
        }

        /** 身体中心的高度（改动前写的是 {@code target.getY() + target.getBbHeight() * 0.5}） */
        double centerY() {
            return y + height * 0.5;
        }

        /** 她到这个点的距离（格）——与改动前的 {@code maid.distanceTo(target)} 完全同一个值 */
        double dist(EntityMaid maid) {
            return Math.sqrt(distSq(maid));
        }

        /** 距离的平方（门槛判定用，省一次开方） */
        double distSq(EntityMaid maid) {
            return maid.distanceToSqr(x, y, z);
        }

        Vec3 pos() {
            return new Vec3(x, y, z);
        }
    }

    /**
     * 本 tick 要去的点：**坐标档优先**，其次"替代主人"（调试/验收），最后主人；null = 没得追。
     *
     * 【口径与改动前逐条对齐】实体档那两条：调试目标活着就用它、否则退回主人；再统一要求
     * "活着 + 同一个 level"（原来是各调用点自己判，现在收在这里一处）。坐标档多一条维度判据
     * （她换维度 = 这条目标作废，同"主人跨维度不追"）。
     */
    private static Aim aimOf(EntityMaid maid, net.minecraft.world.level.Level level) {
        try {
            UUID id = maid.getUUID();
            Vec3 pos = GOTO.get(id);
            if (pos != null) {
                if (!level.dimension().equals(GOTO_DIM.get(id))) {
                    return null; // 跨维度：坐标目标作废
                }
                return Aim.ofPos(pos);
            }
            LivingEntity dbg = DEBUG_TARGET.get(maid);
            LivingEntity e = (dbg != null && dbg.isAlive()) ? dbg : maid.getOwner();
            if (e == null || !e.isAlive() || e.level() != level) {
                return null;
            }
            return Aim.ofEntity(e);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 与目标点之间没有方块阻拦（实体档走原签名；坐标档走实测六百一十四 新增的坐标重载） */
    private static boolean sightOk(EntityMaid maid, Aim aim) {
        try {
            if (aim.entity != null) {
                return SelfPreservationBehavior.hasSight(maid, aim.entity);
            }
            return SelfPreservationBehavior.hasSight(maid, aim.pos());
        } catch (Throwable ignored) {
            return true; // 与 hasSight 的兜底一致：判不出来就当她看得见（宁可不飞也不误判）
        }
    }

    /**
     * 【实测六百一十二】她自己的空袭链路是不是**正在打**（未接敌 = 放行给飞行跟随）。
     *
     * 需求原文第 2 条："2 个空袭状态（未接敌），也可以触发飞行跟随，不做额外抑制。简单来说，
     * 就是目前飞行跟随触发判定推广到所有模式下。"
     *
     * 所以旧版那句"**她是空袭任务就不起飞**"（原话"它自己有飞行作战链路"）被拆成"接敌与否"：
     * <ul>
     *   <li>{@code MaidFlightCombatBehavior.isEngaged}——起跳/烟花爬升/收翅猛击/等待再放烟花/
     *       垂直占位爬升/远程俯冲助推任一在表里 = 这一轮出手**不能被打断**（与实测四百八十七
     *       给"自动传送"写的那条让位同源）。正在出这一手时不起飞，也不接管（不然两个链路会
     *       同 tick 各给一个速度，就是"拉扯"）；</li>
     *   <li>脑内还有**存活的** {@code ATTACK_TARGET} = 正在接敌（与搭路 {@code isTaskOccupied}
     *       完全同口径）。checkExtraStartConditions 里那道 isTaskOccupied 已经覆盖了它，这里再判
     *       一次是为了 canStillUse：**链飞到一半她接上敌了，本趟要当场交回**给她自己的空袭链路。</li>
     * </ul>
     * 【为什么只判这两样 = "不做额外抑制"】两个判据都不看"她挂的是哪个任务"——任务名不再是
     * 理由（空闲/战斗/挖矿/烹饪/搭路……一视同仁），只有"真在打"（上面两条）与"真有活干"
     * （checkExtraStartConditions 里那道 {@code isTaskOccupied}：挖矿/伐木/建造/站桩工作/追杀）
     * 才让位。两个空袭任务**未接敌**的那段时间（她自己的行为没在跑、索敌没锁到人）从此归本链路管。
     *
     * 【为什么读脑内记忆而不是调 {@code FlightTargeting.resolve}】后者会**顺手选定并回写**
     * 目标（它自己就是索敌器），从"搭路/跟随"这一档去调它等于我们替空袭开了一次火；
     * 读记忆没有副作用，与 isTaskOccupied 读的是同一格数据。
     */
    private static boolean ownFlightBusy(EntityMaid maid) {
        if (!MaidFlightKit.isFlightTask(maid)) {
            return false; // 非空袭任务：这一条整个不适用（本来就没有"她自己的飞行链路"）
        }
        try {
            if (com.maidsmart.combat.MaidFlightCombatBehavior.isEngaged(maid)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            var mem = maid.getBrain().getMemory(
                    net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET);
            return mem.isPresent() && mem.get().isAlive();
        } catch (Throwable ignored) {
            return false; // 读不到记忆时按"没接敌"处理（宁可让她飞，也不误杀她的空袭）
        }
    }

    /** 周围 bridge.threatDist 格内有敌对生物（与搭路同口径：绝不往怪堆里飞） */
    private static boolean threatNearby(ServerLevel level, EntityMaid maid) {
        double r = MaidSmartConfig.BRIDGE_THREAT_DIST.get();
        for (net.minecraft.world.entity.Entity e : level.getEntitiesOfClass(
                net.minecraft.world.entity.Entity.class, maid.getBoundingBox().inflate(r), x -> true)) {
            if (e.isAlive() && e instanceof Enemy) {
                return true;
            }
        }
        return false;
    }
}
