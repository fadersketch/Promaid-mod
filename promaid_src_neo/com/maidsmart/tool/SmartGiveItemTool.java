package com.maidsmart.tool;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * smart_give_item：把背包中的指定物品交给主人（最多一整组）。
 * 主人背包满时物品自动退回女仆背包，不会凭空产生或销毁物品。
 */
public class SmartGiveItemTool implements ITool<SmartGiveItemTool.Result> {
    public static final String TOOL_ID = "smart_give_item";
    private static final String ITEM_PARAM_ID = "item";
    private static final String TOOL_DESC = "Use this when the user asks you to give them an item from your inventory.\n"
            + "Set item to the item id (e.g. 'minecraft:diamond', 'minecraft:cooked_beef').\n"
            + "You can only give items you actually carry.";
    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf(ITEM_PARAM_ID).forGetter(Result::item)
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
        StringParameter item = StringParameter.create()
                .setDescription("The registered item id, e.g. minecraft:diamond");
        root.addProperties(ITEM_PARAM_ID, item);
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolId, Result result, LLMCallback callback) {
        EntityMaid maid = callback.getMaid();
        LivingEntity owner = maid.getOwner();
        if (!(owner instanceof ServerPlayer)) {
            return callback.addToolResult("No owner found", toolId);
        }
        ResourceLocation itemId = ResourceLocation.parse(result.item());
        Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(itemId);
        if (item == null) {
            return callback.addToolResult("Unknown item id: " + result.item(), toolId);
        }
        IItemHandler maidInv = maid.getMaidInv();
        for (int i = 0; i < maidInv.getSlots(); i++) {
            ItemStack stack = maidInv.getStackInSlot(i);
            if (stack.isEmpty() || stack.getItem() != item) {
                continue;
            }
            ItemStack toGive = maidInv.extractItem(i, stack.getCount(), false);
            ItemStack remain = ItemHandlerHelper.insertItemStacked(new net.neoforged.neoforge.items.wrapper.InvWrapper(((ServerPlayer) owner).getInventory()), toGive, false);
            if (!remain.isEmpty()) {
                ItemHandlerHelper.insertItemStacked(maidInv, remain, false);
            }
            return callback.addToolResult("Gave " + toGive.getCount() + "x " + result.item(), toolId);
        }
        return callback.addToolResult("You don't have any " + result.item() + " in your inventory", toolId);
    }


    @Override
    public java.util.concurrent.CompletableFuture<LLMCallback> onCallAsync(
            String toolCallId, Result result, LLMCallback callback,
            com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient client) {
        EntityMaid maid = callback.getMaid();
        if (maid.level().isClientSide()) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    callback.addToolResult("Cannot run on client side", toolCallId));
        }
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) maid.level();
        java.util.concurrent.CompletableFuture<LLMCallback> future = new java.util.concurrent.CompletableFuture<>();
        level.getServer().execute(() -> {
            try {
                future.complete(onCall(toolCallId, result, callback));
            } catch (Throwable t) {
                future.complete(callback.addToolResult("Tool execution failed: " + t, toolCallId));
            }
        });
        return future;
    }

    public record Result(String item) {
    }
}
