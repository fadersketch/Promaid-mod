package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidPickupEntitiesTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * 拾取优先级：捡掉落物 = 最低优先级的能力。
 *
 * TLM 的拾取是 core 行为（优先级 10，任何 activity 都运行）。这里在拾取任务启动前
 * （{@code checkExtraStartConditions}）按开关 {@code misc.pickupPriority} 决定是否让位：**默认关**
 * （干活时也去捡掉落物，反馈："砍树伐木时不捡掉落物"）；打开后女仆挖矿/伐木期间拾取任务不启动
 * （旧版行为：防她中途跳下柱子去捡）。干活期间"去捡东西"这一发 WALK_TARGET 由链路独占闸
 * （{@code MaidMoveSuppressMixin}）按 {@code MaidWorkTags#isPickupDrive} 放行。
 * 【拾取目标价值重排】TLM 拾取任务按可见列表顺序取第一个（距离近优先），没有价值概念；这里在 {@code getItems}
 * 返回时按价值重排：原版低价值物品（废石/沙/粘土等）权重 0.5 排后，其余（矿石/模组物品）权重 1 排前，同权
 * 保持原顺序（距离近优先）。模组物品天然权重 1，不会被原版垃圾挤掉——不做"挖矿×N"放大，只降权原版低价值
 * 物品。
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

    @Inject(method = "checkExtraStartConditions(Lnet/minecraft/server/level/ServerLevel;Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;)Z",
            at = @At("HEAD"), cancellable = true)
    private void promaid$pickupLowPriority(ServerLevel level, EntityMaid maid,
                                           CallbackInfoReturnable<Boolean> cir) {
        // v1.5.88：挖矿中禁止拾取（配置面板 misc.pickupPriority）
        // R1125：伐木有自己的开关 wood.noPickup（默认开）——砍树中途也不去捡掉落物；
        // 总开关 misc.pickupPriority 打开时两种工作一起让位（旧行为不变）。
        boolean mining = com.maidsmart.task.MaidMineBehavior.isMining(maid);
        boolean wooding = com.maidsmart.task.MaidWoodBehavior.isWooding(maid);
        boolean blockMine = mining && com.maidsmart.config.MaidSmartConfig.MISC_PICKUP_PRIORITY.get();
        boolean blockWood = wooding && (com.maidsmart.config.MaidSmartConfig.WOOD_NO_PICKUP.get()
                || com.maidsmart.config.MaidSmartConfig.MISC_PICKUP_PRIORITY.get());
        if (blockMine || blockWood) {
            cir.setReturnValue(false);
        }
    }

    /** v1.5.101c：拾取目标按价值重排——高价值（矿石/模组物品 = 权重 1）优先，
     *  原版低价值物品（权重 0.5）排后；同权保持原顺序（距离近优先） */
    @Inject(method = "getItems(Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;)Ljava/util/List;", at = @At("RETURN"), cancellable = true)
    private void promaid$reorderPickupItems(EntityMaid maid, CallbackInfoReturnable<List<Entity>> cir) {
        // 实测八百三十五：价值重排自己的开关（DIY 预设会关掉它——那才是 TLM 原版顺序）
        if (!com.maidsmart.config.MaidSmartConfig.MISC_PICKUP_VALUE_REORDER.get()) {
            return;
        }
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

    /** 实测九百一十六：拾取任务开始挑目标驱动走位时打戳——让链路独占闸（{@code MaidMoveSuppressMixin}）
     *  对"去捡掉落物"这一发 WALK_TARGET 放行（干活期间也捡东西，见 MaidWorkTags#isPickupDrive）。
     *  v1.5.400：注入点必须写 TLM 自己的那个重载 {@code start(ServerLevel, EntityMaid, long)}。Forge 侧
     *  运行期类里它还存在一个**协变桥接**方法，参数是 {@code LivingEntity}（名字被重映射成 {@code m_6735_}）——
     *  旧版按 `m_6735_` 注入正好命中桥接，签名对不上，女仆一生成就崩（InvalidDescriptorException）。 */
    @Inject(method = "start(Lnet/minecraft/server/level/ServerLevel;Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;J)V",
            at = @At("HEAD"))
    private void promaid$markPickupDrive(ServerLevel level, EntityMaid maid, long gameTime, CallbackInfo ci) {
        com.maidsmart.task.MaidWorkTags.markPickupDrive(maid, gameTime);
    }
}
