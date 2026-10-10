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
 * Promaid 英文翻译（1.20.1）· 客户端文本出口。
 *
 * <p>本模组全部自制 UI 文本都以 {@code Component.m_237113_("中文…")}（literal）构造，
 * 原版语言表对 literal 不生效——所以在这里按**玩家自己的语言**把待绘制的中文换成英文：
 * 玩家语言不是中文时才替换；整串命中词条才替换（见 {@link PromaidI18n}），
 * 因此玩家名、物品名、坐标、数字等动态内容一字不动。
 *
 * <p>覆盖 GuiGraphics 上所有"从 Component/String 起绘"的入口。字节码实证：1.20.1 的
 * {@code m_280614_/m_280653_/m_280430_/m_280554_} 最终都汇聚到 {@code m_280649_}，
 * 而**换行**发生在更早的 {@code Font.m_92923_(FormattedText,int)}——所以必须在
 * "还在 Component" 的这一层替换，否则宽度按中文量好了再换英文会串行。
 */
@Mixin(GuiGraphics.class)
public class GuiGraphicsI18nMixin {

    @ModifyVariable(method = "m_280614_(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I",
            at = @At("HEAD"), argsOnly = true)
    private Component promaid$trDrawStringComponent(Component text) {
        return PromaidI18n.tr(text);
    }

    @ModifyVariable(method = "m_280653_(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V",
            at = @At("HEAD"), argsOnly = true)
    private Component promaid$trDrawCenteredComponent(Component text) {
        return PromaidI18n.tr(text);
    }

    @ModifyVariable(method = "m_280430_(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)I",
            at = @At("HEAD"), argsOnly = true)
    private Component promaid$trDrawComponent(Component text) {
        return PromaidI18n.tr(text);
    }

    @ModifyVariable(method = "m_280056_(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)I",
            at = @At("HEAD"), argsOnly = true)
    private String promaid$trDrawStringRaw(String text) {
        return PromaidI18n.tr(text);
    }

    @ModifyVariable(method = "m_280554_(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/FormattedText;IIII)V",
            at = @At("HEAD"), argsOnly = true)
    private FormattedText promaid$trDrawWordWrap(FormattedText text) {
        return PromaidI18n.tr(text);
    }

    @ModifyVariable(method = "m_280557_(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;II)V",
            at = @At("HEAD"), argsOnly = true)
    private Component promaid$trTooltip(Component text) {
        return PromaidI18n.tr(text);
    }

    @ModifyVariable(method = "m_280666_(Lnet/minecraft/client/gui/Font;Ljava/util/List;II)V",
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
