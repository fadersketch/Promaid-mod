package com.maidsmart.emotion;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * 情绪价值交互（v1.1.0 实测二百八十五）——对准女仆按键触发亲昵动作。
 *
 * 触发（键位默认 G/H，原版按键设置可改，见 EmotionKeysClient）：
 * - G【摸摸头】：好感 +1 + 心形粒子 + 女仆开心气泡 + 系统消息；8 秒冷却（按女仆）
 * - H【抱抱】  ：好感 +3 + 双向回 1 心 + 心形粒子加倍 + 气泡 + 系统消息；30 秒冷却
 *
 * 服务端全程验证：视线夹角 ≤~20°、距离 ≤4 格、仅主人（maid.m_21830_），
 * 客户端只发动作类型——无女仆/非主人/冷却中在服务端判定，防作弊。
 *
 * 好感度走 TLM 官方 API（getFavorabilityManager().add，1.5.3 javap 实证）；
 * 心形粒子复用 TLM 官方 SpawnParticleMessage（NetworkHandler.sendToNearby）。
 * TLM 1.5.3 无内置摸头/抱抱动作（动画仅 idle/walk/attack 等模型动画），
 * 视觉反馈 = 女仆看向主人 + 挥臂 + 心形粒子 + 女仆语音（tryPlayMaidPickupSound）。
 */
