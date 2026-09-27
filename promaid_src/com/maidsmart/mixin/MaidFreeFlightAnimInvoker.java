package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.api.entity.IMaid;
import com.github.tartaricacid.touhoulittlemaid.client.animation.gecko.AnimationRegister;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.event.predicate.AnimationEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.function.BiPredicate;

/**
 * 实测七百〇二【仿创造飞行 · 1.20.1 精简版 · 让模型播 fly 动画】——把 TLM 唯一的注册入口暴露出来。
 *
 * 【为什么需要】TLM 的动画状态机是**Java 硬编码**的（{@code AnimationRegister.register(name, priority,
 * predicate)}，状态按 priority 0..4 顺序取第一个命中者），里面**没有 fly / elytra_fly 这两档**——而
 * 模型包（如圣女酒狐）里明明做好了这两个动画。也就是说：**动画是死数据，永远不会被播**。
 * 它们只在 YSM 玩家侧被触发，女仆走不进去。我们注册一个同名状态 {@code fly}，模型包里那条动画就活了。
 *
 * 【为什么用 @Invoker】{@code register} 是 private static，第三方没有公开入口。用 @Invoker 暴露即可
 * ——TLM 的类**不参与混淆/remap**，所以本 mixin 在我们的本地手搓构建里也能正常生效。
 *
 * 【1.20.1 实证（javap 两份 jar 对照）】{@code register} 的两个重载签名在 1.20.1 与 1.21.1 **逐字相同**
 * （{@code (String, ILoopType, int, BiPredicate)} 与 {@code (String, int, BiPredicate)}，都还是
 * private static）——所以这一套可以原样搬，一行不用改。
 */
@Mixin(AnimationRegister.class)
public interface MaidFreeFlightAnimInvoker {

    /** 暴露 TLM 的 private static register(String, int, BiPredicate) */
    @Invoker("register")
    static void promaid$registerState(String name, int priority,
            BiPredicate<IMaid, AnimationEvent<?>> predicate) {
        throw new AssertionError();
    }
}
