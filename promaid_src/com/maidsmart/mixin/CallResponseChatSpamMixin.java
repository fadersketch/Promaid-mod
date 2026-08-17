package com.maidsmart.mixin;

// v1.5.386：CallResponseChatSpamMixin 已废弃并从 mixins.promaid.json 移除。
//
// 历史：v1.5.316 为屏蔽爱憎分明(callresponse 2.0.2)女仆背包界面的"饱食度"调试
// 聊天消息而加入——callresponse 的 MaidHungerGuiDisplay.onMaidGuiRender 每 500
// 帧往聊天框发一条调试消息，与 tweakermore 的 ChatHud 限制 mixin 冲突会损坏
// ChatComponent 内部链表导致渲染崩溃。
//
// 失效原因：callresponse 2.0.4 已移除 onMaidGuiRender 方法（javap 实证：
// MaidHungerGuiDisplay 现仅含 onMaidGuiInit / findFreeTabPosition / isAreaFree /
// overlaps / openStatusPage，调试聊天消息已不存在）。@Redirect 找不到目标方法 →
// InvalidInjectionException → 整个 promaid 模组状态被 Forge 标记为 broken →
// 所有后续 Forge 事件被拒绝发送 → 渲染线程 NPE 崩溃（crash-2026-08-17_14.39.05）。
//
// 此文件保留为空类（无 @Mixin 注解，不参与编译产物），仅作历史记录。
// 类文件已从 mixins.promaid.json 的 client 列表中移除。

/**
 * @deprecated since v1.5.386：目标方法 onMaidGuiRender 在 callresponse 2.0.4 中已不存在。
 * 保留空壳仅为源码历史可追溯；不再编译为 mixin 类。
 */
@Deprecated
public class CallResponseChatSpamMixin {
    private CallResponseChatSpamMixin() {
    }
}
