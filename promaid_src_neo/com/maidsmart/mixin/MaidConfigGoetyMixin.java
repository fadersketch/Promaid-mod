package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.config.MaidConfigContainerGui;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.goety.MaidGoetyAuto;
import com.maidsmart.goety.MaidGoetyNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 实测七百四十五·点1【飞行聚晶 · 女仆配置界面里的一行开关】——右键女仆 → 配置界面 →
 * 「飞行聚晶：开/关」。
 *
 * <p>——
 * 七百一十八 那一版只有 OP 命令，普通玩家开不了。这一行就是"主人自己就能开"的入口。
 *
 * <p>【实测八百零五：位置全交给 {@link com.maidsmart.client.MaidConfigRowLayout}】原实现把按钮
 * 按屏幕绝对坐标 {@code (screenWidth-174, 36)} 摆，窗口一窄就压住 TLM 自己的开关
 * （）。现在与「AI 记忆」「创造飞行」一起，
 * 由 {@code MaidConfigRowLayout} 按**面板相对坐标**统一排在面板左侧外边距里——
 * 整列在面板之外，不遮任何 TLM 控件，彼此也不重叠。详见那个类的注释。
 */
@Mixin(MaidConfigContainerGui.class)
public abstract class MaidConfigGoetyMixin {

    /** 我们这一行按钮（renderAddition 每帧同步文本用） */
    private Button promaid$goetyBtn = null;
    /** 防连点：600ms 内重复点击忽略（双击会把"关"变回"开"） */
    private static long PROMAID$GOETY_LAST_CLICK = 0;

    @Inject(method = "initAdditionWidgets", at = @At("TAIL"))
    private void promaid$addGoetyToggle(CallbackInfo ci) {
        try {
            AbstractMaidContainerGui<?> gui = (AbstractMaidContainerGui<?>) (Object) this;
            EntityMaid maid = gui.getMaid();
            if (maid == null) {
                return;
            }
            Screen screen = (Screen) (Object) this;
            String uid = maid.getUUID().toString();
            // 界面打开时先问一次真实状态（persistentData 只在服务端，客户端缓存可能是空的）
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new MaidGoetyNetworking.TogglePacket(uid, (byte) 0, false));
            Button btn = com.maidsmart.client.MaidConfigRowLayout.toggle(this, screen.width,
                    screen.height, 2, com.maidsmart.client.MaidConfigRowLayout.LABELS[2],
                    promaid$value(uid), () -> {
                        long now = System.currentTimeMillis();
                        if (now - PROMAID$GOETY_LAST_CLICK < 600) {
                            return;
                        }
                        PROMAID$GOETY_LAST_CLICK = now;
                        Boolean cur = MaidGoetyAuto.cachedClient(uid);
                        boolean next = !(cur != null && cur);
                        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                                new MaidGoetyNetworking.TogglePacket(uid, (byte) 1, next));
                    });
            this.promaid$goetyBtn = btn;
            promaid$addRenderable(screen, btn);
        } catch (Throwable ignored) {
        }
    }

    /** renderAddition 每帧同步文本：服务端 S2C 回来后按钮要立刻反映真实值 */
    @Inject(method = "renderAddition", at = @At("HEAD"))
    private void promaid$syncGoetyLabel(GuiGraphics graphics, int mouseX, int mouseY,
                                        float partialTick, CallbackInfo ci) {
        try {
            Button btn = this.promaid$goetyBtn;
            if (btn == null) {
                return;
            }
            EntityMaid maid = ((AbstractMaidContainerGui<?>) (Object) this).getMaid();
            if (maid == null) {
                return;
            }
            btn.setMessage(com.maidsmart.client.MaidConfigRowLayout.text(
                    com.maidsmart.client.MaidConfigRowLayout.LABELS[2],
                    promaid$value(maid.getUUID().toString())));
        } catch (Throwable ignored) {
        }
    }

    /** 按钮值文本：客户端缓存优先；未知 = 关（服务端 S2C 到了会纠正） */
    private static String promaid$value(String uid) {
        Boolean cur = MaidGoetyAuto.cachedClient(uid);
        return (cur != null && cur) ? "\u00a7a\u5f00" : "\u00a77\u5173";
    }

    /** 反射调用 Screen.addRenderableWidget（继承方法，无 refmap 时 @Shadow 定位不到） */
    private static void promaid$addRenderable(Screen screen,
                                              net.minecraft.client.gui.components.events.GuiEventListener widget) {
        try {
            java.lang.reflect.Method m = Screen.class.getDeclaredMethod("addRenderableWidget",
                    net.minecraft.client.gui.components.events.GuiEventListener.class);
            m.setAccessible(true);
            m.invoke(screen, widget);
        } catch (Exception ignored) {
        }
    }
}
