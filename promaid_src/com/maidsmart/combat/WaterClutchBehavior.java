package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 落地水（v1.5.25）：女仆的【被动生存技能】——与水桶是否在背包相关。
 * 落地雪（v1.1.0）：细雪桶版——与水的关键差异是【细雪不流动】：水可以提前放在
 * 稍高的位置自己流淌铺开接人（碰到的任何水都重置摔落），细雪只认实体真正
 * 落进去的那一格。因此雪不照抄水的双格逻辑——只在【落点平面】铺 3×3 雪垫让她
 * 全速落进去（绝不在高处拦她减速——出雪后剩下的路还是自由落体，照样摔），
 * 坠落途中每 tick 把雪垫补到她正下方（防击退/横移漂移错过落点）。
 *
 * 【难度须知】：落地雪天生比落地水难、有小概率失败——细雪不流动、只认实体
 * 真正落进去的那一格，落点预测与实际落点之间只要差出雪垫覆盖范围（击退、
 * 横移、判定与放置同 tick 的竞态、落点格非空气铺不进雪等）就是空摔。这是
 * 机制本身的固有上限，不做进一步优化（玩家徒手铺细雪也有同样的容错问题）。
 * 追求稳就给水桶（水会流动自己铺开，可靠性高一档）。
 *
 * 细雪【下界不蒸发】补上落地水的盲区；两者都有桶时优先用水；细雪接触 7 秒
 * （140 tick）才开始冻伤，保持时长上限 100 tick（5 秒），在安全线内。
 *
 * 与自保（SelfPreservationBehavior）不同：自保是"进入危险状态"的主动状态机；
 * 落地水不进任何状态、不触发逃跑/搭高，只是在【自由落体即将触地】的瞬间
 * 往脚下放一滩水缓冲，落地后 1 秒（20 tick）自动收回。二者互不干扰——
 * 自保逃跑/搭高时从高处掉落同样会被落地水接住。
 *
 * 触发条件（全部满足才放水）：
 * - 背包有【水桶】或【细雪桶】（v1.1.0 落地雪；水桶在下界不作钥匙——水会蒸发）——都没有不触发
 * - 正在下落（垂直速度 < 0）
 * - 未落地（onGround == false）
 * - 下方即将触地（脚下 4 格内是实心地面）
 * - 脚下不是水（已经在水里不需要）
 *
 * 流程：有水桶（触发钥匙，不消耗）→ 放水缓冲 → 20 tick 后收回（水方块移除，
 * 水桶始终留在背包数量不变——玩家落地水也是"放下一瞬间并收回"）。
 *
 * 注册为 core 行为（优先级 240，低于自保 250）：任何 activity 都运行，
 * 但只在"有水桶 + 坠落"时做动作，其余时间静默返回。
 */
public class WaterClutchBehavior extends Behavior<EntityMaid> {
    /** v1.5.102：数值改从配置面板读取（combat 段）——保持时长 / 下探格数 */

    /** 已放的缓冲方块（水=落点+所在格 2 处；细雪=落点平面 3×3 雪垫，追踪漂移会补块） */
    private final java.util.ArrayList<BlockPos> placedList = new java.util.ArrayList<>();
    /** v1.1.0：本次放的是细雪（true）还是水（false）——收回时按类型移除 */
    private boolean placedSnow = false;
    /** 放水时的游戏 tick（用于 1 秒后收回） */
    private long placedTick = 0;
    /** v1.5.25e：本次坠落是否已用过落地水（落地后重置——防止高塔坠落途中反复放水） */
    private boolean clutchedThisFall = false;
    /** v1.5.27：上一 tick 的位置（传送突变检测——传送不会重置 fallDistance，
     *  且旧位置的水/本次坠落标志不跟着走；检测到突变立即清状态） */
    private BlockPos lastTickPos = null;

    public WaterClutchBehavior() {
        super(java.util.Collections.emptyMap());
    }

