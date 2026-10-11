package com.maidsmart;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * 混入配置插件：给本模组的混入加一道「可选择性关闭」的闸，便于与别的模组发生混入冲突时
 * 二分定位，不必改源码重编。默认全部生效（行为与不加插件完全一致）。
 *
 * <p>用法：{@code -Dpromaid.mixin.disabled=all} 关掉全部混入；{@code -Dpromaid.mixin.disabled=MaidSweepMixin}
 * 只关一个（多个用逗号分隔，取混入类的简单名）。命中时在启动日志打一行 {@code [promaid]} 记录。
 *
 * <p>只用 JDK 与 Mixin 本体，不碰 Minecraft 与本模组的配置系统——插件在最早期（配置尚未加载）
 * 运行，不能依赖本模组自己的任何类；日志走标准输出，此时 slf4j 未必就绪。
 */
public final class PromaidMixinPlugin implements IMixinConfigPlugin {
    private static final String DISABLE_PROP = "promaid.mixin.disabled";
    private Set<String> disabled;

    private Set<String> disabledSet() {
        if (this.disabled == null) {
            Set<String> set = new HashSet<>();
            String raw = System.getProperty(DISABLE_PROP);
            if (raw != null) {
                for (String part : raw.split(",")) {
                    String name = part.trim();
                    if (!name.isEmpty()) {
                        set.add(name);
                    }
                }
            }
            this.disabled = set;
        }
        return this.disabled;
    }

    @Override
    public void onLoad(String mixinPackage) {
        Set<String> set = disabledSet();
        if (!set.isEmpty()) {
            System.out.println("[promaid] 混入插件：" + DISABLE_PROP + "=" + set + "，将按此关闭对应混入");
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        Set<String> set = disabledSet();
        if (set.isEmpty()) {
            return true;
        }
        String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        boolean off = set.contains("all") || set.contains(simple);
        if (off) {
            System.out.println("[promaid] 混入已关闭：" + simple + " -> " + targetClassName);
        }
        return !off;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
    }
}
