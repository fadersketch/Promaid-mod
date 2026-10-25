package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatData;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.util.CappedQueue;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 内部触发（主动对话/自主决策）不进 TLM 历史。
 *
 * <p>注入父类 {@code addUserHistory} 的 HEAD，而不是重定向 {@code normalChat} 里的调用点——
 * callresponse 也 @Redirect 了同一个调用点，两个 Redirect 互斥会让后者注入失败并直接崩游戏。
 * 注意：这个混入必须是顶层类并在 {@code mixins.promaid.json} 里登记；Mixin 不会把外层混入的
 * 嵌套 {@code @Mixin} 类当作独立混入处理。
 * <p>v1.3.3（R1087）：旧版本把内部触发当主人消息写进了历史，存档里留下
 * 「主人刚才问了你一个问题…」这类提示词记录。读档时按已知前缀清掉一次——只删我们自己
 * 造出来的提示词，玩家真说过的话（不看前缀）一律不动。
 */
@Mixin(MaidAIChatData.class)
public abstract class MaidInternalHistoryMixin {

    /** 历次内部触发的开头；旧版没有标记前缀，只能按原句认 */
    private static final String[] LEGACY_PROMPTS = {
            "\u3010\u7cfb\u7edf\u00b7\u4e3b\u52a8\u8865\u5145\uff0c\u4e0d\u662f\u4e3b\u4eba\u8bf4\u7684\u8bdd\u3011",
            "\u3010\u7cfb\u7edf\u00b7\u4e8b\u4ef6\uff0c\u4e0d\u662f\u4e3b\u4eba\u8bf4\u7684\u8bdd\u3011",
            "\uff08\u4f60\u72ec\u81ea\u5f85\u4e86\u4e00\u4f1a\u513f\uff0c\u4e3b\u4eba\u6682\u65f6\u6ca1\u6709\u5429\u5490\u3002",
            "\u4e3b\u4eba\u521a\u624d\u95ee\u4e86\u4f60\u4e00\u4e2a\u95ee\u9898"
    };

    @Shadow
    @Final
    protected CappedQueue<LLMMessage> history;

    @Inject(method = "addUserHistory", at = @At("HEAD"), cancellable = true)
    private void maidsmart$skipInternalHistory(String prompt, CallbackInfo ci) {
        if (com.maidsmart.dialogue.ChatInfoUtil.isInternal(prompt)) {
            ci.cancel();
        }
    }

    /** 读档后清掉旧版写进历史里的内部提示词（只按前缀认，玩家消息不受影响） */
    @Inject(method = "readFromTag", at = @At("RETURN"))
    private void maidsmart$scrubLegacyInternalHistory(CompoundTag tag,
                                                     CallbackInfoReturnable<CompoundTag> cir) {
        try {
            if (this.history == null) {
                return;
            }
            this.history.getDeque().removeIf(m -> m != null && maidsmart$isLegacyPrompt(m.message()));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidInternalHistoryMixin", ignored);
        }
    }

    private static boolean maidsmart$isLegacyPrompt(String msg) {
        if (msg == null || msg.isEmpty()) {
            return false;
        }
        for (String p : LEGACY_PROMPTS) {
            if (msg.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
