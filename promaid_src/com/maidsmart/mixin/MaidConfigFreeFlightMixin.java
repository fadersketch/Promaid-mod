package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.config.MaidConfigContainerGui;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.flight.MaidFreeFlightFlags;
import com.maidsmart.flight.MaidFreeFlightNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 【仿创造飞行 · 女仆配置界面里的一行开关】——右键女仆 → 配置界面 → 「创造飞行：开/关」。
 *
 * <p>【为什么能这么做】TLM 的女仆配置界面是**硬编码**的（8 行 {@code MaidConfigButton}，没有注册表可插），
 * 但 {@code AbstractMaidContainerGui.initAdditionWidgets()} 是 protected 扩展点、按钮是公开控件——第三方
 * 可以**追加自己的一行**，不需要改 TLM 本体。
 * <p>【位置全交给 {@link com.maidsmart.client.MaidConfigRowLayout}】按**面板相对坐标**排在面板左侧外边距
 * 里竖着一列（曾按屏幕绝对坐标摆，窗口一窄就压住 TLM 自己开关、还和别的行重叠）——整列在面板之外，不遮
 * 任何 TLM 控件、彼此不重叠。详见那个类的注释。
 * <p>【为什么不用 TLM 的 {@code TaskData} 存这个开关】它的 {@code TaskDataRegister.writeSyncData} 会把编码
 * 结果强转 CompoundTag，一点开关就 ClassCastException 崩服——我们存 per-maid persistentData + 磁盘备份，
 * 客户端靠 S2C 缓存显示。
 * <p>【无 refmap 注意】{@code addRenderableWidget} 是继承自 {@code Screen} 的 protected 方法——无 refmap 时
 * {@code @Shadow} 定位不到（会崩），照记忆开关的做法改反射调用（Forge 用 SRG 名、Neo 用 Mojmap 名）。
 */
@Mixin(MaidConfigContainerGui.class)
public abstract class MaidConfigFreeFlightMixin {

    /** 我们这一行按钮（renderAddition 每帧同步文本用） */
    private Button promaid$freeFlightBtn = null;
    /** 防连点：600ms 内重复点击忽略（双击会把"关"变回"开"） */
    private static long PROMAID$LAST_CLICK = 0;

    @Inject(method = "initAdditionWidgets", at = @At("TAIL"))
    private void promaid$addFreeFlightToggle(CallbackInfo ci) {
        try {
            AbstractMaidContainerGui<?> gui = (AbstractMaidContainerGui<?>) (Object) this;
            EntityMaid maid = gui.getMaid();
            if (maid == null) {
                return;
            }
            Screen screen = (Screen) (Object) this;
            String uid = maid.m_20148_().toString();
            // 界面打开时先问一次真实状态（persistentData 只在服务端，客户端缓存可能是空的）
            MaidFreeFlightNetworking.CHANNEL.sendToServer(
                    new MaidFreeFlightNetworking.TogglePacket(uid, (byte) 0, false));
            Button btn = com.maidsmart.client.MaidConfigRowLayout.toggle(this, screen.f_96543_,
                    screen.f_96544_, 1, com.maidsmart.client.MaidConfigRowLayout.LABELS[1],
                    promaid$value(uid), () -> {
                        long now = System.currentTimeMillis();
                        if (now - PROMAID$LAST_CLICK < 600) {
                            return;
                        }
                        PROMAID$LAST_CLICK = now;
                        Boolean cur = MaidFreeFlightFlags.cachedClient(uid);
                        boolean next = !(cur != null ? cur : MaidFreeFlightFlags.globalOn());
                        MaidFreeFlightNetworking.CHANNEL.sendToServer(
                                new MaidFreeFlightNetworking.TogglePacket(uid, (byte) 1, next));
                    });
            this.promaid$freeFlightBtn = btn;
            promaid$addRenderable(screen, btn);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidConfigFreeFlightMixin", ignored);
        }
    }

    /** renderAddition 每帧同步文本：服务端 S2C 回来后按钮要立刻反映真实值 */
    @Inject(method = "renderAddition", at = @At("HEAD"))
    private void promaid$syncFreeFlightLabel(GuiGraphics graphics, int mouseX, int mouseY,
                                             float partialTick, CallbackInfo ci) {
        try {
            Button btn = this.promaid$freeFlightBtn;
            if (btn == null) {
                return;
            }
            EntityMaid maid = ((AbstractMaidContainerGui<?>) (Object) this).getMaid();
            if (maid == null) {
                return;
            }
            btn.m_93666_(com.maidsmart.client.MaidConfigRowLayout.text(
                    com.maidsmart.client.MaidConfigRowLayout.LABELS[1],
                    promaid$value(maid.m_20148_().toString())));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidConfigFreeFlightMixin", ignored);
        }
    }

    /** 按钮值文本：未设置 = 跟随全局（全局关就是关）；显式值优先。
     *  <p>【实测九百二十三 {@code @Unique}】同一个目标 {@link MaidConfigGoetyMixin} 也有同名私有助手——
     *  不标 {@code @Unique} 就按同名方法并入目标、第二条被 Mixin 判"重复覆写"整条丢弃（启动日志 Method overwrite conflict）。 */
    @Unique
    private static String promaid$value(String uid) {
        Boolean cur = MaidFreeFlightFlags.cachedClient(uid);
        if (cur == null) {
            return MaidFreeFlightFlags.globalOn() ? "\u00a7a\u5f00\u00a77(\u8ddf\u968f\u5168\u5c40)"
                                                  : "\u00a77\u5173\u00a78(\u8ddf\u968f\u5168\u5c40)";
        }
        return cur ? "\u00a7a\u5f00" : "\u00a77\u5173";
    }

    /**
     * 反射调用 Screen.addRenderableWidget（继承方法，无 refmap 时 @Shadow 定位不到）。
     *
     * 【1.20.1 用 SRG 字段名】本树手工编译、生产环境跑的是 SRG 类，所以查的是 {@code m_142416_}。
     * <p>【实测九百二十三】与值文本同因，标 {@code @Unique} 免得与 {@link MaidConfigGoetyMixin} 的同名助手撞名被丢。
     */
    @Unique
    private static void promaid$addRenderable(Screen screen,
                                              net.minecraft.client.gui.components.events.GuiEventListener widget) {
        try {
            java.lang.reflect.Method m = Screen.class.getDeclaredMethod("m_142416_",
                    net.minecraft.client.gui.components.events.GuiEventListener.class);
            m.setAccessible(true);
            m.invoke(screen, widget);
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("MaidConfigFreeFlightMixin", ignored);
        }
    }
}
