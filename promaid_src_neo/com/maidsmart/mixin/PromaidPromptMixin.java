package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.maidsmart.prompt.PromaidPromptAppender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * v1.5.82：Promaid 提示词运行时注入——拦截 TLM 的
 * `LLMMessage.systemChat(EntityMaid, String)`（两条设定路径唯一汇聚点，
 * 与 Heartfelt-connection 的 SmartPromptMixin 同点注入，各自追加互不冲突）。
 *
 * 追加坐姿工作说明（PromaidPromptAppender）：女仆坐着干活时 LLM 不再
 * 说要"先站起来"。
 *
 * v1.5.102g：修复注入方式——旧版 @ModifyArg(at=@At("HEAD")) 是非法组合
 * （@ModifyArg 必须指向方法调用指令，HEAD 是方法开头不是调用）→ 女仆一触发
 * 对话、LLMMessage 类被加载即抛 InvalidInjectionException 崩服（mixin 配置
 * required:true + defaultRequire:1，任何注入失败都致命）。改为 @ModifyVariable
 * (argsOnly=true) 在方法开头改写 String 参数——按类型匹配参数，与 jar 字节码
 * 具体内容无关，对原版/SMART 都稳健。
 */
@Mixin(LLMMessage.class)
public abstract class PromaidPromptMixin {

    @ModifyVariable(method = "systemChat", at = @At("HEAD"), argsOnly = true)
    private static String promaid$appendWorkPosture(String setting) {
        if (setting.contains(PromaidPromptAppender.MARKER)) {
            return setting; // 幂等：已注入不重复
        }
        return setting + "\n\n" + PromaidPromptAppender.build();
    }
}
