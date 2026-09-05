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
 * maid_smart:slaughter —— 宰杀任务（v1.1.0 实测三百一十一）：女仆检测自己周围
 * 5×5 范围内的牲畜（AgeableMob——牛/猪/羊/鸡/兔等），按【实体类型】分组计数，
 * 某组数量超过面板阈值（misc.slaughterCount，默认 5）时，每 3 秒随机 kill 一只
 * 该组牲畜（播放挥臂动画）。
 */
public class MaidSlaughterTask implements IMaidTask {
    public static final ResourceLocation UID = ResourceLocation.parse("maid_smart:slaughter");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public ItemStack getIcon() {
        return new ItemStack(ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("minecraft:beef")));
    }

    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) {
        return null;
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        // v1.1.0 实测三百一十七（排查）：行为注册日志——setTask 触发 refreshBrain
        // 时调用；无此日志 = 任务从未被设置/大脑未刷新
        try {
            com.maidsmart.tool.PromaidLog.log("宰杀",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 宰杀行为已注册（createBrainTasks）");
        } catch (Throwable ignored) {
        }
        // 必须返回可变列表：TLM 的 MaidBrain.registerWorkGoals 会往里追加行为
        return new ArrayList<>(List.of(Pair.of(5, new MaidSlaughterBehavior())));
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createRideBrainTasks(EntityMaid maid) {
        return new ArrayList<>(List.of(Pair.of(5, new MaidSlaughterBehavior())));
    }

    @Override
    public boolean workPointTask(EntityMaid maid) {
        return true;
    }

    /** 站桩任务不需要随机漫步（与烧制/酿造同款） */
    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid) {
        return false;
    }

    @Override
    public String getMaidActionSummary() {
        return "\u5bb0\u6740"; // 宰杀
    }
}
