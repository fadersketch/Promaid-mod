package com.maidsmart.tool;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;

/**
 * 实测四百四十三【悬空禁搭方块】（v1.2.0 实测四百九十八收紧）——
 * 反馈原文："女仆在处于悬空状态下的时候应该禁止搭建方块（这个机制在挖矿和伐木的
 * 时候也通用，有时候就是因为下落悬空的时候搭方块又放不了落地水，导致自己被摔死
 * 了）。"；实测四百九十八追加反馈："女仆似乎还是会在悬空和滑翔状态下搭方块。"
 *
 * ── 四百四十三为什么没拦住（两处漏口）──
 * 旧判定的口径是"未落地 **且**（重锤跃起 **或** 坠落距离 ≥ 落地水触发高度）"：
 * 1. **滑翔被显式放行**：`isFallFlying()` 与"站在地面/水里/骑乘"并列在放行名单里
 *    ——可空袭期间她本来就在滑翔，于是"滑翔中搭方块"这条被完全放行；
 * 2. **普通悬空只拦深坠落**：阈值是 `combat.waterFallDistance`（默认 6 格）——
 *    3 格、5 格的坠落照样放行。而滑翔中 `LivingEntity.travel` 每 tick 调
 *    `Entity.m_245125_()` 把 `fallDistance` **钳在最多 1.0**，所以即便把滑翔从
 *    放行名单里拿掉、只留这条阈值，滑翔期也**永远不会**触发——必须单独判滑翔位。
 *
 * ── 现在的判定（下面全部满足才禁）──
 * - 开关 `misc.noPlaceInAir`（默认开）；
 * - 未落地（`onGround=false`）——站在地上搭方块是各模块的常态，一律放行；
 * - 不在水/岩浆里（水里搭方块是正常操作；**岩浆里垫方块自救更是保命行为**——
 *   实测五百零九确认了这条必须留，见 blocked 内注释）；没骑乘；
 * - 且满足任一条：
 *   a) 滑翔中 / 飞行作战空中（`MaidFlightKit.isGliding` / `isFlightAirborne`）——
 *      **这是本次新增的主漏口**。滑翔（`fallDistance` 被钳在 1.0）、收翅猛击、
 *      被击落盘旋等"飞行期"一律不搭；空袭期间搭桥/垫脚既无意义又会抽掉她脚下的
 *      落点结构、挡住落地水。
 *   b) 重锤跃起中（`MaidMaceSmashBehavior.isAirborne`）——整段空中都禁
 *      （上升段也算：起跳瞬间乱搭同样会破坏猛击节奏）。
 *   c) 【v1.2.0 实测五百五十已改】真·悬空（脚下 3 格内没有可站立面）。四百九十八
 *      当时写的是"只要 fallDistance > 0 就禁"，把**她自己一跳**（战术跳劈、走位跨
 *      台阶、搭高"放块→顶起→落回"）也判成了悬空——自保搭高在围殴中一放不下、
 *      二还因为 443 的静默设计不吭声（详见下面 blocked 的类注释）。现在恢复四百
 *      四十三的深坠落阈值（≥ 落地水触发高度）+ 单独补一条"脚下无地"。
 *      外加一条低空豁免：离地只有一两格时（如搭高"放块→顶起→落回"、被击退后
 *      的落地前几 tick）不算悬空。
 *
 * 【统一闸口】四个搭方块模块（自保搭高·搭路·挖矿垫脚·伐木垫脚）都在各自的
 * takeBuildBlock 取料前问一次本判定：被禁 → 返回 null → 各模块自然放弃本次
 * 放置（不消耗方块、不动世界）。"背包里没有搭方块的材料"这类取料失败气泡也
 * 同样静默，避免悬空时误报。
 *
 * 【不拦】落地水/细雪本身（WaterClutchBehavior 直接放水，不走这里——它是保命
 * 机制，正是用来防摔死的）、照明光块（MaidHeldLight，非"搭建"语义且不消耗物品）、
 * 农耕播种——这些要么是保命机制、要么与坠落无关。
 *
 * 注：插火把（MaidTorchPlacerBehavior）与 AI 工具 `smart_place` 不经本闸口——
 * 前者要求目标格脚下有实心支撑面且贴着主人，后者是玩家的显式指令；两者都不属于
 * "她自己在空中乱搭"的情形。
 */
