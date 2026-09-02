package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.PositionTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashSet;
import java.util.Set;

/**
 * 酿造行为：给酿造台补烈焰粉与材料，收取酿好的药水。
 * 酿造台槽位：0-2=药水瓶，3=材料，4=烈焰粉。
 * 平衡设计：
 * - 只管理燃料与材料（药水瓶由主人准备），每次处理 1 个动作
 * - 燃料/材料必须由女仆背包携带（白名单）；处理间隔 100 tick
 * - 三瓶药水齐且燃料材料耗尽时收取成品
 */
public class MaidBrewBehavior extends Behavior<EntityMaid> {
    /** 搜索范围（v1.5.9+：16 格，未找到目标时更积极地寻找周围的工作方块） */
    /** v1.5.88：读配置面板（misc.brewRadius / processCooldown） */
    private static int brewRadius() {
        return com.maidsmart.config.MaidSmartConfig.MISC_BREW_RADIUS.get();
    }

    private static int processCooldown() {
        return com.maidsmart.config.MaidSmartConfig.MISC_PROCESS_COOLDOWN.get();
    }


    /** v1.5.102：垂直搜索范围从配置面板读取（misc.verticalRange） */
    /** 处理间隔（v1.5.9：40 tick = 2 秒，更快） */

    /**
     * 酿造材料白名单（v1.5.25 收紧）：只含"正向"材料——净疣用于打底（粗药阶段被
     * extractFromMaidExcept 排除），其余是粗药→成品阶段能酿出正面效果的。
     * 删除了会酿出中毒(spider_eye)/虚弱(fermented_spider_eye)的负面材料，
     * 以及对粗药无效的 redstone/glowstone_dust 和喷溅化 gunpowder（全自动不碰）。
     */
    private static final Set<String> INGREDIENTS = new HashSet<>();

    static {
        addId("minecraft:nether_wart");
        addId("minecraft:glistering_melon_slice"); // 治疗
        addId("minecraft:golden_carrot");          // 夜视
        addId("minecraft:blaze_powder");           // 力量
        addId("minecraft:sugar");                  // 迅捷
        addId("minecraft:rabbit_foot");            // 跳跃
        addId("minecraft:magma_cream");            // 防火
        addId("minecraft:pufferfish");             // 水肺
        addId("minecraft:ghast_tear");             // 再生
        addId("minecraft:phantom_membrane");       // 缓降
    }

    private static void addId(String id) {
        INGREDIENTS.add(id);
    }

    private BlockPos standPos = null;
    private int cooldown = 0;
    /** 目标扫描节流：找不到酿造台时每 20 tick 才扫一次 */
    private int scanCooldown = 0;

    // v1.1.0 实测二百八十一：缺料报告——旧版缺料静默等待，玩家不知道女仆卡在
    // 缺什么（"尝试酿隐身药水缺发酵蛛眼，女仆卡在那边不动也没有相关报告"）。
    // 报告 = 女仆气泡 + 主人系统消息；30 秒冷却（按女仆）+ 同一材料不重复报，
    // 补上材料重置 missingNotified（换缺别的材料时能再报）。
    private static final java.util.Map<java.util.UUID, Long> MISSING_CD = new java.util.HashMap<>();
    /** 每女仆最近一次报缺的材料（同一材料不刷屏） */
    private static final java.util.Map<java.util.UUID, String> LAST_MISSING = new java.util.HashMap<>();

    /** 酿造链常见材料中文名（气泡用——气泡是字符串，服务端拿不到客户端 I18n） */
    private static final java.util.Map<String, String> MAT_CN = new java.util.HashMap<>();

    static {
        MAT_CN.put("minecraft:nether_wart", "下界疣");
        MAT_CN.put("minecraft:gunpowder", "火药");
        MAT_CN.put("minecraft:dragon_breath", "龙息");
        MAT_CN.put("minecraft:blaze_powder", "烈焰粉");
        MAT_CN.put("minecraft:redstone", "红石粉");
        MAT_CN.put("minecraft:glowstone_dust", "荧石粉");
        MAT_CN.put("minecraft:fermented_spider_eye", "发酵蛛眼");
        MAT_CN.put("minecraft:spider_eye", "蜘蛛眼");
        MAT_CN.put("minecraft:glistering_melon_slice", "闪烁的西瓜片");
        MAT_CN.put("minecraft:golden_carrot", "金萝卜");
        MAT_CN.put("minecraft:sugar", "糖");
        MAT_CN.put("minecraft:rabbit_foot", "兔子脚");
        MAT_CN.put("minecraft:magma_cream", "岩浆膏");
        MAT_CN.put("minecraft:pufferfish", "河豚");
        MAT_CN.put("minecraft:ghast_tear", "恶魂之泪");
        MAT_CN.put("minecraft:phantom_membrane", "幻翼膜");
        MAT_CN.put("minecraft:slime_ball", "黏液球");
    }

