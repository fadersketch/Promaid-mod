package com.maidsmart.goety;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.tool.PromaidLog;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 实测 G-4【自动触发：她自己决定要不要用 Goety 飞】。
 *
 * <h2>这一层管什么、不管什么</h2>
 * <ul>
 *   <li><b>管</b>：**跟主人跨地形**——主人拉开到 {@value #TAKEOFF_DIST} 格以上，她就起飞追过去，
 *       追到 {@value #LAND_DIST} 格以内落地、交还给 TLM 原本的跟随（滞回，不会起起落落）。</li>
 *   <li><b>不管自动接战</b>：她的近战/远程走位是 TLM 的任务在驱动，我们若在普通战斗里接管移动，
 *       等于把她的近战行为**掐掉**（我们的推进会让走路目标失效）——那是上游架构的事，
 *       所以战斗档只保留命令入口 {@code /maid_smart goety_combat}。</li>
 *   <li><b>让位</b>：只要她已经在用别的飞行方式（空袭飞行任务 / 正在滑翔 / 仿创造飞行接管中 /
 *       上游的飞行跟随在带她飞 / 骑在别的实体上），我们一律退出，绝不抢。</li>
 * </ul>
 *
 * <h2>为什么默认关</h2>
 * 这是"她会自己飞起来"的行为——按本工程一贯口径（仿创造飞行的总开关也是默认关），
 * 默认关、由玩家用 {@code /maid_smart goety_auto on} 开，开完存在 her persistentData 里（随魂符/存档走）。
 *
 * <h2>门槛（玩家问过：飞行聚晶是前期还是后期物品）</h2>
 * <b>后期</b>：飞行聚晶是仪式产物，材料是**鞘翅 ×1 + 龙息 ×2 + 潜影壳 ×2 + 幻翼膜 ×1**
 * —— 与鞘翅同级，而且**会把那张鞘翅吃掉**。它换来的东西是"**不需要烟花、不吃耐久**的持续飞行"，
 * 所以它是鞘翅的**平级替代**，不是"前期飞行手段"。
 * 真正前期/中期的是**发射聚晶**：空聚晶（紫水晶/图腾）→ 风之核（羽毛/荧石/音符盒/甘蔗/矮草）
 * → 发射聚晶（+ 烟花 ×3 + 幻翼膜 ×2，唯一的门槛是幻翼膜）。那是
 * {@link MaidGoetyFlight#boost} 那一档（一次性冲量，约 12 格/次）。
 */
public final class MaidGoetyAuto {

    /** persistentData 键（随实体存盘，跟魂符一起走）。 */
    private static final String TAG = "maid_smart_goety_auto";

    /** 起飞阈值（格）：主人拉开这么远就飞过去追。 */
    private static final double TAKEOFF_DIST = 16.0;
    /** 落地阈值（格）：追到这么近就落地交还给 TLM 的跟随（与起飞阈值形成滞回）。 */
    private static final double LAND_DIST = 6.0;
    /** 决策间隔（tick）：决策不必每 tick 做（任务本身的推进仍是每 tick）。 */
    private static final int CHECK_EVERY = 10;

    private static final Map<UUID, Integer> COUNTER = new HashMap<>();
    /** 【实测七百四十五·点1】客户端缓存（服务端 S2C 广播写入）——界面/快捷键显示用（persistentData 不过网）。 */
    private static final Map<String, Boolean> CLIENT_STATE = new HashMap<>();
    private static boolean hooked;

    private MaidGoetyAuto() {
    }

    public static void ensureHooked() {
        if (hooked) {
            return;
        }
        hooked = true;
        NeoForge.EVENT_BUS.register(new MaidGoetyAuto());
    }

    public static boolean isAuto(EntityMaid maid) {
        try {
            return maid != null && ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid)
                    .getPersistentData().getBoolean(TAG);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setAuto(EntityMaid maid, boolean on) {
        try {
            ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid)
                    .getPersistentData().putBoolean(TAG, on);
            PromaidLog.log("Goety自动", maid.getName().getString() + " 自动推进 = " + on);
        } catch (Throwable ignored) {
        }
        if (!on) {
            MaidGoetyFlight.stop(maid, "自动已关");
        }
    }

    /* ---------------- 【实测七百四十五·点1】非 OP 路径：客户端缓存 ---------------- */

    /** 客户端收到 S2C 状态包时写入（仅客户端）。 */
    public static void pushClientState(String maidUuid, boolean value) {
        if (maidUuid != null) {
            CLIENT_STATE.put(maidUuid, value);
        }
    }

    /** 客户端缓存的自动档状态（null = 未知）。 */
    public static Boolean cachedClient(String maidUuid) {
        return CLIENT_STATE.get(maidUuid);
    }

    /** 让位原因；null = 可以接管。 */
    public static String yieldReason(EntityMaid maid) {
        try {
            if (maid.isPassenger()) {
                return "她骑在别的实体上";
            }
            if (com.maidsmart.combat.MaidFlightKit.isGliding(maid)) {
                return "她正在滑翔（鞘翅在飞）";
            }
            if (com.maidsmart.flight.MaidFreeFlightController.isControlling(maid)) {
                return "仿创造飞行正在带她飞";
            }
            if (com.maidsmart.combat.MaidFlightFollowBehavior.isFollowing(maid)) {
                return "上游的飞行跟随正在带她飞";
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 女仆当前的攻击目标（brain 记忆优先，退回 Mob.getTarget）。 */
    public static LivingEntity attackTarget(EntityMaid maid) {
        try {
            java.util.Optional<LivingEntity> mem = maid.getBrain()
                    .getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET);
            if (mem != null && mem.isPresent()) {
                return mem.get();
            }
        } catch (Throwable ignored) {
        }
        return maid.getTarget();
    }

    @SubscribeEvent
    public void onMaidTick(MaidTickEvent event) {
        try {
            tick(event.getMaid());
        } catch (Throwable ignored) {
        }
    }

    private static void tick(EntityMaid maid) {
        if (maid == null || maid.level().isClientSide() || !isAuto(maid)) {
            return;
        }
        int n = COUNTER.merge(maid.getUUID(), 1, Integer::sum);
        if (n % CHECK_EVERY != 0) {
            return;
        }
        // 资格：没装 Goety / 身上没有飞行聚晶 → 收手（保真：没有道具就不飞）
        // 【只碰自己的任务】玩家命令下发的任务优先级最高——实测踩过：少了这一条，
        // 自动层会在"她暂时没有主人"时把玩家刚下的 goety_fly 一并掐掉。
        boolean ours = MaidGoetyFlight.isAutoTask(maid);
        if (!MaidGoetyCompat.available()
                || !MaidGoetyCompat.hasFocus(maid, MaidGoetyCompat.FOCUS_FLYING)) {
            if (ours) {
                MaidGoetyFlight.stop(maid, "没有飞行聚晶了");
            }
            return;
        }
        String why = yieldReason(maid);
        if (why != null) {
            if (ours) {
                MaidGoetyFlight.stop(maid, "让位：" + why);
            }
            return;
        }
        // 【G-5 需求方的主意】远程空袭打单一目标时本来就是"盘旋"，所以飞行聚晶在这一档同样有位置：
        // 她若处在空袭/飞行任务里、**却没有在滑翔**（= 没有鞘翅或没起飞），但身上有飞行聚晶，
        // 那就由我们接管移动，按上游的接敌机动盘旋——这正是"没有鞘翅的空袭能力"。
        // 反之只要她真在滑翔（鞘翅在飞），上面已经让位了，绝不抢。
        if (com.maidsmart.combat.MaidFlightKit.isFlightTask(maid)) {
            LivingEntity t = attackTarget(maid);
            if (t != null && t.isAlive()) {
                if (!ours) {
                    String bad = MaidGoetyFlight.combat(maid, t, true);
                    if (bad == null) {
                        PromaidLog.log("Goety自动", maid.getName().getString()
                                + " 飞行任务没在滑翔 → 用飞行聚晶接管盘旋");
                    }
                }
                return;
            }
            if (ours) {
                MaidGoetyFlight.stop(maid, "飞行任务但没有目标");
            }
            return;
        }

        LivingEntity owner = null;
        try {
            owner = maid.getOwner();
        } catch (Throwable ignored) {
        }
        if (owner == null || !owner.isAlive()) {
            if (ours) {
                MaidGoetyFlight.stop(maid, "没有主人");
            }
            return;
        }
        // 主人正在滑翔/飞行时不追（追不上也没意义）——他只可能在地面/建筑上，才值得飞过去
        double d = maid.position().distanceTo(owner.position());
        if (d > TAKEOFF_DIST) {
            if (!ours) {
                String bad = MaidGoetyFlight.flyTo(maid, owner.position(), true);
                if (bad == null) {
                    PromaidLog.log("Goety自动", maid.getName().getString() + " 主人拉开 "
                            + String.format(java.util.Locale.ROOT, "%.1f", d) + " 格 → 起飞追");
                }
            } else if (n % 40 == 0) {
                // 追的过程中主人还在动：每 2 秒刷新一次目标点（否则她追的是几秒前的旧位置）
                MaidGoetyFlight.flyTo(maid, owner.position(), true);
            }
        } else if (d < LAND_DIST && ours) {
            MaidGoetyFlight.stop(maid, "已贴近主人，交还跟随");
        }
    }
}
