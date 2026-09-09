package com.maidsmart.fishing;

/**
 * v1.5.257：玩家水行为日志（latest.log 搜 "player water"）——记录玩家放置/收走
 * 水（及岩浆）的位置与方块名，配合 auto-chair 日志定位"女仆识别不到水"是
 * 距离问题还是判定问题（用户实测"草地上挖一格放水，女仆毫无反应"——需要知道
 * 水放哪了，才能判断是扫描范围不够还是判定失败）。
 * 只记录液体方块行为（防刷屏）；玩家移动/其他方块操作不记。
 */
public class PlayerWaterLog {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(PlayerWaterLog.class);

    /** 玩家用水桶（放水/收水）——水桶放水不触发 EntityPlaceEvent（BucketItem 直接
     *  setBlock，无 placedBy），必须单独监听右键使用 */
    @net.neoforged.bus.api.SubscribeEvent
    public void onUseBucket(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem event) {
        net.minecraft.resources.ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(event.getItemStack().getItem());
        if (id == null) {
            return;
        }
        String act = "minecraft:water_bucket".equals(id.toString()) ? "用水桶放水"
                : "minecraft:bucket".equals(id.toString()) ? "用水桶收水" : null;
        if (act == null) {
            return;
        }
        net.minecraft.world.entity.player.Player player = event.getEntity();
        LOGGER.info("player water: 玩家 {} 在 ({}, {}, {}) {}（面朝 {}）",
                player.getDisplayName().getString(),
                (int) Math.floor(player.getX()), (int) Math.floor(player.getY()),
                (int) Math.floor(player.getRandomY()), act,
                player.getDirection());
    }

    /** 玩家收走水（水桶装水/破坏水源） */
    @net.neoforged.bus.api.SubscribeEvent
    public void onBreak(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
        net.minecraft.world.level.block.state.BlockState state = event.getState();
        if (!isLiquidWater(state)) {
            return;
        }
        net.minecraft.core.BlockPos p = event.getPos();
        net.minecraft.resources.ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock());
        LOGGER.info("player water: 玩家 {} 在 ({}, {}, {}) 收走 {}",
                event.getPlayer().getDisplayName().getString(), p.getX(), p.getY(), p.getZ(),
                id == null ? "?" : id);
    }

    /** 玩家放置水（水桶倒水等） */
    @net.neoforged.bus.api.SubscribeEvent
    public void onPlace(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event) {
        // BlockSnapshot 是 Forge 类（mojmap 名）；getCurrentBlock = 放置后的方块
        net.minecraft.world.level.block.state.BlockState state = event.getBlockSnapshot().getCurrentState();
        if (!isLiquidWater(state)
                || !(event.getEntity() instanceof net.minecraft.world.entity.player.Player player)) {
            return;
        }
        net.minecraft.core.BlockPos p = event.getPos();
        net.minecraft.resources.ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock());
        LOGGER.info("player water: 玩家 {} 在 ({}, {}, {}) 放置 {}",
                player.getDisplayName().getString(), p.getX(), p.getY(), p.getZ(),
                id == null ? "?" : id);
    }

    /** 液体且是水（岩浆也记——防玩家在岩浆边测试迷惑）
     *  v1.5.259：canOcclude 是 isSolid——旧版用它当 isLiquid 导致永远 false */
    private static boolean isLiquidWater(net.minecraft.world.level.block.state.BlockState state) {
        return state.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
    }
}
