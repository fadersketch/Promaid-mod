package com.maidsmart.dialogue;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 感知变化检测（v1.5.95，借鉴 maidsoulcore PerceptionEventDetector）。
 *
 * 每 20 tick（1 秒）对主人周围女仆采集"世界快照"，对比上一快照检测【变化】，
 * 变化以【气泡】即时播报（零 LLM token 成本，规则直判）：
 * - 敌对生物出现 / 接近 / 消失（"有僵尸靠近了！"）
 * - 主人受伤 / 主人血量低（"主人受伤了！"）
 * - 主人开始看向女仆 / 不再看（"主人一直在看我…"）
 * - 天气变化（"下雨了"）
 *
 * 与 ProactiveDialogueManager 的区别：那是 LLM 主动对话（花 token、4 分钟冷却）；
 * 这是【纯规则气泡】（不花 token、1 秒级响应），覆盖"危险/关心"类即时感知。
 * 冷却：每只女仆同类事件 30 秒限频（防刷屏）。
 */
public class PerceptionManager {
    /** v1.5.102：以下数值均从配置面板读取（perception 段，玩家可调） */

    /** 上一快照（女仆 UUID → 快照事实） */
    private final Map<UUID, Snapshot> lastSnapshots = new HashMap<>();
    /**
     * v1.5.193：敌袭判定缓存（女仆 UUID → {判定, 游戏tick}）——
     * dangerActive 被气泡镜像/主动对话/自主决策高频调用，0.5 秒 TTL 内不重复
     * 12 格实体扫描。与感知总开关无关（关掉感知播报，敌袭冻结仍生效）。
     * 实测七百九十五：改用 ConcurrentHashMap——本表是 static，且 dangerActive 被
     * 气泡镜像（ChatBubbleLimitMixin）/ 主动对话 / 自主决策多路调用；与本子系统其余
     * 状态表口径一致，避免跨线程写坏普通 HashMap（旧版是 HashMap）。
     */
    private static final Map<UUID, long[]> DANGER_CACHE = new ConcurrentHashMap<>();
    /** 敌袭判定缓存 TTL（tick，10 = 0.5 秒） */
    private static final int DANGER_TTL_TICKS = 10;

    /** 审计M4修复（v1.5.383）：女仆实体卸载时清理其危险缓存（防长会话泄漏） */
    public static void forget(UUID maidUuid) {
        DANGER_CACHE.remove(maidUuid);
    }
    /**
     * v1.5.99b：同类事件上次播报时间【全局限频】（事件名 → tick）——
     * 旧版按女仆各自计：17 只女仆同秒各报一次"天气变了"→ 刷屏 17 条。
     * 现在同一事件全服 30 秒只报一次（由触发的那只女仆代表播报）。
     */
    private final Map<String, Long> eventCooldowns = new HashMap<>();
    /**
     * v1.5.98c：主人注视滞回状态（女仆 UUID → 当前判定状态）——用双阈值消除
     * "看/不看"在临界角抖动导致的反复触发；持续注视计数（tick）≥3 秒才报一次
     * "主人一直在看我"，且不再报"主人不看我了"（不看是常态，无信息量）。
     */
    private final Map<UUID, Boolean> lookingState = new HashMap<>();
    private final Map<UUID, Integer> lookingTicks = new HashMap<>();
    /**
     * 实测八百二十五：「主人一直在看着我…」这条播报带语音（owner_looking.ogg），
     * 玩家要求【每只女仆】各自 1800 秒（30 分钟）最多触发一次——独立于本类其余
     * 事件的 30 秒同类全局限频。map：女仆 UUID → 上次播报的游戏 tick。
     */
    private final Map<UUID, Long> lookCooldowns = new HashMap<>();
    /**
     * 实测九百九十一【敌袭语音改口径】：每只女仆"接敌中"的上一帧状态（边沿检测用）——
     * 口径与链路走位独占**同一条腿**：她自己有活着的攻击目标（attacking desire）才算接敌。
     */
    private final Map<UUID, Boolean> engageState = new HashMap<>();
    /** 接敌开始的 tick（"清掉了"这条要求这一仗至少跨过一次扫描——见 scan 里的说明） */
    private final Map<UUID, Long> engageSince = new HashMap<>();
    /** "清掉了"要求接敌至少持续这么多 tick（默认 20 = 一次感知扫描周期）——防目标一闪一没刷屏 */
    private static final int CLEAR_MIN_ENGAGE_TICKS = 20;
    /** 她最近一个真在打的目标（"清掉了"要核对它死没死——活着跟丢不算清掉，实测九百九十六） */
    private final Map<UUID, net.minecraft.world.entity.LivingEntity> engageLast = new HashMap<>();
    /** "清掉了"确认窗（tick）：目标死了也再等 2 秒、没重新接敌才播 */
    private static final int CLEAR_CONFIRM_TICKS = 40;
    /** "清掉了"挂起超时（tick）：10 秒内没等到"附近真没威胁"就静默放弃 */
    private static final int CLEAR_PENDING_TIMEOUT = 200;
    /** "清掉了"的挂起表（女仆 UUID → 目标刚死的那一刻）——两重确认见 scan */
    private final Map<UUID, Long> clearPending = new HashMap<>();
    /** 主人持续注视阈值（秒）与滞回角度均从配置面板读取 */

