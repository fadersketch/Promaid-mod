package com.maidsmart.mixin;

import com.maidsmart.i18n.PromaidI18n;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FormattedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Promaid 英文翻译（1.20.1）· 文本度量/换行出口兜底。
 *
 * <p>{@code Font.m_92895_(String)}（宽度）与 {@code m_92923_(FormattedText,int)}（换行切分）
 * 是所有文本测量的必经之处。在这里替换可覆盖"自绘换行"路径（如手册正文逐字符折行），
 * 保证英文文本按英文宽度度量、换行位置正确。
 *
 * <p>三个方法名均经 {@code javap} 对 1.20.1-srg 实测确认存在。
 */
@Mixin(Font.class)
public class FontI18nMixin {

    @ModifyVariable(method = "m_92895_(Ljava/lang/String;)I", at = @At("HEAD"), argsOnly = true)
    private String promaid$trWidthStr(String text) {
        return PromaidI18n.tr(text);
    }

    @ModifyVariable(method = "m_92852_(Lnet/minecraft/network/chat/FormattedText;)I", at = @At("HEAD"), argsOnly = true)
    private FormattedText promaid$trWidthFT(FormattedText text) {
        return PromaidI18n.tr(text);
    }

    @ModifyVariable(method = "m_92923_(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;",
            at = @At("HEAD"), argsOnly = true)
    private FormattedText promaid$trSplit(FormattedText text) {
        return PromaidI18n.tr(text);
    }
}