public final class MaidPlaceGuard {
    private MaidPlaceGuard() {
    }

    /**
     * 实测五百五十【悬空禁搭把"她自己一跳"也算成悬空——自保搭高整条被掐死】
     *
     * ── 现象 ──
     * 实测四百九十八把口径收紧成"只要 fallDistance &gt; 0 就禁"之后，实测五百零九
     * 先撞出一处（岩浆垫方块自救被误伤），本次是同一处收紧的**第二类误伤**：
     * 自保被围殴时她一边挨打一边在打（战术跳劈/走位跨台阶都会带起 1 格的小跳），
     * 落地前的那几 tick `fallDistance` 是正数 → 被这套闸口判成"悬空" →
     * `takeBuildBlock` 返回 `null` → 搭高【一次都放不下】；而 443 又特意把
     * "取料失败"的气泡在悬空时静默了，于是表现为**她既不搭方块、也不吭声地挨打到死**。
     * 搭高自己的"顶起"（放块 → 抬离地面 → 落回）同样在这类 tick 里，属于同一条误伤。
     *
     * ── 改法 ──
     * "悬空"回到它字面的意思：**脚下没有地**。判据换成"脚下三格内有没有可站立面"，
     * 而不是"是否正在下落"。四百九十八要拦的两件事一件没丢：
     * 1. 滑翔 / 飞行空中——单独判（原样保留，那是四百九十八的主漏口）；
     * 2. 1.21.1 重锤跃起——单独判（原样保留）；
     * 3. 从高处掉下来——深坠落阈值（≥ 落地水触发高度）恢复，同时"脚下 3 格内无地"
     *    天然覆盖"3~5 格的浅坠"（离地超过 3 格即判悬空）。
     * 上升段本来就放行，浅跳/被击飞/搭高自身下沉也不再被误判。
     *
     * 三处放行（未落地也不禁）：水里、岩浆里（实测五百零九）、骑乘中。 */
    public static boolean blocked(EntityMaid maid) {
        try {
            if (maid == null || !com.maidsmart.config.MaidSmartConfig.MISC_NO_PLACE_IN_AIR.get()) {
                return false;
            }
            // v1.2.0 实测五百零九【岩浆放行】：1.21.1 侧这里一直是对的（用 Mojmap 的
            // `isInLava()`，不存在 1.20.1 那个"SRG 名字注反"的问题）。本次与 1.20.1 同步
            // 补注，说明这条为什么必须留：
            // 岩浆不会像水那样把 fallDistance 清零，只每 tick 减半（Entity.baseTick 里
            // `fallDistance *= 0.5f`），所以泡岩浆下沉时它稳定停在一个小正数——若不在此
            // 放行，会落到下面"正在下落"那条（当时的口径）被禁搭，`lavaStepUp` 取料失败返回
            // null → **岩浆垫方块自救整条失效**（她背包只有方块、没水桶/珍珠/抗火药时会
            // 一路走到"自救手段都用尽了"）。用户反馈："女仆在没有火焰保护饰品和抗火效果的
            // 情况下，在岩浆里垫方块自救是一个很正常的行为。"
            if (maid.onGround() || maid.isInWater() || maid.isInLava()
                    || maid.isPassenger()) {
                return false;
            }
            // (a) 滑翔 / 飞行作战空中——飞行期一律不搭（旧版在这里把滑翔放行了）
            if (com.maidsmart.combat.MaidFlightKit.isFlightAirborne(maid)) {
                return true;
            }
            // (b) 重锤跃起：整段空中禁搭（上升段也算，避免起跳瞬间乱搭）
            if (com.maidsmart.combat.MaidMaceSmashBehavior.isAirborne(maid)) {
                return true;
            }
            // (c) 深坠落：坠够"落地水触发高度"就交给落地水（四百四十三的原始口径）
            if (maid.fallDistance >= (float) (double)
                    com.maidsmart.config.MaidSmartConfig.COMBAT_WATER_FALL_DISTANCE.get()) {
                return true;
            }
            // (d) 真·悬空 = 脚下 3 格内没有可站立面（实测五百五十把"下落中"换成这条）
            //     浅跳/被击飞/搭高自身"放块→顶起→落回"都落在 1 格以内 → 放行
            return !hasStandableGroundBelow(maid, 3);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 实测五百五十：脚下 maxDepth 格内有没有"能站上去的面"——"悬空"的判据。
     *
     * 用 `isFaceSturdy(level, pos, UP)` 而不是 `isSolidRender`：台阶/楼梯/栅栏/雪层
     * 这类"顶面能站人但不是整块实心"的方块也算落点，否则站在台阶边跳一下就会被
     * 误判成悬空。
     */
    private static boolean hasStandableGroundBelow(EntityMaid maid, int maxDepth) {
        net.minecraft.world.level.Level level = maid.level();
        BlockPos feet = maid.blockPosition();
        for (int d = 1; d <= maxDepth; d++) {
            BlockPos p = feet.below(d);
            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(p);
            if (!st.isAir() && st.isFaceSturdy(level, p, net.minecraft.core.Direction.UP)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 实测五百三十六【不得搭在主人身上】——目标格被主人碰撞箱占着就不搭。
     *
     * 需求原文："搭方块新要求，不得将方块搭在主人（尤其是头部）所在位置。
     * 即不得将方块搭在主人碰撞箱所触碰到的空气方块位置。"
     *
     * 【判据】`new AABB(pos).intersects(owner.getBoundingBox())`。边界语义已用字节码核实
     * （`AABB(BlockPos)` 就是该格整格体积（`x..x+1, y..y+1, z..z+1`）；
     * `intersects`/`m_82381_` 内部走 `m_82314_`，六个分量全是**严格不等式**）——
     * 所以主人**站在方块上**时，脚下那格与碰撞箱只是**相切**、不算被占；
     * 只有碰撞箱真正探进去的格（身体格、头部格）才算占。
     * 这正是需求要的语义：防的是"把方块塞进主人身体/头部"，
     * 而不是"主人站在某块方块上就不能在他脚下搞建设"。
     *
     * 【不限于自保/搭路】四个自主搭块模块、插火把、AI 工具 `smart_place`、
     * 蓝图建造与碑石建造全部接本判定：—— 这一条是**安全约束**（防把主人痊住/卡住），
     * 与“是否她自己主动”无关：玩家一句"把这里填上"也不该把自己埋了。
     *
     * 【不管她自己】女仆自身所在格依然照搭——`placeStep` 把块垫在自己脚下、
     * 蓝图以她脚下为原点先垫自己那块，均是设计意图（不改）。
     *
     * 【v1.2.4 追加：其他女仆也不许压】需求原文："除了不能搭到主人的脑袋上，
     * 也不应该搭到其他女仆的脑袋上，但是对于自己不需要有这个判定。"
     * 所以同一个判据扩到【她以外的每一只女仆】（字段/别的玩家的女仆一视同仁——
     * 判据是"格子里有没有活人的碰撞箱"，与归属无关）：目标格撞到别的女仆
     * 碰撞箱 → 不搭。她自己那一格仍然照搭（下面的 other == maid 直接跳过）；
     * 这样"垫自己脚下往上爬"和"两只女仆挤在一起时别互相埋了"两件事都成立。
     * 判据与主人那条完全同款（整格 AABB 相交 = 身体/头部格，相切不算），
     * 所以"别的女仆站在某块方块上"不会挡住她在那块【下方】施工。
     */
    public static boolean blockedAtOwner(EntityMaid maid, BlockPos pos) {
        try {
            if (maid == null || pos == null
                    || !com.maidsmart.config.MaidSmartConfig.MISC_NO_PLACE_ON_OWNER.get()) {
                return false;
            }
            net.minecraft.world.phys.AABB cell = new net.minecraft.world.phys.AABB(pos);
            net.minecraft.world.entity.LivingEntity owner = maid.getOwner();
            if (owner != null && owner.isAlive() && owner.level() == maid.level()
                    && cell.intersects(owner.getBoundingBox())) {
                return true;
            }
            // v1.2.4：其他女仆（不含她自己）——碰撞箱探进目标格就不搭
            for (EntityMaid other : maid.level().getEntitiesOfClass(EntityMaid.class, cell.inflate(1.0))) {
                if (other == null || other == maid || !other.isAlive()) {
                    continue; // 她自己那格照搭（设计意图，见上面的【不管她自己】）
                }
                if (cell.intersects(other.getBoundingBox())) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
