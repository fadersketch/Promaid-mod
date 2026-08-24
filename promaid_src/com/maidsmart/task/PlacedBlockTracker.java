package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * v1.1.0 实测四十二：女仆搭方块统一追踪器——【绑定搭建者】的到期回收。
 *
 * 旧机制的问题（用户："垫脚方块收回背包这个机制有问题——女仆离得远方块就掉了，
 * 长途搭建场景必掉"）：回收时找【附近 8 格】的女仆塞背包，找不到就落地——长途
 * 搭建（搭路去远处的矿/树）到期时女仆早走远了，方块全部掉地上。
 *
 * 新机制：
 * 1. 【绑定搭建者】：登记时记录放置女仆的 UUID；到期销毁时【跨维度】找到该女仆
 *    （同维度直接进背包；异维度也塞——IItemHandler 与位置无关），强制回收进她
 *    背包；背包满才走落地流程（剩余掉落物 popResource）。
 * 2. 【魂符收回暂停计时】：女仆不在任何维度的实体列表（被魂符收回/区块卸载）时，
 *    她名下的方块【暂停倒计时】（每 tick 记录剩余寿命，重载后从剩余时间继续）；
 *    女仆回到世界后计时恢复。
 * 3. 女仆站在方块上仍刷新计时（实测十八同款保护，防脚下塌陷摔落）。
 * 4. 女仆永久消失（死亡/删除）→ 名下方块立即到期回收（不再有主人，别挂着）。
 *
 * 四套搭方块表（挖矿/伐木/搭路/自保）共用本类，各自一个实例（寿命/销毁回调不同）。
 */
public final class PlacedBlockTracker {

    /** 单块追踪记录：剩余寿命（tick）+ 方块注册名 + 绑定女仆 UUID */
    public record Mark(long remainTicks, String blockId, java.util.UUID maidUuid) {
    }

    private final Map<ResourceKey<Level>, Map<BlockPos, Mark>> placed = new HashMap<>();
    /** 寿命（tick，从配置读——构造时传入当前配置值；expirePlaced 每次重读以支持运行时改配置） */
    private final java.util.function.LongSupplier lifetimeSupplier;

    public PlacedBlockTracker(java.util.function.LongSupplier lifetimeSupplier) {
        this.lifetimeSupplier = lifetimeSupplier;
        ALL_INSTANCES.add(this); // 实测七十一：自动登记进全局表（供跨系统查询）
    }

    /** 登记一个搭方块（绑定放置女仆） */
    public void track(ServerLevel level, BlockPos pos, Block block, EntityMaid maid) {
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
        placed.computeIfAbsent(level.m_46472_(), k -> new HashMap<>())
                .put(pos.m_7949_(), new Mark(lifetimeSupplier.getAsLong(),
                        key != null ? key.toString() : "", maid.m_20148_()));
    }

    /** 该位置是否是本追踪器登记的方块（挡路判定/防误挖用） */
    public boolean isPlaced(Level level, BlockPos pos) {
        Map<BlockPos, Mark> marks = placed.get(level.m_46472_());
        return marks != null && marks.containsKey(pos.m_7949_());
    }

