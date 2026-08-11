package com.maidsmart.tool;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.BoolParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * smart_place（v1.5.190）：从女仆背包取出方块放到指定位置——"帮我把这里填上"、
 * "把我脚下铺平"、"这里缺一块补上"这类指令的落点（区别于蓝图建造的大工程）。
 *
 * 参数：block（方块 id）、x/y/z（放置坐标，默认女仆前方 2 格）、
 * replace（true = 可覆盖空气/水/草等可替换方块，false = 只在空气处放，默认 false）。
 *
 * 规则：
 * - 只放背包里实际有的方块（物品守恒，不凭空生成）
 * - 放不下（目标被挡住 / 脚下悬空）时报原因，不硬放
 * - 每次调用最多放 8 个（防一次调太多把背包掏空/刷屏）
 */
public class SmartPlaceTool implements ITool<SmartPlaceTool.Result> {
    public static final String TOOL_ID = "smart_place";

    private static final String TOOL_DESC = "Place a block from your inventory at a position, "
            + "like helping the owner fill/patch/level an area.\n"
            + "block: the block id (e.g. 'minecraft:dirt', 'minecraft:oak_planks').\n"
            + "x/y/z: target coordinates (default: 2 blocks in front of you).\n"
            + "replace: true to overwrite air/water/tall-grass (false by default, only places into air).\n"
            + "Places up to 8 blocks per call, only blocks you actually carry.";

    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("block").forGetter(Result::block),
            Codec.INT.optionalFieldOf("x", Integer.MIN_VALUE).forGetter(r -> r.x()),
            Codec.INT.optionalFieldOf("y", Integer.MIN_VALUE).forGetter(r -> r.y()),
            Codec.INT.optionalFieldOf("z", Integer.MIN_VALUE).forGetter(r -> r.z()),
            Codec.BOOL.optionalFieldOf("replace", false).forGetter(Result::replace)
    ).apply(instance, Result::new));

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return TOOL_DESC;
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        root.addProperties("block", StringParameter.create()
                .setDescription("要放置的方块 id（如 minecraft:dirt / minecraft:oak_planks）"));
        root.addProperties("x", com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.IntegerParameter.create()
                .setDescription("目标 X 坐标（默认女仆前方 2 格）"));
        root.addProperties("y", com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.IntegerParameter.create()
                .setDescription("目标 Y 坐标"));
        root.addProperties("z", com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.IntegerParameter.create()
                .setDescription("目标 Z 坐标"));
        root.addProperties("replace", BoolParameter.create()
                .setDescription("true=可覆盖空气/水/草丛，false=只在空气处放（默认 false）"));
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolId, Result result, LLMCallback callback) {
        if (!com.maidsmart.config.MaidSmartConfig.TOOL_PLACE.get()) {
            return callback.addToolResult("smart_place 工具已被禁用（配置面板 AI 工具页可开启）。", toolId);
        }
        EntityMaid maid = callback.getMaid();
        if (!(maid.m_9236_() instanceof ServerLevel level)) {
            return callback.addToolResult("放置方块需要在服务端进行", toolId);
        }
        String blockStr = result.block() == null ? "" : result.block().trim();
        if (blockStr.isEmpty()) {
            return callback.addToolResult("请提供要放置的方块 id（block 参数）。", toolId);
        }
        ResourceLocation blockId;
        try {
            blockId = ResourceLocation.m_135820_(blockStr); // parse（非法 id 抛异常→兜底）
        } catch (Exception ex) {
            return callback.addToolResult("方块 id 格式不对：" + blockStr, toolId);
        }
        if (blockId == null) {
            return callback.addToolResult("方块 id 格式不对：" + blockStr, toolId);
        }
        if (blockId == null) {
            return callback.addToolResult("方块 id 格式不对：" + blockStr, toolId);
        }
        Item blockItem = ForgeRegistries.ITEMS.getValue(blockId);
        if (blockItem == null || !(blockItem instanceof BlockItem)) {
            return callback.addToolResult("这不是一个可放置的方块：" + blockStr, toolId);
        }
        // 背包里找这种方块（只消耗实际有的）
        IItemHandler inv = maid.getMaidInv();
        int slot = -1;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.m_41619_() && s.m_41720_() == blockItem) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            return callback.addToolResult("我背包里没有「" + blockStr + "」方块，给不了。", toolId);
        }
        // 目标坐标：默认女仆前方 2 格（视线方向，取整数块）
        BlockPos target;
        if (result.x() != Integer.MIN_VALUE && result.y() != Integer.MIN_VALUE && result.z() != Integer.MIN_VALUE) {
            target = new BlockPos(result.x(), result.y(), result.z());
        } else {
            BlockPos foot = maid.m_20183_();
            target = foot.m_7918_(0, 0, 0);
            // 视线方向（水平）：女仆 look 向量 → 前方块
            double yaw = Math.toRadians(maid.m_146908_()); // getYRot
            int dx = (int) Math.round(-Math.sin(yaw));
            int dz = (int) Math.round(Math.cos(yaw));
            if (dx == 0 && dz == 0) {
                dz = 1;
            }
            target = foot.m_7918_(dx, 0, dz);
        }
        // 距离检查（别隔老远隔山打牛）——m_123331_ = distSqr（平方距离 > 144 = 12 格）
        if (maid.m_20183_().m_123331_(target) > 144) {
            return callback.addToolResult("目标位置离我太远（超过 12 格），走近一点再让我放。", toolId);
        }
        // 逐个放置（最多 8 个）
        int placed = 0;
        int fail = 0;
        int count = inv.getStackInSlot(slot).m_41613_();
        for (int i = 0; i < 8 && count > 0; i++) {
            BlockPos p = target.m_7918_(0, i / 4 == 0 ? 0 : 1, 0); // 前 4 个放目标行，后 4 个上一行
            BlockState cur = level.m_8055_(p);
            // 可放条件：空气/水（流体空=空气或水？用 isEmpty）或 replace=true 且方块本身可替换
            // （m_247087_ = canBeReplaced：水/草丛/雪/藤蔓等，replace=true 时覆盖它们）
            boolean ok = cur.m_60795_() || cur.m_60819_().m_76178_()
                    || (result.replace() && cur.m_247087_());
            if (!ok) {
                fail++;
                continue;
            }
            // 脚下要有支撑（悬空不放）
            BlockState below = level.m_8055_(p.m_7495_());
            if (below.m_60795_()) {
                fail++;
                continue;
            }            ItemStack taken = inv.extractItem(slot, 1, false);
            if (taken.m_41619_()) {
                break;
            }
            level.m_7731_(p, ((BlockItem) blockItem).m_40614_().m_49966_(), 3); // getBlock().defaultBlockState()
            placed++;
            count--;
        }
        String msg = "放了 " + placed + " 个「" + blockStr + "」"
                + (fail > 0 ? "，有 " + fail + " 个位置放不了（被挡住或悬空）" : "");
        if (placed == 0) {
            return callback.addToolResult("没能放上任何方块（目标位置都被挡住或悬空）。换个位置试试，或者把 replace 设为 true 覆盖可替换方块。", toolId);
        }
        return callback.addToolResult(msg, toolId);
    }

    /** 工具参数 */
    public record Result(String block, int x, int y, int z, boolean replace) {
    }
}
