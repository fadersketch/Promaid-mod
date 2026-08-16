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

    /**
     * v1.5.285：手册【始终显示附魔光效】（原版 enchantment glint 风格）——
     * 开局/合成/创造栏拿到的蓝图书天生带紫色闪光。
     * 修复 v1.5.251c 的历史 bug：旧代码重写 m_41463_（Item 默认返回 false 的
     * 别的方法），真正的 isFoil 是 m_5812_（javap 实证：原版附魔书
     * EnchantedBookItem 重写 m_5812_ 返回 true → 附魔光泽；m_8120_ 才是
     * isFireResistant）。旧写法光效从未生效（用户反馈"光效没出现"的根因）。
     */
    @Override
    public boolean m_5812_(ItemStack stack) {
        return true;
    }

    @Override
    public net.minecraft.world.InteractionResultHolder<ItemStack> m_7203_(Level level, Player player, InteractionHand hand) {
        if (!level.m_5776_() && player instanceof ServerPlayer serverPlayer) {
            openFor(serverPlayer, 0);
        }
        return new net.minecraft.world.InteractionResultHolder<>(
                level.m_5776_() ? net.minecraft.world.InteractionResult.SUCCESS : net.minecraft.world.InteractionResult.CONSUME,
                player.m_21120_(hand));
    }

    /** v1.5.275：打开手册（服务端逻辑抽出——右键手册 / 配置面板"跳转女仆管理"共用；
     *  initialView：0=大目录 1=女仆管理） */
    public static void openFor(ServerPlayer serverPlayer, int initialView) {
        Level level = serverPlayer.m_9236_();
        // v1.5.24：材料缺口以【主人背包】为准（0/N）——确认后由服务端自动交付给女仆
        List<BlueprintBookNetworking.Entry> entries = new ArrayList<>();
        for (Map.Entry<String[], Map<String, int[]>> e
                : BlueprintLib.buildCatalogEntriesWithMaterials(serverPlayer).entrySet()) {
            String[] base = e.getKey();
            // v1.5.159：占地尺寸（区块显示预览用）——v1.5.375 走缓存（启动预热）
            int[] size = BlueprintLib.blueprintSizeCached(base[0], BlueprintLib.getBlueprint(base[0]));
            entries.add(new BlueprintBookNetworking.Entry(base[0], base[1], base[2],
                    new ArrayList<>(e.getValue().entrySet().stream()
                            .map(m -> new String[]{m.getKey(), String.valueOf(m.getValue()[0]), String.valueOf(m.getValue()[1])})
                            .toList()),
                    size[0], size[1], size[2]));
        }
        net.minecraft.server.level.ServerLevel sl = net.minecraft.server.level.ServerLevel.class.isInstance(level)
                ? (net.minecraft.server.level.ServerLevel) level : null;
        // v1.5.162：计划区块标记（中心点 + 尺寸，兼容字段）
        int[] region = BlueprintBookNetworking.collectRegion(sl);
        // v1.5.180：区块内右击手册 → 该区块详情页（inPlanRegion + 蓝图 id）；
        // 区块外右击 → 正常目录（全新蓝图）
        BuildPlan.PlanState here = sl == null ? null
                : BlueprintBookNetworking.findPlayerPlan(sl, serverPlayer);
        // v1.5.252z：打开手册立即显示速度/ETA（不等 2 秒轮询）
        double[] se = here == null ? null : com.maidsmart.build.BuildHudTracker.speedEtaOf(here.planId);
        int openEta = se == null ? -1 : (int) Math.round(se[1]);
        String openBps = se == null ? "" : String.format("%.1f", se[0]);
        // v1.5.252ad：打开手册诊断（latest.log 搜 "hud book"）——确认打开瞬间速度值
        com.maidsmart.build.BlueprintBookNetworking.logBookSpeed("open", here == null ? null : here.planId, openBps, openEta);
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
                        sl == null ? new ArrayList<>() : BlueprintBookNetworking.collectBuildRegions(sl.m_7654_()),
                        openEta, openBps, initialView));
    }
}
