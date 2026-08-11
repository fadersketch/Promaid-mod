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
 * maid_smart:mine —— 挖矿任务。
 * 女仆在附近寻找矿石，手持镐开采（掉落物由拾取模式处理）。
 * 这是主模组缺失、社区呼声最高的任务。
 */
public class MaidMineTask implements IMaidTask {
    public static final ResourceLocation UID = ResourceLocation.parse("maid_smart:mine");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public ItemStack getIcon() {
        return new ItemStack(ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("minecraft:diamond_pickaxe")));
    }

    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) {
        return null;
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        // 必须返回可变列表：TLM 的 MaidBrain.registerWorkGoals 会往里追加行为
        return new ArrayList<>(List.of(Pair.of(5, new MaidMineBehavior())));
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createRideBrainTasks(EntityMaid maid) {
        // v1.5.25：坐下/坐垫/骑乘时（TLM 把 activity 切到 RIDE_WORK）任务行为仍运行。
        // TLM 默认 createRideBrainTasks 返回空列表 → 坐下后任务全部停止（"不站桩"的
        // 终极保险：坐下 = 物理不动，但任务照常执行）
        return new ArrayList<>(List.of(Pair.of(5, new MaidMineBehavior())));
    }

    @Override
    public boolean workPointTask(EntityMaid maid) {
        return true;
    }

    /**
     * v1.5.123：关闭 TLM 的"随机散步"（WORK activity 优先级 20 的 getLookAndRandomWalk
     * 内含 RandomStroll.create(0.3f, 5, 3)——IMaidTask.enableLookAndRandomWalk 默认
     * 返回 true，女仆挖矿时它每 60~120 tick 用【随机 5 格内目标、速度 0.3】覆盖我们
     * 设向矿的 WALK_TARGET → 女仆被反复拉去随机散步、"走一步停一下"、效率极低
     * （与距离无关）。挖矿移动完全由本行为经 WALK_TARGET→MoveToTargetSink 驱动，
     * 不需要随机散步；TLM 伐木不受影响（它用直接导航 navigation.moveTo，不经过
     * WALK_TARGET）。关闭后散步/看向/DoNothing 整个 MaidRunOne 不再启动。
     */
    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid) {
        return false;
    }

    @Override
    public String getMaidActionSummary() {
        return "mining ores with a pickaxe";
    }
}