    /** 快照：当前可感知的世界事实 */
    private static final class Snapshot {
        int hostiles;              // 附近敌对数量
        double nearestHostileDist; // 最近敌对距离（-1 = 无）
        double ownerHealth;        // 主人血量（-1 = 不在场）
        boolean ownerLooking;      // 主人是否看向女仆
        String weather;            // 天气
        String timePhase;          // 时间阶段

        Snapshot(int hostiles, double nearestHostileDist, double ownerHealth,
                 boolean ownerLooking, String weather, String timePhase) {
            this.hostiles = hostiles;
            this.nearestHostileDist = nearestHostileDist;
            this.ownerHealth = ownerHealth;
            this.ownerLooking = ownerLooking;
            this.weather = weather;
            this.timePhase = timePhase;
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // v1.5.96：感知总开关（配置面板 perception.enable）
        if (!com.maidsmart.config.MaidSmartConfig.PERCEPTION_ENABLE.get()) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        long tick = server.m_129921_();
        if (tick % com.maidsmart.config.MaidSmartConfig.PERCEPTION_SCAN_INTERVAL.get() != 0) {
            return;
        }
        for (ServerLevel level : server.m_129785_()) {
            for (ServerPlayer player : level.m_6907_()) {
                // 只扫玩家周围 24 格的女仆（感知范围；聊天距离限制下远处女仆无感知意义）
                level.m_6443_(com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid.class,
                        player.m_20191_().m_82400_(24.0), e -> e.m_6084_()).forEach(maid -> {
                    if (!maid.m_21824_()) {
                        return;
                    }
                    LivingEntity owner = maid.m_269323_();
                    if (!(owner instanceof ServerPlayer) || owner != player) {
                        return;
                    }
                    this.scan(level, maid, player, tick);
                });
            }
        }
        // 清理离开范围的女仆快照（防内存泄漏；v1.5.98c 含注视状态；实测九百九十一含接敌状态）
        this.lastSnapshots.keySet().removeIf(id -> !this.isAnyMaidNearby(server, id));
        this.lookingState.keySet().removeIf(id -> !this.lastSnapshots.containsKey(id));
        this.lookingTicks.keySet().removeIf(id -> !this.lastSnapshots.containsKey(id));
        this.lookCooldowns.keySet().removeIf(id -> !this.lastSnapshots.containsKey(id));
        this.engageState.keySet().removeIf(id -> !this.lastSnapshots.containsKey(id));
        this.engageSince.keySet().removeIf(id -> !this.lastSnapshots.containsKey(id));
    }

