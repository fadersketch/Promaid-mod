package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.config.MaidConfigContainerGui;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.MaidConfigButton;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.goety.MaidGoetyAuto;
import com.maidsmart.goety.MaidGoetyNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 实测七百四十五·点1【飞行聚晶 · 女仆配置界面里的一行开关】——右键女仆 → 配置界面 →
 * 「飞行聚晶：开/关」，与仿创造飞行那一行同一格式、同一列。
 *
 * <p>玩家原话：「聚晶必须要使用指令这些 OP 权限才可以使用吗？常规生存不能使用？」——
 * 七百一十八 那一版只有 OP 命令，普通玩家开不了。这一行就是"主人自己就能开"的入口
 * （与 {@link MaidConfigFreeFlightMixin} 完全同构，只是把开关换成 Goety 自动档）。
 *
 * <p>【布局】MaidConfigButton 实测 **164×13**（javap：ctor 里 {@code sipush 164 / bipush 13}），
 * 而 TLM 原生开关列从 **y=52** 起。所以我们的三行必须排在 y=52 之上：本行取 **y=36**
 * （与"AI 记忆"y=8、"创造飞行"y=22 形成 14 的行距，13 高 ⇒ 36+13=49 &lt; 52，互不遮挡）。
 */
@Mixin(MaidConfigContainerGui.class)
public abstract class MaidConfigGoetyMixin {

    /** 我们这一行按钮（renderAddition 每帧同步文本用） */
    private MaidConfigButton promaid$goetyBtn = null;
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
            int w = ((Screen) (Object) this).width;
            String uid = maid.getUUID().toString();
            // 界面打开时先问一次真实状态（persistentData 只在服务端，客户端缓存可能是空的）
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new MaidGoetyNetworking.TogglePacket(uid, (byte) 0, false));
            MaidConfigButton btn = new MaidConfigButton(w - 174, 36,
                    Component.literal("飞行聚晶"),
                    Component.literal(promaid$goetyLabel(uid)),
                    b -> {
                        long now = System.currentTimeMillis();
                        if (now - PROMAID$GOETY_LAST_CLICK < 600) {
                            return;
                        }
                        PROMAID$GOETY_LAST_CLICK = now;
                        Boolean cur = MaidGoetyAuto.cachedClient(uid);
                        boolean next = !(cur != null && cur);
                        b.setValue(Component.literal(next ? "\u00a7a开" : "\u00a77关"));
                        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                                new MaidGoetyNetworking.TogglePacket(uid, (byte) 1, next));
                    });
            this.promaid$goetyBtn = btn;
            promaid$addRenderable((Screen) (Object) this, btn);
        } catch (Throwable ignored) {
        }
    }

    /** renderAddition 每帧同步文本：服务端 S2C 回来后按钮要立刻反映真实值 */
    @Inject(method = "renderAddition", at = @At("HEAD"))
    private void promaid$syncGoetyLabel(GuiGraphics graphics, int mouseX, int mouseY,
                                        float partialTick, CallbackInfo ci) {
        try {
            MaidConfigButton btn = this.promaid$goetyBtn;
            if (btn == null) {
                return;
            }
            EntityMaid maid = ((AbstractMaidContainerGui<?>) (Object) this).getMaid();
            if (maid == null) {
                return;
            }
            btn.setValue(Component.literal(promaid$goetyLabel(maid.getUUID().toString())));
        } catch (Throwable ignored) {
        }
    }

    /** 按钮文本：客户端缓存优先；未知 = 关（服务端 S2C 到了会纠正） */
    private static String promaid$goetyLabel(String uid) {
        Boolean cur = MaidGoetyAuto.cachedClient(uid);
        return (cur != null && cur) ? "\u00a7a开" : "\u00a77关";
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
