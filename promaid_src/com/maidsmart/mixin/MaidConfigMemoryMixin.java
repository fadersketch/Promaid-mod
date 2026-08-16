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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 长期记忆可视化（v1.5.25）：在 TLM 女仆配置界面（右键女仆打开的
 * MaidConfigContainerGui）的额外渲染区（renderAddition，TLM 自有方法）绘制
 * "长期记忆"面板。
 *
 * v1.5.86 改版：数据源从旧 4 键 MaidMemoryManager 换成 AI 记忆系统——
 * 显示 per-maid 开关状态 + 记忆条目投影（仅单机/局域网主机可读本地 jsonl；
 * 纯联机客户端只显示开关与提示）。
 *
 * 关系状态仍由 Heartfelt-connection 的 RelationshipExemption 判定（反射软调用）。
 * 纯只读展示，不修改任何数据。
 *
 * v1.5.25 修复：不再用 @Shadow 取 Screen.font 字段（无 refmap 时继承字段
 * 定位失败导致启动崩溃），改用 Minecraft.getInstance().font（public final 字段）。
 */
@Mixin(MaidConfigContainerGui.class)
public abstract class MaidConfigMemoryMixin {

    /** Heartfelt-connection 的 RelationshipExemption 反射句柄（缓存；未装时为 null） */
    private static java.lang.reflect.Method RELATION_LABEL;

    /** v1.5.227：开关防连点——双击会把"关"变回"开"（实测 03:14:31 关→32 开），
     *  600ms 内的重复点击直接忽略 */
    private static long LAST_TOGGLE_CLICK = 0;

    /** v1.5.242：界面里的"AI 记忆"开关按钮——renderAddition 每次渲染同步文本，
     *  旧版只在 initAdditionWidgets 设置一次，Query/Sync 回来后按钮还显示旧值
     *  （显示"开"时点一下 = 又开，回弹的体感来源之一） */
    private com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.MaidConfigButton
            maidsmart$memoryBtn = null;

    /**
     * v1.5.98 修复：无 refmap 环境下 @Shadow 无法定位【继承】成员（Screen/
     * ContainerScreen 的方法与字段）——原 SRG 名 m_142416_/f_97735_ 在换回原版
     * TLM 后 mixin apply 崩溃（SMART.jar 是 SRG 重映射版所以之前正常）。
     * 改用：
     * - 按钮添加 → 反射调用 Screen.m_142416_（protected，泛型擦除参数 GuiEventListener）
     * - 按钮定位 → Screen public 宽高 f_96543_/f_96544_（屏幕右上角，不依赖 leftPos/topPos）
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

    /** 反射调用 Heartfelt 的关系中文标签（妻子/女儿/恋人）；未装或异常返回 null */
    private static String reflectRelationLabel(EntityMaid maid) {
        try {
            if (RELATION_LABEL == null) {
                Class<?> cls = Class.forName("com.heartfelt.connection.relationship.RelationshipExemption");
                RELATION_LABEL = cls.getMethod("relationLabel", EntityMaid.class);
            }
            Object value = RELATION_LABEL.invoke(null, maid);
            return value instanceof String s ? s : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * v1.5.190：反射读取继承字段 f_97735_/f_97736_（AbstractContainerScreen 的
     * leftPos/topPos，protected）——无 refmap 时 @Shadow 无法定位继承成员（与
     * addRenderable 同源问题）；从运行时类沿继承链向上找字段名（dev=leftPos /
     * 生产=SRG f_97735_ 都能命中）。
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
        } catch (Exception ignored) {
        }
        return 0;
    }

    /**
     * v1.5.190：读 TLM 女仆配置界面的 leftPos/topPos——优先取 AbstractContainerGui
     * 反射（生产环境 SRG f_97735_），反射失败退回容器边距公式
     * （(f_96543_-f_97726_)/2；f_97726_=imageWidth 同样是 protected 继承字段）。
     */
    private static int containerLeft(int screenW, Object gui, int imageW) {
        int v = reflectIntField(gui, "f_97735_");
        if (v == 0) {
            v = reflectIntField(gui, "leftPos");
        }
        if (v == 0) {
            v = (screenW - imageW) / 2;
        }
        return v;
    }

    private static int containerTop(int screenH, Object gui, int imageH) {
        int v = reflectIntField(gui, "f_97736_");
        if (v == 0) {
            v = reflectIntField(gui, "topPos");
        }
        if (v == 0) {
            v = (screenH - imageH) / 2;
        }
        return v;
    }