    /** 始终可运行（被动检查）；具体动作由 m_6725_ 里的条件决定
     *  v1.5.88：落地水开关（combat.waterClutch）；v1.1.0：落地雪开关（combat.snowClutch） */
    @Override
    protected boolean m_6114_(ServerLevel level, EntityMaid maid) {
        return com.maidsmart.config.MaidSmartConfig.COMBAT_WATER_CLUTCH.get()
                || com.maidsmart.config.MaidSmartConfig.COMBAT_SNOW_CLUTCH.get();
    }

    @Override
    protected boolean m_6737_(ServerLevel level, EntityMaid maid, long gameTime) {
        return true;
    }

    @Override
    protected void m_6725_(ServerLevel level, EntityMaid maid, long gameTime) {
        // v1.5.27：传送突变检测（必须放在收水检查【之前】）。
        // 自保传送（teleportHome/末影珍珠）/主人死亡传送/召回：MC 传送不重置
        // fallDistance，且旧位置的水（waterPos）和"本次坠落已用过"标志不跟着走——
        // 传送后女仆在新位置坠落时，旧逻辑会因 waterPos!=null / clutchedThisFall
        // 直接不触发 → 新位置高空坠落摔死（"落地水来不及释放"的根因）。
        // 两 tick 间位置突变 >6 格（普通移动/击退/摔落达不到）→ 视为传送：
        // 清旧水 + 允许重新触发 + 摔落距离归零（伤害重新计算）。
        BlockPos now = maid.m_20183_();
        if (this.lastTickPos != null && !this.lastTickPos.equals(now)) {
            int dx = this.lastTickPos.m_123341_() - now.m_123341_();
            int dy = this.lastTickPos.m_123342_() - now.m_123342_();
            int dz = this.lastTickPos.m_123343_() - now.m_123343_();
            if (dx * dx + dy * dy + dz * dz > 36.0) {
                this.recoverFluid(level, maid);
                this.clutchedThisFall = false;
                maid.f_19789_ = 0.0f;
            }
        }
        this.lastTickPos = now;

        // v1.5.27b：落地 → 立即重置"本次坠落已用过"（放在收水检查【之前】）。
        // 旧顺序：落地后的收水等待期（1 秒）内被 return 拦截，重置不执行——
        // 用户"落地后几秒内放回原处再打下"测试第二次不触发的一部分原因。
        // 提前后：落地瞬间标志即清零，收水期一过（或落地即不再坠落）随时可再触发。
        if (maid.m_20096_()) {
            this.clutchedThisFall = false;
        }

        // 1. 缓冲维护期（v1.1.0 落地雪特有）：还在坠落且尚未进雪 → 每 tick 把雪垫
        //    补到她【当前预测落点】正下方。细雪不流动：被击退/横移漂移后落点会
        //    离开旧雪垫，不跟着补就是空摔。已进雪（脚下是细雪）/已落地/是水（会
        //    流动自己铺开）→ 不维护。补了新块则重置保持计时（防垫子刚铺就被收）。
        if (!this.placedList.isEmpty()) {
            if (this.placedSnow
                    && !maid.m_20096_()
                    && maid.m_20184_().f_82480_ < 0
                    && !this.touchingPlacedSnow(level, maid)) {
                BlockPos land = this.findLandingPos(level, maid);
                if (land != null && this.ensureSnowPad(level, land)) {
                    this.placedTick = level.m_46467_();
                }
            }
            // 收回：HOLD_TICKS 到 → 移除全部缓冲方块（水或细雪，桶始终不消耗）；
            // 保持时长对水/细雪共用（配置上限 100 tick = 5 秒 < 细雪冻伤线 140 tick——安全）
            if (gameTime - this.placedTick >= com.maidsmart.config.MaidSmartConfig.COMBAT_WATER_HOLD.get()) {
                this.recoverFluid(level, maid);
            }
            return; // 缓冲等待期间不重新触发
        }
        // 2. 触发判定：有钥匙桶（水/细雪）+ 真实坠落 + 未落地 + 距地面足够高。
        //    v1.1.0：水桶只在非下界作钥匙（下界放水瞬间蒸发）；细雪桶任何维度都可
        //    （下界也能用）；两者都有时优先水（行为更直观）
        boolean waterKey = com.maidsmart.config.MaidSmartConfig.COMBAT_WATER_CLUTCH.get()
                && !this.isNether(level)
                && this.hasItem(maid, "minecraft:water_bucket");
        boolean snowKey = com.maidsmart.config.MaidSmartConfig.COMBAT_SNOW_CLUTCH.get()
                && this.hasItem(maid, "minecraft:powder_snow_bucket");
        if (!waterKey && !snowKey) {
            return;
        }
        // v1.5.25g：用【累计下落距离 fallDistance】判定（原版每 tick 下落累加、落地归零），
        // 不再用速度——旧版要等速度 < -1.1（约 3 格坠落）才触发，短距离坠落速度
        // 达不到就永远不触发 → 摔死（"成功率不到 50%"根因）。fallDistance ≥ 3 就
        // 触发，任何高度坠落（3 格以上）都会在落水窗口内激活；被击退的轻微离地
        // （fallDistance 只有零点几）不会误触发。
        // v1.5.88：触发高度从配置面板读取（combat.waterFallDistance）
        if (maid.f_19789_ < (float) (double) com.maidsmart.config.MaidSmartConfig.COMBAT_WATER_FALL_DISTANCE.get()) {
            return; // 累计下落不足（不是高空坠落）
        }
        if (this.clutchedThisFall) {
            return; // 本次坠落已用过落地水（防高塔坠落途中反复放水）
        }
        BlockPos land = this.findLandingPos(level, maid);
        if (land == null) {
            return;
        }
        this.clutchedThisFall = true;
        // v1.1.0 实测十三【诊断日志】：用户实测"细雪桶全没了且落地雪没生效"——
        // 全日志无任何 clutch 触发记录。加触发日志（放桶/放水各一条）定位是
        // "没触发"还是"触发了但没生效"。latest.log 搜 "clutch"。
        com.mojang.logging.LogUtils.getLogger().info(
                "clutch trigger: maid={} snow={} fallDist={} hasWater={} hasSnow={} land={}",
                maid.m_5446_() != null ? maid.m_5446_().getString() : maid.m_20148_(),
                snowKey && !waterKey,
                String.format("%.1f", maid.f_19789_),
                waterKey, snowKey, land);
        this.placeFluid(level, maid, land, !waterKey); // 有水钥匙用水；只有细雪钥匙 → 落地雪
    }

