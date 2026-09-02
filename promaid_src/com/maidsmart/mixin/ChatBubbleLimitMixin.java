package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.ChatBubbleManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v1.5.67：气泡限频与优先级——每只女仆 5 秒内最多 1 个对话气泡：
 * - 不再"一直弹个不停"（思念气泡/状态播报/缺料提示全部受此约束，偶尔弹）
 * - 已有气泡时新气泡被压下去（5 秒窗口内丢弃 = 最低优先级效果）
 * v1.5.68：通过限频的气泡内容同步显示到主人聊天框（可细看）；
 * 仅显示，不记录进女仆记忆系统。
 */
@Mixin(ChatBubbleManager.class)
public abstract class ChatBubbleLimitMixin {
    /** v1.5.88：读配置面板（misc.bubbleLimitMs）
     *  v1.5.98：去掉误用的 @Shadow（私有静态方法不需要 shadow，且无 refmap 时
     *  shadow 声明若目标类无对应成员会定位失败） */
    private static long bubbleLimitMs() {
        return com.maidsmart.config.MaidSmartConfig.MISC_BUBBLE_LIMIT_MS.get();
    }


    /** v1.5.103：@Shadow 取回目标类 ChatBubbleManager 的 `maid` 字段——旧版声明了
     *  同名实例字段但从未赋值（恒 null）→ "非工头建造女仆气泡静默"与"气泡同步到
     *  主人聊天框"两个功能一直是死代码。目标类字段存在（dev 名 `maid`），直接 shadow。 */
    @Shadow
    private EntityMaid maid;

    /** 每只女仆（ChatBubbleManager 实例）上次气泡时间 */
    private static final java.util.Map<Object, Long> LAST_BUBBLE = new java.util.WeakHashMap<>();

    @Inject(method = "addTextChatBubble", at = @At("HEAD"), cancellable = true)
    private void maidSmartBubbleLimit(String text, CallbackInfoReturnable<Long> cir) {
        // v1.1.0 实测二百七十四（用户："建造模式屏蔽除了建造以外的其他所有系统信息
        // 系统消息及气泡"）：建造女仆的非建造来源气泡全部静默——调用栈判定来源
        // （com.maidsmart.build 包 = 建造系统）；"建好啦"完成汇报在建造包内，放行。
        if (this.maid != null && com.maidsmart.combat.BuildShieldGuard.shouldMute(this.maid)) {
            cir.setReturnValue(-1L);
            return;
        }
        // v1.5.84：完成汇报（"建好啦"）——放行静默与限频（多女仆时第一个完成的女仆
        // 可能是非工头；且完成瞬间工头可能刚发过其他气泡），保证玩家一定收到汇报
        boolean completeReport = text != null && text.startsWith("建好啦");
        if (!completeReport
                && this.maid != null
                && com.maidsmart.build.BlueprintBuildExecutor.isBuildingTask(this.maid)
                && !com.maidsmart.build.BuildPlan.isForeman(this.maid)) {
            cir.setReturnValue(-1L);
            return;
        }
        if (!completeReport) {
            long now = System.currentTimeMillis();
            Long last = LAST_BUBBLE.get(this);
            if (last != null && now - last < bubbleLimitMs()) {
                cir.setReturnValue(-1L); // 5 秒内不重复弹（偶尔弹，不吵闹）
                return;
            }
            LAST_BUBBLE.put(this, now);
        }
        // v1.5.198：系统消息朗读——通过限频的真实气泡汇入 TTS
        //（①系统语音包命中 ②语音缓存命中 ③TLM TTS 合成；详细门禁见 SystemTTSManager）
        if (this.maid != null) {
            com.maidsmart.voice.SystemTTSManager.speak(this.maid, text);
        }
        // v1.5.68：气泡内容同步到主人聊天框（可细看；不进女仆记忆）
        // v1.5.193：敌袭状态特殊颜色——周围 12 格有存活敌对生物时，气泡以
        // 红色 §c + [警示] 前缀同步（"危险提示"该醒目）
        // v1.5.194：所有女仆【系统对话】（addTextChatBubble = 感知/工作/自保/投喂/
        // 建好啦等全部规则气泡 + TLM 内建气泡）统一青色 §b——区别于 LLM 主动对话
        // 的 TLM 原生灰色 `<名字> 文本`（addLLMChatText 独立方法，mixin 不拦截）
        if (this.maid != null
                && this.maid.m_269323_() instanceof net.minecraft.server.level.ServerPlayer owner) {
            boolean danger = com.maidsmart.dialogue.PerceptionManager.dangerActive(this.maid);
            owner.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                    danger
                            ? "\u00a7c[\u8b66\u793a]\u00a7c[" + this.maid.m_5446_().getString() + "] \u00a7c" + text
                            : "\u00a7b[" + this.maid.m_5446_().getString() + "] \u00a7b" + text));
        }
    }

    /**
     * v1.5.194：世界内气泡也变色——所有系统对话气泡统一青色（敌袭红色）。
     * addTextChatBubble 内部先 addChatBubble 生成 TextChatBubbleData，这里在
     * RETURN 后取回刚加的气泡，改写文本颜色并 forceUpdateChatBubble 同步客户端。
     * LLM 主动对话（addLLMChatText）走独立方法，不受影响（世界内白/聊天框灰）。
     */
    @Inject(method = "addTextChatBubble", at = @At("RETURN"))
    private void maidSmartBubbleColor(String text, CallbackInfoReturnable<Long> cir) {
        try {
            if (this.maid == null) {
                return;
            }
            long key = cir.getReturnValue();
            if (key < 0) {
                return;
            }
            com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.ChatBubbleManager mgr =
                    (com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.ChatBubbleManager) (Object) this;
            com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.IChatBubbleData data = mgr.getChatBubble(key);
            if (data instanceof com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.TextChatBubbleData t) {
                boolean danger = com.maidsmart.dialogue.PerceptionManager.dangerActive(this.maid);
                t.setText(net.minecraft.network.chat.Component.m_237115_(text)
                        .m_130940_(danger
                                ? net.minecraft.ChatFormatting.RED
                                : net.minecraft.ChatFormatting.AQUA));
                mgr.forceUpdateChatBubble();
            }
        } catch (Exception ignored) {
        }
    }
}
