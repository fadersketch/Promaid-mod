package com.maidsmart.build;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Promaid 手册（v1.5.16）：非 LLM 的完整建造入口。
 * 右键打开蓝图列表 GUI（内置 + 外部 + 预制全部图纸），点击即让附近女仆建造。
 * 服务端 use → 下发目录包 → 客户端打开 BlueprintBookScreen。
 */
public class BlueprintBookItem extends Item {

    public BlueprintBookItem(Properties properties) {
        super(properties);
    }

    /** v1.5.251c：手册【始终显示附魔光效】（原版 enchantment glint 风格）——
     *  v1.5.251e：加 10 秒节流日志验证客户端调用链（用户反馈光效没出现，
     *  latest.log 搜 "foil check" 确认 isFoil 确实被渲染端调用） */
    private static long LAST_FOIL_LOG = 0L;

    @Override
    public boolean m_41463_(ItemStack stack) {
        long now = System.currentTimeMillis();
        if (now - LAST_FOIL_LOG > 10000) {
            LAST_FOIL_LOG = now;
            org.slf4j.Logger log = com.mojang.logging.LogUtils.getLogger();
            log.info("foil check: blueprint_book isFoil called (glint active)");
        }
        return true;
    }

    @Override
    public net.minecraft.world.InteractionResultHolder<ItemStack> m_7203_(Level level, Player player, InteractionHand hand) {
        if (!level.m_5776_() && player instanceof ServerPlayer serverPlayer) {
            // v1.5.24：材料缺口以【主人背包】为准（0/N）——确认后由服务端自动交付给女仆
            List<BlueprintBookNetworking.Entry> entries = new ArrayList<>();
            for (Map.Entry<String[], Map<String, int[]>> e
                    : BlueprintLib.buildCatalogEntriesWithMaterials(serverPlayer).entrySet()) {
                String[] base = e.getKey();
                // v1.5.159：占地尺寸（区块显示预览用）
                int[] size = BlueprintLib.blueprintSize(BlueprintLib.getBlueprint(base[0]));
                entries.add(new BlueprintBookNetworking.Entry(base[0], base[1], base[2],
                        new ArrayList<>(e.getValue().entrySet().stream()
                                .map(m -> new String[]{m.getKey(), String.valueOf(m.getValue()[0]), String.valueOf(m.getValue()[1])})
                                .toList()),
                        size[0], size[1], size[2]));
            }
            // v1.5.48：移除打开时的系统消息噪音（"[maid_smart] 手册条目数"）——
            // 条目数在手册标题直接可见
            net.minecraft.server.level.ServerLevel sl = net.minecraft.server.level.ServerLevel.class.isInstance(level)
                    ? (net.minecraft.server.level.ServerLevel) level : null;
            // v1.5.162：计划区块标记（中心点 + 尺寸，兼容字段）
            int[] region = BlueprintBookNetworking.collectRegion(sl);
            // v1.5.180：区块内右击手册 → 该区块详情页（inPlanRegion + 蓝图 id）；
            // 区块外右击 → 正常目录（全新蓝图）
            BuildPlan.PlanState here = sl == null ? null
                    : BlueprintBookNetworking.findPlayerPlan(sl, serverPlayer);
            BlueprintBookNetworking.sendToPlayer(serverPlayer,
                    new BlueprintBookNetworking.OpenBlueprintBookPacket(
                            entries, BlueprintBookNetworking.collectMaidStatus(serverPlayer),
                            sl == null ? new ArrayList<>() : BlueprintBookNetworking.collectAllMaids(sl),
                            here != null && here.paused,
                            MaidBuildBehavior.speedLabel(),
                            sl == null ? "" : BlueprintBookNetworking.buildProgressText(sl, serverPlayer),
                            sl == null ? -1 : BlueprintBookNetworking.buildProgressPct(sl, serverPlayer),
                            region[0], region[1], region[2], region[3], region[4], region[5],
                            here != null, here == null ? null : here.blueprintId,
                            sl == null ? new ArrayList<>() : BlueprintBookNetworking.collectBuildRegions(sl.m_7654_())));
        }
        return new net.minecraft.world.InteractionResultHolder<>(
                level.m_5776_() ? net.minecraft.world.InteractionResult.SUCCESS : net.minecraft.world.InteractionResult.CONSUME,
                player.m_21120_(hand));
    }
}
