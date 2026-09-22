package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.box.CompressionBoxMaidInv;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;
import net.minecraftforge.items.wrapper.RangedWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;

/**
 * 「背包里的压缩盒 = 女仆背包的延伸」（v1.2.2 实测六百一十六；六百一十八补上
 * {@code getAvailableInv} / {@code getAvailableBackpackInv} 两条路）。
 *
 * ── 六百一十六只注入了 {@code getMaidInv()} ──
 * javap 实证全 TLM 只有 4 个类引用它（{@code IMaidBackpack} / {@code MaidWirelessIOEvent} /
 * {@code EntityMaid} 自己 / {@code MaidMainContainer$BackpackSlot}），而**本模组自己的
 * 取物/数物代码几乎全走它**（{@code tool/}、{@code task/}、{@code combat/}、{@code action/}、
 * {@code build/} 下几十处 {@code maid.getMaidInv()}）。一处注入 = 所有调用点
 * 自动把背包里每个压缩盒的 5 格当成背包尾部，不用去改 40 个文件。
 *
 * ── 六百一十八补的那两条：为什么用户说「放食物她不吃、放弹药/TNT 她认」──
 * TLM 有另一套访问入口：{@code getAvailableInv(handsFirst)} 与
 * {@code getAvailableBackpackInv()}——它们读的是 {@code maidInv} **字段**
 * （{@code RangedWrapper} 按背包等级截到 6/12/24/36）而不是 {@code getMaidInv()}，
 * 所以六百一十六那版它们看不见盒子。而**女仆自己吃饭**正好走这条路：
 * {@code MaidHealSelfTask.start} 就是拿 {@code getAvailableBackpackInv()} 逐格找
 * 「能吃的那个」（meal 系统：{@code IMaidMeal.canMaidEat}）；{@code MaidWorkMealTask} /
 * {@code MaidHomeMealTask}（干完活回家吃饭）也一样。而弹药/TNT 是我们自己的代码在
 * {@code getMaidInv()} 上找的 —— 所以「她只认弹药不认饭」，一字不差。
 * 顺带把 {@code getAvailableInv} 一起补上：TLM 拿它做挤奶/剪羊毛/喂动物/采蜜/插火把/
 * 射箭取箭（javap 扫出 30 来个调用点），补完之后盒子才算真的「背包的延伸」。
 *
 * ── 为什么是「重写那两个方法的构造」而不是「把返回值再套一层」──
 * 套一层（{@code new CombinedInvWrapper(原返回值, 盒子段)}）代码最少，但**返回值就不再是
 * TLM 的 {@code MaidInvWrapper} 了**——javap 实证 {@code ItemsUtil.findStackSlot} 里有
 * {@code instanceof MaidInvWrapper} 分支，那一支负责发 {@code MaidRequestItemEvent}
 * （TLM 留给其它模组的「女仆要东西」扩展点）：套层之后这个事件再也不会发，
 * 等于悄悄砍掉一条第三方集成。所以这里**照抄 TLM 那两个方法本身的构造**（就只有
 * 三行：截背包等级、两个 wrapper、handsFirst 决定顺序），只把「盒子那几格」作为
 * **优先级最低的成员接在最后**——类型还是 {@code MaidInvWrapper}，事件照发。
 * 一旦 TLM 改了那两个方法的写法，这里要跟着改（下面每处都标了出处）。
 *
 * ── 没有盒子时一行都不多做 ──
 * 两条注入都是「先问有没有盒子（{@code boxRange()} 返回 null 就 {@code return}，
 * 不 cancel）」——没有盒子时走的就是 TLM 原版实现，行为与旧版逐字一致。
 *
 * 【仍然没做的那一半，写清楚】还有极少数地方是直接遍历 {@code maidInv} 字段本身的
 * （例如 TLM 自己存档/同步的路径），那些看不见盒子。盒子里的东西**不会**因此丢
 * ——它们的真身在盒子物品的 NBT 里，只有「她看得见多少」受影响。
 */
@Mixin(value = EntityMaid.class, remap = false)
public abstract class MaidCompressionBoxMixin {

    /** TLM 真正的 36 格背包（子类视图要把它包在里面） */
    @Shadow
    private ItemStackHandler maidInv;

    /** 背包等级（决定她「可用」多少格：6/12/24/36）——出处 EntityMaid.getAvailableInv */
    @Shadow
    public abstract com.github.tartaricacid.touhoulittlemaid.api.backpack.IMaidBackpack getMaidBackpackType();

