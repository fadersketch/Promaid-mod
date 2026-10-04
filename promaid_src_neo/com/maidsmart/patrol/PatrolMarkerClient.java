package com.maidsmart.patrol;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * v1.3.9【巡逻航图】标记手势（鼠标中键）——纯客户端。
 *
 * <p>── 玩家原话（这一版的改动）──
 * 「Shift加中键，还是以玩家当前所在的位置作为标记的点，这个操作太反人类了。应该改成直接拿着此物品
 * 进行中键的时候就算记一个标记。」+「如果你没有点击这个开始标记这个方式，那么你使用中键是没有
 * 任何用处的。也就是说，你只是一开始就把巡逻航图拿在手上中键是没用的。」
 * v1.3.9.2：「玩家在空中使用鼠标中键进行标记还是没用的。」⇒ **空中也要能打**。
 *
 * <p>── 【v1.3.9.2 关键修复】为什么不能靠 {@code InteractionKeyMappingTriggered} ──
 * 中键（取方块）那个事件是 NeoForge 挂在 {@code Minecraft.pickBlock()} **里面**的，而
 * {@code pickBlock()} 第一行就是（javap 反编译实证）：
 * <pre>
 *   if (hitResult == null || hitResult.getType() == HitResult.Type.MISS) return;   ← 事件根本没机会发
 *   if (ClientHooks.onClickInput(2, options.keyPickItem, MAIN_HAND).isCanceled()) return;
 * </pre>
 * 也就是说：**准星指着空气/天空时按中键，事件压根不触发**——玩家"开着创造模式飞在空中按中键"
 * 正是这种情况（实测日志：站在地上看着方块按那一次成功记下了标记，天上怎么按都没有任何反应）。
 * 所以手势改成**自己取按键**：在 {@code ClientTickEvent.Pre}（早于本拍的 {@code handleKeybinds()}，
 * 见 javap：tick() 里 fireClientTickPre 在偏移 10、handleKeybinds 在 366）里
 * {@code consumeClick()} 掉中键——拿航图时这一下就完全是我们的，原版取方块不会发生；
 * 没拿航图时一个字节都不碰，原版照旧。
 *
 * <p>⇒ 两条规矩：<b>① 拿着航图中键＝标记手势</b>（不用潜行，空中地面都行）；
 * <b>② 必须先"开始标记"</b>，否则只回一句提示。落点永远是服务端按玩家自己的位置取的
 * （客户端发的包里没有坐标，见 {@code PatrolNetworking.MarkPatrolPointPacket}）。
 *
 * <p>【与 {@code WorkPosMarkerClient} 的关系】那边是"潜行+中键标工位"，写的是 TLM 排班锚点。
 * 手持巡逻航图时这一整套让位给本类（见那边的 {@code holdingPatrolChart}）——一次手势只有一个意思。
 */
public final class PatrolMarkerClient {

    /** "还没开始标记"这条提示最多多久说一次（毫秒）——免得玩家连按中键刷屏 */
    private static final long HINT_COOLDOWN = 1500L;
    private static long lastHint;

    private PatrolMarkerClient() {
    }

    /** 由 {@code PromaidClientSetup} 在客户端分支注册 */
    public static void register() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                PatrolMarkerClient::onClientTickPre);
    }

    /**
     * 每拍开始（早于原版 {@code handleKeybinds()}）自己取一次中键。见类注释里为什么不能等事件。
     */
    private static void onClientTickPre(
            net.neoforged.neoforge.client.event.ClientTickEvent.Pre event) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.screen != null) {
                return;
            }
            // 没拿巡逻航图：一个字节都不动，中键照旧是原版取方块
            if (!holdingChart(mc)) {
                return;
            }
            boolean pressed = false;
            while (mc.options.keyPickItem.consumeClick()) {
                pressed = true;
            }
            if (!pressed) {
                return;
            }
            // 拿了航图 → 中键这一下归我们（原版取方块不会再发生）
            logPick(mc);
            if (!PatrolPreviewClient.marking()) {
                hintThrottled(mc, "\u00a77中键打标记要先在航图的轨道管理里点「\u00a7a开始标记\u00a77」");
                return;
            }
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new PatrolNetworking.MarkPatrolPointPacket());
        } catch (Throwable ignored) {
        }
    }

    /** 每次中键（手持航图那一下）留一行痕：日志搜「巡逻航图」，用来判断"手势到底有没有被识别" */
    private static void logPick(Minecraft mc) {
        try {
            com.maidsmart.tool.PromaidLog.log("巡逻航图", "client 中键被识别 marking="
                    + PatrolPreviewClient.marking()
                    + " onGround=" + mc.player.onGround()
                    + " riding=" + (mc.player.getVehicle() == null ? "否" : "是")
                    + " 手上=" + (PatrolChartKit.isChart(mc.player.getMainHandItem()) ? "主手" : "副手"));
        } catch (Throwable ignored) {
        }
    }

    /** 节流版动作栏提示（同一条最多 {@link #HINT_COOLDOWN} 毫秒一次，免得连按刷屏） */
    private static void hintThrottled(Minecraft mc, String text) {
        long now = System.currentTimeMillis();
        if (now - lastHint > HINT_COOLDOWN) {
            lastHint = now;
            mc.player.displayClientMessage(Component.literal(text), true);
        }
    }

    private static boolean holdingChart(Minecraft mc) {
        try {
            return PatrolChartKit.isChart(mc.player.getMainHandItem())
                    || PatrolChartKit.isChart(mc.player.getOffhandItem());
        } catch (Throwable ignored) {
            return false;
        }
    }
}
