package com.maidsmart.tool;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.Entity;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * v1.2.2 实测六百【无主女仆全面降级】。
 *
 * ── 需求原文 ──
 * "某些整合包，对于无主的女仆会有一些额外的特殊效果。我们这个 mod 不能够阻碍他们。所以我需要做
 * 一个很大的降级处理。对于无主女仆，promaid 所做的一切改动全都不生效，保持为原版的状态，
 * promaid 完全不干预。"
 *
 * ── 什么叫"无主" ──
 * {@code EntityMaid} 是 {@code TamableAnimal}（反编译实证，两树同一份字节码）——**主人 UUID 就是
 * 认领与否的唯一凭据**：
 * <ul>
 *   <li>{@code getOwnerUUID() == null} → 野生 / 未认领的女仆 = <b>无主</b>：整合包爱怎么改就怎么改，
 *       promaid 一律不认她；</li>
 *   <li>{@code getOwnerUUID() != null} → 有主：照常全部生效。</li>
 * </ul>
 * <b>故意不用 {@code getOwner()}</b>（= TLM 的 {@code m_269323_}，只返回"在线的那个玩家实体"）：
 * 主人下线时它会变成 null，拿它当判据会把"主人离线但女仆照常干活（排班 / 建造 / 区块加载）"
 * 这些既有行为一起误杀。认领关系跟着 UUID 走、不跟着在线状态走，这才是这条降级的本意。
 *
 * ── 覆盖哪些面 ──
 * ① {@link com.maidsmart.mixin.BehaviorOwnerlessGateMixin}：**所有 com.maidsmart.* 的 AI 行为**
 *    （自保 / 落地水 / 战术 / 自动装备 / 贴身辅助 / 搭路 / 散步 / 施工 / 采矿 / 砍树 / 酿造 /
 *    烹饪 / 站桩气泡 / 床铺互通…）一律不允许起手——这是"不干预"里最大的一块；
 * ② 各 mixin / 事件处理器的入口闸：只改"原版/TLM 自身行为"的那些（危险避让、恐慌、横扫、
 *    反击伤害口径、农田踩踏、床铺互通、危险 buff、威胁驱动…）逐个加一行本类的判据；
 * ③ 动作表现（副手举东西）、日志、气泡一类纯表现：随各自调用方一起被 ① 掐掉。
 *
 * ── 为什么做成静态工具而不是开关 ──
 * 这不是玩家可选项，而是"无主女仆不属于本模组管辖范围"这条边界；做成配置反而会出现
 * "打开开关就能干预野生女仆"的怪状态。整合包作者什么都不用配。
 *
 * 全部方法都是**只读 + 异常兜底 false**：任何读不到的状态一律按"无主"处理（宁可少干预）。
 */
public final class MaidScope {

    private MaidScope() {
    }

    /**
     * 她有主人吗（= 已被认领）。null / 异常一律 false。
     *
     * 注意**不校验 {@code isAlive()}**：死亡处理（自动复活 / 墓碑 / 离开世界）也要能认得出她，
     * 需要"活着"的调用点自己再判 {@code m_6084_()}。
     */
    public static boolean owned(EntityMaid maid) {
        try {
            return maid != null && maid.m_21805_() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 同上，Entity 版（多数事件里拿到的是 Entity）。 */
    public static boolean owned(Entity entity) {
        return entity instanceof EntityMaid maid && owned(maid);
    }

    /* ==================== AI 行为总闸（BehaviorOwnerlessGateMixin 用） ==================== */

    /**
     * 这个行为的类是不是本模组的（{@code com.maidsmart.*}）。
     *
     * 用 {@link ClassValue} 缓存：这个判据跑在**每一个生物、每一个行为、每一 tick** 的
     * {@code Behavior.tryStart} 上，必须便宜到没有感觉得到——ClassValue 就是为这种
     * "按类缓存一个常量" 的场景设计的（一次哈希槽查找，没有字符串比较）。
     */
    private static final ClassValue<Boolean> PROMAID_CLASS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return type.getName().startsWith("com.maidsmart.");
        }
    };

    public static boolean promaidBehavior(Object behavior) {
        try {
            return behavior != null && Boolean.TRUE.equals(PROMAID_CLASS.get(behavior.getClass()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ==================== 一次性日志（便于实测验收） ==================== */

    /** 已经记过的女仆（上限 {@value #NOTE_CAP}，满了整表清空——只是日志去重，丢了无所谓） */
    private static final Set<UUID> NOTED = new HashSet<>();

    private static final int NOTE_CAP = 256;

    /**
     * 记一条"这个无主女仆被降级了"（同一只女仆本局只记一次）。
     *
     * 实测验收用：把女仆的认领撤掉（或召唤一只野生的），日志里搜「无主女仆」应当能看到这条，
     * 且此后她身上不再出现 promid 的任何行为日志 / 动作。
     */
    public static void noteDowngrade(EntityMaid maid) {
        try {
            if (maid == null) {
                return;
            }
            UUID id = maid.m_20148_();
            synchronized (NOTED) {
                if (!NOTED.add(id)) {
                    return;
                }
                if (NOTED.size() > NOTE_CAP) {
                    NOTED.clear();
                    NOTED.add(id);
                }
            }
            PromaidLog.log("降级", PromaidLog.nameOf(maid)
                    + " 是无主女仆（没有主人）→ promid 的全部改动对她不生效，保持 TLM 原版行为");
        } catch (Throwable ignored) {
        }
    }

    /** 供别的入口闸复用：无主 → 记一条并返回 true（调用方直接 return） */
    public static boolean downgrade(EntityMaid maid) {
        if (owned(maid)) {
            return false;
        }
        noteDowngrade(maid);
        return true;
    }
}
