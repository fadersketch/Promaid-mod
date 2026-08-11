package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidFeedAnimalTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.5.189：畜牧数量控制（杀幼保成）——动物数量管理。
 *
 * TLM MaidFeedAnimalTask 只在"动物数 < 上限"时喂食繁殖，超过上限就只播报
 * "动物太多"不处理——女仆的农场动物会无限膨胀。这里在 start TAIL 后：
 * 若附近同种成年动物数 > 上限（misc.animalCapLimit，默认 50），击杀多余
 * 【幼年】动物（杀幼保成——幼崽无产物、占空间，成年保持产量）。
 * 激进操作，默认关（misc.animalCapControl）。
 */
@Mixin(MaidFeedAnimalTask.class)
public abstract class MaidFeedAnimalCapMixin {

    @Inject(method = "start", at = @At("TAIL"))
    private void maidsmart$capAnimalCount(ServerLevel world, EntityMaid maid, long gameTime, CallbackInfo ci) {
        if (!com.maidsmart.config.MaidSmartConfig.ANIMAL_CAP_CONTROL.get()) {
            return;
        }
        int limit = com.maidsmart.config.MaidSmartConfig.ANIMAL_CAP_LIMIT.get();
        try {
            // 女仆周围 24 格内的所有动物（feed_animal 的可见范围）
            net.minecraft.world.phys.AABB box = maid.m_20191_().m_82400_(24.0);
            java.util.List<Animal> animals = world.m_6443_(Animal.class, box, e -> e.m_6084_());
            // 按种类分组统计：成年数 + 幼年列表
            java.util.Map<String, java.util.List<Animal>> byType = new java.util.HashMap<>();
            for (Animal a : animals) {
                String type = a.m_6095_().toString(); // 实体类型名（如 minecraft:sheep）
                byType.computeIfAbsent(type, k -> new java.util.ArrayList<>()).add(a);
            }
            for (java.util.List<Animal> list : byType.values()) {
                // 幼年（m_146764_ != 0）优先杀；成年超限才杀多余成年
                java.util.List<Animal> babies = list.stream().filter(a -> a.m_146764_() != 0)
                        .sorted((x, y) -> Integer.compare(y.m_146764_(), x.m_146764_())).toList();
                long adultCount = list.size() - babies.size();
                long toKill = Math.max(0, list.size() - limit);
                int killed = 0;
                for (Animal b : babies) {
                    if (killed >= toKill) {
                        break;
                    }
                    if (maid.m_20270_(b) <= 16.0) {
                        // v1.5.189：m_146870_ = kill()（无参，实证）；直接击杀幼年
                        b.m_146870_();
                        killed++;
                    }
                }
                // 幼年杀完仍超限 → 杀多余成年（同一种类、远离女仆的优先）
                if (killed < toKill) {
                    java.util.List<Animal> adults = list.stream().filter(a -> a.m_146764_() == 0)
                            .sorted((x, y) -> Double.compare(maid.m_20270_(y), maid.m_20270_(x))).toList();
                    for (Animal a : adults) {
                        if (killed >= toKill) {
                            break;
                        }
                        if (maid.m_20270_(a) <= 16.0) {
                            a.m_146870_();
                            killed++;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }
}
