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
 * maid_smart:cook —— 烹饪任务。
 * 女仆给附近的熔炉补充燃料与生食，并把烧好的成品收进背包。
 */
public class MaidCookTask implements IMaidTask {
    public static final ResourceLocation UID = ResourceLocation.parse("maid_smart:cook");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public ItemStack getIcon() {
        return new ItemStack(ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("minecraft:furnace")));
    }

    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) {
        return null;
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        // 必须返回可变列表：TLM 的 MaidBrain.registerWorkGoals 会往里追加行为
        return new ArrayList<>(List.of(Pair.of(5, new MaidCookBehavior())));
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createRideBrainTasks(EntityMaid maid) {
        // v1.5.25：坐下/坐垫/骑乘时任务行为仍运行（RIDE_WORK activity）
        return new ArrayList<>(List.of(Pair.of(5, new MaidCookBehavior())));
    }

    @Override
    public boolean workPointTask(EntityMaid maid) {
        return true;
    }

    /** v1.5.123：关闭 TLM"随机散步"（站桩任务不需要随机漫步，见 MaidMineTask 注释） */
    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid) {
        return false;
    }

    @Override
    public String getMaidActionSummary() {
        return "\u70f9\u7096"; // 烹饪（v1.5.252e 去掉冶炼，回归纯烹饪）
    }
}
