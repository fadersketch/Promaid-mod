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
     */
    private static final Map<UUID, long[]> DANGER_CACHE = new HashMap<>();
    /** 敌袭判定缓存 TTL（tick，10 = 0.5 秒） */
    private static final int DANGER_TTL_TICKS = 10;
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
        // 清理离开范围的女仆快照（防内存泄漏；v1.5.98c 含注视状态）
        this.lastSnapshots.keySet().removeIf(id -> !this.isAnyMaidNearby(server, id));
        this.lookingState.keySet().removeIf(id -> !this.lastSnapshots.containsKey(id));
        this.lookingTicks.keySet().removeIf(id -> !this.lastSnapshots.containsKey(id));
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
     * v1.5.195：统一威胁判定——候选生物是【敌对生物】或【对女仆/主人携带仇恨】
     * （getTarget==女仆/主人，覆盖中立生物被激怒反咬、主人被攻击的生物）。
     * Monster 兜底：即使暂未锁定目标，敌对生物靠近也算威胁。
     */
    public static boolean isThreat(LivingEntity candidate, EntityMaid maid) {
        try {
            if (candidate == null || !candidate.m_6084_() || candidate == maid) {
                return false;
            }
            if (candidate instanceof Monster) {
                return true;
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
            return false;
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
            return false;
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
            double d = m.m_20238_(maid.m_20182_());
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
        // 敌人出现（v1.5.195：文案"敌对生物/怪物"→"敌人"——威胁含中立反咬/主人被攻击的生物）
        // v1.5.228：三个敌袭子事件共用同一个 key "enemy"（300 秒限频）——
        // 旧版 appear/approach/gone 分开 key，敌人反复出现/消失会各刷一条
        if (hostileOn && prev.hostiles == 0 && now.hostiles > 0) {
            events.add(new String[]{"enemy",
                    "有敌人靠近了！离我 " + String.format(java.util.Locale.ROOT, "%.1f", now.nearestHostileDist) + " 格"});
        } else if (hostileOn && now.hostiles > 0 && prev.nearestHostileDist >= 0 && now.nearestHostileDist >= 0
                && now.nearestHostileDist + 3.0 < prev.nearestHostileDist) {
            events.add(new String[]{"enemy",
                    "有敌人靠近…离我 " + String.format(java.util.Locale.ROOT, "%.1f", now.nearestHostileDist) + " 格了"});
        } else if (hostileOn && prev.hostiles > 0 && now.hostiles == 0) {
            events.add(new String[]{"enemy", "附近的敌人都清掉了，安全了！"});
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
                // 持续注视达到阈值才播报（首次达到报一次）
                if (t == com.maidsmart.config.MaidSmartConfig.PERCEPTION_LOOK_TICKS.get() * 20) {
                    events.add(new String[]{"owner_look", "主人一直在看着我…"});
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
        if (maid.m_20238_(player.m_20182_()) > 16.0) {
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
     *  v1.5.119：敌人感知显示单独限频（默认 300 秒）——感知检测照常进行，仅
     *  "发现敌人"这类显示频率大大降低；主人受伤/注视/天气仍用原 30 秒限频。
     *  v1.5.227：限频 key 从【完整文案】改为【事件类型】——旧版文案含距离/血量
     *  数字（"离我 71.5 格了"每次不同），key 永不重复、限频完全失效（实测敌人
     *  警示每 10 秒刷一条）。enemy_appear/enemy_approach/enemy_gone 共用一个
     *  300 秒敌袭限频，owner_hurt/owner_low/owner_look/weather 共用 30 秒。 */
    private void bubble(EntityMaid maid, String type, String text, long tick) {
        boolean hostile = type.equals("enemy");
        int cd = hostile
                ? com.maidsmart.config.MaidSmartConfig.PERCEPTION_HOSTILE_SHOW_COOLDOWN.get()
                : com.maidsmart.config.MaidSmartConfig.PERCEPTION_EVENT_COOLDOWN.get();
        Long last = this.eventCooldowns.get(type);
        if (last != null && tick - last < (long) cd * 20) {
            return;
        }
        this.eventCooldowns.put(type, tick);
        maid.getChatBubbleManager().addTextChatBubble(text);
    }
}
