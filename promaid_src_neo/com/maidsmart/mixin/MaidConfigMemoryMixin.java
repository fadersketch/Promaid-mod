package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.config.MaidConfigContainerGui;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.memory.AiMemoryExtractor;
import com.maidsmart.memory.AiMemoryManager;
import com.maidsmart.memory.AiMemoryModels;
import com.maidsmart.memory.AiMemoryStore;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 长期记忆可视化（v1.5.25）：在 TLM 女仆配置界面（右键女仆打开的
 * MaidConfigContainerGui）的额外渲染区（renderAddition，TLM 自有方法）绘制
 * "长期记忆"面板。
 *
 * v1.5.86 改版：数据源从旧 4 键 MaidMemoryManager 换成 AI 记忆系统——
 * 显示 per-maid 开关状态 + 记忆条目投影（仅单机/局域网主机可读本地 jsonl；
 * 纯联机客户端只显示开关与提示）。
 *
 * 纯只读展示，不修改任何数据。
 *
 * v1.5.25 修复：不再用 @Shadow 取 Screen.font 字段（无 refmap 时继承字段
 * 定位失败导致启动崩溃），改用 Minecraft.getInstance().font（public final 字段）。
 */
@Mixin(MaidConfigContainerGui.class)
public abstract class MaidConfigMemoryMixin {


    /** v1.5.227：开关防连点——双击会把"关"变回"开"（实测 03:14:31 关→32 开），
     *  600ms 内的重复点击直接忽略 */
    private static long LAST_TOGGLE_CLICK = 0;

    /** v1.5.242：界面里的"AI 记忆"开关按钮——renderAddition 每次渲染同步文本，
     *  旧版只在 initAdditionWidgets 设置一次，Query/Sync 回来后按钮还显示旧值
     *  （显示"开"时点一下 = 又开，回弹的体感来源之一）
     *  <p>实测八百零五：位置改由 {@link com.maidsmart.client.MaidConfigRowLayout} 统一算
     *  （面板左侧外边距竖排，整列在面板之外，不遮 TLM 控件、彼此不重叠）。 */
    private Button maidsmart$memoryBtn = null;

    /**
     * v1.5.98 修复：无 refmap 环境下 @Shadow 无法定位【继承】成员（Screen/
     * ContainerScreen 的方法与字段）——原 SRG 名 addRenderableWidget/leftPos 在换回原版
     * TLM 后 mixin apply 崩溃（SMART.jar 是 SRG 重映射版所以之前正常）。
     * 改用：
     * - 按钮添加 → 反射调用 Screen.addRenderableWidget（protected，泛型擦除参数 GuiEventListener）
     * - 按钮定位 → Screen public 宽高 width/height（屏幕右上角，不依赖 leftPos/topPos）
     */
    private static void addRenderable(Screen screen, net.minecraft.client.gui.components.events.GuiEventListener widget) {
        try {
            java.lang.reflect.Method m = Screen.class.getDeclaredMethod("addRenderableWidget",
                    net.minecraft.client.gui.components.events.GuiEventListener.class);
            m.setAccessible(true);
            m.invoke(screen, widget);
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("MaidConfigMemoryMixin", ignored);
        }
    }