public final class EmotionNetworking {
    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new net.minecraft.resources.ResourceLocation("maid_smart", "emotion"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private EmotionNetworking() {
    }

    public static void register() {
        CHANNEL.registerMessage(0, TriggerEmotionPacket.class,
                TriggerEmotionPacket::encode, TriggerEmotionPacket::decode, TriggerEmotionPacket::handle);
        // v1.1.0 实测二百八十六：姿势同步（S2C）——附近玩家收到后客户端在
        // TLM 动画最终骨骼覆盖层播放摸头/抱抱姿势（HeartPact 同机制）
        CHANNEL.registerMessage(1, EmotionPosePacket.class,
                EmotionPosePacket::encode, EmotionPosePacket::decode, EmotionPosePacket::handle);
    }

    /** 动作冷却表（毫秒时间戳，按女仆 UUID） */
    private static final java.util.Map<String, Long> COOLDOWN = new java.util.HashMap<>();
    private static final long PAT_CD_MS = 8_000L;
    private static final long HUG_CD_MS = 30_000L;

    /** C2S 触发包：type 0=摸头 1=抱抱 */
    public static class TriggerEmotionPacket {
        public final int type;

        public TriggerEmotionPacket(int type) {
            this.type = type;
        }

        public static void encode(TriggerEmotionPacket pkt, FriendlyByteBuf buf) {
            buf.m_130130_(pkt.type); // writeVarInt
        }

        public static TriggerEmotionPacket decode(FriendlyByteBuf buf) {
            return new TriggerEmotionPacket(buf.m_130242_()); // readVarInt
        }

        public static void handle(TriggerEmotionPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null || !(player.m_9236_() instanceof ServerLevel level)) {
                    return;
                }
                boolean hug = pkt.type == 1;
                // v1.1.0 实测二百八十九：服务端收到包 + 判定结果诊断日志
                //（latest.log 搜 "emotion srv"）——定位断点用，验证后移除
                org.slf4j.Logger log = com.mojang.logging.LogUtils.getLogger();
                log.info("emotion srv: got type={} from {}", pkt.type,
                        player.m_5446_() != null ? player.m_5446_().getString() : "?");
                EntityMaid maid = findNearbyMaid(player);
                if (maid == null) {
                    log.info("emotion srv: no maid nearby (type={})", pkt.type);
                    return; // 4 格内没有女仆：静默
                }
                log.info("emotion srv: maid found {} (type={})", maid.m_20148_(), pkt.type);
                if (!maid.m_21830_(player)) {
                    log.info("emotion srv: not owner, rejected");
                    player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                            "\u00a7d[maid_smart]\u00a7f 她只让主人摸哦～"));
                    return;
                }
                String key = maid.m_20148_().toString() + ":" + pkt.type;
                long now = System.currentTimeMillis();
                Long last = COOLDOWN.get(key);
                long cd = hug ? HUG_CD_MS : PAT_CD_MS;
                if (last != null && now - last < cd) {
                    log.info("emotion srv: cooldown active, rejected");
                    player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                            "\u00a77[maid_smart]\u00a7f 她刚被安抚过啦，让她缓缓～"));
                    return;
                }
                COOLDOWN.put(key, now);
                log.info("emotion srv: accepted type={}", pkt.type);
                String name = maid.m_5446_() != null ? maid.m_5446_().getString() : "女仆";
                byte poseType = hug ? EmotionPoseState.TYPE_HUG : EmotionPoseState.TYPE_PAT;
                // v1.1.0 实测二百八十六：服务端记录姿势状态 + 广播给追踪该女仆的
                // 玩家（含发起者）——客户端动画层播放对应姿势
                EmotionPoseState.start(maid, poseType);
                CHANNEL.send(net.minecraftforge.network.PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> maid),
                        new EmotionPosePacket(maid.m_19879_(), poseType));
                if (hug) {
                    // 抱抱：好感 +3 + 双向回 1 心 + 粒子加倍
                    maid.getFavorabilityManager().add(3);
                    maid.m_5634_(2.0f);
                    player.m_5634_(2.0f);
                    com.maidsmart.action.EmotionalActionExecutor.heartParticles(maid);
                    com.maidsmart.action.EmotionalActionExecutor.heartParticles(maid);
                    say(maid, "抱抱！好温暖～", "主人的怀抱最安心了～", "嘿嘿，被抱着都不想动啦～");
                    maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
                    maid.tryPlayMaidPickupSound();
                    // v1.1.0 实测二百九十：系统消息加旁白（第三人称叙述，括号包裹）
                    player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                            "\u00a7d[抱抱]\u00a7f（你张开双臂，把 " + name
                                    + " 轻轻拥入怀中。她先是愣了一下，随即红着脸回抱住你，"
                                    + "把脸埋在你胸口蹭了蹭，小声嘟囔着" + "\u00a7d「主人的怀抱最安心了～」"
                                    + "\u00a7f）（好感 +3，彼此都恢复了一点生命，好感等级 "
                                    + maid.getFavorabilityManager().getLevel() + "）"));
                } else {
                    // 摸摸头：好感 +1 + 粒子
                    maid.getFavorabilityManager().add(1);
                    com.maidsmart.action.EmotionalActionExecutor.heartParticles(maid);
                    say(maid, "嘿嘿，好舒服～", "摸摸头就又有精神了！", "最喜欢主人摸头了～");
                    maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
                    maid.tryPlayMaidPickupSound();
                    // v1.1.0 实测二百九十：系统消息加旁白（第三人称叙述，括号包裹）
                    player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                            "\u00a7d[摸摸头]\u00a7f（你伸出手，轻轻揉了揉 " + name
                                    + " 的头。她眯起眼睛，像只被顺毛的猫一样蹭着你的手心，"
                                    + "头顶仿佛冒出了小星星）（好感 +1，好感等级 "
                                    + maid.getFavorabilityManager().getLevel() + "）"));
                }
                // 女仆看向主人（现成动作：LOOK_TARGET 记忆）
                com.maidsmart.action.EmotionalActionExecutor.lookAtOwner(maid, player);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** 女仆随机说一句（BuildShieldGuard 静默口径与喂食一致） */
    private static void say(EntityMaid maid, String... lines) {
        try {
            String line = lines[java.util.concurrent.ThreadLocalRandom.current().nextInt(lines.length)];
            maid.getChatBubbleManager().addTextChatBubble(line);
            if (!com.maidsmart.combat.BuildShieldGuard.shouldMute(maid)) {
                net.minecraft.world.entity.LivingEntity owner = maid.m_269323_();
                if (owner instanceof ServerPlayer sp) {
                    sp.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                            "\u00a7d" + (maid.m_5446_() != null ? maid.m_5446_().getString() : "女仆")
                                    + "：" + line));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /* ==================== S2C 姿势同步 ==================== */

    /** v1.1.0 实测二百八十六：S2C 姿势同步——女仆 entityId + 姿势类型。
     *  客户端收到后按墙钟记录开始时刻，动画层按进度插值播放。 */
    public static class EmotionPosePacket {
        public final int entityId;
        public final byte type;

        public EmotionPosePacket(int entityId, byte type) {
            this.entityId = entityId;
            this.type = type;
        }

        public static void encode(EmotionPosePacket pkt, FriendlyByteBuf buf) {
            buf.m_130130_(pkt.entityId); // writeVarInt
            buf.writeByte(pkt.type);
        }

        public static EmotionPosePacket decode(FriendlyByteBuf buf) {
            return new EmotionPosePacket(buf.m_130242_(), buf.readByte()); // readVarInt
        }

        public static void handle(EmotionPosePacket pkt, Supplier<NetworkEvent.Context> ctx) {
            // S2C 方向校验（同 BrewManual Open 包）——专用服误收不加载客户端类
            if (ctx.get().getDirection() != net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT) {
                ctx.get().setPacketHandled(true);
                return;
            }
            ctx.get().enqueueWork(() ->
                    com.maidsmart.emotion.EmotionPoseClient.onPose(pkt.entityId, pkt.type));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 服务端判定：2 格内最近的女仆（v1.1.0 实测二百八十九）。
     *  用户要求："把逻辑写简单一点，好触发一些"——不再做视线/角度/射线
     *  判定（旧版 cos≥0.9、射线-AABB 全部实测失败，用户反复进退游戏测试
     *  五六次）。现在 = 空手右击同款逻辑：范围内存在女仆即触发，取最近。
     *  实测二百九十：触发半径 4→2 格（用户："更拟真一些"——摸头/抱抱
     *  本来就是贴身动作，站 4 格外隔空摸头太假）。
     *  查询范围 16 格（m_82363_ 正参数只向正方向膨胀，6 格查询实测找不到
     *  dist=2.3 的女仆，16 格查询经日志实证可靠），距离过滤 2 格。 */
    private static EntityMaid findNearbyMaid(ServerPlayer player) {
        EntityMaid best = null;
        double bestDist = 2.0;
        for (EntityMaid maid : player.m_9236_().m_45976_(EntityMaid.class,
                player.m_20191_().m_82363_(16.0, 16.0, 16.0))) {
            if (!maid.m_6084_()) {
                continue;
            }
            double dist = maid.m_20270_(player);
            if (dist < bestDist) {
                bestDist = dist;
                best = maid;
            }
        }
        return best;
    }
}
