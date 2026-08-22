package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * maid_smart:woodcut —— 伐木任务（v1.1.0）。
 * 女仆在附近寻找木材（原木/菌柄/竹/去皮变体），手持斧渐进开采，整棵树连锁砍完。
 * 架构完整克隆挖矿任务（MaidMineTask/MaidMineBehavior）：扫描→接近→视线→穿透→
 * 渐进挖掘→连锁采集，全部行为逻辑见 MaidWoodBehavior 头注释。
 */
public class MaidWoodTask implements IMaidTask {
    public static final ResourceLocation UID = ResourceLocation.parse("maid_smart:woodcut");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public ItemStack getIcon() {
        return new ItemStack(ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("minecraft:diamond_axe")));
    }

    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) {
        return null;
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        // 必须返回可变列表：TLM 的 MaidBrain.registerWorkGoals 会往里追加行为
        return new ArrayList<>(List.of(Pair.of(5, new MaidWoodBehavior())));
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createRideBrainTasks(EntityMaid maid) {
        // 坐下/坐垫/骑乘时任务行为仍运行（与挖矿同款：坐下 = 物理不动，但任务照常执行）
        return new ArrayList<>(List.of(Pair.of(5, new MaidWoodBehavior())));
    }

    @Override
    public boolean workPointTask(EntityMaid maid) {
        return true;
    }

    // 与挖矿同款：关闭 TLM 的"随机散步"——伐木移动完全由本行为经直接导航驱动，
    // 随机散步会反复覆盖设向木材的目标（"走一步停一下"）
    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid) {
        return false;
    }

    @Override
    public String getMaidActionSummary() {
        return "chopping wood with an axe";
    }
}
