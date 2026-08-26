package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.TextChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 蛋糕投喂与好感加成（实测一百二十五）：
 * - 女仆吃完蛋糕好感 +10——TLM 原版工作餐/家餐只按营养随机给 0~1 点，蛋糕没有额外
 *   价值；这里统一为"每次吃完一整块蛋糕 = +10 好感"（与 MaidCakeFavorMixin 同口径，
 *   玩家投喂和女仆自吃都算）。
 * - 玩家用蛋糕右击自己的女仆 = 女仆立刻触发吃掉事件：玩家手上蛋糕消失（消耗 1 个）、
 *   女仆播放吃音效+蛋糕粒子，并触发蓝色系统消息（主人聊天框）与蓝色世界内气泡，
 *   格式：女仆名：......
 *
 * 注意：气泡走 addChatBubble 直发而非 addTextChatBubble——后者会被
 * ChatBubbleLimitMixin 统一染成青色（§b）且受 5 秒限频；投喂是玩家主动操作，
 * 需要蓝色（§9）即时反馈，故绕过。
 */
public class MaidCakeEatHandler {
    /** 每次吃完一块蛋糕的好感加成 */
    public static final int CAKE_FAVOR_POINTS = 10;

    /** 是否为蛋糕物品（与 MaidCakeEdibleMixin 同一判定口径：注册名 minecraft:cake） */
    public static boolean isCake(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return false;
        }
        net.minecraft.resources.ResourceLocation key =
                net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.m_41720_());
        return key != null && "minecraft:cake".equals(key.toString());
    }

    /**
     * 女仆吃完蛋糕（TLM 语义：onMaidEat 进食动画开始瞬间即生效——与 TLM 原版好感
     * apply 时机一致）。notify=true 时（玩家投喂）额外弹蓝色气泡 + 主人系统消息；
     * 女仆自吃静默，只有 FavorabilityManager.add 自带的心形粒子反馈。
     */
    public static void onCakeEaten(EntityMaid maid, boolean notify) {
        if (maid.m_9236_().m_5776_()) {
            return; // 仅服务端改数值/发消息
        }
        maid.getFavorabilityManager().add(CAKE_FAVOR_POINTS); // 自带升阶/心形粒子/事件
        if (notify) {
            showCakeMessage(maid);
        }
    }

    /** 蓝色系统消息（主人聊天框，格式：女仆名：......）+ 蓝色世界内气泡（只显示内容） */
    private static void showCakeMessage(EntityMaid maid) {
        String name = maid.m_5446_().getString();
        String text = "蛋糕真好吃，主人的心意我收到啦～";
        if (maid.m_269323_() instanceof ServerPlayer owner) {
            owner.m_213846_(Component.m_237113_("\u00a79" + name + "\uff1a" + text));
        }
        maid.getChatBubbleManager().addChatBubble(
                TextChatBubbleData.type2(Component.m_237113_(text).m_130940_(ChatFormatting.BLUE)));
    }

    /** 玩家蛋糕右击自己的女仆：取消原交互（不开 GUI）→ 玩家蛋糕消失 → 女仆立刻吃 */
    @SubscribeEvent
    public void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !(event.getTarget() instanceof EntityMaid maid)
                || !maid.m_21830_(player)) {
            return;
        }
        InteractionHand hand = event.getHand();
        ItemStack stack = player.m_21120_(hand);
        if (!isCake(stack)) {
            return;
        }
        event.setCanceled(true);
        // 玩家手上蛋糕消失 + 玩家挥手动画
        stack.m_41774_(1);
        player.m_6674_(hand);
        // 女仆立刻触发吃掉事件：吃音效 + 蛋糕粒子 + 好感加成 + 蓝色消息
        maid.m_5496_(SoundEvents.f_11912_, 1.0f, 1.0f);
        maid.m_21060_(new ItemStack(Items.f_42502_), 8);
        onCakeEaten(maid, true);
    }
}