    /**
     * v1.5.89：在 TLM 女仆配置界面的开关区（initAdditionWidgets，与"显示背包"等
     * 开关按钮同格式）追加"AI 记忆"开关——照搬 TLM 的 MaidConfigButton 模式
     * （label=配置名、value=开/关、点击翻转+setValue）；点击发 C2S 到服务端
     * setAndSyncData（per-maid TaskData），服务端同步回客户端。
     *
     * v1.5.190 修复：旧版按钮放 (w-110, 8)——MaidConfigButton 硬编码 164 宽，
     * 右缘 w+54 永远超出屏幕，可点击区（x+120..130 / x+154..164）全部在屏幕外，
     * 开关永远点不到。改为右对齐 (w-174, 8)：按钮 x=w-174..w-10 完整在屏内，
     * 点击区 w-54..w-44 与 w-20..w-10 都在屏内；且 y=8..21 在原生开关列
     * （y=52 起）之上，不遮挡任何 TLM 控件。
     */
    @Inject(method = "initAdditionWidgets", at = @At("TAIL"))
    private void promaid$addAiMemoryToggle(CallbackInfo ci) {
        try {
            AbstractMaidContainerGui<?> gui = (AbstractMaidContainerGui<?>) (Object) this;
            EntityMaid maid = gui.getMaid();
            if (maid == null) {
                return;
            }
            int w = ((Screen) (Object) this).f_96543_;
            boolean cur = AiMemoryManager.isEnabled(maid);
            // v1.5.227：界面打开时主动查询真实状态（缓存为空时按钮显示会停留在
            // 旧值——查询后服务端回 MemoryStateSyncPacket 纠正显示）
            // v1.5.242：每次打开都查询（去掉静态去重——同进程内第二次打开界面跳过
            // 查询，缓存过期时按钮显示旧值"开"，用户一点反而又开）
            String uid = maid.m_20148_().toString();
            com.maidsmart.build.BlueprintBookNetworking.CHANNEL.sendToServer(
                    new com.maidsmart.build.BlueprintBookNetworking.MemoryStateQueryPacket(uid));
            com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.MaidConfigButton btn =
                    new com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.MaidConfigButton(
                            w - 174, 8,
                            Component.m_237113_("AI 记忆"),
                            Component.m_237113_(cur ? "\u00a7a开" : "\u00a77关"),
                            b -> {
                                // v1.5.227：防连点——600ms 内重复点击忽略（双击 = 关→开）
                                long now = System.currentTimeMillis();
                                if (now - LAST_TOGGLE_CLICK < 600) {
                                    return;
                                }
                                LAST_TOGGLE_CLICK = now;
                                boolean next = !AiMemoryManager.isEnabled(maid);
                                b.setValue(Component.m_237113_(next ? "\u00a7a开" : "\u00a77关"));
                                com.maidsmart.build.BlueprintBookNetworking.CHANNEL.sendToServer(
                                        new com.maidsmart.build.BlueprintBookNetworking.AiMemoryTogglePacket(
                                                uid, next));
                            });
            this.maidsmart$memoryBtn = btn;
            addRenderable((Screen) (Object) this, btn);
        } catch (Exception ignored) {
        }
    }

