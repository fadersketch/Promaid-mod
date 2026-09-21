package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.box.CompressionBoxMaidInv;
import net.minecraftforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「背包里的压缩盒 = 女仆背包的延伸」（v1.2.2 实测六百一十六）。
 *
 * 注入点只挑了 TLM 的 {@code getMaidInv()} 一个方法：javap 实证全 TLM 只有 4 个类
 * 引用它（{@code IMaidBackpack} / {@code MaidWirelessIOEvent} / {@code EntityMaid}
 * 自己 / {@code MaidMainContainer$BackpackSlot}），而**本模组自己的取物/数物代码
 * 几乎全走它**（{@code tool/}、{@code task/}、{@code combat/}、{@code action/}、
 * {@code build/} 下几十处 {@code maid.getMaidInv()}）。一处注入 = 所有调用点
 * 自动把背包里每个压缩盒的 5 格当成背包尾部，不用去改 40 个文件。
 *
 * 为什么用「子类替换返回值」而不是 `@Redirect` 字段读：{@code getMaidInv} 的返回
 * 类型就是具体的 {@code ItemStackHandler}，写一个子类即可（工具、物品栏包装器、
 * {@code ItemHandlerHelper} 全部照常工作）。
 *
 * 【没做的那一半，写清楚】TLM 的 {@code getAvailableInv(...)} 走的是**字段**
 * {@code maidInv}（不是本方法），那条路按背包等级把槽位截断到 6/12/24/36
 * （{@code RangedWrapper}）。要让它也带上盒子格，就得整段替换那个方法并自己拼
 * {@code RangedWrapper}+{@code MaidInvWrapper}——风险（背包等级语义被改坏）明显
 * 大于收益（只是「播种时数种子」这类少数路径），所以这一批**只做 getMaidInv**：
 * 她**从盒子里拿东西**这条主线是通的；少数走 getAvailableInv 的路径看不见盒子。
 */
@Mixin(value = EntityMaid.class, remap = false)
public abstract class MaidCompressionBoxMixin {

    /** TLM 真正的 36 格背包（子类视图要把它包在里面） */
    @Shadow
    private ItemStackHandler maidInv;

    /** 每个女仆一份视图（本身无状态，缓存只是为了别在热路径上反复 new） */
    @Unique
    private CompressionBoxMaidInv promaid$boxInv;

    @Inject(method = "getMaidInv", at = @At("HEAD"), cancellable = true)
    private void promaid$extendBackpackWithBoxes(CallbackInfoReturnable<ItemStackHandler> cir) {
        try {
            if (!com.maidsmart.config.MaidSmartConfig.COMPRESSION_BOX_MAID_EXTENSION.get()) {
                return; // 开关关掉 → 原样返回，行为与旧版完全一致
            }
            CompressionBoxMaidInv ext = this.promaid$boxInv;
            if (ext == null) {
                ext = new CompressionBoxMaidInv((EntityMaid) (Object) this, this.maidInv);
                this.promaid$boxInv = ext;
            }
            // 背包里一个压缩盒都没有时，视图等价于原背包（多一层转发，行为不变）
            cir.setReturnValue(ext);
        } catch (Throwable ignored) {
            // 任何异常都退回原版返回值——背包访问是女仆每个 tick 都在走的路，
            // 这里绝不能把异常抛进她的 AI 调用链
        }
    }
}