    /** 双手（主/副手）——出处 EntityMaid.getAvailableInv 的 handsFirst 分支 */
    @Shadow
    public abstract net.minecraftforge.items.wrapper.EntityHandsInvWrapper getHandsInvWrapper();

    /** 每个女仆一份视图（本身无状态，缓存只是为了别在热路径上反复 new） */
    @Unique
    private CompressionBoxMaidInv promaid$boxInv;

    @Inject(method = "getMaidInv", at = @At("HEAD"), cancellable = true)
    private void promaid$extendBackpackWithBoxes(CallbackInfoReturnable<ItemStackHandler> cir) {
        try {
            CompressionBoxMaidInv ext = promaid$view();
            if (ext == null) {
                return; // 开关关掉 → 原样返回，行为与旧版完全一致
            }
            // 背包里一个压缩盒都没有时，视图等价于原背包（多一层转发，行为不变）
            cir.setReturnValue(ext);
        } catch (Throwable ignored) {
            // 任何异常都退回原版返回值——背包访问是女仆每个 tick 都在走的路，
            // 这里绝不能把异常抛进她的 AI 调用链
        }
    }

    /** 出处 EntityMaid.getAvailableInv(boolean)：截到背包等级 + handsFirst 决定顺序 */
    @Inject(method = "getAvailableInv", at = @At("HEAD"), cancellable = true)
    private void promaid$extendAvailableInv(boolean handsFirst,
                                            CallbackInfoReturnable<CombinedInvWrapper> cir) {
        try {
            IItemHandlerModifiable boxes = promaid$boxes();
            if (boxes == null) {
                return; // 没有盒子：走 TLM 原版实现
            }
            IItemHandlerModifiable bag = new RangedWrapper(
                    promaid$view(), 0, getMaidBackpackType().getAvailableMaxContainerIndex());
            IItemHandlerModifiable hands = getHandsInvWrapper();
            IItemHandlerModifiable[] members = handsFirst
                    ? new IItemHandlerModifiable[]{hands, bag}
                    : new IItemHandlerModifiable[]{bag, hands};
            cir.setReturnValue(new com.github.tartaricacid.touhoulittlemaid.inventory.handler
                    .MaidInvWrapper((EntityMaid) (Object) this, promaid$append(members, boxes)));
        } catch (Throwable ignored) {
        }
    }

    /** 出处 EntityMaid.getAvailableBackpackInv()：只有背包（不带手）——女仆自己吃饭走这条 */
    @Inject(method = "getAvailableBackpackInv", at = @At("HEAD"), cancellable = true)
    private void promaid$extendAvailableBackpackInv(CallbackInfoReturnable<CombinedInvWrapper> cir) {
        try {
            IItemHandlerModifiable boxes = promaid$boxes();
            if (boxes == null) {
                return;
            }
            IItemHandlerModifiable bag = new RangedWrapper(
                    promaid$view(), 0, getMaidBackpackType().getAvailableMaxContainerIndex());
            cir.setReturnValue(new com.github.tartaricacid.touhoulittlemaid.inventory.handler
                    .MaidInvWrapper((EntityMaid) (Object) this,
                    promaid$append(new IItemHandlerModifiable[]{bag}, boxes)));
        } catch (Throwable ignored) {
        }
    }

    /** 把盒子段接在**最后**（优先级最低：先用自己的背包/手，再动盒子） */
    @Unique
    private static IItemHandlerModifiable[] promaid$append(IItemHandlerModifiable[] members,
                                                           IItemHandlerModifiable boxes) {
        IItemHandlerModifiable[] all = Arrays.copyOf(members, members.length + 1);
        all[members.length] = boxes;
        return all;
    }

    /** 扩展视图（总开关关掉时 null） */
    @Unique
    private CompressionBoxMaidInv promaid$view() {
        if (!com.maidsmart.config.MaidSmartConfig.COMPRESSION_BOX_MAID_EXTENSION.get()) {
            return null;
        }
        CompressionBoxMaidInv ext = this.promaid$boxInv;
        if (ext == null) {
            ext = new CompressionBoxMaidInv((EntityMaid) (Object) this, this.maidInv);
            this.promaid$boxInv = ext;
        }
        return ext;
    }

    /** 只含盒子那几格的段（背包里没有盒子 → null） */
    @Unique
    private IItemHandlerModifiable promaid$boxes() {
        CompressionBoxMaidInv view = promaid$view();
        return view == null ? null : view.boxRange();
    }
}
