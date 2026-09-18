package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.combat.FriendlyWindGuard;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;

/**
 * v1.2.2 实测五百六十：爆炸击退的友军豁免（**双保险 + 主机玩家的本地手感**）。
 *
 * 【为什么单靠 setDeltaMovement 那道闸不够】
 * 原版爆炸对玩家有**两条**施加通道（javap 实证 `Explosion.explode` 实体循环）：
 * ① 服务端 `entity.setDeltaMovement(旧速度 + 击退向量)`（偏移 918）；
 * ② 同一循环里把击退向量塞进 `hitPlayers`（`getHitPlayers()`，偏移 964~971），
 *    随后由 `ServerLevel` 打包成爆炸包发给客户端 —— **客户端拿着这个向量直接推自己的玩家**。
 * 所以只拦 ① 的话：主人的血不掉（本来就不掉）、服务器速度也没变，但本地照样被"推一下"，
 * 下一 tick 再被服务器拉回原地 —— 观感就是"人被震了一下又弹回去"。
 *
 * 两个注入各管一条：
 * - {@link #promaid$skipFriendlyKnockback}：① 的那一次 setDeltaMovement 直接不执行，
 *   归因走爆炸本身（`getDirectSourceEntity`：铁魔法火球就是**用女仆本人**当来源构造的
 *   原版 Explosion；间系来源覆盖 TNT 一类）。`require = 0`：这条指令在别的版本里
 *   万一不是唯一一处，只让它失效（还有 setDeltaMovement 那道总闸兜底），**绝不启动崩溃**。
 * - {@link #promaid$filterHitPlayers}：② 发包前把友军从"受击玩家"表里剔掉（返回**副本**，
 *   不改动原表，避免影响别的读表模组，如新生魔艺的 EffectExplosion）。
 *
 * 注：新生魔艺的 `ANExplosion` 自己覆写了 `explode`，走不到上面第 ① 条，但它没有覆写
 * `getHitPlayers` → 第 ② 条对它同样生效；它服务端那一下则由
 * {@code EntitySetDeltaMovementMixin} 按"正在 tick 的实体（法术投射物）"归因拦掉。
 *
 * 1.21.1 官方名：explode = Explosion.explode，getHitPlayers = getHitPlayers，
 * getDirectSourceEntity/getIndirectSourceEntity = 同名，setDeltaMovement = Entity.setDeltaMovement。
 */
@Mixin(Explosion.class)
public abstract class ExplosionWindGuardMixin {

    /** ① 服务端：不给友军加击退（伤害那条路本来就已被 FriendlyFireGuard 拦掉） */
    @Redirect(method = "explode",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"),
            require = 0)
    private void promaid$skipFriendlyKnockback(Entity entity, Vec3 vec) {
        try {
            if (FriendlyWindGuard.shouldBlockExplosion((Explosion) (Object) this, entity, vec)) {
                return; // 不 setDeltaMovement：击退不生效
            }
        } catch (Throwable ignored) {
        }
        entity.setDeltaMovement(vec);
    }

    /** ② 发包前：把友军从"受击玩家击退表"里剔掉（否则本地玩家仍会被推一下再被拉回） */
    @Inject(method = "getHitPlayers", at = @At("RETURN"), cancellable = true)
    private void promaid$filterHitPlayers(CallbackInfoReturnable<Map<Player, Vec3>> cir) {
        try {
            Map<Player, Vec3> hit = cir.getReturnValue();
            if (hit == null || hit.isEmpty()) {
                return;
            }
            EntityMaid maid = FriendlyWindGuard.maidOfExplosion((Explosion) (Object) this);
            if (maid == null) {
                return;
            }
            Map<Player, Vec3> kept = null;
            for (Map.Entry<Player, Vec3> e : hit.entrySet()) {
                if (FriendlyWindGuard.shouldBlock(maid, e.getKey(), e.getValue())) {
                    if (kept == null) {
                        kept = new HashMap<>(hit);
                    }
                    kept.remove(e.getKey());
                }
            }
            if (kept != null) {
                cir.setReturnValue(kept); // 副本，不动原表
            }
        } catch (Throwable ignored) {
        }
    }
}
