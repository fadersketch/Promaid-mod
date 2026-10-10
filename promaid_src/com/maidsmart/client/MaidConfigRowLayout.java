package com.maidsmart.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * 女仆配置界面（TLM {@code MaidConfigContainerGui}）里我们注入的开关行该摆哪儿、长什么样。
 *
 * <p>【实测八百零五 根因】
 *
 * **屏幕绝对坐标** {@code (screenWidth - 174, 写死的 y)} 摆放，两个维度都错：
 * <ul>
 *   <li><b>横向</b>：面板是**居中**的（{@code leftPos = (screenW - 256) / 2}），
 *       而 {@code screenW - 174} 只跟屏幕宽走、跟面板无关。窗口一窄，
 *       {@code screenW - 174} 就落进面板内部，压住 TLM 自己那 8 行开关。</li>
 *   <li><b>纵向</b>：y 写死 8 / 26 / 36，而按钮高 13 ⇒ 创造飞行 26~39 与飞行聚晶 36~49
 *       **互相重叠 3px**。</li>
 * </ul>
 *
 * <p>【为什么是「面板左侧外边距」这一列】TLM 面板 256×256；面板内部几乎全被占满
 * （{@code topPos+9} 翻页按钮、{@code topPos+14} 皮肤/资料/声音图标、
 * {@code topPos+28} 装饰货架、{@code topPos+52~155} 8 行真开关、{@code topPos+113} 状态图标、
 * {@code topPos+149} 女仆模型、{@code topPos+159/187/206/230} 任务/排班/守家/下载按钮、
 * {@code topPos+174} 起玩家背包——以上均按字节码核对），塞不下一行。
 * 所以改放到**面板之外**：TLM 那个 164 宽的 {@code MaidConfigButton} 在窄边距里放不下
 * （这正是老实现改用「屏幕绝对坐标」的原因，也正是它翻车的根源），
 * 于是这里用**窄按钮**（80×13，与原生开关同高），在面板左侧外边距竖排一列——
 * 整列在面板之外 ⇒ 一个 TLM 控件都不遮；行距 15 ⇒ 三行彼此也不重叠。
 *
 * <p>【无 refmap】{@code leftPos}/{@code topPos} 是 {@code AbstractContainerScreen} 的
 * **继承 protected 字段**，无 refmap 时 {@code @Shadow} 定位不到（本仓库混入的老教训），
 * 照 {@code MaidConfigMemoryMixin} 的做法用反射沿继承链找字段：dev / 1.21.1 是
 * {@code leftPos/topPos}，生产环境 1.20.1 是 SRG {@code f_97735_/f_97736_}，两种名字都试。
 */
public final class MaidConfigRowLayout {

    /** 窄按钮宽度：要能塞进面板左侧边距（缩放 6 时约 85px）。 */
    public static final int BUTTON_W = 80;
    /** 高与 TLM 原生开关行一致（13）。 */
    public static final int BUTTON_H = 13;
    /** 行距：13 高 + 2 间隙 ⇒ 任意两行都不重叠。 */
    public static final int STEP = 15;
    /** 第一行相对面板顶缘的 y 偏移（避开面板内 {@code topPos+9..22} 的翻页按钮）。 */
    public static final int FIRST_Y = 34;
    /** 与面板左缘留的空隙。 */
    public static final int GAP = 4;
    /** 面板贴图尺寸（{@code AbstractMaidContainerGui} 构造器里写死 256×256）。 */
    public static final int IMAGE_W = 256;
    public static final int IMAGE_H = 256;

    /** 三个开关的显示名（中文原文，由 i18n 显示层整串翻译）。 */
    public static final String[] LABELS = {
            "AI \u8bb0\u5fc6", "\u521b\u9020\u98de\u884c", "\u98de\u884c\u805a\u6676"};

    private MaidConfigRowLayout() {
    }

    /**
     * 给第 {@code slot} 个开关（0=AI 记忆 / 1=创造飞行 / 2=飞行聚晶）建一个窄按钮。
     *
     * @param gui     女仆配置界面（{@code this}）
     * @param screenW 屏幕逻辑宽
     * @param screenH 屏幕逻辑高
     * @param slot    第几个
     * @param label   显示名
     * @param value   当前值文本（{@code "开"} / {@code "关"} / {@code "开(跟随全局)"} …）
     * @param onPress 点击回调
     */
    public static Button toggle(Object gui, int screenW, int screenH, int slot, String label,
                                String value, Runnable onPress) {
        int[] xy = place(gui, screenW, screenH, slot);
        return Button.m_253074_(text(label, value), b -> onPress.run())
                .m_252987_(xy[0], xy[1], BUTTON_W, BUTTON_H)
                .m_253136_();
    }

    /**
     * 第 {@code slot} 个按钮的屏幕坐标 {@code {x, y}}：面板左侧外边距竖排一列。
     */
    public static int[] place(Object gui, int screenW, int screenH, int slot) {
        int left = intField(gui, "f_97735_", "leftPos", (screenW - IMAGE_W) / 2);
        int top = intField(gui, "f_97736_", "topPos", (screenH - IMAGE_H) / 2);
        int x = left - BUTTON_W - GAP;
        int y = top + FIRST_Y + slot * STEP;
        // 夹进屏幕：缩放拉满时 leftPos/topPos 可能为负，边距也可能不足
        if (x < 2) {
            x = 2;
        }
        if (x + BUTTON_W > screenW - 2) {
            x = Math.max(2, screenW - BUTTON_W - 2);
        }
        if (y + BUTTON_H > screenH - 2) {
            y = screenH - BUTTON_H - 2;
        }
        if (y < 2) {
            y = 2;
        }
        return new int[]{x, y};
    }

    /**
     * 按钮文本（单一 literal，整串可被 i18n 显示层精确命中翻译）。
     *
     * <p>刻意合成**一个**字符串而不是两个 Component：原版按钮只有一个 message，
     * 而 i18n 显示层只做整串精确匹配（见 {@code PromaidI18n}），拆成两段反而谁都命中不了。
     */
    public static Component text(String label, String value) {
        return Component.m_237113_(label + " " + value);
    }

    /** 反射读继承字段：先 SRG 名、再 Mojmap 名；都没有就用 fallback。 */
    private static int intField(Object o, String srg, String mojmap, int fallback) {
        Integer v = read(o, srg);
        if (v == null) {
            v = read(o, mojmap);
        }
        return v == null || v == 0 ? fallback : v;
    }

    private static Integer read(Object o, String name) {
        try {
            for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.getInt(o);
                } catch (NoSuchFieldException ignored) {
                    // 沿继承链继续找
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
