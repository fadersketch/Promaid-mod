package com.maidsmart.tool;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * smart_owner_inventory（v1.5.287）：查看主人物品栏工具——LLM 对话中按需调用，
 * 确认主人背包里有什么（用户："查看主人的物品栏来确认与获得主人物品栏中有什么东西"）。
 * 只读遍历（BlueprintLib.countPlayerMaterial 同款写法），不修改任何物品；
 * 输出合并计数（物品 id ×数量 + 槽位区），限行数防 token 爆表。
 */
public class OwnerInventoryTool implements ITool<OwnerInventoryTool.Result> {
    public static final String TOOL_ID = "smart_owner_inventory";

    private static final String TOOL_DESC = "Use this when you need to know what items the owner is currently "
            + "carrying (e.g. the user asks 'what do I have in my inventory', or you need to check whether "
            + "the owner has some material/equipment before you act).\n"
            + "Optional: pass 'item' as an item id (e.g. 'minecraft:diamond') to only list matching items.\n"
            + "Returns merged counts: item id x count with slot area (hotbar/main/armor/offhand). "
            + "Read-only, never modifies the owner's inventory.";

    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("item", "").forGetter(Result::item)
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
                .setDescription("可选：物品 ID 过滤（如 minecraft:diamond），留空则列出主人背包全部物品"));
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolId, Result result, LLMCallback callback) {
        if (!com.maidsmart.config.MaidSmartConfig.TOOL_OWNER_INVENTORY.get()) {
            return callback.addToolResult("查看主人物品栏工具已被禁用（设置可开启）。", toolId);
        }
        EntityMaid maid = callback.getMaid();
        if (!(maid.m_9236_() instanceof net.minecraft.server.level.ServerLevel)) {
            return callback.addToolResult("背包查询需要在服务端进行", toolId);
        }
        // 主人判空（未认领/离线时 m_269323_ 可能为 null 或非玩家实体）
        LivingEntity owner = maid.m_269323_();
        if (!(owner instanceof Player player)) {
            return callback.addToolResult("女仆当前没有主人（或主人不在线），无法查看物品栏。", toolId);
        }
        String filter = result.item() == null ? "" : result.item().trim().toLowerCase(java.util.Locale.ROOT);
        // 只读遍历玩家背包（0-8 快捷栏 / 9-35 主区 / 36-39 护甲 / 40 副手）
        net.minecraft.world.entity.player.Inventory inv = player.m_150109_();
        java.util.Map<String, int[]> counts = new java.util.LinkedHashMap<>(); // id -> {count, areaBit}
        int listed = 0;
        for (int i = 0; i < inv.m_6643_() && i <= 40; i++) {
            ItemStack stack = inv.m_8020_(i);
            if (stack.m_41619_()) {
                continue;
            }
            net.minecraft.resources.ResourceLocation rl = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
            String id = rl == null ? "unknown" : rl.toString();
            if (!filter.isEmpty() && !id.contains(filter)) {
                continue;
            }
            int area = i < 9 ? 1 : i < 36 ? 2 : 3; // 1=hotbar 2=main 3=armor+offhand
            int[] cur = counts.computeIfAbsent(id, k -> new int[]{0, 0});
            cur[0] += stack.m_41613_();
            cur[1] |= area;
            listed++;
        }
        if (listed == 0) {
            return callback.addToolResult(filter.isEmpty()
                    ? "主人的背包是空的。" : "主人背包里没有与「" + filter + "」相关的物品。", toolId);
        }
        StringBuilder sb = new StringBuilder("主人背包内容（");
        sb.append(filter.isEmpty() ? "全部" : "筛选 " + filter).append("，共 ").append(counts.size())
                .append(" 种物品）：\n");
        int lines = 0;
        for (java.util.Map.Entry<String, int[]> e : counts.entrySet()) {
            if (lines >= 30) {
                sb.append("- …（其余省略）\n");
                break;
            }
            int[] cur = e.getValue();
            String area = (cur[1] & 1) != 0 ? (cur[1] & 2) != 0 ? "快捷栏+主区" : "快捷栏"
                    : (cur[1] & 2) != 0 ? "主区" : "护甲/副手";
            sb.append("- ").append(e.getKey()).append(" ×").append(cur[0])
                    .append("（").append(area).append("）\n");
            lines++;
        }
        return callback.addToolResult(sb.toString(), toolId);
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
