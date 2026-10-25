package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.TextChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.ModifyDefaultComponentsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;

/**
 * 蛋糕投喂与好感加成（实测一百二十五）：
 * - 女仆吃完蛋糕好感 +10——TLM 原版工作餐/家餐只按营养随机给 0~1 点，蛋糕没有额外
 *   价值；这里统一为"每次吃完一整块蛋糕 = +10 好感"（与 MaidCakeFavorMixin 同口径，
 *   玩家投喂和女仆自吃都算）。
 * - 玩家用蛋糕右击自己的女仆 = 女仆立刻触发吃掉事件：玩家手上蛋糕消失（消耗 1 个）、
 *   女仆播放吃音效+蛋糕粒子，并触发蓝色系统消息（主人聊天框）与蓝色世界内气泡，
 *   格式：女仆名：......
 * 注意：气泡走 addChatBubble 直发而非 addTextChatBubble——后者会被
 * ChatBubbleLimitMixin 统一染成青色（§b）且受 5 秒限频；投喂是玩家主动操作，
 * 需要蓝色（§9）即时反馈，故绕过。
 * ── v1.2.4 实测六百四十四：1.21.1 的「蛋糕可食用」挂法（崩服修复）──
 * 1.20.1 那条 MaidCakeEdibleMixin 注入的是 Item.m_41472_ / m_41473_
 *（isEdible / getFoodProperties，javap 实证返回值就是 boolean / FoodProperties）。 1.21.1 的食物**已经全面数据组件化**：Item 上这两个方法都不存在了，一切改读 {@code DataComponents.FOOD}——javap 实证 Item.use 读 FOOD（有就 startUsingItem + consume）、Item.getUseAnimation = has(FOOD) ? EAT : NONE、Item.getUseDuration = food.eatDurationTicks()，物品提示也读它。 移植时那条 mixin 却被写成注入 {@code Item.use} 与 {@code Item.finishUsingItem}， handler 却是 CallbackInfoReturnable<Boolean> / <FoodProperties>—— 右键蛋糕（手上那块；或对着地上已放好的蛋糕、而当时又吃不下的那一路）会走到 Item.use，它的真实返回值是 InteractionResultHolder，于是 Boolean 强转 InteractionResultHolder 当场崩服：粉丝回报的「右键蛋糕直接被踢出世界」 （1.2.0~1.2.3 都一样）。 【现在】改走 NeoForge 的 ModifyDefaultComponentsEvent：模组加载时给 {@code minecraft:cake} 补一个 FOOD 组件——与任何原版食物同一挂法，上面那串 派生行为全部自动成立，一条 mixin 都不需要。
 */
public class MaidCakeEatHandler {
    /** 每次吃完一块蛋糕的好感加成 */
    public static final int CAKE_FAVOR_POINTS = 10;

    /** 启动探测发现其他模组已经改了蛋糕 → 自动停用（配置开关同步置 false）。 */
    private static volatile boolean AUTO_DISABLED = false;

    /**
     * 【R1102·实测一千一百〇三】会把「可食用物品」直接喂给女仆并取消原交互的附属（反编译实证）：
     * solmaid（生活调味料：酒狐萝贝）的 MaidFeeding 对**任意**女仆——包括野生未驯服的——
     * 只要主手物品有食物属性就塞给她吃掉并 setCanceled：TLM 的驯服交互
     * （EntityMaid.mobInteract → tameMaid，蛋糕正是驯服物）根本走不到。我们给蛋糕挂上
     * FOOD 组件之后正好武装了它——这类附属在场时蛋糕功能必须让出（与"别的模组改过蛋糕"
     * 同一条自动关闭口径，只是探测信号不同：那边看 FOOD 组件，这边看附属名单 + 运行时抢答）。
     */
    private static final java.util.Set<String> FEED_HIJACKER_MODS = java.util.Set.of("solmaid");

    /** 本次交互是不是【我们自己】取消的（低优先级哨兵用它排除自己的取消，防误报）。 */
    private boolean weCanceledThisEvent = false;

