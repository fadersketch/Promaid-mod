package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * v1.5.189：被动插火把（玩家贴身辅助）——core 行为，非工作状态。
 *
 * 主人周围 5×5 的黑暗格（有效亮度 < 阈值，默认 7，方块光与天光取大者）自动插火把：
 * 包里有火把（见 TORCH_IDS）且脚下有实心支撑时放置，消耗 1 个火把 + 挥手一次。
 * 总开关：combat.torchPlacerEnable（默认开）+ 阈值可调；插一根后间隔 2 秒（实测八百五十一）。
 *
 * v1.1.0 实测五十二【亮度口径修复】：旧版只读方块光——白天地表方块光恒 0，跟着主人走路一路狂插火把。
 * 改为 effective = max(方块光, 天光 - getSkyDarken)（原版合并亮度口径，详见 tick 内注释）。
 *
 * 实测八百四十七：包里（含精妙背包）优先、主/副手兜底且**不搬动**任何槽位；候选格取"最暗的能放下格"。
 * 实测八百五十一：她自己的手持光源只按上界排除、真实光源（含她刚插下的那根）照到的格子不再重复插。
 */
public class MaidTorchPlacerBehavior extends Behavior<EntityMaid> {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** 实测八百五十一：扫描节奏（tick）——每这么多 tick 才排一次扫描；"插"的间隔另由 torchCooldown 管 */
    private static final int SCAN_GAP = 10;

    private int torchCooldown = 0;
    /** v1.5.227：canUse 首调诊断标记（只打第一条） */
    private boolean canUseLogged = false;

