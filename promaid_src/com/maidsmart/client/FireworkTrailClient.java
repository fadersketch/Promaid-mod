package com.maidsmart.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 烟花尾迹的**客户端**表现层：粒子长在她**被画出来的那个位置**上（1.20.1 镜像）。
 *
 * <p>旧版由服务端 {@code m_8767_} 打尾迹，坐标是服务端那一刻的她；客户端画她用的是
 * {@code xo → x} 的逐帧插值（叠加位置包限流与延迟），她滑翔时一拍一格多——差的这一拍就是"粒子跑到人前面"。
 * 原版挂载烟花没有这一步：{@code FireworkRocketEntity.m_8119_} 的粒子只在客户端、且在**火箭自己**的坐标上发。
 * 本轮照这个口径：服务端只发"哪只女仆、多少拍"（{@link com.maidsmart.combat.FireworkTrailNetworking}），
 * 客户端每拍补一粒，位置/速度/密度都同原版一行 {@code m_7106_(FIREWORK, x, y, z, gauss×0.05, -dy×0.5, gauss×0.05)}。
 *
 * <p>【为什么在渲染阶段而不是 tick】她按 {@code xo → x} 的 partialTick 插值画出来；
 * 在 {@code AFTER_ENTITIES} 用**同一个 partialTick** 取她的位置，粒子就落在这一帧的身体上。
 * 同一游戏拍只发一粒（{@link #LAST_EMIT}），密度仍是原版的 20 粒/秒。
 */
@net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class FireworkTrailClient {

    private static boolean registered = false;

    /** maidId → 尾迹在客户端结束的 gameTime */
    private static final Map<Integer, Long> ENDS = new HashMap<>();
    /** maidId → 上一次发粒子的 gameTime（一帧多拍时不重复发） */
    private static final Map<Integer, Long> LAST_EMIT = new HashMap<>();

    private FireworkTrailClient() {
    }

    public static void ensureRegistered() {
        if (!registered) {
            registered = true;
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(FireworkTrailClient.class);
        }
    }

    /** S2C 到达：开/关一段尾迹（{@code ticks <= 0} = 立刻收掉）。 */
    public static void start(int maidId, int ticks) {
        ensureRegistered();
        ClientLevel level = Minecraft.m_91087_().f_91073_;
        if (level == null) {
            return;
        }
        if (ticks <= 0) {
            ENDS.remove(maidId);
            LAST_EMIT.remove(maidId);
            return;
        }
        ENDS.put(maidId, level.m_46467_() + ticks);
    }

    /** 退出世界清表（客户端专类，由退出事件调用）。 */
    public static void clear() {
        ENDS.clear();
        LAST_EMIT.clear();
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || ENDS.isEmpty()) {
            return;
        }
        ClientLevel level = Minecraft.m_91087_().f_91073_;
        if (level == null) {
            clear();
            return;
        }
        float partial = event.getPartialTick();
        long now = level.m_46467_();
        Iterator<Map.Entry<Integer, Long>> it = ENDS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Long> entry = it.next();
            int maidId = entry.getKey();
            if (now > entry.getValue()) {
                it.remove();
                LAST_EMIT.remove(maidId);
                continue;
            }
            Long last = LAST_EMIT.get(maidId);
            if (last != null && last == now) {
                continue;
            }
            if (!(level.m_6815_(maidId) instanceof EntityMaid maid)
                    || !maid.m_21255_() || maid.m_20096_()) {
                continue;
            }
            emit(level, maid, partial);
            LAST_EMIT.put(maidId, now);
        }
    }

    /** 原版那一行：位置取她本帧的插值坐标，速度 {@code (gauss×0.05, -dy×0.5, gauss×0.05)}。 */
    private static void emit(ClientLevel level, EntityMaid maid, float partial) {
        double x = maid.f_19790_ + (maid.m_20185_() - maid.f_19790_) * partial;
        double y = maid.f_19791_ + (maid.m_20186_() - maid.f_19791_) * partial
                + maid.m_20206_() * 0.5;
        double z = maid.f_19792_ + (maid.m_20189_() - maid.f_19792_) * partial;
        double vy = -(maid.m_20186_() - maid.f_19791_) * 0.5;
        SimpleParticleType type = particle("firework");
        double spread = 0.05;
        ItemStack chest = maid.m_6844_(EquipmentSlot.CHEST);
        if (!chest.m_41619_()) {
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(chest.m_41720_());
            String id = key == null ? "" : key.toString();
            if ("enigmaticlegacyplus:majestic_elytra".equals(id)
                    || "enigmaticlegacyplus:chaos_elytra".equals(id)) {
                type = particle("dragon_breath");
                spread = 0.02;
            }
        }
        if (type == null) {
            return;
        }
        level.m_7106_(type, x, y, z,
                level.f_46441_.m_188500_() * spread,
                vy,
                level.f_46441_.m_188500_() * spread);
    }

    /** 按注册名取粒子，避免跨版本 SRG 字段名差异（与 {@code MaidVirtualFirework} 同款）。 */
    private static SimpleParticleType particle(String path) {
        ParticleType<?> type = ForgeRegistries.PARTICLE_TYPES.getValue(
                new ResourceLocation("minecraft", path));
        return type instanceof SimpleParticleType simple ? simple : null;
    }
}
