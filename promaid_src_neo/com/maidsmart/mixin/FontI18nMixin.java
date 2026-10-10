package com.maidsmart.mixin;

import com.maidsmart.i18n.PromaidI18n;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FormattedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Promaid 英文翻译（1.21.1）· 文本度量/换行出口兜底。
 *
 * <p>{@code Font.width(String)} 与 {@code Font.split(FormattedText,int)} 是测量与换行的
 * 必经之处（javap 实测）；在此替换可覆盖自绘换行路径，保证英文按英文宽度度量。
 */
@Mixin(Font.class)
public class FontI18nMixin {

    @ModifyVariable(method = "width(Ljava/lang/String;)I", at = @At("HEAD"), argsOnly = true)
    private String promaid$trWidthStr(String text) {
        return PromaidI18n.tr(text);
    }

    @ModifyVariable(method = "width(Lnet/minecraft/network/chat/FormattedText;)I", at = @At("HEAD"), argsOnly = true)
    private FormattedText promaid$trWidthFT(FormattedText text) {
        return PromaidI18n.tr(text);
    }

    @ModifyVariable(method = "split(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;",
            at = @At("HEAD"), argsOnly = true)
    private FormattedText promaid$trSplit(FormattedText text) {
        return PromaidI18n.tr(text);
    }
}
