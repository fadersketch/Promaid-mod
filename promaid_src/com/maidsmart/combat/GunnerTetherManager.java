package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * v1.3.7 实测六百六十七【武装拴绳】——粉丝点单的「武装直升机二号位」。
 *
 * 需求原文："众所周知酒狐会魔法，酒狐在空中飞的时候，是否可以让玩家挂在酒狐下方？让玩家能够
 * 与酒狐一同攻击，八宝粥行动那样武装直升机，玩家挂在2号位开火。这边要引入一个新道具，应该类似
 * 于一个不会扯断，而且长度只有一格的拴绳。可以将玩家和女仆锁在一起。使用方法跟拴绳一样，右击
 * 女仆就行。解除也是同样的方式。"
 *
 * ── 实现口径（为什么是"真乘客 + positionRider 定位"，而不是绳子拉力模拟）──
 * 把玩家 startRiding(force) 上女仆（她当载具），再在 {@link com.maidsmart.mixin.EntityMaidGunnerMixin}
 * 里把乘客定位从"骑在头顶"改成"悬挂在下方 hang 格"。选这个方案是因为：
 * <ul>
 *   <li>**位移零仿真**：乘客跟着载具走是原版机制（客户端插值、多人同步、俯冲/爬升/烟花加速
 *       全部自动正确），自己写"弹簧拉力"会跟玩家本地输入打架（橡皮筋 + 抖动）；</li>
 *   <li>**她照常飞行开火**：javap 实证 TLM {@code EntityMaid} 没有覆写
 *       {@code getControllingPassenger}（m_6688_）——玩家乘客不会抢走驾驶权，她的大脑/远程空袭
 *       一切照旧，"一号位她开火、二号位你开火"就是这么来的；</li>
 *   <li>**骑乘关系零成本同步**：乘客关系由原版 SetPassengersPacket 同步，绳子渲染只要知道
 *       "谁是她的枪手"（S2C 包，见 GunnerTetherNetworking）；</li>
 *   <li>**"长度只有一格"**：默认悬挂距离 1.8 格（可调 0.5~4.0），绳子画在女仆腰部与玩家手之间。</li>
 * </ul>
 *
 * ── 安全网（都做进 tick 校验）──
 * 她落地/入水超过 0.6 秒 → 自动把玩家放下（免得挂着拖地闷在水里）；玩家潜跳自行下鞍 /
 * 被别的模组拽下去 → 下一次校验自动解除；解除瞬间人在空中 → 5 秒摔伤豁免（不搞"刚松手就摔死"）；
 * 挂着时卡墙/挤墙伤全免（贴着树冠飞是常态）；她本人对主人的伤害由 {@link FriendlyFireGuard}
 * 三层总闸拦（挂载者必须是主人，天然被覆盖）。
 *
 * 【持久化】挂载标记写在女仆的 persistentData（{@code maid_smart_gunner}），存档重载后由
 * 每 30 秒一次的恢复扫描重建（骑乘关系本身由原版存档恢复）。
 *
 * 【事件】手持武装拴绳右击女仆 = 挂载/解除（与服务端 IndexStoneInteractHandler 同款骨架：
 * EntityInteract 事件比 TLM 的 mobInteract 先到，cancel 掉就不会误开女仆 GUI）。
 */
@Mod.EventBusSubscriber(modid = "promaid")
public final class GunnerTetherManager {

    /** persistentData 里的挂载标记键 */
    public static final String TAG_GUNNER = "maid_smart_gunner";
    /** 她连续贴地/入水多少 tick 后自动放下（12 = 0.6 秒；碰一下地面不算） */
    private static final int GROUND_DISMOUNT_TICKS = 12;
    /** 解除后的摔伤豁免时长（tick） */
    private static final int DISMOUNT_GRACE_TICKS = 100;
    /** 拒绝提示节流（ms） */
    private static final long DENY_INTERVAL_MS = 4000L;

    /** 服务端权威挂载表：maid UUID → 挂载记录（弱引用：女仆/玩家没了自动失效） */
    private static final Map<UUID, Link> LINKS = new HashMap<>();
    /**
     * 挂载对（客户端视角）：maid entityId → rider entityId。只由 S2C 包
     * （{@link GunnerTetherNetworking} → {@link com.maidsmart.client.GunnerTetherClient}）写入，
     * mixin 定位与绳子渲染读。放本类（而不是客户端类）是为了让 mixin 这份公共代码
     * 不引用任何客户端类型（专用服务器安全铁律）。
     */
    public static final Map<Integer, Integer> SYNCED_PAIRS = new HashMap<>();
    /** 她连续贴地/入水计数：maid UUID → tick 数 */
    private static final Map<UUID, Integer> GROUND_TICKS = new HashMap<>();
    /** 解除后的摔伤豁免：player UUID → 到期 game tick */
    private static final Map<UUID, Long> DISMOUNT_GRACE = new HashMap<>();
    /** 拒绝提示节流：maid UUID → 上次提示 ms */
    private static final Map<UUID, Long> DENY_LOG = new HashMap<>();
    /** 恢复扫描计时（ProMaidExtension 每 2 tick 调一次 tick()，这里再分流） */
    private static int restoreTimer = 0;

