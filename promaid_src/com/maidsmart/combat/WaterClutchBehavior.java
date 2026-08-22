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
 * 落地雪（v1.1.0）：细雪桶同款——落点放细雪缓冲；细雪【下界不蒸发】，补上
 * 落地水在地狱维度失效的盲区。两者都有桶时优先用水；细雪接触 7 秒（140 tick）
 * 才开始冻伤，保持时长上限 100 tick（5 秒），在安全线内。
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

    /** 已放水的坐标（null = 当前没在放水） */
    private BlockPos waterPos = null;
    /** v1.5.25h：第二格水（女仆所在格）——极端情况（速度太快穿过第一格）兜底 */
    private BlockPos waterPos2 = null;
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

        // 1. 收回：放缓冲后 HOLD_TICKS 到 → 移除方块（水或细雪，桶始终不消耗）
        if (this.waterPos != null) {
            // 保持时长对水/细雪共用（配置上限 100 tick = 5 秒 < 细雪冻伤线 140 tick——安全）
            if (gameTime - this.placedTick >= com.maidsmart.config.MaidSmartConfig.COMBAT_WATER_HOLD.get()) {
                this.recoverFluid(level, maid);
            }
            return; // 放缓冲等待期间不重复放
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
     * 放缓冲方块（v1.1.0：落地水/落地雪同一实现——snow=true 放细雪）。
     * 桶只是【触发钥匙】——不真正消耗、不变化背包；玩家落地水/落地雪也是
     * "放下一瞬间并收回"，收回时直接移除方块。
     * v1.5.25h：双格——第一格在落点，第二格在女仆所在格（速度太快穿过第一格/
     * 落点偏移时兜底接住）。
     */
    private void placeFluid(ServerLevel level, EntityMaid maid, BlockPos pos, boolean snow) {
        Block fluidBlock = ForgeRegistries.BLOCKS.getValue(
                net.minecraft.resources.ResourceLocation.parse(snow ? "minecraft:powder_snow" : "minecraft:water"));
        if (fluidBlock == null) {
            return;
        }
        // 第一格：落点
        level.m_7731_(pos, fluidBlock.m_49966_(), 3);
        this.waterPos = pos;
        // 第二格：女仆所在格（若与落点不同且是空气才放——避免覆盖地面）
        BlockPos feetPos = maid.m_20183_();
        if (!feetPos.equals(pos) && level.m_8055_(feetPos).m_60795_()) {
            level.m_7731_(feetPos, fluidBlock.m_49966_(), 3);
            this.waterPos2 = feetPos;
        } else {
            this.waterPos2 = null;
        }
        this.placedSnow = snow;
        this.placedTick = level.m_46467_();
        // 播报（让主人看见"用了一次缓冲"）
        maid.getChatBubbleManager().addTextChatBubble(snow ? "落地雪！" : "落地水！");
    }

    /** 收回：按本次类型移除两格缓冲方块（水/细雪；桶不消耗，无需还原） */
    private void recoverFluid(ServerLevel level, EntityMaid maid) {
        Block fluidBlock = ForgeRegistries.BLOCKS.getValue(
                net.minecraft.resources.ResourceLocation.parse(this.placedSnow ? "minecraft:powder_snow" : "minecraft:water"));
        Block airBlock = ForgeRegistries.BLOCKS.getValue(
                net.minecraft.resources.ResourceLocation.parse("minecraft:air"));
        if (this.waterPos != null) {
            BlockState state = level.m_8055_(this.waterPos);
            if (fluidBlock != null && state.m_60734_() == fluidBlock) {
                // 移除缓冲方块：换成空气（用注册表取 air，避免猜 SRG 字段名）
                level.m_7731_(this.waterPos,
                        airBlock != null ? airBlock.m_49966_() : net.minecraft.world.level.block.Blocks.f_50016_.m_49966_(),
                        3);
            }
            this.waterPos = null;
        }
        // 第二格（女仆所在格）同样收回
        if (this.waterPos2 != null) {
            BlockState state2 = level.m_8055_(this.waterPos2);
            if (fluidBlock != null && state2.m_60734_() == fluidBlock) {
                level.m_7731_(this.waterPos2,
                        airBlock != null ? airBlock.m_49966_() : net.minecraft.world.level.block.Blocks.f_50016_.m_49966_(),
                        3);
            }
            this.waterPos2 = null;
        }
        // v1.5.25e：收水后允许再放一次——极高塔坠落时 1 秒水可能提前收，
        // 落地前最后一段若速度仍达标则再放一次接住
        this.clutchedThisFall = false;
    }
}