    @Inject(method = "renderAddition", at = @At("HEAD"))
    private void maidsmart$renderMemories(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        try {
            AbstractMaidContainerGui<?> gui = (AbstractMaidContainerGui<?>) (Object) this;
            EntityMaid maid = gui.getMaid();
            if (maid == null) {
                return;
            }
            // v1.5.242：每次渲染同步"AI 记忆"开关按钮文本——查询/同步包回来
            // 后按钮即时纠正（旧版只在 initAdditionWidgets 设置一次，显示旧值
            // "开"时用户一点反而又开，是开关回弹的体感来源之一）
            if (this.maidsmart$memoryBtn != null) {
                boolean e = AiMemoryManager.isEnabled(maid);
                this.maidsmart$memoryBtn.setValue(Component.m_237113_(e ? "\u00a7a开" : "\u00a77关"));
            }
            Font font = net.minecraft.client.Minecraft.m_91087_().f_91062_;
            // v1.5.190：面板移到容器内左上（leftPos+5, topPos+20），宽度 ≤245——
            // 旧版屏幕绝对坐标 x=168 与原生开关列（leftPos+86 起，y=52 起）横向
            // 重叠，记忆行从 y=45 开始直接压在本机开关按钮上。新位置在图标行
            // （y 6..15）之下、原生开关列（y=52）之上，互不遮挡。
            int screenW = ((Screen) (Object) this).f_96543_;
            int screenH = ((Screen) (Object) this).f_96544_;
            int imageW = reflectIntField(this, "f_97726_"); // imageWidth（继承，0=失败）
            int imageH = reflectIntField(this, "f_97727_");
            if (imageW == 0) {
                imageW = 256;
            }
            if (imageH == 0) {
                imageH = 256;
            }
            int x = containerLeft(screenW, this, imageW) + 5;
            int y = containerTop(screenH, this, imageH) + 20;
            if (x < 4) {
                x = 4;
            }
            if (y < 8) {
                y = 8;
            }
            graphics.m_280653_(font, Component.m_237113_("—— AI 长期记忆 ——"), x, y, 0xFFFFD700);
            y += 11;
            // per-maid 开关状态（客户端 TaskData 已同步）
            boolean enabled = AiMemoryManager.isEnabled(maid);
            graphics.m_280653_(font, Component.m_237113_(enabled
                            ? "\u00a7a记忆提取：开（对话攒满自动提取）"
                            : "\u00a77记忆提取：关（可在右上角开启）"),
                    x, y, enabled ? 0xFF55FF55 : 0xFF888888);
            y += 11;
            // v1.5.227：记忆关闭时【不显示记忆列表】——"女仆记忆那一栏关不掉"的
            // 直接体感来源：开关显示"关"但列表还在，看起来像没关掉。关闭时只保留
            // 开关状态行 + 提示，列表完全隐藏。
            if (!enabled) {
                if (y + 10 < 52) {
                    drawLine(font, graphics, x, y, "（记忆已关闭——开启后恢复显示）", 0xFF666666);
                }
                return;
            }
            String relation = reflectRelationLabel(maid);
            if (relation != null) {
                graphics.m_280653_(font, Component.m_237113_("关系：" + relation), x, y, 0xFF55FF55);
                y += 11;
            }
            // 记忆条目投影（仅单机/局域网主机可读本地 jsonl；联机只显示提示）——
            // v1.5.190：只显示 2 条（y 上限 52 = 原生开关列顶部，防重叠；完整记忆
            // 请看Promaid 手册的"女仆记忆"页）
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.m_91087_();
            if (mc.m_91092_() != null) {
                // v1.5.251：灵魂女仆 → 读全局灵魂目录（客户端单机直接读 config
                // 目录文件；soulId 由 SyncPacket 缓存）
                java.nio.file.Path root;
                String soulId = com.maidsmart.memory.AiMemoryManager.clientSoulId(
                        maid.m_20148_().toString());
                if (soulId != null && !soulId.isEmpty()) {
                    root = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                            .resolve("maid_smart").resolve("souls").resolve(soulId)
                            .resolve("memory");
                } else {
                    root = AiMemoryExtractor.memoryRoot(mc.m_91092_());
                }
                AiMemoryStore store = AiMemoryStore.of(maid.m_20148_(), root);
                // v1.1.0：人格种子状态（只读展示——文件在女仆记忆目录，可手改：
                // persona.properties 人格 / traits.properties 参数 / core_memories.jsonl 核心记忆）
                if (y + 10 < 52) {
                    String pname = com.maidsmart.persona.PersonaPackage.personaName(store.dir());
                    if (pname != null) {
                        graphics.m_280653_(font, Component.m_237113_("\u00a7b人格：" + pname), x, y, 0xFF55FFFF);
                        y += 11;
                    }
                }
                // v1.2.1：TLM 人设统一状态（有→补充模式 / 无→完整模式；客户端取不到则跳过）
                if (y + 10 < 52) {
                    try {
                        if (maid.getAiChatManager() != null) {
                            boolean has = com.maidsmart.memory.AiMemoryContext.tlmHasPersona(maid);
                            drawLine(font, graphics, x, y,
                                    has ? "\u00a7bTLM 人设：有（补充模式）" : "\u00a77TLM 人设：无（完整模式）",
                                    has ? 0xFF55FFFF : 0xFF888888);
                            y += 11;
                        }
                    } catch (Exception ignored) {
                    }
                }
                List<AiMemoryModels.Paragraph> top = new ArrayList<>(store.paragraphs());
                top.sort(Comparator.comparingInt(AiMemoryModels.Paragraph::salience).reversed());
                if (top.isEmpty()) {
                    if (y + 10 < 52) {
                        drawLine(font, graphics, x, y, "（暂无记忆——多和女仆对话会慢慢积累）", 0xFF888888);
                    }
                } else {
                    int shown = 0;
                    for (AiMemoryModels.Paragraph p : top) {
                        if (shown >= 2 || y + 10 >= 52) {
                            break;
                        }
                        // v1.5.251：显示来源世界 + 获得时间
                        drawLine(font, graphics, x, y,
                                "[" + p.salience() + "] " + p.content()
                                        + com.maidsmart.memory.AiMemoryModels.memoryMeta(p), 0xFFAAAAAA);
                        y += 11;
                        shown++;
                    }
                    if (y + 10 < 52) {
                        drawLine(font, graphics, x, y, "（完整记忆见 Promaid 手册·女仆记忆页）", 0xFF666666);
                    }
                }
            } else if (y + 10 < 52) {
                drawLine(font, graphics, x, y, "（联机客户端无法预览记忆文件）", 0xFF666666);
            }
        } catch (Exception ignored) {
        }
    }

    /** 超长截断绘制一行（防溢出界面） */
    private static void drawLine(Font font, GuiGraphics graphics, int x, int y, String line, int color) {
        while (font.m_92895_(line) > 150 && line.length() > 1) {
            line = line.substring(0, line.length() - 1);
        }
        graphics.m_280653_(font, Component.m_237113_(line), x, y, color);
    }
}
