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
import net.minecraftforge.eventbus.api.EventPriority;
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

    /** 启动探测发现其他模组已经改了蛋糕 → 自动停用（配置开关同步置 false）。 */
    private static volatile boolean AUTO_DISABLED = false;

    /** 探测期间本模组 mixin 暂时透明，读到的就是其他模组/原版的结果。 */
    private static volatile boolean PROBING = false;

    /**
     * 【R1102·实测一千一百〇三】会把「可食用物品」直接喂给女仆并取消原交互的附属（反编译实证）：
     * solmaid（生活调味料：酒狐萝贝）的 MaidFeeding 对**任意**女仆——包括野生未驯服的——
     * 只要主手物品有食物属性就塞给她吃掉并 setCanceled：TLM 的驯服交互
     * （EntityMaid.m_6071_ → tameMaid，蛋糕正是驯服物）根本走不到。我们让蛋糕可食用
     * 之后正好武装了它——这类附属在场时蛋糕功能必须让出（与"别的模组改过蛋糕"同一条
     * 自动关闭口径，只是探测的信号不同：那边看蛋糕的食物属性，这边看附属名单 + 运行时抢答）。
     */
    private static final java.util.Set<String> FEED_HIJACKER_MODS = java.util.Set.of("solmaid");

    /** 本次交互是不是【我们自己】取消的（低优先级哨兵用它排除自己的取消，防误报）。 */
    private boolean weCanceledThisEvent = false;

    /** 装了哪个「喂食抢答型」附属；没装返回 null。 */
    private static String loadedFeedHijacker() {
        try {
            for (String id : FEED_HIJACKER_MODS) {
                if (net.minecraftforge.fml.ModList.get().isLoaded(id)) {
                    return id;
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCakeEatHandler.loadedFeedHijacker", ignored);
        }
        return null;
    }

    /** 蛋糕功能总闸：配置开 + 未被冲突探测自动关闭。 */
    public static boolean isEnabled() {
        return !AUTO_DISABLED
                && com.maidsmart.config.MaidSmartConfig.MISC_CAKE_EDIBLE.get();
    }

    /** 供 MaidCakeEdibleMixin 在探测期间让路。 */
    public static boolean isProbing() {
        return PROBING;
    }

    /**
     * 启动探测：其他模组若已让蛋糕可食用（或给了食物属性），就自动关闭本模组的
     * 蛋糕功能，避免两边同时改同一个判定。探测时本模组 mixin 让路，读的是原版
     * 与后续注入的真实结果；开关会置 false，需要恢复时在配置面板手动打开。
     */
    public static void detectExternalCakeModification() {
        try {
            if (AUTO_DISABLED
                    || !com.maidsmart.config.MaidSmartConfig.MISC_CAKE_EDIBLE.get()) {
                return;
            }
            // 【R1102】先看「喂食抢答型」附属：它们不改蛋糕本身，旧探测（只看蛋糕的
            // 食物属性）永远探不到——但只要在场，蛋糕一旦可食用就会被抢去喂掉、
            // 驯服交互被取消。这 must 在挂 mixin 生效路径之外先让出。
            String hijacker = loadedFeedHijacker();
            if (hijacker != null) {
                disableBecauseConflict("喂食型附属 " + hijacker
                        + "（生活调味料：酒狐萝贝）会把可食用物品直接喂给女仆并抢走交互，"
                        + "蛋糕驯服会被截走");
                return;
            }
            PROBING = true;
            net.minecraft.world.food.FoodProperties food =
                    net.minecraft.world.item.Items.f_42502_.m_41473_();
            boolean edible = net.minecraft.world.item.Items.f_42502_.m_41472_();
            if (food != null || edible) {
                disableBecauseConflict("蛋糕已可食用/已有食物属性（营养 "
                        + (food == null ? "-" : food.m_38744_()) + "）");
            } else {
                com.maidsmart.tool.PromaidLog.log("蛋糕可食用",
                        "未检测到其他模组修改蛋糕，按配置开启");
            }
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow(
                    "MaidCakeEatHandler.detectExternalCakeModification", ignored);
        } finally {
            PROBING = false;
        }
    }

    /** 自动关闭（带现场细节）：内存里把开关置 false（面板可见），并留一行明确的日志。 */
    private static void disableBecauseConflict(String detail) {
        AUTO_DISABLED = true;
        try {
            com.maidsmart.config.MaidSmartConfig.MISC_CAKE_EDIBLE.set(false);
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("MaidCakeEatHandler.disableBecauseConflict", ignored);
        }
        com.maidsmart.tool.PromaidLog.log("蛋糕可食用",
                "检测到冲突（" + detail + "），本模组的蛋糕功能已自动关闭"
                        + "（可在配置面板手动恢复；冲突源仍在时会再次让出）");
    }

    /** 是否为蛋糕物品（与 MaidCakeEdibleMixin 同一判定口径：注册名 minecraft:cake）
     *  实测四百四十八：misc.cakeEdible 关掉后本功能整体停用（与 mixin 同口径）。 */
    public static boolean isCake(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return false;
        }
        if (!isEnabled()) {
            return false;
        }
        net.minecraft.resources.ResourceLocation key =
                net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.m_41720_());
        return key != null && "minecraft:cake".equals(key.toString());
    }

    /** 吃完一整块蛋糕回血量（= 蛋糕营养 14，与 MaidCakeEdibleMixin 的 CAKE_FOOD
     *  同口径）——女仆不是 Player 没有 FoodData，喂蛋糕要走 m_5634_（heal）直接回血，
     *  否则右键喂食只加好感不回复生命（实测一百二十八反馈） */
    private static final float CAKE_HEAL = 14.0F;

    /**
     * 女仆吃完蛋糕（TLM 语义：onMaidEat 进食动画开始瞬间即生效——与 TLM 原版好感
     * apply 时机一致）。notify=true 时（玩家投喂）额外弹蓝色气泡 + 主人系统消息；
     * 女仆自吃静默，只有回血粒子 + FavorabilityManager.add 的心形粒子反馈。
     */
    public static void onCakeEaten(EntityMaid maid, boolean notify) {
        if (maid.m_9236_().m_5776_()) {
            return; // 仅服务端改数值/发消息
        }
        // 回血：像喂驯养动物一样按营养回复生命（m_5634_ = heal，满血自动无效）
        maid.m_5634_(CAKE_HEAL);
        maid.getFavorabilityManager().add(CAKE_FAVOR_POINTS); // 自带升阶/心形粒子/事件
        // 实测四百二十九：睡觉中不弹投喂气泡/消息（回血与好感照常结算）
        if (notify && !maid.m_5803_()) {
            showCakeMessage(maid);
        }
    }

    /** 蓝色系统消息（主人聊天框，格式：女仆名：......）+ 蓝色世界内气泡（只显示内容） */
    private static void showCakeMessage(EntityMaid maid) {
        String name = maid.m_5446_().getString();
        String text = "蛋糕真好吃，主人的心意我收到啦～";
        if (maid.m_269323_() instanceof ServerPlayer owner) {
            owner.m_213846_(Component.m_237113_("\u00a7d" + name + "\uff1a" + text));
        }
        maid.getChatBubbleManager().addChatBubble(
                TextChatBubbleData.type2(Component.m_237113_(text).m_130940_(ChatFormatting.LIGHT_PURPLE)));
        // v1.3.0 实测六百五十五【"投喂蛋糕没有语音"】：全模组只有两个语音入口，一个在
        // ChatBubbleLimitMixin（拦的是 addTextChatBubble），另一个在排班闲聊行为——而本方法
        // 刻意走**直发 addChatBubble**（为了保住蓝色、跳过 5 秒限频，见类注释），于是它
        // 从来就没进过语音链路。这里显式补一次 speak()：气泡的颜色与限频行为一个字不动，
        // 语音照走内置语音包（manifest 里 cake_eat 那条）。speak 内部自带开关/冷却，
        // 即使和别处的播报撞上也不会重复出声。
        com.maidsmart.voice.SystemTTSManager.speak(maid, text);
    }

    /** 玩家蛋糕右击自己的女仆：取消原交互（不开 GUI）→ 玩家蛋糕消失 → 女仆立刻吃。
     *  【R1102】别的事件监听已把本次交互取消时直接让路——否则会双喂（对方喂一份、
     *  我们再喂一份，玩家一次点击亏两块蛋糕）。 */
    @SubscribeEvent
    public void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.isCanceled()) {
            return; // 已被别的模组处理 → 让路
        }
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
        this.weCanceledThisEvent = true; // 告诉低优先级哨兵：这次取消是我们干的
        // 玩家手上蛋糕消失 + 玩家挥手动画
        stack.m_41774_(1);
        player.m_6674_(hand);
        // 女仆立刻触发吃掉事件：吃音效 + 蛋糕粒子 + 好感加成 + 蓝色消息
        maid.m_5496_(SoundEvents.f_11912_, 1.0f, 1.0f);
        maid.m_21060_(new ItemStack(Items.f_42502_), 8);
        onCakeEaten(maid, true);
    }

    /**
     * 【R1102·实测一千一百〇三】低优先级哨兵：所有监听跑完、原版 mobInteract 之前，
     * 看「野生女仆 + 我们的蛋糕」的交互是不是被【别的模组】取消并抢去喂了
     * （对方会把蛋糕塞进她手里并让她开吃）——是就当场自动关闭蛋糕功能。
     * 名单外的喂食型附属靠这一条兜底（第一个被抢的蛋糕收不回来，之后恢复正常）。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onInteractGuard(PlayerInteractEvent.EntityInteract event) {
        try {
            if (!event.isCanceled() || this.weCanceledThisEvent) {
                return;
            }
            if (!(event.getEntity() instanceof ServerPlayer)
                    || !(event.getTarget() instanceof EntityMaid maid)) {
                return;
            }
            if (maid.m_269323_() != null) {
                return; // 有主人的女仆：取消来自别的链路，不算驯服抢答
            }
            if (!isCake(event.getItemStack())) {
                return;
            }
            // 抢答的特征：她手里被塞了蛋糕 / 正在吃——保护类取消不会动她的手
            boolean fedCake = isCake(maid.m_21120_(InteractionHand.MAIN_HAND))
                    || isCake(maid.m_21120_(InteractionHand.OFF_HAND));
            if (!fedCake && !maid.m_6117_()) {
                return;
            }
            disableBecauseConflict("野生女仆 + 蛋糕的驯服交互被其他模组抢去喂掉（蛋糕已进她手/正在吃）");
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("MaidCakeEatHandler.onInteractGuard", ignored);
        } finally {
            this.weCanceledThisEvent = false;
        }
    }
}
