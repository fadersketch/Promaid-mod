package com.maidsmart.build;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * maid_smart:index_build —— 指标石一次性临时建造任务（v1.2.0，1.21.1 版）。
 *
 * 隐藏任务：isHidden 返回 true，不出现在 TLM 任务面板；只由 IndexStoneService
 * 在玩家绑定女仆时临时指派，任务完成后立刻还原原任务。
 */
public class IndexStoneBuildTask implements IMaidTask {
    public static final ResourceLocation UID = IndexStoneService.TASK_UID;

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public ItemStack getIcon() {
        return new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                ResourceLocation.parse("minecraft:crafting_table")));
    }

    /** 隐藏：不出现在 TLM 任务选择面板 */
    @Override
    public boolean isHidden(EntityMaid maid) {
        return true;
    }

    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) {
        return null;
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        return new ArrayList<>(List.of(Pair.of(5, new IndexStoneBuildBehavior())));
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createRideBrainTasks(EntityMaid maid) {
        return new ArrayList<>(List.of(Pair.of(5, new IndexStoneBuildBehavior())));
    }

    @Override
    public boolean workPointTask(EntityMaid maid) {
        return true;
    }

    /** 站桩任务：关闭 TLM 随机散步（与建造任务一致） */
    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid) {
        return false;
    }

    @Override
    public String getMaidActionSummary() {
        return "building a temporary bridge from the index stone";
    }
}