    /** v1.1.0 实测七十一（用户反馈："伐木状态+搭路开着，女仆会砍自己搭路的方块"）：
     *  全部实例登记表——「是否女仆搭的方块」必须【跨系统】查询。旧版四套表各自为政，
     *  伐木只查伐木表 → 搭路垫的原木桥不在保护名单里；搭路选材又是"背包最多的
     *  可放置方块"（= 刚砍下的原木）→ 女仆把脚下的桥当树砍掉、自己摔下去。 */
    private static final java.util.List<PlacedBlockTracker> ALL_INSTANCES =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 该位置是否是【任何系统】（挖矿/伐木/搭路/自保）登记的女仆搭方块——防误挖统一口径 */
    public static boolean isAnyPlaced(Level level, BlockPos pos) {
        for (PlacedBlockTracker t : ALL_INSTANCES) {
            if (t.isPlaced(level, pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 每 tick 到期扫描。女仆站在上面 → 刷新剩余寿命；绑定女仆不在线 → 暂停倒计时；
     * 到期 → 销毁并强制回收进绑定女仆背包（跨维度；背包满落地）。
     */
    public void expirePlaced(net.minecraft.server.MinecraftServer server, long gameTime,
                             Predicate<BlockPos> stoodOnCheck) {
        long lifetime = lifetimeSupplier.getAsLong();
        for (ServerLevel level : server.m_129785_()) {
            Map<BlockPos, Mark> marks = placed.get(level.m_46472_());
            if (marks == null || marks.isEmpty()) {
                continue;
            }
            Iterator<Map.Entry<BlockPos, Mark>> it = marks.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<BlockPos, Mark> e = it.next();
                BlockPos pos = e.getKey();
                Mark mark = e.getValue();
                // 实测四十七：绑定女仆本人站上方块 → 恒刷新（她挖矿/伐木的"挖矿中"
                // 标记在空闲扫描期会短暂移除，但脚下还是自己的垫块——不刷新就塌）。
                // 谓词 stoodOnCheck 覆盖其他场景（同任务姐妹借踩/搭路任意踩）。
                boolean ownerOn = false;
                EntityMaid owner = findMaid(server, mark.maidUuid());
                if (owner != null) {
                    BlockPos feet = owner.m_20183_();
                    if (feet.m_7949_().equals(pos) || feet.m_7918_(0, -1, 0).m_7949_().equals(pos)) {
                        ownerOn = true;
                    }
                }
                // 女仆站在上面 → 刷新寿命（防脚下塌陷；实测十八同款）
                if (ownerOn || stoodOnCheck.test(pos)) {
                    e.setValue(new Mark(lifetime, mark.blockId(), mark.maidUuid()));
                    continue;
                }
                if (owner == null) {
                    // 绑定女仆不在线（魂符收回/区块卸载）→ 暂停倒计时（remainTicks 不减）
                    continue;
                }
                long remain = mark.remainTicks() - 1;
                if (remain > 0) {
                    e.setValue(new Mark(remain, mark.blockId(), mark.maidUuid()));
                    continue;
                }
                // 到期：销毁 + 强制回收进绑定女仆背包（跨维度；背包满落地）
                it.remove();
                destroyAndReclaim(level, pos, mark, owner);
            }
        }
    }

    /** 服务器停止/启动清场（残留方块立即销毁回收；绑定女仆不在线则落地） */
    public void clearAll(net.minecraft.server.MinecraftServer server) {
        for (ServerLevel level : server.m_129785_()) {
            Map<BlockPos, Mark> marks = placed.remove(level.m_46472_());
            if (marks == null) {
                continue;
            }
            for (Map.Entry<BlockPos, Mark> e : marks.entrySet()) {
                EntityMaid owner = findMaid(server, e.getValue().maidUuid());
                destroyAndReclaim(level, e.getKey(), e.getValue(), owner);
            }
        }
        placed.clear();
    }

    /** 跨维度找绑定女仆（同维度优先；任何维度在线即算）。
     *  server 引用由 expirePlaced/clearAll 的调用方透传（MinecraftServer 可拿全维度） */
    private static EntityMaid findMaid(net.minecraft.server.MinecraftServer server, java.util.UUID uuid) {
        for (ServerLevel lvl : server.m_129785_()) {
            net.minecraft.world.entity.Entity e = lvl.m_8791_(uuid);
            if (e instanceof EntityMaid m && m.m_6084_()) {
                return m;
            }
        }
        return null;
    }

    /**
     * 销毁一个到期方块并回收：绑定女仆在线 → 掉落物强制塞她背包（跨维度；
     * 背包满/塞不下 → 落地）；女仆 null（清场时已离线）→ 落地。
     * 玩家替换过的方块不误破坏（blockId 比对，同旧口径）。
     */
    private static void destroyAndReclaim(ServerLevel level, BlockPos pos, Mark mark,
                                          EntityMaid owner) {
        BlockState state = level.m_8055_(pos);
        if (state.m_60795_()) {
            return;
        }
        ResourceLocation cur = ForgeRegistries.BLOCKS.getKey(state.m_60734_());
        if (!mark.blockId().isEmpty() && cur != null && !mark.blockId().equals(cur.toString())) {
            return; // 玩家已替换，尊重改动
        }
        level.m_46796_(2001, pos, Block.m_49956_(state));
        java.util.List<ItemStack> drops = Block.m_49869_(state, level, pos, null);
        boolean handed = false;
        if (owner != null && !drops.isEmpty()) {
            try {
                // 跨维度回收：直接操作女仆背包（IItemHandler 与位置无关）
                IItemHandler inv = owner.getMaidInv();
                for (ItemStack stack : drops) {
                    if (stack.m_41619_()) {
                        continue;
                    }
                    ItemStack remain = net.minecraftforge.items.ItemHandlerHelper
                            .insertItemStacked(inv, stack, false);
                    if (!remain.m_41619_()) {
                        // 背包满：落地（原版 popResource）
                        Block.m_49840_(level, pos, remain);
                    }
                }
                handed = true;
            } catch (Exception ignored) {
            }
        }
        if (!handed && !drops.isEmpty()) {
            for (ItemStack stack : drops) {
                Block.m_49840_(level, pos, stack);
            }
        }
        level.m_7731_(pos, net.minecraft.world.level.block.Blocks.f_50016_.m_49966_(), 3);
    }

    /**
     * v1.1.0 实测四十六：女仆放方块的【玩家同款】放置音效。
     * 旧实现用 levelEvent 3001 + Block.m_49956_(state)（Block id）——客户端
     * LevelEvent handler 对 3001 的附加数据按【BlockState id】走的是原版注释/
     * 事件路径，mod 环境下 id 与注册表错位会被解析成无关音效（用户听成"咆哮"）。
     * 改为服务端直接 playSound：取方块自身 SoundType 的放置音效
     * （m_56777_ = getPlaceSound），音量/音调按原版 BlockItem 放置公式
     * （(volume+1)/2, pitch*0.8）——与玩家放方块完全一致。
     */
    public static void placeSound(net.minecraft.server.level.ServerLevel level,
                                  net.minecraft.core.BlockPos pos,
                                  net.minecraft.world.level.block.Block block) {
        net.minecraft.world.level.block.state.BlockState state = block.m_49966_();
        net.minecraft.world.level.block.SoundType st = state.m_60827_();
        level.m_5594_(null, pos, st.m_56777_(), net.minecraft.sounds.SoundSource.BLOCKS,
                (st.m_56773_() + 1.0f) / 2.0f, st.m_56774_() * 0.8f);
    }

    /** 兼容旧调用：某位置附近是否有活的女仆（旧 supportsAnyMiner/supportsBridger 判定保留用） */
    public static boolean anyMaidStanding(ServerLevel level, BlockPos pos,
                                          Predicate<EntityMaid> filter) {
        for (EntityMaid m : level.m_45976_(EntityMaid.class,
                new net.minecraft.world.phys.AABB(pos).m_82400_(2.0))) {
            if (!m.m_6084_() || !filter.test(m)) {
                continue;
            }
            BlockPos feet = m.m_20183_();
            if (feet.m_7949_().equals(pos) || feet.m_7918_(0, -1, 0).m_7949_().equals(pos)) {
                return true;
            }
        }
        return false;
    }
}
