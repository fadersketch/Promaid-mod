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
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;

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
        LivingEntity owner = maid.m_269323_();
        if (!(owner instanceof ServerPlayer)) {
            return callback.addToolResult("No owner found", toolId);
        }
        ResourceLocation itemId = ResourceLocation.parse(result.item());
        Item item = ForgeRegistries.ITEMS.getValue(itemId);
        if (item == null) {
            return callback.addToolResult("Unknown item id: " + result.item(), toolId);
        }
        IItemHandler maidInv = maid.getMaidInv();
        for (int i = 0; i < maidInv.getSlots(); i++) {
            ItemStack stack = maidInv.getStackInSlot(i);
            if (stack.m_41619_() || stack.m_41720_() != item) {
                continue;
            }
            ItemStack toGive = maidInv.extractItem(i, stack.m_41613_(), false);
            ItemStack remain = ItemHandlerHelper.insertItemStacked(new net.minecraftforge.items.wrapper.InvWrapper(((ServerPlayer) owner).m_150109_()), toGive, false);
            if (!remain.m_41619_()) {
                ItemHandlerHelper.insertItemStacked(maidInv, remain, false);
            }
            return callback.addToolResult("Gave " + toGive.m_41613_() + "x " + result.item(), toolId);
        }
        return callback.addToolResult("You don't have any " + result.item() + " in your inventory", toolId);
    }

    public record Result(String item) {
    }
}