    /** v1.1.0 实测二百八十一：缺料报告（气泡 + 主人系统消息，30 秒冷却 + 同材料不重复）。
     *  气泡走字符串（服务端拼中文映射）；系统消息用 translatable 组件——客户端
     *  渲染时按客户端语言本地化，物品名天然正确。 */
    private void notifyMissing(EntityMaid maid, String itemId) {
        try {
            long nowTick = maid.m_9236_().m_46467_();
            Long lastCd = MISSING_CD.get(maid.m_20148_());
            if (lastCd != null && nowTick - lastCd < 600L) {
                return;
            }
            String lastMissing = LAST_MISSING.get(maid.m_20148_());
            if (itemId.equals(lastMissing)) {
                return; // 同一材料已报过，不刷屏
            }
            MISSING_CD.put(maid.m_20148_(), nowTick);
            LAST_MISSING.put(maid.m_20148_(), itemId);
            String cn = MAT_CN.getOrDefault(itemId,
                    itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId);
            maid.getChatBubbleManager().addTextChatBubble(
                    "主人，酿造缺材料：" + cn + " ×1，放进我背包我就继续～");
            net.minecraft.world.entity.LivingEntity owner = maid.m_269323_();
            if (owner instanceof net.minecraft.server.level.ServerPlayer sp) {
                net.minecraft.world.item.Item mat = ForgeRegistries.ITEMS
                        .getValue(new ResourceLocation(itemId));
                net.minecraft.network.chat.Component matName =
                        mat == null ? net.minecraft.network.chat.Component.m_237113_(cn)
                                : new ItemStack(mat).m_41611_();
                sp.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                                "\u00a7e[酿造]\u00a7f " + com.maidsmart.tool.PromaidLog.nameOf(maid)
                                        + " 缺少材料：")
                        .m_7220_(matName)
                        .m_7220_(net.minecraft.network.chat.Component.m_237113_(" \u00a7e×1\u00a7f，放进她背包后会自动继续")));
            }
        } catch (Throwable ignored) {
        }
    }

    /** v1.1.0 实测二百八十一：下料/收成品/补燃料时挥臂（酿造动作可视化） */
    private static void swing(EntityMaid maid) {
        try {
            maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
        } catch (Throwable ignored) {
        }
    }


    public MaidBrewBehavior() {
        // v1.5.124：无限运行时长（旧版默认 60 tick 上限导致行为每 3 秒重启）
        super(java.util.Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Override
    protected boolean m_6114_(ServerLevel level, EntityMaid maid) {
        // v1.5.24 站桩（参考建筑行为）：只要任务是酿造就启动，不再依赖附近有酿造台
        return isBrewTask(maid);
    }

    private static boolean isBrewTask(EntityMaid maid) {
        return maid.getTask() != null
                && ResourceLocation.parse("maid_smart:brew").equals(maid.getTask().getUid());
    }

    @Override
    protected void m_6735_(ServerLevel level, EntityMaid maid, long gameTime) {
        if (this.standPos == null) {
            this.standPos = this.findBrewingStand(level, maid);
        }
        this.cooldown = 0;
    }

    @Override
    protected void m_6725_(ServerLevel level, EntityMaid maid, long gameTime) {
        if (this.standPos == null) {
            // v1.5.24：找不到酿造台时站桩等待（不乱跑），节流扫描
            MaidWorkTags.setStill(maid, true);
            if (this.scanCooldown-- > 0) {
                return;
            }
            this.scanCooldown = 20;
            this.standPos = this.findBrewingStand(level, maid);
            if (this.standPos == null) {
                return;
            }
        }
        BlockState state = level.m_8055_(this.standPos);
        if (!(state.m_60734_() instanceof BrewingStandBlock)) {
            this.standPos = null;
            this.standUp(maid); // v1.5.252：酿造台没了恢复站立
            MaidWorkTags.setStill(maid, true); // 酿造台没了：继续站桩等扫描
            return;
        }
        double distSq = maid.m_20275_(this.standPos.m_123341_() + 0.5, this.standPos.m_123342_() + 0.5, this.standPos.m_123343_() + 0.5);
        if (distSq > 6.25) {
            // 还没到酿造台：解除站桩标记，允许 MoveToTargetSink 走过去
            MaidWorkTags.setStill(maid, false);
            this.standUp(maid);
            maid.m_6274_().m_21879_(MemoryModuleType.f_26370_,
                    new WalkTarget(new BlockPosTracker(this.standPos), 1.0f, 2));
            return;
        }
        // v1.5.17 站桩强化：绑定工作方块后每 tick 清移动目标 + 停止导航，
        // 冷却期间也清——防止漫游/跟随在冷却间隙重新设目标（完全站桩不动）
        MaidWorkTags.setStill(maid, true);
        maid.m_6274_().m_21936_(MemoryModuleType.f_26370_);
        maid.m_21573_().m_26569_();
        // v1.5.252：绑定完成 → 立刻坐下不动（类似建筑；每 tick 保持坐姿防状态机重置）
        if (!maid.isMaidInSittingPose()) {
            maid.m_20124_(net.minecraft.world.entity.Pose.SITTING);
        }
        if (this.cooldown-- > 0) {
            return;
        }
        this.cooldown = processCooldown();
        BlockEntity be = level.m_7702_(this.standPos);
        if (be instanceof BrewingStandBlockEntity) {
            this.processStand(maid, (Container) be);
        }
    }

    @Override
    protected boolean m_6737_(ServerLevel level, EntityMaid maid, long gameTime) {
        // v1.5.24 站桩：只要还在酿造任务就持续（酿造台在烧/暂时无事也不解除），任务切走才让位
        boolean still = isBrewTask(maid);
        if (!still) {
            MaidWorkTags.setStill(maid, false);
            this.standUp(maid);
        }
        return still;
    }

    @Override
    protected void m_6732_(ServerLevel level, EntityMaid maid, long gameTime) {
        // v1.5.24：行为真正停止时解除站桩标记（双保险）+ 恢复站立
        MaidWorkTags.setStill(maid, false);
        this.standUp(maid);
    }

    /** v1.5.252：恢复站立（若当前是坐姿） */
    private void standUp(EntityMaid maid) {
        try {
            if (maid.isMaidInSittingPose()) {
                maid.m_20124_(net.minecraft.world.entity.Pose.STANDING);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * v1.5.25 全自动酿药（重写）。
     * 旧版致命缺陷：金萝卜/烈焰粉/糖等材料【不能直接作用于水瓶】——必须先
     * 水瓶+下界疣 → awkward，再 awkward+材料 → 真药水（两阶段）。旧版不管
     * 基底直接塞槽3 → 酿造台永不反应 → 观感"乱塞材料不酿药"。
     *
     * v1.1.0 实测二百七十七（女仆药剂手册）：按手册配置驱动——
     * - 批量模式（默认）：按槽0 阶段补水瓶/疣/正向材料，再按配置的强化路线
     *   （红石延长/萤石强化）与成品形态（饮用/喷溅/滞留）补强化/形态材料；
     *   收成品判定匹配配置（未到目标形态不收）
     * - 定向模式：按目标药水的配方链（BrewRecipeResolver 反推）精确下料，
     *   缺料停止下料等待（半成品留在酿造台，补料后自动继续，不换材料凑合）；
     *   槽里非目标链上的药水收走腾位
     * 燃料槽 4 始终优先补烈焰粉；槽3 已有材料时不动（酿造台自动续酿消耗）。
     */
    private void processStand(EntityMaid maid, Container stand) {
        IItemHandler maidInv = maid.getMaidInv();
        com.maidsmart.brew.BrewConfig cfg = com.maidsmart.brew.BrewConfig.load(maid);

        // 1. 补燃料（槽 4）——始终优先，没燃料什么都不酿
        if (stand.m_8020_(4).m_41619_()) {
            ItemStack fuel = this.extractItemFromMaid(maidInv, "minecraft:blaze_powder", 1);
            if (!fuel.m_41619_()) {
                stand.m_6836_(4, fuel);
                swing(maid); // v1.1.0 实测二百八十一：动作可视化
            }
        }
        // 2. 收成品：槽0-2 里是"最终状态"（water/awkward 之外的一切药水）→ 收走
        //    （平凡/浓稠是无效果死路也收，腾出位置重新酿）
        //    v1.1.0 实测二百七十七：定向模式只收【目标链上的最终成品】——链上
        //    中间产物（如 awkward→healing 链里的 healing 之前）不收，非目标
        //    药水（无关的成品/死路）收走腾位
        for (int i = 0; i <= 2; i++) {
            ItemStack s = stand.m_8020_(i);
            if (this.isDonePotion(s)) {
                if (cfg.mode == com.maidsmart.brew.BrewConfig.MODE_TARGETED) {
                    if (this.isTargetFinal(s, cfg)) {
                        this.takeIntoMaid(maid, stand, i);
                    } else if (!this.isOnTargetChain(s, cfg)) {
                        this.takeIntoMaid(maid, stand, i); // 非目标药水收走腾位
                    }
                    // 链上中间产物（非最终）留在酿造台继续
                } else {
                    this.takeIntoMaid(maid, stand, i);
                }
            }
        }
        // 3. 按配置下料（槽3 空时才放材料，一次一轮）
        //    v1.5.252：手册"自动酿造"关闭时跳过——只维持（补燃料+收成品），
        //    由玩家/LLM 指令决定酿什么
        if (com.maidsmart.config.MaidSmartConfig.MISC_BREW_AUTO.get()) {
            if (cfg.mode == com.maidsmart.brew.BrewConfig.MODE_TARGETED) {
                this.processTargeted(maid, stand, cfg);
            } else {
                this.processBatch(maid, stand, cfg);
            }
        }

        // v1.5.24：酿造中/无事可做时【不】放弃酿造台——持续站桩等待
        //（旧版 didSomething=false 就清 standPos → 酿造中开始漫游）
    }

    /** 收走槽位药水进女仆背包（满则退回槽位） */
    private void takeIntoMaid(EntityMaid maid, Container stand, int slot) {
        ItemStack taken = stand.m_8016_(slot);
        ItemStack left = ItemHandlerHelper.insertItemStacked(maid.getMaidInv(), taken, false);
        if (!left.m_41619_()) {
            stand.m_6836_(slot, left);
        } else {
            swing(maid); // v1.1.0 实测二百八十一：收成品动作可视化
        }
    }

    /** 定向模式：槽内药水是否为目标链的最终成品（含形态） */
    private boolean isTargetFinal(ItemStack s, com.maidsmart.brew.BrewConfig cfg) {
        if (!cfg.hasValidTarget()) {
            return false;
        }
        net.minecraft.world.item.alchemy.Potion p = net.minecraft.world.item.alchemy.PotionUtils.m_43579_(s);
        if (p == null) {
            return false;
        }
        net.minecraft.resources.ResourceLocation key = ForgeRegistries.POTIONS.getKey(p);
        if (key == null || !cfg.targetPotion.equals(key.toString())) {
            return false;
        }
        // 药水效果对了，还要形态对（饮用/喷溅/滞留）
        return this.formOf(s) == cfg.form;
    }

    /** 定向模式：槽内药水是否在目标链上（中间产物或最终成品） */
    private boolean isOnTargetChain(ItemStack s, com.maidsmart.brew.BrewConfig cfg) {
        if (!cfg.hasValidTarget()) {
            return false;
        }
        net.minecraft.world.item.alchemy.Potion p = net.minecraft.world.item.alchemy.PotionUtils.m_43579_(s);
        if (p == null) {
            return false;
        }
        com.maidsmart.brew.BrewRecipeResolver.Chain chain =
                com.maidsmart.brew.BrewRecipeResolver.chainFor(cfg.targetPotion);
        if (chain == null || chain.isEmpty()) {
            return false;
        }
        if (p == chain.base()) {
            return true;
        }
        for (com.maidsmart.brew.BrewRecipeResolver.Step st : chain.steps()) {
            if (p == st.to()) {
                return true;
            }
        }
        return false;
    }

    /** 定向模式：按配方链精确下料（缺料停止等待，不换材料凑合） */
    private void processTargeted(EntityMaid maid, Container stand, com.maidsmart.brew.BrewConfig cfg) {
        if (!cfg.hasValidTarget()) {
            return; // 目标无效（配置损坏/药水被移除）：不下料
        }
        com.maidsmart.brew.BrewRecipeResolver.Chain chain =
                com.maidsmart.brew.BrewRecipeResolver.chainFor(cfg.targetPotion);
        if (chain == null || chain.isEmpty()) {
            return; // 无链可达：不下料
        }
        IItemHandler maidInv = maid.getMaidInv();
        for (int i = 0; i <= 2; i++) {
            ItemStack s = stand.m_8020_(i);
            if (s.m_41619_()) {
                // 空槽：补水瓶（v1.1.0 实测二百八十：链基底恒为 water——
                // 链完整回退到水瓶起步，awkward 瓶作为链上中间产物正常推进）
                ItemStack bottle = this.extractWaterBottle(maidInv);
                if (!bottle.m_41619_()) {
                    stand.m_6836_(i, bottle);
                    swing(maid);
                }
                continue;
            }
            if (i != 0 || !stand.m_8020_(3).m_41619_()) {
                continue; // 只以槽0 为准下料；槽3 有材料等酿造台消耗
            }
            int progress = com.maidsmart.brew.BrewRecipeResolver.progressOf(s, chain);
            if (progress < 0) {
                // 不在链上的药水：收走腾位（让空槽补水瓶重新开始）
                this.takeIntoMaid(maid, stand, i);
                continue;
            }
            if (progress >= chain.steps().size()) {
                // 药水效果已到目标——检查形态
                this.processForm(maid, stand, i, s, cfg);
                continue;
            }
            // 链上第 progress 步的材料（progress=0 时基底是 water/awkward）
            net.minecraft.world.item.Item reagent = com.maidsmart.brew.BrewRecipeResolver.reagentAt(chain, progress);
            if (reagent == null) {
                continue;
            }
            net.minecraft.resources.ResourceLocation rid = ForgeRegistries.ITEMS.getKey(reagent);
            if (rid == null) {
                continue;
            }
            ItemStack ing = this.extractItemFromMaid(maidInv, rid.toString(), 1);
            if (!ing.m_41619_()) {
                stand.m_6836_(3, ing);
                swing(maid); // v1.1.0 实测二百八十一：下料动作可视化
                // 补料后重置缺料记录（下次缺别的材料能再报）
                LAST_MISSING.remove(maid.m_20148_());
            } else {
                // v1.1.0 实测二百八十一：缺料立即报告（旧版静默等待，玩家不知道
                // 卡在缺什么）——链上已有步骤照常完成，缺的这步停下并提示
                this.notifyMissing(maid, rid.toString());
            }
            // 缺料：不换材料凑合，停止等待（半成品留在酿造台，补料后自动继续）
        }
    }

    /** 批量模式：按槽0 阶段补水瓶/疣/正向材料 + 配置的强化/形态 */
    private void processBatch(EntityMaid maid, Container stand, com.maidsmart.brew.BrewConfig cfg) {
        IItemHandler maidInv = maid.getMaidInv();
        for (int i = 0; i <= 2; i++) {
            ItemStack s = stand.m_8020_(i);
            if (s.m_41619_()) {
                ItemStack bottle = this.extractWaterBottle(maidInv);
                if (!bottle.m_41619_()) {
                    stand.m_6836_(i, bottle);
                    swing(maid);
                }
                continue;
            }
            if (i != 0 || !stand.m_8020_(3).m_41619_()) {
                continue;
            }
            if (this.isPotion(s, "minecraft:water")) {
                ItemStack wart = this.extractItemFromMaid(maidInv, "minecraft:nether_wart", 1);
                if (!wart.m_41619_()) {
                    stand.m_6836_(3, wart);
                    swing(maid);
                }
            } else if (this.isPotion(s, "minecraft:awkward")) {
                // 粗药阶段：放下界疣以外的正向材料（红石/荧石对粗药无效、
                // 负面材料已从 INGREDIENTS 移除）
                ItemStack ingredient = this.extractFromMaidExcept(maidInv, INGREDIENTS, "minecraft:nether_wart", 1);
                if (!ingredient.m_41619_()) {
                    stand.m_6836_(3, ingredient);
                }
            } else {
                // 基础药水（真药水/平凡/浓稠）：按配置补强化/形态
                this.processForm(maid, stand, i, s, cfg);
            }
        }
    }

    /**
     * 强化/形态处理（批量与定向共用）——按配置把基础药水推进到目标形态：
     * - 强化路线：红石延长（long_*）/ 萤石强化（strong_*）——由原版配方表
     *   （PotionBrewing.m_43529_）判定当前瓶+红石/萤石能否出结果，能则下料
     * - 形态：饮用→喷溅（火药）→滞留（龙息），逐级推进
     * 槽3 已有材料时不动（酿造台自动续酿消耗）。
     */
    private void processForm(EntityMaid maid, Container stand, int slot, ItemStack s,
                             com.maidsmart.brew.BrewConfig cfg) {
        if (!stand.m_8020_(3).m_41619_()) {
            return;
        }
        IItemHandler maidInv = maid.getMaidInv();
        int form = this.formOf(s);
        // 1. 强化（仅饮用形态可强化——喷溅/滞留瓶加红石/萤石无效）
        if (form == com.maidsmart.brew.BrewConfig.FORM_DRINK && cfg.enhance != com.maidsmart.brew.BrewConfig.ENHANCE_NONE) {
            String reagentId = cfg.enhance == com.maidsmart.brew.BrewConfig.ENHANCE_REDSTONE
                    ? "minecraft:redstone" : "minecraft:glowstone_dust";
            ItemStack reagent = this.extractItemFromMaid(maidInv, reagentId, 1);
            if (!reagent.m_41619_()) {
                // 当前瓶 + 强化材料能否出结果（原版配方表判定——如 healing+红石
                // 无结果（治疗没有延长版），则跳过强化直接进形态）
                ItemStack test = net.minecraft.world.item.alchemy.PotionBrewing.m_43529_(reagent, s);
                if (!test.m_41619_() && test.m_41720_() == s.m_41720_()
                        && !net.minecraft.world.item.alchemy.PotionUtils.m_43579_(test)
                        .equals(net.minecraft.world.item.alchemy.PotionUtils.m_43579_(s))) {
                    stand.m_6836_(3, reagent);
                    return;
                }
            }
        }
        // 2. 形态推进（饮用→喷溅→滞留）
        if (form < cfg.form) {
            int nextForm = form + 1;
            net.minecraft.world.item.Item reagent = com.maidsmart.brew.BrewRecipeResolver.formReagent(nextForm);
            if (reagent == null) {
                return;
            }
            net.minecraft.resources.ResourceLocation rid = ForgeRegistries.ITEMS.getKey(reagent);
            if (rid == null) {
                return;
            }
            ItemStack ing = this.extractItemFromMaid(maidInv, rid.toString(), 1);
            if (!ing.m_41619_()) {
                stand.m_6836_(3, ing);
                swing(maid); // v1.1.0 实测二百八十一：下料动作可视化
                LAST_MISSING.remove(maid.m_20148_());
            } else {
                // v1.1.0 实测二百八十一：缺火药/龙息立即报告（"喷溅型根本没法酿"
                // 的感知根因——旧版静默等待，玩家不知道还要往背包放形态材料）
                this.notifyMissing(maid, rid.toString());
            }
        }
    }

    /** 槽内药水的形态（0=饮用 1=喷溅 2=滞留；非药水返回 0） */
    private int formOf(ItemStack s) {
        if (s.m_41619_()) {
            return com.maidsmart.brew.BrewConfig.FORM_DRINK;
        }
        net.minecraft.world.item.Item item = s.m_41720_();
        if (item == com.maidsmart.brew.BrewRecipeResolver.item("minecraft:potion")) {
            return com.maidsmart.brew.BrewConfig.FORM_DRINK;
        }
        if (item == com.maidsmart.brew.BrewRecipeResolver.item("minecraft:splash_potion")) {
            return com.maidsmart.brew.BrewConfig.FORM_SPLASH;
        }
        if (item == com.maidsmart.brew.BrewRecipeResolver.item("minecraft:lingering_potion")) {
            return com.maidsmart.brew.BrewConfig.FORM_LINGERING;
        }
        return com.maidsmart.brew.BrewConfig.FORM_DRINK;
    }

    /** 是否"最终状态"药水（water/awkward 之外的一切：真药水/平凡/浓稠都收走） */
    private boolean isDonePotion(ItemStack s) {
        if (s.m_41619_() || !(s.m_41720_() instanceof net.minecraft.world.item.PotionItem)) {
            return false;
        }
        net.minecraft.world.item.alchemy.Potion p = net.minecraft.world.item.alchemy.PotionUtils.m_43579_(s);
        if (p == null) {
            return false;
        }
        net.minecraft.resources.ResourceLocation key = ForgeRegistries.POTIONS.getKey(p);
        if (key == null) {
            return false;
        }
        String k = key.toString();
        return !"minecraft:water".equals(k) && !"minecraft:awkward".equals(k);
    }

    /** 槽内药水是否为指定 Potion（按注册表 key 判定，可靠） */
    private boolean isPotion(ItemStack s, String potionId) {
        if (s.m_41619_() || !(s.m_41720_() instanceof net.minecraft.world.item.PotionItem)) {
            return false;
        }
        net.minecraft.world.item.alchemy.Potion p = net.minecraft.world.item.alchemy.PotionUtils.m_43579_(s);
        if (p == null) {
            return false;
        }
        net.minecraft.resources.ResourceLocation key = ForgeRegistries.POTIONS.getKey(p);
        return key != null && potionId.equals(key.toString());
    }

    /** 从白名单取材料，但排除指定 id（awkward 阶段不能再用下界疣） */
    private ItemStack extractFromMaidExcept(IItemHandler maidInv, Set<String> whitelistIds, String excludeId, int count) {
        for (int i = 0; i < maidInv.getSlots(); i++) {
            ItemStack stack = maidInv.getStackInSlot(i);
            if (stack.m_41619_()) {
                continue;
            }
            ResourceLocation stackId = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
            if (stackId != null && whitelistIds.contains(stackId.toString())
                    && !excludeId.equals(stackId.toString())) {
                return maidInv.extractItem(i, count, false);
            }
        }
        return ItemStack.f_41583_;
    }

    /** 从背包取 1 瓶水瓶（PotionItem 且药水为 water，动态 key 判定）；没有返回空 */
    private ItemStack extractWaterBottle(IItemHandler inv) {
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.m_41619_() || !(stack.m_41720_() instanceof net.minecraft.world.item.PotionItem)) {
                continue;
            }
            net.minecraft.world.item.alchemy.Potion potion = net.minecraft.world.item.alchemy.PotionUtils.m_43579_(stack);
            if (potion == null) {
                continue;
            }
            net.minecraft.resources.ResourceLocation key = ForgeRegistries.POTIONS.getKey(potion);
            if (key != null && "minecraft:water".equals(key.toString())) {
                return inv.extractItem(i, 1, false);
            }
        }
        return ItemStack.f_41583_;
    }

    private ItemStack extractFromMaid(IItemHandler maidInv, Set<String> whitelistIds, int count) {
        for (int i = 0; i < maidInv.getSlots(); i++) {
            ItemStack stack = maidInv.getStackInSlot(i);
            if (stack.m_41619_()) {
                continue;
            }
            ResourceLocation stackId = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
            if (stackId != null && whitelistIds.contains(stackId.toString())) {
                return maidInv.extractItem(i, count, false);
            }
        }
        return ItemStack.f_41583_;
    }

    private ItemStack extractItemFromMaid(IItemHandler maidInv, String itemId, int count) {
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(itemId));
        if (item == null) {
            return ItemStack.f_41583_;
        }
        for (int i = 0; i < maidInv.getSlots(); i++) {
            ItemStack stack = maidInv.getStackInSlot(i);
            if (!stack.m_41619_() && stack.m_41720_() == item) {
                return maidInv.extractItem(i, count, false);
            }
        }
        return ItemStack.f_41583_;
    }

    private BlockPos findBrewingStand(ServerLevel level, EntityMaid maid) {
        BlockPos pos = maid.m_20183_();
        for (int dy = -com.maidsmart.config.MaidSmartConfig.MISC_VERTICAL_RANGE.get();
             dy <= com.maidsmart.config.MaidSmartConfig.MISC_VERTICAL_RANGE.get(); dy++) {
            for (int dx = -brewRadius(); dx <= brewRadius(); dx++) {
                for (int dz = -brewRadius(); dz <= brewRadius(); dz++) {
                    BlockPos p = pos.m_7918_(dx, dy, dz);
                    if (level.m_8055_(p).m_60734_() instanceof BrewingStandBlock) {
                        return p;
                    }
                }
            }
        }
        return null;
    }
}
