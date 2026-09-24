package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraftforge.items.IItemHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 战斗中的 TNT 节拍（v1.2.4 从 MaidBombing 拆出）。
 * 
 * 远程/近战的 TNT 投放节拍与攻击链结束时的一次清投。
 */
public final class BombTntTick {
    private BombTntTick() {
    }

    public static void tickRangedTnt(ServerLevel level, EntityMaid maid, LivingEntity target, UUID id, long gameTime) {
        try {
            if (level == null || maid == null || target == null || (!BombConfig.cfgTnt() && !BombConfig.cfgMelee())) {
                return;
            }
            if (com.maidsmart.compat.MaidModeCompat.isSuspended(maid)) {
                return; // 傀儡模式（第三方玩法）期间不介入
            }
            // v1.2.2 实测五百九十【改挂攻击链路】：本方法由空袭远程链路在 fireRanged
            // **之后**调用 = 「这一次开火打完」，所以直接走链路收尾（最短间隔只当下限）
            if (BombConfig.cfgMelee() && MaidBombing.tryStartMelee(level, maid, target)) {
                return; // 起手成功：TNT 归相位收尾（与猛击那条路一致）
            }
            onAttackChainEnd(level, maid, target);
        } catch (Throwable t) {
            MaidBombing.log("投掷异常：" + t);
        }
    }

    public static void tickCombatTnt(ServerLevel level, EntityMaid maid) {
        try {
            // v1.2.2 实测五百九十二：这条扫描现在同时负责【轰炸起手】与【TNT 投放】，所以只要
            // 两个开关里有一个开着就得跑（各自的部分再各自判开关）
            if (level == null || maid == null || (!BombConfig.cfgTnt() && !BombConfig.cfgMelee())) {
                return;
            }
            // v1.2.2 实测五百九十：傀儡模式（第三方玩法）期间不介入
            if (com.maidsmart.compat.MaidModeCompat.isSuspended(maid)) {
                return;
            }
            // v1.3.6 实测六百六十一：扫帚模式不放放置类战术（TNT / 末地水晶 / 重生锚 / 床），
            // 只留主手武器输出。这一条是**所有战斗任务**的入口，所以闸放在这里最省事也最全。
            if (com.maidsmart.combat.MaidBroomKit.forbidsBombing(maid)) {
                return;
            }
            if (!com.maidsmart.task.MaidWorkTags.isCombatTask(maid)) {
                return; // 非战斗任务不扔（干活/待机/跟随都不打扰）
            }
            if (!maid.m_6084_() || maid.m_213877_()) {
                return;
            }
            UUID id = maid.m_20148_();
            long gameTime = level.m_46467_();
            // ① 目标先解出来（轰炸与 TNT 共用同一套目标口径：记忆优先、不合法再按半径找）
            LivingEntity target = null;
            try {
                target = maid.m_6274_().m_21952_(MemoryModuleType.f_26372_).orElse(null);
            } catch (Throwable ignored) {
            }
            if (!BombThrow.legalThrowTarget(maid, target)) {
                target = BombThrow.findThrowTarget(level, maid); // 记忆里的目标不合法（友军/非敌人）再按半径找
            }
            // ② 攻击链路完成检测：攻击冷却记忆【由无到有】= 她刚打完一记（TLM 近战与远程
            //    攻击都会写这个记忆）——轰炸与 TNT 都挂在「打完这一记」后面
            boolean cooling = maid.m_6274_().m_21952_(MemoryModuleType.f_26373_).isPresent();
            Boolean prev = MaidBombing.COOLDOWN_SEEN.put(id, cooling);
            if (cooling && (prev == null || !prev)) {
                MaidBombing.TNT_ARMED.put(id, gameTime);
                // ③ v1.2.2 实测五百九十二【轰炸推广到所有攻击模式】：这一记打完 → 先试轰炸起手
                //    （黑曜石+末地水晶 / 重生锚+萤石 / 床）。起手成功则整段交给轰炸相位，
                //    相位收尾自己会投 TNT（见 tick 末尾的 flushTnt）——所以这里直接返回。
                //    v1.2.2 实测六百〇三：远程空袭那道闸**删掉**（需求："将重生锚之类的放置
                //    也加入到远程空袭"）——她飞在天上也照放，落点是目标脚边 / 她正下方 /
                //    悬空 / 最后走 tryPlaceAirDrop 的后门。
                if (BombConfig.cfgMelee() && MaidBombing.tryStartMelee(level, maid, target)) {
                    return;
                }
            }
            // ④ 剩下的才是 TNT 那一发
            if (!BombConfig.cfgTnt()) {
                return;
            }
            Long armedAt = MaidBombing.TNT_ARMED.get(id);
            if (armedAt == null) {
                return;
            }
            if (gameTime - armedAt > MaidBombing.ARMED_TIMEOUT) {
                MaidBombing.TNT_ARMED.remove(id); // 挂太久：作废
                return;
            }
            if (gameTime < MaidBombing.TNT_NEXT.getOrDefault(id, 0L)) {
                return; // 最短间隔（默认 200 tick = 10 秒）没到：挂着，等下一记
            }
            flushTnt(level, maid, target, id, gameTime);
        } catch (Throwable t) {
            MaidBombing.log("战斗投掷异常：" + t);
        }
    }

    public static void onAttackChainEnd(ServerLevel level, EntityMaid maid, LivingEntity target) {
        try {
            if (level == null || maid == null || !BombConfig.cfgTnt()) {
                return;
            }
            if (com.maidsmart.compat.MaidModeCompat.isSuspended(maid)) {
                return; // 傀儡模式（第三方玩法）期间不介入
            }
            UUID id = maid.m_20148_();
            long gameTime = level.m_46467_();
            MaidBombing.TNT_ARMED.put(id, gameTime);
            flushTnt(level, maid, target, id, gameTime);
        } catch (Throwable t) {
            MaidBombing.log("链路投掷异常：" + t);
        }
    }

    static void flushTnt(ServerLevel level, EntityMaid maid, LivingEntity target, UUID id, long gameTime) {
        try {
            if (level == null || maid == null || !BombConfig.cfgTnt()) {
                return;
            }
            if (com.maidsmart.compat.MaidModeCompat.isSuspended(maid)) {
                return;
            }
            if (!BombThrow.legalThrowTarget(maid, target)) {
                return; // 没目标（或目标不合法）：挂着等下一记
            }
            if (gameTime < MaidBombing.TNT_NEXT.getOrDefault(id, 0L)) {
                return; // 最短间隔没到
            }
            if (!BombItems.hasTnt(maid) || !BombItems.hasIgniter(maid)) {
                return; // 不刚需：缺料直接跳过（不占用间隔）——v1.2.2 实测六百〇三：点火料 = 打火石 或 烈焰弹；
                        // 实测六百〇四：TNT 判据放宽，注册名里带 tnt 的模组 TNT 也算（见 isTnt）；
                        // 实测六百〇五：判据再加"方块继承 TntBlock"那条，且放出的是它自己那一枚（见 primeTnt）
            }
            if (BombThrow.throwTntAt(level, maid, target, id, gameTime) > 0) {
                MaidBombing.TNT_ARMED.remove(id);
            }
        } catch (Throwable t) {
            MaidBombing.log("投放异常：" + t);
        }
    }
}
