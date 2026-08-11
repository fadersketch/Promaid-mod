package com.maidsmart.build;

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
 * maid_smart:build —— 建筑任务。
 * 按 BuildPlan 计划逐块放置方块（从背包真实消耗，缺料暂停等待）。
 */
public class MaidBuildTask implements IMaidTask {
    public static final ResourceLocation UID = ResourceLocation.parse("maid_smart:build");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public ItemStack getIcon() {
        return new ItemStack(ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("minecraft:crafting_table")));
    }

    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) {
        return null;
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        // 必须返回可变列表：TLM 的 MaidBrain.registerWorkGoals 会往里追加行为
        return new ArrayList<>(List.of(Pair.of(5, new MaidBuildBehavior())));
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createRideBrainTasks(EntityMaid maid) {
        // v1.5.25：坐下/坐垫/骑乘时任务行为仍运行（RIDE_WORK activity）
        return new ArrayList<>(List.of(Pair.of(5, new MaidBuildBehavior())));
    }

    @Override
    public boolean workPointTask(EntityMaid maid) {
        return true;
    }

    /**
     * v1.5.123：关闭 TLM"随机散步"（WORK activity 的 RandomStroll）——建筑是站桩任务
     * （WORK_STILL_TAG 已压制移动），但散步行为仍会每 60~120 tick 写入随机 WALK_TARGET，
     * 徒增记忆写入与潜在竞态；关闭后 MaidRunOne 整体不启动，站桩更干净。
     */
    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid) {
        return false;
    }

    @Override
    public String getMaidActionSummary() {
        return "building a structure from the build plan";
    }
}
