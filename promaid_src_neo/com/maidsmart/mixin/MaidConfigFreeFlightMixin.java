package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.config.MaidConfigContainerGui;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.MaidConfigButton;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.flight.MaidFreeFlightFlags;
import com.maidsmart.flight.MaidFreeFlightNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 实测六百七十八【仿创造飞行 · 女仆配置界面里的一行开关】——右键女仆 → 配置界面 →
 * 「创造飞行：开/关」，与 TLM 自己的「显示背包 / 能否开门」同一格式、同一列。
 *
 * 【为什么能这么做（关键调研结论）】TLM 的女仆配置界面确实是**硬编码**的（8 行
 * `MaidConfigButton`，没有注册表可插），但 `AbstractMaidContainerGui.initAdditionWidgets()`
 * 是 protected 的扩展点、`MaidConfigButton` 是公开控件——所以第三方可以**追加自己的一行**，
 * 不需要改 TLM 本体。我们仓库里「AI 记忆」开关用的就是这套（见 MaidConfigMemoryMixin），
 * 这里照搬：按钮位置右对齐、放在原生开关列（y=52 起）之上，不遮挡任何 TLM 控件。
 *
 * 【为什么不用 TLM 的 TaskData 存这个开关】TLM 的 `TaskDataRegister.writeSyncData` 会把编码结果
 * 强转 CompoundTag，一点开关就 ClassCastException 崩服（AiMemoryManager 的实测教训）——
 * 我们存 per-maid persistentData + 磁盘备份，客户端靠 S2C 缓存显示。
 *
 * 【无 refmap 注意】`addRenderableWidget` 是继承自 Screen 的 protected 方法——无 refmap 时
 * `@Shadow` 定位不到（会崩），照记忆开关的做法改反射调用。
 */
@Mixin(MaidConfigContainerGui.class)
public abstract class MaidConfigFreeFlightMixin {

    /** 我们这一行按钮（renderAddition 每帧同步文本用） */
    private MaidConfigButton promaid$freeFlightBtn = null;
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
            int w = ((Screen) (Object) this).width;
            String uid = maid.getUUID().toString();
            // 界面打开时先问一次真实状态（persistentData 只在服务端，客户端缓存可能是空的）
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new MaidFreeFlightNetworking.TogglePacket(uid, (byte) 0, false));
            MaidConfigButton btn = new MaidConfigButton(w - 174, 26,
                    Component.literal("创造飞行"),
                    Component.literal(promaid$label(uid)),
                    b -> {
                        long now = System.currentTimeMillis();
                        if (now - PROMAID$LAST_CLICK < 600) {
                            return;
                        }
                        PROMAID$LAST_CLICK = now;
                        Boolean cur = MaidFreeFlightFlags.cachedClient(uid);
                        boolean next = !(cur != null ? cur : MaidFreeFlightFlags.globalOn());
                        b.setValue(Component.literal(next ? "\u00a7a开" : "\u00a77关"));
                        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                                new MaidFreeFlightNetworking.TogglePacket(uid, (byte) 1, next));
                    });
            this.promaid$freeFlightBtn = btn;
            promaid$addRenderable((Screen) (Object) this, btn);
        } catch (Throwable ignored) {
        }
    }

    /** renderAddition 每帧同步文本：服务端 S2C 回来后按钮要立刻反映真实值 */
    @Inject(method = "renderAddition", at = @At("HEAD"))
    private void promaid$syncFreeFlightLabel(GuiGraphics graphics, int mouseX, int mouseY,
                                             float partialTick, CallbackInfo ci) {
        try {
            MaidConfigButton btn = this.promaid$freeFlightBtn;
            if (btn == null) {
                return;
            }
            EntityMaid maid = ((AbstractMaidContainerGui<?>) (Object) this).getMaid();
            if (maid == null) {
                return;
            }
            btn.setValue(Component.literal(promaid$label(maid.getUUID().toString())));
        } catch (Throwable ignored) {
        }
    }

    /** 按钮文本：未设置 = 跟随全局（全局关就是关）；显式值优先 */
    private static String promaid$label(String uid) {
        Boolean cur = MaidFreeFlightFlags.cachedClient(uid);
        if (cur == null) {
            return MaidFreeFlightFlags.globalOn() ? "\u00a7a开\u00a77(跟随全局)" : "\u00a77关\u00a78(跟随全局)";
        }
        return cur ? "\u00a7a开" : "\u00a77关";
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