    private boolean isAnyMaidNearby(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.m_129785_()) {
            for (ServerPlayer player : level.m_6907_()) {
                for (EntityMaid m : level.m_45976_(EntityMaid.class, player.m_20191_().m_82400_(24.0))) {
                    if (m.m_20148_().equals(id)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 统一威胁判定——候选生物是【敌对生物】或【对女仆/主人携带仇恨】（{@code getTarget} == 女仆/主人，
     * 覆盖中立被激怒反咬、主人被攻击的生物）；{@code Monster} 兜底：即使暂未锁定目标，敌对生物靠近也算。
     * {@code getTarget} 判定走【普适通道】——不限定 {@code NeutralMob} 实现类（模组魔改的"被动变中立"
     * 生物不实现 NeutralMob 是常态），任意 Mob 只要锁定主人/女仆就是敌对威胁。女仆本身永远不算。
     */
    public static boolean isThreat(LivingEntity candidate, EntityMaid maid) {
        try {
            if (candidate == null || !candidate.m_6084_() || candidate == maid) {
                return false;
            }
            if (candidate instanceof Monster) {
                return true;
            }
            if (candidate instanceof EntityMaid) {
                return false; // 女仆永远不算威胁（女仆之间不打）
            }
            if (candidate instanceof Mob mob) {
                LivingEntity t = mob.m_5448_(); // getTarget（已实证 EntityMaid 覆写即读 brain）
                if (t == null) {
                    return false;
                }
                LivingEntity owner = maid.m_269323_();
                return t == maid || (owner != null && t == owner);
            }
            return false;
        } catch (Exception e) {
             com.maidsmart.diag.Diag.swallow("PerceptionManager", e); return false;
        }
    }

    /**
     * v1.5.193：敌袭状态判定——女仆周围 12 格内存在威胁生物（v1.5.195：敌对生物
     * 或对女仆/主人携带仇恨的中立生物）。带 0.5 秒 TTL 缓存（高频调用不反复扫描）；
     * 独立于感知总开关；非服务端/异常返回 false（冻结失败只不冻，不影响游戏）。
     */
    public static boolean dangerActive(EntityMaid maid) {
        try {
            if (maid == null || maid.m_6084_() == false || maid.m_9236_().m_5776_()) {
                return false;
            }
            UUID id = maid.m_20148_();
            long nowTick = maid.m_9236_().m_46467_();
            long[] cached = DANGER_CACHE.get(id);
            if (cached != null && nowTick - cached[1] < DANGER_TTL_TICKS) {
                return cached[0] == 1L;
            }
            boolean active = !maid.m_9236_().m_6443_(Mob.class,
                    maid.m_20191_().m_82400_(12.0),
                    e -> e instanceof LivingEntity && isThreat((LivingEntity) e, maid)).isEmpty();
            DANGER_CACHE.put(id, new long[]{active ? 1L : 0L, nowTick});
            if (DANGER_CACHE.size() > 200) {
                DANGER_CACHE.keySet().removeIf(u -> {
                    long[] c = DANGER_CACHE.get(u);
                    return c != null && nowTick - c[1] >= DANGER_TTL_TICKS;
                });
            }
            return active;
        } catch (Exception e) {
             com.maidsmart.diag.Diag.swallow("PerceptionManager", e); return false;
        }
    }

    /** 采集快照 + 对比上一快照 → 事件播报 */
    private void scan(ServerLevel level, EntityMaid maid, ServerPlayer player, long tick) {
        // 采集（v1.5.195：威胁 = 敌对生物 + 对女仆/主人带仇恨的中立生物）
        int hostiles = 0;
        double nearest = -1.0;
        for (Mob m : level.m_6443_(Mob.class, maid.m_20191_().m_82400_(12.0),
                e -> e instanceof LivingEntity && isThreat((LivingEntity) e, maid))) {
            hostiles++;
            // v1.1.0 修复：m_20238_(Vec3) 是【平方距离】（distanceToSqr，字节码 dx²+dy²+dz²
            // 无 sqrt）——旧版直接当"格"显示，扫描盒 ±12 格时最多报 432 格（反馈：
            // "很容易检索到几百格之外的敌人"）。改用 m_20270_(Entity)（distanceTo，带 sqrt）。
            double d = maid.m_20270_(m);
            if (nearest < 0 || d < nearest) {
                nearest = d;
            }
        }
        double ownerHealth = player.m_21223_() / Math.max(1.0f, player.m_21233_()) * 100.0;
        boolean ownerLooking = isLookingAt(maid, player);
        // v1.5.103：修天气判定——旧版 `m_46758_ ? thunder : m_46749_ ? rain : clear` 是错的：
        // m_46758_ = isRainingAt（雨/雷雨都 true），m_46749_ = isLoaded（女仆所在区块恒已加载）
        // → 非雷雨时 weather 恒为 "rain"，"clear" 永不出现。正确区分：m_46470_ = isThundering（无参）。
        boolean thundering = level.m_46470_();
        boolean raining = level.m_46758_(maid.m_20183_());
        String weather = thundering ? "thunder" : (raining ? "rain" : "clear");
        String timePhase = timePhase(level);
        Snapshot now = new Snapshot(hostiles, nearest, ownerHealth, ownerLooking, weather, timePhase);

        Snapshot prev = this.lastSnapshots.get(maid.m_20148_());
        this.lastSnapshots.put(maid.m_20148_(), now);
        if (prev == null) {
            return; // 首次快照，不对比
        }
        // 对比检测事件（v1.5.96：各类型独立开关）
        List<String[]> events = new ArrayList<>(); // {事件类型, 文案}
        boolean hostileOn = com.maidsmart.config.MaidSmartConfig.PERCEPTION_HOSTILE.get();
        boolean ownerOn = com.maidsmart.config.MaidSmartConfig.PERCEPTION_OWNER.get();
        boolean weatherOn = com.maidsmart.config.MaidSmartConfig.PERCEPTION_WEATHER.get();
        // 敌袭语音（实测九百九十一【改口径】）：旧版拿"她周围 12 格威胁数"的快照差分判，
        // 三个子事件还共用同一个 300 秒限频 key——"有敌人靠近"播过之后"清掉了"基本被吞
        //（反馈："这个语音原本出现的判定也有问题"），而且"附近没威胁"也不代表她没在打
        //（怪被引走 / 她在圈外打）。现在与链路走位独占统一成**她自己的接敌口径**
        //（脑里/身上有活着的攻击目标，见 CombatWorkRange.attackDesireTarget）：
        //   没目标 → 有目标 = "有敌人靠近了"（报她自己那个目标的距离）；
        //   有目标 → 没目标 = "附近的敌人都清掉了，安全了"（这一仗至少跨过一次扫描才算数，
        //   防目标一闪一没刷屏）。两条各用各的限频 key（enemy_near / enemy_clear），互不吞。
        if (hostileOn) {
            UUID id = maid.m_20148_();
            net.minecraft.world.entity.LivingEntity foe =
                    com.maidsmart.combat.CombatWorkRange.attackDesireTarget(maid);
            boolean engaged = foe != null;
            Boolean was = this.engageState.put(id, engaged);
            if (engaged) {
                this.engageLast.put(id, foe);
            }
            if (engaged && (was == null || !was)) {
                this.engageSince.put(id, tick);
            }
            if (was != null) {
                if (!was && engaged) {
                    this.clearPending.remove(id); // 重新接敌：这一仗没完，撤销待播的"清掉了"
                    events.add(new String[]{"enemy_near", "有敌人靠近了！离我 "
                            + String.format(java.util.Locale.ROOT, "%.1f",
                                    Math.sqrt(maid.m_20280_(foe))) + " 格"});
                } else if (was && !engaged) {
                    Long since = this.engageSince.get(id);
                    // 实测九百九十六【清敌语音两重确认】：目标丢失 ≠ 都清掉了——先核对刚打
                    // 的那个还活着没有：只是跟丢（钻地 / 被掀飞 / 记忆闪断）不算清掉，真的
                    // 打死了才挂起等确认窗。日志实证：那条"安全了"竟带着 [警示] 前缀一块播、
                    // 几秒后她又挨监守者一炮——都是"活着跟丢"造成的误报。
                    net.minecraft.world.entity.LivingEntity last = this.engageLast.get(id);
                    if ((since == null || tick - since >= CLEAR_MIN_ENGAGE_TICKS)
                            && (last == null || !last.m_6084_())) {
                        this.clearPending.put(id, tick);
                    }
                }
            }
            // 确认：目标死了不算完——挂起后要持续 CLEAR_CONFIRM_TICKS 没重新接敌、且附近
            // 真没有威胁（hostiles == 0）才播；目标回来即撤销，一直等不到就超时静默放弃。
            Long pending = this.clearPending.get(id);
            if (pending != null) {
                if (engaged) {
                    this.clearPending.remove(id);
                } else if (now.hostiles == 0 && tick - pending >= CLEAR_CONFIRM_TICKS) {
                    this.clearPending.remove(id);
                    events.add(new String[]{"enemy_clear", "附近的敌人都清掉了，安全了！"});
                } else if (tick - pending > CLEAR_PENDING_TIMEOUT) {
                    this.clearPending.remove(id);
                }
            }
        }
        // 主人受伤（v1.5.227：限频按事件类型，不再按"含血量数字的完整文案"——
        // 旧版 70%/60%/30% 每个百分比都是新 key，30 秒限频形同虚设）
        if (ownerOn && prev.ownerHealth >= 0 && now.ownerHealth >= 0 && now.ownerHealth < prev.ownerHealth - 5.0) {
            events.add(new String[]{"owner_hurt",
                    "主人受伤了！生命值降到 " + String.format(java.util.Locale.ROOT, "%.0f", now.ownerHealth) + "%"});
        } else if (ownerOn && now.ownerHealth >= 0 && now.ownerHealth < com.maidsmart.config.MaidSmartConfig.PERCEPTION_OWNER_LOW_HEALTH.get()
                && (prev.ownerHealth >= com.maidsmart.config.MaidSmartConfig.PERCEPTION_OWNER_LOW_HEALTH.get() || prev.ownerHealth < 0)) {
            events.add(new String[]{"owner_low", "主人血量很低了，我好担心…"});
        }
        // v1.5.120：威胁抑制——附近有怪物（hostiles>0）时，"不合时宜"的非紧急感知
        // （主人注视/天气变化）停止播报；紧急类（敌对出现/靠近/消失、主人受伤）
        // 照常。危险中注视计时也不累计，解除威胁后重新计时。
        boolean threatened = now.hostiles > 0;
        // v1.5.98c：主人注视——滞回状态机 + 持续注视 ≥3 秒才报一次
        //（不再"看/不看"抖动刷屏，也不报"不看"这种常态事件）
        if (ownerOn) {
            UUID id = maid.m_20148_();
            Boolean wasLooking = this.lookingState.get(id);
            boolean nowLooking = isLookingAt(maid, player);
            // 滞回：中间区间（进入/退出角度之间）保持原状态，消除临界抖动
            if (wasLooking != null) {
                double angle = lookingAngle(maid, player);
                if (wasLooking && angle > com.maidsmart.config.MaidSmartConfig.PERCEPTION_LOOK_EXIT_DEG.get()) {
                    nowLooking = false;
                } else if (!wasLooking && angle < com.maidsmart.config.MaidSmartConfig.PERCEPTION_LOOK_ENTER_DEG.get()) {
                    nowLooking = true;
                } else {
                    nowLooking = wasLooking;
                }
            }
            this.lookingState.put(id, nowLooking);
            if (nowLooking && !threatened) {
                int t = this.lookingTicks.getOrDefault(id, 0) + 1;
                this.lookingTicks.put(id, t);
                // 持续注视达到阈值才播报；实测八百二十五：再加【每只女仆 1800 秒】
                // 独立限频——这条带语音（owner_looking.ogg），不能像别的事件那样
                // 30 秒一次。注视一直持续时每满 1800 秒报一次，中途打断则重新计时。
                if (t >= com.maidsmart.config.MaidSmartConfig.PERCEPTION_LOOK_TICKS.get() * 20) {
                    Long lastLook = this.lookCooldowns.get(id);
                    if (lastLook == null || tick - lastLook
                            >= (long) com.maidsmart.config.MaidSmartConfig.PERCEPTION_LOOK_COOLDOWN.get() * 20) {
                        this.lookCooldowns.put(id, tick);
                        events.add(new String[]{"owner_look", "主人一直在看着我…"});
                    }
                }
            } else {
                this.lookingTicks.put(id, 0); // v1.5.120：危险中注视不累计（解除后重新计时）
            }
        }
        // 天气变化（v1.5.120：有威胁时不播——危险时"下雨了"不合时宜）
        if (weatherOn && !prev.weather.equals(now.weather) && !threatened) {
            events.add(new String[]{"weather", "天气变成" + weatherName(now.weather) + "了"});
        }
        // 播报（同类 30 秒限频）
        for (String[] ev : events) {
            this.bubble(maid, ev[0], ev[1], tick);
        }
    }

    /** 主人是否看向女仆（视线 + 朝向粗略判定；进入阈值 35°，滞回见 scan） */
    private static boolean isLookingAt(EntityMaid maid, ServerPlayer player) {
        if (player == null) {
            return false;
        }
        // 距离过远不算"看向"
        if (maid.m_20270_(player) > 16.0) {
            return false;
        }
        return lookingAngle(maid, player) < com.maidsmart.config.MaidSmartConfig.PERCEPTION_LOOK_ENTER_DEG.get();
    }

    /** 主人朝向与"主人→女仆"方向的夹角（度）；距离过近视为看向 */
    private static double lookingAngle(EntityMaid maid, ServerPlayer player) {
        double dx = maid.m_20185_() - player.m_20185_();
        double dz = maid.m_20189_() - player.m_20189_();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.01) {
            return 0.0;
        }
        double yaw = Math.toRadians(player.m_146908_()); // getYRot
        double lookX = -Math.sin(yaw);
        double lookZ = Math.cos(yaw);
        double dot = (dx / dist) * lookX + (dz / dist) * lookZ;
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
    }

    private static String timePhase(ServerLevel level) {
        long time = level.m_46468_() % 24000L;
        if (time < 6000) {
            return "morning";
        }
        if (time < 12000) {
            return "noon";
        }
        if (time < 13000) {
            return "evening";
        }
        return "night";
    }

    private static String weatherName(String w) {
        return switch (w) {
            case "rain" -> "下雨";
            case "thunder" -> "雷雨";
            default -> "放晴";
        };
    }

    /** 气泡播报（v1.5.99b：同类事件【全局限频】30 秒——多女仆不再各喊一遍）
     *  v1.5.119：敌人感知显示单独限频（默认 300 秒）；主人受伤/注视/天气用 30 秒。
     *  v1.5.227：限频 key 从【完整文案】改为【事件类型】（旧版文案含距离数字，key 永不
     *  重复、限频完全失效）。实测九百九十一：敌袭两条语音各用各的 key——enemy_near 吃
     *  300 秒敌袭限频，enemy_clear 吃 30 秒普通事件限频（原先三条敌袭语音共用一个 key，
     *  靠近播过之后清敌语音被吞）。
     */
    private void bubble(EntityMaid maid, String type, String text, long tick) {
        // 实测八百二十五：owner_look 已在 scan 里做【每只女仆 1800 秒】独立限频，
        // 此处不再叠加同类事件的全局限频——否则多只女仆同时被注视时互相压制。
        if (!"owner_look".equals(type)) {
            // 实测九百九十一：敌袭两条语音改用**各自**的 key——"有敌人靠近"（enemy_near）仍吃
            // 「敌对感知显示限频」（默认 300 秒，防"发现怪"刷屏）；"附近的敌人都清掉了"
            // （enemy_clear）改吃普通事件限频（PERCEPTION_EVENT_COOLDOWN，默认 30 秒）——
            // 旧版两条共用一个 key，靠近播过一次之后清敌语音 5 分钟内全被吞（反馈的那条问题）。
            boolean hostile = "enemy_near".equals(type);
            int cd = hostile
                    ? com.maidsmart.config.MaidSmartConfig.PERCEPTION_HOSTILE_SHOW_COOLDOWN.get()
                    : com.maidsmart.config.MaidSmartConfig.PERCEPTION_EVENT_COOLDOWN.get();
            Long last = this.eventCooldowns.get(type);
            if (last != null && tick - last < (long) cd * 20) {
                return;
            }
            this.eventCooldowns.put(type, tick);
        }
        maid.getChatBubbleManager().addTextChatBubble(text);
    }
}
