package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.3.0 实测六百六十八（修 667 的启动崩溃）【武装拴绳】：拴绳挂载的玩家定位在女仆【下方】
 * hang 格（默认 2.6，配置面板可调），而不是原版的"骑在头顶"。
 *
 * ── 【实测六百七十一 / 六百七十二：定位改成"挑偏移 + 滑变"，并分「悬挂 / 并肩」两档】──
 * 玩家实测反馈："被绑定以后，玩家会在女仆的上下反复横跳"（㊁），以及"平时待命没有起飞的时候，
 * 直接跟女仆的建模完全重叠看起来真的太难绷了。应该要跟女仆拉开一定程度上的距离的"（㊁后半）。
 * 根因不是碰撞（javap 实证：原版 {@code Entity.push} 对"载具与其自身乘客"本来就互推豁免——
 * 1.20.1 走 m_20365_、1.21.1 走 isPassengerOfSameVehicle），而是旧版这里**二值回退且无插值**，
 * 而"回退"的那一头又恰好是 0（= 玩家站在她脚底，两个建模完全重叠）。
 * 现在统一由 {@code GunnerTetherManager.seatOffset} 给出偏移：下方有空间就吊在下方 hang 格、
 * 下方没空间就水平拉开并肩站，两档之间还有粘滞。见那个方法的说明。
 *
 * ── 【为什么是 {@code @Mixin(Entity.class)} 而不是 EntityMaid —— 667 让游戏开不起来的根因】──
 * Mixin 的 {@code @Inject} 只在【目标类自己声明】的方法里找注入点，**继承来的方法一律匹配不到**。
 * {@code positionRider} 只在 {@code net.minecraft.world.entity.Entity} 声明（javap 实证：
 * {@code EntityMaid extends TamableAnimal}，整条链上没有任何一处覆写它）。所以
 * {@code @Mixin(EntityMaid.class)} + {@code method="positionRider"} 的结局必然是启动期
 * {@code InvalidInjectionException}：
 * <pre>
 *   Mixin apply for mod promaid failed mixins.promaid.json:EntityMaidGunnerMixin
 *   @Inject ... could not find any targets matching 'positionRider' in
 *   com/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid. No refMap loaded.
 * </pre>
 * ——游戏连主菜单都进不去（玩家实测六百六十八 直接反馈的崩溃）。
 * 正解：注入到【声明它的那个类】Entity，再用 {@code instanceof EntityMaid} 把世上所有别的载具
 * 放行。同类前例：{@code EntitySetDeltaMovementMixin}、{@code EntityTickBlameMixin} 也是这么做的。
 *
 * ── 【名字口径】──
 * 本项目手工编译、**没有 refmap**（"No refMap loaded"），意味着 {@code method} 字符串按
 * **运行时名逐字匹配**：1.21.1 运行时是官方名（{@code positionRider}，javap 实证
 * client-1.21.1-*-srg.jar 里没有 m_19956_ 这种东西），1.20.1 运行时才是 SRG——所以两树各写
 * 各的，见 promaid_src 的同名文件。
 * 这里连**完整描述符**一起写上，避免 1.21.1 里 positionRider 有两个重载（1 参 / 2 参）时歧义。
 *
 * ── 【语义】──
 * 只有"拴绳挂载的那位玩家"（{@link com.maidsmart.combat.GunnerTetherManager#isGunner}：
 * 服务端看 LINKS / 客户端看 SYNCED_PAIRS）才会被改定位；其余乘客（船 / 矿车 / 别的模组）与
 * "别人在驾驶的载具"一律原版一字不动。回调仍是原版同款 MoveFunction（Entity::setPos），
 * 只是 Y 从"脚踩在她身上"换成"悬在她脚底下方 hang 格"。
 *
 * ── 【两侧都生效】──
 * positionRider 客户端/服务端都会调：服务端定权威位置，客户端同款算式保证本地预览一致
 * （挂载对由 S2C 包同步，见 GunnerTetherManager.SYNCED_PAIRS）。
 */
@Mixin(Entity.class)
public abstract class EntityGunnerHangMixin {

    @Inject(method = "positionRider(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity$MoveFunction;)V",
            at = @At("HEAD"), cancellable = true)
    private void maidsmart$hangGunnerBelow(Entity passenger, Entity.MoveFunction fn, CallbackInfo ci) {
        try {
            if (!(passenger instanceof Player)) {
                return;
            }
            // 目标类换成了 Entity（见类注释）：非女仆载具一律原版走完，绝不碰
            if (!((Object) this instanceof EntityMaid)) {
                return;
            }
            // 注意 mixin 类不继承 Entity，直接 this.getX() 编译不过——走强转（强转 Object 才合法）
            EntityMaid self = (EntityMaid) (Object) this;
            if (!com.maidsmart.combat.GunnerTetherManager.isGunner(self, passenger)) {
                return;
            }
            double hang = com.maidsmart.combat.GunnerTetherManager.hangOffset();
            // 【实测六百七十二：定位改成"挑偏移 + 滑变"，并且分「悬挂 / 并肩」两档】
            //   她在飞（下方有空间）→ 吊在她脚底下方 hang 格，偏移平滑滑变（地形起伏像船随浪）；
            //   她站在地上 / 贴着地形滑飞（下方一点空间都没有）→ **水平拉开**并肩站着：
            //   实测反馈㊁"平时待命没有起飞的时候，直接跟女仆的建模完全重叠看起来真的太难绷了"。
            // 两档的规则、粘滞、每一档为什么这么定，全在 GunnerTetherManager.seatOffset 的说明里。
            net.minecraft.world.phys.Vec3 off =
                    com.maidsmart.combat.GunnerTetherManager.seatOffset(self, passenger, hang);
            fn.accept(passenger, self.getX() + off.x, self.getY() + off.y, self.getZ() + off.z);
            ci.cancel();
        } catch (Throwable ignored) {
        }
    }

    // 【实测六百七十一 / 六百七十二】空间判定与整套定位算式都搬到了 GunnerTetherManager：
    // roomFor（有没有空间）、hangTarget（下方能吊多远）、seatOffset（这一拍该在哪，含滑变与
    // 悬挂/并肩两档）。留在这里就会变成第二份口径（本项目"口径只有一处"的铁律）。
}
