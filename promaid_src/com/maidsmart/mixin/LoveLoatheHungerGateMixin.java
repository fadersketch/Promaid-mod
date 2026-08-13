package com.maidsmart.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.5.199：爱憎分明饥饿系统测试开关（misc.loveLoatheHungerOff，默认 true=关闭）。
 *
 * 背景：爱憎分明的 HungerManager（纯 Forge 事件，无 mixin）在 onServerTick 里
 * 每 20 tick 干全部的事——自然衰减、饿死扣血（starve 2 点/秒）、撑死扣血 +
 * OverfedDeath 标签（1 点/秒 + 死亡取消广播"被撑死了"）、饱腹回血、消耗饥饿回血、
 * 速度四档 clobber、以及【自动进食】（扫背包任意食物，腐肉也吃 → "越吃越饿"）。
 * 用户测试期要求先把饥饿效果和撑死关掉（日志：女仆饿死/被尸壳群殴时自保不喝药）。
 *
 * 做法：配置开关打开时，HEAD cancel 掉 onServerTick（饿死/撑死/自动进食/速度
 * 惩罚全禁）与 onMaidDeath（防御：旧 OverfedDeath 标签取消死亡+全服广播"被撑死"
 * 也一并封死——女仆按正常死亡走）。
 * onMaidEat 保留（吃饭 +1 信任 / -1 恐惧不受影响）；记忆适配层读 EmotionData
 * 与 HungerManager 独立，不受影响。
 *
 * v1.5.252o：修复目标包名——爱憎分明 2.0.2 作者包从 tartaricacid 改为
 * JumDa5he，旧 targets 找不到类 → @Pseudo 静默跳过 → 开关从未生效（用户实测）。
 * 方法签名已用 javap 对账 2.0.2：onServerTick(TickEvent.ServerTickEvent) 与
 * onMaidDeath(LivingDeathEvent) 均存在且签名匹配。
 *
 * @Pseudo + 字符串类名：爱憎分明可选，未安装时静默跳过（heartfelt 同款模式）。
 */
@Pseudo
@Mixin(targets = "com.github.JumDa5he.callresponse.compat.hunger.HungerManager")
public abstract class LoveLoatheHungerGateMixin {
    private static boolean gated() {
        return com.maidsmart.config.MaidSmartConfig.MISC_LOVELOATHE_DISABLE_HUNGER.get();
    }

    /** 主开关：取消整个饥饿 tick（饿死/撑死/自动进食/速度惩罚/回血联动全禁） */
    @Inject(method = "onServerTick", at = @At("HEAD"), cancellable = true)
    private void maidsmart$gateHungerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event, CallbackInfo ci) {
        if (gated()) {
            ci.cancel();
        }
    }

    /** 防御：关掉"撑死"死亡取消广播（旧 OverfedDeath 标签不再拦正常死亡） */
    @Inject(method = "onMaidDeath", at = @At("HEAD"), cancellable = true)
    private void maidsmart$gateOverfedDeath(net.minecraftforge.event.entity.living.LivingDeathEvent event, CallbackInfo ci) {
        if (gated()) {
            ci.cancel();
        }
    }
}