    /** 装了哪个「喂食抢答型」附属；没装返回 null。 */
    private static String loadedFeedHijacker() {
        try {
            for (String id : FEED_HIJACKER_MODS) {
                if (net.neoforged.fml.ModList.get().isLoaded(id)) {
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

    /** 蛋糕食物属性（营养 14 = 7 切片 × 2、饱和度 0.6——整块蛋糕一次吃完的量）；
     *  eatSeconds 留 1.21.1 的默认 1.6 秒（= 32 tick，与原版食物同量级）。
     *  与 1.20.1 那条 mixin 的 CAKE_FOOD 同口径——两树的蛋糕营养/回复量必须一致。 */
    public static final FoodProperties CAKE_FOOD = new FoodProperties.Builder()
            .nutrition(14).saturationModifier(0.6f).build();

    /**
     *（NeoForge 的「改默认数据组件」事件——1.21.1 里食物就是这么定义的了）。挂上之后：
     * 工作餐/家餐判定、进食动画与时长、物品提示全部自动成立。一次性：只在加载期跑，
     * 改开关要**重启游戏**才完全生效；关掉 = 组件不挂，蛋糕恢复原版不可食。
     * 【冲突探测】蛋糕已有 FOOD = 别的模组改过 → 让出；【R1102】「喂食抢答型」附属
     * （{@link #FEED_HIJACKER_MODS}）不改蛋糕、却会把可食用物品直接喂给任意女仆并
     * 取消交互——在场时同样让出（组件根本不挂）。
     */
    public static void onModifyDefaultComponents(ModifyDefaultComponentsEvent event) {
        boolean enabled;
        try {
            enabled = com.maidsmart.config.MaidSmartConfig.MISC_CAKE_EDIBLE.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidCakeEatHandler", ignored);
            enabled = true; // 配置尚未挂上 → 按默认（开）走
        }
        if (!enabled || AUTO_DISABLED) {
            return;
        }
        // 【R1102】先让出「喂食抢答型」附属：挂了 FOOD 只会武装它们抢走蛋糕驯服
        String hijacker = loadedFeedHijacker();
        if (hijacker != null) {
            disableBecauseConflict("喂食型附属 " + hijacker
                    + "（生活调味料：酒狐萝贝）会把可食用物品直接喂给女仆并抢走交互，"
                    + "蛋糕驯服会被截走");
            return;
        }
        try {
            FoodProperties existing = Items.CAKE.components().get(DataComponents.FOOD);
            if (existing != null) {
                if (!CAKE_FOOD.equals(existing)) {
                    disableBecauseConflict("已有 FOOD 组件：营养 " + existing.nutrition()
                            + " / 饱和 " + existing.saturation());
                }
                return;
            }
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("MaidCakeEatHandler.checkExistingFood", ignored);
        }
        event.modify(Items.CAKE, builder -> builder.set(DataComponents.FOOD, CAKE_FOOD));
        com.maidsmart.tool.PromaidLog.log("蛋糕可食用",
                "已给 minecraft:cake 挂上 FOOD 组件（营养 14 / 饱和 0.6 / 1.6 秒）"
                        + "——女仆把它当食物，玩家右键也能吃");
    }

    /** 服务端启动复核（加载全部结束）：若 FOOD 被别的模组后写/覆盖，自动关闭蛋糕功能。
     *  【R1102】也兜底复核「喂食抢答型」附属（组件事件早于一切时 ModList 万一没就绪）。 */
    public static void verifyAfterLoad() {
        if (!isEnabled()) {
            return;
        }
        try {
            String hijacker = loadedFeedHijacker();
            if (hijacker != null) {
                disableBecauseConflict("喂食型附属 " + hijacker + " 在场（组件事件那次没拦到）");
                return;
            }
            FoodProperties food = Items.CAKE.components().get(DataComponents.FOOD);
            if (food != null && !CAKE_FOOD.equals(food)) {
                disableBecauseConflict("FOOD 已被其他模组覆盖：营养 " + food.nutrition()
                        + " / 饱和 " + food.saturation());
            }
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("MaidCakeEatHandler.verifyAfterLoad", ignored);
        }
    }

    /** 自动关闭（带现场细节）：内存里把开关置 false，并留一行明确的日志。 */
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

    /** 是否为蛋糕物品（判定口径：注册名 minecraft:cake——与上方
     *  onModifyDefaultComponents 挂 FOOD 组件同一口径）
     *  实测四百四十八：misc.cakeEdible 关掉后本功能整体停用（组件也不再挂）。 */
    public static boolean isCake(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (!isEnabled()) {
            return false;
        }
        net.minecraft.resources.ResourceLocation key =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key != null && "minecraft:cake".equals(key.toString());
    }

    /** 吃完一整块蛋糕回血量（= 蛋糕营养 14，与上方 CAKE_FOOD 同口径）——女仆不是
     *  Player 没有 FoodData，喂蛋糕要直接 heal 回血，
     *  否则右键喂食只加好感不回复生命（实测一百二十八反馈） */
    private static final float CAKE_HEAL = 14.0F;

    /**
     * 女仆吃完蛋糕（TLM 语义：onMaidEat 进食动画开始瞬间即生效——与 TLM 原版好感
     * apply 时机一致）。notify=true 时（玩家投喂）额外弹蓝色气泡 + 主人系统消息；
     * 女仆自吃静默，只有回血粒子 + FavorabilityManager.add 的心形粒子反馈。
     */
    public static void onCakeEaten(EntityMaid maid, boolean notify) {
        if (maid.level().isClientSide()) {
            return; // 仅服务端改数值/发消息
        }
        // 回血：像喂驯养动物一样按营养回复生命（heal = heal，满血自动无效）
        maid.heal(CAKE_HEAL);
        maid.getFavorabilityManager().add(CAKE_FAVOR_POINTS); // 自带升阶/心形粒子/事件
        // 实测四百二十九：睡觉中不弹投喂气泡/消息（回血与好感照常结算）
        if (notify && !maid.isSleeping()) {
            showCakeMessage(maid);
        }
    }

    /** 蓝色系统消息（主人聊天框，格式：女仆名：......）+ 蓝色世界内气泡（只显示内容） */
    private static void showCakeMessage(EntityMaid maid) {
        String name = maid.getDisplayName().getString();
        String text = "蛋糕真好吃，主人的心意我收到啦～";
        if (maid.getOwner() instanceof ServerPlayer owner) {
            owner.sendSystemMessage(Component.literal("\u00a7d" + name + "\uff1a" + text));
        }
        maid.getChatBubbleManager().addChatBubble(
                TextChatBubbleData.type2(Component.literal(text).withStyle(ChatFormatting.LIGHT_PURPLE)));
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
                || !maid.isOwnedBy(player)) {
            return;
        }
        InteractionHand hand = event.getHand();
        ItemStack stack = player.getItemInHand(hand);
        if (!isCake(stack)) {
            return;
        }
        event.setCanceled(true);
        this.weCanceledThisEvent = true; // 告诉低优先级哨兵：这次取消是我们干的
        // 玩家手上蛋糕消失 + 玩家挥手动画
        stack.shrink(1);
        player.swing(hand);
        // 女仆立刻触发吃掉事件：吃音效 + 蛋糕粒子 + 好感加成 + 蓝色消息
        maid.playSound(SoundEvents.GENERIC_EAT, 1.0f, 1.0f);
        maid.spawnItemParticles(new ItemStack(Items.CAKE), 8);
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
            if (maid.getOwner() != null) {
                return; // 有主人的女仆：取消来自别的链路，不算驯服抢答
            }
            if (!isCake(event.getItemStack())) {
                return;
            }
            // 抢答的特征：她手里被塞了蛋糕 / 正在吃——保护类取消不会动她的手
            boolean fedCake = isCake(maid.getItemInHand(InteractionHand.MAIN_HAND))
                    || isCake(maid.getItemInHand(InteractionHand.OFF_HAND));
            if (!fedCake && !maid.isUsingItem()) {
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
