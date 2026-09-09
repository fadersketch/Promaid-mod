package com.maidsmart.client;

import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * 仅客户端类（专用服务器上永不加载）：集中存放一切带客户端类型的方法签名与合成 lambda。
 *
 * 【为什么必须单独成类】@Mod 主类在服务端也会被 FML 反射（getDeclaredConstructor /
 * getDeclaredMethods），反射解析方法描述符时会把内联 lambda 编译出的合成方法一并解析——
 * 主类里内联的 (mc, parent) -> new PromaidConfigScreen(...) 合成方法描述符含
 * net.minecraft.client.gui.screens.Screen，NeoForge/Forge 的 RuntimeDistCleaner 在
 * DEDICATED_SERVER 直接抛 "Attempted to load class ... for invalid dist DEDICATED_SERVER"
 * → 整个 mod 加载失败、服务器启动中止（1.20.1 粉丝服崩报告实证）。
 * 主类只在 dist.isClient() 分支里静态调用本类，服务端该分支不执行 → 本类不被加载 →
 * 客户端类型永不被触碰。
 */
public final class PromaidClientSetup {
    private PromaidClientSetup() {
    }

    /** 注册"模组列表→promaid→Config"自定义配置面板（客户端专属扩展点） */
    public static void registerConfigScreen(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (mc, parent) -> new com.maidsmart.config.PromaidConfigScreen(parent));
    }
}
