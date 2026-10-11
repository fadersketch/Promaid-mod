package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.build.MaidBuildBehavior;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v1.5.393 实测八百零三：建造期间把 home 的【代码】整个作废（不锁她的 home）。
 *
 * <p>【口径（
 *
 * （玩家手动开的、排班表开的都保留原样），但在建造期间，一切"home 才生效"的行为
 * 一律不生效——不让它参与进来跟建造打架。
 *
 * <p>【为什么必须在字节码层做】TLM 的 home 不是独立机制，而是把原版 {@code Mob} 的限制圈
 * API 整个劫持了（{@code EntityMaid.hasRestriction()} 就是 {@code isHomeModeEnable()}）。
 * 而"按限制圈干活"的判定全都读 {@code isWithinRestriction(BlockPos)}——它 home 开时按
 * 【圈心距离 &lt; 半径²】算，圈心又是建造从不配置的排班锚点（原点/旧工位）。于是她一旦是
 * home，{@code SchedulePos.tick} 的"出圈就传送回工位"（每 2 秒）、
 * {@code MaidFollowOwnerTask} 里 {@code teleportToOwner} 的"跟随半径 + 4 判据"、
 * {@code MaidAwaitTask}（TLM 挂在 RandomStroll 上的空闲等待）等全部按错误的距离判据开火。
 *
 * <p>【为什么注入这里、注入什么】{@code isWithinRestriction(BlockPos)} 是所有"限制圈判定"
 * 的唯一咽喉，上面那三条链路全部经过它（javap 实证）。建造/指标石期间的女仆直接返回 true
 * （= "在圈内"）——等价于"没有限制圈"，正是 home 关闭时原版自己的语义，于是传送/走回/等待
 * 逻辑自然全部落空，而<b>不需要碰她的 home 标志</b>。
 *
 * <p>【边界】只对"建造任务"生效（{@code BlueprintBuildExecutor.isBuildingTask} +
 * 指标石临时搭建），其余一律原样走 super；总开关 build.noForceHome，关掉即整体回退。
 * {@code restrictTo} 不去改——圈心保持玩家/排班设的原值，我们没有篡改存储，
 * 建造一结束她的 home 与锚点完好如初。
 */
@Mixin(EntityMaid.class)
public abstract class BuildHomeInertMixin {

    /**
     * v1.5.394 实测八百零五【启动崩溃修复】：这里**必须写全描述符**。
     *
     * <p>TLM 的 {@code EntityMaid} 声明了**两个**同名重载——{@code isWithinRestriction()} 与
     * {@code isWithinRestriction(BlockPos)}（javap 实证；无参那个只是转发到带参那个）。
     * 只写 {@code method = "isWithinRestriction"} 时 Mixin 会解析到**无参**那个，于是
     * 处理函数的 {@code (BlockPos, CallbackInfoReturnable)} 与目标 {@code ()Z} 对不上 →
     * 启动期 {@code InvalidInjectionException} → Bootstrap 直接崩（实测日志实证）。
     *
     * <p>javac 查不出这个错（它只当注解是个字符串），所以这是"只有真跑起来才会发现"的一类炸弹。
     * 1.20.1 树没这问题：那边注解用的是 SRG 名 {@code m_21444_}，而 SRG 名**按描述符生成**
     * （无参 = {@code m_21533_}、带参 = {@code m_21444_}），天然不歧义。
     */
    @Inject(method = "isWithinRestriction(Lnet/minecraft/core/BlockPos;)Z",
            at = @At("HEAD"), cancellable = true)
    private void maidsmart$homeInertWhileBuilding(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        try {
            EntityMaid maid = (EntityMaid) (Object) this;
            if (MaidBuildBehavior.homeInert(maid)) {
                cir.setReturnValue(true); // 视为"在圈内"= 无圈：出圈传送/走回/等圈逻辑全部落空
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BuildHomeInertMixin", ignored);
        }
    }

    /**
     * 同一口径补一道"别拿旧锚点当搜索原点"：{@code getBrainSearchPos()} 在 home 时返回
     * 限制圈心（旧工位/原点），建造选点会按那个错位置找活。建造期间让它退回"她自己所在格"。
     */
    @Inject(method = "getBrainSearchPos", at = @At("HEAD"), cancellable = true)
    private void maidsmart$searchFromSelfWhileBuilding(CallbackInfoReturnable<BlockPos> cir) {
        try {
            EntityMaid maid = (EntityMaid) (Object) this;
            if (MaidBuildBehavior.homeInert(maid)) {
                cir.setReturnValue(maid.blockPosition());
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BuildHomeInertMixin", ignored);
        }
    }
}