    private static final class Link {
        final java.lang.ref.WeakReference<EntityMaid> maid;
        final java.lang.ref.WeakReference<ServerPlayer> player;

        Link(EntityMaid m, ServerPlayer p) {
            this.maid = new java.lang.ref.WeakReference<>(m);
            this.player = new java.lang.ref.WeakReference<>(p);
        }
    }

    private GunnerTetherManager() {
    }

    /* ==================== 开关 / 参数 ==================== */

    public static boolean isEnabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_TETHER_ENABLE.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 悬挂距离（格）——mixin 定位用；配置没挂上时退回默认 */
    public static double hangOffset() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_TETHER_HANG.get();
        } catch (Throwable t) {
            return 1.8;
        }
    }

    /** 这位乘客是不是她的拴绳枪手（mixin 两侧行为统一入口：服务端看 LINKS / 客户端看 SYNCED_PAIRS） */
    public static boolean isGunner(EntityMaid maid, Entity passenger) {
        if (maid == null || passenger == null) {
            return false;
        }
        try {
            if (maid.m_9236_().m_5776_()) {
                // 客户端：包同步的实体 id 对
                Integer pid = SYNCED_PAIRS.get(maid.m_19879_());
                return pid != null && pid.intValue() == passenger.m_19879_();
            }
            Link link = LINKS.get(maid.m_20148_());
            ServerPlayer p = link == null ? null : link.player.get();
            return p != null && p.m_20148_().equals(passenger.m_20148_());
        } catch (Throwable t) {
            return false;
        }
    }

    /* ==================== 挂载 / 解除 ==================== */

    /** 手持武装拴绳右击自己的女仆：挂载（已在挂 → 由 handler 走解除分支） */
    public static void attach(ServerPlayer player, EntityMaid maid) {
        if (!isEnabled()) {
            return;
        }
        LivingEntity owner = maid.m_269323_();
        if (owner == null || !owner.m_20148_().equals(player.m_20148_())) {
            deny(maid, "这不是我的主人，绳子不给别人抓～");
            return;
        }
        if (LINKS.containsKey(maid.m_20148_())) {
            deny(maid, "已经有人挂在我身上了～");
            return;
        }
        if (player.m_20152_()) {
            deny(maid, "主人先从坐骑上下来再抓绳子～");
            return;
        }
        // 地面挂着会把人拖进地里——必须她飞起来时挂
        if (maid.m_20096_()) {
            deny(maid, "等我飞起来再右击我，你先抓好绳子～");
            return;
        }
        // force = true（实测六百五十七同款）：原版不带 force 的 startRiding 要求
        // 双方"此刻互相没骑"之外还要过 canAddPassenger/canRide——force 一并跳过
        //（javap 实证 m_7998_：iload_2 ifne 直接跳过两道门；开门的那道 m_269011_
        //  在基类恒 true，EntityMaid 没覆写）
        if (!player.m_7998_(maid, true)) {
            deny(maid, "绳子没扣上……再试一次？");
            return;
        }
        LINKS.put(maid.m_20148_(), new Link(maid, player));
        GROUND_TICKS.remove(maid.m_20148_());
        try {
            maid.getPersistentData().m_128359_(TAG_GUNNER, player.m_20148_().toString());
        } catch (Throwable ignored) {
        }
        GunnerTetherNetworking.send(maid, true);
        bubble(maid, "上来吧！抓好绳子，我们一起飞～");
        com.maidsmart.tool.PromaidLog.log("武装拴绳", "挂载：主人=" + playerName(player)
                + " 女仆=" + com.maidsmart.tool.PromaidLog.nameOf(maid)
                + "（悬挂 " + hangOffset() + " 格，二号位开火）");
    }

    /** 解除（natural=true 是自动解除而不是右击） */
    public static void detach(EntityMaid maid, boolean natural) {
        Link link = LINKS.remove(maid.m_20148_());
        GROUND_TICKS.remove(maid.m_20148_());
        try {
            maid.getPersistentData().m_128473_(TAG_GUNNER);
        } catch (Throwable ignored) {
        }
        ServerPlayer player = link == null ? null : link.player.get();
        GunnerTetherNetworking.send(maid, false);
        if (player != null) {
            if (!player.m_20096_()) {
                // 空中松手：5 秒摔伤豁免（"不会扯断"的绳子不负责防摔死，但也不至于秒摔没）
                try {
                    DISMOUNT_GRACE.put(player.m_20148_(),
                            player.m_9236_().m_46467_() + DISMOUNT_GRACE_TICKS);
                } catch (Throwable ignored) {
                }
            }
            if (player.m_20202_() == maid) {
                player.m_8127_();
            }
        }
        if (!natural) {
            bubble(maid, "到站啦，小心落地～");
        }
        com.maidsmart.tool.PromaidLog.log("武装拴绳", "解除(" + (natural ? "自动" : "右击") + ")：女仆="
                + com.maidsmart.tool.PromaidLog.nameOf(maid)
                + " 玩家=" + (player == null ? "?" : playerName(player)));
    }

    /** 玩家显示名（PromaidLog.nameOf 只收 EntityMaid，玩家这里自己取） */
    private static String playerName(ServerPlayer player) {
        try {
            return player.m_5446_() != null ? player.m_5446_().getString() : player.m_20148_().toString();
        } catch (Throwable t) {
            return player.m_20148_().toString();
        }
    }

    /** 手持拴绳再右击 = 解除；返回是否处理了（给 handler 决定要不要挥手） */
    public static boolean toggle(ServerPlayer player, EntityMaid maid) {
        if (!isEnabled()) {
            return false;
        }
        if (LINKS.containsKey(maid.m_20148_())) {
            LivingEntity owner = maid.m_269323_();
            boolean allowed = (owner != null && owner.m_20148_().equals(player.m_20148_()))
                    || isGunner(maid, player);
            if (!allowed) {
                deny(maid, "绳子只听主人和挂着的那位的话～");
                return true;
            }
            detach(maid, false);
            return true;
        }
        attach(player, maid);
        return true;
    }

    /* ==================== 每 tick 校验（ProMaidExtension 每 2 tick 调） ==================== */

    public static void tick(MinecraftServer server) {
        try {
            long gt = server.m_129785_().iterator().next().m_46467_();
            Iterator<Map.Entry<UUID, Link>> it = LINKS.entrySet().iterator();
            while (it.hasNext()) {
                Link link = it.next().getValue();
                EntityMaid maid = link.maid.get();
                ServerPlayer player = link.player.get();
                // ① 一方没了 / 跨维度 → 静默解除
                if (maid == null || player == null || !maid.m_6084_() || !player.m_6084_()
                        || maid.m_9236_() != player.m_9236_()) {
                    it.remove();
                    if (maid != null) {
                        try {
                            maid.getPersistentData().m_128473_(TAG_GUNNER);
                        } catch (Throwable ignored) {
                        }
                        GunnerTetherNetworking.send(maid, false);
                    }
                    continue;
                }
                // ② 玩家自己潜跳下鞍 / 被别的模组拽下去 → 解除 + 空中给摔伤豁免
                if (!maid.m_20363_(player)) {
                    it.remove();
                    try {
                        maid.getPersistentData().m_128473_(TAG_GUNNER);
                    } catch (Throwable ignored) {
                    }
                    if (!player.m_20096_()) {
                        DISMOUNT_GRACE.put(player.m_20148_(),
                                player.m_9236_().m_46467_() + DISMOUNT_GRACE_TICKS);
                    }
                    GunnerTetherNetworking.send(maid, false);
                    com.maidsmart.tool.PromaidLog.log("武装拴绳", "解除(玩家离鞍)：女仆="
                            + com.maidsmart.tool.PromaidLog.nameOf(maid));
                    continue;
                }
                // ③ 她落地/入水（连续 0.6 秒）→ 稳稳放下，别把人拖进地里/水里
                if (maid.m_20096_() || maid.m_20069_()) {
                    int grounded = GROUND_TICKS.merge(maid.m_20148_(), 2, Integer::sum);
                    if (grounded >= GROUND_DISMOUNT_TICKS) {
                        detach(maid, true);
                    }
                } else {
                    GROUND_TICKS.remove(maid.m_20148_());
                }
            }
            // 豁免表过期清理
            if (!DISMOUNT_GRACE.isEmpty()) {
                Iterator<Map.Entry<UUID, Long>> git = DISMOUNT_GRACE.entrySet().iterator();
                while (git.hasNext()) {
                    if (git.next().getValue() < gt) {
                        git.remove();
                    }
                }
            }
            // 恢复扫描（每 600 tick ≈ 30 秒）：存档重载后重建 LINKS
            if (++restoreTimer >= 300) {
                restoreTimer = 0;
                restore(server);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 存档重载/玩家重进后，从女仆 persistentData 的标记重建挂载（必须真的还骑着才恢复） */
    private static void restore(MinecraftServer server) {
        for (ServerLevel lvl : server.m_129785_()) {
            for (Entity e : lvl.m_8583_()) {
                if (!(e instanceof EntityMaid maid) || !maid.m_6084_()) {
                    continue;
                }
                if (LINKS.containsKey(maid.m_20148_())) {
                    continue;
                }
                String pid;
                try {
                    pid = maid.getPersistentData().m_128461_(TAG_GUNNER);
                } catch (Throwable t) {
                    continue;
                }
                if (pid == null || pid.isEmpty()) {
                    continue;
                }
                UUID puid;
                try {
                    puid = UUID.fromString(pid);
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                ServerPlayer p = server.m_6846_().m_11259_(puid);
                if (p == null) {
                    continue;
                }
                if (maid.m_20363_(p)) {
                    LINKS.put(maid.m_20148_(), new Link(maid, p));
                    GunnerTetherNetworking.send(maid, true);
                    com.maidsmart.tool.PromaidLog.log("武装拴绳", "存档重载恢复：女仆="
                            + com.maidsmart.tool.PromaidLog.nameOf(maid));
                } else {
                    // 骑乘关系没被存档带回来（被拽下去等）——标记清掉，别每 30 秒白扫
                    try {
                        maid.getPersistentData().m_128473_(TAG_GUNNER);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    /* ==================== 事件 ==================== */

    /** 手持武装拴绳右击女仆 = 挂载/解除（骨架同 IndexStoneInteractHandler） */
    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!isEnabled()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !(event.getTarget() instanceof EntityMaid maid)) {
            return;
        }
        InteractionHand hand = event.getHand();
        ItemStack stack = player.m_21120_(hand);
        if (!(stack.m_41720_() instanceof CombatLeashItem)) {
            return;
        }
        event.setCanceled(true);
        player.m_6674_(hand);
        toggle(player, maid);
    }

    /** 新玩家开始追踪这只女仆时，把当前挂载状态补发给他（晚进服/传过来的人也能看到绳子） */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getTarget() instanceof EntityMaid maid)) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer watcher)) {
            return;
        }
        Link link = LINKS.get(maid.m_20148_());
        ServerPlayer rider = link == null ? null : link.player.get();
        GunnerTetherNetworking.sendTo(watcher, maid.m_19879_(), rider == null ? -1 : rider.m_19879_());
    }

    /** ① 受击事件：挂着时卡墙/挤墙伤全免；刚解除 5 秒内摔伤豁免 */
    @SubscribeEvent
    public static void onLivingAttack(LivingAttackEvent event) {
        if (shouldCancel(event.getEntity(), event.getSource())) {
            event.setCanceled(true);
        }
    }

    /** ①b 受伤事件兜底（与 FriendlyFireGuard 同款双闸） */
    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (shouldCancel(event.getEntity(), event.getSource())) {
            event.setCanceled(true);
        }
    }

    private static boolean shouldCancel(LivingEntity target, DamageSource source) {
        if (!(target instanceof ServerPlayer player) || source == null) {
            return false;
        }
        try {
            String msgId = source.m_19385_();
            // ① 挂着：卡墙(inWall)/挤在一起(cramming) 全免——贴着树冠飞是常态
            if (isRidingAsGunner(player)) {
                return "inWall".equals(msgId) || "cramming".equals(msgId);
            }
            // ② 刚解除：摔伤豁免（到期由 tick 清理）
            Long until = DISMOUNT_GRACE.get(player.m_20148_());
            return until != null && "fall".equals(msgId);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 玩家此刻是否正作为拴绳枪手骑着某只女仆 */
    public static boolean isRidingAsGunner(ServerPlayer player) {
        try {
            Entity vehicle = player.m_20202_();
            return vehicle instanceof EntityMaid maid && isGunner(maid, player);
        } catch (Throwable t) {
            return false;
        }
    }

    /* ==================== 提示 ==================== */

    private static void deny(EntityMaid maid, String msg) {
        long now = System.currentTimeMillis();
        Long last = DENY_LOG.get(maid.m_20148_());
        if (last != null && now - last < DENY_INTERVAL_MS) {
            return;
        }
        DENY_LOG.put(maid.m_20148_(), now);
        bubble(maid, msg);
    }

    private static void bubble(EntityMaid maid, String msg) {
        try {
            maid.getChatBubbleManager().addTextChatBubble(msg);
        } catch (Throwable ignored) {
        }
    }
}
