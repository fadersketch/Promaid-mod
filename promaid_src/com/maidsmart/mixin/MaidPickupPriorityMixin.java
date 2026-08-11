package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidPickupEntitiesTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * 拾取优先级（v1.5.87）：捡掉落物 = 最低优先级的能力。
 *
 * TLM 的拾取是 core 行为（优先级 10，任何 activity 都运行）——挖矿时女仆
 * 会中途跳下去捡附近的掉落物（包括自己柱子 10 秒销毁掉的），打断工作甚至
 * 从柱子上跳下来。这里在拾取任务启动前（checkExtraStartConditions）拦截：
 * 女仆正在挖矿 → 拾取任务不启动（矿石掉落物由挖矿流程即时回收，不走拾取任务）。
 *
 * v1.5.101c：新增拾取目标价值重排——TLM 拾取任务按可见列表顺序取第一个
 * （距离近优先），没有价值概念。这里在 getItems 返回时按价值重排：
 * 原版低价值物品（废石/沙/粘土等）权重 0.5 排后，其余（矿石/模组物品）权重
 * 1 排前，同权保持原顺序（距离近优先）。模组物品天然权重 1，不会被原版垃圾
 * 挤掉——不做"挖矿×N"的放大，只降权原版低价值物品。
 *
 * 目标类是 TLM 的（编译 classpath 有，未混淆开发名），直接类引用。
 */
@Mixin(MaidPickupEntitiesTask.class)
public abstract class MaidPickupPriorityMixin {

    /** v1.5.101c：原版低价值物品（挖矿废石/沙/粘土——优先级降为 0.5 档；仅限 minecraft 命名空间，
     *  其他模组物品保持权重 1） */
    private static final java.util.Set<String> LOW_VALUE = java.util.Set.of(
            "stone", "cobblestone", "deepslate", "cobbled_deepslate", "granite", "diorite",
            "andesite", "tuff", "gravel", "dirt", "netherrack", "blackstone", "basalt",
            "soul_sand", "soul_soil", "magma_block", "calcite", "dripstone_block",
            "sandstone", "red_sandstone", "sand", "red_sand", "clay");

    @Inject(method = "checkExtraStartConditions",
            at = @At("HEAD"), cancellable = true)
    private void promaid$pickupLowPriority(ServerLevel level, EntityMaid maid,
                                           CallbackInfoReturnable<Boolean> cir) {
        // v1.5.88：拾取优先级开关（配置面板 misc.pickupPriority）
        if (!com.maidsmart.config.MaidSmartConfig.MISC_PICKUP_PRIORITY.get()) {
            return;
        }
        // v1.5.87：工作中不捡——防止"挖矿中途跳下去捡掉落物 / 从柱子跳下又搭上"
        if (com.maidsmart.task.MaidMineBehavior.isMining(maid)) {
            cir.setReturnValue(false);
        }
    }

    /** v1.5.101c：拾取目标按价值重排——高价值（矿石/模组物品 = 权重 1）优先，
     *  原版低价值物品（权重 0.5）排后；同权保持原顺序（距离近优先） */
    @Inject(method = "getItems", at = @At("RETURN"), cancellable = true)
    private void promaid$reorderPickupItems(EntityMaid maid, CallbackInfoReturnable<List<Entity>> cir) {
        List<Entity> list = cir.getReturnValue();
        if (list == null || list.size() <= 1) {
            return;
        }
        List<Entity> sorted = new ArrayList<>(list);
        sorted.sort((a, b) -> Integer.compare(pickupWeight(b), pickupWeight(a)));
        cir.setReturnValue(sorted);
    }

    /** 拾取权重：原版低价值物品 0（=0.5 档）；其余 1（矿石/工具/模组物品保持 1） */
    private static int pickupWeight(Entity e) {
        if (!(e instanceof ItemEntity ie)) {
            return 1;
        }
        net.minecraft.resources.ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.ITEMS
                .getKey(ie.m_32055_().m_41720_());
        if (key != null && "minecraft".equals(key.m_135827_()) && LOW_VALUE.contains(key.m_135815_())) {
            return 0;
        }
        return 1;
    }
}
