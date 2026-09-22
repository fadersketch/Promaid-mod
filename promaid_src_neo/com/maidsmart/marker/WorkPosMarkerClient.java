package com.maidsmart.marker;

/**
 * 实测五百六十二：工位标记（潜行 + 鼠标中键）——粉丝反馈"不跟随状态下女仆找不准
 * 工作区域"的直接入口。客户端只负责识别手势 + 发包：
 *
 * - 中键（不潜行）= 原版取方块，不动；
 * - 潜行 + 中键方块 = 把指针指向的方块标成自家 home 模式女仆的工作区域锚点
 *   （服务端 MarkWorkPosPacket 处理：工位/休闲锚点写过去 + 立即 restrictTo），
 *   并取消事件让原版取方块不同时生效。
 *
 * 指针指向的方块用与指标石同款的服务端无关射线（Entity.pick）获取。
 */
public final class WorkPosMarkerClient {
    /** 标记射程（格）——比原版取方块略宽，够得着的都能标；服务端还有 ≤64 校验 */
    private static final double MARK_REACH = 8.0;

    private WorkPosMarkerClient() {
    }

    /** PromaidClientSetup（仅客户端分支）调用 */
    public static void register() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                WorkPosMarkerClient::onPick);
    }

    private static void onPick(
            net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isPickBlock()) {
            return;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        // 未潜行 → 完全交给原版（取方块）
        if (!mc.options.keyShift.isDown()) {
            return;
        }
        // 实测五百七十三：开关关掉 → 中键完全交还原版（取方块）
        if (!com.maidsmart.config.MaidSmartConfig.MISC_WORK_POS_MARKER.get()) {
            return;
        }
        // 实测五百七十三【与 TLM 自带的「河童的罗盘」撞车 → 让位】：
        // 罗盘（touhou_little_maid:kappa_compass）是"右键方块记坐标 → 右键女仆写入工作/休息/
        // 睡觉区域"的另一套入口，写的是**同一份 TLM 排班锚点**（谁后写谁生效）。手持罗盘时
        // 说明玩家正在用罗盘那套流程，此时我们的中键不再抢着标。
        if (holdingKappaCompass(mc)) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "\u00a77手持河童的罗盘：中键工位标记已让位（罗盘优先）；想用中键标记先收好罗盘"), true);
            return;
        }
        net.minecraft.core.BlockPos pos = pickBlock(mc);
        if (pos == null) {
            return; // 指着空气/实体 → 原版行为
        }
        event.setCanceled(true); // 潜行+中键是我们的标记手势，原版取方块不生效
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.maidsmart.schedule.SchedulePacketsMaid.MarkWorkPosPacket(
                        pos.getX(), pos.getY(), pos.getZ()));
        mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                "\u00a7a工位标记已发送（" + pos.getX() + ", " + pos.getY() + ", "
                        + pos.getZ() + "）——范围内在家模式的女仆会认这里当工作区域"), true);
    }

    /**
     * 实测五百七十三：玩家主手/副手是不是拿着 TLM 自带的「河童的罗盘」。
     * 按注册名查（不 import TLM 的物品常量，与其它软兼容同口径）；取不到恒 false。
     */
    private static boolean holdingKappaCompass(net.minecraft.client.Minecraft mc) {
        try {
            net.minecraft.world.item.Item compass =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                            net.minecraft.resources.ResourceLocation.parse("touhou_little_maid:kappa_compass"));
            if (compass == null) {
                return false;
            }
            return mc.player.getMainHandItem().is(compass) || mc.player.getOffhandItem().is(compass);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 与指标石同款的客户端射线：命中非空气方块返回坐标，否则 null */
    private static net.minecraft.core.BlockPos pickBlock(net.minecraft.client.Minecraft mc) {
        try {
            net.minecraft.world.entity.Entity cam = mc.getCameraEntity() != null
                    ? mc.getCameraEntity() : mc.player;
            net.minecraft.world.phys.HitResult hit = cam.pick(MARK_REACH,
                    mc.getTimer().getGameTimeDeltaPartialTick(false), false);
            if (hit instanceof net.minecraft.world.phys.BlockHitResult bhr
                    && bhr.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                net.minecraft.core.BlockPos p = bhr.getBlockPos();
                if (!mc.level.getBlockState(p).isAir()) {
                    return p;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
