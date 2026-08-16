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
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.ArrayList;
import java.util.List;

/**
 * smart_craft（v1.5.190）：按配方合成物品——让女仆拥有"玩家合成"的能力。
 * LLM 传 item（目标物品 id，如 minecraft:golden_apple）：
 * 1. 从 RecipeManager 找第一个【输出物品匹配】的无序/有序合成配方；
 * 2. 从女仆背包逐个抽取配方材料（不拆堆、不卡槽，放得下才抽）；
 * 3. 结果物品（配方输出）交给主人背包，放不下退回女仆背包；
 * 4. 材料不足时报告缺什么（名字 + 差几个）。
 *
 * 不做的东西：不做任何凭空生成/复制；不碰女仆手上装备；
 * 只消耗背包里的材料——所有物品守恒。
 */
public class SmartCraftTool implements ITool<SmartCraftTool.Result> {
    public static final String TOOL_ID = "smart_craft";

    private static final String TOOL_DESC = "Craft an item for the owner using recipes (like a player would).\n"
            + "item: the output item id to craft, e.g. 'minecraft:golden_apple', 'minecraft:torch'.\n"
            + "Materials are taken from your own inventory. The crafted result is given to the owner; "
            + "if their inventory is full the result goes back to you.\n"
            + "If you lack materials, the reply lists what is missing.\n"
            + "Only craft items you can actually make with available materials.";

    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("item").forGetter(Result::item)
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
        root.addProperties("item", StringParameter.create()
                .setDescription("要合成的物品 id（如 minecraft:golden_apple / minecraft:torch）"));
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolId, Result result, LLMCallback callback) {
        // v1.5.190：工具开关（配置面板 aitools.craft）
        if (!com.maidsmart.config.MaidSmartConfig.TOOL_CRAFT.get()) {
            return callback.addToolResult("smart_craft 工具已被禁用（配置面板 AI 工具页可开启）。", toolId);
        }
        EntityMaid maid = callback.getMaid();
        LivingEntity owner = maid.m_269323_();
        if (!(owner instanceof ServerPlayer)) {
            return callback.addToolResult("合成需要主人（Owner）在场才能接收成品。", toolId);
        }
        if (!(maid.m_9236_() instanceof net.minecraft.server.level.ServerLevel level)) {
            return callback.addToolResult("合成需要在服务端进行", toolId);
        }
        String itemStr = result.item() == null ? "" : result.item().trim();
        if (itemStr.isEmpty()) {
            return callback.addToolResult("请提供要合成的物品 id（item 参数）。", toolId);
        }
        ResourceLocation outId;
        try {
            outId = ResourceLocation.m_135820_(itemStr); // parse（非法 id 抛异常→兜底）
        } catch (Exception ex) {
            return callback.addToolResult("物品 id 格式不对：" + itemStr, toolId);
        }
        if (outId == null) {
            return callback.addToolResult("物品 id 格式不对：" + itemStr, toolId);
        }
        if (outId == null) {
            return callback.addToolResult("物品 id 格式不对：" + itemStr, toolId);
        }
        Item outItem = ForgeRegistries.ITEMS.getValue(outId);
        if (outItem == null) {
            return callback.addToolResult("未知物品：" + itemStr + "（试试带命名空间的完整 id，如 minecraft:golden_apple）", toolId);
        }
        RecipeManager recipes = level.m_7465_(); // getRecipeManager
        CraftingRecipe recipe = findRecipe(recipes, outItem, level.m_9598_()); // registryAccess
        if (recipe == null) {
            return callback.addToolResult("没有找到能做出「" + itemStr + "」的合成配方（可能无法合成，或配方不在此服务端）。", toolId);
        }
        ItemStack recipeOut = recipe.m_8043_(level.m_9598_()); // getResultItem(RegistryAccess)
        if (recipeOut.m_41619_()) {
            return callback.addToolResult("配方输出为空，无法合成。", toolId);
        }
        // 从女仆背包抽取材料（每种材料按余数匹配任意可用的物品槽）
        List<Ingredient> ings = recipe.m_7527_(); // getIngredients
        List<ItemStack> used = new ArrayList<>();
        StringBuilder missing = new StringBuilder();
        net.minecraftforge.items.IItemHandler maidInv = maid.getMaidInv();
        for (Ingredient ing : ings) {
            // v1.5.190b：有序配方 getIngredients() 返回 3×3 共 9 格，空槽是
            // Ingredient.EMPTY（getItems 为空数组）——必须跳过，否则会把空槽
            // 误报成"缺某种材料×1"（材料齐了也永远报缺料）
            if (ing == null || ing.m_43908_().length == 0) {
                continue;
            }
            int need = 1; // 每次抽 1 个（有序/无序配方每个格子恰好需要 1 个材料）
            while (need > 0) {
                int slot = findSlot(maidInv, ing);
                if (slot < 0) {
                    break;
                }
                ItemStack stack = maidInv.extractItem(slot, 1, false);
                if (stack.m_41619_()) {
                    break;
                }
                used.add(stack);
                need--;
            }
            if (need > 0) {
                // 报告缺什么（用配方的展示名）——m_43908_() = getItems()
                String display = ing.m_43908_().length > 0 && !ing.m_43908_()[0].m_41619_()
                        ? ing.m_43908_()[0].m_41786_().getString() : "某种材料";
                if (missing.length() > 0) {
                    missing.append("、");
                }
                missing.append(display).append("×").append(need);
                // 已抽出来的还回去（失败回滚，物品守恒）
                rollback(maidInv, used);
                return callback.addToolResult("材料不够，还缺：" + missing + "。请先给我这些材料再让我合成。", toolId);
            }
        }
        // 材料齐了：产出交给主人（背包满退回女仆）
        int count = recipeOut.m_41613_();
        ItemStack crafted = recipeOut.m_41777_();
        ItemStack remain = ItemHandlerHelper.insertItemStacked(
                new InvWrapper(((ServerPlayer) owner).m_150109_()), crafted, false);
        if (!remain.m_41619_()) {
            ItemHandlerHelper.insertItemStacked(maidInv, remain, false);
        }
        return callback.addToolResult("合成成功：用掉背包里的材料，做出了 "
                + count + "x " + itemStr + " 交给了主人"
                + (!remain.m_41619_() ? "（主人背包满，放回了我的背包）" : "") + "。", toolId);
    }

    /** 找第一个输出匹配的合成配方（无序/有序都行） */
    private static CraftingRecipe findRecipe(RecipeManager recipes, Item out,
                                             net.minecraft.core.RegistryAccess access) {
        for (CraftingRecipe r : recipes.m_44013_(RecipeType.f_44107_)) { // getAllRecipesFor(CRAFTING)
            ItemStack ro = r.m_8043_(access); // getResultItem（只用来比对 item）
            if (!ro.m_41619_() && ro.m_41720_() == out) {
                return r;
            }
        }
        return null;
    }

    /** 找满足配方的材料槽（跳过空的；每种材料从 0 开始找） */
    private static int findSlot(net.minecraftforge.items.IItemHandler inv, Ingredient ing) {
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.m_41619_() && ing.test(s)) {
                return i;
            }
        }
        return -1;
    }

    /** 失败回滚：把已抽取的材料放回女仆背包 */
    private static void rollback(net.minecraftforge.items.IItemHandler inv, List<ItemStack> used) {
        for (ItemStack s : used) {
            if (!s.m_41619_()) {
                ItemHandlerHelper.insertItemStacked(inv, s, false);
            }
        }
    }

    /** 工具参数 */

    @Override
    public java.util.concurrent.CompletableFuture<LLMCallback> onCallAsync(
            String toolCallId, Result result, LLMCallback callback,
            com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient client) {
        EntityMaid maid = callback.getMaid();
        if (maid.m_9236_().m_5776_()) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    callback.addToolResult("Cannot run on client side", toolCallId));
        }
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) maid.m_9236_();
        java.util.concurrent.CompletableFuture<LLMCallback> future = new java.util.concurrent.CompletableFuture<>();
        level.m_7654_().execute(() -> {
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