    /** 背包里是否有指定物品（v1.1.0：水桶/细雪桶通用——落地雪复用） */
    private boolean hasItem(EntityMaid maid, String itemId) {
        Item item = ForgeRegistries.ITEMS.getValue(
                net.minecraft.resources.ResourceLocation.parse(itemId));
        if (item == null) {
            return false;
        }
        IItemHandler inv = maid.getMaidInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.m_41619_() && stack.m_41720_() == item) {
                return true;
            }
        }
        return false;
    }

    /** 下界判断（f_46429_ = the_nether——照 SelfPreservationBehavior 的实证写法，勿用 f_46428_） */
    private static boolean isNether(ServerLevel level) {
        return level.m_46472_() == net.minecraft.world.level.Level.f_46429_;
    }

    /**
     * 找"即将落地"的放水格：从脚下向下扫 LANDING_SCAN 格，
     * 第一个实心方块的上方格就是落地点（若该格是空气则返回）。
     * v1.5.25g：加距离窗口——落水点必须在脚下 2~7 格之间（太近 <2 格来不及放水；
     * 太远 >7 格水先收回）。脚下已经是水 → 返回 null（在水里不需要落地水）。
     * v1.5.27：d 从 1 开始扫——旧版从 2 开始，"地面就在脚下 1 格"（传送后或
     * 高速坠落瞬间）时第一个实心块是地面本身，其上方格是地面表面（实心）
     * → 永远 null → 不触发 → 摔死。d=1 时上方格即女仆脚格（空气）→ 能触发。
     */
    private BlockPos findLandingPos(ServerLevel level, EntityMaid maid) {
        Block waterBlock = ForgeRegistries.BLOCKS.getValue(
                net.minecraft.resources.ResourceLocation.parse("minecraft:water"));
        BlockPos feet = maid.m_20183_();
        for (int d = 1; d <= Math.min(com.maidsmart.config.MaidSmartConfig.COMBAT_WATER_LANDING_SCAN.get(), 7); d++) {
            BlockPos check = feet.m_7918_(0, -d, 0);
            BlockState state = level.m_8055_(check);
            if (state.m_60795_()) {
                continue; // 空气，继续向下
            }
            // 已在水/细雪里：不需要缓冲（落进去本身就会重置摔落距离）
            if (waterBlock != null && state.m_60734_() == waterBlock) {
                return null;
            }
            Block snowBlock = ForgeRegistries.BLOCKS.getValue(
                    net.minecraft.resources.ResourceLocation.parse("minecraft:powder_snow"));
            if (snowBlock != null && state.m_60734_() == snowBlock) {
                return null;
            }
            // 遇到实心方块：它上方一格就是落水点（需是空气；d=1 时该格即女仆脚格）
            BlockPos above = check.m_7918_(0, 1, 0);
            if (level.m_8055_(above).m_60795_()) {
                return above;
            }
            return null;
        }
        return null;
    }

    /**
     * 放缓冲（v1.1.0：水/雪分开处理——细雪不流动，不能照抄水的逻辑）。
     * - 水：v1.5.25h 双格——落点 + 女仆所在格（水会流动铺开，提前放稍高处也接得住；
     *   双格兜底速度太快穿过第一格的情况）。
     * - 细雪：只在【落点平面】铺 3×3 雪垫（空气格才铺）——她全速落进雪垫里重置
     *   摔落距离。绝不在高处放雪拦她：细雪把她减速，出雪后剩下的路又是自由落体，
     *   摔落距离照样累积 → 反而摔死。落点精度靠 3×3 容差 + 坠落途中逐 tick 追补。
     * 桶只是【触发钥匙】——不真正消耗、不变化背包；收回时直接移除方块。
     */
    private void placeFluid(ServerLevel level, EntityMaid maid, BlockPos pos, boolean snow) {
        if (snow) {
            this.placedSnow = true;
            this.ensureSnowPad(level, pos);
            maid.getChatBubbleManager().addTextChatBubble("落地雪！");
        } else {
            this.placedSnow = false;
            Block waterBlock = ForgeRegistries.BLOCKS.getValue(
                    net.minecraft.resources.ResourceLocation.parse("minecraft:water"));
            if (waterBlock == null) {
                return;
            }
            // 第一格：落点
            level.m_7731_(pos, waterBlock.m_49966_(), 3);
            this.placedList.add(pos.m_7949_());
            // 第二格：女仆所在格（若与落点不同且是空气才放——避免覆盖地面）
            BlockPos feetPos = maid.m_20183_();
            if (!feetPos.equals(pos) && level.m_8055_(feetPos).m_60795_()) {
                level.m_7731_(feetPos, waterBlock.m_49966_(), 3);
                this.placedList.add(feetPos.m_7949_());
            }
            maid.getChatBubbleManager().addTextChatBubble("落地水！");
        }
        this.placedTick = level.m_46467_();
    }

    /**
     * 落点平面 3×3 雪垫（只铺空气格）。幂等：本轮已铺过的格跳过——坠落途中的
     * 追补调用只会补上新漂进来的格子。返回是否真的铺了新块（铺了则调用方重置保持计时）。
     * 注意：落点格本身不是空气（地面凸起/花草上沿等）时这格铺不进雪——落地雪
     * 固有的小概率失败场景之一，见类头的【难度须知】。
     */
    private boolean ensureSnowPad(ServerLevel level, BlockPos center) {
        Block snowBlock = ForgeRegistries.BLOCKS.getValue(
                net.minecraft.resources.ResourceLocation.parse("minecraft:powder_snow"));
        if (snowBlock == null) {
            return false;
        }
        boolean added = false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos p = center.m_7918_(dx, 0, dz);
                if (!level.m_8055_(p).m_60795_()) {
                    continue; // 非空气不铺（地面/花草/已有方块都跳过）
                }
                if (this.placedList.contains(p)) {
                    continue; // 这一轮已铺过
                }
                level.m_7731_(p, snowBlock.m_49966_(), 3);
                this.placedList.add(p.m_7949_());
                added = true;
            }
        }
        return added;
    }

    /** 女仆是否已在细雪里（所在格或脚下格是细雪——进了雪就不用再追补雪垫） */
    private boolean touchingPlacedSnow(ServerLevel level, EntityMaid maid) {
        Block snowBlock = ForgeRegistries.BLOCKS.getValue(
                net.minecraft.resources.ResourceLocation.parse("minecraft:powder_snow"));
        if (snowBlock == null) {
            return true; // 取不到方块按"已在雪"处理（不追补）
        }
        BlockPos feet = maid.m_20183_();
        return level.m_8055_(feet).m_60734_() == snowBlock
                || level.m_8055_(feet.m_7918_(0, -1, 0)).m_60734_() == snowBlock;
    }

    /** 收回：按本次类型移除全部缓冲方块（水的双格/细雪的 3×3 雪垫；桶不消耗，无需还原） */
    private void recoverFluid(ServerLevel level, EntityMaid maid) {
        // v1.1.0 实测十六（审查 P1-3/P2）：维度感知回收——maid 当前所在维度可能已
        // 不是放水时的维度（传送/跟随跨维度后 recover 被调），按旧坐标在新维度
        // 查方块是 no-op、旧维度的水/雪永久残留。回收一律用【女仆当前维度】：
        // 传送突变检测在跨维度 tick 的第一帧就触发（位置突变）且彼时 level 已是
        // 新维度，旧维度残留同样收不到——所以另配 doStop/卸载兜底（见下）。
        ServerLevel cur = (ServerLevel) maid.m_9236_();
        Block fluidBlock = ForgeRegistries.BLOCKS.getValue(
                net.minecraft.resources.ResourceLocation.parse(this.placedSnow ? "minecraft:powder_snow" : "minecraft:water"));
        Block airBlock = ForgeRegistries.BLOCKS.getValue(
                net.minecraft.resources.ResourceLocation.parse("minecraft:air"));
        for (BlockPos p : this.placedList) {
            BlockState state = cur.m_8055_(p);
            if (fluidBlock != null && state.m_60734_() == fluidBlock) {
                // 移除缓冲方块：换成空气（用注册表取 air，避免猜 SRG 字段名；
                // 中途被玩家替换成别方块的格子不动）
                cur.m_7731_(p,
                        airBlock != null ? airBlock.m_49966_() : net.minecraft.world.level.block.Blocks.f_50016_.m_49966_(),
                        3);
            }
        }
        this.placedList.clear();
        // v1.5.25e：收水后允许再放一次——极高塔坠落时 1 秒水可能提前收，
        // 落地前最后一段若速度仍达标则再放一次接住
        this.clutchedThisFall = false;
    }

    /**
     * v1.1.0 实测十六（审查 P1-3）：行为停止兜底回收。旧版没有 doStop——20 tick
     * 保持期内女仆被魂符收回/死亡/任务切换导致行为停止时，placedList 连同 brain
     * 一起丢弃 → 缓冲水（= 无限水）/细雪永久留在世界里。doStop 时把还没到期的
     * 缓冲方块立即收回（与正常到期同路径）。
     */
    @Override
    protected void m_6732_(ServerLevel level, EntityMaid maid, long gameTime) {
        if (!this.placedList.isEmpty()) {
            this.recoverFluid(level, maid);
        }
        super.m_6732_(level, maid, gameTime);
    }
}