    /**
     * v1.5.190：反射读取继承字段 leftPos/topPos（AbstractContainerScreen 的
     * leftPos/topPos，protected）——无 refmap 时 @Shadow 无法定位继承成员（与
     * addRenderable 同源问题）；从运行时类沿继承链向上找字段名（dev=leftPos /
     * 生产=SRG leftPos 都能命中）。
     */
    private static int reflectIntField(Object o, String name) {
        try {
            for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.getInt(o);
                } catch (NoSuchFieldException ignored) {
                }
            }
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("MaidConfigMemoryMixin", ignored);
        }
        return 0;
    }

    /**
     * v1.5.190：读 TLM 女仆配置界面的 leftPos/topPos——优先取 AbstractContainerGui
     * 反射（生产环境 SRG leftPos），反射失败退回容器边距公式
     * （(width-imageWidth)/2；imageWidth=imageWidth 同样是 protected 继承字段）。
     */
    private static int containerLeft(int screenW, Object gui, int imageW) {
        int v = reflectIntField(gui, "leftPos");
        if (v == 0) {
            v = reflectIntField(gui, "leftPos");
        }
        if (v == 0) {
            v = (screenW - imageW) / 2;
        }
        return v;
    }

    private static int containerTop(int screenH, Object gui, int imageH) {
        int v = reflectIntField(gui, "topPos");
        if (v == 0) {
            v = reflectIntField(gui, "topPos");
        }
        if (v == 0) {
            v = (screenH - imageH) / 2;
        }
        return v;
    }

    /**
     * 开关按钮同格式）追加"AI 记忆"开关——照搬 TLM 的 MaidConfigButton 模式
     * （label=配置名、value=开/关、点击翻转+setValue）；点击发 C2S 到服务端
     * setAndSyncData（per-maid TaskData），服务端同步回客户端。
     * 右缘 w+54 永远超出屏幕，可点击区（x+120..130 / x+154..164）全部在屏幕外，
     * 开关永远点不到。改为右对齐 (w-174, 8)：按钮 x=w-174..w-10 完整在屏内，
     * 点击区 w-54..w-44 与 w-20..w-10 都在屏内；且 y=8..21 在原生开关列 （y=52 起）之上，不遮挡任何 TLM 控件。
     */
    @Inject(method = "initAdditionWidgets", at = @At("TAIL"))
    private void promaid$addAiMemoryToggle(CallbackInfo ci) {
        try {
            AbstractMaidContainerGui<?> gui = (AbstractMaidContainerGui<?>) (Object) this;
            EntityMaid maid = gui.getMaid();
            if (maid == null) {
                return;
            }
            Screen screen = (Screen) (Object) this;
            boolean cur = AiMemoryManager.isEnabled(maid);
            // v1.5.227：界面打开时主动查询真实状态（缓存为空时按钮显示会停留在
            // 旧值——查询后服务端回 MemoryStateSyncPacket 纠正显示）
            // v1.5.242：每次打开都查询（去掉静态去重——同进程内第二次打开界面跳过
            // 查询，缓存过期时按钮显示旧值"开"，玩家一点反而又开）
            String uid = maid.getUUID().toString();
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.maidsmart.build.BlueprintBookEntityPackets.MemoryStateQueryPacket(uid));
            Button btn = com.maidsmart.client.MaidConfigRowLayout.toggle(this, screen.width,
                    screen.height, 0, com.maidsmart.client.MaidConfigRowLayout.LABELS[0],
                    cur ? "\u00a7a\u5f00" : "\u00a77\u5173", () -> {
                        // v1.5.227：防连点——600ms 内重复点击忽略（双击 = 关→开）
                        long now = System.currentTimeMillis();
                        if (now - LAST_TOGGLE_CLICK < 600) {
                            return;
                        }
                        LAST_TOGGLE_CLICK = now;
                        boolean next = !AiMemoryManager.isEnabled(maid);
                        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                                new com.maidsmart.build.BlueprintBookEntityPackets.AiMemoryTogglePacket(
                                        uid, next));
                    });
            this.maidsmart$memoryBtn = btn;
            addRenderable(screen, btn);
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("MaidConfigMemoryMixin", ignored);
        }
    }

    /**
     * 【v1.6.0 实测八百一十三：删掉「AI 长期记忆」整块面板】
     * 「把图片中长期记忆那段字删掉，它挡住 ui 了」。
     * <p>旧版在这块 {@code renderAddition} 里，于 TLM 女仆配置界面**容器内部**左上角
     * （{@code leftPos+5, topPos+20}）画一整块：「—— AI 长期记忆 —— / 记忆提取：开 /
     * 人格：xx / TLM 人设：有 / 记忆日记：N 篇 / [salience] 记忆条目…」。
     * 那一块会压住她自己的图标行与开关列——而同样这些状态在 Promaid 手册的 「AI 记忆系统」章里写得更完整。所以整块停止绘制。 <p>【保留什么】只保留**开关按钮文本的同步**：那个窄按钮由 {@link com.maidsmart.client.MaidConfigRowLayout} 摆在面板**之外**的左边距竖排一列 （不遮任何 TLM 控件），仍要在查询/同步包回来之后即时纠正显示——否则会出现 「显示『开』时点一下反而又开」的回弹观感（v1.5.242 的老问题）。
     */
    @Inject(method = "renderAddition", at = @At("HEAD"))
    private void maidsmart$renderMemories(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        try {
            AbstractMaidContainerGui<?> gui = (AbstractMaidContainerGui<?>) (Object) this;
            EntityMaid maid = gui.getMaid();
            if (maid == null) {
                return;
            }
            if (this.maidsmart$memoryBtn != null) {
                boolean e = AiMemoryManager.isEnabled(maid);
                this.maidsmart$memoryBtn.setMessage(com.maidsmart.client.MaidConfigRowLayout.text(
                        com.maidsmart.client.MaidConfigRowLayout.LABELS[0],
                        e ? "\u00a7a\u5f00" : "\u00a77\u5173"));
            }
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("MaidConfigMemoryMixin", ignored);
        }
    }
}
