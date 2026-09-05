package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.build.BlueprintBookNetworking;
import com.maidsmart.memory.AiMemoryManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * AI 记忆 per-maid 开关（v1.5.86）：注入 maidmarriage 的调试面板（按 T 对准女仆打开），
 * 在面板底部加 "AI记忆：开/关" 按钮。
 *
 * 目标类在 maidmarriage（不在编译 classpath）→ @Pseudo + 字符串类名（未安装静默跳过）；
 * maidmarriage 类未混淆，maidUuid 字段用开发名 @Shadow。
 *
 * 点击流程：客户端本地翻转按钮文字（乐观更新）→ 发 C2S AiMemoryTogglePacket →
 * 服务端校验（主人/OP）→ setAndSyncData 写 TaskData 并同步回客户端。
 */
@Pseudo
@Mixin(targets = "com.example.maidmarriage.client.MaidDebugPanelScreen")
public abstract class MaidDebugPanelMixin {
    /** maidmarriage 调试面板的女仆 UUID（开发名，未混淆；目标类自身字段 @Shadow 可定位） */
    @Shadow
    private UUID maidUuid;

    /**
     * v1.5.98 修复：无 refmap 时 @Shadow 无法定位【继承】成员（Screen.addRenderableWidget）——
     * 原 SRG 名 m_142416_ 在未混淆的 maidmarriage 类上定位失败 → mixin apply 崩溃。
     * 改用反射调用（protected，泛型擦除参数 GuiEventListener）。
     */
    private static void addRenderable(Screen screen, net.minecraft.client.gui.components.events.GuiEventListener widget) {
        try {
            java.lang.reflect.Method m = Screen.class.getDeclaredMethod("m_142416_",
                    net.minecraft.client.gui.components.events.GuiEventListener.class);
            m.setAccessible(true);
            m.invoke(screen, widget);
        } catch (Exception ignored) {
        }
    }

    /**
     * v1.1.0 实测三百一十九（Issue #2：maidmarriage 2.2.0/2.3.0 打开调试面板即崩）：
     * require=0——maidmarriage 更新后 MaidDebugPanelScreen.init 方法被移除/重命名，
     * @Inject 找不到目标 → Mixin apply failed → 打开界面即崩（100% 复现）。
     * require=0 后找不到目标静默跳过（AI 记忆按钮不显示，功能降级但不崩），
     * 与 MixinInteractionSittingAllow 的 require=0 同款策略。
     */
    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private void promaidAddMemoryToggle(CallbackInfo ci) {
        try {
            Minecraft mc = Minecraft.m_91087_();
            if (mc == null || mc.f_91073_ == null || this.maidUuid == null) {
                return;
            }
            EntityMaid found = null;
            for (net.minecraft.world.entity.Entity e : mc.f_91073_.m_104735_()) {
                if (e instanceof EntityMaid m && m.m_20148_().equals(this.maidUuid)) {
                    found = m;
                    break;
                }
            }
            if (found == null) {
                return;
            }
            final EntityMaid maid = found;
            boolean cur = AiMemoryManager.isEnabled(maid);
            // v1.5.190 修复：旧版按钮 (w/2-80, h-40) 覆盖原生"应用到女仆/刷新当前值"
            // 按钮（y=196..216 行）并挂出面板下缘。新位置：面板底部下方独立一行
            // （Apply/Refresh/Close 之下，居中），屏幕内时贴面板、屏幕小时钳制到
            // h-26，不与任何原生控件重叠。
            int w = ((net.minecraft.client.gui.screens.Screen) (Object) this).f_96543_;
            int h = ((net.minecraft.client.gui.screens.Screen) (Object) this).f_96544_;
            int panelTop = h / 2 - 92;
            int y = Math.min(panelTop + 192, h - 26);
            net.minecraft.client.gui.components.Button btn = net.minecraft.client.gui.components.Button
                    .m_253074_(Component.m_237113_(cur ? "AI记忆：开" : "AI记忆：关"), b -> {
                        boolean next = !AiMemoryManager.isEnabled(maid);
                        b.m_93666_(Component.m_237113_(next ? "AI记忆：开" : "AI记忆：关"));
                        BlueprintBookNetworking.CHANNEL.sendToServer(
                                new BlueprintBookNetworking.AiMemoryTogglePacket(this.maidUuid.toString(), next));
                    })
                    .m_252987_(w / 2 - 80, y, 160, 20)
                    .m_253136_();
            addRenderable((Screen) (Object) this, btn);
            // v1.5.387：红字免责声明——本面板是开发者调试工具，未经适配，
            // 出问题不负责也不修（红字常驻面板底部，按钮下方一行）
            try {
                int warnY = Math.min(y + 24, h - 8);
                net.minecraft.client.gui.components.StringWidget warn =
                        new net.minecraft.client.gui.components.StringWidget(
                                0, warnY, w, 20,
                                Component.m_237113_("\u00a7c仅为开发者调试工具，没有做任何相关适配，出bug不负责，也不会去修。"),
                                mc.f_91062_); // Minecraft.font（公开字段，避开 Screen 继承成员 @Shadow 定位风险）
                warn.m_269033_(0xFFFF5555); // setColor 纯红
                addRenderable((Screen) (Object) this, warn);
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
            // maidmarriage 版本不兼容：静默（不干扰面板）
        }
    }
}
