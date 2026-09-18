package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;

/**
 * v1.2.2 实测五百六十：**友军风免**——玩家与其他女仆不被"女仆放出来的风"震开。
 *
 * 【用户原话】"玩家和其他女仆免疫女仆释放的风暴/风弹效果，不会被震开。当前版本免疫伤害，
 * 但是会被震风。导致从高空攻击的时候会直接把主人也打到空中。"
 *
 * 【为什么"免疫伤害"了还会被震开】伤害那条路我们早就堵死了：{@link FriendlyFireGuard}
 * （本模组）与万法皆通自己的 {@code MaidSpellAllyEvents} 都在
 * LivingAttack/Hurt/Damage 三层取消"女仆→友军"的伤害。但**击退不是伤害事件**：
 *
 * - 原版爆炸（`Explosion.explode`，javap 实证）在实体循环里先把每个实体
 *   `hurt(伤害源, …)`（→ 被上面三层拦掉），随后**无条件**执行
 *   `entity.setDeltaMovement(entity.getDeltaMovement().add(击退向量))`（字节码偏移 918 起）——
 *   伤害结果为 false 也照推不误。这就是"掉的血没了，人还是飞了"的原因。
 *   铁魔法的火球（`MagicFireball`）正是**用女仆本人当爆炸来源**构造原版 Explosion
 *   （javap 实证：`new Explosion(level, getOwner(), …)` 后 `explode(); finalizeExplosion(Z)`），
 *   所以"女仆在高空打、火球在主人脚边炸 → 主人被顶上天"完全对得上。
 * - 铁魔法"呼啸之风"（`GustCollider.onHitEntity`）与新生魔艺的击退构件同理：直接给对方
 *   `setDeltaMovement(old.add(推力))`，一次伤害事件都不发（所以三层防护对它完全无效）。
 *
 * 【做法】不去逐个模组打补丁（那是无底洞，且要把第三方类名写死），而是抓住**所有外力位移
 * 的唯一出口**：`Entity.setDeltaMovement(Vec3)`（1.21.1 各重载最终都汇到这里）。
 * 唯一要解决的问题是**归因**——这一下是谁推的？答案用"当前正在 tick 的实体"回答：
 *
 * - `EntityTickBlameMixin` 在 `Entity.tick` 的 HEAD/RETURN 维护一个"当前 tick 实体"；
 *   法术/风弹/火球推人时，调用栈**一定**在那个效果实体的 tick 里（铁魔法是在自己的
 *   `tick` 里射线命中后推、火球是在自己的 tick 里爆炸、瞬发范围法术则是在**女仆本人**
 *   的 tick 里结算）；即使像 `GustCollider` 那样由法书在施法瞬间手动 `tick()` 一次，
 *   也照样落在这条链上（javap 实证 `GustSpell.onCast` 里 `addFreshEntity` + `tick()`）。
 * - 于是"推人者"= 那个实体本身是女仆，或它的主人是女仆（投射物/召唤物/载具：走
 *   `Projectile.getOwner` / `OwnableEntity.getOwner`）。爆炸还有一路独立归因：
 *   `Explosion.getDirectSourceEntity/getIndirectSourceEntity`（第三方模组的爆炸也照这条，
 *   不必知道是哪个模组）。
 *
 * 【判据只有三条】① 推人者是女仆方且受害者不是她本人；② 受害者是玩家或女仆，
 * 且与她是友军（**优先用万法皆通自己的盟友口径** `MaidSpellAllyResolver.areFriendly`，
 * 保证与它对伤害的判定一致；没装就走 {@link FriendlyFireGuard#isFriendly}）；
 * ③ 这一下是**明显的外力**（速度变化 ≥ {@link #MIN_IMPULSE}，正常走路/重力/挤开远小于它）。
 *
 * 【刻意不管的】女仆推自己（风弹自起跳、烟花推进、空袭的近身弹开）——受害者==推人者，
 * 第一版就排除，保证她自己的机动一字不改。
 *
 * 【只在服务端生效】客户端的速度是服务端同步下来的，本地再掺一脚只会和服务端打架；
 * 主人本地"被推一下"的问题由 {@code ExplosionWindGuardMixin} 在服务端把击退向量
 * 从发包数据里剔掉解决。
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = "promaid")
public final class FriendlyWindGuard {

    /**
     * 只拦"明显的外力位移"（格/tick）。参考量级：走路每 tick ≈ 0.2 以下、重力 0.08、
     * 实体互相挤开 ≤ 0.05；而风弹推力 = 法强×0.2（动辄 1~6）、爆炸击退 0.5~3。
     * 取 0.2 既不会拦到正常移动，也不会漏掉真正把人掀起来的那一下。
     */
    private static final double MIN_IMPULSE = 0.2;

    /** 当前 tick 的"最近一次 tick 的实体"（由 EntityTickBlameMixin 写入、服务器 tick 开始时清空） */
    private static Entity ticking;

    /** 日志限频：每个（女仆, 受害者）10 秒最多一条 */
    private static final java.util.Map<String, Long> LOG_AT = new java.util.HashMap<>();
    private static final long LOG_INTERVAL_MS = 10_000L;

    private FriendlyWindGuard() {
    }

    // ==================== tick 归因（两个 mixin 共用） ====================

    /**
     * Entity.tick 的 HEAD：把"当前 tick 实体"换成她。
     *
     * 【为什么是"粘住"而不是"HEAD 存入 / RETURN 还原"】javap 实证：投射物的命中判定
     * 在自己的 `tick()` 里**先调 `super.tick()`、回来之后**才做移动与命中
     * （铁魔法火球的爆炸、新生魔艺弹射物的效果都在这一段里）。若在 super 返回时就还原，
     * 真正推人的那一刻归因已经丢失。所以这里只在"下一个实体开始 tick"时被覆盖，
     * 跨 tick 的陈旧值由 {@link #resetTick()}（服务器 tick 开始）兜底清掉。
     */
    public static void beginTick(Entity self) {
        ticking = self;
    }

    /** 服务器 tick 开始：清掉上一 tick 的归因（防止"上个 tick 的女仆弹"误伤本 tick 的外力） */
    public static void resetTick() {
        ticking = null;
    }

    /**
     * 这个实体是不是"女仆方"——本身是女仆，或者它的主人是女仆（投射物/召唤物/载具）。
     * 取不到主人一律返回 null（= 不归因，绝不误伤）。
     */
    public static EntityMaid maidOf(Entity e) {
        if (e == null) {
            return null;
        }
        try {
            if (e instanceof EntityMaid m) {
                return m;
            }
            if (e instanceof Projectile p) {
                Entity owner = p.getOwner(); // 投射物的主人：火球/风弹/法术弹都在这条路上
                if (owner instanceof EntityMaid m) {
                    return m;
                }
            }
            if (e instanceof OwnableEntity ow) {
                LivingEntity owner = ow.getOwner();
                if (owner instanceof EntityMaid m) {
                    return m;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 爆炸归因：直系来源（女仆本人/她的火球）→ 间系来源（原版 getIndirectSourceEntity，含 TNT 的主人） */
    public static EntityMaid maidOfExplosion(Explosion ex) {
        if (ex == null) {
            return null;
        }
        try {
            EntityMaid m = maidOf(ex.getDirectSourceEntity());
            if (m != null) {
                return m;
            }
            return maidOf(ex.getIndirectSourceEntity());
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ==================== 两条调用路径 ====================

    /** 路径①：Entity.setDeltaMovement（风弹/呼啸之风/击退构件/任何直接改速度的法术） */
    public static boolean shouldBlockTick(Entity victim, Vec3 newVel) {
        Entity t = ticking;
        if (t == null || t == victim) {
            return false; // 受害者==当前 tick 实体：她自己的机动（烟花推进/风弹自起跳）
        }
        return shouldBlock(maidOf(t), victim, newVel);
    }

    /** 路径②：原版 Explosion.explode 的实体循环（伤害被拦、但击退无条件施加的那一处） */
    public static boolean shouldBlockExplosion(Explosion ex, Entity victim, Vec3 newVel) {
        return shouldBlock(maidOfExplosion(ex), victim, newVel);
    }

    /** 唯一的判据（两条路径共用；public 供爆炸的"发包击退向量"过滤复用） */
    public static boolean shouldBlock(EntityMaid maid, Entity victim, Vec3 newVel) {
        try {
            if (maid == null || victim == null || newVel == null || maid == victim) {
                return false;
            }
            if (!(victim instanceof Player || victim instanceof EntityMaid)) {
                return false; // 只保护玩家与女仆（怪物该被打飞就打飞）
            }
            if (victim.level().isClientSide()) {
                return false; // 只做服务端：客户端的速度由服务端同步下来，本地不掺和（避免与服务端打架）
            }
            if (!MaidSmartConfig.COMBAT_FRIENDLY_WIND_IMMUNE.get()) {
                return false;
            }
            double dv = newVel.distanceTo(victim.getDeltaMovement()); // |新速度 - 旧速度|
            if (dv < MIN_IMPULSE) {
                return false;
            }
            if (!areFriendly(maid, victim)) {
                return false;
            }
            log(maid, victim, dv);
            return true;
        } catch (Throwable ignored) {
            return false; // 任何意外都不拦（宁可漏一次，不可误伤正常位移）
        }
    }

    /** 友军口径：优先附属自己的（同主女仆/召唤物/同队全认），没装则用本模组既有口径 */
    private static boolean areFriendly(EntityMaid maid, Entity victim) {
        Boolean addon = MaidSpellCompat.addonAreFriendly(maid, victim);
        if (addon != null) {
            return addon;
        }
        return FriendlyFireGuard.isFriendly(maid, victim);
    }

    // ==================== 归因的跨 tick 护栏 ====================

    /** 每个服务器 tick 开始时清空归因（FriendlyFireGuard 同一套路的事件订阅者） */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Pre event) {
        resetTick();
    }

    private static void log(EntityMaid maid, Entity victim, double dv) {
        try {
            long now = System.currentTimeMillis();
            String key = maid.getUUID() + "/" + victim.getUUID();
            Long last = LOG_AT.get(key);
            if (last != null && now - last < LOG_INTERVAL_MS) {
                return;
            }
            if (LOG_AT.size() > 512) {
                LOG_AT.clear();
            }
            LOG_AT.put(key, now);
            org.slf4j.LoggerFactory.getLogger("promaid").info(
                    "friendly-wind-immune: maid={} victim={} dv={} · 女仆的法术/风弹不再震开主人与同主女仆",
                    com.maidsmart.tool.PromaidLog.nameOf(maid), victimName(victim), String.format("%.2f", dv));
        } catch (Throwable ignored) {
        }
    }

    private static String victimName(Entity victim) {
        try {
            if (victim instanceof EntityMaid m) {
                return com.maidsmart.tool.PromaidLog.nameOf(m);
            }
        } catch (Throwable ignored) {
        }
        return victim.getClass().getSimpleName();
    }
}