    public MaidTorchPlacerBehavior() {
        super(java.util.Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
        // v1.5.227 诊断：行为构造 = 类被加载 + 实例被创建
        LOGGER.info("torch-placer constructed");
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        // v1.5.227 诊断：canUse 被 Brain 调用过一次后不再刷（只打第一条）
        if (!this.canUseLogged) {
            this.canUseLogged = true;
            LOGGER.info("torch-placer canUse first-call: enabled={}",
                    com.maidsmart.config.MaidSmartConfig.TORCH_PLACER_ENABLE.get());
        }
        return com.maidsmart.config.MaidSmartConfig.TORCH_PLACER_ENABLE.get();
    }

    /**
     * v1.5.228【重大修复】：canStillUse 必须重写为 true——原版 1.20.1 Behavior 的
     * canStillUse 默认返回【false】！行为 tryStart 后下一 tick 立即被 tickOrStop
     * 停掉，tick() 永远不执行。插火把行为从 v1.5.189 诞生起就没 tick 过。
     */
    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        // 重写为 true 的原因见上；TD-10：总开关也在 canStillUse 里问——关掉即刻停，不用等跑完。
        return com.maidsmart.config.MaidSmartConfig.TORCH_PLACER_ENABLE.get();
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        if (this.torchCooldown > 0) {
            this.torchCooldown--;
            return;
        }
        // 实测八百五十一【扫描节奏闸】：每 SCAN_GAP tick 才排一次扫描——旧版每 tick 都扫 25 格
        //（"插"被冷却挡着而已）；扫描里现在多了一次"真实光源盘点"，不设闸会在没料时白烧 CPU。
        if (gameTime % SCAN_GAP != 0L) {
            return;
        }
        if (!(maid.getOwner() instanceof ServerPlayer owner)) {
            return;
        }
        if (!owner.isAlive()) {
            return;
        }
        if (maid.position().distanceTo(owner.position()) > 8.0) {
            return; // 主人不在身边（> 8 格）不管
        }
        int threshold = com.maidsmart.config.MaidSmartConfig.TORCH_DARK_THRESHOLD.get();
        net.minecraft.core.BlockPos base = owner.blockPosition();
        // v1.1.0 实测三十五（反馈："检索间隔隔得有点儿嫌久"）：扫描范围 3×3 → 5×5
        //（主人脚下半径 2 格）——3×3 只有一格宽的跟随带，走路时边缘暗格根本轮不到
        // 检索；5×5 覆盖正常走路节奏的暗区。每轮仍只插【一根】（插最暗格），节奏
        // 由冷却控制，不会一次连插一片。
        // 改为原版 getMaxLocalRawBrightness 同款合并口径：effective = max(方块光, 天光 - 天空变暗度)。
        // getSkyDarken（getSkyDarken，javap 反编译核实）= (1-雨)×(1-雷)×(1-昼曲线)×11：正午 0、
        // 深夜 11、暴雨白天也升到 8+——白天/雨天不插，夜晚地表 15-11=4 照插，
        // 地下天光 0 走方块光口径不变。阈值 7 的语义从此才真正成立。
        int skyDarken = level.getSkyDarken();
        // 实测八百四十七【候选格：25 格里挑最暗的**可放**格】——旧版只取唯一的"最暗一格"，
        // 拿到之后才判空气/被占/支撑，任一不过就整轮 return：矿道贴墙（最暗那格是墙）、
        // 家具/箱子多（最暗那格是家具）、主人刚好站在那格上 → 她永远不插。现在三关前移：
        // 只在**真能放下**的格子里比亮度，最暗的那一格放不了就看第二暗的。
        // 实测八百五十一【亮度口径改版】——她自己的手持光源（脚底光块）只按**上界**排除：
        // 某格"读数 ≤ 她自己的上界"时真实值未知（可能全是她自己照的，也可能盖着真实光源）
        // → 这批格子进 masked，等下面那次"真实光源盘点"定夺。
        net.minecraft.core.BlockPos target = null;
        int darkest = threshold;
        int darkCells = 0;    // 判为"要插"的格数（诊断）
        int blockedCells = 0; // 其中"放不了"的格数（诊断）
        int coveredCells = 0; // 其中"其实已被真实光源照到"的格数（诊断）
        java.util.List<net.minecraft.core.BlockPos> masked = null; // 读数可能全是她自己照的（等她那一轮）
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                net.minecraft.core.BlockPos p = base.offset(dx, 0, dz);
                int raw = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, p);
                int sky = Math.max(0, level.getBrightness(net.minecraft.world.level.LightLayer.SKY, p) - skyDarken);
                int own = com.maidsmart.tool.MaidHeldLight.ownLightUpperBound(maid, level, p);
                boolean selfLit = own > 0 && raw <= own;
                if (sky >= threshold || (!selfLit && raw >= threshold)) {
                    continue; // 够亮：天光够，或方块光的**真实读数**够（她自己的光不算数）
                }
                darkCells++;
                // 目标格可放（空气/可替换）——实测五百三十六：被主人碰撞箱占着也不插
                if (!level.getBlockState(p).isAir()
                        || com.maidsmart.tool.MaidPlaceGuard.blockedAtOwner(maid, p)) {
                    blockedCells++;
                    continue;
                }
                // v1.1.0 修复：脚下必须是【有支撑面】的实体方块——旧版只判"非空气"，
                // 于是火把会插在短草/花/已有火把（无碰撞方块）上悬空。改用原版放置规则
                // 判定（isFaceSturdy 朝上），与玩家手动插火把口径一致。
                net.minecraft.core.BlockPos below = p.offset(0, -1, 0);
                if (!level.getBlockState(below).isFaceSturdy(level, below, net.minecraft.core.Direction.UP)) {
                    blockedCells++; // 悬空 / 无支撑面（草、火把、雪层等都不行）
                    continue;
                }
                if (selfLit) {
                    if (masked == null) {
                        masked = new java.util.ArrayList<>();
                    }
                    masked.add(p); // 真实值未知：等下面的"真实光源盘点"
                    continue;
                }
                int light = Math.max(raw, sky);
                if (light < darkest) {
                    darkest = light;
                    target = p;
                }
            }
        }
        // 实测八百五十一【真实光源盘点】：只在她自己的光盖住读数（masked 非空）时才做——
        // 盘点一次主人周围 reach+2 格内的真实发光方块（排除她自己的隐藏光块），给每个 masked
        // 格定夺：有真实光源能给它留下 ≥ 阈值的光 → 这地方已经有灯了（不插，也就是"别再挨着
        // 火把插一根"）；否则按暗格候选（真实方块光未知但 < 阈值，按天光估亮度）。
        // reach = 15 − 阈值：发光 15 的东西也不可能更远还给某格留够阈值；再 +2 覆盖 5×5 边角。
        if (masked != null && !masked.isEmpty()) {
            int reach = Math.min(8, Math.max(0, 15 - threshold));
            java.util.List<com.maidsmart.tool.MaidHeldLight.RealSource> real =
                    com.maidsmart.tool.MaidHeldLight.realSourcesAround(maid, level, base, reach + 2);
            for (net.minecraft.core.BlockPos p : masked) {
                boolean lit = false;
                for (com.maidsmart.tool.MaidHeldLight.RealSource s : real) {
                    if (s.covers(p, threshold)) {
                        lit = true;
                        break;
                    }
                }
                if (lit) {
                    coveredCells++;
                    continue; // 这块其实已被真实光源照到（她自己的光遮住了读数）→ 不插
                }
                int sky = Math.max(0, level.getBrightness(net.minecraft.world.level.LightLayer.SKY, p) - skyDarken);
                if (sky < darkest) {
                    darkest = sky;
                    target = p;
                }
            }
        }
        if (target == null) {
            // 实测八百四十七：两种"不插"分开记一句（限频；状态变化立刻记）——下一轮日志
            // 搜「插火把」就能一眼看出是"周围够亮 / 已被真实光源照到"还是"暗但一个都放不了"。
            if (darkCells > 0) {
                diag(maid, gameTime, "noSpot", "周围有 " + darkCells + " 个暗格：放不了 " + blockedCells
                        + " 个（非空气 / 被主人占 / 脚下没有支撑面）、已被真实光源照到 " + coveredCells
                        + " 个 → 这一轮不插");
            }
            return; // 周围够亮，或暗格全都放不了 / 都已被真实光源照到
        }
        // 找背包火把并放置（v1.1.0 实测五：兼容灵魂火把等——普通火把优先，
        // 没有才退而求其次；放置用对应火把自己的方块而不是写死 TORCH）
        // v1.1.0 实测二百三十一（审计：主副手识别）：背包没有则用主手/副手拿的
        // 实测八百四十七【口径钉死】：**包里（含精妙背包，findTorch 里 pull 已问过）
        // 有就绝不动她的槽位**——不要求手持、也不把火把在手与包之间搬来搬去；
        // 手上拿着只是"也行"的兜底。放置/扣料/挥手三步，见下。
        int slot = findTorch(maid);
        net.neoforged.neoforge.items.IItemHandler inv = maid.getAvailableBackpackInv();
        ItemStack torch;
        boolean fromHands = false;
        if (slot >= 0) {
            torch = inv.getStackInSlot(slot);
        } else {
            torch = isTorchItem(maid.getMainHandItem()) ? maid.getMainHandItem()
                    : (isTorchItem(maid.getOffhandItem()) ? maid.getOffhandItem() : ItemStack.EMPTY);
            fromHands = true;
        }
        if (torch.isEmpty()) {
            // 实测八百四十七：留痕——背包、精妙背包（pull 已问）、双手都没有火把
            diag(maid, gameTime, "noTorch", "身上一根火把都没有（背包 / 精妙背包 / 主手 / 副手都问过了）→ 不插");
            return; // 背包和手上都没有火把
        }
        Block placedBlock = torchBlockOf(torch);
        if (placedBlock == null) {
            return; // 保险：取不到方块（理论上 findTorch 已过滤）
        }
        if (!level.setBlock(target, placedBlock.defaultBlockState(), 3)) {
            return; // 放不下（被占 / 已经是同一种方块）→ 一根都不扣，下轮再看
        }
        // v1.1.0 实测十六（审查 P2）：消耗改 extractItem——旧版直缩 getStackInSlot
        // 返回栈，handler 返回副本时扣不掉（无限插火把刷方块）；与工程其他消耗点统一。
        //
        // 【实测七百六十二·issue #19 三修】上面那条注释说的坑其实只堵了一半：extractItem 在
        // **坏包装层**下会"返回副本却不真扣"，于是火把照插、材料一件没少——与搭路/战斗放置
        // 完全同一形状。现在改成"扣完核对槽位总数真的减了 1"（extractOneHonest）；扣不到就把
        // 刚放下的那根**拆掉回滚**——宁可这次不插，也绝不放一根不扣一根。顺序仍是"先放后扣"，
        // 保留原语义：放置失败时不该扣她的料。
        boolean paid;
        try {
            if (fromHands) {
                paid = com.maidsmart.tool.MaidBuildBlockFilter.extractOneHonest(
                        (net.neoforged.neoforge.items.IItemHandlerModifiable) maid.getHandsInvWrapper(),
                        isTorchItem(maid.getMainHandItem()) ? 0 : 1);
            } else {
                paid = com.maidsmart.tool.MaidBuildBlockFilter.extractOneHonest(inv, slot);
            }
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("MaidTorchPlacerBehavior", ignored);
            paid = false;
        }
        if (!paid) {
            level.removeBlock(target, false); // 回滚：没付账就不留这一根
            return;
        }
        // 实测八百四十七【只挥手一次】：v1.2.2 实测五百九十七 起这里还有一记
        // BombPose.showGated（副手短暂举起那根火把的"拟真"表现）——开发者本轮明确
        // 「不再要求原来的拟真行为」「不再要求对火把在手和包的位置进行变动」→ 删掉，
        // 只留下面这一次挥手（"播放一次动作"）+ 上面的扣 1 根。
        maid.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        // 实测八百五十一【插的间隔】：10 tick（0.5 秒）→ 40 tick（2 秒）——开发者实机看到"连插、
        // 一点间隔都没有"（夜晚/地下周围总有暗格，0.5 秒一根就等于连插）；回调到 2 秒一根。
        // 历史沿革：30 tick（1.5 秒）→ 15 → 10（实测三十五"检索间隔隔得有点儿嫌久"时压下来的）。
        this.torchCooldown = 40; // 2 秒
    }

    /**
     * （灵魂火把第二；含常见模组火把的注册名，找不到的自动跳过）。
     * 灵魂火把亮度 10 比普通火把 14 低，但在下界/驱猪场景有独特价值——
     * 有普通火把绝不用它（玩家指定优先级）。
     * v1.1.0 实测十六（审查 P2）：移出 redstone_torch——它亮度只有 7（临界）且
     * 【会发射红石信号】：黑暗中自动"照明"会意外激活附近的红石灯/活塞/门/
     * TNT 类机关，作为照明清单成员风险不对称，不配当自动放置的备选。
     */
    private static final String[] TORCH_IDS = {
            "minecraft:torch",
            "minecraft:soul_torch",
            "tacz:torch"
    };

    /** 该物品是否是可插火把（按注册名匹配清单） */
    private static boolean isTorchItem(ItemStack stack) {
        ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (key == null) {
            return false;
        }
        String id = key.toString();
        for (String t : TORCH_IDS) {
            if (t.equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** 火把物品对应的放置方块（BlockItem.getBlock = getBlock）；非火把返回 null */
    private static Block torchBlockOf(ItemStack stack) {
        if (!isTorchItem(stack) || !(stack.getItem() instanceof net.minecraft.world.item.BlockItem bi)) {
            return null;
        }
        return bi.getBlock();
    }

    /**
     * 背包里找火把槽位（普通火把优先——按 TORCH_IDS 顺序逐档找）。
     * v1.1.0 实测五：旧版只认 minecraft:torch（TORCH），灵魂火把在背包里
     * 也当"没有火把"处理；现在全清单兼容且保持优先级。
     */
    private static int findTorch(EntityMaid maid) {
        try {
            // v1.2.4 实测六百三十九【先判背包，再判精妙背包】：自己背包里没有火把 →
            // 先请 TLM 从精妙背包/旅行者背包搬一把进来（谓词 = 本类自己的火把清单，
            // 与下面逐档匹配同一份 TORCH_IDS；搬进来后照原样返回背包槽位，调用方无需改）
            com.maidsmart.tool.MaidExtraContainer.pull(maid,
                    s -> !s.isEmpty() && isTorchItem(s), 1);
            net.neoforged.neoforge.items.IItemHandler inv = maid.getAvailableBackpackInv();
            for (String torchId : TORCH_IDS) {
                Item torchItem = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(ResourceLocation.parse(torchId));
                if (torchItem == null) {
                    continue; // 该火把未注册（模组未装）——跳过这档
                }
                for (int i = 0; i < inv.getSlots(); i++) {
                    ItemStack stack = inv.getStackInSlot(i);
                    if (!stack.isEmpty() && stack.getItem() == torchItem) {
                        return i;
                    }
                }
            }
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("MaidTorchPlacerBehavior", ignored);
        }
        return -1;
    }

    /**
     * 实测八百四十七【限频留痕】：只在"这一轮的结论变了"或"距上次 ≥ 10 秒"时记一句，
     * 搜「插火把」即可看到她为什么一根都没插（没有料 / 暗但一个都放不了）。
     * 口径与额外容器那条取物留痕一致（状态变化立刻记 + 时间窗防刷屏）。
     */
    private static final java.util.Map<java.util.UUID, Long> DIAG_TICK =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final java.util.Map<java.util.UUID, String> DIAG_KIND =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static void diag(EntityMaid maid, long gameTime, String kind, String msg) {
        try {
            java.util.UUID id = maid.getUUID();
            if (kind.equals(DIAG_KIND.get(id))) {
                Long last = DIAG_TICK.get(id);
                if (last != null && gameTime - last < 200L) {
                    return; // 同一种结论，10 秒内不重复
                }
            }
            DIAG_KIND.put(id, kind);
            DIAG_TICK.put(id, gameTime);
            com.maidsmart.tool.PromaidLog.log("插火把",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " " + msg);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidTorchPlacerBehavior", ignored);
        }
    }

    /** v1.5.215：诊断——主人脚下 3×3 里最暗的方块亮度（LightLayer.BLOCK，0-15） */
    private static int darkestAround(ServerLevel level, net.minecraft.core.BlockPos base) {
        int darkest = 15;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int light = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK,
                        base.offset(dx, 0, dz));
                if (light < darkest) {
                    darkest = light;
                }
            }
        }
        return darkest;
    }
}
