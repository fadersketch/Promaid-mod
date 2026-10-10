package com.maidsmart.mixin;

import com.maidsmart.i18n.PromaidI18n;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.List;

/**
 * Promaid 英文翻译（1.21.1）· 客户端文本出口。
 *
 * <p>同 Forge 版：本模组自制文本都是 literal，在**绘制出口**按玩家语言整串替换为英文。
 * 覆盖 1.21.1 {@code GuiGraphics} 的全部文本入口（drawString 两个重载组、drawCenteredString、
 * drawWordWrap、drawStringWithBackdrop，以及物品/组件 tooltip）。
 *
 * <p>为何不用简单的一条"String 入口"通吃：1.21.1 的
 * {@code drawString(Font,String,...)} 返回宽度值并**同时绘制**，而换行在
 * {@code Font.split} 里——所以必须在 Component/String 层就替换，宽度才按英文度量。
 */
@Mixin(GuiGraphics.class)
public class GuiGraphicsI18nMixin {

    @ModifyVariable(method = "drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)I",
            at = @At("HEAD"), argsOnly = true)
    private String promaid$trDrawStr1(String text) {
        return PromaidI18n.tr(text);
    }

    @ModifyVariable(method = "drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)I",
            at = @At("HEAD"), argsOnly = true)
    private String promaid$trDrawStr2(String text) {
        return PromaidI18n.tr(text);
    }

    @ModifyVariable(method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)I",
            at = @At("HEAD"), argsOnly = true)
    private Component promaid$trDrawCmp1(Component text) {
        return PromaidI18n.tr(text);
    }

    @ModifyVariable(method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I",
            at = @At("HEAD"), argsOnly = true)
    private Component promaid$trDrawCmp2(Component text) {
        return PromaidI18n.tr(text);
    }

    @ModifyVariable(method = "drawCenteredString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V",
            at = @At("HEAD"), argsOnly = true)
    private String promaid$trCenterStr(String text) {
        return PromaidI18n.tr(text);
    }

    @ModifyVariable(method = "drawCenteredString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V",
            at = @At("HEAD"), argsOnly = true)
    private Component promaid$trCenterCmp(Component text) {
        return PromaidI18n.tr(text);
    }

    @ModifyVariable(method = "drawWordWrap(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/FormattedText;IIII)V",
            at = @At("HEAD"), argsOnly = true)
    private FormattedText promaid$trWordWrap(FormattedText text) {
        return PromaidI18n.tr(text);
    }

    @ModifyVariable(method = "drawStringWithBackdrop(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)I",
            at = @At("HEAD"), argsOnly = true)
    private Component promaid$trBackdrop(Component text) {
        return PromaidI18n.tr(text);
    }

    @ModifyVariable(method = "renderComponentTooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;II)V",
            at = @At("HEAD"), argsOnly = true)
    private List<Component> promaid$trTooltipList(List<Component> lines) {
        if (lines == null || lines.isEmpty() || PromaidI18n.isChinese()) {
            return lines;
        }
        boolean any = false;
        List<Component> out = new ArrayList<>(lines.size());
        for (Component c : lines) {
            Component t = PromaidI18n.tr(c);
            if (t != c) {
                any = true;
            }
            out.add(t);
        }
        return any ? out : lines;
    }
}
